/**
 * 设置 → 外观与语言 (README_for_ai §17.2)：界面风格、自定义主题编辑器、主题颜色。
 * Everything edits the shell-owned draft; 保存 in the top bar persists via PUT /api/settings.
 * Colors are stored as opaque ARGB ints (Kotlin Int semantics); <input type="color">
 * works on the low 24 bits (#rrggbb), alpha stays 0xFF.
 */
import React from "react";
import type { CustomThemePalette, CustomThemeSettings } from "../../../api/types";
import { tr } from "../../../i18n/tr";
import { SectionCard, Segmented, SliderRow } from "../SettingsPage";
import type { SettingsSectionProps } from "../SettingsPage";

type VisualStyle = "MATERIAL" | "LIQUID_GLASS" | "ORGANIC_FUTURE" | "CUSTOM";
type BaseStyle = "MATERIAL" | "LIQUID_GLASS" | "ORGANIC_FUTURE";

function palette(
  backgroundArgb: number, onBackgroundArgb: number, surfaceArgb: number, onSurfaceArgb: number,
  surfaceContainerArgb: number, surfaceVariantArgb: number, onSurfaceVariantArgb: number, outlineArgb: number,
): CustomThemePalette {
  return { backgroundArgb, onBackgroundArgb, surfaceArgb, onSurfaceArgb, surfaceContainerArgb, surfaceVariantArgb, onSurfaceVariantArgb, outlineArgb };
}

// Values mirror DEFAULT_CUSTOM_THEME_LIGHT_PALETTE / DARK_PALETTE in AppModels.kt.
const LIGHT_PALETTE: CustomThemePalette = palette(
  0xFFF7FBF5 | 0, 0xFF171D19 | 0, 0xFFF7FBF5 | 0, 0xFF171D19 | 0,
  0xFFE9EFE9 | 0, 0xFFDDE5DD | 0, 0xFF414943 | 0, 0xFF717971 | 0,
);
const DARK_PALETTE: CustomThemePalette = palette(
  0xFF101511 | 0, 0xFFE0E4DF | 0, 0xFF101511 | 0, 0xFFE0E4DF | 0,
  0xFF1B211C | 0, 0xFF414943 | 0, 0xFFC1C9C1 | 0, 0xFF8B938A | 0,
);

const CUSTOM_DEFAULTS: CustomThemeSettings = {
  baseStyle: "MATERIAL",
  lightPalette: LIGHT_PALETTE,
  darkPalette: DARK_PALETTE,
  cornerRadiusDp: 18,
  borderWidthDp: 1,
  elevationDp: 2,
  panelOpacity: 0.94,
  spacingScale: 1,
  animationScale: 1,
};

const STYLE_OPTIONS: { value: VisualStyle; label: string }[] = [
  { value: "MATERIAL", label: tr("原生", "Material") },
  { value: "LIQUID_GLASS", label: tr("玻璃", "Glass") },
  { value: "ORGANIC_FUTURE", label: tr("有机未来", "Organic") },
  { value: "CUSTOM", label: tr("自定义", "Custom") },
];

const STYLE_DESCRIPTIONS: Record<VisualStyle, string> = {
  MATERIAL: tr("安卓原生 · 清晰、直接的 Material 界面", "Material · Clear, direct Android UI"),
  LIQUID_GLASS: tr("透明玻璃 · 轻盈的半透明层次", "Liquid Glass · Light translucent layers"),
  ORGANIC_FUTURE: tr("有机未来 · 森林色、哑光有机面板与杂志式层级", "Organic Future · Forest tones, matte organic panels, and editorial type"),
  CUSTOM: tr("自定义 · 使用受控颜色和视觉参数，不执行 CSS、脚本或任意选择器", "Custom · Controlled colors and visual parameters, without CSS, scripts, or arbitrary selectors"),
};

const BASE_STYLE_OPTIONS: { value: BaseStyle; label: string }[] = [
  { value: "MATERIAL", label: tr("原生", "Material") },
  { value: "LIQUID_GLASS", label: tr("玻璃", "Glass") },
  { value: "ORGANIC_FUTURE", label: tr("有机", "Organic") },
];

const ROLE_LABELS: { key: keyof CustomThemePalette; zh: string; en: string }[] = [
  { key: "backgroundArgb", zh: "页面背景", en: "Background" },
  { key: "onBackgroundArgb", zh: "背景文字", en: "On background" },
  { key: "surfaceArgb", zh: "基础表面", en: "Surface" },
  { key: "onSurfaceArgb", zh: "正文文字", en: "On surface" },
  { key: "surfaceContainerArgb", zh: "卡片表面", en: "Surface container" },
  { key: "surfaceVariantArgb", zh: "次级表面", en: "Surface variant" },
  { key: "onSurfaceVariantArgb", zh: "次要文字", en: "On surface variant" },
  { key: "outlineArgb", zh: "边框", en: "Outline" },
];

/** Preset swatches offered when adding a secondary color (Android picks an unused preset). */
const PRESET_COLORS: number[] = [
  0xFF42664D | 0, 0xFFC96F4A | 0, 0xFFD4A72C | 0, 0xFF527F91 | 0,
  0xFF7B5E57 | 0, 0xFF6750A4 | 0, 0xFF984061 | 0, 0xFF3F6374 | 0,
];

