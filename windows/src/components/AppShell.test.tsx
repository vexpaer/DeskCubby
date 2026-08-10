import { listen, type Event as TauriEvent } from "@tauri-apps/api/event";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createDefaultDesktopNavigationPreferences } from "./desktopNavigation";
import { useAppStore } from "../store/appStore";
import { AppShell } from "./AppShell";
import "../styles/components.css";

function renderShell(initialPath = "/") {
  const router = createMemoryRouter([
    {
      path: "*",
      element: <AppShell>page content</AppShell>,
    },
  ], { initialEntries: [initialPath] });
  return { ...render(<RouterProvider router={router} />), router };
}

describe("AppShell", () => {
  const listenMock = vi.mocked(listen);

  beforeEach(() => {
    window.localStorage.clear();
    listenMock.mockReset();
    listenMock.mockResolvedValue(vi.fn());
    useAppStore.setState({
      appearance: {
        language: "zh-CN",
        visualTheme: "material",
        colorMode: "system",
        fontScale: 1,
        compactMode: false,
      },
      sidebarCollapsed: false,
      desktopNavigation: createDefaultDesktopNavigationPreferences(),
      collapsedNavigationCategoryIds: [],
      mobileNavigationOpen: false,
      toasts: [],
    });
  });

  it("renders only the supported Windows destinations", () => {
    renderShell();

    expect(screen.getByRole("link", { name: "日记" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "诗词本" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "笔记" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "阅读" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "RSS 订阅" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "AI 聊天" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "收藏夹" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "小游戏" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "统计" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "手机使用时间" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "健康" })).toBeInTheDocument();
    expect(screen.getByText("本地优先")).toBeInTheDocument();
    expect(screen.queryByText("离线可用")).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "浏览器" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "桌面小卡片" })).not.toBeInTheDocument();
  });

  it("collapses the desktop sidebar", () => {
    const { container } = renderShell();

    fireEvent.click(screen.getByRole("button", { name: "收起侧栏" }));

    expect(container.querySelector(".app-layout")).toHaveClass(
      "sidebar-is-collapsed",
    );
    expect(screen.getByTitle("日记")).toBeInTheDocument();
  });

  it("traps the narrow-window drawer and restores focus after Escape", async () => {
    const user = userEvent.setup();
    const { container } = renderShell();
    // jsdom does not evaluate the responsive media query that makes this
    // control visible, so select the stable class to exercise its handler.
    const menuButton = container.querySelector<HTMLButtonElement>(
      ".mobile-menu-button",
    );
    expect(menuButton).not.toBeNull();

    await user.click(menuButton!);
    expect(container.querySelector(".app-layout")).toHaveClass(
      "mobile-navigation-is-open",
    );
    expect(
      screen.getByRole("dialog", { name: "DeskCubby 页面导航" }),
    ).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "收起分类：记录" }));
    expect(screen.queryByRole("link", { name: "日记" })).not.toBeInTheDocument();
    const settingsLink = screen.getByRole("link", { name: "设置" });
    settingsLink.focus();
    await user.tab();
    expect(screen.getByRole("link", { name: "DeskCubby" })).toHaveFocus();

    await user.keyboard("{Escape}");
    await waitFor(() => expect(menuButton).toHaveFocus());
    expect(container.querySelector(".app-layout")).not.toHaveClass(
      "mobile-navigation-is-open",
    );
  });

  it("switches every sidebar destination to English", () => {
    useAppStore.setState((state) => ({
      appearance: { ...state.appearance, language: "en" },
    }));

    renderShell();

    expect(screen.getByRole("link", { name: "Diary" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Meal calendar" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Thoughts" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Vault" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Phone screen time" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Settings" })).toBeInTheDocument();
    expect(screen.getByText("Local first")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Browser" })).not.toBeInTheDocument();
  });

  it("visually collapses a category without leaving its links in the tab order", async () => {
    const user = userEvent.setup();
    renderShell();

    const toggle = screen.getByRole("button", { name: "收起分类：记录" });
    const categoryItems = document.getElementById("navigation-category-capture");
    expect(categoryItems).not.toBeNull();
    expect(window.getComputedStyle(categoryItems!).display).toBe("grid");
    await user.click(toggle);

    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(window.getComputedStyle(categoryItems!).display).toBe("none");
    expect(screen.queryByRole("link", { name: "日记" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "设置" })).toBeInTheDocument();
    expect(useAppStore.getState().collapsedNavigationCategoryIds).toContain(
      "capture",
    );
    const persisted = JSON.parse(
      window.localStorage.getItem("deskcubby-window-preferences") ?? "{}",
    ) as { state?: { collapsedNavigationCategoryIds?: string[] } };
    expect(persisted.state?.collapsedNavigationCategoryIds).toContain("capture");

    await user.click(screen.getByRole("button", { name: "展开分类：记录" }));
    expect(screen.getByRole("link", { name: "日记" })).toBeInTheDocument();
  });

  it("honors custom category order and hidden pages while keeping settings", () => {
    const preferences = createDefaultDesktopNavigationPreferences();
    preferences.categories[0].itemIds = ["daily", "home", "diary", "meals"];
    preferences.categories.unshift({
      id: "empty",
      chinese: "空分类",
      english: "Empty category",
      itemIds: [],
    });
    preferences.hiddenItemIds = ["poetry"];
    useAppStore.setState({ desktopNavigation: preferences });

    renderShell();

    const daily = screen.getByRole("link", { name: "日常记录" });
    const home = screen.getByRole("link", { name: "首页" });
    expect(
      daily.compareDocumentPosition(home) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(screen.queryByRole("link", { name: "诗词本" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "空分类" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "设置" })).toBeInTheDocument();
  });

  it("moves away safely when a preference update hides the active route", async () => {
    const { router } = renderShell("/diary");
    const preferences = createDefaultDesktopNavigationPreferences();
    preferences.hiddenItemIds = ["diary"];

    act(() => {
      useAppStore.getState().setDesktopNavigation(preferences);
    });

    await waitFor(() => expect(router.state.location.pathname).toBe("/"));
  });

  it("keeps settings reachable when every configurable page is hidden", () => {
    const preferences = createDefaultDesktopNavigationPreferences();
    preferences.hiddenItemIds = preferences.categories.flatMap(
      (category) => category.itemIds,
    );
    useAppStore.setState({ desktopNavigation: preferences });

    renderShell("/settings");

    expect(screen.getByRole("link", { name: "设置" })).toBeInTheDocument();
    expect(
      screen.getByRole("navigation", { name: "DeskCubby 页面导航" }),
    ).toBeEmptyDOMElement();
  });

  it("falls back to settings if hiding the active page leaves no visible page", async () => {
    const { router } = renderShell("/diary");
    const preferences = createDefaultDesktopNavigationPreferences();
    preferences.hiddenItemIds = preferences.categories.flatMap(
      (category) => category.itemIds,
    );

    act(() => {
      useAppStore.getState().setDesktopNavigation(preferences);
    });

    await waitFor(() =>
      expect(router.state.location.pathname).toBe("/settings"),
    );
  });

  it("labels nested cloud and About routes in the window toolbar", () => {
    const { unmount } = renderShell("/settings/cloud");
    expect(screen.getByText("云端同步")).toBeInTheDocument();
    unmount();

    renderShell("/settings/about");
    expect(screen.getByText("关于")).toBeInTheDocument();
  });

  it("shows a persistent actionable toast for a safe update event", async () => {
    const user = userEvent.setup();
    let updateListener:
      | ((event: TauriEvent<unknown>) => void)
      | undefined;
    listenMock.mockImplementation(async (event, handler) => {
      if (event === "update-available") {
        updateListener = handler as (event: TauriEvent<unknown>) => void;
      }
      return vi.fn();
    });

    renderShell();
    expect(updateListener).toBeDefined();
    act(() => {
      updateListener?.({
        event: "update-available",
        id: 1,
        payload: {
          schemaVersion: 1,
          currentVersion: "0.2.0",
          version: "0.3.0",
          notes: null,
          publishedAt: null,
        },
      });
    });

    expect(await screen.findByText("发现 DeskCubby 更新")).toBeInTheDocument();
    expect(
      screen.getByText("新版本 0.3.0 已可用；不会自动下载或安装。"),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("link", { name: "查看更新" }));

    expect(screen.getByText("关于")).toBeInTheDocument();
    expect(screen.queryByText("发现 DeskCubby 更新")).not.toBeInTheDocument();
  });
});
