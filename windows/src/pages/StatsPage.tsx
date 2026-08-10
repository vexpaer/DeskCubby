import {
  Activity,
  ArrowLeft,
  BookOpen,
  FileText,
  Gamepad2,
  HeartPulse,
  Smartphone,
  Trophy,
} from "lucide-react";
import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type CSSProperties,
  type ReactNode,
} from "react";

import { ErrorState, LoadingState } from "../components/AsyncState";
import { PageFrame } from "../components/PageFrame";
import { GAME_IDS, gameApi, type GameId, type GamesSnapshotV1 } from "../lib/gameApi";
import { HEALTH_DTO_VERSION, healthApi, type HealthSnapshotV1 } from "../lib/healthApi";
import { diaryApi, readableError, tr, type DiaryEntry } from "../lib/ipc";
import { readerApi, type ReaderLibraryV1 } from "../lib/readerApi";
import {
  PHONE_USAGE_DTO_VERSION,
  usageApi,
  type PhoneUsageSnapshotV2,
} from "../lib/usageApi";
import { useAppStore } from "../store/appStore";
import { deriveDiarySummary } from "./statsModels";
import "./stats.css";

type Language = "zh-CN" | "en";
type StatsSection = "overview" | "diary" | "usage" | "health" | "reading" | "games";

interface StatsData {
  diaries: DiaryEntry[] | null;
  usage: PhoneUsageSnapshotV2 | null;
  health: HealthSnapshotV1 | null;
  reader: ReaderLibraryV1 | null;
  games: GamesSnapshotV1 | null;
}

const GAME_TITLES: Record<GameId, [string, string]> = {
  "2048": ["2048 · 4×4", "2048 · 4×4"],
  "2048_5": ["2048 · 5×5", "2048 · 5×5"],
  "2048_6": ["2048 · 6×6", "2048 · 6×6"],
  snake: ["贪吃蛇", "Snake"],
  tetris: ["俄罗斯方块", "Tetris"],
  minesweeper: ["扫雷", "Minesweeper"],
  spider: ["蜘蛛纸牌", "Spider"],
  go: ["围棋", "Go"],
};

const METRIC_LABELS: Record<string, [string, string]> = {
  moveAttempts: ["总操作次数", "Total moves"],
  effectiveMoves: ["有效移动", "Effective moves"],
  merges: ["合并次数", "Merges"],
  highestTile: ["最高方块", "Highest tile"],
  wins: ["胜利", "Wins"],
  losses: ["失败", "Losses"],
  foodEaten: ["吃到食物", "Food eaten"],
  maxLength: ["最长身体", "Max length"],
  piecesLocked: ["落定方块", "Pieces locked"],
  linesCleared: ["消除行", "Lines cleared"],
  tetrises: ["四消", "Tetrises"],
  minesCellsRevealed: ["翻开格子", "Cells revealed"],
  minesSwept: ["排除地雷", "Mines swept"],
  flagsPlaced: ["放置旗帜", "Flags placed"],
  spiderCardMoves: ["移牌", "Card moves"],
  spiderDeals: ["发牌", "Deals"],
  spiderUndos: ["撤回", "Undos"],
  goMovesPlayed: ["落子", "Moves played"],
  goStonesCaptured: ["提子", "Stones captured"],
  goPasses: ["停着", "Passes"],
  goGamesCompleted: ["完成棋局", "Games completed"],
};

function decimal(value: string | null | undefined): bigint | null {
  if (!value || !/^\d+$/.test(value)) return null;
  try {
    return BigInt(value);
  } catch {
    return null;
  }
}

function duration(value: string | null | undefined, language: Language): string {
  const millis = decimal(value);
  if (millis === null) return "—";
  const minutes = millis / 60_000n;
  const hours = minutes / 60n;
  const rest = minutes % 60n;
  return language === "en"
    ? hours > 0n ? `${hours}h ${rest}m` : `${rest}m`
    : hours > 0n ? `${hours} 小时 ${rest} 分钟` : `${rest} 分钟`;
}

function number(value: bigint | number | null, language: Language): string {
  if (value === null) return "—";
  return new Intl.NumberFormat(language === "en" ? "en-US" : "zh-CN").format(value);
}

function Metric({ label, value, unknown }: { label: string; value: string; unknown?: string }) {
  return (
    <div className="stats-metric">
      <dt>{label}</dt>
      <dd>{value}</dd>
      {value === "—" && unknown ? <small>{unknown}</small> : null}
    </div>
  );
}

