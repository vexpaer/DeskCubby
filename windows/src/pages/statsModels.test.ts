import type { DiaryEntry } from "../lib/ipc";
import { deriveDiarySummary } from "./statsModels";

function diary(date: string, wordCount: number): DiaryEntry {
  return {
    relativePath: `${date}.md`,
    fileName: `${date}.md`,
    title: date,
    date,
    month: date.slice(0, 7),
    excerpt: "",
    wordCount,
    modifiedAt: "",
    trashed: false,
  };
}

describe("statistics hub diary derivation", () => {
  it("calculates current/longest civil-day streaks and continuous month buckets", () => {
    const result = deriveDiarySummary(
      [
        diary("2026-07-30", 10),
        diary("2026-07-31", 20),
        diary("2026-08-05", 30),
        diary("2026-08-06", 40),
      ],
      "2026-08-07",
    );
    expect(result.count).toBe(4);
    expect(result.words).toBe(100n);
    expect(result.currentStreak).toBe(2);
    expect(result.longestStreak).toBe(2);
    expect(result.months).toHaveLength(12);
    expect(result.months.at(-1)).toEqual({ key: "2026-08", value: 70n });
  });

  it("does not turn a missing day into a zero-valued diary entry", () => {
    const result = deriveDiarySummary([], "2026-08-07");
    expect(result.count).toBe(0);
    expect(result.currentStreak).toBe(0);
    expect(result.months.every((month) => month.value === 0n)).toBe(true);
  });
});
