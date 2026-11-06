import { useCallback, useEffect, useRef, type RefObject } from "react";

/** 字段校验错误优先：容器内第一个 aria-invalid 的控件。 */
const INVALID_FIELD = '[aria-invalid="true"]';

/**
 * 异步操作结束后把键盘焦点留在原处附近（F-19 "保留键盘焦点"）。
 *
 * 提交 / 删除 / 重查期间按钮和输入框会被 disabled（或随后被卸载），浏览器随即把焦点丢回 `<body>`，
 * 键盘用户只能从页首重新 Tab。用法：
 * 1. 在触发操作的事件处理函数开头调用返回的 `arm()`：记下"操作开始时焦点在容器内"；
 * 2. `busy` 由 true 变为 false 时，如果已 arm 且焦点"搁浅"（在 body / 容器本身 / 已禁用或已卸载的元素上），
 *    依次尝试：容器内第一个 `aria-invalid` 的字段 → `candidates` 中第一个可用元素 → 容器本身（需带 tabIndex）。
 *
 * 只在用户从容器内发起操作时生效，焦点已被用户移到别处时不抢焦点；后台轮询等非用户操作不会触发。
 */
export function useKeepFocus(
  containerRef: RefObject<HTMLElement | null>,
  busy: boolean,
  candidates: readonly string[],
): () => void {
  const armed = useRef(false);
  const wasBusy = useRef(busy);

  useEffect(() => {
    const ended = wasBusy.current && !busy;
    wasBusy.current = busy;
    if (!ended || !armed.current) return;
    armed.current = false;
    const container = containerRef.current;
    if (container) restoreFocus(container, candidates);
  }, [busy, containerRef, candidates]);

  return useCallback(() => {
    const container = containerRef.current;
    armed.current = !!container && container.contains(document.activeElement);
  }, [containerRef]);
}

function isStranded(container: HTMLElement): boolean {
  const el = document.activeElement;
  return (
    !el ||
    el === document.body ||
    el === container ||
    !el.isConnected ||
    (el instanceof HTMLElement && el.matches(":disabled"))
  );
}

function restoreFocus(container: HTMLElement, candidates: readonly string[]) {
  const invalid = firstEnabled(container, INVALID_FIELD);
  if (invalid) {
    invalid.focus();
    return;
  }
  if (!isStranded(container)) return;
  for (const selector of candidates) {
    const target = firstEnabled(container, selector);
    if (target) {
      target.focus();
      return;
    }
  }
  if (container.hasAttribute("tabindex")) container.focus();
}

function firstEnabled(container: HTMLElement, selector: string): HTMLElement | null {
  for (const el of container.querySelectorAll<HTMLElement>(selector)) {
    if (!el.matches(":disabled")) return el;
  }
  return null;
}
