/**
 * 小游戏 GamesListPage (/games) — web port of the Android game list page in
 * ui/games/GamesScreen.kt (README_for_ai.md §13 游戏列表页):
 * eight cards — 2048 · 4×4 / 5×5 / 6×6, 贪吃蛇, 俄罗斯方块, 扫雷, 蜘蛛纸牌, 围棋 —
 * each with its own high score from GET /api/games/states/<id> (404/missing rows are
 * tolerated), 继续 + 新游戏 when a save exists (新游戏 clears the save first so the
 * target page starts fresh) and 开始 otherwise, plus a 特色统计 section fed by
 * GET /api/games/statistics with metric labels mirroring the Android keys in
 * StatisticsHubScreen.kt (incl. win rate for minesweeper/spider).
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { apiGet, apiSend } from "../../api/client";
import { tr } from "../../i18n/tr";
import { ErrorText, PageTutorialOverlay, Spinner, TopBar } from "../../components/ui";

interface GameStateDto {
  gameId?: string;
  highScore?: number;
  saveJson?: string | null;
}

interface StatisticsPayload {
  games?: Record<string, Record<string, number>>;
  byGameId?: { gameId: string; metrics: Record<string, number> }[];
}

interface GameEntry {
  gameId: string;
  title: string;
  subtitle: string;
  route: string;
  bestLabel: string;
}

const GAME_ENTRIES: GameEntry[] = [
  {
    gameId: "2048",
    title: "2048 · 4×4",
    subtitle: tr("经典棋盘，节奏紧凑", "Classic compact board"),
    route: "/games/2048",
    bestLabel: tr("最高分", "Best"),
  },
  {
    gameId: "2048_5",
    title: "2048 · 5×5",
    subtitle: tr("空间更大，适合长局", "More space for longer games"),
    route: "/games/2048?size=5",
    bestLabel: tr("最高分", "Best"),
  },
  {
    gameId: "2048_6",
    title: "2048 · 6×6",
    subtitle: tr("最大棋盘，挑战高分", "Largest board for high scores"),
    route: "/games/2048?size=6",
    bestLabel: tr("最高分", "Best"),
  },
  {
    gameId: "snake",
    title: tr("贪吃蛇", "Snake"),
    subtitle: tr("吃食物长大，别撞墙或自己", "Eat food and avoid walls and yourself"),
    route: "/games/snake",
    bestLabel: tr("最高分", "Best"),
  },
  {
    gameId: "tetris",
    title: tr("俄罗斯方块", "Tetris"),
    subtitle: tr("旋转方块，消除整行", "Rotate pieces and clear lines"),
    route: "/games/tetris",
    bestLabel: tr("最高分", "Best"),
  },
  {
    gameId: "minesweeper",
    title: tr("扫雷", "Minesweeper"),
    subtitle: tr("自定义行数、列数和雷数", "Custom rows, columns, and mine count"),
    route: "/games/minesweeper",
    bestLabel: tr("最高分", "Best"),
  },
  {
    gameId: "spider",
    title: tr("蜘蛛纸牌", "Spider Solitaire"),
    subtitle: tr("横屏一花色玩法，支持保存与撤回", "Landscape one-suit play with save and undo"),
    route: "/games/spider",
    bestLabel: tr("最高分", "Best"),
  },
  {
    gameId: "go",
    title: tr("围棋", "Go"),
    subtitle: tr("本地双人，支持 9/13/19 路与基本规则", "Local two-player play on 9/13/19 boards with core rules"),
    route: "/games/go",
    bestLabel: tr("最高提子", "Best captures"),
  },
];

/** Metric display order per game, mirroring StatisticsHubScreen.GAME_METRIC_ORDER. */
const GAME_METRIC_ORDER: Record<string, string[]> = {
  "2048": ["moveAttempts", "effectiveMoves", "merges", "highestTile", "wins"],
  "2048_5": ["moveAttempts", "effectiveMoves", "merges", "highestTile", "wins"],
  "2048_6": ["moveAttempts", "effectiveMoves", "merges", "highestTile", "wins"],
  snake: ["foodEaten", "maxLength", "losses"],
  tetris: ["piecesLocked", "linesCleared", "tetrises", "losses"],
  minesweeper: ["minesCellsRevealed", "minesSwept", "flagsPlaced", "wins", "losses"],
  spider: ["spiderCardMoves", "spiderDeals", "spiderUndos", "wins", "losses"],
  go: ["goMovesPlayed", "goStonesCaptured", "goPasses", "goGamesCompleted"],
};

/** Only 扫雷 / 蜘蛛纸牌 show a win rate on Android. */
const GAMES_WITH_WIN_RATE = new Set(["minesweeper", "spider"]);

