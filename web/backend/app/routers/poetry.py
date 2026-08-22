"""诗词本 Poetry book API — mirrors PoetryBookRepository + SavedPoemDao/PoetryCategoryDao
and the daily-poetry rotation in services/poetry_daily.py.

- poem list: ORDER BY sortOrder ASC, createdAt DESC, id DESC (newest first)
- new poem  : insertAtStart → sortOrder = COALESCE(MIN(sortOrder),0) - 1
- reorder   : stable-ID, pre-move-list semantics so index 0 is always a valid target
- categories: NOCASE unique names; DELETE ?mode=keep|delete decides whether the
              category's poems fall back to 未分类 or are deleted with it
- presets   : bounded offline textbook catalog; imports skip duplicates by content
"""
from __future__ import annotations

import sqlite3
import threading
import time
from typing import Any

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from ..core.db import get_db, write_lock
from ..core.errors import ApiError
from ..services import poetry_daily

router = APIRouter(prefix="/api/poetry", tags=["poetry"])

MAX_CONTENT_CHARS = 4_000
MAX_SOURCE_CHARS = 512
MAX_CATEGORY_NAME_CHARS = 100

POEM_ORDER = "sortOrder ASC, createdAt DESC, id DESC"
CATEGORY_ORDER = "sortOrder ASC, createdAt ASC, id ASC"

_reorder_lock = threading.RLock()


def now_ms() -> int:
    return int(time.time() * 1000)


def to_int32(value: Any) -> int:
    try:
        v = int(value)
    except (TypeError, ValueError):
        raise ApiError(400, "invalid_color", "colorArgb must be an integer")
    return ((v & 0xFFFFFFFF) ^ 0x80000000) - 0x80000000


def opaque(color: int) -> int:
    """PoetryBookRepository stores categories as `colorArgb or 0xFF000000`."""
    return to_int32((int(color) & 0xFFFFFFFF) | 0xFF000000)


def row_to_poem(row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "content": row["content"],
        "source": row["source"],
        "createdAt": row["createdAt"],
        "updatedAt": row["updatedAt"],
        "sortOrder": row["sortOrder"],
        "categoryId": row["categoryId"],
    }


def row_to_category(row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "name": row["name"],
        "colorArgb": row["colorArgb"],
        "sortOrder": row["sortOrder"],
        "createdAt": row["createdAt"],
        "updatedAt": row["updatedAt"],
    }


def _require_poem(con, poem_id: int):
    if poem_id <= 0:
        raise ApiError(404, "poem_not_found", "Saved poem not found")
    row = con.execute("SELECT * FROM saved_poems WHERE id = ? LIMIT 1", (poem_id,)).fetchone()
    if row is None:
        raise ApiError(404, "poem_not_found", "Saved poem not found")
    return row


def _checked_content(content: str) -> str:
    trimmed = content.strip()
    if not trimmed:
        raise ApiError(400, "empty_content", "Poem content must not be blank")
    if len(trimmed) > MAX_CONTENT_CHARS:
        raise ApiError(400, "content_too_long", "Poem content is too long")
    return trimmed


def _checked_source(source: str) -> str:
    trimmed = source.strip()
    if len(trimmed) > MAX_SOURCE_CHARS:
        raise ApiError(400, "source_too_long", "Poem source is too long")
    return trimmed


def _validate_category(con, category_id: int | None) -> None:
    if category_id is None:
        return
    row = con.execute(
        "SELECT id FROM poetry_categories WHERE id = ? LIMIT 1", (category_id,)
    ).fetchone()
    if row is None:
        raise ApiError(404, "category_not_found", "Category not found")


# ---------------------------------------------------------------------------
# Poems CRUD + reorder
# ---------------------------------------------------------------------------

class PoemBody(BaseModel):
    content: str
    source: str = ""
    categoryId: int | None = None


class PoemMoveBody(BaseModel):
    id: int
    targetIndex: int
    categoryId: int | None = None
    scoped: bool = False


class PoemReorderItem(BaseModel):
    id: int
    sortOrder: int | None = None


class PoemReorderBody(BaseModel):
    items: list[PoemReorderItem]
    scopedCategoryId: int | None = None
    scoped: bool = False


@router.get("/poems")
def list_poems(categoryId: int | None = None, con=Depends(get_db)):
    if categoryId is not None:
        rows = con.execute(
            f"SELECT * FROM saved_poems WHERE categoryId IS ? ORDER BY {POEM_ORDER}",
            (None if categoryId < 0 else categoryId,),
        ).fetchall()
    else:
        rows = con.execute(f"SELECT * FROM saved_poems ORDER BY {POEM_ORDER}").fetchall()
    return [row_to_poem(r) for r in rows]


