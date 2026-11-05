"use client";

import { useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";

import { ErrorState } from "@/components/ErrorState";
import { isAbortError, isApiError } from "@/lib/api/client";
import { listCategories, listTemplates, type Category, type TemplateSummary } from "@/lib/api/endpoints/catalog";

import { CategoryTabs } from "./CategoryTabs";
import { TemplateGrid, TemplateGridSkeleton } from "./TemplateGrid";

/** URL 中的分类参数（architecture §6：`/?category=`）。 */
export const CATEGORY_QUERY_KEY = "category";

const FALLBACK_ERROR_MESSAGE = "加载失败，请稍后重试";
const INTERRUPTED_MESSAGE = "加载被中断，请重试";

type Outcome<T> = { status: "loading" } | { status: "error"; message: string } | { status: "ready"; data: T };

/**
 * 按 key 加载数据：key 变化或重试时 abort 旧请求；结果按 (key, attempt) 归属，
 * 只有与当前 key 匹配的结果才会展示，较慢的旧响应不会覆盖新结果（F-01 验收 3）。
 * key 为 null 表示依赖尚未就绪，保持 loading 且不发请求。
 */
function useKeyedLoad<T>(key: string | null, load: (key: string, signal: AbortSignal) => Promise<T>) {
  const [attempt, setAttempt] = useState(0);
  const [settled, setSettled] = useState<{ key: string; attempt: number; outcome: Outcome<T> } | null>(null);

  useEffect(() => {
    if (key === null) return;
    const controller = new AbortController();
    load(key, controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) setSettled({ key, attempt, outcome: { status: "ready", data } });
      },
      (error: unknown) => {
        if (controller.signal.aborted) return;
        // 被会话级取消（退出登录时 abortAllRequests）的请求不能卡在 loading，转为可重试的错误
        const message = isAbortError(error)
          ? INTERRUPTED_MESSAGE
          : isApiError(error)
            ? error.message
            : FALLBACK_ERROR_MESSAGE;
        setSettled({ key, attempt, outcome: { status: "error", message } });
      },
    );
    return () => controller.abort();
  }, [key, attempt, load]);

  const outcome: Outcome<T> =
    settled && settled.key === key && settled.attempt === attempt ? settled.outcome : { status: "loading" };
  return { outcome, retry: () => setAttempt((n) => n + 1) };
}

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
  outcome: Outcome<TemplateSummary[]>;
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
