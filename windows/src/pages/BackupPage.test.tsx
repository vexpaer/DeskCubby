import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import BackupPage from "./BackupPage";

describe("BackupPage", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => {
    document.documentElement.lang = "zh-CN";
    invokeMock.mockReset();
    invokeMock.mockImplementation(async (command) => {
      if (command === "list_restore_points") return [] as never;
      throw new Error(`Unexpected command: ${command}`);
    });
  });

  it.each([1, 18, 27])("allows a validated Android v%s preview to be confirmed", async (formatVersion) => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "list_restore_points") return [] as never;
      if (command === "choose_and_preview_backup") {
        return {
          token: "76eb8778-3457-4f37-a979-9113b1770d9c",
          displayName: `android-v${formatVersion}.json`,
          preview: {
            formatVersion,
            exportedAt: "2026-08-07T00:00:00Z",
            thoughtCount: 0,
            categoryCount: 0,
            favoriteCount: 0,
            dateRecordCount: 0,
            poemCount: 0,
            preservedTopLevelKeys: [],
          },
        } as never;
      }
      if (command === "import_backup") {
        return {
          importedAt: "2026-08-07T00:00:00Z",
          thoughtCount: 0,
          categoryCount: 0,
          dateRecordCount: 0,
          poemCount: 0,
          usageDevicesMerged: true,
        } as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });

    render(<BackupPage />);
    await user.click(await screen.findByRole("button", { name: "选择并预览" }));

    const checkbox = await screen.findByRole("checkbox");
    expect(checkbox).toBeEnabled();
    await user.click(checkbox);
    await user.click(screen.getByRole("button", { name: "确认导入" }));

    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("import_backup", {
        token: "76eb8778-3457-4f37-a979-9113b1770d9c",
      });
    });
  });
});
