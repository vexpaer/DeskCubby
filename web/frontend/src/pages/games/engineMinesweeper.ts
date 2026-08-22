/**
 * Pure-TypeScript, first-tap-safe Minesweeper engine — faithful port of
 * android/app/src/main/java/com/deskcubby/app/games/MinesweeperGame.kt.
 *
 * Bounded custom dimensions (6–30 × 6–30, 1 .. w*h−1 mines), first-reveal safety with
 * broad exclusion of the surrounding eight cells when space allows, flood reveal,
 * chord (double-tap) that opens every unflagged neighbor without requiring matching
 * flag counts, flag cap at the mine count, automatic flagging of remaining mines on a
 * win (never counted as player placements), and strict JSON save validation.
 */

export interface MinesweeperCellView {
  revealed: boolean;
  flagged: boolean;
  adjacentMines: number;
  mine: boolean;
}

export type MinesweeperAction = "REVEAL" | "CHORD" | "TOGGLE_FLAG";

/** Lifetime-statistics increments produced by exactly one accepted player action. */
export interface MinesweeperStatisticsDelta {
  minesCellsRevealed: number;
  minesSwept: number;
  flagsPlaced: number;
  wins: number;
  losses: number;
}

export interface MinesweeperActionResult {
  action: MinesweeperAction;
  changed: boolean;
  statisticsDelta: MinesweeperStatisticsDelta;
}

interface RestoredMinesweeperState {
  mines: boolean[];
  revealed: boolean[];
  flagged: boolean[];
  initialized: boolean;
  gameOver: boolean;
  won: boolean;
}

export const MINESWEEPER_MIN_WIDTH = 6;
export const MINESWEEPER_MAX_WIDTH = 30;
export const MINESWEEPER_MIN_HEIGHT = 6;
export const MINESWEEPER_MAX_HEIGHT = 30;

const EMPTY_DELTA: MinesweeperStatisticsDelta = {
  minesCellsRevealed: 0,
  minesSwept: 0,
  flagsPlaced: 0,
  wins: 0,
  losses: 0,
};

function deltaIsEmpty(delta: MinesweeperStatisticsDelta): boolean {
  return (
    delta.minesCellsRevealed === 0 &&
    delta.minesSwept === 0 &&
    delta.flagsPlaced === 0 &&
    delta.wins === 0 &&
    delta.losses === 0
  );
}

function validDimensions(width: number, height: number, mineCount: number): boolean {
  return (
    width >= MINESWEEPER_MIN_WIDTH &&
    width <= MINESWEEPER_MAX_WIDTH &&
    height >= MINESWEEPER_MIN_HEIGHT &&
    height <= MINESWEEPER_MAX_HEIGHT &&
    mineCount >= 1 &&
    mineCount < width * height
  );
}

export class MinesweeperGame {
  readonly width: number;
  readonly height: number;
  readonly mineCount: number;
  private random: () => number;
  private mines: boolean[];
  private revealed: boolean[];
  private flagged: boolean[];

  initialized: boolean;
  isGameOver: boolean;
  isWon: boolean;

  constructor(
    width: number = 9,
    height: number = 9,
    mineCount: number = 10,
    random: () => number = Math.random,
    state?: RestoredMinesweeperState,
  ) {
    if (!validDimensions(width, height, mineCount)) {
      throw new Error("Invalid minesweeper dimensions");
    }
    this.width = width;
    this.height = height;
    this.mineCount = mineCount;
    this.random = random;
    if (state) {
      this.mines = state.mines.slice();
      this.revealed = state.revealed.slice();
      this.flagged = state.flagged.slice();
      this.initialized = state.initialized;
      this.isGameOver = state.gameOver;
      this.isWon = state.won;
      return;
    }
    this.mines = new Array<boolean>(width * height).fill(false);
    this.revealed = new Array<boolean>(width * height).fill(false);
    this.flagged = new Array<boolean>(width * height).fill(false);
    this.initialized = false;
    this.isGameOver = false;
    this.isWon = false;
  }

