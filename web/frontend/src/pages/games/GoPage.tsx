/**
 * Go game page (/games/go). Faithful web port of the Android GoPage in
 * ui/games/GoGameScreen.kt: local two-player play on 9/13/19 boards starting with
 * black, captures/suicide/simple ko via engineGo.ts, tap snapped to the nearest
 * intersection through the exact shared board geometry, 停一手/清空重开 controls,
 * restart confirmation, finished dialog reporting 黑提/白提 counts only (no territory
 * scoring), status panel with 第 n 手 and consecutive-pass warning. Progress saves to
 * /api/games/states/go after every accepted action plus on background/unmount;
 * statistics use the exact Kotlin metric keys goMovesPlayed / goStonesCaptured /
 * goPasses / goGamesCompleted. Arrow keys + Enter mirror the TalkBack actions.
 */
import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { ApiClientError, apiGet, apiSend } from "../../api/client";
import { tr } from "../../i18n/tr";
import { ConfirmDialog, Spinner } from "../../components/ui";
import { GO_BLACK, GO_WHITE, GoGame, type GoMoveError, type GoPoint } from "./engineGo";

const GAME_ID = "go";

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

export interface GoBoardGeometry {
  originX: number;
  originY: number;
  lastX: number;
  lastY: number;
  spacing: number;
}

const BOARD_PADDING_FRACTION = 0.065;

/** Shared by drawing and hit testing so a visible intersection is always the playable one. */
export function goBoardGeometry(
  boardWidth: number,
  boardHeight: number,
  boardSize: number,
): GoBoardGeometry | null {
  if (
    !Number.isFinite(boardWidth) ||
    !Number.isFinite(boardHeight) ||
    boardWidth <= 0 ||
    boardHeight <= 0 ||
    boardSize < 2
  ) {
    return null;
  }
  const side = Math.min(boardWidth, boardHeight);
  const left = (boardWidth - side) / 2;
  const top = (boardHeight - side) / 2;
  const padding = side * BOARD_PADDING_FRACTION;
  const spacing = (side - padding * 2) / (boardSize - 1);
  if (!Number.isFinite(spacing) || spacing <= 0) return null;
  return {
    originX: left + padding,
    originY: top + padding,
    lastX: left + side - padding,
    lastY: top + side - padding,
    spacing,
  };
}

/**
 * Snaps a tap to its nearest intersection. The half-spacing boundary gives every visible grid
 * cell a continuous target instead of leaving dead zones between tiny circular hit areas.
 */
export function goIntersectionForTap(
  tapX: number,
  tapY: number,
  boardWidth: number,
  boardHeight: number,
  boardSize: number,
): GoPoint | null {
  if (!Number.isFinite(tapX) || !Number.isFinite(tapY)) return null;
  const geometry = goBoardGeometry(boardWidth, boardHeight, boardSize);
  if (!geometry) return null;
  const edgeAllowance = geometry.spacing / 2;
  if (
    tapX < geometry.originX - edgeAllowance ||
    tapX > geometry.lastX + edgeAllowance ||
    tapY < geometry.originY - edgeAllowance ||
    tapY > geometry.lastY + edgeAllowance
  ) {
    return null;
  }
  const x = Math.round((tapX - geometry.originX) / geometry.spacing);
  const y = Math.round((tapY - geometry.originY) / geometry.spacing);
  return { x: Math.min(Math.max(x, 0), boardSize - 1), y: Math.min(Math.max(y, 0), boardSize - 1) };
}

function starPoints(size: number): GoPoint[] {
  if (size === 9) {
    return [
      { x: 2, y: 2 }, { x: 6, y: 2 }, { x: 4, y: 4 },
      { x: 2, y: 6 }, { x: 6, y: 6 },
    ];
  }
  if (size === 13) {
    const points: GoPoint[] = [];
    for (const y of [3, 6, 9]) for (const x of [3, 6, 9]) points.push({ x, y });
    return points;
  }
  if (size === 19) {
    const points: GoPoint[] = [];
    for (const y of [3, 9, 15]) for (const x of [3, 9, 15]) points.push({ x, y });
    return points;
  }
  return [];
}

function cssVar(name: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value || fallback;
}

function goMoveErrorText(error: GoMoveError): string {
  switch (error) {
    case "OUT_OF_BOUNDS":
      return tr("请点击棋盘交叉点。", "Tap a board intersection.");
    case "OCCUPIED":
      return tr("这个交叉点已有棋子。", "That intersection is occupied.");
    case "SUICIDE":
      return tr("不能下自杀棋。", "Suicide moves are not allowed.");
    case "KO":
      return tr("简单劫：不能立即还原上一局面。", "Simple ko: the previous position cannot be repeated immediately.");
    case "GAME_FINISHED":
      return tr("棋局已经结束。", "The game has finished.");
  }
}

