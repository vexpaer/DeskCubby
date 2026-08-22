/**
 * Pure-TypeScript 2048 engine — faithful port of
 * android/app/src/main/java/com/deskcubby/app/games/Game2048.kt.
 *
 * Board sizes 4/5/6, standard merge rules (each pair merges at most once per move),
 * 90%/10% tile spawn, unlimited undo history, win-at-2048 recorded once per round,
 * game-over detection, and byte-compatible JSON save/restore (including the legacy
 * single-snapshot undoCells/undoScore format from v0.3.7).
 *
 * moveAttempts vs effectiveMoves: the engine only ever reports an effective move
 * (statisticsDelta of a successful moveWithResult). Rejected direction inputs return
 * null and stay the caller's responsibility, exactly like the Android ViewModel's
 * record2048MoveAttempt(delta = null) path. Losses are never emitted for new rounds;
 * lossRecorded is kept only for save-format round-trip compatibility.
 */

export type Direction2048 = "UP" | "DOWN" | "LEFT" | "RIGHT";

export interface TileMotion2048 {
  fromIndex: number;
  toIndex: number;
  value: number;
  merged: boolean;
}

export interface Merge2048 {
  toIndex: number;
  value: number;
}

export interface Spawn2048 {
  index: number;
  value: number;
}

/** Aggregate-statistics increments caused by one successful move. */
export interface StatisticsDelta2048 {
  effectiveMoves: number;
  merges: number;
  /** Candidate for a lifetime maximum; consumers should combine it with max(). */
  highestTile: number;
  wins: number;
  losses: number;
}

/**
 * Immutable transition data for one successful move. The engine has already committed
 * `after`, including `spawn`, when this is returned.
 */
export interface MoveResult2048 {
  before: number[];
  after: number[];
  motions: TileMotion2048[];
  merges: Merge2048[];
  spawn: Spawn2048;
  scoreGained: number;
  statisticsDelta: StatisticsDelta2048;
}

interface UndoState2048 {
  cells: number[];
  score: number;
}

export const GAME_2048_SIZE = 4;
export const GAME_2048_MIN_SIZE = 4;
export const GAME_2048_MAX_SIZE = 6;
export const GAME_2048_WIN_TILE = 2048;

export type Random2048 = () => number;

function validatedSize(size: number): number {
  if (!Number.isInteger(size) || size < GAME_2048_MIN_SIZE || size > GAME_2048_MAX_SIZE) {
    throw new Error("2048 board size must be 4, 5, or 6");
  }
  return size;
}

function hasAnyMoveOn(cells: number[], size: number): boolean {
  for (let index = 0; index < cells.length; index++) {
    if (cells[index] === 0) return true;
    const row = Math.floor(index / size);
    const column = index % size;
    if (column + 1 < size && cells[index] === cells[index + 1]) return true;
    if (row + 1 < size && cells[index] === cells[index + size]) return true;
  }
  return false;
}

interface RestoredState2048 {
  cells: number[];
  score: number;
  undoHistory: UndoState2048[];
  winRecorded: boolean;
  lossRecorded: boolean;
}

export class Game2048 {
  readonly size: number;
  score: number;
  private cells: number[];
  private winRecorded: boolean;
  private lossRecorded: boolean;
  private undoHistory: UndoState2048[];
  private random: Random2048;

  /**
   * Starts a fresh game with two spawned tiles. `state` is reserved for
   * {@link Game2048.fromJson}; callers always use the two-argument form.
   */
  constructor(size: number = GAME_2048_SIZE, random: Random2048 = Math.random, state?: RestoredState2048) {
    if (state) {
      this.size = validatedSize(size);
      this.cells = state.cells.slice();
      this.score = state.score;
      this.winRecorded = state.winRecorded;
      this.lossRecorded = state.lossRecorded;
      this.undoHistory = state.undoHistory.map((history) => ({
        cells: history.cells.slice(),
        score: history.score,
      }));
      this.random = random;
      return;
    }
    this.size = validatedSize(size);
    this.cells = new Array<number>(this.size * this.size).fill(0);
    this.score = 0;
    this.winRecorded = false;
    this.lossRecorded = false;
    this.undoHistory = [];
    this.random = random;
    this.spawnTile();
    this.spawnTile();
  }

  /** Row-major snapshot of all tile values (0 = empty). */
  get board(): number[] {
    return this.cells.slice();
  }

  /** Every successful move remains restorable until the player starts a new game. */
  get canUndo(): boolean {
    return this.undoHistory.length > 0;
  }

  /** Per-round guard: true once a 2048 tile has existed this round (survives undo). */
  get winRecordedFlag(): boolean {
    return this.winRecorded;
  }

  cellAt(row: number, column: number): number {
    return this.cells[row * this.size + column];
  }

