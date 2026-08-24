"""Agent runtime: bounded streaming tool-calling loop over an OpenAI-compatible endpoint.

Mirrors Android `AgentRuntime` / `OpenAiCompatibleAgentModelClient` /
`AiAgentRequestJson` on the web backend:
- at most MAX_MODEL_ROUNDS model rounds; each round streams content/reasoning
  deltas to the caller through `emit` while accumulating native tool calls;
- canonical wire serialization comes from
  `ai_chat_service.build_tool_request_json` (assistant turns omit `content`
  when blank; tool turns are `{role, tool_call_id, content}` without `name`);
- MUTATION-classified tools go through `agent_permissions` when the effective
  permission mode is REQUIRE_APPROVAL: a pending approval is registered under
  its toolCallId and awaited for at most APPROVAL_TIMEOUT_S (600 s), then
  auto-denied;
- every executed mutation records an `agent_mutations` receipt whose
  undoPayload captures enough state (kind = diary_file | thought | note |
  setting, ids/names, previous bytes/content) for the router to restore it;
- runs are cancellable: the asyncio task registers itself in
  `app.state.agent_runs_tasks[runId]`.

Contract with `services/agent_tools.py` (sibling module):
- `TOOLS: list[ToolSpec]` registry (`name/description/parameters/classification/
  requiredSource`); only specs whose requiredSource is authorized are offered;
- `AgentToolContext(con, settings, authorized_sources, run_id)` +
  `execute_tool(ctx, name, args) -> ToolResult(ok, summary, data, errorCode)`
  run off-loop via `asyncio.to_thread`;
- `build_system_prompt_section(authorized_sources, custom_instructions)`
  supplies the tool-catalog system prompt.

Mutation receipts: agent_tools returns post-state metadata only, so this
runtime captures before/after snapshots itself (diary/note file text,
thought content, setting value) and writes the `agent_mutations` receipt whose
undoPayload = {kind = diary_file|thought|note|setting, name/path/id/key,
previousExisted, previousContent, afterContent}.

Run status values written to `agent_runs.status` use Android's canonical wire
enum: RUNNING -> SUCCEEDED | CANCELED | FAILED.
"""
from __future__ import annotations

import asyncio
import base64
import json
import time
import uuid
from pathlib import Path
from typing import Any, Awaitable, Callable

from ..core.config import UPLOADS_DIR
from ..core.errors import ApiError
from .agent_permissions import ApprovalRequest, get_permission_manager
from .agent_review import get_review_store, summarize_arguments
from .ai_chat_service import (
    AGENT_TOOL_NAME,
    AiChatError,
    MAX_AGENT_ARGUMENT_BYTES,
    MAX_AGENT_TOOL_CALL_ID_CHARS,
    MAX_AGENT_TOOL_CALLS_PER_RESPONSE,
    MAX_BODY_BYTES,
    MAX_IMAGE_BYTES,
    StreamAccumulator,
    TokenUsage,
    ToolCall,
    ToolCompletion,
    _post_bounded,
    _raise_for_status,
    build_tool_request_json,
    merge_usage,
    parse_tool_completion,
    resolve_config,
    stream_chat_completion,
    validate_endpoint,
)
from .settings_store import load_settings

MAX_MODEL_ROUNDS = 12
MAX_HISTORY_MESSAGES = 80
MAX_HISTORY_CONTENT_CHARS = 1024 * 1024
MAX_AGENT_ATTACHMENTS = 5
MAX_TOOL_RESULT_CHARS = 200_000
MAX_SOURCES = 32
MAX_SOURCE_ID_CHARS = 80
LOOP_LIMIT_MESSAGE = "Agent stopped after reaching the maximum number of tool-call rounds."
EMPTY_ROUND_MESSAGE = "AI 返回了空回答。"

EMIT_TYPES = ("started", "delta", "reasoning", "tool_event", "approval_required", "done", "cancelled", "error")

EmitFn = Callable[[dict[str, Any]], Awaitable[None]]


# ---------------------------------------------------------------------------
# Streaming accumulator with native tool-call support
# ---------------------------------------------------------------------------

