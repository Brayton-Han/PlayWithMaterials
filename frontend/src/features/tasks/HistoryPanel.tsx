"use client";

import { useCallback } from "react";

import { TaskList } from "./TaskList";
import { selectByTemplate, useTaskStore, type TaskStoreState } from "./taskStore";
import { useTaskHistory } from "./useTaskHistory";
import { useTaskTracker } from "./useTaskTracker";

/**
 * 模板详情页右栏 History（F-02 规则 3，F-09，F-11）：只显示当前用户在当前模板下的任务（来自 taskStore），
 * 挂载时从后端加载当前模板的历史写入 store（T-054，刷新 / 重新打开浏览器后恢复），失败时显示错误 + 重试；
 * 自动跟踪其中未结束的任务（离开页面即停止）；右上角"刷新"重新加载历史，并立即重查全部未结束或查询异常的任务。
 * 提交受理后由详情页写入 store（T-053）。只在已登录时渲染（会话切换时随 SessionBoundary 重新挂载）。
 */
export function HistoryPanel({ templateId }: { templateId: string }) {
  const entries = useTaskStore(
    useCallback((state: TaskStoreState) => selectByTemplate(state.entries, templateId), [templateId]),
  );
  const { refetch, refetchAll } = useTaskTracker({ kind: "template", templateId });
  const { load, reload } = useTaskHistory(templateId);
  const refresh = () => {
    reload();
    refetchAll();
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm text-zinc-500 dark:text-zinc-400">当前模板的制作记录</p>
        <button
          type="button"
          onClick={refresh}
          aria-label="刷新任务状态"
          title="刷新任务状态"
          className="rounded-md border border-zinc-300 px-2 py-1 text-sm hover:bg-zinc-100 focus-visible:ring-2 focus-visible:ring-zinc-400 focus-visible:outline-none dark:border-zinc-700 dark:hover:bg-zinc-800"
        >
          ↻ 刷新
        </button>
      </div>
      <TaskList entries={entries} onRefetch={refetch} load={load} label="当前模板的制作记录" />
    </div>
  );
}
