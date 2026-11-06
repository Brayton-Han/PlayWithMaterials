import { act, fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useEffect } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { UploadSlots } from "./UploadSlots";
import { useSlots, validateFile, type SlotRules, type UseSlotsResult } from "./useSlots";

const MB = 1024 * 1024;
const RULES: SlotRules = { acceptedMimeTypes: ["image/jpeg", "image/png"], maxFileSizeBytes: 1 * MB };

function image(name: string, type = "image/png", size = 1024): File {
  const file = new File(["x"], name, { type });
  Object.defineProperty(file, "size", { value: size });
  return file;
}

let created: string[] = [];
let revoked: string[] = [];
let api: UseSlotsResult;

function Harness({ count = 2, locked = false, rules = RULES }: { count?: number; locked?: boolean; rules?: SlotRules }) {
  const slots = useSlots(count, rules, locked);
  // 测试通过 api 读取 hook 状态、调用 applyServerErrors（T-032 的调用方式）
  useEffect(() => {
    api = slots;
  });
  return (
    <UploadSlots slots={slots.slots} rules={rules} locked={locked} onSelect={slots.select} onRemove={slots.remove} />
  );
}

/** 模拟用户在系统对话框里选"所有文件"，绕过 accept 过滤，让前端校验来拦截。 */
function setupUser() {
  return userEvent.setup({ applyAccept: false });
}

const input = (n: number) => screen.getByLabelText(`选择第 ${n} 张图片`);
const slotItem = (n: number) => screen.getAllByRole("listitem")[n - 1];

beforeEach(() => {
  created = [];
  revoked = [];
  let seq = 0;
  URL.createObjectURL = vi.fn(() => {
    const url = `blob:http://localhost:3000/${++seq}`;
    created.push(url);
    return url;
  });
  URL.revokeObjectURL = vi.fn((url: string) => {
    revoked.push(url);
  });
});

