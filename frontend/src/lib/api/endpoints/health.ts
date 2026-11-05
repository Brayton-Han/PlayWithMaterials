import { apiRequest } from "../client";
import type { paths } from "../schema";

export type HealthResponse =
  paths["/actuator/health"]["get"]["responses"]["200"]["content"]["application/json"];

export function getHealth(signal?: AbortSignal): Promise<HealthResponse> {
  return apiRequest<HealthResponse>("/actuator/health", { signal });
}
