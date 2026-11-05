import { apiRequest } from "../client";
import type { components } from "../schema";

export type Category = components["schemas"]["Category"];
export type CategoryList = components["schemas"]["CategoryList"];
export type TemplateSummary = components["schemas"]["TemplateSummary"];
export type TemplateSummaryList = components["schemas"]["TemplateSummaryList"];

/** 一级分类，按 sortOrder 升序；"全部"不在结果中，由前端虚拟（api.yaml listCategories）。 */
export function listCategories(signal?: AbortSignal): Promise<CategoryList> {
  return apiRequest<CategoryList>("/api/categories", { signal });
}

/** 模板卡片列表；categoryId 缺省 = 全部（api.yaml listTemplates）。 */
export function listTemplates(categoryId: string | null, signal?: AbortSignal): Promise<TemplateSummaryList> {
  const query = categoryId ? `?${new URLSearchParams({ categoryId }).toString()}` : "";
  return apiRequest<TemplateSummaryList>(`/api/templates${query}`, { signal });
}
