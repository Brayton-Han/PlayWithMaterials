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
