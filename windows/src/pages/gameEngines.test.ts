import {
  actMines,
  actTetris,
  dealSpider,
  move2048,
  newMines,
  newSpider,
  newTetris,
  parseGame2048,
  parseMines,
  parseSnake,
  parseSpider,
  serializeGame,
  tickSnake,
  turnSnake,
  type Game2048State,
  type SnakeState,
} from "./gameEngines";

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
    expect(result.metrics?.increments).toMatchObject({ effectiveMoves: 1, merges: 2 });
    expect(result.metrics?.maxima).toMatchObject({ highestTile: 8 });
    expect(parseGame2048(JSON.stringify(result.state), 4)).not.toBeNull();
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
});
