/**
 * 设置 → AI 设置 (README_for_ai §17.12)。
 *
 * Draft-based fields persist through the shell's 保存 (PUT /api/settings):
 * - AI 配置 CRUD rows (name/type/endpoint/model/temperature/systemPrompt/enabled/
 *   supportsToolCalling). API keys are write-only on Web: GET never returns the
 *   stored value, so an untouched key field keeps the server value and shows
 *   「已配置（留空保持不变）」.
 * - aiChatConfigId, agentEnabledSources (9 toggles), agentPermissionMode,
 *   agentPrompt + 恢复默认, aiPageFontSizeSp 12–28, aiReplyBoxWidthDp 280–1200.
 * - AI 热量估算: calorieEstimationEnabled + text/image model selects +
 *   vision/text prompts with exact Android defaults (AppModels.kt).
 *
 * reportInvalid blocks 保存 while any config row fails validation or the calorie
 * toggle lacks its required model selections.
 */
import React, { useEffect, useMemo } from "react";
import { Trash2 } from "lucide-react";
import type { AiModelConfig } from "../../../api/types";
import { tr } from "../../../i18n/tr";
import {
  SectionCard, Segmented, SelectField, SliderRow, TextField, Toggle,
} from "../SettingsPage";
import type { SettingsSectionProps } from "../SettingsPage";

// Mirrors DEFAULT_AGENT_PROMPT in AppModels.kt.
const DEFAULT_AGENT_PROMPT =
  "回答简洁、准确、友好，使用与用户当前使用的语言。Be concise, accurate, and friendly; reply in the user's language.";

// Exact copies of DEFAULT_CALORIE_VISION_PROMPT / DEFAULT_CALORIE_TEXT_PROMPT.
const DEFAULT_CALORIE_VISION_PROMPT = "你是谨慎的餐食视觉记录助手。识别图片中所有可食用食物和饮料，按主食、蛋白质、蔬菜、水果、酱汁/油和饮料等实际组成拆分；估计可食用部分的数值分量与单位，餐具和装饰不要算作食物，同一食物不要重复列出。只返回 JSON，不要 Markdown：{\"foods\":[{\"name\":\"食物名称\",\"amount\":\"估计数值或范围\",\"unit\":\"g、ml、个或份\",\"confidence\":0.0}],\"sceneNotes\":\"烹饪方式、遮挡和份量不确定性\"}。看不清时给出保守的合理范围并降低 confidence，不要虚构无法从图片推断的品牌或配方。";
const DEFAULT_CALORIE_TEXT_PROMPT = "你是谨慎的营养能量估算助手。根据随后 JSON 中同一天 photos 的 recognizedFoods、visionNotes 和可选 userNote，结合可食用分量、常见烹饪方式、可见油脂/酱汁与饮料统一估算当天各图片的能量；用户备注可用于判断多人分享、同一餐多角度拍摄、剩余比例或实际分量。综合全部图片避免重复计算，并在证据不足时采用中性的合理估值。按输入 photoIndex 为每张图片返回结果；确认是同一餐的重复角度时，可将重复图片记为 0 kJ。只返回 JSON，不要 Markdown：{\"photos\":[{\"photoIndex\":1,\"energyKj\":整数,\"foods\":[{\"name\":\"食物名称\",\"amount\":\"分量\",\"unit\":\"单位\",\"energyKj\":整数}]}]}。所有能量使用千焦(kJ)，单张图片各项之和应与该图片总能量在合理舍入范围内一致。";

/** AgentDataSource wire values with Android UI labels (AiChatScreen.kt). */
const AGENT_SOURCES: { value: string; zh: string; en: string; descZh: string; descEn: string }[] = [
  { value: "diary", zh: "日记", en: "Diary", descZh: "按日期检索与读取；允许创建、编辑、删除", descEn: "Search/read by date; create, edit, delete" },
  { value: "thoughts", zh: "小巧思", en: "Thoughts", descZh: "正文、分类与时间", descEn: "Text, categories, and timestamps" },
  { value: "date_records", zh: "日期记录", en: "Dates", descZh: "重要日期名称与日期", descEn: "Important dates and names" },
  { value: "daily_events", zh: "结构化记录", en: "Structured records", descZh: "日常记录模板", descEn: "Daily-record templates" },
  { value: "notes", zh: "笔记", en: "Notes", descZh: "笔记库内容", descEn: "Note tree contents" },
  { value: "poems", zh: "诗词本", en: "Poetry book", descZh: "收藏诗词与分类", descEn: "Saved poems and categories" },
  { value: "usage", zh: "手机使用时间", en: "Phone usage", descZh: "只读的按日/应用使用数据", descEn: "Read-only daily/app usage" },
  { value: "statistics", zh: "统计数据", en: "Statistics", descZh: "只读聚合统计", descEn: "Read-only aggregate statistics" },
  { value: "app_guide", zh: "应用指南", en: "App guide", descZh: "应用使用教学按章节索引；只读", descEn: "Read-only how-to guide indexed by section" },
];

const DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";
const MAX_PROMPT_CHARS = 20000;

function newConfig(): AiModelConfig {
  return {
    id: `ai-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
    name: "",
    type: "TEXT",
    endpointUrl: DEFAULT_ENDPOINT,
    model: "",
    enabled: true,
    allowInsecureHttp: false,
    temperature: 0.7,
    systemPrompt: "",
    apiKey: "",
    supportsToolCalling: false,
  };
}

function endpointValid(url: string, allowInsecureHttp: boolean): boolean {
  try {
    const parsed = new URL(url);
    if (!parsed.hostname) return false;
    if (parsed.protocol === "https:") return true;
    return allowInsecureHttp && parsed.protocol === "http:";
  } catch {
    return false;
  }
}

export default function AiSection(props: SettingsSectionProps) {
  const { draft, patch, snackbar, reportInvalid } = props;
  const configs = draft.aiConfigs ?? [];

  const updateConfig = (index: number, p: Partial<AiModelConfig>) => {
    patch({ aiConfigs: configs.map((c, i) => (i === index ? { ...c, ...p } : c)) });
  };
  const addConfig = () => patch({ aiConfigs: [...configs, newConfig()] });
  const duplicateConfig = (index: number) => {
    const src = configs[index];
    patch({
      aiConfigs: [
        ...configs,
        { ...src, id: `ai-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`, name: `${src.name} 2` },
      ],
    });
  };
  const removeConfig = (index: number) => {
    const removed = configs[index];
    const next = configs.filter((_, i) => i !== index);
    const extra: Partial<typeof draft> = {};
    if (draft.aiChatConfigId === removed.id) extra.aiChatConfigId = null;
    if (draft.calorieTextConfigId === removed.id) extra.calorieTextConfigId = null;
    if (draft.calorieImageConfigId === removed.id) extra.calorieImageConfigId = null;
    patch({ aiConfigs: next, ...extra });
  };

  const textConfigs = configs.filter((c) => c.type === "TEXT");
  const imageConfigs = configs.filter((c) => c.type === "IMAGE");
  const hasCalorieModels = textConfigs.length > 0 && imageConfigs.length > 0;
  const calorieReady =
    !draft.calorieEstimationEnabled ||
    (!!draft.calorieTextConfigId && !!draft.calorieImageConfigId);

  // Validation: every enabled row must be complete; endpoint must be https
  // unless 允许 HTTP is checked; calorie needs both models selected when on.
  const invalid = useMemo(() => {
    return (
      configs.some((c) => !c.name.trim() || !c.model.trim() || !endpointValid(c.endpointUrl, c.allowInsecureHttp)) ||
      !calorieReady ||
      configs.some((c) => c.endpointUrl.trim().length > 4096) ||
      configs.some((c) => c.model.length > 512)
    );
  }, [configs, calorieReady]);
  // Report validation asynchronously so the shell can block 保存.
  useEffect(() => {
    reportInvalid?.(invalid);
  });

  const sourceSet = new Set(draft.agentEnabledSources ?? []);
  const toggleSource = (value: string, on: boolean) => {
    const next = new Set(sourceSet);
    if (on) next.add(value);
    else next.delete(value);
    patch({ agentEnabledSources: AGENT_SOURCES.map((s) => s.value).filter((v) => next.has(v)) });
  };

  const modelOptions = (list: AiModelConfig[], fallbackLabel: string) => [
    { value: "", label: fallbackLabel },
    ...list.map((c) => ({ value: c.id, label: c.name || tr("未命名配置", "Unnamed configuration") })),
  ];

  return (
    <div className="dc-col" style={{ gap: 12 }}>
      <SectionCard
        title={tr("AI 配置", "AI configurations")}
        description={tr(
          "管理文字/图片模型配置；API Key 按 Android 规则明文显示并随配置保存。",
          "Manage text/image model configurations; API keys are shown in full and saved with the configuration, matching Android.",
        )}
      >
        {configs.length === 0 && (
          <div className="dc-muted" style={{ fontSize: "0.88em" }}>
            {tr("还没有配置，点「添加配置」新建一个模型。", "No configurations yet — add one below.")}
          </div>
        )}
        <div className="dc-col" style={{ gap: 10 }}>
          {configs.map((config, index) => {
            const epBad = !endpointValid(config.endpointUrl, config.allowInsecureHttp);
            const nameBad = !config.name.trim();
            const modelBad = !config.model.trim();
            return (
              <div key={config.id} className="dc-card dc-col" style={{ padding: 12, gap: 8 }}>
                <div className="dc-row" style={{ justifyContent: "space-between", gap: 8 }}>
                  <span style={{ fontWeight: 600 }}>
                    {config.type === "TEXT" ? tr("文字模型", "Text model") : tr("图片模型", "Image model")}
                    ·{config.name || tr("未命名配置", "Unnamed configuration")}
                  </span>
                  <button className="dc-icon-btn" style={{ width: 32, height: 32 }}
                    aria-label={tr("删除配置", "Delete configuration")}
                    onClick={() => removeConfig(index)}>
                    <Trash2 size={16} />
                  </button>
                </div>
                <Segmented<"TEXT" | "IMAGE">
                  value={config.type}
                  onChange={(v) => updateConfig(index, { type: v })}
                  options={[
                    { value: "TEXT", label: tr("文字模型", "Text model") },
                    { value: "IMAGE", label: tr("图片模型", "Image model") },
                  ]}
                />
                <TextField
                  label={tr("配置名称", "Configuration name")}
                  value={config.name}
                  error={nameBad}
                  onChange={(v) => updateConfig(index, { name: v.slice(0, 256) })}
                />
                <TextField
                  label={tr("API 地址", "API endpoint")}
                  value={config.endpointUrl}
                  maxLength={4096}
                  error={epBad}
                  placeholder={DEFAULT_ENDPOINT}
                  hint={epBad ? tr("必须是带主机名的 https 地址（勾选「允许 HTTP」后才接受 http）。", "Must be an https address with a host (http only after enabling Allow HTTP).") : undefined}
                  onChange={(v) => updateConfig(index, { endpointUrl: v })}
                />
                <TextField
                  label={tr("模型名称", "Model name")}
                  value={config.model}
                  maxLength={512}
                  error={modelBad}
                  onChange={(v) => updateConfig(index, { model: v })}
                />
                <TextField
                  label="API Key"
                  value={config.apiKey ?? ""}
                  maxLength={8192}
                  hint={tr(
                    "明文显示并随配置保存；不要在日志、请求预览或错误信息中输出。",
                    "Shown and stored as plain text; it must never appear in logs, request previews, or errors.",
                  )}
                  onChange={(v) => updateConfig(index, { apiKey: v })}
                />
                {config.type === "TEXT" && (
                  <TextField
                    label={tr("附加模型指令", "Additional model instructions")}
                    value={config.systemPrompt}
                    maxLength={MAX_PROMPT_CHARS}
                    multilineRows={3}
                    hint={tr(
                      "DeskCubby 的严格 Agent system prompt 始终优先；这里仅补充风格和任务偏好。",
                      "DeskCubby's strict Agent prompt always takes precedence; use this for style/task preferences only.",
                    )}
                    onChange={(v) => updateConfig(index, { systemPrompt: v })}
                  />
                )}
                <SliderRow
                  label={tr("温度", "Temperature")}
                  value={config.temperature}
                  min={0} max={2} step={0.1}
                  format={(v) => v.toFixed(1)}
                  onChange={(v) => updateConfig(index, { temperature: Math.round(v * 10) / 10 })}
                />
                {config.type === "TEXT" && (
                  <Toggle
                    checked={config.supportsToolCalling}
                    onChange={(v) => updateConfig(index, { supportsToolCalling: v })}
                    label={<span>{tr("原生工具调用", "Native tool calling")}<div className="dc-muted" style={{ fontSize: "0.82em" }}>{tr("仅当 Provider 支持 OpenAI-compatible tools/tool_calls 时开启；关闭后该配置不能运行 Agent。", "Enable only if the provider supports OpenAI-compatible tools/tool_calls. Disabled configurations cannot run Agent.")}</div></span>}
                  />
                )}
                <Toggle
                  checked={config.allowInsecureHttp}
                  onChange={(v) => updateConfig(index, { allowInsecureHttp: v })}
                  label={<span>{tr("允许 HTTP", "Allow HTTP")}<div className="dc-muted" style={{ fontSize: "0.82em" }}>{tr("仅用于可信局域网接口。", "Only for trusted local endpoints.")}</div></span>}
                />
                <Toggle
                  checked={config.enabled}
                  onChange={(v) => updateConfig(index, { enabled: v })}
                  label={<span>{tr("启用此配置", "Enabled")}<div className="dc-muted" style={{ fontSize: "0.82em" }}>{tr("停用的配置不会出现在聊天与热量估算选择器中。", "Disabled configurations disappear from chat and calorie pickers.")}</div></span>}
                />
                <div className="dc-row">
                  <button className="dc-btn" onClick={() => duplicateConfig(index)}>{tr("复制配置", "Duplicate")}</button>
                </div>
              </div>
            );
          })}
        </div>
        <div className="dc-row">
          <button className="dc-btn dc-btn-tonal" onClick={addConfig}>{tr("添加配置", "Add configuration")}</button>
          {(configs.some((c) => !c.name.trim() || !c.model.trim()) || configs.some((c) => !endpointValid(c.endpointUrl, c.allowInsecureHttp))) && (
            <span className="dc-muted" style={{ fontSize: "0.82em" }}>
              {tr("名称、模型名称非空且地址合法后才能保存。", "Name/model are required and the endpoint must be valid before saving.")}
            </span>
          )}
        </div>
      </SectionCard>

      <SectionCard title={tr("聊天与显示", "Chat and display")}>
        <SelectField
          label={tr("AI 聊天默认模型", "Default chat model")}
          value={draft.aiChatConfigId ?? ""}
          onChange={(v) => patch({ aiChatConfigId: v || null })}
          options={[
            { value: "", label: tr("跟随 AI 聊天页选择", "Chosen on the AI chat page") },
            ...configs.map((c) => ({ value: c.id, label: `${c.type === "TEXT" ? tr("文", "Text") : tr("图", "Image")} · ${c.name || tr("未命名配置", "Unnamed")}` })),
          ]}
        />
        <SliderRow
          label={tr("字体大小", "Font size")}
          value={draft.aiPageFontSizeSp}
          min={12} max={28} step={1}
          format={(v) => `${v} sp`}
          onChange={(v) => patch({ aiPageFontSizeSp: Math.round(v) })}
          hint={tr("作用于 AI 聊天页的消息气泡与输入框。", "Applies to message bubbles and the input box on the AI chat page.")}
        />
        <SliderRow
          label={tr("回复框宽度", "Reply box width")}
          value={draft.aiReplyBoxWidthDp}
          min={280} max={1200} step={10}
          format={(v) => `${v} dp`}
          onChange={(v) => patch({ aiReplyBoxWidthDp: v })}
          hint={tr("限制消息气泡的最大宽度；手机窄屏会自动收窄。", "Limits the maximum bubble width; narrow screens still shrink automatically.")}
        />
      </SectionCard>

      <SectionCard title={tr("Agent 数据源", "Agent data sources")}>
        <div className="dc-col" style={{ gap: 6 }}>
          {AGENT_SOURCES.map((s) => (
            <Toggle
              key={s.value}
              checked={sourceSet.has(s.value)}
              onChange={(v) => toggleSource(s.value, v)}
              label={<span>{tr(s.zh, s.en)}<div className="dc-muted" style={{ fontSize: "0.82em" }}>{tr(s.descZh, s.descEn)}</div></span>}
            />
          ))}
        </div>
      </SectionCard>

      <SectionCard title={tr("Agent 权限模式", "Agent permission mode")}>
        <Segmented<"REQUIRE_APPROVAL" | "FULL_AUTO">
          value={draft.agentPermissionMode}
          onChange={(v) => patch({ agentPermissionMode: v })}
          options={[
            { value: "REQUIRE_APPROVAL", label: tr("需要批准", "Require approval") },
            { value: "FULL_AUTO", label: tr("全自动", "Full auto") },
          ]}
        />
        <div className="dc-muted" style={{ fontSize: "0.86em" }}>
          {draft.agentPermissionMode === "FULL_AUTO"
            ? tr("修改直接执行，但仍逐项写入 Review，并可在安全时 Undo。", "Mutations run directly but remain individually recorded in Review with Undo where safe.")
            : tr("读取无需确认；每一个创建、编辑、删除或设置修改都先显示预览。", "Reads run directly; every create, edit, delete, or setting change shows a preview first.")}
        </div>
      </SectionCard>

      <SectionCard title={tr("Agent 提示词", "Agent prompt")}>
        <TextField
          label={tr("Agent 提示词", "Agent prompt")}
          value={draft.agentPrompt}
          maxLength={MAX_PROMPT_CHARS}
          multilineRows={5}
          hint={tr(
            "作为风格与任务偏好附加在严格的内置规则之后，不能扩大权限；最多 20000 个字符。",
            "Appended after the strict built-in rules as style/task preferences; it cannot expand permissions. Up to 20000 characters.",
          )}
          onChange={(v) => patch({ agentPrompt: v })}
        />
        <div className="dc-row">
          <button className="dc-btn" onClick={() => patch({ agentPrompt: DEFAULT_AGENT_PROMPT })}>
            {tr("恢复 Agent 提示词", "Restore Agent prompt")}
          </button>
        </div>
      </SectionCard>

      <SectionCard
        title={tr("AI 热量估算", "AI calorie estimation")}
        description={
          hasCalorieModels
            ? tr("结果会写入图片标题并显示在吃历。", "Results are written to captions and shown in the meal calendar.")
            : tr("需要先在 AI 配置中添加文字模型和图片模型。", "Add a text model and an image model in AI configurations first.")
        }
      >
        <Toggle
          checked={draft.calorieEstimationEnabled}
          disabled={!hasCalorieModels}
          onChange={(v) => patch({ calorieEstimationEnabled: v })}
          label={<span>{tr("上传饮食图片后自动估算", "Estimate after uploading meal images")}</span>}
        />
        <SelectField
          label={tr("热量计算文字模型", "Calorie text model")}
          value={draft.calorieTextConfigId ?? ""}
          onChange={(v) => patch({ calorieTextConfigId: v || null })}
          options={modelOptions(textConfigs, tr("请选择文字模型", "Choose a text model"))}
        />
        <SelectField
          label={tr("食物图片识别模型", "Food image model")}
          value={draft.calorieImageConfigId ?? ""}
          onChange={(v) => patch({ calorieImageConfigId: v || null })}
          options={modelOptions(imageConfigs, tr("请选择图片模型", "Choose an image model"))}
        />
        {!calorieReady && (
          <div style={{ color: "var(--dc-error)", fontSize: "0.85em" }}>
            {tr("开启热量估算前必须选择两个模型。", "Both models must be chosen before saving with estimation on.")}
          </div>
        )}
        <TextField
          label={tr("图片识别提示词", "Vision prompt")}
          value={draft.calorieVisionPrompt}
          maxLength={MAX_PROMPT_CHARS}
          multilineRows={4}
          onChange={(v) => patch({ calorieVisionPrompt: v })}
        />
        <div className="dc-row">
          <button className="dc-btn" onClick={() => { patch({ calorieVisionPrompt: DEFAULT_CALORIE_VISION_PROMPT }); snackbar(tr("已恢复默认提示词，保存后生效", "Default prompt restored; press Save")); }}>
            {tr("恢复默认识别提示词", "Restore default vision prompt")}
          </button>
        </div>
        <TextField
          label={tr("热量计算提示词", "Calorie prompt")}
          value={draft.calorieTextPrompt}
          maxLength={MAX_PROMPT_CHARS}
          multilineRows={4}
          onChange={(v) => patch({ calorieTextPrompt: v })}
        />
        <div className="dc-row">
          <button className="dc-btn" onClick={() => { patch({ calorieTextPrompt: DEFAULT_CALORIE_TEXT_PROMPT }); snackbar(tr("已恢复默认提示词，保存后生效", "Default prompt restored; press Save")); }}>
            {tr("恢复默认计算提示词", "Restore default calorie prompt")}
          </button>
        </div>
      </SectionCard>
    </div>
  );
}
