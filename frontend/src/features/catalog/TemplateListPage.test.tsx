import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { Category, TemplateSummary } from "@/lib/api/endpoints/catalog";
import { server } from "@/test/msw/server";

import { TemplateListPage } from "./TemplateListPage";

vi.mock("next/navigation", async () => (await import("@/test/navigation")).mockUrlNavigation());

const CATEGORIES: Category[] = [
  { categoryId: "creative", name: "创意图片", sortOrder: 10 },
  { categoryId: "portrait", name: "人像写真", sortOrder: 20 },
  { categoryId: "festival", name: "节日贺卡", sortOrder: 30 },
];

const TEMPLATES: TemplateSummary[] = [
  { templateId: "demo-postcard", title: "旅行明信片", categoryId: "creative", coverUrl: "/files/seed/covers/postcard.jpg" },
  { templateId: "demo-collage", title: "拼贴海报", categoryId: "creative", coverUrl: "/files/seed/covers/collage.jpg" },
  { templateId: "demo-portrait", title: "复古写真", categoryId: "portrait", coverUrl: "/files/seed/covers/portrait.jpg" },
];

function byCategory(categoryId: string | null) {
  return TEMPLATES.filter((t) => categoryId === null || t.categoryId === categoryId);
}

/** 记录每次模板请求的 categoryId（null = 全部）。 */
let templateRequests: (string | null)[] = [];

function useCatalogHandlers() {
  server.use(
    http.get("/api/categories", () => HttpResponse.json({ items: CATEGORIES })),
    http.get("/api/templates", ({ request }) => {
      const categoryId = new URL(request.url).searchParams.get("categoryId");
      templateRequests.push(categoryId);
      return HttpResponse.json({ items: byCategory(categoryId) });
    }),
  );
}

function setUrl(path: string) {
  window.history.replaceState(null, "", path);
}

function cardTitles() {
  const list = screen.getByRole("list", { name: "模板列表" });
  return within(list)
    .getAllByRole("link")
    .map((link) => link.textContent);
}

function tab(name: string) {
  return screen.getByRole("button", { name });
}

beforeEach(() => {
  templateRequests = [];
  setUrl("/");
});

