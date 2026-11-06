"use client";

/**
 * 身份上下文（architecture §4.4、§6）：`{ status, user, epoch }` + 登录 / 注册 / 退出。
 *
 * 会话切换（登录、注册、退出、任意业务接口 401）统一走 {@link switchSession}：
 *   1. abortAllRequests()：取消前一会话的全部在途请求；
 *   2. resetUserScopedState()：清空模块级用户状态（T-050 taskStore 等注册的清理函数）；
 *   3. epoch + 1：之后到达的旧响应校验 epoch 失败被丢弃；
 *   4. SessionBoundary 以 epoch 为 key 重新挂载页面内容，清空组件本地状态（如已选文件）。
 *
 * 删除账户（F-28 规则 7）成功后执行同样的清理，再跳转首页。
 */
import { Fragment, createContext, use, useEffect, useState, type ReactNode } from "react";
import { useRouter } from "next/navigation";

import { abortAllRequests, isAbortError, setUnauthorizedHandler } from "@/lib/api/client";
import * as accountApi from "@/lib/api/endpoints/account";
import * as authApi from "@/lib/api/endpoints/auth";
import type { LoginRequest, RegisterRequest, User } from "@/lib/api/endpoints/auth";

import {
  authPageHref,
  bumpSessionEpoch,
  currentBrowserPath,
  getSessionEpoch,
  isAuthPage,
  isCurrentEpoch,
  resetUserScopedState,
} from "./session";

export type AuthStatus = "loading" | "anonymous" | "authenticated";

export type AuthState =
  | { status: "loading"; user: null; epoch: number }
  | { status: "anonymous"; user: null; epoch: number; accountDeleted?: boolean }
  | { status: "authenticated"; user: User; epoch: number };

export interface AuthContextValue {
  status: AuthStatus;
  user: User | null;
  epoch: number;
  /** 本次进入未登录状态是因为刚删除了账户（受保护页此时不跳转登录页，而是回到首页）。 */
  accountDeleted: boolean;
  login(body: LoginRequest): Promise<User>;
  register(body: RegisterRequest): Promise<User>;
  /** 退出：取消在途请求、通知后端删除会话、清空用户状态。停留在当前页，受保护页由 RequireAuth 处理。 */
  logout(): Promise<void>;
  /**
   * 删除当前账户（F-28）。成功后执行与退出相同的清理并跳转首页；
   * 失败时原样抛出错误，不做任何清理，用户仍处于登录状态。
   */
  deleteAccount(confirmText: string): Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/** 结束当前会话的前端状态，返回新的 epoch。 */
function switchSession(): number {
  abortAllRequests();
  resetUserScopedState();
  return bumpSessionEpoch();
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const router = useRouter();
  const [state, setState] = useState<AuthState>(() => ({
    status: "loading",
    user: null,
    epoch: getSessionEpoch(),
  }));

  // 首次加载：以 /api/auth/me 判断登录状态；此接口的 401 只表示"未登录"。
  useEffect(() => {
    const startedAt = getSessionEpoch();
    const controller = new AbortController();
    authApi
      .getCurrentUser(controller.signal)
      .then((user) => {
        if (isCurrentEpoch(startedAt)) setState({ status: "authenticated", user, epoch: startedAt });
      })
      .catch((error: unknown) => {
        if (isAbortError(error) || !isCurrentEpoch(startedAt)) return;
        // 401 → 未登录；网络 / 5xx 也按未登录处理，需要登录的操作会再次被后端拒绝
        setState({ status: "anonymous", user: null, epoch: startedAt });
      });
    return () => controller.abort();
  }, []);

  // 全局 401：会话缺失 / 过期 / 账户已删除 → 退出清理并跳转登录，带回跳地址
  useEffect(
    () =>
      setUnauthorizedHandler(() => {
        const epoch = switchSession();
        setState({ status: "anonymous", user: null, epoch });
        if (isAuthPage(window.location.pathname)) return;
        router.replace(authPageHref("/login", currentBrowserPath()));
      }),
    [router],
  );

  function startSession(user: User): User {
    const epoch = switchSession();
    setState({ status: "authenticated", user, epoch });
    return user;
  }

  const value: AuthContextValue = {
    status: state.status,
    user: state.user,
    epoch: state.epoch,
    accountDeleted: state.status === "anonymous" && state.accountDeleted === true,
    login: async (body) => startSession(await authApi.login(body)),
    register: async (body) => startSession(await authApi.register(body)),
    logout: async () => {
      abortAllRequests();
      try {
        await authApi.logout();
      } catch (error) {
        // 网络失败时仍在前端退出；Cookie 可能残留，下次加载 /me 会如实反映
        if (!isAbortError(error)) console.warn("logout request failed", error);
      }
      const epoch = switchSession();
      setState({ status: "anonymous", user: null, epoch });
    },
    deleteAccount: async (confirmText) => {
      const startedAt = getSessionEpoch();
      // 请求成功前不取消其他在途请求：删除失败时账户与任务都应保持原状
      await accountApi.deleteAccount({ confirmText });
      // 等待期间会话已被切换（如全局 401 已处理），不再重复清理
      if (!isCurrentEpoch(startedAt)) return;
      const epoch = switchSession();
      setState({ status: "anonymous", user: null, epoch, accountDeleted: true });
      router.replace("/");
    },
  };

  return (
    <AuthContext value={value}>
      {/* 以 epoch 为 key：切换会话时重新挂载页面内容，组件本地的用户数据随之清空 */}
      <Fragment key={state.epoch}>{children}</Fragment>
    </AuthContext>
  );
}

export function useAuth(): AuthContextValue {
  const value = use(AuthContext);
  if (!value) throw new Error("useAuth must be used inside <AuthProvider>");
  return value;
}
