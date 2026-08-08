import { invoke } from "@tauri-apps/api/core";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, Link, RouterProvider } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  createDefaultDesktopNavigationPreferences,
  MAX_DESKTOP_NAVIGATION_CATEGORIES,
} from "../components";
import type { WindowsSettings } from "../lib/ipc";
import { useAppStore } from "../store/appStore";
import SettingsPage from "./SettingsPage";

const SAVED_SETTINGS = {
  visualStyle: "LIQUID_GLASS",
  darkMode: "SYSTEM",
  appLanguage: "CHINESE",
  themeColorArgb: 0xff42664d | 0,
  themeSecondaryColorsArgb: [
    0xffc96f4a | 0,
    0xffd4a72c | 0,
    0xff527f91 | 0,
  ],
  fontScale: 1,
  compactMode: false,
  backgroundImagePath: null,
  backgroundImageOpacity: 0.45,
  backgroundImageBlurPx: 0,
  tutorialModeEnabled: true,
  diaryDirectory: "D:\\DeskCubby\\Diary",
  mediaDirectory: "D:\\DeskCubby\\Media",
  backupDirectory: "D:\\DeskCubby\\Backup",
  fileNamePattern: "yyyy-MM-dd-custom",
  markdownTemplate: "# {title}\n\n",
  imageNamePattern: "{date}_{category}_{seq}",
  imageMaxWidthPx: 2560,
  imageMaxHeightPx: 2560,
  markdownHeadingSizesSp: [32, 28, 24, 21, 19, 17],
  mealImageCompressionEnabled: true,
  mealImageCompressionQuality: 80,
  photoLocationEnabled: false,
  thoughtDisplayMode: "SINGLE_LINE",
  thoughtHighlightColorArgb: 0xfff6e3a1 | 0,
  thoughtEditorMaxHeightPx: 168,
  vaultRowHeightDp: 64,
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
  mealButtonIcons: ["", "", "", "", "", ""],
  userName: "测试用户",
  homeGreetings: [
    { chinese: "你好，{name}", english: "Hello, {name}" },
  ],
  homeWidgetBordersEnabled: true,
  homeWidgets: ["TODAY_DIARY", "THOUGHTS"],
  homeGameShortcuts: ["MINESWEEPER"],
  homeWidgetTitles: [],
} as const satisfies WindowsSettings;

const DEFAULT_SETTINGS = {
  ...SAVED_SETTINGS,
  visualStyle: "MATERIAL",
  diaryDirectory: null,
  mediaDirectory: null,
  backupDirectory: null,
  fileNamePattern: "yyyy-MM-dd",
} as const satisfies WindowsSettings;

function renderSettings(initialEntries: string[] = ["/settings"]) {
  const router = createMemoryRouter(
    [
      {
        path: "/settings/*",
        element: (
          <>
            <Link to="/previous">模拟侧栏首页</Link>
            <SettingsPage />
          </>
        ),
      },
      { path: "/previous", element: <p>上一页</p> },
      { path: "/backup", element: <p>备份管理</p> },
    ],
    {
      initialEntries,
      initialIndex: initialEntries.length - 1,
    },
  );
  render(<RouterProvider router={router} />);
  return router;
}

