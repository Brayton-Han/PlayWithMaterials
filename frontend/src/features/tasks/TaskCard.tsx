"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";

import type { Task } from "@/lib/api/endpoints/tasks";
import { useKeepFocus } from "@/lib/useKeepFocus";

import { ResultPreviewDialog } from "./ResultPreviewDialog";
import type { TaskEntry, TaskQueryState } from "./taskStore";
import { cardState, failureDetail, formatDateTime, statusLabel } from "./taskView";

/** 重查结束后焦点去向：仍有异常时回到"重新查询"按钮，异常消失（按钮卸载）时落在卡片本身（F-19）。 */
const FOCUS_AFTER_REFETCH = ["button[data-refetch]"];

const STATE_CLASS = {
  pending: "border-zinc-200 dark:border-zinc-800",
  processing: "border-sky-200 dark:border-sky-900",
  succeeded: "border-emerald-200 dark:border-emerald-900",
  failed: "border-red-300 bg-red-50 dark:border-red-900 dark:bg-red-950/40",
} as const;

const STATUS_TEXT_CLASS = {
  pending: "text-zinc-600 dark:text-zinc-300",
  processing: "text-sky-700 dark:text-sky-300",
  succeeded: "text-emerald-700 dark:text-emerald-300",
  failed: "text-red-700 dark:text-red-300",
} as const;

/**
 * 任务卡片（F-09，F-11；详情页 History 与"我的记录"页复用）。
 *
 * - 外观只由服务端字段决定（`data-state`）：等待处理 / 处理中·阶段 / 制作完成·结果缩略图 / 制作失败·阶段 + 原因。
 * - 查询异常（`entry.query.error`）叠加在最后已知状态之上（`data-query="error"`）：
 *   "状态获取失败" + "重新查询"，404 时为"任务不存在或无权访问"；绝不显示为"制作失败"（R-07.2）。
 * - 缩略图：成功显示结果图（点击打开预览对话框），其他状态显示第一张输入图（OQ-22 临时默认）；
 *   图片加载失败只显示占位，不改变任务状态。
 * - `showTemplate`：显示模板标题和封面，点击进入该模板详情页（"我的记录"页，F-11 规则 1）。
 */
export function TaskCard({
  entry,
  onRefetch,
  showTemplate = false,
}: {
  entry: TaskEntry;
  onRefetch: (taskId: string) => void;
  showTemplate?: boolean;
}) {
  const { task, query } = entry;
  const state = cardState(task);
  const label = `任务 #${task.taskId}`;
  const cardRef = useRef<HTMLElement>(null);
  const armFocus = useKeepFocus(cardRef, query.status === "loading", FOCUS_AFTER_REFETCH);

  return (
    <article
      ref={cardRef}
      tabIndex={-1}
      aria-label={label}
      data-state={state}
      data-query={query.error ? "error" : "ok"}
      className={`flex flex-col gap-3 rounded-lg border p-3 ${STATE_CLASS[state]}`}
    >
      {showTemplate && <TemplateInfo task={task} />}
      <div className="flex gap-3">
        <TaskThumbnail task={task} label={label} />
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <p className={`text-sm font-medium ${STATUS_TEXT_CLASS[state]}`}>{statusLabel(task)}</p>
          {state === "failed" && (
            <p className="text-sm break-words text-red-700 dark:text-red-300">{failureDetail(task)}</p>
          )}
          <p className="text-xs text-zinc-500 dark:text-zinc-400">
            <time dateTime={task.createdAt}>{formatDateTime(task.createdAt)}</time>
            <span className="ml-2">{label}</span>
          </p>
        </div>
      </div>
      {query.error && (
        <QueryErrorNotice
          query={query}
          onRetry={() => {
            armFocus();
            onRefetch(task.taskId);
          }}
        />
      )}
    </article>
  );
}

