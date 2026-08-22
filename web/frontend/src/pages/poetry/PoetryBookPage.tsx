/**
 * 诗词本 (/poetry_book) — port of Android ui/poetry/PoetryBookScreen.kt.
 * Category filter chips, poetry typography from settings (font size, line
 * spacing, alignment, source line, quote mark, optional 7-char wrap), daily
 * poem highlight card, ⋮ actions (编辑/归类/置顶到第一位/删除), sort mode with
 * drag handles + up/down buttons, category manager and school preset import.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  BookOpen as IconBook, Check as IconCheck, ChevronDown as IconDown, ChevronUp as IconUp,
  FolderOpen as IconFolder, GripVertical as IconGrip, Tag as IconLabel, ListFilter as IconLabelOff,
  MoreVertical as IconMore, Palette as IconPalette,
  Pencil as IconEdit, Plus as IconPlus, Trash2 as IconDelete, Download as IconImport,
} from "lucide-react";
import { apiGet, apiSend } from "../../api/client";
import { useSettings } from "../../stores/settings";
import { tr } from "../../i18n/tr";
import {
  ConfirmDialog, EmptyState, ErrorText, Modal, PageTutorialOverlay, Snackbar, Spinner, TopBar, useSnackbar,
} from "../../components/ui";
import { arrayOf, argbLuminance, useLongPress } from "../thought/ThoughtPage";
import { SimpleColorPicker } from "../thought/ThoughtPage";
import { CategoryColorDot } from "../thought/ThoughtPage";

interface SavedPoem {
  id: number;
  content: string;
  source: string;
  createdAt: number;
  updatedAt: number;
  sortOrder: number;
  categoryId: number | null;
}

interface PoetryCategory {
  id: number;
  name: string;
  colorArgb: number;
  sortOrder: number;
  createdAt: number;
  updatedAt: number;
}

interface PoetryPreset {
  id: string;
  nameZh: string;
  nameEn: string;
  colorArgb: number;
  itemCount: number;
}

interface DailyPoem {
  content: string;
  source: string;
  dynasty?: string;
  title?: string;
}

type PoetryFilter =
  | { kind: "all" }
  | { kind: "uncategorized" }
  | { kind: "category"; id: number };

const POETRY_CATEGORY_COLORS = [0xffe05252, 0xffeb8c3a, 0xffe0b72f, 0xff4e9a62, 0xff3c9a9a, 0xff4c78c2, 0xff8166c2, 0xffc45e91, 0xff7b716a];
const POETRY_ORGANIC_CATEGORY_COLORS = [0xffc76b5c, 0xffca8b45, 0xff9d8a45, 0xff5d9168, 0xff4f8f8a, 0xff4f76a1, 0xff7166a4, 0xff98639a];
const MAX_POEM_CONTENT_CHARS = 4000;
const MAX_POEM_SOURCE_CHARS = 512;
const MAX_CATEGORY_NAME_CHARS = 100;

const POETRY_TRAILING_PUNCTUATION = new Set(
  "，。！？；：、,.!?;:”’）)》〉】〕".split(""),
);
const POETRY_CLAUSE_PUNCTUATION = new Set([
  ...POETRY_TRAILING_PUNCTUATION,
  ..."“‘（(《〈【〔—-",
]);

/** Port of PoetryTypography.kt isSevenCharacterPoem. */
export function isSevenCharacterPoem(text: string): boolean {
  const clauses = text
    .trim()
    .split(/[，。！？；：、,.!?;:]+/)
    .flatMap((segment) => segment.split("\n"))
    .map((clause) =>
      [...clause].filter((ch) => !/\s/.test(ch) && !POETRY_CLAUSE_PUNCTUATION.has(ch)).join(""),
    )
    .filter((clause) => clause.length > 0);
  return clauses.length >= 2 && clauses.every((clause) => [...clause].length === 7);
}

/** Port of PoetryTypography.kt wrapSevenCharacterVerse. */
export function wrapSevenCharacterVerse(text: string): string {
  return text.split("\n").map((line) => {
    if (line.trim().length === 0) return line;
    const chars = [...line];
    const output: string[] = [];
    let contentCount = 0;
    let pendingBreak = false;
    chars.forEach((character, index) => {
      const punctuation = POETRY_TRAILING_PUNCTUATION.has(character);
      if (pendingBreak && !punctuation) {
        output.push("\n");
        pendingBreak = false;
        contentCount = 0;
      }
      output.push(character);
      if (!punctuation && !/\s/.test(character)) {
        contentCount++;
        if (contentCount === 7 && index < chars.length - 1) pendingBreak = true;
      }
    });
    return output.join("");
  }).join("\n");
}

/** Extracts 《title》 from the canonical `author《title》` source label. */
export function poetryTitleFromSource(source: string): string {
  const start = source.indexOf("《");
  const end = start >= 0 ? source.indexOf("》", start + 1) : -1;
  return start >= 0 && end > start + 1 ? source.substring(start + 1, end).trim() : "";
}

