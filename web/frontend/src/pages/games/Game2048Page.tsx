/**
 * 2048 game page (/games/2048, ?size=5|6 selects the 5×5 / 6×6 variants with
 * gameIds "2048_5" / "2048_6"). Faithful web port of the Android Game2048Page in
 * ui/games/GamesScreen.kt: own classic day/night palette, page-wide swipe input,
 * unlimited undo, per-page dark-mode override, animation on/off + SLOW/NORMAL/FAST
 * speed persisted in settings.game2048AnimationSpeed, score-only header (Android does
 * not show a best-score chip on this page; the best score is still persisted),
 * game-over overlay with 撤回/再试一次, and a win-at-2048 overlay with 继续游戏.
 */
import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { ArrowLeft, Moon, Pause, Play, RotateCcw, Sun } from "lucide-react";
import { ApiClientError, apiGet, apiSend } from "../../api/client";
import { tr } from "../../i18n/tr";
import { useSettings, useSettingsOrThrow } from "../../stores/settings";
import { Game2048, type Direction2048, type MoveResult2048 } from "./engine2048";

type GameId2048 = "2048" | "2048_5" | "2048_6";
type AnimSpeed = "SLOW" | "NORMAL" | "FAST";

interface Palette2048 {
  pageBackground: string;
  heading: string;
  board: string;
  emptyTile: string;
  button: string;
  scoreLabel: string;
  lightTileText: string;
  darkTileText: string;
}

const GAME_2048_DAY_PALETTE: Palette2048 = {
  pageBackground: "#FAF8EF",
  heading: "#776E65",
  board: "#BBADA0",
  emptyTile: "rgba(238, 228, 218, 0.35)",
  button: "#8F7A66",
  scoreLabel: "#EEE4DA",
  lightTileText: "#F9F6F2",
  darkTileText: "#776E65",
};

const GAME_2048_NIGHT_PALETTE: Palette2048 = {
  pageBackground: "#1B1917",
  heading: "#F3EDE6",
  board: "#4E453E",
  emptyTile: "rgba(78, 69, 62, 0.20)",
  button: "#B58B67",
  scoreLabel: "#F3EDE6",
  lightTileText: "#F9F6F2",
  darkTileText: "#5B5047",
};

const TILE_COLORS: Record<number, string> = {
  2: "#EEE4DA",
  4: "#EDE0C8",
  8: "#F2B179",
  16: "#F59563",
  32: "#F67C5F",
  64: "#F65E3B",
  128: "#EDCF72",
  256: "#EDCC61",
  512: "#EDC850",
  1024: "#EDC53F",
  2048: "#EDC22E",
};

const SWIPE_THRESHOLD_PX = 42;

function speedDurationMillis(speed: AnimSpeed): number {
  switch (speed) {
    case "SLOW":
      return 500;
    case "NORMAL":
      return 300;
    case "FAST":
      return 150;
  }
}

function nextSpeed(speed: AnimSpeed): AnimSpeed {
  switch (speed) {
    case "SLOW":
      return "NORMAL";
    case "NORMAL":
      return "FAST";
    case "FAST":
      return "SLOW";
  }
}

/** Port of GamesScreen.tileFontSize: single-line digits shrink with count and cell width. */
function tileFontSize(value: number, boardSize: number, tileWidthPx: number): number {
  const boardScale = boardSize === 4 ? 1 : boardSize === 5 ? 0.98 : 0.96;
  const base = Math.min(55, Math.max(16, Math.round(tileWidthPx * 0.47 * boardScale)));
  const digitCount = Math.max(1, String(value).length);
  const original2048Scale = digitCount <= 2 ? 1 : digitCount === 3 ? 0.82 : digitCount === 4 ? 0.64 : 0.54;
  const widthBound = Math.floor((tileWidthPx * 0.82) / (digitCount * 0.6));
  return Math.max(9, Math.min(Math.round(base * original2048Scale), widthBound));
}

async function fetchGameState(gameId: string): Promise<{ highScore: number; saveJson: string | null }> {
  try {
    const data = await apiGet<{ highScore?: number; saveJson?: string | null }>(
      `/api/games/states/${encodeURIComponent(gameId)}`,
    );
    return {
      highScore: typeof data?.highScore === "number" ? data.highScore : 0,
      saveJson: typeof data?.saveJson === "string" ? data.saveJson : null,
    };
  } catch (error) {
    if (error instanceof ApiClientError && error.status === 404) return { highScore: 0, saveJson: null };
    throw error;
  }
}

