/**
 * 日期记录 (/date_records) — port of Android ui/date/DateRecordScreen.kt.
 * Cards with emoji icon, localized date and days-until text; FAB add/edit
 * dialog with emoji picker row, date input and live countdown preview.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { CalendarDays as IconCalendar, Pencil as IconEdit, Plus as IconPlus, Trash2 as IconDelete } from "lucide-react";
import { apiGet, apiSend } from "../../api/client";
import { tr, uiLanguage } from "../../i18n/tr";
import {
  ConfirmDialog, EmptyState, ErrorText, Modal, PageTutorialOverlay, Snackbar, Spinner, TopBar, useSnackbar,
} from "../../components/ui";

interface DateRecord {
  id: number;
  name: string;
  icon: string;
  dateIso: string;
  createdAt: number;
  updatedAt: number;
}

const COMMON_DATE_ICONS = ["🎯", "🎂", "❤️", "✈️", "🎓", "🏠", "💼", "🎉", "⭐", "📅"];
const DEFAULT_DATE_ICON = "🎯";
const MAX_NAME_CHARS = 256;

function arrayOf<T>(v: unknown): T[] {
  if (Array.isArray(v)) return v as T[];
  if (v && typeof v === "object") {
    const obj = v as Record<string, unknown>;
    for (const key of ["items", "records", "data", "results"]) {
      if (Array.isArray(obj[key])) return obj[key] as T[];
    }
  }
  return [];
}

/** Parses `yyyy-MM-dd` like java.time.LocalDate.parse (no timezone shifting). */
function parseIsoDate(dateIso: string): { year: number; month: number; day: number } | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateIso.trim());
  if (!m) return null;
  const year = Number(m[1]);
  const month = Number(m[2]);
  const day = Number(m[3]);
  if (month < 1 || month > 12 || day < 1 || day > 31) return null;
  return { year, month, day };
}

function todayParts(): { year: number; month: number; day: number } {
  const now = new Date();
  return { year: now.getFullYear(), month: now.getMonth() + 1, day: now.getDate() };
}

function toUtcMillis(p: { year: number; month: number; day: number }): number {
  return Date.UTC(p.year, p.month - 1, p.day);
}

/** Mirrors dateDistanceText in DateRecordScreen.kt. */
export function dateDistanceText(name: string, target: ReturnType<typeof parseIsoDate>): string {
  if (!target) return tr("日期格式无效", "Invalid date");
  const days = Math.round((toUtcMillis(target) - toUtcMillis(todayParts())) / 86_400_000);
  if (days < 0) {
    return tr("距离 $name 已经过去 ${-days} 天", "${-days} days since $name")
      .replace("$name", name).replace("${-days}", String(-days));
  }
  if (days > 0) {
    return tr("还有 $days 天到 $name", "$days days until $name")
      .replace("$name", name).replace("$days", String(days));
  }
  return tr("今天就是 $name", "$name is today").replace("$name", name);
}

function formatLongDate(p: { year: number; month: number; day: number }): string {
  const locale = uiLanguage() === "ENGLISH" ? "en-US" : "zh-CN";
  return new Intl.DateTimeFormat(locale, { dateStyle: "long" }).format(new Date(p.year, p.month - 1, p.day));
}

function codePointLength(s: string): number {
  return [...s].length;
}

