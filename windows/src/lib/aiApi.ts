import { listen, type UnlistenFn } from "@tauri-apps/api/event";

import { invokeCommand } from "./ipc";

export const AI_SCHEMA_VERSION = 1 as const;

export const DEFAULT_CALORIE_VISION_PROMPT =
  '你是谨慎的餐食视觉记录助手。识别图片中所有可食用食物和饮料，按主食、蛋白质、蔬菜、水果、酱汁/油和饮料等实际组成拆分；估计可食用部分的数值分量与单位，餐具和装饰不要算作食物，同一食物不要重复列出。只返回 JSON，不要 Markdown：{"foods":[{"name":"食物名称","amount":"估计数值或范围","unit":"g、ml、个或份","confidence":0.0}],"sceneNotes":"烹饪方式、遮挡和份量不确定性"}。看不清时给出保守的合理范围并降低 confidence，不要虚构无法从图片推断的品牌或配方。';

export const DEFAULT_CALORIE_TEXT_PROMPT =
  '你是谨慎的营养能量估算助手。根据随后 JSON 中同一天 photos 的 recognizedFoods、visionNotes 和可选 userNote，结合可食用分量、常见烹饪方式、可见油脂/酱汁与饮料统一估算当天各图片的能量；用户备注可用于判断多人分享、同一餐多角度拍摄、剩余比例或实际分量。综合全部图片避免重复计算，并在证据不足时采用中性的合理估值。按输入 photoIndex 为每张图片返回结果；确认是同一餐的重复角度时，可将重复图片记为 0 kJ。只返回 JSON，不要 Markdown：{"photos":[{"photoIndex":1,"energyKj":整数,"foods":[{"name":"食物名称","amount":"分量","unit":"单位","energyKj":整数}]}]}。所有能量使用千焦(kJ)，单张图片各项之和应与该图片总能量在合理舍入范围内一致。';

export type DecimalI64 = string;
export type AiModelType = "TEXT" | "IMAGE";
export type AiMessageRole = "USER" | "ASSISTANT" | "CONTEXT";
export type AiContextSource = "DIARY" | "THOUGHT";
export type AiCompletionStatus = "COMPLETED" | "FAILED";

export interface AiModelConfig {
  id: string;
  name: string;
  type: AiModelType;
  endpointUrl: string;
  model: string;
  enabled: boolean;
  allowInsecureHttp: boolean;
  temperature: number;
  systemPrompt: string;
  /**
   * Android v28 deliberately treats this as a normal, plaintext setting. It
   * is returned in full for editing and backup compatibility, but the request
   * builder must only place it in the Authorization header.
   */
  apiKey: string;
}

export interface AiSettings {
  schemaVersion: typeof AI_SCHEMA_VERSION;
  configs: AiModelConfig[];
  aiChatConfigId: string | null;
  calorieEstimationEnabled: boolean;
  calorieTextConfigId: string | null;
  calorieImageConfigId: string | null;
  calorieVisionPrompt: string;
  calorieTextPrompt: string;
}

export interface AiConversation {
  schemaVersion: typeof AI_SCHEMA_VERSION;
  id: DecimalI64;
  title: string;
  modelConfigId: string;
  createdAt: DecimalI64;
  updatedAt: DecimalI64;
}

export interface AiContextItem {
  source: AiContextSource;
  title: string;
  date: string;
  attribution: string;
  content: string;
}

export interface AiMessage {
  schemaVersion: typeof AI_SCHEMA_VERSION;
  id: DecimalI64;
  conversationId: DecimalI64;
  role: AiMessageRole;
  content: string;
  reasoning: string;
  hasImage: boolean;
  imageMimeType: string | null;
  contextItems: AiContextItem[];
  createdAt: DecimalI64;
}

export interface AiContextCandidate {
  schemaVersion: typeof AI_SCHEMA_VERSION;
  source: AiContextSource;
  reference: string;
  title: string;
  subtitle: string;
  groupTitle: string;
  previewExcerpt: string;
  previewIsExcerpt: boolean;
  estimatedBytes: number | null;
}

export interface AiContextSelection {
  source: AiContextSource;
  reference: string;
}

export interface AiAttachment {
  schemaVersion: typeof AI_SCHEMA_VERSION;
  token: string;
  displayName: string;
  mimeType: string;
  byteSize: number;
}

export interface AiSendInput {
  requestToken: string;
  conversationId: DecimalI64 | null;
  modelConfigId: string | null;
  content: string;
  attachmentToken: string | null;
  contexts: AiContextSelection[];
}

export interface AiStreamUpdate {
  schemaVersion: typeof AI_SCHEMA_VERSION;
  requestToken: string;
  content: string;
  reasoning: string;
}

export interface AiSendResult {
  schemaVersion: typeof AI_SCHEMA_VERSION;
  status: AiCompletionStatus;
  errorCode: string | null;
  conversation: AiConversation;
  messages: AiMessage[];
}

