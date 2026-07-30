import { AlertTriangle, Check, Info, X, XCircle } from "lucide-react";
import { useEffect } from "react";
import { Link } from "react-router-dom";
import { translate } from "../i18n";
import { useAppStore } from "../store/appStore";

const icons = {
  success: Check,
  info: Info,
  warning: AlertTriangle,
  error: XCircle,
};

function AutoDismissToast({ id, createdAt }: { id: string; createdAt: number }) {
  const dismissToast = useAppStore((state) => state.dismissToast);

  useEffect(() => {
    const elapsed = Date.now() - createdAt;
    const timeout = window.setTimeout(
      () => dismissToast(id),
      Math.max(800, 5000 - elapsed),
    );
    return () => window.clearTimeout(timeout);
  }, [createdAt, dismissToast, id]);

  return null;
}

export function ToastViewport() {
  const language = useAppStore((state) => state.appearance.language);
  const toasts = useAppStore((state) => state.toasts);
  const dismissToast = useAppStore((state) => state.dismissToast);

  return (
    <div
      className="toast-viewport"
      aria-live="polite"
      aria-relevant="additions removals"
    >
      {toasts.map((toast) => {
        const Icon = icons[toast.kind];
        return (
          <article className={`toast toast-${toast.kind}`} key={toast.id}>
            {!toast.persistent ? (
              <AutoDismissToast id={toast.id} createdAt={toast.createdAt} />
            ) : null}
            <Icon className="toast-icon" aria-hidden="true" size={19} />
            <div className="toast-copy">
              <strong>{toast.title}</strong>
              {toast.detail ? <p>{toast.detail}</p> : null}
              {toast.action ? (
                <Link
                  className="toast-action"
                  to={toast.action.to}
                  onClick={() => dismissToast(toast.id)}
                >
                  {toast.action.label}
                </Link>
              ) : null}
            </div>
            <button
              className="icon-button toast-close"
              type="button"
              aria-label={translate(language, "action.close")}
              onClick={() => dismissToast(toast.id)}
            >
              <X aria-hidden="true" size={16} />
            </button>
          </article>
        );
      })}
    </div>
  );
}