export default function PoetryBookPage() {
  const settingsState = useSettings();
  const settings = settingsState.settings;
  const [snack, showSnack] = useSnackbar();

  const [poems, setPoems] = useState<SavedPoem[] | null>(null);
  const [categories, setCategories] = useState<PoetryCategory[]>([]);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [filter, setFilter] = useState<PoetryFilter>({ kind: "all" });

  const [daily, setDaily] = useState<DailyPoem | null>(null);
  const [sorting, setSorting] = useState(false);
  const [dragId, setDragId] = useState<number | null>(null);
  const [insertSlot, setInsertSlot] = useState<number | null>(null);
  const slotRef = useRef<number | null>(null);

  const [menuPoem, setMenuPoem] = useState<SavedPoem | null>(null);
  const [menuPos, setMenuPos] = useState<{ x: number; y: number } | null>(null);
  const [showNewEditor, setShowNewEditor] = useState(false);
  const [editPoem, setEditPoem] = useState<SavedPoem | null>(null);
  const [savingPoem, setSavingPoem] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<SavedPoem | null>(null);
  const [categorizingPoem, setCategorizingPoem] = useState<SavedPoem | null>(null);

  const [showCategoryManager, setShowCategoryManager] = useState(false);
  const [showAddCategoryChoice, setShowAddCategoryChoice] = useState(false);
  const [creatingCustomCategory, setCreatingCustomCategory] = useState(false);
  const [editingCategory, setEditingCategory] = useState<PoetryCategory | null>(null);
  const [pendingCategoryDelete, setPendingCategoryDelete] = useState<PoetryCategory | null>(null);
  const [showPresetPicker, setShowPresetPicker] = useState(false);
  const [presets, setPresets] = useState<PoetryPreset[] | null>(null);
  const [importingPresetId, setImportingPresetId] = useState<string | null>(null);

  const fail = useCallback((e: unknown) => {
    showSnack(tr("操作失败：", "Operation failed: ") + (e instanceof Error ? e.message : String(e)));
  }, [showSnack]);

  const reload = useCallback(async () => {
    try {
      const [p, c] = await Promise.all([
        apiGet<unknown>("/api/poetry/poems"),
        apiGet<unknown>("/api/poetry/categories"),
      ]);
      setPoems(arrayOf<SavedPoem>(p));
      setCategories(
        arrayOf<PoetryCategory>(c).sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id),
      );
      setLoadError(null);
    } catch (e) {
      setLoadError(e);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  // 每日诗词 highlight card at the top.
  useEffect(() => {
    let cancelled = false;
    apiGet<unknown>("/api/poetry/daily")
      .then((data) => {
        if (cancelled || !data || typeof data !== "object") return;
        const obj = data as Record<string, unknown>;
        const content = typeof obj.content === "string" ? obj.content : "";
        if (!content) return;
        setDaily({
          content,
          source: typeof obj.source === "string" ? obj.source : "",
          dynasty: typeof obj.dynasty === "string" ? obj.dynasty : undefined,
          title: typeof obj.title === "string" ? obj.title : undefined,
        });
      })
      .catch(() => undefined);
    return () => { cancelled = true; };
  }, []);

  const visiblePoems = useMemo(() => {
    if (!poems) return [];
    switch (filter.kind) {
      case "all": return poems;
      case "uncategorized": return poems.filter((p) => p.categoryId == null);
      case "category": return poems.filter((p) => p.categoryId === filter.id);
    }
  }, [poems, filter]);

  const categoriesById = useMemo(() => {
    const map = new Map<number, PoetryCategory>();
    for (const c of categories) map.set(c.id, c);
    return map;
  }, [categories]);

  /** Persists a new relative order of the currently visible subset. */
  const persistOrder = async (ordered: SavedPoem[]) => {
    try {
      await apiSend("/api/poetry/poems/reorder", "POST", ordered.map((p, i) => ({ id: p.id, sortOrder: i })));
      await reload();
    } catch (e) {
      fail(e);
    }
  };

  const moveRelative = (index: number, delta: number) => {
    const target = index + delta;
    if (target < 0 || target >= visiblePoems.length) return;
    const next = visiblePoems.slice();
    const [moved] = next.splice(index, 1);
    next.splice(target, 0, moved);
    void persistOrder(next);
  };

  const moveToFirst = (poem: SavedPoem) => {
    const index = visiblePoems.findIndex((p) => p.id === poem.id);
    if (index <= 0) return;
    const next = visiblePoems.slice();
    const [moved] = next.splice(index, 1);
    next.unshift(moved);
    void persistOrder(next);
  };

  const commitDrag = useCallback(() => {
    const sourceIndex = dragId != null ? visiblePoems.findIndex((p) => p.id === dragId) : -1;
    const slot = slotRef.current;
    setDragId(null);
    setInsertSlot(null);
    slotRef.current = null;
    if (sourceIndex < 0 || slot == null) return;
    const insertAt = slot > sourceIndex ? slot - 1 : slot;
    if (insertAt === sourceIndex) return;
    const next = visiblePoems.slice();
    const [moved] = next.splice(sourceIndex, 1);
    next.splice(insertAt, 0, moved);
    void persistOrder(next);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dragId, visiblePoems]);

  const setPoemCategory = async (poemId: number, categoryId: number | null) => {
    try {
      await apiSend(`/api/poetry/poems/${poemId}`, "PUT", { categoryId });
      await reload();
    } catch (e) { fail(e); }
  };

  const deletePoem = async (poemId: number) => {
    try {
      await apiSend(`/api/poetry/poems/${poemId}`, "DELETE");
      await reload();
    } catch (e) { fail(e); }
  };

  const createPoem = async (content: string, source: string, categoryId: number | null): Promise<boolean> => {
    try {
      await apiSend("/api/poetry/poems", "POST", { content, source, categoryId });
      await reload();
      return true;
    } catch (e) {
      fail(e);
      return false;
    }
  };

  const updatePoem = async (id: number, content: string, source: string, categoryId: number | null): Promise<boolean> => {
    try {
      await apiSend(`/api/poetry/poems/${id}`, "PUT", { content, source, categoryId });
      await reload();
      return true;
    } catch (e) {
      fail(e);
      return false;
    }
  };

  const createCategory = async (name: string, colorArgb: number): Promise<boolean> => {
    const normalized = name.trim();
    if (!normalized || categories.some((c) => c.name.toLowerCase() === normalized.toLowerCase())) return false;
    try {
      await apiSend("/api/poetry/categories", "POST", { name: normalized, colorArgb });
      await reload();
      return true;
    } catch (e) {
      fail(e);
      return false;
    }
  };

  const updateCategory = async (id: number, name: string, colorArgb: number): Promise<boolean> => {
    const normalized = name.trim();
    if (!normalized || categories.some((c) => c.id !== id && c.name.toLowerCase() === normalized.toLowerCase())) return false;
    try {
      await apiSend(`/api/poetry/categories/${id}`, "PUT", { name: normalized, colorArgb });
      await reload();
      return true;
    } catch (e) {
      fail(e);
      return false;
    }
  };

  // Backend contract: DELETE /api/poetry/categories/{id}?mode=keep|delete
  // (app/routers/poetry.py delete_category); keep → poems fall back to 未分类.
  const deleteCategoryApi = async (id: number, deletePoems: boolean) => {
    try {
      await apiSend(`/api/poetry/categories/${id}?mode=${deletePoems ? "delete" : "keep"}`, "DELETE");
      await reload();
    } catch (e) { fail(e); }
  };

  const loadPresets = useCallback(async () => {
    try {
      const data = await apiGet<unknown>("/api/poetry/presets");
      setPresets(arrayOf<PoetryPreset>(data));
    } catch (e) {
      setPresets([]);
      fail(e);
    }
  }, [fail]);

  useEffect(() => {
    if (showPresetPicker && presets == null) void loadPresets();
  }, [showPresetPicker, presets, loadPresets]);

  const importPreset = async (presetId: string) => {
    setImportingPresetId(presetId);
    try {
      const result = (await apiSend<Record<string, unknown>>("/api/poetry/presets/import", "POST", { presetId })) as Record<string, unknown>;
      const added = Number(result.addedCount ?? 0);
      const existing = Number(result.existingCount ?? 0);
      showSnack(tr(
        "预设导入完成：新增 ${a} 篇，跳过 ${b} 篇重复内容",
        "Preset imported: ${a} added, ${b} duplicates skipped",
      ).replace("${a}", String(added)).replace("${b}", String(existing)));
      await reload();
    } catch (e) {
      showSnack(tr("预设分类加载或导入失败", "Could not load or import the preset category"));
      fail(e);
    } finally {
      setImportingPresetId(null);
    }
  };

  if (!settings) return <Spinner />;

  const fontPx = Math.max(10, settings.poetryFontSizeSp) * (settings.fontScale || 1);
  const lineHeight = fontPx * Math.max(0.8, settings.poetryLineSpacing || 1);
  const center = settings.poetryTextAlignment === "CENTER";
  const organic = settings.visualStyle === "ORGANIC_FUTURE";

  const openMenuAt = (poem: SavedPoem, x: number, y: number) => {
    setMenuPoem(poem);
    setMenuPos({ x, y });
  };

  return (
    <div>
      <TopBar
        title={tr("诗词本", "Poetry book")}
        actions={
          <>
            <button
              className="dc-btn"
              onClick={() => { setSorting((s) => !s); setDragId(null); setInsertSlot(null); slotRef.current = null; }}
            >
              {sorting ? <IconCheck size={17} /> : <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden><path d="M4 7h10M4 12h16M4 17h7" /></svg>}
              {sorting ? tr("完成", "Done") : tr("排序", "Sort")}
            </button>
            <button
              className="dc-icon-btn"
              aria-label={tr("管理诗词分类", "Manage poetry categories")}
              onClick={() => setShowCategoryManager(true)}
            >
              <IconFolder size={20} />
            </button>
          </>
        }
      />

      {/* Category filter chips */}
      <div style={{ display: "flex", gap: 8, overflowX: "auto", padding: "6px 4px", alignItems: "center" }}>
        <button className={`dc-chip ${filter.kind === "all" ? "active" : ""}`} onClick={() => setFilter({ kind: "all" })}>
          {tr("全部", "All")}
        </button>
        <button className={`dc-chip ${filter.kind === "uncategorized" ? "active" : ""}`} onClick={() => setFilter({ kind: "uncategorized" })}>
          {tr("未分类", "Uncategorized")}
        </button>
        {categories.map((category) => (
          <button
            key={category.id}
            className={`dc-chip ${filter.kind === "category" && filter.id === category.id ? "active" : ""}`}
            onClick={() => setFilter({ kind: "category", id: category.id })}
          >
            <CategoryColorDot colorArgb={category.colorArgb} size={12} />
            {category.name}
          </button>
        ))}
        <button className="dc-chip" onClick={() => setShowCategoryManager(true)}>
          <IconPlus size={13} /> {tr("分类", "Categories")}
        </button>
      </div>

      {loadError != null && (
        <div className="dc-row">
          <ErrorText error={loadError} />
          <button className="dc-btn dc-btn-tonal" onClick={() => { setPoems(null); void reload(); }}>
            {tr("重试", "Retry")}
          </button>
        </div>
      )}
      {poems == null && loadError == null && <Spinner />}

      {/* Daily poem highlight card */}
      {daily && (
        <div
          className="dc-card"
          style={{
            margin: "8px 4px", padding: "14px 16px",
            background: "color-mix(in srgb, var(--dc-primary) 10%, var(--dc-surface-container))",
            borderColor: "color-mix(in srgb, var(--dc-primary) 40%, transparent)",
          }}
        >
          <div className="dc-chip" style={{ marginBottom: 8 }}>
            <IconBook size={13} /> {tr("每日诗词", "Daily poem")}
          </div>
          <div style={{ display: "flex", gap: 6 }}>
            {settings.poetryShowQuoteMark && (
              <span aria-hidden style={{ fontSize: fontPx * 1.6, lineHeight: 1, color: "var(--dc-primary)", fontWeight: 700 }}>“</span>
            )}
            <div className="dc-grow" style={{ minWidth: 0 }}>
              <div style={{
                fontSize: fontPx, lineHeight, fontWeight: 500,
                textAlign: center ? "center" : "start", whiteSpace: "pre-wrap",
              }}>
                {settings.poetrySevenCharacterWrapEnabled && isSevenCharacterPoem(daily.content)
                  ? wrapSevenCharacterVerse(daily.content)
                  : daily.content}
              </div>
              {settings.poetryShowSource && daily.source && (
                <div className="dc-muted" style={{ fontSize: "0.82em", fontStyle: "italic", marginTop: 8, textAlign: center ? "center" : "start" }}>
                  —— {daily.source}
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {poems != null && visiblePoems.length === 0 && (
        <EmptyState
          icon={<IconBook size={40} />}
          title={poems.length > 0 ? tr("这个分类还是空的", "This category is empty") : tr("诗词本还是空的", "Your poetry book is empty")}
          hint={poems.length > 0
            ? tr("添加诗词，或切换到其他分类", "Add a poem or choose another category")
            : tr("收藏每日诗词、手动添加，或从初高中预设导入", "Save a daily poem, add one manually, or import a school preset")}
        />
      )}

      <div className="dc-col" style={{ padding: "8px 4px 100px", gap: 10 }}>
        {visiblePoems.map((poem, index) => {
          if (sorting) {
            return (
              <div
                key={poem.id}
                onDragOver={(e) => {
                  e.preventDefault();
                  const rect = e.currentTarget.getBoundingClientRect();
                  const after = e.clientY > rect.top + rect.height / 2;
                  slotRef.current = after ? index + 1 : index;
                  setInsertSlot(after ? index + 1 : index);
                }}
                onDrop={(e) => { e.preventDefault(); commitDrag(); }}
              >
                {dragId != null && insertSlot === index && (
                  <div aria-hidden style={{ height: 2, background: "var(--dc-primary)", margin: "0 8px" }} />
                )}
                <div
                  className="dc-card"
                  style={{
                    display: "flex", alignItems: "center", gap: 8, padding: "10px 12px",
                    opacity: dragId === poem.id ? 0.62 : 1,
                  }}
                >
                  <span className="dc-grow" style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", fontSize: fontPx, fontWeight: 500 }}>
                    {(() => {
                      const t = poetryTitleFromSource(poem.source);
                      const body = poem.content.replace(/\s+/g, " ").trim();
                      return (t ? `《${t}》 ` : "") + body;
                    })()}
                  </span>
                  <span style={{ display: "flex", flexDirection: "column" }}>
                    <button className="dc-icon-btn" style={{ width: 26, height: 18 }} aria-label={tr("上移", "Move up")} disabled={index === 0} onClick={() => moveRelative(index, -1)}>
                      <IconUp size={14} />
                    </button>
                    <button className="dc-icon-btn" style={{ width: 26, height: 18 }} aria-label={tr("下移", "Move down")} disabled={index === visiblePoems.length - 1} onClick={() => moveRelative(index, 1)}>
                      <IconDown size={14} />
                    </button>
                  </span>
                  <span
                    role="button"
                    tabIndex={0}
                    aria-label={tr("拖动排序（或聚焦后按 ↑/↓）", "Drag to reorder (or focus and press ↑/↓)")}
                    title={tr("拖动排序；聚焦后可用 ↑/↓", "Drag to reorder; focus and press ↑/↓")}
                    draggable
                    onDragStart={(e) => {
                      e.dataTransfer.effectAllowed = "move";
                      e.dataTransfer.setData("text/plain", String(poem.id));
                      setDragId(poem.id);
                      slotRef.current = index;
                      setInsertSlot(null);
                    }}
                    onDragEnd={commitDrag}
                    onKeyDown={(e) => {
                      if (e.key === "ArrowUp") { e.preventDefault(); moveRelative(index, -1); }
                      if (e.key === "ArrowDown") { e.preventDefault(); moveRelative(index, 1); }
                    }}
                    style={{ display: "flex", padding: "8px 4px", cursor: "grab", touchAction: "none" }}
                  >
                    <IconGrip size={16} />
                  </span>
                </div>
                {index === visiblePoems.length - 1 && dragId != null && insertSlot === visiblePoems.length && (
                  <div aria-hidden style={{ height: 2, background: "var(--dc-primary)", margin: "0 8px" }} />
                )}
              </div>
            );
          }
          return (
            <PoemCard
              key={poem.id}
              poem={poem}
              category={poem.categoryId != null ? categoriesById.get(poem.categoryId) : undefined}
              fontPx={fontPx}
              lineHeight={lineHeight}
              center={center}
              showQuoteMark={settings.poetryShowQuoteMark}
              showSource={settings.poetryShowSource}
              sevenCharWrap={settings.poetrySevenCharacterWrapEnabled}
              onActions={(x, y) => openMenuAt(poem, x, y)}
            />
          );
        })}
      </div>

      <button
        className="dc-fab"
        aria-label={tr("添加诗词", "Add poem")}
        onClick={() => { setShowNewEditor(true); }}
      >
        <IconPlus size={24} />
      </button>

      {/* Poem ⋮ / long-press menu */}
      {menuPoem && menuPos && (
        <PopupMenu
          x={menuPos.x}
          y={menuPos.y}
          onClose={() => { setMenuPoem(null); setMenuPos(null); }}
          items={[
            { label: tr("编辑", "Edit"), onClick: () => { setEditPoem(menuPoem); setMenuPoem(null); setMenuPos(null); } },
            { label: tr("归类", "Categorize"), onClick: () => { setCategorizingPoem(menuPoem); setMenuPoem(null); setMenuPos(null); } },
            { label: tr("置顶到第一位", "Pin to the top"), onClick: () => { moveToFirst(menuPoem); setMenuPoem(null); setMenuPos(null); } },
            { label: tr("删除", "Delete"), danger: true, onClick: () => { setPendingDelete(menuPoem); setMenuPoem(null); setMenuPos(null); } },
          ]}
        />
      )}

      {/* Add / edit poem dialog */}
      {(showNewEditor || editPoem) && (
        <PoemEditorDialog
          poem={editPoem}
          categories={categories}
          initialCategoryId={editPoem ? editPoem.categoryId : (filter.kind === "category" ? filter.id : null)}
          saving={savingPoem}
          onClose={() => { if (!savingPoem) { setShowNewEditor(false); setEditPoem(null); } }}
          onConfirm={async (content, source, categoryId) => {
            setSavingPoem(true);
            try {
              const ok = editPoem
                ? await updatePoem(editPoem.id, content, source, categoryId)
                : await createPoem(content, source, categoryId);
              if (ok) { setShowNewEditor(false); setEditPoem(null); }
            } finally {
              setSavingPoem(false);
            }
          }}
        />
      )}

      {/* Change poem category */}
      {categorizingPoem && (
        <PoetryCategoryPickerDialog
          currentCategoryId={categorizingPoem.categoryId}
          categories={categories}
          onClose={() => setCategorizingPoem(null)}
          onSelect={(categoryId) => {
            const poem = categorizingPoem;
            setCategorizingPoem(null);
            void setPoemCategory(poem.id, categoryId);
          }}
        />
      )}

      {/* Delete poem confirm */}
      <ConfirmDialog
        open={pendingDelete != null}
        title={tr("删除这首诗词？", "Delete this poem?")}
        message={pendingDelete
          ? tr("删除后无法恢复。", "This cannot be undone.") + "\n\n" + pendingDelete.content.slice(0, 80)
          : undefined}
        confirmLabel={tr("删除", "Delete")}
        cancelLabel={tr("取消", "Cancel")}
        danger
        onCancel={() => setPendingDelete(null)}
        onConfirm={() => {
          const poem = pendingDelete;
          setPendingDelete(null);
          if (poem) void deletePoem(poem.id);
        }}
      />

      {/* Category manager */}
      <Modal open={showCategoryManager} onClose={() => setShowCategoryManager(false)} title={tr("诗词分类", "Poetry categories")} width={440}>
        <button
          className="dc-btn"
          style={{ width: "100%", justifyContent: "flex-start" }}
          onClick={() => { setShowCategoryManager(false); setShowAddCategoryChoice(true); }}
        >
          <IconPlus size={18} /> {tr("添加分类", "Add category")}
        </button>
        {categories.length === 0 && (
          <div className="dc-muted" style={{ padding: 12 }}>{tr("还没有分类", "No categories yet")}</div>
        )}
        {categories.map((category) => (
          <button
            key={category.id}
            className="dc-btn"
            style={{ width: "100%", justifyContent: "flex-start" }}
            onClick={() => { setShowCategoryManager(false); setEditingCategory(category); }}
          >
            <CategoryColorDot colorArgb={category.colorArgb} size={12} />
            <span className="dc-grow" style={{ textAlign: "left" }}>{category.name}</span>
            <IconEdit size={16} className="dc-muted" aria-label={tr("编辑", "Edit")} />
          </button>
        ))}
        <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 12 }}>
          <button className="dc-btn dc-btn-filled" onClick={() => setShowCategoryManager(false)}>{tr("完成", "Done")}</button>
        </div>
      </Modal>

      {/* Add-category choice */}
      <Modal open={showAddCategoryChoice} onClose={() => setShowAddCategoryChoice(false)} title={tr("添加分类", "Add category")} width={420}>
        <div className="dc-col" style={{ gap: 2 }}>
          <button
            className="dc-btn"
            style={{ width: "100%", justifyContent: "flex-start" }}
            onClick={() => { setShowAddCategoryChoice(false); setCreatingCustomCategory(true); }}
          >
            <IconPlus size={18} /> {tr("新建自定义分类", "Create custom category")}
          </button>
          <button
            className="dc-btn"
            style={{ width: "100%", justifyContent: "flex-start" }}
            onClick={() => { setShowAddCategoryChoice(false); setShowPresetPicker(true); }}
          >
            <IconBook size={18} /> {tr("选择初高中古诗文预设", "Choose a school poetry preset")}
          </button>
        </div>
        <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 12 }}>
          <button className="dc-btn" onClick={() => setShowAddCategoryChoice(false)}>{tr("取消", "Cancel")}</button>
        </div>
      </Modal>

      {/* Custom category editor */}
      {creatingCustomCategory && (
        <PoetryCategoryEditorDialog
          category={null}
          categories={categories}
          organic={organic}
          onClose={() => setCreatingCustomCategory(false)}
          onSave={async (name, color) => {
            const ok = await createCategory(name, color);
            if (ok) setCreatingCustomCategory(false);
            else showSnack(tr("已有同名分类", "A category with this name already exists"));
          }}
          onDelete={() => undefined}
        />
      )}

      {/* Edit category */}
      {editingCategory && (
        <PoetryCategoryEditorDialog
          category={editingCategory}
          categories={categories}
          organic={organic}
          onClose={() => setEditingCategory(null)}
          onSave={async (name, color) => {
            const ok = await updateCategory(editingCategory.id, name, color);
            if (ok) setEditingCategory(null);
            else showSnack(tr("已有同名分类", "A category with this name already exists"));
          }}
          onDelete={(cat) => { setEditingCategory(null); setPendingCategoryDelete(cat); }}
        />
      )}

      {/* Delete category choice */}
      <Modal
        open={pendingCategoryDelete != null}
        onClose={() => setPendingCategoryDelete(null)}
        title={tr("删除分类？", "Delete category?")}
        width={480}
      >
        {pendingCategoryDelete && (
          <>
            <div className="dc-muted">
              {tr(
                "“${name}”中有 ${count} 首诗词。请选择保留诗词并归入“未分类”，或将分类和其中诗词一起永久删除。",
                "“${name}” contains ${count} poems. Keep them as uncategorized, or permanently delete the category and its poems.",
              )
                .replace("${name}", pendingCategoryDelete.name)
                .replace("${count}", String(poems?.filter((p) => p.categoryId === pendingCategoryDelete.id).length ?? 0))}
            </div>
            <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 16, flexWrap: "wrap", gap: 6 }}>
              <button className="dc-btn" onClick={() => setPendingCategoryDelete(null)}>{tr("取消", "Cancel")}</button>
              <button
                className="dc-btn"
                onClick={() => { const cat = pendingCategoryDelete; setPendingCategoryDelete(null); void deleteCategoryApi(cat.id, false); }}
              >
                {tr("仅删除分类（诗词变为未分类）", "Delete category only")}
              </button>
              <button
                className="dc-btn dc-btn-danger"
                onClick={() => { const cat = pendingCategoryDelete; setPendingCategoryDelete(null); void deleteCategoryApi(cat.id, true); }}
              >
                {tr("分类和诗词一起删除", "Delete category and poems")}
              </button>
            </div>
          </>
        )}
      </Modal>

      {/* Preset picker */}
      <Modal
        open={showPresetPicker}
        onClose={() => { if (importingPresetId == null) setShowPresetPicker(false); }}
        title={tr("初高中古诗文预设", "School poetry presets")}
        width={480}
      >
        <div className="dc-muted" style={{ fontSize: "0.85em", marginBottom: 8 }}>
          {tr(
            "按教材册次导入；重复导入会跳过已有内容。教材版本调整时篇目可能有差异。",
            "Import by textbook volume. Existing entries are skipped; selections can vary by edition.",
          )}
        </div>
        <div className="dc-col" style={{ gap: 2, maxHeight: 480, overflowY: "auto" }}>
          {presets == null && <Spinner />}
          {presets != null && presets.length === 0 && (
            <div className="dc-muted" style={{ padding: 12 }}>{tr("暂无预设", "No presets available")}</div>
          )}
          {(presets ?? []).map((preset) => (
            <button
              key={preset.id}
              className="dc-btn"
              style={{ width: "100%", justifyContent: "flex-start" }}
              disabled={importingPresetId != null}
              onClick={() => void importPreset(preset.id)}
            >
              <CategoryColorDot colorArgb={preset.colorArgb} size={12} />
              <span className="dc-grow dc-col" style={{ alignItems: "flex-start", gap: 0, textAlign: "left" }}>
                <span style={{ fontWeight: 600 }}>{preset.nameZh}</span>
                <span className="dc-muted" style={{ fontSize: "0.8em" }}>
                  {preset.nameEn} · {preset.itemCount} {tr("篇", "items")}
                </span>
              </span>
              {importingPresetId === preset.id ? <Spinner size={18} /> : <IconImport size={17} aria-label={tr("导入", "Import")} />}
            </button>
          ))}
        </div>
        <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 12 }}>
          <button className="dc-btn" disabled={importingPresetId != null} onClick={() => setShowPresetPicker(false)}>
            {tr("取消", "Cancel")}
          </button>
        </div>
      </Modal>

      <Snackbar message={snack} />
      <PageTutorialOverlay
        pageKey="poetry_book"
        title={tr("诗词本", "Poetry book")}
        lines={[tr("收藏每日诗词或手动添加；分类在顶栏的文件夹图标里管理。", "Save the daily poem or add your own; manage categories from the folder icon in the top bar.")]}
      />
    </div>
  );
}

