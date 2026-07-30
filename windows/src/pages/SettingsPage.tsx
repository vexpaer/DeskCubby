import {
  ArrowLeft,
  Check,
  Cloud,
  Home,
  Info,
  LayoutDashboard,
  LockKeyhole,
  RotateCcw,
  Save,
  Settings2,
  Timer,
  X,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";

import { UnsavedChangesGuard } from "../components";
import {
  invokeCommand,
  readableError,
  type DirectoryKind,
  type WindowsSettings,
} from "../lib/ipc";
import {
  AppDataSettings,
  AppearanceSettings,
  DiaryMediaSettings,
  PoetryMealSettings,
  ThoughtSettings,
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
  diaryDirectory: null,
  mediaDirectory: null,
  backupDirectory: null,
  fileNamePattern: "yyyy-MM-dd",
  markdownTemplate: "# {title}\n\n",
  imageNamePattern: "{date}_{category}_{seq}",
  imageMaxWidthPx: 720,
  imageMaxHeightPx: 640,
  mealImageCompressionEnabled: true,
  mealImageCompressionQuality: 80,
  photoLocationEnabled: false,
  thoughtDisplayMode: "SINGLE_LINE",
  thoughtHighlightColorArgb: 0xfff6e3a1 | 0,
  thoughtEditorMaxHeightPx: 168,
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
  ],
  "diary-media": [
    "diaryDirectory",
    "mediaDirectory",
    "fileNamePattern",
    "markdownTemplate",
    "imageNamePattern",
    "imageMaxWidthPx",
    "imageMaxHeightPx",
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
    imageMaxWidthPx: clampInteger(input.imageMaxWidthPx, 120, 2_400),
    imageMaxHeightPx: clampInteger(input.imageMaxHeightPx, 120, 2_400),
    mealImageCompressionQuality: clampInteger(
      input.mealImageCompressionQuality,
      30,
      95,
    ),
    thoughtEditorMaxHeightPx: clampInteger(input.thoughtEditorMaxHeightPx, 96, 400),
    mealCalendarImageMaxHeightPx: clampInteger(
      input.mealCalendarImageMaxHeightPx,
      80,
      320,
    ),
    themeSecondaryColorsArgb:
      input.themeSecondaryColorsArgb?.length >= 2
        ? input.themeSecondaryColorsArgb.slice(0, 5)
        : DEFAULT_SETTINGS.themeSecondaryColorsArgb,
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
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const language = draft.appLanguage === "ENGLISH" ? "en" : "zh";
  const errorLanguage = language === "en" ? "en" : "zh-CN";
  const tr = useCallback(
    (chinese: string, english: string) => (language === "zh" ? chinese : english),
    [language],
  );
  const dirty = useMemo(
    () => original !== null && JSON.stringify(original) !== JSON.stringify(draft),
    [draft, original],
  );
  const editableFields = EDITABLE_FIELDS[page as keyof typeof EDITABLE_FIELDS];
  const editable = Boolean(editableFields);

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
      setNotice(tr("设置已保存。", "Settings saved."));
    } catch (reason) {
      setError(readableError(reason, errorLanguage));
    } finally {
      setSaving(false);
    }
  }, [draft, errorLanguage, tr]);

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
    if (!editableFields) return;
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
        return <AppearanceSettings draft={draft} setDraft={setDraft} tr={tr} />;
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
        return (
          <SettingsInfoPage
            icon={Home}
            title={tr("主页设置", "Home settings")}
            description={tr(
              "Windows 首页已经使用与手机端相同的双语问候和核心模块。模块管理入口会在对应 IPC 完成后显示在这里。",
              "Windows Home already uses bilingual greetings and core widgets. Widget management will appear here when its IPC is available.",
            )}
          >
            <Link className="button button-secondary" to="/">
              {tr("打开首页", "Open Home")}
            </Link>
          </SettingsInfoPage>
        );
      case "vault":
        return (
          <SettingsInfoPage
            icon={LockKeyhole}
            title={tr("收藏夹安全", "Vault security")}
            description={tr(
              "密码设置、解锁与改密由收藏夹的受限 Rust 边界处理，不会进入普通设置或 Android JSON 备份。",
              "Password setup, unlocking and changes stay behind the Vault's restricted Rust boundary and never enter ordinary settings or Android JSON backups.",
            )}
          >
            <Link className="button button-secondary" to="/vault">
              {tr("打开收藏夹", "Open Vault")}
            </Link>
          </SettingsInfoPage>
        );
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
      case "navigation":
        return (
          <SettingsInfoPage
            icon={LayoutDashboard}
            title={tr("桌面导航", "Desktop navigation")}
            description={tr(
              "Windows 使用可折叠侧栏；窄窗口会自动切换为菜单，不会显示尚未实现功能的入口。",
              "Windows uses a collapsible sidebar that becomes a menu in narrow windows and hides unavailable features.",
            )}
          />
        );
      case "about":
        return (
          <div className="settings-about-grid">
            <SettingsInfoPage
              icon={Info}
              title="DeskCubby"
              description={tr(
                "本地优先的跨平台日记与个人记录应用。Windows 版本 0.2.0。",
                "A local-first cross-platform diary and personal records app. Windows version 0.2.0.",
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
