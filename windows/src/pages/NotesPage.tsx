import * as Dialog from "@radix-ui/react-dialog";
import * as AlertDialog from "@radix-ui/react-alert-dialog";
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
  AlertTriangle,
  ChevronRight,
  Eye,
  FilePlus2,
  FileText,
  Folder,
  FolderOpen,
  FolderPlus,
  ImageOff,
  ImagePlus,
  LoaderCircle,
  PencilLine,
  RefreshCw,
  Save,
  Search,
  Trash2,
  X,
} from "lucide-react";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type MutableRefObject,
} from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { ConfirmDialog, ExternalMarkdownLink, UnsavedChangesGuard } from "../components";
import { DeskCubbyIpcError, tr } from "../lib/ipc";
import {
  notesApi,
  type NoteConflictReasonV1,
  type NoteDocumentV1,
  type NoteEntryV1,
  type NoteFolderSnapshotV1,
  type NoteSaveResolutionV1,
  type NotesRootStateV1,
} from "../lib/notesApi";
import { useAppStore } from "../store/appStore";
import "./NotesPage.css";
import { safeNoteLinkTransform, splitNotePreview } from "./notesPreview";

type EditorMode = "source" | "preview";

interface NoteConflict {
  reason: NoteConflictReasonV1;
  diskDocument: NoteDocumentV1 | null;
}

function noteError(error: unknown, language: "zh-CN" | "en") {
  const code = error instanceof DeskCubbyIpcError ? error.code : "unexpected_error";
  const messages: Record<string, [string, string]> = {
    notes_directory_not_configured: [
      "请先选择一个笔记库目录。",
      "Choose a notes vault first.",
    ],
    name_exists: ["当前文件夹已有同名项目。", "An item with that name already exists."],
    content_too_large: [
      "笔记或图片超过安全大小上限。",
      "The note or image exceeds the safety size limit.",
    ],
    invalid_input: ["名称、文件或内容不符合要求。", "The name, file, or content is invalid."],
    not_found: [
      "项目已被其他应用移动或删除。",
      "The item was moved or deleted by another application.",
    ],
    path_not_allowed: [
      "所选项目不在当前笔记库内，或路径含链接/保留名称。",
      "The item is outside this vault or uses a link/reserved path.",
    ],
    storage_unavailable: [
      "无法访问笔记库，请检查目录权限。",
      "The notes vault is unavailable. Check folder access.",
    ],
    unexpected_error: ["笔记操作失败，请重试。", "The notes operation failed. Try again."],
  };
  const message = messages[code] ?? messages.unexpected_error;
  return tr(language, message[0], message[1]);
}

function MarkdownEditor({
  value,
  onChange,
  onSave,
  editorRef,
  label,
}: {
  value: string;
  onChange: (value: string) => void;
  onSave: () => void;
  editorRef: MutableRefObject<EditorView | null>;
  label: string;
}) {
  const hostRef = useRef<HTMLDivElement>(null);
  const onChangeRef = useRef(onChange);
  const onSaveRef = useRef(onSave);
  onChangeRef.current = onChange;
  onSaveRef.current = onSave;

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
          keymap.of([
            {
              key: "Mod-s",
              preventDefault: true,
              run: () => {
                onSaveRef.current();
                return true;
              },
            },
            indentWithTab,
            ...defaultKeymap,
            ...historyKeymap,
          ]),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) onChangeRef.current(update.state.doc.toString());
          }),
        ],
      }),
    });
    editorRef.current = view;
    return () => {
      if (editorRef.current === view) editorRef.current = null;
      view.destroy();
    };
    // CodeMirror synchronizes content below; recreating it loses selection.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const view = editorRef.current;
    if (!view || view.state.doc.toString() === value) return;
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } });
  }, [editorRef, value]);

  return <div ref={hostRef} className="markdown-editor" aria-label={label} />;
}

