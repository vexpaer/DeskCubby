/**
 * 设置 → 云端同步 (README_for_ai §17.14)。
 *
 * 配置由后端专用端点管理，字段与 Android CloudSyncModels.kt 一致：
 * - GET /api/cloudsync/status -> {running, configs(脱敏), lastResult, undoAvailable}
 *   脱敏行中秘密字段恒为空串，是否已配置凭据用 hasCredentials 标记。
 * - POST /api/cloudsync/configs 新建、PUT /api/cloudsync/configs/{id} 更新、
 *   DELETE /api/cloudsync/configs/{id} 删除（同时清除服务器保存的凭据）。
 * - 立即同步/强制上传/强制下载：POST /api/cloudsync/sync {configId, mode}。
 * 秘密输入只写不读：留空提交表示保持服务器已存值不变。
 * cloudSyncEnabled 主开关仍是设置草稿字段，随右上角「保存」持久化。
 */
import React, { useCallback, useEffect, useState } from "react";
import { apiGet, apiSend } from "../../../api/client";
import type { CloudSyncConfig } from "../../../api/types";
import { tr } from "../../../i18n/tr";
import { ConfirmDialog, ErrorText, Modal, Spinner } from "../../../components/ui";
import { SectionCard, SelectField, TextField, Toggle } from "../SettingsPage";
import type { SettingsSectionProps } from "../SettingsPage";

type SyncMode = "now" | "force_upload" | "force_download";

interface CloudSyncLastResult {
  uploaded?: number;
  downloaded?: number;
  conflicts?: number;
  finishedAtMs?: number | null;
}

interface CloudSyncStatusInfo {
  running?: boolean;
  configs?: CloudSyncConfig[];
  lastResult?: CloudSyncLastResult | null;
  undoAvailable?: boolean;
}

/** 与后端 SECRET_FIELDS 一致；空值剔除后提交，语义为“留空保持不变”。 */
const SECRET_FIELDS = ["webDavPassword", "s3AccessKey", "s3SecretKey", "s3SessionToken"] as const;

const CONTENT_LABELS: Record<string, { zh: string; en: string }> = {
  DIARIES: { zh: "日记", en: "Diaries" },
  NOTES: { zh: "笔记", en: "Notes" },
  MEDIA: { zh: "媒体", en: "Media" },
  THOUGHTS: { zh: "小巧思", en: "Thoughts" },
  THOUGHT_CATEGORIES: { zh: "小巧思分类", en: "Thought categories" },
  DATE_RECORDS: { zh: "日期记录", en: "Date records" },
  POEMS: { zh: "诗词", en: "Poems" },
  POETRY_CATEGORIES: { zh: "诗词分类", en: "Poetry categories" },
  FAVORITES: { zh: "收藏夹", en: "Favorites" },
  RSS_SUBSCRIPTIONS: { zh: "RSS 订阅", en: "RSS subscriptions" },
  GAME_STATES: { zh: "游戏存档", en: "Game states" },
  GAME_STATISTICS: { zh: "游戏统计", en: "Game statistics" },
  USAGE_STATISTICS: { zh: "手机使用时间", en: "Usage statistics" },
  READING_PROGRESS: { zh: "阅读进度", en: "Reading progress" },
  READER_PREFERENCES: { zh: "阅读偏好", en: "Reader preferences" },
  AGENT_CHATS: { zh: "AI 对话", en: "AI chats" },
  VAULT: { zh: "收藏夹密文", en: "Vault ciphertext" },
  GLOBAL_SETTINGS: { zh: "全局设置", en: "Global settings" },
};

const ALL_CONTENTS = Object.keys(CONTENT_LABELS);

const DEFAULT_CONTENTS = [
  "DIARIES", "NOTES", "MEDIA", "THOUGHTS", "THOUGHT_CATEGORIES", "DATE_RECORDS",
  "POEMS", "POETRY_CATEGORIES", "FAVORITES", "READING_PROGRESS",
  "READER_PREFERENCES", "AGENT_CHATS",
];

function contentLabel(id: string): string {
  const found = CONTENT_LABELS[id];
  return found ? tr(found.zh, found.en) : id;
}

