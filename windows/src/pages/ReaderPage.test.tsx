import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type {
  ReaderDocumentV3,
  ReaderLibraryV3,
  ReaderPreferencesV3,
} from "../lib/readerApi";
import { useAppStore } from "../store/appStore";
import ReaderPage from "./ReaderPage";

// The in-app React-PDF viewer needs a real canvas and PDF.js worker, which jsdom
// does not provide. The URL-boundary behavior of ReaderPage is asserted against
// a mock of the viewer component; pdf.js itself is mocked in
// ReaderPage.pdfViewer.test.tsx.
vi.mock("./readerPdfViewer", () => ({
  default: ({
    assetUrl,
    pageIndex,
    zoomPercent,
    background,
    foreground,
    colorMode,
    scrollMode,
  }: {
    assetUrl: string;
    pageIndex: number;
    zoomPercent: number;
    background?: string;
    foreground?: string;
    colorMode: string;
    scrollMode: string;
  }) => (
    <div
      data-testid="reader-pdf-viewer"
      data-url={assetUrl}
      data-page-index={pageIndex}
      data-zoom={zoomPercent}
      data-background={background}
      data-foreground={foreground}
      data-color-mode={colorMode}
      data-scroll-mode={scrollMode}
    />
  ),
}));

const PREFERENCES: ReaderPreferencesV3 = {
  background: "paper",
  customBackgroundArgb: -724762,
  customForegroundArgb: null,
  fontSizePx: 19,
  fontFamily: "serif",
  lineHeightMultiplier: 1.6,
  paragraphSpacingPx: 10,
  contentWidthPx: 960,
  textAlignment: "start",
  firstLineIndentEm: 0,
  letterSpacingPx: 0,
  pagePaddingPx: 36,
  pdfZoomPercent: 100,
  pdfColorMode: "original",
  pdfScrollMode: "continuous",
  pdfPageGapPx: 18,
  immersiveMode: false,
  showProgressPercentage: false,
  libraryLayout: "list",
  showGridBookTitles: true,
  chapterDetectionMode: "smartAndCustom",
  customChapterRegex: "",
  chapterHeadingMaxChars: 160,
};

const TEXT_BOOK = {
  dtoVersion: 3,
  id: "11111111-1111-4111-8111-111111111111",
  title: "示例小说",
  bookType: "txt",
  addedAt: 1_786_089_600_000,
  lastOpenedAt: 1_786_089_600_000,
  textParagraphIndex: 0,
  textPageIndex: 0,
  pdfPageIndex: 0,
  totalPages: 2,
  readingMillis: "60000",
} as const;

const PDF_BOOK = {
  dtoVersion: 3,
  id: "22222222-2222-4222-8222-222222222222",
  title: "研究报告",
  bookType: "pdf",
  addedAt: 1_786_089_600_000,
  lastOpenedAt: 1_786_089_600_000,
  textParagraphIndex: 0,
  textPageIndex: 0,
  pdfPageIndex: 4,
  totalPages: 12,
  readingMillis: "0",
} as const;

const EMPTY_LIBRARY: ReaderLibraryV3 = {
  dtoVersion: 3,
  books: [],
  preferences: PREFERENCES,
  totalReadingMillis: "0",
};

const TEXT_LIBRARY: ReaderLibraryV3 = {
  dtoVersion: 3,
  books: [TEXT_BOOK],
  preferences: PREFERENCES,
  totalReadingMillis: "60000",
};

