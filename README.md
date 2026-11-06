# 小影 · 素材制作网站

选择模板 → 上传图片 → 后端异步执行"素材检查 → 生成 → 结果整理"三步 → 前端跟踪任务状态并展示结果。
Spring Boot 单体后端 + Next.js 前端 + MySQL，全部服务在 Docker Compose 中运行。

## 技术版本、项目结构与连接方式

| 层 | 选型 |
| --- | --- |
| 运行时 | Java 21（eclipse-temurin）、Node.js 24（node:24-alpine） |
| 后端 | Spring Boot 4.0.9、mybatis-spring-boot-starter 4.0.1、Flyway、Bean Validation、Actuator |
| 数据库 | MySQL 8.4（`utf8mb4_0900_ai_ci`，时间存 UTC） |
| 前端 | Next.js 16.4.5（App Router）、React 19.3、TypeScript 5（strict）、Tailwind CSS 4、Zustand 5 |
| 测试 | JUnit 5、Spring Boot Test、Testcontainers；Vitest、React Testing Library、MSW |
| 构建 | Docker Compose；Maven Wrapper（Maven 3.9.11）、npm（`package-lock.json`） |

```
.
├── backend/                 Spring Boot：auth、account、catalog、task（提交 / 查询 / pipeline）、simulation、storage、common
├── frontend/                Next.js：app/（页面）、features/（auth、account、catalog、upload、tasks）、lib/api/（client + 生成的类型）
├── docs/                    需求、设计与接口契约（api.yaml）
├── samples/                 演示上传样例
├── scripts/                 素材生成、冒烟与验收脚本
├── .github/workflows/ci.yml CI
├── docker-compose.yml       演示 / 验收
├── docker-compose.dev.yml   本地开发（源码挂载）
└── .env.example             环境变量示例
```

**连接方式**：浏览器只访问 frontend（`:3000`），Next.js rewrites 把 `/api/**`、`/files/**` 同源转发到 backend（`http://backend:8080`），因此无 CORS、会话 Cookie 正常携带；backend 通过 JDBC 连接 `mysql:3306`，并读写 `storage-data` 卷中的文件。backend 等待 MySQL healthy，frontend 等待 backend healthy。

## 构建、测试与启动

只需要 Docker（Compose v2），宿主机不需要 JDK、Maven 或 Node.js。

```bash
# 演示 / 验收：三个服务 healthy 后打开 http://localhost:3000（backend 也映射到 :8080 便于 curl）
cp .env.example .env          # 可选
docker compose up --build

# 本地开发：frontend 热更新，backend 修改后 restart
DEV="docker compose -f docker-compose.dev.yml"
$DEV up --build
$DEV restart backend

# 测试
$DEV run --rm --no-deps backend ./mvnw -B verify                                    # 后端单元测试 + *IT（Testcontainers）
$DEV exec frontend sh -c "npm run lint && npm run typecheck && npm test && npm run build"
$DEV exec frontend npm run gen:api                                                  # docs/api.yaml 变更后重新生成前端类型
```

CI（GitHub Actions）在每次 push / PR 时运行前端检查、后端 `mvnw verify`，并用 `docker compose up --build --wait` + `scripts/smoke-demo.sh` 冒烟。

## 配置项

