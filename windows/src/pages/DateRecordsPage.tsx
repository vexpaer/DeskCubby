import { CalendarDays, Pencil, Plus, Save, Search, Trash2, X } from "lucide-react";
import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";

import {
  dateRecordApi,
  type DateRecord,
  type DateRecordDraft,
} from "../lib/ipc";

type Language = "zh" | "en";

function localTodayIso(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = `${now.getMonth() + 1}`.padStart(2, "0");
  const day = `${now.getDate()}`.padStart(2, "0");
  return `${year}-${month}-${day}`;
}

const EMPTY_DRAFT: DateRecordDraft = {
  name: "",
  icon: "📅",
  dateIso: localTodayIso(),
};

function useDocumentLanguage(): Language {
  const read = () =>
    document.documentElement.lang.toLowerCase().startsWith("en") ? "en" : "zh";
  const [language, setLanguage] = useState<Language>(read);

  useEffect(() => {
    const observer = new MutationObserver(() => setLanguage(read()));
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["lang"] });
    return () => observer.disconnect();
  }, []);

  return language;
}

function parseLocalDate(dateIso: string): Date {
  const [year, month, day] = dateIso.split("-").map(Number);
  return new Date(year, month - 1, day);
}

function dayDifference(dateIso: string): number {
  const target = parseLocalDate(dateIso);
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.round((target.getTime() - today.getTime()) / 86_400_000);
}

