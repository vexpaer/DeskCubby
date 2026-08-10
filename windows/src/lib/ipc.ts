import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";

export type Language = "zh-CN" | "en";
export type ThemeStyle = "material" | "liquid-glass" | "organic-future";
export type ColorMode = "system" | "light" | "dark";

export const IPC_SCHEMA_VERSION = 2;

export interface IpcProtocolInfo {
  schemaVersion: number;
  minimumSupportedVersion: number;
  appVersion: string;
}

export function tr(language: Language, zh: string, en: string): string {
  return language === "en" ? en : zh;
}

export interface IpcErrorShape {
  code: string;
  message?: string;
}

export class DeskCubbyIpcError extends Error {
  readonly code: string;

  constructor(code: string, message?: string) {
    super(message || code);
    this.name = "DeskCubbyIpcError";
    this.code = code;
  }
}

function asIpcError(error: unknown): DeskCubbyIpcError {
  if (error instanceof DeskCubbyIpcError) return error;

  if (typeof error === "object" && error !== null) {
    const candidate = error as Partial<IpcErrorShape>;
    if (typeof candidate.code === "string") {
      return new DeskCubbyIpcError(
        candidate.code,
        typeof candidate.message === "string" ? candidate.message : undefined,
      );
    }
  }

  if (typeof error === "string") {
    try {
      const parsed = JSON.parse(error) as Partial<IpcErrorShape>;
      if (typeof parsed.code === "string") {
        return new DeskCubbyIpcError(parsed.code, parsed.message);
      }
    } catch {
      // Rust commands intentionally return stable error codes. Do not expose an
      // unexpected raw error because it can contain a private path or content.
    }
  }

  return new DeskCubbyIpcError("unexpected_error");
}

async function call<T>(
  command: string,
  args?: Record<string, unknown>,
): Promise<T> {
  try {
    return await invoke<T>(command, args);
  } catch (error) {
    throw asIpcError(error);
  }
}

export function assertIpcProtocolCompatible(
  protocol: IpcProtocolInfo,
): IpcProtocolInfo {
  const valid =
    Number.isSafeInteger(protocol.schemaVersion) &&
    Number.isSafeInteger(protocol.minimumSupportedVersion) &&
    protocol.schemaVersion >= 1 &&
    protocol.minimumSupportedVersion >= 1 &&
    protocol.minimumSupportedVersion <= IPC_SCHEMA_VERSION &&
    IPC_SCHEMA_VERSION <= protocol.schemaVersion &&
    typeof protocol.appVersion === "string" &&
    protocol.appVersion.trim().length > 0;
  if (!valid) {
    throw new DeskCubbyIpcError("ipc_protocol_incompatible");
  }
  return protocol;
}

function hasTauriInvokeBridge(): boolean {
  return (
    typeof window !== "undefined" &&
    "__TAURI_INTERNALS__" in window
  );
}

export async function verifyIpcProtocol(): Promise<IpcProtocolInfo> {
  // Component tests and the standalone Vite preview intentionally have no
  // privileged Tauri bridge. Real desktop builds always validate Rust first.
  const protocol = hasTauriInvokeBridge()
    ? await call<IpcProtocolInfo>("get_ipc_protocol")
    : {
        schemaVersion: IPC_SCHEMA_VERSION,
        minimumSupportedVersion: IPC_SCHEMA_VERSION,
        appVersion: "test-or-browser-preview",
      };
  return assertIpcProtocolCompatible(protocol);
}

// Shared escape hatch for typed feature modules whose commands are not part of
// the core page clients below. It keeps the same redacted error boundary.
export const invokeCommand = call;

export type WindowsVisualStyle =
  | "MATERIAL"
  | "LIQUID_GLASS"
  | "ORGANIC_FUTURE";
