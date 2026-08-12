import { invokeCommand } from "./ipc";

export const READER_DTO_VERSION = 2 as const;

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

export interface ReaderPreferencesV2 {
  background: ReaderBackground;
  customBackgroundArgb: number;
  customForegroundArgb: number | null;
  fontSizePx: number;
  fontFamily: ReaderFontFamily;
  lineHeightMultiplier: number;
  paragraphSpacingPx: number;
  contentWidthPx: number;
  textAlignment: ReaderTextAlignment;
  pdfZoomPercent: number;
  immersiveMode: boolean;
  showProgressPercentage: boolean;
  libraryLayout: ReaderLibraryLayout;
  showGridBookTitles: boolean;
  chapterDetectionMode: ReaderChapterDetectionMode;
  customChapterRegex: string;
  chapterHeadingMaxChars: number;
}

export interface ReaderBookV2 {
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

export interface ReaderLibraryV2 {
  dtoVersion: typeof READER_DTO_VERSION;
  books: ReaderBookV2[];
  preferences: ReaderPreferencesV2;
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

interface ReaderDocumentBaseV2 {
  dtoVersion: typeof READER_DTO_VERSION;
  book: ReaderBookV2;
  preferences: ReaderPreferencesV2;
}

export interface ReaderTextDocumentV2 extends ReaderDocumentBaseV2 {
  kind: "txt";
  pages: ReaderTextPageV1[];
  chapters: ReaderChapterV1[];
}

export interface ReaderPdfDocumentV2 extends ReaderDocumentBaseV2 {
  kind: "pdf";
  assetUrl: string;
}

export type ReaderDocumentV2 = ReaderTextDocumentV2 | ReaderPdfDocumentV2;

export interface ReaderProgressRequestV2 {
  dtoVersion: typeof READER_DTO_VERSION;
  bookId: string;
  pageIndex: number;
  paragraphIndex?: number;
}

export const readerApi = {
  library(): Promise<ReaderLibraryV2> {
    return invokeCommand("get_reader_library");
  },

  chooseBook(): Promise<ReaderDocumentV2 | null> {
    return invokeCommand("choose_reader_book");
  },

  openBook(bookId: string): Promise<ReaderDocumentV2> {
    return invokeCommand("open_reader_book", {
      request: { dtoVersion: READER_DTO_VERSION, bookId },
    });
  },

  saveProgress(request: Omit<ReaderProgressRequestV2, "dtoVersion">): Promise<ReaderBookV2> {
    return invokeCommand("save_reader_progress", {
      request: { ...request, dtoVersion: READER_DTO_VERSION },
    });
  },

  savePreferences(preferences: ReaderPreferencesV2): Promise<ReaderLibraryV2> {
    return invokeCommand("save_reader_preferences", {
      request: { dtoVersion: READER_DTO_VERSION, preferences },
    });
  },

  removeBook(bookId: string): Promise<ReaderLibraryV2> {
    return invokeCommand("remove_reader_book", {
      request: { dtoVersion: READER_DTO_VERSION, bookId },
    });
  },

  recordTime(bookId: string, deltaMillis: number): Promise<ReaderBookV2> {
    return invokeCommand("record_reader_time", {
      request: { dtoVersion: READER_DTO_VERSION, bookId, deltaMillis },
    });
  },
};
