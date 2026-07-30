import { invokeCommand } from "./ipc";

export const CLOUD_SYNC_DTO_VERSION = 1 as const;

export type CloudServiceType = "WEBDAV" | "S3_COMPATIBLE";
export type CloudSyncContent = "DIARIES" | "MEDIA" | "JSON_BACKUP";
export type CloudSyncDirection = "UPLOAD_ONLY" | "TWO_WAY";

export interface CloudSyncConfigV1 {
  id: string;
  name: string;
  enabled: boolean;
  serviceType: CloudServiceType;
  endpointUrl: string;
  remotePath: string;
  webDavUsername: string;
  s3Bucket: string;
  s3Region: string;
  allowInsecureHttp: boolean;
  selectedContents: CloudSyncContent[];
  direction: CloudSyncDirection;
  hasCredentials: boolean;
}

export interface CloudSyncStatusV1 {
  running: boolean;
  runningConfigId: string | null;
  phase: string | null;
  lastCompletedAt: string | null;
  lastErrorCode: string | null;
}

export interface CloudSyncConfigListV1 {
  schemaVersion: typeof CLOUD_SYNC_DTO_VERSION;
  globalEnabled: boolean;
  configs: CloudSyncConfigV1[];
  status: CloudSyncStatusV1;
}

export interface CloudSyncConfigDraftV1 {
  id: string | null;
  name: string;
  enabled: boolean;
  serviceType: CloudServiceType;
  endpointUrl: string;
  remotePath: string;
  webDavUsername: string;
  s3Bucket: string;
  s3Region: string;
  allowInsecureHttp: boolean;
  selectedContents: CloudSyncContent[];
  direction: CloudSyncDirection;
}

export type CloudCredentialUpdateV1 =
  | { mode: "preserve" }
  | { mode: "clear" }
  | {
      mode: "replace";
      webDavPassword?: string;
      s3AccessKey?: string;
      s3SecretKey?: string;
      s3SessionToken?: string;
    };

export interface CloudPendingJsonV1 {
  id: string;
  receivedAt: string;
  size: number;
  sourceLabel: string | null;
}

export interface CloudPendingJsonPreviewV1 {
  schemaVersion: typeof CLOUD_SYNC_DTO_VERSION;
  id: string;
  confirmationToken: string;
  formatVersion: number;
  exportedAt: string | null;
  thoughtCount: number;
  categoryCount: number;
  dateRecordCount: number;
  poemCount: number;
}

export interface CloudSyncRunSummaryV1 {
  schemaVersion: typeof CLOUD_SYNC_DTO_VERSION;
  uploaded: number;
  downloaded: number;
  conflicts: number;
  skipped: number;
}

export const cloudApi = {
  listConfigs(): Promise<CloudSyncConfigListV1> {
    return invokeCommand("list_cloud_sync_configs");
  },

  saveConfig(
    config: CloudSyncConfigDraftV1,
    credentialUpdate: CloudCredentialUpdateV1,
  ): Promise<CloudSyncConfigV1> {
    return invokeCommand("save_cloud_sync_config", {
      request: {
        schemaVersion: CLOUD_SYNC_DTO_VERSION,
        config,
        credentialUpdate,
      },
    });
  },

  deleteConfig(id: string): Promise<void> {
    return invokeCommand("delete_cloud_sync_config", {
      request: { schemaVersion: CLOUD_SYNC_DTO_VERSION, id },
    });
  },

  copyConfig(id: string): Promise<CloudSyncConfigV1> {
    return invokeCommand("copy_cloud_sync_config", {
      request: { schemaVersion: CLOUD_SYNC_DTO_VERSION, id },
    });
  },

  setEnabled(enabled: boolean): Promise<void> {
    return invokeCommand("set_cloud_sync_enabled", {
      request: { schemaVersion: CLOUD_SYNC_DTO_VERSION, enabled },
    });
  },

  run(configId?: string): Promise<CloudSyncRunSummaryV1> {
    return invokeCommand("run_cloud_sync", {
      request: {
        schemaVersion: CLOUD_SYNC_DTO_VERSION,
        configId: configId ?? null,
      },
    });
  },

  cancel(): Promise<void> {
    return invokeCommand("cancel_cloud_sync");
  },

  listPendingJson(): Promise<CloudPendingJsonV1[]> {
    return invokeCommand("list_pending_cloud_json");
  },

  previewPendingJson(id: string): Promise<CloudPendingJsonPreviewV1> {
    return invokeCommand("preview_pending_cloud_json", {
      request: { schemaVersion: CLOUD_SYNC_DTO_VERSION, id },
    });
  },

  restorePendingJson(id: string, confirmationToken: string): Promise<void> {
    return invokeCommand("restore_pending_cloud_json", {
      request: {
        schemaVersion: CLOUD_SYNC_DTO_VERSION,
        id,
        confirmationToken,
      },
    });
  },
};
