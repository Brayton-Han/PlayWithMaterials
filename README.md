# 小影 · 素材制作网站

选择模板 → 上传图片 → 后端异步执行"素材检查 → 生成 → 结果整理"三步 → 前端跟踪任务状态并展示结果。
Spring Boot 单体后端 + Next.js 前端 + MySQL，全部服务在 Docker Compose 中运行。

> **当前进度**：P0 全部任务（T-001 ~ T-062）已完成，`docker compose up --build` 可完成完整演示闭环；T-061 README 与交付整理已完成；P1（T-070 ~ T-075）未开始。逐项状态见 [完成情况](#完成情况与已知限制) 与 [docs/backlog.md](docs/backlog.md)。

## 目录

- [小影 · 素材制作网站](#小影--素材制作网站)
  - [目录](#目录)
  - [交付检查清单（F-16）](#交付检查清单f-16)
  - [快速开始](#快速开始)
  - [演示指南](#演示指南)
    - [0. 准备：curl 调整模拟配置](#0-准备curl-调整模拟配置)
    - [1. 未登录浏览（F-01、F-02）](#1-未登录浏览f-01f-02)
    - [2. 成功流程（F-03 ~ F-09）](#2-成功流程f-03--f-09)
    - [3. 调整耗时 + 多任务（F-08、F-10）](#3-调整耗时--多任务f-08f-10)
    - [4. 指定阶段失败（F-08、F-09）](#4-指定阶段失败f-08f-09)
    - [5. 查询异常 ≠ 任务失败（F-09、R-07.2）](#5-查询异常--任务失败f-09r-072)
    - [6. 刷新恢复与我的记录（F-10、F-11）](#6-刷新恢复与我的记录f-10f-11)
    - [7. 用户隔离（F-12）](#7-用户隔离f-12)
    - [8. 账户设置（F-28）](#8-账户设置f-28)
    - [用 curl 走一遍任务接口（可选）](#用-curl-走一遍任务接口可选)
    - [自动化验收脚本](#自动化验收脚本)
  - [用户身份与个人记录](#用户身份与个人记录)
  - [任务状态、阶段与后台执行](#任务状态阶段与后台执行)
    - [状态与阶段](#状态与阶段)
    - [后台执行方式](#后台执行方式)
  - [主要接口](#主要接口)
    - [主要数据结构](#主要数据结构)
    - [常见错误码](#常见错误码)
  - [配置项](#配置项)
  - [数据库、种子数据与素材](#数据库种子数据与素材)
  - [项目结构与技术栈](#项目结构与技术栈)
  - [本地开发与测试](#本地开发与测试)
  - [CI](#ci)
  - [部署与回滚](#部署与回滚)
  - [完成情况与已知限制](#完成情况与已知限制)
    - [已完成](#已完成)
    - [未完成（本期范围外或 P1 未开始）](#未完成本期范围外或-p1-未开始)
    - [已知限制](#已知限制)
  - [实际投入](#实际投入)
  - [AI 使用说明](#ai-使用说明)
  - [复用的脚手架、开源代码与素材来源](#复用的脚手架开源代码与素材来源)
  - [交付物与验收 commit](#交付物与验收-commit)
  - [文档](#文档)

## 交付检查清单（F-16）

对照 [requirements.md F-16](docs/requirements.md#f-16-readme-与交付材料p0)（R-13.2 / R-13.1）：

- [x] 技术版本、项目结构、构建 / 测试 / 启动命令、前后端与数据库连接方式 → [项目结构与技术栈](#项目结构与技术栈)、[本地开发与测试](#本地开发与测试)
- [x] 配置项与无真实凭据的示例（`.env.example`）、数据库初始化、模拟数据与素材准备 → [配置项](#配置项)、[数据库、种子数据与素材](#数据库种子数据与素材)
- [x] 主要接口及请求 / 响应字段；任务状态、阶段与后台执行方式 → [主要接口](#主要接口)、[任务状态、阶段与后台执行](#任务状态阶段与后台执行)
- [x] 演示：成功、指定阶段失败、调整耗时、多任务、刷新恢复 → [演示指南](#演示指南)
- [x] 用户身份与个人记录设计及演示方法 → [用户身份与个人记录](#用户身份与个人记录)
- [x] 已完成 / 未完成项、已知限制、实际投入（含重启恢复等边界说明）→ [完成情况与已知限制](#完成情况与已知限制)、[实际投入](#实际投入)
- [x] 部署与回滚基本步骤 → [部署与回滚](#部署与回滚)
- [x] AI 使用说明（工作流、一个具体例子）→ [AI 使用说明](#ai-使用说明)
- [x] 复用的脚手架 / 开源代码 / 素材来源 → [复用的脚手架、开源代码与素材来源](#复用的脚手架开源代码与素材来源)
- [x] 验收 commit 标注 → [交付物与验收 commit](#交付物与验收-commit)
- [x] 交付物：前后端源码、锁文件、DB 结构与初始化数据、CI 配置 → [交付物与验收 commit](#交付物与验收-commit)

## 快速开始

前置条件：只需要 Docker（Docker Desktop 或 Docker Engine + Compose v2）。宿主机不需要 JDK、Maven 或 Node.js。

```bash
cp .env.example .env          # 可选，按需修改端口 / 密码；不复制也能用默认值
docker compose up --build     # 首次构建约数分钟；三个服务 healthy 后打开 http://localhost:3000
```

| 服务 | 地址 | 说明 |
| --- | --- | --- |
| frontend | http://localhost:3000 | 浏览器只访问这里；`/api/**`、`/files/**` 由 Next.js rewrites 同源转发给后端 |
| backend | http://localhost:8080 | 也映射到宿主机，方便 curl 调试；`demo` profile |
| mysql | 不映射到宿主机 | 数据在 `mysql-data` 卷 |

- 首次启动时后端用 Flyway 自动建表并写入种子数据（分类、模板、演示账户）。
- `docker compose down` 后再 `up`，账户、任务、上传素材和结果图都还在（`mysql-data`、`storage-data` 卷）；`docker compose down -v` 清空数据，下次启动重新初始化。
- 端口被占用时在 `.env` 中修改 `FRONTEND_PORT` / `BACKEND_PORT`。

**演示账户**（种子数据，数据库只保存 BCrypt 哈希）：

| 用户名 | 邮箱 | 密码 |
| --- | --- | --- |
| `demo1` | `demo1@example.com` | `xiaoying123` |
| `demo2` | `demo2@example.com` | `xiaoying123` |

## 演示指南

以下步骤对应 [implementation-plan.md 验收演示脚本](docs/implementation-plan.md#m5-交付) 1 ~ 8，另一个人按顺序执行即可完成验收。上传图片用仓库里的 [samples/](samples/README.md)。

### 0. 准备：curl 调整模拟配置

模拟配置控制每个阶段的耗时和指定失败阶段，只在 `dev` / `demo` profile 下存在，不需要登录。**修改只影响之后提交的任务**（创建时快照），不持久化，后端重启后回到默认值（每阶段 1.5 s / 3 s / 1.5 s，不失败）。

```bash
SIM=http://localhost:3000/api/dev/simulation   # 也可以直连 :8080
H='Content-Type: application/json'

curl -s $SIM                                     # 查看当前配置

# 每阶段 10 s，便于观察多任务和刷新恢复
curl -s -X PUT -H "$H" $SIM -d '{"assetCheckMs":10000,"generatingMs":10000,"resultOrganizingMs":10000,"failStage":null,"failReason":null}'

# 指定在生成阶段失败（failStage 可选 ASSET_CHECK / GENERATING / RESULT_ORGANIZING；failReason 为 null 时用默认文案）
curl -s -X PUT -H "$H" $SIM -d '{"assetCheckMs":1000,"generatingMs":3000,"resultOrganizingMs":1000,"failStage":"GENERATING","failReason":null}'

# 恢复默认
curl -s -X PUT -H "$H" $SIM -d '{"assetCheckMs":1500,"generatingMs":3000,"resultOrganizingMs":1500,"failStage":null,"failReason":null}'
```

PUT 是整体替换，五个字段都必须给出；耗时范围 0 ~ 60000 ms，超出返回 400 `VALIDATION_FAILED`。

### 1. 未登录浏览（F-01、F-02）

打开 http://localhost:3000：模板列表来自后端接口；点分类标签（全部 / 创意图片 / 人像写真 / 节日贺卡）筛选；点任意卡片进入详情页，可以看到素材要求，上传区显示"登录后即可制作"，没有上传位。

### 2. 成功流程（F-03 ~ F-09）

1. 右上角登录 `demo1` / `xiaoying123`。
2. 打开"旅行明信片"（需要 2 张 JPEG / PNG），在两个上传位选 `samples/sample-1.jpg`、`sample-2.jpg`；可以点"替换"、"移除"（移除后提交按钮禁用）。
   - 可选：选 `samples/sample.webp` 或 `fake-image.jpg` 看类型校验；选 `over-5mb.png` 看大小校验。
3. 提交：按钮显示"上传中 → 提交中"，右侧 History 立即出现一张"等待处理"卡片，上传区清空。
4. 卡片依次显示"处理中 · 素材检查中 / 生成中 / 结果整理中"，约 6 s 后变为"制作完成"并显示结果缩略图，点击可看大图（Esc 关闭）。

### 3. 调整耗时 + 多任务（F-08、F-10）

1. 用第 0 步命令把每阶段设为 10 s。
2. 在同一模板或不同模板连续提交 3 个任务。
3. 观察三张卡片各自推进：线程池默认 2 个工作线程，第 3 个任务会先停在"等待处理"，前两个任务之一结束后才开始（OQ-12 临时默认）。每张卡片只显示自己的阶段和结果。

### 4. 指定阶段失败（F-08、F-09）

1. 用第 0 步命令设置 `failStage=GENERATING`。
2. 再提交一个任务：卡片显示"制作失败 · 生成阶段 · 模拟失败：生成阶段出错"，不显示结果图。
3. 演示结束后恢复默认配置。

### 5. 查询异常 ≠ 任务失败（F-09、R-07.2）

1. 把耗时设为每阶段 10 s，提交一个任务。
2. 任务处理中执行 `docker compose stop backend`：卡片保留最后已知状态，叠加显示"状态获取失败"和"重新查询"，**不会**变成"制作失败"。
3. `docker compose start backend`，等后端 healthy 后点"重新查询"，查询恢复。

> 注意（OQ-13）：后端停止会中断正在执行的任务，这个任务重启后会一直停在"处理中"（见 [已知限制](#已知限制)）。要看到任务继续推进到终态，请在后端恢复后**新提交**一个任务，或观察停机前已经完成的任务。

### 6. 刷新恢复与我的记录（F-10、F-11）

1. 每阶段 10 s，提交一个任务，处理中直接刷新页面（或关闭浏览器再打开）：卡片从后端重新加载，继续自动推进到终态。
2. 右上角用户菜单 →"我的记录"（`/records`）：显示全部模板的任务，每条带模板标题和封面，未结束的任务同样自动跟踪。

### 7. 用户隔离（F-12）

退出登录，用 `demo2` 登录：详情页 History 和"我的记录"都看不到 `demo1` 的任务。`demo2` 直接请求 `demo1` 的任务（`GET /api/tasks/{id}`）或其结果图返回 404。

### 8. 账户设置（F-28）

建议先注册一个临时账户再演示，避免影响 `demo1` / `demo2` 的复用。

1. 右上角"注册"：用户名 3 ~ 20 位字母数字下划线，密码 8 ~ 64 位。
2. 用户菜单 →"账户设置"（`/account`）：显示用户名、邮箱（只读）。
3. 修改密码：当前密码错误时提示"当前密码不正确"；成功后提示"密码已修改"，当前会话保持登录，其他会话失效；退出后旧密码不能登录，新密码可以。
4. 把耗时设为 10 s，提交一个任务；处理中回到 `/account` →"删除账户"，输入 `DELETE`（区分大小写）后确认：自动退出并回到首页，再用该账户登录会被拒绝；该处理中任务被标记为 `FAILED / ACCOUNT_DELETED`。
5. 用同一邮箱 + 新用户名重新注册：成功，且"我的记录"为空。原用户名不能再注册（409，OQ-31 临时默认）。

### 用 curl 走一遍任务接口（可选）

```bash
B=http://localhost:3000
curl -s -c jar.txt -H 'Content-Type: application/json' \
  -d '{"username":"demo1","password":"xiaoying123"}' $B/api/auth/login

# 提交（202，立即返回 PENDING 任务）
curl -s -b jar.txt $B/api/tasks -F templateId=creative-poster -F "files=@samples/sample-1.jpg;type=image/jpeg"

curl -s -b jar.txt $B/api/tasks/<taskId>                 # 单个任务（只读，不推进状态）
curl -s -b jar.txt "$B/api/tasks?templateId=creative-poster"   # 某模板历史
curl -s -b jar.txt $B/api/tasks                          # 我的全部记录
```

### 自动化验收脚本

上面的流程有两个脚本可以自动执行（需要 `curl`、`jq`；`acceptance-demo.sh` 还需要 `python3`）：

```bash
docker compose up -d --build --wait

# 冒烟（CI docker job 也运行它）：simulation 接口、匿名 401、登录、模板列表、成功任务 + 结果图、GENERATING 失败
scripts/smoke-demo.sh

# 完整验收：成功、RESULT_ORGANIZING 失败、多任务并行、新会话恢复、我的记录、用户隔离、
# 约 15 MB 请求体经 :3000 代理、修改密码、删除账户、同一邮箱再注册；然后验证 down → up 后数据保留
scripts/acceptance-demo.sh run state.json
docker compose down && docker compose up -d --wait
scripts/acceptance-demo.sh persisted state.json
```

脚本会以 `demo1` 创建若干任务（冒烟 2 个、验收 5 个），修改密码 / 删除账户使用脚本临时注册的账户，结束后恢复模拟配置。

## 用户身份与个人记录

| 项 | 设计 |
| --- | --- |
| 注册 | 开放自助注册：用户名（`^[A-Za-z0-9_]{3,20}$`）+ 邮箱 + 密码（8 ~ 64 位）；"确认密码"只在前端校验。注册成功即登录。邮箱注册后不可修改 |
| 登录 | 用户名 + 密码；失败统一返回 `INVALID_CREDENTIALS`，不区分用户不存在 / 密码错 / 账户已删除 |
| 会话 | 服务端会话：随机 32 字节令牌放在 `sid` Cookie（`HttpOnly; SameSite=Lax`），数据库只存令牌的 SHA-256；固定 7 天有效，不续期（OQ-28 临时默认） |
| 密码存储 | BCrypt |
| 未登录 | 只能浏览模板列表和详情；提交任务、历史、我的记录、账户设置都需要登录 |
| 修改密码 | 需要当前密码；成功后保留当前会话，作废该用户其他全部会话 |
| 删除账户 | 输入 `DELETE` 确认；同一事务内软删除用户、把进行中任务标记为 `FAILED / ACCOUNT_DELETED`、删除全部会话。**数据保留**（用户、任务、素材记录和文件都不物理删除，OQ-26），但任何接口都读不到；素材和结果图链接随之失效。同一邮箱可以再注册（新账户看不到旧数据），原用户名不能再用 |
| 个人记录 | 所有任务查询都按当前登录用户过滤；他人或不存在的任务一律 404（不暴露存在性）；用户上传的素材和结果图同样需要登录且只返回本人文件（`Cache-Control: private, no-store`）。种子封面公开 |
| 前端 | 退出 / 切换用户时取消在途请求、清空任务 store、丢弃迟到的响应，新用户界面不会出现上一个用户的任务；任一接口返回 401 时执行同样的清理并跳转登录 |
| CSRF | 依赖 `SameSite=Lax`，所有写接口都不是 GET |

演示方法见 [演示指南第 7、8 步](#7-用户隔离f-12)。设计细节见 [architecture.md §4](docs/architecture.md#4-身份认证与数据访问边界)。

## 任务状态、阶段与后台执行

### 状态与阶段

| status | stage | 前端卡片 | 说明 |
| --- | --- | --- | --- |
| `PENDING` | `null` | 等待处理 | 已受理，在线程池队列中 |
| `PROCESSING` | `ASSET_CHECK` | 处理中 · 素材检查中 | 素材记录数 = 模板要求；每个文件存在、大小一致、文件头类型仍合法 |
| `PROCESSING` | `GENERATING` | 处理中 · 生成中 | 只等待（模拟生成） |
| `PROCESSING` | `RESULT_ORGANIZING` | 处理中 · 结果整理中 | 把模板的预置结果图复制为任务专属文件 `results/{taskId}/{uuid}.png` |
| `SUCCEEDED` | 最后阶段 | 制作完成 + 结果缩略图 | `resultUrl`、`finishedAt` 非空 |
| `FAILED` | 失败时所处阶段 | 制作失败 · {阶段} · {原因} | `failedStage`、`failureCode`、`failureReason`、`finishedAt` 非空 |

- 阶段严格按 `ASSET_CHECK → GENERATING → RESULT_ORGANIZING` 顺序；某一阶段失败后，后续阶段不执行。
- `SUCCEEDED` / `FAILED` 是终态，不会再变化（所有状态写入都是带 `WHERE status IN (...)` 的条件更新）。
- `failureCode`：`SIMULATED_FAILURE`（模拟配置指定）、`ASSET_MISSING` / `ASSET_INVALID`（素材检查）、`RESULT_WRITE_FAILED`（结果整理）、`ACCOUNT_DELETED`（删除账户时被废弃）、`INTERNAL_ERROR`（未预期异常兜底，原因只给通用文案，堆栈只写日志）。
- 结果图是模板的预置图片，不做真实图片处理（OQ-08 临时默认）。

### 后台执行方式

1. `POST /api/tasks`：校验模板、张数、每个文件的大小和文件头魔数类型 → 保存文件、在一个事务内创建任务（`PENDING`）和素材记录，并把当前模拟配置快照到 `tasks.sim_config` → 派发给线程池 → **立即返回 202**，不等待制作完成。任何校验失败都不留下任务或文件。
2. 线程池：`ThreadPoolTaskExecutor`，核心 = 最大 = 2 个工作线程（`task-exec-N`），队列 100；队列满时撤销任务和文件，返回 503 `QUEUE_FULL`（OQ-12 临时默认）。不依赖消息队列或 Redis。
3. `TaskPipeline` 在工作线程中按顺序执行三个步骤：每步开始时把 `stage` 写入数据库，按快照 sleep 该阶段耗时，若快照的 `failStage` 是本阶段则失败，否则执行本步逻辑；全部成功后写 `SUCCEEDED + resultUrl`。
4. 查询接口（`GET /api/tasks*`）只读，不推进任何状态。
5. 前端 `useTaskTracker`：每 2 s 查询范围内未结束的任务（每个任务最多一个在途请求）；查询失败按 4 s / 8 s / 16 s / 30 s 退避，并保留手动"重新查询"；终态或 404 后停止；没有未结束任务时不发请求。离开页面或切换用户时取消全部请求。

详细时序图见 [architecture.md §5](docs/architecture.md#5-核心数据流)。

## 主要接口

完整契约：[docs/api.yaml](docs/api.yaml)（OpenAPI 3.1，前端类型由它生成）。浏览器经 `:3000` 同源访问；curl 也可直连 `:8080`。

| 方法 | 路径 | 登录 | 说明 | 成功响应 |
| --- | --- | --- | --- | --- |
| POST | `/api/auth/register` | — | `{username, email, password}` | 201 `User` + `Set-Cookie` |
| POST | `/api/auth/login` | — | `{username, password}` | 200 `User` + `Set-Cookie` |
| POST | `/api/auth/logout` | — | 删除当前会话 | 204 |
| GET | `/api/auth/me` | ✓ | 当前用户 | 200 `User` |
| PUT | `/api/account/password` | ✓ | `{currentPassword, newPassword}` | 204 |
| DELETE | `/api/account` | ✓ | `{confirmText: "DELETE"}` | 204 + 清除 Cookie |
| GET | `/api/categories` | — | 一级分类 | 200 `{items: Category[]}` |
| GET | `/api/templates?categoryId=` | — | 模板卡片，可按分类筛选 | 200 `{items: TemplateSummary[]}` |
| GET | `/api/templates/{templateId}` | — | 模板详情与素材要求 | 200 `TemplateDetail` |
| POST | `/api/tasks` | ✓ | multipart：`templateId` + `files`（按上传位顺序） | 202 `Task` + `Location` |
| GET | `/api/tasks?templateId=` | ✓ | 当前用户的任务，按创建时间倒序；不传 templateId 返回全部模板 | 200 `{items: Task[]}` |
| GET | `/api/tasks/{taskId}` | ✓ | 单个任务（只读） | 200 `Task` |
| GET / PUT | `/api/dev/simulation` | — | 模拟配置，仅 dev / demo profile；prod 下 404 | 200 `SimulationConfig` |
| GET | `/files/seed/**` | — | 种子封面 | 图片 |
| GET | `/files/uploads/**`、`/files/results/**` | ✓ 本人 | 用户素材、结果图；匿名 401，他人 404 | 图片 |
| GET | `/actuator/health` | — | 健康检查 | 200 `{status: "UP"}` |

### 主要数据结构

| 结构 | 字段 |
| --- | --- |
| `User` | `userId`、`username`、`email`、`createdAt` |
| `Category` | `categoryId`、`name`、`sortOrder` |
| `TemplateSummary` | `templateId`、`title`、`categoryId`、`coverUrl` |
| `TemplateDetail` | 上述字段 + `description`、`categoryName`、`requiredImageCount`（恰好 N 张）、`acceptedMimeTypes`（`image/jpeg` / `image/png` / `image/webp` 子集）、`maxFileSizeBytes` |
| `Task` | `taskId`、`templateId`、`templateTitle`、`templateCoverUrl`、`status`、`stage`、`failedStage`、`failureCode`、`failureReason`、`resultUrl`、`inputAssets[{slotIndex, url}]`、`createdAt`、`updatedAt`、`startedAt`、`finishedAt` |
| `SimulationConfig` | `assetCheckMs`、`generatingMs`、`resultOrganizingMs`（0 ~ 60000）、`failStage`（阶段或 `null`）、`failReason`（≤ 200 字或 `null`） |
| `ErrorResponse` | `code`（稳定的机器可读码）、`message`（可直接展示的中文）、`details`（如 `fieldErrors[{field, slotIndex?, code, message}]`、`expected` / `actual`） |

约定：ID 以字符串输出；时间为 ISO-8601 UTC（毫秒）；列表统一为 `{items: [...]}`；文件 URL 是站内相对路径。

### 常见错误码

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 参数格式不符，`details.fieldErrors` 给出字段 |
| 400 | `IMAGE_COUNT_MISMATCH` | 图片张数 ≠ 模板要求 |
| 400 | `ASSET_VALIDATION_FAILED` | 文件为空 / 超过模板上限 / 类型不接受，带 `slotIndex` |
| 400 | `CURRENT_PASSWORD_INCORRECT` / `CONFIRM_TEXT_MISMATCH` | 修改密码当前密码错 / 删除确认文字不是 `DELETE` |
| 401 | `UNAUTHENTICATED` / `INVALID_CREDENTIALS` | 未登录或会话失效 / 登录失败 |
| 404 | `TEMPLATE_NOT_FOUND` / `TASK_NOT_FOUND` / `NOT_FOUND` | 模板不存在 / 任务不存在或不属于当前用户 / 路由不存在 |
| 409 | `USERNAME_TAKEN` / `EMAIL_TAKEN` | 注册冲突 |
| 413 | `PAYLOAD_TOO_LARGE` | 单文件 > 5 MB 或请求体 > 20 MB |
| 503 | `QUEUE_FULL` | 执行队列已满，任务未创建 |

## 配置项

[.env.example](.env.example) 只含示例值，没有真实凭据。复制为 `.env` 后由 `docker compose` 自动读取：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MYSQL_DATABASE` / `MYSQL_USER` / `MYSQL_PASSWORD` / `MYSQL_ROOT_PASSWORD` | `xiaoying` / `xiaoying` / `xiaoying` / `root` | MySQL 初始化与后端连接 |
| `FRONTEND_PORT` / `BACKEND_PORT` | `3000` / `8080` | 宿主机端口 |
| `MYSQL_PORT` | `3307` | 仅开发 compose 映射 MySQL |
| `IMAGE_TAG` | `latest` | 演示镜像 tag，用于回滚 |

后端配置在 [application.yml](backend/src/main/resources/application.yml)，都可以用环境变量覆盖（Spring relaxed binding）：

| 配置 | 环境变量 | 默认 | 说明 |
| --- | --- | --- | --- |
| `spring.datasource.url` / `username` / `password` | `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 开发库 `localhost:3307` | compose 中指向 `mysql:3306`；URL 必须带 `connectionTimeZone=UTC` |
| `spring.profiles.active` | `SPRING_PROFILES_ACTIVE` | `dev` | `dev`（开发）/ `demo`（演示 compose）/ `test` / `prod` |
| `app.storage.root` | `APP_STORAGE_ROOT` | `/data/storage` | 上传与结果文件根目录（数据卷） |
| `app.simulation.asset-check-ms` / `generating-ms` / `result-organizing-ms` | `APP_SIMULATION_ASSET_CHECK_MS` 等 | 1500 / 3000 / 1500 | 各阶段默认耗时 |
| `app.simulation.fail-stage` / `fail-reason` | `APP_SIMULATION_FAIL_STAGE` 等 | 空 | 默认不失败 |
| `app.simulation.dev-api-enabled` | — | 仅 dev / demo 为 `true` | 是否注册 `/api/dev/simulation` |
| `app.task.executor.pool-size` / `queue-capacity` | `APP_TASK_EXECUTOR_POOL_SIZE` / `APP_TASK_EXECUTOR_QUEUE_CAPACITY` | 2 / 100 | 执行线程池 |
| `app.auth.session-ttl` | — | `7d` | 会话有效期 |
| `app.auth.cookie-secure` | `APP_AUTH_COOKIE_SECURE` | `false` | 部署到 https 时设为 `true` |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | — | 5MB / 20MB | 全局上传上限（模板级上限在业务层校验） |

前端只有一个构建期参数 `BACKEND_INTERNAL_URL`（rewrites 目标，compose 中为 `http://backend:8080`，开发环境同样指向 backend 容器）。

## 数据库、种子数据与素材

- **初始化**：后端启动时 Flyway 自动执行 [V1__init_schema.sql](backend/src/main/resources/db/migration/V1__init_schema.sql)（建表）和 [V2__seed_data.sql](backend/src/main/resources/db/migration/V2__seed_data.sql)（种子），无需手动建库。已执行的迁移不会重复执行；Schema 变更只新增 `V{n}__*.sql`，不修改已有迁移。
- **表**：`users`、`sessions`、`categories`、`templates`、`tasks`、`task_assets`，字符集 `utf8mb4_0900_ai_ci`，时间存 UTC。字段说明见 [docs/database.md](docs/database.md)。
- **种子数据**：3 个分类、4 个模板（覆盖 1 / 2 / 3 张、不同类型与上限）、2 个演示账户。

| 模板 | 分类 | 张数 | 接受类型 | 单张上限 |
| --- | --- | --- | --- | --- |
| 旅行明信片 `demo-postcard` | 创意图片 | 2 | JPEG、PNG | 5 MB |
| 创意海报 `creative-poster` | 创意图片 | 1 | JPEG、PNG、WebP | 5 MB |
| 证件照 `portrait-id-photo` | 人像写真 | 1 | PNG | 1 MB |
| 节日拼图贺卡 `festival-collage` | 节日贺卡 | 3 | JPEG、PNG、WebP | 5 MB |

- **文件布局**（`storage-data` 卷）：`uploads/yyyy/MM/{uuid}.{ext}` 用户素材，`results/{taskId}/{uuid}.{ext}` 结果图，`tmp/` 提交时的暂存文件。文件名由服务端生成（UUID + 按文件头类型决定的扩展名）。种子封面和预置结果图随 jar 打包，位于 `backend/src/main/resources/seed/`。
- **素材准备**：种子图和 `samples/` 演示样例已提交到仓库，无需额外准备；只有修改素材时才需要运行 `python3 scripts/gen-seed-assets.py`（需要 Pillow）。样例说明见 [samples/README.md](samples/README.md)。

## 项目结构与技术栈

```
.
├── backend/                 Spring Boot 后端，Dockerfile（多阶段构建）
│   └── src/main/java/com/xiaoying/
│       ├── auth/            注册、登录、会话、鉴权
│       ├── account/         修改密码、删除账户
│       ├── catalog/         分类与模板
│       ├── task/            任务提交、查询、pipeline（三个步骤与执行器）
│       ├── simulation/      模拟配置与 /api/dev/simulation
│       ├── storage/         文件存储与 /files/** 访问控制
│       └── common/          统一错误、Web 与 MyBatis 配置
├── frontend/                Next.js 前端，Dockerfile（standalone 构建）
│   └── src/
│       ├── app/             页面：/、/templates/[id]、/records、/account、/login、/register
│       ├── features/        auth、account、catalog、upload、tasks（store + 跟踪调度器 + 卡片）
│       ├── components/      通用组件
│       └── lib/api/         API client 与由 api.yaml 生成的 schema.d.ts
├── docs/                    需求、设计与契约
├── samples/                 演示上传样例（合法 / 超大 / 伪装类型）
├── scripts/                 素材生成、冒烟与验收脚本
├── .github/workflows/ci.yml CI
├── docker-compose.yml       演示 / 验收：mysql + backend + frontend（构建镜像）
├── docker-compose.dev.yml   本地开发：源码挂载
└── .env.example             环境变量示例
```

| 层 | 选型 |
| --- | --- |
| 运行时 | Java 21（eclipse-temurin）、Node.js 24（node:24-alpine） |
| 后端 | Spring Boot 4.0.9、mybatis-spring-boot-starter 4.0.1、Flyway、Bean Validation、Actuator |
| 数据库 | MySQL 8.4（`utf8mb4_0900_ai_ci`，时间存 UTC） |
| 前端 | Next.js 16.4.5（App Router）、React 19.3、TypeScript 5（strict）、Tailwind CSS 4、Zustand 5 |
| 测试 | JUnit 5、Spring Boot Test、Testcontainers；Vitest、React Testing Library、MSW |
| 构建 / 运行 | Docker Compose；Maven Wrapper（Maven 3.9.11）、npm（`package-lock.json`） |

**连接方式**：

- 浏览器 → frontend（`:3000`）。Next.js rewrites 把 `/api/**`、`/files/**` 转发到 `BACKEND_INTERNAL_URL`（`http://backend:8080`），同源，无 CORS，会话 Cookie 正常携带。
- backend → MySQL：JDBC（`DB_URL`），compose 内网 `mysql:3306`；backend 用 `depends_on: service_healthy` 等待 MySQL 就绪，frontend 等待 backend healthy。
- 文件：backend 读写 `storage-data` 卷（`/data/storage`）。

## 本地开发与测试

**所有服务都在 Docker 中运行**，宿主机不需要安装 JDK、Maven 或 Node.js。

```bash
docker compose -f docker-compose.dev.yml up --build     # http://localhost:3000
```

| 服务 | 地址 | 代码修改后 |
| --- | --- | --- |
| frontend（`next dev`） | http://localhost:3000 | 自动热更新 |
| backend（`spring-boot:run`，dev profile） | http://localhost:8080 | `docker compose -f docker-compose.dev.yml restart backend` |
| mysql | 宿主机 `localhost:3307`（`MYSQL_PORT`） | — |

```bash
DEV="docker compose -f docker-compose.dev.yml"

# 后端：单元测试 + 集成测试（*IT 用 Testcontainers，经宿主机 docker.sock 启动 MySQL）
$DEV run --rm --no-deps backend ./mvnw -B verify

# 前端：lint、类型检查、测试、构建
$DEV exec frontend sh -c "npm run lint && npm run typecheck && npm test && npm run build"

# docs/api.yaml 变更后重新生成前端类型（CI 会检查是否已提交）
$DEV exec frontend npm run gen:api

# 修改 package.json 后：重建镜像并刷新容器内的 node_modules 卷
$DEV up -d --build --renew-anon-volumes frontend

# 查看日志 / 停止
$DEV logs -f backend
$DEV down                                # 加 -v 清空开发数据库
```

> `node_modules`、`.next`、`backend/target`、Maven 仓库都放在容器卷中，与宿主机隔离。如需让编辑器获得类型提示，可在宿主机执行一次 `cd frontend && npm install`（可选）。

**测试覆盖**（对照 [implementation-plan.md §4](docs/implementation-plan.md#4-需求--验证对照)）：

- 后端：三步骤顺序执行与成功、每个阶段失败（参数化）后停止且后续步骤不执行、终态不被覆盖、查询不推进状态、长耗时下快速受理、提交校验（张数 / 类型 / 大小 / 魔数）、用户隔离与文件访问控制、修改密码与删除账户端到端、迁移与种子数据。
- 前端：多任务互不串扰、查询异常与服务端失败区分、终态后停止请求、切换用户丢弃迟到响应、上传失败与任务失败区分、上传位交互、登录态与账户设置。

## CI

GitHub Actions（[.github/workflows/ci.yml](.github/workflows/ci.yml)）在每次 push / pull request 时运行：

- **frontend**：`npm ci` → API 类型同步检查（`schema.d.ts` 与 `api.yaml` 一致）→ lint → typecheck → test → build
- **backend**：`./mvnw -B verify`（`*Test` surefire + `*IT` failsafe，Testcontainers 启动 MySQL 8.4）；失败时上传测试报告为 artifact
- **docker**：`docker compose up --build --wait`，确认三个服务 healthy、经 `:3000` 访问后端为 UP；再运行 [scripts/smoke-demo.sh](scripts/smoke-demo.sh) 走一遍演示闭环

## 部署与回滚

目标环境为单机 Docker Compose（OQ-21 临时默认），不涉及生产环境操作。

**部署**

```bash
git fetch && git checkout <要部署的 commit 或 tag>
# 给本次构建一个新 tag，保留上一个版本的镜像用于回滚
IMAGE_TAG=<新版本，如 git short sha> docker compose up -d --build --wait
docker compose ps                       # 三个服务都应为 healthy
scripts/smoke-demo.sh                   # 可选：冒烟验证
```

也可以把 `IMAGE_TAG` 写入 `.env`。镜像名为 `xiaoying-backend:<tag>`、`xiaoying-frontend:<tag>`；数据在 `mysql-data`、`storage-data` 卷中，与镜像无关，升级和回滚都不会丢数据。部署到 https 时设置 `APP_AUTH_COOKIE_SECURE=true`；生产环境使用 `prod` profile（`/api/dev/simulation` 不存在），数据库凭据只通过环境变量提供。

**回滚**

```bash
docker image ls 'xiaoying-*'            # 查看保留的旧 tag
IMAGE_TAG=<上一个版本> docker compose up -d --no-build --wait
```

**数据库迁移约定**：Flyway 迁移只增不改（新增 `V{n}__*.sql`，不修改已执行的迁移），且新迁移须向后兼容（只加表 / 加可空列，不删列、不改列含义），这样回滚到旧镜像时旧代码仍能使用新 Schema。Flyway 不做自动 down 迁移；如确需撤销 Schema 变更，写一个新的前向迁移。升级前建议备份：

```bash
docker compose exec mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' > backup.sql
```

## 完成情况与已知限制

### 已完成

- P0 功能任务：M0 工程骨架、M1 账户与模板、M2 选图与提交、M3 后端异步核心、M4 前端异步状态、M5 交付（T-060 CI、T-062 Compose、T-061 README）。每个任务的完成说明和验证记录见 [docs/backlog.md](docs/backlog.md)。
- P1 中的 F-17 Docker Compose 一键启动（含健康检查、启动顺序、数据卷保留）已交付。
- 基础视觉：顶部导航、分类标签、卡片网格、详情页左右两栏、History 卡片网格（随 P0 页面实现，未做 T-070 的专门打磨）。

### 未完成（本期范围外或 P1 未开始）

| 项 | 状态 |
| --- | --- |
| T-070 视觉打磨（参考 UI 的暗色主题等） | 未开始 |
| T-071 可访问性与 360px 小屏专项 | 未开始（已有基础语义元素与键盘操作，未专项验收） |
| T-072 服务重启时未完成任务的处理 | 未开始，见下方 OQ-13 |
| T-073 批量任务查询接口 | 未开始（契约中 `GET /api/tasks?ids=` 标注为 P1，后端未实现） |
| T-074 阶段执行日志 TaskStepLog | 未开始 |
| T-075 三阶段步骤条 UI | 未开始（当前只显示当前阶段文案） |
| P2：受理幂等、SSE / WebSocket、失败重试 / 重新制作、删除历史、下载结果、历史分页 | 不做 |
| 范围外：真实 AI / 图片处理、支付积分、模板管理、第三方登录、忘记密码邮件、修改邮箱、国际化 | 不做（见 [requirements.md §10.2](docs/requirements.md#102-暂不实现本期范围外)） |

### 已知限制

1. **服务重启边界（OQ-13，待确认）**：后端进程停止或重启时，执行中的任务会被中断（模拟耗时的等待可被中断，实测 `docker compose stop backend` 约 1 s 完成），线程池队列中尚未开始的任务也不会再执行，这些任务停在 `PENDING` / `PROCESSING`。**重启后不会自动恢复执行、不会重试，也不会被标记为失败**，前端会一直显示"处理中"并按 2 s 间隔持续查询（不会显示"制作失败"）。这是当前按 OQ-13 临时默认"保持原状、只在 README 说明"的行为。规避：演示时不要在任务处理中重启后端（第 5 步除外，并按其中的注意事项观察新任务）；如果需要清理，可以删除这个账户（进行中任务会被标记为 `ACCOUNT_DELETED`），或 `docker compose down -v` 重置数据。T-072 预留了"启动时把中断任务标记为失败"的方案，待 OQ-13 确认后实现。
2. 单实例：线程池、模拟配置都在进程内存中；模拟配置修改不持久化，重启后回到默认值。
3. 线程池 2 个工作线程，同时提交多个任务时后提交的会先"等待处理"；队列满（100）时拒绝受理（503）。
4. 历史列表不分页（OQ-09 余项），任务很多时一次返回全部。
5. 前端按任务逐个轮询（每个未结束任务每 2 s 一个请求）；批量查询为 P1（T-073）。
6. 没有登录限流 / 验证码；会话固定 7 天不续期。
7. 删除账户的数据只做软删除、不提供恢复；文件不做定期清理。进程崩溃在"写文件"和"事务提交"之间时可能留下孤儿文件。
8. 结果图是模板的预置图片，与上传内容无关（模拟生成）。
9. 多个规则按 open-questions 的临时默认实现（如图片张数恰好 N 张、提交后清空上传区、用户名删除后不可再用），见 [docs/open-questions.md](docs/open-questions.md)；结论变化时局部调整。

## 实际投入

（OQ-01：PRD 未给出预计投入数值，按临时默认先完成 P0 + Compose，P1 其余项按剩余时间取舍。）

| 项 | 数据 |
| --- | --- |
| 日历时间（据 git 记录） | 2026-11-04 20:16 首个 commit → 2026-11-05 21:27 T-062 完成（UTC-8），约 25 小时 |
| 实际投入工时 | 12h |
| 提交数 | 44（截至 T-062），其中 `feat(T-xxx)` 31 个，其余为规划文档、构建、CI 与状态同步 |
| 时间分配（定性） | 先用较多时间做需求澄清与设计（requirements、open-questions、architecture、database、api.yaml、implementation-plan），之后按 backlog 逐任务实现；交付阶段用于 CI、Compose 验收与 README |

**取舍**：优先保证 P0 闭环、可测试性和数据隔离（如查询异常与任务失败严格区分、终态不可覆盖、用户文件访问控制），把 Compose 一键启动提前到工程骨架阶段；视觉打磨、可访问性专项、重启处理、批量查询、阶段日志等 P1 未做，重启边界在上面的已知限制中说明。

## AI 使用说明

本项目在 [Claude Code](https://claude.com/claude-code)（Anthropic 的编码助手）辅助下完成，44 个 commit 中有 41 个带 `Co-Authored-By: Claude` 标注。

**工作流**

1. **规划**：把 [PRD](docs/PRD.md) 交给 AI 拆解为需求规格（F-xx / R-xx）、未确定问题（OQ-xx，每个给选项和临时默认）、backlog（T-xxx，带依赖和完成定义）、架构、数据库、API 契约和实施计划。需求方的业务决定由人确认后写回 open-questions（如 OQ-02 需要密码登录、OQ-05 提交时一起上传、OQ-26 删除账户保留数据），AI 不替需求方做业务决定。
2. **约束**：[AGENTS.md](AGENTS.md) 规定文档的权威顺序和开发规则：一次只做一个 T-xxx；不得自行发明 API 字段或数据库列（契约变更先改 `api.yaml` 再生成类型）；业务规则只放在后端；`[待确认 OQ-xx]` 按临时默认实现；未经验证不得把任务标记为 Done；未经批准不提交、不推送。
3. **逐任务实现**：每个任务使用同一个提示模板——执行前阅读相关文档和代码、确认依赖、列出要改的文件和测试计划；执行中按契约实现并补充正常 / 无效输入 / 未授权测试；执行后在 Docker 中实际运行测试、lint、build，报告实际结果和未验证项，再更新 backlog 状态。
4. **人工验证**：人审查 diff，在浏览器中按演示脚本操作，确认 CI 通过后才提交并把任务标为 Done（例如 T-043 / T-046 / T-052 / T-056 是在 CI 和浏览器验证之后单独用一个 commit 标记 Done 的）。

**具体例子：大文件上传经代理是否被截断（风险 → 测试 → 验收）**

- 规划阶段（[implementation-plan.md §6 风险](docs/implementation-plan.md#6-风险)），AI 指出：图片在提交时一次性上传，最大的模板需要 3 × 5 MB，而浏览器请求要经过 Next.js rewrites 转发，可能被截断或缓冲，且全局上限必须 ≥ 15 MB。
- 实现时把 `max-request-size` 设为 20 MB，并用 `MultipartLimitsIT` 断言"全局上限 ≥ 最大张数 × 单张上限"，防止以后改种子数据时被打破。
- T-062 验收时，AI 在 `scripts/acceptance-demo.sh` 中加入 3 × 4.7 MB PNG（约 14 MB 请求体）经 `:3000` 提交的用例，在全新 compose 项目中实际运行：任务受理并 `SUCCEEDED`，服务端保存的素材字节数与原文件一致。人工复核了脚本输出后才把 T-062 标记为 Done。

AI 在这个过程中的主要价值是把需求里的歧义和风险提前列出来、保持文档与代码一致、并写出覆盖边界情况的测试；业务取舍、最终验收和提交由人负责。

## 复用的脚手架、开源代码与素材来源

**脚手架**：后端按 Spring Boot 标准项目结构 + Maven Wrapper 搭建；前端按 Next.js 官方 App Router 项目结构（TypeScript、ESLint、Tailwind）搭建。没有复制其他项目的业务代码。

**主要开源依赖**（均通过包管理器引入，版本见 `backend/pom.xml`、`frontend/package-lock.json`）：

| 用途 | 依赖 | 许可 |
| --- | --- | --- |
| 后端框架 | Spring Boot、Spring Web、Bean Validation、Actuator | Apache-2.0 |
| 持久化 | MyBatis、mybatis-spring-boot-starter、Flyway、MySQL Connector/J | Apache-2.0；Connector/J 为 GPLv2 + FOSS Exception |
| 密码哈希 | Spring Security Crypto（BCrypt） | Apache-2.0 |
| 后端测试 | JUnit 5、Spring Boot Test、Testcontainers | EPL-2.0 / Apache-2.0 / MIT |
| 前端 | Next.js、React、Zustand、Tailwind CSS | MIT |
| 前端测试 / 工具 | Vitest、React Testing Library、MSW、openapi-typescript、ESLint | MIT |
| 容器镜像 | `mysql:8.4`、`eclipse-temurin:21`、`node:24-alpine` | 各自官方镜像许可 |

**素材来源**：种子封面、预置结果图和 `samples/` 演示样例**全部由 [scripts/gen-seed-assets.py](scripts/gen-seed-assets.py) 用 Pillow 程序绘制**（渐变 + 圆形 + 英文标签，字体为 Pillow 内置默认字体），不包含任何第三方图片，作为本项目原创内容以 [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) 发布（OQ-19 临时默认：优先自制）。

| 位置 | 文件 | 用途 |
| --- | --- | --- |
| `backend/src/main/resources/seed/covers/` | `postcard.jpg`、`poster.jpg`、`id-photo.jpg`、`collage.jpg`（800×600 JPEG） | 模板封面，经 `/files/seed/covers/**` 公开访问 |
| `backend/src/main/resources/seed/results/` | 同名 `.png`（1024×768） | 预置结果图，结果整理阶段复制为任务专属文件（OQ-08 临时默认） |
| `samples/` | 见 [samples/README.md](samples/README.md) | 演示上传：合法 JPEG / PNG / WebP、> 1 MB、> 5 MB、伪装成 `.jpg` 的文本文件 |

参考截图（`image/`）只用于布局参考，未作为素材使用。

## 交付物与验收 commit

| 交付物（R-13.1） | 位置 |
| --- | --- |
| 前端源码 + 锁文件 | `frontend/`（`package-lock.json`） |
| 后端源码 + 构建配置 | `backend/`（`pom.xml`、Maven Wrapper） |
| 数据库结构与初始化数据 | `backend/src/main/resources/db/migration/`（`V1__init_schema.sql`、`V2__seed_data.sql`），说明见 `docs/database.md` |
| 种子素材与演示样例 | `backend/src/main/resources/seed/`、`samples/` |
| CI 配置 | `.github/workflows/ci.yml` |
| 部署配置 | `docker-compose.yml`、`backend/Dockerfile`、`frontend/Dockerfile`、`.env.example` |
| 设计文档与契约 | `docs/` |

**验收 commit**

| commit | 内容 |
| --- | --- |
| `0779165` `feat(T-062)` | 功能代码的验收基线：P0 全部功能完成，T-062 的 Compose 验收（全新 compose 项目 + 无缓存构建、`acceptance-demo.sh` 两阶段、浏览器演示脚本 1 ~ 8）基于此 commit |
| `feat(T-061)`（本 README 所在 commit） | 只修改文档（README、backlog），不改动代码；建议以它作为交付版本并打 tag，如 `git tag v0.1.0` |

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/PRD.md](docs/PRD.md) | 原始需求 |
| [docs/requirements.md](docs/requirements.md) | 需求规格（F-xx / R-xx） |
| [docs/backlog.md](docs/backlog.md) | 任务拆分（T-xxx）与完成记录 |
| [docs/architecture.md](docs/architecture.md) | 架构设计 |
| [docs/database.md](docs/database.md) | 数据库设计 |
| [docs/api.yaml](docs/api.yaml) | 前后端接口契约（OpenAPI 3.1） |
| [docs/implementation-plan.md](docs/implementation-plan.md) | 实施计划与验证方式 |
| [docs/open-questions.md](docs/open-questions.md) | 未确定问题 |
