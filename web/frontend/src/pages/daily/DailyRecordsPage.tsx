/**
 * Structured daily records (/daily) — mirrors Android 结构化记录:
 * typed field values (word / number / type / time / duration) written into the
 * Markdown diaries as <!--dc:f_*--> HTML comments. This page lists occurrences
 * grouped by journal day and lets you add a new record via POST; record values
 * live inside diary files, so there is no separate delete endpoint here.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import {
  Clock, Hash, Info, ListOrdered, Plus, RefreshCw, Type as TypeIcon,
} from "lucide-react";
import { apiGet, apiSend } from "../../api/client";
import { tr } from "../../i18n/tr";
import {
  EmptyState, ErrorText, Modal, PageTutorialOverlay,
  Snackbar, Spinner, TopBar, useSnackbar,
} from "../../components/ui";

type FieldType = "word" | "number" | "type" | "time" | "duration";

interface SField {
  id: string;
  name: string;
  type: FieldType;
  unit?: string | null;
  options?: string[];
  allowCustomOption?: boolean;
  archived?: boolean;
  sortOrder?: number;
}

interface SRecord {
  id?: number;
  journalDay: string;
  fieldId: string;
  rawValue: string;
  valueType?: string | null;
  sourceFile?: string | null;
  orderInFile?: number | null;
  parsedAt?: number | null;
}

function fieldTypeLabel(t: string): string {
  switch (t) {
    case "word": return tr("文字", "Text");
    case "number": return tr("数字", "Number");
    case "type": return tr("分类", "Category");
    case "time": return tr("时间", "Time");
    case "duration": return tr("时长", "Duration");
    default: return t;
  }
}

function todayIso(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function shiftDays(iso: string, delta: number): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  if (!m) return iso;
  const dt = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]) + delta);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${dt.getFullYear()}-${p(dt.getMonth() + 1)}-${p(dt.getDate())}`;
}

function nowTime(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}`;
}

function formatTime(epochMs?: number | null): string {
  if (!epochMs) return "";
  const d = new Date(epochMs);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}`;
}

function asObj(v: unknown): Record<string, unknown> {
  return v && typeof v === "object" ? (v as Record<string, unknown>) : {};
}

function normalizeFields(data: unknown): SField[] {
  const o = asObj(data);
  const raw = Array.isArray(data)
    ? data
    : Array.isArray(o.fields)
      ? o.fields
      : [];
  return raw.map((f) => {
    const fo = asObj(f);
    const type = String(fo.type ?? "word") as FieldType;
    return {
      id: String(fo.id ?? ""),
      name: String(fo.name ?? fo.id ?? ""),
      type: ["word", "number", "type", "time", "duration"].includes(type) ? type : "word",
      unit: typeof fo.unit === "string" && fo.unit ? fo.unit : null,
      options: Array.isArray(fo.options) ? fo.options.filter((x): x is string => typeof x === "string") : [],
      allowCustomOption: fo.allowCustomOption !== false,
      archived: fo.archived === true,
      sortOrder: typeof fo.sortOrder === "number" ? fo.sortOrder : 0,
    };
  }).filter((f) => f.id);
}

function normalizeRecords(data: unknown): SRecord[] {
  const o = asObj(data);
  const raw = Array.isArray(data)
    ? data
    : Array.isArray(o.records)
      ? o.records
      : Array.isArray(o.items)
        ? o.items
        : Array.isArray(o.occurrences)
          ? o.occurrences
          : [];
  return raw.map((r) => {
    const ro = asObj(r);
    return {
      id: typeof ro.id === "number" ? ro.id : undefined,
      journalDay: String(ro.journalDay ?? ro.day ?? ""),
      fieldId: String(ro.fieldId ?? ""),
      rawValue: String(ro.rawValue ?? ro.value ?? ""),
      valueType: typeof ro.valueType === "string" ? ro.valueType : null,
      sourceFile: typeof ro.sourceFile === "string" ? ro.sourceFile : null,
      orderInFile: typeof ro.orderInFile === "number" ? ro.orderInFile : null,
      parsedAt: typeof ro.parsedAt === "number" ? ro.parsedAt : null,
    };
  }).filter((r) => r.journalDay && r.fieldId);
}

export default function DailyRecordsPage() {
  const [searchParams] = useSearchParams();
  const targetDocument = searchParams.get("document")?.trim() || null;
  const [snack, showSnack] = useSnackbar();
  const [fields, setFields] = useState<SField[]>([]);
  const [records, setRecords] = useState<SRecord[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);

  const [rangeEnd, setRangeEnd] = useState(todayIso());
  const rangeStart = shiftDays(rangeEnd, -29);

  const [addOpen, setAddOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const cfg = await apiGet<unknown>("/api/structured/config");
      setFields(normalizeFields(cfg));
      const data = await apiGet<unknown>(
        `/api/structured/records?fromDay=${rangeStart}&toDay=${rangeEnd}`,
      );
      setRecords(normalizeRecords(data));
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  }, [rangeStart, rangeEnd]);

  useEffect(() => {
    void load();
  }, [load]);

  const fieldById = useMemo(() => new Map(fields.map((f) => [f.id, f])), [fields]);

  const groups = useMemo(() => {
    const list = [...(records ?? [])].sort((a, b) => {
      if (a.journalDay !== b.journalDay) return b.journalDay.localeCompare(a.journalDay);
      const oa = a.orderInFile ?? a.parsedAt ?? 0;
      const ob = b.orderInFile ?? b.parsedAt ?? 0;
      return oa - ob;
    });
    const map = new Map<string, SRecord[]>();
    for (const r of list) {
      if (!map.has(r.journalDay)) map.set(r.journalDay, []);
      map.get(r.journalDay)!.push(r);
    }
    return [...map.entries()];
  }, [records]);

  const activeFields = fields.filter((f) => !f.archived);

  return (
    <div className="daily-records-page">
      <TopBar
        title={tr("结构化记录", "Structured records")}
        subtitle={targetDocument
          ? tr(`写入当前日记：${targetDocument}`, `Writing to current diary: ${targetDocument}`)
          : tr("按自然本地日期归档", "Filed by natural local date")}
        actions={
          <button className="dc-icon-btn" aria-label={tr("刷新", "Refresh")} onClick={() => void load()}>
            <RefreshCw size={20} />
          </button>
        }
      />

      {/* Range navigation */}
      <div className="dc-row" style={{ margin: "4px 0 12px" }}>
        <button className="dc-icon-btn" aria-label={tr("前 30 天", "Previous 30 days")} onClick={() => setRangeEnd(shiftDays(rangeEnd, -30))}>
          <span aria-hidden style={{ fontSize: 16 }}>‹</span>
        </button>
        <span style={{ fontVariantNumeric: "tabular-nums" }}>{rangeStart} ~ {rangeEnd}</span>
        <button
          className="dc-icon-btn"
          aria-label={tr("后 30 天", "Next 30 days")}
          disabled={rangeEnd >= todayIso()}
          onClick={() => setRangeEnd(shiftDays(rangeEnd, 30))}
        >
          <span aria-hidden style={{ fontSize: 16 }}>›</span>
        </button>
      </div>

      {/* Hint: where records live */}
      <div className="dc-card dc-row" style={{ padding: "10px 14px", gap: 10, alignItems: "flex-start", marginBottom: 12 }}>
        <Info size={17} className="dc-muted" style={{ flexShrink: 0, marginTop: 2 }} />
        <div className="dc-muted" style={{ fontSize: "0.84em" }}>
          {tr(
            "结构化记录以 <!--dc:f_字段--> 注释形式保存在 Markdown 日记正文中，普通阅读时不可见，可随时从日记重建索引。",
            "Structured records are stored inside Markdown diaries as <!--dc:f_*--> comments — invisible while reading, and the index can always be rebuilt.",
          )}
        </div>
      </div>

      {loading && records === null && <Spinner />}
      {!loading && records === null && error !== null && (
        <EmptyState
          icon={<ListOrdered size={44} />}
          title={tr("无法读取结构化记录", "Could not load structured records")}
          hint={
            <button className="dc-btn dc-btn-tonal" style={{ marginTop: 10 }} onClick={() => void load()}>
              {tr("重试", "Retry")}
            </button>
          }
        />
      )}
      {!loading && records !== null && groups.length === 0 && !error && (
        <EmptyState
          icon={<ListOrdered size={44} />}
          title={tr("还没有结构化记录", "No structured records yet")}
          hint={
            <div className="dc-muted" style={{ fontSize: "0.85em", marginTop: 6, maxWidth: 420 }}>
              {tr(
                "点右下角按钮添加第一条字段记录；字段可在 设置 → 子页面设置 → 日记与媒体 中管理。",
                "Use the bottom-right button to add your first field record; manage fields under Settings → Sub-pages → Diary & media.",
              )}
            </div>
          }
        />
      )}
      <ErrorText error={error && records !== null ? error : null} />

      {/* Day groups */}
      <div className="dc-col" style={{ gap: 16 }}>
        {groups.map(([day, items]) => (
          <div key={day}>
            <div className="dc-row" style={{ marginBottom: 8 }}>
              <span style={{ fontWeight: 600 }}>{day}</span>
              <span className="dc-muted" style={{ fontSize: "0.82em" }}>
                {tr(`${items.length} 条`, `${items.length} records`)}
              </span>
            </div>
            <div className="dc-col" style={{ gap: 8 }}>
              {items.map((r, i) => {
                const field = fieldById.get(r.fieldId);
                const TypeGlyph = r.valueType === "time" || field?.type === "time"
                  ? Clock
                  : field?.type === "number" || field?.type === "duration"
                    ? Hash
                    : TypeIcon;
                return (
                  <div key={(r.id ?? `${r.fieldId}-${i}`) + day} className="dc-card dc-row" style={{ padding: "11px 14px", gap: 10 }}>
                    <TypeGlyph size={17} className="dc-muted" style={{ flexShrink: 0 }} />
                    <div className="dc-grow" style={{ minWidth: 0 }}>
                      <div className="dc-row" style={{ gap: 8 }}>
                        <span style={{ fontWeight: 500 }}>
                          {field?.name ?? r.fieldId}
                        </span>
                        <span className="dc-chip" style={{ padding: "1px 8px", fontSize: "0.72em" }}>
                          {fieldTypeLabel(field?.type ?? r.valueType ?? "word")}
                        </span>
                      </div>
                      <div style={{ marginTop: 3, overflowWrap: "anywhere" }}>{r.rawValue}</div>
                    </div>
                    <div className="dc-muted" style={{ fontSize: "0.78em", textAlign: "right", flexShrink: 0 }}>
                      {formatTime(r.parsedAt)}
                      {r.sourceFile && (
                        <div style={{ maxWidth: 140, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                          {r.sourceFile}
                        </div>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        ))}
      </div>

      <button className="dc-fab" aria-label={tr("新建结构化记录", "New structured record")} onClick={() => setAddOpen(true)}>
        <Plus size={24} />
      </button>

      <AddRecordDialog
        open={addOpen}
        fields={activeFields}
        targetDocument={targetDocument}
        onClose={() => setAddOpen(false)}
        onSaved={async () => {
          setAddOpen(false);
          showSnack(tr("已添加结构化记录", "Structured record added"));
          await load();
        }}
        onError={(msg) => showSnack(msg)}
      />

      <Snackbar message={snack} />
      <PageTutorialOverlay
        pageKey="daily"
        title={tr("结构化记录", "Structured records")}
        lines={[
          tr("点右下角按钮选择字段并填写值，记录会写入对应日记日的 Markdown 正文。", "Use the bottom-right button to pick a field and enter a value; the record is written into that journal day's Markdown."),
          tr("支持文字、数字、分类、时间、时长五种字段类型。", "Five field types are supported: word, number, category, time and duration."),
          tr("统计页会按字段自动汇总这些数据。", "The statistics hub aggregates these values per field automatically."),
        ]}
      />
    </div>
  );
}

function AddRecordDialog(props: {
  open: boolean;
  fields: SField[];
  targetDocument: string | null;
  onClose: () => void;
  onSaved: () => Promise<void>;
  onError: (msg: string) => void;
}) {
  const { fields } = props;
  const [fieldId, setFieldId] = useState("");
  const [journalDay, setJournalDay] = useState(todayIso());
  const [value, setValue] = useState("");
  const [customMode, setCustomMode] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  // Reset the draft each time the dialog opens.
  useEffect(() => {
    if (!props.open) return;
    setFieldId(fields[0]?.id ?? "");
    setJournalDay(todayIso());
    setValue("");
    setCustomMode(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.open]);

  const field = fields.find((f) => f.id === fieldId);

  useEffect(() => {
    if (field?.type === "time") setValue(nowTime());
    else if (!field) setValue("");
    else setValue("");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fieldId]);

  const trimmed = value.trim();
  const hasMarker = trimmed.includes("<!--") || trimmed.includes("-->");
  const invalid = hasMarker
    ? tr("内容包含保留标记 <!-- 与 -->", "The value must not contain reserved markers <!-- or -->")
    : "";

  const canSubmit = !!field && !!trimmed && !submitting && !hasMarker;

  const submit = async () => {
    if (!canSubmit || !field) return;
    setSubmitting(true);
    try {
      await apiSend("/api/structured/records", "POST", {
        journalDay: props.targetDocument ? null : journalDay,
        documentName: props.targetDocument,
        fieldId: field.id,
        rawValue: trimmed,
      });
      await props.onSaved();
    } catch (e) {
      props.onError(e instanceof Error ? e.message : tr("写入失败", "Could not save the record"));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal open={props.open} onClose={props.onClose} title={tr("新建结构化记录", "New structured record")}>
      <div className="dc-col" style={{ gap: 12 }}>
        <label className="dc-col" style={{ gap: 4 }}>
          <span className="dc-muted" style={{ fontSize: "0.84em" }}>{tr("字段", "Field")}</span>
          <select
            className="dc-input"
            value={fieldId}
            onChange={(e) => setFieldId(e.target.value)}
            aria-label={tr("选择字段", "Select a field")}
          >
            {fields.length === 0 && <option value="">{tr("（暂无可用字段）", "(no fields available)")}</option>}
            {fields.map((f) => (
              <option key={f.id} value={f.id}>
                {f.name}（{fieldTypeLabel(f.type)}{f.unit ? ` · ${f.unit}` : ""}）
              </option>
            ))}
          </select>
        </label>

        {props.targetDocument ? (
          <div className="dc-muted" style={{ fontSize: "0.86em", overflowWrap: "anywhere" }}>
            {tr(`将写入当前打开的日记：${props.targetDocument}`, `Will write to the open diary: ${props.targetDocument}`)}
          </div>
        ) : (
          <label className="dc-col" style={{ gap: 4 }}>
            <span className="dc-muted" style={{ fontSize: "0.84em" }}>{tr("自然日期", "Calendar date")}</span>
            <input
              className="dc-input"
              type="date"
              value={journalDay}
              onChange={(e) => setJournalDay(e.target.value)}
              aria-label={tr("自然日期", "Calendar date")}
            />
          </label>
        )}

        {field?.type === "type" && (field.options ?? []).length > 0 && !customMode ? (
          <div className="dc-col" style={{ gap: 4 }}>
            <span className="dc-muted" style={{ fontSize: "0.84em" }}>{tr("值", "Value")}</span>
            <select
              className="dc-input"
              value={value}
              onChange={(e) => {
                if (e.target.value === "__custom__") {
                  setCustomMode(true);
                  setValue("");
                } else {
                  setValue(e.target.value);
                }
              }}
              aria-label={tr("值", "Value")}
            >
              <option value="">{tr("请选择…", "Choose…")}</option>
              {(field.options ?? []).map((o) => (
                <option key={o} value={o}>{o}</option>
              ))}
              {field.allowCustomOption !== false && (
                <option value="__custom__">{tr("自定义…", "Custom…")}</option>
              )}
            </select>
          </div>
        ) : field?.type === "time" ? (
          <div className="dc-col" style={{ gap: 4 }}>
            <span className="dc-muted" style={{ fontSize: "0.84em" }}>{tr("值", "Value")}</span>
            <div className="dc-row">
              <input
                className="dc-input"
                type="time"
                value={value}
                onChange={(e) => setValue(e.target.value)}
                aria-label={tr("值", "Value")}
              />
              <button className="dc-btn" onClick={() => setValue(nowTime())}>{tr("当前时间", "Now")}</button>
            </div>
          </div>
        ) : (
          <label className="dc-col" style={{ gap: 4 }}>
            <span className="dc-muted" style={{ fontSize: "0.84em" }}>{tr("值", "Value")}</span>
            <input
              className="dc-input"
              autoFocus
              type="text"
              inputMode={field?.type === "number" ? "decimal" : undefined}
              placeholder={
                field?.type === "number"
                  ? tr("例如：20", "e.g. 20")
                  : field?.type === "duration"
                    ? tr("例如：30 分钟、1:30、45m", "e.g. 30 min, 1:30, 45m")
                    : field?.type === "word"
                      ? tr("一句话…", "One sentence…")
                      : tr("填写内容", "Enter a value")
              }
              value={value}
              onChange={(e) => setValue(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter") void submit(); }}
              aria-label={tr("值", "Value")}
            />
            {field?.unit && (
              <span className="dc-muted" style={{ fontSize: "0.78em" }}>{tr("单位", "Unit")}：{field.unit}</span>
            )}
            {field?.type === "type" && field.allowCustomOption !== false && customMode && (
              <button className="dc-btn" style={{ alignSelf: "flex-start" }} onClick={() => { setCustomMode(false); setValue(""); }}>
                {tr("返回选择预设选项", "Back to preset options")}
              </button>
            )}
          </label>
        )}

        {invalid && <div style={{ color: "var(--dc-error)", fontSize: "0.84em" }}>{invalid}</div>}
      </div>

      <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 16 }}>
        <button className="dc-btn" onClick={props.onClose}>{tr("取消", "Cancel")}</button>
        <button className="dc-btn dc-btn-filled" disabled={!canSubmit} onClick={() => void submit()}>
          {submitting ? "…" : tr("保存", "Save")}
        </button>
      </div>
    </Modal>
  );
}
