import { http, HttpResponse, delay } from "msw";
import { describe, expect, it } from "vitest";

import { server } from "@/test/msw/server";

import {
  ApiError,
  GENERIC_HTTP_ERROR_MESSAGE,
  NETWORK_ERROR_MESSAGE,
  apiRequest,
  isAbortError,
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
