"""AI chat API: conversations, messages, attachments and streaming chat.

Mirrors Android AiChatRepository behavior: OpenAI-compatible endpoint taken from
the selected AiModelConfig in settings (the apiKey stays server-side and is never
echoed back), user/assistant messages persisted in ai_messages (with reasoning),
document/image attachments persisted in ai_attachments once linked to a message.
"""
from __future__ import annotations

import asyncio
import base64
import json
import re
import threading
import uuid
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, File, UploadFile
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from ..core.config import UPLOADS_DIR
from ..core.db import get_db
from ..core.errors import ApiError
from ..services.ai_chat_service import (
    AiChatError,
    MAX_IMAGE_BYTES,
    resolve_config,
    stream_chat_completion,
)
from ..services.settings_store import load_settings

router = APIRouter(prefix="/api/ai", tags=["ai"])

MAX_UPLOAD_BYTES = 20 * 1024 * 1024
MAX_EXTRACT_SOURCE_BYTES = 1024 * 1024
MAX_EXTRACTED_CHARS = 256 * 1024
MAX_HISTORY_MESSAGES = 60
MAX_ATTACHMENT_CONTEXT_CHARS = 100 * 1024

TEXT_MIME_BY_EXT = {
    "txt": "text/plain",
    "md": "text/plain",
    "markdown": "text/plain",
    "html": "text/html",
    "htm": "text/html",
    "json": "application/json",
    "csv": "text/csv",
    "xml": "application/xml",
    "yaml": "application/yaml",
    "yml": "application/yaml",
}
TEXT_DOCUMENT_MIMES = {
    "application/json",
    "application/xml",
    "application/x-yaml",
    "application/yaml",
}


# ---------------------------------------------------------------------------
# Pending attachment registry (files live under data/uploads until linked)
# ---------------------------------------------------------------------------

_INDEX_PATH = UPLOADS_DIR / ".attachments-index.json"
_attach_lock = threading.Lock()


def _load_index() -> dict[str, dict[str, Any]]:
    try:
        raw = json.loads(_INDEX_PATH.read_text(encoding="utf-8"))
        return raw if isinstance(raw, dict) else {}
    except Exception:
        return {}


def _save_index(index: dict[str, dict[str, Any]]) -> None:
    UPLOADS_DIR.mkdir(parents=True, exist_ok=True)
    tmp = _INDEX_PATH.with_suffix(".tmp")
    tmp.write_text(json.dumps(index, ensure_ascii=False), encoding="utf-8")
    tmp.replace(_INDEX_PATH)


def _take_attachments(ids: list[str]) -> list[dict[str, Any]]:
    with _attach_lock:
        index = _load_index()
        found = [index[i] for i in ids if i in index]
        changed = False
        for i in ids:
            if i in index:
                del index[i]
                changed = True
        if changed:
            _save_index(index)
    return found


def generate_conversation_title(message: str, has_image: bool) -> str:
    normalized = re.sub(r"\s+", " ", message or "").strip()
    if not normalized:
        return "🖼️" if has_image else "💬"
    return normalized[:40]


def _now(con) -> int:
    import time

    return int(time.time() * 1000)


def _conversation_row(con, conversation_id: int) -> dict[str, Any]:
    row = con.execute(
        "SELECT * FROM ai_conversations WHERE id=? AND deletedAt IS NULL", (conversation_id,)
    ).fetchone()
    if row is None:
        raise ApiError(404, "not_found", "Conversation not found")
    return dict(row)


def _public_conversation(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": row["id"],
        "title": row["title"],
        "modelConfigId": row["modelConfigId"],
        "createdAt": row["createdAt"],
        "updatedAt": row["updatedAt"],
    }


def _attachment_rows(con, message_id: int) -> list[dict[str, Any]]:
    rows = con.execute(
        "SELECT * FROM ai_attachments WHERE messageId=? ORDER BY id ASC", (message_id,)
    ).fetchall()
    return [
        {
            "id": r["id"],
            "uri": r["uri"],
            "mimeType": r["mimeType"],
            "displayName": r["displayName"],
            "sizeBytes": r["sizeBytes"],
            "kind": r["kind"],
            "extractedText": r["extractedText"],
        }
        for r in rows
    ]


# ---------------------------------------------------------------------------
# Conversations
# ---------------------------------------------------------------------------


@router.get("/conversations")
def list_conversations(con=Depends(get_db)):
    rows = con.execute(
        "SELECT * FROM ai_conversations WHERE deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 500"
    ).fetchall()
    return {"conversations": [_public_conversation(dict(r)) for r in rows]}