  /** True when no move in any direction can change the board. */
  get isGameOver(): boolean {
    return !hasAnyMoveOn(this.cells, this.size);
  }

  /**
   * Applies one move with the standard merge rules. When the board changed, one random
   * tile (2 with 90% probability, else 4) is spawned and true is returned.
   */
  move(direction: Direction2048): boolean {
    return this.moveWithResult(direction) !== null;
  }

  /**
   * Applies one move and returns the exact source-to-destination mapping needed for
   * animation. Returns null without changing board, score or RNG when nothing moves.
   */
  moveWithResult(direction: Direction2048): MoveResult2048 | null {
    const size = this.size;
    const before = this.cells.slice();
    const scoreBefore = this.score;
    const next = new Array<number>(this.cells.length).fill(0);
    const motions: TileMotion2048[] = [];
    const merges: Merge2048[] = [];
    let scoreGained = 0;

    for (let line = 0; line < size; line++) {
      const indices = this.lineIndices(direction, line);
      const tiles: Array<[number, number]> = [];
      for (const index of indices) {
        const value = this.cells[index];
        if (value !== 0) tiles.push([index, value]);
      }
      let sourcePosition = 0;
      let destinationPosition = 0;
      while (sourcePosition < tiles.length) {
        const first = tiles[sourcePosition];
        const second = sourcePosition + 1 < tiles.length ? tiles[sourcePosition + 1] : undefined;
        const destination = indices[destinationPosition];
        if (second !== undefined && first[1] === second[1]) {
          const mergedValue = first[1] * 2;
          next[destination] = mergedValue;
          motions.push({ fromIndex: first[0], toIndex: destination, value: first[1], merged: true });
          motions.push({ fromIndex: second[0], toIndex: destination, value: second[1], merged: true });
          merges.push({ toIndex: destination, value: mergedValue });
          scoreGained += mergedValue;
          sourcePosition += 2;
        } else {
          next[destination] = first[1];
          motions.push({ fromIndex: first[0], toIndex: destination, value: first[1], merged: false });
          sourcePosition += 1;
        }
        destinationPosition += 1;
      }
    }

    if (sameCells(before, next)) return null;

    this.undoHistory.push({ cells: before.slice(), score: scoreBefore });
    this.cells = next;
    this.score += scoreGained;
    const spawn = this.spawnTile();
    if (spawn === null) {
      throw new Error("A successful 2048 move must leave room for exactly one spawned tile");
    }
    let highestTile = 0;
    for (const value of this.cells) if (value > highestTile) highestTile = value;
    const wins = !this.winRecorded && highestTile >= GAME_2048_WIN_TILE ? ((this.winRecorded = true), 1) : 0;
    const losses = !this.lossRecorded && this.isGameOver ? ((this.lossRecorded = true), 1) : 0;
    return {
      before,
      after: this.cells.slice(),
      motions,
      merges,
      spawn,
      scoreGained,
      statisticsDelta: {
        effectiveMoves: 1,
        merges: merges.length,
        highestTile,
        wins,
        losses,
      },
    };
  }

  /** Restores the state before the latest successful move and keeps all earlier history. */
  undo(): boolean {
    const previous = this.undoHistory.pop();
    if (!previous) return false;
    this.cells = previous.cells.slice();
    this.score = previous.score;
    return true;
  }

  /** Serializes the complete restorable state as JSON (byte-compatible with Game2048.kt). */
  toJson(): string {
    const parts: string[] = [];
    parts.push(`{"size":${this.size},"cells":[`);
    this.cells.forEach((value, index) => {
      if (index > 0) parts.push(",");
      parts.push(String(value));
    });
    parts.push(`],"score":${this.score},"undoHistory":[`);
    this.undoHistory.forEach((previous, historyIndex) => {
      if (historyIndex > 0) parts.push(",");
      parts.push('{"cells":[');
      previous.cells.forEach((value, cellIndex) => {
        if (cellIndex > 0) parts.push(",");
        parts.push(String(value));
      });
      parts.push(`],"score":${previous.score}}`);
    });
    parts.push(
      `],"winRecorded":${this.winRecorded},"lossRecorded":${this.lossRecorded}}`,
    );
    return parts.join("");
  }

  /** Cell indices of one line, ordered from the edge the tiles slide towards. */
  private lineIndices(direction: Direction2048, line: number): number[] {
    const size = this.size;
    const result: number[] = new Array<number>(size);
    switch (direction) {
      case "LEFT":
        for (let i = 0; i < size; i++) result[i] = line * size + i;
        break;
      case "RIGHT":
        for (let i = 0; i < size; i++) result[i] = line * size + (size - 1 - i);
        break;
      case "UP":
        for (let i = 0; i < size; i++) result[i] = i * size + line;
        break;
      case "DOWN":
        for (let i = 0; i < size; i++) result[i] = (size - 1 - i) * size + line;
        break;
    }
    return result;
  }