function Bars({
  points,
  label,
  language,
}: {
  points: { key: string; value: bigint | null }[];
  label: string;
  language: Language;
}) {
  const maximum = points.reduce((max, point) => point.value !== null && point.value > max ? point.value : max, 0n);
  return (
    <div className="stats-bars-scroll" role="region" tabIndex={0} aria-label={label}>
      <ol className="stats-bars" style={{ "--stats-count": points.length } as CSSProperties}>
        {points.map((point) => {
          const height = point.value === null || maximum === 0n ? 0 : Number((point.value * 100n) / maximum);
          return (
            <li key={point.key} title={`${point.key}: ${point.value === null ? "—" : number(point.value, language)}`}>
              <span className="stats-bar-track">
                {point.value === null ? <i className="is-unknown" /> : <i style={{ height: `${Math.max(height, point.value > 0n ? 3 : 0)}%` }} />}
              </span>
              <small>{point.key.slice(5)}</small>
            </li>
          );
        })}
      </ol>
    </div>
  );
}

function OverviewCard({
  icon,
  title,
  value,
  detail,
  onClick,
}: {
  icon: ReactNode;
  title: string;
  value: string;
  detail: string;
  onClick: () => void;
}) {
  return (
    <button className="panel stats-overview-card" type="button" onClick={onClick}>
      <span className="stats-card-icon">{icon}</span>
      <span><small>{title}</small><strong>{value}</strong><em>{detail}</em></span>
    </button>
  );
}

function SectionHeading({ language, title, onBack }: { language: Language; title: string; onBack: () => void }) {
  return (
    <div className="stats-section-heading">
      <button className="button button-secondary" type="button" onClick={onBack}><ArrowLeft size={17} />{tr(language, "概览", "Overview")}</button>
      <h2>{title}</h2>
    </div>
  );
}

