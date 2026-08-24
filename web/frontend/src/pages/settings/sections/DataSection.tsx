/**
 * 设置 → 应用数据 (README_for_ai §17.13)：存储占用、备份与恢复、自动备份、访问密码。
 *
 * This section mixes draft-free server operations with immediate endpoints:
 * - GET /api/settings/data-usage renders the storage rows (read-only).
 * - 导出备份 opens /api/backup/export (v34 sanitized download).
 * - 导入备份 uploads the file for a preview first; committing requires the
 *   preview token and replaces core data in one transaction on the server.
 * - 自动备份 config lives outside AppSettings (GET/PUT /api/backup/auto).
 * - 访问密码 talks to /api/auth/*; passwords are only sent, never stored here.
 */
import React, { useEffect, useRef, useState } from "react";
import { Cloud, ChevronRight } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { apiGet, apiSend, apiUpload } from "../../../api/client";
import { useSettings } from "../../../stores/settings";
import { tr } from "../../../i18n/tr";
import { ConfirmDialog, ErrorText, Modal, Spinner } from "../../../components/ui";
import { SectionCard, SliderRow, TextField, Toggle } from "../SettingsPage";
import type { SettingsSectionProps } from "../SettingsPage";

// ---------------------------------------------------------------------------
// Wire shapes
// ---------------------------------------------------------------------------

interface DataUsage {
  diaryMB: number;
  mediaMB: number;
  notesMB: number;
  booksMB: number;
  backupsMB: number;
  privateMB: number;
}

const USAGE_ROWS: { key: keyof DataUsage; zh: string; en: string }[] = [
  { key: "diaryMB", zh: "日记", en: "Diaries" },
  { key: "mediaMB", zh: "媒体", en: "Media" },
  { key: "notesMB", zh: "笔记", en: "Notes" },
  { key: "booksMB", zh: "书籍", en: "Books" },
  { key: "backupsMB", zh: "备份", en: "Backups" },
  { key: "privateMB", zh: "其他", en: "Other" },
];

/** GET /api/backup/import preview: per-section counters + optional warnings. */
interface ImportPreview {
  token?: string;
  version?: number;
  exportedAt?: string | number;
  warnings?: string[];
  thoughtCount?: number;
  categoryCount?: number;
  favoriteCount?: number;
  dateRecordCount?: number;
  poetryCategoryCount?: number;
  poemCount?: number;
  vaultItemCount?: number;
  gameStateCount?: number;
  gameStatisticCount?: number;
  usageDeviceCount?: number;
  usageDayCount?: number;
  readerProgressCount?: number;
  agentConversationCount?: number;
}

const PREVIEW_COUNTS: { key: keyof ImportPreview; zh: string; en: string }[] = [
  { key: "thoughtCount", zh: "小巧思", en: "Thoughts" },
  { key: "categoryCount", zh: "小巧思分类", en: "Thought categories" },
  { key: "favoriteCount", zh: "收藏", en: "Favorites" },
  { key: "dateRecordCount", zh: "日期记录", en: "Date records" },
  { key: "poetryCategoryCount", zh: "诗词分类", en: "Poetry categories" },
  { key: "poemCount", zh: "诗词", en: "Poems" },
  { key: "vaultItemCount", zh: "收藏夹条目", en: "Vault items" },
  { key: "gameStateCount", zh: "游戏存档", en: "Game saves" },
  { key: "gameStatisticCount", zh: "游戏特色统计", en: "Game statistics" },
  { key: "usageDeviceCount", zh: "使用时间设备", en: "Usage devices" },
  { key: "usageDayCount", zh: "使用时间天数", en: "Usage days" },
  { key: "readerProgressCount", zh: "阅读进度", en: "Reader progress" },
  { key: "agentConversationCount", zh: "Agent 对话", en: "Agent chats" },
];

interface AutoBackupConfig {
  enabled: boolean;
  dirName: string;
  keepCount: number;
}

interface AuthStatusInfo {
  enabled: boolean;
  authenticated?: boolean;
}

function fmtMb(v: number | undefined): string {
  return `${(typeof v === "number" && Number.isFinite(v) ? v : 0).toFixed(2)} MB`;
}

