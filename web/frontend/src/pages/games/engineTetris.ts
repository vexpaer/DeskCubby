/**
 * Pure-TypeScript Tetris engine — faithful port of
 * android/app/src/main/java/com/deskcubby/app/games/TetrisGame.kt.
 *
 * 10×20 board, the 7 classic tetrominoes with classic bounding-box clockwise
 * rotation (rotation fails when blocked; no wall kicks), spawn centered at the top,
 * line scores 100/300/500/800, level = clearedLines / 10, next-piece preview.
 * Speed is owned by the UI layer: tick = max(120, 600 − 40·level) ms.
 * JSON save format is byte-compatible with the Kotlin engine.
 */

export interface TetrisCell {
  x: number;
  y: number;
}

/** Aggregate-statistics increments caused by one gravity/drop action. */
export interface TetrisStatisticsDelta {
  piecesLocked: number;
  linesCleared: number;
  tetrises: number;
  losses: number;
}

export interface TetrisStepResult {
  moved: boolean;
  lockedPiece: boolean;
  linesCleared: number;
  gameEnded: boolean;
  statisticsDelta: TetrisStatisticsDelta;
}

interface RestoredTetrisState {
  board: number[];
  score: number;
  lines: number;
  pieceType: number;
  pieceRotation: number;
  pieceX: number;
  pieceY: number;
  nextPieceType: number;
  isGameOver: boolean;
}

const TETRIS_STATISTICS_NONE: TetrisStatisticsDelta = {
  piecesLocked: 0,
  linesCleared: 0,
  tetrises: 0,
  losses: 0,
};

export const TETRIS_WIDTH = 10;
export const TETRIS_HEIGHT = 20;
export const TETRIS_PIECE_COUNT = 7;
const ROTATION_COUNT = 4;
const LINES_PER_LEVEL = 10;

/** Points for clearing 1 / 2 / 3 / 4 rows at once. */
const LINE_SCORES = [100, 300, 500, 800];

/** Bounding-box edge per piece, used to rotate and to center the spawn position. */
const BOX_SIZES = [4, 2, 3, 3, 3, 3, 3];

/** Base cells per piece, in order: I, O, T, S, Z, J, L. */
const BASE_CELLS: TetrisCell[][] = [
  [{ x: 0, y: 1 }, { x: 1, y: 1 }, { x: 2, y: 1 }, { x: 3, y: 1 }],
  [{ x: 0, y: 0 }, { x: 1, y: 0 }, { x: 0, y: 1 }, { x: 1, y: 1 }],
  [{ x: 1, y: 0 }, { x: 0, y: 1 }, { x: 1, y: 1 }, { x: 2, y: 1 }],
  [{ x: 1, y: 0 }, { x: 2, y: 0 }, { x: 0, y: 1 }, { x: 1, y: 1 }],
  [{ x: 0, y: 0 }, { x: 1, y: 0 }, { x: 1, y: 1 }, { x: 2, y: 1 }],
  [{ x: 0, y: 0 }, { x: 0, y: 1 }, { x: 1, y: 1 }, { x: 2, y: 1 }],
  [{ x: 2, y: 0 }, { x: 0, y: 1 }, { x: 1, y: 1 }, { x: 2, y: 1 }],
];

/** All four classic clockwise rotations per piece, precomputed inside each bounding box. */
const SHAPES: TetrisCell[][][] = BASE_CELLS.map((base, type) => {
  const box = BOX_SIZES[type];
  const rotations: TetrisCell[][] = [];
  let current = base;
  for (let r = 0; r < ROTATION_COUNT; r++) {
    rotations.push(current);
    current = current
      .map((cell) => ({ x: box - 1 - cell.y, y: cell.x }))
      .sort((a, b) => a.y - b.y || a.x - b.x);
  }
  return rotations;
});

function collidesAt(
  type: number,
  rotation: number,
  originX: number,
  originY: number,
  board: number[],
): boolean {
  for (const cell of SHAPES[type][rotation]) {
    const x = originX + cell.x;
    const y = originY + cell.y;
    if (x < 0 || x >= TETRIS_WIDTH || y < 0 || y >= TETRIS_HEIGHT) return true;
    if (board[y * TETRIS_WIDTH + x] !== 0) return true;
  }
  return false;
}

export class TetrisGame {
  private board: number[];
  private random: () => number;

  score: number;
  lines: number;
  isGameOver: boolean;
  pieceType: number;
  pieceRotation: number;
  pieceX: number;
  pieceY: number;
  nextPieceType: number;

