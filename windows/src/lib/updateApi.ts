import { listen, type UnlistenFn } from "@tauri-apps/api/event";

import { invokeCommand } from "./ipc";

export const UPDATE_DTO_VERSION = 1 as const;

export interface UpdateStateV1 {
  schemaVersion: typeof UPDATE_DTO_VERSION;
  configured: boolean;
  currentVersion: string;
  automaticChecksEnabled: boolean;
}

export type UpdateCheckResultV1 =
  | {
      schemaVersion: typeof UPDATE_DTO_VERSION;
      kind: "UP_TO_DATE";
      currentVersion: string;
    }
  | {
      schemaVersion: typeof UPDATE_DTO_VERSION;
      kind: "AVAILABLE";
      currentVersion: string;
      version: string;
      notes: string | null;
      publishedAt: string | null;
    };

export type OfficialLinkTarget = "REPOSITORY" | "TUTORIAL";

export interface UpdateAvailableEventV1 {
  schemaVersion: typeof UPDATE_DTO_VERSION;
  currentVersion: string;
  version: string;
  notes: string | null;
  publishedAt: string | null;
}

export interface UpdateDownloadProgressV1 {
  schemaVersion: typeof UPDATE_DTO_VERSION;
  downloadedBytes: string;
  totalBytes: string | null;
}

const U64_MAX = 18_446_744_073_709_551_615n;

function containsAsciiControl(value: string): boolean {
  return [...value].some((character) => {
    const code = character.codePointAt(0) ?? 0;
    return code <= 0x1f || code === 0x7f;
  });
}

function isU64Text(value: unknown): value is string {
  if (typeof value !== "string" || !/^(?:0|[1-9]\d*)$/.test(value)) {
    return false;
  }
  try {
    return BigInt(value) <= U64_MAX;
  } catch {
    return false;
  }
}

function isUpdateAvailableEvent(
  payload: unknown,
): payload is UpdateAvailableEventV1 {
  if (typeof payload !== "object" || payload === null) return false;
  const candidate = payload as Partial<UpdateAvailableEventV1>;
  const keys = Object.keys(candidate);
  return (
    keys.every((key) =>
      [
        "schemaVersion",
        "currentVersion",
        "version",
        "notes",
        "publishedAt",
      ].includes(key),
    ) &&
    candidate.schemaVersion === UPDATE_DTO_VERSION &&
    typeof candidate.currentVersion === "string" &&
    candidate.currentVersion.length > 0 &&
    candidate.currentVersion.length <= 128 &&
    typeof candidate.version === "string" &&
    candidate.version.trim().length > 0 &&
    candidate.version.length <= 128 &&
    !containsAsciiControl(candidate.version) &&
    (candidate.notes === null ||
      (typeof candidate.notes === "string" &&
        candidate.notes.length <= 65_536)) &&
    (candidate.publishedAt === null ||
      (typeof candidate.publishedAt === "string" &&
        candidate.publishedAt.length <= 128))
  );
}

export async function subscribeUpdateAvailable(
  callback: (update: UpdateAvailableEventV1) => void,
): Promise<UnlistenFn> {
  return listen<unknown>("update-available", ({ payload }) => {
    if (isUpdateAvailableEvent(payload)) callback(payload);
  });
}

function isUpdateDownloadProgress(
  payload: unknown,
): payload is UpdateDownloadProgressV1 {
  if (typeof payload !== "object" || payload === null) return false;
  const candidate = payload as Partial<UpdateDownloadProgressV1>;
  const keys = Object.keys(candidate);
  return (
    keys.every((key) =>
      ["schemaVersion", "downloadedBytes", "totalBytes"].includes(key),
    ) &&
    candidate.schemaVersion === UPDATE_DTO_VERSION &&
    isU64Text(candidate.downloadedBytes) &&
    (candidate.totalBytes === null || isU64Text(candidate.totalBytes))
  );
}

export async function subscribeUpdateDownloadProgress(
  callback: (progress: UpdateDownloadProgressV1) => void,
): Promise<UnlistenFn> {
  return listen<unknown>("update-download-progress", ({ payload }) => {
    if (isUpdateDownloadProgress(payload)) callback(payload);
  });
}

export const updateApi = {
  state(): Promise<UpdateStateV1> {
    return invokeCommand("get_update_state");
  },

  setAutomaticChecks(enabled: boolean): Promise<void> {
    return invokeCommand("set_automatic_update_checks", {
      request: { schemaVersion: UPDATE_DTO_VERSION, enabled },
    });
  },

  check(): Promise<UpdateCheckResultV1> {
    return invokeCommand("check_for_updates");
  },

  install(expectedVersion: string): Promise<void> {
    return invokeCommand("install_update", {
      request: { schemaVersion: UPDATE_DTO_VERSION, expectedVersion },
    });
  },

  openOfficialLink(target: OfficialLinkTarget): Promise<void> {
    return invokeCommand("open_official_link", {
      request: { schemaVersion: UPDATE_DTO_VERSION, target },
    });
  },
};
