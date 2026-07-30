export type AppLanguage = "zh-CN" | "en";

export type VisualTheme = "material" | "liquid-glass" | "organic-future";

export type ColorMode = "system" | "light" | "dark";

export type PageId =
  | "home"
  | "diary"
  | "meals"
  | "daily"
  | "thoughts"
  | "dates"
  | "poetry"
  | "vault"
  | "usage"
  | "backup"
  | "settings";

export interface AppearanceSettings {
  language: AppLanguage;
  visualTheme: VisualTheme;
  colorMode: ColorMode;
  fontScale: number;
  compactMode: boolean;
}

export type AsyncStatus = "idle" | "loading" | "success" | "error";

export interface AppError {
  code: string;
  message: string;
  retryable?: boolean;
}

export interface ToastMessage {
  id: string;
  kind: "success" | "info" | "warning" | "error";
  title: string;
  detail?: string;
  action?: {
    label: string;
    to: string;
  };
  dedupeKey?: string;
  persistent?: boolean;
  createdAt: number;
}
