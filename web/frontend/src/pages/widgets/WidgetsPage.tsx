/**
 * 小卡片 WidgetsPage (/desktop_widgets) — faithful web replica of the Android
 * 桌面小卡片设计器 (README_for_ai.md「桌面小卡片（Android）」):
 * designs list + designer dialog with every DesktopWidgetConfig field and bound,
 * live scaled preview; persisted into settings.desktopWidgetConfigs so Android
 * 端可同步/迁移同一份配置。系统桌面添加仍需在 Android 端操作。
 */
import React, { useMemo, useState } from "react";
import { Copy, MoreVertical, Pencil, Plus, Trash2 } from "lucide-react";
import { argbToCss } from "../../api/types";
import type { DesktopWidgetConfig } from "../../api/types";
import { tr } from "../../i18n/tr";
import { useSettings } from "../../stores/settings";
import { ConfirmDialog, EmptyState, Modal, PageTutorialOverlay, PopupMenu, Snackbar, TopBar, useSnackbar } from "../../components/ui";

// Labels mirror DesktopWidgetsScreen.kt homeModuleLabel (games annotated with
// 桌面直接玩 / 横屏 / 本地双人 like Android; usage_apps & cloud_sync verbatim).
const HOME_MODULE_CATALOG: { id: string; zh: string; en: string }[] = [
  { id: "calendar", zh: "日历", en: "Calendar" },
  { id: "weather", zh: "天气缓存", en: "Weather cache" },
  { id: "poem", zh: "每日诗词", en: "Daily poem" },
  { id: "today", zh: "今天日期", en: "Today" },
  { id: "date_records", zh: "日期记录", en: "Date records" },
  { id: "streak", zh: "连续记录天数", en: "Streak days" },
  { id: "month_diaries", zh: "本月日记数量", en: "Month diaries" },
  { id: "total_words", zh: "日记总字数", en: "Total diary words" },
  { id: "recent_diary", zh: "最近日记", en: "Recent diary" },
  { id: "recent_thought", zh: "最近小巧思", en: "Recent thought" },
  { id: "quick_input", zh: "快速输入", en: "Quick input" },
  { id: "daily_records", zh: "日常记录", en: "Daily records" },
  { id: "meal_photos", zh: "饮食图片", en: "Meal photos" },
  { id: "random_diary", zh: "随机旧日记", en: "Random old diary" },
  { id: "year_progress", zh: "年度进度", en: "Year progress" },
  { id: "website", zh: "网站快捷入口", en: "Website shortcut" },
  { id: "notes", zh: "笔记", en: "Notes" },
  { id: "game_shortcuts", zh: "小游戏", en: "Mini games" },
  { id: "record_overview", zh: "记录概览", en: "Record overview" },
  { id: "game_2048", zh: "2048（桌面直接玩）", en: "2048" },
  { id: "game_2048_5", zh: "2048 五阶", en: "2048 5×5" },
  { id: "game_2048_6", zh: "2048 六阶", en: "2048 6×6" },
  { id: "game_snake", zh: "贪吃蛇（桌面直接玩）", en: "Snake" },
  { id: "game_tetris", zh: "俄罗斯方块（桌面直接玩）", en: "Tetris" },
  { id: "game_minesweeper", zh: "扫雷（桌面直接玩）", en: "Minesweeper" },
  { id: "game_spider", zh: "蜘蛛纸牌（横屏）", en: "Spider Solitaire" },
  { id: "game_go", zh: "围棋（本地双人）", en: "Go" },
  { id: "music_visualizer", zh: "音乐可视化", en: "Music visualizer" },
  { id: "reader", zh: "阅读", en: "Reader" },
  { id: "usage_overview", zh: "使用时间总览", en: "Screen time overview" },
  { id: "usage_chart", zh: "使用时间图表", en: "Screen time chart" },
  { id: "usage_apps", zh: "使用时间应用排行", en: "Top apps by usage" },
  { id: "cloud_sync", zh: "云端同步（合并）", en: "Cloud sync (combined)" },
];

function moduleLabel(id: string): string {
  const found = HOME_MODULE_CATALOG.find((m) => m.id === id);
  if (!found) return tr("主页模块", "Home module");
  return tr(found.zh, found.en);
}

