/**
 * 蜘蛛纸牌（一花色）SpiderPage (/games/spider) — web port of the Android
 * SpiderSolitairePage in ui/games/AdditionalGames.kt on top of engineSpider.ts
 * (README_for_ai.md §13 蜘蛛纸牌):
 * - ten-column one-suit board, 发牌 n stock button (disabled while any column is empty),
 *   single-step 撤回, 重开 with 重开蜘蛛纸牌？ confirmation (abandon counts a loss only
 *   for a played round);
 * - pointer drag & drop plus the accessible click-select-then-click-destination flow
 *   (failed moves reselect the tapped runnable like Android);
 * - K→A runs collect automatically; 完成蜘蛛纸牌 win dialog with 本局 n 分，最高 m 分;
 * - portrait small screens show a rotate-to-landscape hint overlay instead of locking
 *   orientation;
 * - persistence identical to the other pages: serialized PUT queue onto
 *   /api/games/states/spider after every accepted action + background/unmount,
 *   statistics deltas posted with the Kotlin metric keys.
 */
import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowLeft, Layers, RefreshCw, RotateCcw } from "lucide-react";
import { ApiClientError, apiGet, apiSend } from "../../api/client";
import { tr } from "../../i18n/tr";
import { ConfirmDialog, Snackbar, Spinner, TopBar, useSnackbar } from "../../components/ui";
import { SpiderSolitaireGame, type SpiderActionResult, type SpiderCard } from "./engineSpider";

const GAME_ID = "spider";
const COLUMN_COUNT = 10;
const DRAG_THRESHOLD_PX = 8;

interface GameStateDto {
  highScore?: number;
  saveJson?: string | null;
}

async function fetchGameState(): Promise<{ highScore: number; saveJson: string | null }> {
  try {
    const data = await apiGet<GameStateDto>(`/api/games/states/${encodeURIComponent(GAME_ID)}`);
    return {
      highScore: typeof data?.highScore === "number" ? data.highScore : 0,
      saveJson: typeof data?.saveJson === "string" ? data.saveJson : null,
    };
  } catch (error) {
    if (error instanceof ApiClientError && error.status === 404) return { highScore: 0, saveJson: null };
    throw error;
  }
}

function postStat(metricKey: string, value: number): void {
  if (value <= 0) return;
  void apiSend("/api/games/statistics", "POST", { gameId: GAME_ID, metricKey, value }).catch(() => undefined);
}

function postDelta(delta: SpiderActionResult["statisticsDelta"]): void {
  postStat("spiderCardMoves", delta.cardMoves);
  postStat("spiderDeals", delta.deals);
  postStat("spiderUndos", delta.undos);
  postStat("wins", delta.wins);
  postStat("losses", delta.losses);
}

function spiderRank(rank: number): string {
  switch (rank) {
    case 1:
      return "A";
    case 11:
      return "J";
    case 12:
      return "Q";
    case 13:
      return "K";
    default:
      return String(rank);
  }
}

interface Selection {
  column: number;
  cardIndex: number;
}

interface SpiderDragState {
  column: number;
  cardIndex: number;
  startX: number;
  startY: number;
  x: number;
  y: number;
  moved: boolean;
}

