/**
 * 带上传进度的 multipart 请求（F-03 规则 5，architecture §6）。
 *
 * `fetch` 拿不到上传进度，这里用 XMLHttpRequest：
 * - `onProgress(fraction)`：请求体发送进度，0 ~ 1；浏览器无法计算总长时为 null；
 * - `onUploaded()`：请求体已全部发出、等待服务端响应（调用方据此从"上传中"切换到"提交中"）；
 * - 错误与 {@link apiRequest} 一致：拿不到响应 → `ApiError(kind='network')`，4xx / 5xx → `ApiError(kind='http')`，
 *   401 先通知全局会话失效处理器；
 * - 取消：调用方的 `signal` 或会话级 signal（{@link abortAllRequests}，退出登录时）任一触发都会 `xhr.abort()`，
 *   并以 `AbortError` 拒绝（用 {@link isAbortError} 识别后忽略）。
 */
import {
  ApiError,
  getSessionSignal,
  httpErrorFromBody,
  NETWORK_ERROR_MESSAGE,
  notifyUnauthorized,
  resolveUrl,
} from "./client";

export interface UploadOptions {
  signal?: AbortSignal;
  onProgress?: (fraction: number | null) => void;
  onUploaded?: () => void;
}

function abortError(): DOMException {
  return new DOMException("The upload was aborted.", "AbortError");
}

export function uploadMultipart<T>(path: string, body: FormData, options: UploadOptions = {}): Promise<T> {
  const { signal, onProgress, onUploaded } = options;
  const session = getSessionSignal();
  const combined = signal ? AbortSignal.any([signal, session]) : session;

  return new Promise<T>((resolve, reject) => {
    if (combined.aborted) {
      reject(abortError());
      return;
    }

    const xhr = new XMLHttpRequest();
    let settled = false;
    const finish = (action: () => void) => {
      if (settled) return;
      settled = true;
      combined.removeEventListener("abort", onAbort);
      action();
    };
    const onAbort = () => {
      xhr.abort();
      finish(() => reject(abortError()));
    };

    xhr.open("POST", resolveUrl(path));
    xhr.setRequestHeader("Accept", "application/json");
    // 同源请求默认携带会话 Cookie；Content-Type（含 boundary）由浏览器根据 FormData 生成

    xhr.upload.onprogress = (event) => {
      if (settled) return;
      onProgress?.(event.lengthComputable && event.total > 0 ? Math.min(event.loaded / event.total, 1) : null);
    };
    xhr.upload.onload = () => {
      if (!settled) onUploaded?.();
    };

    xhr.onload = () => {
      const { status } = xhr;
      const text = typeof xhr.responseText === "string" ? xhr.responseText : "";
      if (status >= 200 && status < 300) {
        let data: T;
        try {
          data = (text ? JSON.parse(text) : undefined) as T;
        } catch (cause) {
          finish(() => reject(new ApiError({ kind: "http", status, message: "响应格式错误", cause })));
          return;
        }
        finish(() => resolve(data));
        return;
      }
      if (status === 0) {
        finish(() => reject(new ApiError({ kind: "network", message: NETWORK_ERROR_MESSAGE })));
        return;
      }
      const error = httpErrorFromBody(status, text);
      finish(() => {
        if (status === 401) notifyUnauthorized(path);
        reject(error);
      });
    };
    const onNetworkError = () =>
      finish(() => reject(new ApiError({ kind: "network", message: NETWORK_ERROR_MESSAGE })));
    xhr.onerror = onNetworkError;
    xhr.ontimeout = onNetworkError;
    xhr.onabort = () => finish(() => reject(abortError()));

    combined.addEventListener("abort", onAbort);
    xhr.send(body);
  });
}
