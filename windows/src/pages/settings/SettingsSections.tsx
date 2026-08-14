import {
  ArchiveRestore,
  BookOpenText,
  Cloud,
  FolderOpen,
  Gamepad2,
  Home,
  Image,
  Languages,
  Plus,
  ShieldCheck,
  Sparkles,
  Trash2,
} from "lucide-react";
import { Link } from "react-router-dom";

import type {
  DirectoryKind,
  MealPhotosPerRow,
  PoetryTextAlignment,
  ThoughtDisplayMode,
  WindowsAppLanguage,
  WindowsDarkMode,
  WindowsSettings,
  WindowsVisualStyle,
} from "../../lib/ipc";
import type { SettingsPageId, SettingsTranslator } from "./settingsRoutes";

function argbToHex(argb: number): string {
  return `#${((argb >>> 0) & 0xffffff).toString(16).padStart(6, "0")}`;
}

function hexToArgb(hex: string): number {
  return (Number.parseInt(`ff${hex.replace("#", "")}`, 16) | 0) as number;
}

type SetDraft = React.Dispatch<React.SetStateAction<WindowsSettings>>;

const HOME_WIDGET_OPTIONS = [
  ["today", "今天", "Today"],
  ["poem", "每日诗词", "Daily poem"],
  ["quick_input", "快速输入", "Quick capture"],
  ["meal_photos", "饮食图片", "Meal photos"],
  ["year_progress", "年度进度", "Year progress"],
  ["notes", "笔记入口", "Notes shortcut"],
  ["game_shortcuts", "小游戏快捷入口", "Game shortcuts"],
  ["record_overview", "记录概览", "Record overview"],
  ["calendar", "日历", "Calendar"],
  ["weather", "天气", "Weather"],
  ["streak", "连续记录", "Streak"],
  ["month_diaries", "本月日记", "Monthly diaries"],
  ["total_words", "总字数", "Total words"],
  ["recent_diary", "最近日记", "Recent diary"],
  ["recent_thought", "最近小巧思", "Recent thought"],
  ["date_records", "日期记录", "Date records"],
  ["daily_records", "日常记录", "Daily records"],
  ["random_diary", "随机日记", "Random diary"],
] as const;

const HOME_GAME_OPTIONS = [
  ["2048", "2048 · 4×4"],
  ["2048_5", "2048 · 5×5"],
  ["2048_6", "2048 · 6×6"],
  ["snake", "贪吃蛇 / Snake"],
  ["tetris", "俄罗斯方块 / Tetris"],
  ["minesweeper", "扫雷 / Minesweeper"],
  ["spider", "蜘蛛纸牌 / Spider Solitaire"],
] as const;


function toggleCatalogItem(
  current: readonly string[],
  id: string,
  checked: boolean,
  catalog: readonly (readonly [string, ...unknown[]])[],
): string[] {
  const selected = new Set(current);
  if (checked) selected.add(id);
  else selected.delete(id);
  return catalog.map(([itemId]) => itemId).filter((itemId) => selected.has(itemId));
}

function SettingsSectionHeading({
  icon: Icon,
  title,
  description,
}: {
  icon: typeof BookOpenText;
  title: string;
  description: string;
}) {
  return (
    <div className="settings-section-heading">
      <Icon size={20} aria-hidden="true" />
      <div>
        <h2>{title}</h2>
        <p>{description}</p>
      </div>
    </div>
  );
}

function DirectoryRows({
  rows,
  choosingDirectory,
  tr,
  onChoose,
}: {
  rows: ReadonlyArray<readonly [DirectoryKind, string, string | null]>;
  choosingDirectory: DirectoryKind | null;
  tr: SettingsTranslator;
  onChoose: (kind: DirectoryKind) => void;
}) {
  return (
    <div className="directory-list">
      {rows.map(([kind, label, path]) => (
        <div className="directory-row" key={kind}>
          <div>
            <strong>{label}</strong>
            <code title={path ?? undefined}>{path || tr("尚未选择", "Not selected")}</code>
          </div>
          <button
            className="button-secondary"
            type="button"
            disabled={choosingDirectory !== null}
            onClick={() => onChoose(kind)}
          >
            <FolderOpen size={17} />
            {choosingDirectory === kind
              ? tr("选择中…", "Choosing…")
              : tr("选择文件夹", "Choose folder")}
          </button>
        </div>
      ))}
    </div>
  );
}

