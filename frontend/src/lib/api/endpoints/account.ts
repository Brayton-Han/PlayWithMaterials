import { apiRequest } from "../client";
import type { components } from "../schema";

export type ChangePasswordRequest = components["schemas"]["ChangePasswordRequest"];
export type DeleteAccountRequest = components["schemas"]["DeleteAccountRequest"];

/** 204；当前密码错误为 400 CURRENT_PASSWORD_INCORRECT（不是 401，不触发全局会话失效处理）。 */
export function changePassword(body: ChangePasswordRequest, signal?: AbortSignal): Promise<void> {
  return apiRequest<void>("/api/account/password", { method: "PUT", body, signal });
}

/** 204 + 清除 Cookie；确认文字不是 `DELETE` 时 400 CONFIRM_TEXT_MISMATCH。 */
export function deleteAccount(body: DeleteAccountRequest, signal?: AbortSignal): Promise<void> {
  return apiRequest<void>("/api/account", { method: "DELETE", body, signal });
}