function PoemCard(props: {
  poem: SavedPoem;
  category: PoetryCategory | undefined;
  fontPx: number;
  lineHeight: number;
  center: boolean;
  showQuoteMark: boolean;
  showSource: boolean;
  sevenCharWrap: boolean;
  onActions: (x: number, y: number) => void;
}) {
  const longPress = useLongPress(true, () => props.onActions(window.innerWidth / 2, window.innerHeight / 3));
  const wrapped = props.sevenCharWrap && isSevenCharacterPoem(props.poem.content)
    ? wrapSevenCharacterVerse(props.poem.content)
    : props.poem.content;
  return (
    <div
      className="dc-card"
      tabIndex={0}
      aria-label={tr("显示编辑、分类和删除操作", "Show edit, category, and delete actions")}
      onKeyDown={(e) => { if (e.key === "Enter") props.onActions(window.innerWidth / 2, window.innerHeight / 3); }}
      {...longPress.props}
      style={{ padding: "13px 16px", cursor: "context-menu" }}
    >
      <div style={{ display: "flex", alignItems: "flex-start", gap: 6 }}>
        {props.showQuoteMark && (
          <span aria-hidden style={{ fontSize: props.fontPx * 1.6, lineHeight: 1, color: "var(--dc-primary)", fontWeight: 700 }}>“</span>
        )}
        <div className="dc-grow" style={{ minWidth: 0 }}>
          {props.category && (
            <div className="dc-row" style={{ gap: 5, marginBottom: 5 }}>
              <CategoryColorDot colorArgb={props.category.colorArgb} size={12} />
              <span className="dc-muted" style={{ fontSize: "0.75em" }}>{props.category.name}</span>
            </div>
          )}
          <div style={{
            fontSize: props.fontPx, lineHeight: props.lineHeight, fontWeight: 500,
            whiteSpace: "pre-wrap", textAlign: props.center ? "center" : "start",
            wordBreak: "break-word",
          }}>
            {wrapped}
          </div>
          {props.showSource && props.poem.source && (
            <div
              className="dc-muted"
              style={{
                fontSize: "0.82em", fontStyle: "italic", marginTop: 8,
                textAlign: props.center ? "center" : "start",
              }}
            >
              —— {props.poem.source}
            </div>
          )}
        </div>
        <button
          className="dc-icon-btn"
          aria-label={tr("诗词操作", "Poem actions")}
          aria-haspopup="menu"
          onClick={(e) => {
            e.stopPropagation();
            const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
            props.onActions(rect.left, rect.bottom + 4);
          }}
        >
          <IconMore size={18} />
        </button>
      </div>
    </div>
  );
}

