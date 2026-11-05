import { describe, expect, it, vi } from "vitest";

import {
  authPageHref,
  bumpSessionEpoch,
  getSessionEpoch,
  isCurrentEpoch,
  onUserScopeReset,
  resetUserScopedState,
  safeNextPath,
} from "./session";

describe("safeNextPath", () => {
  it.each([
    ["/records", "/records"],
    ["/templates/a?x=1#top", "/templates/a?x=1#top"],
    ["/account", "/account"],
  ])("keeps the in-site path %s", (raw, expected) => {
    expect(safeNextPath(raw)).toBe(expected);
  });

  it.each([
    [null],
    [""],
    ["records"],
    ["//evil.example.com/x"],
    ["/\\evil.example.com"],
    ["https://evil.example.com/"],
    ["javascript:alert(1)"],
    ["/login"],
    ["/register?next=/records"],
  ])("falls back to / for %s", (raw) => {
    expect(safeNextPath(raw)).toBe("/");
  });
});

describe("authPageHref", () => {
  it("encodes the next path and omits it for /", () => {
    expect(authPageHref("/login", "/records?tab=1")).toBe("/login?next=%2Frecords%3Ftab%3D1");
    expect(authPageHref("/register", "/")).toBe("/register");
    expect(authPageHref("/login", "//evil.example.com")).toBe("/login");
  });
});

describe("session epoch and user-scope reset", () => {
  it("bumps the epoch so earlier epochs are stale", () => {
    const before = getSessionEpoch();
    expect(bumpSessionEpoch()).toBe(before + 1);
    expect(isCurrentEpoch(before)).toBe(false);
    expect(isCurrentEpoch(before + 1)).toBe(true);
  });

  it("runs every registered reset, survives a failing one, and supports unregistering", () => {
    const failing = vi.fn(() => {
      throw new Error("boom");
    });
    const ok = vi.fn();
    const removed = vi.fn();
    vi.spyOn(console, "error").mockImplementation(() => {});
    const unregisterFailing = onUserScopeReset(failing);
    const unregisterOk = onUserScopeReset(ok);
    onUserScopeReset(removed)();

    resetUserScopedState();
    expect(failing).toHaveBeenCalledTimes(1);
    expect(ok).toHaveBeenCalledTimes(1);
    expect(removed).not.toHaveBeenCalled();
    unregisterFailing();
    unregisterOk();
  });
});
