/**
 * DeskPage — web replication of Android ui/desk/DeskScreen.kt (+ model/components).
 *
 * A personal "desk" for today: the date itself is the page title; today's diary
 * becomes a paper object, today's thoughts become idea slips, today's meal
 * photos become prints, and everything recorded today is listed typographically
 * under "Today Traces". ✦ opens a lightweight AI overlay; "+" expands quick
 * capture. Empty days stay intentionally blank. A very subtle ambient tint
 * follows morning/afternoon/evening/late-night.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { apiGet, apiSend, apiUpload } from "../../api/client";
import { useSettings } from "../../stores/settings";
import { tr, localeTag } from "../../i18n/tr";
import { Modal, PageTutorialOverlay, Snackbar, Spinner, useSnackbar } from "../../components/ui";
import { appendToTodayDiary, ensureTodayDiaryName, extractPhotosForDay } from "../home/HomePage";

/* ------------------------------------------------------------------ */
/* API shapes (mirror Room entities, camelCase)                        */
/* ------------------------------------------------------------------ */

interface DiaryDocument {
  name: string;
  dateIso: string;
  lastModified: number;
  wordCount: number;
}

interface DiaryEditorDocument {
  content: string;
  sha256: string;
}

interface FlashThought {
  id: number;
  content: string;
  createdAt: number;
  highlighted?: boolean;
}

interface DateRecord {
  id: number;
  name: string;
  icon: string;
  dateIso: string;
  createdAt: number;
}

interface PhotoThumb {
  path: string;
  category: string;
  caption: string;
}

interface StructRow {
  fieldId?: string;
  rawValue?: string;
  createdAt?: number;
  timestamp?: number;
  recordedAt?: number;
}

interface DeskTrace {
  time: number;
  label: string;
}

/* ------------------------------------------------------------------ */
/* Helpers                                                             */
/* ------------------------------------------------------------------ */

const DAY_MS = 86_400_000;

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