  /** Starts a fresh game with a random current and next piece. */
  constructor(random: () => number = Math.random, state?: RestoredTetrisState) {
    if (state) {
      this.board = state.board.slice();
      this.score = state.score;
      this.lines = state.lines;
      this.pieceType = state.pieceType;
      this.pieceRotation = state.pieceRotation;
      this.pieceX = state.pieceX;
      this.pieceY = state.pieceY;
      this.nextPieceType = state.nextPieceType;
      this.isGameOver = state.isGameOver;
      this.random = random;
      return;
    }
    this.board = new Array<number>(TETRIS_WIDTH * TETRIS_HEIGHT).fill(0);
    this.score = 0;
    this.lines = 0;
    this.isGameOver = false;
    this.pieceType = 0;
    this.pieceRotation = 0;
    this.pieceX = 0;
    this.pieceY = 0;
    this.nextPieceType = 0;
    this.random = random;
    this.spawnPiece(Math.floor(this.random() * TETRIS_PIECE_COUNT));
    this.nextPieceType = Math.floor(this.random() * TETRIS_PIECE_COUNT);
  }

  /** Increases every LINES_PER_LEVEL cleared lines; the UI shortens the tick interval with it. */
  get level(): number {
    return Math.floor(this.lines / LINES_PER_LEVEL);
  }

  /** Locked cells only: 0 = empty, otherwise pieceType + 1 of the locked piece. */
  boardCell(x: number, y: number): number {
    return this.board[y * TETRIS_WIDTH + x];
  }

  boardSnapshot(): number[] {
    return this.board.slice();
  }

  /** Absolute board coordinates of the falling piece. */
  currentPieceCells(): TetrisCell[] {
    return SHAPES[this.pieceType][this.pieceRotation].map((cell) => ({
      x: this.pieceX + cell.x,
      y: this.pieceY + cell.y,
    }));
  }

  /** Next piece in spawn rotation, normalized so its occupied cells start at (0, 0). */
  nextPiecePreviewCells(): TetrisCell[] {
    const cells = SHAPES[this.nextPieceType][0];
    let minX = Number.POSITIVE_INFINITY;
    let minY = Number.POSITIVE_INFINITY;
    for (const cell of cells) {
      if (cell.x < minX) minX = cell.x;
      if (cell.y < minY) minY = cell.y;
    }
    return cells.map((cell) => ({ x: cell.x - minX, y: cell.y - minY }));
  }

  moveLeft(): boolean {
    return this.applyMove(-1, 0, this.pieceRotation);
  }

  moveRight(): boolean {
    return this.applyMove(1, 0, this.pieceRotation);
  }

  /** Clockwise rotation; fails (returns false) when the rotated piece would collide. */
  rotate(): boolean {
    return this.applyMove(0, 0, (this.pieceRotation + 1) % ROTATION_COUNT);
  }

  /** Moves down one row; locks the piece when it cannot fall. Returns true when it moved. */
  softDrop(): boolean {
    return this.softDropWithResult().moved;
  }

  softDropWithResult(): TetrisStepResult {
    return this.descendWithResult();
  }

  /** Gravity step driven by the UI loop. */
  tick(): void {
    this.tickWithResult();
  }

  tickWithResult(): TetrisStepResult {
    return this.descendWithResult();
  }

  /** Drops the piece to the bottom and locks it immediately. */
  hardDrop(): void {
    this.hardDropWithResult();
  }

  hardDropWithResult(): TetrisStepResult {
    if (this.isGameOver) return idleStepResult(this.isGameOver);
    let moved = false;
    while (this.applyMove(0, 1, this.pieceRotation)) {
      moved = true;
      // keep falling
    }
    return this.lockPiece(moved);
  }

  /** Serializes the complete restorable state as JSON (byte-compatible with TetrisGame.kt). */
  toJson(): string {
    const parts: string[] = [];
    parts.push('{"board":[');
    this.board.forEach((value, index) => {
      if (index > 0) parts.push(",");
      parts.push(String(value));
    });
    parts.push(
      `],"score":${this.score}`,
      `,"lines":${this.lines}`,
      `,"type":${this.pieceType}`,
      `,"rot":${this.pieceRotation}`,
      `,"x":${this.pieceX}`,
      `,"y":${this.pieceY}`,
      `,"next":${this.nextPieceType}`,
      `,"over":${this.isGameOver}}`,
    );
    return parts.join("");
  }

  private descendWithResult(): TetrisStepResult {
    if (this.isGameOver) return idleStepResult(this.isGameOver);
    if (this.applyMove(0, 1, this.pieceRotation)) {
      return {
        moved: true,
        lockedPiece: false,
        linesCleared: 0,
        gameEnded: false,
        statisticsDelta: TETRIS_STATISTICS_NONE,
      };
    }
    return this.lockPiece(false);
  }

