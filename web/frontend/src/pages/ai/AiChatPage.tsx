/**
 * AI 聊天 / DeskCubby Agent (README_for_ai.md §11).
 * Conversation drawer + streaming chat; agent runs stream tool events,
 * approvals, run summary and support Review/Undo.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  BarChart3, Bot, Check, ChevronDown, ChevronRight, Database, FileText, History, Image as ImageIcon,
  Info, LayoutGrid, ListChecks, Lock, MessagesSquare, Paperclip, Pencil, Plus, Send, Settings,
  ShieldCheck, Square, Trash2, Undo2, X,
} from "lucide-react";
import { apiGet, apiSend, apiUpload } from "../../api/client";
import type { AiModelConfig, AppSettings } from "../../api/types";
import { useSettings } from "../../stores/settings";
import { tr } from "../../i18n/tr";
import { MarkdownPreview } from "../../components/MarkdownPreview";
import {
  ConfirmDialog, EmptyState, ErrorText, Modal, PageTutorialOverlay, PopupMenu, Snackbar,
  Spinner, TopBar, useSnackbar,
} from "../../components/ui";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface AiConversation {
  id: number;
  title: string;
  modelConfigId: string;
  createdAt: number;
  updatedAt: number;
}

interface AiAttachmentDto {
  /** Staged uploads use an opaque UUID; persisted message attachments use a DB id. */
  id: string | number;
  displayName: string;
  mimeType: string;
  sizeBytes: number;
  kind: string;
}

interface AiMessageDto {
  id: number;
  conversationId: number;
  role: string;
  content: string;
  reasoning?: string;
  attachments?: AiAttachmentDto[];
  createdAt: number;
}

/** Pending attachment uploaded via POST /api/ai/attachments. */
interface PendingAttachment extends AiAttachmentDto {
  previewUrl?: string;
}

type ToolStatus =
  | "PREPARING" | "RUNNING" | "WAITING_APPROVAL" | "APPROVED" | "REJECTED"
  | "SUCCEEDED" | "FAILED" | "CANCELED";

interface ToolEventUi {
  toolCallId: string;
  toolName: string;
  status: ToolStatus;
  target: string;
  summary: string;
  argumentsSummary: string;
  resultSummary: string;
  errorCode?: string | null;
}

interface ApprovalUi {
  toolCallId: string;
  toolName: string;
  target: string;
  summary: string;
  before: string;
  after: string;
  argumentsSummary: string;
  runId?: string;
}

interface RunUsageUi {
  modelCallCount?: number;
  reportedCallCount?: number;
  inputTokens?: number | null;
  outputTokens?: number | null;
  totalTokens?: number | null;
  cachedInputTokens?: number | null;
  cacheRateInputTokens?: number | null;
  reasoningTokens?: number | null;
}

interface UiMessage {
  key: string;
  role: "user" | "assistant";
  content: string;
  reasoning?: string;
  attachments?: AiAttachmentDto[];
  /** Local object URLs for images of a just-sent user message (thumbnails). */
  previews?: string[];
  toolEvents?: ToolEventUi[];
  approval?: ApprovalUi | null;
  usage?: RunUsageUi;
  runId?: string;
  streaming?: boolean;
  stopped?: boolean;
  error?: string | null;
}

interface AgentRunDetailDto {
  run: AgentRunDto;
  toolEvents: Record<string, unknown>[];
}

interface PendingApprovalsDto {
  approvals: Record<string, unknown>[];
}

// ---------------------------------------------------------------------------
// SSE parsing (manual fetch reader; dispatches on event:/data: lines)
// ---------------------------------------------------------------------------

export type SseEvent = { event: string; data: string };

/** Read `resp` body as an SSE stream, emitting parsed {event,data} frames. */
export async function parseSseStream(
  resp: Response,
  onEvent: (ev: SseEvent) => void
): Promise<void> {
  const reader = resp.body?.getReader();
  if (!reader) throw new Error(tr("响应不是有效的流", "Response is not a readable stream"));
  const decoder = new TextDecoder();
  let buf = "";
  let eventName = "message";
  let dataLines: string[] = [];
  const dispatch = () => {
    if (eventName === "message" && dataLines.length === 0) return;
    onEvent({ event: eventName, data: dataLines.join("\n") });
    eventName = "message";
    dataLines = [];
  };
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    for (;;) {
      const nl = buf.indexOf("\n");
      if (nl < 0) break;
      let line = buf.slice(0, nl);
      buf = buf.slice(nl + 1);
      if (line.endsWith("\r")) line = line.slice(0, -1);
      if (line === "") { dispatch(); continue; }
      if (line.startsWith(":")) continue; // comment / heartbeat
      if (line.startsWith("event:")) eventName = line.slice(6).trim() || "message";
      else if (line.startsWith("data:")) dataLines.push(line.slice(5).replace(/^ /, ""));
      // id:/retry: ignored
    }
  }
  dispatch();
}

/** POST JSON and return the raw Response for SSE consumption (401 handled like client.ts). */
async function openSse(path: string, body: unknown, signal: AbortSignal): Promise<Response> {
  const resp = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
    body: JSON.stringify(body),
    signal,
  });
  if (resp.status === 401) {
    location.href = "/login";
    throw new Error("Authentication required");
  }
  if (!resp.ok) {
    let msg = `Request failed (${resp.status})`;
    try {
      const d = await resp.json();
      if (d?.error?.message) msg = String(d.error.message);
      else if (typeof d?.detail === "string") msg = d.detail;
    } catch { /* not json */ }
    throw new Error(msg);
  }
  return resp;
}

// ---------------------------------------------------------------------------
// Stream event interpretation (tolerant to naming variants)
// ---------------------------------------------------------------------------

export type ChatStreamAction =
  | { kind: "delta"; text: string }
  | { kind: "reasoning"; text: string }
  | { kind: "done"; payload: Record<string, unknown> }
  | { kind: "error"; message: string }
  | { kind: "ignore" };

function jsonOrText(raw: string): Record<string, unknown> | string {
  try {
    const v = JSON.parse(raw);
    return typeof v === "object" && v !== null ? (v as Record<string, unknown>) : String(v);
  } catch {
    return raw;
  }
}

function extractDeltaText(p: unknown): string {
  if (typeof p === "string") return p;
  if (typeof p === "object" && p !== null) {
    const o = p as Record<string, unknown>;
    for (const k of ["delta", "text", "content", "reasoning", "token"]) {
      if (typeof o[k] === "string") return o[k] as string;
    }
  }
  return "";
}

/** Map one SSE frame to a chat action; `message` frames fall back to their `type` field. */
export function interpretChatEvent(ev: SseEvent): ChatStreamAction {
  const payload = jsonOrText(ev.data);
  let eff = ev.event || "message";
  if ((eff === "message" || eff === "") && typeof payload === "object") {
    const t = payload.type;
    if (typeof t === "string" && t) eff = t;
  }
  switch (eff) {
    case "delta":
    case "token":
    case "token_delta":
    case "content": {
      const text = extractDeltaText(payload);
      return text ? { kind: "delta", text } : { kind: "ignore" };
    }
    case "reasoning":
    case "reasoning_delta":
    case "thinking": {
      const text = extractDeltaText(payload);
      return text ? { kind: "reasoning", text } : { kind: "ignore" };
    }
    case "done":
    case "finish":
    case "complete":
      return {
        kind: "done",
        payload: typeof payload === "object" ? (payload as Record<string, unknown>) : {},
      };
    case "error":
    case "stream_error": {
      const msg =
        typeof payload === "object"
          ? String(payload.message ?? payload.error ?? "")
          : String(payload);
      return { kind: "error", message: msg || tr("流式请求失败", "Streaming request failed") };
    }
    default:
      return { kind: "ignore" };
  }
}

// ---------------------------------------------------------------------------
// Agent data sources (mirrors Android AgentDataSource wire values)
// ---------------------------------------------------------------------------

export interface AgentSourceDef {
  value: string;
  label: string;
  description: string;
}

/** The 9 authorized data sources; labels/descriptions copy AiChatScreen.kt. */
export function agentDataSourceDefs(): AgentSourceDef[] {
  return [
    { value: "diary", label: tr("日记", "Diary"), description: tr("按日期检索与读取；允许创建、编辑、删除", "Search/read by date; create, edit, delete") },
    { value: "thoughts", label: tr("小巧思", "Thoughts"), description: tr("正文、分类与时间", "Text, categories, and timestamps") },
    { value: "date_records", label: tr("日期", "Dates"), description: tr("重要日期名称与日期", "Important dates and names") },
    { value: "daily_events", label: tr("结构化记录", "Structured records"), description: tr("日常记录模板", "Daily-record templates") },
    { value: "notes", label: tr("笔记", "Notes"), description: tr("已授权 SAF 笔记目录", "Authorized SAF notes tree") },
    { value: "poems", label: tr("诗词本", "Poetry book"), description: tr("收藏诗词与分类", "Saved poems and categories") },
    { value: "usage", label: tr("手机使用时间", "Phone usage"), description: tr("只读的按日/应用使用数据", "Read-only daily/app usage") },
    { value: "statistics", label: tr("统计数据", "Statistics"), description: tr("只读聚合统计", "Read-only aggregate statistics") },
    { value: "app_guide", label: tr("应用指南", "App guide"), description: tr("应用使用教学按章节索引；只读", "Read-only how-to guide indexed by section") },
  ];
}

// ---------------------------------------------------------------------------
// Agent stream interpretation (tolerant to naming variants)
// ---------------------------------------------------------------------------

export type AgentStreamAction =
  | { kind: "delta"; text: string }
  | { kind: "reasoning"; text: string }
  | { kind: "tool"; event: ToolEventUi }
  | { kind: "approval"; approval: ApprovalUi }
  | { kind: "run"; runId: string }
  | { kind: "usage"; usage: RunUsageUi }
  | { kind: "done"; payload: Record<string, unknown>; cancelled?: boolean }
  | { kind: "error"; message: string }
  | { kind: "ignore" };

function pickStr(o: Record<string, unknown>, keys: string[]): string {
  for (const k of keys) {
    const v = o[k];
    if (typeof v === "string") return v;
    if (typeof v === "number") return String(v);
  }
  return "";
}

function pickUsage(o: Record<string, unknown>): RunUsageUi | null {
  const raw = o.usage ?? o.tokenUsage;
  if (typeof raw !== "object" || raw === null) return null;
  const u = raw as Record<string, unknown>;
  const num = (k: string[]): number | null => {
    for (const key of k) {
      const v = u[key];
      if (typeof v === "number" && Number.isFinite(v)) return v;
    }
    return null;
  };
  const out: RunUsageUi = {
    modelCallCount: num(["modelCallCount", "model_call_count"]) ?? undefined,
    reportedCallCount: num(["reportedCallCount", "usageReportedCallCount", "reported_call_count"]) ?? undefined,
    inputTokens: num(["inputTokens", "input_tokens", "promptTokens"]),
    outputTokens: num(["outputTokens", "output_tokens", "completionTokens"]),
    totalTokens: num(["totalTokens", "total_tokens"]),
    cachedInputTokens: num(["cachedInputTokens", "cached_input_tokens"]),
    cacheRateInputTokens: num(["cacheRateInputTokens", "cache_rate_input_tokens"]),
    reasoningTokens: num(["reasoningTokens", "reasoning_tokens"]),
  };
  if (Object.values(out).every((v) => v === undefined || v === null)) return null;
  return out;
}

function toolEventFromPayload(payload: Record<string, unknown>, fallbackStatus: ToolStatus): ToolEventUi {
  const statusRaw = pickStr(payload, ["status", "state"]).toUpperCase();
  const status = (
    ["PREPARING", "RUNNING", "WAITING_APPROVAL", "APPROVED", "REJECTED", "SUCCEEDED", "FAILED", "CANCELED"].includes(statusRaw)
      ? statusRaw
      : fallbackStatus
  ) as ToolStatus;
  const errorCode = pickStr(payload, ["errorCode", "error_code"]);
  return {
    toolCallId: pickStr(payload, ["toolCallId", "tool_call_id", "callId", "id"]),
    toolName: pickStr(payload, ["toolName", "tool_name", "tool"]),
    status,
    target: pickStr(payload, ["target", "targetSummary"]),
    summary: pickStr(payload, ["summary", "title", "description"]),
    argumentsSummary: pickStr(payload, ["argumentsSummary", "arguments_summary", "argsSummary"]),
    resultSummary: pickStr(payload, ["resultSummary", "result_summary", "result"]),
    errorCode: errorCode || null,
  };
}

