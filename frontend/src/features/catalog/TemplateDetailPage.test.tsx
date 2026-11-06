import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AuthProvider, useAuth } from "@/features/auth/AuthProvider";
import { getSessionEpoch } from "@/features/auth/session";
import { taskStore } from "@/features/tasks/taskStore";
import type { TemplateDetail } from "@/lib/api/endpoints/catalog";
import { installFakeXhr, lastXhr } from "@/test/fakeXhr";
import { makeTask, makeUser } from "@/test/fixtures";
import { server } from "@/test/msw/server";

import { rememberListCategory } from "./listReturn";
import { TemplateDetailPage } from "./TemplateDetailPage";
import { formatFileSize } from "./TemplateRequirements";

const nav = vi.hoisted(() => ({ router: { push: vi.fn(), replace: vi.fn() }, search: "" }));
vi.mock("next/navigation", async () => (await import("@/test/navigation")).mockNavigation(nav));

const POSTCARD: TemplateDetail = {
  templateId: "demo-postcard",
  title: "旅行明信片",
  description: "上传图片，模拟生成一张明信片",
  categoryId: "creative",
  categoryName: "创意图片",
  coverUrl: "/files/seed/covers/postcard.jpg",
  requiredImageCount: 2,
  acceptedMimeTypes: ["image/jpeg", "image/png", "image/webp"],
  maxFileSizeBytes: 5 * 1024 * 1024,
};

const notFound = () =>
  HttpResponse.json({ code: "TEMPLATE_NOT_FOUND", message: "模板不存在" }, { status: 404 });
const anonymous = () => HttpResponse.json({ code: "UNAUTHENTICATED", message: "请先登录" }, { status: 401 });
const loggedIn = () => HttpResponse.json(makeUser("demo1"));

/** 记录每次详情请求的路径参数。 */
let detailRequests: string[] = [];

function useTemplateHandler(respond: (templateId: string) => Response | Promise<Response>) {
  server.use(
    http.get("/api/templates/:templateId", ({ params }) => {
      const templateId = String(params.templateId);
      detailRequests.push(templateId);
      return respond(templateId);
    }),
  );
}

function useCatalog(templates: TemplateDetail[]) {
  useTemplateHandler((id) => {
    const found = templates.find((t) => t.templateId === id);
    return found ? HttpResponse.json(found) : notFound();
  });
}

function renderPage(templateId: string, me: () => Response = anonymous) {
  server.use(http.get("/api/auth/me", me));
  return render(
    <AuthProvider>
      <TemplateDetailPage templateId={templateId} />
    </AuthProvider>,
  );
}

function backLink() {
  return screen.getByRole("link", { name: "← 返回模板列表" });
}

beforeEach(() => {
  detailRequests = [];
  rememberListCategory(null);
  window.history.replaceState(null, "", "/templates/demo-postcard");
});

