/**
 * DeskCubby Web shell: auth gate → settings → navigation (bottom bar on phones,
 * left rail on desktop) → routed pages from pages/registry.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import {
  Home as IconHome, LayoutDashboard as IconDesk, Book as IconBook, FileText as IconNotes,
  Globe as IconLanguage, Zap as IconBolt, BookOpen as IconPoetry, Settings as IconSettings,
  CalendarDays as IconCalendar, CalendarClock as IconEvent, Star as IconStar, PenLine as IconWrite,
  Sparkles as IconSparkle, Rows3 as IconDay, Rss as IconRss, Brain as IconAi,
  LayoutGrid as IconApps, Lock as IconLock, Gamepad2 as IconGame, BookMarked as IconReader,
  Clock as IconUsage, HeartPulse as IconSteps, BarChart3 as IconStatistics,
  Blocks as IconWidgets, Check, Grip, SlidersHorizontal,
} from "lucide-react";
import { apiGet } from "./api/client";
import { argbToCss, type AppSettings, type NavItemConfig } from "./api/types";
import { useSettings } from "./stores/settings";
import { buildRoutes } from "./pages/registry";
import { tr } from "./i18n/tr";
import { PageTutorialOverlay, Snackbar, TopBar, TutorialProvider } from "./components/ui";
import LoginPage from "./pages/login/LoginPage";

const ICONS: Record<string, React.ComponentType<{ size?: number | string }>> = {
  home: IconHome,
  desk: IconDesk,
  book: IconBook,
  notes: IconNotes,
  language: IconLanguage,
  bolt: IconBolt,
  poetry: IconPoetry,
  settings: IconSettings,
  calendar: IconCalendar,
  event: IconEvent,
  star: IconStar,
  write: IconWrite,
  sparkle: IconSparkle,
  day: IconDay,
  rss: IconRss,
  ai: IconAi,
  apps: IconApps,
  lock: IconLock,
  game: IconGame,
  reader: IconReader,
  usage: IconUsage,
  steps: IconSteps,
  statistics: IconStatistics,
  widgets: IconWidgets,
};

export function iconFor(key: string): React.ComponentType<{ size?: number | string }> {
  return ICONS[key] ?? IconPoetry;
}

interface AuthStatus {
  enabled: boolean;
  authenticated: boolean;
}

const DEFAULT_LABELS: Record<string, { zh: string; en: string }> = {
  HOME: { zh: "首页", en: "Home" },
  DESK: { zh: "桌面", en: "Desk" },
  DIARY: { zh: "日记", en: "Diary" },
  NOTES: { zh: "笔记", en: "Notes" },
  BLOG: { zh: "浏览器", en: "Browser" },
  THOUGHT: { zh: "小巧思", en: "Thoughts" },
  DATE: { zh: "日期记录", en: "Dates" },
  POETRY: { zh: "诗词本", en: "Poetry book" },
  RSS: { zh: "RSS 订阅", en: "RSS" },
  AI_CHAT: { zh: "AI 聊天", en: "AI chat" },
  VAULT: { zh: "收藏夹", en: "Vault" },
  READER: { zh: "阅读", en: "Reader" },
  GAMES: { zh: "小游戏", en: "Games" },
  STATISTICS: { zh: "统计", en: "Statistics" },
  USAGE: { zh: "手机使用时间", en: "Screen time" },
  STEPS: { zh: "健康", en: "Health" },
  WIDGETS: { zh: "小卡片", en: "Widgets" },
  MORE: { zh: "导航", en: "More" },
  SETTINGS: { zh: "设置", en: "Settings" },
};

const DEFAULT_DESC: Record<string, { zh: string; en: string }> = {
  DESK: { zh: "把今天留下的痕迹摊开在你的数字桌面上", en: "Spread today's traces across your personal desk" },
  NOTES: { zh: "按文件夹管理 Obsidian 兼容 Markdown 笔记", en: "Manage Obsidian-compatible Markdown notes by folder" },
  BLOG: { zh: "在应用内浏览网页", en: "Browse the web in the app" },
  DATE: { zh: "追踪纪念日与目标日期", en: "Track occasions and target dates" },
  POETRY: { zh: "收藏喜欢的诗词", en: "Keep your favorite poems" },
  RSS: { zh: "阅读订阅源的最新文章", en: "Read the latest from your feeds" },
  AI_CHAT: { zh: "选择本机记录作为上下文并与模型分析", en: "Analyze selected local records with AI" },
  VAULT: { zh: "密码保护的私密收藏", en: "Password-protected private notes" },
  READER: { zh: "导入并阅读 TXT/PDF 小说", en: "Import and read TXT/PDF books" },
  GAMES: { zh: "2048、贪吃蛇、俄罗斯方块、扫雷与蜘蛛纸牌", en: "2048, Snake, Tetris, Minesweeper, and Spider Solitaire" },
  STATISTICS: { zh: "汇总日记、使用时间、健康、阅读与小游戏数据", en: "Explore diary, screen-time, health, reading, and game insights" },
  WIDGETS: { zh: "设计并添加可缩放的桌面小卡片", en: "Design reusable home-screen widgets" },
};

function labelFor(s: AppSettings, id: string): string {
  const item = s.navItems.find((n) => n.id === id);
  const fallback = DEFAULT_LABELS[id] ?? { zh: id, en: id };
  const label = item?.label || fallback.zh;
  // Built-in labels use the five-language table. A user-customized label stays
  // literal in every language, matching Android's isBuiltInLabel behavior.
  const legacyDefault = (id === "BLOG" && label === "博客") || (id === "THOUGHT" && label === "闪思");
  return label === fallback.zh || legacyDefault ? tr(fallback.zh, fallback.en) : label;
}

export function routeFor(id: string): string {
  return (
    {
      HOME: "/home", DESK: "/desk", DIARY: "/diary", NOTES: "/notes", BLOG: "/blog",
      THOUGHT: "/thought", DATE: "/date_records", POETRY: "/poetry_book", RSS: "/rss",
      AI_CHAT: "/ai_chat", VAULT: "/vault", READER: "/reader", GAMES: "/games",
      STATISTICS: "/statistics", USAGE: "/usage_statistics", STEPS: "/step_statistics",
      WIDGETS: "/desktop_widgets", MORE: "/more", SETTINGS: "/settings",
    } as Record<string, string>
  )[id] ?? "/" + id.toLowerCase();
}

const APP_ROUTES = buildRoutes();
const LANGUAGE_SELECTED_KEY = "deskcubby.language-selected.v1";

function languageWasSelected(): boolean {
  try { return window.localStorage.getItem(LANGUAGE_SELECTED_KEY) === "1"; }
  catch { return false; }
}

function rememberLanguageSelection(): void {
  try { window.localStorage.setItem(LANGUAGE_SELECTED_KEY, "1"); }
  catch { /* an in-memory flag still lets this session continue */ }
}

