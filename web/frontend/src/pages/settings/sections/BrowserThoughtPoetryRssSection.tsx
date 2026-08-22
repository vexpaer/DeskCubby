/**
 * 设置 → 子页面设置：手机使用时间 / 健康 / 浏览器 / 小巧思 / 诗词本 / RSS 订阅
 * (README_for_ai §17.4 §17.5 §17.8 §17.9 §17.10 §17.11)。
 *
 * The shell reuses this ONE component across the related section ids
 * (usage/health/browser/thought/poetry/rss, like AppearanceSection is embedded
 * in general); the active card follows the ?section= query parameter and an
 * unknown id renders every card. All edits stay in the shell-owned draft; the
 * top-bar 保存 persists via PUT /api/settings and 恢复默认 resets draft only.
 * 清除浏览记录 is the one immediate server call (DELETE /api/browser/records).
 */
import React, { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Trash2 } from "lucide-react";
import { apiSend } from "../../../api/client";
import type { DarkMode, RssSubscription } from "../../../api/types";
import { tr } from "../../../i18n/tr";
import { ConfirmDialog, ErrorText } from "../../../components/ui";
import { SectionCard, Segmented, SliderRow, TextField, Toggle } from "../SettingsPage";
import type { SettingsSectionProps } from "../SettingsPage";

type CardKey = "usage" | "health" | "browser" | "thought" | "poetry" | "rss";

const SECTION_CARDS: Record<string, CardKey> = {
  usage: "usage",
  health: "health",
  browser: "browser",
  thought: "thought",
  poetry: "poetry",
  rss: "rss",
};

function argbToHex(v: number): string {
  return "#" + ((v & 0xffffff) >>> 0).toString(16).padStart(6, "0");
}

function hexToArgb(hex: string): number {
  const n = parseInt(hex.replace("#", ""), 16);
  if (!Number.isFinite(n)) return 0xff000000 | 0;
  return (0xff000000 | (n & 0xffffff)) | 0;
}

