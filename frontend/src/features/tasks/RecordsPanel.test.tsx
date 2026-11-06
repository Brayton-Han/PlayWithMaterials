/**
 * "我的记录"页（T-055；F-11 规则 1 ~ 4，OQ-09 余项临时默认：倒序、不分页、不筛选）：
 * 不带 templateId 加载全部历史 → 复用 TaskCard（带模板标题 / 封面 / 详情页链接）→ 跟踪未结束任务；
 * 空 / 加载 / 错误态；与详情页共用 store，状态一致；未登录跳转登录页且不请求任务。
 */
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import RecordsPage from "@/app/records/page";
import { AuthProvider } from "@/features/auth/AuthProvider";
import { bumpSessionEpoch, getSessionEpoch, resetUserScopedState } from "@/features/auth/session";
import type { Task } from "@/lib/api/endpoints/tasks";
import { makeTask, makeUser } from "@/test/fixtures";
import { server } from "@/test/msw/server";
import { mockTaskHistory } from "@/test/msw/tasks";

import { HistoryPanel } from "./HistoryPanel";
import { RecordsPanel } from "./RecordsPanel";
import { taskStore } from "./taskStore";
import { TRACK_INTERVAL_MS } from "./useTaskTracker";

const nav = vi.hoisted(() => ({ router: { push: vi.fn(), replace: vi.fn() }, search: "" }));
vi.mock("next/navigation", async () => (await import("@/test/navigation")).mockNavigation(nav));

const T0 = "2026-11-05T08:00:00.000Z";
const at = (s: number) => new Date(Date.parse(T0) + s * 1000).toISOString();

const TEMPLATES = {
  "tpl-a": { templateTitle: "旅行明信片", templateCoverUrl: "/files/seed/covers/a.jpg" },
  "tpl-b": { templateTitle: "生日海报", templateCoverUrl: "/files/seed/covers/b.jpg" },
} as const;
type TemplateId = keyof typeof TEMPLATES;

const base = (taskId: string, templateId: TemplateId) => ({
  taskId,
  templateId,
  ...TEMPLATES[templateId],
  createdAt: at(Number(taskId)),
});
const pending = (taskId: string, templateId: TemplateId) =>
  makeTask({ ...base(taskId, templateId), updatedAt: at(Number(taskId)) });
const processing = (taskId: string, templateId: TemplateId, s = 0, stage: Task["stage"] = "GENERATING") =>
  makeTask({ ...base(taskId, templateId), status: "PROCESSING", stage, startedAt: at(0), updatedAt: at(s) });
const succeeded = (taskId: string, templateId: TemplateId, s = 5) =>
  makeTask({
    ...base(taskId, templateId),
    status: "SUCCEEDED",
    stage: "RESULT_ORGANIZING",
    resultUrl: `/files/results/${taskId}/r.png`,
    startedAt: at(0),
    finishedAt: at(s),
    updatedAt: at(s),
  });
const failed = (taskId: string, templateId: TemplateId, s = 5) =>
  makeTask({
    ...base(taskId, templateId),
    status: "FAILED",
    failedStage: "GENERATING",
    failureCode: "SIMULATED_FAILURE",
    failureReason: "模拟失败：生成阶段出错",
    startedAt: at(0),
    finishedAt: at(s),
    updatedAt: at(s),
  });

/** GET /api/tasks/:taskId：`reply(taskId, call)` 决定响应。 */
function mockGetTask(reply: (taskId: string, call: number) => Task | "500") {
  const calls: string[] = [];
  server.use(
    http.get("/api/tasks/:taskId", ({ params }) => {
      const taskId = String(params.taskId);
      const call = calls.filter((id) => id === taskId).length;
      calls.push(taskId);
      const r = reply(taskId, call);
      if (r === "500") return HttpResponse.json({ code: "INTERNAL_ERROR", message: "服务器内部错误" }, { status: 500 });
      return HttpResponse.json(r);
    }),
  );
  return calls;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

async function flush() {
  for (let i = 0; i < 10; i += 1) {
    await act(async () => {
      await new Promise<void>((resolve) => setImmediate(resolve));
    });
  }
}

async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
  await flush();
}

const card = (taskId: string) => screen.getByRole("article", { name: `任务 #${taskId}` });
const cardIds = () =>
  within(screen.getByRole("list", { name: "我的制作记录" }))
    .getAllByRole("article")
    .map((a) => a.getAttribute("aria-label"));

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date"] });
  vi.setSystemTime(new Date(T0));
  resetUserScopedState();
  bumpSessionEpoch();
  nav.router.replace.mockClear();
  window.history.replaceState(null, "", "/records");
});

afterEach(() => {
  vi.useRealTimers();
});

