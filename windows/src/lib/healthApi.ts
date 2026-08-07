import { invokeCommand } from "./ipc";
import type { DecimalI64, UsageRange } from "./usageApi";

export const HEALTH_DTO_VERSION = 1 as const;

export type HealthMetric = "STEPS" | "DISTANCE" | "ACTIVE_CALORIES";
export type HealthSourceMode = "snapshot" | "linkedFile";
export type HealthSourceState = "ready" | "stale" | "missing" | "invalid";

export interface HealthQueryV1 {
  dtoVersion: typeof HEALTH_DTO_VERSION;
  range: UsageRange;
  metric: HealthMetric;
}

export interface HealthSourceV1 {
  dtoVersion: typeof HEALTH_DTO_VERSION;
  mode: HealthSourceMode;
  state: HealthSourceState;
  displayName: string;
  canRefresh: boolean;
  lastSuccessfulReadAtMs: DecimalI64;
  lastAttemptAtMs: DecimalI64;
  sourceModifiedAtMs: DecimalI64 | null;
}

export interface HealthPointV1 {
  date: string;
  zoneId: string;
  state: "OPEN" | "FINAL";
  collectedAtEpochMillis: DecimalI64;
  /** Null means Android supplied no trustworthy aggregate for this metric. */
  value: string | null;
}

export interface HealthOverviewV1 {
  rangeStartedOn: string | null;
  recordedDays: number;
  daysWithData: number;
  total: string | null;
  averagePerDataDay: string | null;
  highestDay: string | null;
}

export interface HealthSnapshotV1 {
  dtoVersion: typeof HEALTH_DTO_VERSION;
  source: HealthSourceV1;
  trackingStartedOn: string | null;
  anchorDate: string | null;
  metric: HealthMetric;
  overview: HealthOverviewV1;
  points: HealthPointV1[];
}

export const healthApi = {
  page(query: HealthQueryV1): Promise<HealthSnapshotV1 | null> {
    return invokeCommand("get_health_page", { query });
  },

  chooseSource(mode: HealthSourceMode): Promise<HealthSnapshotV1 | null> {
    return invokeCommand("choose_health_statistics_source", {
      request: { dtoVersion: HEALTH_DTO_VERSION, mode },
    });
  },

  refresh(query: HealthQueryV1): Promise<HealthSnapshotV1 | null> {
    return invokeCommand("refresh_health_statistics", { query });
  },
};