describe("TemplateDetailPage", () => {
  it("shows a loading placeholder without upload slots, then the template from the API", async () => {
    useCatalog([POSTCARD]);
    renderPage("demo-postcard", loggedIn);

    expect(screen.getByRole("status", { name: "模板详情加载中" })).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "上传位" })).not.toBeInTheDocument();

    expect(await screen.findByRole("heading", { level: 1, name: "旅行明信片" })).toBeInTheDocument();
    expect(screen.queryByRole("status", { name: "模板详情加载中" })).not.toBeInTheDocument();
    expect(screen.getByText("创意图片")).toBeInTheDocument();
    expect(screen.getByText("上传图片，模拟生成一张明信片")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "旅行明信片 示例封面" })).toHaveAttribute(
      "src",
      "/files/seed/covers/postcard.jpg",
    );
    expect(detailRequests).toEqual(["demo-postcard"]);
  });

  it("shows the asset requirements from the API", async () => {
    useCatalog([{ ...POSTCARD, requiredImageCount: 3, acceptedMimeTypes: ["image/jpeg", "image/png"] }]);
    renderPage("demo-postcard");

    const requirements = await screen.findByRole("region", { name: "素材要求" });
    const values = within(requirements)
      .getAllByRole("definition")
      .map((dd) => dd.textContent);
    expect(values).toEqual(["3 张", "JPG / PNG", "5 MB"]);
  });

  it.each([1, 2, 3])("renders %i upload slot(s) for a logged-in user", async (count) => {
    useCatalog([{ ...POSTCARD, requiredImageCount: count }]);
    renderPage("demo-postcard", loggedIn);

    const slots = await screen.findByRole("list", { name: "上传位" });
    expect(within(slots).getAllByRole("listitem")).toHaveLength(count);
    expect(screen.queryByText("登录后即可制作")).not.toBeInTheDocument();
    expect(screen.getByRole("complementary", { name: "History" })).toBeInTheDocument();
  });

  it("shows only this template's tasks from the task store in History, with an empty state otherwise", async () => {
    useCatalog([POSTCARD]);
    renderPage("demo-postcard", loggedIn);

    const history = await screen.findByRole("complementary", { name: "History" });
    expect(await within(history).findByText("还没有制作记录")).toBeInTheDocument();
    expect(within(history).getByRole("button", { name: "刷新任务状态" })).toBeInTheDocument();

    // 终态任务不会触发跟踪请求
    const failedTask = makeTask({
      taskId: "8",
      templateId: "demo-postcard",
      status: "FAILED",
      stage: "GENERATING",
      failedStage: "GENERATING",
      failureCode: "SIMULATED_FAILURE",
      failureReason: "模拟失败：生成阶段出错",
    });
    act(() => {
      taskStore
        .getState()
        .upsertMany([failedTask, { ...failedTask, taskId: "9", templateId: "other" }], getSessionEpoch());
    });

    const card = within(history).getByRole("article", { name: "任务 #8" });
    expect(card).toHaveTextContent("制作失败");
    expect(card).toHaveTextContent("生成阶段 · 模拟失败：生成阶段出错");
    expect(within(history).queryByRole("article", { name: "任务 #9" })).not.toBeInTheDocument();
  });

  it("lets a logged-in user pick an image into a slot with a local preview and no upload request", async () => {
    URL.createObjectURL = vi.fn(() => "blob:http://localhost:3000/preview");
    URL.revokeObjectURL = vi.fn();
    useCatalog([{ ...POSTCARD, acceptedMimeTypes: ["image/png"], maxFileSizeBytes: 1024 * 1024 }]);
    const requests: string[] = [];
    server.events.on("request:start", ({ request }) => requests.push(`${request.method} ${new URL(request.url).pathname}`));
    renderPage("demo-postcard", loggedIn);
    const user = userEvent.setup({ applyAccept: false });

    const first = await screen.findByLabelText("选择第 1 张图片");
    expect(first).toHaveAttribute("accept", "image/png");
    // 监听器确实记录到了页面加载时的请求
    expect(requests).toContain("GET /api/templates/demo-postcard");
    const before = requests.length;

    await user.upload(first, new File(["png"], "a.png", { type: "image/png" }));
    expect(screen.getByRole("img", { name: "第 1 张图片预览" })).toHaveAttribute(
      "src",
      "blob:http://localhost:3000/preview",
    );

    // 模板只接受 PNG：JPG 被拦截，提示来自接口给出的类型
    await user.upload(screen.getByLabelText("选择第 2 张图片"), new File(["jpg"], "b.jpg", { type: "image/jpeg" }));
    expect(screen.getByRole("alert")).toHaveTextContent("第 2 张图片格式不支持，仅支持 PNG");
    expect(screen.queryByRole("img", { name: "第 2 张图片预览" })).not.toBeInTheDocument();

    expect(requests.slice(before)).toEqual([]);
    server.events.removeAllListeners();
  });

  describe("submitting (T-032)", () => {
    const POSTCARD_PNG = new File(["png"], "a.png", { type: "image/png" });
    const SECOND_PNG = new File(["png2"], "b.png", { type: "image/png" });

    function LogoutButton() {
      const { logout } = useAuth();
      return (
        <button type="button" onClick={() => void logout()}>
          退出测试
        </button>
      );
    }

    async function selectAndSubmit() {
      const user = userEvent.setup({ applyAccept: false });
      await user.upload(await screen.findByLabelText("选择第 1 张图片"), POSTCARD_PNG);
      await user.upload(screen.getByLabelText("选择第 2 张图片"), SECOND_PNG);
      await user.click(screen.getByRole("button", { name: "提交制作" }));
      return user;
    }

    beforeEach(() => {
      installFakeXhr();
      URL.createObjectURL = vi.fn(() => "blob:http://localhost:3000/preview");
      URL.revokeObjectURL = vi.fn();
      useCatalog([POSTCARD]);
    });
    afterEach(() => vi.unstubAllGlobals());

    it("uploads the selected images with progress, then shows the accepted task", async () => {
      renderPage("demo-postcard", loggedIn);
      await selectAndSubmit();
      const xhr = lastXhr();

      expect(xhr.url).toBe(`${window.location.origin}/api/tasks`);
      expect(xhr.form).toEqual({ templateId: "demo-postcard", files: [POSTCARD_PNG, SECOND_PNG] });
      xhr.progress(40, 100);
      expect(screen.getByRole("progressbar", { name: "上传进度" })).toHaveAttribute("value", "40");
      xhr.finishUpload();
      expect(screen.getByRole("button", { name: "提交中…" })).toBeDisabled();

      await xhr.respond(202, makeTask({ taskId: "42", templateId: "demo-postcard" }));
      expect(screen.getByText("提交成功，任务 #42 已受理，正在后台制作")).toBeInTheDocument();
      // 上传区已清空（OQ-07），按钮回到"提交制作"、等待重新选图
      expect(screen.getByRole("button", { name: "提交制作" })).toBeDisabled();
      expect(screen.queryByRole("img", { name: "第 1 张图片预览" })).not.toBeInTheDocument();
    });

    it("aborts an in-flight upload on logout and clears the selection", async () => {
      server.use(http.post("/api/auth/logout", () => new HttpResponse(null, { status: 204 })));
      server.use(http.get("/api/auth/me", loggedIn));
      render(
        <AuthProvider>
          <LogoutButton />
          <TemplateDetailPage templateId="demo-postcard" />
        </AuthProvider>,
      );
      const user = await selectAndSubmit();
      const xhr = lastXhr();

      await user.click(screen.getByRole("button", { name: "退出测试" }));

      expect(xhr.aborted).toBe(true);
      // 切换会话后页面以新的 epoch 重新挂载：模板重新加载，上传区变为登录提示，已选文件被清空
      const upload = await screen.findByRole("region", { name: "上传素材" });
      expect(await within(upload).findByText("登录后即可制作")).toBeInTheDocument();
      expect(screen.queryByRole("img", { name: "第 1 张图片预览" })).not.toBeInTheDocument();
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    });

    it("sends the user to login when the session has expired at submit time (401)", async () => {
      renderPage("demo-postcard", loggedIn);
      await selectAndSubmit();
      await lastXhr().respond(401, { code: "UNAUTHENTICATED", message: "请先登录" });

      expect(nav.router.replace).toHaveBeenCalledWith("/login?next=%2Ftemplates%2Fdemo-postcard");
      expect(screen.queryByText(/提交失败/)).not.toBeInTheDocument();
    });
  });

  it("replaces the upload area and History with a login prompt for anonymous users", async () => {
    useCatalog([POSTCARD]);
    renderPage("demo-postcard");

    await screen.findByRole("heading", { level: 1, name: "旅行明信片" });
    // 模板信息照常可见
    expect(screen.getByRole("region", { name: "素材要求" })).toBeInTheDocument();

    const upload = screen.getByRole("region", { name: "上传素材" });
    const history = screen.getByRole("complementary", { name: "History" });
    expect(await within(upload).findByText("登录后即可制作")).toBeInTheDocument();
    expect(within(history).getByText("登录后即可制作")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "上传位" })).not.toBeInTheDocument();

    // 登录 / 注册后回到本页
    expect(within(upload).getByRole("link", { name: "登录" })).toHaveAttribute(
      "href",
      "/login?next=%2Ftemplates%2Fdemo-postcard",
    );
    expect(within(upload).getByRole("link", { name: "注册新账户" })).toHaveAttribute(
      "href",
      "/register?next=%2Ftemplates%2Fdemo-postcard",
    );
    // 详情页不强制跳转登录
    expect(nav.router.replace).not.toHaveBeenCalled();
  });

  it("shows 模板不存在 with a way back to the list on 404", async () => {
    useCatalog([POSTCARD]);
    renderPage("no-such-template");

    expect(await screen.findByRole("heading", { name: "模板不存在" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "返回模板列表" })).toHaveAttribute("href", "/");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "重试" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "上传素材" })).not.toBeInTheDocument();
    expect(detailRequests).toEqual(["no-such-template"]);
  });

  it("treats an overlong or odd template ID as not found (encoded in the request path)", async () => {
    useCatalog([POSTCARD]);
    const odd = `${"x".repeat(80)}/../a b`;
    renderPage(odd);

    expect(await screen.findByRole("heading", { name: "模板不存在" })).toBeInTheDocument();
    expect(detailRequests).toEqual([odd]);
  });

  it("shows a load failure with retry on 500, not 模板不存在, and recovers after retry", async () => {
    let fail = true;
    useTemplateHandler(() =>
      fail
        ? HttpResponse.json({ code: "INTERNAL_ERROR", message: "服务器内部错误" }, { status: 500 })
        : HttpResponse.json(POSTCARD),
    );
    renderPage("demo-postcard");

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("模板加载失败");
    expect(screen.queryByText("模板不存在")).not.toBeInTheDocument();

    fail = false;
    await userEvent.click(within(alert).getByRole("button", { name: "重试" }));
    expect(await screen.findByRole("heading", { level: 1, name: "旅行明信片" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(detailRequests).toEqual(["demo-postcard", "demo-postcard"]);
  });

  it("shows a load failure (not 不存在) when the backend is unreachable", async () => {
    useTemplateHandler(() => HttpResponse.error());
    renderPage("demo-postcard");

    expect(await screen.findByRole("alert")).toHaveTextContent("网络连接异常");
    expect(screen.queryByText("模板不存在")).not.toBeInTheDocument();
  });

  it("treats a 404 without TEMPLATE_NOT_FOUND (e.g. proxy HTML) as a load failure", async () => {
    useTemplateHandler(() => new HttpResponse("<html>Not Found</html>", { status: 404 }));
    renderPage("demo-postcard");

    expect(await screen.findByRole("alert")).toHaveTextContent("模板加载失败");
    expect(screen.queryByText("模板不存在")).not.toBeInTheDocument();
  });

  it("back link returns to the list with the category the user came from", async () => {
    useCatalog([POSTCARD]);
    rememberListCategory("portrait");
    renderPage("demo-postcard");

    await screen.findByRole("heading", { level: 1, name: "旅行明信片" });
    expect(backLink()).toHaveAttribute("href", "/?category=portrait");
  });

  it("back link goes to the full list when the page was opened directly", async () => {
    useCatalog([POSTCARD]);
    renderPage("demo-postcard");

    expect(backLink()).toHaveAttribute("href", "/");
    await screen.findByRole("heading", { level: 1, name: "旅行明信片" });
    expect(backLink()).toHaveAttribute("href", "/");
  });
});

describe("formatFileSize", () => {
  it.each([
    [5 * 1024 * 1024, "5 MB"],
    [1.5 * 1024 * 1024, "1.5 MB"],
    [500 * 1024, "500 KB"],
    [999, "999 B"],
  ])("%i → %s", (bytes, label) => {
    expect(formatFileSize(bytes)).toBe(label);
  });
});
