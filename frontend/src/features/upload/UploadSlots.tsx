"use client";

import { useId, useState, type ChangeEvent, type DragEvent } from "react";

import type { Slot, SlotRules } from "./useSlots";

interface UploadSlotsProps {
  slots: readonly Slot[];
  rules: SlotRules;
  /** 上传 / 提交中：不能选择、替换、移除或拖入（F-03 规则 8）。 */
  locked?: boolean;
  onSelect: (index: number, file: File) => void;
  onRemove: (index: number) => void;
}

/**
 * 上传位列表（F-03）：每位点击或拖入选择 1 张图片，本地预览，可替换、移除。
 * 文件输入框带 `aria-label="选择第 n 张图片"`，移除按钮带 `aria-label="移除第 n 张图片"`（F-19）。
 */
export function UploadSlots({ slots, rules, locked = false, onSelect, onRemove }: UploadSlotsProps) {
  return (
    <ol aria-label="上传位" className="grid grid-cols-2 gap-3 sm:grid-cols-3">
      {slots.map((slot, index) => (
        <UploadSlotItem
          key={index}
          index={index}
          slot={slot}
          accept={rules.acceptedMimeTypes.join(",")}
          locked={locked}
          onSelect={onSelect}
          onRemove={onRemove}
        />
      ))}
    </ol>
  );
}

interface UploadSlotItemProps {
  index: number;
  slot: Slot;
  accept: string;
  locked: boolean;
  onSelect: (index: number, file: File) => void;
  onRemove: (index: number) => void;
}

function UploadSlotItem({ index, slot, accept, locked, onSelect, onRemove }: UploadSlotItemProps) {
  const inputId = useId();
  const messageId = useId();
  const [dragOver, setDragOver] = useState(false);
  const n = index + 1;
  const previewUrl = slot.status === "empty" ? undefined : slot.previewUrl;
  const file = slot.status === "empty" ? undefined : slot.file;
  const message = slot.status === "invalid" ? slot.reason : slot.status === "selected" ? slot.notice : undefined;
  const invalid = slot.status === "invalid";

  function handleChange(event: ChangeEvent<HTMLInputElement>) {
    const picked = event.target.files?.[0];
    // 清空 value，重新选择同一个文件也会触发 change
    event.target.value = "";
    if (picked) onSelect(index, picked);
  }

  function handleDragOver(event: DragEvent<HTMLLabelElement>) {
    if (locked) return;
    event.preventDefault();
    setDragOver(true);
  }

  function handleDrop(event: DragEvent<HTMLLabelElement>) {
    event.preventDefault();
    setDragOver(false);
    if (locked) return;
    const dropped = event.dataTransfer.files[0];
    if (dropped) onSelect(index, dropped);
  }

  const frame = invalid
    ? "border-red-400 dark:border-red-700"
    : dragOver
      ? "border-zinc-900 dark:border-zinc-100"
      : "border-zinc-300 dark:border-zinc-700";

  return (
    <li className="flex flex-col gap-1">
      <div className="relative">
        <input
          id={inputId}
          type="file"
          accept={accept}
          aria-label={`选择第 ${n} 张图片`}
          aria-invalid={invalid || undefined}
          aria-describedby={message ? messageId : undefined}
          disabled={locked}
          onChange={handleChange}
          className="peer sr-only"
        />
        <label
          htmlFor={inputId}
          onDragOver={handleDragOver}
          onDragLeave={() => setDragOver(false)}
          onDrop={handleDrop}
          className={`flex aspect-square items-center justify-center overflow-hidden rounded-lg border border-dashed text-sm text-zinc-500 peer-focus-visible:ring-2 peer-focus-visible:ring-zinc-400 ${frame} ${
            locked ? "cursor-not-allowed opacity-60" : "cursor-pointer hover:bg-zinc-50 dark:hover:bg-zinc-900"
          }`}
        >
          {previewUrl ? (
            // blob URL 本地预览，不走 next/image
            // eslint-disable-next-line @next/next/no-img-element
            <img src={previewUrl} alt={`第 ${n} 张图片预览`} className="h-full w-full object-cover" />
          ) : (
            <span className="px-2 text-center">
              第 {n} 张
              <br />
              点击或拖入图片
            </span>
          )}
        </label>
        {file && (
          <button
            type="button"
            aria-label={`移除第 ${n} 张图片`}
            disabled={locked}
            onClick={() => onRemove(index)}
            className="absolute top-1 right-1 rounded-full bg-zinc-900/70 px-2 py-0.5 text-xs text-white hover:bg-zinc-900 focus-visible:ring-2 focus-visible:ring-zinc-400 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50"
          >
            移除
          </button>
        )}
      </div>
      {file && (
        <p className="truncate text-xs text-zinc-500 dark:text-zinc-400" title={file.name}>
          {file.name}
        </p>
      )}
      {message && (
        <p id={messageId} role="alert" className="text-xs text-red-600 dark:text-red-400">
          {message}
        </p>
      )}
    </li>
  );
}
