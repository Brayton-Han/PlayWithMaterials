# syntax=docker/dockerfile:1
# 前端镜像（多阶段）：
#   dev     —— 本地开发：源码由 docker-compose.dev.yml 挂载，运行 next dev（热更新）
#   build   —— next build（output: standalone）
#   runtime —— 演示 / 生产：只包含 standalone server
#
# 注意：rewrites 的目标地址在 next build 时写入（architecture §1.1），
# 因此 runtime 镜像必须用构建参数 BACKEND_INTERNAL_URL 构建（compose 中为 http://backend:8080）。

ARG NODE_IMAGE=node:24-alpine

# ---------- 依赖 ----------
FROM ${NODE_IMAGE} AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

# ---------- 本地开发 ----------
FROM deps AS dev
ENV NEXT_TELEMETRY_DISABLED=1
EXPOSE 3000
CMD ["npm", "run", "dev", "--", "--hostname", "0.0.0.0", "--port", "3000"]

# ---------- 构建 ----------
FROM deps AS build
ARG BACKEND_INTERNAL_URL=http://backend:8080
ENV BACKEND_INTERNAL_URL=${BACKEND_INTERNAL_URL} \
    NEXT_TELEMETRY_DISABLED=1
COPY . .
RUN mkdir -p public && npm run build

# ---------- 运行 ----------
FROM ${NODE_IMAGE} AS runtime
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    HOSTNAME=0.0.0.0 \
    PORT=3000
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static
COPY --from=build --chown=node:node /app/public ./public
USER node
EXPOSE 3000
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=10 \
    CMD wget -qO /dev/null http://127.0.0.1:3000/ || exit 1
CMD ["node", "server.js"]
