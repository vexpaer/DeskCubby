"""收藏夹 Vault API (server-side encrypted vault).

Endpoints mirror the Android 收藏夹 page contract (README_for_ai.md §12):
status/setup/unlock/lock/change-password plus item CRUD and drag-handle reorder.
All ciphertext handling lives in `services.vault_service`; plaintext exists only
while the session is unlocked.
"""
from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from ..core.db import get_db
from ..services import vault_service

router = APIRouter(prefix="/api/vault", tags=["vault"])


class PasswordBody(BaseModel):
    password: str


class ChangePasswordBody(BaseModel):
    password: str  # current password
    newPassword: str


class ItemBody(BaseModel):
    content: str
    note: str | None = None


class ReorderItem(BaseModel):
    id: int


@router.get("/status")
def get_status():
    return vault_service.status()


@router.post("/setup")
def setup(body: PasswordBody, con=Depends(get_db)):
    vault_service.setup_password(con, body.password)
    return {"ok": True, **vault_service.status()}


@router.post("/unlock")
def unlock(body: PasswordBody, con=Depends(get_db)):
    vault_service.unlock(con, body.password)
    return {"ok": True, **vault_service.status()}


@router.post("/lock")
def lock():
    vault_service.lock()
    return {"ok": True, **vault_service.status()}


@router.post("/change-password")
def change_password(body: ChangePasswordBody, con=Depends(get_db)):
    vault_service.change_password(con, body.password, body.newPassword)
    return {"ok": True, **vault_service.status()}


@router.get("/items")
def list_items(con=Depends(get_db)):
    """VaultContentState shape: decrypted items + opaque corruption count."""
    return vault_service.list_items(con)


@router.post("/items", status_code=201)
def create_item(body: ItemBody, con=Depends(get_db)):
    return vault_service.add_item(con, body.content, body.note)


@router.put("/items/{item_id}")
def update_item(item_id: int, body: ItemBody, con=Depends(get_db)):
    return vault_service.update_item(con, item_id, body.content, body.note)


@router.delete("/items/{item_id}")
def delete_item(item_id: int, con=Depends(get_db)):
    vault_service.delete_item(con, item_id)
    return {"ok": True}


@router.post("/items/reorder")
def reorder_items(body: list[ReorderItem] | dict, con=Depends(get_db)):
    """Accepts `[{"id":3},...]`, `{"ids":[3,...]}` or `{"items":[{"id":3},...]}`."""
    if isinstance(body, list):
        ordered_ids = [int(entry.id) for entry in body]
    elif isinstance(body.get("ids"), list):
        ordered_ids = [int(value) for value in body["ids"]]
    else:
        entries = body.get("items")
        if not isinstance(entries, list):
            from ..core.errors import ApiError

            raise ApiError(400, "invalid_reorder", "排序请求格式无效")
        ordered_ids = [int(entry["id"]) for entry in entries if isinstance(entry, dict) and "id" in entry]
    return vault_service.reorder_items(con, ordered_ids)
