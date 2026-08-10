import { invoke } from "@tauri-apps/api/core";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useAppStore } from "../store/appStore";
import StatsPage from "./StatsPage";

describe("StatsPage", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => {
    useAppStore.setState((state) => ({
      ...state,
      appearance: { ...state.appearance, language: "zh-CN" },
    }));
    invokeMock.mockReset();
    invokeMock.mockImplementation(async (command) => {
      if (command === "list_diaries") {
        return [{
          relativePath: "2026-08-07.md",
          fileName: "2026-08-07.md",
          title: "今天",
          date: "2026-08-07",
          month: "2026-08",
          excerpt: "",
          wordCount: 123,
          modifiedAt: "",
          trashed: false,
        }] as never;
      }
      if (command === "get_usage_page" || command === "get_health_page") return null as never;
      if (command === "get_reader_library") {
        return { dtoVersion: 1, books: [], preferences: {}, totalReadingMillis: "0" } as never;
      }
      if (command === "get_games_snapshot") {
        return {
          dtoVersion: 1,
          games: [
            "2048", "2048_5", "2048_6", "snake", "tetris", "minesweeper", "spider", "go",
          ].map((gameId) => ({
            gameId,
            highScore: gameId === "go" ? 4 : 0,
            saveJson: null,
            updatedAt: gameId === "go" ? "1" : null,
            totalPlayMillis: gameId === "go" ? "60000" : "0",
          })),
          statistics: [{ gameId: "go", metricKey: "goMovesPlayed", value: "8", updatedAt: "1" }],
        } as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });
  });

  it("keeps unavailable phone and health values unknown instead of fabricating zero", async () => {
    const user = userEvent.setup();
    render(<StatsPage />);
    expect(await screen.findByText("123 字")).toBeInTheDocument();
    expect(screen.getAllByText("尚无可信数据").length).toBeGreaterThanOrEqual(2);

    await user.click(screen.getByRole("button", { name: /近 7 天步数/ }));
    expect(screen.getByText("近 7 天步数")).toBeInTheDocument();
    expect(screen.getAllByText("—").length).toBeGreaterThan(0);
  });

  it("shows Go capture and lifetime metrics as private local statistics", async () => {
    const user = userEvent.setup();
    render(<StatsPage />);
    await user.click(await screen.findByRole("button", { name: /小游戏/ }));
    expect(screen.getByRole("heading", { name: "围棋" })).toBeInTheDocument();
    expect(screen.getByText("最高提子")).toBeInTheDocument();
    expect(screen.getByText("落子")).toBeInTheDocument();
    expect(screen.getByText("仅限本机（不进入 v28）")).toBeInTheDocument();
  });
});
