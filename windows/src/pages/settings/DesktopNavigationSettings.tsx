import {
  ArrowDown,
  ArrowUp,
  LayoutDashboard,
  Plus,
  Trash2,
} from "lucide-react";
import type { Dispatch, SetStateAction } from "react";

import {
  desktopNavigationItem,
  MAX_DESKTOP_NAVIGATION_CATEGORIES,
  MAX_DESKTOP_NAVIGATION_CATEGORY_NAME_LENGTH,
  sanitizeDesktopNavigationCategoryName,
  type DesktopNavigationItemId,
  type DesktopNavigationPreferences,
} from "../../components";
import { translate } from "../../i18n";
import type { AppLanguage } from "../../types";
import type { SettingsTranslator } from "./settingsRoutes";

interface DesktopNavigationSettingsProps {
  draft: DesktopNavigationPreferences;
  setDraft: Dispatch<SetStateAction<DesktopNavigationPreferences>>;
  language: AppLanguage;
  tr: SettingsTranslator;
}

function moveValue<T>(values: T[], index: number, delta: -1 | 1): T[] {
  const target = index + delta;
  if (target < 0 || target >= values.length) return values;
  const next = [...values];
  [next[index], next[target]] = [next[target], next[index]];
  return next;
}

function createCategoryId(existingIds: Set<string>): string {
  const random =
    typeof crypto !== "undefined" && "randomUUID" in crypto
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const base = `custom-${random}`.slice(0, 64);
  let candidate = base;
  let suffix = 2;
  while (existingIds.has(candidate)) {
    candidate = `${base.slice(0, 60)}-${suffix}`;
    suffix += 1;
  }
  return candidate;
}