function approvalFromPayload(payload: Record<string, unknown>): ApprovalUi {
  return {
    toolCallId: pickStr(payload, ["toolCallId", "tool_call_id", "callId", "id"]),
    toolName: pickStr(payload, ["toolName", "tool_name", "tool"]),
    target: pickStr(payload, ["target", "targetSummary"]),
    summary: pickStr(payload, ["summary", "plan", "description"]),
    before: pickStr(payload, ["before", "beforeContent", "before_content"]),
    after: pickStr(payload, ["after", "afterContent", "after_content"]),
    argumentsSummary: pickStr(payload, ["argumentsSummary", "arguments_summary", "argsSummary"]),
    runId: pickStr(payload, ["runId", "run_id"]) || undefined,
  };
}

/** Map one /api/agent/run SSE frame to an agent action. */
export function interpretAgentEvent(ev: SseEvent): AgentStreamAction {
  const payload = jsonOrText(ev.data);
  let eff = ev.event || "message";
  if ((eff === "message" || eff === "") && typeof payload === "object") {
    const t = payload.type;
    if (typeof t === "string" && t) eff = t;
  }
  switch (eff) {
    case "delta":
    case "token":
    case "token_delta":
    case "content": {
      const text = extractDeltaText(payload);
      return text ? { kind: "delta", text } : { kind: "ignore" };
    }
    case "reasoning":
    case "reasoning_delta":
    case "thinking": {
      const text = extractDeltaText(payload);
      return text ? { kind: "reasoning", text } : { kind: "ignore" };
    }
    case "run_started":
    case "run":
    case "started": {
      if (typeof payload === "object") {
        const runId = pickStr(payload, ["runId", "run_id"]);
        if (runId) return { kind: "run", runId };
      }
      return { kind: "ignore" };
    }
    case "tool_started":
    case "tool_start":
    case "tool_call":
    case "tool_begin":
    case "tool_preparing":
    case "tool_event":
      if (typeof payload === "object") {
        return { kind: "tool", event: toolEventFromPayload(payload, eff === "tool_preparing" ? "PREPARING" : "RUNNING") };
      }
      return { kind: "ignore" };
    case "tool_completed":
    case "tool_complete":
    case "tool_result":
    case "tool_finished":
    case "tool_end":
      if (typeof payload === "object") {
        return { kind: "tool", event: toolEventFromPayload(payload, "SUCCEEDED") };
      }
      return { kind: "ignore" };
    case "approval_required":
    case "approval_pending":
    case "approval":
    case "mutation_approval":
      if (typeof payload === "object") {
        return { kind: "approval", approval: approvalFromPayload(payload) };
      }
      return { kind: "ignore" };
    case "usage":
      if (typeof payload === "object") {
        const usage = pickUsage(payload);
        if (usage) return { kind: "usage", usage };
      }
      return { kind: "ignore" };
    case "cancelled":
    case "canceled":
    case "run_cancelled":
      return { kind: "done", payload: typeof payload === "object" ? payload : {}, cancelled: true };
    case "done":
    case "finish":
    case "complete":
      return {
        kind: "done",
        payload: typeof payload === "object" ? (payload as Record<string, unknown>) : {},
      };
    case "error":
    case "stream_error":
    case "run_error": {
      const msg =
        typeof payload === "object"
          ? String((payload as Record<string, unknown>).message ?? (payload as Record<string, unknown>).error ?? "")
          : String(payload);
      return { kind: "error", message: msg || tr("流式请求失败", "Streaming request failed") };
    }
    default:
      return { kind: "ignore" };
  }
}

/** Merge one streamed tool event into the accumulated list (by toolCallId). */
export function mergeToolEvent(list: ToolEventUi[], next: ToolEventUi): ToolEventUi[] {
  const idx = next.toolCallId
    ? list.findIndex((t) => t.toolCallId && t.toolCallId === next.toolCallId)
    : -1;
  if (idx < 0) return [...list, next];
  return list.map((t, i) => (i === idx ? { ...t, ...next } : t));
}

// ---------------------------------------------------------------------------
// Page shell
// ---------------------------------------------------------------------------

function useMediaQuery(query: string): boolean {
  const [match, setMatch] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const mq = window.matchMedia(query);
    const fn = (e: MediaQueryListEvent) => setMatch(e.matches);
    mq.addEventListener("change", fn);
    return () => mq.removeEventListener("change", fn);
  }, [query]);
  return match;
}

function asArray<T>(d: T[] | { items?: T[]; conversations?: T[]; messages?: T[] } | null | undefined): T[] {
  if (Array.isArray(d)) return d;
  if (d && Array.isArray(d.items)) return d.items;
  if (d && Array.isArray((d as { conversations?: T[] }).conversations)) return (d as { conversations: T[] }).conversations;
  if (d && Array.isArray((d as { messages?: T[] }).messages)) return (d as { messages: T[] }).messages;
  return [];
}

function persistedMessages(d: AiMessageDto[] | { messages?: AiMessageDto[] }): UiMessage[] {
  return asArray<AiMessageDto>(d)
    .filter((m) => ["USER", "user", "ASSISTANT", "assistant"].includes(m.role))
    .map<UiMessage>((m) => ({
      key: `m-${m.id}`,
      role: m.role === "USER" || m.role === "user" ? "user" : "assistant",
      content: m.content ?? "",
      reasoning: m.reasoning || undefined,
      attachments: m.attachments ?? [],
    }));
}

/** A server reload must not erase an in-flight Agent bubble whose final answer is not persisted yet. */
function mergePersistedWithLiveAgent(persisted: UiMessage[], current: UiMessage[]): UiMessage[] {
  const liveRuns = current.filter((m) => m.role === "assistant" && m.streaming && !!m.runId);
  return liveRuns.length > 0 ? [...persisted, ...liveRuns] : persisted;
}

