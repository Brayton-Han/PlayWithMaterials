import type { Metadata } from "next";
import { Suspense } from "react";

import { LoginForm } from "@/features/auth/LoginForm";

export const metadata: Metadata = { title: "登录 · 小影" };

export default function LoginPage() {
  return (
    <section className="mx-auto flex w-full max-w-sm flex-col gap-6">
      <h1 className="text-2xl font-semibold">登录</h1>
      {/* LoginForm 读取 ?next=，useSearchParams 需要 Suspense 边界才能预渲染页面其余部分 */}
      <Suspense fallback={null}>
        <LoginForm />
      </Suspense>
    </section>
  );
}