[.env.example](.env.example) 只含示例值，没有真实凭据：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MYSQL_DATABASE` / `MYSQL_USER` / `MYSQL_PASSWORD` / `MYSQL_ROOT_PASSWORD` | `xiaoying` / `xiaoying` / `xiaoying` / `root` | MySQL 初始化与后端连接 |
| `FRONTEND_PORT` / `BACKEND_PORT` | `3000` / `8080` | 宿主机端口 |
| `MYSQL_PORT` | `3307` | 仅开发 compose 映射 MySQL |
| `IMAGE_TAG` | `latest` | 演示镜像 tag，用于回滚 |

后端配置在 [application.yml](backend/src/main/resources/application.yml)，均可用环境变量覆盖：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 开发库 `localhost:3307` | compose 中指向 `mysql:3306`，URL 须带 `connectionTimeZone=UTC` |
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev` / `demo`（演示 compose）/ `test` / `prod` |
| `APP_STORAGE_ROOT` | `/data/storage` | 上传与结果文件目录（数据卷） |
| `app.simulation.*` | 1500 / 3000 / 1500 ms，不失败 | 各阶段默认耗时与失败阶段；`/api/dev/simulation` 只在 dev / demo 注册 |
| `app.task.executor.pool-size` / `queue-capacity` | 2 / 100 | 执行线程池 |
| `app.auth.session-ttl` / `APP_AUTH_COOKIE_SECURE` | `7d` / `false` | 会话有效期；https 部署时设为 `true` |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | 5MB / 20MB | 全局上传上限（模板级上限在业务层校验） |

## 数据库初始化、模拟数据与素材

- 后端启动时 Flyway 自动执行 [V1__init_schema.sql](backend/src/main/resources/db/migration/V1__init_schema.sql)（建表）和 [V2__seed_data.sql](backend/src/main/resources/db/migration/V2__seed_data.sql)（种子），无需手动建库。表：`users`、`sessions`、`categories`、`templates`、`tasks`、`task_assets`，字段见 [docs/database.md](docs/database.md)。
- `docker compose down` 后再 `up` 数据保留（`mysql-data`、`storage-data` 卷）；`down -v` 清空并在下次启动重新初始化。
- 种子数据：3 个分类、4 个模板、2 个演示账户（数据库只存 BCrypt 哈希）：

| 模板 | 分类 | 张数 | 接受类型 | 单张上限 |
| --- | --- | --- | --- | --- |
| 旅行明信片 `demo-postcard` | 创意图片 | 2 | JPEG、PNG | 5 MB |
| 创意海报 `creative-poster` | 创意图片 | 1 | JPEG、PNG、WebP | 5 MB |
| 证件照 `portrait-id-photo` | 人像写真 | 1 | PNG | 1 MB |
| 节日拼图贺卡 `festival-collage` | 节日贺卡 | 3 | JPEG、PNG、WebP | 5 MB |

| 用户名 | 邮箱 | 密码 |
| --- | --- | --- |
| `demo1` | `demo1@example.com` | `xiaoying123` |
| `demo2` | `demo2@example.com` | `xiaoying123` |

- 素材：种子封面、预置结果图（`backend/src/main/resources/seed/`）和演示样例（[samples/](samples/README.md)）都已提交，无需额外准备。它们全部由 [scripts/gen-seed-assets.py](scripts/gen-seed-assets.py) 用 Pillow 程序绘制，不含第三方图片；修改素材时才需重新运行。

## 主要接口

完整契约见 [docs/api.yaml](docs/api.yaml)（OpenAPI 3.1，前端类型由它生成）。

| 方法 | 路径 | 登录 | 请求 | 成功响应 |
| --- | --- | --- | --- | --- |
| POST | `/api/auth/register` | — | `{username, email, password}` | 201 `User` + `Set-Cookie` |
| POST | `/api/auth/login` | — | `{username, password}` | 200 `User` + `Set-Cookie` |
| POST | `/api/auth/logout` | — | — | 204 |
| GET | `/api/auth/me` | ✓ | — | 200 `User` |
| PUT | `/api/account/password` | ✓ | `{currentPassword, newPassword}` | 204 |
| DELETE | `/api/account` | ✓ | `{confirmText: "DELETE"}` | 204 |
| GET | `/api/categories` | — | — | 200 `{items: Category[]}` |
| GET | `/api/templates?categoryId=` | — | — | 200 `{items: TemplateSummary[]}` |
| GET | `/api/templates/{templateId}` | — | — | 200 `TemplateDetail` |
| POST | `/api/tasks` | ✓ | multipart：`templateId` + `files`（按上传位顺序） | 202 `Task`（PENDING） |
| GET | `/api/tasks?templateId=` | ✓ | 不传 templateId 返回全部 | 200 `{items: Task[]}`，创建时间倒序 |
| GET | `/api/tasks/{taskId}` | ✓ | — | 200 `Task`；他人或不存在 404 |
| GET / PUT | `/api/dev/simulation` | — | `SimulationConfig` | 200；仅 dev / demo |
| GET | `/files/uploads/**`、`/files/results/**` | ✓ 本人 | — | 图片；他人 404 |

