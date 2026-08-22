/**
 * Material-3-like color scheme generation from a seed ARGB, approximated with
 * OKLCH tonal palettes. Produces the same roles Android maps into Compose tokens.
 */
import type { CustomThemeSettings } from "../api/types";

function argbToRgb(argb: number): [number, number, number] {
  const v = argb >>> 0;
  return [(v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff];
}

function rgbToOklch(r: number, g: number, b: number): [number, number, number] {
  const rf = r / 255, gf = g / 255, bf = b / 255;
  const lin = (c: number) => (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
  const [lr, lg, lb] = [lin(rf), lin(gf), lin(bf)];
  const l = 0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb;
  const m = 0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb;
  const s = 0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb;
  const l_ = Math.cbrt(l), m_ = Math.cbrt(m), s_ = Math.cbrt(s);
  const L = 0.2104542553 * l_ + 0.793617785 * m_ - 0.0040720468 * s_;
  const A = 1.9779984951 * l_ - 2.428592205 * m_ + 0.4505937099 * s_;
  const B = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.808675766 * s_;
  const C = Math.sqrt(A * A + B * B);
  let H = (Math.atan2(B, A) * 180) / Math.PI;
  if (H < 0) H += 360;
  return [L, C, H];
}

function oklchToRgb(L: number, C: number, H: number): [number, number, number] {
  const hr = (H * Math.PI) / 180;
  const A = C * Math.cos(hr), B = C * Math.sin(hr);
  const l_ = L + 0.3963377774 * A + 0.2158037573 * B;
  const m_ = L - 0.1055613458 * A - 0.0638541728 * B;
  const s_ = L - 0.0894841775 * A - 1.291485548 * B;
  const l = l_ * l_ * l_, m = m_ * m_ * m_, s = s_ * s_ * s_;
  const lr = +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
  const lg = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
  const lb = -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s;
  const gam = (c: number) => {
    const v = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
    return Math.round(Math.min(1, Math.max(0, v)) * 255);
  };
  return [gam(lr), gam(lg), gam(lb)];
}

function tone(seed: [number, number, number], L: number, chromaScale = 1): [number, number, number] {
  const [, C, H] = seed;
  const c = Math.min(0.37, C * chromaScale);
  return oklchToRgb(L, c, H);
}

export interface SchemeRoles {
  primary: string;
  onPrimary: string;
  primaryContainer: string;
  onPrimaryContainer: string;
  secondary: string;
  onSecondary: string;
  secondaryContainer: string;
  onSecondaryContainer: string;
  tertiary: string;
  onTertiary: string;
  tertiaryContainer: string;
  onTertiaryContainer: string;
  error: string;
  onError: string;
  errorContainer: string;
  onErrorContainer: string;
  background: string;
  onBackground: string;
  surface: string;
  onSurface: string;
  surfaceContainer: string;
  surfaceContainerHigh: string;
  surfaceVariant: string;
  onSurfaceVariant: string;
  outline: string;
  outlineVariant: string;
  inverseSurface: string;
  inverseOnSurface: string;
  scrim: string;
}

export function buildScheme(seedArgb: number, secondaryArgb: number[], dark: boolean): SchemeRoles {
  const seed = rgbToOklch(...argbToRgb(seedArgb));
  const secSeed = rgbToOklch(...argbToRgb(secondaryArgb[0] ?? seedArgb));
  const terSeed = rgbToOklch(...argbToRgb(secondaryArgb[1] ?? secondaryArgb[0] ?? seedArgb));
  const rgb = (c: [number, number, number]) => `rgb(${c[0]},${c[1]},${c[2]})`;
  if (!dark) {
    return {
      primary: rgb(tone(seed, 0.45)),
      onPrimary: rgb(tone(seed, 1.0)),
      primaryContainer: rgb(tone(seed, 0.9, 0.6)),
      onPrimaryContainer: rgb(tone(seed, 0.2, 0.6)),
      secondary: rgb(tone(secSeed, 0.48)),
      onSecondary: rgb(tone(secSeed, 1.0)),
      secondaryContainer: rgb(tone(secSeed, 0.9, 0.5)),
      onSecondaryContainer: rgb(tone(secSeed, 0.2, 0.5)),
      tertiary: rgb(tone(terSeed, 0.48)),
      onTertiary: rgb(tone(terSeed, 1.0)),
      tertiaryContainer: rgb(tone(terSeed, 0.9, 0.5)),
      onTertiaryContainer: rgb(tone(terSeed, 0.2, 0.5)),
      error: "rgb(186,26,26)",
      onError: "rgb(255,255,255)",
      errorContainer: "rgb(255,218,214)",
      onErrorContainer: "rgb(65,0,2)",
      background: rgb(tone(seed, 0.98, 0.12)),
      onBackground: rgb(tone(seed, 0.2, 0.3)),
      surface: rgb(tone(seed, 0.98, 0.12)),
      onSurface: rgb(tone(seed, 0.2, 0.3)),
      surfaceContainer: rgb(tone(seed, 0.94, 0.16)),
      surfaceContainerHigh: rgb(tone(seed, 0.91, 0.18)),
      surfaceVariant: rgb(tone(seed, 0.92, 0.2)),
      onSurfaceVariant: rgb(tone(seed, 0.38, 0.3)),
      outline: rgb(tone(seed, 0.55, 0.25)),
      outlineVariant: rgb(tone(seed, 0.85, 0.12)),
      inverseSurface: rgb(tone(seed, 0.2, 0.2)),
      inverseOnSurface: rgb(tone(seed, 0.96, 0.15)),
      scrim: "rgba(0,0,0,0.5)",
    };
  }
  return {
    primary: rgb(tone(seed, 0.82, 0.7)),
    onPrimary: rgb(tone(seed, 0.28, 0.6)),
    primaryContainer: rgb(tone(seed, 0.36, 0.55)),
    onPrimaryContainer: rgb(tone(seed, 0.92, 0.55)),
    secondary: rgb(tone(secSeed, 0.8, 0.55)),
    onSecondary: rgb(tone(secSeed, 0.28, 0.5)),
    secondaryContainer: rgb(tone(secSeed, 0.34, 0.45)),
    onSecondaryContainer: rgb(tone(secSeed, 0.92, 0.45)),
    tertiary: rgb(tone(terSeed, 0.8, 0.55)),
    onTertiary: rgb(tone(terSeed, 0.28, 0.5)),
    tertiaryContainer: rgb(tone(terSeed, 0.34, 0.45)),
    onTertiaryContainer: rgb(tone(terSeed, 0.92, 0.45)),
    error: "rgb(255,180,171)",
    onError: "rgb(105,0,5)",
    errorContainer: "rgb(147,0,10)",
    onErrorContainer: "rgb(255,218,214)",
    background: rgb(tone(seed, 0.12, 0.1)),
    onBackground: rgb(tone(seed, 0.92, 0.15)),
    surface: rgb(tone(seed, 0.12, 0.1)),
    onSurface: rgb(tone(seed, 0.92, 0.15)),
    surfaceContainer: rgb(tone(seed, 0.17, 0.12)),
    surfaceContainerHigh: rgb(tone(seed, 0.21, 0.14)),
    surfaceVariant: rgb(tone(seed, 0.26, 0.16)),
    onSurfaceVariant: rgb(tone(seed, 0.8, 0.15)),
    outline: rgb(tone(seed, 0.6, 0.15)),
    outlineVariant: rgb(tone(seed, 0.3, 0.1)),
    inverseSurface: rgb(tone(seed, 0.92, 0.12)),
    inverseOnSurface: rgb(tone(seed, 0.2, 0.15)),
    scrim: "rgba(0,0,0,0.6)",
  };
}

/** Apply the active scheme + style + custom theme to document root CSS variables. */
export function applyThemeVariables(opts: {
  style: string;
  customTheme: CustomThemeSettings | null;
  dark: boolean;
  seedArgb: number;
  secondaryArgb: number[];
  fontScale: number;
  compactMode: boolean;
}): void {
  const root = document.documentElement;
  let roles: SchemeRoles;
  if (opts.style === "CUSTOM" && opts.customTheme) {
    const p = opts.dark ? opts.customTheme.darkPalette : opts.customTheme.lightPalette;
    const c = (v: number) => `rgb(${(v >>> 16) & 0xff},${(v >>> 8) & 0xff},${v & 0xff})`;
    roles = {
      primary: c(p.surfaceContainerArgb), onPrimary: c(p.onSurfaceArgb),
      primaryContainer: c(p.surfaceVariantArgb), onPrimaryContainer: c(p.onSurfaceVariantArgb),
      secondary: c(p.outlineArgb), onSecondary: c(p.onBackgroundArgb),
      secondaryContainer: c(p.surfaceVariantArgb), onSecondaryContainer: c(p.onSurfaceVariantArgb),
      tertiary: c(p.outlineArgb), onTertiary: c(p.onBackgroundArgb),
      tertiaryContainer: c(p.surfaceVariantArgb), onTertiaryContainer: c(p.onSurfaceVariantArgb),
      error: "rgb(186,26,26)", onError: "#fff", errorContainer: "rgb(255,218,214)", onErrorContainer: "rgb(65,0,2)",
      background: c(p.backgroundArgb), onBackground: c(p.onBackgroundArgb),
      surface: c(p.surfaceArgb), onSurface: c(p.onSurfaceArgb),
      surfaceContainer: c(p.surfaceContainerArgb), surfaceContainerHigh: c(p.surfaceContainerArgb),
      surfaceVariant: c(p.surfaceVariantArgb), onSurfaceVariant: c(p.onSurfaceVariantArgb),
      outline: c(p.outlineArgb), outlineVariant: c(p.outlineArgb),
      inverseSurface: c(p.onBackgroundArgb), inverseOnSurface: c(p.backgroundArgb), scrim: "rgba(0,0,0,0.5)",
    };
  } else {
    roles = buildScheme(opts.seedArgb, opts.secondaryArgb, opts.dark);
  }
  for (const [k, v] of Object.entries(roles)) {
    root.style.setProperty(`--dc-${k.replace(/[A-Z]/g, (m) => "-" + m.toLowerCase())}`, v);
  }
  root.dataset.style = opts.style === "CUSTOM" ? (opts.customTheme?.baseStyle ?? "MATERIAL").toLowerCase() : opts.style.toLowerCase();
  root.dataset.theme = opts.dark ? "dark" : "light";
  const spacing = opts.compactMode ? 0.85 : 1;
  root.style.setProperty("--dc-font-scale", String(opts.fontScale));
  root.style.setProperty("--dc-spacing", String(spacing));
  root.style.setProperty("--dc-motion-scale", String(
    opts.style === "CUSTOM" ? (opts.customTheme?.animationScale ?? 1) : 1
  ));
  root.style.setProperty("--dc-radius", `${opts.style === "CUSTOM" ? (opts.customTheme?.cornerRadiusDp ?? 18) : 16}px`);
  if (opts.style === "CUSTOM" && opts.customTheme) {
    root.style.setProperty("--dc-border-width", `${opts.customTheme.borderWidthDp}px`);
    root.style.setProperty("--dc-elevation", `${opts.customTheme.elevationDp}px`);
    root.style.setProperty("--dc-panel-opacity", String(opts.customTheme.panelOpacity));
    root.style.setProperty("--dc-gap-scale", String(opts.customTheme.spacingScale));
  } else {
    root.style.setProperty("--dc-border-width", "1px");
    root.style.setProperty("--dc-elevation", "2px");
    root.style.setProperty("--dc-panel-opacity", "1");
    root.style.setProperty("--dc-gap-scale", "1");
  }
}
