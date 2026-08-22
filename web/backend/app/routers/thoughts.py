"""小巧思 Thoughts API.

Faithful port of `ThoughtRepository` + `FlashThoughtDao` / `ThoughtCategoryDao`
(android/app/src/main/java/com/deskcubby/app/data/local/Daos.kt):

- active list   : ORDER BY sortOrder ASC, pinned DESC, createdAt ASC, id ASC, deletedAt IS NULL
- trash list    : deletedAt IS NOT NULL ORDER BY deletedAt DESC
- categories    : thought_categories ORDER BY sortOrder ASC, createdAt ASC, id ASC
                  with a NOCASE unique name index (duplicate names rejected case-insensitively)
- pin on        : sortOrder = COALESCE(MIN(sortOrder),0) - 1  (jumps to the very front)
- pin off/restore: sortOrder = COALESCE(MAX(sortOrder),-1) + 1 (goes to the end)
- category delete: thoughts keep and fall back to 未分类 (categoryId = NULL)
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

# Discovered by main.py; the two prefixed sub-routers are attached at the bottom.
router = APIRouter(tags=["thoughts"])
thoughts_router = APIRouter(prefix="/api/thoughts", tags=["thoughts"])
categories_router = APIRouter(prefix="/api/thought-categories", tags=["thoughts"])

MAX_CATEGORY_NAME_LENGTH = 40  # ThoughtRepository.MAX_CATEGORY_NAME_LENGTH

_refresh_lock = threading.RLock()

ACTIVE_ORDER = "sortOrder ASC, pinned DESC, createdAt ASC, id ASC"


def now_ms() -> int:
    return int(time.time() * 1000)


def to_int32(value: Any) -> int:
    """Coerce a JSON color into a signed 32-bit int like Kotlin's Int."""
    try:
        v = int(value)
    except (TypeError, ValueError):
        raise ApiError(400, "invalid_color", "colorArgb must be an integer")
    return ((v & 0xFFFFFFFF) ^ 0x80000000) - 0x80000000


def row_to_thought(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "content": row["content"],
        "createdAt": row["createdAt"],
        "updatedAt": row["updatedAt"],
        "pinned": bool(row["pinned"]),
        "deletedAt": row["deletedAt"],
        "sortOrder": row["sortOrder"],
        "categoryId": row["categoryId"],
        "highlighted": bool(row["highlighted"]),
    }


def row_to_category(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "name": row["name"],
        "colorArgb": row["colorArgb"],
        "sortOrder": row["sortOrder"],
        "createdAt": row["createdAt"],
        "updatedAt": row["updatedAt"],
    }


# ---------------------------------------------------------------------------
# DAO-equivalent helpers
# ---------------------------------------------------------------------------

def get_thought(con, thought_id: int) -> sqlite3.Row | None:
    return con.execute("SELECT * FROM flash_thoughts WHERE id = ? LIMIT 1", (thought_id,)).fetchone()


def _require_thought(con, thought_id: int) -> sqlite3.Row:
    row = get_thought(con, thought_id)
    if row is None:
        raise ApiError(404, "thought_not_found", "Thought not found")
    return row


def next_active_sort_order(con) -> int:
    row = con.execute(
        "SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM flash_thoughts WHERE deletedAt IS NULL"
    ).fetchone()
    return int(row[0])


def sort_order_before_first(con) -> int:
    row = con.execute(
        "SELECT COALESCE(MIN(sortOrder), 0) - 1 FROM flash_thoughts WHERE deletedAt IS NULL"
    ).fetchone()
    return int(row[0])


def active_ids_in_order(con) -> list[int]:
    return [
        int(r[0])
        for r in con.execute(
            f"SELECT id FROM flash_thoughts WHERE deletedAt IS NULL ORDER BY {ACTIVE_ORDER}"
        )
    ]


def active_ids_in_category(con, category_id: int | None) -> list[int]:
    return [
        int(r[0])
        for r in con.execute(
            "SELECT id FROM flash_thoughts WHERE deletedAt IS NULL AND categoryId IS ? "
            f"ORDER BY {ACTIVE_ORDER}",
            (category_id,),
        )
    ]


def replace_active_order(con, ordered_ids: list[int]) -> None:
    for index, thought_id in enumerate(ordered_ids):
        con.execute(
            "UPDATE flash_thoughts SET sortOrder = ? WHERE id = ? AND deletedAt IS NULL",
            (index, thought_id),
        )


def move_active(con, thought_id: int, target_index: int) -> None:
    ordered = active_ids_in_order(con)
    if thought_id not in ordered or len(ordered) < 2:
        return
    destination = max(0, min(target_index, len(ordered) - 1))
    source = ordered.index(thought_id)
    if source == destination:
        return
    ordered.insert(destination, ordered.pop(source))
    replace_active_order(con, ordered)