export function DesktopNavigationSettings({
  draft,
  setDraft,
  language,
  tr,
}: DesktopNavigationSettingsProps) {
  const updateCategoryName = (
    categoryId: string,
    field: "chinese" | "english",
    value: string,
  ) => {
    setDraft((current) => ({
      ...current,
      categories: current.categories.map((category) =>
        category.id === categoryId
          ? {
              ...category,
              [field]: sanitizeDesktopNavigationCategoryName(value),
            }
          : category,
      ),
    }));
  };

  const moveCategory = (index: number, delta: -1 | 1) => {
    setDraft((current) => ({
      ...current,
      categories: moveValue(current.categories, index, delta),
    }));
  };

  const addCategory = () => {
    setDraft((current) => {
      if (current.categories.length >= MAX_DESKTOP_NAVIGATION_CATEGORIES) {
        return current;
      }
      return {
        ...current,
        categories: [
          ...current.categories,
          {
            id: createCategoryId(
              new Set(current.categories.map((category) => category.id)),
            ),
            chinese: "新分类",
            english: "New category",
            itemIds: [],
          },
        ],
      };
    });
  };

  const deleteCategory = (index: number) => {
    setDraft((current) => {
      if (current.categories.length <= 1) return current;
      const removed = current.categories[index];
      const targetIndex = index === 0 ? 1 : index - 1;
      return {
        ...current,
        categories: current.categories
          .map((category, categoryIndex) =>
            categoryIndex === targetIndex
              ? {
                  ...category,
                  itemIds: [...category.itemIds, ...removed.itemIds],
                }
              : category,
          )
          .filter((_, categoryIndex) => categoryIndex !== index),
      };
    });
  };

  const setItemVisible = (itemId: DesktopNavigationItemId, visible: boolean) => {
    setDraft((current) => {
      const hidden = new Set(current.hiddenItemIds);
      if (visible) hidden.delete(itemId);
      else hidden.add(itemId);
      return { ...current, hiddenItemIds: [...hidden] };
    });
  };

  const moveItem = (categoryId: string, index: number, delta: -1 | 1) => {
    setDraft((current) => ({
      ...current,
      categories: current.categories.map((category) =>
        category.id === categoryId
          ? { ...category, itemIds: moveValue(category.itemIds, index, delta) }
          : category,
      ),
    }));
  };

  const moveItemToCategory = (
    itemId: DesktopNavigationItemId,
    targetCategoryId: string,
  ) => {
    setDraft((current) => ({
      ...current,
      categories: current.categories.map((category) => ({
        ...category,
        itemIds:
          category.id === targetCategoryId
            ? category.itemIds.includes(itemId)
              ? category.itemIds
              : [...category.itemIds, itemId]
            : category.itemIds.filter((candidate) => candidate !== itemId),
      })),
    }));
  };

  return (
    <section className="panel settings-section desktop-navigation-settings">
      <div className="settings-section-heading">
        <LayoutDashboard size={20} aria-hidden="true" />
        <div>
          <h2>{tr("侧栏页面与分类", "Sidebar pages & categories")}</h2>
          <p>
            {tr(
              "在这里调整侧栏中的页面、顺序和分类。隐藏的页面仍可从“导航”页或其他功能入口打开；设置入口始终保留。",
              "Choose sidebar pages, order and categories here. Hidden pages remain available from Navigation or other feature links, and Settings is always retained.",
            )}
          </p>
        </div>
      </div>

      <div className="desktop-navigation-category-list">
        {draft.categories.map((category, categoryIndex) => (
          <article className="desktop-navigation-category" key={category.id}>
            <div className="desktop-navigation-category-heading">
              <div className="desktop-navigation-category-names">
                <label>
                  <span>{tr("中文分类名", "Chinese name")}</span>
                  <input
                    aria-label={tr(
                      `分类 ${categoryIndex + 1} 中文名称`,
                      `Category ${categoryIndex + 1} Chinese name`,
                    )}
                    maxLength={MAX_DESKTOP_NAVIGATION_CATEGORY_NAME_LENGTH}
                    value={category.chinese}
                    onChange={(event) =>
                      updateCategoryName(category.id, "chinese", event.target.value)
                    }
                  />
                </label>
                <label>
                  <span>{tr("英文分类名", "English name")}</span>
                  <input
                    aria-label={tr(
                      `分类 ${categoryIndex + 1} 英文名称`,
                      `Category ${categoryIndex + 1} English name`,
                    )}
                    maxLength={MAX_DESKTOP_NAVIGATION_CATEGORY_NAME_LENGTH}
                    value={category.english}
                    onChange={(event) =>
                      updateCategoryName(category.id, "english", event.target.value)
                    }
                  />
                </label>
              </div>
              <div className="desktop-navigation-order-actions">
                <button
                  className="icon-button"
                  type="button"
                  disabled={categoryIndex === 0}
                  aria-label={tr(
                    `上移分类 ${category.chinese}`,
                    `Move category ${category.english} up`,
                  )}
                  onClick={() => moveCategory(categoryIndex, -1)}
                >
                  <ArrowUp size={17} aria-hidden="true" />
                </button>
                <button
                  className="icon-button"
                  type="button"
                  disabled={categoryIndex === draft.categories.length - 1}
                  aria-label={tr(
                    `下移分类 ${category.chinese}`,
                    `Move category ${category.english} down`,
                  )}
                  onClick={() => moveCategory(categoryIndex, 1)}
                >
                  <ArrowDown size={17} aria-hidden="true" />
                </button>
                <button
                  className="icon-button danger"
                  type="button"
                  disabled={draft.categories.length <= 1}
                  aria-label={tr(
                    `删除分类 ${category.chinese}`,
                    `Delete category ${category.english}`,
                  )}
                  title={tr(
                    "删除后，其中页面会移入相邻分类",
                    "Its pages will move into a neighboring category",
                  )}
                  onClick={() => deleteCategory(categoryIndex)}
                >
                  <Trash2 size={17} aria-hidden="true" />
                </button>
              </div>
            </div>

            <div className="desktop-navigation-item-list">
              {category.itemIds.length === 0 ? (
                <p className="desktop-navigation-empty-category">
                  {tr(
                    "空分类。可从其他分类把页面移动到这里。",
                    "Empty category. Move a page here from another category.",
                  )}
                </p>
              ) : (
                category.itemIds.map((itemId, itemIndex) => {
                  const item = desktopNavigationItem(itemId);
                  const Icon = item.icon;
                  const label = translate(language, item.label);
                  return (
                    <div className="desktop-navigation-item-row" key={itemId}>
                      <label className="desktop-navigation-visibility">
                        <input
                          type="checkbox"
                          checked={!draft.hiddenItemIds.includes(itemId)}
                          aria-label={tr(
                            `在侧栏显示${label}`,
                            `Show ${label} in sidebar`,
                          )}
                          onChange={(event) =>
                            setItemVisible(itemId, event.target.checked)
                          }
                        />
                        <Icon size={18} aria-hidden="true" />
                        <span>{label}</span>
                      </label>
                      <label className="desktop-navigation-category-select">
                        <span className="sr-only">
                          {tr(`移动${label}到分类`, `Move ${label} to category`)}
                        </span>
                        <select
                          aria-label={tr(
                            `移动${label}到分类`,
                            `Move ${label} to category`,
                          )}
                          value={category.id}
                          onChange={(event) =>
                            moveItemToCategory(itemId, event.target.value)
                          }
                        >
                          {draft.categories.map((candidate) => (
                            <option key={candidate.id} value={candidate.id}>
                              {language === "en"
                                ? candidate.english
                                : candidate.chinese}
                            </option>
                          ))}
                        </select>
                      </label>
                      <div className="desktop-navigation-order-actions">
                        <button
                          className="icon-button"
                          type="button"
                          disabled={itemIndex === 0}
                          aria-label={tr(`上移${label}`, `Move ${label} up`)}
                          onClick={() => moveItem(category.id, itemIndex, -1)}
                        >
                          <ArrowUp size={16} aria-hidden="true" />
                        </button>
                        <button
                          className="icon-button"
                          type="button"
                          disabled={itemIndex === category.itemIds.length - 1}
                          aria-label={tr(`下移${label}`, `Move ${label} down`)}
                          onClick={() => moveItem(category.id, itemIndex, 1)}
                        >
                          <ArrowDown size={16} aria-hidden="true" />
                        </button>
                      </div>
                    </div>
                  );
                })
              )}
            </div>
          </article>
        ))}
      </div>

      <button
        className="button button-secondary"
        type="button"
        disabled={
          draft.categories.length >= MAX_DESKTOP_NAVIGATION_CATEGORIES
        }
        title={
          draft.categories.length >= MAX_DESKTOP_NAVIGATION_CATEGORIES
            ? tr(
                `最多可创建 ${MAX_DESKTOP_NAVIGATION_CATEGORIES} 个分类`,
                `You can create up to ${MAX_DESKTOP_NAVIGATION_CATEGORIES} categories`,
              )
            : undefined
        }
        onClick={addCategory}
      >
        <Plus size={17} aria-hidden="true" />
        {draft.categories.length >= MAX_DESKTOP_NAVIGATION_CATEGORIES
          ? tr(
              `已达 ${MAX_DESKTOP_NAVIGATION_CATEGORIES} 个分类上限`,
              `${MAX_DESKTOP_NAVIGATION_CATEGORIES}-category limit reached`,
            )
          : tr("新建分类", "New category")}
      </button>
    </section>
  );
}
