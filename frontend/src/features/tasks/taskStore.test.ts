import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";

import { bumpSessionEpoch, getSessionEpoch, resetUserScopedState } from "@/features/auth/session";
import type { Task } from "@/lib/api/endpoints/tasks";
import { makeTask } from "@/test/fixtures";

import {
  applyQuery,
  applyUpsert,
  createTaskStore,
  queryError,
  selectActive,
  selectAll,
  selectByTemplate,
  shouldAccept,
  taskStore,
  useTaskStore,
  type TaskEntries,
  type TaskQueryError,
  type TaskStore,
} from "./taskStore";

const T0 = "2026-11-05T08:00:00.000Z";
const T1 = "2026-11-05T08:00:01.000Z";
const T2 = "2026-11-05T08:00:02.000Z";
const T3 = "2026-11-05T08:00:03.000Z";

const QUERY_500: TaskQueryError = { kind: "http", status: 500, message: "服务器开小差了，请稍后重试" };

function processing(taskId: string, stage: Task["stage"], updatedAt: string, extra: Partial<Task> = {}): Task {
  return makeTask({ taskId, status: "PROCESSING", stage, startedAt: T1, updatedAt, ...extra });
}

function succeeded(taskId: string, updatedAt: string, extra: Partial<Task> = {}): Task {
  return makeTask({
    taskId,
    status: "SUCCEEDED",
    stage: "RESULT_ORGANIZING",
    resultUrl: `/files/results/${taskId}/r.png`,
    startedAt: T1,
    finishedAt: updatedAt,
    updatedAt,
    ...extra,
  });
}

function failed(taskId: string, updatedAt: string, extra: Partial<Task> = {}): Task {
  return makeTask({
    taskId,
    status: "FAILED",
    stage: "GENERATING",
    failedStage: "GENERATING",
    failureCode: "SIMULATED_FAILURE",
    failureReason: "模拟失败：生成阶段出错",
    startedAt: T1,
    finishedAt: updatedAt,
    updatedAt,
    ...extra,
  });
}

function ids(list: readonly { task: Task }[]): string[] {
  return list.map((entry) => entry.task.taskId);
}

describe("纯函数：单调合并", () => {
  it("新任务插入为 query idle", () => {
    const entries = applyUpsert({}, [makeTask({ taskId: "1" })]);
    expect(entries["1"].task.status).toBe("PENDING");
    expect(entries["1"].query).toEqual({ status: "idle" });
  });

  it("按 updatedAt 前进：PENDING → PROCESSING → SUCCEEDED", () => {
    let entries: TaskEntries = applyUpsert({}, [makeTask({ taskId: "1" })]);
    entries = applyUpsert(entries, [processing("1", "GENERATING", T1)]);
    expect(entries["1"].task.stage).toBe("GENERATING");
    entries = applyUpsert(entries, [succeeded("1", T2)]);
    expect(entries["1"].task.status).toBe("SUCCEEDED");
    expect(entries["1"].task.resultUrl).toBe("/files/results/1/r.png");
  });

  it.each([
    ["SUCCEEDED", succeeded("1", T2)],
    ["FAILED", failed("1", T2)],
  ])("终态 %s 不被非终态覆盖（即使 updatedAt 更新）", (_label, terminal) => {
    const entries = applyUpsert({}, [terminal]);
    for (const stale of [makeTask({ taskId: "1", updatedAt: T3 }), processing("1", "RESULT_ORGANIZING", T3)]) {
      const next = applyUpsert(entries, [stale]);
      expect(next).toBe(entries);
      expect(next["1"].task).toBe(terminal);
    }
  });

  it("updatedAt 更旧的响应被丢弃", () => {
    const entries = applyUpsert({}, [processing("1", "RESULT_ORGANIZING", T2)]);
    const next = applyUpsert(entries, [processing("1", "ASSET_CHECK", T1)]);
    expect(next).toBe(entries);
    expect(next["1"].task.stage).toBe("RESULT_ORGANIZING");
  });

  it("updatedAt 相同时不允许进度回退，同进度的重复响应接受", () => {
    const current = processing("1", "GENERATING", T1);
    expect(shouldAccept(current, processing("1", "ASSET_CHECK", T1))).toBe(false);
    expect(shouldAccept(current, makeTask({ taskId: "1", updatedAt: T1 }))).toBe(false);
    expect(shouldAccept(current, processing("1", "GENERATING", T1))).toBe(true);
    expect(shouldAccept(current, processing("1", "RESULT_ORGANIZING", T1))).toBe(true);
  });

  it("updatedAt 无法解析时退回按进度比较", () => {
    const current = processing("1", "GENERATING", "not-a-date");
    expect(shouldAccept(current, processing("1", "ASSET_CHECK", T3))).toBe(false);
    expect(shouldAccept(current, succeeded("1", T3))).toBe(true);
  });

  it("非终态 → 终态按 updatedAt 正常接受；更旧的终态不覆盖更新的非终态", () => {
    expect(shouldAccept(processing("1", "GENERATING", T1), failed("1", T2))).toBe(true);
    expect(shouldAccept(processing("1", "GENERATING", T2), failed("1", T1))).toBe(false);
  });

  it("合并已有任务时保留其 query 状态", () => {
    let entries = applyUpsert({}, [makeTask({ taskId: "1" })]);
    entries = applyQuery(entries, "1", (q) => queryError(q, QUERY_500));
    entries = applyUpsert(entries, [processing("1", "ASSET_CHECK", T1)]);
    expect(entries["1"].task.status).toBe("PROCESSING");
    expect(entries["1"].query.status).toBe("error");
  });

  it("同一批次中同一任务出现多次时按单调规则合并", () => {
    const entries = applyUpsert({}, [processing("1", "GENERATING", T2), makeTask({ taskId: "1", updatedAt: T1 })]);
    expect(entries["1"].task.stage).toBe("GENERATING");
  });

  it("applyQuery 对不存在的任务不变、不创建条目", () => {
    const entries = applyUpsert({}, [makeTask({ taskId: "1" })]);
    expect(applyQuery(entries, "999", (q) => queryError(q, QUERY_500))).toBe(entries);
  });
});