export function AppearanceSettings({
  draft,
  setDraft,
  tr,
  choosingBackground,
  onChooseBackground,
  onClearBackground,
}: {
  draft: WindowsSettings;
  setDraft: SetDraft;
  tr: SettingsTranslator;
  choosingBackground: boolean;
  onChooseBackground: () => void;
  onClearBackground: () => void;
}) {
  const setSecondaryColor = (index: number, value: string) => {
    setDraft((current) => {
      const colors = [...current.themeSecondaryColorsArgb];
      colors[index] = hexToArgb(value);
      return { ...current, themeSecondaryColorsArgb: colors };
    });
  };
  return (
    <section className="panel settings-section">
      <SettingsSectionHeading
        icon={Languages}
        title={tr("外观与语言", "Appearance & language")}
        description={tr("保存后同步应用到全部页面。", "Saved appearance applies across the app.")}
      />
      <div className="form-grid">
        <label>
          <span>{tr("界面语言", "Language")}</span>
          <select
            value={draft.appLanguage}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                appLanguage: event.target.value as WindowsAppLanguage,
              }))
            }
          >
            <option value="CHINESE">简体中文</option>
            <option value="ENGLISH">English</option>
          </select>
        </label>
        <label>
          <span>{tr("视觉风格", "Visual style")}</span>
          <select
            value={draft.visualStyle}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                visualStyle: event.target.value as WindowsVisualStyle,
              }))
            }
          >
            <option value="MATERIAL">Material</option>
            <option value="LIQUID_GLASS">Liquid Glass</option>
            <option value="ORGANIC_FUTURE">Organic Future</option>
          </select>
        </label>
        <label>
          <span>{tr("明暗模式", "Color mode")}</span>
          <select
            value={draft.darkMode}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                darkMode: event.target.value as WindowsDarkMode,
              }))
            }
          >
            <option value="SYSTEM">{tr("跟随系统", "System")}</option>
            <option value="LIGHT">{tr("浅色", "Light")}</option>
            <option value="DARK">{tr("深色", "Dark")}</option>
          </select>
        </label>
        <label>
          <span>
            {tr("字号缩放", "Font scale")} · {Math.round(draft.fontScale * 100)}%
          </span>
          <input
            type="range"
            min="0.8"
            max="1.3"
            step="0.05"
            value={draft.fontScale}
            onChange={(event) =>
              setDraft((current) => ({ ...current, fontScale: Number(event.target.value) }))
            }
          />
        </label>
        <label>
          <span>{tr("主色", "Primary color")}</span>
          <input
            type="color"
            value={argbToHex(draft.themeColorArgb)}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                themeColorArgb: hexToArgb(event.target.value),
              }))
            }
          />
        </label>
        <fieldset className="color-fieldset">
          <legend>{tr("副色", "Secondary colors")}</legend>
          <div className="color-row">
            {draft.themeSecondaryColorsArgb.map((color, index) => (
              <input
                aria-label={tr(`副色 ${index + 1}`, `Secondary color ${index + 1}`)}
                key={index}
                type="color"
                value={argbToHex(color)}
                onChange={(event) => setSecondaryColor(index, event.target.value)}
              />
            ))}
          </div>
        </fieldset>
        <label className="check-control">
          <input
            type="checkbox"
            checked={draft.compactMode}
            onChange={(event) =>
              setDraft((current) => ({ ...current, compactMode: event.target.checked }))
            }
          />
          {tr("紧凑布局", "Compact layout")}
        </label>
        <label className="check-control">
          <input
            type="checkbox"
            checked={draft.tutorialModeEnabled}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                tutorialModeEnabled: event.target.checked,
              }))
            }
          />
          {tr("启用应用教学", "Enable app tutorial")}
        </label>
        <p className="field-help form-span">
          {tr(
            "控制首次进入各页面时的教学提示；已确认过哪些页面仍只保存在本机。",
            "Controls first-visit guidance on each page; completed-page confirmations remain local to this PC.",
          )}
        </p>
        <div className="form-span directory-row settings-background-row">
          <div>
            <strong>{tr("全局背景图片", "Global background image")}</strong>
            <code title={draft.backgroundImagePath ?? undefined}>
              {draft.backgroundImagePath ?? tr("尚未选择", "Not selected")}
            </code>
          </div>
          <div className="inline-actions">
            <button
              className="button-secondary"
              type="button"
              disabled={choosingBackground}
              onClick={onChooseBackground}
            >
              <Image size={17} />
              {choosingBackground
                ? tr("选择中…", "Choosing…")
                : tr("选择图片", "Choose image")}
            </button>
            {draft.backgroundImagePath ? (
              <button
                className="button-secondary"
                type="button"
                onClick={onClearBackground}
              >
                {tr("清除", "Clear")}
              </button>
            ) : null}
          </div>
        </div>
        <label>
          <span>
            {tr("背景可见度", "Background visibility")} · {Math.round(draft.backgroundImageOpacity * 100)}%
          </span>
          <input
            type="range"
            min="0"
            max="1"
            step="0.05"
            disabled={!draft.backgroundImagePath}
            value={draft.backgroundImageOpacity}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                backgroundImageOpacity: Number(event.target.value),
              }))
            }
          />
        </label>
        <label>
          <span>
            {tr("背景模糊", "Background blur")} · {Math.round(draft.backgroundImageBlurPx)}px
          </span>
          <input
            type="range"
            min="0"
            max="40"
            step="1"
            disabled={!draft.backgroundImagePath}
            value={draft.backgroundImageBlurPx}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                backgroundImageBlurPx: Number(event.target.value),
              }))
            }
          />
        </label>
      </div>
    </section>
  );
}

