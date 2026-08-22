/**
 * Settings shell (/settings) — README_for_ai §17.
 *
 * Left sub-section list on desktop / dropdown on mobile; each section lazily loads
 * ./sections/<Name>Section.tsx. The shell owns the local draft (initialized from the
 * saved AppSettings), dirty tracking, the top-right 恢复默认 + 保存 actions and the
 * 「设置尚未保存」 confirm dialog shown before leaving a dirty section.
 * Shared form primitives (Toggle / Segmented / SliderRow / TextField / SelectField /
 * SectionCard) are exported here so every section file reuses the same look.
 */
import React, { Suspense, lazy, useEffect, useMemo, useRef, useState } from "react";
import type { ComponentType, LazyExoticComponent } from "react";
import { useSearchParams } from "react-router-dom";
import {
  AlertTriangle, BookOpen, Brain, ChevronRight, Cloud, Columns3, Database, Globe,
  HeartPulse, Home as IconHome, Info, LayoutGrid, Palette, RotateCcw, Rss, Save,
  Search, SlidersHorizontal, X, Zap,
} from "lucide-react";
import { apiGet } from "../../api/client";
import type { AppSettings } from "../../api/types";
import { useSettings } from "../../stores/settings";
import { tr } from "../../i18n/tr";
import {
  ConfirmDialog, EmptyState, ErrorText, PageTutorialOverlay, Snackbar, Spinner,
  TopBar, useDirtyGuard, useSnackbar,
} from "../../components/ui";

// ---------------------------------------------------------------------------
// Shared section contract + form primitives (used by ./sections/*.tsx)
// ---------------------------------------------------------------------------

export interface SettingsSectionProps {
  /** Saved settings (server state). */
  settings: AppSettings;
  /** Editable full copy owned by the shell; mutate via patch(). */
  draft: AppSettings;
  /** Merge a partial change into the draft. */
  patch: (p: Partial<AppSettings>) => void;
  /** Show a transient toast. */
  snackbar: (message: string) => void;
  /** Report whether the section's current draft fails validation; blocks 保存. */
  reportInvalid?: (invalid: boolean) => void;
}

export function SectionCard(props: { title?: React.ReactNode; description?: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="dc-card dc-col" style={{ padding: 14, gap: 12 }}>
      {props.title != null && <div style={{ fontWeight: 600 }}>{props.title}</div>}
      {props.description != null && (
        <div className="dc-muted" style={{ fontSize: "0.86em", marginTop: -6 }}>{props.description}</div>
      )}
      {props.children}
    </div>
  );
}

export function Toggle(props: { checked: boolean; onChange: (v: boolean) => void; disabled?: boolean; label?: React.ReactNode }) {
  const sw = (
    <button
      type="button" role="switch" aria-checked={props.checked} disabled={props.disabled}
      onClick={() => props.onChange(!props.checked)}
      style={{
        width: 46, height: 26, borderRadius: 999, border: "none", position: "relative", flexShrink: 0,
        background: props.checked ? "var(--dc-primary)" : "var(--dc-surface-variant)",
        opacity: props.disabled ? 0.5 : 1, transition: "background 0.18s ease", padding: 0,
      }}
    >
      <span style={{
        position: "absolute", top: 3, left: props.checked ? 23 : 3, width: 20, height: 20,
        borderRadius: "50%", background: props.checked ? "var(--dc-on-primary)" : "var(--dc-outline)",
        transition: "left 0.18s ease",
      }} />
    </button>
  );
  if (props.label === undefined) return sw;
  return <div className="dc-row" style={{ justifyContent: "space-between", gap: 12 }}><div className="dc-grow">{props.label}</div>{sw}</div>;
}

export function Segmented<T extends string>(props: {
  value: T; options: { value: T; label: string }[]; onChange: (v: T) => void; disabled?: boolean;
}) {
  return (
    <div role="radiogroup" style={{
      display: "flex", border: "var(--dc-border-width) solid var(--dc-outline-variant)",
      borderRadius: "calc(var(--dc-radius) * 0.6)", overflow: "hidden",
    }}>
      {props.options.map((o) => {
        const active = o.value === props.value;
        return (
          <button key={o.value} type="button" role="radio" aria-checked={active} disabled={props.disabled}
            onClick={() => props.onChange(o.value)}
            style={{
              flex: 1, padding: "8px 4px", border: "none", fontSize: "0.9em", whiteSpace: "nowrap",
              background: active ? "var(--dc-secondary-container)" : "transparent",
              color: active ? "var(--dc-on-secondary-container)" : "var(--dc-on-surface)",
              opacity: props.disabled ? 0.5 : 1,
            }}>
            {o.label}
          </button>
        );
      })}
    </div>
  );
}

