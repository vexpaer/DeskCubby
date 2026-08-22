"""Agent review ledger: runs, tool events, mutations and undo.

Mirrors Android AgentReviewRepository on the agent_runs / agent_tool_events /
agent_mutations tables (column names identical to the Room entities). Undo of a
mutation restores the recorded undo payload only while the live content still
matches the recorded after-state; otherwise the caller gets `content_changed`.
"""
from __future__ import annotations

import json
import re
import sqlite3
import threading
import time
from typing import Any

from ..core.errors import ApiError

MAX_TITLE_CHARS = 160
MAX_REQUEST_SUMMARY_CHARS = 500
MAX_MODEL_CONFIG_ID_CHARS = 80
MAX_SOURCES_JSON_CHARS = 2048
MAX_TARGET_CHARS = 1024
MAX_SUMMARY_CHARS = 4096
MAX_RESULT_SUMMARY_CHARS = 256 * 1024
MAX_ARGUMENT_SUMMARY_CHARS = 8192
MAX_REVIEW_CONTENT_CHARS = 256 * 1024
MAX_UNDO_PAYLOAD_CHARS = 512 * 1024
STATUS_APPLIED = "APPLIED"

SECRET_ARGUMENT_KEY = re.compile(
    r"(?i)(api.?key|password|passwd|authorization|credential|secret|session.?token|access.?token|keystore)"
)


def now_ms() -> int:
    return int(time.time() * 1000)


def summarize_arguments(arguments: dict[str, Any]) -> str:
    def safe_value(value: Any) -> str:
        if isinstance(value, dict):
            return "{%d fields}" % len(value)
        text = str(value)
        return re.sub(r"\s+", " ", text)[:160]

    parts = []
    for key in sorted(arguments.keys(), key=str):
        value = arguments[key]
        rendered = "<redacted>" if SECRET_ARGUMENT_KEY.search(str(key)) else safe_value(value)
        parts.append(f"{key}={rendered}")
    return ", ".join(parts)[:MAX_ARGUMENT_SUMMARY_CHARS]


def mutation_operation(tool_name: str) -> str:
    if tool_name.startswith("create_"):
        return "CREATE"
    if tool_name.startswith("delete_"):
        return "DELETE"
    return "UPDATE"