function PoemEditorDialog(props: {
  poem: SavedPoem | null;
  categories: PoetryCategory[];
  initialCategoryId: number | null;
  saving: boolean;
  onClose: () => void;
  onConfirm: (content: string, source: string, categoryId: number | null) => Promise<void>;
}) {
  const [content, setContent] = useState(props.poem?.content ?? "");
  const [source, setSource] = useState(props.poem?.source ?? "");
  const [categoryId, setCategoryId] = useState<number | null>(props.initialCategoryId);
  const [pickerOpen, setPickerOpen] = useState(false);
  const saving = props.saving;
  const currentName = props.categories.find((c) => c.id === categoryId)?.name ?? tr("未分类", "Uncategorized");

  if (pickerOpen) {
    return (
      <PoetryCategoryPickerDialog
        currentCategoryId={categoryId}
        categories={props.categories}
        onClose={() => setPickerOpen(false)}
        onSelect={(id) => { setCategoryId(id); setPickerOpen(false); }}
      />
    );
  }

  return (
    <Modal
      open
      onClose={() => { if (!saving) props.onClose(); }}
      title={props.poem ? tr("编辑诗词", "Edit poem") : tr("添加诗词", "Add poem")}
      width={520}
    >
      <div className="dc-col" style={{ gap: 12 }}>
        <label className="dc-col" style={{ gap: 6, alignItems: "stretch" }}>
          <span>{tr("诗词正文", "Poem text")}</span>
          <textarea
            className="dc-input"
            rows={6}
            value={content}
            placeholder={tr("输入完整诗词正文", "Enter the complete poem text")}
            maxLength={MAX_POEM_CONTENT_CHARS}
            onChange={(e) => setContent(e.target.value.slice(0, MAX_POEM_CONTENT_CHARS))}
            style={{ resize: "vertical" }}
          />
        </label>
        <label className="dc-col" style={{ gap: 6, alignItems: "stretch" }}>
          <span>{tr("出处（可选）", "Source (optional)")}</span>
          <input
            className="dc-input"
            value={source}
            maxLength={MAX_POEM_SOURCE_CHARS}
            placeholder={tr("例如：李白《静夜思》", "e.g. Li Bai, Quiet Night Thought")}
            onChange={(e) => setSource(e.target.value.slice(0, MAX_POEM_SOURCE_CHARS))}
          />
        </label>
        <button className="dc-btn" style={{ justifyContent: "flex-start", width: "100%" }} onClick={() => setPickerOpen(true)}>
          <IconLabel size={17} />
          {tr("分类：", "Category: ") + currentName}
        </button>
      </div>
      <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 16 }}>
        <button className="dc-btn" disabled={saving} onClick={props.onClose}>{tr("取消", "Cancel")}</button>
        <button
          className="dc-btn dc-btn-filled"
          disabled={content.trim().length === 0 || saving}
          onClick={() => void props.onConfirm(content, source.trim(), categoryId)}
        >
          {saving ? tr("保存中…", "Saving…") : tr("保存", "Save")}
        </button>
      </div>
    </Modal>
  );
}