describe("纯函数：派生列表", () => {
  const entries = applyUpsert({}, [
    makeTask({ taskId: "1", templateId: "X", createdAt: T0 }),
    succeeded("2", T3, { templateId: "Y", createdAt: T1 }),
    processing("3", "GENERATING", T3, { templateId: "X", createdAt: T2 }),
    failed("4", T3, { templateId: "X", createdAt: T2 }),
    makeTask({ taskId: "10", templateId: "X", createdAt: T2 }),
  ]);

  it("按模板派生：只含该模板，创建时间倒序，同一时间 taskId 数值倒序", () => {
    expect(ids(selectByTemplate(entries, "X"))).toEqual(["10", "4", "3", "1"]);
    expect(ids(selectByTemplate(entries, "Y"))).toEqual(["2"]);
    expect(selectByTemplate(entries, "Z")).toEqual([]);
  });

  it("全部任务覆盖所有模板", () => {
    expect(ids(selectAll(entries))).toEqual(["10", "4", "3", "2", "1"]);
  });

  it("selectActive 只返回 scope 内 PENDING / PROCESSING 的任务", () => {
    expect(ids(selectActive(entries, { kind: "template", templateId: "X" }))).toEqual(["10", "3", "1"]);
    expect(ids(selectActive(entries, { kind: "template", templateId: "Y" }))).toEqual([]);
    expect(ids(selectActive(entries, { kind: "all" }))).toEqual(["10", "3", "1"]);
  });

  it("entries 不变时派生结果是同一个数组引用；变化后重新计算", () => {
    expect(selectByTemplate(entries, "X")).toBe(selectByTemplate(entries, "X"));
    expect(selectAll(entries)).toBe(selectAll(entries));
    const next = applyUpsert(entries, [succeeded("3", "2026-11-05T08:00:09.000Z", { templateId: "X", createdAt: T2 })]);
    expect(selectActive(next, { kind: "all" })).not.toBe(selectActive(entries, { kind: "all" }));
    expect(ids(selectActive(next, { kind: "all" }))).toEqual(["10", "1"]);
  });
});

