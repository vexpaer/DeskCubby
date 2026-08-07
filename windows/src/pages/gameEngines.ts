import type { GameId, GameMetricDelta } from "../lib/gameApi";

export interface EngineAction<T> {
  state: T;
  changed: boolean;
  terminal: boolean;
  metrics?: GameMetricDelta;
}

function randomIndex(length: number): number {
  return Math.floor(Math.random() * length);
}

function shuffled<T>(values: T[]): T[] {
  const result = [...values];
  for (let index = result.length - 1; index > 0; index -= 1) {
    const other = randomIndex(index + 1);
    [result[index], result[other]] = [result[other], result[index]];
  }
  return result;
}

function parseObject(json: string): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(json);
    return typeof value === "object" && value !== null && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

function isIntegerArray(value: unknown, length?: number): value is number[] {
  return (
    Array.isArray(value) &&
    (length === undefined || value.length === length) &&
    value.every((item) => Number.isSafeInteger(item))
  );
}

function isBoolean(value: unknown): value is boolean {
  return typeof value === "boolean";
}

// ---------------------------------------------------------------------------
// 2048 — JSON-compatible with Android Game2048.toJson().

export type Game2048Direction = "UP" | "DOWN" | "LEFT" | "RIGHT";

export interface Game2048UndoState {
  cells: number[];
  score: number;
}

export interface Game2048State {
  size: number;
  cells: number[];
  score: number;
  undoHistory: Game2048UndoState[];
  winRecorded: boolean;
  lossRecorded: boolean;
}

function has2048Move(cells: number[], size: number): boolean {
  for (let index = 0; index < cells.length; index += 1) {
    if (cells[index] === 0) return true;
    const row = Math.floor(index / size);
    const column = index % size;
    if (column + 1 < size && cells[index] === cells[index + 1]) return true;
    if (row + 1 < size && cells[index] === cells[index + size]) return true;
  }
  return false;
}

function spawn2048(cells: number[]): void {
  const empty = cells.flatMap((value, index) => (value === 0 ? [index] : []));
  if (empty.length === 0) return;
  cells[empty[randomIndex(empty.length)]] = Math.random() < 0.9 ? 2 : 4;
}

export function newGame2048(size: 4 | 5 | 6): Game2048State {
  const cells = Array<number>(size * size).fill(0);
  spawn2048(cells);
  spawn2048(cells);
  return {
    size,
    cells,
    score: 0,
    undoHistory: [],
    winRecorded: false,
    lossRecorded: false,
  };
}

export function parseGame2048(
  json: string,
  expectedSize: 4 | 5 | 6,
): Game2048State | null {
  const value = parseObject(json);
  if (!value) return null;
  const size = value.size ?? 4;
  if (size !== expectedSize || !Number.isSafeInteger(size)) return null;
  if (
    !isIntegerArray(value.cells, expectedSize * expectedSize) ||
    value.cells.some((cell) => cell < 0) ||
    !Number.isSafeInteger(value.score) ||
    (value.score as number) < 0
  ) {
    return null;
  }
  const historyValue = value.undoHistory;
  const undoHistory: Game2048UndoState[] = [];
  if (historyValue !== undefined) {
    if (!Array.isArray(historyValue)) return null;
    for (const entry of historyValue) {
      if (typeof entry !== "object" || entry === null || Array.isArray(entry)) return null;
      const item = entry as Record<string, unknown>;
      if (
        !isIntegerArray(item.cells, expectedSize * expectedSize) ||
        item.cells.some((cell) => cell < 0) ||
        !Number.isSafeInteger(item.score) ||
        (item.score as number) < 0
      ) {
        return null;
      }
      undoHistory.push({ cells: [...item.cells], score: item.score as number });
    }
  } else if (value.undoCells !== undefined || value.undoScore !== undefined) {
    if (
      !isIntegerArray(value.undoCells, expectedSize * expectedSize) ||
      !Number.isSafeInteger(value.undoScore)
    ) {
      return null;
    }
    undoHistory.push({ cells: [...value.undoCells], score: value.undoScore as number });
  }
  const cells = [...value.cells];
  const winRecorded =
    value.winRecorded === undefined
      ? Math.max(...cells, 0) >= 2048
      : isBoolean(value.winRecorded)
        ? value.winRecorded
        : null;
  const lossRecorded =
    value.lossRecorded === undefined
      ? !has2048Move(cells, expectedSize)
      : isBoolean(value.lossRecorded)
        ? value.lossRecorded
        : null;
  if (winRecorded === null || lossRecorded === null) return null;
  return {
    size: expectedSize,
    cells,
    score: value.score as number,
    undoHistory,
    winRecorded,
    lossRecorded,
  };
}