export default function SpiderPage() {
  const navigate = useNavigate();
  const [snack, showSnack] = useSnackbar();

  const engineRef = useRef<SpiderSolitaireGame | null>(null);
  const [, setTick] = useState(0);
  const bump = useCallback(() => setTick((t) => (t + 1) % 1_000_000_000), []);

  const [loaded, setLoaded] = useState(false);
  const loadedRef = useRef(false);
  const [selected, setSelected] = useState<Selection | null>(null);

  const highScoreRef = useRef(0);
  const scoreRecordedRef = useRef(false);
  const saveQueueRef = useRef<Promise<void>>(Promise.resolve());

  const [restartOpen, setRestartOpen] = useState(false);
  const [portraitHintDismissed, setPortraitHintDismissed] = useState(false);
  const [portraitSmall, setPortraitSmall] = useState(false);

  const enqueue = useCallback((fn: () => Promise<void>) => {
    const run = saveQueueRef.current.then(fn).catch(() => undefined);
    saveQueueRef.current = run;
    return run;
  }, []);

  const saveProgressNow = useCallback(() => {
    const current = engineRef.current;
    if (!current || current.isWon) return;
    const json = current.toJson();
    void enqueue(() =>
      apiSend(`/api/games/states/${encodeURIComponent(GAME_ID)}`, "PUT", { highScore: highScoreRef.current, saveJson: json }).then(() => undefined),
    );
  }, [enqueue]);

  const recordScoreNow = useCallback(
    (game: SpiderSolitaireGame) => {
      highScoreRef.current = Math.max(highScoreRef.current, game.score);
      void enqueue(() =>
        apiSend(`/api/games/states/${encodeURIComponent(GAME_ID)}`, "PUT", { highScore: highScoreRef.current, saveJson: null }).then(() => undefined),
      );
    },
    [enqueue],
  );

  /** Mirrors AdditionalGames.saveOrFinish. */
  const saveOrFinish = useCallback(() => {
    const current = engineRef.current;
    if (!current) return;
    if (current.isWon) recordScoreNow(current);
    else saveProgressNow();
  }, [recordScoreNow, saveProgressNow]);
  const saveOrFinishRef = useRef(saveOrFinish);
  useEffect(() => {
    saveOrFinishRef.current = saveOrFinish;
  }, [saveOrFinish]);

  // Load save on mount (corrupt saves degrade to a fresh deal).
  useEffect(() => {
    let cancelled = false;
    loadedRef.current = false;
    (async () => {
      let restored: SpiderSolitaireGame | null = null;
      try {
        const state = await fetchGameState();
        if (cancelled) return;
        highScoreRef.current = state.highScore;
        restored = state.saveJson ? SpiderSolitaireGame.fromJson(state.saveJson) : null;
      } catch {
        restored = null;
      }
      if (cancelled) return;
      engineRef.current = restored ?? new SpiderSolitaireGame(Math.random);
      scoreRecordedRef.current = engineRef.current.isWon && engineRef.current.outcomeRecorded;
      loadedRef.current = true;
      setLoaded(true);
      bump();
    })();
    return () => {
      cancelled = true;
    };
  }, [bump]);

  // Persist on background/unmount; leaving without finishing is not a loss.
  useEffect(() => {
    const onVisibility = () => {
      if (document.hidden) saveOrFinishRef.current();
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      saveOrFinishRef.current();
    };
  }, []);

  // Portrait + small screen ⇒ suggest rotating (web cannot force-orient like Android).
  useEffect(() => {
    const update = () => {
      const portrait = window.matchMedia("(orientation: portrait)").matches;
      const small = window.matchMedia("(max-width: 760px)").matches;
      setPortraitSmall(portrait && small);
    };
    update();
    window.addEventListener("resize", update);
    window.addEventListener("orientationchange", update);
    return () => {
      window.removeEventListener("resize", update);
      window.removeEventListener("orientationchange", update);
    };
  }, []);

  // Record the high score exactly once per completed deal.
  const engine = engineRef.current;
  const isWon = engine?.isWon ?? false;
  useEffect(() => {
    const current = engineRef.current;
    if (!loaded || !current || !current.isWon || scoreRecordedRef.current) return;
    scoreRecordedRef.current = true;
    recordScoreNow(current);
  }, [loaded, isWon, recordScoreNow, engine]);

  /** Applies an accepted action: statistics deltas + immediate save (mirrors Android). */
  const applyAction = useCallback(
    (result: SpiderActionResult): boolean => {
      if (!result.changed) return false;
      postDelta(result.statisticsDelta);
      bump();
      saveOrFinish();
      return true;
    },
    [bump, saveOrFinish],
  );

  const exitToList = useCallback(() => {
    saveOrFinishRef.current();
    navigate("/games");
  }, [navigate]);

  const undo = useCallback(() => {
    const current = engineRef.current;
    if (!current) return;
    if (applyAction(current.undoWithResult())) setSelected(null);
  }, [applyAction]);

  const dealStock = useCallback(() => {
    const current = engineRef.current;
    if (!current) return;
    if (applyAction(current.dealStockWithResult())) setSelected(null);
  }, [applyAction]);

  const confirmRestart = useCallback(() => {
    const current = engineRef.current;
    setRestartOpen(false);
    if (!current) return;
    // Only an actually played round counts as a loss when replaced.
    const abandoned = current.abandonWithResult();
    if (abandoned.changed) postDelta(abandoned.statisticsDelta);
    engineRef.current = new SpiderSolitaireGame(Math.random);
    setSelected(null);
    scoreRecordedRef.current = false;
    void enqueue(() =>
      apiSend(`/api/games/states/${encodeURIComponent(GAME_ID)}`, "PUT", { clearSave: true }).then(() => undefined),
    );
    bump();
  }, [bump, enqueue]);

  const startNewDeal = useCallback(() => {
    engineRef.current = new SpiderSolitaireGame(Math.random);
    setSelected(null);
    scoreRecordedRef.current = false;
    bump();
  }, [bump]);

  if (!loaded) {
    return (
      <div className="dc-page">
        <TopBar title={tr("蜘蛛纸牌 · 一花色", "Spider · One suit")} />
        <Spinner />
      </div>
    );
  }

  const current = engineRef.current;
  if (!current) return null;

  return (
    <div className="dc-page dc-col" style={{ maxWidth: 1400, margin: "0 auto", width: "100%", minHeight: "100%" }}>
      <Snackbar message={snack} />
      <TopBar
        title={tr("蜘蛛纸牌 · 一花色", "Spider · One suit")}
        back
        onBack={exitToList}
        actions={
          <>
            <span className="dc-chip">{tr(`分数 ${current.score}`, `Score ${current.score}`)}</span>
            <span className="dc-chip">{tr(`完成 ${current.completedRuns}/8`, `Runs ${current.completedRuns}/8`)}</span>
            <button
              className="dc-icon-btn"
              aria-label={tr("撤回", "Undo")}
              disabled={!current.canUndo}
              onClick={undo}
              style={{ opacity: current.canUndo ? 1 : 0.4 }}
            >
              <RotateCcw size={18} />
            </button>
            <button
              className="dc-btn dc-btn-tonal"
              disabled={!current.canDealStock}
              onClick={dealStock}
              style={{ opacity: current.canDealStock ? 1 : 0.4, gap: 4 }}
            >
              <Layers size={16} aria-hidden />
              {tr(`发牌 ${current.stockDealsRemaining}`, `Deal ${current.stockDealsRemaining}`)}
            </button>
            <button className="dc-icon-btn" aria-label={tr("新游戏", "New game")} onClick={() => setRestartOpen(true)}>
              <RefreshCw size={18} />
            </button>
          </>
        }
      />

      <SpiderBoard
        game={current}
        selected={selected}
        onSelect={(selection) => setSelected(selection)}
        onMove={(fromColumn, cardIndex, toColumn) => {
          if (applyAction(current.moveWithResult(fromColumn, cardIndex, toColumn))) {
            setSelected(null);
            return true;
          }
          return false;
        }}
      />

      {/* Landscape hint overlay for portrait small screens */}
      {portraitSmall && !portraitHintDismissed && (
        <div className="dc-dialog-overlay" style={{ zIndex: 120 }}>
          <div className="dc-dialog" role="alertdialog" aria-modal="true" style={{ width: "min(400px, 92vw)" }}>
            <div className="dc-title" style={{ marginBottom: 8 }}>
              {tr("建议横屏游玩", "Landscape recommended")}
            </div>
            <div className="dc-muted">
              {tr(
                "蜘蛛纸牌为横屏玩法，旋转设备后可以看到完整的十列牌桌。",
                "Spider Solitaire is a landscape game; rotate your device to see the full ten-column table.",
              )}
            </div>
            <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 16 }}>
              <button className="dc-btn dc-btn-filled" onClick={() => setPortraitHintDismissed(true)}>
                {tr("仍要竖屏继续", "Continue in portrait")}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 重开确认 */}
      <ConfirmDialog
        open={restartOpen}
        title={tr("重开蜘蛛纸牌？", "Restart Spider?")}
        message={tr(
          "当前牌局进度会被放弃，并立即重新发牌。此操作无法撤回。",
          "The current deal will be abandoned and a new one dealt immediately. This cannot be undone.",
        )}
        confirmLabel={tr("确认重开", "Restart")}
        cancelLabel={tr("取消", "Cancel")}
        danger
        onConfirm={confirmRestart}
        onCancel={() => setRestartOpen(false)}
      />

      {/* 胜利对话框 */}
      {current.isWon && (
        <div className="dc-dialog-overlay" style={{ zIndex: 110 }}>
          <div className="dc-dialog" role="alertdialog" aria-modal="true" style={{ width: "min(420px, 94vw)" }}>
            <div className="dc-title" style={{ marginBottom: 8 }}>{tr("完成蜘蛛纸牌", "Spider completed")}</div>
            <div className="dc-muted">
              {tr(
                `本局 ${current.score} 分，最高 ${Math.max(highScoreRef.current, current.score)} 分。`,
                `Score ${current.score}; best ${Math.max(highScoreRef.current, current.score)}.`,
              )}
            </div>
            <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 16 }}>
              <button className="dc-btn" onClick={exitToList}>
                {tr("返回", "Back")}
              </button>
              <button
                className="dc-btn dc-btn-filled"
                onClick={() => {
                  startNewDeal();
                }}
              >
                {tr("再来一局", "Play again")}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Board: ten columns with drag & drop + click-select alternative
// ---------------------------------------------------------------------------

function SpiderBoard(props: {
  game: SpiderSolitaireGame;
  selected: Selection | null;
  onSelect: (selection: Selection | null) => void;
  onMove: (fromColumn: number, cardIndex: number, toColumn: number) => boolean;
}) {
  const { game, selected, onSelect, onMove } = props;
  const [, setTick] = useState(0);
  const frame = useCallback(() => setTick((t) => (t + 1) % 1_000_000_000), []);

  const areaRef = useRef<HTMLDivElement | null>(null);
  const columnRefs = useRef<(HTMLDivElement | null)[]>([]);
  const [areaHeight, setAreaHeight] = useState(0);
  const [areaWidth, setAreaWidth] = useState(0);

  useLayoutEffect(() => {
    const node = areaRef.current;
    if (!node) return;
    const update = () => {
      setAreaWidth(node.getBoundingClientRect().width);
      setAreaHeight(node.getBoundingClientRect().height);
    };
    update();
    const observer = new ResizeObserver(update);
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  const cardWidth = Math.max(34, Math.min(72, Math.floor(areaWidth / COLUMN_COUNT) - 4));
  const cardHeight = Math.max(44, Math.min(58, Math.round(cardWidth * 1.35)));

  const columns: SpiderCard[][] = [];
  for (let c = 0; c < COLUMN_COUNT; c++) columns.push(game.column(c));

  // ---- pointer drag state (ref-authoritative; ghost mirrors it for rendering) ----
  const [dragging, setDragging] = useState(false);
  const [dragPos, setDragPos] = useState<{ x: number; y: number; moved: boolean } | null>(null);
  const dragRef = useRef<SpiderDragState | null>(null);
  const selectedRef = useRef<Selection | null>(selected);
  selectedRef.current = selected;
  const gameRef = useRef(game);
  gameRef.current = game;
  const moveRef = useRef(onMove);
  moveRef.current = onMove;
  const selectRef = useRef(onSelect);
  selectRef.current = onSelect;

  const hitTestColumn = useCallback((clientX: number, clientY: number): number | null => {
    for (let c = 0; c < COLUMN_COUNT; c++) {
      const node = columnRefs.current[c];
      if (!node) continue;
      const rect = node.getBoundingClientRect();
      if (clientX >= rect.left && clientX <= rect.right && clientY >= rect.top - 40 && clientY <= rect.bottom + 80) {
        return c;
      }
    }
    return null;
  }, []);

  /** Click-select-then-click-destination; failed moves reselect like Android. */
  const finishClick = useCallback(
    (column: number, cardIndex: number | null) => {
      const source = selectedRef.current;
      const currentGame = gameRef.current;
      const selectable = cardIndex !== null && currentGame.canSelect(column, cardIndex);
      if (source === null) {
        selectRef.current(selectable ? { column, cardIndex: cardIndex as number } : null);
        frame();
        return;
      }
      if (!moveRef.current(source.column, source.cardIndex, column)) {
        // Failed moves reselect the tapped runnable card (mirrors Android).
        selectRef.current(selectable ? { column, cardIndex: cardIndex as number } : null);
      }
      frame();
    },
    [frame],
  );

  useEffect(() => {
    if (!dragging) return;
    const onPointerMove = (event: PointerEvent) => {
      const current = dragRef.current;
      if (!current) return;
      current.x = event.clientX;
      current.y = event.clientY;
      if (
        !current.moved &&
        (Math.abs(event.clientX - current.startX) > DRAG_THRESHOLD_PX ||
          Math.abs(event.clientY - current.startY) > DRAG_THRESHOLD_PX)
      ) {
        current.moved = true;
      }
      setDragPos({ x: current.x, y: current.y, moved: current.moved });
    };
    const onPointerUp = (event: PointerEvent) => {
      const current = dragRef.current;
      dragRef.current = null;
      setDragging(false);
      setDragPos(null);
      if (!current) return;
      if (current.moved) {
        const target = hitTestColumn(event.clientX, event.clientY);
        if (target !== null && target !== current.column && moveRef.current(current.column, current.cardIndex, target)) {
          frame();
          return;
        }
        // Dropped nowhere valid: keep the run selected when still movable.
        selectRef.current(
          gameRef.current.canSelect(current.column, current.cardIndex)
            ? { column: current.column, cardIndex: current.cardIndex }
            : null,
        );
        frame();
      } else {
        finishClick(current.column, current.cardIndex);
      }
    };
    const onPointerCancel = () => {
      dragRef.current = null;
      setDragging(false);
      setDragPos(null);
    };
    window.addEventListener("pointermove", onPointerMove);
    window.addEventListener("pointerup", onPointerUp);
    window.addEventListener("pointercancel", onPointerCancel);
    return () => {
      window.removeEventListener("pointermove", onPointerMove);
      window.removeEventListener("pointerup", onPointerUp);
      window.removeEventListener("pointercancel", onPointerCancel);
    };
  }, [dragging, finishClick, frame, hitTestColumn]);

  const beginPointer = useCallback(
    (column: number, cardIndex: number, event: React.PointerEvent) => {
      if (!game.canSelect(column, cardIndex)) return;
      event.preventDefault();
      dragRef.current = {
        column,
        cardIndex,
        startX: event.clientX,
        startY: event.clientY,
        x: event.clientX,
        y: event.clientY,
        moved: false,
      };
      setDragging(true);
    },
    [game],
  );

  // Empty-column click uses the same selection flow.
  const onEmptyClick = useCallback(
    (column: number) => {
      finishClick(column, null);
    },
    [finishClick],
  );

  const dragCurrent = dragging ? dragRef.current : null;
  const effectiveSelected =
    dragCurrent && dragCurrent.moved
      ? { column: dragCurrent.column, cardIndex: dragCurrent.cardIndex }
      : selected;

  return (
    <div
      ref={areaRef}
      style={{
        display: "flex",
        gap: 3,
        flex: 1,
        minHeight: 320,
        padding: "2px 0",
        touchAction: "none",
        userSelect: "none",
        WebkitUserSelect: "none",
      }}
      role="application"
      aria-label={tr("蜘蛛纸牌牌桌", "Spider table")}
    >
      {columns.map((cards, columnIndex) => {
        const availableStep =
          cards.length <= 1
            ? 22
            : Math.max(5, Math.min(22, Math.floor((areaHeight - cardHeight - 6) / (cards.length - 1))));
        let offset = 3;
        const isSelectedColumn = effectiveSelected?.column === columnIndex;
        return (
          <div
            key={columnIndex}
            ref={(node) => {
              columnRefs.current[columnIndex] = node;
            }}
            style={{
              position: "relative",
              flex: 1,
              borderRadius: 8,
              background: "color-mix(in srgb, var(--dc-surface-variant) 35%, transparent)",
              minWidth: 0,
              cursor: "pointer",
            }}
            onClick={() => {
              if (dragRef.current?.moved) return;
              onEmptyClick(columnIndex);
            }}
          >
            {cards.map((card, cardIndex) => {
              const isSelected =
                isSelectedColumn && effectiveSelected !== null && cardIndex >= effectiveSelected.cardIndex;
              const faceBackground = isSelected
                ? "var(--dc-primary-container)"
                : "var(--dc-surface)";
              const faceColor = isSelected ? "var(--dc-on-primary-container)" : "var(--dc-on-surface)";
              return (
                <button
                  key={card.id}
                  className="dc-icon-btn"
                  aria-label={
                    card.faceUp
                      ? tr(`${spiderRank(card.rank)} 黑桃`, `${spiderRank(card.rank)} of spades`)
                      : tr("背面牌", "Face-down card")
                  }
                  style={{
                    position: "absolute",
                    left: 1,
                    right: 1,
                    top: offset,
                    height: cardHeight,
                    padding: "2px 4px",
                    borderRadius: 5,
                    display: "flex",
                    flexDirection: "column",
                    alignItems: "flex-start",
                    justifyContent: "flex-start",
                    background: card.faceUp ? faceBackground : "var(--dc-primary)",
                    color: card.faceUp ? faceColor : "var(--dc-on-primary)",
                    border: `${isSelected ? 2 : 1}px solid ${isSelected ? "var(--dc-primary)" : "var(--dc-outline-variant)"}`,
                    boxShadow: "0 1px 2px rgba(0,0,0,0.18)",
                    fontSize: 12,
                    fontWeight: 700,
                    lineHeight: 1.15,
                    overflow: "hidden",
                    cursor: "pointer",
                  }}
                  onClick={(e) => {
                    // Tap actions are handled on pointerup; swallow the synthetic click
                    // so it never reaches the empty-column handler underneath.
                    e.stopPropagation();
                  }}
                  onPointerDown={(e) => beginPointer(columnIndex, cardIndex, e)}
                >
                  {card.faceUp ? (
                    <>
                      <span>{spiderRank(card.rank)}</span>
                      <span aria-hidden>♠</span>
                    </>
                  ) : (
                    <span
                      aria-hidden
                      style={{
                        alignSelf: "stretch",
                        flex: 1,
                        borderRadius: 3,
                        background: "var(--dc-secondary-container)",
                        margin: 2,
                      }}
                    />
                  )}
                </button>
              );
            })}
          </div>
        );
      })}

      {/* Dragged run ghost */}
      {dragPos !== null && dragPos.moved && dragCurrent && (
        <div
          style={{
            position: "fixed",
            left: dragPos.x - cardWidth / 2,
            top: dragPos.y - 14,
            width: cardWidth,
            height: cardHeight,
            borderRadius: 5,
            background: "var(--dc-primary-container)",
            color: "var(--dc-on-primary-container)",
            border: "2px solid var(--dc-primary)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            fontWeight: 700,
            fontSize: 12,
            pointerEvents: "none",
            zIndex: 200,
            boxShadow: "0 8px 20px rgba(0,0,0,0.3)",
          }}
        >
          {columns[dragCurrent.column].length - dragCurrent.cardIndex}
        </div>
      )}
    </div>
  );
}
