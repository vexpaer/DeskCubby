/**
 * Snake game page (/games/snake). Faithful web port of the Android SnakePage in
 * ui/games/GamesScreen.kt: 16×16 wall-bounded board, 220 ms ticks, swipe on the
 * board + direction pad (上/左/下/右) + arrow keys, pause/resume with
 * 「已暂停，进度已保存」 overlay, score/best chips, auto-pause save on background and
 * unmount, game-over dialog 再来一局/返回. Statistics use the exact Kotlin metric keys:
 * foodEaten / losses increments and maxLength as a lifetime maximum.
 */
import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowLeft, Pause, Play } from "lucide-react";
import { ApiClientError, apiGet, apiSend } from "../../api/client";
import { tr } from "../../i18n/tr";
import { SnakeGame, type SnakeDirection } from "./engineSnake";

const SNAKE_TICK_MILLIS = 220;
const SWIPE_THRESHOLD_PX = 42;
const GAME_ID = "snake";

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

/** Tolerant reader for GET /api/games/statistics (row list or nested map shapes). */
function readStatValue(payload: unknown, gameId: string, metricKey: string): number {
  let rows: unknown = payload;
  if (payload !== null && typeof payload === "object" && !Array.isArray(payload)) {
    const obj = payload as Record<string, unknown>;
    rows = obj["statistics"] ?? obj["items"] ?? obj["rows"] ?? obj[gameId] ?? payload;
  }
  if (Array.isArray(rows)) {
    for (const row of rows) {
      if (row !== null && typeof row === "object") {
        const r = row as Record<string, unknown>;
        if (r["gameId"] === gameId && r["metricKey"] === metricKey && typeof r["value"] === "number") {
          return r["value"] as number;
        }
      }
    }
    return 0;
  }
  if (rows !== null && typeof rows === "object") {
    const byMetric = (rows as Record<string, unknown>)[gameId];
    if (byMetric !== null && typeof byMetric === "object") {
      const v = (byMetric as Record<string, unknown>)[metricKey];
      if (typeof v === "number") return v;
    }
  }
  return 0;
}