function NotePreview({
  document,
  content,
  language,
  onOpenError,
}: {
  document: NoteDocumentV1;
  content: string;
  language: "zh-CN" | "en";
  onOpenError: (message: string) => void;
}) {
  const parts = useMemo(() => splitNotePreview(content), [content]);
  const targets = useMemo(
    () => [...new Set(parts.filter((part) => part.kind === "image").map((part) => part.target))],
    [parts],
  );
  const [resolved, setResolved] = useState<Map<string, string>>(new Map());
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    let active = true;
    setResolved(new Map());
    if (!targets.length) return () => undefined;
    setLoading(true);
    void notesApi
      .resolveMedia(document.relativePath, targets)
      .then((items) => {
        if (active) setResolved(new Map(items.map((item) => [item.target, item.dataUrl])));
      })
      .catch(() => {
        if (active) setResolved(new Map());
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [document.relativePath, targets]);

  return (
    <article className="markdown-preview notes-preview" aria-busy={loading}>
      {parts.map((part) =>
        part.kind === "text" ? (
          <ReactMarkdown
            key={part.key}
            remarkPlugins={[remarkGfm]}
            urlTransform={safeNoteLinkTransform}
            components={{
              a: ({ href, children }) => (
                <ExternalMarkdownLink
                  href={href}
                  language={language}
                  onOpenError={onOpenError}
                >
                  {children}
                </ExternalMarkdownLink>
              ),
            }}
          >
            {part.markdown}
          </ReactMarkdown>
        ) : resolved.has(part.target) ? (
          <figure className="notes-preview-image" key={part.key}>
            <img src={resolved.get(part.target)} alt={part.caption} loading="lazy" />
            <figcaption>{part.caption || part.target}</figcaption>
          </figure>
        ) : (
          <figure className="markdown-image-placeholder" key={part.key}>
            {loading ? <LoaderCircle className="spin" aria-hidden="true" /> : <ImageOff aria-hidden="true" />}
            <figcaption>
              {loading
                ? tr(language, "正在读取图片…", "Loading image…")
                : tr(language, "无法找到媒体", "Media could not be found")}
            </figcaption>
            <small>{part.target}</small>
          </figure>
        ),
      )}
    </article>
  );
}

function NameDialog({
  open,
  title,
  initialValue,
  busy,
  language,
  onCancel,
  onConfirm,
}: {
  open: boolean;
  title: string;
  initialValue: string;
  busy: boolean;
  language: "zh-CN" | "en";
  onCancel: () => void;
  onConfirm: (value: string) => void;
}) {
  const [value, setValue] = useState(initialValue);
  useEffect(() => {
    if (open) setValue(initialValue);
  }, [initialValue, open]);
  const labelId = useMemo(() => `note-name-${title.replace(/\W/g, "-")}`, [title]);
  return (
    <Dialog.Root open={open} onOpenChange={(next) => !next && !busy && onCancel()}>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="dialog-content">
          <Dialog.Title className="dialog-title">{title}</Dialog.Title>
          <label className="notes-name-field" htmlFor={labelId}>
            {tr(language, "名称", "Name")}
            <input
              id={labelId}
              value={value}
              maxLength={220}
              onChange={(event) => setValue(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter" && value.trim() && !busy) onConfirm(value);
              }}
              autoFocus
            />
          </label>
          <div className="dialog-actions">
            <button className="button button-ghost" type="button" onClick={onCancel} disabled={busy}>
              {tr(language, "取消", "Cancel")}
            </button>
            <button
              className="button button-primary"
              type="button"
              onClick={() => onConfirm(value)}
              disabled={!value.trim() || busy}
            >
              {tr(language, "确定", "OK")}
            </button>
          </div>
          <Dialog.Close className="icon-button dialog-close" aria-label={tr(language, "关闭", "Close")}>
            <X aria-hidden="true" />
          </Dialog.Close>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function formatSize(bytes: number) {
  if (bytes < 1_024) return `${bytes} B`;
  if (bytes < 1_048_576) return `${Math.floor(bytes / 1_024)} KiB`;
  return `${(bytes / 1_048_576).toFixed(1)} MiB`;
}

function parentPath(path: string) {
  const parts = path.split("/").filter(Boolean);
  parts.pop();
  return parts.join("/");
}

export default function NotesPage() {
  const language = useAppStore((state) => state.appearance.language);
  const t = useCallback((zh: string, en: string) => tr(language, zh, en), [language]);
  const [root, setRoot] = useState<NotesRootStateV1 | null>(null);
  const [folderPath, setFolderPath] = useState("");
  const [snapshot, setSnapshot] = useState<NoteFolderSnapshotV1 | null>(null);
  const [document, setDocument] = useState<NoteDocumentV1 | null>(null);
  const [draft, setDraft] = useState("");
  const [mode, setMode] = useState<EditorMode>("source");
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadingDocument, setLoadingDocument] = useState(false);
  const [saving, setSaving] = useState(false);
  const [mutating, setMutating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [conflict, setConflict] = useState<NoteConflict | null>(null);
  const [nameDialog, setNameDialog] = useState<
    | { kind: "folder" | "note"; entry?: NoteEntryV1 }
    | null
  >(null);
  const [deleteEntry, setDeleteEntry] = useState<NoteEntryV1 | null>(null);
  const editorRef = useRef<EditorView | null>(null);
  const latestDraftRef = useRef(draft);
  const savingRef = useRef(false);
  latestDraftRef.current = draft;
  const dirty = Boolean(document && draft !== document.content);

  const loadFolder = useCallback(
    async (path: string, quiet = false) => {
      if (!quiet) setLoading(true);
      setError(null);
      try {
        const next = await notesApi.listFolder(path);
        setSnapshot(next);
        setFolderPath(next.relativePath);
      } catch (reason) {
        setError(noteError(reason, language));
      } finally {
        if (!quiet) setLoading(false);
      }
    },
    [language],
  );

  useEffect(() => {
    let active = true;
    setLoading(true);
    void notesApi
      .root()
      .then(async (state) => {
        if (!active) return;
        setRoot(state);
        if (state.configured) await loadFolder("");
      })
      .catch((reason) => active && setError(noteError(reason, language)))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [language, loadFolder]);

  const saveDocument = useCallback(
    async (resolution: NoteSaveResolutionV1 = "normal") => {
      if (!document || savingRef.current) return false;
      if (!dirty && resolution === "normal") return true;
      const source = document;
      const content = latestDraftRef.current;
      savingRef.current = true;
      setSaving(true);
      setError(null);
      try {
        const result = await notesApi.save(source, content, resolution);
        if (result.status === "conflict") {
          setConflict({ reason: result.reason, diskDocument: result.diskDocument });
          return false;
        }
        setConflict(null);
        setDocument(result.document);
        if (latestDraftRef.current === content) setDraft(result.document.content);
        setNotice(
          resolution === "copy"
            ? t("已另存 DeskCubby 冲突副本", "Saved a DeskCubby conflict copy")
            : t("笔记已保存", "Note saved"),
        );
        await loadFolder(folderPath, true);
        return true;
      } catch (reason) {
        setError(noteError(reason, language));
        return false;
      } finally {
        savingRef.current = false;
        setSaving(false);
      }
    },
    [dirty, document, folderPath, language, loadFolder, t],
  );

  useEffect(() => {
    if (!dirty || saving || conflict || !document) return;
    const timer = window.setTimeout(() => void saveDocument("normal"), 900);
    return () => window.clearTimeout(timer);
  }, [conflict, dirty, document, draft, saveDocument, saving]);

  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(null), 3_000);
    return () => window.clearTimeout(timer);
  }, [notice]);

  const selectRoot = async () => {
    if (dirty || saving) return;
    setMutating(true);
    setError(null);
    try {
      const selected = await notesApi.selectRoot();
      if (!selected) return;
      setRoot(selected);
      setFolderPath("");
      setDocument(null);
      setDraft("");
      setConflict(null);
      await loadFolder("");
    } catch (reason) {
      setError(noteError(reason, language));
    } finally {
      setMutating(false);
    }
  };

  const openEntry = async (entry: NoteEntryV1) => {
    if (entry.kind === "folder") {
      await loadFolder(entry.relativePath);
      return;
    }
    if (dirty || saving) return;
    setLoadingDocument(true);
    setError(null);
    try {
      const opened = await notesApi.open(entry.relativePath);
      setDocument(opened);
      setDraft(opened.content);
      setConflict(null);
      setMode("source");
    } catch (reason) {
      setError(noteError(reason, language));
    } finally {
      setLoadingDocument(false);
    }
  };

  const confirmName = async (value: string) => {
    const action = nameDialog;
    if (!action || mutating) return;
    setMutating(true);
    setError(null);
    try {
      if (action.entry) {
        const renamed = await notesApi.rename(
          action.entry.relativePath,
          action.entry.kind,
          value,
        );
        if (
          document &&
          (document.relativePath === action.entry.relativePath ||
            document.relativePath.startsWith(`${action.entry.relativePath}/`))
        ) {
          if (action.entry.kind === "note") {
            const reopened = await notesApi.open(renamed.relativePath);
            setDocument(reopened);
            setDraft(reopened.content);
          } else {
            setDocument(null);
            setDraft("");
          }
        }
        setNotice(t("已重命名", "Renamed"));
      } else if (action.kind === "folder") {
        await notesApi.createFolder(folderPath, value);
        setNotice(t("文件夹已创建", "Folder created"));
      } else {
        const created = await notesApi.createNote(folderPath, value);
        setDocument(created);
        setDraft(created.content);
        setMode("source");
        setNotice(t("Markdown 笔记已创建", "Markdown note created"));
      }
      setNameDialog(null);
      await loadFolder(folderPath, true);
    } catch (reason) {
      setError(noteError(reason, language));
    } finally {
      setMutating(false);
    }
  };

  const removeEntry = async () => {
    const entry = deleteEntry;
    if (!entry || mutating || dirty) return;
    setMutating(true);
    setError(null);
    try {
      await notesApi.remove(entry.relativePath, entry.kind);
      if (
        document &&
        (document.relativePath === entry.relativePath ||
          document.relativePath.startsWith(`${entry.relativePath}/`))
      ) {
        setDocument(null);
        setDraft("");
        setConflict(null);
      }
      setDeleteEntry(null);
      setNotice(t("已删除", "Deleted"));
      await loadFolder(folderPath, true);
    } catch (reason) {
      setError(noteError(reason, language));
    } finally {
      setMutating(false);
    }
  };

  const importMedia = async () => {
    if (!document || saving) return;
    setMutating(true);
    setError(null);
    try {
      const imported = await notesApi.selectAndImportMedia(document.relativePath);
      if (!imported) return;
      const view = editorRef.current;
      const current = latestDraftRef.current;
      const ending = current.includes("\r\n") ? "\r\n" : "\n";
      const separator = !current || /[\r\n]$/.test(current) ? "" : ending;
      const insertion = `${separator}${imported.markdown}${ending}`;
      if (view) {
        const position = view.state.selection.main.head;
        view.dispatch({
          changes: { from: position, insert: insertion },
          selection: { anchor: position + insertion.length },
        });
      } else {
        setDraft(`${current}${insertion}`);
      }
      setNotice(
        t(
          "媒体已复制到本次明确选择的笔记库位置",
          "Media copied to the vault location chosen for this upload",
        ),
      );
    } catch (reason) {
      setError(noteError(reason, language));
    } finally {
      setMutating(false);
    }
  };

  const filteredEntries = useMemo(() => {
    const needle = query.trim().toLocaleLowerCase();
    return snapshot?.entries.filter((entry) => !needle || entry.name.toLocaleLowerCase().includes(needle)) ?? [];
  }, [query, snapshot]);

  const breadcrumbs = useMemo(() => {
    const segments = folderPath.split("/").filter(Boolean);
    return [
      { label: root?.displayName || t("笔记库", "Notes"), path: "" },
      ...segments.map((label, index) => ({ label, path: segments.slice(0, index + 1).join("/") })),
    ];
  }, [folderPath, root?.displayName, t]);

  const nameDialogTitle = nameDialog?.entry
    ? t("重命名", "Rename")
    : nameDialog?.kind === "folder"
      ? t("新建文件夹", "New folder")
      : t("新建 Markdown 笔记", "New Markdown note");

  return (
    <main className="page notes-page">
      <UnsavedChangesGuard when={dirty} scope="notes" />
      <header className="page-header">
        <div>
          <p className="eyebrow">{t("Markdown · Obsidian 兼容", "Markdown · Obsidian compatible")}</p>
          <h1>{t("笔记", "Notes")}</h1>
        </div>
        <div className="header-actions">
          <button
            className="button secondary"
            type="button"
            onClick={() => void selectRoot()}
            disabled={dirty || saving || mutating}
            title={dirty ? t("等待当前笔记自动保存", "Wait for the current note to save") : undefined}
          >
            <FolderOpen aria-hidden="true" />
            {root?.configured ? t("更换笔记库", "Change vault") : t("选择笔记库", "Choose vault")}
          </button>
          {root?.configured && (
            <button className="button secondary" type="button" onClick={() => void loadFolder(folderPath)} disabled={loading}>
              <RefreshCw className={loading ? "spin" : ""} aria-hidden="true" />
              {t("刷新", "Refresh")}
            </button>
          )}
        </div>
      </header>

      {error && (
        <div className="inline-error" role="alert">
          {error}
          <button type="button" onClick={() => setError(null)} aria-label={t("关闭", "Close")}>
            <X aria-hidden="true" />
          </button>
        </div>
      )}
      {notice && <div className="toast" role="status">{notice}</div>}

      {!root?.configured ? (
        <section className="panel empty-state notes-root-empty" aria-busy={loading}>
          {loading ? <LoaderCircle className="spin" aria-hidden="true" /> : <FolderOpen aria-hidden="true" />}
          <h2>{t("选择普通笔记目录", "Choose a notes folder")}</h2>
          <p>
            {t(
              "可选择 Obsidian 仓库或普通文件夹。Markdown、文件夹和媒体仍是目录中的真实文件。",
              "Choose an Obsidian vault or a regular folder. Markdown, folders, and media remain real files there.",
            )}
          </p>
          <button className="button primary" type="button" onClick={() => void selectRoot()} disabled={loading || mutating}>
            {t("选择目录", "Choose folder")}
          </button>
        </section>
      ) : (
        <div className="diary-workspace notes-workspace">
          <aside className="diary-list-panel panel" aria-label={t("笔记浏览器", "Notes browser")}>
            <nav className="notes-breadcrumbs" aria-label={t("文件夹路径", "Folder path")}>
              {breadcrumbs.map((crumb, index) => (
                <span key={crumb.path || "root"}>
                  {index > 0 && <ChevronRight aria-hidden="true" />}
                  <button type="button" onClick={() => void loadFolder(crumb.path)} disabled={loading}>
                    {crumb.label}
                  </button>
                </span>
              ))}
            </nav>
            <div className="notes-browser-actions">
              <button className="button secondary" type="button" onClick={() => setNameDialog({ kind: "folder" })} disabled={mutating}>
                <FolderPlus aria-hidden="true" /> {t("文件夹", "Folder")}
              </button>
              <button className="button primary" type="button" onClick={() => setNameDialog({ kind: "note" })} disabled={mutating}>
                <FilePlus2 aria-hidden="true" /> {t("笔记", "Note")}
              </button>
            </div>
            <label className="search-field">
              <Search aria-hidden="true" />
              <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t("搜索当前文件夹", "Search this folder")} />
            </label>
            {loading ? (
              <div className="panel-loading" aria-busy="true">
                <LoaderCircle className="spin" aria-hidden="true" />
                <span>{t("正在读取文件夹…", "Loading folder…")}</span>
              </div>
            ) : filteredEntries.length ? (
              <div className="notes-entry-list" role="list">
                {filteredEntries.map((entry) => (
                  <div className={document?.relativePath === entry.relativePath ? "notes-entry selected" : "notes-entry"} key={entry.relativePath} role="listitem">
                    <button
                      className="notes-entry-main"
                      type="button"
                      onClick={() => void openEntry(entry)}
                      disabled={entry.kind === "note" && (dirty || saving)}
                    >
                      {entry.kind === "folder" ? <Folder aria-hidden="true" /> : <FileText aria-hidden="true" />}
                      <span>
                        <strong>{entry.kind === "note" ? entry.name.replace(/\.md$/i, "") : entry.name}</strong>
                        <small>{entry.kind === "folder" ? t("文件夹", "Folder") : formatSize(entry.size)}</small>
                      </span>
                    </button>
                    <span className="row-actions">
                      <button className="icon-button" type="button" aria-label={t(`重命名 ${entry.name}`, `Rename ${entry.name}`)} onClick={() => setNameDialog({ kind: entry.kind, entry })} disabled={dirty || mutating}>
                        <PencilLine aria-hidden="true" />
                      </button>
                      <button className="icon-button danger" type="button" aria-label={t(`删除 ${entry.name}`, `Delete ${entry.name}`)} onClick={() => setDeleteEntry(entry)} disabled={dirty || mutating}>
                        <Trash2 aria-hidden="true" />
                      </button>
                    </span>
                  </div>
                ))}
              </div>
            ) : (
              <div className="empty-state notes-folder-empty">
                <FolderOpen aria-hidden="true" />
                <h2>{query ? t("没有匹配项目", "No matching items") : t("这个文件夹是空的", "This folder is empty")}</h2>
                <p>{t("非 Markdown 文件会保留在磁盘上，但不显示在列表中。", "Non-Markdown files stay on disk but are not shown here.")}</p>
              </div>
            )}
          </aside>

          <section className="diary-editor-panel panel">
            {loadingDocument ? (
              <div className="page-centered" aria-busy="true">
                <LoaderCircle className="spin" aria-hidden="true" />
                <p>{t("正在打开笔记…", "Opening note…")}</p>
              </div>
            ) : document ? (
              <>
                <header className="editor-header">
                  <div>
                    <h2>{document.name}</h2>
                    <p className="muted">
                      {conflict
                        ? t("发现外部修改 · 自动保存已暂停", "External change found · autosave paused")
                        : saving
                          ? t("正在保存…", "Saving…")
                          : dirty
                            ? t("等待自动保存", "Waiting to autosave")
                            : t("已保存", "Saved")}
                    </p>
                  </div>
                  <div className="header-actions">
                    <div className="segmented" role="group" aria-label={t("编辑模式", "Editor mode")}>
                      <button type="button" className={mode === "source" ? "selected" : undefined} onClick={() => setMode("source")}>
                        <PencilLine aria-hidden="true" /> {t("源码", "Source")}
                      </button>
                      <button type="button" className={mode === "preview" ? "selected" : undefined} onClick={() => setMode("preview")}>
                        <Eye aria-hidden="true" /> {t("预览", "Preview")}
                      </button>
                    </div>
                    <button className="icon-button" type="button" onClick={() => void importMedia()} disabled={saving || mutating} aria-label={t("上传媒体", "Upload media")} title={t("每次上传都重新选择笔记库内目标文件夹", "Choose a destination inside the vault for every upload")}>
                      <ImagePlus aria-hidden="true" />
                    </button>
                    <button className="button primary" type="button" onClick={() => void saveDocument("normal")} disabled={!dirty || saving || Boolean(conflict)}>
                      {saving ? <LoaderCircle className="spin" aria-hidden="true" /> : <Save aria-hidden="true" />}
                      {t("保存", "Save")}
                    </button>
                  </div>
                </header>
                {mode === "source" ? (
                  <MarkdownEditor
                    value={draft}
                    onChange={setDraft}
                    onSave={() => void saveDocument("normal")}
                    editorRef={editorRef}
                    label={t("Markdown 笔记正文", "Markdown note content")}
                  />
                ) : (
                  <NotePreview
                    document={document}
                    content={draft}
                    language={language}
                    onOpenError={setError}
                  />
                )}
                <footer className="editor-footer">
                  <span>{draft.length.toLocaleString()} {t("字符", "characters")}</span>
                  <span>{formatSize(document.version.size)} · {new Date(document.version.modifiedAt).toLocaleString()}</span>
                </footer>
              </>
            ) : (
              <div className="empty-state editor-empty">
                <FileText aria-hidden="true" />
                <h2>{t("选择一篇笔记", "Choose a note")}</h2>
                <p>{t("文件夹优先显示；选择 Markdown 文件即可编辑并自动保存。", "Folders appear first. Choose a Markdown file to edit with autosave.")}</p>
              </div>
            )}
          </section>
        </div>
      )}

      <NameDialog
        open={Boolean(nameDialog)}
        title={nameDialogTitle}
        initialValue={nameDialog?.entry?.name ?? ""}
        busy={mutating}
        language={language}
        onCancel={() => setNameDialog(null)}
        onConfirm={(value) => void confirmName(value)}
      />
      <ConfirmDialog
        open={Boolean(deleteEntry)}
        title={deleteEntry?.kind === "folder" ? t("删除文件夹？", "Delete folder?") : t("删除笔记？", "Delete note?")}
        description={
          deleteEntry?.kind === "folder"
            ? t(`“${deleteEntry.name}”及其中全部文件会被永久删除。此操作无法撤回。`, `“${deleteEntry.name}” and everything inside it will be permanently deleted. This cannot be undone.`)
            : t(`“${deleteEntry?.name ?? ""}”会被永久删除。此操作无法撤回。`, `“${deleteEntry?.name ?? ""}” will be permanently deleted. This cannot be undone.`)
        }
        confirmLabel={t("删除", "Delete")}
        destructive
        busy={mutating}
        onCancel={() => setDeleteEntry(null)}
        onConfirm={() => void removeEntry()}
      />

      {conflict && document && (
        <AlertDialog.Root open>
          <AlertDialog.Portal>
            <AlertDialog.Overlay className="dialog-backdrop" />
            <AlertDialog.Content
              className="dialog conflict-dialog"
              aria-labelledby="note-conflict-title"
              aria-describedby="note-conflict-description"
              onEscapeKeyDown={(event) => event.preventDefault()}
            >
            <header>
              <AlertDialog.Title id="note-conflict-title">
                {conflict.reason === "deleted" ? t("文件已被外部删除", "File deleted externally") : t("文件已在外部修改", "File changed externally")}
              </AlertDialog.Title>
              <AlertTriangle aria-hidden="true" />
            </header>
            <AlertDialog.Description id="note-conflict-description">
              {conflict.reason === "deleted"
                ? t("原文件已不存在。自动保存已暂停；可接受删除、明确重建原文件，或另存冲突副本。", "The original no longer exists. Autosave is paused; accept deletion, explicitly recreate it, or save a conflict copy.")
                : t("Obsidian 或其他应用修改了磁盘内容。自动保存已暂停，不会自行覆盖。", "Obsidian or another app changed the file. Autosave is paused and will not overwrite it.")}
            </AlertDialog.Description>
            <footer className="stacked-actions">
              <button className="button secondary" type="button" onClick={() => {
                if (conflict.diskDocument) {
                  setDocument(conflict.diskDocument);
                  setDraft(conflict.diskDocument.content);
                } else {
                  setDocument(null);
                  setDraft("");
                  void loadFolder(parentPath(document.relativePath), true);
                }
                setConflict(null);
              }}>
                <RefreshCw aria-hidden="true" />
                {conflict.reason === "deleted" ? t("接受删除并关闭", "Accept deletion and close") : t("加载磁盘版本", "Load disk version")}
              </button>
              <button className="button secondary" type="button" onClick={() => void saveDocument("copy")} disabled={saving}>
                <FilePlus2 aria-hidden="true" /> {t("另存 DeskCubby 冲突副本", "Save a DeskCubby conflict copy")}
              </button>
              <button className="button danger" type="button" onClick={() => void saveDocument("overwrite")} disabled={saving}>
                <Save aria-hidden="true" /> {t("明确覆盖", "Explicitly overwrite")}
              </button>
            </footer>
            </AlertDialog.Content>
          </AlertDialog.Portal>
        </AlertDialog.Root>
      )}
    </main>
  );
}
