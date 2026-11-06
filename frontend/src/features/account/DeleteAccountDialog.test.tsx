import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { delay, http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AuthProvider } from "@/features/auth/AuthProvider";
import { RequireAuth } from "@/features/auth/RequireAuth";
import { onUserScopeReset } from "@/features/auth/session";
import { makeUser } from "@/test/fixtures";
import { server } from "@/test/msw/server";

import { AccountSettings } from "./AccountSettings";

const nav = vi.hoisted(() => ({ router: { push: vi.fn(), replace: vi.fn() }, search: "" }));
vi.mock("next/navigation", async () => (await import("@/test/navigation")).mockNavigation(nav));

/** 模拟 taskStore 等模块级用户状态，删除成功后应被清空。 */
const userScoped = { tasks: [] as string[] };
let unregister: () => void = () => {};

/** 与 /account 页面相同的组合：守卫 + 设置内容。 */
async function renderAccountPage() {
  render(
    <AuthProvider>
      <RequireAuth>
        <AccountSettings />
      </RequireAuth>
    </AuthProvider>,
  );
  await screen.findByRole("heading", { name: "账户信息" });
}

async function openDialog(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: "删除账户" }));
  return screen.getByRole("dialog", { name: "确认删除账户？" });
}

const confirmInput = () => screen.getByLabelText("请输入 DELETE 以确认");
const confirmButton = () => screen.getByRole("button", { name: "永久删除账户" });

beforeEach(() => {
  nav.router.replace.mockClear();
  window.history.replaceState(null, "", "/account");
  server.use(http.get("/api/auth/me", () => HttpResponse.json(makeUser("demo1"))));
  userScoped.tasks = ["task-1"];
  unregister = onUserScopeReset(() => {
    userScoped.tasks = [];
  });
});
afterEach(() => unregister());

describe("AccountSettings", () => {
  it("shows only account info, change password and delete account", async () => {
    await renderAccountPage();

    expect(screen.getAllByRole("heading", { level: 2 }).map((h) => h.textContent)).toEqual([
      "账户信息",
      "修改密码",
      "删除账户",
    ]);
    expect(screen.getByText("demo1")).toBeInTheDocument();
    expect(screen.getByText("demo1@example.com")).toBeInTheDocument();
    // 只读：账户信息区没有可编辑的输入框
    expect(screen.queryByLabelText("用户名")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("邮箱")).not.toBeInTheDocument();
    for (const text of [/billing/i, /terms/i, /privacy/i, /subscription/i]) {
      expect(screen.queryByText(text)).not.toBeInTheDocument();
    }
  });
});

