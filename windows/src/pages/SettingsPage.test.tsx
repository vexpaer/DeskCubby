import { invoke } from "@tauri-apps/api/core";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, Link, RouterProvider } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { WindowsSettings } from "../lib/ipc";
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
  diaryDirectory: "D:\\DeskCubby\\Diary",
  mediaDirectory: "D:\\DeskCubby\\Media",
  backupDirectory: "D:\\DeskCubby\\Backup",
  fileNamePattern: "yyyy-MM-dd-custom",
  markdownTemplate: "# {title}\n\n",
  imageNamePattern: "{date}_{category}_{seq}",
  imageMaxWidthPx: 2560,
  imageMaxHeightPx: 2560,
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

  it("restores only the current page as a dirty draft until top-right save", async () => {
    const user = userEvent.setup();
    renderSettings();
    await screen.findByRole("heading", { name: "设置", level: 1 });
    await user.click(screen.getByRole("button", { name: /外观与语言/ }));

    expect(await screen.findByLabelText("视觉风格")).toHaveValue("LIQUID_GLASS");
    await user.click(screen.getByRole("button", { name: "恢复默认" }));

    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining("本页设置"));
    expect(screen.getByLabelText("视觉风格")).toHaveValue("MATERIAL");
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
});
