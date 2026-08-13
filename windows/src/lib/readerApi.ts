import { invokeCommand } from "./ipc";

export const READER_DTO_VERSION = 3 as const;

export type ReaderBookType = "txt" | "pdf";
export type ReaderBackground =
  | "white"
  | "paper"
  | "sepia"
  | "green"
  | "night"
  | "custom";
export type ReaderChapterDetectionMode =
  | "smart"
  | "custom"
  | "smartAndCustom";
export type ReaderFontFamily = "serif" | "sans" | "mono";
export type ReaderTextAlignment = "start" | "justify";
export type ReaderLibraryLayout = "list" | "grid";
export type ReaderPdfColorMode = "original" | "readingColors";
export type ReaderPdfScrollMode = "continuous" | "singlePage";

export interface ReaderPreferencesV3 {
  background: ReaderBackground;
  customBackgroundArgb: number;
  customForegroundArgb: number | null;
  fontSizePx: number;
  fontFamily: ReaderFontFamily;
  lineHeightMultiplier: number;
  paragraphSpacingPx: number;
  contentWidthPx: number;
  textAlignment: ReaderTextAlignment;
  firstLineIndentEm: number;
  letterSpacingPx: number;
  pagePaddingPx: number;
  pdfZoomPercent: number;
  pdfColorMode: ReaderPdfColorMode;
  pdfScrollMode: ReaderPdfScrollMode;
  pdfPageGapPx: number;
  immersiveMode: boolean;
  showProgressPercentage: boolean;
  libraryLayout: ReaderLibraryLayout;
  showGridBookTitles: boolean;
  chapterDetectionMode: ReaderChapterDetectionMode;
  customChapterRegex: string;
  chapterHeadingMaxChars: number;
}

export interface ReaderBookV3 {
  dtoVersion: typeof READER_DTO_VERSION;
  id: string;
  title: string;
  bookType: ReaderBookType;
  addedAt: number;
  lastOpenedAt: number;
  textParagraphIndex: number;
  textPageIndex: number;
  pdfPageIndex: number;
  totalPages: number;
  readingMillis: string;
}

export interface ReaderLibraryV3 {
  dtoVersion: typeof READER_DTO_VERSION;
  books: ReaderBookV3[];
  preferences: ReaderPreferencesV3;
  totalReadingMillis: string;
}

export interface ReaderTextPageV1 {
  text: string;
  firstParagraphIndex: number;
}

export interface ReaderChapterV1 {
  title: string;
  pageIndex: number;
  paragraphIndex: number;
}

interface ReaderDocumentBaseV3 {
  dtoVersion: typeof READER_DTO_VERSION;
  book: ReaderBookV3;
  preferences: ReaderPreferencesV3;
}

export interface ReaderTextDocumentV3 extends ReaderDocumentBaseV3 {
  kind: "txt";
  pages: ReaderTextPageV1[];
  chapters: ReaderChapterV1[];
}

export interface ReaderPdfDocumentV3 extends ReaderDocumentBaseV3 {
  kind: "pdf";
  assetUrl: string;
}

export type ReaderDocumentV3 = ReaderTextDocumentV3 | ReaderPdfDocumentV3;

export interface ReaderProgressRequestV3 {
  dtoVersion: typeof READER_DTO_VERSION;
  bookId: string;
  pageIndex: number;
  paragraphIndex?: number;
}

export const readerApi = {
  library(): Promise<ReaderLibraryV3> {
    return invokeCommand("get_reader_library");
  },

  chooseBook(): Promise<ReaderDocumentV3 | null> {
    return invokeCommand("choose_reader_book");
  },

  openBook(bookId: string): Promise<ReaderDocumentV3> {
    return invokeCommand("open_reader_book", {
      request: { dtoVersion: READER_DTO_VERSION, bookId },
    });
  },

  saveProgress(request: Omit<ReaderProgressRequestV3, "dtoVersion">): Promise<ReaderBookV3> {
    return invokeCommand("save_reader_progress", {
      request: { ...request, dtoVersion: READER_DTO_VERSION },
    });
  },

  savePreferences(preferences: ReaderPreferencesV3): Promise<ReaderLibraryV3> {
    return invokeCommand("save_reader_preferences", {
      request: { dtoVersion: READER_DTO_VERSION, preferences },
    });
  },

  removeBook(bookId: string): Promise<ReaderLibraryV3> {
    return invokeCommand("remove_reader_book", {
      request: { dtoVersion: READER_DTO_VERSION, bookId },
    });
  },

  recordTime(bookId: string, deltaMillis: number): Promise<ReaderBookV3> {
    return invokeCommand("record_reader_time", {
      request: { dtoVersion: READER_DTO_VERSION, bookId, deltaMillis },
    });
  },
};