describe("taskStore（Zustand）", () => {
  let store: TaskStore;
  let epoch: number;

  beforeEach(() => {
    store = createTaskStore();
    epoch = getSessionEpoch();
  });

  const entry = (id: string) => store.getState().entries[id];

  it("两个任务的更新互不影响", () => {
    store.getState().upsertMany([makeTask({ taskId: "1" }), makeTask({ taskId: "2" })], epoch);
    const before2 = entry("2");

    store.getState().upsertFromServer(processing("1", "GENERATING", T1), epoch);
    store.getState().markQueryError("1", QUERY_500, epoch);

    expect(entry("1").task.stage).toBe("GENERATING");
    expect(entry("1").query.status).toBe("error");
    expect(entry("2")).toBe(before2);

    store.getState().upsertFromServer(failed("2", T2), epoch);
    expect(entry("2").task.status).toBe("FAILED");
    expect(entry("1").task.status).toBe("PROCESSING");
    expect(entry("1").query.error).toEqual(QUERY_500);
  });

  it("markQueryError 只改 query，不改 task.status（查询异常 ≠ 任务失败）", () => {
    const task = processing("1", "ASSET_CHECK", T1);
    store.getState().upsertFromServer(task, epoch);
    store.getState().markQueryOk("1", epoch, 1000);
    store.getState().markQueryLoading("1", epoch);
    expect(entry("1").query).toEqual({ status: "loading", lastSuccessAt: 1000 });

    store.getState().markQueryError("1", { kind: "network", message: "网络连接异常" }, epoch);
    expect(entry("1").task).toBe(task);
    expect(entry("1").task.status).toBe("PROCESSING");
    expect(entry("1").query).toEqual({
      status: "error",
      error: { kind: "network", message: "网络连接异常" },
      lastSuccessAt: 1000,
    });

    store.getState().markQueryError("1", { kind: "notFound", status: 404, message: "任务不存在或无权访问" }, epoch);
    expect(entry("1").task.status).toBe("PROCESSING");
  });

  it("重新查询成功后清除错误、记录 lastSuccessAt", () => {
    store.getState().upsertFromServer(makeTask({ taskId: "1" }), epoch);
    store.getState().markQueryError("1", QUERY_500, epoch);
    store.getState().markQueryLoading("1", epoch);
    expect(entry("1").query.error).toEqual(QUERY_500); // 重查期间仍保留上次错误
    store.getState().markQueryOk("1", epoch, 2000);
    expect(entry("1").query).toEqual({ status: "idle", lastSuccessAt: 2000 });
  });

  it("终态不回退，被拒绝的写入返回 false 且不通知订阅者", () => {
    store.getState().upsertFromServer(succeeded("1", T2), epoch);
    let notified = 0;
    const unsubscribe = store.subscribe(() => {
      notified += 1;
    });
    expect(store.getState().upsertFromServer(processing("1", "GENERATING", T3), epoch)).toBe(false);
    expect(store.getState().upsertFromServer(makeTask({ taskId: "1", updatedAt: T1 }), epoch)).toBe(false);
    unsubscribe();
    expect(notified).toBe(0);
    expect(entry("1").task.status).toBe("SUCCEEDED");
  });

  it("epoch 不是当前值的写入全部被丢弃", () => {
    store.getState().upsertFromServer(makeTask({ taskId: "1" }), epoch);
    const stale = epoch;
    const current = bumpSessionEpoch();

    expect(store.getState().upsertFromServer(succeeded("1", T3), stale)).toBe(false);
    expect(store.getState().upsertMany([makeTask({ taskId: "2" })], stale)).toBe(false);
    store.getState().markQueryLoading("1", stale);
    store.getState().markQueryError("1", QUERY_500, stale);
    store.getState().markQueryOk("1", stale, 5);
    expect(Object.keys(store.getState().entries)).toEqual(["1"]);
    expect(entry("1").task.status).toBe("PENDING");
    expect(entry("1").query).toEqual({ status: "idle" });

    expect(store.getState().upsertFromServer(succeeded("1", T3), current)).toBe(true);
    expect(entry("1").task.status).toBe("SUCCEEDED");
  });

  it("reset 后清空，派生列表为空", () => {
    store.getState().upsertMany([makeTask({ taskId: "1", templateId: "X" }), makeTask({ taskId: "2" })], epoch);
    store.getState().reset();
    expect(store.getState().entries).toEqual({});
    expect(selectAll(store.getState().entries)).toEqual([]);
    expect(selectByTemplate(store.getState().entries, "X")).toEqual([]);
    expect(selectActive(store.getState().entries, { kind: "all" })).toEqual([]);
  });
});

describe("应用级 taskStore 与会话清理", () => {
  it("切换会话（resetUserScopedState + epoch 递增）后清空前一用户任务并拒绝其迟到响应", () => {
    const userA = getSessionEpoch();
    taskStore.getState().upsertFromServer(makeTask({ taskId: "1" }), userA);
    expect(selectAll(taskStore.getState().entries)).toHaveLength(1);

    // AuthProvider 退出 / 切换用户时的顺序：先清理，再递增 epoch
    resetUserScopedState();
    const userB = bumpSessionEpoch();
    expect(taskStore.getState().entries).toEqual({});

    taskStore.getState().upsertFromServer(succeeded("1", T3), userA);
    expect(taskStore.getState().entries).toEqual({});

    taskStore.getState().upsertFromServer(makeTask({ taskId: "7" }), userB);
    expect(ids(selectAll(taskStore.getState().entries))).toEqual(["7"]);
    taskStore.getState().reset();
  });
});

describe("useTaskStore", () => {
  it("派生列表订阅稳定（不会无限重渲染），写入后更新，reset 后为空", () => {
    const epoch = getSessionEpoch();
    const { result } = renderHook(() => useTaskStore((s) => selectByTemplate(s.entries, "X")));
    expect(result.current).toEqual([]);

    act(() => {
      taskStore.getState().upsertMany([makeTask({ taskId: "1", templateId: "X" }), makeTask({ taskId: "2" })], epoch);
    });
    expect(ids(result.current)).toEqual(["1"]);

    act(() => {
      taskStore.getState().upsertFromServer(processing("1", "GENERATING", T1, { templateId: "X" }), epoch);
    });
    expect(result.current[0].task.stage).toBe("GENERATING");

    act(() => taskStore.getState().reset());
    expect(result.current).toEqual([]);
  });
});