function line2048(size: number, direction: Game2048Direction, line: number): number[] {
  return Array.from({ length: size }, (_, offset) => {
    switch (direction) {
      case "LEFT":
        return line * size + offset;
      case "RIGHT":
        return line * size + (size - 1 - offset);
      case "UP":
        return offset * size + line;
      case "DOWN":
        return (size - 1 - offset) * size + line;
    }
  });
}

export function move2048(
  state: Game2048State,
  direction: Game2048Direction,
): EngineAction<Game2048State> {
  const next = Array<number>(state.cells.length).fill(0);
  let gained = 0;
  let merges = 0;
  for (let line = 0; line < state.size; line += 1) {
    const indices = line2048(state.size, direction, line);
    const values = indices.map((index) => state.cells[index]).filter((value) => value !== 0);
    let source = 0;
    let destination = 0;
    while (source < values.length) {
      const first = values[source];
      const second = values[source + 1];
      if (second !== undefined && first === second) {
        const merged = first * 2;
        next[indices[destination]] = merged;
        gained += merged;
        merges += 1;
        source += 2;
      } else {
        next[indices[destination]] = first;
        source += 1;
      }
      destination += 1;
    }
  }
  if (next.every((value, index) => value === state.cells[index])) {
    return { state, changed: false, terminal: !has2048Move(state.cells, state.size) };
  }
  const before = { cells: [...state.cells], score: state.score };
  spawn2048(next);
  const highestTile = Math.max(...next, 0);
  const wins = !state.winRecorded && highestTile >= 2048 ? 1 : 0;
  const terminal = !has2048Move(next, state.size);
  const losses = !state.lossRecorded && terminal ? 1 : 0;
  return {
    changed: true,
    terminal,
    state: {
      ...state,
      cells: next,
      score: state.score + gained,
      undoHistory: [...state.undoHistory, before],
      winRecorded: state.winRecorded || wins === 1,
      lossRecorded: state.lossRecorded || losses === 1,
    },
    metrics: {
      increments: { effectiveMoves: 1, merges, wins, losses },
      maxima: { highestTile },
    },
  };
}

export function undo2048(state: Game2048State): Game2048State | null {
  const previous = state.undoHistory.at(-1);
  if (!previous) return null;
  return {
    ...state,
    cells: [...previous.cells],
    score: previous.score,
    undoHistory: state.undoHistory.slice(0, -1),
  };
}

// ---------------------------------------------------------------------------
// Snake — JSON-compatible with Android SnakeGame.toJson().

export type SnakeDirection = "UP" | "DOWN" | "LEFT" | "RIGHT";
export type GridPoint = [number, number];

export interface SnakeState {
  w: number;
  h: number;
  snake: GridPoint[];
  dir: SnakeDirection;
  /** Runtime-only guard matching Android's last direction actually used by a tick. */
  movedDir: SnakeDirection;
  food: GridPoint;
  score: number;
  over: boolean;
}

const directionVector: Record<SnakeDirection, GridPoint> = {
  UP: [0, -1],
  DOWN: [0, 1],
  LEFT: [-1, 0],
  RIGHT: [1, 0],
};
const oppositeDirection: Record<SnakeDirection, SnakeDirection> = {
  UP: "DOWN",
  DOWN: "UP",
  LEFT: "RIGHT",
  RIGHT: "LEFT",
};

function snakeFood(w: number, h: number, body: GridPoint[]): GridPoint | null {
  const occupied = new Set(body.map(([x, y]) => `${x}:${y}`));
  const empty: GridPoint[] = [];
  for (let y = 0; y < h; y += 1) {
    for (let x = 0; x < w; x += 1) {
      if (!occupied.has(`${x}:${y}`)) empty.push([x, y]);
    }
  }
  return empty.length ? empty[randomIndex(empty.length)] : null;
}

export function newSnake(w = 16, h = 16): SnakeState {
  const centerX = Math.floor(w / 2);
  const centerY = Math.floor(h / 2);
  const snake: GridPoint[] = Array.from({ length: Math.min(3, centerX + 1) }, (_, index) => [
    centerX - index,
    centerY,
  ]);
  return {
    w,
    h,
    snake,
    dir: "RIGHT",
    movedDir: "RIGHT",
    food: snakeFood(w, h, snake) ?? [0, 0],
    score: 0,
    over: false,
  };
}

