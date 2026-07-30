import {
  BookOpen,
  CheckCircle2,
  Download,
  ExternalLink,
  Github,
  Info,
  LoaderCircle,
  RefreshCw,
  ShieldCheck,
  Sparkles,
} from "lucide-react";
import { useCallback, useEffect, useState } from "react";

import { EmptyState, LoadingState, PageFrame } from "../components";
import { readableError, tr } from "../lib/ipc";
import {
  subscribeUpdateDownloadProgress,
  updateApi,
  type OfficialLinkTarget,
  type UpdateCheckResultV1,
  type UpdateDownloadProgressV1,
  type UpdateStateV1,
} from "../lib/updateApi";
import { useAppStore } from "../store/appStore";

function formatPublishedDate(value: string | null, locale: string): string | null {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return null;
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(date);
}

function formatBytes(value: string, language: "zh-CN" | "en"): string {
  const bytes = BigInt(value);
  const units = ["B", "KiB", "MiB", "GiB", "TiB", "EiB"];
  let unit = 0;
  let divisor = 1n;
  while (unit < units.length - 1 && bytes >= divisor * 1024n) {
    divisor *= 1024n;
    unit += 1;
  }
  if (unit === 0) {
    return language === "zh-CN" ? `${bytes} 字节` : `${bytes} B`;
  }
  const whole = bytes / divisor;
  const decimal = ((bytes % divisor) * 10n) / divisor;
  const valueText = decimal === 0n ? `${whole}` : `${whole}.${decimal}`;
  return `${valueText} ${units[unit]}`;
}

function progressPercent(progress: UpdateDownloadProgressV1): number | null {
  if (progress.totalBytes === null) return null;
  const total = BigInt(progress.totalBytes);
  if (total === 0n) return null;
  const downloaded = BigInt(progress.downloadedBytes);
  return Number(
    (downloaded > total ? 100n : (downloaded * 100n) / total),
  );
}

