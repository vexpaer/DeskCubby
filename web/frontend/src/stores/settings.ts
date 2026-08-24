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

type LockableScreenOrientation = ScreenOrientation & {
  lock?: (orientation: "portrait-primary" | "landscape-primary") => Promise<void>;
  unlock?: () => void;
};

function applyScreenOrientation(preference: AppSettings["orientationPreference"]): void {
  const orientation = window.screen?.orientation as LockableScreenOrientation | undefined;
  if (!orientation) return;
  if (preference === "AUTO") {
    try { orientation.unlock?.(); } catch { /* browser/platform does not expose unlock */ }
    return;
  }
  // Most browsers only permit orientation locking for an installed/fullscreen
  // experience. Avoid prompting or throwing in an ordinary tab.
  const standalone = window.matchMedia?.("(display-mode: standalone)").matches === true ||
    (navigator as Navigator & { standalone?: boolean }).standalone === true ||
    document.fullscreenElement != null;
  if (!standalone || typeof orientation.lock !== "function") return;
  void orientation.lock(preference === "PORTRAIT" ? "portrait-primary" : "landscape-primary")
    .catch(() => undefined);
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
  document.documentElement.lang = ({
    CHINESE: "zh-CN",
    TRADITIONAL_CHINESE: "zh-TW",
    ENGLISH: "en",
    KOREAN: "ko",
    JAPANESE: "ja",
  } as const)[s.appLanguage] ?? "zh-CN";
  applyScreenOrientation(s.orientationPreference);
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

// SYSTEM mode follows changes made while the app is already open. The old
// implementation sampled matchMedia only during settings loads/updates.
if (typeof window !== "undefined" && window.matchMedia) {
  const systemScheme = window.matchMedia("(prefers-color-scheme: dark)");
  const refreshSystemTheme = () => {
    const settings = useSettings.getState().settings;
    if (settings?.darkMode === "SYSTEM") applySettingsTheme(settings);
  };
  if (typeof systemScheme.addEventListener === "function") {
    systemScheme.addEventListener("change", refreshSystemTheme);
  } else {
    systemScheme.addListener(refreshSystemTheme);
  }
}

export function useSettingsOrThrow(): AppSettings {
  const s = useSettings((st) => st.settings);
  if (!s) throw new Error("settings not loaded");
  return s;
}
