import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { Task } from "@/lib/api/endpoints/tasks";
import { makeTask } from "@/test/fixtures";

import { TaskCard } from "./TaskCard";
import type { TaskEntry, TaskQueryState } from "./taskStore";
import { formatDateTime } from "./taskView";

const nav = vi.hoisted(() => ({ router: { push: vi.fn(), replace: vi.fn() }, search: "" }));
vi.mock("next/navigation", async () => (await import("@/test/navigation")).mockNavigation(nav));

const IDLE: TaskQueryState = { status: "idle" };

const pending = () => makeTask({ taskId: "42" });
const processing = (stage: Task["stage"] = "GENERATING") =>
  makeTask({ taskId: "42", status: "PROCESSING", stage, startedAt: "2026-11-05T08:00:00.100Z" });
const succeeded = () =>
  makeTask({
    taskId: "42",
    status: "SUCCEEDED",
    stage: "RESULT_ORGANIZING",
    resultUrl: "/files/results/42/r.png",
    finishedAt: "2026-11-05T08:00:06.000Z",
    updatedAt: "2026-11-05T08:00:06.000Z",
  });
const failed = (extra: Partial<Task> = {}) =>
  makeTask({
    taskId: "42",
    status: "FAILED",
    stage: "GENERATING",
    failedStage: "GENERATING",
    failureCode: "SIMULATED_FAILURE",
    failureReason: "模拟失败：生成阶段出错",
    finishedAt: "2026-11-05T08:00:04.000Z",
    updatedAt: "2026-11-05T08:00:04.000Z",
    ...extra,
  });

const networkError: TaskQueryState = {
  status: "error",
  error: { kind: "network", message: "网络连接异常，请检查网络后重试" },
};

function renderCard(task: Task, query: TaskQueryState = IDLE, props: { showTemplate?: boolean } = {}) {
  const onRefetch = vi.fn();
  const entry: TaskEntry = { task, query };
  const view = render(<TaskCard entry={entry} onRefetch={onRefetch} {...props} />);
  const card = screen.getByRole("article", { name: "任务 #42" });
  return { card, onRefetch, ...view };
}

describe("TaskCard 外观", () => {
  it("等待处理：显示输入图缩略图和本地时间", () => {
    const { card } = renderCard(pending());

    expect(card).toHaveAttribute("data-state", "pending");
    expect(card).toHaveAttribute("data-query", "ok");
    expect(within(card).getByText("等待处理")).toBeInTheDocument();
    expect(within(card).getByRole("img", { name: "任务 #42 输入图" })).toHaveAttribute(
      "src",
      "/files/uploads/2026/11/a.jpg",
    );
    const time = card.querySelector("time")!;
    expect(time).toHaveAttribute("dateTime", "2026-11-05T08:00:00.000Z");
    expect(time.textContent).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
    expect(within(card).queryByRole("button")).not.toBeInTheDocument();
  });

  it.each([
    ["ASSET_CHECK", "处理中 · 素材检查中"],
    ["GENERATING", "处理中 · 生成中"],
    ["RESULT_ORGANIZING", "处理中 · 结果整理中"],
  ] as const)("处理中 · %s → %s", (stage, text) => {
    const { card } = renderCard(processing(stage));

    expect(card).toHaveAttribute("data-state", "processing");
    expect(within(card).getByText(text)).toBeInTheDocument();
  });

  it("制作完成：显示结果缩略图，可打开预览", () => {
    const { card } = renderCard(succeeded());

    expect(card).toHaveAttribute("data-state", "succeeded");
    expect(within(card).getByText("制作完成")).toBeInTheDocument();
    const trigger = within(card).getByRole("button", { name: "预览任务 #42结果" });
    expect(within(trigger).getByRole("img")).toHaveAttribute("src", "/files/results/42/r.png");
    expect(within(card).queryByRole("img", { name: "任务 #42 输入图" })).not.toBeInTheDocument();
  });

  it("制作失败：显示失败阶段与原因", () => {
    const { card } = renderCard(failed());

    expect(card).toHaveAttribute("data-state", "failed");
    expect(within(card).getByText("制作失败")).toBeInTheDocument();
    expect(within(card).getByText("生成阶段 · 模拟失败：生成阶段出错")).toBeInTheDocument();
    expect(within(card).queryByText(/状态获取失败/)).not.toBeInTheDocument();
    expect(within(card).queryByRole("button", { name: "重新查询" })).not.toBeInTheDocument();
  });

  it("任务未开始就被废弃（failedStage = null）只显示原因", () => {
    const { card } = renderCard(
      failed({ stage: null, failedStage: null, failureCode: "ACCOUNT_DELETED", failureReason: "账户已删除" }),
    );

    expect(within(card).getByText("账户已删除")).toBeInTheDocument();
    expect(within(card).queryByText(/阶段 ·/)).not.toBeInTheDocument();
  });

  it("查询异常叠加在最后已知状态之上，提供重新查询", async () => {
    const user = userEvent.setup();
    const { card, onRefetch } = renderCard(processing(), networkError);

    // 仍是处理中·生成中，不是制作失败
    expect(card).toHaveAttribute("data-state", "processing");
    expect(card).toHaveAttribute("data-query", "error");
    expect(within(card).getByText("处理中 · 生成中")).toBeInTheDocument();
    expect(within(card).queryByText(/制作失败/)).not.toBeInTheDocument();
    expect(within(card).getByRole("status")).toHaveTextContent("状态获取失败：网络连接异常，请检查网络后重试");

    await user.click(within(card).getByRole("button", { name: "重新查询" }));
    expect(onRefetch).toHaveBeenCalledWith("42");
  });

  it("查询异常与制作失败的文案和样式（data-state）都不同", () => {
    const { card: errorCard, unmount } = renderCard(
      processing(),
      { status: "error", error: { kind: "http", status: 500, message: "服务器内部错误" } },
    );
    const errorState = { state: errorCard.dataset.state, query: errorCard.dataset.query, text: errorCard.textContent };
    unmount();
    const { card: failedCard } = renderCard(failed());

    expect(errorState.state).toBe("processing");
    expect(errorState.query).toBe("error");
    expect(errorState.text).toContain("状态获取失败：服务器内部错误");
    expect(errorState.text).not.toContain("制作失败");
    expect(failedCard.dataset.state).toBe("failed");
    expect(failedCard.dataset.query).toBe("ok");
    expect(failedCard.textContent).toContain("制作失败");
    expect(failedCard.textContent).not.toContain("状态获取失败");
    expect(failedCard.className).not.toBe(errorCard.className);
  });

  it("重查进行中：保留异常提示，按钮禁用", () => {
    const { card } = renderCard(processing(), { status: "loading", error: networkError.error });

    expect(within(card).getByRole("status")).toHaveTextContent("状态获取失败");
    expect(within(card).getByRole("button", { name: "重新查询中…" })).toBeDisabled();
  });

  it("404：提示任务不存在或无权访问，仍可手动重查", async () => {
    const user = userEvent.setup();
    const { card, onRefetch } = renderCard(processing(), {
      status: "error",
      error: { kind: "notFound", status: 404, message: "任务不存在" },
    });

    expect(within(card).getByRole("status")).toHaveTextContent("任务不存在或无权访问");
    expect(within(card).getByText("处理中 · 生成中")).toBeInTheDocument();
    await user.click(within(card).getByRole("button", { name: "重新查询" }));
    expect(onRefetch).toHaveBeenCalledWith("42");
  });

  it("结果图加载失败：显示占位与提示，任务仍是制作完成", () => {
    const { card } = renderCard(succeeded());

    fireEvent.error(within(card).getByRole("img", { name: "任务 #42 结果" }));

    expect(within(card).getByRole("img", { name: "结果加载失败" })).toBeInTheDocument();
    expect(card).toHaveAttribute("data-state", "succeeded");
    expect(within(card).getByText("制作完成")).toBeInTheDocument();
    expect(within(card).queryByRole("button", { name: /预览/ })).not.toBeInTheDocument();
  });

  it("输入图加载失败：显示占位，状态不变", () => {
    const { card } = renderCard(processing());

    fireEvent.error(within(card).getByRole("img", { name: "任务 #42 输入图" }));

    expect(within(card).getByRole("img", { name: "素材加载失败" })).toBeInTheDocument();
    expect(within(card).getByText("处理中 · 生成中")).toBeInTheDocument();
  });

  it("默认不显示模板信息；showTemplate 时显示模板标题和封面，链接到模板详情页", () => {
    const { card, unmount } = renderCard(pending());
    expect(within(card).queryByRole("link")).not.toBeInTheDocument();
    unmount();

    const { card: withTemplate } = renderCard(makeTask({ taskId: "42", templateId: "a b/c" }), IDLE, {
      showTemplate: true,
    });
    const link = within(withTemplate).getByRole("link", { name: "明信片" });
    expect(link).toHaveAttribute("href", "/templates/a%20b%2Fc");
    expect(link.querySelector("img")).toHaveAttribute("src", "/files/seed/covers/postcard.jpg");
  });
});

