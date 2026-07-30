import {
  closestCenter,
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core";
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import {
  ArchiveRestore,
  ArrowDown,
  ArrowUp,
  Download,
  GripVertical,
  Highlighter,
  Pencil,
  Pin,
  Plus,
  RotateCcw,
  Save,
  Search,
  Trash2,
  X,
} from "lucide-react";
import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";

import {
  compareDecimalI64,
  dateFromI64Milliseconds,
  thoughtApi,
  type DecimalI64,
  type Thought,
  type ThoughtCategory,
  type ThoughtDraft,
} from "../lib/ipc";

type Language = "zh" | "en";

const EMPTY_DRAFT: ThoughtDraft = {
  content: "",
  pinned: false,
  categoryId: null,
  highlighted: false,
};

function currentLanguage(): Language {
  return document.documentElement.lang.toLowerCase().startsWith("en") ? "en" : "zh";
}

function useDocumentLanguage(): Language {
  const [language, setLanguage] = useState<Language>(currentLanguage);

  useEffect(() => {
    const observer = new MutationObserver(() => setLanguage(currentLanguage()));
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["lang"] });
    return () => observer.disconnect();
  }, []);

  return language;
}

function argbToCss(argb: number): string {
  const normalized = argb >>> 0;
  const alpha = ((normalized >>> 24) & 0xff) / 255;
  const red = (normalized >>> 16) & 0xff;
  const green = (normalized >>> 8) & 0xff;
  const blue = normalized & 0xff;
  return `rgba(${red}, ${green}, ${blue}, ${alpha || 1})`;
}