def move_active_in_category(con, thought_id: int, target_index: int, category_id: int | None) -> None:
    """FlashThoughtDao.moveActiveInCategory: reorder inside one group while other
    groups keep their global slots."""
    all_ids = active_ids_in_order(con)
    group_ids = active_ids_in_category(con, category_id)
    if thought_id not in group_ids or not group_ids:
        return
    destination = max(0, min(target_index, len(group_ids) - 1))
    source = group_ids.index(thought_id)
    if source == destination:
        return
    group_ids.insert(destination, group_ids.pop(source))
    group_set = set(group_ids)
    replacement = 0
    for index in range(len(all_ids)):
        if all_ids[index] in group_set:
            all_ids[index] = group_ids[replacement]
            replacement += 1
    replace_active_order(con, all_ids)


# ---------------------------------------------------------------------------
# Body models
# ---------------------------------------------------------------------------

class ThoughtCreate(BaseModel):
    content: str
    categoryId: int | None = None


class ThoughtUpdate(BaseModel):
    content: str


class FlagBody(BaseModel):
    value: bool


class MoveBody(BaseModel):
    categoryId: int | None = None


class ReorderItem(BaseModel):
    id: int
    sortOrder: int | None = None


class ReorderBody(BaseModel):
    items: list[ReorderItem]
    # When present (including null) the items describe the new order of that
    # category's subset — `null` means 未分类. Omitted means the full active list.
    scopedCategoryId: int | None = None
    scoped: bool = False


class CategoryBody(BaseModel):
    name: str
    colorArgb: int = -1


class CategoryReorderItem(BaseModel):
    id: int
    sortOrder: int | None = None


# ---------------------------------------------------------------------------
# Thoughts endpoints
# ---------------------------------------------------------------------------

@thoughts_router.get("")
def list_thoughts(trash: int = 0, con=Depends(get_db)):
    if trash:
        rows = con.execute(
            "SELECT * FROM flash_thoughts WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC"
        ).fetchall()
    else:
        rows = con.execute(
            f"SELECT * FROM flash_thoughts WHERE deletedAt IS NULL ORDER BY {ACTIVE_ORDER}"
        ).fetchall()
    return [row_to_thought(r) for r in rows]


@thoughts_router.post("", status_code=201)
def create_thought(body: ThoughtCreate, con=Depends(get_db)):
    content = body.content.strip()
    if not content:
        raise ApiError(400, "empty_content", "Content must not be blank")
    if body.categoryId is not None:
        exists = con.execute(
            "SELECT id FROM thought_categories WHERE id = ? LIMIT 1", (body.categoryId,)
        ).fetchone()
        if exists is None:
            raise ApiError(404, "category_not_found", "Category not found")
    now = now_ms()
    with write_lock(), con:
        sort_order = next_active_sort_order(con)
        cur = con.execute(
            "INSERT INTO flash_thoughts(content, createdAt, updatedAt, pinned, deletedAt,"
            " sortOrder, categoryId, highlighted) VALUES(?,?,?,0,NULL,?,?,0)",
            (content, now, now, sort_order, body.categoryId),
        )
        thought_id = int(cur.lastrowid)
    row = get_thought(con, thought_id)
    assert row is not None
    return row_to_thought(row)


@thoughts_router.get("/{thought_id}")
def get_one(thought_id: int, con=Depends(get_db)):
    row = _require_thought(con, thought_id)
    return row_to_thought(row)


@thoughts_router.put("/{thought_id}")
def update_content(thought_id: int, body: ThoughtUpdate, con=Depends(get_db)):
    _require_thought(con, thought_id)
    content = body.content.strip()
    if not content:
        raise ApiError(400, "empty_content", "Content must not be blank")
    with write_lock(), con:
        con.execute(
            "UPDATE flash_thoughts SET content = ?, updatedAt = ? WHERE id = ?",
            (content, now_ms(), thought_id),
        )
    return row_to_thought(_require_thought(con, thought_id))


@thoughts_router.delete("/{thought_id}")
def soft_delete(thought_id: int, con=Depends(get_db)):
    row = _require_thought(con, thought_id)
    if row["deletedAt"] is not None:
        raise ApiError(404, "thought_not_found", "Thought not found")
    now = now_ms()
    with write_lock(), con:
        con.execute(
            "UPDATE flash_thoughts SET deletedAt = ?, updatedAt = ? WHERE id = ?",
            (now, now, thought_id),
        )
    return {"ok": True}


