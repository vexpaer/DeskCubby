/**
 * HomePage — web replication of Android ui/home/HomeScreen.kt + HomeViewModel.kt.
 *
 * A vertically scrollable module list whose contents/order come from
 * settings.homeWidgets. Module titles render only when the id is listed in
 * settings.homeWidgetTitles; cards render bordered (.dc-card) only when
 * settings.homeWidgetBordersEnabled is true. Data comes from the REST APIs
 * documented in web/docs/CONVENTIONS.md; every module degrades gracefully
 * while its data is missing.
 */
import React, { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Check as IconCheck, ChevronDown as IconChevronDown, ChevronUp as IconChevronUp,
  FileText as IconFile, Globe as IconGlobe, GripVertical as IconGrip,
  RefreshCw as IconRefresh, Send as IconSend, Sun as IconSun,
} from "lucide-react";
import { ApiClientError, apiGet, apiSend, apiUpload } from "../../api/client";
import type { AppSettings, HomeGreetingTemplate } from "../../api/types";
import { argbToCss, MEAL_CATEGORIES } from "../../api/types";
import { useSettings } from "../../stores/settings";
import { tr, uiLanguage } from "../../i18n/tr";
import { ConfirmDialog, ErrorText, Modal, PageTutorialOverlay, Snackbar, Spinner, useSnackbar } from "../../components/ui";

/* ------------------------------------------------------------------ */
/* API shapes (mirror Room entities / Android models, camelCase)       */
/* ------------------------------------------------------------------ */

interface DiaryDocument {
  name: string;
  title?: string;
  dateIso: string;
  lastModified: number;
  wordCount: number;
}

/** GET /api/diary/stats — fields are merged over locally computed values when present. */
interface DiaryStats {
  streakDays?: number;
  monthCount?: number;
  totalWords?: number;
  totalDocuments?: number;
}

interface FlashThought {
  id: number;
  content: string;
  createdAt: number;
  updatedAt: number;
  highlighted?: boolean;
}

interface ThoughtCategory {
  id: number;
  name: string;
  colorArgb: number;
}

interface DateRecord {
  id: number;
  name: string;
  icon: string;
  dateIso: string;
  createdAt: number;
}

interface DailyPoem {
  content: string;
  source: string;
  title?: string;
  fullContent?: string;
  dynasty?: string;
}

/** Android PoetryRepository.FALLBACK — shown while/offline instead of an error. */
const FALLBACK_POEM: DailyPoem = {
  content: "山中何事？松花酿酒，春水煎茶。",
  source: "— 张可久《人月圆·山中书事》",
  fullContent:
    "兴亡千古繁华梦，诗眼倦天涯。\n孔林乔木，吴宫蔓草，楚庙寒鸦。\n数间茅舍，藏书万卷，投老村家。\n山中何事？松花酿酒，春水煎茶。",
  dynasty: "元",
  title: "人月圆·山中书事",
};

const HOME_GAME_SHORTCUTS: { id: string; zh: string; en: string }[] = [
  { id: "2048", zh: "2048 · 4×4", en: "2048 · 4×4" },
  { id: "2048_5", zh: "2048 · 5×5", en: "2048 · 5×5" },
  { id: "2048_6", zh: "2048 · 6×6", en: "2048 · 6×6" },
  { id: "snake", zh: "贪吃蛇", en: "Snake" },
  { id: "tetris", zh: "俄罗斯方块", en: "Tetris" },
  { id: "minesweeper", zh: "扫雷", en: "Minesweeper" },
  { id: "spider", zh: "蜘蛛纸牌", en: "Spider Solitaire" },
  { id: "go", zh: "围棋", en: "Go" },
];

const GAME_ROUTES: Record<string, string> = {
  "2048": "/games/2048",
  "2048_5": "/games/2048?size=5",
  "2048_6": "/games/2048?size=6",
  snake: "/games/snake",
  tetris: "/games/tetris",
  minesweeper: "/games/minesweeper",
  spider: "/games/spider",
  go: "/games/go",
};

/* ------------------------------------------------------------------ */
/* More API shapes                                                     */
/* ------------------------------------------------------------------ */

/** GET/PUT /api/diary/document. */
interface DiaryEditorDocument {
  name?: string;
  content: string;
  sha256: string;
  lastModified?: number;
}

/** POST /api/media/upload → ImportedMedia. */
interface ImportedMedia {
  fileName: string;
  markdown: string;
}

interface MealPhotoThumb {
  path: string;
  category: string;
  caption: string;
}

/** GET /api/cloudsync/status — defensive shape; every field optional. */
interface CloudSyncStatus {
  enabled?: boolean;
  configs?: { id: string; name?: string; enabled?: boolean }[];
  running?: boolean;
  progress?: { completedObjects?: number; totalObjects?: number } | null;
  message?: string | null;
  error?: string | null;
  lastFinishedAt?: number | null;
  lastUploadedCount?: number | null;
  lastDownloadedCount?: number | null;
  lastConflictCount?: number | null;
}

interface StructFieldView {
  id: string;
  label: string;
  type: string;
}

interface StructRecordRow {
  fieldId?: string;
  rawValue?: string;
  journalDay?: string;
}

/* ------------------------------------------------------------------ */
/* Shared helpers                                                      */
/* ------------------------------------------------------------------ */

const DAY_MS = 86_400_000;

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

