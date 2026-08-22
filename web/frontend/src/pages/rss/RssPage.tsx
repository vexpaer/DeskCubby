/**
 * RSS 订阅 (/rss) — port of Android ui/rss/RssScreen.kt + RssViewModel.kt.
 * Horizontal feed cards (enable/edit/delete persisted in settings.rssSubscriptions),
 * latest-articles list from /api/rss/items, refresh via POST /api/rss/refresh,
 * articles open the built-in browser at /blog?url=….
 */
import React, { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Newspaper as IconArticle, Pencil as IconEdit, Plus as IconPlus,
  RefreshCw as IconRefresh, Rss as IconRss, Trash2 as IconDelete,
} from "lucide-react";
import { apiGet, apiSend } from "../../api/client";
import type { RssSubscription } from "../../api/types";
import { useSettings } from "../../stores/settings";
import { tr, uiLanguage } from "../../i18n/tr";
import {
  ConfirmDialog, EmptyState, Modal, PageTutorialOverlay, Snackbar, Spinner, TopBar, useSnackbar,
} from "../../components/ui";

interface RssArticle {
  id: string;
  feedId: string;
  feedTitle: string;
  title: string;
  url: string;
  summary: string;
  publishedAtMillis: number | null;
}

type ArticleUrl =
  | { kind: "valid"; value: string }
  | { kind: "missing" }
  | { kind: "unsafe" };

const MAX_ARTICLE_URL_LENGTH = 8192;

function arrayOf<T>(v: unknown): T[] {
  if (Array.isArray(v)) return v as T[];
  if (v && typeof v === "object") {
    const obj = v as Record<string, unknown>;
    for (const key of ["items", "records", "data", "results", "articles"]) {
      if (Array.isArray(obj[key])) return obj[key] as T[];
    }
  }
  return [];
}

/** Port of RssArticleUrl.kt normalizeRssArticleUrl — absolute HTTPS links only. */
export function normalizeRssArticleUrl(raw: string): ArticleUrl {
  const candidate = raw.trim();
  if (!candidate) return { kind: "missing" };
  if (candidate.length > MAX_ARTICLE_URL_LENGTH) return { kind: "unsafe" };
  let uri: URL;
  try {
    uri = new URL(candidate);
  } catch {
    return { kind: "unsafe" };
  }
  if (uri.protocol.toLowerCase() !== "https:") return { kind: "unsafe" };
  if (!uri.hostname || uri.username || uri.password) return { kind: "unsafe" };
  return { kind: "valid", value: uri.href };
}

/** Port of RssRepository.normalizeFeedUrl validation (client side). */
export function normalizeFeedUrl(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed) throw new Error(tr("RSS 地址不能为空。", "The RSS URL cannot be empty."));
  if (trimmed.length > 2048 || /[\u0000-\u001f]/.test(trimmed)) {
    throw new Error(tr("RSS 地址过长或包含无效字符。", "The RSS URL is too long or contains invalid characters."));
  }
  const withScheme = /^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(trimmed) ? trimmed : `https://${trimmed}`;
  let uri: URL;
  try {
    uri = new URL(withScheme);
  } catch {
    throw new Error(tr("RSS 地址格式不正确。", "The RSS URL is malformed."));
  }
  if (uri.protocol === "http:") {
    throw new Error(tr("RSS 地址仅支持 HTTPS，请将 http:// 改为 https://。", "RSS supports HTTPS only; change http:// to https://."));
  }
  if (uri.protocol !== "https:") {
    throw new Error(tr("RSS 地址仅支持 HTTPS。", "RSS supports HTTPS only."));
  }
  return withScheme;
}

function formatArticleMeta(article: RssArticle): string {
  const date = article.publishedAtMillis
    ? new Intl.DateTimeFormat(uiLanguage() === "ENGLISH" ? "en-US" : "zh-CN", {
        dateStyle: "medium", timeStyle: "short",
      }).format(new Date(article.publishedAtMillis))
    : null;
  return [article.feedTitle || null, date].filter(Boolean).join(" · ");
}

