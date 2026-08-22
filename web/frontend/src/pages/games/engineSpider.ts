/**
 * Pure-TypeScript one-suit Spider Solitaire engine — faithful port of
 * android/app/src/main/java/com/deskcubby/app/games/SpiderSolitaireGame.kt.
 *
 * Ten columns, initial 54-card layout, five stock deals of ten face-up cards,
 * same-suit descending run selection/moves, automatic K→A collection (+100),
 * 500 starting score with −1 per move/deal, single-step undo history capped at
 * 100 snapshots, an explicit abandon action for counting losses, and strict JSON
 * save validation (schemaVersion 2 incl. history).
 */

export interface SpiderCard {
  id: number;
  rank: number;
  suit: number;
  faceUp: boolean;
}

export type SpiderAction = "MOVE" | "DEAL_STOCK" | "UNDO" | "ABANDON";

/** Lifetime-statistics increments caused by exactly one accepted player action. */
export interface SpiderStatisticsDelta {
  cardMoves: number;
  deals: number;
  undos: number;
  wins: number;
  losses: number;
}

export function spiderDeltaIsEmpty(delta: SpiderStatisticsDelta): boolean {
  return delta.cardMoves === 0 && delta.deals === 0 && delta.undos === 0 && delta.wins === 0 && delta.losses === 0;
}

export interface SpiderActionResult {
  action: SpiderAction;
  changed: boolean;
  statisticsDelta: SpiderStatisticsDelta;
}

interface SpiderSnapshot {
  columns: SpiderCard[][];
  stock: SpiderCard[];
  completedRuns: number;
  score: number;
  moves: number;
}

interface RestoredSpiderState {
  columns: SpiderCard[][];
  stock: SpiderCard[];
  completedRuns: number;
  score: number;
  moves: number;
  hasPlayedAction: boolean;
  outcomeRecorded: boolean;
  history: SpiderSnapshot[];
}

const COLUMN_COUNT = 10;
const TOTAL_RUNS = 8;
const RUN_LENGTH = 13;
const START_SCORE = 500;
const MOVE_COST = 1;
const COMPLETED_RUN_SCORE = 100;
const MAX_UNDO = 100;
const SAVE_SCHEMA_VERSION = 2;
const MAX_SAVE_CHARS = 1_000_000;

function emptyDelta(): SpiderStatisticsDelta {
  return { cardMoves: 0, deals: 0, undos: 0, wins: 0, losses: 0 };
}

function unchanged(action: SpiderAction): SpiderActionResult {
  return { action, changed: false, statisticsDelta: emptyDelta() };
}

function copyCard(card: SpiderCard): SpiderCard {
  return { ...card };
}

function copyColumn(column: SpiderCard[]): SpiderCard[] {
  return column.map(copyCard);
}

function shuffledDeck(random: () => number): SpiderCard[] {
  const deck: SpiderCard[] = [];
  for (let deckIndex = 0; deckIndex < TOTAL_RUNS; deckIndex++) {
    for (let rank = 1; rank <= RUN_LENGTH; rank++) {
      deck.push({ id: deckIndex * RUN_LENGTH + rank - 1, rank, suit: 0, faceUp: false });
    }
  }
  // Fisher-Yates shuffle driven by the injected random, mirroring shuffled(random).
  for (let i = deck.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    const tmp = deck[i];
    deck[i] = deck[j];
    deck[j] = tmp;
  }
  return deck;
}

function deal(random: () => number): { columns: SpiderCard[][]; stock: SpiderCard[] } {
  const deck = shuffledDeck(random);
  const columns: SpiderCard[][] = Array.from({ length: COLUMN_COUNT }, () => [] as SpiderCard[]);
  for (let index = 0; index < 54; index++) {
    columns[index % COLUMN_COUNT].push({ ...deck[index] });
  }
  for (const column of columns) {
    if (column.length > 0) column[column.length - 1].faceUp = true;
  }
  return { columns, stock: deck.slice(54).map(copyCard) };
}

export class SpiderSolitaireGame {
  private readonly columns: SpiderCard[][];
  private readonly stock: SpiderCard[];
  private readonly history: SpiderSnapshot[];

  completedRuns: number;
  score: number;
  moves: number;

  /**
   * Whether this round has ever accepted a card move or stock deal. Unlike [moves],
   * this is intentionally not rewound by undo; it distinguishes abandoning a played
   * round from replacing an untouched deal. Round state, not a lifetime counter.
   */
  hasPlayedAction: boolean;

