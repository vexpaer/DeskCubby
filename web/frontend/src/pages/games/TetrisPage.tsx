/**
 * Tetris game page (/games/tetris). Faithful web port of the Android TetrisPage in
 * ui/games/GamesScreen.kt: 10×20 board, next-piece preview, 行数/等级 panels,
 * five bottom controls (左移/旋转/加速下落/硬降/右移), no swipe gestures, pause with
 * 「已暂停，进度已保存」, tick = max(120, 600 − 40·level) ms, line scores 100/300/500/800,
 * auto-pause save on background/unmount and game-over dialog 再来一局/返回.
 * Keyboard mapping is added for web accessibility (arrows + WASD + Space hard drop).
 * Statistics use the exact Kotlin metric keys: piecesLocked / linesCleared / tetrises / losses.
 */
import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  ArrowDown,
  ArrowDownToLine,
  ArrowLeft,
  ArrowRight,
  Pause,
  Play,
  RotateCw,
} from "lucide-react";
import { ApiClientError, apiGet, apiSend } from "../../api/client";
import { tr } from "../../i18n/tr";
import { TETRIS_HEIGHT, TETRIS_PIECE_COUNT, TETRIS_WIDTH, TetrisGame } from "./engineTetris";

const TETRIS_BASE_TICK_MILLIS = 600;
const TETRIS_LEVEL_STEP_MILLIS = 40;
const TETRIS_MIN_TICK_MILLIS = 120;
const GAME_ID = "tetris";

interface GameStateDto {
  highScore?: number;
  saveJson?: string | null;
}

