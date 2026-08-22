/**
 * Meal calendar (/meals) — mirrors Android 吃历页:
 * date-grouped meal photos with wrapped/horizontal layouts (TWO/THREE/SMART),
 * captions toggle, image height cap, saved photo filter, category filter dialog,
 * long-image export, fullscreen zoom viewer, per-day energy details, and AI
 * calorie estimation entry points with progress polling.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Calculator, ChevronLeft, ChevronRight, Download, Filter, Image as ImageIcon,
  RefreshCw, SlidersHorizontal, Wand2, X,
} from "lucide-react";
import { apiGet, apiSend } from "../../api/client";
import { MEAL_CATEGORIES } from "../../api/types";
import type { AppSettings } from "../../api/types";
import { useSettings } from "../../stores/settings";
import { tr } from "../../i18n/tr";
import {
  ConfirmDialog, EmptyState, ErrorText, Modal, PageTutorialOverlay,
  Snackbar, Spinner, TopBar, useSnackbar,
} from "../../components/ui";
import { cssMealPhotoFilter } from "./MealFilterPage";

// ---------- defensive response shapes ----------

interface FoodItem {
  name: string;
  amount: string | null;
  unit: string | null;
  energyKj: number | null;
}

interface MealPhotoView {
  key: string;
  fileName: string;
  thumbUrl: string;
  fullUrl: string;
  caption: string;
  categoryKey: string | null;
  energyKj: number | null;
  location: string | null;
  foods: FoodItem[];
}

interface MealDayView {
  dateIso: string;
  photos: MealPhotoView[];
  overrideTotal: number | null;
  totalEnergyKj: number | null;
  note: string;
}

function asObj(v: unknown): Record<string, unknown> {
  return v && typeof v === "object" ? (v as Record<string, unknown>) : {};
}

function str(v: unknown): string | null {
  return typeof v === "string" && v.length > 0 ? v : null;
}

function num(v: unknown): number | null {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

function arr(v: unknown): unknown[] {
  return Array.isArray(v) ? v : [];
}

function mediaUrl(p: Record<string, unknown>, size: "thumb" | "full"): string {
  const direct = str(p.url) ?? str(p.uri) ?? str(p.href);
  if (direct && (direct.startsWith("/") || direct.startsWith("http"))) return direct;
  const rel = str(p.path) ?? str(p.fileName) ?? str(p.file) ?? str(p.name) ?? "";
  return `/api/media/file?path=${encodeURIComponent(rel)}&size=${size}`;
}

function normalizeDays(data: unknown): MealDayView[] {
  const raw = Array.isArray(data)
    ? data
    : arr(asObj(data).days).length > 0
      ? arr(asObj(data).days)
      : arr(asObj(data).items).length > 0
        ? arr(asObj(data).items)
        : arr(asObj(data).calendar);
  const days: MealDayView[] = [];
  for (const entry of raw) {
    const d = asObj(entry);
    const dateIso = str(d.dateIso) ?? str(d.date) ?? str(d.day);
    if (!dateIso) continue;
    const photos: MealPhotoView[] = [];
    arr(d.photos).forEach((pe, i) => {
      const p = asObj(pe);
      const cats = arr(p.categories).filter((c): c is string => typeof c === "string");
      const foods: FoodItem[] = arr(p.foods).map((fe) => {
        const f = asObj(fe);
        return {
          name: str(f.name) ?? "",
          amount: str(f.amount),
          unit: str(f.unit),
          energyKj: num(f.energyKj),
        };
      }).filter((f) => f.name);
      photos.push({
        key: str(p.fileName) ?? str(p.url) ?? `${dateIso}#${i}`,
        fileName: str(p.fileName) ?? "",
        thumbUrl: mediaUrl(p, "thumb"),
        fullUrl: mediaUrl(p, "full"),
        caption: str(p.caption) ?? "",
        categoryKey: str(p.category) ?? cats[0] ?? null,
        energyKj: num(p.energyKj) ?? num(p.totalEnergyKj) ?? num(p.kj),
        location: str(p.location) ?? str(p.locationName) ?? str(p.place),
        foods,
      });
    });
    const overrideTotal = num(d.totalEnergyKjOverride) ?? num(d.manualTotalKj);
    const sum = photos.some((p) => p.energyKj != null)
      ? photos.reduce((acc, p) => acc + (p.energyKj ?? 0), 0)
      : null;
    const total = num(d.totalEnergyKj) ?? overrideTotal ?? sum;
    days.push({
      dateIso,
      photos,
      overrideTotal,
      totalEnergyKj: total,
      note: str(d.note) ?? "",
    });
  }
  return days.sort((a, b) => b.dateIso.localeCompare(a.dateIso));
}

/** Port of AppModels.kt mealPhotoRowSizes(): SMART mixes 3s and 2s so no dangling single. */
export function mealPhotoRowSizes(count: number, mode: AppSettings["mealCalendarPhotosPerRow"]): number[] {
  if (count <= 0) return [];
  switch (mode) {
    case "TWO":
      return [...Array<number>(Math.floor(count / 2)).fill(2), ...(count % 2 === 1 ? [1] : [])];
    case "THREE":
      return [...Array<number>(Math.floor(count / 3)).fill(3), ...(count % 3 !== 0 ? [count % 3] : [])];
    case "SMART":
    default: {
      if (count === 1) return [1];
      if (count % 3 === 0) return Array<number>(count / 3).fill(3);
      if (count % 3 === 1) return [...Array<number>((count - 4) / 3).fill(3), 2, 2];
      return [...Array<number>(Math.floor(count / 3)).fill(3), 2];
    }
  }
}

