"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import type { ErrorDetails } from "@/lib/api/client";
import type { TemplateDetail } from "@/lib/api/endpoints/catalog";
import { formatFileSize, formatMimeTypes } from "@/features/catalog/TemplateRequirements";

export type FieldError = NonNullable<ErrorDetails["fieldErrors"]>[number];

/** 素材规则，全部来自模板详情接口（F-02 规则 1）。 */
export type SlotRules = Pick<TemplateDetail, "acceptedMimeTypes" | "maxFileSizeBytes">;

/**
 * 单个上传位的状态（architecture §6 前端异步状态分层）。
 * - `empty`：未选择；
 * - `selected`：已选择合法文件，`previewUrl` 是本地 blob URL，只用于预览（R-03.3）。
 *   `notice` 表示用户试图换成一个不合法的文件而被拒绝，原文件保留；
 * - `invalid`：有错误、不能提交。来自客户端校验时没有文件；来自服务端（400 带 slotIndex）时保留原文件和预览，
 *   用户替换或移除后清除。
 */
export type Slot =
  | { status: "empty" }
  | { status: "selected"; file: File; previewUrl: string; notice?: string }
  | { status: "invalid"; reason: string; file?: File; previewUrl?: string };

/** 客户端校验（F-03 规则 4）：空文件、类型、大小；合法返回 null。服务端会独立复核（R-03.2）。 */
export function validateFile(file: File, slotIndex: number, rules: SlotRules): string | null {
  const name = `第 ${slotIndex + 1} 张图片`;
  if (!(rules.acceptedMimeTypes as readonly string[]).includes(file.type)) {
    return `${name}格式不支持，仅支持 ${formatMimeTypes(rules.acceptedMimeTypes)}`;
  }
  if (file.size === 0) return `${name}是空文件，请重新选择`;
  if (file.size > rules.maxFileSizeBytes) {
    return `${name}超过单张上限 ${formatFileSize(rules.maxFileSizeBytes)}`;
  }
  return null;
}

function emptySlots(count: number): Slot[] {
  return Array.from({ length: count }, () => ({ status: "empty" }));
}

function previewOf(slot: Slot): string | undefined {
  return slot.status === "empty" ? undefined : slot.previewUrl;
}

export interface UseSlotsResult {
  slots: readonly Slot[];
  /** 全部上传位都是 `selected`（OQ-04 临时默认：恰好 N 张，全部必填）。 */
  allSelected: boolean;
  /** 按上传位顺序的文件；未选齐时为 null。提交时把文件本身发给服务端（T-032）。 */
  files: File[] | null;
  /** 选择 / 替换第 index 个上传位；不合法的文件不进入上传位。锁定时忽略。 */
  select: (index: number, file: File) => void;
  /** 清空第 index 个上传位。锁定时忽略。 */
  remove: (index: number) => void;
  /** 把服务端 400 的 `fieldErrors[].slotIndex` 标到对应上传位，保留已选文件（F-03 规则 7）。 */
  applyServerErrors: (errors: readonly FieldError[]) => void;
}

/**
 * 上传位本地状态（F-03）：只在内存中保存 File 和 blob URL，选择时不发任何请求。
 * blob URL 在替换、移除和卸载时释放。`locked` 为 true（上传 / 提交中）时不能选择、替换或移除（F-03 规则 8）。
 * 上传位数量变化时由调用方用 key 重新挂载。
 */
export function useSlots(count: number, rules: SlotRules, locked = false): UseSlotsResult {
  const [slots, setSlots] = useState<Slot[]>(() => emptySlots(count));
  // 事件处理里读最新状态并在 setState 之外做 revoke（updater 必须是纯函数）
  const latest = useRef<Slot[]>(slots);

  const update = useCallback((index: number, next: Slot) => {
    const current = latest.current;
    const previous = current[index];
    if (!previous) return;
    const oldUrl = previewOf(previous);
    if (oldUrl && oldUrl !== previewOf(next)) URL.revokeObjectURL(oldUrl);
    const copy = current.slice();
    copy[index] = next;
    latest.current = copy;
    setSlots(copy);
  }, []);

  const select = useCallback(
    (index: number, file: File) => {
      if (locked) return;
      const previous = latest.current[index];
      if (!previous) return;
      const reason = validateFile(file, index, rules);
      if (reason) {
        // 不合法的文件不进入上传位；已有的合法文件保留
        update(
          index,
          previous.status === "selected" ? { ...previous, notice: reason } : { status: "invalid", reason },
        );
        return;
      }
      update(index, { status: "selected", file, previewUrl: URL.createObjectURL(file) });
    },
    [locked, rules, update],
  );

  const remove = useCallback(
    (index: number) => {
      if (locked) return;
      update(index, { status: "empty" });
    },
    [locked, update],
  );

  const applyServerErrors = useCallback(
    (errors: readonly FieldError[]) => {
      for (const error of errors) {
        if (error.slotIndex === undefined) continue;
        const previous = latest.current[error.slotIndex];
        if (!previous) continue;
        update(error.slotIndex, {
          status: "invalid",
          reason: error.message,
          file: previous.status === "empty" ? undefined : previous.file,
          previewUrl: previewOf(previous),
        });
      }
    },
    [update],
  );

  // 卸载时释放所有仍在使用的 blob URL
  useEffect(() => {
    const ref = latest;
    return () => {
      for (const slot of ref.current) {
        const url = previewOf(slot);
        if (url) URL.revokeObjectURL(url);
      }
    };
  }, []);

  const allSelected = slots.every((slot) => slot.status === "selected");
  const files = allSelected ? slots.map((slot) => (slot as Extract<Slot, { status: "selected" }>).file) : null;

  return { slots, allSelected, files, select, remove, applyServerErrors };
}
