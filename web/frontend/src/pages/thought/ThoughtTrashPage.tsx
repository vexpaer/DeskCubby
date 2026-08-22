/**
 * 小巧思回收站 (/thought/trash) — port of Android ThoughtTrashScreen.
 * Lists soft-deleted thoughts with restore and permanent delete (with confirm).
 */
import React, { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Eraser as IconDeleteSweep, RotateCcw as IconRestore, Trash2 as IconDeleteForever } from "lucide-react";
import { apiGet, apiSend } from "../../api/client";
import { tr } from "../../i18n/tr";
import { ConfirmDialog, EmptyState, ErrorText, Spinner, TopBar, useSnackbar, Snackbar } from "../../components/ui";

interface FlashThought {
  id: number;
  content: string;
  createdAt: number;
  updatedAt: number;
  deletedAt: number | null;
}

function arrayOf<T>(v: unknown): T[] {
  if (Array.isArray(v)) return v as T[];
  if (v && typeof v === "object") {
    const obj = v as Record<string, unknown>;
    for (const key of ["items", "records", "data", "results"]) {
      if (Array.isArray(obj[key])) return obj[key] as T[];
    }
  }
  return [];
}

export default function ThoughtTrashPage() {
  const navigate = useNavigate();
  const [snack, showSnack] = useSnackbar();
  const [items, setItems] = useState<FlashThought[] | null>(null);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [deleting, setDeleting] = useState<FlashThought | null>(null);

  const reload = useCallback(async () => {
    try {
      const data = await apiGet<unknown>("/api/thoughts?trash=1");
      setItems(arrayOf<FlashThought>(data));
      setLoadError(null);
    } catch (e) {
      setLoadError(e);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const restore = async (item: FlashThought) => {
    try {
      await apiSend(`/api/thoughts/${item.id}/restore`, "POST");
      await reload();
    } catch (e) {
      showSnack(tr("操作失败：", "Operation failed: ") + (e instanceof Error ? e.message : String(e)));
    }
  };

  return (
    <div>
      <TopBar
        back
        onBack={() => navigate("/thought")}
        title={tr("小巧思回收站", "Thought trash")}
      />
      {loadError != null && (
        <div className="dc-row">
          <ErrorText error={loadError} />
          <button className="dc-btn dc-btn-tonal" onClick={() => { setItems(null); void reload(); }}>
            {tr("重试", "Retry")}
          </button>
        </div>
      )}
      {items == null && loadError == null && <Spinner />}
      {items != null && items.length === 0 && (
        <EmptyState
          icon={<IconDeleteSweep size={40} />}
          title={tr("回收站为空", "Trash is empty")}
          hint={tr("删除的小巧思会暂时保存在这里。", "Deleted thoughts will be kept here temporarily.")}
        />
      )}
      <div className="dc-col" style={{ padding: 12, gap: 8 }}>
        {(items ?? []).map((item) => (
          <div key={item.id} className="dc-card" style={{ display: "flex", alignItems: "center", gap: 8, padding: 12 }}>
            <span
              className="dc-grow"
              style={{
                overflow: "hidden", display: "-webkit-box", WebkitLineClamp: 2,
                WebkitBoxOrient: "vertical", wordBreak: "break-word",
              }}
            >
              {item.content}
            </span>
            <button className="dc-icon-btn" aria-label={tr("恢复", "Restore")} title={tr("恢复", "Restore")} onClick={() => void restore(item)}>
              <IconRestore size={18} />
            </button>
            <button
              className="dc-icon-btn"
              aria-label={tr("永久删除", "Delete forever")}
              title={tr("永久删除", "Delete forever")}
              style={{ color: "var(--dc-error)" }}
              onClick={() => setDeleting(item)}
            >
              <IconDeleteForever size={18} />
            </button>
          </div>
        ))}
      </div>

      <ConfirmDialog
        open={deleting != null}
        title={tr("永久删除？", "Delete forever?")}
        message={tr("此操作无法恢复。", "This cannot be undone.")}
        confirmLabel={tr("永久删除", "Delete forever")}
        cancelLabel={tr("取消", "Cancel")}
        danger
        onCancel={() => setDeleting(null)}
        onConfirm={() => {
          const item = deleting;
          setDeleting(null);
          if (!item) return;
          void (async () => {
            try {
              await apiSend(`/api/thoughts/${item.id}/permanent`, "DELETE");
              await reload();
            } catch (e) {
              showSnack(tr("操作失败：", "Operation failed: ") + (e instanceof Error ? e.message : String(e)));
            }
          })();
        }}
      />
      <Snackbar message={snack} />
    </div>
  );
}
