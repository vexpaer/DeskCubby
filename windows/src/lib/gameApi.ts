import { invokeCommand } from "./ipc";

export const GAME_DTO_VERSION = 1 as const;

export const GAME_IDS = [
  "2048",
  "2048_5",
  "2048_6",
  "snake",
  "tetris",
  "minesweeper",
  "spider",
] as const;

export type GameId = (typeof GAME_IDS)[number];
export type GameSaveMode = "save" | "finish" | "clear" | "none";
export type DecimalI64 = string;

export interface GameStateV1 {
  gameId: GameId;
  highScore: number;
  saveJson: string | null;
  updatedAt: DecimalI64 | null;
  totalPlayMillis: DecimalI64;
}

export interface GameStatisticV1 {
  gameId: GameId;
  metricKey: string;
  value: DecimalI64;
  updatedAt: DecimalI64;
}

export interface GamesSnapshotV1 {
  dtoVersion: typeof GAME_DTO_VERSION;
  games: GameStateV1[];
  statistics: GameStatisticV1[];
}

export interface GameMetricDelta {
  increments?: Record<string, number | bigint>;
  maxima?: Record<string, number | bigint>;
}

export interface ApplyGameAction {
  gameId: GameId;
  saveMode: GameSaveMode;
  saveJson?: string | null;
  score: number;
  metrics?: GameMetricDelta;
}

function decimalRecord(
  values: Record<string, number | bigint> | undefined,
): Record<string, DecimalI64> {
  return Object.fromEntries(
    Object.entries(values ?? {})
      .filter(([, value]) =>
        typeof value === "bigint"
          ? value >= 0n
          : Number.isSafeInteger(value) && value >= 0,
      )
      .map(([key, value]) => [key, value.toString()]),
  );
}

export const gameApi = {
  snapshot(): Promise<GamesSnapshotV1> {
    return invokeCommand("get_games_snapshot");
  },

  applyAction(action: ApplyGameAction): Promise<GamesSnapshotV1> {
    return invokeCommand("apply_game_action", {
      request: {
        dtoVersion: GAME_DTO_VERSION,
        gameId: action.gameId,
        saveMode: action.saveMode,
        saveJson: action.saveMode === "save" ? action.saveJson ?? null : null,
        score: action.score,
        increments: decimalRecord(action.metrics?.increments),
        maxima: decimalRecord(action.metrics?.maxima),
      },
    });
  },

  addPlayTime(
    gameId: GameId,
    deltaMillis: number,
  ): Promise<GamesSnapshotV1> {
    return invokeCommand("add_game_play_time", {
      request: {
        dtoVersion: GAME_DTO_VERSION,
        gameId,
        deltaMillis: Math.max(1, Math.trunc(deltaMillis)).toString(),
      },
    });
  },
};

export function gameState(
  snapshot: GamesSnapshotV1 | null,
  gameId: GameId,
): GameStateV1 | null {
  return snapshot?.games.find((game) => game.gameId === gameId) ?? null;
}
