/**
 * Meal photo filter settings (/meals/filter) — mirrors Android 吃历滤镜页:
 * one global filter switch, live preview on a built-in sample, five sliders
 * (brightness / contrast / saturation / warmth / tint) with Android semantics:
 * brightness -100..+100 (default 0), contrast 0..200 (default 100),
 * saturation 0..200 (default 100), warmth -100..+100 (cold↔warm),
 * tint -100..+100 (green↔magenta). All step 5. Save persists via settings;
 * reset only clears the draft sliders.
 */
import React, { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { RefreshCw, Save } from "lucide-react";
import type { MealPhotoFilterSettings } from "../../api/types";
import { useSettings } from "../../stores/settings";
import { tr } from "../../i18n/tr";
import {
  ConfirmDialog, ErrorText, Modal, PageTutorialOverlay, Snackbar, Spinner, TopBar,
  useDirtyGuard, useSnackbar,
} from "../../components/ui";

/** Percent-integer draft used by the sliders. */
export interface FilterDraft {
  enabled: boolean;
  brightness: number; // -100..100, default 0
  contrast: number; // 0..200, default 100
  saturation: number; // 0..200, default 100
  warmth: number; // -100..100, default 0
  tint: number; // -100..100, default 0
}

export function draftFromSettings(f: MealPhotoFilterSettings): FilterDraft {
  const pct = (v: number) => Math.round((Number.isFinite(v) ? v : 0) * 100);
  return {
    enabled: !!f.enabled,
    brightness: clamp(pct(f.brightness), -100, 100),
    contrast: clamp(pct(f.contrast), 0, 200),
    saturation: clamp(pct(f.saturation), 0, 200),
    warmth: clamp(pct(f.warmth), -100, 100),
    tint: clamp(pct(f.tint), -100, 100),
  };
}

export const DEFAULT_FILTER_DRAFT: FilterDraft = {
  enabled: false, brightness: 0, contrast: 100, saturation: 100, warmth: 0, tint: 0,
};

function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v));
}

function draftToSettings(d: FilterDraft): MealPhotoFilterSettings {
  return {
    enabled: d.enabled,
    brightness: d.brightness / 100,
    contrast: d.contrast / 100,
    saturation: d.saturation / 100,
    warmth: d.warmth / 100,
    tint: d.tint / 100,
  };
}

/** True when any slider deviates from its default (mirrors hasVisibleAdjustment). */
export function filterHasAdjustment(f: MealPhotoFilterSettings): boolean {
  const v = f;
  return v.brightness !== 0 || v.contrast !== 1 || v.saturation !== 1 || v.warmth !== 0 || v.tint !== 0;
}

/**
 * CSS approximation of Android's MealPhotoFilter color matrix:
 * brightness()/contrast()/saturate() map 1:1; warmth uses sepia (warm) or a
 * small hue-rotate toward blue (cold); tint rotates green↔magenta.
 */
export function cssMealPhotoFilter(f: MealPhotoFilterSettings): string {
  if (!f.enabled || !filterHasAdjustment(f)) return "none";
  const parts: string[] = [];
  const b = Number.isFinite(f.brightness) ? clamp(f.brightness, -1, 1) : 0;
  const c = Number.isFinite(f.contrast) ? clamp(f.contrast, 0, 2) : 1;
  const s = Number.isFinite(f.saturation) ? clamp(f.saturation, 0, 2) : 1;
  const w = Number.isFinite(f.warmth) ? clamp(f.warmth, -1, 1) : 0;
  const t = Number.isFinite(f.tint) ? clamp(f.tint, -1, 1) : 0;
  if (b !== 0) parts.push(`brightness(${(1 + b).toFixed(3)})`);
  if (c !== 1) parts.push(`contrast(${c.toFixed(3)})`);
  if (s !== 1) parts.push(`saturate(${s.toFixed(3)})`);
  if (w > 0) parts.push(`sepia(${(w * 0.35).toFixed(3)})`);
  else if (w < 0) parts.push(`hue-rotate(${(w * 18).toFixed(1)}deg)`);
  if (t !== 0) parts.push(`hue-rotate(${(-t * 20).toFixed(1)}deg)`);
  return parts.length > 0 ? parts.join(" ") : "none";
}

function signed(v: number): string {
  return v > 0 ? `+${v}%` : `${v}%`;
}

function FilterSlider(props: {
  label: string;
  value: number;
  min: number;
  max: number;
  startLabel?: string;
  endLabel?: string;
  format: (v: number) => string;
  onChange: (v: number) => void;
}) {
  return (
    <div style={{ marginBottom: 14 }}>
      <div className="dc-row" style={{ justifyContent: "space-between" }}>
        <span style={{ fontWeight: 500 }}>{props.label}</span>
        <span className="dc-muted" style={{ fontVariantNumeric: "tabular-nums" }}>{props.format(props.value)}</span>
      </div>
      <input
        type="range"
        min={props.min}
        max={props.max}
        step={5}
        value={props.value}
        onChange={(e) => props.onChange(Number(e.target.value))}
        aria-label={props.label}
        style={{ width: "100%", accentColor: "var(--dc-primary)" }}
      />
      {(props.startLabel || props.endLabel) && (
        <div className="dc-row" style={{ justifyContent: "space-between", fontSize: "0.78em" }}>
          <span className="dc-muted">{props.startLabel}</span>
          <span className="dc-muted">{props.endLabel}</span>
        </div>
      )}
    </div>
  );
}

