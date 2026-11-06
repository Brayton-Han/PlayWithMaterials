"use client";

import { ErrorState } from "@/components/ErrorState";

import { TaskCard } from "./TaskCard";
import type { TaskEntry } from "./taskStore";

/** 列表数据（历史）的加载状态；历史请求由调用方负责（详情页 T-054、"我的记录"页 T-055）。 */
export type TaskListStatus =
  | { status: "loading" }
  | { status: "error"; message: string; onRetry: () => void }
  | { status: "ready" };

const READY: TaskListStatus = { status: "ready" };

/**
 * 任务卡片列表 + 空 / 加载 / 错误态（F-11 异常情况）。
 * 已有任务时始终显示卡片（例如刚提交的任务），加载 / 错误提示显示在列表上方，历史加载失败不显示为空。
 */
export function TaskList({
  entries,
  onRefetch,
  showTemplate = false,
  load = READY,
  label = "制作记录",
}: {
  entries: readonly TaskEntry[];
  onRefetch: (taskId: string) => void;
  showTemplate?: boolean;
  load?: TaskListStatus;
  label?: string;
}) {
  return (
    <div className="flex flex-col gap-3">
      {load.status === "loading" && (
        <p role="status" className="text-sm text-zinc-500 dark:text-zinc-400">
          正在加载制作记录…
        </p>
      )}
      {load.status === "error" && <ErrorState message={`制作记录加载失败：${load.message}`} onRetry={load.onRetry} />}
      {entries.length > 0 ? (
        <ul aria-label={label} className="flex flex-col gap-3">
          {entries.map((entry) => (
            <li key={entry.task.taskId}>
              <TaskCard entry={entry} onRefetch={onRefetch} showTemplate={showTemplate} />
            </li>
          ))}
        </ul>
      ) : (
        load.status === "ready" && <p className="text-sm text-zinc-500 dark:text-zinc-400">还没有制作记录</p>
      )}
    </div>
  );
}
