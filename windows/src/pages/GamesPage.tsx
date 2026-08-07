import {
  ArrowDown,
  ArrowLeft,
  ArrowRight,
  ArrowUp,
  Bomb,
  CirclePause,
  CirclePlay,
  Gamepad2,
  Grid3X3,
  Layers3,
  RotateCcw,
  Save,
  Spade,
  Trophy,
} from "lucide-react";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type FormEvent,
} from "react";
import { useSearchParams } from "react-router-dom";

import { ErrorState, LoadingState } from "../components/AsyncState";
import { PageFrame } from "../components/PageFrame";
import {
  GAME_IDS,
  gameApi,
  gameState,
  type GameId,
  type GameMetricDelta,
  type GamesSnapshotV1,
} from "../lib/gameApi";
import { readableError, tr } from "../lib/ipc";
import { useAppStore } from "../store/appStore";
import {
  abandonSpiderMetrics,
  actMines,
  actTetris,
  dealSpider,
  minesCell,
  move2048,
  moveSpider,
  newGame2048,
  newMines,
  newSnake,
  newSpider,
  newTetris,
  parseGame2048,
  parseMines,
  parseSnake,
  parseSpider,
  parseTetris,
  serializeGame,
  spiderCanSelect,
  tetrisPieceCells,
  tickSnake,
  turnSnake,
  undo2048,
  undoSpider,
  type Game2048Direction,
  type Game2048State,
  type MinesState,
  type SnakeDirection,
  type SnakeState,
  type SpiderState,
  type TetrisAction,
  type TetrisState,
} from "./gameEngines";
import "./games.css";

type Language = "zh-CN" | "en";

type ActiveGame =
  | { id: "2048" | "2048_5" | "2048_6"; state: Game2048State }
  | { id: "snake"; state: SnakeState }
  | { id: "tetris"; state: TetrisState }
  | { id: "minesweeper"; state: MinesState }
  | { id: "spider"; state: SpiderState };

const TITLES: Record<GameId, [string, string]> = {
  "2048": ["2048 · 4×4", "2048 · 4×4"],
  "2048_5": ["2048 · 5×5", "2048 · 5×5"],
  "2048_6": ["2048 · 6×6", "2048 · 6×6"],
  snake: ["贪吃蛇", "Snake"],
  tetris: ["俄罗斯方块", "Tetris"],
  minesweeper: ["扫雷", "Minesweeper"],
  spider: ["单花色蜘蛛纸牌", "One-suit Spider"],
};

function gameTitle(gameId: GameId, language: Language): string {
  return language === "en" ? TITLES[gameId][1] : TITLES[gameId][0];
}

function formatDuration(value: string, language: Language): string {
  const millis = /^\d+$/.test(value) ? BigInt(value) : 0n;
  const minutes = millis / 60_000n;
  const hours = minutes / 60n;
  const rest = minutes % 60n;
  return language === "en"
    ? hours > 0n
      ? `${hours}h ${rest}m`
      : `${rest}m`
    : hours > 0n
      ? `${hours} 小时 ${rest} 分钟`
      : `${rest} 分钟`;
}

function scoreOf(active: ActiveGame): number {
  if (active.id === "minesweeper") {
    if (!active.state.won) return 0;
    const revealedSafe = active.state.revealed.filter(
      (index) => !active.state.mines.includes(index),
    ).length;
    return active.state.count * 100 + revealedSafe;
  }
  if (active.id === "spider") {
    return active.state.completed === 8 ? active.state.score : 0;
  }
  return active.state.score;
}

function createGame(gameId: GameId): ActiveGame {
  if (gameId === "2048" || gameId === "2048_5" || gameId === "2048_6") {
    const size = gameId === "2048" ? 4 : gameId === "2048_5" ? 5 : 6;
    return { id: gameId, state: newGame2048(size) };
  }
  if (gameId === "snake") return { id: gameId, state: newSnake() };
  if (gameId === "tetris") return { id: gameId, state: newTetris() };
  if (gameId === "minesweeper") return { id: gameId, state: newMines() };
  return { id: gameId, state: newSpider() };
}

