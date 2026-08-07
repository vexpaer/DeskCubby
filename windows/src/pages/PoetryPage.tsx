import * as Dialog from "@radix-ui/react-dialog";
import {
  ArrowDown,
  ArrowUp,
  BookHeart,
  Feather,
  FolderCog,
  LibraryBig,
  Pencil,
  Plus,
  RefreshCw,
  Save,
  Search,
  Trash2,
  X,
} from "lucide-react";
import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";

import {
  compareDecimalI64,
  homeApi,
  poetryApi,
  type DailyPoemResponse,
  type DecimalI64,
  type PoetryCategory,
  type PoetryMoveScope,
  type PoetryPresetSummary,
  type SavedPoem,
  type SavedPoemDraft,
} from "../lib/ipc";

type Language = "zh" | "en";
type CategoryFilter = PoetryMoveScope;

const EMPTY_DRAFT: SavedPoemDraft = { content: "", source: "", categoryId: null };
const DEFAULT_CATEGORY_COLOR = "#6750a4";

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

function argbToHex(value: number): string {
  const rgb = BigInt.asUintN(32, BigInt(value)) & 0x00ff_ffffn;
  return `#${rgb.toString(16).padStart(6, "0")}`;
}

function hexToArgb(value: string): number {
  const rgb = BigInt(`0x${value.slice(1)}`);
  return Number(BigInt.asIntN(32, 0xff00_0000n | rgb));
}

function categoryColor(value: number): string {
  return argbToHex(value);
}