function point(value: unknown): value is GridPoint {
  return (
    isIntegerArray(value, 2) &&
    Number.isSafeInteger(value[0]) &&
    Number.isSafeInteger(value[1])
  );
}

export function parseSnake(json: string): SnakeState | null {
  const value = parseObject(json);
  if (!value) return null;
  const w = value.w;
  const h = value.h;
  if (!Number.isSafeInteger(w) || !Number.isSafeInteger(h) || (w as number) < 4 || (h as number) < 4)
    return null;
  if (!Array.isArray(value.snake) || value.snake.length === 0 || !value.snake.every(point))
    return null;
  if (!point(value.food) || !["UP", "DOWN", "LEFT", "RIGHT"].includes(value.dir as string))
    return null;
  if (!Number.isSafeInteger(value.score) || (value.score as number) < 0 || !isBoolean(value.over))
    return null;
  const width = w as number;
  const height = h as number;
  const body = value.snake as GridPoint[];
  if (
    body.length > width * height ||
    [...body, value.food as GridPoint].some(
      ([x, y]) => x < 0 || x >= width || y < 0 || y >= height,
    ) ||
    new Set(body.map(([x, y]) => `${x}:${y}`)).size !== body.length
  ) {
    return null;
  }
  return {
    w: width,
    h: height,
    snake: body.map(([x, y]) => [x, y]),
    dir: value.dir as SnakeDirection,
    movedDir: value.dir as SnakeDirection,
    food: [...(value.food as GridPoint)],
    score: value.score as number,
    over: value.over,
  };
}

export function turnSnake(state: SnakeState, direction: SnakeDirection): SnakeState {
  if (
    state.over ||
    (state.snake.length > 1 && oppositeDirection[state.movedDir] === direction)
  ) {
    return state;
  }
  return { ...state, dir: direction };
}

export function tickSnake(state: SnakeState): EngineAction<SnakeState> {
  if (state.over) return { state, changed: false, terminal: true };
  const [dx, dy] = directionVector[state.dir];
  const [headX, headY] = state.snake[0];
  const next: GridPoint = [headX + dx, headY + dy];
  const wall = next[0] < 0 || next[0] >= state.w || next[1] < 0 || next[1] >= state.h;
  const growing = next[0] === state.food[0] && next[1] === state.food[1];
  const blockingBody = growing ? state.snake : state.snake.slice(0, -1);
  const self = blockingBody.some(([x, y]) => x === next[0] && y === next[1]);
  if (wall || self) {
    return {
      state: { ...state, movedDir: state.dir, over: true },
      changed: true,
      terminal: true,
      metrics: {
        increments: { losses: 1 },
        maxima: { maxLength: state.snake.length },
      },
    };
  }
  const body = [next, ...state.snake];
  if (!growing) body.pop();
  const food = growing ? snakeFood(state.w, state.h, body) : state.food;
  const terminal = food === null;
  return {
    state: {
      ...state,
      snake: body,
      movedDir: state.dir,
      food: food ?? state.food,
      score: state.score + (growing ? 10 : 0),
      over: terminal,
    },
    changed: true,
    terminal,
    metrics: growing
      ? { increments: { foodEaten: 1 }, maxima: { maxLength: body.length } }
      : undefined,
  };
}

// ---------------------------------------------------------------------------
// Tetris — JSON-compatible with Android TetrisGame.toJson().

export interface TetrisState {
  board: number[];
  score: number;
  lines: number;
  type: number;
  rot: number;
  x: number;
  y: number;
  next: number;
  over: boolean;
}

export type TetrisAction = "left" | "right" | "rotate" | "soft" | "hard" | "tick";
const TETRIS_WIDTH = 10;
const TETRIS_HEIGHT = 20;
const BOX_SIZES = [4, 2, 3, 3, 3, 3, 3];
const BASE_CELLS: GridPoint[][] = [
  [[0, 1], [1, 1], [2, 1], [3, 1]],
  [[0, 0], [1, 0], [0, 1], [1, 1]],
  [[1, 0], [0, 1], [1, 1], [2, 1]],
  [[1, 0], [2, 0], [0, 1], [1, 1]],
  [[0, 0], [1, 0], [1, 1], [2, 1]],
  [[0, 0], [0, 1], [1, 1], [2, 1]],
  [[2, 0], [0, 1], [1, 1], [2, 1]],
];
const TETRIS_SHAPES: GridPoint[][][] = BASE_CELLS.map((base, type) => {
  const rotations: GridPoint[][] = [];
  let current = base.map(([x, y]) => [x, y] as GridPoint);
  for (let rotation = 0; rotation < 4; rotation += 1) {
    rotations.push(current);
    current = current
      .map(([x, y]) => [BOX_SIZES[type] - 1 - y, x] as GridPoint)
      .sort((left, right) => left[1] - right[1] || left[0] - right[0]);
  }
  return rotations;
});

