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
  BookOpen, Brain, ChevronRight, Cloud, Columns3, Database, Globe,
  HeartPulse, Home as IconHome, Info, LayoutGrid, Lock, Palette, RotateCcw, Rss, Save,
  Search, SlidersHorizontal, X, Zap,
} from "lucide-react";
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
    <div role="radiogroup" className="dc-segmented" style={{
      display: "flex", border: "var(--dc-border-width) solid var(--dc-outline-variant)",
      borderRadius: "calc(var(--dc-radius) * 0.6)", overflow: "hidden",
    }}>
      {props.options.map((o) => {
        const active = o.value === props.value;
        return (
          <button key={o.value} type="button" role="radio" aria-checked={active} disabled={props.disabled}
            className={`dc-segmented-option${active ? " is-selected" : ""}`}
            onClick={() => props.onChange(o.value)}
            style={{
              flex: 1, padding: "8px 4px", border: "none", fontSize: "0.9em", whiteSpace: "nowrap",
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
  /** Android-style parent page. Children return here instead of flattening to the root. */
  parent?: string;
  /** Menu-only pages contain child sections and do not edit a draft themselves. */
  children?: string[];
  /** Immediate/read-only pages do not show the shell-level Save action. */
  draftManaged?: boolean;
  /** 恢复默认 restores only these keys of the current section's draft. */
  defaults?: Partial<AppSettings>;
}

const GENERAL_DEFAULTS: Partial<AppSettings> = {
  visualStyle: "MATERIAL", appLanguage: "CHINESE", darkMode: "SYSTEM",
  orientationPreference: "AUTO", fontScale: 1, compactMode: false,
  backgroundImageUri: null, backgroundImageOpacity: 0.45, backgroundImageBlurDp: 0,
  themeColorArgb: 0xFF42664D | 0,
  themeSecondaryColorsArgb: [0xFFC96F4A | 0, 0xFFD4A72C | 0, 0xFF527F91 | 0],
};

// 恢复默认 targets (draft only) mirroring the AppModels.kt defaults of the
// fields each section edits.
const BROWSER_THOUGHT_POETRY_RSS_SECTION = lazy(() => import("./sections/BrowserThoughtPoetryRssSection"));

const USAGE_DEFAULTS: Partial<AppSettings> = { usageTrackingEnabled: false };
const HEALTH_DEFAULTS: Partial<AppSettings> = { stepTrackingEnabled: false };
const BROWSER_DEFAULTS: Partial<AppSettings> = {
  browserHomeUrl: "https://www.google.com", browserTheme: "SYSTEM", browserDesktopMode: false,
};
const THOUGHT_DEFAULTS: Partial<AppSettings> = {
  thoughtRowHeightDp: 56, thoughtReopenMode: "ALL", thoughtDisplayMode: "SINGLE_LINE",
  thoughtHighlightColorArgb: 0xFFF6E3A1 | 0, thoughtEditorMaxHeightDp: 168,
};
const POETRY_DEFAULTS: Partial<AppSettings> = {
  poetryFontSizeSp: 18, poetryLineSpacing: 1.45, poetryTextAlignment: "START",
  poetryShowSource: true, poetryShowQuoteMark: true, poetrySevenCharacterWrapEnabled: false,
};
const RSS_DEFAULTS: Partial<AppSettings> = {
  rssSubscriptions: [], rssMaxItemsPerFeed: 50, rssShowSummaries: true,
};
const VAULT_DEFAULTS: Partial<AppSettings> = { vaultRowHeightDp: 56 };

const SECTIONS: SectionDef[] = [
  {
    id: "general", zh: "外观与语言", en: "Appearance & language",
    descZh: "风格、自定义主题、颜色、字号、明暗与背景", descEn: "Style, custom theme, colors, font size, dark mode, and background",
    icon: Palette, keywords: "appearance language dark font background compact 外观 语言 风格 颜色 字号 明暗 背景 紧凑",
    Component: lazy(() => import("./sections/GeneralSection")), defaults: GENERAL_DEFAULTS,
  },
  { id: "subpages", zh: "子页面设置", en: "Subpage settings", icon: SlidersHorizontal, keywords: "subpage 子页面 主页 日记 浏览器 小巧思 诗词 rss ai 收藏夹 导航页",
    children: ["home", "diary", "browser", "thought", "vault", "poetry", "rss", "ai", "morepage", "usage", "health"], draftManaged: false },
  { id: "usage", zh: "手机使用时间", en: "Screen time", descZh: "导入、追踪开关与按日统计", descEn: "Import, tracking switch, and daily statistics", icon: Zap, keywords: "usage screen time 使用 时间 统计 时长",
    Component: BROWSER_THOUGHT_POETRY_RSS_SECTION, defaults: USAGE_DEFAULTS, parent: "subpages" },
  { id: "health", zh: "健康", en: "Health", descZh: "步数、距离与活动热量", descEn: "Steps, distance, and active calories", icon: HeartPulse, keywords: "health steps 步数 健康 热量 距离",
    Component: BROWSER_THOUGHT_POETRY_RSS_SECTION, defaults: HEALTH_DEFAULTS, parent: "subpages" },
  { id: "home", zh: "主页", en: "Home", descZh: "问候语、模块、标题、排序与饮食按钮", descEn: "Greeting, widgets, titles, order, and meal buttons", icon: IconHome, keywords: "home widget greeting 主页 模块 问候 饮食按钮 游戏",
    Component: lazy(() => import("./sections/HomeSection")), parent: "subpages" },
  { id: "diary", zh: "日记与媒体", en: "Diary & media", descZh: "保存文件夹、文件名、图片与吃历规则", descEn: "Storage folders, file names, images, and meal rules", icon: BookOpen, keywords: "diary media image compress meal calorie 日记 媒体 图片 压缩 相册 吃历 热量 结构化",
    Component: lazy(() => import("./sections/DiarySection")), parent: "subpages" },
  { id: "browser", zh: "浏览器", en: "Browser", descZh: "默认主页、主题与电脑模式", descEn: "Home page, theme, and desktop mode", icon: Globe, keywords: "browser 浏览器 主页 电脑模式 desktop",
    Component: BROWSER_THOUGHT_POETRY_RSS_SECTION, defaults: BROWSER_DEFAULTS, parent: "subpages" },
  { id: "thought", zh: "小巧思", en: "Thoughts", descZh: "打开位置、内容显示与行高", descEn: "Reopen behavior, content display, and row height", icon: Zap, keywords: "thought 小巧思 行高 重点 高亮 输入框",
    Component: BROWSER_THOUGHT_POETRY_RSS_SECTION, defaults: THOUGHT_DEFAULTS, parent: "subpages" },
  { id: "vault", zh: "收藏夹", en: "Vault", descZh: "调整收藏夹条目高度", descEn: "Adjust vault entry height", icon: Lock, keywords: "vault favorite row height 收藏夹 高度 行高",
    Component: BROWSER_THOUGHT_POETRY_RSS_SECTION, defaults: VAULT_DEFAULTS, parent: "subpages" },
  { id: "poetry", zh: "诗词本", en: "Poetry book", descZh: "字体、字号、行距、对齐与出处", descEn: "Font, size, spacing, alignment, and source", icon: BookOpen, keywords: "poetry font 诗词 字体 字号 行距 对齐 出处 引号 七言",
    Component: BROWSER_THOUGHT_POETRY_RSS_SECTION, defaults: POETRY_DEFAULTS, parent: "subpages" },
  { id: "rss", zh: "RSS 订阅", en: "RSS", descZh: "文章数量与摘要显示", descEn: "Article limits and summary display", icon: Rss, keywords: "rss feed 订阅 摘要",
    Component: BROWSER_THOUGHT_POETRY_RSS_SECTION, defaults: RSS_DEFAULTS, parent: "subpages" },
  { id: "ai", zh: "AI 配置", en: "AI configurations", descZh: "模型、接口、提示词与 API 密钥", descEn: "Models, endpoints, prompts, and API keys", icon: Brain, keywords: "ai agent model api key prompt calorie 模型 密钥 提示词 温度 热量 agent",
    Component: lazy(() => import("./sections/AiSection")), parent: "subpages" },
  { id: "data", zh: "应用数据", en: "App data", icon: Database, keywords: "backup export import storage 备份 导出 导入 存储 占用 自动备份",
    Component: lazy(() => import("./sections/DataSection")), draftManaged: false },
  { id: "cloudsync", zh: "云端同步", en: "Cloud sync", icon: Cloud, keywords: "webdav s3 cloud sync 云端 同步 上传 下载 冲突",
    Component: lazy(() => import("./sections/CloudSyncSection")), parent: "data" },
  { id: "navigation", zh: "底部导航", en: "Bottom navigation", icon: Columns3, keywords: "bottom nav bar 底栏 底部导航 图标 默认页 排序 名称",
    Component: lazy(() => import("./sections/SubpagesNavSection").then((m) => ({ default: m.BottomNavSection }))) },
  { id: "morepage", zh: "导航页", en: "Navigation page", descZh: "收纳页面、列数、名称、描述与颜色", descEn: "Collected pages, columns, names, descriptions, and colors", icon: LayoutGrid, keywords: "more page columns 导航页 列 一列 两列 三列 收纳 描述 底色",
    Component: lazy(() => import("./sections/SubpagesNavSection").then((m) => ({ default: m.MorePageSection }))), parent: "subpages" },
  { id: "about", zh: "关于", en: "About", icon: Info, keywords: "about version password guide 关于 版本 更新 密码 教学 指南",
    Component: lazy(() => import("./sections/AboutSection")), draftManaged: false },
];

const SECTION_BY_ID = new Map(SECTIONS.map((s) => [s.id, s]));

const PRIMARY_SECTION_IDS = ["general", "subpages", "data", "navigation", "about"];

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

  // Validation state belongs to the active section only.
  useEffect(() => {
    setSectionInvalid(false);
  }, [sectionId]);

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

  const parentId = section?.parent ?? "";

  const requestClose = () => {
    const target: PendingNav = parentId ? { kind: "open", id: parentId } : { kind: "close" };
    if (dirty) setPending(target);
    else applyPending(target);
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

  const Active = section?.Component;
  const draftManaged = section?.draftManaged !== false && !!Active;

  return (
    <div>
      <style>{`
        .dc-settings-content { width: min(100%, 900px); margin-inline: auto; }
      `}</style>
      <TopBar
        title={tr("设置", "Settings")}
        subtitle={section ? tr(section.zh, section.en) : undefined}
        back={!!section}
        onBack={requestClose}
        actions={
          <>
            {draftManaged && section?.defaults && (
              <button className="dc-icon-btn" disabled={saving} title={tr("重置本页所有设置", "Reset this page's settings")}
                aria-label={tr("重置本页所有设置", "Reset this page's settings")} onClick={resetSection}>
                <RotateCcw size={20} />
              </button>
            )}
            {draftManaged && (
              <button className="dc-btn dc-btn-filled" disabled={!dirty || saving || sectionInvalid} onClick={() => {
                void save().then((ok) => {
                  if (ok) applyPending(parentId ? { kind: "open", id: parentId } : { kind: "close" });
                });
              }}>
                <Save size={17} />{saving ? tr("保存中…", "Saving…") : tr("保存", "Save")}
              </button>
            )}
          </>
        }
      />
      <ErrorText error={error} />
      <div className="dc-settings-content">
        <div className="dc-col" style={{ gap: 12, minWidth: 0 }}>
          {!section && <SettingsHome settings={storeSettings} onOpen={requestSection} />}
          {section?.children && <SettingsCategory children={section.children} onOpen={requestSection} />}
          {section && Active && (
            <Suspense fallback={<Spinner />}>
              <Active settings={storeSettings} draft={draft} patch={patch} snackbar={showSnackbar} reportInvalid={setSectionInvalid} />
            </Suspense>
          )}
          {section && !Active && !section.children && (
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
            tr("首页只有五个主要分类；先进入分类，再选择细分设置。", "The home page has five main categories; open one to choose a detailed setting."),
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
      {!q && PRIMARY_SECTION_IDS.map((id) => {
        const def = SECTION_BY_ID.get(id);
        return def ? <MenuEntry key={id} def={def} onOpen={props.onOpen} /> : null;
      })}
    </div>
  );
}

function SettingsCategory(props: { children: string[]; onOpen: (id: string) => void }) {
  return (
    <div className="dc-col" style={{ gap: 8 }}>
      {props.children.map((id) => {
        const def = SECTION_BY_ID.get(id);
        return def ? <MenuEntry key={id} def={def} onOpen={props.onOpen} showDescription /> : null;
      })}
    </div>
  );
}

function MenuEntry(props: { def: SectionDef; onOpen: (id: string) => void; showDescription?: boolean }) {
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
        {props.showDescription && (def.descZh || def.descEn) && (
          <span className="dc-muted" style={{ fontSize: "0.84em" }}>{tr(def.descZh ?? "", def.descEn ?? "")}</span>
        )}
      </span>
      <ChevronRight size={18} className="dc-muted" />
    </button>
  );
}