  /** Single-round idempotency marker; prevents win/loss replay after undo or restore. */
  outcomeRecorded: boolean;

  constructor(random: () => number);
  constructor(state: RestoredSpiderState);
  constructor(arg?: (() => number) | RestoredSpiderState) {
    if (arg && typeof arg !== "function") {
      this.columns = arg.columns.map(copyColumn);
      this.stock = arg.stock.map(copyCard);
      this.history = arg.history.map((snapshot) => ({
        columns: snapshot.columns.map(copyColumn),
        stock: snapshot.stock.map(copyCard),
        completedRuns: snapshot.completedRuns,
        score: snapshot.score,
        moves: snapshot.moves,
      }));
      this.completedRuns = arg.completedRuns;
      this.score = arg.score;
      this.moves = arg.moves;
      this.hasPlayedAction = arg.hasPlayedAction;
      this.outcomeRecorded = arg.outcomeRecorded;
      return;
    }
    const random = (arg as (() => number) | undefined) ?? Math.random;
    const dealt = deal(random);
    this.columns = dealt.columns;
    this.stock = dealt.stock;
    this.history = [];
    this.completedRuns = 0;
    this.score = START_SCORE;
    this.moves = 0;
    this.hasPlayedAction = false;
    this.outcomeRecorded = false;
  }

  get isWon(): boolean {
    return this.completedRuns === TOTAL_RUNS;
  }

  get canUndo(): boolean {
    return this.history.length > 0;
  }

  get stockDealsRemaining(): number {
    return this.stock.length / COLUMN_COUNT;
  }

  get canDealStock(): boolean {
    return (
      !this.isWon &&
      this.stock.length >= COLUMN_COUNT &&
      this.columns.every((column) => column.length > 0)
    );
  }

  column(index: number): SpiderCard[] {
    return copyColumn(this.columns[index]);
  }

  canSelect(column: number, cardIndex: number): boolean {
    const cards = this.columns[column];
    if (!cards || cardIndex < 0 || cardIndex >= cards.length || !cards[cardIndex].faceUp) return false;
    for (let index = cardIndex; index < cards.length - 1; index++) {
      const upper = cards[index];
      const lower = cards[index + 1];
      if (!lower.faceUp || upper.suit !== lower.suit || upper.rank !== lower.rank + 1) return false;
    }
    return true;
  }

  moveWithResult(fromColumn: number, cardIndex: number, toColumn: number): SpiderActionResult {
    if (this.isWon || fromColumn === toColumn || !this.canSelect(fromColumn, cardIndex)) {
      return unchanged("MOVE");
    }
    const source = this.columns[fromColumn];
    const target = this.columns[toColumn];
    if (!source || !target) return unchanged("MOVE");
    const movingFirst = source[cardIndex];
    if (target.length > 0 && target[target.length - 1].rank !== movingFirst.rank + 1) {
      return unchanged("MOVE");
    }
    this.rememberSnapshot();
    const moving = source.slice(cardIndex).map(copyCard);
    source.length = cardIndex;
    for (const card of moving) target.push(card);
    this.revealTop(source);
    this.score = Math.max(0, this.score - MOVE_COST);
    this.moves += 1;
    this.hasPlayedAction = true;
    this.removeCompletedRuns(fromColumn);
    this.removeCompletedRuns(toColumn);
    const wonThisAction = this.isWon && !this.outcomeRecorded;
    if (wonThisAction) this.outcomeRecorded = true;
    return {
      action: "MOVE",
      changed: true,
      statisticsDelta: { ...emptyDelta(), cardMoves: 1, wins: wonThisAction ? 1 : 0 },
    };
  }

  dealStockWithResult(): SpiderActionResult {
    if (!this.canDealStock) return unchanged("DEAL_STOCK");
    this.rememberSnapshot();
    for (let columnIndex = 0; columnIndex < COLUMN_COUNT; columnIndex++) {
      const card = this.stock.shift() as SpiderCard;
      this.columns[columnIndex].push({ ...card, faceUp: true });
    }
    this.score = Math.max(0, this.score - MOVE_COST);
    this.moves += 1;
    this.hasPlayedAction = true;
    for (let columnIndex = 0; columnIndex < COLUMN_COUNT; columnIndex++) {
      this.removeCompletedRuns(columnIndex);
    }
    const wonThisAction = this.isWon && !this.outcomeRecorded;
    if (wonThisAction) this.outcomeRecorded = true;
    return {
      action: "DEAL_STOCK",
      changed: true,
      statisticsDelta: { ...emptyDelta(), deals: 1, wins: wonThisAction ? 1 : 0 },
    };
  }

