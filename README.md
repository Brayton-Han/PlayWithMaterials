# 小影 · 素材制作网站

选择模板 → 上传图片 → 后端异步执行"素材检查 → 生成 → 结果整理"三步 → 前端跟踪任务状态并展示结果。

> 当前进度：**S0 工程骨架**（T-001 ~ T-006）已完成；**S1 身份 + 目录**进行中（已完成 T-010 后端注册 / 登录 / 退出、T-021 种子素材、T-020 模板接口、T-011 前端登录 / 注册 / 顶栏、T-022 模板列表页、T-023 模板详情页骨架）；**S2 异步核心**进行中（已完成 T-040 任务领域模型与 Mapper、T-045 任务查询接口、T-041 任务提交接口、T-042 异步执行引擎；T-043 三个步骤已实现（素材检查、模拟生成、结果整理复制为任务专属结果图，支持快照中的耗时与指定阶段失败）；T-044 模拟配置：dev / demo profile 下可用 `GET/PUT /api/dev/simulation` 运行时调整各阶段耗时和指定失败阶段（不需要登录，只影响之后创建的任务，重启后回到 `app.simulation.*`；prod profile 下该接口不存在，返回 404）；T-047 用户文件访问控制：`/files/uploads/**`、`/files/results/**` 需要登录且只返回本人任务的文件（匿名 401，他人或不存在 404，`Cache-Control: private, no-store`），删除账户后链接失效、文件保留；T-046 后端核心测试已补齐长耗时快速受理与查询不推进的集成测试（待 CI 验证，删除账户端到端用例待 T-012））；**M2 选图与提交**进行中（已完成 T-030 文件存储服务）。功能按 [docs/implementation-plan.md](docs/implementation-plan.md) 的阶段逐步实现。

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
- **docker**：`docker compose up --build --wait`，确认三个服务 healthy、经 `:3000` 访问后端为 UP