function restoreGame(gameId: GameId, json: string): ActiveGame | null {
  if (gameId === "2048" || gameId === "2048_5" || gameId === "2048_6") {
    const size = gameId === "2048" ? 4 : gameId === "2048_5" ? 5 : 6;
    const state = parseGame2048(json, size);
    return state ? { id: gameId, state } : null;
  }
  if (gameId === "snake") {
    const state = parseSnake(json);
    return state ? { id: gameId, state } : null;
  }
  if (gameId === "tetris") {
    const state = parseTetris(json);
    return state ? { id: gameId, state } : null;
  }
  if (gameId === "minesweeper") {
    const state = parseMines(json);
    return state ? { id: gameId, state } : null;
  }
  const state = parseSpider(json);
  return state ? { id: gameId, state } : null;
}

function filteredMetrics(metrics: GameMetricDelta | undefined): GameMetricDelta | undefined {
  if (!metrics) return undefined;
  const increments = Object.fromEntries(
    Object.entries(metrics.increments ?? {}).filter(([, value]) => Number(value) > 0),
  );
  const maxima = Object.fromEntries(
    Object.entries(metrics.maxima ?? {}).filter(([, value]) => Number(value) > 0),
  );
  return Object.keys(increments).length || Object.keys(maxima).length
    ? { increments, maxima }
    : undefined;
}

function GameCatalog({
  snapshot,
  language,
  onLaunch,
}: {
  snapshot: GamesSnapshotV1;
  language: Language;
  onLaunch: (gameId: GameId, resume: boolean) => void;
}) {
  return (
    <div className="game-catalog" aria-label={tr(language, "游戏列表", "Game list")}>
      {GAME_IDS.map((gameId) => {
        const meta = gameState(snapshot, gameId);
        const hasSave = Boolean(meta?.saveJson);
        return (
          <article className="panel game-catalog-card" key={gameId}>
            <span className="game-catalog-icon" aria-hidden="true">
              {gameId.startsWith("2048") ? <Grid3X3 /> : gameId === "snake" ? <Gamepad2 /> : gameId === "tetris" ? <Layers3 /> : gameId === "minesweeper" ? <Bomb /> : <Spade />}
            </span>
            <div>
              <h2>{gameTitle(gameId, language)}</h2>
              <p>
                {tr(language, "最高分", "High score")} · {meta?.highScore ?? 0}
              </p>
              <small>
                {tr(language, "游玩", "Played")} {formatDuration(meta?.totalPlayMillis ?? "0", language)}
              </small>
            </div>
            <div className="game-card-actions">
              {hasSave ? (
                <button className="button button-primary" type="button" onClick={() => onLaunch(gameId, true)}>
                  {tr(language, "继续", "Resume")}
                </button>
              ) : null}
              <button className={hasSave ? "button button-secondary" : "button button-primary"} type="button" onClick={() => onLaunch(gameId, false)}>
                {hasSave ? tr(language, "新游戏", "New game") : tr(language, "开始", "Start")}
              </button>
            </div>
          </article>
        );
      })}
    </div>
  );
}

function Game2048Board({
  state,
  language,
  onMove,
  onUndo,
}: {
  state: Game2048State;
  language: Language;
  onMove: (direction: Game2048Direction) => void;
  onUndo: () => void;
}) {
  useEffect(() => {
    const handle = (event: KeyboardEvent) => {
      const direction: Record<string, Game2048Direction | undefined> = {
        ArrowUp: "UP", w: "UP", W: "UP", ArrowDown: "DOWN", s: "DOWN", S: "DOWN",
        ArrowLeft: "LEFT", a: "LEFT", A: "LEFT", ArrowRight: "RIGHT", d: "RIGHT", D: "RIGHT",
      };
      const next = direction[event.key];
      if (next) {
        event.preventDefault();
        onMove(next);
      } else if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "z") {
        event.preventDefault();
        onUndo();
      }
    };
    window.addEventListener("keydown", handle);
    return () => window.removeEventListener("keydown", handle);
  }, [onMove, onUndo]);

  return (
    <div className="game-stage game-2048-stage">
      <div className="game-score-row">
        <strong>{tr(language, "分数", "Score")} {state.score}</strong>
        <button className="button button-secondary" type="button" disabled={!state.undoHistory.length} onClick={onUndo}>
          <RotateCcw size={17} /> {tr(language, "撤回", "Undo")}
        </button>
      </div>
      <div
        className="game-2048-grid"
        style={{ "--board-size": state.size } as CSSProperties}
        aria-label={`${state.size} × ${state.size} 2048`}
      >
        {state.cells.map((value, index) => (
          <span className="game-2048-cell" data-empty={value === 0} data-tier={Math.min(12, value ? Math.log2(value) : 0)} key={index}>
            {value || ""}
          </span>
        ))}
      </div>
      <div className="game-dpad" aria-label={tr(language, "方向控制", "Direction controls")}>
        <button type="button" aria-label={tr(language, "向上", "Up")} onClick={() => onMove("UP")}><ArrowUp /></button>
        <button type="button" aria-label={tr(language, "向左", "Left")} onClick={() => onMove("LEFT")}><ArrowLeft /></button>
        <button type="button" aria-label={tr(language, "向下", "Down")} onClick={() => onMove("DOWN")}><ArrowDown /></button>
        <button type="button" aria-label={tr(language, "向右", "Right")} onClick={() => onMove("RIGHT")}><ArrowRight /></button>
      </div>
      {!state.cells.some((cell) => cell === 0) ? <p className="game-hint">{tr(language, "若没有可合并方块，本局结束。", "The round ends when no merge remains.")}</p> : null}
    </div>
  );
}

