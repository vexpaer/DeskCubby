import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getActiveDiary, rememberActiveDiary } from "../lib/ipc";
import { useAppStore } from "../store/appStore";
import DiaryPage from "./DiaryPage";

const ENTRY = {
  relativePath: "2026/2026-07-29.md",
  fileName: "2026-07-29.md",
  title: "测试日记",
  date: "2026-07-29",
  month: "2026-07",
  excerpt: "原始正文",
  wordCount: 4,
  modifiedAt: "2026-07-29T04:00:00.000Z",
  trashed: false,
};

const VERSION = {
  sha256: "a".repeat(64),
  size: 12,
  modifiedAt: "2026-07-29T04:00:00.000Z",
};

const DOCUMENT = {
  entry: ENTRY,
  content: "# 测试日记\n\n原始正文",
  version: VERSION,
};

describe("DiaryPage file conflict flow", () => {
  const invokeMock = vi.mocked(invoke);
  let conflictReason: "changed" | "deleted";
  let openedDocument = DOCUMENT;

  beforeEach(() => {
    conflictReason = "changed";
    openedDocument = DOCUMENT;
    rememberActiveDiary(null);
    useAppStore.setState({
      appearance: {
        language: "zh-CN",
        visualTheme: "material",
        colorMode: "light",
        fontScale: 1,
        compactMode: false,
      },
      dirtyScopes: [],
    });
    invokeMock.mockReset();
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "list_diaries") {
        return [ENTRY] as never;
      }
      if (command === "rescan_diaries") {
        return (conflictReason === "deleted" ? [] : [ENTRY]) as never;
      }
      if (command === "open_diary") {
        return openedDocument as never;
      }
      if (command === "resolve_media_asset") {
        const source = (args as { source: string }).source;
        return `http://media.localhost/${source.replace(/^\.\//, "")}` as never;
      }
      if (command === "select_and_import_diary_image") {
        return {
          fileName: "lunch.jpg",
          markdown: "![午餐](lunch.jpg)",
          photo: null,
        } as never;
      }
      if (command === "save_diary") {
        const request = (
          args as {
            request: {
              content: string;
              resolution: "normal" | "overwrite" | "copy";
            };
          }
        ).request;
        if (request.resolution === "normal") {
          return {
            status: "conflict",
            currentVersion: {
              ...VERSION,
              sha256: "b".repeat(64),
              size: 24,
            },
            reason: conflictReason,
          } as never;
        }
        return {
          status: "saved",
          document: {
            ...DOCUMENT,
            content: request.content,
            version: {
              ...VERSION,
              sha256: "c".repeat(64),
              size: request.content.length,
            },
          },
        } as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });
  });

  it("passes local meal and ordinary images to the bounded media protocol in preview mode", async () => {
    openedDocument = {
      ...DOCUMENT,
      content:
        "# 测试日记\n\n![午餐](<lunch.jpg>)\n\n![随手拍](./snapshot.webp)",
    };
    const user = userEvent.setup();
    const router = createMemoryRouter(
      [{ path: "/diary", element: <DiaryPage /> }],
      { initialEntries: ["/diary?entry=2026-07-29.md"] },
    );
    render(<RouterProvider router={router} />);

    await user.click(await screen.findByRole("button", { name: "预览" }));

    const image = await screen.findByRole("img", { name: "午餐" });
    expect(image).toHaveAttribute("src", "http://media.localhost/lunch.jpg");
    expect(await screen.findByRole("img", { name: "随手拍" })).toHaveAttribute(
      "src",
      "http://media.localhost/snapshot.webp",
    );
    expect(invokeMock).toHaveBeenCalledWith("resolve_media_asset", {
      diaryRelativePath: "2026/2026-07-29.md",
      source: "lunch.jpg",
    });
    expect(invokeMock).toHaveBeenCalledWith("resolve_media_asset", {
      diaryRelativePath: "2026/2026-07-29.md",
      source: "./snapshot.webp",
    });
  });

  it("never overwrites an external edit until the user confirms", async () => {
    const user = userEvent.setup();
    const router = createMemoryRouter(
      [{ path: "/diary", element: <DiaryPage /> }],
      { initialEntries: ["/diary?entry=2026%2F2026-07-29.md"] },
    );
    render(<RouterProvider router={router} />);

    expect(
      await screen.findByRole("heading", { name: "测试日记", level: 2 }),
    ).toBeInTheDocument();
    expect(vi.mocked(listen)).toHaveBeenCalledWith(
      "diary-index-changed",
      expect.any(Function),
    );
    await waitFor(() => {
      expect(screen.getByLabelText("Markdown 日记正文")).toBeInTheDocument();
    });

    await user.click(screen.getByRole("button", { name: "导入图片" }));
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "保存" })).toBeEnabled();
    });
    await user.click(screen.getByRole("button", { name: "保存" }));

    const conflict = await screen.findByRole("alertdialog", {
      name: "检测到外部修改",
    });
    expect(conflict).toHaveTextContent("不会自动覆盖磁盘内容");
    expect(
      screen.getByRole("button", { name: "重新加载磁盘版本" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "另存冲突副本" }),
    ).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "确认覆盖" }));

    await waitFor(() => {
      const saveCalls = invokeMock.mock.calls.filter(
        ([command]) => command === "save_diary",
      );
      expect(saveCalls).toHaveLength(2);
      expect(
        (
          saveCalls[0][1] as {
            request: { resolution: string; expectedVersion: typeof VERSION };
          }
        ).request,
      ).toMatchObject({
        resolution: "normal",
        expectedVersion: VERSION,
      });
      expect(
        (saveCalls[1][1] as { request: { resolution: string } }).request
          .resolution,
      ).toBe("overwrite");
    });
    expect(await screen.findByText("日记已保存")).toBeInTheDocument();
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("accepts an external deletion without trying to reopen the missing file", async () => {
    conflictReason = "deleted";
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();
    const router = createMemoryRouter(
      [{ path: "/diary", element: <DiaryPage /> }],
      { initialEntries: ["/diary?entry=2026%2F2026-07-29.md"] },
    );
    render(<RouterProvider router={router} />);

    expect(
      await screen.findByRole("heading", { name: "测试日记", level: 2 }),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "导入图片" }));
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "保存" })).toBeEnabled();
    });
    await user.click(screen.getByRole("button", { name: "保存" }));

    const conflict = await screen.findByRole("alertdialog", {
      name: "文件已被外部删除",
    });
    expect(conflict).toHaveTextContent("文件已被外部删除");
    expect(conflict).toHaveTextContent("覆盖以重新创建文件");
    expect(
      screen.getByRole("button", { name: "另存冲突副本" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "确认覆盖" })).toBeInTheDocument();

    const openCountBeforeAccept = invokeMock.mock.calls.filter(
      ([command]) => command === "open_diary",
    ).length;
    await user.click(
      screen.getByRole("button", { name: "重新加载（接受删除）" }),
    );

    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining("接受删除会丢弃当前草稿并关闭编辑器"),
    );
    expect(
      await screen.findByRole("heading", { name: "选择一篇日记" }),
    ).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
      expect(router.state.location.search).toBe("");
      expect(getActiveDiary()).toBeNull();
    });
    const openCountAfterAccept = invokeMock.mock.calls.filter(
      ([command]) => command === "open_diary",
    ).length;
    expect(openCountAfterAccept).toBe(openCountBeforeAccept);
    expect(invokeMock).toHaveBeenCalledWith("rescan_diaries", undefined);
  });
});
