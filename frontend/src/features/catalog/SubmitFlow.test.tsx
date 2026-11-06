/**
 * 提交与任务区衔接（T-053；F-05，F-09 规则 2，OQ-07 临时默认）：
 * 详情页提交受理（202）后立即把任务写入 taskStore → History 出现 PENDING 卡片并自动跟踪；
 * 提交按钮退出"提交中"，上传区清空；提交失败不插入任务。
 */
import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AuthProvider } from "@/features/auth/AuthProvider";
import { bumpSessionEpoch, resetUserScopedState } from "@/features/auth/session";
import { selectByTemplate, taskStore } from "@/features/tasks/taskStore";
import { TRACK_INTERVAL_MS } from "@/features/tasks/useTaskTracker";
import type { TemplateDetail } from "@/lib/api/endpoints/catalog";
import type { Task } from "@/lib/api/endpoints/tasks";
import { FakeXhr, installFakeXhr, lastXhr } from "@/test/fakeXhr";
import { makeTask, makeUser } from "@/test/fixtures";
import { server } from "@/test/msw/server";

import { TemplateDetailPage } from "./TemplateDetailPage";

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
  acceptedMimeTypes: ["image/jpeg", "image/png"],
  maxFileSizeBytes: 5 * 1024 * 1024,
};

const pending = (taskId: string, createdAt = "2026-11-05T08:00:00.000Z") =>
  makeTask({ taskId, templateId: "demo-postcard", status: "PENDING", stage: null, createdAt, updatedAt: createdAt });

/** GET /api/tasks/:taskId 的调用记录；默认回 500，避免意外的真实推进。 */
let taskQueries: string[] = [];
let replyTask: (taskId: string) => Task | null = () => null;

function renderPage() {
  server.use(
    http.get("/api/auth/me", () => HttpResponse.json(makeUser("demo1"))),
    http.get("/api/templates/:templateId", () => HttpResponse.json(POSTCARD)),
    http.get("/api/tasks/:taskId", ({ params }) => {
      const taskId = String(params.taskId);
      taskQueries.push(taskId);
      const task = replyTask(taskId);
      return task
        ? HttpResponse.json(task)
        : HttpResponse.json({ code: "INTERNAL_ERROR", message: "服务器内部错误" }, { status: 500 });
    }),
  );
  render(
    <AuthProvider>
      <TemplateDetailPage templateId="demo-postcard" />
    </AuthProvider>,
  );
}

let user: ReturnType<typeof userEvent.setup>;
let fileSeq = 0;
const png = () => new File([`png ${++fileSeq}`], `img-${fileSeq}.png`, { type: "image/png" });

async function selectBoth() {
  await user.upload(await screen.findByLabelText("选择第 1 张图片"), png());
  await user.upload(screen.getByLabelText("选择第 2 张图片"), png());
}

/** 选齐两张图并点击提交，返回本次请求的 XHR。 */
async function selectAndSubmit(): Promise<FakeXhr> {
  await selectBoth();
  await user.click(screen.getByRole("button", { name: "提交制作" }));
  return lastXhr();
}

const history = () => screen.getByRole("complementary", { name: "History" });
const uploadArea = () => screen.getByRole("region", { name: "上传素材" });
const card = (taskId: string) => within(history()).getByRole("article", { name: `任务 #${taskId}` });
const cardIds = () =>
  within(history())
    .queryAllByRole("article")
    .map((a) => a.getAttribute("aria-label"));
const storedIds = () => selectByTemplate(taskStore.getState().entries, "demo-postcard").map((e) => e.task.taskId);

