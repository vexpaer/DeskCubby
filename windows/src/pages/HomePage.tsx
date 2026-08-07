import {
  BookOpen,
  CalendarDays,
  ChartNoAxesCombined,
  ChevronRight,
  Dices,
  Feather,
  FileText,
  Gamepad2,
  ImagePlus,
  Lightbulb,
  LoaderCircle,
  MessageSquarePlus,
  RefreshCw,
  Sparkles,
  Target,
  Utensils,
} from "lucide-react";
import {
  useCallback,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { Link } from "react-router-dom";

import {
  dailyRecordApi,
  dateRecordApi,
  diaryApi,
  getActiveDiary,
  homeApi,
  poetryApi,
  readableError,
  thoughtApi,
  tr,
  type DailyRecordTarget,
  type DateRecord,
  type DiaryEntry,
  type HomeSnapshot,
  type Language,
  type MealPhoto,
} from "../lib/ipc";
import { useAppStore } from "../store/appStore";
import {
  dayDistance,
  nearestDateRecords,
  normalizeHomeConfiguration,
  parseLocalDate,
  writingStreak,
  type HomeConfiguration,
  type HomeWidgetId,
} from "./homeModels";

const GAME_OPTIONS = [
  ["2048", "2048 · 4×4", "2048 · 4×4"],
  ["2048_5", "2048 · 5×5", "2048 · 5×5"],
  ["2048_6", "2048 · 6×6", "2048 · 6×6"],
  ["snake", "贪吃蛇", "Snake"],
  ["tetris", "俄罗斯方块", "Tetris"],
  ["minesweeper", "扫雷", "Minesweeper"],
  ["spider", "蜘蛛纸牌", "Spider Solitaire"],
] as const;

const MEAL_OPTIONS = [
  ["breakfast", "早餐", "Breakfast", "🥪"],
  ["lunch", "午餐", "Lunch", "🍱"],
  ["afternoon_tea", "下午茶", "Afternoon tea", "🍹"],
  ["dinner", "晚餐", "Dinner", "🍜"],
  ["fruit", "水果", "Fruit", "🍊"],
  ["late_night", "夜宵", "Late snack", "🍤"],
] as const;

interface HomeDetails {
  diaries: DiaryEntry[] | null;
  thoughtCount: number | null;
  dateRecords: DateRecord[] | null;
  poemCount: number | null;
}

function formatDate(value: string, language: Language) {
  const parsed = parseLocalDate(value);
  return parsed
    ? new Intl.DateTimeFormat(language === "en" ? "en-US" : "zh-CN", {
        weekday: "long",
        year: "numeric",
        month: "long",
        day: "numeric",
      }).format(parsed)
    : value;
}

function AssetImage({
  photo,
  language,
}: {
  photo: MealPhoto;
  language: Language;
}) {
  const [failed, setFailed] = useState(false);
  if (!photo.assetUrl || photo.missing || failed) {
    return (
      <div className="meal-strip-image media-placeholder" role="img">
        <Utensils aria-hidden="true" />
        <span>{tr(language, "图片不可用", "Image unavailable")}</span>
      </div>
    );
  }
  return (
    <img
      className="meal-strip-image"
      src={photo.assetUrl}
      alt={photo.caption || photo.fileName}
      loading="lazy"
      onError={() => setFailed(true)}
    />
  );
}

function WidgetFrame({
  id,
  title,
  icon,
  configuration,
  action,
  wide = false,
  children,
}: {
  id: HomeWidgetId;
  title: string;
  icon: ReactNode;
  configuration: HomeConfiguration;
  action?: ReactNode;
  wide?: boolean;
  children: ReactNode;
}) {
  const showTitle = configuration.titles.has(id);
  return (
    <article
      className={`${configuration.borders ? "card " : ""}widget home-widget${
        configuration.borders ? "" : " is-borderless"
      }${wide ? " span-two" : ""}`}
      data-home-widget={id}
      aria-label={title}
    >
      {showTitle ? (
        <div className="home-widget-heading">
          <div>
            {icon}
            <h2>{title}</h2>
          </div>
          {action}
        </div>
      ) : action ? (
        <div className="home-widget-action-only">{action}</div>
      ) : null}
      {children}
    </article>
  );
}

function MonthCalendar({ today, language }: { today: string; language: Language }) {
  const date = parseLocalDate(today);
  if (!date) return <p className="muted">{today}</p>;
  const year = date.getFullYear();
  const month = date.getMonth();
  const firstWeekday = (new Date(year, month, 1).getDay() + 6) % 7;
  const days = new Date(year, month + 1, 0).getDate();
  const cells = [
    ...Array.from({ length: firstWeekday }, () => null),
    ...Array.from({ length: days }, (_, index) => index + 1),
  ];
  const weekdays =
    language === "en"
      ? ["M", "T", "W", "T", "F", "S", "S"]
      : ["一", "二", "三", "四", "五", "六", "日"];
  const monthLabel = new Intl.DateTimeFormat(language === "en" ? "en-US" : "zh-CN", {
    year: "numeric",
    month: "long",
  }).format(date);
  return (
    <div className="home-calendar">
      <strong>{monthLabel}</strong>
      <div className="home-calendar-grid" aria-hidden="true">
        {weekdays.map((weekday, index) => (
          <span className="home-calendar-weekday" key={`${weekday}-${index}`}>
            {weekday}
          </span>
        ))}
        {cells.map((day, index) => (
          <span
            className={day === date.getDate() ? "is-today" : undefined}
            key={`${day ?? "empty"}-${index}`}
          >
            {day ?? ""}
          </span>
        ))}
      </div>
    </div>
  );
}

function metricValue(value: number | null, language: Language): string {
  return value === null
    ? tr(language, "暂不可用", "Unavailable")
    : value.toLocaleString(language === "en" ? "en-US" : "zh-CN");
}

export default function HomePage() {
  const language = useAppStore((state) => state.appearance.language);
  const t = useCallback(
    (zh: string, en: string) => tr(language, zh, en),
    [language],
  );
  const [snapshot, setSnapshot] = useState<HomeSnapshot | null>(null);
  const [configuration, setConfiguration] = useState<HomeConfiguration>(() =>
    normalizeHomeConfiguration(null),
  );
  const [details, setDetails] = useState<HomeDetails>({
    diaries: null,
    thoughtCount: null,
    dateRecords: null,
    poemCount: null,
  });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [warning, setWarning] = useState<string | null>(null);
  const [thought, setThought] = useState("");
  const [thoughtBusy, setThoughtBusy] = useState(false);
  const [dailyDrafts, setDailyDrafts] = useState<Record<string, string>>({});
  const [dailyTarget, setDailyTarget] = useState<DailyRecordTarget>("current");
  const [dailyBusy, setDailyBusy] = useState<string | null>(null);
  const [poemBusy, setPoemBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    setWarning(null);
    const results = await Promise.allSettled([
      homeApi.snapshot(),
      homeApi.settings(),
      diaryApi.list(false),
      thoughtApi.list(false),
      dateRecordApi.list(),
      poetryApi.list(),
    ] as const);
    const [snapshotResult, settingsResult, diariesResult, thoughtsResult, datesResult, poemsResult] =
      results;
    if (snapshotResult.status === "rejected") {
      setError(readableError(snapshotResult.reason, language));
      setLoading(false);
      return;
    }

    const next = snapshotResult.value;
    const activeDiary = getActiveDiary();
    const resolvedSnapshot = activeDiary
      ? { ...next, currentDiaryRelativePath: activeDiary }
      : next;
    const nextConfiguration = normalizeHomeConfiguration(
      settingsResult.status === "fulfilled" ? settingsResult.value : null,
    );
    setSnapshot(resolvedSnapshot);
    setConfiguration(nextConfiguration);
    setDailyTarget(resolvedSnapshot.currentDiaryRelativePath ? "current" : "today");
    setDailyDrafts((current) =>
      Object.fromEntries(
        next.dailyTemplates.map((item) => [item.id, current[item.id] ?? item.text]),
      ),
    );
    setDetails({
      diaries: diariesResult.status === "fulfilled" ? diariesResult.value : null,
      thoughtCount: thoughtsResult.status === "fulfilled" ? thoughtsResult.value.length : null,
      dateRecords: datesResult.status === "fulfilled" ? datesResult.value : null,
      poemCount: poemsResult.status === "fulfilled" ? poemsResult.value.length : null,
    });
    if (results.slice(1).some((result) => result.status === "rejected")) {
      setWarning(
        t(
          "部分概览数据暂时不可用，已保留其余首页模块。",
          "Some overview data is temporarily unavailable; the other Home widgets are still shown.",
        ),
      );
    }
    setLoading(false);

    if (nextConfiguration.widgets.includes("poem")) {
      void homeApi
        .dailyPoem(false)
        .then((poem) => {
          setSnapshot((current) =>
            current
              ? {
                  ...current,
                  dailyPoem: {
                    title: poem.title ?? current.dailyPoem?.title ?? "",
                    dynasty: poem.dynasty ?? "",
                    author: poem.author ?? "",
                    content: [poem.content],
                    source: poem.usedFallback
                      ? "builtin"
                      : poem.fromCache
                        ? "cache"
                        : "network",
                  },
                }
              : current,
          );
        })
        .catch(() => undefined);
    }
  }, [language, t]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(null), 3500);
    return () => window.clearTimeout(timer);
  }, [notice]);

  async function submitThought() {
    const content = thought.trim();
    if (!content || thoughtBusy) return;
    setThoughtBusy(true);
    setError(null);
    try {
      await homeApi.createThought(content);
      setThought("");
      setNotice(t("小巧思已保存", "Thought saved"));
      await load();
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setThoughtBusy(false);
    }
  }

  function selectPlaceholder(id: string, element: HTMLTextAreaElement) {
    const value = dailyDrafts[id] ?? "";
    const index = value.indexOf("xx");
    if (index < 0) return;
    window.requestAnimationFrame(() => {
      element.focus();
      element.setSelectionRange(index, index + 2);
    });
  }

  async function submitDaily(id: string) {
    const text = (dailyDrafts[id] ?? "").trim();
    if (!snapshot || !text || dailyBusy) return;
    setDailyBusy(id);
    setError(null);
    try {
      await dailyRecordApi.append(
        text,
        dailyTarget,
        snapshot.currentDiaryRelativePath,
      );
      const template = snapshot.dailyTemplates.find((item) => item.id === id);
      setDailyDrafts((current) => ({ ...current, [id]: template?.text ?? "" }));
      setNotice(t("已写入日记", "Added to diary"));
      await load();
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setDailyBusy(null);
    }
  }

  async function refreshPoem() {
    if (poemBusy) return;
    setPoemBusy(true);
    setError(null);
    try {
      const poem = await homeApi.dailyPoem(true);
      setSnapshot((current) =>
        current
          ? {
              ...current,
              dailyPoem: {
                title: poem.title ?? "",
                dynasty: poem.dynasty ?? "",
                author: poem.author ?? "",
                content: [poem.content],
                source: poem.usedFallback ? "builtin" : "network",
              },
            }
          : current,
      );
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setPoemBusy(false);
    }
  }

  if (loading && !snapshot) {
    return (
      <main className="page home-page page-centered" aria-busy="true">
        <LoaderCircle className="spin" aria-hidden="true" />
        <p>{t("正在整理桌洞…", "Getting your DeskCubby ready…")}</p>
      </main>
    );
  }

  if (!snapshot) {
    return (
      <main className="page home-page page-centered">
        <h1>{t("首页暂时无法打开", "Home is unavailable")}</h1>
        <p className="muted">
          {error ?? t("本地数据读取失败。", "Local data could not be read.")}
        </p>
        <button className="button primary" type="button" onClick={() => void load()}>
          <RefreshCw aria-hidden="true" /> {t("重试", "Retry")}
        </button>
      </main>
    );
  }

  const titleFor = (id: HomeWidgetId) => {
    const labels: Record<HomeWidgetId, [string, string]> = {
      calendar: ["日历", "Calendar"],
      poem: ["每日诗词", "Daily poem"],
      today: ["今天", "Today"],
      date_records: ["日期记录", "Date records"],
      streak: ["连续记录", "Writing streak"],
      month_diaries: ["本月日记", "Diaries this month"],
      total_words: ["日记总字数", "Total diary words"],
      recent_diary: ["最近日记", "Recent diaries"],
      recent_thought: ["最近小巧思", "Recent thoughts"],
      quick_input: ["快速输入", "Quick input"],
      daily_records: ["日常记录", "Daily records"],
      meal_photos: ["饮食图片", "Meal photos"],
      random_diary: ["随机旧日记", "Random old diary"],
      year_progress: ["年度进度", "Year progress"],
      notes: ["笔记", "Notes"],
      game_shortcuts: ["小游戏", "Mini games"],
      record_overview: ["记录概览", "Record overview"],
    };
    return t(...labels[id]);
  };
  const streak = details.diaries ? writingStreak(details.diaries, snapshot.today) : null;
  const dateRecords = details.dateRecords
    ? nearestDateRecords(details.dateRecords, snapshot.today)
    : null;

  const renderWidget = (id: HomeWidgetId) => {
    const title = titleFor(id);
    switch (id) {
      case "calendar":
        return (
          <WidgetFrame id={id} title={title} icon={<CalendarDays />} configuration={configuration} wide>
            <MonthCalendar today={snapshot.today} language={language} />
          </WidgetFrame>
        );
      case "poem":
        return (
          <WidgetFrame
            id={id}
            title={title}
            icon={<Feather />}
            configuration={configuration}
            action={
              <button
                className="icon-button"
                type="button"
                onClick={() => void refreshPoem()}
                disabled={poemBusy}
                aria-label={t("换一句诗词", "Refresh poem")}
              >
                {poemBusy ? <LoaderCircle className="spin" /> : <RefreshCw />}
              </button>
            }
          >
            {snapshot.dailyPoem ? (
              <>
                <blockquote>
                  {snapshot.dailyPoem.content.slice(0, 3).map((line, index) => (
                    <p key={`${line}-${index}`}>{line}</p>
                  ))}
                </blockquote>
                <p className="muted">
                  {snapshot.dailyPoem.dynasty ? `〔${snapshot.dailyPoem.dynasty}〕` : ""}
                  {snapshot.dailyPoem.author} · {snapshot.dailyPoem.title}
                </p>
              </>
            ) : (
              <p className="muted">{t("今日诗词稍后再来。", "Today's poem will return later.")}</p>
            )}
            <Link className="text-link" to="/poetry">
              {t("打开诗词本", "Open poetry book")} <ChevronRight aria-hidden="true" />
            </Link>
          </WidgetFrame>
        );
      case "today":
        return (
          <WidgetFrame id={id} title={title} icon={<CalendarDays />} configuration={configuration}>
            <strong className="home-today-date">{formatDate(snapshot.today, language)}</strong>
          </WidgetFrame>
        );
      case "date_records":
        return (
          <WidgetFrame
            id={id}
            title={title}
            icon={<Target />}
            configuration={configuration}
            action={<Link className="text-link" to="/dates">{t("全部", "All")}</Link>}
            wide
          >
            {dateRecords === null ? (
              <p className="muted">{t("日期记录暂不可用", "Date records are unavailable")}</p>
            ) : dateRecords.length === 0 ? (
              <div className="empty-compact">
                <p>{t("还没有日期记录", "No date records yet")}</p>
                <Link className="button secondary" to="/dates">{t("添加目标日期", "Add a target date")}</Link>
              </div>
            ) : (
              <div className="home-date-records">
                {dateRecords.map((record) => {
                  const distance = dayDistance(snapshot.today, record.dateIso) ?? 0;
                  const distanceText =
                    distance === 0
                      ? t(`今天就是 ${record.name}`, `${record.name} is today`)
                      : distance > 0
                        ? t(`还有 ${distance} 天到 ${record.name}`, `${distance} days until ${record.name}`)
                        : t(`距离 ${record.name} 已过去 ${-distance} 天`, `${-distance} days since ${record.name}`);
                  return (
                    <Link className="compact-list-row" to="/dates" key={record.id}>
                      <span className="home-record-icon">{record.icon || "🎯"}</span>
                      <span>
                        <strong>{distanceText}</strong>
                        <small>{record.dateIso}</small>
                      </span>
                      <ChevronRight aria-hidden="true" />
                    </Link>
                  );
                })}
              </div>
            )}
          </WidgetFrame>
        );
      case "streak":
        return (
          <WidgetFrame id={id} title={title} icon={<Sparkles />} configuration={configuration}>
            <strong className="stat-number">{metricValue(streak, language)}</strong>
            {streak !== null && <span className="muted">{t("天", "days")}</span>}
          </WidgetFrame>
        );
      case "month_diaries":
        return (
          <WidgetFrame id={id} title={title} icon={<BookOpen />} configuration={configuration}>
            <strong className="stat-number">{snapshot.monthlyDiaryCount.toLocaleString()}</strong>
            <span className="muted">{t("篇", "entries")}</span>
          </WidgetFrame>
        );
      case "total_words":
        return (
          <WidgetFrame id={id} title={title} icon={<BookOpen />} configuration={configuration}>
            <strong className="stat-number">{snapshot.totalWordCount.toLocaleString()}</strong>
            <span className="muted">{t("累计字数", "Total words")}</span>
          </WidgetFrame>
        );
      case "recent_diary":
        return (
          <WidgetFrame
            id={id}
            title={title}
            icon={<BookOpen />}
            configuration={configuration}
            action={<Link className="text-link" to="/diary">{t("全部", "All")}</Link>}
            wide
          >
            {snapshot.recentDiaries.length ? (
              <div className="compact-list">
                {snapshot.recentDiaries.slice(0, 3).map((diary) => (
                  <Link
                    className="compact-list-row"
                    key={diary.relativePath}
                    to={`/diary?entry=${encodeURIComponent(diary.relativePath)}`}
                  >
                    <time>{diary.date}</time>
                    <span>
                      <strong>{diary.title}</strong>
                      <small>{diary.excerpt || t("还没有正文", "No content yet")}</small>
                    </span>
                    <ChevronRight aria-hidden="true" />
                  </Link>
                ))}
              </div>
            ) : (
              <p className="muted">{t("还没有日记", "No diaries yet")}</p>
            )}
          </WidgetFrame>
        );
      case "recent_thought":
        return (
          <WidgetFrame
            id={id}
            title={title}
            icon={<Lightbulb />}
            configuration={configuration}
            action={<Link className="text-link" to="/thoughts">{t("全部", "All")}</Link>}
          >
            {snapshot.recentThoughts.length ? (
              <ul className="thought-preview-list">
                {snapshot.recentThoughts.slice(0, 3).map((item) => (
                  <li key={item.id} className={item.highlighted ? "highlighted" : undefined}>
                    <p>{item.content}</p>
                    <small>{item.categoryName ?? t("未分类", "Uncategorized")}</small>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="muted">{t("还没有小巧思。", "No thoughts yet.")}</p>
            )}
          </WidgetFrame>
        );
      case "quick_input":
        return (
          <WidgetFrame id={id} title={title} icon={<MessageSquarePlus />} configuration={configuration}>
            <textarea
              value={thought}
              onChange={(event) => setThought(event.target.value.slice(0, 20_000))}
              placeholder={t("记一条小巧思", "Write a thought")}
              aria-label={t("快速输入小巧思", "Quick thought")}
              rows={4}
            />
            <div className="form-actions">
              <span className="character-count">{thought.length}/20000</span>
              <button
                className="button primary"
                type="button"
                disabled={!thought.trim() || thoughtBusy}
                onClick={() => void submitThought()}
              >
                {thoughtBusy ? <LoaderCircle className="spin" /> : <Lightbulb />}
                {t("保存", "Save")}
              </button>
            </div>
          </WidgetFrame>
        );
      case "daily_records":
        return (
          <WidgetFrame
            id={id}
            title={title}
            icon={<Sparkles />}
            configuration={configuration}
            action={<Link className="text-link" to="/daily">{t("管理", "Manage")}</Link>}
            wide
          >
            <div className="segmented" role="group" aria-label={t("写入目标", "Write target")}>
              <button
                type="button"
                className={dailyTarget === "current" ? "selected" : undefined}
                onClick={() => setDailyTarget("current")}
                disabled={!snapshot.currentDiaryRelativePath}
              >
                {t("当前日记", "Current diary")}
              </button>
              <button
                type="button"
                className={dailyTarget === "today" ? "selected" : undefined}
                onClick={() => setDailyTarget("today")}
              >
                {t("今日日记", "Today's diary")}
              </button>
            </div>
            {snapshot.dailyTemplates.length ? (
              <div className="daily-quick-list">
                {snapshot.dailyTemplates.slice(0, 4).map((template) => (
                  <div className="daily-quick-row" key={template.id}>
                    <textarea
                      rows={2}
                      value={dailyDrafts[template.id] ?? template.text}
                      aria-label={t("日常记录内容", "Daily record text")}
                      onClick={(event) => selectPlaceholder(template.id, event.currentTarget)}
                      onChange={(event) =>
                        setDailyDrafts((current) => ({
                          ...current,
                          [template.id]: event.target.value.slice(0, 100),
                        }))
                      }
                    />
                    <button
                      className="icon-button"
                      type="button"
                      aria-label={t("写入日记", "Add to diary")}
                      disabled={
                        dailyBusy !== null ||
                        !(dailyDrafts[template.id] ?? template.text).trim()
                      }
                      onClick={() => void submitDaily(template.id)}
                    >
                      {dailyBusy === template.id ? (
                        <LoaderCircle className="spin" />
                      ) : (
                        <ChevronRight />
                      )}
                    </button>
                  </div>
                ))}
              </div>
            ) : (
              <div className="empty-compact">
                <p>{t("还没有日常事件", "No daily events yet")}</p>
                <Link className="button secondary" to="/daily">{t("添加", "Add")}</Link>
              </div>
            )}
          </WidgetFrame>
        );
      case "meal_photos":
        return (
          <WidgetFrame
            id={id}
            title={title}
            icon={<Utensils />}
            configuration={configuration}
            action={<Link className="text-link" to="/meals">{t("吃历", "Meal calendar")}</Link>}
            wide
          >
            <div className="home-meal-actions" aria-label={t("饮食快捷入口", "Meal shortcuts")}>
              {MEAL_OPTIONS.map(([, zh, en, fallback], index) => (
                <Link className="button secondary button-small" to="/meals" key={zh}>
                  {configuration.mealButtonsUseIcons
                    ? configuration.mealButtonIcons[index] || fallback
                    : t(zh, en)}
                </Link>
              ))}
            </div>
            {snapshot.mealPhotos.length ? (
              <div className="meal-strip">
                {snapshot.mealPhotos.slice(0, 6).map((photo) => (
                  <AssetImage key={photo.id} photo={photo} language={language} />
                ))}
              </div>
            ) : (
              <p className="muted">
                {t("从吃历导入今天的饮食图片。", "Import today's meal photos from the meal calendar.")}
              </p>
            )}
          </WidgetFrame>
        );
      case "random_diary":
        return (
          <WidgetFrame id={id} title={title} icon={<Dices />} configuration={configuration}>
            {snapshot.randomDiary ? (
              <Link
                className="random-diary"
                to={`/diary?entry=${encodeURIComponent(snapshot.randomDiary.relativePath)}`}
              >
                <strong>{snapshot.randomDiary.title}</strong>
                <small>{snapshot.randomDiary.date}</small>
              </Link>
            ) : (
              <p className="muted">{t("还没有可回顾的日记", "No diary to revisit")}</p>
            )}
          </WidgetFrame>
        );
      case "year_progress":
        return (
          <WidgetFrame id={id} title={title} icon={<ChartNoAxesCombined />} configuration={configuration}>
            <strong className="stat-number">{Math.round(snapshot.yearProgress * 100)}%</strong>
            <div className="year-progress" aria-label={title}>
              <span style={{ width: `${snapshot.yearProgress * 100}%` }} />
            </div>
          </WidgetFrame>
        );
      case "notes":
        return (
          <WidgetFrame id={id} title={title} icon={<FileText />} configuration={configuration}>
            <p className="muted">
              {t("打开 Obsidian 兼容的 Markdown 笔记库", "Open your Obsidian-compatible Markdown vault")}
            </p>
            <Link className="button secondary" to="/notes">
              <FileText aria-hidden="true" /> {t("打开笔记", "Open notes")}
            </Link>
          </WidgetFrame>
        );
      case "game_shortcuts":
        return (
          <WidgetFrame id={id} title={title} icon={<Gamepad2 />} configuration={configuration} wide>
            {configuration.gameShortcuts.length ? (
              <div className="home-game-links">
                {GAME_OPTIONS.filter(([gameId]) => configuration.gameShortcuts.includes(gameId)).map(
                  ([gameId, zh, en]) => (
                    <Link
                      className="button secondary button-small"
                      key={gameId}
                      to={`/games?game=${encodeURIComponent(gameId)}`}
                    >
                      {t(zh, en)}
                    </Link>
                  ),
                )}
              </div>
            ) : (
              <p className="muted">
                {t(
                  "可在“设置 → 子页面设置 → 主页”选择快捷入口",
                  "Choose shortcuts in Settings → Subpage settings → Home",
                )}
              </p>
            )}
          </WidgetFrame>
        );
      case "record_overview":
        return (
          <WidgetFrame
            id={id}
            title={title}
            icon={<ChartNoAxesCombined />}
            configuration={configuration}
            action={<Link className="text-link" to="/statistics">{t("查看统计", "View statistics")}</Link>}
            wide
          >
            <div className="home-metrics">
              {[
                [details.diaries?.length ?? null, t("日记", "Diaries")],
                [details.thoughtCount, t("小巧思", "Thoughts")],
                [details.dateRecords?.length ?? null, t("日期", "Dates")],
                [details.poemCount, t("诗词", "Poems")],
              ].map(([value, label]) => (
                <div key={String(label)}>
                  <strong>{metricValue(value as number | null, language)}</strong>
                  <span>{label}</span>
                </div>
              ))}
            </div>
          </WidgetFrame>
        );
    }
  };

  return (
    <main className="page home-page">
      <header className="page-header home-hero">
        <div>
          <p className="eyebrow">{formatDate(snapshot.today, language)}</p>
          <h1>{snapshot.greeting}</h1>
        </div>
        <button
          className="icon-button"
          type="button"
          onClick={() => void load()}
          disabled={loading}
          aria-label={t("刷新首页", "Refresh home")}
        >
          <RefreshCw className={loading ? "spin" : ""} aria-hidden="true" />
        </button>
      </header>

      {error && <div className="inline-error" role="alert">{error}</div>}
      {warning && <div className="status-banner warning" role="status">{warning}</div>}
      {notice && <div className="toast" role="status">{notice}</div>}

      {configuration.widgets.length ? (
        <section className="home-grid" aria-label={t("首页模块", "Home widgets")}>
          {configuration.widgets.map((id) => (
            <div className="home-widget-slot" key={id}>
              {renderWidget(id)}
            </div>
          ))}
        </section>
      ) : (
        <section className="panel empty-state home-empty-state">
          <ImagePlus aria-hidden="true" />
          <h2>{t("首页暂无可显示模块", "No Home widgets to display")}</h2>
          <p>
            {t(
              "浏览器与天气模块不会在 Windows 显示；可在主页设置中添加其他模块。",
              "Browser and weather widgets are not shown on Windows. Add other widgets in Home settings.",
            )}
          </p>
          <Link className="button primary" to="/settings/home">
            {t("打开主页设置", "Open Home settings")}
          </Link>
        </section>
      )}
    </main>
  );
}
