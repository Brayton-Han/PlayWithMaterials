"use client";

import { useId, useState, type FormEvent } from "react";

import { TextField } from "@/components/TextField";
import { formErrorMessage, hasErrors, serverFieldErrors, type FieldErrors } from "@/features/auth/formErrors";
import { isAbortError, isApiError } from "@/lib/api/client";
import { changePassword } from "@/lib/api/endpoints/account";

const FIELDS = ["currentPassword", "newPassword", "confirmPassword"] as const;
type Field = (typeof FIELDS)[number];
type Values = Record<Field, string>;

const EMPTY: Values = { currentPassword: "", newPassword: "", confirmPassword: "" };

/** 规则与 api.yaml ChangePasswordRequest 一致；新密码规则同注册（OQ-24），确认新密码只在前端校验（DQ-04）。 */
export function validateChangePassword(values: Values): FieldErrors<Field> {
  const errors: FieldErrors<Field> = {};
  if (!values.currentPassword) errors.currentPassword = "请输入当前密码";

  if (!values.newPassword) errors.newPassword = "请输入新密码";
  else if (values.newPassword.length < 8 || values.newPassword.length > 64) errors.newPassword = "密码需为 8 ~ 64 位";

  if (!values.confirmPassword) errors.confirmPassword = "请再次输入新密码";
  else if (values.confirmPassword !== values.newPassword) errors.confirmPassword = "两次输入的新密码不一致";
  return errors;
}

/** 修改密码（F-28 规则 2）：需要当前密码；成功后其他设备的会话失效，当前会话保留（OQ-25 余项临时默认）。 */
export function ChangePasswordForm() {
  const headingId = useId();
  const [values, setValues] = useState<Values>(EMPTY);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors<Field>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [succeeded, setSucceeded] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    const errors = validateChangePassword(values);
    setFieldErrors(errors);
    setFormError(null);
    setSucceeded(false);
    if (hasErrors(errors)) return;

    setSubmitting(true);
    try {
      await changePassword({ currentPassword: values.currentPassword, newPassword: values.newPassword });
      setValues(EMPTY);
      setSucceeded(true);
    } catch (error) {
      // 会话切换时请求被取消，本组件随之卸载
      if (isAbortError(error)) return;
      if (isApiError(error) && error.code === "CURRENT_PASSWORD_INCORRECT") {
        setFieldErrors({ currentPassword: error.message });
      } else if (isApiError(error) && error.code === "VALIDATION_FAILED") {
        const mapped = serverFieldErrors(error, FIELDS);
        setFieldErrors(mapped);
        if (!hasErrors(mapped)) setFormError(error.message);
      } else {
        // 401 已由全局处理器退出并跳转登录；其余（网络 / 5xx）提示失败，密码未修改
        setFormError(formErrorMessage(error));
      }
    }
    setSubmitting(false);
  }

  const update = (field: Field) => (event: { target: { value: string } }) => {
    setValues((prev) => ({ ...prev, [field]: event.target.value }));
    setSucceeded(false);
  };

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4 rounded-lg border border-zinc-200 p-6 dark:border-zinc-800">
      <h2 id={headingId} className="text-lg font-semibold">
        修改密码
      </h2>
      <form noValidate onSubmit={handleSubmit} className="flex max-w-sm flex-col gap-4" aria-label="修改密码">
        <TextField
          label="当前密码"
          name="currentPassword"
          type="password"
          autoComplete="current-password"
          maxLength={64}
          value={values.currentPassword}
          onChange={update("currentPassword")}
          error={fieldErrors.currentPassword}
        />
        <TextField
          label="新密码"
          name="newPassword"
          type="password"
          autoComplete="new-password"
          maxLength={64}
          hint="8 ~ 64 位"
          value={values.newPassword}
          onChange={update("newPassword")}
          error={fieldErrors.newPassword}
        />
        <TextField
          label="确认新密码"
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
        <p role="status" className="text-sm text-green-700 empty:hidden dark:text-green-400">
          {succeeded ? "密码已修改。其他设备上的登录已失效，当前设备保持登录。" : ""}
        </p>
        <button
          type="submit"
          disabled={submitting}
          className="self-start rounded-md bg-zinc-900 px-4 py-2 text-sm font-medium text-white hover:bg-zinc-700 focus-visible:ring-2 focus-visible:ring-zinc-400 focus-visible:outline-none disabled:opacity-60 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
        >
          {submitting ? "保存中…" : "修改密码"}
        </button>
      </form>
    </section>
  );
}