function isoDate(d: Date): string {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

/** Parse yyyy-MM-dd as a LOCAL date (JS default parses it as UTC). */
function parseIsoLocal(s: string): Date | null {
  const m = /^(\d{4})-(\d{1,2})-(\d{1,2})/.exec(s);
  if (!m) return null;
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  return Number.isNaN(d.getTime()) ? null : d;
}

/** Days from a to b on the local calendar (negative when a is before b). */
function dayDiff(a: Date, b: Date): number {
  return Math.round(
    (Date.UTC(a.getFullYear(), a.getMonth(), a.getDate()) -
      Date.UTC(b.getFullYear(), b.getMonth(), b.getDate())) / DAY_MS
  );
}

/** Port of Android HomeGreeting.forDate — stable per-local-date pick with {name}. */
export function greetingForDate(
  date: Date,
  english: boolean,
  userName: string,
  templates: HomeGreetingTemplate[]
): string {
  const patterns = templates
    .map((t) => {
      const preferred = english ? t.english : t.chinese;
      const fallback = english ? t.chinese : t.english;
      return (preferred.trim() || fallback.trim());
    })
    .filter((p) => p.length > 0);
  if (patterns.length === 0) return english ? "Today's overview" : "今日概览";
  const epochDay = Math.round(
    Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / DAY_MS
  );
  const index = ((epochDay % patterns.length) + patterns.length) % patterns.length;
  const name = userName.trim() || (english ? "you" : "你");
  return patterns[index].split("{name}").join(name);
}

/** Port of Android HomeScreen.streakDays(diaries, today). */
function streakDaysFromDocs(docs: DiaryDocument[], today: Date): number {
  const dates = new Set(docs.map((d) => d.dateIso));
  const cursor = new Date(today);
  if (!dates.has(isoDate(cursor))) cursor.setDate(cursor.getDate() - 1);
  let count = 0;
  while (dates.has(isoDate(cursor))) {
    count += 1;
    cursor.setDate(cursor.getDate() - 1);
  }
  return count;
}

/* ------------------------------------------------------------------ */
/* Today-diary append pipeline (meal photos / quick diary lines)       */
/* ------------------------------------------------------------------ */

/** Find today's diary document, creating it when missing (POST /api/diary/documents). */
export async function ensureTodayDiaryName(todayIso: string): Promise<string> {
  const docs = await apiGet<DiaryDocument[]>("/api/diary/documents");
  const list = Array.isArray(docs) ? docs : [];
  const found =
    list.find((d) => d.dateIso === todayIso) ??
    list.find((d) => (d.name ?? "").startsWith(todayIso));
  if (found) return found.name;
  const created = await apiSend<Partial<DiaryDocument>>("/api/diary/documents", "POST", {
    dateIso: todayIso,
  });
  return created?.name ?? `${todayIso} 日记.md`;
}

function mergeAppend(content: string, appendText: string): string {
  const base = content.length > 0 ? (content.endsWith("\n") ? content : `${content}\n`) : "";
  return `${base}${appendText.trimEnd()}\n`;
}

/**
 * Append text to today's diary with SHA-256 conflict handling: on 409 the
 * current content is reloaded once and the merge retried exactly once.
 */
export async function appendToTodayDiary(appendText: string): Promise<void> {
  const todayIso = isoDate(new Date());
  const name = await ensureTodayDiaryName(todayIso);
  const load = () =>
    apiGet<DiaryEditorDocument>(`/api/diary/document?name=${encodeURIComponent(name)}`);
  let current = await load();
  try {
    await apiSend("/api/diary/document", "PUT", {
      name,
      content: mergeAppend(current.content ?? "", appendText),
      previousSha256: current.sha256,
    });
  } catch (error) {
    if (error instanceof ApiClientError && error.status === 409) {
      current = await load();
      await apiSend("/api/diary/document", "PUT", {
        name,
        content: mergeAppend(current.content ?? "", appendText),
        previousSha256: current.sha256,
      });
    } else {
      throw error;
    }
  }
}

/* ------------------------------------------------------------------ */
/* Meals calendar / structured config defensive parsers                */
/* ------------------------------------------------------------------ */

function mediaThumbUrl(path: string): string {
  return `/api/media/file?path=${encodeURIComponent(path)}&size=thumb`;
}

/** Extract today's photos from GET /api/meals/calendar without trusting one exact shape. */
export function extractPhotosForDay(payload: unknown, todayIso: string): MealPhotoThumb[] {
  let days: unknown[] = [];
  if (Array.isArray(payload)) {
    days = payload;
  } else if (payload && typeof payload === "object") {
    const obj = payload as Record<string, unknown>;
    if (obj[todayIso] !== undefined) days = [obj[todayIso]];
    else days = Object.values(obj);
  }
  const out: MealPhotoThumb[] = [];
  for (const day of days) {
    let dayDate = "";
    let photos: unknown[] = [];
    if (Array.isArray(day)) {
      photos = day;
    } else if (day && typeof day === "object") {
      const d = day as Record<string, unknown>;
      dayDate = String(d.dateIso ?? d.date ?? d.day ?? "");
      if (Array.isArray(d.photos)) photos = d.photos;
    }
    if (dayDate && !dayDate.startsWith(todayIso)) continue;
    for (const p of photos) {
      if (!p || typeof p !== "object") continue;
      const photo = p as Record<string, unknown>;
      const path = [photo.path, photo.filePath, photo.fileName, photo.name].find(
        (v): v is string => typeof v === "string" && v.length > 0
      );
      if (!path) continue;
      out.push({
        path,
        category: typeof photo.category === "string" ? photo.category : "",
        caption: typeof photo.caption === "string" ? photo.caption : "",
      });
    }
  }
  return out;
}

type UnknownRecord = Record<string, unknown>;

/** Parse GET /api/structured/config into displayable fields; empty when unusable. */
export function parseStructFields(payload: unknown): StructFieldView[] {
  let raw: unknown[] = [];
  if (Array.isArray(payload)) raw = payload;
  else if (payload && typeof payload === "object") {
    const obj = payload as UnknownRecord;
    if (Array.isArray(obj.fields)) raw = obj.fields;
    else if (Array.isArray(obj.templates)) raw = obj.templates;
  }
  const fields: StructFieldView[] = [];
  for (const item of raw) {
    if (!item || typeof item !== "object") continue;
    const f = item as UnknownRecord;
    const id = [f.id, f.fieldId, f.key].find((v): v is string => typeof v === "string" && v.length > 0);
    if (!id) continue;
    const label = [f.name, f.text, f.title, f.label].find(
      (v): v is string => typeof v === "string" && v.trim().length > 0
    );
    const type = [f.type, f.valueType, f.inputType].find(
      (v): v is string => typeof v === "string" && v.length > 0
    );
    fields.push({ id, label: label ?? id, type: (type ?? "word").toLowerCase() });
  }
  return fields;
}

/** Latest rawValue per fieldId from GET /api/structured/records. */
export function latestStructValues(records: unknown): Map<string, string> {
  const list = Array.isArray(records) ? (records as UnknownRecord[]) : [];
  const map = new Map<string, string>();
  for (const r of list) {
    if (!r || typeof r !== "object") continue;
    const fieldId = typeof r.fieldId === "string" ? r.fieldId : "";
    const value = typeof r.rawValue === "string" ? r.rawValue : "";
    if (fieldId) map.set(fieldId, value);
  }
  return map;
}

function formatSyncFinishedAt(ts: number): string {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "short",
    timeStyle: "short",
  }).format(new Date(ts));
}

/* ------------------------------------------------------------------ */
/* Data loading                                                        */
/* ------------------------------------------------------------------ */

interface HomeDataBundle {
  diaries: DiaryDocument[];
  diaryStats: DiaryStats | null;
  thoughts: FlashThought[];
  categories: ThoughtCategory[];
  dateRecords: DateRecord[];
  poem: DailyPoem;
  /** true when /api/diary/documents itself failed (index-derived modules degrade). */
  diariesFailed: boolean;
}

function useHomeData(reloadKey: number): {
  data: HomeDataBundle | null;
  loading: boolean;
  error: unknown;
  reload: () => void;
} {
  const [data, setData] = useState<HomeDataBundle | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [tick, setTick] = useState(0);

  const reload = () => {
    setTick((t) => t + 1);
  };
  // Expose reloadKey changes through tick as well.
  useEffect(() => {
    if (reloadKey > 0) setTick((t) => t + 1);
  }, [reloadKey]);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    Promise.allSettled([
      apiGet<DiaryDocument[]>("/api/diary/documents"),
      apiGet<DiaryStats>("/api/diary/stats"),
      apiGet<FlashThought[]>("/api/thoughts"),
      apiGet<ThoughtCategory[]>("/api/thought-categories"),
      apiGet<DateRecord[]>("/api/date-records"),
      apiGet<DailyPoem>("/api/poetry/daily"),
    ]).then(([diaries, stats, thoughts, categories, dates, poem]) => {
      if (!alive) return;
      const failed = diaries.status === "rejected";
      setData({
        diaries: diaries.status === "fulfilled" ? (diaries.value ?? []) : [],
        diaryStats: stats.status === "fulfilled" ? stats.value : null,
        thoughts: thoughts.status === "fulfilled" ? (thoughts.value ?? []) : [],
        categories: categories.status === "fulfilled" ? (categories.value ?? []) : [],
        dateRecords: dates.status === "fulfilled" ? (dates.value ?? []) : [],
        poem: poem.status === "fulfilled" && poem.value?.content ? poem.value : FALLBACK_POEM,
        diariesFailed: failed,
      });
      if (
        diaries.status === "rejected" &&
        thoughts.status === "rejected" &&
        dates.status === "rejected"
      ) {
        setError(diaries.reason);
      }
      setLoading(false);
    });
    return () => {
      alive = false;
    };
  }, [tick]);

  return { data, loading, error, reload };
}

/* ------------------------------------------------------------------ */
/* Card chrome                                                         */
/* ------------------------------------------------------------------ */

function WidgetCard(props: {
  title: string;
  showTitle: boolean;
  showBorder: boolean;
  organic: boolean;
  marginBottom: number;
  children: React.ReactNode;
}) {
  const titleEl = props.showTitle ? (
    <div
      style={{
        fontWeight: 600,
        fontSize: "1.02em",
        marginBottom: 8,
        color: props.organic ? "var(--dc-on-surface)" : "var(--dc-primary)",
      }}
    >
      {props.title}
    </div>
  ) : null;
  if (props.showBorder) {
    return (
      <section className="dc-card" style={{ padding: 16, marginBottom: props.marginBottom, breakInside: "avoid" }}>
        {titleEl}
        <div className="dc-col" style={{ gap: 8 }}>{props.children}</div>
      </section>
    );
  }
  return (
    <section style={{ padding: "8px 16px", marginBottom: props.marginBottom, breakInside: "avoid" }}>
      {titleEl}
      <div className="dc-col" style={{ gap: 8 }}>{props.children}</div>
    </section>
  );
}