function PoetryCategoryPickerDialog(props: {
  currentCategoryId: number | null;
  categories: PoetryCategory[];
  onClose: () => void;
  onSelect: (categoryId: number | null) => void;
}) {
  return (
    <Modal open onClose={props.onClose} title={tr("选择分类", "Choose category")} width={420}>
      <div className="dc-col" style={{ gap: 2, maxHeight: 420, overflowY: "auto" }}>
        <button className="dc-btn" style={{ width: "100%", justifyContent: "flex-start" }} onClick={() => props.onSelect(null)}>
          <IconLabelOff size={17} />
          <span className="dc-grow" style={{ textAlign: "left" }}>{tr("未分类", "Uncategorized")}</span>
          {props.currentCategoryId == null && <IconCheck size={16} aria-label={tr("当前分类", "Current category")} />}
        </button>
        {props.categories.map((category) => (
          <button key={category.id} className="dc-btn" style={{ width: "100%", justifyContent: "flex-start" }} onClick={() => props.onSelect(category.id)}>
            <CategoryColorDot colorArgb={category.colorArgb} size={12} />
            <span className="dc-grow" style={{ textAlign: "left" }}>{category.name}</span>
            {props.currentCategoryId === category.id && <IconCheck size={16} aria-label={tr("当前分类", "Current category")} />}
          </button>
        ))}
      </div>
      <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 12 }}>
        <button className="dc-btn" onClick={props.onClose}>{tr("取消", "Cancel")}</button>
      </div>
    </Modal>
  );
}

