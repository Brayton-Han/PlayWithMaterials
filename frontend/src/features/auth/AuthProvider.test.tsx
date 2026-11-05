import { act, render, screen, waitFor } from "@testing-library/react";
import { delay, http, HttpResponse } from "msw";
import { useEffect, useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { apiRequest, isAbortError } from "@/lib/api/client";
import { makeUser } from "@/test/fixtures";
import { server } from "@/test/msw/server";

import { AuthProvider, useAuth, type AuthContextValue } from "./AuthProvider";
import { onUserScopeReset } from "./session";

const nav = vi.hoisted(() => ({ router: { push: vi.fn(), replace: vi.fn() }, search: "" }));
vi.mock("next/navigation", async () => (await import("@/test/navigation")).mockNavigation(nav));

const unauthenticated = () => HttpResponse.json({ code: "UNAUTHENTICATED", message: "请先登录" }, { status: 401 });

/** 模拟 T-050 taskStore：模块级的用户数据，通过 onUserScopeReset 注册清理。 */
const fakeTaskStore = { tasks: [] as string[] };
let unregisterStore: () => void = () => {};

/** 测试里调用 login / logout 用；在 effect 中更新，保持渲染纯净 */
const captured: { auth?: AuthContextValue } = {};
const auth = () => captured.auth!;
function Probe() {
  const value = useAuth();
  useEffect(() => {
    captured.auth = value;
  });
  return (
    <p data-testid="auth">
      {value.status}|{value.user?.username ?? "-"}
    </p>
  );
}

/** 组件本地的用户状态（如已选文件），切换会话时应被清空。 */
function LocalDraft() {
  const [draft, setDraft] = useState("");
  return <input aria-label="draft" value={draft} onChange={(e) => setDraft(e.target.value)} />;
}

function renderProvider() {
  return render(
    <AuthProvider>
      <Probe />
      <LocalDraft />
    </AuthProvider>,
  );
}

const authText = () => screen.getByTestId("auth").textContent;

beforeEach(() => {
  nav.router.push.mockClear();
  nav.router.replace.mockClear();
  window.history.replaceState(null, "", "/");
  fakeTaskStore.tasks = [];
  unregisterStore = onUserScopeReset(() => {
    fakeTaskStore.tasks = [];
  });
});
afterEach(() => unregisterStore());

describe("AuthProvider", () => {
  it("treats a 401 from /api/auth/me as anonymous without redirecting", async () => {
    server.use(http.get("/api/auth/me", unauthenticated));
    renderProvider();
    expect(authText()).toBe("loading|-");
    await waitFor(() => expect(authText()).toBe("anonymous|-"));
    expect(nav.router.replace).not.toHaveBeenCalled();
    expect(nav.router.push).not.toHaveBeenCalled();
  });

  it("restores the session from /api/auth/me", async () => {
    server.use(http.get("/api/auth/me", () => HttpResponse.json(makeUser("demo1"))));
    renderProvider();
    await waitFor(() => expect(authText()).toBe("authenticated|demo1"));
  });

  it("increments the epoch on login and remounts the content", async () => {
    server.use(
      http.get("/api/auth/me", unauthenticated),
      http.post("/api/auth/login", () => HttpResponse.json(makeUser("demo1"))),
    );
    renderProvider();
    await waitFor(() => expect(authText()).toBe("anonymous|-"));
    const before = auth().epoch;

    await act(() => auth().login({ username: "demo1", password: "xiaoying123" }));
    expect(authText()).toBe("authenticated|demo1");
    expect(auth().epoch).toBe(before + 1);
  });

  it("A → logout → B: aborts A's requests, clears user state, and never shows A's data", async () => {
    let loggedOut = false;
    server.use(
      http.get("/api/auth/me", () => HttpResponse.json(makeUser("demo1"))),
      http.post("/api/auth/logout", () => {
        loggedOut = true;
        return new HttpResponse(null, { status: 204 });
      }),
      http.post("/api/auth/login", () => HttpResponse.json(makeUser("demo2", "2"))),
      http.get("/api/tasks", async () => {
        await delay("infinite");
        return HttpResponse.json({ items: [] });
      }),
    );
    const user = (await import("@testing-library/user-event")).default.setup();
    renderProvider();
    await waitFor(() => expect(authText()).toBe("authenticated|demo1"));

    // A 的用户数据：store 中有任务，输入框有未提交的草稿，还有一个在途请求
    fakeTaskStore.tasks = ["task-of-demo1"];
    await user.type(screen.getByLabelText("draft"), "demo1 draft");
    const inFlight = apiRequest("/api/tasks").catch((error: unknown) => error);
    const epochOfA = auth().epoch;

    await act(() => auth().logout());

    expect(loggedOut).toBe(true);
    expect(isAbortError(await inFlight)).toBe(true);
    expect(fakeTaskStore.tasks).toEqual([]);
    expect(screen.getByLabelText<HTMLInputElement>("draft").value).toBe("");
    expect(authText()).toBe("anonymous|-");
    expect(auth().epoch).toBeGreaterThan(epochOfA);
    // 退出只清理、不跳转；受保护页由 RequireAuth 处理
    expect(nav.router.replace).not.toHaveBeenCalled();

    await act(() => auth().login({ username: "demo2", password: "xiaoying123" }));
    expect(authText()).toBe("authenticated|demo2");
    expect(fakeTaskStore.tasks).toEqual([]);
    expect(screen.getByLabelText<HTMLInputElement>("draft").value).toBe("");
  });

  it("still logs out locally when the logout request fails", async () => {
    server.use(
      http.get("/api/auth/me", () => HttpResponse.json(makeUser("demo1"))),
      http.post("/api/auth/logout", () => HttpResponse.error()),
    );
    vi.spyOn(console, "warn").mockImplementation(() => {});
    renderProvider();
    await waitFor(() => expect(authText()).toBe("authenticated|demo1"));
    fakeTaskStore.tasks = ["task-of-demo1"];

    await act(() => auth().logout());
    expect(authText()).toBe("anonymous|-");
    expect(fakeTaskStore.tasks).toEqual([]);
  });

  it("on a 401 from any business endpoint: clears state and redirects to /login?next=<current path>", async () => {
    server.use(
      http.get("/api/auth/me", () => HttpResponse.json(makeUser("demo1"))),
      http.get("/api/tasks", unauthenticated),
    );
    window.history.replaceState(null, "", "/records?tab=1");
    renderProvider();
    await waitFor(() => expect(authText()).toBe("authenticated|demo1"));
    fakeTaskStore.tasks = ["task-of-demo1"];

    await act(async () => {
      await apiRequest("/api/tasks").catch(() => {});
    });

    expect(authText()).toBe("anonymous|-");
    expect(fakeTaskStore.tasks).toEqual([]);
    expect(nav.router.replace).toHaveBeenCalledWith("/login?next=%2Frecords%3Ftab%3D1");
  });

  it("does not redirect on a 401 while already on the login page", async () => {
    server.use(http.get("/api/auth/me", unauthenticated), http.get("/api/tasks", unauthenticated));
    window.history.replaceState(null, "", "/login?next=%2Frecords");
    renderProvider();
    await waitFor(() => expect(authText()).toBe("anonymous|-"));

    await act(async () => {
      await apiRequest("/api/tasks").catch(() => {});
    });
    expect(nav.router.replace).not.toHaveBeenCalled();
  });

  it("discards a late /api/auth/me response that belongs to an earlier session", async () => {
    server.use(
      http.get("/api/auth/me", async () => {
        await delay(50);
        return HttpResponse.json(makeUser("demo1"));
      }),
      http.post("/api/auth/login", () => HttpResponse.json(makeUser("demo2", "2"))),
    );
    renderProvider();
    await act(() => auth().login({ username: "demo2", password: "xiaoying123" }));
    expect(authText()).toBe("authenticated|demo2");

    await act(() => new Promise((resolve) => setTimeout(resolve, 80)));
    expect(authText()).toBe("authenticated|demo2");
  });

  it("throws a clear error when useAuth is used outside the provider", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    expect(() => render(<Probe />)).toThrow("useAuth must be used inside <AuthProvider>");
  });
});