export function HomeSettings({
  draft,
  setDraft,
  tr,
}: {
  draft: WindowsSettings;
  setDraft: SetDraft;
  tr: SettingsTranslator;
}) {
  return (
    <>
      <section className="panel settings-section">
        <SettingsSectionHeading
          icon={Home}
          title={tr("主页问候", "Home greeting")}
          description={tr(
            "{name} 会替换为用户名；中文和英文随界面语言切换。",
            "{name} is replaced with the user name; each language uses its matching text.",
          )}
        />
        <div className="form-grid">
          <label className="form-span">
            <span>{tr("用户名", "User name")}</span>
            <input
              maxLength={80}
              value={draft.userName}
              onChange={(event) =>
                setDraft((current) => ({ ...current, userName: event.target.value }))
              }
            />
          </label>
          <div className="form-span settings-repeater">
            {draft.homeGreetings.map((greeting, index) => (
              <div className="settings-repeater-row" key={`greeting-${index}`}>
                <label>
                  <span>{tr(`问候 ${index + 1} · 中文`, `Greeting ${index + 1} · Chinese`)}</span>
                  <input
                    maxLength={160}
                    value={greeting.chinese}
                    onChange={(event) =>
                      setDraft((current) => ({
                        ...current,
                        homeGreetings: current.homeGreetings.map((item, itemIndex) =>
                          itemIndex === index
                            ? { ...item, chinese: event.target.value }
                            : item,
                        ),
                      }))
                    }
                  />
                </label>
                <label>
                  <span>{tr(`问候 ${index + 1} · 英文`, `Greeting ${index + 1} · English`)}</span>
                  <input
                    maxLength={160}
                    value={greeting.english}
                    onChange={(event) =>
                      setDraft((current) => ({
                        ...current,
                        homeGreetings: current.homeGreetings.map((item, itemIndex) =>
                          itemIndex === index
                            ? { ...item, english: event.target.value }
                            : item,
                        ),
                      }))
                    }
                  />
                </label>
                <button
                  className="icon-button"
                  type="button"
                  aria-label={tr(`删除问候 ${index + 1}`, `Delete greeting ${index + 1}`)}
                  disabled={draft.homeGreetings.length <= 1}
                  onClick={() =>
                    setDraft((current) => ({
                      ...current,
                      homeGreetings: current.homeGreetings.filter((_, itemIndex) => itemIndex !== index),
                    }))
                  }
                >
                  <Trash2 size={17} />
                </button>
              </div>
            ))}
            <button
              className="button-secondary"
              type="button"
              disabled={draft.homeGreetings.length >= 64}
              onClick={() =>
                setDraft((current) => ({
                  ...current,
                  homeGreetings: [
                    ...current.homeGreetings,
                    { chinese: "", english: "" },
                  ],
                }))
              }
            >
              <Plus size={17} />
              {tr("新增问候", "Add greeting")}
            </button>
          </div>
        </div>
      </section>

      <section className="panel settings-section">
        <SettingsSectionHeading
          icon={Home}
          title={tr("主页模块", "Home modules")}
          description={tr(
  "选择 Windows 首页显示的模块；列表顺序使用 Android v33 的稳定模块 ID。",
  "Choose Home modules. The list uses the stable module IDs from Android v33.",
          )}
        />
        <div className="settings-option-grid">
          {HOME_WIDGET_OPTIONS.map(([id, chinese, english]) => (
            <label className="check-control" key={id}>
              <input
                type="checkbox"
                checked={draft.homeWidgets.includes(id)}
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    homeWidgets: toggleCatalogItem(
                      current.homeWidgets,
                      id,
                      event.target.checked,
                      HOME_WIDGET_OPTIONS,
                    ),
                  }))
                }
              />
              {tr(chinese, english)}
            </label>
          ))}
        </div>
        <label className="check-control">
          <input
            type="checkbox"
            checked={draft.homeWidgetBordersEnabled}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                homeWidgetBordersEnabled: event.target.checked,
              }))
            }
          />
          {tr("显示模块边框", "Show module borders")}
        </label>
      </section>

      <section className="panel settings-section">
        <SettingsSectionHeading
          icon={Gamepad2}
          title={tr("小游戏快捷入口", "Game shortcuts")}
          description={tr(
            "只控制主页快捷入口，不删除游戏存档或统计。",
            "This only controls Home shortcuts and never deletes saves or statistics.",
          )}
        />
        <div className="settings-option-grid">
          {HOME_GAME_OPTIONS.map(([id, label]) => (
            <label className="check-control" key={id}>
              <input
                type="checkbox"
                checked={draft.homeGameShortcuts.includes(id)}
                onChange={(event) =>
                  setDraft((current) => ({
                    ...current,
                    homeGameShortcuts: toggleCatalogItem(
                      current.homeGameShortcuts,
                      id,
                      event.target.checked,
                      HOME_GAME_OPTIONS,
                    ),
                  }))
                }
              />
              {label}
            </label>
          ))}
        </div>
      </section>
    </>
  );
}