  undoWithResult(): SpiderActionResult {
    const previous = this.history.pop();
    if (!previous) return unchanged("UNDO");
    this.columns.length = 0;
    for (const column of previous.columns) this.columns.push(copyColumn(column));
    this.stock.length = 0;
    for (const card of previous.stock) this.stock.push(copyCard(card));
    this.completedRuns = previous.completedRuns;
    this.score = previous.score;
    this.moves = previous.moves;
    return {
      action: "UNDO",
      changed: true,
      statisticsDelta: { ...emptyDelta(), undos: 1 },
    };
  }

  /**
   * Explicitly abandons this round before the caller replaces it with a new deal.
   * Merely leaving the page must not call this: an in-progress save is not a loss.
   * Untouched deals and already-recorded outcomes produce no increment.
   */
  abandonWithResult(): SpiderActionResult {
    if (!this.hasPlayedAction || this.isWon || this.outcomeRecorded) return unchanged("ABANDON");
    this.outcomeRecorded = true;
    return {
      action: "ABANDON",
      changed: true,
      statisticsDelta: { ...emptyDelta(), losses: 1 },
    };
  }

  toJson(): string {
    const parts: string[] = [];
    parts.push(`{"schemaVersion":${SAVE_SCHEMA_VERSION}`);
    parts.push(`,"columns":[`);
    this.columns.forEach((column, columnIndex) => {
      if (columnIndex > 0) parts.push(",");
      appendCards(parts, column);
    });
    parts.push(`],"stock":`);
    appendCards(parts, this.stock);
    parts.push(`,"completed":${this.completedRuns}`);
    parts.push(`,"score":${this.score}`);
    parts.push(`,"moves":${this.moves}`);
    parts.push(`,"hasPlayedAction":${this.hasPlayedAction}`);
    parts.push(`,"outcomeRecorded":${this.outcomeRecorded}`);
    parts.push(`,"history":[`);
    this.history.forEach((snapshot, index) => {
      if (index > 0) parts.push(",");
      appendSnapshot(parts, snapshot);
    });
    parts.push(`]}`);
    return parts.join("");
  }

  private removeCompletedRuns(columnIndex: number): void {
    const column = this.columns[columnIndex];
    while (column.length >= RUN_LENGTH) {
      const tail = column.slice(column.length - RUN_LENGTH);
      if (!tail.every((card) => card.faceUp)) return;
      if (tail[0].rank !== 13 || tail[tail.length - 1].rank !== 1) return;
      if (new Set(tail.map((card) => card.suit)).size !== 1) return;
      for (let i = 0; i < tail.length - 1; i++) {
        if (tail[i].rank !== tail[i + 1].rank + 1) return;
      }
      column.length = column.length - RUN_LENGTH;
      this.completedRuns += 1;
      this.score += COMPLETED_RUN_SCORE;
      this.revealTop(column);
    }
  }

  private revealTop(column: SpiderCard[]): void {
    if (column.length > 0) column[column.length - 1].faceUp = true;
  }

  private rememberSnapshot(): void {
    if (this.history.length >= MAX_UNDO) this.history.shift();
    this.history.push({
      columns: this.columns.map(copyColumn),
      stock: this.stock.map(copyCard),
      completedRuns: this.completedRuns,
      score: this.score,
      moves: this.moves,
    });
  }

