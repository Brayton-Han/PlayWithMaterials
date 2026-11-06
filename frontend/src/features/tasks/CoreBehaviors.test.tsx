/**
 * 前端核心测试（T-056；requirements F-15 前端条目；implementation-plan "T-056 前端核心测试清单" #1 ~ #6）。
 *
 * 与各组件 / hook 的单元测试不同，这里渲染真实的模板详情页（AuthProvider + 顶栏用户菜单 + TemplateDetailPage），
 * 经过 MSW（查询、历史、身份接口）与 FakeXhr（提交上传）驱动完整链路：
 * 历史加载 / 提交受理 → taskStore → useTaskTracker 定时查询（fake timers）→ TaskCard 渲染。
 */
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AuthProvider, useAuth } from "@/features/auth/AuthProvider";
import { bumpSessionEpoch, resetUserScopedState } from "@/features/auth/session";
import { UserMenu } from "@/features/auth/UserMenu";
import { TemplateDetailPage } from "@/features/catalog/TemplateDetailPage";
import type { User } from "@/lib/api/endpoints/auth";
import type { TemplateDetail } from "@/lib/api/endpoints/catalog";
import type { Task, TaskStage } from "@/lib/api/endpoints/tasks";
import { installFakeXhr, lastXhr } from "@/test/fakeXhr";
import { makeTask, makeUser } from "@/test/fixtures";
import { server } from "@/test/msw/server";
import { mockTaskHistory } from "@/test/msw/tasks";

import { taskStore } from "./taskStore";
import { TRACK_INTERVAL_MS } from "./useTaskTracker";

const nav = vi.hoisted(() => ({ router: { push: vi.fn(), replace: vi.fn() }, search: "" }));
vi.mock("next/navigation", async () => (await import("@/test/navigation")).mockNavigation(nav));

const TEMPLATE_ID = "demo-postcard";
const POSTCARD: TemplateDetail = {
  templateId: TEMPLATE_ID,
  title: "旅行明信片",
  description: "上传图片，模拟生成一张明信片",
  categoryId: "creative",
  categoryName: "创意图片",
  coverUrl: "/files/seed/covers/postcard.jpg",
  requiredImageCount: 2,
  acceptedMimeTypes: ["image/jpeg", "image/png"],
  maxFileSizeBytes: 5 * 1024 * 1024,
};

const DEMO1 = makeUser("demo1", "1");
const DEMO2 = makeUser("demo2", "2");

// ---------- 任务构造 ----------

const T0 = "2026-11-05T08:00:00.000Z";
const at = (s: number) => new Date(Date.parse(T0) + s * 1000).toISOString();
/** taskId 越大创建越晚（卡片按创建时间倒序）。 */
const created = (taskId: string) => at(-1000 + Number(taskId));

const pending = (taskId: string, s = 0) =>
  makeTask({ taskId, templateId: TEMPLATE_ID, createdAt: created(taskId), updatedAt: at(s) });

const processing = (taskId: string, stage: TaskStage, s: number) =>
  makeTask({
    taskId,
    templateId: TEMPLATE_ID,
    status: "PROCESSING",
    stage,
    createdAt: created(taskId),
    startedAt: at(0),
    updatedAt: at(s),
  });

const resultUrl = (taskId: string) => `/files/results/${taskId}/result.png`;

const succeeded = (taskId: string, s: number) =>
  makeTask({
    taskId,
    templateId: TEMPLATE_ID,
    status: "SUCCEEDED",
    stage: "RESULT_ORGANIZING",
    resultUrl: resultUrl(taskId),
    createdAt: created(taskId),
    startedAt: at(0),
    finishedAt: at(s),
    updatedAt: at(s),
  });

const failed = (taskId: string, stage: TaskStage, reason: string, s: number) =>
  makeTask({
    taskId,
    templateId: TEMPLATE_ID,
    status: "FAILED",
    stage,
    failedStage: stage,
    failureCode: "SIMULATED_FAILURE",
    failureReason: reason,
    createdAt: created(taskId),
    startedAt: at(0),
    finishedAt: at(s),
    updatedAt: at(s),
  });

