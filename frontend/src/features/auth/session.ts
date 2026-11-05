/**
 * 会话世代号与用户相关状态的清理（architecture §4.4，F-10 规则 5，R-08.2）。
 *
 * - epoch 在每次登录、注册、退出、会话失效时递增。异步响应写入任何用户相关状态之前，
 *   用 {@link isCurrentEpoch} 校验"发起时的 epoch === 当前 epoch"，不一致就丢弃。
 * - 模块级的用户相关状态（如 T-050 的 taskStore）用 {@link onUserScopeReset} 注册清理函数，
 *   切换会话时由 AuthProvider 统一调用。组件本地状态（如已选文件）由 SessionBoundary 重新挂载清空。
 *
 * 放在模块里而不是只放在 React state 里，是为了让 React 之外的代码（store、跟踪调度器）也能读取。
 */
let epoch = 0;
const resetters = new Set<() => void>();

export function getSessionEpoch(): number {
  return epoch;
}

export function isCurrentEpoch(value: number): boolean {
  return value === epoch;
}

/** 递增并返回新的 epoch。只由 AuthProvider 调用。 */
export function bumpSessionEpoch(): number {
  epoch += 1;
  return epoch;
}

/** 注册切换会话时要执行的清理函数；返回注销函数。 */
export function onUserScopeReset(reset: () => void): () => void {
  resetters.add(reset);
  return () => {
    resetters.delete(reset);
  };
}

/** 执行全部已注册的清理函数。单个清理函数抛错不影响其他清理。 */
export function resetUserScopedState(): void {
  for (const reset of resetters) {
    try {
      reset();
    } catch (error) {
      console.error("user-scoped reset failed", error);
    }
  }
}

const AUTH_PAGES = ["/login", "/register"];

export function isAuthPage(pathname: string): boolean {
  return AUTH_PAGES.includes(pathname);
}

/**
 * 校验登录后的回跳地址：只接受站内绝对路径，拒绝 `//host`、`/\host`、`javascript:` 等，
 * 也不回跳到登录 / 注册页本身（避免循环）。不合法时返回 `/`。
 */
export function safeNextPath(raw: string | null | undefined): string {
  if (!raw || !raw.startsWith("/") || raw.startsWith("//") || raw.startsWith("/\\")) return "/";
  let url: URL;
  try {
    url = new URL(raw, "http://internal.invalid");
  } catch {
    return "/";
  }
  if (url.origin !== "http://internal.invalid") return "/";
  if (isAuthPage(url.pathname)) return "/";
  return `${url.pathname}${url.search}${url.hash}`;
}

/** 生成带回跳地址的登录 / 注册链接；回跳地址为 `/` 时省略参数。 */
export function authPageHref(page: "/login" | "/register", next?: string | null): string {
  const target = safeNextPath(next);
  return target === "/" ? page : `${page}?next=${encodeURIComponent(target)}`;
}

/** 浏览器中当前页面的路径（含查询串）；服务端渲染时返回 `/`。 */
export function currentBrowserPath(): string {
  if (typeof window === "undefined") return "/";
  return `${window.location.pathname}${window.location.search}`;
}