function FirstLaunchLanguageScreen(props: {
  selected: AppSettings["appLanguage"];
  onSelect: (language: AppSettings["appLanguage"]) => Promise<void>;
}) {
  const [busy, setBusy] = useState<AppSettings["appLanguage"] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const languages: { value: AppSettings["appLanguage"]; label: string; detail: string }[] = [
    { value: "CHINESE", label: "简体中文", detail: "继续使用简体中文" },
    { value: "TRADITIONAL_CHINESE", label: "繁體中文", detail: "繼續使用繁體中文" },
    { value: "ENGLISH", label: "English", detail: "Continue in English" },
    { value: "KOREAN", label: "한국어", detail: "한국어로 계속" },
    { value: "JAPANESE", label: "日本語", detail: "日本語で続行" },
  ];
  return (
    <main className="dc-center" style={{ minHeight: "100dvh", padding: 20 }}>
      <section className="dc-surface dc-col" style={{ width: "min(520px, 96vw)", padding: 26, gap: 14 }}>
        <img src="/icons/icon-192.png" alt="" width={72} height={72} style={{ alignSelf: "center", objectFit: "contain" }} />
        <div style={{ textAlign: "center" }}>
          <div className="dc-title">选择语言 · Choose language</div>
          <div className="dc-muted" style={{ marginTop: 5 }}>首次使用请选择软件语言</div>
        </div>
        <div className="dc-col" style={{ gap: 8 }}>
          {languages.map((language) => (
            <button
              key={language.value}
              className={`dc-btn ${props.selected === language.value ? "dc-btn-tonal" : "dc-card"}`}
              disabled={busy !== null}
              style={{ width: "100%", padding: "12px 14px", justifyContent: "space-between", textAlign: "left" }}
              onClick={() => {
                setBusy(language.value);
                setError(null);
                void props.onSelect(language.value).catch((reason: unknown) => {
                  setError(reason instanceof Error ? reason.message : String(reason));
                  setBusy(null);
                });
              }}
            >
              <span style={{ fontWeight: 650 }}>{language.label}</span>
              <span className="dc-muted" style={{ fontSize: "0.86em" }}>
                {busy === language.value ? "…" : language.detail}
              </span>
            </button>
          ))}
        </div>
        {error && <div role="alert" style={{ color: "var(--dc-error)", fontSize: "0.9em" }}>{error}</div>}
      </section>
    </main>
  );
}