function tetrisCollision(state: TetrisState, x: number, y: number, rotation: number): boolean {
  return TETRIS_SHAPES[state.type][rotation].some(([offsetX, offsetY]) => {
    const cellX = x + offsetX;
    const cellY = y + offsetY;
    return (
      cellX < 0 ||
      cellX >= TETRIS_WIDTH ||
      cellY < 0 ||
      cellY >= TETRIS_HEIGHT ||
      state.board[cellY * TETRIS_WIDTH + cellX] !== 0
    );
  });
}

function spawnTetris(state: TetrisState, type: number): TetrisState {
  const next = {
    ...state,
    type,
    rot: 0,
    x: Math.floor((TETRIS_WIDTH - BOX_SIZES[type]) / 2),
    y: 0,
  };
  return { ...next, over: tetrisCollision(next, next.x, next.y, 0) };
}

export function newTetris(): TetrisState {
  const type = randomIndex(7);
  return spawnTetris(
    {
      board: Array<number>(TETRIS_WIDTH * TETRIS_HEIGHT).fill(0),
      score: 0,
      lines: 0,
      type,
      rot: 0,
      x: 0,
      y: 0,
      next: randomIndex(7),
      over: false,
    },
    type,
  );
}

export function parseTetris(json: string): TetrisState | null {
  const value = parseObject(json);
  if (!value || !isIntegerArray(value.board, TETRIS_WIDTH * TETRIS_HEIGHT)) return null;
  const integerKeys = ["score", "lines", "type", "rot", "x", "y", "next"] as const;
  if (integerKeys.some((key) => !Number.isSafeInteger(value[key])) || !isBoolean(value.over))
    return null;
  const state: TetrisState = {
    board: [...value.board],
    score: value.score as number,
    lines: value.lines as number,
    type: value.type as number,
    rot: value.rot as number,
    x: value.x as number,
    y: value.y as number,
    next: value.next as number,
    over: value.over,
  };
  if (
    state.board.some((cell) => cell < 0 || cell > 7) ||
    state.score < 0 ||
    state.lines < 0 ||
    state.type < 0 ||
    state.type >= 7 ||
    state.next < 0 ||
    state.next >= 7 ||
    state.rot < 0 ||
    state.rot >= 4 ||
    (!state.over && tetrisCollision(state, state.x, state.y, state.rot))
  ) {
    return null;
  }
  return state;
}

export function tetrisPieceCells(state: TetrisState): GridPoint[] {
  return TETRIS_SHAPES[state.type][state.rot].map(([x, y]) => [state.x + x, state.y + y]);
}

function lockTetris(state: TetrisState): EngineAction<TetrisState> {
  const board = [...state.board];
  for (const [x, y] of tetrisPieceCells(state)) board[y * TETRIS_WIDTH + x] = state.type + 1;
  const rows: number[][] = [];
  let cleared = 0;
  for (let y = TETRIS_HEIGHT - 1; y >= 0; y -= 1) {
    const row = board.slice(y * TETRIS_WIDTH, (y + 1) * TETRIS_WIDTH);
    if (row.every((cell) => cell !== 0)) cleared += 1;
    else rows.unshift(row);
  }
  while (rows.length < TETRIS_HEIGHT) rows.unshift(Array<number>(TETRIS_WIDTH).fill(0));
  const scoreValues = [0, 100, 300, 500, 800];
  let next = spawnTetris(
    {
      ...state,
      board: rows.flat(),
      score: state.score + scoreValues[cleared],
      lines: state.lines + cleared,
    },
    state.next,
  );
  next = { ...next, next: randomIndex(7) };
  return {
    state: next,
    changed: true,
    terminal: next.over,
    metrics: {
      increments: {
        piecesLocked: 1,
        linesCleared: cleared,
        tetrises: cleared === 4 ? 1 : 0,
        losses: next.over ? 1 : 0,
      },
    },
  };
}

