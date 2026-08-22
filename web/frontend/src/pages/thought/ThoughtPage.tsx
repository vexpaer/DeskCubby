/**
 * 小巧思 (/thought) — faithful web port of Android ui/thought/*.
 * Left category drawer, 一行/完整 display toggle (persisted via settings),
 * pinned-first server-ordered list, long-press/⋮ action dialog, drag-or-button
 * reorder (keyboard accessible), sticky composer above the nav bar, and
 * category CRUD dialogs.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  ArchiveRestore as IconTrashOpen,
  Check as IconCheck,
  ChevronDown as IconDown,
  ChevronUp as IconUp,
  Copy as IconCopy,
  FolderInput as IconMoveCat,
  GripVertical as IconGrip,
  ListFilter as IconLabelOff,
  ListChecks as IconAll,
  Menu as IconMenu,
  Palette as IconPalette,
  Pencil as IconEdit,
  Pin as IconPin,
  Plus as IconPlus,
  Send as IconSend,
  Share2 as IconShare,
  Star as IconStar,
  Trash2 as IconDelete,
  X as IconCancel,
  Zap as IconBolt,
} from "lucide-react";
import { apiGet, apiSend } from "../../api/client";
import { argbToCss } from "../../api/types";
import { useSettings } from "../../stores/settings";
import { tr, uiLanguage } from "../../i18n/tr";
import {
  ConfirmDialog, EmptyState, ErrorText, Modal, PageTutorialOverlay, Snackbar, Spinner, TopBar, useSnackbar,
} from "../../components/ui";

interface FlashThought {
  id: number;
  content: string;
  createdAt: number;
  updatedAt: number;
  pinned: boolean;
  deletedAt: number | null;
  sortOrder: number;
  categoryId: number | null;
  highlighted: boolean;
}

export interface ThoughtCategory {
  id: number;
  name: string;
  colorArgb: number;
  sortOrder: number;
  createdAt: number;
  updatedAt: number;
}

type CategoryFilter =
  | { kind: "all" }
  | { kind: "uncategorized" }
  | { kind: "category"; id: number };

/** Preset palettes copied from ThoughtCategoryComponents.kt. */
const CATEGORY_COLORS = [0xffe05252, 0xffeb8c3a, 0xffe0b72f, 0xff4e9a62, 0xff3c9a9a, 0xff4c78c2, 0xff8166c2, 0xffc45e91, 0xff7b716a];
const ORGANIC_CATEGORY_COLORS = [0xffb0563c, 0xffc08a2e, 0xff2e7d4b, 0xff3d7665, 0xff3e7c8a, 0xff4c63a6, 0xff7a5fa0, 0xffa05577, 0xff7b716a];

export function arrayOf<T>(v: unknown): T[] {
  if (Array.isArray(v)) return v as T[];
  if (v && typeof v === "object") {
    const obj = v as Record<string, unknown>;
    for (const key of ["items", "records", "data", "results"]) {
      if (Array.isArray(obj[key])) return obj[key] as T[];
    }
  }
  return [];
}

export function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

export function argbLuminance(argb: number): number {
  const v = argb >>> 0;
  const r = (v >>> 16) & 255;
  const g = (v >>> 8) & 255;
  const b = v & 255;
  return (0.299 * r + 0.587 * g + 0.114 * b) / 255;
}

function thoughtDateTime(ms: number): string {
  const locale = uiLanguage() === "ENGLISH" ? "en-US" : "zh-CN";
  return new Intl.DateTimeFormat(locale, {
    year: "numeric", month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit", hour12: false,
  }).format(new Date(ms));
}

/** Long-press helper (touch) with right-click parity; click can be suppressed after firing. */
export function useLongPress(enabled: boolean, onLongPress: () => void, ms = 480) {
  const timer = useRef<number | null>(null);
  const firedRef = useRef(false);
  const clear = useCallback(() => {
    if (timer.current != null) {
      window.clearTimeout(timer.current);
      timer.current = null;
    }
  }, []);
  useEffect(() => clear, [clear]);
  const props = enabled
    ? {
        onPointerDown: () => {
          firedRef.current = false;
          clear();
          timer.current = window.setTimeout(() => {
            firedRef.current = true;
            onLongPress();
          }, ms);
        },
        onPointerUp: clear,
        onPointerLeave: clear,
        onPointerCancel: clear,
        onContextMenu: (e: React.MouseEvent) => {
          e.preventDefault();
          clear();
          if (!firedRef.current) {
            firedRef.current = true;
            onLongPress();
          }
        },
      }
    : {};
  const suppressClick = useCallback(() => {
    if (firedRef.current) {
      firedRef.current = false;
      return true;
    }
    return false;
  }, []);
  return { props, suppressClick };
}

export function CategoryColorDot({ colorArgb, size = 14 }: { colorArgb: number; size?: number }) {
  return (
    <span
      aria-hidden
      style={{ width: size, height: size, borderRadius: "50%", background: argbToCss(colorArgb), display: "inline-block", flexShrink: 0 }}
    />
  );
}

