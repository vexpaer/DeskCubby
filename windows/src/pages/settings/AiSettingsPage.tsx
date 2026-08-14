import {
  Bot,
  Copy,
  Eye,
  KeyRound,
  LoaderCircle,
  Plus,
  RotateCcw,
  Save,
  Sparkles,
  Trash2,
  X,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";

import { ErrorState, LoadingState, PageFrame, UnsavedChangesGuard } from "../../components";
import {
  aiApi,
  buildOpenAiRequestPreview,
  DEFAULT_CALORIE_TEXT_PROMPT,
  DEFAULT_CALORIE_VISION_PROMPT,
  type AiModelConfig,
  type AiModelType,
  type AiSettings,
} from "../../lib/aiApi";
import { readableError, tr } from "../../lib/ipc";
import { useAppStore } from "../../store/appStore";
import "./AiSettingsPage.css";

function newId(): string {
  return `model-${crypto.randomUUID()}`;
}

function createConfig(type: AiModelType): AiModelConfig {
  return {
    id: newId(),
    name: type === "TEXT" ? "OpenAI Text" : "OpenAI Vision",
    type,
    endpointUrl: "https://api.openai.com/v1/chat/completions",
    model: "",
    enabled: true,
    allowInsecureHttp: false,
    temperature: 0.7,
    systemPrompt: "",
    apiKey: "",
  };
}

function cloneSettings(settings: AiSettings): AiSettings {
  return structuredClone(settings);
}

function resetDraft(current: AiSettings): AiSettings {
  return {
    ...current,
    configs: [],
    aiChatConfigId: null,
    calorieEstimationEnabled: false,
    calorieTextConfigId: null,
    calorieImageConfigId: null,
    calorieVisionPrompt: DEFAULT_CALORIE_VISION_PROMPT,
    calorieTextPrompt: DEFAULT_CALORIE_TEXT_PROMPT,
  };
}

export default function AiSettingsPage() {
  const language = useAppStore((state) => state.appearance.language);
  const t = useCallback((zh: string, en: string) => tr(language, zh, en), [language]);
  const [original, setOriginal] = useState<AiSettings | null>(null);
  const [draft, setDraft] = useState<AiSettings | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [preview, setPreview] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const dirty = useMemo(
    () => original !== null && draft !== null && JSON.stringify(original) !== JSON.stringify(draft),
    [draft, original],
  );
  const selected = useMemo(
    () => draft?.configs.find((item) => item.id === selectedId) ?? null,
    [draft, selectedId],
  );
  const enabledText = draft?.configs.filter((item) => item.enabled && item.type === "TEXT") ?? [];
  const enabledImage = draft?.configs.filter((item) => item.enabled && item.type === "IMAGE") ?? [];

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const value = await aiApi.settings();
      setOriginal(cloneSettings(value));
      setDraft(cloneSettings(value));
      setSelectedId((current) =>
        value.configs.some((item) => item.id === current) ? current : value.configs[0]?.id ?? null,
      );
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setLoading(false);
    }
  }, [language]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(null), 3500);
    return () => window.clearTimeout(timer);
  }, [notice]);

  const updateConfig = (patch: Partial<AiModelConfig>) => {
    if (!selectedId) return;
    setDraft((current) =>
      current
        ? {
            ...current,
            configs: current.configs.map((item) =>
              item.id === selectedId ? { ...item, ...patch } : item,
            ),
          }
        : current,
    );
  };

  const addConfig = (type: AiModelType) => {
    if (!draft || draft.configs.length >= 20) return;
    const config = createConfig(type);
    setDraft({ ...draft, configs: [...draft.configs, config] });
    setSelectedId(config.id);
    setPreview(false);
  };

  const duplicateConfig = () => {
    if (!draft || !selected || draft.configs.length >= 20) return;
    const clone = { ...selected, id: newId(), name: `${selected.name} ${t("副本", "copy")}` };
    setDraft({ ...draft, configs: [...draft.configs, clone] });
    setSelectedId(clone.id);
  };

  const removeConfig = () => {
    if (!draft || !selected) return;
    const configs = draft.configs.filter((item) => item.id !== selected.id);
    setDraft({
      ...draft,
      configs,
      aiChatConfigId: draft.aiChatConfigId === selected.id ? null : draft.aiChatConfigId,
      calorieTextConfigId:
        draft.calorieTextConfigId === selected.id ? null : draft.calorieTextConfigId,
      calorieImageConfigId:
        draft.calorieImageConfigId === selected.id ? null : draft.calorieImageConfigId,
      calorieEstimationEnabled:
        draft.calorieTextConfigId === selected.id || draft.calorieImageConfigId === selected.id
          ? false
          : draft.calorieEstimationEnabled,
    });
    setSelectedId(configs[0]?.id ?? null);
    setPreview(false);
  };

  const save = async () => {
    if (!draft || !dirty) return;
    setSaving(true);
    setError(null);
    try {
      const value = await aiApi.saveSettings(draft);
      setOriginal(cloneSettings(value));
      setDraft(cloneSettings(value));
      setNotice(t("AI 设置已保存", "AI settings saved"));
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <PageFrame title={t("AI 设置", "AI settings")}><LoadingState /></PageFrame>;
  }

  if (!draft || !original) {
    return <PageFrame title={t("AI 设置", "AI settings")}><ErrorState description={error ?? undefined} retry={() => void load()} /></PageFrame>;
  }

  return (
    <PageFrame
      className="ai-settings-page"
      eyebrow={t("设置 → 子页面设置 → AI", "Settings → Subpage settings → AI")}
      title={t("AI 设置", "AI settings")}
      description={t(
  "配置与 Android v33 使用相同字段。API Key 按产品约定明文保存、完整显示并进入 v33 备份，但不会写入日志、错误或请求预览。",
  "Configurations use the Android v33 fields. By product design, API keys are stored in plaintext, shown in full and included in v33 backup, but never written to logs, errors or request previews.",
      )}
      actions={
        <>
          <button className="button button-ghost" type="button" disabled={saving} onClick={() => { setDraft(resetDraft(draft)); setSelectedId(null); setPreview(false); }}>
            <RotateCcw size={17} /> {t("恢复默认", "Restore defaults")}
          </button>
          <button className="button button-primary" type="button" disabled={!dirty || saving} onClick={() => void save()}>
            {saving ? <LoaderCircle className="spin" size={17} /> : <Save size={17} />}
            {t("保存", "Save")}
          </button>
        </>
      }
    >
      <UnsavedChangesGuard when={dirty} scope="ai-settings" onDiscard={() => setDraft(cloneSettings(original))} />
      {error ? <div className="inline-error" role="alert">{error}<button type="button" aria-label={t("关闭", "Close")} onClick={() => setError(null)}><X size={16} /></button></div> : null}
      {notice ? <div className="toast" role="status">{notice}</div> : null}

      <section className="panel ai-default-models">
        <div className="settings-section-heading"><Bot size={20} /><div><h2>{t("默认模型", "Default models")}</h2><p>{t("只有已启用且类型匹配的配置可供选择。", "Only enabled configurations of the matching type can be selected.")}</p></div></div>
        <div className="form-grid">
          <label><span>{t("AI 对话文字模型", "AI chat text model")}</span><select value={draft.aiChatConfigId ?? ""} onChange={(event) => setDraft({ ...draft, aiChatConfigId: event.target.value || null })}><option value="">{t("未选择", "Not selected")}</option>{enabledText.map((config) => <option key={config.id} value={config.id}>{config.name} · {config.model || t("未填写模型", "No model")}</option>)}</select></label>
          <label className="check-control"><input type="checkbox" checked={draft.calorieEstimationEnabled} onChange={(event) => setDraft({ ...draft, calorieEstimationEnabled: event.target.checked })} /><span>{t("启用吃历 AI 热量估算", "Enable AI meal calorie estimates")}</span></label>
          <label><span>{t("热量统一计算文字模型", "Calorie text model")}</span><select value={draft.calorieTextConfigId ?? ""} onChange={(event) => setDraft({ ...draft, calorieTextConfigId: event.target.value || null })}><option value="">{t("未选择", "Not selected")}</option>{enabledText.map((config) => <option key={config.id} value={config.id}>{config.name}</option>)}</select></label>
          <label><span>{t("热量图片识别模型", "Calorie image model")}</span><select value={draft.calorieImageConfigId ?? ""} onChange={(event) => setDraft({ ...draft, calorieImageConfigId: event.target.value || null })}><option value="">{t("未选择", "Not selected")}</option>{enabledImage.map((config) => <option key={config.id} value={config.id}>{config.name}</option>)}</select></label>
        </div>
      </section>

      <div className="ai-settings-grid">
        <aside className="panel ai-config-list">
          <header><div><Sparkles size={20} /><strong>{t("模型配置", "Model configurations")}</strong></div><span>{draft.configs.length}/20</span></header>
          <div className="ai-config-actions">
            <button className="button button-secondary" type="button" disabled={draft.configs.length >= 20} onClick={() => addConfig("TEXT")}><Plus size={16} />{t("文字", "Text")}</button>
            <button className="button button-secondary" type="button" disabled={draft.configs.length >= 20} onClick={() => addConfig("IMAGE")}><Plus size={16} />{t("图片", "Image")}</button>
          </div>
          <div>
            {draft.configs.map((config) => <button className={selectedId === config.id ? "ai-config-row selected" : "ai-config-row"} type="button" key={config.id} onClick={() => { setSelectedId(config.id); setPreview(false); }}><span><strong>{config.name || t("未命名", "Untitled")}</strong><small>{config.type === "TEXT" ? t("文字模型", "Text model") : t("图片模型", "Image model")} · {config.enabled ? t("已启用", "Enabled") : t("已停用", "Disabled")}</small></span><em>{config.model || "—"}</em></button>)}
          </div>
        </aside>

        <section className="panel ai-config-editor">
          {selected ? (
            <>
              <header><div><KeyRound size={20} /><div><h2>{selected.name || t("模型详情", "Model details")}</h2><p>{t("API Key 在此处完整显示，不做遮罩。", "The API key is shown here in full without masking.")}</p></div></div><div><button className="button button-secondary" type="button" disabled={draft.configs.length >= 20} onClick={duplicateConfig}><Copy size={16} />{t("复制", "Duplicate")}</button><button className="button button-danger" type="button" onClick={removeConfig}><Trash2 size={16} />{t("删除", "Delete")}</button></div></header>
              <div className="form-grid ai-model-fields">
                <label><span>{t("配置名称", "Configuration name")}</span><input value={selected.name} maxLength={80} onChange={(event) => updateConfig({ name: event.target.value })} /></label>
                <label><span>{t("类型", "Type")}</span><select value={selected.type} onChange={(event) => updateConfig({ type: event.target.value as AiModelType })}><option value="TEXT">{t("文字", "Text")}</option><option value="IMAGE">{t("图片", "Image")}</option></select></label>
                <label className="wide"><span>{t("OpenAI 兼容端点", "OpenAI-compatible endpoint")}</span><input type="url" value={selected.endpointUrl} maxLength={4096} spellCheck={false} onChange={(event) => updateConfig({ endpointUrl: event.target.value })} /></label>
                <label><span>{t("模型", "Model")}</span><input value={selected.model} maxLength={512} spellCheck={false} onChange={(event) => updateConfig({ model: event.target.value })} /></label>
                <label><span>{t("温度", "Temperature")} · {selected.temperature.toFixed(2)}</span><input type="range" min="0" max="2" step="0.05" value={selected.temperature} onChange={(event) => updateConfig({ temperature: Number(event.target.value) })} /></label>
                <label className="wide"><span>{t("API Key（明文完整显示）", "API key (plaintext, shown in full)")}</span><input type="text" value={selected.apiKey} maxLength={8192} autoComplete="off" spellCheck={false} onChange={(event) => updateConfig({ apiKey: event.target.value })} /></label>
                <label className="wide"><span>{t("系统提示词", "System prompt")}</span><textarea value={selected.systemPrompt} maxLength={20_000} onChange={(event) => updateConfig({ systemPrompt: event.target.value })} /></label>
                <label className="check-control"><input type="checkbox" checked={selected.enabled} onChange={(event) => updateConfig({ enabled: event.target.checked })} /><span>{t("启用此配置", "Enable this configuration")}</span></label>
                <label className="check-control"><input type="checkbox" checked={selected.allowInsecureHttp} onChange={(event) => updateConfig({ allowInsecureHttp: event.target.checked })} /><span>{t("允许可信本地服务使用 HTTP", "Allow HTTP for a trusted local service")}</span></label>
              </div>
              <div className="ai-preview-toggle"><button className="button button-secondary" type="button" onClick={() => setPreview((value) => !value)}><Eye size={16} />{preview ? t("隐藏请求预览", "Hide request preview") : t("请求预览", "Request preview")}</button><small>{t("预览使用占位内容，绝不包含 API Key 或私人消息。", "The preview uses placeholders and never includes the API key or private messages.")}</small></div>
              {preview ? <pre className="ai-request-preview">{JSON.stringify(buildOpenAiRequestPreview(selected), null, 2)}</pre> : null}
            </>
          ) : <div className="empty-state"><KeyRound size={34} /><h2>{t("添加模型配置", "Add a model configuration")}</h2><p>{t("创建文字或图片模型后在这里编辑详细字段。", "Create a text or image model to edit its fields here.")}</p></div>}
        </section>
      </div>

      <section className="panel ai-calorie-prompts">
        <div className="settings-section-heading"><Sparkles size={20} /><div><h2>{t("吃历热量提示词", "Meal calorie prompts")}</h2><p>{t("每张图片最多并发识别 3 个任务；全部成功后只调用一次文字模型统一计算，并原子写入 dc-media.json v2。", "Up to three images are recognized concurrently. After every recognition succeeds, exactly one text-model calculation runs and commits dc-media.json v2 atomically.")}</p></div></div>
        <label><span>{t("图片识别提示词", "Vision recognition prompt")}</span><textarea value={draft.calorieVisionPrompt} maxLength={20_000} onChange={(event) => setDraft({ ...draft, calorieVisionPrompt: event.target.value })} /></label>
        <label><span>{t("统一热量计算提示词", "Unified calorie calculation prompt")}</span><textarea value={draft.calorieTextPrompt} maxLength={20_000} onChange={(event) => setDraft({ ...draft, calorieTextPrompt: event.target.value })} /></label>
      </section>
    </PageFrame>
  );
}
