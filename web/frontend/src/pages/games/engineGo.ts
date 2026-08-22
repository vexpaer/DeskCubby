/**
 * Pure-TypeScript local two-player Go engine — faithful port of
 * android/app/src/main/java/com/deskcubby/app/games/GoGame.kt.
 *
 * Implements captures, suicide prevention, positional simple ko and two consecutive
 * passes. Territory scoring is deliberately left to the players; the finished game
 * only reports capture counts. The serialized JSON stays byte-compatible with the
 * Kotlin engine ({"v":1,"size":..,"board":[..],...}) so saves can round-trip.
 */

export const GO_EMPTY = 0;
export const GO_BLACK = 1;
export const GO_WHITE = 2;
export type GoStone = typeof GO_EMPTY | typeof GO_BLACK | typeof GO_WHITE;

export function goOpponent(stone: GoStone): GoStone {
  if (stone === GO_BLACK) return GO_WHITE;
  if (stone === GO_WHITE) return GO_BLACK;
  return GO_EMPTY;
}

export type GoMoveError = "OUT_OF_BOUNDS" | "OCCUPIED" | "SUICIDE" | "KO" | "GAME_FINISHED";

/** Lifetime-statistics increments caused by exactly one accepted action. */
export interface GoStatisticsDelta {
  movesPlayed: number;
  stonesCaptured: number;
  passes: number;
  gamesCompleted: number;
}

export function goDeltaIsEmpty(delta: GoStatisticsDelta): boolean {
  return (
    delta.movesPlayed === 0 &&
    delta.stonesCaptured === 0 &&
    delta.passes === 0 &&
    delta.gamesCompleted === 0
  );
}

export interface GoMoveResult {
  accepted: boolean;
  error?: GoMoveError;
  captured: number;
  statisticsDelta: GoStatisticsDelta;
}

export interface GoPoint {
  x: number;
  y: number;
}

interface RestoredGoState {
  size: number;
  cells: number[];
  currentPlayer: GoStone;
  capturedByBlack: number;
  capturedByWhite: number;
  consecutivePasses: number;
  isFinished: boolean;
  turnCount: number;
  previousCells: number[] | null;
  lastMove: GoPoint | null;
}

export const GO_DEFAULT_SIZE = 9;
export const GO_SUPPORTED_SIZES: readonly number[] = [9, 13, 19];

const SAVE_VERSION = 1;
const MAX_COUNTER = 10_000_000;

function rejected(error: GoMoveError): GoMoveResult {
  return { accepted: false, error, captured: 0, statisticsDelta: { movesPlayed: 0, stonesCaptured: 0, passes: 0, gamesCompleted: 0 } };
}

interface GoGroup {
  stones: number[];
  hasLiberty: boolean;
}

export class GoGame {
  readonly size: number;
  private cells: number[];
  private previousCells: number[] | null;

  currentPlayer: GoStone;
  capturedByBlack: number;
  capturedByWhite: number;
  consecutivePasses: number;
  isFinished: boolean;
  turnCount: number;
  lastMove: GoPoint | null;

  constructor(size: number = GO_DEFAULT_SIZE, state?: RestoredGoState) {
    if (!GO_SUPPORTED_SIZES.includes(size)) {
      throw new Error("Unsupported Go board size");
    }
    this.size = size;
    if (state) {
      this.cells = state.cells.slice();
      this.previousCells = state.previousCells ? state.previousCells.slice() : null;
      this.currentPlayer = state.currentPlayer;
      this.capturedByBlack = state.capturedByBlack;
      this.capturedByWhite = state.capturedByWhite;
      this.consecutivePasses = state.consecutivePasses;
      this.isFinished = state.isFinished;
      this.turnCount = state.turnCount;
      this.lastMove = state.lastMove ? { ...state.lastMove } : null;
      return;
    }
    this.cells = new Array<number>(size * size).fill(GO_EMPTY);
    this.previousCells = null;
    this.currentPlayer = GO_BLACK;
    this.capturedByBlack = 0;
    this.capturedByWhite = 0;
    this.consecutivePasses = 0;
    this.isFinished = false;
    this.turnCount = 0;
    this.lastMove = null;
  }

  stoneAt(x: number, y: number): GoStone {
    if (x < 0 || x >= this.size || y < 0 || y >= this.size) {
      throw new Error("Point is outside the board");
    }
    return this.cells[this.indexOf(x, y)] as GoStone;
  }

