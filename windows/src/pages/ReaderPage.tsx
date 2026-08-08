import {
  ArrowLeft,
  BookOpen,
  ChevronLeft,
  ChevronRight,
  Clipboard,
  FilePlus2,
  FileText,
  LibraryBig,
  List,
  RotateCcw,
  Save,
  Search,
  Settings2,
  Trash2,
  X,
} from "lucide-react";
import {
  type CSSProperties,
  type ReactNode,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";

import {
  ConfirmDialog,
  EmptyState,
  ErrorState,
  LoadingState,
  PageFrame,
  UnsavedChangesGuard,
} from "../components";
import { DeskCubbyIpcError, readableError, tr } from "../lib/ipc";
import {
  readerApi,
  type ReaderBookV1,
  type ReaderDocumentV1,
  type ReaderLibraryV1,
  type ReaderPreferencesV1,
  type ReaderTextDocumentV1,
} from "../lib/readerApi";
import { useAppStore } from "../store/appStore";
import "../styles/reader.css";

const DEFAULT_READER_PREFERENCES: ReaderPreferencesV1 = {
  background: "paper",
  customBackgroundArgb: -724762,
  fontSizePx: 19,
  lineHeightMultiplier: 1.6,
  paragraphSpacingPx: 10,
  pdfZoomPercent: 100,
  chapterDetectionMode: "smartAndCustom",
  customChapterRegex: "",
  chapterHeadingMaxChars: 160,
};

const MAX_READER_SEARCH_RESULTS = 5_000;
const MAX_READER_SEARCH_QUERY_CHARS = 128;
const MAX_PDF_PAGE_INDEX = 19_999;

interface ReaderTextMatch {
  pageIndex: number;
  startIndex: number;
  endIndex: number;
}

function findReaderTextMatches(
  document: ReaderTextDocumentV1,
  rawQuery: string,
): ReaderTextMatch[] {
  const query = [...rawQuery.trim()].slice(0, MAX_READER_SEARCH_QUERY_CHARS).join("");
  if (!query) return [];
  const foldedQuery = query.toLocaleLowerCase();
  const matches: ReaderTextMatch[] = [];
  for (let pageIndex = 0; pageIndex < document.pages.length; pageIndex += 1) {
    const text = document.pages[pageIndex].text;
    const foldedText = text.toLocaleLowerCase();
    let fromIndex = 0;
    while (matches.length < MAX_READER_SEARCH_RESULTS) {
      const startIndex = foldedText.indexOf(foldedQuery, fromIndex);
      if (startIndex < 0) break;
      matches.push({
        pageIndex,
        startIndex,
        endIndex: startIndex + query.length,
      });
      fromIndex = startIndex + Math.max(query.length, 1);
    }
    if (matches.length >= MAX_READER_SEARCH_RESULTS) break;
  }
  return matches;
}

function safeReaderPdfUrl(assetUrl: string, bookId: string): string | null {
  try {
    const parsed = new URL(assetUrl);
    const expectedPath = `/${bookId}.pdf`;
    if (
      parsed.protocol !== "http:" ||
      parsed.hostname !== "reader.localhost" ||
      parsed.port ||
      parsed.username ||
      parsed.password ||
      parsed.pathname !== expectedPath ||
      parsed.search
    ) {
      return null;
    }
    return parsed.href;
  } catch {
    return null;
  }
}

function argbToCss(argb: number): string {
  const rgb = (argb >>> 0) & 0x00ff_ffff;
  return `#${rgb.toString(16).padStart(6, "0")}`;
}

function cssToArgb(value: string): number {
  const rgb = Number.parseInt(value.replace(/^#/, ""), 16);
  return (0xff00_0000 | (Number.isFinite(rgb) ? rgb : 0xf4f0e6)) | 0;
}

function decimalMillis(value: string): bigint {
  return /^\d+$/.test(value) ? BigInt(value) : 0n;
}

function formatReadingTime(value: string, language: "zh-CN" | "en"): string {
  const minutes = decimalMillis(value) / 60_000n;
  const hours = minutes / 60n;
  const remaining = minutes % 60n;
  if (language === "en") {
    return hours > 0n ? `${hours}h ${remaining}m` : `${minutes}m`;
  }
  return hours > 0n ? `${hours} 小时 ${remaining} 分钟` : `${minutes} 分钟`;
}

function replaceBook(
  library: ReaderLibraryV1 | null,
  book: ReaderBookV1,
): ReaderLibraryV1 | null {
  if (!library) return library;
  const books = library.books.some((candidate) => candidate.id === book.id)
    ? library.books.map((candidate) => (candidate.id === book.id ? book : candidate))
    : [book, ...library.books];
  return {
    ...library,
    books: [...books].sort(
      (left, right) =>
        right.lastOpenedAt - left.lastOpenedAt || left.title.localeCompare(right.title),
    ),
    totalReadingMillis: books
      .reduce((total, candidate) => total + decimalMillis(candidate.readingMillis), 0n)
      .toString(),
  };
}

function readerErrorMessage(
  reason: unknown,
  language: "zh-CN" | "en",
): string {
  const code = reason instanceof DeskCubbyIpcError ? reason.code : "";
  const messages: Record<string, [string, string]> = {
    reader_file_type_unsupported: ["请选择 TXT 或 PDF 文件。", "Choose a TXT or PDF file."],
    reader_file_too_large: ["文件超过阅读器的安全大小上限。", "The file exceeds the reader safety limit."],
    reader_library_limit_exceeded: ["书库已达到 500 本上限。", "The library has reached its 500-book limit."],
    reader_file_missing: ["原文件已移动或删除，请重新导入。", "The original file was moved or deleted. Import it again."],
    reader_file_changed: ["读取时文件发生变化，请重试。", "The file changed while it was being read. Try again."],
    reader_state_corrupt: [
      "阅读状态文件已损坏；DeskCubby 已保留原文件且停止覆盖，请先备份应用数据。",
      "The reader state is damaged. DeskCubby preserved it and stopped writing; back up app data before recovery.",
    ],
    reader_state_unsupported: [
      "阅读状态来自更新版本，请升级 DeskCubby。",
      "The reader state was created by a newer DeskCubby version. Update DeskCubby.",
    ],
  };
  const message = messages[code];
  return message ? tr(language, message[0], message[1]) : readableError(reason, language);
}

function ReaderBookCard({
  book,
  language,
  busy,
  onOpen,
  onRemove,
}: {
  book: ReaderBookV1;
  language: "zh-CN" | "en";
  busy: boolean;
  onOpen: () => void;
  onRemove: () => void;
}) {
  const locale = language === "en" ? "en-US" : "zh-CN";
  return (
    <article className="card reader-book-card">
      <button
        className="reader-book-open"
        type="button"
        disabled={busy}
        onClick={onOpen}
        aria-label={tr(language, `打开《${book.title}》`, `Open ${book.title}`)}
      >
        <span className="reader-book-icon" aria-hidden="true">
          {book.bookType === "pdf" ? <FileText /> : <BookOpen />}
        </span>
        <span className="reader-book-copy">
          <strong>{book.title}</strong>
          <span>
            {book.bookType.toUpperCase()} · {formatReadingTime(book.readingMillis, language)}
          </span>
          <small>
            {tr(language, "最近打开", "Last opened")} {" "}
            {new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(
              new Date(book.lastOpenedAt),
            )}
          </small>
        </span>
      </button>
      <button
        className="icon-button"
        type="button"
        disabled={busy}
        onClick={onRemove}
        aria-label={tr(language, `从书库移除《${book.title}》`, `Remove ${book.title} from library`)}
      >
        <Trash2 aria-hidden="true" size={17} />
      </button>
    </article>
  );
}

function HighlightedPage({
  document,
  pageIndex,
  matches,
  currentMatchIndex,
  paragraphSpacing,
}: {
  document: ReaderTextDocumentV1;
  pageIndex: number;
  matches: ReaderTextMatch[];
  currentMatchIndex: number;
  paragraphSpacing: number;
}) {
  const text = document.pages[pageIndex]?.text ?? "";
  const pageMatches = matches
    .map((match, index) => ({ ...match, resultIndex: index }))
    .filter((match) => match.pageIndex === pageIndex);
  const paragraphs: Array<{ text: string; offset: number }> = [];
  let offset = 0;
  for (const paragraph of text.split("\n\n")) {
    paragraphs.push({ text: paragraph, offset });
    offset += paragraph.length + 2;
  }

  function highlightedParagraph(paragraph: { text: string; offset: number }): ReactNode[] {
    const start = paragraph.offset;
    const end = start + paragraph.text.length;
    const contained = pageMatches.filter(
      (match) => match.startIndex >= start && match.endIndex <= end,
    );
    if (!contained.length) return [paragraph.text];
    const content: ReactNode[] = [];
    let cursor = start;
    for (const match of contained) {
      if (match.startIndex > cursor) {
        content.push(text.slice(cursor, match.startIndex));
      }
      content.push(
        <mark
          className={match.resultIndex === currentMatchIndex ? "is-current" : undefined}
          key={`${match.startIndex}-${match.endIndex}`}
        >
          {text.slice(match.startIndex, match.endIndex)}
        </mark>,
      );
      cursor = match.endIndex;
    }
    if (cursor < end) content.push(text.slice(cursor, end));
    return content;
  }

  return (
    <article className="reader-text-page" aria-label={`${pageIndex + 1}`}>
      {paragraphs.map((paragraph, index) => (
        <p
          key={`${paragraph.offset}-${index}`}
          style={{ marginBlockEnd: `${paragraphSpacing}px` }}
        >
          {highlightedParagraph(paragraph)}
        </p>
      ))}
    </article>
  );
}

function ReaderSettings({
  language,
  draft,
  busy,
  error,
  onChange,
  onReset,
  onSave,
  onClose,
}: {
  language: "zh-CN" | "en";
  draft: ReaderPreferencesV1;
  busy: boolean;
  error: string;
  onChange: (next: ReaderPreferencesV1) => void;
  onReset: () => void;
  onSave: () => void;
  onClose: () => void;
}) {
  const copy = (zh: string, en: string) => tr(language, zh, en);
  return (
    <section className="panel reader-settings" aria-labelledby="reader-settings-title">
      <div className="panel-header reader-settings-header">
        <div>
          <h2 id="reader-settings-title">{copy("阅读设置", "Reader settings")}</h2>
          <p>{copy("阅读设置仅保存在这台电脑；无路径、无书名的阅读进度可进入 v28 备份，并可通过已启用的阅读进度对象同步。", "Reader settings stay on this PC. Path-free, title-free progress can enter v28 backups and sync through the enabled reader-progress object.")}</p>
        </div>
        <div className="row">
          <button className="button-ghost" type="button" disabled={busy} onClick={onReset}>
            <RotateCcw aria-hidden="true" size={16} />
            {copy("恢复默认", "Restore defaults")}
          </button>
          <button className="button-primary" type="button" disabled={busy} onClick={onSave}>
            <Save aria-hidden="true" size={16} />
            {busy ? copy("保存中…", "Saving…") : copy("保存", "Save")}
          </button>
          <button className="icon-button" type="button" disabled={busy} onClick={onClose} aria-label={copy("关闭设置", "Close settings")}>
            <X aria-hidden="true" size={18} />
          </button>
        </div>
      </div>
      {error ? <div className="inline-error" role="alert">{error}</div> : null}
      <div className="reader-settings-grid">
        <label className="field">
          <span className="field-label">{copy("阅读背景", "Reading background")}</span>
          <select
            value={draft.background}
            onChange={(event) => onChange({ ...draft, background: event.target.value as ReaderPreferencesV1["background"] })}
          >
            <option value="white">{copy("白色", "White")}</option>
            <option value="paper">{copy("纸张", "Paper")}</option>
            <option value="sepia">{copy("羊皮纸", "Sepia")}</option>
            <option value="green">{copy("护眼绿", "Reading green")}</option>
            <option value="night">{copy("夜间", "Night")}</option>
            <option value="custom">{copy("自定义", "Custom")}</option>
          </select>
        </label>
        {draft.background === "custom" ? (
          <label className="field">
            <span className="field-label">{copy("自定义背景色", "Custom background")}</span>
            <input
              type="color"
              value={argbToCss(draft.customBackgroundArgb)}
              onChange={(event) => onChange({ ...draft, customBackgroundArgb: cssToArgb(event.target.value) })}
            />
          </label>
        ) : null}
        <label className="field reader-setting-range">
          <span className="field-label">{copy("TXT 字号", "TXT font size")} · {draft.fontSizePx.toFixed(0)} px</span>
          <input type="range" min="12" max="38" step="1" value={draft.fontSizePx} onChange={(event) => onChange({ ...draft, fontSizePx: Number(event.target.value) })} />
        </label>
        <label className="field reader-setting-range">
          <span className="field-label">{copy("行距", "Line height")} · {draft.lineHeightMultiplier.toFixed(1)}</span>
          <input type="range" min="1" max="2.4" step="0.1" value={draft.lineHeightMultiplier} onChange={(event) => onChange({ ...draft, lineHeightMultiplier: Number(event.target.value) })} />
        </label>
        <label className="field reader-setting-range">
          <span className="field-label">{copy("段距", "Paragraph spacing")} · {draft.paragraphSpacingPx.toFixed(0)} px</span>
          <input type="range" min="0" max="36" step="1" value={draft.paragraphSpacingPx} onChange={(event) => onChange({ ...draft, paragraphSpacingPx: Number(event.target.value) })} />
        </label>
        <label className="field reader-setting-range">
          <span className="field-label">{copy("PDF 基准缩放", "PDF base zoom")} · {draft.pdfZoomPercent}%</span>
          <input type="range" min="50" max="300" step="10" value={draft.pdfZoomPercent} onChange={(event) => onChange({ ...draft, pdfZoomPercent: Number(event.target.value) })} />
        </label>
        <label className="field">
          <span className="field-label">{copy("章节识别", "Chapter detection")}</span>
          <select
            value={draft.chapterDetectionMode}
            onChange={(event) => onChange({ ...draft, chapterDetectionMode: event.target.value as ReaderPreferencesV1["chapterDetectionMode"] })}
          >
            <option value="smartAndCustom">{copy("智能 + 自定义", "Smart + custom")}</option>
            <option value="smart">{copy("仅智能", "Smart only")}</option>
            <option value="custom">{copy("仅自定义正则", "Custom regex only")}</option>
          </select>
        </label>
        <label className="field reader-setting-range">
          <span className="field-label">{copy("标题最大长度", "Maximum heading length")} · {draft.chapterHeadingMaxChars}</span>
          <input type="range" min="20" max="240" step="5" value={draft.chapterHeadingMaxChars} onChange={(event) => onChange({ ...draft, chapterHeadingMaxChars: Number(event.target.value) })} />
        </label>
        <label className="field reader-settings-regex">
          <span className="field-label">{copy("自定义章节正则", "Custom chapter regex")}</span>
          <input
            value={draft.customChapterRegex}
            maxLength={1024}
            spellCheck={false}
            placeholder={copy("例如：Scene\\s+\\d+", "For example: Scene\\s+\\d+")}
            onChange={(event) => onChange({ ...draft, customChapterRegex: event.target.value })}
          />
          <small className="field-hint">{copy("必须匹配整行；Rust 正则不支持回溯引用或环视。", "Must match the full line. Rust regex does not support backreferences or look-around.")}</small>
        </label>
      </div>
    </section>
  );
}

export default function ReaderPage() {
  const language = useAppStore((state) => state.appearance.language);
  const copy = useCallback((zh: string, en: string) => tr(language, zh, en), [language]);
  const [library, setLibrary] = useState<ReaderLibraryV1 | null>(null);
  const [activeDocument, setActiveDocument] = useState<ReaderDocumentV1 | null>(null);
  const [pageIndex, setPageIndex] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [tocOpen, setTocOpen] = useState(true);
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchMatchIndex, setSearchMatchIndex] = useState(0);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settingsDraft, setSettingsDraft] = useState<ReaderPreferencesV1>(DEFAULT_READER_PREFERENCES);
  const [settingsError, setSettingsError] = useState("");
  const [settingsDiscardConfirm, setSettingsDiscardConfirm] = useState(false);
  const [removeTarget, setRemoveTarget] = useState<ReaderBookV1 | null>(null);
  const progressSequence = useRef(0);

  const settingsDirty = useMemo(
    () => Boolean(library) && JSON.stringify(settingsDraft) !== JSON.stringify(library?.preferences),
    [library, settingsDraft],
  );

  const loadLibrary = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const next = await readerApi.library();
      setLibrary(next);
      setSettingsDraft(next.preferences);
    } catch (reason) {
      setError(readerErrorMessage(reason, language));
    } finally {
      setLoading(false);
    }
  }, [language]);

  useEffect(() => {
    void loadLibrary();
  }, [loadLibrary]);

  const applyDocument = useCallback((next: ReaderDocumentV1) => {
    setActiveDocument(next);
    setPageIndex(
      next.kind === "txt"
        ? Math.min(next.book.textPageIndex, Math.max(next.pages.length - 1, 0))
        : Math.min(next.book.pdfPageIndex, MAX_PDF_PAGE_INDEX),
    );
    setSearchQuery("");
    setSearchMatchIndex(0);
    setNotice("");
    setLibrary((current) => replaceBook(current, next.book));
  }, []);

  async function chooseBook() {
    setBusy("choose");
    setError("");
    setNotice("");
    try {
      const chosen = await readerApi.chooseBook();
      if (chosen) {
        applyDocument(chosen);
        const nextLibrary = await readerApi.library();
        setLibrary(nextLibrary);
        setSettingsDraft(nextLibrary.preferences);
      }
    } catch (reason) {
      setError(readerErrorMessage(reason, language));
    } finally {
      setBusy("");
    }
  }

  async function openBook(bookId: string) {
    setBusy(`open:${bookId}`);
    setError("");
    setNotice("");
    try {
      applyDocument(await readerApi.openBook(bookId));
    } catch (reason) {
      setError(readerErrorMessage(reason, language));
    } finally {
      setBusy("");
    }
  }

  async function closeBook() {
    if (!activeDocument) return;
    const closingDocument = activeDocument;
    const closingPage = Math.max(0, Math.floor(pageIndex));
    const paragraphIndex =
      closingDocument.kind === "txt"
        ? closingDocument.pages[closingPage]?.firstParagraphIndex ?? 0
        : undefined;
    setBusy("close");
    setError("");
    try {
      const book = await readerApi.saveProgress({
        bookId: closingDocument.book.id,
        pageIndex: closingPage,
        ...(paragraphIndex === undefined ? {} : { paragraphIndex }),
      });
      setLibrary((current) => replaceBook(current, book));
      setActiveDocument(null);
    } catch (reason) {
      setError(readerErrorMessage(reason, language));
    } finally {
      setBusy("");
    }
  }

  async function removeBook() {
    if (!removeTarget) return;
    setBusy("remove");
    setError("");
    try {
      const next = await readerApi.removeBook(removeTarget.id);
      setLibrary(next);
      if (activeDocument?.book.id === removeTarget.id) setActiveDocument(null);
      setRemoveTarget(null);
      setNotice(copy("已从书库移除；原文件没有被删除。", "Removed from the library; the original file was not deleted."));
    } catch (reason) {
      setError(readerErrorMessage(reason, language));
    } finally {
      setBusy("");
    }
  }

  const progressBookId = activeDocument?.book.id ?? null;
  const progressKind = activeDocument?.kind ?? null;
  const progressParagraphIndex =
    activeDocument?.kind === "txt"
      ? activeDocument.pages[pageIndex]?.firstParagraphIndex ?? 0
      : undefined;

  useEffect(() => {
    if (!progressBookId || !progressKind) return;
    const sequence = ++progressSequence.current;
    const timeout = window.setTimeout(() => {
      const page = Math.max(0, Math.floor(pageIndex));
      void readerApi
        .saveProgress({
          bookId: progressBookId,
          pageIndex: page,
          ...(progressParagraphIndex === undefined
            ? {}
            : { paragraphIndex: progressParagraphIndex }),
        })
        .then((book) => {
          if (sequence !== progressSequence.current) return;
          setActiveDocument((current) =>
            current?.book.id === book.id ? { ...current, book } : current,
          );
          setLibrary((current) => replaceBook(current, book));
        })
        .catch((reason: unknown) => {
          if (sequence === progressSequence.current) {
            setError(readerErrorMessage(reason, language));
          }
        });
    }, 250);
    return () => window.clearTimeout(timeout);
  }, [language, pageIndex, progressBookId, progressKind, progressParagraphIndex]);

  useEffect(() => {
    const bookId = activeDocument?.book.id;
    if (!bookId) return;
    let lastCheckpoint = performance.now();
    const checkpoint = (force = false) => {
      if (!force && globalThis.document.visibilityState !== "visible") return;
      const now = performance.now();
      const deltaMillis = Math.min(Math.floor(now - lastCheckpoint), 5 * 60 * 1_000);
      lastCheckpoint = now;
      if (deltaMillis < 1_000) return;
      void readerApi
        .recordTime(bookId, deltaMillis)
        .then((book) => {
          setActiveDocument((current) =>
            current?.book.id === book.id ? { ...current, book } : current,
          );
          setLibrary((current) => replaceBook(current, book));
        })
        .catch(() => {
          // Engagement time is best-effort and private. A failed checkpoint must not interrupt
          // reading or expose a storage/path error through an unload callback.
        });
    };
    const interval = window.setInterval(() => checkpoint(), 30_000);
    const visibility = () => {
      if (globalThis.document.visibilityState === "hidden") checkpoint(true);
      else lastCheckpoint = performance.now();
    };
    globalThis.document.addEventListener("visibilitychange", visibility);
    return () => {
      window.clearInterval(interval);
      globalThis.document.removeEventListener("visibilitychange", visibility);
      checkpoint(true);
    };
  }, [activeDocument?.book.id]);

  const textMatches = useMemo(
    () =>
      activeDocument?.kind === "txt"
        ? findReaderTextMatches(activeDocument, searchQuery)
        : [],
    [activeDocument, searchQuery],
  );

  useEffect(() => {
    setSearchMatchIndex(0);
  }, [activeDocument?.book.id, searchQuery]);

  function moveSearchResult(direction: -1 | 1) {
    if (!textMatches.length) return;
    const next = (searchMatchIndex + direction + textMatches.length) % textMatches.length;
    setSearchMatchIndex(next);
    setPageIndex(textMatches[next].pageIndex);
  }

  async function copyCurrentPage() {
    if (activeDocument?.kind !== "txt") return;
    try {
      await navigator.clipboard.writeText(activeDocument.pages[pageIndex]?.text ?? "");
      setNotice(copy("当前逻辑页已复制。", "Current logical page copied."));
    } catch {
      setError(copy("无法写入剪贴板，请选择文字后按 Ctrl+C。", "Clipboard access failed. Select text and press Ctrl+C."));
    }
  }

  function openSettings() {
    const preferences = library?.preferences ?? DEFAULT_READER_PREFERENCES;
    setSettingsDraft(preferences);
    setSettingsError("");
    setSettingsOpen(true);
  }

  function requestSettingsClose() {
    if (settingsDirty) setSettingsDiscardConfirm(true);
    else setSettingsOpen(false);
  }

  async function saveSettings() {
    setBusy("settings");
    setSettingsError("");
    try {
      const currentDocument = activeDocument;
      const currentPage = pageIndex;
      const currentParagraph =
        currentDocument?.kind === "txt"
          ? currentDocument.pages[currentPage]?.firstParagraphIndex ?? 0
          : undefined;
      const next = await readerApi.savePreferences(settingsDraft);
      setLibrary(next);
      setSettingsDraft(next.preferences);
      setSettingsOpen(false);
      setNotice(copy("阅读设置已保存。", "Reader settings saved."));
      if (currentDocument) {
        try {
          const reopened = await readerApi.openBook(currentDocument.book.id);
          applyDocument(reopened);
          if (reopened.kind === "txt" && currentParagraph !== undefined) {
            const resumedPage = reopened.pages.reduce(
              (match, page, index) =>
                page.firstParagraphIndex <= currentParagraph ? index : match,
              0,
            );
            setPageIndex(resumedPage);
          } else {
            setPageIndex(Math.min(currentPage, MAX_PDF_PAGE_INDEX));
          }
        } catch (reason) {
          setError(readerErrorMessage(reason, language));
        }
      }
    } catch (reason) {
      setSettingsError(readerErrorMessage(reason, language));
    } finally {
      setBusy("");
    }
  }

  const currentTextPageCount = activeDocument?.kind === "txt" ? activeDocument.pages.length : 0;
  const progressPercent = currentTextPageCount
    ? Math.round(((pageIndex + 1) / currentTextPageCount) * 100)
    : 0;
  const pdfBaseUrl =
    activeDocument?.kind === "pdf"
      ? safeReaderPdfUrl(activeDocument.assetUrl, activeDocument.book.id)
      : null;
  const pdfUrl = pdfBaseUrl
    ? `${pdfBaseUrl}#page=${pageIndex + 1}&zoom=${activeDocument?.preferences.pdfZoomPercent ?? 100}`
    : null;
  const readerStyle = {
    "--reader-font-size": `${activeDocument?.preferences.fontSizePx ?? 19}px`,
    "--reader-line-height": activeDocument?.preferences.lineHeightMultiplier ?? 1.6,
    ...(activeDocument?.preferences.background === "custom"
      ? { "--reader-custom-background": argbToCss(activeDocument.preferences.customBackgroundArgb) }
      : {}),
  } as CSSProperties;

  if (loading && !library) {
    return (
      <PageFrame title={copy("阅读", "Reader")}>
        <div className="panel"><LoadingState label={copy("正在读取本机书库", "Loading local library")} /></div>
      </PageFrame>
    );
  }

  if (!library && error) {
    return (
      <PageFrame title={copy("阅读", "Reader")}>
        <div className="panel">
          <ErrorState title={copy("无法打开阅读书库", "Reader library unavailable")} description={error} retry={() => void loadLibrary()} />
        </div>
      </PageFrame>
    );
  }

  return (
    <PageFrame
      className={`reader-page${activeDocument ? " reader-is-open" : ""}`}
      eyebrow={copy("本机私有 · 原文件只读", "Private on this PC · Original files read-only")}
      title={activeDocument?.book.title ?? copy("阅读", "Reader")}
      description={
        activeDocument
          ? activeDocument.kind === "txt"
            ? copy(`第 ${pageIndex + 1} / ${currentTextPageCount} 逻辑页 · ${progressPercent}%`, `Logical page ${pageIndex + 1} of ${currentTextPageCount} · ${progressPercent}%`)
            : copy(`已保存到第 ${pageIndex + 1} 页`, `Saved at page ${pageIndex + 1}`)
          : copy("显式打开本机 TXT/PDF；书架路径、阅读设置和时长保持本机私有，无路径、无书名的进度可进入 v28 备份及可选云同步。", "Explicitly open local TXT/PDF files. Library paths, settings, and reading time remain private; path-free, title-free progress can enter v28 backups and optional cloud sync.")
      }
      actions={
        activeDocument ? (
          <>
            <button className="button-secondary" type="button" disabled={!!busy || settingsOpen} onClick={() => void closeBook()}>
              <ArrowLeft aria-hidden="true" size={17} />
              {copy("返回书库", "Back to library")}
            </button>
            {activeDocument.kind === "txt" ? (
              <button className="button-secondary" type="button" disabled={!!busy || settingsOpen} aria-pressed={searchOpen} onClick={() => setSearchOpen((open) => !open)}>
                <Search aria-hidden="true" size={17} />
                {copy("全文搜索", "Full-text search")}
              </button>
            ) : null}
            <button className="button-secondary" type="button" disabled={!!busy || settingsOpen} onClick={openSettings}>
              <Settings2 aria-hidden="true" size={17} />
              {copy("阅读设置", "Reader settings")}
            </button>
          </>
        ) : (
          <>
            <button className="button-secondary" type="button" disabled={!!busy || settingsOpen} onClick={openSettings}>
              <Settings2 aria-hidden="true" size={17} />
              {copy("阅读设置", "Reader settings")}
            </button>
            <button className="button-primary" type="button" disabled={!!busy || settingsOpen} onClick={() => void chooseBook()}>
              <FilePlus2 aria-hidden="true" size={17} />
              {busy === "choose" ? copy("打开中…", "Opening…") : copy("打开 TXT / PDF", "Open TXT / PDF")}
            </button>
          </>
        )
      }
    >
      <UnsavedChangesGuard
        when={settingsOpen && settingsDirty}
        scope="reader-settings"
        title={copy("放弃未保存的阅读设置？", "Discard unsaved reader settings?")}
        description={copy("离开阅读页会丢弃当前设置草稿。", "Leaving the reader discards the current settings draft.")}
        onDiscard={() => {
          setSettingsOpen(false);
          setSettingsDraft(library?.preferences ?? DEFAULT_READER_PREFERENCES);
        }}
      />
      {error ? <div className="inline-error" role="alert">{error}</div> : null}
      {notice ? <div className="status-banner success" role="status">{notice}</div> : null}

      {settingsOpen ? (
        <ReaderSettings
          language={language}
          draft={settingsDraft}
          busy={busy === "settings"}
          error={settingsError}
          onChange={setSettingsDraft}
          onReset={() => setSettingsDraft(DEFAULT_READER_PREFERENCES)}
          onSave={() => void saveSettings()}
          onClose={requestSettingsClose}
        />
      ) : null}

      {!activeDocument ? (
        <>
          <aside className="panel reader-boundary-note">
            <LibraryBig aria-hidden="true" size={23} />
            <div>
              <h2>{copy("文件与数据边界", "File and data boundary")}</h2>
              <p>{copy("DeskCubby 只读你明确选择的原文件；从书库移除不会删除文件，绝对路径和正文不会出现在错误消息中。", "DeskCubby reads only files you explicitly choose. Removing a book never deletes it, and absolute paths or content never appear in errors.")}</p>
            </div>
            <strong>{formatReadingTime(library?.totalReadingMillis ?? "0", language)}</strong>
          </aside>
          {library?.books.length ? (
            <section className="reader-library-grid" aria-label={copy("本机书库", "Local library")}>
              {library.books.map((book) => (
                <ReaderBookCard
                  key={book.id}
                  book={book}
                  language={language}
                  busy={!!busy || settingsOpen}
                  onOpen={() => void openBook(book.id)}
                  onRemove={() => setRemoveTarget(book)}
                />
              ))}
            </section>
          ) : (
            <div className="panel">
              <EmptyState
                title={copy("书库还是空的", "Your library is empty")}
                description={copy("选择一个 UTF-8 / UTF-16 / GB18030 TXT，或交给 Windows PDF 查看器连续阅读。", "Choose a UTF-8 / UTF-16 / GB18030 TXT, or read a PDF continuously in the Windows PDF viewer.")}
                icon={BookOpen}
                action={<button className="button-primary" type="button" disabled={!!busy} onClick={() => void chooseBook()}><FilePlus2 aria-hidden="true" size={17} />{copy("打开文件", "Open file")}</button>}
              />
            </div>
          )}
        </>
      ) : (
        <section className="reader-document-shell" style={readerStyle}>
          {searchOpen && activeDocument.kind === "txt" ? (
            <div className="panel reader-search-bar" role="search">
              <Search aria-hidden="true" size={18} />
              <label>
                <span className="sr-only">{copy("搜索整本 TXT", "Search the whole TXT")}</span>
                <input autoFocus value={searchQuery} maxLength={MAX_READER_SEARCH_QUERY_CHARS} placeholder={copy("搜索整本 TXT", "Search the whole TXT")} onChange={(event) => setSearchQuery(event.target.value)} />
              </label>
              <output aria-live="polite">
                {searchQuery.trim()
                  ? copy(`${textMatches.length ? searchMatchIndex + 1 : 0} / ${textMatches.length}`, `${textMatches.length ? searchMatchIndex + 1 : 0} / ${textMatches.length}`)
                  : copy("输入关键词", "Enter a query")}
              </output>
              <button className="icon-button" type="button" disabled={!textMatches.length} onClick={() => moveSearchResult(-1)} aria-label={copy("上一个结果", "Previous result")}><ChevronLeft aria-hidden="true" size={18} /></button>
              <button className="icon-button" type="button" disabled={!textMatches.length} onClick={() => moveSearchResult(1)} aria-label={copy("下一个结果", "Next result")}><ChevronRight aria-hidden="true" size={18} /></button>
              <button className="icon-button" type="button" onClick={() => setSearchOpen(false)} aria-label={copy("关闭搜索", "Close search")}><X aria-hidden="true" size={18} /></button>
            </div>
          ) : null}

          <div className={`reader-workspace${tocOpen ? " has-toc" : ""}`}>
            {tocOpen && activeDocument.kind === "txt" ? (
              <aside className="panel reader-toc" aria-label={copy("目录", "Table of contents")}>
                <div className="panel-header">
                  <div><h2>{copy("目录", "Contents")}</h2><p>{copy(`${activeDocument.chapters.length} 个章节`, `${activeDocument.chapters.length} chapters`)}</p></div>
                  <button className="icon-button" type="button" onClick={() => setTocOpen(false)} aria-label={copy("收起目录", "Hide contents")}><X aria-hidden="true" size={17} /></button>
                </div>
                {activeDocument.chapters.length ? (
                  <ol>
                    {activeDocument.chapters.map((chapter, index) => (
                      <li key={`${chapter.pageIndex}-${chapter.paragraphIndex}-${index}`}>
                        <button className={chapter.pageIndex === pageIndex ? "is-current" : undefined} type="button" onClick={() => setPageIndex(chapter.pageIndex)}>
                          <span>{chapter.title}</span><small>{chapter.pageIndex + 1}</small>
                        </button>
                      </li>
                    ))}
                  </ol>
                ) : <p className="reader-toc-empty">{copy("没有识别到章节，可在阅读设置中调整规则。", "No chapters detected. Adjust the rules in reader settings.")}</p>}
              </aside>
            ) : null}

            <div className="reader-content-column">
              <div className="panel reader-toolbar" aria-label={copy("阅读控制", "Reader controls")}>
                {activeDocument.kind === "txt" && !tocOpen ? (
                  <button className="icon-button" type="button" onClick={() => setTocOpen(true)} aria-label={copy("显示目录", "Show contents")}><List aria-hidden="true" size={18} /></button>
                ) : null}
                <button className="icon-button" type="button" disabled={pageIndex <= 0} onClick={() => setPageIndex((page) => Math.max(page - 1, 0))} aria-label={copy("上一页", "Previous page")}><ChevronLeft aria-hidden="true" size={19} /></button>
                {activeDocument.kind === "txt" ? (
                  <>
                    <label className="reader-progress-range">
                      <span className="sr-only">{copy("阅读进度", "Reading progress")}</span>
                      <input type="range" min="1" max={Math.max(currentTextPageCount, 1)} value={pageIndex + 1} onChange={(event) => setPageIndex(Number(event.target.value) - 1)} />
                    </label>
                    <label className="reader-page-input">
                      <span>{copy("页", "Page")}</span>
                      <input type="number" min="1" max={Math.max(currentTextPageCount, 1)} value={pageIndex + 1} onChange={(event) => setPageIndex(Math.min(Math.max(Number(event.target.value || 1) - 1, 0), Math.max(currentTextPageCount - 1, 0)))} />
                      <span>/ {currentTextPageCount}</span>
                    </label>
                  </>
                ) : (
                  <label className="reader-page-input reader-pdf-page-input">
                    <span>{copy("保存页码", "Saved page")}</span>
                    <input type="number" min="1" max={MAX_PDF_PAGE_INDEX + 1} value={pageIndex + 1} onChange={(event) => setPageIndex(Math.min(Math.max(Number(event.target.value || 1) - 1, 0), MAX_PDF_PAGE_INDEX))} />
                  </label>
                )}
                <button className="icon-button" type="button" disabled={activeDocument.kind === "txt" ? pageIndex >= currentTextPageCount - 1 : pageIndex >= MAX_PDF_PAGE_INDEX} onClick={() => setPageIndex((page) => Math.min(page + 1, activeDocument.kind === "txt" ? currentTextPageCount - 1 : MAX_PDF_PAGE_INDEX))} aria-label={copy("下一页", "Next page")}><ChevronRight aria-hidden="true" size={19} /></button>
                {activeDocument.kind === "txt" ? (
                  <button className="button-ghost button-small" type="button" onClick={() => void copyCurrentPage()}><Clipboard aria-hidden="true" size={16} />{copy("复制当前页", "Copy page")}</button>
                ) : <output className="reader-pdf-zoom">{activeDocument.preferences.pdfZoomPercent}%</output>}
              </div>

              {activeDocument.kind === "txt" ? (
                <div className={`reader-text-surface background-${activeDocument.preferences.background}`} style={activeDocument.preferences.background === "custom" ? { background: argbToCss(activeDocument.preferences.customBackgroundArgb) } : undefined}>
                  <HighlightedPage document={activeDocument} pageIndex={pageIndex} matches={textMatches} currentMatchIndex={searchMatchIndex} paragraphSpacing={activeDocument.preferences.paragraphSpacingPx} />
                </div>
              ) : pdfUrl ? (
                <div className="reader-pdf-surface">
                  <div className="reader-pdf-note" role="note">
                    {copy("PDF 由 Windows WebView2 的连续查看器显示；使用查看器工具栏或 Ctrl+F 搜索，拖选文字后按 Ctrl+C 复制。应用只保存上方页码。", "PDF uses the continuous Windows WebView2 viewer. Search with its toolbar or Ctrl+F; select text and press Ctrl+C to copy. The app saves only the page number above.")}
                  </div>
                  <iframe key={pdfUrl} title={copy(`${activeDocument.book.title} PDF 连续阅读`, `${activeDocument.book.title} continuous PDF reader`)} src={pdfUrl} referrerPolicy="no-referrer" />
                </div>
              ) : (
                <div className="panel"><ErrorState title={copy("无法显示 PDF", "PDF cannot be displayed")} description={copy("后端返回了无效的阅读地址；绝对路径未被使用。", "The backend returned an invalid reader URL; no absolute path was used.")} /></div>
              )}
            </div>
          </div>
        </section>
      )}

      <ConfirmDialog
        open={Boolean(removeTarget)}
        title={copy("从书库移除？", "Remove from library?")}
        description={copy(`只移除《${removeTarget?.title ?? ""}》的本机书库记录和进度，不会删除原文件。`, `Only the local library entry and progress for ${removeTarget?.title ?? ""} will be removed. The original file will not be deleted.`)}
        confirmLabel={copy("移除", "Remove")}
        destructive
        busy={busy === "remove"}
        onConfirm={() => void removeBook()}
        onCancel={() => setRemoveTarget(null)}
      />
      <ConfirmDialog
        open={settingsDiscardConfirm}
        title={copy("放弃未保存的阅读设置？", "Discard unsaved reader settings?")}
        description={copy("关闭后，本页草稿不会保留。", "Closing now discards the local draft.")}
        confirmLabel={copy("放弃更改", "Discard changes")}
        destructive
        onConfirm={() => { setSettingsDiscardConfirm(false); setSettingsOpen(false); setSettingsDraft(library?.preferences ?? DEFAULT_READER_PREFERENCES); }}
        onCancel={() => setSettingsDiscardConfirm(false)}
      />
    </PageFrame>
  );
}
