/**
 * 收藏夹 VaultPage (/vault) — faithful web port of android ui/vault/VaultScreen.kt
 * (README_for_ai.md §12): three states — first-run setup (设置收藏夹密码), locked
 * screen titled 收藏夹已锁定, and the unlocked encrypted list.
 *
 * Server-side contract (web/backend/app/routers/vault.py):
 *   GET  /api/vault/status            {hasPassword, unlocked}
 *   POST /api/vault/setup|unlock|lock {password}          (unlock 401 = wrong password)
 *   POST /api/vault/change-password   {password,newPassword}
 *   GET/POST /api/vault/items  PUT/DELETE /api/vault/items/{id}
 *   POST /api/vault/items/reorder     [{id},...] full order
 *
 * Passwords are counted in Unicode code points (min 1, no maximum); wrong-password
 * 401s are handled inline (never a login redirect), plaintext never leaves this page
 * except through the item API responses.
 */
import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  Archive,
  ArrowDown,
  ArrowUp,
  Eye,
  EyeOff,
  KeyRound,
  Lock,
  MoreVertical,
  Plus,
  Unlock,
} from "lucide-react";
import { ApiClientError, apiGet, apiSend } from "../../api/client";
import { tr } from "../../i18n/tr";
import { useSettingsOrThrow } from "../../stores/settings";
import {
  EmptyState,
  ErrorText,
  Modal,
  PageTutorialOverlay,
  PopupMenu,
  Snackbar,
  Spinner,
  TopBar,
  useSnackbar,
} from "../../components/ui";

interface VaultStatus {
  hasPassword: boolean;
  unlocked: boolean;
}

interface VaultItemDto {
  id: number;
  content: string;
  note: string | null;
  createdAt: number;
  updatedAt: number;
  sortOrder: number;
}

interface VaultItemsResponse {
  items: VaultItemDto[];
  corruptedItemCount: number;
}

type Phase = "loading" | "setup" | "locked" | "unlocked";
type LoadError = { message: string };

