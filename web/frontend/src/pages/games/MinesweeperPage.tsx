/**
 * 自定义扫雷 MinesweeperPage (/games/minesweeper) — web port of the Android
 * MinesweeperPage in ui/games/AdditionalGames.kt on top of engineMinesweeper.ts
 * (README_for_ai.md §13 自定义扫雷):
 * - configuration dialog 自定义扫雷 with 初级/中级/高级 presets (9×9·10, 16×16·40,
 *   30×16·99) and 列数/行数/雷数 sliders bounded 6–30 × 6–30 × 1..cells−1;
 * - first reveal is safe (broad exclusion around the first cell inside the engine);
 * - tap reveals, long-press / right-click flags, plus an explicit flag-mode toggle;
 *   double-click a revealed number to chord its unflagged neighbors;
 * - remaining-mine counter, elapsed timer, win/lose dialogs (扫雷成功 / 踩到雷了,
 *   best = mines×100 + revealed safe cells);
 * - persistence identical to the other game pages: serialized PUT queue onto
 *   /api/games/states/minesweeper, score finalized once on finish, snapshot saves on
 *   pause/background/unmount, statistics deltas posted with the Kotlin metric keys.
 */
import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowLeft, Flag, RefreshCw, Timer } from "lucide-react";
import { ApiClientError, apiGet, apiSend } from "../../api/client";
import { tr } from "../../i18n/tr";
import { Modal, Snackbar, Spinner, TopBar, useSnackbar } from "../../components/ui";
import {
  MinesweeperGame,
  type MinesweeperActionResult,
  type MinesweeperCellView,
} from "./engineMinesweeper";

const GAME_ID = "minesweeper";
const LONG_PRESS_MS = 450;

interface GameStateDto {
  highScore?: number;
  saveJson?: string | null;
}

async function fetchGameState(): Promise<{ highScore: number; saveJson: string | null }> {
  try {
    const data = await apiGet<GameStateDto>(`/api/games/states/${encodeURIComponent(GAME_ID)}`);
    return {
      highScore: typeof data?.highScore === "number" ? data.highScore : 0,
      saveJson: typeof data?.saveJson === "string" ? data.saveJson : null,
    };
  } catch (error) {
    if (error instanceof ApiClientError && error.status === 404) return { highScore: 0, saveJson: null };
    throw error;
  }
}

function postStat(metricKey: string, value: number): void {
  if (value <= 0) return;
  void apiSend("/api/games/statistics", "POST", { gameId: GAME_ID, metricKey, value }).catch(() => undefined);
}

function postDelta(delta: MinesweeperActionResult["statisticsDelta"]): void {
  postStat("minesCellsRevealed", delta.minesCellsRevealed);
  postStat("minesSwept", delta.minesSwept);
  postStat("flagsPlaced", delta.flagsPlaced);
  postStat("wins", delta.wins);
  postStat("losses", delta.losses);
}

/** Mirrors AdditionalGames.saveOrFinish: finished rounds finalize the score. */
function roundScore(game: MinesweeperGame): number {
  return game.isWon ? game.mineCount * 100 + game.revealedSafeCount : 0;
}

