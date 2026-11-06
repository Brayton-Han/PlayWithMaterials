import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { makeTask } from "@/test/fixtures";

import { TaskList } from "./TaskList";
import type { TaskEntry } from "./taskStore";

const nav = vi.hoisted(() => ({ router: { push: vi.fn(), replace: vi.fn() }, search: "" }));
vi.mock("next/navigation", async () => (await import("@/test/navigation")).mockNavigation(nav));

const entry = (task: TaskEntry["task"], query: TaskEntry["query"] = { status: "idle" }): TaskEntry => ({ task, query });

describe("TaskList", () => {
  it("空：显示还没有制作记录", () => {
    render(<TaskList entries={[]} onRefetch={vi.fn()} />);

    expect(screen.getByText("还没有制作记录")).toBeInTheDocument();
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
  });

  it("加载中：显示加载提示，不显示为空", () => {
    render(<TaskList entries={[]} onRefetch={vi.fn()} load={{ status: "loading" }} />);

    expect(screen.getByRole("status")).toHaveTextContent("正在加载制作记录…");
    expect(screen.queryByText("还没有制作记录")).not.toBeInTheDocument();
  });

  it("加载失败：错误 + 重试，不显示为空", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    render(<TaskList entries={[]} onRefetch={vi.fn()} load={{ status: "error", message: "服务器内部错误", onRetry }} />);

    expect(screen.getByRole("alert")).toHaveTextContent("制作记录加载失败：服务器内部错误");
    expect(screen.queryByText("还没有制作记录")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "重试" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("已有任务时加载失败：错误提示与已有卡片同时显示", () => {
    render(
      <TaskList
        entries={[entry(makeTask({ taskId: "1" }))]}
        onRefetch={vi.fn()}
        load={{ status: "error", message: "x", onRetry: vi.fn() }}
      />,
    );

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("article", { name: "任务 #1" })).toBeInTheDocument();
  });

  it("按传入顺序渲染多张卡片，各自的状态与重新查询互不串扰", async () => {
    const user = userEvent.setup();
    const onRefetch = vi.fn();
    render(
      <TaskList
        label="当前模板的制作记录"
        onRefetch={onRefetch}
        entries={[
          entry(makeTask({ taskId: "3", status: "PROCESSING", stage: "ASSET_CHECK" })),
          entry(makeTask({ taskId: "2", status: "PROCESSING", stage: "GENERATING" }), {
            status: "error",
            error: { kind: "http", status: 500, message: "服务器内部错误" },
          }),
          entry(
            makeTask({
              taskId: "1",
              status: "FAILED",
              stage: "GENERATING",
              failedStage: "GENERATING",
              failureCode: "SIMULATED_FAILURE",
              failureReason: "模拟失败：生成阶段出错",
            }),
          ),
        ]}
      />,
    );

    const list = screen.getByRole("list", { name: "当前模板的制作记录" });
    const cards = within(list).getAllByRole("article");
    expect(cards.map((c) => c.getAttribute("aria-label"))).toEqual(["任务 #3", "任务 #2", "任务 #1"]);
    expect(cards.map((c) => c.dataset.state)).toEqual(["processing", "processing", "failed"]);
    expect(cards.map((c) => c.dataset.query)).toEqual(["ok", "error", "ok"]);
    expect(within(cards[0]).getByText("处理中 · 素材检查中")).toBeInTheDocument();
    expect(within(cards[1]).getByText("处理中 · 生成中")).toBeInTheDocument();
    expect(within(cards[2]).getByText("生成阶段 · 模拟失败：生成阶段出错")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "重新查询" })).toHaveLength(1);

    await user.click(within(cards[1]).getByRole("button", { name: "重新查询" }));
    expect(onRefetch).toHaveBeenCalledExactlyOnceWith("2");
  });
});