export function SliderRow(props: {
  label: React.ReactNode; value: number; min: number; max: number; step: number;
  format?: (v: number) => string; onChange: (v: number) => void; disabled?: boolean; hint?: React.ReactNode;
}) {
  return (
    <div className="dc-col" style={{ gap: 4, opacity: props.disabled ? 0.5 : 1 }}>
      <div className="dc-row" style={{ justifyContent: "space-between" }}>
        <span>{props.label}</span>
        <span className="dc-muted" style={{ fontSize: "0.88em" }}>{props.format ? props.format(props.value) : props.value}</span>
      </div>
      <input
        type="range" min={props.min} max={props.max} step={props.step} value={props.value}
        disabled={props.disabled} aria-label={typeof props.label === "string" ? props.label : undefined}
        onChange={(e) => props.onChange(Number(e.target.value))}
        style={{ accentColor: "var(--dc-primary)", width: "100%", margin: 0 }}
      />
      {props.hint != null && <span className="dc-muted" style={{ fontSize: "0.82em" }}>{props.hint}</span>}
    </div>
  );
}

export function TextField(props: {
  label?: React.ReactNode; value: string; onChange: (v: string) => void; placeholder?: string;
  maxLength?: number; error?: boolean; multilineRows?: number; disabled?: boolean; hint?: React.ReactNode;
}) {
  const border = props.error ? ({ borderColor: "var(--dc-error)" } as React.CSSProperties) : undefined;
  return (
    <label className="dc-col" style={{ gap: 4 }}>
      {props.label != null && <span style={{ fontSize: "0.9em" }}>{props.label}</span>}
      {props.multilineRows ? (
        <textarea className="dc-input" rows={props.multilineRows} value={props.value} maxLength={props.maxLength}
          placeholder={props.placeholder} disabled={props.disabled} style={{ resize: "vertical", ...border }}
          onChange={(e) => props.onChange(e.target.value)} />
      ) : (
        <input className="dc-input" value={props.value} maxLength={props.maxLength} placeholder={props.placeholder}
          disabled={props.disabled} style={border} onChange={(e) => props.onChange(e.target.value)} />
      )}
      {props.hint != null && <span className="dc-muted" style={{ fontSize: "0.82em" }}>{props.hint}</span>}
    </label>
  );
}