async function fetchGameState(gameId: string): Promise<{ highScore: number; saveJson: string | null }> {
  try {
    const data = await apiGet<GameStateDto>(`/api/games/states/${encodeURIComponent(gameId)}`);
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

function recordTetrisDelta(delta: {
  piecesLocked: number;
  linesCleared: number;
  tetrises: number;
  losses: number;
}): void {
  postStat("piecesLocked", delta.piecesLocked);
  postStat("linesCleared", delta.linesCleared);
  postStat("tetrises", delta.tetrises);
  postStat("losses", delta.losses);
}

export default function TetrisPage() {
  const navigate = useNavigate();

  const engineRef = useRef<TetrisGame | null>(null);
  const [tick, setTick] = useState(0);
  const bump = useCallback(() => setTick((t) => (t + 1) % 1_000_000_000), []);
  const [loaded, setLoaded] = useState(false);
  const loadedRef = useRef(false);
  const [paused, setPaused] = useState(false);
  const pausedRef = useRef(false);
  const scoreRecordedRef = useRef(false);

  // Derived state (refs are read during render; bump() drives refreshes).
  const engine = engineRef.current;
  const score = engine ? engine.score : 0;
  const lines = engine ? engine.lines : 0;
  const level = engine ? engine.level : 0;
  const gameOver = engine ? engine.isGameOver : false;

  const highScoreRef = useRef(0);
  const saveQueueRef = useRef<Promise<void>>(Promise.resolve());

  const [boardSize, setBoardSize] = useState({ width: 0, height: 0 });
  const boardBoxRef = useRef<HTMLDivElement | null>(null);

  useLayoutEffect(() => {
    const node = boardBoxRef.current;
    if (!node) return;
    const update = () => {
      const rect = node.getBoundingClientRect();
      setBoardSize({ width: rect.width, height: rect.height });
    };
    update();
    const observer = new ResizeObserver(update);
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  const enqueue = useCallback((fn: () => Promise<void>) => {
    const run = saveQueueRef.current.then(fn).catch(() => undefined);
    saveQueueRef.current = run;
    return run;
  }, []);

  const saveProgressNow = useCallback(() => {
    const current = engineRef.current;
    if (!current || current.isGameOver) return;
    const high = Math.max(highScoreRef.current, current.score);
    highScoreRef.current = high;
    const json = current.toJson();
    void enqueue(() => apiSend(`/api/games/states/${encodeURIComponent(GAME_ID)}`, "PUT", { highScore: high, saveJson: json }).then(() => undefined));
  }, [enqueue]);

  const recordScoreNow = useCallback(() => {
    const current = engineRef.current;
    if (!current) return;
    const high = Math.max(highScoreRef.current, current.score);
    highScoreRef.current = high;
    void enqueue(() => apiSend(`/api/games/states/${encodeURIComponent(GAME_ID)}`, "PUT", { highScore: high, saveJson: null }).then(() => undefined));
  }, [enqueue]);

  /** Mirrors Android pauseAndSave: finished rounds finalize the score, running ones snapshot. */
  const pauseAndSave = useCallback(() => {
    pausedRef.current = true;
    setPaused(true);
    const current = engineRef.current;
    if (!current) return;
    if (current.isGameOver) {
      recordScoreNow();
      return;
    }
    saveProgressNow();
  }, [recordScoreNow, saveProgressNow]);
  const pauseAndSaveRef = useRef(pauseAndSave);
  useEffect(() => {
    pauseAndSaveRef.current = pauseAndSave;
  }, [pauseAndSave]);

  // Load save on mount (corrupt saves degrade to a fresh game).
  useEffect(() => {
    let cancelled = false;
    loadedRef.current = false;
    (async () => {
      let restored: TetrisGame | null = null;
      try {
        const state = await fetchGameState(GAME_ID);
        if (cancelled) return;
        highScoreRef.current = state.highScore;
        restored = state.saveJson ? TetrisGame.fromJson(state.saveJson) : null;
      } catch {
        restored = null;
      }
      if (cancelled) return;
      engineRef.current = restored ?? new TetrisGame();
      scoreRecordedRef.current = engineRef.current.isGameOver;
      loadedRef.current = true;
      setLoaded(true);
      bump();
    })();
    return () => {
      cancelled = true;
    };
  }, [bump]);

  // Auto-pause when the tab goes to the background; persist once more on unmount.
  useEffect(() => {
    const onHide = () => {
      if (document.hidden) pauseAndSaveRef.current();
    };
    document.addEventListener("visibilitychange", onHide);
    return () => {
      document.removeEventListener("visibilitychange", onHide);
      pauseAndSaveRef.current();
    };
  }, []);

  // Gravity loop: base 600 ms, −40 ms per level, floor 120 ms.
  useEffect(() => {
    if (!loaded || paused || gameOver) return;
    const interval = Math.max(
      TETRIS_MIN_TICK_MILLIS,
      TETRIS_BASE_TICK_MILLIS - TETRIS_LEVEL_STEP_MILLIS * (engineRef.current?.level ?? 0),
    );
    const timer = window.setInterval(() => {
      const game = engineRef.current;
      if (!game || pausedRef.current || game.isGameOver) return;
      recordTetrisDelta(game.tickWithResult().statisticsDelta);
      bump();
    }, interval);
    return () => window.clearInterval(timer);
  }, [loaded, paused, gameOver, level, bump]);

  // Record the high score exactly once when the round ends.
  useEffect(() => {
    const current = engineRef.current;
    if (!loaded || !current || !current.isGameOver || scoreRecordedRef.current) return;
    scoreRecordedRef.current = true;
    recordScoreNow();
  }, [loaded, gameOver, recordScoreNow]);

  const act = useCallback(
    (action: (game: TetrisGame) => { statisticsDelta: { piecesLocked: number; linesCleared: number; tetrises: number; losses: number } } | null | undefined) => {
      const current = engineRef.current;
      if (!current || pausedRef.current || current.isGameOver) return;
      const result = action(current);
      if (result) recordTetrisDelta(result.statisticsDelta);
      bump();
    },
    [bump],
  );

  // Keyboard: arrows + WASD + Space (web accessibility addition over the touch buttons).
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const key = event.key;
      if (key === "ArrowLeft" || key === "a" || key === "A") {
        event.preventDefault();
        act((game) => (game.moveLeft() ? null : null));
      } else if (key === "ArrowRight" || key === "d" || key === "D") {
        event.preventDefault();
        act((game) => (game.moveRight() ? null : null));
      } else if (key === "ArrowUp" || key === "w" || key === "W") {
        event.preventDefault();
        act((game) => (game.rotate() ? null : null));
      } else if (key === "ArrowDown" || key === "s" || key === "S") {
        event.preventDefault();
        act((game) => game.softDropWithResult());
      } else if (key === " " || key === "Spacebar") {
        event.preventDefault();
        act((game) => game.hardDropWithResult());
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [act]);

  const togglePause = useCallback(() => {
    if (!engineRef.current || engineRef.current.isGameOver) return;
    if (!pausedRef.current) {
      pauseAndSave();
    } else {
      pausedRef.current = false;
      setPaused(false);
    }
  }, [pauseAndSave]);

  const restart = useCallback(() => {
    engineRef.current = new TetrisGame();
    pausedRef.current = false;
    setPaused(false);
    scoreRecordedRef.current = false;
    bump();
  }, [bump]);

  const exitToGameList = useCallback(() => {
    const current = engineRef.current;
    if (current && !current.isGameOver) saveProgressNow();
    navigate("/games");
  }, [navigate, saveProgressNow]);

  const bestScore = Math.max(highScoreRef.current, score);

  // Merged locked board + falling piece, exactly like the Compose TetrisBoard input.
  const mergedCells = useMemo(() => {
    const current = engineRef.current;
    if (!current) return [];
    const merged = current.boardSnapshot();
    for (const cell of current.currentPieceCells()) {
      if (cell.x >= 0 && cell.x < TETRIS_WIDTH && cell.y >= 0 && cell.y < TETRIS_HEIGHT) {
        merged[cell.y * TETRIS_WIDTH + cell.x] = current.pieceType + 1;
      }
    }
    return merged;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tick, loaded]);

  const nextCells = useMemo(
    () => (engineRef.current ? engineRef.current.nextPiecePreviewCells() : []),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [tick, loaded],
  );
  const nextType = engineRef.current?.nextPieceType ?? 0;

  // All piece colors are interpolated strictly between primary and secondary.
  const pieceColor = (pieceIndex: number): string => {
    const percent = Math.round((1 - pieceIndex / (TETRIS_PIECE_COUNT - 1)) * 100);
    return `color-mix(in srgb, var(--dc-primary) ${percent}%, var(--dc-secondary))`;
  };

  const cellW = boardSize.width > 0 ? boardSize.width / TETRIS_WIDTH : 0;
  const cellH = boardSize.height > 0 ? boardSize.height / TETRIS_HEIGHT : 0;

  const controlButton = (
    label: string,
    icon: React.ReactNode,
    onClick: () => void,
  ): React.ReactNode => (
    <button
      className="dc-btn dc-btn-tonal"
      aria-label={label}
      disabled={!loaded || paused || gameOver}
      onClick={onClick}
      style={{ minWidth: 52, minHeight: 48, justifyContent: "center", display: "flex" }}
    >
      {icon}
    </button>
  );

  return (
    <div className="dc-col" style={{ maxWidth: 560, margin: "0 auto", width: "100%" }}>
      {/* Top bar */}
      <div className="dc-row">
        <button className="dc-icon-btn" aria-label={tr("返回", "Back")} onClick={exitToGameList}>
          <ArrowLeft size={22} />
        </button>
        <div className="dc-title dc-grow">{tr("俄罗斯方块", "Tetris")}</div>
        {loaded && !gameOver && (
          <button className="dc-icon-btn" aria-label={paused ? tr("继续", "Resume") : tr("暂停", "Pause")} onClick={togglePause}>
            {paused ? <Play size={22} /> : <Pause size={22} />}
          </button>
        )}
      </div>

      {/* Score chips */}
      <div className="dc-row" style={{ gap: 12, padding: "8px 0" }}>
        <div className="dc-card dc-grow" style={{ padding: "8px 14px", textAlign: "center" }}>
          <div className="dc-muted" style={{ fontSize: "0.8em" }}>{tr("分数", "Score")}</div>
          <div style={{ fontWeight: 600, fontSize: "1.15em" }}>{score}</div>
        </div>
        <div className="dc-card dc-grow" style={{ padding: "8px 14px", textAlign: "center" }}>
          <div className="dc-muted" style={{ fontSize: "0.8em" }}>{tr("最高分", "Best")}</div>
          <div style={{ fontWeight: 600, fontSize: "1.15em" }}>{bestScore}</div>
        </div>
      </div>

      {/* Board + side panels */}
      <div className="dc-row" style={{ flex: 1, minHeight: 0, gap: 12, alignItems: "stretch" }}>
        <div className="dc-grow dc-center" style={{ minHeight: 0 }}>
          <div
            ref={boardBoxRef}
            role="application"
            aria-label={tr("俄罗斯方块棋盘", "Tetris board")}
            style={{
              position: "relative",
              height: "min(58vh, 460px)",
              aspectRatio: `${TETRIS_WIDTH} / ${TETRIS_HEIGHT}`,
              borderRadius: 12,
              background: "color-mix(in srgb, var(--dc-surface-variant) 50%, transparent)",
              overflow: "hidden",
            }}
          >
            {cellW > 0 &&
              mergedCells.map((value, index) => {
                if (value === 0) return null;
                const x = index % TETRIS_WIDTH;
                const y = Math.floor(index / TETRIS_WIDTH);
                return (
                  <div
                    key={`${x},${y}`}
                    style={{
                      position: "absolute",
                      left: x * cellW + 1,
                      top: y * cellH + 1,
                      width: cellW - 2,
                      height: cellH - 2,
                      borderRadius: cellW * 0.18,
                      background: pieceColor((value - 1) % TETRIS_PIECE_COUNT),
                    }}
                  />
                );
              })}
            {paused && !gameOver && (
              <div
                style={{
                  position: "absolute",
                  inset: 0,
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  zIndex: 5,
                }}
              >
                <div
                  className="dc-card"
                  style={{
                    padding: "14px 24px",
                    borderRadius: 16,
                    background: "var(--dc-surface-container-high)",
                    color: "var(--dc-on-surface)",
                    fontWeight: 600,
                    whiteSpace: "nowrap",
                  }}
                >
                  {tr("已暂停，进度已保存", "Paused, progress saved")}
                </div>
              </div>
            )}
          </div>
        </div>

        <div className="dc-col" style={{ gap: 10, justifyContent: "flex-start", paddingTop: 4 }}>
          <div className="dc-card dc-col dc-center" style={{ padding: 10, gap: 6 }}>
            <div className="dc-muted" style={{ fontSize: "0.82em" }}>{tr("下一块", "Next")}</div>
            <div style={{ position: "relative", width: 64, height: 64 }}>
              {(() => {
                if (nextCells.length === 0 || boardSize.width <= 0) return null;
                const unit = 64 / 4;
                let maxX = 0;
                let maxY = 0;
                for (const cell of nextCells) {
                  if (cell.x > maxX) maxX = cell.x;
                  if (cell.y > maxY) maxY = cell.y;
                }
                const offsetX = (64 - (maxX + 1) * unit) / 2;
                const offsetY = (64 - (maxY + 1) * unit) / 2;
                return nextCells.map((cell) => (
                  <div
                    key={`${cell.x},${cell.y}`}
                    style={{
                      position: "absolute",
                      left: offsetX + cell.x * unit + 1,
                      top: offsetY + cell.y * unit + 1,
                      width: unit - 2,
                      height: unit - 2,
                      borderRadius: unit * 0.2,
                      background: pieceColor(nextType),
                    }}
                  />
                ));
              })()}
            </div>
          </div>
          <div className="dc-card" style={{ padding: "8px 12px" }}>
            <div style={{ fontSize: "0.85em" }}>{`${tr("行数", "Lines")}: ${lines}`}</div>
            <div style={{ fontSize: "0.85em" }}>{`${tr("等级", "Level")}: ${level}`}</div>
          </div>
        </div>
      </div>

      {/* Controls */}
      <div className="dc-row" style={{ justifyContent: "space-evenly", padding: "10px 0 4px", flexWrap: "wrap", gap: 6 }}>
        {controlButton(tr("左移", "Left"), <ArrowLeft size={22} />, () =>
          act((game) => (game.moveLeft() ? null : null)),
        )}
        {controlButton(tr("旋转", "Rotate"), <RotateCw size={22} />, () =>
          act((game) => (game.rotate() ? null : null)),
        )}
        {controlButton(tr("加速下落", "Soft drop"), <ArrowDown size={22} />, () =>
          act((game) => game.softDropWithResult()),
        )}
        {controlButton(tr("硬降", "Hard drop"), <ArrowDownToLine size={22} />, () =>
          act((game) => game.hardDropWithResult()),
        )}
        {controlButton(tr("右移", "Right"), <ArrowRight size={22} />, () =>
          act((game) => (game.moveRight() ? null : null)),
        )}
      </div>

      {/* Game over dialog */}
      {gameOver && (
        <div className="dc-dialog-overlay" onClick={exitToGameList}>
          <div className="dc-dialog" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()} style={{ width: "min(420px, 94vw)" }}>
            <div className="dc-title" style={{ marginBottom: 8 }}>{tr("游戏结束", "Game over")}</div>
            <div className="dc-muted" style={{ marginBottom: 12 }}>{tr("本局得分：", "Score: ") + score}</div>
            <div className="dc-row" style={{ justifyContent: "flex-end" }}>
              <button className="dc-btn" onClick={exitToGameList}>{tr("返回", "Back")}</button>
              <button className="dc-btn dc-btn-filled" onClick={restart}>{tr("再来一局", "Play again")}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