function routeMatches(pathname: string, route: string): boolean {
  return pathname === route || pathname.startsWith(`${route}/`);
}

function descriptionFor(item: NavItemConfig): string {
  const fallback = DEFAULT_DESC[item.id];
  if (!fallback) return item.moreDescription ?? "";
  if (!item.moreDescription || item.moreDescription === fallback.zh) return tr(fallback.zh, fallback.en);
  return item.moreDescription;
}

function normalizedMoreOrder(settings: AppSettings): string[] {
  const eligible = settings.navItems
    .map((item) => item.id)
    .filter((id) => id !== "HOME" && id !== "MORE" && id !== "SETTINGS");
  return [...(settings.morePageOrder ?? []), ...eligible]
    .filter((id, index, values) => eligible.includes(id) && values.indexOf(id) === index);
}

/** Reorder visible cards without disturbing the slots of cards currently omitted from More. */
function mergeVisibleMoreOrder(settings: AppSettings, visibleOrder: string[]): string[] {
  const normalized = normalizedMoreOrder(settings);
  const visibleIds = new Set(settings.navItems.filter((item) => item.showInMore).map((item) => item.id));
  const replacements = [
    ...visibleOrder.filter((id, index) => visibleIds.has(id) && visibleOrder.indexOf(id) === index),
    ...normalized.filter((id) => visibleIds.has(id) && !visibleOrder.includes(id)),
  ];
  let replacementIndex = 0;
  return normalized.map((id) => visibleIds.has(id) ? replacements[replacementIndex++] : id);
}