function metricLabel(key: string): string {
  switch (key) {
    case "moveAttempts":
      return tr("总操作次数", "Total moves");
    case "effectiveMoves":
      return tr("有效移动", "Effective moves");
    case "merges":
      return tr("合并次数", "Tile merges");
    case "highestTile":
      return tr("最高方块", "Highest tile");
    case "wins":
      return tr("获胜次数", "Wins");
    case "losses":
      return tr("失败次数", "Losses");
    case "foodEaten":
      return tr("吃到食物", "Food eaten");
    case "maxLength":
      return tr("最长蛇身", "Maximum length");
    case "piecesLocked":
      return tr("落定方块", "Pieces locked");
    case "linesCleared":
      return tr("消除行数", "Lines cleared");
    case "tetrises":
      return tr("四消次数", "Tetrises");
    case "minesCellsRevealed":
      return tr("揭开格数", "Cells revealed");
    case "minesSwept":
      return tr("累计排雷", "Mines swept");
    case "flagsPlaced":
      return tr("插旗次数", "Flags placed");
    case "spiderCardMoves":
      return tr("移动次数", "Card moves");
    case "spiderDeals":
      return tr("发牌次数", "Deals");
    case "spiderUndos":
      return tr("撤回次数", "Undos");
    case "goMovesPlayed":
      return tr("落子次数", "Stones played");
    case "goStonesCaptured":
      return tr("提子总数", "Stones captured");
    case "goPasses":
      return tr("停着次数", "Passes");
    case "goGamesCompleted":
      return tr("完成棋局", "Games completed");
    default:
      return key;
  }
}

function formatInteger(value: number): string {
  return new Intl.NumberFormat().format(Math.max(0, Math.round(value)));
}

