"use client";

import type { ReactNode } from "react";

import { useRequireAuth } from "./useRequireAuth";

/** 路由守卫：已登录才渲染 children，未登录跳转登录页（带回跳地址）。 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useRequireAuth({ redirect: true });

  if (status === "authenticated") return <>{children}</>;
  return (
    <p role="status" className="text-sm text-zinc-500">
      {status === "loading" ? "正在确认登录状态…" : "请先登录，正在跳转到登录页…"}
    </p>
  );
}
