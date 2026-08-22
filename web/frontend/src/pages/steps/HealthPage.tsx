/**
 * 健康 (/step_statistics) — README_for_ai §15。
 *
 * The web server never reads Health Connect: daily steps / distance / active
 * calories arrive by importing an exported health JSON (POST /api/health/import)
 * or through cloud sync. Shows metric tabs, today/7-day/30-day totals cards,
 * trend line + daily bars, a descending detail table and the data-source note.
 * All response shapes are parsed defensively; missing values stay unknown
 * instead of being fabricated as 0.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Upload } from "lucide-react";
import { apiGet, apiUpload } from "../../api/client";
import { tr } from "../../i18n/tr";
import {
  EmptyState, ErrorText, PageTutorialOverlay, Snackbar, Spinner, TopBar,
  useSnackbar,
} from "../../components/ui";
import { BarChart, LineChart } from "../../components/charts";

// ---------------------------------------------------------------------------
// Defensive parsing helpers
// ---------------------------------------------------------------------------

function asRecord(v: unknown): Record<string, unknown> | null {
  return v !== null && typeof v === "object" && !Array.isArray(v)
    ? (v as Record<string, unknown>)
    : null;
}

function num(v: unknown, fallback = 0): number {
  return typeof v === "number" && Number.isFinite(v) ? v : fallback;
}

function str(v: unknown): string {
  return typeof v === "string" && v.length > 0 ? v : "";
}

function asArray(v: unknown): unknown[] {
  return Array.isArray(v) ? v : [];
}

interface HealthDay {
  date: string;
  steps: number;
  distanceMeters: number;
  activeCaloriesKcal: number;
}

function parseHealthDay(raw: unknown): HealthDay | null {
  const r = asRecord(raw);
  if (!r) return null;
  const date = str(r["date"]) || str(r["dayIso"]) || str(r["day"]);
  if (!date) return null;
  return {
    date: date.slice(0, 10),
    steps: num(r["steps"] ?? r["stepCount"]),
    distanceMeters: num(r["distanceMeters"] ?? r["distance"]),
    activeCaloriesKcal: num(r["activeCaloriesKcal"] ?? r["calories"]),
  };
}

export function todayIso(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

type Metric = "steps" | "distance" | "calories";

const METRIC_OPTIONS: { value: Metric; label: string }[] = [
  { value: "steps", label: tr("步数", "Steps") },
  { value: "distance", label: tr("距离(km)", "Distance (km)") },
  { value: "calories", label: tr("活动热量(kcal)", "Active kcal") },
];

/** Query window for GET /api/health/overview?days= — default 近30天. */
const RANGE_OPTIONS: { value: 7 | 30 | 90; label: string }[] = [
  { value: 7, label: tr("近7天", "Last 7 days") },
  { value: 30, label: tr("近30天", "Last 30 days") },
  { value: 90, label: tr("近90天", "Last 90 days") },
];

function formatDistance(meters: number): string {
  if (meters >= 1000) return `${tr((meters / 1000).toFixed(2) + " km", (meters / 1000).toFixed(2) + " km")}`;
  return `${Math.round(meters)} ${tr("m", "m")}`;
}

function metricValue(day: HealthDay, metric: Metric): number {
  switch (metric) {
    case "steps": return day.steps;
    case "distance": return day.distanceMeters;
    case "calories": return day.activeCaloriesKcal;
  }
}