function MoreHub({ settings }: { settings: AppSettings }) {
  const navigate = useNavigate();
  const updateSettings = useSettings((state) => state.update);
  const persistedItems = useMemo(() => {
    const byId = new Map(settings.navItems.map((item) => [item.id, item]));
    return normalizedMoreOrder(settings).map((id) => byId.get(id)).filter((item): item is NavItemConfig => !!item?.showInMore);
  }, [settings]);
  const [items, setItems] = useState(persistedItems);
  const [editMode, setEditMode] = useState(false);
  const [savingOrder, setSavingOrder] = useState(false);
  const [draggingId, setDraggingId] = useState<string | null>(null);
  const [dropId, setDropId] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const longPress = useRef<{ timer: number; x: number; y: number } | null>(null);
  const suppressClickUntil = useRef(0);

  useEffect(() => {
    if (!savingOrder) setItems(persistedItems);
  }, [persistedItems, savingOrder]);

  const cancelLongPress = () => {
    if (longPress.current) window.clearTimeout(longPress.current.timer);
    longPress.current = null;
  };

  const enterEditMode = () => {
    suppressClickUntil.current = Date.now() + 700;
    setEditMode(true);
  };

  const persistVisibleOrder = async (next: NavItemConfig[]) => {
    if (savingOrder) return;
    const before = items;
    setItems(next);
    setSavingOrder(true);
    try {
      await updateSettings({ morePageOrder: mergeVisibleMoreOrder(settings, next.map((item) => item.id)) });
    } catch {
      setItems(before);
      setMessage(tr(
        "导航页顺序保存失败，已恢复上次保存的顺序。",
        "Could not save the navigation-page order. The last saved order was restored.",
      ));
    } finally {
      setSavingOrder(false);
    }
  };

  const moveItem = (sourceId: string, targetId: string) => {
    if (savingOrder || sourceId === targetId) return;
    const from = items.findIndex((item) => item.id === sourceId);
    const to = items.findIndex((item) => item.id === targetId);
    if (from < 0 || to < 0 || from === to) return;
    const next = [...items];
    const [moved] = next.splice(from, 1);
    next.splice(to, 0, moved);
    void persistVisibleOrder(next);
  };

  const moveBy = (id: string, delta: number) => {
    const index = items.findIndex((item) => item.id === id);
    const target = index + delta;
    if (index < 0 || target < 0 || target >= items.length) return;
    moveItem(id, items[target].id);
  };

  return (
    <div>
      <TopBar
        title={tr("导航", "More")}
        subtitle={tr("快捷入口", "Quick access")}
        actions={editMode ? (
          <button className="dc-icon-btn" aria-label={tr("完成布局更改", "Finish layout editing")}
            onClick={() => { setEditMode(false); setDraggingId(null); setDropId(null); }}>
            <Check size={21} />
          </button>
        ) : (
          <button className="dc-icon-btn" aria-label={tr("设置导航页", "Navigation page settings")}
            onClick={() => navigate("/settings?section=morepage")}>
            <SlidersHorizontal size={20} />
          </button>
        )}
      />
      {editMode && (
        <div className="dc-row" style={{
          padding: "9px 12px", marginBottom: 10, borderRadius: 12,
          background: "var(--dc-secondary-container)", color: "var(--dc-on-secondary-container)",
          fontSize: "0.86em",
        }}>
          <Grip size={18} />
          <span>{tr(
            "拖动模块右上角的手柄调整顺序，点右上角对勾完成。",
            "Drag each module's handle to reorder; use the check mark when done.",
          )}</span>
        </div>
      )}
      {items.length === 0 && (
        <div className="dc-card dc-col dc-center" style={{ padding: 30, textAlign: "center" }}>
          <strong>{tr("还没有收纳的页面", "No pages here yet")}</strong>
          <span className="dc-muted">{tr(
            "在导航页设置中选择要放到这里的页面。",
            "Choose which pages appear here in navigation page settings.",
          )}</span>
          <button className="dc-btn dc-btn-tonal" onClick={() => navigate("/settings?section=morepage")}>
            {tr("设置导航页", "Navigation page settings")}
          </button>
        </div>
      )}
      <div style={{ columns: settings.morePageColumns, columnGap: 12 }}>
        {items.map((item, index) => {
          const Icon = iconFor(item.iconKey);
          return (
            <div
              key={item.id}
              data-more-id={item.id}
              role={editMode ? undefined : "button"}
              tabIndex={editMode ? -1 : 0}
              aria-label={labelFor(settings, item.id)}
              onContextMenu={(event) => { event.preventDefault(); enterEditMode(); }}
              onPointerDown={(event) => {
                if (editMode || event.button !== 0) return;
                cancelLongPress();
                const { clientX: x, clientY: y } = event;
                longPress.current = {
                  x, y,
                  timer: window.setTimeout(() => { longPress.current = null; enterEditMode(); }, 520),
                };
              }}
              onPointerMove={(event) => {
                const press = longPress.current;
                if (press && Math.hypot(event.clientX - press.x, event.clientY - press.y) > 8) cancelLongPress();
              }}
              onPointerUp={cancelLongPress}
              onPointerCancel={cancelLongPress}
              onPointerLeave={cancelLongPress}
              onClick={() => {
                if (!editMode && Date.now() >= suppressClickUntil.current) navigate(routeFor(item.id));
              }}
              onKeyDown={(event) => {
                if (!editMode && (event.key === "Enter" || event.key === " ")) {
                  event.preventDefault();
                  navigate(routeFor(item.id));
                }
              }}
              className="dc-card"
              style={{
                display: "flex", width: "100%", minHeight: 126, textAlign: "left", border: "none",
                cursor: editMode ? "default" : "pointer", position: "relative",
                flexDirection: "column", alignItems: "stretch", gap: 12,
                padding: 16, marginBottom: 12, breakInside: "avoid",
                background: item.moreCardColorArgb != null ? argbToCss(item.moreCardColorArgb) : "var(--dc-surface-container)",
                outline: dropId === item.id && draggingId !== item.id ? "2px solid var(--dc-primary)" : undefined,
                opacity: draggingId === item.id ? 0.65 : 1,
              }}
            >
              <span className="dc-row" style={{ justifyContent: "space-between" }}>
              <span style={{
                width: 44, height: 44, borderRadius: "50%", display: "flex", alignItems: "center", justifyContent: "center",
                background: item.moreButtonColorArgb != null ? argbToCss(item.moreButtonColorArgb) :
                  index % 3 === 1 ? "var(--dc-secondary-container)" : index % 3 === 2 ? "var(--dc-tertiary-container)" : "var(--dc-primary-container)",
                color: index % 3 === 1 ? "var(--dc-secondary)" : index % 3 === 2 ? "var(--dc-tertiary)" : "var(--dc-primary)",
                flexShrink: 0,
              }}>
                <Icon size={21} />
              </span>
              {editMode && (
                <button
                  type="button"
                  className="dc-icon-btn"
                  aria-label={tr(`拖动${labelFor(settings, item.id)}排序`, `Reorder ${labelFor(settings, item.id)}`)}
                  aria-describedby={`more-order-${item.id}`}
                  onClick={(event) => event.stopPropagation()}
                  onKeyDown={(event) => {
                    if (event.key === "ArrowUp" || event.key === "ArrowLeft") {
                      event.preventDefault(); event.stopPropagation(); moveBy(item.id, -1);
                    } else if (event.key === "ArrowDown" || event.key === "ArrowRight") {
                      event.preventDefault(); event.stopPropagation(); moveBy(item.id, 1);
                    }
                  }}
                  onPointerDown={(event) => {
                    if (savingOrder || items.length < 2) return;
                    event.stopPropagation();
                    event.currentTarget.setPointerCapture(event.pointerId);
                    setDraggingId(item.id); setDropId(item.id);
                  }}
                  onPointerMove={(event) => {
                    if (draggingId !== item.id) return;
                    const target = document.elementFromPoint(event.clientX, event.clientY)?.closest<HTMLElement>("[data-more-id]");
                    if (target?.dataset.moreId) setDropId(target.dataset.moreId);
                  }}
                  onPointerUp={(event) => {
                    event.stopPropagation();
                    const targetId = dropId;
                    setDraggingId(null); setDropId(null);
                    if (targetId) moveItem(item.id, targetId);
                  }}
                  onPointerCancel={(event) => {
                    event.stopPropagation(); setDraggingId(null); setDropId(null);
                  }}
                  style={{
                    width: 38, height: 38, borderRadius: "50%", display: "inline-flex",
                    alignItems: "center", justifyContent: "center", touchAction: "none",
                    color: "var(--dc-on-surface-variant)", cursor: savingOrder ? "wait" : "grab",
                  }}
                >
                  <Grip size={20} />
                </button>
              )}
              </span>
              <span className="dc-grow" style={{ minWidth: 0 }}>
                <div style={{ fontWeight: 650, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  {labelFor(settings, item.id)}
                </div>
                {settings.morePageShowDescriptions && (
                  <div className="dc-muted" style={{ fontSize: "0.82em", marginTop: 4, display: "-webkit-box", WebkitLineClamp: 4, WebkitBoxOrient: "vertical", overflow: "hidden" }}>
                    {descriptionFor(item)}
                  </div>
                )}
              </span>
              <span id={`more-order-${item.id}`} className="sr-only">
                {tr(`第 ${index + 1} 项，共 ${items.length} 项`, `${index + 1} of ${items.length}`)}
              </span>
            </div>
          );
        })}
      </div>
      <Snackbar message={message} />
      <PageTutorialOverlay pageKey="more" title={tr("导航页", "Navigation hub")} lines={[tr("这里收纳了未固定到底部导航的页面。", "Collected pages that are not pinned to the bottom bar.")]} />
    </div>
  );
}

export default function App() {
  const [auth, setAuth] = useState<AuthStatus | null>(null);
  const [authError, setAuthError] = useState<string | null>(null);
  const [settingsError, setSettingsError] = useState<string | null>(null);
  const [languageSelected, setLanguageSelected] = useState(languageWasSelected);
  const settingsState = useSettings();
  const location = useLocation();
  const navigate = useNavigate();

  const loadAuth = useCallback(() => {
    setAuthError(null);
    apiGet<AuthStatus>("/api/auth/status")
      .then(setAuth)
      .catch((reason: unknown) => {
        setAuth(null);
        setAuthError(reason instanceof Error ? reason.message : String(reason));
      });
  }, []);

  useEffect(() => { loadAuth(); }, [loadAuth]);

  useEffect(() => {
    if (auth?.authenticated) {
      setSettingsError(null);
      void settingsState.load().catch((reason: unknown) => {
        setSettingsError(reason instanceof Error ? reason.message : String(reason));
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [auth?.authenticated]);

  const settings = settingsState.settings;

  useEffect(() => {
    if (!settings) return;
    if (location.pathname === "/" && routeFor(settings.defaultPage ?? "HOME") !== "/") {
      navigate(routeFor(settings.defaultPage ?? "HOME"), { replace: true });
    }
  }, [settings, location.pathname, navigate]);

  const bottomItems = useMemo(() => {
    if (!settings) return [];
    // Android renders ALL visible tabs in the bar — no cap here either; the
    // buttons flex/shrink and the bar scrolls horizontally only if needed.
    const ids = settings.navItems.filter((n) => n.visible).map((n) => n.id).filter((id) => id !== "SETTINGS");
    const hasMore = settings.navItems.some((n) => !n.visible && n.showInMore);
    if (hasMore && !ids.includes("MORE")) ids.push("MORE");
    ids.push("SETTINGS");
    return ids;
  }, [settings]);

  if (!auth) {
    return (
      <div className="dc-center dc-col" style={{ height: "100dvh", padding: 20 }}>
        {authError ? (
          <>
            <div role="alert" style={{ color: "var(--dc-error)", textAlign: "center" }}>{tr("无法连接 DeskCubby 服务", "Could not connect to DeskCubby")}</div>
            <button className="dc-btn dc-btn-tonal" onClick={loadAuth}>{tr("重试", "Retry")}</button>
          </>
        ) : <span className="dc-muted">DeskCubby…</span>}
      </div>
    );
  }
  if (!auth.authenticated) {
    return location.pathname === "/login" ? <LoginPage /> : <Navigate to="/login" replace />;
  }
  if (location.pathname === "/login") {
    return <Navigate to="/" replace />;
  }
  if (!settings) {
    return (
      <div className="dc-center dc-col" style={{ height: "100dvh", padding: 20 }}>
        {settingsError ? (
          <>
            <div role="alert" style={{ color: "var(--dc-error)", textAlign: "center" }}>{tr("设置加载失败", "Could not load settings")}</div>
            <button className="dc-btn dc-btn-tonal" onClick={() => {
              setSettingsError(null);
              void settingsState.load().catch((reason: unknown) => setSettingsError(reason instanceof Error ? reason.message : String(reason)));
            }}>{tr("重试", "Retry")}</button>
          </>
        ) : <span className="dc-muted">DeskCubby…</span>}
      </div>
    );
  }

  const s = settings;
  const bgImage = s.backgroundImageUri
    ? `/api/settings/background-image?ts=${encodeURIComponent(s.backgroundImageUri)}`
    : null;

  const ackTutorial = (page: string) => {
    const next = Array.from(new Set([...(s.tutorialAcknowledgedPages ?? []), page]));
    void settingsState.update({ tutorialAcknowledgedPages: next });
  };

  if (!languageSelected) {
    return (
      <FirstLaunchLanguageScreen
        selected={s.appLanguage}
        onSelect={async (language) => {
          if (language !== useSettings.getState().settings?.appLanguage) {
            await settingsState.update({ appLanguage: language });
          }
          rememberLanguageSelection();
          setLanguageSelected(true);
        }}
      />
    );
  }

  const currentItem = [...s.navItems]
    .sort((a, b) => routeFor(b.id).length - routeFor(a.id).length)
    .find((item) => routeMatches(location.pathname, routeFor(item.id)));
  const currentIsCollected = !!currentItem && !currentItem.visible && currentItem.showInMore;
  // Resolve one navigation selection once.  Per-button pathname predicates can
  // accidentally leave two painted items when a collected route and More both
  // match, and stale pointer/focus paint can make that indistinguishable from
  // selection.  A single ID plus aria-current makes the state unambiguous.
  const activeNavId = location.pathname === "/more"
    ? "MORE"
    : currentIsCollected && bottomItems.includes("MORE")
      ? "MORE"
      : currentItem?.id ?? null;
  const isSettingsSubpage = location.pathname === "/settings" && new URLSearchParams(location.search).has("section");
  const mainRoutePaths = new Set(s.navItems.map((item) => routeFor(item.id)).concat("/more"));
  const showNavigation = mainRoutePaths.has(location.pathname) && !isSettingsSubpage;

  return (
    <TutorialProvider enabled={s.tutorialModeEnabled} acknowledged={s.tutorialAcknowledgedPages ?? []} ack={ackTutorial}>
      <div style={{ minHeight: "100%", position: "relative", isolation: "isolate" }}>
        {bgImage && (
          <div aria-hidden style={{
            position: "fixed", inset: s.backgroundImageBlurDp > 0 ? -Math.ceil(s.backgroundImageBlurDp * 2) : 0, zIndex: 0,
            backgroundImage: `url(${bgImage})`, backgroundSize: "cover", backgroundPosition: "center",
            opacity: s.backgroundImageOpacity,
            filter: s.backgroundImageBlurDp > 0 ? `blur(${s.backgroundImageBlurDp}px)` : undefined,
          }} />
        )}
        {/* Desktop left rail */}
        {showNavigation && <nav aria-label={tr("主导航", "Main navigation")} className="glass-bar rail-target" style={{
          display: "none",
          position: "fixed", left: 0, top: 0, bottom: 0, width: 88, zIndex: 50,
          flexDirection: "column", alignItems: "center", padding: "12px 4px", gap: 4,
          overflowY: "auto",
        }}>
          {bottomItems.map((id) => {
            const Icon = iconFor(s.navItems.find((n) => n.id === id)?.iconKey ?? "");
            const active = id === activeNavId;
            return (
              <button key={id}
                className={`dc-icon-btn dc-rail-nav-item${active ? " is-active" : ""}`}
                aria-current={active ? "page" : undefined}
                style={{
                width: 64, height: 56, borderRadius: 16, flexDirection: "column", gap: 2,
                color: active ? "var(--dc-on-secondary-container)" : "var(--dc-on-surface)",
              }} onClick={() => navigate(routeFor(id))} title={labelFor(s, id)}>
                <Icon size={22} />
                {s.bottomNavShowLabels && <span style={{ fontSize: "0.62em" }}>{labelFor(s, id)}</span>}
              </button>
            );
          })}
          <div className="dc-grow" />
        </nav>}
        {/* Mobile bottom bar */}
        {showNavigation && <nav aria-label={tr("底部导航", "Bottom navigation")} className="glass-bar bottombar-target" style={{
          position: "fixed", left: 0, right: 0, bottom: 0, zIndex: 50,
          height: "calc(var(--dc-bottom-nav-height) + env(safe-area-inset-bottom))",
          paddingBottom: "env(safe-area-inset-bottom)",
          display: "flex", alignItems: "stretch",
          overflowX: "auto", overflowY: "hidden",
          borderTop: "var(--dc-border-width) solid var(--dc-outline-variant)",
          background: "color-mix(in srgb, var(--dc-surface) 92%, transparent)",
        }}>
          {bottomItems.map((id) => {
            const Icon = iconFor(s.navItems.find((n) => n.id === id)?.iconKey ?? "");
            const active = id === activeNavId;
            return (
              <button key={id} aria-current={active ? "page" : undefined} style={{
                flex: "1 1 0", border: "none", background: "transparent",
                minWidth: 0, width: "auto", padding: 0, cursor: "pointer",
                display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", gap: 2,
                color: active ? "var(--dc-primary)" : "var(--dc-on-surface-variant)",
              }} onClick={() => navigate(routeFor(id))}>
                <Icon size={22} />
                {s.bottomNavShowLabels && <span style={{ fontSize: "0.66em", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", maxWidth: "100%", padding: "0 2px" }}>{labelFor(s, id)}</span>}
              </button>
            );
          })}
        </nav>}
        <style>{`
          .dc-page-without-navigation { padding-bottom: max(calc(20px * var(--dc-spacing)), env(safe-area-inset-bottom)); }
          @media (min-width: 769px){
            .rail-target{display:flex!important;}
            .bottombar-target{display:none!important;}
            .dc-page-with-navigation{padding-left: calc(104px * var(--dc-spacing));}
          }
        `}</style>
        <main className={`dc-page ${showNavigation ? "dc-page-with-navigation" : "dc-page-without-navigation"}`} style={{ position: "relative", zIndex: 1 }}>
          <Routes>
            {APP_ROUTES.map((r) => (
              <Route key={r.path} path={r.path} element={r.element} />
            ))}
            <Route path="/more" element={<MoreHub settings={s} />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </main>
      </div>
    </TutorialProvider>
  );
}