| 结构 | 字段 |
| --- | --- |
| `User` | `userId`、`username`、`email`、`createdAt` |
| `TemplateSummary` / `TemplateDetail` | `templateId`、`title`、`categoryId`、`coverUrl`；详情另有 `description`、`categoryName`、`requiredImageCount`、`acceptedMimeTypes`、`maxFileSizeBytes` |
| `Task` | `taskId`、`templateId`、`templateTitle`、`templateCoverUrl`、`status`、`stage`、`failedStage`、`failureCode`、`failureReason`、`resultUrl`、`inputAssets[{slotIndex, url}]`、`createdAt`、`updatedAt`、`startedAt`、`finishedAt` |
| `SimulationConfig` | `assetCheckMs`、`generatingMs`、`resultOrganizingMs`（0 ~ 60000）、`failStage`、`failReason` |
| `ErrorResponse` | `code`（机器可读）、`message`（可展示的中文）、`details`（如 `fieldErrors[{field, slotIndex?, code, message}]`） |

ID 为字符串，时间为 ISO-8601 UTC。

## 任务状态、阶段与后台执行

| status | stage | 前端卡片 |
| --- | --- | --- |
| `PENDING` | `null` | 等待处理 |
| `PROCESSING` | `ASSET_CHECK` → `GENERATING` → `RESULT_ORGANIZING` | 处理中 · 素材检查中 / 生成中 / 结果整理中 |
| `SUCCEEDED` | 最后阶段 | 制作完成 + 结果缩略图（`resultUrl` 非空） |
| `FAILED` | 失败时所处阶段 | 制作失败 · {阶段} · {原因}（`failedStage`、`failureCode`、`failureReason` 非空） |

- 阶段严格按顺序执行，某一阶段失败后不再执行后续阶段；`SUCCEEDED` / `FAILED` 是终态，不会再变化。
- 素材检查复核文件存在、大小和文件头类型；生成阶段只等待；结果整理把模板的预置结果图复制为任务专属文件（模拟生成）。

**后台执行**：

1. `POST /api/tasks` 校验模板、张数、大小和文件头类型 → 保存文件，在一个事务内创建 `PENDING` 任务与素材记录，并快照当前模拟配置 → 交给线程池 → **立即返回 202**。校验失败不留下任务或文件。
2. 线程池 `ThreadPoolTaskExecutor`：2 个工作线程、队列 100，队列满返回 503 `QUEUE_FULL`。不使用消息队列或 Redis。
3. `TaskPipeline` 在工作线程中依次执行三步：写入当前 `stage` → 按快照耗时等待 → 若快照指定本阶段失败则落 `FAILED`，否则执行本步逻辑；全部成功后写 `SUCCEEDED`。
4. 查询接口只读，不推进状态。前端每 2 s 查询未结束的任务，查询失败按退避重试并保留手动"重新查询"（查询异常不显示为制作失败）；终态后停止，离开页面或切换用户时取消全部请求。

## 演示

上传图片用 [samples/](samples/README.md)。模拟配置通过 `/api/dev/simulation` 调整，**只影响之后提交的任务**，不持久化：

```bash
SIM=http://localhost:3000/api/dev/simulation; H='Content-Type: application/json'
# 每阶段 10 s（观察多任务、刷新恢复）
curl -s -X PUT -H "$H" $SIM -d '{"assetCheckMs":10000,"generatingMs":10000,"resultOrganizingMs":10000,"failStage":null,"failReason":null}'
# 指定生成阶段失败（failStage 可选 ASSET_CHECK / GENERATING / RESULT_ORGANIZING）
curl -s -X PUT -H "$H" $SIM -d '{"assetCheckMs":1000,"generatingMs":3000,"resultOrganizingMs":1000,"failStage":"GENERATING","failReason":null}'
# 恢复默认
curl -s -X PUT -H "$H" $SIM -d '{"assetCheckMs":1500,"generatingMs":3000,"resultOrganizingMs":1500,"failStage":null,"failReason":null}'
```

