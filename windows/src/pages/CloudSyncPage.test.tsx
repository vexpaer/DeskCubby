import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useAppStore } from "../store/appStore";
import CloudSyncPage from "./CloudSyncPage";

const CONFIG = {
  id: "config-1",
  name: "My WebDAV",
  enabled: true,
  serviceType: "WEBDAV",
  endpointUrl: "https://dav.example.com/",
  remotePath: "DeskCubby",
  userAgent: "DeskCubby-Sync/1",
  webDavUsername: "alice",
  s3Bucket: "",
  s3Region: "us-east-1",
  s3PathStyle: true,
  allowInsecureHttp: false,
  selectedContents: ["DIARIES", "MEDIA"],
  direction: "TWO_WAY",
  hasCredentials: true,
} as const;

const STATE = {
  schemaVersion: 1,
  globalEnabled: true,
  configs: [CONFIG],
  status: {
    running: false,
    runningConfigId: null,
    phase: null,
    lastCompletedAt: null,
    lastErrorCode: null,
  },
} as const;

function renderCloud() {
  const router = createMemoryRouter([
    { path: "/settings/data/sync", element: <CloudSyncPage /> },
    { path: "/backup", element: <p>Backup</p> },
  ], { initialEntries: ["/settings/data/sync"] });
  return render(<RouterProvider router={router} />);
}

