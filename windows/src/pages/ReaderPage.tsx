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
  Maximize2,
  Minimize2,
  Minus,
  Plus,
  RotateCcw,
  RotateCw,
  Save,
  Search,
  Settings2,
  Trash2,
  X,
} from "lucide-react";
import {
  type CSSProperties,
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
  argbToCss,
  automaticReaderForeground,
  cssToArgb,
  readerPalette,
} from "../lib/readerAppearance";
import {
  readerApi,
  type ReaderBookV3,
  type ReaderDocumentV3,
  type ReaderLibraryV3,
  type ReaderPreferencesV3,
  type ReaderTextDocumentV3,
} from "../lib/readerApi";
import { useAppStore } from "../store/appStore";
import "../styles/reader.css";
import ReaderPdfViewer, { type ReaderPdfSearchMatch } from "./readerPdfViewer";
import TextScrollView, {
  type ReaderTextScrollPosition,
} from "./readerTextScroll";

const DEFAULT_READER_PREFERENCES: ReaderPreferencesV3 = {
  background: "paper",
  customBackgroundArgb: -724762,
  customForegroundArgb: null,
  fontSizePx: 19,
  fontFamily: "serif",
  lineHeightMultiplier: 1.6,
  paragraphSpacingPx: 10,
  contentWidthPx: 960,
  textAlignment: "start",
  firstLineIndentEm: 0,
  letterSpacingPx: 0,
  pagePaddingPx: 36,
  pdfZoomPercent: 100,
  pdfColorMode: "original",
  pdfScrollMode: "continuous",
  pdfPageGapPx: 18,
  immersiveMode: false,
  showProgressPercentage: false,
  libraryLayout: "list",
  showGridBookTitles: true,
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
  document: ReaderTextDocumentV3,
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
  if (typeof assetUrl !== "string") return null;
  try {
    const parsed = new URL(assetUrl);
    const expectedPath = `/${bookId}.pdf`;
    const isProductionProtocol =
      parsed.protocol === "http:" && parsed.hostname === "reader.localhost";
    const isNativeProtocol =
      parsed.protocol === "reader:" && parsed.hostname === "localhost";
    if (
      (!isProductionProtocol && !isNativeProtocol) ||
      parsed.port ||
      parsed.username ||
      parsed.password ||
      parsed.pathname !== expectedPath ||
      parsed.search ||
      parsed.hash
    ) {
      return null;
    }
    return parsed.href;
  } catch {
    return null;
  }
}

