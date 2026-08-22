/** Shared UI components: TopBar, dialogs, menu, empty state, tutorial overlay. */
import React, { createContext, useContext, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";
import { tr } from "../i18n/tr";

export function TopBar(props: {
  title: React.ReactNode;
  actions?: React.ReactNode;
  onBack?: () => void;
  back?: boolean;
  subtitle?: React.ReactNode;
}) {
  return (
    <div className="dc-row" style={{ padding: "10px 4px", position: "sticky", top: 0, zIndex: 40, background: "color-mix(in srgb, var(--dc-background) 88%, transparent)", backdropFilter: "blur(10px)" }}>
      {props.back && props.onBack && (
        <button className="dc-icon-btn" aria-label={tr("返回", "Back")} onClick={props.onBack}>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M19 12H5M12 19l-7-7 7-7" /></svg>
        </button>
      )}
      <div className="dc-grow" style={{ minWidth: 0 }}>
        <div className="dc-title" style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{props.title}</div>
        {props.subtitle && <div className="dc-muted" style={{ fontSize: "0.85em" }}>{props.subtitle}</div>}
      </div>
      <div className="dc-row">{props.actions}</div>
    </div>
  );
}

export function ConfirmDialog(props: {
  open: boolean;
  title: React.ReactNode;
  message?: React.ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  danger?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
  children?: React.ReactNode;
}) {
  if (!props.open) return null;
  return (
    <div className="dc-dialog-overlay" onClick={props.onCancel}>
      <div className="dc-dialog" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        <div className="dc-title" style={{ marginBottom: 8 }}>{props.title}</div>
        {props.message && <div className="dc-muted" style={{ marginBottom: 12, whiteSpace: "pre-wrap" }}>{props.message}</div>}
        {props.children}
        <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 16 }}>
          <button className="dc-btn" onClick={props.onCancel}>{props.cancelLabel ?? tr("取消", "Cancel")}</button>
          <button className={`dc-btn ${props.danger ? "dc-btn-danger" : "dc-btn-filled"}`} onClick={props.onConfirm}>
            {props.confirmLabel ?? tr("确定", "OK")}
          </button>
        </div>
      </div>
    </div>
  );
}

export function PopupMenu(props: { open: boolean; onClose: () => void; x: number; y: number; items: { label: React.ReactNode; onClick: () => void; danger?: boolean }[] }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!props.open) return;
    const close = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) props.onClose();
    };
    window.addEventListener("mousedown", close);
    return () => window.removeEventListener("mousedown", close);
  }, [props.open]);
  if (!props.open) return null;
  const x = Math.min(props.x, window.innerWidth - 200);
  const y = Math.min(props.y, window.innerHeight - props.items.length * 44 - 20);
  return createPortal(
    <div ref={ref} className="dc-menu" style={{ left: x, top: y }}>
      {props.items.map((it, i) => (
        <button key={i} style={it.danger ? { color: "var(--dc-error)" } : undefined} onClick={() => { props.onClose(); it.onClick(); }}>
          {it.label}
        </button>
      ))}
    </div>,
    document.body
  );
}

export function EmptyState(props: { icon?: React.ReactNode; title: React.ReactNode; hint?: React.ReactNode }) {
  return (
    <div className="dc-col dc-center" style={{ padding: "48px 16px", textAlign: "center", color: "var(--dc-on-surface-variant)" }}>
      {props.icon}
      <div style={{ fontSize: "1.1em", marginTop: 12 }}>{props.title}</div>
      {props.hint && <div style={{ marginTop: 6, fontSize: "0.9em" }}>{props.hint}</div>}
    </div>
  );
}

