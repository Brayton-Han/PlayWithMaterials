"use client";

import Link from "next/link";
import { useState } from "react";

import type { TemplateSummary } from "@/lib/api/endpoints/catalog";

const GRID_CLASS = "grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3";

/** 模板卡片网格；点击卡片进入 `/templates/{id}`（F-01 规则 2）。 */
export function TemplateGrid({ templates }: { templates: readonly TemplateSummary[] }) {
  return (
    <ul className={GRID_CLASS} aria-label="模板列表">
      {templates.map((t) => (
        <li key={t.templateId}>
          <TemplateCard template={t} />
        </li>
      ))}
    </ul>
  );
}

function TemplateCard({ template }: { template: TemplateSummary }) {
  // 封面加载失败时显示占位，标题仍可见可点（F-01 异常情况）
  const [coverFailed, setCoverFailed] = useState(false);

  return (
    <Link
      href={`/templates/${encodeURIComponent(template.templateId)}`}
      className="group flex flex-col overflow-hidden rounded-lg border border-zinc-200 hover:border-zinc-400 focus-visible:ring-2 focus-visible:ring-zinc-400 focus-visible:outline-none dark:border-zinc-800 dark:hover:border-zinc-600"
    >
      <div className="aspect-[4/3] w-full bg-zinc-100 dark:bg-zinc-900">
        {coverFailed ? (
          <div
            role="img"
            aria-label="封面加载失败"
            className="flex h-full w-full items-center justify-center text-sm text-zinc-400"
          >
            暂无封面
          </div>
        ) : (
          // 封面是后端经 rewrites 提供的同源静态文件，不走 next/image 优化管线
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={template.coverUrl}
            alt=""
            loading="lazy"
            onError={() => setCoverFailed(true)}
            className="h-full w-full object-cover transition-transform group-hover:scale-[1.02]"
          />
        )}
      </div>
      <span className="px-4 py-3 font-medium">{template.title}</span>
    </Link>
  );
}

/** 加载占位（骨架卡片）。 */
export function TemplateGridSkeleton({ count = 6 }: { count?: number }) {
  return (
    <div role="status" aria-label="模板加载中">
      <ul className={GRID_CLASS} aria-hidden>
        {Array.from({ length: count }, (_, i) => (
          <li key={i} className="overflow-hidden rounded-lg border border-zinc-200 dark:border-zinc-800">
            <div className="aspect-[4/3] w-full animate-pulse bg-zinc-100 dark:bg-zinc-900" />
            <div className="px-4 py-3">
              <div className="h-5 w-2/3 animate-pulse rounded bg-zinc-100 dark:bg-zinc-900" />
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
