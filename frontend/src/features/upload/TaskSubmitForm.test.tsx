import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";

import { bumpSessionEpoch, getSessionEpoch } from "@/features/auth/session";
import { abortAllRequests, ApiError, setUnauthorizedHandler } from "@/lib/api/client";
import type { Task } from "@/lib/api/endpoints/tasks";
import { makeTask } from "@/test/fixtures";
import { FakeXhr, installFakeXhr, lastXhr } from "@/test/fakeXhr";

import { TaskSubmitForm } from "./TaskSubmitForm";
import { canSubmit, isSubmitBusy, submitErrorMessage, useSubmitTask, type SubmitState } from "./useSubmitTask";

const MB = 1024 * 1024;
const TEMPLATE = {
  templateId: "demo-postcard",
  requiredImageCount: 2,
  acceptedMimeTypes: ["image/jpeg", "image/png"] as ("image/jpeg" | "image/png")[],
  maxFileSizeBytes: 1 * MB,
};

function image(name: string, type = "image/png"): File {
  return new File([`content of ${name}`], name, { type });
}

const input = (n: number) => screen.getByLabelText(`选择第 ${n} 张图片`);
const removeButton = (n: number) => screen.getByRole("button", { name: `移除第 ${n} 张图片` });
const submitButton = () => within(screen.getByRole("form", { name: "提交制作" })).getByRole("button", { name: /提交|上传中|重新提交/ });
const slotItem = (n: number) => screen.getAllByRole("listitem")[n - 1];

let user: ReturnType<typeof userEvent.setup>;
let onAccepted: Mock<(task: Task, epoch: number) => void>;

function renderForm() {
  onAccepted = vi.fn<(task: Task, epoch: number) => void>();
  return render(<TaskSubmitForm template={TEMPLATE} onAccepted={onAccepted} />);
}

async function selectBoth() {
  await user.upload(input(1), image("a.png"));
  await user.upload(input(2), image("b.png"));
}