export function Spinner({ size = 28 }: { size?: number }) {
  return (
    <div className="dc-center" style={{ padding: 24 }}>
      <div style={{
        width: size, height: size, borderRadius: "50%",
        border: "3px solid var(--dc-outline-variant)", borderTopColor: "var(--dc-primary)",
        animation: "dc-spin 0.9s linear infinite",
      }} />
      <style>{`@keyframes dc-spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}

export function ErrorText({ error }: { error: unknown }) {
  if (!error) return null;
  const msg = error instanceof Error ? error.message : String(error);
  return <div style={{ color: "var(--dc-error)", fontSize: "0.9em", margin: "6px 0" }}>{msg}</div>;
}

export function Snackbar({ message }: { message: string | null }) {
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    if (message) {
      setVisible(true);
      const t = setTimeout(() => setVisible(false), 2600);
      return () => clearTimeout(t);
    }
  }, [message]);
  if (!message || !visible) return null;
  return createPortal(
    <div style={{
      position: "fixed", left: "50%", transform: "translateX(-50%)",
      bottom: "calc(var(--dc-bottom-nav-height) + 24px)", zIndex: 400,
      background: "var(--dc-inverse-surface)", color: "var(--dc-inverse-on-surface)",
      padding: "10px 18px", borderRadius: 10, boxShadow: "0 6px 18px rgba(0,0,0,0.3)", maxWidth: "90vw",
    }}>
      {message}
    </div>,
    document.body
  );
}

/** useSnackbar: returns [message, show] where show auto-clears. */
export function useSnackbar(): [string | null, (m: string) => void] {
  const [message, setMessage] = useState<string | null>(null);
  const show = (m: string) => setMessage(m);
  return [message, show];
}

/** Dirty-page guard: confirms navigation away when draft is unsaved. */
export function useDirtyGuard(dirty: boolean, message?: string): void {
  const msg = message ?? tr("有未保存的修改，确定离开？", "You have unsaved changes. Leave anyway?");
  useEffect(() => {
    if (!dirty) return;
    const handler = (e: BeforeUnloadEvent) => {
      e.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);
}

export function Modal(props: { open: boolean; onClose: () => void; title: React.ReactNode; width?: number; children: React.ReactNode }) {
  if (!props.open) return null;
  return (
    <div className="dc-dialog-overlay" onClick={props.onClose}>
      <div className="dc-dialog" style={props.width ? { width: `min(${props.width}px, 94vw)` } : undefined} role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        <div className="dc-row" style={{ marginBottom: 12 }}>
          <div className="dc-title dc-grow">{props.title}</div>
          <button className="dc-icon-btn" aria-label={tr("关闭", "Close")} onClick={props.onClose}><X size={20} /></button>
        </div>
        {props.children}
      </div>
    </div>
  );
}

const TutorialContext = createContext<{ enabled: boolean; acknowledged: string[]; ack: (page: string) => void }>({
  enabled: false, acknowledged: [], ack: () => {},
});

export function TutorialProvider(props: { enabled: boolean; acknowledged: string[]; ack: (page: string) => void; children: React.ReactNode }) {
  return <TutorialContext.Provider value={{ enabled: props.enabled, acknowledged: props.acknowledged, ack: props.ack }}>{props.children}</TutorialContext.Provider>;
}

/** Per-page tutorial overlay, shown once per page key while tutorial mode is on. */
export function PageTutorialOverlay(props: { pageKey: string; title: React.ReactNode; lines: React.ReactNode[] }) {
  const ctx = useContext(TutorialContext);
  const [dismissed, setDismissed] = useState(false);
  const active = ctx.enabled && !ctx.acknowledged.includes(props.pageKey) && !dismissed;
  if (!active) return null;
  return (
    <div className="dc-dialog-overlay" style={{ zIndex: 500 }}>
      <div className="dc-dialog" role="dialog" aria-modal="true">
        <div className="dc-title" style={{ marginBottom: 10 }}>{props.title}</div>
        <ul style={{ paddingLeft: 20, lineHeight: 1.8 }}>
          {props.lines.map((l, i) => <li key={i}>{l}</li>)}
        </ul>
        <div className="dc-row" style={{ justifyContent: "flex-end", marginTop: 14 }}>
          <button className="dc-btn dc-btn-filled" onClick={() => { setDismissed(true); ctx.ack(props.pageKey); }}>
            {tr("知道了", "Got it")}
          </button>
        </div>
      </div>
    </div>
  );
}
