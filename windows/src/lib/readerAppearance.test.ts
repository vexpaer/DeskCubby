import { describe, expect, it } from "vitest";

import {
  contrastRatio,
  mapPdfPixelToReaderColors,
  readerPalette,
} from "./readerAppearance";
import type { ReaderPreferencesV3 } from "./readerApi";

const preferences: ReaderPreferencesV3 = {
  background: "paper",
  customBackgroundArgb: -724762,
  customForegroundArgb: null,
  fontSizePx: 19,
  fontFamily: "serif",
  lineHeightMultiplier: 1.6,
  paragraphSpacingPx: 10,
  contentWidthPx: 960,
  textAlignment: "start",
  firstLineIndentEm: 0,
  letterSpacingPx: 0,
  pagePaddingPx: 36,
  pdfZoomPercent: 100,
  pdfColorMode: "original",
  pdfScrollMode: "continuous",
  pdfPageGapPx: 18,
  immersiveMode: false,
  showProgressPercentage: false,
  libraryLayout: "list",
  showGridBookTitles: true,
  chapterDetectionMode: "smartAndCustom",
  customChapterRegex: "",
  chapterHeadingMaxChars: 160,
};

describe("reader appearance", () => {
  it("keeps every effective text palette above normal-text contrast", () => {
    const palette = readerPalette({
      ...preferences,
      background: "custom",
      customBackgroundArgb: 0xff33_3333 | 0,
      customForegroundArgb: 0xff38_3838 | 0,
    });
    expect(palette.adjustedForContrast).toBe(true);
    expect(palette.contrast).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(palette.background, palette.foreground)).toBe(palette.contrast);
  });

  it("maps white and black PDF pixels to the selected reader colors", () => {
    expect(mapPdfPixelToReaderColors(255, 255, 255, "#ead9b9", "#382d20"))
      .toEqual([234, 217, 185]);
    expect(mapPdfPixelToReaderColors(0, 0, 0, "#ead9b9", "#382d20"))
      .toEqual([56, 45, 32]);
  });
});
