/**
 * 笔记 NotesPage (/notes) — faithful web port of Android ui/notes/NotesScreens.kt
 * (README_for_ai.md 笔记页面): Obsidian-compatible vault browser + Markdown editor.
 *
 * Server-side contract (web/backend/app/routers/notes.py):
 *   GET    /api/notes/tree                       {location:{name}, root:{children}}
 *   GET    /api/notes/file?path=                 NoteDocument{content, version.sha256,...}
 *   PUT    /api/notes/file {path,content,previousSha256}  -> doc | 409{currentSha256,content}
 *   POST   /api/notes/folder      {parent,name}  -> entry
 *   POST   /api/notes/file-create {parent,name}  -> NoteDocument (opens immediately)
 *   POST   /api/notes/rename      {path,newName} -> {ok, entry}
 *   DELETE /api/notes/node?path=
 *   GET    /api/notes/search?q=                  [{path,name,nameMatch,matches[]}]
 *   POST   /api/notes/media-upload?targetFolder= (multipart) -> {fileName,markdownTarget,markdown}
 *
 * ≥1000px: folder-tree sidebar + list + editor split; below that the editor becomes a
 * full-page detail with a back button. Autosave debounces 800ms; manual 保存 in the top
 * bar. External-modification 409 opens 重新加载/覆盖/另存副本 (覆盖 resends with the
 * returned currentSha256; 另存副本 creates a "DeskCubby conflict" copy). `![[Wiki]]`
 * links resolve case-insensitively within the loaded tree and navigate on click.
 */
