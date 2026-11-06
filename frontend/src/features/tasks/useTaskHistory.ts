"use client";

import { getSessionEpoch } from "@/features/auth/session";
import { listTasks } from "@/lib/api/endpoints/tasks";
import { useKeyedLoad } from "@/lib/useKeyedLoad";

import type { TaskListStatus } from "./TaskList";
import { taskStore } from "./taskStore";

/**
 * 请求某个模板的任务历史并写入 taskStore（F-11 规则 3：刷新后从后端恢复）。
 * 写入带上请求发起时的 session epoch：期间退出 / 切换用户时 store 丢弃这次写入（F-10 规则 5）。
 * 合并按 store 的单调规则进行，较旧的历史快照不会覆盖跟踪中已拿到的更新状态。
 * 写入后，store 中未结束的任务由订阅了 store 的 useTaskTracker 自动接手跟踪。
 */
function loadTemplateHistory(templateId: string, signal: AbortSignal): Promise<void> {
  const epoch = getSessionEpoch();
  return listTasks({ templateId }, signal).then((tasks) => {
    if (!signal.aborted) taskStore.getState().upsertMany(tasks, epoch);
  });
}

/**
 * 详情页 History 的历史加载（T-054）：挂载时（只在已登录时挂载）按 templateId 请求一次；
 * 返回给 TaskList 用的加载状态（失败时为错误 + 重试，不显示为空列表）和 `reload()`。
 */
export function useTaskHistory(templateId: string): { load: TaskListStatus; reload: () => void } {
  const { outcome, retry: reload } = useKeyedLoad(templateId, loadTemplateHistory);
  const load: TaskListStatus =
    outcome.status === "error"
      ? { status: "error", message: outcome.message, onRetry: reload }
      : { status: outcome.status };
  return { load, reload };
}
