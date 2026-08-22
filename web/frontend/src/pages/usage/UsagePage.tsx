/**
 * 手机使用时间 (/usage_statistics) — README_for_ai §14。
 *
 * The web server never collects usage by itself: data arrives through cloud
 * sync of `records/usage` or by importing an exported usage JSON (POST
 * /api/usage/import). The page shows per-device overviews, daily bars, a sorted
 * app ranking and the privacy note; every API shape is parsed defensively so a
 * fresh install renders an honest empty state instead of fake zeros.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Upload } from "lucide-react";
import { apiGet, apiUpload } from "../../api/client";
import { tr } from "../../i18n/tr";
import {
  EmptyState, ErrorText, PageTutorialOverlay, Snackbar, Spinner, TopBar,
  useSnackbar,
} from "../../components/ui";
import { BarChart } from "../../components/charts";

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

/** Compact H/M duration mirroring the Android page (e.g. 1H 23M). */
export function formatCompactMinutes(minutes: number): string {
  const total = Math.max(0, Math.round(minutes));
  const h = Math.floor(total / 60);
  const m = total % 60;
  if (h > 0) return `${h}H ${m}M`;
  return `${m}M`;
}

interface UsageDay {
  date: string;
  minutes: number;
}

interface UsageApp {
  key: string;
  label: string;
  minutes: number;
}

interface UsageDeviceInfo {
  id: string;
  name: string;
  platform: string;
  recordedDays: number;
}

interface UsageOverview {
  totalMinutes: number;
  days: UsageDay[];
  apps: UsageApp[];
}

function parseDay(raw: unknown): UsageDay | null {
  const r = asRecord(raw);
  if (!r) return null;
  const date = str(r["date"]) || str(r["dayIso"]) || str(r["day"]);
  let minutes = -1;
  for (const key of ["totalMinutes", "minutes", "totalTimeMinutes"]) {
    const v = r[key];
    if (typeof v === "number" && Number.isFinite(v)) {
      minutes = v;
      break;
    }
  }
  if (minutes < 0) {
    const ms = r["totalMs"] ?? r["totalTimeMs"];
    minutes = typeof ms === "number" && Number.isFinite(ms) ? ms / 60000 : 0;
  }
  if (!date) return null;
  return { date: date.slice(0, 10), minutes: Math.max(0, minutes) };
}

function parseOverview(data: unknown): UsageOverview {
  const root = asRecord(data);
  const days = asArray(root?.["days"] ?? root?.["daily"])
    .map(parseDay)
    .filter((d): d is UsageDay => d !== null);
  const apps = asArray(root?.["apps"])
    .map((raw) => {
      const r = asRecord(raw);
      if (!r) return null;
      const pkg = str(r["packageName"]) || str(r["package"]) || str(r["appId"]) || str(r["id"]);
      const label = str(r["label"]) || str(r["appName"]) || str(r["name"]) || pkg;
      let minutes = -1;
      for (const key of ["totalMinutes", "minutes"]) {
        const v = r[key];
        if (typeof v === "number" && Number.isFinite(v)) {
          minutes = v;
          break;
        }
      }
      if (minutes < 0) {
        const ms = r["totalMs"] ?? r["totalTimeMs"];
        minutes = typeof ms === "number" && Number.isFinite(ms) ? ms / 60000 : 0;
      }
      if (!label) return null;
      return { key: pkg || label, label, minutes: Math.max(0, minutes) };
    })
    .filter((a): a is UsageApp => a !== null);

  let totalMinutes = -1;
  const rawTotal = root?.["totalMinutes"];
  if (typeof rawTotal === "number" && Number.isFinite(rawTotal)) totalMinutes = rawTotal;
  if (totalMinutes < 0) {
    const rawMs = root?.["totalMs"] ?? root?.["lastSevenTotalMs"];
    if (typeof rawMs === "number" && Number.isFinite(rawMs)) totalMinutes = rawMs / 60000;
  }
  if (totalMinutes < 0) totalMinutes = days.reduce((sum, d) => sum + d.minutes, 0);
  return { totalMinutes: Math.max(0, totalMinutes), days, apps };
}