describe("CloudSyncPage", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => {
    useAppStore.setState((state) => ({
      ...state,
      appearance: { ...state.appearance, language: "zh-CN" },
      dirtyScopes: [],
    }));
    invokeMock.mockReset();
  });

  it("keeps the global switch as a draft and preserves stored credentials on edit", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "list_cloud_sync_configs") return STATE as never;
      if (command === "list_pending_cloud_json") return [] as never;
      if (command === "set_cloud_sync_enabled") return undefined as never;
      if (command === "save_cloud_sync_config") return CONFIG as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    renderCloud();
    expect(await screen.findByText("My WebDAV")).toBeInTheDocument();

    await user.click(screen.getByRole("checkbox", { name: "已开启" }));
    expect(
      invokeMock.mock.calls.some(([command]) => command === "set_cloud_sync_enabled"),
    ).toBe(false);
    await user.click(screen.getByRole("button", { name: "保存" }));
    expect(invokeMock).toHaveBeenCalledWith("set_cloud_sync_enabled", {
      request: { schemaVersion: 1, enabled: false },
    });

    await user.click(screen.getByRole("button", { name: "编辑配置" }));
    const name = await screen.findByLabelText("配置名称");
    expect(screen.queryByLabelText("密码")).not.toBeInTheDocument();
    expect(screen.getByText("已在本机加密保存，不会回传到界面。")).toBeInTheDocument();
    await user.clear(name);
    await user.type(name, "Renamed WebDAV");
    await user.click(screen.getByRole("button", { name: "保存配置" }));

    await waitFor(() => {
      const call = invokeMock.mock.calls.find(
        ([command]) => command === "save_cloud_sync_config",
      );
      expect(call?.[1]).toMatchObject({
        request: {
          schemaVersion: 1,
          config: { id: "config-1", name: "Renamed WebDAV" },
          credentialUpdate: { mode: "preserve" },
        },
      });
    });
    expect(screen.queryByDisplayValue("Renamed WebDAV")).not.toBeInTheDocument();
  });

  it("defaults reading progress only for new configurations", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "list_cloud_sync_configs") return STATE as never;
      if (command === "list_pending_cloud_json") return [] as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    renderCloud();
    await user.click(await screen.findByRole("button", { name: "编辑配置" }));
    expect(screen.getByRole("checkbox", { name: "阅读进度" })).not.toBeChecked();
    await user.click(screen.getByRole("button", { name: "关闭" }));
    await user.click((await screen.findAllByRole("button", { name: "新增配置" }))[0]);
    expect(screen.getByRole("checkbox", { name: "阅读进度" })).toBeChecked();
  });

  it("requires explicit confirmation before saving an HTTP configuration", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "list_cloud_sync_configs") {
        return { ...STATE, configs: [] } as never;
      }
      if (command === "list_pending_cloud_json") return [] as never;
      if (command === "save_cloud_sync_config") return CONFIG as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    renderCloud();
    await user.click((await screen.findAllByRole("button", { name: "新增配置" }))[0]);
    await user.type(screen.getByLabelText("配置名称"), "LAN DAV");
    await user.type(screen.getByLabelText("服务地址"), "http://192.168.1.2/dav/");
    await user.click(screen.getByRole("checkbox", { name: "允许 HTTP（仅可信局域网）" }));
    await user.type(screen.getByLabelText("密码"), "local-secret");
    await user.click(screen.getByRole("button", { name: "保存配置" }));

    expect(
      await screen.findByRole("heading", { name: "允许未加密 HTTP？" }),
    ).toBeInTheDocument();
    expect(
      invokeMock.mock.calls.some(([command]) => command === "save_cloud_sync_config"),
    ).toBe(false);

    await user.click(screen.getByRole("button", { name: "我了解风险，继续" }));
    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith(
        "save_cloud_sync_config",
        expect.objectContaining({
          request: expect.objectContaining({
            credentialUpdate: {
              mode: "replace",
              webDavPassword: "local-secret",
            },
          }),
        }),
      );
    });
    expect(screen.queryByDisplayValue("local-secret")).not.toBeInTheDocument();
  });

  it("keeps credential input after a failed save and reuses it on retry", async () => {
    const user = userEvent.setup();
    let saveAttempts = 0;
    invokeMock.mockImplementation(async (command) => {
      if (command === "list_cloud_sync_configs") {
        return { ...STATE, configs: [] } as never;
      }
      if (command === "list_pending_cloud_json") return [] as never;
      if (command === "save_cloud_sync_config") {
        saveAttempts += 1;
        if (saveAttempts === 1) {
          throw { code: "cloud_sync_storage" };
        }
        return { ...CONFIG, hasCredentials: true } as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });

    renderCloud();
    await user.click((await screen.findAllByRole("button", { name: "新增配置" }))[0]);
    await user.type(screen.getByLabelText("配置名称"), "Retry DAV");
    await user.type(screen.getByLabelText("服务地址"), "https://dav.example.com/");
    const password = screen.getByLabelText("密码");
    await user.type(password, "keep-this-secret");
    await user.click(screen.getByRole("button", { name: "保存配置" }));

    await waitFor(() => {
      expect(saveAttempts).toBe(1);
      expect(screen.getByRole("button", { name: "保存配置" })).toBeEnabled();
    });
    expect(screen.getByLabelText("密码")).toHaveValue("keep-this-secret");
    expect(screen.getByLabelText("配置名称")).toHaveValue("Retry DAV");

    await user.click(screen.getByRole("button", { name: "保存配置" }));
    await waitFor(() => expect(saveAttempts).toBe(2));
    const saveCalls = invokeMock.mock.calls.filter(
      ([command]) => command === "save_cloud_sync_config",
    );
    expect(saveCalls[1]?.[1]).toMatchObject({
      request: {
        credentialUpdate: {
          mode: "replace",
          webDavPassword: "keep-this-secret",
        },
      },
    });
    await waitFor(() => {
      expect(screen.queryByDisplayValue("Retry DAV")).not.toBeInTheDocument();
    });
  });

  it("confirms before closing a dirty configuration draft", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "list_cloud_sync_configs") return STATE as never;
      if (command === "list_pending_cloud_json") return [] as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    renderCloud();
    await user.click(await screen.findByRole("button", { name: "编辑配置" }));
    const name = screen.getByLabelText("配置名称");
    await user.clear(name);
    await user.type(name, "Unsaved WebDAV");

    await user.click(screen.getByRole("button", { name: "关闭" }));
    expect(
      await screen.findByRole("heading", { name: "放弃未保存的修改？" }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("配置名称")).toHaveValue("Unsaved WebDAV");

    await user.click(screen.getByRole("button", { name: "继续编辑" }));
    expect(
      screen.queryByRole("heading", { name: "放弃未保存的修改？" }),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("配置名称")).toHaveValue("Unsaved WebDAV");

    await user.click(screen.getByRole("button", { name: "取消" }));
    expect(
      await screen.findByRole("heading", { name: "放弃未保存的修改？" }),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "放弃修改" }));
    await waitFor(() => {
      expect(screen.queryByLabelText("配置名称")).not.toBeInTheDocument();
    });
  });

  it("allows anonymous or username-only WebDAV without inventing a password", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "list_cloud_sync_configs") {
        return { ...STATE, configs: [] } as never;
      }
      if (command === "list_pending_cloud_json") return [] as never;
      if (command === "save_cloud_sync_config") {
        return { ...CONFIG, hasCredentials: false } as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });

    renderCloud();
    await user.click((await screen.findAllByRole("button", { name: "新增配置" }))[0]);
    await user.type(screen.getByLabelText("配置名称"), "Anonymous DAV");
    await user.type(screen.getByLabelText("服务地址"), "https://dav.example.com/");
    expect(screen.getByText("匿名或仅用户名的 WebDAV 可留空。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "保存配置" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "保存配置" }));

    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith(
        "save_cloud_sync_config",
        expect.objectContaining({
          request: expect.objectContaining({
            credentialUpdate: { mode: "clear" },
          }),
        }),
      );
    });
  });

  it("keeps an unsaved global draft when a configuration refreshes", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "list_cloud_sync_configs") return STATE as never;
      if (command === "list_pending_cloud_json") return [] as never;
      if (command === "copy_cloud_sync_config") {
        return { ...CONFIG, id: "config-copy", hasCredentials: false } as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });

    renderCloud();
    await user.click(await screen.findByRole("checkbox", { name: "已开启" }));
    await user.click(screen.getByRole("button", { name: "复制配置" }));

    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("copy_cloud_sync_config", {
        request: { schemaVersion: 1, id: "config-1" },
      });
    });
    expect(screen.getByRole("checkbox", { name: "已关闭" })).not.toBeChecked();
    expect(screen.getByRole("button", { name: "保存" })).toBeEnabled();
  });

  it("keeps manual sync available when background scheduling is disabled", async () => {
    invokeMock.mockImplementation(async (command) => {
      if (command === "list_cloud_sync_configs") {
        return { ...STATE, globalEnabled: false } as never;
      }
      if (command === "list_pending_cloud_json") return [] as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    renderCloud();
    expect(
      await screen.findByRole("button", { name: "全部立即同步" }),
    ).toBeEnabled();
    expect(screen.getByRole("button", { name: "立即同步" })).toBeEnabled();
    expect(
      screen.getByText(/只控制后台调度；手动“立即同步”始终可用/),
    ).toBeInTheDocument();
  });

  it("allows an enabled anonymous WebDAV configuration to sync", async () => {
    const user = userEvent.setup();
    const anonymous = { ...CONFIG, hasCredentials: false } as const;
    invokeMock.mockImplementation(async (command) => {
      if (command === "list_cloud_sync_configs") {
        return { ...STATE, configs: [anonymous] } as never;
      }
      if (command === "list_pending_cloud_json") return [] as never;
      if (command === "run_cloud_sync") {
        return {
          schemaVersion: 1,
          uploaded: 0,
          downloaded: 0,
          conflicts: 0,
          skipped: 0,
        } as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });

    renderCloud();
    await user.click(await screen.findByRole("button", { name: "立即同步" }));
    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("run_cloud_sync", {
        request: { schemaVersion: 1, configId: "config-1" },
      });
    });
  });

  it("requires explicit credential replacement after a binding field changes", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "list_cloud_sync_configs") return STATE as never;
      if (command === "list_pending_cloud_json") return [] as never;
      if (command === "save_cloud_sync_config") return CONFIG as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    renderCloud();
    await user.click(await screen.findByRole("button", { name: "编辑配置" }));
    const endpoint = screen.getByLabelText("服务地址");
    await user.clear(endpoint);
    await user.type(endpoint, "https://dav2.example.com/");

    expect(
      screen.getByText(
        "账号绑定字段已修改。旧凭据不会复用，请明确选择替换或清除。",
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "保存配置" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "替换" }));
    const password = screen.getByLabelText("密码");
    expect(password).toHaveAttribute("maxlength", "8192");
    await user.type(password, "new-local-secret");
    await user.click(screen.getByRole("button", { name: "保存配置" }));

    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith(
        "save_cloud_sync_config",
        expect.objectContaining({
          request: expect.objectContaining({
            config: expect.objectContaining({
              endpointUrl: "https://dav2.example.com/",
            }),
            credentialUpdate: {
              mode: "replace",
              webDavPassword: "new-local-secret",
            },
          }),
        }),
      );
    });
    expect(screen.queryByDisplayValue("new-local-secret")).not.toBeInTheDocument();
  });

  it("previews remote JSON before restoring with the confirmation token", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "list_cloud_sync_configs") return STATE as never;
      if (command === "list_pending_cloud_json") {
        return [
          {
            id: "pending-1",
            receivedAt: "2026-07-29T00:00:00Z",
            size: 1024,
            sourceLabel: "Home DAV",
          },
        ] as never;
      }
      if (command === "preview_pending_cloud_json") {
        return {
          schemaVersion: 1,
          id: "pending-1",
          confirmationToken: "token-1",
          formatVersion: 18,
          exportedAt: "2026-07-29T00:00:00Z",
          thoughtCount: 2,
          categoryCount: 1,
          dateRecordCount: 3,
          poemCount: 4,
        } as never;
      }
      if (command === "restore_pending_cloud_json") return undefined as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    renderCloud();
    await user.click(await screen.findByRole("button", { name: "预览并恢复" }));
    expect(
      await screen.findByRole("heading", { name: "恢复这份远端应用 JSON？" }),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "确认恢复" }));
    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("restore_pending_cloud_json", {
        request: {
          schemaVersion: 1,
          id: "pending-1",
          confirmationToken: "token-1",
        },
      });
    });
  });
});