function formatTime(value: DecimalI64, language: Language): string {
  const date = dateFromI64Milliseconds(value);
  if (!date) return "—";
  return new Intl.DateTimeFormat(language === "zh" ? "zh-CN" : "en-US", {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

interface SortableThoughtCardProps {
  thought: Thought;
  category: ThoughtCategory | undefined;
  index: number;
  total: number;
  showTrash: boolean;
  dragEnabled: boolean;
  language: Language;
  tr: (chinese: string, english: string) => string;
  onRestore: (thought: Thought) => void;
  onPermanentlyDelete: (thought: Thought) => void;
  onMove: (id: DecimalI64, direction: -1 | 1) => void;
  onPatch: (
    thought: Thought,
    patch: Partial<Pick<Thought, "pinned" | "highlighted" | "categoryId">>,
  ) => void;
  onEdit: (thought: Thought) => void;
  onTrash: (thought: Thought) => void;
}

function SortableThoughtCard({
  thought,
  category,
  index,
  total,
  showTrash,
  dragEnabled,
  language,
  tr,
  onRestore,
  onPermanentlyDelete,
  onMove,
  onPatch,
  onEdit,
  onTrash,
}: SortableThoughtCardProps) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: thought.id, disabled: !dragEnabled });
  const updatedDate = dateFromI64Milliseconds(thought.updatedAt);

  return (
    <li
      ref={setNodeRef}
      style={{
        transform: CSS.Transform.toString(transform),
        transition,
      }}
      className={[
        "panel",
        "thought-card",
        thought.highlighted ? "is-highlighted" : "",
        thought.pinned ? "is-pinned" : "",
        isDragging ? "is-dragging" : "",
      ]
        .filter(Boolean)
        .join(" ")}
    >
      <div className="thought-card-main">
        <div className="thought-meta">
          {thought.pinned && <Pin size={14} aria-label={tr("已置顶", "Pinned")} />}
          {category && (
            <span
              className="category-pill"
              style={{ borderColor: argbToCss(category.colorArgb) }}
            >
              {category.name}
            </span>
          )}
          <time dateTime={updatedDate?.toISOString()}>
            {formatTime(thought.updatedAt, language)}
          </time>
        </div>
        <p>{thought.content}</p>
      </div>
      <div className="thought-card-actions">
        {showTrash ? (
          <>
            <button
              className="button-ghost"
              type="button"
              onClick={() => onRestore(thought)}
            >
              <ArchiveRestore size={16} />
              {tr("恢复", "Restore")}
            </button>
            <button
              className="button-ghost danger"
              type="button"
              onClick={() => onPermanentlyDelete(thought)}
            >
              <Trash2 size={16} />
              {tr("永久删除", "Delete forever")}
            </button>
          </>
        ) : (
          <>
            <button
              {...attributes}
              {...listeners}
              className="icon-button drag-handle"
              type="button"
              disabled={!dragEnabled}
              title={
                dragEnabled
                  ? tr("拖动或用键盘排序", "Drag or use the keyboard to reorder")
                  : tr("清除搜索后可拖动", "Clear the search to drag")
              }
              aria-label={tr("拖动排序", "Drag to reorder")}
              style={{ touchAction: "none" }}
            >
              <GripVertical size={17} />
            </button>
            <button
              className="icon-button"
              type="button"
              disabled={index === 0}
              title={tr("上移", "Move up")}
              aria-label={tr("上移", "Move up")}
              onClick={() => onMove(thought.id, -1)}
            >
              <ArrowUp size={16} />
            </button>
            <button
              className="icon-button"
              type="button"
              disabled={index === total - 1}
              title={tr("下移", "Move down")}
              aria-label={tr("下移", "Move down")}
              onClick={() => onMove(thought.id, 1)}
            >
              <ArrowDown size={16} />
            </button>
            <button
              className={thought.pinned ? "icon-button is-active" : "icon-button"}
              type="button"
              title={tr("切换置顶", "Toggle pin")}
              aria-label={tr("切换置顶", "Toggle pin")}
              onClick={() => onPatch(thought, { pinned: !thought.pinned })}
            >
              <Pin size={16} />
            </button>
            <button
              className={thought.highlighted ? "icon-button is-active" : "icon-button"}
              type="button"
              title={tr("切换重点", "Toggle highlight")}
              aria-label={tr("切换重点", "Toggle highlight")}
              onClick={() => onPatch(thought, { highlighted: !thought.highlighted })}
            >
              <Highlighter size={16} />
            </button>
            <button
              className="icon-button"
              type="button"
              title={tr("编辑", "Edit")}
              aria-label={tr("编辑", "Edit")}
              onClick={() => onEdit(thought)}
            >
              <Pencil size={16} />
            </button>
            <button
              className="icon-button danger"
              type="button"
              title={tr("移到回收站", "Move to trash")}
              aria-label={tr("移到回收站", "Move to trash")}
              onClick={() => onTrash(thought)}
            >
              <Trash2 size={16} />
            </button>
          </>
        )}
      </div>
    </li>
  );
}

