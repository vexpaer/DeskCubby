import { invokeCommand } from "./ipc";

export const PHONE_USAGE_DTO_VERSION = 1 as const;

export type DecimalI64 = string;
export type UsageRange =
  | "LAST_7_DAYS"
  | "LAST_30_DAYS"
  | "LAST_90_DAYS"
  | "ALL";
export type UsageSourceMode = "snapshot" | "linkedFile";
export type UsageSourceState = "ready" | "stale" | "missing" | "invalid";

export interface UsageQueryV1 {
  dtoVersion: typeof PHONE_USAGE_DTO_VERSION;
  range: UsageRange;
  packageName?: string | null;
}

export interface UsageSourceV1 {
  dtoVersion: typeof PHONE_USAGE_DTO_VERSION;
  mode: UsageSourceMode;
  state: UsageSourceState;
  displayName: string;
  canRefresh: boolean;
  lastSuccessfulReadAtMs: DecimalI64;
  lastAttemptAtMs: DecimalI64;
  sourceModifiedAtMs: DecimalI64 | null;
}

export interface UsageOverviewV1 {
  rangeStartedOn: string | null;
  recordedDays: number;
  totalMillis: DecimalI64;
  averageMillis: DecimalI64;
  highestDayMillis: DecimalI64;
  lastSevenAverageMillis: DecimalI64;
}

export interface UsageAppChoiceV1 {
  packageName: string;
  label: string;
  rangeMillis: DecimalI64;
}

export interface UsagePointV1 {
  date: string;
  zoneId: string;
  state: "OPEN" | "FINAL";
  collectedAtEpochMillis: DecimalI64;
  valueMillis: DecimalI64;
}

export interface PhoneUsageSnapshotV1 {
  dtoVersion: typeof PHONE_USAGE_DTO_VERSION;
  source: UsageSourceV1;
  trackingStartedOn: string | null;
  backfillCompletedThrough: string | null;
  anchorDate: string | null;
  selectedPackageName: string | null;
  overview: UsageOverviewV1;
  appChoices: UsageAppChoiceV1[];
  points: UsagePointV1[];
}

export const usageApi = {
  page(query: UsageQueryV1): Promise<PhoneUsageSnapshotV1 | null> {
    return invokeCommand("get_usage_page", { query });
  },

  chooseSource(mode: UsageSourceMode): Promise<PhoneUsageSnapshotV1 | null> {
    return invokeCommand("choose_usage_statistics_source", {
      request: { dtoVersion: PHONE_USAGE_DTO_VERSION, mode },
    });
  },

  refresh(query: UsageQueryV1): Promise<PhoneUsageSnapshotV1 | null> {
    return invokeCommand("refresh_usage_statistics", { query });
  },
};