export function actTetris(
  state: TetrisState,
  action: TetrisAction,
): EngineAction<TetrisState> {
  if (state.over) return { state, changed: false, terminal: true };
  const moved = (x: number, y: number, rotation: number): TetrisState | null =>
    tetrisCollision(state, x, y, rotation) ? null : { ...state, x, y, rot: rotation };
  if (action === "left") {
    const next = moved(state.x - 1, state.y, state.rot);
    return { state: next ?? state, changed: next !== null, terminal: false };
  }
  if (action === "right") {
    const next = moved(state.x + 1, state.y, state.rot);
    return { state: next ?? state, changed: next !== null, terminal: false };
  }
  if (action === "rotate") {
    const next = moved(state.x, state.y, (state.rot + 1) % 4);
    return { state: next ?? state, changed: next !== null, terminal: false };
  }
  if (action === "hard") {
    let next = state;
    while (!tetrisCollision(next, next.x, next.y + 1, next.rot)) {
      next = { ...next, y: next.y + 1 };
    }
    return lockTetris(next);
  }
  const down = moved(state.x, state.y + 1, state.rot);
  return down
    ? { state: down, changed: true, terminal: false }
    : lockTetris(state);
}

// ---------------------------------------------------------------------------
// Minesweeper — JSON-compatible with Android MinesweeperGame.toJson().

export interface MinesState {
  w: number;
  h: number;
  count: number;
  initialized: boolean;
  over: boolean;
  won: boolean;
  mines: number[];
  revealed: number[];
  flagged: number[];
}

export type MinesAction = "reveal" | "chord" | "flag";

export function newMines(w = 9, h = 9, count = 10): MinesState {
  if (w < 6 || w > 30 || h < 6 || h > 30 || count < 1 || count >= w * h) {
    throw new Error("invalid minesweeper dimensions");
  }
  return { w, h, count, initialized: false, over: false, won: false, mines: [], revealed: [], flagged: [] };
}

export function parseMines(json: string): MinesState | null {
  const value = parseObject(json);
  if (!value) return null;
  const integerKeys = ["w", "h", "count"] as const;
  if (integerKeys.some((key) => !Number.isSafeInteger(value[key]))) return null;
  if (!["initialized", "over", "won"].every((key) => isBoolean(value[key]))) return null;
  if (!["mines", "revealed", "flagged"].every((key) => isIntegerArray(value[key]))) return null;
  const state: MinesState = {
    w: value.w as number,
    h: value.h as number,
    count: value.count as number,
    initialized: value.initialized as boolean,
    over: value.over as boolean,
    won: value.won as boolean,
    mines: [...(value.mines as number[])],
    revealed: [...(value.revealed as number[])],
    flagged: [...(value.flagged as number[])],
  };
  const size = state.w * state.h;
  const arrays = [state.mines, state.revealed, state.flagged];
  const mines = new Set(state.mines);
  const flagged = new Set(state.flagged);
  const revealedMine = state.revealed.some((item) => mines.has(item));
  const revealedSafe = state.revealed.filter((item) => !mines.has(item)).length;
  if (
    state.w < 6 || state.w > 30 || state.h < 6 || state.h > 30 ||
    state.count < 1 || state.count >= size ||
    arrays.some((items) => items.some((item) => item < 0 || item >= size) || new Set(items).size !== items.length) ||
    (state.initialized ? state.mines.length !== state.count : state.mines.length !== 0) ||
    state.revealed.some((item) => flagged.has(item)) ||
    ((state.over || state.won) && !state.initialized) ||
    (state.over && state.won) ||
    state.over !== revealedMine ||
    state.flagged.length > state.count ||
    (state.won && revealedSafe !== size - state.count) ||
    (!state.won && !state.over && revealedSafe === size - state.count)
  ) return null;
  return state;
}

function mineNeighbors(state: MinesState, index: number): number[] {
  const x = index % state.w;
  const y = Math.floor(index / state.w);
  const result: number[] = [];
  for (let dy = -1; dy <= 1; dy += 1) {
    for (let dx = -1; dx <= 1; dx += 1) {
      if (dx === 0 && dy === 0) continue;
      const nextX = x + dx;
      const nextY = y + dy;
      if (nextX >= 0 && nextX < state.w && nextY >= 0 && nextY < state.h)
        result.push(nextY * state.w + nextX);
    }
  }
  return result;
}

function initializeMines(state: MinesState, first: number): MinesState {
  const broad = new Set([first, ...mineNeighbors(state, first)]);
  const excluded = state.w * state.h - broad.size >= state.count ? broad : new Set([first]);
  const candidates = shuffled(
    Array.from({ length: state.w * state.h }, (_, index) => index).filter((index) => !excluded.has(index)),
  );
  return { ...state, initialized: true, mines: candidates.slice(0, state.count).sort((a, b) => a - b) };
}

