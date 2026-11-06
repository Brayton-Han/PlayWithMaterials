import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { bumpSessionEpoch, getSessionEpoch, resetUserScopedState } from "@/features/auth/session";
import type { Task } from "@/lib/api/endpoints/tasks";
import { makeTask } from "@/test/fixtures";
import { server } from "@/test/msw/server";
import { mockTaskHistory } from "@/test/msw/tasks";

import { HistoryPanel } from "./HistoryPanel";
import { taskStore } from "./taskStore";
import { TRACK_INTERVAL_MS } from "./useTaskTracker";

const nav = vi.hoisted(() => ({ router: { push: vi.fn(), replace: vi.fn() }, search: "" }));
vi.mock("next/navigation", async () => (await import("@/test/navigation")).mockNavigation(nav));

const T0 = "2026-11-05T08:00:00.000Z";
const at = (s: number) => new Date(Date.parse(T0) + s * 1000).toISOString();

const processing = (taskId: string, templateId = "tpl-a", s = 0, stage: Task["stage"] = "GENERATING") =>
  makeTask({ taskId, templateId, status: "PROCESSING", stage, startedAt: at(0), updatedAt: at(s), createdAt: at(Number(taskId)) });
const succeeded = (taskId: string, templateId = "tpl-a", s = 5) =>
  makeTask({
    taskId,
    templateId,
    status: "SUCCEEDED",
    stage: "RESULT_ORGANIZING",
    resultUrl: `/files/results/${taskId}/r.png`,
    startedAt: at(0),
    finishedAt: at(s),
    updatedAt: at(s),
    createdAt: at(Number(taskId)),
  });

type Reply = Task | "500";

/** GET /api/tasks/:taskId：`reply(taskId, call)` 决定响应。 */
function mockGetTask(reply: (taskId: string, call: number) => Reply) {
  const calls: string[] = [];
  server.use(
    http.get("/api/tasks/:taskId", ({ params }) => {
      const taskId = String(params.taskId);
      const call = calls.filter((id) => id === taskId).length;
      calls.push(taskId);
      const r = reply(taskId, call);
      if (r === "500") return HttpResponse.json({ code: "INTERNAL_ERROR", message: "服务器内部错误" }, { status: 500 });
      return HttpResponse.json(r);
    }),
  );
  return calls;
}

async function flush() {
  for (let i = 0; i < 10; i += 1) {
    await act(async () => {
      await new Promise<void>((resolve) => setImmediate(resolve));
    });
  }
}

async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
  await flush();
}

function seed(...tasks: Task[]) {
  act(() => {
    taskStore.getState().upsertMany(tasks, getSessionEpoch());
  });
}

