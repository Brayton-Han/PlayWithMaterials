"use client";

import { useEffect, useState } from "react";

import { isAbortError, isApiError } from "./api/client";

const FALLBACK_ERROR_MESSAGE = "加载失败，请稍后重试";
const INTERRUPTED_MESSAGE = "加载被中断，请重试";

export type LoadOutcome<T> =
  | { status: "loading" }
  /** error 为原始错误（通常是 ApiError），供调用方区分 404 等；message 可直接展示 */
  | { status: "error"; message: string; error: unknown }
  | { status: "ready"; data: T };

/**
 * 按 key 加载数据：key 变化或重试时 abort 旧请求；结果按 (key, attempt) 归属，
 * 只有与当前 key 匹配的结果才会展示，较慢的旧响应不会覆盖新结果（F-01 验收 3）。
 * key 为 null 表示依赖尚未就绪，保持 loading 且不发请求。
 *
 * `load` 需是稳定引用（模块级函数），否则每次渲染都会重新请求。
 */
export function useKeyedLoad<T>(key: string | null, load: (key: string, signal: AbortSignal) => Promise<T>) {
  const [attempt, setAttempt] = useState(0);
  const [settled, setSettled] = useState<{ key: string; attempt: number; outcome: LoadOutcome<T> } | null>(null);

  useEffect(() => {
    if (key === null) return;
    const controller = new AbortController();
    load(key, controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) setSettled({ key, attempt, outcome: { status: "ready", data } });
      },
      (error: unknown) => {
        if (controller.signal.aborted) return;
        // 被会话级取消（退出登录时 abortAllRequests）的请求不能卡在 loading，转为可重试的错误
        const message = isAbortError(error)
          ? INTERRUPTED_MESSAGE
          : isApiError(error)
            ? error.message
            : FALLBACK_ERROR_MESSAGE;
        setSettled({ key, attempt, outcome: { status: "error", message, error } });
      },
    );
    return () => controller.abort();
  }, [key, attempt, load]);

  const outcome: LoadOutcome<T> =
    settled && settled.key === key && settled.attempt === attempt ? settled.outcome : { status: "loading" };
  return { outcome, retry: () => setAttempt((n) => n + 1) };
}
