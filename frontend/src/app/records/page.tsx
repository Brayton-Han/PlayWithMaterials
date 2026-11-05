import type { Metadata } from "next";

import { RequireAuth } from "@/features/auth/RequireAuth";

export const metadata: Metadata = { title: "我的记录 · 小影" };

export default function RecordsPage() {
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">我的记录</h1>
      <RequireAuth>
        <p className="text-zinc-600 dark:text-zinc-400">记录列表将在 T-055 中实现。</p>
      </RequireAuth>
    </section>
  );
}