function argbToHex(v: number): string {
  return "#" + ((v & 0xffffff) >>> 0).toString(16).padStart(6, "0");
}

function hexToArgb(hex: string): number {
  const n = parseInt(hex.replace("#", ""), 16);
  if (!Number.isFinite(n)) return 0xff000000 | 0;
  return (0xff000000 | (n & 0xffffff)) | 0;
}

function fmtDp(v: number): string {
  return `${Number.isInteger(v) ? v : v.toFixed(2)} dp`;
}

export default function AppearanceSection({ draft, patch }: SettingsSectionProps) {
  const style = draft.visualStyle as VisualStyle;
  const ct = draft.customTheme;
  const secondaries = draft.themeSecondaryColorsArgb ?? [];

  const patchCustom = (p: Partial<CustomThemeSettings>) => patch({ customTheme: { ...ct, ...p } });
  const patchPalette = (mode: "light" | "dark", key: keyof CustomThemePalette, value: number) =>
    patch({
      customTheme: {
        ...ct,
        [mode === "light" ? "lightPalette" : "darkPalette"]: {
          ...(mode === "light" ? ct.lightPalette : ct.darkPalette),
          [key]: value,
        },
      },
    });

  const duplicateSecondary = new Set(secondaries.map((c) => (c & 0xffffff) >>> 0)).size !== secondaries.length;

  const addSecondary = () => {
    if (secondaries.length >= 5) return;
    const used = new Set(secondaries.map((c) => (c & 0xffffff) >>> 0));
    const next = PRESET_COLORS.find((c) => !used.has((c & 0xffffff) >>> 0)) ?? (0xff42664d | 0);
    patch({ themeSecondaryColorsArgb: [...secondaries, next] });
  };

  const updateSecondary = (index: number, value: number) =>
    patch({ themeSecondaryColorsArgb: secondaries.map((c, i) => (i === index ? value : c)) });

  const removeSecondary = (index: number) =>
    patch({ themeSecondaryColorsArgb: secondaries.filter((_, i) => i !== index) });

  const paletteEditor = (mode: "light" | "dark") => {
    const pal = mode === "light" ? ct.lightPalette : ct.darkPalette;
    return (
      <div className="dc-col" style={{ gap: 6 }}>
        {ROLE_LABELS.map((role) => (
          <div key={role.key} className="dc-row" style={{ gap: 10 }}>
            <input
              type="color" aria-label={tr(role.zh, role.en)}
              value={argbToHex(pal[role.key])}
              onChange={(e) => patchPalette(mode, role.key, hexToArgb(e.target.value))}
              style={{ width: 36, height: 28, padding: 0, border: "var(--dc-border-width) solid var(--dc-outline-variant)", borderRadius: 8, background: "none", cursor: "pointer", flexShrink: 0 }}
            />
            <span className="dc-grow" style={{ fontSize: "0.9em" }}>{tr(role.zh, role.en)}</span>
            <span className="dc-muted" style={{ fontSize: "0.8em", fontFamily: "monospace" }}>{argbToHex(pal[role.key])}</span>
          </div>
        ))}
      </div>
    );
  };

  return (
    <div className="dc-col" style={{ gap: 12 }}>
      <SectionCard title={tr("界面风格", "Visual style")}>
        <Segmented<VisualStyle>
          value={style}
          onChange={(v) => patch({ visualStyle: v })}
          options={STYLE_OPTIONS}
        />
        <div className="dc-muted" style={{ fontSize: "0.86em" }}>{STYLE_DESCRIPTIONS[style]}</div>
      </SectionCard>

      {style === "CUSTOM" && (
        <SectionCard
          title={tr("自定义主题", "Custom theme")}
          description={tr(
            "设置只映射到受控主题角色；不会加载 CSS、脚本、网络资源或修改页面结构。",
            "Maps only to controlled theme roles; no CSS, scripts, network resources, or page-structure changes.",
          )}
        >
          <div className="dc-col" style={{ gap: 4 }}>
            <span style={{ fontSize: "0.9em" }}>{tr("基础渲染", "Base rendering")}</span>
            <Segmented<BaseStyle>
              value={ct.baseStyle}
              onChange={(v) => patchCustom({ baseStyle: v })}
              options={BASE_STYLE_OPTIONS}
            />
          </div>

          <div className="dc-col" style={{ gap: 8 }}>
            <span style={{ fontSize: "0.9em", fontWeight: 600 }}>{tr("浅色模式颜色", "Light-mode colors")}</span>
            {paletteEditor("light")}
          </div>
          <hr className="dc-divider" />
          <div className="dc-col" style={{ gap: 8 }}>
            <span style={{ fontSize: "0.9em", fontWeight: 600 }}>{tr("深色模式颜色", "Dark-mode colors")}</span>
            {paletteEditor("dark")}
          </div>
          <div className="dc-muted" style={{ fontSize: "0.82em" }}>
            {tr(
              "部分文字或边框颜色对比度不足时，保存后会自动调整到可读颜色。",
              "Low-contrast text or border colors are adjusted to readable values when saved.",
            )}
          </div>

          <hr className="dc-divider" />
          <SliderRow
            label={tr("全局圆角", "Global corners")}
            value={ct.cornerRadiusDp} min={0} max={40} step={1}
            format={fmtDp}
            onChange={(v) => patchCustom({ cornerRadiusDp: v })}
          />
          <SliderRow
            label={tr("面板边框", "Panel border")}
            value={ct.borderWidthDp} min={0} max={4} step={0.25}
            format={fmtDp}
            onChange={(v) => patchCustom({ borderWidthDp: Math.round(v * 4) / 4 })}
          />
          <SliderRow
            label={tr("面板阴影", "Panel elevation")}
            value={ct.elevationDp} min={0} max={16} step={1}
            format={fmtDp}
            onChange={(v) => patchCustom({ elevationDp: v })}
          />
          <SliderRow
            label={tr("面板不透明度", "Panel opacity")}
            value={Math.round(ct.panelOpacity * 100)} min={65} max={100} step={5}
            format={(v) => `${v}%`}
            onChange={(v) => patchCustom({ panelOpacity: v / 100 })}
          />
          <SliderRow
            label={tr("面板内容间距", "Panel content spacing")}
            value={Math.round(ct.spacingScale * 100)} min={75} max={135} step={5}
            format={(v) => `${v}%`}
            onChange={(v) => patchCustom({ spacingScale: v / 100 })}
          />
          <SliderRow
            label={tr("页面切换动效", "Page transition motion")}
            value={Math.round(ct.animationScale * 100)} min={0} max={200} step={10}
            format={(v) => `${v}%`}
            onChange={(v) => patchCustom({ animationScale: v / 100 })}
            hint={tr("设为 0% 会关闭页面切换动效。", "0% disables page transitions.")}
          />

          <div className="dc-row" style={{ justifyContent: "flex-end" }}>
            <button className="dc-btn" onClick={() => patch({ customTheme: { ...CUSTOM_DEFAULTS } })}>
              {tr("恢复默认", "Use default")}
            </button>
          </div>
        </SectionCard>
      )}

      <SectionCard title={tr("主题颜色", "Theme colors")}>
        <div className="dc-row" style={{ gap: 10 }}>
          <input
            type="color" aria-label={tr("主颜色", "Primary color")}
            value={argbToHex(draft.themeColorArgb)}
            onChange={(e) => patch({ themeColorArgb: hexToArgb(e.target.value) })}
            style={{ width: 36, height: 28, padding: 0, border: "var(--dc-border-width) solid var(--dc-outline-variant)", borderRadius: 8, background: "none", cursor: "pointer" }}
          />
          <span className="dc-grow">{tr("主颜色", "Primary color")}</span>
          <span className="dc-muted" style={{ fontSize: "0.8em", fontFamily: "monospace" }}>{argbToHex(draft.themeColorArgb)}</span>
        </div>
        <div className="dc-col" style={{ gap: 6 }}>
          <span style={{ fontSize: "0.9em" }}>
            {tr("副颜色", "Secondary colors")}
            <span className="dc-muted" style={{ fontSize: "0.85em" }}>（2–5）</span>
          </span>
          {secondaries.map((color, i) => (
            <div key={i} className="dc-row" style={{ gap: 10 }}>
              <input
                type="color" aria-label={`${tr("副颜色", "Secondary color")} ${i + 1}`}
                value={argbToHex(color)}
                onChange={(e) => updateSecondary(i, hexToArgb(e.target.value))}
                style={{ width: 36, height: 28, padding: 0, border: "var(--dc-border-width) solid var(--dc-outline-variant)", borderRadius: 8, background: "none", cursor: "pointer", flexShrink: 0 }}
              />
              <span className="dc-grow">{`${tr("副颜色", "Secondary")} ${i + 1}`}</span>
              <span className="dc-muted" style={{ fontSize: "0.8em", fontFamily: "monospace" }}>{argbToHex(color)}</span>
              <button
                className="dc-icon-btn" style={{ width: 32, height: 32 }}
                disabled={secondaries.length <= 2}
                aria-label={tr("删除副颜色", "Remove secondary color")}
                title={secondaries.length <= 2 ? tr("至少保留 2 个副颜色", "At least 2 secondary colors are required") : undefined}
                onClick={() => removeSecondary(i)}
              >×</button>
            </div>
          ))}
          {duplicateSecondary && (
            <div style={{ color: "var(--dc-error)", fontSize: "0.85em" }}>
              {tr("副颜色不能重复", "Secondary colors must be unique")}
            </div>
          )}
          <div className="dc-row">
            <button className="dc-btn dc-btn-tonal" disabled={secondaries.length >= 5} onClick={addSecondary}>
              {tr("添加", "Add")}
            </button>
            <span className="dc-muted" style={{ fontSize: "0.82em" }}>
              {tr("最多 5 个；须互不重复才能保存。", "Up to 5; must be unique to save.")}
            </span>
          </div>
        </div>
      </SectionCard>
    </div>
  );
}