export default function ThoughtPage() {
  const navigate = useNavigate();
  const settingsState = useSettings();
  const settings = settingsState.settings;
  const [snack, showSnack] = useSnackbar();

  const [thoughts, setThoughts] = useState<FlashThought[] | null>(null);
  const [categories, setCategories] = useState<ThoughtCategory[]>([]);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [filter, setFilter] = useState<CategoryFilter>({ kind: "all" });
  const [drawerOpen, setDrawerOpen] = useState(false);

  const [editingId, setEditingId] = useState<number | null>(null);
  const [editor, setEditor] = useState("");
  const [sending, setSending] = useState(false);

  const [actionItem, setActionItem] = useState<FlashThought | null>(null);
  const [editDialogItem, setEditDialogItem] = useState<FlashThought | null>(null);
  const [editDraft, setEditDraft] = useState("");
  const [categorizingItem, setCategorizingItem] = useState<FlashThought | null>(null);
  const [creatingCategoryForItem, setCreatingCategoryForItem] = useState<FlashThought | null>(null);
  const [showSendPicker, setShowSendPicker] = useState(false);
  const [showAddCategory, setShowAddCategory] = useState(false);
  const [editingCategory, setEditingCategory] = useState<ThoughtCategory | null>(null);

  // drag state
  const [dragId, setDragId] = useState<number | null>(null);
  const [insertSlot, setInsertSlot] = useState<number | null>(null);
  const slotRef = useRef<number | null>(null);

  const restoredRef = useRef(false);
  const positionedPagesRef = useRef<Set<string>>(new Set());

  const organic = settings?.visualStyle === "ORGANIC_FUTURE";

  const reload = useCallback(async () => {
    try {
      const [t, c] = await Promise.all([
        apiGet<unknown>("/api/thoughts"),
        apiGet<unknown>("/api/thought-categories"),
      ]);
      setThoughts(arrayOf<FlashThought>(t));
      setCategories(
        arrayOf<ThoughtCategory>(c).sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id),
      );
      setLoadError(null);
    } catch (e) {
      setLoadError(e);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const pageKey = useMemo(() => {
    switch (filter.kind) {
      case "all": return "all";
      case "uncategorized": return "uncategorized";
      case "category": return `category:${filter.id}`;
    }
  }, [filter]);

  // Restore last visited page once data is known (settings.thoughtReopenMode).
  useEffect(() => {
    if (restoredRef.current || !settings || thoughts == null) return;
    restoredRef.current = true;
    if (settings.thoughtReopenMode !== "LAST_VISITED") return;
    const key = settings.lastThoughtPageKey ?? "all";
    if (key === "uncategorized") {
      setFilter({ kind: "uncategorized" });
    } else if (key.startsWith("category:")) {
      const id = Number(key.slice("category:".length));
      setFilter(Number.isFinite(id) && categories.some((c) => c.id === id) ? { kind: "category", id } : { kind: "all" });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settings, thoughts == null]);

  // Drop a filter whose category no longer exists.
  useEffect(() => {
    if (filter.kind === "category" && !categories.some((c) => c.id === filter.id)) setFilter({ kind: "all" });
  }, [categories, filter]);

  const items = useMemo(() => {
    if (!thoughts) return [];
    switch (filter.kind) {
      case "all": return thoughts;
      case "uncategorized": return thoughts.filter((t) => t.categoryId == null);
      case "category": return thoughts.filter((t) => t.categoryId === filter.id);
    }
  }, [thoughts, filter]);

  const categoriesById = useMemo(() => {
    const map = new Map<number, ThoughtCategory>();
    for (const c of categories) map.set(c.id, c);
    return map;
  }, [categories]);

  const selectCategory = useCallback((next: CategoryFilter) => {
    setFilter(next);
    setEditingId(null);
    setEditor("");
    const key = next.kind === "all" ? "all" : next.kind === "uncategorized" ? "uncategorized" : `category:${next.id}`;
    void settingsState.update({ lastThoughtPageKey: key }).catch(() => undefined);
  }, [settingsState]);

  // First entry into a page auto-positions to the newest item at the bottom
  // (Android: 首次进入定位到底部); later manual scrolls are not hijacked.
  useEffect(() => {
    if (!thoughts || items.length === 0) return;
    if (positionedPagesRef.current.has(pageKey)) return;
    positionedPagesRef.current.add(pageKey);
    requestAnimationFrame(() => {
      window.scrollTo({ top: document.documentElement.scrollHeight });
    });
  }, [thoughts, items.length, pageKey]);

  const scrollToBottomSoon = () => {
    requestAnimationFrame(() => {
      window.scrollTo({ top: document.documentElement.scrollHeight, behavior: "smooth" });
    });
  };

  const fail = useCallback((e: unknown) => {
    showSnack(tr("操作失败：", "Operation failed: ") + errMsg(e));
  }, [showSnack]);

  const startEdit = (item: FlashThought) => {
    setEditingId(item.id);
    setEditor(item.content);
  };

  const exitEdit = () => {
    setEditingId(null);
    setEditor("");
  };

  const submit = async (overrideCategoryId?: number | null) => {
    const content = editor.trim();
    if (!content || sending) return;
    setSending(true);
    try {
      if (editingId != null) {
        await apiSend(`/api/thoughts/${editingId}`, "PUT", { content });
        exitEdit();
      } else if (overrideCategoryId !== undefined) {
        await apiSend("/api/thoughts", "POST", { content, categoryId: overrideCategoryId });
        selectCategory(overrideCategoryId == null ? { kind: "uncategorized" } : { kind: "category", id: overrideCategoryId });
        exitEdit();
      } else {
        const dest = filter.kind === "category" ? filter.id : null;
        await apiSend("/api/thoughts", "POST", { content, categoryId: dest });
        exitEdit();
      }
      await reload();
      scrollToBottomSoon();
    } catch (e) {
      fail(e);
    } finally {
      setSending(false);
    }
  };

  const removeItem = async (item: FlashThought) => {
    if (editingId === item.id) exitEdit();
    try {
      await apiSend(`/api/thoughts/${item.id}`, "DELETE");
      setActionItem((cur) => (cur?.id === item.id ? null : cur));
      await reload();
    } catch (e) {
      fail(e);
    }
  };

  const togglePinned = async (item: FlashThought) => {
    try {
      await apiSend(`/api/thoughts/${item.id}/pin`, "POST", { value: !item.pinned });
      await reload();
    } catch (e) { fail(e); }
  };

  const toggleHighlighted = async (item: FlashThought) => {
    try {
      await apiSend(`/api/thoughts/${item.id}/highlight`, "POST", { value: !item.highlighted });
      await reload();
    } catch (e) { fail(e); }
  };

  const moveCategory = async (item: FlashThought, categoryId: number | null) => {
    try {
      await apiSend(`/api/thoughts/${item.id}/move`, "POST", { categoryId });
      if (editingId === item.id) exitEdit();
      await reload();
    } catch (e) { fail(e); }
  };

  /** Persists a new relative order of the currently visible subset. */
  const persistOrder = async (ordered: FlashThought[]) => {
    try {
      await apiSend("/api/thoughts/reorder", "POST", ordered.map((it, i) => ({ id: it.id, sortOrder: i })));
      await reload();
    } catch (e) {
      fail(e);
    }
  };

  const moveRelative = (index: number, delta: number) => {
    const target = index + delta;
    if (target < 0 || target >= items.length) return;
    const next = items.slice();
    const [moved] = next.splice(index, 1);
    next.splice(target, 0, moved);
    void persistOrder(next);
  };

  const commitDrag = useCallback(() => {
    const sourceIndex = dragId != null ? items.findIndex((it) => it.id === dragId) : -1;
    const slot = slotRef.current;
    setDragId(null);
    setInsertSlot(null);
    slotRef.current = null;
    if (sourceIndex < 0 || slot == null) return;
    const insertAt = slot > sourceIndex ? slot - 1 : slot;
    if (insertAt === sourceIndex) return;
    const next = items.slice();
    const [moved] = next.splice(sourceIndex, 1);
    next.splice(insertAt, 0, moved);
    void persistOrder(next);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dragId, items]);

  const createCategory = async (name: string, colorArgb: number): Promise<boolean> => {
    const normalized = name.trim();
    if (!normalized) return false;
    if (categories.some((c) => c.name.toLowerCase() === normalized.toLowerCase())) return false;
    try {
      await apiSend("/api/thought-categories", "POST", { name: normalized, colorArgb });
      await reload();
      return true;
    } catch (e) {
      fail(e);
      return false;
    }
  };

  const updateCategory = async (id: number, name: string, colorArgb: number): Promise<boolean> => {
    const normalized = name.trim();
    if (!normalized) return false;
    if (categories.some((c) => c.id !== id && c.name.toLowerCase() === normalized.toLowerCase())) return false;
    try {
      await apiSend(`/api/thought-categories/${id}`, "PUT", { name: normalized, colorArgb });
      await reload();
      return true;
    } catch (e) {
      fail(e);
      return false;
    }
  };

  const deleteCategory = async (id: number) => {
    try {
      await apiSend(`/api/thought-categories/${id}`, "DELETE");
      await reload();
    } catch (e) { fail(e); }
  };

  /** Creates a category from the picker and immediately assigns the thought to it. */
  const createCategoryAndAssign = async (thoughtId: number, name: string, colorArgb: number): Promise<boolean> => {
    const normalized = name.trim();
    if (!normalized || categories.some((c) => c.name.toLowerCase() === normalized.toLowerCase())) return false;
    try {
      await apiSend("/api/thought-categories", "POST", { name: normalized, colorArgb });
      const fresh = arrayOf<ThoughtCategory>(
        await apiGet<unknown>("/api/thought-categories"),
      ).sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id);
      setCategories(fresh);
      const created = [...fresh].reverse().find((c) => c.name.toLowerCase() === normalized.toLowerCase());
      if (created) await apiSend(`/api/thoughts/${thoughtId}/move`, "POST", { categoryId: created.id });
      await reload();
      return true;
    } catch (e) {
      fail(e);
      return false;
    }
  };

  const copyText = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      showSnack(tr("复制失败", "Copy failed"));
    }
  };

  const shareText = async (text: string) => {
    if (typeof navigator.share === "function") {
      try {
        await navigator.share({ title: tr("分享小巧思", "Share thought"), text });
        return;
      } catch {
        return; // user closed the share sheet
      }
    }
    await copyText(text);
    showSnack(tr("已复制到剪贴板", "Copied to clipboard"));
  };

  if (!settings) return <Spinner />;

  const fullMode = settings.thoughtDisplayMode === "FULL";
  const rowHeightPx = settings.thoughtRowHeightDp > 0 ? settings.thoughtRowHeightDp : 56;
  const maxEditorPx = settings.thoughtEditorMaxHeightDp > 0 ? settings.thoughtEditorMaxHeightDp : 200;
  const hlBg = argbToCss(settings.thoughtHighlightColorArgb);
  const hlFg = argbLuminance(settings.thoughtHighlightColorArgb) > 0.5 ? "#000000" : "#ffffff";
  const title = filter.kind === "all"
    ? tr("全部", "All")
    : filter.kind === "uncategorized"
      ? tr("未分类", "Uncategorized")
      : (categoriesById.get(filter.id)?.name ?? tr("未分类", "Uncategorized"));

  const sendLongPress = useLongPress(editor.trim().length > 0, () => setShowSendPicker(true));

  return (
    <div style={{ display: "flex", flexDirection: "column", minHeight: "calc(100dvh - var(--dc-bottom-nav-height) - 40px)" }}>
      <TopBar
        title={title}
        actions={
          <>
            <button
              className="dc-btn"
              aria-label={tr("切换一行/完整显示", "Toggle single-line/full display")}
              title={tr("切换一行/完整显示", "Toggle single-line/full display")}
              onClick={() => {
                void settingsState
                  .update({ thoughtDisplayMode: fullMode ? "SINGLE_LINE" : "FULL" })
                  .catch(fail);
              }}
            >
              {fullMode ? (
                <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden><path d="M4 6h16M4 12h16M4 18h10" /></svg>
              ) : (
                <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden><path d="M4 6h16M4 12h10" /></svg>
              )}
              {fullMode ? tr("完整", "Full") : tr("一行", "1 line")}
            </button>
            <button className="dc-icon-btn" aria-label={tr("查看分类", "View categories")} onClick={() => setDrawerOpen(true)}>
              <IconMenu size={20} />
            </button>
            <button className="dc-icon-btn" aria-label={tr("回收站", "Trash")} onClick={() => navigate("/thought/trash")}>
              <IconTrashOpen size={20} />
            </button>
          </>
        }
      />

      {loadError != null && (
        <div className="dc-row" style={{ padding: "0 4px" }}>
          <ErrorText error={loadError} />
          <button className="dc-btn dc-btn-tonal" onClick={() => { setThoughts(null); void reload(); }}>
            {tr("重试", "Retry")}
          </button>
        </div>
      )}

      {thoughts != null && items.length === 0 && (
        <EmptyState
          icon={<IconBolt size={40} />}
          title={filter.kind === "all" ? tr("记录此刻的想法", "Capture what is on your mind") : tr("这个分类还是空的", "This category is empty")}
          hint={filter.kind === "all"
            ? tr("在下方快速写一条小巧思。", "Write a quick thought below.")
            : tr("可以在下方输入，长按发送按钮选择分类。", "Write below, then hold Send to choose a category.")}
        />
      )}

      <div style={{ flex: 1, padding: "6px 8px", display: "flex", flexDirection: "column", gap: 4 }}>
        {items.map((item, index) => (
          <ThoughtRow
            key={item.id}
            item={item}
            index={index}
            fullMode={fullMode}
            rowHeightPx={rowHeightPx}
            highlightBg={hlBg}
            highlightFg={hlFg}
            category={item.categoryId != null ? categoriesById.get(item.categoryId) : undefined}
            single={items.length > 1}
            isLast={index === items.length - 1}
            dragging={dragId === item.id}
            showInsertAbove={dragId != null && insertSlot === index}
            showInsertBelow={dragId != null && index === items.length - 1 && insertSlot === items.length}
            onActivate={() => startEdit(item)}
            onActions={() => setActionItem(item)}
            onDelete={() => void removeItem(item)}
            onMoveRelative={(delta) => moveRelative(index, delta)}
            onDragStart={() => { setDragId(item.id); slotRef.current = index; setInsertSlot(null); }}
            onDragEnd={commitDrag}
            onDragOverRow={(slot) => { slotRef.current = slot; setInsertSlot(slot); }}
          />
        ))}
      </div>

      {/* Sticky composer above the bottom nav bar */}
      <div
        style={{
          position: "sticky",
          bottom: "calc(var(--dc-bottom-nav-height) + env(safe-area-inset-bottom) + 8px)",
          zIndex: 30,
          padding: "8px 10px",
          background: "color-mix(in srgb, var(--dc-background) 88%, transparent)",
          backdropFilter: "blur(10px)",
          borderRadius: "var(--dc-radius)",
        }}
      >
        {editingId != null && (
          <div className="dc-row" style={{ marginBottom: 4 }}>
            <span style={{ color: "var(--dc-primary)", fontWeight: 600, fontSize: "0.9em" }}>
              {tr("正在编辑一条小巧思", "Editing an existing thought")}
            </span>
            <span className="dc-grow" />
            <button className="dc-btn" onClick={exitEdit}>
              <IconCancel size={16} /> {tr("取消", "Cancel")}
            </button>
          </div>
        )}
        <div className="dc-row" style={{ alignItems: "flex-end" }}>
          <textarea
            className="dc-input"
            value={editor}
            placeholder={tr("此刻在想什么？", "What's on your mind?")}
            rows={1}
            onChange={(e) => setEditor(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
                e.preventDefault();
                void submit();
              }
            }}
            style={{ resize: "none", maxHeight: maxEditorPx, minHeight: 44, overflowY: "auto", borderRadius: 28 }}
          />
          <span {...sendLongPress.props} style={{ display: "inline-flex", touchAction: "none" }}>
            <button
              aria-label={tr("发送；长按选择分类", "Send; hold to choose category")}
              title={tr("发送；长按/右键选择分类", "Send; long-press or right-click to choose a category")}
              disabled={editor.trim().length === 0 || sending}
              onClick={() => { if (!sendLongPress.suppressClick()) void submit(); }}
              onContextMenu={(e) => e.preventDefault()}
              style={{
                width: 36, height: 36, borderRadius: "50%", border: "none", flexShrink: 0,
                display: "flex", alignItems: "center", justifyContent: "center",
                background: editor.trim().length > 0 ? "var(--dc-primary)" : "var(--dc-surface-variant)",
                color: editor.trim().length > 0 ? "var(--dc-on-primary)" : "var(--dc-on-surface-variant)",
              }}
            >
              {sending ? <Spinner size={16} /> : <IconSend size={19} />}
            </button>
          </span>
        </div>
      </div>

      {/* ---- Category drawer ---- */}
      {drawerOpen && (
        <div
          className="dc-dialog-overlay"
          style={{ justifyContent: "flex-start", alignItems: "stretch", padding: 0 }}
          onClick={() => setDrawerOpen(false)}
        >
          <nav
            aria-label={tr("小巧思分类", "Thought categories")}
            style={{
              width: "min(320px, 85vw)", height: "100%", overflowY: "auto",
              background: "var(--dc-surface-container-high)", padding: 12,
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="dc-title" style={{ padding: 12 }}>{tr("小巧思分类", "Thought categories")}</div>
            <DrawerRow
              label={tr("全部", "All")}
              count={thoughts?.length ?? 0}
              selected={filter.kind === "all"}
              leading={<IconAll size={18} />}
              onClick={() => { selectCategory({ kind: "all" }); setDrawerOpen(false); }}
            />
            <DrawerRow
              label={tr("未分类", "Uncategorized")}
              count={(thoughts ?? []).filter((t) => t.categoryId == null).length}
              selected={filter.kind === "uncategorized"}
              leading={<IconLabelOff size={18} />}
              onClick={() => { selectCategory({ kind: "uncategorized" }); setDrawerOpen(false); }}
            />
            <hr className="dc-divider" />
            {categories.map((category) => (
              <DrawerRow
                key={category.id}
                label={category.name}
                count={(thoughts ?? []).filter((t) => t.categoryId === category.id).length}
                selected={filter.kind === "category" && filter.id === category.id}
                leading={<CategoryColorDot colorArgb={category.colorArgb} />}
                onClick={() => { selectCategory({ kind: "category", id: category.id }); setDrawerOpen(false); }}
                onLongPress={() => { setEditingCategory(category); setDrawerOpen(false); }}
                onEdit={() => { setEditingCategory(category); setDrawerOpen(false); }}
              />
            ))}
            <button className="dc-btn" style={{ width: "100%", justifyContent: "flex-start" }} onClick={() => { setShowAddCategory(true); setDrawerOpen(false); }}>
              <IconPlus size={18} /> {tr("新增分类", "New category")}
            </button>
            <hr className="dc-divider" />
            <button className="dc-btn" style={{ width: "100%", justifyContent: "flex-start" }} onClick={() => navigate("/thought/trash")}>
              <IconTrashOpen size={18} /> {tr("回收站", "Trash")}
            </button>
          </nav>
        </div>
      )}

      {/* ---- Long-press / ⋮ action dialog ---- */}
      {actionItem && (() => {
        const item = actionItem;
        const catName = item.categoryId != null
          ? (categoriesById.get(item.categoryId)?.name ?? tr("未分类", "Uncategorized"))
          : tr("未分类", "Uncategorized");
        return (
          <Modal
            open
            onClose={() => setActionItem(null)}
            title={<span style={{ display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden" }}>{item.content}</span>}
          >
            <div className="dc-muted" style={{ fontSize: "0.9em", lineHeight: 1.7 }}>
              <div>{tr("分类：", "Category: ") + catName}</div>
              <div>{tr("创建：", "Created: ") + thoughtDateTime(item.createdAt)}</div>
              {item.updatedAt !== item.createdAt && <div>{tr("更新：", "Updated: ") + thoughtDateTime(item.updatedAt)}</div>}
            </div>
            <hr className="dc-divider" />
            <div className="dc-col" style={{ gap: 2 }}>
              <MenuAction icon={<IconPin size={18} />} label={item.pinned ? tr("取消置顶", "Unpin") : tr("置顶", "Pin")} onClick={() => { setActionItem(null); void togglePinned(item); }} />
              <MenuAction icon={<IconStar size={18} />} label={item.highlighted ? tr("取消重点", "Remove highlight") : tr("标记重点", "Mark as highlight")} onClick={() => { setActionItem(null); void toggleHighlighted(item); }} />
              <MenuAction icon={<IconEdit size={18} />} label={tr("编辑", "Edit")} onClick={() => { setEditDialogItem(item); setEditDraft(item.content); setActionItem(null); }} />
              <MenuAction icon={<IconMoveCat size={18} />} label={tr("切换分类", "Change category")} onClick={() => { setCategorizingItem(item); setActionItem(null); }} />
              <MenuAction icon={<IconCopy size={18} />} label={tr("复制", "Copy")} onClick={() => { setActionItem(null); void copyText(item.content); }} />
              <MenuAction icon={<IconShare size={18} />} label={tr("分享这段文字", "Share this text")} onClick={() => { setActionItem(null); void shareText(item.content); }} />
              <MenuAction danger icon={<IconDelete size={18} />} label={tr("移入回收站", "Move to trash")} onClick={() => { setActionItem(null); void removeItem(item); }} />
            </div>
            <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 12 }}>
              <button className="dc-btn" onClick={() => setActionItem(null)}>{tr("关闭", "Close")}</button>
            </div>
          </Modal>
        );
      })()}

      {/* ---- Inline edit dialog ---- */}
      <Modal open={editDialogItem != null} onClose={() => setEditDialogItem(null)} title={tr("编辑小巧思", "Edit thought")}>
        {editDialogItem && (
          <>
            <textarea
              className="dc-input"
              value={editDraft}
              rows={4}
              onChange={(e) => setEditDraft(e.target.value)}
              style={{ resize: "vertical", whiteSpace: "pre-wrap" }}
            />
            <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 12 }}>
              <button className="dc-btn" onClick={() => setEditDialogItem(null)}>{tr("取消", "Cancel")}</button>
              <button
                className="dc-btn dc-btn-filled"
                disabled={editDraft.trim().length === 0}
                onClick={async () => {
                  const target = editDialogItem;
                  try {
                    await apiSend(`/api/thoughts/${target.id}`, "PUT", { content: editDraft.trim() });
                    setEditDialogItem(null);
                    await reload();
                    document
                      .querySelector(`[data-thought-id="${target.id}"]`)
                      ?.scrollIntoView({ block: "nearest", behavior: "smooth" });
                  } catch (e) { fail(e); }
                }}
              >
                {tr("保存", "Save")}
              </button>
            </div>
          </>
        )}
      </Modal>

      {/* ---- Change category picker ---- */}
      {categorizingItem && (
        <CategoryPickerDialog
          title={tr("切换分类", "Change category")}
          categories={categories}
          currentCategoryId={categorizingItem.categoryId}
          onCreateNew={() => { setCreatingCategoryForItem(categorizingItem); setCategorizingItem(null); }}
          onSelect={(categoryId) => {
            const item = categorizingItem;
            setCategorizingItem(null);
            void moveCategory(item, categoryId);
          }}
          onClose={() => setCategorizingItem(null)}
        />
      )}

      {/* ---- Send-to-category picker (long-press send) ---- */}
      {showSendPicker && (
        <CategoryPickerDialog
          title={tr("发送到分类", "Send to category")}
          categories={categories}
          currentCategoryId={
            editingId != null
              ? (thoughts?.find((it) => it.id === editingId)?.categoryId ?? null)
              : (filter.kind === "category" ? filter.id : null)
          }
          onSelect={(categoryId) => {
            setShowSendPicker(false);
            void submit(categoryId);
          }}
          onClose={() => setShowSendPicker(false)}
        />
      )}

      {/* ---- Category create ---- */}
      {showAddCategory && (
        <CategoryEditorDialog
          category={null}
          categories={categories}
          organic={!!organic}
          onClose={() => setShowAddCategory(false)}
          onSave={async (name, color) => {
            const ok = await createCategory(name, color);
            if (ok) setShowAddCategory(false);
            else showSnack(tr("已有同名分类", "A category with this name already exists"));
          }}
        />
      )}

      {/* ---- Category edit ---- */}
      {editingCategory && (
        <CategoryEditorDialog
          category={editingCategory}
          categories={categories}
          organic={!!organic}
          onClose={() => setEditingCategory(null)}
          onSave={async (name, color) => {
            const ok = await updateCategory(editingCategory.id, name, color);
            if (ok) setEditingCategory(null);
            else showSnack(tr("已有同名分类", "A category with this name already exists"));
          }}
          onDelete={(cat) => setEditingCategory(cat)}
        />
      )}

      <ConfirmDialog
        open={editingCategory != null}
        title={tr("删除分类？", "Delete category?")}
        message={editingCategory
          ? tr(
              "“" + editingCategory.name + "”中的所有小巧思会保留，并归入“未分类”。",
              "All thoughts in “" + editingCategory.name + "” will be kept and moved to Uncategorized.",
            )
          : undefined}
        confirmLabel={tr("删除分类", "Delete category")}
        cancelLabel={tr("取消", "Cancel")}
        danger
        onCancel={() => setEditingCategory(null)}
        onConfirm={() => {
          const cat = editingCategory;
          setEditingCategory(null);
          if (cat) void deleteCategory(cat.id);
        }}
      />

      {creatingCategoryForItem && (
        <CategoryEditorDialog
          category={null}
          categories={categories}
          organic={!!organic}
          onClose={() => setCreatingCategoryForItem(null)}
          onSave={async (name, color) => {
            const ok = await createCategoryAndAssign(creatingCategoryForItem.id, name, color);
            if (ok) setCreatingCategoryForItem(null);
            else showSnack(tr("已有同名分类", "A category with this name already exists"));
          }}
        />
      )}

      <Snackbar message={snack} />
      <PageTutorialOverlay
        pageKey="thought"
        title={tr("小巧思", "Thoughts")}
        lines={[tr("长按条目查看更多操作。", "Long-press an entry for more actions.")]}
      />
    </div>
  );
}