function argbToHex(argb: number): string {
  const rgb = argb & 0xffffff;
  return `#${rgb.toString(16).padStart(6, "0")}`;
}

function hexToArgb(hex: string): number {
  const v = hex.replace("#", "");
  const n = parseInt(v, 16);
  if (Number.isNaN(n)) return 0xff263238;
  return (0xff << 24 | n) | 0;
}

function defaultConfig(): DesktopWidgetConfig {
  return {
    id: `widget-${Date.now()}-${Math.floor(Math.random() * 1e4)}`,
    name: tr("新设计", "New design"),
    widthCells: 2,
    heightCells: 1,
    backgroundColorArgb: -15461322,
    textColorArgb: -1,
    backgroundImageUri: null,
    showName: true,
    backgroundOpacityPercent: 100,
    showIcon: true,
    textAlignment: "START",
    textScalePercent: 100,
    cornerStyle: "ROUNDED",
    surfaceScalePercent: 100,
    appIconScalePercent: 100,
    contentType: "HOME_MODULE",
    homeModuleId: "today",
    appPackageName: null,
    appLabel: null,
    usageRangeDays: 7,
  };
}

function Stepper(props: { label: string; value: number; min: number; max: number; onChange: (v: number) => void }) {
  return (
    <div className="dc-row" style={{ justifyContent: "space-between" }}>
      <span>{props.label}</span>
      <div className="dc-row">
        <button className="dc-icon-btn" style={{ width: 30, height: 30 }} disabled={props.value <= props.min}
          onClick={() => props.onChange(Math.max(props.min, props.value - 1))}>−</button>
        <span style={{ minWidth: 24, textAlign: "center" }}>{props.value}</span>
        <button className="dc-icon-btn" style={{ width: 30, height: 30 }} disabled={props.value >= props.max}
          onClick={() => props.onChange(Math.min(props.max, props.value + 1))}>+</button>
      </div>
    </div>
  );
}

function SliderRow(props: { label: string; value: number; min: number; max: number; step?: number; suffix?: string; onChange: (v: number) => void }) {
  return (
    <label className="dc-col" style={{ gap: 2 }}>
      <span className="dc-row" style={{ justifyContent: "space-between" }}>
        <span>{props.label}</span>
        <span className="dc-muted">{props.value}{props.suffix ?? ""}</span>
      </span>
      <input type="range" min={props.min} max={props.max} step={props.step ?? 1} value={props.value}
        onChange={(e) => props.onChange(Number(e.target.value))} />
    </label>
  );
}

function WidgetPreview({ c }: { c: DesktopWidgetConfig }) {
  const isUsage = c.homeModuleId.startsWith("usage_");
  const title = c.contentType === "APP_SHORTCUT"
    ? (c.appLabel || tr("应用", "App"))
    : moduleLabel(c.homeModuleId);
  return (
    <div className="dc-center" style={{ padding: "14px 0" }}>
      <div style={{
        width: 180, height: 90 * (c.heightCells / Math.max(1, c.widthCells / 2)),
        background: argbToCss(c.backgroundColorArgb),
        opacity: c.backgroundOpacityPercent / 100,
        borderRadius: c.cornerStyle === "ROUNDED" ? 18 : 0,
        color: argbToCss(c.textColorArgb),
        padding: 10,
        display: "flex", flexDirection: "column",
        alignItems: c.textAlignment === "CENTER" ? "center" : c.textAlignment === "END" ? "flex-end" : "flex-start",
        justifyContent: "center", gap: 4,
        fontSize: `${13 * (c.textScalePercent / 100)}px`,
        transform: `scale(${c.surfaceScalePercent / 100})`, transformOrigin: "center",
        overflow: "hidden",
      }}>
        {(c.showIcon && c.contentType === "APP_SHORTCUT") && (
          <div style={{ fontSize: `${22 * (c.appIconScalePercent / 100)}px` }}>{title.slice(0, 2)}</div>
        )}
        {c.showName && <strong>{title}</strong>}
        {isUsage && (
          <div style={{ display: "flex", gap: 3, alignItems: "flex-end", height: 26 }}>
            {[40, 65, 50, 80, 60].map((h, i) => (
              <div key={i} style={{ width: 10, height: `${h}%`, background: argbToCss(c.textColorArgb), opacity: 0.75 }} />
            ))}
          </div>
        )}
        {!isUsage && c.contentType !== "APP_SHORTCUT" && (
          <span style={{ opacity: 0.7, fontSize: "0.85em" }}>{c.widthCells}×{c.heightCells}</span>
        )}
      </div>
    </div>
  );
}