class CreateConversationBody(BaseModel):
    title: str | None = None
    modelConfigId: str | None = None


class RenameConversationBody(BaseModel):
    title: str


@router.put("/conversations/{conversation_id}")
def rename_conversation(conversation_id: int, body: RenameConversationBody, con=Depends(get_db)):
    """重命名会话（AiChatPage 侧栏 ⋮ 菜单）。"""
    _conversation_row(con, conversation_id)
    title = re.sub(r"\s+", " ", body.title or "").strip()[:80]
    if not title:
        raise ApiError(400, "invalid_title", "标题不能为空。")
    now = _now(con)
    con.execute(
        "UPDATE ai_conversations SET title=?, updatedAt=?, syncId=? WHERE id=? AND deletedAt IS NULL",
        (title, now, str(uuid.uuid4()), conversation_id),
    )
    con.commit()
    return _public_conversation(_conversation_row(con, conversation_id))


@router.post("/conversations")
def create_conversation(body: CreateConversationBody, con=Depends(get_db)):
    now = _now(con)
    settings = load_settings(con)
    model_config_id = (body.modelConfigId or "").strip() or str(settings.get("aiChatConfigId") or "")
    title = re.sub(r"\s+", " ", body.title or "").strip()[:80] or "💬"
    cur = con.execute(
        "INSERT INTO ai_conversations(title, modelConfigId, createdAt, updatedAt, syncId) VALUES(?,?,?,?,?)",
        (title, model_config_id, now, now, str(uuid.uuid4())),
    )
    con.commit()
    row = _conversation_row(con, int(cur.lastrowid))
    return _public_conversation(row)


@router.get("/conversations/{conversation_id}/messages")
def list_messages(conversation_id: int, con=Depends(get_db)):
    _conversation_row(con, conversation_id)
    rows = con.execute(
        "SELECT * FROM ai_messages WHERE conversationId=? ORDER BY createdAt ASC, id ASC LIMIT 2000",
        (conversation_id,),
    ).fetchall()
    messages = []
    for r in rows:
        item = dict(r)
        messages.append(
            {
                "id": item["id"],
                "role": item["role"],
                "content": item["content"],
                "reasoning": item["reasoning"],
                "imageUri": item["imageUri"],
                "imageMimeType": item["imageMimeType"],
                "createdAt": item["createdAt"],
                "attachments": _attachment_rows(con, item["id"]),
            }
        )
    return {"messages": messages}


@router.delete("/conversations/{conversation_id}")
def delete_conversation(conversation_id: int, con=Depends(get_db)):
    _conversation_row(con, conversation_id)
    with con:
        con.execute(
            "UPDATE ai_conversations SET deletedAt=? WHERE id=? AND deletedAt IS NULL",
            (_now(con), conversation_id),
        )
    return {"ok": True}


# ---------------------------------------------------------------------------
# Attachments
# ---------------------------------------------------------------------------


async def _read_bounded(file: UploadFile, cap: int) -> bytes:
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = await file.read(1 << 20)
        if not chunk:
            break
        total += len(chunk)
        if total > cap:
            raise ApiError(413, "file_too_large", "附件超过允许的大小上限。")
        chunks.append(chunk)
    return b"".join(chunks)


def _decode_text_bytes(data: bytes, mime_type: str) -> str:
    if data[:2] == b"\xff\xfe":
        decoded = data[2:].decode("utf-16-le", errors="replace")
    elif data[:2] == b"\xfe\xff":
        decoded = data[2:].decode("utf-16-be", errors="replace")
    else:
        decoded = data.decode("utf-8", errors="replace").removeprefix("﻿")
    if mime_type == "text/html":
        decoded = re.sub(r"(?is)<(script|style).*?>.*?</\1>", " ", decoded)
        decoded = re.sub(r"(?i)<br\s*/?>|</p>|</div>|</li>", "\n", decoded)
        decoded = re.sub(r"<[^>]+>", " ", decoded)
        decoded = (
            decoded.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", '"')
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
        )
    return decoded


