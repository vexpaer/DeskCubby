import { invoke } from "@tauri-apps/api/core";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useAppStore } from "../store/appStore";
import GamesPage from "./GamesPage";

function renderGames(entry = "/games") {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <GamesPage />
    </MemoryRouter>,
  );
}

const snapshot = {
  dtoVersion: 1,
  games: [
    "2048", "2048_5", "2048_6", "snake", "tetris", "minesweeper", "spider",
  ].map((gameId) => ({
    gameId,
    highScore: 0,
    saveJson: null,
    updatedAt: null,
    totalPlayMillis: "0",
  })),
  statistics: [],
};

describe("GamesPage", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => {
    useAppStore.setState((state) => ({
      ...state,
      appearance: { ...state.appearance, language: "zh-CN" },
    }));
    invokeMock.mockReset();
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_games_snapshot" || command === "apply_game_action" || command === "add_game_play_time")
        return snapshot as never;
      throw new Error(`Unexpected command: ${command}`);
    });
  });

  it("launches a real 2048 board and serializes keyboard actions through Rust", async () => {
    const user = userEvent.setup();
    renderGames();
    const heading = await screen.findByRole("heading", { name: "2048 · 4×4" });
    const card = heading.closest("article");
    expect(card).not.toBeNull();
    await user.click(within(card!).getByRole("button", { name: "开始" }));

    expect(await screen.findByLabelText("4 × 4 2048")).toBeInTheDocument();
    expect(screen.getAllByText("分数", { exact: false }).length).toBeGreaterThan(0);
    fireEvent.keyDown(window, { key: "ArrowLeft" });

    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith(
        "apply_game_action",
        expect.objectContaining({
          request: expect.objectContaining({ gameId: "2048", dtoVersion: 1 }),
        }),
      );
    });
  });

  it("offers all seven stable Android game identifiers as playable entries", async () => {
    renderGames();
    expect(await screen.findByRole("heading", { name: "2048 · 4×4" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "2048 · 5×5" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "2048 · 6×6" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "贪吃蛇" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "俄罗斯方块" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "扫雷" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "单花色蜘蛛纸牌" })).toBeInTheDocument();
  });

  it("opens a validated home shortcut and resumes its Android-compatible save", async () => {
    const saved2048 = JSON.stringify({
      size: 4,
      cells: [2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
      score: 12,
      undoHistory: [],
      winRecorded: false,
      lossRecorded: false,
    });
    const resumed = {
      ...snapshot,
      games: snapshot.games.map((game) =>
        game.gameId === "2048" ? { ...game, saveJson: saved2048 } : game,
      ),
    };
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_games_snapshot" || command === "apply_game_action") {
        return resumed as never;
      }
      if (command === "add_game_play_time") return resumed as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    renderGames("/games?game=2048");

    expect(await screen.findByLabelText("4 × 4 2048")).toBeInTheDocument();
    expect(screen.getByText("分数 12")).toBeInTheDocument();
    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith(
        "apply_game_action",
        expect.objectContaining({
          request: expect.objectContaining({ gameId: "2048", saveJson: saved2048 }),
        }),
      );
    });
  });
});
