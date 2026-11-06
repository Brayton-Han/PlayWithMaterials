import { act, renderHook } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { bumpSessionEpoch, getSessionEpoch, resetUserScopedState } from "@/features/auth/session";
import { ApiError, setUnauthorizedHandler } from "@/lib/api/client";
import type { Task } from "@/lib/api/endpoints/tasks";
import { makeTask } from "@/test/fixtures";
import { server } from "@/test/msw/server";

import { taskStore, type TaskScope } from "./taskStore";
import { backoffDelay, MAX_BACKOFF_MS, TRACK_INTERVAL_MS, toQueryError, useTaskTracker } from "./useTaskTracker";

const T0 = "2026-11-05T08:00:00.000Z";

function at(seconds: number): string {
  return new Date(Date.parse(T0) + seconds * 1000).toISOString();
}

function processing(taskId: string, seconds: number, extra: Partial<Task> = {}): Task {
  return makeTask({ taskId, status: "PROCESSING", stage: "GENERATING", startedAt: at(0), updatedAt: at(seconds), ...extra });
}

function succeeded(taskId: string, seconds: number, extra: Partial<Task> = {}): Task {
  return makeTask({
    taskId,
    status: "SUCCEEDED",
    stage: "RESULT_ORGANIZING",
    resultUrl: `/files/results/${taskId}/r.png`,
    startedAt: at(0),
    finishedAt: at(seconds),
    updatedAt: at(seconds),
    ...extra,
  });
}

function failed(taskId: string, seconds: number): Task {
  return makeTask({
    taskId,
    status: "FAILED",
    stage: "GENERATING",
    failedStage: "GENERATING",
    failureCode: "SIMULATED_FAILURE",
    failureReason: "模拟失败：生成阶段出错",
    startedAt: at(0),
    finishedAt: at(seconds),
    updatedAt: at(seconds),
  });
}

type Reply = Task | "404" | "500" | "401" | "network" | Promise<Task>;

