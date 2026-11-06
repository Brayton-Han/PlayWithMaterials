import { apiRequest } from "../client";
import type { components } from "../schema";
import { uploadMultipart, type UploadOptions } from "../upload";

/** 任务视图（api.yaml Task）。 */
export type Task = components["schemas"]["Task"];
export type TaskStatus = components["schemas"]["TaskStatus"];
export type TaskStage = components["schemas"]["TaskStage"];
export type CreateTaskForm = components["schemas"]["CreateTaskForm"];
export type TaskList = components["schemas"]["TaskList"];

/**
 * POST /api/tasks（createTask）：模板 ID 与图片在同一个 multipart 请求中上传（OQ-05）。
 * `files` 按上传位顺序排列，第 i 个文件对应 slotIndex = i。成功返回 202 + Task（PENDING）。
 */
export function createTask(templateId: string, files: readonly File[], options?: UploadOptions): Promise<Task> {
  const form = new FormData();
  form.append("templateId", templateId satisfies CreateTaskForm["templateId"]);
  for (const file of files) form.append("files", file, file.name);
  return uploadMultipart<Task>("/api/tasks", form, options);
}

/**
 * GET /api/tasks/{taskId}（getTask）：单个任务的当前状态，纯只读。
 * 他人或不存在的任务 → 404 TASK_NOT_FOUND；非数字 taskId → 400（api.yaml TaskId）。
 */
export function getTask(taskId: string, signal?: AbortSignal): Promise<Task> {
  return apiRequest<Task>(`/api/tasks/${encodeURIComponent(taskId)}`, { signal });
}

/**
 * GET /api/tasks（listTasks）：当前用户的任务历史，createdAt 倒序、不分页（OQ-09 余项临时默认），纯只读。
 * 传 `templateId` 只返回该模板的任务（详情页 History）；不传返回全部模板（"我的记录"页）。
 * templateId 不存在时返回空列表。
 */
export async function listTasks(
  params: { templateId?: string } = {},
  signal?: AbortSignal,
): Promise<Task[]> {
  const query = params.templateId ? `?${new URLSearchParams({ templateId: params.templateId }).toString()}` : "";
  const body = await apiRequest<TaskList>(`/api/tasks${query}`, { signal });
  return body.items;
}
