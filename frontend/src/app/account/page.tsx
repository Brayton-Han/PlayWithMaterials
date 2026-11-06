import type { Metadata } from "next";

import { AccountSettings } from "@/features/account/AccountSettings";
import { RequireAuth } from "@/features/auth/RequireAuth";

export const metadata: Metadata = { title: "账户设置 · 小影" };

export default function AccountPage() {
  return (
    <section className="flex flex-col gap-6">
      <h1 className="text-2xl font-semibold">账户设置</h1>
      <RequireAuth>
        <AccountSettings />
      </RequireAuth>
    </section>
  );
}
