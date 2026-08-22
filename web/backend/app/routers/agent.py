"""Agent API: SSE runs, review ledger queries, mutation undo, approvals, cancel.

Mirrors the Android Agent surface (AgentWebService / AgentReviewRepository):
- POST /api/agent/run streams `event: <type>\ndata: {json}\n\n` frames driven by
  services.agent_runtime.run_agent;
- undo restores a recorded mutation only while the live content still matches
  the recorded after-state, otherwise 409 `content_changed`.
"""
from __future__ import annotations

import asyncio
import base64
import json
import time
from typing import Any

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from ..core import fs
from ..core.config import DIARY_DIR, NOTES_DIR
from ..core.db import get_db
from ..core.errors import ApiError
from ..services.agent_permissions import get_permission_manager
from ..services.agent_review import get_review_store
from ..services.agent_runtime import run_agent
from ..services.settings_store import load_settings, update_settings

router = APIRouter(prefix="/api/agent", tags=["agent"])


def _sse(event: str, payload: dict[str, Any]) -> str:
    return f"event: {event}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"


def _now_ms() -> int:
    return int(time.time() * 1000)


# ---------------------------------------------------------------------------
# Run (SSE)
# ---------------------------------------------------------------------------


class AgentRunBody(BaseModel):
    conversationId: int | None = None
    content: str = ""
    configId: str | None = None
    sourceAuthorizations: list[str] | None = None
    permissionMode: str | None = None


@router.post("/run")
async def start_run(body: AgentRunBody, request: Request, con=Depends(get_db)):
    # Validate eagerly so request-level problems return HTTP errors; model,
    # tool and network failures stream as `error` events with a persisted run.
    if not str(body.content or "").strip():
        raise ApiError(400, "empty_message", "消息内容不能为空。")
    if body.conversationId is not None:
        row = con.execute(
            "SELECT id FROM ai_conversations WHERE id=? AND deletedAt IS NULL", (body.conversationId,)
        ).fetchone()
        if row is None:
            raise ApiError(404, "not_found", "Conversation not found")

    queue: asyncio.Queue[tuple[str, dict[str, Any]] | None] = asyncio.Queue()

    async def emit(event: dict[str, Any]) -> None:
        await queue.put((str(event.get("type") or "event"), event))

    async def worker() -> None:
        try:
            await run_agent(request.app, body, emit)
        except ApiError as exc:
            await queue.put(("error", {"code": exc.code, "message": exc.message}))
        except Exception:  # noqa: BLE001 - never leak internals to the stream
            await queue.put(("error", {"code": "agent_error", "message": "Agent 运行失败。"}))
        finally:
            await queue.put(None)

    async def event_stream():
        task = asyncio.create_task(worker())
        try:
            while True:
                item = await queue.get()
                if item is None:
                    break
                event_type, payload = item
                yield _sse(event_type, payload)
        finally:
            if not task.done():
                task.cancel()

    return StreamingResponse(event_stream(), media_type="text/event-stream")


# ---------------------------------------------------------------------------
# Review ledger queries
# ---------------------------------------------------------------------------


@router.get("/runs")
def list_runs(conversationId: int | None = None, limit: int = 100, con=Depends(get_db)):
    return {"runs": get_review_store().list_runs(con, conversation_id=conversationId, limit=limit)}


@router.get("/runs/{run_id}")
def get_run(run_id: str, con=Depends(get_db)):
    review = get_review_store()
    run = review.get_run(con, run_id)
    if run is None:
        raise ApiError(404, "not_found", "Agent run not found")
    return {"run": run, "toolEvents": review.list_tool_events(con, run_id)}


@router.get("/mutations")
def list_mutations(runId: str | None = None, con=Depends(get_db)):
    return {"mutations": get_review_store().list_mutations(con, runId)}


@router.get("/token-stats")
def token_stats(con=Depends(get_db)):
    return get_review_store().token_stats(con)


# ---------------------------------------------------------------------------
# Undo
# ---------------------------------------------------------------------------


def _changed() -> ApiError:
    return ApiError(409, "content_changed", "内容已发生变化，无法自动撤销。")


def _read_text(path, limit: int = 4 * 1024 * 1024) -> str | None:
    try:
        if not path.is_file() or path.is_symlink():
            return None
        if path.stat().st_size > limit:
            return None
        return path.read_text(encoding="utf-8")
    except OSError:
        return None