function floodMines(state: MinesState, starts: number[]): { revealed: Set<number>; count: number } {
  const mines = new Set(state.mines);
  const flags = new Set(state.flagged);
  const revealed = new Set(state.revealed);
  const queue = [...starts];
  let count = 0;
  while (queue.length) {
    const current = queue.shift()!;
    if (revealed.has(current) || flags.has(current) || mines.has(current)) continue;
    revealed.add(current);
    count += 1;
    if (mineNeighbors(state, current).filter((index) => mines.has(index)).length === 0)
      queue.push(...mineNeighbors(state, current));
  }
  return { revealed, count };
}

export function minesCell(state: MinesState, index: number) {
  const revealed = state.revealed.includes(index);
  const mine = state.mines.includes(index);
  return {
    revealed,
    flagged: state.flagged.includes(index),
    mine: mine && (state.over || state.won || revealed),
    adjacent: revealed && !mine
      ? mineNeighbors(state, index).filter((neighbor) => state.mines.includes(neighbor)).length
      : 0,
  };
}

export function actMines(
  input: MinesState,
  index: number,
  action: MinesAction,
): EngineAction<MinesState> {
  if (input.over || input.won || index < 0 || index >= input.w * input.h)
    return { state: input, changed: false, terminal: input.over || input.won };
  if (action === "flag") {
    if (input.revealed.includes(index)) return { state: input, changed: false, terminal: false };
    const flagged = new Set(input.flagged);
    const placing = !flagged.has(index);
    if (placing && flagged.size >= input.count) return { state: input, changed: false, terminal: false };
    if (placing) flagged.add(index); else flagged.delete(index);
    return {
      state: { ...input, flagged: [...flagged].sort((a, b) => a - b) },
      changed: true,
      terminal: false,
      metrics: placing ? { increments: { flagsPlaced: 1 } } : undefined,
    };
  }
  let state = input.initialized ? input : initializeMines(input, index);
  const mines = new Set(state.mines);
  const flags = new Set(state.flagged);
  const revealed = new Set(state.revealed);
  let targets: number[];
  if (action === "chord") {
    if (!revealed.has(index) || mines.has(index) || mineNeighbors(state, index).filter((item) => mines.has(item)).length === 0)
      return { state: input, changed: false, terminal: false };
    targets = mineNeighbors(state, index).filter((item) => !revealed.has(item) && !flags.has(item));
  } else {
    if (flags.has(index) || revealed.has(index)) return { state: input, changed: false, terminal: false };
    targets = [index];
  }
  if (!targets.length) return { state: input, changed: false, terminal: false };
  const before = revealed.size;
  let loss = false;
  for (const target of targets) {
    if (mines.has(target)) {
      revealed.add(target);
      loss = true;
    } else {
      const flooded = floodMines({ ...state, revealed: [...revealed] }, [target]);
      flooded.revealed.forEach((item) => revealed.add(item));
    }
  }
  const safeCount = [...revealed].filter((item) => !mines.has(item)).length;
  const won = !loss && safeCount === state.w * state.h - state.count;
  const flagged = won ? [...mines] : state.flagged;
  const newlyRevealed = revealed.size - before;
  state = {
    ...state,
    over: loss,
    won,
    revealed: [...revealed].sort((a, b) => a - b),
    flagged: [...flagged].sort((a, b) => a - b),
  };
  return {
    state,
    changed: newlyRevealed > 0,
    terminal: loss || won,
    metrics: {
      increments: {
        minesCellsRevealed: newlyRevealed,
        minesSwept: won ? state.count : 0,
        wins: won ? 1 : 0,
        losses: loss ? 1 : 0,
      },
    },
  };
}

// ---------------------------------------------------------------------------
// One-suit Spider Solitaire — Android schemaVersion 2 save format.

export interface SpiderCard {
  id: number;
  rank: number;
  suit: number;
  faceUp: boolean;
}

export interface SpiderSnapshot {
  columns: SpiderCard[][];
  stock: SpiderCard[];
  completed: number;
  score: number;
  moves: number;
}

export interface SpiderState extends SpiderSnapshot {
  schemaVersion: 2;
  hasPlayedAction: boolean;
  outcomeRecorded: boolean;
  history: SpiderSnapshot[];
}

function cloneCards(cards: SpiderCard[]): SpiderCard[] {
  return cards.map((card) => ({ ...card }));
}

function spiderSnapshot(state: SpiderState): SpiderSnapshot {
  return {
    columns: state.columns.map(cloneCards),
    stock: cloneCards(state.stock),
    completed: state.completed,
    score: state.score,
    moves: state.moves,
  };
}

