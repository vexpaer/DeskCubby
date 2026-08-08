import {
  ArchiveRestore,
  Bot,
  BookHeart,
  BookOpen,
  CalendarDays,
  ChartNoAxesCombined,
  FileText,
  Gamepad2,
  HeartPulse,
  Home,
  Hourglass,
  Lightbulb,
  LockKeyhole,
  NotebookPen,
  PanelsTopLeft,
  Rss,
  Soup,
  Sparkles,
  type LucideIcon,
} from "lucide-react";

import type { MessageKey } from "../i18n";
import type { AppLanguage } from "../types";
import {
  createDefaultDesktopNavigationPreferences,
  firstVisibleNavigationItemId,
  type DesktopNavigationCategory,
  type DesktopNavigationItemId,
  type DesktopNavigationPreferences,
} from "./desktopNavigationModel";

export interface DesktopNavigationItem {
  id: DesktopNavigationItemId;
  to: string;
  label: MessageKey;
  icon: LucideIcon;
  end?: boolean;
}

export interface DesktopNavigationSection extends DesktopNavigationCategory {
  items: DesktopNavigationItem[];
}

/**
 * Windows exposes every implemented desktop destination. Visibility, order and
 * grouping are local UI preferences; routes remain available to deep links.
 */
export const DESKTOP_NAVIGATION_ITEMS = [
  { id: "home", to: "/", label: "nav.home", icon: Home, end: true },
  { id: "diary", to: "/diary", label: "nav.diary", icon: NotebookPen },
  { id: "meals", to: "/meals", label: "nav.meals", icon: Soup },
  { id: "daily", to: "/daily", label: "nav.daily", icon: Sparkles },
  { id: "notes", to: "/notes", label: "nav.notes", icon: FileText },
  { id: "thoughts", to: "/thoughts", label: "nav.thoughts", icon: Lightbulb },
  { id: "dates", to: "/dates", label: "nav.dates", icon: CalendarDays },
  { id: "poetry", to: "/poetry", label: "nav.poetry", icon: BookHeart },
  { id: "reader", to: "/reader", label: "nav.reader", icon: BookOpen },
  { id: "rss", to: "/rss", label: "nav.rss", icon: Rss },
  { id: "ai", to: "/ai", label: "nav.ai", icon: Bot },
  { id: "vault", to: "/vault", label: "nav.vault", icon: LockKeyhole },
  { id: "games", to: "/games", label: "nav.games", icon: Gamepad2 },
  {
    id: "statistics",
    to: "/statistics",
    label: "nav.statistics",
    icon: ChartNoAxesCombined,
  },
  { id: "usage", to: "/usage", label: "nav.usage", icon: Hourglass },
  { id: "health", to: "/health", label: "nav.health", icon: HeartPulse },
  { id: "more", to: "/more", label: "nav.more", icon: PanelsTopLeft },
  { id: "backup", to: "/backup", label: "nav.backup", icon: ArchiveRestore },
] as const satisfies readonly DesktopNavigationItem[];

const ITEMS_BY_ID = new Map<DesktopNavigationItemId, DesktopNavigationItem>(
  DESKTOP_NAVIGATION_ITEMS.map((item) => [item.id, item]),
);

export function desktopNavigationItem(
  id: DesktopNavigationItemId,
): DesktopNavigationItem {
  return ITEMS_BY_ID.get(id)!;
}

export function buildDesktopNavigationSections(
  preferences: DesktopNavigationPreferences,
  includeHidden = false,
): DesktopNavigationSection[] {
  const hidden = new Set(preferences.hiddenItemIds);
  return preferences.categories.map((category) => ({
    ...category,
    itemIds: [...category.itemIds],
    items: category.itemIds
      .filter((itemId) => includeHidden || !hidden.has(itemId))
      .map((itemId) => desktopNavigationItem(itemId)),
  }));
}

export function desktopNavigationCategoryName(
  category: Pick<DesktopNavigationCategory, "chinese" | "english">,
  language: AppLanguage,
): string {
  return language === "en" ? category.english : category.chinese;
}

export function desktopNavigationItemForPath(
  pathname: string,
): DesktopNavigationItem | undefined {
  return [...DESKTOP_NAVIGATION_ITEMS]
    .sort((left, right) => right.to.length - left.to.length)
    .find((item) =>
      item.to === "/"
        ? pathname === "/"
        : pathname === item.to || pathname.startsWith(`${item.to}/`),
    );
}

export function firstVisibleNavigationPath(
  preferences: DesktopNavigationPreferences,
): string {
  const itemId = firstVisibleNavigationItemId(preferences);
  return itemId ? desktopNavigationItem(itemId).to : "/settings";
}

export const DESKTOP_NAVIGATION_SECTIONS = buildDesktopNavigationSections(
  createDefaultDesktopNavigationPreferences(),
);

export type {
  DesktopNavigationCategory,
  DesktopNavigationItemId,
  DesktopNavigationPreferences,
} from "./desktopNavigationModel";
export {
  cloneDesktopNavigationPreferences,
  createDefaultDesktopNavigationPreferences,
  MAX_DESKTOP_NAVIGATION_CATEGORIES,
  MAX_DESKTOP_NAVIGATION_CATEGORY_NAME_LENGTH,
  normalizeDesktopNavigationPreferences,
  sanitizeDesktopNavigationCategoryName,
} from "./desktopNavigationModel";
