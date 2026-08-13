import {
  ArchiveRestore,
  ArrowLeft,
  Bot,
  Check,
  Cloud,
  HeartPulse,
  Info,
  Rss,
  RotateCcw,
  Save,
  Settings2,
  Timer,
  X,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";

import {
  cloneDesktopNavigationPreferences,
  createDefaultDesktopNavigationPreferences,
  normalizeDesktopNavigationPreferences,
  UnsavedChangesGuard,
} from "../components";
import {
  invokeCommand,
  readableError,
  type DirectoryKind,
  type WindowsSettings,
} from "../lib/ipc";
import { useAppStore } from "../store/appStore";
import { DesktopNavigationSettings } from "./settings/DesktopNavigationSettings";
import {
  AppDataSettings,
  AppearanceSettings,
  DiaryMediaSettings,
  HomeSettings,
  PoetryMealSettings,
  ThoughtSettings,
  VaultSettings,
} from "./settings/SettingsSections";
import {
  SettingsHome,
  SettingsInfoPage,
  SubpageSettingsHome,
} from "./settings/settingsNavigation";
import {
  destinationFor,
  pageForSettingsPath,
  parentForSettingsPage,
  pathForSettingsPage,
  type SettingsPageId,
} from "./settings/settingsRoutes";

const DEFAULT_SETTINGS: WindowsSettings = {
  visualStyle: "MATERIAL",
  darkMode: "SYSTEM",
  appLanguage: "CHINESE",
  themeColorArgb: 0xff42664d | 0,
  themeSecondaryColorsArgb: [0xffc96f4a | 0, 0xffd4a72c | 0, 0xff527f91 | 0],
  fontScale: 1,
  compactMode: false,
  backgroundImagePath: null,
  backgroundImageOpacity: 0.45,
  backgroundImageBlurPx: 0,
  tutorialModeEnabled: true,
  diaryDirectory: null,
  mediaDirectory: null,
  backupDirectory: null,
  fileNamePattern: "yyyy-MM-dd",
  markdownTemplate: "# {title}\n\n",
  imageNamePattern: "{date}_{category}_{seq}",
  imageMaxWidthPx: 720,
  imageMaxHeightPx: 640,
  markdownHeadingSizesSp: [32, 28, 24, 21, 19, 17],
  mealImageCompressionEnabled: true,
  mealImageCompressionQuality: 80,
  photoLocationEnabled: false,
  thoughtDisplayMode: "SINGLE_LINE",
  thoughtHighlightColorArgb: 0xfff6e3a1 | 0,
  thoughtEditorMaxHeightPx: 168,
  vaultRowHeightDp: 56,
  poetryFontSizePx: 18,
  poetryLineSpacing: 1.45,
  poetryTextAlignment: "START",
  poetryShowSource: true,
  poetryShowQuoteMark: true,
  poetrySevenCharacterWrapEnabled: false,
  mealCalendarImageMaxHeightPx: 124,
  mealCalendarShowCaptions: true,
  mealCalendarWrapEnabled: false,
  mealCalendarPhotosPerRow: "SMART",
  mealButtonsUseIcons: false,
  mealButtonIcons: ["🥪", "🍱", "🍹", "🍜", "🍊", "🍤"],
  userName: "",
  homeGreetings: [
    { chinese: "今天也要好好生活，{name}", english: "Make today count, {name}" },
  ],
  homeWidgetBordersEnabled: true,
  homeWidgets: [
    "today",
    "poem",
    "quick_input",
    "meal_photos",
    "year_progress",
    "notes",
    "game_shortcuts",
    "record_overview",
  ],
  homeGameShortcuts: ["2048", "snake", "minesweeper"],
  homeWidgetTitles: [
    "calendar",
    "weather",
    "poem",
    "today",
    "streak",
    "month_diaries",
    "total_words",
    "recent_diary",
    "recent_thought",
    "date_records",
    "quick_input",
    "daily_records",
    "meal_photos",
    "random_diary",
    "year_progress",
    "website",
    "notes",
    "game_shortcuts",
    "record_overview",
  ],
};

const EDITABLE_FIELDS = {
  appearance: [
    "visualStyle",
    "darkMode",
    "appLanguage",
    "themeColorArgb",
    "themeSecondaryColorsArgb",
    "fontScale",
    "compactMode",
    "tutorialModeEnabled",
    "backgroundImagePath",
    "backgroundImageOpacity",
    "backgroundImageBlurPx",
  ],
  home: [
    "userName",
    "homeGreetings",
    "homeWidgetBordersEnabled",
    "homeWidgets",
    "homeGameShortcuts",
    "homeWidgetTitles",
    "mealButtonsUseIcons",
    "mealButtonIcons",
  ],
  "diary-media": [
    "diaryDirectory",
    "mediaDirectory",
    "fileNamePattern",
    "markdownTemplate",
    "imageNamePattern",
    "imageMaxWidthPx",
    "imageMaxHeightPx",
    "markdownHeadingSizesSp",
    "mealImageCompressionEnabled",
    "mealImageCompressionQuality",
    "photoLocationEnabled",
  ],
  thoughts: [
    "thoughtDisplayMode",
    "thoughtHighlightColorArgb",
    "thoughtEditorMaxHeightPx",
  ],
  "poetry-meals": [
    "poetryFontSizePx",
    "poetryLineSpacing",
    "poetryTextAlignment",
    "poetryShowSource",
    "poetryShowQuoteMark",
    "poetrySevenCharacterWrapEnabled",
    "mealCalendarImageMaxHeightPx",
    "mealCalendarShowCaptions",
    "mealCalendarWrapEnabled",
    "mealCalendarPhotosPerRow",
  ],
  "app-data": ["backupDirectory"],
  vault: ["vaultRowHeightDp"],
} as const satisfies Partial<
  Record<SettingsPageId, readonly (keyof WindowsSettings)[]>
>;

function clampInteger(value: number, minimum: number, maximum: number): number {
  if (!Number.isFinite(value)) return minimum;
  return Math.min(maximum, Math.max(minimum, Math.round(value)));
}

function normalizeSettings(input: WindowsSettings): WindowsSettings {
  return {
    ...DEFAULT_SETTINGS,
    ...input,
    backgroundImageOpacity: Math.min(1, Math.max(0, input.backgroundImageOpacity)),
    backgroundImageBlurPx: Math.min(40, Math.max(0, input.backgroundImageBlurPx)),
    imageMaxWidthPx: clampInteger(input.imageMaxWidthPx, 120, 2_400),
    imageMaxHeightPx: clampInteger(input.imageMaxHeightPx, 120, 2_400),
    mealImageCompressionQuality: clampInteger(
      input.mealImageCompressionQuality,
      30,
      95,
    ),
    thoughtEditorMaxHeightPx: clampInteger(input.thoughtEditorMaxHeightPx, 96, 400),
    vaultRowHeightDp: clampInteger(input.vaultRowHeightDp, 48, 120),
    mealCalendarImageMaxHeightPx: clampInteger(
      input.mealCalendarImageMaxHeightPx,
      80,
      320,
    ),
    themeSecondaryColorsArgb:
      input.themeSecondaryColorsArgb?.length >= 2
        ? input.themeSecondaryColorsArgb.slice(0, 5)
        : DEFAULT_SETTINGS.themeSecondaryColorsArgb,
    markdownHeadingSizesSp:
      input.markdownHeadingSizesSp?.length === 6
        ? input.markdownHeadingSizesSp.map((size) => clampInteger(size, 12, 48))
        : DEFAULT_SETTINGS.markdownHeadingSizesSp,
  };
}

function argbToHex(argb: number): string {
  return `#${((argb >>> 0) & 0xffffff).toString(16).padStart(6, "0")}`;
}

function applyAppearance(settings: WindowsSettings) {
  const root = document.documentElement;
  const visualTheme = {
    MATERIAL: "material",
    LIQUID_GLASS: "liquid-glass",
    ORGANIC_FUTURE: "organic-future",
  } as const;
  const systemDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
  const colorScheme =
    settings.darkMode === "SYSTEM"
      ? systemDark
        ? "dark"
        : "light"
      : settings.darkMode.toLowerCase();
  root.lang = settings.appLanguage === "ENGLISH" ? "en" : "zh-CN";
  root.dataset.visualTheme = visualTheme[settings.visualStyle];
  root.dataset.colorScheme = colorScheme;
  root.dataset.compact = String(settings.compactMode);
  root.style.setProperty("--font-scale", String(settings.fontScale));
  root.style.setProperty("--user-primary", argbToHex(settings.themeColorArgb));
  settings.themeSecondaryColorsArgb.forEach((color, index) => {
    root.style.setProperty(`--user-secondary-${index + 1}`, argbToHex(color));
  });
}

function copyFields<T extends object>(
  target: T,
  source: T,
  keys: readonly (keyof T)[],
): T {
  const next = { ...target };
  keys.forEach((key) => {
    next[key] = source[key];
  });
  return next;
}

function pageTitle(page: SettingsPageId, tr: (zh: string, en: string) => string) {
  if (page === "main") return tr("设置", "Settings");
  return destinationFor(page)
    ? tr(destinationFor(page)!.chinese, destinationFor(page)!.english)
    : tr("设置", "Settings");
}

function pageEyebrow(page: SettingsPageId, tr: (zh: string, en: string) => string) {
  if (page === "main") return tr("本地优先 · Windows", "Local first · Windows");
  if (parentForSettingsPage(page) === "subpages") {
    return tr("子页面设置", "Subpage settings");
  }
  if (parentForSettingsPage(page) === "app-data") {
    return tr("应用数据", "App data");
  }
  return tr("设置", "Settings");
}

export default function SettingsPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const page = pageForSettingsPath(location.pathname);
  const [original, setOriginal] = useState<WindowsSettings | null>(null);
  const [draft, setDraft] = useState<WindowsSettings>(DEFAULT_SETTINGS);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [choosingDirectory, setChoosingDirectory] = useState<DirectoryKind | null>(null);
  const [choosingBackground, setChoosingBackground] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const setDesktopNavigation = useAppStore(
    (state) => state.setDesktopNavigation,
  );
  const [originalNavigation, setOriginalNavigation] = useState(() =>
    cloneDesktopNavigationPreferences(useAppStore.getState().desktopNavigation),
  );
  const [navigationDraft, setNavigationDraft] = useState(() =>
    cloneDesktopNavigationPreferences(useAppStore.getState().desktopNavigation),
  );

  const language = draft.appLanguage === "ENGLISH" ? "en" : "zh";
  const errorLanguage = language === "en" ? "en" : "zh-CN";
  const tr = useCallback(
    (chinese: string, english: string) => (language === "zh" ? chinese : english),
    [language],
  );
  const settingsDirty = useMemo(
    () => original !== null && JSON.stringify(original) !== JSON.stringify(draft),
    [draft, original],
  );
  const navigationDirty = useMemo(
    () => JSON.stringify(originalNavigation) !== JSON.stringify(navigationDraft),
    [navigationDraft, originalNavigation],
  );
  const dirty = settingsDirty || navigationDirty;
  const editableFields = EDITABLE_FIELDS[page as keyof typeof EDITABLE_FIELDS];
  const editable = Boolean(editableFields) || page === "navigation";

  useEffect(() => {
    let active = true;
    const load = async () => {
      setLoading(true);
      try {
        const settings = normalizeSettings(
          await invokeCommand<WindowsSettings>("get_windows_settings"),
        );
        if (!active) return;
        setOriginal(settings);
        setDraft(settings);
        applyAppearance(settings);
        window.dispatchEvent(
          new CustomEvent("deskcubby:settings-changed", { detail: settings }),
        );
      } catch (reason) {
        if (!active) return;
        setError(
          readableError(
            reason,
            document.documentElement.lang === "en" ? "en" : "zh-CN",
          ),
        );
      } finally {
        if (active) setLoading(false);
      }
    };
    void load();
    return () => {
      active = false;
    };
  }, []);

  const openPage = useCallback(
    (nextPage: SettingsPageId) => {
      navigate(pathForSettingsPage(nextPage));
    },
    [navigate],
  );

  const persistSettings = useCallback(async () => {
    setSaving(true);
    setError("");
    setNotice("");
    try {
      if (settingsDirty) {
        const saved = normalizeSettings(
          await invokeCommand<WindowsSettings>("update_windows_settings", {
            settings: draft,
          }),
        );
        setOriginal(saved);
        setDraft(saved);
        applyAppearance(saved);
        window.dispatchEvent(
          new CustomEvent("deskcubby:settings-changed", { detail: saved }),
        );
      }
      if (navigationDirty) {
        const savedNavigation = normalizeDesktopNavigationPreferences(
          navigationDraft,
        );
        setDesktopNavigation(savedNavigation);
        setOriginalNavigation(
          cloneDesktopNavigationPreferences(savedNavigation),
        );
        setNavigationDraft(cloneDesktopNavigationPreferences(savedNavigation));
      }
      setNotice(tr("设置已保存。", "Settings saved."));
    } catch (reason) {
      setError(readableError(reason, errorLanguage));
    } finally {
      setSaving(false);
    }
  }, [
    draft,
    errorLanguage,
    navigationDraft,
    navigationDirty,
    setDesktopNavigation,
    settingsDirty,
    tr,
  ]);

  useEffect(() => {
    const saveShortcut = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
        event.preventDefault();
        if (editable && dirty && !saving) void persistSettings();
      }
    };
    window.addEventListener("keydown", saveShortcut);
    return () => window.removeEventListener("keydown", saveShortcut);
  }, [dirty, editable, persistSettings, saving]);

  const restoreDefaults = async () => {
    if (!editableFields && page !== "navigation") return;
    if (
      !window.confirm(
        tr(
          "把本页设置恢复为默认草稿？仍需点击保存。",
          "Restore defaults for this page? You still need to save.",
        ),
      )
    ) {
      return;
    }
    if (page === "navigation") {
      setNavigationDraft(createDefaultDesktopNavigationPreferences());
      setNotice(
        tr(
          "本页默认值已放入草稿，点击保存后生效。",
          "Defaults for this page are in the draft; save to apply.",
        ),
      );
      return;
    }
    if (!editableFields) return;
    let defaults = DEFAULT_SETTINGS;
    try {
      defaults = normalizeSettings(
        await invokeCommand<WindowsSettings>("get_default_windows_settings"),
      );
    } catch {
      // Compiled defaults keep the action useful with an older backend.
    }
    setDraft((current) => copyFields(current, defaults, editableFields));
    setNotice(
      tr(
        "本页默认值已放入草稿，点击保存后生效。",
        "Defaults for this page are in the draft; save to apply.",
      ),
    );
  };

  const chooseDirectory = async (kind: DirectoryKind) => {
    setChoosingDirectory(kind);
    setError("");
    try {
      const currentPath =
        kind === "diary"
          ? draft.diaryDirectory
          : kind === "media"
            ? draft.mediaDirectory
            : draft.backupDirectory;
      const selected = await invokeCommand<string | null>("select_directory", {
        kind,
        currentPath,
      });
      if (selected) {
        setDraft((current) => ({
          ...current,
          diaryDirectory: kind === "diary" ? selected : current.diaryDirectory,
          mediaDirectory: kind === "media" ? selected : current.mediaDirectory,
          backupDirectory: kind === "backup" ? selected : current.backupDirectory,
        }));
      }
    } catch (reason) {
      setError(readableError(reason, errorLanguage));
    } finally {
      setChoosingDirectory(null);
    }
  };

  const chooseBackground = async () => {
    setChoosingBackground(true);
    setError("");
    try {
      const selected = await invokeCommand<string | null>("select_background_image", {
        currentPath: draft.backgroundImagePath,
      });
      if (selected) {
        setDraft((current) => ({ ...current, backgroundImagePath: selected }));
      }
    } catch (reason) {
      setError(readableError(reason, errorLanguage));
    } finally {
      setChoosingBackground(false);
    }
  };

  if (loading) {
    return (
      <main className="page-shell settings-page">
        <div className="panel empty-state" role="status">
          <Settings2 size={26} aria-hidden="true" />
          <p>正在读取设置… / Loading settings…</p>
        </div>
      </main>
    );
  }

  const renderPage = () => {
    switch (page) {
      case "main":
        return <SettingsHome settings={draft} tr={tr} onOpen={openPage} />;
      case "subpages":
        return <SubpageSettingsHome settings={draft} tr={tr} onOpen={openPage} />;
      case "appearance":
        return (
          <AppearanceSettings
            draft={draft}
            setDraft={setDraft}
            tr={tr}
            choosingBackground={choosingBackground}
            onChooseBackground={() => void chooseBackground()}
            onClearBackground={() =>
              setDraft((current) => ({ ...current, backgroundImagePath: null }))
            }
          />
        );
      case "diary-media":
        return (
          <DiaryMediaSettings
            draft={draft}
            setDraft={setDraft}
            choosingDirectory={choosingDirectory}
            tr={tr}
            onChooseDirectory={(kind) => void chooseDirectory(kind)}
          />
        );
      case "thoughts":
        return <ThoughtSettings draft={draft} setDraft={setDraft} tr={tr} />;
      case "poetry-meals":
        return <PoetryMealSettings draft={draft} setDraft={setDraft} tr={tr} />;
      case "app-data":
        return (
          <AppDataSettings
            draft={draft}
            choosingDirectory={choosingDirectory}
            tr={tr}
            onChooseDirectory={(kind) => void chooseDirectory(kind)}
            onOpen={openPage}
          />
        );
      case "home":
        return <HomeSettings draft={draft} setDraft={setDraft} tr={tr} />;
      case "vault":
        return <VaultSettings draft={draft} setDraft={setDraft} tr={tr} />;
      case "mobile-usage":
        return (
          <SettingsInfoPage
            icon={Timer}
            title={tr("手机使用时间", "Phone screen time")}
            description={tr(
              "Windows 端只显示从手机导入或同步的统计，不采集这台电脑的应用使用时间。",
              "Windows only displays statistics imported or synced from a phone and does not track app usage on this PC.",
            )}
          >
            <Link className="button button-secondary" to="/usage">
              {tr("查看手机数据", "View phone data")}
            </Link>
          </SettingsInfoPage>
        );
      case "rss":
        return (
          <SettingsInfoPage
            icon={Rss}
            title={tr("RSS 订阅", "RSS")}
            description={tr(
              "订阅地址、每个订阅的文章上限和摘要显示均在 RSS 页面管理；只接受经过限制的公网 HTTPS 来源。",
              "Feed URLs, per-feed item limits and summaries are managed on the RSS page, which accepts only constrained public HTTPS sources.",
            )}
          >
            <Link className="button button-secondary" to="/rss">
              {tr("打开 RSS 设置", "Open RSS settings")}
            </Link>
          </SettingsInfoPage>
        );
      case "ai":
        return (
          <SettingsInfoPage
            icon={Bot}
            title={tr("AI 配置", "AI configurations")}
            description={tr(
              "在独立 AI 设置页管理兼容端点、模型、系统提示词和 API Key。所有改动先保留为草稿，保存后才生效。",
              "Manage compatible endpoints, models, system prompts and API keys on the dedicated AI settings page. Changes remain drafts until saved.",
            )}
          >
            <Link className="button button-secondary" to="/settings/ai">
              {tr("打开 AI 配置", "Open AI configurations")}
            </Link>
          </SettingsInfoPage>
        );
      case "health":
        return (
          <SettingsInfoPage
            icon={HeartPulse}
            title={tr("健康", "Health")}
            description={tr(
              "Windows 只显示你明确导入或链接的 Android 健康统计，不申请健康或活动权限，也不采集这台电脑的数据。",
              "Windows only displays Android health statistics you explicitly import or link. It requests no health or activity permissions and collects nothing from this PC.",
            )}
          >
            <Link className="button button-secondary" to="/health">
              {tr("查看健康数据", "View health data")}
            </Link>
          </SettingsInfoPage>
        );
      case "cloud":
        return (
          <SettingsInfoPage
            icon={Cloud}
            title={tr("WebDAV / S3 云同步", "WebDAV / S3 cloud sync")}
            description={tr(
              "服务端点、同步方向和内容会在此管理；密码和访问密钥只交给 Rust，并使用 Windows 加密保护。",
              "Service endpoints, direction and content are managed here. Passwords and access keys are sent only to Rust and protected with Windows encryption.",
            )}
          />
        );
      case "data-usage":
        return (
          <SettingsInfoPage
            icon={ArchiveRestore}
            title={tr("数据占用", "Storage usage")}
            description={tr(
              "日记和媒体保留在你选择的目录；应用数据库、缩略图与只读手机数据缓存保留在 Windows 本机应用目录。Windows 不会把本机路径写回 Android 备份。",
              "Diary and media remain in your selected folders. The app database, thumbnails and read-only phone-data cache remain in Windows local app data. Windows paths are never written back to Android backups.",
            )}
          >
            <Link className="button button-secondary" to="/backup">
              {tr("打开备份管理", "Open backup manager")}
            </Link>
          </SettingsInfoPage>
        );
      case "navigation":
        return (
          <DesktopNavigationSettings
            draft={navigationDraft}
            setDraft={setNavigationDraft}
            language={language === "en" ? "en" : "zh-CN"}
            tr={tr}
          />
        );
      case "about":
        return (
          <div className="settings-about-grid">
            <SettingsInfoPage
              icon={Info}
              title="DeskCubby"
              description={tr(
                "本地优先的跨平台日记与个人记录应用。Windows 版本 0.7.0。",
                "A local-first cross-platform diary and personal records app. Windows version 0.7.0.",
              )}
            >
              <button
                className="button button-secondary"
                type="button"
                onClick={() => openPage("updates")}
              >
                {tr("检查更新", "Check for updates")}
              </button>
            </SettingsInfoPage>
            <aside className="panel compatibility-note">
              <h2>{tr("应用教学", "App tutorial")}</h2>
              <p>{tr(
                "完整使用说明保留在项目根目录的 TUTORIAL.md。",
                "The complete guide remains in TUTORIAL.md at the project root.",
              )}</p>
            </aside>
          </div>
        );
      case "updates":
        return (
          <SettingsInfoPage
            icon={Info}
            title={tr("更新与签名", "Updates & signing")}
            description={tr(
              "更新检查、下载和安装状态会在此显示。签名属于发布流程，应用不会把私钥或证书密码保存为普通设置。",
              "Update checks, downloads and installation status appear here. Signing belongs to the release process; private keys and certificate passwords are never stored as ordinary settings.",
            )}
          />
        );
    }
  };

  return (
    <main className="page-shell settings-page" aria-labelledby="settings-title">
      <UnsavedChangesGuard
        when={dirty}
        scope="settings"
        onDiscard={() => {
          if (original) setDraft(original);
          setNavigationDraft(
            cloneDesktopNavigationPreferences(originalNavigation),
          );
        }}
      />
      <header className="page-header sticky-page-header settings-page-header">
        <div className="settings-heading-row">
          {page !== "main" ? (
            <button
              className="icon-button settings-back-button"
              type="button"
              aria-label={tr("返回上一级设置", "Back to parent settings")}
              onClick={() => openPage(parentForSettingsPage(page))}
            >
              <ArrowLeft size={19} />
            </button>
          ) : null}
          <div>
            <p className="eyebrow">{pageEyebrow(page, tr)}</p>
            <h1 id="settings-title">{pageTitle(page, tr)}</h1>
          </div>
        </div>
        {editable ? (
          <div className="page-actions">
            <button
              className="button-secondary"
              type="button"
              onClick={() => void restoreDefaults()}
            >
              <RotateCcw size={17} />
              {tr("恢复默认", "Restore defaults")}
            </button>
            <button
              className="button-primary"
              type="button"
              disabled={!dirty || saving}
              onClick={() => void persistSettings()}
            >
              {dirty ? <Save size={17} /> : <Check size={17} />}
              {saving
                ? tr("保存中…", "Saving…")
                : dirty
                  ? tr("保存", "Save")
                  : tr("已保存", "Saved")}
            </button>
          </div>
        ) : null}
      </header>

      {dirty && (
        <div className="status-banner warning" role="status">
          {tr(
            "本页有未保存的修改。离开页面或关闭窗口前会询问你。",
            "This page has unsaved changes. You will be asked before leaving.",
          )}
        </div>
      )}
      {(error || notice) && (
        <div
          className={error ? "status-banner error" : "status-banner success"}
          role={error ? "alert" : "status"}
        >
          <span>{error || notice}</span>
          <button
            className="icon-button"
            type="button"
            aria-label={tr("关闭提示", "Dismiss")}
            onClick={() => {
              setError("");
              setNotice("");
            }}
          >
            <X size={16} />
          </button>
        </div>
      )}

      <div className="settings-page-content">{renderPage()}</div>
    </main>
  );
}
