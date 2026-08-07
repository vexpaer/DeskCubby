import { invoke } from "@tauri-apps/api/core";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { AiSettings } from "../../lib/aiApi";
import { useAppStore } from "../../store/appStore";
import AiSettingsPage from "./AiSettingsPage";

const SECRET = "sk-full-plaintext-value";
const SETTINGS: AiSettings = {
  schemaVersion: 1,
  configs: [
    {
      id: "text-1",
      name: "主模型",
      type: "TEXT",
      endpointUrl: "https://example.com/v1/chat/completions",
      model: "model-1",
      enabled: true,
      allowInsecureHttp: false,
      temperature: 0.7,
      systemPrompt: "system",
      apiKey: SECRET,
    },
  ],
  aiChatConfigId: "text-1",
  calorieEstimationEnabled: false,
  calorieTextConfigId: null,
  calorieImageConfigId: null,
  calorieVisionPrompt: "vision",
  calorieTextPrompt: "text",
};

describe("AiSettingsPage", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => {
    useAppStore.setState((state) => ({
      ...state,
      appearance: { ...state.appearance, language: "zh-CN" },
    }));
    invokeMock.mockReset();
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "get_ai_settings") return structuredClone(SETTINGS) as never;
      if (command === "save_ai_settings") {
        return structuredClone((args as { request: { settings: AiSettings } }).request.settings) as never;
      }
      throw new Error(`Unexpected command: ${command}`);
    });
  });

  function renderPage() {
    const router = createMemoryRouter(
      [{ path: "/settings/ai", element: <AiSettingsPage /> }],
      { initialEntries: ["/settings/ai"] },
    );
    return render(<RouterProvider router={router} />);
  }

  it("shows the full plaintext key while redacting it from request preview", async () => {
    const user = userEvent.setup();
    const { container } = renderPage();
    const key = await screen.findByRole("textbox", { name: "API Key（明文完整显示）" });
    expect(key).toHaveAttribute("type", "text");
    expect(key).toHaveValue(SECRET);

    await user.click(screen.getByRole("button", { name: "请求预览" }));
    const preview = container.querySelector(".ai-request-preview");
    expect(preview).toHaveTextContent("Bearer <configured>");
    expect(preview).not.toHaveTextContent(SECRET);
  });

  it("keeps edits local until the top-right save action", async () => {
    const user = userEvent.setup();
    renderPage();
    const name = await screen.findByRole("textbox", { name: "配置名称" });
    await user.clear(name);
    await user.type(name, "新名称");
    expect(invokeMock).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole("button", { name: "保存" }));
    expect(invokeMock).toHaveBeenCalledWith(
      "save_ai_settings",
      expect.objectContaining({
        request: expect.objectContaining({
          schemaVersion: 1,
          settings: expect.objectContaining({
            configs: [expect.objectContaining({ name: "新名称", apiKey: SECRET })],
          }),
        }),
      }),
    );
  });
});
