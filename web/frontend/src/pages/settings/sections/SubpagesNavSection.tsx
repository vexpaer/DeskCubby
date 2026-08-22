/**
 * 设置 → 子页面设置 / 底部导航 / 导航页设置 (README_for_ai §17.3 + §17.15 + §17.16).
 *
 * Three sections share this file because they edit overlapping parts of the same
 * navItems/morePage* draft fields:
 * - default SubpagesSection ("subpages"): one editor card per navigation item —
 *   visible/showInMore toggles, label, iconKey, moreDescription, button/card colors.
 * - BottomNavSection ("navigation"): bottomNavShowLabels, 默认启动页面 and the
 *   bottom-bar visibility/order/name/icon editors (label limited to 8 characters,
 *   mirroring §17.15).
 * - MorePageSection ("morepage"): morePageColumns 1–3, morePageShowDescriptions,
 *   收纳 toggles, morePageOrder sorting and per-module name/description/colors
 *   (§17.16).
 *
 * Everything edits the shell-owned draft; 保存 persists via PUT /api/settings.
 */
import React from "react";
import type { NavItemConfig } from "../../../api/types";
import { argbToCss } from "../../../api/types";
import { tr } from "../../../i18n/tr";
import {
  SectionCard, Segmented, SelectField, TextField, Toggle, navLabel,
} from "../SettingsPage";
import type { SettingsSectionProps } from "../SettingsPage";

// ---------------------------------------------------------------------------
// Catalogs mirroring AppModels.kt NavItemId
// ---------------------------------------------------------------------------

interface NavCatalogEntry {
  id: string;
  zh: string;
  en: string;
  descZh: string;
  descEn: string;
  icon: string;
  visible: boolean;
  showInMore: boolean;
}

export const NAV_CATALOG: NavCatalogEntry[] = [
  { id: "HOME", zh: "首页", en: "Home", descZh: "今日概览与快捷记录", descEn: "Overview and quick capture", icon: "home", visible: true, showInMore: false },
  { id: "DESK", zh: "桌面", en: "Desk", descZh: "把今天留下的痕迹摊开在你的数字桌面上", descEn: "Your personal desk for today's traces", icon: "desk", visible: false, showInMore: true },
  { id: "DIARY", zh: "日记", en: "Diary", descZh: "浏览、编辑日记与吃历", descEn: "Diaries and meal calendar", icon: "book", visible: true, showInMore: false },
  { id: "NOTES", zh: "笔记", en: "Notes", descZh: "按文件夹管理 Obsidian 兼容 Markdown 笔记", descEn: "Manage Obsidian-compatible Markdown notes by folder", icon: "notes", visible: false, showInMore: true },
  { id: "BLOG", zh: "浏览器", en: "Browser", descZh: "在应用内浏览网页", descEn: "Browse the web in the app", icon: "language", visible: false, showInMore: true },
  { id: "THOUGHT", zh: "小巧思", en: "Thoughts", descZh: "记录与整理瞬间想法", descEn: "Capture and organize thoughts", icon: "bolt", visible: true, showInMore: false },
  { id: "DATE", zh: "日期记录", en: "Dates", descZh: "追踪纪念日与目标日期", descEn: "Track occasions and target dates", icon: "event", visible: false, showInMore: true },
  { id: "POETRY", zh: "诗词本", en: "Poetry book", descZh: "收藏喜欢的诗词", descEn: "Keep your favorite poems", icon: "poetry", visible: false, showInMore: true },
  { id: "RSS", zh: "RSS 订阅", en: "RSS", descZh: "阅读订阅源的最新文章", descEn: "Read the latest from your feeds", icon: "rss", visible: false, showInMore: true },
  { id: "AI_CHAT", zh: "AI 聊天", en: "AI chat", descZh: "选择本机记录作为上下文并与模型分析", descEn: "Analyze selected local records with AI", icon: "ai", visible: false, showInMore: true },
  { id: "VAULT", zh: "收藏夹", en: "Vault", descZh: "密码保护的私密收藏", descEn: "Password-protected private notes", icon: "lock", visible: false, showInMore: true },
  { id: "READER", zh: "阅读", en: "Reader", descZh: "导入并阅读 TXT/PDF 小说", descEn: "Import and read TXT/PDF books", icon: "reader", visible: false, showInMore: true },
  { id: "GAMES", zh: "小游戏", en: "Games", descZh: "2048、贪吃蛇、俄罗斯方块、扫雷与蜘蛛纸牌", descEn: "2048, Snake, Tetris, Minesweeper, and Spider Solitaire", icon: "game", visible: false, showInMore: true },
  { id: "STATISTICS", zh: "统计", en: "Statistics", descZh: "汇总日记、使用时间、健康、阅读与小游戏数据", descEn: "Explore diary, screen-time, health, reading, and game insights", icon: "statistics", visible: false, showInMore: true },
  { id: "USAGE", zh: "手机使用时间", en: "Screen time", descZh: "按天查看各应用的使用时长", descEn: "Daily usage time by app", icon: "usage", visible: false, showInMore: false },
  { id: "STEPS", zh: "健康", en: "Health", descZh: "读取并可视化每日步数、距离和活动热量", descEn: "Chart daily steps, distance, and active calories", icon: "steps", visible: false, showInMore: false },
  { id: "WIDGETS", zh: "小卡片", en: "Widgets", descZh: "设计并添加可缩放的桌面小卡片", descEn: "Design and add resizable home-screen widgets", icon: "widgets", visible: false, showInMore: true },
  { id: "MORE", zh: "导航", en: "More", descZh: "打开收纳的页面", descEn: "Open collected pages", icon: "apps", visible: false, showInMore: false },
  { id: "SETTINGS", zh: "设置", en: "Settings", descZh: "调整应用与页面设置", descEn: "Adjust app and page settings", icon: "settings", visible: true, showInMore: false },
];

