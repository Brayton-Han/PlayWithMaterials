"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState, type FormEvent } from "react";

import { TextField } from "@/components/TextField";
import { isAbortError, isApiError } from "@/lib/api/client";
import { useKeepFocus } from "@/lib/useKeepFocus";

import { useAuth } from "./AuthProvider";
import { formErrorMessage, hasErrors, serverFieldErrors, type FieldErrors } from "./formErrors";
import { authPageHref, safeNextPath } from "./session";

const FIELDS = ["username", "password"] as const;
type Field = (typeof FIELDS)[number];
const FOCUS_AFTER_SUBMIT = ['button[type="submit"]'];

/** 登录只校验必填；长度上限（api.yaml LoginRequest：20 / 64）由输入框 maxLength 限制。 */
export function validateLogin(values: Record<Field, string>): FieldErrors<Field> {
  const errors: FieldErrors<Field> = {};
  if (!values.username) errors.username = "请输入用户名";
  if (!values.password) errors.password = "请输入密码";
  return errors;
}

export function LoginForm() {
  const router = useRouter();
  const next = safeNextPath(useSearchParams().get("next"));
  const { status, login } = useAuth();

  const [values, setValues] = useState<Record<Field, string>>({ username: "", password: "" });
  const [fieldErrors, setFieldErrors] = useState<FieldErrors<Field>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);
  // 失败后焦点回到出错字段或提交按钮（提交期间按钮被禁用，浏览器会把焦点丢回 body）
  const armFocus = useKeepFocus(formRef, submitting, FOCUS_AFTER_SUBMIT);

  // 已登录（包括刚登录成功）→ 回到来源页
  useEffect(() => {
    if (status === "authenticated") router.replace(next);
  }, [status, next, router]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    armFocus();
    const errors = validateLogin(values);
    setFieldErrors(errors);
    setFormError(null);
    if (hasErrors(errors)) return;

    setSubmitting(true);
    try {
      await login(values);
      // 成功后 AuthProvider 切换会话并重新挂载本组件，由上面的 effect 跳转
    } catch (error) {
      if (isAbortError(error)) return;
      if (isApiError(error) && error.code === "VALIDATION_FAILED") {
        const mapped = serverFieldErrors(error, FIELDS);
        setFieldErrors(mapped);
        if (!hasErrors(mapped)) setFormError(error.message);
      } else {
        // INVALID_CREDENTIALS 统一提示；网络 / 5xx 显示通用文案
        setFormError(formErrorMessage(error));
      }
      setSubmitting(false);
    }
  }

  if (status === "authenticated") {
    return (
      <p role="status" className="text-sm text-zinc-500">
        已登录，正在跳转…
      </p>
    );
  }

  const update = (field: Field) => (event: { target: { value: string } }) =>
    setValues((prev) => ({ ...prev, [field]: event.target.value }));

  return (
    <form ref={formRef} noValidate onSubmit={handleSubmit} className="flex flex-col gap-4" aria-label="登录">
      <TextField
        label="用户名"
        name="username"
        autoComplete="username"
        maxLength={20}
        value={values.username}
        onChange={update("username")}
        error={fieldErrors.username}
      />
      <TextField
        label="密码"
        name="password"
        type="password"
        autoComplete="current-password"
        maxLength={64}
        value={values.password}
        onChange={update("password")}
        error={fieldErrors.password}
      />
      {formError && (
        <p role="alert" className="text-sm text-red-600 dark:text-red-400">
          {formError}
        </p>
      )}
      <button
        type="submit"
        disabled={submitting}
        className="rounded-md bg-zinc-900 px-4 py-2 text-sm font-medium text-white hover:bg-zinc-700 disabled:opacity-60 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
      >
        {submitting ? "登录中…" : "登录"}
      </button>
      <p className="text-sm text-zinc-600 dark:text-zinc-400">
        还没有账户？
        <Link href={authPageHref("/register", next)} className="underline">
          注册
        </Link>
      </p>
    </form>
  );
}
