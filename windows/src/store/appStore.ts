import { create } from "zustand";
import { persist } from "zustand/middleware";
import {
  createDefaultDesktopNavigationPreferences,
  normalizeDesktopNavigationPreferences,
  type DesktopNavigationPreferences,
} from "../components/desktopNavigationModel";
import type {
  AppearanceSettings,
  AppLanguage,
  ColorMode,
  ToastMessage,
  VisualTheme,
} from "../types";

const DEFAULT_APPEARANCE: AppearanceSettings = {
  language: "zh-CN",
  visualTheme: "material",
  colorMode: "system",
  fontScale: 1,
  compactMode: false,
};

const DEFAULT_DESKTOP_NAVIGATION = createDefaultDesktopNavigationPreferences();

interface AppState {
  appearance: AppearanceSettings;
  sidebarCollapsed: boolean;
  desktopNavigation: DesktopNavigationPreferences;
  collapsedNavigationCategoryIds: string[];
  mobileNavigationOpen: boolean;
  dirtyScopes: string[];
  toasts: ToastMessage[];
  setLanguage: (language: AppLanguage) => void;
  setVisualTheme: (visualTheme: VisualTheme) => void;
  setColorMode: (colorMode: ColorMode) => void;
  setFontScale: (fontScale: number) => void;
  setCompactMode: (compactMode: boolean) => void;
  applyAppearance: (appearance: Partial<AppearanceSettings>) => void;
  resetAppearance: () => void;
  toggleSidebar: () => void;
  setDesktopNavigation: (preferences: DesktopNavigationPreferences) => void;
  toggleNavigationCategory: (categoryId: string) => void;
  setMobileNavigationOpen: (open: boolean) => void;
  markDirty: (scope: string, dirty: boolean) => void;
  addToast: (toast: Omit<ToastMessage, "id" | "createdAt">) => string;
  dismissToast: (id: string) => void;
}

function clampFontScale(fontScale: number): number {
  if (!Number.isFinite(fontScale)) {
    return DEFAULT_APPEARANCE.fontScale;
  }
  return Math.min(1.35, Math.max(0.85, Math.round(fontScale * 100) / 100));
}

function createToastId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export const useAppStore = create<AppState>()(
  persist(
    (set, get) => ({
      appearance: DEFAULT_APPEARANCE,
      sidebarCollapsed: false,
      desktopNavigation: DEFAULT_DESKTOP_NAVIGATION,
      collapsedNavigationCategoryIds: [],
      mobileNavigationOpen: false,
      dirtyScopes: [],
      toasts: [],
      setLanguage: (language) =>
        set((state) => ({ appearance: { ...state.appearance, language } })),
      setVisualTheme: (visualTheme) =>
        set((state) => ({ appearance: { ...state.appearance, visualTheme } })),
      setColorMode: (colorMode) =>
        set((state) => ({ appearance: { ...state.appearance, colorMode } })),
      setFontScale: (fontScale) =>
        set((state) => ({
          appearance: { ...state.appearance, fontScale: clampFontScale(fontScale) },
        })),
      setCompactMode: (compactMode) =>
        set((state) => ({ appearance: { ...state.appearance, compactMode } })),
      applyAppearance: (appearance) =>
        set((state) => ({
          appearance: {
            ...state.appearance,
            ...appearance,
            fontScale: clampFontScale(
              appearance.fontScale ?? state.appearance.fontScale,
            ),
          },
        })),
      resetAppearance: () => set({ appearance: DEFAULT_APPEARANCE }),
      toggleSidebar: () =>
        set((state) => ({ sidebarCollapsed: !state.sidebarCollapsed })),
      setDesktopNavigation: (preferences) =>
        set((state) => {
          const desktopNavigation = normalizeDesktopNavigationPreferences(preferences);
          const categoryIds = new Set(
            desktopNavigation.categories.map((category) => category.id),
          );
          return {
            desktopNavigation,
            collapsedNavigationCategoryIds:
              state.collapsedNavigationCategoryIds.filter((id) =>
                categoryIds.has(id),
              ),
          };
        }),
      toggleNavigationCategory: (categoryId) =>
        set((state) => {
          if (
            !state.desktopNavigation.categories.some(
              (category) => category.id === categoryId,
            )
          ) {
            return {};
          }
          const collapsed = new Set(state.collapsedNavigationCategoryIds);
          if (collapsed.has(categoryId)) collapsed.delete(categoryId);
          else collapsed.add(categoryId);
          return { collapsedNavigationCategoryIds: [...collapsed] };
        }),
      setMobileNavigationOpen: (mobileNavigationOpen) =>
        set({ mobileNavigationOpen }),
      markDirty: (scope, dirty) =>
        set((state) => {
          const scopes = new Set(state.dirtyScopes);
          if (dirty) {
            scopes.add(scope);
          } else {
            scopes.delete(scope);
          }
          return { dirtyScopes: [...scopes] };
        }),
      addToast: (toast) => {
        if (toast.dedupeKey) {
          const existing = get().toasts.find(
            (item) => item.dedupeKey === toast.dedupeKey,
          );
          if (existing) return existing.id;
        }
        const id = createToastId();
        set((state) => ({
          toasts: [
            ...state.toasts.slice(-3),
            { ...toast, id, createdAt: Date.now() },
          ],
        }));
        return id;
      },
      dismissToast: (id) =>
        set((state) => ({
          toasts: state.toasts.filter((toast) => toast.id !== id),
        })),
    }),
    {
      name: "deskcubby-window-preferences",
      version: 1,
      partialize: (state) => ({
        appearance: state.appearance,
        sidebarCollapsed: state.sidebarCollapsed,
        desktopNavigation: state.desktopNavigation,
        collapsedNavigationCategoryIds: state.collapsedNavigationCategoryIds,
      }),
      merge: (persistedState, currentState) => {
        const persisted = (persistedState ?? {}) as Partial<AppState>;
        const desktopNavigation = normalizeDesktopNavigationPreferences(
          persisted.desktopNavigation,
        );
        const categoryIds = new Set(
          desktopNavigation.categories.map((category) => category.id),
        );
        return {
          ...currentState,
          appearance: {
            ...currentState.appearance,
            ...(persisted.appearance ?? {}),
          },
          sidebarCollapsed:
            typeof persisted.sidebarCollapsed === "boolean"
              ? persisted.sidebarCollapsed
              : currentState.sidebarCollapsed,
          desktopNavigation,
          collapsedNavigationCategoryIds: Array.isArray(
            persisted.collapsedNavigationCategoryIds,
          )
            ? persisted.collapsedNavigationCategoryIds.filter(
                (id): id is string =>
                  typeof id === "string" && categoryIds.has(id),
              )
            : [],
          mobileNavigationOpen: false,
          dirtyScopes: [],
          toasts: [],
        };
      },
    },
  ),
);

export { DEFAULT_APPEARANCE, DEFAULT_DESKTOP_NAVIGATION };
