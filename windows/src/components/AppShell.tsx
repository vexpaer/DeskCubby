import {
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Menu,
  Settings,
  X,
} from "lucide-react";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { NavLink, useLocation, useNavigate } from "react-router-dom";
import { translate, type MessageKey } from "../i18n";
import { subscribeUpdateAvailable } from "../lib/updateApi";
import { useAppStore } from "../store/appStore";
import {
  buildDesktopNavigationSections,
  desktopNavigationCategoryName,
  desktopNavigationItemForPath,
  firstVisibleNavigationPath,
} from "./desktopNavigation";
import { ToastViewport } from "./ToastViewport";

const brandIcon = new URL("../assets/deskcubby.png", import.meta.url).href;

const pathTitles: Array<[string, MessageKey]> = [
  ["/settings/health", "nav.health"],
  ["/settings/rss", "nav.rss"],
  ["/settings/ai", "nav.ai"],
  ["/settings/data/sync", "nav.cloud"],
  ["/settings/cloud", "nav.cloud"],
  ["/settings/about", "nav.about"],
  ["/settings/updates", "nav.about"],
  ["/settings", "nav.settings"],
  ["/statistics", "nav.statistics"],
  ["/thoughts", "nav.thoughts"],
  ["/poetry", "nav.poetry"],
  ["/reader", "nav.reader"],
  ["/health", "nav.health"],
  ["/diary", "nav.diary"],
  ["/meals", "nav.meals"],
  ["/daily", "nav.daily"],
  ["/notes", "nav.notes"],
  ["/dates", "nav.dates"],
  ["/games", "nav.games"],
  ["/rss", "nav.rss"],
  ["/ai", "nav.ai"],
  ["/vault", "nav.vault"],
  ["/usage", "nav.usage"],
  ["/more", "nav.more"],
  ["/backup", "nav.backup"],
  ["/", "nav.home"],
];

function Brand() {
  const language = useAppStore((state) => state.appearance.language);
  return (
    <NavLink className="brand" to="/" aria-label={translate(language, "app.name")}>
      <span className="brand-mark" aria-hidden="true">
        <img src={brandIcon} alt="" />
      </span>
      <span className="brand-copy">
        <strong>{translate(language, "app.name")}</strong>
        <small>{translate(language, "app.tagline")}</small>
      </span>
    </NavLink>
  );
}

function Navigation({ railCollapsed }: { railCollapsed: boolean }) {
  const language = useAppStore((state) => state.appearance.language);
  const preferences = useAppStore((state) => state.desktopNavigation);
  const collapsedCategoryIds = useAppStore(
    (state) => state.collapsedNavigationCategoryIds,
  );
  const toggleNavigationCategory = useAppStore(
    (state) => state.toggleNavigationCategory,
  );
  const setMobileNavigationOpen = useAppStore(
    (state) => state.setMobileNavigationOpen,
  );
  const sections = useMemo(
    () => buildDesktopNavigationSections(preferences),
    [preferences],
  );

  return (
    <nav className="sidebar-navigation" aria-label={translate(language, "nav.sidebar")}>
      {sections.map((section) => {
        if (section.items.length === 0) return null;
        const name = desktopNavigationCategoryName(section, language);
        const categoryCollapsed =
          !railCollapsed && collapsedCategoryIds.includes(section.id);
        return (
          <section
            className="navigation-section"
            key={section.id}
            aria-label={name}
          >
            <h2>
              <button
                className="navigation-section-toggle"
                type="button"
                aria-expanded={!categoryCollapsed}
                aria-controls={`navigation-category-${section.id}`}
                aria-label={`${
                  language === "en"
                    ? categoryCollapsed
                      ? "Expand category"
                      : "Collapse category"
                    : categoryCollapsed
                      ? "展开分类"
                      : "收起分类"
                }${language === "en" ? ": " : "："}${name}`}
                onClick={() => toggleNavigationCategory(section.id)}
              >
                <span>{name}</span>
                {categoryCollapsed ? (
                  <ChevronRight aria-hidden="true" size={14} />
                ) : (
                  <ChevronDown aria-hidden="true" size={14} />
                )}
              </button>
            </h2>
            <span className="navigation-divider" aria-hidden="true" />
            <div
              className="navigation-items"
              id={`navigation-category-${section.id}`}
              hidden={categoryCollapsed}
            >
              {section.items.map(({ id, to, label, icon: Icon, end }) => (
                <NavLink
                  className={({ isActive }) =>
                    `navigation-link ${isActive ? "is-active" : ""}`
                  }
                  data-page={id}
                  to={to}
                  end={end}
                  key={id}
                  title={railCollapsed ? translate(language, label) : undefined}
                  aria-label={translate(language, label)}
                  onClick={() => setMobileNavigationOpen(false)}
                >
                  <Icon aria-hidden="true" size={20} strokeWidth={1.9} />
                  <span className="navigation-link-label">
                    {translate(language, label)}
                  </span>
                </NavLink>
              ))}
            </div>
          </section>
        );
      })}
    </nav>
  );
}

