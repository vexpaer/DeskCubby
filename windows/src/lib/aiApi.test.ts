import { invoke } from "@tauri-apps/api/core";
import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  AI_SCHEMA_VERSION,
  aiApi,
  buildOpenAiRequestPreview,
  type AiModelConfig,
  type AiSettings,
} from "./aiApi";

const CONFIG: AiModelConfig = {
  id: "text-1",
  name: "Text",
  type: "TEXT",
  endpointUrl: "https://example.com/v1/chat/completions",
  model: "model-1",
  enabled: true,
  allowInsecureHttp: false,
  temperature: 0.7,
  systemPrompt: "Be useful",
  apiKey: "plain-secret-key",
};

const SETTINGS: AiSettings = {
  schemaVersion: AI_SCHEMA_VERSION,
  configs: [CONFIG],
  aiChatConfigId: CONFIG.id,
  calorieEstimationEnabled: false,
  calorieTextConfigId: null,
  calorieImageConfigId: null,
  calorieVisionPrompt: "vision",
  calorieTextPrompt: "text",
};

describe("aiApi", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => invokeMock.mockReset());

  it("shows only an Authorization placeholder in request previews", () => {
    const serialized = JSON.stringify(buildOpenAiRequestPreview(CONFIG));
    expect(serialized).toContain("Bearer <configured>");
    expect(serialized).not.toContain(CONFIG.apiKey);
    expect(serialized).not.toContain("apiKey");
  });

  it("saves the full v28-compatible setting through the versioned command", async () => {
    invokeMock.mockResolvedValue(SETTINGS as never);
    await aiApi.saveSettings(SETTINGS);
    expect(invokeMock).toHaveBeenCalledWith("save_ai_settings", {
      request: { schemaVersion: 1, settings: SETTINGS },
    });
  });

  it("sends only opaque image tokens and frozen context references", async () => {
    invokeMock.mockResolvedValue({} as never);
    await aiApi.send({
      requestToken: "00000000-0000-4000-8000-000000000000",
      conversationId: null,
      modelConfigId: "text-1",
      content: "question",
      attachmentToken: "11111111-1111-4111-8111-111111111111",
      contexts: [{ source: "DIARY", reference: "2026-08-07.md" }],
    });
    expect(invokeMock).toHaveBeenCalledWith("send_ai_message", {
      request: {
        schemaVersion: 1,
        requestToken: "00000000-0000-4000-8000-000000000000",
        conversationId: null,
        modelConfigId: "text-1",
        content: "question",
        attachmentToken: "11111111-1111-4111-8111-111111111111",
        contexts: [{ source: "DIARY", reference: "2026-08-07.md" }],
      },
    });
  });
});