function formatMetric(value: number, metric: Metric): string {
  switch (metric) {
    case "steps": return Math.round(value).toLocaleString("en-US");
    case "distance": return formatDistance(value);
    case "calories": return `${(Math.round(value * 10) / 10).toLocaleString("en-US")} ${tr("kcal", "kcal")}`;
  }
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export default function HealthPage() {
  const fileRef = useRef<HTMLInputElement>(null);
  const [snackbarMessage, showSnackbar] = useSnackbar();

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [days, setDays] = useState<HealthDay[]>([]);
  const [metric, setMetric] = useState<Metric>("steps");
  const [rangeDays, setRangeDays] = useState<7 | 30 | 90>(30);
  const [importing, setImporting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await apiGet<unknown>(`/api/health/overview?days=${rangeDays}`);
      const root = asRecord(data);
      const parsed = asArray(root?.["days"] ?? root?.["daily"])
        .map(parseHealthDay)
        .filter((d): d is HealthDay => d !== null);
      parsed.sort((a, b) => b.date.localeCompare(a.date)); // descending everywhere
      setDays(parsed);
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  }, [rangeDays]);

  useEffect(() => {
    void load();
  }, [load]);

  const onImportFile = async (file: File | undefined) => {
    if (!file) return;
    setImporting(true);
    setError(null);
    try {
      await apiUpload("/api/health/import", file);
      showSnackbar(tr("导入完成", "Import complete"));
      await load();
    } catch (e) {
      setError(e);
      showSnackbar(tr("导入失败", "Import failed"));
    } finally {
      setImporting(false);
      if (fileRef.current) fileRef.current.value = "";
    }
  };

  const today = todayIso();
  const sumWithin = useCallback(
    (limitDays: number) => {
      // `days` is descending by date; take the most recent N recorded days that
      // fall inside the window ending today.
      const cutoff = new Date(`${today}T00:00:00`);
      cutoff.setDate(cutoff.getDate() - (limitDays - 1));
      const cutoffIso = cutoff.toISOString().slice(0, 10);
      return days
        .filter((d) => d.date >= cutoffIso && d.date <= today)
        .reduce<HealthDay>((acc, d) => ({
          date: acc.date,
          steps: acc.steps + d.steps,
          distanceMeters: acc.distanceMeters + d.distanceMeters,
          activeCaloriesKcal: acc.activeCaloriesKcal + d.activeCaloriesKcal,
        }), { date: "", steps: 0, distanceMeters: 0, activeCaloriesKcal: 0 });
    },
    [days, today],
  );

  const todayTotals = useMemo(() => sumWithin(1), [sumWithin]);
  const sevenTotals = useMemo(() => sumWithin(7), [sumWithin]);
  const thirtyTotals = useMemo(() => sumWithin(30), [sumWithin]);

  const hasAnyData = days.some(
    (d) => d.steps > 0 || d.distanceMeters > 0 || d.activeCaloriesKcal > 0,
  );

  // Charts use chronological order over the selected range's loaded days.
  const chartWindow = useMemo(() => days.slice().reverse(), [days]);
  const chartLabels = chartWindow.map((d) => d.date.slice(5));
  const chartValues = chartWindow.map((d) => metricValue(d, metric));

  return (
    <div className="dc-page">
      <TopBar
        title={tr("健康", "Health")}
        subtitle={tr("步数、距离与活动热量来自手动导入或云同步。", "Steps, distance and active calories come from manual import or cloud sync.")}
        actions={
          <>
            {importing && <Spinner size={20} />}
            <button className="dc-btn dc-btn-tonal" disabled={importing} onClick={() => fileRef.current?.click()}>
              <Upload size={16} />{tr("导入", "Import")}
            </button>
          </>
        }
      />
      <input
        ref={fileRef} type="file" accept=".json,application/json" hidden
        onChange={(e) => void onImportFile(e.target.files?.[0])}
      />
      <ErrorText error={error} />

      {loading && days.length === 0 ? (
        <Spinner />
      ) : !hasAnyData ? (
        <EmptyState
          title={tr("还没有健康数据", "No health data yet")}
          hint={tr(
            "点右上角「导入」上传 Android 端导出的健康统计 JSON；没有可信数据时这里保持空白，不会伪造 0。",
            "Use “Import” to upload an exported health JSON from your phone; without trusted data this page stays empty instead of faking zeros.",
          )}
        />
      ) : (
        <div className="dc-col" style={{ gap: 12 }}>
          {/* 时间范围切换：驱动 /api/health/overview?days= 查询 */}
          <div role="radiogroup" aria-label={tr("时间范围", "Range")} style={{
            display: "inline-flex", alignSelf: "flex-start",
            border: "var(--dc-border-width) solid var(--dc-outline-variant)",
            borderRadius: "calc(var(--dc-radius) * 0.6)", overflow: "hidden",
          }}>
            {RANGE_OPTIONS.map((o) => {
              const active = o.value === rangeDays;
              return (
                <button key={o.value} type="button" role="radio" aria-checked={active}
                  onClick={() => setRangeDays(o.value)}
                  style={{
                    padding: "8px 14px", border: "none", fontSize: "0.9em", whiteSpace: "nowrap",
                    background: active ? "var(--dc-secondary-container)" : "transparent",
                    color: active ? "var(--dc-on-secondary-container)" : "var(--dc-on-surface)",
                  }}>
                  {o.label}
                </button>
              );
            })}
          </div>

          {/* 指标切换 */}
          <div role="radiogroup" aria-label={tr("指标", "Metric")} style={{
            display: "inline-flex", alignSelf: "flex-start",
            border: "var(--dc-border-width) solid var(--dc-outline-variant)",
            borderRadius: "calc(var(--dc-radius) * 0.6)", overflow: "hidden",
          }}>
            {METRIC_OPTIONS.map((o) => {
              const active = o.value === metric;
              return (
                <button key={o.value} type="button" role="radio" aria-checked={active}
                  onClick={() => setMetric(o.value)}
                  style={{
                    padding: "8px 14px", border: "none", fontSize: "0.9em", whiteSpace: "nowrap",
                    background: active ? "var(--dc-secondary-container)" : "transparent",
                    color: active ? "var(--dc-on-secondary-container)" : "var(--dc-on-surface)",
                  }}>
                  {o.label}
                </button>
              );
            })}
          </div>

          {/* 总览数据卡 */}
          <div className="dc-row dc-wrap" style={{ gap: 12, alignItems: "stretch" }}>
            <TotalCard title={tr("今天", "Today")} metric={metric}
              steps={todayTotals.steps} meters={todayTotals.distanceMeters} kcal={todayTotals.activeCaloriesKcal} />
            <TotalCard title={tr("近7天", "Last 7 days")} metric={metric}
              steps={sevenTotals.steps} meters={sevenTotals.distanceMeters} kcal={sevenTotals.activeCaloriesKcal} />
            <TotalCard title={tr("近30天", "Last 30 days")} metric={metric}
              steps={thirtyTotals.steps} meters={thirtyTotals.distanceMeters} kcal={thirtyTotals.activeCaloriesKcal} />
            <TotalCard title={tr("已记录天数", "Recorded days")} metric={null}
              steps={days.length} meters={0} kcal={0} />
          </div>

          {/* 图表 */}
          {chartWindow.length > 0 && (
            <>
              <section className="dc-card dc-col" style={{ padding: 14, gap: 10 }}>
                <div style={{ fontWeight: 600 }}>{tr("趋势", "Trend")}</div>
                <LineChart series={[{ values: chartValues }]} labels={chartLabels} height={150}
                  formatValue={(v) => formatMetric(v, metric)} />
              </section>
              <section className="dc-card dc-col" style={{ padding: 14, gap: 10 }}>
                <div style={{ fontWeight: 600 }}>{tr("每日", "Daily")}</div>
                <BarChart series={[{ values: chartValues }]} labels={chartLabels} height={150}
                  formatValue={(v) => formatMetric(v, metric)} />
              </section>
            </>
          )}

          {/* 每日明细 */}
          <section className="dc-card dc-col" style={{ padding: 14, gap: 8 }}>
            <div style={{ fontWeight: 600 }}>{tr("明细", "Daily details")}</div>
            <div style={{ overflowX: "auto" }}>
              <table style={{ width: "100%", borderCollapse: "collapse", fontSize: "0.9em" }}>
                <thead>
                  <tr className="dc-muted" style={{ textAlign: "left" }}>
                    <th style={cellStyle}>{tr("日期", "Date")}</th>
                    <th style={cellStyle}>{tr("步数", "Steps")}</th>
                    <th style={cellStyle}>{tr("距离", "Distance")}</th>
                    <th style={cellStyle}>{tr("活动热量", "Active calories")}</th>
                  </tr>
                </thead>
                <tbody>
                  {days.map((d) => (
                    <tr key={d.date}>
                      <td style={cellStyle}>{d.date}</td>
                      <td style={cellStyle}>{formatMetric(d.steps, "steps")}</td>
                      <td style={cellStyle}>{formatDistance(d.distanceMeters)}</td>
                      <td style={cellStyle}>{formatMetric(d.activeCaloriesKcal, "calories")}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </div>
      )}

      {/* 状态说明 */}
      <div className="dc-card dc-col dc-muted" style={{ padding: 14, gap: 6, marginTop: 12, fontSize: "0.86em" }}>
        <span style={{ fontWeight: 600, color: "var(--dc-on-surface)" }}>{tr("状态说明", "Status notes")}</span>
        <span>{tr(
          "数据来自手动导入的 Android 健康导出文件；Web 端不连接 Health Connect，也不使用计步传感器。",
          "Data comes from manually imported Android health export files; the web server never talks to Health Connect or step sensors.",
        )}</span>
        <span>{tr(
          "步数、距离与活动热量只用于本页总览、图表和明细；不会从步数推算距离或热量，缺失的日期不会补成 0。",
          "Steps, distance and active calories feed only this page's overview, charts and details; nothing is derived and missing days stay missing instead of becoming 0.",
        )}</span>
      </div>

      <PageTutorialOverlay
        pageKey="step_statistics"
        title={tr("健康", "Health")}
        lines={[
          tr("用顶部按钮在步数、距离与活动热量之间切换。", "Switch between steps, distance and active calories at the top."),
          tr("数据通过「导入」上传的 Android 健康导出文件进入本服务器。", "Data arrives by importing an exported Android health file."),
        ]}
      />
      <Snackbar message={snackbarMessage} />
    </div>
  );
}

const cellStyle: React.CSSProperties = { padding: "6px 10px 6px 0", borderBottom: "var(--dc-border-width) solid var(--dc-outline-variant)", whiteSpace: "nowrap" };

function TotalCard(props: {
  title: React.ReactNode;
  metric: Metric | null;
  steps: number;
  meters: number;
  kcal: number;
}) {
  let value: string;
  if (props.metric === null) {
    value = String(Math.round(props.steps));
  } else {
    switch (props.metric) {
      case "steps": value = formatMetric(props.steps, "steps"); break;
      case "distance": value = formatDistance(props.meters); break;
      case "calories": value = formatMetric(props.kcal, "calories"); break;
    }
  }
  return (
    <div className="dc-card dc-col dc-grow" style={{ padding: 14, gap: 4, minWidth: 140 }}>
      <span className="dc-muted" style={{ fontSize: "0.82em" }}>{props.title}</span>
      <span style={{ fontWeight: 600, fontSize: "1.05em" }}>{value}</span>
    </div>
  );
}