@thoughts_router.post("/{thought_id}/restore")
def restore(thought_id: int, con=Depends(get_db)):
    row = _require_thought(con, thought_id)
    if row["deletedAt"] is None:
        raise ApiError(400, "not_in_trash", "Thought is not in the trash")
    now = now_ms()
    with write_lock(), con:
        # FlashThoughtDao.restoreToActiveList: pinned returns to the front,
        # everything else goes to the end of the active list.
        sort_order = (
            sort_order_before_first(con) if row["pinned"] else next_active_sort_order(con)
        )
        con.execute(
            "UPDATE flash_thoughts SET deletedAt = NULL, sortOrder = ?, updatedAt = ? WHERE id = ?",
            (sort_order, now, thought_id),
        )
    return row_to_thought(_require_thought(con, thought_id))


@thoughts_router.delete("/{thought_id}/permanent")
def permanent_delete(thought_id: int, con=Depends(get_db)):
    _require_thought(con, thought_id)
    with write_lock(), con:
        cur = con.execute(
            "DELETE FROM flash_thoughts WHERE id = ? AND deletedAt IS NOT NULL", (thought_id,)
        )
        if cur.rowcount == 0:
            raise ApiError(404, "thought_not_trashable", "Only trashed thoughts can be purged")
    return {"ok": True}


@thoughts_router.post("/{thought_id}/pin")
def set_pinned(thought_id: int, body: FlagBody, con=Depends(get_db)):
    row = _require_thought(con, thought_id)
    now = now_ms()
    with write_lock(), con:
        # FlashThoughtDao.togglePinned: pinning moves to the front, unpinning to the end.
        sort_order = (
            sort_order_before_first(con) if body.value else next_active_sort_order(con)
        )
        con.execute(
            "UPDATE flash_thoughts SET pinned = ?, sortOrder = ?, updatedAt = ? WHERE id = ?",
            (1 if body.value else 0, sort_order, now, thought_id),
        )
    return row_to_thought(_require_thought(con, thought_id))


@thoughts_router.post("/{thought_id}/highlight")
def set_highlighted(thought_id: int, body: FlagBody, con=Depends(get_db)):
    _require_thought(con, thought_id)
    now = now_ms()
    with write_lock(), con:
        con.execute(
            "UPDATE flash_thoughts SET highlighted = ?, updatedAt = ? WHERE id = ?",
            (1 if body.value else 0, now, thought_id),
        )
    return row_to_thought(_require_thought(con, thought_id))


@thoughts_router.post("/{thought_id}/move")
def move_to_category(thought_id: int, body: MoveBody, con=Depends(get_db)):
    """切换分类: assign the thought to another category (or 未分类)."""
    _require_thought(con, thought_id)
    if body.categoryId is not None:
        exists = con.execute(
            "SELECT id FROM thought_categories WHERE id = ? LIMIT 1", (body.categoryId,)
        ).fetchone()
        if exists is None:
            raise ApiError(404, "category_not_found", "Category not found")
    with write_lock(), con:
        con.execute(
            "UPDATE flash_thoughts SET categoryId = ?, updatedAt = ? WHERE id = ?",
            (body.categoryId, now_ms(), thought_id),
        )
    return row_to_thought(_require_thought(con, thought_id))


@thoughts_router.post("/reorder")
def reorder(body: list[ReorderItem] | ReorderBody, con=Depends(get_db)):
    """Drag-handle reorder.

    Accepts either:
    - `[{"id": 3, "sortOrder": 0}, ...]`: explicit per-id sortOrder values;
    - `{"items": [{"id": 3}, ...], "scoped": false}`: complete new active order
      (renumbered sequentially, mirroring FlashThoughtDao.replaceActiveOrder);
    - `{"items": [...], "scoped": true, "scopedCategoryId": 7|null}`: new order of
      one group only; other groups keep their global slots
      (mirroring FlashThoughtDao.moveActiveInCategory).
    """
    with _refresh_lock, write_lock(), con:
        if isinstance(body, list):
            # Bare list form: missing sortOrder falls back to the positional index,
            # mirroring FlashThoughtDao.replaceActiveOrder's sequential numbering.
            for index, item in enumerate(body):
                sort_order = int(item.sortOrder) if item.sortOrder is not None else index
                con.execute(
                    "UPDATE flash_thoughts SET sortOrder = ? WHERE id = ? AND deletedAt IS NULL",
                    (sort_order, item.id),
                )
        elif body.scoped:
            group_ids = [item.id for item in body.items]
            move_group_subset(con, group_ids, body.scopedCategoryId)
        else:
            replace_active_order(con, [item.id for item in body.items])
    rows = con.execute(
        f"SELECT * FROM flash_thoughts WHERE deletedAt IS NULL ORDER BY {ACTIVE_ORDER}"
    ).fetchall()
    return [row_to_thought(r) for r in rows]


def move_group_subset(con, group_ids: list[int], category_id: int | None) -> None:
    all_ids = active_ids_in_order(con)
    current_group = active_ids_in_category(con, category_id)
    if not current_group or sorted(group_ids) != sorted(current_group):
        raise ApiError(400, "reorder_mismatch", "Reorder items do not match the category list")
    group_set = set(group_ids)
    replacement = 0
    for index in range(len(all_ids)):
        if all_ids[index] in group_set:
            all_ids[index] = group_ids[replacement]
            replacement += 1
    replace_active_order(con, all_ids)


