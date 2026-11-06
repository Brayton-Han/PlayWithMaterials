import { act } from "@testing-library/react";
import { vi } from "vitest";

type Handler = ((event: ProgressEvent) => void) | null;

function progressEvent(type: string, loaded = 0, total = 0, lengthComputable = total > 0): ProgressEvent {
  return { type, loaded, total, lengthComputable } as ProgressEvent;
}

/**
 * 可由测试逐步驱动的 XMLHttpRequest（MSW 不产生 upload 进度事件）。
 * 用 {@link installFakeXhr} 安装，`requests` 记录每个实例；测试结束时 `vi.unstubAllGlobals()` 恢复。
 */
export class FakeXhr {
  static requests: FakeXhr[] = [];

  method = "";
  url = "";
  headers: Record<string, string> = {};
  body: unknown = undefined;
  status = 0;
  responseText = "";
  aborted = false;
  sent = false;

  upload: { onprogress: Handler; onload: Handler } = { onprogress: null, onload: null };
  onload: Handler = null;
  onerror: Handler = null;
  ontimeout: Handler = null;
  onabort: Handler = null;

  constructor() {
    FakeXhr.requests.push(this);
  }

  open(method: string, url: string) {
    this.method = method;
    this.url = url;
  }

  setRequestHeader(name: string, value: string) {
    this.headers[name] = value;
  }

  send(body: unknown) {
    this.sent = true;
    this.body = body;
  }

  abort() {
    if (this.aborted) return;
    this.aborted = true;
    this.onabort?.(progressEvent("abort"));
  }

  /** 请求体发送进度。 */
  progress(loaded: number, total: number, lengthComputable = true) {
    act(() => this.upload.onprogress?.(progressEvent("progress", loaded, total, lengthComputable)));
  }

  /** 请求体已全部发出。 */
  finishUpload() {
    act(() => this.upload.onload?.(progressEvent("load")));
  }

  /** 服务端响应；body 为对象时序列化为 JSON。 */
  async respond(status: number, body?: unknown) {
    this.status = status;
    this.responseText = body === undefined ? "" : typeof body === "string" ? body : JSON.stringify(body);
    await act(async () => this.onload?.(progressEvent("load")));
  }

  /** 网络失败（断网、连接被重置），没有 HTTP 响应。 */
  async failNetwork() {
    await act(async () => this.onerror?.(progressEvent("error")));
  }

  /** 表单中的文件（files 字段）与模板 ID。 */
  get form(): { templateId: string | null; files: File[] } {
    const form = this.body as FormData;
    return {
      templateId: form.get("templateId") as string | null,
      files: form.getAll("files") as File[],
    };
  }
}

export function installFakeXhr(): typeof FakeXhr {
  FakeXhr.requests = [];
  vi.stubGlobal("XMLHttpRequest", FakeXhr);
  return FakeXhr;
}

export function lastXhr(): FakeXhr {
  const xhr = FakeXhr.requests.at(-1);
  if (!xhr) throw new Error("no XMLHttpRequest was created");
  return xhr;
}
