import * as Dialog from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import type { ReactNode } from "react";
import { translate, type MessageKey } from "../i18n";
import { useAppStore } from "../store/appStore";

interface ConfirmDialogProps {
  open: boolean;
  title: ReactNode;
  description?: ReactNode;
  confirmLabel?: ReactNode;
  cancelLabel?: ReactNode;
  destructive?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  cancelLabel,
  destructive = false,
  busy = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const language = useAppStore((state) => state.appearance.language);
  const tr = (key: MessageKey) => translate(language, key);

  return (
    <Dialog.Root
      open={open}
      onOpenChange={(nextOpen) => {
        if (!nextOpen && !busy) onCancel();
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content
          className="dialog-content"
          onEscapeKeyDown={(event) => {
            if (busy) event.preventDefault();
          }}
          onPointerDownOutside={(event) => {
            if (busy) event.preventDefault();
          }}
        >
          <Dialog.Title className="dialog-title">{title}</Dialog.Title>
          {description ? (
            <Dialog.Description className="dialog-description">
              {description}
            </Dialog.Description>
          ) : null}
          <div className="dialog-actions">
            <button
              className="button button-ghost"
              type="button"
              disabled={busy}
              onClick={onCancel}
            >
              {cancelLabel ?? tr("action.cancel")}
            </button>
            <button
              className={`button ${destructive ? "button-danger" : "button-primary"}`}
              type="button"
              disabled={busy}
              onClick={onConfirm}
            >
              {confirmLabel ?? tr("action.confirm")}
            </button>
          </div>
          <Dialog.Close
            className="icon-button dialog-close"
            aria-label={tr("action.close")}
            disabled={busy}
          >
            <X aria-hidden="true" size={18} />
          </Dialog.Close>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
