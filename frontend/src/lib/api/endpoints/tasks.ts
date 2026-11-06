import type { components } from "../schema";
import { uploadMultipart, type UploadOptions } from "../upload";

/** 任务视图（api.yaml Task）；查询类请求函数由 T-051 / T-054 按需添加。 */
export type Task = components["schemas"]["Task"];
export type TaskStatus = components["schemas"]["TaskStatus"];
export type TaskStage = components["schemas"]["TaskStage"];
export type CreateTaskForm = components["schemas"]["CreateTaskForm"];

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
