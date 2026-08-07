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

export interface DesktopNavigationItem {
  id: string;
  to: string;
  label: MessageKey;
  icon: LucideIcon;
  end?: boolean;
}

export interface DesktopNavigationSection {
  label: MessageKey;
  items: DesktopNavigationItem[];
}

/**
 * Windows exposes every Android 0.9.3 destination except the Android-only
 * browser and launcher widgets. Meal calendar and daily capture remain
 * first-class desktop shortcuts even though Android reaches them through
 * Diary and Home.
 */
export const DESKTOP_NAVIGATION_SECTIONS: readonly DesktopNavigationSection[] = [
  {
    label: "nav.capture",
    items: [
      { id: "home", to: "/", label: "nav.home", icon: Home, end: true },
      { id: "diary", to: "/diary", label: "nav.diary", icon: NotebookPen },
      { id: "meals", to: "/meals", label: "nav.meals", icon: Soup },
      { id: "daily", to: "/daily", label: "nav.daily", icon: Sparkles },
    ],
  },
  {
    label: "nav.library",
    items: [
      { id: "notes", to: "/notes", label: "nav.notes", icon: FileText },
      { id: "thoughts", to: "/thoughts", label: "nav.thoughts", icon: Lightbulb },
      { id: "dates", to: "/dates", label: "nav.dates", icon: CalendarDays },
      { id: "poetry", to: "/poetry", label: "nav.poetry", icon: BookHeart },
      { id: "reader", to: "/reader", label: "nav.reader", icon: BookOpen },
    ],
  },
  {
    label: "nav.connected",
    items: [
      { id: "rss", to: "/rss", label: "nav.rss", icon: Rss },
      { id: "ai", to: "/ai", label: "nav.ai", icon: Bot },
      { id: "vault", to: "/vault", label: "nav.vault", icon: LockKeyhole },
    ],
  },
  {
    label: "nav.insights",
    items: [
      { id: "games", to: "/games", label: "nav.games", icon: Gamepad2 },
      {
        id: "statistics",
        to: "/statistics",
        label: "nav.statistics",
        icon: ChartNoAxesCombined,
      },
      { id: "usage", to: "/usage", label: "nav.usage", icon: Hourglass },
      { id: "health", to: "/health", label: "nav.health", icon: HeartPulse },
    ],
  },
  {
    label: "nav.tools",
    items: [
      { id: "more", to: "/more", label: "nav.more", icon: PanelsTopLeft },
      { id: "backup", to: "/backup", label: "nav.backup", icon: ArchiveRestore },
    ],
  },
];
