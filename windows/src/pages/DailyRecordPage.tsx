import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowDown,
  ArrowUp,
  Check,
  LoaderCircle,
  PencilLine,
  Plus,
  Send,
  Sparkles,
  Trash2,
  X,
} from "lucide-react";
import {
  dailyRecordApi,
  getActiveDiary,
  readableError,
  tr,
  type DailyRecordContext,
  type DailyRecordTarget,
  type DailyTemplate,
} from "../lib/ipc";
import { useAppStore } from "../store/appStore";

export default function DailyRecordPage() {
  const language = useAppStore((state) => state.appearance.language);
  const t = useCallback(
    (zh: string, en: string) => tr(language, zh, en),
    [language],
  );
  const [templates, setTemplates] = useState<DailyTemplate[]>([]);
  const [context, setContext] = useState<DailyRecordContext | null>(null);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [target, setTarget] = useState<DailyRecordTarget>("today");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [newOpen, setNewOpen] = useState(false);
  const [newText, setNewText] = useState("");
  const [editing, setEditing] = useState<DailyTemplate | null>(null);
  const [editText, setEditText] = useState("");
  const textareas = useRef(new Map<string, HTMLTextAreaElement>());

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [nextTemplates, nextContext] = await Promise.all([
        dailyRecordApi.listTemplates(),
        dailyRecordApi.getContext(),
      ]);
      const activeDiary = getActiveDiary();
      const resolvedContext = activeDiary
        ? { ...nextContext, currentDiaryRelativePath: activeDiary }
        : nextContext;
      setTemplates(nextTemplates);
      setContext(resolvedContext);
      setTarget(resolvedContext.currentDiaryRelativePath ? "current" : "today");
      setDrafts(
        Object.fromEntries(nextTemplates.map((item) => [item.id, item.text])),
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

  const sortedTemplates = useMemo(
    () => [...templates].sort((a, b) => a.sortOrder - b.sortOrder),
    [templates],
  );

  function selectPlaceholder(id: string) {
    const input = textareas.current.get(id);
    const value = drafts[id] ?? "";
    const index = value.indexOf("xx");
    if (!input || index < 0) return;
    window.requestAnimationFrame(() => {
      input.focus();
      input.setSelectionRange(index, index + 2);
    });
  }

  async function append(template: DailyTemplate) {
    const text = (drafts[template.id] ?? "").trim();
    if (!text || !context || busy) return;
    setBusy(template.id);
    setError(null);
    try {
      await dailyRecordApi.append(
        text,
        target,
        context.currentDiaryRelativePath,
      );
      setDrafts((current) => ({ ...current, [template.id]: template.text }));
      setNotice(
        target === "current"
          ? t("已写入当前日记", "Added to current diary")
          : t("已写入今日日记", "Added to today's diary"),
      );
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setBusy(null);
    }
  }

  async function createTemplate() {
    const text = newText.trim();
    if (!text || busy) return;
    setBusy("create");
    setError(null);
    try {
      const created = await dailyRecordApi.createTemplate(text);
      setTemplates((current) => [...current, created]);
      setDrafts((current) => ({ ...current, [created.id]: created.text }));
      setNewText("");
      setNewOpen(false);
      setNotice(t("模板已创建", "Template created"));
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setBusy(null);
    }
  }

  async function updateTemplate() {
    if (!editing || !editText.trim() || busy) return;
    setBusy(editing.id);
    setError(null);
    try {
      const updated = await dailyRecordApi.updateTemplate(
        editing.id,
        editText.trim(),
      );
      setTemplates((current) =>
        current.map((item) => (item.id === updated.id ? updated : item)),
      );
      setDrafts((current) => ({ ...current, [updated.id]: updated.text }));
      setEditing(null);
      setEditText("");
      setNotice(t("模板已保存", "Template saved"));
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setBusy(null);
    }
  }

  async function deleteTemplate(template: DailyTemplate) {
    if (
      !window.confirm(
        language === "en"
          ? `Delete template “${template.text}”?`
          : `删除模板“${template.text}”？`,
      )
    )
      return;
    setBusy(template.id);
    setError(null);
    try {
      await dailyRecordApi.deleteTemplate(template.id);
      setTemplates((current) =>
        current.filter((item) => item.id !== template.id),
      );
      setNotice(t("模板已删除", "Template deleted"));
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setBusy(null);
    }
  }

  async function moveTemplate(id: string, direction: -1 | 1) {
    const currentIndex = sortedTemplates.findIndex((item) => item.id === id);
    const targetIndex = currentIndex + direction;
    if (currentIndex < 0 || targetIndex < 0 || targetIndex >= templates.length) {
      return;
    }
    const reordered = [...sortedTemplates];
    [reordered[currentIndex], reordered[targetIndex]] = [
      reordered[targetIndex],
      reordered[currentIndex],
    ];
    setTemplates(
      reordered.map((item, index) => ({ ...item, sortOrder: index })),
    );
    try {
      setTemplates(await dailyRecordApi.reorderTemplates(reordered.map((i) => i.id)));
    } catch (reason) {
      setError(readableError(reason, language));
      await load();
    }
  }

  return (
    <main className="page daily-record-page">
      <header className="page-header">
        <div>
          <p className="eyebrow">
            {t(
              "一句话模板 · 写盘后才清空",
              "Sentence templates · Clear only after saving",
            )}
          </p>
          <h1>{t("日常记录", "Daily record")}</h1>
          <p className="muted">
            {t(
              "点击模板中的 xx 会自动选中，输入内容后整句写入日记。",
              "Click xx in a template to select it, then add the completed sentence to your diary.",
            )}
          </p>
        </div>
        <button
          className="button primary"
          type="button"
          onClick={() => setNewOpen(true)}
        >
          <Plus aria-hidden="true" /> {t("新建模板", "New template")}
        </button>
      </header>

      {error && (
        <div className="inline-error" role="alert">
          {error}
          <button
            type="button"
            onClick={() => setError(null)}
            aria-label={t("关闭", "Close")}
          >
            <X aria-hidden="true" />
          </button>
        </div>
      )}
      {notice && (
        <div className="toast" role="status">
          <Check aria-hidden="true" /> {notice}
        </div>
      )}

      <section
        className="target-panel panel"
        aria-label={t("写入目标", "Write target")}
      >
        <div>
          <strong>{t("写入位置", "Write to")}</strong>
          <small className="muted">
            {context?.currentDiaryRelativePath
              ? t(
                  "当前已打开日记，也可以固定写入今天。",
                  "A diary is open; you can also always write to today.",
                )
              : t(
                  "目前没有已打开的日记，将写入今天。",
                  "No diary is open, so entries will go to today.",
                )}
          </small>
        </div>
        <div className="segmented" role="group">
          <button
            type="button"
            className={target === "current" ? "selected" : undefined}
            disabled={!context?.currentDiaryRelativePath}
            onClick={() => setTarget("current")}
          >
            {t("当前日记", "Current diary")}
          </button>
          <button
            type="button"
            className={target === "today" ? "selected" : undefined}
            onClick={() => setTarget("today")}
          >
            {t("今日日记", "Today's diary")}
          </button>
        </div>
      </section>

      {loading ? (
        <div className="page-centered" aria-busy="true">
          <LoaderCircle className="spin" aria-hidden="true" />
          <p>{t("正在加载模板…", "Loading templates…")}</p>
        </div>
      ) : sortedTemplates.length ? (
        <section
          className="daily-template-list"
          aria-label={t("日常记录模板", "Daily record templates")}
        >
          {sortedTemplates.map((template, index) => (
            <article className="card daily-template-card" key={template.id}>
              <div
                className="template-sort-actions"
                aria-label={t("排序", "Sort")}
              >
                <button
                  className="icon-button"
                  type="button"
                  title={t("上移", "Move up")}
                  aria-label={t("上移模板", "Move template up")}
                  disabled={index === 0 || busy !== null}
                  onClick={() => void moveTemplate(template.id, -1)}
                >
                  <ArrowUp aria-hidden="true" />
                </button>
                <button
                  className="icon-button"
                  type="button"
                  title={t("下移", "Move down")}
                  aria-label={t("下移模板", "Move template down")}
                  disabled={index === sortedTemplates.length - 1 || busy !== null}
                  onClick={() => void moveTemplate(template.id, 1)}
                >
                  <ArrowDown aria-hidden="true" />
                </button>
              </div>
              <textarea
                ref={(node) => {
                  if (node) textareas.current.set(template.id, node);
                  else textareas.current.delete(template.id);
                }}
                rows={3}
                value={drafts[template.id] ?? template.text}
                onClick={() => selectPlaceholder(template.id)}
                onChange={(event) =>
                  setDrafts((current) => ({
                    ...current,
                    [template.id]: event.target.value.slice(0, 100),
                  }))
                }
                aria-label={
                  language === "en"
                    ? `Record: ${template.text}`
                    : `记录：${template.text}`
                }
              />
              <footer>
                <div className="row-actions">
                  <button
                    className="icon-button"
                    type="button"
                    title={t("编辑模板", "Edit template")}
                    aria-label={t("编辑模板", "Edit template")}
                    onClick={() => {
                      setEditing(template);
                      setEditText(template.text);
                    }}
                  >
                    <PencilLine aria-hidden="true" />
                  </button>
                  <button
                    className="icon-button danger"
                    type="button"
                    title={t("删除模板", "Delete template")}
                    aria-label={t("删除模板", "Delete template")}
                    onClick={() => void deleteTemplate(template)}
                  >
                    <Trash2 aria-hidden="true" />
                  </button>
                </div>
                <button
                  className="button primary"
                  type="button"
                  disabled={
                    busy !== null ||
                    !(drafts[template.id] ?? template.text).trim()
                  }
                  onClick={() => void append(template)}
                >
                  {busy === template.id ? (
                    <LoaderCircle className="spin" aria-hidden="true" />
                  ) : (
                    <Send aria-hidden="true" />
                  )}
                  {t("写入", "Add")}
                </button>
              </footer>
            </article>
          ))}
        </section>
      ) : (
        <div className="empty-state">
          <Sparkles aria-hidden="true" />
          <h2>{t("还没有日常模板", "No daily templates yet")}</h2>
          <p>
            {t(
              "例如“今天喝了 xx 杯水”或“运动 xx 分钟”。",
              "For example, “Drank xx glasses of water” or “Exercised for xx minutes.”",
            )}
          </p>
          <button
            className="button primary"
            type="button"
            onClick={() => setNewOpen(true)}
          >
            {t("创建第一个模板", "Create first template")}
          </button>
        </div>
      )}

      {(newOpen || editing) && (
        <div className="dialog-backdrop" role="presentation">
          <section
            className="dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="template-dialog-title"
          >
            <header>
              <h2 id="template-dialog-title">
                {editing
                  ? t("编辑模板", "Edit template")
                  : t("新建模板", "New template")}
              </h2>
              <button
                className="icon-button"
                type="button"
                onClick={() => {
                  setNewOpen(false);
                  setEditing(null);
                }}
                aria-label={t("关闭", "Close")}
              >
                <X aria-hidden="true" />
              </button>
            </header>
            <label>
              {t("完整句子", "Complete sentence")}
              <textarea
                rows={5}
                maxLength={100}
                value={editing ? editText : newText}
                onChange={(event) =>
                  editing
                    ? setEditText(event.target.value)
                    : setNewText(event.target.value)
                }
                placeholder={t(
                  "例如：今天喝了 xx 杯水",
                  "For example: Drank xx glasses of water",
                )}
                autoFocus
              />
            </label>
            <p className="muted">
              {t(
                "xx 是可选占位符；使用模板时点击句子即可快速替换。",
                "xx is an optional placeholder; click the sentence to replace it quickly.",
              )}
            </p>
            <footer>
              <button
                className="button secondary"
                type="button"
                onClick={() => {
                  setNewOpen(false);
                  setEditing(null);
                }}
              >
                {t("取消", "Cancel")}
              </button>
              <button
                className="button primary"
                type="button"
                disabled={
                  busy !== null || !(editing ? editText : newText).trim()
                }
                onClick={() =>
                  editing ? void updateTemplate() : void createTemplate()
                }
              >
                {t("保存", "Save")}
              </button>
            </footer>
          </section>
        </div>
      )}
    </main>
  );
}