// ---------- MSW ----------

type Reply = Task | "500" | Promise<Task>;

/**
 * GET /api/tasks/:taskId：`script[taskId](call)` 按该任务的第几次查询决定响应。
 * 未声明的任务回 404。返回调用记录与每次请求的 AbortSignal。
 */
function mockGetTask(script: Record<string, (call: number) => Reply>) {
  const calls: string[] = [];
  const signals: AbortSignal[] = [];
  server.use(
    http.get("/api/tasks/:taskId", async ({ params, request }) => {
      const taskId = String(params.taskId);
      const call = calls.filter((id) => id === taskId).length;
      calls.push(taskId);
      signals.push(request.signal);
      const spec = script[taskId];
      if (!spec) return HttpResponse.json({ code: "TASK_NOT_FOUND", message: "任务不存在" }, { status: 404 });
      const reply = spec(call);
      if (reply === "500") return HttpResponse.json({ code: "INTERNAL_ERROR", message: "服务器内部错误" }, { status: 500 });
      return HttpResponse.json(await reply);
    }),
  );
  return {
    calls,
    signals,
    count: (taskId?: string) => (taskId ? calls.filter((id) => id === taskId).length : calls.length),
  };
}

/** 按调用次数依次返回，用完后一直返回最后一个。 */
function sequence(...replies: Reply[]): (call: number) => Reply {
  return (call) => replies[Math.min(call, replies.length - 1)];
}

/** 当前登录用户（/api/auth/me、登录接口返回它；历史按它返回）。 */
let currentUser: User | null;
let historyByUser: Record<string, Task[]>;
let historyCalls: (string | null)[];

function mockBackend() {
  historyCalls = mockTaskHistory(() => (currentUser ? (historyByUser[currentUser.username] ?? []) : "500"));
  server.use(
    http.get("/api/auth/me", () =>
      currentUser
        ? HttpResponse.json(currentUser)
        : HttpResponse.json({ code: "UNAUTHENTICATED", message: "请先登录" }, { status: 401 }),
    ),
    http.post("/api/auth/logout", () => {
      currentUser = null;
      return new HttpResponse(null, { status: 204 });
    }),
    http.post("/api/auth/login", async ({ request }) => {
      const body = (await request.json()) as { username: string };
      currentUser = body.username === DEMO2.username ? DEMO2 : DEMO1;
      return HttpResponse.json(currentUser);
    }),
    http.get("/api/templates/:templateId", () => HttpResponse.json(POSTCARD)),
  );
}

// ---------- 渲染与时间 ----------

/** 测试用登录入口（登录表单本身由 LoginForm.test 覆盖）：直接调用 AuthProvider.login。 */
function LoginAs() {
  const { login } = useAuth();
  return (
    <button type="button" onClick={() => void login({ username: DEMO2.username, password: "secret-123" })}>
      以 demo2 登录
    </button>
  );
}

async function renderDetailPage() {
  mockBackend();
  render(
    <AuthProvider>
      <UserMenu />
      <LoginAs />
      <TemplateDetailPage templateId={TEMPLATE_ID} />
    </AuthProvider>,
  );
  await flush();
}

/** 让 MSW 响应（Promise / 流读取）走完；setImmediate 不在 fake 范围内。 */
async function flush() {
  for (let i = 0; i < 10; i += 1) {
    await act(async () => {
      await new Promise<void>((resolve) => setImmediate(resolve));
    });
  }
}

/** 推进 n 个查询间隔，并让期间发出的请求完成。 */
async function tick(n = 1) {
  for (let i = 0; i < n; i += 1) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(TRACK_INTERVAL_MS);
    });
    await flush();
  }
}

/** 点击并让随后发出的请求完成（fake timers 下不用 user-event，避免其内部等待被冻结）。 */
async function click(element: HTMLElement) {
  act(() => {
    fireEvent.click(element);
  });
  await flush();
}

