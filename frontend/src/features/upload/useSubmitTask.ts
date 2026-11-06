"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { getSessionEpoch, isCurrentEpoch } from "@/features/auth/session";
import { ApiError, isAbortError, isApiError, NETWORK_ERROR_MESSAGE } from "@/lib/api/client";
import { createTask, type Task } from "@/lib/api/endpoints/tasks";

import type { FieldError } from "./useSlots";

/**
 * 提交请求的状态（architecture §6 前端异步状态分层，F-03 规则 5，R-07.1）。
 * 与任务本身的状态（PENDING / PROCESSING …）无关：这里只描述"这一次 POST /api/tasks"。
 * - `uploading`：请求体发送中，`progress` 为 0 ~ 1，浏览器无法计算总长时为 null；
 * - `submitting`：文件已发完，等待服务端校验 / 入库 / 受理；
 * - `error`：本次提交失败，已选文件保留，可直接重新提交（F-03 规则 6）。
 */
export type SubmitState =
  | { status: "idle" }
  | { status: "uploading"; progress: number | null }
  | { status: "submitting" }
  | { status: "error"; error: ApiError };

const IDLE: SubmitState = { status: "idle" };

/** 上传中或提交中：按钮禁用、上传位锁定（F-03 规则 8，F-04 规则 2）。 */
export function isSubmitBusy(state: SubmitState): boolean {
  return state.status === "uploading" || state.status === "submitting";
}

/** 可提交：全部上传位为 `selected`（OQ-04 临时默认：恰好 N 张），且当前处于 idle 或 error（F-04 规则 1）。 */
export function canSubmit(allSelected: boolean, state: SubmitState): boolean {
  return allSelected && (state.status === "idle" || state.status === "error");
}

/** 服务端返回的、能定位到上传位的错误（400 ASSET_VALIDATION_FAILED 等，F-03 规则 7）。 */
export function slotFieldErrors(error: ApiError): FieldError[] {
  return (error.details?.fieldErrors ?? []).filter((e) => e.slotIndex !== undefined);
}

/** 提交失败时展示给用户的文案（F-03 / F-04 异常情况）。 */
export function submitErrorMessage(error: ApiError): string {
  if (error.kind === "network") return "上传失败：网络连接异常，已选图片已保留，可直接重新提交";
  if (slotFieldErrors(error).length > 0) return `提交失败：${error.message}，请替换标出的图片后重新提交`;
  if (error.status === 413) return `上传失败：${error.message}`;
  if (error.status !== undefined && error.status >= 500) return `提交失败：${error.message}，可直接重新提交`;
  return `提交失败：${error.message}`;
}

export interface SubmitHandlers {
  /**
   * 202 受理：返回的任务（PENDING）与请求发起时的 session epoch（写入 taskStore 时用于丢弃跨会话的结果）。
   * 只在 epoch 仍是当前值、请求未被取消时调用，调用前提交状态已回到 idle。
   */
  onAccepted?: (task: Task, epoch: number) => void;
  /** 400 带 slotIndex 的错误，交给 useSlots.applyServerErrors 标到对应上传位。 */
  onFieldErrors?: (errors: FieldError[]) => void;
}

export interface UseSubmitTaskResult {
  state: SubmitState;
  busy: boolean;
  /**
   * 发起提交；`files` 为 null（未选齐）或已有请求在途时忽略并返回 false。
   * 在途判断用 ref，同一事件循环内的双击也只发一次请求。
   */
  submit: (files: readonly File[] | null, handlers?: SubmitHandlers) => boolean;
}

/**
 * 提交请求状态（T-032）：上传进度 → 提交中 → 受理 / 失败。
 * 卸载（离开页面）时 abort；退出登录时由会话级 signal abort（upload.ts），迟到的结果按 epoch 丢弃。
 */
export function useSubmitTask(templateId: string): UseSubmitTaskResult {
  const [state, setState] = useState<SubmitState>(IDLE);
  const inFlight = useRef<AbortController | null>(null);

  useEffect(
    () => () => {
      inFlight.current?.abort();
      inFlight.current = null;
    },
    [],
  );

  const submit = useCallback(
    (files: readonly File[] | null, handlers: SubmitHandlers = {}) => {
      if (!files || files.length === 0 || inFlight.current) return false;
      const controller = new AbortController();
      inFlight.current = controller;
      const epoch = getSessionEpoch();
      const live = () => !controller.signal.aborted && isCurrentEpoch(epoch);
      const release = () => {
        if (inFlight.current === controller) inFlight.current = null;
      };

      setState({ status: "uploading", progress: 0 });
      createTask(templateId, files, {
        signal: controller.signal,
        onProgress: (progress) => {
          if (live()) setState((s) => (s.status === "uploading" ? { status: "uploading", progress } : s));
        },
        onUploaded: () => {
          if (live()) setState({ status: "submitting" });
        },
      }).then(
        (task) => {
          release();
          if (!live()) return;
          setState(IDLE);
          handlers.onAccepted?.(task, epoch);
        },
        (error: unknown) => {
          release();
          if (isAbortError(error)) {
            // 被取消（离开页面 / 退出登录）：不算失败
            if (isCurrentEpoch(epoch)) setState(IDLE);
            return;
          }
          // 401 时全局处理器已切换会话（epoch 已变），由跳转登录接管
          if (!live()) return;
          const apiError = isApiError(error)
            ? error
            : new ApiError({ kind: "network", message: NETWORK_ERROR_MESSAGE, cause: error });
          setState({ status: "error", error: apiError });
          const fieldErrors = slotFieldErrors(apiError);
          if (fieldErrors.length > 0) handlers.onFieldErrors?.(fieldErrors);
        },
      );
      return true;
    },
    [templateId],
  );

  return { state, busy: isSubmitBusy(state), submit };
}