class AgentReviewStore:
    """SQLite-backed review store. All writes go through one process-wide mutex."""

    def __init__(self):
        self._undo_lock = threading.Lock()

    # -- runs --------------------------------------------------------------

    def start_run(
        self,
        con: sqlite3.Connection,
        *,
        run_id: str,
        conversation_id: int | None,
        conversation_title: str,
        user_request: str,
        model_config_id: str,
        permission_mode: str,
        allowed_sources: list[str],
    ) -> None:
        existing = con.execute("SELECT runId FROM agent_runs WHERE runId = ?", (run_id,)).fetchone()
        sources_json = json.dumps(sorted(allowed_sources), ensure_ascii=False)[:MAX_SOURCES_JSON_CHARS]
        if existing is not None:
            return
        with con:
            con.execute(
                "INSERT INTO agent_runs(runId, conversationId, conversationTitle, userRequestSummary,"
                " modelConfigId, permissionMode, enabledSourcesJson, status, startedAt)"
                " VALUES(?,?,?,?,?,?,?,?,?)",
                (
                    run_id,
                    conversation_id,
                    conversation_title.strip()[:MAX_TITLE_CHARS] or "Agent",
                    re.sub(r"\s+", " ", user_request).strip()[:MAX_REQUEST_SUMMARY_CHARS],
                    model_config_id.strip()[:MAX_MODEL_CONFIG_ID_CHARS],
                    permission_mode,
                    sources_json,
                    "RUNNING",
                    now_ms(),
                ),
            )

    def finish_run(self, con: sqlite3.Connection, run_id: str, status: str, usage: dict[str, Any]) -> None:
        with con:
            con.execute(
                "UPDATE agent_runs SET status=?, modelCallCount=?, usageReportedCallCount=?, inputTokens=?,"
                " outputTokens=?, totalTokens=?, cachedInputTokens=?, cacheRateInputTokens=?, reasoningTokens=?,"
                " completedAt=? WHERE runId=?",
                (
                    status[:32],
                    int(usage.get("modelCallCount") or 0),
                    int(usage.get("reportedCallCount") or 0),
                    usage.get("inputTokens"),
                    usage.get("outputTokens"),
                    usage.get("totalTokens"),
                    usage.get("cachedInputTokens"),
                    usage.get("cacheRateInputTokens"),
                    usage.get("reasoningTokens"),
                    now_ms(),
                    run_id,
                ),
            )

    # -- tool events -------------------------------------------------------

    def start_tool_event(
        self,
        con: sqlite3.Connection,
        *,
        run_id: str,
        sequence: int,
        tool_call_id: str,
        tool_name: str,
        classification: str | None,
        arguments_summary: str,
    ) -> int:
        cur = con.execute(
            "INSERT INTO agent_tool_events(runId, sequence, toolCallId, toolName, classification, status,"
            " target, summary, argumentsSummary, resultSummary, errorCode, startedAt)"
            " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                run_id,
                sequence,
                tool_call_id[:200],
                tool_name[:64],
                classification or "UNKNOWN",
                "PREPARING",
                "",
                "",
                arguments_summary[:MAX_ARGUMENT_SUMMARY_CHARS],
                "",
                None,
                now_ms(),
            ),
        )
        con.commit()
        return int(cur.lastrowid)

    def finish_tool_event(
        self,
        con: sqlite3.Connection,
        event_id: int,
        *,
        status: str,
        target: str = "",
        summary: str = "",
        result_summary: str = "",
        error_code: str | None = None,
    ) -> None:
        with con:
            con.execute(
                "UPDATE agent_tool_events SET status=?, target=?, summary=?, resultSummary=?, errorCode=?,"
                " completedAt=? WHERE id=?",
                (
                    status,
                    target[:MAX_TARGET_CHARS],
                    summary[:MAX_SUMMARY_CHARS],
                    result_summary[:MAX_RESULT_SUMMARY_CHARS],
                    (error_code or "")[:80] or None,
                    now_ms(),
                    event_id,
                ),
            )

    # -- mutations ---------------------------------------------------------

    def begin_mutation(
        self,
        con: sqlite3.Connection,
        *,
        run_id: str,
        event_id: int,
        tool_name: str,
        target: str,
        summary: str,
        before: str,
        after: str,
        undo_payload: dict[str, Any],
    ) -> int:
        serialized_undo = json.dumps(undo_payload, ensure_ascii=False)
        if len(serialized_undo) > MAX_UNDO_PAYLOAD_CHARS:
            # Same rule as complete_mutation: never persist truncated JSON
            # (unparseable at undo time) nor NULL (NOT NULL column).
            serialized_undo = '{"truncated":true}'
        cur = con.execute(
            "INSERT INTO agent_mutations(runId, toolEventId, toolName, target, operation, summary,"
            " beforeContent, afterContent, undoPayload, status, createdAt)"
            " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            (
                run_id,
                event_id,
                tool_name,
                target[:MAX_TARGET_CHARS],
                mutation_operation(tool_name),
                summary[:MAX_SUMMARY_CHARS],
                before[:MAX_REVIEW_CONTENT_CHARS],
                after[:MAX_REVIEW_CONTENT_CHARS],
                serialized_undo,
                "PENDING",
                now_ms(),
            ),
        )
        con.commit()
        return int(cur.lastrowid)

    def complete_mutation(
        self,
        con: sqlite3.Connection,
        mutation_id: int,
        *,
        before: str,
        after: str,
        undo_payload: dict[str, Any],
    ) -> None:
        serialized_undo = json.dumps(undo_payload, ensure_ascii=False)
        if len(serialized_undo) > MAX_UNDO_PAYLOAD_CHARS:
            # Truncated JSON would be unparseable at undo time, and NULL is not
            # allowed by the schema (undoPayload TEXT NOT NULL); store a marker
            # object instead — _restore_mutation sees no known kind and returns
            # a clean 409 undo_unavailable.
            serialized_undo = '{"truncated":true}'
        with con:
            con.execute(
                "UPDATE agent_mutations SET beforeContent=?, afterContent=?, undoPayload=?, status=? WHERE id=?",
                (
                    before[:MAX_REVIEW_CONTENT_CHARS],
                    after[:MAX_REVIEW_CONTENT_CHARS],
                    serialized_undo,
                    STATUS_APPLIED,
                    mutation_id,
                ),
            )

    def fail_mutation(self, con: sqlite3.Connection, mutation_id: int) -> None:
        with con:
            con.execute(
                "UPDATE agent_mutations SET status='FAILED' WHERE id=? AND status='PENDING'", (mutation_id,)
            )

    def get_mutation(self, con: sqlite3.Connection, mutation_id: int) -> dict[str, Any] | None:
        row = con.execute("SELECT * FROM agent_mutations WHERE id=?", (mutation_id,)).fetchone()
        return dict(row) if row is not None else None

    # -- queries -----------------------------------------------------------

    def list_runs(self, con: sqlite3.Connection, conversation_id: int | None = None, limit: int = 100) -> list[dict[str, Any]]:
        limit = max(1, min(int(limit), 200))
        if conversation_id is not None:
            rows = con.execute(
                "SELECT * FROM agent_runs WHERE conversationId=? ORDER BY startedAt DESC LIMIT ?",
                (conversation_id, limit),
            ).fetchall()
        else:
            rows = con.execute("SELECT * FROM agent_runs ORDER BY startedAt DESC LIMIT ?", (limit,)).fetchall()
        return [dict(r) for r in rows]

    def get_run(self, con: sqlite3.Connection, run_id: str) -> dict[str, Any] | None:
        row = con.execute("SELECT * FROM agent_runs WHERE runId=?", (run_id,)).fetchone()
        return dict(row) if row is not None else None

    def list_tool_events(self, con: sqlite3.Connection, run_id: str) -> list[dict[str, Any]]:
        rows = con.execute(
            "SELECT * FROM agent_tool_events WHERE runId=? ORDER BY sequence ASC", (run_id,)
        ).fetchall()
        return [dict(r) for r in rows]

    def list_mutations(self, con: sqlite3.Connection, run_id: str | None) -> list[dict[str, Any]]:
        if run_id:
            rows = con.execute(
                "SELECT * FROM agent_mutations WHERE runId=? ORDER BY createdAt DESC", (run_id,)
            ).fetchall()
        else:
            rows = con.execute("SELECT * FROM agent_mutations ORDER BY createdAt DESC LIMIT 200").fetchall()
        return [dict(r) for r in rows]

    def token_stats(self, con: sqlite3.Connection) -> dict[str, Any]:
        row = con.execute(
            "SELECT COUNT(*) AS runCount, COALESCE(SUM(modelCallCount),0) AS modelCallCount,"
            " COALESCE(SUM(usageReportedCallCount),0) AS reportedCallCount,"
            " SUM(inputTokens) AS inputTokens, SUM(outputTokens) AS outputTokens,"
            " SUM(totalTokens) AS totalTokens, SUM(cachedInputTokens) AS cachedInputTokens,"
            " SUM(cacheRateInputTokens) AS cacheRateInputTokens, SUM(reasoningTokens) AS reasoningTokens"
            " FROM agent_runs"
        ).fetchone()
        cached = row["cachedInputTokens"]
        cache_rate_input = row["cacheRateInputTokens"]
        cache_rate = None
        if cached is not None and cache_rate_input is not None and cache_rate_input > 0:
            cache_rate = cached / cache_rate_input
        model_calls = max(int(row["modelCallCount"] or 0), 0)
        reported = max(int(row["reportedCallCount"] or 0), 0)
        return {
            "runCount": int(row["runCount"] or 0),
            "modelCallCount": model_calls,
            "usageReportedCallCount": reported,
            "unreportedCallCount": max(model_calls - reported, 0),
            "inputTokens": row["inputTokens"],
            "outputTokens": row["outputTokens"],
            "totalTokens": row["totalTokens"],
            "cachedInputTokens": cached,
            "reasoningTokens": row["reasoningTokens"],
            "cacheRate": cache_rate,
        }

    # -- undo --------------------------------------------------------------

    def undo_mutation(
        self,
        con: sqlite3.Connection,
        mutation_id: int,
        restore_fn,
    ) -> dict[str, Any]:
        """Undo via `restore_fn(payload)`; refuses when the target changed since the mutation.

        `restore_fn` receives the parsed undo payload and must raise ApiError(409,
        "content_changed") itself when current content no longer matches afterContent.
        Returns the restore result dict.
        """
        with self._undo_lock:
            mutation = self.get_mutation(con, mutation_id)
            if mutation is None:
                raise ApiError(404, "review_not_found", "The Review item no longer exists.")
            if mutation["status"] != STATUS_APPLIED or not mutation["undoPayload"]:
                raise ApiError(409, "undo_unavailable", "This Review item can no longer be undone.")
            try:
                payload = json.loads(mutation["undoPayload"])
            except ValueError:
                raise ApiError(409, "undo_unavailable", "This Review item can no longer be undone.")
            result = restore_fn(payload, mutation) or {}
            with con:
                changed = con.execute(
                    "UPDATE agent_mutations SET status='UNDONE', undoneAt=? WHERE id=? AND status=?",
                    (now_ms(), mutation_id, STATUS_APPLIED),
                ).rowcount
            if changed == 0:
                raise ApiError(409, "review_changed", "Review item changed while Undo was running")
            return result


_review_store = AgentReviewStore()


def get_review_store() -> AgentReviewStore:
    return _review_store
