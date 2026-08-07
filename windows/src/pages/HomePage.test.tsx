import { invoke } from "@tauri-apps/api/core";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { HomeSnapshot } from "../lib/ipc";
import { useAppStore } from "../store/appStore";
import HomePage from "./HomePage";

const SNAPSHOT: HomeSnapshot = {
  today: "2026-08-07",
  greeting: "今天从这里开始",
  dailyPoem: null,
  recentDiaries: [
    {
      relativePath: "2026-08-07.md",
      fileName: "2026-08-07.md",
      title: "今天",
      date: "2026-08-07",
      month: "2026-08",
      excerpt: "正文",
      wordCount: 12,
      modifiedAt: "2026-08-07T08:00:00Z",
      trashed: false,
    },
  ],
  recentThoughts: [
    {
      id: "1",
      content: "想法",
      categoryName: null,
      color: null,
      pinned: false,
      highlighted: false,
      updatedAt: "2026-08-07T08:00:00Z",
    },
  ],
  mealPhotos: [],
  dailyTemplates: [],
  currentDiaryRelativePath: "2026-08-07.md",
  monthlyDiaryCount: 1,
  monthlyThoughtCount: 1,
  totalWordCount: 12,
  yearProgress: 0.6,
  randomDiary: null,
};

function renderHome() {
  return render(
    <MemoryRouter>
      <HomePage />
    </MemoryRouter>,
  );
}

describe("HomePage", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => {
    invokeMock.mockReset();
    useAppStore.getState().setLanguage("zh-CN");
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_home_snapshot") return SNAPSHOT as never;
      if (command === "get_windows_settings") {
        return {
          homeWidgets: [
            "record_overview",
            "website",
            "game_shortcuts",
            "notes",
            "weather",
            "today",
            "record_overview",
          ],
          homeWidgetTitles: ["game_shortcuts"],
          homeWidgetBordersEnabled: false,
          homeGameShortcuts: [
            "2048",
            "2048_5",
            "2048_6",
            "snake",
            "tetris",
            "minesweeper",
            "spider",
          ],
          mealButtonsUseIcons: false,
          mealButtonIcons: ["🥪", "🍱", "🍹", "🍜", "🍊", "🍤"],
        } as never;
      }
      if (command === "list_diaries") {
        return [
          SNAPSHOT.recentDiaries[0],
          { ...SNAPSHOT.recentDiaries[0], relativePath: "2026-08-06.md", date: "2026-08-06" },
        ] as never;
      }
      if (command === "list_thoughts") return [{ id: "1" }] as never;
      if (command === "list_date_records") {
        return [
          {
            id: "1",
            name: "目标",
            icon: "🎯",
            dateIso: "2026-08-08",
            createdAt: "1",
            updatedAt: "1",
          },
        ] as never;
      }
      if (command === "list_poems") return [{ id: "1" }] as never;
      if (command === "get_daily_poem") {
        return {
          content: "诗句",
          title: "诗题",
          source: "出处",
          author: "作者",
          dynasty: "唐",
          fromCache: true,
          usedFallback: false,
        } as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });
  });

  it("renders supported widgets in configured order with title and border settings", async () => {
    const { container } = renderHome();
    expect(await screen.findByRole("heading", { name: "今天从这里开始" })).toBeInTheDocument();

    const widgets = [...container.querySelectorAll<HTMLElement>("[data-home-widget]")];
    expect(widgets.map((widget) => widget.dataset.homeWidget)).toEqual([
      "record_overview",
      "game_shortcuts",
      "notes",
      "today",
    ]);
    expect(widgets.every((widget) => widget.classList.contains("is-borderless"))).toBe(true);
    expect(widgets.every((widget) => !widget.classList.contains("card"))).toBe(true);
    expect(screen.queryByRole("heading", { name: "记录概览" })).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "小游戏" })).toBeInTheDocument();

    const overview = container.querySelector<HTMLElement>(
      '[data-home-widget="record_overview"]',
    );
    expect(overview).not.toBeNull();
    expect(within(overview!).getByText("日记")).toBeInTheDocument();
    expect(within(overview!).getByText("诗词")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "2048 · 6×6" })).toHaveAttribute(
      "href",
      "/games?game=2048_6",
    );
    expect(screen.getByRole("link", { name: "蜘蛛纸牌" })).toHaveAttribute(
      "href",
      "/games?game=spider",
    );
    expect(screen.getByRole("link", { name: /打开笔记/ })).toHaveAttribute("href", "/notes");
    expect(container.querySelector('[data-home-widget="website"]')).toBeNull();
    expect(container.querySelector('[data-home-widget="weather"]')).toBeNull();
  });

  it("shows an actionable empty state when only unsupported Android widgets are configured", async () => {
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_home_snapshot") return SNAPSHOT as never;
      if (command === "get_windows_settings") {
        return {
          homeWidgets: ["website", "weather"],
          homeWidgetTitles: ["website", "weather"],
          homeWidgetBordersEnabled: true,
          homeGameShortcuts: [],
          mealButtonsUseIcons: false,
          mealButtonIcons: [],
        } as never;
      }
      if (["list_diaries", "list_thoughts", "list_date_records", "list_poems"].includes(command)) {
        return [] as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });

    renderHome();
    expect(await screen.findByRole("heading", { name: "首页暂无可显示模块" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "打开主页设置" })).toHaveAttribute(
      "href",
      "/settings/home",
    );
    expect(screen.getByText(/浏览器与天气模块不会在 Windows 显示/)).toBeInTheDocument();
  });

  it("keeps available widgets visible when supplemental overview data fails", async () => {
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_home_snapshot") return SNAPSHOT as never;
      if (command === "get_windows_settings") {
        return {
          homeWidgets: ["record_overview"],
          homeWidgetTitles: ["record_overview"],
          homeWidgetBordersEnabled: true,
          homeGameShortcuts: [],
          mealButtonsUseIcons: false,
          mealButtonIcons: [],
        } as never;
      }
      if (command === "list_date_records") throw new Error("unavailable");
      if (["list_diaries", "list_thoughts", "list_poems"].includes(command)) return [] as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    renderHome();
    expect(await screen.findByRole("heading", { name: "记录概览" })).toBeInTheDocument();
    expect(screen.getByText(/部分概览数据暂时不可用/)).toBeInTheDocument();
    expect(screen.getByText("暂不可用")).toBeInTheDocument();
  });

  it("shows a retry state when the core Home snapshot cannot be loaded", async () => {
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_home_snapshot") throw new Error("offline");
      return [] as never;
    });

    renderHome();
    expect(await screen.findByRole("heading", { name: "首页暂时无法打开" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "重试" })).toBeEnabled();
  });
});
