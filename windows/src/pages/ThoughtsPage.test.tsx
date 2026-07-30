import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type {
  Thought,
  ThoughtCategory,
  ThoughtDraft,
} from "../lib/ipc";
import ThoughtsPage from "./ThoughtsPage";

const FIRST_ID = "9007199254740993";
const SECOND_ID = "9007199254740995";
const CATEGORY_ID = "9007199254740997";
const NOW = "1785254400000";

const initialThoughts: Thought[] = [
  {
    id: FIRST_ID,
    content: "第一条超大 ID 小巧思",
    createdAt: NOW,
    updatedAt: NOW,
    pinned: false,
    deletedAt: null,
    sortOrder: "9007199254740993",
    categoryId: CATEGORY_ID,
    highlighted: false,
  },
  {
    id: SECOND_ID,
    content: "第二条超大 ID 小巧思",
    createdAt: NOW,
    updatedAt: NOW,
    pinned: false,
    deletedAt: null,
    sortOrder: "9007199254740995",
    categoryId: null,
    highlighted: false,
  },
];

const initialCategories: ThoughtCategory[] = [
  {
    id: CATEGORY_ID,
    name: "边界分类",
    colorArgb: 0xff42664d | 0,
    sortOrder: "9007199254740999",
    createdAt: NOW,
    updatedAt: NOW,
  },
];

function installThoughtIpcMock() {
  const invokeMock = vi.mocked(invoke);
  invokeMock.mockImplementation(async (command, args) => {
    if (command === "list_thoughts") return structuredClone(initialThoughts) as never;
    if (command === "list_thought_categories") {
      return structuredClone(initialCategories) as never;
    }
    if (command === "update_thought") {
      const request = args as { id: string; draft: ThoughtDraft };
      const original = initialThoughts.find((thought) => thought.id === request.id);
      if (!original) throw new Error("missing test thought");
      return {
        ...original,
        ...request.draft,
        updatedAt: "1785254400001",
      } as never;
    }
    if (command === "delete_thought") {
      const request = args as { id: string; permanent: boolean };
      const original = initialThoughts.find((thought) => thought.id === request.id);
      if (!original) throw new Error("missing test thought");
      return {
        ...original,
        deletedAt: request.permanent ? null : "1785254400002",
      } as never;
    }
    if (command === "reorder_thoughts") return undefined as never;
    throw new Error(`unexpected command: ${command}`);
  });
  return invokeMock;
}

describe("ThoughtsPage lossless i64 IPC", () => {
  beforeEach(() => {
    document.documentElement.lang = "zh-CN";
    vi.clearAllMocks();
  });

  it("edits a thought without rounding its >2^53 ID or category ID", async () => {
    const invokeMock = installThoughtIpcMock();
    const user = userEvent.setup();
    render(<ThoughtsPage />);

    await screen.findByText("第一条超大 ID 小巧思");
    await user.click(screen.getAllByRole("button", { name: "编辑" })[0]);
    const editor = screen.getByRole("textbox", { name: "小巧思内容" });
    await user.clear(editor);
    await user.type(editor, "编辑后的内容");
    await user.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() =>
      expect(invokeMock).toHaveBeenCalledWith("update_thought", {
        id: FIRST_ID,
        draft: {
          id: FIRST_ID,
          content: "编辑后的内容",
          pinned: false,
          categoryId: CATEGORY_ID,
          highlighted: false,
        },
      }),
    );
  });

  it("soft-deletes using the exact >2^53 decimal ID", async () => {
    const invokeMock = installThoughtIpcMock();
    const user = userEvent.setup();
    render(<ThoughtsPage />);

    await screen.findByText("第一条超大 ID 小巧思");
    await user.click(screen.getAllByRole("button", { name: "移到回收站" })[0]);

    await waitFor(() =>
      expect(invokeMock).toHaveBeenCalledWith("delete_thought", {
        id: FIRST_ID,
        permanent: false,
      }),
    );
  });

  it("reorders exact >2^53 IDs through the keyboard move control", async () => {
    const invokeMock = installThoughtIpcMock();
    const user = userEvent.setup();
    render(<ThoughtsPage />);

    await screen.findByText("第一条超大 ID 小巧思");
    await user.click(screen.getAllByRole("button", { name: "下移" })[0]);

    await waitFor(() =>
      expect(invokeMock).toHaveBeenCalledWith("reorder_thoughts", {
        ids: [SECOND_ID, FIRST_ID],
      }),
    );
  });
});