function readerBookProgressPercent(book: ReaderBookV3): number {
  if (book.totalPages <= 1) return 0;
  const page = book.bookType === "pdf" ? book.pdfPageIndex : book.textPageIndex;
  return Math.round((Math.min(Math.max(page, 0), book.totalPages - 1) / (book.totalPages - 1)) * 100);
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
  library: ReaderLibraryV3 | null,
  book: ReaderBookV3,
): ReaderLibraryV3 | null {
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
  layout,
  showGridBookTitles,
  showProgressPercentage,
  busy,
  onOpen,
  onRemove,
}: {
  book: ReaderBookV3;
  language: "zh-CN" | "en";
  layout: ReaderPreferencesV3["libraryLayout"];
  showGridBookTitles: boolean;
  showProgressPercentage: boolean;
  busy: boolean;
  onOpen: () => void;
  onRemove: () => void;
}) {
  const locale = language === "en" ? "en-US" : "zh-CN";
  const progress = readerBookProgressPercent(book);
  return (
    <article className={`card reader-book-card layout-${layout}`}>
      <button
        className="reader-book-open"
        type="button"
        disabled={busy}
        onClick={onOpen}
        aria-label={tr(language, `打开《${book.title}》`, `Open ${book.title}`)}
      >
        <span className="reader-book-icon" aria-hidden="true">
          {book.bookType === "pdf" ? <FileText /> : <BookOpen />}
          {layout === "grid" ? <em>{book.title}</em> : null}
        </span>
        <span className="reader-book-copy">
          {layout !== "grid" || showGridBookTitles ? <strong>{book.title}</strong> : null}
          <span>
            {book.bookType.toUpperCase()} · {formatReadingTime(book.readingMillis, language)}
          </span>
          <small>
            {tr(language, "最近打开", "Last opened")} {" "}
            {new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(
              new Date(book.lastOpenedAt),
            )}
          </small>
          {showProgressPercentage && book.totalPages > 1 ? (
            <span className="reader-book-progress">
              <span aria-hidden="true"><i style={{ width: `${progress}%` }} /></span>
              <small>{progress}%</small>
            </span>
          ) : null}
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
  draft: ReaderPreferencesV3;
  busy: boolean;
  error: string;
  onChange: (next: ReaderPreferencesV3) => void;
  onReset: () => void;
  onSave: () => void;
  onClose: () => void;
}) {
  const copy = (zh: string, en: string) => tr(language, zh, en);
  const palette = readerPalette(draft);
  const previewFontFamily = {
    serif: 'ui-serif, Georgia, "Noto Serif SC", serif',
    sans: "var(--font-sans)",
    mono: 'ui-monospace, "Cascadia Mono", Consolas, monospace',
  }[draft.fontFamily];
  return (
    <section className="panel reader-settings" aria-labelledby="reader-settings-title">
      <div className="panel-header reader-settings-header">
        <div>
          <h2 id="reader-settings-title">{copy("阅读设置", "Reader settings")}</h2>
          <p>{copy("阅读设置仅保存在这台电脑；无路径、无书名的阅读进度可进入 v33 备份，并可通过已启用的阅读进度对象同步。", "Reader settings stay on this PC. Path-free, title-free progress can enter v33 backups and sync through the enabled reader-progress object.")}</p>
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
          <button autoFocus className="icon-button" type="button" disabled={busy} onClick={onClose} aria-label={copy("关闭设置", "Close settings")}>
            <X aria-hidden="true" size={18} />
          </button>
        </div>
      </div>
      {error ? <div className="inline-error" role="alert">{error}</div> : null}
      <div className="reader-settings-sections">
        <fieldset className="reader-settings-section">
          <legend>{copy("颜色与页面", "Color and page")}</legend>
          <div className="reader-settings-grid">
            <label className="field">
              <span className="field-label">{copy("阅读背景", "Reading background")}</span>
              <select
                value={draft.background}
                onChange={(event) => onChange({ ...draft, background: event.target.value as ReaderPreferencesV3["background"] })}
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
              <label className="field reader-color-field">
                <span className="field-label">{copy("自定义背景色", "Custom background")}</span>
                <input
                  type="color"
                  value={argbToCss(draft.customBackgroundArgb)}
                  onChange={(event) => onChange({ ...draft, customBackgroundArgb: cssToArgb(event.target.value) })}
                />
              </label>
            ) : null}
            <label className="field">
              <span className="field-label">{copy("文字颜色", "Text color")}</span>
              <select
                value={draft.customForegroundArgb === null ? "auto" : "custom"}
                onChange={(event) => onChange({
                  ...draft,
                  customForegroundArgb: event.target.value === "auto"
                    ? null
                    : cssToArgb(automaticReaderForeground(draft)),
                })}
              >
                <option value="auto">{copy("自动对比色", "Automatic contrast")}</option>
                <option value="custom">{copy("自定义", "Custom")}</option>
              </select>
            </label>
            {draft.customForegroundArgb !== null ? (
              <label className="field reader-color-field">
                <span className="field-label">{copy("自定义前景色", "Custom foreground")}</span>
                <input
                  type="color"
                  value={argbToCss(draft.customForegroundArgb)}
                  onChange={(event) => onChange({ ...draft, customForegroundArgb: cssToArgb(event.target.value) })}
                />
              </label>
            ) : null}
          </div>
          <div className="reader-color-preview" style={{
            background: palette.background,
            color: palette.foreground,
            fontFamily: previewFontFamily,
            fontSize: `${Math.min(draft.fontSizePx, 26)}px`,
            lineHeight: draft.lineHeightMultiplier,
            letterSpacing: `${draft.letterSpacingPx}px`,
            padding: `${Math.min(draft.pagePaddingPx, 48)}px`,
            textAlign: draft.textAlignment,
          }}>
            <strong>{copy("阅读效果预览", "Reading preview")}</strong>
            <p style={{ textIndent: `${draft.firstLineIndentEm}em`, marginBlockEnd: `${draft.paragraphSpacingPx}px` }}>{copy("清晰的文字应该始终与背景保持足够对比。拖动下方排版设置会立即更新此预览。", "Readable text should always maintain enough contrast with its background. Typography controls below update this preview immediately.")}</p>
            <small>{copy(`对比度 ${palette.contrast.toFixed(1)}:1`, `Contrast ${palette.contrast.toFixed(1)}:1`)}</small>
          </div>
          {palette.adjustedForContrast ? (
            <small className="field-hint reader-contrast-warning" role="status">{copy("自定义文字色对比度过低，阅读区已自动使用安全对比色；颜色值仍会保留。", "The custom text color has insufficient contrast, so the reader uses a safe automatic color while preserving your choice.")}</small>
          ) : null}
        </fieldset>

        <fieldset className="reader-settings-section">
          <legend>{copy("PDF 显示", "PDF display")}</legend>
          <div className="reader-settings-grid">
            <label className="field">
              <span className="field-label">{copy("页面颜色", "Page colors")}</span>
              <select value={draft.pdfColorMode} onChange={(event) => onChange({ ...draft, pdfColorMode: event.target.value as ReaderPreferencesV3["pdfColorMode"] })}>
                <option value="original">{copy("保留原稿（推荐）", "Original document (recommended)")}</option>
                <option value="readingColors">{copy("阅读配色（黑白映射）", "Reader colors (monochrome mapping)")}</option>
              </select>
              <small className="field-hint">{copy("原稿模式保留图片和彩色图表；阅读配色只改变画面副本，不修改文件。", "Original mode preserves images and charts. Reader colors affect only the rendered copy, never the file.")}</small>
            </label>
            <label className="field">
              <span className="field-label">{copy("翻页方式", "Page flow")}</span>
              <select value={draft.pdfScrollMode} onChange={(event) => onChange({ ...draft, pdfScrollMode: event.target.value as ReaderPreferencesV3["pdfScrollMode"] })}>
                <option value="continuous">{copy("连续纵向滚动", "Continuous vertical")}</option>
                <option value="singlePage">{copy("单页翻页", "Single page")}</option>
              </select>
            </label>
            <label className="field reader-setting-range">
              <span className="field-label">{copy("PDF 基准缩放", "PDF base zoom")} · {draft.pdfZoomPercent}%</span>
              <input type="range" min="50" max="300" step="10" value={draft.pdfZoomPercent} onChange={(event) => onChange({ ...draft, pdfZoomPercent: Number(event.target.value) })} />
            </label>
            <label className="field reader-setting-range">
              <span className="field-label">{copy("页间距", "Page gap")} · {draft.pdfPageGapPx} px</span>
              <input type="range" min="0" max="48" step="2" value={draft.pdfPageGapPx} onChange={(event) => onChange({ ...draft, pdfPageGapPx: Number(event.target.value) })} />
            </label>
          </div>
        </fieldset>

        <fieldset className="reader-settings-section">
          <legend>{copy("TXT 排版", "TXT typography")}</legend>
          <div className="reader-settings-grid">
            <label className="field">
              <span className="field-label">{copy("字体", "Font family")}</span>
              <select value={draft.fontFamily} onChange={(event) => onChange({ ...draft, fontFamily: event.target.value as ReaderPreferencesV3["fontFamily"] })}>
                <option value="serif">{copy("衬线（适合长文）", "Serif (long-form)")}</option>
                <option value="sans">{copy("无衬线", "Sans serif")}</option>
                <option value="mono">{copy("等宽", "Monospace")}</option>
              </select>
            </label>
            <label className="field">
              <span className="field-label">{copy("段落对齐", "Paragraph alignment")}</span>
              <select value={draft.textAlignment} onChange={(event) => onChange({ ...draft, textAlignment: event.target.value as ReaderPreferencesV3["textAlignment"] })}>
                <option value="start">{copy("自然左对齐", "Natural start")}</option>
                <option value="justify">{copy("两端对齐", "Justified")}</option>
              </select>
            </label>
            <label className="field reader-setting-range">
              <span className="field-label">{copy("字号", "Font size")} · {draft.fontSizePx.toFixed(0)} px</span>
              <input type="range" min="12" max="38" step="1" value={draft.fontSizePx} onChange={(event) => onChange({ ...draft, fontSizePx: Number(event.target.value) })} />
            </label>
            <label className="field reader-setting-range">
              <span className="field-label">{copy("正文宽度", "Text width")} · {draft.contentWidthPx} px</span>
              <input type="range" min="520" max="1280" step="40" value={draft.contentWidthPx} onChange={(event) => onChange({ ...draft, contentWidthPx: Number(event.target.value) })} />
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
              <span className="field-label">{copy("首行缩进", "First-line indent")} · {draft.firstLineIndentEm.toFixed(1)} em</span>
              <input type="range" min="0" max="3" step="0.25" value={draft.firstLineIndentEm} onChange={(event) => onChange({ ...draft, firstLineIndentEm: Number(event.target.value) })} />
            </label>
            <label className="field reader-setting-range">
              <span className="field-label">{copy("字间距", "Letter spacing")} · {draft.letterSpacingPx.toFixed(1)} px</span>
              <input type="range" min="-0.5" max="2" step="0.1" value={draft.letterSpacingPx} onChange={(event) => onChange({ ...draft, letterSpacingPx: Number(event.target.value) })} />
            </label>
            <label className="field reader-setting-range">
              <span className="field-label">{copy("页面留白", "Page padding")} · {draft.pagePaddingPx} px</span>
              <input type="range" min="12" max="96" step="4" value={draft.pagePaddingPx} onChange={(event) => onChange({ ...draft, pagePaddingPx: Number(event.target.value) })} />
            </label>
          </div>
        </fieldset>

        <fieldset className="reader-settings-section">
          <legend>{copy("阅读与书架", "Reading and shelf")}</legend>
          <div className="reader-settings-grid">
            <label className="field">
              <span className="field-label">{copy("书架布局", "Shelf layout")}</span>
              <select value={draft.libraryLayout} onChange={(event) => onChange({ ...draft, libraryLayout: event.target.value as ReaderPreferencesV3["libraryLayout"] })}>
                <option value="list">{copy("紧凑列表", "Compact list")}</option>
                <option value="grid">{copy("封面网格", "Cover grid")}</option>
              </select>
            </label>
            <div className="reader-toggle-setting">
              <span><strong>{copy("默认进入专注模式", "Start in focus mode")}</strong><small>{copy("控制栏悬浮在正文上，不占阅读高度。", "The controls float over the page instead of taking reading height.")}</small></span>
              <label className="switch-row"><input aria-label={copy("默认进入专注模式", "Start in focus mode")} type="checkbox" checked={draft.immersiveMode} onChange={(event) => onChange({ ...draft, immersiveMode: event.target.checked })} />{draft.immersiveMode ? copy("已开启", "On") : copy("已关闭", "Off")}</label>
            </div>
            <div className="reader-toggle-setting">
              <span><strong>{copy("显示书架进度", "Show shelf progress")}</strong><small>{copy("按 TXT 逻辑页或 PDF 实际页计算。", "Uses TXT logical pages or physical PDF pages.")}</small></span>
              <label className="switch-row"><input aria-label={copy("显示书架进度", "Show shelf progress")} type="checkbox" checked={draft.showProgressPercentage} onChange={(event) => onChange({ ...draft, showProgressPercentage: event.target.checked })} />{draft.showProgressPercentage ? copy("已开启", "On") : copy("已关闭", "Off")}</label>
            </div>
            {draft.libraryLayout === "grid" ? (
              <div className="reader-toggle-setting">
                <span><strong>{copy("封面下显示书名", "Show title below cover")}</strong><small>{copy("封面本身仍保留书名，关闭可减少重复信息。", "The cover still carries the title; turn this off to reduce repetition.")}</small></span>
                <label className="switch-row"><input aria-label={copy("封面下显示书名", "Show title below cover")} type="checkbox" checked={draft.showGridBookTitles} onChange={(event) => onChange({ ...draft, showGridBookTitles: event.target.checked })} />{draft.showGridBookTitles ? copy("已开启", "On") : copy("已关闭", "Off")}</label>
              </div>
            ) : null}
          </div>
        </fieldset>

        <fieldset className="reader-settings-section">
          <legend>{copy("章节识别", "Chapter detection")}</legend>
          <div className="reader-settings-grid">
            <label className="field">
              <span className="field-label">{copy("识别方式", "Detection mode")}</span>
              <select value={draft.chapterDetectionMode} onChange={(event) => onChange({ ...draft, chapterDetectionMode: event.target.value as ReaderPreferencesV3["chapterDetectionMode"] })}>
                <option value="smartAndCustom">{copy("智能 + 自定义", "Smart + custom")}</option>
                <option value="smart">{copy("仅智能", "Smart only")}</option>
                <option value="custom">{copy("仅自定义正则", "Custom regex only")}</option>
              </select>
            </label>
            <label className="field reader-setting-range">
              <span className="field-label">{copy("标题最大长度", "Maximum heading length")} · {draft.chapterHeadingMaxChars}</span>
              <input type="range" min="20" max="240" step="5" value={draft.chapterHeadingMaxChars} onChange={(event) => onChange({ ...draft, chapterHeadingMaxChars: Number(event.target.value) })} />
            </label>
            {draft.chapterDetectionMode !== "smart" ? (
              <label className="field reader-settings-regex">
                <span className="field-label">{copy("自定义章节正则", "Custom chapter regex")}</span>
                <input value={draft.customChapterRegex} maxLength={1024} spellCheck={false} placeholder={copy("例如：Scene\\s+\\d+", "For example: Scene\\s+\\d+")} onChange={(event) => onChange({ ...draft, customChapterRegex: event.target.value })} />
                <small className="field-hint">{copy("必须匹配整行；Rust 正则不支持回溯引用或环视。", "Must match the full line. Rust regex does not support backreferences or look-around.")}</small>
              </label>
            ) : null}
          </div>
        </fieldset>
      </div>
    </section>
  );
}

