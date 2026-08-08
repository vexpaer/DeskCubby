import { invoke } from "@tauri-apps/api/core";
import { listen, type Event as TauriEvent } from "@tauri-apps/api/event";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useAppStore } from "../store/appStore";
import AboutPage from "./AboutPage";

describe("AboutPage", () => {
  const invokeMock = vi.mocked(invoke);
  const listenMock = vi.mocked(listen);

  beforeEach(() => {
    useAppStore.setState((state) => ({
      ...state,
      appearance: { ...state.appearance, language: "zh-CN" },
    }));
    invokeMock.mockReset();
    listenMock.mockReset();
    listenMock.mockResolvedValue(vi.fn());
  });

  it("uses the shared DeskCubby logo as a decorative brand mark", async () => {
    invokeMock.mockResolvedValue({
      schemaVersion: 1,
      configured: false,
      currentVersion: "0.3.0",
      automaticChecksEnabled: true,
    } as never);

    const { container } = render(<AboutPage />);
    await screen.findByText("此构建没有配置可信更新源或更新公钥。");

    const image = container.querySelector<HTMLImageElement>(
      ".about-app-mark img",
    );
    expect(image).not.toBeNull();
    expect(image).toHaveAttribute("alt", "");
    expect(image?.src).toMatch(/deskcubby\.png$/);
  });

  it("checks, displays and installs an exact signed updater version", async () => {
    const user = userEvent.setup();
    const unlisten = vi.fn();
    let progressListener:
      | ((event: TauriEvent<unknown>) => void)
      | undefined;
    listenMock.mockImplementation(async (event, handler) => {
      if (event === "update-download-progress") {
        progressListener = handler as (event: TauriEvent<unknown>) => void;
      }
      return unlisten;
    });
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "get_update_state") {
        return {
          schemaVersion: 1,
          configured: true,
          currentVersion: "0.1.0",
          automaticChecksEnabled: true,
        } as never;
      }
      if (command === "check_for_updates") {
        return {
          schemaVersion: 1,
          kind: "AVAILABLE",
          currentVersion: "0.1.0",
          version: "0.2.0",
          notes: "Safer sync and vault support.",
          publishedAt: "2026-07-29T00:00:00Z",
        } as never;
      }
      if (command === "install_update") {
        expect(args).toEqual({
          request: { schemaVersion: 1, expectedVersion: "0.2.0" },
        });
        return new Promise(() => undefined) as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });

    const { unmount } = render(<AboutPage />);
    await user.click(await screen.findByRole("button", { name: "检查更新" }));
    expect(await screen.findByText("0.2.0")).toBeInTheDocument();
    expect(screen.getByText("Safer sync and vault support.")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "下载、验证并安装" }));
    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("install_update", {
        request: { schemaVersion: 1, expectedVersion: "0.2.0" },
      });
    });

    act(() => {
      progressListener?.({
        event: "update-download-progress",
        id: 1,
        payload: {
          schemaVersion: 2,
          downloadedBytes: "1048576",
          totalBytes: "2097152",
        },
      });
    });
    expect(
      screen.getByRole("progressbar", { name: "更新下载进度" }),
    ).not.toHaveAttribute("aria-valuenow");

    act(() => {
      progressListener?.({
        event: "update-download-progress",
        id: 2,
        payload: {
          schemaVersion: 1,
          downloadedBytes: "1048576",
          totalBytes: "2097152",
        },
      });
    });
    expect(
      screen.getByRole("progressbar", { name: "更新下载进度" }),
    ).toHaveAttribute("aria-valuenow", "50");
    expect(screen.getByText("1 MiB / 2 MiB")).toBeInTheDocument();

    unmount();
    await waitFor(() => expect(unlisten).toHaveBeenCalledTimes(1));
  });

  it("does not claim update trust when the build is not configured", async () => {
    invokeMock.mockResolvedValue({
      schemaVersion: 1,
      configured: false,
      currentVersion: "0.1.0",
      automaticChecksEnabled: true,
    } as never);

    render(<AboutPage />);
    expect(
      await screen.findByText("此构建没有配置可信更新源或更新公钥。"),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "此构建未配置可信更新源或更新器公钥，因此不会发起更新请求。",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "检查更新" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("checkbox", { name: "启动时自动检查更新" }),
    ).toBeDisabled();
    expect(screen.getByText("不可用")).toBeInTheDocument();
    expect(invokeMock).toHaveBeenCalledTimes(1);
    expect(invokeMock).toHaveBeenCalledWith("get_update_state", undefined);
  });

  it("saves the automatic-check preference and refreshes updater state", async () => {
    const user = userEvent.setup();
    let automaticChecksEnabled = true;
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "get_update_state") {
        return {
          schemaVersion: 1,
          configured: true,
          currentVersion: "0.2.0",
          automaticChecksEnabled,
        } as never;
      }
      if (command === "set_automatic_update_checks") {
        expect(args).toEqual({
          request: { schemaVersion: 1, enabled: false },
        });
        automaticChecksEnabled = false;
        return undefined as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });

    render(<AboutPage />);
    const toggle = await screen.findByRole("checkbox", {
      name: "启动时自动检查更新",
    });
    expect(toggle).toBeChecked();
    await user.click(toggle);

    await waitFor(() => expect(toggle).not.toBeChecked());
    expect(screen.getByText("已关闭")).toBeInTheDocument();
    expect(invokeMock).toHaveBeenCalledWith("set_automatic_update_checks", {
      request: { schemaVersion: 1, enabled: false },
    });
    expect(
      invokeMock.mock.calls.filter(([command]) => command === "get_update_state"),
    ).toHaveLength(2);
  });
});