# ---------------------------------------------------------------------------
# Category endpoints
# ---------------------------------------------------------------------------

CATEGORY_ORDER = "sortOrder ASC, createdAt ASC, id ASC"


@categories_router.get("")
def list_categories(con=Depends(get_db)):
    rows = con.execute(f"SELECT * FROM thought_categories ORDER BY {CATEGORY_ORDER}").fetchall()
    return [row_to_category(r) for r in rows]


def _normalize_category_name(name: str) -> str:
    normalized = name.strip()[:MAX_CATEGORY_NAME_LENGTH].strip()
    if not normalized:
        raise ApiError(400, "invalid_name", "Category name must not be blank")
    return normalized


def _find_by_name(con, name: str) -> int | None:
    row = con.execute(
        "SELECT id FROM thought_categories WHERE name = ? COLLATE NOCASE LIMIT 1", (name,)
    ).fetchone()
    return int(row[0]) if row else None


def _next_category_sort_order(con) -> int:
    row = con.execute("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM thought_categories").fetchone()
    return int(row[0])


@categories_router.post("", status_code=201)
def create_category(body: CategoryBody, con=Depends(get_db)):
    name = _normalize_category_name(body.name)
    color = to_int32(body.colorArgb)
    now = now_ms()
    with _refresh_lock, write_lock(), con:
        if _find_by_name(con, name) is not None:
            raise ApiError(409, "duplicate_name", "A category with this name already exists")
        try:
            cur = con.execute(
                "INSERT INTO thought_categories(name, colorArgb, sortOrder, createdAt, updatedAt)"
                " VALUES(?,?,?,?,?)",
                (name, color, _next_category_sort_order(con), now, now),
            )
        except sqlite3.IntegrityError:
            raise ApiError(409, "duplicate_name", "A category with this name already exists")
        category_id = int(cur.lastrowid)
    row = con.execute("SELECT * FROM thought_categories WHERE id = ?", (category_id,)).fetchone()
    assert row is not None
    return row_to_category(row)


@categories_router.put("/{category_id}")
def update_category(category_id: int, body: CategoryBody, con=Depends(get_db)):
    name = _normalize_category_name(body.name)
    color = to_int32(body.colorArgb)
    exists = con.execute(
        "SELECT id FROM thought_categories WHERE id = ?", (category_id,)
    ).fetchone()
    if exists is None:
        raise ApiError(404, "category_not_found", "Category not found")
    with _refresh_lock, write_lock(), con:
        duplicate = _find_by_name(con, name)
        if duplicate is not None and duplicate != category_id:
            raise ApiError(409, "duplicate_name", "A category with this name already exists")
        try:
            cur = con.execute(
                "UPDATE thought_categories SET name = ?, colorArgb = ?, updatedAt = ? WHERE id = ?",
                (name, color, now_ms(), category_id),
            )
        except sqlite3.IntegrityError:
            raise ApiError(409, "duplicate_name", "A category with this name already exists")
        if cur.rowcount == 0:
            raise ApiError(404, "category_not_found", "Category not found")
    row = con.execute("SELECT * FROM thought_categories WHERE id = ?", (category_id,)).fetchone()
    assert row is not None
    return row_to_category(row)


@categories_router.delete("/{category_id}")
def delete_category(category_id: int, con=Depends(get_db)):
    """删除分类不丢内容：该分类下的小巧思全部归入「未分类」."""
    exists = con.execute(
        "SELECT id FROM thought_categories WHERE id = ?", (category_id,)
    ).fetchone()
    if exists is None:
        raise ApiError(404, "category_not_found", "Category not found")
    with write_lock(), con:
        con.execute("UPDATE flash_thoughts SET categoryId = NULL WHERE categoryId = ?", (category_id,))
        con.execute("DELETE FROM thought_categories WHERE id = ?", (category_id,))
    return {"ok": True}


@categories_router.post("/reorder")
def reorder_categories(body: list[CategoryReorderItem], con=Depends(get_db)):
    with write_lock(), con:
        for index, item in enumerate(body):
            sort_order = int(item.sortOrder) if item.sortOrder is not None else index
            con.execute(
                "UPDATE thought_categories SET sortOrder = ? WHERE id = ?", (sort_order, item.id)
            )
    rows = con.execute(f"SELECT * FROM thought_categories ORDER BY {CATEGORY_ORDER}").fetchall()
    return [row_to_category(r) for r in rows]


# main.py auto-discovery includes only the module-level `router`.
router.include_router(thoughts_router)
router.include_router(categories_router)