export function DiaryMediaSettings({
  draft,
  setDraft,
  choosingDirectory,
  tr,
  onChooseDirectory,
}: {
  draft: WindowsSettings;
  setDraft: SetDraft;
  choosingDirectory: DirectoryKind | null;
  tr: SettingsTranslator;
  onChooseDirectory: (kind: DirectoryKind) => void;
}) {
  return (
    <>
      <section className="panel settings-section">
        <SettingsSectionHeading
          icon={FolderOpen}
          title={tr("本地文件", "Local files")}
          description={tr(
            "Windows 原地读写所选目录，不会把路径写入 Android content URI。",
            "Windows works in place and never writes these paths into Android content URIs.",
          )}
        />
        <DirectoryRows
          rows={[
            ["diary", tr("日记目录", "Diary folder"), draft.diaryDirectory],
            ["media", tr("媒体目录", "Media folder"), draft.mediaDirectory],
          ]}
          choosingDirectory={choosingDirectory}
          tr={tr}
          onChoose={onChooseDirectory}
        />
      </section>
      <section className="panel settings-section">
        <SettingsSectionHeading
          icon={BookOpenText}
          title={tr("日记与媒体", "Diary & media")}
          description={tr(
            "新建文件与导入图片使用这些规则。",
            "These rules apply to new files and imported images.",
          )}
        />
        <div className="form-grid">
          <label>
            <span>{tr("日记文件名格式", "Diary filename pattern")}</span>
            <input
              maxLength={120}
              value={draft.fileNamePattern}
              onChange={(event) =>
                setDraft((current) => ({ ...current, fileNamePattern: event.target.value }))
              }
            />
          </label>
          <label>
            <span>{tr("图片文件名格式", "Image filename pattern")}</span>
            <input
              maxLength={160}
              value={draft.imageNamePattern}
              onChange={(event) =>
                setDraft((current) => ({ ...current, imageNamePattern: event.target.value }))
              }
            />
          </label>
          <label className="form-span">
            <span>{tr("Markdown 模板", "Markdown template")}</span>
            <textarea
              rows={5}
              maxLength={65_536}
              value={draft.markdownTemplate}
              onChange={(event) =>
                setDraft((current) => ({ ...current, markdownTemplate: event.target.value }))
              }
            />
          </label>
          <fieldset className="form-span markdown-heading-settings">
            <legend>{tr("Markdown 阅读预览标题字号", "Markdown preview heading sizes")}</legend>
            <div className="settings-option-grid">
              {draft.markdownHeadingSizesSp.map((size, index) => (
                <label key={`h${index + 1}`}>
                  <span>H{index + 1} · {Math.round(size)}sp</span>
                  <input
                    type="range"
                    min="12"
                    max="48"
                    step="1"
                    value={size}
                    onChange={(event) =>
                      setDraft((current) => ({
                        ...current,
                        markdownHeadingSizesSp: current.markdownHeadingSizesSp.map(
                          (item, itemIndex) =>
                            itemIndex === index ? Number(event.target.value) : item,
                        ),
                      }))
                    }
                  />
                </label>
              ))}
            </div>
          </fieldset>
          <label>
            <span>{tr("图片预览最大宽度（px）", "Maximum preview width (px)")}</span>
            <input
              type="number"
              min="120"
              max="2400"
              step="8"
              value={draft.imageMaxWidthPx}
              onChange={(event) =>
                setDraft((current) => ({
                  ...current,
                  imageMaxWidthPx: Number(event.target.value),
                }))
              }
            />
          </label>
          <label>
            <span>{tr("图片预览最大高度（px）", "Maximum preview height (px)")}</span>
            <input
              type="number"
              min="120"
              max="2400"
              step="8"
              value={draft.imageMaxHeightPx}
              onChange={(event) =>
                setDraft((current) => ({
                  ...current,
                  imageMaxHeightPx: Number(event.target.value),
                }))
              }
            />
          </label>
          <label>
            <span>{tr("JPEG 质量", "JPEG quality")} · {draft.mealImageCompressionQuality}%</span>
            <input
              type="range"
              min="30"
              max="95"
              step="1"
              disabled={!draft.mealImageCompressionEnabled}
              value={draft.mealImageCompressionQuality}
              onChange={(event) =>
                setDraft((current) => ({
                  ...current,
                  mealImageCompressionQuality: Number(event.target.value),
                }))
              }
            />
          </label>
          <label className="check-control">
            <input
              type="checkbox"
              checked={draft.mealImageCompressionEnabled}
              onChange={(event) =>
                setDraft((current) => ({
                  ...current,
                  mealImageCompressionEnabled: event.target.checked,
                }))
              }
            />
            <Image size={16} />
            {tr("导入时压缩图片", "Compress imported images")}
          </label>
          <p className="field-help form-span">
            {tr(
              "导入压缩最长边固定限制为 2560 px；上面的宽高只控制应用内预览。",
              "Imported images are capped at a 2560 px longest edge; the dimensions above only control previews.",
            )}
          </p>
          <label className="check-control">
            <input
              type="checkbox"
              checked={draft.photoLocationEnabled}
              onChange={(event) =>
                setDraft((current) => ({
                  ...current,
                  photoLocationEnabled: event.target.checked,
                }))
              }
            />
            {tr("保留 EXIF 经纬度", "Keep EXIF coordinates")}
          </label>
        </div>
      </section>
    </>
  );
}

