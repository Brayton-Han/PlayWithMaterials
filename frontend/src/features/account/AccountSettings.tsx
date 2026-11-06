"use client";

import { useId } from "react";

import { useAuth } from "@/features/auth/AuthProvider";

import { ChangePasswordForm } from "./ChangePasswordForm";
import { DeleteAccountSection } from "./DeleteAccountDialog";

/**
 * 账户设置页内容（F-28 规则 1）：只有账户信息、修改密码、删除账户三部分；
 * 不包含 Billing / Terms / Privacy / Subscription（OQ-02）。需放在 RequireAuth 内。
 */
export function AccountSettings() {
  const { user } = useAuth();
  if (!user) return null;
  return (
    <div className="flex max-w-2xl flex-col gap-6">
      <AccountInfo username={user.username} email={user.email} />
      <ChangePasswordForm />
      <DeleteAccountSection />
    </div>
  );
}

/** 账户信息（OQ-27）：默认头像 + 用户名 + 邮箱，均只读。 */
function AccountInfo({ username, email }: { username: string; email: string }) {
  const headingId = useId();
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4 rounded-lg border border-zinc-200 p-6 dark:border-zinc-800">
      <h2 id={headingId} className="text-lg font-semibold">
        账户信息
      </h2>
      <div className="flex min-w-0 items-center gap-4">
        <span
          aria-hidden
          className="flex h-14 w-14 shrink-0 items-center justify-center rounded-full bg-zinc-200 text-zinc-500 dark:bg-zinc-800 dark:text-zinc-400"
        >
          <svg viewBox="0 0 24 24" className="h-8 w-8" fill="currentColor">
            <path d="M12 12a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9Zm0 2c-4.1 0-7.5 2.2-7.5 5v1.5h15V19c0-2.8-3.4-5-7.5-5Z" />
          </svg>
        </span>
        <dl className="grid min-w-0 grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="text-zinc-500">用户名</dt>
          <dd className="truncate font-medium">{username}</dd>
          <dt className="text-zinc-500">邮箱</dt>
          <dd className="break-all">{email}</dd>
        </dl>
      </div>
      <p className="text-xs text-zinc-500">用户名和邮箱注册后不能修改。</p>
    </section>
  );
}
