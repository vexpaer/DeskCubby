import * as Dialog from "@radix-ui/react-dialog";
import {
  AlertTriangle,
  Check,
  Cloud,
  Copy,
  DatabaseBackup,
  DownloadCloud,
  LoaderCircle,
  Pencil,
  Plus,
  RefreshCw,
  Save,
  Server,
  ShieldAlert,
  Trash2,
  UploadCloud,
  X,
} from "lucide-react";
import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type FormEvent,
} from "react";
import { Link } from "react-router-dom";

import {
  ConfirmDialog,
  EmptyState,
  LoadingState,
  PageFrame,
  UnsavedChangesGuard,
} from "../components";
import {
  cloudApi,
  type CloudCredentialUpdateV1,
  type CloudPendingJsonPreviewV1,
  type CloudPendingJsonV1,
  type CloudServiceType,
  type CloudSyncConfigDraftV1,
  type CloudSyncConfigListV1,
  type CloudSyncConfigV1,
  type CloudSyncContent,
  type CloudSyncDirection,
  type CloudSyncRunMode,
} from "../lib/cloudApi";
import { readableError, tr } from "../lib/ipc";
import { useAppStore } from "../store/appStore";

interface SecretDraft {
  webDavPassword: string;
  s3AccessKey: string;
  s3SecretKey: string;
  s3SessionToken: string;
}

const EMPTY_SECRETS: SecretDraft = {
  webDavPassword: "",
  s3AccessKey: "",
  s3SecretKey: "",
  s3SessionToken: "",
};

function configDraft(config?: CloudSyncConfigV1): CloudSyncConfigDraftV1 {
  return {
    id: config?.id ?? null,
    name: config?.name ?? "",
    enabled: config?.enabled ?? true,
    serviceType: config?.serviceType ?? "WEBDAV",
    endpointUrl: config?.endpointUrl ?? "",
    remotePath: config?.remotePath ?? "DeskCubby",
    userAgent: config?.userAgent ?? "DeskCubby-Sync/1",
    webDavUsername: config?.webDavUsername ?? "",
    s3Bucket: config?.s3Bucket ?? "",
    s3Region: config?.s3Region ?? "us-east-1",
    s3PathStyle: config?.s3PathStyle ?? true,
    allowInsecureHttp: config?.allowInsecureHttp ?? false,
    selectedContents: config?.selectedContents ?? [
      "DIARIES",
      "MEDIA",
      "JSON_BACKUP",
      "USAGE_STATISTICS",
      "READING_PROGRESS",
    ],
    direction: config?.direction ?? "TWO_WAY",
  };
}

function credentialBindingChanged(
  config: CloudSyncConfigV1 | null,
  draft: CloudSyncConfigDraftV1,
): boolean {
  if (!config?.hasCredentials) return false;
  if (
    config.serviceType !== draft.serviceType ||
    config.endpointUrl.trim() !== draft.endpointUrl.trim()
  ) {
    return true;
  }
  return draft.serviceType === "WEBDAV"
    ? config.webDavUsername.trim() !== draft.webDavUsername.trim()
    : config.s3Bucket.trim() !== draft.s3Bucket.trim() ||
        config.s3Region.trim() !== draft.s3Region.trim();
}

function validRemotePath(value: string): boolean {
  const parts = value.replaceAll("\\", "/").split("/");
  return (
    value.length <= 1_024 &&
    !value.startsWith("/") &&
    parts.every((part) => part !== "." && part !== "..")
  );
}

function isHttpControlCharacter(character: string): boolean {
  const codePoint = character.codePointAt(0);
  return codePoint !== undefined && (codePoint <= 0x1f || codePoint === 0x7f);
}

function configDraftsEqual(
  left: CloudSyncConfigDraftV1,
  right: CloudSyncConfigDraftV1,
): boolean {
  return (
    left.id === right.id &&
    left.name === right.name &&
    left.enabled === right.enabled &&
    left.serviceType === right.serviceType &&
    left.endpointUrl === right.endpointUrl &&
    left.remotePath === right.remotePath &&
    left.userAgent === right.userAgent &&
    left.webDavUsername === right.webDavUsername &&
    left.s3Bucket === right.s3Bucket &&
    left.s3Region === right.s3Region &&
    left.s3PathStyle === right.s3PathStyle &&
    left.allowInsecureHttp === right.allowInsecureHttp &&
    left.direction === right.direction &&
    left.selectedContents.length === right.selectedContents.length &&
    left.selectedContents.every(
      (content, index) => content === right.selectedContents[index],
    )
  );
}

function endpointState(
  endpoint: string,
  allowInsecureHttp: boolean,
): "valid" | "insecure" | "invalid" {
  try {
    const parsed = new URL(endpoint);
    if (
      parsed.username ||
      parsed.password ||
      parsed.hash ||
      endpoint.includes("?") ||
      endpoint.includes("#")
    ) {
      return "invalid";
    }
    if (parsed.protocol === "https:") return "valid";
    if (parsed.protocol === "http:" && allowInsecureHttp) return "insecure";
    return "invalid";
  } catch {
    return "invalid";
  }
}

interface ConfigDialogProps {
  open: boolean;
  config: CloudSyncConfigV1 | null;
  busy: boolean;
  language: "zh-CN" | "en";
  onClose: () => void;
  onSave: (
    draft: CloudSyncConfigDraftV1,
    credentialUpdate: CloudCredentialUpdateV1,
  ) => Promise<void>;
}

