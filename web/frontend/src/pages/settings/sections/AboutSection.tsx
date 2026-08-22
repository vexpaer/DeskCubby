/**
 * 设置 → 关于 (README_for_ai §17.17)：版本信息、数据归属说明与部署安全提示。
 *
 * Read-only section backed by GET /api/system/info; no draft fields, so the
 * shell's 保存 stays disabled and no 恢复默认 button appears.
 */
import React, { useEffect, useState } from "react";
import { AlertTriangle } from "lucide-react";
import { apiGet } from "../../../api/client";
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

export default function AboutSection(_props: SettingsSectionProps) {
  const [info, setInfo] = useState<SystemInfoFull | null>(null);
  const [error, setError] = useState<unknown>(null);

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
