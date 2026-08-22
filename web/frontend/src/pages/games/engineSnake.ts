/**
 * Pure-TypeScript snake engine — faithful port of
 * android/app/src/main/java/com/deskcubby/app/games/SnakeGame.kt.
 *
 * Wall-bounded 16×16 grid (default), one food, +10 points per food, no direct
 * 180-degree reversal of the last moved direction, tail vacates before the self
 * check unless growing, filling the whole board is completion (BOARD_FILLED) and
 * not a loss. JSON save format is byte-compatible with the Kotlin engine.
 */

export type SnakeDirection = "UP" | "DOWN" | "LEFT" | "RIGHT";

export interface SnakeCell {
  x: number;
  y: number;
}

/** Why a tick ended the round. Filling the board is completion, not a collision loss. */
export type SnakeEndReason = "WALL" | "SELF_COLLISION" | "BOARD_FILLED";

/** Aggregate-statistics increments caused by one tick. */
export interface SnakeStatisticsDelta {
  foodEaten: number;
  /** Candidate for a lifetime maximum; 0 means this tick needs no maximum update. */
  maxLength: number;
  losses: number;
}

export interface SnakeTickResult {
  moved: boolean;
  ateFood: boolean;
  canContinue: boolean;
  endReason: SnakeEndReason | null;
  statisticsDelta: SnakeStatisticsDelta;
}

const DIRECTION_DELTAS: Record<SnakeDirection, { dx: number; dy: number }> = {
  UP: { dx: 0, dy: -1 },
  DOWN: { dx: 0, dy: 1 },
  LEFT: { dx: -1, dy: 0 },
  RIGHT: { dx: 1, dy: 0 },
};

function oppositeOf(direction: SnakeDirection): SnakeDirection {
  switch (direction) {
    case "UP":
      return "DOWN";
    case "DOWN":
      return "UP";
    case "LEFT":
      return "RIGHT";
    case "RIGHT":
      return "LEFT";
  }
}

const SNAKE_STATISTICS_NONE: SnakeStatisticsDelta = { foodEaten: 0, maxLength: 0, losses: 0 };

export const SNAKE_DEFAULT_WIDTH = 16;
export const SNAKE_DEFAULT_HEIGHT = 16;
export const SNAKE_EAT_SCORE = 10;
const SNAKE_MIN_SIZE = 4;

function keyOf(cell: SnakeCell): string {
  return `${cell.x},${cell.y}`;
}

function startingBody(width: number, height: number): SnakeCell[] {
  const centerX = Math.floor(width / 2);
  const centerY = Math.floor(height / 2);
  const length = Math.min(3, centerX + 1);
  const result: SnakeCell[] = [];
  for (let i = 0; i < length; i++) result.push({ x: centerX - i, y: centerY });
  return result;
}

interface RestoredSnakeState {
  body: SnakeCell[];
  direction: SnakeDirection;
  food: SnakeCell;
  score: number;
  isGameOver: boolean;
}

export class SnakeGame {
  readonly width: number;
  readonly height: number;
  private body: SnakeCell[];
  private movedDirection: SnakeDirection;
  private random: () => number;

  /** Direction that will be applied on the next tick. */
  direction: SnakeDirection;
  food: SnakeCell;
  score: number;
  isGameOver: boolean;

  /**
   * Starts a fresh game: a short snake in the middle moving right, with one food
   * spawned. `state` is reserved for {@link SnakeGame.fromJson}; callers always use
   * the three-argument form.
   */
  constructor(
    width: number = SNAKE_DEFAULT_WIDTH,
    height: number = SNAKE_DEFAULT_HEIGHT,
    random: () => number = Math.random,
    state?: RestoredSnakeState,
  ) {
    this.width = width;
    this.height = height;
    if (state) {
      this.body = state.body.map((cell) => ({ ...cell }));
      this.direction = state.direction;
      this.movedDirection = state.direction;
      this.food = { ...state.food };
      this.score = state.score;
      this.isGameOver = state.isGameOver;
      this.random = random;
      return;
    }
    this.body = startingBody(width, height);
    this.direction = "RIGHT";
    this.movedDirection = "RIGHT";
    this.food = { x: 0, y: 0 };
    this.score = 0;
    this.isGameOver = false;
    this.random = random;
    this.food = this.randomEmptyCell() ?? this.food;
  }

  /** Head-first snapshot of the snake body. */
  get snake(): SnakeCell[] {
    return this.body.map((cell) => ({ ...cell }));
  }

  /** Requests a new heading; a direct reversal of the last moved direction is ignored. */
  setDirection(newDirection: SnakeDirection): void {
    if (this.isGameOver) return;
    if (this.body.length > 1 && newDirection === oppositeOf(this.movedDirection)) return;
    this.direction = newDirection;
  }

  /**
   * Advances one step: hitting a wall or the snake's own body ends the game, eating
   * food adds EAT_SCORE points and grows the snake. Returns false when the game is over.
   */
  tick(): boolean {
    return this.tickWithResult().canContinue;
  }