export default function MealFilterPage() {
  const navigate = useNavigate();
  const [snack, showSnack] = useSnackbar();
  const settings = useSettings((s) => s.settings);
  const updateSettings = useSettings((s) => s.update);
  const saved = settings?.mealPhotoFilter;
  const [draft, setDraft] = useState<FilterDraft | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<unknown>(null);
  const [confirmLeave, setConfirmLeave] = useState(false);

  // Initialize the draft once settings arrive (and never clobber user edits).
  const effective: FilterDraft = draft ?? (saved ? draftFromSettings(saved) : DEFAULT_FILTER_DRAFT);
  const dirty = useMemo(() => {
    if (!saved) return false;
    const base = draftFromSettings(saved);
    return (
      base.enabled !== effective.enabled ||
      base.brightness !== effective.brightness ||
      base.contrast !== effective.contrast ||
      base.saturation !== effective.saturation ||
      base.warmth !== effective.warmth ||
      base.tint !== effective.tint
    );
  }, [saved, effective]);

  useDirtyGuard(dirty);

  const css = cssMealPhotoFilter(draftToSettings(effective));

  const doSave = async (): Promise<boolean> => {
    if (saving) return false;
    setSaving(true);
    setSaveError(null);
    try {
      await updateSettings({ mealPhotoFilter: draftToSettings(effective) });
      showSnack(tr("已保存", "Saved"));
      setDraft(null);
      return true;
    } catch (e) {
      setSaveError(e);
      return false;
    } finally {
      setSaving(false);
    }
  };

  const leavePage = () => navigate("/meals");

  const onBack = () => {
    if (dirty) {
      setConfirmLeave(true);
    } else {
      leavePage();
    }
  };

  if (!settings) {
    return (
      <div>
        <TopBar title={tr("吃历滤镜", "Meal photo filter")} back onBack={() => navigate("/meals")} />
        <Spinner />
      </div>
    );
  }

  const hasSliderAdjustment =
    effective.brightness !== DEFAULT_FILTER_DRAFT.brightness ||
    effective.contrast !== DEFAULT_FILTER_DRAFT.contrast ||
    effective.saturation !== DEFAULT_FILTER_DRAFT.saturation ||
    effective.warmth !== DEFAULT_FILTER_DRAFT.warmth ||
    effective.tint !== DEFAULT_FILTER_DRAFT.tint;

  return (
    <div className="meal-filter-page">
      <TopBar
        title={tr("吃历滤镜", "Meal photo filter")}
        back
        onBack={onBack}
        actions={
          <button
            className={`dc-btn ${dirty ? "dc-btn-filled" : ""}`}
            disabled={!dirty || saving}
            onClick={() => void doSave()}
          >
            <Save size={16} /> {tr("保存", "Save")}
          </button>
        }
      />
      <ErrorText error={saveError} />

      {/* 统一照片滤镜 */}
      <div className="dc-card" style={{ padding: 16, marginBottom: 12 }}>
        <label className="dc-row" style={{ cursor: "pointer" }}>
          <input
            type="checkbox"
            checked={effective.enabled}
            onChange={(e) => setDraft({ ...effective, enabled: e.target.checked })}
            style={{ width: 18, height: 18, accentColor: "var(--dc-primary)" }}
          />
          <span style={{ fontWeight: 600 }}>{tr("统一照片滤镜", "Unified photo filter")}</span>
        </label>
        <div className="dc-muted" style={{ fontSize: "0.85em", marginTop: 6 }}>
          {tr("只改变吃历中的显示效果，不会修改原始图片。", "Only changes how photos are shown in the meal calendar; original images are never modified.")}
        </div>
      </div>

      {/* 实时预览 */}
      <div className="dc-card" style={{ padding: 16, marginBottom: 12 }}>
        <div className="dc-row" style={{ justifyContent: "space-between", marginBottom: 10 }}>
          <span style={{ fontWeight: 600 }}>{tr("实时预览", "Live preview")}</span>
          <span className="dc-chip" style={effective.enabled ? { background: "var(--dc-secondary-container)", color: "var(--dc-on-secondary-container)", borderColor: "transparent" } : undefined}>
            {effective.enabled ? tr("滤镜已开启", "Filter on") : tr("滤镜已关闭", "Filter off")}
          </span>
        </div>
        <div
          aria-hidden
          style={{
            position: "relative",
            height: 150,
            borderRadius: "calc(var(--dc-radius) * 0.7)",
            overflow: "hidden",
            border: "var(--dc-border-width) solid var(--dc-outline-variant)",
          }}
        >
          <div style={{
            position: "absolute", inset: 0,
            background: "linear-gradient(120deg, #e66465 0%, #f6b73c 34%, #4d9f0c 62%, #3f6ad8 100%)",
            filter: css === "none" ? undefined : css,
          }} />
          <div style={{
            position: "absolute", inset: 0,
            background:
              "repeating-linear-gradient(90deg, rgba(128,128,128,1) 0 28px, rgba(192,192,192,1) 28px 56px, rgba(64,64,64,1) 56px 84px, rgba(255,255,255,1) 84px 112px)",
            opacity: 0.55,
            filter: css === "none" ? undefined : css,
          }} />
        </div>
        <div className="dc-muted" style={{ fontSize: "0.82em", marginTop: 8 }}>
          {tr("调整立即显示在预览中，保存后同一效果应用到全部吃历照片。", "Adjustments appear in the preview instantly; after saving the same effect applies to every meal photo.")}
        </div>
      </div>

      {/* 调整 */}
      <div className="dc-card" style={{ padding: 16 }}>
        <div className="dc-row" style={{ justifyContent: "space-between", marginBottom: 12 }}>
          <span style={{ fontWeight: 600 }}>{tr("调整", "Adjustments")}</span>
          <button
            className="dc-btn"
            disabled={!hasSliderAdjustment}
            onClick={() => setDraft({ ...effective, brightness: 0, contrast: 100, saturation: 100, warmth: 0, tint: 0 })}
          >
            <RefreshCw size={15} /> {tr("重置", "Reset")}
          </button>
        </div>
        <FilterSlider
          label={tr("亮度", "Brightness")}
          value={effective.brightness}
          min={-100}
          max={100}
          format={signed}
          onChange={(v) => setDraft({ ...effective, brightness: v })}
        />
        <FilterSlider
          label={tr("对比度", "Contrast")}
          value={effective.contrast}
          min={0}
          max={200}
          format={(v) => `${v}%`}
          onChange={(v) => setDraft({ ...effective, contrast: v })}
        />
        <FilterSlider
          label={tr("饱和度", "Saturation")}
          value={effective.saturation}
          min={0}
          max={200}
          format={(v) => `${v}%`}
          onChange={(v) => setDraft({ ...effective, saturation: v })}
        />
        <FilterSlider
          label={tr("色温", "Warmth")}
          value={effective.warmth}
          min={-100}
          max={100}
          startLabel={tr("冷", "Cold")}
          endLabel={tr("暖", "Warm")}
          format={signed}
          onChange={(v) => setDraft({ ...effective, warmth: v })}
        />
        <FilterSlider
          label={tr("色调", "Tint")}
          value={effective.tint}
          min={-100}
          max={100}
          startLabel={tr("绿色", "Green")}
          endLabel={tr("洋红", "Magenta")}
          format={signed}
          onChange={(v) => setDraft({ ...effective, tint: v })}
        />
        <div className="dc-muted" style={{ fontSize: "0.8em" }}>
          {tr("重置只会恢复滑条默认值，仍需点“保存”才会写入。", "Reset only restores the slider defaults; tap “Save” to persist.")}
        </div>
      </div>

      {/* Unsaved-changes dialog: save / keep editing / discard */}
      <Modal open={confirmLeave} onClose={() => setConfirmLeave(false)} title={tr("设置尚未保存", "Settings not saved")}>
        <div className="dc-muted" style={{ marginBottom: 14 }}>
          {tr("返回会丢失刚才的滤镜调整。", "Going back discards the filter adjustments you just made.")}
        </div>
        <div className="dc-row dc-wrap" style={{ justifyContent: "flex-end", gap: 8 }}>
          <button
            className="dc-btn dc-btn-filled"
            onClick={async () => {
              setConfirmLeave(false);
              if (await doSave()) leavePage();
            }}
          >
            {tr("保存", "Save")}
          </button>
          <button className="dc-btn" onClick={() => setConfirmLeave(false)}>{tr("继续编辑", "Keep editing")}</button>
          <button
            className="dc-btn"
            style={{ color: "var(--dc-error)" }}
            onClick={() => { setConfirmLeave(false); setDraft(null); leavePage(); }}
          >
            {tr("放弃", "Discard")}
          </button>
        </div>
      </Modal>

      <Snackbar message={snack} />
      <PageTutorialOverlay
        pageKey="meals-filter"
        title={tr("吃历滤镜", "Meal photo filter")}
        lines={[
          tr("拖动滑条实时预览；改动需点右上角“保存”才生效。", "Drag the sliders for a live preview; tap “Save” in the top bar to apply."),
          tr("滤镜只改变显示效果，不会修改原始图片。", "The filter only changes the display; original images are never modified."),
        ]}
      />
    </div>
  );
}
