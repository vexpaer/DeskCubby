import { listen, type Event as TauriEvent } from "@tauri-apps/api/event";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { act, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useAppStore } from "../store/appStore";
import { AppShell } from "./AppShell";

function renderShell(initialPath = "/") {
  const router = createMemoryRouter([
    {
      path: "*",
      element: <AppShell>page content</AppShell>,
    },
  ], { initialEntries: [initialPath] });
  return render(<RouterProvider router={router} />);
}

describe("AppShell", () => {
  const listenMock = vi.mocked(listen);

  beforeEach(() => {
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