class AgentToolStreamAccumulator(StreamAccumulator):
    """StreamAccumulator plus OpenAI streaming `delta.tool_calls` assembly."""

    def __init__(self, api_key: str):
        super().__init__(api_key)
        self._call_frags: dict[int, dict[str, Any]] = {}
        self._next_index = 0

    def consume(self, payload: str) -> tuple[str, str] | None:
        stripped = payload.strip()
        if stripped and stripped != "[DONE]":
            try:
                root = json.loads(stripped)
            except ValueError:
                root = None  # parent consume raises the canonical error
            if isinstance(root, dict):
                self._absorb_tool_calls(root)
        return super().consume(payload)

    def _absorb_tool_calls(self, root: dict[str, Any]) -> None:
        choices = root.get("choices")
        choice = choices[0] if isinstance(choices, list) and choices and isinstance(choices[0], dict) else None
        if choice is None:
            return
        delta = choice.get("delta") if isinstance(choice.get("delta"), dict) else {}
        calls = delta.get("tool_calls")
        if not isinstance(calls, list):
            return
        for raw in calls:
            if not isinstance(raw, dict):
                continue
            index = raw.get("index")
            if isinstance(index, bool) or not isinstance(index, int) or index < 0:
                index = self._next_index
            self._next_index = max(self._next_index, index + 1)
            frag = self._call_frags.setdefault(index, {"id": "", "name": "", "arguments": []})
            call_id = raw.get("id")
            if isinstance(call_id, str) and call_id and not frag["id"]:
                frag["id"] = call_id
            function = raw.get("function")
            if isinstance(function, dict):
                name = function.get("name")
                if isinstance(name, str) and name and not frag["name"]:
                    frag["name"] = name
                arguments = function.get("arguments")
                if isinstance(arguments, str) and arguments:
                    frag["arguments"].append(arguments)

    def tool_completion(self) -> ToolCompletion:
        base = self.result()
        calls: list[ToolCall] = []
        ids: set[str] = set()
        if len(self._call_frags) > MAX_AGENT_TOOL_CALLS_PER_RESPONSE:
            raise AiChatError("INVALID_RESPONSE", "模型一次返回了过多工具调用。")
        for index in sorted(self._call_frags.keys()):
            frag = self._call_frags[index]
            call_id = frag["id"].strip()
            name = frag["name"].strip()
            arguments_text = "".join(frag["arguments"])
            try:
                arguments_obj = json.loads(arguments_text or "{}")
            except ValueError:
                raise AiChatError("INVALID_RESPONSE", "模型返回了非法工具参数。")
            if (
                not call_id
                or len(call_id) > MAX_AGENT_TOOL_CALL_ID_CHARS
                or call_id in ids
                or not AGENT_TOOL_NAME.fullmatch(name)
                or len(arguments_text.encode("utf-8")) > MAX_AGENT_ARGUMENT_BYTES
                or not isinstance(arguments_obj, dict)
            ):
                raise AiChatError("INVALID_RESPONSE", "模型返回了非法工具调用。")
            ids.add(call_id)
            calls.append(ToolCall(id=call_id, name=name, arguments=arguments_obj))
        if not calls and not base.content.strip() and not base.reasoning.strip():
            raise AiChatError("INVALID_RESPONSE", EMPTY_ROUND_MESSAGE)
        return ToolCompletion(content=base.content.strip(), reasoning=base.reasoning, tool_calls=calls, usage=self.usage)


# ---------------------------------------------------------------------------
# One streamed model round with native tool definitions
# ---------------------------------------------------------------------------

async def _agent_model_round(
    config: dict[str, Any],
    *,
    system_prompt: str,
    messages: list[dict[str, Any]],
    tools: list[dict[str, Any]],
    emit: EmitFn,
) -> ToolCompletion:
    if not config.get("supportsToolCalling", False):
        # Android 0.23.4 keeps older/non-tool configurations usable as ordinary
        # chat: ignore tool turns/definitions and complete in one round.
        fallback_messages: list[dict[str, Any]] = []
        for message in messages:
            if message.get("role") == "tool":
                continue
            images = message.get("images") or []
            if images and message.get("role") == "user":
                for index, image in enumerate(images):
                    fallback_messages.append({
                        "role": "user",
                        "content": str(message.get("content") or "") if index == 0 else "",
                        "imageDataUrl": image,
                    })
            else:
                fallback_messages.append({
                    "role": "assistant" if message.get("role") == "assistant" else "user",
                    "content": str(message.get("content") or ""),
                })

        async def on_delta(value: str) -> None:
            await emit({"type": "delta", "content": value})

        async def on_reasoning(value: str) -> None:
            await emit({"type": "reasoning", "content": value})

        result = await stream_chat_completion(
            config,
            system_prompt=system_prompt,
            messages=fallback_messages,
            on_delta=on_delta,
            on_reasoning_delta=on_reasoning,
        )
        return ToolCompletion(
            content=result.content,
            reasoning=result.reasoning,
            tool_calls=[],
            usage=result.usage,
        )
    api_key = str(config.get("apiKey") or "").strip()
    allow_insecure = bool(config.get("allowInsecureHttp"))
    endpoint = validate_endpoint(str(config.get("endpointUrl") or ""), allow_insecure)
    model = str(config.get("model") or "").strip()
    if not model:
        raise AiChatError("CONFIGURATION", "请先在 AI 设置中填写模型名称。")
    # Canonical serialization (AiAgentRequestJson): assistant turns omit blank
    # content, tool turns carry only {role, tool_call_id, content}.
    request = build_tool_request_json(
        model=model,
        temperature=config.get("temperature"),
        system_prompt=system_prompt,
        messages=messages,
        tools=tools,
    )
    request["stream"] = True
    body = json.dumps(request, ensure_ascii=False).encode("utf-8")
    if len(body) > MAX_BODY_BYTES:
        raise AiChatError("CONFIGURATION", "Agent 请求内容过长。")

    response, client = await _post_bounded(endpoint, body, api_key, allow_insecure, accept="text/event-stream, application/json")
    try:
        _raise_for_status(response, api_key)
        content_type = response.headers.get("content-type", "").split(";")[0].strip().lower()
        if content_type != "text/event-stream":
            # Provider ignored stream=true: fall back to the JSON tool parser.
            return parse_tool_completion(response.text, api_key)
        accumulator = AgentToolStreamAccumulator(api_key)
        total_bytes = 0
        async for line in response.aiter_lines():
            total_bytes += len(line.encode("utf-8", "replace")) + 1
            if total_bytes > MAX_BODY_BYTES:
                raise AiChatError("RESPONSE_TOO_LARGE", "AI 服务响应超过 4 MiB，已停止读取。")
            if accumulator.done:
                break
            stripped = line.rstrip("\r")
            if not stripped.startswith("data:"):
                continue
            deltas = accumulator.consume(stripped[len("data:"):])
            if deltas is None:
                continue
            new_content, new_reasoning = deltas
            if new_reasoning:
                await emit({"type": "reasoning", "content": new_reasoning})
            if new_content:
                await emit({"type": "delta", "content": new_content})
        return accumulator.tool_completion()
    except AiChatError:
        raise
    except Exception:  # noqa: BLE001 - network layer normalized like ai_chat_service
        raise AiChatError("NETWORK", "无法连接 AI 服务，请检查网络和接口地址。")
    finally:
        aclose = getattr(response, "aclose", None)
        if aclose is not None:
            try:
                await aclose()
            except Exception:  # noqa: BLE001 - best-effort release
                pass
        else:
            response.close()
        await client.aclose()


