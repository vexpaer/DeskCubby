import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useAppStore } from "../store/appStore";
import VaultPage from "./VaultPage";

const STATUS_UNLOCKED = {
  schemaVersion: 1,
  lockState: "UNLOCKED",
  corruptedItemCount: 0,
} as const;

const ITEMS = [
  {
    id: "9007199254740993",
    content: "first secret",
    note: "note",
    sortOrder: "1",
    createdAt: "1785254400000",
    updatedAt: "1785254400000",
    primaryAction: "COPY",
  },
  {
    id: "9007199254740995",
    content: "http://example.com/",
    note: null,
    sortOrder: "2",
    createdAt: "1785254400001",
    updatedAt: "1785254400001",
    primaryAction: "OPEN_URL",
  },
] as const;

describe("VaultPage", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => {
    useAppStore.setState((state) => ({
      ...state,
      appearance: { ...state.appearance, language: "zh-CN" },
    }));
    invokeMock.mockReset();
  });

  it("sets up with a Unicode password and clears password fields after submit", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_vault_status") {
        return {
          schemaVersion: 1,
          lockState: "NOT_SET",
          corruptedItemCount: 0,
        } as never;
      }
      if (command === "setup_vault") return STATUS_UNLOCKED as never;
      if (command === "list_vault_items") return [] as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    render(<VaultPage />);
    await screen.findByRole("heading", { name: "设置收藏夹密码" });

    await user.type(screen.getByLabelText("密码"), "🔐");
    await user.type(screen.getByLabelText("确认密码"), "🔐");
    await user.click(screen.getByRole("button", { name: "创建并解锁" }));

    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("setup_vault", {
        request: { schemaVersion: 1, password: "🔐" },
      });
    });
    expect(await screen.findByRole("button", { name: "新增" })).toBeInTheDocument();
    expect(screen.queryByDisplayValue("🔐")).not.toBeInTheDocument();
  });

  it("preserves exact decimal IDs for copy, URL opening and keyboard move controls", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "get_vault_status") return STATUS_UNLOCKED as never;
      if (command === "list_vault_items") return [...ITEMS] as never;
      if (command === "copy_vault_item") return undefined as never;
      if (command === "open_vault_item_url") return undefined as never;
      if (command === "reorder_vault_items") {
        const ids = (args as { request: { ids: string[] } }).request.ids;
        return ids.map((id, index) => ({
          ...ITEMS.find((item) => item.id === id)!,
          sortOrder: String(index + 1),
        })) as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });

    render(<VaultPage />);
    expect(await screen.findByText("first secret")).toBeInTheDocument();

    await user.click(screen.getAllByRole("button", { name: "复制正文" })[0]);
    expect(invokeMock).toHaveBeenCalledWith("copy_vault_item", {
      request: { schemaVersion: 1, id: "9007199254740993" },
    });

    await user.click(screen.getByRole("button", { name: "在系统浏览器打开" }));
    expect(invokeMock).toHaveBeenCalledWith("open_vault_item_url", {
      request: { schemaVersion: 1, id: "9007199254740995" },
    });

    await user.click(screen.getAllByRole("button", { name: "下移" })[0]);
    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("reorder_vault_items", {
        request: {
          schemaVersion: 1,
          ids: ["9007199254740995", "9007199254740993"],
        },
      });
    });
  });

  it("rekeys without retaining either password in the dialog", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_vault_status") return STATUS_UNLOCKED as never;
      if (command === "list_vault_items") return [] as never;
      if (command === "change_vault_password") return undefined as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    render(<VaultPage />);
    await user.click(await screen.findByRole("button", { name: "修改密码" }));
    await user.type(screen.getByLabelText("当前密码"), "old");
    await user.type(screen.getByLabelText("新密码"), "new🔑");
    await user.type(screen.getByLabelText("确认新密码"), "new🔑");
    await user.click(screen.getByRole("button", { name: "修改密码" }));

    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("change_vault_password", {
        request: {
          schemaVersion: 1,
          currentPassword: "old",
          newPassword: "new🔑",
        },
      });
    });
    expect(screen.queryByDisplayValue("old")).not.toBeInTheDocument();
    expect(screen.queryByDisplayValue("new🔑")).not.toBeInTheDocument();
  });

  it("locks explicitly and removes decrypted items from the frontend", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_vault_status") return STATUS_UNLOCKED as never;
      if (command === "list_vault_items") return [...ITEMS] as never;
      if (command === "lock_vault") {
        return {
          schemaVersion: 1,
          lockState: "LOCKED",
          corruptedItemCount: 0,
        } as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });

    render(<VaultPage />);
    expect(await screen.findByText("first secret")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "锁定" }));

    expect(invokeMock).toHaveBeenCalledWith("lock_vault", undefined);
    expect(
      await screen.findByRole("heading", { name: "解锁收藏夹" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("first secret")).not.toBeInTheDocument();
  });

  it("fails closed when Rust reports that the vault session changed", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_vault_status") return STATUS_UNLOCKED as never;
      if (command === "list_vault_items") return [...ITEMS] as never;
      if (command === "copy_vault_item") {
        throw {
          code: "vault_session_changed",
          message: "private backend context",
        };
      }
      throw new Error(`Unexpected command: ${command}`);
    });

    render(<VaultPage />);
    await user.click(
      (await screen.findAllByRole("button", { name: "复制正文" }))[0],
    );

    expect(
      await screen.findByRole("heading", { name: "解锁收藏夹" }),
    ).toBeInTheDocument();
    expect(screen.getByText("收藏夹会话已变化，请重新解锁后重试。")).toBeInTheDocument();
    expect(screen.queryByText("first secret")).not.toBeInTheDocument();
    expect(screen.queryByText("private backend context")).not.toBeInTheDocument();
  });
});