describe("ResultPreviewDialog", () => {
  async function openPreview() {
    const user = userEvent.setup();
    const { card } = renderCard(succeeded());
    const trigger = within(card).getByRole("button", { name: "预览任务 #42结果" });
    await user.click(trigger);
    const dialog = screen.getByRole("dialog", { name: "任务 #42 结果预览" });
    return { user, trigger, dialog };
  }

  it("点击缩略图打开模态对话框，显示大图，焦点在关闭按钮", async () => {
    const { dialog } = await openPreview();

    expect(dialog).toHaveAttribute("open");
    expect(within(dialog).getByRole("img", { name: "任务 #42 结果预览大图" })).toHaveAttribute(
      "src",
      "/files/results/42/r.png",
    );
    expect(within(dialog).getByRole("button", { name: "关闭" })).toHaveFocus();
  });

  it("Escape 关闭，焦点回到触发按钮", async () => {
    const { trigger, dialog } = await openPreview();

    // 浏览器中 Escape 触发 cancel 事件（jsdom 不实现，直接派发）
    fireEvent(dialog, new Event("cancel", { cancelable: true }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("关闭按钮关闭，焦点回到触发按钮", async () => {
    const { user, trigger, dialog } = await openPreview();

    await user.click(within(dialog).getByRole("button", { name: "关闭" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("点击遮罩关闭，点击图片不关闭", async () => {
    const { user, dialog } = await openPreview();

    await user.click(within(dialog).getByRole("img"));
    expect(screen.getByRole("dialog")).toBeInTheDocument();

    fireEvent.click(dialog);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("大图加载失败显示占位", async () => {
    const { dialog } = await openPreview();

    fireEvent.error(within(dialog).getByRole("img", { name: "任务 #42 结果预览大图" }));

    expect(within(dialog).getByRole("img", { name: "结果加载失败" })).toBeInTheDocument();
  });
});

describe("formatDateTime", () => {
  it("浏览器本地时区 YYYY-MM-DD HH:mm，补零", () => {
    expect(formatDateTime(new Date(2026, 0, 5, 7, 3, 59).toISOString())).toBe("2026-01-05 07:03");
  });

  it("无法解析时原样返回", () => {
    expect(formatDateTime("not-a-date")).toBe("not-a-date");
  });
});