@router.post("/poems", status_code=201)
def create_poem(body: PoemBody, con=Depends(get_db)):
    content = _checked_content(body.content)
    source = _checked_source(body.source)
    _validate_category(con, body.categoryId)
    now = now_ms()
    with _reorder_lock, write_lock(), con:
        row = con.execute("SELECT COALESCE(MIN(sortOrder), 0) AS m FROM saved_poems").fetchone()
        sort_order = int(row[0]) - 1
        cur = con.execute(
            "INSERT INTO saved_poems(content, source, createdAt, updatedAt, sortOrder, categoryId)"
            " VALUES(?,?,?,?,?,?)",
            (content, source, now, now, sort_order, body.categoryId),
        )
        poem_id = int(cur.lastrowid)
    return row_to_poem(_require_poem(con, poem_id))


@router.get("/poems/{poem_id}")
def get_poem(poem_id: int, con=Depends(get_db)):
    return row_to_poem(_require_poem(con, poem_id))


@router.put("/poems/{poem_id}")
def update_poem(poem_id: int, body: PoemBody, con=Depends(get_db)):
    _require_poem(con, poem_id)
    content = _checked_content(body.content)
    source = _checked_source(body.source)
    _validate_category(con, body.categoryId)
    with write_lock(), con:
        cur = con.execute(
            "UPDATE saved_poems SET content = ?, source = ?, categoryId = ?, updatedAt = ?"
            " WHERE id = ?",
            (content, source, body.categoryId, now_ms(), poem_id),
        )
        if cur.rowcount == 0:
            raise ApiError(404, "poem_not_found", "Saved poem not found")
    return row_to_poem(_require_poem(con, poem_id))


@router.delete("/poems/{poem_id}")
def delete_poem(poem_id: int, con=Depends(get_db)):
    _require_poem(con, poem_id)
    with write_lock(), con:
        cur = con.execute("DELETE FROM saved_poems WHERE id = ?", (poem_id,))
        if cur.rowcount == 0:
            raise ApiError(404, "poem_not_found", "Saved poem no longer exists")
    return {"ok": True}


def _poem_ids_in_order(con) -> list[int]:
    return [int(r[0]) for r in con.execute(f"SELECT id FROM saved_poems ORDER BY {POEM_ORDER}")]


def _poem_ids_in_category(con, category_id: int | None) -> list[int]:
    return [
        int(r[0])
        for r in con.execute(
            "SELECT id FROM saved_poems WHERE "
            "(? IS NULL AND categoryId IS NULL) OR categoryId = ? "
            f"ORDER BY {POEM_ORDER}",
            (category_id, category_id),
        )
    ]


def _replace_poem_order(con, ordered_ids: list[int]) -> None:
    for index, poem_id in enumerate(ordered_ids):
        con.execute("UPDATE saved_poems SET sortOrder = ? WHERE id = ?", (index, poem_id))


def move_poem_id_to_index(ordered_ids: list[int], poem_id: int, target_index: int) -> list[int]:
    """Port of Daos.kt `movePoemIdToIndex`: reorders against the pre-move list so
    index zero is a valid destination and source."""
    if poem_id not in ordered_ids or len(ordered_ids) < 2:
        return ordered_ids
    destination = max(0, min(target_index, len(ordered_ids) - 1))
    source = ordered_ids.index(poem_id)
    if source == destination:
        return ordered_ids
    moving = ordered_ids.pop(source)
    ordered_ids.insert(min(destination, len(ordered_ids)), moving)
    return ordered_ids


def replace_poem_subset_order(all_ids: list[int], ordered_subset_ids: list[int]) -> list[int]:
    """Port of Daos.kt `replacePoemSubsetOrder`: places a filtered category order
    back into the same global slots without moving other poems."""
    subset = set(ordered_subset_ids)
    if len(subset) != len(ordered_subset_ids):
        return all_ids
    replacement = 0
    result = []
    for poem_id in all_ids:
        if poem_id in subset and replacement < len(ordered_subset_ids):
            result.append(ordered_subset_ids[replacement])
            replacement += 1
        else:
            result.append(poem_id)
    return result if replacement == len(ordered_subset_ids) else all_ids


