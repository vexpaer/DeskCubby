import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useAppStore } from "../store/appStore";
import RssPage from "./RssPage";

const PAGE = {
  dtoVersion: 1,
  subscriptions: [
    {
      id: "feed-1",
      title: "示例订阅",
      url: "https://example.com/feed.xml",
      enabled: true,
    },
  ],
  maxItemsPerFeed: 50,
  showSummaries: true,
  articles: [
    {
      id: "feed-1:article-1",
      feedId: "feed-1",
      feedTitle: "示例订阅",
      title: "第一篇文章",
      urlAvailable: true,
      summary: "这是一段纯文本摘要。",
      publishedAtMs: "1785981600000",
    },
    {
      id: "feed-1:unsafe",
      feedId: "feed-1",
      feedTitle: "示例订阅",
      title: "没有安全链接",
      urlAvailable: false,
      summary: "",
      publishedAtMs: null,
    },
  ],
  errors: [],
  lastUpdatedAtMs: "1785981600000",
} as const;

function renderPage() {
  const router = createMemoryRouter(
    [
      { path: "/rss", element: <RssPage /> },
      { path: "/other", element: <p>其他页面</p> },
    ],
    { initialEntries: ["/rss"] },
  );
  return { ...render(<RouterProvider router={router} />), router };
}

describe("RssPage", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => {
    useAppStore.setState((state) => ({
      ...state,
      appearance: { ...state.appearance, language: "zh-CN" },
      dirtyScopes: [],
    }));
    invokeMock.mockReset();
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_rss_page" || command === "refresh_rss") return PAGE as never;
      if (command === "open_rss_article") return undefined as never;
      throw new Error(`Unexpected command: ${command}`);
    });
  });

  it("loads RSS feeds, refreshes on first entry, and opens only a cached article id", async () => {
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText("第一篇文章")).toBeInTheDocument();
    await waitFor(() => expect(invokeMock).toHaveBeenCalledWith("refresh_rss", undefined));
    expect(screen.queryByRole("link")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "在系统浏览器打开：第一篇文章" }));
    expect(invokeMock).toHaveBeenCalledWith("open_rss_article", {
      request: { dtoVersion: 1, articleId: "feed-1:article-1" },
    });
    expect(screen.getByRole("button", { name: "没有安全链接：没有安全链接" })).toBeDisabled();
  });

  it("adds, pauses, edits and deletes subscriptions through versioned commands", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command) => {
      if ([
        "get_rss_page",
        "refresh_rss",
        "save_rss_subscription",
        "set_rss_subscription_enabled",
        "delete_rss_subscription",
      ].includes(command)) return PAGE as never;
      throw new Error(`Unexpected command: ${command}`);
    });
    renderPage();
    expect((await screen.findAllByText("示例订阅")).length).toBeGreaterThan(0);

    await user.click(screen.getByRole("button", { name: "添加订阅" }));
    await user.type(screen.getByLabelText("名称（可选）"), "新订阅");
    await user.type(screen.getByLabelText("HTTPS 订阅地址"), "news.example/feed");
    await user.click(screen.getByRole("button", { name: "保存" }));
    expect(invokeMock).toHaveBeenCalledWith("save_rss_subscription", {
      request: {
        dtoVersion: 1,
        id: null,
        title: "新订阅",
        url: "news.example/feed",
      },
    });

    await user.click(screen.getByRole("checkbox", { name: "启用 示例订阅" }));
    expect(invokeMock).toHaveBeenCalledWith("set_rss_subscription_enabled", {
      request: { dtoVersion: 1, id: "feed-1", enabled: false },
    });

    await user.click(screen.getByRole("button", { name: "编辑 示例订阅" }));
    expect(screen.getByDisplayValue("https://example.com/feed.xml")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "取消" }));

    await user.click(screen.getByRole("button", { name: "删除 示例订阅" }));
    await user.click(screen.getByRole("button", { name: "删除" }));
    expect(invokeMock).toHaveBeenCalledWith("delete_rss_subscription", {
      request: { dtoVersion: 1, id: "feed-1" },
    });
  });

  it("shows safe localized feed errors and saves reading preferences", async () => {
    const user = userEvent.setup();
    const failedPage = {
      ...PAGE,
      errors: [{ feedId: "feed-1", code: "rss_redirect_not_allowed" }],
    };
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_rss_page" || command === "refresh_rss") return failedPage as never;
      if (command === "set_rss_preferences") return { ...failedPage, showSummaries: false } as never;
      throw new Error(`Unexpected command: ${command}`);
    });
    renderPage();

    expect(await screen.findByText(/不同主机、端口或非 HTTPS/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "阅读设置" }));
    const itemLimit = screen.getByRole("spinbutton", { name: /^每个订阅最多条目/ });
    await user.clear(itemLimit);
    await user.type(itemLimit, "80");
    await user.click(screen.getByRole("checkbox", { name: "在文章卡片显示摘要" }));
    await user.click(screen.getByRole("button", { name: "保存" }));
    expect(invokeMock).toHaveBeenCalledWith("set_rss_preferences", {
      request: { dtoVersion: 1, maxItemsPerFeed: 80, showSummaries: false },
    });
  });

  it("confirms before closing a dirty RSS draft and activates the route guard", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText("第一篇文章");

    await user.click(screen.getByRole("button", { name: "阅读设置" }));
    const itemLimit = screen.getByRole("spinbutton", { name: /^每个订阅最多条目/ });
    await user.clear(itemLimit);
    await user.type(itemLimit, "80");
    await user.click(screen.getByRole("button", { name: "取消" }));

    expect(
      await screen.findByRole("heading", { name: "放弃未保存的 RSS 设置？" }),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "放弃更改" }));
    expect(screen.queryByRole("spinbutton", { name: /^每个订阅最多条目/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "阅读设置" }));
    const reopenedLimit = screen.getByRole("spinbutton", { name: /^每个订阅最多条目/ });
    await user.clear(reopenedLimit);
    await user.type(reopenedLimit, "90");
    await waitFor(() => {
      expect(useAppStore.getState().dirtyScopes).toContain("rss-drafts");
    });
  });

  it("redacts backend messages when a command fails", async () => {
    invokeMock.mockRejectedValue({
      code: "rss_network_failed",
      message: "https://private.example/path?token=secret",
    });
    renderPage();
    expect(await screen.findByText("网络请求失败。")).toBeInTheDocument();
    expect(screen.queryByText(/token=secret/)).not.toBeInTheDocument();
  });
});
