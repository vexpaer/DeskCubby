import {
  closestCenter,
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core";
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import * as Dialog from "@radix-ui/react-dialog";
import {
  ArrowDown,
  ArrowUp,
  Copy,
  ExternalLink,
  GripVertical,
  KeyRound,
  Lock,
  Pencil,
  Plus,
  RefreshCw,
  Save,
  ShieldCheck,
  Trash2,
  X,
} from "lucide-react";
import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type FormEvent,
} from "react";

import { ConfirmDialog, EmptyState, LoadingState, PageFrame } from "../components";
import {
  dateFromI64Milliseconds,
  DeskCubbyIpcError,
  readableError,
  tr,
} from "../lib/ipc";
import {
  vaultApi,
  type DecimalI64,
  type VaultItemDraftV1,
  type VaultItemV1,
  type VaultStatusV1,
} from "../lib/vaultApi";
import { useAppStore } from "../store/appStore";

function compareDecimal(left: string, right: string): number {
  try {
    const a = BigInt(left);
    const b = BigInt(right);
    return a < b ? -1 : a > b ? 1 : 0;
  } catch {
    return left.localeCompare(right);
  }
}

function isSafeWebLink(value: string): boolean {
  try {
    const candidate = value.trim();
    if (
      !candidate ||
      [...candidate].some((character) => /\s|\p{Cc}/u.test(character))
    ) {
      return false;
    }
    const parsed = new URL(candidate);
    return (
      (parsed.protocol === "http:" || parsed.protocol === "https:") &&
      !parsed.username &&
      !parsed.password &&
      !!parsed.hostname
    );
  } catch {
    return false;
  }
}

interface VaultItemCardProps {
  item: VaultItemV1;
  index: number;
  total: number;
  display: "note" | "date";
  language: "zh-CN" | "en";
  busy: boolean;
  reorderDisabled: boolean;
  onCopy: (item: VaultItemV1) => void;
  onOpen: (item: VaultItemV1) => void;
  onEdit: (item: VaultItemV1) => void;
  onDelete: (item: VaultItemV1) => void;
  onMove: (id: DecimalI64, direction: -1 | 1) => void;
}

function VaultItemCard({
  item,
  index,
  total,
  display,
  language,
  busy,
  reorderDisabled,
  onCopy,
  onOpen,
  onEdit,
  onDelete,
  onMove,
}: VaultItemCardProps) {
  const copy = useCallback(
    (zh: string, en: string) => tr(language, zh, en),
    [language],
  );
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: item.id, disabled: busy || reorderDisabled });
  const updated = dateFromI64Milliseconds(item.updatedAt);

  return (
    <li
      ref={setNodeRef}
      className={`card vault-item-card ${isDragging ? "is-dragging" : ""}`}
      style={{ transform: CSS.Transform.toString(transform), transition }}
    >
      <div className="vault-item-copy">
        <p>{item.content}</p>
        {display === "note" ? (
          item.note ? <small>{item.note}</small> : null
        ) : (
          <time dateTime={updated?.toISOString()}>
            {updated
              ? new Intl.DateTimeFormat(language === "en" ? "en-US" : "zh-CN", {
                  dateStyle: "medium",
                  timeStyle: "short",
                }).format(updated)
              : "—"}
          </time>
        )}
      </div>
      <div className="vault-item-actions">
        <button
          className="icon-button drag-handle"
          type="button"
          aria-label={copy("拖动排序", "Reorder item")}
          title={copy("拖动排序", "Reorder item")}
          disabled={busy || reorderDisabled}
          {...attributes}
          {...listeners}
        >
          <GripVertical aria-hidden="true" size={17} />
        </button>
        <button
          className="icon-button"
          type="button"
          aria-label={copy("上移", "Move up")}
          disabled={busy || reorderDisabled || index === 0}
          onClick={() => onMove(item.id, -1)}
        >
          <ArrowUp aria-hidden="true" size={16} />
        </button>
        <button
          className="icon-button"
          type="button"
          aria-label={copy("下移", "Move down")}
          disabled={busy || reorderDisabled || index === total - 1}
          onClick={() => onMove(item.id, 1)}
        >
          <ArrowDown aria-hidden="true" size={16} />
        </button>
        <button
          className="icon-button"
          type="button"
          aria-label={copy("复制正文", "Copy content")}
          disabled={busy}
          onClick={() => onCopy(item)}
        >
          <Copy aria-hidden="true" size={16} />
        </button>
        {item.primaryAction === "OPEN_URL" && isSafeWebLink(item.content) ? (
          <button
            className="icon-button"
            type="button"
            aria-label={copy("在系统浏览器打开", "Open in system browser")}
            disabled={busy}
            onClick={() => onOpen(item)}
          >
            <ExternalLink aria-hidden="true" size={16} />
          </button>
        ) : null}
        <button
          className="icon-button"
          type="button"
          aria-label={copy("编辑", "Edit")}
          disabled={busy}
          onClick={() => onEdit(item)}
        >
          <Pencil aria-hidden="true" size={16} />
        </button>
        <button
          className="icon-button danger"
          type="button"
          aria-label={copy("删除", "Delete")}
          disabled={busy}
          onClick={() => onDelete(item)}
        >
          <Trash2 aria-hidden="true" size={16} />
        </button>
      </div>
    </li>
  );
}

