import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { getDocument } from "pdfjs-dist";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type {
  ReaderDocumentV1,
  ReaderLibraryV1,
  ReaderPreferencesV1,
} from "../lib/readerApi";
import { useAppStore } from "../store/appStore";
import ReaderPage from "./ReaderPage";

// jsdom has no canvas 2D context and no real worker, so pdf.js is mocked. This
// exercises the real readerPdfViewer wiring: document load through the
// restricted URL and ReaderPage's page-index clamp on the reported page count.
vi.mock("pdfjs-dist", () => ({
  GlobalWorkerOptions: {},
  getDocument: vi.fn(),
}));
vi.mock("pdfjs-dist/build/pdf.worker.min.mjs?worker", () => ({
  default: class PdfWorkerStub {},
}));

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

describe("ReaderPage PDF viewer integration", () => {
  const invokeMock = vi.mocked(invoke);
  const getDocumentMock = vi.mocked(getDocument);

  beforeEach(() => {
    useAppStore.setState((state) => ({
      ...state,
      appearance: { ...state.appearance, language: "zh-CN" },
      dirtyScopes: [],
    }));
    invokeMock.mockReset();
    getDocumentMock.mockReset();
  });

  it("loads the PDF through the restricted reader URL and clamps a restored page index", async () => {
    const user = userEvent.setup();
    const library: ReaderLibraryV1 = {
      ...EMPTY_LIBRARY,
      books: [PDF_BOOK],
    };
    const document: ReaderDocumentV1 = {
      dtoVersion: 1,
      book: PDF_BOOK,
      preferences: PREFERENCES,
      kind: "pdf",
      assetUrl: `http://reader.localhost/${PDF_BOOK.id}.pdf`,
    };
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_reader_library") return library as never;
      if (command === "open_reader_book") return document as never;
      if (command === "save_reader_progress") return PDF_BOOK as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    // The restored bookmark points at page 5 (pdfPageIndex 4), but the real
    // document has only 3 pages, so the viewer reports numPages and ReaderPage
    // must clamp the saved-page input down to 3.
    const proxy = {
      numPages: 3,
      getPage: vi.fn(),
      destroy: vi.fn(async () => undefined),
    };
    getDocumentMock.mockReturnValue({
      promise: Promise.resolve(proxy),
      destroy: vi.fn(),
    } as never);

    renderReader();
    await user.click(await screen.findByRole("button", { name: "打开《研究报告》" }));

    const input = await screen.findByRole("spinbutton", { name: "保存页码" });
    await waitFor(() => expect(input).toHaveValue(3));
    expect(getDocumentMock).toHaveBeenCalledWith(
      expect.objectContaining({
        url: `http://reader.localhost/${PDF_BOOK.id}.pdf`,
      }),
    );
    expect(screen.getByText(/已保存到第 3 页/)).toBeInTheDocument();
  });
});
