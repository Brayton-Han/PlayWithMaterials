import { setupServer } from "msw/node";

/** 测试共用的 MSW server；各测试用 server.use(...) 声明自己需要的接口。 */
export const server = setupServer();
