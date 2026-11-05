import type { NextConfig } from "next";

/**
 * 浏览器只访问前端域名；/api/** 与 /files/** 经 rewrites 同源转发给 Java（architecture §1.1）。
 *
 * - 不创建 proxy.ts（原 middleware）：存在 proxy 时 Next.js 会缓冲请求体（默认上限 10 MB），
 *   会截断 multipart 上传。路由守卫在客户端做，权限由 Java 保证。
 * - standalone 构建时 rewrites 目标在**构建期**写入，Docker 构建需传入 BACKEND_INTERNAL_URL。
 */
const backendUrl = (process.env.BACKEND_INTERNAL_URL ?? "http://localhost:8080").replace(/\/+$/, "");

const nextConfig: NextConfig = {
  output: "standalone",
  cacheComponents: true,
  partialPrefetching: true,
  reactCompiler: true,
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