export type WindowsDarkMode = "SYSTEM" | "LIGHT" | "DARK";
export type WindowsAppLanguage = "CHINESE" | "ENGLISH";
export type PoetryTextAlignment = "START" | "CENTER";
export type MealPhotosPerRow = "TWO" | "THREE" | "SMART";
export type ThoughtDisplayMode = "SINGLE_LINE" | "FULL";
export type DirectoryKind = "diary" | "media" | "backup";

export interface WindowsHomeGreeting {
  chinese: string;
  english: string;
}

export interface WindowsSettings {
  visualStyle: WindowsVisualStyle;
  darkMode: WindowsDarkMode;
  appLanguage: WindowsAppLanguage;
  themeColorArgb: number;
  themeSecondaryColorsArgb: number[];
  fontScale: number;
  compactMode: boolean;
  backgroundImagePath: string | null;
  backgroundImageOpacity: number;
  backgroundImageBlurPx: number;
  tutorialModeEnabled: boolean;
  diaryDirectory: string | null;
  mediaDirectory: string | null;
  backupDirectory: string | null;
  fileNamePattern: string;
  markdownTemplate: string;
  imageNamePattern: string;
  imageMaxWidthPx: number;
  imageMaxHeightPx: number;
  markdownHeadingSizesSp: number[];
  mealImageCompressionEnabled: boolean;
  mealImageCompressionQuality: number;
  photoLocationEnabled: boolean;
  thoughtDisplayMode: ThoughtDisplayMode;
  thoughtHighlightColorArgb: number;
  thoughtEditorMaxHeightPx: number;
  vaultRowHeightDp: number;
  poetryFontSizePx: number;
  poetryLineSpacing: number;
  poetryTextAlignment: PoetryTextAlignment;
  poetryShowSource: boolean;
  poetryShowQuoteMark: boolean;
  poetrySevenCharacterWrapEnabled: boolean;
  mealCalendarImageMaxHeightPx: number;
  mealCalendarShowCaptions: boolean;
  mealCalendarWrapEnabled: boolean;
  mealCalendarPhotosPerRow: MealPhotosPerRow;
  mealButtonsUseIcons: boolean;
  mealButtonIcons: string[];
  userName: string;
  homeGreetings: WindowsHomeGreeting[];
  homeWidgetBordersEnabled: boolean;
  homeWidgets: string[];
  homeGameShortcuts: string[];
  homeWidgetTitles: string[];
}

// Transient navigation context only. The backend still validates the relative
// name before every write; no absolute path or file permission is retained in
// the webview.
let activeDiaryRelativePath: string | null = null;

export function rememberActiveDiary(relativePath: string | null): void {
  activeDiaryRelativePath = relativePath;
}

export function getActiveDiary(): string | null {
  return activeDiaryRelativePath;
}

export interface FileVersion {
  sha256: string;
  size: number;
  modifiedAt: string;
}

export interface DiaryEntry {
  relativePath: string;
  fileName: string;
  title: string;
  date: string;
  month: string;
  excerpt: string;
  wordCount: number;
  modifiedAt: string;
  trashed: boolean;
}

export interface DiaryDocument {
  entry: DiaryEntry;
  content: string;
  version: FileVersion;
}

export type DiarySaveResolution = "normal" | "overwrite" | "copy";

export interface DiarySaveRequest {
  relativePath: string;
  content: string;
  expectedVersion: FileVersion | null;
  resolution: DiarySaveResolution;
}

export type DiarySaveResult =
  | { status: "saved"; document: DiaryDocument }
  | {
      status: "conflict";
      currentVersion: FileVersion;
      reason: "changed" | "deleted";
    };

export type MealCategory =
  | "breakfast"
  | "lunch"
  | "afternoon_tea"
  | "dinner"
  | "fruit"
  | "late_night";

export interface MealPhoto {
  id: string;
  fileName: string;
  diaryRelativePath: string;
  date: string;
  category: MealCategory;
  caption: string;
  energyKj: number | null;
  location: string | null;
  latitude: number | null;
  longitude: number | null;
  assetUrl: string | null;
  missing: boolean;
}

