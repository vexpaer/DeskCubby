import type { ReactNode } from "react";

interface PageFrameProps {
  eyebrow?: ReactNode;
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}

export function PageFrame({
  eyebrow,
  title,
  description,
  actions,
  children,
  className = "",
}: PageFrameProps) {
  return (
    <section className={`page-shell ${className}`.trim()}>
      <header className="page-header">
        <div className="page-heading">
          {eyebrow ? <span className="page-eyebrow">{eyebrow}</span> : null}
          <h1>{title}</h1>
          {description ? <p>{description}</p> : null}
        </div>
        {actions ? <div className="page-actions">{actions}</div> : null}
      </header>
      {children}
    </section>
  );
}
