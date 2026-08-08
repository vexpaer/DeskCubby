import {
  type CSSProperties,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import {
  Download,
  ImageOff,
  ImagePlus,
  LoaderCircle,
  MapPin,
  RefreshCw,
  Sparkles,
  SlidersHorizontal,
  Utensils,
  X,
  Zap,
} from "lucide-react";
import {
  aiApi,
  createAiRequestToken,
  type CalorieProgress,
} from "../lib/aiApi";
import {
  mealApi,
  readableError,
  tr,
  type MealCategory,
  type MealColumns,
  type MealPhoto,
  type MealQuery,
  type MealViewPreferences,
} from "../lib/ipc";
import { useAppStore } from "../store/appStore";
import "./MealPage.ai.css";
import "./MealPage.layout.css";

const CATEGORY_META: Record<
  MealCategory,
  { icon: string; zh: string; en: string; order: number }
> = {
  breakfast: { icon: "🥪", zh: "早餐", en: "Breakfast", order: 0 },
  lunch: { icon: "🍱", zh: "午餐", en: "Lunch", order: 1 },
  afternoon_tea: {
    icon: "🍹",
    zh: "下午茶",
    en: "Afternoon tea",
    order: 2,
  },
  dinner: { icon: "🍜", zh: "晚餐", en: "Dinner", order: 3 },
  fruit: { icon: "🍊", zh: "水果", en: "Fruit", order: 4 },
  late_night: { icon: "🍤", zh: "夜宵", en: "Late night", order: 5 },
};

const ALL_CATEGORIES = Object.keys(CATEGORY_META) as MealCategory[];

const DEFAULT_VIEW_PREFERENCES: MealViewPreferences = {
  schemaVersion: 1,
  dayColumns: 1,
  wrapEnabled: false,
  columns: "smart",
  showCaptions: true,
  imageMaxHeightPx: 124,
  filter: {
    enabled: false,
    brightness: 100,
    contrast: 100,
    saturation: 100,
    warmth: 0,
    tint: 0,
  },
};

function dateToday() {
  const now = new Date();
  return [
    now.getFullYear(),
    String(now.getMonth() + 1).padStart(2, "0"),
    String(now.getDate()).padStart(2, "0"),
  ].join("-");
}

function filterCss(
  brightness: number,
  contrast: number,
  saturation: number,
  warmth: number,
  tint: number,
) {
  return [
    `brightness(${brightness}%)`,
    `contrast(${contrast}%)`,
    `saturate(${saturation}%)`,
    `sepia(${Math.abs(warmth) * 0.002})`,
    `hue-rotate(${tint * 0.45 + warmth * -0.16}deg)`,
  ].join(" ");
}

export default function MealPage() {
  const language = useAppStore((state) => state.appearance.language);
  const t = useCallback(
    (zh: string, en: string) => tr(language, zh, en),
    [language],
  );
  const [photos, setPhotos] = useState<MealPhoto[]>([]);
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [categories, setCategories] =
    useState<MealCategory[]>(ALL_CATEGORIES);
  const [viewPreferences, setViewPreferences] =
    useState<MealViewPreferences>(DEFAULT_VIEW_PREFERENCES);
  const [preferencesLoading, setPreferencesLoading] = useState(true);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [selected, setSelected] = useState<MealPhoto | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [importDate, setImportDate] = useState(dateToday());
  const [importCategory, setImportCategory] =
    useState<MealCategory>("breakfast");
  const calorieTokenRef = useRef<string | null>(null);
  const [estimatingDate, setEstimatingDate] = useState<string | null>(null);
  const [calorieProgress, setCalorieProgress] =
    useState<CalorieProgress | null>(null);
  const preferencesRef = useRef(viewPreferences);
  const pendingPreferencesRef = useRef<MealViewPreferences | null>(null);
  const savingPreferencesRef = useRef(false);
  const mountedRef = useRef(true);

  const {
    columns,
    dayColumns,
    wrapEnabled,
    showCaptions,
    imageMaxHeightPx,
    filter: {
      enabled: filterEnabled,
      brightness,
      contrast,
      saturation,
      warmth,
      tint,
    },
  } = viewPreferences;

  const queuePreferencesSave = useCallback(
    (next: MealViewPreferences) => {
      pendingPreferencesRef.current = next;
      if (savingPreferencesRef.current) return;
      savingPreferencesRef.current = true;
      void (async () => {
        do {
          const pending = pendingPreferencesRef.current;
          pendingPreferencesRef.current = null;
          if (!pending) break;
          try {
            await mealApi.updateViewPreferences(pending);
          } catch (reason) {
            if (mountedRef.current) {
              setError(readableError(reason, language));
            }
            if (!pendingPreferencesRef.current) {
              try {
                const persisted = await mealApi.getViewPreferences();
                if (mountedRef.current && !pendingPreferencesRef.current) {
                  preferencesRef.current = persisted;
                  setViewPreferences(persisted);
                }
              } catch {
                // Keep the explicit save error. A later interaction retries
                // the latest full meal-view preference snapshot.
              }
            }
          }
        } while (pendingPreferencesRef.current);
        savingPreferencesRef.current = false;
      })();
    },
    [language],
  );

  const updateViewPreferences = useCallback(
    (transform: (current: MealViewPreferences) => MealViewPreferences) => {
      const next = transform(preferencesRef.current);
      preferencesRef.current = next;
      setViewPreferences(next);
      queuePreferencesSave(next);
    },
    [queuePreferencesSave],
  );

  useEffect(() => {
    mountedRef.current = true;
    void mealApi
      .getViewPreferences()
      .then((preferences) => {
        if (!mountedRef.current) return;
        preferencesRef.current = preferences;
        setViewPreferences(preferences);
      })
      .catch((reason) => {
        if (mountedRef.current) setError(readableError(reason, language));
      })
      .finally(() => {
        if (mountedRef.current) setPreferencesLoading(false);
      });
    return () => {
      mountedRef.current = false;
    };
  }, [language]);

  const query: MealQuery = useMemo(
    () => ({
      startDate: startDate || null,
      endDate: endDate || null,
      categories,
    }),
    [categories, endDate, startDate],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setPhotos(await mealApi.list(query));
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setLoading(false);
    }
  }, [language, query]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 150);
    return () => window.clearTimeout(timer);
  }, [load]);

  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(null), 3500);
    return () => window.clearTimeout(timer);
  }, [notice]);

  useEffect(() => {
    let unlisten: (() => void) | undefined;
    void aiApi.onCalorieProgress((progress) => {
      if (progress.requestToken === calorieTokenRef.current) {
        setCalorieProgress(progress);
      }
    }).then((dispose) => {
      unlisten = dispose;
    });
    return () => unlisten?.();
  }, []);

  const grouped = useMemo(() => {
    const map = new Map<string, MealPhoto[]>();
    for (const photo of photos) {
      map.set(photo.date, [...(map.get(photo.date) ?? []), photo]);
    }
    for (const [, list] of map) {
      list.sort(
        (a, b) =>
          CATEGORY_META[a.category].order - CATEGORY_META[b.category].order,
      );
    }
    return [...map].sort(([a], [b]) => b.localeCompare(a));
  }, [photos]);

  const groupedDayColumns = useMemo(() => {
    if (dayColumns === 1) return [grouped];
    const midpoint = Math.ceil(grouped.length / 2);
    return [grouped.slice(0, midpoint), grouped.slice(midpoint)];
  }, [dayColumns, grouped]);

  const imageFilter = filterEnabled
    ? filterCss(brightness, contrast, saturation, warmth, tint)
    : "none";

  function toggleCategory(category: MealCategory) {
    setCategories((current) =>
      current.includes(category)
        ? current.filter((item) => item !== category)
        : [...current, category],
    );
  }

  async function importPhotos() {
    setBusy(true);
    setError(null);
    try {
      const imported = await mealApi.selectAndImport(importDate, importCategory);
      if (imported.length) {
        setNotice(
          language === "en"
            ? `${imported.length} image${imported.length === 1 ? "" : "s"} imported`
            : `已导入 ${imported.length} 张图片`,
        );
        await load();
      }
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setBusy(false);
    }
  }

  async function exportPng() {
    if (!photos.length || busy) return;
    setBusy(true);
    setError(null);
    try {
      const destination = await mealApi.exportPng({
        ...query,
        columns,
        showCaptions,
        filterEnabled,
        brightness,
        contrast,
        saturation,
        warmth,
        tint,
      });
      if (destination) {
        setNotice(t("吃历长图已导出", "Meal calendar image exported"));
      }
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setBusy(false);
    }
  }

  async function estimateDay(date: string, dayPhotos: MealPhoto[]) {
    if (estimatingDate) return;
    const fileNames = [
      ...new Set(
        dayPhotos
          .filter((photo) => !photo.missing)
          .map((photo) => photo.fileName),
      ),
    ];
    if (!fileNames.length) return;
    const requestToken = createAiRequestToken();
    calorieTokenRef.current = requestToken;
    setEstimatingDate(date);
    setCalorieProgress({
      schemaVersion: 1,
      requestToken,
      stage: "IMAGE_RECOGNITION",
      completedImages: 0,
      totalImages: fileNames.length,
      photoIndex: null,
      content: "",
      reasoning: "",
    });
    setError(null);
    try {
      const result = await aiApi.estimateMealDay(requestToken, date, fileNames);
      // The Rust boundary writes every estimate in one dc-media.json v2
      // transaction. Refresh only after that full command succeeds.
      await load();
      setNotice(
        language === "en"
          ? `Estimated ${result.estimates.length} photo${result.estimates.length === 1 ? "" : "s"} for ${date}`
          : `已完成 ${date} 的 ${result.estimates.length} 张图片热量估算`,
      );
    } catch (reason) {
      // Do not refresh here: a failed day must never look partially committed.
      setError(readableError(reason, language));
    } finally {
      calorieTokenRef.current = null;
      setEstimatingDate(null);
      setCalorieProgress(null);
    }
  }

  function calorieProgressText(progress: CalorieProgress) {
    if (progress.stage === "TEXT_ESTIMATION") {
      return t("正在统一计算并避免重复餐食…", "Calculating the whole day and removing duplicates…");
    }
    if (progress.stage === "SAVING") {
      return t("正在原子写入媒体元数据…", "Committing media metadata atomically…");
    }
    return t(
      `正在识别图片 ${progress.completedImages}/${progress.totalImages}（最多 3 张并发）`,
      `Recognizing ${progress.completedImages}/${progress.totalImages} images (up to 3 concurrently)`,
    );
  }

  return (
    <main className="page meal-page">
      <header className="page-header">
        <div>
          <p className="eyebrow">
            {t("按日 · 按餐别", "By date · By meal")}
          </p>
          <h1>{t("吃历", "Meal calendar")}</h1>
          <p className="muted">
            {t(
              "照片来自 Markdown 日记；滤镜只影响显示和导出，不改写原图。",
              "Photos come from Markdown diaries. Filters affect display and export only; originals stay untouched.",
            )}
          </p>
        </div>
        <div className="header-actions">
          <button
            className={
              filtersOpen || filterEnabled
                ? "button secondary selected"
                : "button secondary"
            }
            type="button"
            aria-expanded={filtersOpen}
            onClick={() => setFiltersOpen((value) => !value)}
            disabled={preferencesLoading}
          >
            <SlidersHorizontal aria-hidden="true" /> {t("滤镜", "Filters")}
          </button>
          <button
            className="button secondary"
            type="button"
            onClick={() => void load()}
            disabled={loading}
          >
            <RefreshCw className={loading ? "spin" : ""} aria-hidden="true" />
            {t("刷新", "Refresh")}
          </button>
          <button
            className="button primary"
            type="button"
            onClick={() => void exportPng()}
            disabled={!photos.length || busy || preferencesLoading}
          >
            {busy ? (
              <LoaderCircle className="spin" aria-hidden="true" />
            ) : (
              <Download aria-hidden="true" />
            )}
            {t("导出长图", "Export image")}
          </button>
        </div>
      </header>

      {error && (
        <div className="inline-error" role="alert">
          {error}
          <button
            type="button"
            onClick={() => setError(null)}
            aria-label={t("关闭", "Close")}
          >
            <X aria-hidden="true" />
          </button>
        </div>
      )}
      {notice && (
        <div className="toast" role="status">
          {notice}
        </div>
      )}

      <section
        className="meal-toolbar panel"
        aria-label={t("吃历筛选", "Meal calendar filters")}
      >
        <div className="date-range">
          <label>
            {t("开始日期", "Start date")}
            <input
              type="date"
              value={startDate}
              max={endDate || undefined}
              onChange={(event) => setStartDate(event.target.value)}
            />
          </label>
          <span aria-hidden="true">—</span>
          <label>
            {t("结束日期", "End date")}
            <input
              type="date"
              value={endDate}
              min={startDate || undefined}
              onChange={(event) => setEndDate(event.target.value)}
            />
          </label>
        </div>

        <div
          className="category-chips"
          aria-label={t("餐别筛选", "Meal category filters")}
        >
          {ALL_CATEGORIES.map((category) => {
            const meta = CATEGORY_META[category];
            const active = categories.includes(category);
            return (
              <button
                type="button"
                key={category}
                className={active ? "chip selected" : "chip"}
                aria-pressed={active}
                onClick={() => toggleCategory(category)}
              >
                <span aria-hidden="true">{meta.icon}</span>{" "}
                {language === "en" ? meta.en : meta.zh}
              </button>
            );
          })}
        </div>

        <div className="meal-layout-controls">
          <div
            className="segmented"
            role="group"
            aria-label={t("每日卡片布局", "Daily card layout")}
          >
            {([1, 2] as const).map((value) => (
              <button
                type="button"
                key={value}
                className={dayColumns === value ? "selected" : undefined}
                aria-pressed={dayColumns === value}
                disabled={preferencesLoading}
                onClick={() =>
                  updateViewPreferences((current) => ({
                    ...current,
                    dayColumns: value,
                  }))
                }
              >
                {value === 1 ? t("单列", "One column") : t("双列", "Two columns")}
              </button>
            ))}
          </div>
          <div
            className="segmented"
            role="group"
            aria-label={t("每行图片", "Images per row")}
          >
            {(["smart", 2, 3] as MealColumns[]).map((value) => (
              <button
                type="button"
                key={value}
                className={columns === value ? "selected" : undefined}
                aria-pressed={columns === value}
                disabled={preferencesLoading || !wrapEnabled}
                onClick={() =>
                  updateViewPreferences((current) => ({
                    ...current,
                    columns: value,
                  }))
                }
              >
                {value === "smart"
                  ? t("智能", "Smart")
                  : language === "en"
                    ? `${value} columns`
                    : `${value} 列`}
              </button>
            ))}
          </div>
          <label className="switch-row">
            <input
              type="checkbox"
              checked={wrapEnabled}
              disabled={preferencesLoading}
              onChange={(event) =>
                updateViewPreferences((current) => ({
                  ...current,
                  wrapEnabled: event.target.checked,
                }))
              }
            />
            {t("智能换行", "Wrap photos")}
          </label>
          <label className="switch-row">
            <input
              type="checkbox"
              checked={showCaptions}
              disabled={preferencesLoading}
              onChange={(event) =>
                updateViewPreferences((current) => ({
                  ...current,
                  showCaptions: event.target.checked,
                }))
              }
            />
            {t("显示说明", "Show captions")}
          </label>
        </div>
      </section>

      {filtersOpen && (
        <section
          className="filter-panel panel"
          aria-label={t("非破坏滤镜", "Non-destructive filters")}
        >
          <label className="switch-row">
            <input
              type="checkbox"
              checked={filterEnabled}
              disabled={preferencesLoading}
              onChange={(event) =>
                updateViewPreferences((current) => ({
                  ...current,
                  filter: {
                    ...current.filter,
                    enabled: event.target.checked,
                  },
                }))
              }
            />
            {t("启用滤镜", "Enable filters")}
          </label>
          {[
            { key: "brightness" as const, label: t("亮度", "Brightness"), value: brightness, min: 0, max: 200 },
            { key: "contrast" as const, label: t("对比度", "Contrast"), value: contrast, min: 0, max: 200 },
            { key: "saturation" as const, label: t("饱和度", "Saturation"), value: saturation, min: 0, max: 200 },
            { key: "warmth" as const, label: t("色温", "Warmth"), value: warmth, min: -100, max: 100 },
            { key: "tint" as const, label: t("色调", "Tint"), value: tint, min: -100, max: 100 },
          ].map(({ key, label, value, min, max }) => (
            <label className="range-field" key={key}>
              <span>
                {label}
                <output>{value}</output>
              </span>
              <input
                type="range"
                min={min}
                max={max}
                value={value}
                disabled={preferencesLoading}
                onChange={(event) =>
                  updateViewPreferences((current) => ({
                    ...current,
                    filter: {
                      ...current.filter,
                      [key]: Number(event.target.value),
                    },
                  }))
                }
              />
            </label>
          ))}
          <button
            className="button secondary"
            type="button"
            disabled={preferencesLoading}
            onClick={() => {
              updateViewPreferences((current) => ({
                ...current,
                filter: {
                  ...current.filter,
                  brightness: 100,
                  contrast: 100,
                  saturation: 100,
                  warmth: 0,
                  tint: 0,
                },
              }));
            }}
          >
            {t("恢复原始显示", "Reset display")}
          </button>
        </section>
      )}

      <section
        className="import-panel panel"
        aria-label={t("导入饮食图片", "Import meal photos")}
      >
        <div>
          <ImagePlus aria-hidden="true" />
          <span>
            <strong>{t("添加饮食图片", "Add meal photos")}</strong>
            <small>
              {t(
                "图片会安全压缩并写入媒体目录，再追加到当日日记。",
                "Images are safely compressed into the media folder and appended to that day's diary.",
              )}
            </small>
          </span>
        </div>
        <label>
          {t("日期", "Date")}
          <input
            type="date"
            value={importDate}
            onChange={(event) => setImportDate(event.target.value)}
          />
        </label>
        <label>
          {t("餐别", "Meal")}
          <select
            value={importCategory}
            onChange={(event) =>
              setImportCategory(event.target.value as MealCategory)
            }
          >
            {ALL_CATEGORIES.map((category) => (
              <option key={category} value={category}>
                {CATEGORY_META[category].icon}{" "}
                {language === "en"
                  ? CATEGORY_META[category].en
                  : CATEGORY_META[category].zh}
              </option>
            ))}
          </select>
        </label>
        <button
          className="button primary"
          type="button"
          disabled={busy}
          onClick={() => void importPhotos()}
        >
          <ImagePlus aria-hidden="true" /> {t("选择图片", "Choose images")}
        </button>
      </section>

      {loading ? (
        <div className="page-centered" aria-busy="true">
          <LoaderCircle className="spin" aria-hidden="true" />
          <p>{t("正在整理照片…", "Organizing photos…")}</p>
        </div>
      ) : grouped.length ? (
        <div
          className={`meal-days layout-${dayColumns}`}
          style={
            {
              "--meal-image-max-height": `${imageMaxHeightPx}px`,
              "--meal-card-max-width": `${Math.round((imageMaxHeightPx * 4) / 3)}px`,
            } as CSSProperties
          }
        >
          {groupedDayColumns.map((dayGroup, columnIndex) => (
            <div
              className="meal-day-column"
              data-column={columnIndex + 1}
              key={`column-${columnIndex + 1}`}
            >
              {dayGroup.map(([date, dayPhotos]) => (
                <section className="meal-day" key={date}>
              <header>
                <div>
                  <h2>{date}</h2>
                  <span className="muted">
                    {language === "en"
                      ? `${dayPhotos.length} photo${dayPhotos.length === 1 ? "" : "s"}`
                      : `${dayPhotos.length} 张`}
                  </span>
                </div>
                <button
                  className="button secondary"
                  type="button"
                  disabled={
                    estimatingDate !== null ||
                    dayPhotos.every((photo) => photo.missing)
                  }
                  onClick={() => void estimateDay(date, dayPhotos)}
                >
                  {estimatingDate === date ? (
                    <LoaderCircle className="spin" aria-hidden="true" />
                  ) : (
                    <Sparkles aria-hidden="true" />
                  )}
                  {estimatingDate === date
                    ? t("估算中", "Estimating")
                    : t("AI 估算热量", "Estimate calories")}
                </button>
              </header>
              {estimatingDate === date && calorieProgress ? (
                <div className="meal-ai-progress" role="status">
                  <span>{calorieProgressText(calorieProgress)}</span>
                  {calorieProgress.reasoning ? (
                    <details>
                      <summary>{t("查看模型思考", "Show model reasoning")}</summary>
                      <pre>{calorieProgress.reasoning}</pre>
                    </details>
                  ) : null}
                </div>
              ) : null}
              <div
                className={
                  wrapEnabled
                    ? `meal-grid columns-${columns}`
                    : "meal-grid no-wrap"
                }
                data-count={dayPhotos.length}
              >
                {dayPhotos.map((photo) => {
                  const meta = CATEGORY_META[photo.category];
                  return (
                    <button
                      className="meal-card"
                      type="button"
                      key={photo.id}
                      onClick={() => setSelected(photo)}
                    >
                      {photo.assetUrl && !photo.missing ? (
                        <img
                          src={photo.assetUrl}
                          alt={
                            photo.caption ||
                            (language === "en"
                              ? `${meta.en} photo`
                              : `${meta.zh}图片`)
                          }
                          loading="lazy"
                          style={{ filter: imageFilter }}
                        />
                      ) : (
                        <span className="meal-missing">
                          <ImageOff aria-hidden="true" />{" "}
                          {t("图片缺失", "Image missing")}
                        </span>
                      )}
                      <span className="meal-card-overlay">
                        <strong>
                          {meta.icon} {language === "en" ? meta.en : meta.zh}
                        </strong>
                        {showCaptions && photo.caption && (
                          <small>{photo.caption}</small>
                        )}
                        {(photo.energyKj || photo.location) && (
                          <span className="meal-meta">
                            {photo.energyKj !== null && (
                              <small>
                                <Zap aria-hidden="true" />
                                {photo.energyKj} kJ
                              </small>
                            )}
                            {photo.location && (
                              <small>
                                <MapPin aria-hidden="true" />
                                {photo.location}
                              </small>
                            )}
                          </span>
                        )}
                      </span>
                    </button>
                  );
                })}
              </div>
                </section>
              ))}
            </div>
          ))}
        </div>
      ) : (
        <div className="empty-state">
          <Utensils aria-hidden="true" />
          <h2>
            {t(
              "这个范围还没有饮食照片",
              "No meal photos in this range",
            )}
          </h2>
          <p>
            {t(
              "调整日期和餐别筛选，或导入一张图片。",
              "Adjust the date and meal filters, or import an image.",
            )}
          </p>
        </div>
      )}

      {selected && (
        <div
          className="lightbox"
          role="dialog"
          aria-modal="true"
          aria-label={
            language === "en"
              ? `${CATEGORY_META[selected.category].en} image`
              : `${CATEGORY_META[selected.category].zh}图片`
          }
          onClick={() => setSelected(null)}
        >
          <button
            className="lightbox-close"
            type="button"
            onClick={() => setSelected(null)}
            aria-label={t("关闭", "Close")}
          >
            <X aria-hidden="true" />
          </button>
          <div className="lightbox-content" onClick={(event) => event.stopPropagation()}>
            {selected.assetUrl && !selected.missing ? (
              <img
                src={selected.assetUrl}
                alt={selected.caption || selected.fileName}
                style={{ filter: imageFilter }}
              />
            ) : (
              <div className="meal-missing">
                <ImageOff aria-hidden="true" />{" "}
                {t("图片缺失", "Image missing")}
              </div>
            )}
            <footer>
              <strong>
                {CATEGORY_META[selected.category].icon}{" "}
                {language === "en"
                  ? CATEGORY_META[selected.category].en
                  : CATEGORY_META[selected.category].zh}{" "}
                · {selected.date}
              </strong>
              {selected.caption && <p>{selected.caption}</p>}
              <div className="meal-meta">
                {selected.energyKj !== null && (
                  <span>
                    <Zap aria-hidden="true" /> {selected.energyKj} kJ
                  </span>
                )}
                {selected.location && (
                  <span>
                    <MapPin aria-hidden="true" /> {selected.location}
                  </span>
                )}
              </div>
            </footer>
          </div>
        </div>
      )}
    </main>
  );
}
