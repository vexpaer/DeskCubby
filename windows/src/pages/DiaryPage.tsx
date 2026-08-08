import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type MutableRefObject,
} from "react";
import { EditorState } from "@codemirror/state";
import {
  defaultKeymap,
  history,
  historyKeymap,
  indentWithTab,
} from "@codemirror/commands";
import { markdown } from "@codemirror/lang-markdown";
import {
  drawSelection,
  dropCursor,
  EditorView,
  highlightActiveLine,
  highlightActiveLineGutter,
  highlightSpecialChars,
  keymap,
  lineNumbers,
  rectangularSelection,
} from "@codemirror/view";
import {
  ArchiveRestore,
  BookOpen,
  Eye,
  FilePlus2,
  ImagePlus,
  ListRestart,
  LoaderCircle,
  PencilLine,
  RefreshCw,
  Save,
  Search,
  Trash2,
  X,
} from "lucide-react";
import ReactMarkdown from "react-markdown";
import { useSearchParams } from "react-router-dom";
import { ExternalMarkdownLink, UnsavedChangesGuard } from "../components";
import { useAppStore } from "../store/appStore";
import {
  diaryApi,
  rememberActiveDiary,
  readableError,
  subscribeDiaryIndexChanged,
  tr,
  type DiaryDocument,
  type DiaryEntry,
  type DiarySaveResolution,
  type Language,
} from "../lib/ipc";
import { safeExternalHttpUrl } from "../lib/externalLinkApi";

type EditorMode = "source" | "preview";
type ConflictReason = "changed" | "deleted";

function MarkdownEditor({
  value,
  onChange,
  editorViewRef,
  language,
}: {
  value: string;
  onChange: (next: string) => void;
  editorViewRef: MutableRefObject<EditorView | null>;
  language: Language;
}) {
  const hostRef = useRef<HTMLDivElement>(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  useEffect(() => {
    if (!hostRef.current) return;
    const view = new EditorView({
      parent: hostRef.current,
      state: EditorState.create({
        doc: value,
        extensions: [
          lineNumbers(),
          highlightActiveLineGutter(),
          highlightSpecialChars(),
          history(),
          drawSelection(),
          dropCursor(),
          rectangularSelection(),
          highlightActiveLine(),
          EditorView.lineWrapping,
          markdown(),
          keymap.of([indentWithTab, ...defaultKeymap, ...historyKeymap]),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) {
              onChangeRef.current(update.state.doc.toString());
            }
          }),
        ],
      }),
    });
    editorViewRef.current = view;
    return () => {
      if (editorViewRef.current === view) editorViewRef.current = null;
      view.destroy();
    };
    // The document is synchronized by the effect below. Recreating CodeMirror
    // on each keystroke would discard its selection and undo history.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const view = editorViewRef.current;
    if (!view || view.state.doc.toString() === value) return;
    view.dispatch({
      changes: { from: 0, to: view.state.doc.length, insert: value },
    });
  }, [editorViewRef, value]);

  return (
    <div
      ref={hostRef}
      className="markdown-editor"
      aria-label={tr(language, "Markdown 日记正文", "Markdown diary content")}
    />
  );
}

function dateToday() {
  const now = new Date();
  return [
    now.getFullYear(),
    String(now.getMonth() + 1).padStart(2, "0"),
    String(now.getDate()).padStart(2, "0"),
  ].join("-");
}

function groupByMonth(entries: DiaryEntry[]) {
  return entries.reduce<Map<string, DiaryEntry[]>>((groups, entry) => {
    const month = entry.month || entry.date.slice(0, 7);
    groups.set(month, [...(groups.get(month) ?? []), entry]);
    return groups;
  }, new Map());
}