import React, {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from "react";
import ReactMarkdown, { defaultUrlTransform } from "react-markdown";
import {
  BookOpen,
  ChevronDown,
  ChevronRight,
  Code2,
  FileText,
  Folder,
  FolderPlus,
  ImagePlus,
  MoreVertical,
  Pencil,
  Plus,
  Save,
  Search,
  Trash2,
  X,
} from "lucide-react";
import { ApiClientError, apiGet, apiSend, apiUpload } from "../../api/client";
import { tr } from "../../i18n/tr";
import { useSettings } from "../../stores/settings";
import {
  ConfirmDialog,
  EmptyState,
  Modal,
  PopupMenu,
  Snackbar,
  Spinner,
  TopBar,
  useDirtyGuard,
  useSnackbar,
} from "../../components/ui";

const WIDE_BREAKPOINT = 1000;
const AUTOSAVE_DEBOUNCE_MS = 800;
const SEARCH_DEBOUNCE_MS = 300;

interface NoteNode {
  name: string;
  path: string;
  isFolder: boolean;
  size: number;
  lastModified: number;
  children?: NoteNode[];
}

interface NotesTree {
  location: { name: string; relativePath: string };
  root: NoteNode;
}

interface NoteDocument {
  uri: string;
  path: string;
  folderRelativePath: string;
  name: string;
  content: string;
  version: { sha256: string; size: number; lastModified: number };
}

interface ConflictInfo {
  currentSha256?: string;
  content?: string;
  lastModified?: number;
}

interface SearchResult {
  path: string;
  name: string;
  nameMatch: boolean;
  matches: { line: number; text: string }[];
}

interface ImportedNoteMedia {
  fileName: string;
  markdownTarget: string;
  markdown: string;
}

type SaveState = "saved" | "dirty" | "saving" | "conflict";

function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/** Mirrors Android NotesScreens.kt formatNoteSize. */
function formatNoteSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.floor(bytes / 1024)} KiB`;
  return `${(bytes / 1048576.0).toFixed(1)} MiB`;
}

function noteStem(name: string): string {
  return name.replace(/\.md$/i, "");
}

function parentOf(path: string): string {
  const idx = path.lastIndexOf("/");
  return idx === -1 ? "" : path.slice(0, idx);
}

function findFolder(root: NoteNode | null, path: string): NoteNode | null {
  if (!root) return null;
  if (!path) return root;
  let node: NoteNode | undefined = root;
  for (const segment of path.split("/")) {
    node = (node.children ?? []).find((c) => c.isFolder && c.name === segment);
    if (!node) return null;
  }
  return node ?? null;
}

function flattenFolders(root: NoteNode): { name: string; path: string; depth: number }[] {
  const out: { name: string; path: string; depth: number }[] = [];
  const walk = (node: NoteNode, depth: number) => {
    out.push({ name: node.name, path: node.path, depth });
    for (const child of node.children ?? []) if (child.isFolder) walk(child, depth + 1);
  };
  walk(root, 0);
  return out;
}

/** Case-insensitive `![[Name]]` resolution within the loaded tree (bounded BFS). */
function resolveWikiInTree(root: NoteNode | null, rawName: string): string | null {
  const wanted = rawName.trim().toLowerCase();
  if (!root || !wanted) return null;
  const candidates = new Set([wanted, wanted.endsWith(".md") ? wanted : `${wanted}.md`]);
  const queue: NoteNode[] = [root];
  while (queue.length > 0) {
    const node = queue.shift() as NoteNode;
    for (const child of node.children ?? []) {
      if (!child.isFolder && candidates.has(child.name.toLowerCase())) return child.path;
      if (child.isFolder) queue.push(child);
    }
  }
  return null;
}

/** Rewrites `[[Name]]` / `![[Name]]` into clickable wiki links for the preview. */
function convertWikiLinks(content: string): string {
  return content.replace(/(!?)\[\[([^[\]\n]+?)\]\]/g, (_match, bang: string, target: string) => {
    const label = String(target).trim();
    return `[${label}](wiki:${encodeURIComponent(label)})`;
  });
}

/** PUT with raw fetch so a 409 conflict body can be read for the dialog. */
async function putNoteFile(payload: {
  path: string;
  content: string;
  previousSha256?: string;
}): Promise<{ ok: true; doc?: NoteDocument } | { ok: false; status: number; conflict?: ConflictInfo }> {
  const resp = await fetch("/api/notes/file", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (resp.status === 401 && !location.pathname.startsWith("/login")) {
    location.href = "/login";
    throw new ApiClientError(401, "unauthorized", "Authentication required");
  }
  if (resp.ok) {
    const text = await resp.text();
    return { ok: true, doc: text ? (JSON.parse(text) as NoteDocument) : undefined };
  }
  if (resp.status === 409) {
    let conflict: ConflictInfo | undefined;
    try {
      const data = await resp.json();
      conflict = {
        currentSha256: data?.currentSha256 ?? data?.error?.currentSha256,
        content: data?.content ?? data?.error?.content,
        lastModified: data?.lastModified ?? data?.error?.lastModified,
      };
    } catch {
      /* no parseable body */
    }
    return { ok: false, status: 409, conflict };
  }
  let message = `Request failed (${resp.status})`;
  try {
    const data = await resp.json();
    if (data?.error?.message) message = String(data.error.message);
  } catch {
    /* keep default */
  }
  throw new ApiClientError(resp.status, "http_" + resp.status, message);
}

export default function NotesPage() {
  const [snack, showSnack] = useSnackbar();

  const [tree, setTree] = useState<NotesTree | null>(null);
  const [treeError, setTreeError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [mutating, setMutating] = useState(false);

  const [currentPath, setCurrentPath] = useState("");
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set([""]));

  const [openPath, setOpenPath] = useState<string | null>(null);
  const [editorSession, setEditorSession] = useState(0);
  const editorRef = useRef<EditorHandle | null>(null);

  const [wide, setWide] = useState(() =>
    typeof window === "undefined" ? true : window.innerWidth >= WIDE_BREAKPOINT,
  );

  const [query, setQuery] = useState("");
  const [results, setResults] = useState<SearchResult[] | null>(null);
  const [searching, setSearching] = useState(false);

  const [newFolderOpen, setNewFolderOpen] = useState(false);
  const [newNoteOpen, setNewNoteOpen] = useState(false);
  const [renameTarget, setRenameTarget] = useState<NoteNode | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<NoteNode | null>(null);
  const [menu, setMenu] = useState<{ x: number; y: number; entry: NoteNode } | null>(null);
  const [errorDialog, setErrorDialog] = useState<string | null>(null);

  useEffect(() => {
    const onResize = () => setWide(window.innerWidth >= WIDE_BREAKPOINT);
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  const loadTree = useCallback(async () => {
    setLoading(true);
    setTreeError(null);
    try {
      const data = await apiGet<NotesTree>("/api/notes/tree");
      setTree(data);
    } catch (e) {
      setTreeError(errMsg(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadTree();
  }, [loadTree]);

  // Debounced vault-wide search; an empty query returns to the folder list.
  useEffect(() => {
    const q = query.trim();
    if (!q) {
      setResults(null);
      setSearching(false);
      return;
    }
    setSearching(true);
    const timer = window.setTimeout(async () => {
      try {
        const data = await apiGet<SearchResult[]>(`/api/notes/search?q=${encodeURIComponent(q)}`);
        setResults(Array.isArray(data) ? data : []);
      } catch {
        setResults([]);
      } finally {
        setSearching(false);
      }
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [query]);

  const mutate = useCallback(async (fn: () => Promise<void>) => {
    if (mutating) return;
    setMutating(true);
    try {
      await fn();
    } catch (e) {
      setErrorDialog(errMsg(e));
    } finally {
      setMutating(false);
    }
  }, [mutating]);

  const selectFolder = useCallback((node: NoteNode) => {
    setCurrentPath(node.path);
    setResults(null);
    setQuery("");
    setExpanded((prev) => {
      const next = new Set(prev);
      next.add(node.path);
      return next;
    });
  }, []);

  const openFromList = useCallback((path: string) => {
    setOpenPath(path);
    setEditorSession((s) => s + 1);
  }, []);

  const createFolder = useCallback(
    (name: string) => {
      void mutate(async () => {
        await apiSend("/api/notes/folder", "POST", { parent: currentPath, name });
        setNewFolderOpen(false);
        showSnack(tr("文件夹已创建", "Folder created"));
        await loadTree();
      });
    },
    [currentPath, loadTree, mutate, showSnack],
  );

  const createNote = useCallback(
    (name: string) => {
      void mutate(async () => {
        const doc = await apiSend<NoteDocument>("/api/notes/file-create", "POST", {
          parent: currentPath,
          name,
        });
        setNewNoteOpen(false);
        await loadTree();
        openFromList(doc.path || (currentPath ? `${currentPath}/${doc.name}` : doc.name));
      });
    },
    [currentPath, loadTree, mutate, openFromList],
  );

  const renameEntry = useCallback(
    (entry: NoteNode, name: string) => {
      void mutate(async () => {
        const res = await apiSend<{ ok: boolean; entry: NoteNode }>("/api/notes/rename", "POST", {
          path: entry.path,
          newName: name,
        });
        const parent = parentOf(entry.path);
        const newPath = parent ? `${parent}/${res.entry.name}` : res.entry.name;
        // Keep the open editor (and its unsaved draft) when its own path was renamed.
        if (openPath === entry.path) {
          editorRef.current?.adoptPath(newPath, res.entry.name);
          setOpenPath(newPath);
        } else if (openPath && entry.isFolder && openPath.startsWith(`${entry.path}/`)) {
          const moved = `${newPath}${openPath.slice(entry.path.length)}`;
          editorRef.current?.adoptPath(moved, noteStem(openPath).split("/").pop() ?? "");
          setOpenPath(moved);
        }
        if (entry.isFolder) {
          if (currentPath === entry.path || currentPath.startsWith(`${entry.path}/`)) {
            setCurrentPath(`${newPath}${currentPath.slice(entry.path.length)}`);
          }
          setExpanded((prev) => {
            const next = new Set<string>();
            for (const p of prev) {
              if (p === entry.path || p.startsWith(`${entry.path}/`)) {
                next.add(`${newPath}${p.slice(entry.path.length)}`);
              } else {
                next.add(p);
              }
            }
            return next;
          });
        }
        setRenameTarget(null);
        showSnack(tr("已重命名", "Renamed"));
        await loadTree();
      });
    },
    [currentPath, loadTree, mutate, openPath, showSnack],
  );

  const deleteEntry = useCallback(
    (entry: NoteNode) => {
      void mutate(async () => {
        await apiSend(`/api/notes/node?path=${encodeURIComponent(entry.path)}`, "DELETE");
        if (openPath && (openPath === entry.path || openPath.startsWith(`${entry.path}/`))) {
          setOpenPath(null);
        }
        if (
          entry.isFolder &&
          (currentPath === entry.path || currentPath.startsWith(`${entry.path}/`))
        ) {
          setCurrentPath(parentOf(entry.path));
        }
        setDeleteTarget(null);
        showSnack(tr("已删除", "Deleted"));
        await loadTree();
      });
    },
    [currentPath, loadTree, mutate, openPath, showSnack],
  );

  const entries = useMemo<NoteNode[]>(() => {
    if (results !== null) return [];
    const folder = tree ? findFolder(tree.root, currentPath) : null;
    return folder?.children ?? [];
  }, [tree, currentPath, results]);

  const breadcrumbs = useMemo(() => {
    const segments = currentPath ? currentPath.split("/") : [];
    return [
      { name: tree?.location?.name || tr("笔记库", "Notes vault"), path: "" },
      ...segments.map((segment, i) => ({
        name: segment,
        path: segments.slice(0, i + 1).join("/"),
      })),
    ];
  }, [currentPath, tree]);

  const searchingActive = query.trim().length > 0;

  const browserPane = (
    <section className="dc-col" style={{ gap: 8, minWidth: 0 }}>
      {/* Breadcrumb capsules — click any level to jump straight back to it. */}
      <div className="dc-row dc-wrap" style={{ gap: 6 }}>
        {breadcrumbs.map((crumb, i) => (
          <button
            key={crumb.path || "__root"}
            className={`dc-chip ${i === breadcrumbs.length - 1 ? "active" : ""}`}
            style={{ maxWidth: 220, cursor: "pointer" }}
            onClick={() => {
              setCurrentPath(crumb.path);
              setResults(null);
            }}
            title={crumb.name}
          >
            <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
              {crumb.name}
            </span>
          </button>
        ))}
      </div>

      {/* Search box */}
      <div className="dc-row" style={{ gap: 6 }}>
        <Search size={16} className="dc-muted" aria-hidden />
        <input
          className="dc-input dc-grow"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={tr("搜索", "Search")}
          aria-label={tr("搜索", "Search")}
        />
        {searchingActive && (
          <button
            className="dc-icon-btn"
            aria-label={tr("取消", "Cancel")}
            onClick={() => setQuery("")}
          >
            <X size={16} />
          </button>
        )}
      </div>

      {searchingActive ? (
        results === null ? (
          <Spinner />
        ) : results.length === 0 ? (
          <EmptyState
            icon={<FileText size={44} />}
            title={tr("没有找到匹配的笔记", "No matching notes")}
            hint={tr("换个关键词试试", "Try a different keyword")}
          />
        ) : (
          <div className="dc-col" style={{ gap: 8 }}>
            {results.map((r) => (
              <div
                key={r.path}
                className="dc-card"
                role="button"
                tabIndex={0}
                style={{ padding: "10px 12px", cursor: "pointer" }}
                onClick={() => openFromList(r.path)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    openFromList(r.path);
                  }
                }}
              >
                <div className="dc-row" style={{ gap: 10 }}>
                  <FileText size={20} style={{ color: "var(--dc-primary)", flexShrink: 0 }} />
                  <div className="dc-grow" style={{ minWidth: 0 }}>
                    <div style={{ fontWeight: 600 }}>{noteStem(r.name)}</div>
                    <div className="dc-muted" style={{ fontSize: "0.82em", wordBreak: "break-all" }}>
                      {r.path}
                    </div>
                    {r.matches.map((m) => (
                      <div
                        key={m.line}
                        className="dc-muted"
                        style={{
                          fontSize: "0.82em",
                          marginTop: 2,
                          overflow: "hidden",
                          textOverflow: "ellipsis",
                          whiteSpace: "nowrap",
                        }}
                      >
                        {m.text}
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            ))}
          </div>
        )
      ) : loading && !tree ? (
        <Spinner />
      ) : treeError ? (
        <div role="alert" className="dc-col dc-center" style={{ padding: 32 }}>
          <div style={{ color: "var(--dc-error)", fontSize: "0.9em" }}>{treeError}</div>
          <button className="dc-btn dc-btn-tonal" style={{ marginTop: 10 }} onClick={() => void loadTree()}>
            {tr("重试", "Retry")}
          </button>
        </div>
      ) : entries.length === 0 ? (
        <EmptyState
          icon={<FileText size={44} />}
          title={tr("这个文件夹是空的", "This folder is empty")}
          hint={tr(
            "可新建 Markdown 笔记或子文件夹。非 Markdown 文件会保留在磁盘上，但不出现在列表中。",
            "Create a Markdown note or subfolder. Non-Markdown files remain on disk but are not listed here.",
          )}
        />
      ) : (
        <div className="dc-col" style={{ gap: 8 }}>
          {entries.map((entry) => (
            <div key={entry.path} className="dc-card" style={{ padding: 0 }}>
              <div
                role="button"
                tabIndex={0}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 12,
                  padding: 12,
                  cursor: "pointer",
                  borderRadius: "inherit",
                }}
                onClick={() => {
                  if (entry.isFolder) selectFolder(entry);
                  else openFromList(entry.path);
                }}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    if (entry.isFolder) selectFolder(entry);
                    else openFromList(entry.path);
                  }
                }}
              >
                {entry.isFolder ? (
                  <Folder size={22} style={{ color: "var(--dc-primary)", flexShrink: 0 }} />
                ) : (
                  <FileText size={22} style={{ color: "var(--dc-primary)", flexShrink: 0 }} />
                )}
                <div className="dc-grow" style={{ minWidth: 0 }}>
                  <div
                    style={{
                      fontWeight: 600,
                      display: "-webkit-box",
                      WebkitLineClamp: 2,
                      WebkitBoxOrient: "vertical",
                      overflow: "hidden",
                      wordBreak: "break-word",
                    }}
                  >
                    {noteStem(entry.name)}
                  </div>
                  <div className="dc-muted" style={{ fontSize: "0.85em" }}>
                    {entry.isFolder ? tr("文件夹", "Folder") : formatNoteSize(entry.size)}
                  </div>
                </div>
                <button
                  className="dc-icon-btn"
                  aria-label={tr("更多操作", "More actions")}
                  disabled={mutating}
                  onClick={(e) => {
                    e.stopPropagation();
                    setMenu({ x: e.clientX, y: e.clientY, entry });
                  }}
                >
                  <MoreVertical size={18} />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );

  const editorPane =
    openPath !== null ? (
      <NoteEditor
        key={editorSession}
        ref={editorRef}
        path={openPath}
        tree={tree}
        wide={wide}
        onClose={() => setOpenPath(null)}
        onOpenPath={setOpenPath}
        onOpenNote={openFromList}
        onTreeChanged={() => void loadTree()}
        showSnack={showSnack}
      />
    ) : null;

  return (
    <div className="dc-page">
      <Snackbar message={snack} />

      <TopBar
        title={tr("笔记", "Notes")}
        subtitle={tree?.location?.name}
        actions={
          <>
            <button
              className="dc-icon-btn"
              aria-label={tr("新建文件夹", "New folder")}
              title={tr("新建文件夹", "New folder")}
              disabled={!tree || mutating}
              onClick={() => setNewFolderOpen(true)}
            >
              <FolderPlus size={20} />
            </button>
            <button
              className="dc-icon-btn"
              aria-label={tr("新建笔记", "New note")}
              title={tr("新建笔记", "New note")}
              disabled={!tree || mutating}
              onClick={() => setNewNoteOpen(true)}
            >
              <Plus size={20} />
            </button>
          </>
        }
      />

      {(loading || mutating) && (
        <div
          style={{
            height: 3,
            borderRadius: 2,
            overflow: "hidden",
            background: "var(--dc-surface-variant)",
            marginBottom: 8,
          }}
        >
          <div
            style={{
              height: "100%",
              width: "40%",
              background: "var(--dc-primary)",
              animation: "dc-indeterminate 1.1s ease-in-out infinite",
            }}
          />
          <style>{`@keyframes dc-indeterminate { 0% { transform: translateX(-100%);} 100% { transform: translateX(280%);} }`}</style>
        </div>
      )}

      {wide ? (
        <div
          style={{
            display: "grid",
            gridTemplateColumns:
              openPath !== null ? "230px minmax(240px, 330px) minmax(0, 1fr)" : "230px minmax(0, 1fr)",
            gap: 14,
            alignItems: "start",
          }}
        >
          <aside style={{ minWidth: 0 }}>
            <FolderTreePanel
              tree={tree}
              currentPath={currentPath}
              expanded={expanded}
              onSelect={selectFolder}
              onToggle={(path) =>
                setExpanded((prev) => {
                  const next = new Set(prev);
                  if (next.has(path)) next.delete(path);
                  else next.add(path);
                  return next;
                })
              }
            />
          </aside>
          {browserPane}
          {editorPane}
        </div>
      ) : openPath !== null ? (
        editorPane
      ) : (
        browserPane
      )}

      <PopupMenu
        open={menu !== null}
        onClose={() => setMenu(null)}
        x={menu?.x ?? 0}
        y={menu?.y ?? 0}
        items={[
          {
            label: (
              <span className="dc-row" style={{ gap: 8 }}>
                <Pencil size={16} /> {tr("重命名", "Rename")}
              </span>
            ),
            onClick: () => {
              if (menu) setRenameTarget(menu.entry);
            },
          },
          {
            label: (
              <span className="dc-row" style={{ gap: 8, color: "var(--dc-error)" }}>
                <Trash2 size={16} /> {tr("删除", "Delete")}
              </span>
            ),
            danger: true,
            onClick: () => {
              if (menu) setDeleteTarget(menu.entry);
            },
          },
        ]}
      />

      <NameDialog
        open={newFolderOpen}
        title={tr("新建文件夹", "New folder")}
        initial=""
        onDismiss={() => setNewFolderOpen(false)}
        onConfirm={createFolder}
      />
      <NameDialog
        open={newNoteOpen}
        title={tr("新建 Markdown 笔记", "New Markdown note")}
        initial=""
        onDismiss={() => setNewNoteOpen(false)}
        onConfirm={createNote}
      />
      <NameDialog
        open={renameTarget !== null}
        title={tr("重命名", "Rename")}
        initial={renameTarget?.name ?? ""}
        onDismiss={() => setRenameTarget(null)}
        onConfirm={(name) => {
          if (renameTarget) renameEntry(renameTarget, name);
        }}
      />

      <ConfirmDialog
        open={deleteTarget !== null}
        title={
          deleteTarget?.isFolder
            ? tr("删除文件夹？", "Delete folder?")
            : tr("删除笔记？", "Delete note?")
        }
        message={
          deleteTarget?.isFolder
            ? tr(
                `“${deleteTarget?.name ?? ""}”及其中全部文件会由存储服务删除。此操作无法撤回。`,
                `“${deleteTarget?.name ?? ""}” and all files inside it will be deleted by the storage provider. This cannot be undone.`,
              )
            : tr(
                `“${deleteTarget?.name ?? ""}”会被删除。此操作无法撤回。`,
                `“${deleteTarget?.name ?? ""}” will be deleted. This cannot be undone.`,
              )
        }
        confirmLabel={tr("删除", "Delete")}
        danger
        onConfirm={() => {
          if (deleteTarget) void deleteEntry(deleteTarget);
        }}
        onCancel={() => setDeleteTarget(null)}
      />

      <Modal open={errorDialog !== null} onClose={() => setErrorDialog(null)} title={tr("笔记操作失败", "Notes operation failed")}>
        <div className="dc-muted" style={{ marginBottom: 12, wordBreak: "break-word" }}>
          {errorDialog}
        </div>
        <div className="dc-row" style={{ justifyContent: "flex-end" }}>
          <button className="dc-btn dc-btn-filled" onClick={() => setErrorDialog(null)}>
            {tr("知道了", "OK")}
          </button>
        </div>
      </Modal>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Folder tree sidebar (desktop)
// ---------------------------------------------------------------------------

function FolderTreePanel(props: {
  tree: NotesTree | null;
  currentPath: string;
  expanded: Set<string>;
  onSelect: (node: NoteNode) => void;
  onToggle: (path: string) => void;
}) {
  if (!props.tree) return null;
  return (
    <nav aria-label={props.tree.location.name} className="dc-col" style={{ gap: 0 }}>
      <TreeNodeRow
        node={props.tree.root}
        depth={0}
        currentPath={props.currentPath}
        expanded={props.expanded}
        onSelect={props.onSelect}
        onToggle={props.onToggle}
      />
    </nav>
  );
}

function TreeNodeRow(props: {
  node: NoteNode;
  depth: number;
  currentPath: string;
  expanded: Set<string>;
  onSelect: (node: NoteNode) => void;
  onToggle: (path: string) => void;
}) {
  const { node, depth } = props;
  const subFolders = useMemo(
    () => (node.children ?? []).filter((c) => c.isFolder),
    [node.children],
  );
  const isOpen = props.expanded.has(node.path);
  const active = props.currentPath === node.path;
  const hasChildren = subFolders.length > 0;
  return (
    <>
      <div
        role="button"
        tabIndex={0}
        style={{
          display: "flex",
          alignItems: "center",
          gap: 2,
          padding: "5px 6px",
          borderRadius: 8,
          cursor: "pointer",
          background: active ? "var(--dc-secondary-container)" : "transparent",
          color: active ? "var(--dc-on-secondary-container)" : "var(--dc-on-surface)",
          paddingLeft: 6 + depth * 14,
        }}
        onClick={() => props.onSelect(node)}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            props.onSelect(node);
          }
        }}
        title={node.name}
      >
        <span
          aria-hidden
          style={{
            width: 20,
            height: 20,
            display: "inline-flex",
            alignItems: "center",
            justifyContent: "center",
            flexShrink: 0,
            opacity: hasChildren ? 1 : 0.3,
          }}
          onClick={(e) => {
            e.stopPropagation();
            if (hasChildren) props.onToggle(node.path);
          }}
        >
          {isOpen ? <ChevronDown size={15} /> : <ChevronRight size={15} />}
        </span>
        <Folder size={15} style={{ color: "var(--dc-primary)", flexShrink: 0 }} />
        <span
          style={{
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
            fontSize: "0.92em",
          }}
        >
          {node.name}
        </span>
      </div>
      {isOpen &&
        subFolders.map((folder) => (
          <TreeNodeRow
            key={folder.path}
            node={folder}
            depth={depth + 1}
            currentPath={props.currentPath}
            expanded={props.expanded}
            onSelect={props.onSelect}
            onToggle={props.onToggle}
          />
        ))}
    </>
  );
}

// ---------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------

interface EditorHandle {
  /** Updates the editor's target path after an external rename without losing the draft. */
  adoptPath(path: string, name: string): void;
}

interface EditorProps {
  path: string;
  tree: NotesTree | null;
  wide: boolean;
  onClose: () => void;
  /** Switches the page-level open path without remounting this editor. */
  onOpenPath: (path: string) => void;
  /** Opens another note from a wiki-link click (remounts with fresh state). */
  onOpenNote: (path: string) => void;
  onTreeChanged: () => void;
  showSnack: (message: string) => void;
}

const NoteEditor = forwardRef<EditorHandle, EditorProps>(function NoteEditor(props, ref) {
  const { wide, tree } = props;

  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [docName, setDocName] = useState(() => noteStem(props.path.split("/").pop() ?? ""));
  const [folderRel, setFolderRel] = useState(() => parentOf(props.path));
  const [draft, setDraft] = useState("");
  const savedContent = useRef("");
  const shaRef = useRef("");
  const pathRef = useRef(props.path);
  const [saveState, setSaveState] = useState<SaveState>("saved");
  const [preview, setPreview] = useState(false);
  const [conflict, setConflict] = useState<ConflictInfo | null>(null);
  const conflictRef = useRef<ConflictInfo | null>(null);
  const savingRef = useRef(false);
  const saveTimer = useRef<number | null>(null);

  const [uploading, setUploading] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [pickerTarget, setPickerTarget] = useState("");
  const pendingFileRef = useRef<File | null>(null);
  const taRef = useRef<HTMLTextAreaElement | null>(null);
  const fileRef = useRef<HTMLInputElement | null>(null);
  const [errorDialog, setErrorDialog] = useState<string | null>(null);

  const dirty = draft !== savedContent.current;

  const setConflictInfo = (info: ConflictInfo | null) => {
    conflictRef.current = info;
    setConflict(info);
  };

  useImperativeHandle(ref, () => ({
    adoptPath(path: string, name: string) {
      pathRef.current = path;
      setDocName(name);
      setFolderRel(parentOf(path));
    },
  }));

  useDirtyGuard(dirty && conflict === null);

  const loadDocument = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const doc = await apiGet<NoteDocument>(
        `/api/notes/file?path=${encodeURIComponent(pathRef.current)}`,
      );
      pathRef.current = doc.path || pathRef.current;
      setDocName(doc.name);
      setFolderRel(doc.folderRelativePath ?? "");
      setDraft(doc.content ?? "");
      savedContent.current = doc.content ?? "";
      shaRef.current = doc.version?.sha256 ?? "";
      setSaveState("saved");
    } catch (e) {
      setLoadError(errMsg(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadDocument();
  }, [loadDocument]);

  const doSave = useCallback(
    async (options?: { force?: boolean }): Promise<boolean> => {
      if (savingRef.current || conflictRef.current !== null) return false;
      if (!options?.force && draft === savedContent.current) return true;
      savingRef.current = true;
      setSaveState("saving");
      try {
        const result = await putNoteFile({
          path: pathRef.current,
          content: draft,
          previousSha256: shaRef.current,
        });
        if (result.ok) {
          if (result.doc?.version?.sha256) shaRef.current = result.doc.version.sha256;
          savedContent.current = draft;
          setSaveState("saved");
          props.onTreeChanged();
          return true;
        }
        setConflictInfo(result.conflict ?? {});
        setSaveState("conflict");
        return false;
      } catch (e) {
        setSaveState("dirty");
        setErrorDialog(errMsg(e));
        return false;
      } finally {
        savingRef.current = false;
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [draft],
  );

  // Debounced autosave (800ms after typing stops), paused during a conflict.
  useEffect(() => {
    if (loading || conflict !== null) return;
    if (draft === savedContent.current) return;
    setSaveState("dirty");
    if (saveTimer.current !== null) window.clearTimeout(saveTimer.current);
    saveTimer.current = window.setTimeout(() => void doSave(), AUTOSAVE_DEBOUNCE_MS);
    return () => {
      if (saveTimer.current !== null) window.clearTimeout(saveTimer.current);
    };
  }, [draft, loading, conflict, doSave]);

  const handleBack = useCallback(async () => {
    await doSave();
    props.onClose();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [doSave]);

  // ---- conflict resolution ----

  const reloadConflict = async () => {
    try {
      const disk = await apiGet<NoteDocument>(
        `/api/notes/file?path=${encodeURIComponent(pathRef.current)}`,
      );
      setDocName(disk.name);
      setFolderRel(disk.folderRelativePath ?? "");
      setDraft(disk.content ?? "");
      savedContent.current = disk.content ?? "";
      shaRef.current = disk.version?.sha256 ?? "";
      setConflictInfo(null);
      setSaveState("saved");
      props.onTreeChanged();
    } catch (e) {
      setErrorDialog(errMsg(e));
    }
  };

  const overwriteConflict = async () => {
    const diskSha = conflictRef.current?.currentSha256;
    if (diskSha) shaRef.current = diskSha;
    setConflictInfo(null);
    await doSave();
  };

  const copyConflict = async () => {
    const stem = (docName || noteStem(pathRef.current.split("/").pop() ?? "")).slice(0, 100) || "note";
    const siblings = new Set(
      ((tree && findFolder(tree.root, folderRel)?.children) ?? []).map((c) => c.name.toLowerCase()),
    );
    let sequence = 1;
    let copyName = `${stem} (DeskCubby conflict).md`;
    while (siblings.has(copyName.toLowerCase()) && sequence < 99) {
      sequence += 1;
      copyName = `${stem} (DeskCubby conflict ${sequence}).md`;
    }
    try {
      const created = await apiSend<NoteDocument>("/api/notes/file-create", "POST", {
        parent: folderRel,
        name: copyName,
      });
      const put = await putNoteFile({
        path: created.path,
        content: draft,
        previousSha256: created.version?.sha256,
      });
      if (!put.ok) throw new Error(tr("操作失败", "Operation failed"));
      if (put.doc?.version?.sha256) shaRef.current = put.doc.version.sha256;
      savedContent.current = draft;
      pathRef.current = created.path;
      setDocName(created.name);
      setFolderRel(created.folderRelativePath ?? "");
      setConflictInfo(null);
      setSaveState("saved");
      props.onOpenPath(created.path);
      props.onTreeChanged();
      props.showSnack(tr("已另存冲突副本", "Conflict copy saved"));
    } catch (e) {
      setErrorDialog(errMsg(e));
    }
  };

  // ---- media upload ----

  const insertAtCursor = useCallback(
    (text: string) => {
      const ta = taRef.current;
      const pos = ta ? (ta.selectionStart ?? draft.length) : draft.length;
      const end = ta ? (ta.selectionEnd ?? pos) : pos;
      const next = draft.slice(0, pos) + text + draft.slice(end);
      setDraft(next);
      requestAnimationFrame(() => {
        const el = taRef.current;
        if (!el) return;
        el.focus();
        el.setSelectionRange(pos + text.length, pos + text.length);
      });
    },
    [draft],
  );

  const confirmUpload = async () => {
    const file = pendingFileRef.current;
    if (!file || uploading) return;
    setUploading(true);
    try {
      const media = await apiUpload<ImportedNoteMedia>(
        `/api/notes/media-upload?targetFolder=${encodeURIComponent(pickerTarget)}`,
        file,
      );
      const caption = media.fileName.replace(/\.[^.]*$/, "").replace(/\]/g, "_");
      const target = String(media.markdownTarget ?? "").replace(/>/g, "%3E");
      const lineEnding = draft.includes("\r\n") ? "\r\n" : "\n";
      const ta = taRef.current;
      const pos = ta ? (ta.selectionStart ?? draft.length) : draft.length;
      const before = draft.slice(0, pos);
      const prefix = before.length > 0 && !before.endsWith("\n") ? lineEnding : "";
      insertAtCursor(`${prefix}![${caption}](<${target}>)${lineEnding}`);
      pendingFileRef.current = null;
      setPickerOpen(false);
      props.showSnack(tr("媒体已复制到所选笔记库位置", "Media copied to the selected vault location"));
    } catch (e) {
      setErrorDialog(errMsg(e));
    } finally {
      setUploading(false);
    }
  };

  const handleWikiClick = useCallback(
    (name: string) => {
      const found = tree ? resolveWikiInTree(tree.root, name) : null;
      if (found) props.onOpenNote(found);
      else props.showSnack(tr("未找到同名笔记", "No matching note found"));
      // eslint-disable-next-line react-hooks/exhaustive-deps
    },
    [tree],
  );

  const statusText =
    saveState === "conflict"
      ? tr("发现外部修改", "External changes found")
      : saveState === "saving"
        ? tr("正在保存…", "Saving…")
        : saveState === "dirty"
          ? tr("未保存", "Unsaved")
          : tr("已保存", "Saved");

  const folders = useMemo(
    () => (tree ? flattenFolders(tree.root) : []),
    [tree],
  );

  return (
    <section className="dc-col" style={{ gap: 8, minWidth: 0 }}>
      <TopBar
        title={docName || tr("笔记编辑器", "Note editor")}
        subtitle={`${statusText} · ${preview ? tr("阅读预览", "Preview") : tr("Markdown 源码", "Markdown source")}`}
        back={!wide}
        onBack={() => void handleBack()}
        actions={
          <>
            <button
              className="dc-icon-btn"
              aria-label={preview ? tr("源码", "Source") : tr("预览", "Preview")}
              title={preview ? tr("源码", "Source") : tr("预览", "Preview")}
              onClick={() => setPreview((v) => !v)}
            >
              {preview ? <Code2 size={20} /> : <BookOpen size={20} />}
            </button>
            <button
              className="dc-icon-btn"
              aria-label={tr("保存", "Save")}
              title={tr("保存", "Save")}
              disabled={saveState === "saving"}
              onClick={() => void doSave()}
            >
              <Save size={20} />
            </button>
          </>
        }
      />

      {saveState === "saving" && (
        <div
          style={{
            height: 3,
            borderRadius: 2,
            overflow: "hidden",
            background: "var(--dc-surface-variant)",
          }}
        >
          <div
            style={{
              height: "100%",
              width: "40%",
              background: "var(--dc-primary)",
              animation: "dc-indeterminate 1.1s ease-in-out infinite",
            }}
          />
          <style>{`@keyframes dc-indeterminate { 0% { transform: translateX(-100%);} 100% { transform: translateX(280%);} }`}</style>
        </div>
      )}

      {loading ? (
        <Spinner />
      ) : loadError ? (
        <div role="alert" className="dc-col dc-center" style={{ padding: 32 }}>
          <div style={{ color: "var(--dc-error)", fontSize: "0.9em", wordBreak: "break-word" }}>
            {loadError}
          </div>
          <div className="dc-row" style={{ marginTop: 12, gap: 8 }}>
            {!wide && (
              <button className="dc-btn" onClick={() => void handleBack()}>
                {tr("返回", "Back")}
              </button>
            )}
            <button className="dc-btn dc-btn-tonal" onClick={() => void loadDocument()}>
              {tr("重试", "Retry")}
            </button>
          </div>
        </div>
      ) : preview ? (
        <div
          className="dc-card"
          style={{ padding: "14px 16px", minHeight: 320, background: "var(--dc-surface)" }}
        >
          <WikiMarkdownPreview content={draft} onOpenWiki={handleWikiClick} />
        </div>
      ) : (
        <textarea
          ref={taRef}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder={tr("开始写 Markdown…", "Start writing Markdown…")}
          spellCheck={false}
          aria-label={tr("Markdown 源码", "Markdown source")}
          style={{
            width: "100%",
            minHeight: 320,
            flex: 1,
            resize: "none",
            border: "var(--dc-border-width) solid var(--dc-outline-variant)",
            borderRadius: "var(--dc-radius)",
            background: "var(--dc-surface)",
            color: "var(--dc-on-surface)",
            padding: 14,
            lineHeight: 1.7,
            fontFamily: "'JetBrains Mono', 'Cascadia Code', Consolas, monospace",
            fontSize: "0.95em",
            outline: "none",
          }}
        />
      )}

      {/* 上传媒体：每次上传都选择笔记库内的存储位置 */}
      <button
        type="button"
        className="dc-btn dc-btn-tonal"
        style={{ width: "100%", justifyContent: "center", padding: "10px 14px" }}
        disabled={loading || saveState === "saving" || uploading}
        onClick={() => fileRef.current?.click()}
      >
        <ImagePlus size={18} />
        <span className="dc-col" style={{ gap: 0, alignItems: "flex-start" }}>
          <span>{tr("上传媒体", "Upload media")}</span>
          <span style={{ fontSize: "0.78em" }}>
            {tr("每次上传都选择笔记库内的存储位置", "Choose a location inside the vault every time")}
          </span>
        </span>
      </button>
      <input
        ref={fileRef}
        type="file"
        accept="image/*"
        hidden
        onChange={(e) => {
          const f = e.target.files?.[0];
          e.target.value = "";
          if (f) {
            pendingFileRef.current = f;
            setPickerTarget(folderRel);
            setPickerOpen(true);
          }
        }}
      />

      {/* Folder picker for media upload */}
      <Modal
        open={pickerOpen}
        onClose={() => {
          if (!uploading) setPickerOpen(false);
        }}
        title={tr("选择笔记库内的文件夹", "Choose a folder inside the vault")}
        width={440}
      >
        <div className="dc-col" style={{ gap: 2, maxHeight: "46vh", overflowY: "auto" }}>
          {folders.map((f) => {
            const selected = pickerTarget === f.path;
            return (
              <button
                key={f.path || "__root"}
                className="dc-btn"
                style={{
                  justifyContent: "flex-start",
                  paddingLeft: 10 + f.depth * 16,
                  background: selected ? "var(--dc-secondary-container)" : undefined,
                  color: selected ? "var(--dc-on-secondary-container)" : undefined,
                }}
                onClick={() => setPickerTarget(f.path)}
              >
                <Folder size={16} />
                <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  {f.name}
                </span>
              </button>
            );
          })}
        </div>
        <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 14, gap: 8 }}>
          <button className="dc-btn" disabled={uploading} onClick={() => setPickerOpen(false)}>
            {tr("取消", "Cancel")}
          </button>
          <button className="dc-btn dc-btn-filled" disabled={uploading} onClick={() => void confirmUpload()}>
            {uploading ? tr("正在上传图片…", "Uploading image…") : tr("确定", "OK")}
          </button>
        </div>
      </Modal>

      {/* External-modification conflict */}
      <Modal open={conflict !== null} onClose={() => undefined} title={tr("文件已在外部修改", "File changed externally")}>
        <div className="dc-muted" style={{ marginBottom: 12 }}>
          {tr(
            `${docName} 在 Obsidian 或其他应用中发生了变化。自动保存已暂停。`,
            `${docName} changed in Obsidian or another app. Autosave is paused.`,
          )}
        </div>
        <div className="dc-row dc-wrap" style={{ justifyContent: "flex-end", gap: 8 }}>
          <button className="dc-btn" onClick={() => void reloadConflict()}>
            {tr("重新加载", "Reload")}
          </button>
          <button className="dc-btn dc-btn-danger" onClick={() => void overwriteConflict()}>
            {tr("覆盖", "Overwrite")}
          </button>
          <button className="dc-btn dc-btn-filled" onClick={() => void copyConflict()}>
            {tr("另存副本", "Save a copy")}
          </button>
        </div>
      </Modal>

      {/* Operation failed */}
      <Modal open={errorDialog !== null} onClose={() => setErrorDialog(null)} title={tr("操作失败", "Operation failed")}>
        <div className="dc-muted" style={{ marginBottom: 12, wordBreak: "break-word" }}>
          {errorDialog}
        </div>
        <div className="dc-row" style={{ justifyContent: "flex-end" }}>
          <button className="dc-btn dc-btn-filled" onClick={() => setErrorDialog(null)}>
            {tr("知道了", "OK")}
          </button>
        </div>
      </Modal>
    </section>
  );
});

// ---------------------------------------------------------------------------
// Preview with clickable `![[Wiki]]` links (H1–H6 sizes shared with diary settings)
// ---------------------------------------------------------------------------

function WikiMarkdownPreview(props: { content: string; onOpenWiki: (name: string) => void }) {
  const settings = useSettings((s) => s.settings);
  const sizes = settings?.markdownHeadingSizesSp ?? [32, 28, 24, 21, 19, 17];
  const base = settings?.fontScale ?? 1;
  const h = (level: number): React.CSSProperties => ({
    fontSize: `${((sizes[level - 1] ?? 17) * base) / 16}em`,
  });
  const processed = useMemo(() => convertWikiLinks(props.content), [props.content]);
  return (
    <div className="dc-markdown">
      <ReactMarkdown
        urlTransform={(url) => (url.startsWith("wiki:") ? url : defaultUrlTransform(url))}
        components={{
          h1: ({ children }) => <h1 style={h(1)}>{children}</h1>,
          h2: ({ children }) => <h2 style={h(2)}>{children}</h2>,
          h3: ({ children }) => <h3 style={h(3)}>{children}</h3>,
          h4: ({ children }) => <h4 style={h(4)}>{children}</h4>,
          h5: ({ children }) => <h5 style={h(5)}>{children}</h5>,
          h6: ({ children }) => <h6 style={h(6)}>{children}</h6>,
          a: ({ href, children }) => {
            if (href && href.startsWith("wiki:")) {
              let name = href.slice(5);
              try {
                name = decodeURIComponent(name);
              } catch {
                /* keep raw */
              }
              return (
                <button
                  type="button"
                  onClick={() => props.onOpenWiki(name)}
                  style={{
                    background: "none",
                    border: "none",
                    padding: 0,
                    font: "inherit",
                    color: "var(--dc-primary)",
                    textDecoration: "underline",
                    cursor: "pointer",
                  }}
                >
                  {children}
                </button>
              );
            }
            return (
              <a href={href} target="_blank" rel="noreferrer">
                {children}
              </a>
            );
          },
        }}
      >
        {processed}
      </ReactMarkdown>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Name dialog (新建文件夹 / 新建笔记 / 重命名)
// ---------------------------------------------------------------------------

function NameDialog(props: {
  open: boolean;
  title: string;
  initial: string;
  onDismiss: () => void;
  onConfirm: (value: string) => void;
}) {
  const [value, setValue] = useState(props.initial);
  useEffect(() => {
    if (props.open) setValue(props.initial);
  }, [props.open, props.initial]);
  if (!props.open) return null;
  return (
    <div className="dc-dialog-overlay" onClick={props.onDismiss}>
      <div
        className="dc-dialog"
        role="dialog"
        aria-modal="true"
        style={{ width: "min(420px, 94vw)" }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="dc-title" style={{ marginBottom: 12 }}>
          {props.title}
        </div>
        <label className="dc-col" style={{ gap: 4 }}>
          <span className="dc-muted" style={{ fontSize: "0.85em" }}>
            {tr("名称", "Name")}
          </span>
          <input
            className="dc-input"
            value={value}
            maxLength={220}
            autoFocus
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && value.trim()) props.onConfirm(value);
            }}
          />
        </label>
        <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 16 }}>
          <button className="dc-btn" onClick={props.onDismiss}>
            {tr("取消", "Cancel")}
          </button>
          <button
            className="dc-btn dc-btn-filled"
            disabled={!value.trim()}
            onClick={() => props.onConfirm(value)}
          >
            {tr("确定", "OK")}
          </button>
        </div>
      </div>
    </div>
  );
}
