/**
 * 设置 → 子页面设置 → 日记与媒体 (README_for_ai §17.7)。
 * Draft-based fields persist through the shell's 保存 (PUT /api/settings).
 * The 结构化记录 card talks to the server workspace directly and immediately:
 * PUT /api/structured/day-boundary {hours}, PUT /api/structured/fields {fields[]},
 * POST /api/structured/reindex.
 *
 * Numeric bounds mirror SettingsRepository.decode(): imageMaxWidthDp/imageMaxHeightDp
 * 120–2400, mealImageCompressionQuality 30–95 (step 5), mealCalendarImageMaxHeightDp
 * 80–320 (step 8), markdownHeadingSizesSp 12–48 sp.
 */
import React, { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { apiGet, apiSend } from "../../../api/client";
import { MEAL_CATEGORIES } from "../../../api/types";
import { tr } from "../../../i18n/tr";
import { ErrorText, Spinner } from "../../../components/ui";
import {
  SectionCard, Segmented, SelectField, SliderRow, TextField, Toggle,
} from "../SettingsPage";
import type { SettingsSectionProps } from "../SettingsPage";

const MIN_IMAGE_DP = 120;
const MAX_IMAGE_DP = 2400;
const MIN_HEADING_SP = 12;
const MAX_HEADING_SP = 48;
const DEFAULT_MEAL_ICONS = MEAL_CATEGORIES.map((c) => c.icon as string);

const HEADING_LABELS = ["H1", "H2", "H3", "H4", "H5", "H6"];

const FIELD_TYPE_OPTIONS: { value: string; label: string }[] = [
  { value: "word", label: tr("字数", "Word count") },
  { value: "number", label: tr("数字", "Number") },
  { value: "type", label: tr("分类", "Category") },
  { value: "time", label: tr("时间", "Time") },
  { value: "duration", label: tr("时长", "Duration") },
];

/** Wire shape of `.deskcubby/fields.json` rows; unknown siblings are preserved. */
interface StructuredFieldWire {
  id: string;
  name: string;
  type: string;
  [key: string]: unknown;
}

interface StructuredConfig {
  dayBoundaryHour?: number;
  fields?: StructuredFieldWire[];
}

function NumberField(props: {
  label: React.ReactNode; value: number; min: number; max: number;
  onChange: (v: number) => void; errorText?: string;
}) {
  const [text, setText] = useState(String(props.value));
  useEffect(() => setText(String(props.value)), [props.value]);
  const valid = /^\d+$/.test(text) && Number(text) >= props.min && Number(text) <= props.max;
  return (
    <TextField
      label={props.label}
      value={text}
      error={!valid}
      onChange={(v) => {
        setText(v);
        if (/^\d+$/.test(v)) props.onChange(Number(v));
      }}
      hint={valid ? `${props.min}–${props.max}` : props.errorText ?? `${props.min}–${props.max}`}
    />
  );
}

export default function DiarySection({ draft, patch, snackbar, reportInvalid }: SettingsSectionProps) {
  const navigate = useNavigate();

  // ----- draft validation (blocks the shell's 保存 button) -----
  const icons = DEFAULT_MEAL_ICONS.map((fallback, i) => draft.mealButtonIcons?.[i] ?? fallback);
  const invalid =
    !draft.fileNamePattern.trim() ||
    !draft.imageNamePattern.trim() ||
    !(draft.imageMaxWidthDp >= MIN_IMAGE_DP && draft.imageMaxWidthDp <= MAX_IMAGE_DP) ||
    !(draft.imageMaxHeightDp >= MIN_IMAGE_DP && draft.imageMaxHeightDp <= MAX_IMAGE_DP) ||
    (draft.mealButtonsUseIcons && icons.some((icon) => !icon.trim()));
  useEffect(() => {
    reportInvalid?.(invalid);
  }, [invalid, reportInvalid]);

  const patchHeading = (index: number, value: number) =>
    patch({ markdownHeadingSizesSp: draft.markdownHeadingSizesSp.map((v, i) => (i === index ? value : v)) });

  const patchIcon = (index: number, value: string) => {
    const next = [...icons];
    next[index] = value;
    patch({ mealButtonIcons: next });
  };

  // ----- structured records (immediate persistence, not part of the draft) -----
  const [cfgLoading, setCfgLoading] = useState(true);
  const [cfgError, setCfgError] = useState<unknown>(null);
  const [boundary, setBoundary] = useState(5);
  const [fieldsDraft, setFieldsDraft] = useState<StructuredFieldWire[]>([]);
  const [fieldsDirty, setFieldsDirty] = useState(false);
  const [savingFields, setSavingFields] = useState(false);
  const [reindexing, setReindexing] = useState(false);

  const loadConfig = async () => {
    setCfgLoading(true);
    setCfgError(null);
    try {
      const cfg = await apiGet<StructuredConfig>("/api/structured/config");
      setBoundary(((cfg.dayBoundaryHour ?? 5) + 24) % 24);
      setFieldsDraft((cfg.fields ?? []).map((f) => ({ ...f })));
      setFieldsDirty(false);
    } catch (e) {
      setCfgError(e);
    } finally {
      setCfgLoading(false);
    }
  };

  useEffect(() => {
    void loadConfig();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const changeBoundary = async (hours: number) => {
    const prev = boundary;
    setBoundary(hours);
    try {
      await apiSend("/api/structured/day-boundary", "PUT", { hours });
      snackbar(tr("日界线已更新", "Day boundary updated"));
    } catch (e) {
      setBoundary(prev);
      snackbar(tr("保存失败", "Save failed"));
    }
  };

  const fieldsValid =
    fieldsDraft.length > 0 &&
    fieldsDraft.every((f) => f.id.trim() && f.name.trim()) &&
    new Set(fieldsDraft.map((f) => f.id.trim())).size === fieldsDraft.length;

  const saveFields = async () => {
    if (!fieldsValid) return;
    setSavingFields(true);
    try {
      await apiSend("/api/structured/fields", "PUT", { fields: fieldsDraft });
      setFieldsDirty(false);
      snackbar(tr("字段结构已保存", "Field schema saved"));
    } catch {
      snackbar(tr("保存失败", "Save failed"));
    } finally {
      setSavingFields(false);
    }
  };

  const reindex = async () => {
    setReindexing(true);
    try {
      await apiSend("/api/structured/reindex", "POST");
      snackbar(tr("重建索引完成", "Reindex finished"));
    } catch {
      snackbar(tr("重建索引失败", "Reindex failed"));
    } finally {
      setReindexing(false);
    }
  };

  const updateField = (index: number, key: keyof StructuredFieldWire, value: string) => {
    setFieldsDraft((rows) => rows.map((r, i) => (i === index ? { ...r, [key]: value } : r)));
    setFieldsDirty(true);
  };
  const moveField = (index: number, delta: number) => {
    const target = index + delta;
    if (target < 0 || target >= fieldsDraft.length) return;
    setFieldsDraft((rows) => {
      const next = [...rows];
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
    setFieldsDirty(true);
  };

  return (
    <div className="dc-col" style={{ gap: 12 }}>
      <SectionCard title={tr("文件与模板", "Files & templates")}>
        <TextField
          label={tr("今日日记文件名格式", "Diary file name pattern")}
          value={draft.fileNamePattern}
          onChange={(v) => patch({ fileNamePattern: v })}
          placeholder="yyyy-MM-dd"
          error={!draft.fileNamePattern.trim()}
        />
        <TextField
          label={tr("图片命名格式", "Image name pattern")}
          value={draft.imageNamePattern}
          onChange={(v) => patch({ imageNamePattern: v })}
          placeholder="{date}_{category}_{seq}"
          error={!draft.imageNamePattern.trim()}
        />
        <TextField
          label={tr("默认 Markdown 模板", "Default Markdown template")}
          value={draft.markdownTemplate}
          onChange={(v) => patch({ markdownTemplate: v })}
          multilineRows={4}
          hint={tr("支持 {title} 与 {date} 占位符。", "Supports the {title} and {date} placeholders.")}
        />
      </SectionCard>

      <SectionCard title={tr("图片与标题", "Images & headings")}>
        <div className="dc-row dc-wrap" style={{ alignItems: "flex-start" }}>
          <div className="dc-grow" style={{ minWidth: 160 }}>
            <NumberField
              label={tr("图片最大宽度 dp", "Image max width dp")}
              value={draft.imageMaxWidthDp} min={MIN_IMAGE_DP} max={MAX_IMAGE_DP}
              onChange={(v) => patch({ imageMaxWidthDp: v })}
            />
          </div>
          <div className="dc-grow" style={{ minWidth: 160 }}>
            <NumberField
              label={tr("图片最大高度 dp", "Image max height dp")}
              value={draft.imageMaxHeightDp} min={MIN_IMAGE_DP} max={MAX_IMAGE_DP}
              onChange={(v) => patch({ imageMaxHeightDp: v })}
            />
          </div>
        </div>
        <div className="dc-col" style={{ gap: 10 }}>
          <span style={{ fontSize: "0.9em", fontWeight: 600 }}>
            {tr("Markdown 阅读预览", "Markdown reading preview")}
          </span>
          {HEADING_LABELS.map((h, i) => (
            <SliderRow
              key={h}
              label={
                <span style={{ fontSize: `${draft.markdownHeadingSizesSp[i]}px`, lineHeight: 1.2 }}>
                  {h} {tr("标题预览", "heading preview")}
                </span>
              }
              value={draft.markdownHeadingSizesSp[i]}
              min={MIN_HEADING_SP} max={MAX_HEADING_SP} step={1}
              format={(v) => `${v} sp`}
              onChange={(v) => patchHeading(i, v)}
            />
          ))}
        </div>
      </SectionCard>

      <SectionCard title={tr("饮食图片", "Meal photos")}>
        <Toggle
          checked={draft.mealImageCompressionEnabled}
          onChange={(v) => patch({ mealImageCompressionEnabled: v })}
          label={<span>{tr("自动压缩饮食图片", "Compress meal photos")}</span>}
        />
        <SliderRow
          label={tr("压缩质量", "Compression quality")}
          value={draft.mealImageCompressionQuality}
          min={30} max={95} step={5}
          format={(v) => `${v}%`}
          disabled={!draft.mealImageCompressionEnabled}
          onChange={(v) => patch({ mealImageCompressionQuality: v })}
        />
        <Toggle
          checked={draft.saveOriginalToGallery}
          onChange={(v) => patch({ saveOriginalToGallery: v })}
          label={<span>{tr("保存原图到系统相册", "Save originals to gallery")}</span>}
        />
        <Toggle
          checked={draft.photoLocationEnabled}
          onChange={(v) => patch({ photoLocationEnabled: v })}
          label={
            <span>
              {tr("记录照片拍摄地点", "Record photo location")}
              <div className="dc-muted" style={{ fontSize: "0.82em" }}>
                {tr("Web 端仅记录到媒体目录的 dc-media.json。", "On Web the location is only stored in dc-media.json.")}
              </div>
            </span>
          }
        />
      </SectionCard>

      <SectionCard title={tr("吃历", "Meal calendar")}>
        <SliderRow
          label={tr("图片高度上限", "Image height limit")}
          value={draft.mealCalendarImageMaxHeightDp}
          min={80} max={320} step={8}
          format={(v) => `${v} dp`}
          onChange={(v) => patch({ mealCalendarImageMaxHeightDp: v })}
        />
        <Toggle
          checked={draft.mealCalendarShowCaptions}
          onChange={(v) => patch({ mealCalendarShowCaptions: v })}
          label={<span>{tr("显示餐别文字", "Show meal captions")}</span>}
        />
        <Toggle
          checked={draft.mealCalendarWrapEnabled}
          onChange={(v) => patch({ mealCalendarWrapEnabled: v })}
          label={<span>{tr("图片自动换行", "Wrap photos")}</span>}
        />
        {draft.mealCalendarWrapEnabled && (
          <div className="dc-col" style={{ gap: 4 }}>
            <span style={{ fontSize: "0.9em" }}>{tr("每行图片数量", "Photos per row")}</span>
            <Segmented
              value={draft.mealCalendarPhotosPerRow}
              onChange={(v) => patch({ mealCalendarPhotosPerRow: v })}
              options={[
                { value: "TWO" as const, label: "2" },
                { value: "THREE" as const, label: "3" },
                { value: "SMART" as const, label: tr("2+3 自动", "2+3 auto") },
              ]}
            />
          </div>
        )}
        <div className="dc-col" style={{ gap: 4 }}>
          <span style={{ fontSize: "0.9em" }}>{tr("饮食按钮", "Meal buttons")}</span>
          <Segmented
            value={draft.mealButtonsUseIcons ? "ICONS" : "TEXT"}
            onChange={(v) => patch({ mealButtonsUseIcons: v === "ICONS" })}
            options={[
              { value: "TEXT", label: tr("文字", "Text") },
              { value: "ICONS", label: tr("图标", "Icons") },
            ]}
          />
        </div>
        {draft.mealButtonsUseIcons && (
          <div className="dc-col" style={{ gap: 6 }}>
            {MEAL_CATEGORIES.map((cat, i) => (
              <div key={cat.key} className="dc-row" style={{ gap: 8 }}>
                <span style={{ width: 64, fontSize: "0.9em", flexShrink: 0 }}>{tr(cat.zh, cat.en)}</span>
                <input
                  className="dc-input"
                  value={icons[i]}
                  maxLength={16}
                  aria-label={`${tr(cat.zh, cat.en)}${tr("图标", " icon")}`}
                  style={{ maxWidth: 120, borderColor: icons[i].trim() ? undefined : "var(--dc-error)" }}
                  onChange={(e) => patchIcon(i, e.target.value)}
                />
                {!icons[i].trim() && (
                  <span style={{ color: "var(--dc-error)", fontSize: "0.82em" }}>{tr("图标不能为空", "Icon required")}</span>
                )}
              </div>
            ))}
            <span className="dc-muted" style={{ fontSize: "0.82em" }}>
              {tr("任一图标为空时无法保存；最多 16 个字符。", "Every icon is required to save; up to 16 characters each.")}
            </span>
          </div>
        )}
        <div className="dc-row">
          <button className="dc-btn dc-btn-tonal" onClick={() => navigate("/meals/filter")}>
            {tr("吃历滤镜", "Meal photo filter")}
          </button>
          <span className="dc-muted" style={{ fontSize: "0.82em" }}>
            {tr("在独立页面调整亮度、对比度、饱和度、色温与色调。", "Adjust brightness, contrast, saturation, warmth and tint on its own page.")}
          </span>
        </div>
      </SectionCard>

      <SectionCard
        title={tr("结构化记录", "Structured records")}
        description={tr(
          "日界线与字段结构立即保存到服务器工作区，不经过顶栏「保存」。",
          "The day boundary and field schema save to the server workspace immediately, without the top-bar Save.",
        )}
      >
        {cfgLoading ? (
          <Spinner size={22} />
        ) : (
          <>
            <ErrorText error={cfgError} />
            <SelectField
              label={tr("日记日界线（小时）", "Journal day boundary (hour)")}
              value={String(boundary)}
              onChange={(v) => void changeBoundary(Number(v))}
              options={Array.from({ length: 24 }, (_, h) => ({
                value: String(h),
                label: `${String(h).padStart(2, "0")}:00`,
              }))}
              hint={tr("此后的小时计入下一天，默认 05:00。", "Hours after this count toward the next day; default 05:00.")}
            />
            <div className="dc-col" style={{ gap: 6 }}>
              <span style={{ fontSize: "0.9em", fontWeight: 600 }}>{tr("字段", "Fields")}</span>
              {fieldsDraft.map((f, i) => (
                <div key={i} className="dc-row dc-wrap dc-card" style={{ padding: "6px 8px", gap: 6 }}>
                  <input
                    className="dc-input" value={f.id} maxLength={64} placeholder="id"
                    aria-label={`field ${i + 1} id`} style={{ maxWidth: 130 }}
                    onChange={(e) => updateField(i, "id", e.target.value)}
                  />
                  <input
                    className="dc-input" value={f.name} maxLength={64} placeholder={tr("名称", "name")}
                    aria-label={`field ${i + 1} ${tr("名称", "name")}`} style={{ maxWidth: 150 }}
                    onChange={(e) => updateField(i, "name", e.target.value)}
                  />
                  <select
                    className="dc-input" value={FIELD_TYPE_OPTIONS.some((o) => o.value === f.type) ? f.type : "number"}
                    aria-label={`field ${i + 1} ${tr("类型", "type")}`} style={{ maxWidth: 110 }}
                    onChange={(e) => updateField(i, "type", e.target.value)}
                  >
                    {FIELD_TYPE_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                  </select>
                  <button className="dc-icon-btn" style={{ width: 30, height: 30 }} disabled={i === 0}
                    aria-label={tr("上移", "Move up")} onClick={() => moveField(i, -1)}>↑</button>
                  <button className="dc-icon-btn" style={{ width: 30, height: 30 }} disabled={i === fieldsDraft.length - 1}
                    aria-label={tr("下移", "Move down")} onClick={() => moveField(i, 1)}>↓</button>
                  <button className="dc-icon-btn" style={{ width: 30, height: 30 }} aria-label={tr("删除", "Delete")}
                    onClick={() => { setFieldsDraft((rows) => rows.filter((_, j) => j !== i)); setFieldsDirty(true); }}>×</button>
                </div>
              ))}
              <div className="dc-row dc-wrap">
                <button
                  className="dc-btn dc-btn-tonal"
                  onClick={() => {
                    setFieldsDraft((rows) => [
                      ...rows,
                      { id: `field_${Date.now().toString(36)}`, name: "", type: "number" },
                    ]);
                    setFieldsDirty(true);
                  }}
                >
                  {tr("添加字段", "Add field")}
                </button>
                <button className="dc-btn dc-btn-filled" disabled={!fieldsDirty || !fieldsValid || savingFields} onClick={() => void saveFields()}>
                  {savingFields ? tr("保存中…", "Saving…") : tr("保存字段", "Save fields")}
                </button>
                {fieldsDirty && !fieldsValid && (
                  <span style={{ color: "var(--dc-error)", fontSize: "0.82em" }}>
                    {tr("每个字段都需要唯一的 id 和名称。", "Every field needs a unique id and a name.")}
                  </span>
                )}
              </div>
            </div>
            <div className="dc-row dc-wrap">
              <button className="dc-btn" disabled={reindexing} onClick={() => void reindex()}>
                {reindexing ? tr("正在重建…", "Rebuilding…") : tr("重建索引", "Rebuild index")}
              </button>
              <span className="dc-muted" style={{ fontSize: "0.82em" }}>
                {tr("从日记 Markdown 重新扫描结构化记录。", "Rescans structured records from diary Markdown.")}
              </span>
            </div>
          </>
        )}
        <Toggle
          checked={draft.structuredAutoRecordSleepWake}
          onChange={(v) => patch({ structuredAutoRecordSleepWake: v })}
          label={
            <span>
              {tr("自动记录睡醒", "Auto record sleep/wake")}
              <div className="dc-muted" style={{ fontSize: "0.82em" }}>
                {tr("Web 端无系统用量接口，仅保存设置。", "Web has no system usage feed; the switch is stored only.")}
              </div>
            </span>
          }
        />
      </SectionCard>
    </div>
  );
}
