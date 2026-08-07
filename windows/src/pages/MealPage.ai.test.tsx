import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { MealPhoto } from "../lib/ipc";
import { useAppStore } from "../store/appStore";
import MealPage from "./MealPage";

const PHOTO: MealPhoto = {
  id: "photo-1",
  fileName: "meal.jpg",
  diaryRelativePath: "2026-08-07.md",
  date: "2026-08-07",
  category: "lunch",
  caption: "午餐",
  energyKj: null,
  location: null,
  latitude: null,
  longitude: null,
  assetUrl: null,
  missing: false,
};

describe("MealPage AI calorie integration", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => {
    useAppStore.setState((state) => ({
      ...state,
      appearance: { ...state.appearance, language: "zh-CN" },
    }));
    invokeMock.mockReset();
  });

  it("refreshes the day only after the atomic estimate command succeeds", async () => {
    let listCalls = 0;
    invokeMock.mockImplementation(async (command) => {
      if (command === "list_meal_photos") {
        listCalls += 1;
        return [{ ...PHOTO, energyKj: listCalls > 1 ? 2100 : null }] as never;
      }
      if (command === "estimate_meal_day") {
        return {
          schemaVersion: 1,
          dateIso: PHOTO.date,
          estimates: [{ fileName: PHOTO.fileName, energyKj: 2100, foods: [] }],
        } as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });
    const user = userEvent.setup();
    render(<MealPage />);

    await user.click(await screen.findByRole("button", { name: "AI 估算热量" }));
    await screen.findByText("2100 kJ");
    expect(listCalls).toBe(2);
    expect(invokeMock).toHaveBeenCalledWith("estimate_meal_day", {
      request: {
        schemaVersion: 1,
        requestToken: expect.any(String),
        dateIso: "2026-08-07",
        photoFileNames: ["meal.jpg"],
      },
    });
  });

  it("does not refresh or claim partial success when estimation fails", async () => {
    let listCalls = 0;
    invokeMock.mockImplementation(async (command) => {
      if (command === "list_meal_photos") {
        listCalls += 1;
        return [PHOTO] as never;
      }
      if (command === "estimate_meal_day") {
        throw { code: "ai_network_failed", message: "private endpoint response" };
      }
      throw new Error(`Unexpected command: ${command}`);
    });
    const user = userEvent.setup();
    render(<MealPage />);

    await user.click(await screen.findByRole("button", { name: "AI 估算热量" }));
    await waitFor(() => expect(screen.getByRole("alert")).toBeInTheDocument());
    expect(listCalls).toBe(1);
    expect(screen.queryByText(/已完成 2026-08-07/)).not.toBeInTheDocument();
    expect(screen.queryByText(/private endpoint response/)).not.toBeInTheDocument();
  });
});