function InsertLine() {
  return <div aria-hidden style={{ height: 2, background: "var(--dc-primary)", margin: "0 8px", borderRadius: 1 }} />;
}

function ThoughtRow(props: {
  item: FlashThought;
  index: number;
  fullMode: boolean;
  rowHeightPx: number;
  highlightBg: string;
  highlightFg: string;
  category: ThoughtCategory | undefined;
  single: boolean;
  isLast: boolean;
  dragging: boolean;
  showInsertAbove: boolean;
  showInsertBelow: boolean;
  onActivate: () => void;
  onActions: () => void;
  onDelete: () => void;
  onMoveRelative: (delta: number) => void;
  onDragStart: () => void;
  onDragEnd: () => void;
  onDragOverRow: (slot: number) => void;
}) {
  const item = props.item;
  const longPress = useLongPress(true, props.onActions);
  const fg = item.highlighted ? props.highlightFg : undefined;
  return (
    <div
      data-thought-id={item.id}
      onDragOver={(e) => {
        if (!props.single) return;
        e.preventDefault();
        const rect = e.currentTarget.getBoundingClientRect();
        const after = e.clientY > rect.top + rect.height / 2;
        props.onDragOverRow(after ? props.index + 1 : props.index);
      }}
      onDrop={(e) => {
        if (!props.single) return;
        e.preventDefault();
        props.onDragEnd();
      }}
    >
      {props.showInsertAbove && <InsertLine />}
      <div
        className="dc-card"
        role="button"
        tabIndex={0}
        onKeyDown={(e) => { if (e.key === "Enter") props.onActivate(); }}
        onClick={() => { if (!longPress.suppressClick()) props.onActivate(); }}
        {...longPress.props}
        style={{
          display: "flex", alignItems: "center", gap: 6,
          paddingLeft: 10, paddingRight: 2, paddingTop: 4, paddingBottom: 4,
          minHeight: props.rowHeightPx,
          maxHeight: props.fullMode ? undefined : props.rowHeightPx,
          background: item.highlighted ? props.highlightBg : undefined,
          borderColor: item.highlighted ? "transparent" : undefined,
          color: fg,
          opacity: props.dragging ? 0.62 : 1,
          cursor: "pointer",
        }}
      >
        {item.pinned && <IconPin size={16} style={{ color: "var(--dc-primary)", flexShrink: 0 }} aria-label={tr("已置顶", "Pinned")} />}
        {props.category && <CategoryColorDot colorArgb={props.category.colorArgb} />}
        <span
          className="dc-grow"
          style={{
            whiteSpace: props.fullMode ? "pre-wrap" : "nowrap",
            overflow: "hidden",
            textOverflow: props.fullMode ? "clip" : "ellipsis",
            wordBreak: "break-word",
          }}
        >
          {item.content}
        </span>
        <button
          className="dc-icon-btn"
          aria-label={tr("删除", "Delete")}
          title={tr("移入回收站", "Move to trash")}
          onClick={(e) => { e.stopPropagation(); props.onDelete(); }}
          style={{ color: fg }}
        >
          <IconDelete size={18} />
        </button>
        <span style={{ display: "flex", flexDirection: "column", flexShrink: 0 }}>
          <button
            className="dc-icon-btn"
            style={{ width: 26, height: 18, color: fg }}
            aria-label={tr("上移", "Move up")}
            disabled={!props.single || props.index === 0}
            onClick={(e) => { e.stopPropagation(); props.onMoveRelative(-1); }}
          >
            <IconUp size={14} />
          </button>
          <button
            className="dc-icon-btn"
            style={{ width: 26, height: 18, color: fg }}
            aria-label={tr("下移", "Move down")}
            disabled={!props.single || props.isLast}
            onClick={(e) => { e.stopPropagation(); props.onMoveRelative(1); }}
          >
            <IconDown size={14} />
          </button>
        </span>
        <span
          role="button"
          tabIndex={props.single ? 0 : -1}
          aria-label={tr("拖动排序（或聚焦后按 ↑/↓）", "Drag to reorder (or focus and press ↑/↓)")}
          title={tr("拖动排序；聚焦后可用 ↑/↓", "Drag to reorder; focus and press ↑/↓")}
          draggable={props.single}
          onDragStart={(e) => {
            if (!props.single) return;
            e.dataTransfer.effectAllowed = "move";
            e.dataTransfer.setData("text/plain", String(item.id));
            props.onDragStart();
          }}
          onDragEnd={props.onDragEnd}
          onKeyDown={(e) => {
            if (!props.single) return;
            if (e.key === "ArrowUp") { e.preventDefault(); props.onMoveRelative(-1); }
            if (e.key === "ArrowDown") { e.preventDefault(); props.onMoveRelative(1); }
          }}
          onClick={(e) => e.stopPropagation()}
          style={{
            display: "flex", alignItems: "center", padding: "8px 6px",
            cursor: props.single ? "grab" : "not-allowed",
            opacity: props.single ? 1 : 0.4,
            color: fg,
            touchAction: "none",
          }}
        >
          <IconGrip size={16} />
        </span>
      </div>
      {props.showInsertBelow && <InsertLine />}
    </div>
  );
}