  private applyMove(dx: number, dy: number, rotation: number): boolean {
    if (this.isGameOver) return false;
    const newX = this.pieceX + dx;
    const newY = this.pieceY + dy;
    if (collidesAt(this.pieceType, rotation, newX, newY, this.board)) return false;
    this.pieceX = newX;
    this.pieceY = newY;
    this.pieceRotation = rotation;
    return true;
  }

  private lockPiece(moved: boolean): TetrisStepResult {
    for (const cell of this.currentPieceCells()) {
      this.board[cell.y * TETRIS_WIDTH + cell.x] = this.pieceType + 1;
    }
    const cleared = this.clearFullRows();
    if (cleared > 0) {
      this.lines += cleared;
      this.score += LINE_SCORES[cleared - 1];
    }
    this.spawnPiece(this.nextPieceType);
    this.nextPieceType = Math.floor(this.random() * TETRIS_PIECE_COUNT);
    return {
      moved,
      lockedPiece: true,
      linesCleared: cleared,
      gameEnded: this.isGameOver,
      statisticsDelta: {
        piecesLocked: 1,
        linesCleared: cleared,
        tetrises: cleared === 4 ? 1 : 0,
        losses: this.isGameOver ? 1 : 0,
      },
    };
  }

  private clearFullRows(): number {
    let cleared = 0;
    let writeY = TETRIS_HEIGHT - 1;
    for (let y = TETRIS_HEIGHT - 1; y >= 0; y--) {
      let full = true;
      for (let x = 0; x < TETRIS_WIDTH; x++) {
        if (this.board[y * TETRIS_WIDTH + x] === 0) {
          full = false;
          break;
        }
      }
      if (full) {
        cleared++;
      } else {
        if (writeY !== y) {
          for (let x = 0; x < TETRIS_WIDTH; x++) {
            this.board[writeY * TETRIS_WIDTH + x] = this.board[y * TETRIS_WIDTH + x];
          }
        }
        writeY--;
      }
    }
    for (let y = writeY; y >= 0; y--) {
      for (let x = 0; x < TETRIS_WIDTH; x++) {
        this.board[y * TETRIS_WIDTH + x] = 0;
      }
    }
    return cleared;
  }

  private spawnPiece(type: number): void {
    this.pieceType = type;
    this.pieceRotation = 0;
    this.pieceX = Math.floor((TETRIS_WIDTH - BOX_SIZES[type]) / 2);
    this.pieceY = 0;
    if (collidesAt(this.pieceType, this.pieceRotation, this.pieceX, this.pieceY, this.board)) {
      this.isGameOver = true;
    }
  }

  /** Restores a game from toJson() output. Returns null (never throws) on invalid input. */
  static fromJson(json: string, random: () => number = Math.random): TetrisGame | null {
    let parsed: unknown;
    try {
      parsed = JSON.parse(json);
    } catch {
      return null;
    }
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return null;
    const map = parsed as Record<string, unknown>;
    const boardValues = intListOf(map["board"]);
    if (boardValues === null || boardValues.length !== TETRIS_WIDTH * TETRIS_HEIGHT) return null;
    if (boardValues.some((value) => value < 0 || value > TETRIS_PIECE_COUNT)) return null;
    const score = intOf(map["score"]);
    const lines = intOf(map["lines"]);
    if (score === null || lines === null || score < 0 || lines < 0) return null;
    const type = intOf(map["type"]);
    const rotation = intOf(map["rot"]);
    const x = intOf(map["x"]);
    const y = intOf(map["y"]);
    const next = intOf(map["next"]);
    const over = boolOf(map["over"]);
    if (type === null || next === null || over === null) return null;
    if (rotation === null || x === null || y === null) return null;
    if (type < 0 || type >= TETRIS_PIECE_COUNT || next < 0 || next >= TETRIS_PIECE_COUNT) return null;
    if (rotation < 0 || rotation >= ROTATION_COUNT) return null;
    const game = new TetrisGame(random, {
      board: boardValues.slice(),
      score,
      lines,
      pieceType: type,
      pieceRotation: rotation,
      pieceX: x,
      pieceY: y,
      nextPieceType: next,
      isGameOver: over,
    });
    if (!over && collidesAt(type, rotation, x, y, game.board)) return null;
    return game;
  }
}

function idleStepResult(gameOver: boolean): TetrisStepResult {
  return {
    moved: false,
    lockedPiece: false,
    linesCleared: 0,
    gameEnded: gameOver,
    statisticsDelta: TETRIS_STATISTICS_NONE,
  };
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
