import { Search, type LucideIcon } from "lucide-react";
import { useMemo, useState, type ReactNode } from "react";

import type { WindowsSettings } from "../../lib/ipc";
import {
  MAIN_DESTINATIONS,
  SETTINGS_DESTINATIONS,
  SUBPAGE_DESTINATIONS,
  destinationFor,
  type SettingsDestination,
  type SettingsPageId,
  type SettingsTranslator,
} from "./settingsRoutes";

function localizedTitle(entry: SettingsDestination, tr: SettingsTranslator): string {
  return tr(entry.chinese, entry.english);
}

function localizedDescription(
  entry: SettingsDestination,
  tr: SettingsTranslator,
): string {
  return tr(entry.chineseDescription, entry.englishDescription);
}

function SettingsMenuCard({
  entry,
  tr,
  onOpen,
  detail,
}: {
  entry: SettingsDestination;
  tr: SettingsTranslator;
  onOpen: (id: SettingsPageId) => void;
  detail?: string;
}) {
  const Icon = entry.icon;
  return (
    <button
      className="panel settings-menu-card"
      type="button"
      onClick={() => onOpen(entry.id)}
    >
      <span className="settings-menu-icon" aria-hidden="true">
        <Icon size={21} />
      </span>
      <span className="settings-menu-copy">
        <strong>{localizedTitle(entry, tr)}</strong>
        <small>{detail ?? localizedDescription(entry, tr)}</small>
      </span>
      <span className="settings-menu-chevron" aria-hidden="true">
        ›
      </span>
    </button>
  );
}
export function SettingsHome({
  settings,
  tr,
  onOpen,
}: {
  settings: WindowsSettings;
  tr: SettingsTranslator;
  onOpen: (id: SettingsPageId) => void;
}) {
  const [query, setQuery] = useState("");
  const normalizedQuery = query.trim().toLocaleLowerCase();
  const searchResults = useMemo(() => {
    if (!normalizedQuery) return [];
    return SETTINGS_DESTINATIONS.filter((entry) => {
      const searchable = [
        entry.chinese,
        entry.english,
        entry.chineseDescription,
        entry.englishDescription,
        entry.keywords,
      ]
        .join(" ")
        .toLocaleLowerCase();
      return searchable.includes(normalizedQuery);
    });
  }, [normalizedQuery]);

  const entries = normalizedQuery
    ? searchResults
    : MAIN_DESTINATIONS.map(destinationFor).filter(
        (entry): entry is SettingsDestination => entry !== undefined,
      );
  const appDataDetail = settings.backupDirectory
    ? tr(
        "自动备份目录已配置，可管理 JSON 与云端同步",
        "Backup folder configured; manage JSON and cloud sync",
      )
    : tr(
        "本地 JSON、自动备份与云端同步",
        "Local JSON, automatic backup and cloud sync",
      );

  return (
    <>
      <label className="settings-search search-field">
        <Search size={18} aria-hidden="true" />
        <span className="sr-only">{tr("搜索设置", "Search settings")}</span>
        <input
          type="search"
          value={query}
          placeholder={tr("搜索设置", "Search settings")}
          onChange={(event) => setQuery(event.target.value)}
        />
      </label>

      {normalizedQuery && entries.length === 0 ? (
        <div className="panel settings-search-empty" role="status">
          <Search size={23} aria-hidden="true" />
          <p>{tr("没有匹配的设置", "No matching settings")}</p>
        </div>
      ) : (
        <div className="settings-menu-grid">
          {entries.map((entry) => (
            <SettingsMenuCard
              entry={entry}
              key={entry.id}
              tr={tr}
              onOpen={onOpen}
              detail={entry.id === "app-data" ? appDataDetail : undefined}
            />
          ))}
        </div>
      )}
    </>
  );
}

export function SubpageSettingsHome({
  settings,
  tr,
  onOpen,
}: {
  settings: WindowsSettings;
  tr: SettingsTranslator;
  onOpen: (id: SettingsPageId) => void;
}) {
  const entries = SUBPAGE_DESTINATIONS.map(destinationFor).filter(
    (entry): entry is SettingsDestination => entry !== undefined,
  );
  return (
    <div className="settings-menu-grid">
      {entries.map((entry) => (
        <SettingsMenuCard
          entry={entry}
          key={entry.id}
          tr={tr}
          onOpen={onOpen}
          detail={
            entry.id === "diary-media" && settings.diaryDirectory
              ? tr("日记目录已配置", "Diary folder configured")
              : undefined
          }
        />
      ))}
    </div>
  );
}

export function SettingsInfoPage({
  icon: Icon,
  title,
  description,
  children,
}: {
  icon: LucideIcon;
  title: string;
  description: string;
  children?: ReactNode;
}) {
  return (
    <section className="panel settings-info-page">
      <span className="settings-info-icon" aria-hidden="true">
        <Icon size={25} />
      </span>
      <div>
        <h2>{title}</h2>
        <p>{description}</p>
        {children}
      </div>
    </section>
  );
}