beforeEach(() => {
  resetUserScopedState();
  bumpSessionEpoch();
  installFakeXhr();
  taskQueries = [];
  replyTask = () => null;
  user = userEvent.setup({ applyAccept: false });
  let seq = 0;
  URL.createObjectURL = vi.fn(() => `blob:http://localhost:3000/${++seq}`);
  URL.revokeObjectURL = vi.fn();
  window.history.replaceState(null, "", "/templates/demo-postcard");
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("提交与任务区衔接（T-053）", () => {
  it("202 后立即出现 PENDING 卡片，按钮退出提交中，上传区清空", async () => {
    renderPage();
    expect(await within(await screen.findByRole("complementary", { name: "History" })).findByText("还没有制作记录")).toBeInTheDocument();

    const xhr = await selectAndSubmit();
    xhr.finishUpload();
    expect(within(uploadArea()).getByRole("button", { name: "提交中…" })).toBeDisabled();
    expect(cardIds()).toEqual([]);

    await xhr.respond(202, pending("42"));

    // 同一次渲染内：卡片出现（无需等待查询），显示"等待处理"
    expect(card("42")).toHaveAttribute("data-state", "pending");
    expect(card("42")).toHaveTextContent("等待处理");
    expect(within(history()).queryByText("还没有制作记录")).not.toBeInTheDocument();
    expect(storedIds()).toEqual(["42"]);

    // 提交状态回到 idle：不再显示"提交中"，也没有进度条
    const area = within(uploadArea());
    expect(area.queryByRole("button", { name: "提交中…" })).not.toBeInTheDocument();
    expect(area.getByRole("button", { name: "提交制作" })).toBeInTheDocument();
    expect(area.queryByRole("progressbar")).not.toBeInTheDocument();
    expect(area.getByText("提交成功，任务 #42 已受理，正在后台制作")).toBeInTheDocument();

    // OQ-07 临时默认：上传位已清空，选图后可直接制作下一张
    expect(area.queryByRole("img", { name: /张图片预览/ })).not.toBeInTheDocument();
    expect(area.getByRole("button", { name: "提交制作" })).toBeDisabled();
    await selectBoth();
    expect(area.getByRole("button", { name: "提交制作" })).toBeEnabled();
  });

  it("连续提交 3 次出现 3 张独立卡片，新的在前", async () => {
    renderPage();
    const ids = ["101", "102", "103"];
    for (const [i, id] of ids.entries()) {
      const xhr = await selectAndSubmit();
      xhr.finishUpload();
      await xhr.respond(202, pending(id, `2026-11-05T08:00:0${i}.000Z`));
      expect(card(id)).toHaveTextContent("等待处理");
    }

    expect(FakeXhr.requests).toHaveLength(3);
    // 每次提交都是自己选的两张图
    expect(new Set(FakeXhr.requests.flatMap((x) => x.form.files.map((f) => f.name))).size).toBe(6);
    expect(cardIds()).toEqual(["任务 #103", "任务 #102", "任务 #101"]);
    expect(storedIds()).toEqual(["103", "102", "101"]);
  });

  it.each([
    ["网络错误", (xhr: FakeXhr) => xhr.failNetwork(), "上传失败：网络连接异常"],
    ["500", (xhr: FakeXhr) => xhr.respond(500, { code: "INTERNAL_ERROR", message: "服务器内部错误" }), "提交失败：服务器内部错误"],
    [
      "400 slotIndex",
      (xhr: FakeXhr) =>
        xhr.respond(400, {
          code: "ASSET_VALIDATION_FAILED",
          message: "素材校验失败",
          details: { fieldErrors: [{ field: "files", slotIndex: 1, message: "文件类型不受支持" }] },
        }),
      "提交失败：素材校验失败",
    ],
    ["503 队列满", (xhr: FakeXhr) => xhr.respond(503, { code: "QUEUE_FULL", message: "任务繁忙，请稍后重试" }), "任务繁忙"],
  ])("提交失败（%s）时不插入任务，已选文件保留", async (_name, fail, message) => {
    renderPage();
    const xhr = await selectAndSubmit();
    await fail(xhr);

    // 表单级错误（400 时上传位上还有各自的错误提示）
    expect(within(uploadArea()).getAllByRole("alert").some((a) => a.textContent?.includes(message))).toBe(true);
    expect(within(uploadArea()).getByRole("img", { name: "第 1 张图片预览" })).toBeInTheDocument();
    expect(within(history()).getByText("还没有制作记录")).toBeInTheDocument();
    expect(cardIds()).toEqual([]);
    expect(storedIds()).toEqual([]);
    expect(taskQueries).toEqual([]);
  });

  it("失败后重新提交成功，只出现一张卡片", async () => {
    renderPage();
    const first = await selectAndSubmit();
    await first.failNetwork();
    expect(cardIds()).toEqual([]);

    await user.click(within(uploadArea()).getByRole("button", { name: "重新提交" }));
    await lastXhr().respond(202, pending("55"));

    expect(cardIds()).toEqual(["任务 #55"]);
    expect(within(uploadArea()).queryByRole("alert")).not.toBeInTheDocument();
  });

  it("受理的任务自动开始跟踪：间隔后查询并推进阶段", async () => {
    // 跟踪调度器用 Date.now 计算到期时间，Date 一并伪造；shouldAdvanceTime 让 findBy / user-event 的真实等待照常推进
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date"], shouldAdvanceTime: true });
    replyTask = (taskId) =>
      makeTask({
        taskId,
        templateId: "demo-postcard",
        status: "PROCESSING",
        stage: "ASSET_CHECK",
        startedAt: "2026-11-05T08:00:01.000Z",
        updatedAt: "2026-11-05T08:00:01.000Z",
      });
    renderPage();
    const xhr = await selectAndSubmit();
    await xhr.respond(202, pending("42"));
    expect(card("42")).toHaveTextContent("等待处理");

    await act(async () => {
      await vi.advanceTimersByTimeAsync(TRACK_INTERVAL_MS);
    });

    expect(taskQueries).toEqual(["42"]);
    await vi.waitFor(() => expect(card("42")).toHaveTextContent("处理中 · 素材检查中"));
    expect(card("42")).toHaveAttribute("data-state", "processing");
  });
});