const CATALOG_BY_ID = new Map(NAV_CATALOG.map((c) => [c.id, c]));

/** HOME/MORE/SETTINGS never appear as navigation-hub cards (AppModels.kt). */
const MORE_PAGE_ORDERABLE_IDS = NAV_CATALOG.filter((c) => !["HOME", "MORE", "SETTINGS"].includes(c.id)).map((c) => c.id);

/** Icon keys offered by the picker = src/App.tsx ICONS map keys. */
const ICON_KEYS: { value: string; label: string }[] = [
  { value: "home", label: tr("首页图标", "Home") },
  { value: "desk", label: tr("桌面图标", "Desk") },
  { value: "book", label: tr("书本图标", "Book") },
  { value: "notes", label: tr("笔记图标", "Notes") },
  { value: "language", label: tr("语言图标", "Language") },
  { value: "bolt", label: tr("闪电图标", "Bolt") },
  { value: "poetry", label: tr("诗词图标", "Poetry") },
  { value: "settings", label: tr("齿轮图标", "Settings") },
  { value: "calendar", label: tr("日历图标", "Calendar") },
  { value: "event", label: tr("事件图标", "Event") },
  { value: "star", label: tr("星标图标", "Star") },
  { value: "write", label: tr("书写图标", "Write") },
  { value: "sparkle", label: tr("火花图标", "Sparkle") },
  { value: "day", label: tr("日期行图标", "Day") },
  { value: "rss", label: tr("RSS 图标", "RSS") },
  { value: "ai", label: tr("AI 图标", "AI") },
  { value: "apps", label: tr("格子图标", "Apps") },
  { value: "lock", label: tr("锁图标", "Lock") },
  { value: "game", label: tr("手柄图标", "Game") },
  { value: "reader", label: tr("阅读图标", "Reader") },
  { value: "usage", label: tr("使用时间图标", "Usage") },
  { value: "steps", label: tr("健康图标", "Steps") },
  { value: "statistics", label: tr("统计图标", "Statistics") },
  { value: "widgets", label: tr("小卡片图标", "Widgets") },
];

const ICON_KEY_SET = new Set(ICON_KEYS.map((k) => k.value));

function argbToHex(v: number): string {
  return "#" + ((v & 0xffffff) >>> 0).toString(16).padStart(6, "0");
}

function hexToArgb(hex: string): number {
  const n = parseInt(hex.replace("#", ""), 16);
  if (!Number.isFinite(n)) return 0xff000000 | 0;
  return (0xff000000 | (n & 0xffffff)) | 0;
}

function catalogOf(id: string): NavCatalogEntry {
  return (
    CATALOG_BY_ID.get(id) ?? {
      id, zh: id, en: id, descZh: "", descEn: "", icon: "poetry", visible: false, showInMore: false,
    }
  );
}

