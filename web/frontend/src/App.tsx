/**
 * DeskCubby Web shell: auth gate → settings → navigation (bottom bar on phones,
 * left rail on desktop) → routed pages from pages/registry.
 */
import React, { useEffect, useMemo, useState } from "react";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import {
  Home as IconHome, LayoutDashboard as IconDesk, Book as IconBook, FileText as IconNotes,
  Globe as IconLanguage, Zap as IconBolt, BookOpen as IconPoetry, Settings as IconSettings,
  CalendarDays as IconCalendar, CalendarClock as IconEvent, Star as IconStar, PenLine as IconWrite,
  Sparkles as IconSparkle, Rows3 as IconDay, Rss as IconRss, Brain as IconAi,
  LayoutGrid as IconApps, Lock as IconLock, Gamepad2 as IconGame, BookMarked as IconReader,
  Clock as IconUsage, HeartPulse as IconSteps, BarChart3 as IconStatistics,
  Blocks as IconWidgets, ChevronRight,
} from "lucide-react";
import { apiGet } from "./api/client";
import type { AppSettings } from "./api/types";
import { useSettings } from "./stores/settings";
import { buildRoutes } from "./pages/registry";
import { tr } from "./i18n/tr";
import { PageTutorialOverlay, TutorialProvider } from "./components/ui";

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

const DEFAULT_DESC: Record<string, string> = {
  DESK: "把今天留下的痕迹摊开在你的数字桌面上",
  NOTES: "按文件夹管理 Obsidian 兼容 Markdown 笔记",
  BLOG: "在应用内浏览网页",
  DATE: "追踪纪念日与目标日期",
  POETRY: "收藏喜欢的诗词",
  RSS: "阅读订阅源的最新文章",
  AI_CHAT: "选择本机记录作为上下文并与模型分析",
  VAULT: "密码保护的私密收藏",
  READER: "导入并阅读 TXT/PDF 小说",
  GAMES: "2048、贪吃蛇、俄罗斯方块、扫雷与蜘蛛纸牌",
  STATISTICS: "汇总日记、使用时间、健康、阅读与小游戏数据",
  WIDGETS: "设计并添加可缩放的桌面小卡片",
};