export function ThoughtSettings({
  draft,
  setDraft,
  tr,
}: {
  draft: WindowsSettings;
  setDraft: SetDraft;
  tr: SettingsTranslator;
}) {
  return (
    <section className="panel settings-section">
      <SettingsSectionHeading
        icon={Sparkles}
        title={tr("小巧思", "Thoughts")}
        description={tr("调整卡片与编辑区显示。", "Adjust cards and the editor.")}
      />
      <div className="form-grid">
        <label>
          <span>{tr("内容显示", "Content display")}</span>
          <select
            value={draft.thoughtDisplayMode}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                thoughtDisplayMode: event.target.value as ThoughtDisplayMode,
              }))
            }
          >
            <option value="SINGLE_LINE">{tr("单行", "Single line")}</option>
            <option value="FULL">{tr("完整内容", "Full content")}</option>
          </select>
        </label>
        <label>
          <span>{tr("重点背景色", "Highlight color")}</span>
          <input
            type="color"
            value={argbToHex(draft.thoughtHighlightColorArgb)}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                thoughtHighlightColorArgb: hexToArgb(event.target.value),
              }))
            }
          />
        </label>
        <label>
          <span>{tr("编辑区最大高度", "Editor maximum height")} · {draft.thoughtEditorMaxHeightPx}px</span>
          <input
            type="range"
            min="96"
            max="400"
            step="8"
            value={draft.thoughtEditorMaxHeightPx}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                thoughtEditorMaxHeightPx: Number(event.target.value),
              }))
            }
          />
        </label>
      </div>
    </section>
  );
}