export default function PoetryPage() {
  const language = useDocumentLanguage();
  const tr = useCallback(
    (chinese: string, english: string) => (language === "zh" ? chinese : english),
    [language],
  );
  const [dailyPoem, setDailyPoem] = useState<DailyPoemResponse | null>(null);
  const [poems, setPoems] = useState<SavedPoem[]>([]);
  const [categories, setCategories] = useState<PoetryCategory[]>([]);
  const [presets, setPresets] = useState<PoetryPresetSummary[]>([]);
  const [draft, setDraft] = useState<SavedPoemDraft>(EMPTY_DRAFT);
  const [selectedCategory, setSelectedCategory] = useState<CategoryFilter>("all");
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [moving, setMoving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [showCategoryEditor, setShowCategoryEditor] = useState(false);
  const [categoryId, setCategoryId] = useState<DecimalI64 | null>(null);
  const [categoryName, setCategoryName] = useState("");
  const [categoryColorHex, setCategoryColorHex] = useState(DEFAULT_CATEGORY_COLOR);
  const [showPresets, setShowPresets] = useState(false);
  const [importingPresetId, setImportingPresetId] = useState<string | null>(null);

  const orderedCategories = useMemo(
    () =>
      [...categories].sort(
        (left, right) =>
          compareDecimalI64(left.sortOrder, right.sortOrder) ||
          compareDecimalI64(left.id, right.id),
      ),
    [categories],
  );

  const orderedPoems = useMemo(
    () =>
      [...poems].sort(
        (left, right) =>
          compareDecimalI64(left.sortOrder, right.sortOrder) ||
          compareDecimalI64(left.id, right.id),
      ),
    [poems],
  );

  const loadLibrary = useCallback(async () => {
    try {
      const [saved, savedCategories, presetCatalog] = await Promise.all([
        poetryApi.list(),
        poetryApi.listCategories(),
        poetryApi.listPresets(),
      ]);
      setPoems(saved);
      setCategories(savedCategories);
      setPresets(presetCatalog);
    } catch (reason) {
      setError(
        reason instanceof Error ? reason.message : tr("读取诗词本失败。", "Could not load poems."),
      );
    }
  }, [tr]);

  const loadDaily = useCallback(
    async (forceRefresh: boolean) => {
      setRefreshing(true);
      try {
        setDailyPoem(await homeApi.dailyPoem(forceRefresh));
      } catch (reason) {
        setError(
          reason instanceof Error
            ? reason.message
            : tr("每日诗词暂时不可用。", "Daily poem is temporarily unavailable."),
        );
      } finally {
        setRefreshing(false);
      }
    },
    [tr],
  );

  useEffect(() => {
    let active = true;
    const initialLoad = async () => {
      setLoading(true);
      await Promise.all([loadLibrary(), loadDaily(false)]);
      if (active) setLoading(false);
    };
    void initialLoad();
    return () => {
      active = false;
    };
  }, [loadDaily, loadLibrary]);

  const scopedPoems = useMemo(
    () =>
      orderedPoems.filter((poem) => {
        if (selectedCategory === "all") return true;
        if (selectedCategory === "uncategorized") return poem.categoryId === null;
        return poem.categoryId === selectedCategory;
      }),
    [orderedPoems, selectedCategory],
  );

  const visiblePoems = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase();
    return scopedPoems.filter(
      (poem) =>
        normalized.length === 0 ||
        poem.content.toLocaleLowerCase().includes(normalized) ||
        poem.source.toLocaleLowerCase().includes(normalized),
    );
  }, [query, scopedPoems]);

  const dailySource = useMemo(() => {
    if (!dailyPoem) return "";
    if (dailyPoem.source) return dailyPoem.source;
    return [dailyPoem.dynasty, dailyPoem.author, dailyPoem.title].filter(Boolean).join(" · ");
  }, [dailyPoem]);

  const selectedDraftCategory = (): DecimalI64 | null =>
    selectedCategory === "all" || selectedCategory === "uncategorized"
      ? null
      : selectedCategory;

  const resetEditor = () =>
    setDraft({ ...EMPTY_DRAFT, categoryId: selectedDraftCategory() });

  const savePoem = async (event: FormEvent) => {
    event.preventDefault();
    const content = draft.content.trim();
    const source = draft.source.trim();
    if (!content) {
      setError(tr("诗词正文不能为空。", "Poem text cannot be empty."));
      return;
    }
    setSaving(true);
    setError("");
    setNotice("");
    try {
      const saved = draft.id
        ? await poetryApi.update(draft.id, { content, source, categoryId: draft.categoryId })
        : await poetryApi.create({ content, source, categoryId: draft.categoryId });
      setPoems((current) => [...current.filter((item) => item.id !== saved.id), saved]);
      resetEditor();
      setNotice(tr("已保存到诗词本。", "Saved to poetry book."));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tr("保存失败。", "Save failed."));
    } finally {
      setSaving(false);
    }
  };

  const saveDailyPoem = async () => {
    if (!dailyPoem) return;
    setError("");
    try {
      const saved = await poetryApi.create({
        content: dailyPoem.content,
        source: dailySource,
        categoryId: selectedDraftCategory(),
      });
      setPoems((current) => [...current.filter((item) => item.id !== saved.id), saved]);
      setNotice(tr("每日诗词已加入诗词本。", "Daily poem added to your book."));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tr("保存失败。", "Save failed."));
    }
  };

  const editPoem = (poem: SavedPoem) => {
    setDraft({
      id: poem.id,
      content: poem.content,
      source: poem.source,
      categoryId: poem.categoryId,
    });
    document.getElementById("poem-content")?.focus();
  };

  const deletePoem = async (poem: SavedPoem) => {
    if (!window.confirm(tr("从诗词本中删除这首诗？", "Remove this poem from your book?"))) return;
    setError("");
    try {
      await poetryApi.delete(poem.id);
      setPoems((current) => current.filter((item) => item.id !== poem.id));
      if (draft.id === poem.id) resetEditor();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tr("删除失败。", "Delete failed."));
    }
  };

  const changePoemCategory = async (poem: SavedPoem, nextCategoryId: DecimalI64 | null) => {
    setError("");
    try {
      await poetryApi.setCategory(poem.id, nextCategoryId);
      setPoems((current) =>
        current.map((item) =>
          item.id === poem.id ? { ...item, categoryId: nextCategoryId } : item,
        ),
      );
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tr("分类失败。", "Could not assign category."));
    }
  };

  const movePoem = async (poem: SavedPoem, direction: -1 | 1) => {
    const currentIndex = scopedPoems.findIndex((item) => item.id === poem.id);
    const targetIndex = currentIndex + direction;
    if (currentIndex < 0 || targetIndex < 0 || targetIndex >= scopedPoems.length) return;
    setMoving(true);
    setError("");
    try {
      await poetryApi.move(poem.id, targetIndex, selectedCategory);
      await loadLibrary();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tr("排序失败。", "Could not reorder poems."));
    } finally {
      setMoving(false);
    }
  };

  const openNewCategory = () => {
    setCategoryId(null);
    setCategoryName("");
    setCategoryColorHex(DEFAULT_CATEGORY_COLOR);
    setShowCategoryEditor(true);
  };

  const openEditCategory = (category: PoetryCategory) => {
    setCategoryId(category.id);
    setCategoryName(category.name);
    setCategoryColorHex(argbToHex(category.colorArgb));
    setShowCategoryEditor(true);
  };

  const saveCategory = async (event: FormEvent) => {
    event.preventDefault();
    const name = categoryName.trim();
    if (!name) return;
    setSaving(true);
    setError("");
    try {
      const saved = categoryId
        ? await poetryApi.updateCategory(categoryId, {
            name,
            colorArgb: hexToArgb(categoryColorHex),
          })
        : await poetryApi.createCategory({ name, colorArgb: hexToArgb(categoryColorHex) });
      setCategories((current) => [
        ...current.filter((category) => category.id !== saved.id),
        saved,
      ]);
      setSelectedCategory(saved.id);
      setShowCategoryEditor(false);
      setNotice(categoryId ? tr("分类已更新。", "Category updated.") : tr("分类已创建。", "Category created."));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tr("保存分类失败。", "Could not save category."));
    } finally {
      setSaving(false);
    }
  };

  const deleteCategory = async (deletePoems: boolean) => {
    if (!categoryId) return;
    const prompt = deletePoems
      ? tr("删除分类及其中全部诗词？此操作无法撤销。", "Delete this category and every poem in it? This cannot be undone.")
      : tr("删除分类但保留其中诗词？诗词将变为未分类。", "Delete this category but keep its poems as uncategorized?");
    if (!window.confirm(prompt)) return;
    setSaving(true);
    setError("");
    try {
      await poetryApi.deleteCategory(categoryId, deletePoems);
      setShowCategoryEditor(false);
      setSelectedCategory("all");
      await loadLibrary();
      setNotice(tr("分类已删除。", "Category deleted."));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tr("删除分类失败。", "Could not delete category."));
    } finally {
      setSaving(false);
    }
  };

  const moveCategory = async (category: PoetryCategory, direction: -1 | 1) => {
    const currentIndex = orderedCategories.findIndex((item) => item.id === category.id);
    const targetIndex = currentIndex + direction;
    if (currentIndex < 0 || targetIndex < 0 || targetIndex >= orderedCategories.length) return;
    setMoving(true);
    try {
      await poetryApi.moveCategory(category.id, targetIndex);
      await loadLibrary();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tr("分类排序失败。", "Could not reorder categories."));
    } finally {
      setMoving(false);
    }
  };

  const importPreset = async (preset: PoetryPresetSummary) => {
    setImportingPresetId(preset.id);
    setError("");
    try {
      const result = await poetryApi.importPreset(preset.id);
      await loadLibrary();
      setSelectedCategory(result.categoryId);
      setShowPresets(false);
      setNotice(
        tr(
          `已导入 ${result.addedCount} 篇，跳过 ${result.existingCount} 篇已有内容。`,
          `Imported ${result.addedCount}; skipped ${result.existingCount} existing items.`,
        ),
      );
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tr("导入失败。", "Import failed."));
    } finally {
      setImportingPresetId(null);
    }
  };

  return (
    <main className="page-shell poetry-page" aria-labelledby="poetry-title">
      <header className="page-header">
        <div>
          <p className="eyebrow">{tr("每日一句，也收藏整首", "A line for today, a poem to keep")}</p>
          <h1 id="poetry-title">{tr("诗词本", "Poetry book")}</h1>
        </div>
        <div className="page-actions">
          <button className="button-secondary" type="button" onClick={() => setShowPresets(true)}>
            <LibraryBig size={18} />
            {tr("教材预设", "School presets")}
          </button>
          <button className="button-primary" type="button" onClick={() => document.getElementById("poem-content")?.focus()}>
            <Plus size={18} />
            {tr("新增诗词", "New poem")}
          </button>
        </div>
      </header>

      {(error || notice) && (
        <div className={error ? "status-banner error" : "status-banner success"} role="status">
          <span>{error || notice}</span>
          <button className="icon-button" type="button" aria-label={tr("关闭提示", "Dismiss")} onClick={() => { setError(""); setNotice(""); }}>
            <X size={16} />
          </button>
        </div>
      )}

      <section className="panel poetry-categories" aria-labelledby="poetry-categories-title">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">{tr("筛选与管理", "Filter and organize")}</p>
            <h2 id="poetry-categories-title">{tr("分类", "Categories")}</h2>
          </div>
          <button className="button-ghost" type="button" onClick={openNewCategory}>
            <Plus size={17} />
            {tr("新建分类", "New category")}
          </button>
        </div>
        <div className="poetry-category-list" aria-label={tr("诗词分类", "Poetry categories")}>
          <button className={selectedCategory === "all" ? "category-chip selected" : "category-chip"} type="button" onClick={() => setSelectedCategory("all")}>
            {tr("全部", "All")} <span>{poems.length}</span>
          </button>
          <button className={selectedCategory === "uncategorized" ? "category-chip selected" : "category-chip"} type="button" onClick={() => setSelectedCategory("uncategorized")}>
            {tr("未分类", "Uncategorized")} <span>{poems.filter((poem) => poem.categoryId === null).length}</span>
          </button>
          {orderedCategories.map((category, index) => (
            <div className="poetry-category-item" key={category.id}>
              <button className={selectedCategory === category.id ? "category-chip selected" : "category-chip"} type="button" onClick={() => setSelectedCategory(category.id)}>
                <span className="category-color-dot" style={{ backgroundColor: categoryColor(category.colorArgb) }} aria-hidden="true" />
                {category.name}
                <span>{poems.filter((poem) => poem.categoryId === category.id).length}</span>
              </button>
              <div className="inline-actions">
                <button className="icon-button" type="button" disabled={moving || index === 0} aria-label={tr(`上移分类 ${category.name}`, `Move ${category.name} up`)} onClick={() => void moveCategory(category, -1)}><ArrowUp size={15} /></button>
                <button className="icon-button" type="button" disabled={moving || index === orderedCategories.length - 1} aria-label={tr(`下移分类 ${category.name}`, `Move ${category.name} down`)} onClick={() => void moveCategory(category, 1)}><ArrowDown size={15} /></button>
                <button className="icon-button" type="button" aria-label={tr(`编辑分类 ${category.name}`, `Edit ${category.name}`)} onClick={() => openEditCategory(category)}><FolderCog size={15} /></button>
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="panel daily-poem" aria-labelledby="daily-poem-title">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">{tr("在线失败时使用本地缓存或内置内容", "Cached locally for offline use")}</p>
            <h2 id="daily-poem-title">{dailyPoem?.title || tr("每日诗词", "Daily poem")}</h2>
          </div>
          <button className="button-ghost" type="button" disabled={refreshing} onClick={() => void loadDaily(true)}>
            <RefreshCw className={refreshing ? "spin" : ""} size={17} />
            {refreshing ? tr("刷新中…", "Refreshing…") : tr("换一句", "Refresh")}
          </button>
        </div>
        {dailyPoem ? (
          <>
            <blockquote>{dailyPoem.content}</blockquote>
            <div className="daily-poem-footer">
              <div>
                {dailySource && <cite>{dailySource}</cite>}
                {(dailyPoem.fromCache || dailyPoem.usedFallback) && (
                  <span className="source-badge">{dailyPoem.usedFallback ? tr("内置回退", "Built-in fallback") : tr("本地缓存", "Local cache")}</span>
                )}
              </div>
              <button className="button-secondary" type="button" onClick={() => void saveDailyPoem()}>
                <BookHeart size={17} />
                {tr("加入当前分类", "Save to current category")}
              </button>
            </div>
          </>
        ) : (
          <div className="empty-state" role="status">{refreshing ? tr("正在取一首诗…", "Finding a poem…") : tr("暂无每日诗词。", "No daily poem available.")}</div>
        )}
      </section>

      <form className="panel poem-editor" onSubmit={savePoem}>
        <div className="panel-heading">
          <h2>{draft.id ? tr("编辑诗词", "Edit poem") : tr("手动收藏", "Add manually")}</h2>
          {draft.id && <button className="button-ghost" type="button" onClick={resetEditor}><X size={16} />{tr("取消编辑", "Cancel")}</button>}
        </div>
        <div className="form-grid poem-editor-fields">
          <label className="form-span">
            <span>{tr("正文", "Text")}</span>
            <textarea id="poem-content" maxLength={4_000} rows={6} required value={draft.content} placeholder={tr("输入诗词正文，可保留换行", "Enter the poem; line breaks are preserved")} onChange={(event) => setDraft((current) => ({ ...current, content: event.target.value }))} />
          </label>
          <label>
            <span>{tr("出处（可选）", "Source (optional)")}</span>
            <input maxLength={512} value={draft.source} placeholder={tr("朝代 · 作者 · 篇名", "Dynasty · Author · Title")} onChange={(event) => setDraft((current) => ({ ...current, source: event.target.value }))} />
          </label>
          <label>
            <span>{tr("分类", "Category")}</span>
            <select value={draft.categoryId ?? ""} onChange={(event) => setDraft((current) => ({ ...current, categoryId: event.target.value || null }))}>
              <option value="">{tr("未分类", "Uncategorized")}</option>
              {orderedCategories.map((category) => <option value={category.id} key={category.id}>{category.name}</option>)}
            </select>
          </label>
          <button className="button-primary align-end" type="submit" disabled={saving}><Save size={17} />{saving ? tr("保存中…", "Saving…") : tr("保存", "Save")}</button>
        </div>
      </form>

      <section aria-label={tr("已收藏诗词", "Saved poems") }>
        <label className="search-field">
          <Search size={17} aria-hidden="true" />
          <span className="sr-only">{tr("搜索诗词", "Search poems")}</span>
          <input type="search" value={query} placeholder={tr("搜索正文或出处", "Search text or source")} onChange={(event) => setQuery(event.target.value)} />
        </label>

        {loading ? (
          <div className="panel empty-state" role="status">{tr("正在读取诗词本…", "Loading poetry book…")}</div>
        ) : visiblePoems.length === 0 ? (
          <div className="panel empty-state"><Feather size={28} aria-hidden="true" /><p>{tr("当前分类还没有诗词。", "There are no poems in this view.")}</p></div>
        ) : (
          <div className="poem-grid">
            {visiblePoems.map((poem) => {
              const scopedIndex = scopedPoems.findIndex((item) => item.id === poem.id);
              return (
                <article className="panel poem-card" key={poem.id}>
                  <blockquote>{poem.content}</blockquote>
                  <footer>
                    <div className="poem-card-meta">
                      <cite>{poem.source || tr("未填写出处", "Source not specified")}</cite>
                      <select aria-label={tr("诗词分类", "Poem category")} value={poem.categoryId ?? ""} onChange={(event) => void changePoemCategory(poem, event.target.value || null)}>
                        <option value="">{tr("未分类", "Uncategorized")}</option>
                        {orderedCategories.map((category) => <option value={category.id} key={category.id}>{category.name}</option>)}
                      </select>
                    </div>
                    <div className="inline-actions">
                      <button className="icon-button" type="button" disabled={moving || query.trim().length > 0 || scopedIndex === 0} aria-label={tr("上移诗词", "Move poem up")} onClick={() => void movePoem(poem, -1)}><ArrowUp size={17} /></button>
                      <button className="icon-button" type="button" disabled={moving || query.trim().length > 0 || scopedIndex === scopedPoems.length - 1} aria-label={tr("下移诗词", "Move poem down")} onClick={() => void movePoem(poem, 1)}><ArrowDown size={17} /></button>
                      <button className="icon-button" type="button" aria-label={tr("编辑诗词", "Edit poem")} onClick={() => editPoem(poem)}><Pencil size={17} /></button>
                      <button className="icon-button danger" type="button" aria-label={tr("删除诗词", "Delete poem")} onClick={() => void deletePoem(poem)}><Trash2 size={17} /></button>
                    </div>
                  </footer>
                </article>
              );
            })}
          </div>
        )}
      </section>

      <Dialog.Root open={showCategoryEditor} onOpenChange={(open) => !open && !saving && setShowCategoryEditor(false)}>
        <Dialog.Portal>
          <Dialog.Overlay className="dialog-overlay" />
          <Dialog.Content className="dialog-content poetry-dialog" onEscapeKeyDown={(event) => saving && event.preventDefault()} onPointerDownOutside={(event) => saving && event.preventDefault()}>
          <form onSubmit={saveCategory}>
            <div className="panel-heading">
              <Dialog.Title>{categoryId ? tr("编辑分类", "Edit category") : tr("新建分类", "New category")}</Dialog.Title>
              <Dialog.Close className="icon-button" type="button" disabled={saving} aria-label={tr("关闭", "Close")}><X size={17} /></Dialog.Close>
            </div>
            <Dialog.Description className="sr-only">{tr("设置分类名称和颜色。", "Set the category name and color.")}</Dialog.Description>
            <label><span>{tr("分类名称", "Category name")}</span><input autoFocus required maxLength={100} value={categoryName} onChange={(event) => setCategoryName(event.target.value)} /></label>
            <label><span>{tr("分类颜色", "Category color")}</span><input type="color" value={categoryColorHex} onChange={(event) => setCategoryColorHex(event.target.value)} /></label>
            <div className="page-actions">
              {categoryId && <><button className="button-ghost danger" type="button" disabled={saving} onClick={() => void deleteCategory(false)}>{tr("删除分类，保留诗词", "Delete, keep poems")}</button><button className="button-ghost danger" type="button" disabled={saving} onClick={() => void deleteCategory(true)}>{tr("连同诗词删除", "Delete with poems")}</button></>}
              <button className="button-primary" type="submit" disabled={saving}><Save size={17} />{tr("保存", "Save")}</button>
            </div>
          </form>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>

      <Dialog.Root open={showPresets} onOpenChange={(open) => !open && importingPresetId === null && setShowPresets(false)}>
        <Dialog.Portal>
          <Dialog.Overlay className="dialog-overlay" />
          <Dialog.Content className="dialog-content poetry-dialog poetry-preset-dialog" onEscapeKeyDown={(event) => importingPresetId !== null && event.preventDefault()} onPointerDownOutside={(event) => importingPresetId !== null && event.preventDefault()}>
            <div className="panel-heading">
              <div><Dialog.Title>{tr("初高中古诗文预设", "School poetry presets")}</Dialog.Title><Dialog.Description>{tr("按教材册次导入；重复内容会自动跳过。", "Import by textbook volume; existing entries are skipped.")}</Dialog.Description></div>
              <Dialog.Close className="icon-button" type="button" disabled={importingPresetId !== null} aria-label={tr("关闭", "Close")}><X size={17} /></Dialog.Close>
            </div>
            <div className="poetry-preset-list">
              {presets.map((preset) => (
                <button className="poetry-preset-item" type="button" disabled={importingPresetId !== null} key={preset.id} onClick={() => void importPreset(preset)}>
                  <span className="category-color-dot" style={{ backgroundColor: categoryColor(preset.colorArgb) }} aria-hidden="true" />
                  <span><strong>{language === "zh" ? preset.nameZh : preset.nameEn}</strong><small>{language === "zh" ? preset.nameEn : preset.nameZh} · {preset.itemCount} {tr("篇", "items")}</small></span>
                  <Plus className={importingPresetId === preset.id ? "spin" : ""} size={18} />
                </button>
              ))}
            </div>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </main>
  );
}