function PoetryCategoryEditorDialog(props: {
  category: PoetryCategory | null;
  categories: PoetryCategory[];
  organic: boolean;
  onClose: () => void;
  onSave: (name: string, colorArgb: number) => Promise<void> | void;
  onDelete: (category: PoetryCategory) => void;
}) {
  const palette = props.organic ? POETRY_ORGANIC_CATEGORY_COLORS : POETRY_CATEGORY_COLORS;
  const [name, setName] = useState(props.category?.name ?? "");
  const [colorArgb, setColorArgb] = useState(props.category?.colorArgb ?? palette[0]);
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
            maxLength={MAX_CATEGORY_NAME_CHARS}
            onChange={(e) => setName(e.target.value.slice(0, MAX_CATEGORY_NAME_CHARS))}
          />
        </label>
        {duplicate && <ErrorText error={tr("已有同名分类", "A category with this name already exists")} />}
        <div style={{ marginTop: 12, fontWeight: 600, fontSize: "0.9em" }}>{tr("分类颜色", "Category color")}</div>
        <div style={{ display: "flex", flexWrap: "wrap", gap: 10, marginTop: 10 }}>
          {availableColors.map((choice) => {
            const selected = choice === colorArgb;
            const lum = argbLuminance(choice);
            return (
              <button
                key={choice}
                aria-label={"#" + (choice >>> 0).toString(16).padStart(8, "0").slice(2)}
                onClick={() => setColorArgb(choice)}
                style={{
                  width: 38, height: 38, borderRadius: "50%",
                  background: `rgb(${(choice >>> 16) & 255},${(choice >>> 8) & 255},${choice & 255})`,
                  border: `${selected ? 3 : 1}px solid ${selected ? "var(--dc-on-surface)" : "var(--dc-outline-variant)"}`,
                  display: "flex", alignItems: "center", justifyContent: "center",
                  color: lum > 0.42 ? "#000" : "#fff",
                }}
              >
                {selected && <IconCheck size={16} aria-label={tr("已选择", "Selected")} />}
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
        {props.category && (
          <button
            className="dc-btn"
            style={{ color: "var(--dc-error)", marginTop: 12 }}
            disabled={saving}
            onClick={() => props.onDelete(props.category!)}
          >
            <IconDelete size={17} /> {tr("删除分类", "Delete category")}
          </button>
        )}
        <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 12 }}>
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
      </Modal>
      {showColorPicker && (
        <SimpleColorPicker
          initialArgb={colorArgb}
          title={tr("分类颜色", "Category color")}
          onCancel={() => setShowColorPicker(false)}
          onConfirm={(picked) => { setColorArgb(picked); setShowColorPicker(false); }}
        />
      )}
    </>
  );
}

/** Local popup menu (shared PopupMenu lacks disabled support; this one is simple). */
function PopupMenu(props: {
  x: number;
  y: number;
  onClose: () => void;
  items: { label: string; onClick: () => void; danger?: boolean }[];
}) {
  const x = Math.min(props.x, window.innerWidth - 210);
  const y = Math.min(props.y, window.innerHeight - props.items.length * 44 - 20);
  return (
    <div style={{ position: "fixed", inset: 0, zIndex: 300 }} onClick={props.onClose} onContextMenu={(e) => { e.preventDefault(); props.onClose(); }}>
      <div
        className="dc-menu"
        role="menu"
        style={{ left: x, top: y }}
        onClick={(e) => e.stopPropagation()}
      >
        {props.items.map((item, i) => (
          <button
            key={i}
            role="menuitem"
            style={item.danger ? { color: "var(--dc-error)" } : undefined}
            onClick={() => { props.onClose(); item.onClick(); }}
          >
            {item.label}
          </button>
        ))}
      </div>
    </div>
  );
}
