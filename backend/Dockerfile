# syntax=docker/dockerfile:1
# 后端镜像（多阶段）：
#   dev     —— 本地开发：JDK + Maven Wrapper，源码由 docker-compose.dev.yml 挂载，运行 spring-boot:run
#   build   —— 编译打包（跳过测试，测试在 CI / dev 容器中执行）
#   runtime —— 演示 / 生产：JRE 21、非 root 用户，只包含可执行 jar

ARG JDK_IMAGE=eclipse-temurin:21-jdk
ARG JRE_IMAGE=eclipse-temurin:21-jre

# ---------- 依赖缓存 ----------
FROM ${JDK_IMAGE} AS deps
WORKDIR /app
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

# ---------- 本地开发 ----------
FROM ${JDK_IMAGE} AS dev
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC -Dfile.encoding=UTF-8"
EXPOSE 8080
CMD ["./mvnw", "-B", "spring-boot:run", "-Dspring-boot.run.profiles=dev"]

# ---------- 构建 ----------
FROM deps AS build
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
    && cp target/xiaoying-backend-*.jar /app/app.jar

# ---------- 运行 ----------
FROM ${JRE_IMAGE} AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app --home-dir /app app \
    && mkdir -p /data/storage && chown -R app:app /data/storage
WORKDIR /app
COPY --from=build --chown=app:app /app/app.jar app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC -Dfile.encoding=UTF-8 -XX:MaxRAMPercentage=75" \
    SPRING_PROFILES_ACTIVE=demo
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=10 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