export default function GoPage() {
  const navigate = useNavigate();

  const engineRef = useRef<GoGame | null>(null);
  const [tick, setTick] = useState(0);
  const bump = useCallback(() => setTick((t) => (t + 1) % 1_000_000_000), []);
  const [loaded, setLoaded] = useState(false);
  const [lastError, setLastError] = useState<GoMoveError | null>(null);
  const [pendingRestartSize, setPendingRestartSize] = useState<number | null>(null);

  const highScoreRef = useRef(0);
  const saveQueueRef = useRef<Promise<void>>(Promise.resolve());

  // Keyboard-accessible selection mirroring the Android custom accessibility actions.
  const [selected, setSelected] = useState<GoPoint>({ x: 4, y: 4 });

  const [boardPx, setBoardPx] = useState({ width: 0, height: 0 });
  const boardBoxRef = useRef<HTMLDivElement | null>(null);

  useLayoutEffect(() => {
    const node = boardBoxRef.current;
    if (!node) return;
    const update = () => {
      const rect = node.getBoundingClientRect();
      setBoardPx({ width: rect.width, height: rect.height });
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

  /** Finished rounds finalize the best-captures score; running ones keep their snapshot. */
  const saveOrFinish = useCallback(
    (game: GoGame) => {
      if (game.isFinished) {
        const high = Math.max(highScoreRef.current, game.captureScore());
        highScoreRef.current = high;
        void enqueue(() =>
          apiSend(`/api/games/states/${encodeURIComponent(GAME_ID)}`, "PUT", { highScore: high, saveJson: null }).then(() => undefined),
        );
      } else {
        void enqueue(() =>
          apiSend(`/api/games/states/${encodeURIComponent(GAME_ID)}`, "PUT", { highScore: highScoreRef.current, saveJson: game.toJson() }).then(() => undefined),
        );
      }
    },
    [enqueue],
  );
  const saveOrFinishRef = useRef(saveOrFinish);
  useEffect(() => {
    saveOrFinishRef.current = saveOrFinish;
  }, [saveOrFinish]);

  // Load the saved position on mount (corrupt saves degrade to a fresh 9×9 game).
  useEffect(() => {
    let cancelled = false;
    (async () => {
      let restored: GoGame | null = null;
      let fresh = false;
      try {
        const state = await fetchGameState(GAME_ID);
        if (cancelled) return;
        highScoreRef.current = state.highScore;
        restored = state.saveJson ? GoGame.fromJson(state.saveJson) : null;
      } catch {
        restored = null;
      }
      if (cancelled) return;
      fresh = restored === null;
      engineRef.current = restored ?? new GoGame();
      setSelected({ x: Math.floor(engineRef.current.size / 2), y: Math.floor(engineRef.current.size / 2) });
      setLoaded(true);
      bump();
      if (fresh) saveOrFinishRef.current(engineRef.current);
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Persist once more when the tab hides or the page unmounts.
  useEffect(() => {
    const onHide = () => {
      if (document.hidden && engineRef.current && loaded && !engineRef.current.isFinished) {
        saveOrFinishRef.current(engineRef.current);
      }
    };
    document.addEventListener("visibilitychange", onHide);
    return () => {
      document.removeEventListener("visibilitychange", onHide);
      const current = engineRef.current;
      if (current && loaded && !current.isFinished) saveOrFinishRef.current(current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loaded]);

  const replaceGame = useCallback(
    (size: number) => {
      const replacement = new GoGame(size);
      engineRef.current = replacement;
      setLastError(null);
      setPendingRestartSize(null);
      setSelected({ x: Math.floor(size / 2), y: Math.floor(size / 2) });
      void enqueue(() =>
        apiSend(`/api/games/states/${encodeURIComponent(GAME_ID)}`, "PUT", { highScore: highScoreRef.current, saveJson: replacement.toJson() }).then(() => undefined),
      );
      bump();
    },
    [bump, enqueue],
  );

  const handlePlay = useCallback(
    (x: number, y: number): boolean => {
      const current = engineRef.current;
      if (!current || !loaded) return false;
      const result = current.play(x, y);
      if (result.accepted) {
        setLastError(null);
        postStat("goMovesPlayed", result.statisticsDelta.movesPlayed);
        postStat("goStonesCaptured", result.statisticsDelta.stonesCaptured);
        saveOrFinish(current);
        bump();
        return true;
      }
      setLastError(result.error ?? null);
      return false;
    },
    [bump, loaded, saveOrFinish],
  );

  const handlePass = useCallback(() => {
    const current = engineRef.current;
    if (!current || !loaded) return;
    const result = current.pass();
    if (result.accepted) {
      setLastError(null);
      postStat("goPasses", result.statisticsDelta.passes);
      postStat("goGamesCompleted", result.statisticsDelta.gamesCompleted);
      saveOrFinish(current);
      bump();
    }
  }, [bump, loaded, saveOrFinish]);

  const leaveGame = useCallback(() => {
    const current = engineRef.current;
    if (current && !current.isFinished) saveOrFinish(current);
    navigate("/games");
  }, [navigate, saveOrFinish]);

  const engine = engineRef.current;
  const size = engine?.size ?? 9;
  const captures = engine ? engine.captureScore() : 0;
  const bestCaptures = Math.max(highScoreRef.current, captures);

  // Reset keyboard selection when the board size changes.
  useEffect(() => {
    setSelected({ x: Math.floor(size / 2), y: Math.floor(size / 2) });
  }, [size]);

  // Redraw whenever anything relevant changes.
  useEffect(() => {
    const canvas = canvasRef.current;
    const current = engineRef.current;
    if (!canvas || !current || boardPx.width <= 0 || boardPx.height <= 0) return;
    drawGoBoard(canvas, current, boardPx.width, boardPx.height);
  });

  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  const onBoardClick = useCallback(
    (event: React.MouseEvent<HTMLDivElement>) => {
      const current = engineRef.current;
      if (!current || current.isFinished) return;
      const rect = event.currentTarget.getBoundingClientRect();
      const point = goIntersectionForTap(
        event.clientX - rect.left,
        event.clientY - rect.top,
        rect.width,
        rect.height,
        current.size,
      );
      if (point) handlePlay(point.x, point.y);
    },
    [handlePlay],
  );

  const onBoardKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLDivElement>) => {
      const current = engineRef.current;
      if (!current || current.isFinished) return;
      const key = event.key;
      if (key === "ArrowLeft" || key === "ArrowRight" || key === "ArrowUp" || key === "ArrowDown") {
        event.preventDefault();
        setSelected((prev) => {
          const next = { ...prev };
          if (key === "ArrowLeft") next.x = Math.max(0, prev.x - 1);
          else if (key === "ArrowRight") next.x = Math.min(current.size - 1, prev.x + 1);
          else if (key === "ArrowUp") next.y = Math.max(0, prev.y - 1);
          else next.y = Math.min(current.size - 1, prev.y + 1);
          return next;
        });
      } else if (key === "Enter" || key === " ") {
        event.preventDefault();
        handlePlay(selected.x, selected.y);
      }
    },
    [handlePlay, selected.x, selected.y],
  );

  if (!loaded || !engine) {
    return (
      <div className="dc-col dc-center" style={{ minHeight: "60vh" }}>
        <Spinner />
      </div>
    );
  }

  const blackCount = engine.boardSnapshot().filter((stone) => stone === GO_BLACK).length;
  const whiteCount = engine.boardSnapshot().filter((stone) => stone === GO_WHITE).length;
  const boardDescription = tr(
    `${engine.size}路围棋棋盘，黑子 ${blackCount}，白子 ${whiteCount}。点击交叉点落子。`,
    `${engine.size} by ${engine.size} Go board with ${blackCount} black and ${whiteCount} white stones. Tap an intersection to play.`,
  );

  return (
    <div className="dc-col" style={{ maxWidth: 560, margin: "0 auto", width: "100%" }}>
      {/* Top bar */}
      <div className="dc-row">
        <button className="dc-icon-btn" aria-label={tr("返回", "Back")} onClick={leaveGame}>
          <ArrowLeft size={22} />
        </button>
        <div className="dc-title dc-grow">{tr("围棋", "Go")}</div>
      </div>

      {/* Score chips */}
      <div className="dc-row" style={{ gap: 12, padding: "8px 0" }}>
        <div className="dc-card dc-grow" style={{ padding: "8px 14px", textAlign: "center" }}>
          <div className="dc-muted" style={{ fontSize: "0.8em" }}>{tr("本局提子", "Captured")}</div>
          <div style={{ fontWeight: 600, fontSize: "1.15em" }}>{captures}</div>
        </div>
        <div className="dc-card dc-grow" style={{ padding: "8px 14px", textAlign: "center" }}>
          <div className="dc-muted" style={{ fontSize: "0.8em" }}>{tr("最高提子", "Best captures")}</div>
          <div style={{ fontWeight: 600, fontSize: "1.15em" }}>{bestCaptures}</div>
        </div>
      </div>

      {/* Status panel */}
      <div className="dc-card dc-col" style={{ padding: "10px 14px", gap: 6 }}>
        <div className="dc-row" style={{ justifyContent: "space-between" }}>
          <span className="dc-row" style={{ gap: 8 }}>
            <GoStoneDot stone={engine.currentPlayer} />
            <span style={{ fontWeight: 600 }}>
              {engine.currentPlayer === GO_BLACK ? tr("黑方落子", "Black to play") : tr("白方落子", "White to play")}
            </span>
          </span>
          <span className="dc-muted" style={{ fontSize: "0.85em" }}>
            {tr(`第 ${engine.turnCount + 1} 手`, `Turn ${engine.turnCount + 1}`)}
          </span>
        </div>
        <div className="dc-muted" style={{ fontSize: "0.85em" }}>
          {tr(
            `黑提 ${engine.capturedByBlack} · 白提 ${engine.capturedByWhite}`,
            `Black captures ${engine.capturedByBlack} · White captures ${engine.capturedByWhite}`,
          )}
        </div>
        {engine.consecutivePasses === 1 && (
          <div style={{ fontSize: "0.85em", color: "var(--dc-tertiary)" }}>
            {tr("上一方已停着；再次停着将结束棋局。", "The last player passed; another pass ends the game.")}
          </div>
        )}
      </div>

      {/* Board size selector */}
      <div className="dc-row" style={{ gap: 8, padding: "10px 0 0" }}>
        {[9, 13, 19].map((candidate) => (
          <button
            key={candidate}
            className={`dc-btn dc-grow ${candidate === engine.size ? "dc-btn-filled" : ""}`}
            onClick={() => {
              if (candidate !== engine.size || engine.turnCount > 0) {
                setPendingRestartSize(candidate);
              }
            }}
          >
            {`${candidate}×${candidate}`}
          </button>
        ))}
      </div>

      {/* Board */}
      <div className="dc-center" style={{ padding: "10px 0" }}>
        <div
          ref={boardBoxRef}
          onClick={onBoardClick}
          onKeyDown={onBoardKeyDown}
          role="application"
          tabIndex={0}
          aria-label={boardDescription}
          style={{
            width: "min(92vw, 520px)",
            aspectRatio: "1 / 1",
            borderRadius: 18,
            touchAction: "manipulation",
            cursor: engine.isFinished ? "default" : "pointer",
            outlineOffset: 2,
          }}
        >
          <canvas ref={canvasRef} style={{ width: "100%", height: "100%", borderRadius: 18 }} />
        </div>
      </div>

      {lastError && (
        <div role="status" style={{ color: "var(--dc-error)", fontSize: "0.85em", paddingBottom: 6 }}>
          {goMoveErrorText(lastError)}
        </div>
      )}

      {/* Pass / restart */}
      <div className="dc-row" style={{ gap: 10, padding: "4px 0" }}>
        <button className="dc-btn dc-btn-filled dc-grow" disabled={engine.isFinished} onClick={handlePass}>
          {tr("停一手", "Pass")}
        </button>
        <button className="dc-btn dc-grow" onClick={() => setPendingRestartSize(engine.size)}>
          {tr("清空重开", "Clear & restart")}
        </button>
      </div>

      <div className="dc-muted" style={{ fontSize: "0.82em", padding: "4px 0 12px" }}>
        {tr(
          "连续两次停着结束棋局；本页记录提子数，不自动判定地域胜负。",
          "Two consecutive passes end the game. Captures are tracked; territory is not scored automatically.",
        )}
      </div>

      {/* Restart confirmation */}
      <ConfirmDialog
        open={pendingRestartSize !== null}
        title={tr("重新开始？", "Start over?")}
        message={tr(
          `当前棋局会被清空，并开始一局 ${pendingRestartSize ?? 9}×${pendingRestartSize ?? 9} 围棋。`,
          `The current board will be cleared and a ${pendingRestartSize ?? 9}×${pendingRestartSize ?? 9} game will begin.`,
        )}
        confirmLabel={tr("重开", "Restart")}
        onCancel={() => setPendingRestartSize(null)}
        onConfirm={() => replaceGame(pendingRestartSize ?? engine.size)}
      />

      {/* Finished dialog (explicit button choice only, like Android) */}
      {engine.isFinished && pendingRestartSize === null && (
        <div className="dc-dialog-overlay">
          <div className="dc-dialog" role="dialog" aria-modal="true" style={{ width: "min(420px, 94vw)" }}>
            <div className="dc-title" style={{ marginBottom: 8 }}>{tr("棋局结束", "Game finished")}</div>
            <div className="dc-muted" style={{ marginBottom: 12 }}>
              {tr(
                `双方连续停着。黑方提子 ${engine.capturedByBlack}，白方提子 ${engine.capturedByWhite}。请按你们采用的数子或数目规则判断胜负。`,
                `Both players passed. Black captured ${engine.capturedByBlack}; White captured ${engine.capturedByWhite}. Use your chosen territory or area rules to determine the result.`,
              )}
            </div>
            <div className="dc-row" style={{ justifyContent: "flex-end" }}>
              <button className="dc-btn" onClick={leaveGame}>{tr("返回", "Back")}</button>
              <button className="dc-btn dc-btn-filled" onClick={() => replaceGame(engine.size)}>
                {tr("再来一局", "Play again")}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function GoStoneDot({ stone }: { stone: number }) {
  const fill = stone === GO_BLACK ? "#171717" : "#F7F7F2";
  return (
    <span
      aria-hidden
      style={{
        width: 16,
        height: 16,
        borderRadius: "50%",
        background: fill,
        border: "1px solid var(--dc-outline)",
        display: "inline-block",
      }}
    />
  );
}

/** Canvas painter mirroring the Compose GoBoard draw calls. */
function drawGoBoard(canvas: HTMLCanvasElement, game: GoGame, cssWidth: number, cssHeight: number): void {
  const dpr = window.devicePixelRatio || 1;
  const pixelWidth = Math.max(1, Math.round(cssWidth * dpr));
  const pixelHeight = Math.max(1, Math.round(cssHeight * dpr));
  if (canvas.width !== pixelWidth || canvas.height !== pixelHeight) {
    canvas.width = pixelWidth;
    canvas.height = pixelHeight;
  }
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

  const boardColor = cssVar("--dc-tertiary-container", "#DDD8C6");
  const lineColorBase = cssVar("--dc-on-tertiary-container", "#44403C");
  const outlineColor = cssVar("--dc-outline", "#79747E");

  ctx.clearRect(0, 0, cssWidth, cssHeight);
  ctx.fillStyle = boardColor;
  ctx.fillRect(0, 0, cssWidth, cssHeight);

  const geometry = goBoardGeometry(cssWidth, cssHeight, game.size);
  if (!geometry) return;
  const { originX, originY, lastX, lastY, spacing } = geometry;
  const lineWidth = Math.min(Math.max(spacing * 0.055, 1), 2);

  ctx.strokeStyle = lineColorBase;
  ctx.globalAlpha = 0.72;
  ctx.lineWidth = lineWidth;
  ctx.beginPath();
  for (let i = 0; i < game.size; i++) {
    const x = originX + i * spacing;
    const y = originY + i * spacing;
    ctx.moveTo(originX, y);
    ctx.lineTo(lastX, y);
    ctx.moveTo(x, originY);
    ctx.lineTo(x, lastY);
  }
  ctx.stroke();

  ctx.fillStyle = lineColorBase;
  for (const point of starPoints(game.size)) {
    ctx.beginPath();
    ctx.arc(originX + point.x * spacing, originY + point.y * spacing, Math.max(spacing * 0.1, 2), 0, Math.PI * 2);
    ctx.fill();
  }

  const radius = spacing * 0.43;
  for (let y = 0; y < game.size; y++) {
    for (let x = 0; x < game.size; x++) {
      const stone = game.stoneAt(x, y);
      if (stone === 0) continue;
      const cx = originX + x * spacing;
      const cy = originY + y * spacing;
      const fill = stone === GO_BLACK ? "#171717" : "#F7F7F2";
      ctx.globalAlpha = 1;
      ctx.beginPath();
      ctx.arc(cx, cy, radius, 0, Math.PI * 2);
      ctx.fillStyle = fill;
      ctx.fill();
      ctx.globalAlpha = 0.75;
      ctx.strokeStyle = outlineColor;
      ctx.lineWidth = lineWidth;
      ctx.stroke();
      ctx.globalAlpha = 1;
      if (game.lastMove && game.lastMove.x === x && game.lastMove.y === y) {
        ctx.beginPath();
        ctx.arc(cx, cy, radius * 0.22, 0, Math.PI * 2);
        ctx.fillStyle = stone === GO_BLACK ? "#FFFFFF" : "#000000";
        ctx.fill();
      }
    }
  }
  ctx.globalAlpha = 1;
}