# ---------------------------------------------------------------------------
# agent_tools adapter (sibling module owns the concrete implementations)
# ---------------------------------------------------------------------------

def _tool_module() -> Any:
    from . import agent_tools

    return agent_tools


def _normalize_parameters(raw: Any) -> dict[str, Any]:
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str) and raw.strip():
        try:
            parsed = json.loads(raw)
        except ValueError:
            return {"type": "object", "properties": {}}
        return parsed if isinstance(parsed, dict) else {"type": "object", "properties": {}}
    return {"type": "object", "properties": {}}


async def _resolve_tool_specs(allowed_sources: list[str]) -> list[dict[str, Any]]:
    """Visible TOOLS entries (module enforces authorization again at dispatch)."""
    mod = _tool_module()
    registry = getattr(mod, "TOOLS", []) or []
    authorized = set(allowed_sources)
    specs: list[dict[str, Any]] = []
    for spec in registry:
        name = str(getattr(spec, "name", "") or "")
        if not name:
            continue
        required_source = getattr(spec, "requiredSource", None)
        if required_source is not None and required_source not in authorized:
            continue
        specs.append(
            {
                "name": name,
                "description": str(getattr(spec, "description", "") or ""),
                "parameters": _normalize_parameters(getattr(spec, "parameters", None)),
                "classification": str(getattr(spec, "classification", "") or "READ_ONLY").upper(),
            }
        )
    return specs[:32]


def _outcome_field(outcome: dict[str, Any], *names: str, default: Any = None) -> Any:
    for name in names:
        if name in outcome and outcome[name] is not None:
            return outcome[name]
    return default


def _mutation_receipt(data: Any) -> dict[str, Any] | None:
    """Extract an undo receipt from a mutation ToolResult.data when present."""
    if not isinstance(data, dict):
        return None
    nested = data.get("undoPayload")
    if isinstance(nested, dict):
        return nested
    if data.get("kind"):
        return {k: v for k, v in data.items() if k != "content"}
    return None


async def _execute_via_agent_tools(
    con,
    *,
    run_id: str,
    sequence: int,
    tool_call_id: str,
    tool_name: str,
    arguments: dict[str, Any],
    allowed_sources: list[str],
    settings: dict[str, Any],
) -> dict[str, Any]:
    mod = _tool_module()
    ctx = mod.AgentToolContext(
        con=con,
        settings=settings if isinstance(settings, dict) else {},
        authorized_sources=set(allowed_sources),
        run_id=run_id,
    )
    # execute_tool is synchronous (SQLite + file I/O); keep the loop streaming.
    result = await asyncio.to_thread(mod.execute_tool, ctx, tool_name, dict(arguments or {}))
    ok = bool(getattr(result, "ok", False))
    summary = str(getattr(result, "summary", "") or "")
    error_code = getattr(result, "errorCode", None)
    data = getattr(result, "data", None)
    data_dict = data if isinstance(data, dict) else {}
    content_obj: dict[str, Any] = {"ok": ok, "summary": summary}
    if data is not None:
        content_obj["data"] = data
    content = json.dumps(content_obj, ensure_ascii=False, default=str)[:MAX_TOOL_RESULT_CHARS]
    return {
        "ok": ok,
        "content": content,
        "summary": summary,
        "target": str(_outcome_field(data_dict, "target", "name", "path", "key", default="") or ""),
        "beforeContent": str(_outcome_field(data_dict, "beforeContent", "before", default="") or ""),
        "afterContent": str(_outcome_field(data_dict, "afterContent", "after", default="") or ""),
        "undoPayload": _mutation_receipt(data),
        "kind": str(_outcome_field(data_dict, "kind", default="") or ""),
        "errorCode": error_code,
    }


# ---------------------------------------------------------------------------
# Mutation receipts: before/after snapshots + undoPayload
# ---------------------------------------------------------------------------

