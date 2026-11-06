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

const FIELDS = ["username", "email", "password", "confirmPassword"] as const;
type Field = (typeof FIELDS)[number];
const FOCUS_AFTER_SUBMIT = ['button[type="submit"]'];
type Values = Record<Field, string>;

const USERNAME_PATTERN = /^[A-Za-z0-9_]{3,20}$/; // OQ-29 临时默认
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+$/; // OQ-30：只校验格式，不比后端 @Email 更严

/** 规则与 api.yaml RegisterRequest、后端校验文案一致；确认密码只在前端校验（DQ-04）。 */
export function validateRegister(values: Values): FieldErrors<Field> {
  const errors: FieldErrors<Field> = {};
  if (!values.username) errors.username = "请输入用户名";
  else if (!USERNAME_PATTERN.test(values.username)) errors.username = "用户名需为 3 ~ 20 位字母、数字或下划线";

  const email = values.email.trim();
  if (!email) errors.email = "请输入邮箱";
  else if (email.length > 254) errors.email = "邮箱最长 254 位";
  else if (!EMAIL_PATTERN.test(email)) errors.email = "邮箱格式不正确";

  if (!values.password) errors.password = "请输入密码";
  else if (values.password.length < 8 || values.password.length > 64) errors.password = "密码需为 8 ~ 64 位"; // OQ-24

  if (!values.confirmPassword) errors.confirmPassword = "请再次输入密码";
  else if (values.confirmPassword !== values.password) errors.confirmPassword = "两次输入的密码不一致";
  return errors;
}

export function RegisterForm() {
  const router = useRouter();
  const next = safeNextPath(useSearchParams().get("next"));
  const { status, register } = useAuth();

  const [values, setValues] = useState<Values>({ username: "", email: "", password: "", confirmPassword: "" });
  const [fieldErrors, setFieldErrors] = useState<FieldErrors<Field>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);
  // 失败后焦点回到出错字段或提交按钮（提交期间按钮被禁用，浏览器会把焦点丢回 body）
  const armFocus = useKeepFocus(formRef, submitting, FOCUS_AFTER_SUBMIT);

  useEffect(() => {
    if (status === "authenticated") router.replace(next);
  }, [status, next, router]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    armFocus();
    const errors = validateRegister(values);
    setFieldErrors(errors);
    setFormError(null);
    if (hasErrors(errors)) return;

    setSubmitting(true);
    try {
      await register({ username: values.username, email: values.email.trim(), password: values.password });
    } catch (error) {
      if (isAbortError(error)) return;
      if (isApiError(error) && error.code === "USERNAME_TAKEN") {
        setFieldErrors({ username: error.message });
      } else if (isApiError(error) && error.code === "EMAIL_TAKEN") {
        setFieldErrors({ email: error.message });
      } else if (isApiError(error) && error.code === "VALIDATION_FAILED") {
        const mapped = serverFieldErrors(error, FIELDS);
        setFieldErrors(mapped);
        if (!hasErrors(mapped)) setFormError(error.message);
      } else {
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
    <form ref={formRef} noValidate onSubmit={handleSubmit} className="flex flex-col gap-4" aria-label="注册">
      <TextField
        label="用户名"
        name="username"
        autoComplete="username"
        maxLength={20}
        hint="3 ~ 20 位字母、数字或下划线，用于登录"
        value={values.username}
        onChange={update("username")}
        error={fieldErrors.username}
      />
      <TextField
        label="邮箱"
        name="email"
        type="email"
        autoComplete="email"
        maxLength={254}
        hint="邮箱注册后不能修改"
        value={values.email}
        onChange={update("email")}
        error={fieldErrors.email}
      />
      <TextField
        label="密码"
        name="password"
        type="password"
        autoComplete="new-password"
        maxLength={64}
        hint="8 ~ 64 位"
        value={values.password}
        onChange={update("password")}
        error={fieldErrors.password}
      />
      <TextField
        label="确认密码"
        name="confirmPassword"
        type="password"
        autoComplete="new-password"
        maxLength={64}
        value={values.confirmPassword}
        onChange={update("confirmPassword")}
        error={fieldErrors.confirmPassword}
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
        {submitting ? "注册中…" : "注册"}
      </button>
      <p className="text-sm text-zinc-600 dark:text-zinc-400">
        已有账户？
        <Link href={authPageHref("/login", next)} className="underline">
          登录
        </Link>
      </p>
    </form>
  );
}