function isoDate(d: Date): string {
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

function hhmm(ts: number): string {
  const d = new Date(ts);
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

/** Deterministic slight rotation — port of DeskViewModel.seedRotation. */
function seedRotation(seedKey: string, minDeg: number, maxDeg: number): number {
  let hash = 0;
  for (let i = 0; i < seedKey.length; i++) {
    hash = (Math.imul(hash, 31) + seedKey.charCodeAt(i)) | 0;
  }
  const unit = ((hash & 0xffff) >>> 0) / 0xffff;
  return minDeg + unit * (maxDeg - minDeg);
}

type Ambient = "MORNING" | "AFTERNOON" | "EVENING" | "LATE_NIGHT";

/** Port of DeskViewModel.ambientFor. */
function ambientFor(hour: number): Ambient {
  if (hour >= 5 && hour <= 10) return "MORNING";
  if (hour >= 11 && hour <= 16) return "AFTERNOON";
  if (hour >= 17 && hour <= 21) return "EVENING";
  return "LATE_NIGHT";
}

function parseColorString(value: string): [number, number, number] | null {
  const v = value.trim();
  const hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(v);
  if (hex) {
    const h = hex[1];
    if (h.length === 3) {
      return [
        parseInt(h[0] + h[0], 16),
        parseInt(h[1] + h[1], 16),
        parseInt(h[2] + h[2], 16),
      ];
    }
    return [
      parseInt(h.slice(0, 2), 16),
      parseInt(h.slice(2, 4), 16),
      parseInt(h.slice(4, 6), 16),
    ];
  }
  const rgb = /^rgba?\(([^)]+)\)$/i.exec(v);
  if (rgb) {
    const parts = rgb[1].split(",").map((s) => parseFloat(s));
    if (parts.length >= 3 && parts.every((n) => !Number.isNaN(n))) {
      return [parts[0], parts[1], parts[2]];
    }
  }
  return null;
}

/**
 * Very subtle screen tint: Evening leans warm, Late Night leans toward the
 * foreground color — mirroring DeskScreen.ambientTintColor at half strength.
 */
function ambientTintCss(ambient: Ambient): string | null {
  const warmth = ambient === "EVENING" ? 0.035 : ambient === "LATE_NIGHT" ? 0.07 : 0;
  if (warmth <= 0) return null;
  const alpha = warmth * 0.5;
  if (ambient === "LATE_NIGHT") {
    const style = getComputedStyle(document.body);
    const fg = parseColorString(style.getPropertyValue("--dc-on-background") ?? "");
    if (!fg) return null;
    return `rgba(${fg[0]},${fg[1]},${fg[2]},${alpha})`;
  }
  return `rgba(201,111,74,${alpha})`; // #C96F4A warm accent
}

/** Port of DeskViewModel.plainExcerpt — strips markdown punctuation, collapses space. */
function plainExcerpt(markdown: string, maxChars = 140): string {
  let out = "";
  let inCode = false;
  for (const ch of markdown) {
    if (ch === "`") {
      inCode = !inCode;
      continue;
    }
    if (!inCode && "#!>*-_[]()".includes(ch)) continue;
    out += ch;
  }
  const collapsed = out.replace(/\s+/g, " ").trim();
  return collapsed.length > maxChars ? collapsed.slice(0, maxChars) + "…" : collapsed;
}

function mediaUrl(path: string): string {
  return `/api/media/file?path=${encodeURIComponent(path)}`;
}

/* ------------------------------------------------------------------ */
/* Data                                                                */
/* ------------------------------------------------------------------ */

interface DeskData {
  diaryDoc: DiaryDocument | null;
  excerpt: string;
  ideas: FlashThought[];
  photos: PhotoThumb[];
  dateRecords: DateRecord[];
  structRecords: StructRow[];
}

function useDeskData(): { data: DeskData | null; loading: boolean } {
  const [data, setData] = useState<DeskData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    (async () => {
      const todayIso = isoDate(new Date());
      const startOfDay = new Date().setHours(0, 0, 0, 0);
      const endOfDay = startOfDay + DAY_MS;
      const [diariesR, thoughtsR, mealsR, datesR, structR] = await Promise.allSettled([
        apiGet<DiaryDocument[]>("/api/diary/documents"),
        apiGet<FlashThought[]>("/api/thoughts"),
        apiGet<unknown>(`/api/meals/calendar?from=${todayIso}&to=${todayIso}`),
        apiGet<DateRecord[]>("/api/date-records"),
        apiGet<StructRow[]>(`/api/structured/records?fromDay=${todayIso}&toDay=${todayIso}`),
      ]);
      if (!alive) return;

      const docs = diariesR.status === "fulfilled" && Array.isArray(diariesR.value)
        ? diariesR.value
        : [];
      const diaryDoc =
        docs
          .filter((d) => d.dateIso === todayIso)
          .sort((a, b) => (b.lastModified ?? 0) - (a.lastModified ?? 0))[0] ?? null;

      let excerpt = "";
      if (diaryDoc) {
        try {
          const editor = await apiGet<DiaryEditorDocument>(
            `/api/diary/document?name=${encodeURIComponent(diaryDoc.name)}`
          );
          excerpt = plainExcerpt(editor.content ?? "");
        } catch {
          excerpt = "";
        }
      }

      const thoughts = thoughtsR.status === "fulfilled" && Array.isArray(thoughtsR.value)
        ? thoughtsR.value
        : [];
      const ideas = thoughts
        .filter((t) => t.createdAt >= startOfDay && t.createdAt < endOfDay)
        .sort((a, b) =>
          a.highlighted === b.highlighted
            ? b.createdAt - a.createdAt
            : a.highlighted
              ? -1
              : 1
        )
        .slice(0, 2);

      const photos = mealsR.status === "fulfilled"
        ? extractPhotosForDay(mealsR.value, todayIso).slice(0, 2)
        : [];

      const dates = datesR.status === "fulfilled" && Array.isArray(datesR.value)
        ? datesR.value
        : [];
      const structs = structR.status === "fulfilled" && Array.isArray(structR.value)
        ? structR.value
        : [];

      setData({ diaryDoc, excerpt, ideas, photos, dateRecords: dates, structRecords: structs });
      setLoading(false);
    })();
    return () => {
      alive = false;
    };
  }, []);

  return { data, loading };
}

/* ------------------------------------------------------------------ */
/* Page                                                                */
/* ------------------------------------------------------------------ */