@router.post("/poems/reorder")
def reorder_poems(body: PoemMoveBody | list[PoemReorderItem] | PoemReorderBody, con=Depends(get_db)):
    """Drag-handle reorder with Android semantics:
    - `{id, targetIndex}` moves within the whole book (PoetryBookRepository.move);
    - `{id, targetIndex, scoped: true, categoryId}` moves inside one group
      (moveInCategory — other groups keep their slots);
    - a bare `[{"id","sortOrder"}]` list assigns explicit sortOrder values.
    """
    with _reorder_lock, write_lock(), con:
        if isinstance(body, PoemMoveBody):
            _require_poem(con, body.id)
            if body.scoped:
                all_ids = _poem_ids_in_order(con)
                group_ids = _poem_ids_in_category(con, body.categoryId)
                reordered = move_poem_id_to_index(list(group_ids), body.id, body.targetIndex)
                if reordered != group_ids:
                    _replace_poem_order(con, replace_poem_subset_order(all_ids, reordered))
            else:
                all_ids = _poem_ids_in_order(con)
                reordered = move_poem_id_to_index(list(all_ids), body.id, body.targetIndex)
                if reordered != all_ids:
                    _replace_poem_order(con, reordered)
        elif isinstance(body, list):
            for index, item in enumerate(body):
                sort_order = int(item.sortOrder) if item.sortOrder is not None else index
                con.execute(
                    "UPDATE saved_poems SET sortOrder = ? WHERE id = ?", (sort_order, item.id)
                )
        else:
            if body.scoped:
                group_ids = [item.id for item in body.items]
                current_group = _poem_ids_in_category(con, body.scopedCategoryId)
                if not current_group or sorted(group_ids) != sorted(current_group):
                    raise ApiError(400, "reorder_mismatch", "Reorder items do not match the category list")
                all_ids = _poem_ids_in_order(con)
                _replace_poem_order(con, replace_poem_subset_order(all_ids, group_ids))
            else:
                _replace_poem_order(con, [item.id for item in body.items])
    rows = con.execute(f"SELECT * FROM saved_poems ORDER BY {POEM_ORDER}").fetchall()
    return [row_to_poem(r) for r in rows]


# ---------------------------------------------------------------------------
# Categories
# ---------------------------------------------------------------------------

class CategoryBody(BaseModel):
    name: str
    colorArgb: int = -1


class CategoryReorderItem(BaseModel):
    id: int
    sortOrder: int | None = None


class PoemsMoveBody(BaseModel):
    poemIds: list[int]


def _normalize_name(name: str) -> str:
    normalized = " ".join(name.split())[:MAX_CATEGORY_NAME_CHARS]
    if not normalized:
        raise ApiError(400, "invalid_name", "Category name must not be blank")
    return normalized


def _find_category_by_name(con, name: str) -> int | None:
    row = con.execute(
        "SELECT id FROM poetry_categories WHERE name = ? COLLATE NOCASE LIMIT 1", (name,)
    ).fetchone()
    return int(row[0]) if row else None


def _require_category_row(con, category_id: int):
    if category_id <= 0:
        raise ApiError(404, "category_not_found", "Category not found")
    row = con.execute(
        "SELECT * FROM poetry_categories WHERE id = ? LIMIT 1", (category_id,)
    ).fetchone()
    if row is None:
        raise ApiError(404, "category_not_found", "Category not found")
    return row


@router.get("/categories")
def list_categories(con=Depends(get_db)):
    rows = con.execute(f"SELECT * FROM poetry_categories ORDER BY {CATEGORY_ORDER}").fetchall()
    return [row_to_category(r) for r in rows]


@router.post("/categories", status_code=201)
def create_category(body: CategoryBody, con=Depends(get_db)):
    name = _normalize_name(body.name)
    color = opaque(body.colorArgb)
    now = now_ms()
    with _reorder_lock, write_lock(), con:
        if _find_category_by_name(con, name) is not None:
            raise ApiError(409, "duplicate_name", "A category with this name already exists")
        row = con.execute("SELECT COALESCE(MAX(sortOrder), -1) + 1 AS s FROM poetry_categories").fetchone()
        try:
            cur = con.execute(
                "INSERT INTO poetry_categories(name, colorArgb, sortOrder, createdAt, updatedAt)"
                " VALUES(?,?,?,?,?)",
                (name, color, int(row[0]), now, now),
            )
        except sqlite3.IntegrityError:
            raise ApiError(409, "duplicate_name", "A category with this name already exists")
        category_id = int(cur.lastrowid)
    return row_to_category(_require_category_row(con, category_id))


@router.put("/categories/{category_id}")
def update_category(category_id: int, body: CategoryBody, con=Depends(get_db)):
    _require_category_row(con, category_id)
    name = _normalize_name(body.name)
    color = opaque(body.colorArgb)
    with _reorder_lock, write_lock(), con:
        duplicate = _find_category_by_name(con, name)
        if duplicate is not None and duplicate != category_id:
            raise ApiError(409, "duplicate_name", "A category with this name already exists")
        cur = con.execute(
            "UPDATE poetry_categories SET name = ?, colorArgb = ?, updatedAt = ? WHERE id = ?",
            (name, color, now_ms(), category_id),
        )
        if cur.rowcount == 0:
            raise ApiError(404, "category_not_found", "Category not found")
    return row_to_category(_require_category_row(con, category_id))


