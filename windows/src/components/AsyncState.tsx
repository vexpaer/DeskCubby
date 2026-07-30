import {
  CircleAlert,
  Inbox,
  LoaderCircle,
  RefreshCw,
  type LucideIcon,
} from "lucide-react";
import type { ReactNode } from "react";
import { translate } from "../i18n";
import { useAppStore } from "../store/appStore";

interface StatePanelProps {
  title?: string;
  description?: string;
  icon?: LucideIcon;
  action?: ReactNode;
  compact?: boolean;
}

function StatePanel({
  title,
  description,
  icon: Icon = Inbox,
  action,
  compact = false,
}: StatePanelProps) {
  return (
    <div
      className={`state-panel ${compact ? "state-panel-compact" : ""}`}
      role="status"
    >
      <span className="state-panel-icon" aria-hidden="true">
        <Icon size={compact ? 22 : 30} />
      </span>
      {title ? <h2>{title}</h2> : null}
      {description ? <p>{description}</p> : null}
      {action}
    </div>
  );
}

export function LoadingState({
  label,
  compact,
}: {
  label?: string;
  compact?: boolean;
}) {
  const language = useAppStore((state) => state.appearance.language);
  return (
    <StatePanel
      title={label ?? translate(language, "status.loading")}
      icon={LoaderCircle}
      compact={compact}
    />
  );
}

export function EmptyState({
  title,
  description,
  action,
  icon,
  compact,
}: StatePanelProps) {
  const language = useAppStore((state) => state.appearance.language);
  return (
    <StatePanel
      title={title ?? translate(language, "status.empty")}
      description={description}
      action={action}
      icon={icon}
      compact={compact}
    />
  );
}

export function ErrorState({
  title,
  description,
  retry,
  compact,
}: {
  title?: string;
  description?: string;
  retry?: () => void;
  compact?: boolean;
}) {
  const language = useAppStore((state) => state.appearance.language);
  return (
    <StatePanel
      title={title ?? translate(language, "status.error")}
      description={description}
      icon={CircleAlert}
      compact={compact}
      action={
        retry ? (
          <button className="button button-secondary" type="button" onClick={retry}>
            <RefreshCw aria-hidden="true" size={17} />
            {translate(language, "action.retry")}
          </button>
        ) : null
      }
    />
  );
}

export function InlineLoading({ label }: { label?: string }) {
  const language = useAppStore((state) => state.appearance.language);
  return (
    <span className="inline-loading" role="status">
      <LoaderCircle aria-hidden="true" size={16} />
      {label ?? translate(language, "status.loading")}
    </span>
  );
}
