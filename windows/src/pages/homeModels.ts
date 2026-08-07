import type {
  DateRecord,
  DiaryEntry,
  WindowsSettings,
} from "../lib/ipc";

const DEFAULT_HOME_WIDGETS = [
  "today",
  "poem",
  "quick_input",
  "meal_photos",
  "year_progress",
  "notes",
  "game_shortcuts",
  "record_overview",
] as const;

const DEFAULT_HOME_TITLES = [
  "calendar",
  "weather",
  "poem",
  "today",
  "streak",
  "month_diaries",
  "total_words",
  "recent_diary",
  "recent_thought",
  "date_records",
  "quick_input",
  "daily_records",
  "meal_photos",
  "random_diary",
  "year_progress",
  "website",
  "notes",
  "game_shortcuts",
  "record_overview",
] as const;

export const HOME_WIDGET_IDS = [
  "calendar",
  "poem",
  "today",
  "date_records",
  "streak",
  "month_diaries",
  "total_words",
  "recent_diary",
  "recent_thought",
  "quick_input",
  "daily_records",
  "meal_photos",
  "random_diary",
  "year_progress",
  "notes",
  "game_shortcuts",
  "record_overview",
] as const;

export type HomeWidgetId = (typeof HOME_WIDGET_IDS)[number];

export interface HomeConfiguration {
  widgets: HomeWidgetId[];
  titles: Set<string>;
  borders: boolean;
  gameShortcuts: string[];
  mealButtonsUseIcons: boolean;
  mealButtonIcons: string[];
}

const HOME_WIDGET_ID_SET = new Set<string>(HOME_WIDGET_IDS);
const GAME_ID_SET = new Set([
  "2048",
  "2048_5",
  "2048_6",
  "snake",
  "tetris",
  "minesweeper",
  "spider",
]);
const DEFAULT_MEAL_ICONS = ["🥪", "🍱", "🍹", "🍜", "🍊", "🍤"];

function uniqueStrings(value: unknown, fallback: readonly string[]): string[] {
  if (!Array.isArray(value)) return [...fallback];
  return [...new Set(value.filter((item): item is string => typeof item === "string"))];
}

export function normalizeHomeConfiguration(
  settings: Partial<WindowsSettings> | null,
): HomeConfiguration {
  const configuredWidgets = uniqueStrings(
    settings?.homeWidgets,
    DEFAULT_HOME_WIDGETS,
  );
  const widgets = configuredWidgets.filter(
    (id): id is HomeWidgetId => HOME_WIDGET_ID_SET.has(id),
  );
  const titles = new Set(
    uniqueStrings(settings?.homeWidgetTitles, DEFAULT_HOME_TITLES),
  );
  const gameShortcuts = uniqueStrings(settings?.homeGameShortcuts, [
    "2048",
    "snake",
    "minesweeper",
  ]).filter((id) => GAME_ID_SET.has(id));
  const configuredIcons = Array.isArray(settings?.mealButtonIcons)
    ? settings.mealButtonIcons.filter(
        (item): item is string => typeof item === "string",
      )
    : DEFAULT_MEAL_ICONS;
  const mealButtonIcons = DEFAULT_MEAL_ICONS.map(
    (fallback, index) => configuredIcons[index]?.trim() || fallback,
  );
  return {
    widgets,
    titles,
    borders: settings?.homeWidgetBordersEnabled ?? true,
    gameShortcuts,
    mealButtonsUseIcons: settings?.mealButtonsUseIcons ?? false,
    mealButtonIcons,
  };
}

export function parseLocalDate(value: string): Date | null {
  const parsed = new Date(`${value}T00:00:00`);
  return Number.isNaN(parsed.valueOf()) ? null : parsed;
}

function toIsoDate(value: Date): string {
  return [
    value.getFullYear(),
    String(value.getMonth() + 1).padStart(2, "0"),
    String(value.getDate()).padStart(2, "0"),
  ].join("-");
}

export function writingStreak(diaries: readonly DiaryEntry[], today: string): number {
  const parsedToday = parseLocalDate(today);
  if (!parsedToday) return 0;
  const dates = new Set(diaries.map((entry) => entry.date));
  const cursor = new Date(parsedToday);
  if (!dates.has(today)) cursor.setDate(cursor.getDate() - 1);
  let count = 0;
  while (dates.has(toIsoDate(cursor))) {
    count += 1;
    cursor.setDate(cursor.getDate() - 1);
  }
  return count;
}

export function dayDistance(from: string, to: string): number | null {
  const left = parseLocalDate(from);
  const right = parseLocalDate(to);
  if (!left || !right) return null;
  return Math.round(
    (Date.UTC(right.getFullYear(), right.getMonth(), right.getDate()) -
      Date.UTC(left.getFullYear(), left.getMonth(), left.getDate())) /
      86_400_000,
  );
}

export function nearestDateRecords(
  records: readonly DateRecord[],
  today: string,
): DateRecord[] {
  const valid = records
    .map((record) => ({ record, distance: dayDistance(today, record.dateIso) }))
    .filter(
      (item): item is { record: DateRecord; distance: number } =>
        item.distance !== null,
    );
  const upcoming = valid
    .filter(({ distance }) => distance >= 0)
    .sort((left, right) =>
      left.distance === right.distance
        ? left.record.id.localeCompare(right.record.id)
        : left.distance - right.distance,
    )
    .slice(0, 2);
  const past = valid
    .filter(({ distance }) => distance < 0)
    .sort((left, right) =>
      left.distance === right.distance
        ? left.record.id.localeCompare(right.record.id)
        : right.distance - left.distance,
    )
    .slice(0, 2);
  return [...upcoming, ...past].map(({ record }) => record);
}
