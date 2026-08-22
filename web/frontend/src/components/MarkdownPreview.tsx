/** Markdown preview with per-heading font sizes from settings (H1–H6), CommonMark. */
import React from "react";
import ReactMarkdown from "react-markdown";
import { useSettings } from "../stores/settings";

export function MarkdownPreview(props: { content: string; className?: string; style?: React.CSSProperties }) {
  const settings = useSettings((s) => s.settings);
  const sizes = settings?.markdownHeadingSizesSp ?? [32, 28, 24, 21, 19, 17];
  const base = settings?.fontScale ?? 1;
  const h = (level: number): React.CSSProperties => ({
    fontSize: `${(sizes[level - 1] ?? 17) * base / 16}em`,
  });
  return (
    <div className={`dc-markdown ${props.className ?? ""}`} style={props.style}>
      <ReactMarkdown
        components={{
          h1: ({ children }) => <h1 style={h(1)}>{children}</h1>,
          h2: ({ children }) => <h2 style={h(2)}>{children}</h2>,
          h3: ({ children }) => <h3 style={h(3)}>{children}</h3>,
          h4: ({ children }) => <h4 style={h(4)}>{children}</h4>,
          h5: ({ children }) => <h5 style={h(5)}>{children}</h5>,
          h6: ({ children }) => <h6 style={h(6)}>{children}</h6>,
          a: ({ href, children }) => <a href={href} target="_blank" rel="noreferrer">{children}</a>,
        }}
      >
        {props.content}
      </ReactMarkdown>
    </div>
  );
}
