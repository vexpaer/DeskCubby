import { invoke } from "@tauri-apps/api/core";
import { act, render, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useAppStore } from "../store/appStore";
import { ThemeRuntime } from "./ThemeRuntime";

describe("ThemeRuntime", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => {
    delete (
      window as Window & { __TAURI_INTERNALS__?: unknown }
    ).__TAURI_INTERNALS__;
    invokeMock.mockReset();
    invokeMock.mockResolvedValue(undefined as never);
    useAppStore.setState({
      appearance: {
        language: "zh-CN",
        visualTheme: "material",
        colorMode: "light",
        fontScale: 1,
        compactMode: false,
      },
    });
    document.documentElement.removeAttribute("style");
  });

  it("applies the selected appearance to the document", async () => {
    render(
      <ThemeRuntime>
        <span>content</span>
      </ThemeRuntime>,
    );

    await waitFor(() => {
      expect(document.documentElement.dataset.visualTheme).toBe("material");
      expect(document.documentElement.dataset.colorScheme).toBe("light");
      expect(document.documentElement.lang).toBe("zh-CN");
      expect(document.body).toHaveTextContent("content");
    });
  });

  it.each(["material", "liquid-glass", "organic-future"] as const)(
    "applies the %s theme token",
    async (visualTheme) => {
      useAppStore.setState((state) => ({
        appearance: { ...state.appearance, visualTheme },
      }));

      render(
        <ThemeRuntime>
          <span>content</span>
        </ThemeRuntime>,
      );

      await waitFor(() => {
        expect(document.documentElement.dataset.visualTheme).toBe(visualTheme);
      });
    },
  );

  it("syncs a saved Rust settings snapshot", async () => {
    render(
      <ThemeRuntime>
        <span>content</span>
      </ThemeRuntime>,
    );

    act(() => {
      window.dispatchEvent(
        new CustomEvent("deskcubby:settings-changed", {
          detail: {
            visualStyle: "LIQUID_GLASS",
            darkMode: "DARK",
            appLanguage: "ENGLISH",
            fontScale: 1.2,
            compactMode: true,
            themeColorArgb: 0xff336699 | 0,
            themeSecondaryColorsArgb: [0xff995533 | 0],
          },
        }),
      );
    });

    await waitFor(() => {
      expect(document.documentElement.dataset.visualTheme).toBe("liquid-glass");
      expect(document.documentElement.dataset.colorScheme).toBe("dark");
      expect(document.documentElement.lang).toBe("en");
      expect(document.documentElement.style.getPropertyValue("--primary")).toBe(
        "#336699",
      );
    });
  });

  it("reloads appearance after structured data is restored", async () => {
    let settingsReads = 0;
    invokeMock.mockImplementation(async (command) => {
      if (command !== "get_windows_settings") {
        throw new Error(`Unexpected command: ${command}`);
      }
      settingsReads += 1;
      return (settingsReads === 1
        ? {
            visualStyle: "MATERIAL",
            darkMode: "LIGHT",
            appLanguage: "CHINESE",
            fontScale: 1,
            compactMode: false,
            themeColorArgb: 0xff42664d | 0,
            themeSecondaryColorsArgb: [0xffc96f4a | 0],
          }
        : {
            visualStyle: "ORGANIC_FUTURE",
            darkMode: "DARK",
            appLanguage: "ENGLISH",
            fontScale: 1.25,
            compactMode: true,
            themeColorArgb: 0xff336633 | 0,
            themeSecondaryColorsArgb: [0xffaa6633 | 0],
          }) as never;
    });
    render(
      <ThemeRuntime>
        <span>content</span>
      </ThemeRuntime>,
    );
    await waitFor(() => {
      expect(document.documentElement.dataset.visualTheme).toBe("material");
      expect(document.body).toHaveTextContent("content");
    });

    act(() => {
      window.dispatchEvent(new CustomEvent("deskcubby:data-restored"));
    });

    await waitFor(() => {
      expect(settingsReads).toBe(2);
      expect(document.documentElement.dataset.visualTheme).toBe(
        "organic-future",
      );
      expect(document.documentElement.dataset.colorScheme).toBe("dark");
      expect(document.documentElement.lang).toBe("en");
      expect(
        document.documentElement.style.getPropertyValue("--font-scale"),
      ).toBe("1.25");
    });
  });

  it("verifies the Rust IPC protocol before reading settings", async () => {
    Object.defineProperty(window, "__TAURI_INTERNALS__", {
      configurable: true,
      value: {},
    });
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_ipc_protocol") {
        return {
          schemaVersion: 2,
          minimumSupportedVersion: 1,
          appVersion: "0.2.0",
        } as never;
      }
      if (command === "get_windows_settings") return undefined as never;
      throw new Error(`Unexpected command: ${command}`);
    });

    render(
      <ThemeRuntime>
        <span>ready</span>
      </ThemeRuntime>,
    );
    await waitFor(() => {
      expect(document.body).toHaveTextContent("ready");
    });
    expect(invokeMock.mock.calls.map(([command]) => command)).toEqual([
      "get_ipc_protocol",
      "get_windows_settings",
    ]);
  });
});