export interface MealQuery {
  startDate: string | null;
  endDate: string | null;
  categories: MealCategory[];
}

export type MealColumns = 2 | 3 | "smart";

export type MealDayColumns = 1 | 2;

export interface MealFilterPreferences {
  enabled: boolean;
  brightness: number;
  contrast: number;
  saturation: number;
  warmth: number;
  tint: number;
}

export interface MealViewPreferences {
  schemaVersion: 1;
  dayColumns: MealDayColumns;
  wrapEnabled: boolean;
  columns: MealColumns;
  showCaptions: boolean;
  imageMaxHeightPx: number;
  filter: MealFilterPreferences;
}

export interface MealExportRequest extends MealQuery {
  columns: MealColumns;
  showCaptions: boolean;
  filterEnabled: boolean;
  brightness: number;
  contrast: number;
  saturation: number;
  warmth: number;
  tint: number;
}

export interface ImportedMedia {
  fileName: string;
  markdown: string;
  photo: MealPhoto | null;
}

export interface DailyTemplate {
  id: string;
  text: string;
  sortOrder: number;
}

export interface DailyRecordContext {
  currentDiaryRelativePath: string | null;
  today: string;
}

export type DailyRecordTarget = "current" | "today";

/**
 * A signed 64-bit integer encoded as base-10 text at the IPC boundary.
 *
 * SQLite row IDs, sort keys and millisecond timestamps must never cross the
 * WebView bridge as JavaScript numbers: valid i64 values can exceed 2^53.
 */
export type DecimalI64 = string;

const DECIMAL_I64_PATTERN = /^-?(?:0|[1-9]\d*)$/;
const MIN_DATE_MILLISECONDS = -8_640_000_000_000_000n;
const MAX_DATE_MILLISECONDS = 8_640_000_000_000_000n;

export function compareDecimalI64(
  left: DecimalI64,
  right: DecimalI64,
): number {
  if (!DECIMAL_I64_PATTERN.test(left) || !DECIMAL_I64_PATTERN.test(right)) {
    return left.localeCompare(right);
  }
  const leftValue = BigInt(left);
  const rightValue = BigInt(right);
  return leftValue < rightValue ? -1 : leftValue > rightValue ? 1 : 0;
}

/**
 * Converts a decimal i64 millisecond timestamp only when it is inside the
 * ECMAScript Date range. The Number conversion is intentionally confined to
 * display; IDs and ordering values remain lossless strings.
 */
export function dateFromI64Milliseconds(value: DecimalI64): Date | null {
  if (!DECIMAL_I64_PATTERN.test(value)) return null;
  const milliseconds = BigInt(value);
  if (
    milliseconds < MIN_DATE_MILLISECONDS ||
    milliseconds > MAX_DATE_MILLISECONDS
  ) {
    return null;
  }
  const date = new Date(Number(milliseconds));
  return Number.isNaN(date.valueOf()) ? null : date;
}

export interface Thought {
  id: DecimalI64;
  content: string;
  createdAt: DecimalI64;
  updatedAt: DecimalI64;
  pinned: boolean;
  deletedAt: DecimalI64 | null;
  sortOrder: DecimalI64;
  categoryId: DecimalI64 | null;
  highlighted: boolean;
}

export interface ThoughtCategory {
  id: DecimalI64;
  name: string;
  colorArgb: number;
  sortOrder: DecimalI64;
  createdAt: DecimalI64;
  updatedAt: DecimalI64;
}

export interface ThoughtDraft {
  id?: DecimalI64;
  content: string;
  pinned: boolean;
  categoryId: DecimalI64 | null;
  highlighted: boolean;
}

export interface ThoughtCategoryDraft {
  id?: DecimalI64;
  name: string;
  colorArgb: number;
}

export interface DateRecord {
  id: DecimalI64;
  name: string;
  icon: string;
  dateIso: string;
  createdAt: DecimalI64;
  updatedAt: DecimalI64;
}

export interface DateRecordDraft {
  id?: DecimalI64;
  name: string;
  icon: string;
  dateIso: string;
}