function labelFor(s: AppSettings, id: string): string {
  const item = s.navItems.find((n) => n.id === id);
  const fallback = DEFAULT_LABELS[id] ?? { zh: id, en: id };
  const label = item?.label || fallback.zh;
  if (s.appLanguage === "ENGLISH") {
    // Show built-in English label unless the user customized the name away from
    // the built-in Chinese default (mirrors Navigation.kt isBuiltInLabel check).
    const zhDefaults = Object.values(DEFAULT_LABELS).map((v) => v.zh);
    return zhDefaults.includes(label) ? fallback.en : label;
  }
  return label;
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

function MoreHub({ settings }: { settings: AppSettings }) {
  const navigate = useNavigate();
  const order = settings.morePageOrder ?? [];
  const items = [...settings.navItems]
    .filter((n) => n.showInMore)
    .sort((a, b) => {
      const ia = order.includes(a.id) ? order.indexOf(a.id) : 9999;
      const ib = order.includes(b.id) ? order.indexOf(b.id) : 9999;
      return ia - ib;
    });
  return (
    <div>
      <div className="dc-title" style={{ padding: "10px 4px" }}>{tr("导航", "More")}</div>
      <div style={{ columns: settings.morePageColumns, columnGap: 12 }}>
        {items.map((item) => {
          const Icon = iconFor(item.iconKey);
          return (
            <button key={item.id} onClick={() => navigate(routeFor(item.id))} className="dc-card" style={{
              display: "flex", width: "100%", textAlign: "left", border: "none", cursor: "pointer",
              alignItems: "center", gap: 12, padding: 14, marginBottom: 12, breakInside: "avoid",
              background: item.moreCardColorArgb != null ? `rgb(${(item.moreCardColorArgb >>> 16) & 255},${(item.moreCardColorArgb >>> 8) & 255},${item.moreCardColorArgb & 255})` : "var(--dc-surface-container)",
            }}>
              <span style={{
                width: 42, height: 42, borderRadius: 13, display: "flex", alignItems: "center", justifyContent: "center",
                background: item.moreButtonColorArgb != null ? `rgb(${(item.moreButtonColorArgb >>> 16) & 255},${(item.moreButtonColorArgb >>> 8) & 255},${item.moreButtonColorArgb & 255})` : "var(--dc-surface-container-high)",
                color: "var(--dc-on-surface)", flexShrink: 0,
              }}>
                <Icon size={21} />
              </span>
              <span className="dc-grow">
                <div style={{ fontWeight: 600 }}>{item.label || labelFor(settings, item.id)}</div>
                {settings.morePageShowDescriptions && (
                  <div className="dc-muted" style={{ fontSize: "0.82em", marginTop: 2 }}>
                    {item.moreDescription || DEFAULT_DESC[item.id]}
                  </div>
                )}
              </span>
              <ChevronRight size={18} className="dc-muted" />
            </button>
          );
        })}
      </div>
      <PageTutorialOverlay pageKey="more" title={tr("导航页", "Navigation hub")} lines={[tr("这里收纳了未固定到底部导航的页面。", "Collected pages that are not pinned to the bottom bar.")]} />
    </div>
  );
}

export default function App() {
  const [auth, setAuth] = useState<AuthStatus | null>(null);
  const settingsState = useSettings();
  const location = useLocation();
  const navigate = useNavigate();

  useEffect(() => {
    apiGet<AuthStatus>("/api/auth/status").then(setAuth).catch(() => setAuth({ enabled: false, authenticated: true }));
  }, []);

  useEffect(() => {
    if (auth?.authenticated) void settingsState.load().catch(() => undefined);
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
    return <div className="dc-center" style={{ height: "100vh" }}><span className="dc-muted">DeskCubby…</span></div>;
  }
  if (!auth.authenticated) {
    return <Navigate to="/login" replace />;
  }
  if (!settings) {
    return <div className="dc-center" style={{ height: "100vh" }}><span className="dc-muted">DeskCubby…</span></div>;
  }

  const s = settings;
  const bgImage = s.backgroundImageUri
    ? `/api/settings/background-image?ts=${encodeURIComponent(s.backgroundImageUri)}`
    : null;

  const ackTutorial = (page: string) => {
    const next = Array.from(new Set([...(s.tutorialAcknowledgedPages ?? []), page]));
    void settingsState.update({ tutorialAcknowledgedPages: next });
  };

  const routes = buildRoutes();

  return (
    <TutorialProvider enabled={s.tutorialModeEnabled} acknowledged={s.tutorialAcknowledgedPages ?? []} ack={ackTutorial}>
      <div style={{ minHeight: "100%", position: "relative" }}>
        {bgImage && (
          <div aria-hidden style={{
            position: "fixed", inset: 0, zIndex: -1,
            backgroundImage: `url(${bgImage})`, backgroundSize: "cover", backgroundPosition: "center",
            opacity: s.backgroundImageOpacity,
            filter: s.backgroundImageBlurDp > 0 ? `blur(${s.backgroundImageBlurDp}px)` : undefined,
          }} />
        )}
        {/* Desktop left rail */}
        <nav aria-label={tr("主导航", "Main navigation")} className="glass-bar rail-target" style={{
          display: "none",
          position: "fixed", left: 0, top: 0, bottom: 0, width: 88, zIndex: 50,
          flexDirection: "column", alignItems: "center", padding: "12px 4px", gap: 4,
          overflowY: "auto",
        }}>
          {bottomItems.map((id) => {
            const Icon = iconFor(s.navItems.find((n) => n.id === id)?.iconKey ?? "");
            const active = location.pathname.startsWith(routeFor(id));
            return (
              <button key={id} className="dc-icon-btn" style={{
                width: 64, height: 56, borderRadius: 16, flexDirection: "column", gap: 2,
                background: active ? "var(--dc-secondary-container)" : "transparent",
                color: active ? "var(--dc-on-secondary-container)" : "var(--dc-on-surface)",
              }} onClick={() => navigate(routeFor(id))} title={labelFor(s, id)}>
                <Icon size={22} />
                {s.bottomNavShowLabels && <span style={{ fontSize: "0.62em" }}>{labelFor(s, id)}</span>}
              </button>
            );
          })}
          <div className="dc-grow" />
        </nav>
        {/* Mobile bottom bar */}
        <nav aria-label={tr("底部导航", "Bottom navigation")} className="glass-bar bottombar-target" style={{
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
            const active = location.pathname.startsWith(routeFor(id));
            return (
              <button key={id} style={{
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
        </nav>
        <style>{`@media (min-width: 769px){ .rail-target{display:flex!important;} .bottombar-target{display:none!important;} .dc-page{padding-left: calc(104px * var(--dc-spacing));} }`}</style>
        <main className="dc-page">
          <Routes>
            {routes.map((r) => (
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