export function VaultSettings({
  draft,
  setDraft,
  tr,
}: {
  draft: WindowsSettings;
  setDraft: SetDraft;
  tr: SettingsTranslator;
}) {
  return (
    <section className="panel settings-section">
      <SettingsSectionHeading
        icon={ShieldCheck}
        title={tr("收藏夹", "Vault")}
        description={tr(
          "这里只调整卡片显示；密码、明文和派生密钥仍只在 Rust 边界处理。",
          "Only card display is configured here. Passwords, plaintext and derived keys remain inside Rust.",
        )}
      />
      <div className="form-grid">
        <label>
          <span>
            {tr("卡片最小高度", "Minimum card height")} · {draft.vaultRowHeightDp}dp
          </span>
          <input
            type="range"
            min="48"
            max="120"
            step="4"
            value={draft.vaultRowHeightDp}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                vaultRowHeightDp: Number(event.target.value),
              }))
            }
          />
        </label>
      </div>
      <Link className="button button-secondary" to="/vault">
        {tr("打开收藏夹", "Open Vault")}
      </Link>
    </section>
  );
}

export function PoetryMealSettings({
  draft,
  setDraft,
  tr,
}: {
  draft: WindowsSettings;
  setDraft: SetDraft;
  tr: SettingsTranslator;
}) {
  return (
    <section className="panel settings-section">
      <SettingsSectionHeading
        icon={Languages}
        title={tr("诗词本与吃历", "Poetry & meal calendar")}
        description={tr(
          "排版选项不会改写原始内容或图片。",
          "Display options never rewrite source text or images.",
        )}
      />
      <div className="form-grid">
        <label>
          <span>{tr("诗词字号", "Poetry font size")} · {draft.poetryFontSizePx}px</span>
          <input
            type="range"
            min="14"
            max="36"
            step="1"
            value={draft.poetryFontSizePx}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                poetryFontSizePx: Number(event.target.value),
              }))
            }
          />
        </label>
        <label>
          <span>{tr("诗词行距", "Poetry line spacing")} · {draft.poetryLineSpacing.toFixed(2)}</span>
          <input
            type="range"
            min="1"
            max="2"
            step="0.05"
            value={draft.poetryLineSpacing}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                poetryLineSpacing: Number(event.target.value),
              }))
            }
          />
        </label>
        <label>
          <span>{tr("诗词对齐", "Poetry alignment")}</span>
          <select
            value={draft.poetryTextAlignment}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                poetryTextAlignment: event.target.value as PoetryTextAlignment,
              }))
            }
          >
            <option value="START">{tr("靠左", "Start")}</option>
            <option value="CENTER">{tr("居中", "Center")}</option>
          </select>
        </label>
        <label className="check-control">
          <input
            type="checkbox"
            checked={draft.poetryShowSource}
            onChange={(event) =>
              setDraft((current) => ({ ...current, poetryShowSource: event.target.checked }))
            }
          />
          {tr("显示出处", "Show source")}
        </label>
        <label className="check-control">
          <input
            type="checkbox"
            checked={draft.poetryShowQuoteMark}
            onChange={(event) =>
              setDraft((current) => ({ ...current, poetryShowQuoteMark: event.target.checked }))
            }
          />
          {tr("显示引号装饰", "Show quote decoration")}
        </label>
        <label className="check-control">
          <input
            type="checkbox"
            checked={draft.poetrySevenCharacterWrapEnabled}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                poetrySevenCharacterWrapEnabled: event.target.checked,
              }))
            }
          />
          {tr("七言自动换行", "Auto-wrap seven-character lines")}
        </label>
        <label>
          <span>{tr("吃历图片最大高度", "Meal image maximum height")} · {draft.mealCalendarImageMaxHeightPx}px</span>
          <input
            type="range"
            min="80"
            max="320"
            step="8"
            value={draft.mealCalendarImageMaxHeightPx}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                mealCalendarImageMaxHeightPx: Number(event.target.value),
              }))
            }
          />
        </label>
        <label>
          <span>{tr("每行图片", "Photos per row")}</span>
          <select
            value={draft.mealCalendarPhotosPerRow}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                mealCalendarPhotosPerRow: event.target.value as MealPhotosPerRow,
              }))
            }
          >
            <option value="TWO">2</option>
            <option value="THREE">3</option>
            <option value="SMART">{tr("2+3 智能", "Smart 2+3")}</option>
          </select>
        </label>
        <label className="check-control">
          <input
            type="checkbox"
            checked={draft.mealCalendarShowCaptions}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                mealCalendarShowCaptions: event.target.checked,
              }))
            }
          />
          {tr("显示说明文字", "Show captions")}
        </label>
        <label className="check-control">
          <input
            type="checkbox"
            checked={draft.mealCalendarWrapEnabled}
            onChange={(event) =>
              setDraft((current) => ({
                ...current,
                mealCalendarWrapEnabled: event.target.checked,
              }))
            }
          />
          {tr("智能换行", "Smart wrapping")}
        </label>
      </div>
    </section>
  );
}

