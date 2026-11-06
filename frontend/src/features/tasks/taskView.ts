/**
 * 任务卡片的展示文案（T-052；F-09 规则 1 / 4 / 6，F-11 规则 6）。只做服务端字段 → 文案的映射，不含业务判断。
 */
import type { Task, TaskStage } from "@/lib/api/endpoints/tasks";

/** 处理中阶段文案（F-09 规则 6）。 */
export const STAGE_LABEL: Record<TaskStage, string> = {
  ASSET_CHECK: "素材检查中",
  GENERATING: "生成中",
  RESULT_ORGANIZING: "结果整理中",
};

/** 失败阶段文案，如"失败 · 生成阶段 · <原因>"（F-09 验收标准 3）。 */
export const FAILED_STAGE_LABEL: Record<TaskStage, string> = {
  ASSET_CHECK: "素材检查阶段",
  GENERATING: "生成阶段",
  RESULT_ORGANIZING: "结果整理阶段",
};

/** 卡片外观，与 `data-state` 一致；查询异常不在这里（叠加显示，见 TaskCard）。 */
export type TaskCardState = "pending" | "processing" | "succeeded" | "failed";

export function cardState(task: Task): TaskCardState {
  switch (task.status) {
    case "PENDING":
      return "pending";
    case "PROCESSING":
      return "processing";
    case "SUCCEEDED":
      return "succeeded";
    case "FAILED":
      return "failed";
  }
}

/** 状态标题：等待处理 / 处理中 · 生成中 / 制作完成 / 制作失败。只有服务端 FAILED 才是"制作失败"（F-09 规则 4）。 */
export function statusLabel(task: Task): string {
  switch (task.status) {
    case "PENDING":
      return "等待处理";
    case "PROCESSING":
      // PROCESSING 时 stage 非空；万一缺失也只显示"处理中"
      return task.stage ? `处理中 · ${STAGE_LABEL[task.stage]}` : "处理中";
    case "SUCCEEDED":
      return "制作完成";
    case "FAILED":
      return "制作失败";
  }
}

/** 失败详情："生成阶段 · 模拟失败：生成阶段出错"；任务未开始就被废弃时（failedStage = null）只有原因。 */
export function failureDetail(task: Task): string {
  const reason = task.failureReason ?? "未知原因";
  return task.failedStage ? `${FAILED_STAGE_LABEL[task.failedStage]} · ${reason}` : reason;
}

const pad = (n: number) => String(n).padStart(2, "0");

/** 浏览器本地时区 `YYYY-MM-DD HH:mm`（OQ-09 余项临时默认）；无法解析时原样返回。 */
export function formatDateTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
