"use client";

import type { ReactNode } from "react";

import { useRequireAuth } from "./useRequireAuth";

/** 路由守卫：已登录才渲染 children，未登录跳转登录页（带回跳地址）。 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status, accountDeleted } = useRequireAuth({ redirect: true });

  if (status === "authenticated") return <>{children}</>;
  let message = "请先登录，正在跳转到登录页…";
  if (status === "loading") message = "正在确认登录状态…";
  else if (accountDeleted) message = "账户已删除，正在返回首页…";
  return (
    <p role="status" className="text-sm text-zinc-500">
      {message}
    </p>
  );
}
