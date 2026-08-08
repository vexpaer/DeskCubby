import { describe, expect, it } from "vitest";

import {
  DESKTOP_NAVIGATION_ITEM_IDS,
  MAX_DESKTOP_NAVIGATION_CATEGORIES,
  createDefaultDesktopNavigationPreferences,
  firstVisibleNavigationItemId,
  normalizeDesktopNavigationPreferences,
} from "./desktopNavigationModel";

describe("desktopNavigationModel", () => {
  it("keeps every desktop destination exactly once in the defaults", () => {
    const preferences = createDefaultDesktopNavigationPreferences();
    const itemIds = preferences.categories.flatMap((category) => category.itemIds);

    expect(itemIds).toEqual(DESKTOP_NAVIGATION_ITEM_IDS);
    expect(new Set(itemIds).size).toBe(DESKTOP_NAVIGATION_ITEM_IDS.length);
    expect(preferences.hiddenItemIds).toEqual([]);
  });

  it("repairs duplicate, unknown and missing entries from persisted storage", () => {
    const repaired = normalizeDesktopNavigationPreferences({
      categories: [
        {
          id: "custom",
          chinese: "  我的分类  ",
          english: "  Mine  ",
          itemIds: ["diary", "diary", "unknown"],
        },
        {
          id: "custom",
          chinese: "",
          english: "",
          itemIds: ["home", "diary"],
        },
      ],
      hiddenItemIds: ["diary", "diary", "unknown"],
    });
    const itemIds = repaired.categories.flatMap((category) => category.itemIds);

    expect(repaired.categories[0]).toMatchObject({
      id: "custom",
      chinese: "我的分类",
      english: "Mine",
    });
    expect(repaired.categories[1].id).not.toBe("custom");
    expect(new Set(itemIds)).toEqual(new Set(DESKTOP_NAVIGATION_ITEM_IDS));
    expect(itemIds).toHaveLength(DESKTOP_NAVIGATION_ITEM_IDS.length);
    expect(repaired.hiddenItemIds).toEqual(["diary"]);
  });

  it("bounds categories and removes control characters from their names", () => {
    const categories = Array.from(
      { length: MAX_DESKTOP_NAVIGATION_CATEGORIES + 1 },
      (_, index) => ({
        id: `custom-${index}`,
        chinese: index === 0 ? "  我\u0000的\u202e分类  " : `分类 ${index}`,
        english: index === 0 ? "  My\u0085\u200b group  " : `Group ${index}`,
        itemIds: index === 0 ? [...DESKTOP_NAVIGATION_ITEM_IDS] : [],
      }),
    );

    const repaired = normalizeDesktopNavigationPreferences({
      categories,
      hiddenItemIds: [],
    });

    expect(repaired.categories).toHaveLength(MAX_DESKTOP_NAVIGATION_CATEGORIES);
    expect(repaired.categories[0]).toMatchObject({
      chinese: "我的分类",
      english: "My group",
    });
    expect(repaired.categories.at(-1)?.id).toBe(
      `custom-${MAX_DESKTOP_NAVIGATION_CATEGORIES - 1}`,
    );
  });

  it("uses category and page order when choosing a safe visible fallback", () => {
    const preferences = createDefaultDesktopNavigationPreferences();
    preferences.categories[0].itemIds = ["daily", "home", "diary", "meals"];
    preferences.hiddenItemIds = ["daily"];

    expect(firstVisibleNavigationItemId(preferences)).toBe("home");
    preferences.hiddenItemIds = [...DESKTOP_NAVIGATION_ITEM_IDS];
    expect(firstVisibleNavigationItemId(preferences)).toBeNull();
  });
});
