# 小影 · 素材制作网站

选择模板 → 上传图片 → 后端异步执行"素材检查 → 生成 → 结果整理"三步 → 前端跟踪任务状态并展示结果。

> 当前进度：**S0 工程骨架**（T-001 ~ T-006）已完成；**S1 身份 + 目录**进行中（已完成 T-010 后端注册 / 登录 / 退出、T-021 种子素材、T-020 模板接口、T-011 前端登录 / 注册 / 顶栏、T-012 后端账户设置：`PUT /api/account/password` 修改密码（需当前密码，作废其他会话）、`DELETE /api/account` 删除账户（确认文字 `DELETE`，软删除 + 进行中任务废弃为 `FAILED + ACCOUNT_DELETED` + 作废全部会话，数据保留）、T-013 前端账户设置页：`/account` 只读账户信息、修改密码表单、输入 `DELETE` 才可确认的删除账户对话框，删除成功后执行退出清理并回到首页、T-022 模板列表页、T-023 模板详情页骨架）；**S2 异步核心**进行中（已完成 T-040 任务领域模型与 Mapper、T-045 任务查询接口、T-041 任务提交接口、T-042 异步执行引擎；T-043 三个步骤（素材检查、模拟生成、结果整理复制为任务专属结果图，支持快照中的耗时与指定阶段失败）；T-044 模拟配置：dev / demo profile 下可用 `GET/PUT /api/dev/simulation` 运行时调整各阶段耗时和指定失败阶段（不需要登录，只影响之后创建的任务，重启后回到 `app.simulation.*`；prod profile 下该接口不存在，返回 404）；T-047 用户文件访问控制：`/files/uploads/**`、`/files/results/**` 需要登录且只返回本人任务的文件（匿名 401，他人或不存在 404，`Cache-Control: private, no-store`），删除账户后链接失效、文件保留；T-046 后端核心测试已补齐长耗时快速受理与查询不推进的集成测试（删除账户端到端用例 `AccountDeletionFlowIT` 已随 T-012 补齐；CI 已通过））；**M2 选图与提交**进行中（已完成 T-030 文件存储服务、T-031 上传位组件：本地选图 / 预览 / 替换 / 移除与客户端校验、T-032 提交请求状态：上传进度 → 提交中 → 受理，上传 / 提交中锁定与防重复提交，失败保留已选文件并显示原因（网络、413、400 按上传位标出），离开页面或退出登录时取消上传）；**M4 前端异步状态**进行中（已完成 T-050 任务状态 store、T-051 任务跟踪调度器 `useTaskTracker(scope)`：每 2 s 查询范围内未结束的任务，终态 / 404 后停止，失败退避并保留手动重查，离开页面或切换用户时取消请求并丢弃迟到响应；T-052 任务卡片与详情页 History 面板（等待 / 处理阶段 / 成功缩略图与大图预览 / 失败阶段与原因，查询异常与服务端失败样式区分；浏览器验收通过）；T-053 提交与任务区衔接：受理后立即在 History 显示 PENDING 卡片并自动跟踪，上传区清空（OQ-07 临时默认），提交失败不插入任务；T-054 刷新恢复与历史加载：进入详情页（已登录）按 templateId 从后端加载当前模板的历史，刷新或重新打开浏览器后记录仍在，未结束的任务自动继续跟踪至终态，加载失败显示错误 + 重试（不显示为空），"刷新"按钮同时重新加载历史；T-055 "我的记录"页：`/records`（需要登录，顶栏用户菜单入口）显示全部模板的任务，每条带模板标题和封面、点击进入该模板详情页，自动跟踪未结束任务，与详情页共用同一 store、状态一致；T-056 前端核心测试已补齐（多任务互不串扰、查询异常 vs 服务端失败、终态后停止请求、切换用户丢弃迟到响应、上传失败 vs 任务失败；本地与 CI 均通过））；**M5 交付**进行中（已完成 T-060 CI 完整化：CI 运行前后端全部测试，docker job 增加演示环境闭环冒烟 `scripts/smoke-demo.sh`；CI 已通过；T-062 Docker Compose 交付：`docker compose up --build` 完整演示闭环与 `down` / `up` 数据保留已验收，新增 `scripts/acceptance-demo.sh`）。功能按 [docs/implementation-plan.md](docs/implementation-plan.md) 的阶段逐步实现。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/PRD.md](docs/PRD.md) | 原始需求 |
| [docs/requirements.md](docs/requirements.md) | 需求规格（F-xx / R-xx） |
| [docs/backlog.md](docs/backlog.md) | 任务拆分（T-xxx） |
| [docs/architecture.md](docs/architecture.md) | 架构设计 |
| [docs/database.md](docs/database.md) | 数据库设计 |
| [docs/api.yaml](docs/api.yaml) | 前后端接口契约（OpenAPI 3.1） |
| [docs/implementation-plan.md](docs/implementation-plan.md) | 实施计划与验证方式 |
| [docs/open-questions.md](docs/open-questions.md) | 未确定问题 |

