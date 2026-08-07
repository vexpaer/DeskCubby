import * as Dialog from "@radix-ui/react-dialog";
import {
  ExternalLink,
  FileWarning,
  Pencil,
  Plus,
  RefreshCw,
  Rss,
  Settings2,
  Trash2,
  X,
} from "lucide-react";
import {
  type FormEvent,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";

import {
  ConfirmDialog,
  EmptyState,
  LoadingState,
  PageFrame,
  UnsavedChangesGuard,
} from "../components";
import { DeskCubbyIpcError, tr } from "../lib/ipc";
import {
  rssApi,
  type RssArticleV1,
  type RssFeedErrorV1,
  type RssPageV1,
  type RssSubscriptionV1,
} from "../lib/rssApi";
import { useAppStore } from "../store/appStore";
import "./RssPage.css";

type Language = "zh-CN" | "en";

const FEED_ERROR_MESSAGES: Record<string, [string, string]> = {
  rss_invalid_url: ["订阅地址无效。", "The feed URL is invalid."],
  rss_https_required: ["订阅源仅支持 HTTPS。", "Feeds must use HTTPS."],
  rss_private_host: ["订阅源必须使用公网 HTTPS 主机。", "The feed must use a public HTTPS host."],
  rss_dns_failed: ["无法解析订阅源主机。", "The feed host could not be resolved."],
  rss_timed_out: ["订阅源请求超时。", "The feed request timed out."],
  rss_redirect_not_allowed: [
    "订阅源尝试跳转到不同主机、端口或非 HTTPS 地址。",
    "The feed redirected to a different host, port, or a non-HTTPS URL.",
  ],
  rss_http_failed: ["订阅源返回了错误状态。", "The feed returned an error status."],
  rss_too_large: ["订阅内容超过 5 MiB 上限。", "The feed exceeds the 5 MiB limit."],
  rss_doctype_forbidden: ["为保证安全，不支持包含 DOCTYPE 的订阅。", "Feeds containing DOCTYPE are not supported."],
  rss_unsupported_format: ["该地址不是 RSS 2.0 或 Atom。", "This is not an RSS 2.0 or Atom feed."],
  rss_parse_failed: ["无法解析订阅内容。", "The feed could not be parsed."],
  rss_network_failed: ["网络请求失败。", "The network request failed."],
};

const COMMAND_ERROR_MESSAGES: Record<string, [string, string]> = {
  ...FEED_ERROR_MESSAGES,
  rss_duplicate_feed: ["这个 RSS 地址已经添加过了。", "This RSS feed has already been added."],
  rss_limit_exceeded: ["RSS 设置超过安全上限。", "The RSS safety limit was exceeded."],
  rss_not_found: ["这项 RSS 内容已不存在，请刷新。", "This RSS item no longer exists. Refresh and try again."],
  rss_open_failed: ["无法使用系统浏览器打开文章。", "The article could not be opened in the system browser."],
  rss_storage_unavailable: ["RSS 设置暂时无法保存。", "RSS settings are temporarily unavailable."],
};

function rssErrorMessage(reason: unknown, language: Language): string {
  const code = reason instanceof DeskCubbyIpcError ? reason.code : "";
  const copy = COMMAND_ERROR_MESSAGES[code];
  if (copy) return language === "en" ? copy[1] : copy[0];
  return tr(language, "RSS 操作失败，请重试。", "The RSS operation failed. Try again.");
}

function feedErrorMessage(error: RssFeedErrorV1, language: Language): string {
  const copy = FEED_ERROR_MESSAGES[error.code];
  if (copy) return language === "en" ? copy[1] : copy[0];
  return tr(language, "订阅刷新失败。", "The feed could not be refreshed.");
}

function formatTimestamp(value: string | null, language: Language): string | null {
  if (!value || !/^\d+$/.test(value)) return null;
  const timestamp = Number(value);
  if (!Number.isSafeInteger(timestamp)) return null;
  const date = new Date(timestamp);
  if (Number.isNaN(date.valueOf())) return null;
  return new Intl.DateTimeFormat(language === "en" ? "en-US" : "zh-CN", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date);
}

function FeedEditor({
  open,
  feed,
  busy,
  language,
  onClose,
  onDirtyChange,
  onSave,
}: {
  open: boolean;
  feed: RssSubscriptionV1 | null;
  busy: boolean;
  language: Language;
  onClose: () => void;
  onDirtyChange: (dirty: boolean) => void;
  onSave: (title: string, url: string) => void;
}) {
  const [title, setTitle] = useState("");
  const [url, setUrl] = useState("");
  const [discardOpen, setDiscardOpen] = useState(false);
  const dirty =
    title !== (feed?.title ?? "") || url !== (feed?.url ?? "");

  useEffect(() => {
    if (open) {
      setTitle(feed?.title ?? "");
      setUrl(feed?.url ?? "");
    }
  }, [feed, open]);

  useEffect(() => {
    onDirtyChange(open && dirty);
  }, [dirty, onDirtyChange, open]);

  function requestClose() {
    if (busy) return;
    if (dirty) setDiscardOpen(true);
    else onClose();
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!busy && url.trim()) onSave(title, url);
  }

  return (
    <>
      <Dialog.Root open={open} onOpenChange={(next) => !next && requestClose()}>
        <Dialog.Portal>
          <Dialog.Overlay className="dialog-overlay" />
          <Dialog.Content className="dialog-content" aria-describedby="rss-editor-description">
          <Dialog.Title className="dialog-title">
            {feed
              ? tr(language, "编辑 RSS 订阅", "Edit RSS feed")
              : tr(language, "添加 RSS 订阅", "Add RSS feed")}
          </Dialog.Title>
          <Dialog.Description id="rss-editor-description" className="dialog-description">
            {tr(
              language,
              "仅支持公网 HTTPS 订阅地址；省略协议时会自动补充 https://。",
              "Only public HTTPS feed URLs are supported. https:// is added when omitted.",
            )}
          </Dialog.Description>
          <form className="rss-preferences-grid" onSubmit={submit}>
            <label className="field">
              <span className="field-label">{tr(language, "名称（可选）", "Name (optional)")}</span>
              <input
                autoFocus
                maxLength={120}
                value={title}
                disabled={busy}
                onChange={(event) => setTitle(event.target.value)}
              />
            </label>
            <label className="field">
              <span className="field-label">{tr(language, "HTTPS 订阅地址", "HTTPS feed URL")}</span>
              <input
                type="text"
                inputMode="url"
                maxLength={8192}
                required
                value={url}
                disabled={busy}
                onChange={(event) => setUrl(event.target.value)}
              />
            </label>
            <div className="dialog-actions">
              <button className="button-secondary" type="button" disabled={busy} onClick={requestClose}>
                {tr(language, "取消", "Cancel")}
              </button>
              <button className="button-primary" type="submit" disabled={busy || !url.trim()}>
                {busy ? tr(language, "保存中…", "Saving…") : tr(language, "保存", "Save")}
              </button>
            </div>
          </form>
          <button className="icon-button dialog-close" type="button" aria-label={tr(language, "关闭", "Close")} disabled={busy} onClick={requestClose}>
            <X aria-hidden="true" size={18} />
          </button>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
      <ConfirmDialog
        open={discardOpen}
        title={tr(language, "放弃未保存的订阅？", "Discard unsaved feed?")}
        description={tr(language, "当前订阅草稿尚未保存。", "The current feed draft has not been saved.")}
        confirmLabel={tr(language, "放弃更改", "Discard changes")}
        destructive
        onCancel={() => setDiscardOpen(false)}
        onConfirm={() => {
          setDiscardOpen(false);
          onDirtyChange(false);
          onClose();
        }}
      />
    </>
  );
}