def _mutation_target(tool_name: str, arguments: dict[str, Any]) -> tuple[str | None, Any]:
    """Map a MUTATION tool onto an undoable target (kind, identifier)."""
    args = arguments if isinstance(arguments, dict) else {}
    if tool_name.startswith("diary_"):
        return "diary_file", str(args.get("name") or "")
    if tool_name.startswith("note"):
        return "note", str(args.get("path") or "")
    if "thought" in tool_name:
        return "thought", args.get("id")
    if tool_name.startswith("settings_") or tool_name.startswith("setting_"):
        return "setting", str(args.get("key") or "")
    return None, None


def _read_current_snapshot(con, kind: str | None, target: Any) -> dict[str, Any]:
    """Best-effort current content of one undoable target (runs off-loop)."""
    if kind is None:
        return {"existed": False, "content": ""}
    try:
        if kind == "diary_file":
            from . import diary_files

            doc = diary_files.load_document(str(target))
            return {"existed": True, "content": str(doc.get("content") or "")}
        if kind == "note":
            from . import notes_repo

            note = notes_repo.load_note(str(target))
            return {"existed": True, "content": str(note.get("content") or "")}
        if kind == "thought":
            row = con.execute("SELECT content FROM flash_thoughts WHERE id=?", (target,)).fetchone()
            if row is None:
                return {"existed": False, "content": ""}
            return {"existed": True, "content": str(row["content"])}
        if kind == "setting":
            from .settings_store import load_settings

            value = load_settings(con).get(str(target))
            if value is None:
                return {"existed": False, "content": ""}
            return {
                "existed": True,
                "content": json.dumps(value, ensure_ascii=False, sort_keys=True, default=str),
            }
    except Exception:  # noqa: BLE001 - a missing/unreadable target is just "not there"
        return {"existed": False, "content": ""}
    return {"existed": False, "content": ""}


def _build_undo_payload(
    kind: str | None,
    target: Any,
    tool_name: str,
    snapshot: dict[str, Any],
    after_snapshot: dict[str, Any],
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "kind": kind,
        "toolName": tool_name,
        "previousExisted": bool(snapshot.get("existed")),
        "previousContent": snapshot.get("content") or "",
        "afterContent": (after_snapshot.get("content") or "") if after_snapshot.get("existed") else "",
    }
    if kind == "diary_file":
        payload["name"] = str(target or "")
    elif kind == "note":
        payload["path"] = str(target or "")
    elif kind == "thought":
        payload["id"] = target
    elif kind == "setting":
        payload["key"] = str(target or "")
    return payload


# ---------------------------------------------------------------------------
# Single tool call: ledger event -> approval gate -> execution -> receipt
# ---------------------------------------------------------------------------

