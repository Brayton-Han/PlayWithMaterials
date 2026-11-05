import { render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { makeUser } from "@/test/fixtures";
import { server } from "@/test/msw/server";

import { AuthProvider } from "./AuthProvider";
import { LoginPrompt } from "./LoginPrompt";
import { RequireAuth } from "./RequireAuth";
import { useRequireAuth } from "./useRequireAuth";

const nav = vi.hoisted(() => ({ router: { push: vi.fn(), replace: vi.fn() }, search: "" }));
vi.mock("next/navigation", async () => (await import("@/test/navigation")).mockNavigation(nav));

const anonymous = () => HttpResponse.json({ code: "UNAUTHENTICATED", message: "请先登录" }, { status: 401 });

beforeEach(() => {
  nav.router.replace.mockClear();
  window.history.replaceState(null, "", "/records");
});

describe("RequireAuth", () => {
  it("redirects anonymous users to login with the current path as next", async () => {
    server.use(http.get("/api/auth/me", anonymous));
    render(
      <AuthProvider>
        <RequireAuth>
          <p>secret records</p>
        </RequireAuth>
      </AuthProvider>,
    );

    expect(screen.getByRole("status")).toHaveTextContent("正在确认登录状态");
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/login?next=%2Frecords"));
    expect(screen.queryByText("secret records")).not.toBeInTheDocument();
  });

  it("renders the content for a logged-in user", async () => {
    server.use(http.get("/api/auth/me", () => HttpResponse.json(makeUser("demo1"))));
    render(
      <AuthProvider>
        <RequireAuth>
          <p>secret records</p>
        </RequireAuth>
      </AuthProvider>,
    );

    expect(await screen.findByText("secret records")).toBeInTheDocument();
    expect(nav.router.replace).not.toHaveBeenCalled();
  });
});

/** 模板详情页的用法：不跳转，按状态决定显示上传区还是登录提示。 */
function DetailUploadArea() {
  const { status } = useRequireAuth();
  if (status === "loading") return null;
  return status === "authenticated" ? <p>upload slots</p> : <LoginPrompt next="/templates/a" />;
}

describe("useRequireAuth without redirect", () => {
  it("shows the login prompt to anonymous users without redirecting", async () => {
    server.use(http.get("/api/auth/me", anonymous));
    render(
      <AuthProvider>
        <DetailUploadArea />
      </AuthProvider>,
    );

    expect(await screen.findByText("登录后即可制作")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "登录" })).toHaveAttribute("href", "/login?next=%2Ftemplates%2Fa");
    expect(screen.queryByText("upload slots")).not.toBeInTheDocument();
    expect(nav.router.replace).not.toHaveBeenCalled();
  });

  it("shows the upload area to logged-in users", async () => {
    server.use(http.get("/api/auth/me", () => HttpResponse.json(makeUser("demo1"))));
    render(
      <AuthProvider>
        <DetailUploadArea />
      </AuthProvider>,
    );
    expect(await screen.findByText("upload slots")).toBeInTheDocument();
  });
});
