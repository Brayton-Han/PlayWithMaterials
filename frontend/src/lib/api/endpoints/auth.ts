import { apiRequest } from "../client";
import type { components } from "../schema";

export type User = components["schemas"]["User"];
export type RegisterRequest = components["schemas"]["RegisterRequest"];
export type LoginRequest = components["schemas"]["LoginRequest"];

export function register(body: RegisterRequest, signal?: AbortSignal): Promise<User> {
  return apiRequest<User>("/api/auth/register", { method: "POST", body, signal });
}

export function login(body: LoginRequest, signal?: AbortSignal): Promise<User> {
  return apiRequest<User>("/api/auth/login", { method: "POST", body, signal });
}

export function logout(signal?: AbortSignal): Promise<void> {
  return apiRequest<void>("/api/auth/logout", { method: "POST", signal });
}

/** 401 表示"未登录"，不触发全局会话失效处理（api.yaml getCurrentUser）。 */
export function getCurrentUser(signal?: AbortSignal): Promise<User> {
  return apiRequest<User>("/api/auth/me", { signal });
}