async def _run_single_tool_call(
    con,
    *,
    run_id: str,
    sequence: int,
    call: ToolCall,
    classifications: dict[str, str],
    permission_mode: str,
    allowed_sources: list[str],
    settings: dict[str, Any],
    emit: EmitFn,
) -> str:
    review = get_review_store()
    manager = get_permission_manager()
    classification = classifications.get(call.name, "UNKNOWN")
    event_id = review.start_tool_event(
        con,
        run_id=run_id,
        sequence=sequence,
        tool_call_id=call.id,
        tool_name=call.name,
        classification=classification if classification in ("READ_ONLY", "MUTATION") else None,
        arguments_summary=summarize_arguments(call.arguments),
    )
    await emit(
        {
            "type": "tool_event",
            "runId": run_id,
            "sequence": sequence,
            "toolCallId": call.id,
            "toolName": call.name,
            "status": "PREPARING",
        }
    )

    kind, target_value = _mutation_target(call.name, call.arguments)
    snapshot: dict[str, Any] = {"existed": False, "content": ""}
    if classification == "MUTATION" and kind is not None:
        snapshot = await asyncio.to_thread(_read_current_snapshot, con, kind, target_value)

    approved = True
    if classification == "MUTATION" and permission_mode == "REQUIRE_APPROVAL":
        request = ApprovalRequest(
            request_id=uuid.uuid4().hex,
            run_id=run_id,
            tool_call_id=call.id,
            tool_name=call.name,
            target=str(target_value or ""),
            summary=f"{call.name}({summarize_arguments(call.arguments)[:160]})",
            before=(snapshot.get("content") or "")[:4000],
            after="",
            arguments_summary=summarize_arguments(call.arguments),
        )

        async def _on_pending(pending: ApprovalRequest) -> None:
            await emit(
                {
                    "type": "approval_required",
                    "requestId": pending.request_id,
                    "runId": pending.run_id,
                    "toolCallId": pending.tool_call_id,
                    "toolName": pending.tool_name,
                    "summary": pending.summary,
                    "argumentsSummary": pending.arguments_summary,
                }
            )

        approved = await manager.authorize(permission_mode, request, on_pending=_on_pending)

    mutation_id: int | None = None
    if classification == "MUTATION" and approved:
        mutation_id = review.begin_mutation(
            con,
            run_id=run_id,
            event_id=event_id,
            tool_name=call.name,
            target="",
            summary=f"{call.name}({summarize_arguments(call.arguments)[:160]})",
            before="",
            after="",
            undo_payload={},
        )

    if not approved:
        denial = json.dumps({"ok": False, "error": "APPROVAL_DENIED"}, ensure_ascii=False)
        review.finish_tool_event(
            con,
            event_id,
            status="REJECTED",
            summary="The user declined this mutation.",
            error_code="APPROVAL_DENIED",
        )
        await emit(
            {
                "type": "tool_event",
                "runId": run_id,
                "sequence": sequence,
                "toolCallId": call.id,
                "toolName": call.name,
                "status": "REJECTED",
            }
        )
        return denial

    try:
        outcome = await _execute_via_agent_tools(
            con,
            run_id=run_id,
            sequence=sequence,
            tool_call_id=call.id,
            tool_name=call.name,
            arguments=call.arguments,
            allowed_sources=allowed_sources,
            settings=settings,
        )
    except Exception as exc:  # noqa: BLE001 - tool failures feed back to the model
        if mutation_id is not None:
            review.fail_mutation(con, mutation_id)
        message = "Agent 工具执行失败。" if not isinstance(exc, ApiError) else exc.message
        code = getattr(exc, "code", None) or "TOOL_FAILED"
        review.finish_tool_event(con, event_id, status="FAILED", summary=message, error_code=str(code))
        await emit(
            {
                "type": "tool_event",
                "runId": run_id,
                "sequence": sequence,
                "toolCallId": call.id,
                "toolName": call.name,
                "status": "FAILED",
            }
        )
        return json.dumps({"ok": False, "error": str(code)}, ensure_ascii=False)

    content = outcome["content"][:MAX_TOOL_RESULT_CHARS]
    if outcome["ok"]:
        after_snapshot: dict[str, Any] = {"existed": False, "content": ""}
        if kind is not None:
            after_snapshot = await asyncio.to_thread(_read_current_snapshot, con, kind, target_value)
        undo_payload = (
            _build_undo_payload(kind, target_value, call.name, snapshot, after_snapshot)
            if kind is not None
            else (outcome["undoPayload"] if isinstance(outcome["undoPayload"], dict) else {})
        )
        review.finish_tool_event(
            con,
            event_id,
            status="SUCCEEDED",
            target=outcome["target"] or str(target_value or ""),
            summary=outcome["summary"],
            result_summary=content,
        )
        if mutation_id is not None:
            review.complete_mutation(
                con,
                mutation_id,
                before=snapshot.get("content") or outcome["beforeContent"],
                after=undo_payload.get("afterContent") or outcome["afterContent"],
                undo_payload=undo_payload,
            )
        await emit(
            {
                "type": "tool_event",
                "runId": run_id,
                "sequence": sequence,
                "toolCallId": call.id,
                "toolName": call.name,
                "status": "SUCCEEDED",
            }
        )
    else:
        if mutation_id is not None:
            review.fail_mutation(con, mutation_id)
        review.finish_tool_event(
            con,
            event_id,
            status="FAILED",
            target=outcome["target"],
            summary=outcome["summary"] or content[:500],
            result_summary=content,
            error_code=str(outcome["errorCode"] or "TOOL_FAILED"),
        )
        await emit(
            {
                "type": "tool_event",
                "runId": run_id,
                "sequence": sequence,
                "toolCallId": call.id,
                "toolName": call.name,
                "status": "FAILED",
            }
        )
    return content or json.dumps({"ok": outcome["ok"]}, ensure_ascii=False)


# ---------------------------------------------------------------------------
# System prompt
# ---------------------------------------------------------------------------

_FALLBACK_SYSTEM_PROMPT = "You are DeskCubby's local-first assistant. Use the provided tools when they help."
MAX_AGENT_INSTRUCTIONS_CHARS = 20_000


def final_message_sync_id(run_id: str) -> str:
    """Stable Android recovery identity for one Agent run's final reply."""
    return f"agent-final:{run_id}"


def _build_system_prompt(
    con: Any,
    config: dict[str, Any],
    settings: dict[str, Any],
    allowed_sources: list[str],
) -> str:
    # Android appends the global Agent prompt first and the selected model's
    # own instruction second. The legacy ordinary-chat aiSystemPrompt is not
    # an Agent setting.
    custom = "\n".join(
        value for value in (
            str(settings.get("agentPrompt") or "").strip(),
            str(config.get("systemPrompt") or "").strip(),
        ) if value
    )[:MAX_AGENT_INSTRUCTIONS_CHARS]
    try:
        mod = _tool_module()
        metadata = mod.build_source_metadata(
            con,
            settings,
            allowed_sources,
            english=settings.get("appLanguage") == "ENGLISH",
        )
        section = mod.build_system_prompt_section(
            allowed_sources,
            custom_instructions=custom,
            metadata=metadata,
        )
        if section and str(section).strip():
            return str(section)
    except Exception:  # noqa: BLE001 - prompt building must not depend on the module
        pass
    return custom or _FALLBACK_SYSTEM_PROMPT


# ---------------------------------------------------------------------------
# Main entry point
# ---------------------------------------------------------------------------

def normalize_allowed_sources(raw: Any) -> list[str]:
    if isinstance(raw, dict):
        values = [key for key, enabled in raw.items() if enabled is True]
    elif isinstance(raw, list):
        values = raw
    else:
        return []
    seen: list[str] = []
    for item in values:
        value = str(item or "").strip()[:MAX_SOURCE_ID_CHARS]
        if value and value not in seen:
            seen.append(value)
        if len(seen) >= MAX_SOURCES:
            break
    return seen


