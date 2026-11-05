/** 请求失败时的提示 + "重试"按钮（F-01 / F-02：不白屏）。 */
export function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div
      role="alert"
      className="flex flex-col items-start gap-3 rounded-lg border border-red-200 bg-red-50 p-6 text-red-800 dark:border-red-900 dark:bg-red-950/40 dark:text-red-200"
    >
      <p>{message}</p>
      <button
        type="button"
        onClick={onRetry}
        className="rounded-md bg-zinc-900 px-4 py-2 text-sm font-medium text-white hover:bg-zinc-700 focus-visible:ring-2 focus-visible:ring-zinc-400 focus-visible:outline-none dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
      >
        重试
      </button>
    </div>
  );
}
