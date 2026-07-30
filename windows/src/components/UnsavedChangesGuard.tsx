import { useBeforeUnload, useBlocker } from "react-router-dom";
import { useCallback, useEffect } from "react";
import { translate } from "../i18n";
import { useAppStore } from "../store/appStore";
import { ConfirmDialog } from "./ConfirmDialog";

interface UnsavedChangesGuardProps {
  when: boolean;
  scope?: string;
  title?: string;
  description?: string;
  onDiscard?: () => void;
}

export function UnsavedChangesGuard({
  when,
  scope,
  title,
  description,
  onDiscard,
}: UnsavedChangesGuardProps) {
  const language = useAppStore((state) => state.appearance.language);
  const markDirty = useAppStore((state) => state.markDirty);
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      when &&
      `${currentLocation.pathname}${currentLocation.search}` !==
        `${nextLocation.pathname}${nextLocation.search}`,
  );

  useEffect(() => {
    if (!scope) return;
    markDirty(scope, when);
    return () => markDirty(scope, false);
  }, [markDirty, scope, when]);

  useBeforeUnload(
    useCallback(
      (event) => {
        if (!when) return;
        event.preventDefault();
        event.returnValue = "";
      },
      [when],
    ),
  );

  return (
    <ConfirmDialog
      open={blocker.state === "blocked"}
      title={title ?? translate(language, "leave.title")}
      description={description ?? translate(language, "leave.description")}
      confirmLabel={translate(language, "action.discard")}
      destructive
      onCancel={() => blocker.reset?.()}
      onConfirm={() => {
        onDiscard?.();
        if (scope) markDirty(scope, false);
        blocker.proceed?.();
      }}
    />
  );
}
