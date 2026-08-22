/**
 * Page registry: each entry maps a route path to its page module.
 * Feature implementers create the page files; do not edit this table unless adding
 * a brand-new top-level route.
 */
import React, { Suspense, lazy } from "react";

export const routeTable: Record<string, () => Promise<{ default: React.ComponentType }>> = {
  "/login": () => import("./login/LoginPage"),

  "/home": () => import("./home/HomePage"),
  "/desk": () => import("./desk/DeskPage"),

  "/diary": () => import("./diary/DiaryListPage"),
  "/diary/edit": () => import("./diary/DiaryEditPage"),
  "/diary/trash": () => import("./diary/DiaryTrashPage"),
  "/meals": () => import("./meals/MealCalendarPage"),
  "/meals/filter": () => import("./meals/MealFilterPage"),
  "/daily": () => import("./daily/DailyRecordsPage"),

  "/notes": () => import("./notes/NotesPage"),
  "/blog": () => import("./blog/BrowserPage"),

  "/thought": () => import("./thought/ThoughtPage"),
  "/thought/trash": () => import("./thought/ThoughtTrashPage"),
  "/date_records": () => import("./date/DateRecordsPage"),
  "/poetry_book": () => import("./poetry/PoetryBookPage"),
  "/rss": () => import("./rss/RssPage"),

  "/ai_chat": () => import("./ai/AiChatPage"),
  "/vault": () => import("./vault/VaultPage"),
  "/reader": () => import("./reader/ReaderPage"),

  "/games": () => import("./games/GamesListPage"),
  "/games/2048": () => import("./games/Game2048Page"),
  "/games/snake": () => import("./games/SnakePage"),
  "/games/tetris": () => import("./games/TetrisPage"),
  "/games/minesweeper": () => import("./games/MinesweeperPage"),
  "/games/spider": () => import("./games/SpiderPage"),
  "/games/go": () => import("./games/GoPage"),

  "/statistics": () => import("./statshub/StatsHubPage"),
  "/usage_statistics": () => import("./usage/UsagePage"),
  "/step_statistics": () => import("./steps/HealthPage"),
  "/desktop_widgets": () => import("./widgets/WidgetsPage"),

  "/settings": () => import("./settings/SettingsPage"),
};

export function buildRoutes() {
  return Object.entries(routeTable).map(([path, loader]) => ({
    path,
    element: (
      <Suspense fallback={<div className="dc-center" style={{ padding: 48 }}><span className="dc-muted">…</span></div>}>
        {React.createElement(lazy(loader))}
      </Suspense>
    ),
  }));
}