export interface MealFoodEnergy {
  name: string;
  amount: string;
  unit: string;
  energyKj: number;
}

export interface MealEnergyEstimate {
  fileName: string;
  energyKj: number;
  foods: MealFoodEnergy[];
}

export interface CalorieProgress {
  schemaVersion: typeof AI_SCHEMA_VERSION;
  requestToken: string;
  stage: string;
  completedImages: number;
  totalImages: number;
  photoIndex: number | null;
  content: string;
  reasoning: string;
}

export interface EstimateMealDayResult {
  schemaVersion: typeof AI_SCHEMA_VERSION;
  dateIso: string;
  estimates: MealEnergyEstimate[];
}

export interface OpenAiRequestPreview {
  method: "POST";
  url: string;
  headers: {
    "Content-Type": "application/json";
    Authorization: "Bearer <configured>" | "Not configured";
  };
  body: {
    model: string;
    stream: true;
    temperature: number;
    messages: Array<{ role: "system" | "user"; content: string }>;
  };
}

export function createAiRequestToken(): string {
  return crypto.randomUUID();
}

/** A user-visible preview that can never copy the plaintext key into JSON. */
export function buildOpenAiRequestPreview(
  config: AiModelConfig,
): OpenAiRequestPreview {
  const messages: OpenAiRequestPreview["body"]["messages"] = [];
  if (config.systemPrompt.trim()) {
    messages.push({ role: "system", content: config.systemPrompt });
  }
  messages.push({ role: "user", content: "<message content is inserted when sent>" });
  return {
    method: "POST",
    url: config.endpointUrl,
    headers: {
      "Content-Type": "application/json",
      Authorization: config.apiKey.trim()
        ? "Bearer <configured>"
        : "Not configured",
    },
    body: {
      model: config.model,
      stream: true,
      temperature: config.temperature,
      messages,
    },
  };
}

export const aiApi = {
  settings(): Promise<AiSettings> {
    return invokeCommand("get_ai_settings");
  },

  saveSettings(settings: AiSettings): Promise<AiSettings> {
    return invokeCommand("save_ai_settings", {
      request: { schemaVersion: AI_SCHEMA_VERSION, settings },
    });
  },

  conversations(): Promise<AiConversation[]> {
    return invokeCommand("list_ai_conversations", {
      schemaVersion: AI_SCHEMA_VERSION,
    });
  },

  messages(conversationId: DecimalI64): Promise<AiMessage[]> {
    return invokeCommand("list_ai_messages", {
      request: { schemaVersion: AI_SCHEMA_VERSION, conversationId },
    });
  },

  renameConversation(
    conversationId: DecimalI64,
    title: string,
  ): Promise<AiConversation> {
    return invokeCommand("rename_ai_conversation", {
      request: { schemaVersion: AI_SCHEMA_VERSION, conversationId, title },
    });
  },

  deleteConversation(conversationId: DecimalI64): Promise<void> {
    return invokeCommand("delete_ai_conversation", {
      request: { schemaVersion: AI_SCHEMA_VERSION, conversationId },
    });
  },

  setConversationModel(
    conversationId: DecimalI64,
    modelConfigId: string,
  ): Promise<AiConversation> {
    return invokeCommand("set_ai_conversation_model", {
      request: {
        schemaVersion: AI_SCHEMA_VERSION,
        conversationId,
        modelConfigId,
      },
    });
  },

  contextCandidates(): Promise<AiContextCandidate[]> {
    return invokeCommand("list_ai_context_candidates", {
      schemaVersion: AI_SCHEMA_VERSION,
    });
  },

  pickImage(): Promise<AiAttachment | null> {
    return invokeCommand("pick_ai_image", {
      schemaVersion: AI_SCHEMA_VERSION,
    });
  },

  cancelImage(token: string): Promise<void> {
    return invokeCommand("cancel_ai_image", {
      request: { schemaVersion: AI_SCHEMA_VERSION, token },
    });
  },

  send(input: AiSendInput): Promise<AiSendResult> {
    return invokeCommand("send_ai_message", {
      request: { schemaVersion: AI_SCHEMA_VERSION, ...input },
    });
  },

  estimateMealDay(
    requestToken: string,
    dateIso: string,
    photoFileNames: string[],
  ): Promise<EstimateMealDayResult> {
    return invokeCommand("estimate_meal_day", {
      request: {
        schemaVersion: AI_SCHEMA_VERSION,
        requestToken,
        dateIso,
        photoFileNames,
      },
    });
  },

  onStreamUpdate(listener: (payload: AiStreamUpdate) => void): Promise<UnlistenFn> {
    return listen<AiStreamUpdate>("ai-stream-update", (event) => listener(event.payload));
  },

  onCalorieProgress(
    listener: (payload: CalorieProgress) => void,
  ): Promise<UnlistenFn> {
    return listen<CalorieProgress>("ai-calorie-progress", (event) =>
      listener(event.payload),
    );
  },
};