export default function GamesListPage() {
  const navigate = useNavigate();
  const [states, setStates] = useState<Record<string, GameStateDto> | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [stats, setStats] = useState<Record<string, Record<string, number>> | null>(null);

  const loadStates = useCallback(async () => {
    setLoadError(null);
    try {
      const results = await Promise.all(
        GAME_ENTRIES.map(async (entry): Promise<[string, GameStateDto]> => {
          try {
            const data = await apiGet<GameStateDto>(`/api/games/states/${encodeURIComponent(entry.gameId)}`);
            return [entry.gameId, data ?? {}];
          } catch (error) {
            if (error instanceof Error && /404/.test(error.message)) return [entry.gameId, {}];
            // Missing rows degrade to an empty state like the Android first-run list.
            return [entry.gameId, {}];
          }
        }),
      );
      setStates(Object.fromEntries(results));
    } catch (error) {
      setLoadError(error instanceof Error ? error.message : String(error));
    }
  }, []);

  const loadStats = useCallback(async () => {
    try {
      const payload = await apiGet<StatisticsPayload>("/api/games/statistics");
      if (payload && typeof payload === "object" && payload.games && typeof payload.games === "object") {
        setStats(payload.games);
      } else if (Array.isArray(payload?.byGameId)) {
        const grouped: Record<string, Record<string, number>> = {};
        for (const row of payload.byGameId) {
          if (row && typeof row.gameId === "string" && row.metrics && typeof row.metrics === "object") {
            grouped[row.gameId] = row.metrics;
          }
        }
        setStats(grouped);
      } else {
        setStats({});
      }
    } catch {
      // The statistics section is optional; hide it rather than failing the page.
      setStats({});
    }
  }, []);

  useEffect(() => {
    void loadStates();
    void loadStats();
  }, [loadStates, loadStats]);

  /** 新游戏 clears that game's save first so the target page deals a fresh round. */
  const startFresh = useCallback(
    async (gameId: string, route: string) => {
      try {
        await apiSend(`/api/games/states/${encodeURIComponent(gameId)}`, "PUT", { clearSave: true });
      } catch {
        /* the game page still works with its previous save */
      }
      navigate(route);
    },
    [navigate],
  );

  const statGroups = useMemo(() => {
    if (!stats) return [];
    return GAME_ENTRIES.map((entry) => {
      const metrics = stats[entry.gameId];
      if (!metrics || typeof metrics !== "object") return null;
      const orderedKeys = GAME_METRIC_ORDER[entry.gameId] ?? Object.keys(metrics);
      const rows = orderedKeys
        .filter((key) => typeof metrics[key] === "number")
        .map((key) => ({ label: metricLabel(key), value: formatInteger(metrics[key]) }));
      const wins = typeof metrics["wins"] === "number" ? metrics["wins"] : 0;
      const losses = typeof metrics["losses"] === "number" ? metrics["losses"] : 0;
      const finished = wins + losses;
      if (GAMES_WITH_WIN_RATE.has(entry.gameId) && finished > 0) {
        rows.push({
          label: tr("胜率", "Win rate"),
          value: `${((wins / finished) * 100).toFixed(1)}%`,
        });
      }
      if (rows.length === 0) return null;
      return { entry, rows };
    }).filter((g): g is { entry: GameEntry; rows: { label: string; value: string }[] } => g !== null);
  }, [stats]);

  return (
    <div className="dc-page">
      <TopBar title={tr("小游戏", "Mini games")} />

      {states === null ? (
        loadError ? (
          <div role="alert" className="dc-col dc-center" style={{ padding: 32 }}>
            <ErrorText error={loadError} />
            <button className="dc-btn dc-btn-tonal" onClick={() => void loadStates()}>
              {tr("重试", "Retry")}
            </button>
          </div>
        ) : (
          <Spinner />
        )
      ) : (
        <>
          <div
            style={{
              display: "grid",
              gridTemplateColumns: "repeat(auto-fill, minmax(min(260px, 100%), 1fr))",
              gap: 12,
              padding: "12px 0",
            }}
          >
            {GAME_ENTRIES.map((entry) => {
              const state = states[entry.gameId] ?? {};
              const highScore = typeof state.highScore === "number" ? state.highScore : 0;
              const hasSave = typeof state.saveJson === "string" && state.saveJson.length > 0;
              return (
                <div key={entry.gameId} className="dc-card dc-col" style={{ padding: 16, gap: 10 }}>
                  <div className="dc-row" style={{ alignItems: "flex-start", gap: 12 }}>
                    <div className="dc-grow" style={{ minWidth: 0 }}>
                      <div style={{ fontWeight: 600 }}>{entry.title}</div>
                      <div className="dc-muted" style={{ fontSize: "0.85em", marginTop: 2 }}>{entry.subtitle}</div>
                    </div>
                    <div className="dc-col dc-center" style={{ alignItems: "flex-end" }}>
                      <span className="dc-muted" style={{ fontSize: "0.75em" }}>{entry.bestLabel}</span>
                      <span style={{ fontWeight: 600, color: "var(--dc-primary)", fontSize: "1.15em" }}>
                        {formatInteger(highScore)}
                      </span>
                    </div>
                  </div>
                  <div className="dc-row" style={{ gap: 10 }}>
                    {hasSave ? (
                      <>
                        <button className="dc-btn dc-btn-filled" onClick={() => navigate(entry.route)}>
                          {tr("继续", "Resume")}
                        </button>
                        <button className="dc-btn dc-btn-tonal" onClick={() => void startFresh(entry.gameId, entry.route)}>
                          {tr("新游戏", "New game")}
                        </button>
                      </>
                    ) : (
                      <button className="dc-btn dc-btn-filled" onClick={() => navigate(entry.route)}>
                        {tr("开始", "Start")}
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>

          {/* 特色统计 section */}
          <div style={{ marginTop: 16 }}>
            <div className="dc-title" style={{ fontSize: "1.15em", marginBottom: 8 }}>
              {tr("特色统计", "Feature statistics")}
            </div>
            {stats === null ? (
              <Spinner />
            ) : statGroups.length === 0 ? (
              <div className="dc-card dc-col dc-center" style={{ padding: 28 }}>
                <span className="dc-muted">
                  {tr("还没有任何游戏记录，开始一局后这里会显示累计数据。", "No game records yet. Play a round to see lifetime statistics here.")}
                </span>
              </div>
            ) : (
              <div
                style={{
                  display: "grid",
                  gridTemplateColumns: "repeat(auto-fill, minmax(min(300px, 100%), 1fr))",
                  gap: 12,
                }}
              >
                {statGroups.map(({ entry, rows }) => (
                  <div key={entry.gameId} className="dc-card dc-col" style={{ padding: 14, gap: 6 }}>
                    <div style={{ fontWeight: 600 }}>{entry.title}</div>
                    {rows.map((row) => (
                      <div key={row.label} className="dc-row" style={{ justifyContent: "space-between", fontSize: "0.9em" }}>
                        <span className="dc-muted">{row.label}</span>
                        <span style={{ fontWeight: 500 }}>{row.value}</span>
                      </div>
                    ))}
                  </div>
                ))}
              </div>
            )}
          </div>
        </>
      )}
      <PageTutorialOverlay
        pageKey="games"
        title={tr("小游戏", "Games")}
        lines={[tr("各游戏的最高分与存档自动保存，并计入统计中心。", "High scores and saves are kept automatically and feed the statistics hub.")]}
      />
    </div>
  );
}
