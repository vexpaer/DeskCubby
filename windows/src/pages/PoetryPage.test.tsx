import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import PoetryPage from "./PoetryPage";

const poems = [
  {
    id: "11",
    content: "第一首",
    source: "甲《其一》",
    createdAt: "10",
    updatedAt: "10",
    sortOrder: "0",
    categoryId: "2",
  },
  {
    id: "12",
    content: "第二首",
    source: "乙《其二》",
    createdAt: "11",
    updatedAt: "11",
    sortOrder: "1",
    categoryId: "3",
  },
];

const categories = [
  {
    id: "2",
    name: "唐诗",
    colorArgb: -9_801_247,
    sortOrder: "0",
    createdAt: "1",
    updatedAt: "1",
  },
  {
    id: "3",
    name: "宋词",
    colorArgb: -4_323_944,
    sortOrder: "1",
    createdAt: "2",
    updatedAt: "2",
  },
];

const dailyPoem = {
  content: "海上生明月，天涯共此时。",
  title: "望月怀远",
  source: "张九龄《望月怀远》",
  author: "张九龄",
  dynasty: "唐",
  fromCache: true,
  usedFallback: false,
};

describe("PoetryPage", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => {
    document.documentElement.lang = "zh-CN";
    invokeMock.mockReset();
    invokeMock.mockImplementation(async (command) => {
      if (command === "list_poems") return poems as never;
      if (command === "list_poetry_categories") return categories as never;
      if (command === "list_poetry_presets") {
        return [
          {
            id: "junior-7-1",
            nameZh: "初中·七年级上册",
            nameEn: "Junior · Grade 7 Vol. 1",
            colorArgb: -3_708_068,
            itemCount: 2,
          },
        ] as never;
      }
      if (command === "get_daily_poem") return dailyPoem as never;
      if (command === "move_poetry_category" || command === "move_poem") return undefined as never;
      if (command === "create_poetry_category") {
        return {
          id: "4",
          name: "自选",
          colorArgb: -10_137_436,
          sortOrder: "2",
          createdAt: "3",
          updatedAt: "3",
        } as never;
      }
      if (command === "update_poetry_category") {
        return { ...categories[0], name: "新唐诗" } as never;
      }
      if (command === "delete_poetry_category") return undefined as never;
      if (command === "import_poetry_preset") {
        return { categoryId: "2", addedCount: 0, existingCount: 2 } as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });
  });

  it("exposes category and poem ordering as keyboard-operable buttons", async () => {
    const user = userEvent.setup();
    render(<PoetryPage />);

    expect(await screen.findByText("第一首")).toBeInTheDocument();
    const moveCategoryUp = screen.getByRole("button", { name: "上移分类 宋词" });
    moveCategoryUp.focus();
    await user.keyboard("{Enter}");

    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("move_poetry_category", {
        id: "3",
        targetIndex: 0,
      });
    });

    const movePoemDown = screen.getAllByRole("button", { name: "下移诗词" })[0];
    movePoemDown.focus();
    await user.keyboard("{Enter}");
    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("move_poem", {
        id: "11",
        targetIndex: 1,
        scope: "all",
      });
    });
  });

  it("imports the shared Android preset and reports skipped duplicates", async () => {
    const user = userEvent.setup();
    render(<PoetryPage />);
    await screen.findByText("第一首");

    await user.click(screen.getByRole("button", { name: "教材预设" }));
    const dialog = screen.getByRole("dialog", { name: "初高中古诗文预设" });
    await user.click(within(dialog).getByRole("button", { name: /初中·七年级上册/ }));

    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("import_poetry_preset", {
        presetId: "junior-7-1",
      });
    });
    expect(await screen.findByText("已导入 0 篇，跳过 2 篇已有内容。")).toBeInTheDocument();
  });

  it("creates, edits, and safely deletes a category without deleting its poems", async () => {
    const user = userEvent.setup();
    vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<PoetryPage />);
    await screen.findByText("第一首");

    await user.click(screen.getByRole("button", { name: "新建分类" }));
    const createDialog = screen.getByRole("dialog", { name: "新建分类" });
    await user.type(within(createDialog).getByLabelText("分类名称"), "自选");
    await user.click(within(createDialog).getByRole("button", { name: "保存" }));
    expect(await screen.findByRole("button", { name: "编辑分类 自选" })).toBeInTheDocument();
    expect(invokeMock).toHaveBeenCalledWith(
      "create_poetry_category",
      expect.objectContaining({ draft: expect.objectContaining({ name: "自选" }) }),
    );

    await user.click(screen.getByRole("button", { name: "编辑分类 唐诗" }));
    const editDialog = screen.getByRole("dialog", { name: "编辑分类" });
    const nameInput = within(editDialog).getByLabelText("分类名称");
    await user.clear(nameInput);
    await user.type(nameInput, "新唐诗");
    await user.click(within(editDialog).getByRole("button", { name: "保存" }));
    expect(await screen.findByRole("button", { name: /^新唐诗/ })).toBeInTheDocument();
    expect(invokeMock).toHaveBeenCalledWith(
      "update_poetry_category",
      expect.objectContaining({ id: "2", draft: expect.objectContaining({ name: "新唐诗" }) }),
    );

    await user.click(screen.getByRole("button", { name: "编辑分类 自选" }));
    await user.click(
      within(screen.getByRole("dialog", { name: "编辑分类" })).getByRole("button", {
        name: "删除分类，保留诗词",
      }),
    );
    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("delete_poetry_category", {
        id: "4",
        deletePoems: false,
      });
    });
  });
});