1. **成功**：登录 `demo1` → 打开"旅行明信片"，两个上传位选 `samples/sample-1.jpg`、`sample-2.jpg` → 提交。右侧 History 立即出现"等待处理"卡片，依次显示三个阶段，约 6 s 后"制作完成"，点缩略图看大图。
2. **指定阶段失败**：设置 `failStage=GENERATING` 后提交，卡片显示"制作失败 · 生成阶段 · 模拟失败：生成阶段出错"。
3. **调整耗时 + 多任务**：每阶段设为 10 s，连续提交 3 个任务：各卡片独立推进，第 3 个因线程池只有 2 个线程先停在"等待处理"。
4. **刷新恢复**：处理中刷新页面或重开浏览器，卡片从后端重新加载并继续推进；用户菜单 →"我的记录"显示全部模板的任务。

`scripts/acceptance-demo.sh` 可自动执行以上流程以及用户隔离和账户设置的验证（需要 `curl`、`jq`、`python3`）。

## 用户身份与个人记录

| 项 | 设计 |
| --- | --- |
| 注册 / 登录 | 开放注册：用户名（3 ~ 20 位字母数字下划线）+ 邮箱 + 密码（8 ~ 64 位），注册即登录，邮箱不可修改；用户名 + 密码登录，失败统一提示 |
| 会话 | 服务端会话：随机令牌放在 `HttpOnly; SameSite=Lax` 的 `sid` Cookie，数据库只存令牌的 SHA-256；固定 7 天；密码 BCrypt |
| 未登录 | 只能浏览模板；提交、历史、我的记录、账户设置都需要登录 |
| 个人记录 | 所有任务查询按当前用户过滤，他人或不存在的任务一律 404；用户素材和结果图同样只返回本人文件 |
| 账户设置 | 修改密码需当前密码，成功后作废其他会话；删除账户需输入 `DELETE`，软删除用户、进行中任务标记为 `FAILED / ACCOUNT_DELETED`，数据保留但不可访问，同一邮箱可再注册 |
| 前端 | 退出 / 切换用户时取消在途请求、清空任务状态并丢弃迟到响应；任一接口 401 时同样清理并跳转登录 |

**演示**：`demo1` 提交任务后退出，用 `demo2` 登录：History 和"我的记录"都看不到 `demo1` 的任务，直接请求其任务或结果图返回 404。账户设置建议用新注册的临时账户演示：修改密码后旧密码不能登录；删除账户后自动退出且不能再登录。

## 完成情况与已知限制

**已完成**：PRD 全部必需功能（模板浏览、选图上传、异步三步任务、状态跟踪、历史与刷新恢复、用户身份与个人记录、账户设置、可访问性与 360px 小屏、测试、CI、README），以及 Docker Compose 一键启动。

**未完成**：

| 项 | 说明 |
| --- | --- |
| 视觉打磨（参考 UI 的暗色主题等） | 只有基础布局 |
| 服务重启时处理未完成任务 | 见已知限制 1 |
| 批量任务查询、阶段执行日志落库、三阶段步骤条 | 前端逐个任务轮询；阶段只写后端日志；只显示当前阶段文案 |
| 受理幂等、SSE / WebSocket、失败重试 / 重新制作、删除历史、下载结果、历史分页 | PRD 不要求。幂等因提交包含文件、重试代价高，留作扩展；轮询已满足当前规模 |

**已知限制**：