/** Draft navItems normalized against the catalog (missing entries get defaults). */
function navItemMap(settings: SettingsSectionProps["settings"]): Map<string, NavItemConfig> {
  const map = new Map<string, NavItemConfig>();
  NAV_CATALOG.forEach((c) => {
    const existing = settings.navItems.find((n) => n.id === c.id);
    map.set(c.id, {
      id: c.id,
      label: existing?.label ?? "",
      iconKey: existing && ICON_KEY_SET.has(existing.iconKey) ? existing.iconKey : c.icon,
      visible: existing?.visible ?? c.visible,
      showInMore: existing?.showInMore ?? c.showInMore,
      moreDescription: existing?.moreDescription ?? "",
      moreButtonColorArgb: existing?.moreButtonColorArgb ?? null,
      moreCardColorArgb: existing?.moreCardColorArgb ?? null,
    });
  });
  return map;
}

/** normalizeMorePageOrder semantics: given order first, then navItems order, then catalog. */
function normalizeMorePageOrder(order: string[], navIds: string[]): string[] {
  const eligible = new Set(MORE_PAGE_ORDERABLE_IDS);
  const out: string[] = [];
  const push = (id: string) => {
    if (eligible.has(id) && !out.includes(id)) out.push(id);
  };
  order.forEach(push);
  navIds.forEach(push);
  MORE_PAGE_ORDERABLE_IDS.forEach(push);
  return out;
}

interface ItemEditorProps {
  item: NavItemConfig;
  fallbackName: string;
  /** null hides a control entirely. */
  showVisible?: boolean;
  showShowInMore?: boolean;
  labelMaxLength?: number;
  showDescription?: boolean;
  showColors?: boolean;
  showIcon?: boolean;
  onChange: (patch: Partial<NavItemConfig>) => void;
}

function NavItemEditor(props: ItemEditorProps) {
  const { item, onChange } = props;
  const colorRow = (labelZh: string, labelEn: string, value: number | null, key: "moreButtonColorArgb" | "moreCardColorArgb") => (
    <div className="dc-row" style={{ gap: 10 }}>
      <input
        type="color"
        aria-label={tr(labelZh, labelEn)}
        value={argbToHex(value ?? 0xff42664d)}
        disabled={value == null}
        onChange={(e) => onChange({ [key]: hexToArgb(e.target.value) } as Partial<NavItemConfig>)}
        style={{ width: 36, height: 28, padding: 0, border: "var(--dc-border-width) solid var(--dc-outline-variant)", borderRadius: 8, background: "none", cursor: value == null ? "default" : "pointer", opacity: value == null ? 0.4 : 1, flexShrink: 0 }}
      />
      <span className="dc-grow" style={{ fontSize: "0.9em" }}>{tr(labelZh, labelEn)}</span>
      {value != null ? (
        <>
          <span className="dc-muted" style={{ fontSize: "0.8em", fontFamily: "monospace" }}>{argbToHex(value)}</span>
          <button className="dc-btn" style={{ padding: "4px 10px", fontSize: "0.85em" }} onClick={() => onChange({ [key]: null } as Partial<NavItemConfig>)}>
            {tr("默认", "Default")}
          </button>
        </>
      ) : (
        <>
          <span className="dc-muted" style={{ fontSize: "0.82em" }}>{tr("主题配色", "Theme color")}</span>
          <button className="dc-btn dc-btn-tonal" style={{ padding: "4px 10px", fontSize: "0.85em" }}
            onClick={() => onChange({ [key]: hexToArgb(argbToHex(0xff42664d)) } as Partial<NavItemConfig>)}>
            {tr("自定义颜色", "Custom")}
          </button>
        </>
      )}
    </div>
  );

  return (
    <div className="dc-col" style={{ gap: 8 }}>
      {props.showVisible !== false && (
        <Toggle
          checked={item.visible}
          disabled={item.id === "SETTINGS"}
          onChange={(v) => onChange({ visible: v })}
          label={<span>{tr("底栏", "Bottom bar")}<div className="dc-muted" style={{ fontSize: "0.82em" }}>{item.id === "SETTINGS" ? tr("设置项固定开启、不可关闭。", "Settings is always shown and cannot be turned off.") : tr("控制该页面是否显示在底部导航栏。", "Whether the page appears in the bottom navigation bar.")}</div></span>}
        />
      )}
      {props.showShowInMore === true && !["HOME", "MORE", "SETTINGS"].includes(item.id) && (
        <Toggle
          checked={item.showInMore}
          onChange={(v) => onChange({ showInMore: v })}
          label={<span>{tr("收进导航页", "Show in navigation hub")}<div className="dc-muted" style={{ fontSize: "0.82em" }}>{tr("开启后该页面会以卡片形式收进导航聚合页。", "The page becomes a card inside the navigation hub.")}</div></span>}
        />
      )}
      <TextField
        label={`${tr("名称", "Label")}（${props.fallbackName}）`}
        value={item.label}
        maxLength={props.labelMaxLength ?? 32}
        placeholder={props.fallbackName}
        onChange={(v) => onChange({ label: v })}
        hint={props.labelMaxLength === 8 ? tr("最多 8 个字符。", "Up to 8 characters.") : undefined}
      />
      {props.showIcon !== false && (
        <SelectField
          label={tr("图标", "Icon")}
          value={item.iconKey}
          onChange={(v) => onChange({ iconKey: v })}
          options={ICON_KEYS}
        />
      )}
      {props.showDescription === true && (
        <TextField
          label={tr("描述", "Description")}
          value={item.moreDescription}
          maxLength={160}
          multilineRows={3}
          placeholder={catalogOf(item.id).descZh}
          onChange={(v) => onChange({ moreDescription: v })}
          hint={tr("最多 160 个字符；留空隐藏这张卡片的描述。", "Up to 160 characters; leave empty to hide this card's description.")}
        />
      )}
      {props.showColors === true && (
        <div className="dc-col" style={{ gap: 6 }}>
          {colorRow(tr("按钮底色", "Button color"), "Button color", item.moreButtonColorArgb, "moreButtonColorArgb")}
          {colorRow(tr("卡片底色", "Card color"), "Card color", item.moreCardColorArgb, "moreCardColorArgb")}
        </div>
      )}
    </div>
  );
}