export default function SnakePage() {
  const navigate = useNavigate();

  const engineRef = useRef<SnakeGame | null>(null);
  const [tick, setTick] = useState(0);
  const bump = useCallback(() => setTick((t) => (t + 1) % 1_000_000_000), []);
  const [loaded, setLoaded] = useState(false);
  const loadedRef = useRef(false);
  const [paused, setPaused] = useState(false);
  const pausedRef = useRef(false);
  const scoreRecordedRef = useRef(false);

  // Derived board state (refs are read during render; bump() drives refreshes).
  const engine = engineRef.current;
  const score = engine ? engine.score : 0;
  const gameOver = engine ? engine.isGameOver : false;

  const highScoreRef = useRef(0);
  const maxLengthRef = useRef(0);
  const saveQueueRef = useRef<Promise<void>>(Promise.resolve());

  const [boardWidth, setBoardWidth] = useState(0);
  const boardBoxRef = useRef<HTMLDivElement | null>(null);

  useLayoutEffect(() => {
    const node = boardBoxRef.current;
    if (!node) return;
    const update = () => setBoardWidth(node.getBoundingClientRect().width);
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

  // Load save on mount (corrupt saves degrade to a fresh game) plus cached maxLength.
  useEffect(() => {
    let cancelled = false;
    loadedRef.current = false;
    (async () => {
      let restored: SnakeGame | null = null;
      try {
        const state = await fetchGameState(GAME_ID);
        if (cancelled) return;
        highScoreRef.current = state.highScore;
        restored = state.saveJson ? SnakeGame.fromJson(state.saveJson) : null;
      } catch {
        restored = null;
      }
      if (cancelled) return;
      engineRef.current = restored ?? new SnakeGame();
      scoreRecordedRef.current = engineRef.current.isGameOver;
      loadedRef.current = true;
      setLoaded(true);
      bump();
      try {
        const stats = await apiGet<unknown>("/api/games/statistics");
        if (!cancelled) maxLengthRef.current = readStatValue(stats, GAME_ID, "maxLength");
      } catch {
        /* statistics are optional */
      }
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

  // Game loop: one tick every SNAKE_TICK_MILLIS while playing.
  useEffect(() => {
    if (!loaded || paused || gameOver) return;
    const timer = window.setInterval(() => {
      const game = engineRef.current;
      if (!game || pausedRef.current || game.isGameOver) return;
      const result = game.tickWithResult();
      postStat("foodEaten", result.statisticsDelta.foodEaten);
      postStat("losses", result.statisticsDelta.losses);
      const candidate = result.statisticsDelta.maxLength;
      const previousMax = maxLengthRef.current;
      if (candidate > previousMax) {
        maxLengthRef.current = candidate;
        postStat("maxLength", candidate - previousMax);
      }
      bump();
    }, SNAKE_TICK_MILLIS);
    return () => window.clearInterval(timer);
  }, [loaded, paused, gameOver, bump]);

  // Record the high score exactly once when the round ends.
  useEffect(() => {
    const current = engineRef.current;
    if (!loaded || !current || !current.isGameOver || scoreRecordedRef.current) return;
    scoreRecordedRef.current = true;
    recordScoreNow();
  }, [loaded, gameOver, recordScoreNow]);

  const steer = useCallback((direction: SnakeDirection) => {
    const current = engineRef.current;
    if (!current || pausedRef.current || current.isGameOver) return;
    current.setDirection(direction);
  }, []);

  // Keyboard: arrow keys + WASD.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const key = event.key;
      let direction: SnakeDirection | null = null;
      if (key === "ArrowUp" || key === "w" || key === "W") direction = "UP";
      else if (key === "ArrowDown" || key === "s" || key === "S") direction = "DOWN";
      else if (key === "ArrowLeft" || key === "a" || key === "A") direction = "LEFT";
      else if (key === "ArrowRight" || key === "d" || key === "D") direction = "RIGHT";
      if (direction === null) return;
      event.preventDefault();
      steer(direction);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [steer]);

  // Board-only swipe: one dominant-axis direction judged when the pointer is released.
  const pointerStartRef = useRef<{ x: number; y: number } | null>(null);
  const playing = loaded && !paused && !(engineRef.current?.isGameOver ?? false);
  const onPointerDown = useCallback(
    (event: React.PointerEvent) => {
      if (!playing) return;
      pointerStartRef.current = { x: event.clientX, y: event.clientY };
    },
    [playing],
  );
  const onPointerCancel = useCallback(() => {
    pointerStartRef.current = null;
  }, []);
  const onPointerUp = useCallback(
    (event: React.PointerEvent) => {
      const start = pointerStartRef.current;
      pointerStartRef.current = null;
      if (!start || !playing) return;
      const dx = event.clientX - start.x;
      const dy = event.clientY - start.y;
      if (Math.abs(dx) < SWIPE_THRESHOLD_PX && Math.abs(dy) < SWIPE_THRESHOLD_PX) return;
      if (Math.abs(dx) >= Math.abs(dy)) steer(dx > 0 ? "RIGHT" : "LEFT");
      else steer(dy > 0 ? "DOWN" : "UP");
    },
    [playing, steer],
  );

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
    engineRef.current = new SnakeGame();
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

  const cellSize = boardWidth > 0 ? boardWidth / (engine?.width ?? 16) : 0;
  const snakeCells = useMemo(
    () => (engine ? engine.snake : []),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [engine, tick, loaded],
  );
  const food = engine ? engine.food : null;

  const padButton = (label: string, aria: string, direction: SnakeDirection): React.ReactNode => (
    <button
      className="dc-btn dc-btn-tonal"
      aria-label={aria}
      disabled={!playing}
      onClick={() => steer(direction)}
      style={{ width: 56, height: 56, borderRadius: "50%", justifyContent: "center", display: "flex", fontSize: 18 }}
    >
      {label}
    </button>
  );

  return (
    <div className="dc-col" style={{ maxWidth: 520, margin: "0 auto", width: "100%" }}>
      {/* Top bar */}
      <div className="dc-row">
        <button className="dc-icon-btn" aria-label={tr("返回", "Back")} onClick={exitToGameList}>
          <ArrowLeft size={22} />
        </button>
        <div className="dc-title dc-grow">{tr("贪吃蛇", "Snake")}</div>
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

      {/* Board */}
      <div className="dc-col dc-center" style={{ flex: 1, minHeight: 0 }}>
        <div
          ref={boardBoxRef}
          onPointerDown={onPointerDown}
          onPointerUp={onPointerUp}
          onPointerCancel={onPointerCancel}
          role="application"
          aria-label={tr("贪吃蛇棋盘", "Snake board")}
          style={{
            position: "relative",
            width: "100%",
            maxWidth: "min(92vw, 520px)",
            aspectRatio: "1 / 1",
            borderRadius: 16,
            background: "color-mix(in srgb, var(--dc-surface-variant) 50%, transparent)",
            touchAction: "none",
            overflow: "hidden",
          }}
        >
          {food && cellSize > 0 && (
            <div
              style={{
                position: "absolute",
                left: (food.x + 0.5) * cellSize - cellSize * 0.32,
                top: (food.y + 0.5) * cellSize - cellSize * 0.32,
                width: cellSize * 0.64,
                height: cellSize * 0.64,
                borderRadius: "50%",
                background: "var(--dc-secondary)",
              }}
            />
          )}
          {cellSize > 0 &&
            snakeCells.map((cell, index) => (
              <div
                key={`${cell.x},${cell.y}`}
                style={{
                  position: "absolute",
                  left: cell.x * cellSize + 1,
                  top: cell.y * cellSize + 1,
                  width: cellSize - 2,
                  height: cellSize - 2,
                  borderRadius: cellSize * 0.3,
                  background:
                    index === 0
                      ? "var(--dc-primary)"
                      : "color-mix(in srgb, var(--dc-primary) 58%, var(--dc-secondary))",
                }}
              />
            ))}
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
                }}
              >
                {tr("已暂停，进度已保存", "Paused, progress saved")}
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Direction pad */}
      <div className="dc-col dc-center" style={{ gap: 4, padding: "10px 0 4px" }}>
        {padButton("↑", tr("上", "Up"), "UP")}
        <div className="dc-row" style={{ gap: 28 }}>
          {padButton("←", tr("左", "Left"), "LEFT")}
          {padButton("↓", tr("下", "Down"), "DOWN")}
          {padButton("→", tr("右", "Right"), "RIGHT")}
        </div>
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