  /** Restores a game from toJson() output. Returns null (never throws) on invalid input. */
  static fromJson(json: string): SpiderSolitaireGame | null {
    if (json.length < 2 || json.length > MAX_SAVE_CHARS) return null;
    let parsed: unknown;
    try {
      parsed = JSON.parse(json);
    } catch {
      return null;
    }
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return null;
    const map = parsed as Record<string, unknown>;
    const schemaVersion = map["schemaVersion"] === undefined ? 1 : intOf(map["schemaVersion"]);
    if (schemaVersion === null || schemaVersion < 1 || schemaVersion > SAVE_SCHEMA_VERSION) return null;

    const current = decodeSnapshot(map);
    if (current === null) return null;

    const hasPlayedAction = "hasPlayedAction" in map ? boolOf(map["hasPlayedAction"]) : current.moves > 0;
    if (hasPlayedAction === null) return null;
    // A restored completed legacy round must not emit another win.
    const outcomeRecorded =
      "outcomeRecorded" in map ? boolOf(map["outcomeRecorded"]) : current.completedRuns === TOTAL_RUNS;
    if (outcomeRecorded === null) return null;

    let rawHistory: unknown[];
    if (!("history" in map) || map["history"] === undefined) {
      rawHistory = [];
    } else if (schemaVersion < SAVE_SCHEMA_VERSION) {
      return null;
    } else {
      if (!Array.isArray(map["history"])) return null;
      rawHistory = map["history"];
    }
    if (rawHistory.length > MAX_UNDO) return null;
    const restoredHistory: SpiderSnapshot[] = [];
    for (const item of rawHistory) {
      const snapshot = decodeSnapshot(item);
      if (snapshot === null) return null;
      restoredHistory.push(snapshot);
    }

    return new SpiderSolitaireGame({
      columns: current.columns,
      stock: current.stock,
      completedRuns: current.completedRuns,
      score: current.score,
      moves: current.moves,
      hasPlayedAction,
      outcomeRecorded,
      history: restoredHistory,
    });
  }
}

function appendCards(parts: string[], cards: SpiderCard[]): void {
  parts.push("[");
  cards.forEach((card, index) => {
    if (index > 0) parts.push(",");
    parts.push(`[${card.id},${card.rank},${card.suit},${card.faceUp}]`);
  });
  parts.push("]");
}

function appendSnapshot(parts: string[], snapshot: SpiderSnapshot): void {
  parts.push(`{"columns":[`);
  snapshot.columns.forEach((column, columnIndex) => {
    if (columnIndex > 0) parts.push(",");
    appendCards(parts, column);
  });
  parts.push(`],"stock":`);
  appendCards(parts, snapshot.stock);
  parts.push(`,"completed":${snapshot.completedRuns}`);
  parts.push(`,"score":${snapshot.score}`);
  parts.push(`,"moves":${snapshot.moves}}`);
}

function decodeSnapshot(raw: unknown): SpiderSnapshot | null {
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) return null;
  const state = raw as Record<string, unknown>;
  if (!Array.isArray(state["columns"]) || (state["columns"] as unknown[]).length !== COLUMN_COUNT) return null;
  const columns: SpiderCard[][] = [];
  for (const item of state["columns"] as unknown[]) {
    const column = decodeCards(item);
    if (column === null) return null;
    columns.push(column);
  }
  const stock = decodeCards(state["stock"]);
  if (stock === null) return null;
  const completed = intOf(state["completed"]);
  const score = intOf(state["score"]);
  const moves = intOf(state["moves"]);
  if (completed === null || score === null || moves === null) return null;
  if (
    completed < 0 ||
    completed > TOTAL_RUNS ||
    score < 0 ||
    moves < 0 ||
    stock.length % COLUMN_COUNT !== 0 ||
    stock.some((card) => card.faceUp)
  ) {
    return null;
  }
  const allCards = [...columns.flat(), ...stock];
  if (new Set(allCards.map((card) => card.id)).size !== allCards.length) return null;
  if (allCards.length + completed * RUN_LENGTH !== TOTAL_RUNS * RUN_LENGTH) return null;
  for (const column of columns) {
    if (column.length > 0 && !column[column.length - 1].faceUp) return null;
    let seenFaceUp = false;
    for (const card of column) {
      if (card.faceUp) seenFaceUp = true;
      else if (seenFaceUp) return null;
    }
  }
  return { columns, stock, completedRuns: completed, score, moves };
}

function decodeCards(raw: unknown): SpiderCard[] | null {
  if (!Array.isArray(raw)) return null;
  if (raw.length > TOTAL_RUNS * RUN_LENGTH) return null;
  const cards: SpiderCard[] = [];
  for (const item of raw) {
    if (!Array.isArray(item) || item.length !== 4) return null;
    const id = intOf(item[0]);
    const rank = intOf(item[1]);
    const suit = intOf(item[2]);
    const faceUp = boolOf(item[3]);
    if (
      id === null ||
      rank === null ||
      suit === null ||
      faceUp === null ||
      id < 0 ||
      id >= TOTAL_RUNS * RUN_LENGTH ||
      rank < 1 ||
      rank > RUN_LENGTH ||
      rank !== (id % RUN_LENGTH) + 1 ||
      suit !== 0
    ) {
      return null;
    }
    cards.push({ id, rank, suit, faceUp });
  }
  return cards;
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
