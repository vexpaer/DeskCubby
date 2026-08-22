"""小游戏 Games API — per-game save state and additive feature statistics.

Mirrors android `GamePersistenceCoordinator.kt` / `GameStateDao` /
`GameStatisticsRepository.kt`:

- `game_states` upserts preserve the historical high score (`max(existing, score)`)
  and store the raw engine `saveJson`; a null saveJson clears only the save.
- `game_statistics` rows are keyed `(gameId, metricKey)`; increments must be
  positive, maxima non-negative, both restricted to each game's catalog metric set
  (2048 losses stay accepted for legacy data but are no longer active), with
  saturating adds at Long.MAX_VALUE so counts never wrap negative.
"""
from __future__ import annotations

import time
from typing import Any

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from ..core.db import get_db, write_lock
from ..core.errors import ApiError

router = APIRouter(prefix="/api/games", tags=["games"])

# GameStatisticCatalog.kt — game ids and supported metric keys.
GAME_2048 = "2048"
GAME_2048_5 = "2048_5"
GAME_2048_6 = "2048_6"
GAME_SNAKE = "snake"
GAME_TETRIS = "tetris"
GAME_MINESWEEPER = "minesweeper"
GAME_SPIDER = "spider"
GAME_GO = "go"

METRIC_WINS = "wins"
METRIC_LOSSES = "losses"

_COMMON_OUTCOMES = {METRIC_WINS, METRIC_LOSSES}
_GAME_2048_METRICS = _COMMON_OUTCOMES | {
    "moveAttempts", "effectiveMoves", "merges", "highestTile",
}

SUPPORTED_METRICS_BY_GAME_ID: dict[str, set[str]] = {
    GAME_2048: _GAME_2048_METRICS,
    GAME_2048_5: _GAME_2048_METRICS,
    GAME_2048_6: _GAME_2048_METRICS,
    GAME_SNAKE: {"losses", "foodEaten", "maxLength"},
    GAME_TETRIS: {"losses", "piecesLocked", "linesCleared", "tetrises"},
    GAME_MINESWEEPER: _COMMON_OUTCOMES | {"minesCellsRevealed", "minesSwept", "flagsPlaced"},
    GAME_SPIDER: _COMMON_OUTCOMES | {"spiderCardMoves", "spiderDeals", "spiderUndos"},
    GAME_GO: {"goMovesPlayed", "goStonesCaptured", "goPasses", "goGamesCompleted"},
}

GAME_IDS = list(SUPPORTED_METRICS_BY_GAME_ID.keys())
GAME_2048_IDS = {GAME_2048, GAME_2048_5, GAME_2048_6}

LONG_MAX = 2**63 - 1


def now_ms() -> int:
    return int(time.time() * 1000)


def supports(game_id: str, metric_key: str) -> bool:
    return metric_key in SUPPORTED_METRICS_BY_GAME_ID.get(game_id, set())


def is_active(game_id: str, metric_key: str) -> bool:
    """Current gameplay metrics; legacy 2048 loss rows round-trip but never grow."""
    return supports(game_id, metric_key) and not (
        game_id in GAME_2048_IDS and metric_key == METRIC_LOSSES
    )


def require_game_id(game_id: str) -> str:
    if game_id not in SUPPORTED_METRICS_BY_GAME_ID:
        raise ApiError(400, "unsupported_game", "Unsupported game statistic ID")
    return game_id


def row_to_state(row) -> dict[str, Any]:
    return {
        "gameId": row["gameId"],
        "highScore": int(row["highScore"]),
        "saveJson": row["saveJson"],
        "updatedAt": int(row["updatedAt"]),
    }


class StateUpdateBody(BaseModel):
    highScore: int | None = None
    # Raw engine JSON string (opaque to this layer); null/absent clears the save
    # like GameStateDao.clearSave. Omit handling: use explicit null to clear.
    saveJson: str | None = None
    clearSave: bool = False


class StatisticsBody(BaseModel):
    gameId: str
    metricKey: str
    value: int
    # "add" (default) mirrors increments; "max" mirrors maxima (highestTile etc.)
    mode: str = "add"


@router.get("/states/{game_id}")
def get_state(game_id: str, con=Depends(get_db)):
    require_game_id(game_id)
    row = con.execute("SELECT * FROM game_states WHERE gameId = ? LIMIT 1", (game_id,)).fetchone()
    if row is None:
        return {"gameId": game_id, "highScore": 0, "saveJson": None, "updatedAt": 0}
    return row_to_state(row)