function newForm(): CloudSyncConfig {
  return {
    id: "",
    name: "",
    enabled: true,
    serviceType: "WEBDAV",
    endpointUrl: "",
    remotePath: "DeskCubby",
    userAgent: "DeskCubby-Sync/1",
    webDavUsername: "",
    s3Bucket: "",
    s3Region: "us-east-1",
    s3PathStyle: true,
    allowInsecureHttp: false,
    selectedContents: [...DEFAULT_CONTENTS],
    direction: "TWO_WAY",
    webDavPassword: "",
    s3AccessKey: "",
    s3SecretKey: "",
    s3SessionToken: "",
  };
}

/** 从已保存配置进入编辑：秘密字段保持空串（留空=保持不变）。 */
function editForm(c: CloudSyncConfig): CloudSyncConfig {
  return { ...newForm(), ...c, selectedContents: c.selectedContents?.length ? [...c.selectedContents] : [] };
}

function urlValid(url: string): boolean {
  try {
    const parsed = new URL(url.trim());
    return !!parsed.hostname && (parsed.protocol === "https:" || parsed.protocol === "http:");
  } catch {
    return false;
  }
}

function formatWhen(ms: number | null | undefined): string {
  if (typeof ms !== "number" || !Number.isFinite(ms)) return "";
  try {
    return new Date(ms).toLocaleString();
  } catch {
    return String(ms);
  }
}

