"""Agent mutation authorization gateway.

Mirrors Android AgentPermissionManager semantics adapted to the web runtime:
- FULL_AUTO approves immediately (everything is still recorded in the ledger);
- REQUIRE_APPROVAL persists a pending marker on the tool-event row, registers an
  in-memory wait keyed by toolCallId and blocks until the user decides through
  POST /api/agent/approvals/{toolCallId} or a 10-minute timeout auto-denies.
"""
from __future__ import annotations

import asyncio
import threading
import time
from dataclasses import dataclass, field

APPROVAL_TIMEOUT_S = 10 * 60
STATUS_PENDING = "PENDING"
STATUS_APPROVED = "APPROVED"
STATUS_REJECTED = "REJECTED"


@dataclass
class ApprovalRequest:
    request_id: str
    run_id: str
    tool_call_id: str
    tool_name: str
    target: str
    summary: str
    before: str = ""
    after: str = ""
    arguments_summary: str = ""


@dataclass
class _Waiting:
    request: ApprovalRequest
    event: asyncio.Event = field(default_factory=asyncio.Event)
    decision: bool = False
    decided: bool = False


class AgentPermissionManager:
    def __init__(self):
        self._lock = threading.Lock()
        self._waiting: dict[str, _Waiting] = {}

    # -- gateway -----------------------------------------------------------

    async def authorize(
        self,
        mode: str,
        request: ApprovalRequest,
        *,
        on_pending: object = None,
    ) -> bool:
        """Returns True when approved. `on_pending(request)` is awaited right after registration."""
        if mode == "FULL_AUTO":
            return True
        waiting = _Waiting(request=request)
        with self._lock:
            self._waiting[request.tool_call_id] = waiting
        try:
            if on_pending is not None:
                await on_pending(request)  # type: ignore[misc]
            try:
                await asyncio.wait_for(waiting.event.wait(), timeout=APPROVAL_TIMEOUT_S)
            except asyncio.TimeoutError:
                return False
            return waiting.decision
        finally:
            with self._lock:
                self._waiting.pop(request.tool_call_id, None)

    # -- user decisions ----------------------------------------------------

    def decide(self, tool_call_id: str, approve: bool) -> bool:
        """Resolve a pending approval. Returns False when no matching pending request exists."""
        with self._lock:
            waiting = self._waiting.get(tool_call_id)
            if waiting is None or waiting.decided:
                return False
            waiting.decided = True
            waiting.decision = approve
        waiting.event.set()
        return True

    def pending(self) -> list[ApprovalRequest]:
        with self._lock:
            return [w.request for w in self._waiting.values() if not w.decided]

    def pending_for_run(self, run_id: str) -> list[ApprovalRequest]:
        with self._lock:
            return [w.request for w in self._waiting.values() if w.request.run_id == run_id and not w.decided]

    def reject_run(self, run_id: str) -> None:
        """Auto-deny everything still waiting for a cancelled run."""
        with self._lock:
            targets = [w for w in self._waiting.values() if w.request.run_id == run_id and not w.decided]
            for waiting in targets:
                waiting.decided = True
                waiting.decision = False
        for waiting in targets:
            waiting.event.set()


_manager = AgentPermissionManager()


def get_permission_manager() -> AgentPermissionManager:
    return _manager


def approval_expired(created_at_ms: int) -> bool:
    return time.time() * 1000 - created_at_ms > APPROVAL_TIMEOUT_S * 1000