export interface SavedPoem {
  id: DecimalI64;
  content: string;
  source: string;
  createdAt: DecimalI64;
  updatedAt: DecimalI64;
  sortOrder: DecimalI64;
  categoryId: DecimalI64 | null;
}

export interface SavedPoemDraft {
  id?: DecimalI64;
  content: string;
  source: string;
  categoryId?: DecimalI64 | null;
}

export interface PoetryCategory {
  id: DecimalI64;
  name: string;
  colorArgb: number;
  sortOrder: DecimalI64;
  createdAt: DecimalI64;
  updatedAt: DecimalI64;
}

export interface PoetryCategoryDraft {
  id?: DecimalI64;
  name: string;
  colorArgb: number;
}

export interface PoetryPresetSummary {
  id: string;
  nameZh: string;
  nameEn: string;
  colorArgb: number;
  itemCount: number;
}

export interface PoetryPresetImportResult {
  categoryId: DecimalI64;
  addedCount: number;
  existingCount: number;
}

export type PoetryMoveScope = "all" | "uncategorized" | DecimalI64;

export interface ThoughtSummary {
  id: DecimalI64;
  content: string;
  categoryName: string | null;
  color: string | null;
  pinned: boolean;
  highlighted: boolean;
  updatedAt: DecimalI64;
}

export interface PoemSummary {
  title: string;
  dynasty: string;
  author: string;
  content: string[];
  source: "network" | "cache" | "builtin";
}

export interface DailyPoemResponse {
  content: string;
  title: string | null;
  source: string | null;
  author: string | null;
  dynasty: string | null;
  fromCache: boolean;
  usedFallback: boolean;
}

export interface HomeSnapshot {
  today: string;
  greeting: string;
  dailyPoem: PoemSummary | null;
  recentDiaries: DiaryEntry[];
  recentThoughts: ThoughtSummary[];
  mealPhotos: MealPhoto[];
  dailyTemplates: DailyTemplate[];
  currentDiaryRelativePath: string | null;
  monthlyDiaryCount: number;
  monthlyThoughtCount: number;
  totalWordCount: number;
  yearProgress: number;
  randomDiary: DiaryEntry | null;
}

export const diaryApi = {
  list(includeTrashed = false): Promise<DiaryEntry[]> {
    return call("list_diaries", { includeTrashed });
  },

  open(relativePath: string): Promise<DiaryDocument> {
    return call("open_diary", { relativePath });
  },

  create(date: string, title: string): Promise<DiaryDocument> {
    return call("create_diary", { request: { date, title } });
  },

  save(request: DiarySaveRequest): Promise<DiarySaveResult> {
    return call("save_diary", { request });
  },

  rename(relativePath: string, title: string): Promise<DiaryDocument> {
    return call("rename_diary", { request: { relativePath, title } });
  },

  trash(relativePath: string): Promise<void> {
    return call("trash_diary", { relativePath });
  },

  restore(relativePath: string): Promise<DiaryDocument> {
    return call("restore_diary", { relativePath });
  },

  deletePermanently(relativePath: string): Promise<void> {
    return call("delete_diary_permanently", { relativePath });
  },

  rescan(): Promise<DiaryEntry[]> {
    return call("rescan_diaries");
  },

  resolveMediaAsset(
    diaryRelativePath: string,
    source: string,
  ): Promise<string | null> {
    return call("resolve_media_asset", { diaryRelativePath, source });
  },

  selectAndImportImage(
    diaryRelativePath: string,
    category: MealCategory | null,
  ): Promise<ImportedMedia | null> {
    return call("select_and_import_diary_image", {
      request: { diaryRelativePath, category },
    });
  },
};

