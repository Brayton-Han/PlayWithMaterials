import { Suspense } from "react";

import { TemplateGridSkeleton } from "@/features/catalog/TemplateGrid";
import { TemplateListPage } from "@/features/catalog/TemplateListPage";

export default function Home() {
  return (
    <section className="flex flex-col gap-6">
      <h1 className="text-2xl font-semibold">模板</h1>
      {/* TemplateListPage 读取 ?category=，useSearchParams 需要 Suspense 边界才能预渲染页面其余部分 */}
      <Suspense fallback={<TemplateGridSkeleton />}>
        <TemplateListPage />
      </Suspense>
    </section>
  );
}
