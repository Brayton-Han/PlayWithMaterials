import { http, HttpResponse, delay } from "msw";
import { describe, expect, it, vi } from "vitest";

import { server } from "@/test/msw/server";

import {
  ApiError,
  GENERIC_HTTP_ERROR_MESSAGE,
  NETWORK_ERROR_MESSAGE,
  abortAllRequests,
  apiRequest,
  isAbortError,
  setUnauthorizedHandler,
} from "./client";

async function catchError(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise;
  } catch (error) {
    return error;
  }
  throw new Error("expected promise to reject");
}

describe("apiRequest", () => {
  it("returns parsed JSON on success and sends same-origin credentials", async () => {
    let credentials: RequestCredentials | undefined;
    server.use(
      http.get("/api/ping", ({ request }) => {
        credentials = request.credentials;
        return HttpResponse.json({ ok: true });
      }),
    );

    await expect(apiRequest<{ ok: boolean }>("/api/ping")).resolves.toEqual({ ok: true });
    expect(credentials).toBe("same-origin");
  });

  it("returns undefined for 204", async () => {
    server.use(http.post("/api/auth/logout", () => new HttpResponse(null, { status: 204 })));
    await expect(apiRequest("/api/auth/logout", { method: "POST" })).resolves.toBeUndefined();
  });

  it("sends plain objects as JSON", async () => {
    let received: unknown;
    let contentType: string | null = null;
    server.use(
      http.post("/api/echo", async ({ request }) => {
        contentType = request.headers.get("content-type");
        received = await request.json();
        return HttpResponse.json({});
      }),
    );

    await apiRequest("/api/echo", { method: "POST", body: { username: "demo1" } });
    expect(contentType).toBe("application/json");
    expect(received).toEqual({ username: "demo1" });
  });

  it("maps network failures to kind 'network'", async () => {
    server.use(http.get("/api/ping", () => HttpResponse.error()));

    const error = await catchError(apiRequest("/api/ping"));
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ kind: "network", message: NETWORK_ERROR_MESSAGE, status: undefined });
  });

  it("maps 4xx JSON error bodies to kind 'http' with code, message and details", async () => {
    server.use(
      http.get("/api/tasks/1", () =>
        HttpResponse.json(
          {
            code: "VALIDATION_FAILED",
            message: "请求参数不合法",
            details: { fieldErrors: [{ field: "taskId", code: "FORMAT", message: "格式不正确" }] },
          },
          { status: 400 },
        ),
      ),
    );

    const error = await catchError(apiRequest("/api/tasks/1"));
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      kind: "http",
      status: 400,
      code: "VALIDATION_FAILED",
      message: "请求参数不合法",
      details: { fieldErrors: [{ field: "taskId", code: "FORMAT", message: "格式不正确" }] },
    });
  });

  it("maps 5xx JSON error bodies to kind 'http' with code", async () => {
    server.use(
      http.post("/api/tasks", () =>
        HttpResponse.json({ code: "QUEUE_FULL", message: "当前制作任务较多，请稍后再试" }, { status: 503 }),
      ),
    );

    const error = await catchError(apiRequest("/api/tasks", { method: "POST", body: {} }));
    expect(error).toMatchObject({ kind: "http", status: 503, code: "QUEUE_FULL" });
  });

  it("still produces an ApiError for non-JSON 5xx bodies", async () => {
    server.use(
      http.get("/api/ping", () =>
        new HttpResponse("<html>502 Bad Gateway</html>", {
          status: 502,
          headers: { "Content-Type": "text/html" },
        }),
      ),
    );

    const error = await catchError(apiRequest("/api/ping"));
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      kind: "http",
      status: 502,
      code: undefined,
      message: GENERIC_HTTP_ERROR_MESSAGE,
    });
  });

  it("does not turn an abort into an ApiError", async () => {
    server.use(
      http.get("/api/slow", async () => {
        await delay("infinite");
        return HttpResponse.json({});
      }),
    );

    const controller = new AbortController();
    const pending = catchError(apiRequest("/api/slow", { signal: controller.signal }));
    controller.abort();

    const error = await pending;
    expect(error).not.toBeInstanceOf(ApiError);
    expect(isAbortError(error)).toBe(true);
  });
});

describe("abortAllRequests", () => {
  it("aborts every in-flight request but not requests started afterwards", async () => {
    server.use(
      http.get("/api/slow", async () => {
        await delay("infinite");
        return HttpResponse.json({});
      }),
      http.get("/api/ping", () => HttpResponse.json({ ok: true })),
    );

    const first = catchError(apiRequest("/api/slow"));
    const second = catchError(apiRequest("/api/slow", { signal: new AbortController().signal }));
    abortAllRequests();

    expect(isAbortError(await first)).toBe(true);
    expect(isAbortError(await second)).toBe(true);
    await expect(apiRequest("/api/ping")).resolves.toEqual({ ok: true });
  });
});

describe("global 401 handling", () => {
  const unauthenticated = () =>
    HttpResponse.json({ code: "UNAUTHENTICATED", message: "请先登录" }, { status: 401 });

  it("notifies the handler for a 401 from a business endpoint and still rejects", async () => {
    const handler = vi.fn();
    const unregister = setUnauthorizedHandler(handler);
    server.use(http.get("/api/tasks", unauthenticated));

    const error = await catchError(apiRequest("/api/tasks?templateId=a"));
    expect(error).toMatchObject({ kind: "http", status: 401, code: "UNAUTHENTICATED" });
    expect(handler).toHaveBeenCalledTimes(1);
    unregister();
  });

  it("does not notify for /api/auth/me or /api/auth/login, nor for other statuses", async () => {
    const handler = vi.fn();
    const unregister = setUnauthorizedHandler(handler);
    server.use(
      http.get("/api/auth/me", unauthenticated),
      http.post("/api/auth/login", () =>
        HttpResponse.json({ code: "INVALID_CREDENTIALS", message: "用户名或密码错误" }, { status: 401 }),
      ),
      http.get("/api/tasks/1", () => HttpResponse.json({ code: "TASK_NOT_FOUND", message: "任务不存在" }, { status: 404 })),
    );

    await catchError(apiRequest("/api/auth/me"));
    await catchError(apiRequest("/api/auth/login", { method: "POST", body: {} }));
    await catchError(apiRequest("/api/tasks/1"));
    expect(handler).not.toHaveBeenCalled();
    unregister();
  });

  it("stops notifying after the handler is unregistered", async () => {
    const handler = vi.fn();
    setUnauthorizedHandler(handler)();
    server.use(http.get("/api/tasks", unauthenticated));

    await catchError(apiRequest("/api/tasks"));
    expect(handler).not.toHaveBeenCalled();
  });
});