export default function ThoughtsPage() {
  const language = useDocumentLanguage();
  const tr = useCallback(
    (chinese: string, english: string) => (language === "zh" ? chinese : english),
    [language],
  );
  const [thoughts, setThoughts] = useState<Thought[]>([]);
  const [categories, setCategories] = useState<ThoughtCategory[]>([]);
  const [activeCategory, setActiveCategory] = useState<DecimalI64 | "all">("all");
  const [showTrash, setShowTrash] = useState(false);
  const [query, setQuery] = useState("");
  const [draft, setDraft] = useState<ThoughtDraft>(EMPTY_DRAFT);
  const [categoryName, setCategoryName] = useState("");
  const [categoryColor, setCategoryColor] = useState("#7c8f66");
  const [editingCategory, setEditingCategory] = useState<ThoughtCategory | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [nextThoughts, nextCategories] = await Promise.all([
        thoughtApi.list(true),
        thoughtApi.listCategories(),
      ]);
      setThoughts(nextThoughts);
      setCategories(nextCategories);
    } catch (reason) {
      setError(
        reason instanceof Error
          ? reason.message
          : tr("读取小巧思失败。", "Could not load thoughts."),
      );
    } finally {
      setLoading(false);
    }
  }, [tr]);

  useEffect(() => {
    void load();
  }, [load]);

  const visibleThoughts = useMemo(() => {
    const normalizedQuery = query.trim().toLocaleLowerCase();
    return thoughts
      .filter((thought) => (showTrash ? thought.deletedAt !== null : thought.deletedAt === null))
      .filter(
        (thought) =>
          activeCategory === "all" || thought.categoryId === activeCategory,
      )
      .filter(
        (thought) =>
          normalizedQuery.length === 0 ||
          thought.content.toLocaleLowerCase().includes(normalizedQuery),
      )
      .sort((left, right) => {
        if (left.pinned !== right.pinned) return left.pinned ? -1 : 1;
        const order = compareDecimalI64(left.sortOrder, right.sortOrder);
        return order || compareDecimalI64(right.updatedAt, left.updatedAt);
      });
  }, [activeCategory, query, showTrash, thoughts]);
  const dragEnabled = !showTrash && query.trim() === "";

  const resetEditor = () => setDraft(EMPTY_DRAFT);

  const editThought = (thought: Thought) => {
    setDraft({
      id: thought.id,
      content: thought.content,
      pinned: thought.pinned,
      categoryId: thought.categoryId,
      highlighted: thought.highlighted,
    });
    document.getElementById("thought-editor")?.focus();
  };

  const saveThought = async (event: FormEvent) => {
    event.preventDefault();
    const content = draft.content.trim();
    if (!content) {
      setError(tr("请输入小巧思内容。", "Write something first."));
      return;
    }
    setSaving(true);
    setError("");
    setNotice("");
    try {
      const saved = draft.id
        ? await thoughtApi.update(draft.id, { ...draft, content })
        : await thoughtApi.create({ ...draft, content });
      setThoughts((current) => {
        const withoutSaved = current.filter((item) => item.id !== saved.id);
        return [...withoutSaved, saved];
      });
      resetEditor();
      setNotice(tr("已保存。", "Saved."));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tr("保存失败。", "Save failed."));
    } finally {
      setSaving(false);
    }
  };

  const patchThought = async (
    thought: Thought,
    patch: Partial<Pick<Thought, "pinned" | "highlighted" | "categoryId">>,
  ) => {
    setError("");
    try {
      const updated = await thoughtApi.update(thought.id, {
        content: thought.content,
        pinned: patch.pinned ?? thought.pinned,
        highlighted: patch.highlighted ?? thought.highlighted,
        categoryId: patch.categoryId === undefined ? thought.categoryId : patch.categoryId,
      });
      setThoughts((current) => current.map((item) => (item.id === updated.id ? updated : item)));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tr("更新失败。", "Update failed."));
    }
  };

  const moveToTrash = async (thought: Thought) => {
    setError("");
    try {
      const updated = await thoughtApi.delete(thought.id, false);
      if (updated) {
        setThoughts((current) =>
          current.map((item) => (item.id === thought.id ? updated : item)),
        );
      }
      if (draft.id === thought.id) resetEditor();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tr("删除失败。", "Delete failed."));
    }
  };

  const restoreThought = async (thought: Thought) => {
    setError("");
    try {
      const restored = await thoughtApi.restore(thought.id);
      setThoughts((current) =>
        current.map((item) => (item.id === restored.id ? restored : item)),
      );
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tr("恢复失败。", "Restore failed."));
    }
  };

  const permanentlyDelete = async (thought: Thought) => {
    if (
      !window.confirm(
        tr("永久删除后无法恢复，确定继续吗？", "This cannot be undone. Delete permanently?"),
      )
    ) {
      return;
    }
    setError("");
    try {
      await thoughtApi.delete(thought.id, true);
      setThoughts((current) => current.filter((item) => item.id !== thought.id));
    } catch (reason) {
      setError(
        reason instanceof Error ? reason.message : tr("永久删除失败。", "Permanent delete failed."),
      );
    }
  };

  const persistOrder = async (ordered: Thought[]) => {
    const ids = ordered.map((thought) => thought.id);
    const optimistic = ordered.map((thought, index) => ({
      ...thought,
      sortOrder: String(index),
    }));
    setThoughts((current) => [
      ...current.filter((thought) => !ids.includes(thought.id)),
      ...optimistic,
    ]);
    try {
      await thoughtApi.reorder(ids);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tr("排序保存失败。", "Could not save order."));
      await load();
    }
  };

  const moveThought = async (id: DecimalI64, direction: -1 | 1) => {
    const index = visibleThoughts.findIndex((thought) => thought.id === id);
    const nextIndex = index + direction;
    if (index < 0 || nextIndex < 0 || nextIndex >= visibleThoughts.length) return;
    const reordered = [...visibleThoughts];
    const [moving] = reordered.splice(index, 1);
    reordered.splice(nextIndex, 0, moving);
    await persistOrder(reordered);
  };

  const finishDrag = async ({ active, over }: DragEndEvent) => {
    if (!dragEnabled || !over || active.id === over.id) return;
    const activeId = active.id as DecimalI64;
    const overId = over.id as DecimalI64;
    const sourceIndex = visibleThoughts.findIndex((thought) => thought.id === activeId);
    const targetIndex = visibleThoughts.findIndex((thought) => thought.id === overId);
    if (sourceIndex < 0 || targetIndex < 0) return;
    await persistOrder(arrayMove(visibleThoughts, sourceIndex, targetIndex));
  };

  const saveCategory = async (event: FormEvent) => {
    event.preventDefault();
    const name = categoryName.trim();
    if (!name) return;
    const colorArgb = Number.parseInt(`ff${categoryColor.slice(1)}`, 16) | 0;
    setError("");
    try {
      const category = editingCategory
        ? await thoughtApi.updateCategory(editingCategory.id, { name, colorArgb })
        : await thoughtApi.createCategory({ name, colorArgb });
      setCategories((current) => [
        ...current.filter((item) => item.id !== category.id),
        category,
      ]);
      setEditingCategory(null);
      setCategoryName("");
      setCategoryColor("#7c8f66");
    } catch (reason) {
      setError(
        reason instanceof Error ? reason.message : tr("分类保存失败。", "Could not save category."),
      );
    }
  };

  const startCategoryEdit = (category: ThoughtCategory) => {
    const rgb = (category.colorArgb >>> 0) & 0xffffff;
    setEditingCategory(category);
    setCategoryName(category.name);
    setCategoryColor(`#${rgb.toString(16).padStart(6, "0")}`);
  };

  const deleteCategory = async (category: ThoughtCategory) => {
    if (
      !window.confirm(
        tr(
          `删除分类“${category.name}”？其中的小巧思会保留并变为未分类。`,
          `Delete “${category.name}”? Its thoughts will remain uncategorized.`,
        ),
      )
    ) {
      return;
    }
    try {
      await thoughtApi.deleteCategory(category.id);
      setCategories((current) => current.filter((item) => item.id !== category.id));
      setThoughts((current) =>
        current.map((thought) =>
          thought.categoryId === category.id ? { ...thought, categoryId: null } : thought,
        ),
      );
      if (activeCategory === category.id) setActiveCategory("all");
    } catch (reason) {
      setError(
        reason instanceof Error ? reason.message : tr("分类删除失败。", "Could not delete category."),
      );
    }
  };

  const exportCategory = async (category: ThoughtCategory) => {
    setError("");
    try {
      const exported = await thoughtApi.exportCategory(category.id);
      if (exported) {
        setNotice(tr("分类文本已导出。", "Category text exported."));
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tr("导出失败。", "Export failed."));
    }
  };

  return (
    <main className="page-shell thoughts-page" aria-labelledby="thoughts-title">
      <header className="page-header">
        <div>
          <p className="eyebrow">{tr("随手记录，稍后整理", "Capture now, organize later")}</p>
          <h1 id="thoughts-title">{tr("小巧思", "Thoughts")}</h1>
        </div>
        <div className="page-actions">
          <button
            className={showTrash ? "button-secondary is-active" : "button-secondary"}
            type="button"
            onClick={() => {
              setShowTrash((value) => !value);
              resetEditor();
            }}
          >
            {showTrash ? <RotateCcw size={18} /> : <Trash2 size={18} />}
            {showTrash ? tr("返回小巧思", "Back to thoughts") : tr("回收站", "Trash")}
          </button>
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

      {!showTrash && (
        <form className="panel thought-editor" onSubmit={saveThought}>
          <div className="panel-heading">
            <h2>{draft.id ? tr("编辑小巧思", "Edit thought") : tr("快速记录", "Quick capture")}</h2>
            {draft.id && (
              <button className="button-ghost" type="button" onClick={resetEditor}>
                <X size={16} />
                {tr("取消编辑", "Cancel")}
              </button>
            )}
          </div>
          <label className="sr-only" htmlFor="thought-editor">
            {tr("小巧思内容", "Thought content")}
          </label>
          <textarea
            id="thought-editor"
            maxLength={65_536}
            rows={4}
            placeholder={tr("此刻在想什么？", "What is on your mind?")}
            value={draft.content}
            onChange={(event) => setDraft((current) => ({ ...current, content: event.target.value }))}
          />
          <div className="form-row form-row-wrap">
            <label>
              <span>{tr("分类", "Category")}</span>
              <select
                value={draft.categoryId ?? ""}
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    categoryId: event.target.value || null,
                  }))
                }
              >
                <option value="">{tr("未分类", "Uncategorized")}</option>
                {categories
                  .slice()
                  .sort((left, right) =>
                    compareDecimalI64(left.sortOrder, right.sortOrder),
                  )
                  .map((category) => (
                    <option key={category.id} value={category.id}>
                      {category.name}
                    </option>
                  ))}
              </select>
            </label>
            <label className="check-control">
              <input
                type="checkbox"
                checked={draft.pinned}
                onChange={(event) =>
                  setDraft((current) => ({ ...current, pinned: event.target.checked }))
                }
              />
              <Pin size={16} />
              {tr("置顶", "Pin")}
            </label>
            <label className="check-control">
              <input
                type="checkbox"
                checked={draft.highlighted}
                onChange={(event) =>
                  setDraft((current) => ({ ...current, highlighted: event.target.checked }))
                }
              />
              <Highlighter size={16} />
              {tr("重点", "Highlight")}
            </label>
            <button className="button-primary push-end" type="submit" disabled={saving}>
              <Save size={17} />
              {saving ? tr("保存中…", "Saving…") : tr("保存", "Save")}
            </button>
          </div>
        </form>
      )}

      <section className="thought-workspace">
        <aside className="panel category-panel" aria-label={tr("小巧思分类", "Thought categories")}>
          <button
            className={activeCategory === "all" ? "category-item is-active" : "category-item"}
            type="button"
            onClick={() => setActiveCategory("all")}
          >
            <span>{tr("全部", "All")}</span>
            <span>{thoughts.filter((item) => item.deletedAt === null).length}</span>
          </button>
          {categories
            .slice()
            .sort((left, right) =>
              compareDecimalI64(left.sortOrder, right.sortOrder),
            )
            .map((category) => (
              <div className="category-row" key={category.id}>
                <button
                  className={
                    activeCategory === category.id ? "category-item is-active" : "category-item"
                  }
                  type="button"
                  onClick={() => setActiveCategory(category.id)}
                >
                  <span
                    className="category-swatch"
                    style={{ backgroundColor: argbToCss(category.colorArgb) }}
                    aria-hidden="true"
                  />
                  <span>{category.name}</span>
                </button>
                <div className="inline-actions">
                  <button
                    className="icon-button"
                    type="button"
                    title={tr("导出分类", "Export category")}
                    onClick={() => void exportCategory(category)}
                  >
                    <Download size={15} />
                  </button>
                  <button
                    className="icon-button"
                    type="button"
                    title={tr("编辑分类", "Edit category")}
                    onClick={() => startCategoryEdit(category)}
                  >
                    <Pencil size={15} />
                  </button>
                  <button
                    className="icon-button danger"
                    type="button"
                    title={tr("删除分类", "Delete category")}
                    onClick={() => void deleteCategory(category)}
                  >
                    <Trash2 size={15} />
                  </button>
                </div>
              </div>
            ))}
          <form className="category-form" onSubmit={saveCategory}>
            <input
              aria-label={tr("分类名称", "Category name")}
              maxLength={80}
              placeholder={tr("新分类", "New category")}
              value={categoryName}
              onChange={(event) => setCategoryName(event.target.value)}
            />
            <input
              aria-label={tr("分类颜色", "Category color")}
              type="color"
              value={categoryColor}
              onChange={(event) => setCategoryColor(event.target.value)}
            />
            <button
              className="icon-button"
              type="submit"
              title={editingCategory ? tr("保存分类", "Save category") : tr("添加分类", "Add category")}
            >
              {editingCategory ? <Save size={17} /> : <Plus size={17} />}
            </button>
            {editingCategory && (
              <button
                className="icon-button"
                type="button"
                title={tr("取消", "Cancel")}
                onClick={() => {
                  setEditingCategory(null);
                  setCategoryName("");
                  setCategoryColor("#7c8f66");
                }}
              >
                <X size={17} />
              </button>
            )}
          </form>
        </aside>

        <div className="thought-list-column">
          <label className="search-field">
            <Search size={17} aria-hidden="true" />
            <span className="sr-only">{tr("搜索小巧思", "Search thoughts")}</span>
            <input
              type="search"
              value={query}
              placeholder={tr("搜索小巧思", "Search thoughts")}
              onChange={(event) => setQuery(event.target.value)}
            />
          </label>

          {loading ? (
            <div className="panel empty-state" role="status">
              {tr("正在读取小巧思…", "Loading thoughts…")}
            </div>
          ) : visibleThoughts.length === 0 ? (
            <div className="panel empty-state">
              <p>
                {showTrash
                  ? tr("回收站是空的。", "Trash is empty.")
                  : tr("这里还没有小巧思。", "No thoughts here yet.")}
              </p>
            </div>
          ) : (
            <DndContext
              sensors={sensors}
              collisionDetection={closestCenter}
              onDragEnd={(event) => void finishDrag(event)}
              accessibility={{
                screenReaderInstructions: {
                  draggable: tr(
                    "按空格拾取，用方向键移动，再按空格放下；按 Escape 取消。",
                    "Press space to pick up, use arrow keys to move, then press space to drop. Press Escape to cancel.",
                  ),
                },
              }}
            >
              <SortableContext
                items={visibleThoughts.map((thought) => thought.id)}
                strategy={verticalListSortingStrategy}
              >
                <ol className="thought-list" aria-label={tr("小巧思列表", "Thought list")}>
                  {visibleThoughts.map((thought, index) => (
                    <SortableThoughtCard
                      key={thought.id}
                      thought={thought}
                      category={categories.find((item) => item.id === thought.categoryId)}
                      index={index}
                      total={visibleThoughts.length}
                      showTrash={showTrash}
                      dragEnabled={dragEnabled}
                      language={language}
                      tr={tr}
                      onRestore={(item) => void restoreThought(item)}
                      onPermanentlyDelete={(item) => void permanentlyDelete(item)}
                      onMove={(id, direction) => void moveThought(id, direction)}
                      onPatch={(item, patch) => void patchThought(item, patch)}
                      onEdit={editThought}
                      onTrash={(item) => void moveToTrash(item)}
                    />
                  ))}
                </ol>
              </SortableContext>
            </DndContext>
          )}
        </div>
      </section>
    </main>
  );
}