beforeEach(() => {
  installFakeXhr();
  user = userEvent.setup({ applyAccept: false });
  let seq = 0;
  URL.createObjectURL = vi.fn(() => `blob:http://localhost:3000/${++seq}`);
  URL.revokeObjectURL = vi.fn();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("TaskSubmitForm", () => {
  it("keeps the submit button disabled until every slot holds a valid image", async () => {
    renderForm();

    expect(submitButton()).toBeDisabled();
    expect(submitButton()).toHaveAccessibleDescription("请选择第 1、2 张图片");

    await user.upload(input(1), image("a.png"));
    expect(submitButton()).toBeDisabled();
    expect(submitButton()).toHaveAccessibleDescription("请选择第 2 张图片");

    // 不合法的文件不进入上传位，按钮仍禁用
    await user.upload(input(2), image("b.gif", "image/gif"));
    expect(submitButton()).toBeDisabled();

    await user.upload(input(2), image("b.png"));
    expect(submitButton()).toBeEnabled();
    expect(submitButton()).toHaveTextContent("提交制作");

    await user.click(removeButton(1));
    expect(submitButton()).toBeDisabled();
    await user.click(submitButton());
    expect(FakeXhr.requests).toHaveLength(0);
  });

  it("sends a single request on double click, with templateId and the files in slot order", async () => {
    renderForm();
    await selectBoth();

    await user.dblClick(submitButton());

    expect(FakeXhr.requests).toHaveLength(1);
    const { templateId, files } = lastXhr().form;
    expect(templateId).toBe("demo-postcard");
    expect(files.map((f) => f.name)).toEqual(["a.png", "b.png"]);
    expect(submitButton()).toBeDisabled();
  });

  it("goes uploading(progress) → submitting → idle on progress, upload end and 202", async () => {
    renderForm();
    await selectBoth();
    await user.click(submitButton());
    const xhr = lastXhr();

    expect(submitButton()).toHaveTextContent("上传中 0%");
    expect(screen.getByRole("progressbar", { name: "上传进度" })).toHaveAttribute("value", "0");

    xhr.progress(50, 100);
    expect(submitButton()).toHaveTextContent("上传中 50%");
    expect(screen.getByRole("progressbar", { name: "上传进度" })).toHaveAttribute("value", "50");
    expect(screen.getByText("素材上传中 50%")).toBeInTheDocument();

    xhr.progress(1, 0, false);
    expect(submitButton()).toHaveTextContent("上传中…");

    xhr.progress(100, 100);
    xhr.finishUpload();
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
    expect(submitButton()).toHaveTextContent("提交中…");
    expect(submitButton()).toBeDisabled();
    expect(screen.getByText("素材已上传，正在提交…")).toBeInTheDocument();

    const task = makeTask({ taskId: "42", templateId: "demo-postcard" });
    await xhr.respond(202, task);

    expect(onAccepted).toHaveBeenCalledTimes(1);
    expect(onAccepted).toHaveBeenCalledWith(task, getSessionEpoch());
    expect(submitButton()).toHaveTextContent("提交制作");
    expect(screen.getByText("提交成功，任务 #42 已受理，正在后台制作")).toBeInTheDocument();
    // OQ-07 临时默认：受理后清空上传位，按钮因未选图而禁用（不是"提交中"）
    expect(submitButton()).toBeDisabled();
    expect(submitButton()).toHaveAccessibleDescription("请选择第 1、2 张图片");
  });

  it("locks the slots while uploading and submitting, then clears and unlocks them on 202 (OQ-07)", async () => {
    renderForm();
    await selectBoth();
    await user.click(submitButton());

    expect(input(1)).toBeDisabled();
    expect(removeButton(2)).toBeDisabled();
    await user.upload(input(1), image("other.png"));
    await user.click(removeButton(2));
    expect(within(slotItem(1)).getByText("a.png")).toBeInTheDocument();
    expect(within(slotItem(2)).getByText("b.png")).toBeInTheDocument();

    lastXhr().finishUpload();
    expect(input(2)).toBeDisabled();

    await lastXhr().respond(202, makeTask({ taskId: "1" }));
    expect(input(1)).toBeEnabled();
    expect(input(2)).toBeEnabled();
    expect(screen.queryByRole("img", { name: /张图片预览/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /移除第/ })).not.toBeInTheDocument();
    // 两张预览的 blob URL 都已释放
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:http://localhost:3000/1");
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:http://localhost:3000/2");

    // 清空后可以直接选图制作下一张
    await selectBoth();
    expect(submitButton()).toBeEnabled();
  });

  it("keeps the selected files after a network failure and resubmits them without reselecting", async () => {
    renderForm();
    await selectBoth();
    await user.click(submitButton());
    lastXhr().progress(30, 100);
    await lastXhr().failNetwork();

    expect(screen.getByRole("alert")).toHaveTextContent("上传失败：网络连接异常，已选图片已保留，可直接重新提交");
    expect(screen.getByRole("img", { name: "第 1 张图片预览" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "第 2 张图片预览" })).toBeInTheDocument();
    expect(onAccepted).not.toHaveBeenCalled();
    expect(submitButton()).toHaveTextContent("重新提交");
    expect(submitButton()).toBeEnabled();
    expect(input(1)).toBeEnabled();

    await user.click(submitButton());
    expect(FakeXhr.requests).toHaveLength(2);
    expect(lastXhr().form.files.map((f) => f.name)).toEqual(["a.png", "b.png"]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    await lastXhr().respond(202, makeTask({ taskId: "7" }));
    expect(onAccepted).toHaveBeenCalledTimes(1);
  });

  it("shows a 400 slotIndex error on the matching slot and requires replacing it", async () => {
    renderForm();
    await selectBoth();
    await user.click(submitButton());
    await lastXhr().respond(400, {
      code: "ASSET_VALIDATION_FAILED",
      message: "部分图片不符合要求",
      details: {
        fieldErrors: [
          { field: "files", slotIndex: 1, code: "FILE_TYPE_NOT_ACCEPTED", message: "第 2 张图片格式不支持，仅支持 JPEG、PNG" },
        ],
      },
    });

    expect(within(slotItem(2)).getByRole("alert")).toHaveTextContent("第 2 张图片格式不支持，仅支持 JPEG、PNG");
    expect(within(slotItem(1)).queryByRole("alert")).not.toBeInTheDocument();
    expect(input(2)).toHaveAttribute("aria-invalid", "true");
    // 文件与预览都保留
    expect(within(slotItem(2)).getByText("b.png")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "第 2 张图片预览" })).toBeInTheDocument();
    expect(screen.getByText("提交失败：部分图片不符合要求，请替换标出的图片后重新提交")).toBeInTheDocument();
    expect(submitButton()).toBeDisabled();
    expect(submitButton()).toHaveAccessibleDescription("请替换第 2 张图片");

    await user.upload(input(2), image("c.jpg", "image/jpeg"));
    expect(submitButton()).toBeEnabled();
    await user.click(submitButton());
    expect(lastXhr().form.files.map((f) => f.name)).toEqual(["a.png", "c.jpg"]);
  });

  it("shows the server message for 413 and keeps the files", async () => {
    renderForm();
    await selectBoth();
    await user.click(submitButton());
    await lastXhr().respond(413, {
      code: "PAYLOAD_TOO_LARGE",
      message: "上传内容过大，单张图片不超过 5 MB，总大小不超过 20 MB",
      details: { maxFileSizeBytes: 5242880, maxRequestSizeBytes: 20971520 },
    });

    expect(screen.getByRole("alert")).toHaveTextContent("上传失败：上传内容过大，单张图片不超过 5 MB，总大小不超过 20 MB");
    expect(screen.getAllByRole("img")).toHaveLength(2);
    expect(submitButton()).toBeEnabled();
  });

  it.each([
    [503, { code: "QUEUE_FULL", message: "系统繁忙，请稍后重试" }, "提交失败：系统繁忙，请稍后重试，可直接重新提交"],
    [502, "<html>Bad Gateway</html>", "提交失败：服务器开小差了，请稍后重试，可直接重新提交"],
    [404, { code: "TEMPLATE_NOT_FOUND", message: "模板不存在" }, "提交失败：模板不存在"],
  ])("shows a readable error for %i without inserting a task", async (status, body, text) => {
    renderForm();
    await selectBoth();
    await user.click(submitButton());
    await lastXhr().respond(status, body);

    expect(screen.getByRole("alert")).toHaveTextContent(text);
    expect(onAccepted).not.toHaveBeenCalled();
    expect(submitButton()).toBeEnabled();
  });

  it("aborts the upload when the page unmounts", async () => {
    const view = renderForm();
    await selectBoth();
    await user.click(submitButton());
    const xhr = lastXhr();

    view.unmount();
    expect(xhr.aborted).toBe(true);
    expect(onAccepted).not.toHaveBeenCalled();
  });

  it("aborts on session-wide cancellation (logout) and returns to idle without an error", async () => {
    renderForm();
    await selectBoth();
    await user.click(submitButton());
    const xhr = lastXhr();

    await act(async () => abortAllRequests());

    expect(xhr.aborted).toBe(true);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(submitButton()).toHaveTextContent("提交制作");
  });

  it("ignores the outcome of a 401 once the global handler has switched sessions", async () => {
    const unregister = setUnauthorizedHandler(() => {
      bumpSessionEpoch();
    });
    try {
      renderForm();
      await selectBoth();
      await user.click(submitButton());
      await lastXhr().respond(401, { code: "UNAUTHENTICATED", message: "请先登录" });

      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
      expect(onAccepted).not.toHaveBeenCalled();
    } finally {
      unregister();
    }
  });
});

describe("useSubmitTask", () => {
  function Harness({ files }: { files: File[] | null }) {
    const { state, submit } = useSubmitTask("demo-postcard");
    return (
      <>
        <button
          type="button"
          onClick={() => {
            // 同一事件内连续调用两次（绕过按钮禁用），在途判断由 ref 保证只发一次
            results.push(submit(files), submit(files));
          }}
        >
          submit twice
        </button>
        <output>{state.status}</output>
      </>
    );
  }
  let results: boolean[] = [];

  it("sends only one request when submit is called twice in the same tick", async () => {
    results = [];
    render(<Harness files={[image("a.png"), image("b.png")]} />);
    await user.click(screen.getByRole("button", { name: "submit twice" }));

    expect(results).toEqual([true, false]);
    expect(FakeXhr.requests).toHaveLength(1);
    expect(screen.getByRole("status")).toHaveTextContent("uploading");

    // 结束后可以再次提交
    await lastXhr().respond(202, makeTask({ taskId: "1" }));
    await user.click(screen.getByRole("button", { name: "submit twice" }));
    expect(FakeXhr.requests).toHaveLength(2);
  });

  it("ignores submit while files are not all selected", async () => {
    results = [];
    render(<Harness files={null} />);
    await user.click(screen.getByRole("button", { name: "submit twice" }));

    expect(results).toEqual([false, false]);
    expect(FakeXhr.requests).toHaveLength(0);
    expect(screen.getByRole("status")).toHaveTextContent("idle");
  });
});

describe("submit state helpers", () => {
  const error = new ApiError({ kind: "network", message: "x" });
  const states: [SubmitState, boolean, boolean][] = [
    [{ status: "idle" }, false, true],
    [{ status: "uploading", progress: 0.5 }, true, false],
    [{ status: "submitting" }, true, false],
    [{ status: "error", error }, false, true],
  ];

  it.each(states)("%o → busy=%s, canSubmit(allSelected)=%s", (state, busy, submittable) => {
    expect(isSubmitBusy(state)).toBe(busy);
    expect(canSubmit(true, state)).toBe(submittable);
    expect(canSubmit(false, state)).toBe(false);
  });

  it("labels network failures as upload failures", () => {
    expect(submitErrorMessage(error)).toContain("上传失败");
  });
});
