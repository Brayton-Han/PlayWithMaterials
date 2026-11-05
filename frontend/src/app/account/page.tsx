import type { Metadata } from "next";

import { RequireAuth } from "@/features/auth/RequireAuth";

export const metadata: Metadata = { title: "账户设置 · 小影" };

export default function AccountPage() {
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">账户设置</h1>
      <RequireAuth>
        <p className="text-zinc-600 dark:text-zinc-400">修改密码、删除账户将在 T-013 中实现。</p>
      </RequireAuth>
    </section>
  );
}
