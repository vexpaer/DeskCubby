import { invokeCommand } from "./ipc";

export const EXTERNAL_LINK_DTO_VERSION = 1 as const;

// Keep the renderer's policy aligned with Rust. The command performs the
// authoritative validation again before it asks Windows to open a browser.
export function safeExternalHttpUrl(value: string): string | null {
  if (
    value.length === 0 ||
    value.length > 8_192 ||
    value !== value.trim() ||
    [...value].some((character) => {
      const codePoint = character.codePointAt(0) ?? 0;
      return codePoint <= 0x1f || codePoint === 0x7f;
    })
  ) {
    return null;
  }
  try {
    const parsed = new URL(value);
    if (
      (parsed.protocol !== "http:" && parsed.protocol !== "https:") ||
      !parsed.hostname ||
      parsed.username ||
      parsed.password
    ) {
      return null;
    }
    return value;
  } catch {
    return null;
  }
}

export function openExternalHttpUrl(url: string): Promise<void> {
  return invokeCommand("open_external_link", {
    request: { schemaVersion: EXTERNAL_LINK_DTO_VERSION, url },
  });
}
