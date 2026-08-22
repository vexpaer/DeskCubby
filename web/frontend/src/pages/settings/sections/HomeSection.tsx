/**
 * 设置 → 子页面设置 → 主页 (README_for_ai §17.6)：主页模块、显示模块边框、
 * 小游戏快捷入口与主页问候。All edits stay in the shell-owned draft; the top-bar
 * 保存 button persists via PUT /api/settings.
 *
 * Module catalog = home-module portion of DESKTOP_WIDGET_HOME_MODULE_IDS
 * (AppModels.kt). Legacy home-widget ids `cloud_sync_now` / `cloud_sync_force`
 * are normalized to the combined `cloud_sync`, mirroring
 * normalizeDesktopWidgetHomeModuleId; game shortcuts keep
 * normalizeHomeGameShortcutIds ordering (canonical order, unknown ids dropped).
 */
import React from "react";
import type { HomeGreetingTemplate } from "../../../api/types";
import { tr } from "../../../i18n/tr";
import { SectionCard, Toggle } from "../SettingsPage";
import type { SettingsSectionProps } from "../SettingsPage";

// ---------------------------------------------------------------------------
// Catalogs (mirror AppModels.kt)
// ---------------------------------------------------------------------------

const MODULE_CATALOG: { id: string; zh: string; en: string }[] = [
  { id: "calendar", zh: "日历", en: "Calendar" },
  { id: "weather", zh: "天气缓存", en: "Weather cache" },
  { id: "poem", zh: "每日诗词", en: "Daily poem" },
  { id: "today", zh: "今天日期", en: "Today" },
  { id: "date_records", zh: "日期记录", en: "Date records" },
  { id: "streak", zh: "连续记录天数", en: "Writing streak" },
  { id: "month_diaries", zh: "本月日记数量", en: "Diaries this month" },
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
  { id: "cloud_sync", zh: "云端同步", en: "Cloud sync" },
];

const MODULE_BY_ID = new Map(MODULE_CATALOG.map((m) => [m.id, m]));

/** Legacy ids collapse onto the combined cloud-sync module. */
function normalizeHomeWidgetId(raw: string): string | null {
  if (raw === "cloud_sync_now" || raw === "cloud_sync_force") return "cloud_sync";
  return MODULE_BY_ID.has(raw) ? raw : null;
}

const GAME_SHORTCUT_ORDER = ["2048", "2048_5", "2048_6", "snake", "tetris", "minesweeper", "spider", "go"];

const GAME_SHORTCUT_LABELS: Record<string, [string, string]> = {
  "2048": ["2048 · 4×4", "2048 · 4×4"],
  "2048_5": ["2048 · 5×5", "2048 · 5×5"],
  "2048_6": ["2048 · 6×6", "2048 · 6×6"],
  snake: [tr("贪吃蛇", "Snake"), tr("贪吃蛇", "Snake")],
  tetris: [tr("俄罗斯方块", "Tetris"), tr("俄罗斯方块", "Tetris")],
  minesweeper: [tr("扫雷", "Minesweeper"), tr("扫雷", "Minesweeper")],
  spider: [tr("蜘蛛纸牌", "Spider solitaire"), tr("蜘蛛纸牌", "Spider solitaire")],
  go: [tr("围棋", "Go"), tr("围棋", "Go")],
};

/** Mirrors DEFAULT_HOME_GREETINGS in AppModels.kt (24 bilingual templates). */
export const DEFAULT_HOME_GREETINGS: HomeGreetingTemplate[] = [
  { chinese: "今天从这里开始", english: "Start here today" },
  { chinese: "看看今天的安排", english: "Check today's plan" },
  { chinese: "有想法就记下来", english: "Write down what's on your mind" },
  { chinese: "先完成一件小事", english: "Start with one small task" },
  { chinese: "今天想写点什么？", english: "What would you like to write today?" },
  { chinese: "看看最近的记录", english: "Review your recent notes" },
  { chinese: "先处理重要的事", english: "Start with what matters" },
  { chinese: "打开日历看看", english: "Take a look at the calendar" },
  { chinese: "记录一下当前状态", english: "Record where things stand" },
  { chinese: "今天的进度怎么样？", english: "How is today going?" },
  { chinese: "先快速记一条", english: "Add a quick note" },
  { chinese: "看看时间都去哪了", english: "See where the time went" },
  { chinese: "今天走了多少步？", english: "How many steps today?" },
  { chinese: "查看新的订阅", english: "Check the latest feeds" },
  { chinese: "整理一下当前思路", english: "Organize your current thoughts" },
  { chinese: "从最简单的事开始", english: "Begin with the simplest thing" },
  { chinese: "该记录今天了", english: "Time to record today" },
  { chinese: "翻翻过去写的内容", english: "Browse something you wrote before" },
  { chinese: "现在要做什么？", english: "What comes next?" },
  { chinese: "先看一眼今日数据", english: "Check today's numbers" },
  { chinese: "把刚才的想法留下", english: "Keep that thought before it slips away" },
  { chinese: "今天也按计划推进", english: "Keep today's plan moving" },
  { chinese: "检查一下重要日期", english: "Check the important dates" },
  { chinese: "{name}，欢迎回来", english: "Welcome back, {name}" },
];

