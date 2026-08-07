import { invokeCommand } from "./ipc";

export const PHONE_USAGE_DTO_VERSION = 2 as const;

export type DecimalI64 = string;
export type UsageRange =
  | "LAST_7_DAYS"
  | "LAST_30_DAYS"
  | "LAST_90_DAYS"
  | "ALL";
export type UsageSourceMode = "snapshot" | "linkedFile" | "cloudSync";
export type UsageSelectableSourceMode = Exclude<UsageSourceMode, "cloudSync">;
export type UsageSourceState = "ready" | "stale" | "missing" | "invalid";

export interface UsageQueryV2 {
  dtoVersion: typeof PHONE_USAGE_DTO_VERSION;
  range: UsageRange;
  packageName?: string | null;
  /** Null is the Android-compatible all-device projection. */
  deviceId?: string | null;
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

export interface UsageDeviceChoiceV2 {
  deviceId: string;
  deviceName: string;
  platform: string;
  updatedAtEpochMillis: DecimalI64;
  recordedDays: number;
}

export interface PhoneUsageSnapshotV2 {
  dtoVersion: typeof PHONE_USAGE_DTO_VERSION;
  source: UsageSourceV1;
  trackingStartedOn: string | null;
  backfillCompletedThrough: string | null;
  anchorDate: string | null;
  selectedDeviceId: string | null;
  selectedPackageName: string | null;
  deviceChoices: UsageDeviceChoiceV2[];
  overview: UsageOverviewV1;
  appChoices: UsageAppChoiceV1[];
  points: UsagePointV1[];
}

export const usageApi = {
  page(query: UsageQueryV2): Promise<PhoneUsageSnapshotV2 | null> {
    return invokeCommand("get_usage_page", { query });
  },

  chooseSource(mode: UsageSelectableSourceMode): Promise<PhoneUsageSnapshotV2 | null> {
    return invokeCommand("choose_usage_statistics_source", {
      request: { dtoVersion: PHONE_USAGE_DTO_VERSION, mode },
    });
  },

  refresh(query: UsageQueryV2): Promise<PhoneUsageSnapshotV2 | null> {
    return invokeCommand("refresh_usage_statistics", { query });
  },
};
