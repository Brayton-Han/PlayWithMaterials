"use client";

import { useEffect, useState } from "react";

import { isAbortError, isApiError } from "@/lib/api/client";
import { getHealth } from "@/lib/api/endpoints/health";

type State =
  | { kind: "loading" }
  | { kind: "up" }
  | { kind: "down"; message: string };

/** 骨架阶段的示例：经 rewrites 同源调用后端 /actuator/health 并显示结果。 */
export function BackendStatus() {
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    const controller = new AbortController();
    getHealth(controller.signal)
      .then((res) =>
        setState(res.status === "UP" ? { kind: "up" } : { kind: "down", message: "后端状态异常" }),
      )
      .catch((error: unknown) => {
        if (isAbortError(error)) return;
        setState({ kind: "down", message: isApiError(error) ? error.message : "未知错误" });
      });
    return () => controller.abort();
  }, []);

  const label =
    state.kind === "loading" ? "检查中…" : state.kind === "up" ? "后端正常（UP）" : `后端不可用：${state.message}`;
  const dot =
    state.kind === "loading" ? "bg-zinc-400" : state.kind === "up" ? "bg-emerald-500" : "bg-red-500";

  return (
    <p role="status" className="inline-flex items-center gap-2 text-sm text-zinc-600 dark:text-zinc-300">
      <span aria-hidden className={`inline-block h-2.5 w-2.5 rounded-full ${dot}`} />
      {label}
    </p>
  );
}
