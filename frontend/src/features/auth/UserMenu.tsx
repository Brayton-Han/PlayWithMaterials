"use client";

import Link from "next/link";
import { useEffect, useId, useRef, useState } from "react";

import { useAuth } from "./AuthProvider";

const itemClass =
  "block w-full px-4 py-2 text-left text-sm hover:bg-zinc-100 focus-visible:bg-zinc-100 focus-visible:outline-none disabled:opacity-60 dark:hover:bg-zinc-800 dark:focus-visible:bg-zinc-800";

/** 顶栏右侧：未登录显示"登录 / 注册"，已登录显示用户菜单（我的记录、账户设置、退出）。 */
export function UserMenu() {
  const { status, user, logout } = useAuth();

  if (status === "loading") {
    return <span aria-hidden className="h-5 w-20 animate-pulse rounded bg-zinc-200 dark:bg-zinc-800" />;
  }
  if (status === "anonymous" || !user) {
    return (
      <nav aria-label="账户" className="flex items-center gap-1 text-sm">
        <Link href="/login" className="rounded-md px-3 py-1.5 hover:bg-zinc-100 dark:hover:bg-zinc-800">
          登录
        </Link>
        <span aria-hidden className="text-zinc-400">
          /
        </span>
        <Link href="/register" className="rounded-md px-3 py-1.5 hover:bg-zinc-100 dark:hover:bg-zinc-800">
          注册
        </Link>
      </nav>
    );
  }
  return <AccountDropdown username={user.username} onLogout={logout} />;
}

function AccountDropdown({ username, onLogout }: { username: string; onLogout: () => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelId = useId();

  // 点击外部或按 Escape 关闭；Escape 时焦点回到触发按钮
  useEffect(() => {
    if (!open) return;
    function onPointerDown(event: PointerEvent) {
      if (!containerRef.current?.contains(event.target as Node)) setOpen(false);
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setOpen(false);
        buttonRef.current?.focus();
      }
    }
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  async function handleLogout() {
    setLoggingOut(true);
    await onLogout(); // 成功后本组件随会话切换卸载
  }

  return (
    <div ref={containerRef} className="relative">
      <button
        ref={buttonRef}
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-1 rounded-md px-3 py-1.5 text-sm hover:bg-zinc-100 dark:hover:bg-zinc-800"
      >
        <span className="sr-only">账户菜单：</span>
        {username}
        <span aria-hidden className="text-xs text-zinc-500">
          ▾
        </span>
      </button>
      {open && (
        <ul
          id={panelId}
          className="absolute right-0 z-20 mt-1 w-40 overflow-hidden rounded-md border border-zinc-200 bg-background py-1 shadow-lg dark:border-zinc-800"
        >
          <li>
            <Link href="/records" className={itemClass} onClick={() => setOpen(false)}>
              我的记录
            </Link>
          </li>
          <li>
            <Link href="/account" className={itemClass} onClick={() => setOpen(false)}>
              账户设置
            </Link>
          </li>
          <li className="border-t border-zinc-200 dark:border-zinc-800">
            <button type="button" className={itemClass} disabled={loggingOut} onClick={handleLogout}>
              {loggingOut ? "退出中…" : "退出"}
            </button>
          </li>
        </ul>
      )}
    </div>
  );
}