export default function WidgetsPage() {
  const settings = useSettings((s) => s.settings);
  const update = useSettings((s) => s.update);
  const [snack, showSnack] = useSnackbar();
  const [editing, setEditing] = useState<DesktopWidgetConfig | null>(null);
  const [deleting, setDeleting] = useState<DesktopWidgetConfig | null>(null);
  const [menu, setMenu] = useState<{ x: number; y: number; config: DesktopWidgetConfig } | null>(null);

  const configs = settings?.desktopWidgetConfigs ?? [];

  const persist = async (next: DesktopWidgetConfig[]) => {
    try {
      await update({ desktopWidgetConfigs: next });
    } catch (e) {
      showSnack(e instanceof Error ? e.message : tr("保存失败", "Save failed"));
    }
  };

  const saveEditing = () => {
    if (!editing) return;
    const exists = configs.some((c) => c.id === editing.id);
    const next = exists ? configs.map((c) => (c.id === editing.id ? editing : c)) : [...configs, editing];
    void persist(next);
    setEditing(null);
    showSnack(tr("已保存设计", "Design saved"));
  };

  const contentLabel = (t: DesktopWidgetConfig["contentType"]): string =>
    t === "HOME_MODULE" ? tr("主页模块", "Home module")
      : t === "APP_MODULE" ? tr("应用模块", "App module")
        : tr("应用快捷方式", "App shortcut");

  const titleOf = (c: DesktopWidgetConfig): string =>
    c.contentType === "APP_SHORTCUT" ? (c.appLabel || tr("应用", "App")) : moduleLabel(c.homeModuleId);

  return (
    <div>
      <TopBar
        title={tr("小卡片", "Widgets")}
        actions={
          <button className="dc-btn dc-btn-filled" onClick={() => setEditing(defaultConfig())}>
            <Plus size={17} /> {tr("新建设计", "New design")}
          </button>
        }
      />
      <div className="dc-muted" style={{ marginBottom: 10 }}>
        {tr(
          "每个设计可绑定一个桌面小卡片实例；宽高均可在 1–6 格之间自定义。",
          "Each design can be bound to a home-screen widget instance; width and height are customizable between 1–6 cells."
        )}
      </div>
      {configs.length === 0 ? (
        <EmptyState
          title={tr("还没有小卡片", "No widget designs yet")}
          hint={tr("点击右上角“新建设计”创建第一个小卡片。", "Tap “New design” to create your first widget.")}
        />
      ) : (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(240px, 1fr))", gap: 12 }}>
          {configs.map((c) => (
            <div key={c.id} className="dc-card dc-row" style={{ padding: 12, alignItems: "flex-start", position: "relative" }}>
              <WidgetPreview c={c} />
              <div className="dc-grow">
                <div style={{ fontWeight: 600 }}>{c.name}</div>
                <div className="dc-muted" style={{ fontSize: "0.85em" }}>
                  {contentLabel(c.contentType)} · {titleOf(c)} · {c.widthCells}×{c.heightCells}
                </div>
              </div>
              <button
                className="dc-icon-btn"
                aria-label={tr("更多操作", "More actions")}
                onClick={(e) => setMenu({ x: e.clientX - 140, y: e.clientY + 8, config: c })}
              >
                <MoreVertical size={18} />
              </button>
            </div>
          ))}
        </div>
      )}

      <PopupMenu
        open={menu !== null}
        onClose={() => setMenu(null)}
        x={menu?.x ?? 0}
        y={menu?.y ?? 0}
        items={[
          { label: <><Pencil size={15} /> {tr("编辑", "Edit")}</>, onClick: () => menu && setEditing({ ...menu.config }) },
          {
            label: <><Copy size={15} /> {tr("复制设计", "Duplicate design")}</>,
            onClick: () => {
              if (!menu) return;
              const copy = { ...menu.config, id: `widget-${Date.now()}-${Math.floor(Math.random() * 1e4)}`, name: `${menu.config.name} · ${tr("副本", "copy")}` };
              void persist([...configs, copy]);
            },
          },
          {
            label: <span style={{ color: "var(--dc-error)" }}><Trash2 size={15} /> {tr("删除", "Delete")}</span>,
            onClick: () => menu && setDeleting(menu.config),
          },
        ]}
      />

      <Modal open={editing !== null} onClose={() => setEditing(null)} title={tr("编辑小卡片设计", "Edit widget design")} width={720}>
        {editing && (
          <div className="dc-col" style={{ gap: 12 }}>
            <WidgetPreview c={editing} />
            <label className="dc-col" style={{ gap: 2 }}>
              <span>{tr("名称", "Name")}</span>
              <input className="dc-input" value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} />
            </label>
            <Stepper label={tr("宽度（格）", "Width (cells)")} value={editing.widthCells} min={1} max={6}
              onChange={(v) => setEditing({ ...editing, widthCells: v })} />
            <Stepper label={tr("高度（格）", "Height (cells)")} value={editing.heightCells} min={1} max={6}
              onChange={(v) => setEditing({ ...editing, heightCells: v })} />
            <label className="dc-col" style={{ gap: 2 }}>
              <span>{tr("内容类型", "Content type")}</span>
              <select className="dc-input" value={editing.contentType}
                onChange={(e) => setEditing({ ...editing, contentType: e.target.value as DesktopWidgetConfig["contentType"] })}>
                <option value="HOME_MODULE">{tr("主页模块", "Home module")}</option>
                <option value="APP_MODULE">{tr("应用模块", "App module")}</option>
                <option value="APP_SHORTCUT">{tr("应用快捷方式", "App shortcut")}</option>
              </select>
            </label>
            {editing.contentType !== "APP_SHORTCUT" && (
              <label className="dc-col" style={{ gap: 2 }}>
                <span>{editing.contentType === "APP_MODULE" ? tr("应用模块", "App module") : tr("主页模块", "Home module")}</span>
                <select className="dc-input" value={editing.homeModuleId}
                  onChange={(e) => setEditing({ ...editing, homeModuleId: e.target.value })}>
                  {HOME_MODULE_CATALOG.map((m) => (
                    <option key={m.id} value={m.id}>{tr(m.zh, m.en)}</option>
                  ))}
                </select>
              </label>
            )}
            {editing.contentType === "APP_SHORTCUT" && (
              <>
                <label className="dc-col" style={{ gap: 2 }}>
                  <span>{tr("应用名称", "App name")}</span>
                  <input className="dc-input" value={editing.appLabel ?? ""}
                    onChange={(e) => setEditing({ ...editing, appLabel: e.target.value })} />
                </label>
                <label className="dc-col" style={{ gap: 2 }}>
                  <span>{tr("图标（emoji）", "Icon (emoji)")}</span>
                  <input className="dc-input" value={(editing.appLabel ?? "").slice(0, 2)}
                    onChange={(e) => setEditing({ ...editing, appLabel: e.target.value.slice(0, 2) })} maxLength={2} />
                </label>
              </>
            )}
            <div className="dc-row dc-wrap">
              <label className="dc-col" style={{ gap: 2, flex: 1, minWidth: 160 }}>
                <span>{tr("背景颜色", "Background color")}</span>
                <input type="color" value={argbToHex(editing.backgroundColorArgb)}
                  onChange={(e) => setEditing({ ...editing, backgroundColorArgb: hexToArgb(e.target.value) })} />
              </label>
              <label className="dc-col" style={{ gap: 2, flex: 1, minWidth: 160 }}>
                <span>{tr("文字颜色", "Text color")}</span>
                <input type="color" value={argbToHex(editing.textColorArgb)}
                  onChange={(e) => setEditing({ ...editing, textColorArgb: hexToArgb(e.target.value) })} />
              </label>
            </div>
            <SliderRow label={tr("背景不透明度", "Background opacity")} value={editing.backgroundOpacityPercent} min={0} max={100}
              suffix="%" onChange={(v) => setEditing({ ...editing, backgroundOpacityPercent: v })} />
            <div className="dc-row dc-wrap">
              <label className="dc-row" style={{ gap: 6 }}>
                <input type="checkbox" checked={editing.showName}
                  onChange={(e) => setEditing({ ...editing, showName: e.target.checked })} />
                {tr("显示名称", "Show name")}
              </label>
              <label className="dc-row" style={{ gap: 6 }}>
                <input type="checkbox" checked={editing.showIcon}
                  onChange={(e) => setEditing({ ...editing, showIcon: e.target.checked })} />
                {tr("显示图标", "Show icon")}
              </label>
            </div>
            <label className="dc-col" style={{ gap: 2 }}>
              <span>{tr("文字对齐", "Text alignment")}</span>
              <select className="dc-input" value={editing.textAlignment}
                onChange={(e) => setEditing({ ...editing, textAlignment: e.target.value as DesktopWidgetConfig["textAlignment"] })}>
                <option value="START">{tr("左对齐", "Start")}</option>
                <option value="CENTER">{tr("居中", "Center")}</option>
                <option value="END">{tr("右对齐", "End")}</option>
              </select>
            </label>
            <SliderRow label={tr("文字缩放", "Text scale")} value={editing.textScalePercent} min={75} max={150} step={5}
              suffix="%" onChange={(v) => setEditing({ ...editing, textScalePercent: v })} />
            <label className="dc-col" style={{ gap: 2 }}>
              <span>{tr("圆角样式", "Corner style")}</span>
              <select className="dc-input" value={editing.cornerStyle}
                onChange={(e) => setEditing({ ...editing, cornerStyle: e.target.value as DesktopWidgetConfig["cornerStyle"] })}>
                <option value="ROUNDED">{tr("圆角", "Rounded")}</option>
                <option value="SQUARE">{tr("直角", "Square")}</option>
              </select>
            </label>
            <SliderRow label={tr("整体缩放", "Surface scale")} value={editing.surfaceScalePercent} min={70} max={100}
              suffix="%" onChange={(v) => setEditing({ ...editing, surfaceScalePercent: v })} />
            <SliderRow label={tr("图标缩放", "App icon scale")} value={editing.appIconScalePercent} min={50} max={150}
              suffix="%" onChange={(v) => setEditing({ ...editing, appIconScalePercent: v })} />
            {editing.homeModuleId.startsWith("usage_") && (
              <label className="dc-col" style={{ gap: 2 }}>
                <span>{tr("时间范围", "Range")}</span>
                <select className="dc-input" value={String(editing.usageRangeDays)}
                  onChange={(e) => setEditing({ ...editing, usageRangeDays: Number(e.target.value) })}>
                  <option value="7">7 {tr("天", "days")}</option>
                  <option value="30">30 {tr("天", "days")}</option>
                  <option value="90">90 {tr("天", "days")}</option>
                </select>
              </label>
            )}
            <div className="dc-row" style={{ justifyContent: "flex-end" }}>
              <button className="dc-btn" onClick={() => setEditing(null)}>{tr("取消", "Cancel")}</button>
              <button className="dc-btn dc-btn-filled" onClick={saveEditing}>{tr("保存", "Save")}</button>
            </div>
          </div>
        )}
      </Modal>

      <ConfirmDialog
        open={deleting !== null}
        title={tr("删除这个设计？", "Delete this design?")}
        message={deleting?.name}
        danger
        confirmLabel={tr("删除", "Delete")}
        onCancel={() => setDeleting(null)}
        onConfirm={() => {
          if (deleting) void persist(configs.filter((c) => c.id !== deleting.id));
          setDeleting(null);
          showSnack(tr("已删除设计", "Design deleted"));
        }}
      />
      <Snackbar message={snack} />
      <PageTutorialOverlay
        pageKey="desktop_widgets"
        title={tr("小卡片", "Widgets")}
        lines={[tr("设计保存在设置里，可在 Android 端同步并添加到系统桌面。", "Designs are stored in settings and can sync to Android to be added to the home screen.")]}
      />
    </div>
  );
}
