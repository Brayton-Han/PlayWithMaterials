# 小影 · 素材制作网站

选择模板 → 上传图片 → 后端异步执行"素材检查 → 生成 → 结果整理"三步 → 前端跟踪任务状态并展示结果。

> 当前进度：**S0 工程骨架**（T-001 ~ T-006）。功能按 [docs/implementation-plan.md](docs/implementation-plan.md) 的阶段逐步实现。

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
├── backend/                 Spring Boot 4 + MyBatis + Flyway（Java 21）
├── frontend/                Next.js 16 App Router + React 19 + TypeScript + Tailwind
├── docs/                    需求、设计与契约
├── docker-compose.dev.yml   本地开发：只启动 MySQL
├── docker-compose.yml       演示环境（占位，T-062 完成）
└── .env.example             环境变量示例
```

## 技术栈与版本

| 层 | 选型 |
| --- | --- |
| 运行时 | Java 21、Node.js 24 |
| 后端 | Spring Boot 4.0.9、mybatis-spring-boot-starter 4.0.1、Flyway、Bean Validation、Actuator |
| 数据库 | MySQL 8.4（`utf8mb4_0900_ai_ci`，时间存 UTC） |
| 前端 | Next.js 16.4、React 19、TypeScript（strict）、Tailwind CSS 4 |
| 测试 | JUnit 5、Spring Boot Test、Testcontainers；Vitest、React Testing Library、MSW |
| 构建 | Maven Wrapper（Maven 3.9.11）、npm |

## 本地开发

前置条件：JDK 21、Node.js 24、Docker。

```bash
# 1. 启动 MySQL（宿主机端口 3307，避免与本机 MySQL 冲突；可用 MYSQL_PORT 修改）
docker compose -f docker-compose.dev.yml up -d mysql

# 2. 启动后端（dev profile，首次启动 Flyway 自动建表并写入种子数据）
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
curl http://localhost:8080/actuator/health        # {"status":"UP"}

# 3. 启动前端（/api/**、/files/** 经 rewrites 转发到 http://localhost:8080）
cd frontend && npm install && npm run dev        # http://localhost:3000
```

连接参数可通过环境变量覆盖，见 [.env.example](.env.example)。

### 常用命令

```bash
# 后端：单元测试 + 集成测试（*IT 使用 Testcontainers，需要 Docker）
cd backend && ./mvnw -B verify

# 前端
cd frontend && npm run lint && npm run typecheck && npm test && npm run build

# docs/api.yaml 变更后重新生成前端类型（CI 会检查是否已提交）
cd frontend && npm run gen:api
```

## 演示账户

种子数据预置两个账户（仅供演示，数据库只保存 BCrypt 哈希）：

| 用户名 | 密码 |
| --- | --- |
| `demo1` | `xiaoying123` |
| `demo2` | `xiaoying123` |

## Docker Compose 演示

`docker compose up --build` 一键启动全部服务将在 T-062 中完成。