export const mealApi = {
  list(query: MealQuery): Promise<MealPhoto[]> {
    return call("list_meal_photos", { query });
  },

  getViewPreferences(): Promise<MealViewPreferences> {
    return call("get_meal_view_preferences");
  },

  updateViewPreferences(
    preferences: MealViewPreferences,
  ): Promise<MealViewPreferences> {
    return call("update_meal_view_preferences", { preferences });
  },

  selectAndImport(
    date: string,
    category: MealCategory,
  ): Promise<ImportedMedia[]> {
    return call("select_and_import_meal_photos", {
      request: { date, category },
    });
  },

  exportPng(request: MealExportRequest): Promise<string | null> {
    return call("export_meal_calendar_png", { request });
  },
};

export const dailyRecordApi = {
  listTemplates(): Promise<DailyTemplate[]> {
    return call("list_daily_templates");
  },

  getContext(): Promise<DailyRecordContext> {
    return call("get_daily_record_context");
  },

  createTemplate(text: string): Promise<DailyTemplate> {
    return call("create_daily_template", { text });
  },

  updateTemplate(id: string, text: string): Promise<DailyTemplate> {
    return call("update_daily_template", { request: { id, text } });
  },

  deleteTemplate(id: string): Promise<void> {
    return call("delete_daily_template", { id });
  },

  reorderTemplates(ids: string[]): Promise<DailyTemplate[]> {
    return call("reorder_daily_templates", { ids });
  },

  append(
    text: string,
    target: DailyRecordTarget,
    currentDiaryRelativePath: string | null,
  ): Promise<DiaryEntry> {
    return call("append_daily_record", {
      request: { text, target, currentDiaryRelativePath },
    });
  },
};

export const thoughtApi = {
  list(includeDeleted = false): Promise<Thought[]> {
    return call("list_thoughts", { includeDeleted });
  },

  listCategories(): Promise<ThoughtCategory[]> {
    return call("list_thought_categories");
  },

  create(draft: ThoughtDraft): Promise<Thought> {
    return call("create_thought", { draft });
  },

  update(id: DecimalI64, draft: ThoughtDraft): Promise<Thought> {
    return call("update_thought", { id, draft });
  },

  delete(id: DecimalI64, permanent = false): Promise<Thought | null> {
    return call("delete_thought", { id, permanent });
  },

  restore(id: DecimalI64): Promise<Thought> {
    return call("restore_thought", { id });
  },

  reorder(ids: DecimalI64[]): Promise<void> {
    return call("reorder_thoughts", { ids });
  },

  createCategory(draft: ThoughtCategoryDraft): Promise<ThoughtCategory> {
    return call("create_thought_category", { draft });
  },

  updateCategory(
    id: DecimalI64,
    draft: ThoughtCategoryDraft,
  ): Promise<ThoughtCategory> {
    return call("update_thought_category", { id, draft });
  },

  deleteCategory(id: DecimalI64): Promise<void> {
    return call("delete_thought_category", { id });
  },

  exportCategory(categoryId: DecimalI64): Promise<boolean> {
    return call("export_thought_category", { categoryId });
  },
};

export const dateRecordApi = {
  list(): Promise<DateRecord[]> {
    return call("list_date_records");
  },

  create(draft: DateRecordDraft): Promise<DateRecord> {
    return call("create_date_record", { draft });
  },

  update(id: DecimalI64, draft: DateRecordDraft): Promise<DateRecord> {
    return call("update_date_record", { id, draft });
  },

  delete(id: DecimalI64): Promise<void> {
    return call("delete_date_record", { id });
  },
};

