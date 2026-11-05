import { render, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import { server } from "@/test/msw/server";

import { BackendStatus } from "./BackendStatus";

describe("BackendStatus", () => {
  it("shows UP when the backend is healthy", async () => {
    server.use(http.get("/actuator/health", () => HttpResponse.json({ status: "UP" })));
    render(<BackendStatus />);
    expect(await screen.findByText("后端正常（UP）")).toBeInTheDocument();
  });

  it("shows the error when the backend is unreachable", async () => {
    server.use(http.get("/actuator/health", () => HttpResponse.error()));
    render(<BackendStatus />);
    expect(await screen.findByText(/后端不可用：网络连接异常/)).toBeInTheDocument();
  });
});
