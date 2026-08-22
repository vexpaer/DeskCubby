/** Shared API types mirroring Android AppModels.kt / Entities.kt (camelCase). */

export type VisualStyle = "MATERIAL" | "LIQUID_GLASS" | "ORGANIC_FUTURE" | "CUSTOM";
export type DarkMode = "SYSTEM" | "LIGHT" | "DARK";
export type AppLanguage = "CHINESE" | "TRADITIONAL_CHINESE" | "ENGLISH" | "KOREAN" | "JAPANESE";

export interface CustomThemePalette {
  backgroundArgb: number;
  onBackgroundArgb: number;
  surfaceArgb: number;
  onSurfaceArgb: number;
  surfaceContainerArgb: number;
  surfaceVariantArgb: number;
  onSurfaceVariantArgb: number;
  outlineArgb: number;
}

export interface CustomThemeSettings {
  baseStyle: "MATERIAL" | "LIQUID_GLASS" | "ORGANIC_FUTURE";
  lightPalette: CustomThemePalette;
  darkPalette: CustomThemePalette;
  cornerRadiusDp: number;
  borderWidthDp: number;
  elevationDp: number;
  panelOpacity: number;
  spacingScale: number;
  animationScale: number;
}

export interface NavItemConfig {
  id: string;
  label: string;
  iconKey: string;
  visible: boolean;
  showInMore: boolean;
  moreDescription: string;
  moreButtonColorArgb: number | null;
  moreCardColorArgb: number | null;
}

export interface HomeGreetingTemplate {
  chinese: string;
  english: string;
}

export interface DailyEventTemplate {
  id: string;
  text: string;
  firstUnit: string;
  secondUnit: string;
}

export interface RssSubscription {
  id: string;
  title: string;
  url: string;
  enabled: boolean;
}

export interface AiModelConfig {
  id: string;
  name: string;
  type: "TEXT" | "IMAGE";
  endpointUrl: string;
  model: string;
  enabled: boolean;
  allowInsecureHttp: boolean;
  temperature: number;
  systemPrompt: string;
  apiKey: string;
  supportsToolCalling: boolean;
}

/** Mirrors Android CloudSyncModels.kt / backend routers/cloudsync.py ConfigBody. */
export interface CloudSyncConfig {
  id: string;
  name: string;
  enabled: boolean;
  serviceType: "WEBDAV" | "S3_COMPATIBLE";
  endpointUrl: string;
  remotePath?: string;
  userAgent?: string;
  webDavUsername?: string;
  s3Bucket?: string;
  s3Region?: string;
  s3PathStyle?: boolean;
  allowInsecureHttp?: boolean;
  selectedContents: string[];
  direction: "TWO_WAY" | "UPLOAD_ONLY";
  /** redacted projection flag: whether the server holds credentials */
  hasCredentials?: boolean;
  /** secrets are write-only; GET returns empty strings (留空保持不变) */
  webDavPassword?: string;
  s3AccessKey?: string;
  s3SecretKey?: string;
  s3SessionToken?: string;
}

export interface DesktopWidgetConfig {
  id: string;
  name: string;
  widthCells: number;
  heightCells: number;
  backgroundColorArgb: number;
  textColorArgb: number;
  backgroundImageUri: string | null;
  showName: boolean;
  backgroundOpacityPercent: number;
  showIcon: boolean;
  textAlignment: "START" | "CENTER" | "END";
  textScalePercent: number;
  cornerStyle: "ROUNDED" | "SQUARE";
  surfaceScalePercent: number;
  appIconScalePercent: number;
  contentType: "HOME_MODULE" | "APP_MODULE" | "APP_SHORTCUT";
  homeModuleId: string;
  appPackageName: string | null;
  appLabel: string | null;
  usageRangeDays: number;
}

export interface MealPhotoFilterSettings {
  enabled: boolean;
  brightness: number;
  contrast: number;
  saturation: number;
  warmth: number;
  tint: number;
}

