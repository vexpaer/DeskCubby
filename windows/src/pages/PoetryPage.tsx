import {
  BookHeart,
  Feather,
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
  type SavedPoem,
  type SavedPoemDraft,
} from "../lib/ipc";

type Language = "zh" | "en";

const EMPTY_DRAFT: SavedPoemDraft = { content: "", source: "" };

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

export default function PoetryPage() {
  const language = useDocumentLanguage();
  const tr = useCallback(
    (chinese: string, english: string) => (language === "zh" ? chinese : english),
    [language],
  );
  const [dailyPoem, setDailyPoem] = useState<DailyPoemResponse | null>(null);
  const [poems, setPoems] = useState<SavedPoem[]>([]);
  const [draft, setDraft] = useState<SavedPoemDraft>(EMPTY_DRAFT);
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const loadSaved = useCallback(async () => {
    try {
      setPoems(await poetryApi.list());
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
      await Promise.all([loadSaved(), loadDaily(false)]);
      if (active) setLoading(false);
    };
    void initialLoad();
    return () => {
      active = false;
    };
  }, [loadDaily, loadSaved]);

  const visiblePoems = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase();
    return poems
      .filter(
        (poem) =>
          normalized.length === 0 ||
          poem.content.toLocaleLowerCase().includes(normalized) ||
          poem.source.toLocaleLowerCase().includes(normalized),
      )
      .sort((left, right) => compareDecimalI64(right.updatedAt, left.updatedAt));
  }, [poems, query]);

  const dailySource = useMemo(() => {
    if (!dailyPoem) return "";
    if (dailyPoem.source) return dailyPoem.source;
    return [dailyPoem.dynasty, dailyPoem.author, dailyPoem.title].filter(Boolean).join(" · ");
  }, [dailyPoem]);

  const resetEditor = () => setDraft(EMPTY_DRAFT);

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
        ? await poetryApi.update(draft.id, { content, source })
        : await poetryApi.create({ content, source });
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
      });
      setPoems((current) => [...current.filter((item) => item.id !== saved.id), saved]);
      setNotice(tr("每日诗词已加入诗词本。", "Daily poem added to your book."));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tr("保存失败。", "Save failed."));
    }
  };

  const editPoem = (poem: SavedPoem) => {
    setDraft({ id: poem.id, content: poem.content, source: poem.source });
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

  return (
    <main className="page-shell poetry-page" aria-labelledby="poetry-title">
      <header className="page-header">
        <div>
          <p className="eyebrow">{tr("每日一句，也收藏整首", "A line for today, a poem to keep")}</p>
          <h1 id="poetry-title">{tr("诗词本", "Poetry book")}</h1>
        </div>
        <div className="page-actions">
          <button
            className="button-primary"
            type="button"
            onClick={() => document.getElementById("poem-content")?.focus()}
          >
            <Plus size={18} />
            {tr("新增诗词", "New poem")}
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

      <section className="panel daily-poem" aria-labelledby="daily-poem-title">
        <div className="panel-heading">
          <div>
            <p className="eyebrow">{tr("在线失败时使用本地缓存或内置内容", "Cached locally for offline use")}</p>
            <h2 id="daily-poem-title">{dailyPoem?.title || tr("每日诗词", "Daily poem")}</h2>
          </div>
          <button
            className="button-ghost"
            type="button"
            disabled={refreshing}
            onClick={() => void loadDaily(true)}
          >
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
                  <span className="source-badge">
                    {dailyPoem.usedFallback
                      ? tr("内置回退", "Built-in fallback")
                      : tr("本地缓存", "Local cache")}
                  </span>
                )}
              </div>
              <button className="button-secondary" type="button" onClick={() => void saveDailyPoem()}>
                <BookHeart size={17} />
                {tr("加入诗词本", "Save to book")}
              </button>
            </div>
          </>
        ) : (
          <div className="empty-state" role="status">
            {refreshing
              ? tr("正在取一首诗…", "Finding a poem…")
              : tr("暂无每日诗词。", "No daily poem available.")}
          </div>
        )}
      </section>

      <form className="panel poem-editor" onSubmit={savePoem}>
        <div className="panel-heading">
          <h2>{draft.id ? tr("编辑诗词", "Edit poem") : tr("手动收藏", "Add manually")}</h2>
          {draft.id && (
            <button className="button-ghost" type="button" onClick={resetEditor}>
              <X size={16} />
              {tr("取消编辑", "Cancel")}
            </button>
          )}
        </div>
        <div className="form-grid poem-editor-fields">
          <label className="form-span">
            <span>{tr("正文", "Text")}</span>
            <textarea
              id="poem-content"
              maxLength={65_536}
              rows={6}
              required
              value={draft.content}
              placeholder={tr("输入诗词正文，可保留换行", "Enter the poem; line breaks are preserved")}
              onChange={(event) =>
                setDraft((current) => ({ ...current, content: event.target.value }))
              }
            />
          </label>
          <label>
            <span>{tr("出处（可选）", "Source (optional)")}</span>
            <input
              maxLength={500}
              value={draft.source}
              placeholder={tr("朝代 · 作者 · 篇名", "Dynasty · Author · Title")}
              onChange={(event) =>
                setDraft((current) => ({ ...current, source: event.target.value }))
              }
            />
          </label>
          <button className="button-primary align-end" type="submit" disabled={saving}>
            <Save size={17} />
            {saving ? tr("保存中…", "Saving…") : tr("保存", "Save")}
          </button>
        </div>
      </form>

      <section aria-label={tr("已收藏诗词", "Saved poems")}>
        <label className="search-field">
          <Search size={17} aria-hidden="true" />
          <span className="sr-only">{tr("搜索诗词", "Search poems")}</span>
          <input
            type="search"
            value={query}
            placeholder={tr("搜索正文或出处", "Search text or source")}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>

        {loading ? (
          <div className="panel empty-state" role="status">
            {tr("正在读取诗词本…", "Loading poetry book…")}
          </div>
        ) : visiblePoems.length === 0 ? (
          <div className="panel empty-state">
            <Feather size={28} aria-hidden="true" />
            <p>{tr("诗词本还是空的。", "Your poetry book is empty.")}</p>
          </div>
        ) : (
          <div className="poem-grid">
            {visiblePoems.map((poem) => (
              <article className="panel poem-card" key={poem.id}>
                <blockquote>{poem.content}</blockquote>
                <footer>
                  <cite>{poem.source || tr("未填写出处", "Source not specified")}</cite>
                  <div className="inline-actions">
                    <button
                      className="icon-button"
                      type="button"
                      title={tr("编辑", "Edit")}
                      onClick={() => editPoem(poem)}
                    >
                      <Pencil size={17} />
                    </button>
                    <button
                      className="icon-button danger"
                      type="button"
                      title={tr("删除", "Delete")}
                      onClick={() => void deletePoem(poem)}
                    >
                      <Trash2 size={17} />
                    </button>
                  </div>
                </footer>
              </article>
            ))}
          </div>
        )}
      </section>
    </main>
  );
}
