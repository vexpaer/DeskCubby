/**
 * Diary trash page (/diary/trash) — mirrors Android 日记回收站:
 * soft-deleted diaries with restore and permanent delete (both confirmed).
 */
import React, { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Inbox, RefreshCw, RotateCcw, Trash2 } from "lucide-react";
import { apiGet, apiSend } from "../../api/client";
import { tr } from "../../i18n/tr";
import {
  ConfirmDialog, EmptyState, ErrorText, Snackbar, Spinner, TopBar, useSnackbar,
} from "../../components/ui";

interface DiaryTrashItem {
  uri: string;
  originalName: string;
  deletedAt: number;
  /** Optional backend-provided trash-file key; preferred for name params. */
  name?: string;
  trashName?: string;
  fileName?: string;
}

function trashKey(item: DiaryTrashItem): string {
  return item.name ?? item.trashName ?? item.fileName ?? item.originalName;
}

function formatDateTime(epochMs: number): string {
  if (!epochMs) return "";
  const d = new Date(epochMs);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

export default function DiaryTrashPage() {
  const navigate = useNavigate();
  const [snack, showSnack] = useSnackbar();
  const [items, setItems] = useState<DiaryTrashItem[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [restoreItem, setRestoreItem] = useState<DiaryTrashItem | null>(null);
  const [restoring, setRestoring] = useState(false);
  const [deleteItem, setDeleteItem] = useState<DiaryTrashItem | null>(null);
  const [deleting, setDeleting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const list = await apiGet<DiaryTrashItem[]>("/api/diary/trash");
      setItems(Array.isArray(list) ? list : []);
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const performRestore = async (item: DiaryTrashItem) => {
    if (restoring) return;
    setRestoring(true);
    try {
      await apiSend("/api/diary/trash/restore", "POST", { name: trashKey(item) });
      showSnack(tr("日记已恢复", "Diary restored"));
      setRestoreItem(null);
      await load();
    } catch (e) {
      showSnack(e instanceof Error ? e.message : tr("恢复失败", "Restore failed"));
    } finally {
      setRestoring(false);
    }
  };

  const performPermanentDelete = async (item: DiaryTrashItem) => {
    if (deleting) return;
    setDeleting(true);
    try {
      await apiSend(`/api/diary/trash/item?name=${encodeURIComponent(trashKey(item))}`, "DELETE");
      showSnack(tr("已永久删除", "Deleted forever"));
      setDeleteItem(null);
      await load();
    } catch (e) {
      showSnack(e instanceof Error ? e.message : tr("删除失败", "Delete failed"));
    } finally {
      setDeleting(false);
    }
  };

  return (
    <div className="diary-trash-page">
      <TopBar
        title={tr("日记回收站", "Diary trash")}
        back
        onBack={() => navigate("/diary")}
        actions={
          <button className="dc-icon-btn" aria-label={tr("刷新", "Refresh")} onClick={() => void load()}>
            <RefreshCw size={20} />
          </button>
        }
      />

      {loading && items === null && <Spinner />}
      {!loading && !!error && items === null && (
        <EmptyState
          icon={<Inbox size={44} />}
          title={tr("无法读取日记回收站", "Could not load the diary trash")}
          hint={
            <button className="dc-btn dc-btn-tonal" style={{ marginTop: 10 }} onClick={() => void load()}>
              {tr("重试", "Retry")}
            </button>
          }
        />
      )}
      {!loading && items !== null && items.length === 0 && (
        <EmptyState icon={<Inbox size={44} />} title={tr("回收站为空", "Trash is empty")} />
      )}
      <ErrorText error={error && items !== null ? error : null} />

      <div className="dc-col" style={{ gap: 8 }}>
        {(items ?? []).map((item) => (
          <div
            key={item.uri || trashKey(item)}
            className="dc-row dc-card"
            style={{ padding: "12px 14px", gap: 10 }}
          >
            <Trash2 size={18} className="dc-muted" style={{ flexShrink: 0 }} />
            <div className="dc-grow" style={{ minWidth: 0 }}>
              <div style={{ fontWeight: 500, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                {item.originalName}
              </div>
              {item.deletedAt ? (
                <div className="dc-muted" style={{ fontSize: "0.8em", marginTop: 2 }}>
                  {tr("删除于", "Deleted at")} {formatDateTime(item.deletedAt)}
                </div>
              ) : null}
            </div>
            <button
              className="dc-btn"
              disabled={restoring}
              onClick={() => setRestoreItem(item)}
            >
              <RotateCcw size={16} /> {tr("恢复", "Restore")}
            </button>
            <button
              className="dc-icon-btn"
              aria-label={tr("永久删除", "Delete forever")}
              style={{ color: "var(--dc-error)" }}
              onClick={() => setDeleteItem(item)}
            >
              <Trash2 size={18} />
            </button>
          </div>
        ))}
      </div>

      <ConfirmDialog
        open={restoreItem !== null}
        title={tr("恢复日记？", "Restore diary?")}
        message={restoreItem
          ? tr(
            `将把 ${restoreItem.originalName} 还原回日记目录；若已有同名文件会自动改名。`,
            `${restoreItem.originalName} will be restored into the diary folder; it is renamed automatically on name clashes.`,
          )
          : ""}
        confirmLabel={restoring ? "…" : tr("恢复", "Restore")}
        onConfirm={() => restoreItem && void performRestore(restoreItem)}
        onCancel={() => setRestoreItem(null)}
      />

      <ConfirmDialog
        open={deleteItem !== null}
        danger
        title={tr("永久删除？", "Delete forever?")}
        message={deleteItem ? tr(`${deleteItem.originalName} 将无法恢复。`, `${deleteItem.originalName} cannot be recovered.`) : ""}
        confirmLabel={deleting ? "…" : tr("永久删除", "Delete forever")}
        onConfirm={() => deleteItem && void performPermanentDelete(deleteItem)}
        onCancel={() => setDeleteItem(null)}
      />

      <Snackbar message={snack} />
    </div>
  );
}
