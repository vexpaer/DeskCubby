import {
  ArchiveRestore,
  CheckCircle2,
  DatabaseBackup,
  Download,
  FileJson,
  History,
  RefreshCw,
  RotateCcw,
  ShieldCheck,
  Upload,
  X,
} from "lucide-react";
import { useCallback, useEffect, useState } from "react";

import { invokeCommand, readableError } from "../lib/ipc";

interface BackupPreview {
  formatVersion: number;
  exportedAt: string;
  thoughtCount: number;
  categoryCount: number;
  favoriteCount: number;
  dateRecordCount: number;
  poemCount: number;
  readerProgressCount: number;
  preservedTopLevelKeys: string[];
}

interface BackupSelection {
  token?: string;
  importToken?: string;
  displayName?: string;
  fileName?: string;
  preview?: BackupPreview;
  formatVersion?: number;
  exportedAt?: string;
  thoughtCount?: number;
  categoryCount?: number;
  favoriteCount?: number;
  dateRecordCount?: number;
  poemCount?: number;
  readerProgressCount?: number;
  preservedTopLevelKeys?: string[];
}

interface ImportResult {
  importedAt: string;
  restorePointId?: string;
  restorePointLabel?: string;
  thoughtCount: number;
  categoryCount: number;
  dateRecordCount: number;
  poemCount: number;
  usageDevicesMerged: boolean;
}

interface BackupWriteResult {
  displayName?: string;
  fileName?: string;
  createdAt: string;
  size: number;
  sha256?: string;
  previousRotated?: boolean;
}

interface RestorePoint {
  id: string;
  label: string;
  createdAt: string;
  size: number;
}

type Language = "zh" | "en";

const MIN_SUPPORTED_BACKUP_VERSION = 1;
const MAX_SUPPORTED_BACKUP_VERSION = 28;

function isSupportedBackupVersion(version: number): boolean {
  return (
    Number.isInteger(version) &&
    version >= MIN_SUPPORTED_BACKUP_VERSION &&
    version <= MAX_SUPPORTED_BACKUP_VERSION
  );
}