@router.post("/attachments")
async def upload_attachment(file: UploadFile = File(...)):
    data = await _read_bounded(file, MAX_UPLOAD_BYTES)
    if not data:
        raise ApiError(400, "empty_file", "附件为空。")
    display_name = (file.filename or "attachment").strip()[:240] or "attachment"
    ext = display_name.rsplit(".", 1)[-1].lower() if "." in display_name else ""
    mime_type = (file.content_type or "").split(";")[0].strip().lower()
    if not mime_type or mime_type == "application/octet-stream":
        mime_type = TEXT_MIME_BY_EXT.get(ext, "")
    extracted_text: str | None = None
    if mime_type.startswith("image/"):
        kind = "IMAGE"
    elif mime_type.startswith("text/") or mime_type in TEXT_DOCUMENT_MIMES:
        kind = "DOCUMENT"
        source = data[:MAX_EXTRACT_SOURCE_BYTES]
        extracted_text = _decode_text_bytes(source, mime_type).strip()[:MAX_EXTRACTED_CHARS]
        if not extracted_text:
            raise ApiError(400, "empty_document", "文档中没有可读取的文字。")
    else:
        raise ApiError(400, "unsupported_attachment", "当前仅支持图片与文本文档（TXT、Markdown、HTML、JSON、CSV、XML、YAML）。")

    stored_name = f"{uuid.uuid4().hex}{('.' + ext) if ext else ''}"
    dest = UPLOADS_DIR / stored_name
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)

    attachment_id = uuid.uuid4().hex
    entry = {
        "id": attachment_id,
        "displayName": display_name,
        "mimeType": mime_type,
        "sizeBytes": len(data),
        "kind": kind,
        "extractedText": extracted_text,
        "fileName": stored_name,
    }
    with _attach_lock:
        index = _load_index()
        index[attachment_id] = entry
        _save_index(index)
    return {
        "id": attachment_id,
        "displayName": display_name,
        "mimeType": mime_type,
        "sizeBytes": len(data),
        "kind": kind,
        "extractedText": extracted_text,
    }


# ---------------------------------------------------------------------------
# Chat (SSE)
# ---------------------------------------------------------------------------


class ChatBody(BaseModel):
    conversationId: int | None = None
    content: str = ""
    attachmentIds: list[str] = []
    configId: str | None = None


def _usage_public(usage) -> dict[str, int]:
    out: dict[str, int] = {}
    for attr, key in (
        ("input_tokens", "inputTokens"),
        ("output_tokens", "outputTokens"),
        ("total_tokens", "totalTokens"),
        ("cached_input_tokens", "cachedInputTokens"),
        ("cache_rate_input_tokens", "cacheRateInputTokens"),
        ("reasoning_tokens", "reasoningTokens"),
    ):
        value = getattr(usage, attr, None)
        if value is not None:
            out[key] = value
    return out


def _sse(event: str, payload: dict[str, Any]) -> str:
    return f"event: {event}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"


