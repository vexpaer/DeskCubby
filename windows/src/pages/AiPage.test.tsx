import { invoke } from "@tauri-apps/api/core";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { AiSettings } from "../lib/aiApi";
import { useAppStore } from "../store/appStore";
import AiPage from "./AiPage";

const SETTINGS: AiSettings = {
  schemaVersion: 1,
  configs: [
    {
      id: "text-1",
      name: "文字模型",
      type: "TEXT",
      endpointUrl: "https://example.com/v1/chat/completions",
      model: "model-1",
      enabled: true,
      allowInsecureHttp: false,
      temperature: 0.7,
      systemPrompt: "",
      apiKey: "secret",
    },
  ],
  aiChatConfigId: "text-1",
  calorieEstimationEnabled: false,
  calorieTextConfigId: null,
  calorieImageConfigId: null,
  calorieVisionPrompt: "vision",
  calorieTextPrompt: "text",
};

function renderPage() {
  return render(<MemoryRouter><AiPage /></MemoryRouter>);
}

describe("AiPage", () => {
  const invokeMock = vi.mocked(invoke);
  let sentMessages: unknown[];

  beforeEach(() => {
    useAppStore.setState((state) => ({
      ...state,
      appearance: { ...state.appearance, language: "zh-CN" },
    }));
    invokeMock.mockReset();
    sentMessages = [];
    invokeMock.mockImplementation(async (command) => {
      if (command === "get_ai_settings") return SETTINGS as never;
      if (command === "list_ai_conversations") return [] as never;
      if (command === "list_ai_context_candidates") {
        return [
          {
            schemaVersion: 1,
            source: "THOUGHT",
            reference: "42",
            title: "待办想法",
            subtitle: "2026-08-07",
            groupTitle: "工作",
            previewExcerpt: "这只是发送前预览",
            previewIsExcerpt: false,
            estimatedBytes: 30,
          },
        ] as never;
      }
      if (command === "send_ai_message") {
        const result = {
          schemaVersion: 1,
          status: "COMPLETED",
          errorCode: null,
          conversation: {
            schemaVersion: 1,
            id: "1",
            title: "问题",
            modelConfigId: "text-1",
            createdAt: "1",
            updatedAt: "2",
          },
          messages: [
            {
              schemaVersion: 1,
              id: "1",
              conversationId: "1",
              role: "CONTEXT",
              content: "",
              reasoning: "",
              hasImage: false,
              imageMimeType: null,
              contextItems: [
                {
                  source: "THOUGHT",
                  title: "待办想法",
                  date: "2026-08-07",
                  attribution: "工作",
                  content: "发送时已经冻结的正文",
                },
              ],
              createdAt: "1",
            },
            {
              schemaVersion: 1,
              id: "2",
              conversationId: "1",
              role: "USER",
              content: "帮我整理",
              reasoning: "",
              hasImage: false,
              imageMimeType: null,
              contextItems: [],
              createdAt: "1",
            },
            {
              schemaVersion: 1,
              id: "3",
              conversationId: "1",
              role: "ASSISTANT",
              content: "最终回答",
              reasoning: "推理内容",
              hasImage: false,
              imageMimeType: null,
              contextItems: [],
              createdAt: "2",
            },
          ],
        };
        sentMessages = result.messages;
        return result as never;
      }
      if (command === "list_ai_messages") return sentMessages as never;
      throw new Error(`Unexpected command: ${command}`);
    });
  });

  it("sends selected references and renders frozen context plus reasoning", async () => {
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText("开始一段新对话")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "参考" }));
    await user.click(screen.getByRole("checkbox", { name: /待办想法/ }));
    await user.type(screen.getByRole("textbox", { name: "消息" }), "帮我整理");
    await user.click(screen.getByRole("button", { name: "发送" }));

    expect(await screen.findByText("最终回答")).toBeInTheDocument();
    expect(screen.getByText("推理内容")).toBeInTheDocument();
    expect(screen.getByText("发送时已经冻结的正文")).toBeInTheDocument();
    await waitFor(() =>
      expect(invokeMock).toHaveBeenCalledWith(
        "send_ai_message",
        expect.objectContaining({
          request: expect.objectContaining({
            contexts: [{ source: "THOUGHT", reference: "42" }],
          }),
        }),
      ),
    );
  });
});
