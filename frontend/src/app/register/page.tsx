import type { Metadata } from "next";
import { Suspense } from "react";

import { RegisterForm } from "@/features/auth/RegisterForm";

export const metadata: Metadata = { title: "注册 · 小影" };

export default function RegisterPage() {
  return (
    <section className="mx-auto flex w-full max-w-sm flex-col gap-6">
      <h1 className="text-2xl font-semibold">注册</h1>
      <Suspense fallback={null}>
        <RegisterForm />
      </Suspense>
    </section>
  );
}
