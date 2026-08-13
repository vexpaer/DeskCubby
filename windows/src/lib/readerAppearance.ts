import type { ReaderPreferencesV3 } from "./readerApi";

const BACKGROUNDS: Record<ReaderPreferencesV3["background"], string> = {
  white: "#ffffff",
  paper: "#f4f0e6",
  sepia: "#ead9b9",
  green: "#dce8d7",
  night: "#15171c",
  custom: "#f4f0e6",
};

const FOREGROUNDS: Record<Exclude<ReaderPreferencesV3["background"], "custom">, string> = {
  white: "#1d1d1f",
  paper: "#29261f",
  sepia: "#382d20",
  green: "#203126",
  night: "#e6e4de",
};

export interface ReaderPalette {
  background: string;
  requestedForeground: string;
  foreground: string;
  contrast: number;
  adjustedForContrast: boolean;
}

export function argbToCss(argb: number): string {
  const rgb = (argb >>> 0) & 0x00ff_ffff;
  return `#${rgb.toString(16).padStart(6, "0")}`;
}

export function cssToArgb(value: string): number {
  const match = /^#([\da-f]{6})$/i.exec(value.trim());
  const rgb = match ? Number.parseInt(match[1], 16) : 0xf4f0e6;
  return (0xff00_0000 | rgb) | 0;
}

export function readerBackgroundCss(preferences: ReaderPreferencesV3): string {
  return preferences.background === "custom"
    ? argbToCss(preferences.customBackgroundArgb)
    : BACKGROUNDS[preferences.background];
}

function rgbFromCss(value: string): [number, number, number] {
  const match = /^#([\da-f]{6})$/i.exec(value);
  const rgb = match ? Number.parseInt(match[1], 16) : 0;
  return [(rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff];
}

function relativeLuminance(value: string): number {
  const linear = rgbFromCss(value).map((component) => {
    const channel = component / 255;
    return channel <= 0.04045
      ? channel / 12.92
      : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
}

export function contrastRatio(first: string, second: string): number {
  const firstLuminance = relativeLuminance(first);
  const secondLuminance = relativeLuminance(second);
  const lighter = Math.max(firstLuminance, secondLuminance);
  const darker = Math.min(firstLuminance, secondLuminance);
  return (lighter + 0.05) / (darker + 0.05);
}

export function automaticReaderForeground(preferences: ReaderPreferencesV3): string {
  if (preferences.background !== "custom") return FOREGROUNDS[preferences.background];
  const background = readerBackgroundCss(preferences);
  const dark = "#171717";
  const light = "#f4f4f2";
  return contrastRatio(background, dark) >= contrastRatio(background, light) ? dark : light;
}

export function readerPalette(preferences: ReaderPreferencesV3): ReaderPalette {
  const background = readerBackgroundCss(preferences);
  const automatic = automaticReaderForeground(preferences);
  const requestedForeground = preferences.customForegroundArgb === null
    ? automatic
    : argbToCss(preferences.customForegroundArgb);
  const requestedContrast = contrastRatio(background, requestedForeground);
  const adjustedForContrast = requestedContrast < 4.5;
  const foreground = adjustedForContrast ? automatic : requestedForeground;
  return {
    background,
    requestedForeground,
    foreground,
    contrast: contrastRatio(background, foreground),
    adjustedForContrast,
  };
}

export function mapPdfPixelToReaderColors(
  red: number,
  green: number,
  blue: number,
  backgroundCss: string,
  foregroundCss: string,
): [number, number, number] {
  const sourceLuminance = Math.min(
    Math.max((0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255, 0),
    1,
  );
  const background = rgbFromCss(backgroundCss);
  const foreground = rgbFromCss(foregroundCss);
  return foreground.map((component, index) =>
    Math.round(component * (1 - sourceLuminance) + background[index] * sourceLuminance),
  ) as [number, number, number];
}

export function recolorPdfCanvas(
  canvas: HTMLCanvasElement,
  background: string,
  foreground: string,
): void {
  const context = canvas.getContext("2d", { willReadFrequently: true });
  if (!context || canvas.width < 1 || canvas.height < 1) return;
  // Process strips instead of cloning the entire high-DPI page at once. At
  // 300% zoom a full-page ImageData allocation can otherwise exceed 100 MiB.
  const rowsPerStrip = 192;
  for (let top = 0; top < canvas.height; top += rowsPerStrip) {
    const stripHeight = Math.min(rowsPerStrip, canvas.height - top);
    const image = context.getImageData(0, top, canvas.width, stripHeight);
    for (let index = 0; index < image.data.length; index += 4) {
      if (image.data[index + 3] === 0) continue;
      const mapped = mapPdfPixelToReaderColors(
        image.data[index],
        image.data[index + 1],
        image.data[index + 2],
        background,
        foreground,
      );
      image.data[index] = mapped[0];
      image.data[index + 1] = mapped[1];
      image.data[index + 2] = mapped[2];
    }
    context.putImageData(image, 0, top);
  }
}
