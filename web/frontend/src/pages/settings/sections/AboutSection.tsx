/**
 * 设置 → 关于 (README_for_ai §17.17)：版本信息、数据归属说明与部署安全提示。
 *
 * Read-only section backed by GET /api/system/info; no draft fields, so the
 * shell's 保存 stays disabled and no 恢复默认 button appears.
 */
import React, { useEffect, useState } from "react";
import { AlertTriangle } from "lucide-react";
import { apiGet } from "../../../api/client";
import { useSettings } from "../../../stores/settings";
import { tr } from "../../../i18n/tr";
import { ErrorText, Spinner } from "../../../components/ui";
import { SectionCard } from "../SettingsPage";
import type { SettingsSectionProps } from "../SettingsPage";

interface DataUsage {
  diaryMB?: number;
  mediaMB?: number;
  notesMB?: number;
  booksMB?: number;
  backupsMB?: number;
  privateMB?: number;
}

interface SystemInfoFull {
  version?: string;
  platform?: string;
  deployment?: {
    scheme?: string;
    behindProxy?: boolean;
    publicDeployment?: boolean;
    suggestPassword?: boolean;
    suggestHttps?: boolean;
  };
  dataUsage?: DataUsage;
}

const USAGE_ROWS: { key: keyof DataUsage; zh: string; en: string }[] = [
  { key: "diaryMB", zh: "日记", en: "Diaries" },
  { key: "mediaMB", zh: "媒体", en: "Media" },
  { key: "notesMB", zh: "笔记", en: "Notes" },
  { key: "booksMB", zh: "书籍", en: "Books" },
  { key: "backupsMB", zh: "备份", en: "Backups" },
  { key: "privateMB", zh: "其他", en: "Other" },
];

function fmtMb(v: number | undefined): string {
  return `${(typeof v === "number" && Number.isFinite(v) ? v : 0).toFixed(2)} MB`;
}

