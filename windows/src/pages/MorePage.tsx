import { Settings } from "lucide-react";
import { Link } from "react-router-dom";

import {
  DESKTOP_NAVIGATION_SECTIONS,
  PageFrame,
} from "../components";
import { translate } from "../i18n";
import { useAppStore } from "../store/appStore";

export default function MorePage() {
  const language = useAppStore((state) => state.appearance.language);

  return (
    <PageFrame
      className="more-page"
      eyebrow={translate(language, "more.eyebrow")}
      title={translate(language, "more.title")}
      description={translate(language, "more.description")}
    >
      <div className="more-navigation-sections">
        {DESKTOP_NAVIGATION_SECTIONS.map((section) => {
          const items = section.items.filter((item) => item.id !== "more");
          if (items.length === 0) return null;
          return (
            <section className="more-navigation-section" key={section.label}>
              <h2>{translate(language, section.label)}</h2>
              <div className="settings-menu-grid">
                {items.map(({ id, to, label, icon: Icon }) => (
                  <Link className="panel settings-menu-card" key={id} to={to}>
                    <span className="settings-menu-icon" aria-hidden="true">
                      <Icon size={21} />
                    </span>
                    <span className="settings-menu-copy">
                      <strong>{translate(language, label)}</strong>
                      <small>{translate(language, section.label)}</small>
                    </span>
                    <span className="settings-menu-chevron" aria-hidden="true">
                      ›
                    </span>
                  </Link>
                ))}
              </div>
            </section>
          );
        })}

        <section className="more-navigation-section">
          <h2>{translate(language, "nav.settings")}</h2>
          <div className="settings-menu-grid">
            <Link className="panel settings-menu-card" to="/settings">
              <span className="settings-menu-icon" aria-hidden="true">
                <Settings size={21} />
              </span>
              <span className="settings-menu-copy">
                <strong>{translate(language, "nav.settings")}</strong>
                <small>{translate(language, "footer.localFirst")}</small>
              </span>
              <span className="settings-menu-chevron" aria-hidden="true">
                ›
              </span>
            </Link>
          </div>
        </section>
      </div>
    </PageFrame>
  );
}
