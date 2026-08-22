/**
 * Diary list page (/diary) — mirrors Android 日记列表:
 * month-grouped accordion list, search, trash entry, meal-calendar entry,
 * create-new dialog, FAB opens/creates today's diary, per-item rename/delete.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  CalendarDays, ChevronRight, FileText, MoreVertical,
  Pencil, Plus, RefreshCw, Search, Trash2, X,
} from "lucide-react";
import { ApiClientError, apiGet, apiSend } from "../../api/client";
import { tr } from "../../i18n/tr";
import {
  ConfirmDialog, EmptyState, ErrorText, Modal, PageTutorialOverlay, PopupMenu,
  Snackbar, Spinner, TopBar, useSnackbar,
} from "../../components/ui";

interface DiaryDocument {
  uri: string;
  name: string;
  title: string;
  dateIso: string;
  monthKey: string;
  lastModified: number;
  size: number;
  wordCount: number;
}

interface DiaryEditorDocument {
  uri: string;
  name: string;
  content: string;
  lastModified: number;
  size: number;
  sha256: string;
}

interface DiaryTrashItem {
  uri: string;
  originalName: string;
  deletedAt: number;
  name?: string;
  trashName?: string;
  fileName?: string;
}

export function todayIso(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function displayName(doc: DiaryDocument): string {
  return doc.title || doc.name.replace(/\.md$/i, "");
}

function monthLabel(monthKey: string): string {
  const m = /^(\d{4})-(\d{1,2})$/.exec(monthKey);
  if (!m) return monthKey;
  return tr(`${m[1]}年${parseInt(m[2], 10)}月`, `${m[1]}-${m[2].padStart(2, "0")}`);
}

function formatSize(size: number): string {
  if (size >= 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`;
  if (size >= 1024) return `${(size / 1024).toFixed(1)} KB`;
  return tr(`${size} B`, `${size} B`);
}

/** The key a trash endpoint expects: prefer an explicit trash-file name, fall back to original. */
export function trashKey(item: DiaryTrashItem): string {
  return item.name ?? item.trashName ?? item.fileName ?? item.originalName;
}