export function newSpider(): SpiderState {
  const deck = shuffled(
    Array.from({ length: 104 }, (_, id): SpiderCard => ({
      id,
      rank: (id % 13) + 1,
      suit: 0,
      faceUp: false,
    })),
  );
  const columns = Array.from({ length: 10 }, () => [] as SpiderCard[]);
  for (let index = 0; index < 54; index += 1) columns[index % 10].push({ ...deck[index] });
  columns.forEach((column) => { column[column.length - 1].faceUp = true; });
  return {
    schemaVersion: 2,
    columns,
    stock: deck.slice(54).map((card) => ({ ...card, faceUp: false })),
    completed: 0,
    score: 500,
    moves: 0,
    hasPlayedAction: false,
    outcomeRecorded: false,
    history: [],
  };
}

function parseSpiderCard(value: unknown): SpiderCard | null {
  if (!Array.isArray(value) || value.length !== 4) return null;
  const [id, rank, suit, faceUp] = value;
  if (!Number.isSafeInteger(id) || !Number.isSafeInteger(rank) || suit !== 0 || !isBoolean(faceUp))
    return null;
  if ((id as number) < 0 || (id as number) >= 104 || rank !== ((id as number) % 13) + 1)
    return null;
  return { id: id as number, rank: rank as number, suit: 0, faceUp };
}

function parseSpiderCards(value: unknown): SpiderCard[] | null {
  if (!Array.isArray(value) || value.length > 104) return null;
  const cards = value.map(parseSpiderCard);
  return cards.every((card): card is SpiderCard => card !== null) ? cards : null;
}

function parseSpiderSnapshot(value: unknown): SpiderSnapshot | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  const object = value as Record<string, unknown>;
  if (!Array.isArray(object.columns) || object.columns.length !== 10) return null;
  const columns = object.columns.map(parseSpiderCards);
  const stock = parseSpiderCards(object.stock);
  if (columns.some((column) => column === null) || !stock) return null;
  if (!["completed", "score", "moves"].every((key) => Number.isSafeInteger(object[key]))) return null;
  const snapshot: SpiderSnapshot = {
    columns: columns as SpiderCard[][],
    stock,
    completed: object.completed as number,
    score: object.score as number,
    moves: object.moves as number,
  };
  const all = [...snapshot.columns.flat(), ...snapshot.stock];
  if (
    snapshot.completed < 0 || snapshot.completed > 8 || snapshot.score < 0 || snapshot.moves < 0 ||
    snapshot.stock.length % 10 !== 0 || snapshot.stock.some((card) => card.faceUp) ||
    new Set(all.map((card) => card.id)).size !== all.length ||
    all.length + snapshot.completed * 13 !== 104
  ) return null;
  return snapshot;
}

export function parseSpider(json: string): SpiderState | null {
  const value = parseObject(json);
  if (!value || (value.schemaVersion ?? 1) !== 2) return null;
  const current = parseSpiderSnapshot(value);
  if (!current || !isBoolean(value.hasPlayedAction) || !isBoolean(value.outcomeRecorded)) return null;
  if (!Array.isArray(value.history) || value.history.length > 100) return null;
  const history = value.history.map(parseSpiderSnapshot);
  if (history.some((snapshot) => snapshot === null)) return null;
  return {
    schemaVersion: 2,
    ...current,
    hasPlayedAction: value.hasPlayedAction,
    outcomeRecorded: value.outcomeRecorded,
    history: history as SpiderSnapshot[],
  };
}

export function spiderJson(state: SpiderState): string {
  const cards = (items: SpiderCard[]) => items.map((card) => [card.id, card.rank, card.suit, card.faceUp]);
  const snapshot = (item: SpiderSnapshot) => ({
    columns: item.columns.map(cards), stock: cards(item.stock), completed: item.completed,
    score: item.score, moves: item.moves,
  });
  return JSON.stringify({
    schemaVersion: 2,
    ...snapshot(state),
    hasPlayedAction: state.hasPlayedAction,
    outcomeRecorded: state.outcomeRecorded,
    history: state.history.map(snapshot),
  });
}

export function spiderCanSelect(state: SpiderState, column: number, cardIndex: number): boolean {
  const cards = state.columns[column];
  if (!cards || cardIndex < 0 || cardIndex >= cards.length || !cards[cardIndex].faceUp) return false;
  for (let index = cardIndex; index < cards.length - 1; index += 1) {
    const upper = cards[index];
    const lower = cards[index + 1];
    if (!lower.faceUp || upper.suit !== lower.suit || upper.rank !== lower.rank + 1) return false;
  }
  return true;
}