function parseDevices(data: unknown): UsageDeviceInfo[] {
  const list = asArray(Array.isArray(data) ? data : asRecord(data)?.["devices"]);
  const out: UsageDeviceInfo[] = [];
  for (const raw of list) {
    const r = asRecord(raw);
    if (!r) continue;
    const id = str(r["id"]) || str(r["deviceId"]) || str(r["deviceKey"]);
    if (!id) continue;
    out.push({
      id,
      name: str(r["name"]) || str(r["deviceName"]) || str(r["label"]) || id,
      platform: str(r["platform"]),
      recordedDays: num(r["recordedDays"] ?? r["days"]),
    });
  }
  return out;
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

type RangeDays = 7 | 30 | 90;

const RANGE_OPTIONS: { value: RangeDays; label: string }[] = [
  { value: 7, label: tr("近7天", "Last 7 days") },
  { value: 30, label: tr("近30天", "Last 30 days") },
  { value: 90, label: tr("近90天", "Last 90 days") },
];

export default function UsagePage() {
  const fileRef = useRef<HTMLInputElement>(null);
  const [snackbarMessage, showSnackbar] = useSnackbar();

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [devices, setDevices] = useState<UsageDeviceInfo[]>([]);
  const [deviceId, setDeviceId] = useState<string>("");
  const [rangeDays, setRangeDays] = useState<RangeDays>(7);
  const [overview, setOverview] = useState<UsageOverview | null>(null);
  const [importing, setImporting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params = new URLSearchParams({ days: String(rangeDays) });
      if (deviceId) params.set("deviceId", deviceId);
      const [ovr, devs] = await Promise.all([
        apiGet<unknown>(`/api/usage/overview?${params.toString()}`),
        apiGet<unknown>("/api/usage/devices").catch(() => null),
      ]);
      setOverview(parseOverview(ovr));
      if (devs !== null) setDevices(parseDevices(devs));
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  }, [rangeDays, deviceId]);

  useEffect(() => {
    void load();
  }, [load]);

  const onImportFile = async (file: File | undefined) => {
    if (!file) return;
    setImporting(true);
    setError(null);
    try {
      const res = asRecord(await apiUpload<unknown>("/api/usage/import", file));
      const parts: string[] = [];
      const dayCount = res ? num(res["importedDays"] ?? res["days"], NaN) : NaN;
      const devCount = res ? num(res["devices"], NaN) : NaN;
      if (Number.isFinite(dayCount)) parts.push(tr("导入天数", "Days imported") + ` ${dayCount}`);
      if (Number.isFinite(devCount)) parts.push(tr("设备数", "Devices") + ` ${devCount}`);
      const message = res ? str(res["message"]) : "";
      showSnackbar(
        message ||
          (parts.length > 0
            ? `${tr("导入完成", "Import complete")} · ${parts.join(" · ")}`
            : tr("导入完成", "Import complete")),
      );
      await load();
    } catch (e) {
      setError(e);
      showSnackbar(tr("导入失败", "Import failed"));
    } finally {
      setImporting(false);
      if (fileRef.current) fileRef.current.value = "";
    }
  };

  const hasAnyData = !!overview &&
    (overview.days.some((d) => d.minutes > 0) || overview.apps.some((a) => a.minutes > 0));

  const chartLabels = useMemo(
    () => (overview ? overview.days.map((d) => d.date.slice(5)) : []),
    [overview],
  );
  const chartValues = useMemo(
    () => (overview ? overview.days.map((d) => d.minutes) : []),
    [overview],
  );

  const sortedApps = useMemo(() => {
    if (!overview) return [];
    return [...overview.apps].sort((a, b) => b.minutes - a.minutes || a.label.localeCompare(b.label));
  }, [overview]);
  const appTotal = sortedApps.reduce((sum, a) => sum + a.minutes, 0);
  const appMax = sortedApps.length > 0 ? sortedApps[0].minutes : 0;

  return (
    <div className="dc-page">
      <TopBar
        title={tr("手机使用时间", "Screen time")}
        subtitle={tr("数据来自云同步或手动导入，Web 端不自动采集。", "Data comes from cloud sync or manual import; the web server does not collect it.")}
        actions={
          <>
            {importing && <Spinner size={20} />}
            <button className="dc-btn dc-btn-tonal" disabled={importing} onClick={() => fileRef.current?.click()}>
              <Upload size={16} />{tr("导入数据", "Import data")}
            </button>
          </>
        }
      />
      <input
        ref={fileRef} type="file" accept=".json,application/json" hidden
        onChange={(e) => void onImportFile(e.target.files?.[0])}
      />
      <ErrorText error={error} />

      {loading && !overview ? (
        <Spinner />
      ) : (
        <div className="dc-col" style={{ gap: 12 }}>
          {/* 统计对象 + 范围 */}
          <div className="dc-card dc-col" style={{ padding: 14, gap: 12 }}>
            <div className="dc-row dc-wrap" style={{ gap: 12 }}>
              <label className="dc-row" style={{ gap: 8 }}>
                <span style={{ fontSize: "0.9em" }}>{tr("统计对象", "Statistics target")}</span>
                <select
                  className="dc-input" style={{ maxWidth: 260 }}
                  value={deviceId}
                  aria-label={tr("选择设备", "Choose device")}
                  onChange={(e) => setDeviceId(e.target.value)}
                >
                  <option value="">{tr("所有设备", "All devices")}</option>
                  {devices.map((d) => (
                    <option key={d.id} value={d.id}>
                      {`${d.name}${d.platform ? ` · ${d.platform}` : ""}${
                        d.recordedDays > 0 ? tr(`（${d.recordedDays} 天）`, ` (${d.recordedDays} d)`) : ""
                      }`}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <div role="group" aria-label={tr("日期范围", "Date range")}>
              <SegmentedRange value={rangeDays} onChange={setRangeDays} />
            </div>
          </div>

          {!hasAnyData ? (
            <EmptyState
              title={tr("还没有使用时间数据", "No screen-time data yet")}
              hint={
                <>
                  {tr(
                    "统计默认关闭：先在 Android 端开启统计并授权，再通过云端同步或「导入数据」把记录带到本服务器。",
                    "Collection is off by default: enable it on your phone first, then bring records here via cloud sync or “Import data”.",
                  )}
                </>
              }
            />
          ) : (
            <>
              {/* 总览 */}
              <section className="dc-card dc-col" style={{ padding: 14, gap: 10 }}>
                <div className="dc-row" style={{ justifyContent: "space-between" }}>
                  <span style={{ fontWeight: 600 }}>{tr("总览", "Overview")}</span>
                  <span className="dc-muted" style={{ fontSize: "0.9em" }}>
                    {tr("总时长", "Total time")}
                    ：{formatCompactMinutes(overview?.totalMinutes ?? 0)}
                  </span>
                </div>
                {chartValues.length > 0 && (
                  <BarChart
                    series={[{ values: chartValues }]}
                    labels={chartLabels}
                    height={160}
                    formatValue={(v) => formatCompactMinutes(v)}
                  />
                )}
              </section>

              {/* App 列表 */}
              <section className="dc-card dc-col" style={{ padding: 14, gap: 10 }}>
                <div style={{ fontWeight: 600 }}>{tr("应用列表", "App ranking")}</div>
                <style>{`
                  .dc-usage-app-bar { height: 8px; border-radius: 4px; background: var(--dc-primary); min-width: 2px; }
                `}</style>
                {sortedApps.map((a) => {
                  const pctOfTotal = appTotal > 0 ? (a.minutes / appTotal) * 100 : 0;
                  const widthPct = appMax > 0 ? (a.minutes / appMax) * 100 : 0;
                  return (
                    <div key={a.key} className="dc-col" style={{ gap: 4 }}>
                      <div className="dc-row" style={{ justifyContent: "space-between", gap: 12 }}>
                        <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{a.label}</span>
                        <span className="dc-muted" style={{ fontSize: "0.85em", flexShrink: 0 }}>
                          {formatCompactMinutes(a.minutes)} · {pctOfTotal.toFixed(1)}%
                        </span>
                      </div>
                      <div style={{ background: "var(--dc-surface-container-high)", borderRadius: 4 }}>
                        <div className="dc-usage-app-bar" style={{ width: `${Math.max(1, widthPct)}%` }} />
                      </div>
                    </div>
                  );
                })}
              </section>
            </>
          )}

          {/* 隐私说明 */}
          <div className="dc-card dc-col dc-muted" style={{ padding: 14, gap: 6, fontSize: "0.86em" }}>
            <span>{tr("数据仅存本服务器，可随时删除导入的数据。", "Data is stored only on this server; imported data can be deleted at any time.")}</span>
            <span>{tr("关闭统计开关不会删除已有历史；Web 端不读取手机系统的事件流。", "Turning collection off keeps existing history; the web server never reads system event streams.")}</span>
          </div>
        </div>
      )}

      <PageTutorialOverlay
        pageKey="usage_statistics"
        title={tr("手机使用时间", "Screen time")}
        lines={[
          tr("先在设置里打开统计开关，Android 端采集后经云同步或导入到达这里。", "Enable the toggle in settings first; records arrive via cloud sync or import."),
          tr("可切换所有设备或单台设备，以及近 7/30/90 天范围。", "Switch between all devices or one device, and 7/30/90-day ranges."),
        ]}
      />
      <Snackbar message={snackbarMessage} />
    </div>
  );
}

/** Local segmented control for the three fixed ranges (keeps this file self-contained). */
function SegmentedRange(props: { value: RangeDays; onChange: (v: RangeDays) => void }) {
  return (
    <div role="radiogroup" style={{
      display: "inline-flex", border: "var(--dc-border-width) solid var(--dc-outline-variant)",
      borderRadius: "calc(var(--dc-radius) * 0.6)", overflow: "hidden",
    }}>
      {RANGE_OPTIONS.map((o) => {
        const active = o.value === props.value;
        return (
          <button key={o.value} type="button" role="radio" aria-checked={active}
            onClick={() => props.onChange(o.value)}
            style={{
              padding: "8px 18px", border: "none", fontSize: "0.9em", whiteSpace: "nowrap",
              background: active ? "var(--dc-secondary-container)" : "transparent",
              color: active ? "var(--dc-on-secondary-container)" : "var(--dc-on-surface)",
            }}>
            {o.label}
          </button>
        );
      })}
    </div>
  );
}
