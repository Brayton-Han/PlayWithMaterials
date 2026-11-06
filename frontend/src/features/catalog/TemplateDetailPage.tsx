"use client";

import Link from "next/link";
import { useState, type ReactNode } from "react";

import { ErrorState } from "@/components/ErrorState";
import { LoginPrompt } from "@/features/auth/LoginPrompt";
import { useRequireAuth } from "@/features/auth/useRequireAuth";
import { isApiError } from "@/lib/api/client";
import { getTemplate, type TemplateDetail } from "@/lib/api/endpoints/catalog";
import type { Task } from "@/lib/api/endpoints/tasks";
import { HistoryPanel } from "@/features/tasks/HistoryPanel";
import { taskStore } from "@/features/tasks/taskStore";
import { TaskSubmitForm } from "@/features/upload/TaskSubmitForm";
import { useKeyedLoad } from "@/lib/useKeyedLoad";

import { listPageHref } from "./listReturn";
import { TemplateRequirements } from "./TemplateRequirements";

function loadTemplate(templateId: string, signal: AbortSignal): Promise<TemplateDetail> {
  return getTemplate(templateId, signal);
}

/** 只有后端明确的 404 TEMPLATE_NOT_FOUND 才算"不存在"；网络错误、5xx 等一律是"请求失败"（F-02 异常情况）。 */
function isTemplateNotFound(error: unknown): boolean {
  return isApiError(error) && error.status === 404 && error.code === "TEMPLATE_NOT_FOUND";
}

/**
 * 模板详情页（F-02）：返回、标题、封面、描述、素材要求；左栏上传区（T-031 选图，T-032 提交），右栏 History（T-052）；
 * 提交受理的任务写入 taskStore 后出现在 History（T-053）。
 * 未登录时上传区和 History 替换为"登录后即可制作"提示，登录后回到本页（OQ-23 余项 1 临时默认）。
 */
export function TemplateDetailPage({ templateId }: { templateId: string }) {
  const { outcome, retry } = useKeyedLoad(templateId, loadTemplate);
  // 挂载时读取一次：站内跳转来时保留列表页的分类筛选
  const [backHref] = useState(listPageHref);

  return (
    <div className="flex flex-col gap-6">
      <BackLink href={backHref} />
      {outcome.status === "loading" && <TemplateDetailSkeleton />}
      {outcome.status === "error" &&
        (isTemplateNotFound(outcome.error) ? (
          <TemplateNotFound backHref={backHref} />
        ) : (
          <ErrorState message={`模板加载失败：${outcome.message}`} onRetry={retry} />
        ))}
      {outcome.status === "ready" && (
        <TemplateDetailLayout
          template={outcome.data}
          loginNext={`/templates/${encodeURIComponent(templateId)}`}
        />
      )}
    </div>
  );
}

function BackLink({ href }: { href: string }) {
  return (
    <Link
      href={href}
      className="self-start text-sm text-zinc-600 hover:text-zinc-900 hover:underline dark:text-zinc-400 dark:hover:text-zinc-100"
    >
      ← 返回模板列表
    </Link>
  );
}

const COLUMNS_CLASS = "grid grid-cols-1 gap-8 lg:grid-cols-[minmax(0,1fr)_20rem]";
const PANEL_CLASS = "flex flex-col gap-3 rounded-lg border border-zinc-200 p-4 dark:border-zinc-800";

function TemplateDetailLayout({ template, loginNext }: { template: TemplateDetail; loginNext: string }) {
  return (
    <div className={COLUMNS_CLASS}>
      <div className="flex min-w-0 flex-col gap-6">
        <header className="flex flex-col gap-1">
          <p className="text-sm text-zinc-500 dark:text-zinc-400">{template.categoryName}</p>
          <h1 className="text-2xl font-semibold">{template.title}</h1>
        </header>
        <TemplateCover url={template.coverUrl} title={template.title} />
        <p className="whitespace-pre-line text-zinc-700 dark:text-zinc-300">{template.description}</p>
        <TemplateRequirements template={template} />
        <section aria-labelledby="upload-area" className={PANEL_CLASS}>
          <h2 id="upload-area" className="text-lg font-semibold">
            上传素材
          </h2>
          <AuthOnly loginNext={loginNext}>
            <TemplateUploadArea key={template.templateId} template={template} />
          </AuthOnly>
        </section>
      </div>
      <aside aria-labelledby="template-history" className={`${PANEL_CLASS} self-start`}>
        <h2 id="template-history" className="text-lg font-semibold">
          History
        </h2>
        <AuthOnly loginNext={loginNext}>
          <HistoryPanel key={template.templateId} templateId={template.templateId} />
        </AuthOnly>
      </aside>
    </div>
  );
}