interface ItemDialogProps {
  open: boolean;
  item: VaultItemV1 | null;
  busy: boolean;
  language: "zh-CN" | "en";
  onClose: () => void;
  onSave: (draft: VaultItemDraftV1) => void;
}

function ItemDialog({
  open,
  item,
  busy,
  language,
  onClose,
  onSave,
}: ItemDialogProps) {
  const copy = useCallback(
    (zh: string, en: string) => tr(language, zh, en),
    [language],
  );
  const [content, setContent] = useState("");
  const [note, setNote] = useState("");

  useEffect(() => {
    if (!open) {
      setContent("");
      setNote("");
      return;
    }
    setContent(item?.content ?? "");
    setNote(item?.note ?? "");
  }, [item, open]);

  function submit(event: FormEvent) {
    event.preventDefault();
    const normalized = content.trim();
    if (!normalized) return;
    onSave({ content: normalized, note: note.trim() || null });
  }

  return (
    <Dialog.Root
      open={open}
      onOpenChange={(next) => {
        if (!next && !busy) onClose();
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="dialog-content vault-dialog">
          <Dialog.Title className="dialog-title">
            {item ? copy("编辑收藏", "Edit item") : copy("新增收藏", "New item")}
          </Dialog.Title>
          <Dialog.Description className="dialog-description">
            {copy(
              "正文必填，备注可选。内容只保存在本机加密收藏夹中。",
              "Content is required and the note is optional. Data stays in the encrypted local vault.",
            )}
          </Dialog.Description>
          <form className="stack" onSubmit={submit}>
            <label className="field">
              <span className="field-label">{copy("正文", "Content")}</span>
              <textarea
                autoFocus
                maxLength={65_536}
                rows={6}
                value={content}
                onChange={(event) => setContent(event.target.value)}
              />
            </label>
            <label className="field">
              <span className="field-label">{copy("备注（可选）", "Note (optional)")}</span>
              <textarea
                maxLength={8_192}
                rows={3}
                value={note}
                onChange={(event) => setNote(event.target.value)}
              />
            </label>
            <div className="dialog-actions">
              <button
                className="button-ghost"
                type="button"
                disabled={busy}
                onClick={onClose}
              >
                {copy("取消", "Cancel")}
              </button>
              <button
                className="button-primary"
                type="submit"
                disabled={busy || !content.trim()}
              >
                <Save aria-hidden="true" size={16} />
                {busy ? copy("保存中…", "Saving…") : copy("保存", "Save")}
              </button>
            </div>
          </form>
          <Dialog.Close
            className="icon-button dialog-close"
            aria-label={copy("关闭", "Close")}
            disabled={busy}
          >
            <X aria-hidden="true" size={18} />
          </Dialog.Close>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

interface PasswordDialogProps {
  open: boolean;
  busy: boolean;
  language: "zh-CN" | "en";
  onClose: () => void;
  onSubmit: (currentPassword: string, newPassword: string) => Promise<void>;
}

function PasswordDialog({
  open,
  busy,
  language,
  onClose,
  onSubmit,
}: PasswordDialogProps) {
  const copy = useCallback(
    (zh: string, en: string) => tr(language, zh, en),
    [language],
  );
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const valid = [...newPassword].length > 0 && newPassword === confirmation;

  useEffect(() => {
    if (!open) {
      setCurrentPassword("");
      setNewPassword("");
      setConfirmation("");
    }
  }, [open]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!currentPassword || !valid) return;
    const current = currentPassword;
    const next = newPassword;
    setCurrentPassword("");
    setNewPassword("");
    setConfirmation("");
    await onSubmit(current, next);
  }

  return (
    <Dialog.Root open={open} onOpenChange={(next) => !next && !busy && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="dialog-content">
          <Dialog.Title className="dialog-title">
            {copy("修改收藏夹密码", "Change vault password")}
          </Dialog.Title>
          <Dialog.Description className="dialog-description">
            {copy(
              "修改会在 Rust 后端重新加密全部条目。密码无法找回。",
              "Rust re-encrypts every item. The password cannot be recovered.",
            )}
          </Dialog.Description>
          <form className="stack" onSubmit={(event) => void submit(event)}>
            <label className="field">
              <span className="field-label">{copy("当前密码", "Current password")}</span>
              <input
                autoFocus
                autoComplete="current-password"
                type="password"
                value={currentPassword}
                onChange={(event) => setCurrentPassword(event.target.value)}
              />
            </label>
            <label className="field">
              <span className="field-label">{copy("新密码", "New password")}</span>
              <input
                autoComplete="new-password"
                type="password"
                value={newPassword}
                onChange={(event) => setNewPassword(event.target.value)}
              />
            </label>
            <label className="field">
              <span className="field-label">{copy("确认新密码", "Confirm new password")}</span>
              <input
                autoComplete="new-password"
                type="password"
                value={confirmation}
                onChange={(event) => setConfirmation(event.target.value)}
              />
              {confirmation && newPassword !== confirmation ? (
                <small className="field-error">
                  {copy("两次输入不一致。", "Passwords do not match.")}
                </small>
              ) : null}
            </label>
            <div className="dialog-actions">
              <button className="button-ghost" type="button" disabled={busy} onClick={onClose}>
                {copy("取消", "Cancel")}
              </button>
              <button
                className="button-primary"
                type="submit"
                disabled={busy || !currentPassword || !valid}
              >
                <KeyRound aria-hidden="true" size={16} />
                {busy ? copy("重新加密中…", "Re-encrypting…") : copy("修改密码", "Change password")}
              </button>
            </div>
          </form>
          <Dialog.Close className="icon-button dialog-close" aria-label={copy("关闭", "Close")}>
            <X aria-hidden="true" size={18} />
          </Dialog.Close>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

export default function VaultPage() {
  const language = useAppStore((state) => state.appearance.language);
  const copy = useCallback(
    (zh: string, en: string) => tr(language, zh, en),
    [language],
  );
  const [status, setStatus] = useState<VaultStatusV1 | null>(null);
  const [items, setItems] = useState<VaultItemV1[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [editing, setEditing] = useState<VaultItemV1 | "new" | null>(null);
  const [deleteCandidate, setDeleteCandidate] = useState<VaultItemV1 | null>(null);
  const [passwordDialogOpen, setPasswordDialogOpen] = useState(false);
  const [display, setDisplay] = useState<"note" | "date">("note");
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const orderedItems = useMemo(
    () => [...items].sort((a, b) => compareDecimal(a.sortOrder, b.sortOrder)),
    [items],
  );

  const clearSensitiveState = useCallback(() => {
    setItems([]);
    setPassword("");
    setConfirmation("");
    setEditing(null);
    setDeleteCandidate(null);
    setPasswordDialogOpen(false);
  }, []);

  const handleVaultError = useCallback(
    (reason: unknown) => {
      if (
        reason instanceof DeskCubbyIpcError &&
        [
          "vault_locked",
          "vault_session_changed",
          "vault_metadata_corrupt",
          "vault_store_unavailable",
          "vault_operation_failed",
        ].includes(reason.code)
      ) {
        clearSensitiveState();
        setStatus((current) =>
          current
            ? { ...current, lockState: "LOCKED", corruptedItemCount: 0 }
            : current,
        );
      }
      setError(readableError(reason, language));
    },
    [clearSensitiveState, language],
  );

  const applyUnlockedStatus = useCallback(async (nextStatus: VaultStatusV1) => {
    setStatus(nextStatus);
    setItems(nextStatus.lockState === "UNLOCKED" ? await vaultApi.list() : []);
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const nextStatus = await vaultApi.status();
      setStatus(nextStatus);
      if (nextStatus.lockState === "UNLOCKED") {
        setItems(await vaultApi.list());
      } else {
        setItems([]);
      }
    } catch (reason) {
      handleVaultError(reason);
    } finally {
      setLoading(false);
    }
  }, [handleVaultError]);

  useEffect(() => {
    void load();
  }, [load]);

  async function submitPassword(event: FormEvent) {
    event.preventDefault();
    const configured = status?.lockState !== "NOT_SET";
    if (!status || !password || (!configured && password !== confirmation)) return;
    const submitted = password;
    setPassword("");
    setConfirmation("");
    setBusy(configured ? "unlock" : "setup");
    setError("");
    try {
      await applyUnlockedStatus(
        configured
          ? await vaultApi.unlock(submitted)
          : await vaultApi.setup(submitted),
      );
    } catch (reason) {
      handleVaultError(reason);
    } finally {
      setBusy("");
    }
  }

  async function lockVault() {
    setBusy("lock");
    try {
      const locked = await vaultApi.lock();
      clearSensitiveState();
      setStatus(locked);
      setNotice("");
    } catch (reason) {
      handleVaultError(reason);
    } finally {
      setBusy("");
    }
  }

  async function saveItem(draft: VaultItemDraftV1) {
    const target = editing;
    if (!target) return;
    setBusy("item");
    setError("");
    try {
      const saved =
        target === "new"
          ? await vaultApi.create(draft)
          : await vaultApi.update(target.id, draft);
      setItems((current) =>
        target === "new"
          ? [...current, saved]
          : current.map((item) => (item.id === saved.id ? saved : item)),
      );
      setEditing(null);
      setNotice(copy("收藏已保存。", "Vault item saved."));
    } catch (reason) {
      handleVaultError(reason);
    } finally {
      setBusy("");
    }
  }

  async function deleteItem() {
    if (!deleteCandidate) return;
    const id = deleteCandidate.id;
    setBusy("delete");
    try {
      await vaultApi.remove(id);
      setItems((current) => current.filter((item) => item.id !== id));
      setDeleteCandidate(null);
      setNotice(copy("收藏已删除。", "Vault item deleted."));
    } catch (reason) {
      handleVaultError(reason);
    } finally {
      setBusy("");
    }
  }

  async function persistOrder(next: VaultItemV1[]) {
    if ((status?.corruptedItemCount ?? 0) > 0) return;
    const previous = items;
    setItems(next);
    setBusy("reorder");
    try {
      const saved = await vaultApi.reorder(next.map((item) => item.id));
      setItems(saved);
    } catch (reason) {
      setItems(previous);
      handleVaultError(reason);
    } finally {
      setBusy("");
    }
  }

  function moveItem(id: DecimalI64, direction: -1 | 1) {
    const source = orderedItems.findIndex((item) => item.id === id);
    const target = source + direction;
    if (source < 0 || target < 0 || target >= orderedItems.length) return;
    void persistOrder(arrayMove(orderedItems, source, target));
  }

  function finishDrag(event: DragEndEvent) {
    if (!status || status.corruptedItemCount > 0) return;
    if (!event.over || event.active.id === event.over.id) return;
    const source = orderedItems.findIndex((item) => item.id === event.active.id);
    const target = orderedItems.findIndex((item) => item.id === event.over?.id);
    if (source >= 0 && target >= 0) {
      void persistOrder(arrayMove(orderedItems, source, target));
    }
  }

  async function itemAction(item: VaultItemV1, action: "copy" | "open") {
    setBusy(action);
    setError("");
    try {
      if (action === "copy") await vaultApi.copyItem(item.id);
      else await vaultApi.openItem(item.id);
      setNotice(
        action === "copy"
          ? copy("正文已复制。", "Content copied.")
          : copy("已交给系统浏览器打开。", "Opened in the system browser."),
      );
    } catch (reason) {
      handleVaultError(reason);
    } finally {
      setBusy("");
    }
  }

  async function changePassword(currentPassword: string, newPassword: string) {
    setBusy("password");
    setError("");
    try {
      await vaultApi.changePassword(currentPassword, newPassword);
      setPasswordDialogOpen(false);
      setNotice(copy("收藏夹密码已修改。", "Vault password changed."));
    } catch (reason) {
      handleVaultError(reason);
    } finally {
      setBusy("");
    }
  }

  if (loading && !status) {
    return (
      <PageFrame title={copy("收藏夹", "Vault")}>
        <div className="panel">
          <LoadingState label={copy("正在打开加密收藏夹", "Opening encrypted vault")} />
        </div>
      </PageFrame>
    );
  }

  if (!status) {
    return (
      <PageFrame
        title={copy("收藏夹", "Vault")}
        actions={
          <button className="button-secondary" type="button" onClick={() => void load()}>
            <RefreshCw aria-hidden="true" size={17} />
            {copy("重试", "Retry")}
          </button>
        }
      >
        <div className="inline-error" role="alert">{error}</div>
      </PageFrame>
    );
  }

  if (status.lockState !== "UNLOCKED") {
    const settingUp = status.lockState === "NOT_SET";
    const passwordsMatch = password === confirmation;
    return (
      <PageFrame
        className="vault-page"
        eyebrow={copy("本机加密 · 不进入应用 JSON", "Encrypted locally · Excluded from app JSON")}
        title={copy("收藏夹", "Vault")}
        description={copy(
          "密码和条目只用于当前解锁会话，前端不会持久保存。",
          "Passwords and items are never persisted by the frontend.",
        )}
      >
        {error ? <div className="inline-error" role="alert">{error}</div> : null}
        <section className="panel vault-gate" aria-labelledby="vault-gate-title">
          <span className="vault-lock-mark" aria-hidden="true">
            {settingUp ? <ShieldCheck /> : <Lock />}
          </span>
          <div>
            <h2 id="vault-gate-title">
              {settingUp ? copy("设置收藏夹密码", "Set a vault password") : copy("解锁收藏夹", "Unlock vault")}
            </h2>
            <p>
              {settingUp
                ? copy("密码至少包含一个 Unicode 字符，且无法找回。", "Use at least one Unicode character. There is no password recovery.")
                : copy("输入本机收藏夹密码。", "Enter the password for this local vault.")}
            </p>
          </div>
          <form className="vault-password-form" onSubmit={(event) => void submitPassword(event)}>
            <label className="field">
              <span className="field-label">{copy("密码", "Password")}</span>
              <input
                autoFocus
                autoComplete={settingUp ? "new-password" : "current-password"}
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
            </label>
            {settingUp ? (
              <label className="field">
                <span className="field-label">{copy("确认密码", "Confirm password")}</span>
                <input
                  autoComplete="new-password"
                  type="password"
                  value={confirmation}
                  onChange={(event) => setConfirmation(event.target.value)}
                />
                {confirmation && !passwordsMatch ? (
                  <small className="field-error">
                    {copy("两次输入不一致。", "Passwords do not match.")}
                  </small>
                ) : null}
              </label>
            ) : null}
            <button
              className="button-primary"
              type="submit"
              disabled={
                !!busy ||
                [...password].length === 0 ||
                (settingUp && !passwordsMatch)
              }
            >
              <KeyRound aria-hidden="true" size={17} />
              {busy
                ? copy("处理中…", "Working…")
                : settingUp
                  ? copy("创建并解锁", "Create and unlock")
                  : copy("解锁", "Unlock")}
            </button>
          </form>
        </section>
      </PageFrame>
    );
  }

  return (
    <PageFrame
      className="vault-page"
      eyebrow={copy("本机加密 · 已解锁", "Encrypted locally · Unlocked")}
      title={copy("收藏夹", "Vault")}
      description={copy(
        `${orderedItems.length} 条收藏；离开应用前可立即锁定。`,
        `${orderedItems.length} items. Lock the vault before leaving the app.`,
      )}
      actions={
        <>
          <button className="button-secondary" type="button" onClick={() => setPasswordDialogOpen(true)}>
            <KeyRound aria-hidden="true" size={16} />
            {copy("修改密码", "Change password")}
          </button>
          <button className="button-secondary" type="button" disabled={!!busy} onClick={() => void lockVault()}>
            <Lock aria-hidden="true" size={16} />
            {copy("锁定", "Lock")}
          </button>
          <button className="button-primary" type="button" disabled={!!busy} onClick={() => setEditing("new")}>
            <Plus aria-hidden="true" size={16} />
            {copy("新增", "New item")}
          </button>
        </>
      }
    >
      {error ? <div className="inline-error" role="alert">{error}</div> : null}
      {notice ? <div className="status-banner success" role="status">{notice}</div> : null}
      {status.corruptedItemCount > 0 ? (
        <div className="status-banner warning" role="status">
          {copy(
            `${status.corruptedItemCount} 条收藏无法解密，已跳过且未删除；修复数据前排序已停用。`,
            `${status.corruptedItemCount} items could not be decrypted. They were skipped, not deleted; reordering is disabled until the data is repaired.`,
          )}
        </div>
      ) : null}
      <div className="vault-toolbar">
        <div className="segmented" role="group" aria-label={copy("卡片附加信息", "Card details")}>
          <button
            type="button"
            className={display === "note" ? "selected" : undefined}
            aria-pressed={display === "note"}
            onClick={() => setDisplay("note")}
          >
            {copy("备注", "Notes")}
          </button>
          <button
            type="button"
            className={display === "date" ? "selected" : undefined}
            aria-pressed={display === "date"}
            onClick={() => setDisplay("date")}
          >
            {copy("日期", "Dates")}
          </button>
        </div>
      </div>
      {orderedItems.length === 0 ? (
        <div className="panel">
          <EmptyState
            title={copy("收藏夹还是空的", "Your vault is empty")}
            description={copy(
              "新增一条普通文字或安全的 HTTP(S) 链接。",
              "Add plain text or a safe HTTP(S) link.",
            )}
            icon={ShieldCheck}
            action={
              <button className="button-primary" type="button" onClick={() => setEditing("new")}>
                <Plus aria-hidden="true" size={16} />
                {copy("新增收藏", "New item")}
              </button>
            }
          />
        </div>
      ) : (
        <DndContext
          sensors={sensors}
          collisionDetection={closestCenter}
          onDragEnd={finishDrag}
          accessibility={{
            screenReaderInstructions: {
              draggable: copy(
                "按空格拾取，用方向键移动，再按空格放下；Escape 取消。",
                "Press space to pick up, use arrow keys to move, press space to drop, or Escape to cancel.",
              ),
            },
          }}
        >
          <SortableContext
            items={orderedItems.map((item) => item.id)}
            strategy={verticalListSortingStrategy}
          >
            <ol className="vault-item-list" aria-label={copy("收藏列表", "Vault items")}>
              {orderedItems.map((item, index) => (
                <VaultItemCard
                  key={item.id}
                  item={item}
                  index={index}
                  total={orderedItems.length}
                  display={display}
                  language={language}
                  busy={!!busy}
                  reorderDisabled={status.corruptedItemCount > 0}
                  onCopy={(target) => void itemAction(target, "copy")}
                  onOpen={(target) => void itemAction(target, "open")}
                  onEdit={setEditing}
                  onDelete={setDeleteCandidate}
                  onMove={moveItem}
                />
              ))}
            </ol>
          </SortableContext>
        </DndContext>
      )}

      <ItemDialog
        open={editing !== null}
        item={editing === "new" ? null : editing}
        busy={busy === "item"}
        language={language}
        onClose={() => setEditing(null)}
        onSave={(draft) => void saveItem(draft)}
      />
      <PasswordDialog
        open={passwordDialogOpen}
        busy={busy === "password"}
        language={language}
        onClose={() => setPasswordDialogOpen(false)}
        onSubmit={changePassword}
      />
      <ConfirmDialog
        open={deleteCandidate !== null}
        title={copy("删除这条收藏？", "Delete this vault item?")}
        description={copy("删除后无法恢复。", "This cannot be undone.")}
        confirmLabel={copy("删除", "Delete")}
        destructive
        busy={busy === "delete"}
        onCancel={() => setDeleteCandidate(null)}
        onConfirm={() => void deleteItem()}
      />
    </PageFrame>
  );
}