function formatClock(totalSeconds: number): string {
  const m = Math.floor(totalSeconds / 60);
  const s = totalSeconds % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

export default function MinesweeperPage() {
  const navigate = useNavigate();
  const [snack, showSnack] = useSnackbar();

  const engineRef = useRef<MinesweeperGame | null>(null);
  const [, setTick] = useState(0);
  const bump = useCallback(() => setTick((t) => (t + 1) % 1_000_000_000), []);

  const [loaded, setLoaded] = useState(false);
  const loadedRef = useRef(false);
  const [configOpen, setConfigOpen] = useState(false);
  const [flagMode, setFlagMode] = useState(false);

  const highScoreRef = useRef(0);
  const resultRecordedRef = useRef(false);
  const saveQueueRef = useRef<Promise<void>>(Promise.resolve());

  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [visible, setVisible] = useState(!document.hidden);

  const enqueue = useCallback((fn: () => Promise<void>) => {
    const run = saveQueueRef.current.then(fn).catch(() => undefined);
    saveQueueRef.current = run;
    return run;
  }, []);

  const saveProgressNow = useCallback(() => {
    const current = engineRef.current;
    if (!current || current.isGameOver || current.isWon) return;
    const json = current.toJson();
    void enqueue(() =>
      apiSend(`/api/games/states/${encodeURIComponent(GAME_ID)}`, "PUT", { highScore: highScoreRef.current, saveJson: json }).then(() => undefined),
    );
  }, [enqueue]);

  const recordScoreNow = useCallback(
    (game: MinesweeperGame) => {
      const score = roundScore(game);
      highScoreRef.current = Math.max(highScoreRef.current, score);
      void enqueue(() =>
        apiSend(`/api/games/states/${encodeURIComponent(GAME_ID)}`, "PUT", { highScore: highScoreRef.current, saveJson: null }).then(() => undefined),
      );
    },
    [enqueue],
  );

  /** Mirrors AdditionalGameAutoSaveEffect + BackHandler. */
  const saveOrFinish = useCallback(() => {
    const current = engineRef.current;
    if (!current) return;
    if (current.isGameOver || current.isWon) recordScoreNow(current);
    else saveProgressNow();
  }, [recordScoreNow, saveProgressNow]);
  const saveOrFinishRef = useRef(saveOrFinish);
  useEffect(() => {
    saveOrFinishRef.current = saveOrFinish;
  }, [saveOrFinish]);

  // Load the save on mount; without one, open the configuration dialog like Android.
  useEffect(() => {
    let cancelled = false;
    loadedRef.current = false;
    (async () => {
      let restored: MinesweeperGame | null = null;
      try {
        const state = await fetchGameState();
        if (cancelled) return;
        highScoreRef.current = state.highScore;
        restored = state.saveJson ? MinesweeperGame.fromJson(state.saveJson) : null;
      } catch {
        restored = null;
      }
      if (cancelled) return;
      engineRef.current = restored;
      resultRecordedRef.current = restored !== null && (restored.isGameOver || restored.isWon);
      loadedRef.current = true;
      setLoaded(true);
      if (!restored) setConfigOpen(true);
      bump();
    })();
    return () => {
      cancelled = true;
    };
  }, [bump]);

  // Persist on unmount and when the tab/page goes to the background.
  useEffect(() => {
    const onVisibility = () => {
      setVisible(!document.hidden);
      if (document.hidden) saveOrFinishRef.current();
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      saveOrFinishRef.current();
    };
  }, []);

  const applyAction = useCallback(
    (result: MinesweeperActionResult) => {
      if (!result.changed) return;
      postDelta(result.statisticsDelta);
      bump();
      const current = engineRef.current;
      if (current && !current.isGameOver && !current.isWon) saveProgressNow();
    },
    [bump, saveProgressNow],
  );

  // Finalize the score exactly once when the round ends.
  const engine = engineRef.current;
  const finished = engine ? engine.isGameOver || engine.isWon : false;
  useEffect(() => {
    const current = engineRef.current;
    if (!loaded || !current || !(current.isGameOver || current.isWon) || resultRecordedRef.current) return;
    resultRecordedRef.current = true;
    recordScoreNow(current);
  }, [loaded, finished, recordScoreNow, engine]);

  // Elapsed timer: runs from load until the round finishes (pauses when hidden/configuring).
  useEffect(() => {
    if (!loaded || finished || configOpen || !visible) return;
    const timer = window.setInterval(() => setElapsedSeconds((s) => s + 1), 1000);
    return () => window.clearInterval(timer);
  }, [loaded, finished, configOpen, visible]);

  const exitToList = useCallback(() => {
    saveOrFinishRef.current();
    navigate("/games");
  }, [navigate]);

  const startNewBoard = useCallback(
    (width: number, height: number, mines: number) => {
      engineRef.current = new MinesweeperGame(width, height, mines);
      resultRecordedRef.current = false;
      setElapsedSeconds(0);
      setConfigOpen(false);
      // 清除上一份暂停存档 (mirrors viewModel.clearSave).
      void enqueue(() =>
        apiSend(`/api/games/states/${encodeURIComponent(GAME_ID)}`, "PUT", { clearSave: true }).then(() => undefined),
      );
      bump();
    },
    [bump, enqueue],
  );

  const remainingMines = engine ? engine.remainingMines : 0;

  return (
    <div className="dc-page dc-col" style={{ maxWidth: 1100, margin: "0 auto", width: "100%" }}>
      <Snackbar message={snack} />
      <TopBar
        title={tr("扫雷", "Minesweeper")}
        back
        onBack={exitToList}
        actions={
          <>
            <span className="dc-chip" title={tr("剩余雷数", "Mines left")}>
              {tr(`剩余 ${remainingMines}`, `${remainingMines} left`)}
            </span>
            <span className="dc-chip">
              <Timer size={14} aria-hidden />
              {formatClock(elapsedSeconds)}
            </span>
            <button
              className="dc-icon-btn"
              aria-label={flagMode ? tr("关闭插旗模式", "Disable flag mode") : tr("开启插旗模式", "Enable flag mode")}
              title={tr("插旗模式", "Flag mode")}
              aria-pressed={flagMode}
              onClick={() => setFlagMode((f) => !f)}
              style={flagMode ? { color: "var(--dc-primary)" } : undefined}
            >
              <Flag size={20} />
            </button>
            <button className="dc-icon-btn" aria-label={tr("新游戏", "New game")} onClick={() => setConfigOpen(true)}>
              <RefreshCw size={18} />
            </button>
          </>
        }
      />

      {!loaded ? (
        <Spinner />
      ) : !engine ? (
        <div className="dc-col dc-center" style={{ flex: 1, minHeight: 240 }}>
          <button className="dc-btn dc-btn-filled" onClick={() => setConfigOpen(true)}>
            {tr("设置棋盘", "Configure board")}
          </button>
        </div>
      ) : (
        <>
          <MinesweeperBoard game={engine} flagMode={flagMode} onAction={applyAction} onFlagFeedback={() => undefined} />
          <div className="dc-muted dc-center" style={{ fontSize: "0.82em", padding: "6px 8px", textAlign: "center" }}>
            {tr(
              "点按翻开，长按插旗；双击已翻开的数字可展开周围未标旗格",
              "Tap to reveal, long-press to flag; double-tap a revealed number to open its unflagged neighbors",
            )}
          </div>

          {finished && !configOpen && (
            <div className="dc-dialog-overlay" style={{ zIndex: 90 }}>
              <div className="dc-dialog" role="alertdialog" aria-modal="true" style={{ width: "min(420px, 94vw)" }}>
                <div className="dc-title" style={{ marginBottom: 8 }}>
                  {engine.isWon ? tr("扫雷成功", "Board cleared") : tr("踩到雷了", "Mine triggered")}
                </div>
                <div className="dc-muted" style={{ whiteSpace: "pre-wrap" }}>
                  {engine.isWon
                    ? tr(
                        `最高分：${Math.max(highScoreRef.current, roundScore(engine))}`,
                        `Best: ${Math.max(highScoreRef.current, roundScore(engine))}`,
                      )
                    : tr("可以重新配置棋盘再试一次。", "Configure a new board and try again.")}
                </div>
                <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 16 }}>
                  <button className="dc-btn" onClick={exitToList}>
                    {tr("返回", "Back")}
                  </button>
                  <button className="dc-btn dc-btn-filled" onClick={() => setConfigOpen(true)}>
                    {tr("新游戏", "New game")}
                  </button>
                </div>
              </div>
            </div>
          )}
        </>
      )}

      <MinesweeperConfigDialog
        open={configOpen}
        initialWidth={engine?.width ?? 9}
        initialHeight={engine?.height ?? 9}
        initialMines={engine?.mineCount ?? 10}
        onCancel={() => {
          setConfigOpen(false);
          if (!engineRef.current) exitToList();
        }}
        onStart={startNewBoard}
      />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------

function MinesweeperBoard(props: {
  game: MinesweeperGame;
  flagMode: boolean;
  onAction: (result: MinesweeperActionResult) => void;
  onFlagFeedback: () => void;
}) {
  const { game, flagMode, onAction } = props;
  const [boxWidth, setBoxWidth] = useState(0);
  const boxRef = useRef<HTMLDivElement | null>(null);

  useLayoutEffect(() => {
    const node = boxRef.current;
    if (!node) return;
    const update = () => setBoxWidth(node.getBoundingClientRect().width);
    update();
    const observer = new ResizeObserver(update);
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  // Fit the board when possible, shrink cells (down to 22px) before scrolling huge boards.
  const gapTotal = 2;
  const fitSize = boxWidth > 0 ? Math.floor((boxWidth - 8 - gapTotal * game.width) / game.width) : 34;
  const cellSize = Math.max(22, Math.min(34, fitSize));

  const longPressRef = useRef<{ timer: number | null; fired: boolean }>({ timer: null, fired: false });
  useEffect(
    () => () => {
      if (longPressRef.current.timer !== null) window.clearTimeout(longPressRef.current.timer);
    },
    [],
  );

  const act = useCallback(
    (x: number, y: number, kind: "tap" | "chord" | "flag") => {
      if (kind === "flag") {
        onAction(game.toggleFlagWithResult(x, y));
        return;
      }
      if (kind === "chord") {
        onAction(game.chordWithResult(x, y));
        return;
      }
      onAction(game.revealWithResult(x, y));
    },
    [game, onAction],
  );

  const cells: React.ReactNode[] = [];
  for (let y = 0; y < game.height; y++) {
    for (let x = 0; x < game.width; x++) {
      const cell: MinesweeperCellView = game.cell(x, y);
      const background = cell.mine
        ? "var(--dc-error-container)"
        : cell.revealed
          ? "var(--dc-surface)"
          : "var(--dc-secondary-container)";
      cells.push(
        <button
          key={`${x},${y}`}
          className="dc-icon-btn"
          aria-label={mineCellAriaLabel(x, y, cell)}
          style={{
            width: cellSize,
            height: cellSize,
            minWidth: cellSize,
            minHeight: cellSize,
            borderRadius: 4,
            background,
            color: cell.flagged ? "var(--dc-primary)" : cell.mine ? "var(--dc-error)" : "var(--dc-primary)",
            border: "var(--dc-border-width, 1px) solid var(--dc-outline-variant)",
            fontWeight: 700,
            fontSize: Math.max(11, Math.round(cellSize * 0.45)),
            padding: 0,
            justifyContent: "center",
            alignItems: "center",
            display: "flex",
            cursor: "pointer",
            boxShadow: cell.revealed ? "none" : "0 1px 2px rgba(0,0,0,0.12)",
          }}
          onContextMenu={(e) => {
            e.preventDefault();
            act(x, y, "flag");
          }}
          onPointerDown={(e) => {
            if (e.pointerType === "mouse") return; // mouse uses right-click for flags
            longPressRef.current.fired = false;
            longPressRef.current.timer = window.setTimeout(() => {
              longPressRef.current.fired = true;
              act(x, y, "flag");
            }, LONG_PRESS_MS);
          }}
          onPointerUp={() => {
            if (longPressRef.current.timer !== null) {
              window.clearTimeout(longPressRef.current.timer);
              longPressRef.current.timer = null;
            }
          }}
          onPointerLeave={() => {
            if (longPressRef.current.timer !== null) {
              window.clearTimeout(longPressRef.current.timer);
              longPressRef.current.timer = null;
            }
          }}
          onClick={() => {
            if (longPressRef.current.fired) {
              longPressRef.current.fired = false;
              return;
            }
            act(x, y, flagMode ? "flag" : "tap");
          }}
          onDoubleClick={() => act(x, y, "chord")}
        >
          {cell.flagged ? (
            <Flag size={Math.max(12, Math.round(cellSize * 0.5))} aria-hidden />
          ) : cell.mine ? (
            "●"
          ) : cell.revealed && cell.adjacentMines > 0 ? (
            cell.adjacentMines
          ) : null}
        </button>,
      );
    }
  }

  return (
    <div ref={boxRef} style={{ overflow: "auto", maxWidth: "100%", padding: 4 }} role="application" aria-label={tr("扫雷棋盘", "Minesweeper board")}>
      <div
        style={{
          display: "grid",
          gridTemplateColumns: `repeat(${game.width}, ${cellSize}px)`,
          gap: 2,
          width: "fit-content",
          margin: "0 auto",
          userSelect: "none",
          WebkitUserSelect: "none",
        }}
      >
        {cells}
      </div>
    </div>
  );
}

function mineCellAriaLabel(x: number, y: number, cell: MinesweeperCellView): string {
  const position = tr(`第 ${y + 1} 行 第 ${x + 1} 列`, `Row ${y + 1}, column ${x + 1}`);
  if (cell.flagged) return `${position} ${tr("已插旗", "flagged")}`;
  if (cell.revealed) {
    if (cell.mine) return `${position} ${tr("踩到雷了", "Mine triggered")}`;
    if (cell.adjacentMines > 0)
      return `${position} ${tr(`${cell.adjacentMines}`, String(cell.adjacentMines))}`;
    return `${position} ${tr("空白", "blank")}`;
  }
  return `${position} ${tr("未翻开", "unrevealed")}`;
}

// ---------------------------------------------------------------------------
// Configuration dialog (自定义扫雷)
// ---------------------------------------------------------------------------

function MinesweeperConfigDialog(props: {
  open: boolean;
  initialWidth: number;
  initialHeight: number;
  initialMines: number;
  onCancel: () => void;
  onStart: (width: number, height: number, mines: number) => void;
}) {
  const { open, initialWidth, initialHeight, initialMines, onCancel, onStart } = props;
  const [width, setWidth] = useState(initialWidth);
  const [height, setHeight] = useState(initialHeight);
  const [mines, setMines] = useState(Math.max(1, Math.min(initialMines, initialWidth * initialHeight - 1)));

  useEffect(() => {
    if (!open) return;
    setWidth(initialWidth);
    setHeight(initialHeight);
    setMines(Math.max(1, Math.min(initialMines, initialWidth * initialHeight - 1)));
  }, [open, initialWidth, initialHeight, initialMines]);

  // 雷数会随棋盘缩小自动收敛到合法范围。
  useEffect(() => {
    const maxMines = Math.max(1, width * height - 1);
    if (mines > maxMines) setMines(maxMines);
  }, [width, height, mines]);

  if (!open) return null;

  const maxMines = Math.max(1, width * height - 1);

  const preset = (label: string, w: number, h: number, m: number) => (
    <button
      key={label}
      className="dc-btn dc-btn-tonal"
      onClick={() => {
        setWidth(w);
        setHeight(h);
        setMines(m);
      }}
    >
      {label}
    </button>
  );

  return (
    <Modal open={open} onClose={onCancel} title={tr("自定义扫雷", "Custom Minesweeper")} width={420}>
      <div className="dc-col" style={{ gap: 12 }}>
        <div className="dc-row" style={{ gap: 8, flexWrap: "wrap" }}>
          {preset(tr("初级", "Easy"), 9, 9, 10)}
          {preset(tr("中级", "Medium"), 16, 16, 40)}
          {preset(tr("高级", "Expert"), 30, 16, 99)}
        </div>
        <ValueSlider label={tr("列数", "Columns")} value={width} min={6} max={30} onChange={setWidth} />
        <ValueSlider label={tr("行数", "Rows")} value={height} min={6} max={30} onChange={setHeight} />
        <ValueSlider label={tr("雷数", "Mines")} value={mines} min={1} max={maxMines} onChange={setMines} />
        <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 4 }}>
          <button className="dc-btn" onClick={onCancel}>
            {tr("取消", "Cancel")}
          </button>
          <button className="dc-btn dc-btn-filled" onClick={() => onStart(width, height, mines)}>
            {tr("开始", "Start")}
          </button>
        </div>
      </div>
    </Modal>
  );
}

function ValueSlider(props: {
  label: string;
  value: number;
  min: number;
  max: number;
  onChange: (value: number) => void;
}) {
  return (
    <label className="dc-col" style={{ gap: 4 }}>
      <span className="dc-row" style={{ justifyContent: "space-between" }}>
        <span>{props.label}</span>
        <span style={{ color: "var(--dc-primary)", fontWeight: 600 }}>{props.value}</span>
      </span>
      <input
        type="range"
        min={props.min}
        max={props.max}
        step={1}
        value={props.value}
        onChange={(e) => props.onChange(Number(e.target.value))}
        style={{ width: "100%", accentColor: "var(--dc-primary)" }}
      />
    </label>
  );
}
