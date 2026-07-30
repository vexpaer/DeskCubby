import {
  ArchiveRestore,
  BookOpenText,
  Cloud,
  FolderOpen,
  Image,
  Languages,
  ShieldCheck,
  Sparkles,
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
}: {
  draft: WindowsSettings;
  setDraft: SetDraft;
  tr: SettingsTranslator;
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
      </div>
    </section>
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
      </section>
      <aside className="panel compatibility-note">
        <ShieldCheck size={20} aria-hidden="true" />
        <div>
          <h2>{tr("Android v18 兼容", "Android v18 compatibility")}</h2>
          <p>{tr(
            "Windows 目录保持独立；导出时不会覆盖 Android 的 content URI 字段。",
            "Windows folders remain separate and never overwrite Android content URI fields on export.",
          )}</p>
        </div>
      </aside>
    </>
  );
}