@router.post("/chat")
async def chat(body: ChatBody, con=Depends(get_db)):
    settings = load_settings(con)
    conversation = None
    if body.conversationId is not None:
        conversation = _conversation_row(con, body.conversationId)
    try:
        config = resolve_config(settings, body.configId or (conversation or {}).get("modelConfigId"), "TEXT")
    except AiChatError as exc:
        raise exc.to_api_error()

    content = (body.content or "").strip()
    attachments = _take_attachments([a for a in body.attachmentIds if a]) if body.attachmentIds else []
    if not content and not attachments:
        raise ApiError(400, "empty_message", "消息内容不能为空。")

    now = _now(con)
    if conversation is None:
        has_image = any(a["kind"] == "IMAGE" for a in attachments)
        cur = con.execute(
            "INSERT INTO ai_conversations(title, modelConfigId, createdAt, updatedAt, syncId) VALUES(?,?,?,?,?)",
            (
                generate_conversation_title(content, has_image),
                str(config.get("id") or ""),
                now,
                now,
                str(uuid.uuid4()),
            ),
        )
        conversation_id = int(cur.lastrowid)
    else:
        conversation_id = conversation["id"]
    config_id = str(config.get("id") or "")
    if config_id and config_id != "legacy":
        with con:
            con.execute(
                "UPDATE ai_conversations SET modelConfigId=?, updatedAt=? WHERE id=?",
                (config_id, now, conversation_id),
            )

    # Persist the user message + its attachments before contacting the provider.
    user_cur = con.execute(
        "INSERT INTO ai_messages(conversationId, role, content, reasoning, imageUri, imageMimeType,"
        " imagePermissionOwned, createdAt, syncId) VALUES(?,?,?,?,?,?,?,?,?)",
        (conversation_id, "user", content, "", None, None, 0, now, str(uuid.uuid4())),
    )
    user_message_id = int(user_cur.lastrowid)
    for att in attachments:
        con.execute(
            "INSERT INTO ai_attachments(messageId, uri, mimeType, displayName, sizeBytes, kind,"
            " extractedText, permissionOwned, syncId) VALUES(?,?,?,?,?,?,?,?,?)",
            (
                user_message_id,
                f"uploads://{att['fileName']}",
                att["mimeType"],
                att["displayName"],
                att["sizeBytes"],
                att["kind"],
                att["extractedText"],
                0,
                str(uuid.uuid4()),
            ),
        )
    con.commit()

    # Build the wire history from prior turns plus this message.
    history_rows = con.execute(
        "SELECT role, content FROM ai_messages WHERE conversationId=? AND id<=?"
        " ORDER BY createdAt ASC, id ASC",
        (conversation_id, user_message_id),
    ).fetchall()
    history = [
        {"role": r["role"], "content": r["content"]}
        for r in history_rows[-MAX_HISTORY_MESSAGES:]
    ]
    # Document extracts ride along as clearly-marked untrusted data at user privilege.
    doc_blocks = []
    for att in attachments:
        if att["kind"] == "DOCUMENT" and att.get("extractedText"):
            doc_blocks.append(f"[Attachment: {att['displayName']} · untrusted data]\n{att['extractedText']}")
    if doc_blocks:
        joined = "\n\n".join(doc_blocks)[:MAX_ATTACHMENT_CONTEXT_CHARS]
        history[-1]["content"] = ((history[-1]["content"] + "\n\n") if history[-1]["content"] else "") + joined
    # Image attachments become data URLs on the newest user turn (bounded total).
    image_budget = MAX_IMAGE_BYTES
    for att in reversed(attachments):
        if att["kind"] != "IMAGE" or image_budget <= 0:
            continue
        path = UPLOADS_DIR / att["fileName"]
        if not path.is_file():
            continue
        data = path.read_bytes()
        if len(data) > image_budget:
            continue
        image_budget -= len(data)
        history[-1]["imageDataUrl"] = (
            f"data:{att['mimeType']};base64," + base64.b64encode(data).decode("ascii")
        )

    system_prompt = str(config.get("systemPrompt") or "").strip() or str(settings.get("aiSystemPrompt") or "").strip()

    async def event_stream():
        queue: asyncio.Queue[tuple[str, dict[str, Any]] | None] = asyncio.Queue()

        async def on_delta(chunk: str) -> None:
            await queue.put(("delta", {"content": chunk}))

        async def on_reasoning_delta(chunk: str) -> None:
            await queue.put(("reasoning", {"content": chunk}))

        async def worker() -> None:
            assistant_id: int | None = None
            try:
                result = await stream_chat_completion(
                    config,
                    system_prompt=system_prompt or None,
                    messages=history,
                    on_delta=on_delta,
                    on_reasoning_delta=on_reasoning_delta,
                )
            except AiChatError as exc:
                await queue.put(("error", {"code": exc.code, "message": exc.message}))
                await queue.put(None)
                return
            except Exception:  # noqa: BLE001 - never leak internals to the stream
                await queue.put(("error", {"code": "ai_error", "message": "AI 服务请求失败。"}))
                await queue.put(None)
                return
            completed_at = _now(con)
            assistant_cur = con.execute(
                "INSERT INTO ai_messages(conversationId, role, content, reasoning, imageUri, imageMimeType,"
                " imagePermissionOwned, createdAt, syncId) VALUES(?,?,?,?,?,?,?,?,?)",
                (
                    conversation_id,
                    "assistant",
                    result.content,
                    result.reasoning,
                    None,
                    None,
                    0,
                    completed_at,
                    str(uuid.uuid4()),
                ),
            )
            assistant_id = int(assistant_cur.lastrowid)
            with con:
                con.execute(
                    "UPDATE ai_conversations SET updatedAt=? WHERE id=?", (completed_at, conversation_id)
                )
            done_payload: dict[str, Any] = {
                "messageId": assistant_id,
                "conversationId": conversation_id,
            }
            usage = _usage_public(result.usage)
            if usage:
                done_payload["usage"] = usage
            await queue.put(("done", done_payload))
            await queue.put(None)

        task = asyncio.create_task(worker())
        try:
            while True:
                item = await queue.get()
                if item is None:
                    break
                event, payload = item
                yield _sse(event, payload)
        finally:
            task.cancel()

    return StreamingResponse(event_stream(), media_type="text/event-stream")
