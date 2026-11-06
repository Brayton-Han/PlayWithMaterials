"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";

import { useAuth, type AuthContextValue } from "./AuthProvider";
import { authPageHref, currentBrowserPath } from "./session";

/**
 * 需要登录的页面 / 区块使用。
 *
 * - `redirect: true`（我的记录、账户设置）：确认未登录后跳转 `/login?next=<当前路径>`；
 * - 默认不跳转（模板详情页）：调用方按 `status` 决定显示上传区和 History，还是 {@link LoginPrompt}。
 * - 刚删除账户时不跳转登录页：AuthProvider 已负责跳转首页（F-28 规则 7），避免两次跳转互相覆盖。
 *
 * 只影响体验；权限由后端保证。
 */
export function useRequireAuth(options: { redirect?: boolean } = {}): AuthContextValue {
  const { redirect = false } = options;
  const auth = useAuth();
  const router = useRouter();
  const shouldRedirect = redirect && auth.status === "anonymous" && !auth.accountDeleted;

  useEffect(() => {
    if (shouldRedirect) router.replace(authPageHref("/login", currentBrowserPath()));
  }, [shouldRedirect, router]);

  return auth;
}