@router.delete("/categories/{category_id}")
def delete_category(category_id: int, mode: str = "keep", con=Depends(get_db)):
    """Delete a category. `mode=keep` (default): its poems fall back to 未分类.
    `mode=delete`: the poems are deleted together with the category (irreversible)."""
    _require_category_row(con, category_id)
    with _reorder_lock, write_lock(), con:
        if mode == "delete":
            con.execute("DELETE FROM saved_poems WHERE categoryId = ?", (category_id,))
        elif mode == "keep":
            con.execute("UPDATE saved_poems SET categoryId = NULL WHERE categoryId = ?", (category_id,))
        else:
            raise ApiError(400, "invalid_mode", "mode must be keep or delete")
        cur = con.execute("DELETE FROM poetry_categories WHERE id = ?", (category_id,))
        if cur.rowcount == 0:
            raise ApiError(404, "category_not_found", "Category not found")
    return {"ok": True}


@router.post("/categories/reorder")
def reorder_categories(body: list[CategoryReorderItem], con=Depends(get_db)):
    with _reorder_lock, write_lock(), con:
        for index, item in enumerate(body):
            sort_order = int(item.sortOrder) if item.sortOrder is not None else index
            con.execute(
                "UPDATE poetry_categories SET sortOrder = ? WHERE id = ?", (sort_order, item.id)
            )
    rows = con.execute(f"SELECT * FROM poetry_categories ORDER BY {CATEGORY_ORDER}").fetchall()
    return [row_to_category(r) for r in rows]


@router.post("/categories/{category_id}/poems-move")
def move_poems_into_category(category_id: int, body: PoemsMoveBody, con=Depends(get_db)):
    """批量移动诗词到指定分类（未分类请用 PUT /poems/{id} 的 categoryId=null）。"""
    _require_category_row(con, category_id)
    moved = 0
    with write_lock(), con:
        for poem_id in body.poemIds:
            cur = con.execute(
                "UPDATE saved_poems SET categoryId = ?, updatedAt = ? WHERE id = ?",
                (category_id, now_ms(), poem_id),
            )
            moved += cur.rowcount
    return {"movedCount": moved}


# ---------------------------------------------------------------------------
# Presets
# ---------------------------------------------------------------------------

class PresetImportBody(BaseModel):
    presetId: str


@router.get("/presets")
def list_presets():
    return poetry_daily.preset_summaries()


@router.post("/presets/import")
def import_preset(body: PresetImportBody, con=Depends(get_db)):
    categories = poetry_daily.load_preset_categories()
    preset = next((c for c in categories if c["id"] == body.presetId), None)
    if preset is None:
        raise ApiError(404, "preset_not_found", "Poetry preset category does not exist")
    now = now_ms()
    with _reorder_lock, write_lock(), con:
        row = _find_category_by_name(con, preset["nameZh"])
        if row is None:
            max_row = con.execute(
                "SELECT COALESCE(MAX(sortOrder), -1) + 1 AS s FROM poetry_categories"
            ).fetchone()
            try:
                cur = con.execute(
                    "INSERT INTO poetry_categories(name, colorArgb, sortOrder, createdAt, updatedAt)"
                    " VALUES(?,?,?,?,?)",
                    (preset["nameZh"], preset["colorArgb"], int(max_row[0]), now, now),
                )
                category_id = int(cur.lastrowid)
            except sqlite3.IntegrityError:
                existing = _find_category_by_name(con, preset["nameZh"])
                if existing is None:
                    raise ApiError(409, "duplicate_name", "Poetry preset category could not be created")
                category_id = existing
        else:
            category_id = row
        added = 0
        for index, poem in enumerate(preset["poems"]):
            dup = con.execute(
                "SELECT id FROM saved_poems WHERE categoryId = ? AND content = ? AND source = ? LIMIT 1",
                (category_id, poem["content"], poem["source"]),
            ).fetchone()
            if dup is not None:
                continue
            # observeAll() is newest-first; decreasing timestamps preserve the
            # textbook order instead of displaying each import backwards.
            stamp = now - index
            end_row = con.execute(
                "SELECT COALESCE(MAX(sortOrder), -1) + 1 AS s FROM saved_poems"
            ).fetchone()
            con.execute(
                "INSERT INTO saved_poems(content, source, createdAt, updatedAt, sortOrder, categoryId)"
                " VALUES(?,?,?,?,?,?)",
                (poem["content"], poem["source"], stamp, stamp, int(end_row[0]), category_id),
            )
            added += 1
    return {
        "categoryId": category_id,
        "addedCount": added,
        "existingCount": len(preset["poems"]) - added,
    }


# ---------------------------------------------------------------------------
# Daily poem (每日诗词)
# ---------------------------------------------------------------------------

@router.get("/daily")
def get_daily(force: bool = False):
    poem = poetry_daily.refresh_daily(force=force)
    return poem