export default function DeskPage() {
  const settingsState = useSettings();
  const settings = settingsState.settings;
  const navigate = useNavigate();
  const [message, showMessage] = useSnackbar();
  const { data, loading } = useDeskData();

  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const t = window.setInterval(() => setNow(new Date()), 60_000);
    return () => window.clearInterval(t);
  }, []);
  const ambient = ambientFor(now.getHours());

  const [aiOpen, setAiOpen] = useState(false);
  const [aiPrompt, setAiPrompt] = useState("");
  const [qcOpen, setQcOpen] = useState(false);
  const [tracesExpanded, setTracesExpanded] = useState(false);
  const [zoomPhoto, setZoomPhoto] = useState<PhotoThumb | null>(null);

  /* quick capture state */
  const [thoughtDraft, setThoughtDraft] = useState("");
  const [thoughtSending, setThoughtSending] = useState(false);
  const [lineDraft, setLineDraft] = useState("");
  const [lineSending, setLineSending] = useState(false);
  const photoInputRef = useRef<HTMLInputElement>(null);
  const [photoBusy, setPhotoBusy] = useState(false);

  useEffect(() => {
    if (!aiOpen) setAiPrompt("");
  }, [aiOpen]);

  const tint = useMemo(() => {
    try {
      return ambientTintCss(ambient);
    } catch {
      return null;
    }
  }, [ambient]);

  const openAiChat = useCallback(
    (prompt: string | null) => {
      setAiOpen(false);
      navigate(
        prompt ? `/ai_chat?prompt=${encodeURIComponent(prompt)}` : "/ai_chat",
        prompt ? { state: { prompt } } : undefined
      );
    },
    [navigate]
  );

  const submitQuickThought = async () => {
    const snapshot = thoughtDraft.trim();
    if (!snapshot || thoughtSending) return;
    setThoughtSending(true);
    try {
      await apiSend("/api/thoughts", "POST", { content: snapshot });
      setThoughtDraft("");
    } catch {
      showMessage(tr("保存失败，请重试", "Could not save; try again"));
    } finally {
      setThoughtSending(false);
    }
  };

  const submitDiaryLine = async () => {
    const snapshot = lineDraft.trim();
    if (!snapshot || lineSending) return;
    setLineSending(true);
    try {
      await appendToTodayDiary(snapshot);
      setLineDraft("");
      showMessage(
        tr(`已添加到今日日记：${snapshot}`, `Added to today's diary: ${snapshot}`)
      );
    } catch (error) {
      const base = tr("日常记录添加失败", "Could not add the daily record");
      showMessage(error instanceof Error && error.message ? `${base}: ${error.message}` : base);
    } finally {
      setLineSending(false);
    }
  };

  const capturePhoto = async (file: File | undefined) => {
    if (!file || photoBusy) return;
    setPhotoBusy(true);
    const categoryLabel = tr("图片", "Photo");
    try {
      const imported = await apiUpload<{ markdown?: string }>(
        `/api/media/upload?category=${encodeURIComponent(categoryLabel)}`,
        file
      );
      await appendToTodayDiary(imported.markdown ?? "");
      showMessage(
        tr(`${categoryLabel}已加入今日日记`, `${categoryLabel} added to today's diary`)
      );
    } catch (error) {
      const base = tr("图片添加失败", "Could not add the photo");
      showMessage(error instanceof Error && error.message ? `${base}: ${error.message}` : base);
    } finally {
      setPhotoBusy(false);
    }
  };

  const openTodayDiary = async () => {
    try {
      const name = await ensureTodayDiaryName(isoDate(new Date()));
      navigate(`/diary/edit?name=${encodeURIComponent(name)}`);
    } catch {
      navigate("/diary");
    }
  };

  if (!settings) {
    return <div className="dc-center" style={{ padding: 48 }}><Spinner /></div>;
  }

  const english = settings.appLanguage === "ENGLISH";
  const locale = localeTag();
  const todayIso = isoDate(now);
  const dayNumber = String(now.getDate());
  const monthLabel = now
    .toLocaleString(locale, { month: "short" })
    .toUpperCase();
  const weekdayLabel = now.toLocaleString(locale, { weekday: "long" });

  /* Traces: ideas + diary + today's date records + today's structured records. */
  const startOfDay = new Date(now).setHours(0, 0, 0, 0);
  const traces: DeskTrace[] = [];
  (data?.ideas ?? []).forEach((t) => traces.push({ time: t.createdAt, label: tr("小巧思", "idea") }));
  if (data?.diaryDoc) traces.push({ time: data.diaryDoc.lastModified, label: tr("日记", "diary") });
  (data?.dateRecords ?? [])
    .filter((d) => d.dateIso === todayIso)
    .forEach((d) =>
      traces.push({
        time: typeof d.createdAt === "number" && d.createdAt > 0 ? d.createdAt : startOfDay,
        label: d.name?.trim() || tr("事件", "event"),
      })
    );
  (data?.structRecords ?? []).forEach((r) => {
    const time = [r.createdAt, r.timestamp, r.recordedAt].find(
      (v) => typeof v === "number" && v > 0
    );
    traces.push({
      time: typeof time === "number" ? time : startOfDay,
      label: r.rawValue?.trim() || tr("结构化记录", "structured record"),
    });
  });
  traces.sort((a, b) => a.time - b.time);
  const totalTraceCount = traces.length;
  const visibleTraces = tracesExpanded ? traces : traces.slice(0, 6);

  const isEmpty =
    !loading &&
    !data?.diaryDoc &&
    (data?.ideas.length ?? 0) === 0 &&
    (data?.photos.length ?? 0) === 0 &&
    totalTraceCount === 0;

  const wordCountLabel = (count: number) => (english ? `${count} words` : `${count} 字`);

  return (
    <div>
      <style>{`
        @keyframes dc-rise { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: none; } }
        .desk-rise { animation: dc-rise 0.45s ease both; }
        .desk-clamp-3 { display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
        .desk-clamp-4 { display: -webkit-box; -webkit-line-clamp: 4; -webkit-box-orient: vertical; overflow: hidden; }
      `}</style>

      {/* Ambient tint overlay — extremely subtle, behind everything. */}
      {tint && <div aria-hidden style={{ position: "fixed", inset: 0, zIndex: -1, background: tint, pointerEvents: "none" }} />}

      <div
        style={{
          maxWidth: 760,
          margin: "0 auto",
          padding: "24px 20px 140px",
        }}
      >
        {/* Date masthead — the date itself is the page title. */}
        <div className="desk-rise dc-row" style={{ alignItems: "flex-start", justifyContent: "space-between" }}>
          <div>
            <div style={{ fontSize: 72, fontWeight: 300, letterSpacing: "-2px", lineHeight: 1 }}>
              {dayNumber}
            </div>
            <div className="dc-muted" style={{ fontSize: 16, fontWeight: 600, letterSpacing: "2.5px", marginTop: 6 }}>
              {monthLabel}
            </div>
            <div className="dc-muted" style={{ fontSize: 20, opacity: 0.82, marginTop: 6 }}>
              {weekdayLabel}
            </div>
          </div>
          <button
            className="dc-icon-btn"
            aria-label={tr("打开 AI", "Open AI")}
            title={tr("想做些什么？", "What are you thinking?")}
            onClick={() => setAiOpen(true)}
            style={{
              width: 52,
              height: 52,
              fontSize: 26,
              color: ambient === "LATE_NIGHT" ? "var(--dc-on-surface-variant)" : "var(--dc-primary)",
            }}
          >
            ✦
          </button>
        </div>

        <div style={{ height: 40 }} />

        {loading ? (
          <Spinner />
        ) : isEmpty ? (
          /* Empty state: intentional whitespace, one quiet line and a "+". */
          <div className="desk-rise dc-col dc-center" style={{ gap: 40, paddingTop: 48 }}>
            <div className="dc-muted" style={{ fontSize: 16 }}>
              {tr("这是你的日子留下痕迹的地方。", "This is where your days leave traces.")}
            </div>
            <button
              className="dc-icon-btn"
              aria-label={tr("快速记录", "Quick capture")}
              onClick={() => setQcOpen((v) => !v)}
              style={{ width: 56, height: 56, fontSize: 32, fontWeight: 300, color: "var(--dc-primary)" }}
            >
              +
            </button>
          </div>
        ) : (
          <>
            {/* Today's diary paper object. */}
            {data?.diaryDoc && (
              <div
                className="desk-rise"
                role="button"
                tabIndex={0}
                onClick={() => navigate(`/diary/edit?name=${encodeURIComponent(data.diaryDoc!.name)}`)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    navigate(`/diary/edit?name=${encodeURIComponent(data.diaryDoc!.name)}`);
                  }
                }}
                style={{
                  transform: `rotate(${seedRotation(`${data.diaryDoc.name}:${todayIso}`, -0.5, 0.5)}deg)`,
                  background: "var(--dc-surface-container)",
                  border: "var(--dc-border-width) solid var(--dc-outline-variant)",
                  borderRadius: 4,
                  boxShadow: "0 3px 8px rgba(0,0,0,0.18)",
                  padding: "22px 24px",
                  cursor: "pointer",
                }}
              >
                <div className="dc-muted" style={{ fontSize: 11, fontWeight: 600, letterSpacing: "2.2px" }}>
                  TODAY DIARY
                </div>
                <div style={{ marginTop: 14, fontSize: 17, lineHeight: 1.55 }}>
                  <span className="desk-clamp-3">
                    {(data.excerpt || data.diaryDoc.name).trim() || data.diaryDoc.name}
                  </span>
                </div>
                <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 22 }}>
                  <span className="dc-muted" style={{ fontSize: 12 }}>
                    {wordCountLabel(data.diaryDoc.wordCount ?? 0)}
                  </span>
                </div>
              </div>
            )}
            {data?.diaryDoc && <div style={{ height: 28 }} />}

            {/* Idea slips. */}
            {(data?.ideas.length ?? 0) > 0 && (
              <div className="desk-rise dc-col" style={{ gap: 6 }}>
                {data!.ideas.map((idea) => (
                  <div
                    key={idea.id}
                    role="button"
                    tabIndex={0}
                    onClick={() => navigate("/thought")}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") navigate("/thought");
                    }}
                    style={{
                      transform: `rotate(${seedRotation(`${idea.id}:${todayIso}`, -0.6, 0.6)}deg)`,
                      padding: "10px 4px",
                      cursor: "pointer",
                    }}
                  >
                    <div className="dc-muted" style={{ fontSize: 11, fontWeight: 600, letterSpacing: "2px" }}>
                      ╱ IDEA ╱
                    </div>
                    <div style={{ marginTop: 10, fontSize: 20, lineHeight: 1.5, fontWeight: 500 }}>
                      <span className="desk-clamp-4">{idea.content}</span>
                    </div>
                    <div className="dc-muted" style={{ marginTop: 12, fontSize: 12, letterSpacing: "0.4px" }}>
                      {tr("小巧思", "Idea")}
                    </div>
                  </div>
                ))}
              </div>
            )}
            {(data?.ideas.length ?? 0) > 0 && <div style={{ height: 28 }} />}

            {/* Photo prints. */}
            {(data?.photos.length ?? 0) > 0 && (
              <div className="desk-rise dc-row dc-wrap" style={{ gap: 22, justifyContent: "center" }}>
                {data!.photos.map((photo, i) => (
                  <div
                    key={`${photo.path}-${i}`}
                    className="dc-col dc-center"
                    style={{ gap: 8, transform: `rotate(${seedRotation(`${photo.path}:${todayIso}`, -0.9, 0.9)}deg)` }}
                  >
                    <img
                      src={`${mediaUrl(photo.path)}&size=thumb`}
                      alt={photo.caption || photo.category || tr("照片", "Photo")}
                      loading="lazy"
                      onClick={() => setZoomPhoto(photo)}
                      style={{
                        width: 120,
                        height: 120,
                        objectFit: "cover",
                        borderRadius: 2,
                        boxShadow: "0 2px 6px rgba(0,0,0,0.25)",
                        cursor: "zoom-in",
                        background: "var(--dc-surface-container)",
                      }}
                    />
                    <span className="dc-muted" style={{ fontSize: 12, maxWidth: 130, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                      {photo.caption || photo.category || "photo"}
                    </span>
                  </div>
                ))}
              </div>
            )}
            {(data?.photos.length ?? 0) > 0 && <div style={{ height: 32 }} />}
          </>
        )}

        <div style={{ height: 8 }} />

        {/* Today Traces — purely typographic. */}
        {visibleTraces.length > 0 && (
          <div className="desk-rise">
            <div className="dc-muted" style={{ fontSize: 12, fontWeight: 600, letterSpacing: "1.8px" }}>
              Today Traces
            </div>
            <div style={{ height: 18 }} />
            {visibleTraces.map((trace, i) => (
              <div key={`${trace.time}-${i}`} className="dc-row" style={{ padding: "7px 0", gap: 12 }}>
                <span
                  className="dc-muted"
                  style={{ fontSize: 13, fontFamily: "ui-monospace, monospace", width: 52, flexShrink: 0, opacity: 0.9 }}
                >
                  {hhmm(trace.time)}
                </span>
                <span
                  aria-hidden
                  style={{ width: 12, height: 1, background: "var(--dc-outline)", flexShrink: 0 }}
                />
                <span style={{ fontSize: 15, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  {trace.label}
                </span>
              </div>
            ))}
            <div style={{ height: 14 }} />
            <button
              className="dc-btn"
              style={{ padding: "6px 0", color: "var(--dc-primary)", fontWeight: 500 }}
              onClick={() => setTracesExpanded((v) => !v)}
            >
              + {totalTraceCount} {tr("条痕迹", "traces")}
            </button>
          </div>
        )}

        <div style={{ height: 24 }} />

        {/* Quiet bottom "+" for quick capture. */}
        {!isEmpty && (
          <div className="dc-row" style={{ justifyContent: "center" }}>
            <button
              className="dc-icon-btn"
              aria-label={tr("快速记录", "Quick capture")}
              onClick={() => setQcOpen((v) => !v)}
              style={{ width: 56, height: 56, fontSize: 28, color: "var(--dc-on-surface-variant)", opacity: 0.85 }}
            >
              +
            </button>
          </div>
        )}
      </div>

      {/* Quick capture tray. */}
      {qcOpen && (
        <>
          <div
            style={{ position: "fixed", inset: 0, zIndex: 85 }}
            onClick={() => setQcOpen(false)}
            aria-hidden
          />
          <div
            role="dialog"
            aria-label={tr("快速记录", "Quick capture")}
            style={{
              position: "fixed",
              left: 0,
              right: 0,
              bottom: "calc(var(--dc-bottom-nav-height) + env(safe-area-inset-bottom))",
              zIndex: 90,
              background: "color-mix(in srgb, var(--dc-surface-container) 96%, transparent)",
              borderTop: "var(--dc-border-width) solid var(--dc-outline-variant)",
              padding: "16px 20px 20px",
            }}
          >
            <div style={{ maxWidth: 640, margin: "0 auto" }} className="dc-col">
              <form
                className="dc-row"
                onSubmit={(e) => {
                  e.preventDefault();
                  void submitQuickThought();
                }}
              >
                <input
                  className="dc-input dc-grow"
                  value={thoughtDraft}
                  onChange={(e) => setThoughtDraft(e.target.value)}
                  placeholder={tr("记一条小巧思", "Write a thought")}
                  aria-label={tr("记一条小巧思", "Write a thought")}
                />
                <button
                  type="submit"
                  className="dc-btn dc-btn-filled"
                  disabled={!thoughtDraft.trim() || thoughtSending}
                  aria-label={tr("发送", "Send")}
                >
                  {thoughtSending ? "…" : tr("发送", "Send")}
                </button>
              </form>
              <form
                className="dc-row"
                onSubmit={(e) => {
                  e.preventDefault();
                  void submitDiaryLine();
                }}
              >
                <input
                  className="dc-input dc-grow"
                  value={lineDraft}
                  onChange={(e) => setLineDraft(e.target.value)}
                  placeholder={tr("给今天的日记加一行", "Add a line to today's diary")}
                  aria-label={tr("给今天的日记加一行", "Add a line to today's diary")}
                />
                <button
                  type="submit"
                  className="dc-btn dc-btn-filled"
                  disabled={!lineDraft.trim() || lineSending}
                  aria-label={tr("添加到今日日记", "Add to today's diary")}
                >
                  {lineSending ? "…" : tr("添加", "Add")}
                </button>
              </form>
              <div className="dc-row" style={{ justifyContent: "space-around", marginTop: 6, flexWrap: "wrap", gap: 8 }}>
                {([
                  { glyph: "✎", label: tr("日记", "Diary"), act: () => void openTodayDiary() },
                  { glyph: "✦", label: tr("小巧思", "Idea"), act: () => navigate("/thought") },
                  {
                    glyph: "□",
                    label: tr("照片", "Photo"),
                    act: () => photoInputRef.current?.click(),
                  },
                  { glyph: "○", label: tr("事件", "Event"), act: () => navigate("/date_records") },
                ] as const).map((a) => (
                  <button
                    key={a.label}
                    className="dc-col dc-center"
                    style={{ background: "none", border: "none", padding: 8, gap: 6 }}
                    onClick={a.act}
                  >
                    <span style={{ fontSize: 22, color: "var(--dc-primary)" }}>{a.glyph}</span>
                    <span style={{ fontSize: 13 }}>{a.label}</span>
                  </button>
                ))}
              </div>
            </div>
          </div>
          <input
            ref={photoInputRef}
            type="file"
            accept="image/*"
            style={{ display: "none" }}
            onChange={(e) => {
              const f = e.target.files?.[0];
              e.target.value = "";
              void capturePhoto(f);
            }}
          />
        </>
      )}

      {/* Lightweight AI overlay. */}
      {aiOpen && (
        <div
          style={{
            position: "fixed",
            inset: 0,
            zIndex: 200,
            background: "rgba(0,0,0,0.35)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            padding: 16,
          }}
          onClick={() => setAiOpen(false)}
        >
          <div
            role="dialog"
            aria-modal="true"
            aria-label={tr("想做些什么？", "What are you thinking?")}
            onClick={(e) => e.stopPropagation()}
            style={{
              width: 320,
              maxWidth: "94vw",
              background: "var(--dc-surface)",
              color: "var(--dc-on-surface)",
              borderRadius: 6,
              padding: 28,
              textAlign: "center",
            }}
          >
            <div style={{ fontSize: 28, color: "var(--dc-primary)" }}>✦</div>
            <div style={{ marginTop: 18, fontSize: 19, fontWeight: 500 }}>
              {tr("想做些什么？", "What are you thinking?")}
            </div>
            <div style={{ height: 1, background: "var(--dc-outline-variant)", margin: "20px 0" }} />
            <form
              className="dc-row"
              onSubmit={(e) => {
                e.preventDefault();
                const p = aiPrompt.trim();
                if (p) openAiChat(p);
              }}
            >
              <input
                className="dc-input dc-grow"
                value={aiPrompt}
                onChange={(e) => setAiPrompt(e.target.value)}
                placeholder={tr("问问 AI…", "Ask the AI…")}
                aria-label={tr("问问 AI…", "Ask the AI…")}
              />
              <button type="submit" className="dc-btn dc-btn-filled" disabled={!aiPrompt.trim()}>
                {tr("发送", "Send")}
              </button>
            </form>
            <div
              role="button"
              tabIndex={0}
              onClick={() => openAiChat(tr("总结一下我今天的状态", "Summarize how my day went"))}
              onKeyDown={(e) => {
                if (e.key === "Enter") openAiChat(tr("总结一下我今天的状态", "Summarize how my day went"));
              }}
              className="dc-muted"
              style={{ textAlign: "left", padding: "14px 0", cursor: "pointer", fontSize: 15 }}
            >
              {tr("总结一下我今天的状态", "Summarize how my day went")}
            </div>
            <div style={{ height: 1, background: "var(--dc-outline-variant)", opacity: 0.5 }} />
            <button
              className="dc-btn"
              style={{ color: "var(--dc-primary)", fontWeight: 500, marginTop: 14 }}
              onClick={() => openAiChat(null)}
            >
              {tr("直接开始", "Just start typing")}
            </button>
          </div>
        </div>
      )}

      {/* Photo zoom dialog. */}
      <Modal open={zoomPhoto !== null} onClose={() => setZoomPhoto(null)} title={zoomPhoto?.caption || zoomPhoto?.category || tr("照片", "Photo")} width={720}>
        {zoomPhoto && (
          <img
            src={mediaUrl(zoomPhoto.path)}
            alt={zoomPhoto.caption || zoomPhoto.category || tr("照片", "Photo")}
            style={{ width: "100%", maxHeight: "70vh", objectFit: "contain", borderRadius: 8 }}
          />
        )}
      </Modal>

      <Snackbar message={message} />
      <PageTutorialOverlay
        pageKey="desk"
        title={tr("桌面", "Desk")}
        lines={[tr("桌面汇总今天留下的日记、小巧思与照片，点模块可直接跳转。", "The desk gathers today's diary, thoughts and photos; tap a module to jump there.")]}
      />
    </div>
  );
}