function DrawerRow(props: {
  label: string;
  count: number;
  selected: boolean;
  leading: React.ReactNode;
  onClick: () => void;
  onLongPress?: () => void;
  onEdit?: () => void;
}) {
  const longPress = useLongPress(!!props.onLongPress, () => props.onLongPress?.());
  return (
    <div
      role="button"
      tabIndex={0}
      onKeyDown={(e) => { if (e.key === "Enter") props.onClick(); }}
      onClick={() => { if (!longPress.suppressClick()) props.onClick(); }}
      {...longPress.props}
      style={{
        display: "flex", alignItems: "center", gap: 12,
        padding: "12px 16px", borderRadius: 24, cursor: "pointer",
        background: props.selected ? "var(--dc-secondary-container)" : "transparent",
        color: props.selected ? "var(--dc-on-secondary-container)" : undefined,
      }}
    >
      <span style={{ width: 22, display: "inline-flex", justifyContent: "center", flexShrink: 0 }}>{props.leading}</span>
      <span className="dc-grow" style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{props.label}</span>
      <span className="dc-muted" style={{ fontSize: "0.82em" }}>{props.count}</span>
      {props.onEdit && (
        <button
          className="dc-icon-btn"
          style={{ width: 30, height: 30 }}
          aria-label={tr("编辑分类", "Edit category")}
          onClick={(e) => { e.stopPropagation(); props.onEdit?.(); }}
        >
          <IconEdit size={15} />
        </button>
      )}
    </div>
  );
}