export default function AboutPage() {
  const language = useAppStore((state) => state.appearance.language);
  const copy = useCallback(
    (zh: string, en: string) => tr(language, zh, en),
    [language],
  );
  const [state, setState] = useState<UpdateStateV1 | null>(null);
  const [result, setResult] = useState<UpdateCheckResultV1 | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<
    "automatic" | "check" | "install" | "link" | null
  >(null);
  const [downloadProgress, setDownloadProgress] =
    useState<UpdateDownloadProgressV1 | null>(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setState(await updateApi.state());
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
    let active = true;
    let unlisten: (() => void) | undefined;
    void subscribeUpdateDownloadProgress((progress) => {
      if (active) setDownloadProgress(progress);
    })
      .then((stop) => {
        if (active) unlisten = stop;
        else stop();
      })
      .catch(() => {
        // Progress events are optional UI enhancement. The updater command
        // still reports a stable error if installation itself fails.
      });
    return () => {
      active = false;
      unlisten?.();
    };
  }, []);

  async function check() {
    setBusy("check");
    setError("");
    setResult(null);
    setDownloadProgress(null);
    try {
      setResult(await updateApi.check());
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setBusy(null);
    }
  }

  async function setAutomaticChecks(enabled: boolean) {
    setBusy("automatic");
    setError("");
    try {
      await updateApi.setAutomaticChecks(enabled);
      setState(await updateApi.state());
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setBusy(null);
    }
  }

  async function install(version: string) {
    setBusy("install");
    setError("");
    setDownloadProgress({
      schemaVersion: 1,
      downloadedBytes: "0",
      totalBytes: null,
    });
    try {
      await updateApi.install(version);
    } catch (reason) {
      setError(readableError(reason, language));
      setDownloadProgress(null);
      setBusy(null);
    }
  }

  async function openLink(target: OfficialLinkTarget) {
    setBusy("link");
    setError("");
    try {
      await updateApi.openOfficialLink(target);
    } catch (reason) {
      setError(readableError(reason, language));
    } finally {
      setBusy(null);
    }
  }

  if (loading && !state) {
    return (
      <PageFrame title={copy("关于", "About")}>
        <div className="panel">
          <LoadingState label={copy("正在读取应用信息", "Loading app information")} />
        </div>
      </PageFrame>
    );
  }

  if (!state) {
    return (
      <PageFrame title={copy("关于", "About")}>
        <div className="panel">
          <EmptyState
            title={copy("无法读取应用信息", "App information unavailable")}
            description={error}
            icon={Info}
            action={
              <button className="button-secondary" type="button" onClick={() => void load()}>
                <RefreshCw aria-hidden="true" size={16} />
                {copy("重试", "Retry")}
              </button>
            }
          />
        </div>
      </PageFrame>
    );
  }

  const available = result?.kind === "AVAILABLE" ? result : null;
  const locale = language === "en" ? "en-US" : "zh-CN";
  const publishedDate = formatPublishedDate(
    available?.publishedAt ?? null,
    locale,
  );
  const downloadPercent = downloadProgress
    ? progressPercent(downloadProgress)
    : null;

  return (
    <PageFrame
      className="about-page"
      eyebrow={copy("设置 → 关于", "Settings → About")}
      title={copy("关于 DeskCubby", "About DeskCubby")}
      description={copy(
        "本地优先的日记、记录与私人数据空间。",
        "A local-first home for diaries, records and private data.",
      )}
    >
      {error ? <div className="inline-error" role="alert">{error}</div> : null}

      <section className="about-hero panel">
        <span className="about-app-mark" aria-hidden="true">
          <Sparkles />
        </span>
        <div>
          <h2>DeskCubby</h2>
          <p>
            {copy("Windows 版本 ", "Windows version ")}
            <strong>{state.currentVersion}</strong>
          </p>
          <span className="badge">
            {copy("本地优先", "Local first")}
          </span>
        </div>
      </section>

      <div className="about-grid">
        <section className="panel about-links" aria-labelledby="about-links-title">
          <div className="settings-section-heading">
            <Info aria-hidden="true" size={21} />
            <div>
              <h2 id="about-links-title">{copy("应用信息", "App information")}</h2>
              <p>{copy("链接由 Rust 白名单验证后交给系统浏览器。", "Rust validates these fixed links before opening the system browser.")}</p>
            </div>
          </div>
          <button
            className="about-link-button"
            type="button"
            disabled={busy !== null}
            onClick={() => void openLink("REPOSITORY")}
          >
            <Github aria-hidden="true" size={20} />
            <span>
              <strong>{copy("GitHub 仓库", "GitHub repository")}</strong>
              <small>{copy("查看源码与发布记录", "Source code and releases")}</small>
            </span>
            <ExternalLink aria-hidden="true" size={16} />
          </button>
          <button
            className="about-link-button"
            type="button"
            disabled={busy !== null}
            onClick={() => void openLink("TUTORIAL")}
          >
            <BookOpen aria-hidden="true" size={20} />
            <span>
              <strong>{copy("应用教学", "App tutorial")}</strong>
              <small>{copy("逐页面操作说明", "Page-by-page instructions")}</small>
            </span>
            <ExternalLink aria-hidden="true" size={16} />
          </button>
        </section>

        <section className="panel update-panel" aria-labelledby="update-panel-title">
          <div className="settings-section-heading">
            <Download aria-hidden="true" size={21} />
            <div>
              <h2 id="update-panel-title">{copy("应用更新", "App updates")}</h2>
              <p>
                {!state.configured
                  ? copy(
                      "此构建未配置可信更新源或更新器公钥，因此不会发起更新请求。",
                      "This build has no trusted update endpoint or updater public key, so it makes no update requests.",
                    )
                  : state.automaticChecksEnabled
                  ? copy("启动后会自动检查，不会静默安装。", "Updates are checked automatically after launch and never installed silently.")
                  : copy("自动检查已关闭，可随时手动检查。", "Automatic checks are off; you can check manually.")}
              </p>
            </div>
          </div>

          <div className="update-auto-row">
            <div>
              <strong>{copy("启动时自动检查", "Check automatically at launch")}</strong>
              <small>
                {state.configured
                  ? copy(
                      "只显示提示，不会自动下载或安装。",
                      "Only shows a notification; never downloads or installs automatically.",
                    )
                  : copy(
                      "此构建未配置可信更新源，因此不可启用。",
                      "Unavailable because this build has no trusted update source.",
                    )}
              </small>
            </div>
            <label className="switch-row">
              <input
                aria-label={copy("启动时自动检查更新", "Check for updates at launch")}
                type="checkbox"
                checked={state.configured && state.automaticChecksEnabled}
                disabled={!state.configured || busy !== null}
                onChange={(event) =>
                  void setAutomaticChecks(event.target.checked)
                }
              />
              {state.configured
                ? state.automaticChecksEnabled
                  ? copy("已开启", "Enabled")
                  : copy("已关闭", "Disabled")
                : copy("不可用", "Unavailable")}
            </label>
          </div>

          {!state.configured ? (
            <div className="status-banner warning" role="status">
              {copy(
                "此构建没有配置可信更新源或更新公钥。",
                "This build has no trusted update endpoint or updater public key.",
              )}
            </div>
          ) : (
            <>
              <div className="update-security-note">
                <ShieldCheck aria-hidden="true" size={19} />
                <p>
                  {copy(
                    "下载安装前会重新检查版本并验证 Tauri 更新签名。Windows 发布者身份由安装包的 Authenticode 证书单独决定。",
                    "The version and Tauri update signature are rechecked before installation. Windows publisher identity is separately determined by the installer's Authenticode certificate.",
                  )}
                </p>
              </div>
              <button
                className="button-primary"
                type="button"
                disabled={busy !== null}
                onClick={() => void check()}
              >
                {busy === "check" ? (
                  <LoaderCircle className="spin" aria-hidden="true" size={17} />
                ) : (
                  <RefreshCw aria-hidden="true" size={17} />
                )}
                {busy === "check" ? copy("正在检查…", "Checking…") : copy("检查更新", "Check for updates")}
              </button>
            </>
          )}

          {result?.kind === "UP_TO_DATE" ? (
            <div className="update-result is-current" role="status">
              <CheckCircle2 aria-hidden="true" size={20} />
              <div>
                <strong>{copy("已是最新版本", "You're up to date")}</strong>
                <span>{result.currentVersion}</span>
              </div>
            </div>
          ) : null}

          {available ? (
            <article className="update-result is-available" aria-live="polite">
              <div className="update-version-row">
                <div>
                  <span className="eyebrow">{copy("发现新版本", "Update available")}</span>
                  <strong>{available.version}</strong>
                </div>
                {publishedDate ? (
                  <time dateTime={available.publishedAt ?? undefined}>
                    {publishedDate}
                  </time>
                ) : null}
              </div>
              {available.notes ? (
                <div className="update-notes">
                  <h3>{copy("更新说明", "Release notes")}</h3>
                  <p>{available.notes}</p>
                </div>
              ) : null}
              <button
                className="button-primary"
                type="button"
                disabled={busy !== null}
                onClick={() => void install(available.version)}
              >
                {busy === "install" ? (
                  <LoaderCircle className="spin" aria-hidden="true" size={17} />
                ) : (
                  <Download aria-hidden="true" size={17} />
                )}
                {busy === "install"
                  ? copy("正在下载并验证…", "Downloading and verifying…")
                  : copy("下载、验证并安装", "Download, verify & install")}
              </button>
              {busy === "install" && downloadProgress ? (
                <div className="update-progress" aria-live="polite">
                  <div
                    className={`update-progress-track ${
                      downloadPercent === null ? "is-indeterminate" : ""
                    }`}
                    role="progressbar"
                    aria-label={copy("更新下载进度", "Update download progress")}
                    aria-valuemin={0}
                    aria-valuemax={100}
                    aria-valuenow={downloadPercent ?? undefined}
                    aria-valuetext={
                      downloadProgress.totalBytes
                        ? `${formatBytes(downloadProgress.downloadedBytes, language)} / ${formatBytes(downloadProgress.totalBytes, language)}`
                        : copy(
                            `已下载 ${formatBytes(downloadProgress.downloadedBytes, language)}`,
                            `${formatBytes(downloadProgress.downloadedBytes, language)} downloaded`,
                          )
                    }
                  >
                    <span
                      style={
                        downloadPercent === null
                          ? undefined
                          : { width: `${downloadPercent}%` }
                      }
                    />
                  </div>
                  <small>
                    {downloadProgress.totalBytes
                      ? `${formatBytes(downloadProgress.downloadedBytes, language)} / ${formatBytes(downloadProgress.totalBytes, language)}`
                      : copy(
                          `已下载 ${formatBytes(downloadProgress.downloadedBytes, language)}`,
                          `${formatBytes(downloadProgress.downloadedBytes, language)} downloaded`,
                        )}
                  </small>
                </div>
              ) : null}
              <small className="form-hint">
                {copy(
                  "安装完成后应用会重新启动；未保存内容应先保存。",
                  "The app restarts after installation. Save open work first.",
                )}
              </small>
            </article>
          ) : null}
        </section>
      </div>
    </PageFrame>
  );
}