export default function DateRecordsPage() {
  const language = useDocumentLanguage();
  const tr = useCallback(
    (chinese: string, english: string) => (language === "zh" ? chinese : english),
    [language],
  );
  const [records, setRecords] = useState<DateRecord[]>([]);
  const [draft, setDraft] = useState<DateRecordDraft>(EMPTY_DRAFT);
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setRecords(await dateRecordApi.list());
    } catch (reason) {
      setError(
        reason instanceof Error
          ? reason.message
          : tr("读取日期记录失败。", "Could not load date records."),
      );
    } finally {
      setLoading(false);
    }
  }, [tr]);

  useEffect(() => {
    void load();
  }, [load]);

  const visibleRecords = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase();
    return records
      .filter(
        (record) =>
          normalized.length === 0 ||
          record.name.toLocaleLowerCase().includes(normalized) ||
          record.dateIso.includes(normalized),
      )
      .sort((left, right) => {
        const leftDays = dayDifference(left.dateIso);
        const rightDays = dayDifference(right.dateIso);
        const leftPast = leftDays < 0;
        const rightPast = rightDays < 0;
        if (leftPast !== rightPast) return leftPast ? 1 : -1;
        return leftPast ? rightDays - leftDays : leftDays - rightDays;
      });
  }, [query, records]);

  const resetEditor = () => setDraft({ ...EMPTY_DRAFT, dateIso: localTodayIso() });

  const saveRecord = async (event: FormEvent) => {
    event.preventDefault();
    const name = draft.name.trim();
    const icon = draft.icon.trim() || "📅";
    if (!name || !draft.dateIso) {
      setError(tr("名称和日期不能为空。", "Name and date are required."));
      return;
    }
    setSaving(true);
    setError("");
    setNotice("");
    try {
      const saved = draft.id
        ? await dateRecordApi.update(draft.id, { name, icon, dateIso: draft.dateIso })
        : await dateRecordApi.create({ name, icon, dateIso: draft.dateIso });
      setRecords((current) => [...current.filter((item) => item.id !== saved.id), saved]);
      resetEditor();
      setNotice(tr("日期记录已保存。", "Date record saved."));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tr("保存失败。", "Save failed."));
    } finally {
      setSaving(false);
    }
  };

  const editRecord = (record: DateRecord) => {
    setDraft({
      id: record.id,
      name: record.name,
      icon: record.icon,
      dateIso: record.dateIso,
    });
    document.getElementById("date-record-name")?.focus();
  };

  const deleteRecord = async (record: DateRecord) => {
    if (
      !window.confirm(
        tr(`删除日期记录“${record.name}”？`, `Delete the date record “${record.name}”?`),
      )
    ) {
      return;
    }
    setError("");
    try {
      await dateRecordApi.delete(record.id);
      setRecords((current) => current.filter((item) => item.id !== record.id));
      if (draft.id === record.id) resetEditor();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tr("删除失败。", "Delete failed."));
    }
  };

  const relativeLabel = (dateIso: string): string => {
    const difference = dayDifference(dateIso);
    if (difference === 0) return tr("就是今天", "Today");
    if (difference === 1) return tr("还有 1 天", "1 day to go");
    if (difference > 1) return tr(`还有 ${difference} 天`, `${difference} days to go`);
    if (difference === -1) return tr("已经过去 1 天", "1 day ago");
    return tr(`已经过去 ${Math.abs(difference)} 天`, `${Math.abs(difference)} days ago`);
  };

  const formattedDate = (dateIso: string): string =>
    new Intl.DateTimeFormat(language === "zh" ? "zh-CN" : "en-US", {
      year: "numeric",
      month: "long",
      day: "numeric",
      weekday: "short",
    }).format(parseLocalDate(dateIso));

  return (
    <main className="page-shell date-records-page" aria-labelledby="date-records-title">
      <header className="page-header">
        <div>
          <p className="eyebrow">{tr("重要的日子，清楚地记住", "Keep meaningful dates in view")}</p>
          <h1 id="date-records-title">{tr("日期记录", "Date records")}</h1>
        </div>
        <div className="page-actions">
          <button
            className="button-primary"
            type="button"
            onClick={() => document.getElementById("date-record-name")?.focus()}
          >
            <Plus size={18} />
            {tr("新增日期", "New date")}
          </button>
        </div>
      </header>

      {(error || notice) && (
        <div className={error ? "status-banner error" : "status-banner success"} role="status">
          <span>{error || notice}</span>
          <button
            className="icon-button"
            type="button"
            aria-label={tr("关闭提示", "Dismiss")}
            onClick={() => {
              setError("");
              setNotice("");
            }}
          >
            <X size={16} />
          </button>
        </div>
      )}

      <form className="panel date-record-editor" onSubmit={saveRecord}>
        <div className="panel-heading">
          <h2>{draft.id ? tr("编辑日期", "Edit date") : tr("添加日期", "Add a date")}</h2>
          {draft.id && (
            <button className="button-ghost" type="button" onClick={resetEditor}>
              <X size={16} />
              {tr("取消编辑", "Cancel")}
            </button>
          )}
        </div>
        <div className="form-grid date-record-fields">
          <label className="icon-input">
            <span>{tr("图标", "Icon")}</span>
            <input
              aria-label={tr("日期图标", "Date icon")}
              maxLength={8}
              value={draft.icon}
              onChange={(event) =>
                setDraft((current) => ({ ...current, icon: event.target.value }))
              }
            />
          </label>
          <label>
            <span>{tr("名称", "Name")}</span>
            <input
              id="date-record-name"
              maxLength={160}
              required
              value={draft.name}
              placeholder={tr("例如：搬入新家", "For example: Moved home")}
              onChange={(event) =>
                setDraft((current) => ({ ...current, name: event.target.value }))
              }
            />
          </label>
          <label>
            <span>{tr("日期", "Date")}</span>
            <input
              type="date"
              required
              value={draft.dateIso}
              onChange={(event) =>
                setDraft((current) => ({ ...current, dateIso: event.target.value }))
              }
            />
          </label>
          <button className="button-primary align-end" type="submit" disabled={saving}>
            <Save size={17} />
            {saving ? tr("保存中…", "Saving…") : tr("保存", "Save")}
          </button>
        </div>
      </form>

      <section aria-label={tr("日期记录列表", "Date record list")}>
        <label className="search-field">
          <Search size={17} aria-hidden="true" />
          <span className="sr-only">{tr("搜索日期记录", "Search date records")}</span>
          <input
            type="search"
            value={query}
            placeholder={tr("搜索名称或日期", "Search by name or date")}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>

        {loading ? (
          <div className="panel empty-state" role="status">
            {tr("正在读取日期记录…", "Loading date records…")}
          </div>
        ) : visibleRecords.length === 0 ? (
          <div className="panel empty-state">
            <CalendarDays size={28} aria-hidden="true" />
            <p>{tr("还没有日期记录。", "No date records yet.")}</p>
          </div>
        ) : (
          <div className="date-record-grid">
            {visibleRecords.map((record) => (
              <article className="panel date-record-card" key={record.id}>
                <div className="date-record-icon" aria-hidden="true">
                  {record.icon || "📅"}
                </div>
                <div className="date-record-copy">
                  <h2>{record.name}</h2>
                  <time dateTime={record.dateIso}>{formattedDate(record.dateIso)}</time>
                  <strong className={dayDifference(record.dateIso) < 0 ? "is-past" : ""}>
                    {relativeLabel(record.dateIso)}
                  </strong>
                </div>
                <div className="inline-actions">
                  <button
                    className="icon-button"
                    type="button"
                    title={tr("编辑", "Edit")}
                    onClick={() => editRecord(record)}
                  >
                    <Pencil size={17} />
                  </button>
                  <button
                    className="icon-button danger"
                    type="button"
                    title={tr("删除", "Delete")}
                    onClick={() => void deleteRecord(record)}
                  >
                    <Trash2 size={17} />
                  </button>
                </div>
              </article>
            ))}
          </div>
        )}
      </section>
    </main>
  );
}
