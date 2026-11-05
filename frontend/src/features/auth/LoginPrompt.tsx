import Link from "next/link";

import { authPageHref } from "./session";

/** 未登录时替代上传区 / History 的提示（OQ-23 余项临时默认）。`next` 为登录后要回到的站内路径。 */
export function LoginPrompt({ next, message = "登录后即可制作" }: { next: string; message?: string }) {
  return (
    <div className="flex flex-col items-start gap-3 rounded-lg border border-dashed border-zinc-300 p-6 dark:border-zinc-700">
      <p className="text-zinc-700 dark:text-zinc-300">{message}</p>
      <div className="flex gap-3 text-sm">
        <Link
          href={authPageHref("/login", next)}
          className="rounded-md bg-zinc-900 px-4 py-2 font-medium text-white hover:bg-zinc-700 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
        >
          登录
        </Link>
        <Link href={authPageHref("/register", next)} className="px-2 py-2 text-zinc-600 underline dark:text-zinc-400">
          注册新账户
        </Link>
      </div>
    </div>
  );
}