/** Mirrors Android AppSettings. Secrets come back as empty strings. */
export interface AppSettings {
  visualStyle: VisualStyle;
  customTheme: CustomThemeSettings;
  darkMode: DarkMode;
  appLanguage: AppLanguage;
  orientationPreference: "AUTO" | "PORTRAIT" | "LANDSCAPE";
  userName: string;
  homeGreetings: HomeGreetingTemplate[];
  themeColorArgb: number;
  themeSecondaryColorsArgb: number[];
  fontScale: number;
  compactMode: boolean;
  backgroundImageUri: string | null;
  backgroundImageOpacity: number;
  backgroundImageBlurDp: number;
  tutorialModeEnabled: boolean;
  tutorialAcknowledgedPages: string[];
  useChineseLauncherName: boolean;
  launcherIcon: "CURRENT" | "MAGIC_BOOK" | "DESK_CUBBY";
  backupTreeUri: string | null;
  cloudSyncEnabled: boolean;
  cloudSyncConfigs: CloudSyncConfig[];
  diaryTreeUri: string | null;
  mediaTreeUri: string | null;
  notesTreeUri: string | null;
  fileNamePattern: string;
  markdownTemplate: string;
  imageNamePattern: string;
  imageMaxWidthDp: number;
  imageMaxHeightDp: number;
  markdownHeadingSizesSp: number[];
  mealImageCompressionEnabled: boolean;
  mealImageCompressionQuality: number;
  saveOriginalToGallery: boolean;
  photoLocationEnabled: boolean;
  browserHomeUrl: string;
  lastBrowserUrl: string | null;
  browserTheme: "SYSTEM" | "LIGHT" | "DARK";
  browserDesktopMode: boolean;
  thoughtSplitRatio: number;
  thoughtRowHeightDp: number;
  thoughtReopenMode: "LAST_VISITED" | "ALL";
  lastThoughtPageKey: string;
  thoughtDisplayMode: "SINGLE_LINE" | "FULL";
  thoughtHighlightColorArgb: number;
  thoughtEditorMaxHeightDp: number;
  vaultRowHeightDp: number;
  poetryFontUri: string | null;
  poetryFontSizeSp: number;
  poetryLineSpacing: number;
  poetryTextAlignment: "START" | "CENTER";
  poetryShowSource: boolean;
  poetryShowQuoteMark: boolean;
  poetrySevenCharacterWrapEnabled: boolean;
  mealCalendarImageMaxHeightDp: number;
  mealCalendarShowCaptions: boolean;
  mealCalendarWrapEnabled: boolean;
  mealCalendarPhotosPerRow: "TWO" | "THREE" | "SMART";
  mealPhotoFilter: MealPhotoFilterSettings;
  mealButtonsUseIcons: boolean;
  mealButtonIcons: string[];
  dailyEventTemplates: DailyEventTemplate[];
  structuredAutoRecordSleepWake: boolean;
  rssSubscriptions: RssSubscription[];
  rssMaxItemsPerFeed: number;
  rssShowSummaries: boolean;
  aiEndpointUrl: string;
  aiModel: string;
  aiSystemPrompt: string;
  aiTemperature: number;
  aiAllowInsecureHttp: boolean;
  aiConfigs: AiModelConfig[];
  aiChatConfigId: string | null;
  agentEnabledSources: string[];
  agentPermissionMode: "REQUIRE_APPROVAL" | "FULL_AUTO";
  aiPageFontSizeSp: number;
  aiReplyBoxWidthDp: number;
  agentPrompt: string;
  calorieEstimationEnabled: boolean;
  calorieTextConfigId: string | null;
  calorieImageConfigId: string | null;
  calorieVisionPrompt: string;
  calorieTextPrompt: string;
  usageTrackingEnabled: boolean;
  stepTrackingEnabled: boolean;
  navigationIntroAcknowledged: boolean;
  navItems: NavItemConfig[];
  morePageOrder: string[];
  morePageColumns: number;
  defaultPage: string;
  bottomNavShowLabels: boolean;
  musicVisualizerEnabled: boolean;
  musicVisualizerStyle: "BARS" | "WAVEFORM" | "CURVE";
  musicVisualizerFrequencyMode: "ADAPTIVE" | "MANUAL";
  musicVisualizerMinFrequencyHz: number;
  musicVisualizerMaxFrequencyHz: number;
  game2048AnimationSpeed: "SLOW" | "NORMAL" | "FAST";
  morePageShowDescriptions: boolean;
  homeWidgetBordersEnabled: boolean;
  homeWidgets: string[];
  homeGameShortcuts: string[];
  homeWidgetTitles: string[];
  desktopWidgetConfigs: DesktopWidgetConfig[];
}

export const MEAL_CATEGORIES = [
  { key: "breakfast", zh: "早餐", en: "Breakfast", icon: "🥪", sortOrder: 0 },
  { key: "lunch", zh: "午餐", en: "Lunch", icon: "🍱", sortOrder: 1 },
  { key: "afternoon_tea", zh: "下午茶", en: "Afternoon tea", icon: "🍹", sortOrder: 2 },
  { key: "dinner", zh: "晚餐", en: "Dinner", icon: "🍜", sortOrder: 3 },
  { key: "fruit", zh: "水果", en: "Fruit", icon: "🍊", sortOrder: 4 },
  { key: "late_snack", zh: "夜宵", en: "Late snack", icon: "🍤", sortOrder: 5 },
] as const;

export function argbToCss(argb: number): string {
  const v = argb >>> 0;
  const a = ((v >>> 24) & 0xff) / 255;
  const r = (v >>> 16) & 0xff;
  const g = (v >>> 8) & 0xff;
  const b = v & 0xff;
  if (a >= 1) return `rgb(${r},${g},${b})`;
  return `rgba(${r},${g},${b},${a.toFixed(3)})`;
}
