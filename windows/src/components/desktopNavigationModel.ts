export const DESKTOP_NAVIGATION_ITEM_IDS = [
  "home",
  "diary",
  "meals",
  "daily",
  "notes",
  "thoughts",
  "dates",
  "poetry",
  "reader",
  "rss",
  "ai",
  "vault",
  "games",
  "statistics",
  "usage",
  "health",
  "more",
  "backup",
] as const;

export type DesktopNavigationItemId =
  (typeof DESKTOP_NAVIGATION_ITEM_IDS)[number];

export interface DesktopNavigationCategory {
  id: string;
  chinese: string;
  english: string;
  itemIds: DesktopNavigationItemId[];
}

export interface DesktopNavigationPreferences {
  categories: DesktopNavigationCategory[];
  hiddenItemIds: DesktopNavigationItemId[];
}

const ITEM_ID_SET = new Set<string>(DESKTOP_NAVIGATION_ITEM_IDS);
export const MAX_DESKTOP_NAVIGATION_CATEGORIES = 32;
export const MAX_DESKTOP_NAVIGATION_CATEGORY_NAME_LENGTH = 60;
const MAX_CATEGORY_ID_LENGTH = 64;

function isDesktopNavigationNameControlCharacter(character: string): boolean {
  const codePoint = character.codePointAt(0) ?? 0;
  return (
    codePoint <= 0x1f ||
    (codePoint >= 0x7f && codePoint <= 0x9f) ||
    (codePoint >= 0x200b && codePoint <= 0x200f) ||
    (codePoint >= 0x202a && codePoint <= 0x202e) ||
    (codePoint >= 0x2060 && codePoint <= 0x206f) ||
    codePoint === 0xfeff
  );
}

const DEFAULT_CATEGORIES: readonly DesktopNavigationCategory[] = [
  {
    id: "capture",
    chinese: "记录",
    english: "Capture",
    itemIds: ["home", "diary", "meals", "daily"],
  },
  {
    id: "library",
    chinese: "资料库",
    english: "Library",
    itemIds: ["notes", "thoughts", "dates", "poetry", "reader"],
  },
  {
    id: "connected",
    chinese: "订阅与智能",
    english: "Feeds & intelligence",
    itemIds: ["rss", "ai", "vault"],
  },
  {
    id: "insights",
    chinese: "娱乐与统计",
    english: "Play & insights",
    itemIds: ["games", "statistics", "usage", "health"],
  },
  {
    id: "tools",
    chinese: "工具",
    english: "Tools",
    itemIds: ["more", "backup"],
  },
];

function cloneCategory(
  category: DesktopNavigationCategory,
): DesktopNavigationCategory {
  return { ...category, itemIds: [...category.itemIds] };
}

export function createDefaultDesktopNavigationPreferences(): DesktopNavigationPreferences {
  return {
    categories: DEFAULT_CATEGORIES.map(cloneCategory),
    hiddenItemIds: [],
  };
}

export function sanitizeDesktopNavigationCategoryName(value: unknown): string {
  return typeof value === "string"
    ? Array.from(value)
        .filter(
          (character) =>
            !isDesktopNavigationNameControlCharacter(character),
        )
        .join("")
        .trim()
        .slice(0, MAX_DESKTOP_NAVIGATION_CATEGORY_NAME_LENGTH)
    : "";
}

function safeCategoryId(value: unknown, index: number, used: Set<string>): string {
  const candidate =
    typeof value === "string" &&
    value.length <= MAX_CATEGORY_ID_LENGTH &&
    /^[a-z0-9][a-z0-9_-]*$/i.test(value)
      ? value
      : `category-${index + 1}`;
  let id = candidate;
  let suffix = 2;
  while (used.has(id)) {
    id = `${candidate.slice(0, MAX_CATEGORY_ID_LENGTH - 4)}-${suffix}`;
    suffix += 1;
  }
  used.add(id);
  return id;
}

