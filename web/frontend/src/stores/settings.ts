/** Global settings store: loads /api/settings once, applies theme, exposes update(). */
import { create } from "zustand";
import { apiGet, apiSend } from "../api/client";
import type { AppSettings } from "../api/types";
import { applyThemeVariables } from "../theme/colorScheme";
import { setUiLanguage } from "../i18n/tr";

interface SettingsState {
  settings: AppSettings | null;
  loaded: boolean;
  load: () => Promise<void>;
  update: (patch: Partial<AppSettings>) => Promise<void>;
  refresh: () => Promise<void>;
}

function systemDark(): boolean {
  return window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? false;
}

export function applySettingsTheme(s: AppSettings): void {
  const dark = s.darkMode === "DARK" || (s.darkMode === "SYSTEM" && systemDark());
  applyThemeVariables({
    style: s.visualStyle,
    customTheme: s.customTheme,
    dark,
    seedArgb: s.themeColorArgb,
    secondaryArgb: s.themeSecondaryColorsArgb,
    fontScale: s.fontScale,
    compactMode: s.compactMode,
  });
  setUiLanguage(s.appLanguage);
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) {
    const v = s.themeColorArgb >>> 0;
    meta.setAttribute("content", `rgb(${(v >>> 16) & 0xff},${(v >>> 8) & 0xff},${v & 0xff})`);
  }
}

export const useSettings = create<SettingsState>((set, get) => ({
  settings: null,
  loaded: false,
  load: async () => {
    const settings = await apiGet<AppSettings>("/api/settings");
    applySettingsTheme(settings);
    set({ settings, loaded: true });
  },
  refresh: async () => {
    await get().load();
  },
  update: async (patch) => {
    const settings = await apiSend<AppSettings>("/api/settings", "PUT", patch);
    applySettingsTheme(settings);
    set({ settings });
  },
}));

export function useSettingsOrThrow(): AppSettings {
  const s = useSettings((st) => st.settings);
  if (!s) throw new Error("settings not loaded");
  return s;
}
