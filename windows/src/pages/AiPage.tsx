import {
  Bot,
  BrainCircuit,
  FileText,
  ImagePlus,
  LoaderCircle,
  MessageSquarePlus,
  Pencil,
  RefreshCw,
  Send,
  Sparkles,
  Trash2,
  X,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";

import {
  ConfirmDialog,
  EmptyState,
  ErrorState,
  LoadingState,
  PageFrame,
} from "../components";
import {
  aiApi,
  createAiRequestToken,
  type AiAttachment,
  type AiContextCandidate,
  type AiContextSelection,
  type AiConversation,
  type AiMessage,
  type AiSettings,
  type AiStreamUpdate,
} from "../lib/aiApi";
import { readableError, tr } from "../lib/ipc";
import { useAppStore } from "../store/appStore";
import "./AiPage.css";

const MAX_CONTEXT_ITEMS = 50;

function contextKey(context: Pick<AiContextSelection, "source" | "reference">) {
  return `${context.source}:${context.reference}`;
}

function safeCompletionError(
  code: string | null,
  language: "zh-CN" | "en",
): string {
  const copy: Record<string, readonly [string, string]> = {
    ai_network_failed: ["AI 网络请求失败，请检查端点和网络。", "The AI network request failed. Check the endpoint and connection."],
    ai_response_invalid: ["模型返回了无法解析的响应。", "The model returned an invalid response."],
    ai_response_too_large: ["模型响应超过 4 MiB 上限。", "The model response exceeded the 4 MiB limit."],
    ai_request_too_large: ["本次请求超过安全大小上限。", "This request exceeded the safe size limit."],
    ai_redirect_not_allowed: ["模型端点尝试了不安全的重定向。", "The model endpoint attempted an unsafe redirect."],
    ai_configuration_invalid: ["请先在设置中选择可用的文字模型。", "Choose an enabled text model in Settings first."],
  };
  const value = code ? copy[code] : undefined;
  return value
    ? value[language === "en" ? 1 : 0]
    : language === "en"
      ? "The model request did not complete. Your message remains in this conversation."
      : "模型请求未完成；你的消息已保留在当前会话中。";
}

function MessageCard({
  message,
  language,
}: {
  message: AiMessage;
  language: "zh-CN" | "en";
}) {
  const t = (zh: string, en: string) => tr(language, zh, en);
  if (message.role === "CONTEXT") {
    return (
      <article className="ai-message ai-context-message">
        <header>
          <FileText size={16} aria-hidden="true" />
          <strong>{t("已冻结的参考内容", "Frozen references")}</strong>
          <span>{message.contextItems.length}</span>
        </header>
        <div className="ai-context-snapshots">
          {message.contextItems.map((item, index) => (
            <details key={`${item.source}:${item.title}:${index}`}>
              <summary>
                <span>{item.source === "DIARY" ? t("日记", "Diary") : t("小巧思", "Thought")}</span>
                <strong>{item.title}</strong>
                {item.date ? <small>{item.date}</small> : null}
              </summary>
              {item.attribution ? <p className="muted">{item.attribution}</p> : null}
              <pre>{item.content}</pre>
            </details>
          ))}
        </div>
      </article>
    );
  }

  const assistant = message.role === "ASSISTANT";
  return (
    <article className={`ai-message ${assistant ? "assistant" : "user"}`}>
      <header>
        {assistant ? <Bot size={17} aria-hidden="true" /> : <span aria-hidden="true">你</span>}
        <strong>{assistant ? t("AI", "AI") : t("你", "You")}</strong>
        {message.hasImage ? <span>{t("含图片", "Image attached")}</span> : null}
      </header>
      {message.reasoning ? (
        <details className="ai-reasoning">
          <summary>
            <BrainCircuit size={16} aria-hidden="true" />
            {t("查看思考过程", "Show reasoning")}
          </summary>
          <pre>{message.reasoning}</pre>
        </details>
      ) : null}
      {message.content ? <div className="ai-message-content">{message.content}</div> : null}
    </article>
  );
}

export default function AiPage() {
  const language = useAppStore((state) => state.appearance.language);
  const t = useCallback((zh: string, en: string) => tr(language, zh, en), [language]);
  const [settings, setSettings] = useState<AiSettings | null>(null);
  const [conversations, setConversations] = useState<AiConversation[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [messages, setMessages] = useState<AiMessage[]>([]);
  const [candidates, setCandidates] = useState<AiContextCandidate[]>([]);
  const [selectedContexts, setSelectedContexts] = useState<Set<string>>(new Set());
  const [modelConfigId, setModelConfigId] = useState("");
  const [draft, setDraft] = useState("");
  const [attachment, setAttachment] = useState<AiAttachment | null>(null);
  const attachmentRef = useRef<AiAttachment | null>(null);
  const requestTokenRef = useRef<string | null>(null);
  const [live, setLive] = useState<AiStreamUpdate | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMessages, setLoadingMessages] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [contextOpen, setContextOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<AiConversation | null>(null);
  const [renameTarget, setRenameTarget] = useState<AiConversation | null>(null);
  const [renameDraft, setRenameDraft] = useState("");

  const textConfigs = useMemo(
    () => settings?.configs.filter((item) => item.enabled && item.type === "TEXT") ?? [],
    [settings],
  );
  const activeConversation = useMemo(
    () => conversations.find((item) => item.id === activeId) ?? null,
    [activeId, conversations],
  );

  const loadPage = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [nextSettings, nextConversations, nextCandidates] = await Promise.all([
        aiApi.settings(),
        aiApi.conversations(),
        aiApi.contextCandidates(),
      ]);
      setSettings(nextSettings);
      setConversations(nextConversations);
      const selected =
        nextSettings.aiChatConfigId ??
        nextSettings.configs.find((item) => item.enabled && item.type === "TEXT")?.id ??
        "";
      setModelConfigId(selected);
      setCandidates(nextCandidates);
      if (nextConversations.length) setActiveId((current) => current ?? nextConversations[0].id);
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setLoading(false);
    }
  }, [language]);

  useEffect(() => {
    void loadPage();
  }, [loadPage]);

  useEffect(() => {
    let active = true;
    if (!activeId) {
      setMessages([]);
      return () => {
        active = false;
      };
    }
    const conversation = conversations.find((item) => item.id === activeId);
    if (conversation) setModelConfigId(conversation.modelConfigId);
    setLoadingMessages(true);
    void aiApi
      .messages(activeId)
      .then((value) => {
        if (active) setMessages(value);
      })
      .catch((reason) => {
        if (active) setError(readableError(reason, language));
      })
      .finally(() => {
        if (active) setLoadingMessages(false);
      });
    return () => {
      active = false;
    };
  }, [activeId, conversations, language]);

  useEffect(() => {
    let unlisten: (() => void) | undefined;
    void aiApi.onStreamUpdate((payload) => {
      if (payload.requestToken === requestTokenRef.current) setLive(payload);
    }).then((dispose) => {
      unlisten = dispose;
    });
    return () => unlisten?.();
  }, []);

  useEffect(() => {
    attachmentRef.current = attachment;
  }, [attachment]);

  useEffect(
    () => () => {
      const current = attachmentRef.current;
      if (current) void aiApi.cancelImage(current.token);
    },
    [],
  );

  const newConversation = () => {
    setActiveId(null);
    setMessages([]);
    setSelectedContexts(new Set());
    setLive(null);
    setError(null);
    setModelConfigId(
      settings?.aiChatConfigId ?? textConfigs[0]?.id ?? "",
    );
  };

  const toggleContext = (candidate: AiContextCandidate) => {
    const key = contextKey(candidate);
    setSelectedContexts((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else if (next.size < MAX_CONTEXT_ITEMS) next.add(key);
      return next;
    });
  };

  const chooseImage = async () => {
    try {
      const chosen = await aiApi.pickImage();
      if (!chosen) return;
      if (attachment) await aiApi.cancelImage(attachment.token);
      setAttachment(chosen);
    } catch (reason) {
      setError(readableError(reason, language));
    }
  };

  const removeImage = async () => {
    const current = attachment;
    setAttachment(null);
    if (current) {
      try {
        await aiApi.cancelImage(current.token);
      } catch (reason) {
        setError(readableError(reason, language));
      }
    }
  };

  const sendMessage = async () => {
    if (sending || (!draft.trim() && !attachment) || !modelConfigId) return;
    const requestToken = createAiRequestToken();
    requestTokenRef.current = requestToken;
    setSending(true);
    setLive({ schemaVersion: 1, requestToken, content: "", reasoning: "" });
    setError(null);
    const contexts = candidates
      .filter((item) => selectedContexts.has(contextKey(item)))
      .map(({ source, reference }) => ({ source, reference }));
    try {
      const result = await aiApi.send({
        requestToken,
        conversationId: activeId,
        modelConfigId,
        content: draft,
        attachmentToken: attachment?.token ?? null,
        contexts,
      });
      attachmentRef.current = null;
      setAttachment(null);
      setDraft("");
      setSelectedContexts(new Set());
      setMessages(result.messages);
      setActiveId(result.conversation.id);
      setConversations((current) => [
        result.conversation,
        ...current.filter((item) => item.id !== result.conversation.id),
      ]);
      if (result.status === "FAILED") {
        setError(safeCompletionError(result.errorCode, language));
      }
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      requestTokenRef.current = null;
      setLive(null);
      setSending(false);
    }
  };

  const changeModel = async (nextId: string) => {
    if (!activeId) {
      setModelConfigId(nextId);
      return;
    }
    const previous = modelConfigId;
    setModelConfigId(nextId);
    try {
      const updated = await aiApi.setConversationModel(activeId, nextId);
      setConversations((current) =>
        current.map((item) => (item.id === updated.id ? updated : item)),
      );
    } catch (reason) {
      setModelConfigId(previous);
      setError(readableError(reason, language));
    }
  };

  const deleteConversation = async () => {
    if (!deleteTarget) return;
    try {
      await aiApi.deleteConversation(deleteTarget.id);
      setConversations((current) => current.filter((item) => item.id !== deleteTarget.id));
      if (activeId === deleteTarget.id) newConversation();
      setDeleteTarget(null);
    } catch (reason) {
      setError(readableError(reason, language));
    }
  };

  const renameConversation = async () => {
    if (!renameTarget || !renameDraft.trim()) return;
    try {
      const updated = await aiApi.renameConversation(renameTarget.id, renameDraft);
      setConversations((current) =>
        current.map((item) => (item.id === updated.id ? updated : item)),
      );
      setRenameTarget(null);
    } catch (reason) {
      setError(readableError(reason, language));
    }
  };

  if (loading) {
    return <PageFrame title={t("AI 对话", "AI chat")}><LoadingState /></PageFrame>;
  }

  if (!settings) {
    return (
      <PageFrame title={t("AI 对话", "AI chat")}>
        <ErrorState description={error ?? undefined} retry={() => void loadPage()} />
      </PageFrame>
    );
  }

  return (
    <PageFrame
      title={t("AI 对话", "AI chat")}
      eyebrow={t("OpenAI 兼容接口 · 本地会话", "OpenAI-compatible · Local history")}
      description={t(
  "会话只保存在这台电脑，不进入 v29 备份或云同步。",
  "Conversations stay on this PC and are excluded from v29 backup and cloud sync.",
      )}
      actions={
        <>
          <Link className="button button-secondary" to="/settings/ai">
            <Bot size={17} aria-hidden="true" /> {t("AI 设置", "AI settings")}
          </Link>
          <button className="button button-secondary" type="button" onClick={() => void loadPage()}>
            <RefreshCw size={17} aria-hidden="true" /> {t("刷新", "Refresh")}
          </button>
        </>
      }
      className="ai-page"
    >
      {error ? <div className="inline-error" role="alert">{error}<button type="button" aria-label={t("关闭", "Close")} onClick={() => setError(null)}><X size={16} /></button></div> : null}
      {!textConfigs.length ? (
        <EmptyState
          title={t("还没有可用的文字模型", "No enabled text model")}
          description={t("前往“设置 → AI 设置”添加 OpenAI 兼容模型。", "Add an OpenAI-compatible model under Settings → AI settings.")}
          action={
            <Link className="button button-primary" to="/settings/ai">
              <Bot size={17} aria-hidden="true" /> {t("打开 AI 设置", "Open AI settings")}
            </Link>
          }
        />
      ) : (
        <div className="ai-workspace">
          <aside className="ai-history panel" aria-label={t("会话历史", "Conversation history")}>
            <button className="button button-primary" type="button" onClick={newConversation}>
              <MessageSquarePlus size={17} /> {t("新对话", "New chat")}
            </button>
            <div className="ai-conversation-list">
              {conversations.map((conversation) => (
                <div className={activeId === conversation.id ? "ai-conversation selected" : "ai-conversation"} key={conversation.id}>
                  <button type="button" onClick={() => setActiveId(conversation.id)}>
                    <strong>{conversation.title}</strong>
                    <small>{new Date(Number(conversation.updatedAt)).toLocaleString(language)}</small>
                  </button>
                  <span>
                    <button type="button" aria-label={t(`重命名 ${conversation.title}`, `Rename ${conversation.title}`)} onClick={() => { setRenameTarget(conversation); setRenameDraft(conversation.title); }}><Pencil size={14} /></button>
                    <button type="button" aria-label={t(`删除 ${conversation.title}`, `Delete ${conversation.title}`)} onClick={() => setDeleteTarget(conversation)}><Trash2 size={14} /></button>
                  </span>
                </div>
              ))}
            </div>
          </aside>

          <section className="ai-chat panel">
            <header className="ai-chat-toolbar">
              <div>
                <Sparkles size={18} aria-hidden="true" />
                <strong>{activeConversation?.title ?? t("新对话", "New chat")}</strong>
              </div>
              <label>
                <span>{t("文字模型", "Text model")}</span>
                <select value={modelConfigId} disabled={sending} onChange={(event) => void changeModel(event.target.value)}>
                  {textConfigs.map((config) => <option key={config.id} value={config.id}>{config.name} · {config.model}</option>)}
                </select>
              </label>
            </header>

            <div className="ai-messages" aria-live="polite">
              {loadingMessages ? <LoadingState /> : messages.length ? messages.map((message) => <MessageCard key={message.id} message={message} language={language} />) : (
                <div className="ai-chat-empty"><Bot size={38} aria-hidden="true" /><h2>{t("开始一段新对话", "Start a new conversation")}</h2><p>{t("可附加一张图片，或选择日记与小巧思作为冻结参考。", "Attach one image or select diary/thought snapshots as frozen references.")}</p></div>
              )}
              {sending && live ? (
                <article className="ai-message assistant streaming">
                  <header><LoaderCircle className="spin" size={17} /><strong>{t("AI 正在回复", "AI is responding")}</strong></header>
                  {live.reasoning ? <details className="ai-reasoning" open><summary><BrainCircuit size={16} />{t("思考过程", "Reasoning")}</summary><pre>{live.reasoning}</pre></details> : null}
                  <div className="ai-message-content">{live.content || t("正在连接模型…", "Connecting to model…")}</div>
                </article>
              ) : null}
            </div>

            <div className="ai-composer">
              {contextOpen ? (
                <section className="ai-context-picker" aria-label={t("选择参考内容", "Select references")}>
                  <header><div><strong>{t("冻结参考", "Frozen references")}</strong><small>{t("点击发送时读取并冻结；历史不会随源文件变化。", "Read and frozen when you send; history does not change with the source.")}</small></div><span>{selectedContexts.size}/{MAX_CONTEXT_ITEMS}</span></header>
                  <div>
                    {candidates.length ? candidates.map((candidate) => {
                      const key = contextKey(candidate);
                      const checked = selectedContexts.has(key);
                      return <label className="ai-context-option" key={key}><input type="checkbox" checked={checked} disabled={!checked && selectedContexts.size >= MAX_CONTEXT_ITEMS} onChange={() => toggleContext(candidate)} /><span><strong>{candidate.title}</strong><small>{candidate.source === "DIARY" ? t("日记", "Diary") : t("小巧思", "Thought")}{candidate.subtitle ? ` · ${candidate.subtitle}` : ""}</small><em>{candidate.previewExcerpt}</em></span></label>;
                    }) : <p className="muted">{t("没有可选的日记或小巧思。", "No diary or thought references are available.")}</p>}
                  </div>
                </section>
              ) : null}
              {attachment ? <div className="ai-attachment"><ImagePlus size={16} /><span><strong>{attachment.displayName}</strong><small>{(attachment.byteSize / 1024 / 1024).toFixed(2)} MiB</small></span><button type="button" aria-label={t("移除图片", "Remove image")} onClick={() => void removeImage()}><X size={16} /></button></div> : null}
              <textarea
                aria-label={t("消息", "Message")}
                placeholder={t("输入消息；Ctrl + Enter 发送", "Write a message; Ctrl + Enter to send")}
                value={draft}
                maxLength={100_000}
                disabled={sending}
                onChange={(event) => setDraft(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && event.ctrlKey) {
                    event.preventDefault();
                    void sendMessage();
                  }
                }}
              />
              <footer>
                <div>
                  <button className={contextOpen ? "button button-secondary selected" : "button button-secondary"} type="button" onClick={() => setContextOpen((value) => !value)}><FileText size={17} />{t("参考", "References")}{selectedContexts.size ? ` (${selectedContexts.size})` : ""}</button>
                  <button className="button button-secondary" type="button" disabled={sending} onClick={() => void chooseImage()}><ImagePlus size={17} />{t("图片", "Image")}</button>
                </div>
                <button className="button button-primary" type="button" disabled={sending || (!draft.trim() && !attachment) || !modelConfigId} onClick={() => void sendMessage()}>{sending ? <LoaderCircle className="spin" size={17} /> : <Send size={17} />}{t("发送", "Send")}</button>
              </footer>
            </div>
          </section>
        </div>
      )}

      <ConfirmDialog
        open={deleteTarget !== null}
        title={t("删除这段对话？", "Delete this conversation?")}
        description={t("消息与冻结参考会从这台电脑永久删除。", "Messages and frozen references will be permanently deleted from this PC.")}
        confirmLabel={t("删除", "Delete")}
        destructive
        onCancel={() => setDeleteTarget(null)}
        onConfirm={() => void deleteConversation()}
      />
      <ConfirmDialog
        open={renameTarget !== null}
        title={t("重命名对话", "Rename conversation")}
        description={<label className="ai-rename-field"><span>{t("名称", "Name")}</span><input autoFocus maxLength={80} value={renameDraft} onChange={(event) => setRenameDraft(event.target.value)} /></label>}
        confirmLabel={t("保存", "Save")}
        onCancel={() => setRenameTarget(null)}
        onConfirm={() => void renameConversation()}
      />
    </PageFrame>
  );
}