describe("UploadSlots", () => {
  it.each([1, 2, 3])("renders %i empty slot(s) with accessible inputs", (count) => {
    render(<Harness count={count} />);

    const list = screen.getByRole("list", { name: "上传位" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(count);
    for (let n = 1; n <= count; n++) {
      expect(input(n)).toHaveAttribute("type", "file");
      expect(input(n)).toHaveAttribute("accept", "image/jpeg,image/png");
      expect(input(n)).toBeEnabled();
    }
    expect(screen.queryByRole("button", { name: /移除/ })).not.toBeInTheDocument();
    expect(api.allSelected).toBe(false);
    expect(api.files).toBeNull();
  });

  it("shows a local blob preview after selecting a valid file, without any network request", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    const xhrSpy = vi.spyOn(XMLHttpRequest.prototype, "open");
    const user = setupUser();
    render(<Harness count={2} />);

    await user.upload(input(1), image("cat.png"));

    const preview = screen.getByRole("img", { name: "第 1 张图片预览" });
    expect(preview).toHaveAttribute("src", created[0]);
    expect(created[0]).toMatch(/^blob:/);
    expect(within(slotItem(1)).getByText("cat.png")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "移除第 1 张图片" })).toBeInTheDocument();
    expect(screen.queryByRole("img", { name: "第 2 张图片预览" })).not.toBeInTheDocument();
    expect(api.slots.map((s) => s.status)).toEqual(["selected", "empty"]);
    expect(api.allSelected).toBe(false);

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(xhrSpy).not.toHaveBeenCalled();
  });

  it("reports allSelected and files in slot order once every slot has a file", async () => {
    const user = setupUser();
    render(<Harness count={2} />);
    const first = image("a.jpg", "image/jpeg");
    const second = image("b.png");

    await user.upload(input(2), second);
    await user.upload(input(1), first);

    expect(api.allSelected).toBe(true);
    expect(api.files).toEqual([first, second]);
  });

  it.each([
    ["an unaccepted type", image("anim.gif", "image/gif"), "第 1 张图片格式不支持，仅支持 JPG / PNG"],
    ["a file with no type", image("noext", ""), "第 1 张图片格式不支持，仅支持 JPG / PNG"],
    ["an oversized file", image("big.png", "image/png", 1 * MB + 1), "第 1 张图片超过单张上限 1 MB"],
    ["an empty file", image("empty.png", "image/png", 0), "第 1 张图片是空文件，请重新选择"],
  ])("rejects %s: it does not enter the slot and the reason is shown", async (_, file, reason) => {
    const user = setupUser();
    render(<Harness count={1} />);

    await user.upload(input(1), file);

    expect(within(slotItem(1)).getByRole("alert")).toHaveTextContent(reason);
    expect(input(1)).toHaveAttribute("aria-invalid", "true");
    expect(input(1)).toHaveAccessibleDescription(reason);
    expect(screen.queryByRole("img", { name: "第 1 张图片预览" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "移除第 1 张图片" })).not.toBeInTheDocument();
    expect(api.slots[0]).toEqual({ status: "invalid", reason });
    expect(api.files).toBeNull();
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });

  it("accepts a file exactly at the size limit and clears a previous rejection", async () => {
    const user = setupUser();
    render(<Harness count={1} />);

    await user.upload(input(1), image("big.png", "image/png", 2 * MB));
    expect(screen.getByRole("alert")).toBeInTheDocument();

    await user.upload(input(1), image("edge.png", "image/png", 1 * MB));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByRole("img", { name: "第 1 张图片预览" })).toBeInTheDocument();
    expect(api.allSelected).toBe(true);
  });

  it("replaces the file and preview, revoking the old blob URL", async () => {
    const user = setupUser();
    render(<Harness count={1} />);
    const replacement = image("new.jpg", "image/jpeg");

    await user.upload(input(1), image("old.png"));
    const [oldUrl] = created;
    await user.upload(input(1), replacement);

    expect(revoked).toEqual([oldUrl]);
    expect(screen.getByRole("img", { name: "第 1 张图片预览" })).toHaveAttribute("src", created[1]);
    expect(within(slotItem(1)).getByText("new.jpg")).toBeInTheDocument();
    expect(within(slotItem(1)).queryByText("old.png")).not.toBeInTheDocument();
    expect(api.files).toEqual([replacement]);
  });

  it("keeps the current file when an invalid replacement is chosen, and shows why", async () => {
    const user = setupUser();
    render(<Harness count={1} />);
    const good = image("good.png");

    await user.upload(input(1), good);
    await user.upload(input(1), image("bad.gif", "image/gif"));

    expect(screen.getByRole("alert")).toHaveTextContent("第 1 张图片格式不支持");
    expect(screen.getByRole("img", { name: "第 1 张图片预览" })).toHaveAttribute("src", created[0]);
    expect(revoked).toEqual([]);
    expect(api.files).toEqual([good]);

    // 再选一个合法文件，提示消失
    await user.upload(input(1), image("better.png"));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("removes a file, revoking its blob URL and leaving other slots untouched", async () => {
    const user = setupUser();
    render(<Harness count={2} />);

    await user.upload(input(1), image("a.png"));
    await user.upload(input(2), image("b.png"));
    await user.click(screen.getByRole("button", { name: "移除第 1 张图片" }));

    expect(revoked).toEqual([created[0]]);
    expect(screen.queryByRole("img", { name: "第 1 张图片预览" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "移除第 1 张图片" })).not.toBeInTheDocument();
    expect(screen.getByRole("img", { name: "第 2 张图片预览" })).toHaveAttribute("src", created[1]);
    expect(api.slots.map((s) => s.status)).toEqual(["empty", "selected"]);
    expect(api.allSelected).toBe(false);

    // 移除后可以重新选择
    await user.upload(input(1), image("c.png"));
    expect(api.allSelected).toBe(true);
  });

  it("can re-select the same file after removing it", async () => {
    const user = setupUser();
    render(<Harness count={1} />);
    const file = image("same.png");

    await user.upload(input(1), file);
    await user.click(screen.getByRole("button", { name: "移除第 1 张图片" }));
    await user.upload(input(1), file);

    expect(api.files).toEqual([file]);
  });

  it("accepts a dropped file and validates it like a picked one", () => {
    render(<Harness count={2} />);
    const zone = (n: number) => within(slotItem(n)).getByText(/点击或拖入图片/).closest("label")!;

    fireEvent.drop(zone(1), { dataTransfer: { files: [image("drop.png")] } });
    fireEvent.drop(zone(2), { dataTransfer: { files: [image("drop.gif", "image/gif")] } });

    expect(screen.getByRole("img", { name: "第 1 张图片预览" })).toBeInTheDocument();
    expect(within(slotItem(2)).getByRole("alert")).toHaveTextContent("第 2 张图片格式不支持");
    expect(api.slots.map((s) => s.status)).toEqual(["selected", "invalid"]);
  });

  describe("while locked", () => {
    it("disables inputs and remove buttons, and ignores select / remove / drop", async () => {
      const user = setupUser();
      const { rerender } = render(<Harness count={2} />);
      const kept = image("kept.png");
      await user.upload(input(1), kept);

      rerender(<Harness count={2} locked />);

      expect(input(1)).toBeDisabled();
      expect(input(2)).toBeDisabled();
      expect(screen.getByRole("button", { name: "移除第 1 张图片" })).toBeDisabled();

      await user.upload(input(1), image("other.png"));
      await user.upload(input(2), image("other.png"));
      await user.click(screen.getByRole("button", { name: "移除第 1 张图片" }));
      fireEvent.drop(within(slotItem(2)).getByText(/点击或拖入图片/).closest("label")!, {
        dataTransfer: { files: [image("drop.png")] },
      });
      // 直接调用 hook 也不生效（防止界面以外的路径改动正在发送的文件）
      act(() => {
        api.select(1, image("direct.png"));
        api.remove(0);
      });

      expect(api.slots).toEqual([{ status: "selected", file: kept, previewUrl: created[0] }, { status: "empty" }]);
      expect(created).toHaveLength(1);
      expect(revoked).toEqual([]);

      // 解锁后恢复可操作
      rerender(<Harness count={2} />);
      expect(input(1)).toBeEnabled();
      await user.click(screen.getByRole("button", { name: "移除第 1 张图片" }));
      expect(api.slots[0]).toEqual({ status: "empty" });
    });
  });

  describe("server slot errors", () => {
    it("marks the slot from fieldErrors[].slotIndex as invalid and keeps its file and preview", async () => {
      const user = setupUser();
      render(<Harness count={2} />);
      const first = image("a.png");
      const second = image("b.png");
      await user.upload(input(1), first);
      await user.upload(input(2), second);

      act(() =>
        api.applyServerErrors([
          { field: "files", slotIndex: 1, code: "FILE_TYPE_NOT_ACCEPTED", message: "第 2 张图片格式不支持，仅支持 JPEG、PNG" },
          { field: "files", slotIndex: 7, code: "FILE_EMPTY", message: "越界的上传位被忽略" },
          { field: "templateId", code: "REQUIRED", message: "没有 slotIndex 的错误被忽略" },
        ]),
      );

      expect(within(slotItem(2)).getByRole("alert")).toHaveTextContent("第 2 张图片格式不支持，仅支持 JPEG、PNG");
      expect(input(2)).toHaveAttribute("aria-invalid", "true");
      expect(within(slotItem(1)).queryByRole("alert")).not.toBeInTheDocument();
      expect(screen.queryByText("越界的上传位被忽略")).not.toBeInTheDocument();
      // 文件与预览保留，但不能提交，直到替换或移除
      expect(screen.getByRole("img", { name: "第 2 张图片预览" })).toHaveAttribute("src", created[1]);
      expect(api.slots[1]).toMatchObject({ status: "invalid", file: second });
      expect(api.allSelected).toBe(false);
      expect(api.files).toBeNull();
      expect(revoked).toEqual([]);

      await user.upload(input(2), image("fixed.png"));
      expect(revoked).toEqual([created[1]]);
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
      expect(api.allSelected).toBe(true);
    });

    it("clears a server error when the slot is removed", async () => {
      const user = setupUser();
      render(<Harness count={1} />);
      await user.upload(input(1), image("a.png"));
      act(() => api.applyServerErrors([{ field: "files", slotIndex: 0, code: "FILE_TOO_LARGE", message: "太大了" }]));

      await user.click(screen.getByRole("button", { name: "移除第 1 张图片" }));

      expect(revoked).toEqual([created[0]]);
      expect(api.slots[0]).toEqual({ status: "empty" });
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    });
  });

  it("revokes every remaining blob URL on unmount", async () => {
    const user = setupUser();
    const { unmount } = render(<Harness count={3} />);
    await user.upload(input(1), image("a.png"));
    await user.upload(input(3), image("c.png"));

    unmount();

    expect(revoked.sort()).toEqual([...created].sort());
  });
});

describe("validateFile", () => {
  it("returns null for an accepted type within the limit", () => {
    expect(validateFile(image("ok.jpg", "image/jpeg", MB), 0, RULES)).toBeNull();
  });

  it("uses the template's own types and limit in the message", () => {
    const pngOnly: SlotRules = { acceptedMimeTypes: ["image/png"], maxFileSizeBytes: 500 * 1024 };
    expect(validateFile(image("a.jpg", "image/jpeg"), 2, pngOnly)).toBe("第 3 张图片格式不支持，仅支持 PNG");
    expect(validateFile(image("a.png", "image/png", 600 * 1024), 0, pngOnly)).toBe("第 1 张图片超过单张上限 500 KB");
  });
});
