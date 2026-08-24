/**
 * 设置 → 通用与外观 (README_for_ai §17.1 通用部分 + §17.2 的语言/明暗/字号/背景)。
 * Local draft only — the shell's 保存 button persists via PUT /api/settings.
 * The background image file itself is uploaded/cleared immediately through
 * /api/settings/background-image; visibility/blur stay draft fields.
 */
import React, { useRef, useState } from "react";
import { apiSend, apiUpload } from "../../../api/client";
import type { AppLanguage, DarkMode } from "../../../api/types";
import { useSettings } from "../../../stores/settings";
import { tr } from "../../../i18n/tr";
import { ErrorText, Spinner } from "../../../components/ui";
import {
  SectionCard, Segmented, SelectField, SliderRow, TextField, Toggle,
} from "../SettingsPage";
import type { SettingsSectionProps } from "../SettingsPage";
import AppearanceSection from "./AppearanceSection";

const LANGUAGE_OPTIONS: { value: AppLanguage; label: string }[] = [
  { value: "CHINESE", label: "简体中文" },
  { value: "TRADITIONAL_CHINESE", label: "繁體中文" },
  { value: "ENGLISH", label: "English" },
  { value: "KOREAN", label: "한국어" },
  { value: "JAPANESE", label: "日本語" },
];

const DARK_MODE_OPTIONS: { value: DarkMode; label: string }[] = [
  { value: "SYSTEM", label: tr("跟随", "System") },
  { value: "LIGHT", label: tr("浅色", "Light") },
  { value: "DARK", label: tr("深色", "Dark") },
];

const ORIENTATION_OPTIONS: { value: "AUTO" | "PORTRAIT" | "LANDSCAPE"; label: string }[] = [
  { value: "AUTO", label: tr("自动", "Auto") },
  { value: "PORTRAIT", label: tr("竖屏", "Portrait") },
  { value: "LANDSCAPE", label: tr("横屏", "Landscape") },
];

export default function GeneralSection({ settings, draft, patch, snackbar }: SettingsSectionProps) {
  const refresh = useSettings((s) => s.refresh);
  const fileRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [bgError, setBgError] = useState<unknown>(null);

  const bgUri = draft.backgroundImageUri;

  const onPickFile = () => fileRef.current?.click();

  const onFile = async (file: File | undefined) => {
    if (!file) return;
    setUploading(true);
    setBgError(null);
    try {
      const res = await apiUpload<{ uri: string }>("/api/settings/background-image", file);
      await refresh();
      patch({ backgroundImageUri: res.uri });
      snackbar(tr("背景图片已更新", "Background image updated"));
    } catch (e) {
      setBgError(e);
    } finally {
      setUploading(false);
      if (fileRef.current) fileRef.current.value = "";
    }
  };

  const onClear = async () => {
    setUploading(true);
    setBgError(null);
    try {
      await apiSend("/api/settings/background-image", "DELETE");
      await refresh();
      patch({ backgroundImageUri: null });
      snackbar(tr("已移除背景图片", "Background image removed"));
    } catch (e) {
      setBgError(e);
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="dc-col" style={{ gap: 12 }}>
      <SectionCard title={tr("通用", "General")}>
        <SelectField
          label={tr("软件语言", "App language")}
          value={draft.appLanguage}
          onChange={(v) => patch({ appLanguage: v as AppLanguage })}
          options={LANGUAGE_OPTIONS}
          hint={tr("保存后立即生效，覆盖全部页面文案。", "Applies to every page immediately after saving.")}
        />
        <div className="dc-col" style={{ gap: 4 }}>
          <span style={{ fontSize: "0.9em" }}>{tr("明暗模式", "Dark mode")}</span>
          <Segmented<DarkMode>
            value={draft.darkMode}
            onChange={(v) => patch({ darkMode: v })}
            options={DARK_MODE_OPTIONS}
          />
        </div>
        <SliderRow
          label={tr("全局字号", "Global type size")}
          value={Math.round(draft.fontScale * 100)}
          min={80} max={130} step={5}
          format={(v) => `${v}%`}
          onChange={(v) => patch({ fontScale: v / 100 })}
        />
        <SelectField
          label={tr("屏幕方向", "Screen orientation")}
          value={draft.orientationPreference}
          onChange={(v) => patch({ orientationPreference: v as "AUTO" | "PORTRAIT" | "LANDSCAPE" })}
          options={ORIENTATION_OPTIONS}
          hint={tr(
            "安装为 PWA 后由浏览器尽力应用；普通标签页可能不允许锁定方向。",
            "Applied when the installed PWA/browser permits it; normal tabs may reject orientation locking.",
          )}
        />
        <Toggle
          checked={draft.compactMode}
          onChange={(v) => patch({ compactMode: v })}
          label={<span>{tr("紧凑显示", "Compact layout")}<div className="dc-muted" style={{ fontSize: "0.82em" }}>{tr("开启后缩小列表与卡片间距，一屏显示更多内容。", "Shrinks list and card spacing to fit more content on screen.")}</div></span>}
        />
      </SectionCard>

      <SectionCard title={tr("背景图片", "Background image")}>
        <div style={{
          height: 120, borderRadius: "calc(var(--dc-radius) * 0.7)", overflow: "hidden",
          border: "var(--dc-border-width) solid var(--dc-outline-variant)",
          background: "var(--dc-surface-container-high)", display: "flex", alignItems: "center", justifyContent: "center",
        }}>
          {bgUri ? (
            <img
              src={`/api/settings/background-image?ts=${encodeURIComponent(bgUri)}`}
              alt={tr("背景图片预览", "Background preview")}
              style={{ width: "100%", height: "100%", objectFit: "cover" }}
            />
          ) : (
            <span className="dc-muted" style={{ fontSize: "0.88em" }}>{tr("未选择图片", "No image selected")}</span>
          )}
        </div>
        <div className="dc-row dc-wrap">
          <button className="dc-btn dc-btn-tonal" disabled={uploading} onClick={onPickFile}>
            {bgUri ? tr("更换图片", "Replace image") : tr("选择图片", "Choose image")}
          </button>
          <button className="dc-btn dc-btn-danger" disabled={uploading || !bgUri} onClick={() => void onClear()}>
            {tr("移除", "Remove")}
          </button>
          {uploading && <Spinner size={20} />}
        </div>
        <input
          ref={fileRef} type="file" accept="image/*" hidden
          onChange={(e) => void onFile(e.target.files?.[0])}
        />
        <ErrorText error={bgError} />
        <SliderRow
          label={tr("图片可见度", "Image visibility")}
          value={Math.round(draft.backgroundImageOpacity * 100)}
          min={0} max={100} step={5}
          format={(v) => `${v}%`}
          disabled={!bgUri}
          onChange={(v) => patch({ backgroundImageOpacity: v / 100 })}
        />
        <SliderRow
          label={tr("背景模糊", "Background blur")}
          value={draft.backgroundImageBlurDp}
          min={0} max={40} step={1}
          format={(v) => `${v} dp`}
          disabled={!bgUri}
          onChange={(v) => patch({ backgroundImageBlurDp: v })}
        />
      </SectionCard>

      {/* §17.2 外观：界面风格、自定义主题、主题颜色（同一草稿与保存按钮） */}
      <AppearanceSection settings={settings} draft={draft} patch={patch} snackbar={snackbar} />
    </div>
  );
}