function MenuAction(props: { icon: React.ReactNode; label: string; danger?: boolean; onClick: () => void }) {
  return (
    <button
      className="dc-btn"
      style={{ justifyContent: "flex-start", width: "100%", color: props.danger ? "var(--dc-error)" : undefined }}
      onClick={props.onClick}
    >
      {props.icon}
      {props.label}
    </button>
  );
}

function CategoryPickerDialog(props: {
  title: string;
  categories: ThoughtCategory[];
  currentCategoryId: number | null;
  onSelect: (categoryId: number | null) => void;
  onCreateNew?: () => void;
  onClose: () => void;
}) {
  return (
    <Modal open onClose={props.onClose} title={props.title} width={420}>
      <div className="dc-col" style={{ gap: 2, maxHeight: 420, overflowY: "auto" }}>
        <PickerRow
          label={tr("未分类", "Uncategorized")}
          selected={props.currentCategoryId == null}
          leading={<IconLabelOff size={18} />}
          onClick={() => props.onSelect(null)}
        />
        {props.categories.map((category) => (
          <PickerRow
            key={category.id}
            label={category.name}
            selected={props.currentCategoryId === category.id}
            leading={<CategoryColorDot colorArgb={category.colorArgb} />}
            onClick={() => props.onSelect(category.id)}
          />
        ))}
        {props.onCreateNew && (
          <PickerRow
            label={tr("新增分类…", "New category…")}
            selected={false}
            leading={<IconPlus size={18} />}
            onClick={props.onCreateNew}
          />
        )}
      </div>
      <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 12 }}>
        <button className="dc-btn" onClick={props.onClose}>{tr("取消", "Cancel")}</button>
      </div>
    </Modal>
  );
}