const MAX_GREETINGS = 100;
const MAX_GREETING_CHARS = 40;

// ---------------------------------------------------------------------------
// Section
// ---------------------------------------------------------------------------

export default function HomeSection({ draft, patch }: SettingsSectionProps) {
  // Normalized view of the selected home modules (legacy ids folded, deduped,
  // order preserved).
  const selected: string[] = [];
  (draft.homeWidgets ?? []).forEach((raw) => {
    const id = normalizeHomeWidgetId(raw);
    if (id && !selected.includes(id)) selected.push(id);
  });

  const setSelected = (next: string[]) =>
    patch({ homeWidgets: Array.from(new Set(next.filter((id) => MODULE_BY_ID.has(id)))) });

  const moveModule = (index: number, delta: number) => {
    const target = index + delta;
    if (target < 0 || target >= selected.length) return;
    const next = [...selected];
    [next[index], next[target]] = [next[target], next[index]];
    setSelected(next);
  };

  const available = MODULE_CATALOG.filter((m) => !selected.includes(m.id));

  const gameSet = new Set(draft.homeGameShortcuts ?? []);
  const toggleGame = (id: string, on: boolean) => {
    const next = new Set(gameSet);
    if (on) next.add(id);
    else next.delete(id);
    // normalizeHomeGameShortcutIds semantics: canonical order, members only.
    patch({ homeGameShortcuts: GAME_SHORTCUT_ORDER.filter((id2) => next.has(id2)) });
  };

  const greetings = draft.homeGreetings ?? [];
  const updateGreeting = (index: number, key: keyof HomeGreetingTemplate, value: string) =>
    patch({ homeGreetings: greetings.map((g, i) => (i === index ? { ...g, [key]: value } : g)) });
  const removeGreeting = (index: number) =>
    patch({ homeGreetings: greetings.filter((_, i) => i !== index) });
  const addGreeting = () =>
    patch({ homeGreetings: [...greetings, { chinese: "", english: "" }] });

  return (
    <div className="dc-col" style={{ gap: 12 }}>
      <SectionCard title={tr("主页模块", "Home widgets")}>
        <Toggle
          checked={draft.homeWidgetBordersEnabled}
          onChange={(v) => patch({ homeWidgetBordersEnabled: v })}
          label={
            <span>
              {tr("显示模块边框", "Show widget borders")}
              <div className="dc-muted" style={{ fontSize: "0.82em" }}>
                {tr("关闭后主页模块会更自然地连成一体。", "Modules blend together more naturally when off.")}
              </div>
            </span>
          }
        />
        {selected.length === 0 ? (
          <div className="dc-muted" style={{ fontSize: "0.88em" }}>{tr("还没有添加主页模块。", "No home widgets yet.")}</div>
        ) : (
          <div className="dc-col" style={{ gap: 4 }}>
            {selected.map((id, index) => {
              const m = MODULE_BY_ID.get(id);
              return (
                <div key={id} className="dc-row dc-card" style={{ padding: "6px 10px", gap: 8 }}>
                  <span className="dc-grow" style={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                    {m ? tr(m.zh, m.en) : id}
                  </span>
                  <button className="dc-icon-btn" style={{ width: 32, height: 32 }} disabled={index === 0}
                    aria-label={tr("上移", "Move up")} onClick={() => moveModule(index, -1)}>↑</button>
                  <button className="dc-icon-btn" style={{ width: 32, height: 32 }} disabled={index === selected.length - 1}
                    aria-label={tr("下移", "Move down")} onClick={() => moveModule(index, 1)}>↓</button>
                  <button className="dc-btn" style={{ padding: "4px 10px", fontSize: "0.85em" }}
                    onClick={() => setSelected(selected.filter((x) => x !== id))}>
                    {tr("移除", "Remove")}
                  </button>
                </div>
              );
            })}
          </div>
        )}
        <div className="dc-col" style={{ gap: 6 }}>
          <span style={{ fontSize: "0.9em", fontWeight: 600 }}>{tr("添加模块", "Add widgets")}</span>
          {available.length === 0 ? (
            <div className="dc-muted" style={{ fontSize: "0.86em" }}>{tr("所有模块都已添加。", "All widgets have been added.")}</div>
          ) : (
            <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
              {available.map((m) => (
                <button key={m.id} className="dc-chip" onClick={() => setSelected([...selected, m.id])}>
                  {tr(m.zh, m.en)}
                </button>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      <SectionCard
        title={tr("小游戏快捷入口", "Game shortcuts")}
        description={tr(
          "控制主页「小游戏」模块内显示的入口；允许全部取消。",
          "Choose the entries shown in the home mini-games widget; all may be unchecked.",
        )}
      >
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(150px, 1fr))", gap: 6 }}>
          {GAME_SHORTCUT_ORDER.map((id) => {
            const label = GAME_SHORTCUT_LABELS[id];
            return (
              <label key={id} className="dc-row" style={{ gap: 8, cursor: "pointer", fontSize: "0.92em" }}>
                <input
                  type="checkbox"
                  checked={gameSet.has(id)}
                  onChange={(e) => toggleGame(id, e.target.checked)}
                  style={{ accentColor: "var(--dc-primary)", width: 16, height: 16 }}
                />
                {tr(label[0], label[1])}
              </label>
            );
          })}
        </div>
      </SectionCard>

      <SectionCard
        title={tr("主页问候", "Home greeting")}
        description={tr(
          "支持 {name} 占位符；单个语言最多 40 个字符，至少填写其中一种语言。",
          "Supports the {name} placeholder; each language is limited to 40 characters and at least one is required.",
        )}
      >
        {greetings.length === 0 && (
          <div className="dc-muted" style={{ fontSize: "0.88em" }}>
            {tr("没有问候语时主页显示「今日概览」。", "With no greetings the home page shows “Today's overview”.")}
          </div>
        )}
        <div className="dc-col" style={{ gap: 8 }}>
          {greetings.map((g, i) => (
            <div key={i} className="dc-row dc-card" style={{ padding: "8px 10px", gap: 8, alignItems: "flex-start" }}>
              <div className="dc-grow dc-col" style={{ gap: 6, minWidth: 0 }}>
                <input
                  className="dc-input" value={g.chinese} maxLength={MAX_GREETING_CHARS}
                  placeholder={tr("中文", "Chinese")} aria-label={`${tr("问候语", "Greeting")} ${i + 1} ${tr("中文", "Chinese")}`}
                  onChange={(e) => updateGreeting(i, "chinese", e.target.value)}
                />
                <input
                  className="dc-input" value={g.english} maxLength={MAX_GREETING_CHARS}
                  placeholder="English" aria-label={`${tr("问候语", "Greeting")} ${i + 1} English`}
                  onChange={(e) => updateGreeting(i, "english", e.target.value)}
                />
              </div>
              <button className="dc-icon-btn" style={{ width: 32, height: 32, flexShrink: 0 }}
                aria-label={tr("删除这条问候语", "Delete this greeting")}
                onClick={() => removeGreeting(i)}>
                🗑
              </button>
            </div>
          ))}
        </div>
        <div className="dc-row dc-wrap">
          <button className="dc-btn dc-btn-tonal" disabled={greetings.length >= MAX_GREETINGS} onClick={addGreeting}>
            {tr("增加问候语", "Add greeting")}
          </button>
          <button className="dc-btn" onClick={() => patch({ homeGreetings: DEFAULT_HOME_GREETINGS.map((g) => ({ ...g })) })}>
            {tr("恢复默认问候", "Restore default greetings")}
          </button>
          {greetings.length >= MAX_GREETINGS && (
            <span className="dc-muted" style={{ fontSize: "0.82em" }}>{tr("最多 100 条。", "Up to 100 greetings.")}</span>
          )}
        </div>
      </SectionCard>
    </div>
  );
}