@router.put("/states/{game_id}")
def put_state(game_id: str, body: StateUpdateBody, con=Depends(get_db)):
    """upsertPreservingHighScore: high score only ever rises via this endpoint."""
    require_game_id(game_id)
    now = now_ms()
    with write_lock(), con:
        existing = con.execute(
            "SELECT * FROM game_states WHERE gameId = ? LIMIT 1", (game_id,)
        ).fetchone()
        previous_high = int(existing["highScore"]) if existing is not None else 0
        new_high = previous_high
        if body.highScore is not None:
            if body.highScore < 0 or body.highScore > LONG_MAX:
                raise ApiError(400, "invalid_score", "分数超出允许范围")
            new_high = max(previous_high, int(body.highScore))
        save_json = None if body.clearSave else body.saveJson
        con.execute(
            "INSERT INTO game_states(gameId, highScore, saveJson, updatedAt) VALUES(?,?,?,?) "
            "ON CONFLICT(gameId) DO UPDATE SET highScore=excluded.highScore,"
            " saveJson=excluded.saveJson, updatedAt=excluded.updatedAt",
            (game_id, new_high, save_json, now),
        )
    row = con.execute("SELECT * FROM game_states WHERE gameId = ? LIMIT 1", (game_id,)).fetchone()
    assert row is not None
    return row_to_state(row)


@router.get("/statistics")
def get_statistics(con=Depends(get_db)):
    """snapshotOf projection: grouped metrics, inactive/negative rows filtered."""
    rows = con.execute(
        "SELECT gameId, metricKey, value FROM game_statistics ORDER BY gameId ASC, metricKey ASC"
    ).fetchall()
    grouped: dict[str, dict[str, int]] = {}
    for row in rows:
        value = int(row["value"])
        if value < 0 or not is_active(str(row["gameId"]), str(row["metricKey"])):
            continue
        grouped.setdefault(str(row["gameId"]), {})[str(row["metricKey"])] = value
    return {
        "byGameId": [
            {"gameId": game_id, "metrics": grouped[game_id]}
            for game_id in sorted(grouped.keys())
        ],
        "games": grouped,
    }


def _saturating_add(left: int, right: int) -> int:
    if left > LONG_MAX - right:
        return LONG_MAX
    return left + right


@router.post("/statistics")
def post_statistic(body: StatisticsBody, con=Depends(get_db)):
    """Additive/max upsert mirroring GameStatisticsRepository.record."""
    require_game_id(body.gameId)
    mode = (body.mode or "add").lower()
    if mode not in ("add", "max"):
        raise ApiError(400, "invalid_mode", "mode 仅支持 add 或 max")
    if not supports(body.gameId, body.metricKey):
        raise ApiError(400, "unsupported_metric", "Unsupported game statistic metric")
    if not is_active(body.gameId, body.metricKey):
        raise ApiError(400, "inactive_metric", "该指标不再累计")
    if mode == "add" and body.value <= 0:
        raise ApiError(400, "invalid_value", "Statistic increments must be positive")
    if mode == "max" and body.value < 0:
        raise ApiError(400, "invalid_value", "Statistic maxima must be non-negative")
    now = now_ms()
    with write_lock(), con:
        existing = con.execute(
            "SELECT value FROM game_statistics WHERE gameId = ? AND metricKey = ? LIMIT 1",
            (body.gameId, body.metricKey),
        ).fetchone()
        current = int(existing["value"]) if existing is not None else 0
        updated = (
            _saturating_add(current, int(body.value)) if mode == "add" else max(current, int(body.value))
        )
        con.execute(
            "INSERT INTO game_statistics(gameId, metricKey, value, updatedAt) VALUES(?,?,?,?) "
            "ON CONFLICT(gameId, metricKey) DO UPDATE SET value=excluded.value,"
            " updatedAt=excluded.updatedAt",
            (body.gameId, body.metricKey, updated, now),
        )
        stored = con.execute(
            "SELECT value FROM game_statistics WHERE gameId = ? AND metricKey = ? LIMIT 1",
            (body.gameId, body.metricKey),
        ).fetchone()
    return {
        "gameId": body.gameId,
        "metricKey": body.metricKey,
        "value": int(stored["value"]),
        "updatedAt": now,
    }
