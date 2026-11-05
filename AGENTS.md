# Project Instructions

小影 (xiaoying): pick a template → upload images → backend runs an async 3-step task
(check → generate → organize) → frontend tracks status and shows results.
Single Spring Boot monolith + Next.js UI. See [README.md](README.md) for setup details.

## Tech Stack
- Frontend: Next.js 16 (App Router), React 19, TypeScript strict, Tailwind 4; Vitest + RTL + MSW
- Backend: Spring Boot 4, Java 21, MyBatis, Flyway, Bean Validation; JUnit 5 + Testcontainers
- Database: MySQL 8.4 (`utf8mb4_0900_ai_ci`, times stored in UTC)
- Runtime: everything runs in Docker Compose; no JDK/Node needed on the host

## Source of Truth
Read the relevant ones before implementing. If code and docs disagree, stop and ask.

| Doc | Use it for |
| --- | --- |
| [docs/requirements.md](docs/requirements.md) | Functional spec (F-xx / R-xx) — what to build |
| [docs/backlog.md](docs/backlog.md) | Tasks (T-xxx), acceptance criteria, dependencies |
| [docs/implementation-plan.md](docs/implementation-plan.md) | Stage order and how each task is verified |
| [docs/architecture.md](docs/architecture.md) | Module layout, async execution, design principles |
| [docs/database.md](docs/database.md) | Tables and columns |
| [docs/api.yaml](docs/api.yaml) | OpenAPI 3.1 — the only frontend/backend contract |
| [docs/open-questions.md](docs/open-questions.md) | Unresolved rules (OQ-xx) |
| [docs/PRD.md](docs/PRD.md) | Original product brief (background only) |

## Commands
```bash
DEV="docker compose -f docker-compose.dev.yml"
$DEV up --build                                    # dev: http://localhost:3000, backend :8080, mysql :3307
$DEV restart backend                               # backend has no hot reload
$DEV run --rm --no-deps backend ./mvnw -B verify   # backend unit + *IT (Testcontainers)
$DEV exec frontend sh -c "npm run lint && npm run typecheck && npm test && npm run build"
$DEV exec frontend npm run gen:api                 # after any docs/api.yaml change
docker compose up --build                          # demo / acceptance build
```

## Development Rules
1. Work on one backlog task (T-xxx) at a time; stay within its scope and the requirements.
2. Follow existing architecture and conventions; keep changes small and focused.
3. Never invent API fields or DB columns. Contract change = edit `docs/api.yaml` first,
   then run `gen:api` and commit the regenerated `frontend/src/lib/api/schema.d.ts` (CI checks it).
4. Schema change = a new Flyway migration `V{n}__desc.sql`, matching `docs/database.md`.
   Never edit an existing migration.
5. Business rules, validation, persistence and task execution live in the backend only;
   the frontend renders pages and UI state. Every user-data query filters by `user_id`.
6. Items marked `[待确认 OQ-xx]` use the documented temporary default; don't invent a different rule.
7. Add or update tests when behavior changes, and run the relevant ones before reporting.
8. Frontend: Next.js 16 has breaking changes — follow [frontend/AGENTS.md](frontend/AGENTS.md)
   and check `frontend/node_modules/next/dist/docs/` before using Next.js APIs.
9. Do not commit, push, deploy, or alter production data without approval.
   Commit style: `feat(T-xxx): ...`, `fix(T-xxx): ...`, `docs: ...`, `build: ...`, `ci: ...`.

## Definition of Done
- Implementation matches the task's acceptance criteria in `docs/backlog.md`.
- Relevant tests pass (backend `mvnw verify`, frontend lint/typecheck/test/build); CI stays green.
- Frontend and backend work together end to end (`docker compose up --build` still healthy).
- No known blocking errors remain.
- Docs updated if behavior, contract, schema or progress changed (backlog status, README progress).

## Task Completion Report
Always report:
- Task ID and files changed
- Features implemented
- Tests executed, with actual results (not assumed)
- Known limitations / deviations from docs
- Next recommended task