def _restore_mutation(con, payload: dict[str, Any], mutation: dict[str, Any]) -> dict[str, Any]:
    """Restore one recorded mutation; raises 409 content_changed on drift."""
    kind = str(payload.get("kind") or "")
    after = mutation["afterContent"]

    if kind == "diary_file":
        name = str(payload.get("name") or "")
        path = fs.sanitize_rel_path(name, DIARY_DIR)
        current = _read_text(path)
        if current is None or current != after:
            raise _changed()
        if not payload.get("previousExisted", True):
            # Undo of a CREATE: retire the created file into the diary trash.
            from ..core.config import DIARY_TRASH_DIR

            fs.move_to_trash(path, DIARY_TRASH_DIR)
        elif isinstance(payload.get("previousContentBase64"), str) and payload["previousContentBase64"]:
            fs.safe_write(path, base64.b64decode(payload["previousContentBase64"]))
        else:
            fs.safe_write_text(path, str(payload.get("previousContent") or ""))
        try:
            from ..services.diary_files import ensure_index_fresh

            ensure_index_fresh(con)
        except Exception:  # noqa: BLE001 - index refresh must not fail the undo
            pass
        return {"restored": True, "kind": kind, "name": name}

    if kind == "note":
        rel = str(payload.get("path") or "")
        path = fs.sanitize_rel_path(rel, NOTES_DIR)
        current = _read_text(path)
        if current is None or current != after:
            raise _changed()
        if not payload.get("previousExisted", True):
            # Undo of a CREATE: retire the created note (recoverable trash).
            fs.move_to_trash(path, NOTES_DIR / ".trash")
        else:
            fs.safe_write_text(path, str(payload.get("previousContent") or ""))
        return {"restored": True, "kind": kind, "path": rel}

    if kind == "thought":
        thought_id = payload.get("id")
        row = con.execute("SELECT content FROM flash_thoughts WHERE id=?", (thought_id,)).fetchone()
        if row is None or str(row["content"]) != after:
            raise _changed()
        if not payload.get("previousExisted", True):
            raise ApiError(409, "undo_unavailable", "This Review item can no longer be undone.")
        with con:
            con.execute(
                "UPDATE flash_thoughts SET content=?, updatedAt=? WHERE id=?",
                (str(payload.get("previousContent") or ""), _now_ms(), thought_id),
            )
        return {"restored": True, "kind": kind, "id": thought_id}

    if kind == "setting":
        key = str(payload.get("key") or "")
        if not key:
            raise _changed()
        current = load_settings(con).get(key)
        if json.dumps(current, ensure_ascii=False, sort_keys=True, default=str) != after:
            raise _changed()
        if not payload.get("previousExisted", True):
            update_settings(con, {key: None})
        else:
            # Runtime snapshots store the prior value under previousContent as
            # JSON text (see _build_undo_payload); decode it back to the real
            # value so booleans/lists survive (update_settings re-serializes).
            raw = payload.get("previousContent")
            if raw is None:
                raw = payload.get("previousValue")  # legacy rows
            if isinstance(raw, str):
                try:
                    value = json.loads(raw)
                except (ValueError, TypeError):
                    value = raw
            else:
                value = raw
            update_settings(con, {key: value})
        return {"restored": True, "kind": kind, "key": key}

    raise ApiError(409, "undo_unavailable", "This Review item can no longer be undone.")


@router.post("/mutations/{mutation_id}/undo")
def undo_mutation(mutation_id: int, con=Depends(get_db)):
    result = get_review_store().undo_mutation(
        con,
        mutation_id,
        lambda payload, mutation: _restore_mutation(con, payload, mutation),
    )
    return {"ok": True, **(result or {})}


# ---------------------------------------------------------------------------
# Approvals
# ---------------------------------------------------------------------------


@router.get("/pending-approvals")
def pending_approvals():
    approvals = [
        {
            "requestId": req.request_id,
            "runId": req.run_id,
            "toolCallId": req.tool_call_id,
            "toolName": req.tool_name,
            "target": req.target,
            "summary": req.summary,
            "argumentsSummary": req.arguments_summary,
        }
        for req in get_permission_manager().pending()
    ]
    return {"approvals": approvals}


class ApprovalDecisionBody(BaseModel):
    approve: bool


@router.post("/approvals/{tool_call_id}")
async def decide_approval(tool_call_id: str, body: ApprovalDecisionBody):
    # Event-loop route: asyncio.Event.set() inside decide() must run on the
    # same loop that awaits the approval event, or the wakeup may be missed.
    decided = get_permission_manager().decide(tool_call_id, bool(body.approve))
    if not decided:
        raise ApiError(404, "approval_not_found", "没有等待中的授权请求。")
    return {"ok": True, "toolCallId": tool_call_id, "approved": bool(body.approve)}


# ---------------------------------------------------------------------------
# Cancel
# ---------------------------------------------------------------------------


@router.post("/cancel/{run_id}")
async def cancel_run(run_id: str, request: Request, con=Depends(get_db)):
    # Event-loop route: Task.cancel() must be invoked from the loop owning the
    # agent run task to guarantee prompt wakeup.
    manager = get_permission_manager()
    registry = getattr(request.app.state, "agent_runs_tasks", {}) or {}
    task = registry.get(run_id)
    if task is not None and not task.done():
        manager.reject_run(run_id)
        task.cancel()
        return {"ok": True, "runId": run_id, "cancelled": True}
    run = get_review_store().get_run(con, run_id)
    if run is None:
        raise ApiError(404, "not_found", "Agent run not found")
    if str(run.get("status") or "") == "RUNNING":
        manager.reject_run(run_id)
        get_review_store().finish_run(
            con,
            run_id,
            "CANCELLED",
            {
                "modelCallCount": run.get("modelCallCount") or 0,
                "reportedCallCount": run.get("usageReportedCallCount") or 0,
                "inputTokens": run.get("inputTokens"),
                "outputTokens": run.get("outputTokens"),
                "totalTokens": run.get("totalTokens"),
                "cachedInputTokens": run.get("cachedInputTokens"),
                "cacheRateInputTokens": run.get("cacheRateInputTokens"),
                "reasoningTokens": run.get("reasoningTokens"),
            },
        )
    return {"ok": True, "runId": run_id, "cancelled": True}