function SnakeBoard({
  state,
  language,
  onState,
  onPlaying,
}: {
  state: SnakeState;
  language: Language;
  onState: (state: SnakeState, metrics?: GameMetricDelta, terminal?: boolean) => void;
  onPlaying: (playing: boolean) => void;
}) {
  const [paused, setPaused] = useState(false);
  const stateRef = useRef(state);
  stateRef.current = state;
  const applyTurn = useCallback((direction: SnakeDirection) => {
    const next = turnSnake(stateRef.current, direction);
    if (next !== stateRef.current) onState(next);
  }, [onState]);

  useEffect(() => {
    const handle = (event: KeyboardEvent) => {
      const direction: Record<string, SnakeDirection | undefined> = {
        ArrowUp: "UP", w: "UP", W: "UP", ArrowDown: "DOWN", s: "DOWN", S: "DOWN",
        ArrowLeft: "LEFT", a: "LEFT", A: "LEFT", ArrowRight: "RIGHT", d: "RIGHT", D: "RIGHT",
      };
      if (event.key === " ") {
        event.preventDefault();
        setPaused((value) => !value);
      } else if (direction[event.key]) {
        event.preventDefault();
        applyTurn(direction[event.key]!);
      }
    };
    window.addEventListener("keydown", handle);
    return () => window.removeEventListener("keydown", handle);
  }, [applyTurn]);

  useEffect(() => {
    const playing = !paused && !state.over;
    onPlaying(playing);
    if (!playing) return;
    const timer = window.setInterval(() => {
      const result = tickSnake(stateRef.current);
      if (result.changed) onState(result.state, result.metrics, result.terminal);
    }, 155);
    return () => window.clearInterval(timer);
  }, [onPlaying, onState, paused, state.over]);

  const occupied = useMemo(() => new Map(state.snake.map((point, index) => [`${point[0]}:${point[1]}`, index])), [state.snake]);
  return (
    <div className="game-stage">
      <div className="game-score-row">
        <strong>{tr(language, "分数", "Score")} {state.score}</strong>
        <button className="button button-secondary" type="button" disabled={state.over} onClick={() => setPaused((value) => !value)}>
          {paused ? <CirclePlay size={18} /> : <CirclePause size={18} />}
          {paused ? tr(language, "继续", "Resume") : tr(language, "暂停", "Pause")}
        </button>
      </div>
      <div className="snake-grid" style={{ "--snake-width": state.w } as CSSProperties} aria-label={tr(language, "贪吃蛇棋盘", "Snake board")}>
        {Array.from({ length: state.w * state.h }, (_, index) => {
          const x = index % state.w;
          const y = Math.floor(index / state.w);
          const body = occupied.get(`${x}:${y}`);
          const food = state.food[0] === x && state.food[1] === y;
          return <span key={index} data-snake={body !== undefined} data-head={body === 0} data-food={food} />;
        })}
      </div>
      <div className="game-dpad">
        <button type="button" aria-label={tr(language, "向上", "Up")} onClick={() => applyTurn("UP")}><ArrowUp /></button>
        <button type="button" aria-label={tr(language, "向左", "Left")} onClick={() => applyTurn("LEFT")}><ArrowLeft /></button>
        <button type="button" aria-label={tr(language, "向下", "Down")} onClick={() => applyTurn("DOWN")}><ArrowDown /></button>
        <button type="button" aria-label={tr(language, "向右", "Right")} onClick={() => applyTurn("RIGHT")}><ArrowRight /></button>
      </div>
      {state.over ? <p className="game-result">{tr(language, "本局结束", "Game over")}</p> : null}
    </div>
  );
}