export default function ReaderPage() {
  const language = useAppStore((state) => state.appearance.language);
  const copy = useCallback((zh: string, en: string) => tr(language, zh, en), [language]);
  const [library, setLibrary] = useState<ReaderLibraryV3 | null>(null);
  const [activeDocument, setActiveDocument] = useState<ReaderDocumentV3 | null>(null);
  const [pageIndex, setPageIndex] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [tocOpen, setTocOpen] = useState(false);
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchMatchIndex, setSearchMatchIndex] = useState(0);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settingsDraft, setSettingsDraft] = useState<ReaderPreferencesV3>(DEFAULT_READER_PREFERENCES);
  const [settingsError, setSettingsError] = useState("");
  const [settingsDiscardConfirm, setSettingsDiscardConfirm] = useState(false);
  const [removeTarget, setRemoveTarget] = useState<ReaderBookV3 | null>(null);
  const [pdfPageCount, setPdfPageCount] = useState<number | null>(null);
  const [pdfSearchMatches, setPdfSearchMatches] = useState<ReaderPdfSearchMatch[]>([]);
  const [pdfSearching, setPdfSearching] = useState(false);
  const [pdfRotation, setPdfRotation] = useState(0);
  const [focusMode, setFocusMode] = useState(false);
  const [textJump, setTextJump] = useState({ token: 0, pageIndex: 0 });
  const [textPosition, setTextPosition] = useState<ReaderTextScrollPosition | null>(null);
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

  const applyDocument = useCallback((next: ReaderDocumentV3) => {
    setActiveDocument(next);
    setPageIndex(
      next.kind === "txt"
        ? Math.min(next.book.textPageIndex, Math.max(next.pages.length - 1, 0))
        : Math.min(next.book.pdfPageIndex, MAX_PDF_PAGE_INDEX),
    );
    setPdfPageCount(null);
    setPdfSearchMatches([]);
    setPdfSearching(false);
    setPdfRotation(0);
    setFocusMode(next.preferences.immersiveMode);
    setTocOpen(false);
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
        ? textPosition?.paragraphIndex ??
          closingDocument.pages[closingPage]?.firstParagraphIndex ??
          0
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
      setFocusMode(false);
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
    activeDocument?.kind === "txt" ? textPosition?.paragraphIndex : undefined;

  useEffect(() => {
    if (!progressBookId || !progressKind) return;
    const sequence = ++progressSequence.current;
    const timeout = window.setTimeout(() => {
      const page = textPosition?.pageIndex ?? Math.max(0, Math.floor(pageIndex));
      void readerApi
        .saveProgress({
          bookId: progressBookId,
          pageIndex: page,
          ...(progressParagraphIndex === undefined
            ? {}
            : {
                paragraphIndex: progressParagraphIndex,
                ...(textPosition === null ? {} : { pageOffsetPercent: textPosition.pageOffsetPercent }),
              }),
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
  }, [language, pageIndex, textPosition, progressBookId, progressKind, progressParagraphIndex]);

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

  const jumpToTextPage = useCallback((pageIndex: number) => {
    const pages = activeDocument?.kind === "txt" ? activeDocument.pages.length : 0;
    const target = Math.min(Math.max(pageIndex, 0), Math.max(pages - 1, 0));
    setPageIndex(target);
    setTextJump((previous) => ({ token: previous.token + 1, pageIndex: target }));
  }, [activeDocument]);

  function moveSearchResult(direction: -1 | 1) {
    const matches = activeDocument?.kind === "pdf" ? pdfSearchMatches : textMatches;
    if (!matches.length) return;
    const next = (searchMatchIndex + direction + matches.length) % matches.length;
    setSearchMatchIndex(next);
    if (activeDocument?.kind === "txt") {
      jumpToTextPage(matches[next].pageIndex);
    } else {
      setPageIndex(matches[next].pageIndex);
    }
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
    setFocusMode(false);
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
          ? textPosition?.paragraphIndex ??
            currentDocument.pages[currentPage]?.firstParagraphIndex ??
            0
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
  const pdfPageInputMax = pdfPageCount
    ? Math.min(pdfPageCount, MAX_PDF_PAGE_INDEX + 1)
    : Math.max(pageIndex + 1, 1);
  const pdfLastPageIndex = pdfPageCount
    ? Math.min(pdfPageCount - 1, MAX_PDF_PAGE_INDEX)
    : Math.max(pageIndex, 0);
  const lastPageIndex = activeDocument?.kind === "txt"
    ? Math.max(currentTextPageCount - 1, 0)
    : pdfLastPageIndex;
  const activeSearchMatches = activeDocument?.kind === "pdf" ? pdfSearchMatches : textMatches;
  const activeSearchBusy = activeDocument?.kind === "pdf" && pdfSearching;

  function adjustPdfZoom(delta: number) {
    setActiveDocument((current) => {
      if (current?.kind !== "pdf") return current;
      const pdfZoomPercent = Math.min(
        Math.max(current.preferences.pdfZoomPercent + delta, 50),
        300,
      );
      return { ...current, preferences: { ...current.preferences, pdfZoomPercent } };
    });
  }

  // The pdf.js viewer reports the total page count only after the document has
  // been loaded through the restricted reader URL. Clamp a bookmark-restored
  // page index that exceeds the real document size once the count is known.
  const handlePdfPageCount = useCallback((count: number) => {
    const safeCount = Math.max(count, 0);
    setPdfPageCount(safeCount);
    setPageIndex((page) => Math.min(Math.max(page, 0), Math.max(safeCount - 1, 0)));
  }, []);

  const handlePdfRenderFailed = useCallback(
    (reason: unknown) => setError(readerErrorMessage(reason, language)),
    [language],
  );
  const handlePdfRendered = useCallback(() => setError(""), []);
  const handlePdfSearchChanged = useCallback((matches: ReaderPdfSearchMatch[], searching: boolean) => {
    setPdfSearchMatches(matches);
    setPdfSearching(searching);
    setSearchMatchIndex((index) => Math.min(index, Math.max(matches.length - 1, 0)));
  }, []);
  const handlePdfVisiblePage = useCallback((nextPageIndex: number) => {
    setPageIndex(nextPageIndex);
  }, []);
  useEffect(() => {
    if (!activeDocument || settingsOpen) return;
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      const editing = Boolean(
        target?.closest("input, textarea, select, button, [contenteditable='true']"),
      );
      if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === "f") {
        event.preventDefault();
        setFocusMode(false);
        setSearchOpen(true);
        return;
      }
      if (editing) return;
      if (event.key === "Escape") {
        if (searchOpen) setSearchOpen(false);
        else if (tocOpen) setTocOpen(false);
        else if (focusMode) setFocusMode(false);
        else return;
        event.preventDefault();
        return;
      }
      if (event.key === "ArrowLeft" || event.key === "PageUp") {
        event.preventDefault();
        if (activeDocument.kind === "txt") jumpToTextPage(pageIndex - 1);
        else setPageIndex((page) => Math.max(page - 1, 0));
      } else if (event.key === "ArrowRight" || event.key === "PageDown" || event.key === " ") {
        event.preventDefault();
        if (activeDocument.kind === "txt") jumpToTextPage(pageIndex + 1);
        else setPageIndex((page) => Math.min(page + 1, lastPageIndex));
      } else if (event.key === "Home") {
        event.preventDefault();
        if (activeDocument.kind === "txt") jumpToTextPage(0);
        else setPageIndex(0);
      } else if (event.key === "End") {
        event.preventDefault();
        if (activeDocument.kind === "txt") jumpToTextPage(lastPageIndex);
        else setPageIndex(lastPageIndex);
      }
    };
    globalThis.addEventListener("keydown", onKeyDown);
    return () => globalThis.removeEventListener("keydown", onKeyDown);
  }, [activeDocument, focusMode, jumpToTextPage, lastPageIndex, pageIndex, searchOpen, settingsOpen, tocOpen]);

  const activePreferences = activeDocument?.preferences ?? DEFAULT_READER_PREFERENCES;
  const activePalette = readerPalette(activePreferences);
  const activeBackground = activePalette.background;
  const activeForeground = activePalette.foreground;
  const readerFontFamily = {
    serif: 'ui-serif, Georgia, "Noto Serif SC", "Songti SC", serif',
    sans: 'var(--font-sans)',
    mono: 'ui-monospace, "Cascadia Mono", Consolas, monospace',
  }[activePreferences.fontFamily];
  const readerStyle = {
    "--reader-font-size": `${activePreferences.fontSizePx}px`,
    "--reader-line-height": activePreferences.lineHeightMultiplier,
    "--reader-content-width": `${activePreferences.contentWidthPx}px`,
    "--reader-font-family": readerFontFamily,
    "--reader-text-align": activePreferences.textAlignment,
    "--reader-first-line-indent": `${activePreferences.firstLineIndentEm}em`,
    "--reader-letter-spacing": `${activePreferences.letterSpacingPx}px`,
    "--reader-page-padding": `${activePreferences.pagePaddingPx}px`,
    "--reader-background": activeBackground,
    "--reader-foreground": activeForeground,
  } as CSSProperties;
  const settingsPanel = (
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
  );

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
      className={`reader-page${activeDocument ? " reader-is-open" : ""}${focusMode ? " reader-focus-mode" : ""}`}
      eyebrow={activeDocument ? undefined : copy("本机私有 · 原文件只读", "Private on this PC · Original files read-only")}
      title={activeDocument?.book.title ?? copy("阅读", "Reader")}
      description={
        activeDocument
          ? undefined
          : copy("显式打开本机 TXT/PDF；书架路径、阅读设置和时长保持本机私有，无路径、无书名的进度可进入 v33 备份及可选云同步。", "Explicitly open local TXT/PDF files. Library paths, settings, and reading time remain private; path-free, title-free progress can enter v33 backups and optional cloud sync.")
      }
      actions={
        activeDocument ? undefined : (
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

      {settingsOpen && !activeDocument ? settingsPanel : null}

      {!activeDocument ? (
        settingsOpen ? null : <>
          <aside className="panel reader-boundary-note">
            <LibraryBig aria-hidden="true" size={23} />
            <div>
              <h2>{copy("本机书架", "Local shelf")}</h2>
              <p>{copy("只读已选择的原文件；移除书架记录不会删除原文件。", "Original files are read-only; removing a shelf entry never deletes the file.")}</p>
            </div>
            <strong>{formatReadingTime(library?.totalReadingMillis ?? "0", language)}</strong>
          </aside>
          {library?.books.length ? (
            <section className={`reader-library-grid layout-${library.preferences.libraryLayout}`} aria-label={copy("本机书库", "Local library")}>
              {library.books.map((book) => (
                <ReaderBookCard
                  key={book.id}
                  book={book}
                  language={language}
                  layout={library.preferences.libraryLayout}
                  showGridBookTitles={library.preferences.showGridBookTitles}
                  showProgressPercentage={library.preferences.showProgressPercentage}
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
                description={copy("选择 UTF-8 / UTF-16 / GB18030 TXT 或 PDF；PDF 会直接在应用内渲染。", "Choose a UTF-8 / UTF-16 / GB18030 TXT or PDF; PDFs render directly in the app.")}
                icon={BookOpen}
                action={<button className="button-primary" type="button" disabled={!!busy} onClick={() => void chooseBook()}><FilePlus2 aria-hidden="true" size={17} />{copy("打开文件", "Open file")}</button>}
              />
            </div>
          )}
        </>
      ) : (
        <section className="reader-document-shell" style={readerStyle} inert={settingsOpen ? true : undefined} aria-hidden={settingsOpen || undefined}>
          {searchOpen ? (
            <div className="panel reader-search-bar" role="search">
              <Search aria-hidden="true" size={18} />
              <label>
                <span className="sr-only">{copy("搜索整本书", "Search the whole book")}</span>
                <input autoFocus value={searchQuery} maxLength={MAX_READER_SEARCH_QUERY_CHARS} placeholder={activeDocument.kind === "pdf" ? copy("搜索 PDF 文字层", "Search PDF text") : copy("搜索整本 TXT", "Search the whole TXT")} onChange={(event) => setSearchQuery(event.target.value)} />
              </label>
              <output aria-live="polite">
                {activeSearchBusy
                  ? copy("搜索中…", "Searching…")
                  : searchQuery.trim()
                  ? copy(`${activeSearchMatches.length ? searchMatchIndex + 1 : 0} / ${activeSearchMatches.length}`, `${activeSearchMatches.length ? searchMatchIndex + 1 : 0} / ${activeSearchMatches.length}`)
                  : copy("输入关键词", "Enter a query")}
              </output>
              <button className="icon-button" type="button" disabled={!activeSearchMatches.length} onClick={() => moveSearchResult(-1)} aria-label={copy("上一个结果", "Previous result")}><ChevronLeft aria-hidden="true" size={18} /></button>
              <button className="icon-button" type="button" disabled={!activeSearchMatches.length} onClick={() => moveSearchResult(1)} aria-label={copy("下一个结果", "Next result")}><ChevronRight aria-hidden="true" size={18} /></button>
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
                        <button className={chapter.pageIndex === pageIndex ? "is-current" : undefined} type="button" onClick={() => jumpToTextPage(chapter.pageIndex)}>
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
                <div className="reader-toolbar-leading">
                  <button className="icon-button" type="button" disabled={!!busy || settingsOpen} onClick={() => void closeBook()} aria-label={copy("返回书架", "Back to shelf")} title={copy("返回书架", "Back to shelf")}><ArrowLeft aria-hidden="true" size={18} /></button>
                  <h1 title={activeDocument.book.title}>{activeDocument.book.title}</h1>
                  <button className={`icon-button${tocOpen ? " is-active" : ""}`} type="button" aria-pressed={tocOpen} onClick={() => { setFocusMode(false); setTocOpen((open) => !open); }} aria-label={tocOpen ? copy("收起目录", "Hide contents") : copy("显示目录", "Show contents")} title={copy("目录", "Contents")}><List aria-hidden="true" size={18} /></button>
                  <button className={`icon-button${searchOpen ? " is-active" : ""}`} type="button" aria-pressed={searchOpen} onClick={() => { setFocusMode(false); setSearchOpen((open) => !open); }} aria-label={copy("全文搜索", "Full-text search")} title={`${copy("全文搜索", "Full-text search")} · Ctrl+F`}><Search aria-hidden="true" size={18} /></button>
                </div>

                <div className="reader-toolbar-pager">
                  <button className="icon-button" type="button" disabled={pageIndex <= 0} onClick={() => (activeDocument.kind === "txt" ? jumpToTextPage(pageIndex - 1) : setPageIndex((page) => Math.max(page - 1, 0)))} aria-label={copy("上一页", "Previous page")} title={`${copy("上一页", "Previous page")} · ← / PageUp`}><ChevronLeft aria-hidden="true" size={19} /></button>
                  {activeDocument.kind === "txt" ? (
                    <>
                      <label className="reader-progress-range">
                        <span className="sr-only">{copy("阅读进度", "Reading progress")}</span>
                        <input type="range" min="1" max={Math.max(currentTextPageCount, 1)} value={pageIndex + 1} onChange={(event) => jumpToTextPage(Number(event.target.value) - 1)} />
                      </label>
                      <label className="reader-page-input">
                        <input aria-label={copy("页码", "Page number")} type="number" min="1" max={Math.max(currentTextPageCount, 1)} value={pageIndex + 1} onChange={(event) => jumpToTextPage(Math.min(Math.max(Number(event.target.value || 1) - 1, 0), Math.max(currentTextPageCount - 1, 0)))} />
                        <span>/ {currentTextPageCount} · {progressPercent}%</span>
                      </label>
                    </>
                  ) : (
                    <label className="reader-page-input reader-pdf-page-input">
                      <input aria-label={copy("保存页码", "Saved page")} type="number" min="1" max={pdfPageInputMax} value={pageIndex + 1} onChange={(event) => setPageIndex(Math.min(Math.max(Number(event.target.value || 1) - 1, 0), pdfLastPageIndex))} />
                      <span>/ {pdfPageCount ?? "…"}</span>
                    </label>
                  )}
                  <button className="icon-button" type="button" disabled={pageIndex >= lastPageIndex} onClick={() => (activeDocument.kind === "txt" ? jumpToTextPage(pageIndex + 1) : setPageIndex((page) => Math.min(page + 1, lastPageIndex)))} aria-label={copy("下一页", "Next page")} title={`${copy("下一页", "Next page")} · → / PageDown`}><ChevronRight aria-hidden="true" size={19} /></button>
                </div>

                <div className="reader-toolbar-trailing">
                  {activeDocument.kind === "txt" ? (
                    <button className="icon-button" type="button" onClick={() => void copyCurrentPage()} aria-label={copy("复制当前页", "Copy page")} title={copy("复制当前页", "Copy page")}><Clipboard aria-hidden="true" size={17} /></button>
                  ) : (
                    <>
                      <div className="reader-pdf-zoom-controls" aria-label={copy("PDF 临时缩放", "Temporary PDF zoom")}>
                        <button className="icon-button" type="button" disabled={activeDocument.preferences.pdfZoomPercent <= 50} onClick={() => adjustPdfZoom(-10)} aria-label={copy("缩小 PDF", "Zoom PDF out")}><Minus aria-hidden="true" size={17} /></button>
                        <output>{activeDocument.preferences.pdfZoomPercent}%</output>
                        <button className="icon-button" type="button" disabled={activeDocument.preferences.pdfZoomPercent >= 300} onClick={() => adjustPdfZoom(10)} aria-label={copy("放大 PDF", "Zoom PDF in")}><Plus aria-hidden="true" size={17} /></button>
                      </div>
                      <button className="icon-button" type="button" onClick={() => setPdfRotation((value) => (value + 90) % 360)} aria-label={copy("顺时针旋转 PDF", "Rotate PDF clockwise")} title={copy(`旋转 ${pdfRotation}°`, `Rotation ${pdfRotation}°`)}><RotateCw aria-hidden="true" size={17} /></button>
                    </>
                  )}
                  <button className="icon-button" type="button" disabled={!!busy || settingsOpen} onClick={openSettings} aria-label={copy("阅读设置", "Reader settings")} title={copy("阅读设置", "Reader settings")}><Settings2 aria-hidden="true" size={18} /></button>
                  <button className={`icon-button${focusMode ? " is-active" : ""}`} type="button" aria-pressed={focusMode} onClick={() => setFocusMode((enabled) => !enabled)} aria-label={focusMode ? copy("退出专注模式", "Exit focus mode") : copy("进入专注模式", "Enter focus mode")} title={focusMode ? copy("退出专注模式", "Exit focus mode") : copy("进入专注模式", "Enter focus mode")}>{focusMode ? <Minimize2 aria-hidden="true" size={18} /> : <Maximize2 aria-hidden="true" size={18} />}</button>
                </div>
              </div>

              {activeDocument.kind === "txt" ? (
                <div className={"reader-text-surface background-" + activeDocument.preferences.background}>
                  <TextScrollView
                    document={activeDocument}
                    matches={textMatches}
                    currentMatchIndex={searchMatchIndex}
                    metrics={{
                      fontSizePx: activeDocument.preferences.fontSizePx,
                      lineHeight: activeDocument.preferences.lineHeightMultiplier,
                      contentWidthPx: activeDocument.preferences.contentWidthPx,
                      paragraphSpacingPx: activeDocument.preferences.paragraphSpacingPx,
                      pagePaddingPx: activeDocument.preferences.pagePaddingPx,
                    }}
                    initialPageIndex={activeDocument.book.textPageIndex}
                    initialParagraphIndex={activeDocument.book.textParagraphIndex}
                    jumpToken={textJump.token}
                    jumpPageIndex={textJump.pageIndex}
                    onPositionChanged={(position) => {
                      setTextPosition(position);
                      setPageIndex(position.pageIndex);
                    }}
                    onInitialRestored={() => {
                      setTextPosition({
                        pageIndex: activeDocument.book.textPageIndex,
                        paragraphIndex: activeDocument.book.textParagraphIndex,
                        pageOffsetPercent: activeDocument.book.textPageOffsetPercent,
                      });
                    }}
                  />
                </div>
              ) : pdfBaseUrl ? (
                <div className="reader-pdf-surface">
                  <ReaderPdfViewer
                    key={activeDocument.book.id}
                    assetUrl={pdfBaseUrl}
                    pageIndex={pageIndex}
                    zoomPercent={activeDocument.preferences.pdfZoomPercent}
                    language={language}
                    background={activeBackground}
                    foreground={activeForeground}
                    colorMode={activeDocument.preferences.pdfColorMode}
                    scrollMode={activeDocument.preferences.pdfScrollMode}
                    pageGapPx={activeDocument.preferences.pdfPageGapPx}
                    pagePaddingPx={activeDocument.preferences.pagePaddingPx}
                    rotation={pdfRotation}
                    searchQuery={searchOpen ? searchQuery : ""}
                    showOutline={tocOpen}
                    onOutlineClose={() => setTocOpen(false)}
                    onPageCountChanged={handlePdfPageCount}
                    onVisiblePageChanged={handlePdfVisiblePage}
                    onSearchChanged={handlePdfSearchChanged}
                    onPageRendered={handlePdfRendered}
                    onRenderFailed={handlePdfRenderFailed}
                  />
                </div>
              ) : (
                <div className="panel"><ErrorState title={copy("无法显示 PDF", "PDF cannot be displayed")} description={copy("阅读数据版本不兼容，请返回书架后重新打开；应用不会把绝对路径交给前端。", "The reader data version is incompatible. Return to the shelf and reopen it; the app never gives the frontend an absolute path.")} /></div>
              )}
            </div>
          </div>
        </section>
      )}

      {settingsOpen && activeDocument ? (
        <div className="reader-settings-overlay" role="dialog" aria-modal="true" aria-labelledby="reader-settings-title">
          {settingsPanel}
        </div>
      ) : null}

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