## 目录结构

```
.
├── backend/                 Spring Boot 4 + MyBatis + Flyway（Java 21），Dockerfile
├── frontend/                Next.js 16 App Router + React 19 + TypeScript + Tailwind，Dockerfile
├── docs/                    需求、设计与契约
├── samples/                 演示上传用的样例文件（合法 / 超大 / 伪装类型）
├── scripts/                 辅助脚本（gen-seed-assets.py 生成种子素材与样例）
├── docker-compose.dev.yml   本地开发：mysql + backend + frontend（源码挂载）
├── docker-compose.yml       演示 / 验收：mysql + backend + frontend（构建镜像）
└── .env.example             环境变量示例
```

## 技术栈与版本

| 层 | 选型 |
| --- | --- |
| 运行时 | Java 21（eclipse-temurin）、Node.js 24（node:24-alpine） |
| 后端 | Spring Boot 4.0.9、mybatis-spring-boot-starter 4.0.1、Flyway、Bean Validation、Actuator |
| 数据库 | MySQL 8.4（`utf8mb4_0900_ai_ci`，时间存 UTC） |
| 前端 | Next.js 16.4、React 19、TypeScript（strict）、Tailwind CSS 4 |
| 测试 | JUnit 5、Spring Boot Test、Testcontainers；Vitest、React Testing Library、MSW |
| 构建 / 运行 | Docker Compose；Maven Wrapper（Maven 3.9.11）、npm |

## 快速开始（演示环境）

前置条件：只需要 Docker（Docker Desktop 或 Docker Engine + Compose v2）。

```bash
cp .env.example .env          # 可选，按需修改端口 / 密码
docker compose up --build     # 打开 http://localhost:3000
```

- 首次启动时后端通过 Flyway 自动建表并写入种子数据；`docker compose down` 后再 `up`，数据仍在（`mysql-data`、`storage-data` 卷）。
- `docker compose down -v` 会删除数据卷，下次启动重新初始化。
- 后端 `http://localhost:8080` 也映射到宿主机，方便 curl 调试；浏览器统一访问 `:3000`。

### 演示验收脚本

[scripts/acceptance-demo.sh](scripts/acceptance-demo.sh) 经 `:3000` 走完整演示闭环（需要 `curl`、`jq`、`python3`）：成功、失败、多任务同时处理、刷新恢复（新会话重新拉取）、我的记录、用户隔离、约 15 MB 请求体经代理提交、修改密码、删除账户、同一邮箱再注册；再验证 `down` → `up` 后任务、素材、结果图仍在。修改密码 / 删除账户使用脚本临时注册的账户，`demo1` / `demo2` 不受影响（会以 `demo1` 创建 5 个任务）。

```bash
docker compose up -d --build --wait
scripts/acceptance-demo.sh run state.json          # 阶段 1：演示闭环
docker compose down && docker compose up -d --wait
scripts/acceptance-demo.sh persisted state.json    # 阶段 2：数据保留
```

## 本地开发

**所有服务都在 Docker 中运行**，宿主机不需要安装 JDK、Maven 或 Node.js。