function TetrisBoard({
  state,
  language,
  onState,
  onPlaying,
}: {
  state: TetrisState;
  language: Language;
  onState: (state: TetrisState, metrics?: GameMetricDelta, terminal?: boolean) => void;
  onPlaying: (playing: boolean) => void;
}) {
  const [paused, setPaused] = useState(false);
  const stateRef = useRef(state);
  stateRef.current = state;
  const dispatch = useCallback((action: TetrisAction) => {
    const result = actTetris(stateRef.current, action);
    if (result.changed) onState(result.state, result.metrics, result.terminal);
  }, [onState]);

  useEffect(() => {
    const handle = (event: KeyboardEvent) => {
      const action: Record<string, TetrisAction | undefined> = {
        ArrowLeft: "left", a: "left", A: "left", ArrowRight: "right", d: "right", D: "right",
        ArrowUp: "rotate", w: "rotate", W: "rotate", ArrowDown: "soft", s: "soft", S: "soft",
      };
      if (event.key === " ") {
        event.preventDefault();
        dispatch("hard");
      } else if (event.key.toLowerCase() === "p") {
        event.preventDefault();
        setPaused((value) => !value);
      } else if (action[event.key]) {
        event.preventDefault();
        dispatch(action[event.key]!);
      }
    };
    window.addEventListener("keydown", handle);
    return () => window.removeEventListener("keydown", handle);
  }, [dispatch]);

  useEffect(() => {
    const playing = !paused && !state.over;
    onPlaying(playing);
    if (!playing) return;
    const delay = Math.max(110, 700 - Math.floor(state.lines / 10) * 55);
    const timer = window.setInterval(() => dispatch("tick"), delay);
    return () => window.clearInterval(timer);
  }, [dispatch, onPlaying, paused, state.lines, state.over]);

  const falling = new Set(tetrisPieceCells(state).map(([x, y]) => y * 10 + x));
  return (
    <div className="game-stage tetris-layout">
      <div className="tetris-board" aria-label={tr(language, "俄罗斯方块棋盘", "Tetris board")}>
        {state.board.map((value, index) => (
          <span key={index} data-piece={falling.has(index) ? state.type + 1 : value} />
        ))}
      </div>
      <aside className="tetris-sidebar panel-subtle">
        <strong>{tr(language, "分数", "Score")} {state.score}</strong>
        <span>{tr(language, "消除行", "Lines")} {state.lines}</span>
        <span>{tr(language, "等级", "Level")} {Math.floor(state.lines / 10)}</span>
        <button className="button button-secondary" type="button" disabled={state.over} onClick={() => setPaused((value) => !value)}>
          {paused ? <CirclePlay size={18} /> : <CirclePause size={18} />}
          {paused ? tr(language, "继续", "Resume") : tr(language, "暂停", "Pause")}
        </button>
        <div className="tetris-controls">
          <button type="button" onClick={() => dispatch("left")} aria-label={tr(language, "左移", "Move left")}><ArrowLeft /></button>
          <button type="button" onClick={() => dispatch("rotate")} aria-label={tr(language, "旋转", "Rotate")}><RotateCcw /></button>
          <button type="button" onClick={() => dispatch("right")} aria-label={tr(language, "右移", "Move right")}><ArrowRight /></button>
          <button type="button" onClick={() => dispatch("soft")} aria-label={tr(language, "下移", "Soft drop")}><ArrowDown /></button>
          <button type="button" onClick={() => dispatch("hard")} aria-label={tr(language, "落到底", "Hard drop")}><Layers3 /></button>
        </div>
      </aside>
      {state.over ? <p className="game-result">{tr(language, "本局结束", "Game over")}</p> : null}
    </div>
  );
}

