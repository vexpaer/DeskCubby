import { useCallback, useEffect, useMemo, useState } from "react";
import {
  BookOpen,
  CalendarDays,
  ChevronRight,
  Feather,
  Lightbulb,
  LoaderCircle,
  MessageSquarePlus,
  RefreshCw,
  Sparkles,
  Utensils,
} from "lucide-react";
import { Link } from "react-router-dom";
import { useAppStore } from "../store/appStore";
import {
  dailyRecordApi,
  getActiveDiary,
  homeApi,
  readableError,
  tr,
  type DailyRecordTarget,
  type HomeSnapshot,
  type Language,
  type MealPhoto,
} from "../lib/ipc";

function formatDate(value: string, language: Language) {
  const parsed = new Date(`${value}T00:00:00`);
  return Number.isNaN(parsed.valueOf())
    ? value
    : new Intl.DateTimeFormat(language === "en" ? "en-US" : "zh-CN", {
        weekday: "long",
        year: "numeric",
        month: "long",
        day: "numeric",
      }).format(parsed);
}

function AssetImage({
  photo,
  className,
  language,
}: {
  photo: MealPhoto;
  className?: string;
  language: Language;
}) {
  const [failed, setFailed] = useState(false);
  if (!photo.assetUrl || photo.missing || failed) {
    return (
      <div className={`${className ?? ""} media-placeholder`} role="img">
        <Utensils aria-hidden="true" />
        <span>{tr(language, "图片不可用", "Image unavailable")}</span>
      </div>
    );
  }
  return (
    <img
      className={className}
      src={photo.assetUrl}
      alt={photo.caption || photo.fileName}
      loading="lazy"
      onError={() => setFailed(true)}
    />
  );
}