function useNavDraft(props: SettingsSectionProps) {
  const { draft, patch } = props;
  const map = navItemMap(props.settings);
  const updateItem = (id: string, p: Partial<NavItemConfig>) => {
    // Base on the current draft list so concurrent edits are preserved.
    const list = draft.navItems.length > 0 ? draft.navItems : [];
    const base = list.find((n) => n.id === id);
    const cat = catalogOf(id);
    const merged: NavItemConfig = {
      id,
      label: base?.label ?? "",
      iconKey: base && ICON_KEY_SET.has(base.iconKey) ? base.iconKey : cat.icon,
      visible: base?.visible ?? cat.visible,
      showInMore: base?.showInMore ?? cat.showInMore,
      moreDescription: base?.moreDescription ?? "",
      moreButtonColorArgb: base?.moreButtonColorArgb ?? null,
      moreCardColorArgb: base?.moreCardColorArgb ?? null,
      ...p,
    };
    const next = list.some((n) => n.id === id)
      ? list.map((n) => (n.id === id ? merged : n))
      : [...list, merged];
    patch({ navItems: next });
  };
  return { map, updateItem };
}

function MoveButtons(props: { onMove: (delta: number) => void; upDisabled: boolean; downDisabled: boolean }) {
  return (
    <div className="dc-col" style={{ gap: 0 }}>
      <button className="dc-icon-btn" style={{ width: 30, height: 26 }} disabled={props.upDisabled}
        aria-label={tr("上移", "Move up")} onClick={() => props.onMove(-1)}>↑</button>
      <button className="dc-icon-btn" style={{ width: 30, height: 26 }} disabled={props.downDisabled}
        aria-label={tr("下移", "Move down")} onClick={() => props.onMove(1)}>↓</button>
    </div>
  );
}

// ---------------------------------------------------------------------------
// 子页面设置 (§17.3) — per-page editors in one place
// ---------------------------------------------------------------------------