function MinesBoard({
  state,
  language,
  onState,
  onReplace,
}: {
  state: MinesState;
  language: Language;
  onState: (state: MinesState, metrics?: GameMetricDelta, terminal?: boolean) => void;
  onReplace: (state: MinesState) => void;
}) {
  const [width, setWidth] = useState(state.w);
  const [height, setHeight] = useState(state.h);
  const [count, setCount] = useState(state.count);
  const submit = (event: FormEvent) => {
    event.preventDefault();
    const safeWidth = Math.min(30, Math.max(6, Math.trunc(width)));
    const safeHeight = Math.min(30, Math.max(6, Math.trunc(height)));
    const safeCount = Math.min(safeWidth * safeHeight - 1, Math.max(1, Math.trunc(count)));
    onReplace(newMines(safeWidth, safeHeight, safeCount));
  };
  const act = (index: number, action: "reveal" | "chord" | "flag") => {
    const result = actMines(state, index, action);
    if (result.changed) onState(result.state, result.metrics, result.terminal);
  };
  return (
    <div className="game-stage mines-stage">
      <form className="mines-config panel-subtle" onSubmit={submit}>
        <label>{tr(language, "列", "Columns")}<input type="number" min={6} max={30} value={width} onChange={(event) => setWidth(Number(event.target.value))} /></label>
        <label>{tr(language, "行", "Rows")}<input type="number" min={6} max={30} value={height} onChange={(event) => setHeight(Number(event.target.value))} /></label>
        <label>{tr(language, "雷", "Mines")}<input type="number" min={1} max={Math.max(1, width * height - 1)} value={count} onChange={(event) => setCount(Number(event.target.value))} /></label>
        <button className="button button-secondary" type="submit"><RotateCcw size={17} />{tr(language, "应用并重开", "Apply & restart")}</button>
      </form>
      <p className="game-hint">{tr(language, `剩余雷数 ${Math.max(0, state.count - state.flagged.length)} · 双击数字展开周围`, `${Math.max(0, state.count - state.flagged.length)} mines left · Double-click a number to chord`)}</p>
      <div className="mines-scroll" tabIndex={0} role="region" aria-label={tr(language, "扫雷棋盘", "Minesweeper board")}>
        <div className="mines-grid" style={{ "--mines-width": state.w } as CSSProperties}>
          {Array.from({ length: state.w * state.h }, (_, index) => {
            const cell = minesCell(state, index);
            const label = cell.flagged ? "🚩" : cell.mine ? "●" : cell.revealed && cell.adjacent ? cell.adjacent : "";
            return (
              <button
                key={index}
                type="button"
                data-revealed={cell.revealed}
                data-number={cell.adjacent || undefined}
                onClick={() => act(index, "reveal")}
                onDoubleClick={() => act(index, "chord")}
                onContextMenu={(event) => { event.preventDefault(); act(index, "flag"); }}
                aria-label={`${Math.floor(index / state.w) + 1}, ${index % state.w + 1}${cell.flagged ? ", flagged" : ""}`}
              >
                {label}
              </button>
            );
          })}
        </div>
      </div>
      {state.won ? <p className="game-result is-win">{tr(language, "扫雷成功！", "Board cleared!")}</p> : state.over ? <p className="game-result">{tr(language, "踩到雷了", "Mine triggered")}</p> : null}
    </div>
  );
}