```bash
docker compose -f docker-compose.dev.yml up --build     # http://localhost:3000
```

| 服务 | 地址 | 代码修改后 |
| --- | --- | --- |
| frontend（`next dev`） | http://localhost:3000 | 自动热更新 |
| backend（`spring-boot:run`，dev profile） | http://localhost:8080 | `docker compose -f docker-compose.dev.yml restart backend` |
| mysql | 宿主机 `localhost:3307`（`MYSQL_PORT`） | — |

### 常用命令

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
$DEV exec frontend npm install <pkg>      # 或在容器内安装，package*.json 会写回宿主机

# 查看日志 / 停止
$DEV logs -f backend
$DEV down                                # 加 -v 清空开发数据库
```

> 说明：`node_modules`、`.next`、`backend/target`、Maven 仓库都放在容器卷中，与宿主机隔离。
> 如需让编辑器获得类型提示，可在宿主机执行一次 `cd frontend && npm install`（可选，不影响容器）。

## 演示账户

种子数据预置两个账户（仅供演示，数据库只保存 BCrypt 哈希）：

| 用户名 | 密码 |
| --- | --- |
| `demo1` | `xiaoying123` |
| `demo2` | `xiaoying123` |

## 素材来源

种子封面、预置结果图和演示样例**全部由 [scripts/gen-seed-assets.py](scripts/gen-seed-assets.py) 用 Pillow 程序绘制**（渐变 + 圆形 + 英文标签，字体为 Pillow 内置默认字体），不包含任何第三方图片，作为本项目原创内容以 [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) 发布（OQ-19 临时默认：优先自制）。重新生成：`python3 scripts/gen-seed-assets.py`（需要 Pillow，仅在修改素材时使用）。

| 位置 | 文件 | 用途 |
| --- | --- | --- |
| `backend/src/main/resources/seed/covers/` | `postcard.jpg`、`poster.jpg`、`id-photo.jpg`、`collage.jpg`（800×600 JPEG） | 模板封面，经 `/files/seed/covers/**` 公开访问（`templates.cover_url`） |
| `backend/src/main/resources/seed/results/` | 同名 `.png`（1024×768） | 预置结果图，结果整理阶段复制为任务专属文件（`templates.sample_result_path`，OQ-08 临时默认） |
| `samples/` | 见 [samples/README.md](samples/README.md) | 演示上传：合法 JPEG / PNG / WebP、>1 MB、>5 MB、伪装成 `.jpg` 的文本文件 |

## CI

GitHub Actions（[.github/workflows/ci.yml](.github/workflows/ci.yml)）在每次 push / pull request 时运行：

- **frontend**：`npm ci` → API 类型同步检查 → lint → typecheck → test → build
- **backend**：`./mvnw -B verify`（含 Testcontainers 集成测试）
- **frontend**、**backend** 使用 npm / Maven 依赖缓存；前端用例为 `src/**/*.test.{ts,tsx}`（Vitest），后端 `*Test`（surefire）+ `*IT`（failsafe，Testcontainers 启动 MySQL 8.4，Ubuntu runner 自带 Docker）。任一用例失败、lint / 类型错误或 `schema.d.ts` 未同步都会让对应 job 失败；后端失败时上传 surefire / failsafe 报告为 artifact。
- **docker**：`docker compose up --build --wait`，确认三个服务 healthy、经 `:3000` 访问后端为 UP；再运行 [scripts/smoke-demo.sh](scripts/smoke-demo.sh) 走一遍演示环境（`demo` profile）闭环：`/api/dev/simulation` 可用、匿名 401、`demo1` 登录、模板列表、提交任务 → `SUCCEEDED` 且结果图本人可取 / 匿名 401、指定 `GENERATING` 失败 → `FAILED`。

本地对演示环境运行同一冒烟测试（需要 `curl`、`jq`；会以 `demo1` 创建 2 个任务，结束后恢复模拟配置）：

```bash
docker compose up -d --build --wait
scripts/smoke-demo.sh                      # 默认 BASE_URL=http://localhost:3000
```