export const poetryApi = {
  list(): Promise<SavedPoem[]> {
    return call("list_poems");
  },

  create(draft: SavedPoemDraft): Promise<SavedPoem> {
    return call("create_poem", { draft });
  },

  update(id: DecimalI64, draft: SavedPoemDraft): Promise<SavedPoem> {
    return call("update_poem", { id, draft });
  },

  delete(id: DecimalI64): Promise<void> {
    return call("delete_poem", { id });
  },

  listCategories(): Promise<PoetryCategory[]> {
    return call("list_poetry_categories");
  },

  createCategory(draft: PoetryCategoryDraft): Promise<PoetryCategory> {
    return call("create_poetry_category", { draft });
  },

  updateCategory(
    id: DecimalI64,
    draft: PoetryCategoryDraft,
  ): Promise<PoetryCategory> {
    return call("update_poetry_category", { id, draft });
  },

  deleteCategory(id: DecimalI64, deletePoems = false): Promise<void> {
    return call("delete_poetry_category", { id, deletePoems });
  },

  moveCategory(id: DecimalI64, targetIndex: number): Promise<void> {
    return call("move_poetry_category", { id, targetIndex });
  },

  setCategory(id: DecimalI64, categoryId: DecimalI64 | null): Promise<void> {
    return call("set_poem_category", { id, categoryId });
  },

  move(id: DecimalI64, targetIndex: number, scope: PoetryMoveScope): Promise<void> {
    return call("move_poem", { id, targetIndex, scope });
  },

  listPresets(): Promise<PoetryPresetSummary[]> {
    return call("list_poetry_presets");
  },

  importPreset(presetId: string): Promise<PoetryPresetImportResult> {
    return call("import_poetry_preset", { presetId });
  },
};

export const homeApi = {
  snapshot(): Promise<HomeSnapshot> {
    return call("get_home_snapshot");
  },

  settings(): Promise<WindowsSettings> {
    return call("get_windows_settings");
  },

  createThought(content: string): Promise<Thought> {
    return call("create_thought", { request: { content } });
  },

  dailyPoem(forceRefresh = false): Promise<DailyPoemResponse> {
    return call("get_daily_poem", { forceRefresh });
  },
};

export async function subscribeDiaryIndexChanged(
  listener: () => void,
): Promise<UnlistenFn> {
  return listen("diary-index-changed", listener);
}