describe("SettingsPage", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => {
    document.documentElement.lang = "zh-CN";
    invokeMock.mockReset();
    vi.spyOn(window, "confirm").mockReturnValue(true);
    useAppStore.setState({
      desktopNavigation: createDefaultDesktopNavigationPreferences(),
      collapsedNavigationCategoryIds: [],
    });
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "get_windows_settings") {
        return { ...SAVED_SETTINGS } as never;
      }
      if (command === "get_default_windows_settings") {
        return { ...DEFAULT_SETTINGS } as never;
      }
      if (command === "update_windows_settings") {
        const settings = (args as { settings: typeof SAVED_SETTINGS }).settings;
        return { ...settings } as never;
      }
      if (command === "select_directory") {
        return "E:\\DeskCubby\\Selected" as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });
  });

  it("uses the Android-like settings home and searches directly into a subpage", async () => {
    const user = userEvent.setup();
    renderSettings();

    expect(
      await screen.findByRole("heading", { name: "设置", level: 1 }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /外观与语言/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /子页面设置/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /应用数据/ })).toBeInTheDocument();
    expect(screen.queryByLabelText("视觉风格")).not.toBeInTheDocument();

    await user.type(screen.getByRole("searchbox", { name: "搜索设置" }), "备份");
    await user.click(screen.getByRole("button", { name: /应用数据/ }));

    expect(
      await screen.findByRole("heading", { name: "应用数据", level: 1 }),
    ).toBeInTheDocument();
    expect(screen.getByText("D:\\DeskCubby\\Backup")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "打开备份管理" })).toHaveAttribute(
      "href",
      "/backup",
    );
  });

  it("matches Android subpage settings without exposing excluded Windows pages", async () => {
    const user = userEvent.setup();
    renderSettings();
    await screen.findByRole("heading", { name: "设置", level: 1 });

    await user.click(screen.getByRole("button", { name: /子页面设置/ }));

    expect(await screen.findByRole("button", { name: /RSS 订阅/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /AI 配置/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /健康/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /浏览器/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /桌面小卡片/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^阅读/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^笔记/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /健康/ }));
    expect(
      await screen.findByRole("heading", { name: "健康", level: 1 }),
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "查看健康数据" })).toHaveAttribute(
      "href",
      "/health",
    );
  });

  it("restores only the current page as a dirty draft until top-right save", async () => {
    const user = userEvent.setup();
    renderSettings();
    await screen.findByRole("heading", { name: "设置", level: 1 });
    await user.click(screen.getByRole("button", { name: /外观与语言/ }));

    expect(await screen.findByLabelText("视觉风格")).toHaveValue("LIQUID_GLASS");
    expect(screen.getByRole("checkbox", { name: "启用应用教学" })).toBeChecked();
    await user.click(screen.getByRole("button", { name: "恢复默认" }));

    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining("本页设置"));
    expect(screen.getByLabelText("视觉风格")).toHaveValue("MATERIAL");
    expect(screen.getByRole("checkbox", { name: "启用应用教学" })).toBeChecked();
    expect(
      screen.getByText("本页有未保存的修改。离开页面或关闭窗口前会询问你。"),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "保存" })).toBeEnabled();

    await user.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => {
      const updateCall = invokeMock.mock.calls.find(
        ([command]) => command === "update_windows_settings",
      );
      const settings = (updateCall?.[1] as { settings: WindowsSettings }).settings;
      expect(settings.visualStyle).toBe("MATERIAL");
      expect(settings.diaryDirectory).toBe("D:\\DeskCubby\\Diary");
      expect(settings.fileNamePattern).toBe("yyyy-MM-dd-custom");
    });
    expect(await screen.findByText("设置已保存。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "已保存" })).toBeDisabled();
  });

  it("keeps the tutorial master switch in the appearance draft until save", async () => {
    const user = userEvent.setup();
    renderSettings(["/settings/appearance"]);
    await screen.findByRole("heading", { name: "外观与语言", level: 1 });

    const tutorialSwitch = screen.getByRole("checkbox", { name: "启用应用教学" });
    expect(tutorialSwitch).toBeChecked();
    await user.click(tutorialSwitch);

    expect(tutorialSwitch).not.toBeChecked();
    expect(screen.getByRole("button", { name: "保存" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => {
      const updateCall = invokeMock.mock.calls.find(
        ([command]) => command === "update_windows_settings",
      );
      expect(
        (updateCall?.[1] as { settings: WindowsSettings }).settings
          .tutorialModeEnabled,
      ).toBe(false);
    });
  });

  it("persists the active subpage draft with Ctrl+S", async () => {
    const user = userEvent.setup();
    renderSettings(["/settings/appearance"]);
    await screen.findByRole("heading", { name: "外观与语言", level: 1 });

    await user.selectOptions(screen.getByLabelText("明暗模式"), "DARK");
    expect(screen.getByRole("button", { name: "保存" })).toBeEnabled();
    await user.keyboard("{Control>}s{/Control}");

    await waitFor(() => {
      const updateCall = invokeMock.mock.calls.find(
        ([command]) => command === "update_windows_settings",
      );
      expect(
        (updateCall?.[1] as { settings: WindowsSettings }).settings.darkMode,
      ).toBe("DARK");
    });
  });

  it("blocks parent, history and sidebar navigation while a subpage is dirty", async () => {
    const user = userEvent.setup();
    const router = renderSettings(["/previous", "/settings/appearance"]);
    await screen.findByRole("heading", { name: "外观与语言", level: 1 });

    await user.selectOptions(screen.getByLabelText("明暗模式"), "DARK");
    await user.click(screen.getByRole("button", { name: "返回上一级设置" }));
    expect(
      await screen.findByRole("heading", { name: "放弃未保存的更改？" }),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "取消" }));

    await act(async () => {
      await router.navigate(-1);
    });
    expect(
      await screen.findByRole("heading", { name: "放弃未保存的更改？" }),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "取消" }));

    await user.click(screen.getByRole("link", { name: "模拟侧栏首页" }));
    expect(
      await screen.findByRole("heading", { name: "放弃未保存的更改？" }),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "放弃更改" }));
    expect(await screen.findByText("上一页")).toBeInTheDocument();
  });

  it("keeps Windows directory selection inside the diary subpage draft", async () => {
    const user = userEvent.setup();
    renderSettings(["/settings/diary-media"]);
    await screen.findByRole("heading", { name: "日记与媒体", level: 1 });

    await user.click(screen.getAllByRole("button", { name: "选择文件夹" })[0]);
    expect(await screen.findByText("E:\\DeskCubby\\Selected")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "保存" })).toBeEnabled();

    await user.click(screen.getByRole("button", { name: "保存" }));
    await waitFor(() => {
      const updateCall = invokeMock.mock.calls.find(
        ([command]) => command === "update_windows_settings",
      );
      expect(
        (updateCall?.[1] as { settings: WindowsSettings }).settings.diaryDirectory,
      ).toBe("E:\\DeskCubby\\Selected");
    });
  });

  it("keeps desktop navigation edits as a local draft until top-right save", async () => {
    const user = userEvent.setup();
    renderSettings(["/settings/navigation"]);
    await screen.findByRole("heading", { name: "桌面导航", level: 1 });

    const chineseName = screen.getByLabelText("分类 1 中文名称");
    await user.clear(chineseName);
    await user.type(chineseName, "我的记录");
    await user.click(screen.getByRole("checkbox", { name: "在侧栏显示日记" }));
    await user.click(screen.getByRole("button", { name: "下移首页" }));
    await user.click(screen.getByRole("button", { name: "新建分类" }));
    expect(screen.getByDisplayValue("新分类")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "删除分类 新分类" }));

    const beforeSave = useAppStore.getState().desktopNavigation;
    expect(beforeSave.categories[0].chinese).toBe("记录");
    expect(beforeSave.hiddenItemIds).not.toContain("diary");
    expect(screen.getByRole("button", { name: "保存" })).toBeEnabled();

    await user.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => {
      const saved = useAppStore.getState().desktopNavigation;
      expect(saved.categories[0].chinese).toBe("我的记录");
      expect(saved.categories[0].itemIds.slice(0, 2)).toEqual(["diary", "home"]);
      expect(saved.hiddenItemIds).toContain("diary");
    });
    expect(
      invokeMock.mock.calls.some(
        ([command]) => command === "update_windows_settings",
      ),
    ).toBe(false);
    expect(screen.getByRole("button", { name: "已保存" })).toBeDisabled();
  });

  it("never allows the final navigation category to be deleted", async () => {
    const preferences = createDefaultDesktopNavigationPreferences();
    preferences.categories = [
      {
        id: "only",
        chinese: "唯一分类",
        english: "Only category",
        itemIds: preferences.categories.flatMap((category) => category.itemIds),
      },
    ];
    useAppStore.setState({ desktopNavigation: preferences });

    renderSettings(["/settings/navigation"]);

    expect(
      await screen.findByRole("button", { name: "删除分类 唯一分类" }),
    ).toBeDisabled();
    expect(screen.getByRole("checkbox", { name: "在侧栏显示首页" })).toBeChecked();
  });

  it("disables category creation with a clear hint at the persisted limit", async () => {
    const preferences = createDefaultDesktopNavigationPreferences();
    preferences.categories = Array.from(
      { length: MAX_DESKTOP_NAVIGATION_CATEGORIES },
      (_, index) => ({
        id: `category-${index + 1}`,
        chinese: `分类 ${index + 1}`,
        english: `Category ${index + 1}`,
        itemIds:
          index === 0
            ? preferences.categories.flatMap((category) => category.itemIds)
            : [],
      }),
    );
    useAppStore.setState({ desktopNavigation: preferences });

    renderSettings(["/settings/navigation"]);

    expect(
      await screen.findByRole("button", { name: "已达 32 个分类上限" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "已达 32 个分类上限" }),
    ).toHaveAttribute("title", "最多可创建 32 个分类");
  });
});