const TEXT_DOCUMENT: ReaderDocumentV3 = {
  dtoVersion: 3,
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
    expect(await screen.findByRole("spinbutton", { name: "页码" })).toHaveValue(2);

    await user.click(screen.getByRole("button", { name: "复制当前页" }));
    expect(clipboardWrite).toHaveBeenCalledWith("第二章\n\nlater alpha");
    expect(await screen.findByText("当前逻辑页已复制。")).toBeInTheDocument();

    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("save_reader_progress", {
        request: {
          dtoVersion: 3,
          bookId: TEXT_BOOK.id,
          pageIndex: 1,
          paragraphIndex: 2,
        },
      });
    });
  });

  it("renders PDFs through the in-app viewer using only an opaque reader URL", async () => {
    const user = userEvent.setup();
    const library: ReaderLibraryV3 = {
      ...EMPTY_LIBRARY,
      books: [PDF_BOOK],
    };
    const document: ReaderDocumentV3 = {
      dtoVersion: 3,
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
    const viewer = await screen.findByTestId("reader-pdf-viewer");
    // The viewer receives the restricted URL only — no #page/zoom fragment and
    // no filesystem path, so pdf.js can fetch pages through the Rust protocol.
    expect(viewer).toHaveAttribute(
      "data-url",
      `http://reader.localhost/${PDF_BOOK.id}.pdf`,
    );
    expect(viewer).toHaveAttribute("data-page-index", "4");
    expect(viewer).toHaveAttribute("data-zoom", "150");
    expect(viewer).toHaveAttribute("data-background", "#f4f0e6");
    expect(viewer).toHaveAttribute("data-foreground", "#29261f");
    expect(viewer).toHaveAttribute("data-color-mode", "original");
    expect(viewer).toHaveAttribute("data-scroll-mode", "continuous");
    expect(screen.getByText("150%")).toBeInTheDocument();
    expect(viewer.getAttribute("data-url")).not.toContain("C:");
    expect(viewer.getAttribute("data-url")).not.toContain("#page");
  });

  it("restores a settings draft and saves it only after the top-right action", async () => {
    const user = userEvent.setup();
    const customized: ReaderLibraryV3 = {
      ...EMPTY_LIBRARY,
      preferences: { ...PREFERENCES, fontSizePx: 28 },
    };
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "get_reader_library") return customized as never;
      if (command === "save_reader_preferences") {
        return {
          ...EMPTY_LIBRARY,
          preferences: (args as { request: { preferences: ReaderPreferencesV3 } }).request.preferences,
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
          dtoVersion: 3,
          preferences: PREFERENCES,
        },
      });
    });
  });

  it("persists the expanded desktop reading controls and starts in focus mode", async () => {
    const user = userEvent.setup();
    let savedPreferences = PREFERENCES;
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "get_reader_library") {
        return { ...TEXT_LIBRARY, preferences: savedPreferences } as never;
      }
      if (command === "save_reader_preferences") {
        savedPreferences = (args as { request: { preferences: ReaderPreferencesV3 } }).request.preferences;
        return { ...TEXT_LIBRARY, preferences: savedPreferences } as never;
      }
      if (command === "open_reader_book") {
        return { ...TEXT_DOCUMENT, preferences: savedPreferences } as never;
      }
      if (command === "save_reader_progress") return TEXT_BOOK as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    renderReader();
    await user.click(await screen.findByRole("button", { name: "阅读设置" }));
    await user.selectOptions(screen.getByLabelText("字体"), "sans");
    await user.selectOptions(screen.getByLabelText("段落对齐"), "justify");
    await user.selectOptions(screen.getByLabelText("阅读背景"), "night");
    await user.selectOptions(screen.getByRole("combobox", { name: /页面颜色/ }), "readingColors");
    await user.selectOptions(screen.getByLabelText("翻页方式"), "singlePage");
    await user.selectOptions(screen.getByLabelText("书架布局"), "grid");
    await user.click(screen.getByRole("checkbox", { name: "默认进入专注模式" }));
    await user.click(screen.getByRole("checkbox", { name: "显示书架进度" }));
    await user.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => {
      expect(savedPreferences).toEqual(expect.objectContaining({
        fontFamily: "sans",
        textAlignment: "justify",
        background: "night",
        pdfColorMode: "readingColors",
        pdfScrollMode: "singlePage",
        libraryLayout: "grid",
        immersiveMode: true,
        showProgressPercentage: true,
      }));
    });
    await user.click(screen.getByRole("button", { name: "打开《示例小说》" }));
    expect(await screen.findByRole("button", { name: "退出专注模式" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "退出专注模式" }));
    expect(screen.getByRole("button", { name: "进入专注模式" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "阅读设置" }));
    expect(screen.getByRole("dialog", { name: "阅读设置" })).toBeInTheDocument();
    expect(document.querySelector(".reader-document-shell")).toHaveAttribute("inert");
    await user.click(screen.getByRole("button", { name: "关闭设置" }));
  });

  it("delegates custom-regex validation to the Rust-compatible backend", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "get_reader_library") return EMPTY_LIBRARY as never;
      if (command === "save_reader_preferences") {
        return {
          ...EMPTY_LIBRARY,
          preferences: (args as { request: { preferences: ReaderPreferencesV3 } }).request.preferences,
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
          dtoVersion: 3,
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
