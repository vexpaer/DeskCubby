import { invoke } from "@tauri-apps/api/core";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { useAppStore } from "../store/appStore";
import HealthPage from "./HealthPage";

const SNAPSHOT = {
  dtoVersion: 1,
  source: {
    dtoVersion: 1,
    mode: "snapshot",
    state: "ready",
    displayName: "step-statistics.json",
    canRefresh: false,
    lastSuccessfulReadAtMs: "1785254400000",
    lastAttemptAtMs: "1785254400000",
    sourceModifiedAtMs: "1785254300000",
  },
  trackingStartedOn: "2026-07-01",
  anchorDate: "2026-07-28",
  metric: "STEPS",
  overview: {
    rangeStartedOn: "2026-07-27",
    recordedDays: 2,
    daysWithData: 1,
    total: "1234",
    averagePerDataDay: "1234",
    highestDay: "1234",
  },
  points: [
    {
      date: "2026-07-27",
      zoneId: "Asia/Shanghai",
      state: "FINAL",
      collectedAtEpochMillis: "1785168000000",
      value: "1234",
    },
    {
      date: "2026-07-28",
      zoneId: "Asia/Shanghai",
      state: "OPEN",
      collectedAtEpochMillis: "1785254400000",
      value: null,
    },
  ],
} as const;

function renderHealth() {
  return render(<HealthPage />);
}

describe("HealthPage", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => {
    useAppStore.setState((state) => ({
      ...state,
      appearance: { ...state.appearance, language: "zh-CN" },
    }));
    invokeMock.mockReset();
  });

  it("offers only explicit read-only Android sources and no Windows collection", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_health_page") return null as never;
      if (command === "choose_health_statistics_source") return SNAPSHOT as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    renderHealth();
    expect(await screen.findByText("还没有健康统计数据")).toBeInTheDocument();
    expect(invokeMock).toHaveBeenCalledWith("get_health_page", {
      query: {
        dtoVersion: 1,
        range: "LAST_30_DAYS",
        metric: "STEPS",
      },
    });
    expect(screen.queryByText("开启统计")).not.toBeInTheDocument();
    expect(screen.queryByText("申请权限")).not.toBeInTheDocument();
    expect(screen.getByText(/v29 按隐私设计不包含健康历史/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "选择健康文件" }));
    expect(invokeMock).toHaveBeenCalledWith("choose_health_statistics_source", {
      request: { dtoVersion: 1, mode: "snapshot" },
    });
    expect(await screen.findByText("step-statistics.json")).toBeInTheDocument();
  });

  it("renders missing values as unknown and explicit zero only when supplied", async () => {
    const user = userEvent.setup();
    invokeMock.mockImplementation(async (command, payload) => {
      if (command !== "get_health_page") throw new Error(`Unexpected command: ${command}`);
      const metric = (payload as { query: { metric: string } }).query.metric;
      if (metric === "DISTANCE") {
        return {
          ...SNAPSHOT,
          metric: "DISTANCE",
          overview: {
            ...SNAPSHOT.overview,
            daysWithData: 2,
            total: "987.5",
            averagePerDataDay: "493.75",
            highestDay: "987.5",
          },
          points: [
            { ...SNAPSHOT.points[0], value: "987.5" },
            { ...SNAPSHOT.points[1], value: "0" },
          ],
        } as never;
      }
      return SNAPSHOT as never;
    });

    renderHealth();
    expect((await screen.findAllByText("1,234 步")).length).toBeGreaterThan(0);
    expect(
      screen.getByRole("button", { name: /2026-07-28，无可信数据，当天数据仍可刷新/ }),
    ).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "距离" }));
    await waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith("get_health_page", {
        query: {
          dtoVersion: 1,
          range: "LAST_30_DAYS",
          metric: "DISTANCE",
        },
      });
    });
    expect(await screen.findByRole("button", { name: /2026-07-28，0 m/ })).toBeInTheDocument();
  });

  it("uses a safe retryable error and does not render backend details", async () => {
    const user = userEvent.setup();
    let rejectLoad: ((reason: unknown) => void) | undefined;
    invokeMock.mockImplementationOnce(
      () =>
        new Promise((_, reject) => {
          rejectLoad = reject;
        }) as never,
    );

    renderHealth();
    expect(screen.getByText("正在读取健康统计")).toBeInTheDocument();
    await act(async () => {
      rejectLoad?.({ code: "health_statistics_invalid", message: "C:\\private\\health.json" });
    });
    expect(await screen.findByRole("heading", { name: "无法读取健康统计" })).toBeInTheDocument();
    expect(screen.queryByText(/private\\health/)).not.toBeInTheDocument();

    invokeMock.mockResolvedValueOnce(null as never);
    await user.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("还没有健康统计数据")).toBeInTheDocument();
  });
});
