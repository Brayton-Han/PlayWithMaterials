"use client";

import { useEffect, useId, useRef, useState } from "react";

/**
 * 结果预览对话框（F-11 规则 5）：原生模态 `<dialog>`，打开后焦点在"关闭"按钮上；
 * Escape、"关闭"按钮或点击遮罩关闭。焦点归还由打开它的组件负责（卸载后再聚焦触发按钮）。
 */
export function ResultPreviewDialog({
  url,
  title,
  onClose,
}: {
  url: string;
  title: string;
  onClose: () => void;
}) {
  const titleId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (!dialog.open) dialog.showModal();
    closeRef.current?.focus();
    return () => {
      if (dialog.open) dialog.close();
    };
  }, []);

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby={titleId}
      onCancel={(event) => {
        // Escape：交给 React 状态关闭
        event.preventDefault();
        onClose();
      }}
      onClick={(event) => {
        // 点击遮罩（事件目标是 dialog 本身）关闭
        if (event.target === event.currentTarget) onClose();
      }}
      className="m-auto w-[calc(100%-2rem)] max-w-3xl rounded-lg border border-zinc-200 bg-background p-4 text-foreground shadow-xl backdrop:bg-black/60 dark:border-zinc-800"
    >
      <div className="flex flex-col gap-3">
        <div className="flex items-center justify-between gap-4">
          <h2 id={titleId} className="text-lg font-semibold">
            {title}
          </h2>
          <button
            ref={closeRef}
            type="button"
            onClick={onClose}
            className="rounded-md border border-zinc-300 px-3 py-1 text-sm hover:bg-zinc-100 focus-visible:ring-2 focus-visible:ring-zinc-400 focus-visible:outline-none dark:border-zinc-700 dark:hover:bg-zinc-800"
          >
            关闭
          </button>
        </div>
        {failed ? (
          <div
            role="img"
            aria-label="结果加载失败"
            className="flex aspect-[4/3] items-center justify-center rounded bg-zinc-100 text-sm text-zinc-500 dark:bg-zinc-900"
          >
            结果加载失败
          </div>
        ) : (
          // 结果图是后端经 rewrites 提供的同源受保护文件（带 Cookie），不走 next/image
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={url}
            alt={`${title}大图`}
            onError={() => setFailed(true)}
            className="max-h-[75vh] w-full rounded object-contain"
          />
        )}
      </div>
    </dialog>
  );
}
