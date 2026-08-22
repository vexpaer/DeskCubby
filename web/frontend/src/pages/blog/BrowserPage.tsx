/**
 * 浏览器 (/blog) — pragmatic web port of Android ui/blog/BlogScreen.kt.
 * Web iframes cannot fetch arbitrary sites like a WebView, so the faithful
 * feature set (tabs, address bar, favorites/history records, desktop mode,
 * theme, external open) is kept while page content renders in a sandboxed
 * iframe with an "open externally" fallback for sites that block embedding.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import {
  ArrowLeft as IconBack, ArrowRight as IconForward, Bookmark as IconBookmark,
  BookmarkPlus as IconBookmarkAdd, Check as IconCheck, ExternalLink as IconExternal,
  History as IconHistory, Home as IconHome, Menu as IconMenu,
  Monitor as IconDesktop, Plus as IconPlus, RefreshCw as IconRefresh, Search as IconOpenUrl,
  Search as IconFind,
  X as IconClose,
} from "lucide-react";
import { apiGet, apiSend } from "../../api/client";
import { useSettings } from "../../stores/settings";
import { tr } from "../../i18n/tr";
import { EmptyState, ErrorText, Modal, PageTutorialOverlay, Snackbar, Spinner, useSnackbar } from "../../components/ui";

interface BrowserRecord {
  url: string;
  title: string;
  lastVisitedAt: number;
  visitCount: number;
  favorite: boolean;
}

interface BrowserTab {
  id: number;
  url: string; // canonical; "about:blank" = start page
  title: string;
  draft: string;
  backStack: string[];
  forwardStack: string[];
}

const BROWSER_BLANK_URL = "about:blank";
const MAX_BROWSER_TABS = 8;

function arrayOf<T>(v: unknown): T[] {
  if (Array.isArray(v)) return v as T[];
  if (v && typeof v === "object") {
    const obj = v as Record<string, unknown>;
    for (const key of ["items", "records", "data", "results"]) {
      if (Array.isArray(obj[key])) return obj[key] as T[];
    }
  }
  return [];
}

/** Port of SettingsRepository.normalizeUrl. */
export function normalizeBrowserUrl(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed) return BROWSER_BLANK_URL;
  if (trimmed.includes("://") || trimmed.startsWith("about:")) return trimmed;
  return `https://${trimmed}`;
}

function hostOf(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return "";
  }
}

function isBlank(url: string): boolean {
  return url.toLowerCase() === BROWSER_BLANK_URL;
}

