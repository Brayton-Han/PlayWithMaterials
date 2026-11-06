import type { User } from "@/lib/api/endpoints/auth";
import type { Task } from "@/lib/api/endpoints/tasks";

export function makeUser(username: string, userId = "1"): User {
  return { userId, username, email: `${username}@example.com`, createdAt: "2026-11-05T00:00:00.000Z" };
}

/** 符合 api.yaml Task 字段规则的任务；默认 PENDING。 */
export function makeTask(overrides: Partial<Task> & { taskId: string }): Task {
  return {
    templateId: "tpl-postcard",
    templateTitle: "明信片",
    templateCoverUrl: "/files/seed/covers/postcard.jpg",
    status: "PENDING",
    stage: null,
    failedStage: null,
    failureCode: null,
    failureReason: null,
    resultUrl: null,
    inputAssets: [{ slotIndex: 0, url: "/files/uploads/2026/11/a.jpg" }],
    createdAt: "2026-11-05T08:00:00.000Z",
    updatedAt: "2026-11-05T08:00:00.000Z",
    startedAt: null,
    finishedAt: null,
    ...overrides,
  };
}