function categoryNameFallback(
  id: string,
  index: number,
  language: "chinese" | "english",
): string {
  const defaultCategory = DEFAULT_CATEGORIES.find((category) => category.id === id);
  if (defaultCategory) return defaultCategory[language];
  return language === "chinese" ? `分类 ${index + 1}` : `Category ${index + 1}`;
}

/**
 * Treat persisted WebView storage as untrusted input. Every known destination
 * is retained exactly once even when an older or partially-written preference
 * is recovered; visibility remains a separate, bounded list.
 */
export function normalizeDesktopNavigationPreferences(
  input: unknown,
): DesktopNavigationPreferences {
  if (!input || typeof input !== "object") {
    return createDefaultDesktopNavigationPreferences();
  }
  const candidate = input as Partial<DesktopNavigationPreferences>;
  if (!Array.isArray(candidate.categories) || candidate.categories.length === 0) {
    return createDefaultDesktopNavigationPreferences();
  }

  const usedCategoryIds = new Set<string>();
  const usedItemIds = new Set<DesktopNavigationItemId>();
  const rawCategories: unknown[] = candidate.categories;
  const categories = rawCategories
    .slice(0, MAX_DESKTOP_NAVIGATION_CATEGORIES)
    .filter((value): value is Record<string, unknown> =>
      Boolean(value && typeof value === "object"),
    )
    .map((category, index) => {
      const id = safeCategoryId(category.id, index, usedCategoryIds);
      const itemIds: DesktopNavigationItemId[] = [];
      if (Array.isArray(category.itemIds)) {
        category.itemIds.forEach((itemId) => {
          if (
            typeof itemId === "string" &&
            ITEM_ID_SET.has(itemId) &&
            !usedItemIds.has(itemId as DesktopNavigationItemId)
          ) {
            const validId = itemId as DesktopNavigationItemId;
            usedItemIds.add(validId);
            itemIds.push(validId);
          }
        });
      }
      return {
        id,
        chinese:
          sanitizeDesktopNavigationCategoryName(category.chinese) ||
          categoryNameFallback(id, index, "chinese"),
        english:
          sanitizeDesktopNavigationCategoryName(category.english) ||
          categoryNameFallback(id, index, "english"),
        itemIds,
      };
    });

  if (categories.length === 0) {
    return createDefaultDesktopNavigationPreferences();
  }

  DEFAULT_CATEGORIES.forEach((defaultCategory) => {
    const target =
      categories.find((category) => category.id === defaultCategory.id) ??
      categories[0];
    defaultCategory.itemIds.forEach((itemId) => {
      if (!usedItemIds.has(itemId)) {
        target.itemIds.push(itemId);
        usedItemIds.add(itemId);
      }
    });
  });

  const hiddenItemIds: DesktopNavigationItemId[] = [];
  if (Array.isArray(candidate.hiddenItemIds)) {
    const seenHidden = new Set<DesktopNavigationItemId>();
    candidate.hiddenItemIds.forEach((itemId) => {
      if (
        typeof itemId === "string" &&
        ITEM_ID_SET.has(itemId) &&
        !seenHidden.has(itemId as DesktopNavigationItemId)
      ) {
        const validId = itemId as DesktopNavigationItemId;
        seenHidden.add(validId);
        hiddenItemIds.push(validId);
      }
    });
  }

  return { categories, hiddenItemIds };
}

export function cloneDesktopNavigationPreferences(
  preferences: DesktopNavigationPreferences,
): DesktopNavigationPreferences {
  return {
    categories: preferences.categories.map(cloneCategory),
    hiddenItemIds: [...preferences.hiddenItemIds],
  };
}

export function firstVisibleNavigationItemId(
  preferences: DesktopNavigationPreferences,
): DesktopNavigationItemId | null {
  const hidden = new Set(preferences.hiddenItemIds);
  for (const category of preferences.categories) {
    const visible = category.itemIds.find((itemId) => !hidden.has(itemId));
    if (visible) return visible;
  }
  return null;
}