export function readableError(
  error: unknown,
  language: Language = "zh-CN",
): string {
  const code =
    error instanceof DeskCubbyIpcError ? error.code : "unexpected_error";
  const zh: Record<string, string> = {
    directory_not_configured: "请先在设置中选择日记和媒体目录。",
    entry_not_found: "找不到这篇日记，它可能已被其他应用移动。",
    not_found: "找不到这条记录，它可能已被删除。",
    media_not_found: "找不到媒体文件。",
    invalid_input: "输入内容不符合要求。",
    invalid_date: "日期格式不正确。",
    conflict: "文件已被其他应用修改。",
    external_edit_conflict:
      "文件已被其他应用修改，请重新加载、明确覆盖或另存副本。",
    io_failed: "文件操作失败，请检查目录权限。",
    storage_unavailable: "本地存储暂时不可用，请检查目录权限。",
    path_not_allowed: "所选路径不在允许的目录中。",
    operation_failed: "操作没有完成，请重试。",
    network_unavailable: "网络暂时不可用，已保留本地内容。",
    json_too_large: "备份文件超过 64 MiB 上限。",
    backup_too_large: "备份文件超过 64 MiB 上限。",
    backup_invalid: "备份文件无效或不符合 Android v1–v28 格式。",
    backup_version_unsupported: "仅支持 Android v1–v28 备份。",
    compatibility_shadow_corrupt:
      "兼容备份数据已损坏，无法安全保留未知字段，请重新导入有效的 v1–v28 备份。",
    ipc_protocol_incompatible:
      "Windows 界面与本机后端版本不兼容，请安装匹配版本的 DeskCubby。",
    database_busy: "本地数据库正忙，请稍后重试。",
    database_version_unsupported: "数据库来自更高版本，请升级 DeskCubby。",
    invalid_configuration: "云同步配置无效，请检查服务地址、路径和账户字段。",
    sync_force_download_source_count:
      "强制下载要求恰好启用一个云端来源，请先停用其他同步服务。",
    cloud_sync_busy: "云同步正在运行，请等待完成或先取消。",
    cloud_credentials_replacement_required:
      "账号绑定字段已变化，请明确替换或清除本机凭据。",
    authentication_failed: "云端身份验证失败，请检查本机保存的凭据。",
    permission_denied: "云端服务拒绝访问，请检查账户权限。",
    remote_directory_missing: "找不到远端目录，请检查同步路径。",
    unsupported_remote: "云端服务不支持安全同步所需的条件请求。",
    limit_exceeded: "同步内容超过安全数量或大小上限。",
    timed_out: "连接云端服务超时，请重试。",
    vault_not_configured: "收藏夹尚未设置密码。",
    vault_already_configured: "收藏夹已经设置，请刷新后重试。",
    vault_locked: "收藏夹已锁定，请重新解锁。",
    vault_wrong_password: "收藏夹密码不正确。",
    vault_invalid_password: "收藏夹密码不符合要求。",
    vault_invalid_content: "收藏内容无效或过长。",
    vault_metadata_corrupt: "收藏夹元数据损坏，无法安全打开。",
    vault_corrupted_items: "部分收藏无法解密，已跳过且未删除。",
    vault_item_not_found: "找不到这条收藏，它可能已被删除。",
    vault_order_invalid: "收藏排序无效，请刷新后重试。",
    vault_session_changed: "收藏夹会话已变化，请重新解锁后重试。",
    vault_store_unavailable: "本机收藏夹存储暂时不可用。",
    vault_operation_failed: "收藏夹操作失败，请重试。",
    vault_url_not_safe: "这条收藏不是可安全打开的 HTTP(S) 链接。",
    vault_open_failed: "无法使用系统浏览器打开这条收藏。",
    vault_clipboard_failed: "无法把收藏正文复制到剪贴板。",
    usage_statistics_invalid: "手机使用时间文件无效，仅支持 Android v4 统计文件或含 usageDevices 的 v28 备份。",
    usage_statistics_too_large: "手机使用时间文件超过安全大小上限。",
    usage_statistics_source_missing: "手机统计源文件已丢失，请重新选择。",
    usage_statistics_source_changed: "读取时手机统计源文件发生变化，请重试。",
    usage_statistics_cache_unavailable: "本机手机统计缓存暂时不可用。",
    usage_statistics_not_configured: "尚未导入或链接手机统计文件。",
    usage_statistics_not_linked: "当前数据不是只读链接，无法从源文件刷新。",
    update_not_configured: "此构建未配置可信更新源或更新公钥。",
    update_busy: "另一个更新操作正在进行，请稍候。",
    update_not_available: "这项更新已不可用，请重新检查。",
    update_version_changed: "可用版本已变化，请重新检查后再安装。",
    update_check_failed: "无法检查更新，请确认网络后重试。",
    update_install_failed: "更新下载、验证或安装失败。",
    open_failed: "无法使用系统浏览器打开链接。",
    cancelled: "操作已取消。",
    export_too_large: "导出图片过大，请缩短日期范围。",
    unexpected_error: "操作失败，请重试。",
  };
  const en: Record<string, string> = {
    directory_not_configured:
      "Choose diary and media folders in Settings first.",
    entry_not_found:
      "This diary could not be found. It may have been moved externally.",
    not_found: "This record could not be found. It may have been deleted.",
    media_not_found: "The media file could not be found.",
    invalid_input: "The input is invalid.",
    invalid_date: "The date is invalid.",
    conflict: "The file was changed by another application.",
    external_edit_conflict:
      "The file was changed externally. Reload, explicitly overwrite, or save a copy.",
    io_failed: "The file operation failed. Check folder access.",
    storage_unavailable: "Local storage is unavailable. Check folder access.",
    path_not_allowed: "The selected path is outside the allowed folder.",
    operation_failed: "The operation did not complete. Please try again.",
    network_unavailable:
      "The network is unavailable. Local content has been preserved.",
    json_too_large: "The backup exceeds the 64 MiB limit.",
    backup_too_large: "The backup exceeds the 64 MiB limit.",
    backup_invalid: "The backup is invalid or is not an Android v1–v28 backup.",
    backup_version_unsupported: "Only Android v1–v28 backups are supported.",
    compatibility_shadow_corrupt:
      "Compatibility backup data is corrupt, so unknown fields cannot be preserved safely. Import a valid v1–v28 backup again.",
    ipc_protocol_incompatible:
      "The Windows interface and local backend are incompatible. Install matching DeskCubby versions.",
    database_busy: "The local database is busy. Try again shortly.",
    database_version_unsupported:
      "This database requires a newer version of DeskCubby.",
    invalid_configuration:
      "The cloud sync configuration is invalid. Check the endpoint, path and account fields.",
    sync_force_download_source_count:
      "Force download requires exactly one enabled cloud source. Disable the other sync services first.",
    cloud_sync_busy:
      "Cloud sync is running. Wait for it to finish or cancel it first.",
    cloud_credentials_replacement_required:
      "Credential-binding fields changed. Replace or clear the local credentials.",
    authentication_failed:
      "Cloud authentication failed. Check the credentials stored on this PC.",
    permission_denied:
      "The cloud service denied access. Check the account permissions.",
    remote_directory_missing:
      "The remote directory could not be found. Check the sync path.",
    unsupported_remote:
      "The cloud service does not support the conditional requests required for safe sync.",
    limit_exceeded: "The sync exceeds a safety count or size limit.",
    timed_out: "The cloud service timed out. Try again.",
    vault_not_configured: "The vault does not have a password yet.",
    vault_already_configured:
      "The vault is already configured. Refresh and try again.",
    vault_locked: "The vault is locked. Unlock it again.",
    vault_wrong_password: "The vault password is incorrect.",
    vault_invalid_password: "The vault password does not meet the requirements.",
    vault_invalid_content: "The vault content is invalid or too long.",
    vault_metadata_corrupt:
      "The vault metadata is corrupt and cannot be opened safely.",
    vault_corrupted_items:
      "Some vault items could not be decrypted. They were skipped, not deleted.",
    vault_item_not_found:
      "This vault item could not be found. It may have been deleted.",
    vault_order_invalid: "The vault order is invalid. Refresh and try again.",
    vault_session_changed:
      "The vault session changed. Unlock it again before retrying.",
    vault_store_unavailable: "The local vault storage is unavailable.",
    vault_operation_failed: "The vault operation failed. Try again.",
    vault_url_not_safe: "This vault item is not a safe HTTP(S) link.",
    vault_open_failed: "The vault item could not be opened in the system browser.",
    vault_clipboard_failed: "The vault content could not be copied to the clipboard.",
    usage_statistics_invalid:
      "The phone screen-time file is invalid. Use Android v4 statistics or a v28 backup containing usageDevices.",
    usage_statistics_too_large:
      "The phone screen-time file exceeds the safety size limit.",
    usage_statistics_source_missing:
      "The phone statistics source is missing. Choose it again.",
    usage_statistics_source_changed:
      "The phone statistics source changed while it was read. Try again.",
    usage_statistics_cache_unavailable:
      "The local phone statistics cache is unavailable.",
    usage_statistics_not_configured:
      "No phone statistics file has been imported or linked.",
    usage_statistics_not_linked:
      "The current data is not a read-only link and cannot refresh from a source file.",
    update_not_configured:
      "This build has no trusted update endpoint or updater public key.",
    update_busy: "Another update operation is in progress.",
    update_not_available: "This update is no longer available. Check again.",
    update_version_changed:
      "The available version changed. Check again before installing.",
    update_check_failed:
      "DeskCubby could not check for updates. Check the network and try again.",
    update_install_failed:
      "The update could not be downloaded, verified or installed.",
    open_failed: "The link could not be opened in the system browser.",
    cancelled: "The operation was cancelled.",
    export_too_large: "The export is too large. Choose a shorter date range.",
    unexpected_error: "The operation failed. Please try again.",
  };
  return (language === "en" ? en : zh)[code] ??
    (language === "en" ? en.unexpected_error : zh.unexpected_error);
}