function SpiderBoard({
  state,
  language,
  onState,
}: {
  state: SpiderState;
  language: Language;
  onState: (state: SpiderState, metrics?: GameMetricDelta, terminal?: boolean) => void;
}) {
  const [selected, setSelected] = useState<{ column: number; index: number } | null>(null);
  const choose = (column: number, index: number) => {
    if (!selected) {
      if (spiderCanSelect(state, column, index)) setSelected({ column, index });
      return;
    }
    const result = moveSpider(state, selected.column, selected.index, column);
    if (result.changed) {
      setSelected(null);
      onState(result.state, result.metrics, result.terminal);
    } else if (spiderCanSelect(state, column, index)) {
      setSelected({ column, index });
    } else {
      setSelected(null);
    }
  };
  const undo = () => {
    const result = undoSpider(state);
    if (result.changed) {
      setSelected(null);
      onState(result.state, result.metrics, result.terminal);
    }
  };
  const deal = () => {
    const result = dealSpider(state);
    if (result.changed) {
      setSelected(null);
      onState(result.state, result.metrics, result.terminal);
    }
  };
  useEffect(() => {
    const handle = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "z") {
        event.preventDefault();
        undo();
      }
    };
    window.addEventListener("keydown", handle);
    return () => window.removeEventListener("keydown", handle);
  });
  return (
    <div className="game-stage spider-stage">
      <div className="game-score-row">
        <strong>{tr(language, "分数", "Score")} {state.score}</strong>
        <span>{tr(language, "完成组", "Completed runs")} {state.completed}/8</span>
        <span>{tr(language, "步数", "Moves")} {state.moves}</span>
        <button className="button button-secondary" type="button" disabled={!state.history.length} onClick={undo}><RotateCcw size={17} />{tr(language, "撤回", "Undo")}</button>
        <button className="button button-primary" type="button" disabled={state.stock.length < 10 || state.columns.some((column) => !column.length)} onClick={deal}><Layers3 size={17} />{tr(language, `发牌 ${state.stock.length / 10}`, `Deal ${state.stock.length / 10}`)}</button>
      </div>
      <div className="spider-table" role="group" aria-label={tr(language, "蜘蛛纸牌桌面", "Spider tableau")}>
        {state.columns.map((column, columnIndex) => (
          <div className="spider-column" key={columnIndex}>
            <button className="spider-column-target" type="button" onClick={() => selected && choose(columnIndex, Math.max(0, column.length - 1))} aria-label={tr(language, `移动到第 ${columnIndex + 1} 列`, `Move to column ${columnIndex + 1}`)} />
            {column.map((card, cardIndex) => (
              <button
                className="spider-card"
                type="button"
                data-face-up={card.faceUp}
                data-selected={selected?.column === columnIndex && selected.index === cardIndex}
                style={{ "--card-index": cardIndex } as CSSProperties}
                key={card.id}
                onClick={() => choose(columnIndex, cardIndex)}
                aria-label={card.faceUp ? `${card.rank} ${tr(language, "黑桃", "spades")}` : tr(language, "背面牌", "Face-down card")}
              >
                {card.faceUp ? <><span>{card.rank === 1 ? "A" : card.rank === 11 ? "J" : card.rank === 12 ? "Q" : card.rank === 13 ? "K" : card.rank}</span><Spade size={13} /></> : null}
              </button>
            ))}
          </div>
        ))}
      </div>
      {state.completed === 8 ? <p className="game-result is-win">{tr(language, "恭喜完成本局！", "Round complete!")}</p> : null}
    </div>
  );
}