export default function RssPage() {
  const navigate = useNavigate();
  const settingsState = useSettings();
  const settings = settingsState.settings;
  const [snack, showSnack] = useSnackbar();

  const [articles, setArticles] = useState<RssArticle[]>([]);
  const [refreshing, setRefreshing] = useState(false);
  const [showNewSubscription, setShowNewSubscription] = useState(false);
  const [editingSubscription, setEditingSubscription] = useState<RssSubscription | null>(null);
  const [deletingSubscription, setDeletingSubscription] = useState<RssSubscription | null>(null);
  const startedRefreshRef = useRef(false);

  const subscriptions = settings?.rssSubscriptions ?? [];
  const maxItemsPerFeed = settings?.rssMaxItemsPerFeed ?? 20;
  const showSummaries = settings?.rssShowSummaries ?? true;

  const fail = useCallback((e: unknown) => {
    showSnack(e instanceof Error ? e.message : String(e));
  }, [showSnack]);

  /** POST /api/rss/refresh re-fetches all enabled feeds server-side. */
  const refresh = useCallback(async () => {
    if (!settings) return;
    const enabled = subscriptions.filter((s) => s.enabled);
    if (enabled.length === 0) {
      setArticles([]);
      showSnack(
        subscriptions.length === 0
          ? tr("请先添加一个 RSS 订阅。", "Add an RSS feed first.")
          : tr("没有已启用的 RSS 订阅。", "No enabled RSS feeds."),
      );
      return;
    }
    setRefreshing(true);
    try {
      const data = await apiSend<unknown>("/api/rss/refresh", "POST");
      setArticles(arrayOf<RssArticle>(data));
    } catch (e) {
      fail(e);
    } finally {
      setRefreshing(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settings, subscriptions, fail, showSnack]);

  // Android auto-refreshes once on entry when any subscription is enabled.
  useEffect(() => {
    if (startedRefreshRef.current || !settings) return;
    startedRefreshRef.current = true;
    if ((settings.rssSubscriptions ?? []).some((s) => s.enabled)) void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settings]);

  // Load cached items once so the page is not empty before the first refresh.
  useEffect(() => {
    let cancelled = false;
    apiGet<unknown>("/api/rss/items")
      .then((data) => {
        if (!cancelled) setArticles(arrayOf<RssArticle>(data));
      })
      .catch(() => undefined);
    return () => { cancelled = true; };
  }, []);

  const persistSubscriptions = async (next: RssSubscription[]): Promise<boolean> => {
    try {
      await settingsState.update({ rssSubscriptions: next });
      return true;
    } catch (e) {
      showSnack(
        tr("保存 RSS 订阅失败。", "Failed to save RSS feeds.") + " " +
        (e instanceof Error ? e.message : String(e)),
      );
      return false;
    }
  };

  const saveSubscription = async (
    subscriptionId: string | null,
    title: string,
    url: string,
  ): Promise<boolean> => {
    let normalizedUrl: string;
    try {
      normalizedUrl = normalizeFeedUrl(url);
    } catch (e) {
      fail(e);
      return false;
    }
    if (subscriptions.some((s) => s.id !== subscriptionId && s.url.toLowerCase() === normalizedUrl.toLowerCase())) {
      showSnack(tr("这个 RSS 地址已经添加过了。", "This RSS URL has already been added."));
      return false;
    }
    const normalizedTitle = title.trim() || hostOf(normalizedUrl) || "RSS";
    const next = subscriptionId == null
      ? [...subscriptions, { id: cryptoId(), title: normalizedTitle, url: normalizedUrl, enabled: true }]
      : subscriptions.map((s) => (s.id === subscriptionId ? { ...s, title: normalizedTitle, url: normalizedUrl } : s));
    return persistSubscriptions(next);
  };

  const deleteSubscription = async (subscriptionId: string) => {
    const ok = await persistSubscriptions(subscriptions.filter((s) => s.id !== subscriptionId));
    if (ok) setArticles((prev) => prev.filter((a) => a.feedId !== subscriptionId));
  };

  const setSubscriptionEnabled = async (subscriptionId: string, enabled: boolean) => {
    const next = subscriptions.map((s) => (s.id === subscriptionId ? { ...s, enabled } : s));
    const ok = await persistSubscriptions(next);
    if (!ok) return;
    if (!enabled) {
      setArticles((prev) => prev.filter((a) => a.feedId !== subscriptionId));
    } else {
      void refresh();
    }
  };

  if (!settings) return <Spinner />;

  return (
    <div>
      <TopBar
        title="RSS"
        actions={
          <button
            className="dc-icon-btn"
            aria-label={tr("刷新订阅", "Refresh feeds")}
            disabled={refreshing}
            onClick={() => void refresh()}
          >
            {refreshing ? <Spinner size={18} /> : <IconRefresh size={19} />}
          </button>
        }
      />

      {subscriptions.length === 0 ? (
        <EmptyState
          icon={<IconRss size={40} />}
          title={tr("还没有 RSS 订阅", "No RSS feeds yet")}
          hint={tr(
            "添加一个 HTTPS 订阅地址，在一个页面阅读更新。",
            "Add an HTTPS feed URL and read its updates in one place.",
          )}
        />
      ) : (
        <div className="dc-col" style={{ gap: 12, paddingTop: 12 }}>
          {/* Feeds */}
          <div className="dc-row" style={{ padding: "0 16px" }}>
            <span style={{ fontWeight: 600 }} className="dc-grow">{tr("订阅源", "Feeds")}</span>
            <span className="dc-muted" style={{ fontSize: "0.82em" }}>
              {tr(
                "每个订阅最多 ${n} 篇",
                "Up to ${n} items per feed",
              ).replace("${n}", String(maxItemsPerFeed))}
            </span>
          </div>
          <div style={{ display: "flex", gap: 10, overflowX: "auto", padding: "0 16px 4px" }}>
            {subscriptions.map((subscription) => (
              <div
                key={subscription.id}
                className="dc-card"
                style={{ minWidth: 250, maxWidth: 300, flexShrink: 0, display: "flex", alignItems: "center", gap: 4, padding: "8px 4px 8px 12px" }}
              >
                <input
                  type="checkbox"
                  checked={subscription.enabled}
                  aria-label={tr("启用订阅", "Enable feed") + ": " + (subscription.title || subscription.url)}
                  onChange={(e) => void setSubscriptionEnabled(subscription.id, e.target.checked)}
                  style={{ width: 18, height: 18, flexShrink: 0 }}
                />
                <span className="dc-grow dc-col" style={{ gap: 1, minWidth: 0 }}>
                  <span style={{ fontWeight: 600, fontSize: "0.92em", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                    {subscription.title || subscription.url}
                  </span>
                  <span className="dc-muted" style={{ fontSize: "0.78em", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                    {subscription.url}
                  </span>
                </span>
                <button className="dc-icon-btn" aria-label={tr("编辑订阅", "Edit feed")} onClick={() => setEditingSubscription(subscription)}>
                  <IconEdit size={17} />
                </button>
                <button
                  className="dc-icon-btn"
                  aria-label={tr("删除订阅", "Delete feed")}
                  style={{ color: "var(--dc-error)" }}
                  onClick={() => setDeletingSubscription(subscription)}
                >
                  <IconDelete size={17} />
                </button>
              </div>
            ))}
          </div>

          {/* Latest articles */}
          <div className="dc-row" style={{ padding: "0 16px" }}>
            <span style={{ fontWeight: 600 }} className="dc-grow">{tr("最新文章", "Latest articles")}</span>
            <span className="dc-muted" style={{ fontSize: "0.82em" }}>
              {tr("${n} 篇", "${n} items").replace("${n}", String(articles.length))}
            </span>
          </div>
          {articles.length === 0 ? (
            <div style={{ minHeight: 300 }}>
              <EmptyState
                icon={<IconRss size={36} />}
                title={tr("暂无文章", "No articles yet")}
                hint={tr(
                  "点击右上角刷新；也可以检查订阅是否已启用。",
                  "Refresh from the top right, or check that a feed is enabled.",
                )}
              />
              <div className="dc-center">
                <button className="dc-btn dc-btn-filled" disabled={refreshing} onClick={() => void refresh()}>
                  {refreshing ? <Spinner size={16} /> : <IconRefresh size={16} />} {tr("刷新", "Refresh")}
                </button>
              </div>
            </div>
          ) : (
            articles.map((article) => {
              const articleUrl = normalizeRssArticleUrl(article.url);
              const openable = articleUrl.kind === "valid" ? articleUrl.value : null;
              return (
                <div
                  key={article.id}
                  className="dc-card"
                  role={openable ? "button" : undefined}
                  tabIndex={openable ? 0 : undefined}
                  onKeyDown={(e) => {
                    if (openable && e.key === "Enter") navigate(`/blog?url=${encodeURIComponent(openable)}`);
                  }}
                  onClick={() => {
                    if (openable) navigate(`/blog?url=${encodeURIComponent(openable)}`);
                  }}
                  style={{
                    margin: "0 16px", padding: "14px 16px", cursor: openable ? "pointer" : "default",
                    opacity: openable ? 1 : 0.85,
                  }}
                >
                  <div className="dc-row" style={{ alignItems: "flex-start" }}>
                    <span className="dc-grow dc-col" style={{ gap: 6, minWidth: 0 }}>
                      <span style={{
                        fontWeight: 600, overflow: "hidden", display: "-webkit-box",
                        WebkitLineClamp: 3, WebkitBoxOrient: "vertical", wordBreak: "break-word",
                      }}>
                        {article.title || tr("未命名文章", "Untitled article")}
                      </span>
                      <span style={{ color: "var(--dc-primary)", fontSize: "0.8em", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                        {formatArticleMeta(article)}
                      </span>
                    </span>
                    {openable && (
                      <IconArticle size={18} className="dc-muted" aria-label={tr("阅读全文", "Read full article")} />
                    )}
                  </div>
                  {articleUrl.kind === "missing" && (
                    <div style={{ color: "var(--dc-error)", fontSize: "0.82em", marginTop: 8 }}>
                      {tr("文章没有可用链接", "This article has no usable link")}
                    </div>
                  )}
                  {articleUrl.kind === "unsafe" && (
                    <div style={{ color: "var(--dc-error)", fontSize: "0.82em", marginTop: 8 }}>
                      {tr("文章链接不安全或不受支持", "The article link is unsafe or unsupported")}
                    </div>
                  )}
                  {showSummaries && article.summary && (
                    <div
                      className="dc-muted"
                      style={{
                        marginTop: 9, overflow: "hidden", display: "-webkit-box",
                        WebkitLineClamp: 5, WebkitBoxOrient: "vertical", wordBreak: "break-word",
                      }}
                    >
                      {article.summary}
                    </div>
                  )}
                </div>
              );
            })
          )}
        </div>
      )}

      <button className="dc-fab" aria-label={tr("添加 RSS 订阅", "Add RSS feed")} onClick={() => setShowNewSubscription(true)}>
        <IconPlus size={24} />
      </button>

      {showNewSubscription && (
        <SubscriptionEditorDialog
          subscription={null}
          onClose={() => setShowNewSubscription(false)}
          onConfirm={async (title, url) => {
            const ok = await saveSubscription(null, title, url);
            if (ok) {
              setShowNewSubscription(false);
              void refresh();
            }
          }}
        />
      )}
      {editingSubscription && (
        <SubscriptionEditorDialog
          subscription={editingSubscription}
          onClose={() => setEditingSubscription(null)}
          onConfirm={async (title, url) => {
            const ok = await saveSubscription(editingSubscription.id, title, url);
            if (ok) {
              setEditingSubscription(null);
              void refresh();
            }
          }}
        />
      )}

      <ConfirmDialog
        open={deletingSubscription != null}
        title={tr("删除 RSS 订阅？", "Delete RSS feed?")}
        message={deletingSubscription
          ? tr(
              "将删除“${name}”及当前加载的文章。",
              "This removes “${name}” and its loaded articles.",
            ).replace("${name}", deletingSubscription.title || deletingSubscription.url)
          : undefined}
        confirmLabel={tr("删除", "Delete")}
        cancelLabel={tr("取消", "Cancel")}
        danger
        onCancel={() => setDeletingSubscription(null)}
        onConfirm={() => {
          const sub = deletingSubscription;
          setDeletingSubscription(null);
          if (sub) void deleteSubscription(sub.id);
        }}
      />
      <Snackbar message={snack} />
      <PageTutorialOverlay
        pageKey="rss"
        title={tr("RSS 订阅", "RSS")}
        lines={[tr("在顶栏添加订阅源并刷新，即可在一个页面阅读最新文章。", "Add feeds and refresh from the top bar to read the latest articles in one place.")]}
      />
    </div>
  );
}

function hostOf(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return "";
  }
}

function cryptoId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return "rss-" + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

function SubscriptionEditorDialog(props: {
  subscription: RssSubscription | null;
  onClose: () => void;
  onConfirm: (title: string, url: string) => Promise<void>;
}) {
  const [title, setTitle] = useState(props.subscription?.title ?? "");
  const [url, setUrl] = useState(props.subscription?.url ?? "");
  const [saving, setSaving] = useState(false);
  const canSave = url.trim().length > 0 && !saving;

  return (
    <Modal
      open
      onClose={() => { if (!saving) props.onClose(); }}
      title={props.subscription ? tr("编辑 RSS 订阅", "Edit RSS feed") : tr("添加 RSS 订阅", "Add RSS feed")}
      width={480}
    >
      <div className="dc-col" style={{ gap: 12 }}>
        <label className="dc-col" style={{ gap: 6, alignItems: "stretch" }}>
          <span>{tr("名称（可选）", "Name (optional)")}</span>
          <input className="dc-input" value={title} disabled={saving} onChange={(e) => setTitle(e.target.value)} />
        </label>
        <label className="dc-col" style={{ gap: 6, alignItems: "stretch" }}>
          <span>{tr("HTTPS 订阅地址", "HTTPS feed URL")}</span>
          <input
            className="dc-input"
            value={url}
            disabled={saving}
            inputMode="url"
            onChange={(e) => setUrl(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && canSave) void props.onConfirm(title, url).catch(() => undefined);
            }}
          />
          <span className="dc-muted" style={{ fontSize: "0.8em" }}>
            {tr("省略协议时会自动使用 https://", "https:// is added when omitted")}
          </span>
        </label>
      </div>
      <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 16 }}>
        <button className="dc-btn" disabled={saving} onClick={props.onClose}>{tr("取消", "Cancel")}</button>
        <button
          className="dc-btn dc-btn-filled"
          disabled={!canSave}
          onClick={() => {
            setSaving(true);
            void props.onConfirm(title, url).catch(() => undefined).finally(() => setSaving(false));
          }}
        >
          {saving && <Spinner size={15} />}
          {tr("保存", "Save")}
        </button>
      </div>
    </Modal>
  );
}