/** 已登录才渲染 children；未登录显示登录提示（不跳转）。只影响体验，权限由后端保证。 */
function AuthOnly({ loginNext, children }: { loginNext: string; children: ReactNode }) {
  const { status } = useRequireAuth();
  if (status === "authenticated") return <>{children}</>;
  if (status === "anonymous") return <LoginPrompt next={loginNext} />;
  return (
    <p role="status" className="text-sm text-zinc-500">
      正在确认登录状态…
    </p>
  );
}

/**
 * 上传区：上传位数量 = requiredImageCount（F-02 规则 2），选图、预览、替换、移除在本地完成（T-031）；
 * 提交、上传进度、锁定与失败反馈见 TaskSubmitForm（T-032）。
 * 受理后立即把返回的任务（PENDING）写入 taskStore（T-053，F-09 规则 2）：右栏 History 随即出现新卡片，
 * 其中的跟踪调度器订阅了 store，会自动开始跟踪该任务。提交失败时不写入。
 */
function TemplateUploadArea({ template }: { template: TemplateDetail }) {
  return <TaskSubmitForm template={template} onAccepted={upsertAcceptedTask} />;
}

function upsertAcceptedTask(task: Task, epoch: number) {
  taskStore.getState().upsertFromServer(task, epoch);
}

function TemplateCover({ url, title }: { url: string; title: string }) {
  // 封面加载失败时显示占位，不影响页面其余内容
  const [failed, setFailed] = useState(false);
  return (
    <div className="aspect-[4/3] w-full max-w-xl overflow-hidden rounded-lg bg-zinc-100 dark:bg-zinc-900">
      {failed ? (
        <div role="img" aria-label="封面加载失败" className="flex h-full items-center justify-center text-sm text-zinc-400">
          暂无封面
        </div>
      ) : (
        // 封面是后端经 rewrites 提供的同源静态文件，不走 next/image 优化管线
        // eslint-disable-next-line @next/next/no-img-element
        <img src={url} alt={`${title} 示例封面`} onError={() => setFailed(true)} className="h-full w-full object-cover" />
      )}
    </div>
  );
}

function TemplateNotFound({ backHref }: { backHref: string }) {
  return (
    <div className="flex flex-col items-start gap-3 rounded-lg border border-dashed border-zinc-300 p-10 dark:border-zinc-700">
      <h1 className="text-2xl font-semibold">模板不存在</h1>
      <p className="text-zinc-600 dark:text-zinc-400">该模板可能已下线，或链接有误。</p>
      <Link
        href={backHref}
        className="rounded-md bg-zinc-900 px-4 py-2 text-sm font-medium text-white hover:bg-zinc-700 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
      >
        返回模板列表
      </Link>
    </div>
  );
}

/** 加载占位：两栏骨架；此时不渲染上传区，上传与提交不可用（F-02 异常情况）。 */
export function TemplateDetailSkeleton() {
  const block = "animate-pulse rounded bg-zinc-100 dark:bg-zinc-900";
  return (
    <div role="status" aria-label="模板详情加载中" className={COLUMNS_CLASS}>
      <div className="flex flex-col gap-6" aria-hidden>
        <div className={`h-8 w-1/3 ${block}`} />
        <div className={`aspect-[4/3] w-full max-w-xl ${block}`} />
        <div className={`h-16 w-full ${block}`} />
      </div>
      <div className={`h-40 w-full ${block}`} aria-hidden />
    </div>
  );
}
