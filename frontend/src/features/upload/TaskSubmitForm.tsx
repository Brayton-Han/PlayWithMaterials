"use client";

import { useId, useRef, useState, type FormEvent } from "react";

import type { Task } from "@/lib/api/endpoints/tasks";
import type { TemplateDetail } from "@/lib/api/endpoints/catalog";
import { useKeepFocus } from "@/lib/useKeepFocus";

import { UploadSlots } from "./UploadSlots";
import { useSlots, type Slot } from "./useSlots";
import { canSubmit, submitErrorMessage, useSubmitTask, type SubmitState } from "./useSubmitTask";

/**
 * 上传 / 提交结束后焦点去向（F-19）：有问题的上传位 → "重新提交"按钮（失败）→ 第一个上传位
 * （受理后上传位清空、按钮禁用）。
 */
const FOCUS_AFTER_SUBMIT = ['button[type="submit"]', 'input[type="file"]'];

type SubmitTemplate = Pick<TemplateDetail, "templateId" | "requiredImageCount" | "acceptedMimeTypes" | "maxFileSizeBytes">;

interface TaskSubmitFormProps {
  template: SubmitTemplate;
  /** 202 受理后的回调（任务 + 请求发起时的 session epoch），由详情页写入任务区（taskStore，T-053）。 */
  onAccepted?: (task: Task, epoch: number) => void;
}

/**
 * 上传区 + 提交（F-03、F-04）：选图在本地完成（T-031），点击提交时图片和模板 ID 在同一个请求中上传（OQ-05），
 * 显示上传进度 → "提交中" → 受理；上传 / 提交期间锁定上传位并禁用按钮；失败时保留已选文件并显示原因；
 * 受理后清空上传位（OQ-07 临时默认）并把任务交给 `onAccepted`。
 * 上传位数量变化时由调用方用 key 重新挂载。
 */
export function TaskSubmitForm({ template, onAccepted }: TaskSubmitFormProps) {
  const { state, busy, submit } = useSubmitTask(template.templateId);
  const { slots, allSelected, files, select, remove, applyServerErrors, clear } = useSlots(
    template.requiredImageCount,
    template,
    busy,
  );
  const [accepted, setAccepted] = useState<Task | null>(null);
  const hintId = useId();
  const formRef = useRef<HTMLFormElement>(null);
  const armFocus = useKeepFocus(formRef, busy, FOCUS_AFTER_SUBMIT);
  const enabled = canSubmit(allSelected, state);
  const hint = enabled ? null : pendingSlotsHint(slots);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!enabled) return;
    armFocus();
    setAccepted(null);
    submit(files, {
      onAccepted: (task, epoch) => {
        setAccepted(task);
        // OQ-07 临时默认：受理后清空上传区，便于制作下一张
        clear();
        onAccepted?.(task, epoch);
      },
      onFieldErrors: applyServerErrors,
    });
  }

  return (
    <form ref={formRef} onSubmit={handleSubmit} aria-label="提交制作" aria-busy={busy || undefined} className="flex flex-col gap-4" noValidate>
      <UploadSlots slots={slots} rules={template} locked={busy} onSelect={select} onRemove={remove} />
      <div className="flex flex-col gap-2">
        <div className="flex flex-wrap items-center gap-3">
          <button
            type="submit"
            disabled={!enabled}
            aria-describedby={hint ? hintId : undefined}
            className="rounded-md bg-zinc-900 px-4 py-2 text-sm font-medium text-white hover:bg-zinc-700 focus-visible:ring-2 focus-visible:ring-zinc-400 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
          >
            {buttonLabel(state)}
          </button>
          {hint && !busy && (
            <p id={hintId} className="text-sm text-zinc-500 dark:text-zinc-400">
              {hint}
            </p>
          )}
        </div>
        <SubmitProgress state={state} />
        {state.status === "error" && (
          <p role="alert" className="text-sm text-red-600 dark:text-red-400">
            {submitErrorMessage(state.error)}
          </p>
        )}
        {state.status === "idle" && accepted && (
          <p role="status" className="text-sm text-emerald-700 dark:text-emerald-400">
            提交成功，任务 #{accepted.taskId} 已受理，正在后台制作
          </p>
        )}
      </div>
    </form>
  );
}

function buttonLabel(state: SubmitState): string {
  switch (state.status) {
    case "uploading":
      return state.progress === null ? "上传中…" : `上传中 ${Math.round(state.progress * 100)}%`;
    case "submitting":
      return "提交中…";
    case "error":
      return "重新提交";
    default:
      return "提交制作";
  }
}

/** "素材上传中"与"提交中"两种状态的可见反馈，与任务处理状态区分（R-07.1）。 */
function SubmitProgress({ state }: { state: SubmitState }) {
  if (state.status === "uploading") {
    const percent = state.progress === null ? null : Math.round(state.progress * 100);
    return (
      <div className="flex items-center gap-3">
        <progress
          aria-label="上传进度"
          max={100}
          value={percent ?? undefined}
          className="h-2 w-full max-w-xs overflow-hidden rounded accent-zinc-900 dark:accent-zinc-100"
        />
        <span role="status" className="text-sm text-zinc-600 dark:text-zinc-400">
          {percent === null ? "素材上传中…" : `素材上传中 ${percent}%`}
        </span>
      </div>
    );
  }
  if (state.status === "submitting") {
    return (
      <p role="status" className="text-sm text-zinc-600 dark:text-zinc-400">
        素材已上传，正在提交…
      </p>
    );
  }
  return null;
}

/** 按钮禁用时提示缺少或有问题的上传位（F-04 异常情况）。 */
function pendingSlotsHint(slots: readonly Slot[]): string | null {
  const missing: number[] = [];
  const invalid: number[] = [];
  slots.forEach((slot, index) => {
    if (slot.status === "empty") missing.push(index + 1);
    else if (slot.status === "invalid") (slot.file ? invalid : missing).push(index + 1);
  });
  const parts: string[] = [];
  if (missing.length > 0) parts.push(`请选择第 ${missing.join("、")} 张图片`);
  if (invalid.length > 0) parts.push(`请替换第 ${invalid.join("、")} 张图片`);
  return parts.length > 0 ? parts.join("；") : null;
}
