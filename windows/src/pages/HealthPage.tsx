import {
  Activity,
  FileDown,
  FileSymlink,
  Flame,
  Footprints,
  HeartPulse,
  RefreshCw,
  Ruler,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { EmptyState, ErrorState, LoadingState, PageFrame } from "../components";
import {
  HEALTH_DTO_VERSION,
  healthApi,
  type HealthMetric,
  type HealthPointV1,
  type HealthSnapshotV1,
  type HealthSourceMode,
} from "../lib/healthApi";
import { dateFromI64Milliseconds, readableError, tr } from "../lib/ipc";
import type { DecimalI64, UsageRange } from "../lib/usageApi";
import { useAppStore } from "../store/appStore";

function numberValue(value: string | null): number | null {
  if (value === null || !/^-?\d+(?:\.\d+)?(?:e[+-]?\d+)?$/i.test(value)) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function formatHealthValue(
  value: string | null,
  metric: HealthMetric,
  language: "zh-CN" | "en",
): string {
  const parsed = numberValue(value);
  if (parsed === null) return "—";
  const locale = language === "en" ? "en-US" : "zh-CN";
  if (metric === "STEPS") {
    return `${new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(parsed)} ${
      language === "en" ? "steps" : "步"
    }`;
  }
  if (metric === "DISTANCE") {
    if (parsed >= 1_000) {
      return `${new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(parsed / 1_000)} km`;
    }
    return `${new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(parsed)} m`;
  }
  return `${new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(parsed)} kcal`;
}

function formatReadTime(value: DecimalI64, language: "zh-CN" | "en"): string {
  const date = dateFromI64Milliseconds(value);
  return date
    ? new Intl.DateTimeFormat(language === "en" ? "en-US" : "zh-CN", {
        dateStyle: "medium",
        timeStyle: "short",
      }).format(date)
    : "—";
}

function rangeLabel(range: UsageRange, language: "zh-CN" | "en"): string {
  const labels: Record<UsageRange, [string, string]> = {
    LAST_7_DAYS: ["近 7 天", "Last 7 days"],
    LAST_30_DAYS: ["近 30 天", "Last 30 days"],
    LAST_90_DAYS: ["近 90 天", "Last 90 days"],
    ALL: ["全部", "All"],
  };
  return language === "en" ? labels[range][1] : labels[range][0];
}

function metricLabel(metric: HealthMetric, language: "zh-CN" | "en"): string {
  const labels: Record<HealthMetric, [string, string]> = {
    STEPS: ["步数", "Steps"],
    DISTANCE: ["距离", "Distance"],
    ACTIVE_CALORIES: ["活动热量", "Active calories"],
  };
  return language === "en" ? labels[metric][1] : labels[metric][0];
}

function HealthBars({
  points,
  metric,
  language,
}: {
  points: HealthPointV1[];
  metric: HealthMetric;
  language: "zh-CN" | "en";
}) {
  const max = points.reduce((current, point) => {
    const value = numberValue(point.value);
    return value !== null && value > current ? value : current;
  }, 0);
  const locale = language === "en" ? "en-US" : "zh-CN";

  return (
    <div
      className="usage-chart-scroll"
      role="region"
      aria-label={tr(language, "每日健康数据图表", "Daily health chart")}
      tabIndex={0}
    >
      <ol className="usage-bar-chart">
        {points.map((point) => {
          const value = numberValue(point.value);
          const height = value === null || max === 0 ? 0 : (value / max) * 100;
          const dateLabel = new Intl.DateTimeFormat(locale, {
            month: "short",
            day: "numeric",
          }).format(new Date(`${point.date}T00:00:00`));
          const accessible = tr(
            language,
            `${point.date}，${
              value === null ? "无可信数据" : formatHealthValue(point.value, metric, language)
            }${point.state === "OPEN" ? "，当天数据仍可刷新" : ""}`,
            `${point.date}, ${
              value === null ? "no trusted data" : formatHealthValue(point.value, metric, language)
            }${point.state === "OPEN" ? ", current day can still change" : ""}`,
          );
          return (
            <li key={point.date}>
              <button
                className="usage-bar-button"
                type="button"
                aria-label={accessible}
                title={accessible}
              >
                <span className="usage-bar-track" aria-hidden="true">
                  {value !== null ? (
                    <span
                      className={point.state === "OPEN" ? "usage-bar is-open" : "usage-bar"}
                      style={{ height: `${Math.max(height, value > 0 ? 3 : 0)}%` }}
                    />
                  ) : (
                    <span className="usage-bar is-missing" />
                  )}
                </span>
                <small>{dateLabel}</small>
              </button>
            </li>
          );
        })}
      </ol>
    </div>
  );
}

export default function HealthPage() {
  const language = useAppStore((state) => state.appearance.language);
  const copy = useCallback(
    (zh: string, en: string) => tr(language, zh, en),
    [language],
  );
  const [range, setRange] = useState<UsageRange>("LAST_30_DAYS");
  const [metric, setMetric] = useState<HealthMetric>("STEPS");
  const [snapshot, setSnapshot] = useState<HealthSnapshotV1 | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const loadSequence = useRef(0);

  const query = useMemo(
    () => ({ dtoVersion: HEALTH_DTO_VERSION, range, metric }),
    [metric, range],
  );

  const load = useCallback(async () => {
    const sequence = ++loadSequence.current;
    setLoading(true);
    setError("");
    try {
      const next = await healthApi.page(query);
      if (sequence === loadSequence.current) setSnapshot(next);
    } catch (reason) {
      if (sequence === loadSequence.current) setError(readableError(reason, language));
    } finally {
      if (sequence === loadSequence.current) setLoading(false);
    }
  }, [language, query]);

  useEffect(() => {
    void load();
  }, [load]);

  async function chooseSource(mode: HealthSourceMode) {
    ++loadSequence.current;
    setBusy(mode);
    setLoading(false);
    setError("");
    setNotice("");
    try {
      const chosen = await healthApi.chooseSource(mode);
      if (chosen) {
        const filtered = await healthApi.page(query);
        setSnapshot(filtered ?? chosen);
        setNotice(
          mode === "snapshot"
            ? copy("健康统计快照已导入。", "Health snapshot imported.")
            : copy(
                "已建立只读链接；源文件不会被 Windows 修改。",
                "Read-only link created; Windows will not modify the source file.",
              ),
        );
      }
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setBusy("");
    }
  }

  async function refresh() {
    ++loadSequence.current;
    setBusy("refresh");
    setLoading(false);
    setError("");
    setNotice("");
    try {
      const next = await healthApi.refresh(query);
      setSnapshot(next);
      if (next) setNotice(copy("健康统计已刷新。", "Health statistics refreshed."));
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setBusy("");
    }
  }

  if (loading && !snapshot) {
    return (
      <PageFrame title={copy("健康", "Health")}>
        <div className="panel">
          <LoadingState label={copy("正在读取健康统计", "Loading health statistics")} />
        </div>
      </PageFrame>
    );
  }

  if (!snapshot && error) {
    return (
      <PageFrame
        className="usage-page"
        eyebrow={copy("只读显示 · 不采集 Windows", "Read only · No Windows tracking")}
        title={copy("健康", "Health")}
        description={copy(
          "只显示用户明确选择的 Android 健康统计文件。",
          "Displays only an Android health statistics file explicitly selected by you.",
        )}
      >
        <div className="panel">
          <ErrorState
            title={copy("无法读取健康统计", "Health statistics unavailable")}
            description={error}
            retry={() => void load()}
          />
        </div>
      </PageFrame>
    );
  }

  return (
    <PageFrame
      className="usage-page"
      eyebrow={copy("只读显示 · 不采集 Windows", "Read only · No Windows tracking")}
      title={copy("健康", "Health")}
      description={copy(
        "显示 Android Health Connect 已有的步数、距离和活动热量；Windows 不采集健康数据。",
        "Displays existing Android Health Connect steps, distance and active calories. Windows collects no health data.",
      )}
      actions={
        <>
          <button
            className="button-secondary"
            type="button"
            disabled={!!busy || loading}
            onClick={() => void chooseSource("snapshot")}
          >
            <FileDown aria-hidden="true" size={17} />
            {busy === "snapshot" ? copy("选择中…", "Choosing…") : copy("导入快照", "Import snapshot")}
          </button>
          <button
            className="button-secondary"
            type="button"
            disabled={!!busy || loading}
            onClick={() => void chooseSource("linkedFile")}
          >
            <FileSymlink aria-hidden="true" size={17} />
            {busy === "linkedFile" ? copy("选择中…", "Choosing…") : copy("链接只读文件", "Link read-only file")}
          </button>
          <button
            className="button-primary"
            type="button"
            disabled={!!busy || loading || !snapshot?.source.canRefresh}
            onClick={() => void refresh()}
          >
            <RefreshCw className={busy === "refresh" ? "spin" : ""} aria-hidden="true" size={17} />
            {busy === "refresh" ? copy("刷新中…", "Refreshing…") : copy("刷新", "Refresh")}
          </button>
        </>
      }
    >
      {error ? <div className="inline-error" role="alert">{error}</div> : null}
      {notice ? <div className="status-banner success" role="status">{notice}</div> : null}

      <aside className="panel usage-readonly-note">
        <HeartPulse aria-hidden="true" size={22} />
        <div>
          <h2>{copy("数据边界", "Data boundary")}</h2>
          <p>
            {copy(
  "Android v29 按隐私设计不包含健康历史。这里仅读取你明确选择的 schema v1–v3 文件，不申请 Windows 权限，也不会把缺失值伪造成 0。",
  "Android v29 intentionally excludes health history. This view only reads a schema v1–v3 file you explicitly choose, requests no Windows permission, and never turns missing values into zero.",
            )}
          </p>
        </div>
      </aside>

      {!snapshot ? (
        <div className="panel">
          <EmptyState
            title={copy("还没有健康统计数据", "No health statistics yet")}
            description={copy(
              "选择一个 Android step-statistics.json 文件导入快照或建立只读链接。",
              "Choose an Android step-statistics.json file to import or link read-only.",
            )}
            icon={HeartPulse}
            action={
              <button
                className="button-primary"
                type="button"
                disabled={!!busy || loading}
                onClick={() => void chooseSource("snapshot")}
              >
                <FileDown aria-hidden="true" size={16} />
                {copy("选择健康文件", "Choose health file")}
              </button>
            }
          />
        </div>
      ) : (
        <>
          <section className={`panel usage-source-panel is-${snapshot.source.state}`}>
            <div>
              <span className="usage-source-icon" aria-hidden="true">
                {snapshot.source.mode === "snapshot" ? <FileDown /> : <FileSymlink />}
              </span>
              <div>
                <h2>{snapshot.source.mode === "snapshot" ? copy("导入快照", "Imported snapshot") : copy("只读链接", "Read-only link")}</h2>
                <p>{snapshot.source.displayName}</p>
              </div>
            </div>
            <dl>
              <div>
                <dt>{copy("状态", "Status")}</dt>
                <dd>{
                  {
                    ready: copy("可用", "Ready"),
                    stale: copy("可能已过期", "Possibly stale"),
                    missing: copy("源文件已丢失", "Source missing"),
                    invalid: copy("源文件无效", "Invalid source"),
                  }[snapshot.source.state]
                }</dd>
              </div>
              <div>
                <dt>{copy("最近成功读取", "Last successful read")}</dt>
                <dd>{formatReadTime(snapshot.source.lastSuccessfulReadAtMs, language)}</dd>
              </div>
              <div>
                <dt>{copy("最新统计日", "Latest statistics day")}</dt>
                <dd>{snapshot.anchorDate ?? "—"}</dd>
              </div>
            </dl>
          </section>

          <section className="usage-controls panel" aria-label={copy("健康筛选", "Health filters")}>
            <div className="segmented" role="group" aria-label={copy("指标", "Metric")}>
              {(["STEPS", "DISTANCE", "ACTIVE_CALORIES"] as HealthMetric[]).map((value) => {
                const Icon = value === "STEPS" ? Footprints : value === "DISTANCE" ? Ruler : Flame;
                return (
                  <button
                    key={value}
                    type="button"
                    className={metric === value ? "selected" : undefined}
                    aria-pressed={metric === value}
                    disabled={loading || !!busy}
                    onClick={() => setMetric(value)}
                  >
                    <Icon aria-hidden="true" size={16} />
                    {metricLabel(value, language)}
                  </button>
                );
              })}
            </div>
            <div className="segmented" role="group" aria-label={copy("时间范围", "Time range")}>
              {(["LAST_7_DAYS", "LAST_30_DAYS", "LAST_90_DAYS", "ALL"] as UsageRange[]).map((value) => (
                <button
                  key={value}
                  type="button"
                  className={range === value ? "selected" : undefined}
                  aria-pressed={range === value}
                  disabled={loading || !!busy}
                  onClick={() => setRange(value)}
                >
                  {rangeLabel(value, language)}
                </button>
              ))}
            </div>
          </section>

          <dl className="usage-summary-grid">
            <div className="card">
              <dt>{copy("总量", "Total")}</dt>
              <dd>{formatHealthValue(snapshot.overview.total, metric, language)}</dd>
            </div>
            <div className="card">
              <dt>{copy("有数据日均", "Average per data day")}</dt>
              <dd>{formatHealthValue(snapshot.overview.averagePerDataDay, metric, language)}</dd>
            </div>
            <div className="card">
              <dt>{copy("单日最高", "Highest day")}</dt>
              <dd>{formatHealthValue(snapshot.overview.highestDay, metric, language)}</dd>
            </div>
            <div className="card">
              <dt>{copy("可信数据天数", "Days with data")}</dt>
              <dd>{snapshot.overview.daysWithData} / {snapshot.overview.recordedDays}</dd>
            </div>
          </dl>

          <section className="panel usage-chart-panel" aria-labelledby="health-chart-title">
            <div className="panel-heading">
              <div>
                <h2 id="health-chart-title">{copy("每日趋势", "Daily trend")}</h2>
                <p>{copy("空缺保持未知；0 只在源文件明确记录 0 时显示。", "Gaps stay unknown; zero appears only when the source explicitly records zero.")}</p>
              </div>
              <Activity aria-hidden="true" size={22} />
            </div>
            {snapshot.points.length ? (
              <HealthBars points={snapshot.points} metric={metric} language={language} />
            ) : (
              <EmptyState compact title={copy("此范围没有数据", "No data in this range")} icon={Activity} />
            )}
          </section>
        </>
      )}
    </PageFrame>
  );
}
