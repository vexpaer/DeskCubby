import {
  ArchiveRestore,
  Bot,
  BookOpenText,
  Cloud,
  FolderCog,
  HeartPulse,
  Home,
  Info,
  Languages,
  LayoutDashboard,
  LockKeyhole,
  Palette,
  Rss,
  Settings2,
  Sparkles,
  Timer,
  type LucideIcon,
} from "lucide-react";

export type SettingsPageId =
  | "main"
  | "appearance"
  | "subpages"
  | "home"
  | "diary-media"
  | "thoughts"
  | "poetry-meals"
  | "app-data"
  | "data-usage"
  | "cloud"
  | "vault"
  | "mobile-usage"
  | "rss"
  | "ai"
  | "health"
  | "navigation"
  | "about"
  | "updates";

export type SettingsTranslator = (chinese: string, english: string) => string;

export interface SettingsDestination {
  id: SettingsPageId;
  path: string;
  parent: SettingsPageId;
  chinese: string;
  english: string;
  chineseDescription: string;
  englishDescription: string;
  keywords: string;
  icon: LucideIcon;
}

export const SETTINGS_DESTINATIONS: readonly SettingsDestination[] = [
  {
    id: "appearance",
    path: "/settings/appearance",
    parent: "main",
    chinese: "外观与语言",
    english: "Appearance & language",
    chineseDescription: "界面风格、多色主题、字号、明暗模式和语言",
    englishDescription: "Style, theme colors, type size, dark mode and language",
    keywords: "外观 风格 主题 颜色 辅色 字号 明暗 紧凑 语言 material glass organic",
    icon: Palette,
  },
  {
    id: "subpages",
    path: "/settings/pages",
    parent: "main",
    chinese: "子页面设置",
    english: "Subpage settings",
    chineseDescription: "记录、阅读、订阅、AI、娱乐与只读手机数据",
    englishDescription: "Capture, reading, feeds, AI, games and read-only phone data",
    keywords: "子页面 页面 功能 阅读 订阅 ai 游戏 健康 统计 pages reader games health",
    icon: Settings2,
  },
  {
    id: "home",
    path: "/settings/home",
    parent: "subpages",
    chinese: "主页",
    english: "Home",
    chineseDescription: "问候、模块和首页快捷内容",
    englishDescription: "Greeting, widgets and home shortcuts",
    keywords: "主页 问候 用户名 模块 组件 首页 home greeting widget",
    icon: Home,
  },
  {
    id: "diary-media",
    path: "/settings/diary-media",
    parent: "subpages",
    chinese: "日记与媒体",
    english: "Diary & media",
    chineseDescription: "本地目录、文件名、模板、图片压缩与拍摄信息",
    englishDescription: "Local folders, file names, templates, compression and photo metadata",
    keywords: "日记 媒体 目录 文件名 模板 图片 压缩 exif diary media folder image",
    icon: BookOpenText,
  },
  {
    id: "thoughts",
    path: "/settings/thoughts",
    parent: "subpages",
    chinese: "小巧思",
    english: "Thoughts",
    chineseDescription: "内容显示、重点颜色与编辑区高度",
    englishDescription: "Content display, highlight color and editor height",
    keywords: "小巧思 内容 单行 完整 重点 高亮 编辑区 thought highlight editor",
    icon: Sparkles,
  },
  {
    id: "poetry-meals",
    path: "/settings/poetry-meals",
    parent: "subpages",
    chinese: "诗词本与吃历",
    english: "Poetry & meal calendar",
    chineseDescription: "诗词排版、吃历图片与智能换行",
    englishDescription: "Poetry layout, meal photos and smart wrapping",
    keywords: "诗词 字号 行距 对齐 出处 吃历 图片 换行 poetry meal calendar",
    icon: Languages,
  },
  {
    id: "vault",
    path: "/settings/vault",
    parent: "subpages",
    chinese: "收藏夹",
    english: "Vault",
    chineseDescription: "密码保护的私密收藏与锁定状态",
    englishDescription: "Password-protected private items and lock state",
    keywords: "收藏夹 私密 密码 加密 锁定 vault password encryption",
    icon: LockKeyhole,
  },
  {
    id: "mobile-usage",
    path: "/settings/mobile-usage",
    parent: "subpages",
    chinese: "手机使用时间",
    english: "Phone screen time",
    chineseDescription: "只显示手机同步的数据，不统计 Windows 使用时间",
    englishDescription: "Displays synced phone data without tracking Windows usage",
    keywords: "手机 使用 时间 时长 统计 显示 同步 screen time usage phone",
    icon: Timer,
  },
  {
    id: "rss",
    path: "/settings/rss",
    parent: "subpages",
    chinese: "RSS 订阅",
    english: "RSS",
    chineseDescription: "每个订阅的文章数量与摘要显示",
    englishDescription: "Article limit and summary display for each feed",
    keywords: "rss 订阅 摘要 文章 数量 feed summary",
    icon: Rss,
  },
  {
    id: "ai",
    path: "/settings/ai",
    parent: "subpages",
    chinese: "AI 配置",
    english: "AI configurations",
    chineseDescription: "兼容接口、模型、系统提示词与 API Key",
    englishDescription: "Compatible endpoints, models, system prompts and API keys",
    keywords: "ai 模型 接口 endpoint model api key 提示词 prompt",
    icon: Bot,
  },
  {
    id: "health",
    path: "/settings/health",
    parent: "subpages",
    chinese: "健康",
    english: "Health",
    chineseDescription: "只显示手机健康数据，不在 Windows 采集",
    englishDescription: "Displays phone health data without Windows collection",
    keywords: "健康 步数 距离 热量 只读 health steps distance calories",
    icon: HeartPulse,
  },
  {
    id: "app-data",
    path: "/settings/app-data",
    parent: "main",
    chinese: "应用数据",
    english: "App data",
    chineseDescription: "自动备份、JSON 导入导出、WebDAV 与 S3",
    englishDescription: "Automatic backup, JSON import/export, WebDAV and S3",
    keywords: "应用 数据 备份 导入 导出 json 云端 同步 webdav s3 backup cloud",
    icon: ArchiveRestore,
  },
  {
    id: "cloud",
    path: "/settings/cloud",
    parent: "app-data",
    chinese: "云端同步",
    english: "Cloud sync",
    chineseDescription: "WebDAV 与 S3 服务、同步内容和状态",
    englishDescription: "WebDAV and S3 services, synced content and status",
    keywords: "云端 同步 webdav s3 服务 凭据 cloud sync",
    icon: Cloud,
  },
  {
    id: "data-usage",
    path: "/settings/data-usage",
    parent: "app-data",
    chinese: "数据占用",
    english: "Storage usage",
    chineseDescription: "查看本机数据库、缓存与用户所选目录的占用说明",
    englishDescription: "Review local database, cache and selected-folder storage guidance",
    keywords: "数据 占用 空间 存储 缓存 数据库 storage usage cache database",
    icon: FolderCog,
  },
  {
    id: "navigation",
    path: "/settings/navigation",
    parent: "main",
    chinese: "桌面导航",
    english: "Desktop navigation",
    chineseDescription: "侧栏、窄窗口菜单与页面入口",
    englishDescription: "Sidebar, compact-window menu and page entries",
    keywords: "导航 侧栏 菜单 页面 桌面 navigation sidebar menu",
    icon: LayoutDashboard,
  },
  {
    id: "about",
    path: "/settings/about",
    parent: "main",
    chinese: "关于",
    english: "About",
    chineseDescription: "版本、应用教学与更新",
    englishDescription: "Version, tutorial and updates",
    keywords: "关于 版本 教学 github 更新 about version tutorial update",
    icon: Info,
  },
  {
    id: "updates",
    path: "/settings/updates",
    parent: "about",
    chinese: "检查更新",
    english: "Check for updates",
    chineseDescription: "查看自动更新和发布签名状态",
    englishDescription: "Review automatic updates and release signing status",
    keywords: "检查 更新 自动 下载 安装 签名 update download install signing",
    icon: FolderCog,
  },
] as const;

export const MAIN_DESTINATIONS: readonly SettingsPageId[] = [
  "appearance",
  "subpages",
  "app-data",
  "navigation",
  "about",
];

export const SUBPAGE_DESTINATIONS: readonly SettingsPageId[] = [
  "home",
  "diary-media",
  "thoughts",
  "poetry-meals",
  "vault",
  "rss",
  "ai",
  "mobile-usage",
  "health",
];

export function destinationFor(id: SettingsPageId): SettingsDestination | undefined {
  return SETTINGS_DESTINATIONS.find((entry) => entry.id === id);
}

export function pathForSettingsPage(id: SettingsPageId): string {
  return id === "main" ? "/settings" : destinationFor(id)?.path ?? "/settings";
}

export function pageForSettingsPath(pathname: string): SettingsPageId {
  const normalized = pathname.replace(/\/+$/, "") || "/";
  if (normalized === "/settings") return "main";
  return (
    SETTINGS_DESTINATIONS.find((entry) => entry.path === normalized)?.id ?? "main"
  );
}

export function parentForSettingsPage(id: SettingsPageId): SettingsPageId {
  return destinationFor(id)?.parent ?? "main";
}
