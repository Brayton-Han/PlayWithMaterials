import { BackendStatus } from "@/features/health/BackendStatus";

export default function Home() {
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">模板</h1>
      <p className="text-zinc-600 dark:text-zinc-400">模板列表将在 T-022 中实现。</p>
      <BackendStatus />
    </section>
  );
}
