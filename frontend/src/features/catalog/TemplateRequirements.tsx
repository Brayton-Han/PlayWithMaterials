import type { TemplateDetail } from "@/lib/api/endpoints/catalog";

const MIME_LABELS: Record<TemplateDetail["acceptedMimeTypes"][number], string> = {
  "image/jpeg": "JPG",
  "image/png": "PNG",
  "image/webp": "WebP",
};

const KB = 1024;
const MB = 1024 * KB;

/** 字节数 → "5 MB" / "1.5 MB" / "500 KB"（1 MB = 1024 × 1024 字节，与后端 maxFileSizeBytes 一致）。 */
export function formatFileSize(bytes: number): string {
  if (bytes >= MB) return `${trimDecimal(bytes / MB)} MB`;
  if (bytes >= KB) return `${trimDecimal(bytes / KB)} KB`;
  return `${bytes} B`;
}

function trimDecimal(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(1).replace(/\.0$/, "");
}

export function formatMimeTypes(types: readonly TemplateDetail["acceptedMimeTypes"][number][]): string {
  return types.map((t) => MIME_LABELS[t] ?? t).join(" / ");
}

/** 素材要求：张数、允许类型、单张大小上限（F-02 规则 1）。数值全部来自接口。 */
export function TemplateRequirements({ template }: { template: TemplateDetail }) {
  const items = [
    { label: "图片数量", value: `${template.requiredImageCount} 张` },
    { label: "支持格式", value: formatMimeTypes(template.acceptedMimeTypes) },
    { label: "单张上限", value: formatFileSize(template.maxFileSizeBytes) },
  ];
  return (
    <section aria-labelledby="template-requirements" className="flex flex-col gap-2">
      <h2 id="template-requirements" className="text-lg font-semibold">
        素材要求
      </h2>
      <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-1 text-sm">
        {items.map((item) => (
          <div key={item.label} className="contents">
            <dt className="text-zinc-500 dark:text-zinc-400">{item.label}</dt>
            <dd>{item.value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
