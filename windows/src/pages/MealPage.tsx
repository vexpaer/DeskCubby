import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Download,
  ImageOff,
  ImagePlus,
  LoaderCircle,
  MapPin,
  RefreshCw,
  SlidersHorizontal,
  Utensils,
  X,
  Zap,
} from "lucide-react";
import {
  mealApi,
  readableError,
  tr,
  type MealCategory,
  type MealColumns,
  type MealPhoto,
  type MealQuery,
} from "../lib/ipc";
import { useAppStore } from "../store/appStore";

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
  const [columns, setColumns] = useState<MealColumns>("smart");
  const [showCaptions, setShowCaptions] = useState(true);
  const [brightness, setBrightness] = useState(100);
  const [contrast, setContrast] = useState(100);
  const [saturation, setSaturation] = useState(100);
  const [warmth, setWarmth] = useState(0);
  const [tint, setTint] = useState(0);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [selected, setSelected] = useState<MealPhoto | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [importDate, setImportDate] = useState(dateToday());
  const [importCategory, setImportCategory] =
    useState<MealCategory>("breakfast");

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

  const imageFilter = filterCss(
    brightness,
    contrast,
    saturation,
    warmth,
    tint,
  );

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
              filtersOpen ? "button secondary selected" : "button secondary"
            }
            type="button"
            onClick={() => setFiltersOpen((value) => !value)}
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
            disabled={!photos.length || busy}
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
            aria-label={t("每行图片", "Images per row")}
          >
            {(["smart", 2, 3] as MealColumns[]).map((value) => (
              <button
                type="button"
                key={value}
                className={columns === value ? "selected" : undefined}
                onClick={() => setColumns(value)}
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
              checked={showCaptions}
              onChange={(event) => setShowCaptions(event.target.checked)}
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
          {[
            [t("亮度", "Brightness"), brightness, setBrightness, 50, 150],
            [t("对比度", "Contrast"), contrast, setContrast, 50, 150],
            [t("饱和度", "Saturation"), saturation, setSaturation, 0, 200],
            [t("色温", "Warmth"), warmth, setWarmth, -100, 100],
            [t("色调", "Tint"), tint, setTint, -100, 100],
          ].map(([label, value, setter, min, max]) => (
            <label className="range-field" key={label as string}>
              <span>
                {label as string}
                <output>{value as number}</output>
              </span>
              <input
                type="range"
                min={min as number}
                max={max as number}
                value={value as number}
                onChange={(event) =>
                  (
                    setter as React.Dispatch<React.SetStateAction<number>>
                  )(Number(event.target.value))
                }
              />
            </label>
          ))}
          <button
            className="button secondary"
            type="button"
            onClick={() => {
              setBrightness(100);
              setContrast(100);
              setSaturation(100);
              setWarmth(0);
              setTint(0);
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
        <div className="meal-days">
          {grouped.map(([date, dayPhotos]) => (
            <section className="meal-day" key={date}>
              <header>
                <h2>{date}</h2>
                <span className="muted">
                  {language === "en"
                    ? `${dayPhotos.length} photo${dayPhotos.length === 1 ? "" : "s"}`
                    : `${dayPhotos.length} 张`}
                </span>
              </header>
              <div
                className={`meal-grid columns-${columns}`}
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