function PreferencesDialog({
  open,
  page,
  busy,
  language,
  onClose,
  onDirtyChange,
  onSave,
}: {
  open: boolean;
  page: RssPageV1;
  busy: boolean;
  language: Language;
  onClose: () => void;
  onDirtyChange: (dirty: boolean) => void;
  onSave: (maxItems: number, showSummaries: boolean) => void;
}) {
  const [maxItems, setMaxItems] = useState(String(page.maxItemsPerFeed));
  const [showSummaries, setShowSummaries] = useState(page.showSummaries);
  const [discardOpen, setDiscardOpen] = useState(false);

  useEffect(() => {
    if (open) {
      setMaxItems(String(page.maxItemsPerFeed));
      setShowSummaries(page.showSummaries);
    }
  }, [open, page.maxItemsPerFeed, page.showSummaries]);

  const parsedMaxItems = /^\d+$/.test(maxItems) ? Number(maxItems) : Number.NaN;
  const valid = Number.isInteger(parsedMaxItems) && parsedMaxItems >= 10 && parsedMaxItems <= 200;
  const dirty =
    maxItems !== String(page.maxItemsPerFeed) ||
    showSummaries !== page.showSummaries;

  useEffect(() => {
    onDirtyChange(open && dirty);
  }, [dirty, onDirtyChange, open]);

  function requestClose() {
    if (busy) return;
    if (dirty) setDiscardOpen(true);
    else onClose();
  }

  return (
    <>
      <Dialog.Root open={open} onOpenChange={(next) => !next && requestClose()}>
        <Dialog.Portal>
          <Dialog.Overlay className="dialog-overlay" />
          <Dialog.Content className="dialog-content" aria-describedby="rss-preferences-description">
          <Dialog.Title className="dialog-title">
            {tr(language, "RSS 阅读设置", "RSS reading settings")}
          </Dialog.Title>
          <Dialog.Description id="rss-preferences-description" className="dialog-description">
            {tr(language, "设置每个订阅的条目上限与摘要显示。", "Choose the item limit and summary display for each feed.")}
          </Dialog.Description>
          <div className="rss-preferences-grid">
            <label className="field">
              <span className="field-label">{tr(language, "每个订阅最多条目", "Maximum items per feed")}</span>
              <input
                type="number"
                min={10}
                max={200}
                step={1}
                value={maxItems}
                disabled={busy}
                onChange={(event) => setMaxItems(event.currentTarget.value)}
              />
              <small className="field-hint">{tr(language, "可设置 10–200。", "Choose 10–200.")}</small>
            </label>
            <label className="checkbox-row">
              <input
                type="checkbox"
                checked={showSummaries}
                disabled={busy}
                onChange={(event) => setShowSummaries(event.target.checked)}
              />
              <span>{tr(language, "在文章卡片显示摘要", "Show summaries on article cards")}</span>
            </label>
            <div className="dialog-actions">
              <button className="button-secondary" type="button" disabled={busy} onClick={requestClose}>
                {tr(language, "取消", "Cancel")}
              </button>
              <button
                className="button-primary"
                type="button"
                disabled={busy || !valid}
                onClick={() => onSave(parsedMaxItems, showSummaries)}
              >
                {busy ? tr(language, "保存中…", "Saving…") : tr(language, "保存", "Save")}
              </button>
            </div>
          </div>
          <button className="icon-button dialog-close" type="button" aria-label={tr(language, "关闭", "Close")} disabled={busy} onClick={requestClose}>
            <X aria-hidden="true" size={18} />
          </button>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
      <ConfirmDialog
        open={discardOpen}
        title={tr(language, "放弃未保存的 RSS 设置？", "Discard unsaved RSS settings?")}
        description={tr(language, "文章上限或摘要显示的修改尚未保存。", "Changes to the item limit or summaries have not been saved.")}
        confirmLabel={tr(language, "放弃更改", "Discard changes")}
        destructive
        onCancel={() => setDiscardOpen(false)}
        onConfirm={() => {
          setDiscardOpen(false);
          onDirtyChange(false);
          onClose();
        }}
      />
    </>
  );
}

