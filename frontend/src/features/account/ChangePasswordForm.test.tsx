import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AuthProvider } from "@/features/auth/AuthProvider";
import { makeUser } from "@/test/fixtures";
import { server } from "@/test/msw/server";

import { ChangePasswordForm, validateChangePassword } from "./ChangePasswordForm";

const nav = vi.hoisted(() => ({ router: { push: vi.fn(), replace: vi.fn() }, search: "" }));
vi.mock("next/navigation", async () => (await import("@/test/navigation")).mockNavigation(nav));

async function renderForm() {
  render(
    <AuthProvider>
      <ChangePasswordForm />
    </AuthProvider>,
  );
  return screen.findByRole("form", { name: "修改密码" });
}

async function fill(user: ReturnType<typeof userEvent.setup>, current: string, next: string, confirm: string) {
  if (current) await user.type(screen.getByLabelText("当前密码"), current);
  if (next) await user.type(screen.getByLabelText("新密码"), next);
  if (confirm) await user.type(screen.getByLabelText("确认新密码"), confirm);
  await user.click(screen.getByRole("button", { name: "修改密码" }));
}

beforeEach(() => {
  nav.router.replace.mockClear();
  window.history.replaceState(null, "", "/account");
  server.use(http.get("/api/auth/me", () => HttpResponse.json(makeUser("demo1"))));
});

describe("validateChangePassword", () => {
  it("accepts valid input and rejects each rule", () => {
    expect(validateChangePassword({ currentPassword: "x", newPassword: "12345678", confirmPassword: "12345678" })).toEqual({});
    expect(validateChangePassword({ currentPassword: "", newPassword: "", confirmPassword: "" })).toEqual({
      currentPassword: "请输入当前密码",
      newPassword: "请输入新密码",
      confirmPassword: "请再次输入新密码",
    });
    expect(validateChangePassword({ currentPassword: "x", newPassword: "1234567", confirmPassword: "1234567" }).newPassword).toBe(
      "密码需为 8 ~ 64 位",
    );
    expect(
      validateChangePassword({ currentPassword: "x", newPassword: "a".repeat(65), confirmPassword: "a".repeat(65) }).newPassword,
    ).toBe("密码需为 8 ~ 64 位");
  });
});

describe("ChangePasswordForm", () => {
  it("changes the password, clears the fields and stays logged in", async () => {
    let body: unknown;
    server.use(
      http.put("/api/account/password", async ({ request }) => {
        body = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    await renderForm();

    await fill(user, "xiaoying123", "new-password-1", "new-password-1");

    expect(await screen.findByRole("status")).toHaveTextContent("密码已修改");
    expect(body).toEqual({ currentPassword: "xiaoying123", newPassword: "new-password-1" });
    expect(screen.getByLabelText("当前密码")).toHaveValue("");
    expect(screen.getByLabelText("新密码")).toHaveValue("");
    expect(screen.getByLabelText("确认新密码")).toHaveValue("");
    expect(nav.router.replace).not.toHaveBeenCalled();
  });

  it("does not send a request when the new passwords differ", async () => {
    const put = vi.fn(() => new HttpResponse(null, { status: 204 }));
    server.use(http.put("/api/account/password", put));
    const user = userEvent.setup();
    await renderForm();

    await fill(user, "xiaoying123", "new-password-1", "new-password-2");

    expect(screen.getByText("两次输入的新密码不一致")).toBeInTheDocument();
    expect(screen.getByLabelText("确认新密码")).toHaveAttribute("aria-invalid", "true");
    expect(put).not.toHaveBeenCalled();
  });

  it("blocks empty and too-short input without sending a request", async () => {
    const put = vi.fn(() => new HttpResponse(null, { status: 204 }));
    server.use(http.put("/api/account/password", put));
    const user = userEvent.setup();
    await renderForm();

    await user.click(screen.getByRole("button", { name: "修改密码" }));
    expect(screen.getByText("请输入当前密码")).toBeInTheDocument();
    expect(screen.getByText("请输入新密码")).toBeInTheDocument();

    await fill(user, "xiaoying123", "short", "short");
    expect(screen.getByText("密码需为 8 ~ 64 位")).toBeInTheDocument();
    expect(put).not.toHaveBeenCalled();
  });

  it("shows CURRENT_PASSWORD_INCORRECT on the current password field and stays logged in", async () => {
    server.use(
      http.put("/api/account/password", () =>
        HttpResponse.json({ code: "CURRENT_PASSWORD_INCORRECT", message: "当前密码不正确" }, { status: 400 }),
      ),
    );
    const user = userEvent.setup();
    await renderForm();

    await fill(user, "wrong-password", "new-password-1", "new-password-1");

    expect(await screen.findByText("当前密码不正确")).toBeInTheDocument();
    expect(screen.getByLabelText("当前密码")).toHaveAttribute("aria-invalid", "true");
    expect(screen.queryByRole("status")).toBeEmptyDOMElement();
    expect(screen.getByRole("button", { name: "修改密码" })).toBeEnabled();
    expect(nav.router.replace).not.toHaveBeenCalled();
  });

  it("maps server VALIDATION_FAILED field errors", async () => {
    server.use(
      http.put("/api/account/password", () =>
        HttpResponse.json(
          {
            code: "VALIDATION_FAILED",
            message: "请求参数不合法",
            details: { fieldErrors: [{ field: "newPassword", code: "LENGTH", message: "密码长度需为 8 ~ 64 位" }] },
          },
          { status: 400 },
        ),
      ),
    );
    const user = userEvent.setup();
    await renderForm();

    await fill(user, "xiaoying123", "new-password-1", "new-password-1");

    expect(await screen.findByText("密码长度需为 8 ~ 64 位")).toBeInTheDocument();
    expect(screen.getByLabelText("新密码")).toHaveAttribute("aria-invalid", "true");
  });

  it("shows a network error and keeps the input", async () => {
    server.use(http.put("/api/account/password", () => HttpResponse.error()));
    const user = userEvent.setup();
    await renderForm();

    await fill(user, "xiaoying123", "new-password-1", "new-password-1");

    expect(await screen.findByRole("alert")).toHaveTextContent("网络连接异常");
    expect(screen.getByLabelText("当前密码")).toHaveValue("xiaoying123");
  });

  it("redirects to login when the session has expired (401)", async () => {
    server.use(
      http.put("/api/account/password", () =>
        HttpResponse.json({ code: "UNAUTHENTICATED", message: "请先登录" }, { status: 401 }),
      ),
    );
    const user = userEvent.setup();
    await renderForm();

    await fill(user, "xiaoying123", "new-password-1", "new-password-1");

    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/login?next=%2Faccount"));
  });
});