  boardSnapshot(): GoStone[] {
    return this.cells.map((code) => code as GoStone);
  }

  /** Total stones captured by both players; used as the persisted "score". */
  captureScore(): number {
    return this.capturedByBlack + this.capturedByWhite;
  }

  /**
   * Returns a detached copy of the current position. The UI publishes a new object
   * after every successful move so observers reliably detect the change.
   */
  snapshotCopy(): GoGame {
    return new GoGame(this.size, {
      size: this.size,
      cells: this.cells,
      currentPlayer: this.currentPlayer,
      capturedByBlack: this.capturedByBlack,
      capturedByWhite: this.capturedByWhite,
      consecutivePasses: this.consecutivePasses,
      isFinished: this.isFinished,
      turnCount: this.turnCount,
      previousCells: this.previousCells,
      lastMove: this.lastMove,
    });
  }

  play(x: number, y: number): GoMoveResult {
    if (this.isFinished) return rejected("GAME_FINISHED");
    if (x < 0 || x >= this.size || y < 0 || y >= this.size) {
      return rejected("OUT_OF_BOUNDS");
    }
    const playedIndex = this.indexOf(x, y);
    if (this.cells[playedIndex] !== GO_EMPTY) return rejected("OCCUPIED");

    const before = this.cells.slice();
    const candidate = before.slice();
    candidate[playedIndex] = this.currentPlayer;

    let captured = 0;
    const checkedOpponent = new Array<boolean>(candidate.length).fill(false);
    for (const neighbor of this.neighbors(playedIndex)) {
      if (
        candidate[neighbor] === goOpponent(this.currentPlayer) &&
        !checkedOpponent[neighbor]
      ) {
        const group = this.collectGroup(candidate, neighbor);
        for (const stone of group.stones) checkedOpponent[stone] = true;
        if (!group.hasLiberty) {
          for (const stone of group.stones) candidate[stone] = GO_EMPTY;
          captured += group.stones.length;
        }
      }
    }

    if (!this.collectGroup(candidate, playedIndex).hasLiberty) {
      return rejected("SUICIDE");
    }
    if (this.previousCells !== null && goArraysEqual(this.previousCells, candidate)) {
      return rejected("KO");
    }

    this.cells = candidate;
    this.previousCells = before;
    if (this.currentPlayer === GO_BLACK) {
      this.capturedByBlack += captured;
    } else {
      this.capturedByWhite += captured;
    }
    this.consecutivePasses = 0;
    this.turnCount += 1;
    this.lastMove = { x, y };
    this.currentPlayer = goOpponent(this.currentPlayer);
    return {
      accepted: true,
      captured,
      statisticsDelta: { movesPlayed: 1, stonesCaptured: captured, passes: 0, gamesCompleted: 0 },
    };
  }

  pass(): GoMoveResult {
    if (this.isFinished) return rejected("GAME_FINISHED");
    this.previousCells = this.cells.slice();
    this.consecutivePasses += 1;
    this.turnCount += 1;
    this.lastMove = null;
    this.currentPlayer = goOpponent(this.currentPlayer);
    if (this.consecutivePasses >= 2) this.isFinished = true;
    return {
      accepted: true,
      captured: 0,
      statisticsDelta: {
        movesPlayed: 0,
        stonesCaptured: 0,
        passes: 1,
        gamesCompleted: this.isFinished ? 1 : 0,
      },
    };
  }

  toJson(): string {
    const parts: string[] = [];
    parts.push(`{"v":${SAVE_VERSION}`);
    parts.push(`,"size":${this.size}`);
    parts.push(`,"board":[${this.cells.join(",")}]`);
    parts.push(`,"current":${this.currentPlayer}`);
    parts.push(`,"capturedByBlack":${this.capturedByBlack}`);
    parts.push(`,"capturedByWhite":${this.capturedByWhite}`);
    parts.push(`,"passes":${this.consecutivePasses}`);
    parts.push(`,"finished":${this.isFinished}`);
    parts.push(`,"turnCount":${this.turnCount}`);
    parts.push(`,"previousBoard":`);
    if (this.previousCells === null) parts.push("null");
    else parts.push(`[${this.previousCells.join(",")}]`);
    parts.push(`,"lastMove":`);
    if (this.lastMove === null) parts.push("null");
    else parts.push(`{"x":${this.lastMove.x},"y":${this.lastMove.y}}`);
    parts.push(`}`);
    return parts.join("");
  }