function removeSpiderRuns(columns: SpiderCard[][], start: number): number {
  let completed = 0;
  const column = columns[start];
  while (column.length >= 13) {
    const tail = column.slice(-13);
    if (
      !tail.every((card) => card.faceUp) || tail[0].rank !== 13 || tail.at(-1)?.rank !== 1 ||
      tail.some((card, index) => index > 0 && tail[index - 1].rank !== card.rank + 1)
    ) break;
    column.splice(-13);
    completed += 1;
    if (column.length) column[column.length - 1].faceUp = true;
  }
  return completed;
}

export function moveSpider(
  state: SpiderState,
  from: number,
  cardIndex: number,
  to: number,
): EngineAction<SpiderState> {
  if (state.completed === 8 || from === to || !spiderCanSelect(state, from, cardIndex))
    return { state, changed: false, terminal: state.completed === 8 };
  const target = state.columns[to];
  const source = state.columns[from];
  if (!target || (target.length && target.at(-1)!.rank !== source[cardIndex].rank + 1))
    return { state, changed: false, terminal: false };
  const columns = state.columns.map(cloneCards);
  const moving = columns[from].splice(cardIndex);
  columns[to].push(...moving);
  if (columns[from].length) columns[from][columns[from].length - 1].faceUp = true;
  const removed = removeSpiderRuns(columns, from) + removeSpiderRuns(columns, to);
  const completed = state.completed + removed;
  const won = completed === 8 && !state.outcomeRecorded;
  return {
    state: {
      ...state,
      columns,
      completed,
      score: Math.max(0, state.score - 1) + removed * 100,
      moves: state.moves + 1,
      hasPlayedAction: true,
      outcomeRecorded: state.outcomeRecorded || won,
      history: [...state.history.slice(-99), spiderSnapshot(state)],
    },
    changed: true,
    terminal: completed === 8,
    metrics: { increments: { spiderCardMoves: 1, wins: won ? 1 : 0 } },
  };
}

export function dealSpider(state: SpiderState): EngineAction<SpiderState> {
  if (state.completed === 8 || state.stock.length < 10 || state.columns.some((column) => !column.length))
    return { state, changed: false, terminal: state.completed === 8 };
  const columns = state.columns.map(cloneCards);
  const stock = cloneCards(state.stock);
  for (let column = 0; column < 10; column += 1)
    columns[column].push({ ...stock.shift()!, faceUp: true });
  let removed = 0;
  for (let column = 0; column < 10; column += 1) removed += removeSpiderRuns(columns, column);
  const completed = state.completed + removed;
  const won = completed === 8 && !state.outcomeRecorded;
  return {
    state: {
      ...state, columns, stock, completed,
      score: Math.max(0, state.score - 1) + removed * 100,
      moves: state.moves + 1, hasPlayedAction: true,
      outcomeRecorded: state.outcomeRecorded || won,
      history: [...state.history.slice(-99), spiderSnapshot(state)],
    },
    changed: true,
    terminal: completed === 8,
    metrics: { increments: { spiderDeals: 1, wins: won ? 1 : 0 } },
  };
}

export function undoSpider(state: SpiderState): EngineAction<SpiderState> {
  const previous = state.history.at(-1);
  if (!previous) return { state, changed: false, terminal: state.completed === 8 };
  return {
    state: {
      schemaVersion: 2,
      ...spiderSnapshot({ ...state, ...previous }),
      hasPlayedAction: state.hasPlayedAction,
      outcomeRecorded: state.outcomeRecorded,
      history: state.history.slice(0, -1),
    },
    changed: true,
    terminal: previous.completed === 8,
    metrics: { increments: { spiderUndos: 1 } },
  };
}

export function abandonSpiderMetrics(state: SpiderState | null): GameMetricDelta | undefined {
  return state?.hasPlayedAction && state.completed !== 8 && !state.outcomeRecorded
    ? { increments: { losses: 1 } }
    : undefined;
}

export function serializeGame(gameId: GameId, state: unknown): string {
  if (gameId === "spider") return spiderJson(state as SpiderState);
  if (gameId === "snake") {
    const snake = state as SnakeState;
    return JSON.stringify({
      w: snake.w,
      h: snake.h,
      snake: snake.snake,
      dir: snake.dir,
      food: snake.food,
      score: snake.score,
      over: snake.over,
    });
  }
  return JSON.stringify(state);
}
