import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useAppStore } from "../store/appStore";
import NotesPage from "./NotesPage";
import { safeNoteLinkTransform, splitNotePreview } from "./notesPreview";

const VERSION = {
  sha256: "a".repeat(64),
  size: 20,
  modifiedAt: 1_786_000_000_000,
};

const NOTE_ENTRY = {
  schemaVersion: 1,
  relativePath: "projects/entry.md",
  name: "entry.md",
  kind: "note",
  size: 20,
  modifiedAt: VERSION.modifiedAt,
};

const FOLDER_ENTRY = {
  schemaVersion: 1,
  relativePath: "projects/assets",
  name: "assets",
  kind: "folder",
  size: 0,
  modifiedAt: VERSION.modifiedAt,
};

const DOCUMENT = {
  schemaVersion: 1,
  relativePath: NOTE_ENTRY.relativePath,
  folderRelativePath: "projects",
  name: NOTE_ENTRY.name,
  content: "# Entry\n\nOriginal",
  version: VERSION,
};

function renderPage() {
  const router = createMemoryRouter(
    [{ path: "/notes", element: <NotesPage /> }],
    { initialEntries: ["/notes"] },
  );
  return render(<RouterProvider router={router} />);
}

describe("NotesPage", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => {
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
      if (command === "get_notes_root") {
        return { schemaVersion: 1, configured: true, displayName: "Vault" } as never;
      }
      if (command === "list_note_folder") {
        return {
          schemaVersion: 1,
          relativePath: "",
          displayName: "Vault",
          entries: [FOLDER_ENTRY, NOTE_ENTRY],
        } as never;
      }
      if (command === "open_note") return DOCUMENT as never;
      if (command === "select_and_import_note_media") {
        return {
          schemaVersion: 1,
          fileName: "photo.png",
          relativePath: "assets/photo.png",
          markdownTarget: "../assets/photo.png",
          markdown: "![photo](<../assets/photo.png>)",
        } as never;
      }
      if (command === "save_note") {
        const request = (args as { request: { content: string; resolution: string } }).request;
        if (request.resolution === "normal") {
          return {
            status: "conflict",
            schemaVersion: 1,
            reason: "changed",
            diskDocument: {
              ...DOCUMENT,
              content: "# Changed in Obsidian",
              version: { ...VERSION, sha256: "b".repeat(64), size: 21 },
            },
          } as never;
        }
        return {
          status: "saved",
          schemaVersion: 1,
          document: {
            ...DOCUMENT,
            content: request.content,
            version: { ...VERSION, sha256: "c".repeat(64), size: request.content.length },
          },
        } as never;
      }
      if (command === "resolve_note_media") return [] as never;
      throw new Error(`Unexpected command: ${command}`);
    });
  });

  it("autosaves, pauses on an external conflict, and overwrites only after confirmation", async () => {
    const user = userEvent.setup();
    renderPage();

    const entryLabel = await screen.findByText("entry");
    await user.click(entryLabel.closest("button")!);
    expect(await screen.findByLabelText("Markdown 笔记正文")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "上传媒体" }));

    const conflict = await screen.findByRole(
      "alertdialog",
      { name: "文件已在外部修改" },
      { timeout: 3_000 },
    );
    expect(conflict).toHaveTextContent("不会自行覆盖");
    const firstSave = invokeMock.mock.calls.find(([command]) => command === "save_note");
    expect((firstSave?.[1] as { request: { schemaVersion: number; resolution: string } }).request).toMatchObject({
      schemaVersion: 1,
      resolution: "normal",
    });
    expect(
      invokeMock.mock.calls.filter(
        ([command, args]) =>
          command === "save_note" &&
          (args as { request: { resolution: string } }).request.resolution === "overwrite",
      ),
    ).toHaveLength(0);

    await user.click(screen.getByRole("button", { name: "明确覆盖" }));
    await waitFor(() => {
      expect(
        invokeMock.mock.calls.some(
          ([command, args]) =>
            command === "save_note" &&
            (args as { request: { resolution: string } }).request.resolution === "overwrite",
        ),
      ).toBe(true);
    });
    expect(await screen.findByText("笔记已保存")).toBeInTheDocument();
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(invokeMock).toHaveBeenCalledWith("select_and_import_note_media", {
      request: { schemaVersion: 1, relativePath: NOTE_ENTRY.relativePath },
    });
  });

  it("shows loading, folder-first browser controls, and bilingual empty-safe actions", async () => {
    renderPage();
    expect(await screen.findByRole("navigation", { name: "文件夹路径" })).toHaveTextContent("Vault");
    const items = screen.getAllByRole("listitem");
    expect(items[0]).toHaveTextContent("assets");
    expect(items[1]).toHaveTextContent("entry");
    expect(screen.getByRole("button", { name: "文件夹" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "笔记" })).toBeEnabled();
  });
});

describe("note preview parsing", () => {
  it("extracts standard Markdown images and Obsidian wiki embeds without rewriting source", () => {
    const source = "Before\n![caption](<assets/a b.png>)\n![[photo.jpg|Wiki caption]]\nAfter";
    const images = splitNotePreview(source).filter((part) => part.kind === "image");
    expect(images).toEqual([
      expect.objectContaining({ target: "assets/a b.png", caption: "caption" }),
      expect.objectContaining({ target: "photo.jpg", caption: "Wiki caption" }),
    ]);
    expect(source).toContain("![[photo.jpg|Wiki caption]]");
  });

  it("allows only safe external links in rendered Markdown", () => {
    expect(safeNoteLinkTransform("https://example.test")).toBe("https://example.test");
    expect(safeNoteLinkTransform("mailto:user@example.test")).toBe("");
    expect(safeNoteLinkTransform("javascript:alert(1)")).toBe("");
    expect(safeNoteLinkTransform("file:///C:/private.txt")).toBe("");
    expect(safeNoteLinkTransform("../private.txt")).toBe("");
  });
});