export default function DataSection({ snackbar }: SettingsSectionProps) {
  const navigate = useNavigate();
  const refresh = useSettings((s) => s.refresh);

  // ----- 存储占用 -----------------------------------------------------------
  const [usage, setUsage] = useState<DataUsage | null>(null);
  const [usageError, setUsageError] = useState<unknown>(null);

  const loadUsage = async () => {
    setUsageError(null);
    try {
      setUsage(await apiGet<DataUsage>("/api/settings/data-usage"));
    } catch (e) {
      setUsageError(e);
    }
  };
  useEffect(() => {
    void loadUsage();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ----- 备份与恢复 ---------------------------------------------------------
  const fileRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [committing, setCommitting] = useState(false);
  const [opError, setOpError] = useState<unknown>(null);

  const onImportFile = async (file: File | undefined) => {
    if (!file) return;
    setUploading(true);
    setOpError(null);
    try {
      const res = await apiUpload<ImportPreview>("/api/backup/import", file);
      if (!res?.token) throw new Error(tr("预览失败", "Preview failed"));
      setPreview(res);
    } catch (e) {
      setOpError(e);
    } finally {
      setUploading(false);
      if (fileRef.current) fileRef.current.value = "";
    }
  };

  const commitImport = async () => {
    const token = preview?.token;
    if (!token) return;
    setCommitting(true);
    setOpError(null);
    try {
      await apiSend("/api/backup/import/commit", "POST", { token });
      setPreview(null);
      snackbar(tr("已导入", "Imported"));
      await refresh();
      void loadUsage();
    } catch (e) {
      snackbar(tr("导入失败", "Import failed"));
      setOpError(e);
    } finally {
      setCommitting(false);
    }
  };

  // ----- 自动备份 -----------------------------------------------------------
  const [auto, setAuto] = useState<AutoBackupConfig | null>(null);
  const [autoLoading, setAutoLoading] = useState(true);
  const [autoSaving, setAutoSaving] = useState(false);
  const [runningBackup, setRunningBackup] = useState(false);
  const [autoError, setAutoError] = useState<unknown>(null);

  const loadAuto = async () => {
    setAutoLoading(true);
    setAutoError(null);
    try {
      const cfg = await apiGet<AutoBackupConfig>("/api/backup/auto");
      setAuto({
        enabled: !!cfg.enabled,
        dirName: String(cfg.dirName ?? "auto"),
        keepCount: Number(cfg.keepCount) || 7,
      });
    } catch (e) {
      setAutoError(e);
    } finally {
      setAutoLoading(false);
    }
  };
  useEffect(() => {
    void loadAuto();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const saveAuto = async () => {
    if (!auto) return;
    setAutoSaving(true);
    setAutoError(null);
    try {
      const saved = await apiSend<AutoBackupConfig>("/api/backup/auto", "PUT", {
        enabled: auto.enabled,
        dirName: auto.dirName.trim() || "auto",
        keepCount: Math.round(auto.keepCount),
      });
      setAuto({
        enabled: !!saved.enabled,
        dirName: String(saved.dirName ?? "auto"),
        keepCount: Number(saved.keepCount) || 7,
      });
      snackbar(tr("已保存", "Saved"));
    } catch (e) {
      setAutoError(e);
    } finally {
      setAutoSaving(false);
    }
  };

  const runBackupNow = async () => {
    setRunningBackup(true);
    setAutoError(null);
    try {
      await apiSend("/api/backup/auto/run", "POST");
      snackbar(tr("已备份", "Backup created"));
      void loadUsage();
    } catch (e) {
      setAutoError(e);
    } finally {
      setRunningBackup(false);
    }
  };

  // ----- 访问密码 -----------------------------------------------------------
  const [auth, setAuth] = useState<AuthStatusInfo | null>(null);
  const [authError, setAuthError] = useState<unknown>(null);
  const [authBusy, setAuthBusy] = useState(false);
  const [newPw1, setNewPw1] = useState("");
  const [newPw2, setNewPw2] = useState("");
  const [oldPw, setOldPw] = useState("");
  const [changeNewPw, setChangeNewPw] = useState("");
  const [disableOpen, setDisableOpen] = useState(false);
  const [disablePw, setDisablePw] = useState("");

  const loadAuth = async () => {
    setAuthError(null);
    try {
      setAuth(await apiGet<AuthStatusInfo>("/api/auth/status"));
    } catch (e) {
      setAuthError(e);
    }
  };
  useEffect(() => {
    void loadAuth();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const authReady = auth?.enabled === true;

  const enablePassword = async () => {
    setAuthBusy(true);
    setAuthError(null);
    try {
      await apiSend("/api/auth/set-password", "POST", { password: newPw1 });
      snackbar(tr("已开启访问密码", "Access password enabled"));
      setNewPw1("");
      setNewPw2("");
      await loadAuth();
    } catch (e) {
      setAuthError(e);
    } finally {
      setAuthBusy(false);
    }
  };

  const changePassword = async () => {
    setAuthBusy(true);
    setAuthError(null);
    try {
      await apiSend("/api/auth/change-password", "POST", { password: oldPw, newPassword: changeNewPw });
      snackbar(tr("已修改密码", "Password changed"));
      setOldPw("");
      setChangeNewPw("");
    } catch (e) {
      setAuthError(e);
    } finally {
      setAuthBusy(false);
    }
  };

  const disablePassword = async () => {
    setAuthBusy(true);
    setAuthError(null);
    try {
      await apiSend("/api/auth/disable", "POST", { password: disablePw });
      snackbar(tr("已关闭密码保护", "Password protection disabled"));
      setDisableOpen(false);
      setDisablePw("");
      await loadAuth();
    } catch (e) {
      setAuthError(e);
      setDisableOpen(false);
      setDisablePw("");
    } finally {
      setAuthBusy(false);
    }
  };

  const logout = async () => {
    setAuthBusy(true);
    setAuthError(null);
    try {
      await apiSend("/api/auth/logout", "POST");
    } catch {
      /* even a failed logout must not keep the user stuck here */
    }
    location.href = "/login";
  };

  const enableValid = newPw1.length >= 4 && newPw1 === newPw2;

  return (
    <div className="dc-col" style={{ gap: 12 }}>
      <button className="dc-card dc-row" style={{ width: "100%", padding: "12px 14px", border: "none", textAlign: "left", gap: 12 }}
        onClick={() => navigate("/settings?section=cloudsync")}>
        <span className="dc-center" style={{ width: 38, height: 38, borderRadius: 12, background: "var(--dc-surface-container-high)" }}>
          <Cloud size={19} />
        </span>
        <span className="dc-grow dc-col" style={{ gap: 2 }}>
          <span style={{ fontWeight: 600 }}>{tr("云端同步", "Cloud sync")}</span>
          <span className="dc-muted" style={{ fontSize: "0.84em" }}>
            {tr("WebDAV、S3、同步内容与冲突处理", "WebDAV, S3, synced content, and conflicts")}
          </span>
        </span>
        <ChevronRight size={18} className="dc-muted" />
      </button>
      <SectionCard
        title={tr("存储占用", "Storage usage")}
        description={tr(
          "统计服务器数据目录中各类内容的磁盘占用。",
          "Disk usage of each content type inside the server data directory.",
        )}
      >
        {!usage && !usageError && <Spinner size={20} />}
        <ErrorText error={usageError} />
        {USAGE_ROWS.map((row) => (
          <div key={row.key} className="dc-row" style={{ justifyContent: "space-between", gap: 12 }}>
            <span>{tr(row.zh, row.en)}</span>
            <span className="dc-muted" style={{ fontVariantNumeric: "tabular-nums" }}>
              {fmtMb(usage?.[row.key])}
            </span>
          </div>
        ))}
      </SectionCard>

      <SectionCard
        title={tr("备份与恢复", "Backup & restore")}
        description={tr(
          "导出为不加密的 JSON 备份；导入前先预览，确认后才替换核心数据。",
          "Exports an unencrypted JSON backup; imports show a preview before replacing core data.",
        )}
      >
        <div className="dc-row dc-wrap">
          <button
            className="dc-btn dc-btn-tonal"
            onClick={() => window.open("/api/backup/export", "_blank")}
          >
            {tr("导出备份", "Export backup")}
          </button>
          <button className="dc-btn dc-btn-tonal" disabled={uploading} onClick={() => fileRef.current?.click()}>
            {uploading ? tr("正在读取…", "Reading…") : tr("导入备份", "Import backup")}
          </button>
          {uploading && <Spinner size={18} />}
        </div>
        <input
          ref={fileRef} type="file" accept=".json,application/json" hidden
          onChange={(e) => void onImportFile(e.target.files?.[0])}
        />
        <ErrorText error={opError} />
      </SectionCard>

      <SectionCard
        title={tr("自动备份", "Automatic backups")}
        description={tr(
          "按设置定期把 v34 JSON 备份写入服务器数据目录并按数量清理旧文件。",
          "Periodically writes timestamped v34 backups into the server data directory and prunes old files.",
        )}
      >
        {autoLoading && !auto && <Spinner size={20} />}
        <ErrorText error={autoError} />
        {auto && (
          <>
            <Toggle
              checked={auto.enabled}
              onChange={(v) => setAuto({ ...auto, enabled: v })}
              label={<span>{tr("开启自动备份", "Enable automatic backups")}</span>}
            />
            <TextField
              label={tr("备份目录名", "Backup folder name")}
              value={auto.dirName}
              maxLength={120}
              onChange={(v) => setAuto({ ...auto, dirName: v })}
            />
            <SliderRow
              label={tr("保留份数", "Kept copies")}
              value={auto.keepCount}
              min={1} max={100} step={1}
              format={(v) => `${v}`}
              onChange={(v) => setAuto({ ...auto, keepCount: v })}
            />
            <div className="dc-row dc-wrap">
              <button className="dc-btn dc-btn-filled" disabled={autoSaving} onClick={() => void saveAuto()}>
                {autoSaving ? tr("保存中…", "Saving…") : tr("保存", "Save")}
              </button>
              <button className="dc-btn" disabled={runningBackup} onClick={() => void runBackupNow()}>
                {runningBackup ? tr("正在备份…", "Backing up…") : tr("立即备份", "Back up now")}
              </button>
              {(autoSaving || runningBackup) && <Spinner size={18} />}
            </div>
          </>
        )}
      </SectionCard>

      <SectionCard
        title={tr("访问密码", "Access password")}
        description={tr(
          "开启后所有页面与接口均需登录。",
          "When enabled, every page and API call requires sign-in.",
        )}
      >
        {!auth && <Spinner size={20} />}
        <ErrorText error={authError} />
        {auth && !authReady && (
          <>
            <label className="dc-col" style={{ gap: 4 }}>
              <span style={{ fontSize: "0.9em" }}>{tr("设置访问密码", "Set access password")}</span>
              <input
                className="dc-input" type="password" autoComplete="new-password"
                value={newPw1} maxLength={128}
                placeholder={tr("至少 4 个字符", "At least 4 characters")}
                onChange={(e) => setNewPw1(e.target.value)}
              />
            </label>
            <label className="dc-col" style={{ gap: 4 }}>
              <span style={{ fontSize: "0.9em" }}>{tr("确认访问密码", "Confirm access password")}</span>
              <input
                className="dc-input" type="password" autoComplete="new-password"
                value={newPw2} maxLength={128}
                onChange={(e) => setNewPw2(e.target.value)}
              />
            </label>
            {newPw2.length > 0 && newPw1 !== newPw2 && (
              <div style={{ color: "var(--dc-error)", fontSize: "0.85em" }}>
                {tr("两次输入的密码不一致。", "The two passwords do not match.")}
              </div>
            )}
            <div className="dc-row">
              <button className="dc-btn dc-btn-filled" disabled={!enableValid || authBusy} onClick={() => void enablePassword()}>
                {tr("开启访问密码", "Enable access password")}
              </button>
              {authBusy && <Spinner size={18} />}
            </div>
          </>
        )}
        {authReady && (
          <>
            <div className="dc-col" style={{ gap: 8 }}>
              <span style={{ fontSize: "0.9em", fontWeight: 600 }}>{tr("修改密码", "Change password")}</span>
              <input
                className="dc-input" type="password" autoComplete="current-password"
                value={oldPw} maxLength={128}
                placeholder={tr("当前密码", "Current password")}
                aria-label={tr("当前密码", "Current password")}
                onChange={(e) => setOldPw(e.target.value)}
              />
              <input
                className="dc-input" type="password" autoComplete="new-password"
                value={changeNewPw} maxLength={128}
                placeholder={tr("新密码（至少 4 个字符）", "New password (at least 4 characters)")}
                aria-label={tr("新密码", "New password")}
                onChange={(e) => setChangeNewPw(e.target.value)}
              />
              <div className="dc-row">
                <button
                  className="dc-btn dc-btn-tonal"
                  disabled={!oldPw || changeNewPw.length < 4 || authBusy}
                  onClick={() => void changePassword()}
                >
                  {tr("修改密码", "Change password")}
                </button>
              </div>
            </div>
            <div className="dc-row dc-wrap">
              <button className="dc-btn" disabled={authBusy} onClick={() => void logout()}>
                {tr("退出登录", "Sign out")}
              </button>
              <button className="dc-btn dc-btn-danger" disabled={authBusy} onClick={() => setDisableOpen(true)}>
                {tr("关闭密码保护", "Disable password protection")}
              </button>
            </div>
          </>
        )}
        <div className="dc-muted" style={{ fontSize: "0.84em" }}>
          {tr(
            "密码仅以 PBKDF2 哈希保存于服务器；开启后所有页面与接口均需登录。",
            "The password is stored on the server as a PBKDF2 hash only; when enabled, every page and API call requires sign-in.",
          )}
        </div>
        <div className="dc-muted" style={{ fontSize: "0.84em" }}>
          {tr(
            "公网部署建议同时通过 Caddy/Nginx 反向代理启用 HTTPS。",
            "For public deployments, also enable HTTPS through a Caddy/Nginx reverse proxy.",
          )}
        </div>
      </SectionCard>

      <Modal open={preview !== null} onClose={() => (committing ? undefined : setPreview(null))} title={tr("导入预览", "Import preview")} width={480}>
        {preview && (
          <div className="dc-col" style={{ gap: 10 }}>
            <div className="dc-row" style={{ justifyContent: "space-between" }}>
              <span>{tr("备份版本", "Backup version")}</span>
              <span className="dc-muted">v{preview.version ?? "?"}</span>
            </div>
            {PREVIEW_COUNTS.map((row) => {
              const value = preview[row.key];
              if (typeof value !== "number" || value <= 0) return null;
              return (
                <div key={String(row.key)} className="dc-row" style={{ justifyContent: "space-between" }}>
                  <span>{tr(row.zh, row.en)}</span>
                  <span className="dc-muted" style={{ fontVariantNumeric: "tabular-nums" }}>{value}</span>
                </div>
              );
            })}
            {Array.isArray(preview.warnings) && preview.warnings.length > 0 && (
              <div className="dc-col" style={{ gap: 4, color: "var(--dc-error)", fontSize: "0.86em" }}>
                {preview.warnings.map((w, i) => <div key={i}>{String(w)}</div>)}
              </div>
            )}
            <div className="dc-muted" style={{ fontSize: "0.85em" }}>
              {tr(
                "确认后将在单个事务中替换核心数据；失败会自动回滚并保留原有内容。",
                "Confirming replaces core data in a single transaction; failures roll back and keep existing content.",
              )}
            </div>
            <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 6 }}>
              <button className="dc-btn" disabled={committing} onClick={() => setPreview(null)}>{tr("取消", "Cancel")}</button>
              <button className="dc-btn dc-btn-filled" disabled={committing} onClick={() => void commitImport()}>
                {committing ? tr("正在导入…", "Importing…") : tr("确认导入", "Confirm import")}
              </button>
            </div>
          </div>
        )}
      </Modal>

      <ConfirmDialog
        open={disableOpen}
        title={tr("关闭密码保护？", "Disable password protection?")}
        message={tr(
          "关闭后任何能访问此地址的人都可以打开你的数据。",
          "Once off, anyone who can reach this address can open your data.",
        )}
        confirmLabel={authBusy ? tr("处理中…", "Working…") : tr("关闭密码保护", "Disable")}
        danger
        onConfirm={() => void disablePassword()}
        onCancel={() => { setDisableOpen(false); setDisablePw(""); }}
      >
        <input
          className="dc-input" type="password" autoFocus autoComplete="current-password"
          value={disablePw} maxLength={128}
          placeholder={tr("输入当前密码以确认", "Enter the current password to confirm")}
          aria-label={tr("输入当前密码以确认", "Enter the current password to confirm")}
          onChange={(e) => setDisablePw(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter" && disablePw) void disablePassword(); }}
        />
      </ConfirmDialog>
    </div>
  );
}
