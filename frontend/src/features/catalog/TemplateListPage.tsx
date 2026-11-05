"use client";

import { useSearchParams } from "next/navigation";
import { useEffect } from "react";

import { ErrorState } from "@/components/ErrorState";
import { listCategories, listTemplates, type Category, type TemplateSummary } from "@/lib/api/endpoints/catalog";
import { useKeyedLoad, type LoadOutcome } from "@/lib/useKeyedLoad";

import { CategoryTabs } from "./CategoryTabs";
import { CATEGORY_QUERY_KEY, rememberListCategory } from "./listReturn";
import { TemplateGrid, TemplateGridSkeleton } from "./TemplateGrid";

const CATEGORIES_KEY = "categories";
/** "全部"对应的模板请求 key（分类 ID 为非空 slug，不会冲突）。 */
const ALL_TEMPLATES_KEY = "";

function loadCategories(_key: string, signal: AbortSignal): Promise<Category[]> {
  return listCategories(signal).then((res) => res.items);
}

function loadTemplates(key: string, signal: AbortSignal): Promise<TemplateSummary[]> {
  return listTemplates(key === ALL_TEMPLATES_KEY ? null : key, signal).then((res) => res.items);
}

/** 写入 `?category=`；用原生 History API，Next.js 会同步到 useSearchParams 且不触发服务端往返。 */
function pushCategoryToUrl(categoryId: string | null) {
  const params = new URLSearchParams(window.location.search);
  if (categoryId) params.set(CATEGORY_QUERY_KEY, categoryId);
  else params.delete(CATEGORY_QUERY_KEY);
  const query = params.toString();
  window.history.pushState(null, "", query ? `?${query}` : window.location.pathname);
}

/**
 * 模板列表页（F-01）：分类标签行 + 卡片网格，分类与 URL 同步。
 * 先拿分类列表，再对照它决定实际筛选的分类：URL 中的未知分类回落到"全部"并提示（F-01 `[技术决策]`），
 * 因此不会把未知 / 超长的 categoryId 发给后端。
 */
export function TemplateListPage() {
  const urlCategory = useSearchParams().get(CATEGORY_QUERY_KEY) || null;

  const categories = useKeyedLoad(CATEGORIES_KEY, loadCategories);
  const known = categories.outcome.status === "ready" ? categories.outcome.data : null;
  const unknownCategory =
    known !== null && urlCategory !== null && !known.some((c) => c.categoryId === urlCategory) ? urlCategory : null;
  const selected = unknownCategory ? null : urlCategory;

  const templates = useKeyedLoad(known === null ? null : (selected ?? ALL_TEMPLATES_KEY), loadTemplates);

  // 记住已确认的筛选，详情页的"返回"据此回到原分类（F-02 验收 4）；分类未加载 / 未知分类时不记录
  useEffect(() => {
    if (known !== null) rememberListCategory(selected);
  }, [known, selected]);

  if (categories.outcome.status === "error") {
    return <ErrorState message={categories.outcome.message} onRetry={categories.retry} />;
  }
  if (known === null) {
    return <TemplateGridSkeleton />;
  }

  return (
    <div className="flex flex-col gap-6">
      <CategoryTabs categories={known} selected={selected} onSelect={pushCategoryToUrl} />
      {unknownCategory && (
        <p role="status" className="text-sm text-amber-700 dark:text-amber-400">
          未找到分类「{unknownCategory}」，已显示全部模板。
        </p>
      )}
      <TemplateResults outcome={templates.outcome} onRetry={templates.retry} filtered={selected !== null} />
    </div>
  );
}

function TemplateResults({
  outcome,
  onRetry,
  filtered,
}: {
  outcome: LoadOutcome<TemplateSummary[]>;
  onRetry: () => void;
  filtered: boolean;
}) {
  if (outcome.status === "loading") return <TemplateGridSkeleton />;
  if (outcome.status === "error") return <ErrorState message={outcome.message} onRetry={onRetry} />;
  if (outcome.data.length === 0) {
    return (
      <p className="rounded-lg border border-dashed border-zinc-300 p-10 text-center text-zinc-500 dark:border-zinc-700">
        {filtered ? "该分类下暂无模板" : "暂无模板"}
      </p>
    );
  }
  return <TemplateGrid templates={outcome.data} />;
}