def _xml_escape(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace('"', "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")[:500]
    )


def _attachment_file(uri: str) -> Path | None:
    prefix = "uploads://"
    if not uri.startswith(prefix):
        return None
    name = uri[len(prefix):]
    if not name or Path(name).name != name:
        return None
    candidate = UPLOADS_DIR / name
    return candidate if candidate.is_file() else None


def _build_agent_history(con: Any, conversation_id: int, up_to_message_id: int) -> list[dict[str, Any]]:
    """Build Android-equivalent bounded history, including untrusted document
    extracts and available device-local images from persisted attachments."""
    rows = con.execute(
        "SELECT * FROM (SELECT id,role,content,createdAt FROM ai_messages "
        "WHERE conversationId=? AND id<=? AND role IN ('user','assistant') "
        "ORDER BY createdAt DESC,id DESC LIMIT ?) ORDER BY createdAt ASC,id ASC",
        (conversation_id, up_to_message_id, MAX_HISTORY_MESSAGES),
    ).fetchall()
    remaining_chars = MAX_HISTORY_CONTENT_CHARS
    remaining_image_bytes = MAX_IMAGE_BYTES
    remaining_images = MAX_AGENT_ATTACHMENTS
    newest_first: list[dict[str, Any]] = []
    for row in reversed(rows):
        if remaining_chars <= 0:
            break
        attachment_rows = con.execute(
            "SELECT uri,mimeType,displayName,kind,extractedText FROM ai_attachments "
            "WHERE messageId=? ORDER BY id ASC",
            (row["id"],),
        ).fetchall()
        documents: list[str] = []
        has_unavailable_image = False
        images: list[str] = []
        for attachment in attachment_rows:
            kind = str(attachment["kind"] or "").upper()
            if kind == "DOCUMENT" and str(attachment["extractedText"] or "").strip():
                documents.append(
                    f'<untrusted_attachment name="{_xml_escape(str(attachment["displayName"] or ""))}" '
                    f'mime="{_xml_escape(str(attachment["mimeType"] or ""))}">\n'
                    f'{attachment["extractedText"]}\n</untrusted_attachment>'
                )
            elif kind == "IMAGE":
                path = _attachment_file(str(attachment["uri"] or ""))
                if path is None:
                    has_unavailable_image = True
                    continue
                try:
                    size = path.stat().st_size
                    if (
                        str(row["role"]) == "user"
                        and remaining_images > 0
                        and 0 < size <= remaining_image_bytes
                    ):
                        data = path.read_bytes()
                        if len(data) == size:
                            images.append(
                                f'data:{attachment["mimeType"]};base64,'
                                + base64.b64encode(data).decode("ascii")
                            )
                            remaining_image_bytes -= size
                            remaining_images -= 1
                except OSError:
                    has_unavailable_image = True
        combined = str(row["content"] or "")
        if documents:
            combined += ("\n\n" if combined else "") + "\n\n".join(documents)
        if has_unavailable_image:
            combined += ("\n\n" if combined else "") + (
                "[An image attachment exists in synced history but its device-local file is unavailable.]"
            )
        combined = combined[-remaining_chars:]
        remaining_chars -= len(combined)
        item: dict[str, Any] = {"role": row["role"], "content": combined}
        if images:
            item["images"] = images
        newest_first.append(item)
    return list(reversed(newest_first))