export default function DiaryListPage() {
  const navigate = useNavigate();
  const [snack, showSnack] = useSnackbar();
  const [docs, setDocs] = useState<DiaryDocument[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [searchOpen, setSearchOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [expandedMonth, setExpandedMonth] = useState<string | null>(null);
  const [menuOpen, setMenuOpen] = useState<{ x: number; y: number } | null>(null);
  const [creating, setCreating] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [createTitle, setCreateTitle] = useState("");
  const [actionItem, setActionItem] = useState<DiaryDocument | null>(null);
  const [renameOpen, setRenameOpen] = useState(false);
  const [renameValue, setRenameValue] = useState("");
  const [renaming, setRenaming] = useState(false);
  const [deleteItem, setDeleteItem] = useState<DiaryDocument | null>(null);
  const [deleting, setDeleting] = useState(false);
  const longPressFired = useRef(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const list = await apiGet<DiaryDocument[]>("/api/diary/documents");
      setDocs(Array.isArray(list) ? list : []);
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const filtered = useMemo(() => {
    const list = docs ?? [];
    const q = query.trim().toLowerCase();
    const base = q
      ? list.filter((d) => d.name.toLowerCase().includes(q) || (d.title ?? "").toLowerCase().includes(q))
      : list;
    return [...base].sort((a, b) =>
      (b.dateIso || b.name).localeCompare(a.dateIso || a.name));
  }, [docs, query]);

  const groups = useMemo(() => {
    const map = new Map<string, DiaryDocument[]>();
    for (const d of filtered) {
      const key = d.monthKey || (d.dateIso || "").slice(0, 7) || "—";
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push(d);
    }
    return [...map.entries()].sort((a, b) => b[0].localeCompare(a[0]));
  }, [filtered]);

  /** Create (or open) today's diary, then enter the editor. */
  const openToday = async () => {
    if (creating) return;
    setCreating(true);
    setError(null);
    try {
      let name: string | undefined;
      try {
        const created = await apiSend<Partial<DiaryDocument>>("/api/diary/documents", "POST", { dateIso: todayIso() });
        name = created?.name;
      } catch {
        // Possibly "already exists" — fall through and look it up.
      }
      if (!name) {
        const list = await apiGet<DiaryDocument[]>("/api/diary/documents");
        name = (Array.isArray(list) ? list : []).find((d) => d.dateIso === todayIso())?.name;
      }
      if (name) {
        navigate(`/diary/edit?name=${encodeURIComponent(name)}`);
      } else {
        showSnack(tr("创建失败", "Could not create the diary"));
      }
    } catch (e) {
      setError(e);
    } finally {
      setCreating(false);
    }
  };

  const createNamed = async () => {
    const title = createTitle.trim();
    if (!title || creating) return;
    setCreating(true);
    try {
      const name = `${todayIso()} ${title}`;
      let finalName: string | undefined;
      try {
        const created = await apiSend<Partial<DiaryDocument>>("/api/diary/documents", "POST", { name });
        finalName = created?.name ?? name;
      } catch {
        finalName = name;
      }
      setCreateOpen(false);
      setCreateTitle("");
      navigate(`/diary/edit?name=${encodeURIComponent(finalName)}`);
    } catch (e) {
      setError(e);
    } finally {
      setCreating(false);
    }
  };

  /**
   * Rename. The conventions table has no dedicated diary rename endpoint, so try
   * POST /api/diary/rename first; when the route is missing fall back to the
   * available primitives: read old → create new → copy content → remove old.
   */
  const performRename = async (oldName: string, rawNew: string) => {
    const newName = /\.(md|markdown)$/i.test(rawNew) ? rawNew : `${rawNew}.md`;
    if (renaming) return;
    setRenaming(true);
    try {
      if (newName !== oldName && (docs ?? []).some((d) => d.name.toLowerCase() === newName.toLowerCase())) {
        showSnack(tr("目录中已有同名日记", "A diary with the same name already exists"));
        return;
      }
      try {
        await apiSend("/api/diary/rename", "POST", { name: oldName, newName });
      } catch (e) {
        const status = e instanceof ApiClientError ? e.status : 0;
        if (status !== 404 && status !== 405) throw e;
        // Composite fallback using documented endpoints.
        const old = await apiGet<DiaryEditorDocument>(`/api/diary/document?name=${encodeURIComponent(oldName)}`);
        await apiSend("/api/diary/documents", "POST", { name: newName });
        const fresh = await apiGet<DiaryEditorDocument>(`/api/diary/document?name=${encodeURIComponent(newName)}`);
        await apiSend("/api/diary/document", "PUT", {
          name: newName, content: old.content, previousSha256: fresh.sha256,
        });
        await apiSend(`/api/diary/document?name=${encodeURIComponent(oldName)}`, "DELETE");
        // The soft-deleted original would linger in the trash after a rename;
        // try to clean it up, but never fail the rename over cleanup.
        try {
          const trash = await apiGet<DiaryTrashItem[]>("/api/diary/trash");
          const match = (Array.isArray(trash) ? trash : [])
            .filter((t) => t.originalName === oldName)
            .sort((a, b) => (b.deletedAt ?? 0) - (a.deletedAt ?? 0))[0];
          if (match) await apiSend(`/api/diary/trash/item?name=${encodeURIComponent(trashKey(match))}`, "DELETE");
        } catch {
          /* ignore cleanup failures */
        }
      }
      showSnack(tr(`已重命名为 ${newName}`, `Renamed to ${newName}`));
      await load();
    } catch (e) {
      showSnack(e instanceof Error ? e.message : tr("重命名失败", "Rename failed"));
    } finally {
      setRenaming(false);
    }
  };

  const performDelete = async (item: DiaryDocument) => {
    if (deleting) return;
    setDeleting(true);
    try {
      await apiSend(`/api/diary/document?name=${encodeURIComponent(item.name)}`, "DELETE");
      showSnack(tr("已移入日记回收站", "Moved to the diary trash"));
      setDeleteItem(null);
      await load();
    } catch (e) {
      showSnack(e instanceof Error ? e.message : tr("删除失败", "Delete failed"));
    } finally {
      setDeleting(false);
    }
  };

  const startRename = (item: DiaryDocument) => {
    setActionItem(null);
    setRenameValue(item.name.replace(/\.md$/i, ""));
    setRenameOpen(true);
  };

  const longPressTimer = useRef<number | null>(null);
  const clearLongPress = () => {
    if (longPressTimer.current !== null) {
      window.clearTimeout(longPressTimer.current);
      longPressTimer.current = null;
    }
  };
  useEffect(() => clearLongPress, []);
  const longPressProps = (item: DiaryDocument) => ({
    onTouchStart: () => {
      longPressFired.current = false;
      clearLongPress();
      longPressTimer.current = window.setTimeout(() => {
        longPressFired.current = true;
        setActionItem(item);
      }, 500);
    },
    onTouchMove: clearLongPress,
    onTouchEnd: clearLongPress,
    onTouchCancel: clearLongPress,
    onContextMenu: (e: React.MouseEvent) => {
      e.preventDefault();
      longPressFired.current = true;
      setActionItem(item);
    },
  });

  return (
    <div className="diary-list-page">
      <TopBar
        title={tr("日记", "Diary")}
        actions={<>
          <button className="dc-icon-btn" aria-label={tr("搜索", "Search")} onClick={() => { setSearchOpen((v) => !v); if (searchOpen) setQuery(""); }}>
            {searchOpen ? <X size={20} /> : <Search size={20} />}
          </button>
          <button className="dc-icon-btn" aria-label={tr("日记回收站", "Diary trash")} onClick={() => navigate("/diary/trash")}>
            <Trash2 size={20} />
          </button>
          <button
            className="dc-icon-btn" aria-label={tr("更多", "More")}
            onClick={(e) => setMenuOpen({ x: e.clientX, y: e.clientY })}
          >
            <MoreVertical size={20} />
          </button>
        </>}
      />

      {searchOpen && (
        <div style={{ marginBottom: 10 }}>
          <input
            className="dc-input"
            autoFocus
            placeholder={tr("搜索日记…", "Search diaries…")}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
      )}

      {loading && docs === null && <Spinner />}
      <ErrorText error={error && docs === null ? error : null} />

      {!loading && docs !== null && filtered.length === 0 && (
        error ? (
          <EmptyState
            icon={<FileText size={44} />}
            title={tr("无法读取日记", "Could not load diaries")}
            hint={
              <button className="dc-btn dc-btn-tonal" style={{ marginTop: 10 }} onClick={() => void load()}>
                <RefreshCw size={16} /> {tr("重试", "Retry")}
              </button>
            }
          />
        ) : query.trim() ? (
          <EmptyState icon={<Search size={44} />} title={tr("没有匹配的日记", "No matching diaries")} />
        ) : (
          <EmptyState
            icon={<FileText size={44} />}
            title={tr("这里还没有日记", "No diaries here yet")}
            hint={
              <button className="dc-btn dc-btn-filled" style={{ marginTop: 12 }} disabled={creating} onClick={() => void openToday()}>
                {creating ? "…" : tr("进入今日日记", "Open today's diary")}
              </button>
            }
          />
        )
      )}

      <div className="dc-col" style={{ gap: 6 }}>
        {groups.map(([month, items]) => {
          const expanded = expandedMonth === month || query.trim().length > 0;
          return (
            <div key={month} className="dc-card" style={{ overflow: "hidden" }}>
              <button
                className="dc-row"
                style={{
                  width: "100%", border: "none", background: "transparent",
                  padding: "12px 14px", textAlign: "left", gap: 8,
                }}
                onClick={() => setExpandedMonth(expanded && expandedMonth === month ? null : month)}
                aria-expanded={expanded}
              >
                <span style={{ fontWeight: 600 }}>{monthLabel(month)}</span>
                <span className="dc-muted" style={{ fontSize: "0.85em" }}>
                  {tr(`${items.length} 篇`, `${items.length} entries`)}
                </span>
                <span className="dc-grow" />
                <ChevronRight
                  size={18}
                  className="dc-muted"
                  style={{ transform: expanded ? "rotate(90deg)" : "none", transition: "transform 0.18s ease" }}
                />
              </button>
              {expanded && (
                <div className="dc-col" style={{ gap: 6, padding: "0 8px 8px" }}>
                  {items.map((item) => (
                    <div
                      key={item.uri || item.name}
                      className="dc-row diary-item"
                      role="button"
                      tabIndex={0}
                      style={{
                        background: "var(--dc-surface-container-high)",
                        borderRadius: "calc(var(--dc-radius) * 0.7)",
                        padding: "10px 12px",
                        cursor: "pointer",
                      }}
                      onClick={() => { if (!longPressFired.current) navigate(`/diary/edit?name=${encodeURIComponent(item.name)}`); }}
                      onKeyDown={(e) => { if (e.key === "Enter") navigate(`/diary/edit?name=${encodeURIComponent(item.name)}`); }}
                      {...longPressProps(item)}
                    >
                      <FileText size={18} className="dc-muted" style={{ flexShrink: 0 }} />
                      <div className="dc-grow" style={{ minWidth: 0 }}>
                        <div style={{ fontWeight: 500, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                          {displayName(item)}
                        </div>
                        <div className="dc-muted" style={{ fontSize: "0.8em", marginTop: 2 }}>
                          {tr(`${item.wordCount} 字`, `${item.wordCount} words`)} · {formatSize(item.size)}
                        </div>
                      </div>
                      <button
                        className="dc-icon-btn"
                        aria-label={tr("更多", "More")}
                        onClick={(e) => { e.stopPropagation(); setActionItem(item); }}
                      >
                        <MoreVertical size={18} />
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </div>

      <button className="dc-fab" aria-label={tr("进入今日日记", "Open today's diary")} disabled={creating} onClick={() => void openToday()}>
        {creating ? <RefreshCw size={22} className="dc-spin" /> : <Plus size={24} />}
      </button>

      <PopupMenu
        open={menuOpen !== null}
        onClose={() => setMenuOpen(null)}
        x={menuOpen?.x ?? 0}
        y={menuOpen?.y ?? 0}
        items={[
          { label: <span className="dc-row" style={{ gap: 8 }}><RefreshCw size={16} /> {tr("刷新", "Refresh")}</span>, onClick: () => void load() },
          { label: <span className="dc-row" style={{ gap: 8 }}><Plus size={16} /> {tr("新建", "New")}</span>, onClick: () => setCreateOpen(true) },
          { label: <span className="dc-row" style={{ gap: 8 }}><CalendarDays size={16} /> {tr("吃历", "Meal calendar")}</span>, onClick: () => navigate("/meals") },
        ]}
      />

      {/* New diary dialog */}
      <Modal open={createOpen} onClose={() => setCreateOpen(false)} title={tr("新建日记", "New diary")}>
        <input
          className="dc-input"
          autoFocus
          placeholder={tr("标题", "Title")}
          value={createTitle}
          onChange={(e) => setCreateTitle(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter") void createNamed(); }}
        />
        <div className="dc-muted" style={{ fontSize: "0.82em", marginTop: 8 }}>
          {tr("文件将命名为“当天日期 标题.md”。", "The file will be named “date title.md”.")}
        </div>
        <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 14 }}>
          <button className="dc-btn" onClick={() => setCreateOpen(false)}>{tr("取消", "Cancel")}</button>
          <button className="dc-btn dc-btn-filled" disabled={!createTitle.trim() || creating} onClick={() => void createNamed()}>
            {tr("确定", "OK")}
          </button>
        </div>
      </Modal>

      {/* Per-item action dialog */}
      <Modal open={actionItem !== null} onClose={() => setActionItem(null)} title={actionItem ? displayName(actionItem) : ""}>
        <div className="dc-col" style={{ gap: 4 }}>
          <button
            className="dc-btn"
            style={{ justifyContent: "flex-start", width: "100%" }}
            onClick={() => { if (actionItem) navigate(`/diary/edit?name=${encodeURIComponent(actionItem.name)}`); }}
          >
            <FileText size={16} /> {tr("打开", "Open")}
          </button>
          <button className="dc-btn" style={{ justifyContent: "flex-start", width: "100%" }} onClick={() => actionItem && startRename(actionItem)}>
            <Pencil size={16} /> {tr("重命名", "Rename")}
          </button>
          <button
            className="dc-btn"
            style={{ justifyContent: "flex-start", width: "100%", color: "var(--dc-error)" }}
            onClick={() => { if (actionItem) { setDeleteItem(actionItem); setActionItem(null); } }}
          >
            <Trash2 size={16} /> {tr("删除", "Delete")}
          </button>
        </div>
      </Modal>

      {/* Rename dialog */}
      <Modal open={renameOpen} onClose={() => setRenameOpen(false)} title={tr("重命名文件", "Rename file")}>
        <input
          className="dc-input"
          autoFocus
          value={renameValue}
          onChange={(e) => setRenameValue(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && renameValue.trim() && actionItem) {
              void performRename(actionItem.name, renameValue.trim());
              setRenameOpen(false);
            }
          }}
        />
        <div className="dc-muted" style={{ fontSize: "0.82em", marginTop: 8 }}>
          {tr("将自动补上 .md 后缀。", "The .md suffix is added automatically.")}
        </div>
        <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 14 }}>
          <button className="dc-btn" onClick={() => setRenameOpen(false)}>{tr("取消", "Cancel")}</button>
          <button
            className="dc-btn dc-btn-filled"
            disabled={!renameValue.trim() || renaming}
            onClick={() => {
              if (actionItem && renameValue.trim()) {
                void performRename(actionItem.name, renameValue.trim());
                setRenameOpen(false);
              }
            }}
          >
            {tr("确定", "OK")}
          </button>
        </div>
      </Modal>

      {/* Delete (move to trash) confirm */}
      <ConfirmDialog
        open={deleteItem !== null}
        danger
        title={deleteItem ? tr(`删除 ${deleteItem.name.replace(/\.md$/i, "")}？`, `Delete ${deleteItem.name.replace(/\.md$/i, "")}?`) : ""}
        message={tr("文件将安全复制到日记目录内的回收站，校验成功后才删除原文件。", "The file is copied and verified in the diary trash before the original is removed.")}
        confirmLabel={tr("移入回收站", "Move to trash")}
        onConfirm={() => deleteItem && void performDelete(deleteItem)}
        onCancel={() => setDeleteItem(null)}
      />

      <Snackbar message={snack} />
      <PageTutorialOverlay
        pageKey="diary"
        title={tr("日记", "Diary")}
        lines={[
          tr("点击日记条目进入编辑器；长按或点右侧“更多”可重命名、删除。", "Tap an entry to open the editor; long-press or use “More” to rename or delete."),
          tr("右下角按钮直接打开（或创建）今天的日记。", "The bottom-right button opens (or creates) today's diary."),
          tr("顶栏可搜索日记、打开日记回收站与吃历。", "Use the top bar to search, open the trash and the meal calendar."),
        ]}
      />
      <style>{`
        @media (max-width: 700px) {
          .diary-list-page .diary-item { padding: 9px 10px; }
        }
      `}</style>
    </div>
  );
}
