"use client";

import { useEffect, useId, useRef, useState, type FormEvent } from "react";

import { TextField } from "@/components/TextField";
import { useAuth } from "@/features/auth/AuthProvider";
import { formErrorMessage } from "@/features/auth/formErrors";
import { isAbortError, isApiError } from "@/lib/api/client";

/** 必须完全一致：区分大小写、不去除首尾空格（OQ-26 余项临时默认）。服务端同样校验。 */
export const DELETE_CONFIRM_TEXT = "DELETE";

const dangerButtonClass =
  "rounded-md bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 focus-visible:ring-2 focus-visible:ring-red-400 focus-visible:ring-offset-2 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50";

function Consequences() {
  return (
    <ul className="list-disc space-y-1 pl-5 text-sm text-zinc-600 dark:text-zinc-400">
      <li>账户删除后无法再登录，你的任务记录、上传的素材和结果图都将无法再访问。</li>
      <li>正在制作的任务会被废弃，不会再产出结果。</li>
      <li>邮箱之后可以重新注册新账户，用户名不能再使用。</li>
    </ul>
  );
}

/** 账户设置页底部的"删除账户"区：说明 + 打开确认对话框的按钮。关闭对话框后焦点回到该按钮。 */
export function DeleteAccountSection() {
  const headingId = useId();
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const wasOpen = useRef(false);

  // 对话框卸载（模态解除）后再把焦点还给触发按钮
  useEffect(() => {
    if (wasOpen.current && !open) triggerRef.current?.focus();
    wasOpen.current = open;
  }, [open]);

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4 rounded-lg border border-red-200 p-6 dark:border-red-900">
      <h2 id={headingId} className="text-lg font-semibold text-red-700 dark:text-red-400">
        删除账户
      </h2>
      <Consequences />
      <button
        ref={triggerRef}
        type="button"
        aria-haspopup="dialog"
        onClick={() => setOpen(true)}
        className={`self-start ${dangerButtonClass}`}
      >
        删除账户
      </button>
      {open && <DeleteAccountDialog onClose={() => setOpen(false)} />}
    </section>
  );
}

/**
 * 删除账户确认对话框（F-28 规则 3）：原生模态 `<dialog>`，打开后焦点进入确认输入框；
 * 输入框内容完全等于 `DELETE` 时确认按钮才可用。取消 / Escape 关闭且不发请求；删除中不能关闭。
 * 删除成功后由 AuthProvider 执行退出清理并跳转首页（本组件随会话切换卸载）；失败时保持登录并显示原因。
 */
export function DeleteAccountDialog({ onClose }: { onClose: () => void }) {
  const { deleteAccount } = useAuth();
  const titleId = useId();
  const descriptionId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const [confirmText, setConfirmText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (!dialog.open) dialog.showModal();
    inputRef.current?.focus();
    return () => {
      if (dialog.open) dialog.close();
    };
  }, []);

  const confirmed = confirmText === DELETE_CONFIRM_TEXT;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!confirmed || deleting) return;
    setDeleting(true);
    setError(null);
    try {
      await deleteAccount(confirmText);
      return; // 成功：会话切换后本组件卸载
    } catch (err) {
      // 会话被切换（如全局 401）时请求被取消，本组件随之卸载
      if (isAbortError(err)) return;
      setError(
        isApiError(err) && err.code === "CONFIRM_TEXT_MISMATCH"
          ? err.message
          : `删除失败，账户未删除：${formErrorMessage(err)}`,
      );
    }
    setDeleting(false);
  }

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby={titleId}
      aria-describedby={descriptionId}
      onCancel={(event) => {
        // Escape：交给 React 状态关闭；删除中不允许关闭
        event.preventDefault();
        if (!deleting) onClose();
      }}
      className="m-auto w-[calc(100%-2rem)] max-w-md rounded-lg border border-zinc-200 bg-background p-6 text-foreground shadow-xl backdrop:bg-black/50 dark:border-zinc-800"
    >
      <form noValidate onSubmit={handleSubmit} className="flex flex-col gap-4">
        <h2 id={titleId} className="text-lg font-semibold">
          确认删除账户？
        </h2>
        <div id={descriptionId} className="flex flex-col gap-2">
          <p className="text-sm">此操作无法撤销。</p>
          <Consequences />
        </div>
        <TextField
          ref={inputRef}
          label={`请输入 ${DELETE_CONFIRM_TEXT} 以确认`}
          name="confirmText"
          autoComplete="off"
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          value={confirmText}
          disabled={deleting}
          onChange={(event) => {
            setConfirmText(event.target.value);
            setError(null);
          }}
        />
        {error && (
          <p role="alert" className="text-sm text-red-600 dark:text-red-400">
            {error}
          </p>
        )}
        <div className="flex flex-wrap justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            disabled={deleting}
            className="rounded-md border border-zinc-300 px-4 py-2 text-sm hover:bg-zinc-100 focus-visible:ring-2 focus-visible:ring-zinc-400 focus-visible:outline-none disabled:opacity-60 dark:border-zinc-700 dark:hover:bg-zinc-800"
          >
            取消
          </button>
          <button type="submit" disabled={!confirmed || deleting} className={dangerButtonClass}>
            {deleting ? "删除中…" : "永久删除账户"}
          </button>
        </div>
      </form>
    </dialog>
  );
}