export function SelectField(props: {
  label?: React.ReactNode; value: string; onChange: (v: string) => void;
  options: { value: string; label: string }[]; disabled?: boolean; hint?: React.ReactNode;
}) {
  return (
    <label className="dc-col" style={{ gap: 4 }}>
      {props.label != null && <span style={{ fontSize: "0.9em" }}>{props.label}</span>}
      <select className="dc-input" value={props.value} disabled={props.disabled}
        onChange={(e) => props.onChange(e.target.value)}>
        {props.options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
      {props.hint != null && <span className="dc-muted" style={{ fontSize: "0.82em" }}>{props.hint}</span>}
    </label>
  );
}

/** Default nav labels mirroring Android NavItemId (used when item.label is empty). */
const NAV_DEFAULT_LABELS: Record<string, { zh: string; en: string }> = {
  HOME: { zh: "首页", en: "Home" }, DESK: { zh: "桌面", en: "Desk" },
  DIARY: { zh: "日记", en: "Diary" }, NOTES: { zh: "笔记", en: "Notes" },
  BLOG: { zh: "浏览器", en: "Browser" }, THOUGHT: { zh: "小巧思", en: "Thoughts" },
  DATE: { zh: "日期记录", en: "Dates" }, POETRY: { zh: "诗词本", en: "Poetry book" },
  RSS: { zh: "RSS 订阅", en: "RSS" }, AI_CHAT: { zh: "AI 聊天", en: "AI chat" },
  VAULT: { zh: "收藏夹", en: "Vault" }, READER: { zh: "阅读", en: "Reader" },
  GAMES: { zh: "小游戏", en: "Games" }, STATISTICS: { zh: "统计", en: "Statistics" },
  USAGE: { zh: "手机使用时间", en: "Screen time" }, STEPS: { zh: "健康", en: "Health" },
  WIDGETS: { zh: "小卡片", en: "Widgets" }, MORE: { zh: "导航", en: "More" },
  SETTINGS: { zh: "设置", en: "Settings" },
};

export function navLabel(settings: AppSettings, id: string): string {
  const item = settings.navItems.find((n) => n.id === id);
  const fallback = NAV_DEFAULT_LABELS[id] ?? { zh: id, en: id };
  if (settings.appLanguage === "ENGLISH") return fallback.en;
  return item?.label || fallback.zh;
}

// ---------------------------------------------------------------------------
// Section registry
// ---------------------------------------------------------------------------

interface SectionDef {
  id: string;
  zh: string;
  en: string;
  descZh?: string;
  descEn?: string;
  icon?: React.ComponentType<{ size?: number | string }>;
  keywords?: string;
  Component?: LazyExoticComponent<ComponentType<SettingsSectionProps>>;
  /** 恢复默认 restores only these keys of the current section's draft. */
  defaults?: Partial<AppSettings>;
}

const GENERAL_DEFAULTS: Partial<AppSettings> = {
  appLanguage: "CHINESE", darkMode: "SYSTEM", fontScale: 1, compactMode: false,
  userName: "", tutorialModeEnabled: true, defaultPage: "HOME", bottomNavShowLabels: true,
  backgroundImageUri: null, backgroundImageOpacity: 0.45, backgroundImageBlurDp: 0,
};

// 恢复默认 targets (draft only) mirroring the AppModels.kt defaults of the
// fields each section edits.
const SUBPAGES_SECTION = lazy(() => import("./sections/SubpagesNavSection"));
const BROWSER_THOUGHT_POETRY_RSS_SECTION = lazy(() => import("./sections/BrowserThoughtPoetryRssSection"));

const USAGE_DEFAULTS: Partial<AppSettings> = { usageTrackingEnabled: false };
const HEALTH_DEFAULTS: Partial<AppSettings> = { stepTrackingEnabled: false };
const BROWSER_DEFAULTS: Partial<AppSettings> = {
  browserHomeUrl: "https://www.google.com", browserTheme: "SYSTEM", browserDesktopMode: false,
};
const THOUGHT_DEFAULTS: Partial<AppSettings> = {
  thoughtRowHeightDp: 56, thoughtReopenMode: "ALL", thoughtDisplayMode: "SINGLE_LINE",
  thoughtHighlightColorArgb: 0xFFF6E3A1, thoughtEditorMaxHeightDp: 168,
};
const POETRY_DEFAULTS: Partial<AppSettings> = {
  poetryFontSizeSp: 18, poetryLineSpacing: 1.45, poetryTextAlignment: "START",
  poetryShowSource: true, poetryShowQuoteMark: true, poetrySevenCharacterWrapEnabled: false,
};
const RSS_DEFAULTS: Partial<AppSettings> = {
  rssSubscriptions: [], rssMaxItemsPerFeed: 50, rssShowSummaries: true,
};

const SECTIONS: SectionDef[] = [
  {
    id: "general", zh: "通用与外观", en: "General & appearance",
    descZh: "语言、明暗、字号、用户名、背景图片与教学模式", descEn: "Language, dark mode, font size, user name, background and tutorial mode",
    icon: Palette, keywords: "appearance language dark font background compact tutorial 外观 语言 风格 颜色 字号 明暗 背景 紧凑 用户名 教学 启动页 底栏",
    Component: lazy(() => import("./sections/GeneralSection")), defaults: GENERAL_DEFAULTS,
  },
  { id: "subpages", zh: "子页面设置", en: "Subpage settings", icon: SlidersHorizontal, keywords: "subpage 子页面 主页 日记 浏览器 小巧思 诗词 rss ai 收藏夹 导航页",
    Component: SUBPAGES_SECTION },
  { id: "usage", zh: "手机使用时间设置", en: "Screen time settings", icon: Zap, keywords: "usage screen time 使用 时间 统计 时长",
    Component: BROWSER_THOUGHT_POETRY_RSS_SECTION, defaults: USAGE_DEFAULTS },
  { id: "health", zh: "健康设置", en: "Health settings", icon: HeartPulse, keywords: "health steps 步数 健康 热量 距离",
    Component: BROWSER_THOUGHT_POETRY_RSS_SECTION, defaults: HEALTH_DEFAULTS },
  { id: "home", zh: "主页设置", en: "Home settings", icon: IconHome, keywords: "home widget greeting 主页 模块 问候 饮食按钮 游戏",
    Component: lazy(() => import("./sections/HomeSection")) },
  { id: "diary", zh: "日记与媒体", en: "Diary & media", icon: BookOpen, keywords: "diary media image compress meal calorie 日记 媒体 图片 压缩 相册 吃历 热量 结构化",
    Component: lazy(() => import("./sections/DiarySection")) },
  { id: "browser", zh: "浏览器设置", en: "Browser settings", icon: Globe, keywords: "browser 浏览器 主页 电脑模式 desktop",
    Component: BROWSER_THOUGHT_POETRY_RSS_SECTION, defaults: BROWSER_DEFAULTS },
  { id: "thought", zh: "小巧思设置", en: "Thoughts settings", icon: Zap, keywords: "thought 小巧思 行高 重点 高亮 输入框",
    Component: BROWSER_THOUGHT_POETRY_RSS_SECTION, defaults: THOUGHT_DEFAULTS },
  { id: "poetry", zh: "诗词本设置", en: "Poetry book settings", icon: BookOpen, keywords: "poetry font 诗词 字体 字号 行距 对齐 出处 引号 七言",
    Component: BROWSER_THOUGHT_POETRY_RSS_SECTION, defaults: POETRY_DEFAULTS },
  { id: "rss", zh: "RSS 订阅设置", en: "RSS settings", icon: Rss, keywords: "rss feed 订阅 摘要",
    Component: BROWSER_THOUGHT_POETRY_RSS_SECTION, defaults: RSS_DEFAULTS },
  { id: "ai", zh: "AI 设置", en: "AI settings", icon: Brain, keywords: "ai agent model api key prompt calorie 模型 密钥 提示词 温度 热量 agent",
    Component: lazy(() => import("./sections/AiSection")) },
  { id: "data", zh: "应用数据", en: "App data", icon: Database, keywords: "backup export import storage 备份 导出 导入 存储 占用 自动备份",
    Component: lazy(() => import("./sections/DataSection")) },
  { id: "cloudsync", zh: "云端同步", en: "Cloud sync", icon: Cloud, keywords: "webdav s3 cloud sync 云端 同步 上传 下载 冲突",
    Component: lazy(() => import("./sections/CloudSyncSection")) },
  { id: "navigation", zh: "底部导航", en: "Bottom navigation", icon: Columns3, keywords: "bottom nav bar 底栏 底部导航 图标 默认页 排序 名称",
    Component: lazy(() => import("./sections/SubpagesNavSection").then((m) => ({ default: m.BottomNavSection }))) },
  { id: "morepage", zh: "导航页设置", en: "Navigation hub settings", icon: LayoutGrid, keywords: "more page columns 导航页 列 一列 两列 三列 收纳 描述 底色",
    Component: lazy(() => import("./sections/SubpagesNavSection").then((m) => ({ default: m.MorePageSection }))) },
  { id: "about", zh: "关于", en: "About", icon: Info, keywords: "about version password guide 关于 版本 更新 密码 教学 指南",
    Component: lazy(() => import("./sections/AboutSection")) },
];

const SECTION_BY_ID = new Map(SECTIONS.map((s) => [s.id, s]));

const MENU_GROUPS: { zh: string; en: string; ids: string[] }[] = [
  { zh: "通用", en: "General", ids: ["general"] },
  { zh: "子页面设置", en: "Subpage settings", ids: ["subpages", "usage", "health", "home", "diary", "browser", "thought", "poetry", "rss", "ai", "morepage"] },
  { zh: "数据", en: "Data", ids: ["data", "cloudsync"] },
  { zh: "导航与关于", en: "Navigation & about", ids: ["navigation", "about"] },
];

// ---------------------------------------------------------------------------
// Draft helpers
// ---------------------------------------------------------------------------

function cloneSettings(s: AppSettings): AppSettings {
  return JSON.parse(JSON.stringify(s)) as AppSettings;
}

function diffPatch(base: AppSettings, draft: AppSettings): Partial<AppSettings> {
  const out: Record<string, unknown> = {};
  (Object.keys(base) as (keyof AppSettings)[]).forEach((k) => {
    if (JSON.stringify(base[k]) !== JSON.stringify(draft[k])) out[k] = draft[k];
  });
  return out;
}

interface DeploymentInfo {
  scheme?: string;
  behindProxy?: boolean;
  publicDeployment?: boolean;
  suggestPassword?: boolean;
  suggestHttps?: boolean;
}

interface SystemInfo {
  version?: string;
  platform?: string;
  deployment?: DeploymentInfo;
}

type PendingNav = { kind: "close" } | { kind: "open"; id: string } | null;

// ---------------------------------------------------------------------------
// Shell
// ---------------------------------------------------------------------------

export default function SettingsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const storeSettings = useSettings((s) => s.settings);
  const update = useSettings((s) => s.update);
  const [snackbarMessage, showSnackbar] = useSnackbar();

  const sectionId = searchParams.get("section") ?? "";
  const section = sectionId ? SECTION_BY_ID.get(sectionId) : undefined;

  const [draft, setDraft] = useState<AppSettings | null>(null);
  const [saving, setSaving] = useState(false);
  const [sectionInvalid, setSectionInvalid] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [pending, setPending] = useState<PendingNav>(null);
  const [bannerDismissed, setBannerDismissed] = useState(false);
  const [systemInfo, setSystemInfo] = useState<SystemInfo | null>(null);

  // Validation state belongs to the active section only.
  useEffect(() => {
    setSectionInvalid(false);
  }, [sectionId]);

  useEffect(() => {
    let alive = true;
    apiGet<SystemInfo>("/api/system/info")
      .then((info) => { if (alive) setSystemInfo(info); })
      .catch(() => undefined);
    return () => { alive = false; };
  }, []);

  // (Re)initialize the draft whenever the saved settings change while clean.
  const dirtyRef = useRef(false);
  useEffect(() => {
    if (storeSettings && !dirtyRef.current) setDraft(cloneSettings(storeSettings));
  }, [storeSettings]);

  const dirty = useMemo(() => {
    if (!storeSettings || !draft) return false;
    return Object.keys(diffPatch(storeSettings, draft)).length > 0;
  }, [storeSettings, draft]);
  dirtyRef.current = dirty;

  useDirtyGuard(dirty);

  if (!storeSettings || !draft) {
    return <div className="dc-center" style={{ padding: 48 }}><Spinner /></div>;
  }

  const patch = (p: Partial<AppSettings>) => setDraft((prev) => (prev ? { ...prev, ...p } : prev));

  const save = async (): Promise<boolean> => {
    if (!storeSettings || !draft) return false;
    if (sectionInvalid) return false;
    const p = diffPatch(storeSettings, draft);
    if (Object.keys(p).length === 0) return true;
    setSaving(true);
    setError(null);
    try {
      await update(p);
      const fresh = useSettings.getState().settings;
      if (fresh) setDraft(cloneSettings(fresh));
      showSnackbar(tr("已保存", "Saved"));
      return true;
    } catch (e) {
      setError(e);
      return false;
    } finally {
      setSaving(false);
    }
  };

  const applyPending = (p: PendingNav) => {
    if (!p) return;
    if (p.kind === "close") {
      setSearchParams({});
    } else {
      setSearchParams({ section: p.id });
    }
  };

  const requestClose = () => {
    if (dirty) setPending({ kind: "close" });
    else setSearchParams({});
  };

  const requestSection = (id: string) => {
    if (id === sectionId) return;
    if (dirty) setPending({ kind: "open", id });
    else setSearchParams({ section: id });
  };

  const resetSection = () => {
    if (!section?.defaults) return;
    setDraft({ ...draft, ...section.defaults });
  };

  const deploy = systemInfo?.deployment;
  const showBanner = !!deploy && (!!deploy.suggestPassword || !!deploy.suggestHttps) && !bannerDismissed;

  const sideList = (
    <nav aria-label={tr("设置分区", "Settings sections")} className="dc-set-side">
      {SECTIONS.map((s) => {
        const Icon = s.icon;
        const active = s.id === sectionId;
        return (
          <button key={s.id} className={`dc-set-item${active ? " active" : ""}`} onClick={() => requestSection(s.id)}>
            {Icon && <Icon size={19} />}
            <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
              {tr(s.zh, s.en)}
            </span>
          </button>
        );
      })}
    </nav>
  );

  const mobileSelect = (
    <select
      className="dc-input dc-set-mobile"
      aria-label={tr("设置分区", "Settings sections")}
      value={sectionId}
      onChange={(e) => (e.target.value ? requestSection(e.target.value) : requestClose())}
    >
      <option value="">{tr("设置主页", "Settings home")}</option>
      {SECTIONS.map((s) => <option key={s.id} value={s.id}>{tr(s.zh, s.en)}</option>)}
    </select>
  );

  const Active = section?.Component;

  return (
    <div>
      <style>{`
        .dc-set-grid { display: flex; flex-direction: column; gap: 12px; }
        .dc-set-side { display: none; flex-direction: column; gap: 2px; }
        @media (min-width: 900px) {
          .dc-set-grid { display: grid; grid-template-columns: 240px minmax(0, 1fr); gap: 20px; align-items: start; }
          .dc-set-mobile { display: none !important; }
          .dc-set-side { display: flex; position: sticky; top: 70px; max-height: calc(100vh - 96px); overflow-y: auto; }
        }
        .dc-set-item { display: flex; width: 100%; text-align: left; align-items: center; gap: 10px; padding: 10px 12px;
          border: none; background: transparent; border-radius: calc(var(--dc-radius) * 0.7); color: var(--dc-on-surface); }
        .dc-set-item.active { background: var(--dc-secondary-container); color: var(--dc-on-secondary-container); }
        .dc-set-item:hover:not(.active) { background: color-mix(in srgb, var(--dc-on-surface) 6%, transparent); }
      `}</style>
      <TopBar
        title={tr("设置", "Settings")}
        subtitle={section ? tr(section.zh, section.en) : undefined}
        back={!!section}
        onBack={requestClose}
        actions={
          <>
            {section?.defaults && (
              <button className="dc-icon-btn" disabled={saving} title={tr("重置本页所有设置", "Reset this page's settings")}
                aria-label={tr("重置本页所有设置", "Reset this page's settings")} onClick={resetSection}>
                <RotateCcw size={20} />
              </button>
            )}
            {section && (
              <button className="dc-btn dc-btn-filled" disabled={!dirty || saving || sectionInvalid} onClick={() => void save()}>
                <Save size={17} />{saving ? tr("保存中…", "Saving…") : tr("保存", "Save")}
              </button>
            )}
          </>
        }
      />
      {showBanner && (
        <div className="dc-card dc-row" role="alert"
          style={{ padding: "10px 12px", borderColor: "var(--dc-error)", alignItems: "flex-start", gap: 10, marginBottom: 12 }}>
          <AlertTriangle size={18} style={{ color: "var(--dc-error)", flexShrink: 0, marginTop: 2 }} />
          <div className="dc-grow" style={{ fontSize: "0.9em" }}>
            {tr(
              "检测到公网部署：建议开启访问密码并启用 HTTPS（兼容 Caddy/Nginx 反向代理）。",
              "Public deployment detected: enable an access password and HTTPS (compatible with Caddy/Nginx reverse proxies).",
            )}
          </div>
          <button className="dc-icon-btn" style={{ width: 32, height: 32 }} aria-label={tr("关闭提示", "Dismiss")}
            onClick={() => setBannerDismissed(true)}>
            <X size={16} />
          </button>
        </div>
      )}
      <ErrorText error={error} />
      <div className="dc-set-grid">
        {sideList}
        <div className="dc-col" style={{ gap: 12, minWidth: 0 }}>
          {mobileSelect}
          {!section && <SettingsHome settings={storeSettings} onOpen={requestSection} />}
          {section && Active && (
            <Suspense fallback={<Spinner />}>
              <Active settings={storeSettings} draft={draft} patch={patch} snackbar={showSnackbar} reportInvalid={setSectionInvalid} />
            </Suspense>
          )}
          {section && !Active && (
            <EmptyState title={tr("此设置分区尚未提供", "This settings section is not available yet")} />
          )}
        </div>
      </div>

      <ConfirmDialog
        open={pending !== null}
        title={tr("设置尚未保存", "Settings not saved")}
        message={tr("返回会丢失刚才的修改。", "Going back will lose your changes.")}
        confirmLabel={saving ? tr("保存中…", "Saving…") : tr("保存", "Save")}
        cancelLabel={tr("继续编辑", "Keep editing")}
        onConfirm={() => {
          void (async () => {
            const ok = await save();
            if (ok) {
              const p = pending;
              setPending(null);
              applyPending(p);
            }
          })();
        }}
        onCancel={() => setPending(null)}
      >
        <div className="dc-row" style={{ justifyContent: "flex-end" }}>
          <button className="dc-btn dc-btn-danger" disabled={saving}
            onClick={() => {
              setDraft(cloneSettings(storeSettings));
              const p = pending;
              setPending(null);
              applyPending(p);
            }}>
            {tr("放弃", "Discard")}
          </button>
        </div>
      </ConfirmDialog>

      {!section && (
        <PageTutorialOverlay
          pageKey="settings"
          title={tr("设置", "Settings")}
          lines={[
            tr("用搜索或点按分区进入对应设置页。", "Search or tap a section to open its settings."),
            tr("每个子页右上角可恢复默认并保存修改。", "Each subpage offers reset and save in the top bar."),
          ]}
        />
      )}
      <Snackbar message={snackbarMessage} />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Settings home menu (search + grouped entries), mirrors Android 设置主页
// ---------------------------------------------------------------------------

function SettingsHome(props: { settings: AppSettings; onOpen: (id: string) => void }) {
  const [query, setQuery] = useState("");
  const q = query.trim().toLowerCase();
  const results = q
    ? SECTIONS.filter((s) =>
      s.zh.toLowerCase().includes(q) ||
      s.en.toLowerCase().includes(q) ||
      (s.descZh ?? "").toLowerCase().includes(q) ||
      (s.descEn ?? "").toLowerCase().includes(q) ||
      (s.keywords ?? "").toLowerCase().includes(q))
    : [];

  return (
    <div className="dc-col" style={{ gap: 12 }}>
      <div style={{ position: "relative" }}>
        <input
          className="dc-input" value={query} onChange={(e) => setQuery(e.target.value)}
          placeholder={tr("搜索设置", "Search settings")} aria-label={tr("搜索设置", "Search settings")}
          style={{ paddingRight: 36 }}
        />
        {query && (
          <button className="dc-icon-btn" style={{ position: "absolute", right: 2, top: 3, width: 34, height: 34 }}
            aria-label={tr("清除搜索", "Clear search")} onClick={() => setQuery("")}>
            <X size={16} />
          </button>
        )}
      </div>
      {q && (
        results.length === 0
          ? <div className="dc-muted" style={{ padding: "8px 4px" }}>{tr("没有匹配的设置", "No matching settings")}</div>
          : results.map((s) => <MenuEntry key={s.id} def={s} onOpen={props.onOpen} />)
      )}
      {!q && MENU_GROUPS.map((g) => (
        <div key={g.zh} className="dc-col" style={{ gap: 8 }}>
          <div className="dc-muted" style={{ fontSize: "0.85em", padding: "0 4px" }}>{tr(g.zh, g.en)}</div>
          {g.ids.map((id) => {
            const def = SECTION_BY_ID.get(id);
            return def ? <MenuEntry key={id} def={def} onOpen={props.onOpen} /> : null;
          })}
        </div>
      ))}
    </div>
  );
}

function MenuEntry(props: { def: SectionDef; onOpen: (id: string) => void }) {
  const { def } = props;
  const Icon = def.icon;
  return (
    <button className="dc-card dc-row" style={{ width: "100%", textAlign: "left", padding: "12px 14px", gap: 12, border: "none", cursor: "pointer" }}
      onClick={() => props.onOpen(def.id)}>
      {Icon && (
        <span style={{
          width: 38, height: 38, borderRadius: 12, flexShrink: 0, display: "flex", alignItems: "center", justifyContent: "center",
          background: "var(--dc-surface-container-high)", color: "var(--dc-on-surface)",
        }}>
          <Icon size={19} />
        </span>
      )}
      <span className="dc-grow dc-col" style={{ gap: 2, minWidth: 0 }}>
        <span style={{ fontWeight: 600 }}>{tr(def.zh, def.en)}</span>
        {(def.descZh || def.descEn) && (
          <span className="dc-muted" style={{ fontSize: "0.84em" }}>{def.descZh ? tr(def.descZh, def.descEn ?? def.descZh) : def.descEn}</span>
        )}
      </span>
      <ChevronRight size={18} className="dc-muted" />
    </button>
  );
}
