/**
 * 阅读 ReaderPage (/reader) — faithful web replica of the Android 阅读页面
 * (README_for_ai.md「阅读页面（Android）」):
 *
 * - 书架 grid fed by GET /api/reader/books: letter-cover tiles + title + ⋮ menu
 *   (重命名 PUT /api/reader/books/{id} {title} / 删除 DELETE with confirm),
 *   导入书籍 FAB accepting .txt/.pdf -> POST /api/reader/books multipart,
 *   empty-shelf 导入小说 shortcut.
 * - TXT: decoded via GET /api/reader/books/{id}/text, paginated client-side by
 *   measuring chars-per-page from the container size + font size (recomputed on
 *   resize), position restored from GET /api/reader/progress (fingerprint =
 *   book sha256 + type) at page + 5% in-page offset, saved debounced 600 ms and
 *   flushed on unmount via PUT /api/reader/progress. Center tap toggles the
 *   top/bottom bars; 目录 sidebar lists 第…章 regex chapters with jump list;
 *   settings popover shares the Android record-sync preference subset through
 *   GET/PUT /api/reader/preferences, with localStorage kept only as an offline
 *   cache and one-time migration source.
 * - PDF: pdfjs-dist continuous vertical scroll with lazy canvas rendering for
 *   pages near the viewport, zoom −/+ buttons + range slider (25–400 %, step 1),
 *   page indicator x/y, getTextContent() search jumping to the first hit page,
 *   getOutline() sidebar when available; same progress ledger (pdfPageIndex).
 * - Engagement heartbeat: visible seconds accumulate and POST
 *   /api/reader/engagement {bookId, seconds} every 60 s, flushed on unmount.
 *
 * Note: paper themes (羊皮纸/浅色/深色) and generated book covers are content
 * colors mirroring the Android reader palette, not app-chrome colors; all app
 * chrome consumes --dc-* CSS variable tokens only.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  BookOpen,
  ChevronDown,
  ChevronUp,
  FileText,
  Minus,
  MoreVertical,
  Plus,
  Search,
  Settings,
  X,
} from "lucide-react";
import { apiGet, apiSend, apiUpload } from "../../api/client";
import { tr } from "../../i18n/tr";
import {
  ConfirmDialog,
  EmptyState,
  ErrorText,
  Modal,
  PageTutorialOverlay,
  PopupMenu,
  Spinner,
  TopBar,
  useSnackbar,
} from "../../components/ui";

// Vite asset import (?url) typing comes from src/vite-env.d.ts (vite/client).
import pdfWorkerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";

// ---------------------------------------------------------------------------
// DTOs (web/backend/app/services/reader_service.py shapes)
// ---------------------------------------------------------------------------

export interface BookDto {
  id: string;
  fileName: string;
  bookType: "TXT" | "PDF";
  title: string;
  sizeBytes: number;
  fingerprint: string;
  addedAt: number;
}

/** Exact shape accepted by reading/v1/progress.json (validate_record bounds). */
interface ProgressRecord {
  fingerprint: string;
  type: "TXT" | "PDF";
  textPageIndex: number; // -1..49_999
  textParagraphIndex: number; // 0..249_999
  pdfPageIndex: number; // 0..19_999
  totalPages: number; // 0..50_000 (TXT) / 20_000 (PDF)
  updatedAt: number;
}

interface ProgressDoc {
  version: number;
  records: ProgressRecord[];
}

type ReaderBackground = "WHITE" | "PAPER" | "SEPIA" | "GREEN" | "NIGHT" | "CUSTOM";
type ReaderChapterDetectionMode = "SMART" | "CUSTOM" | "SMART_AND_CUSTOM";

interface ReaderPrefs {
  background: ReaderBackground;
  customBackgroundArgb: number;
  fontSizeSp: number; // 12–38 (Android sp range)
  lineHeightMultiplier: number; // 1.0–2.4
  paragraphSpacingDp: number; // 0–36
  showProgressPercentage: boolean;
  chapterDetectionMode: ReaderChapterDetectionMode;
  /** Device-local, matching Android ReaderPreferences (never record-synced). */
  customChapterRegex: string;
  /** Device-local maximum normalized heading length (20–240). */
  chapterHeadingMaxChars: number;
}

type ReaderSyncedPrefs = Pick<
  ReaderPrefs,
  | "background"
  | "customBackgroundArgb"
  | "fontSizeSp"
  | "lineHeightMultiplier"
  | "paragraphSpacingDp"
  | "showProgressPercentage"
  | "chapterDetectionMode"
>;

interface ReaderPrefsResponse extends ReaderSyncedPrefs {
  stored: boolean;
}

const PREFS_KEY = "dc-reader-prefs";

const DEFAULT_PREFS: ReaderPrefs = {
  background: "PAPER",
  customBackgroundArgb: -724762, // Android 0xFFF4F0E6.toInt()
  fontSizeSp: 19,
  lineHeightMultiplier: 1.6,
  paragraphSpacingDp: 10,
  showProgressPercentage: false,
  chapterDetectionMode: "SMART_AND_CUSTOM",
  customChapterRegex: "",
  chapterHeadingMaxChars: 160,
};

/** Content palettes mirroring the Android reader paper themes (not app chrome). */
const READER_THEMES: { key: ReaderBackground; zh: string; en: string; bg: string; fg: string }[] = [
  { key: "WHITE", zh: "白色", en: "White", bg: "#ffffff", fg: "#202124" },
  { key: "PAPER", zh: "纸张", en: "Paper", bg: "#f4f0e6", fg: "#332e28" },
  { key: "SEPIA", zh: "羊皮纸", en: "Sepia", bg: "#e8d6b0", fg: "#3b2c1e" },
  { key: "GREEN", zh: "护眼绿", en: "Green", bg: "#dde8d7", fg: "#203126" },
  { key: "NIGHT", zh: "夜间", en: "Night", bg: "#171a1c", fg: "#e2e0da" },
  { key: "CUSTOM", zh: "自定义", en: "Custom", bg: "#f4f0e6", fg: "#332e28" },
];

const SMART_CHAPTER_PATTERNS = [
  /^(?:正文\s*)?[【[〈《（(]?[☆★◎◇◆•·\s]*第\s*[0-9０-９零〇○一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟]+\s*[章节卷回部篇集幕]\s*[】\]〉》）)]?(?:\s+|[:：、.．_\-—]?)[^\n]*$/,
  /^(?:chapter|part|book|section|episode)\s*(?:[0-9０-９]+|[ivxlcdm]+|(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)(?:[ -](?:one|two|three|four|five|six|seven|eight|nine))?)(?:\s*[:：.\-—]?\s*.*)?$/i,
  /^(?:[0-9０-９]{1,5}(?:[.．、]|\s+-\s+)|[一二三四五六七八九十百千万]+、)\s*\S.*$/,
  /^(?:[【[])?(?:序章|序言|前言|楔子|引子|终章|尾声|后记|番外(?:篇)?|上卷|中卷|下卷|prologue|epilogue|preface|introduction|afterword)(?:[】\]])?(?:\s*[:：.、\-]?\s*.*)?$/i,
  /^(?:卷|部|篇|集)\s*[0-9０-９零〇○一二三四五六七八九十百千万两]+(?:\s*[:：.、\-]?\s*.*)?$/,
  /^#{1,6}\s+\S.*$/,
  /^[【[](?:第[0-9０-９零〇○一二三四五六七八九十百千万两]+[章节卷回部篇集幕]|chapter\s+[^】\]]+)[】\]](?:\s*.*)?$/i,
];

const PROGRESS_URL = "/api/reader/progress";
const ENGAGEMENT_URL = "/api/reader/engagement";
const PREFERENCES_URL = "/api/reader/preferences";
const MAX_CUSTOM_CHAPTER_REGEX_CHARS = 1024;
const MIN_CHAPTER_HEADING_CHARS = 20;
const MAX_CHAPTER_HEADING_CHARS = 240;

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function signedArgbToCss(argb: number): string {
  return `#${(argb >>> 0).toString(16).padStart(8, "0").slice(2)}`;
}