async def run_agent(app, body, emit: EmitFn) -> dict[str, Any]:
    """Execute one Agent run and stream lifecycle events through `emit`.

    body: {conversationId?, content, configId?, sourceAuthorizations?, permissionMode?}
    """
    con = app.state.db
    review = get_review_store()
    settings = load_settings(con)
    requested_run_id = str(getattr(body, "runId", "") or "").strip()
    run_id = requested_run_id or str(uuid.uuid4())
    if con.execute("SELECT 1 FROM agent_runs WHERE runId=?", (run_id,)).fetchone() is not None:
        raise ApiError(409, "run_exists", "Agent Run 已存在。")

    conversation = None
    if getattr(body, "conversationId", None) is not None:
        row = con.execute(
            "SELECT * FROM ai_conversations WHERE id=? AND deletedAt IS NULL", (body.conversationId,)
        ).fetchone()
        if row is None:
            raise ApiError(404, "not_found", "Conversation not found")
        conversation = dict(row)

    try:
        config = resolve_config(settings, getattr(body, "configId", None) or (conversation or {}).get("modelConfigId"), "TEXT")
    except AiChatError as exc:
        raise exc.to_api_error()

    content = str(getattr(body, "content", "") or "").strip()
    attachment_ids = [str(value or "").strip() for value in (getattr(body, "attachmentIds", None) or [])]
    if (
        len(attachment_ids) > MAX_AGENT_ATTACHMENTS
        or any(not value for value in attachment_ids)
        or len(set(attachment_ids)) != len(attachment_ids)
    ):
        raise ApiError(400, "invalid_attachments", "附件列表无效。")
    if not content and not attachment_ids:
        raise ApiError(400, "empty_message", "消息内容不能为空。")

    # Uploaded attachments are staged by the ordinary chat boundary. Consume
    # every requested id exactly once, then persist it with the user message.
    from ..routers.ai import _take_attachments, generate_conversation_title

    attachments = _take_attachments(attachment_ids) if attachment_ids else []
    if len(attachments) != len(attachment_ids):
        raise ApiError(400, "attachment_missing", "附件已失效，请重新添加。")
    if not content:
        content = "Please analyze the attached content." if settings.get("appLanguage") == "ENGLISH" else "请分析我附加的内容。"

    now_ms = int(time.time() * 1000)
    if conversation is None:
        cursor = con.execute(
            "INSERT INTO ai_conversations(title,modelConfigId,createdAt,updatedAt,syncId) VALUES(?,?,?,?,?)",
            (
                generate_conversation_title(content, any(item.get("kind") == "IMAGE" for item in attachments)),
                str(config.get("id") or ""),
                now_ms,
                now_ms,
                str(uuid.uuid4()),
            ),
        )
        con.commit()
        row = con.execute("SELECT * FROM ai_conversations WHERE id=?", (cursor.lastrowid,)).fetchone()
        conversation = dict(row)
    elif str(config.get("id") or "") and str(config.get("id") or "") != "legacy":
        with con:
            con.execute(
                "UPDATE ai_conversations SET modelConfigId=?,updatedAt=? WHERE id=?",
                (str(config.get("id") or ""), now_ms, conversation["id"]),
            )
        conversation["modelConfigId"] = str(config.get("id") or "")
        conversation["updatedAt"] = now_ms

    allowed_sources = normalize_allowed_sources(getattr(body, "sourceAuthorizations", None))
    requested_mode = str(getattr(body, "permissionMode", "") or "").strip().upper()
    permission_mode = requested_mode if requested_mode in ("FULL_AUTO", "REQUIRE_APPROVAL") else str(
        settings.get("agentPermissionMode") or "REQUIRE_APPROVAL"
    )

    conversation_title = str(conversation.get("title") or content)[:500] or "Agent"
    review.start_run(
        con,
        run_id=run_id,
        conversation_id=conversation["id"],
        conversation_title=conversation_title,
        user_request=content,
        model_config_id=str(config.get("id") or ""),
        permission_mode=permission_mode,
        allowed_sources=allowed_sources,
    )

    tasks_registry: dict[str, asyncio.Task] = getattr(app.state, "agent_runs_tasks", None) or {}
    app.state.agent_runs_tasks = tasks_registry
    approvals_registry = getattr(app.state, "agent_approvals", None)
    if approvals_registry is None:
        app.state.agent_approvals = get_permission_manager()

    # Persist the user turn first (same as chat), then build bounded history.
    # Everything between start_run() and the main try below must still finalize
    # the agent_runs row on failure, or the run would be stuck RUNNING forever
    # (the task is not yet registered for /cancel cleanup in this window).
    try:
        cur = con.execute(
            "INSERT INTO ai_messages(conversationId, role, content, reasoning, imageUri, imageMimeType,"
            " imagePermissionOwned, createdAt, syncId) VALUES(?,?,?,?,?,?,?,?,?)",
            (conversation["id"], "user", content, "", None, None, 0, now_ms, str(uuid.uuid4())),
        )
        user_message_id = int(cur.lastrowid)
        for attachment in attachments:
            con.execute(
                "INSERT INTO ai_attachments(messageId,uri,mimeType,displayName,sizeBytes,kind,"
                "extractedText,permissionOwned,syncId) VALUES(?,?,?,?,?,?,?,?,?)",
                (
                    user_message_id,
                    f"uploads://{attachment['fileName']}",
                    attachment["mimeType"],
                    attachment["displayName"],
                    attachment["sizeBytes"],
                    attachment["kind"],
                    attachment.get("extractedText"),
                    0,
                    str(uuid.uuid4()),
                ),
            )
        con.commit()

        messages = _build_agent_history(con, int(conversation["id"]), user_message_id)

        specs = await _resolve_tool_specs(allowed_sources)
        classifications = {spec["name"]: spec["classification"] for spec in specs}
        system_prompt = _build_system_prompt(con, config, settings, allowed_sources)

        # Inside the guarded window: a client disconnect/cancel during this
        # emit would otherwise leave the run row RUNNING forever.
        await emit(
            {
                "type": "started",
                "runId": run_id,
                "conversationId": conversation["id"],
                "permissionMode": permission_mode,
                "enabledSources": allowed_sources,
            }
        )
    except asyncio.CancelledError:
        get_review_store().finish_run(con, run_id, "CANCELED", {})
        raise
    except Exception:  # noqa: BLE001 - finalize then propagate to the SSE layer
        get_review_store().finish_run(con, run_id, "FAILED", {})
        raise

    total_usage = TokenUsage()
    counters = {"modelCalls": 0, "reportedCalls": 0}

    def usage_dict() -> dict[str, Any]:
        merged = {
            "modelCallCount": counters["modelCalls"],
            "reportedCallCount": counters["reportedCalls"],
            "inputTokens": total_usage.input_tokens,
            "outputTokens": total_usage.output_tokens,
            "totalTokens": total_usage.total_tokens,
            "cachedInputTokens": total_usage.cached_input_tokens,
            "cacheRateInputTokens": total_usage.cache_rate_input_tokens,
            "reasoningTokens": total_usage.reasoning_tokens,
        }
        return merged

    # Persist Android's canonical wire values so Agent review rows can move
    # between Android and Web without translation failures.
    status = "SUCCEEDED"
    final_content = ""
    final_reasoning = ""
    sequence = 0
    task = asyncio.current_task()
    if task is not None:
        tasks_registry[run_id] = task
    manager = get_permission_manager()
    try:
        completed = False
        for _round in range(MAX_MODEL_ROUNDS):
            completion = await _agent_model_round(
                config,
                system_prompt=system_prompt,
                messages=messages,
                tools=[
                    {
                        "name": spec["name"],
                        "description": spec["description"],
                        "parameters": spec["parameters"],
                    }
                    for spec in specs
                ],
                emit=emit,
            )
            counters["modelCalls"] += 1
            if completion.usage.reported:
                counters["reportedCalls"] += 1
            total_usage = merge_usage(total_usage, completion.usage)

            if not completion.tool_calls:
                if not completion.content.strip():
                    raise AiChatError("INVALID_RESPONSE", EMPTY_ROUND_MESSAGE)
                final_content = completion.content.strip()
                final_reasoning = completion.reasoning
                completed = True
                break

            messages.append(
                {
                    "role": "assistant",
                    "content": completion.content,
                    "toolCalls": [
                        {"id": c.id, "name": c.name, "arguments": c.arguments} for c in completion.tool_calls
                    ],
                }
            )
            for call in completion.tool_calls:
                sequence += 1
                result_content = await _run_single_tool_call(
                    con,
                    run_id=run_id,
                    sequence=sequence,
                    call=call,
                    classifications=classifications,
                    permission_mode=permission_mode,
                    allowed_sources=allowed_sources,
                    settings=settings,
                    emit=emit,
                )
                # Canonical tool message: {role:'tool', tool_call_id, content} (no name field).
                messages.append({"role": "tool", "toolCallId": call.id, "content": result_content})
        if not completed:
            raise AiChatError("INVALID_RESPONSE", LOOP_LIMIT_MESSAGE)
    except asyncio.CancelledError:
        status = "CANCELED"
        manager.reject_run(run_id)
        review.finish_run(con, run_id, status, usage_dict())
        await emit({"type": "cancelled", "runId": run_id})
        return {"runId": run_id, "status": status}
    except AiChatError as exc:
        status = "FAILED"
        manager.reject_run(run_id)
        review.finish_run(con, run_id, status, usage_dict())
        await emit({"type": "error", "runId": run_id, "code": exc.code, "message": exc.message})
        return {"runId": run_id, "status": status, "code": exc.code}
    except ApiError as exc:
        status = "FAILED"
        manager.reject_run(run_id)
        review.finish_run(con, run_id, status, usage_dict())
        await emit({"type": "error", "runId": run_id, "code": exc.code, "message": exc.message})
        return {"runId": run_id, "status": status, "code": exc.code}
    except Exception:  # noqa: BLE001 - never leak internals to the stream
        status = "FAILED"
        manager.reject_run(run_id)
        review.finish_run(con, run_id, status, usage_dict())
        await emit({"type": "error", "runId": run_id, "code": "agent_error", "message": "Agent 运行失败。"})
        return {"runId": run_id, "status": status, "code": "agent_error"}
    finally:
        tasks_registry.pop(run_id, None)

    message_id: int | None = None
    if conversation is not None:
        # Persistence of the final assistant turn must never leave the run row
        # stuck RUNNING: finalize first on failure, then re-raise.
        try:
            now = int(time.time() * 1000)
            cur = con.execute(
                "INSERT INTO ai_messages(conversationId, role, content, reasoning, imageUri, imageMimeType,"
                " imagePermissionOwned, createdAt, syncId) VALUES(?,?,?,?,?,?,?,?,?)",
                (
                    conversation["id"],
                    "assistant",
                    final_content,
                    final_reasoning,
                    None,
                    None,
                    0,
                    now,
                    final_message_sync_id(run_id),
                ),
            )
            message_id = int(cur.lastrowid)
            with con:
                con.execute(
                    "UPDATE ai_conversations SET updatedAt=? WHERE id=?", (now, conversation["id"])
                )
        except asyncio.CancelledError:
            review.finish_run(con, run_id, "CANCELED", usage_dict())
            raise
        except Exception:  # noqa: BLE001
            review.finish_run(con, run_id, "FAILED", usage_dict())
            await emit({"type": "error", "runId": run_id, "code": "agent_error", "message": "Agent 运行失败。"})
            return {"runId": run_id, "status": "FAILED", "code": "agent_error"}

    review.finish_run(con, run_id, status, usage_dict())
    done_payload: dict[str, Any] = {"runId": run_id, "status": status}
    if conversation is not None:
        done_payload["conversationId"] = conversation["id"]
    if message_id is not None:
        done_payload["messageId"] = message_id
    usage_public = {k: v for k, v in usage_dict().items() if k.endswith(("Tokens", "Count")) and v}
    if usage_public:
        done_payload["usage"] = usage_public
    await emit(done_payload | {"type": "done"})
    return {"runId": run_id, "status": status}


# Re-exported for router convenience.
__all__ = ["run_agent", "normalize_allowed_sources", "final_message_sync_id", "EMIT_TYPES"]