function ConfigDialog({
  open,
  config,
  busy,
  language,
  onClose,
  onSave,
}: ConfigDialogProps) {
  const copy = useCallback(
    (zh: string, en: string) => tr(language, zh, en),
    [language],
  );
  const [draft, setDraft] = useState<CloudSyncConfigDraftV1>(() =>
    configDraft(config ?? undefined),
  );
  const [credentialMode, setCredentialMode] =
    useState<CloudCredentialUpdateV1["mode"]>("replace");
  const [secrets, setSecrets] = useState<SecretDraft>(EMPTY_SECRETS);
  const [confirmHttp, setConfirmHttp] = useState(false);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const pristineDraft = useMemo(
    () => configDraft(config ?? undefined),
    [config],
  );
  const pristineCredentialMode: CloudCredentialUpdateV1["mode"] =
    config?.hasCredentials ? "preserve" : "replace";
  const dirty =
    !configDraftsEqual(draft, pristineDraft) ||
    credentialMode !== pristineCredentialMode ||
    Object.values(secrets).some((value) => value.length > 0);
  const endpoint = endpointState(draft.endpointUrl, draft.allowInsecureHttp);
  const hasCredentials = config?.hasCredentials ?? false;
  const bindingChanged = credentialBindingChanged(config, draft);
  const needsExistingWebDavReplacement =
    credentialMode === "replace" &&
    draft.serviceType === "WEBDAV" &&
    hasCredentials &&
    !secrets.webDavPassword;
  const needsS3Credentials =
    credentialMode === "replace" &&
    draft.serviceType === "S3_COMPATIBLE" &&
    (!secrets.s3AccessKey || !secrets.s3SecretKey);
  const valid =
    draft.name.trim().length > 0 &&
    draft.endpointUrl.length <= 4_096 &&
    draft.userAgent.trim().length > 0 &&
    draft.userAgent.length <= 512 &&
    !Array.from(draft.userAgent).some(isHttpControlCharacter) &&
    endpoint !== "invalid" &&
    validRemotePath(draft.remotePath) &&
    draft.selectedContents.length > 0 &&
    (draft.serviceType !== "S3_COMPATIBLE" ||
      (!!draft.s3Bucket.trim() && !!draft.s3Region.trim())) &&
    !(credentialMode === "preserve" && bindingChanged) &&
    !needsExistingWebDavReplacement &&
    !needsS3Credentials;

  useEffect(() => {
    if (!open) return;
    setDraft(pristineDraft);
    setCredentialMode(pristineCredentialMode);
    setSecrets(EMPTY_SECRETS);
    setConfirmHttp(false);
    setConfirmDiscard(false);
  }, [open, pristineCredentialMode, pristineDraft]);

  function closeImmediately() {
    setConfirmDiscard(false);
    setConfirmHttp(false);
    setSecrets(EMPTY_SECRETS);
    onClose();
  }

  function requestClose() {
    if (busy) return;
    if (dirty) {
      setConfirmDiscard(true);
      return;
    }
    closeImmediately();
  }

  function credentialUpdate(): CloudCredentialUpdateV1 {
    if (credentialMode === "preserve") return { mode: "preserve" };
    if (credentialMode === "clear") return { mode: "clear" };
    return draft.serviceType === "WEBDAV"
      ? secrets.webDavPassword
        ? { mode: "replace", webDavPassword: secrets.webDavPassword }
        : { mode: "clear" }
      : {
          mode: "replace",
          s3AccessKey: secrets.s3AccessKey,
          s3SecretKey: secrets.s3SecretKey,
          s3SessionToken: secrets.s3SessionToken || undefined,
        };
  }

  async function commit() {
    if (!valid) return;
    const normalized: CloudSyncConfigDraftV1 = {
      ...draft,
      name: draft.name.trim(),
      endpointUrl: draft.endpointUrl.trim(),
      remotePath: draft.remotePath.trim() || "DeskCubby",
      userAgent: draft.userAgent.trim(),
      webDavUsername: draft.webDavUsername.trim(),
      s3Bucket: draft.s3Bucket.trim(),
      s3Region: draft.s3Region.trim(),
    };
    const credentials = credentialUpdate();
    try {
      await onSave(normalized, credentials);
      setSecrets(EMPTY_SECRETS);
      onClose();
    } catch {
      // The page owns the safe, localized error message. Keep every draft
      // value in place so credentials do not need to be typed again.
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    if (endpoint === "insecure") {
      setConfirmHttp(true);
    } else {
      void commit();
    }
  }

  function toggleContent(content: CloudSyncContent, checked: boolean) {
    setDraft((current) => ({
      ...current,
      selectedContents: checked
        ? [...new Set([...current.selectedContents, content])]
        : current.selectedContents.filter((item) => item !== content),
    }));
  }

  return (
    <>
      <Dialog.Root
        open={open}
        onOpenChange={(next) => {
          if (!next) requestClose();
        }}
      >
        <Dialog.Portal>
          <Dialog.Overlay className="dialog-overlay" />
          <Dialog.Content className="dialog-content cloud-config-dialog">
            <Dialog.Title className="dialog-title">
              {config
                ? copy("编辑同步配置", "Edit sync configuration")
                : copy("新增同步配置", "New sync configuration")}
            </Dialog.Title>
            <Dialog.Description className="dialog-description">
              {copy(
                "凭据由 Rust 使用 Windows DPAPI 加密，不进入 JSON 备份或前端持久状态。",
                "Rust encrypts credentials with Windows DPAPI. They never enter JSON backups or persistent frontend state.",
              )}
            </Dialog.Description>
            <form
              autoComplete="off"
              className="stack cloud-config-form"
              onSubmit={submit}
            >
              <div
                className="segmented"
                role="group"
                aria-label={copy("服务类型", "Service type")}
              >
                {(["WEBDAV", "S3_COMPATIBLE"] as CloudServiceType[]).map((type) => (
                  <button
                    key={type}
                    type="button"
                    className={draft.serviceType === type ? "selected" : undefined}
                    aria-pressed={draft.serviceType === type}
                    onClick={() => {
                      setDraft((current) => ({ ...current, serviceType: type }));
                      setSecrets(EMPTY_SECRETS);
                    }}
                  >
                    {type === "WEBDAV" ? "WebDAV" : copy("S3 兼容", "S3 compatible")}
                  </button>
                ))}
              </div>

              <div className="form-grid">
                <label className="field">
                  <span className="field-label">{copy("配置名称", "Configuration name")}</span>
                  <input
                    autoFocus
                    maxLength={200}
                    value={draft.name}
                    onChange={(event) =>
                      setDraft((current) => ({ ...current, name: event.target.value }))
                    }
                  />
                </label>
                <label className="check-control cloud-enabled-control">
                  <input
                    type="checkbox"
                    checked={draft.enabled}
                    onChange={(event) =>
                      setDraft((current) => ({ ...current, enabled: event.target.checked }))
                    }
                  />
                  {copy("启用此配置", "Enable this configuration")}
                </label>
                <label className="field full-width">
                  <span className="field-label">{copy("服务地址", "Endpoint URL")}</span>
                  <input
                    maxLength={4_096}
                    inputMode="url"
                    placeholder={
                      draft.serviceType === "WEBDAV"
                        ? "https://dav.example.com/remote.php/dav/files/name/"
                        : "https://s3.example.com"
                    }
                    value={draft.endpointUrl}
                    onChange={(event) =>
                      setDraft((current) => ({
                        ...current,
                        endpointUrl: event.target.value,
                      }))
                    }
                  />
                  {draft.endpointUrl && endpoint === "invalid" ? (
                    <small className="field-error">
                      {copy("默认只允许有效的 HTTPS 地址。", "A valid HTTPS URL is required by default.")}
                    </small>
                  ) : null}
                </label>
                <label className="field full-width">
                  <span className="field-label">{copy("远端目录", "Remote path")}</span>
                  <input
                    maxLength={1_024}
                    value={draft.remotePath}
                    onChange={(event) =>
                      setDraft((current) => ({
                        ...current,
                        remotePath: event.target.value,
                      }))
                    }
                  />
                  {!validRemotePath(draft.remotePath) ? (
                    <small className="field-error">
                      {copy("不能使用绝对路径、. 或 ..。", "Absolute paths, . and .. are not allowed.")}
                    </small>
                  ) : null}
                </label>
                <label className="field full-width">
                  <span className="field-label">User-Agent</span>
                  <input
                    maxLength={512}
                    value={draft.userAgent}
                    onChange={(event) =>
                      setDraft((current) => ({
                        ...current,
                        userAgent: event.target.value,
                      }))
                    }
                  />
                  <small className="form-hint">
                    {copy(
  "与 Android v29 使用相同的可配置请求标识；不能为空或包含控制字符。",
  "Uses the same configurable request identifier as Android v29; it cannot be blank or contain control characters.",
                    )}
                  </small>
                </label>
                <label className="check-control full-width">
                  <input
                    type="checkbox"
                    checked={draft.allowInsecureHttp}
                    onChange={(event) =>
                      setDraft((current) => ({
                        ...current,
                        allowInsecureHttp: event.target.checked,
                      }))
                    }
                  />
                  {copy("允许 HTTP（仅可信局域网）", "Allow HTTP (trusted local network only)")}
                </label>
              </div>

              <fieldset className="cloud-fieldset">
                <legend>{copy("同步内容", "Sync contents")}</legend>
                <div className="cloud-checkbox-grid">
                  {(
                    [
                      ["DIARIES", copy("日记", "Diaries")],
                      ["MEDIA", copy("媒体", "Media")],
                      ["JSON_BACKUP", copy("应用 JSON", "App JSON")],
                      ["USAGE_STATISTICS", copy("手机使用时间", "Phone screen time")],
                      ["READING_PROGRESS", copy("阅读进度", "Reading progress")],
                    ] as Array<[CloudSyncContent, string]>
                  ).map(([content, label]) => (
                    <label className="check-control" key={content}>
                      <input
                        type="checkbox"
                        checked={draft.selectedContents.includes(content)}
                        onChange={(event) => toggleContent(content, event.target.checked)}
                      />
                      {label}
                    </label>
                  ))}
                </div>
                {draft.selectedContents.length === 0 ? (
                  <small className="field-error">
                    {copy("至少选择一种同步内容。", "Select at least one content type.")}
                  </small>
                ) : null}
                {draft.selectedContents.includes("JSON_BACKUP") ? (
                  <div className="cloud-sensitive-warning" role="note">
                    <ShieldAlert aria-hidden="true" size={18} />
                    <span>
                      {copy(
                        "应用 JSON 可能包含 AI 配置中的明文 API Key；远端对象没有端到端加密。",
                        "App JSON can contain plaintext API keys from AI configurations; remote objects are not end-to-end encrypted.",
                      )}
                    </span>
                  </div>
                ) : null}
                {draft.selectedContents.includes("USAGE_STATISTICS") ? (
                  <div className="cloud-sensitive-warning" role="note">
                    <ShieldAlert aria-hidden="true" size={18} />
                    <span>
                      {copy(
                        "Windows 只下载并显示 Android 的 usage/v1 设备对象，绝不会采集或上传这台电脑的使用时间；“仅上传”模式会跳过这些对象。",
                        "Windows only downloads and displays Android usage/v1 device objects. It never collects or uploads PC usage; upload-only mode skips these objects.",
                      )}
                    </span>
                  </div>
                ) : null}
                {draft.selectedContents.includes("READING_PROGRESS") ? (
                  <div className="cloud-sensitive-warning" role="note">
                    <ShieldAlert aria-hidden="true" size={18} />
                    <span>
                      {copy(
                        "仅同步完整文件 SHA-256 指纹、类型、位置、总页数与更新时间，不包含路径、书名、封面或正文；指纹仍可能被用于识别已知文件，远端对象没有端到端加密。",
                        "Only the full-file SHA-256 fingerprint, type, position, page count, and update time are synced—never paths, titles, covers, or document text. A fingerprint can still identify a known file, and the remote object is not end-to-end encrypted.",
                      )}
                    </span>
                  </div>
                ) : null}
              </fieldset>

              <fieldset className="cloud-fieldset">
                <legend>{copy("同步方向", "Direction")}</legend>
                <div className="segmented" role="group" aria-label={copy("同步方向", "Direction")}>
                  {(
                    [
                      ["UPLOAD_ONLY", copy("仅上传", "Upload only")],
                      ["TWO_WAY", copy("双向", "Two-way")],
                    ] as Array<[CloudSyncDirection, string]>
                  ).map(([direction, label]) => (
                    <button
                      key={direction}
                      type="button"
                      className={draft.direction === direction ? "selected" : undefined}
                      aria-pressed={draft.direction === direction}
                      onClick={() =>
                        setDraft((current) => ({ ...current, direction }))
                      }
                    >
                      {label}
                    </button>
                  ))}
                </div>
                <small className="form-hint">
                  {copy(
                    "双向同步遇到双方修改时保留冲突副本，不会静默覆盖。",
                    "Two-way sync keeps a conflict copy when both sides changed.",
                  )}
                </small>
              </fieldset>

              {draft.serviceType === "WEBDAV" ? (
                <div className="form-grid">
                  <label className="field full-width">
                    <span className="field-label">{copy("用户名", "Username")}</span>
                    <input
                      maxLength={512}
                      autoComplete="username"
                      value={draft.webDavUsername}
                      onChange={(event) =>
                        setDraft((current) => ({
                          ...current,
                          webDavUsername: event.target.value,
                        }))
                      }
                    />
                  </label>
                </div>
              ) : (
                <div className="form-grid">
                  <label className="field">
                    <span className="field-label">Bucket</span>
                    <input
                      maxLength={255}
                      value={draft.s3Bucket}
                      onChange={(event) =>
                        setDraft((current) => ({ ...current, s3Bucket: event.target.value }))
                      }
                    />
                  </label>
                  <label className="field">
                    <span className="field-label">Region</span>
                    <input
                      maxLength={128}
                      value={draft.s3Region}
                      onChange={(event) =>
                        setDraft((current) => ({ ...current, s3Region: event.target.value }))
                      }
                    />
                  </label>
                  <label className="check-control full-width">
                    <input
                      type="checkbox"
                      checked={draft.s3PathStyle}
                      onChange={(event) =>
                        setDraft((current) => ({
                          ...current,
                          s3PathStyle: event.target.checked,
                        }))
                      }
                    />
                    {copy(
                      "使用 Path-style 地址（兼容多数自建 S3 服务）",
                      "Use path-style addressing (recommended for most self-hosted S3 services)",
                    )}
                  </label>
                </div>
              )}

              <fieldset className="cloud-fieldset">
                <legend>{copy("本机凭据", "Local credentials")}</legend>
                <div className="segmented cloud-credential-mode" role="group" aria-label={copy("凭据操作", "Credential action")}>
                  {hasCredentials ? (
                    <button
                      type="button"
                      className={credentialMode === "preserve" ? "selected" : undefined}
                      aria-pressed={credentialMode === "preserve"}
                      disabled={bindingChanged}
                      onClick={() => {
                        setCredentialMode("preserve");
                        setSecrets(EMPTY_SECRETS);
                      }}
                    >
                      {copy("保留", "Keep")}
                    </button>
                  ) : null}
                  <button
                    type="button"
                    className={credentialMode === "replace" ? "selected" : undefined}
                    aria-pressed={credentialMode === "replace"}
                    onClick={() => setCredentialMode("replace")}
                  >
                    {hasCredentials ? copy("替换", "Replace") : copy("填写", "Enter")}
                  </button>
                  {hasCredentials ? (
                    <button
                      type="button"
                      className={credentialMode === "clear" ? "selected" : undefined}
                      aria-pressed={credentialMode === "clear"}
                      onClick={() => {
                        setCredentialMode("clear");
                        setSecrets(EMPTY_SECRETS);
                      }}
                    >
                      {copy("清除", "Clear")}
                    </button>
                  ) : null}
                </div>
                {credentialMode === "preserve" ? (
                  <p
                    className={bindingChanged ? "cloud-sensitive-warning" : "form-hint"}
                    role={bindingChanged ? "alert" : undefined}
                  >
                    {bindingChanged ? (
                      <AlertTriangle aria-hidden="true" size={18} />
                    ) : null}
                    {bindingChanged
                      ? copy(
                          "账号绑定字段已修改。旧凭据不会复用，请明确选择替换或清除。",
                          "A credential-binding field changed. Stored credentials cannot be reused; explicitly replace or clear them.",
                        )
                      : copy(
                          "已在本机加密保存，不会回传到界面。",
                          "Encrypted credentials remain stored and are never returned to the UI.",
                        )}
                  </p>
                ) : credentialMode === "clear" ? (
                  <p className="cloud-sensitive-warning">
                    <AlertTriangle aria-hidden="true" size={18} />
                    {copy("保存后会清除本机凭据。", "Saving will clear locally stored credentials.")}
                  </p>
                ) : draft.serviceType === "WEBDAV" ? (
                  <div className="field">
                    <label
                      className="field-label"
                      htmlFor="cloud-webdav-password"
                    >
                      {copy("密码", "Password")}
                    </label>
                    <input
                      autoComplete="new-password"
                      id="cloud-webdav-password"
                      maxLength={8_192}
                      type="password"
                      value={secrets.webDavPassword}
                      onChange={(event) =>
                        setSecrets((current) => ({
                          ...current,
                          webDavPassword: event.target.value,
                        }))
                      }
                    />
                    <small className="form-hint">
                      {hasCredentials
                        ? copy(
                            "替换时需填写新密码；若账号不需要密码，请选择“清除”。",
                            "Enter a new password to replace it, or choose Clear if the account needs no password.",
                          )
                        : copy(
                            "匿名或仅用户名的 WebDAV 可留空。",
                            "Leave blank for anonymous or username-only WebDAV.",
                        )}
                    </small>
                  </div>
                ) : (
                  <div className="form-grid">
                    <label className="field">
                      <span className="field-label">Access Key ID</span>
                      <input
                        autoComplete="off"
                        maxLength={8_192}
                        type="password"
                        value={secrets.s3AccessKey}
                        onChange={(event) =>
                          setSecrets((current) => ({
                            ...current,
                            s3AccessKey: event.target.value,
                          }))
                        }
                      />
                    </label>
                    <label className="field">
                      <span className="field-label">Secret Access Key</span>
                      <input
                        autoComplete="new-password"
                        maxLength={8_192}
                        type="password"
                        value={secrets.s3SecretKey}
                        onChange={(event) =>
                          setSecrets((current) => ({
                            ...current,
                            s3SecretKey: event.target.value,
                          }))
                        }
                      />
                    </label>
                    <label className="field full-width">
                      <span className="field-label">
                        {copy("Session Token（可选）", "Session token (optional)")}
                      </span>
                      <input
                        autoComplete="off"
                        maxLength={8_192}
                        type="password"
                        value={secrets.s3SessionToken}
                        onChange={(event) =>
                          setSecrets((current) => ({
                            ...current,
                            s3SessionToken: event.target.value,
                          }))
                        }
                      />
                    </label>
                  </div>
                )}
              </fieldset>

              <div className="dialog-actions">
                <button className="button-ghost" type="button" disabled={busy} onClick={requestClose}>
                  {copy("取消", "Cancel")}
                </button>
                <button className="button-primary" type="submit" disabled={busy || !valid}>
                  <Save aria-hidden="true" size={16} />
                  {busy ? copy("保存中…", "Saving…") : copy("保存配置", "Save configuration")}
                </button>
              </div>
            </form>
            <Dialog.Close className="icon-button dialog-close" aria-label={copy("关闭", "Close")} disabled={busy}>
              <X aria-hidden="true" size={18} />
            </Dialog.Close>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
      <ConfirmDialog
        open={confirmHttp}
        title={copy("允许未加密 HTTP？", "Allow unencrypted HTTP?")}
        description={copy(
          "凭据和私人数据可能被同一网络中的其他设备看到。只应连接你信任的局域网服务。",
          "Credentials and private data may be visible on the network. Continue only for a trusted local service.",
        )}
        confirmLabel={copy("我了解风险，继续", "I understand, continue")}
        destructive
        busy={busy}
        onCancel={() => setConfirmHttp(false)}
        onConfirm={() => {
          setConfirmHttp(false);
          void commit();
        }}
      />
      <ConfirmDialog
        open={confirmDiscard}
        title={copy("放弃未保存的修改？", "Discard unsaved changes?")}
        description={copy(
          "同步配置和尚未保存的本机凭据都会被丢弃。",
          "The sync configuration and any unsaved local credentials will be discarded.",
        )}
        confirmLabel={copy("放弃修改", "Discard changes")}
        cancelLabel={copy("继续编辑", "Keep editing")}
        destructive
        onCancel={() => setConfirmDiscard(false)}
        onConfirm={closeImmediately}
      />
    </>
  );
}

export default function CloudSyncPage() {
  const language = useAppStore((state) => state.appearance.language);
  const copy = useCallback(
    (zh: string, en: string) => tr(language, zh, en),
    [language],
  );
  const [snapshot, setSnapshot] = useState<CloudSyncConfigListV1 | null>(null);
  const [pendingJson, setPendingJson] = useState<CloudPendingJsonV1[]>([]);
  const [globalEnabledDraft, setGlobalEnabledDraft] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [syncing, setSyncing] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [editing, setEditing] = useState<CloudSyncConfigV1 | "new" | null>(null);
  const [deleteCandidate, setDeleteCandidate] = useState<CloudSyncConfigV1 | null>(null);
  const [forcedRunCandidate, setForcedRunCandidate] =
    useState<CloudSyncRunMode | null>(null);
  const [pendingPreview, setPendingPreview] =
    useState<CloudPendingJsonPreviewV1 | null>(null);
  const globalDirty =
    snapshot !== null && snapshot.globalEnabled !== globalEnabledDraft;

  const load = useCallback(async (preserveGlobalDraft = false) => {
    setLoading(true);
    setError("");
    try {
      const [next, pending] = await Promise.all([
        cloudApi.listConfigs(),
        cloudApi.listPendingJson(),
      ]);
      setSnapshot(next);
      if (!preserveGlobalDraft) {
        setGlobalEnabledDraft(next.globalEnabled);
      }
      setPendingJson(pending);
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setLoading(false);
    }
  }, [language]);

  useEffect(() => {
    void load();
  }, [load]);

  async function saveGlobalEnabled() {
    setBusy("global");
    setError("");
    try {
      await cloudApi.setEnabled(globalEnabledDraft);
      setSnapshot((current) =>
        current ? { ...current, globalEnabled: globalEnabledDraft } : current,
      );
      setNotice(copy("云同步总开关已保存。", "Cloud sync setting saved."));
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setBusy("");
    }
  }

  async function saveConfig(
    draft: CloudSyncConfigDraftV1,
    credentialUpdate: CloudCredentialUpdateV1,
  ) {
    setBusy("config");
    setError("");
    try {
      await cloudApi.saveConfig(draft, credentialUpdate);
      setNotice(copy("同步配置已保存。", "Sync configuration saved."));
      await load(globalDirty);
    } catch (reason) {
      setError(readableError(reason, language));
      throw reason;
    } finally {
      setBusy("");
    }
  }

  async function deleteConfig() {
    if (!deleteCandidate) return;
    setBusy("delete");
    try {
      await cloudApi.deleteConfig(deleteCandidate.id);
      setDeleteCandidate(null);
      setNotice(copy("配置和本机凭据已删除，远端文件没有变化。", "Configuration and local credentials deleted; remote files were not changed."));
      await load(globalDirty);
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setBusy("");
    }
  }

  async function copyConfig(config: CloudSyncConfigV1) {
    setBusy(`copy:${config.id}`);
    try {
      await cloudApi.copyConfig(config.id);
      setNotice(copy("已复制为停用配置，凭据未复制。", "Copied as a disabled configuration without credentials."));
      await load(globalDirty);
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setBusy("");
    }
  }

  async function runSync(
    configId?: string,
    mode: CloudSyncRunMode = "NORMAL",
  ) {
    if (syncing) return;
    setSyncing(true);
    setBusy(`sync:${configId ?? "all"}`);
    setError("");
    try {
      const result = await cloudApi.run(configId, mode);
      const action =
        mode === "FORCE_UPLOAD"
          ? copy("强制上传", "Force upload")
          : mode === "FORCE_DOWNLOAD"
            ? copy("强制下载", "Force download")
            : copy("同步", "Sync");
      setNotice(
        copy(
          `${action}完成：上传 ${result.uploaded}、下载 ${result.downloaded}、冲突 ${result.conflicts}、跳过 ${result.skipped}。`,
          `${action} complete: ${result.uploaded} uploaded, ${result.downloaded} downloaded, ${result.conflicts} conflicts and ${result.skipped} skipped.`,
        ),
      );
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setSyncing(false);
      setBusy("");
      await load(globalDirty);
    }
  }

  async function cancelSync() {
    setBusy("cancel");
    try {
      await cloudApi.cancel();
      setNotice(copy("已请求取消同步。", "Sync cancellation requested."));
      await load(globalDirty);
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setBusy("");
    }
  }

  async function previewJson(item: CloudPendingJsonV1) {
    setBusy(`preview:${item.id}`);
    try {
      setPendingPreview(await cloudApi.previewPendingJson(item.id));
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setBusy("");
    }
  }

  async function restoreJson() {
    if (!pendingPreview) return;
    setBusy("restore");
    try {
      await cloudApi.restorePendingJson(
        pendingPreview.id,
        pendingPreview.confirmationToken,
      );
      setPendingPreview(null);
      setNotice(copy("远端应用 JSON 已恢复。", "Remote app JSON restored."));
      window.dispatchEvent(new CustomEvent("deskcubby:data-restored"));
      await load(globalDirty);
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setBusy("");
    }
  }

  const enabledConfigs = useMemo(
    () =>
      snapshot?.configs.filter(
        (config) =>
          config.enabled &&
          (config.serviceType === "WEBDAV" || config.hasCredentials),
      ) ?? [],
    [snapshot],
  );
  const enabledSourceCount =
    snapshot?.configs.filter((config) => config.enabled).length ?? 0;
  const syncInProgress = syncing || snapshot?.status.running === true;

  if (loading && !snapshot) {
    return (
      <PageFrame title={copy("云端同步", "Cloud sync")}>
        <div className="panel"><LoadingState label={copy("正在读取同步配置", "Loading sync configurations")} /></div>
      </PageFrame>
    );
  }

  return (
    <PageFrame
      className="cloud-sync-page"
      eyebrow={copy("设置 → 应用数据", "Settings → App data")}
      title={copy("WebDAV / S3 云同步", "WebDAV / S3 cloud sync")}
      description={copy(
  "同步日记、媒体与 Android v29 应用 JSON；手机使用时间仅按需从 Android usage 对象下载并显示。本地文件仍是最终来源。",
  "Sync diaries, media and Android v29 app JSON; phone screen time is downloaded from Android usage objects only when selected. Local files remain the source of truth.",
      )}
      actions={
        <>
          <Link className="button-secondary" to="/backup">
            <DatabaseBackup aria-hidden="true" size={17} />
            {copy("本地备份", "Local backup")}
          </Link>
          {syncInProgress ? (
            <button className="button-secondary" type="button" disabled={busy === "cancel"} onClick={() => void cancelSync()}>
              <X aria-hidden="true" size={17} />
              {busy === "cancel"
                ? copy("取消中…", "Cancelling…")
                : copy("取消同步", "Cancel sync")}
            </button>
          ) : (
            <button
              className="button-secondary"
              type="button"
              disabled={!!busy || enabledConfigs.length === 0}
              onClick={() => void runSync()}
            >
              <RefreshCw className={busy.startsWith("sync:") ? "spin" : ""} aria-hidden="true" size={17} />
              {copy("全部立即同步", "Sync all now")}
            </button>
          )}
          <button className="button-primary" type="button" disabled={!!busy || syncInProgress} onClick={() => setEditing("new")}>
            <Plus aria-hidden="true" size={17} />
            {copy("新增配置", "New configuration")}
          </button>
        </>
      }
    >
      <UnsavedChangesGuard when={globalDirty} scope="cloud-sync-enabled" />
      {error ? <div className="inline-error" role="alert">{error}</div> : null}
      {notice ? <div className="status-banner success" role="status">{notice}</div> : null}

      {snapshot ? (
        <>
          <section className="panel cloud-global-panel">
            <div className="settings-section-heading">
              <Cloud aria-hidden="true" size={21} />
              <div>
                <h2>{copy("云同步总开关", "Cloud sync")}</h2>
                <p>
                  {copy(
                    "只控制后台调度；手动“立即同步”始终可用。修改需点击右侧保存。",
                    "Controls background scheduling only; manual sync remains available. Save to apply changes.",
                  )}
                </p>
              </div>
            </div>
            <div className="cloud-global-controls">
              <div className="cloud-global-actions">
                <label className="switch-row">
                  <input
                    type="checkbox"
                    checked={globalEnabledDraft}
                    onChange={(event) => setGlobalEnabledDraft(event.target.checked)}
                  />
                  {globalEnabledDraft ? copy("已开启", "Enabled") : copy("已关闭", "Disabled")}
                </label>
                <button
                  className="button-primary"
                  type="button"
                  disabled={!globalDirty || busy === "global"}
                  onClick={() => void saveGlobalEnabled()}
                >
                  {globalDirty ? <Save aria-hidden="true" size={16} /> : <Check aria-hidden="true" size={16} />}
                  {busy === "global"
                    ? copy("保存中…", "Saving…")
                    : globalDirty
                      ? copy("保存", "Save")
                      : copy("已保存", "Saved")}
                </button>
              </div>
              <div className="cloud-global-actions">
                <button
                  className="button-secondary"
                  type="button"
                  disabled={!!busy || syncInProgress || enabledConfigs.length === 0}
                  onClick={() => setForcedRunCandidate("FORCE_UPLOAD")}
                >
                  <UploadCloud aria-hidden="true" size={16} />
                  {copy("强制上传", "Force upload")}
                </button>
                <button
                  className="button-secondary"
                  type="button"
                  disabled={
                    !!busy ||
                    syncInProgress ||
                    enabledSourceCount !== 1 ||
                    enabledConfigs.length !== 1
                  }
                  onClick={() => setForcedRunCandidate("FORCE_DOWNLOAD")}
                >
                  <DownloadCloud aria-hidden="true" size={16} />
                  {copy("强制下载", "Force download")}
                </button>
              </div>
              <p className="dialog-description cloud-global-note">
                {copy(
                  "强制上传以本机为准，强制下载以唯一启用的云端来源为准。两者均不传播删除，且仍执行远端版本和本机快照条件校验；应用 JSON 下载仍只会暂存待确认。",
                  "Force upload prefers local data; force download prefers the single enabled cloud source. Neither propagates deletions, and remote-version and local-snapshot checks still apply. Downloaded app JSON remains staged for confirmation.",
                )}
              </p>
            </div>
          </section>

          {syncInProgress ? (
            <div className="status-banner warning" role="status" aria-live="polite">
              <LoaderCircle className="spin" aria-hidden="true" size={18} />
              {copy(
                `正在同步：${snapshot.status.phase ?? "…"}`,
                `Syncing: ${snapshot.status.phase ?? "…"}`,
              )}
            </div>
          ) : snapshot.status.lastErrorCode ? (
            <div className="status-banner warning" role="status">
              {copy("上次同步未完成，请重试。", "The last sync did not complete. Try again.")}
            </div>
          ) : null}

          <section aria-labelledby="cloud-config-list-title">
            <div className="section-header">
              <div>
                <h2 id="cloud-config-list-title">{copy("同步服务", "Sync services")}</h2>
                <p>{copy("凭据只显示是否已配置，不会回传具体内容。", "Only credential presence is shown; secret values are never returned.")}</p>
              </div>
            </div>
            {snapshot.configs.length === 0 ? (
              <div className="panel">
                <EmptyState
                  title={copy("还没有同步服务", "No sync services yet")}
                  description={copy("添加 WebDAV 或 S3 兼容服务。", "Add a WebDAV or S3-compatible service.")}
                  icon={Server}
                  action={
                    <button className="button-primary" type="button" onClick={() => setEditing("new")}>
                      <Plus aria-hidden="true" size={16} />
                      {copy("新增配置", "New configuration")}
                    </button>
                  }
                />
              </div>
            ) : (
              <div className="cloud-config-grid">
                {snapshot.configs.map((config) => (
                  <article className="card cloud-config-card" key={config.id}>
                    <div className="cloud-config-heading">
                      <span className="cloud-service-icon" aria-hidden="true">
                        {config.serviceType === "WEBDAV" ? <Cloud /> : <Server />}
                      </span>
                      <div>
                        <h3>{config.name}</h3>
                        <p>{config.serviceType === "WEBDAV" ? "WebDAV" : copy("S3 兼容", "S3 compatible")}</p>
                      </div>
                      <span className={`badge ${config.enabled ? "is-active" : ""}`}>
                        {config.enabled ? copy("启用", "Enabled") : copy("停用", "Disabled")}
                      </span>
                    </div>
                    <dl className="cloud-config-summary">
                      <div>
                        <dt>{copy("内容", "Content")}</dt>
                        <dd>
                          {config.selectedContents
                            .map((content) =>
                              content === "DIARIES"
                                ? copy("日记", "Diaries")
                                : content === "MEDIA"
                                  ? copy("媒体", "Media")
                                  : content === "JSON_BACKUP"
                                    ? "JSON"
                                    : content === "USAGE_STATISTICS"
                                      ? copy("手机使用时间", "Phone screen time")
                                      : copy("阅读进度", "Reading progress"),
                            )
                            .join(language === "en" ? ", " : "、")}
                        </dd>
                      </div>
                      <div>
                        <dt>{copy("方向", "Direction")}</dt>
                        <dd>{config.direction === "TWO_WAY" ? copy("双向", "Two-way") : copy("仅上传", "Upload only")}</dd>
                      </div>
                      <div>
                        <dt>{copy("凭据", "Credentials")}</dt>
                        <dd>
                          {config.hasCredentials
                            ? copy("本机已保存", "Stored locally")
                            : config.serviceType === "WEBDAV"
                              ? copy("匿名或仅用户名", "Anonymous or username only")
                              : copy("未配置", "Missing")}
                        </dd>
                      </div>
                    </dl>
                    <div className="cloud-card-actions">
                      <button
                        className="button-secondary button-small"
                        type="button"
                        disabled={
                          !!busy ||
                          !config.enabled ||
                          (config.serviceType === "S3_COMPATIBLE" &&
                            !config.hasCredentials)
                        }
                        onClick={() => void runSync(config.id)}
                      >
                        <UploadCloud aria-hidden="true" size={15} />
                        {copy("立即同步", "Sync now")}
                      </button>
                      <button className="icon-button" type="button" aria-label={copy("编辑配置", "Edit configuration")} disabled={!!busy} onClick={() => setEditing(config)}>
                        <Pencil aria-hidden="true" size={16} />
                      </button>
                      <button className="icon-button" type="button" aria-label={copy("复制配置", "Copy configuration")} disabled={!!busy} onClick={() => void copyConfig(config)}>
                        <Copy aria-hidden="true" size={16} />
                      </button>
                      <button className="icon-button danger" type="button" aria-label={copy("删除配置", "Delete configuration")} disabled={!!busy} onClick={() => setDeleteCandidate(config)}>
                        <Trash2 aria-hidden="true" size={16} />
                      </button>
                    </div>
                  </article>
                ))}
              </div>
            )}
          </section>

          {pendingJson.length > 0 ? (
            <section className="panel cloud-pending-panel" aria-labelledby="pending-json-title">
              <div className="panel-heading">
                <div>
                  <h2 id="pending-json-title">{copy("待确认的远端应用 JSON", "Pending remote app JSON")}</h2>
                  <p>{copy("后台同步只暂存文件；必须预览后才能恢复。", "Background sync only stages these files; preview is required before restore.")}</p>
                </div>
              </div>
              <ul className="cloud-pending-list">
                {pendingJson.map((item) => (
                  <li key={item.id}>
                    <div>
                      <strong>{item.sourceLabel ?? copy("远端同步服务", "Cloud sync service")}</strong>
                      <small>
                        {new Intl.DateTimeFormat(language === "en" ? "en-US" : "zh-CN", {
                          dateStyle: "medium",
                          timeStyle: "short",
                        }).format(new Date(item.receivedAt))}
                        {" · "}
                        {new Intl.NumberFormat(language === "en" ? "en-US" : "zh-CN", {
                          style: "unit",
                          unit: "kilobyte",
                          maximumFractionDigits: 1,
                        }).format(item.size / 1024)}
                      </small>
                    </div>
                    <button className="button-secondary" type="button" disabled={!!busy} onClick={() => void previewJson(item)}>
                      {busy === `preview:${item.id}` ? copy("读取中…", "Loading…") : copy("预览并恢复", "Preview & restore")}
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}
        </>
      ) : (
        <div className="panel">
          <EmptyState
            title={copy("无法读取云同步设置", "Cloud sync settings unavailable")}
            description={error}
            action={
              <button className="button-secondary" type="button" onClick={() => void load()}>
                <RefreshCw aria-hidden="true" size={16} />
                {copy("重试", "Retry")}
              </button>
            }
          />
        </div>
      )}

      <ConfigDialog
        open={editing !== null}
        config={editing === "new" ? null : editing}
        busy={busy === "config"}
        language={language}
        onClose={() => setEditing(null)}
        onSave={saveConfig}
      />
      <ConfirmDialog
        open={forcedRunCandidate !== null}
        title={
          forcedRunCandidate === "FORCE_UPLOAD"
            ? copy("确认强制上传？", "Force upload?")
            : copy("确认强制下载？", "Force download?")
        }
        description={
          forcedRunCandidate === "FORCE_UPLOAD"
            ? copy(
                "本机新增项目会上传；同路径内容不同时，只在远端仍匹配本轮扫描版本时覆盖。远端独有项目不会删除，扫描后的远端修改会阻止覆盖。",
                "New local items are uploaded. Different items at the same path replace remote data only while its scanned version still matches. Remote-only items are not deleted, and later remote edits stop the overwrite.",
              )
            : copy(
                "仅使用当前唯一启用的云端来源。云端新增项目会下载；同路径内容不同时，只在本机仍匹配扫描快照时采用云端版本。本机独有项目不会删除，并发本机修改会保留并产生冲突副本。",
                "Only the single enabled cloud source is used. New remote items are downloaded, and different items at the same path use remote data only while the local scan snapshot still matches. Local-only items are not deleted, and concurrent local edits are preserved with a conflict copy.",
              )
        }
        confirmLabel={
          forcedRunCandidate === "FORCE_UPLOAD"
            ? copy("强制上传", "Force upload")
            : copy("强制下载", "Force download")
        }
        destructive
        onCancel={() => setForcedRunCandidate(null)}
        onConfirm={() => {
          const mode = forcedRunCandidate;
          setForcedRunCandidate(null);
          if (mode) void runSync(undefined, mode);
        }}
      />
      <ConfirmDialog
        open={deleteCandidate !== null}
        title={copy("删除同步配置？", "Delete sync configuration?")}
        description={copy(
          "会同时删除本机加密凭据，但不会删除任何远端文件。",
          "Local encrypted credentials are deleted, but remote files are left untouched.",
        )}
        confirmLabel={copy("删除", "Delete")}
        destructive
        busy={busy === "delete"}
        onCancel={() => setDeleteCandidate(null)}
        onConfirm={() => void deleteConfig()}
      />
      <ConfirmDialog
        open={pendingPreview !== null}
        title={copy("恢复这份远端应用 JSON？", "Restore this remote app JSON?")}
        description={
          pendingPreview
            ? copy(
                `格式 v${pendingPreview.formatVersion}：${pendingPreview.thoughtCount} 条小巧思、${pendingPreview.categoryCount} 个分类、${pendingPreview.dateRecordCount} 条日期记录、${pendingPreview.poemCount} 首诗词。日记与媒体文件不会被替换。`,
                `Format v${pendingPreview.formatVersion}: ${pendingPreview.thoughtCount} thoughts, ${pendingPreview.categoryCount} categories, ${pendingPreview.dateRecordCount} date records and ${pendingPreview.poemCount} poems. Diary and media files are not replaced.`,
              )
            : undefined
        }
        confirmLabel={copy("确认恢复", "Restore")}
        destructive
        busy={busy === "restore"}
        onCancel={() => setPendingPreview(null)}
        onConfirm={() => void restoreJson()}
      />
    </PageFrame>
  );
}