  get remainingMines(): number {
    let flaggedCount = 0;
    for (const flagged of this.flagged) if (flagged) flaggedCount++;
    return Math.max(0, this.mineCount - flaggedCount);
  }

  get revealedSafeCount(): number {
    let count = 0;
    for (let i = 0; i < this.revealed.length; i++) {
      if (this.revealed[i] && !this.mines[i]) count++;
    }
    return count;
  }

  cell(x: number, y: number): MinesweeperCellView {
    if (x < 0 || x >= this.width || y < 0 || y >= this.height) {
      throw new Error("Cell is outside the board");
    }
    const index = this.indexOf(x, y);
    const exposeMine = this.mines[index] && (this.isGameOver || this.isWon || this.revealed[index]);
    return {
      revealed: this.revealed[index],
      flagged: this.flagged[index],
      adjacentMines:
        this.revealed[index] && !this.mines[index] ? this.adjacentMineCount(x, y) : 0,
      mine: exposeMine,
    };
  }

  /** Compatibility wrapper for callers that only need to know whether the board changed. */
  reveal(x: number, y: number): boolean {
    return this.revealWithResult(x, y).changed;
  }

  revealWithResult(x: number, y: number): MinesweeperActionResult {
    if (this.isGameOver || this.isWon || x < 0 || x >= this.width || y < 0 || y >= this.height) {
      return unchanged("REVEAL");
    }
    const start = this.indexOf(x, y);
    if (this.flagged[start] || this.revealed[start]) return unchanged("REVEAL");
    if (!this.initialized) this.placeMines(x, y);
    if (this.mines[start]) {
      this.revealed[start] = true;
      this.isGameOver = true;
      return {
        action: "REVEAL",
        changed: true,
        statisticsDelta: { ...EMPTY_DELTA, minesCellsRevealed: 1, losses: 1 },
      };
    }
    const newlyRevealed = this.revealSafeRegion(start);
    const wonThisAction = this.finishWinIfCleared();
    return {
      action: "REVEAL",
      changed: newlyRevealed > 0,
      statisticsDelta: {
        minesCellsRevealed: newlyRevealed,
        minesSwept: wonThisAction ? this.mineCount : 0,
        flagsPlaced: 0,
        wins: wonThisAction ? 1 : 0,
        losses: 0,
      },
    };
  }

  /**
   * Reveals every unrevealed, unflagged neighbor of an already revealed numbered cell.
   * Unlike the classic flag-count shortcut this intentionally does not require the
   * adjacent flag count to match the number, so a wrong flag can expose a mine.
   */
  chordWithResult(x: number, y: number): MinesweeperActionResult {
    if (this.isGameOver || this.isWon || x < 0 || x >= this.width || y < 0 || y >= this.height) {
      return unchanged("CHORD");
    }
    const center = this.indexOf(x, y);
    if (!this.revealed[center] || this.mines[center] || this.adjacentMineCount(x, y) <= 0) {
      return unchanged("CHORD");
    }
    const targets = this.neighbors(x, y).filter((i) => !this.revealed[i] && !this.flagged[i]);
    if (targets.length === 0) return unchanged("CHORD");

    let revealedBefore = 0;
    for (const value of this.revealed) if (value) revealedBefore++;
    let triggeredMine = false;
    for (const target of targets) {
      if (this.mines[target]) {
        this.revealed[target] = true;
        triggeredMine = true;
      } else {
        this.revealSafeRegion(target);
      }
    }
    if (triggeredMine) this.isGameOver = true;
    const wonThisAction = !triggeredMine && this.finishWinIfCleared();
    let revealedAfter = 0;
    for (const value of this.revealed) if (value) revealedAfter++;
    const newlyRevealed = revealedAfter - revealedBefore;
    return {
      action: "CHORD",
      changed: newlyRevealed > 0,
      statisticsDelta: {
        minesCellsRevealed: newlyRevealed,
        minesSwept: wonThisAction ? this.mineCount : 0,
        flagsPlaced: 0,
        wins: wonThisAction ? 1 : 0,
        losses: triggeredMine ? 1 : 0,
      },
    };
  }

