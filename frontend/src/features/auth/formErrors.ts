import { isApiError, type ApiError } from "@/lib/api/client";

export type FieldErrors<F extends string> = Partial<Record<F, string>>;

/** 把后端 `details.fieldErrors` 中属于表单字段的错误取出来（同一字段只保留第一条）。 */
export function serverFieldErrors<F extends string>(error: ApiError, fields: readonly F[]): FieldErrors<F> {
  const result: FieldErrors<F> = {};
  for (const fieldError of error.details?.fieldErrors ?? []) {
    const field = fields.find((f) => f === fieldError.field);
    if (field && !result[field]) result[field] = fieldError.message;
  }
  return result;
}

/** 无法归到字段上的错误转为表单级提示文案。 */
export function formErrorMessage(error: unknown): string {
  return isApiError(error) ? error.message : "操作失败，请稍后重试";
}

export function hasErrors(errors: Record<string, string | undefined>): boolean {
  return Object.values(errors).some(Boolean);
}
