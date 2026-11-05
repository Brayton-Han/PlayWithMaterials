import type { NextConfig } from "next";

/**
 * 浏览器只访问前端域名；/api/** 与 /files/** 经 rewrites 同源转发给 Java（architecture §1.1）。
 *
 * - 不创建 proxy.ts（原 middleware）：路由守卫在客户端做，权限由 Java 保证。
 * - 实测（T-041）：即使没有 proxy.ts，rewrites 转发时请求体仍按 `proxyClientMaxBodySize`（默认 10 MB）缓冲，
 *   超出部分被截断，Java 解析 multipart 时 EOF → 500。上限设为高于后端 max-request-size（20 MB），
 *   使 15 MB 的正常提交完整转发，20 ~ 25 MB 的请求也完整到达后端、得到 413 PAYLOAD_TOO_LARGE。
 * - standalone 构建时 rewrites 目标在**构建期**写入，Docker 构建需传入 BACKEND_INTERNAL_URL。
 */
const backendUrl = (process.env.BACKEND_INTERNAL_URL ?? "http://localhost:8080").replace(/\/+$/, "");

const nextConfig: NextConfig = {
  output: "standalone",
  cacheComponents: true,
  partialPrefetching: true,
  reactCompiler: true,
  experimental: {
    proxyClientMaxBodySize: "25mb",
  },
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
  async rewrites() {
    return [
      { source: "/api/:path*", destination: `${backendUrl}/api/:path*` },
      { source: "/files/:path*", destination: `${backendUrl}/files/:path*` },
      // 仅用于首页探活；compose 健康检查直接访问后端
      { source: "/actuator/health", destination: `${backendUrl}/actuator/health` },
    ];
  },
};

export default nextConfig;