async function upload(input: HTMLElement, file: File) {
  act(() => {
    fireEvent.change(input, { target: { files: [file] } });
  });
  await flush();
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

// ---------- 查询 ----------

const history = () => screen.getByRole("complementary", { name: "History" });
const uploadArea = () => screen.getByRole("region", { name: "上传素材" });
const card = (taskId: string) => within(history()).getByRole("article", { name: `任务 #${taskId}` });
const cardIds = () =>
  within(history())
    .queryAllByRole("article")
    .map((a) => a.getAttribute("aria-label"));
/** 卡片的状态标题（第一行：等待处理 / 处理中 · 阶段 / 制作完成 / 制作失败）。 */
const statusOf = (taskId: string) => card(taskId).querySelector("p")?.textContent;

const STAGE_TEXTS = ["素材检查中", "生成中", "结果整理中"];

let fileSeq = 0;
const png = () => new File([`png ${++fileSeq}`], `img-${fileSeq}.png`, { type: "image/png" });

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date"] });
  vi.setSystemTime(new Date(T0));
  resetUserScopedState();
  bumpSessionEpoch();
  currentUser = DEMO1;
  historyByUser = {};
  nav.router.push.mockReset();
  nav.router.replace.mockReset();
  window.history.replaceState(null, "", `/templates/${TEMPLATE_ID}`);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("T-056 前端核心测试", () => {
  it("#1 同时跟踪 3 个任务：阶段各自推进，一个失败、两个成功，卡片内容互不串扰", async () => {
    historyByUser.demo1 = [pending("1"), pending("2"), pending("3")];
    const api = mockGetTask({
      "1": sequence(
        processing("1", "ASSET_CHECK", 1),
        processing("1", "GENERATING", 3),
        processing("1", "RESULT_ORGANIZING", 5),
        succeeded("1", 7),
      ),
      "2": sequence(processing("2", "GENERATING", 1), failed("2", "GENERATING", "模拟失败：生成阶段出错", 3)),
      "3": sequence(
        pending("3", 1),
        processing("3", "ASSET_CHECK", 3),
        processing("3", "GENERATING", 5),
        processing("3", "RESULT_ORGANIZING", 7),
        succeeded("3", 9),
      ),
    });
    await renderDetailPage();

    expect(historyCalls).toEqual([TEMPLATE_ID]);
    expect(cardIds()).toEqual(["任务 #3", "任务 #2", "任务 #1"]);
    expect([statusOf("1"), statusOf("2"), statusOf("3")]).toEqual(["等待处理", "等待处理", "等待处理"]);

    // 每一轮各卡片只显示自己的阶段
    const rounds: [string, string, string][] = [
      ["处理中 · 素材检查中", "处理中 · 生成中", "等待处理"],
      ["处理中 · 生成中", "制作失败", "处理中 · 素材检查中"],
      ["处理中 · 结果整理中", "制作失败", "处理中 · 生成中"],
      ["制作完成", "制作失败", "处理中 · 结果整理中"],
      ["制作完成", "制作失败", "制作完成"],
    ];
    for (const expected of rounds) {
      await tick();
      expect([statusOf("1"), statusOf("2"), statusOf("3")]).toEqual(expected);
    }

    // 失败阶段与原因只出现在任务 2 上
    expect(card("2")).toHaveAttribute("data-state", "failed");
    expect(card("2")).toHaveTextContent("生成阶段 · 模拟失败：生成阶段出错");
    for (const id of ["1", "3"]) {
      expect(card(id)).toHaveAttribute("data-state", "succeeded");
      expect(card(id)).not.toHaveTextContent("模拟失败");
      expect(card(id)).not.toHaveTextContent("制作失败");
    }
    // 结果图各是各的；失败任务没有结果预览
    expect(within(card("1")).getByRole("img", { name: "任务 #1 结果" })).toHaveAttribute("src", resultUrl("1"));
    expect(within(card("3")).getByRole("img", { name: "任务 #3 结果" })).toHaveAttribute("src", resultUrl("3"));
    expect(within(card("2")).queryByRole("button", { name: /预览/ })).not.toBeInTheDocument();
    // 每张卡片都没有查询异常，也不残留其他任务的阶段文案
    for (const id of ["1", "2", "3"]) {
      expect(card(id)).toHaveAttribute("data-query", "ok");
      for (const text of STAGE_TEXTS) expect(card(id)).not.toHaveTextContent(text);
    }

    // 每个任务只查询到自己进入终态为止
    expect(api.count("1")).toBe(4);
    expect(api.count("2")).toBe(2);
    expect(api.count("3")).toBe(5);
  });

  it("#2 任务 A 查询 500、任务 B 正常：A 保留处理中 · 生成中并显示查询异常，B 推进到成功；A 重新查询后恢复", async () => {
    historyByUser.demo1 = [processing("10", "GENERATING", 0), processing("11", "ASSET_CHECK", 0)];
    let healthy = false;
    const api = mockGetTask({
      "10": () => (healthy ? processing("10", "RESULT_ORGANIZING", 8) : "500"),
      "11": sequence(processing("11", "GENERATING", 1), processing("11", "RESULT_ORGANIZING", 3), succeeded("11", 5)),
    });
    await renderDetailPage();

    await tick();
    const a = card("10");
    expect(a).toHaveAttribute("data-state", "processing");
    expect(a).toHaveAttribute("data-query", "error");
    expect(statusOf("10")).toBe("处理中 · 生成中");
    expect(within(a).getByRole("status")).toHaveTextContent("状态获取失败：服务器内部错误");
    expect(within(a).getByRole("button", { name: "重新查询" })).toBeEnabled();
    expect(a).not.toHaveTextContent("制作失败");
    expect(statusOf("11")).toBe("处理中 · 生成中");
    expect(card("11")).toHaveAttribute("data-query", "ok");

    // A 持续失败（退避）期间 B 照常推进到成功；A 始终不变成"制作失败"
    await tick(2);
    expect(statusOf("11")).toBe("制作完成");
    expect(card("11")).toHaveAttribute("data-state", "succeeded");
    expect(within(card("11")).queryByRole("button", { name: "重新查询" })).not.toBeInTheDocument();
    expect(statusOf("10")).toBe("处理中 · 生成中");
    expect(card("10")).toHaveAttribute("data-query", "error");
    expect(card("10")).not.toHaveTextContent("制作失败");
    expect(taskStore.getState().entries["10"]?.task.status).toBe("PROCESSING");

    // 服务恢复后点击"重新查询"：立即查询并恢复，查询异常提示消失
    healthy = true;
    const before = api.count("10");
    await act(async () => {
      within(card("10")).getByRole("button", { name: "重新查询" }).click();
    });
    await flush();
    expect(api.count("10")).toBe(before + 1);
    expect(card("10")).toHaveAttribute("data-query", "ok");
    expect(statusOf("10")).toBe("处理中 · 结果整理中");
    expect(within(card("10")).queryByRole("status")).not.toBeInTheDocument();
    expect(within(card("10")).queryByRole("button", { name: "重新查询" })).not.toBeInTheDocument();
  });

  it("#3 服务端 FAILED：显示失败阶段和原因，与查询异常的文案、data-state 都不同", async () => {
    // 21：服务端事实为 FAILED；20：处理中但查询 500
    historyByUser.demo1 = [failed("21", "RESULT_ORGANIZING", "模拟失败：结果整理阶段出错", 4), processing("20", "GENERATING", 0)];
    const api = mockGetTask({ "20": () => "500" });
    await renderDetailPage();
    await tick();

    const serverFailed = card("21");
    const queryFailed = card("20");

    // 服务端 FAILED：失败外观 + 阶段 + 原因，没有查询异常提示和"重新查询"
    expect(serverFailed).toHaveAttribute("data-state", "failed");
    expect(serverFailed).toHaveAttribute("data-query", "ok");
    expect(statusOf("21")).toBe("制作失败");
    expect(serverFailed).toHaveTextContent("结果整理阶段 · 模拟失败：结果整理阶段出错");
    expect(serverFailed).not.toHaveTextContent("状态获取失败");
    expect(within(serverFailed).queryByRole("button", { name: "重新查询" })).not.toBeInTheDocument();

    // 查询异常：保留处理中外观，叠加查询异常提示，不是"制作失败"
    expect(queryFailed).toHaveAttribute("data-state", "processing");
    expect(queryFailed).toHaveAttribute("data-query", "error");
    expect(statusOf("20")).toBe("处理中 · 生成中");
    expect(queryFailed).toHaveTextContent("状态获取失败：服务器内部错误");
    expect(queryFailed).not.toHaveTextContent("制作失败");
    expect(queryFailed).not.toHaveTextContent("模拟失败");

    // 两种失败的状态标题、data-state 与样式类都不同
    expect(statusOf("21")).not.toBe(statusOf("20"));
    expect(serverFailed.getAttribute("data-state")).not.toBe(queryFailed.getAttribute("data-state"));
    expect(serverFailed.className).not.toBe(queryFailed.className);

    // 终态任务从未被查询
    expect(api.count("21")).toBe(0);
  });

  it("#4 某任务终态后停止对它的请求；全部终态后再推进 10 个间隔请求数不再增加", async () => {
    historyByUser.demo1 = [pending("30"), pending("31")];
    const api = mockGetTask({
      "30": sequence(succeeded("30", 1)),
      "31": sequence(
        processing("31", "ASSET_CHECK", 1),
        processing("31", "GENERATING", 3),
        failed("31", "GENERATING", "模拟失败：生成阶段出错", 5),
      ),
    });
    await renderDetailPage();

    await tick();
    expect(statusOf("30")).toBe("制作完成");
    expect(api.count("30")).toBe(1);

    // 30 已终态：之后的轮次只查询 31
    await tick(2);
    expect(statusOf("31")).toBe("制作失败");
    expect(api.count("30")).toBe(1);
    expect(api.count("31")).toBe(3);

    const total = api.count();
    const historyTotal = historyCalls.length;
    await tick(10);
    expect(api.count()).toBe(total);
    expect(historyCalls).toHaveLength(historyTotal);
  });

  it("#5 退出登录时有在途查询：迟到的响应不写入 store，界面不出现旧用户的任务", async () => {
    historyByUser.demo1 = [processing("40", "GENERATING", 0)];
    const late = deferred<Task>();
    const api = mockGetTask({ "40": () => late.promise });
    await renderDetailPage();
    expect(statusOf("40")).toBe("处理中 · 生成中");

    await tick();
    expect(api.count("40")).toBe(1);
    expect(taskStore.getState().entries["40"]?.query.status).toBe("loading");

    await click(screen.getByRole("button", { name: /账户菜单：demo1/ }));
    await click(screen.getByRole("button", { name: "退出" }));
    await flush();

    // 已退出：在途请求被取消，旧任务从 store 与界面上消失
    expect(within(screen.getByRole("navigation", { name: "账户" })).getByRole("link", { name: "登录" })).toBeInTheDocument();
    expect(api.signals[0].aborted).toBe(true);
    expect(taskStore.getState().entries).toEqual({});
    expect(within(history()).getByText("登录后即可制作")).toBeInTheDocument();

    // 旧响应晚于退出到达
    late.resolve(succeeded("40", 6));
    await flush();
    await tick(10);
    expect(taskStore.getState().entries).toEqual({});
    expect(screen.queryByRole("article")).not.toBeInTheDocument();
    expect(api.count()).toBe(1);
  });

  it("#5 退出后换 demo2 登录：即使取消未生效、demo1 的响应真的到达，也不写入，只显示 demo2 的任务", async () => {
    // 模拟"取消没来得及生效"（响应已在途）：fetch 忽略 AbortSignal，迟到的响应会真正交给应用代码，
    // 由跟踪器 / store 的会话校验负责丢弃，而不是靠请求被中止
    const realFetch = globalThis.fetch;
    vi.stubGlobal("fetch", (input: RequestInfo | URL, init?: RequestInit) => realFetch(input, { ...init, signal: undefined }));
    historyByUser.demo1 = [processing("40", "GENERATING", 0)];
    historyByUser.demo2 = [succeeded("50", 2)];
    const late = deferred<Task>();
    const api = mockGetTask({ "40": () => late.promise, "50": () => succeeded("50", 2) });
    await renderDetailPage();
    await tick();
    expect(api.count("40")).toBe(1);

    await click(screen.getByRole("button", { name: /账户菜单：demo1/ }));
    await click(screen.getByRole("button", { name: "退出" }));
    await flush();
    await click(screen.getByRole("button", { name: "以 demo2 登录" }));
    await flush();

    expect(screen.getByRole("button", { name: /账户菜单：demo2/ })).toBeInTheDocument();
    expect(historyCalls).toEqual([TEMPLATE_ID, TEMPLATE_ID]);
    expect(cardIds()).toEqual(["任务 #50"]);

    // demo1 的查询响应此时才到达
    late.resolve(succeeded("40", 6));
    await flush();
    await tick(10);

    expect(cardIds()).toEqual(["任务 #50"]);
    expect(Object.keys(taskStore.getState().entries)).toEqual(["50"]);
    expect(screen.queryByText("任务 #40")).not.toBeInTheDocument();
    // demo2 的任务已是终态，不查询；demo1 的任务不再被调度
    expect(api.count("40")).toBe(1);
    expect(api.count("50")).toBe(0);
  });

  it("#6 提交时网络错误显示在上传区且已选文件仍在；重新提交受理后，任务执行失败显示在任务卡片上", async () => {
    installFakeXhr();
    let objectUrlSeq = 0;
    URL.createObjectURL = vi.fn(() => `blob:http://localhost:3000/${++objectUrlSeq}`);
    URL.revokeObjectURL = vi.fn();
    const api = mockGetTask({
      "60": sequence(processing("60", "ASSET_CHECK", 1), failed("60", "ASSET_CHECK", "模拟失败：素材检查阶段出错", 3)),
    });
    await renderDetailPage();
    const area = within(uploadArea());

    await upload(area.getByLabelText("选择第 1 张图片"), png());
    await upload(area.getByLabelText("选择第 2 张图片"), png());
    await click(area.getByRole("button", { name: "提交制作" }));
    const first = lastXhr();
    await first.failNetwork();

    // 上传失败：上传区提示，已选图片保留，可直接重新提交；任务区没有任何卡片，也没有查询
    expect(area.getByRole("alert")).toHaveTextContent("上传失败：网络连接异常，已选图片已保留，可直接重新提交");
    expect(area.getByRole("img", { name: "第 1 张图片预览" })).toBeInTheDocument();
    expect(area.getByRole("img", { name: "第 2 张图片预览" })).toBeInTheDocument();
    expect(cardIds()).toEqual([]);
    expect(within(history()).getByText("还没有制作记录")).toBeInTheDocument();
    expect(within(history()).queryByText(/制作失败/)).not.toBeInTheDocument();

    // 不重新选图，直接重新提交：发送的是同样的文件
    await click(area.getByRole("button", { name: "重新提交" }));
    const second = lastXhr();
    expect(second).not.toBe(first);
    expect(second.form.files.map((f) => f.name)).toEqual(first.form.files.map((f) => f.name));
    await second.respond(202, pending("60"));

    expect(area.queryByRole("alert")).not.toBeInTheDocument();
    expect(statusOf("60")).toBe("等待处理");

    // 任务执行失败（服务端 FAILED）：显示在任务卡片上，上传区不显示上传失败
    await tick(2);
    expect(card("60")).toHaveAttribute("data-state", "failed");
    expect(statusOf("60")).toBe("制作失败");
    expect(card("60")).toHaveTextContent("素材检查阶段 · 模拟失败：素材检查阶段出错");
    expect(card("60")).not.toHaveTextContent("上传失败");
    expect(area.queryByRole("alert")).not.toBeInTheDocument();
    expect(uploadArea()).not.toHaveTextContent("上传失败");
    expect(uploadArea()).not.toHaveTextContent("制作失败");
    expect(api.count("60")).toBe(2);
  });
});
