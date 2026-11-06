/**
 * jsdom 不实现浏览器的 focus fixup：获得焦点的元素被禁用后，jsdom 仍把焦点留在它身上，
 * 而真实浏览器会把焦点丢回 `<body>`。在"按钮已禁用"的时刻调用它来复现浏览器行为（F-19 焦点测试）。
 */
export function simulateFocusFixup() {
  const el = document.activeElement;
  if (!(el instanceof HTMLElement) || !el.matches(":disabled")) return;
  // jsdom 对已禁用元素的 blur() 是空操作：先把焦点移到临时元素再移除它，焦点随之回到 body
  const sink = document.createElement("button");
  document.body.appendChild(sink);
  sink.focus();
  sink.remove();
}

/** 手动放行的 Promise：让请求停在"进行中"，便于断言中间状态。 */
export function deferred<T = void>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}
