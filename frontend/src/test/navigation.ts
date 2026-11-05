import { vi } from "vitest";

/**
 * next/navigation 的测试替身。用法（vi.mock 会被提升，所以替身对象要用 vi.hoisted 创建）：
 *
 *   const nav = vi.hoisted(() => ({ router: { push: vi.fn(), replace: vi.fn() }, search: "" }));
 *   vi.mock("next/navigation", () => mockNavigation(nav));
 */
export interface NavigationMock {
  router: { push: ReturnType<typeof vi.fn>; replace: ReturnType<typeof vi.fn> };
  /** useSearchParams() 返回的查询串（不含 ?） */
  search: string;
}

export function mockNavigation(nav: NavigationMock) {
  const router = { ...nav.router, refresh: vi.fn(), back: vi.fn(), forward: vi.fn(), prefetch: vi.fn() };
  return {
    useRouter: () => Object.assign(router, nav.router),
    useSearchParams: () => new URLSearchParams(nav.search),
    usePathname: () => window.location.pathname,
  };
}

const LOCATION_CHANGE = "test:locationchange";
let historyPatched = false;

/**
 * 由 jsdom 真实 URL 驱动的 next/navigation 替身：模拟 Next.js 把原生 history.pushState / replaceState
 * 和浏览器前进后退同步到 useSearchParams 的行为。用法：
 *
 *   vi.mock("next/navigation", async () => (await import("@/test/navigation")).mockUrlNavigation());
 *
 * 各测试开始前用 window.history.replaceState(null, "", "/?a=b") 设置初始 URL。
 */
export async function mockUrlNavigation() {
  const { useSyncExternalStore } = await import("react");
  if (!historyPatched) {
    historyPatched = true;
    for (const method of ["pushState", "replaceState"] as const) {
      const original = window.history[method].bind(window.history);
      window.history[method] = (...args: Parameters<History["pushState"]>) => {
        original(...args);
        window.dispatchEvent(new Event(LOCATION_CHANGE));
      };
    }
  }
  const subscribe = (onChange: () => void) => {
    window.addEventListener(LOCATION_CHANGE, onChange);
    window.addEventListener("popstate", onChange);
    return () => {
      window.removeEventListener(LOCATION_CHANGE, onChange);
      window.removeEventListener("popstate", onChange);
    };
  };
  const router = { push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), back: vi.fn(), forward: vi.fn(), prefetch: vi.fn() };
  return {
    useRouter: () => router,
    useSearchParams: () => new URLSearchParams(useSyncExternalStore(subscribe, () => window.location.search)),
    usePathname: () => useSyncExternalStore(subscribe, () => window.location.pathname),
  };
}