function PickerRow(props: { label: string; selected: boolean; leading: React.ReactNode; onClick: () => void }) {
  return (
    <button className="dc-btn" style={{ justifyContent: "flex-start", width: "100%" }} onClick={props.onClick}>
      <span style={{ width: 22, display: "inline-flex", justifyContent: "center", flexShrink: 0 }}>{props.leading}</span>
      <span className="dc-grow" style={{ textAlign: "left", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{props.label}</span>
      {props.selected && (
        <span className="dc-row" style={{ gap: 4, color: "var(--dc-primary)" }}>
          <IconCheck size={16} />
          <span style={{ fontSize: "0.82em" }}>{tr("当前分类", "Current category")}</span>
        </span>
      )}
    </button>
  );
}

function CategoryEditorDialog(props: {
  category: ThoughtCategory | null;
  categories: ThoughtCategory[];
  organic: boolean;
  onClose: () => void;
  onSave: (name: string, colorArgb: number) => Promise<void> | void;
  onDelete?: (category: ThoughtCategory) => void;
}) {
  const palette = props.organic ? ORGANIC_CATEGORY_COLORS : CATEGORY_COLORS;
  const [name, setName] = useState(props.category?.name ?? "");
  const [colorArgb, setColorArgb] = useState<number>(props.category?.colorArgb ?? palette[0]);
  const [showColorPicker, setShowColorPicker] = useState(false);
  const [saving, setSaving] = useState(false);

  const normalized = name.trim();
  const duplicate = props.categories.some(
    (c) => c.id !== props.category?.id && c.name.toLowerCase() === normalized.toLowerCase(),
  );
  const canSave = normalized.length > 0 && !duplicate && !saving;
  const availableColors = useMemo(() => {
    const list = [...palette];
    if (props.category) list.unshift(props.category.colorArgb);
    if (!list.includes(colorArgb)) list.push(colorArgb);
    return Array.from(new Set(list));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.category?.colorArgb, props.organic, colorArgb]);

  return (
    <>
      <Modal open onClose={() => { if (!saving) props.onClose(); }} title={props.category ? tr("编辑分类", "Edit category") : tr("新增分类", "New category")}>
        <label className="dc-col" style={{ gap: 6, alignItems: "stretch" }}>
          <span>{tr("分类名称", "Category name")}</span>
          <input
            className="dc-input"
            value={name}
            maxLength={40}
            onChange={(e) => setName(e.target.value.slice(0, 40))}
          />
        </label>
        {duplicate && <ErrorText error={tr("已有同名分类", "A category with this name already exists")} />}
        <div style={{ marginTop: 12, fontWeight: 600, fontSize: "0.9em" }}>{tr("分类颜色", "Category color")}</div>
        <div style={{ display: "flex", flexWrap: "wrap", gap: 10, marginTop: 10 }}>
          {availableColors.map((choice) => {
            const selectedColor = choice === colorArgb;
            const lum = argbLuminance(choice);
            return (
              <button
                key={choice}
                aria-label={"#" + (choice >>> 0).toString(16).padStart(8, "0").slice(2)}
                onClick={() => setColorArgb(choice)}
                style={{
                  width: 38, height: 38, borderRadius: "50%",
                  background: argbToCss(choice),
                  border: `${selectedColor ? 3 : 1}px solid ${selectedColor ? "var(--dc-on-surface)" : "var(--dc-outline-variant)"}`,
                  display: "flex", alignItems: "center", justifyContent: "center",
                  color: lum > 0.42 ? "#000" : "#fff",
                }}
              >
                {selectedColor && <IconCheck size={16} />}
              </button>
            );
          })}
          <button
            aria-label={tr("自定义颜色", "Custom color")}
            title={tr("自定义颜色", "Custom color")}
            onClick={() => setShowColorPicker(true)}
            style={{
              width: 38, height: 38, borderRadius: "50%",
              border: "1px solid var(--dc-outline)", background: "transparent",
              display: "flex", alignItems: "center", justifyContent: "center",
              color: "var(--dc-on-surface-variant)",
            }}
          >
            <IconPalette size={17} />
          </button>
        </div>
        <div className="dc-row" style={{ justifyContent: "space-between", marginTop: 16 }}>
          {props.category && props.onDelete ? (
            <button
              className="dc-btn"
              style={{ color: "var(--dc-error)" }}
              disabled={saving}
              onClick={() => props.onDelete?.(props.category!)}
            >
              <IconDelete size={17} /> {tr("删除", "Delete")}
            </button>
          ) : <span />}
          <div className="dc-row">
            <button className="dc-btn" disabled={saving} onClick={props.onClose}>{tr("取消", "Cancel")}</button>
            <button
              className="dc-btn dc-btn-filled"
              disabled={!canSave}
              onClick={async () => {
                setSaving(true);
                try {
                  await props.onSave(normalized, colorArgb);
                } finally {
                  setSaving(false);
                }
              }}
            >
              {saving ? tr("保存中…", "Saving…") : tr("保存", "Save")}
            </button>
          </div>
        </div>
      </Modal>
      {showColorPicker && (
        <SimpleColorPicker
          initialArgb={colorArgb}
          title={tr("选择颜色", "Choose color")}
          onCancel={() => setShowColorPicker(false)}
          onConfirm={(picked) => { setColorArgb(picked); setShowColorPicker(false); }}
        />
      )}
    </>
  );
}

/** Simple picker per web conventions: preset swatches + native color input + hex field. */
export function SimpleColorPicker(props: {
  initialArgb: number;
  title: string;
  onCancel: () => void;
  onConfirm: (argb: number) => void;
}) {
  const [hex, setHex] = useState("#" + (props.initialArgb >>> 0).toString(16).padStart(8, "0").slice(2));
  const parsed = /^#[0-9a-fA-F]{6}$/.test(hex.trim());
  return (
    <Modal open onClose={props.onCancel} title={props.title} width={360}>
      <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
        <span
          aria-hidden
          style={{ width: 44, height: 44, borderRadius: 10, background: parsed ? hex : "transparent", border: "1px solid var(--dc-outline-variant)", flexShrink: 0 }}
        />
        <input
          type="color"
          value={parsed ? hex : "#000000"}
          onChange={(e) => setHex(e.target.value)}
          aria-label={tr("取色器", "Color picker")}
          style={{ width: 52, height: 44, border: "none", background: "transparent", padding: 0 }}
        />
        <input
          className="dc-input"
          value={hex}
          onChange={(e) => setHex(e.target.value)}
          placeholder="#RRGGBB"
          aria-label={tr("十六进制颜色值", "Hex color")}
        />
      </div>
      {!parsed && <ErrorText error={tr("格式应为 #RRGGBB", "Format must be #RRGGBB")} />}
      <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 14 }}>
        <button className="dc-btn" onClick={props.onCancel}>{tr("取消", "Cancel")}</button>
        <button
          className="dc-btn dc-btn-filled"
          disabled={!parsed}
          onClick={() => props.onConfirm(parseInt(hex.trim().slice(1), 16) | 0xff000000)}
        >
          {tr("确定", "OK")}
        </button>
      </div>
    </Modal>
  );
}