function MarkdownImage({
  source,
  alt,
  diaryRelativePath,
  language,
}: {
  source: string;
  alt: string;
  diaryRelativePath: string;
  language: Language;
}) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    setFailed(false);
    setUrl(null);
    void diaryApi
      .resolveMediaAsset(diaryRelativePath, source)
      .then((resolved) => {
        if (active) setUrl(resolved);
      })
      .catch(() => {
        if (active) setFailed(true);
      });
    return () => {
      active = false;
    };
  }, [diaryRelativePath, source]);

  if (!url || failed) {
    return (
      <span className="markdown-image-placeholder">
        <ImagePlus aria-hidden="true" />
        <span>
          {failed
            ? tr(language, "图片不可用", "Image unavailable")
            : tr(language, "正在读取图片…", "Loading image…")}
        </span>
      </span>
    );
  }

  return (
    <img
      src={url}
      alt={alt}
      loading="lazy"
      onError={() => setFailed(true)}
    />
  );
}

export default function DiaryPage() {
  const language = useAppStore((state) => state.appearance.language);
  const t = useCallback(
    (zh: string, en: string) => tr(language, zh, en),
    [language],
  );
  const [searchParams, setSearchParams] = useSearchParams();
  const [entries, setEntries] = useState<DiaryEntry[]>([]);
  const [showTrash, setShowTrash] = useState(false);
  const [query, setQuery] = useState("");
  const [loadingList, setLoadingList] = useState(true);
  const [document, setDocument] = useState<DiaryDocument | null>(null);
  const [draft, setDraft] = useState("");
  const [loadingDocument, setLoadingDocument] = useState(false);
  const [saving, setSaving] = useState(false);
  const [mode, setMode] = useState<EditorMode>("source");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [conflictOpen, setConflictOpen] = useState(false);
  const [conflictReason, setConflictReason] =
    useState<ConflictReason | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [newDate, setNewDate] = useState(dateToday());
  const [newTitle, setNewTitle] = useState("");
  const [renameOpen, setRenameOpen] = useState(false);
  const [renameTitle, setRenameTitle] = useState("");
  const editorRef = useRef<EditorView | null>(null);
  const suppressedAutoOpenPathRef = useRef<string | null>(null);
  const selectedPath = searchParams.get("entry");

  const dirty = Boolean(document && draft !== document.content);

  const loadList = useCallback(
    async (rescan = false) => {
      setLoadingList(true);
      setError(null);
      try {
        const next = rescan
          ? await diaryApi.rescan()
          : await diaryApi.list(showTrash);
        const visible = showTrash ? next.filter((item) => item.trashed) : next;
        setEntries(visible);
      } catch (reason) {
        setError(readableError(reason, language));
      } finally {
        setLoadingList(false);
      }
    },
    [language, showTrash],
  );

  useEffect(() => {
    void loadList();
  }, [loadList]);

  useEffect(() => {
    let cancelled = false;
    let unlisten: (() => void) | undefined;
    void subscribeDiaryIndexChanged(() => void loadList())
      .then((stop) => {
        if (cancelled) stop();
        else unlisten = stop;
      })
      .catch(() => {
        // Event subscription is an enhancement; explicit Scan still works if
        // the desktop event channel is temporarily unavailable.
      });
    return () => {
      cancelled = true;
      unlisten?.();
    };
  }, [loadList]);

  const openDocument = useCallback(async (relativePath: string) => {
    setLoadingDocument(true);
    setError(null);
    try {
      const next = await diaryApi.open(relativePath);
      setDocument(next);
      setDraft(next.content);
      setRenameTitle(next.entry.title);
      setConflictOpen(false);
      setConflictReason(null);
      rememberActiveDiary(next.entry.relativePath);
    } catch (reason) {
      setError(readableError(reason, language));
      setDocument(null);
      setDraft("");
    } finally {
      setLoadingDocument(false);
    }
  }, [language]);

  useEffect(() => {
    if (!selectedPath) {
      suppressedAutoOpenPathRef.current = null;
    } else if (
      selectedPath !== suppressedAutoOpenPathRef.current &&
      selectedPath !== document?.entry.relativePath
    ) {
      void openDocument(selectedPath);
    }
  }, [document?.entry.relativePath, openDocument, selectedPath]);

  useEffect(() => {
    function saveShortcut(event: KeyboardEvent) {
      if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== "s") {
        return;
      }
      event.preventDefault();
      if (dirty && !saving) void save("normal");
    }
    window.addEventListener("keydown", saveShortcut);
    return () => window.removeEventListener("keydown", saveShortcut);
  });

  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(null), 3500);
    return () => window.clearTimeout(timer);
  }, [notice]);

  const filteredGroups = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase();
    const filtered = normalized
      ? entries.filter(
          (entry) =>
            entry.title.toLocaleLowerCase().includes(normalized) ||
            entry.excerpt.toLocaleLowerCase().includes(normalized) ||
            entry.date.includes(normalized),
        )
      : entries;
    return groupByMonth(filtered);
  }, [entries, query]);

  function selectEntry(entry: DiaryEntry) {
    if (entry.relativePath === selectedPath) return;
    setSearchParams({ entry: entry.relativePath });
  }

  async function createDiary() {
    if (!newDate || saving) return;
    setSaving(true);
    setError(null);
    try {
      const created = await diaryApi.create(newDate, newTitle.trim());
      setCreateOpen(false);
      setNewTitle("");
      await loadList();
      setSearchParams({ entry: created.entry.relativePath });
      setDocument(created);
      setDraft(created.content);
      rememberActiveDiary(created.entry.relativePath);
      setNotice(t("日记已创建", "Diary created"));
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setSaving(false);
    }
  }

  async function save(resolution: DiarySaveResolution) {
    if (!document || saving) return;
    setSaving(true);
    setError(null);
    try {
      const result = await diaryApi.save({
        relativePath: document.entry.relativePath,
        content: draft,
        expectedVersion: document.version,
        resolution,
      });
      if (result.status === "conflict") {
        setConflictReason(result.reason);
        setConflictOpen(true);
        return;
      }
      setDocument(result.document);
      setDraft(result.document.content);
      rememberActiveDiary(result.document.entry.relativePath);
      setConflictOpen(false);
      setConflictReason(null);
      setNotice(
        resolution === "copy"
          ? t("冲突副本已保存", "Conflict copy saved")
          : t("日记已保存", "Diary saved"),
      );
      await loadList();
      if (resolution === "copy") {
        setSearchParams({ entry: result.document.entry.relativePath });
      }
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setSaving(false);
    }
  }

  async function reloadConflict() {
    if (!document) return;
    const externallyDeleted = conflictReason === "deleted";
    if (
      dirty &&
      !window.confirm(
        externallyDeleted
          ? t(
              "文件已被外部删除。接受删除会丢弃当前草稿并关闭编辑器，确定继续吗？",
              "The file was deleted externally. Accepting the deletion will discard this draft and close the editor. Continue?",
            )
          : t(
              "重新加载会丢弃当前草稿，确定继续吗？",
              "Reloading will discard this draft. Continue?",
            ),
      )
    ) {
      return;
    }
    if (externallyDeleted) {
      suppressedAutoOpenPathRef.current = document.entry.relativePath;
      setDocument(null);
      setDraft("");
      setRenameTitle("");
      rememberActiveDiary(null);
      setConflictOpen(false);
      setConflictReason(null);
      setNotice(t("已接受外部删除", "External deletion accepted"));
      await loadList(true);
      setSearchParams({});
      return;
    }
    await openDocument(document.entry.relativePath);
  }

  async function renameDiary() {
    if (!document || !renameTitle.trim() || saving) return;
    setSaving(true);
    setError(null);
    try {
      const next = await diaryApi.rename(
        document.entry.relativePath,
        renameTitle.trim(),
      );
      setDocument(next);
      setDraft(next.content);
      rememberActiveDiary(next.entry.relativePath);
      setRenameOpen(false);
      setNotice(t("日记已重命名", "Diary renamed"));
      await loadList();
      setSearchParams({ entry: next.entry.relativePath });
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setSaving(false);
    }
  }

  async function trashDiary() {
    if (!document) return;
    if (
      !window.confirm(
        language === "en"
          ? `Move “${document.entry.title}” to the recycle bin?`
          : `将“${document.entry.title}”移到回收站？`,
      )
    )
      return;
    setSaving(true);
    try {
      await diaryApi.trash(document.entry.relativePath);
      setDocument(null);
      setDraft("");
      rememberActiveDiary(null);
      setNotice(t("已移到回收站", "Moved to recycle bin"));
      await loadList();
      setSearchParams({});
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setSaving(false);
    }
  }

  async function restoreDiary(entry: DiaryEntry) {
    setSaving(true);
    try {
      const restored = await diaryApi.restore(entry.relativePath);
      setShowTrash(false);
      setSearchParams({ entry: restored.entry.relativePath });
      setDocument(restored);
      setDraft(restored.content);
      rememberActiveDiary(restored.entry.relativePath);
      setNotice(t("日记已恢复", "Diary restored"));
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setSaving(false);
    }
  }

  async function deletePermanently(entry: DiaryEntry) {
    if (
      !window.confirm(
        language === "en"
          ? `Permanently delete “${entry.title}”? This cannot be undone. Linked media will not be deleted automatically.`
          : `永久删除“${entry.title}”？此操作无法撤销，关联媒体不会自动删除。`,
      )
    ) {
      return;
    }
    setSaving(true);
    try {
      await diaryApi.deletePermanently(entry.relativePath);
      if (document?.entry.relativePath === entry.relativePath) {
        setDocument(null);
        setDraft("");
        setSearchParams({});
      }
      setNotice(t("日记已永久删除", "Diary permanently deleted"));
      await loadList();
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setSaving(false);
    }
  }

  async function importImage() {
    if (!document || saving) return;
    setSaving(true);
    setError(null);
    try {
      const imported = await diaryApi.selectAndImportImage(
        document.entry.relativePath,
        null,
      );
      if (!imported) return;
      const view = editorRef.current;
      if (view) {
        const position = view.state.selection.main.head;
        view.dispatch({
          changes: { from: position, insert: `\n${imported.markdown}\n` },
          selection: { anchor: position + imported.markdown.length + 2 },
        });
      } else {
        setDraft((current) => `${current}\n${imported.markdown}\n`);
      }
      setNotice(
        t("图片已导入，请保存日记", "Image imported. Save the diary to finish."),
      );
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setSaving(false);
    }
  }

  return (
    <main className="page diary-page">
      <UnsavedChangesGuard when={dirty} scope="diary" />
      <header className="page-header">
        <div>
          <p className="eyebrow">
            {t("Markdown · 本地文件", "Markdown · Local files")}
          </p>
          <h1>{t("日记", "Diary")}</h1>
        </div>
        <div className="header-actions">
          <button
            className="button secondary"
            type="button"
            onClick={() => setShowTrash((value) => !value)}
          >
            {showTrash ? (
              <BookOpen aria-hidden="true" />
            ) : (
              <Trash2 aria-hidden="true" />
            )}
            {showTrash
              ? t("返回日记", "Back to diaries")
              : t("回收站", "Recycle bin")}
          </button>
          <button
            className="button secondary"
            type="button"
            onClick={() => void loadList(true)}
            disabled={loadingList}
          >
            <RefreshCw
              className={loadingList ? "spin" : ""}
              aria-hidden="true"
            />
            {t("扫描", "Scan")}
          </button>
          {!showTrash && (
            <button
            className="button primary"
            type="button"
            onClick={() => setCreateOpen(true)}
            disabled={dirty}
            title={
              dirty
                ? t(
                    "请先保存当前日记",
                    "Save the current diary before creating another",
                  )
                : undefined
            }
            >
              <FilePlus2 aria-hidden="true" /> {t("新建", "New")}
            </button>
          )}
        </div>
      </header>

      {error && (
        <div className="inline-error" role="alert">
          {error}
          <button
            type="button"
            onClick={() => setError(null)}
            aria-label={t("关闭", "Close")}
          >
            <X aria-hidden="true" />
          </button>
        </div>
      )}
      {notice && (
        <div className="toast" role="status">
          {notice}
        </div>
      )}

      <div className="diary-workspace">
        <aside
          className="diary-list-panel panel"
          aria-label={t("日记列表", "Diary list")}
        >
          <label className="search-field">
            <Search aria-hidden="true" />
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder={t(
                "搜索标题、日期或摘要",
                "Search title, date, or excerpt",
              )}
            />
          </label>
          {loadingList ? (
            <div className="panel-loading" aria-busy="true">
              <LoaderCircle className="spin" aria-hidden="true" />
              <span>{t("正在扫描日记…", "Scanning diaries…")}</span>
            </div>
          ) : filteredGroups.size ? (
            <div className="diary-months">
              {[...filteredGroups].map(([month, monthEntries]) => (
                <section key={month}>
                  <h2>{month}</h2>
                  <div className="diary-list">
                    {monthEntries.map((entry) => (
                      <div
                        className={
                          entry.relativePath === selectedPath
                            ? "diary-list-item selected"
                            : "diary-list-item"
                        }
                        key={entry.relativePath}
                      >
                        <button
                          className="diary-entry-button"
                          type="button"
                          onClick={() => selectEntry(entry)}
                          disabled={showTrash}
                        >
                          <span>
                            <strong>{entry.title}</strong>
                            <time>{entry.date}</time>
                          </span>
                          <small>
                            {entry.excerpt || t("还没有正文", "No content yet")}
                          </small>
                        </button>
                        {showTrash && (
                          <span className="row-actions">
                            <button
                              className="icon-button"
                              type="button"
                              title={t("恢复", "Restore")}
                              aria-label={t("恢复日记", "Restore diary")}
                              onClick={(event) => {
                                event.stopPropagation();
                                void restoreDiary(entry);
                              }}
                            >
                              <ArchiveRestore aria-hidden="true" />
                            </button>
                            <button
                              className="icon-button danger"
                              type="button"
                              title={t("永久删除", "Delete permanently")}
                              aria-label={t(
                                "永久删除日记",
                                "Permanently delete diary",
                              )}
                              onClick={(event) => {
                                event.stopPropagation();
                                void deletePermanently(entry);
                              }}
                            >
                              <Trash2 aria-hidden="true" />
                            </button>
                          </span>
                        )}
                      </div>
                    ))}
                  </div>
                </section>
              ))}
            </div>
          ) : (
            <div className="empty-state">
              <BookOpen aria-hidden="true" />
              <h2>
                {showTrash
                  ? t("回收站是空的", "The recycle bin is empty")
                  : t("还没有日记", "No diaries yet")}
              </h2>
              <p>
                {showTrash
                  ? t(
                      "删除的日记会先来到这里。",
                      "Deleted diaries are kept here first.",
                    )
                  : t(
                      "选择现有目录后扫描，或创建今天的日记。",
                      "Choose an existing folder and scan, or create today's diary.",
                    )}
              </p>
              {!showTrash && (
                <button
                  className="button primary"
                  type="button"
                  onClick={() => setCreateOpen(true)}
                  disabled={dirty}
                >
                  {t("新建日记", "New diary")}
                </button>
              )}
            </div>
          )}
        </aside>

        <section className="diary-editor-panel panel">
          {loadingDocument ? (
            <div className="page-centered" aria-busy="true">
              <LoaderCircle className="spin" aria-hidden="true" />
              <p>{t("正在打开日记…", "Opening diary…")}</p>
            </div>
          ) : document ? (
            <>
              <header className="editor-header">
                <div>
                  <h2>{document.entry.title}</h2>
                  <p className="muted">
                    {document.entry.date} ·{" "}
                    {dirty
                      ? t("有未保存修改", "Unsaved changes")
                      : t("已保存", "Saved")}
                  </p>
                </div>
                <div className="header-actions">
                  <div
                    className="segmented"
                    role="group"
                    aria-label={t("编辑模式", "Editor mode")}
                  >
                    <button
                      type="button"
                      className={mode === "source" ? "selected" : undefined}
                      onClick={() => setMode("source")}
                    >
                      <PencilLine aria-hidden="true" /> {t("源码", "Source")}
                    </button>
                    <button
                      type="button"
                      className={mode === "preview" ? "selected" : undefined}
                      onClick={() => setMode("preview")}
                    >
                      <Eye aria-hidden="true" /> {t("预览", "Preview")}
                    </button>
                  </div>
                  <button
                    className="icon-button"
                    type="button"
                    title={t("导入图片", "Import image")}
                    aria-label={t("导入图片", "Import image")}
                    onClick={() => void importImage()}
                    disabled={saving}
                  >
                    <ImagePlus aria-hidden="true" />
                  </button>
                  <button
                    className="icon-button"
                    type="button"
                    title={t("重命名", "Rename")}
                    aria-label={t("重命名日记", "Rename diary")}
                    onClick={() => {
                      setRenameTitle(document.entry.title);
                      setRenameOpen(true);
                    }}
                    disabled={dirty || saving}
                  >
                    <PencilLine aria-hidden="true" />
                  </button>
                  <button
                    className="icon-button danger"
                    type="button"
                    title={t("移到回收站", "Move to recycle bin")}
                    aria-label={t("移到回收站", "Move to recycle bin")}
                    onClick={() => void trashDiary()}
                    disabled={dirty || saving}
                  >
                    <Trash2 aria-hidden="true" />
                  </button>
                  <button
                    className="button primary"
                    type="button"
                    disabled={!dirty || saving}
                    onClick={() => void save("normal")}
                  >
                    {saving ? (
                      <LoaderCircle className="spin" aria-hidden="true" />
                    ) : (
                      <Save aria-hidden="true" />
                    )}
                    {t("保存", "Save")}
                  </button>
                </div>
              </header>

              {mode === "source" ? (
                <MarkdownEditor
                  value={draft}
                  onChange={setDraft}
                  editorViewRef={editorRef}
                  language={language}
                />
              ) : (
                <article className="markdown-preview">
                  <ReactMarkdown
                    urlTransform={(url, key) =>
                      key === "src" ? url : safeExternalHttpUrl(url) ?? ""
                    }
                    components={{
                      a: ({ href, children }) => (
                        <ExternalMarkdownLink
                          href={href}
                          language={language}
                          onOpenError={setError}
                        >
                          {children}
                        </ExternalMarkdownLink>
                      ),
                      img: ({ src, alt }) =>
                        src ? (
                          <MarkdownImage
                            source={src}
                            alt={alt ?? ""}
                            diaryRelativePath={document.entry.relativePath}
                            language={language}
                          />
                        ) : null,
                    }}
                  >
                    {draft}
                  </ReactMarkdown>
                </article>
              )}
              <footer className="editor-footer">
                <span>
                  {draft.length.toLocaleString()}{" "}
                  {t("字符", "characters")}
                </span>
                <span>
                  {document.version.size.toLocaleString()} bytes ·{" "}
                  {new Date(document.version.modifiedAt).toLocaleString()}
                </span>
              </footer>
            </>
          ) : (
            <div className="empty-state editor-empty">
              <BookOpen aria-hidden="true" />
              <h2>{t("选择一篇日记", "Choose a diary")}</h2>
              <p>
                {t(
                  "左侧选择日记，或新建一篇 Markdown 日记。",
                  "Choose a diary on the left, or create a Markdown diary.",
                )}
              </p>
            </div>
          )}
        </section>
      </div>

      {createOpen && (
        <div className="dialog-backdrop" role="presentation">
          <section
            className="dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="create-diary-title"
          >
            <header>
              <h2 id="create-diary-title">
                {t("新建日记", "New diary")}
              </h2>
              <button
                className="icon-button"
                type="button"
                onClick={() => setCreateOpen(false)}
                aria-label={t("关闭", "Close")}
              >
                <X aria-hidden="true" />
              </button>
            </header>
            <label>
              {t("日期", "Date")}
              <input
                type="date"
                value={newDate}
                onChange={(event) => setNewDate(event.target.value)}
              />
            </label>
            <label>
              {t("标题（可选）", "Title (optional)")}
              <input
                value={newTitle}
                maxLength={200}
                onChange={(event) => setNewTitle(event.target.value)}
                placeholder={t("默认使用日期", "Defaults to the date")}
                autoFocus
              />
            </label>
            <footer>
              <button
                className="button secondary"
                type="button"
                onClick={() => setCreateOpen(false)}
              >
                {t("取消", "Cancel")}
              </button>
              <button
                className="button primary"
                type="button"
                disabled={!newDate || saving}
                onClick={() => void createDiary()}
              >
                {t("创建", "Create")}
              </button>
            </footer>
          </section>
        </div>
      )}

      {renameOpen && document && (
        <div className="dialog-backdrop" role="presentation">
          <section
            className="dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="rename-diary-title"
          >
            <header>
              <h2 id="rename-diary-title">
                {t("重命名日记", "Rename diary")}
              </h2>
              <button
                className="icon-button"
                type="button"
                onClick={() => setRenameOpen(false)}
                aria-label={t("关闭", "Close")}
              >
                <X aria-hidden="true" />
              </button>
            </header>
            <label>
              {t("标题", "Title")}
              <input
                value={renameTitle}
                maxLength={200}
                onChange={(event) => setRenameTitle(event.target.value)}
                autoFocus
              />
            </label>
            <footer>
              <button
                className="button secondary"
                type="button"
                onClick={() => setRenameOpen(false)}
              >
                {t("取消", "Cancel")}
              </button>
              <button
                className="button primary"
                type="button"
                disabled={!renameTitle.trim() || saving}
                onClick={() => void renameDiary()}
              >
                {t("保存", "Save")}
              </button>
            </footer>
          </section>
        </div>
      )}

      {conflictOpen && document && (
        <div className="dialog-backdrop" role="presentation">
          <section
            className="dialog conflict-dialog"
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="conflict-title"
          >
            <header>
              <h2 id="conflict-title">
                {conflictReason === "deleted"
                  ? t("文件已被外部删除", "File deleted externally")
                  : t("检测到外部修改", "External change detected")}
              </h2>
            </header>
            <p>
              {conflictReason === "deleted"
                ? t(
                    "文件已被外部删除。你可以接受删除并关闭编辑器，也可以覆盖以重新创建文件，或把当前草稿另存为冲突副本。DeskCubby 不会自动覆盖磁盘内容。",
                    "The file was deleted externally. You can accept the deletion and close the editor, recreate it by overwriting, or save this draft as a conflict copy. DeskCubby will not overwrite disk content automatically.",
                  )
                : t(
                    "这篇日记在编辑期间被其他应用修改。请选择如何处理，DeskCubby 不会自动覆盖磁盘内容。",
                    "Another application changed this diary while you were editing. Choose how to proceed; DeskCubby will not overwrite the file automatically.",
                  )}
            </p>
            <footer className="stacked-actions">
              <button
                className="button secondary"
                type="button"
                onClick={() => void reloadConflict()}
              >
                <ListRestart aria-hidden="true" />{" "}
                {conflictReason === "deleted"
                  ? t("重新加载（接受删除）", "Accept deletion and close")
                  : t("重新加载磁盘版本", "Reload disk version")}
              </button>
              <button
                className="button secondary"
                type="button"
                onClick={() => void save("copy")}
              >
                <FilePlus2 aria-hidden="true" />{" "}
                {t("另存冲突副本", "Save conflict copy")}
              </button>
              <button
                className="button danger"
                type="button"
                onClick={() => void save("overwrite")}
              >
                <Save aria-hidden="true" />{" "}
                {t("确认覆盖", "Confirm overwrite")}
              </button>
            </footer>
          </section>
        </div>
      )}
    </main>
  );
}