export function AppDataSettings({
  draft,
  choosingDirectory,
  tr,
  onChooseDirectory,
  onOpen,
}: {
  draft: WindowsSettings;
  choosingDirectory: DirectoryKind | null;
  tr: SettingsTranslator;
  onChooseDirectory: (kind: DirectoryKind) => void;
  onOpen: (id: SettingsPageId) => void;
}) {
  return (
    <>
      <section className="panel settings-section">
        <SettingsSectionHeading
          icon={ArchiveRestore}
          title={tr("自动备份目录", "Automatic backup folder")}
          description={tr(
            "自动备份使用 pending/current/previous 轮换并在写入后回读校验。",
            "Automatic backups use pending/current/previous rotation and verify every write.",
          )}
        />
        <DirectoryRows
          rows={[
            [
              "backup",
              tr("自动备份目录（可选）", "Automatic backup folder (optional)"),
              draft.backupDirectory,
            ],
          ]}
          choosingDirectory={choosingDirectory}
          tr={tr}
          onChoose={onChooseDirectory}
        />
      </section>
      <section className="settings-action-grid">
        <article className="panel settings-action-card">
          <ArchiveRestore size={22} aria-hidden="true" />
          <div>
            <h2>{tr("JSON 备份与恢复", "JSON backup & restore")}</h2>
            <p>{tr(
              "导入预览、手动导出、自动备份和导入前恢复点。",
              "Import preview, manual export, automatic backup and pre-import restore points.",
            )}</p>
          </div>
          <Link className="button button-primary" to="/backup">
            {tr("打开备份管理", "Open backup manager")}
          </Link>
        </article>
        <article className="panel settings-action-card">
          <Cloud size={22} aria-hidden="true" />
          <div>
            <h2>{tr("WebDAV / S3 云同步", "WebDAV / S3 cloud sync")}</h2>
            <p>{tr(
              "管理多个服务、同步内容、方向与本机加密凭据。",
              "Manage services, content, direction and locally encrypted credentials.",
            )}</p>
          </div>
          <button className="button button-secondary" type="button" onClick={() => onOpen("cloud")}>
            {tr("打开云端同步", "Open cloud sync")}
          </button>
        </article>
        <article className="panel settings-action-card">
          <ArchiveRestore size={22} aria-hidden="true" />
          <div>
            <h2>{tr("数据占用", "Storage usage")}</h2>
            <p>{tr(
              "查看数据库、缓存及已选择日记、媒体和备份目录的有界统计。",
              "View bounded totals for the database, cache, and selected diary, media and backup folders.",
            )}</p>
          </div>
          <button className="button button-secondary" type="button" onClick={() => onOpen("data-usage")}>
            {tr("查看数据占用", "View storage usage")}
          </button>
        </article>
      </section>
      <aside className="panel compatibility-note">
        <ShieldCheck size={20} aria-hidden="true" />
        <div>
          <h2>{tr("Android v33 兼容", "Android v33 compatibility")}</h2>
          <p>{tr(
            "Windows 目录和本机背景路径保持独立；导出时不会覆盖 Android 的 content URI 字段。",
            "Windows folders and local background paths remain separate and never overwrite Android content URI fields on export.",
          )}</p>
        </div>
      </aside>
    </>
  );
}
