import { invoke } from "@tauri-apps/api/core";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { openExternalHttpUrl, safeExternalHttpUrl } from "./externalLinkApi";

describe("external Markdown links", () => {
  const invokeMock = vi.mocked(invoke);

  beforeEach(() => invokeMock.mockReset());

  it("keeps only bounded credential-free HTTP(S) URLs", () => {
    expect(safeExternalHttpUrl("https://example.test/docs")).toBe("https://example.test/docs");
    expect(safeExternalHttpUrl("http://example.test")).toBe("http://example.test");
    expect(safeExternalHttpUrl("mailto:user@example.test")).toBeNull();
    expect(safeExternalHttpUrl("https://user@example.test")).toBeNull();
    expect(safeExternalHttpUrl("file:///C:/private.txt")).toBeNull();
    expect(safeExternalHttpUrl(" https://example.test")).toBeNull();
  });

  it("opens accepted URLs only through the versioned Rust command", async () => {
    invokeMock.mockResolvedValue(undefined as never);
    await openExternalHttpUrl("https://example.test/docs");
    expect(invokeMock).toHaveBeenCalledWith("open_external_link", {
      request: { schemaVersion: 1, url: "https://example.test/docs" },
    });
  });
});