1. **服务重启边界**：后端停止或重启时，执行中和排队中的任务会停在 `PENDING` / `PROCESSING`，**重启后不会恢复执行、不会重试、也不会标记为失败**，前端会一直显示"处理中"。演示时不要在任务处理中重启后端；如需清理可删除该账户或 `docker compose down -v`。
2. 单实例：线程池和模拟配置都在进程内存中，模拟配置重启后回到默认值。
3. 历史列表不分页；没有登录限流；会话固定 7 天不续期。
4. 删除账户只做软删除、不可恢复；文件不做定期清理。
5. 结果图是模板的预置图片，与上传内容无关（模拟生成）。

## 实际投入与取舍

| 项 | 数据 |
| --- | --- |
| 日历时间（据 git 记录） | 2026-11-04 20:16 首个 commit → 2026-11-05 21:27 Compose 验收完成（UTC-8），约 25 小时 |
| 实际投入工时 | 12h |
| 时间分配 | 先做需求澄清与设计（需求规格、未确定问题、架构、数据库、API 契约），再按任务逐个实现，最后做 CI、Compose 验收与 README |

**取舍**：优先保证业务闭环、可测试性和数据隔离（查询异常与任务失败严格区分、终态不可覆盖、用户文件访问控制），把 Compose 一键启动提前到工程骨架阶段；视觉打磨、重启处理、批量查询、阶段日志、步骤条未做，重启边界见上方已知限制。

## 部署与回滚

目标环境为单机 Docker Compose。

```bash
# 部署：用新 tag 构建，保留上一个版本的镜像用于回滚
git checkout <commit 或 tag>
IMAGE_TAG=<新版本> docker compose up -d --build --wait
docker compose ps                       # 三个服务都应为 healthy

# 回滚：切回上一个镜像 tag，不重新构建
docker image ls 'xiaoying-*'
IMAGE_TAG=<上一个版本> docker compose up -d --no-build --wait
```

数据在 `mysql-data`、`storage-data` 卷中，与镜像无关，升级和回滚不丢数据。Flyway 迁移只增不改且向后兼容（只加表 / 可空列），回滚到旧镜像时旧代码仍能使用新 Schema；需要撤销 Schema 变更时写新的前向迁移。升级前备份：

```bash
docker compose exec mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' > backup.sql
```

生产环境使用 `prod` profile（不注册 `/api/dev/simulation`），https 下设置 `APP_AUTH_COOKIE_SECURE=true`，凭据只通过环境变量提供。

## AI 使用说明

本项目使用 [Claude Code](https://claude.com/claude-code) 辅助完成，AI 参与的 commit 带 `Co-Authored-By: Claude` 标注。

**工作流**

1. **拆解任务**：把 [PRD](docs/PRD.md) 交给 AI 拆为需求规格、未确定问题（每个给选项和临时默认）、带依赖和完成定义的任务清单、架构、数据库设计、API 契约和实施计划。业务决定由人确认后写回文档，AI 不替需求方做决定。
2. **提供上下文**：[AGENTS.md](AGENTS.md) 规定文档的权威顺序和开发规则：一次只做一个任务；不得自行发明 API 字段或数据库列（先改契约再生成类型）；业务规则只放在后端；未经验证不得标记完成；未经批准不提交。
3. **生成 / 修改代码**：每个任务使用同一提示模板：执行前阅读文档和代码、确认依赖、列出改动文件与测试计划；执行中按契约实现并补充正常 / 无效输入 / 未授权测试。
4. **验证与修正**：AI 在 Docker 中实际运行测试、lint、build 并报告真实结果和未验证项；人审查 diff、在浏览器中按演示步骤操作、确认 CI 通过后才提交。

**例子：大文件上传经代理是否被截断**：规划阶段 AI 指出，最大模板一次提交 3 × 5 MB，请求要经过 Next.js rewrites 转发，可能被截断，且全局上限必须 ≥ 15 MB。实现时把请求上限设为 20 MB，并用集成测试断言"全局上限 ≥ 最大张数 × 单张上限"；交付验收时又在验收脚本中加入约 14 MB 请求体经 `:3000` 提交的用例，在全新 compose 环境中确认任务成功、服务端保存的字节数与原文件一致，人工复核输出后才标记完成。
