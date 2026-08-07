import { safeExternalHttpUrl } from "../lib/externalLinkApi";

export type NotePreviewPart =
  | { kind: "text"; markdown: string; key: string }
  | {
      kind: "image";
      target: string;
      caption: string;
      markdown: string;
      key: string;
    };

// Mirrors Android's shared Markdown preview parser. Images are separated from
// CommonMark text so Obsidian wiki embeds and ordinary Markdown images share
// the same bounded Rust resolver.
const NOTE_IMAGE_PATTERN =
  /!\[([^\]\r\n]*)\]\(\s*(?:<([^>\r\n]+)>|([^\s)\r\n]+))(?:\s+(?:"[^"\r\n]*"|'[^'\r\n]*'|\([^)\r\n]*\)))?\s*\)|!\[\[([^\]|\r\n]+)(?:\|([^\]\r\n]+))?\]\]/g;

export function splitNotePreview(content: string): NotePreviewPart[] {
  const parts: NotePreviewPart[] = [];
  let cursor = 0;
  let index = 0;
  for (const match of content.matchAll(NOTE_IMAGE_PATTERN)) {
    const start = match.index ?? 0;
    if (start > cursor) {
      parts.push({
        kind: "text",
        markdown: content.slice(cursor, start),
        key: `text-${index++}-${start}`,
      });
    }
    const standardTarget = match[2] || match[3];
    const target = (standardTarget || match[4] || "").trim();
    const caption = standardTarget
      ? match[1] || ""
      : match[5] || target.split("/").at(-1)?.replace(/\.[^.]+$/, "") || "";
    parts.push({
      kind: "image",
      target,
      caption,
      markdown: match[0],
      key: `image-${index++}-${start}`,
    });
    cursor = start + match[0].length;
  }
  if (cursor < content.length) {
    parts.push({
      kind: "text",
      markdown: content.slice(cursor),
      key: `text-${index}-${cursor}`,
    });
  }
  if (!parts.length && content) {
    parts.push({ kind: "text", markdown: content, key: "text-0" });
  }
  return parts;
}

export function safeNoteLinkTransform(url: string): string {
  return safeExternalHttpUrl(url) ?? "";
}
