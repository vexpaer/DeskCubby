import { apiGet } from "../../api/client";

interface StructuredDateConfig {
  todayDiarySwitchTime?: string;
  dayBoundary?: string;
}

function localIso(value: Date): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return `${value.getFullYear()}-${p(value.getMonth() + 1)}-${p(value.getDate())}`;
}

/**
 * Resolve the one switched date used by “Open today's diary”. Structured
 * records themselves always use the natural local calendar date.
 */
export function resolveTodayDiaryIso(switchTime = "05:00", now = new Date()): string {
  const match = /^(\d{1,2}):(\d{2})$/.exec(switchTime.trim());
  const hour = match ? Number(match[1]) : 5;
  const minute = match ? Number(match[2]) : 0;
  const safeMinutes = hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59
    ? hour * 60 + minute
    : 5 * 60;
  const target = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  if (now.getHours() * 60 + now.getMinutes() < safeMinutes) target.setDate(target.getDate() - 1);
  return localIso(target);
}

export async function loadTodayDiaryIso(): Promise<string> {
  try {
    const config = await apiGet<StructuredDateConfig>("/api/structured/config");
    return resolveTodayDiaryIso(config.todayDiarySwitchTime ?? config.dayBoundary ?? "05:00");
  } catch {
    return resolveTodayDiaryIso();
  }
}
