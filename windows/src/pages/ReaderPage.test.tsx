import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type {
  ReaderDocumentV1,
  ReaderLibraryV1,
  ReaderPreferencesV1,
} from "../lib/readerApi";
import { useAppStore } from "../store/appStore";
import ReaderPage from "./ReaderPage";

const PREFERENCES: ReaderPreferencesV1 = {
  background: "paper",
  customBackgroundArgb: -724762,
  fontSizePx: 19,
  lineHeightMultiplier: 1.6,
  paragraphSpacingPx: 10,
  pdfZoomPercent: 100,
  chapterDetectionMode: "smartAndCustom",
  customChapterRegex: "",
  chapterHeadingMaxChars: 160,
};

const TEXT_BOOK = {
  dtoVersion: 1,
  id: "11111111-1111-4111-8111-111111111111",
  title: "示例小说",
  bookType: "txt",
  addedAt: 1_786_089_600_000,
  lastOpenedAt: 1_786_089_600_000,
  textParagraphIndex: 0,
  textPageIndex: 0,
  pdfPageIndex: 0,
  readingMillis: "60000",
} as const;

const PDF_BOOK = {
  dtoVersion: 1,
  id: "22222222-2222-4222-8222-222222222222",
  title: "研究报告",
  bookType: "pdf",
  addedAt: 1_786_089_600_000,
  lastOpenedAt: 1_786_089_600_000,
  textParagraphIndex: 0,
  textPageIndex: 0,
  pdfPageIndex: 4,
  readingMillis: "0",
} as const;

const EMPTY_LIBRARY: ReaderLibraryV1 = {
  dtoVersion: 1,
  books: [],
  preferences: PREFERENCES,
  totalReadingMillis: "0",
};

const TEXT_LIBRARY: ReaderLibraryV1 = {
  dtoVersion: 1,
  books: [TEXT_BOOK],
  preferences: PREFERENCES,
  totalReadingMillis: "60000",
};

const TEXT_DOCUMENT: ReaderDocumentV1 = {
  dtoVersion: 1,
  book: TEXT_BOOK,
  preferences: PREFERENCES,
  kind: "txt",
  pages: [
    { text: "第一章 Alpha\n\nAlpha beta", firstParagraphIndex: 0 },
    { text: "第二章\n\nlater alpha", firstParagraphIndex: 2 },
  ],
  chapters: [
    { title: "第一章 Alpha", pageIndex: 0, paragraphIndex: 0 },
    { title: "第二章", pageIndex: 1, paragraphIndex: 2 },
  ],
};

function renderReader() {
  const router = createMemoryRouter(
    [
      { path: "/reader", element: <ReaderPage /> },
      { path: "/elsewhere", element: <p>Elsewhere</p> },
    ],
    { initialEntries: ["/reader"] },
  );
  return { router, ...render(<RouterProvider router={router} />) };
}