export default function AiChatPage() {
  const settingsState = useSettings();
  const settings: AppSettings | null = settingsState.settings;
  const navigate = useNavigate();
  const [snack, show] = useSnackbar();

  const wide = useMediaQuery("(min-width: 1024px)");
  const [drawerOpen, setDrawerOpen] = useState(false);

  // Conversations -----------------------------------------------------------
  const [conversations, setConversations] = useState<AiConversation[]>([]);
  const [convLoading, setConvLoading] = useState(true);
  const [convError, setConvError] = useState<unknown>(null);
  const [activeId, setActiveId] = useState<number | null>(null);
  const initialConversationResolvedRef = useRef(false);

  const loadConversations = useCallback(async () => {
    setConvError(null);
    try {
      const d = await apiGet<AiConversation[] | { items?: AiConversation[] }>("/api/ai/conversations");
      setConversations(asArray<AiConversation>(d));
    } catch (e) {
      setConvError(e);
    } finally {
      setConvLoading(false);
    }
  }, []);

  useEffect(() => { void loadConversations(); }, [loadConversations]);

  // Android opens the most recently updated conversation once on entry. Keep an explicit
  // "new conversation" selection intact after that initial resolution.
  useEffect(() => {
    if (convLoading || convError || initialConversationResolvedRef.current) return;
    initialConversationResolvedRef.current = true;
    if (activeId == null && conversations.length > 0) setActiveId(conversations[0].id);
  }, [activeId, conversations, convError, convLoading]);

  // Messages of the active conversation --------------------------------------
  const [messages, setMessages] = useState<UiMessage[]>([]);
  const [msgsLoading, setMsgsLoading] = useState(false);
  const [msgsError, setMsgsError] = useState<unknown>(null);

  useEffect(() => {
    if (activeId == null) { setMessages([]); setMsgsError(null); return; }
    let cancelled = false;
    setMsgsLoading(true);
    setMsgsError(null);
    apiGet<AiMessageDto[]>(`/api/ai/conversations/${activeId}/messages`)
      .then((d) => {
        if (cancelled) return;
        const persisted = persistedMessages(d);
        setMessages((current) => mergePersistedWithLiveAgent(persisted, current));
      })
      .catch((e) => { if (!cancelled) setMsgsError(e); })
      .finally(() => { if (!cancelled) setMsgsLoading(false); });
    return () => { cancelled = true; };
  }, [activeId]);

  const selectConversation = (id: number) => {
    if (streaming) {
      show(tr("请先中止当前任务", "Stop the current task first"));
      return;
    }
    setActiveId(id);
    setDrawerOpen(false);
  };

  /** Android: 新会话 clears local state; the conversation is created server-side on first send. */
  const newConversation = () => {
    if (streaming) {
      show(tr("请先中止当前任务", "Stop the current task first"));
      return;
    }
    setActiveId(null);
    setMessages([]);
    setDrawerOpen(false);
  };

  const renameConversation = async (id: number, title: string) => {
    try {
      await apiSend(`/api/ai/conversations/${id}`, "PUT", { title });
      await loadConversations();
      show(tr("已重命名", "Renamed"));
    } catch (e) {
      show(e instanceof Error ? e.message : tr("操作失败", "Operation failed"));
    }
  };

  const deleteConversation = async (id: number) => {
    if (streaming && activeId === id) {
      show(tr("请先中止当前任务", "Stop the current task first"));
      return;
    }
    try {
      await apiSend(`/api/ai/conversations/${id}`, "DELETE");
      if (activeId === id) { setActiveId(null); setMessages([]); }
      await loadConversations();
    } catch (e) {
      show(e instanceof Error ? e.message : tr("操作失败", "Operation failed"));
    }
  };

  // Model selection ----------------------------------------------------------
  const textConfigs = useMemo(
    () => (settings?.aiConfigs ?? []).filter((c) => c.enabled),
    [settings]
  );
  const selectedConfig: AiModelConfig | undefined =
    textConfigs.find((c) => c.id === settings?.aiChatConfigId) ?? textConfigs[0];
  const [modelPickerOpen, setModelPickerOpen] = useState(false);

  // Agent mode / 四方块 sheet -------------------------------------------------
  const [agentMode, setAgentMode] = useState(false);
  const [agentMenuOpen, setAgentMenuOpen] = useState(false);
  const [contextDialogOpen, setContextDialogOpen] = useState(false);
  const [permissionDialogOpen, setPermissionDialogOpen] = useState(false);
  const [toolsInfoOpen, setToolsInfoOpen] = useState(false);
  const [reviewOpen, setReviewOpen] = useState(false);
  const [tokenStatsOpen, setTokenStatsOpen] = useState(false);
  const [decidingApprovalId, setDecidingApprovalId] = useState<string | null>(null);
  const decidingApprovalRef = useRef<string | null>(null);

  // Desk 深链：/ai_chat?prompt=... 预填输入框
  const [searchParams, setSearchParams] = useSearchParams();

  // Composer / attachments / streaming ---------------------------------------
  const [draft, setDraft] = useState("");
  useEffect(() => {
    const prompt = searchParams.get("prompt");
    if (prompt) {
      setDraft(prompt);
      setSearchParams({}, { replace: true });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const [attachments, setAttachments] = useState<PendingAttachment[]>([]);
  const [uploading, setUploading] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  /** Mirror for closures started before activeId changes. */
  const activeIdRef = useRef<number | null>(null);
  useEffect(() => { activeIdRef.current = activeId; }, [activeId]);
  /** Last send payload for the 重试 button of a failed assistant bubble. */
  const lastSendRef = useRef<{ content: string; atts: PendingAttachment[] } | null>(null);

  // Revoke pending image preview URLs when leaving the page.
  useEffect(() => () => {
    // Closing the page detaches the transport only. Agent runs continue on the server and are
    // recovered from their durable ledger when this page is opened again.
    abortRef.current?.abort();
    for (const a of attachmentsRef.current) if (a.previewUrl) URL.revokeObjectURL(a.previewUrl);
  }, []);
  const attachmentsRef = useRef<PendingAttachment[]>([]);
  useEffect(() => { attachmentsRef.current = attachments; }, [attachments]);

  const pickFiles = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    const room = 5 - attachmentsRef.current.length;
    if (room <= 0) {
      show(tr("最多 5 个附件", "Up to 5 attachments"));
      return;
    }
    const list = Array.from(files).slice(0, room);
    if (files.length > room) show(tr("最多 5 个附件", "Up to 5 attachments"));
    setUploading(true);
    try {
      for (const f of list) {
        if (f.size > 8 * 1024 * 1024) {
          show(tr("单个附件上限 8 MiB", "Each attachment is limited to 8 MiB"));
          continue;
        }
        if (attachmentsRef.current.length >= 5) break;
        try {
          const dto = await apiUpload<AiAttachmentDto>("/api/ai/attachments", f);
          const previewUrl = dto.mimeType.startsWith("image/") ? URL.createObjectURL(f) : undefined;
          setAttachments((a) => (a.length >= 5 ? a : [...a, { ...dto, previewUrl }]));
        } catch (e) {
          show(e instanceof Error ? e.message : tr("上传失败", "Upload failed"));
        }
      }
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  };

  const removeAttachment = (id: string | number) => {
    setAttachments((a) => {
      const target = a.find((x) => x.id === id);
      if (target?.previewUrl) URL.revokeObjectURL(target.previewUrl);
      return a.filter((x) => x.id !== id);
    });
  };

  /** Core send: appends both bubbles, streams the assistant reply via SSE. */
  const doSend = async (content: string, atts: PendingAttachment[]) => {
    if (streamingRef.current) return;
    const cfg = selectedConfig;
    if (!cfg) {
      show(tr("请选择可用的文字模型", "Select an available text model"));
      return;
    }
    lastSendRef.current = { content, atts };
    const assistantKey = `a-${Date.now()}`;
    const dtoAtts: AiAttachmentDto[] = atts.map(({ previewUrl: _p, ...dto }) => dto);
    const previews = atts.map((a) => a.previewUrl).filter((u): u is string => !!u);
    setMessages((m) => [
      ...m,
      {
        key: `u-${Date.now()}`, role: "user", content,
        attachments: dtoAtts, previews: previews.length ? previews : undefined,
      },
      { key: assistantKey, role: "assistant", content: "", reasoning: "", streaming: true },
    ]);
    setDraft("");
    setAttachments([]);
    streamingRef.current = true;
    setStreaming(true);
    const controller = new AbortController();
    abortRef.current = controller;
    const patchAssistant = (patch: Partial<UiMessage>) =>
      setMessages((m) => m.map((msg) => (msg.key === assistantKey ? { ...msg, ...patch } : msg)));
    try {
      const resp = await openSse(
        "/api/ai/chat",
        {
          conversationId: activeIdRef.current ?? undefined,
          content,
          attachmentIds: dtoAtts.map((a) => a.id),
          configId: cfg.id,
        },
        controller.signal
      );
      await parseSseStream(resp, (ev) => {
        const action = interpretChatEvent(ev);
        switch (action.kind) {
          case "delta":
            setMessages((m) =>
              m.map((msg) => (msg.key === assistantKey ? { ...msg, content: msg.content + action.text } : msg))
            );
            break;
          case "reasoning":
            setMessages((m) =>
              m.map((msg) =>
                msg.key === assistantKey ? { ...msg, reasoning: (msg.reasoning ?? "") + action.text } : msg
              )
            );
            break;
          case "done": {
            const convId = Number(action.payload.conversationId);
            if (Number.isFinite(convId) && convId > 0 && activeIdRef.current == null) {
              activeIdRef.current = convId;
              setActiveId(convId); // triggers server reload of persisted messages
            }
            patchAssistant({ streaming: false });
            break;
          }
          case "error":
            patchAssistant({ streaming: false, error: action.message });
            break;
          default:
            break;
        }
      });
      patchAssistant({ streaming: false });
    } catch (e) {
      if (controller.signal.aborted) {
        patchAssistant({ streaming: false, stopped: true });
      } else {
        patchAssistant({
          streaming: false,
          error: e instanceof Error ? e.message : String(e),
        });
      }
    } finally {
      streamingRef.current = false;
      setStreaming(false);
      abortRef.current = null;
      void loadConversations();
    }
  };

  const streamingRef = useRef(false);
  useEffect(() => { streamingRef.current = streaming; }, [streaming]);

  /** Live run id for the cancel endpoint while an Agent run streams. */
  const currentRunIdRef = useRef<string>("");
  const stoppingRunIdRef = useRef<string>("");
  /** Run currently projected back into the page from the durable review ledger. */
  const recoveredRunIdRef = useRef<string>("");

  // Reconnect the page to a server-side Agent run after navigation/reload. The SSE stream is an
  // optimization for live deltas; run state and tool events remain authoritative in SQLite.
  useEffect(() => {
    if (activeId == null) return;
    const conversationId = activeId;
    let disposed = false;
    let pollTimer: number | undefined;

    const schedule = (delay = 800) => {
      if (!disposed) pollTimer = window.setTimeout(() => void poll(), delay);
    };

    const finishRecoveredRun = async (run: AgentRunDto, tools: ToolEventUi[]) => {
      const runId = String(run.runId || "");
      const status = String(run.status || "").toUpperCase();
      const usage = pickUsage({ usage: run as Record<string, unknown> }) ?? undefined;
      recoveredRunIdRef.current = "";
      if (currentRunIdRef.current === runId) currentRunIdRef.current = "";
      if (stoppingRunIdRef.current === runId) stoppingRunIdRef.current = "";
      streamingRef.current = false;
      setStreaming(false);
      try {
        const data = await apiGet<AiMessageDto[] | { messages?: AiMessageDto[] }>(
          `/api/ai/conversations/${conversationId}/messages`
        );
        if (disposed || activeIdRef.current !== conversationId) return;
        const persisted = persistedMessages(data);
        if (status === "SUCCEEDED") {
          setMessages(persisted);
        } else {
          setMessages([
            ...persisted,
            {
              key: `run-${runId}`,
              role: "assistant",
              content: "",
              runId,
              toolEvents: tools,
              usage,
              stopped: status === "CANCELED" || undefined,
              error: status === "FAILED" ? tr("Agent 运行失败。", "Agent run failed.") : undefined,
            },
          ]);
        }
      } catch (e) {
        if (!disposed && activeIdRef.current === conversationId) setMsgsError(e);
      }
      if (!disposed) void loadConversations();
    };

    const poll = async () => {
      try {
        let runId = recoveredRunIdRef.current;
        if (!runId) {
          // A locally attached SSE already owns this conversation; it supplies richer token deltas.
          if (currentRunIdRef.current) return;
          const listed = await apiGet<{ runs?: AgentRunDto[] }>(
            `/api/agent/runs?conversationId=${conversationId}&limit=1`
          );
          if (disposed || activeIdRef.current !== conversationId) return;
          const latest = listed.runs?.[0];
          if (!latest || String(latest.status || "").toUpperCase() !== "RUNNING") return;
          runId = String(latest.runId || "");
          if (!runId) return;
          recoveredRunIdRef.current = runId;
        }

        const [detail, pending] = await Promise.all([
          apiGet<AgentRunDetailDto>(`/api/agent/runs/${encodeURIComponent(runId)}`),
          apiGet<PendingApprovalsDto>("/api/agent/pending-approvals"),
        ]);
        if (disposed || activeIdRef.current !== conversationId || recoveredRunIdRef.current !== runId) return;
        const run = detail.run;
        let tools = asArray<Record<string, unknown>>(detail.toolEvents).map((event) =>
          toolEventFromPayload(event, "PREPARING")
        );
        const approvalPayload = asArray<Record<string, unknown>>(pending.approvals)
          .find((item) => pickStr(item, ["runId", "run_id"]) === runId);
        const approval = approvalPayload ? approvalFromPayload(approvalPayload) : null;
        if (approval) {
          tools = mergeToolEvent(tools, {
            toolCallId: approval.toolCallId,
            toolName: approval.toolName,
            status: "WAITING_APPROVAL",
            target: approval.target,
            summary: approval.summary,
            argumentsSummary: approval.argumentsSummary,
            resultSummary: "",
          });
        }

        if (String(run.status || "").toUpperCase() !== "RUNNING") {
          await finishRecoveredRun(run, tools);
          return;
        }

        currentRunIdRef.current = runId;
        streamingRef.current = true;
        setAgentMode(true);
        setStreaming(true);
        setMessages((current) => {
          const next: UiMessage = {
            key: `run-${runId}`,
            role: "assistant",
            content: "",
            reasoning: "",
            runId,
            streaming: true,
            toolEvents: tools,
            approval,
          };
          const index = current.findIndex((message) => message.runId === runId);
          return index < 0
            ? [...current, next]
            : current.map((message, i) => (i === index ? { ...message, ...next } : message));
        });
        schedule();
      } catch {
        // A transient page/API failure must not turn a live server task into a local failure.
        schedule(1500);
      }
    };

    void poll();
    return () => {
      disposed = true;
      if (pollTimer !== undefined) window.clearTimeout(pollTimer);
    };
  }, [activeId, loadConversations]);

  /** Agent send: POST /api/agent/run SSE with tool events, approvals and usage. */
  const doAgentSend = async (content: string, atts: PendingAttachment[]) => {
    if (streamingRef.current) return;
    const cfg = selectedConfig;
    if (!cfg) {
      show(tr("请选择可用的文字模型", "Select an available text model"));
      return;
    }
    lastSendRef.current = { content, atts };
    const assistantKey = `a-${Date.now()}`;
    const requestedRunId = crypto.randomUUID();
    const dtoAtts: AiAttachmentDto[] = atts.map(({ previewUrl: _p, ...dto }) => dto);
    const previews = atts.map((a) => a.previewUrl).filter((u): u is string => !!u);
    const enabledSources = settings?.agentEnabledSources ?? [];
    const sourceAuthorizations: Record<string, boolean> = {};
    for (const s of agentDataSourceDefs()) sourceAuthorizations[s.value] = enabledSources.includes(s.value);
    setMessages((m) => [
      ...m,
      {
        key: `u-${Date.now()}`, role: "user", content,
        attachments: dtoAtts, previews: previews.length ? previews : undefined,
      },
      {
        key: assistantKey, role: "assistant", content: "", reasoning: "", runId: requestedRunId,
        streaming: true, toolEvents: [], approval: null,
      },
    ]);
    setDraft("");
    setAttachments([]);
    streamingRef.current = true;
    setStreaming(true);
    currentRunIdRef.current = requestedRunId;
    stoppingRunIdRef.current = "";
    const controller = new AbortController();
    abortRef.current = controller;
    const patchAssistant = (patch: Partial<UiMessage>) =>
      setMessages((m) => m.map((msg) => (msg.key === assistantKey ? { ...msg, ...patch } : msg)));
    const updateTools = (fn: (list: ToolEventUi[]) => ToolEventUi[]) =>
      setMessages((m) =>
        m.map((msg) => (msg.key === assistantKey ? { ...msg, toolEvents: fn(msg.toolEvents ?? []) } : msg))
      );
    try {
      const resp = await openSse(
        "/api/agent/run",
        {
          runId: requestedRunId,
          conversationId: activeIdRef.current ?? undefined,
          content,
          attachmentIds: dtoAtts.map((a) => a.id),
          configId: cfg.id,
          sourceAuthorizations,
          permissionMode: settings?.agentPermissionMode ?? "REQUIRE_APPROVAL",
        },
        controller.signal
      );
      await parseSseStream(resp, (ev) => {
        const action = interpretAgentEvent(ev);
        switch (action.kind) {
          case "delta":
            setMessages((m) =>
              m.map((msg) => (msg.key === assistantKey ? { ...msg, content: msg.content + action.text } : msg))
            );
            break;
          case "reasoning":
            setMessages((m) =>
              m.map((msg) =>
                msg.key === assistantKey ? { ...msg, reasoning: (msg.reasoning ?? "") + action.text } : msg
              )
            );
            break;
          case "run":
            currentRunIdRef.current = action.runId;
            patchAssistant({ runId: action.runId });
            break;
          case "tool":
            updateTools((list) => mergeToolEvent(list, action.event));
            break;
          case "approval": {
            const approval = action.approval;
            updateTools((list) =>
              mergeToolEvent(list, {
                toolCallId: approval.toolCallId,
                toolName: approval.toolName,
                status: "WAITING_APPROVAL",
                target: approval.target,
                summary: approval.summary,
                argumentsSummary: approval.argumentsSummary,
                resultSummary: "",
              })
            );
            patchAssistant({ approval });
            break;
          }
          case "usage":
            patchAssistant({ usage: action.usage });
            break;
          case "done": {
            const convId = Number(action.payload.conversationId);
            if (Number.isFinite(convId) && convId > 0 && activeIdRef.current == null) {
              activeIdRef.current = convId;
              setActiveId(convId);
            }
            if (typeof action.payload.usage === "object" && action.payload.usage !== null) {
              const u = pickUsage(action.payload as Record<string, unknown>);
              if (u) patchAssistant({ usage: u });
            }
            patchAssistant({
              streaming: false,
              stopped: action.cancelled || String(action.payload.status ?? "").toUpperCase() === "CANCELED" || undefined,
            });
            break;
          }
          case "error":
            patchAssistant({ streaming: false, error: action.message });
            break;
          default:
            break;
        }
      });
      patchAssistant({ streaming: false });
    } catch (e) {
      if (controller.signal.aborted) {
        patchAssistant({ streaming: false, stopped: true });
      } else {
        patchAssistant({
          streaming: false,
          error: e instanceof Error ? e.message : String(e),
        });
      }
    } finally {
      streamingRef.current = false;
      setStreaming(false);
      abortRef.current = null;
      currentRunIdRef.current = "";
      stoppingRunIdRef.current = "";
      void loadConversations();
    }
  };

  /** 批准 / 拒绝 one pending mutation of the running agent task. */
  const decideApproval = async (toolCallId: string, approve: boolean) => {
    if (decidingApprovalRef.current) return;
    decidingApprovalRef.current = toolCallId;
    setDecidingApprovalId(toolCallId);
    try {
      await apiSend(`/api/agent/approvals/${encodeURIComponent(toolCallId)}`, "POST", { approve });
      setMessages((m) =>
        m.map((msg) => {
          if (msg.role !== "assistant") return msg;
          const tools = (msg.toolEvents ?? []).map((t) =>
            t.toolCallId === toolCallId
              ? { ...t, status: approve ? ("APPROVED" as ToolStatus) : ("REJECTED" as ToolStatus) }
              : t
          );
          return {
            ...msg,
            toolEvents: tools,
            approval: msg.approval?.toolCallId === toolCallId ? null : msg.approval,
          };
        })
      );
      show(approve ? tr("已批准，Agent 继续执行", "Approved; the Agent continues") : tr("已拒绝", "Rejected"));
    } catch (e) {
      show(e instanceof Error ? e.message : tr("操作失败", "Operation failed"));
    } finally {
      decidingApprovalRef.current = null;
      setDecidingApprovalId(null);
    }
  };

  const sendFromComposer = () => {
    const content = draft; // trimmed inside doSend guard; keep raw for pre-wrap display
    if (!content.trim() && attachments.length === 0) return;
    if (agentMode) void doAgentSend(content, attachments);
    else void doSend(content, attachments);
  };

  /** 中止：Agent 运行时同时请求服务端取消；待审批修改按拒绝处理。 */
  const stopStreaming = () => {
    const runId = currentRunIdRef.current;
    if (agentMode && runId) {
      if (stoppingRunIdRef.current === runId) return;
      stoppingRunIdRef.current = runId;
      void apiSend(`/api/agent/cancel/${encodeURIComponent(runId)}`, "POST")
        .then(() => abortRef.current?.abort())
        .catch((error: unknown) => {
          stoppingRunIdRef.current = "";
          show(error instanceof Error ? error.message : tr("中止失败，请重试", "Could not stop; try again"));
        });
      return;
    }
    abortRef.current?.abort();
  };

  const activeTitle =
    conversations.find((c) => c.id === activeId)?.title ?? "";

  if (!settings) return <Spinner />;

  return (
    <div style={{ display: "flex", gap: 16, alignItems: "stretch" }}>
      {/* Conversation list drawer: static pane ≥1024px, overlay below */}
      {wide ? (
        <div style={{ width: 280, flexShrink: 0 }}>
          <ConversationPane
            conversations={conversations}
            loading={convLoading}
            error={convError}
            activeId={activeId}
            onSelect={selectConversation}
            onNew={newConversation}
            onRename={renameConversation}
            onDelete={deleteConversation}
            onRetry={() => void loadConversations()}
          />
        </div>
      ) : null}
      {!wide && drawerOpen ? (
        <div
          style={{ position: "fixed", inset: 0, zIndex: 250, background: "var(--dc-scrim)" }}
          onClick={() => setDrawerOpen(false)}
        >
          <div
            style={{
              position: "absolute", left: 0, top: 0, bottom: 0, width: "min(320px, 85vw)",
              background: "var(--dc-surface-container-high)", padding: 12, overflowY: "auto",
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <ConversationPane
              conversations={conversations}
              loading={convLoading}
              error={convError}
              activeId={activeId}
              onSelect={selectConversation}
              onNew={newConversation}
              onRename={renameConversation}
              onDelete={deleteConversation}
              onRetry={() => void loadConversations()}
            />
          </div>
        </div>
      ) : null}

      {/* Thread + composer column, centered and capped by aiReplyBoxWidthDp */}
      <div className="dc-grow dc-col" style={{ maxWidth: settings.aiReplyBoxWidthDp, margin: "0 auto", width: "100%" }}>
        <TopBar
          back={!wide}
          onBack={() => setDrawerOpen(true)}
          title={activeTitle || tr("DeskCubby Agent", "DeskCubby Agent")}
          subtitle={
            activeTitle
              ? tr("会话自动保存，可选择云同步", "Auto-saved; optional cloud sync")
              : tr("按需调用工具，不预加载全文", "Tools on demand; no bulk content preload")
          }
          actions={
            <>
              <button
                className={`dc-chip ${agentMode ? "active" : ""}`}
                aria-label={tr("Agent 模式", "Agent mode")}
                title={tr("Agent 模式：按需调用工具执行任务", "Agent mode: run tasks with on-demand tools")}
                onClick={() => {
                  if (streaming) { show(tr("请先中止当前任务", "Stop the current task first")); return; }
                  setAgentMode((v) => !v);
                }}
              >
                <Bot size={14} />
                <span>Agent</span>
              </button>
              <button
                className="dc-icon-btn"
                aria-label={tr("Agent Review", "Agent Review")}
                title={tr("Agent Review：查看真实修改并撤回", "Agent Review: inspect changes and undo")}
                onClick={() => setReviewOpen(true)}
              >
                <ListChecks size={20} />
              </button>
              <button
                className="dc-icon-btn"
                aria-label={tr("Token 统计", "Token statistics")}
                title={tr("Token 统计", "Token statistics")}
                onClick={() => setTokenStatsOpen(true)}
              >
                <BarChart3 size={20} />
              </button>
              <button
                className="dc-chip"
                aria-label={tr("当前 Agent 模型", "Current Agent model")}
                title={tr("当前 Agent 模型", "Current Agent model")}
                onClick={() => setModelPickerOpen(true)}
                style={{ maxWidth: 200 }}
              >
                <Check size={14} style={{ color: selectedConfig?.supportsToolCalling ? "var(--dc-primary)" : "var(--dc-on-surface-variant)" }} />
                <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  {selectedConfig?.name ?? tr("请选择", "Select one")}
                </span>
              </button>
              {!wide && (
                <button className="dc-icon-btn" aria-label={tr("会话历史", "Conversation history")} onClick={() => setDrawerOpen(true)}>
                  <History size={20} />
                </button>
              )}
            </>
          }
        />

        <ThreadView
          messages={messages}
          loading={msgsLoading}
          error={msgsError}
          configured={!!selectedConfig}
          fontSizeSp={settings.aiPageFontSizeSp}
          onRetry={() => { setMsgsError(null); if (activeId != null) { const id = activeId; setActiveId(null); setTimeout(() => setActiveId(id), 0); } }}
          onRetryLast={
            lastSendRef.current && !streaming
              ? () => { const s = lastSendRef.current; if (s) void (agentMode ? doAgentSend(s.content, s.atts) : doSend(s.content, s.atts)); }
              : undefined
          }
          onDecideApproval={(toolCallId, approve) => void decideApproval(toolCallId, approve)}
          decidingApprovalId={decidingApprovalId}
        />

        <Composer
          draft={draft}
          onDraftChange={setDraft}
          onSend={sendFromComposer}
          onStop={stopStreaming}
          streaming={streaming}
          canSend={!!draft.trim() || attachments.length > 0}
          configured={!!selectedConfig}
          attachments={attachments}
          uploading={uploading}
          onPickFiles={(files) => void pickFiles(files)}
          onRemoveAttachment={removeAttachment}
          fileInputRef={fileInputRef}
          agentMode={agentMode}
          onOpenAgentMenu={() => setAgentMenuOpen(true)}
        />

        <PageTutorialOverlay
          pageKey="ai_chat"
          title={tr("AI 聊天", "AI chat")}
          lines={[
            tr("左侧选择或新建会话；发送第一条消息时自动保存。", "Pick or create a conversation on the left; it is saved with your first message."),
            tr("Agent 会按需调用工具，执行过程与修改可在 Review 中查看和撤回。", "The Agent calls tools on demand; every step and change can be inspected and undone in Review."),
          ]}
        />
        <Snackbar message={snack} />
      </div>

      <ModelPickerModal
        open={modelPickerOpen}
        configs={textConfigs}
        selectedId={selectedConfig?.id}
        onClose={() => setModelPickerOpen(false)}
        onPick={(id) => { void settingsState.update({ aiChatConfigId: id }); setModelPickerOpen(false); }}
        onOpenSettings={() => { setModelPickerOpen(false); navigate("/settings"); }}
      />

      <AgentSheet
        open={agentMenuOpen}
        agentMode={agentMode}
        onClose={() => setAgentMenuOpen(false)}
        onPickFiles={(files) => void pickFiles(files)}
        fileInputRef={fileInputRef}
        uploading={uploading}
        onManageContext={() => setContextDialogOpen(true)}
        onPermissionMode={() => setPermissionDialogOpen(true)}
        onToolsInfo={() => setToolsInfoOpen(true)}
      />
      <AgentContextDialog
        open={contextDialogOpen}
        enabledSources={settings?.agentEnabledSources ?? []}
        onToggle={(value, checked) => {
          const current = new Set(settings?.agentEnabledSources ?? []);
          if (checked) current.add(value);
          else current.delete(value);
          void settingsState.update({ agentEnabledSources: [...current] });
        }}
        onClose={() => setContextDialogOpen(false)}
      />
      <AgentPermissionDialog
        open={permissionDialogOpen}
        mode={settings?.agentPermissionMode ?? "REQUIRE_APPROVAL"}
        onSelect={(mode) => { void settingsState.update({ agentPermissionMode: mode }); }}
        onClose={() => setPermissionDialogOpen(false)}
      />
      <AgentToolsInfoDialog open={toolsInfoOpen} onClose={() => setToolsInfoOpen(false)} />
      <TokenStatsDialog open={tokenStatsOpen} onClose={() => setTokenStatsOpen(false)} />
      <AgentReviewDialog open={reviewOpen} conversationId={activeId} onClose={() => setReviewOpen(false)} onNotify={show} />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Conversation drawer
// ---------------------------------------------------------------------------

function ConversationPane(props: {
  conversations: AiConversation[];
  loading: boolean;
  error: unknown;
  activeId: number | null;
  onSelect: (id: number) => void;
  onNew: () => void;
  onRename: (id: number, title: string) => void;
  onDelete: (id: number) => void;
  onRetry: () => void;
}) {
  const t = (zh: string, en: string) => tr(zh, en);
  const [menu, setMenu] = useState<{ x: number; y: number; id: number } | null>(null);
  const [renameTarget, setRenameTarget] = useState<AiConversation | null>(null);
  const [renameText, setRenameText] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<AiConversation | null>(null);

  return (
    <div className="dc-col" style={{ gap: 8, height: "100%" }}>
      <div className="dc-row">
        <div className="dc-grow dc-title" style={{ fontSize: "1.05em" }}>{t("会话历史", "Conversation history")}</div>
        <button className="dc-icon-btn" aria-label={t("新会话", "New conversation")} title={t("新会话", "New conversation")} onClick={props.onNew}>
          <Plus size={20} />
        </button>
      </div>
      {props.loading ? (
        <Spinner size={22} />
      ) : props.error ? (
        <div className="dc-col dc-center" style={{ padding: 16 }}>
          <ErrorText error={props.error} />
          <button className="dc-btn" onClick={props.onRetry}>{t("重试", "Retry")}</button>
        </div>
      ) : props.conversations.length === 0 ? (
        <div className="dc-muted" style={{ padding: "12px 6px", fontSize: "0.9em" }}>
          {t("还没有保存的会话。", "No saved conversations yet.")}
        </div>
      ) : (
        <div className="dc-col" style={{ gap: 6, overflowY: "auto" }}>
          {props.conversations.map((c) => (
            <div
              key={c.id}
              className="dc-card"
              role="button"
              tabIndex={0}
              onClick={() => props.onSelect(c.id)}
              onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") props.onSelect(c.id); }}
              style={{
                padding: "10px 12px", cursor: "pointer",
                display: "flex", alignItems: "center", gap: 8,
                background: c.id === props.activeId ? "var(--dc-secondary-container)" : undefined,
                color: c.id === props.activeId ? "var(--dc-on-secondary-container)" : undefined,
              }}
            >
              <div className="dc-grow" style={{ minWidth: 0 }}>
                <div style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", fontWeight: 500 }}>
                  {c.title || t("未命名会话", "Untitled conversation")}
                </div>
                <div className="dc-muted" style={{ fontSize: "0.78em" }}>
                  {c.id === props.activeId ? t("当前会话", "Current") : t("点击继续", "Tap to continue")}
                </div>
              </div>
              <button
                className="dc-icon-btn"
                aria-label={t("更多操作", "More actions")}
                style={{ width: 32, height: 32 }}
                onClick={(e) => {
                  e.stopPropagation();
                  const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
                  setMenu({ x: r.left, y: r.bottom + 4, id: c.id });
                }}
              >
                <Pencil size={15} />
              </button>
            </div>
          ))}
        </div>
      )}

      <PopupMenu
        open={menu != null}
        onClose={() => setMenu(null)}
        x={menu?.x ?? 0}
        y={menu?.y ?? 0}
        items={[
          {
            label: t("重命名", "Rename"),
            onClick: () => {
              const c = props.conversations.find((x) => x.id === menu?.id);
              if (c) { setRenameTarget(c); setRenameText(c.title); }
            },
          },
          {
            label: t("删除", "Delete"),
            danger: true,
            onClick: () => {
              const c = props.conversations.find((x) => x.id === menu?.id);
              if (c) setDeleteTarget(c);
            },
          },
        ]}
      />

      <Modal open={renameTarget != null} onClose={() => setRenameTarget(null)} title={t("重命名会话", "Rename conversation")}>
        <input
          className="dc-input"
          autoFocus
          value={renameText}
          maxLength={80}
          onChange={(e) => setRenameText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && renameText.trim() && renameTarget) {
              props.onRename(renameTarget.id, renameText.trim());
              setRenameTarget(null);
            }
          }}
        />
        <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 14 }}>
          <button className="dc-btn" onClick={() => setRenameTarget(null)}>{t("取消", "Cancel")}</button>
          <button
            className="dc-btn dc-btn-filled"
            disabled={!renameText.trim()}
            onClick={() => {
              if (renameTarget) props.onRename(renameTarget.id, renameText.trim());
              setRenameTarget(null);
            }}
          >
            {t("保存", "Save")}
          </button>
        </div>
      </Modal>

      <ConfirmDialog
        open={deleteTarget != null}
        title={t("删除这段会话？", "Delete this conversation?")}
        message={deleteTarget?.title}
        confirmLabel={t("删除", "Delete")}
        danger
        onCancel={() => setDeleteTarget(null)}
        onConfirm={() => {
          if (deleteTarget) props.onDelete(deleteTarget.id);
          setDeleteTarget(null);
        }}
      />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Model picker
// ---------------------------------------------------------------------------

function ModelPickerModal(props: {
  open: boolean;
  configs: AiModelConfig[];
  selectedId?: string;
  onClose: () => void;
  onPick: (id: string) => void;
  onOpenSettings: () => void;
}) {
  if (!props.open) return null;
  return (
    <Modal open onClose={props.onClose} title={tr("当前 Agent 模型", "Current Agent model")} width={520}>
      {props.configs.length === 0 ? (
        <div className="dc-row">
          <span className="dc-muted dc-grow">{tr("请选择可用的文字模型", "Select an available text model")}</span>
          <button className="dc-btn" onClick={props.onOpenSettings}>{tr("设置", "Settings")}</button>
        </div>
      ) : (
        <div className="dc-col" style={{ gap: 8 }}>
          {props.configs.map((c) => (
            <button
              key={c.id}
              className="dc-card"
              style={{
                padding: "10px 12px", textAlign: "left", cursor: "pointer",
                display: "flex", alignItems: "center", gap: 10,
                background: c.id === props.selectedId ? "var(--dc-secondary-container)" : undefined,
                color: c.id === props.selectedId ? "var(--dc-on-secondary-container)" : undefined,
              }}
              onClick={() => props.onPick(c.id)}
            >
              <div className="dc-grow" style={{ minWidth: 0 }}>
                <div style={{ fontWeight: 600, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{c.name}</div>
                <div className="dc-muted" style={{ fontSize: "0.82em", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{c.model}</div>
              </div>
              <span className="dc-chip" style={{ flexShrink: 0 }}>
                {c.supportsToolCalling
                  ? <><Check size={13} />{tr("原生工具调用", "Native tool calling")}</>
                  : tr("不支持 Agent 工具", "Agent tools unsupported")}
              </span>
            </button>
          ))}
        </div>
      )}
    </Modal>
  );
}

// ---------------------------------------------------------------------------
// Message thread
// ---------------------------------------------------------------------------

function ReasoningBlock(props: { reasoning: string }) {
  if (!props.reasoning.trim()) return null;
  return (
    <details className="dc-card" style={{ padding: "6px 10px", marginBottom: 8, background: "var(--dc-surface-container)" }}>
      <summary className="dc-muted" style={{ cursor: "pointer", fontSize: "0.85em", userSelect: "none" }}>
        {tr("折叠思考", "Collapsed reasoning")}
      </summary>
      <div style={{ whiteSpace: "pre-wrap", marginTop: 6, fontSize: "0.88em", color: "var(--dc-on-surface-variant)" }}>
        {props.reasoning}
      </div>
    </details>
  );
}

function MessageBubble(props: {
  msg: UiMessage;
  retry?: () => void;
  onDecideApproval?: (toolCallId: string, approve: boolean) => void;
  decidingApprovalId?: string | null;
}) {
  const { msg } = props;
  if (msg.role === "user") {
    return (
      <div style={{ display: "flex", justifyContent: "flex-end" }}>
        <div style={{
          maxWidth: "92%", padding: "9px 13px", borderRadius: 16,
          background: "var(--dc-primary)", color: "var(--dc-on-primary)",
          overflowWrap: "anywhere",
        }}>
          {msg.content && <div style={{ whiteSpace: "pre-wrap" }}>{msg.content}</div>}
          {(msg.previews?.length ?? 0) > 0 && (
            <div className="dc-row dc-wrap" style={{ gap: 6, marginTop: msg.content ? 6 : 0 }}>
              {(msg.previews ?? []).map((u, i) => (
                <img key={i} src={u} alt="" style={{ width: 64, height: 64, objectFit: "cover", borderRadius: 10, display: "block" }} />
              ))}
            </div>
          )}
          {(msg.attachments?.length ?? 0) > 0 && (
            <div className="dc-col" style={{ gap: 4, marginTop: msg.content || msg.previews ? 6 : 0 }}>
              {(msg.attachments ?? []).map((a) => (
                <AttachmentChip key={a.id} att={a} onColor />
              ))}
            </div>
          )}
        </div>
      </div>
    );
  }
  const hasExecution = (msg.toolEvents?.length ?? 0) > 0 || !!msg.approval;
  return (
    <div style={{ display: "flex", justifyContent: "flex-start", flexDirection: "column", alignItems: "stretch", gap: 8 }}>
      {hasExecution && (
        <AgentExecutionPanel
          msg={msg}
          running={!!msg.streaming && !msg.error}
          onDecideApproval={props.onDecideApproval}
          decidingApprovalId={props.decidingApprovalId}
        />
      )}
      <div style={{ display: "flex", justifyContent: "flex-start" }}>
        <div className="dc-card" style={{ maxWidth: "94%", padding: "10px 14px", overflowWrap: "anywhere" }}>
          <ReasoningBlock reasoning={msg.reasoning ?? ""} />
          {msg.content ? (
            <MarkdownPreview content={msg.content} />
          ) : msg.streaming && !hasExecution ? (
            <span className="dc-muted">{tr("Agent 正在规划下一步…", "Agent is planning the next step…")}</span>
          ) : null}
          {msg.error ? (
            <>
              <ErrorText error={new Error(msg.error)} />
              {props.retry && (
                <button className="dc-btn" onClick={props.retry}>{tr("重试", "Retry")}</button>
              )}
            </>
          ) : null}
          {msg.stopped ? (
            <div className="dc-muted" style={{ fontSize: "0.82em", marginTop: 4 }}>{tr("已中止", "Stopped")}</div>
          ) : null}
          {msg.usage && !msg.streaming ? <UsageLine usage={msg.usage} /> : null}
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Agent execution panel / approvals
// ---------------------------------------------------------------------------

export function agentStatusGlyph(status: ToolStatus): string {
  switch (status) {
    case "PREPARING": return "…";
    case "RUNNING": return "↻";
    case "WAITING_APPROVAL": return "?";
    case "APPROVED":
    case "SUCCEEDED": return "✓";
    case "REJECTED": return "×";
    case "FAILED": return "!";
    case "CANCELED": return "■";
    default: return "·";
  }
}

export function agentStatusLabel(status: ToolStatus): string {
  switch (status) {
    case "PREPARING": return tr("准备中", "Preparing");
    case "RUNNING": return tr("执行中", "Running");
    case "WAITING_APPROVAL": return tr("待批准", "Needs approval");
    case "APPROVED": return tr("已批准", "Approved");
    case "REJECTED": return tr("已拒绝", "Rejected");
    case "SUCCEEDED": return tr("成功", "Done");
    case "FAILED": return tr("失败", "Failed");
    case "CANCELED": return tr("已取消", "Canceled");
    default: return status;
  }
}

function ToolEventRow(props: { event: ToolEventUi; defaultOpen?: boolean }) {
  const [open, setOpen] = useState(!!props.defaultOpen);
  const ev = props.event;
  const title =
    ev.summary ||
    (ev.status === "RUNNING"
      ? tr(`正在执行 ${ev.toolName}`, `Running ${ev.toolName}`)
      : ev.toolName);
  return (
    <div className="dc-card" style={{ padding: "8px 11px", background: "color-mix(in srgb, var(--dc-surface) 72%, transparent)" }}>
      <button
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        style={{ display: "flex", width: "100%", gap: 8, alignItems: "center", background: "none", border: "none", cursor: "pointer", textAlign: "left", color: "inherit", padding: 0 }}
      >
        <span style={{ width: 20, flexShrink: 0, fontFamily: "monospace" }}>{agentStatusGlyph(ev.status)}</span>
        <span className="dc-grow" style={{ minWidth: 0 }}>
          <span style={{ display: "block", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", fontWeight: 500 }}>{title}</span>
          {ev.target && (
            <span className="dc-muted" style={{ display: "block", fontSize: "0.78em", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{ev.target}</span>
          )}
        </span>
        <span className="dc-chip" style={{ flexShrink: 0, fontSize: "0.75em" }}>{agentStatusLabel(ev.status)}</span>
        {open ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
      </button>
      {open && (
        <div style={{ borderTop: "var(--dc-border-width) solid var(--dc-outline-variant)", marginTop: 7, paddingTop: 7, display: "grid", gap: 4 }}>
          <div className="dc-muted" style={{ fontSize: "0.82em" }}>{tr("工具", "Tool")}: {ev.toolName || "—"}</div>
          {ev.argumentsSummary && (
            <div style={{ fontSize: "0.82em", fontFamily: "monospace", whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{ev.argumentsSummary}</div>
          )}
          {ev.resultSummary && (
            <div style={{ fontSize: "0.85em", whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{ev.resultSummary}</div>
          )}
          {ev.errorCode && <ErrorText error={new Error(ev.errorCode)} />}
        </div>
      )}
    </div>
  );
}

/** Inline mutation-approval card (需要批准 mode). */
function ApprovalCard(props: {
  approval: ApprovalUi;
  decided?: boolean;
  busy?: boolean;
  onDecide: (approve: boolean) => void;
}) {
  const a = props.approval;
  return (
    <div className="dc-card" style={{ padding: 12, borderColor: "var(--dc-primary)" }}>
      <div className="dc-row" style={{ gap: 6, marginBottom: 8 }}>
        <Lock size={16} style={{ color: "var(--dc-primary)", flexShrink: 0 }} />
        <strong>{tr("Agent 请求修改", "Agent requests a change")}</strong>
      </div>
      <div style={{ display: "grid", gap: 5, fontSize: "0.9em" }}>
        <div><span className="dc-muted">{tr("工具", "Tool")}：</span>{a.toolName || "—"}</div>
        <div><span className="dc-muted">{tr("目标", "Target")}：</span>{a.target || "—"}</div>
        {a.summary && <div><span className="dc-muted">{tr("计划", "Plan")}：</span>{a.summary}</div>}
        {(a.before || a.after) && (
          <details>
            <summary className="dc-muted" style={{ cursor: "pointer", userSelect: "none" }}>
              {tr("修改前 / 修改后", "Before / after")}
            </summary>
            {a.before && (
              <pre style={{ margin: "6px 0 0", background: "var(--dc-surface-container-high)", padding: 10, borderRadius: 8, overflowX: "auto", fontSize: "0.8em", whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
                {tr("修改前", "Before") + "\n" + a.before}
              </pre>
            )}
            {a.after && (
              <pre style={{ margin: "6px 0 0", background: "var(--dc-surface-container-high)", padding: 10, borderRadius: 8, overflowX: "auto", fontSize: "0.8em", whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
                {tr("修改后", "After") + "\n" + a.after}
              </pre>
            )}
          </details>
        )}
      </div>
      {props.decided !== undefined ? (
        <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 10 }}>
          <span className="dc-chip">{props.decided ? tr("已批准", "Approved") : tr("已拒绝", "Rejected")}</span>
        </div>
      ) : (
        <div className="dc-row" style={{ justifyContent: "flex-end", gap: 8, marginTop: 10 }}>
          <button className="dc-btn" disabled={props.busy} onClick={() => props.onDecide(false)}>{tr("拒绝", "Reject")}</button>
          <button className="dc-btn dc-btn-filled" disabled={props.busy} onClick={() => props.onDecide(true)}>
            {props.busy ? tr("处理中…", "Working…") : tr("批准", "Approve")}
          </button>
        </div>
      )}
    </div>
  );
}

function AgentExecutionPanel(props: {
  msg: UiMessage;
  running: boolean;
  onDecideApproval?: (toolCallId: string, approve: boolean) => void;
  decidingApprovalId?: string | null;
}) {
  const [open, setOpen] = useState(true);
  const { msg } = props;
  return (
    <div className="dc-card" style={{ padding: 12, background: "var(--dc-secondary-container)", color: "var(--dc-on-secondary-container)" }}>
      <button
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        style={{ display: "flex", width: "100%", gap: 8, alignItems: "center", background: "none", border: "none", cursor: "pointer", color: "inherit", padding: 0 }}
      >
        {props.running ? <Spinner size={18} /> : <Check size={18} />}
        <strong className="dc-grow" style={{ textAlign: "left" }}>
          {props.running ? tr("Agent 执行中", "Agent running") : tr("执行记录", "Execution log")}
        </strong>
        {open ? <ChevronDown size={18} /> : <ChevronRight size={18} />}
      </button>
      {open && (
        <div className="dc-col" style={{ gap: 6, marginTop: 8 }}>
          {(msg.toolEvents ?? []).map((ev, i) => (
            <ToolEventRow key={ev.toolCallId || i} event={ev} />
          ))}
          {msg.approval && props.onDecideApproval && (
            <ApprovalCard
              approval={msg.approval}
              decided={undefined}
              busy={props.decidingApprovalId === msg.approval.toolCallId}
              onDecide={(approve) => props.onDecideApproval!(msg.approval!.toolCallId, approve)}
            />
          )}
        </div>
      )}
    </div>
  );
}

function UsageLine(props: { usage: RunUsageUi }) {
  const u = props.usage;
  const fmt = (v?: number | null) => (typeof v === "number" && Number.isFinite(v) ? v.toLocaleString() : "—");
  const rate = typeof u.cachedInputTokens === "number" && typeof u.cacheRateInputTokens === "number"
    && Number.isFinite(u.cachedInputTokens) && Number.isFinite(u.cacheRateInputTokens) && u.cacheRateInputTokens > 0
    ? `${((u.cachedInputTokens / u.cacheRateInputTokens) * 100).toFixed(1)}%`
    : "—";
  const reported = (u.reportedCallCount ?? 0) > 0;
  return (
    <div className="dc-muted" style={{ fontSize: "0.78em", marginTop: 8 }}>
      {reported
        ? tr(
          `${fmt(u.totalTokens)} Token · 缓存率 ${rate}`,
          `${fmt(u.totalTokens)} tokens · cache rate ${rate}`,
        )
        : tr(
          `${fmt(u.modelCallCount)} 次模型调用 · Provider 未报告 Token`,
          `${fmt(u.modelCallCount)} model calls · tokens not reported by provider`,
        )}
    </div>
  );
}

export function AttachmentChip(props: { att: AiAttachmentDto; onColor?: boolean }) {
  const isImage = props.att.mimeType.startsWith("image/") || props.att.kind === "IMAGE";
  return (
    <span className="dc-chip" style={{ maxWidth: "100%", background: props.onColor ? "rgba(255,255,255,0.18)" : undefined }}>
      {isImage ? <ImageIcon size={13} /> : <FileText size={13} />}
      <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{props.att.displayName}</span>
      <span style={{ opacity: 0.75, flexShrink: 0 }}>{formatBytes(props.att.sizeBytes)}</span>
    </span>
  );
}

export function formatBytes(n: number): string {
  if (!Number.isFinite(n) || n < 0) return "—";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

function ThreadView(props: {
  messages: UiMessage[];
  loading: boolean;
  error: unknown;
  configured: boolean;
  fontSizeSp: number;
  onRetry: () => void;
  onRetryLast?: () => void;
  onDecideApproval?: (toolCallId: string, approve: boolean) => void;
  decidingApprovalId?: string | null;
}) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const stickRef = useRef(true);
  const onScroll = () => {
    const el = scrollRef.current;
    if (el) stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 120;
  };
  useEffect(() => {
    const el = scrollRef.current;
    if (el && stickRef.current) el.scrollTop = el.scrollHeight;
  });
  const lastIdx = props.messages.length - 1;
  return (
    <div
      ref={scrollRef}
      className="dc-col"
      onScroll={onScroll}
      style={{
        flex: 1, minHeight: 320, height: "calc(100vh - 340px)", overflowY: "auto",
        gap: 12, padding: "4px 2px", fontSize: `${props.fontSizeSp}px`,
      }}
    >
      {props.loading ? (
        <Spinner />
      ) : props.error ? (
        <div className="dc-col dc-center" style={{ padding: 24 }}>
          <ErrorText error={props.error} />
          <button className="dc-btn" onClick={props.onRetry}>{tr("重试", "Retry")}</button>
        </div>
      ) : props.messages.length === 0 ? (
        <EmptyState
          icon={<Send size={34} style={{ opacity: 0.5 }} />}
          title={props.configured ? tr("交给 Agent 一项任务", "Give the Agent a task") : tr("先配置 Agent 模型", "Configure an Agent model first")}
          hint={props.configured ? tr("描述任务，Agent 会按需调用工具", "Describe a task; the Agent will use tools as needed") : undefined}
        />
      ) : (
        props.messages.map((m, i) => (
          <MessageBubble
            key={m.key}
            msg={m}
            retry={
              i === lastIdx && m.role === "assistant" && !!m.error && !m.streaming && props.onRetryLast
                ? props.onRetryLast
                : undefined
            }
            onDecideApproval={props.onDecideApproval}
            decidingApprovalId={props.decidingApprovalId}
          />
        ))
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Composer
// ---------------------------------------------------------------------------

function Composer(props: {
  draft: string;
  onDraftChange: (v: string) => void;
  onSend: () => void;
  onStop: () => void;
  streaming: boolean;
  canSend: boolean;
  configured: boolean;
  attachments: PendingAttachment[];
  uploading: boolean;
  onPickFiles: (files: FileList | null) => void;
  onRemoveAttachment: (id: string | number) => void;
  fileInputRef: React.RefObject<HTMLInputElement>;
  agentMode?: boolean;
  onOpenAgentMenu?: () => void;
}) {
  const taRef = useRef<HTMLTextAreaElement>(null);
  // Auto-grow: 1–6 lines.
  useEffect(() => {
    const el = taRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 168)}px`;
  }, [props.draft]);
  return (
    <div className="dc-col" style={{ gap: 6, fontSize: "inherit" }}>
      {(props.attachments.length > 0 || props.uploading) && (
        <div className="dc-row dc-wrap" style={{ gap: 8 }}>
          {props.attachments.map((a) =>
            a.previewUrl ? (
              <div key={a.id} style={{ position: "relative", flexShrink: 0 }}>
                <img
                  src={a.previewUrl}
                  alt={a.displayName}
                  title={`${a.displayName} · ${formatBytes(a.sizeBytes)}`}
                  style={{ width: 56, height: 56, objectFit: "cover", borderRadius: 10, border: "var(--dc-border-width) solid var(--dc-outline-variant)", display: "block" }}
                />
                <button
                  className="dc-icon-btn"
                  aria-label={tr("移除附件", "Remove attachment")}
                  title={tr("移除附件", "Remove attachment")}
                  disabled={props.streaming}
                  onClick={() => props.onRemoveAttachment(a.id)}
                  style={{ position: "absolute", top: -8, right: -8, width: 24, height: 24, background: "var(--dc-surface-container-high)", boxShadow: "0 1px 4px rgba(0,0,0,0.3)" }}
                >
                  <X size={13} />
                </button>
              </div>
            ) : (
              <span key={a.id} className="dc-chip" style={{ gap: 6 }}>
                <FileText size={13} />
                <span style={{ maxWidth: 180, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{a.displayName}</span>
                <span style={{ opacity: 0.75 }}>{formatBytes(a.sizeBytes)}</span>
                <button
                  className="dc-icon-btn"
                  aria-label={tr("移除附件", "Remove attachment")}
                  disabled={props.streaming}
                  onClick={() => props.onRemoveAttachment(a.id)}
                  style={{ width: 20, height: 20, marginLeft: 2 }}
                >
                  <X size={12} />
                </button>
              </span>
            )
          )}
          {props.uploading && <Spinner size={18} />}
        </div>
      )}
      <div className="dc-row" style={{ alignItems: "flex-end", gap: 6 }}>
        {props.agentMode && props.onOpenAgentMenu && (
          <button
            className="dc-icon-btn"
            aria-label={tr("Agent 工具与上下文", "Agent tools and context")}
            title={tr("Agent 工具与上下文", "Agent tools and context")}
            disabled={props.streaming}
            onClick={props.onOpenAgentMenu}
            style={{ flexShrink: 0, opacity: props.streaming ? 0.5 : 1 }}
          >
            <LayoutGrid size={20} />
          </button>
        )}
        <label className="dc-icon-btn" title={tr("插入图片 / 文档", "Insert image / document")} aria-label={tr("插入图片 / 文档", "Insert image / document")} style={{ cursor: props.streaming ? "default" : "pointer", opacity: props.streaming ? 0.5 : 1 }}>
          <Paperclip size={20} />
          <input
            ref={props.fileInputRef}
            type="file"
            multiple
            accept="image/*,.pdf,.docx,.txt,.md,.markdown,.html,.json,.csv,.xml"
            style={{ display: "none" }}
            onChange={(e) => { if (!props.streaming) props.onPickFiles(e.target.files); }}
          />
        </label>
        <textarea
          ref={taRef}
          className="dc-input dc-grow"
          rows={1}
          value={props.draft}
          maxLength={100000}
          placeholder={
            props.configured
              ? tr("描述任务，Agent 会按需调用工具", "Describe a task; the Agent will use tools as needed")
              : tr("请先配置支持工具调用的模型", "Configure a tool-capable model first")
          }
          onChange={(e) => props.onDraftChange(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              if (!props.streaming && props.canSend) props.onSend();
            }
          }}
          style={{ resize: "none", minHeight: 40, lineHeight: 1.45 }}
        />
        {props.streaming ? (
          <button
            className="dc-icon-btn"
            aria-label={tr("中止", "Stop")}
            title={tr("中止", "Stop")}
            onClick={props.onStop}
            style={{ color: "var(--dc-error)", width: 44, height: 44, flexShrink: 0 }}
          >
            <Square size={20} />
          </button>
        ) : (
          <button
            className="dc-icon-btn"
            aria-label={tr("发送", "Send")}
            title={tr("发送", "Send")}
            disabled={!props.canSend}
            onClick={props.onSend}
            style={{ color: props.canSend ? "var(--dc-primary)" : "var(--dc-on-surface-variant)", width: 44, height: 44, flexShrink: 0 }}
          >
            <Send size={20} />
          </button>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// 四方块 Agent sheet + context / permission / tools dialogs
// ---------------------------------------------------------------------------

function AgentSheet(props: {
  open: boolean;
  agentMode: boolean;
  onClose: () => void;
  onPickFiles: (files: FileList | null) => void;
  fileInputRef: React.RefObject<HTMLInputElement>;
  uploading: boolean;
  onManageContext: () => void;
  onPermissionMode: () => void;
  onToolsInfo: () => void;
}) {
  if (!props.open) return null;
  const items: { icon: React.ReactNode; label: string; hint: string; onClick: () => void }[] = [
    {
      icon: <Paperclip size={18} />,
      label: tr("插入图片 / 文档", "Insert image / document"),
      hint: tr("最多 5 个附件，单个上限 8 MiB", "Up to 5 attachments, 8 MiB each"),
      onClick: () => props.fileInputRef.current?.click(),
    },
    {
      icon: <Database size={18} />,
      label: tr("管理上下文", "Manage context"),
      hint: tr("授权数据源后，Agent 才能检索和读取", "Grant a source before the Agent can search or read it"),
      onClick: props.onManageContext,
    },
    {
      icon: <Lock size={18} />,
      label: tr("AI 权限模式", "AI permission mode"),
      hint: tr("需要批准 / 全自动", "Require approval / Full auto"),
      onClick: props.onPermissionMode,
    },
    {
      icon: <ShieldCheck size={18} />,
      label: tr("可用工具", "Available tools"),
      hint: tr("查看工具范围与安全边界", "See tool scope and safety boundaries"),
      onClick: props.onToolsInfo,
    },
  ];
  return (
    <div className="dc-dialog-overlay" onClick={props.onClose}>
      <div className="dc-dialog" role="dialog" aria-modal="true" style={{ width: "min(440px, 94vw)" }} onClick={(e) => e.stopPropagation()}>
        <div className="dc-row" style={{ marginBottom: 10 }}>
          <div className="dc-title dc-grow">{tr("Agent 工具与上下文", "Agent tools and context")}</div>
          {!props.agentMode && (
            <span className="dc-chip">{tr("当前为普通聊天，打开顶栏 Agent 后生效", "Plain chat; enable Agent in the top bar to apply")}</span>
          )}
          {props.uploading && <Spinner size={18} />}
        </div>
        <div className="dc-col" style={{ gap: 8 }}>
          {items.map((it) => (
            <button
              key={it.label}
              className="dc-card"
              style={{ display: "flex", alignItems: "center", gap: 12, padding: "12px 14px", textAlign: "left", cursor: "pointer" }}
              onClick={() => { props.onClose(); it.onClick(); }}
            >
              {it.icon}
              <span className="dc-grow" style={{ minWidth: 0 }}>
                <span style={{ display: "block", fontWeight: 500 }}>{it.label}</span>
                <span className="dc-muted" style={{ display: "block", fontSize: "0.82em" }}>{it.hint}</span>
              </span>
              <ChevronRight size={16} />
            </button>
          ))}
        </div>
        <input
          ref={props.fileInputRef}
          type="file"
          multiple
          accept="image/*,.pdf,.docx,.txt,.md,.markdown,.html,.json,.csv,.xml"
          style={{ display: "none" }}
          onChange={(e) => { props.onPickFiles(e.target.files); if (props.fileInputRef.current) props.fileInputRef.current.value = ""; }}
        />
      </div>
    </div>
  );
}

function AgentContextDialog(props: {
  open: boolean;
  enabledSources: string[];
  onToggle: (value: string, checked: boolean) => void;
  onClose: () => void;
}) {
  if (!props.open) return null;
  const sources = agentDataSourceDefs();
  return (
    <Modal open onClose={props.onClose} title={tr("管理上下文", "Manage context")} width={560}>
      <div className="dc-muted" style={{ fontSize: "0.88em", marginBottom: 10 }}>
        {tr(
          "勾选表示授予检索和按需读取权限，不会把全部正文塞入 Prompt。取消授权后，Agent 工具立即无法访问该数据源。",
          "A check grants search and on-demand read access; it does not inject all content into the prompt. Removing access blocks the source immediately.",
        )}
      </div>
      <div className="dc-col" style={{ gap: 2, maxHeight: "52vh", overflowY: "auto" }}>
        {sources.map((s) => {
          const checked = props.enabledSources.includes(s.value);
          return (
            <label key={s.value} className="dc-row" style={{ gap: 10, padding: "7px 4px", cursor: "pointer", alignItems: "center" }}>
              <input type="checkbox" checked={checked} onChange={(e) => props.onToggle(s.value, e.target.checked)} />
              <span className="dc-grow" style={{ minWidth: 0 }}>
                <span style={{ display: "block", fontWeight: 500 }}>{s.label}</span>
                <span className="dc-muted" style={{ display: "block", fontSize: "0.8em" }}>{s.description}</span>
              </span>
            </label>
          );
        })}
      </div>
      <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 14 }}>
        <button className="dc-btn dc-btn-filled" onClick={props.onClose}>{tr("完成", "Done")}</button>
      </div>
    </Modal>
  );
}

function AgentPermissionDialog(props: {
  open: boolean;
  mode: "REQUIRE_APPROVAL" | "FULL_AUTO";
  onSelect: (mode: "REQUIRE_APPROVAL" | "FULL_AUTO") => void;
  onClose: () => void;
}) {
  if (!props.open) return null;
  const options: { value: "REQUIRE_APPROVAL" | "FULL_AUTO"; title: string; description: string }[] = [
    {
      value: "REQUIRE_APPROVAL",
      title: tr("需要批准", "Require approval"),
      description: tr("读取无需确认；每一个创建、编辑、删除或设置修改都先显示预览。", "Reads run directly; every create, edit, delete, or setting change shows a preview first."),
    },
    {
      value: "FULL_AUTO",
      title: tr("全自动", "Full auto"),
      description: tr("修改直接执行，但仍逐项写入 Review，并可在安全时 Undo。", "Mutations run directly but remain individually recorded in Review with Undo where safe."),
    },
  ];
  return (
    <Modal open onClose={props.onClose} title={tr("AI 权限模式", "AI permission mode")} width={520}>
      <div className="dc-col" style={{ gap: 10 }}>
        {options.map((o) => (
          <button
            key={o.value}
            className="dc-card"
            style={{
              display: "flex", gap: 10, alignItems: "flex-start", padding: 14, cursor: "pointer", textAlign: "left",
              background: props.mode === o.value ? "var(--dc-secondary-container)" : undefined,
              color: props.mode === o.value ? "var(--dc-on-secondary-container)" : undefined,
            }}
            onClick={() => props.onSelect(o.value)}
          >
            <input type="radio" checked={props.mode === o.value} readOnly style={{ marginTop: 3 }} />
            <span>
              <span style={{ display: "block", fontWeight: 600 }}>{o.title}</span>
              <span style={{ display: "block", fontSize: "0.88em", marginTop: 2 }}>{o.description}</span>
            </span>
          </button>
        ))}
      </div>
      <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 14 }}>
        <button className="dc-btn" onClick={props.onClose}>{tr("关闭", "Close")}</button>
      </div>
    </Modal>
  );
}

/** 可用工具 info dialog — wording copied from README_for_ai.md §11 安全边界. */
function AgentToolsInfoDialog(props: { open: boolean; onClose: () => void }) {
  if (!props.open) return null;
  return (
    <Modal open onClose={props.onClose} title={tr("可用工具与安全边界", "Available tools and safety boundaries")} width={640}>
      <div className="dc-col" style={{ gap: 10, fontSize: "0.92em", maxHeight: "60vh", overflowY: "auto" }}>
        <p style={{ margin: 0 }}>
          <strong>{tr("DeskCubby 数据", "DeskCubby data")}</strong>
          {tr(
            "：列出/搜索/读取条目，以及创建、编辑、删除允许修改的日记、小巧思、日期、日常事件、笔记和诗词。",
            ": list/search/read entries, and create/edit/delete the diary, thoughts, dates, daily events, notes, and poems you allow.",
          )}
        </p>
        <p style={{ margin: 0 }}>
          <strong>{tr("文件", "Files")}</strong>
          {tr(
            "：只在已授权的日记或笔记根中列出、搜索、读取、创建和修改；不会把 content:// URI 转成路径，也不能访问任意文件系统。",
            ": only list, search, read, create and modify inside the authorized diary or notes roots; content:// URIs are never converted to paths and arbitrary file access is impossible.",
          )}
        </p>
        <p style={{ margin: 0 }}>
          <strong>{tr("网络", "Network")}</strong>
          {tr(
            "：web_search 与受限网页读取；只访问公开 HTTPS，限制重定向、私网地址、响应大小和运行时间。",
            ": web_search and restricted page reads; public HTTPS only, with redirect, private-address, response-size, and time limits.",
          )}
        </p>
        <p style={{ margin: 0 }}>
          <strong>{tr("App", "App")}</strong>
          {tr(
            "：读取非敏感 DeskCubby 设置、修改明确允许的设置、查询应用状态。API Key、密码、keystore、云凭据、存储授权、Agent 权限等安全字段不会向工具公开，也不能由 Agent 修改。",
            ": read non-sensitive settings, change explicitly allowed ones, and query app state. Secrets such as API keys, passwords, keystores, cloud credentials, storage grants, and Agent permissions are never exposed to tools.",
          )}
        </p>
        <p style={{ margin: 0 }}>
          {tr(
            "日记、笔记、网页、附件和工具结果全部按外部不可信数据处理。其中即使写着“忽略系统提示”“开启全自动”或“调用某工具”，也不能改变系统提示、数据源授权、审批模式或工具规则。",
            "Diary, notes, web pages, attachments, and tool results are all treated as untrusted external data. Text claiming to \"ignore system prompts\", \"enable full auto\", or \"call a tool\" can never change system prompts, source authorization, approval mode, or tool rules.",
          )}
        </p>
        <p style={{ margin: 0 }}>
          {tr(
            "Agent 必须真的调用工具后才能声称读过文件或搜索过网络。",
            "The Agent may only claim it read a file or searched the web after actually calling the corresponding tool.",
          )}
        </p>
      </div>
      <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 14 }}>
        <button className="dc-btn dc-btn-filled" onClick={props.onClose}>{tr("关闭", "Close")}</button>
      </div>
    </Modal>
  );
}

// ---------------------------------------------------------------------------
// Token statistics dialog (GET /api/agent/token-stats)
// ---------------------------------------------------------------------------

interface TokenStatsDto {
  totalRuns?: number;
  runCount?: number;
  modelCallCount?: number;
  usageReportedCallCount?: number;
  reportedCallCount?: number;
  inputTokens?: number | null;
  outputTokens?: number | null;
  totalTokens?: number | null;
  cachedInputTokens?: number | null;
  cacheRateInputTokens?: number | null;
  cacheRate?: number | null;
  reasoningTokens?: number | null;
}

function TokenStatsDialog(props: { open: boolean; onClose: () => void }) {
  const [stats, setStats] = useState<TokenStatsDto | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    if (!props.open) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    apiGet<TokenStatsDto>("/api/agent/token-stats")
      .then((d) => { if (!cancelled) setStats(d ?? {}); })
      .catch((e) => { if (!cancelled) setError(e); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [props.open]);

  if (!props.open) return null;
  const num = (v?: number | null) =>
    typeof v === "number" && Number.isFinite(v) ? v.toLocaleString() : "—";
  const rawRate = typeof stats?.cacheRate === "number" && Number.isFinite(stats.cacheRate)
    ? stats.cacheRate
    : typeof stats?.cachedInputTokens === "number" && typeof stats?.cacheRateInputTokens === "number"
      && Number.isFinite(stats.cachedInputTokens) && Number.isFinite(stats.cacheRateInputTokens)
      && stats.cacheRateInputTokens > 0
      ? stats.cachedInputTokens / stats.cacheRateInputTokens
      : null;
  const rate = rawRate == null ? "—" : `${(rawRate * 100).toFixed(1)}%`;
  const rows: [string, string][] = [
    [tr("Agent 运行数", "Agent runs"), num(stats?.totalRuns ?? stats?.runCount)],
    [tr("模型调用数", "Model calls"), num(stats?.modelCallCount)],
    [tr("已报告调用量", "Reported calls"), num(stats?.usageReportedCallCount ?? stats?.reportedCallCount)],
    [tr("输入 Token", "Input tokens"), num(stats?.inputTokens)],
    [tr("输出 Token", "Output tokens"), num(stats?.outputTokens)],
    [tr("总 Token", "Total tokens"), num(stats?.totalTokens)],
    [tr("缓存输入 Token", "Cached input tokens"), num(stats?.cachedInputTokens)],
    [tr("推理 Token", "Reasoning tokens"), num(stats?.reasoningTokens)],
    [tr("缓存率", "Cache rate"), rate],
  ];
  return (
    <Modal open onClose={props.onClose} title={tr("Token 统计", "Token statistics")} width={480}>
      {loading ? (
        <Spinner />
      ) : error ? (
        <div className="dc-col dc-center" style={{ padding: 16 }}>
          <ErrorText error={error} />
        </div>
      ) : (
        <div className="dc-col" style={{ gap: 6 }}>
          {rows.map(([label, value]) => (
            <div key={label} className="dc-row" style={{ justifyContent: "space-between", padding: "4px 2px" }}>
              <span className="dc-muted">{label}</span>
              <strong>{value}</strong>
            </div>
          ))}
          <hr className="dc-divider" />
          <div className="dc-muted" style={{ fontSize: "0.82em" }}>
            {tr(
              "只统计 Provider 报告的 usage；服务未返回的字段显示“—”，不会按文本长度猜测。",
              "Only provider-reported usage is counted; missing fields show “—” instead of being estimated.",
            )}
          </div>
        </div>
      )}
    </Modal>
  );
}

// ---------------------------------------------------------------------------
// Agent Review dialog (runs → tool events + mutations with Undo)
// ---------------------------------------------------------------------------

interface AgentRunDto extends Record<string, unknown> {
  runId: string;
  conversationId?: number | null;
  conversationTitle?: string;
  userRequestSummary?: string;
  status?: string;
  permissionMode?: string;
  modelCallCount?: number;
  usageReportedCallCount?: number;
  inputTokens?: number | null;
  outputTokens?: number | null;
  totalTokens?: number | null;
  cachedInputTokens?: number | null;
  cacheRateInputTokens?: number | null;
  reasoningTokens?: number | null;
  startedAt?: number;
  completedAt?: number | null;
  toolEvents?: ToolEventDto[];
}

interface ToolEventDto {
  id?: number;
  toolCallId?: string;
  toolName: string;
  classification?: string;
  status: string;
  target: string;
  summary: string;
  argumentsSummary?: string;
  resultSummary?: string;
  errorCode?: string | null;
  startedAt?: number;
}

interface MutationDto {
  id: number;
  toolName: string;
  target: string;
  operation: string;
  summary: string;
  beforeContent: string;
  afterContent: string;
  status: string;
  createdAt?: number;
  undoneAt?: number | null;
}

function asArray2<T>(d: T[] | { runs?: T[]; mutations?: T[]; toolEvents?: T[] } | null | undefined): T[] {
  if (Array.isArray(d)) return d;
  if (!d) return [];
  for (const key of ["runs", "mutations", "toolEvents", "items"] as const) {
    const v = (d as Record<string, unknown>)[key];
    if (Array.isArray(v)) return v as T[];
  }
  return [];
}

function runStatusLabel(status?: string): string {
  switch ((status ?? "").toUpperCase()) {
    case "RUNNING": return tr("执行中", "Running");
    case "COMPLETED":
    case "SUCCEEDED": return tr("成功", "Done");
    case "FAILED": return tr("失败", "Failed");
    case "CANCELED":
    case "CANCELLED": return tr("已取消", "Canceled");
    default: return status || "—";
  }
}

function formatTime(ms?: number | null): string {
  if (typeof ms !== "number" || !Number.isFinite(ms)) return "—";
  try {
    return new Date(ms).toLocaleString();
  } catch {
    return "—";
  }
}

export function AgentReviewDialog(props: {
  open: boolean;
  conversationId: number | null;
  onClose: () => void;
  onNotify: (message: string) => void;
}) {
  const t = (zh: string, en: string) => tr(zh, en);
  const [runs, setRuns] = useState<AgentRunDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [detailRun, setDetailRun] = useState<AgentRunDto | null>(null);
  const [mutations, setMutations] = useState<MutationDto[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);
  const [undoConflict, setUndoConflict] = useState<string | null>(null);
  const [expandedMutation, setExpandedMutation] = useState<number | null>(null);

  const loadRuns = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const q = props.conversationId != null ? `?conversationId=${props.conversationId}` : "";
      const d = await apiGet<AgentRunDto[] | { runs?: AgentRunDto[] }>(`/api/agent/runs${q}`);
      setRuns(asArray2<AgentRunDto>(d));
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  }, [props.conversationId]);

  useEffect(() => {
    if (!props.open) {
      setSelectedRunId(null);
      setDetailRun(null);
      setMutations([]);
      setUndoConflict(null);
      setExpandedMutation(null);
      return;
    }
    void loadRuns();
  }, [props.open, loadRuns]);

  const openRun = async (runId: string) => {
    setSelectedRunId(runId);
    setDetailLoading(true);
    setError(null);
    try {
      const [runD, mutD] = await Promise.all([
        apiGet<AgentRunDetailDto>(`/api/agent/runs/${encodeURIComponent(runId)}`),
        apiGet<MutationDto[] | { mutations?: MutationDto[] }>(
          `/api/agent/mutations?runId=${encodeURIComponent(runId)}`
        ).catch(() => [] as MutationDto[]),
      ]);
      const toolEvents = Array.isArray(runD?.toolEvents)
        ? runD.toolEvents as unknown as ToolEventDto[]
        : [];
      setDetailRun(runD?.run ? { ...runD.run, toolEvents } : null);
      setMutations(asArray2<MutationDto>(mutD));
    } catch (e) {
      setError(e);
      setDetailRun(null);
      setMutations([]);
    } finally {
      setDetailLoading(false);
    }
  };

  const undoMutation = async (m: MutationDto) => {
    setUndoConflict(null);
    try {
      await apiSend(`/api/agent/mutations/${m.id}/undo`, "POST");
      props.onNotify(t("已撤回", "Undone"));
      if (selectedRunId) await openRun(selectedRunId);
      else await loadRuns();
    } catch (e) {
      if (e instanceof Error) {
        // 409: 目标内容已被再次修改，无法安全撤回
        setUndoConflict(
          e.message.includes("409") || (e as { status?: number }).status === 409
            ? t("内容已变化，无法撤回", "Content has changed; cannot undo")
            : e.message
        );
      } else {
        setUndoConflict(String(e));
      }
    }
  };

  if (!props.open) return null;

  const backToList = () => {
    setSelectedRunId(null);
    setDetailRun(null);
    setMutations([]);
    setUndoConflict(null);
    setExpandedMutation(null);
  };

  return (
    <Modal open onClose={backToList} title={t("Agent Review", "Agent Review")} width={760}>
      {selectedRunId == null ? (
        <>
          {loading ? (
            <Spinner />
          ) : error ? (
            <div className="dc-col dc-center" style={{ padding: 20 }}>
              <ErrorText error={error} />
              <button className="dc-btn" onClick={() => void loadRuns()}>{t("重试", "Retry")}</button>
            </div>
          ) : runs.length === 0 ? (
            <EmptyState
              icon={<ListChecks size={32} style={{ opacity: 0.5 }} />}
              title={t("还没有 Agent 运行记录", "No Agent runs yet")}
              hint={t("运行一次任务后，可在这里查看真实修改并撤回。", "Run a task; its real changes can be reviewed and undone here.")}
            />
          ) : (
            <div className="dc-col" style={{ gap: 8, maxHeight: "60vh", overflowY: "auto" }}>
              {runs.map((r) => (
                <button
                  key={r.runId}
                  className="dc-card"
                  style={{ padding: "10px 12px", textAlign: "left", cursor: "pointer" }}
                  onClick={() => void openRun(r.runId)}
                >
                  <div className="dc-row" style={{ gap: 8 }}>
                    <span className="dc-grow" style={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", fontWeight: 500 }}>
                      {r.userRequestSummary || r.conversationTitle || r.runId}
                    </span>
                    <span className="dc-chip" style={{ flexShrink: 0 }}>{runStatusLabel(r.status)}</span>
                  </div>
                  <div className="dc-row dc-muted" style={{ gap: 10, fontSize: "0.78em", marginTop: 4, flexWrap: "wrap" }}>
                    <span>{formatTime(r.startedAt)}</span>
                    <span>{r.conversationTitle}</span>
                    <span>
                      {t("模型调用", "Model calls")} ×{typeof r.modelCallCount === "number" ? r.modelCallCount : "—"}
                    </span>
                    {typeof r.totalTokens === "number" && <span>{`${r.totalTokens.toLocaleString()} Token`}</span>}
                  </div>
                </button>
              ))}
            </div>
          )}
        </>
      ) : detailLoading ? (
        <Spinner />
      ) : (
        <div className="dc-col" style={{ gap: 12, maxHeight: "66vh", overflowY: "auto" }}>
          <div className="dc-row">
            <button className="dc-icon-btn" aria-label={t("返回列表", "Back to list")} onClick={backToList}>
              <ChevronRight size={18} style={{ transform: "rotate(180deg)" }} />
            </button>
            <div className="dc-grow dc-muted" style={{ fontSize: "0.85em", minWidth: 0 }}>
              {detailRun?.userRequestSummary || detailRun?.conversationTitle || selectedRunId}
            </div>
            <span className="dc-chip">{runStatusLabel(detailRun?.status)}</span>
          </div>

          {undoConflict && <ErrorText error={new Error(undoConflict)} />}

          {/* 实际修改 */}
          <div className="dc-title" style={{ fontSize: "1em" }}>{t("实际修改", "Actual changes")}</div>
          {mutations.length === 0 ? (
            <div className="dc-muted" style={{ fontSize: "0.88em" }}>{t("这次运行没有修改任何数据。", "This run changed no data.")}</div>
          ) : (
            <div className="dc-col" style={{ gap: 8 }}>
              {mutations.map((m) => {
                const undoable = (m.status ?? "").toUpperCase() === "APPLIED" && !m.undoneAt;
                return (
                  <div key={m.id} className="dc-card" style={{ padding: "10px 12px" }}>
                    <div className="dc-row" style={{ gap: 8, flexWrap: "wrap" }}>
                      <span className="dc-chip" style={{ flexShrink: 0 }}>{m.operation || m.toolName}</span>
                      <span className="dc-grow" style={{ minWidth: 120, fontSize: "0.92em", overflowWrap: "anywhere" }}>
                        <strong>{m.target || "—"}</strong>
                        {m.summary && <span className="dc-muted"> · {m.summary}</span>}
                      </span>
                      {undoable ? (
                        <button className="dc-btn" onClick={() => void undoMutation(m)}>
                          <span className="dc-row" style={{ gap: 4 }}><Undo2 size={14} />{t("撤回", "Undo")}</span>
                        </button>
                      ) : (
                        <span className="dc-chip">{t("不可撤回", "Not undoable")}</span>
                      )}
                    </div>
                    <button
                      className="dc-btn"
                      style={{ marginTop: 6 }}
                      onClick={() => setExpandedMutation((cur) => (cur === m.id ? null : m.id))}
                    >
                      {expandedMutation === m.id ? t("收起详情", "Hide details") : t("详情", "Details")}
                    </button>
                    {expandedMutation === m.id && (
                      <div className="dc-col" style={{ gap: 6, marginTop: 8 }}>
                        <pre style={{ margin: 0, background: "var(--dc-surface-container-high)", padding: 10, borderRadius: 8, fontSize: "0.8em", whiteSpace: "pre-wrap", overflowWrap: "anywhere", maxHeight: 220, overflowY: "auto" }}>
                          {t("修改前", "Before") + "\n" + (m.beforeContent || "—")}
                        </pre>
                        <pre style={{ margin: 0, background: "var(--dc-surface-container-high)", padding: 10, borderRadius: 8, fontSize: "0.8em", whiteSpace: "pre-wrap", overflowWrap: "anywhere", maxHeight: 220, overflowY: "auto" }}>
                          {t("修改后", "After") + "\n" + (m.afterContent || "—")}
                        </pre>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}

          <hr className="dc-divider" />

          {/* 详细工具执行记录 */}
          <div className="dc-title" style={{ fontSize: "1em" }}>{t("详细工具执行记录", "Tool execution log")}</div>
          {(detailRun?.toolEvents?.length ?? 0) === 0 ? (
            <div className="dc-muted" style={{ fontSize: "0.88em" }}>{t("没有工具执行记录。", "No tool events recorded.")}</div>
          ) : (
            <div className="dc-col" style={{ gap: 6 }}>
              {(detailRun?.toolEvents ?? []).map((ev, i) => (
                <ToolEventRow
                  key={ev.toolCallId || ev.id || i}
                  event={{
                    toolCallId: ev.toolCallId ?? `e-${ev.id ?? i}`,
                    toolName: ev.toolName,
                    status: (ev.status as ToolStatus) ?? "SUCCEEDED",
                    target: ev.target,
                    summary: ev.summary,
                    argumentsSummary: ev.argumentsSummary ?? "",
                    resultSummary: ev.resultSummary ?? "",
                  }}
                />
              ))}
            </div>
          )}
        </div>
      )}
    </Modal>
  );
}