function useDocumentLanguage(): Language {
  const read = () =>
    document.documentElement.lang.toLowerCase().startsWith("en") ? "en" : "zh";
  const [language, setLanguage] = useState<Language>(read);

  useEffect(() => {
    const observer = new MutationObserver(() => setLanguage(read()));
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["lang"] });
    return () => observer.disconnect();
  }, []);

  return language;
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MiB`;
}

function selectionPreview(selection: BackupSelection): BackupPreview {
  if (selection.preview) return selection.preview;
  return {
    formatVersion: selection.formatVersion ?? 0,
    exportedAt: selection.exportedAt ?? "",
    thoughtCount: selection.thoughtCount ?? 0,
    categoryCount: selection.categoryCount ?? 0,
    favoriteCount: selection.favoriteCount ?? 0,
    dateRecordCount: selection.dateRecordCount ?? 0,
    poemCount: selection.poemCount ?? 0,
    readerProgressCount: selection.readerProgressCount ?? 0,
    preservedTopLevelKeys: selection.preservedTopLevelKeys ?? [],
  };
}

export default function BackupPage() {
  const language = useDocumentLanguage();
  const tr = useCallback(
    (chinese: string, english: string) => (language === "zh" ? chinese : english),
    [language],
  );
  const errorLanguage = language === "en" ? "en" : "zh-CN";
  const [selection, setSelection] = useState<BackupSelection | null>(null);
  const [confirmReplace, setConfirmReplace] = useState(false);
  const [restorePoints, setRestorePoints] = useState<RestorePoint[]>([]);
  const [loadingRestorePoints, setLoadingRestorePoints] = useState(true);
  const [busy, setBusy] = useState<"choose" | "import" | "export" | "auto" | "restore" | null>(
    null,
  );
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const preview = selection ? selectionPreview(selection) : null;
  const canImportPreview = preview ? isSupportedBackupVersion(preview.formatVersion) : false;

  const loadRestorePoints = useCallback(async () => {
    setLoadingRestorePoints(true);
    try {
      setRestorePoints(await invokeCommand<RestorePoint[]>("list_restore_points"));
    } catch (reason) {
      setError(readableError(reason, errorLanguage));
    } finally {
      setLoadingRestorePoints(false);
    }
  }, [errorLanguage]);

  useEffect(() => {
    void loadRestorePoints();
  }, [loadRestorePoints]);

  useEffect(() => {
    if (!selection || busy === "import") return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setSelection(null);
        setConfirmReplace(false);
      }
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [busy, selection]);

  const chooseBackup = async () => {
    setBusy("choose");
    setError("");
    setNotice("");
    setConfirmReplace(false);
    try {
      const chosen = await invokeCommand<BackupSelection | null>("choose_and_preview_backup");
      if (chosen) setSelection(chosen);
    } catch (reason) {
      setError(readableError(reason, errorLanguage));
    } finally {
      setBusy(null);
    }
  };

  const importBackup = async () => {
    if (!selection || !confirmReplace) return;
    const token = selection.token ?? selection.importToken;
    if (!token) {
      setError(tr("预览凭据已失效，请重新选择文件。", "Preview expired; choose the file again."));
      return;
    }
    setBusy("import");
    setError("");
    setNotice("");
    try {
      const result = await invokeCommand<ImportResult>("import_backup", { token });
      setSelection(null);
      setConfirmReplace(false);
      setNotice(
        tr(
          result.usageDevicesMerged
            ? `导入完成：${result.thoughtCount} 条小巧思、${result.dateRecordCount} 条日期记录、${result.poemCount} 首诗词。`
            : `核心数据已导入：${result.thoughtCount} 条小巧思、${result.dateRecordCount} 条日期记录、${result.poemCount} 首诗词。手机使用时间缓存未更新；请稍后重新导入该备份。`,
          result.usageDevicesMerged
            ? `Import complete: ${result.thoughtCount} thoughts, ${result.dateRecordCount} dates and ${result.poemCount} poems.`
            : `Core data imported: ${result.thoughtCount} thoughts, ${result.dateRecordCount} dates and ${result.poemCount} poems. The phone-usage cache was not updated; import this backup again later.`,
        ),
      );
      await loadRestorePoints();
      window.dispatchEvent(new CustomEvent("deskcubby:data-restored"));
    } catch (reason) {
      setError(readableError(reason, errorLanguage));
    } finally {
      setBusy(null);
    }
  };

  const exportBackup = async () => {
    setBusy("export");
    setError("");
    setNotice("");
    try {
      const result = await invokeCommand<BackupWriteResult | null>("export_backup");
      if (result) {
        setNotice(
          tr(
            `已导出 ${result.displayName ?? result.fileName ?? "dc-backup.json"}（${formatBytes(result.size)}）。`,
            `Exported ${result.displayName ?? result.fileName ?? "dc-backup.json"} (${formatBytes(result.size)}).`,
          ),
        );
      }
    } catch (reason) {
      setError(readableError(reason, errorLanguage));
    } finally {
      setBusy(null);
    }
  };

  const runAutomaticBackup = async () => {
    setBusy("auto");
    setError("");
    setNotice("");
    try {
      const result = await invokeCommand<BackupWriteResult>("run_automatic_backup");
      setNotice(
        tr(
          `自动备份已写入 ${result.displayName ?? result.fileName ?? "dc.json"} 并通过回读校验。`,
          `Automatic backup ${result.displayName ?? result.fileName ?? "dc.json"} was written and verified.`,
        ),
      );
    } catch (reason) {
      setError(readableError(reason, errorLanguage));
    } finally {
      setBusy(null);
    }
  };

  const restorePoint = async (point: RestorePoint) => {
    if (
      !window.confirm(
        tr(
          `恢复“${point.label}”？当前核心数据会先建立新的恢复点。`,
          `Restore “${point.label}”? A new restore point will be created first.`,
        ),
      )
    ) {
      return;
    }
    setBusy("restore");
    setError("");
    try {
      await invokeCommand<void>("restore_restore_point", { id: point.id });
      setNotice(tr("恢复点已恢复。", "Restore point restored."));
      window.dispatchEvent(new CustomEvent("deskcubby:data-restored"));
      await loadRestorePoints();
    } catch (reason) {
      setError(readableError(reason, errorLanguage));
    } finally {
      setBusy(null);
    }
  };

  const locale = language === "zh" ? "zh-CN" : "en-US";

  return (
    <main className="page-shell backup-page" aria-labelledby="backup-title">
      <header className="page-header">
        <div>
          <p className="eyebrow">{tr("Android v28 双向兼容", "Bidirectional Android v28 compatibility")}</p>
          <h1 id="backup-title">{tr("设置与备份", "Settings & backup")}</h1>
        </div>
      </header>

      {(error || notice) && (
        <div className={error ? "status-banner error" : "status-banner success"} role="status">
          <span>{error || notice}</span>
          <button
            className="icon-button"
            type="button"
            aria-label={tr("关闭提示", "Dismiss")}
            onClick={() => {
              setError("");
              setNotice("");
            }}
          >
            <X size={16} />
          </button>
        </div>
      )}

      <section className="backup-action-grid">
        <article className="panel backup-action-card">
          <Upload size={24} aria-hidden="true" />
          <div>
            <h2>{tr("导入 Android 备份", "Import Android backup")}</h2>
            <p>
              {tr(
                "支持 Android v1–v28 JSON，最大 64 MiB；旧格式会安全升级为 v28。应用会先严格校验并展示统计，不会直接覆盖。",
                "Accepts Android v1–v28 JSON up to 64 MiB and safely upgrades older formats to v28. It is validated and previewed before anything changes.",
              )}
            </p>
          </div>
          <button
            className="button-primary"
            type="button"
            disabled={busy !== null}
            onClick={() => void chooseBackup()}
          >
            <FileJson size={17} />
            {busy === "choose" ? tr("正在校验…", "Validating…") : tr("选择并预览", "Choose & preview")}
          </button>
        </article>

        <article className="panel backup-action-card">
          <Download size={24} aria-hidden="true" />
          <div>
            <h2>{tr("手动导出", "Manual export")}</h2>
            <p>
              {tr(
                "生成 Android 可读的 dc-backup-日期.json；未实现模块与未知字段会从加密兼容影子合并回来。",
                "Creates an Android-readable dc-backup-date.json and merges preserved deferred or unknown fields.",
              )}
            </p>
          </div>
          <button
            className="button-primary"
            type="button"
            disabled={busy !== null}
            onClick={() => void exportBackup()}
          >
            <Download size={17} />
            {busy === "export" ? tr("正在导出…", "Exporting…") : tr("选择位置并导出", "Choose location & export")}
          </button>
        </article>

        <article className="panel backup-action-card">
          <DatabaseBackup size={24} aria-hidden="true" />
          <div>
            <h2>{tr("立即自动备份", "Run automatic backup")}</h2>
            <p>
              {tr(
                "沿用 pending/current/previous 轮换，写入后回读校验；需要先在设置中选择备份目录。",
                "Uses pending/current/previous rotation and verifies every write. A backup folder is required.",
              )}
            </p>
          </div>
          <button
            className="button-secondary"
            type="button"
            disabled={busy !== null}
            onClick={() => void runAutomaticBackup()}
          >
            <RefreshCw className={busy === "auto" ? "spin" : ""} size={17} />
            {busy === "auto" ? tr("备份中…", "Backing up…") : tr("立即运行", "Run now")}
          </button>
        </article>
      </section>

      <section className="panel restore-points" aria-labelledby="restore-points-title">
        <div className="panel-heading">
          <div>
            <h2 id="restore-points-title">{tr("导入前恢复点", "Pre-import restore points")}</h2>
            <p>
              {tr(
                "每次导入前，应用私有目录都会保存当前核心数据快照。",
                "A private snapshot of current core data is created before every import.",
              )}
            </p>
          </div>
          <button
            className="icon-button"
            type="button"
            title={tr("刷新恢复点", "Refresh restore points")}
            disabled={loadingRestorePoints || busy !== null}
            onClick={() => void loadRestorePoints()}
          >
            <RefreshCw className={loadingRestorePoints ? "spin" : ""} size={17} />
          </button>
        </div>
        {loadingRestorePoints ? (
          <div className="empty-state" role="status">
            {tr("正在读取恢复点…", "Loading restore points…")}
          </div>
        ) : restorePoints.length === 0 ? (
          <div className="empty-state">
            <History size={25} aria-hidden="true" />
            <p>{tr("还没有恢复点。", "No restore points yet.")}</p>
          </div>
        ) : (
          <ul className="restore-point-list">
            {restorePoints.map((point) => (
              <li key={point.id}>
                <div>
                  <strong>{point.label}</strong>
                  <span>
                    {new Intl.DateTimeFormat(locale, {
                      dateStyle: "medium",
                      timeStyle: "short",
                    }).format(new Date(point.createdAt))}
                    {" · "}
                    {formatBytes(point.size)}
                  </span>
                </div>
                <button
                  className="button-secondary"
                  type="button"
                  disabled={busy !== null}
                  onClick={() => void restorePoint(point)}
                >
                  <ArchiveRestore size={16} />
                  {tr("恢复", "Restore")}
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <aside className="panel backup-safety-note">
        <ShieldCheck size={22} aria-hidden="true" />
        <div>
          <h2>{tr("导入边界", "Import boundary")}</h2>
          <p>
            {tr(
              "导入会在一个数据库事务中替换 Windows 管理的设置、小巧思、分类、日期记录和诗词；失败会完整回滚。日记正文与媒体目录不会被 JSON 导入覆盖。",
              "Import replaces Windows-managed settings, thoughts, categories, dates and poems in one transaction. Failures roll back completely; diary and media files are not overwritten.",
            )}
          </p>
        </div>
      </aside>

      {selection && preview && (
        <div className="modal-backdrop" role="presentation">
          <section
            aria-labelledby="backup-preview-title"
            aria-modal="true"
            className="modal-card backup-preview"
            role="dialog"
          >
            <div className="panel-heading">
              <div>
                <p className="eyebrow">{selection.displayName ?? selection.fileName ?? "dc.json"}</p>
                <h2 id="backup-preview-title">{tr("导入预览", "Import preview")}</h2>
              </div>
              <button
                className="icon-button"
                type="button"
                aria-label={tr("关闭", "Close")}
                disabled={busy === "import"}
                onClick={() => {
                  setSelection(null);
                  setConfirmReplace(false);
                }}
              >
                <X size={18} />
              </button>
            </div>

            <div className="preview-version">
              {canImportPreview ? (
                <CheckCircle2 size={20} aria-hidden="true" />
              ) : (
                <X size={20} aria-hidden="true" />
              )}
              <div>
                <strong>
                  {tr(`备份格式 v${preview.formatVersion}`, `Backup format v${preview.formatVersion}`)}
                </strong>
                <span>
                  {preview.exportedAt
                    ? new Intl.DateTimeFormat(locale, {
                        dateStyle: "medium",
                        timeStyle: "short",
                      }).format(new Date(preview.exportedAt))
                    : tr("未提供导出时间", "No export timestamp")}
                </span>
              </div>
            </div>

            <dl className="backup-stat-grid">
              <div>
                <dt>{tr("小巧思", "Thoughts")}</dt>
                <dd>{preview.thoughtCount}</dd>
              </div>
              <div>
                <dt>{tr("分类", "Categories")}</dt>
                <dd>{preview.categoryCount}</dd>
              </div>
              <div>
                <dt>{tr("日期记录", "Date records")}</dt>
                <dd>{preview.dateRecordCount}</dd>
              </div>
              <div>
                <dt>{tr("诗词", "Poems")}</dt>
                <dd>{preview.poemCount}</dd>
              </div>
              <div>
                <dt>{tr("浏览收藏（仅保留）", "Browser favorites (preserved)")}</dt>
                <dd>{preview.favoriteCount}</dd>
              </div>
              <div>
                <dt>{tr("阅读进度", "Reader progress")}</dt>
                <dd>{preview.readerProgressCount}</dd>
              </div>
            </dl>

            {preview.preservedTopLevelKeys.length > 0 && (
              <div className="preserved-fields">
                <strong>{tr("将原样保留的额外字段", "Additional fields preserved unchanged")}</strong>
                <code>{preview.preservedTopLevelKeys.join(", ")}</code>
              </div>
            )}

            <label className="check-control confirmation-control">
              <input
                type="checkbox"
                checked={confirmReplace}
                disabled={!canImportPreview || busy === "import"}
                onChange={(event) => setConfirmReplace(event.target.checked)}
              />
              {tr(
                "我确认用此备份替换 Windows 核心结构化数据",
                "I understand this replaces Windows core structured data",
              )}
            </label>

            <div className="modal-actions">
              <button
                className="button-secondary"
                type="button"
                disabled={busy === "import"}
                onClick={() => {
                  setSelection(null);
                  setConfirmReplace(false);
                }}
              >
                {tr("取消", "Cancel")}
              </button>
              <button
                className="button-primary"
                type="button"
                disabled={!confirmReplace || !canImportPreview || busy === "import"}
                onClick={() => void importBackup()}
              >
                <RotateCcw size={17} />
                {busy === "import" ? tr("正在导入…", "Importing…") : tr("确认导入", "Import backup")}
              </button>
            </div>
          </section>
        </div>
      )}
    </main>
  );
}