function todayIso(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function shiftMonth(month: string, delta: number): string {
  const m = /^(\d{4})-(\d{2})$/.exec(month);
  if (!m) return month;
  const dt = new Date(Number(m[1]), Number(m[2]) - 1 + delta, 1);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${dt.getFullYear()}-${p(dt.getMonth() + 1)}`;
}

function lastDayOfMonth(month: string): string {
  const m = /^(\d{4})-(\d{2})$/.exec(month);
  if (!m) return todayIso();
  const dt = new Date(Number(m[1]), Number(m[2]), 0);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${dt.getFullYear()}-${p(dt.getMonth() + 1)}-${p(dt.getDate())}`;
}

function categoryLabel(key: string | null): string {
  const c = MEAL_CATEGORIES.find((x) => x.key === key);
  if (!c) return tr("照片", "Photo");
  return tr(c.zh, c.en);
}

interface CalRun {
  dateIso: string;
  statusText: string;
  running: boolean;
  failed: boolean;
  detail: string;
}

const RUNNING_STATES = ["running", "processing", "queued", "pending", "active", "in_progress", "started", "working"];
const TERMINAL_BAD = ["failed", "error", "cancelled", "canceled"];

function parseStatus(data: unknown): Omit<CalRun, "dateIso"> {
  const o = asObj(data);
  const st = (str(o.state) ?? str(o.status) ?? str(o.phase) ?? "").toLowerCase();
  const processed = num(o.processedPhotos) ?? num(o.completedPhotos);
  const totalPhotos = num(o.totalPhotos);
  const daysDone = num(o.daysCompleted) ?? num(o.finishedDays);
  const daysTotal = num(o.daysTotal);
  let running = o.running === true ? true : o.running === false ? false : RUNNING_STATES.includes(st);
  if (TERMINAL_BAD.includes(st)) running = false;
  const failed = TERMINAL_BAD.includes(st);
  const bits: string[] = [];
  if (st) bits.push(st);
  if (processed != null && totalPhotos != null) bits.push(`${processed}/${totalPhotos}`);
  else if (daysDone != null && daysTotal != null) bits.push(`${daysDone}/${daysTotal}`);
  const detailBits = [str(o.detail), str(o.currentStage), str(o.message)]
    .filter((x): x is string => !!x);
  return {
    statusText: bits.join(" ") || (running ? tr("进行中", "running") : tr("已结束", "finished")),
    running,
    failed,
    detail: detailBits.join(" · "),
  };
}

export default function MealCalendarPage() {
  const navigate = useNavigate();
  const [snack, showSnack] = useSnackbar();
  const settings = useSettings((s) => s.settings);
  const updateSettings = useSettings((s) => s.update);

  const [viewMonth, setViewMonth] = useState(() => todayIso().slice(0, 7));
  const from = `${viewMonth}-01`;
  const to = lastDayOfMonth(viewMonth);

  const [days, setDays] = useState<MealDayView[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);

  const [selectedCats, setSelectedCats] = useState<Set<string>>(new Set(MEAL_CATEGORIES.map((c) => c.key)));
  const [filterOpen, setFilterOpen] = useState(false);
  const [detailsDate, setDetailsDate] = useState<string | null>(null);
  const [zoomIndex, setZoomIndex] = useState<{ dateIso: string; index: number } | null>(null);
  const [recalcDate, setRecalcDate] = useState<string | null>(null);
  const [calcAllOpen, setCalcAllOpen] = useState(false);
  const [runs, setRuns] = useState<Record<string, CalRun>>({});
  const [runsOpen, setRunsOpen] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await apiGet<unknown>(`/api/meals/calendar?from=${from}&to=${to}`);
      setDays(normalizeDays(data));
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  }, [from, to]);

  useEffect(() => {
    void load();
  }, [load]);

  const filterActive = selectedCats.size < MEAL_CATEGORIES.length;
  const allDays = days ?? [];
  const visibleDays = useMemo(
    () => allDays.map((d) => ({
      ...d,
      photos: d.photos.filter((p) => p.categoryKey == null || selectedCats.has(p.categoryKey)),
    })).filter((d) => d.photos.length > 0),
    [allDays, selectedCats],
  );
  const visiblePhotoCount = visibleDays.reduce((n, d) => n + d.photos.length, 0);

  const canonicalDay = useCallback(
    (dateIso: string) => allDays.find((d) => d.dateIso === dateIso),
    [allDays],
  );

  const filterCss = settings?.mealPhotoFilter
    ? cssMealPhotoFilter(settings.mealPhotoFilter)
    : "none";
  const filterOn = !!settings?.mealPhotoFilter?.enabled;

  // ---- calorie estimation ----
  const calorieEnabled = !!settings?.calorieEstimationEnabled;

  const enqueueEstimate = useCallback(async (dates: string[]) => {
    if (!calorieEnabled || dates.length === 0) return;
    setRuns((prev) => {
      const next = { ...prev };
      for (const d of dates) {
        next[d] = { dateIso: d, statusText: tr("已加入队列", "queued"), running: true, failed: false, detail: "" };
      }
      return next;
    });
    for (const d of dates) {
      try {
        await apiSend("/api/calorie/estimate", "POST", { dateIso: d });
      } catch (e) {
        setRuns((prev) => ({
          ...prev,
          [d]: { dateIso: d, statusText: e instanceof Error ? e.message : "error", running: false, failed: true, detail: "" },
        }));
      }
    }
  }, [calorieEnabled]);

  const activeRunKey = Object.values(runs)
    .filter((r) => r.running)
    .map((r) => r.dateIso)
    .sort()
    .join(",");

  useEffect(() => {
    const dates = activeRunKey ? activeRunKey.split(",") : [];
    if (dates.length === 0) return;
    let cancelled = false;
    const timer = window.setInterval(async () => {
      for (const d of dates) {
        try {
          const data = await apiGet<unknown>(`/api/calorie/status?dateIso=${encodeURIComponent(d)}`);
          if (cancelled) return;
          const parsed = parseStatus(data);
          setRuns((prev) => ({ ...prev, [d]: { dateIso: d, ...parsed } }));
        } catch {
          /* keep last known status */
        }
      }
    }, 2500);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [activeRunKey]);

  const runList = Object.values(runs).sort((a, b) => b.dateIso.localeCompare(a.dateIso));
  const runningCount = runList.filter((r) => r.running).length;

  const toggleFilterEnabled = async () => {
    if (!settings) return;
    try {
      await updateSettings({
        mealPhotoFilter: { ...settings.mealPhotoFilter, enabled: !settings.mealPhotoFilter.enabled },
      });
    } catch (e) {
      showSnack(e instanceof Error ? e.message : tr("操作失败", "Operation failed"));
    }
  };

  const zoomed = useMemo(() => {
    if (!zoomIndex) return null;
    const day = visibleDays.find((d) => d.dateIso === zoomIndex.dateIso);
    const photo = day?.photos[zoomIndex.index];
    return photo ? { photo, day } : null;
  }, [zoomIndex, visibleDays]);

  if (!settings) {
    return (
      <div>
        <TopBar title={tr("吃历", "Meal calendar")} back onBack={() => navigate("/diary")} />
        <Spinner />
      </div>
    );
  }

  const maxImgHeight = Math.max(80, Math.min(320, settings.mealCalendarImageMaxHeightDp));

  return (
    <div className="meal-calendar-page">
      <TopBar
        title={tr("吃历", "Meal calendar")}
        back
        onBack={() => navigate("/diary")}
        subtitle={`${from} ~ ${to}`}
        actions={<>
          {calorieEnabled && (
            <button
              className="dc-icon-btn"
              aria-label={tr("计算未计算的热量", "Calculate missing calories")}
              title={tr("计算未计算的热量", "Calculate missing calories")}
              onClick={() => setCalcAllOpen(true)}
            >
              <Calculator size={20} />
            </button>
          )}
          <button
            className="dc-icon-btn"
            aria-label={tr("导出长图", "Export long image")}
            title={tr("导出长图", "Export long image")}
            disabled={visiblePhotoCount === 0}
            onClick={() => {
              const cats = filterActive ? `&categories=${encodeURIComponent([...selectedCats].join(","))}` : "";
              window.open(`/api/diary/export/meal-calendar.png?start=${from}&end=${to}${cats}`, "_blank");
            }}
          >
            <Download size={20} />
          </button>
          <button
            className="dc-icon-btn"
            aria-label={tr("筛选餐别", "Filter meal categories")}
            title={tr("筛选餐别", "Filter meal categories")}
            style={filterActive ? { background: "var(--dc-secondary-container)", color: "var(--dc-on-secondary-container)" } : undefined}
            onClick={() => setFilterOpen(true)}
          >
            <Filter size={20} />
          </button>
          <button
            className="dc-icon-btn"
            aria-label={tr("照片滤镜", "Photo filter")}
            title={tr("照片滤镜（右键/长按进入滤镜设置）", "Photo filter (right-click / long-press for settings)")}
            style={filterOn ? { background: "var(--dc-secondary-container)", color: "var(--dc-on-secondary-container)" } : undefined}
            onClick={() => void toggleFilterEnabled()}
            onContextMenu={(e) => { e.preventDefault(); navigate("/meals/filter"); }}
          >
            <Wand2 size={20} />
          </button>
          <button
            className="dc-icon-btn"
            aria-label={tr("吃历滤镜设置", "Meal filter settings")}
            title={tr("吃历滤镜设置", "Meal filter settings")}
            onClick={() => navigate("/meals/filter")}
          >
            <SlidersHorizontal size={20} />
          </button>
          <button className="dc-icon-btn" aria-label={tr("刷新", "Refresh")} onClick={() => void load()}>
            <RefreshCw size={20} />
          </button>
        </>}
      />

      {/* Month navigation */}
      <div className="dc-row" style={{ margin: "4px 0 12px" }}>
        <button className="dc-icon-btn" aria-label={tr("上个月", "Previous month")} onClick={() => setViewMonth(shiftMonth(viewMonth, -1))}>
          <ChevronLeft size={20} />
        </button>
        <span style={{ fontWeight: 600 }}>{viewMonth}</span>
        <button className="dc-icon-btn" aria-label={tr("下个月", "Next month")} onClick={() => setViewMonth(shiftMonth(viewMonth, 1))}>
          <ChevronRight size={20} />
        </button>
        <span className="dc-grow" />
        {filterActive && (
          <span className="dc-chip">
            {tr(`当前已选择 ${selectedCats.size} 个餐别`, `${selectedCats.size} categories selected`)}
          </span>
        )}
      </div>

      {/* Calorie estimation run card */}
      {calorieEnabled && runList.length > 0 && (
        <div className="dc-card" style={{ padding: "12px 14px", marginBottom: 12 }}>
          <button
            className="dc-row"
            style={{ width: "100%", border: "none", background: "transparent", padding: 0, textAlign: "left" }}
            onClick={() => setRunsOpen((v) => !v)}
            aria-expanded={runsOpen}
          >
            <Calculator size={18} />
            <span style={{ fontWeight: 600 }}>
              {runningCount > 0
                ? tr(`热量估算正在进行（${runningCount}）`, `Calorie estimation running (${runningCount})`)
                : tr("热量估算记录", "Calorie estimation runs")}
            </span>
            <span className="dc-grow" />
            <span className="dc-muted">{runsOpen ? "▾" : "▸"}</span>
          </button>
          {runsOpen && (
            <div className="dc-col" style={{ gap: 6, marginTop: 10 }}>
              {runList.map((r) => (
                <div key={r.dateIso} className="dc-row" style={{ fontSize: "0.88em", gap: 8 }}>
                  <span style={{ fontVariantNumeric: "tabular-nums" }}>{r.dateIso}</span>
                  <span style={{ color: r.failed ? "var(--dc-error)" : r.running ? "var(--dc-primary)" : "var(--dc-on-surface-variant)" }}>
                    {r.statusText}
                  </span>
                  {r.detail && <span className="dc-muted" style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{r.detail}</span>}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {loading && days === null && <Spinner />}
      {!loading && days !== null && allDays.length === 0 && !error && (
        <EmptyState
          icon={<ImageIcon size={44} />}
          title={tr("还没有饮食照片", "No meal photos yet")}
          hint={
            <button className="dc-btn dc-btn-tonal" style={{ marginTop: 10 }} onClick={() => void load()}>
              {tr("刷新", "Refresh")}
            </button>
          }
        />
      )}
      {!loading && !!error && days === null && (
        <EmptyState
          icon={<ImageIcon size={44} />}
          title={tr("无法读取吃历", "Could not load meal calendar")}
          hint={
            <button className="dc-btn dc-btn-tonal" style={{ marginTop: 10 }} onClick={() => void load()}>
              {tr("重试", "Retry")}
            </button>
          }
        />
      )}
      {!loading && days !== null && allDays.length > 0 && visibleDays.length === 0 && (
        <div className="dc-muted" style={{ padding: "24px 4px" }}>
          {tr("当前筛选下没有照片", "No photos match the current filter")}
        </div>
      )}
      <ErrorText error={error && days !== null ? error : null} />

      {/* Day groups */}
      <div className="dc-col" style={{ gap: 22 }}>
        {visibleDays.map((day) => {
          const canonical = canonicalDay(day.dateIso) ?? day;
          const showTotal = canonical.totalEnergyKj != null || calorieEnabled;
          const rowSizes = settings.mealCalendarWrapEnabled
            ? mealPhotoRowSizes(day.photos.length, settings.mealCalendarPhotosPerRow)
            : [];
          let offset = 0;
          return (
            <div key={day.dateIso}>
              <div className="dc-row" style={{ marginBottom: 8, gap: 6 }}>
                <span style={{ fontWeight: 600 }}>{day.dateIso}</span>
                {showTotal && (
                  <button
                    className="dc-btn"
                    style={{ color: "var(--dc-primary)", padding: "2px 6px", fontWeight: 600 }}
                    onClick={() => setDetailsDate(day.dateIso)}
                  >
                    {canonical.totalEnergyKj != null
                      ? tr(`·  总热量 ${canonical.totalEnergyKj} kJ`, `·  Total ${canonical.totalEnergyKj} kJ`)
                      : tr("·  热量详情", "·  Energy details")}
                  </button>
                )}
                <span className="dc-grow" />
                {calorieEnabled && (
                  <button
                    className="dc-icon-btn"
                    aria-label={tr(`重新计算 ${day.dateIso} 的热量`, `Recalculate ${day.dateIso}`)}
                    title={tr(`重新计算 ${day.dateIso} 的热量`, `Recalculate ${day.dateIso}`)}
                    onClick={() => setRecalcDate(day.dateIso)}
                  >
                    <Calculator size={18} />
                  </button>
                )}
              </div>

              {settings.mealCalendarWrapEnabled ? (
                <div className="dc-col" style={{ gap: 10 }}>
                  {rowSizes.map((size, ri) => {
                    const rowStart = offset;
                    const rowPhotos = day.photos.slice(rowStart, rowStart + size);
                    offset += size;
                    return (
                      <div
                        key={ri}
                        style={{
                          display: "grid",
                          gridTemplateColumns: `repeat(${size}, minmax(0, 1fr))`,
                          gap: 10,
                        }}
                      >
                        {rowPhotos.map((photo, pi) => (
                          <MealPhotoCard
                            key={photo.key + "#" + pi}
                            photo={photo}
                            filterCss={filterCss}
                            maxImgHeight={maxImgHeight}
                            showCaptions={settings.mealCalendarShowCaptions}
                            onClick={() => setZoomIndex({ dateIso: day.dateIso, index: rowStart + pi })}
                          />
                        ))}
                      </div>
                    );
                  })}
                </div>
              ) : (
                <div style={{ display: "flex", gap: 10, overflowX: "auto", paddingBottom: 4 }}>
                  {day.photos.map((photo, pi) => (
                    <div key={photo.key + "#" + pi} style={{ width: 148, flexShrink: 0 }}>
                      <MealPhotoCard
                        photo={photo}
                        filterCss={filterCss}
                        maxImgHeight={maxImgHeight}
                        showCaptions={settings.mealCalendarShowCaptions}
                        onClick={() => setZoomIndex({ dateIso: day.dateIso, index: pi })}
                      />
                    </div>
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* 筛选餐别 dialog */}
      <Modal open={filterOpen} onClose={() => setFilterOpen(false)} title={tr("筛选餐别", "Filter meal categories")}>
        <div className="dc-col" style={{ gap: 4 }}>
          {MEAL_CATEGORIES.map((c) => (
            <label key={c.key} className="dc-row" style={{ cursor: "pointer", padding: "6px 2px" }}>
              <input
                type="checkbox"
                checked={selectedCats.has(c.key)}
                onChange={(e) => {
                  setSelectedCats((prev) => {
                    const next = new Set(prev);
                    if (e.target.checked) next.add(c.key);
                    else next.delete(c.key);
                    return next;
                  });
                }}
                style={{ width: 18, height: 18, accentColor: "var(--dc-primary)" }}
              />
              <span>{c.icon} {tr(c.zh, c.en)}</span>
            </label>
          ))}
        </div>
        <div className="dc-muted" style={{ fontSize: "0.82em", marginTop: 8 }}>
          {tr("全部取消时不显示任何照片。", "Unchecking everything hides all photos.")}
        </div>
        <div className="dc-row" style={{ justifyContent: "space-between", marginTop: 14 }}>
          <button
            className="dc-btn"
            onClick={() => setSelectedCats(new Set(MEAL_CATEGORIES.map((c) => c.key)))}
          >
            {tr("全部显示", "Show all")}
          </button>
          <button className="dc-btn dc-btn-filled" onClick={() => setFilterOpen(false)}>
            {tr("完成", "Done")}
          </button>
        </div>
      </Modal>

      {/* 计算热量 confirm */}
      <ConfirmDialog
        open={calcAllOpen}
        title={tr("计算热量", "Calculate calories")}
        message={tr("是否计算所有未计算过的热量", "Calculate all calories not calculated yet?")}
        confirmLabel={tr("计算", "Calculate")}
        onCancel={() => setCalcAllOpen(false)}
        onConfirm={() => {
          setCalcAllOpen(false);
          void enqueueEstimate(
            allDays.filter((d) => d.photos.length > 0 && d.totalEnergyKj == null).map((d) => d.dateIso),
          );
        }}
      />

      {/* 重新计算某日 confirm */}
      <ConfirmDialog
        open={recalcDate !== null}
        title={tr("重新计算热量", "Recalculate calories")}
        message={recalcDate ? tr(`是否重新计算${recalcDate}的食物热量`, `Recalculate food calories for ${recalcDate}?`) : ""}
        confirmLabel={tr("重新计算", "Recalculate")}
        onCancel={() => setRecalcDate(null)}
        onConfirm={() => {
          if (recalcDate) void enqueueEstimate([recalcDate]);
          setRecalcDate(null);
        }}
      />

      {/* 热量详情 dialog */}
      {detailsDate && (
        <EnergyDetailsDialog
          day={canonicalDay(detailsDate)}
          dateIso={detailsDate}
          onClose={() => setDetailsDate(null)}
        />
      )}

      {/* Fullscreen zoom viewer */}
      {zoomed && (
        <ZoomViewer
          photo={zoomed.photo}
          filterCss={filterCss}
          onClose={() => setZoomIndex(null)}
        />
      )}

      <Snackbar message={snack} />
      <PageTutorialOverlay
        pageKey="meals"
        title={tr("吃历", "Meal calendar")}
        lines={[
          tr("日记中标注了餐别的照片会按日期汇总在这里。", "Diary photos tagged with a meal category are grouped here by date."),
          tr("顶栏可筛选餐别、导出长图、开关照片滤镜并进入滤镜设置。", "The top bar filters categories, exports a long image, toggles the photo filter and opens its settings."),
          tr("点击日期后的总热量查看当天热量详情；点照片可全屏放大。", "Tap the total after a date for energy details; tap a photo to zoom."),
        ]}
      />
    </div>
  );
}

function MealPhotoCard(props: {
  photo: MealPhotoView;
  filterCss: string;
  maxImgHeight: number;
  showCaptions: boolean;
  onClick: () => void;
}) {
  const { photo } = props;
  const displayCaption = photo.caption || categoryLabel(photo.categoryKey);
  return (
    <div
      className="dc-card meal-photo-card"
      role="button"
      tabIndex={0}
      style={{ overflow: "hidden", cursor: "pointer" }}
      onClick={props.onClick}
      onKeyDown={(e) => { if (e.key === "Enter") props.onClick(); }}
    >
      <img
        src={photo.thumbUrl}
        alt={displayCaption}
        loading="lazy"
        style={{
          display: "block",
          width: "100%",
          maxHeight: props.maxImgHeight,
          objectFit: "cover",
          filter: props.filterCss === "none" ? undefined : props.filterCss,
        }}
      />
      {props.showCaptions && (
        <div style={{ padding: "7px 10px", fontSize: "0.82em", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
          {displayCaption}
        </div>
      )}
    </div>
  );
}

function EnergyDetailsDialog(props: { day?: MealDayView; dateIso: string; onClose: () => void }) {
  const { day } = props;
  const counters = new Map<string, number>();
  return (
    <Modal open onClose={props.onClose} title={tr(`热量详情 · ${props.dateIso}`, `Energy details · ${props.dateIso}`)} width={640}>
      {!day ? (
        <div className="dc-muted">{tr("无法读取这一天的数据", "Could not read this day's data")}</div>
      ) : (
        <div className="dc-col" style={{ gap: 12 }}>
          <div className="dc-card" style={{ padding: 14 }}>
            <div className="dc-row" style={{ justifyContent: "space-between" }}>
              <span className="dc-muted" style={{ fontSize: "0.85em" }}>{tr("总热量", "Total energy")}</span>
              <span style={{ fontWeight: 700, fontVariantNumeric: "tabular-nums" }}>
                {day.totalEnergyKj != null
                  ? tr(`${day.totalEnergyKj} kJ`, `${day.totalEnergyKj} kJ`)
                  : day.photos.length > 0
                    ? tr("估算失败", "Estimation failed")
                    : "—"}
              </span>
            </div>
            {day.overrideTotal != null && (
              <div className="dc-muted" style={{ fontSize: "0.8em", marginTop: 4 }}>
                {tr("含手工总量覆盖", "Includes a manual total override")}
              </div>
            )}
            {day.note && (
              <div className="dc-muted" style={{ fontSize: "0.85em", marginTop: 8, whiteSpace: "pre-wrap" }}>
                {tr("备注", "Note")}：{day.note}
              </div>
            )}
          </div>

          {day.photos.map((photo, i) => {
            const catKey = photo.categoryKey ?? "photo";
            const n = (counters.get(catKey) ?? 0) + 1;
            counters.set(catKey, n);
            const label = `${categoryLabel(photo.categoryKey)} ${n}`;
            return (
              <div key={photo.key + "#" + i} className="dc-card" style={{ padding: 12 }}>
                <div className="dc-row" style={{ justifyContent: "space-between", gap: 8 }}>
                  <span style={{ fontWeight: 500 }}>{label}</span>
                  <span style={{ fontVariantNumeric: "tabular-nums" }}>
                    {photo.energyKj != null ? tr(`${photo.energyKj} kJ`, `${photo.energyKj} kJ`) : tr("估算失败", "Estimation failed")}
                  </span>
                </div>
                {photo.foods.length > 0 && (
                  <div className="dc-col" style={{ gap: 2, marginTop: 6, fontSize: "0.84em" }}>
                    {photo.foods.map((f, fi) => (
                      <div key={fi} className="dc-row" style={{ justifyContent: "space-between", gap: 8 }}>
                        <span className="dc-muted">
                          {f.name}{f.amount ? ` ${f.amount}` : ""}{f.unit ? ` ${f.unit}` : ""}
                        </span>
                        <span className="dc-muted" style={{ fontVariantNumeric: "tabular-nums" }}>
                          {f.energyKj != null ? `${f.energyKj} kJ` : ""}
                        </span>
                      </div>
                    ))}
                  </div>
                )}
                {photo.location && (
                  <div className="dc-muted" style={{ fontSize: "0.8em", marginTop: 6 }}>
                    {photo.location}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
      <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 14 }}>
        <button className="dc-btn dc-btn-filled" onClick={props.onClose}>{tr("关闭", "Close")}</button>
      </div>
    </Modal>
  );
}

function ZoomViewer(props: { photo: MealPhotoView; filterCss: string; onClose: () => void }) {
  const { photo } = props;
  const [scale, setScale] = useState(1);
  const [offset, setOffset] = useState({ x: 0, y: 0 });
  const dragRef = useRef<{ x: number; y: number; ox: number; oy: number } | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const clampScale = (s: number) => Math.min(6, Math.max(1, s));
  const applyScale = (s: number) => {
    const next = clampScale(s);
    setScale(next);
    if (next <= 1) setOffset({ x: 0, y: 0 });
  };

  const displayCaption = photo.caption || categoryLabel(photo.categoryKey);
  const captionBits = [
    displayCaption,
    photo.energyKj != null ? tr(`${photo.energyKj} kJ`, `${photo.energyKj} kJ`) : null,
    photo.location,
  ].filter((x): x is string => !!x);

  return (
    <div
      ref={containerRef}
      style={{
        position: "fixed", inset: 0, zIndex: 420, background: "rgba(0,0,0,0.94)",
        display: "flex", flexDirection: "column",
      }}
      onClick={props.onClose}
      onWheel={(e) => {
        e.preventDefault();
        applyScale(scale * (e.deltaY < 0 ? 1.15 : 0.87));
      }}
    >
      <div className="dc-row" style={{ justifyContent: "flex-end", padding: 10 }}>
        <button className="dc-icon-btn" aria-label={tr("关闭", "Close")} style={{ color: "#fff" }} onClick={props.onClose}>
          <X size={22} />
        </button>
      </div>
      <div className="dc-grow dc-center" style={{ overflow: "hidden", minHeight: 0 }}>
        <img
          src={photo.fullUrl}
          alt={displayCaption}
          draggable={false}
          onClick={(e) => e.stopPropagation()}
          onDoubleClick={(e) => {
            e.stopPropagation();
            applyScale(scale >= 2 ? 1 : 2.5);
          }}
          onPointerDown={(e) => {
            if (scale <= 1) return;
            e.stopPropagation();
            dragRef.current = { x: e.clientX, y: e.clientY, ox: offset.x, oy: offset.y };
            (e.target as HTMLElement).setPointerCapture(e.pointerId);
          }}
          onPointerMove={(e) => {
            const d = dragRef.current;
            if (!d) return;
            setOffset({ x: d.ox + (e.clientX - d.x), y: d.oy + (e.clientY - d.y) });
          }}
          onPointerUp={() => { dragRef.current = null; }}
          style={{
            maxWidth: "96vw",
            maxHeight: "78vh",
            transform: `translate(${offset.x}px, ${offset.y}px) scale(${scale})`,
            transition: dragRef.current ? "none" : "transform 0.15s ease",
            filter: props.filterCss === "none" ? undefined : props.filterCss,
            touchAction: "none",
            cursor: scale > 1 ? "grab" : "zoom-in",
          }}
        />
      </div>
      <div style={{
        padding: "12px 16px calc(16px + env(safe-area-inset-bottom))",
        color: "#fff", textAlign: "center", fontSize: "0.9em",
      }}>
        {captionBits.join(tr(" · ", " · "))}
      </div>
    </div>
  );
}
