import { describe, expect, it } from "vitest";
import {
  assertIpcProtocolCompatible,
  compareDecimalI64,
  dateFromI64Milliseconds,
  DeskCubbyIpcError,
  IPC_SCHEMA_VERSION,
  readableError,
  verifyIpcProtocol,
} from "./ipc";

describe("lossless i64 DTO helpers", () => {
  it("orders decimal values beyond Number.MAX_SAFE_INTEGER with BigInt", () => {
    expect(compareDecimalI64("9007199254740993", "9007199254740992")).toBe(1);
    expect(compareDecimalI64("9223372036854775806", "9223372036854775807")).toBe(-1);
    expect(compareDecimalI64("-9223372036854775808", "0")).toBe(-1);
  });

  it("converts only valid in-range millisecond strings for display", () => {
    expect(dateFromI64Milliseconds("1785254400000")?.toISOString()).toBe(
      "2026-07-28T16:00:00.000Z",
    );
    expect(dateFromI64Milliseconds("9223372036854775807")).toBeNull();
    expect(dateFromI64Milliseconds("not-an-i64")).toBeNull();
  });
});

describe("IPC protocol compatibility", () => {
  it("accepts a backend range that includes this frontend schema", () => {
    const protocol = {
      schemaVersion: IPC_SCHEMA_VERSION,
      minimumSupportedVersion: IPC_SCHEMA_VERSION,
      appVersion: "0.1.0",
    };
    expect(assertIpcProtocolCompatible(protocol)).toBe(protocol);
  });

  it("rejects a backend range that excludes this frontend schema", () => {
    expect(() =>
      assertIpcProtocolCompatible({
        schemaVersion: 3,
        minimumSupportedVersion: 3,
        appVersion: "0.3.0",
      }),
    ).toThrowError(
      expect.objectContaining({
        code: "ipc_protocol_incompatible",
      }),
    );
  });

  it("uses a compatible protocol in tests without a privileged bridge", async () => {
    delete (
      window as Window & { __TAURI_INTERNALS__?: unknown }
    ).__TAURI_INTERNALS__;
    await expect(verifyIpcProtocol()).resolves.toEqual(
      expect.objectContaining({
        schemaVersion: IPC_SCHEMA_VERSION,
        minimumSupportedVersion: IPC_SCHEMA_VERSION,
      }),
    );
  });
});

describe("safe IPC error translations", () => {
  it.each([
    ["external_edit_conflict", "重新加载", "Reload"],
    ["backup_invalid", "备份文件无效", "backup is invalid"],
    ["backup_too_large", "64 MiB", "64 MiB"],
    ["compatibility_shadow_corrupt", "兼容备份数据", "Compatibility backup data"],
    ["ipc_protocol_incompatible", "版本不兼容", "incompatible"],
    ["authentication_failed", "身份验证失败", "authentication failed"],
    ["invalid_configuration", "云同步配置无效", "cloud sync configuration is invalid"],
    ["unsupported_remote", "安全同步", "safe sync"],
    ["vault_wrong_password", "密码不正确", "password is incorrect"],
    ["vault_session_changed", "重新解锁", "Unlock it again"],
    ["usage_statistics_invalid", "Android v4", "Android v4"],
    ["usage_statistics_source_missing", "源文件已丢失", "source is missing"],
    ["update_not_configured", "可信更新源", "trusted update endpoint"],
    ["update_version_changed", "版本已变化", "version changed"],
  ])("translates %s without exposing backend details", (code, zh, en) => {
    const error = new DeskCubbyIpcError(code, "private backend detail");
    expect(readableError(error, "zh-CN")).toContain(zh);
    expect(readableError(error, "en")).toContain(en);
    expect(readableError(error, "en")).not.toContain("private backend detail");
  });
});