export default function BrowserPage() {
  const [searchParams] = useSearchParams();
  const settingsState = useSettings();
  const settings = settingsState.settings;
  const [snack, showSnack] = useSnackbar();

  const [favorites, setFavorites] = useState<BrowserRecord[]>([]);
  const [history, setHistory] = useState<BrowserRecord[]>([]);
  const [loadError, setLoadError] = useState<unknown>(null);

  const [tabs, setTabs] = useState<BrowserTab[] | null>(null);
  const [currentTabId, setCurrentTabId] = useState<number | null>(null);
  const nextTabIdRef = useRef(1);

  const [tabsMenuOpen, setTabsMenuOpen] = useState(false);
  const [mainMenuOpen, setMainMenuOpen] = useState(false);
  const [recordsDialog, setRecordsDialog] = useState<"history" | "favorites" | null>(null);
  const [findHintOpen, setFindHintOpen] = useState(false);
  const [reloadNonce, setReloadNonce] = useState(0);
  const addressRef = useRef<HTMLInputElement | null>(null);

  // ---- initial tab from ?url= → lastBrowserUrl → browserHomeUrl ----
  useEffect(() => {
    if (tabs != null || !settings) return;
    const paramUrl = searchParams.get("url");
    const initialRaw =
      paramUrl ??
      (settings.lastBrowserUrl && !isBlank(settings.lastBrowserUrl)
        ? settings.lastBrowserUrl
        : settings.browserHomeUrl) ??
      BROWSER_BLANK_URL;
    const initialUrl = normalizeBrowserUrl(initialRaw || settings.browserHomeUrl || BROWSER_BLANK_URL);
    const firstTab: BrowserTab = {
      id: 0,
      url: initialUrl,
      title: hostOf(initialUrl),
      draft: isBlank(initialUrl) ? "" : initialUrl,
      backStack: [],
      forwardStack: [],
    };
    nextTabIdRef.current = 1;
    setTabs([firstTab]);
    setCurrentTabId(firstTab.id);
    if (paramUrl) recordVisit(initialUrl);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settings, tabs == null]);

  const currentTab = useMemo(
    () => tabs?.find((t) => t.id === currentTabId) ?? tabs?.[0] ?? null,
    [tabs, currentTabId],
  );

  const loadRecords = useCallback(async () => {
    try {
      const [fav, hist] = await Promise.all([
        apiGet<unknown>("/api/browser/records?favorites=1"),
        apiGet<unknown>("/api/browser/records?favorites=0"),
      ]);
      setFavorites(arrayOf<BrowserRecord>(fav));
      setHistory(arrayOf<BrowserRecord>(hist));
      setLoadError(null);
    } catch (e) {
      setLoadError(e);
    }
  }, []);

  useEffect(() => {
    void loadRecords();
  }, [loadRecords]);

  const fail = useCallback((e: unknown) => {
    showSnack(tr("操作失败：", "Operation failed: ") + (e instanceof Error ? e.message : String(e)));
  }, [showSnack]);

  /** POST /api/browser/records — visit recording per Android repository.recordVisit. */
  const recordVisit = useCallback((url: string, title = "") => {
    if (!url || isBlank(url)) return;
    void apiSend("/api/browser/records", "POST", { url, title }).catch(() => undefined);
  }, []);

  const updateTab = useCallback((tabId: number, transform: (t: BrowserTab) => BrowserTab) => {
    setTabs((prev) => prev?.map((t) => (t.id === tabId ? transform(t) : t)) ?? prev);
  }, []);

  const commitAddress = useCallback((tabId: number, rawAddress: string) => {
    const normalized = normalizeBrowserUrl(rawAddress);
    updateTab(tabId, (tab) => ({
      ...tab,
      url: normalized,
      title: isBlank(normalized) ? "" : hostOf(normalized),
      draft: isBlank(normalized) ? "" : normalized,
      backStack: isBlank(normalized) ? [] : [...tab.backStack, tab.url].slice(-50),
      forwardStack: [],
    }));
    setReloadNonce((n) => n + 1);
    if (!isBlank(normalized)) {
      recordVisit(normalized);
      if (tabId === (currentTabId ?? tabs?.[0]?.id)) {
        void settingsState.update({ lastBrowserUrl: normalized }).catch(() => undefined);
      }
    }
  }, [currentTabId, recordVisit, settingsState, tabs, updateTab]);

  const addTab = () => {
    if (!tabs || tabs.length >= MAX_BROWSER_TABS) return false;
    const tab: BrowserTab = {
      id: nextTabIdRef.current++,
      url: BROWSER_BLANK_URL,
      title: "",
      draft: "",
      backStack: [],
      forwardStack: [],
    };
    setTabs([...tabs, tab]);
    setCurrentTabId(tab.id);
    return true;
  };

  const closeTab = (tabId: number) => {
    if (!tabs) return;
    if (tabs.length === 1) {
      const blank: BrowserTab = {
        id: nextTabIdRef.current++,
        url: BROWSER_BLANK_URL,
        title: "",
        draft: "",
        backStack: [],
        forwardStack: [],
      };
      setTabs([blank]);
      setCurrentTabId(blank.id);
      return;
    }
    const closingIndex = tabs.findIndex((t) => t.id === tabId);
    const remaining = tabs.filter((t) => t.id !== tabId);
    const nextCurrent =
      currentTabId === tabId
        ? remaining[Math.min(closingIndex, remaining.length - 1)].id
        : (currentTabId ?? remaining[0].id);
    setTabs(remaining);
    setCurrentTabId(nextCurrent);
  };

  const goBack = () => {
    if (!currentTab || currentTab.backStack.length === 0) return;
    const previous = currentTab.backStack[currentTab.backStack.length - 1];
    updateTab(currentTab.id, (tab) => ({
      ...tab,
      url: previous,
      title: isBlank(previous) ? "" : hostOf(previous),
      draft: isBlank(previous) ? "" : previous,
      backStack: tab.backStack.slice(0, -1),
      forwardStack: [...tab.forwardStack, tab.url],
    }));
    setReloadNonce((n) => n + 1);
  };

  const goForward = () => {
    if (!currentTab || currentTab.forwardStack.length === 0) return;
    const next = currentTab.forwardStack[currentTab.forwardStack.length - 1];
    updateTab(currentTab.id, (tab) => ({
      ...tab,
      url: next,
      title: isBlank(next) ? "" : hostOf(next),
      draft: isBlank(next) ? "" : next,
      backStack: [...tab.backStack, tab.url],
      forwardStack: tab.forwardStack.slice(0, -1),
    }));
    setReloadNonce((n) => n + 1);
  };

  const toggleFavorite = async () => {
    if (!currentTab || isBlank(currentTab.url)) return;
    const isFavorite = favorites.some((f) => f.url === currentTab.url);
    try {
      // Dedicated favorite endpoint: POST /api/browser/records only records a
      // visit (VisitBody has no favorite field).
      await apiSend("/api/browser/records/favorite", "POST", {
        url: currentTab.url,
        title: currentTab.title,
        favorite: !isFavorite,
      });
      await loadRecords();
    } catch (e) {
      fail(e);
    }
  };

  const clearHistory = async () => {
    try {
      await apiSend("/api/browser/records?favorite=0", "DELETE");
      await loadRecords();
    } catch (e) {
      fail(e);
    }
  };

  const openExternal = (url: string) => {
    window.open(url, "_blank", "noopener,noreferrer");
  };

  const browserThemeResolved = useMemo<"light" | "dark">(() => {
    if (!settings) return "light";
    if (settings.browserTheme === "DARK") return "dark";
    if (settings.browserTheme === "LIGHT") return "light";
    return window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }, [settings]);

  if (!settings || !tabs || !currentTab) return <Spinner />;

  const isFavorite = !isBlank(currentTab.url) && favorites.some((f) => f.url === currentTab.url);
  const iframeSrc = !isBlank(currentTab.url)
    ? currentTab.url + (settings.browserDesktopMode ? (currentTab.url.includes("?") ? "&" : "?") + "desktop=1" : "")
    : null;

  const mainMenuItems: { label: string; icon?: React.ReactNode; disabled?: boolean; trailing?: React.ReactNode; onClick: () => void }[] = [
    {
      label: tr("打开输入的网址", "Open entered URL"),
      icon: <IconOpenUrl size={17} />,
      onClick: () => { setMainMenuOpen(false); commitAddress(currentTab.id, currentTab.draft); },
    },
    {
      label: tr("主页", "Home"),
      icon: <IconHome size={17} />,
      onClick: () => { setMainMenuOpen(false); commitAddress(currentTab.id, settings.browserHomeUrl); },
    },
    {
      label: tr("后退", "Back"),
      icon: <IconBack size={17} />,
      disabled: currentTab.backStack.length === 0,
      onClick: () => { setMainMenuOpen(false); goBack(); },
    },
    {
      label: tr("前进", "Forward"),
      icon: <IconForward size={17} />,
      disabled: currentTab.forwardStack.length === 0,
      onClick: () => { setMainMenuOpen(false); goForward(); },
    },
    {
      label: tr("刷新", "Refresh"),
      icon: <IconRefresh size={17} />,
      disabled: isBlank(currentTab.url),
      onClick: () => { setMainMenuOpen(false); setReloadNonce((n) => n + 1); },
    },
    {
      label: isFavorite ? tr("取消收藏", "Remove favorite") : tr("收藏当前网页", "Favorite this page"),
      icon: isFavorite ? <IconBookmark size={17} /> : <IconBookmarkAdd size={17} />,
      disabled: isBlank(currentTab.url),
      onClick: () => { setMainMenuOpen(false); void toggleFavorite(); },
    },
    {
      label: tr("页内查找", "Find in page"),
      icon: <IconFind size={17} />,
      disabled: isBlank(currentTab.url),
      onClick: () => { setMainMenuOpen(false); setFindHintOpen(true); },
    },
    {
      label: tr("浏览历史", "History"),
      icon: <IconHistory size={17} />,
      onClick: () => { setMainMenuOpen(false); void loadRecords().then(() => setRecordsDialog("history")); },
    },
    {
      label: tr("收藏夹", "Favorites"),
      icon: <IconBookmark size={17} />,
      onClick: () => { setMainMenuOpen(false); void loadRecords().then(() => setRecordsDialog("favorites")); },
    },
    {
      label: tr("新标签页", "New tab"),
      icon: <IconPlus size={17} />,
      disabled: tabs.length >= MAX_BROWSER_TABS,
      onClick: () => { setMainMenuOpen(false); addTab(); },
    },
    {
      label:
        tr("网页主题：", "Page theme: ") +
        (settings.browserTheme === "SYSTEM"
          ? tr("跟随系统", "System")
          : settings.browserTheme === "LIGHT"
            ? tr("亮色", "Light")
            : tr("暗色", "Dark")),
      icon: <IconRefresh size={17} />,
      onClick: () => {
        setMainMenuOpen(false);
        const next = settings.browserTheme === "SYSTEM" ? "LIGHT" : settings.browserTheme === "LIGHT" ? "DARK" : "SYSTEM";
        void settingsState.update({ browserTheme: next }).catch(fail);
        setReloadNonce((n) => n + 1);
      },
    },
    {
      // Degradation is explicit (desktop mode is best-effort); on/off stays
      // visible through the trailing check mark.
      label: tr(
        "桌面模式（部分站点不支持，可“在新窗口打开”）",
        "Desktop mode (some sites don't support it; try “Open in new window”)",
      ),
      icon: <IconDesktop size={17} />,
      trailing: settings.browserDesktopMode ? <IconCheck size={15} /> : undefined,
      onClick: () => {
        setMainMenuOpen(false);
        void settingsState.update({ browserDesktopMode: !settings.browserDesktopMode }).catch(fail);
        setReloadNonce((n) => n + 1);
      },
    },
    {
      label: tr("在新窗口打开", "Open in new window"),
      icon: <IconExternal size={17} />,
      disabled: isBlank(currentTab.url),
      onClick: () => { setMainMenuOpen(false); openExternal(currentTab.url); },
    },
  ];

  return (
    <div data-theme={browserThemeResolved} style={{ display: "flex", flexDirection: "column", minHeight: "calc(100dvh - var(--dc-bottom-nav-height) - 40px)" }}>
      {/* Address row */}
      <div className="dc-row" style={{ padding: "8px 4px 4px", position: "sticky", top: 0, zIndex: 40, background: "color-mix(in srgb, var(--dc-background) 90%, transparent)", backdropFilter: "blur(10px)" }}>
        <input
          ref={addressRef}
          className="dc-input dc-grow"
          value={currentTab.draft}
          placeholder={tr("输入网址", "Enter a URL")}
          inputMode="url"
          spellCheck={false}
          onChange={(e) => updateTab(currentTab.id, (t) => ({ ...t, draft: e.target.value }))}
          onFocus={(e) => e.currentTarget.select()}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              commitAddress(currentTab.id, (e.target as HTMLInputElement).value);
              addressRef.current?.blur();
            }
          }}
          aria-label={tr("地址栏", "Address bar")}
        />
        {/* Tab count button */}
        <div style={{ position: "relative", flexShrink: 0 }}>
          <button
            className="dc-btn"
            aria-label={tr("标签页", "Tabs")}
            title={tr("标签页", "Tabs")}
            style={{ width: 44, justifyContent: "center", border: "var(--dc-border-width) solid var(--dc-outline)", borderRadius: "calc(var(--dc-radius) * 0.6)" }}
            onClick={() => { setTabsMenuOpen((v) => !v); setMainMenuOpen(false); }}
          >
            {tabs.length}
          </button>
          {tabsMenuOpen && (
            <>
              <div style={{ position: "fixed", inset: 0, zIndex: 290 }} onClick={() => setTabsMenuOpen(false)} />
              <div className="dc-menu" style={{ right: 0, top: 44, position: "absolute", zIndex: 300, minWidth: 260 }}>
                <button
                  disabled={tabs.length >= MAX_BROWSER_TABS}
                  onClick={() => { setTabsMenuOpen(false); addTab(); }}
                  style={{ opacity: tabs.length >= MAX_BROWSER_TABS ? 0.5 : 1 }}
                >
                  <IconPlus size={16} style={{ verticalAlign: "-3px", marginRight: 6 }} />
                  {tabs.length >= MAX_BROWSER_TABS
                    ? tr("最多打开 8 个网页", "Maximum 8 tabs")
                    : tr("新建网页", "New tab")}
                </button>
                {tabs.map((tab, index) => (
                  <div key={tab.id} style={{ display: "flex", alignItems: "center", gap: 4 }}>
                    <button
                      style={{ flex: 1, textAlign: "left" }}
                      onClick={() => { setTabsMenuOpen(false); setCurrentTabId(tab.id); }}
                    >
                      <span className="dc-muted" style={{ marginRight: 6 }}>{index + 1}.</span>
                      {tab.title || tr("网页 ${n}", "Tab ${n}").replace("${n}", String(index + 1))}
                      <span className="dc-muted" style={{ display: "block", fontSize: "0.78em", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                        {tab.url.replace(/^https?:\/\//, "")}
                      </span>
                    </button>
                    {tab.id === currentTab.id && <IconCheck size={16} aria-label={tr("当前网页", "Current tab")} />}
                    <button
                      className="dc-icon-btn"
                      style={{ width: 32, height: 32 }}
                      aria-label={tr("关闭网页 ${n}", "Close tab ${n}").replace("${n}", String(index + 1))}
                      onClick={() => closeTab(tab.id)}
                    >
                      <IconClose size={14} />
                    </button>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
        {/* ☰ menu */}
        <div style={{ position: "relative", flexShrink: 0 }}>
          <button className="dc-icon-btn" aria-label={tr("浏览器菜单", "Browser menu")} onClick={() => { setMainMenuOpen((v) => !v); setTabsMenuOpen(false); }}>
            <IconMenu size={20} />
          </button>
          {mainMenuOpen && (
            <>
              <div style={{ position: "fixed", inset: 0, zIndex: 290 }} onClick={() => setMainMenuOpen(false)} />
              <div className="dc-menu" style={{ right: 0, top: 42, position: "absolute", zIndex: 300, minWidth: 230 }}>
                {mainMenuItems.map((item, i) => (
                  <button
                    key={i}
                    disabled={item.disabled}
                    style={item.disabled ? { opacity: 0.45 } : undefined}
                    onClick={item.onClick}
                  >
                    {item.icon}
                    <span style={{ marginLeft: 8 }}>{item.label}</span>
                    {item.trailing}
                  </button>
                ))}
              </div>
            </>
          )}
        </div>
      </div>

      {/* Loading bar */}
      {!isBlank(currentTab.url) && (
        <LoadBar key={`${currentTab.id}-${reloadNonce}-${iframeSrc ?? ""}`} />
      )}

      {loadError != null && (
        <div className="dc-row" style={{ padding: "0 4px" }}>
          <ErrorText error={loadError} />
          <button className="dc-btn dc-btn-tonal" onClick={() => void loadRecords()}>{tr("重试", "Retry")}</button>
        </div>
      )}

      {/* Content area */}
      <div style={{ flex: 1, borderRadius: "var(--dc-radius)", overflow: "hidden", border: "var(--dc-border-width) solid var(--dc-outline-variant)", background: "var(--dc-surface)" }}>
        {isBlank(currentTab.url) ? (
          <StartPage
            favorites={favorites}
            history={history}
            onOpen={(url) => commitAddress(currentTab.id, url)}
          />
        ) : (
          <div style={{ display: "flex", flexDirection: "column", height: "100%" }}>
            <div
              className="dc-row dc-muted"
              style={{ gap: 8, padding: "6px 10px", fontSize: "0.8em", background: "color-mix(in srgb, var(--dc-primary) 7%, transparent)" }}
            >
              <span className="dc-grow">
                {tr(
                  "部分网站禁止被嵌入显示；若页面空白，请一键外部打开。",
                  "Some sites block embedding; if the page stays blank, open it externally.",
                )}
              </span>
              <button className="dc-btn dc-btn-tonal" style={{ padding: "4px 10px", fontSize: "0.9em" }} onClick={() => openExternal(currentTab.url)}>
                <IconExternal size={14} /> {tr("一键外部打开", "Open externally")}
              </button>
            </div>
            <iframe
              key={`${currentTab.id}-${reloadNonce}`}
              src={iframeSrc ?? undefined}
              title={currentTab.title || tr("网页", "Web page")}
              sandbox="allow-scripts allow-same-origin allow-forms allow-popups"
              onLoad={() => recordVisit(currentTab.url, currentTab.title)}
              style={{ width: "100%", flex: 1, minHeight: 480, border: "none", background: "#ffffff" }}
            />
          </div>
        )}
      </div>

      {/* Records dialog */}
      <Modal
        open={recordsDialog != null}
        onClose={() => setRecordsDialog(null)}
        title={recordsDialog === "history" ? tr("浏览历史", "History") : tr("收藏夹", "Favorites")}
        width={520}
      >
        {recordsDialog === "history" && history.length > 0 && (
          <div className="dc-row" style={{ justifyContent: "flex-end", marginBottom: 6 }}>
            <button className="dc-btn" style={{ color: "var(--dc-error)" }} onClick={() => void clearHistory()}>
              {tr("清空", "Clear all")}
            </button>
          </div>
        )}
        <div className="dc-col" style={{ gap: 2, maxHeight: 420, overflowY: "auto" }}>
          {(recordsDialog === "history" ? history : favorites).length === 0 && (
            <div className="dc-muted" style={{ padding: 12 }}>
              {recordsDialog === "history" ? tr("暂无记录", "No records yet") : tr("暂无书签", "No bookmarks yet")}
            </div>
          )}
          {(recordsDialog === "history" ? history : favorites).map((record) => (
            <button
              key={record.url}
              className="dc-btn"
              style={{ width: "100%", justifyContent: "flex-start", alignItems: "flex-start" }}
              onClick={() => { setRecordsDialog(null); commitAddress(currentTab.id, record.url); }}
            >
              <span className="dc-grow dc-col" style={{ alignItems: "flex-start", gap: 0, textAlign: "left", minWidth: 0 }}>
                <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", maxWidth: "100%" }}>
                  {record.title || record.url}
                </span>
                <span className="dc-muted" style={{ fontSize: "0.78em", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", maxWidth: "100%" }}>
                  {record.url}
                </span>
              </span>
            </button>
          ))}
        </div>
        <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 12 }}>
          <button className="dc-btn dc-btn-filled" onClick={() => setRecordsDialog(null)}>{tr("关闭", "Close")}</button>
        </div>
      </Modal>

      {/* Find-in-page hint dialog (iframes cannot be searched cross-origin) */}
      <Modal open={findHintOpen} onClose={() => setFindHintOpen(false)} title={tr("网页内查找", "Find in page")} width={440}>
        <div className="dc-muted">
          {tr(
            "当前网页在内嵌视图中显示，浏览器安全限制无法跨站高亮关键词。如需页内查找，请先「在新窗口打开」，然后按 Ctrl+F（macOS 为 ⌘F）。",
            "The page renders inside an embedded frame, so cross-site highlighting is blocked by browser security. Use \"Open in new window\" first, then press Ctrl+F (⌘F on macOS).",
          )}
        </div>
        <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 14 }}>
          <button
            className="dc-btn"
            disabled={isBlank(currentTab.url)}
            onClick={() => { setFindHintOpen(false); openExternal(currentTab.url); }}
          >
            <IconExternal size={16} /> {tr("在新窗口打开", "Open in new window")}
          </button>
          <button className="dc-btn dc-btn-filled" onClick={() => setFindHintOpen(false)}>{tr("完成", "Done")}</button>
        </div>
      </Modal>

      <Snackbar message={snack} />
      <PageTutorialOverlay
        pageKey="blog"
        title={tr("浏览器", "Browser")}
        lines={[tr("输入网址即可浏览；菜单可切换桌面模式、查看历史与收藏。", "Type a URL to browse; the menu toggles desktop mode and shows history and favorites.")]}
      />
    </div>
  );
}

/** Indeterminate loading bar shown while the iframe document loads. */
function LoadBar() {
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    const t = window.setTimeout(() => setLoading(false), 6000);
    return () => window.clearTimeout(t);
  }, []);
  if (!loading) return null;
  return (
    <div aria-hidden style={{ position: "relative", height: 3, overflow: "hidden", margin: "0 2px" }}>
      <style>{`@keyframes dc-loadbar { 0% { left: -35%; right: 100%; } 60% { left: 100%; right: -90%; } 100% { left: 100%; right: -90%; } }`}</style>
      <div style={{
        position: "absolute", top: 0, bottom: 0, background: "var(--dc-primary)",
        animation: "dc-loadbar 1.4s ease-in-out infinite",
      }} />
    </div>
  );
}

/** Blank start page = bookmarks grid + recent history (Android 空白起始页). */
function StartPage(props: {
  favorites: BrowserRecord[];
  history: BrowserRecord[];
  onOpen: (url: string) => void;
}) {
  return (
    <div style={{ padding: 16, overflowY: "auto", maxHeight: "70vh" }}>
      <div style={{ fontWeight: 600, margin: "4px 8px 10px" }}>{tr("书签", "Bookmarks")}</div>
      {props.favorites.length === 0 ? (
        <EmptyState
          icon={<IconBookmark size={36} />}
          title={tr("暂无书签", "No bookmarks yet")}
          hint={tr(
            "收藏常用网页后，可以从这里快速打开。",
            "Favorite useful pages to open them quickly from here.",
          )}
        />
      ) : (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(150px, 1fr))", gap: 10 }}>
          {props.favorites.map((favorite) => (
            <button
              key={favorite.url}
              className="dc-card"
              onClick={() => props.onOpen(favorite.url)}
              style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 8, padding: 14, cursor: "pointer" }}
            >
              <span
                aria-hidden
                style={{
                  width: 44, height: 44, borderRadius: "50%",
                  background: "var(--dc-secondary-container)", color: "var(--dc-on-secondary-container)",
                  display: "flex", alignItems: "center", justifyContent: "center",
                  fontWeight: 700, fontSize: 19,
                }}
              >
                {(favorite.title || hostOf(favorite.url) || "?").trim().charAt(0).toUpperCase()}
              </span>
              <span style={{ fontWeight: 600, fontSize: "0.88em", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", maxWidth: "100%" }}>
                {favorite.title || favorite.url}
              </span>
              <span className="dc-muted" style={{ fontSize: "0.75em", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", maxWidth: "100%" }}>
                {hostOf(favorite.url)}
              </span>
            </button>
          ))}
        </div>
      )}
      {props.history.length > 0 && (
        <>
          <div style={{ fontWeight: 600, margin: "18px 8px 10px" }}>{tr("最近访问", "Recent history")}</div>
          <div className="dc-col" style={{ gap: 2 }}>
            {props.history.slice(0, 8).map((record) => (
              <button
                key={record.url}
                className="dc-btn"
                style={{ width: "100%", justifyContent: "flex-start" }}
                onClick={() => props.onOpen(record.url)}
              >
                <IconHistory size={15} />
                <span className="dc-grow" style={{ textAlign: "left", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  {record.title || record.url}
                </span>
                <span className="dc-muted" style={{ fontSize: "0.78em" }}>{hostOf(record.url)}</span>
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