export default function GamesPage() {
  const [searchParams] = useSearchParams();
  const language = useAppStore((state) => state.appearance.language);
  const [snapshot, setSnapshot] = useState<GamesSnapshotV1 | null>(null);
  const [active, setActive] = useState<ActiveGame | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [playing, setPlaying] = useState(true);
  const writeTail = useRef<Promise<void>>(Promise.resolve());
  const handledDeepLink = useRef<string | null>(null);

  const enqueue = useCallback((task: () => Promise<GamesSnapshotV1>) => {
    const run = writeTail.current.then(task, task).then((next) => {
      setSnapshot(next);
    });
    writeTail.current = run.catch((reason) => {
      setError(readableError(reason, language));
    });
  }, [language]);

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setSnapshot(await gameApi.snapshot());
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setLoading(false);
    }
  }, [language]);

  useEffect(() => { void load(); }, [load]);

  const persist = useCallback((next: ActiveGame, metrics?: GameMetricDelta, terminal = false) => {
    const saveJson = serializeGame(next.id, next.state);
    enqueue(() => gameApi.applyAction({
      gameId: next.id,
      saveMode: terminal ? "finish" : "save",
      saveJson: terminal ? null : saveJson,
      score: scoreOf(next),
      metrics: filteredMetrics(metrics),
    }));
  }, [enqueue]);

  const launch = useCallback((gameId: GameId, resume: boolean) => {
    const saved = gameState(snapshot, gameId)?.saveJson ?? null;
    const restored = resume && saved ? restoreGame(gameId, saved) : null;
    const next = restored ?? createGame(gameId);
    const metrics = !resume && gameId === "spider" && saved
      ? abandonSpiderMetrics(parseSpider(saved))
      : undefined;
    setActive(next);
    setPlaying(true);
    persist(next, metrics, false);
  }, [persist, snapshot]);

  const requestedGame = useMemo(() => {
    const candidate = searchParams.get("game");
    return GAME_IDS.find((gameId) => gameId === candidate) ?? null;
  }, [searchParams]);

  useEffect(() => {
    if (!snapshot || !requestedGame || handledDeepLink.current === requestedGame) return;
    handledDeepLink.current = requestedGame;
    launch(requestedGame, Boolean(gameState(snapshot, requestedGame)?.saveJson));
  }, [launch, requestedGame, snapshot]);

  const update = useCallback((next: ActiveGame, metrics?: GameMetricDelta, terminal = false) => {
    setActive(next);
    persist(next, metrics, terminal);
  }, [persist]);

  const activeGameId = active?.id ?? null;
  useEffect(() => {
    if (!activeGameId || !playing) return;
    let started = performance.now();
    const checkpoint = () => {
      const now = performance.now();
      const elapsed = Math.trunc(now - started);
      started = now;
      if (elapsed > 0) enqueue(() => gameApi.addPlayTime(activeGameId, elapsed));
    };
    const visibility = () => {
      if (document.visibilityState === "hidden") checkpoint();
      else started = performance.now();
    };
    const timer = window.setInterval(checkpoint, 30_000);
    document.addEventListener("visibilitychange", visibility);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", visibility);
      checkpoint();
    };
  }, [activeGameId, enqueue, playing]);

  if (loading && !snapshot) {
    return <PageFrame title={tr(language, "小游戏", "Mini games")}><div className="panel"><LoadingState /></div></PageFrame>;
  }
  if (!snapshot) {
    return <PageFrame title={tr(language, "小游戏", "Mini games")}><div className="panel"><ErrorState description={error} retry={() => void load()} /></div></PageFrame>;
  }

  if (!active) {
    return (
      <PageFrame title={tr(language, "小游戏", "Mini games")} description={tr(language, "七种玩法共享 Android v27 存档与特色战绩；游玩时长只保存在这台电脑。", "Seven variants share Android v27 saves and lifetime metrics; play time stays private to this PC.")}>
        {error ? <p className="form-error" role="alert">{error}</p> : null}
        <GameCatalog snapshot={snapshot} language={language} onLaunch={launch} />
      </PageFrame>
    );
  }

  const meta = gameState(snapshot, active.id);
  return (
    <PageFrame
      className={active.id === "spider" ? "game-page is-wide" : "game-page"}
      eyebrow={tr(language, "小游戏", "Mini games")}
      title={gameTitle(active.id, language)}
      description={tr(language, `最高分 ${meta?.highScore ?? 0} · 自动顺序保存`, `High score ${meta?.highScore ?? 0} · Ordered autosave`)}
      actions={<button className="button button-secondary" type="button" onClick={() => setActive(null)}><Save size={17} />{tr(language, "保存并返回", "Save & back")}</button>}
    >
      {error ? <p className="form-error" role="alert">{error}</p> : null}
      {active.id === "2048" || active.id === "2048_5" || active.id === "2048_6" ? (
        <Game2048Board
          state={active.state}
          language={language}
          onMove={(direction) => {
            const result = move2048(active.state, direction);
            if (result.changed) update({ id: active.id, state: result.state }, result.metrics, result.terminal);
          }}
          onUndo={() => {
            const next = undo2048(active.state);
            if (next) update({ id: active.id, state: next });
          }}
        />
      ) : active.id === "snake" ? (
        <SnakeBoard state={active.state} language={language} onPlaying={setPlaying} onState={(state, metrics, terminal) => update({ id: "snake", state }, metrics, terminal)} />
      ) : active.id === "tetris" ? (
        <TetrisBoard state={active.state} language={language} onPlaying={setPlaying} onState={(state, metrics, terminal) => update({ id: "tetris", state }, metrics, terminal)} />
      ) : active.id === "minesweeper" ? (
        <MinesBoard state={active.state} language={language} onState={(state, metrics, terminal) => update({ id: "minesweeper", state }, metrics, terminal)} onReplace={(state) => update({ id: "minesweeper", state })} />
      ) : active.id === "spider" ? (
        <SpiderBoard state={active.state} language={language} onState={(state, metrics, terminal) => update({ id: "spider", state }, metrics, terminal)} />
      ) : null}
      <footer className="game-keyboard-hint"><Trophy size={16} />{tr(language, "支持方向键 / WASD；俄罗斯方块空格硬降，P 暂停；蜘蛛纸牌 Ctrl+Z 撤回。", "Arrow keys / WASD supported; Space hard-drops Tetris, P pauses, and Ctrl+Z undoes Spider.")}</footer>
    </PageFrame>
  );
}