export default function SubpagesSection(props: SettingsSectionProps) {
  const { draft } = props;
  const { map, updateItem } = useNavDraft(props);

  return (
    <div className="dc-col" style={{ gap: 12 }}>
      <SectionCard
        title={tr("子页面设置", "Subpage settings")}
        description={tr(
          "逐页调整底栏显示、导航页收纳、名称、图标、描述与配色；修改后点右上角「保存」。",
          "Adjust bottom-bar visibility, hub collection, names, icons, descriptions and colors per page; press Save when done.",
        )}
      >
        {NAV_CATALOG.map((cat) => {
          const item = map.get(cat.id)!;
          return (
            <details key={cat.id} className="dc-card" style={{ padding: "10px 12px" }}>
              <summary style={{ cursor: "pointer", fontWeight: 600, display: "flex", alignItems: "center", gap: 8 }}>
                {tr(cat.zh, cat.en)}
                <span className="dc-muted" style={{ fontSize: "0.82em", fontWeight: 400 }}>
                  {[item.visible ? tr("底栏", "Bottom bar") : null, item.showInMore ? tr("导航页", "Hub") : null]
                    .filter(Boolean).join(" · ")}
                </span>
              </summary>
              <div style={{ marginTop: 10 }}>
                <NavItemEditor item={item} fallbackName={navLabel(draft, cat.id)} onChange={(p) => updateItem(cat.id, p)} showDescription showColors />
              </div>
            </details>
          );
        })}
      </SectionCard>
    </div>
  );
}

// ---------------------------------------------------------------------------
// 底部导航 (§17.15)
// ---------------------------------------------------------------------------

export function BottomNavSection(props: SettingsSectionProps) {
  const { draft, patch } = props;
  const { map, updateItem } = useNavDraft(props);

  const move = (index: number, delta: number) => {
    const target = index + delta;
    if (target < 0 || target >= draft.navItems.length) return;
    if (draft.navItems[index].id === "SETTINGS" || draft.navItems[target].id === "SETTINGS") return;
    const next = [...draft.navItems];
    [next[index], next[target]] = [next[target], next[index]];
    patch({ navItems: next });
  };

  // 默认启动页面：只列出当前可见的导航项（设置始终可选），当前值始终出现。
  const pageOptions: { value: string; label: string }[] = [];
  draft.navItems.filter((n) => n.visible).forEach((n) =>
    pageOptions.push({ value: n.id, label: navLabel(draft, n.id) }));
  if (!pageOptions.some((o) => o.value === "SETTINGS")) {
    pageOptions.push({ value: "SETTINGS", label: navLabel(draft, "SETTINGS") });
  }
  if (!pageOptions.some((o) => o.value === draft.defaultPage)) {
    pageOptions.unshift({ value: draft.defaultPage, label: navLabel(draft, draft.defaultPage) });
  }

  return (
    <div className="dc-col" style={{ gap: 12 }}>
      <SectionCard title={tr("底部导航", "Bottom navigation")}>
        <Toggle
          checked={draft.bottomNavShowLabels}
          onChange={(v) => patch({ bottomNavShowLabels: v })}
          label={<span>{tr("显示文字", "Show labels")}<div className="dc-muted" style={{ fontSize: "0.82em" }}>{tr("关闭后底栏仅显示图标，导航栏占用高度更低。", "Icons only when off; the bar becomes shorter.")}</div></span>}
        />
        <SelectField
          label={tr("默认启动页面", "Default start page")}
          value={draft.defaultPage}
          onChange={(v) => patch({ defaultPage: v })}
          options={pageOptions}
          hint={tr("只列出当前可见的导航项；若把默认页对应的导航项隐藏，默认页会自动切换到第一个可见项。", "Only visible pages are listed; hiding the default page falls back to the first visible one.")}
        />
      </SectionCard>

      <SectionCard
        title={tr("导航项", "Navigation items")}
        description={tr(
          "拖动手柄或使用上移/下移调整顺序；「设置」固定在最后且不可关闭。",
          "Reorder with up/down controls; Settings stays pinned and cannot be turned off.",
        )}
      >
        <div className="dc-col" style={{ gap: 6 }}>
          {draft.navItems.map((item, index) => {
            const cat = catalogOf(item.id);
            const merged = map.get(item.id) ?? item;
            return (
              <div key={item.id} className="dc-row dc-card" style={{ padding: "8px 10px", gap: 8, flexWrap: "wrap" }}>
                <MoveButtons
                  onMove={(d) => move(index, d)}
                  upDisabled={index === 0 || item.id === "SETTINGS"}
                  downDisabled={index === draft.navItems.length - 1 || item.id === "SETTINGS"}
                />
                <div className="dc-grow dc-col" style={{ gap: 6, minWidth: 180 }}>
                  <div className="dc-row" style={{ justifyContent: "space-between", gap: 8 }}>
                    <span style={{ fontWeight: 600 }}>{tr(cat.zh, cat.en)}</span>
                    <Toggle
                      checked={merged.visible}
                      disabled={item.id === "SETTINGS"}
                      onChange={(v) => updateItem(item.id, { visible: v })}
                      label={<span style={{ fontSize: "0.88em" }}>{tr("底栏", "Bottom bar")}</span>}
                    />
                  </div>
                  <NavItemEditor
                    item={merged}
                    fallbackName={navLabel(draft, item.id)}
                    labelMaxLength={8}
                    showVisible={false}
                    onChange={(p) => updateItem(item.id, p)}
                  />
                </div>
              </div>
            );
          })}
        </div>
      </SectionCard>
    </div>
  );
}