/** 记录 GET /api/tasks/:taskId 的请求；`replies[taskId]` 决定每次的响应（函数可按调用次数返回不同结果）。 */
function mockGetTask(replies: Record<string, Reply | ((call: number) => Reply)>) {
  const calls: string[] = [];
  const signals: AbortSignal[] = [];
  server.use(
    http.get("/api/tasks/:taskId", async ({ params, request }) => {
      const taskId = String(params.taskId);
      const call = calls.filter((id) => id === taskId).length;
      calls.push(taskId);
      signals.push(request.signal);
      const spec = replies[taskId];
      const reply = typeof spec === "function" ? spec(call) : spec;
      if (reply === undefined || reply === "404") {
        return HttpResponse.json({ code: "TASK_NOT_FOUND", message: "任务不存在" }, { status: 404 });
      }
      if (reply === "500") return HttpResponse.json({ code: "INTERNAL_ERROR", message: "服务器内部错误" }, { status: 500 });
      if (reply === "401") return HttpResponse.json({ code: "UNAUTHENTICATED", message: "请先登录" }, { status: 401 });
      if (reply === "network") return HttpResponse.error();
      return HttpResponse.json(await reply);
    }),
  );
  return {
    calls,
    signals,
    count: (taskId?: string) => (taskId ? calls.filter((id) => id === taskId).length : calls.length),
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

/** 让 MSW 的响应（Promise / 流读取）走完；setImmediate 不在 fake 范围内。 */
async function flush() {
  for (let i = 0; i < 10; i += 1) {
    await act(async () => {
      await new Promise<void>((resolve) => setImmediate(resolve));
    });
  }
}

/** 推进假时钟并让期间发出的请求完成。 */
async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
  await flush();
}

function seed(...tasks: Task[]) {
  taskStore.getState().upsertMany(tasks, getSessionEpoch());
}

function entry(taskId: string) {
  return taskStore.getState().entries[taskId];
}

function track(scope: TaskScope = { kind: "all" }) {
  return renderHook(({ s }) => useTaskTracker(s), { initialProps: { s: scope } });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date"] });
  vi.setSystemTime(new Date(T0));
  resetUserScopedState();
  bumpSessionEpoch();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("纯函数", () => {
  it("退避：0 → 2s，之后翻倍，封顶 30s", () => {
    expect(backoffDelay(0)).toBe(TRACK_INTERVAL_MS);
    expect(backoffDelay(1)).toBe(4000);
    expect(backoffDelay(2)).toBe(8000);
    expect(backoffDelay(3)).toBe(16000);
    expect(backoffDelay(4)).toBe(MAX_BACKOFF_MS);
    expect(backoffDelay(50)).toBe(MAX_BACKOFF_MS);
  });

  it("查询错误分类：network / http / notFound", () => {
    expect(toQueryError(new ApiError({ kind: "network", message: "断网" }))).toEqual({ kind: "network", message: "断网" });
    expect(toQueryError(new ApiError({ kind: "http", status: 503, message: "忙" }))).toEqual({
      kind: "http",
      status: 503,
      message: "忙",
    });
    expect(toQueryError(new ApiError({ kind: "http", status: 404, message: "任务不存在" }))).toEqual({
      kind: "notFound",
      status: 404,
      message: "任务不存在",
    });
    expect(toQueryError(new Error("boom")).kind).toBe("network");
  });
});

describe("useTaskTracker 调度", () => {
  it("没有任务时零请求", async () => {
    const api = mockGetTask({});
    track();
    await advance(20_000);
    expect(api.count()).toBe(0);
  });

  it("只有终态任务时零请求", async () => {
    const api = mockGetTask({});
    seed(succeeded("1", 5), failed("2", 5));
    track();
    await advance(20_000);
    expect(api.count()).toBe(0);
  });

  it("按 2s 间隔查询，推进到终态后不再请求该任务", async () => {
    const api = mockGetTask({
      "1": (call) => (call === 0 ? processing("1", 1) : succeeded("1", 3)),
    });
    seed(makeTask({ taskId: "1" }));
    track();

    await advance(TRACK_INTERVAL_MS - 1);
    expect(api.count()).toBe(0);
    await advance(1);
    expect(api.count()).toBe(1);
    expect(entry("1").task.status).toBe("PROCESSING");
    expect(entry("1").query.status).toBe("idle");
    expect(entry("1").query.lastSuccessAt).toBeDefined();

    await advance(TRACK_INTERVAL_MS);
    expect(api.count()).toBe(2);
    expect(entry("1").task.status).toBe("SUCCEEDED");

    await advance(30_000);
    expect(api.count()).toBe(2);
  });

  it("任务被其他途径更新为终态（如提交区 / 历史刷新）后也不再请求", async () => {
    const api = mockGetTask({ "1": processing("1", 1) });
    seed(makeTask({ taskId: "1" }));
    track();
    await advance(TRACK_INTERVAL_MS);
    expect(api.count()).toBe(1);
    act(() => seed(succeeded("1", 4)));
    await advance(20_000);
    expect(api.count()).toBe(1);
  });

  it("新加入 store 的未结束任务自动进入跟踪", async () => {
    const api = mockGetTask({ "7": succeeded("7", 2) });
    track();
    await advance(5000);
    expect(api.count()).toBe(0);
    act(() => seed(makeTask({ taskId: "7" })));
    await advance(TRACK_INTERVAL_MS);
    expect(api.count("7")).toBe(1);
    expect(entry("7").task.status).toBe("SUCCEEDED");
    await advance(20_000);
    expect(api.count()).toBe(1);
  });

  it("模板 scope 只跟踪当前模板的任务", async () => {
    const api = mockGetTask({ "1": processing("1", 1), "2": processing("2", 1, { templateId: "tpl-other" }) });
    seed(makeTask({ taskId: "1" }), makeTask({ taskId: "2", templateId: "tpl-other" }));
    track({ kind: "template", templateId: "tpl-postcard" });
    await advance(TRACK_INTERVAL_MS * 3);
    expect(api.count("1")).toBe(3);
    expect(api.count("2")).toBe(0);
  });

  it("scope 变化后按新范围跟踪", async () => {
    const api = mockGetTask({ "1": processing("1", 1), "2": processing("2", 1, { templateId: "tpl-other" }) });
    seed(makeTask({ taskId: "1" }), makeTask({ taskId: "2", templateId: "tpl-other" }));
    const hook = track({ kind: "template", templateId: "tpl-postcard" });
    await advance(TRACK_INTERVAL_MS);
    hook.rerender({ s: { kind: "template", templateId: "tpl-other" } });
    await advance(TRACK_INTERVAL_MS);
    expect(api.count("1")).toBe(1);
    expect(api.count("2")).toBe(1);
  });

  it("同一任务同一时刻最多一个在途请求（定时 + 手动重查都去重）", async () => {
    const pending = deferred<Task>();
    const api = mockGetTask({ "1": pending.promise });
    seed(makeTask({ taskId: "1" }));
    const hook = track();
    await advance(TRACK_INTERVAL_MS);
    expect(api.count()).toBe(1);
    expect(entry("1").query.status).toBe("loading");

    act(() => {
      hook.result.current.refetch("1");
      hook.result.current.refetchAll();
    });
    await advance(20_000);
    expect(api.count()).toBe(1);

    pending.resolve(processing("1", 1));
    await flush();
    expect(entry("1").task.status).toBe("PROCESSING");
    // 上一次请求结束后才重新计时
    await advance(TRACK_INTERVAL_MS - 1);
    expect(api.count()).toBe(1);
    await advance(1);
    expect(api.count()).toBe(2);
  });

  it("一个任务的慢请求不拖住其他任务", async () => {
    const slow = deferred<Task>();
    const api = mockGetTask({ "1": slow.promise, "2": processing("2", 1) });
    seed(makeTask({ taskId: "1" }), makeTask({ taskId: "2" }));
    track();
    await advance(TRACK_INTERVAL_MS * 3);
    expect(api.count("1")).toBe(1);
    expect(api.count("2")).toBe(3);
    slow.resolve(processing("1", 1));
    await flush();
  });
});

describe("查询失败", () => {
  it("某一任务 500：只标记该任务的查询异常，任务状态与其他任务不受影响", async () => {
    const api = mockGetTask({ "1": "500", "2": (call) => (call === 0 ? processing("2", 1) : succeeded("2", 3)) });
    seed(makeTask({ taskId: "1" }), makeTask({ taskId: "2" }));
    track();
    await advance(TRACK_INTERVAL_MS);

    expect(entry("1").task.status).toBe("PENDING");
    expect(entry("1").query).toMatchObject({ status: "error", error: { kind: "http", status: 500 } });
    expect(entry("2").task.status).toBe("PROCESSING");
    expect(entry("2").query.status).toBe("idle");

    await advance(TRACK_INTERVAL_MS);
    expect(entry("2").task.status).toBe("SUCCEEDED");
    expect(api.count("2")).toBe(2);
  });

  it("连续失败时退避（4s、8s），恢复后回到 2s 并清除错误", async () => {
    const api = mockGetTask({ "1": (call) => (call < 2 ? "network" : processing("1", 9)) });
    seed(makeTask({ taskId: "1" }));
    track();

    await advance(TRACK_INTERVAL_MS); // t=2s 第 1 次，失败
    expect(api.count()).toBe(1);
    expect(entry("1").query.error?.kind).toBe("network");

    await advance(4000 - 1);
    expect(api.count()).toBe(1);
    await advance(1); // t=6s 第 2 次，失败
    expect(api.count()).toBe(2);

    await advance(8000 - 1);
    expect(api.count()).toBe(2);
    await advance(1); // t=14s 第 3 次，成功
    expect(api.count()).toBe(3);
    expect(entry("1").task.status).toBe("PROCESSING");
    expect(entry("1").query).toEqual({ status: "idle", lastSuccessAt: Date.now() });

    await advance(TRACK_INTERVAL_MS);
    expect(api.count()).toBe(4);
  });

  it("退避期间可手动重新查询，不必等待", async () => {
    const api = mockGetTask({ "1": (call) => (call === 0 ? "500" : processing("1", 2)) });
    seed(makeTask({ taskId: "1" }));
    const hook = track();
    await advance(TRACK_INTERVAL_MS);
    expect(entry("1").query.status).toBe("error");

    act(() => hook.result.current.refetch("1"));
    await flush();
    expect(api.count()).toBe(2);
    expect(entry("1").query.status).toBe("idle");
    expect(entry("1").task.status).toBe("PROCESSING");
  });

  it("404：标记任务不存在并停止自动跟踪，仍可手动重查", async () => {
    const api = mockGetTask({ "1": "404" });
    seed(makeTask({ taskId: "1" }));
    const hook = track();
    await advance(TRACK_INTERVAL_MS);
    expect(entry("1").task.status).toBe("PENDING");
    expect(entry("1").query.error).toMatchObject({ kind: "notFound", status: 404 });

    await advance(60_000);
    expect(api.count()).toBe(1);

    act(() => hook.result.current.refetch("1"));
    await flush();
    expect(api.count()).toBe(2);
  });

  it("401：全局处理器切换会话后，迟到的错误不写入 store", async () => {
    const api = mockGetTask({ "1": "401" });
    const unregister = setUnauthorizedHandler(() => bumpSessionEpoch());
    seed(makeTask({ taskId: "1" }));
    track();
    await advance(TRACK_INTERVAL_MS);
    unregister();
    expect(api.count()).toBe(1);
    expect(entry("1").query.status).toBe("loading"); // 没有被标记为查询异常
    await advance(20_000);
    expect(api.count()).toBe(1);
  });
});

describe("refetchAll", () => {
  it("立即查询 scope 内未结束或查询异常的任务，不查已成功的终态任务", async () => {
    const api = mockGetTask({ "1": processing("1", 1), "2": "404", "3": succeeded("3", 2) });
    seed(makeTask({ taskId: "1" }), makeTask({ taskId: "2" }), succeeded("3", 2));
    const hook = track();
    await advance(TRACK_INTERVAL_MS);
    expect(api.count("2")).toBe(1);
    expect(entry("2").query.error?.kind).toBe("notFound");

    act(() => hook.result.current.refetchAll());
    await flush();
    expect(api.count("1")).toBe(2);
    expect(api.count("2")).toBe(2);
    expect(api.count("3")).toBe(0);
  });
});

describe("停止与会话切换", () => {
  it("卸载后 abort 在途请求，之后零请求，迟到响应不写入", async () => {
    const pending = deferred<Task>();
    const api = mockGetTask({ "1": pending.promise });
    seed(makeTask({ taskId: "1" }));
    const hook = track();
    await advance(TRACK_INTERVAL_MS);
    expect(api.count()).toBe(1);

    hook.unmount();
    expect(api.signals[0].aborted).toBe(true);
    pending.resolve(succeeded("1", 3));
    await advance(30_000);
    expect(api.count()).toBe(1);
    expect(entry("1").task.status).toBe("PENDING");
  });

  it("卸载后调用 refetch / refetchAll 为空操作", async () => {
    const api = mockGetTask({ "1": processing("1", 1) });
    seed(makeTask({ taskId: "1" }));
    const hook = track();
    const { refetch, refetchAll } = hook.result.current;
    hook.unmount();
    refetch("1");
    refetchAll();
    await advance(10_000);
    expect(api.count()).toBe(0);
  });

  it("epoch 变化后迟到的响应被丢弃（不会把前一用户的任务写回 store）", async () => {
    const pending = deferred<Task>();
    const api = mockGetTask({ "1": pending.promise });
    seed(makeTask({ taskId: "1" }));
    track();
    await advance(TRACK_INTERVAL_MS);
    expect(api.count()).toBe(1);

    // 模拟切换用户（不 abort，专门验证迟到响应）：清空用户数据 + epoch 递增
    act(() => {
      resetUserScopedState();
      bumpSessionEpoch();
    });
    pending.resolve(succeeded("1", 3));
    await flush();
    expect(entry("1")).toBeUndefined();

    await advance(30_000);
    expect(api.count()).toBe(1);
  });

  it("epoch 变化后不再为前一会话的任务调度请求，并跟踪新会话的任务", async () => {
    const api = mockGetTask({ "1": processing("1", 1), "9": processing("9", 1) });
    seed(makeTask({ taskId: "1" }));
    track();
    await advance(TRACK_INTERVAL_MS);
    expect(api.count("1")).toBe(1);

    act(() => {
      resetUserScopedState();
      bumpSessionEpoch();
    });
    await advance(10_000);
    expect(api.count("1")).toBe(1);

    act(() => seed(makeTask({ taskId: "9" })));
    await advance(TRACK_INTERVAL_MS);
    expect(api.count("9")).toBe(1);
    expect(entry("9").task.status).toBe("PROCESSING");
  });
});