function useNarrowSidebar(): boolean {
  const query = "(max-width: 820px)";
  const [narrow, setNarrow] = useState(
    () => typeof window !== "undefined" && window.matchMedia(query).matches,
  );
  useEffect(() => {
    const media = window.matchMedia(query);
    const handleChange = (event: MediaQueryListEvent) => setNarrow(event.matches);
    setNarrow(media.matches);
    media.addEventListener("change", handleChange);
    return () => media.removeEventListener("change", handleChange);
  }, []);
  return narrow;
}

export function AppShell({ children }: { children: ReactNode }) {
  const sidebarRef = useRef<HTMLElement>(null);
  const mobileMenuButtonRef = useRef<HTMLButtonElement>(null);
  const location = useLocation();
  const navigate = useNavigate();
  const language = useAppStore((state) => state.appearance.language);
  const collapsed = useAppStore((state) => state.sidebarCollapsed);
  const desktopNavigation = useAppStore((state) => state.desktopNavigation);
  const mobileOpen = useAppStore((state) => state.mobileNavigationOpen);
  const toggleSidebar = useAppStore((state) => state.toggleSidebar);
  const addToast = useAppStore((state) => state.addToast);
  const setMobileNavigationOpen = useAppStore(
    (state) => state.setMobileNavigationOpen,
  );
  const narrowSidebar = useNarrowSidebar();
  const previousNavigationRef = useRef(desktopNavigation);
  const currentTitle =
    pathTitles.find(([path]) =>
      path === "/" ? location.pathname === "/" : location.pathname.startsWith(path),
    )?.[1] ?? "app.name";

  const closeMobileNavigation = useCallback(
    (restoreFocus = false) => {
      setMobileNavigationOpen(false);
      if (restoreFocus) {
        window.requestAnimationFrame(() => mobileMenuButtonRef.current?.focus());
      }
    },
    [setMobileNavigationOpen],
  );

  useEffect(() => {
    setMobileNavigationOpen(false);
  }, [location.pathname, setMobileNavigationOpen]);

  useEffect(() => {
    if (previousNavigationRef.current === desktopNavigation) return;
    previousNavigationRef.current = desktopNavigation;
    const currentItem = desktopNavigationItemForPath(location.pathname);
    if (currentItem && desktopNavigation.hiddenItemIds.includes(currentItem.id)) {
      navigate(firstVisibleNavigationPath(desktopNavigation), { replace: true });
    }
  }, [desktopNavigation, location.pathname, navigate]);

  useEffect(() => {
    if (!mobileOpen) return;
    const sidebar = sidebarRef.current;
    if (!sidebar) return;
    const focusableSelector =
      'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
    const getFocusable = () =>
      Array.from(sidebar.querySelectorAll<HTMLElement>(focusableSelector)).filter(
        (element) => {
          if (element.closest('[hidden], [inert], [aria-hidden="true"]')) {
            return false;
          }
          const style = window.getComputedStyle(element);
          return style.display !== "none" && style.visibility !== "hidden";
        },
      );
    getFocusable()[0]?.focus();

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        closeMobileNavigation(true);
        return;
      }
      const focusable = getFocusable();
      if (event.key !== "Tab" || focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [closeMobileNavigation, mobileOpen]);

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
      <aside
        className="sidebar"
        id="desktop-navigation"
        ref={sidebarRef}
        role={mobileOpen ? "dialog" : undefined}
        aria-modal={mobileOpen ? true : undefined}
        aria-label={translate(language, "nav.sidebar")}
      >
        <div className="sidebar-top">
          <Brand />
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
            onClick={() => closeMobileNavigation(true)}
          >
            <X aria-hidden="true" size={20} />
          </button>
        </div>
        <Navigation railCollapsed={collapsed && !narrowSidebar} />
        <div className="sidebar-bottom">
          <NavLink
            className={({ isActive }) =>
              `navigation-link ${isActive ? "is-active" : ""}`
            }
            to="/settings"
            title={collapsed ? translate(language, "nav.settings") : undefined}
            aria-label={translate(language, "nav.settings")}
            onClick={() => setMobileNavigationOpen(false)}
          >
            <Settings aria-hidden="true" size={20} strokeWidth={1.9} />
            <span className="navigation-link-label">
              {translate(language, "nav.settings")}
            </span>
          </NavLink>
          <small>{translate(language, "footer.localFirst")}</small>
        </div>
      </aside>

      <button
        className="navigation-scrim"
        type="button"
        aria-label={translate(language, "action.close")}
        tabIndex={mobileOpen ? 0 : -1}
        onClick={() => closeMobileNavigation(true)}
      />

      <div className="content-column">
        <header className="window-toolbar">
          <button
            ref={mobileMenuButtonRef}
            className="icon-button mobile-menu-button"
            type="button"
            aria-label={translate(language, "action.expand")}
            aria-controls="desktop-navigation"
            aria-expanded={mobileOpen}
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
