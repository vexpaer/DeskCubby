import {
  ArchiveRestore,
  BookHeart,
  CalendarDays,
  ChevronLeft,
  ChevronRight,
  Home,
  Hourglass,
  Lightbulb,
  LockKeyhole,
  Menu,
  NotebookPen,
  Settings,
  Soup,
  Sparkles,
  X,
  type LucideIcon,
} from "lucide-react";
import { useEffect, type ReactNode } from "react";
import { NavLink, useLocation } from "react-router-dom";
import { translate, type MessageKey } from "../i18n";
import { subscribeUpdateAvailable } from "../lib/updateApi";
import { useAppStore } from "../store/appStore";
import type { PageId } from "../types";
import { ToastViewport } from "./ToastViewport";

interface NavigationItem {
  id: PageId;
  to: string;
  label: MessageKey;
  icon: LucideIcon;
  end?: boolean;
}

interface NavigationSection {
  label: MessageKey;
  items: NavigationItem[];
}

const sections: NavigationSection[] = [
  {
    label: "nav.primary",
    items: [
      { id: "home", to: "/", label: "nav.home", icon: Home, end: true },
      { id: "diary", to: "/diary", label: "nav.diary", icon: NotebookPen },
      { id: "meals", to: "/meals", label: "nav.meals", icon: Soup },
      { id: "daily", to: "/daily", label: "nav.daily", icon: Sparkles },
    ],
  },
  {
    label: "nav.data",
    items: [
      {
        id: "thoughts",
        to: "/thoughts",
        label: "nav.thoughts",
        icon: Lightbulb,
      },
      {
        id: "dates",
        to: "/dates",
        label: "nav.dates",
        icon: CalendarDays,
      },
      {
        id: "poetry",
        to: "/poetry",
        label: "nav.poetry",
        icon: BookHeart,
      },
      {
        id: "vault",
        to: "/vault",
        label: "nav.vault",
        icon: LockKeyhole,
      },
      {
        id: "usage",
        to: "/usage",
        label: "nav.usage",
        icon: Hourglass,
      },
      {
        id: "backup",
        to: "/backup",
        label: "nav.backup",
        icon: ArchiveRestore,
      },
    ],
  },
];

const pathTitles: Array<[string, MessageKey]> = [
  ["/settings/data/sync", "nav.cloud"],
  ["/settings/cloud", "nav.cloud"],
  ["/settings/about", "nav.about"],
  ["/settings/updates", "nav.about"],
  ["/diary", "nav.diary"],
  ["/meals", "nav.meals"],
  ["/daily", "nav.daily"],
  ["/thoughts", "nav.thoughts"],
  ["/dates", "nav.dates"],
  ["/poetry", "nav.poetry"],
  ["/vault", "nav.vault"],
  ["/usage", "nav.usage"],
  ["/backup", "nav.backup"],
  ["/settings", "nav.settings"],
  ["/", "nav.home"],
];

function Brand({ collapsed }: { collapsed: boolean }) {
  const language = useAppStore((state) => state.appearance.language);
  return (
    <NavLink className="brand" to="/" aria-label={translate(language, "app.name")}>
      <span className="brand-mark" aria-hidden="true">
        <span />
      </span>
      {!collapsed ? (
        <span className="brand-copy">
          <strong>{translate(language, "app.name")}</strong>
          <small>{translate(language, "app.tagline")}</small>
        </span>
      ) : null}
    </NavLink>
  );
}

