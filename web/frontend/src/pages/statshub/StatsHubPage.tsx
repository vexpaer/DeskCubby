/**
 * 统计中心 (/statistics) — README_for_ai「统计中心」章节。
 *
 * Aggregates every module that already has a web statistic into one adaptive
 * card grid (GET /api/statshub/overview), plus the 结构化记录统计 section from
 * GET /api/structured/statistics. Every section degrades to "—" or zeros when
 * its data is missing; this page never starts any new collection.
 */
import React, { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { BookOpen, Bot, Gamepad2, HeartPulse, Smartphone } from "lucide-react";
import { apiGet } from "../../api/client";
import { tr } from "../../i18n/tr";
import { ErrorText, PageTutorialOverlay, Spinner, TopBar } from "../../components/ui";

// ---------------------------------------------------------------------------
// Defensive parsing helpers (the overview must tolerate missing sections)
// ---------------------------------------------------------------------------

function asRecord(v: unknown): Record<string, unknown> | null {
  return v !== null && typeof v === "object" && !Array.isArray(v)
    ? (v as Record<string, unknown>)
    : null;
}

function num(v: unknown, fallback = 0): number {
  return typeof v === "number" && Number.isFinite(v) ? v : fallback;
}

/** Nullable variant: returns null instead of fabricating a 0 for unknown data. */
function optNum(v: unknown): number | null {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

function str(v: unknown): string | null {
  return typeof v === "string" && v.length > 0 ? v : null;
}

function asArray(v: unknown): unknown[] {
  return Array.isArray(v) ? v : [];
}

/** Compact H/M duration mirroring the Android statistics pages (e.g. 1H 23M). */
export function formatCompactMinutes(minutes: number): string {
  const total = Math.max(0, Math.round(minutes));
  const h = Math.floor(total / 60);
  const m = total % 60;
  if (h > 0) return `${h}H ${m}M`;
  return `${m}M`;
}

function fmtInt(n: number): string {
  return Math.round(n).toLocaleString("en-US");
}

const GAME_LABELS: Record<string, { zh: string; en: string }> = {
  "2048": { zh: "2048", en: "2048" },
  "2048_5": { zh: "2048 五阶", en: "2048 5x5" },
  "2048_6": { zh: "2048 六阶", en: "2048 6x6" },
  snake: { zh: "贪吃蛇", en: "Snake" },
  tetris: { zh: "俄罗斯方块", en: "Tetris" },
  minesweeper: { zh: "扫雷", en: "Minesweeper" },
  spider: { zh: "蜘蛛纸牌", en: "Spider" },
  go: { zh: "围棋", en: "Go" },
};

function gameLabel(id: string): string {
  const l = GAME_LABELS[id];
  return l ? tr(l.zh, l.en) : id;
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

interface StructuredFieldStats {
  fieldId: string;
  name: string;
  type: string;
  unit: string | null;
  count: number;
  latest: string | null;
  average: string | null;
  total: string | null;
}

interface StructuredMetricStats {
  id: string;
  name: string;
  latestDisplay: string | null;
}

export default function StatsHubPage() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [raw, setRaw] = useState<Record<string, unknown> | null>(null);

  const [structLoading, setStructLoading] = useState(true);
  const [structError, setStructError] = useState<unknown>(null);
  const [fields, setFields] = useState<StructuredFieldStats[]>([]);
  const [metrics, setMetrics] = useState<StructuredMetricStats[]>([]);

  useEffect(() => {
    let alive = true;
    apiGet<unknown>("/api/statshub/overview")
      .then((data) => {
        if (!alive) return;
        setRaw(asRecord(data));
        setLoading(false);
      })
      .catch((e) => {
        if (!alive) return;
        setError(e);
        setLoading(false);
      });
    apiGet<unknown>("/api/structured/statistics")
      .then((data) => {
        if (!alive) return;
        const root = asRecord(data);
        const parsedFields: StructuredFieldStats[] = [];
        for (const item of asArray(root?.["fields"])) {
          const f = asRecord(item);
          if (!f) continue;
          parsedFields.push({
            fieldId: str(f["fieldId"]) ?? "",
            name: str(f["name"]) ?? tr("未命名字段", "Unnamed field"),
            type: str(f["type"]) ?? "",
            unit: str(f["unit"]),
            count: num(f["count"]),
            latest: str(f["latest"]),
            average: str(f["average"]),
            total: str(f["total"]),
          });
        }
        const parsedMetrics: StructuredMetricStats[] = [];
        for (const item of asArray(root?.["metrics"])) {
          const m = asRecord(item);
          if (!m) continue;
          const series = asArray(m["series"]);
          let latestDisplay: string | null = null;
          for (let i = series.length - 1; i >= 0; i--) {
            const point = asRecord(series[i]);
            const display = point ? str(point["display"]) : null;
            if (display) {
              latestDisplay = display;
              break;
            }
          }
          parsedMetrics.push({
            id: str(m["id"]) ?? "",
            name: str(m["name"]) ?? tr("未命名指标", "Unnamed metric"),
            latestDisplay,
          });
        }
        if (!alive) return;
        setFields(parsedFields);
        setMetrics(parsedMetrics);
        setStructLoading(false);
      })
      .catch((e) => {
        if (!alive) return;
        setStructError(e);
        setStructLoading(false);
      });
    return () => {
      alive = false;
    };
  }, []);

  // ---- overview sections -------------------------------------------------
  const diary = useMemo(() => asRecord(raw?.["diary"]), [raw]);
  const usage = useMemo(() => asRecord(raw?.["usage"]), [raw]);
  const health = useMemo(() => asRecord(raw?.["health"]), [raw]);
  const reading = useMemo(() => asRecord(raw?.["reading"]), [raw]);
  const agent = useMemo(() => asRecord(raw?.["agent"]), [raw]);

  const games = useMemo(() => {
    return asArray(raw?.["games"])
      .map((g) => asRecord(g))
      .filter((g): g is Record<string, unknown> => g !== null)
      .map((g) => ({ gameId: str(g["gameId"]) ?? "", highScore: num(g["highScore"]) }))
      .filter((g) => g.gameId !== "");
  }, [raw]);

  const diaryEntries = num(diary?.["entryCount"]);
  const diaryWords = num(diary?.["totalWords"]);
  const diaryStreak = num(diary?.["currentStreakDays"]);

  const usageMinutes = num(usage?.["lastSevenTotalMinutes"]);
  const usageDays = num(usage?.["recordedDays"]);

  const healthSteps = num(health?.["lastSevenSteps"]);
  const healthDays = num(health?.["recordedDays"]);

  const readingAvailable = reading?.["available"] === true;
  const readingMinutes = num(reading?.["totalMinutes"]);
  const readingBooks = asArray(reading?.["books"]).length;

  const gamesWithScore = games.filter((g) => g.highScore > 0);
  const bestGame = gamesWithScore.reduce<{ gameId: string; highScore: number } | null>(
    (best, g) => (best === null || g.highScore > best.highScore ? g : best),
    null,
  );

  const agentRuns = num(agent?.["runCount"]);
  const agentInput = optNum(agent?.["inputTokens"]);
  const agentOutput = optNum(agent?.["outputTokens"]);
  const agentCached = optNum(agent?.["cachedInputTokens"]);
  const agentCacheRate = optNum(agent?.["cacheRate"]);

  if (loading) {
    return (
      <div>
        <TopBar title={tr("统计", "Statistics")} />
        <Spinner />
      </div>
    );
  }

  return (
    <div className="dc-page">
      <style>{`
        .dc-stats-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px; }
      `}</style>
      <TopBar title={tr("统计", "Statistics")} subtitle={tr("汇总已有的本机数据，不会开启新的采集。", "Summarises existing local data; never starts new collection.")} />

      <ErrorText error={error} />

      <div className="dc-col" style={{ gap: 12 }}>
        <div className="dc-stats-grid">
          {/* 日记 */}
          <Link to="/diary" className="dc-card dc-col" style={{ padding: 14, gap: 8, textDecoration: "none", color: "inherit" }}>
            <div className="dc-row" style={{ gap: 8 }}>
              <BookOpen size={18} style={{ color: "var(--dc-primary)" }} />
              <span style={{ fontWeight: 600 }}>{tr("日记", "Diary")}</span>
            </div>
            <div className="dc-row dc-wrap" style={{ gap: 14 }}>
              <Stat label={tr("总篇数", "Entries")} value={fmtInt(diaryEntries)} />
              <Stat label={tr("总字数", "Total words")} value={fmtInt(diaryWords)} />
              <Stat label={tr("连续天数", "Streak days")} value={fmtInt(diaryStreak)} />
            </div>
          </Link>

          {/* 手机使用时间 */}
          <Link to="/usage_statistics" className="dc-card dc-col" style={{ padding: 14, gap: 8, textDecoration: "none", color: "inherit" }}>
            <div className="dc-row" style={{ gap: 8 }}>
              <Smartphone size={18} style={{ color: "var(--dc-primary)" }} />
              <span style={{ fontWeight: 600 }}>{tr("手机使用时间", "Screen time")}</span>
            </div>
            <div className="dc-row dc-wrap" style={{ gap: 14 }}>
              <Stat label={tr("近 7 天合计", "Last 7 days")} value={formatCompactMinutes(usageMinutes)} />
              <Stat label={tr("已记录天数", "Recorded days")} value={fmtInt(usageDays)} />
            </div>
          </Link>

          {/* 健康 */}
          <Link to="/step_statistics" className="dc-card dc-col" style={{ padding: 14, gap: 8, textDecoration: "none", color: "inherit" }}>
            <div className="dc-row" style={{ gap: 8 }}>
              <HeartPulse size={18} style={{ color: "var(--dc-primary)" }} />
              <span style={{ fontWeight: 600 }}>{tr("健康", "Health")}</span>
            </div>
            <div className="dc-row dc-wrap" style={{ gap: 14 }}>
              <Stat label={tr("近 7 天步数", "Steps, last 7 days")} value={fmtInt(healthSteps)} />
              <Stat label={tr("已记录天数", "Recorded days")} value={fmtInt(healthDays)} />
            </div>
          </Link>

          {/* 阅读时长 */}
          <Link to="/reader" className="dc-card dc-col" style={{ padding: 14, gap: 8, textDecoration: "none", color: "inherit" }}>
            <div className="dc-row" style={{ gap: 8 }}>
              <BookOpen size={18} style={{ color: "var(--dc-primary)" }} />
              <span style={{ fontWeight: 600 }}>{tr("阅读时长", "Reading time")}</span>
            </div>
            {readingAvailable ? (
              <div className="dc-row dc-wrap" style={{ gap: 14 }}>
                <Stat label={tr("累计时长", "Total time")} value={formatCompactMinutes(readingMinutes)} />
                <Stat label={tr("有记录的书籍", "Books recorded")} value={fmtInt(readingBooks)} />
              </div>
            ) : (
              <div className="dc-muted" style={{ fontSize: "0.88em" }}>{tr("暂无阅读时长数据", "No reading-time data yet")}</div>
            )}
          </Link>

          {/* 小游戏战绩 */}
          <Link to="/games" className="dc-card dc-col" style={{ padding: 14, gap: 8, textDecoration: "none", color: "inherit" }}>
            <div className="dc-row" style={{ gap: 8 }}>
              <Gamepad2 size={18} style={{ color: "var(--dc-primary)" }} />
              <span style={{ fontWeight: 600 }}>{tr("小游戏战绩", "Mini-game records")}</span>
            </div>
            {bestGame ? (
              <div className="dc-row dc-wrap" style={{ gap: 14 }}>
                <Stat label={tr("已有战绩的游戏", "Games scored")} value={fmtInt(gamesWithScore.length)} />
                <Stat
                  label={gameLabel(bestGame.gameId)}
                  value={`${fmtInt(bestGame.highScore)}${tr(" 分", " pts")}`}
                />
              </div>
            ) : (
              <div className="dc-muted" style={{ fontSize: "0.88em" }}>{tr("还没有任何最高分记录", "No high scores yet")}</div>
            )}
          </Link>

          {/* Agent 用量 */}
          <Link to="/ai_chat" className="dc-card dc-col" style={{ padding: 14, gap: 8, textDecoration: "none", color: "inherit" }}>
            <div className="dc-row" style={{ gap: 8 }}>
              <Bot size={18} style={{ color: "var(--dc-primary)" }} />
              <span style={{ fontWeight: 600 }}>{tr("Agent 用量", "Agent usage")}</span>
            </div>
            <div className="dc-row dc-wrap" style={{ gap: 14 }}>
              <Stat label={tr("运行数", "Runs")} value={fmtInt(agentRuns)} />
              <Stat label={tr("输入 Token", "Input tokens")} value={agentInput === null ? "—" : fmtInt(agentInput)} />
              <Stat label={tr("输出 Token", "Output tokens")} value={agentOutput === null ? "—" : fmtInt(agentOutput)} />
              <Stat label={tr("缓存输入", "Cached input")} value={agentCached === null ? "—" : fmtInt(agentCached)} />
              <Stat
                label={tr("缓存率", "Cache rate")}
                value={agentCacheRate === null ? "—" : `${Math.round(agentCacheRate * 100)}%`}
              />
            </div>
          </Link>
        </div>

        {/* 结构化记录统计 */}
        <section className="dc-card dc-col" style={{ padding: 14, gap: 10 }}>
          <div style={{ fontWeight: 600 }}>{tr("结构化记录统计", "Structured record statistics")}</div>
          {structLoading && <Spinner size={20} />}
          <ErrorText error={structError} />
          {!structLoading && fields.length === 0 && metrics.length === 0 && !structError && (
            <div className="dc-muted" style={{ fontSize: "0.88em" }}>
              {tr("还没有结构化字段或派生指标的统计数据。", "No structured fields or derived metrics have statistics yet.")}
            </div>
          )}
          {fields.length > 0 && (
            <div className="dc-col" style={{ gap: 8 }}>
              {fields.map((f) => (
                <div key={f.fieldId || f.name} className="dc-row dc-wrap" style={{ gap: 14, borderTop: "var(--dc-border-width) solid var(--dc-outline-variant)", paddingTop: 8 }}>
                  <div className="dc-grow" style={{ minWidth: 120 }}>
                    <div style={{ fontWeight: 600 }}>{f.name}</div>
                    <div className="dc-muted" style={{ fontSize: "0.82em" }}>{fieldTypeLabel(f.type)}{f.unit ? ` · ${f.unit}` : ""}</div>
                  </div>
                  <Stat label={tr("记录次数", "Records")} value={fmtInt(f.count)} />
                  {f.total != null && <Stat label={tr("总计", "Total")} value={f.total} />}
                  {f.average != null && <Stat label={tr("平均", "Average")} value={f.average} />}
                  <Stat label={tr("最新值", "Latest")} value={f.latest ?? "—"} />
                </div>
              ))}
            </div>
          )}
          {metrics.length > 0 && (
            <div className="dc-col" style={{ gap: 6 }}>
              <div className="dc-muted" style={{ fontSize: "0.85em" }}>{tr("派生指标", "Derived metrics")}</div>
              {metrics.map((m) => (
                <div key={m.id || m.name} className="dc-row" style={{ justifyContent: "space-between", gap: 12 }}>
                  <span>{m.name}</span>
                  <span className="dc-muted">{m.latestDisplay ?? "—"}</span>
                </div>
              ))}
            </div>
          )}
        </section>

        {/* 快捷入口（与卡片重复的显式链接，便于键盘/读屏用户） */}
        <nav className="dc-row dc-wrap" aria-label={tr("统计相关页面", "Statistics pages")} style={{ gap: 8 }}>
          {[
            { to: "/diary", zh: "日记", en: "Diary" },
            { to: "/usage_statistics", zh: "手机使用时间", en: "Screen time" },
            { to: "/step_statistics", zh: "健康", en: "Health" },
            { to: "/games", zh: "小游戏", en: "Games" },
            { to: "/ai_chat", zh: "AI 聊天", en: "AI chat" },
          ].map((l) => (
            <Link key={l.to} to={l.to} className="dc-chip" style={{ textDecoration: "none" }}>
              {tr(l.zh, l.en)}
            </Link>
          ))}
        </nav>
      </div>

      <PageTutorialOverlay
        pageKey="statistics"
        title={tr("统计", "Statistics")}
        lines={[
          tr("这里汇总日记、使用时间、健康、阅读、小游戏与 Agent 用量。", "This hub summarises diary, screen time, health, reading, games and Agent usage."),
          tr("点按卡片进入对应详情页；没有数据的模块显示占位说明。", "Tap a card for details; modules without data show placeholder text."),
        ]}
      />
    </div>
  );
}

function Stat(props: { label: React.ReactNode; value: React.ReactNode }) {
  return (
    <div className="dc-col" style={{ gap: 2 }}>
      <span className="dc-muted" style={{ fontSize: "0.8em" }}>{props.label}</span>
      <span style={{ fontWeight: 600 }}>{props.value}</span>
    </div>
  );
}

function fieldTypeLabel(t: string): string {
  switch (t) {
    case "word": return tr("文字", "Text");
    case "number": return tr("数字", "Number");
    case "type": return tr("分类", "Category");
    case "time": return tr("时间", "Time");
    case "duration": return tr("时长", "Duration");
    default: return t;
  }
}
