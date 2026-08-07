import type { DiaryEntry } from "../lib/ipc";

export interface DiarySummary {
  count: number;
  words: bigint;
  currentStreak: number;
  longestStreak: number;
  months: { key: string; value: bigint }[];
}

function localToday(): string {
  const today = new Date();
  const year = today.getFullYear();
  const month = String(today.getMonth() + 1).padStart(2, "0");
  const day = String(today.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function shiftDate(iso: string, days: number): string {
  const date = new Date(`${iso}T12:00:00`);
  date.setDate(date.getDate() + days);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

export function deriveDiarySummary(
  diaries: DiaryEntry[],
  today = localToday(),
): DiarySummary {
  const dates = [
    ...new Set(
      diaries
        .map((entry) => entry.date)
        .filter((date) => /^\d{4}-\d{2}-\d{2}$/.test(date) && date <= today),
    ),
  ].sort();
  const dateSet = new Set(dates);
  let cursor = dateSet.has(today) ? today : shiftDate(today, -1);
  let currentStreak = 0;
  while (dateSet.has(cursor)) {
    currentStreak += 1;
    cursor = shiftDate(cursor, -1);
  }
  let longestStreak = 0;
  let running = 0;
  let previous = "";
  for (const date of dates) {
    running = previous && shiftDate(previous, 1) === date ? running + 1 : 1;
    longestStreak = Math.max(longestStreak, running);
    previous = date;
  }
  const wordsByMonth = new Map<string, bigint>();
  for (const entry of diaries) {
    const month = /^\d{4}-\d{2}/.exec(entry.date)?.[0];
    if (month) {
      wordsByMonth.set(
        month,
        (wordsByMonth.get(month) ?? 0n) + BigInt(Math.max(0, entry.wordCount)),
      );
    }
  }
  const monthCursor = new Date(`${today.slice(0, 7)}-01T12:00:00`);
  const months = Array.from({ length: 12 }, (_, index) => {
    const date = new Date(monthCursor);
    date.setMonth(date.getMonth() - (11 - index));
    const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}`;
    return { key, value: wordsByMonth.get(key) ?? 0n };
  });
  return {
    count: diaries.length,
    words: diaries.reduce(
      (sum, entry) => sum + BigInt(Math.max(0, entry.wordCount)),
      0n,
    ),
    currentStreak,
    longestStreak,
    months,
  };
}