function ArticleCard({
  article,
  showSummary,
  opening,
  language,
  onOpen,
}: {
  article: RssArticleV1;
  showSummary: boolean;
  opening: boolean;
  language: Language;
  onOpen: () => void;
}) {
  const published = formatTimestamp(article.publishedAtMs, language);
  const title = article.title || tr(language, "未命名文章", "Untitled article");
  return (
    <button
      className="rss-article-card card"
      type="button"
      disabled={!article.urlAvailable || opening}
      onClick={onOpen}
      aria-label={
        article.urlAvailable
          ? tr(language, `在系统浏览器打开：${title}`, `Open in system browser: ${title}`)
          : tr(language, `没有安全链接：${title}`, `No safe link: ${title}`)
      }
    >
      <span className="rss-article-title-row">
        <strong>{title}</strong>
        {article.urlAvailable ? <ExternalLink aria-hidden="true" size={17} /> : <FileWarning aria-hidden="true" size={17} />}
      </span>
      <span className="rss-article-meta">
        {[article.feedTitle, published].filter(Boolean).join(" · ")}
      </span>
      {!article.urlAvailable ? (
        <span className="rss-feed-error">
          {tr(language, "文章链接不安全或不受支持", "The article link is unsafe or unsupported")}
        </span>
      ) : null}
      {showSummary && article.summary ? <span className="rss-article-summary">{article.summary}</span> : null}
    </button>
  );
}