function TemplateInfo({ task }: { task: Task }) {
  const [failed, setFailed] = useState(false);
  return (
    <Link
      href={`/templates/${encodeURIComponent(task.templateId)}`}
      className="flex items-center gap-2 text-sm font-medium hover:underline"
    >
      {failed ? (
        <span aria-hidden className="h-8 w-8 shrink-0 rounded bg-zinc-100 dark:bg-zinc-900" />
      ) : (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={task.templateCoverUrl}
          alt=""
          onError={() => setFailed(true)}
          className="h-8 w-8 shrink-0 rounded object-cover"
        />
      )}
      <span className="truncate">{task.templateTitle}</span>
    </Link>
  );
}

const THUMB_CLASS = "h-16 w-16 shrink-0 overflow-hidden rounded bg-zinc-100 dark:bg-zinc-900";

function Placeholder({ text }: { text: string }) {
  return (
    <div role="img" aria-label={text} className={`${THUMB_CLASS} flex items-center justify-center p-1 text-center text-xs text-zinc-500`}>
      {text}
    </div>
  );
}

function TaskThumbnail({ task, label }: { task: Task; label: string }) {
  if (task.status === "SUCCEEDED" && task.resultUrl) {
    // key：结果地址变化时重置加载失败状态
    return <ResultThumbnail key={task.resultUrl} url={task.resultUrl} label={label} />;
  }
  const input = task.inputAssets[0];
  if (!input) return <Placeholder text="无素材" />;
  return <InputThumbnail key={input.url} url={input.url} label={label} />;
}

function InputThumbnail({ url, label }: { url: string; label: string }) {
  const [failed, setFailed] = useState(false);
  if (failed) return <Placeholder text="素材加载失败" />;
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img src={url} alt={`${label} 输入图`} onError={() => setFailed(true)} className={`${THUMB_CLASS} object-cover`} />
  );
}

/** 成功结果缩略图：点击打开预览对话框；关闭后焦点回到该按钮（F-11 验收标准 5）。 */
function ResultThumbnail({ url, label }: { url: string; label: string }) {
  const [failed, setFailed] = useState(false);
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const wasOpen = useRef(false);

  useEffect(() => {
    if (wasOpen.current && !open) triggerRef.current?.focus();
    wasOpen.current = open;
  }, [open]);

  // 结果图加载失败：占位 + 提示，任务仍显示"制作完成"（F-09 异常情况）
  if (failed) return <Placeholder text="结果加载失败" />;

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        aria-haspopup="dialog"
        aria-label={`预览${label}结果`}
        onClick={() => setOpen(true)}
        className={`${THUMB_CLASS} cursor-zoom-in focus-visible:ring-2 focus-visible:ring-zinc-400 focus-visible:outline-none`}
      >
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={url} alt={`${label} 结果`} onError={() => setFailed(true)} className="h-full w-full object-cover" />
      </button>
      {open && <ResultPreviewDialog url={url} title={`${label} 结果预览`} onClose={() => setOpen(false)} />}
    </>
  );
}

/** 查询异常提示（与"制作失败"文案、样式都不同）：保留上面的最后已知状态，提供"重新查询"。 */
function QueryErrorNotice({ query, onRetry }: { query: TaskQueryState; onRetry: () => void }) {
  const error = query.error!;
  const retrying = query.status === "loading";
  const message =
    error.kind === "notFound" ? "任务不存在或无权访问" : `状态获取失败：${error.message}`;
  return (
    <div
      role="status"
      className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-dashed border-amber-400 bg-amber-50 px-2 py-1.5 text-xs text-amber-900 dark:border-amber-700 dark:bg-amber-950/40 dark:text-amber-200"
    >
      <span className="min-w-0 break-words">{message}</span>
      <button
        type="button"
        data-refetch
        onClick={onRetry}
        disabled={retrying}
        className="shrink-0 rounded border border-amber-500 px-2 py-0.5 font-medium hover:bg-amber-100 focus-visible:ring-2 focus-visible:ring-amber-400 focus-visible:outline-none disabled:cursor-wait disabled:opacity-60 dark:hover:bg-amber-900/40"
      >
        {retrying ? "重新查询中…" : "重新查询"}
      </button>
    </div>
  );
}
