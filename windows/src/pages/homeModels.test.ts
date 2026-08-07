import { describe, expect, it } from "vitest";

import type { DateRecord, DiaryEntry } from "../lib/ipc";
import {
  nearestDateRecords,
  normalizeHomeConfiguration,
  writingStreak,
} from "./homeModels";

function diary(date: string): DiaryEntry {
  return {
    relativePath: `${date}.md`,
    fileName: `${date}.md`,
    title: date,
    date,
    month: date.slice(0, 7),
    excerpt: "",
    wordCount: 0,
    modifiedAt: `${date}T00:00:00Z`,
    trashed: false,
  };
}

function record(id: string, dateIso: string): DateRecord {
  return {
    id,
    name: id,
    icon: "🎯",
    dateIso,
    createdAt: "1",
    updatedAt: "1",
  };
}

describe("homeModels", () => {
  it("preserves configured order while skipping browser, weather, duplicates and unknown IDs", () => {
    const normalized = normalizeHomeConfiguration({
      homeWidgets: [
        "record_overview",
        "website",
        "notes",
        "record_overview",
        "weather",
        "game_shortcuts",
        "unknown",
      ],
      homeWidgetTitles: ["notes"],
      homeWidgetBordersEnabled: false,
      homeGameShortcuts: ["spider", "not-a-game", "2048_6"],
      mealButtonsUseIcons: true,
      mealButtonIcons: ["1", "1", "3", "4", "5", "6"],
    });

    expect(normalized.widgets).toEqual([
      "record_overview",
      "notes",
      "game_shortcuts",
    ]);
    expect([...normalized.titles]).toEqual(["notes"]);
    expect(normalized.borders).toBe(false);
    expect(normalized.gameShortcuts).toEqual(["spider", "2048_6"]);
    expect(normalized.mealButtonIcons).toEqual(["1", "1", "3", "4", "5", "6"]);
  });

  it("computes a streak from today or from yesterday when today has no entry", () => {
    const entries = [
      diary("2026-08-07"),
      diary("2026-08-06"),
      diary("2026-08-05"),
      diary("2026-08-03"),
    ];
    expect(writingStreak(entries, "2026-08-07")).toBe(3);
    expect(writingStreak(entries.slice(1), "2026-08-07")).toBe(2);
  });

  it("selects the two nearest upcoming and two most recent past date records", () => {
    const records = [
      record("future-far", "2026-09-01"),
      record("past-far", "2026-01-01"),
      record("today", "2026-08-07"),
      record("future-near", "2026-08-08"),
      record("past-near", "2026-08-06"),
      record("past-second", "2026-08-05"),
    ];
    expect(nearestDateRecords(records, "2026-08-07").map(({ id }) => id)).toEqual([
      "today",
      "future-near",
      "past-near",
      "past-second",
    ]);
  });
});
