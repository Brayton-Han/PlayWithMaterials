/**
 * 同源 API client（architecture §6）。
 *
 * - 只请求同源相对路径（/api/**、/actuator/**），由 Next.js rewrites 转发给 Java；
 *   credentials: 'same-origin' 让浏览器携带 HttpOnly 会话 Cookie。
 * - 所有失败统一转换为 {@link ApiError}：
 *   - kind: 'network'：断网、DNS、连接被拒等，请求没有拿到 HTTP 响应；
 *   - kind: 'http'：拿到了 4xx / 5xx 响应；JSON 错误体中的 code / message / details 原样带出，
 *     非 JSON 的错误体（如代理返回的 HTML 502）使用通用文案。
 * - 调用方 abort 时**不**转换为 ApiError，而是原样抛出 AbortError，用 {@link isAbortError} 识别后忽略。
 */
import type { components } from "./schema";

export type ErrorCode = components["schemas"]["ErrorCode"];
export type ErrorResponse = components["schemas"]["ErrorResponse"];
export type ErrorDetails = NonNullable<ErrorResponse["details"]>;

export type ApiErrorKind = "network" | "http";

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  /** HTTP 状态码；network 错误时为 undefined */
  readonly status?: number;
  /** 后端稳定错误码；非 JSON 错误体或 network 错误时为 undefined */
  readonly code?: ErrorCode;
  readonly details?: ErrorDetails;

  constructor(init: {
    kind: ApiErrorKind;
    message: string;
    status?: number;
    code?: ErrorCode;
    details?: ErrorDetails;
    cause?: unknown;
  }) {
    super(init.message, { cause: init.cause });
    this.name = "ApiError";
    this.kind = init.kind;
    this.status = init.status;
    this.code = init.code;
    this.details = init.details;
  }
}

export const NETWORK_ERROR_MESSAGE = "网络连接异常，请检查网络后重试";
export const GENERIC_HTTP_ERROR_MESSAGE = "服务器开小差了，请稍后重试";

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

/** 调用方主动取消（AbortController.abort()）产生的错误，不应作为失败展示或上报。 */
export function isAbortError(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "name" in error &&
    (error as { name?: unknown }).name === "AbortError"
  );
}

export type HttpMethod = "GET" | "POST" | "PUT" | "DELETE";

export interface RequestOptions {
  method?: HttpMethod;
  /** 普通对象会被序列化为 JSON；FormData 原样发送 */
  body?: unknown;
  signal?: AbortSignal;
  headers?: Record<string, string>;
}

/** 浏览器中相对路径按当前页面解析；测试环境（jsdom）同样有 location。 */
function resolveUrl(path: string): string {
  if (typeof window !== "undefined" && window.location?.origin) {
    return new URL(path, window.location.origin).toString();
  }
  return path;
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, signal, headers = {} } = options;

  const init: RequestInit = {
    method,
    signal,
    credentials: "same-origin",
    headers: { Accept: "application/json", ...headers },
  };
  if (body !== undefined) {
    if (body instanceof FormData) {
      init.body = body;
    } else {
      init.body = JSON.stringify(body);
      (init.headers as Record<string, string>)["Content-Type"] = "application/json";
    }
  }

  let response: Response;
  try {
    response = await fetch(resolveUrl(path), init);
  } catch (error) {
    if (isAbortError(error) || signal?.aborted) {
      throw error;
    }
    throw new ApiError({ kind: "network", message: NETWORK_ERROR_MESSAGE, cause: error });
  }

  if (!response.ok) {
    throw await toHttpError(response);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

async function toHttpError(response: Response): Promise<ApiError> {
  let payload: Partial<ErrorResponse> | undefined;
  try {
    const text = await response.text();
    const parsed: unknown = text ? JSON.parse(text) : undefined;
    if (parsed && typeof parsed === "object" && typeof (parsed as ErrorResponse).code === "string") {
      payload = parsed as ErrorResponse;
    }
  } catch {
    // 非 JSON 错误体（HTML、空体等），使用通用文案
  }
  return new ApiError({
    kind: "http",
    status: response.status,
    code: payload?.code,
    message: payload?.message || GENERIC_HTTP_ERROR_MESSAGE,
    details: payload?.details,
  });
}
