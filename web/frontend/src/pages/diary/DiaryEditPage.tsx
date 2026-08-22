/**
 * Diary editor (/diary/edit?name=yyyy-MM-dd.md) — mirrors Android 日记编辑器:
 * Markdown source editing with ~1.2s autosave, reading preview, SHA-256 conflict
 * handling (reload / overwrite / save a copy), media upload, formatting helpers,
 * word count, and move-to-trash. Without ?name= it creates today's diary first.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  ArrowLeft, Bold, BookOpen, Code2, FileText, Heading1, ImagePlus, Italic,
  List, MoreVertical, NotebookPen, Quote, Save, Trash2,
} from "lucide-react";
import { ApiClientError, apiGet, apiSend, apiUpload } from "../../api/client";
import { MEAL_CATEGORIES } from "../../api/types";
import { tr } from "../../i18n/tr";
import {
  ConfirmDialog, ErrorText, Modal, PageTutorialOverlay, PopupMenu,
  Snackbar, Spinner, TopBar, useDirtyGuard, useSnackbar,
} from "../../components/ui";
import { MarkdownPreview } from "../../components/MarkdownPreview";
import { useLongPress } from "../thought/ThoughtPage";

interface DiaryEditorDocument {
  uri: string;
  name: string;
  content: string;
  lastModified: number;
  size: number;
  sha256: string;
}

interface ConflictInfo {
  currentSha256?: string;
  content?: string;
  lastModified?: number;
}

interface ImportedMedia {
  documentUri?: string;
  fileName: string;
  markdown: string;
}

type SaveState = "saved" | "dirty" | "saving" | "conflict";

function nowLocalTime(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function stampForCopy(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

export default function DiaryEditPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [snack, showSnack] = useSnackbar();

  const nameParam = searchParams.get("name") ?? "";
  const [docName, setDocName] = useState(nameParam);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<unknown>(null);

  const [draft, setDraft] = useState("");
  const savedContent = useRef("");
  const shaRef = useRef("");
  const [saveState, setSaveState] = useState<SaveState>("saved");
  const [preview, setPreview] = useState(false);
  const [conflict, setConflict] = useState<ConflictInfo | null>(null);
  const [menuOpen, setMenuOpen] = useState<{ x: number; y: number } | null>(null);
  const [confirmLeave, setConfirmLeave] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [uploading, setUploading] = useState(false);
  // Long-press 上传媒体 → meal-category menu (README §3): only photos uploaded
  // with a category enter 吃历; plain tap inserts without any category.
  const [mealMenu, setMealMenu] = useState<{ x: number; y: number } | null>(null);
  const [errorDialog, setErrorDialog] = useState<string | null>(null);

  const taRef = useRef<HTMLTextAreaElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const imgBtnRef = useRef<HTMLButtonElement>(null);
  const pendingMealCategoryRef = useRef<string | null>(null);
  const saveTimer = useRef<number | null>(null);
  const savingRef = useRef(false);
  const conflictRef = useRef<ConflictInfo | null>(null);
  const loadedNameRef = useRef("");
  const dirty = draft !== savedContent.current;

  const setConflictInfo = (info: ConflictInfo | null) => {
    conflictRef.current = info;
    setConflict(info);
  };

  useDirtyGuard(dirty && !conflict);

  /** PUT with raw fetch so a 409 conflict body can be read for the dialog. */
  const putDocument = useCallback(async (payload: {
    name: string; content: string; previousSha256?: string;
  }): Promise<{ ok: true; doc?: Partial<DiaryEditorDocument> } | { ok: false; status: number; conflict?: ConflictInfo }> => {
    const resp = await fetch("/api/diary/document", {
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
      return { ok: true, doc: text ? JSON.parse(text) : undefined };
    }
    if (resp.status === 409) {
      let body: ConflictInfo | undefined;
      try {
        const data = await resp.json();
        body = {
          currentSha256: data?.currentSha256 ?? data?.error?.currentSha256,
          content: data?.content ?? data?.error?.content,
          lastModified: data?.lastModified ?? data?.error?.lastModified,
        };
      } catch {
        /* no parseable body */
      }
      return { ok: false, status: 409, conflict: body };
    }
    let message = `Request failed (${resp.status})`;
    try {
      const data = await resp.json();
      if (data?.error?.message) message = data.error.message;
    } catch {
      /* keep default */
    }
    throw new ApiClientError(resp.status, "http_" + resp.status, message);
  }, []);

  const doSave = useCallback(async (options?: { force?: boolean }): Promise<boolean> => {
    if (savingRef.current || conflictRef.current !== null) return false;
    if (!docName) return false;
    if (!options?.force && draft === savedContent.current) return true;
    savingRef.current = true;
    setSaveState("saving");
    try {
      const result = await putDocument({ name: docName, content: draft, previousSha256: shaRef.current });
      if (result.ok) {
        if (typeof result.doc?.sha256 === "string" && result.doc.sha256) {
          shaRef.current = result.doc.sha256;
        } else {
          // No sha in response — quietly re-read the authoritative version.
          const fresh = await apiGet<DiaryEditorDocument>(`/api/diary/document?name=${encodeURIComponent(docName)}`);
          shaRef.current = fresh.sha256;
        }
        savedContent.current = draft;
        setSaveState("saved");
        return true;
      }
      setConflictInfo(result.conflict ?? {});
      setSaveState("conflict");
      return false;
    } catch (e) {
      setSaveState("dirty");
      setErrorDialog(e instanceof Error ? e.message : String(e));
      return false;
    } finally {
      savingRef.current = false;
    }
  }, [docName, draft, putDocument]);

  const loadDocument = useCallback(async (name: string) => {
    setLoading(true);
    setLoadError(null);
    try {
      const doc = await apiGet<DiaryEditorDocument>(`/api/diary/document?name=${encodeURIComponent(name)}`);
      setDocName(doc.name || name);
      setDraft(doc.content ?? "");
      savedContent.current = doc.content ?? "";
      shaRef.current = doc.sha256 ?? "";
      setSaveState("saved");
    } catch (e) {
      setLoadError(e);
    } finally {
      setLoading(false);
    }
  }, []);

  // Initial load: create today's diary when no ?name= is given.
  useEffect(() => {
    if (!nameParam) {
      loadedNameRef.current = "";
      return;
    }
    if (loadedNameRef.current === nameParam) return;
    loadedNameRef.current = nameParam;
    void loadDocument(nameParam);
  }, [nameParam, loadDocument]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        let name: string | undefined;
        try {
          const d = new Date();
          const p = (n: number) => String(n).padStart(2, "0");
          const today = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
          const created = await apiSend<Partial<{ name: string }>>("/api/diary/documents", "POST", { dateIso: today });
          name = created?.name;
        } catch {
          /* maybe exists already */
        }
        if (!name) {
          const list = await apiGet<Array<{ name: string; dateIso: string }>>("/api/diary/documents");
          const d = new Date();
          const p = (n: number) => String(n).padStart(2, "0");
          const today = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
          name = (Array.isArray(list) ? list : []).find((x) => x.dateIso === today)?.name;
        }
        if (cancelled) return;
        if (!name) {
          setLoadError(new Error(tr("创建今日日记失败", "Could not create today's diary")));
          setLoading(false);
          return;
        }
        setDocName(name);
        setSearchParams({ name }, { replace: true });
        await loadDocument(name);
      } catch (e) {
        if (!cancelled) {
          setLoadError(e);
          setLoading(false);
        }
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Debounced autosave (~1.2s after typing stops), paused while conflicting.
  useEffect(() => {
    if (loading || conflict !== null) return;
    if (draft === savedContent.current) return;
    setSaveState("dirty");
    if (saveTimer.current !== null) window.clearTimeout(saveTimer.current);
    saveTimer.current = window.setTimeout(() => void doSave(), 1200);
    return () => {
      if (saveTimer.current !== null) window.clearTimeout(saveTimer.current);
    };
  }, [draft, loading, conflict, doSave]);

  // Auto-growing textarea.
  useEffect(() => {
    const ta = taRef.current;
    if (ta || preview) return;
    requestAnimationFrame(() => {
      const el = taRef.current;
      if (!el) return;
      el.style.height = "auto";
      el.style.height = `${Math.max(el.scrollHeight, 320)}px`;
    });
  }, [draft, preview]);

  const goBack = useCallback(async () => {
    navigate("/diary");
  }, [navigate]);

  const onBack = () => {
    if (conflict !== null) return;
    if (dirty) {
      setConfirmLeave(true);
    } else {
      void goBack();
    }
  };

  const wordCount = useMemo(() => draft.replace(/\s+/g, "").length, [draft]);
  const dateTitle = useMemo(() => {
    const m = /^(\d{4}-\d{2}-\d{2})/.exec(docName);
    return docName ? (m ? `${m[1]} · ${docName.replace(/\.md$/i, "")}` : docName.replace(/\.md$/i, "")) : tr("日记编辑器", "Diary editor");
  }, [docName]);

  const statusText =
    saveState === "conflict" ? tr("发现外部修改", "External changes found")
      : saveState === "saving" ? tr("正在保存…", "Saving…")
        : saveState === "dirty" ? tr("未保存", "Unsaved")
          : tr("已保存", "Saved");

  // ---- editing helpers ----
  const replaceRange = (start: number, end: number, text: string, cursorStart: number, cursorEnd: number) => {
    setDraft((prev) => prev.slice(0, start) + text + prev.slice(end));
    requestAnimationFrame(() => {
      const ta = taRef.current;
      if (!ta) return;
      ta.focus();
      ta.setSelectionRange(cursorStart, cursorEnd);
    });
  };

  const wrapSelection = (before: string, after = before) => {
    const ta = taRef.current;
    if (!ta) return;
    const s = ta.selectionStart ?? draft.length;
    const e = ta.selectionEnd ?? draft.length;
    const sel = draft.slice(s, e);
    replaceRange(s, e, before + sel + after, s + before.length, e + before.length);
  };

  const prefixLine = (prefix: string) => {
    const ta = taRef.current;
    if (!ta) return;
    const s = ta.selectionStart ?? draft.length;
    const lineStart = draft.lastIndexOf("\n", Math.max(0, s - 1)) + 1;
    replaceRange(lineStart, lineStart, prefix, s + prefix.length, s + prefix.length);
  };

  const insertAtCursor = (text: string) => {
    const ta = taRef.current;
    const pos = ta ? (ta.selectionStart ?? draft.length) : draft.length;
    replaceRange(pos, pos, text, pos + text.length, pos + text.length);
  };

  const onPickImage = async (file: File) => {
    if (uploading) return;
    const category = pendingMealCategoryRef.current;
    pendingMealCategoryRef.current = null;
    setUploading(true);
    try {
      const media = await apiUpload<ImportedMedia>(
        category ? `/api/media/upload?category=${encodeURIComponent(category)}` : "/api/media/upload",
        file,
      );
      const md = media?.markdown || `![](/api/media/file?path=${encodeURIComponent(media?.fileName ?? "")})`;
      insertAtCursor(`\n${md}\n`);
      showSnack(tr("图片已插入", "Image inserted"));
    } catch (e) {
      setErrorDialog(e instanceof Error ? e.message : String(e));
    } finally {
      setUploading(false);
    }
  };

  // 长按上传媒体按钮（约 0.5s 或右键）弹出餐别菜单；单击仍是普通插图。
  const imgLongPress = useLongPress(!uploading, () => {
    const rect = imgBtnRef.current?.getBoundingClientRect();
    setMealMenu(rect
      ? { x: rect.left, y: rect.bottom + 4 }
      : { x: window.innerWidth / 2, y: window.innerHeight / 3 });
  });

  const resolveConflictReload = async () => {
    if (!docName) return;
    try {
      const disk = await apiGet<DiaryEditorDocument>(`/api/diary/document?name=${encodeURIComponent(docName)}`);
      setDraft(disk.content ?? "");
      savedContent.current = disk.content ?? "";
      shaRef.current = disk.sha256 ?? "";
      setConflictInfo(null);
      setSaveState("saved");
    } catch (e) {
      setErrorDialog(e instanceof Error ? e.message : String(e));
    }
  };

  const resolveConflictOverwrite = async () => {
    shaRef.current = conflictRef.current?.currentSha256 ?? "";
    setConflictInfo(null);
    await doSave({ force: true });
  };

  const resolveConflictCopy = async () => {
    if (!docName) return;
    const stem = docName.replace(/\.(md|markdown)$/i, "");
    const copyName = `${stem} (${tr("冲突副本", "conflict copy")} ${stampForCopy()}).md`;
    try {
      await apiSend("/api/diary/documents", "POST", { name: copyName });
      await putDocument({ name: copyName, content: draft });
      savedContent.current = draft;
      setDocName(copyName);
      setSearchParams({ name: copyName }, { replace: true });
      const fresh = await apiGet<DiaryEditorDocument>(`/api/diary/document?name=${encodeURIComponent(copyName)}`);
      shaRef.current = fresh.sha256 ?? "";
      setConflictInfo(null);
      setSaveState("saved");
      showSnack(tr(`已另存副本 ${copyName}`, `Saved a copy as ${copyName}`));
    } catch (e) {
      setErrorDialog(e instanceof Error ? e.message : String(e));
    }
  };

  const performDelete = async () => {
    if (!docName || deleting) return;
    setDeleting(true);
    try {
      await apiSend(`/api/diary/document?name=${encodeURIComponent(docName)}`, "DELETE");
      showSnack(tr("已移入日记回收站", "Moved to the diary trash"));
      navigate("/diary");
    } catch (e) {
      setErrorDialog(e instanceof Error ? e.message : String(e));
    } finally {
      setDeleting(false);
    }
  };

  if (loading && !docName && !loadError) {
    return (
      <div>
        <TopBar title={tr("日记编辑器", "Diary editor")} back onBack={() => void goBack()} />
        <Spinner />
      </div>
    );
  }

  if (loadError) {
    return (
      <div>
        <TopBar title={tr("日记编辑器", "Diary editor")} back onBack={() => void goBack()} />
        <EmptyLoadError error={loadError} onBack={() => void goBack()} />
      </div>
    );
  }

  return (
    <div className="diary-edit-page">
      <TopBar
        title={dateTitle}
        subtitle={`${statusText} · ${preview ? tr("阅读预览", "Preview") : tr("Markdown 源码", "Markdown source")}`}
        back
        onBack={onBack}
        actions={<>
          <button
            className="dc-icon-btn"
            aria-label={preview ? tr("源码", "Source") : tr("预览", "Preview")}
            onClick={() => setPreview((v) => !v)}
          >
            {preview ? <Code2 size={20} /> : <BookOpen size={20} />}
          </button>
          <button
            className="dc-icon-btn"
            aria-label={tr("保存", "Save")}
            disabled={saveState === "saving" || !dirty}
            onClick={() => void doSave({ force: true })}
          >
            <Save size={20} />
          </button>
          <button
            className="dc-icon-btn"
            aria-label={tr("更多", "More")}
            onClick={(e) => setMenuOpen({ x: e.clientX, y: e.clientY })}
          >
            <MoreVertical size={20} />
          </button>
        </>}
      />

      {saveState === "saving" && (
        <div style={{ height: 3, borderRadius: 2, overflow: "hidden", background: "var(--dc-surface-variant)", marginBottom: 8 }}>
          <div style={{ height: "100%", width: "40%", background: "var(--dc-primary)", animation: "dc-indeterminate 1.1s ease-in-out infinite" }} />
          <style>{`@keyframes dc-indeterminate { 0% { transform: translateX(-100%);} 100% { transform: translateX(280%);} }`}</style>
        </div>
      )}

      {preview ? (
        <div
          className="dc-card"
          style={{ padding: "14px 16px", minHeight: 320, background: "var(--dc-surface)" }}
        >
          <MarkdownPreview content={draft} />
        </div>
      ) : (
        <textarea
          ref={taRef}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder={tr("开始记录…", "Start writing…")}
          spellCheck={false}
          aria-label={tr("日记正文", "Diary body")}
          style={{
            width: "100%",
            minHeight: 320,
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

      <div className="dc-row dc-wrap" style={{ marginTop: 10, gap: 4 }}>
        <button
          ref={imgBtnRef}
          className="dc-icon-btn"
          aria-label={tr("上传媒体", "Upload media")}
          title={tr(
            "上传媒体：单击普通插图；长按可选餐别（进入吃历）",
            "Upload media: tap to insert; long-press to pick a meal category (enters 吃历)",
          )}
          disabled={uploading}
          onClick={() => {
            if (imgLongPress.suppressClick()) return;
            pendingMealCategoryRef.current = null;
            fileRef.current?.click();
          }}
          {...imgLongPress.props}
        >
          <ImagePlus size={19} />
        </button>
        <input
          ref={fileRef}
          type="file"
          accept="image/*"
          hidden
          onChange={(e) => {
            const f = e.target.files?.[0];
            e.target.value = "";
            if (f) void onPickImage(f);
          }}
        />
        <button className="dc-icon-btn" aria-label={tr("标题", "Heading")} onClick={() => prefixLine("## ")}>
          <Heading1 size={19} />
        </button>
        <button className="dc-icon-btn" aria-label={tr("粗体", "Bold")} onClick={() => wrapSelection("**")}>
          <Bold size={19} />
        </button>
        <button className="dc-icon-btn" aria-label={tr("斜体", "Italic")} onClick={() => wrapSelection("*")}>
          <Italic size={19} />
        </button>
        <button className="dc-icon-btn" aria-label={tr("列表", "List")} onClick={() => prefixLine("- ")}>
          <List size={19} />
        </button>
        <button className="dc-icon-btn" aria-label={tr("引用", "Quote")} onClick={() => prefixLine("> ")}>
          <Quote size={19} />
        </button>
        <button className="dc-icon-btn" aria-label={tr("代码", "Code")} onClick={() => wrapSelection("`")}>
          <Code2 size={19} />
        </button>
        <span className="dc-grow" />
        <button className="dc-btn" onClick={() => navigate("/daily")}>
          <NotebookPen size={16} /> {tr("日常记录", "Daily records")}
        </button>
        <span className="dc-muted" style={{ fontSize: "0.82em", whiteSpace: "nowrap" }}>
          {tr(`${wordCount} 字`, `${wordCount} words`)}
        </span>
      </div>
      {uploading && <div className="dc-muted" style={{ fontSize: "0.85em", marginTop: 6 }}>{tr("正在上传图片…", "Uploading image…")}</div>}
      <ErrorText error={null} />

      <PopupMenu
        open={menuOpen !== null}
        onClose={() => setMenuOpen(null)}
        x={menuOpen?.x ?? 0}
        y={menuOpen?.y ?? 0}
        items={[
          { label: <span className="dc-row" style={{ gap: 8 }}><Save size={16} /> {tr("保存", "Save")}</span>, onClick: () => void doSave({ force: true }) },
          {
            label: <span className="dc-row" style={{ gap: 8, color: "var(--dc-error)" }}><Trash2 size={16} /> {tr("删除日记", "Delete diary")}</span>,
            danger: true,
            onClick: () => setConfirmDelete(true),
          },
        ]}
      />

      {/* Long-press 上传媒体 → 餐别菜单：选餐别后上传的图片进入吃历 */}
      <PopupMenu
        open={mealMenu !== null}
        onClose={() => setMealMenu(null)}
        x={mealMenu?.x ?? 0}
        y={mealMenu?.y ?? 0}
        items={MEAL_CATEGORIES.map((c) => ({
          label: `${c.icon} ${tr(c.zh, c.en)}`,
          onClick: () => {
            pendingMealCategoryRef.current = c.key;
            fileRef.current?.click();
          },
        }))}
      />

      {/* Unsaved-changes leave confirmation */}
      <ConfirmDialog
        open={confirmLeave}
        title={tr("有未保存的修改", "Unsaved changes")}
        message={tr("离开前要先保存吗？", "Save before leaving?")}
        onCancel={() => setConfirmLeave(false)}
        onConfirm={() => { setConfirmLeave(false); void goBack(); }}
        confirmLabel={tr("不保存并返回", "Discard and go back")}
      >
        <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: -6, gap: 8 }}>
          <button
            className="dc-btn dc-btn-filled"
            onClick={async () => {
              setConfirmLeave(false);
              const ok = await doSave({ force: true });
              if (ok) void goBack();
            }}
          >
            {tr("保存并返回", "Save and go back")}
          </button>
        </div>
      </ConfirmDialog>

      {/* Move to trash */}
      <ConfirmDialog
        open={confirmDelete}
        danger
        title={tr(`删除 ${docName.replace(/\.md$/i, "")}？`, `Delete ${docName.replace(/\.md$/i, "")}?`)}
        message={tr("文件将安全复制到日记目录内的回收站，校验成功后才删除原文件。", "The file is copied and verified in the diary trash before the original is removed.")}
        confirmLabel={deleting ? "…" : tr("移入回收站", "Move to trash")}
        onConfirm={() => void performDelete()}
        onCancel={() => setConfirmDelete(false)}
      />

      {/* External-modification conflict */}
      <Modal open={conflict !== null} onClose={() => undefined} title={tr("文件已在外部修改", "File changed externally")}>
        <div className="dc-muted" style={{ marginBottom: 12 }}>
          {tr(
            `${docName} 的磁盘内容与打开时不同。自动保存已暂停，避免覆盖其他工具的修改。`,
            `${docName} changed on disk. Autosave is paused to avoid overwriting external changes.`,
          )}
        </div>
        <div className="dc-row dc-wrap" style={{ justifyContent: "flex-end", gap: 8 }}>
          <button className="dc-btn" onClick={() => void resolveConflictReload()}>
            <ArrowLeft size={16} /> {tr("重新加载", "Reload")}
          </button>
          <button className="dc-btn dc-btn-danger" onClick={() => void resolveConflictOverwrite()}>
            {tr("覆盖", "Overwrite")}
          </button>
          <button className="dc-btn dc-btn-filled" onClick={() => void resolveConflictCopy()}>
            <FileText size={16} /> {tr("另存副本", "Save a copy")}
          </button>
        </div>
      </Modal>

      {/* Operation failed */}
      <Modal open={errorDialog !== null} onClose={() => setErrorDialog(null)} title={tr("操作失败", "Operation failed")}>
        <div className="dc-muted" style={{ marginBottom: 12, wordBreak: "break-word" }}>{errorDialog}</div>
        <div className="dc-row" style={{ justifyContent: "flex-end" }}>
          <button className="dc-btn dc-btn-filled" onClick={() => setErrorDialog(null)}>{tr("知道了", "OK")}</button>
        </div>
      </Modal>

      <Snackbar message={snack} />
      <PageTutorialOverlay
        pageKey="diary-edit"
        title={tr("日记编辑器", "Diary editor")}
        lines={[
          tr("停止输入约 1.2 秒自动保存；顶栏可切换 Markdown 源码与阅读预览。", "Autosaves about 1.2s after typing stops; switch between Markdown source and preview in the top bar."),
          tr("底部工具栏可插入图片和常用 Markdown 语法。", "The bottom toolbar inserts images and common Markdown syntax."),
          tr("文件被外部修改时会弹出冲突对话框：重新加载、覆盖或另存副本。", "If the file changed externally pick reload, overwrite, or save a copy."),
        ]}
      />
    </div>
  );
}

function EmptyLoadError(props: { error: unknown; onBack: () => void }) {
  return (
    <div className="dc-col dc-center" style={{ padding: 48, textAlign: "center" }}>
      <FileText size={44} className="dc-muted" />
      <div style={{ marginTop: 12 }}>{tr("无法读取日记", "Could not load diaries")}</div>
      <div className="dc-muted" style={{ fontSize: "0.85em", marginTop: 4, maxWidth: 480, wordBreak: "break-word" }}>
        {props.error instanceof Error ? props.error.message : String(props.error)}
      </div>
      <button className="dc-btn dc-btn-tonal" style={{ marginTop: 14 }} onClick={props.onBack}>
        {tr("返回", "Back")}
      </button>
    </div>
  );
}