/** Raw JSON POST that does NOT redirect to /login on 401 (vault passwords are not sessions). */
async function vaultPost(path: string, body: unknown): Promise<{ ok: boolean; status: number; code: string }> {
  try {
    const resp = await fetch(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (resp.ok) return { ok: true, status: resp.status, code: "" };
    let code = "http_" + resp.status;
    try {
      const data = await resp.json();
      if (data?.error?.code) code = String(data.error.code);
    } catch {
      /* keep fallback code */
    }
    return { ok: false, status: resp.status, code };
  } catch {
    return { ok: false, status: 0, code: "network_error" };
  }
}

/** Unicode code point count (mirrors Android's code-point based length limit). */
function codePointLength(value: string): number {
  return Array.from(value).length;
}

/**
 * Port of VaultEntryUrl.safeVaultHttpUrlOrNull: the whole content must be one absolute
 * http(s) URL without whitespace/control characters, user info or an empty host.
 */
export function safeVaultHttpUrl(rawContent: string): string | null {
  const candidate = rawContent.trim();
  if (!candidate) return null;
  // Whitespace or C0/C1 control characters anywhere make it ordinary text.
  for (const ch of candidate) {
    const code = ch.codePointAt(0) ?? 0;
    if (code <= 0x20 || (code >= 0x7f && code <= 0x9f)) return null;
  }
  if (!/^https?:\/\//i.test(candidate)) return null;
  let url: URL;
  try {
    url = new URL(candidate);
  } catch {
    return null;
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") return null;
  if (url.username || url.password) return null;
  if (!url.hostname || /\s/.test(url.hostname)) return null;
  const port = Number(url.port);
  if (url.port !== "" && (!Number.isFinite(port) || port > 65_535)) return null;
  return candidate;
}

export default function VaultPage() {
  const settings = useSettingsOrThrow();
  const [snack, showSnack] = useSnackbar();

  const [phase, setPhase] = useState<Phase>("loading");
  const [loadError, setLoadError] = useState<LoadError | null>(null);

  const refreshStatus = useCallback(async () => {
    setLoadError(null);
    try {
      const status = await apiGet<VaultStatus>("/api/vault/status");
      setPhase(status.unlocked ? "unlocked" : status.hasPassword ? "locked" : "setup");
    } catch (error) {
      setLoadError({ message: error instanceof Error ? error.message : String(error) });
    }
  }, []);

  useEffect(() => {
    void refreshStatus();
  }, [refreshStatus]);

  if (phase === "loading") {
    return (
      <div className="dc-page">
        <TopBar title={tr("收藏夹", "Vault")} />
        {loadError ? (
          <div role="alert">
            <ErrorText error={loadError.message} />
            <button className="dc-btn dc-btn-tonal" onClick={() => void refreshStatus()}>
              {tr("重试", "Retry")}
            </button>
          </div>
        ) : (
          <Spinner />
        )}
      </div>
    );
  }
  if (phase === "setup") {
    return <VaultSetupScreen onDone={refreshStatus} />;
  }
  if (phase === "locked") {
    return <VaultLockedScreen snackbar={snack} onUnlocked={refreshStatus} />;
  }
  return <VaultUnlockedScreen key="unlocked" rowHeightDp={settings.vaultRowHeightDp} onLocked={refreshStatus} snackbar={snack} showSnack={showSnack} />;
}

// ---------------------------------------------------------------------------
// Setup screen (README §12 首次设置页)
// ---------------------------------------------------------------------------

function VaultSetupScreen({ onDone }: { onDone: () => Promise<void> }) {
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [failed, setFailed] = useState(false);

  const mismatch = confirm.length > 0 && confirm !== password;
  const tooShort = codePointLength(password) < 1;
  const canSubmit = !tooShort && !mismatch && !submitting;

  const submit = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    setFailed(false);
    const result = await vaultPost("/api/vault/setup", { password });
    setSubmitting(false);
    setPassword("");
    setConfirm("");
    if (result.ok) {
      await onDone();
    } else {
      setFailed(true);
    }
  };

  return (
    <div className="dc-page dc-col dc-center" style={{ minHeight: "60vh", padding: 24 }}>
      <form
        className="dc-col"
        style={{ width: "min(420px, 100%)", gap: 10 }}
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <Lock size={44} style={{ color: "var(--dc-primary)", alignSelf: "center" }} />
        <div className="dc-title" style={{ textAlign: "center" }}>
          {tr("设置收藏夹密码", "Set a vault password")}
        </div>
        <div className="dc-muted" style={{ textAlign: "center", fontSize: "0.92em" }}>
          {tr(
            "收藏夹中的内容会使用由密码派生的密钥在本机加密保存。请务必牢记密码：一旦丢失将无法找回，加密数据也无法解密。",
            "Vault entries are encrypted on this device with a key derived from your password. Remember it carefully: a lost password cannot be recovered, and the encrypted data cannot be decrypted without it.",
          )}
        </div>
        <PasswordField
          label={tr("密码", "Password")}
          value={password}
          onChange={(v) => {
            setPassword(v);
            setFailed(false);
          }}
          showPassword={showPassword}
          onToggle={() => setShowPassword((s) => !s)}
          isError={false}
          supportingText={tr("至少 1 个 Unicode 码点，长度不限", "At least 1 Unicode code point; no maximum")}
          autoComplete="new-password"
        />
        <PasswordField
          label={tr("确认密码", "Confirm password")}
          value={confirm}
          onChange={setConfirm}
          showPassword={showPassword}
          onToggle={() => setShowPassword((s) => !s)}
          isError={mismatch}
          supportingText={mismatch ? tr("两次输入不一致", "Passwords do not match") : undefined}
          autoComplete="new-password"
        />
        {failed && (
          <div role="alert" style={{ color: "var(--dc-error)", fontSize: "0.88em", textAlign: "center" }}>
            {tr("密码设置失败，加密配置未启用，请重试", "Password setup failed; encryption was not enabled. Please try again.")}
          </div>
        )}
        <button type="submit" className="dc-btn dc-btn-filled" disabled={!canSubmit} style={{ marginTop: 8, justifyContent: "center" }}>
          {submitting ? tr("正在设置…", "Setting up…") : tr("设置密码并启用", "Set password and enable")}
        </button>
      </form>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Locked screen (README §12 锁定页, title 收藏夹已锁定)
// ---------------------------------------------------------------------------

function VaultLockedScreen({
  onUnlocked,
  snackbar,
}: {
  onUnlocked: () => Promise<void>;
  snackbar: string | null;
}) {
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<"wrong" | "failed" | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    if (!password || submitting) return;
    setSubmitting(true);
    setError(null);
    const result = await vaultPost("/api/vault/unlock", { password });
    setSubmitting(false);
    if (result.ok) {
      setPassword("");
      await onUnlocked();
    } else if (result.code === "wrong_password") {
      setError("wrong");
    } else {
      setError("failed");
    }
  };

  return (
    <div className="dc-page dc-col dc-center" style={{ minHeight: "60vh", padding: 24 }}>
      <Snackbar message={snackbar} />
      <form
        className="dc-col"
        style={{ width: "min(380px, 100%)", gap: 12 }}
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <Unlock size={40} style={{ color: "var(--dc-primary)", alignSelf: "center" }} />
        <div className="dc-title" style={{ textAlign: "center" }}>
          {tr("收藏夹已锁定", "Vault is locked")}
        </div>
        <div className="dc-muted" style={{ textAlign: "center", fontSize: "0.92em" }}>
          {tr("输入密码以查看加密内容", "Enter your password to view encrypted entries")}
        </div>
        <PasswordField
          label={tr("密码", "Password")}
          value={password}
          onChange={(v) => {
            setPassword(v);
            setError(null);
          }}
          showPassword={showPassword}
          onToggle={() => setShowPassword((s) => !s)}
          isError={error !== null}
          supportingText={
            error === "wrong" ? tr("密码错误", "Wrong password") : error === "failed" ? tr("操作失败", "Operation failed") : undefined
          }
          autoComplete="current-password"
        />
        <button type="submit" className="dc-btn dc-btn-filled" disabled={!password || submitting} style={{ justifyContent: "center", gap: 8 }}>
          <Unlock size={18} />
          {submitting ? tr("正在解锁…", "Unlocking…") : tr("解锁", "Unlock")}
        </button>
      </form>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Unlocked list
// ---------------------------------------------------------------------------

function VaultUnlockedScreen(props: {
  rowHeightDp: number;
  onLocked: () => Promise<void>;
  snackbar: string | null;
  showSnack: (m: string) => void;
}) {
  const { rowHeightDp, onLocked, snackbar, showSnack } = props;
  const operationFailedLabel = tr("操作失败", "Operation failed");
  const copiedLabel = tr("已复制", "Copied");

  const [items, setItems] = useState<VaultItemDto[] | null>(null);
  const [corruptedItemCount, setCorruptedItemCount] = useState(0);
  const [listError, setListError] = useState<LoadError | null>(null);

  const [editorItem, setEditorItem] = useState<VaultItemDto | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  const [changePasswordOpen, setChangePasswordOpen] = useState(false);

  const menuStateRef = useRef<{ x: number; y: number; item: VaultItemDto } | null>(null);
  const [menu, setMenu] = useState<{ x: number; y: number; item: VaultItemDto } | null>(null);

  const reloadItems = useCallback(async () => {
    setListError(null);
    try {
      const data = await apiGet<VaultItemsResponse>("/api/vault/items");
      setItems(data.items ?? []);
      setCorruptedItemCount(typeof data.corruptedItemCount === "number" ? data.corruptedItemCount : 0);
    } catch (error) {
      if (error instanceof ApiClientError && error.status === 403) {
        // Server dropped the unlocked session (e.g. restart): fall back to the lock screen.
        await onLocked();
        return;
      }
      setListError({ message: error instanceof Error ? error.message : String(error) });
    }
  }, [onLocked]);

  useEffect(() => {
    void reloadItems();
  }, [reloadItems]);

  const lockVault = useCallback(async () => {
    try {
      await apiSend("/api/vault/lock", "POST", {});
    } catch {
      /* locking always returns to the locked screen regardless */
    }
    await onLocked();
  }, [onLocked]);

  const copyContent = useCallback(
    async (content: string) => {
      try {
        await navigator.clipboard.writeText(content);
        showSnack(copiedLabel);
      } catch {
        showSnack(tr("复制失败", "Could not copy"));
      }
    },
    [copiedLabel, showSnack],
  );

  /** Reorder by moving one id; posts the complete ordered list like Android. */
  const moveItem = useCallback(
    async (item: VaultItemDto, direction: -1 | 1) => {
      if (!items) return;
      const index = items.findIndex((it) => it.id === item.id);
      const target = index + direction;
      if (index < 0 || target < 0 || target >= items.length) return;
      const next = items.slice();
      const [moved] = next.splice(index, 1);
      next.splice(target, 0, moved);
      setItems(next);
      try {
        const data = await apiSend<VaultItemsResponse>("/api/vault/items/reorder", "POST", next.map((it) => ({ id: it.id })));
        setItems(data.items ?? next);
      } catch {
        showSnack(operationFailedLabel);
        void reloadItems();
      }
    },
    [items, operationFailedLabel, reloadItems, showSnack],
  );

  // README §12: 删除没有二次确认，点击即删 — the red 删除 deletes immediately.
  const deleteItem = useCallback(async (target: VaultItemDto) => {
    try {
      await apiSend(`/api/vault/items/${target.id}`, "DELETE");
      showSnack(tr("已删除", "Deleted"));
    } catch {
      showSnack(operationFailedLabel);
    }
    void reloadItems();
  }, [operationFailedLabel, reloadItems, showSnack]);

  const sorted = items ?? [];
  const hasItems = sorted.length > 0;

  return (
    <div className="dc-page" style={{ paddingBottom: 96 }}>
      <Snackbar message={snackbar} />
      <TopBar
        title={tr("收藏夹", "Vault")}
        actions={
          <>
            <button
              className="dc-icon-btn"
              aria-label={tr("修改密码", "Change password")}
              title={tr("修改密码", "Change password")}
              onClick={() => setChangePasswordOpen(true)}
            >
              <KeyRound size={20} />
            </button>
            <button className="dc-icon-btn" aria-label={tr("锁定", "Lock")} title={tr("锁定", "Lock")} onClick={() => void lockVault()}>
              <Lock size={20} />
            </button>
          </>
        }
      />

      {items === null ? (
        listError ? (
          <div role="alert" className="dc-col dc-center" style={{ padding: 32 }}>
            <ErrorText error={listError.message} />
            <button className="dc-btn dc-btn-tonal" onClick={() => void reloadItems()}>
              {tr("重试", "Retry")}
            </button>
          </div>
        ) : (
          <Spinner />
        )
      ) : !hasItems ? (
        <EmptyState
          icon={<Archive size={44} />}
          title={tr("收藏夹还是空的", "Vault is empty")}
          hint={tr(
            "在这里保存的内容都会加密存储，只有解锁后才能查看",
            "Everything saved here is stored encrypted and readable only after unlocking",
          )}
        />
      ) : (
        <div className="dc-col" style={{ gap: 4, padding: "8px 0" }}>
          {corruptedItemCount > 0 && (
            <div className="dc-card dc-row" role="alert" style={{ padding: "12px 16px", gap: 12, alignItems: "flex-start" }}>
              <span style={{ color: "var(--dc-error)" }}>⚠</span>
              <span className="dc-muted">
                {tr(
                  `有 ${corruptedItemCount} 条加密内容无法读取；原始数据已保留，未显示任何内容片段。`,
                  corruptedItemCount === 1
                    ? "1 encrypted entry is unreadable. The original data was kept and no content fragment is shown."
                    : `${corruptedItemCount} encrypted entries are unreadable. The original data was kept and no content fragment is shown.`,
                )}
              </span>
            </div>
          )}
          {sorted.map((item, index) => {
            const link = safeVaultHttpUrl(item.content);
            return (
              <div
                key={item.id}
                className="dc-card dc-row"
                role="button"
                tabIndex={0}
                style={{
                  padding: "10px 12px",
                  gap: 6,
                  alignItems: "stretch",
                  minHeight: rowHeightDp,
                  cursor: "pointer",
                }}
                onClick={() => {
                  if (link) window.open(link, "_blank", "noopener,noreferrer");
                  else void copyContent(item.content);
                }}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    if (link) window.open(link, "_blank", "noopener,noreferrer");
                    else void copyContent(item.content);
                  }
                }}
              >
                <div className="dc-grow" style={{ minWidth: 0, display: "flex", flexDirection: "column", justifyContent: "center" }}>
                  <div
                    style={{
                      color: link ? "var(--dc-primary)" : "var(--dc-on-surface)",
                      textDecoration: link ? "underline" : undefined,
                      whiteSpace: "pre-wrap",
                      display: "-webkit-box",
                      WebkitLineClamp: 4,
                      WebkitBoxOrient: "vertical",
                      overflow: "hidden",
                      wordBreak: "break-word",
                    }}
                  >
                    {item.content.trim() ? item.content : tr("（空内容）", "(Empty content)")}
                  </div>
                  {item.note && item.note.trim() !== "" && (
                    <div
                      className="dc-muted"
                      style={{
                        marginTop: 4,
                        fontSize: "0.85em",
                        whiteSpace: "pre-wrap",
                        display: "-webkit-box",
                        WebkitLineClamp: 2,
                        WebkitBoxOrient: "vertical",
                        overflow: "hidden",
                        wordBreak: "break-word",
                      }}
                    >
                      {item.note}
                    </div>
                  )}
                </div>
                <div className="dc-col" style={{ justifyContent: "space-between", alignItems: "center", gap: 2 }}>
                  <button
                    className="dc-icon-btn"
                    aria-label={tr("更多操作", "More actions")}
                    onClick={(e) => {
                      e.stopPropagation();
                      menuStateRef.current = { x: e.clientX, y: e.clientY, item };
                      setMenu(menuStateRef.current);
                    }}
                  >
                    <MoreVertical size={18} />
                  </button>
                  <div className="dc-col" style={{ gap: 0 }}>
                    <button
                      className="dc-icon-btn"
                      aria-label={tr("上移", "Move up")}
                      disabled={index === 0}
                      style={{ opacity: index === 0 ? 0.35 : 1 }}
                      onClick={(e) => {
                        e.stopPropagation();
                        void moveItem(item, -1);
                      }}
                    >
                      <ArrowUp size={16} />
                    </button>
                    <button
                      className="dc-icon-btn"
                      aria-label={tr("下移", "Move down")}
                      disabled={index === sorted.length - 1}
                      style={{ opacity: index === sorted.length - 1 ? 0.35 : 1 }}
                      onClick={(e) => {
                        e.stopPropagation();
                        void moveItem(item, 1);
                      }}
                    >
                      <ArrowDown size={16} />
                    </button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      <PopupMenu
        open={menu !== null}
        onClose={() => setMenu(null)}
        x={menu?.x ?? 0}
        y={menu?.y ?? 0}
        items={[
          {
            label: tr("复制内容", "Copy content"),
            onClick: () => {
              if (menu) void copyContent(menu.item.content);
            },
          },
          {
            label: tr("编辑条目", "Edit entry"),
            onClick: () => {
              if (menu) {
                setEditorItem(menu.item);
                setEditorOpen(true);
              }
            },
          },
          {
            label: tr("删除条目", "Delete entry"),
            danger: true,
            onClick: () => {
              if (menu) void deleteItem(menu.item);
            },
          },
        ]}
      />

      {/* FAB: 新增条目 */}
      <button
        className="dc-fab"
        aria-label={tr("新增条目", "Add entry")}
        onClick={() => {
          setEditorItem(null);
          setEditorOpen(true);
        }}
      >
        <Plus size={24} />
      </button>

      <VaultItemEditorDialog
        open={editorOpen}
        item={editorItem}
        onClose={() => setEditorOpen(false)}
        onSaved={() => {
          setEditorOpen(false);
          void reloadItems();
        }}
        onDeleted={() => {
          setEditorOpen(false);
          void reloadItems();
        }}
        onCopy={copyContent}
        onError={() => showSnack(operationFailedLabel)}
      />

      <VaultChangePasswordDialog open={changePasswordOpen} onClose={() => setChangePasswordOpen(false)} onError={() => showSnack(operationFailedLabel)} />

      <PageTutorialOverlay
        pageKey="vault"
        title={tr("收藏夹", "Vault")}
        lines={[tr("内容加密保存；删除没有二次确认，点击即删。", "Entries are stored encrypted; deletion has no second confirmation — tapping deletes immediately.")]}
      />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Item editor dialog (新增条目 / 编辑条目)
// ---------------------------------------------------------------------------

function VaultItemEditorDialog(props: {
  open: boolean;
  item: VaultItemDto | null;
  onClose: () => void;
  onSaved: () => void;
  onDeleted: () => void;
  onCopy: (content: string) => void;
  onError: () => void;
}) {
  const { open, item, onClose, onSaved, onDeleted, onCopy, onError } = props;
  const editing = item !== null;
  const [content, setContent] = useState("");
  const [note, setNote] = useState("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (open) {
      setContent(item?.content ?? "");
      setNote(item?.note ?? "");
      setSaving(false);
    }
  }, [open, item]);

  if (!open) return null;

  const canSave = content.trim().length > 0 && !saving;

  const save = async () => {
    if (!canSave) return;
    setSaving(true);
    const body = { content, note: note.trim() !== "" ? note : null };
    try {
      if (editing && item) await apiSend(`/api/vault/items/${item.id}`, "PUT", body);
      else await apiSend("/api/vault/items", "POST", body);
      onSaved();
    } catch {
      onError();
    } finally {
      setSaving(false);
    }
  };

  // README §12: 删除没有二次确认，点击即删.
  const remove = async () => {
    if (!item) return;
    try {
      await apiSend(`/api/vault/items/${item.id}`, "DELETE");
    } catch {
      /* deletion failures surface via the reloaded list */
    }
    onDeleted();
  };

  return (
    <>
      <Modal open={open} onClose={onClose} title={editing ? tr("编辑条目", "Edit entry") : tr("新增条目", "Add entry")} width={480}>
        <div className="dc-col" style={{ gap: 10 }}>
          <label className="dc-col" style={{ gap: 4 }}>
            <span className="dc-muted" style={{ fontSize: "0.85em" }}>{tr("内容", "Content")}</span>
            <textarea
              className="dc-input"
              value={content}
              onChange={(e) => setContent(e.target.value)}
              rows={4}
              style={{ resize: "vertical", minHeight: "5.5em", lineHeight: 1.5 }}
              autoFocus
            />
          </label>
          <label className="dc-col" style={{ gap: 4 }}>
            <span className="dc-muted" style={{ fontSize: "0.85em" }}>{tr("备注（可选）", "Note (optional)")}</span>
            <input className="dc-input" value={note} onChange={(e) => setNote(e.target.value)} />
          </label>
          <div className="dc-row" style={{ justifyContent: "space-between", marginTop: 6 }}>
            {editing && item ? (
              <div className="dc-row" style={{ gap: 8 }}>
                <button className="dc-btn" onClick={() => onCopy(item.content)}>
                  {tr("复制", "Copy")}
                </button>
                <button className="dc-btn" style={{ color: "var(--dc-error)" }} onClick={() => void remove()}>
                  {tr("删除", "Delete")}
                </button>
              </div>
            ) : (
              <span />
            )}
            <div className="dc-row" style={{ gap: 8 }}>
              <button className="dc-btn" onClick={onClose}>
                {tr("取消", "Cancel")}
              </button>
              <button className="dc-btn dc-btn-filled" disabled={!canSave} onClick={() => void save()}>
                {saving ? tr("正在保存…", "Saving…") : tr("保存", "Save")}
              </button>
            </div>
          </div>
        </div>
      </Modal>
    </>
  );
}

// ---------------------------------------------------------------------------
// Change password dialog (修改密码)
// ---------------------------------------------------------------------------

function VaultChangePasswordDialog(props: { open: boolean; onClose: () => void; onError: () => void }) {
  const { open, onClose, onError } = props;
  const [oldPassword, setOldPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmNew, setConfirmNew] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [oldWrong, setOldWrong] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (open) {
      setOldPassword("");
      setNewPassword("");
      setConfirmNew("");
      setShowPassword(false);
      setOldWrong(false);
      setSubmitting(false);
    }
  }, [open]);

  if (!open) return null;

  const mismatch = confirmNew.length > 0 && confirmNew !== newPassword;
  const canSubmit =
    oldPassword.length > 0 && codePointLength(newPassword) >= 1 && !mismatch && !submitting;

  const submit = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    setOldWrong(false);
    const result = await vaultPost("/api/vault/change-password", { password: oldPassword, newPassword });
    setSubmitting(false);
    if (result.ok) {
      onClose();
    } else if (result.code === "wrong_password") {
      setOldWrong(true);
    } else {
      onError();
    }
  };

  return (
    <Modal open={open} onClose={onClose} title={tr("修改密码", "Change password")} width={440}>
      <div className="dc-col" style={{ gap: 10 }}>
        <div className="dc-muted" style={{ fontSize: "0.88em" }}>
          {tr(
            "所有条目将使用新密码重新加密。新密码丢失后同样无法找回。",
            "All entries will be re-encrypted with the new password. A lost password still cannot be recovered.",
          )}
        </div>
        <PasswordField
          label={tr("旧密码", "Old password")}
          value={oldPassword}
          onChange={(v) => {
            setOldPassword(v);
            setOldWrong(false);
          }}
          showPassword={showPassword}
          onToggle={() => setShowPassword((s) => !s)}
          isError={oldWrong}
          supportingText={oldWrong ? tr("旧密码错误", "Wrong old password") : undefined}
          autoComplete="current-password"
        />
        <PasswordField
          label={tr("新密码", "New password")}
          value={newPassword}
          onChange={setNewPassword}
          showPassword={showPassword}
          onToggle={() => setShowPassword((s) => !s)}
          isError={false}
          supportingText={tr("至少 1 个 Unicode 码点，长度不限", "At least 1 Unicode code point; no maximum")}
          autoComplete="new-password"
        />
        <PasswordField
          label={tr("确认新密码", "Confirm new password")}
          value={confirmNew}
          onChange={setConfirmNew}
          showPassword={showPassword}
          onToggle={() => setShowPassword((s) => !s)}
          isError={mismatch}
          supportingText={mismatch ? tr("两次输入不一致", "Passwords do not match") : undefined}
          autoComplete="new-password"
        />
        <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 6 }}>
          <button className="dc-btn" onClick={onClose}>
            {tr("取消", "Cancel")}
          </button>
          <button className="dc-btn dc-btn-filled" disabled={!canSubmit} onClick={() => void submit()}>
            {submitting ? tr("正在修改…", "Updating…") : tr("确认修改", "Confirm")}
          </button>
        </div>
      </div>
    </Modal>
  );
}

// ---------------------------------------------------------------------------
// Shared password field with page-level eye toggle
// ---------------------------------------------------------------------------

function PasswordField(props: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  showPassword: boolean;
  onToggle: () => void;
  isError?: boolean;
  supportingText?: string;
  autoComplete?: string;
}) {
  return (
    <label className="dc-col" style={{ gap: 4 }}>
      <span className="dc-muted" style={{ fontSize: "0.85em" }}>{props.label}</span>
      <span className="dc-row" style={{ gap: 4 }}>
        <input
          className="dc-input dc-grow"
          type={props.showPassword ? "text" : "password"}
          value={props.value}
          onChange={(e) => props.onChange(e.target.value)}
          autoComplete={props.autoComplete}
          aria-invalid={props.isError ?? false}
          style={props.isError ? { borderColor: "var(--dc-error)" } : undefined}
        />
        <button
          type="button"
          className="dc-icon-btn"
          aria-label={props.showPassword ? tr("隐藏密码", "Hide password") : tr("显示密码", "Show password")}
          onClick={props.onToggle}
        >
          {props.showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
        </button>
      </span>
      {props.supportingText && (
        <span style={{ fontSize: "0.82em", color: props.isError ? "var(--dc-error)" : "var(--dc-on-surface-variant)" }}>
          {props.supportingText}
        </span>
      )}
    </label>
  );
}