describe("RecordsPanel", () => {
  it("不带 templateId 请求一次全部历史：加载中 → 两个模板的任务都显示（倒序）并标明所属模板", async () => {
    mockGetTask(() => "500");
    const gate = deferred<Task[]>();
    const calls = mockTaskHistory(() => gate.promise);
    render(<RecordsPanel />);
    await flush();

    expect(screen.getByRole("status")).toHaveTextContent("正在加载制作记录");
    expect(screen.queryByText("还没有制作记录")).not.toBeInTheDocument();

    gate.resolve([succeeded("3", "tpl-b"), failed("2", "tpl-a"), succeeded("1", "tpl-a")]);
    await flush();

    expect(calls).toEqual([null]);
    expect(cardIds()).toEqual(["任务 #3", "任务 #2", "任务 #1"]);
    expect(within(card("3")).getByRole("link", { name: "生日海报" })).toBeInTheDocument();
    expect(within(card("2")).getByRole("link", { name: "旅行明信片" })).toBeInTheDocument();
    expect(card("2")).toHaveTextContent("制作失败");
    expect(card("2")).toHaveTextContent("生成阶段 · 模拟失败：生成阶段出错");
    expect(within(card("1")).getByRole("button", { name: "预览任务 #1结果" })).toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("模板标题链接进入对应模板详情页，并显示模板封面", async () => {
    mockGetTask(() => "500");
    mockTaskHistory(() => [succeeded("2", "tpl-b"), succeeded("1", "tpl-a")]);
    render(<RecordsPanel />);
    await flush();

    const linkB = within(card("2")).getByRole("link", { name: "生日海报" });
    expect(linkB).toHaveAttribute("href", "/templates/tpl-b");
    expect(linkB.querySelector("img")).toHaveAttribute("src", "/files/seed/covers/b.jpg");
    expect(within(card("1")).getByRole("link", { name: "旅行明信片" })).toHaveAttribute("href", "/templates/tpl-a");
  });

  it("模板封面加载失败只显示占位，标题链接仍可用", async () => {
    mockGetTask(() => "500");
    mockTaskHistory(() => [succeeded("1", "tpl-a")]);
    render(<RecordsPanel />);
    await flush();

    const link = within(card("1")).getByRole("link", { name: "旅行明信片" });
    fireEvent.error(link.querySelector("img")!);
    expect(link.querySelector("img")).toBeNull();
    expect(link).toHaveAttribute("href", "/templates/tpl-a");
  });

  it("历史为空时显示空状态，且不查询任何任务", async () => {
    const queries = mockGetTask(() => "500");
    mockTaskHistory(() => []);
    render(<RecordsPanel />);
    await flush();

    expect(screen.getByText("还没有制作记录")).toBeInTheDocument();
    await advance(TRACK_INTERVAL_MS * 3);
    expect(queries).toEqual([]);
  });

  it.each(["500", "network"] as const)("历史加载失败（%s）：错误 + 重试，不显示为空；重试成功后显示卡片", async (failure) => {
    mockGetTask(() => "500");
    const calls = mockTaskHistory((_, call) => (call === 0 ? failure : [succeeded("1", "tpl-a")]));
    render(<RecordsPanel />);
    await flush();

    expect(screen.getByRole("alert")).toHaveTextContent("制作记录加载失败");
    expect(screen.queryByText("还没有制作记录")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    await flush();

    expect(calls).toEqual([null, null]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(card("1")).toHaveTextContent("制作完成");
  });

  it("模板 A、B 的未结束任务都自动跟踪并持续更新到终态，终态任务不查询，之后不再请求", async () => {
    const queries = mockGetTask((taskId, call) => {
      if (taskId === "2") return call === 0 ? processing("2", "tpl-b", 2, "RESULT_ORGANIZING") : failed("2", "tpl-b", 4);
      return call === 0 ? processing("1", "tpl-a", 2, "GENERATING") : succeeded("1", "tpl-a", 4);
    });
    mockTaskHistory(() => [pending("2", "tpl-b"), processing("1", "tpl-a", 1, "ASSET_CHECK"), succeeded("0", "tpl-a")]);
    render(<RecordsPanel />);
    await flush();

    expect(card("2")).toHaveTextContent("等待处理");
    expect(card("1")).toHaveTextContent("处理中 · 素材检查中");

    await advance(TRACK_INTERVAL_MS);
    expect(card("2")).toHaveTextContent("处理中 · 结果整理中");
    expect(card("1")).toHaveTextContent("处理中 · 生成中");

    await advance(TRACK_INTERVAL_MS);
    expect(card("2")).toHaveTextContent("制作失败");
    expect(card("1")).toHaveTextContent("制作完成");
    expect(queries).not.toContain("0");

    const count = queries.length;
    await advance(TRACK_INTERVAL_MS * 5);
    expect(queries).toHaveLength(count);
  });

  it("单个任务查询失败：保留最后已知状态 + 查询异常提示，不显示为制作失败；重新查询恢复", async () => {
    mockGetTask((_, call) => (call === 0 ? "500" : succeeded("1", "tpl-a")));
    mockTaskHistory(() => [processing("1", "tpl-a", 1)]);
    render(<RecordsPanel />);
    await flush();
    await advance(TRACK_INTERVAL_MS);

    expect(card("1")).toHaveTextContent("处理中 · 生成中");
    expect(card("1")).toHaveTextContent("状态获取失败");
    expect(card("1")).not.toHaveTextContent("制作失败");

    fireEvent.click(within(card("1")).getByRole("button", { name: "重新查询" }));
    await flush();
    expect(card("1")).toHaveTextContent("制作完成");
    expect(card("1")).not.toHaveTextContent("状态获取失败");
  });

  it("卸载（离开页面）后不再请求", async () => {
    const queries = mockGetTask(() => processing("1", "tpl-a", 2));
    mockTaskHistory(() => [processing("1", "tpl-a", 1)]);
    const view = render(<RecordsPanel />);
    await flush();

    view.unmount();
    await advance(TRACK_INTERVAL_MS * 3);
    expect(queries).toEqual([]);
  });

  it("请求期间切换会话：迟到的历史响应不写入 store", async () => {
    mockGetTask(() => "500");
    const gate = deferred<Task[]>();
    mockTaskHistory(() => gate.promise);
    render(<RecordsPanel />);
    await flush();

    act(() => {
      resetUserScopedState();
      bumpSessionEpoch();
    });
    gate.resolve([succeeded("1", "tpl-a")]);
    await flush();

    expect(taskStore.getState().entries).toEqual({});
    expect(screen.queryByRole("article")).not.toBeInTheDocument();
  });

  it("与详情页状态一致：在记录页跟踪到终态后进入模板详情页，较旧的历史快照不会让任务回退", async () => {
    mockGetTask(() => succeeded("1", "tpl-a", 4));
    mockTaskHistory((templateId) =>
      templateId === "tpl-a" ? [processing("1", "tpl-a", 1)] : [processing("1", "tpl-a", 1), succeeded("2", "tpl-b")],
    );
    const records = render(<RecordsPanel />);
    await flush();
    await advance(TRACK_INTERVAL_MS);
    expect(card("1")).toHaveTextContent("制作完成");
    records.unmount();

    render(<HistoryPanel templateId="tpl-a" />);
    await flush();
    expect(card("1")).toHaveTextContent("制作完成");
    expect(screen.queryByRole("article", { name: "任务 #2" })).not.toBeInTheDocument();
  });

  it("详情页刚提交的任务（已在 store 中）在记录页立即显示并继续跟踪", async () => {
    mockGetTask(() => processing("5", "tpl-b", 7));
    const gate = deferred<Task[]>();
    mockTaskHistory(() => gate.promise);
    act(() => {
      taskStore.getState().upsertMany([pending("5", "tpl-b")], getSessionEpoch());
    });
    render(<RecordsPanel />);
    await flush();
    // 历史仍在加载：已有卡片照常显示
    expect(screen.getByRole("status")).toHaveTextContent("正在加载制作记录");
    expect(card("5")).toHaveTextContent("等待处理");

    await advance(TRACK_INTERVAL_MS);
    expect(card("5")).toHaveTextContent("处理中 · 生成中");
    gate.resolve([pending("5", "tpl-b")]);
    await flush();
    // 较旧的历史快照不会让任务回退
    expect(card("5")).toHaveTextContent("处理中 · 生成中");
  });
});

describe("/records 页面", () => {
  it("未登录：跳转登录页（带回跳地址），不请求任务历史", async () => {
    const calls = mockTaskHistory();
    server.use(
      http.get("/api/auth/me", () => HttpResponse.json({ code: "UNAUTHENTICATED", message: "请先登录" }, { status: 401 })),
    );
    render(
      <AuthProvider>
        <RecordsPage />
      </AuthProvider>,
    );
    await flush();

    expect(nav.router.replace).toHaveBeenCalledWith("/login?next=%2Frecords");
    expect(calls).toEqual([]);
    expect(screen.queryByRole("list", { name: "我的制作记录" })).not.toBeInTheDocument();
  });

  it("已登录：标题 + 全部模板的记录", async () => {
    mockGetTask(() => "500");
    const calls = mockTaskHistory(() => [succeeded("2", "tpl-b"), succeeded("1", "tpl-a")]);
    server.use(http.get("/api/auth/me", () => HttpResponse.json(makeUser("demo1"))));
    render(
      <AuthProvider>
        <RecordsPage />
      </AuthProvider>,
    );
    await flush();

    expect(screen.getByRole("heading", { level: 1, name: "我的记录" })).toBeInTheDocument();
    expect(calls).toEqual([null]);
    expect(cardIds()).toEqual(["任务 #2", "任务 #1"]);
    expect(nav.router.replace).not.toHaveBeenCalled();
  });
});
