import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useRef, useState } from "react";
import { describe, expect, it } from "vitest";

import { simulateFocusFixup } from "@/test/focus";

import { useKeepFocus } from "./useKeepFocus";

const CANDIDATES = ['button[type="submit"]', "input"];

/** 最小表单：提交后进入 busy（按钮禁用），由测试调用 finish() 结束，可选择留下字段错误。 */
function Harness({ arm = true }: { arm?: boolean }) {
  const formRef = useRef<HTMLFormElement>(null);
  const [busy, setBusy] = useState(false);
  const [invalid, setInvalid] = useState(false);
  const armFocus = useKeepFocus(formRef, busy, CANDIDATES);
  return (
    <>
      <form
        ref={formRef}
        aria-label="表单"
        onSubmit={(event) => {
          event.preventDefault();
          if (arm) armFocus();
          setBusy(true);
        }}
      >
        <input aria-label="字段 A" />
        <input aria-label="字段 B" aria-invalid={invalid || undefined} />
        <button type="submit" disabled={busy}>
          提交
        </button>
      </form>
      <button type="button" onClick={() => setBusy(false)}>
        结束（成功）
      </button>
      <button
        type="button"
        onClick={() => {
          setInvalid(true);
          setBusy(false);
        }}
      >
        结束（字段错误）
      </button>
      <button type="button">表单外</button>
    </>
  );
}

const submit = () => screen.getByRole("button", { name: "提交" });

async function startBusy(user: ReturnType<typeof userEvent.setup>) {
  await user.click(submit());
  expect(submit()).toBeDisabled();
  simulateFocusFixup();
  expect(document.body).toHaveFocus();
}

/** 用 act 直接点击结束按钮，不移动焦点（模拟请求在后台完成）。 */
function finish(name: string) {
  act(() => screen.getByRole("button", { name }).click());
}

describe("useKeepFocus", () => {
  it("returns stranded focus to the first available candidate when the action ends", async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await startBusy(user);
    finish("结束（成功）");

    expect(submit()).toHaveFocus();
  });

  it("prefers the first field marked aria-invalid", async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await startBusy(user);
    finish("结束（字段错误）");

    expect(screen.getByLabelText("字段 B")).toHaveFocus();
  });

  it("does not steal focus the user has moved elsewhere during the action", async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await startBusy(user);
    screen.getByRole("button", { name: "表单外" }).focus();
    finish("结束（成功）");

    expect(screen.getByRole("button", { name: "表单外" })).toHaveFocus();
  });

  it("does nothing unless the action was started from inside the container (armed)", async () => {
    const user = userEvent.setup();
    render(<Harness arm={false} />);

    await startBusy(user);
    finish("结束（成功）");

    expect(document.body).toHaveFocus();
  });
});