  /** Compatibility wrapper for callers that only need to know whether the board changed. */
  toggleFlag(x: number, y: number): boolean {
    return this.toggleFlagWithResult(x, y).changed;
  }

  toggleFlagWithResult(x: number, y: number): MinesweeperActionResult {
    if (this.isGameOver || this.isWon || x < 0 || x >= this.width || y < 0 || y >= this.height) {
      return unchanged("TOGGLE_FLAG");
    }
    const index = this.indexOf(x, y);
    if (this.revealed[index]) return unchanged("TOGGLE_FLAG");
    let flaggedCount = 0;
    for (const flagged of this.flagged) if (flagged) flaggedCount++;
    if (!this.flagged[index] && flaggedCount >= this.mineCount) return unchanged("TOGGLE_FLAG");
    const placingFlag = !this.flagged[index];
    this.flagged[index] = !this.flagged[index];
    return {
      action: "TOGGLE_FLAG",
      changed: true,
      statisticsDelta: { ...EMPTY_DELTA, flagsPlaced: placingFlag ? 1 : 0 },
    };
  }

  /** Serializes the complete restorable state as JSON (byte-compatible with the Kotlin engine). */
  toJson(): string {
    const parts: string[] = [];
    parts.push(
      `{"w":${this.width}`,
      `,"h":${this.height}`,
      `,"count":${this.mineCount}`,
      `,"initialized":${this.initialized}`,
      `,"over":${this.isGameOver}`,
      `,"won":${this.isWon}`,
      `,"mines":`,
    );
    appendIndexArray(parts, this.mines);
    parts.push(`,"revealed":`);
    appendIndexArray(parts, this.revealed);
    parts.push(`,"flagged":`);
    appendIndexArray(parts, this.flagged);
    parts.push(`}`);
    return parts.join("");
  }

  private placeMines(firstX: number, firstY: number): void {
    const broadExclusion = new Set<number>([this.indexOf(firstX, firstY), ...this.neighbors(this.indexOf(firstX, firstY))]);
    const size = this.width * this.height;
    const exclusion =
      size - broadExclusion.size >= this.mineCount ? broadExclusion : new Set<number>([this.indexOf(firstX, firstY)]);
    const candidates: number[] = [];
    for (let i = 0; i < size; i++) if (!exclusion.has(i)) candidates.push(i);
    // Fisher-Yates shuffle driven by the injected random, mirroring shuffled(random).
    for (let i = candidates.length - 1; i > 0; i--) {
      const j = Math.floor(this.random() * (i + 1));
      const tmp = candidates[i];
      candidates[i] = candidates[j];
      candidates[j] = tmp;
    }
    for (let i = 0; i < this.mineCount; i++) this.mines[candidates[i]] = true;
    this.initialized = true;
  }

  private revealSafeRegion(start: number): number {
    let newlyRevealed = 0;
    const queue: number[] = [start];
    while (queue.length > 0) {
      const current = queue.shift() as number;
      if (this.revealed[current] || this.flagged[current] || this.mines[current]) continue;
      this.revealed[current] = true;
      newlyRevealed++;
      const cx = current % this.width;
      const cy = Math.floor(current / this.width);
      if (this.adjacentMineCount(cx, cy) === 0) {
        for (const neighbor of this.neighbors(cx, cy)) {
          if (!this.revealed[neighbor] && !this.flagged[neighbor] && !this.mines[neighbor]) {
            queue.push(neighbor);
          }
        }
      }
    }
    return newlyRevealed;
  }

  private finishWinIfCleared(): boolean {
    if (this.isGameOver || this.isWon || this.revealedSafeCount !== this.width * this.height - this.mineCount) {
      return false;
    }
    this.isWon = true;
    for (let i = 0; i < this.mines.length; i++) if (this.mines[i]) this.flagged[i] = true;
    return true;
  }

