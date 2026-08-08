import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { MealPhoto, MealViewPreferences } from "../lib/ipc";
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

const VIEW_PREFERENCES: MealViewPreferences = {
  schemaVersion: 1,
  dayColumns: 1,
  wrapEnabled: false,
  columns: "smart",
  showCaptions: true,
  imageMaxHeightPx: 124,
  filter: {
    enabled: false,
    brightness: 100,
    contrast: 100,
    saturation: 100,
    warmth: 0,
    tint: 0,
  },
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
      if (command === "get_meal_view_preferences") {
        return VIEW_PREFERENCES as never;
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
      if (command === "get_meal_view_preferences") {
        return VIEW_PREFERENCES as never;
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

  it("hydrates and persists the non-destructive filter across remounts", async () => {
    let persisted: MealViewPreferences = {
      ...VIEW_PREFERENCES,
      wrapEnabled: true,
      filter: {
        ...VIEW_PREFERENCES.filter,
        enabled: true,
        brightness: 120,
      },
    };
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "list_meal_photos") {
        return [{ ...PHOTO, assetUrl: "http://media.localhost/meal.jpg" }] as never;
      }
      if (command === "get_meal_view_preferences") {
        return persisted as never;
      }
      if (command === "update_meal_view_preferences") {
        persisted = (args as { preferences: MealViewPreferences }).preferences;
        return persisted as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });
    const user = userEvent.setup();
    const first = render(<MealPage />);

    const filters = await screen.findByRole("button", { name: "滤镜" });
    await waitFor(() => expect(filters).toBeEnabled());
    await user.click(filters);
    const enabled = screen.getByRole("checkbox", { name: "启用滤镜" });
    expect(enabled).toBeChecked();
    const image = await screen.findByRole("img", { name: "午餐" });
    expect(image.style.filter).toContain("brightness(120%)");

    await user.click(enabled);
    await waitFor(() => {
      expect(persisted.filter.enabled).toBe(false);
      expect(invokeMock).toHaveBeenCalledWith("update_meal_view_preferences", {
        preferences: expect.objectContaining({
          schemaVersion: 1,
          filter: expect.objectContaining({ enabled: false, brightness: 120 }),
        }),
      });
    });
    expect(screen.getByRole("img", { name: "午餐" })).toHaveStyle({ filter: "none" });

    first.unmount();
    render(<MealPage />);
    const reopenedFilters = await screen.findByRole("button", { name: "滤镜" });
    await waitFor(() => expect(reopenedFilters).toBeEnabled());
    await user.click(reopenedFilters);
    expect(screen.getByRole("checkbox", { name: "启用滤镜" })).not.toBeChecked();
  });

  it("splits complete days at the midpoint and persists the layout toggle", async () => {
    const dates = ["2026-08-08", "2026-08-07", "2026-08-06", "2026-08-05"];
    let persisted: MealViewPreferences = {
      ...VIEW_PREFERENCES,
      dayColumns: 2,
      wrapEnabled: true,
    };
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "list_meal_photos") {
        return dates.map((date, index) => ({
          ...PHOTO,
          id: `photo-${index}`,
          fileName: `meal-${index}.jpg`,
          date,
        })) as never;
      }
      if (command === "get_meal_view_preferences") {
        return persisted as never;
      }
      if (command === "update_meal_view_preferences") {
        persisted = (args as { preferences: MealViewPreferences }).preferences;
        return persisted as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });
    const user = userEvent.setup();
    const { container } = render(<MealPage />);

    await screen.findByRole("heading", { name: dates[0], level: 2 });
    await waitFor(() => expect(screen.getByRole("button", { name: "单列" })).toBeEnabled());
    const columns = container.querySelectorAll(".meal-day-column");
    expect(columns).toHaveLength(2);
    expect(columns[0]).toHaveTextContent(dates[0]);
    expect(columns[0]).toHaveTextContent(dates[1]);
    expect(columns[0]).not.toHaveTextContent(dates[2]);
    expect(columns[1]).toHaveTextContent(dates[2]);
    expect(columns[1]).toHaveTextContent(dates[3]);
    for (const date of dates) {
      const day = screen
        .getByRole("heading", { name: date, level: 2 })
        .closest(".meal-day");
      expect(day).not.toBeNull();
      expect(day?.querySelectorAll(".meal-card")).toHaveLength(1);
    }

    await user.click(screen.getByRole("button", { name: "单列" }));
    await waitFor(() => expect(persisted.dayColumns).toBe(1));
    expect(container.querySelectorAll(".meal-day-column")).toHaveLength(1);
  });

  it("coalesces rapid preference changes so the latest choice is saved last", async () => {
    const updates: MealViewPreferences[] = [];
    let releaseFirst: () => void = () => undefined;
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "list_meal_photos") return [PHOTO] as never;
      if (command === "get_meal_view_preferences") {
        return VIEW_PREFERENCES as never;
      }
      if (command === "update_meal_view_preferences") {
        const preferences = (args as { preferences: MealViewPreferences }).preferences;
        updates.push(preferences);
        if (updates.length === 1) {
          await new Promise<void>((resolve) => {
            releaseFirst = resolve;
          });
        }
        return preferences as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });
    const user = userEvent.setup();
    render(<MealPage />);

    const twoColumns = await screen.findByRole("button", { name: "双列" });
    await waitFor(() => expect(twoColumns).toBeEnabled());
    await user.click(twoColumns);
    await user.click(screen.getByRole("button", { name: "单列" }));
    expect(updates).toHaveLength(1);
    expect(updates[0].dayColumns).toBe(2);

    releaseFirst();
    await waitFor(() => expect(updates).toHaveLength(2));
    expect(updates[1].dayColumns).toBe(1);
  });
});