function MetricButton(props: { value: string; label: string; onClick: () => void }) {
  return (
    <button
      onClick={props.onClick}
      className="dc-col dc-center"
      style={{ background: "none", border: "none", padding: "4px 10px", gap: 2 }}
    >
      <span style={{ fontSize: "1.5em", fontWeight: 600 }}>{props.value}</span>
      <span className="dc-muted" style={{ fontSize: "0.82em" }}>{props.label}</span>
    </button>
  );
}

function HeadlineNumber(props: { value: string; primary?: boolean }) {
  return (
    <div
      style={{
        fontSize: "2em",
        fontWeight: 600,
        color: props.primary ? "var(--dc-primary)" : "var(--dc-on-surface)",
        lineHeight: 1.2,
      }}
    >
      {props.value}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Display widgets                                                     */
/* ------------------------------------------------------------------ */

function TodayWidget(props: { settings: AppSettings }) {
  const english = props.settings.appLanguage === "ENGLISH";
  const today = new Date();
  const text = new Intl.DateTimeFormat(english ? "en" : "zh-CN", {
    year: "numeric",
    month: english ? "long" : "long",
    day: "numeric",
    weekday: "long",
  }).format(today);
  return <div style={{ fontSize: "1.45em", fontWeight: 500 }}>{text}</div>;
}

function CalendarWidget(props: { organic: boolean }) {
  const english = uiLanguage() === "ENGLISH";
  const today = new Date();
  const year = today.getFullYear();
  const month = today.getMonth();
  const daysInMonth = new Date(year, month + 1, 0).getDate();
  const firstOffset = (new Date(year, month, 1).getDay() + 6) % 7; // Monday-first
  const cells: number[] = [
    ...Array.from({ length: firstOffset }, () => 0),
    ...Array.from({ length: daysInMonth }, (_, i) => i + 1),
  ];
  const title = english
    ? `${today.toLocaleString("en", { month: "long" })} ${year}`
    : `${year}年${month + 1}月`;
  const weekdays = english
    ? ["M", "T", "W", "T", "F", "S", "S"]
    : ["一", "二", "三", "四", "五", "六", "日"];
  return (
    <div>
      <div style={{ fontSize: "1.2em", fontWeight: 600, marginBottom: 6 }}>{title}</div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(7, 1fr)", rowGap: 2 }}>
        {weekdays.map((w, i) => (
          <div key={`w${i}`} className="dc-muted" style={{ textAlign: "center", fontSize: "0.85em" }}>{w}</div>
        ))}
        {cells.map((day, i) =>
          day === 0 ? (
            <div key={`e${i}`} />
          ) : (
            <div
              key={`d${i}`}
              style={{
                textAlign: "center",
                padding: "5px 0",
                borderRadius: 999,
                ...(props.organic && day === today.getDate()
                  ? { background: "var(--dc-secondary-container)", color: "var(--dc-on-secondary-container)" }
                  : day === today.getDate()
                    ? { color: "var(--dc-primary)", fontWeight: 700 }
                    : {}),
              }}
            >
              {day}
            </div>
          )
        )}
      </div>
    </div>
  );
}

function WeatherWidget() {
  return (
    <div className="dc-row" style={{ gap: 10 }}>
      <IconSun size={30} />
      <div className="dc-col" style={{ gap: 2 }}>
        <div>{tr("离线模式", "Offline")}</div>
        <div className="dc-muted" style={{ fontSize: "0.85em" }}>{tr("暂无上次天气缓存", "No cached weather")}</div>
      </div>
    </div>
  );
}

function YearProgressWidget() {
  const english = uiLanguage() === "ENGLISH";
  const today = new Date();
  const startOfYear = new Date(today.getFullYear(), 0, 1);
  const isLeap =
    (today.getFullYear() % 4 === 0 && today.getFullYear() % 100 !== 0) ||
    today.getFullYear() % 400 === 0;
  const total = isLeap ? 366 : 365;
  const dayOfYear = dayDiff(today, startOfYear) + 1;
  const progress = Math.min(dayOfYear / total, 1);
  const percent = Math.floor(progress * 100);
  return (
    <div className="dc-col" style={{ gap: 8 }}>
      <div>
        {english
          ? `${percent}% · day ${dayOfYear} / ${total}`
          : `${percent}% · 第 ${dayOfYear} / ${total} 天`}
      </div>
      <div
        role="progressbar"
        aria-valuenow={percent}
        aria-valuemin={0}
        aria-valuemax={100}
        style={{ height: 6, borderRadius: 999, background: "var(--dc-outline-variant)", overflow: "hidden" }}
      >
        <div style={{ width: `${progress * 100}%`, height: "100%", background: "var(--dc-primary)" }} />
      </div>
    </div>
  );
}

function PoemWidget(props: { poem: DailyPoem; showMessage: (m: string) => void }) {
  const [poem, setPoem] = useState<DailyPoem>(props.poem);
  const [refreshing, setRefreshing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [showFull, setShowFull] = useState(false);
  useEffect(() => setPoem(props.poem), [props.poem]);

  const refreshPoem = async () => {
    if (refreshing) return;
    setRefreshing(true);
    try {
      const next = await apiGet<DailyPoem>("/api/poetry/daily");
      if (next?.content) setPoem(next);
    } catch {
      props.showMessage(tr("诗词刷新失败，已保留当前内容", "Could not refresh the poem; the current poem was kept"));
    } finally {
      setRefreshing(false);
    }
  };

  const savePoem = async () => {
    if (saving) return;
    setSaving(true);
    try {
      await apiSend("/api/poetry/poems", "POST", {
        content: (poem.fullContent ?? "").trim() || poem.content,
        source: poem.source,
      });
      props.showMessage(tr("已加入诗词本", "Added to poetry book"));
    } catch {
      props.showMessage(tr("诗词保存失败", "Could not save the poem"));
    } finally {
      setSaving(false);
    }
  };

  const sourceLine = poem.dynasty ? `${poem.source}（${poem.dynasty}）` : poem.source;

  return (
    <>
      <div className="dc-row" style={{ alignItems: "stretch", gap: 4 }}>
        <button
          onClick={() => setShowFull(true)}
          className="dc-col dc-grow"
          style={{ background: "none", border: "none", padding: 0, textAlign: "left", gap: 4, minWidth: 0 }}
        >
          <span style={{ fontWeight: 600, fontSize: "1.08em", whiteSpace: "pre-wrap" }}>{poem.content}</span>
          <span className="dc-muted" style={{ fontSize: "0.88em" }}>{poem.source}</span>
        </button>
        <div className="dc-col" style={{ gap: 0 }}>
          <button className="dc-icon-btn" aria-label={tr("换一句", "Refresh poem")} disabled={refreshing} onClick={() => void refreshPoem()}>
            {refreshing ? <Spinner size={18} /> : <IconRefresh size={19} />}
          </button>
          <button className="dc-icon-btn" aria-label={tr("加入诗词本", "Save to poetry book")} disabled={saving} onClick={() => void savePoem()}>
            <IconSend size={18} />
          </button>
        </div>
      </div>
      <Modal open={showFull} onClose={() => setShowFull(false)} title={poem.title?.trim() || tr("诗词", "Poem")} width={480}>
        <div className="dc-col" style={{ gap: 12 }}>
          <div style={{ fontWeight: 600, fontSize: "1.1em", whiteSpace: "pre-wrap" }}>
            {(poem.fullContent ?? "").trim() || poem.content}
          </div>
          <div className="dc-muted" style={{ fontSize: "0.9em" }}>{sourceLine}</div>
          {!((poem.fullContent ?? "").trim()) && (
            <div className="dc-muted" style={{ fontSize: "0.85em" }}>
              {tr("完整内容会在下次刷新诗词时获取。", "The full poem is fetched the next time the poem refreshes.")}
            </div>
          )}
          <div className="dc-row" style={{ justifyContent: "flex-end" }}>
            <button className="dc-btn" onClick={() => setShowFull(false)}>{tr("关闭", "Close")}</button>
            <button className="dc-btn dc-btn-filled" onClick={() => { setShowFull(false); void savePoem(); }}>
              {tr("加入诗词本", "Save to poetry book")}
            </button>
          </div>
        </div>
      </Modal>
    </>
  );
}

function StreakWidget(props: { value: number }) {
  const english = uiLanguage() === "ENGLISH";
  return <HeadlineNumber primary value={`${props.value} ${english ? "days" : "天"}`} />;
}

function MonthDiariesWidget(props: { value: number }) {
  const english = uiLanguage() === "ENGLISH";
  return <HeadlineNumber value={`${props.value} ${english ? "entries" : "篇"}`} />;
}

function TotalWordsWidget(props: { value: number }) {
  return <HeadlineNumber value={String(props.value)} />;
}

function RecentDiaryWidget(props: { diaries: DiaryDocument[] }) {
  const navigate = useNavigate();
  if (props.diaries.length === 0) {
    return <div className="dc-muted">{tr("还没有日记", "No diaries yet")}</div>;
  }
  return (
    <div className="dc-col" style={{ gap: 2 }}>
      {props.diaries.slice(0, 3).map((item) => (
        <button
          key={item.name}
          className="dc-col"
          style={{ background: "none", border: "none", padding: "8px 0", textAlign: "left", gap: 2, width: "100%" }}
          onClick={() => navigate(`/diary/edit?name=${encodeURIComponent(item.name)}`)}
        >
          <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
            {item.name.replace(/\.md$/, "")}
          </span>
          <span className="dc-muted" style={{ fontSize: "0.82em" }}>{item.dateIso}</span>
        </button>
      ))}
    </div>
  );
}

function RandomDiaryWidget(props: { diaries: DiaryDocument[] }) {
  const navigate = useNavigate();
  const item = useMemo(() => {
    if (props.diaries.length === 0) return null;
    return props.diaries[Math.floor(Math.random() * props.diaries.length)];
    // Re-picked only when the diary list identity changes, mirroring remember(diaries).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.diaries]);
  if (!item) return <div className="dc-muted">{tr("还没有可回顾的日记", "No diary to revisit")}</div>;
  return (
    <button
      className="dc-btn"
      style={{ padding: "6px 0", textAlign: "left" }}
      onClick={() => navigate(`/diary/edit?name=${encodeURIComponent(item.name)}`)}
    >
      {item.name.replace(/\.md$/, "")}
    </button>
  );
}

function RecentThoughtWidget(props: { thoughts: FlashThought[] }) {
  const navigate = useNavigate();
  return (
    <div className="dc-col" style={{ gap: 6 }}>
      {props.thoughts.slice(0, 3).map((t) => (
        <div key={t.id} style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
          {t.content}
        </div>
      ))}
      <div>
        <button className="dc-btn" style={{ padding: "6px 0" }} onClick={() => navigate("/thought")}>
          {tr("查看全部", "View all")}
        </button>
      </div>
    </div>
  );
}

function QuickInputWidget(props: {
  showMessage: (m: string) => void;
  categories: ThoughtCategory[];
}) {
  const [value, setValue] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [pickerSnapshot, setPickerSnapshot] = useState<string | null>(null);
  const pressTimer = useRef<number | null>(null);
  const suppressClick = useRef(false);

  const submit = async (snapshot: string, categoryId: number | null) => {
    if (!snapshot || submitting) return;
    setSubmitting(true);
    try {
      await apiSend(
        "/api/thoughts",
        "POST",
        categoryId === null ? { content: snapshot } : { content: snapshot, categoryId }
      );
      setValue((current) => (current.trim() === snapshot ? "" : current));
    } catch {
      props.showMessage(tr("保存失败，请重试", "Could not save; try again"));
    } finally {
      setSubmitting(false);
    }
  };

  const cancelPress = () => {
    if (pressTimer.current !== null) {
      window.clearTimeout(pressTimer.current);
      pressTimer.current = null;
    }
  };
  useEffect(() => cancelPress, []);

  const onSendPointerDown = () => {
    if (!value.trim()) return; // long-press requires non-empty content, like Android
    cancelPress();
    pressTimer.current = window.setTimeout(() => {
      pressTimer.current = null;
      suppressClick.current = true;
      setPickerSnapshot(value.trim());
    }, 500);
  };
  const onSendClick = () => {
    if (suppressClick.current) {
      suppressClick.current = false;
      return;
    }
    void submit(value.trim(), null);
  };

  return (
    <form
      className="dc-row"
      onSubmit={(e) => {
        e.preventDefault();
        onSendClick();
      }}
    >
      <input
        className="dc-input dc-grow"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        placeholder={tr("记一条小巧思", "Write a thought")}
        aria-label={tr("记一条小巧思", "Write a thought")}
      />
      <button
        type="button"
        className="dc-icon-btn"
        aria-label={tr("发送", "Send")}
        title={tr("点按发送；长按选择分类", "Click to send; long-press to pick a category")}
        disabled={!value.trim() || submitting}
        onPointerDown={onSendPointerDown}
        onPointerUp={cancelPress}
        onPointerLeave={cancelPress}
        onPointerCancel={cancelPress}
        onContextMenu={(e) => e.preventDefault()}
        onClick={onSendClick}
        style={{
          background: value.trim() && !submitting ? "var(--dc-primary)" : "transparent",
          color: value.trim() && !submitting ? "var(--dc-on-primary)" : "var(--dc-on-surface-variant)",
        }}
      >
        {submitting ? <Spinner size={18} /> : <IconSend size={18} />}
      </button>

      <Modal
        open={pickerSnapshot !== null}
        onClose={() => setPickerSnapshot(null)}
        title={tr("选择分类并发送", "Choose a category and send")}
        width={380}
      >
        <div className="dc-col" style={{ gap: 2 }}>
          <button
            className="dc-row"
            style={{ background: "none", border: "none", padding: "12px 8px", gap: 10, textAlign: "left", width: "100%" }}
            onClick={() => {
              const snapshot = pickerSnapshot;
              setPickerSnapshot(null);
              if (snapshot) void submit(snapshot, null);
            }}
          >
            <span style={{ width: 14, height: 14, borderRadius: "50%", background: "var(--dc-outline)", flexShrink: 0 }} />
            {tr("未分类", "Unfiled")}
          </button>
          {props.categories.map((cat) => (
            <button
              key={cat.id}
              className="dc-row"
              style={{ background: "none", border: "none", padding: "12px 8px", gap: 10, textAlign: "left", width: "100%" }}
              onClick={() => {
                const snapshot = pickerSnapshot;
                setPickerSnapshot(null);
                if (snapshot) void submit(snapshot, cat.id);
              }}
            >
              <span
                style={{ width: 14, height: 14, borderRadius: "50%", background: argbToCss(cat.colorArgb), flexShrink: 0 }}
              />
              {cat.name}
            </button>
          ))}
          <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 10 }}>
            <button className="dc-btn" onClick={() => setPickerSnapshot(null)}>{tr("取消", "Cancel")}</button>
          </div>
        </div>
      </Modal>
    </form>
  );
}

function DateRecordsWidget(props: { records: DateRecord[] }) {
  const navigate = useNavigate();
  const today = new Date();

  const nearest = useMemo(() => {
    const parsed = props.records
      .map((record) => ({ record, date: parseIsoLocal(record.dateIso) }))
      .filter((r): r is { record: DateRecord; date: Date } => r.date !== null);
    const upcoming = parsed
      .filter((r) => dayDiff(r.date, today) >= 0)
      .sort((a, b) => dayDiff(a.date, today) - dayDiff(b.date, today) || a.record.id - b.record.id)
      .slice(0, 2);
    const past = parsed
      .filter((r) => dayDiff(r.date, today) < 0)
      .sort((a, b) => dayDiff(b.date, today) - dayDiff(a.date, today) || a.record.id - b.record.id)
      .slice(0, 2);
    return [...upcoming, ...past];
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.records]);

  const distanceText = (name: string, date: Date): string => {
    const days = dayDiff(date, today);
    if (days < 0) return tr(`距离 ${name} 已经过去 ${-days} 天`, `${-days} days since ${name}`);
    if (days > 0) return tr(`还有 ${days} 天到 ${name}`, `${days} days until ${name}`);
    return tr(`今天就是 ${name}`, `${name} is today`);
  };

  if (nearest.length === 0) {
    return (
      <div className="dc-col" style={{ gap: 8 }}>
        <div className="dc-muted">{tr("还没有日期记录", "No date records yet")}</div>
        <div>
          <button className="dc-btn dc-btn-filled" onClick={() => navigate("/date_records")}>
            {tr("添加目标日期", "Add a target date")}
          </button>
        </div>
      </div>
    );
  }
  return (
    <div className="dc-col" style={{ gap: 2 }}>
      {nearest.map(({ record, date }) => (
        <button
          key={record.id}
          className="dc-row"
          style={{ background: "none", border: "none", padding: "6px 0", textAlign: "left", width: "100%", gap: 10 }}
          onClick={() => navigate("/date_records")}
        >
          <span style={{ fontSize: "1.3em", width: 36, textAlign: "center", flexShrink: 0 }}>
            {record.icon?.trim() ? record.icon : "🎯"}
          </span>
          <span className="dc-col dc-grow" style={{ gap: 1, minWidth: 0 }}>
            <span>{distanceText(record.name, date)}</span>
            <span className="dc-muted" style={{ fontSize: "0.82em" }}>{record.dateIso}</span>
          </span>
        </button>
      ))}
      <div>
        <button className="dc-btn" style={{ padding: "6px 0" }} onClick={() => navigate("/date_records")}>
          {tr("查看全部", "View all")}
        </button>
      </div>
    </div>
  );
}

function WebsiteWidget(props: { settings: AppSettings }) {
  const navigate = useNavigate();
  return (
    <button className="dc-chip" onClick={() => navigate("/blog")} title={props.settings.browserHomeUrl}>
      <IconGlobe size={15} />
      <span style={{ maxWidth: 260, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
        {props.settings.browserHomeUrl}
      </span>
    </button>
  );
}

function NotesWidget() {
  const navigate = useNavigate();
  return (
    <div className="dc-row" style={{ gap: 10 }}>
      <IconFile size={22} />
      <span className="dc-grow dc-muted">
        {tr("打开 Obsidian 兼容的 Markdown 笔记库", "Open your Obsidian-compatible Markdown vault")}
      </span>
      <button className="dc-btn" onClick={() => navigate("/notes")}>{tr("打开", "Open")}</button>
    </div>
  );
}

function GameShortcutsWidget(props: { settings: AppSettings }) {
  const navigate = useNavigate();
  const english = props.settings.appLanguage === "ENGLISH";
  const shortcuts = HOME_GAME_SHORTCUTS.filter((g) => props.settings.homeGameShortcuts.includes(g.id));
  if (shortcuts.length === 0) {
    return (
      <button className="dc-btn dc-muted" style={{ padding: "6px 0", textAlign: "left" }} onClick={() => navigate("/settings")}>
        {tr(
          "可在“设置 → 子页面设置 → 主页”选择快捷入口",
          "Choose shortcuts in Settings → Subpage settings → Home"
        )}
      </button>
    );
  }
  return (
    <div className="dc-row dc-wrap" style={{ gap: 4 }}>
      {shortcuts.map((g) => (
        <button key={g.id} className="dc-btn" style={{ padding: "6px 10px" }} onClick={() => navigate(GAME_ROUTES[g.id] ?? "/games")}>
          {english ? g.en : g.zh}
        </button>
      ))}
    </div>
  );
}

function RecordOverviewWidget(props: {
  diaryCount: number;
  thoughtCount: number;
  dateCount: number;
}) {
  const navigate = useNavigate();
  return (
    <div className="dc-col" style={{ gap: 8 }}>
      <div className="dc-row" style={{ justifyContent: "space-evenly", flexWrap: "wrap", gap: 4 }}>
        <MetricButton value={String(props.diaryCount)} label={tr("日记", "Diaries")} onClick={() => navigate("/diary")} />
        <MetricButton value={String(props.thoughtCount)} label={tr("小巧思", "Thoughts")} onClick={() => navigate("/thought")} />
        <MetricButton value={String(props.dateCount)} label={tr("日期", "Dates")} onClick={() => navigate("/date_records")} />
      </div>
      <div className="dc-row" style={{ justifyContent: "flex-end" }}>
        <button className="dc-btn" onClick={() => navigate("/statistics")}>
          {tr("查看统计", "View statistics")}
        </button>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Meal photos widget                                                  */
/* ------------------------------------------------------------------ */

function MealPhotosWidget(props: { settings: AppSettings; showMessage: (m: string) => void }) {
  const english = props.settings.appLanguage === "ENGLISH";
  const [busy, setBusy] = useState(false);
  const [pendingCategory, setPendingCategory] = useState<string | null>(null);
  const [thumbs, setThumbs] = useState<MealPhotoThumb[]>([]);
  const fileRef = useRef<HTMLInputElement>(null);

  const loadThumbs = React.useCallback(async () => {
    const todayIso = isoDate(new Date());
    try {
      const payload = await apiGet<unknown>(`/api/meals/calendar?from=${todayIso}&to=${todayIso}`);
      setThumbs(extractPhotosForDay(payload, todayIso).slice(0, 6));
    } catch {
      setThumbs([]);
    }
  }, []);
  useEffect(() => {
    void loadThumbs();
  }, [loadThumbs]);

  const startPick = (key: string) => {
    if (busy) return;
    setPendingCategory(key);
    fileRef.current?.click();
  };

  const handleFile = async (file: File | undefined) => {
    const key = pendingCategory;
    setPendingCategory(null);
    if (!file || !key || busy) return;
    const cat = MEAL_CATEGORIES.find((c) => c.key === key);
    const label = cat ? (english ? cat.en : cat.zh) : key;
    setBusy(true); // busy covers only the real upload + diary save window
    try {
      const imported = await apiUpload<ImportedMedia>(
        `/api/media/upload?category=${encodeURIComponent(key)}`,
        file
      );
      await appendToTodayDiary(imported.markdown ?? "");
      props.showMessage(
        tr(`${label}图片已加入今日日记`, `${label} photo added to today's diary`)
      );
      void loadThumbs();
    } catch (error) {
      const base = tr("图片添加失败", "Could not add the photo");
      const msg = error instanceof Error && error.message ? error.message : "";
      props.showMessage(msg ? `${base}: ${msg}` : base);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="dc-col" style={{ gap: 10 }}>
      <div className="dc-row" style={{ gap: 6, alignItems: "stretch" }}>
        {MEAL_CATEGORIES.map((cat) => {
          const customIcon = props.settings.mealButtonIcons[cat.sortOrder]?.trim();
          const display = props.settings.mealButtonsUseIcons ? (customIcon || cat.icon) : english ? cat.en : cat.zh;
          return (
            <button
              key={cat.key}
              disabled={busy}
              aria-label={`${english ? cat.en : cat.zh}`}
              onClick={() => startPick(cat.key)}
              style={{
                flex: 1,
                minWidth: 0,
                minHeight: 48,
                border: "none",
                borderRadius: "calc(var(--dc-radius) * 0.6)",
                background: "var(--dc-secondary-container)",
                color: "var(--dc-on-secondary-container)",
                fontSize: props.settings.mealButtonsUseIcons ? "1.4em" : "0.82em",
                padding: "4px 2px",
                opacity: busy ? 0.6 : 1,
              }}
            >
              {display}
            </button>
          );
        })}
      </div>
      {thumbs.length > 0 && (
        <div className="dc-row dc-wrap" style={{ gap: 8 }}>
          {thumbs.map((t, i) => (
            <img
              key={`${t.path}-${i}`}
              src={mediaThumbUrl(t.path)}
              alt={t.caption || t.category || tr("今日照片", "Today's photo")}
              title={t.caption || t.category}
              loading="lazy"
              style={{ width: 56, height: 56, objectFit: "cover", borderRadius: 8 }}
            />
          ))}
        </div>
      )}
      <input
        ref={fileRef}
        type="file"
        accept="image/*"
        style={{ display: "none" }}
        onChange={(e) => {
          const f = e.target.files?.[0];
          e.target.value = "";
          void handleFile(f);
        }}
      />
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Structured (daily) records widget                                   */
/* ------------------------------------------------------------------ */

function structInputType(type: string): "number" | "time" | "text" {
  if (type.includes("number")) return "number";
  if (type.includes("time") && !type.includes("runtime")) return "time";
  return "text";
}

function DailyRecordsWidget() {
  const navigate = useNavigate();
  const todayIso = isoDate(new Date());
  const [fields, setFields] = useState<StructFieldView[] | null>(null);
  const [configError, setConfigError] = useState<unknown>(null);
  const [values, setValues] = useState<Map<string, string>>(new Map());
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [sending, setSending] = useState<Record<string, boolean>>({});
  const [submitError, setSubmitError] = useState<unknown>(null);

  const loadAll = React.useCallback(async () => {
    let parsed: StructFieldView[] = [];
    try {
      parsed = parseStructFields(await apiGet<unknown>("/api/structured/config"));
      setConfigError(null);
    } catch (e) {
      setConfigError(e);
    }
    setFields(parsed);
    try {
      const recs = await apiGet<unknown>(
        `/api/structured/records?fromDay=${todayIso}&toDay=${todayIso}`
      );
      setValues(latestStructValues(recs));
    } catch {
      /* today's values stay empty */
    }
  }, [todayIso]);
  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  const submit = async (field: StructFieldView) => {
    const raw = (drafts[field.id] ?? "").trim();
    if (!raw || sending[field.id]) return;
    setSending((s) => ({ ...s, [field.id]: true }));
    setSubmitError(null);
    try {
      await apiSend("/api/structured/records", "POST", {
        journalDay: todayIso,
        fieldId: field.id,
        rawValue: raw,
      });
      setDrafts((d) => {
        const next = { ...d };
        delete next[field.id];
        return next;
      });
      try {
        const recs = await apiGet<unknown>(
          `/api/structured/records?fromDay=${todayIso}&toDay=${todayIso}`
        );
        setValues(latestStructValues(recs));
      } catch {
        /* keep previous values */
      }
    } catch (e) {
      setSubmitError(e);
    } finally {
      setSending((s) => ({ ...s, [field.id]: false }));
    }
  };

  if (fields === null) {
    return <Spinner size={20} />;
  }

  if (fields.length === 0) {
    return (
      <div className="dc-col" style={{ gap: 8 }}>
        <div className="dc-row" style={{ gap: 10 }}>
          <IconFile size={20} />
          <span className="dc-grow dc-muted">{tr("还没有结构化记录", "No structured records yet")}</span>
          <button className="dc-btn" onClick={() => navigate("/daily")}>{tr("添加", "Add")}</button>
        </div>
        {configError ? <ErrorText error={configError} /> : null}
      </div>
    );
  }

  return (
    <div className="dc-col" style={{ gap: 12 }}>
      {(fields ?? []).map((field) => {
        const existing = values.get(field.id) ?? "";
        const value = drafts[field.id] ?? "";
        const isSending = !!sending[field.id];
        return (
          <div key={field.id} className="dc-col" style={{ gap: 4 }}>
            <div className="dc-row" style={{ gap: 8 }}>
              <span className="dc-muted" style={{ fontSize: "0.88em", flexShrink: 0 }}>{field.label}</span>
              {existing && (
                <span className="dc-chip" style={{ fontSize: "0.78em" }} title={existing}>
                  {existing.length > 24 ? `${existing.slice(0, 24)}…` : existing}
                </span>
              )}
            </div>
            <div className="dc-row" style={{ gap: 8 }}>
              <input
                className="dc-input dc-grow"
                type={structInputType(field.type)}
                value={value}
                maxLength={100}
                disabled={isSending}
                aria-label={field.label}
                onChange={(e) => setDrafts((d) => ({ ...d, [field.id]: e.target.value }))}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault();
                    void submit(field);
                  }
                }}
              />
              <button
                className="dc-icon-btn"
                aria-label={`${tr("发送", "Send")} ${field.label}`}
                disabled={!value.trim() || isSending}
                onClick={() => void submit(field)}
                style={{
                  background: value.trim() && !isSending ? "var(--dc-primary)" : "transparent",
                  color: value.trim() && !isSending ? "var(--dc-on-primary)" : "var(--dc-on-surface-variant)",
                  flexShrink: 0,
                }}
              >
                {isSending ? <Spinner size={16} /> : <IconSend size={16} />}
              </button>
            </div>
          </div>
        );
      })}
      <ErrorText error={submitError} />
      <div className="dc-row" style={{ justifyContent: "flex-end" }}>
        <button className="dc-btn" onClick={() => navigate("/daily")}>
          {tr("管理结构化记录", "Manage structured records")}
        </button>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Cloud sync widgets                                                  */
/* ------------------------------------------------------------------ */

type CloudSyncMode = "now" | "force_upload" | "force_download";

function CloudSyncHomeWidget(props: {
  forceActions: boolean;
  settings: AppSettings;
  showMessage: (m: string) => void;
}) {
  const navigate = useNavigate();
  const [status, setStatus] = useState<CloudSyncStatus | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [acting, setActing] = useState<CloudSyncMode | null>(null);
  const [confirmMode, setConfirmMode] = useState<Extract<CloudSyncMode, "force_upload" | "force_download"> | null>(null);

  const load = React.useCallback(async () => {
    try {
      setStatus(await apiGet<CloudSyncStatus>("/api/cloudsync/status"));
      setLoadFailed(false);
    } catch {
      setStatus(null);
      setLoadFailed(true);
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);

  const enabledConfigs = (status?.configs ?? []).filter((c) => c.enabled !== false);
  const configured = !loadFailed && !!status && (status.configs ?? []).length > 0;
  const running = status?.running === true;
  const canRun = props.settings.cloudSyncEnabled && enabledConfigs.length > 0 && !running && !acting;

  const run = async (mode: CloudSyncMode) => {
    if (acting || enabledConfigs.length === 0) return;
    setActing(mode);
    try {
      for (const c of enabledConfigs) {
        await apiSend("/api/cloudsync/sync", "POST", { configId: c.id, mode });
      }
    } catch (error) {
      const base = tr("同步失败", "Sync failed");
      const msg = error instanceof Error && error.message ? error.message : "";
      props.showMessage(msg ? `${base}: ${msg}` : base);
    } finally {
      setActing(null);
      void load();
    }
  };

  const progress = status?.progress ?? null;
  let statusText: string;
  let isError = false;
  if (acting) {
    statusText = tr("正在同步", "Syncing");
  } else if (running) {
    const total = progress?.totalObjects ?? 0;
    statusText =
      total > 0
        ? tr(`正在同步 ${progress?.completedObjects ?? 0}/${total}`, `Syncing ${progress?.completedObjects ?? 0}/${total}`)
        : tr("正在同步", "Syncing");
  } else if (!props.settings.cloudSyncEnabled) {
    statusText = tr("云端同步尚未开启", "Cloud sync is turned off");
  } else if (enabledConfigs.length === 0) {
    statusText = tr("没有已启用的同步服务", "No sync service is enabled");
  } else if (status?.error) {
    statusText = String(status.error);
    isError = true;
  } else if (status?.message) {
    statusText = String(status.message);
  } else {
    statusText = tr("已就绪", "Ready");
  }

  const uploaded = status?.lastUploadedCount;
  const downloaded = status?.lastDownloadedCount;
  const conflicts = status?.lastConflictCount;
  const hasCounts =
    typeof uploaded === "number" && typeof downloaded === "number" && typeof conflicts === "number";

  const needsSetup = !configured || !props.settings.cloudSyncEnabled || enabledConfigs.length === 0;

  return (
    <div className="dc-col" style={{ gap: 8 }}>
      <div className="dc-muted" style={{ fontSize: "0.85em" }}>
        {props.forceActions
          ? tr(
              "仅在明确需要以本机或云端为准时使用；不会传播删除，仍会保护并发修改。",
              "Use only when explicitly choosing local or cloud data; deletions are not propagated and concurrent edits remain protected."
            )
          : tr(
              "按已保存的同步方向安全合并所有已启用来源。",
              "Safely merge every enabled source using its saved sync direction."
            )}
      </div>

      <div style={{ color: isError ? "var(--dc-error)" : "var(--dc-on-surface)" }}>{statusText}</div>
      {(running || acting) && (
        <div style={{ height: 4, borderRadius: 999, background: "var(--dc-outline-variant)", overflow: "hidden" }}>
          <div className="home-sync-progress" style={{ width: "40%", height: "100%", background: "var(--dc-primary)", borderRadius: 999 }} />
          <style>{`@keyframes home-sync-slide { 0% { transform: translateX(-100%);} 100% { transform: translateX(350%);} }
            .home-sync-progress { animation: home-sync-slide 1.2s linear infinite; }`}</style>
        </div>
      )}

      {needsSetup && (
        <div>
          <button className="dc-btn" onClick={() => navigate("/settings?section=cloudsync")}>
            {tr("未配置，前往设置", "Not configured — open Settings")}
          </button>
        </div>
      )}

      {!needsSetup && (
        <div className="dc-row dc-wrap" style={{ gap: 8 }}>
          {props.forceActions ? (
            <>
              <button className="dc-btn dc-btn-tonal" disabled={!canRun} onClick={() => setConfirmMode("force_upload")}>
                {tr("强制上传", "Force upload")}
              </button>
              <button
                className="dc-btn dc-btn-tonal"
                disabled={!canRun || enabledConfigs.length !== 1}
                onClick={() => setConfirmMode("force_download")}
              >
                {tr("强制下载", "Force download")}
              </button>
            </>
          ) : (
            <button className="dc-btn dc-btn-filled" disabled={!canRun} onClick={() => void run("now")}>
              {tr("立即同步", "Sync now")}
            </button>
          )}
        </div>
      )}

      {props.forceActions && props.settings.cloudSyncEnabled && enabledConfigs.length !== 1 && (
        <div className="dc-muted" style={{ fontSize: "0.85em" }}>
          {tr("强制下载需要恰好一个已启用的云端来源", "Force download requires exactly one enabled cloud source")}
        </div>
      )}

      {typeof status?.lastFinishedAt === "number" && (
        <div className="dc-muted" style={{ fontSize: "0.85em" }}>
          {tr(`上次完成：${formatSyncFinishedAt(status.lastFinishedAt)}`, `Last completed: ${formatSyncFinishedAt(status.lastFinishedAt)}`)}
        </div>
      )}
      {hasCounts && (
        <div className="dc-muted" style={{ fontSize: "0.85em" }}>
          {tr(
            `上次：上传 ${uploaded}，下载 ${downloaded}，冲突 ${conflicts}`,
            `Last: ${uploaded} uploaded, ${downloaded} downloaded, ${conflicts} conflicts`
          )}
        </div>
      )}

      <ConfirmDialog
        open={confirmMode !== null}
        title={
          confirmMode === "force_upload"
            ? tr("确认强制上传？", "Force upload?")
            : tr("确认强制下载？", "Force download?")
        }
        message={
          confirmMode === "force_upload"
            ? tr(
                "同路径内容不同时将以本机版本覆盖远端，但不会删除远端独有项目；并发远端修改仍会阻止覆盖。",
                "Different items at the same path use the local version. Remote-only items are kept, and concurrent remote edits still stop the overwrite."
              )
            : tr(
                "同路径内容不同时将采用唯一云端来源的版本，但不会删除本机独有项目；并发本机修改仍会保留。",
                "Different items at the same path use the single cloud source. Local-only items are kept, and concurrent local edits are still preserved."
              )
        }
        confirmLabel={confirmMode === "force_upload" ? tr("强制上传", "Force upload") : tr("强制下载", "Force download")}
        onCancel={() => setConfirmMode(null)}
        onConfirm={() => {
          const mode = confirmMode;
          setConfirmMode(null);
          if (mode) void run(mode);
        }}
      />
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Page                                                                */
/* ------------------------------------------------------------------ */

export default function HomePage() {
  const settingsState = useSettings();
  const settings = settingsState.settings;
  const [message, showMessage] = useSnackbar();
  const [reloadKey, setReloadKey] = useState(0);
  const { data, loading, error, reload } = useHomeData(reloadKey);
  const reloadRef = useRef(reload);
  reloadRef.current = reload;

  /* Layout-edit mode: long-press a card (or its hover grip) to enter; drag
     handles / arrow buttons reorder; every change persists homeWidgets. */
  const [editMode, setEditMode] = useState(false);
  const [draftOrder, setDraftOrder] = useState<string[] | null>(null);
  const [dragOverIndex, setDragOverIndex] = useState<number | null>(null);
  const pressTimer = useRef<number | null>(null);
  const pressStart = useRef<{ x: number; y: number } | null>(null);
  const dragFrom = useRef<number | null>(null);

  useEffect(() => {
    if (!editMode) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setEditMode(false);
        setDraftOrder(null);
        setDragOverIndex(null);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [editMode]);

  if (!settings) {
    return <div className="dc-center" style={{ padding: 48 }}><Spinner /></div>;
  }

  const configuredOrder = settings.homeWidgets ?? [];
  const order = editMode && draftOrder ? draftOrder : configuredOrder;

  const enterEditMode = () => {
    setDraftOrder(configuredOrder);
    setEditMode(true);
  };
  const exitEditMode = () => {
    setEditMode(false);
    setDraftOrder(null);
    setDragOverIndex(null);
  };
  const persistOrder = (next: string[]) => {
    setDraftOrder(next);
    settingsState.update({ homeWidgets: next }).catch(() => {
      showMessage(tr("排序保存失败", "Could not save the new order"));
    });
  };
  const moveWidget = (from: number, to: number) => {
    const current = editMode ? draftOrder : null;
    if (!current || from === to || to < 0 || to >= current.length) return;
    const next = [...current];
    const [item] = next.splice(from, 1);
    next.splice(to, 0, item);
    persistOrder(next);
  };

  const cancelPress = () => {
    if (pressTimer.current !== null) {
      window.clearTimeout(pressTimer.current);
      pressTimer.current = null;
    }
    pressStart.current = null;
  };
  const onCardPointerDown = (e: React.PointerEvent) => {
    if (editMode) return;
    const target = e.target as HTMLElement | null;
    if (target?.closest("button,input,a,textarea,select,label")) return;
    pressStart.current = { x: e.clientX, y: e.clientY };
    pressTimer.current = window.setTimeout(() => {
      pressTimer.current = null;
      enterEditMode();
    }, 500);
  };
  const onCardPointerMove = (e: React.PointerEvent) => {
    if (pressTimer.current === null) return;
    const s = pressStart.current;
    if (s && Math.hypot(e.clientX - s.x, e.clientY - s.y) > 8) cancelPress();
  };

  const organic = settings.visualStyle === "ORGANIC_FUTURE";
  const english = settings.appLanguage === "ENGLISH";
  const showBorders = settings.homeWidgetBordersEnabled;
  const gap = !showBorders ? 0 : settings.compactMode ? 6 : 12;

  const greeting = greetingForDate(new Date(), english, settings.userName, settings.homeGreetings ?? []);

  const stats = data?.diaryStats ?? null;
  const diaries = data?.diaries ?? [];
  const monthPrefix = isoDate(new Date()).slice(0, 7);
  const monthCount = stats?.monthCount ?? diaries.filter((d) => d.dateIso.startsWith(monthPrefix)).length;
  const totalWords = stats?.totalWords ?? diaries.reduce((sum, d) => sum + (d.wordCount ?? 0), 0);
  const streak = stats?.streakDays ?? streakDaysFromDocs(diaries, new Date());

  const renderWidget = (id: string): React.ReactNode => {
    const card = (title: string, showTitle: boolean, children: React.ReactNode) => (
      <WidgetCard title={title} showTitle={showTitle} showBorder={showBorders} organic={organic} marginBottom={gap}>
        {children}
      </WidgetCard>
    );
    const titled = settings.homeWidgetTitles.includes(id);
    switch (id) {
      case "today":
        return card(tr("今天", "Today"), titled, <TodayWidget settings={settings} />);
      case "calendar":
        return card(tr("日历", "Calendar"), titled, <CalendarWidget organic={organic} />);
      case "weather":
        return card(tr("天气", "Weather"), titled, <WeatherWidget />);
      case "poem":
        return card(tr("每日诗词", "Daily poem"), titled, data ? <PoemWidget poem={data.poem} showMessage={showMessage} /> : <Spinner size={20} />);
      case "streak":
        return card(tr("连续记录", "Writing streak"), titled, <StreakWidget value={streak} />);
      case "month_diaries":
        return card(tr("本月日记", "Diaries this month"), titled, <MonthDiariesWidget value={monthCount} />);
      case "total_words":
        return card(tr("日记总字数", "Total diary words"), titled, <TotalWordsWidget value={totalWords} />);
      case "recent_diary":
        return card(
          tr("最近日记", "Recent diary"), titled,
          data?.diariesFailed
            ? <div className="dc-muted">{tr("暂时无法加载日记", "Could not load diaries right now")}</div>
            : <RecentDiaryWidget diaries={diaries} />
        );
      case "random_diary":
        return card(
          tr("随机旧日记", "Random old diary"), titled,
          data?.diariesFailed
            ? <div className="dc-muted">{tr("暂时无法加载日记", "Could not load diaries right now")}</div>
            : <RandomDiaryWidget diaries={diaries} />
        );
      case "recent_thought":
        return card(
          tr("最近小巧思", "Recent thoughts"), titled,
          data ? <RecentThoughtWidget thoughts={data.thoughts} /> : <Spinner size={20} />
        );
      case "quick_input":
        return card(
          tr("快速输入", "Quick input"), titled,
          <QuickInputWidget showMessage={showMessage} categories={data?.categories ?? []} />
        );
      case "date_records":
        return card(tr("日期记录", "Date records"), titled, data ? <DateRecordsWidget records={data.dateRecords} /> : <Spinner size={20} />);
      case "year_progress":
        return card(tr("年度进度", "Year progress"), titled, <YearProgressWidget />);
      case "website":
        return card(tr("网站快捷入口", "Website shortcut"), titled, <WebsiteWidget settings={settings} />);
      case "notes":
        return card(tr("笔记", "Notes"), titled, <NotesWidget />);
      case "game_shortcuts":
        return card(tr("小游戏", "Mini games"), titled, <GameShortcutsWidget settings={settings} />);
      case "record_overview":
        return card(
          tr("记录概览", "Record overview"), titled,
          <RecordOverviewWidget
            diaryCount={diaries.length}
            thoughtCount={data?.thoughts.length ?? 0}
            dateCount={data?.dateRecords.length ?? 0}
          />
        );
      case "daily_records":
        return card(tr("结构化记录", "Structured records"), titled, <DailyRecordsWidget />);
      case "meal_photos":
        return card(
          tr("饮食图片", "Meal photos"), titled,
          <MealPhotosWidget settings={settings} showMessage={showMessage} />
        );
      case "cloud_sync_now":
        return card(
          tr("立即同步", "Sync now"), titled,
          <CloudSyncHomeWidget forceActions={false} settings={settings} showMessage={showMessage} />
        );
      case "cloud_sync_force":
        return card(
          tr("强制上传 / 下载", "Force upload / download"), titled,
          <CloudSyncHomeWidget forceActions={true} settings={settings} showMessage={showMessage} />
        );
      default:
        return null;
    }
  };

  return (
    <div>
      {/* Greeting header: pure display, wraps freely, no subtitle. */}
      <header style={{ padding: "14px 4px 10px" }}>
        <div style={{ fontSize: "1.05em", lineHeight: 1.5, overflowWrap: "anywhere" }}>{greeting}</div>
      </header>

      {error ? (
        <div className="dc-row" style={{ padding: "4px 4px 10px", flexWrap: "wrap", gap: 8 }}>
          <ErrorText error={error} />
          <button
            className="dc-btn"
            onClick={() => {
              setReloadKey((k) => k + 1);
              reloadRef.current();
            }}
          >
            {tr("重试", "Retry")}
          </button>
        </div>
      ) : null}

      {editMode && (
        <div className="dc-row" style={{ padding: "0 4px 8px", gap: 8 }}>
          <span className="dc-muted dc-grow" style={{ fontSize: "0.9em" }}>
            {tr("拖动模块或使用箭头调整顺序", "Drag modules or use the arrows to reorder")}
          </span>
          <button className="dc-btn dc-btn-filled" onClick={exitEditMode}>
            <IconCheck size={17} />
            {tr("完成", "Done")}
          </button>
        </div>
      )}

      {loading || !data ? (
        <Spinner />
      ) : (
        <div className="home-widgets">
          <style>{`
            @media (min-width: 900px) { .home-widgets { column-count: 2; column-gap: 14px; } }
            .home-grip {
              opacity: 0; transition: opacity 0.15s ease;
              position: absolute; top: 4px; right: 4px; z-index: 2;
              width: 30px !important; height: 30px !important;
              background: var(--dc-surface-container-high);
              cursor: ${editMode ? "grab" : "pointer"};
            }
            .home-card:hover .home-grip, .home-card.editing .home-grip { opacity: 1; }
          `}</style>
          {order.map((id, index) => (
            <div
              key={id}
              className={`home-card${editMode ? " editing" : ""}`}
              style={{
                position: "relative",
                borderRadius: 8,
                outline:
                  editMode && dragOverIndex === index
                    ? "2px dashed var(--dc-primary)"
                    : undefined,
                outlineOffset: 2,
              }}
              onPointerDown={onCardPointerDown}
              onPointerUp={cancelPress}
              onPointerLeave={cancelPress}
              onPointerMove={onCardPointerMove}
              onPointerCancel={cancelPress}
              onContextMenu={(e) => {
                if (!editMode) e.preventDefault();
              }}
              draggable={editMode}
              onDragStart={(e) => {
                dragFrom.current = index;
                e.dataTransfer.effectAllowed = "move";
                e.dataTransfer.setData("text/plain", String(index));
              }}
              onDragOver={(e) => {
                if (!editMode) return;
                e.preventDefault();
                e.dataTransfer.dropEffect = "move";
                setDragOverIndex(index);
              }}
              onDragLeave={() => setDragOverIndex((cur) => (cur === index ? null : cur))}
              onDrop={(e) => {
                e.preventDefault();
                const from = dragFrom.current;
                dragFrom.current = null;
                setDragOverIndex(null);
                if (from !== null) moveWidget(from, index);
              }}
              onDragEnd={() => {
                dragFrom.current = null;
                setDragOverIndex(null);
              }}
            >
              <button
                className="home-grip dc-icon-btn"
                aria-label={
                  editMode
                    ? tr("拖动以排序", "Drag to reorder")
                    : tr("调整主页模块布局", "Edit home layout")
                }
                title={editMode ? tr("拖动以排序", "Drag to reorder") : tr("长按或点此进入布局编辑", "Long-press or click to edit layout")}
                draggable={editMode}
                onDragStart={(e) => {
                  dragFrom.current = index;
                  e.dataTransfer.effectAllowed = "move";
                  e.dataTransfer.setData("text/plain", String(index));
                }}
                onClick={() => {
                  if (!editMode) enterEditMode();
                }}
              >
                <IconGrip size={17} />
              </button>
              {editMode && (
                <div
                  className="dc-col"
                  style={{ position: "absolute", top: 4, right: 42, zIndex: 2, gap: 0 }}
                >
                  <button
                    className="dc-icon-btn"
                    style={{ width: 28, height: 28 }}
                    aria-label={tr("上移", "Move up")}
                    disabled={index === 0}
                    onClick={() => moveWidget(index, index - 1)}
                  >
                    <IconChevronUp size={16} />
                  </button>
                  <button
                    className="dc-icon-btn"
                    style={{ width: 28, height: 28 }}
                    aria-label={tr("下移", "Move down")}
                    disabled={index === order.length - 1}
                    onClick={() => moveWidget(index, index + 1)}
                  >
                    <IconChevronDown size={16} />
                  </button>
                </div>
              )}
              <div style={{ pointerEvents: editMode ? "none" : undefined }}>
                {renderWidget(id)}
              </div>
            </div>
          ))}
        </div>
      )}
      <Snackbar message={message} />
      <PageTutorialOverlay
        pageKey="home"
        title={tr("首页", "Home")}
        lines={[tr("首页模块可在设置→主页设置调整。", "Home modules can be adjusted in Settings → Home settings.")]}
      />
    </div>
  );
}