export default function AboutSection({ settings, draft, patch, snackbar }: SettingsSectionProps) {
  const updateSettings = useSettings((state) => state.update);
  const [info, setInfo] = useState<SystemInfoFull | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [tutorialSaving, setTutorialSaving] = useState(false);

  useEffect(() => {
    let alive = true;
    apiGet<SystemInfoFull>("/api/system/info")
      .then((data) => { if (alive) setInfo(data); })
      .catch((e) => { if (alive) setError(e); });
    return () => { alive = false; };
  }, []);

  const deploy = info?.deployment;
  const showDeployNotice = !!deploy && (!!deploy.suggestHttps || !!deploy.publicDeployment);

  return (
    <div className="dc-col" style={{ gap: 12 }}>
      <SectionCard title={tr("关于", "About")}>
        {!info && !error && <Spinner size={20} />}
        <ErrorText error={error} />
        <img src="/icons/icon-192.png" alt="" width={64} height={64}
          style={{ alignSelf: "center", objectFit: "contain" }} />
        <div className="dc-row" style={{ justifyContent: "space-between" }}>
          <span>{tr("应用名称", "App name")}</span>
          <span style={{ fontWeight: 600 }}>DeskCubby</span>
        </div>
        <div className="dc-row" style={{ justifyContent: "space-between" }}>
          <span>{tr("版本", "Version")}</span>
          <span className="dc-muted">{info?.version ?? "—"}</span>
        </div>
        <div className="dc-row" style={{ justifyContent: "space-between" }}>
          <span>{tr("平台", "Platform")}</span>
          <span className="dc-muted">Web</span>
        </div>
      </SectionCard>

      <SectionCard title={tr("页面教学", "Page tutorials")}>
        <div className="dc-row" style={{ justifyContent: "space-between", gap: 12 }}>
          <span className="dc-grow">
            {tr("软件教学模式", "Tutorial mode")}
            <div className="dc-muted" style={{ fontSize: "0.82em" }}>
              {tr("首次进入每个页面时显示一次说明蒙版。", "Shows a walkthrough once on the first visit to each page.")}
            </div>
          </span>
          <button
            type="button" role="switch" aria-checked={draft.tutorialModeEnabled}
            onClick={() => patch({ tutorialModeEnabled: !draft.tutorialModeEnabled })}
            style={{
              width: 46, height: 26, borderRadius: 999, border: "none", position: "relative", flexShrink: 0, padding: 0,
              background: draft.tutorialModeEnabled ? "var(--dc-primary)" : "var(--dc-surface-variant)",
            }}
          >
            <span style={{
              position: "absolute", top: 3, left: draft.tutorialModeEnabled ? 23 : 3, width: 20, height: 20,
              borderRadius: "50%", background: draft.tutorialModeEnabled ? "var(--dc-on-primary)" : "var(--dc-outline)",
              transition: "left 0.18s ease",
            }} />
          </button>
        </div>
        <button className="dc-btn dc-btn-tonal" disabled={(settings.tutorialAcknowledgedPages ?? []).length === 0}
          onClick={() => {
            patch({ tutorialAcknowledgedPages: [] });
            snackbar(tr("保存后将重新显示全部页面教学", "Save to show all page tutorials again"));
          }}>
          {tr("重新显示全部页面教学", "Show all page tutorials again")}
        </button>
        <button className="dc-btn dc-btn-filled" disabled={tutorialSaving || (
          draft.tutorialModeEnabled === settings.tutorialModeEnabled &&
          JSON.stringify(draft.tutorialAcknowledgedPages ?? []) === JSON.stringify(settings.tutorialAcknowledgedPages ?? [])
        )} onClick={() => {
          // About is an immediate/read-only shell page, so persist this small
          // draft through the central settings store directly.
          setTutorialSaving(true);
          void updateSettings({
            tutorialModeEnabled: draft.tutorialModeEnabled,
            tutorialAcknowledgedPages: draft.tutorialAcknowledgedPages ?? [],
          }).then(() => {
            snackbar(tr("已保存", "Saved"));
          }).catch((reason: unknown) => {
            setError(reason);
          }).finally(() => setTutorialSaving(false));
        }}>
          {tutorialSaving ? tr("保存中…", "Saving…") : tr("保存页面教学设置", "Save tutorial settings")}
        </button>
      </SectionCard>

      <SectionCard title={tr("数据占用", "Data usage")}>
        {USAGE_ROWS.map((row) => (
          <div key={row.key} className="dc-row" style={{ justifyContent: "space-between", gap: 12 }}>
            <span>{tr(row.zh, row.en)}</span>
            <span className="dc-muted" style={{ fontVariantNumeric: "tabular-nums" }}>
              {fmtMb(info?.dataUsage?.[row.key])}
            </span>
          </div>
        ))}
      </SectionCard>

      <SectionCard title={tr("隐私与数据归属", "Privacy & data ownership")}>
        <div className="dc-muted" style={{ fontSize: "0.88em", lineHeight: 1.7 }}>
          {tr(
            "本地优先：日记、媒体、笔记与数据库均保存在你自己的服务器数据目录中，可随时整体备份迁移。",
            "Local-first: diaries, media, notes, and the database all live in your own server's data directory and can be backed up or migrated as a whole at any time.",
          )}
        </div>
        <div className="dc-muted" style={{ fontSize: "0.88em", lineHeight: 1.7 }}>
          {tr(
            "完整使用教学见仓库内 README_for_ai.md。",
            "The full user guide lives in README_for_ai.md inside the repository.",
          )}
        </div>
      </SectionCard>

      {showDeployNotice && (
        <div className="dc-card dc-row" role="alert"
          style={{ padding: "10px 12px", borderColor: "var(--dc-error)", alignItems: "flex-start", gap: 10 }}>
          <AlertTriangle size={18} style={{ color: "var(--dc-error)", flexShrink: 0, marginTop: 2 }} />
          <div className="dc-grow" style={{ fontSize: "0.9em" }}>
            {tr(
              "检测到公网部署：强烈建议开启访问密码并通过反向代理启用 HTTPS。",
              "Public deployment detected: strongly enable an access password and serve via a reverse proxy with HTTPS.",
            )}
          </div>
        </div>
      )}
    </div>
  );
}