  private spawnTile(): Spawn2048 | null {
    const empty: number[] = [];
    for (let index = 0; index < this.cells.length; index++) {
      if (this.cells[index] === 0) empty.push(index);
    }
    if (empty.length === 0) return null;
    const index = empty[Math.floor(this.random() * empty.length)];
    const value = this.random() < 0.9 ? 2 : 4;
    this.cells[index] = value;
    return { index, value };
  }

  /** Restores a game from toJson() output. Returns null (never throws) on invalid input. */
  static fromJson(json: string, expectedSize?: number | null, random: Random2048 = Math.random): Game2048 | null {
    let parsed: unknown;
    try {
      parsed = JSON.parse(json);
    } catch {
      return null;
    }
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return null;
    const map = parsed as Record<string, unknown>;

    const size = intOf(map["size"]) ?? GAME_2048_SIZE;
    if (size < GAME_2048_MIN_SIZE || size > GAME_2048_MAX_SIZE) return null;
    if (expectedSize != null && expectedSize !== size) return null;
    const cells = intListOf(map["cells"]);
    if (cells === null) return null;
    if (cells.length !== size * size || cells.some((value) => value < 0)) return null;
    const score = intOf(map["score"]);
    if (score === null || score < 0) return null;

    const undoHistory: UndoState2048[] = [];
    if (Object.prototype.hasOwnProperty.call(map, "undoHistory")) {
      const historyValues = map["undoHistory"];
      if (!Array.isArray(historyValues)) return null;
      for (const historyValue of historyValues) {
        if (typeof historyValue !== "object" || historyValue === null || Array.isArray(historyValue)) return null;
        const historyMap = historyValue as Record<string, unknown>;
        const historyCells = intListOf(historyMap["cells"]);
        const historyScore = intOf(historyMap["score"]);
        if (
          historyCells === null ||
          historyScore === null ||
          historyCells.length !== size * size ||
          historyCells.some((value) => value < 0) ||
          historyScore < 0
        ) {
          return null;
        }
        undoHistory.push({ cells: historyCells, score: historyScore });
      }
    } else {
      // v0.3.7 and earlier kept one undo snapshot. Promote it to the new history stack.
      const hasUndoCells = Object.prototype.hasOwnProperty.call(map, "undoCells");
      const hasUndoScore = Object.prototype.hasOwnProperty.call(map, "undoScore");
      if (hasUndoCells !== hasUndoScore) return null;
      if (hasUndoCells && map["undoCells"] != null && map["undoScore"] != null) {
        const restoredUndoCells = intListOf(map["undoCells"]);
        const restoredUndoScore = intOf(map["undoScore"]);
        if (
          restoredUndoCells === null ||
          restoredUndoScore === null ||
          restoredUndoCells.length !== size * size ||
          restoredUndoCells.some((value) => value < 0) ||
          restoredUndoScore < 0
        ) {
          return null;
        }
        undoHistory.push({ cells: restoredUndoCells, score: restoredUndoScore });
      }
    }

    let winRecorded: boolean;
    if (Object.prototype.hasOwnProperty.call(map, "winRecorded")) {
      const value = boolOf(map["winRecorded"]);
      if (value === null) return null;
      winRecorded = value;
    } else {
      winRecorded =
        Math.max(0, ...cells) >= GAME_2048_WIN_TILE ||
        undoHistory.some((history) => Math.max(0, ...history.cells) >= GAME_2048_WIN_TILE);
    }
    let lossRecorded: boolean;
    if (Object.prototype.hasOwnProperty.call(map, "lossRecorded")) {
      const value = boolOf(map["lossRecorded"]);
      if (value === null) return null;
      lossRecorded = value;
    } else {
      lossRecorded = !hasAnyMoveOn(cells, size);
    }

    return new Game2048(
      size,
      random,
      {
        cells: cells.slice(),
        score,
        undoHistory,
        winRecorded,
        lossRecorded,
      },
    );
  }
}

function sameCells(a: number[], b: number[]): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

/** Mirrors GameJson.intOf: integers only, within the signed 32-bit range. */
function intOf(value: unknown): number | null {
  if (typeof value === "number" && Number.isInteger(value) && value >= -2147483648 && value <= 2147483647) {
    return value;
  }
  return null;
}

function boolOf(value: unknown): boolean | null {
  return typeof value === "boolean" ? value : null;
}

function intListOf(value: unknown): number[] | null {
  if (!Array.isArray(value)) return null;
  const result: number[] = new Array<number>(value.length);
  for (let i = 0; i < value.length; i++) {
    const item = intOf(value[i]);
    if (item === null) return null;
    result[i] = item;
  }
  return result;
}
