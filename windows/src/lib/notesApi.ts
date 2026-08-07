import { invokeCommand } from "./ipc";

export const NOTES_DTO_VERSION = 1 as const;

export interface NotesRootStateV1 {
  schemaVersion: typeof NOTES_DTO_VERSION;
  configured: boolean;
  displayName: string | null;
}

export interface NoteFileVersionV1 {
  sha256: string;
  size: number;
  modifiedAt: number;
}

export type NoteEntryKindV1 = "folder" | "note";

export interface NoteEntryV1 {
  schemaVersion: typeof NOTES_DTO_VERSION;
  relativePath: string;
  name: string;
  kind: NoteEntryKindV1;
  size: number;
  modifiedAt: number;
}

export interface NoteFolderSnapshotV1 {
  schemaVersion: typeof NOTES_DTO_VERSION;
  relativePath: string;
  displayName: string;
  entries: NoteEntryV1[];
}

export interface NoteDocumentV1 {
  schemaVersion: typeof NOTES_DTO_VERSION;
  relativePath: string;
  folderRelativePath: string;
  name: string;
  content: string;
  version: NoteFileVersionV1;
}

export type NoteSaveResolutionV1 = "normal" | "overwrite" | "copy";
export type NoteConflictReasonV1 = "changed" | "deleted";

export type SaveNoteResultV1 =
  | {
      status: "saved";
      schemaVersion: typeof NOTES_DTO_VERSION;
      document: NoteDocumentV1;
    }
  | {
      status: "conflict";
      schemaVersion: typeof NOTES_DTO_VERSION;
      reason: NoteConflictReasonV1;
      diskDocument: NoteDocumentV1 | null;
    };

export interface ImportedNoteMediaV1 {
  schemaVersion: typeof NOTES_DTO_VERSION;
  fileName: string;
  relativePath: string;
  markdownTarget: string;
  markdown: string;
}

export interface ResolvedNoteMediaV1 {
  target: string;
  dataUrl: string;
}

function request<T extends Record<string, unknown>>(value: T) {
  return { request: { schemaVersion: NOTES_DTO_VERSION, ...value } };
}

export const notesApi = {
  root(): Promise<NotesRootStateV1> {
    return invokeCommand("get_notes_root");
  },

  selectRoot(): Promise<NotesRootStateV1 | null> {
    return invokeCommand("select_notes_root");
  },

  forgetRoot(): Promise<void> {
    return invokeCommand("forget_notes_root");
  },

  listFolder(folderPath = ""): Promise<NoteFolderSnapshotV1> {
    return invokeCommand(
      "list_note_folder",
      request({ folderPath }),
    );
  },

  createFolder(parentPath: string, name: string): Promise<NoteEntryV1> {
    return invokeCommand(
      "create_note_folder",
      request({ parentPath, name }),
    );
  },

  createNote(parentPath: string, name: string): Promise<NoteDocumentV1> {
    return invokeCommand(
      "create_note",
      request({ parentPath, name }),
    );
  },

  open(relativePath: string): Promise<NoteDocumentV1> {
    return invokeCommand("open_note", request({ relativePath }));
  },

  rename(
    relativePath: string,
    kind: NoteEntryKindV1,
    name: string,
  ): Promise<NoteEntryV1> {
    return invokeCommand(
      "rename_note_entry",
      request({ relativePath, kind, name }),
    );
  },

  remove(relativePath: string, kind: NoteEntryKindV1): Promise<void> {
    return invokeCommand(
      "delete_note_entry",
      request({ relativePath, kind }),
    );
  },

  save(
    document: NoteDocumentV1,
    content: string,
    resolution: NoteSaveResolutionV1,
  ): Promise<SaveNoteResultV1> {
    return invokeCommand(
      "save_note",
      request({
        relativePath: document.relativePath,
        content,
        expectedVersion: document.version,
        resolution,
      }),
    );
  },

  selectAndImportMedia(
    noteRelativePath: string,
  ): Promise<ImportedNoteMediaV1 | null> {
    return invokeCommand(
      "select_and_import_note_media",
      request({ relativePath: noteRelativePath }),
    );
  },

  resolveMedia(
    noteRelativePath: string,
    targets: string[],
  ): Promise<ResolvedNoteMediaV1[]> {
    return invokeCommand(
      "resolve_note_media",
      request({ noteRelativePath, targets }),
    );
  },
};
