import { http, HttpResponse } from "msw";

import type { Task } from "@/lib/api/endpoints/tasks";

import { server } from "./server";

export type HistoryReply = Task[] | "500" | "network" | Promise<Task[] | "500" | "network">;

/**
 * GET /api/tasks（listTasks）的 MSW handler。`reply(templateId, call)` 决定响应：
 * 任务数组 → 200 `{ items }`；"500" → INTERNAL_ERROR；"network" → 网络错误；也可以返回 Promise 以控制时序。
 * 返回调用记录（每次请求的 templateId 查询参数，未传时为 null）。默认回空列表。
 */
export function mockTaskHistory(reply: (templateId: string | null, call: number) => HistoryReply = () => []) {
  const calls: (string | null)[] = [];
  server.use(
    http.get("/api/tasks", async ({ request }) => {
      const templateId = new URL(request.url).searchParams.get("templateId");
      const call = calls.length;
      calls.push(templateId);
      const r = await reply(templateId, call);
      if (r === "network") return HttpResponse.error();
      if (r === "500") return HttpResponse.json({ code: "INTERNAL_ERROR", message: "服务器内部错误" }, { status: 500 });
      return HttpResponse.json({ items: r });
    }),
  );
  return calls;
}