  private collectGroup(board: number[], start: number): GoGroup {
    const color = board[start];
    const seen = new Array<boolean>(board.length).fill(false);
    const stack: number[] = [];
    const stones: number[] = [];
    let hasLiberty = false;
    seen[start] = true;
    stack.push(start);
    while (stack.length > 0) {
      const current = stack.pop() as number;
      stones.push(current);
      for (const neighbor of this.neighbors(current)) {
        if (board[neighbor] === GO_EMPTY) hasLiberty = true;
        else if (board[neighbor] === color && !seen[neighbor]) {
          seen[neighbor] = true;
          stack.push(neighbor);
        }
      }
    }
    return { stones, hasLiberty };
  }

  private neighbors(cellIndex: number): number[] {
    const x = cellIndex % this.size;
    const y = Math.floor(cellIndex / this.size);
    const result: number[] = [];
    if (x > 0) result.push(cellIndex - 1);
    if (x < this.size - 1) result.push(cellIndex + 1);
    if (y > 0) result.push(cellIndex - this.size);
    if (y < this.size - 1) result.push(cellIndex + this.size);
    return result;
  }

  private indexOf(x: number, y: number): number {
    return y * this.size + x;
  }

  /** Restores a game from toJson() output. Returns null (never throws) on invalid input. */
  static fromJson(json: string): GoGame | null {
    let parsed: unknown;
    try {
      parsed = JSON.parse(json);
    } catch {
      return null;
    }
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return null;
    const root = parsed as Record<string, unknown>;

    const version = root["v"] === undefined ? SAVE_VERSION : intOf(root["v"]);
    if (version !== SAVE_VERSION) return null;
    const size = intOf(root["size"]);
    if (size === null || !GO_SUPPORTED_SIZES.includes(size)) return null;

    const cells = decodeCells(root["board"], size);
    if (cells === null) return null;
    const currentCode = intOf(root["current"]);
    if (currentCode !== GO_BLACK && currentCode !== GO_WHITE) return null;
    const capturedByBlack = intOf(root["capturedByBlack"]);
    const capturedByWhite = intOf(root["capturedByWhite"]);
    const passes = intOf(root["passes"]);
    const turnCount = intOf(root["turnCount"]);
    const finished = boolOf(root["finished"]);
    if (
      capturedByBlack === null ||
      capturedByWhite === null ||
      passes === null ||
      turnCount === null ||
      finished === null
    ) {
      return null;
    }
    if (
      capturedByBlack < 0 ||
      capturedByBlack > MAX_COUNTER ||
      capturedByWhite < 0 ||
      capturedByWhite > MAX_COUNTER ||
      passes < 0 ||
      passes > 2 ||
      turnCount < 0 ||
      turnCount > MAX_COUNTER ||
      finished !== passes >= 2
    ) {
      return null;
    }

    let previous: number[] | null = null;
    const rawPrevious = root["previousBoard"];
    if (rawPrevious !== undefined && rawPrevious !== null) {
      previous = decodeCells(rawPrevious, size);
      if (previous === null) return null;
    }

    let lastMove: GoPoint | null = null;
    const rawLastMove = root["lastMove"];
    if (rawLastMove !== undefined && rawLastMove !== null) {
      if (typeof rawLastMove !== "object" || Array.isArray(rawLastMove)) return null;
      const point = rawLastMove as Record<string, unknown>;
      const px = intOf(point["x"]);
      const py = intOf(point["y"]);
      if (px === null || py === null || px < 0 || px >= size || py < 0 || py >= size) return null;
      lastMove = { x: px, y: py };
      if (cells[lastMove.y * size + lastMove.x] === GO_EMPTY) return null;
    }

    return new GoGame(size, {
      size,
      cells,
      currentPlayer: currentCode,
      capturedByBlack,
      capturedByWhite,
      consecutivePasses: passes,
      isFinished: finished,
      turnCount,
      previousCells: previous,
      lastMove,
    });
  }
}

function decodeCells(value: unknown, size: number): number[] | null {
  if (!Array.isArray(value)) return null;
  if (value.length !== size * size) return null;
  const result = new Array<number>(value.length);
  for (let i = 0; i < value.length; i++) {
    const code = intOf(value[i]);
    if (code !== GO_EMPTY && code !== GO_BLACK && code !== GO_WHITE) return null;
    result[i] = code;
  }
  return result;
}

function goArraysEqual(a: number[], b: number[]): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) return false;
  }
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
