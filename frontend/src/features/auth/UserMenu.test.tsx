import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";

import { makeUser } from "@/test/fixtures";
import { server } from "@/test/msw/server";

import { AuthProvider } from "./AuthProvider";
import { UserMenu } from "./UserMenu";

const nav = vi.hoisted(() => ({ router: { push: vi.fn(), replace: vi.fn() }, search: "" }));
vi.mock("next/navigation", async () => (await import("@/test/navigation")).mockNavigation(nav));

function renderMenu() {
  render(
    <AuthProvider>
      <UserMenu />
    </AuthProvider>,
  );
}

describe("UserMenu", () => {
  it("shows login / register links when anonymous", async () => {
    server.use(http.get("/api/auth/me", () => HttpResponse.json({ code: "UNAUTHENTICATED", message: "请先登录" }, { status: 401 })));
    renderMenu();

    expect(await screen.findByRole("link", { name: "登录" })).toHaveAttribute("href", "/login");
    expect(screen.getByRole("link", { name: "注册" })).toHaveAttribute("href", "/register");
  });

  it("shows the username and a menu with records, account settings and logout", async () => {
    server.use(http.get("/api/auth/me", () => HttpResponse.json(makeUser("demo1"))));
    const user = userEvent.setup();
    renderMenu();

    const trigger = await screen.findByRole("button", { name: /demo1/ });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    await user.click(trigger);

    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("link", { name: "我的记录" })).toHaveAttribute("href", "/records");
    expect(screen.getByRole("link", { name: "账户设置" })).toHaveAttribute("href", "/account");
    expect(screen.getByRole("button", { name: "退出" })).toBeInTheDocument();

    await user.keyboard("{Escape}");
    expect(screen.queryByRole("link", { name: "我的记录" })).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("closes when clicking outside", async () => {
    server.use(http.get("/api/auth/me", () => HttpResponse.json(makeUser("demo1"))));
    const user = userEvent.setup();
    renderMenu();
    await user.click(await screen.findByRole("button", { name: /demo1/ }));

    await user.click(document.body);
    expect(screen.queryByRole("link", { name: "我的记录" })).not.toBeInTheDocument();
  });

  it("logs out and switches back to the login / register links", async () => {
    const logout = vi.fn(() => new HttpResponse(null, { status: 204 }));
    server.use(
      http.get("/api/auth/me", () => HttpResponse.json(makeUser("demo1"))),
      http.post("/api/auth/logout", logout),
    );
    const user = userEvent.setup();
    renderMenu();
    await user.click(await screen.findByRole("button", { name: /demo1/ }));
    await user.click(screen.getByRole("button", { name: "退出" }));

    expect(await screen.findByRole("link", { name: "登录" })).toBeInTheDocument();
    expect(screen.queryByText("demo1")).not.toBeInTheDocument();
    await waitFor(() => expect(logout).toHaveBeenCalledTimes(1));
  });
});