export default function BrowserThoughtPoetryRssSection(props: SettingsSectionProps) {
  const [searchParams] = useSearchParams();
  const active = SECTION_CARDS[searchParams.get("section") ?? ""];

  // Validation reported to the shell so 保存 blocks while invalid.
  const showBrowser = active === undefined || active === "browser";
  const invalid = showBrowser && !props.draft.browserHomeUrl.trim();
  useEffect(() => {
    props.reportInvalid?.(invalid);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [invalid]);

  return (
    <div className="dc-col" style={{ gap: 12 }}>
      {(active === undefined || active === "usage") && <UsageCard {...props} />}
      {(active === undefined || active === "health") && <HealthCard {...props} />}
      {(active === undefined || active === "browser") && <BrowserCard {...props} />}
      {(active === undefined || active === "thought") && <ThoughtCard {...props} />}
      {(active === undefined || active === "poetry") && <PoetryCard {...props} />}
      {(active === undefined || active === "rss") && <RssCard {...props} />}
    </div>
  );
}

// ---------------------------------------------------------------------------
// 手机使用时间设置 (§17.4)
// ---------------------------------------------------------------------------

function UsageCard({ draft, patch }: SettingsSectionProps) {
  return (
    <SectionCard title={tr("手机使用时间设置", "Screen time settings")}>
      <Toggle
        checked={draft.usageTrackingEnabled}
        onChange={(v) => patch({ usageTrackingEnabled: v })}
        label={
          <span>
            {draft.usageTrackingEnabled ? tr("统计已开启", "Tracking on") : tr("统计已关闭", "Tracking off")}
            <div className="dc-muted" style={{ fontSize: "0.82em" }}>
              {tr(
                "修改是否允许 DeskCubby 后续采集使用时间；需点右上角「保存」才生效，关闭后保留已有历史。",
                "Whether DeskCubby may keep collecting screen time; press Save to apply. Existing history is kept when off.",
              )}
            </div>
          </span>
        }
      />
      <div className="dc-muted" style={{ fontSize: "0.84em" }}>
        {tr(
          "Web 端不自动采集，开启后可从使用时间页导入 Android 导出文件。",
          "The web app never collects by itself; once enabled you can import Android export files from the screen-time page.",
        )}
      </div>
    </SectionCard>
  );
}

// ---------------------------------------------------------------------------
// 健康设置 (§17.5)
// ---------------------------------------------------------------------------

function HealthCard({ draft, patch }: SettingsSectionProps) {
  return (
    <SectionCard title={tr("健康设置", "Health settings")}>
      <Toggle
        checked={draft.stepTrackingEnabled}
        onChange={(v) => patch({ stepTrackingEnabled: v })}
        label={
          <span>
            {draft.stepTrackingEnabled ? tr("统计已开启", "Tracking on") : tr("统计已关闭", "Tracking off")}
            <div className="dc-muted" style={{ fontSize: "0.82em" }}>
              {tr(
                "修改是否允许 DeskCubby 后续读取步数、距离和活动热量；需点右上角「保存」才生效，关闭后保留已有历史。",
                "Whether DeskCubby may keep reading steps, distance, and active calories; press Save to apply. History stays when off.",
              )}
            </div>
          </span>
        }
      />
      <div className="dc-muted" style={{ fontSize: "0.84em" }}>
        {tr(
          "Web 端不自动采集，开启后可从健康页导入 Android 导出文件。",
          "The web app never collects by itself; once enabled you can import Android export files from the health page.",
        )}
      </div>
    </SectionCard>
  );
}

// ---------------------------------------------------------------------------
// 浏览器设置 (§17.8)
// ---------------------------------------------------------------------------

function BrowserCard({ draft, patch, snackbar }: SettingsSectionProps) {
  const [clearOpen, setClearOpen] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [clearError, setClearError] = useState<unknown>(null);

  const clearRecords = async () => {
    setClearing(true);
    setClearError(null);
    try {
      await apiSend("/api/browser/records", "DELETE");
      snackbar(tr("已清除浏览记录", "Browsing history cleared"));
    } catch (e) {
      setClearError(e);
    } finally {
      setClearing(false);
      setClearOpen(false);
    }
  };

  return (
    <SectionCard title={tr("浏览器设置", "Browser settings")}>
      <TextField
        label={tr("网址（默认主页）", "URL (default home page)")}
        value={draft.browserHomeUrl}
        maxLength={2048}
        error={!draft.browserHomeUrl.trim()}
        placeholder="https://www.google.com"
        hint={!draft.browserHomeUrl.trim() ? tr("不能为空。", "Required.") : undefined}
        onChange={(v) => patch({ browserHomeUrl: v })}
      />
      <div className="dc-col" style={{ gap: 4 }}>
        <span style={{ fontSize: "0.9em" }}>{tr("浏览器主题", "Browser theme")}</span>
        <Segmented<DarkMode>
          value={draft.browserTheme}
          onChange={(v) => patch({ browserTheme: v })}
          options={[
            { value: "SYSTEM", label: tr("跟随系统", "System") },
            { value: "LIGHT", label: tr("浅色", "Light") },
            { value: "DARK", label: tr("深色", "Dark") },
          ]}
        />
      </div>
      <Toggle
        checked={draft.browserDesktopMode}
        onChange={(v) => patch({ browserDesktopMode: v })}
        label={
          <span>
            {tr("电脑模式", "Desktop mode")}
            <div className="dc-muted" style={{ fontSize: "0.82em" }}>
              {tr("部分站点不支持，可用“在新窗口打开”。", "Some sites do not support it; “open in a new window” still works.")}
            </div>
          </span>
        }
      />
      <div className="dc-row">
        <button className="dc-btn dc-btn-danger" disabled={clearing} onClick={() => setClearOpen(true)}>
          {clearing ? tr("正在清除…", "Clearing…") : tr("清除浏览记录", "Clear browsing history")}
        </button>
      </div>
      <ErrorText error={clearError} />
      <ConfirmDialog
        open={clearOpen}
        title={tr("清除浏览记录？", "Clear browsing history?")}
        message={tr("将删除服务器上保存的全部浏览记录与收藏标记。", "All browsing records and favorite marks stored on the server will be deleted.")}
        confirmLabel={clearing ? tr("正在清除…", "Clearing…") : tr("清除", "Clear")}
        danger
        onCancel={() => setClearOpen(false)}
        onConfirm={() => void clearRecords()}
      />
    </SectionCard>
  );
}

// ---------------------------------------------------------------------------
// 小巧思设置 (§17.9)
// ---------------------------------------------------------------------------

function ThoughtCard({ draft, patch }: SettingsSectionProps) {
  return (
    <SectionCard title={tr("小巧思设置", "Thoughts settings")}>
      <SliderRow
        label={tr("小巧思列表行高", "Thought list row height")}
        value={draft.thoughtRowHeightDp}
        min={48} max={120} step={1}
        format={(v) => `${v} dp`}
        onChange={(v) => patch({ thoughtRowHeightDp: Math.round(v) })}
      />
      <div className="dc-col" style={{ gap: 4 }}>
        <span style={{ fontSize: "0.9em" }}>{tr("重新打开", "Reopen behavior")}</span>
        <Segmented<"LAST_VISITED" | "ALL">
          value={draft.thoughtReopenMode}
          onChange={(v) => patch({ thoughtReopenMode: v })}
          options={[
            { value: "LAST_VISITED", label: tr("最近访问", "Last visited") },
            { value: "ALL", label: tr("全部", "All") },
          ]}
        />
      </div>
      <div className="dc-col" style={{ gap: 4 }}>
        <span style={{ fontSize: "0.9em" }}>{tr("内容显示", "Content display")}</span>
        <Segmented<"SINGLE_LINE" | "FULL">
          value={draft.thoughtDisplayMode}
          onChange={(v) => patch({ thoughtDisplayMode: v })}
          options={[
            { value: "SINGLE_LINE", label: tr("一行显示", "Single line") },
            { value: "FULL", label: tr("完整显示", "Full") },
          ]}
        />
      </div>
      <div className="dc-row" style={{ gap: 10 }}>
        <input
          type="color"
          aria-label={tr("高亮颜色", "Highlight color")}
          value={argbToHex(draft.thoughtHighlightColorArgb)}
          onChange={(e) => patch({ thoughtHighlightColorArgb: hexToArgb(e.target.value) })}
          style={{
            width: 36, height: 28, padding: 0, flexShrink: 0,
            border: "var(--dc-border-width) solid var(--dc-outline-variant)",
            borderRadius: 8, background: "none", cursor: "pointer",
          }}
        />
        <span className="dc-grow">{tr("高亮颜色", "Highlight color")}</span>
        <span className="dc-muted" style={{ fontSize: "0.8em", fontFamily: "monospace" }}>
          {argbToHex(draft.thoughtHighlightColorArgb)}
        </span>
      </div>
      <div className="dc-muted" style={{ fontSize: "0.82em", marginTop: -6 }}>
        {tr("在小巧思页面长按条目可标记为重点。", "Long-press a thought to mark it as highlighted.")}
      </div>
      <SliderRow
        label={tr("输入框最大高度", "Editor max height")}
        value={draft.thoughtEditorMaxHeightDp}
        min={96} max={400} step={4}
        format={(v) => `${v} dp`}
        hint={tr("超过上限后输入框内部滚动。", "The editor scrolls internally past this height.")}
        onChange={(v) => patch({ thoughtEditorMaxHeightDp: Math.round(v) })}
      />
    </SectionCard>
  );
}

// ---------------------------------------------------------------------------
// 诗词本设置 (§17.10)
// ---------------------------------------------------------------------------

function PoetryCard({ draft, patch }: SettingsSectionProps) {
  return (
    <SectionCard
      title={tr("诗词本设置", "Poetry book settings")}
      description={tr(
        "只影响展示样式；字体文件请到诗词本页面导入。",
        "Display only; import font files from inside the poetry book page.",
      )}
    >
      <SliderRow
        label={tr("字号", "Font size")}
        value={draft.poetryFontSizeSp}
        min={14} max={36} step={1}
        format={(v) => `${v} sp`}
        onChange={(v) => patch({ poetryFontSizeSp: Math.round(v) })}
      />
      <SliderRow
        label={tr("行距", "Line spacing")}
        value={draft.poetryLineSpacing}
        min={1} max={2} step={0.05}
        format={(v) => `×${v.toFixed(2)}`}
        onChange={(v) => patch({ poetryLineSpacing: Math.round(v * 100) / 100 })}
      />
      <div className="dc-col" style={{ gap: 4 }}>
        <span style={{ fontSize: "0.9em" }}>{tr("对齐方式", "Text alignment")}</span>
        <Segmented<"START" | "CENTER">
          value={draft.poetryTextAlignment}
          onChange={(v) => patch({ poetryTextAlignment: v })}
          options={[
            { value: "START", label: tr("左对齐", "Left") },
            { value: "CENTER", label: tr("居中", "Centered") },
          ]}
        />
      </div>
      <Toggle
        checked={draft.poetryShowSource}
        onChange={(v) => patch({ poetryShowSource: v })}
        label={<span>{tr("显示来源", "Show source")}</span>}
      />
      <Toggle
        checked={draft.poetryShowQuoteMark}
        onChange={(v) => patch({ poetryShowQuoteMark: v })}
        label={<span>{tr("引号装饰", "Quote decoration")}</span>}
      />
      <Toggle
        checked={draft.poetrySevenCharacterWrapEnabled}
        onChange={(v) => patch({ poetrySevenCharacterWrapEnabled: v })}
        label={
          <span>
            {tr("七言换行", "Seven-character line wrap")}
            <div className="dc-muted" style={{ fontSize: "0.82em" }}>
              {tr(
                "检测到至少两句七言时按每句七个字符连同标点自动分行。",
                "Poems with at least two seven-character lines wrap automatically every seven characters plus punctuation.",
              )}
            </div>
          </span>
        }
      />
    </SectionCard>
  );
}

// ---------------------------------------------------------------------------
// RSS 订阅设置 (§17.11)
// ---------------------------------------------------------------------------

const MAX_FEEDS = 200;

function RssCard({ draft, patch, snackbar }: SettingsSectionProps) {
  const feeds = draft.rssSubscriptions ?? [];
  const [newTitle, setNewTitle] = useState("");
  const [newUrl, setNewUrl] = useState("");

  const addValid = newTitle.trim().length > 0 && newUrl.startsWith("https://");

  const addFeed = () => {
    if (!addValid || feeds.length >= MAX_FEEDS) return;
    const feed: RssSubscription = {
      id: `rss-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
      title: newTitle.trim(),
      url: newUrl.trim(),
      enabled: true,
    };
    patch({ rssSubscriptions: [...feeds, feed] });
    setNewTitle("");
    setNewUrl("");
    snackbar(tr("已添加订阅，点右上角「保存」生效", "Subscription added; press Save to apply"));
  };

  const updateFeed = (index: number, p: Partial<RssSubscription>) =>
    patch({ rssSubscriptions: feeds.map((f, i) => (i === index ? { ...f, ...p } : f)) });
  const removeFeed = (index: number) =>
    patch({ rssSubscriptions: feeds.filter((_, i) => i !== index) });

  return (
    <SectionCard
      title={tr("RSS 订阅设置", "RSS settings")}
      description={tr(
        "订阅源本身在 RSS 页面右上角添加和管理。",
        "Feeds themselves are managed from the RSS page's top bar.",
      )}
    >
      <SliderRow
        label={tr("每个订阅最多显示", "Items per feed")}
        value={draft.rssMaxItemsPerFeed}
        min={10} max={200} step={5}
        format={(v) => tr(`${v} 篇`, `${v} items`)}
        onChange={(v) => patch({ rssMaxItemsPerFeed: Math.round(v) })}
      />
      <Toggle
        checked={draft.rssShowSummaries}
        onChange={(v) => patch({ rssShowSummaries: v })}
        label={<span>{tr("显示摘要", "Show summaries")}<div className="dc-muted" style={{ fontSize: "0.82em" }}>{tr("关闭后列表只保留标题、订阅名和时间。", "Off keeps only titles, feed names, and times.")}</div></span>}
      />

      <div className="dc-col" style={{ gap: 6 }}>
        <span style={{ fontSize: "0.9em", fontWeight: 600 }}>{tr("订阅列表", "Subscriptions")}</span>
        {feeds.length === 0 && (
          <div className="dc-muted" style={{ fontSize: "0.86em" }}>{tr("还没有订阅。", "No subscriptions yet.")}</div>
        )}
        {feeds.map((feed, index) => (
          <div key={feed.id || index} className="dc-row dc-card" style={{ padding: "8px 10px", gap: 8, alignItems: "flex-start" }}>
            <input
              type="checkbox"
              aria-label={`${tr("启用", "Enable")} ${feed.title}`}
              checked={feed.enabled}
              onChange={(e) => updateFeed(index, { enabled: e.target.checked })}
              style={{ accentColor: "var(--dc-primary)", width: 16, height: 16, marginTop: 10, flexShrink: 0 }}
            />
            <div className="dc-grow dc-col" style={{ gap: 6, minWidth: 0 }}>
              <input
                className="dc-input" value={feed.title} maxLength={256}
                placeholder={tr("标题", "Title")} aria-label={`${tr("标题", "Title")} ${index + 1}`}
                onChange={(e) => updateFeed(index, { title: e.target.value })}
              />
              <input
                className="dc-input" value={feed.url} maxLength={2048}
                placeholder="https://example.com/feed.xml" aria-label={`URL ${index + 1}`}
                onChange={(e) => updateFeed(index, { url: e.target.value })}
              />
            </div>
            <button
              className="dc-icon-btn" style={{ width: 32, height: 32, flexShrink: 0 }}
              aria-label={tr("删除这条订阅", "Delete this subscription")}
              onClick={() => removeFeed(index)}
            >
              <Trash2 size={16} />
            </button>
          </div>
        ))}
      </div>

      <div className="dc-col" style={{ gap: 6 }}>
        <span style={{ fontSize: "0.9em", fontWeight: 600 }}>{tr("添加订阅", "Add subscription")}</span>
        <input
          className="dc-input" value={newTitle} maxLength={256}
          placeholder={tr("标题", "Title")} aria-label={tr("新订阅标题", "New subscription title")}
          onChange={(e) => setNewTitle(e.target.value)}
        />
        <input
          className="dc-input" value={newUrl} maxLength={2048}
          placeholder="https://example.com/feed.xml" aria-label={tr("新订阅地址", "New subscription URL")}
          onChange={(e) => setNewUrl(e.target.value)}
        />
        {!addValid && (newTitle || newUrl) && (
          <div className="dc-muted" style={{ fontSize: "0.82em", color: "var(--dc-error)" }}>
            {tr("需要标题，且地址必须以 https:// 开头。", "A title is required and the URL must start with https://.")}
          </div>
        )}
        <div className="dc-row">
          <button className="dc-btn dc-btn-tonal" disabled={!addValid} onClick={addFeed}>
            {tr("添加", "Add")}
          </button>
        </div>
      </div>
    </SectionCard>
  );
}