export default function DateRecordsPage() {
  const [snack, showSnack] = useSnackbar();
  const [records, setRecords] = useState<DateRecord[] | null>(null);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [showNewEditor, setShowNewEditor] = useState(false);
  const [editorRecord, setEditorRecord] = useState<DateRecord | null>(null);
  const [pendingDelete, setPendingDelete] = useState<DateRecord | null>(null);

  const reload = useCallback(async () => {
    try {
      const data = await apiGet<unknown>("/api/date-records");
      const list = arrayOf<DateRecord>(data).sort((a, b) => a.dateIso.localeCompare(b.dateIso));
      setRecords(list);
      setLoadError(null);
    } catch (e) {
      setLoadError(e);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const fail = useCallback((e: unknown) => {
    showSnack(tr("操作失败", "Operation failed") + ": " + (e instanceof Error ? e.message : String(e)));
  }, [showSnack]);

  const create = async (name: string, icon: string, dateIso: string) => {
    try {
      await apiSend("/api/date-records", "POST", { name, icon, dateIso });
      await reload();
      return true;
    } catch (e) {
      fail(e);
      return false;
    }
  };

  const update = async (id: number, name: string, icon: string, dateIso: string) => {
    try {
      await apiSend(`/api/date-records/${id}`, "PUT", { name, icon, dateIso });
      await reload();
      return true;
    } catch (e) {
      fail(e);
      return false;
    }
  };

  const remove = async (id: number) => {
    try {
      await apiSend(`/api/date-records/${id}`, "DELETE");
      await reload();
    } catch (e) {
      fail(e);
    }
  };

  return (
    <div>
      <TopBar title={tr("日期记录", "Dates")} />
      {loadError != null && (
        <div className="dc-row">
          <ErrorText error={loadError} />
          <button className="dc-btn dc-btn-tonal" onClick={() => { setRecords(null); void reload(); }}>
            {tr("重试", "Retry")}
          </button>
        </div>
      )}
      {records == null && loadError == null && <Spinner />}
      {records != null && records.length === 0 && (
        <EmptyState
          icon={<IconCalendar size={40} />}
          title={tr("还没有日期记录", "No dates yet")}
          hint={tr("添加纪念日、目标日或其他重要日期", "Add an anniversary, goal, or important date")}
        />
      )}
      <div className="dc-col" style={{ padding: "12px 4px", gap: 12 }}>
        {(records ?? []).map((record) => {
          const target = parseIsoDate(record.dateIso);
          return (
            <div
              key={record.id}
              className="dc-card"
              role="button"
              tabIndex={0}
              onKeyDown={(e) => { if (e.key === "Enter") setEditorRecord(record); }}
              onClick={() => setEditorRecord(record)}
              style={{ display: "flex", alignItems: "center", gap: 14, padding: "14px 16px", cursor: "pointer" }}
            >
              <span
                aria-hidden
                style={{
                  width: 52, height: 52, borderRadius: "var(--dc-radius)", flexShrink: 0,
                  background: "var(--dc-secondary-container)", color: "var(--dc-on-secondary-container)",
                  display: "flex", alignItems: "center", justifyContent: "center", fontSize: 25,
                }}
              >
                {(record.icon || DEFAULT_DATE_ICON).slice(0, 4)}
              </span>
              <span className="dc-grow dc-col" style={{ gap: 2, minWidth: 0 }}>
                <span style={{ fontWeight: 600, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{record.name}</span>
                <span className="dc-muted" style={{ fontSize: "0.85em" }}>
                  {target ? formatLongDate(target) : record.dateIso}
                </span>
                <span style={{ color: "var(--dc-primary)", fontWeight: 500, marginTop: 4 }}>
                  {dateDistanceText(record.name, target)}
                </span>
              </span>
              <button
                className="dc-icon-btn"
                aria-label={tr("编辑", "Edit")}
                onClick={(e) => { e.stopPropagation(); setEditorRecord(record); }}
              >
                <IconEdit size={18} />
              </button>
              <button
                className="dc-icon-btn"
                aria-label={tr("删除", "Delete")}
                style={{ color: "var(--dc-error)" }}
                onClick={(e) => { e.stopPropagation(); setPendingDelete(record); }}
              >
                <IconDelete size={18} />
              </button>
            </div>
          );
        })}
      </div>

      <button className="dc-fab" aria-label={tr("添加日期", "Add date")} onClick={() => setShowNewEditor(true)}>
        <IconPlus size={24} />
      </button>

      {showNewEditor && (
        <DateEditorDialog
          record={null}
          onClose={() => setShowNewEditor(false)}
          onConfirm={async (name, icon, dateIso) => {
            const ok = await create(name, icon, dateIso);
            if (ok) setShowNewEditor(false);
          }}
        />
      )}
      {editorRecord && (
        <DateEditorDialog
          record={editorRecord}
          onClose={() => setEditorRecord(null)}
          onConfirm={async (name, icon, dateIso) => {
            const ok = await update(editorRecord.id, name, icon, dateIso);
            if (ok) setEditorRecord(null);
          }}
        />
      )}

      <ConfirmDialog
        open={pendingDelete != null}
        title={tr("删除日期？", "Delete date?")}
        message={pendingDelete
          ? tr(
              "将删除“" + pendingDelete.name + "”，此操作无法撤销。",
              "\u201c" + pendingDelete.name + "\u201d will be deleted. This cannot be undone.",
            )
          : undefined}
        confirmLabel={tr("删除", "Delete")}
        cancelLabel={tr("取消", "Cancel")}
        danger
        onCancel={() => setPendingDelete(null)}
        onConfirm={() => {
          const record = pendingDelete;
          setPendingDelete(null);
          if (record) void remove(record.id);
        }}
      />
      <Snackbar message={snack} />
      <PageTutorialOverlay
        pageKey="date_records"
        title={tr("日期记录", "Date records")}
        lines={[tr("记录纪念日与目标日期，自动显示正倒数天数。", "Track anniversaries and target dates with automatic countdowns and count-ups.")]}
      />
    </div>
  );
}

function DateEditorDialog(props: {
  record: DateRecord | null;
  onClose: () => void;
  onConfirm: (name: string, icon: string, dateIso: string) => Promise<void>;
}) {
  const initial = useMemo(() => {
    const parsed = props.record ? parseIsoDate(props.record.dateIso) : null;
    if (parsed) return parsed;
    return todayParts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.record?.id]);
  const [name, setName] = useState(props.record?.name ?? "");
  const [icon, setIcon] = useState(props.record?.icon || DEFAULT_DATE_ICON);
  const [dateValue, setDateValue] = useState(
    `${String(initial.year).padStart(4, "0")}-${String(initial.month).padStart(2, "0")}-${String(initial.day).padStart(2, "0")}`,
  );
  const previewName = name.trim().length > 0 ? name.trim() : tr("这个日期", "this date");
  const target = parseIsoDate(dateValue);

  return (
    <Modal open onClose={props.onClose} title={props.record ? tr("编辑日期", "Edit date") : tr("添加日期", "Add date")} width={480}>
      <div className="dc-col" style={{ gap: 12 }}>
        <label className="dc-col" style={{ gap: 6, alignItems: "stretch" }}>
          <span>{tr("名称", "Name")}</span>
          <input
            className="dc-input"
            value={name}
            maxLength={MAX_NAME_CHARS}
            placeholder={tr("例如：旅行出发", "e.g. Start of trip")}
            onChange={(e) => setName(e.target.value.slice(0, MAX_NAME_CHARS))}
          />
        </label>
        <label className="dc-col" style={{ gap: 6, alignItems: "stretch" }}>
          <span>{tr("图标（可直接输入 Emoji）", "Icon (enter an emoji)")}</span>
          <input
            className="dc-input"
            value={icon}
            onChange={(e) => {
              if (codePointLength(e.target.value) <= 4) setIcon(e.target.value);
            }}
          />
        </label>
        <div style={{ display: "flex", gap: 6, overflowX: "auto", padding: 2 }}>
          {COMMON_DATE_ICONS.map((candidate) => (
            <button
              key={candidate}
              onClick={() => setIcon(candidate)}
              aria-label={candidate}
              style={{
                width: 42, height: 42, fontSize: 21, flexShrink: 0,
                borderRadius: "calc(var(--dc-radius) * 0.7)", border: "none",
                background: icon === candidate ? "var(--dc-secondary-container)" : "var(--dc-surface-variant)",
                color: icon === candidate ? "var(--dc-on-secondary-container)" : "var(--dc-on-surface)",
              }}
            >
              {candidate}
            </button>
          ))}
        </div>
        <label className="dc-col" style={{ gap: 6, alignItems: "stretch" }}>
          <span className="dc-row" style={{ gap: 6 }}><IconCalendar size={16} /> {tr("日期", "Date")}</span>
          <input className="dc-input" type="date" value={dateValue} onChange={(e) => setDateValue(e.target.value)} />
        </label>
        <div className="dc-muted" style={{ fontSize: "0.9em" }}>{dateDistanceText(previewName, target)}</div>
      </div>
      <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 16 }}>
        <button className="dc-btn" onClick={props.onClose}>{tr("取消", "Cancel")}</button>
        <button
          className="dc-btn dc-btn-filled"
          disabled={name.trim().length === 0}
          onClick={() => void props.onConfirm(name.trim(), icon.trim() || DEFAULT_DATE_ICON, dateValue)}
        >
          {tr("保存", "Save")}
        </button>
      </div>
    </Modal>
  );
}
