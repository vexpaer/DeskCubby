import {
  AppWindow,
  BarChart3,
  FileDown,
  FileSymlink,
  Link2,
  RefreshCw,
  Smartphone,
  Timer,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";

import { EmptyState, ErrorState, LoadingState, PageFrame } from "../components";
import { dateFromI64Milliseconds, readableError, tr } from "../lib/ipc";
import {
  PHONE_USAGE_DTO_VERSION,
  usageApi,
  type DecimalI64,
  type PhoneUsageSnapshotV1,
  type UsagePointV1,
  type UsageRange,
  type UsageSourceMode,
} from "../lib/usageApi";
import { useAppStore } from "../store/appStore";

function milliseconds(value: DecimalI64): bigint {
  return /^\d+$/.test(value) ? BigInt(value) : 0n;
}

function formatDuration(value: DecimalI64, language: "zh-CN" | "en"): string {
  const totalMinutes = milliseconds(value) / 60_000n;
  const hours = totalMinutes / 60n;
  const minutes = totalMinutes % 60n;
  if (language === "en") {
    if (hours > 0n) return `${hours}h ${minutes}m`;
    return `${minutes}m`;
  }
  if (hours > 0n) return `${hours} 小时 ${minutes} 分钟`;
  return `${minutes} 分钟`;
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

function UsageBars({
  points,
  language,
}: {
  points: UsagePointV1[];
  language: "zh-CN" | "en";
}) {
  const max = points.reduce(
    (current, point) => {
      const next = milliseconds(point.valueMillis);
      return next > current ? next : current;
    },
    0n,
  );
  const locale = language === "en" ? "en-US" : "zh-CN";

  return (
    <div
      className="usage-chart-scroll"
      role="region"
      aria-label={tr(language, "每日使用时长图表", "Daily usage chart")}
      tabIndex={0}
    >
      <ol className="usage-bar-chart">
        {points.map((point) => {
          const value = milliseconds(point.valueMillis);
          const height = max === 0n ? 0 : Number((value * 100n) / max);
          const label = new Intl.DateTimeFormat(locale, {
            month: "short",
            day: "numeric",
          }).format(new Date(`${point.date}T00:00:00`));
          const duration = formatDuration(point.valueMillis, language);
          const accessible = tr(
            language,
            `${point.date}，${duration}${point.state === "OPEN" ? "，当天数据仍可刷新" : ""}`,
            `${point.date}, ${duration}${point.state === "OPEN" ? ", current day can still change" : ""}`,
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
                  <span
                    className={point.state === "OPEN" ? "usage-bar is-open" : "usage-bar"}
                    style={{ height: `${Math.max(height, value > 0n ? 3 : 0)}%` }}
                  />
                </span>
                <small>{label}</small>
              </button>
            </li>
          );
        })}
      </ol>
    </div>
  );
}

export default function UsagePage() {
  const language = useAppStore((state) => state.appearance.language);
  const copy = useCallback(
    (zh: string, en: string) => tr(language, zh, en),
    [language],
  );
  const [range, setRange] = useState<UsageRange>("LAST_7_DAYS");
  const [packageName, setPackageName] = useState<string | null>(null);
  const [snapshot, setSnapshot] = useState<PhoneUsageSnapshotV1 | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const loadSequence = useRef(0);

  const load = useCallback(async () => {
    const sequence = ++loadSequence.current;
    setLoading(true);
    setError("");
    try {
      const next = await usageApi.page({
        dtoVersion: PHONE_USAGE_DTO_VERSION,
        range,
        packageName,
      });
      if (sequence === loadSequence.current) setSnapshot(next);
    } catch (reason) {
      if (sequence === loadSequence.current) {
        setError(readableError(reason, language));
      }
    } finally {
      if (sequence === loadSequence.current) setLoading(false);
    }
  }, [language, packageName, range]);

  useEffect(() => {
    void load();
  }, [load]);

  async function chooseSource(mode: UsageSourceMode) {
    ++loadSequence.current;
    setBusy(mode);
    setLoading(false);
    setError("");
    setNotice("");
    try {
      const chosen = await usageApi.chooseSource(mode);
      if (chosen) {
        setSnapshot(chosen);
        setNotice(
          mode === "snapshot"
            ? copy("手机统计快照已导入。", "Phone statistics snapshot imported.")
            : copy("已建立只读链接；源文件不会被 Windows 修改。", "Read-only link created; Windows will not modify the source file."),
        );
        if (packageName === null) {
          try {
            const filtered = await usageApi.page({
              dtoVersion: PHONE_USAGE_DTO_VERSION,
              range,
              packageName: null,
            });
            if (filtered) setSnapshot(filtered);
          } catch (reason) {
            setError(readableError(reason, language));
          }
        }
        setPackageName(null);
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
      const next = await usageApi.refresh({
        dtoVersion: PHONE_USAGE_DTO_VERSION,
        range,
        packageName,
      });
      setSnapshot(next);
      if (next) setNotice(copy("手机统计已刷新。", "Phone statistics refreshed."));
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setBusy("");
    }
  }

  const sortedApps = useMemo(
    () =>
      [...(snapshot?.appChoices ?? [])].sort((left, right) => {
        const a = milliseconds(left.rangeMillis);
        const b = milliseconds(right.rangeMillis);
        return a === b ? left.packageName.localeCompare(right.packageName) : a > b ? -1 : 1;
      }),
    [snapshot],
  );

  if (loading && !snapshot) {
    return (
      <PageFrame title={copy("手机使用时间", "Phone screen time")}>
        <div className="panel">
          <LoadingState label={copy("正在读取手机统计", "Loading phone statistics")} />
        </div>
      </PageFrame>
    );
  }

  if (!snapshot && error) {
    return (
      <PageFrame
        className="usage-page"
        eyebrow={copy("只读显示 · 不采集 Windows", "Read only · No Windows tracking")}
        title={copy("手机使用时间", "Phone screen time")}
        description={copy(
          "只显示 Android 导入或只读链接的数据。",
          "Displays Android data from an import or read-only link.",
        )}
      >
        <div className="panel">
          <ErrorState
            title={copy("无法读取手机统计", "Phone statistics unavailable")}
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
      title={copy("手机使用时间", "Phone screen time")}
      description={copy(
        "只显示 Android 导入或只读链接的数据；DeskCubby 不会统计这台电脑的应用使用时间。",
        "Displays Android data from an import or read-only link. DeskCubby does not track app usage on this PC.",
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
      {loading && snapshot ? (
        <div className="status-banner" role="status" aria-live="polite">
          <RefreshCw className="spin" aria-hidden="true" size={17} />
          {copy("正在更新筛选结果…", "Updating filtered results…")}
        </div>
      ) : null}

      <aside className="panel usage-readonly-note">
        <Smartphone aria-hidden="true" size={22} />
        <div>
          <h2>{copy("数据边界", "Data boundary")}</h2>
          <p>
            {copy(
              "Windows 只读取 Android v4 统计数据，不申请系统使用权限、不采集电脑应用，也不改写源文件。",
              "Windows only reads Android v4 statistics. It requests no usage permission, tracks no PC apps and never rewrites the source.",
            )}
          </p>
        </div>
        <Link className="text-link" to="/settings/data/sync">
          <Link2 aria-hidden="true" size={15} />
          {copy("云同步设置", "Cloud sync settings")}
        </Link>
      </aside>

      {!snapshot ? (
        <div className="panel">
          <EmptyState
            title={copy("还没有手机统计数据", "No phone statistics yet")}
            description={copy(
              "从 Android 端导出的统计快照导入一次，或链接一个会持续更新的只读统计文件。",
              "Import an Android statistics snapshot, or link a read-only statistics file that can be refreshed.",
            )}
            icon={Timer}
            action={
              <div className="row form-row-wrap">
                <button className="button-primary" type="button" disabled={!!busy || loading} onClick={() => void chooseSource("snapshot")}>
                  <FileDown aria-hidden="true" size={16} />
                  {copy("导入快照", "Import snapshot")}
                </button>
                <button className="button-secondary" type="button" disabled={!!busy || loading} onClick={() => void chooseSource("linkedFile")}>
                  <FileSymlink aria-hidden="true" size={16} />
                  {copy("链接只读文件", "Link read-only file")}
                </button>
              </div>
            }
          />
        </div>
      ) : (
        <>
          <section
            className={`panel usage-source-panel is-${snapshot.source.state}`}
            aria-labelledby="usage-source-title"
          >
            <div>
              <span className="usage-source-icon" aria-hidden="true">
                {snapshot.source.mode === "snapshot" ? <FileDown /> : <FileSymlink />}
              </span>
              <div>
                <h2 id="usage-source-title">
                  {snapshot.source.mode === "snapshot"
                    ? copy("导入快照", "Imported snapshot")
                    : copy("只读链接", "Read-only link")}
                </h2>
                <p>{snapshot.source.displayName}</p>
              </div>
            </div>
            <dl>
              <div>
                <dt>{copy("状态", "Status")}</dt>
                <dd>
                  {
                    {
                      ready: copy("可用", "Ready"),
                      stale: copy("可能已过期", "Possibly stale"),
                      missing: copy("源文件已丢失", "Source missing"),
                      invalid: copy("源文件无效", "Invalid source"),
                    }[snapshot.source.state]
                  }
                </dd>
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

          <section
            className="usage-controls panel"
            aria-busy={loading}
            aria-label={copy("统计筛选", "Statistics filters")}
          >
            <div className="segmented" role="group" aria-label={copy("时间范围", "Time range")}>
              {(["LAST_7_DAYS", "LAST_30_DAYS", "LAST_90_DAYS", "ALL"] as UsageRange[]).map(
                (value) => (
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
                ),
              )}
            </div>
            <label className="usage-app-filter">
              <span>{copy("应用", "App")}</span>
              <select
                value={packageName ?? ""}
                disabled={loading || !!busy}
                onChange={(event) => setPackageName(event.target.value || null)}
              >
                <option value="">{copy("全部应用", "All apps")}</option>
                {sortedApps.map((app) => (
                  <option key={app.packageName} value={app.packageName}>
                    {app.packageName}
                  </option>
                ))}
              </select>
            </label>
          </section>

          <dl className="usage-summary-grid">
            <div className="card">
              <dt>{copy("总时长", "Total")}</dt>
              <dd>{formatDuration(snapshot.overview.totalMillis, language)}</dd>
            </div>
            <div className="card">
              <dt>{copy("日均", "Daily average")}</dt>
              <dd>{formatDuration(snapshot.overview.averageMillis, language)}</dd>
            </div>
            <div className="card">
              <dt>{copy("单日最高", "Highest day")}</dt>
              <dd>{formatDuration(snapshot.overview.highestDayMillis, language)}</dd>
            </div>
            <div className="card">
              <dt>{copy("近 7 日平均", "7-day average")}</dt>
              <dd>{formatDuration(snapshot.overview.lastSevenAverageMillis, language)}</dd>
            </div>
          </dl>

          <section className="panel usage-chart-panel" aria-labelledby="usage-chart-title">
            <div className="panel-heading">
              <div>
                <h2 id="usage-chart-title">{copy("每日趋势", "Daily trend")}</h2>
                <p>
                  {copy(
                    `${snapshot.overview.recordedDays} 个有记录的自然日；虚线柱表示当天仍可更新。`,
                    `${snapshot.overview.recordedDays} recorded calendar days; striped bars are still open for updates.`,
                  )}
                </p>
              </div>
              <BarChart3 aria-hidden="true" size={22} />
            </div>
            {snapshot.points.length ? (
              <UsageBars points={snapshot.points} language={language} />
            ) : (
              <EmptyState
                compact
                title={copy("此范围没有数据", "No data in this range")}
                icon={BarChart3}
              />
            )}
          </section>

          <section className="panel usage-apps-panel" aria-labelledby="usage-apps-title">
            <div className="panel-heading">
              <div>
                <h2 id="usage-apps-title">{copy("应用排行", "App ranking")}</h2>
                <p>
                  {copy(
                    "Android v4 文件不包含可靠应用名称或图标，因此只显示包名。",
                    "Android v4 data has no reliable app names or icons, so only package names are shown.",
                  )}
                </p>
              </div>
              <AppWindow aria-hidden="true" size={22} />
            </div>
            {sortedApps.length ? (
              <div className="data-table-wrap">
                <table className="data-table usage-app-table">
                  <thead>
                    <tr>
                      <th scope="col">{copy("包名", "Package")}</th>
                      <th scope="col">{copy("范围内时长", "Duration in range")}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {sortedApps.map((app) => (
                      <tr key={app.packageName}>
                        <th scope="row">
                          <span className="usage-package-name">
                            <AppWindow aria-hidden="true" size={16} />
                            {app.packageName}
                          </span>
                        </th>
                        <td>{formatDuration(app.rangeMillis, language)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <EmptyState compact title={copy("没有应用数据", "No app data")} icon={AppWindow} />
            )}
          </section>
        </>
      )}
    </PageFrame>
  );
}
