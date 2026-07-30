import { useEffect, useState, type PropsWithChildren } from "react";
import {
  invokeCommand,
  verifyIpcProtocol,
  type WindowsSettings,
} from "../lib/ipc";
import { useAppStore } from "../store/appStore";
import type { AppearanceSettings } from "../types";
import { LoadingState } from "./AsyncState";

type WindowsAppearanceSettings = Pick<
  WindowsSettings,
  | "visualStyle"
  | "darkMode"
  | "appLanguage"
  | "themeColorArgb"
  | "themeSecondaryColorsArgb"
  | "fontScale"
  | "compactMode"
>;

function argbToHex(argb: number): string {
  return `#${((argb >>> 0) & 0xffffff).toString(16).padStart(6, "0")}`;
}

function appearanceFromWindowsSettings(
  settings: WindowsAppearanceSettings,
): Partial<AppearanceSettings> {
  const appearance: Partial<AppearanceSettings> = {};
  const themes = {
    MATERIAL: "material",
    LIQUID_GLASS: "liquid-glass",
    ORGANIC_FUTURE: "organic-future",
  } as const satisfies Record<
    WindowsSettings["visualStyle"],
    AppearanceSettings["visualTheme"]
  >;
  const colorModes = {
    SYSTEM: "system",
    LIGHT: "light",
    DARK: "dark",
  } as const satisfies Record<
    WindowsSettings["darkMode"],
    AppearanceSettings["colorMode"]
  >;
  appearance.visualTheme = themes[settings.visualStyle];
  appearance.colorMode = colorModes[settings.darkMode];
  appearance.language =
    settings.appLanguage === "ENGLISH" ? "en" : "zh-CN";
  appearance.fontScale = settings.fontScale;
  appearance.compactMode = settings.compactMode;
  return appearance;
}

function useSystemDarkMode(): boolean {
  const [dark, setDark] = useState(
    () =>
      typeof window !== "undefined" &&
      window.matchMedia("(prefers-color-scheme: dark)").matches,
  );

  useEffect(() => {
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const update = (event: MediaQueryListEvent) => setDark(event.matches);
    setDark(media.matches);
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);

  return dark;
}

export function ThemeRuntime({ children }: PropsWithChildren) {
  const appearance = useAppStore((state) => state.appearance);
  const applyAppearance = useAppStore((state) => state.applyAppearance);
  const [protocolReady, setProtocolReady] = useState(false);
  const [protocolError, setProtocolError] = useState<Error | null>(null);
  const systemDark = useSystemDarkMode();
  const resolvedMode =
    appearance.colorMode === "system"
      ? systemDark
        ? "dark"
        : "light"
      : appearance.colorMode;

  useEffect(() => {
    const root = document.documentElement;
    root.dataset.visualTheme = appearance.visualTheme;
    root.dataset.colorScheme = resolvedMode;
    root.dataset.compact = String(appearance.compactMode);
    root.style.setProperty("--font-scale", String(appearance.fontScale));
    root.lang = appearance.language;
    root.style.colorScheme = resolvedMode;
  }, [appearance, resolvedMode]);

  useEffect(() => {
    let active = true;
    const syncSettingsSnapshot = (
      settings: WindowsAppearanceSettings | undefined,
    ) => {
      if (!settings) return;
      applyAppearance(appearanceFromWindowsSettings(settings));
      const root = document.documentElement;
      if (typeof settings.themeColorArgb === "number") {
        const primary = argbToHex(settings.themeColorArgb);
        root.style.setProperty("--primary", primary);
        root.style.setProperty(
          "--primary-hover",
          `color-mix(in srgb, ${primary} 84%, black)`,
        );
      }
      const secondary = settings.themeSecondaryColorsArgb?.[0];
      if (typeof secondary === "number") {
        root.style.setProperty("--secondary", argbToHex(secondary));
      }
    };
    const syncSettings = (event: Event) => {
      syncSettingsSnapshot(
        (event as CustomEvent<WindowsAppearanceSettings>).detail,
      );
    };
    const reloadSettings = async () => {
      const settings =
        await invokeCommand<WindowsSettings>("get_windows_settings");
      if (active) syncSettingsSnapshot(settings);
    };
    const reloadRestoredSettings = () => {
      void reloadSettings().catch(() => {
        // The restore already completed in Rust. Keep the current safe visual
        // state if its follow-up settings read is temporarily unavailable.
      });
    };
    window.addEventListener("deskcubby:settings-changed", syncSettings);
    window.addEventListener("deskcubby:data-restored", reloadRestoredSettings);
    void (async () => {
      try {
        await verifyIpcProtocol();
        if (!active) return;
        try {
          await reloadSettings();
        } catch {
          // Appearance already has a safe local default. A settings read failure
          // must not expose backend details or make the desktop shell unusable.
        }
        if (active) setProtocolReady(true);
      } catch (reason) {
        if (!active) return;
        setProtocolError(
          reason instanceof Error ? reason : new Error("unexpected_error"),
        );
      }
    })();
    return () => {
      active = false;
      window.removeEventListener("deskcubby:settings-changed", syncSettings);
      window.removeEventListener(
        "deskcubby:data-restored",
        reloadRestoredSettings,
      );
    };
  }, [applyAppearance]);

  if (protocolError) {
    throw protocolError;
  }
  if (!protocolReady) {
    return (
      <div className="startup-screen">
        <LoadingState />
      </div>
    );
  }
  return children;
}
