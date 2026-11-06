import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { makeUser } from "@/test/fixtures";
import { deferred, simulateFocusFixup } from "@/test/focus";
import { server } from "@/test/msw/server";

import { AuthProvider } from "./AuthProvider";
import { LoginForm } from "./LoginForm";

const nav = vi.hoisted(() => ({ router: { push: vi.fn(), replace: vi.fn() }, search: "" }));
vi.mock("next/navigation", async () => (await import("@/test/navigation")).mockNavigation(nav));

async function renderLogin(search = "") {
  nav.search = search;
  render(
    <AuthProvider>
      <LoginForm />
    </AuthProvider>,
  );
  return screen.findByRole("form", { name: "登录" });
}

beforeEach(() => {
  nav.router.replace.mockClear();
  server.use(http.get("/api/auth/me", () => HttpResponse.json({ code: "UNAUTHENTICATED", message: "请先登录" }, { status: 401 })));
});

describe("LoginForm", () => {
  it("logs in and returns to the next path", async () => {
    let body: unknown;
    server.use(
      http.post("/api/auth/login", async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(makeUser("demo1"));
      }),
    );
    const user = userEvent.setup();
    await renderLogin("next=%2Frecords");

    await user.type(screen.getByLabelText("用户名"), "demo1");
    await user.type(screen.getByLabelText("密码"), "xiaoying123");
    await user.click(screen.getByRole("button", { name: "登录" }));

    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/records"));
    expect(body).toEqual({ username: "demo1", password: "xiaoying123" });
  });

  it("ignores an unsafe next and goes home", async () => {
    server.use(http.post("/api/auth/login", () => HttpResponse.json(makeUser("demo1"))));
    const user = userEvent.setup();
    await renderLogin("next=%2F%2Fevil.example.com");

    await user.type(screen.getByLabelText("用户名"), "demo1");
    await user.type(screen.getByLabelText("密码"), "xiaoying123");
    await user.click(screen.getByRole("button", { name: "登录" }));

    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/"));
  });

  it("blocks empty fields without sending a request", async () => {
    const login = vi.fn(() => HttpResponse.json(makeUser("demo1")));
    server.use(http.post("/api/auth/login", login));
    const user = userEvent.setup();
    await renderLogin();

    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(screen.getByText("请输入用户名")).toBeInTheDocument();
    expect(screen.getByText("请输入密码")).toBeInTheDocument();
    expect(screen.getByLabelText("用户名")).toHaveAttribute("aria-invalid", "true");
    expect(login).not.toHaveBeenCalled();
  });

  it("shows the unified message on INVALID_CREDENTIALS and stays on the page", async () => {
    server.use(
      http.post("/api/auth/login", () =>
        HttpResponse.json({ code: "INVALID_CREDENTIALS", message: "用户名或密码错误" }, { status: 401 }),
      ),
    );
    const user = userEvent.setup();
    await renderLogin();

    await user.type(screen.getByLabelText("用户名"), "demo1@example.com");
    await user.type(screen.getByLabelText("密码"), "wrong-password");
    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("用户名或密码错误");
    expect(screen.getByRole("button", { name: "登录" })).toBeEnabled();
    expect(nav.router.replace).not.toHaveBeenCalled();
  });

  it("shows a network error message", async () => {
    server.use(http.post("/api/auth/login", () => HttpResponse.error()));
    const user = userEvent.setup();
    await renderLogin();

    await user.type(screen.getByLabelText("用户名"), "demo1");
    await user.type(screen.getByLabelText("密码"), "xiaoying123");
    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("网络连接异常");
  });

  it("redirects straight away when already logged in", async () => {
    server.use(http.get("/api/auth/me", () => HttpResponse.json(makeUser("demo1"))));
    nav.search = "next=%2Faccount";
    render(
      <AuthProvider>
        <LoginForm />
      </AuthProvider>,
    );
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/account"));
  });

  it("links to register keeping the next path", async () => {
    await renderLogin("next=%2Frecords");
    expect(screen.getByRole("link", { name: "注册" })).toHaveAttribute("href", "/register?next=%2Frecords");
  });

  it("returns keyboard focus to the submit button after a failed login (F-19)", async () => {
    const gate = deferred();
    server.use(
      http.post("/api/auth/login", async () => {
        await gate.promise;
        return HttpResponse.json({ code: "INVALID_CREDENTIALS", message: "用户名或密码错误" }, { status: 401 });
      }),
    );
    const user = userEvent.setup();
    await renderLogin();

    await user.type(screen.getByLabelText("用户名"), "demo1");
    await user.type(screen.getByLabelText("密码"), "wrong-password");
    await user.click(screen.getByRole("button", { name: "登录" }));
    expect(await screen.findByRole("button", { name: "登录中…" })).toBeDisabled();
    simulateFocusFixup(); // 真实浏览器：按钮被禁用后焦点丢回 body
    gate.resolve();

    expect(await screen.findByRole("alert")).toHaveTextContent("用户名或密码错误");
    expect(screen.getByRole("button", { name: "登录" })).toHaveFocus();
  });
});