describe("ReaderPage", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => {
    useAppStore.setState((state) => ({
      ...state,
      appearance: { ...state.appearance, language: "zh-CN" },
      dirtyScopes: [],
    }));
    invokeMock.mockReset();
  });

  it("imports an explicitly selected TXT and never exposes a path in the library", async () => {
    const user = userEvent.setup();
    let libraryReads = 0;
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_reader_library") {
        libraryReads += 1;
        return (libraryReads === 1 ? EMPTY_LIBRARY : TEXT_LIBRARY) as never;
      }
      if (command === "choose_reader_book") return TEXT_DOCUMENT as never;
      if (command === "save_reader_progress") return TEXT_BOOK as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    renderReader();
    expect(await screen.findByText("书库还是空的")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "打开 TXT / PDF" }));

    expect((await screen.findAllByText("第一章 Alpha")).length).toBeGreaterThan(0);
    expect(invokeMock).toHaveBeenCalledWith("choose_reader_book", undefined);
    expect(screen.queryByText(/^[A-Z]:\\/)).not.toBeInTheDocument();
    expect(screen.queryByText(/private/i)).not.toBeInTheDocument();
  });

  it("searches the whole TXT, jumps logical pages, copies, and persists progress", async () => {
    const user = userEvent.setup();
    const clipboardWrite = vi.spyOn(navigator.clipboard, "writeText");
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "get_reader_library") return TEXT_LIBRARY as never;
      if (command === "open_reader_book") return TEXT_DOCUMENT as never;
      if (command === "save_reader_progress") {
        const request = (args as { request: { pageIndex: number; paragraphIndex: number } }).request;
        return {
          ...TEXT_BOOK,
          textPageIndex: request.pageIndex,
          textParagraphIndex: request.paragraphIndex,
        } as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });

    renderReader();
    await user.click(await screen.findByRole("button", { name: "打开《示例小说》" }));
    await user.click(await screen.findByRole("button", { name: "全文搜索" }));
    await user.type(screen.getByPlaceholderText("搜索整本 TXT"), "alpha");
    expect(await screen.findByText("1 / 3")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "下一个结果" }));
    await user.click(screen.getByRole("button", { name: "下一个结果" }));
    expect(await screen.findByText(/第 2 \/ 2 逻辑页/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "复制当前页" }));
    expect(clipboardWrite).toHaveBeenCalledWith("第二章\n\nlater alpha");
    expect(await screen.findByText("当前逻辑页已复制。")).toBeInTheDocument();

    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("save_reader_progress", {
        request: {
          dtoVersion: 1,
          bookId: TEXT_BOOK.id,
          pageIndex: 1,
          paragraphIndex: 2,
        },
      });
    });
  });

  it("uses only an opaque reader URL for continuous native PDF viewing", async () => {
    const user = userEvent.setup();
    const library: ReaderLibraryV1 = {
      ...EMPTY_LIBRARY,
      books: [PDF_BOOK],
    };
    const document: ReaderDocumentV1 = {
      dtoVersion: 1,
      book: PDF_BOOK,
      preferences: { ...PREFERENCES, pdfZoomPercent: 150 },
      kind: "pdf",
      assetUrl: `http://reader.localhost/${PDF_BOOK.id}.pdf`,
    };
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_reader_library") return library as never;
      if (command === "open_reader_book") return document as never;
      if (command === "save_reader_progress") return PDF_BOOK as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    renderReader();
    await user.click(await screen.findByRole("button", { name: "打开《研究报告》" }));
    const frame = await screen.findByTitle("研究报告 PDF 连续阅读");
    expect(frame).toHaveAttribute(
      "src",
      `http://reader.localhost/${PDF_BOOK.id}.pdf#page=5&zoom=150`,
    );
    expect(screen.getByText(/Ctrl\+F 搜索/)).toBeInTheDocument();
    expect(frame.getAttribute("src")).not.toContain("C:");
  });

  it("restores a settings draft and saves it only after the top-right action", async () => {
    const user = userEvent.setup();
    const customized: ReaderLibraryV1 = {
      ...EMPTY_LIBRARY,
      preferences: { ...PREFERENCES, fontSizePx: 28 },
    };
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "get_reader_library") return customized as never;
      if (command === "save_reader_preferences") {
        return {
          ...EMPTY_LIBRARY,
          preferences: (args as { request: { preferences: ReaderPreferencesV1 } }).request.preferences,
        } as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });

    renderReader();
    await user.click(await screen.findByRole("button", { name: "阅读设置" }));
    await user.click(screen.getByRole("button", { name: "恢复默认" }));
    expect(invokeMock).not.toHaveBeenCalledWith("save_reader_preferences", expect.anything());
    await user.click(screen.getByRole("button", { name: "保存" }));
    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("save_reader_preferences", {
        request: {
          dtoVersion: 1,
          preferences: PREFERENCES,
        },
      });
    });
  });

  it("delegates custom-regex validation to the Rust-compatible backend", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "get_reader_library") return EMPTY_LIBRARY as never;
      if (command === "save_reader_preferences") {
        return {
          ...EMPTY_LIBRARY,
          preferences: (args as { request: { preferences: ReaderPreferencesV1 } }).request.preferences,
        } as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });

    renderReader();
    await user.click(await screen.findByRole("button", { name: "阅读设置" }));
    const regex = screen.getByPlaceholderText("例如：Scene\\s+\\d+");
    await user.type(regex, "(?i)^chapter\\s+\\d+$");
    await user.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("save_reader_preferences", {
        request: {
          dtoVersion: 1,
          preferences: {
            ...PREFERENCES,
            customChapterRegex: "(?i)^chapter\\s+\\d+$",
          },
        },
      });
    });
  });

  it("redacts backend messages that contain an absolute path or book text", async () => {
    invokeMock.mockRejectedValue({
      code: "reader_state_corrupt",
      message: "C:\\Users\\private\\reader-state-v1.json contained secret book text",
    });

    renderReader();
    expect(await screen.findByText(/阅读状态文件已损坏/)).toBeInTheDocument();
    expect(screen.queryByText(/C:\\Users/)).not.toBeInTheDocument();
    expect(screen.queryByText(/secret book text/)).not.toBeInTheDocument();
  });
});