  /**
   * Advances one step and returns the event detail needed for durable aggregate
   * statistics. Calling this after game-over is an idempotent no-op and never emits
   * another loss.
   */
  tickWithResult(): SnakeTickResult {
    if (this.isGameOver) return idleTickResult();
    this.movedDirection = this.direction;
    const head = this.body[0];
    const delta = DIRECTION_DELTAS[this.direction];
    const next: SnakeCell = { x: head.x + delta.dx, y: head.y + delta.dy };
    if (next.x < 0 || next.x >= this.width || next.y < 0 || next.y >= this.height) {
      this.isGameOver = true;
      return collisionResult("WALL", this.body.length);
    }
    const growing = next.x === this.food.x && next.y === this.food.y;
    const hitIndex = this.body.findIndex((cell) => cell.x === next.x && cell.y === next.y);
    // The tail cell vacates this tick unless the snake grows, so it does not block.
    if (hitIndex >= 0 && (growing || hitIndex < this.body.length - 1)) {
      this.isGameOver = true;
      return collisionResult("SELF_COLLISION", this.body.length);
    }
    this.body.unshift(next);
    let endReason: SnakeEndReason | null = null;
    if (growing) {
      this.score += SNAKE_EAT_SCORE;
      const nextFood = this.randomEmptyCell();
      if (nextFood === null) {
        this.isGameOver = true;
        endReason = "BOARD_FILLED";
      } else {
        this.food = nextFood;
      }
    } else {
      this.body.pop();
    }
    return {
      moved: true,
      ateFood: growing,
      canContinue: !this.isGameOver,
      endReason,
      statisticsDelta: growing
        ? { foodEaten: 1, maxLength: this.body.length, losses: 0 }
        : SNAKE_STATISTICS_NONE,
    };
  }

  /** Serializes the complete restorable state as JSON (byte-compatible with SnakeGame.kt). */
  toJson(): string {
    const parts: string[] = [];
    parts.push(`{"w":${this.width},"h":${this.height},"snake":[`);
    this.body.forEach((cell, index) => {
      if (index > 0) parts.push(",");
      parts.push(`[${cell.x},${cell.y}]`);
    });
    parts.push(
      `],"dir":"${this.direction}"`,
      `,"food":[${this.food.x},${this.food.y}]`,
      `,"score":${this.score}`,
      `,"over":${this.isGameOver}}`,
    );
    return parts.join("");
  }

  private randomEmptyCell(): SnakeCell | null {
    const occupied = new Set(this.body.map(keyOf));
    const empty: SnakeCell[] = [];
    for (let y = 0; y < this.height; y++) {
      for (let x = 0; x < this.width; x++) {
        if (!occupied.has(`${x},${y}`)) empty.push({ x, y });
      }
    }
    if (empty.length === 0) return null;
    return empty[Math.floor(this.random() * empty.length)];
  }

  /** Restores a game from toJson() output. Returns null (never throws) on invalid input. */
  static fromJson(json: string, random: () => number = Math.random): SnakeGame | null {
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
    if (width === null || height === null || width < SNAKE_MIN_SIZE || height < SNAKE_MIN_SIZE) return null;
    const rawSnake = map["snake"];
    if (!Array.isArray(rawSnake) || rawSnake.length === 0 || rawSnake.length > width * height) return null;
    const bodyCells: SnakeCell[] = [];
    const seen = new Set<string>();
    for (const entry of rawSnake) {
      const pair = intListOf(entry);
      if (pair === null || pair.length !== 2) return null;
      const cell: SnakeCell = { x: pair[0], y: pair[1] };
      if (cell.x < 0 || cell.x >= width || cell.y < 0 || cell.y >= height) return null;
      const key = keyOf(cell);
      if (seen.has(key)) return null;
      seen.add(key);
      bodyCells.push(cell);
    }
    const directionName = stringOf(map["dir"]);
    const direction = (["UP", "DOWN", "LEFT", "RIGHT"] as SnakeDirection[]).find((d) => d === directionName);
    if (!direction) return null;
    const foodPair = intListOf(map["food"]);
    if (foodPair === null || foodPair.length !== 2) return null;
    const food: SnakeCell = { x: foodPair[0], y: foodPair[1] };
    if (food.x < 0 || food.x >= width || food.y < 0 || food.y >= height) return null;
    const score = intOf(map["score"]);
    if (score === null || score < 0) return null;
    const over = boolOf(map["over"]);
    if (over === null) return null;
    return new SnakeGame(width, height, random, {
      body: bodyCells,
      direction,
      food,
      score,
      isGameOver: over,
    });
  }
}

function idleTickResult(): SnakeTickResult {
  return { moved: false, ateFood: false, canContinue: false, endReason: null, statisticsDelta: SNAKE_STATISTICS_NONE };
}

function collisionResult(reason: SnakeEndReason, length: number): SnakeTickResult {
  return {
    moved: false,
    ateFood: false,
    canContinue: false,
    endReason: reason,
    statisticsDelta: { foodEaten: 0, maxLength: length, losses: 1 },
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

function stringOf(value: unknown): string | null {
  return typeof value === "string" ? value : null;
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
