import type { Metadata } from "next";
import Link from "next/link";

import { AuthProvider } from "@/features/auth/AuthProvider";
import { UserMenu } from "@/features/auth/UserMenu";

import "./globals.css";

export const metadata: Metadata = {
  title: "小影 · 素材制作",
  description: "选择模板、上传图片，异步生成素材",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="zh-CN" className="h-full antialiased">
      <body className="flex min-h-full flex-col bg-background text-foreground">
        <AuthProvider>
          <header className="border-b border-zinc-200 dark:border-zinc-800">
            <div className="mx-auto flex h-14 w-full max-w-6xl items-center justify-between px-4">
              <Link href="/" className="text-base font-semibold">
                小影
              </Link>
              <UserMenu />
            </div>
          </header>
          <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col px-4 py-8">{children}</main>
        </AuthProvider>
      </body>
    </html>
  );
}