// ---------------------------------------------------------------------------
// 导航页设置 (§17.16)
// ---------------------------------------------------------------------------

export function MorePageSection(props: SettingsSectionProps) {
  const { draft, patch } = props;
  const { map, updateItem } = useNavDraft(props);

  const order = normalizeMorePageOrder(draft.morePageOrder ?? [], draft.navItems.map((n) => n.id));
  const setOrder = (next: string[]) => patch({ morePageOrder: normalizeMorePageOrder(next, draft.navItems.map((n) => n.id)) });

  const move = (index: number, delta: number) => {
    const target = index + delta;
    if (target < 0 || target >= order.length) return;
    const next = [...order];
    [next[index], next[target]] = [next[target], next[index]];
    setOrder(next);
  };

  return (
    <div className="dc-col" style={{ gap: 12 }}>
      <SectionCard title={tr("页面布局", "Layout")}>
        <Segmented<string>
          value={String(draft.morePageColumns)}
          onChange={(v) => patch({ morePageColumns: Number(v) })}
          options={[
            { value: "1", label: tr("一列", "One column") },
            { value: "2", label: tr("两列", "Two columns") },
            { value: "3", label: tr("三列", "Three columns") },
          ]}
        />
        <Toggle
          checked={draft.morePageShowDescriptions}
          onChange={(v) => patch({ morePageShowDescriptions: v })}
          label={<span>{tr("显示页面描述", "Show page descriptions")}<div className="dc-muted" style={{ fontSize: "0.82em" }}>{tr("关闭后所有导航卡片只显示图标和名称。", "Hides every card description, leaving icons and names.")}</div></span>}
        />
        <div className="dc-muted" style={{ fontSize: "0.84em" }}>
          {tr(
            "导航页按所选列数排列，每列按卡片实际高度连续排列，描述长短不同不会强制留白。",
            "Cards flow by column at their natural height; unequal description lengths never force blank space.",
          )}
        </div>
      </SectionCard>

      <SectionCard
        title={tr("收纳页面", "Collected pages")}
        description={tr(
          "「首页」「导航」和「设置」不会作为卡片出现在导航页。",
          "Home, More and Settings never become cards in the navigation hub.",
        )}
      >
        <div className="dc-col" style={{ gap: 6 }}>
          {order.map((id, index) => {
            const cat = catalogOf(id);
            const item = map.get(id)!;
            return (
              <div key={id} className="dc-row dc-card" style={{ padding: "8px 10px", gap: 8, alignItems: "flex-start", flexWrap: "wrap" }}>
                <MoveButtons onMove={(d) => move(index, d)} upDisabled={index === 0} downDisabled={index === order.length - 1} />
                <div className="dc-grow dc-col" style={{ gap: 8, minWidth: 200 }}>
                  <div className="dc-row" style={{ justifyContent: "space-between", gap: 8 }}>
                    <span style={{ fontWeight: 600 }}>{tr(cat.zh, cat.en)}</span>
                    <Toggle
                      checked={item.showInMore}
                      onChange={(v) => updateItem(id, { showInMore: v })}
                      label={<span style={{ fontSize: "0.88em" }}>{tr("收进导航页", "In hub")}</span>}
                    />
                  </div>
                  {item.showInMore && (
                    <NavItemEditor
                      item={item}
                      fallbackName={navLabel(draft, id)}
                      showVisible={false}
                      showShowInMore={false}
                      showIcon={false}
                      showDescription
                      showColors
                      onChange={(p) => updateItem(id, p)}
                    />
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </SectionCard>
    </div>
  );
}