export default function RssPage() {
  const language = useAppStore((state) => state.appearance.language);
  const [page, setPage] = useState<RssPageV1 | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [editing, setEditing] = useState<RssSubscriptionV1 | "new" | null>(null);
  const [deleting, setDeleting] = useState<RssSubscriptionV1 | null>(null);
  const [preferencesOpen, setPreferencesOpen] = useState(false);
  const [editorDirty, setEditorDirty] = useState(false);
  const [preferencesDirty, setPreferencesDirty] = useState(false);
  const initialRefreshStarted = useRef(false);

  const copy = useCallback(
    (zh: string, en: string) => tr(language, zh, en),
    [language],
  );

  const refresh = useCallback(async (automatic = false) => {
    setBusy("refresh");
    if (!automatic) {
      setError("");
      setNotice("");
    }
    try {
      const next = await rssApi.refresh();
      setPage(next);
      if (!automatic) setNotice(copy("RSS 已刷新。", "RSS feeds refreshed."));
    } catch (reason) {
      setError(rssErrorMessage(reason, language));
    } finally {
      setBusy("");
    }
  }, [copy, language]);

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const next = await rssApi.page();
      setPage(next);
      if (!initialRefreshStarted.current && next.subscriptions.some((feed) => feed.enabled)) {
        initialRefreshStarted.current = true;
        void refresh(true);
      }
    } catch (reason) {
      setError(rssErrorMessage(reason, language));
    } finally {
      setLoading(false);
    }
  }, [language, refresh]);

  useEffect(() => {
    void load();
  }, [load]);

  const feedErrors = useMemo(
    () => new Map((page?.errors ?? []).map((item) => [item.feedId, item])),
    [page?.errors],
  );

  async function saveFeed(title: string, url: string) {
    const target = editing;
    if (!target) return;
    setBusy("save");
    setError("");
    setNotice("");
    try {
      const next = await rssApi.save({
        id: target === "new" ? null : target.id,
        title,
        url,
      });
      setPage(next);
      setEditing(null);
      setNotice(copy("订阅已保存。", "Feed saved."));
      await refresh(true);
    } catch (reason) {
      setError(rssErrorMessage(reason, language));
    } finally {
      setBusy("");
    }
  }

  async function removeFeed() {
    if (!deleting) return;
    setBusy("delete");
    setError("");
    try {
      setPage(await rssApi.remove(deleting.id));
      setDeleting(null);
      setNotice(copy("订阅已删除。", "Feed deleted."));
    } catch (reason) {
      setError(rssErrorMessage(reason, language));
    } finally {
      setBusy("");
    }
  }

  async function setEnabled(feed: RssSubscriptionV1, enabled: boolean) {
    setBusy(`toggle:${feed.id}`);
    setError("");
    try {
      setPage(await rssApi.setEnabled(feed.id, enabled));
      if (enabled) await refresh(true);
    } catch (reason) {
      setError(rssErrorMessage(reason, language));
    } finally {
      setBusy("");
    }
  }

  async function savePreferences(maxItems: number, showSummaries: boolean) {
    setBusy("preferences");
    setError("");
    try {
      setPage(await rssApi.setPreferences(maxItems, showSummaries));
      setPreferencesOpen(false);
      setNotice(copy("RSS 阅读设置已保存。", "RSS reading settings saved."));
    } catch (reason) {
      setError(rssErrorMessage(reason, language));
    } finally {
      setBusy("");
    }
  }

  async function openArticle(article: RssArticleV1) {
    setBusy(`open:${article.id}`);
    setError("");
    try {
      await rssApi.openArticle(article.id);
      setNotice(copy("已交给系统浏览器打开。", "Opened in the system browser."));
    } catch (reason) {
      setError(rssErrorMessage(reason, language));
    } finally {
      setBusy("");
    }
  }

  if (loading && !page) {
    return (
      <PageFrame title="RSS">
        <div className="panel">
          <LoadingState label={copy("正在读取 RSS 设置", "Loading RSS settings")} />
        </div>
      </PageFrame>
    );
  }

  if (!page) {
    return (
      <PageFrame
        title="RSS"
        actions={
          <button className="button-secondary" type="button" onClick={() => void load()}>
            <RefreshCw aria-hidden="true" size={17} />
            {copy("重试", "Retry")}
          </button>
        }
      >
        <div className="inline-error" role="alert">{error}</div>
      </PageFrame>
    );
  }

  const updated = formatTimestamp(page.lastUpdatedAtMs, language);
  return (
    <PageFrame
      className="rss-page"
      eyebrow={copy("RSS 2.0 / Atom · 文章仅缓存于内存", "RSS 2.0 / Atom · Articles cached in memory only")}
      title="RSS"
      description={copy(
        "管理 HTTPS 订阅并在系统浏览器中安全阅读全文；DeskCubby 不会建立内置浏览器页。",
        "Manage HTTPS feeds and safely read full articles in your system browser. DeskCubby does not add an embedded browser page.",
      )}
      actions={
        <>
          <button className="button-secondary" type="button" disabled={!!busy} onClick={() => setPreferencesOpen(true)}>
            <Settings2 aria-hidden="true" size={17} />
            {copy("阅读设置", "Reading settings")}
          </button>
          <button
            className="button-secondary"
            type="button"
            disabled={!!busy || !page.subscriptions.some((feed) => feed.enabled)}
            onClick={() => void refresh(false)}
          >
            <RefreshCw className={busy === "refresh" ? "spin" : ""} aria-hidden="true" size={17} />
            {busy === "refresh" ? copy("刷新中…", "Refreshing…") : copy("刷新", "Refresh")}
          </button>
          <button className="button-primary" type="button" disabled={!!busy} onClick={() => setEditing("new")}>
            <Plus aria-hidden="true" size={17} />
            {copy("添加订阅", "Add feed")}
          </button>
        </>
      }
    >
      <UnsavedChangesGuard
        when={editorDirty || preferencesDirty}
        scope="rss-drafts"
        title={copy("放弃未保存的 RSS 修改？", "Discard unsaved RSS changes?")}
        description={copy(
          "离开 RSS 页面会丢弃当前订阅或阅读设置草稿。",
          "Leaving RSS will discard the current feed or reading-settings draft.",
        )}
        onDiscard={() => {
          setEditing(null);
          setPreferencesOpen(false);
          setEditorDirty(false);
          setPreferencesDirty(false);
        }}
      />
      {error ? <div className="inline-error" role="alert">{error}</div> : null}
      {notice ? <div className="status-banner success" role="status">{notice}</div> : null}

      <section className="rss-section" aria-labelledby="rss-feeds-title">
        <div className="rss-section-heading">
          <div>
            <h2 id="rss-feeds-title">{copy("订阅源", "Feeds")}</h2>
            <p className="rss-section-detail">
              {copy(
                `每个订阅最多 ${page.maxItemsPerFeed} 篇；同时最多刷新 4 个。`,
                `Up to ${page.maxItemsPerFeed} items per feed; up to 4 refresh concurrently.`,
              )}
            </p>
          </div>
          {updated ? <span className="rss-last-updated">{copy(`更新于 ${updated}`, `Updated ${updated}`)}</span> : null}
        </div>
        {page.subscriptions.length ? (
          <div className="card-grid rss-feed-grid">
            {page.subscriptions.map((feed) => {
              const feedError = feedErrors.get(feed.id);
              return (
                <article className="card rss-feed-card" key={feed.id}>
                  <div className="rss-feed-heading">
                    <input
                      type="checkbox"
                      checked={feed.enabled}
                      disabled={!!busy}
                      aria-label={copy(`启用 ${feed.title}`, `Enable ${feed.title}`)}
                      onChange={(event) => void setEnabled(feed, event.target.checked)}
                    />
                    <div>
                      <h3>{feed.title || feed.url}</h3>
                      <p title={feed.url}>{feed.url}</p>
                    </div>
                    <Rss aria-hidden="true" size={19} />
                  </div>
                  {feedError ? <p className="rss-feed-error" role="status">{feedErrorMessage(feedError, language)}</p> : null}
                  <div className="rss-feed-actions">
                    <span className="rss-section-detail">
                      {feed.enabled ? copy("已启用", "Enabled") : copy("已暂停", "Paused")}
                    </span>
                    <span className="inline-actions">
                      <button
                        className="icon-button"
                        type="button"
                        disabled={!!busy}
                        aria-label={copy(`编辑 ${feed.title}`, `Edit ${feed.title}`)}
                        onClick={() => setEditing(feed)}
                      >
                        <Pencil aria-hidden="true" size={17} />
                      </button>
                      <button
                        className="icon-button"
                        type="button"
                        disabled={!!busy}
                        aria-label={copy(`删除 ${feed.title}`, `Delete ${feed.title}`)}
                        onClick={() => setDeleting(feed)}
                      >
                        <Trash2 aria-hidden="true" size={17} />
                      </button>
                    </span>
                  </div>
                </article>
              );
            })}
          </div>
        ) : (
          <div className="panel">
            <EmptyState
              icon={Rss}
              title={copy("还没有 RSS 订阅", "No RSS feeds yet")}
              description={copy("添加一个公网 HTTPS 订阅地址。", "Add a public HTTPS feed URL.")}
              action={
                <button className="button-primary" type="button" onClick={() => setEditing("new")}>
                  <Plus aria-hidden="true" size={16} />
                  {copy("添加订阅", "Add feed")}
                </button>
              }
            />
          </div>
        )}
      </section>

      {page.subscriptions.length ? (
        <section className="rss-section" aria-labelledby="rss-articles-title">
          <div className="rss-section-heading">
            <div>
              <h2 id="rss-articles-title">{copy("最新文章", "Latest articles")}</h2>
              <p className="rss-section-detail">{copy(`${page.articles.length} 篇`, `${page.articles.length} items`)}</p>
            </div>
          </div>
          {page.articles.length ? (
            <div className="rss-article-list">
              {page.articles.map((article) => (
                <ArticleCard
                  key={article.id}
                  article={article}
                  showSummary={page.showSummaries}
                  opening={busy === `open:${article.id}`}
                  language={language}
                  onOpen={() => void openArticle(article)}
                />
              ))}
            </div>
          ) : (
            <div className="panel">
              <EmptyState
                icon={Rss}
                title={copy("暂无文章", "No articles yet")}
                description={copy("启用订阅后点击刷新。", "Enable a feed, then refresh.")}
                action={
                  <button
                    className="button-primary"
                    type="button"
                    disabled={!!busy || !page.subscriptions.some((feed) => feed.enabled)}
                    onClick={() => void refresh(false)}
                  >
                    <RefreshCw aria-hidden="true" size={16} />
                    {copy("刷新", "Refresh")}
                  </button>
                }
              />
            </div>
          )}
        </section>
      ) : null}

      <FeedEditor
        open={editing !== null}
        feed={editing === "new" ? null : editing}
        busy={busy === "save"}
        language={language}
        onClose={() => setEditing(null)}
        onDirtyChange={setEditorDirty}
        onSave={(title, url) => void saveFeed(title, url)}
      />
      <PreferencesDialog
        open={preferencesOpen}
        page={page}
        busy={busy === "preferences"}
        language={language}
        onClose={() => setPreferencesOpen(false)}
        onDirtyChange={setPreferencesDirty}
        onSave={(maxItems, summaries) => void savePreferences(maxItems, summaries)}
      />
      <ConfirmDialog
        open={deleting !== null}
        title={copy("删除 RSS 订阅？", "Delete RSS feed?")}
        description={copy(
          `将删除“${deleting?.title || deleting?.url || "RSS"}”及当前内存中的文章。`,
          `This removes “${deleting?.title || deleting?.url || "RSS"}” and its in-memory articles.`,
        )}
        confirmLabel={copy("删除", "Delete")}
        destructive
        busy={busy === "delete"}
        onCancel={() => setDeleting(null)}
        onConfirm={() => void removeFeed()}
      />
    </PageFrame>
  );
}