function cssToSignedArgb(css: string): number {
  const rgb = Number.parseInt(css.replace(/^#/, ""), 16) & 0x00ff_ffff;
  return (0xff00_0000 | rgb) | 0;
}

function automaticTextColor(background: string): string {
  const rgb = Number.parseInt(background.slice(1), 16);
  const channel = (shift: number) => {
    const value = ((rgb >> shift) & 0xff) / 255;
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  };
  const luminance = 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0);
  return luminance > 0.5 ? "#181818" : "#f4f4f4";
}

function resolveReaderTheme(prefs: ReaderPrefs): { bg: string; fg: string; faint: string } {
  const preset = READER_THEMES.find((item) => item.key === prefs.background) ?? READER_THEMES[1];
  const bg = prefs.background === "CUSTOM" ? signedArgbToCss(prefs.customBackgroundArgb) : preset.bg;
  const fg = prefs.background === "CUSTOM" ? automaticTextColor(bg) : preset.fg;
  return { bg, fg, faint: `color-mix(in srgb, ${fg} 55%, transparent)` };
}

function normalizePrefs(parsed: Partial<ReaderPrefs> & { fontSize?: unknown; lineHeight?: unknown; theme?: unknown }): ReaderPrefs {
  const finiteOr = (raw: unknown, fallback: number): number => {
    if (raw === null || raw === undefined || raw === "") return fallback;
    const value = Number(raw);
    return Number.isFinite(value) ? value : fallback;
  };
  const legacyBackground: Record<string, ReaderBackground> = {
    parchment: "PAPER",
    light: "WHITE",
    dark: "NIGHT",
  };
  const rawBackground = parsed.background ?? legacyBackground[String(parsed.theme)] ?? DEFAULT_PREFS.background;
  const rawChapterMode = parsed.chapterDetectionMode ?? DEFAULT_PREFS.chapterDetectionMode;
  const customArgb = Number(parsed.customBackgroundArgb);
  const customChapterRegex = typeof parsed.customChapterRegex === "string"
    ? parsed.customChapterRegex.trim().slice(0, MAX_CUSTOM_CHAPTER_REGEX_CHARS)
    : DEFAULT_PREFS.customChapterRegex;
  return {
    background: READER_THEMES.some((item) => item.key === rawBackground)
      ? (rawBackground as ReaderBackground)
      : DEFAULT_PREFS.background,
    customBackgroundArgb: Number.isInteger(customArgb) && customArgb >= -(2 ** 31) && customArgb <= 2 ** 31 - 1
      ? cssToSignedArgb(signedArgbToCss(customArgb))
      : DEFAULT_PREFS.customBackgroundArgb,
    fontSizeSp: clamp(finiteOr(parsed.fontSizeSp ?? parsed.fontSize, DEFAULT_PREFS.fontSizeSp), 12, 38),
    lineHeightMultiplier: clamp(
      finiteOr(parsed.lineHeightMultiplier ?? parsed.lineHeight, DEFAULT_PREFS.lineHeightMultiplier),
      1,
      2.4
    ),
    paragraphSpacingDp: clamp(finiteOr(parsed.paragraphSpacingDp, DEFAULT_PREFS.paragraphSpacingDp), 0, 36),
    showProgressPercentage:
      typeof parsed.showProgressPercentage === "boolean"
        ? parsed.showProgressPercentage
        : DEFAULT_PREFS.showProgressPercentage,
    chapterDetectionMode: ["SMART", "CUSTOM", "SMART_AND_CUSTOM"].includes(String(rawChapterMode))
      ? (rawChapterMode as ReaderChapterDetectionMode)
      : DEFAULT_PREFS.chapterDetectionMode,
    customChapterRegex,
    chapterHeadingMaxChars: Math.round(clamp(
      finiteOr(parsed.chapterHeadingMaxChars, DEFAULT_PREFS.chapterHeadingMaxChars),
      MIN_CHAPTER_HEADING_CHARS,
      MAX_CHAPTER_HEADING_CHARS
    )),
  };
}

/** Explicit record-sync projection: device-local reader fields never cross the API. */
function syncedPrefs(prefs: ReaderPrefs): ReaderSyncedPrefs {
  return {
    background: prefs.background,
    customBackgroundArgb: prefs.customBackgroundArgb,
    fontSizeSp: prefs.fontSizeSp,
    lineHeightMultiplier: prefs.lineHeightMultiplier,
    paragraphSpacingDp: prefs.paragraphSpacingDp,
    showProgressPercentage: prefs.showProgressPercentage,
    chapterDetectionMode: prefs.chapterDetectionMode,
  };
}

function hasCachedPrefs(): boolean {
  try {
    return localStorage.getItem(PREFS_KEY) !== null;
  } catch {
    return false;
  }
}

function loadPrefs(): ReaderPrefs {
  try {
    const raw = localStorage.getItem(PREFS_KEY);
    if (!raw) return { ...DEFAULT_PREFS };
    return normalizePrefs(JSON.parse(raw) as Partial<ReaderPrefs>);
  } catch {
    return { ...DEFAULT_PREFS };
  }
}

function savePrefs(prefs: ReaderPrefs): void {
  try {
    localStorage.setItem(PREFS_KEY, JSON.stringify(prefs));
  } catch {
    /* private mode — preferences simply stay ephemeral */
  }
}

function fmtSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} B`;
}

function fmtDuration(seconds: number): string {
  const s = Math.max(0, Math.round(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (h > 0) return tr(`${h} 小时 ${m} 分钟`, `${h} h ${m} min`);
  if (m > 0) return tr(`${m} 分钟`, `${m} min`);
  return tr(`${s} 秒`, `${s} sec`);
}

function makeRecord(base: Omit<ProgressRecord, "updatedAt">): ProgressRecord {
  return { ...base, updatedAt: Date.now() };
}

/** Fire-and-forget PUT/POST that survives page unload. */
function flushJson(method: "PUT" | "POST", url: string, body: unknown): void {
  try {
    void fetch(url, {
      method,
      keepalive: true,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }).catch(() => {});
  } catch {
    /* best effort only */
  }
}

/** Average glyph advance for the reader font, blending CJK + latin probes. */
function measureAvgAdvance(fontSize: number): number {
  try {
    const canvas = document.createElement("canvas");
    const ctx = canvas.getContext("2d");
    if (!ctx) return fontSize * 0.72;
    ctx.font = `${fontSize}px "Noto Serif SC", Georgia, "Times New Roman", serif`;
    const probe = "永国图书阅读测试中文EnglishReader123";
    return Math.max(4, ctx.measureText(probe).width / probe.length);
  } catch {
    return fontSize * 0.72;
  }
}

/** Split text into page start offsets with soft line/space breaks. */
function slicePageStarts(text: string, charsPerPage: number): number[] {
  const starts: number[] = [];
  if (!text) return [0, 0];
  const step = Math.max(32, Math.floor(charsPerPage));
  let start = 0;
  while (start < text.length && starts.length < 20_000) {
    let end = Math.min(text.length, start + step);
    if (end < text.length) {
      const zoneFrom = Math.max(start + Math.floor(step * 0.75), start + 1);
      const zone = text.slice(zoneFrom, end);
      const nl = zone.lastIndexOf("\n");
      if (nl >= 0) {
        end = zoneFrom + nl + 1;
      } else {
        const sp = zone.lastIndexOf(" ");
        if (sp > 0) end = zoneFrom + sp + 1;
      }
      if (end <= start) end = start + step;
    }
    starts.push(start);
    start = end;
  }
  starts.push(text.length);
  return starts;
}

interface TxtChapter {
  title: string;
  charIndex: number;
}

function compileCustomChapterRegex(value: string): RegExp | null {
  const normalized = value.trim();
  if (!normalized || normalized.length > MAX_CUSTOM_CHAPTER_REGEX_CHARS) return null;
  try {
    return new RegExp(normalized);
  } catch {
    return null;
  }
}

function isCustomChapterRegexValid(value: string): boolean {
  const normalized = value.trim();
  return !normalized || (
    normalized.length <= MAX_CUSTOM_CHAPTER_REGEX_CHARS && compileCustomChapterRegex(normalized) !== null
  );
}

function customRegexMatchesWholeLine(pattern: RegExp, value: string): boolean {
  pattern.lastIndex = 0;
  const match = pattern.exec(value);
  return match !== null && match.index === 0 && match[0].length === value.length;
}

const TOC_ENTRY_PATTERN = /(?:\.{3,}|…{2,}|·{3,}|_{3,})\s*(?:[0-9０-９]+|[ivxlcdm]+)\s*$/i;

function scanChapters(text: string, prefs: ReaderPrefs): TxtChapter[] {
  const chapters: TxtChapter[] = [];
  const smartEnabled = prefs.chapterDetectionMode !== "CUSTOM";
  const customEnabled = prefs.chapterDetectionMode !== "SMART";
  const customPattern = customEnabled ? compileCustomChapterRegex(prefs.customChapterRegex) : null;
  let offset = 0;
  while (offset <= text.length) {
    if (chapters.length >= 20_000) break;
    const newline = text.indexOf("\n", offset);
    const lineEnd = newline < 0 ? text.length : newline;
    const rawLine = text.slice(offset, lineEnd).replace(/\r$/, "");
    const lineOffset = offset;
    offset = newline < 0 ? text.length + 1 : newline + 1;
    const line = rawLine;
    const trimmed = line.replace(/[\u200b-\u200f\u202a-\u202e\u2060\ufeff]/g, "").trim().replace(/[\t\u00a0\u3000 ]+/g, " ");
    const eligible = trimmed.length > 0 &&
      trimmed.length <= prefs.chapterHeadingMaxChars &&
      !TOC_ENTRY_PATTERN.test(trimmed);
    const smartMatch = eligible && smartEnabled && SMART_CHAPTER_PATTERNS.some((pattern) => pattern.test(trimmed));
    const customMatch = eligible && customPattern !== null && customRegexMatchesWholeLine(customPattern, trimmed);
    if (smartMatch || customMatch) {
      chapters.push({ title: trimmed.slice(0, MAX_CHAPTER_HEADING_CHARS), charIndex: lineOffset });
    }
  }
  return chapters;
}

function highlightSlice(slice: string, query: string): React.ReactNode[] {
  if (!query) return [slice];
  const nodes: React.ReactNode[] = [];
  const lower = slice.toLowerCase();
  const q = query.toLowerCase();
  let i = 0;
  let key = 0;
  while (i < slice.length) {
    const j = lower.indexOf(q, i);
    if (j < 0) {
      nodes.push(slice.slice(i));
      break;
    }
    if (j > i) nodes.push(slice.slice(i, j));
    nodes.push(
      <mark
        key={key++}
        style={{ background: "color-mix(in srgb, var(--dc-primary) 34%, transparent)", color: "inherit", borderRadius: 2 }}
      >
        {slice.slice(j, j + q.length)}
      </mark>
    );
    i = j + q.length;
  }
  return nodes;
}

// pdf.js accessed through local structural types so minor version drift in
// pdfjs-dist typings cannot break the build.
interface PdfViewportLike {
  width: number;
  height: number;
}
interface PdfRenderTaskLike {
  promise: Promise<void>;
  cancel(): void;
}
interface PdfPageLike {
  getViewport(options: { scale: number }): PdfViewportLike;
  render(params: { canvasContext: CanvasRenderingContext2D; viewport: PdfViewportLike }): PdfRenderTaskLike;
  getTextContent(): Promise<{ items: Array<{ str?: string }> }>;
}
interface PdfOutlineItemLike {
  title?: string;
  dest?: unknown;
  items?: unknown[];
}
interface PdfDocLike {
  numPages: number;
  getPage(pageNumber: number): Promise<PdfPageLike>;
  getOutline(): Promise<PdfOutlineItemLike[] | null>;
  getDestination(dest: string): Promise<unknown>;
  getPageIndex(ref: unknown): Promise<number>;
  destroy(): Promise<void>;
}

let pdfjsLoader: Promise<typeof import("pdfjs-dist") | null> | null = null;
function loadPdfjs(): Promise<typeof import("pdfjs-dist") | null> {
  if (!pdfjsLoader) {
    pdfjsLoader = (async () => {
      try {
        const lib = await import("pdfjs-dist");
        lib.GlobalWorkerOptions.workerSrc = pdfWorkerUrl;
        return lib;
      } catch (err) {
        console.error("pdfjs-dist unavailable, falling back to iframe view", err);
        return null;
      }
    })();
  }
  return pdfjsLoader;
}

// ---------------------------------------------------------------------------
// Shelf page
// ---------------------------------------------------------------------------

const COVER_BACKGROUNDS = [
  "linear-gradient(140deg, color-mix(in srgb, var(--dc-primary) 30%, var(--dc-surface-container-high)), color-mix(in srgb, var(--dc-primary) 10%, var(--dc-surface)))",
  "linear-gradient(140deg, color-mix(in srgb, var(--dc-secondary-container) 55%, var(--dc-surface-container-high)), color-mix(in srgb, var(--dc-secondary-container) 20%, var(--dc-surface)))",
  "linear-gradient(140deg, color-mix(in srgb, var(--dc-error-container) 55%, var(--dc-surface-container-high)), color-mix(in srgb, var(--dc-error-container) 18%, var(--dc-surface)))",
  "linear-gradient(140deg, var(--dc-surface-variant), color-mix(in srgb, var(--dc-surface-variant) 55%, var(--dc-surface)))",
  "linear-gradient(140deg, color-mix(in srgb, var(--dc-primary) 14%, var(--dc-inverse-surface)), color-mix(in srgb, var(--dc-inverse-surface) 80%, var(--dc-surface)))",
];

function BookTile(props: {
  book: BookDto;
  index: number;
  showProgress: boolean;
  progressPercent: number;
  onOpen: () => void;
  onRename: () => void;
  onDelete: () => void;
}) {
  const { book } = props;
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null);
  const letter = (book.title.trim()[0] ?? "?").toUpperCase();
  return (
    <div className="dc-card" style={{ padding: 10, display: "flex", flexDirection: "column", gap: 8 }}>
      <button
        onClick={props.onOpen}
        aria-label={book.title}
        style={{
          position: "relative",
          border: "none",
          cursor: "pointer",
          borderRadius: "calc(var(--dc-radius) * 0.75)",
          background: COVER_BACKGROUNDS[props.index % COVER_BACKGROUNDS.length],
          aspectRatio: "3 / 4",
          width: "100%",
          overflow: "hidden",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
        }}
      >
        <span
          style={{
            fontSize: 46,
            fontWeight: 700,
            fontFamily: '"Noto Serif SC", Georgia, serif',
            color: "var(--dc-on-surface)",
            opacity: 0.82,
          }}
        >
          {letter}
        </span>
        <span
          style={{
            position: "absolute",
            left: 8,
            right: 8,
            bottom: 8,
            fontSize: 12,
            lineHeight: 1.35,
            maxHeight: "3.2em",
            overflow: "hidden",
            textAlign: "center",
            color: "var(--dc-on-surface)",
            opacity: 0.92,
            display: "-webkit-box",
            WebkitLineClamp: 3,
            WebkitBoxOrient: "vertical",
          }}
        >
          {book.title}
        </span>
      </button>
      <div className="dc-row" style={{ alignItems: "flex-start", gap: 4 }}>
        <div className="dc-grow" style={{ minWidth: 0 }}>
          <div
            style={{
              fontSize: "0.95em",
              fontWeight: 600,
              overflow: "hidden",
              textOverflow: "ellipsis",
              display: "-webkit-box",
              WebkitLineClamp: 2,
              WebkitBoxOrient: "vertical",
            }}
          >
            {book.title}
          </div>
          <div className="dc-muted" style={{ fontSize: "0.78em", marginTop: 2 }}>
            {book.bookType === "PDF" ? tr("PDF 文档", "PDF document") : tr("TXT 文档", "TXT document")}
            {" · "}
            {fmtSize(book.sizeBytes)}
            {props.showProgress && (
              <>
                {" · "}
                {tr("进度", "Progress")} {props.progressPercent}%
              </>
            )}
          </div>
        </div>
        <button
          className="dc-icon-btn"
          aria-label={tr("更多操作", "More actions")}
          onClick={(e) => {
            e.stopPropagation();
            setMenu({ x: e.clientX, y: e.clientY });
          }}
        >
          <MoreVertical size={18} />
        </button>
      </div>
      <PopupMenu
        open={menu !== null}
        onClose={() => setMenu(null)}
        x={menu?.x ?? 0}
        y={menu?.y ?? 0}
        items={[
          { label: tr("重命名", "Rename"), onClick: props.onRename },
          { label: tr("删除", "Delete"), danger: true, onClick: props.onDelete },
        ]}
      />
    </div>
  );
}

export default function ReaderPage() {
  const [books, setBooks] = useState<BookDto[] | null>(null);
  const [shelfPrefs, setShelfPrefs] = useState<ReaderPrefs>(loadPrefs);
  const [shelfProgress, setShelfProgress] = useState<ProgressRecord[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [openBook, setOpenBook] = useState<BookDto | null>(null);
  const [renaming, setRenaming] = useState<BookDto | null>(null);
  const [renameDraft, setRenameDraft] = useState("");
  const [deleting, setDeleting] = useState<BookDto | null>(null);
  const [busy, setBusy] = useState(false);
  const [snack, showSnack] = useSnackbar();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const refresh = useCallback(async () => {
    try {
      setBooks(await apiGet<BookDto[]>("/api/reader/books"));
      setError(null);
    } catch (err) {
      setError(err);
      setBooks([]);
    }
  }, []);

  const refreshShelfMeta = useCallback(async () => {
    const cached = loadPrefs();
    const [preferencesResult, progressResult] = await Promise.allSettled([
      apiGet<ReaderPrefsResponse>(PREFERENCES_URL),
      apiGet<ProgressDoc>(PROGRESS_URL),
    ]);
    if (preferencesResult.status === "fulfilled") {
      // Remote owns only the syncable subset; keep this browser's regex and
      // heading-length fields from local storage.
      const merged = normalizePrefs({ ...cached, ...preferencesResult.value });
      savePrefs(merged);
      setShelfPrefs(merged);
    } else {
      setShelfPrefs(cached);
    }
    if (progressResult.status === "fulfilled") {
      setShelfProgress(progressResult.value.records);
    }
  }, []);

  const progressPercentFor = useCallback((book: BookDto): number => {
    const record = shelfProgress.find(
      (item) => item.fingerprint === book.fingerprint && item.type === book.bookType
    );
    if (!record || record.totalPages <= 1) return 0;
    const page = record.type === "PDF" ? record.pdfPageIndex : record.textPageIndex;
    return Math.round(clamp(page, 0, record.totalPages - 1) / (record.totalPages - 1) * 100);
  }, [shelfProgress]);

  useEffect(() => {
    void refresh();
    void refreshShelfMeta();
  }, [refresh, refreshShelfMeta]);

  const importFiles = useCallback(
    async (files: FileList | null) => {
      if (!files || files.length === 0) return;
      setBusy(true);
      try {
        for (const file of Array.from(files)) {
          await apiUpload<BookDto>("/api/reader/books", file);
        }
        await refresh();
      } catch (err) {
        showSnack(err instanceof Error ? err.message : String(err));
      } finally {
        setBusy(false);
        if (fileInputRef.current) fileInputRef.current.value = "";
      }
    },
    [refresh, showSnack]
  );

  const submitRename = useCallback(async () => {
    if (!renaming) return;
    const title = renameDraft.trim();
    if (!title) return;
    try {
      await apiSend<BookDto>(`/api/reader/books/${renaming.id}`, "PUT", { title });
      setRenaming(null);
      await refresh();
    } catch (err) {
      showSnack(err instanceof Error ? err.message : String(err));
    }
  }, [renaming, renameDraft, refresh, showSnack]);

  const submitDelete = useCallback(async () => {
    if (!deleting) return;
    try {
      await apiSend(`/api/reader/books/${deleting.id}`, "DELETE");
      setDeleting(null);
      await refresh();
    } catch (err) {
      showSnack(err instanceof Error ? err.message : String(err));
      setDeleting(null);
    }
  }, [deleting, refresh, showSnack]);

  return (
    <div className="dc-col" style={{ gap: "var(--dc-spacing)" }}>
      <TopBar
        title={tr("书架", "Bookshelf")}
        subtitle={books ? tr(`${books.length} 本书`, `${books.length} books`) : undefined}
        actions={
          <>
            <input
              ref={fileInputRef}
              type="file"
              accept=".txt,.pdf,application/pdf,text/plain"
              multiple
              style={{ display: "none" }}
              onChange={(e) => void importFiles(e.target.files)}
            />
            <button
              className="dc-icon-btn"
              aria-label={tr("导入书籍", "Import books")}
              disabled={busy}
              onClick={() => fileInputRef.current?.click()}
            >
              <Plus size={20} />
            </button>
          </>
        }
      />
      <ErrorText error={error} />
      {books === null ? (
        <Spinner />
      ) : books.length === 0 ? (
        <EmptyState
          icon={<BookOpen size={44} strokeWidth={1.4} />}
          title={tr("书架还是空的", "Your shelf is empty")}
          hint={
            <button className="dc-btn dc-btn-tonal" disabled={busy} onClick={() => fileInputRef.current?.click()}>
              {tr("导入小说", "Import a novel")}
            </button>
          }
        />
      ) : (
        <div
          style={{
            display: "grid",
            gridTemplateColumns: "repeat(auto-fill, minmax(150px, 1fr))",
            gap: "var(--dc-spacing)",
          }}
        >
          {books.map((book, i) => (
            <BookTile
              key={book.id}
              book={book}
              index={i}
              showProgress={shelfPrefs.showProgressPercentage}
              progressPercent={progressPercentFor(book)}
              onOpen={() => setOpenBook(book)}
              onRename={() => {
                setRenaming(book);
                setRenameDraft(book.title);
              }}
              onDelete={() => setDeleting(book)}
            />
          ))}
        </div>
      )}

      <button
        className="dc-fab"
        aria-label={tr("导入书籍", "Import books")}
        disabled={busy}
        onClick={() => fileInputRef.current?.click()}
      >
        <Plus size={22} />
        <span>{tr("导入书籍", "Import books")}</span>
      </button>

      <Modal open={renaming !== null} onClose={() => setRenaming(null)} title={tr("重命名", "Rename")}>
        <input
          className="dc-input"
          value={renameDraft}
          autoFocus
          maxLength={240}
          aria-label={tr("书名", "Book title")}
          onChange={(e) => setRenameDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") void submitRename();
          }}
        />
        <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 14 }}>
          <button className="dc-btn" onClick={() => setRenaming(null)}>
            {tr("取消", "Cancel")}
          </button>
          <button className="dc-btn dc-btn-filled" disabled={!renameDraft.trim()} onClick={() => void submitRename()}>
            {tr("保存", "Save")}
          </button>
        </div>
      </Modal>

      <ConfirmDialog
        open={deleting !== null}
        danger
        title={tr("删除书籍？", "Delete this book?")}
        message={tr(
          "只从书架移除这条记录，不删除原文件；已累计的阅读时长仍会保留。",
          "This removes the shelf record only; the original file stays untouched and accumulated reading time is kept."
        )}
        confirmLabel={tr("删除", "Delete")}
        onCancel={() => setDeleting(null)}
        onConfirm={() => void submitDelete()}
      />

      {snack && (
        <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: "var(--dc-inverse-surface)", color: "var(--dc-inverse-on-surface)", padding: "10px 18px", borderRadius: 10, zIndex: 400 }}>
          {snack}
        </div>
      )}

      {openBook && (
        <ReaderView
          key={openBook.id}
          book={openBook}
          onClose={() => {
            setOpenBook(null);
            void refreshShelfMeta();
          }}
        />
      )}

      <PageTutorialOverlay
        pageKey="reader"
        title={tr("阅读", "Reader")}
        lines={[tr("导入 TXT/PDF 后自动保存阅读进度与累计时长。", "After importing a TXT/PDF, reading position and accumulated time are saved automatically.")]}
      />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Reading view (TXT + PDF)
// ---------------------------------------------------------------------------

type SearchState = {
  open: boolean;
  query: string;
  hits: number[]; // TXT: char offsets · PDF: 0-based page indexes
  current: number;
  scanning: boolean;
};

const INITIAL_SEARCH: SearchState = { open: false, query: "", hits: [], current: -1, scanning: false };

function ReaderView(props: { book: BookDto; onClose: () => void }) {
  const { book } = props;
  const isTxt = book.bookType === "TXT";
  const contentUrl = `/api/reader/books/${book.id}/content`;

  // ----- shared state -----
  const [prefs, setPrefs] = useState<ReaderPrefs>(loadPrefs);
  const [prefsReady, setPrefsReady] = useState(false);
  const prefsSaveTimer = useRef<number | null>(null);
  const prefsLatest = useRef(prefs);
  const prefsReadyRef = useRef(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settingsDraft, setSettingsDraft] = useState<ReaderPrefs | null>(null);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [search, setSearch] = useState<SearchState>(INITIAL_SEARCH);
  const [chromeVisible, setChromeVisible] = useState(true);
  const [phase, setPhase] = useState<"loading" | "ready" | "error">("loading");
  const [loadError, setLoadError] = useState<unknown>(null);
  const [currentPage, setCurrentPage] = useState(0); // TXT: logical page · PDF: 0-based
  const [totalPages, setTotalPages] = useState<number | null>(null);
  const [readingTime, setReadingTime] = useState<number | null>(null);

  const theme = resolveReaderTheme(prefs);

  useEffect(() => {
    prefsLatest.current = prefs;
    savePrefs(prefs);
    if (!prefsReady) return;
    if (prefsSaveTimer.current !== null) window.clearTimeout(prefsSaveTimer.current);
    prefsSaveTimer.current = window.setTimeout(() => {
      prefsSaveTimer.current = null;
      void apiSend<ReaderPrefsResponse>(PREFERENCES_URL, "PUT", syncedPrefs(prefs)).catch(() => {});
    }, 450);
    return () => {
      if (prefsSaveTimer.current !== null) window.clearTimeout(prefsSaveTimer.current);
    };
  }, [prefs, prefsReady]);

  useEffect(() => {
    let cancelled = false;
    const cached = loadPrefs();
    const hadCache = hasCachedPrefs();
    void (async () => {
      try {
        const remote = await apiGet<ReaderPrefsResponse>(PREFERENCES_URL);
        let resolved = normalizePrefs({ ...cached, ...remote });
        if (!remote.stored && hadCache) {
          const migrated = await apiSend<ReaderPrefsResponse>(PREFERENCES_URL, "PUT", syncedPrefs(cached));
          resolved = normalizePrefs({ ...cached, ...migrated });
        }
        if (!cancelled) setPrefs(resolved);
      } catch {
        // Offline/backend failure: the local cache remains a usable fallback.
      } finally {
        if (!cancelled) {
          prefsReadyRef.current = true;
          setPrefsReady(true);
        }
      }
    })();
    return () => {
      cancelled = true;
      if (prefsSaveTimer.current !== null) window.clearTimeout(prefsSaveTimer.current);
      if (prefsReadyRef.current) flushJson("PUT", PREFERENCES_URL, syncedPrefs(prefsLatest.current));
    };
    // Load once for this reader instance; later local edits flow through prefs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ----- progress + engagement plumbing (latest values kept in refs for flush) -----
  const progressFetched = useRef(false);
  const restoredRef = useRef(false);
  const pendingPos = useRef<{ page: number; frac: number }>({ page: 0, frac: 0 });
  const saveTimer = useRef<number | null>(null);
  const engagedSeconds = useRef(0);
  const buildRecordRef = useRef<(() => ProgressRecord) | null>(null);
  const aliveRef = useRef(true);

  const putProgress = useCallback(
    (record: ProgressRecord) => {
      apiSend(PROGRESS_URL, "PUT", record).catch(() => {});
    },
    []
  );

  const scheduleProgressSave = useCallback(() => {
    if (saveTimer.current !== null) window.clearTimeout(saveTimer.current);
    saveTimer.current = window.setTimeout(() => {
      saveTimer.current = null;
      const build = buildRecordRef.current;
      if (build) putProgress(build());
    }, 600);
  }, [putProgress]);

  const jumpToPage = useRef<(page: number, frac: number) => void>(() => {});

  // Load the fingerprint ledger once and remember whether this book has a match.
  const matchedProgress = useRef<ProgressRecord | null>(null);

  // ----- engagement heartbeat -----
  useEffect(() => {
    const postSeconds = (seconds: number) => {
      if (seconds <= 0) return;
      apiSend(ENGAGEMENT_URL, "POST", { bookId: book.id, seconds }).catch(() => {});
    };
    const interval = window.setInterval(() => {
      if (document.visibilityState !== "visible") return;
      engagedSeconds.current += 1;
      if (engagedSeconds.current >= 60) {
        postSeconds(engagedSeconds.current);
        engagedSeconds.current = 0;
      }
    }, 1000);
    return () => {
      window.clearInterval(interval);
      postSeconds(engagedSeconds.current);
      engagedSeconds.current = 0;
    };
  }, [book.id]);

  // Flush progress + remaining engagement on unmount / pagehide.
  useEffect(() => {
    aliveRef.current = true;
    const cleanup = () => {
      if (!aliveRef.current) return;
      aliveRef.current = false;
      if (saveTimer.current !== null) window.clearTimeout(saveTimer.current);
      const build = buildRecordRef.current;
      if (build) flushJson("PUT", PROGRESS_URL, build());
      if (engagedSeconds.current > 0) {
        flushJson("POST", ENGAGEMENT_URL, { bookId: book.id, seconds: engagedSeconds.current });
        engagedSeconds.current = 0;
      }
    };
    const onPageHide = () => cleanup();
    window.addEventListener("pagehide", onPageHide);
    return () => {
      window.removeEventListener("pagehide", onPageHide);
      cleanup();
    };
  }, [book.id]);

  // ----- TXT state -----
  const [txtText, setTxtText] = useState<string | null>(null);
  const [viewportSize, setViewportSize] = useState<{ w: number; h: number }>({ w: 0, h: 0 });
  const txtScrollerRef = useRef<HTMLDivElement>(null);

  // ----- PDF state -----
  const [pdfMode, setPdfMode] = useState<"idle" | "pdfjs" | "iframe">("idle");
  const [pdfDoc, setPdfDoc] = useState<PdfDocLike | null>(null);
  const [aspects, setAspects] = useState<(number | null)[]>([]);
  const [aspectsVersion, setAspectsVersion] = useState(0);
  const [zoom, setZoom] = useState(100);
  const [zoomLive, setZoomLive] = useState(100);
  const pdfScrollerRef = useRef<HTMLDivElement>(null);
  const wrapperRefs = useRef(new Map<number, HTMLDivElement>());
  const renderCtl = useRef({
    gen: 0,
    busy: false,
    wanted: new Set<number>(),
    tasks: new Map<number, PdfRenderTaskLike>(),
  });
  const searchToken = useRef(0);

  // ------------------------------------------------------------------
  // Initial load: TXT text or PDF document (+ progress ledger lookup)
  // ------------------------------------------------------------------
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        let progressDoc: ProgressDoc | null = null;
        try {
          progressDoc = await apiGet<ProgressDoc>(PROGRESS_URL);
        } catch {
          progressDoc = null;
        }
        if (cancelled) return;
        progressFetched.current = true;
        const match = progressDoc?.records.find((r) => r.fingerprint === book.fingerprint && r.type === book.bookType);
        matchedProgress.current = match ?? null;

        if (isTxt) {
          const data = await apiGet<{ id: string; title: string; text: string }>(
            `/api/reader/books/${book.id}/text`
          );
          if (cancelled) return;
          setTxtText(data.text);
        } else {
          const lib = await loadPdfjs();
          if (cancelled) return;
          if (!lib) {
            setPdfMode("iframe");
            setPhase("ready");
            restoredRef.current = true;
            return;
          }
          setPdfMode("pdfjs");
          const task = lib.getDocument({ url: contentUrl }) as unknown as {
            promise: Promise<PdfDocLike>;
            destroy(): Promise<void>;
          };
          const doc = await task.promise;
          if (cancelled) {
            void task.destroy().catch(() => {});
            return;
          }
          let seedAspect = 1.35;
          try {
            const first = await doc.getPage(1);
            const vp = first.getViewport({ scale: 1 });
            if (vp.width > 0) seedAspect = vp.height / vp.width;
          } catch {
            /* placeholder ratio stays */
          }
          const initialAspects: (number | null)[] = new Array(doc.numPages).fill(null);
          initialAspects[0] = seedAspect;
          setPdfDoc(doc);
          setAspects(initialAspects);
          setAspectsVersion((v) => v + 1);
          setTotalPages(doc.numPages);
        }
      } catch (err) {
        if (!cancelled) {
          setLoadError(err);
          setPhase("error");
        }
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [book.id]);

  // Destroy the pdf document on unmount.
  useEffect(() => {
    const doc = pdfDoc;
    return () => {
      if (doc) void doc.destroy().catch(() => {});
    };
  }, [pdfDoc]);

  // ------------------------------------------------------------------
  // TXT pagination (chars per page from container size + font size)
  // ------------------------------------------------------------------
  useEffect(() => {
    const el = txtScrollerRef.current;
    if (!el || !isTxt || pdfMode === "pdfjs") return;
    const measure = () => setViewportSize({ w: el.clientWidth, h: el.clientHeight });
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [isTxt, pdfMode, phase]);

  const txtLayout = useMemo(() => {
    if (!isTxt || viewportSize.w <= 0 || viewportSize.h <= 0 || !txtText) return null;
    const padX = 28;
    const padY = 26;
    const footerH = 26;
    const avg = measureAvgAdvance(prefs.fontSizeSp);
    const charsPerLine = Math.max(8, Math.floor(((viewportSize.w - padX * 2) / avg) * 0.97));
    const usableH = viewportSize.h - padY * 2 - footerH;
    const lineHeightPx = prefs.fontSizeSp * prefs.lineHeightMultiplier;
    const paragraphAllowance = Math.min(prefs.paragraphSpacingDp, lineHeightPx) * 0.2;
    const linesPerPage = Math.max(4, Math.floor(usableH / (lineHeightPx + paragraphAllowance)));
    const charsPerPage = Math.max(64, charsPerLine * linesPerPage);
    const starts = slicePageStarts(txtText, charsPerPage);
    return { padX, padY, charsPerPage, starts, pageH: viewportSize.h, pageCount: starts.length - 1 };
  }, [isTxt, txtText, viewportSize, prefs.fontSizeSp, prefs.lineHeightMultiplier, prefs.paragraphSpacingDp]);

  const txtChapters = useMemo(
    () => (txtText ? scanChapters(txtText, prefs) : []),
    [txtText, prefs]
  );

  useEffect(() => {
    if (isTxt && txtLayout) setTotalPages(txtLayout.pageCount);
  }, [isTxt, txtLayout]);

  // Restore TXT position once pagination + measurements are ready.
  useEffect(() => {
    if (!isTxt || !txtLayout || restoredRef.current || txtLayout.pageCount === 0) return;
    restoredRef.current = true;
    const rec = matchedProgress.current;
    const page = rec ? clamp(rec.textPageIndex, 0, txtLayout.pageCount - 1) : 0;
    const frac = rec ? 0.05 : 0;
    pendingPos.current = { page, frac };
    setCurrentPage(page);
    const el = txtScrollerRef.current;
    if (el) {
      el.scrollTop = page * txtLayout.pageH + frac * txtLayout.pageH;
    }
    setPhase("ready");
  }, [isTxt, txtLayout]);

  // ------------------------------------------------------------------
  // Restore PDF position once the document is ready.
  // ------------------------------------------------------------------
  useEffect(() => {
    if (isTxt || !pdfDoc || restoredRef.current) return;
    restoredRef.current = true;
    const rec = matchedProgress.current;
    const page = rec ? clamp(rec.pdfPageIndex, 0, pdfDoc.numPages - 1) : 0;
    pendingPos.current = { page, frac: rec ? 0.05 : 0 };
    setCurrentPage(page);
    requestAnimationFrame(() => jumpToPage.current(page, rec ? 0.05 : 0));
    setPhase("ready");
  }, [isTxt, pdfDoc]);

  // ------------------------------------------------------------------
  // TXT scrolling: windowed rendering + position tracking + save
  // ------------------------------------------------------------------
  const txtWindow = useMemo(() => {
    if (!txtLayout) return { lo: 0, hi: -1 };
    const lo = clamp(currentPage - 2, 0, txtLayout.pageCount - 1);
    const hi = clamp(currentPage + 2, 0, txtLayout.pageCount - 1);
    return { lo, hi };
  }, [txtLayout, currentPage]);

  const onTxtScroll = useCallback(() => {
    const el = txtScrollerRef.current;
    if (!el || !txtLayout || txtLayout.pageCount === 0) return;
    const pageH = txtLayout.pageH;
    const raw = el.scrollTop / pageH;
    const page = clamp(Math.floor(raw + 0.0001), 0, txtLayout.pageCount - 1);
    const frac = clamp(raw - page, 0, 0.999);
    setCurrentPage(page);
    pendingPos.current = { page, frac: Math.floor(frac * 20) / 20 }; // 5% checkpoints
    scheduleProgressSave();
  }, [txtLayout, scheduleProgressSave]);

  // ------------------------------------------------------------------
  // PDF layout math + lazy rendering
  // ------------------------------------------------------------------
  const pdfLayout = useMemo(() => {
    if (isTxt || !pdfDoc || aspects.length !== pdfDoc.numPages) return null;
    const scroller = pdfScrollerRef.current;
    const viewW = scroller?.clientWidth ?? 0;
    const viewH = scroller?.clientHeight ?? 0;
    if (viewW <= 0 || viewH <= 0) return null;
    const dispW = Math.max(80, viewW * (zoom / 100));
    const innerW = Math.max(viewW, dispW);
    const heights = aspects.map((a) => dispW * (a ?? 1.35));
    const offsets: number[] = new Array(heights.length + 1);
    offsets[0] = 0;
    for (let i = 0; i < heights.length; i++) offsets[i + 1] = offsets[i] + heights[i] + 8;
    return { dispW, innerW, viewH, heights, offsets };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isTxt, pdfDoc, aspectsVersion, zoom, phase]);

  const setAspect = useCallback((index: number, aspect: number) => {
    setAspects((prev) => {
      if (index < 0 || index >= prev.length) return prev;
      if (prev[index] !== null && Math.abs((prev[index] as number) - aspect) < 0.01) return prev;
      const next = prev.slice();
      next[index] = aspect;
      return next;
    });
    setAspectsVersion((v) => v + 1);
  }, []);

  const renderPdfPage = useCallback(
    async (index: number, doc: PdfDocLike, dispW: number, gen: number) => {
      const ctl = renderCtl.current;
      const wrapper = wrapperRefs.current.get(index);
      if (!wrapper) return;
      try {
        const page = await doc.getPage(index + 1);
        if (gen !== ctl.gen || !wrapperRefs.current.has(index)) return;
        const vp1 = page.getViewport({ scale: 1 });
        if (vp1.width > 0) setAspect(index, vp1.height / vp1.width);
        const dpr = clamp(window.devicePixelRatio || 1, 1, 2);
        const scale = (dispW * dpr) / vp1.width;
        const vp = page.getViewport({ scale });
        const canvas = document.createElement("canvas");
        canvas.width = Math.max(1, Math.floor(vp.width));
        canvas.height = Math.max(1, Math.floor(vp.height));
        canvas.style.width = `${dispW}px`;
        canvas.style.height = `${vp.height / dpr}px`;
        canvas.style.display = "block";
        const ctx = canvas.getContext("2d");
        if (!ctx) return;
        const task = page.render({ canvasContext: ctx, viewport: vp });
        ctl.tasks.set(index, task);
        await task.promise;
        ctl.tasks.delete(index);
        if (gen !== ctl.gen || !wrapperRefs.current.has(index)) {
          canvas.width = 0;
          canvas.height = 0;
          return;
        }
        wrapper.replaceChildren(canvas);
      } catch {
        /* cancelled render or transient failure — retried when re-requested */
      }
    },
    [setAspect]
  );

  const pumpPdfRenders = useCallback(
    (doc: PdfDocLike, dispW: number) => {
      const ctl = renderCtl.current;
      if (ctl.busy) return;
      const nextIndex = [...ctl.wanted].sort((a, b) => a - b)[0];
      if (nextIndex === undefined) return;
      ctl.wanted.delete(nextIndex);
      ctl.busy = true;
      void renderPdfPage(nextIndex, doc, dispW, ctl.gen).finally(() => {
        ctl.busy = false;
        pumpPdfRenders(doc, dispW);
      });
    },
    [renderPdfPage]
  );

  const requestPdfWindow = useCallback(
    (center: number) => {
      const doc = pdfDoc;
      const layout = pdfLayout;
      if (!doc || !layout) return;
      const ctl = renderCtl.current;
      const lo = clamp(center - 1, 0, doc.numPages - 1);
      const hi = clamp(center + 2, 0, doc.numPages - 1);
      const keep = new Set<number>();
      for (let i = lo; i <= hi; i++) {
        keep.add(i);
        if (!ctl.tasks.has(i)) ctl.wanted.add(i);
      }
      for (const [index, task] of [...ctl.tasks]) {
        if (!keep.has(index)) {
          task.cancel();
          ctl.tasks.delete(index);
        }
      }
      for (const index of [...wrapperRefs.current.keys()]) {
        if (!keep.has(index)) {
          const wrapper = wrapperRefs.current.get(index);
          if (wrapper) wrapper.replaceChildren();
          wrapperRefs.current.delete(index);
        }
      }
      pumpPdfRenders(doc, layout.dispW);
    },
    [pdfDoc, pdfLayout, pumpPdfRenders]
  );

  const onPdfScroll = useCallback(() => {
    const el = pdfScrollerRef.current;
    const layout = pdfLayout;
    if (!el || !layout || layout.offsets.length < 2) return;
    const anchor = el.scrollTop + layout.viewH * 0.25;
    let lo = 0;
    let hi = layout.offsets.length - 2;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (layout.offsets[mid] <= anchor) lo = mid;
      else hi = mid - 1;
    }
    const page = clamp(lo, 0, layout.offsets.length - 2);
    const frac = clamp((el.scrollTop - layout.offsets[page]) / Math.max(1, layout.heights[page]), 0, 0.999);
    setCurrentPage(page);
    pendingPos.current = { page, frac: Math.floor(frac * 20) / 20 };
    requestPdfWindow(page);
    scheduleProgressSave();
  }, [pdfLayout, requestPdfWindow, scheduleProgressSave]);

  // Re-request the visible window whenever layout (resize / zoom) shifts.
  useEffect(() => {
    if (isTxt || !pdfDoc || !pdfLayout) return;
    requestPdfWindow(pendingPos.current.page);
    const el = pdfScrollerRef.current;
    if (!el) return;
    const measure = () => {
      // Recompute through a state nudge handled by pdfLayout's dependencies.
    };
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [isTxt, pdfDoc, pdfLayout, requestPdfWindow]);

  jumpToPage.current = (page: number, frac: number) => {
    if (isTxt) {
      const el = txtScrollerRef.current;
      const layout = txtLayout;
      if (!el || !layout) return;
      const p = clamp(page, 0, layout.pageCount - 1);
      el.scrollTop = p * layout.pageH + frac * layout.pageH;
      onTxtScroll();
    } else {
      const el = pdfScrollerRef.current;
      const layout = pdfLayout;
      if (!el || !layout) return;
      const p = clamp(page, 0, layout.offsets.length - 2);
      el.scrollTop = layout.offsets[p] + frac * layout.heights[p];
      onPdfScroll();
    }
  };

  const goToPage = useCallback(
    (page: number) => {
      const total = totalPages ?? 0;
      if (total <= 0) return;
      jumpToPage.current(clamp(page, 0, total - 1), 0);
    },
    [totalPages]
  );

  // Debounced zoom application: cancel in-flight renders, keep position.
  useEffect(() => {
    if (isTxt) return;
    const timer = window.setTimeout(() => {
      if (zoomLive === zoom) return;
      const ctl = renderCtl.current;
      ctl.gen += 1;
      ctl.wanted.clear();
      for (const [, task] of [...ctl.tasks]) task.cancel();
      ctl.tasks.clear();
      for (const [, wrapper] of [...wrapperRefs.current]) wrapper.replaceChildren();
      wrapperRefs.current.clear();
      setZoom(zoomLive);
    }, 180);
    return () => window.clearTimeout(timer);
  }, [zoomLive, zoom, isTxt]);

  // ------------------------------------------------------------------
  // TXT search (whole-book, case-insensitive, capped)
  // ------------------------------------------------------------------
  useEffect(() => {
    if (!isTxt || !search.open) return;
    const q = search.query.trim().toLowerCase();
    if (!q || !txtText) {
      setSearch((s) => ({ ...s, hits: [], current: -1 }));
      return;
    }
    const lower = txtText.toLowerCase();
    const hits: number[] = [];
    let idx = lower.indexOf(q);
    while (idx >= 0 && hits.length < 500) {
      hits.push(idx);
      idx = lower.indexOf(q, idx + Math.max(1, q.length));
    }
    setSearch((s) => {
      if (s.query.trim().toLowerCase() !== q) return s;
      const current = hits.length > 0 ? 0 : -1;
      if (current === 0 && txtLayout) {
        const target = pageOfCharOffset(hits[0], txtLayout.starts);
        window.setTimeout(() => jumpToPage.current(target, 0), 0);
      }
      return { ...s, hits, current };
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search.open, search.query, txtText, isTxt]);

  function pageOfCharOffset(charIndex: number, starts: number[]): number {
    let lo = 0;
    let hi = starts.length - 2;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (starts[mid] <= charIndex) lo = mid;
      else hi = mid - 1;
    }
    return lo;
  }

  // ------------------------------------------------------------------
  // PDF search via getTextContent → jump to page of first hit
  // ------------------------------------------------------------------
  useEffect(() => {
    if (isTxt || !search.open || !pdfDoc) return;
    const q = search.query.trim().toLowerCase();
    if (!q) {
      setSearch((s) => ({ ...s, hits: [], current: -1, scanning: false }));
      return;
    }
    const token = ++searchToken.current;
    setSearch((s) => ({ ...s, scanning: true }));
    const hits: number[] = [];
    (async () => {
      for (let p = 1; p <= pdfDoc.numPages; p++) {
        if (token !== searchToken.current || !aliveRef.current) return;
        try {
          const page = await pdfDoc.getPage(p);
          const tc = await page.getTextContent();
          const text = tc.items.map((it) => it.str ?? "").join(" ").toLowerCase();
          if (text.includes(q)) {
            hits.push(p - 1);
            setSearch((s) => ({ ...s, hits: [...hits] }));
            if (hits.length === 1) jumpToPage.current(p - 1, 0);
            if (hits.length >= 200) break;
          }
        } catch {
          break;
        }
      }
      if (token === searchToken.current) {
        setSearch((s) => ({ ...s, scanning: false, current: hits.length > 0 ? 0 : -1 }));
      }
    })();
    return () => {
      searchToken.current += 1;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search.open, search.query, pdfDoc, isTxt]);

  const stepSearch = useCallback(
    (delta: number) => {
      setSearch((s) => {
        if (s.hits.length === 0) return s;
        const next = (s.current + delta + s.hits.length) % s.hits.length;
        const hit = s.hits[next];
        if (hit !== undefined) {
          if (isTxt && txtLayout) jumpToPage.current(pageOfCharOffset(hit, txtLayout.starts), 0);
          else jumpToPage.current(hit, 0);
        }
        return { ...s, current: next };
      });
    },
    [isTxt, txtLayout]
  );

  // ------------------------------------------------------------------
  // PDF outline (目录 via getOutline())
  // ------------------------------------------------------------------
  interface OutlineEntry {
    title: string;
    page: number;
    depth: number;
  }
  const [outline, setOutline] = useState<OutlineEntry[] | null>(null);

  useEffect(() => {
    if (isTxt || !pdfDoc || outline) return;
    let cancelled = false;
    (async () => {
      const flat: OutlineEntry[] = [];
      try {
        const tree = await pdfDoc.getOutline();
        const walk = async (items: PdfOutlineItemLike[], depth: number): Promise<void> => {
          for (const item of items) {
            if (cancelled || flat.length >= 400) return;
            let page = -1;
            try {
              const dest = typeof item.dest === "string" ? await pdfDoc.getDestination(item.dest) : item.dest;
              if (Array.isArray(dest) && dest.length > 0) page = await pdfDoc.getPageIndex(dest[0]);
            } catch {
              page = -1;
            }
            flat.push({ title: String(item.title ?? ""), page, depth });
            if (Array.isArray(item.items) && item.items.length > 0) await walk(item.items as PdfOutlineItemLike[], depth + 1);
          }
        };
        if (tree && tree.length > 0) await walk(tree, 0);
      } catch {
        /* outlines are optional */
      }
      if (!cancelled) setOutline(flat);
    })();
    return () => {
      cancelled = true;
    };
  }, [isTxt, pdfDoc, outline]);

  // ------------------------------------------------------------------
  // Sidebar entries: TXT chapters (第…章) or PDF outline
  // ------------------------------------------------------------------
  const sidebarEntries: OutlineEntry[] = useMemo(() => {
    if (isTxt) {
      return txtChapters.map((c) => ({
        title: c.title,
        page: txtLayout ? pageOfCharOffset(c.charIndex, txtLayout.starts) : 0,
        depth: 0,
      }));
    }
    return outline ?? [];
  }, [isTxt, txtChapters, txtLayout, outline]);

  const activeSidebarIndex = useMemo(() => {
    let active = -1;
    for (let i = 0; i < sidebarEntries.length; i++) {
      const entry = sidebarEntries[i];
      if (entry.page >= 0 && entry.page <= currentPage) active = i;
    }
    return active;
  }, [sidebarEntries, currentPage]);

  // Settings popover shows accumulated reading time for the current book.
  useEffect(() => {
    if (!settingsOpen || readingTime !== null) return;
    (async () => {
      try {
        const summary = await apiGet<{ books?: { bookId: string; totalSeconds?: number }[] }>(
          `${ENGAGEMENT_URL}?days=36600`
        );
        const mine = summary.books?.find((b) => b.bookId === book.id);
        setReadingTime(mine?.totalSeconds ?? 0);
      } catch {
        setReadingTime(null);
      }
    })();
  }, [settingsOpen, readingTime, book.id]);

  // Close popovers on Escape.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      if (search.open) {
        setSearch(INITIAL_SEARCH);
        searchToken.current += 1;
      } else if (settingsOpen) {
        setSettingsOpen(false);
        setSettingsDraft(null);
      }
      else if (sidebarOpen) setSidebarOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [search.open, settingsOpen, sidebarOpen]);

  // ------------------------------------------------------------------
  // Center-tap toggling (tap = pointerup within 8 px of pointerdown)
  // ------------------------------------------------------------------
  const tapOrigin = useRef<{ x: number; y: number } | null>(null);
  const onContentPointerDown = useCallback((e: React.PointerEvent) => {
    tapOrigin.current = { x: e.clientX, y: e.clientY };
  }, []);
  const onContentPointerUp = useCallback((e: React.PointerEvent) => {
    const origin = tapOrigin.current;
    tapOrigin.current = null;
    if (!origin) return;
    if (Math.hypot(e.clientX - origin.x, e.clientY - origin.y) > 8) return;
    if ((e.target as HTMLElement).closest("button, input, a, mark")) return;
    setChromeVisible((v) => !v);
  }, []);

  // Record builder kept fresh for debounced saves and unmount flush.
  buildRecordRef.current = () => {
    const { page, frac } = pendingPos.current;
    if (isTxt) {
      const paragraphProxy = clamp(txtLayout ? txtLayout.starts[Math.min(page, txtLayout.starts.length - 1)] / 40 : 0, 0, 249_999);
      return makeRecord({
        fingerprint: book.fingerprint,
        type: "TXT",
        textPageIndex: clamp(Math.floor(page), -1, 49_999),
        textParagraphIndex: Math.floor(paragraphProxy),
        pdfPageIndex: 0,
        totalPages: clamp(totalPages ?? 0, 0, 50_000),
      });
    }
    return makeRecord({
      fingerprint: book.fingerprint,
      type: "PDF",
      textPageIndex: -1,
      textParagraphIndex: 0,
      pdfPageIndex: clamp(Math.floor(page), 0, 19_999),
      totalPages: clamp(totalPages ?? 0, 0, 20_000),
    });
  };

  const indicator =
    totalPages && totalPages > 0 ? `${clamp(currentPage + (isTxt ? 1 : 0) + (isTxt ? 0 : 1) > 0 ? currentPage + 1 : currentPage, 0, totalPages)} / ${totalPages}` : "–";
  const displayedSettings = settingsDraft ?? prefs;
  const customRegexValid = isCustomChapterRegexValid(displayedSettings.customChapterRegex);

  // ------------------------------------------------------------------
  // Render
  // ------------------------------------------------------------------
  return (
    <div
      style={{
        position: "fixed",
        inset: 0,
        zIndex: 150,
        background: "var(--dc-background)",
        display: "flex",
        flexDirection: "column",
      }}
      role="region"
      aria-label={book.title}
    >
      {/* Top bar */}
      <div
        className="dc-row"
        style={{
          padding: "8px 8px",
          gap: 4,
          borderBottom: chromeVisible ? "1px solid var(--dc-outline-variant)" : "1px solid transparent",
          transition: "opacity 0.15s ease",
          opacity: chromeVisible ? 1 : 0,
          pointerEvents: chromeVisible ? "auto" : "none",
        }}
      >
        <button className="dc-icon-btn" aria-label={tr("返回", "Back")} onClick={props.onClose}>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M19 12H5M12 19l-7-7 7-7" />
          </svg>
        </button>
        <div className="dc-grow dc-title" style={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
          {book.title}
        </div>
        {pdfMode === "pdfjs" && pdfDoc && (
          <div className="dc-row" style={{ gap: 2, alignItems: "center" }} aria-label={tr("缩放", "Zoom")}>
            <button className="dc-icon-btn" aria-label={tr("缩小", "Zoom out")} onClick={() => setZoomLive((z) => clamp(z - 10, 25, 400))}>
              <Minus size={16} />
            </button>
            <input
              type="range"
              min={25}
              max={400}
              step={1}
              value={zoomLive}
              style={{ width: 110 }}
              aria-label={tr("缩放比例", "Zoom level")}
              onChange={(e) => setZoomLive(Number(e.target.value))}
            />
            <button className="dc-icon-btn" aria-label={tr("放大", "Zoom in")} onClick={() => setZoomLive((z) => clamp(z + 10, 25, 400))}>
              <Plus size={16} />
            </button>
            <span className="dc-muted" style={{ fontSize: "0.8em", width: 42 }}>{zoom}%</span>
          </div>
        )}
        <button
          className="dc-icon-btn"
          aria-label={tr("目录", "Contents")}
          disabled={!isTxt && outline !== null && outline.length === 0}
          onClick={() => setSidebarOpen(true)}
        >
          <FileText size={20} />
        </button>
        {(isTxt || pdfMode === "pdfjs") && (
          <button
            className="dc-icon-btn"
            aria-label={tr("搜索", "Search")}
            onClick={() => setSearch((s) => ({ ...INITIAL_SEARCH, open: !s.open }))}
          >
            <Search size={20} />
          </button>
        )}
        <button
          className="dc-icon-btn"
          aria-label={tr("阅读设置", "Reading settings")}
          onClick={() => {
            if (settingsOpen) {
              setSettingsOpen(false);
              setSettingsDraft(null);
            } else {
              setSettingsDraft({ ...prefs });
              setSettingsOpen(true);
            }
          }}
        >
          <Settings size={20} />
        </button>
      </div>

      {/* Search bar */}
      {search.open && (
        <div className="dc-row" style={{ padding: "6px 12px", gap: 8, borderBottom: "1px solid var(--dc-outline-variant)" }}>
          <input
            className="dc-input dc-grow"
            style={{ minWidth: 0 }}
            placeholder={tr("搜索", "Search")}
            maxLength={128}
            autoFocus
            value={search.query}
            onChange={(e) => setSearch((s) => ({ ...s, query: e.target.value, current: -1, hits: [] }))}
          />
          <span className="dc-muted" style={{ fontSize: "0.85em", whiteSpace: "nowrap" }}>
            {search.hits.length > 0
              ? `${clamp(search.current + 1, 1, search.hits.length)} / ${search.hits.length}`
              : search.scanning
                ? tr("搜索中…", "Searching…")
                : ""}
          </span>
          <button className="dc-icon-btn" aria-label={tr("上一项", "Previous match")} disabled={search.hits.length === 0} onClick={() => stepSearch(-1)}>
            <ChevronUp size={18} />
          </button>
          <button className="dc-icon-btn" aria-label={tr("下一项", "Next match")} disabled={search.hits.length === 0} onClick={() => stepSearch(1)}>
            <ChevronDown size={18} />
          </button>
          <button
            className="dc-icon-btn"
            aria-label={tr("关闭", "Close")}
            onClick={() => {
              searchToken.current += 1;
              setSearch(INITIAL_SEARCH); // closing clears the query (README)
            }}
          >
            <X size={18} />
          </button>
        </div>
      )}

      {/* Content */}
      <div
        style={{ position: "relative", flex: 1, minHeight: 0, background: isTxt || pdfMode !== "pdfjs" ? "var(--dc-background)" : theme.bg }}
        onPointerDown={onContentPointerDown}
        onPointerUp={onContentPointerUp}
      >
        {isTxt ? (
          <div
            ref={txtScrollerRef}
            onScroll={onTxtScroll}
            style={{ position: "absolute", inset: 0, overflowY: "auto", overflowX: "hidden", background: theme.bg }}
          >
            {txtLayout && (
              <div style={{ position: "relative", height: txtLayout.pageCount * txtLayout.pageH }}>
                {txtLayout.pageCount > 0 &&
                  Array.from({ length: txtWindow.hi - txtWindow.lo + 1 }, (_, k) => {
                    const i = txtWindow.lo + k;
                    const slice = txtText?.slice(txtLayout.starts[i], txtLayout.starts[i + 1]) ?? "";
                    return (
                      <div
                        key={i}
                        style={{
                          position: "absolute",
                          top: i * txtLayout.pageH,
                          left: 0,
                          right: 0,
                          height: txtLayout.pageH,
                          padding: `${txtLayout.padY}px ${txtLayout.padX}px`,
                          color: theme.fg,
                          fontSize: prefs.fontSizeSp,
                          lineHeight: prefs.lineHeightMultiplier,
                          whiteSpace: "pre-wrap",
                          overflowWrap: "break-word",
                          overflow: "hidden",
                          fontFamily: '"Noto Serif SC", Georgia, "Times New Roman", serif',
                        }}
                      >
                        {slice.split("\n\n").map((paragraph, paragraphIndex, paragraphs) => (
                          <React.Fragment key={paragraphIndex}>
                            <span style={{ display: "block", marginBottom: prefs.paragraphSpacingDp }}>
                              {highlightSlice(paragraph, search.hits.length > 0 ? search.query.trim() : "")}
                            </span>
                            {paragraphIndex < paragraphs.length - 1 && <span aria-hidden="true">{"\n"}</span>}
                          </React.Fragment>
                        ))}
                        <span
                          style={{
                            position: "absolute",
                            left: 0,
                            right: 0,
                            bottom: 8,
                            textAlign: "center",
                            fontSize: 12,
                            color: theme.faint,
                            fontFamily: "system-ui, sans-serif",
                          }}
                        >
                          {i + 1} / {txtLayout.pageCount}
                        </span>
                      </div>
                    );
                  })}
              </div>
            )}
          </div>
        ) : pdfMode === "pdfjs" && pdfDoc && pdfLayout ? (
          <div
            ref={pdfScrollerRef}
            onScroll={onPdfScroll}
            style={{ position: "absolute", inset: 0, overflow: "auto", background: theme.bg }}
          >
            <div style={{ position: "relative", width: pdfLayout.innerW, height: pdfLayout.offsets[pdfLayout.offsets.length - 1] }}>
              {Array.from({ length: clamp(currentPage + 3, 1, pdfDoc.numPages) - clamp(currentPage - 1, 0, pdfDoc.numPages - 1) }, (_, k) => {
                const i = clamp(currentPage - 1, 0, pdfDoc.numPages - 1) + k;
                return (
                  <div
                    key={i}
                    ref={(node) => {
                      if (node) wrapperRefs.current.set(i, node);
                      else wrapperRefs.current.delete(i);
                    }}
                    data-pdf-page={i}
                    style={{
                      position: "absolute",
                      top: pdfLayout.offsets[i],
                      left: (pdfLayout.innerW - pdfLayout.dispW) / 2,
                      width: pdfLayout.dispW,
                      height: pdfLayout.heights[i],
                      background: theme.bg,
                      boxShadow: "0 1px 4px rgba(0,0,0,0.18)",
                    }}
                  />
                );
              })}
            </div>
          </div>
        ) : pdfMode === "iframe" ? (
          <iframe
            title={book.title}
            src={contentUrl}
            style={{ position: "absolute", inset: 0, width: "100%", height: "100%", border: "none", background: theme.bg }}
          />
        ) : (
          <div />
        )}

        {phase === "loading" && (
          <div
            style={{
              position: "absolute",
              inset: 0,
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "center",
              gap: 12,
              background: "var(--dc-background)",
              zIndex: 5,
            }}
          >
            <Spinner />
            <div className="dc-muted">{tr("正在打开…", "Opening…")}</div>
            <button className="dc-btn" onClick={props.onClose}>
              {tr("返回", "Back")}
            </button>
          </div>
        )}
        {phase === "error" && (
          <div style={{ position: "absolute", inset: 0, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", gap: 10, background: "var(--dc-background)", zIndex: 5 }}>
            <ErrorText error={loadError} />
            <button className="dc-btn" onClick={props.onClose}>
              {tr("返回书架", "Back to shelf")}
            </button>
          </div>
        )}
      </div>

      {/* Bottom bar */}
      <div
        className="dc-row"
        style={{
          justifyContent: "center",
          alignItems: "center",
          gap: 18,
          padding: "6px 12px",
          borderTop: "1px solid var(--dc-outline-variant)",
          transition: "opacity 0.15s ease",
          opacity: chromeVisible ? 1 : 0,
          pointerEvents: chromeVisible ? "auto" : "none",
        }}
      >
        <button className="dc-icon-btn" aria-label={tr("上一页", "Previous page")} disabled={!totalPages || currentPage <= (isTxt ? 0 : 0)} onClick={() => goToPage(currentPage - 1)}>
          <ChevronUp size={20} />
        </button>
        <span className="dc-muted" style={{ minWidth: 90, textAlign: "center", fontVariantNumeric: "tabular-nums" }}>
          {indicator}
        </span>
        <button className="dc-icon-btn" aria-label={tr("下一页", "Next page")} disabled={!totalPages || currentPage >= totalPages - 1} onClick={() => goToPage(currentPage + 1)}>
          <ChevronDown size={20} />
        </button>
      </div>

      {/* 目录 sidebar */}
      {sidebarOpen && (
        <>
          <div
            style={{ position: "absolute", inset: 0, background: "var(--dc-scrim)", zIndex: 28 }}
            onClick={() => setSidebarOpen(false)}
          />
          <aside
            style={{
              position: "absolute",
              top: 0,
              bottom: 0,
              left: 0,
              width: "min(320px, 86vw)",
              background: "var(--dc-surface-container)",
              zIndex: 29,
              display: "flex",
              flexDirection: "column",
              boxShadow: "0 8px 28px rgba(0,0,0,0.25)",
            }}
          >
            <div className="dc-row" style={{ padding: "12px 14px", borderBottom: "1px solid var(--dc-outline-variant)" }}>
              <div className="dc-title dc-grow">{tr("目录", "Contents")}</div>
              <button className="dc-icon-btn" aria-label={tr("关闭", "Close")} onClick={() => setSidebarOpen(false)}>
                <X size={18} />
              </button>
            </div>
            <div style={{ flex: 1, overflowY: "auto", padding: "6px 0" }}>
              {sidebarEntries.length === 0 ? (
                <div className="dc-muted" style={{ padding: "18px 16px", fontSize: "0.9em" }}>
                  {tr("没有可用的目录", "No contents available")}
                </div>
              ) : (
                sidebarEntries.map((entry, i) => (
                  <button
                    key={i}
                    onClick={() => {
                      setSidebarOpen(false);
                      if (entry.page >= 0) goToPage(entry.page);
                    }}
                    style={{
                      display: "block",
                      width: "100%",
                      textAlign: "left",
                      padding: "9px 14px",
                      paddingLeft: 14 + entry.depth * 14,
                      border: "none",
                      background: i === activeSidebarIndex ? "var(--dc-secondary-container)" : "transparent",
                      color: i === activeSidebarIndex ? "var(--dc-on-secondary-container)" : "var(--dc-on-surface)",
                      cursor: "pointer",
                      fontSize: "0.92em",
                      overflow: "hidden",
                      textOverflow: "ellipsis",
                      whiteSpace: "nowrap",
                    }}
                    title={entry.title}
                  >
                    {entry.title || "—"}
                  </button>
                ))
              )}
            </div>
          </aside>
        </>
      )}

      {/* 阅读设置 popover */}
      {settingsOpen && (
        <div
          style={{
            position: "absolute",
            top: 54,
            right: 10,
            width: "min(300px, 92vw)",
            background: "var(--dc-surface-container-high)",
            borderRadius: "var(--dc-radius)",
            boxShadow: "0 10px 30px rgba(0,0,0,0.28)",
            padding: 16,
            zIndex: 30,
            display: "flex",
            flexDirection: "column",
            gap: 12,
            maxHeight: "calc(100vh - 72px)",
            overflowY: "auto",
          }}
          role="dialog"
          aria-label={tr("阅读设置", "Reading settings")}
        >
          <div className="dc-title">{tr("阅读设置", "Reading settings")}</div>
          <label className="dc-col" style={{ gap: 4 }}>
            <span className="dc-muted" style={{ fontSize: "0.88em" }}>
              {tr("字号", "Font size")} · {displayedSettings.fontSizeSp}
            </span>
            <input
              type="range"
              min={12}
              max={38}
              step={1}
              value={displayedSettings.fontSizeSp}
              onChange={(e) => setSettingsDraft((p) => p ? ({ ...p, fontSizeSp: Number(e.target.value) }) : p)}
            />
          </label>
          <label className="dc-col" style={{ gap: 4 }}>
            <span className="dc-muted" style={{ fontSize: "0.88em" }}>
              {tr("行距", "Line spacing")} · ×{displayedSettings.lineHeightMultiplier.toFixed(1)}
            </span>
            <input
              type="range"
              min={1}
              max={2.4}
              step={0.1}
              value={displayedSettings.lineHeightMultiplier}
              onChange={(e) => setSettingsDraft((p) => p ? ({ ...p, lineHeightMultiplier: Number(e.target.value) }) : p)}
            />
          </label>
          {isTxt && (
            <label className="dc-col" style={{ gap: 4 }}>
              <span className="dc-muted" style={{ fontSize: "0.88em" }}>
                {tr("段间距", "Paragraph spacing")} · {Math.round(displayedSettings.paragraphSpacingDp)} dp
              </span>
              <input
                type="range"
                min={0}
                max={36}
                step={2}
                value={displayedSettings.paragraphSpacingDp}
                onChange={(e) => setSettingsDraft((p) => p ? ({ ...p, paragraphSpacingDp: Number(e.target.value) }) : p)}
              />
            </label>
          )}
          <div className="dc-col" style={{ gap: 6 }}>
            <span className="dc-muted" style={{ fontSize: "0.88em" }}>{tr("背景颜色", "Background")}</span>
            <div className="dc-row" style={{ gap: 8, flexWrap: "wrap" }}>
              {READER_THEMES.map((t) => (
                <button
                  key={t.key}
                  className={`dc-chip ${displayedSettings.background === t.key ? "active" : ""}`}
                  onClick={() => setSettingsDraft((p) => p ? ({ ...p, background: t.key }) : p)}
                >
                  {tr(t.zh, t.en)}
                </button>
              ))}
            </div>
            {displayedSettings.background === "CUSTOM" && (
              <label className="dc-row" style={{ justifyContent: "space-between" }}>
                <span>{tr("自定义背景", "Custom background")}</span>
                <input
                  type="color"
                  value={signedArgbToCss(displayedSettings.customBackgroundArgb)}
                  aria-label={tr("自定义背景颜色", "Custom background color")}
                  onChange={(e) => setSettingsDraft((p) => p ? ({ ...p, customBackgroundArgb: cssToSignedArgb(e.target.value) }) : p)}
                />
              </label>
            )}
          </div>
          <label className="dc-row" style={{ justifyContent: "space-between", gap: 12 }}>
            <span>{tr("显示书架进度", "Show shelf progress")}</span>
            <input
              type="checkbox"
              checked={displayedSettings.showProgressPercentage}
              onChange={(e) => setSettingsDraft((p) => p ? ({ ...p, showProgressPercentage: e.target.checked }) : p)}
            />
          </label>
          {isTxt && (
            <div className="dc-col" style={{ gap: 6 }}>
              <span className="dc-muted" style={{ fontSize: "0.88em" }}>{tr("智能章节", "Smart chapters")}</span>
              <div className="dc-row" style={{ gap: 8, flexWrap: "wrap" }}>
                {([
                  ["SMART", tr("仅智能", "Smart only")],
                  ["CUSTOM", tr("仅自定义", "Custom only")],
                  ["SMART_AND_CUSTOM", tr("智能 + 自定义", "Smart + custom")],
                ] as const).map(([mode, label]) => (
                  <button
                    key={mode}
                    className={`dc-chip ${displayedSettings.chapterDetectionMode === mode ? "active" : ""}`}
                    onClick={() => setSettingsDraft((p) => p ? ({ ...p, chapterDetectionMode: mode }) : p)}
                  >
                    {label}
                  </button>
                ))}
              </div>
              <span className="dc-muted" style={{ fontSize: "0.8em", lineHeight: 1.4 }}>
                {tr(
                  "智能规则支持中文章节/卷/回/幕、英文 Chapter/Part/Book/Section/Episode、Markdown 标题、序章/尾声及多种编号格式。",
                  "Smart rules cover Chinese chapters/volumes, Chapter/Part/Book/Section/Episode, Markdown headings, prologues/epilogues, and several numbering styles."
                )}
              </span>
              {displayedSettings.chapterDetectionMode !== "SMART" && (
                <label className="dc-col" style={{ gap: 4 }}>
                  <span className="dc-muted" style={{ fontSize: "0.88em" }}>
                    {tr("自定义整行正则", "Custom full-line regex")}
                  </span>
                  <textarea
                    className="dc-input"
                    rows={3}
                    maxLength={MAX_CUSTOM_CHAPTER_REGEX_CHARS}
                    placeholder={"^(第.+章|Chapter\\s+.+)$"}
                    value={displayedSettings.customChapterRegex}
                    aria-invalid={!customRegexValid}
                    onChange={(e) => setSettingsDraft((p) => p ? ({ ...p, customChapterRegex: e.target.value }) : p)}
                  />
                  <span
                    style={{
                      color: customRegexValid ? "var(--dc-on-surface-variant)" : "var(--dc-error)",
                      fontSize: "0.78em",
                    }}
                  >
                    {customRegexValid
                      ? tr("规则匹配整行；留空等于不追加自定义规则。", "The rule matches a whole line; leave blank to add no custom rule.")
                      : tr("正则格式无效", "Invalid regular expression")}
                  </span>
                </label>
              )}
              <label className="dc-col" style={{ gap: 4 }}>
                <span className="dc-muted" style={{ fontSize: "0.88em" }}>
                  {tr("章节标题最长字符数", "Maximum heading length")} · {displayedSettings.chapterHeadingMaxChars}
                </span>
                <input
                  type="range"
                  min={MIN_CHAPTER_HEADING_CHARS}
                  max={MAX_CHAPTER_HEADING_CHARS}
                  step={1}
                  value={displayedSettings.chapterHeadingMaxChars}
                  onChange={(e) => setSettingsDraft((p) => p ? ({ ...p, chapterHeadingMaxChars: Number(e.target.value) }) : p)}
                />
              </label>
            </div>
          )}
          {readingTime !== null && (
            <div className="dc-muted" style={{ fontSize: "0.85em" }}>
              {tr("累计阅读时间", "Total reading time")}：{fmtDuration(readingTime)}
            </div>
          )}
          <div className="dc-row" style={{ justifyContent: "flex-end" }}>
            <button
              className="dc-btn"
              onClick={() => {
                setSettingsOpen(false);
                setSettingsDraft(null);
              }}
            >
              {tr("取消", "Cancel")}
            </button>
            <button
              className="dc-btn dc-btn-filled"
              disabled={!customRegexValid}
              onClick={() => {
                if (settingsDraft) setPrefs(normalizePrefs(settingsDraft));
                setSettingsOpen(false);
                setSettingsDraft(null);
              }}
            >
              {tr("保存", "Save")}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