export default function CloudSyncSection({ settings, patch, snackbar }: SettingsSectionProps) {
  const [status, setStatus] = useState<CloudSyncStatusInfo>({});
  const [statusError, setStatusError] = useState<unknown>(null);
  const [syncing, setSyncing] = useState<{ id: string; mode: SyncMode } | null>(null);
  const [undoing, setUndoing] = useState(false);
  const [actionError, setActionError] = useState<unknown>(null);

  const loadStatus = useCallback(async () => {
    try {
      setStatus(await apiGet<CloudSyncStatusInfo>("/api/cloudsync/status"));
      setStatusError(null);
    } catch (e) {
      setStatusError(e);
    }
  }, []);

  useEffect(() => {
    void loadStatus();
  }, [loadStatus]);

  const runSync = async (id: string, mode: SyncMode) => {
    if (syncing) return;
    setSyncing({ id, mode });
    setActionError(null);
    try {
      const resp = await apiSend<{ result?: CloudSyncLastResult }>("/api/cloudsync/sync", "POST", { configId: id, mode });
      const r = resp?.result ?? {};
      snackbar(tr(
        `上传 ${r.uploaded ?? 0} · 下载 ${r.downloaded ?? 0} · 冲突 ${r.conflicts ?? 0}`,
        `${r.uploaded ?? 0} uploaded · ${r.downloaded ?? 0} downloaded · ${r.conflicts ?? 0} conflicts`,
      ));
    } catch (e) {
      setActionError(e);
    } finally {
      setSyncing(null);
      void loadStatus();
    }
  };

  // ----- 配置 CRUD（走后端端点） -------------------------------------------
  const [dialog, setDialog] = useState<{ isNew: boolean; form: CloudSyncConfig } | null>(null);
  const [deleting, setDeleting] = useState<CloudSyncConfig | null>(null);
  const [confirmForce, setConfirmForce] = useState<{ config: CloudSyncConfig; mode: Extract<SyncMode, "force_upload" | "force_download"> } | null>(null);
  const [undoOpen, setUndoOpen] = useState(false);
  const [savingDialog, setSavingDialog] = useState(false);

  const dialogValid =
    !!dialog &&
    dialog.form.name.trim().length > 0 &&
    urlValid(dialog.form.endpointUrl) &&
    (dialog.form.serviceType !== "S3_COMPATIBLE" || !!dialog.form.s3Bucket?.trim()) &&
    dialog.form.selectedContents.length > 0;

  const saveDialog = async () => {
    if (!dialog || !dialogValid || savingDialog) return;
    setSavingDialog(true);
    setActionError(null);
    try {
      // 空秘密字段剔除：后端语义为「留空保持已存值」。
      const body: Record<string, unknown> = { ...dialog.form };
      for (const key of SECRET_FIELDS) {
        if (!String(body[key] ?? "").trim()) delete body[key];
      }
      delete body.hasCredentials;
      if (dialog.isNew) {
        await apiSend("/api/cloudsync/configs", "POST", body);
      } else {
        await apiSend(`/api/cloudsync/configs/${encodeURIComponent(dialog.form.id)}`, "PUT", body);
      }
      setDialog(null);
      snackbar(tr("配置已保存到服务器", "Configuration saved"));
      void loadStatus();
    } catch (e) {
      setActionError(e);
    } finally {
      setSavingDialog(false);
    }
  };

  const removeConfig = async () => {
    if (!deleting) return;
    setActionError(null);
    try {
      await apiSend(`/api/cloudsync/configs/${encodeURIComponent(deleting.id)}`, "DELETE");
      snackbar(tr("配置已删除", "Configuration deleted"));
      void loadStatus();
    } catch (e) {
      setActionError(e);
    } finally {
      setDeleting(null);
    }
  };

  const undoLastSync = async () => {
    setUndoing(true);
    setActionError(null);
    try {
      await apiSend("/api/cloudsync/undo", "POST");
      snackbar(tr("已撤回最近一次同步", "Last sync undone"));
    } catch (e) {
      setActionError(e);
    } finally {
      setUndoing(false);
      setUndoOpen(false);
      void loadStatus();
    }
  };

  const configs = status.configs ?? [];
  const busyAny = syncing !== null || status.running === true;

  return (
    <div className="dc-col" style={{ gap: 12 }}>
      <SectionCard
        title={tr("云端同步", "Cloud sync")}
        description={tr(
          "开启后后台任务会在联网时同步已启用的服务；主开关随右上角「保存」生效。",
          "Background jobs sync the enabled services while online; the master switch applies on Save.",
        )}
      >
        <Toggle
          checked={settings.cloudSyncEnabled}
          onChange={(v) => patch({ cloudSyncEnabled: v })}
          label={<span>{tr("开启云端同步", "Enable cloud sync")}<div className="dc-muted" style={{ fontSize: "0.82em" }}>{tr("至少保留一个「已启用」的服务后保存，同步才会运行。", "Keep at least one enabled service and save before sync runs.")}</div></span>}
        />
        <ErrorText error={actionError} />
        <div className="dc-row dc-wrap">
          <button className="dc-btn dc-btn-tonal" onClick={() => setDialog({ isNew: true, form: newForm() })}>{tr("新建配置", "New configuration")}</button>
          <button className="dc-btn dc-btn-danger" disabled={undoing} onClick={() => setUndoOpen(true)}>
            {undoing ? tr("正在撤回…", "Undoing…") : tr("撤回一次", "Undo once")}
          </button>
          {(undoing || syncing !== null) && <Spinner size={18} />}
        </div>
        <div className="dc-muted" style={{ fontSize: "0.84em" }}>
          {tr(
            "「撤回一次」恢复最近一次同步被覆盖的本机内容，并把本轮新建文件移入回收站。",
            "“Undo once” restores content overwritten by the latest sync and moves files it created into the trash.",
          )}
        </div>
      </SectionCard>

      <SectionCard title={tr("同步服务", "Sync services")}>
        <ErrorText error={statusError} />
        {!status.configs && !statusError && <Spinner size={20} />}
        {configs.length === 0 && (
          <div className="dc-muted" style={{ fontSize: "0.88em" }}>
            {tr("还没有同步服务，点「新建配置」添加 WebDAV 或 S3 兼容端点。", "No services yet — add a WebDAV or S3-compatible endpoint.")}
          </div>
        )}
        <div className="dc-col" style={{ gap: 10 }}>
          {configs.map((config) => {
            const busy = syncing?.id === config.id;
            return (
              <div key={config.id} className="dc-card dc-col" style={{ padding: 12, gap: 8 }}>
                <div className="dc-row" style={{ justifyContent: "space-between", gap: 8 }}>
                  <span style={{ fontWeight: 600, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                    {config.name || tr("未命名配置", "Unnamed configuration")}
                  </span>
                  <span className="dc-muted" style={{ fontSize: "0.82em", flexShrink: 0 }}>
                    {config.serviceType === "S3_COMPATIBLE"
                      ? `S3 · ${config.s3Bucket || "-"}`
                      : "WebDAV"}
                    {!config.enabled && tr(" · 已停用", " · disabled")}
                    {!config.hasCredentials && tr(" · 未配置凭据", " · no credentials")}
                  </span>
                </div>
                <div className="dc-muted" style={{ fontSize: "0.85em", wordBreak: "break-all" }}>
                  {config.endpointUrl}{config.remotePath ? ` / ${config.remotePath}` : ""}
                  {" · "}{config.direction === "UPLOAD_ONLY" ? tr("仅上传", "upload only") : tr("双向", "two-way")}
                  {" · "}{(config.selectedContents ?? []).length} {tr("类内容", "content kinds")}
                </div>
                <div className="dc-row dc-wrap">
                  <button
                    className="dc-btn dc-btn-filled"
                    disabled={!config.enabled || busyAny}
                    onClick={() => void runSync(config.id, "now")}
                  >
                    {busy && syncing?.mode === "now" ? tr("正在同步…", "Syncing…") : tr("立即同步", "Sync now")}
                  </button>
                  <button
                    className="dc-btn"
                    disabled={!config.enabled || busyAny}
                    onClick={() => setConfirmForce({ config, mode: "force_upload" })}
                  >
                    {busy && syncing?.mode === "force_upload" ? tr("正在上传…", "Uploading…") : tr("强制上传", "Force upload")}
                  </button>
                  <button
                    className="dc-btn"
                    disabled={!config.enabled || busyAny}
                    onClick={() => setConfirmForce({ config, mode: "force_download" })}
                  >
                    {busy && syncing?.mode === "force_download" ? tr("正在下载…", "Downloading…") : tr("强制下载", "Force download")}
                  </button>
                  <button className="dc-btn" onClick={() => setDialog({ isNew: false, form: editForm(config) })}>{tr("编辑", "Edit")}</button>
                  <button className="dc-btn dc-btn-danger" onClick={() => setDeleting(config)}>{tr("删除", "Delete")}</button>
                  {busy && <Spinner size={18} />}
                </div>
              </div>
            );
          })}
        </div>
      </SectionCard>

      <SectionCard title={tr("最近结果", "Latest result")}>
        {status.running === true && (
          <div className="dc-muted" style={{ fontSize: "0.88em" }}>{tr("正在同步…", "A sync is running…")}</div>
        )}
        {status.lastResult ? (
          <>
            <div className="dc-row" style={{ justifyContent: "space-between" }}>
              <span>{tr("最近一次", "Last run")}</span>
              <span className="dc-muted" style={{ fontVariantNumeric: "tabular-nums" }}>
                {tr(
                  `上传 ${status.lastResult.uploaded ?? 0} · 下载 ${status.lastResult.downloaded ?? 0} · 冲突 ${status.lastResult.conflicts ?? 0}`,
                  `${status.lastResult.uploaded ?? 0} uploaded · ${status.lastResult.downloaded ?? 0} downloaded · ${status.lastResult.conflicts ?? 0} conflicts`,
                )}
              </span>
            </div>
            {formatWhen(status.lastResult.finishedAtMs) && (
              <div className="dc-row" style={{ justifyContent: "space-between" }}>
                <span>{tr("完成时间", "Finished at")}</span>
                <span className="dc-muted">{formatWhen(status.lastResult.finishedAtMs)}</span>
              </div>
            )}
          </>
        ) : (
          !statusError && (
            <div className="dc-muted" style={{ fontSize: "0.88em" }}>
              {tr("还没有同步记录。", "No sync has run yet.")}
            </div>
          )
        )}
      </SectionCard>

      {/* ----- 新建 / 编辑 配置对话框 ----- */}
      <Modal
        open={dialog !== null}
        onClose={() => setDialog(null)}
        title={dialog?.isNew ? tr("新建同步服务", "New sync service") : tr("编辑同步服务", "Edit sync service")}
        width={560}
      >
        {dialog && (
          <div className="dc-col" style={{ gap: 10 }}>
            <TextField
              label={tr("配置名称", "Configuration name")}
              value={dialog.form.name}
              maxLength={200}
              error={!dialog.form.name.trim()}
              onChange={(v) => setDialog({ ...dialog, form: { ...dialog.form, name: v } })}
            />
            <SelectField
              label={tr("服务类型", "Service type")}
              value={dialog.form.serviceType === "S3_COMPATIBLE" ? "S3_COMPATIBLE" : "WEBDAV"}
              onChange={(v) => setDialog({ ...dialog, form: { ...dialog.form, serviceType: v === "S3_COMPATIBLE" ? "S3_COMPATIBLE" : "WEBDAV" } })}
              options={[
                { value: "WEBDAV", label: "WebDAV" },
                { value: "S3_COMPATIBLE", label: tr("S3 兼容", "S3-compatible") },
              ]}
            />
            <TextField
              label={tr("服务地址", "Service URL")}
              value={dialog.form.endpointUrl}
              maxLength={2048}
              error={!urlValid(dialog.form.endpointUrl)}
              placeholder="https://dav.example.com/desk-cubby"
              hint={urlValid(dialog.form.endpointUrl)
                ? tr("填写完整协议地址；HTTP 仅建议用于可信局域网。", "Full protocol URL; HTTP only for trusted LAN services.")
                : tr("必须是带主机名的 http(s) 地址。", "Must be an http(s) address with a host.")}
              onChange={(v) => setDialog({ ...dialog, form: { ...dialog.form, endpointUrl: v } })}
            />
            <TextField
              label={tr("远端目录", "Remote folder")}
              value={dialog.form.remotePath ?? ""}
              maxLength={1024}
              hint={tr("不能包含 . 或 .. 路径段。", "Must not contain . or .. path segments.")}
              onChange={(v) => setDialog({ ...dialog, form: { ...dialog.form, remotePath: v } })}
            />
            {dialog.form.serviceType === "WEBDAV" ? (
              <>
                <TextField
                  label={tr("用户名", "Username")}
                  value={dialog.form.webDavUsername ?? ""}
                  maxLength={8192}
                  onChange={(v) => setDialog({ ...dialog, form: { ...dialog.form, webDavUsername: v } })}
                />
                <TextField
                  label={tr("密码", "Password")}
                  value={dialog.form.webDavPassword ?? ""}
                  maxLength={8192}
                  placeholder={tr("已配置（留空保持不变）", "Configured (leave empty to keep)")}
                  onChange={(v) => setDialog({ ...dialog, form: { ...dialog.form, webDavPassword: v } })}
                />
              </>
            ) : (
              <>
                <TextField
                  label="Bucket"
                  value={dialog.form.s3Bucket ?? ""}
                  maxLength={255}
                  error={!dialog.form.s3Bucket?.trim()}
                  onChange={(v) => setDialog({ ...dialog, form: { ...dialog.form, s3Bucket: v } })}
                />
                <TextField
                  label="Region"
                  value={dialog.form.s3Region ?? ""}
                  maxLength={128}
                  placeholder="us-east-1"
                  onChange={(v) => setDialog({ ...dialog, form: { ...dialog.form, s3Region: v } })}
                />
                <TextField
                  label="Access Key ID"
                  value={dialog.form.s3AccessKey ?? ""}
                  maxLength={8192}
                  placeholder={tr("已配置（留空保持不变）", "Configured (leave empty to keep)")}
                  onChange={(v) => setDialog({ ...dialog, form: { ...dialog.form, s3AccessKey: v } })}
                />
                <TextField
                  label="Secret Access Key"
                  value={dialog.form.s3SecretKey ?? ""}
                  maxLength={8192}
                  placeholder={tr("已配置（留空保持不变）", "Configured (leave empty to keep)")}
                  onChange={(v) => setDialog({ ...dialog, form: { ...dialog.form, s3SecretKey: v } })}
                />
                <TextField
                  label={tr("会话令牌（可选）", "Session token (optional)")}
                  value={dialog.form.s3SessionToken ?? ""}
                  maxLength={8192}
                  placeholder={tr("已配置（留空保持不变）", "Configured (leave empty to keep)")}
                  onChange={(v) => setDialog({ ...dialog, form: { ...dialog.form, s3SessionToken: v } })}
                />
                <Toggle
                  checked={!!dialog.form.s3PathStyle}
                  onChange={(v) => setDialog({ ...dialog, form: { ...dialog.form, s3PathStyle: v } })}
                  label={<span style={{ fontSize: "0.9em" }}>{tr("路径风格寻址（path-style）", "Path-style addressing")}</span>}
                />
              </>
            )}
            <SelectField
              label={tr("同步方向", "Direction")}
              value={dialog.form.direction === "UPLOAD_ONLY" ? "UPLOAD_ONLY" : "TWO_WAY"}
              onChange={(v) => setDialog({ ...dialog, form: { ...dialog.form, direction: v === "UPLOAD_ONLY" ? "UPLOAD_ONLY" : "TWO_WAY" } })}
              options={[
                { value: "TWO_WAY", label: tr("双向同步", "Two-way") },
                { value: "UPLOAD_ONLY", label: tr("仅上传", "Upload only") },
              ]}
            />
            <div className="dc-col" style={{ gap: 4 }}>
              <strong style={{ fontSize: "0.92em" }}>{tr("同步内容", "Contents to sync")}</strong>
              <div className="dc-row dc-wrap" style={{ gap: 10 }}>
                {ALL_CONTENTS.map((id) => (
                  <label key={id} className="dc-row" style={{ gap: 4, fontSize: "0.88em" }}>
                    <input
                      type="checkbox"
                      checked={dialog.form.selectedContents.includes(id)}
                      onChange={(e) => {
                        const next = e.target.checked
                          ? [...dialog.form.selectedContents, id]
                          : dialog.form.selectedContents.filter((x) => x !== id);
                        setDialog({ ...dialog, form: { ...dialog.form, selectedContents: next } });
                      }}
                    />
                    {contentLabel(id)}
                  </label>
                ))}
              </div>
            </div>
            <Toggle
              checked={dialog.form.enabled}
              onChange={(v) => setDialog({ ...dialog, form: { ...dialog.form, enabled: v } })}
              label={<span>{tr("启用此配置", "Enabled")}</span>}
            />
            <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 6 }}>
              <button className="dc-btn" onClick={() => setDialog(null)}>{tr("取消", "Cancel")}</button>
              <button className="dc-btn dc-btn-filled" disabled={!dialogValid || savingDialog} onClick={() => void saveDialog()}>
                {savingDialog ? tr("保存中…", "Saving…") : tr("确定", "OK")}
              </button>
            </div>
          </div>
        )}
      </Modal>

      {/* ----- 删除确认 ----- */}
      <ConfirmDialog
        open={deleting !== null}
        title={tr("删除同步配置？", "Delete this sync configuration?")}
        message={tr(
          "将删除该配置及本服务器保存的凭据；不会删除云端文件。",
          "The configuration and credentials stored on this server are removed; remote files are kept.",
        )}
        confirmLabel={tr("删除", "Delete")}
        danger
        onCancel={() => setDeleting(null)}
        onConfirm={() => void removeConfig()}
      />

      {/* ----- 强制操作确认 ----- */}
      <ConfirmDialog
        open={confirmForce !== null}
        title={confirmForce?.mode === "force_download" ? tr("确认强制下载？", "Force download?") : tr("确认强制上传？", "Force upload?")}
        message={
          confirmForce?.mode === "force_download"
            ? tr(
                "同路径内容不同时采用该云端来源的版本；本机独有项目保留不删。",
                "Differing items use the cloud version; local-only items are kept.",
              )
            : tr(
                "同路径内容不同时以本机版本覆盖远端；远端独有项目保留不删。",
                "Differing items overwrite the cloud with local content; remote-only items are kept.",
              )
        }
        confirmLabel={confirmForce?.mode === "force_download" ? tr("强制下载", "Force download") : tr("强制上传", "Force upload")}
        danger
        onCancel={() => setConfirmForce(null)}
        onConfirm={() => {
          const req = confirmForce;
          setConfirmForce(null);
          if (req) void runSync(req.config.id, req.mode);
        }}
      />

      {/* ----- 撤回确认 ----- */}
      <ConfirmDialog
        open={undoOpen}
        title={tr("撤回最近一次同步？", "Undo the latest sync?")}
        message={tr(
          "会恢复被覆盖的本机内容，并把该轮新建的文件移入回收站；没有可撤回快照时会失败。",
          "Restores overwritten local content and moves files created by that run into the trash; fails when no snapshot exists.",
        )}
        confirmLabel={undoing ? tr("正在撤回…", "Undoing…") : tr("撤回一次", "Undo once")}
        danger
        onCancel={() => setUndoOpen(false)}
        onConfirm={() => void undoLastSync()}
      />
    </div>
  );
}