export default function HomePage() {
  const language = useAppStore((state) => state.appearance.language);
  const t = useCallback(
    (zh: string, en: string) => tr(language, zh, en),
    [language],
  );
  const [snapshot, setSnapshot] = useState<HomeSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [thought, setThought] = useState("");
  const [thoughtBusy, setThoughtBusy] = useState(false);
  const [dailyDrafts, setDailyDrafts] = useState<Record<string, string>>({});
  const [dailyTarget, setDailyTarget] =
    useState<DailyRecordTarget>("current");
  const [dailyBusy, setDailyBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const next = await homeApi.snapshot();
      const activeDiary = getActiveDiary();
      const resolvedSnapshot = activeDiary
        ? { ...next, currentDiaryRelativePath: activeDiary }
        : next;
      setSnapshot(resolvedSnapshot);
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
        .catch(() => {
          // The snapshot already contains the cached/built-in fallback. A
          // transient network failure must not turn Home into an error state.
        });
      setDailyTarget(
        resolvedSnapshot.currentDiaryRelativePath ? "current" : "today",
      );
      setDailyDrafts(
        Object.fromEntries(
          next.dailyTemplates.map((item) => [item.id, item.text]),
        ),
      );
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setLoading(false);
    }
  }, [language]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(null), 3500);
    return () => window.clearTimeout(timer);
  }, [notice]);

  const currentMonthLabel = useMemo(
    () =>
      snapshot
        ? new Intl.DateTimeFormat(language === "en" ? "en-US" : "zh-CN", {
            month: "long",
            year: "numeric",
          }).format(new Date(`${snapshot.today}T00:00:00`))
        : "",
    [language, snapshot],
  );

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
      setNotice(t("已写入日记", "Added to diary"));
      const template = snapshot.dailyTemplates.find((item) => item.id === id);
      setDailyDrafts((current) => ({
        ...current,
        [id]: template?.text ?? "",
      }));
      await load();
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setDailyBusy(null);
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

  return (
    <main className="page home-page">
      <header className="page-header home-hero">
        <div>
          <p className="eyebrow">{formatDate(snapshot.today, language)}</p>
          <h1>{snapshot.greeting}</h1>
          <p className="muted">
            {language === "en"
              ? `${currentMonthLabel}: ${snapshot.monthlyDiaryCount} diaries and ${snapshot.monthlyThoughtCount} thoughts.`
              : `${currentMonthLabel}，已写 ${snapshot.monthlyDiaryCount} 篇日记与 ${snapshot.monthlyThoughtCount} 条小巧思。`}
          </p>
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

      {error && (
        <div className="inline-error" role="alert">
          {error}
        </div>
      )}
      {notice && (
        <div className="toast" role="status">
          {notice}
        </div>
      )}

      <section
        className="home-grid"
        aria-label={t("首页组件", "Home widgets")}
      >
        <article className="card widget today-widget">
          <div className="card-heading">
            <CalendarDays aria-hidden="true" />
            <h2>{t("今天", "Today")}</h2>
          </div>
          <strong className="today-number">
            {new Date(`${snapshot.today}T00:00:00`).getDate()}
          </strong>
          <div
            className="year-progress"
            aria-label={t("年度进度", "Year progress")}
          >
            <span style={{ width: `${snapshot.yearProgress * 100}%` }} />
          </div>
          <p className="muted">
            {language === "en"
              ? `${Math.round(snapshot.yearProgress * 100)}% of the year`
              : `今年已走过 ${Math.round(snapshot.yearProgress * 100)}%`}
          </p>
        </article>

        <article className="card widget poem-widget">
          <div className="card-heading">
            <Feather aria-hidden="true" />
            <h2>{t("每日诗词", "Daily poem")}</h2>
          </div>
          {snapshot.dailyPoem ? (
            <>
              <blockquote>
                {snapshot.dailyPoem.content.slice(0, 3).map((line) => (
                  <p key={line}>{line}</p>
                ))}
              </blockquote>
              <p className="muted">
                {snapshot.dailyPoem.dynasty
                  ? `〔${snapshot.dailyPoem.dynasty}〕`
                  : ""}
                {snapshot.dailyPoem.author} · {snapshot.dailyPoem.title}
              </p>
            </>
          ) : (
            <div className="empty-compact">
              <Sparkles aria-hidden="true" />
              <p>{t("今日诗词稍后再来。", "Today's poem will return later.")}</p>
            </div>
          )}
          <Link className="text-link" to="/poetry">
            {t("打开诗词本", "Open poetry book")}{" "}
            <ChevronRight aria-hidden="true" />
          </Link>
        </article>

        <article className="card widget quick-thought-widget">
          <div className="card-heading">
            <MessageSquarePlus aria-hidden="true" />
            <h2>{t("快速小巧思", "Quick thought")}</h2>
          </div>
          <textarea
            value={thought}
            onChange={(event) => setThought(event.target.value.slice(0, 20_000))}
            placeholder={t("此刻在想什么？", "What's on your mind?")}
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
              {thoughtBusy ? (
                <LoaderCircle className="spin" aria-hidden="true" />
              ) : (
                <Lightbulb aria-hidden="true" />
              )}
              {t("保存", "Save")}
            </button>
          </div>
        </article>

        <article className="card widget stats-widget">
          <div className="card-heading">
            <BookOpen aria-hidden="true" />
            <h2>{t("文字足迹", "Writing trail")}</h2>
          </div>
          <strong className="stat-number">
            {snapshot.totalWordCount.toLocaleString()}
          </strong>
          <span className="muted">{t("累计字数", "Total words")}</span>
          {snapshot.randomDiary && (
            <Link
              className="random-diary"
              to={`/diary?entry=${encodeURIComponent(
                snapshot.randomDiary.relativePath,
              )}`}
            >
              <span>{t("随机翻到", "From the archive")}</span>
              <strong>{snapshot.randomDiary.title}</strong>
              <small>{snapshot.randomDiary.date}</small>
            </Link>
          )}
        </article>

        <article className="card widget recent-widget span-two">
          <div className="card-heading with-action">
            <div>
              <BookOpen aria-hidden="true" />
              <h2>{t("最近日记", "Recent diaries")}</h2>
            </div>
            <Link className="text-link" to="/diary">
              {t("全部", "All")} <ChevronRight aria-hidden="true" />
            </Link>
          </div>
          {snapshot.recentDiaries.length ? (
            <div className="compact-list">
              {snapshot.recentDiaries.map((diary) => (
                <Link
                  className="compact-list-row"
                  key={diary.relativePath}
                  to={`/diary?entry=${encodeURIComponent(diary.relativePath)}`}
                >
                  <time>{diary.date}</time>
                  <span>
                    <strong>{diary.title}</strong>
                    <small>
                      {diary.excerpt || t("还没有正文", "No content yet")}
                    </small>
                  </span>
                  <ChevronRight aria-hidden="true" />
                </Link>
              ))}
            </div>
          ) : (
            <div className="empty-compact">
              <BookOpen aria-hidden="true" />
              <p>
                {t(
                  "写下第一篇日记，让今天有迹可循。",
                  "Write your first diary and give today a place.",
                )}
              </p>
              <Link className="button secondary" to="/diary">
                {t("新建日记", "New diary")}
              </Link>
            </div>
          )}
        </article>

        <article className="card widget thought-widget">
          <div className="card-heading with-action">
            <div>
              <Lightbulb aria-hidden="true" />
              <h2>{t("最近小巧思", "Recent thoughts")}</h2>
            </div>
            <Link className="text-link" to="/thoughts">
              {t("全部", "All")} <ChevronRight aria-hidden="true" />
            </Link>
          </div>
          {snapshot.recentThoughts.length ? (
            <ul className="thought-preview-list">
              {snapshot.recentThoughts.map((item) => (
                <li
                  key={item.id}
                  className={item.highlighted ? "highlighted" : undefined}
                >
                  <p>{item.content}</p>
                  <small>
                    {item.categoryName ?? t("未分类", "Uncategorized")}
                  </small>
                </li>
              ))}
            </ul>
          ) : (
            <p className="muted">
              {t("还没有小巧思。", "No thoughts yet.")}
            </p>
          )}
        </article>

        <article className="card widget meals-widget">
          <div className="card-heading with-action">
            <div>
              <Utensils aria-hidden="true" />
              <h2>{t("饮食图片", "Meal photos")}</h2>
            </div>
            <Link className="text-link" to="/meals">
              {t("吃历", "Meal calendar")} <ChevronRight aria-hidden="true" />
            </Link>
          </div>
          {snapshot.mealPhotos.length ? (
            <div className="meal-strip">
              {snapshot.mealPhotos.slice(0, 6).map((photo) => (
                <AssetImage
                  key={photo.id}
                  photo={photo}
                  className="meal-strip-image"
                  language={language}
                />
              ))}
            </div>
          ) : (
            <p className="muted">
              {t(
                "日记中的饮食照片会出现在这里。",
                "Meal photos from your diaries appear here.",
              )}
            </p>
          )}
        </article>

        <article className="card widget daily-widget span-two">
          <div className="card-heading with-action">
            <div>
              <Sparkles aria-hidden="true" />
              <h2>{t("日常记录", "Daily records")}</h2>
            </div>
            <Link className="text-link" to="/daily">
              {t("管理模板", "Manage templates")}{" "}
              <ChevronRight aria-hidden="true" />
            </Link>
          </div>
          <div
            className="segmented"
            role="group"
            aria-label={t("写入目标", "Write target")}
          >
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
                    onClick={(event) =>
                      selectPlaceholder(template.id, event.currentTarget)
                    }
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
                      <LoaderCircle className="spin" aria-hidden="true" />
                    ) : (
                      <ChevronRight aria-hidden="true" />
                    )}
                  </button>
                </div>
              ))}
            </div>
          ) : (
            <div className="empty-compact">
              <p>
                {t(
                  "创建一句模板，以后替换 xx 就能快速记录。",
                  "Create a sentence template and replace xx for quick entries.",
                )}
              </p>
              <Link className="button secondary" to="/daily">
                {t("创建模板", "Create template")}
              </Link>
            </div>
          )}
        </article>
      </section>
    </main>
  );
}
