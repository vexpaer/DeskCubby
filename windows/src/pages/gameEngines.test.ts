import {
  actMines,
  actTetris,
  dealSpider,
  move2048,
  newGo,
  newMines,
  newSpider,
  newTetris,
  parseGame2048,
  parseGo,
  parseMines,
  parseSnake,
  parseSpider,
  serializeGame,
  passGo,
  playGo,
  tickSnake,
  turnSnake,
  type Game2048State,
  type GoState,
  type SnakeState,
} from "./gameEngines";

function goPosition(
  current: 1 | 2,
  stones: Array<[x: number, y: number, stone: 1 | 2]>,
  size: 9 | 13 | 19 = 9,
): GoState {
  const state = newGo(size);
  const board = [...state.board];
  for (const [x, y, stone] of stones) board[y * size + x] = stone;
  return { ...state, current, board };
}

describe("Windows game engines", () => {
  it("uses Android-compatible 2048 merge and statistic semantics", () => {
    const state: Game2048State = {
      size: 4,
      cells: [
        2, 2, 4, 4,
        0, 0, 0, 0,
        0, 0, 0, 0,
        0, 0, 0, 0,
      ],
      score: 0,
      undoHistory: [],
      winRecorded: false,
      lossRecorded: false,
    };
    const result = move2048(state, "LEFT");
    expect(result.changed).toBe(true);
    expect(result.state.cells.slice(0, 2)).toEqual([4, 8]);
    expect(result.state.score).toBe(12);
    expect(result.metrics?.increments).toMatchObject({
      moveAttempts: 1,
      effectiveMoves: 1,
      merges: 2,
    });
    expect(result.metrics?.increments).not.toHaveProperty("losses");
    expect(result.metrics?.maxima).toMatchObject({ highestTile: 8 });
    expect(parseGame2048(JSON.stringify(result.state), 4)).not.toBeNull();
  });

  it("counts a 2048 direction attempt even when the board does not move", () => {
    const state: Game2048State = {
      size: 4,
      cells: [2, 0, 0, 0, ...Array<number>(12).fill(0)],
      score: 0,
      undoHistory: [],
      winRecorded: false,
      lossRecorded: false,
    };
    const result = move2048(state, "LEFT");
    expect(result.changed).toBe(false);
    expect(result.metrics?.increments).toEqual({ moveAttempts: 1 });
  });

  it("records one snake collision loss and leaves later ticks idempotent", () => {
    const state: SnakeState = {
      w: 4,
      h: 4,
      snake: [[0, 0], [1, 0]],
      dir: "LEFT",
      movedDir: "LEFT",
      food: [3, 3],
      score: 0,
      over: false,
    };
    const ended = tickSnake(state);
    expect(ended.terminal).toBe(true);
    expect(ended.metrics?.increments).toMatchObject({ losses: 1 });
    expect(tickSnake(ended.state).metrics).toBeUndefined();
  });

  it("guards rapid snake turns against the last moved direction without changing Android JSON", () => {
    const state: SnakeState = {
      w: 6,
      h: 6,
      snake: [[3, 3], [2, 3], [1, 3]],
      dir: "RIGHT",
      movedDir: "RIGHT",
      food: [5, 5],
      score: 0,
      over: false,
    };
    const queuedUp = turnSnake(state, "UP");
    expect(turnSnake(queuedUp, "LEFT").dir).toBe("UP");
    const encoded = serializeGame("snake", queuedUp);
    expect(encoded).not.toContain("movedDir");
    expect(parseSnake(encoded)?.movedDir).toBe("UP");
  });

  it("keeps a custom minesweeper first reveal safe and round-trips the save", () => {
    const start = newMines(12, 8, 17);
    const result = actMines(start, 0, "reveal");
    expect(result.changed).toBe(true);
    expect(result.state.initialized).toBe(true);
    expect(result.state.mines).toHaveLength(17);
    expect(result.state.mines).not.toContain(0);
    expect(parseMines(JSON.stringify(result.state))).toEqual(result.state);
  });

  it("hard-drops and locks a real Tetris piece", () => {
    const result = actTetris(newTetris(), "hard");
    expect(result.changed).toBe(true);
    expect(result.metrics?.increments).toMatchObject({ piecesLocked: 1 });
    expect(result.state.board.filter(Boolean)).toHaveLength(4);
  });

  it("uses the full Android Spider v2 save with stock deals and undo history", () => {
    const start = newSpider();
    expect(start.columns).toHaveLength(10);
    expect(start.stock).toHaveLength(50);
    const dealt = dealSpider(start);
    expect(dealt.changed).toBe(true);
    expect(dealt.state.stock).toHaveLength(40);
    expect(dealt.state.history).toHaveLength(1);
    expect(dealt.metrics?.increments).toMatchObject({ spiderDeals: 1 });
    const json = serializeGame("spider", dealt.state);
    expect(JSON.parse(json).schemaVersion).toBe(2);
    expect(parseSpider(json)).toEqual(dealt.state);
  });

  it("matches Android Go capture, suicide and simple-ko rules", () => {
    const capture = goPosition(1, [
      [1, 1, 2], [0, 1, 1], [1, 0, 1], [2, 1, 1],
    ]);
    const captured = playGo(capture, 1, 2);
    expect(captured.accepted).toBe(true);
    expect(captured.captured).toBe(1);
    expect(captured.state.board[1 * 9 + 1]).toBe(0);
    expect(captured.state.capturedByBlack).toBe(1);
    expect(captured.metrics?.increments).toMatchObject({
      goMovesPlayed: 1,
      goStonesCaptured: 1,
    });

    const suicide = goPosition(2, [
      [1, 0, 1], [0, 1, 1], [2, 1, 1], [1, 2, 1],
    ]);
    expect(playGo(suicide, 1, 1)).toMatchObject({ accepted: false, error: "SUICIDE", state: suicide });

    const ko = goPosition(1, [
      [1, 0, 1], [0, 1, 1], [2, 1, 1], [1, 1, 2],
      [0, 2, 2], [2, 2, 2], [1, 3, 2],
    ]);
    const first = playGo(ko, 1, 2);
    expect(first.accepted).toBe(true);
    expect(playGo(first.state, 1, 1)).toMatchObject({ accepted: false, error: "KO" });
  });

  it("round-trips Go ko history and finishes after two consecutive passes", () => {
    const first = passGo(newGo(13));
    expect(first.state.finished).toBe(false);
    expect(first.metrics?.increments).toMatchObject({ goPasses: 1, goGamesCompleted: 0 });
    const second = passGo(first.state);
    expect(second.terminal).toBe(true);
    expect(second.state.finished).toBe(true);
    expect(second.metrics?.increments).toMatchObject({ goPasses: 1, goGamesCompleted: 1 });
    expect(playGo(second.state, 6, 6).error).toBe("GAME_FINISHED");

    const restored = parseGo(serializeGame("go", second.state));
    expect(restored).toEqual(second.state);
    expect(parseGo('{"v":1,"size":7}')).toBeNull();
  });
});
