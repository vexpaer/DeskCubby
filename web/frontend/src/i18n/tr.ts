/**
 * tr("中文", "English") — mirrors Android's two-argument translation helper.
 * Simplified Chinese is the source language; Traditional Chinese / Korean / Japanese
 * come from the converted AppTranslations.kt tables (missing entries fall back to
 * Simplified Chinese). Placeholders like ${count} are interpolated by the caller.
 */
import translations from "./translations.json";

export type UiLanguage = "CHINESE" | "TRADITIONAL_CHINESE" | "ENGLISH" | "KOREAN" | "JAPANESE";

const TABLES = translations as Record<string, Record<string, string>>;

let currentLanguage: UiLanguage = "CHINESE";

export function setUiLanguage(lang: UiLanguage): void {
  currentLanguage = lang;
}

export function uiLanguage(): UiLanguage {
  return currentLanguage;
}

export function tr(zh: string, en: string): string {
  switch (currentLanguage) {
    case "ENGLISH":
      return en;
    case "CHINESE":
      return zh;
    case "TRADITIONAL_CHINESE":
      return TABLES["zh-TW"]?.[zh] ?? zh;
    case "KOREAN":
      return TABLES["ko"]?.[zh] ?? zh;
    case "JAPANESE":
      return TABLES["ja"]?.[zh] ?? zh;
    default:
      return zh;
  }
}

/** Language code for html lang attribute and date formatting. */
export function localeTag(): string {
  switch (currentLanguage) {
    case "TRADITIONAL_CHINESE":
      return "zh-TW";
    case "ENGLISH":
      return "en";
    case "KOREAN":
      return "ko";
    case "JAPANESE":
      return "ja";
    default:
      return "zh-CN";
  }
}
