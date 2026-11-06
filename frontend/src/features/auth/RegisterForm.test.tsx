import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { makeUser } from "@/test/fixtures";
import { server } from "@/test/msw/server";

import { AuthProvider } from "./AuthProvider";
import { RegisterForm, validateRegister } from "./RegisterForm";

const nav = vi.hoisted(() => ({ router: { push: vi.fn(), replace: vi.fn() }, search: "" }));
vi.mock("next/navigation", async () => (await import("@/test/navigation")).mockNavigation(nav));

const valid = { username: "new_user", email: "New@Example.com", password: "password123", confirmPassword: "password123" };

async function renderRegister(search = "") {
  nav.search = search;
  render(
    <AuthProvider>
      <RegisterForm />
    </AuthProvider>,
  );
  return screen.findByRole("form", { name: "注册" });
}

async function fill(values: Partial<typeof valid>) {
  const user = userEvent.setup();
  const v = { ...valid, ...values };
  if (v.username) await user.type(screen.getByLabelText("用户名"), v.username);
  if (v.email) await user.type(screen.getByLabelText("邮箱"), v.email);
  if (v.password) await user.type(screen.getByLabelText("密码"), v.password);
  if (v.confirmPassword) await user.type(screen.getByLabelText("确认密码"), v.confirmPassword);
  await user.click(screen.getByRole("button", { name: "注册" }));
}

beforeEach(() => {
  nav.router.replace.mockClear();
  server.use(http.get("/api/auth/me", () => HttpResponse.json({ code: "UNAUTHENTICATED", message: "请先登录" }, { status: 401 })));
});

describe("validateRegister", () => {
  it("accepts valid input", () => {
    expect(validateRegister(valid)).toEqual({});
  });

  it.each([
    [{ username: "ab" }, "username", "用户名需为 3 ~ 20 位字母、数字或下划线"],
    [{ username: "a".repeat(21) }, "username", "用户名需为 3 ~ 20 位字母、数字或下划线"],
    [{ username: "名字abc" }, "username", "用户名需为 3 ~ 20 位字母、数字或下划线"],
    [{ email: "not-an-email" }, "email", "邮箱格式不正确"],
    [{ email: `${"a".repeat(250)}@b.cn` }, "email", "邮箱最长 254 位"],
    [{ password: "short", confirmPassword: "short" }, "password", "密码需为 8 ~ 64 位"],
    [{ password: "x".repeat(65), confirmPassword: "x".repeat(65) }, "password", "密码需为 8 ~ 64 位"],
    [{ confirmPassword: "password124" }, "confirmPassword", "两次输入的密码不一致"],
  ])("rejects %o", (override, field, message) => {
    expect(validateRegister({ ...valid, ...override })).toMatchObject({ [field]: message });
  });
});

describe("RegisterForm", () => {
  it("tells the user the email cannot be changed after registering", async () => {
    await renderRegister();
    expect(screen.getByLabelText("邮箱")).toHaveAccessibleDescription("邮箱注册后不能修改");
  });

  it("registers without sending confirmPassword and returns to next", async () => {
    let body: unknown;
    server.use(
      http.post("/api/auth/register", async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(makeUser("new_user"), { status: 201 });
      }),
    );
    await renderRegister("next=%2Ftemplates%2Fa");
    await fill({});

    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/templates/a"));
    expect(body).toEqual({ username: "new_user", email: "New@Example.com", password: "password123" });
  });

  it("blocks mismatched passwords without sending a request", async () => {
    const register = vi.fn(() => HttpResponse.json(makeUser("x"), { status: 201 }));
    server.use(http.post("/api/auth/register", register));
    await renderRegister();
    await fill({ confirmPassword: "different1" });

    expect(screen.getByText("两次输入的密码不一致")).toBeInTheDocument();
    expect(register).not.toHaveBeenCalled();
  });

  it("shows USERNAME_TAKEN on the username field", async () => {
    server.use(
      http.post("/api/auth/register", () =>
        HttpResponse.json({ code: "USERNAME_TAKEN", message: "用户名已被使用" }, { status: 409 }),
      ),
    );
    await renderRegister();
    await fill({});

    expect(await screen.findByText("用户名已被使用")).toBeInTheDocument();
    expect(screen.getByLabelText("用户名")).toHaveAttribute("aria-invalid", "true");
    // F-19：服务端字段错误后焦点移到出错的字段
    expect(screen.getByLabelText("用户名")).toHaveFocus();
    expect(nav.router.replace).not.toHaveBeenCalled();
  });

  it("shows EMAIL_TAKEN on the email field", async () => {
    server.use(
      http.post("/api/auth/register", () =>
        HttpResponse.json({ code: "EMAIL_TAKEN", message: "邮箱已被使用" }, { status: 409 }),
      ),
    );
    await renderRegister();
    await fill({});

    expect(await screen.findByText("邮箱已被使用")).toBeInTheDocument();
    expect(screen.getByLabelText("邮箱")).toHaveAttribute("aria-invalid", "true");
  });

  it("maps server VALIDATION_FAILED field errors onto fields", async () => {
    server.use(
      http.post("/api/auth/register", () =>
        HttpResponse.json(
          {
            code: "VALIDATION_FAILED",
            message: "请求参数不合法",
            details: { fieldErrors: [{ field: "email", code: "FORMAT", message: "邮箱格式不正确" }] },
          },
          { status: 400 },
        ),
      ),
    );
    await renderRegister();
    await fill({ email: "a@b" });

    expect(await screen.findByText("邮箱格式不正确")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
