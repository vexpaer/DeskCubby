import { invokeCommand } from "./ipc";

export const READER_DTO_VERSION = 1 as const;

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

export interface ReaderPreferencesV1 {
  background: ReaderBackground;
  customBackgroundArgb: number;
  fontSizePx: number;
  lineHeightMultiplier: number;
  paragraphSpacingPx: number;
  pdfZoomPercent: number;
  chapterDetectionMode: ReaderChapterDetectionMode;
  customChapterRegex: string;
  chapterHeadingMaxChars: number;
}

export interface ReaderBookV1 {
  dtoVersion: typeof READER_DTO_VERSION;
  id: string;
  title: string;
  bookType: ReaderBookType;
  addedAt: number;
  lastOpenedAt: number;
  textParagraphIndex: number;
  textPageIndex: number;
  pdfPageIndex: number;
  readingMillis: string;
}

export interface ReaderLibraryV1 {
  dtoVersion: typeof READER_DTO_VERSION;
  books: ReaderBookV1[];
  preferences: ReaderPreferencesV1;
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

interface ReaderDocumentBaseV1 {
  dtoVersion: typeof READER_DTO_VERSION;
  book: ReaderBookV1;
  preferences: ReaderPreferencesV1;
}

export interface ReaderTextDocumentV1 extends ReaderDocumentBaseV1 {
  kind: "txt";
  pages: ReaderTextPageV1[];
  chapters: ReaderChapterV1[];
}

export interface ReaderPdfDocumentV1 extends ReaderDocumentBaseV1 {
  kind: "pdf";
  assetUrl: string;
}

export type ReaderDocumentV1 = ReaderTextDocumentV1 | ReaderPdfDocumentV1;

export interface ReaderProgressRequestV1 {
  dtoVersion: typeof READER_DTO_VERSION;
  bookId: string;
  pageIndex: number;
  paragraphIndex?: number;
}

export const readerApi = {
  library(): Promise<ReaderLibraryV1> {
    return invokeCommand("get_reader_library");
  },

  chooseBook(): Promise<ReaderDocumentV1 | null> {
    return invokeCommand("choose_reader_book");
  },

  openBook(bookId: string): Promise<ReaderDocumentV1> {
    return invokeCommand("open_reader_book", {
      request: { dtoVersion: READER_DTO_VERSION, bookId },
    });
  },

  saveProgress(request: Omit<ReaderProgressRequestV1, "dtoVersion">): Promise<ReaderBookV1> {
    return invokeCommand("save_reader_progress", {
      request: { ...request, dtoVersion: READER_DTO_VERSION },
    });
  },

  savePreferences(preferences: ReaderPreferencesV1): Promise<ReaderLibraryV1> {
    return invokeCommand("save_reader_preferences", {
      request: { dtoVersion: READER_DTO_VERSION, preferences },
    });
  },

  removeBook(bookId: string): Promise<ReaderLibraryV1> {
    return invokeCommand("remove_reader_book", {
      request: { dtoVersion: READER_DTO_VERSION, bookId },
    });
  },

  recordTime(bookId: string, deltaMillis: number): Promise<ReaderBookV1> {
    return invokeCommand("record_reader_time", {
      request: { dtoVersion: READER_DTO_VERSION, bookId, deltaMillis },
    });
  },
};