export default function StatsPage() {
  const language = useAppStore((state) => state.appearance.language);
  const [section, setSection] = useState<StatsSection>("overview");
  const [data, setData] = useState<StatsData>({ diaries: null, usage: null, health: null, reader: null, games: null });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    const results = await Promise.allSettled([
      diaryApi.list(false),
      usageApi.page({ dtoVersion: PHONE_USAGE_DTO_VERSION, range: "LAST_7_DAYS", packageName: null, deviceId: null }),
      healthApi.page({ dtoVersion: HEALTH_DTO_VERSION, range: "LAST_7_DAYS", metric: "STEPS" }),
      readerApi.library(),
      gameApi.snapshot(),
    ] as const);
    setData({
      diaries: results[0].status === "fulfilled" ? results[0].value : null,
      usage: results[1].status === "fulfilled" ? results[1].value : null,
      health: results[2].status === "fulfilled" ? results[2].value : null,
      reader: results[3].status === "fulfilled" ? results[3].value : null,
      games: results[4].status === "fulfilled" ? results[4].value : null,
    });
    const failures = results.filter((result) => result.status === "rejected");
    if (failures.length === results.length) setError(readableError(failures[0].reason, language));
    else if (failures.length) setError(tr(language, "部分本机统计暂时不可用；缺失项保持未知。", "Some local statistics are unavailable; missing values remain unknown."));
    setLoading(false);
  }, [language]);

  useEffect(() => { void load(); }, [load]);

  const diary = useMemo(() => data.diaries ? deriveDiarySummary(data.diaries) : null, [data.diaries]);
  const gameMetrics = useMemo(() => {
    const result = new Map<GameId, Map<string, string>>();
    for (const item of data.games?.statistics ?? []) {
      const metrics = result.get(item.gameId) ?? new Map<string, string>();
      metrics.set(item.metricKey, item.value);
      result.set(item.gameId, metrics);
    }
    return result;
  }, [data.games]);
  const gameTotal = data.games?.games.reduce((sum, game) => sum + (decimal(game.totalPlayMillis) ?? 0n), 0n) ?? null;

  if (loading && !Object.values(data).some(Boolean)) {
    return <PageFrame title={tr(language, "统计", "Statistics")}><div className="panel"><LoadingState /></div></PageFrame>;
  }
  if (!Object.values(data).some(Boolean)) {
    return <PageFrame title={tr(language, "统计", "Statistics")}><div className="panel"><ErrorState description={error} retry={() => void load()} /></div></PageFrame>;
  }

  const unknown = tr(language, "尚无可信数据", "No trusted data yet");
  const pageTitle: Record<StatsSection, string> = {
    overview: tr(language, "统计", "Statistics"),
    diary: tr(language, "日记统计", "Diary statistics"),
    usage: tr(language, "手机使用时间", "Phone screen time"),
    health: tr(language, "健康", "Health"),
    reading: tr(language, "阅读时长", "Reading time"),
    games: tr(language, "小游戏战绩", "Game records"),
  };

  return (
    <PageFrame title={pageTitle[section]} description={tr(language, "数据缺失时显示未知，不会把未采集内容伪装成 0。", "Missing data is shown as unknown, never fabricated as zero.")}>
      {error ? <p className="inline-notice" role="status">{error}</p> : null}
      {section === "overview" ? (
        <div className="stats-overview-grid">
          <OverviewCard icon={<FileText />} title={tr(language, "日记", "Diary")} value={diary ? number(diary.count, language) : "—"} detail={diary ? tr(language, `${number(diary.words, language)} 字`, `${number(diary.words, language)} words`) : unknown} onClick={() => setSection("diary")} />
          <OverviewCard icon={<Smartphone />} title={tr(language, "近 7 天手机使用", "Phone · last 7 days")} value={duration(data.usage?.overview.totalMillis, language)} detail={data.usage ? tr(language, `${data.usage.overview.recordedDays} 个记录日`, `${data.usage.overview.recordedDays} recorded days`) : unknown} onClick={() => setSection("usage")} />
          <OverviewCard icon={<HeartPulse />} title={tr(language, "近 7 天步数", "Steps · last 7 days")} value={number(decimal(data.health?.overview.total), language)} detail={data.health ? tr(language, `${data.health.overview.daysWithData} 个有效日`, `${data.health.overview.daysWithData} days with data`) : unknown} onClick={() => setSection("health")} />
          <OverviewCard icon={<BookOpen />} title={tr(language, "阅读", "Reading")} value={data.reader ? duration(data.reader.totalReadingMillis, language) : "—"} detail={data.reader ? tr(language, `${data.reader.books.length} 本书`, `${data.reader.books.length} books`) : unknown} onClick={() => setSection("reading")} />
          <OverviewCard icon={<Gamepad2 />} title={tr(language, "小游戏", "Mini games")} value={gameTotal === null ? "—" : duration(gameTotal.toString(), language)} detail={data.games ? tr(language, `${data.games.statistics.length} 项特色指标`, `${data.games.statistics.length} lifetime metrics`) : unknown} onClick={() => setSection("games")} />
        </div>
      ) : null}

      {section === "diary" ? (
        <section className="stats-detail">
          <SectionHeading language={language} title={pageTitle.diary} onBack={() => setSection("overview")} />
          <dl className="stats-metric-grid">
            <Metric label={tr(language, "日记篇数", "Entries")} value={diary ? number(diary.count, language) : "—"} unknown={unknown} />
            <Metric label={tr(language, "总字数", "Total words")} value={diary ? number(diary.words, language) : "—"} unknown={unknown} />
            <Metric label={tr(language, "当前连续", "Current streak")} value={diary ? tr(language, `${diary.currentStreak} 天`, `${diary.currentStreak} days`) : "—"} unknown={unknown} />
            <Metric label={tr(language, "最长连续", "Longest streak")} value={diary ? tr(language, `${diary.longestStreak} 天`, `${diary.longestStreak} days`) : "—"} unknown={unknown} />
          </dl>
          {diary ? <div className="panel"><h3>{tr(language, "近 12 个自然月字数", "Words across 12 calendar months")}</h3><Bars points={diary.months} label={tr(language, "月度日记字数图", "Monthly diary word chart")} language={language} /></div> : null}
        </section>
      ) : null}

      {section === "usage" ? (
        <section className="stats-detail">
          <SectionHeading language={language} title={pageTitle.usage} onBack={() => setSection("overview")} />
          <dl className="stats-metric-grid">
            <Metric label={tr(language, "近 7 天总计", "Last 7 days")} value={duration(data.usage?.overview.totalMillis, language)} unknown={unknown} />
            <Metric label={tr(language, "日均", "Daily average")} value={duration(data.usage?.overview.averageMillis, language)} unknown={unknown} />
            <Metric label={tr(language, "单日最高", "Highest day")} value={duration(data.usage?.overview.highestDayMillis, language)} unknown={unknown} />
            <Metric label={tr(language, "已记录日", "Recorded days")} value={data.usage ? number(data.usage.overview.recordedDays, language) : "—"} unknown={unknown} />
          </dl>
          {data.usage ? <div className="panel"><Bars points={data.usage.points.map((point) => ({ key: point.date, value: decimal(point.valueMillis) }))} label={tr(language, "每日手机使用时间", "Daily phone screen time")} language={language} /></div> : null}
        </section>
      ) : null}

      {section === "health" ? (
        <section className="stats-detail">
          <SectionHeading language={language} title={pageTitle.health} onBack={() => setSection("overview")} />
          <dl className="stats-metric-grid">
            <Metric label={tr(language, "近 7 天步数", "Steps · last 7 days")} value={number(decimal(data.health?.overview.total), language)} unknown={unknown} />
            <Metric label={tr(language, "有效日均", "Average per data day")} value={number(decimal(data.health?.overview.averagePerDataDay), language)} unknown={unknown} />
            <Metric label={tr(language, "单日最高", "Highest day")} value={number(decimal(data.health?.overview.highestDay), language)} unknown={unknown} />
            <Metric label={tr(language, "有效日", "Days with data")} value={data.health ? number(data.health.overview.daysWithData, language) : "—"} unknown={unknown} />
          </dl>
          {data.health ? <div className="panel"><Bars points={data.health.points.map((point) => ({ key: point.date, value: decimal(point.value) }))} label={tr(language, "每日步数", "Daily steps")} language={language} /></div> : null}
        </section>
      ) : null}

      {section === "reading" ? (
        <section className="stats-detail">
          <SectionHeading language={language} title={pageTitle.reading} onBack={() => setSection("overview")} />
          <dl className="stats-metric-grid"><Metric label={tr(language, "累计阅读", "Total reading")} value={data.reader ? duration(data.reader.totalReadingMillis, language) : "—"} unknown={unknown} /></dl>
          {data.reader ? (
            <div className="panel stats-ranking"><h3>{tr(language, "按书排行", "By book")}</h3>
              {data.reader.books.length ? data.reader.books.slice().sort((a, b) => Number((decimal(b.readingMillis) ?? 0n) - (decimal(a.readingMillis) ?? 0n))).map((book) => <div key={book.id}><span>{book.title}</span><strong>{duration(book.readingMillis, language)}</strong></div>) : <p>{tr(language, "还没有阅读记录", "No reading time yet")}</p>}
            </div>
          ) : null}
        </section>
      ) : null}

      {section === "games" ? (
        <section className="stats-detail">
          <SectionHeading language={language} title={pageTitle.games} onBack={() => setSection("overview")} />
          <div className="stats-game-grid">
            {GAME_IDS.map((gameId) => {
              const state = data.games?.games.find((game) => game.gameId === gameId);
              const metrics = gameMetrics.get(gameId);
              const hasData = Boolean(state?.updatedAt) || Boolean(metrics?.size) || (decimal(state?.totalPlayMillis) ?? 0n) > 0n;
              const is2048 = gameId === "2048" || gameId === "2048_5" || gameId === "2048_6";
              const isGo = gameId === "go";
              const wins = decimal(metrics?.get("wins"));
              const losses = decimal(metrics?.get("losses"));
              const finished = (wins ?? 0n) + (losses ?? 0n);
              return (
                <article className="panel stats-game-card" key={gameId}>
                  <header><Trophy size={19} /><h3>{language === "en" ? GAME_TITLES[gameId][1] : GAME_TITLES[gameId][0]}</h3></header>
                  {hasData ? (
                    <dl>
                      <Metric label={isGo ? tr(language, "最高提子", "Best captures") : tr(language, "最高分", "High score")} value={number(BigInt(state?.highScore ?? 0), language)} />
                      <Metric label={tr(language, "游玩时长", "Play time")} value={duration(state?.totalPlayMillis, language)} />
                      {metrics ? [...metrics].filter(([key]) => !(is2048 && key === "losses")).map(([key, value]) => <Metric key={key} label={language === "en" ? METRIC_LABELS[key]?.[1] ?? key : METRIC_LABELS[key]?.[0] ?? key} value={number(decimal(value), language)} />) : null}
                      {!is2048 && !isGo && finished > 0n && wins !== null ? <Metric label={tr(language, "胜率", "Win rate")} value={`${Number((wins * 10_000n) / finished) / 100}%`} /> : null}
                      {isGo ? <Metric label={tr(language, "存储范围", "Storage scope")} value={tr(language, "仅限本机（不进入 v28）", "This PC only (excluded from v28)")} /> : null}
                    </dl>
                  ) : <p>{unknown}</p>}
                </article>
              );
            })}
          </div>
        </section>
      ) : null}
      <footer className="stats-source-note"><Activity size={16} />{tr(language, "手机与健康只显示 Android 提供的数据；Windows 不采集系统活动。", "Phone and health views only show Android data; Windows does not collect system activity.")}</footer>
    </PageFrame>
  );
}
