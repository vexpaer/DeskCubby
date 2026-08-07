import type { ReactNode } from "react";
import { tr, type Language } from "../lib/ipc";
import { openExternalHttpUrl, safeExternalHttpUrl } from "../lib/externalLinkApi";

interface ExternalMarkdownLinkProps {
  href?: string;
  children: ReactNode;
  language: Language;
  onOpenError: (message: string) => void;
}

// Markdown is user-controlled text. Use a button, rather than an anchor, so
// the main WebView never navigates to a Markdown-provided URL (including via a
// middle click); Rust validates and launches approved URLs externally instead.
export function ExternalMarkdownLink({
  href,
  children,
  language,
  onOpenError,
}: ExternalMarkdownLinkProps) {
  const url = safeExternalHttpUrl(href ?? "");
  if (!url) return <>{children}</>;

  return (
    <button
      className="markdown-external-link"
      type="button"
      title={tr(language, "在系统浏览器中打开链接", "Open link in your browser")}
      onClick={() => {
        void openExternalHttpUrl(url).catch(() => {
          onOpenError(
            tr(language, "无法在系统浏览器中打开链接。", "The link could not be opened in your browser."),
          );
        });
      }}
    >
      {children}
    </button>
  );
}