  private adjacentMineCount(x: number, y: number): number {
    let count = 0;
    for (const index of this.neighbors(this.indexOf(x, y))) if (this.mines[index]) count++;
    return count;
  }

  private neighbors(cellIndexOrX: number, maybeY?: number): number[] {
    let x: number;
    let y: number;
    if (maybeY === undefined) {
      x = cellIndexOrX % this.width;
      y = Math.floor(cellIndexOrX / this.width);
    } else {
      x = cellIndexOrX;
      y = maybeY;
    }
    const result: number[] = [];
    for (let dy = -1; dy <= 1; dy++) {
      for (let dx = -1; dx <= 1; dx++) {
        if (dx === 0 && dy === 0) continue;
        const nx = x + dx;
        const ny = y + dy;
        if (nx >= 0 && nx < this.width && ny >= 0 && ny < this.height) {
          result.push(this.indexOf(nx, ny));
        }
      }
    }
    return result;
  }

  private indexOf(x: number, y: number): number {
    return y * this.width + x;
  }

  /** Restores a game from toJson() output. Returns null (never throws) on invalid input. */
  static fromJson(json: string, random: () => number = Math.random): MinesweeperGame | null {
    let parsed: unknown;
    try {
      parsed = JSON.parse(json);
    } catch {
      return null;
    }
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return null;
    const map = parsed as Record<string, unknown>;
    const width = intOf(map["w"]);
    const height = intOf(map["h"]);
    const count = intOf(map["count"]);
    if (width === null || height === null || count === null) return null;
    if (!validDimensions(width, height, count)) return null;
    const size = width * height;

    const decodeFlags = (name: string): boolean[] | null => {
      const indices = intListOf(map[name]);
      if (indices === null) return null;
      const seen = new Set<number>(indices);
      if (seen.size !== indices.length) return null;
      for (const index of indices) if (index < 0 || index >= size) return null;
      const values = new Array<boolean>(size).fill(false);
      for (const index of indices) values[index] = true;
      return values;
    };

    const mines = decodeFlags("mines");
    const revealed = decodeFlags("revealed");
    const flagged = decodeFlags("flagged");
    if (mines === null || revealed === null || flagged === null) return null;
    const initialized = boolOf(map["initialized"]);
    const over = boolOf(map["over"]);
    const won = boolOf(map["won"]);
    if (initialized === null || over === null || won === null) return null;

    let mineTotal = 0;
    for (const mine of mines) if (mine) mineTotal++;
    let flaggedTotal = 0;
    let revealedMine = false;
    let revealedSafe = 0;
    for (let i = 0; i < size; i++) {
      if (mines[i] && revealed[i]) revealedMine = true;
      if (revealed[i] && !mines[i]) revealedSafe++;
      if (flagged[i]) flaggedTotal++;
    }
    if (initialized && mineTotal !== count) return null;
    if (!initialized && mineTotal !== 0) return null;
    for (let i = 0; i < size; i++) if (revealed[i] && flagged[i]) return null;
    if ((over || won) && !initialized) return null;
    if (over && won) return null;
    if (over !== revealedMine) return null;
    if (flaggedTotal > count) return null;
    if (won && revealedSafe !== size - count) return null;
    // A chord can expose a mine and the final safe cells in the same atomic action. That
    // is a valid loss, whereas an unfinished board with every safe cell open is not.
    if (!won && !over && revealedSafe === size - count) return null;

    return new MinesweeperGame(width, height, count, random, {
      mines,
      revealed,
      flagged,
      initialized,
      gameOver: over,
      won,
    });
  }
}

function unchanged(action: MinesweeperAction): MinesweeperActionResult {
  return { action, changed: false, statisticsDelta: { ...EMPTY_DELTA } };
}

function appendIndexArray(parts: string[], values: boolean[]): void {
  parts.push("[");
  let first = true;
  values.forEach((value, index) => {
    if (value) {
      if (!first) parts.push(",");
      parts.push(String(index));
      first = false;
    }
  });
  parts.push("]");
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
