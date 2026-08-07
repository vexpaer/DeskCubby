import { invoke } from "@tauri-apps/api/core";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useAppStore } from "../store/appStore";
import UsagePage from "./UsagePage";

const SNAPSHOT = {
  dtoVersion: 2,
  source: {
    dtoVersion: 1,
    mode: "snapshot",
    state: "ready",
    displayName: "usage-statistics.json",
    canRefresh: true,
    lastSuccessfulReadAtMs: "1785254400000",
    lastAttemptAtMs: "1785254400000",
    sourceModifiedAtMs: "1785254300000",
  },
  trackingStartedOn: "2026-07-01",
  backfillCompletedThrough: "2026-07-27",
  anchorDate: "2026-07-28",
  selectedDeviceId: null,
  selectedPackageName: null,
  deviceChoices: [
    {
      deviceId: "11111111-1111-4111-8111-111111111111",
      deviceName: "Pixel",
      platform: "android",
      updatedAtEpochMillis: "1785254400000",
      recordedDays: 7,
    },
  ],
  overview: {
    rangeStartedOn: "2026-07-22",
    recordedDays: 7,
    totalMillis: "25200000",
    averageMillis: "3600000",
    highestDayMillis: "7200000",
    lastSevenAverageMillis: "3600000",
  },
  appChoices: [
    {
      packageName: "com.example.one",
      label: "Untrusted friendly label",
      rangeMillis: "18000000",
    },
  ],
  points: [
    {
      date: "2026-07-28",
      zoneId: "Asia/Shanghai",
      state: "OPEN",
      collectedAtEpochMillis: "1785254400000",
      valueMillis: "3600000",
    },
  ],
} as const;

function renderUsage() {
  return render(
    <MemoryRouter>
      <UsagePage />
    </MemoryRouter>,
  );
}

describe("UsagePage", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => {
    useAppStore.setState((state) => ({
      ...state,
      appearance: { ...state.appearance, language: "zh-CN" },
    }));
    invokeMock.mockReset();
  });

  it("offers snapshot import and a read-only link without any Windows tracking controls", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_usage_page") return null as never;
      if (command === "choose_usage_statistics_source") return SNAPSHOT as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    renderUsage();
    expect(await screen.findByText("还没有手机统计数据")).toBeInTheDocument();
    expect(invokeMock).toHaveBeenCalledWith("get_usage_page", {
      query: {
        dtoVersion: 2,
        range: "LAST_7_DAYS",
        packageName: null,
        deviceId: null,
      },
    });
    expect(screen.queryByText("开启统计")).not.toBeInTheDocument();
    expect(screen.queryByText("申请权限")).not.toBeInTheDocument();

    await user.click(screen.getAllByRole("button", { name: "导入快照" })[0]);
    expect(invokeMock).toHaveBeenCalledWith("choose_usage_statistics_source", {
      request: { dtoVersion: 2, mode: "snapshot" },
    });
    expect(await screen.findByText("usage-statistics.json")).toBeInTheDocument();
  });

  it("separates loading and safe retryable error states from an empty source", async () => {
    const user = userEvent.setup();
    let rejectLoad: ((reason: unknown) => void) | undefined;
    invokeMock.mockImplementationOnce(
      () =>
        new Promise((_, reject) => {
          rejectLoad = reject;
        }) as never,
    );

    renderUsage();
    expect(screen.getByText("正在读取手机统计")).toBeInTheDocument();
    await act(async () => {
      rejectLoad?.({
        code: "usage_statistics_invalid",
        message: "private source path",
      });
    });

    expect(
      await screen.findByRole("heading", { name: "无法读取手机统计" }),
    ).toBeInTheDocument();
    expect(screen.getByText(/Android v27/)).toBeInTheDocument();
    expect(screen.queryByText("private source path")).not.toBeInTheDocument();

    invokeMock.mockResolvedValueOnce(null as never);
    await user.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("还没有手机统计数据")).toBeInTheDocument();
  });

  it("renders package names and an accessible chart while ignoring untrusted labels", async () => {
    const user = userEvent.setup();
    invokeMock.mockResolvedValue(SNAPSHOT as never);

    renderUsage();
    expect((await screen.findAllByText("com.example.one")).length).toBeGreaterThan(0);
    expect(screen.queryByText("Untrusted friendly label")).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", {
        name: /2026-07-28，1 小时 0 分钟，当天数据仍可刷新/,
      }),
    ).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("设备"), SNAPSHOT.deviceChoices[0].deviceId);
    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("get_usage_page", {
        query: {
          dtoVersion: 2,
          range: "LAST_7_DAYS",
          packageName: null,
          deviceId: SNAPSHOT.deviceChoices[0].deviceId,
        },
      });
    });

    await user.click(screen.getByRole("button", { name: "近 30 天" }));
    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("get_usage_page", {
        query: {
          dtoVersion: 2,
          range: "LAST_30_DAYS",
          packageName: null,
          deviceId: SNAPSHOT.deviceChoices[0].deviceId,
        },
      });
    });

    await user.click(screen.getByRole("button", { name: "刷新" }));
    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("refresh_usage_statistics", {
        query: {
          dtoVersion: 2,
          range: "LAST_30_DAYS",
          packageName: null,
          deviceId: SNAPSHOT.deviceChoices[0].deviceId,
        },
      });
    });
  });
});