function postStat(gameId: string, metricKey: string, value: number): void {
  if (value <= 0) return;
  void apiSend("/api/games/statistics", "POST", { gameId, metricKey, value }).catch(() => undefined);
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

export default function Game2048Page() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const settings = useSettingsOrThrow();
  const updateSettings = useSettings((s) => s.update);

  const sizeParam = searchParams.get("size");
  const boardSize = sizeParam === "5" ? 5 : sizeParam === "6" ? 6 : 4;
  const gameId: GameId2048 = boardSize === 5 ? "2048_5" : boardSize === 6 ? "2048_6" : "2048";

  const systemPrefersDark = useMemo(
    () => window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false,
    [],
  );
  const reducedMotion = useMemo(
    () => window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false,
    [],
  );
  const themeDark = settings.darkMode === "DARK" || (settings.darkMode === "SYSTEM" && systemPrefersDark);
  const [darkOverride, setDarkOverride] = useState<boolean | null>(null);
  const darkMode = darkOverride ?? themeDark;
  const palette = darkMode ? GAME_2048_NIGHT_PALETTE : GAME_2048_DAY_PALETTE;

  const [animationsEnabled, setAnimationsEnabled] = useState(true);
  const animate = animationsEnabled && !reducedMotion;
  const durationMillis = speedDurationMillis(settings.game2048AnimationSpeed);
  const slideMillis = Math.round(durationMillis / 3);
  const popMillis = durationMillis - slideMillis;

  const engineRef = useRef<Game2048 | null>(null);
  const [, setTick] = useState(0);
  const bump = useCallback(() => setTick((t) => (t + 1) % 1_000_000_000), []);
  const [loaded, setLoaded] = useState(false);
  const loadedRef = useRef(false);

  const [transition, setTransition] = useState<{ result: MoveResult2048; seq: number } | null>(null);
  const [phase, setPhase] = useState<"slide" | "pop">("slide");
  const transitionSeqRef = useRef(0);

  const scoreRecordedRef = useRef(false);
  const [winOverlayOpen, setWinOverlayOpen] = useState(false);
  const winOverlayRef = useRef(false);

  const highScoreRef = useRef(0);
  const highestTileRef = useRef(0);
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
    void enqueue(() => apiSend(`/api/games/states/${encodeURIComponent(gameId)}`, "PUT", { highScore: high, saveJson: json }).then(() => undefined));
  }, [enqueue, gameId]);

  const recordScoreNow = useCallback(() => {
    const current = engineRef.current;
    if (!current) return;
    const high = Math.max(highScoreRef.current, current.score);
    highScoreRef.current = high;
    void enqueue(() => apiSend(`/api/games/states/${encodeURIComponent(gameId)}`, "PUT", { highScore: high, saveJson: null }).then(() => undefined));
  }, [enqueue, gameId]);

  /** Mirrors Android pauseAndSave: finished rounds finalize the score, running ones snapshot. */
  const pauseAndSave = useCallback(() => {
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

  // Load save on mount (corrupt saves degrade to a fresh game), plus the cached highestTile.
  useEffect(() => {
    let cancelled = false;
    loadedRef.current = false;
    (async () => {
      let restored: Game2048 | null = null;
      try {
        const state = await fetchGameState(gameId);
        if (cancelled) return;
        highScoreRef.current = state.highScore;
        restored = state.saveJson ? Game2048.fromJson(state.saveJson, boardSize) : null;
      } catch {
        restored = null;
      }
      if (cancelled) return;
      engineRef.current = restored ?? new Game2048(boardSize);
      scoreRecordedRef.current = engineRef.current.isGameOver;
      loadedRef.current = true;
      setLoaded(true);
      bump();
      try {
        const stats = await apiGet<unknown>("/api/games/statistics");
        if (!cancelled) highestTileRef.current = readStatValue(stats, gameId, "highestTile");
      } catch {
        /* statistics are optional */
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [gameId, boardSize, bump]);

  // Persist on unmount and when the tab/page goes to the background.
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

  const bumpHighestTile = useCallback(
    (candidate: number) => {
      const current = highestTileRef.current;
      if (candidate > current) {
        highestTileRef.current = candidate;
        postStat(gameId, "highestTile", candidate - current);
      }
    },
    [gameId],
  );

  const attemptMove = useCallback(
    (direction: Direction2048) => {
      const current = engineRef.current;
      if (!current || !loadedRef.current || current.isGameOver || winOverlayRef.current) return;
      const result = current.moveWithResult(direction);
      // A direction the active game accepts is an operation even when no tile can move.
      postStat(gameId, "moveAttempts", 1);
      if (result !== null) {
        const delta = result.statisticsDelta;
        postStat(gameId, "effectiveMoves", delta.effectiveMoves);
        postStat(gameId, "merges", delta.merges);
        postStat(gameId, "wins", delta.wins);
        if (delta.wins > 0) {
          winOverlayRef.current = true;
          setWinOverlayOpen(true);
        }
        bumpHighestTile(delta.highestTile);
        transitionSeqRef.current += 1;
        if (animate) {
          setTransition({ result, seq: transitionSeqRef.current });
          setPhase("slide");
        } else {
          setTransition(null);
        }
        bump();
      }
    },
    [animate, bump, bumpHighestTile, gameId],
  );

  // Slide → pop → settle, mirroring the two-phase Compose animation.
  useEffect(() => {
    if (!transition) return;
    const t1 = window.setTimeout(() => setPhase("pop"), slideMillis);
    const t2 = window.setTimeout(() => setTransition(null), durationMillis);
    return () => {
      window.clearTimeout(t1);
      window.clearTimeout(t2);
    };
  }, [transition, slideMillis, durationMillis]);

  // Record the high score exactly once when the round ends.
  useEffect(() => {
    const current = engineRef.current;
    if (!loaded || !current || !current.isGameOver || scoreRecordedRef.current) return;
    scoreRecordedRef.current = true;
    recordScoreNow();
  }, [loaded, tickDep(engineRef), recordScoreNow]);

  const undoLastMove = useCallback(() => {
    const current = engineRef.current;
    if (!current || !current.undo()) return;
    setTransition(null);
    scoreRecordedRef.current = false;
    saveProgressNow();
    bump();
  }, [bump, saveProgressNow]);

  const startNewGame = useCallback(() => {
    engineRef.current = new Game2048(boardSize);
    setTransition(null);
    scoreRecordedRef.current = false;
    winOverlayRef.current = false;
    setWinOverlayOpen(false);
    void enqueue(() =>
      apiSend(`/api/games/states/${encodeURIComponent(gameId)}`, "PUT", { highScore: highScoreRef.current, saveJson: null }).then(() => undefined),
    );
    bump();
  }, [boardSize, bump, enqueue, gameId]);

  // Keyboard: arrow keys + WASD.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const key = event.key;
      let direction: Direction2048 | null = null;
      if (key === "ArrowUp" || key === "w" || key === "W") direction = "UP";
      else if (key === "ArrowDown" || key === "s" || key === "S") direction = "DOWN";
      else if (key === "ArrowLeft" || key === "a" || key === "A") direction = "LEFT";
      else if (key === "ArrowRight" || key === "d" || key === "D") direction = "RIGHT";
      if (direction === null) return;
      event.preventDefault();
      attemptMove(direction);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [attemptMove]);

  // Page-wide swipe: one dominant-axis direction judged when the pointer is released.
  const pointerStartRef = useRef<{ x: number; y: number } | null>(null);
  const onPointerDown = useCallback((event: React.PointerEvent) => {
    pointerStartRef.current = { x: event.clientX, y: event.clientY };
  }, []);
  const onPointerCancel = useCallback(() => {
    pointerStartRef.current = null;
  }, []);
  const onPointerUp = useCallback(
    (event: React.PointerEvent) => {
      const start = pointerStartRef.current;
      pointerStartRef.current = null;
      if (!start) return;
      const dx = event.clientX - start.x;
      const dy = event.clientY - start.y;
      if (Math.abs(dx) < SWIPE_THRESHOLD_PX && Math.abs(dy) < SWIPE_THRESHOLD_PX) return;
      if (Math.abs(dx) >= Math.abs(dy)) attemptMove(dx > 0 ? "RIGHT" : "LEFT");
      else attemptMove(dy > 0 ? "DOWN" : "UP");
    },
    [attemptMove],
  );

  const engine = engineRef.current;
  const board = engine ? engine.board : new Array<number>(boardSize * boardSize).fill(0);
  const score = engine ? engine.score : 0;
  const gameOver = engine ? engine.isGameOver : false;
  const canUndo = engine ? engine.canUndo : false;

  const gap = boardSize === 4 ? 10 : boardSize === 5 ? 8 : 7;
  const boardPadding = gap;
  const tileSize =
    boardWidth > 0 ? (boardWidth - boardPadding * 2 - gap * (boardSize - 1)) / boardSize : 0;
  const cellOffset = useCallback(
    (index: number): { x: number; y: number } => ({
      x: boardPadding + (index % boardSize) * (tileSize + gap),
      y: boardPadding + Math.floor(index / boardSize) * (tileSize + gap),
    }),
    [boardPadding, boardSize, gap, tileSize],
  );

  const largeLayout = boardWidth >= 480;
  const mergeDestinations = useMemo(
    () => (transition ? new Set(transition.result.merges.map((m) => m.toIndex)) : new Set<number>()),
    [transition],
  );

  const renderTileFace = (value: number): React.CSSProperties => ({
    background: TILE_COLORS[value] ?? "#3C3A32",
    color: value <= 4 ? palette.darkTileText : palette.lightTileText,
    fontSize: tileSize > 0 ? tileFontSize(value, boardSize, tileSize) : undefined,
  });

  const tileBaseStyle: React.CSSProperties = {
    position: "absolute",
    width: tileSize,
    height: tileSize,
    borderRadius: 3,
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    fontWeight: 700,
    lineHeight: 1,
    whiteSpace: "nowrap",
    overflow: "hidden",
  };

  const showSlide = transition !== null && phase === "slide";
  const shownBoard = transition ? transition.result.after : board;

  return (
    <div
      style={{
        minHeight: "100%",
        background: palette.pageBackground,
        color: palette.heading,
        touchAction: "pan-y",
        userSelect: "none",
        WebkitUserSelect: "none",
        display: "flex",
        justifyContent: "center",
      }}
      onPointerDown={onPointerDown}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerCancel}
    >
      <style>{`
        @keyframes dc2048-slide { to { transform: translate(var(--dc2048-dx), var(--dc2048-dy)); } }
        @keyframes dc2048-appear { from { transform: scale(0); opacity: 0; } to { transform: scale(1); opacity: 1; } }
        @keyframes dc2048-pop { 0% { transform: scale(0); } 50% { transform: scale(1.2); } 100% { transform: scale(1); } }
        @keyframes dc2048-float { from { transform: translateY(0); opacity: 1; } to { transform: translateY(-40px); opacity: 0; } }
        .dc2048-motion { animation-name: dc2048-slide; animation-timing-function: cubic-bezier(0.42, 0, 0.58, 1); animation-fill-mode: forwards; }
        .dc2048-spawn { animation-name: dc2048-appear; animation-timing-function: cubic-bezier(0.25, 0.1, 0.25, 1); animation-fill-mode: both; }
        .dc2048-merged { animation-name: dc2048-pop; animation-timing-function: ease-in-out; animation-fill-mode: both; }
        .dc2048-float { animation-name: dc2048-float; animation-timing-function: cubic-bezier(0.42, 0, 1, 1); animation-fill-mode: both; }
      `}</style>
      <div style={{ width: "min(92vw, 520px)", padding: "16px 0", display: "flex", flexDirection: "column" }}>
        {/* Top controls */}
        <div className="dc-row" style={{ gap: 4 }}>
          <button
            className="dc-icon-btn"
            aria-label={tr("返回", "Back")}
            onClick={() => {
              const current = engineRef.current;
              if (current && !current.isGameOver) saveProgressNow();
              navigate("/games");
            }}
            style={{ color: palette.heading }}
          >
            <ArrowLeft size={22} />
          </button>
          <span className="dc-grow" />
          <button
            className="dc-icon-btn"
            aria-label={darkMode ? tr("切换白天模式", "Switch to day mode") : tr("切换黑夜模式", "Switch to night mode")}
            onClick={() => setDarkOverride(!darkMode)}
            style={{ color: palette.heading }}
          >
            {darkMode ? <Sun size={20} /> : <Moon size={20} />}
          </button>
          <button
            className="dc-btn"
            onClick={() => void updateSettings({ game2048AnimationSpeed: nextSpeed(settings.game2048AnimationSpeed) })}
            style={{ color: palette.heading, background: "transparent" }}
          >
            {settings.game2048AnimationSpeed === "SLOW"
              ? tr("慢速", "Slow")
              : settings.game2048AnimationSpeed === "NORMAL"
                ? tr("标准", "Normal")
                : tr("快速", "Fast")}
          </button>
          <button
            className="dc-icon-btn"
            aria-label={animationsEnabled ? tr("关闭动画", "Disable animations") : tr("开启动画", "Enable animations")}
            onClick={() => setAnimationsEnabled(!animationsEnabled)}
            style={{ color: palette.heading }}
          >
            {animationsEnabled ? <Pause size={20} /> : <Play size={20} />}
          </button>
        </div>

        {/* Title + score */}
        <div className="dc-row" style={{ marginTop: 8 }}>
          <span
            className="dc-grow"
            style={{
              fontSize: largeLayout ? 72 : 27,
              fontWeight: 700,
              lineHeight: 1,
              color: palette.heading,
            }}
          >
            2048
          </span>
          <ScoreBox2048
            label={tr("分数", "Score")}
            value={score}
            addition={animate ? transition?.result.scoreGained ?? 0 : 0}
            transitionSeq={transition?.seq ?? 0}
            large={largeLayout}
            floatMillis={durationMillis * 2}
            palette={palette}
          />
        </div>

        {/* Tagline + new game */}
        <div className="dc-row" style={{ marginTop: largeLayout ? 26 : 16 }}>
          <span className="dc-grow" style={{ fontSize: largeLayout ? 18 : 13, fontWeight: 600, lineHeight: 1.3, color: palette.heading }}>
            {tr("合并数字，得到 2048 方块！", "Join the numbers and get to the 2048 tile!")}
          </span>
          <button
            onClick={startNewGame}
            style={{
              background: palette.button,
              color: "#FFFFFF",
              border: "none",
              borderRadius: 3,
              fontWeight: 700,
              fontSize: largeLayout ? 18 : 12,
              padding: largeLayout ? "9px 20px" : "7px 12px",
              cursor: "pointer",
            }}
          >
            {tr("新游戏", "New Game")}
          </button>
        </div>

        {/* Board */}
        <div
          ref={boardBoxRef}
          style={{
            position: "relative",
            width: "100%",
            aspectRatio: "1 / 1",
            marginTop: largeLayout ? 32 : 12,
            borderRadius: 6,
            background: palette.board,
          }}
          role="application"
          aria-label={tr("2048 棋盘", "2048 board")}
        >
          {Array.from({ length: boardSize * boardSize }, (_, index) => {
            const pos = cellOffset(index);
            return (
              <div
                key={`bg${index}`}
                style={{
                  position: "absolute",
                  left: pos.x,
                  top: pos.y,
                  width: tileSize,
                  height: tileSize,
                  borderRadius: 3,
                  background: palette.emptyTile,
                }}
              />
            );
          })}

          {showSlide
            ? transition!.result.motions.map((motion, order) => {
                const from = cellOffset(motion.fromIndex);
                const to = cellOffset(motion.toIndex);
                return (
                  <div
                    key={`m${transition!.seq}-${order}`}
                    className="dc2048-motion"
                    style={{
                      ...tileBaseStyle,
                      ...renderTileFace(motion.value),
                      left: from.x,
                      top: from.y,
                      ["--dc2048-dx" as string]: `${to.x - from.x}px`,
                      ["--dc2048-dy" as string]: `${to.y - from.y}px`,
                      animationDuration: `${slideMillis}ms`,
                    }}
                  >
                    {motion.value}
                  </div>
                );
              })
            : shownBoard.map((value, index) => {
                if (value === 0) return null;
                const pos = cellOffset(index);
                const isSpawn = transition !== null && index === transition.result.spawn.index;
                const isMerged = mergeDestinations.has(index);
                const animClass = transition === null ? "" : isSpawn ? "dc2048-spawn" : isMerged ? "dc2048-merged" : "";
                return (
                  <div
                    key={`t${transition?.seq ?? "s"}-${index}-${value}`}
                    className={animClass}
                    style={{
                      ...tileBaseStyle,
                      ...renderTileFace(value),
                      left: pos.x,
                      top: pos.y,
                      ...(transition !== null && animClass ? { animationDuration: `${popMillis}ms` } : {}),
                    }}
                  >
                    {value}
                  </div>
                );
              })}

          {gameOver && (
            <div
              style={{
                position: "absolute",
                inset: 0,
                borderRadius: 6,
                background: `${palette.pageBackground}D1`,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                zIndex: 10,
              }}
            >
              <div style={{ textAlign: "center" }}>
                <div style={{ fontSize: largeLayout ? 55 : 34, fontWeight: 700, color: palette.heading }}>
                  {tr("游戏结束！", "Game over!")}
                </div>
                <div className="dc-row" style={{ marginTop: 18, justifyContent: "center", gap: 8 }}>
                  {canUndo && (
                    <button
                      onClick={undoLastMove}
                      style={{
                        background: palette.button,
                        color: "#FFFFFF",
                        border: "none",
                        borderRadius: 3,
                        fontWeight: 700,
                        fontSize: largeLayout ? 18 : 12,
                        padding: largeLayout ? "9px 20px" : "7px 12px",
                        cursor: "pointer",
                      }}
                    >
                      {tr("撤回", "Undo")}
                    </button>
                  )}
                  <button
                    onClick={startNewGame}
                    style={{
                      background: palette.button,
                      color: "#FFFFFF",
                      border: "none",
                      borderRadius: 3,
                      fontWeight: 700,
                      fontSize: largeLayout ? 18 : 12,
                      padding: largeLayout ? "9px 20px" : "7px 12px",
                      cursor: "pointer",
                    }}
                  >
                    {tr("再试一次", "Try again")}
                  </button>
                </div>
              </div>
            </div>
          )}

          {winOverlayOpen && !gameOver && (
            <div
              role="dialog"
              aria-modal="true"
              style={{
                position: "absolute",
                inset: 0,
                borderRadius: 6,
                background: `${palette.pageBackground}D1`,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                zIndex: 11,
              }}
            >
              <div style={{ textAlign: "center" }}>
                <div style={{ fontSize: largeLayout ? 48 : 30, fontWeight: 700, color: palette.heading }}>
                  {tr("达到 2048！", "Got 2048!")}
                </div>
                <div className="dc-row" style={{ marginTop: 18, justifyContent: "center", gap: 8 }}>
                  <button
                    onClick={() => {
                      winOverlayRef.current = false;
                      setWinOverlayOpen(false);
                    }}
                    style={{
                      background: palette.button,
                      color: "#FFFFFF",
                      border: "none",
                      borderRadius: 3,
                      fontWeight: 700,
                      fontSize: largeLayout ? 18 : 12,
                      padding: largeLayout ? "9px 20px" : "7px 12px",
                      cursor: "pointer",
                    }}
                  >
                    {tr("继续游戏", "Keep going")}
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Unlimited undo */}
      <button
        className="dc-icon-btn"
        aria-label={tr("无限撤回", "Undo")}
        onClick={undoLastMove}
        disabled={!canUndo}
        style={{
          position: "fixed",
          right: 16,
          bottom: "calc(var(--dc-bottom-nav-height) + 24px + env(safe-area-inset-bottom))",
          width: 52,
          height: 52,
          borderRadius: 16,
          background: "var(--dc-secondary-container)",
          color: "var(--dc-on-secondary-container)",
          boxShadow: "0 4px 14px rgba(0,0,0,0.25)",
          zIndex: 60,
          opacity: canUndo ? 1 : 0.4,
        }}
      >
        <RotateCcw size={22} />
      </button>
    </div>
  );
}

/** Stable dep that changes whenever the engine mutates (forces the game-over effect to re-run). */
function tickDep(ref: React.MutableRefObject<Game2048 | null>): number {
  return ref.current ? ref.current.score * 31 + (ref.current.isGameOver ? 1 : 0) + (ref.current.canUndo ? 2 : 0) : -1;
}

function ScoreBox2048(props: {
  label: string;
  value: number;
  addition: number;
  transitionSeq: number;
  large: boolean;
  floatMillis: number;
  palette: Palette2048;
}) {
  const floating = props.addition > 0;
  return (
    <div style={{ position: "relative" }}>
      <div
        style={{
          minWidth: props.large ? 92 : 55,
          borderRadius: 3,
          background: props.palette.board,
          padding: props.large ? "8px 15px" : "5px 8px",
          textAlign: "center",
        }}
      >
        <div
          style={{
            color: props.palette.scoreLabel,
            fontSize: props.large ? 13 : 10,
            lineHeight: props.large ? "14px" : "11px",
            fontWeight: 700,
            textTransform: "uppercase",
          }}
        >
          {props.label}
        </div>
        <div style={{ color: "#FFFFFF", fontSize: props.large ? 25 : 17, lineHeight: props.large ? "27px" : "18px", fontWeight: 700 }}>
          {props.value}
        </div>
      </div>
      {floating && (
        <div
          key={props.transitionSeq}
          className="dc2048-float"
          style={{
            position: "absolute",
            top: 0,
            left: 0,
            right: 0,
            textAlign: "center",
            color: props.palette.heading,
            fontSize: props.large ? 25 : 18,
            fontWeight: 700,
            pointerEvents: "none",
            animationDuration: `${props.floatMillis}ms`,
          }}
        >
          +{props.addition}
        </div>
      )}
    </div>
  );
}