const card = (taskId: string) => screen.getByRole("article", { name: `任务 #${taskId}` });

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date"] });
  vi.setSystemTime(new Date(T0));
  resetUserScopedState();
  bumpSessionEpoch();
  // 历史加载（T-054）默认返回空列表；需要历史数据的用例自行覆盖
  mockTaskHistory();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("HistoryPanel", () => {
  it("只显示当前模板的任务，创建时间倒序", () => {
    mockGetTask(() => "500");
    seed(succeeded("1", "tpl-a"), succeeded("2", "tpl-b"), succeeded("3", "tpl-a"));
    render(<HistoryPanel templateId="tpl-a" />);

    const list = screen.getByRole("list", { name: "当前模板的制作记录" });
    expect(within(list).getAllByRole("article").map((a) => a.getAttribute("aria-label"))).toEqual(["任务 #3", "任务 #1"]);
    expect(screen.queryByRole("article", { name: "任务 #2" })).not.toBeInTheDocument();
  });

  it("当前模板没有任务时显示空状态，且不发请求", async () => {
    const calls = mockGetTask(() => "500");
    seed(processing("2", "tpl-b"));
    render(<HistoryPanel templateId="tpl-a" />);
    await flush();

    expect(screen.getByText("还没有制作记录")).toBeInTheDocument();
    await advance(TRACK_INTERVAL_MS * 3);
    expect(calls).toEqual([]);
  });

  it("新写入 store 的当前模板任务立即出现", async () => {
    mockGetTask(() => "500");
    render(<HistoryPanel templateId="tpl-a" />);
    await flush();
    expect(screen.getByText("还没有制作记录")).toBeInTheDocument();

    seed(makeTask({ taskId: "7", templateId: "tpl-a" }));

    expect(card("7")).toHaveTextContent("等待处理");
    expect(screen.queryByText("还没有制作记录")).not.toBeInTheDocument();
  });

  it("自动跟踪未结束任务：阶段推进到制作完成，之后不再请求", async () => {
    const calls = mockGetTask((id, call) =>
      call === 0 ? processing(id, "tpl-a", 2, "RESULT_ORGANIZING") : succeeded(id, "tpl-a", 4),
    );
    seed(processing("1", "tpl-a", 0, "ASSET_CHECK"));
    render(<HistoryPanel templateId="tpl-a" />);
    expect(card("1")).toHaveTextContent("处理中 · 素材检查中");

    await advance(TRACK_INTERVAL_MS);
    expect(card("1")).toHaveTextContent("处理中 · 结果整理中");

    await advance(TRACK_INTERVAL_MS);
    expect(card("1")).toHaveAttribute("data-state", "succeeded");
    expect(card("1")).toHaveTextContent("制作完成");

    await advance(TRACK_INTERVAL_MS * 5);
    expect(calls).toEqual(["1", "1"]);
  });

  it("查询 500：保留处理中 + 查询异常提示；点击重新查询恢复正确状态", async () => {
    let healthy = false;
    mockGetTask((id) => (healthy ? succeeded(id) : "500"));
    seed(processing("1"));
    render(<HistoryPanel templateId="tpl-a" />);

    await advance(TRACK_INTERVAL_MS);
    expect(card("1")).toHaveAttribute("data-state", "processing");
    expect(card("1")).toHaveAttribute("data-query", "error");
    expect(card("1")).toHaveTextContent("处理中 · 生成中");
    expect(card("1")).toHaveTextContent("状态获取失败：服务器内部错误");
    expect(card("1")).not.toHaveTextContent("制作失败");

    healthy = true;
    fireEvent.click(within(card("1")).getByRole("button", { name: "重新查询" }));
    await flush();

    expect(card("1")).toHaveAttribute("data-state", "succeeded");
    expect(card("1")).toHaveAttribute("data-query", "ok");
    expect(within(card("1")).queryByRole("button", { name: "重新查询" })).not.toBeInTheDocument();
  });

  it("刷新按钮立即查询当前模板全部未结束任务", async () => {
    const calls = mockGetTask((id) => succeeded(id));
    seed(processing("1"), processing("2"), succeeded("3"), processing("4", "tpl-b"));
    render(<HistoryPanel templateId="tpl-a" />);

    fireEvent.click(screen.getByRole("button", { name: "刷新任务状态" }));
    await flush();

    expect([...calls].sort()).toEqual(["1", "2"]);
    expect(card("1")).toHaveTextContent("制作完成");
    expect(card("2")).toHaveTextContent("制作完成");
  });

  it("卸载（离开页面）后不再请求", async () => {
    const calls = mockGetTask((id) => processing(id, "tpl-a", 1));
    seed(processing("1"));
    const view = render(<HistoryPanel templateId="tpl-a" />);

    view.unmount();
    await advance(TRACK_INTERVAL_MS * 5);

    expect(calls).toEqual([]);
  });

  it("切换会话后清空前一用户的卡片", async () => {
    mockGetTask(() => "500");
    seed(succeeded("1"));
    render(<HistoryPanel templateId="tpl-a" />);
    await flush();
    expect(card("1")).toBeInTheDocument();

    act(() => {
      resetUserScopedState();
      bumpSessionEpoch();
    });

    expect(screen.queryByRole("article")).not.toBeInTheDocument();
    expect(screen.getByText("还没有制作记录")).toBeInTheDocument();
  });
});

