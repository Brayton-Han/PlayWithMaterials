import type { Metadata } from "next";

import { RequireAuth } from "@/features/auth/RequireAuth";
import { RecordsPanel } from "@/features/tasks/RecordsPanel";

export const metadata: Metadata = { title: "我的记录 · 小影" };

export default function RecordsPage() {
  return (
    <section className="flex w-full max-w-2xl flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold">我的记录</h1>
        <p className="text-sm text-zinc-500 dark:text-zinc-400">全部模板的制作记录，按创建时间从新到旧排列</p>
      </div>
      <RequireAuth>
        <RecordsPanel />
      </RequireAuth>
    </section>
  );
}
