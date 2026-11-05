import type { User } from "@/lib/api/endpoints/auth";

export function makeUser(username: string, userId = "1"): User {
  return { userId, username, email: `${username}@example.com`, createdAt: "2026-11-05T00:00:00.000Z" };
}