describe("DeleteAccountDialog", () => {
  it("opens a labelled modal dialog with focus in the confirm input", async () => {
    const user = userEvent.setup();
    await renderAccountPage();

    const dialog = await openDialog(user);

    expect(dialog).toHaveAttribute("open");
    expect(dialog).toHaveAccessibleDescription(/正在制作的任务会被废弃/);
    expect(dialog).toHaveAccessibleDescription(/邮箱之后可以重新注册新账户，用户名不能再使用/);
    expect(confirmInput()).toHaveFocus();
    expect(confirmButton()).toBeDisabled();
  });

  it.each(["", "delete", "DELET", "Delete", "DELETE ", " DELETE"])(
    "keeps the confirm button disabled for %j",
    async (text) => {
      const del = vi.fn(() => new HttpResponse(null, { status: 204 }));
      server.use(http.delete("/api/account", del));
      const user = userEvent.setup();
      await renderAccountPage();
      await openDialog(user);

      if (text) await user.type(confirmInput(), text);
      expect(confirmButton()).toBeDisabled();
      // 按 Enter 提交也不发请求
      await user.type(confirmInput(), "{Enter}");
      expect(del).not.toHaveBeenCalled();
    },
  );

  it("enables the confirm button only for exactly DELETE", async () => {
    const user = userEvent.setup();
    await renderAccountPage();
    await openDialog(user);

    await user.type(confirmInput(), "DELETE");
    expect(confirmButton()).toBeEnabled();
    await user.type(confirmInput(), "{Backspace}");
    expect(confirmButton()).toBeDisabled();
  });

  it("cancel closes the dialog, sends nothing and returns focus to the trigger", async () => {
    const del = vi.fn(() => new HttpResponse(null, { status: 204 }));
    server.use(http.delete("/api/account", del));
    const user = userEvent.setup();
    await renderAccountPage();
    await openDialog(user);
    await user.type(confirmInput(), "DELETE");

    await user.click(screen.getByRole("button", { name: "取消" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "删除账户" })).toHaveFocus();
    expect(del).not.toHaveBeenCalled();
    expect(userScoped.tasks).toEqual(["task-1"]);

    // 重新打开时输入框是空的
    await openDialog(user);
    expect(confirmInput()).toHaveValue("");
  });

  it("Escape (cancel event) closes the dialog without a request", async () => {
    const del = vi.fn(() => new HttpResponse(null, { status: 204 }));
    server.use(http.delete("/api/account", del));
    const user = userEvent.setup();
    await renderAccountPage();
    const dialog = await openDialog(user);

    fireEvent(dialog, new Event("cancel", { cancelable: true }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "删除账户" })).toHaveFocus();
    expect(del).not.toHaveBeenCalled();
  });

  it("deletes with confirmText DELETE, clears user state and goes home (not to login)", async () => {
    let body: unknown;
    server.use(
      http.delete("/api/account", async ({ request }) => {
        body = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    await renderAccountPage();
    await openDialog(user);

    await user.type(confirmInput(), "DELETE");
    await user.click(confirmButton());

    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/"));
    expect(body).toEqual({ confirmText: "DELETE" });
    expect(userScoped.tasks).toEqual([]);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByText("demo1@example.com")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("账户已删除，正在返回首页");
    // 守卫没有再把页面带去登录页
    expect(nav.router.replace).toHaveBeenCalledTimes(1);
  });

  it("locks the dialog while deleting", async () => {
    server.use(
      http.delete("/api/account", async () => {
        await delay("infinite");
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    await renderAccountPage();
    const dialog = await openDialog(user);
    await user.type(confirmInput(), "DELETE");
    await user.click(confirmButton());

    expect(await screen.findByRole("button", { name: "删除中…" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "取消" })).toBeDisabled();
    fireEvent(dialog, new Event("cancel", { cancelable: true }));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it.each([
    ["server error", () => HttpResponse.json({ code: "INTERNAL_ERROR", message: "服务器开小差了，请稍后重试" }, { status: 500 }), "服务器开小差了"],
    ["network error", () => HttpResponse.error(), "网络连接异常"],
    [
      "CONFIRM_TEXT_MISMATCH",
      () => HttpResponse.json({ code: "CONFIRM_TEXT_MISMATCH", message: "请输入 DELETE 以确认删除" }, { status: 400 }),
      "请输入 DELETE 以确认删除",
    ],
  ])("stays logged in and shows the error on %s", async (_name, resolver, message) => {
    server.use(http.delete("/api/account", resolver));
    const user = userEvent.setup();
    await renderAccountPage();
    await openDialog(user);
    await user.type(confirmInput(), "DELETE");
    await user.click(confirmButton());

    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(confirmButton()).toBeEnabled(); // 可以重试
    expect(screen.getByText("demo1@example.com")).toBeInTheDocument();
    expect(userScoped.tasks).toEqual(["task-1"]);
    expect(nav.router.replace).not.toHaveBeenCalled();
  });

  it("redirects to login when the session has already expired (401)", async () => {
    server.use(
      http.delete("/api/account", () => HttpResponse.json({ code: "UNAUTHENTICATED", message: "请先登录" }, { status: 401 })),
    );
    const user = userEvent.setup();
    await renderAccountPage();
    await openDialog(user);
    await user.type(confirmInput(), "DELETE");
    await user.click(confirmButton());

    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/login?next=%2Faccount"));
    expect(nav.router.replace).not.toHaveBeenCalledWith("/");
  });

  it("can be completed with the keyboard only", async () => {
    server.use(http.delete("/api/account", () => new HttpResponse(null, { status: 204 })));
    const user = userEvent.setup();
    await renderAccountPage();

    screen.getByRole("button", { name: "删除账户" }).focus();
    await user.keyboard("{Enter}");
    expect(confirmInput()).toHaveFocus();
    await user.keyboard("DELETE{Enter}");

    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/"));
  });
});