function Navigation({ collapsed }: { collapsed: boolean }) {
  const language = useAppStore((state) => state.appearance.language);
  const setMobileNavigationOpen = useAppStore(
    (state) => state.setMobileNavigationOpen,
  );

  return (
    <nav className="sidebar-navigation" aria-label={translate(language, "nav.primary")}>
      {sections.map((section) => (
        <section className="navigation-section" key={section.label}>
          {!collapsed ? (
            <h2>{translate(language, section.label)}</h2>
          ) : (
            <span className="navigation-divider" aria-hidden="true" />
          )}
          <div className="navigation-items">
            {section.items.map(({ id, to, label, icon: Icon, end }) => (
              <NavLink
                className={({ isActive }) =>
                  `navigation-link ${isActive ? "is-active" : ""}`
                }
                data-page={id}
                to={to}
                end={end}
                key={id}
                title={collapsed ? translate(language, label) : undefined}
                onClick={() => setMobileNavigationOpen(false)}
              >
                <Icon aria-hidden="true" size={20} strokeWidth={1.9} />
                {!collapsed ? <span>{translate(language, label)}</span> : null}
              </NavLink>
            ))}
          </div>
        </section>
      ))}
    </nav>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const location = useLocation();
  const language = useAppStore((state) => state.appearance.language);
  const collapsed = useAppStore((state) => state.sidebarCollapsed);
  const mobileOpen = useAppStore((state) => state.mobileNavigationOpen);
  const toggleSidebar = useAppStore((state) => state.toggleSidebar);
  const addToast = useAppStore((state) => state.addToast);
  const setMobileNavigationOpen = useAppStore(
    (state) => state.setMobileNavigationOpen,
  );
  const currentTitle =
    pathTitles.find(([path]) =>
      path === "/" ? location.pathname === "/" : location.pathname.startsWith(path),
    )?.[1] ?? "app.name";

  useEffect(() => {
    setMobileNavigationOpen(false);
  }, [location.pathname, setMobileNavigationOpen]);

  useEffect(() => {
    let active = true;
    let unlisten: (() => void) | undefined;
    void subscribeUpdateAvailable((update) => {
      if (!active) return;
      addToast({
        kind: "info",
        title: translate(language, "update.availableTitle"),
        detail: translate(language, "update.availableDetail").replace(
          "{version}",
          update.version,
        ),
        action: {
          label: translate(language, "action.viewUpdate"),
          to: "/settings/about",
        },
        dedupeKey: `update:${update.version}`,
        persistent: true,
      });
    })
      .then((stop) => {
        if (active) unlisten = stop;
        else stop();
      })
      .catch(() => {
        // Update notifications are optional. A missing event bridge must not
        // affect local builds or the rest of the desktop shell.
      });
    return () => {
      active = false;
      unlisten?.();
    };
  }, [addToast, language]);

  return (
    <div
      className={`app-layout ${collapsed ? "sidebar-is-collapsed" : ""} ${
        mobileOpen ? "mobile-navigation-is-open" : ""
      }`}
    >
      <aside className="sidebar">
        <div className="sidebar-top">
          <Brand collapsed={collapsed} />
          <button
            className="icon-button desktop-sidebar-toggle"
            type="button"
            aria-label={translate(
              language,
              collapsed ? "action.expand" : "action.collapse",
            )}
            onClick={toggleSidebar}
          >
            {collapsed ? (
              <ChevronRight aria-hidden="true" size={18} />
            ) : (
              <ChevronLeft aria-hidden="true" size={18} />
            )}
          </button>
          <button
            className="icon-button mobile-sidebar-close"
            type="button"
            aria-label={translate(language, "action.close")}
            onClick={() => setMobileNavigationOpen(false)}
          >
            <X aria-hidden="true" size={20} />
          </button>
        </div>
        <Navigation collapsed={collapsed} />
        <div className="sidebar-bottom">
          <NavLink
            className={({ isActive }) =>
              `navigation-link ${isActive ? "is-active" : ""}`
            }
            to="/settings"
            title={collapsed ? translate(language, "nav.settings") : undefined}
          >
            <Settings aria-hidden="true" size={20} strokeWidth={1.9} />
            {!collapsed ? <span>{translate(language, "nav.settings")}</span> : null}
          </NavLink>
          {!collapsed ? (
            <small>{translate(language, "footer.localFirst")}</small>
          ) : null}
        </div>
      </aside>

      <button
        className="navigation-scrim"
        type="button"
        aria-label={translate(language, "action.close")}
        onClick={() => setMobileNavigationOpen(false)}
      />

      <div className="content-column">
        <header className="window-toolbar">
          <button
            className="icon-button mobile-menu-button"
            type="button"
            aria-label={translate(language, "action.expand")}
            onClick={() => setMobileNavigationOpen(true)}
          >
            <Menu aria-hidden="true" size={21} />
          </button>
          <strong>{translate(language, currentTitle)}</strong>
          <span className="window-drag-region" data-tauri-drag-region />
          <span className="local-first-badge">
            {translate(language, "status.localFirst")}
          </span>
        </header>
        <main className="app-content" id="main-content" tabIndex={-1}>
          {children}
        </main>
      </div>
      <ToastViewport />
    </div>
  );
}