describe("TemplateListPage", () => {
  it("shows a loading placeholder, then categories and cards from the API", async () => {
    useCatalogHandlers();
    render(<TemplateListPage />);

    expect(screen.getByRole("status", { name: "模板加载中" })).toBeInTheDocument();

    await screen.findByRole("list", { name: "模板列表" });
    const nav = screen.getByRole("navigation", { name: "模板分类" });
    expect(within(nav).getAllByRole("button").map((b) => b.textContent)).toEqual([
      "全部",
      "创意图片",
      "人像写真",
      "节日贺卡",
    ]);
    expect(tab("全部")).toHaveAttribute("aria-pressed", "true");
    expect(cardTitles()).toEqual(["旅行明信片", "拼贴海报", "复古写真"]);
    expect(templateRequests).toEqual([null]);
  });

  it("links each card to its template detail page", async () => {
    useCatalogHandlers();
    render(<TemplateListPage />);

    const card = await screen.findByRole("link", { name: "旅行明信片" });
    expect(card).toHaveAttribute("href", "/templates/demo-postcard");
    expect(card.querySelector("img")).toHaveAttribute("src", "/files/seed/covers/postcard.jpg");
  });

  it("filters by category, syncs ?category= and restores the full list on 全部", async () => {
    useCatalogHandlers();
    const user = userEvent.setup();
    render(<TemplateListPage />);
    await screen.findByRole("list", { name: "模板列表" });

    await user.click(tab("创意图片"));
    await waitFor(() => expect(cardTitles()).toEqual(["旅行明信片", "拼贴海报"]));
    expect(window.location.search).toBe("?category=creative");
    expect(tab("创意图片")).toHaveAttribute("aria-pressed", "true");
    expect(tab("全部")).toHaveAttribute("aria-pressed", "false");

    await user.click(tab("全部"));
    await waitFor(() => expect(cardTitles()).toEqual(["旅行明信片", "拼贴海报", "复古写真"]));
    expect(window.location.search).toBe("");
    expect(templateRequests).toEqual([null, "creative", null]);
  });

  it("reads the initial category from the URL and follows browser back / forward", async () => {
    useCatalogHandlers();
    setUrl("/?category=portrait");
    render(<TemplateListPage />);

    await waitFor(() => expect(cardTitles()).toEqual(["复古写真"]));
    expect(tab("人像写真")).toHaveAttribute("aria-pressed", "true");
    expect(templateRequests).toEqual(["portrait"]);

    act(() => {
      setUrl("/?category=creative");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    await waitFor(() => expect(cardTitles()).toEqual(["旅行明信片", "拼贴海报"]));
  });

  it("does not let a slower stale response overwrite the latest category, and aborts it", async () => {
    let releaseCreative!: () => void;
    const creativeGate = new Promise<void>((resolve) => (releaseCreative = resolve));
    let creativeAborted = false;
    server.use(
      http.get("/api/categories", () => HttpResponse.json({ items: CATEGORIES })),
      http.get("/api/templates", async ({ request }) => {
        const categoryId = new URL(request.url).searchParams.get("categoryId");
        templateRequests.push(categoryId);
        if (categoryId === "creative") {
          request.signal.addEventListener("abort", () => (creativeAborted = true));
          await creativeGate;
        }
        return HttpResponse.json({ items: byCategory(categoryId) });
      }),
    );
    const user = userEvent.setup();
    render(<TemplateListPage />);
    await screen.findByRole("list", { name: "模板列表" });

    await user.click(tab("创意图片")); // 慢请求挂起
    await user.click(tab("人像写真")); // 快请求立即返回
    await waitFor(() => expect(cardTitles()).toEqual(["复古写真"]));
    await waitFor(() => expect(creativeAborted).toBe(true));

    // 旧请求的响应迟到，也不能覆盖当前结果
    await act(async () => {
      releaseCreative();
      await new Promise((r) => setTimeout(r, 20));
    });
    expect(cardTitles()).toEqual(["复古写真"]);
    expect(tab("人像写真")).toHaveAttribute("aria-pressed", "true");
    expect(window.location.search).toBe("?category=portrait");
    expect(templateRequests).toEqual([null, "creative", "portrait"]);
  });

  it("shows an empty state when a category has no templates", async () => {
    useCatalogHandlers();
    const user = userEvent.setup();
    render(<TemplateListPage />);
    await screen.findByRole("list", { name: "模板列表" });

    await user.click(tab("节日贺卡"));
    expect(await screen.findByText("该分类下暂无模板")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "模板列表" })).not.toBeInTheDocument();
  });

  it("falls back to 全部 with a notice for an unknown category, without sending it to the backend", async () => {
    useCatalogHandlers();
    setUrl("/?category=nope");
    render(<TemplateListPage />);

    await waitFor(() => expect(cardTitles()).toEqual(["旅行明信片", "拼贴海报", "复古写真"]));
    expect(screen.getByText("未找到分类「nope」，已显示全部模板。")).toBeInTheDocument();
    expect(tab("全部")).toHaveAttribute("aria-pressed", "true");
    expect(templateRequests).toEqual([null]);
  });

  it("shows an error with retry when the backend is unreachable, then recovers", async () => {
    let categoriesUp = false;
    server.use(
      http.get("/api/categories", () =>
        categoriesUp ? HttpResponse.json({ items: CATEGORIES }) : HttpResponse.error(),
      ),
      http.get("/api/templates", () => HttpResponse.json({ items: TEMPLATES })),
    );
    const user = userEvent.setup();
    render(<TemplateListPage />);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("网络连接异常，请检查网络后重试");
    // 不回退到静态数据
    expect(screen.queryByRole("navigation", { name: "模板分类" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();

    categoriesUp = true;
    await user.click(within(alert).getByRole("button", { name: "重试" }));
    await waitFor(() => expect(cardTitles()).toEqual(["旅行明信片", "拼贴海报", "复古写真"]));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows the template list error with retry while keeping the category tabs", async () => {
    let fail = true;
    server.use(
      http.get("/api/categories", () => HttpResponse.json({ items: CATEGORIES })),
      http.get("/api/templates", () =>
        fail
          ? HttpResponse.json({ code: "INTERNAL_ERROR", message: "服务器内部错误" }, { status: 500 })
          : HttpResponse.json({ items: TEMPLATES }),
      ),
    );
    const user = userEvent.setup();
    render(<TemplateListPage />);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("服务器内部错误");
    expect(screen.getByRole("navigation", { name: "模板分类" })).toBeInTheDocument();

    fail = false;
    await user.click(within(alert).getByRole("button", { name: "重试" }));
    await waitFor(() => expect(cardTitles()).toHaveLength(3));
  });

  it("shows a placeholder when a cover fails to load, keeping the title clickable", async () => {
    useCatalogHandlers();
    render(<TemplateListPage />);

    const card = await screen.findByRole("link", { name: /旅行明信片/ });
    fireEvent.error(card.querySelector("img")!);

    expect(within(card).getByRole("img", { name: "封面加载失败" })).toBeInTheDocument();
    expect(card).toHaveTextContent("旅行明信片");
    expect(card).toHaveAttribute("href", "/templates/demo-postcard");
  });
});
