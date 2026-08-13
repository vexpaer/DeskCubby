import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type {
  ReaderDocumentV3,
  ReaderLibraryV3,
  ReaderPreferencesV3,
} from "../lib/readerApi";
import { useAppStore } from "../store/appStore";
import ReaderPage from "./ReaderPage";

const pdfMocks = vi.hoisted(() => ({
  documentProps: vi.fn(),
  pageProps: vi.fn(),
  proxy: {
    numPages: 3,
    getPage: vi.fn(async () => ({
      getViewport: () => ({ width: 612, height: 792 }),
      getTextContent: vi.fn(async () => ({ items: [], styles: {} })),
    })),
  },
}));

vi.mock("react-pdf", async () => {
  const React = await import("react");
  return {
    pdfjs: { GlobalWorkerOptions: {} },
    PasswordResponses: { NEED_PASSWORD: 1, INCORRECT_PASSWORD: 2 },
    Document: ({
      children,
      file,
      onLoadSuccess,
    }: {
      children?: ReactNode;
      file?: unknown;
      onLoadSuccess?: (proxy: typeof pdfMocks.proxy) => void;
    }) => {
      pdfMocks.documentProps({ file });
      React.useEffect(() => onLoadSuccess?.(pdfMocks.proxy), [onLoadSuccess]);
      return React.createElement("div", { "data-testid": "react-pdf-document" }, children);
    },
    Page: (props: Record<string, unknown>) => {
      pdfMocks.pageProps(props);
      return React.createElement(
        "div",
        { "data-testid": "react-pdf-page" },
        React.createElement(
          "a",
          { href: "https://example.com/pdf-annotation" },
          "PDF annotation link",
        ),
      );
    },
    Outline: () => null,
  };
});

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

function renderReader() {
  const router = createMemoryRouter(
    [
      { path: "/reader", element: <ReaderPage /> },
      { path: "/elsewhere", element: <p>Elsewhere</p> },
    ],
    { initialEntries: ["/reader"] },
  );
  return render(<RouterProvider router={router} />);
}

describe("ReaderPage React-PDF integration", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => {
    useAppStore.setState((state) => ({
      ...state,
      appearance: { ...state.appearance, language: "zh-CN" },
      dirtyScopes: [],
    }));
    invokeMock.mockReset();
    pdfMocks.documentProps.mockClear();
    pdfMocks.pageProps.mockClear();
  });

  it("loads through the restricted URL, clamps progress, and never enables PDF.js pageColors", async () => {
    const user = userEvent.setup();
    const library: ReaderLibraryV3 = { ...EMPTY_LIBRARY, books: [PDF_BOOK] };
    const document: ReaderDocumentV3 = {
      dtoVersion: 3,
      book: PDF_BOOK,
      preferences: PREFERENCES,
      kind: "pdf",
      assetUrl: `http://reader.localhost/${PDF_BOOK.id}.pdf`,
    };
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_reader_library") return library as never;
      if (command === "open_reader_book") return document as never;
      if (command === "save_reader_progress") return PDF_BOOK as never;
      if (command === "open_external_link") return undefined as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    renderReader();
    await user.click(await screen.findByRole("button", { name: "打开《研究报告》" }));

    const input = await screen.findByRole("spinbutton", { name: "保存页码" });
    await waitFor(() => expect(input).toHaveValue(3));
    expect(pdfMocks.documentProps).toHaveBeenCalledWith({
      file: { url: `http://reader.localhost/${PDF_BOOK.id}.pdf` },
    });
    await waitFor(() => expect(pdfMocks.pageProps).toHaveBeenCalled());
    const pageProps = pdfMocks.pageProps.mock.calls.at(-1)?.[0];
    expect(pageProps).not.toHaveProperty("pageColors");
    expect(pageProps).toEqual(expect.objectContaining({
      renderTextLayer: true,
      renderAnnotationLayer: true,
      renderForms: true,
    }));

    await user.click(screen.getAllByRole("link", { name: "PDF annotation link" })[0]);
    expect(invokeMock).toHaveBeenCalledWith("open_external_link", {
      request: {
        schemaVersion: 1,
        url: "https://example.com/pdf-annotation",
      },
    });
  });
});
