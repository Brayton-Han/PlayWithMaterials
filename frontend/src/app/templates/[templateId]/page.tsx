import type { Metadata } from "next";
import { Suspense } from "react";

import { TemplateDetailPage, TemplateDetailSkeleton } from "@/features/catalog/TemplateDetailPage";

export const metadata: Metadata = { title: "模板详情 · 小影" };

/** 读取路由参数是请求期数据，放进 Suspense，页面外壳（顶栏）仍可预渲染（cacheComponents）。 */
export default function TemplatePage({ params }: PageProps<"/templates/[templateId]">) {
  return (
    <Suspense fallback={<TemplateDetailSkeleton />}>
      <TemplateDetail params={params} />
    </Suspense>
  );
}

async function TemplateDetail({ params }: Pick<PageProps<"/templates/[templateId]">, "params">) {
  const { templateId } = await params;
  // 以 templateId 为 key：站内切换模板时重新挂载，旧模板的本地状态不会残留
  return <TemplateDetailPage key={templateId} templateId={templateId} />;
}