describe("HistoryPanel 历史加载（T-054）", () => {
  /** 手动控制的历史响应：返回 Promise 与 resolve 函数。 */
  function deferred<T>() {
    let resolve!: (value: T) => void;
    const promise = new Promise<T>((r) => {
      resolve = r;
    });
    return { promise, resolve };
  }

  it("挂载时按 templateId 请求一次历史：加载中 → 卡片（倒序），不显示为空", async () => {
    mockGetTask(() => "500");
    const gate = deferred<Task[]>();
    const calls = mockTaskHistory(() => gate.promise);
    render(<HistoryPanel templateId="tpl-a" />);

    expect(screen.getByRole("status")).toHaveTextContent("正在加载制作记录…");
    expect(screen.queryByText("还没有制作记录")).not.toBeInTheDocument();

    gate.resolve([succeeded("3"), succeeded("1")]);
    await flush();

    expect(calls).toEqual(["tpl-a"]);
    expect(screen.queryByText("正在加载制作记录…")).not.toBeInTheDocument();
    expect(screen.getAllByRole("article").map((a) => a.getAttribute("aria-label"))).toEqual(["任务 #3", "任务 #1"]);
    expect(card("3")).toHaveTextContent("制作完成");
  });

  it("历史为空时显示空状态", async () => {
    mockGetTask(() => "500");
    mockTaskHistory(() => []);
    render(<HistoryPanel templateId="tpl-a" />);
    await flush();
    expect(screen.getByText("还没有制作记录")).toBeInTheDocument();
  });

  it("历史中未结束的任务自动进入跟踪并推进到终态，终态任务不查询", async () => {
    const calls = mockGetTask((id, call) => (call === 0 ? processing(id, "tpl-a", 3, "RESULT_ORGANIZING") : succeeded(id, "tpl-a", 6)));
    mockTaskHistory(() => [processing("2", "tpl-a", 1, "ASSET_CHECK"), succeeded("1")]);
    render(<HistoryPanel templateId="tpl-a" />);
    await flush();
    expect(card("2")).toHaveTextContent("处理中 · 素材检查中");

    await advance(TRACK_INTERVAL_MS);
    expect(card("2")).toHaveTextContent("处理中 · 结果整理中");
    await advance(TRACK_INTERVAL_MS);
    expect(card("2")).toHaveTextContent("制作完成");

    await advance(TRACK_INTERVAL_MS * 3);
    expect(calls).toEqual(["2", "2"]);
  });

  it.each(["500", "network"] as const)("历史加载失败（%s）：错误 + 重试，不显示空状态；重试成功后显示卡片", async (failure) => {
    mockGetTask(() => "500");
    const calls = mockTaskHistory((_, call) => (call === 0 ? failure : [succeeded("1")]));
    render(<HistoryPanel templateId="tpl-a" />);
    await flush();

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("制作记录加载失败");
    expect(alert).toHaveTextContent(failure === "500" ? "服务器内部错误" : "网络连接异常");
    expect(screen.queryByText("还没有制作记录")).not.toBeInTheDocument();

    fireEvent.click(within(alert).getByRole("button", { name: "重试" }));
    await flush();

    expect(calls).toEqual(["tpl-a", "tpl-a"]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(card("1")).toHaveTextContent("制作完成");
  });

  it("历史加载失败时，store 中已有的卡片（如刚提交的任务）仍然显示", async () => {
    mockGetTask(() => "500");
    mockTaskHistory(() => "500");
    seed(makeTask({ taskId: "9", templateId: "tpl-a" }));
    render(<HistoryPanel templateId="tpl-a" />);
    await flush();

    expect(screen.getByRole("alert")).toHaveTextContent("制作记录加载失败");
    expect(card("9")).toHaveTextContent("等待处理");
  });

  it("较旧的历史快照不会覆盖 store 中更新的状态", async () => {
    mockGetTask(() => "500");
    mockTaskHistory(() => [processing("1", "tpl-a", 1)]);
    seed(succeeded("1", "tpl-a", 5));
    render(<HistoryPanel templateId="tpl-a" />);
    await flush();

    expect(card("1")).toHaveTextContent("制作完成");
    expect(taskStore.getState().entries["1"]?.task.status).toBe("SUCCEEDED");
  });

  it("请求期间切换会话：迟到的历史响应不写入 store", async () => {
    mockGetTask(() => "500");
    const gate = deferred<Task[]>();
    mockTaskHistory(() => gate.promise);
    render(<HistoryPanel templateId="tpl-a" />);
    await flush();

    act(() => {
      resetUserScopedState();
      bumpSessionEpoch();
    });
    gate.resolve([succeeded("1")]);
    await flush();

    expect(taskStore.getState().entries).toEqual({});
    expect(screen.queryByRole("article")).not.toBeInTheDocument();
  });

  it("卸载后迟到的历史响应不写入 store", async () => {
    mockGetTask(() => "500");
    const gate = deferred<Task[]>();
    mockTaskHistory(() => gate.promise);
    const view = render(<HistoryPanel templateId="tpl-a" />);
    await flush();

    view.unmount();
    gate.resolve([succeeded("1")]);
    await flush();

    expect(taskStore.getState().entries).toEqual({});
  });

  it("刷新按钮重新加载历史：出现其他页面提交的新任务", async () => {
    mockGetTask(() => "500");
    const calls = mockTaskHistory((_, call) => (call === 0 ? [succeeded("1")] : [succeeded("2"), succeeded("1")]));
    render(<HistoryPanel templateId="tpl-a" />);
    await flush();
    expect(screen.getAllByRole("article")).toHaveLength(1);

    fireEvent.click(screen.getByRole("button", { name: "刷新任务状态" }));
    await flush();

    expect(calls).toEqual(["tpl-a", "tpl-a"]);
    expect(screen.getAllByRole("article").map((a) => a.getAttribute("aria-label"))).toEqual(["任务 #2", "任务 #1"]);
  });
});
