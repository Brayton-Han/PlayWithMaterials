import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { FakeXhr, installFakeXhr, lastXhr } from "@/test/fakeXhr";

import { ApiError, GENERIC_HTTP_ERROR_MESSAGE, NETWORK_ERROR_MESSAGE, abortAllRequests, isAbortError, setUnauthorizedHandler } from "./client";
import { createTask } from "./endpoints/tasks";
import { uploadMultipart } from "./upload";

async function catchError(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise;
  } catch (error) {
    return error;
  }
  throw new Error("expected promise to reject");
}

const form = () => {
  const data = new FormData();
  data.append("templateId", "demo");
  return data;
};

beforeEach(() => {
  installFakeXhr();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("uploadMultipart", () => {
  it("POSTs the FormData same-origin with JSON Accept and resolves the parsed body", async () => {
    const body = form();
    const promise = uploadMultipart<{ ok: boolean }>("/api/tasks", body);
    const xhr = lastXhr();

    expect(xhr.method).toBe("POST");
    expect(xhr.url).toBe(`${window.location.origin}/api/tasks`);
    expect(xhr.headers).toEqual({ Accept: "application/json" });
    // Content-Type（boundary）交给浏览器生成
    expect(xhr.body).toBe(body);

    await xhr.respond(202, { ok: true });
    await expect(promise).resolves.toEqual({ ok: true });
  });

  it("reports upload progress as a fraction, null when not computable, then 'uploaded'", async () => {
    const onProgress = vi.fn();
    const onUploaded = vi.fn();
    const promise = uploadMultipart("/api/tasks", form(), { onProgress, onUploaded });
    const xhr = lastXhr();

    xhr.progress(0, 200);
    xhr.progress(50, 200);
    xhr.progress(10, 0, false);
    xhr.progress(200, 200);
    expect(onProgress.mock.calls).toEqual([[0], [0.25], [null], [1]]);
    expect(onUploaded).not.toHaveBeenCalled();

    xhr.finishUpload();
    expect(onUploaded).toHaveBeenCalledTimes(1);

    await xhr.respond(202, {});
    await promise;
  });

  it("maps a connection failure to ApiError(kind='network')", async () => {
    const promise = catchError(uploadMultipart("/api/tasks", form()));
    await lastXhr().failNetwork();

    const error = await promise;
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ kind: "network", message: NETWORK_ERROR_MESSAGE, status: undefined });
  });

  it("maps 400 with slotIndex field errors to ApiError(kind='http') with details", async () => {
    const promise = catchError(uploadMultipart("/api/tasks", form()));
    const payload = {
      code: "ASSET_VALIDATION_FAILED",
      message: "部分图片不符合要求",
      details: {
        fieldErrors: [{ field: "files", slotIndex: 1, code: "FILE_TYPE_NOT_ACCEPTED", message: "第 2 张图片格式不支持" }],
      },
    };
    await lastXhr().respond(400, payload);

    const error = await promise;
    expect(error).toMatchObject({ kind: "http", status: 400, code: "ASSET_VALIDATION_FAILED", details: payload.details });
  });

  it("maps 413 and non-JSON 5xx bodies", async () => {
    const tooLarge = catchError(uploadMultipart("/api/tasks", form()));
    await lastXhr().respond(413, { code: "PAYLOAD_TOO_LARGE", message: "上传内容过大" });
    expect(await tooLarge).toMatchObject({ kind: "http", status: 413, code: "PAYLOAD_TOO_LARGE", message: "上传内容过大" });

    const badGateway = catchError(uploadMultipart("/api/tasks", form()));
    await lastXhr().respond(502, "<html>Bad Gateway</html>");
    expect(await badGateway).toMatchObject({ kind: "http", status: 502, code: undefined, message: GENERIC_HTTP_ERROR_MESSAGE });
  });

  it("notifies the global handler on 401 and still rejects", async () => {
    const handler = vi.fn();
    const unregister = setUnauthorizedHandler(handler);
    try {
      const promise = catchError(uploadMultipart("/api/tasks", form()));
      await lastXhr().respond(401, { code: "UNAUTHENTICATED", message: "请先登录" });
      expect(await promise).toMatchObject({ status: 401, code: "UNAUTHENTICATED" });
      expect(handler).toHaveBeenCalledTimes(1);
    } finally {
      unregister();
    }
  });

  it("aborts the XHR when the caller's signal aborts, rejecting with AbortError and no further callbacks", async () => {
    const controller = new AbortController();
    const onProgress = vi.fn();
    const promise = catchError(uploadMultipart("/api/tasks", form(), { signal: controller.signal, onProgress }));
    const xhr = lastXhr();

    controller.abort();
    expect(xhr.aborted).toBe(true);
    const error = await promise;
    expect(isAbortError(error)).toBe(true);
    expect(error).not.toBeInstanceOf(ApiError);

    xhr.progress(1, 2);
    expect(onProgress).not.toHaveBeenCalled();
  });

  it("does not send at all when the signal is already aborted", async () => {
    const controller = new AbortController();
    controller.abort();
    const error = await catchError(uploadMultipart("/api/tasks", form(), { signal: controller.signal }));
    expect(isAbortError(error)).toBe(true);
    expect(FakeXhr.requests).toHaveLength(0);
  });

  it("is cancelled by abortAllRequests (logout) but later uploads are not", async () => {
    const first = catchError(uploadMultipart("/api/tasks", form()));
    const firstXhr = lastXhr();
    abortAllRequests();
    expect(firstXhr.aborted).toBe(true);
    expect(isAbortError(await first)).toBe(true);

    const second = uploadMultipart<{ ok: boolean }>("/api/tasks", form());
    const secondXhr = lastXhr();
    expect(secondXhr.aborted).toBe(false);
    await secondXhr.respond(202, { ok: true });
    await expect(second).resolves.toEqual({ ok: true });
  });
});

describe("createTask", () => {
  it("sends templateId and files in slot order under the 'files' field", async () => {
    const a = new File(["a"], "a.png", { type: "image/png" });
    const b = new File(["b"], "b.jpg", { type: "image/jpeg" });
    const promise = createTask("demo-postcard", [a, b]);
    const xhr = lastXhr();

    expect(xhr.url).toBe(`${window.location.origin}/api/tasks`);
    expect(xhr.form.templateId).toBe("demo-postcard");
    expect(xhr.form.files.map((f) => f.name)).toEqual(["a.png", "b.jpg"]);

    await xhr.respond(202, { taskId: "42", status: "PENDING" });
    await expect(promise).resolves.toMatchObject({ taskId: "42", status: "PENDING" });
  });
});
