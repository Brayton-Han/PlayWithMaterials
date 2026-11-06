import { useId, type InputHTMLAttributes, type Ref } from "react";

interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "id"> {
  label: string;
  /** 常驻说明（如"邮箱注册后不能修改"） */
  hint?: string;
  /** 校验错误；有值时输入框标记为 aria-invalid 并用 aria-describedby 关联 */
  error?: string;
  /** 指向内部 input（React 19 ref 作为普通 prop），如对话框打开后聚焦输入框 */
  ref?: Ref<HTMLInputElement>;
}

/** 带标签、说明和错误提示的单行输入框。 */
export function TextField({ label, hint, error, className, ref, ...inputProps }: TextFieldProps) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ") || undefined;

  return (
    <div className={`flex flex-col gap-1 ${className ?? ""}`}>
      <label htmlFor={id} className="text-sm font-medium">
        {label}
      </label>
      <input
        ref={ref}
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={`rounded-md border bg-transparent px-3 py-2 text-sm outline-none focus-visible:ring-2 focus-visible:ring-zinc-400 ${
          error ? "border-red-500" : "border-zinc-300 dark:border-zinc-700"
        }`}
        {...inputProps}
      />
      {hint && (
        <p id={hintId} className="text-xs text-zinc-500">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className="text-xs text-red-600 dark:text-red-400">
          {error}
        </p>
      )}
    </div>
  );
}
