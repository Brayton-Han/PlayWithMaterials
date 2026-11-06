"use client";

import { getSessionEpoch } from "@/features/auth/session";
import { listTasks } from "@/lib/api/endpoints/tasks";
import { useKeyedLoad } from "@/lib/useKeyedLoad";

import type { TaskListStatus } from "./TaskList";
import { taskStore, type TaskScope } from "./taskStore";

const ALL_KEY = "all";
const TEMPLATE_PREFIX = "template:";

/** scope → useKeyedLoad 的 key（scope 变化时重新请求，旧响应不覆盖新结果）。 */
function historyKey(scope: TaskScope): string {
  return scope.kind === "all" ? ALL_KEY : `${TEMPLATE_PREFIX}${scope.templateId}`;
}

/**
 * 请求任务历史并写入 taskStore（F-11 规则 3：刷新后从后端恢复）。
 * key 为 "all" 时不带参数请求全部模板（"我的记录"页），否则按 templateId 请求（详情页 History）。
 * 写入带上请求发起时的 session epoch：期间退出 / 切换用户时 store 丢弃这次写入（F-10 规则 5）。
 * 合并按 store 的单调规则进行，较旧的历史快照不会覆盖跟踪中已拿到的更新状态（F-11 规则 4）。
 * 写入后，store 中未结束的任务由订阅了 store 的 useTaskTracker 自动接手跟踪。
 */
function loadHistory(key: string, signal: AbortSignal): Promise<void> {
  const epoch = getSessionEpoch();
  const params = key === ALL_KEY ? {} : { templateId: key.slice(TEMPLATE_PREFIX.length) };
  return listTasks(params, signal).then((tasks) => {
    if (!signal.aborted) taskStore.getState().upsertMany(tasks, epoch);
  });
}

/**
 * 历史加载（详情页 T-054：当前模板；"我的记录"页 T-055：全部）：挂载时（只在已登录时挂载）请求一次；
 * 返回给 TaskList 用的加载状态（失败时为错误 + 重试，不显示为空列表）和 `reload()`。
 */
export function useTaskHistory(scope: TaskScope): { load: TaskListStatus; reload: () => void } {
  const { outcome, retry: reload } = useKeyedLoad(historyKey(scope), loadHistory);
  const load: TaskListStatus =
    outcome.status === "error"
      ? { status: "error", message: outcome.message, onRetry: reload }
      : { status: outcome.status };
  return { load, reload };
}
