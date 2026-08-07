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
    delete document.documentElement.dataset.hasGlobalBackground;
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
            backgroundImagePath: "E:\\Pictures\\background.png",
            backgroundImageOpacity: 0.64,
            backgroundImageBlurPx: 12,
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
      const image = document.documentElement.style.getPropertyValue(
        "--global-background-image",
      );
      expect(image).toMatch(
        /^url\("http:\/\/background\.localhost\/current\?rev=\d+"\)$/,
      );
      expect(image).not.toContain("Pictures");
      expect(
        document.documentElement.style.getPropertyValue(
          "--global-background-opacity",
        ),
      ).toBe("0.64");
      expect(
        document.documentElement.style.getPropertyValue("--global-background-blur"),
      ).toBe("12px");
    });
  });

  it("does not create a protocol URL without a selected background and clears one immediately", async () => {
    render(
      <ThemeRuntime>
        <span>content</span>
      </ThemeRuntime>,
    );
    await waitFor(() => expect(document.body).toHaveTextContent("content"));
    expect(
      document.documentElement.style.getPropertyValue("--global-background-image"),
    ).toBe("");

    act(() => {
      window.dispatchEvent(
        new CustomEvent("deskcubby:settings-changed", {
          detail: {
            visualStyle: "MATERIAL",
            darkMode: "LIGHT",
            appLanguage: "CHINESE",
            fontScale: 1,
            compactMode: false,
            themeColorArgb: 0xff42664d | 0,
            themeSecondaryColorsArgb: [0xffc96f4a | 0],
            backgroundImagePath: "E:\\Pictures\\background.webp",
            backgroundImageOpacity: 0.45,
            backgroundImageBlurPx: 4,
          },
        }),
      );
    });
    expect(document.documentElement.dataset.hasGlobalBackground).toBe("true");

    act(() => {
      window.dispatchEvent(
        new CustomEvent("deskcubby:settings-changed", {
          detail: {
            visualStyle: "MATERIAL",
            darkMode: "LIGHT",
            appLanguage: "CHINESE",
            fontScale: 1,
            compactMode: false,
            themeColorArgb: 0xff42664d | 0,
            themeSecondaryColorsArgb: [0xffc96f4a | 0],
            backgroundImagePath: null,
            backgroundImageOpacity: 0.45,
            backgroundImageBlurPx: 0,
          },
        }),
      );
    });
    expect(document.documentElement.dataset.hasGlobalBackground).toBeUndefined();
    expect(
      document.documentElement.style.getPropertyValue("--global-background-image"),
    ).toBe("");
    expect(
      document.documentElement.style.getPropertyValue("--global-background-opacity"),
    ).toBe("");
    expect(
      document.documentElement.style.getPropertyValue("--global-background-blur"),
    ).toBe("");
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
            backgroundImagePath: "E:\\Pictures\\background.png",
            backgroundImageOpacity: 0.45,
            backgroundImageBlurPx: 0,
          }
        : {
            visualStyle: "ORGANIC_FUTURE",
            darkMode: "DARK",
            appLanguage: "ENGLISH",
            fontScale: 1.25,
            compactMode: true,
            themeColorArgb: 0xff336633 | 0,
            themeSecondaryColorsArgb: [0xffaa6633 | 0],
            backgroundImagePath: "E:\\Pictures\\background.png",
            backgroundImageOpacity: 0.7,
            backgroundImageBlurPx: 8,
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
    const initialBackground = document.documentElement.style.getPropertyValue(
      "--global-background-image",
    );

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
      expect(
        document.documentElement.style.getPropertyValue(
          "--global-background-opacity",
        ),
      ).toBe("0.7");
    });
    expect(
      document.documentElement.style.getPropertyValue("--global-background-image"),
    ).not.toBe(initialBackground);
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
