"""Backup round-trip + boundary tests (v34 codec, import commit, widgets,
cloudsync redaction, auto backups) over the throwaway data dir /tmp/dc-r3b."""
from __future__ import annotations

import base64
import json
import os
import shutil

# The data dir must be configured before any app module import reads it.
os.environ["DESKCUBBY_DATA_DIR"] = "/tmp/dc-r3b"
shutil.rmtree("/tmp/dc-r3b", ignore_errors=True)

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402

NOW_MS = 1_700_000_000_000
FINGERPRINT = "a" * 64
USAGE_DEVICE_ID = "00000000-0000-4000-8000-000000000001"


@pytest.fixture(scope="module")
def client():
    with TestClient(app) as test_client:
        yield test_client


def _db(client):
    return client.app.state.db


def _seed_core_rows(client):
    """Insert one representative row set mirroring Room entities."""
    con = _db(client)
    with con:
        con.execute(
            "INSERT INTO thought_categories(id, name, colorArgb, sortOrder, createdAt, updatedAt) "
            "VALUES(1, '灵感', -1, 0, ?, ?)", (NOW_MS, NOW_MS))
        con.execute(
            "INSERT INTO flash_thoughts(id, content, createdAt, updatedAt, pinned, deletedAt, "
            "sortOrder, categoryId, highlighted) VALUES(1, '第一条', ?, ?, 1, NULL, 0, 1, 0)",
            (NOW_MS, NOW_MS))
        con.execute(
            "INSERT INTO flash_thoughts(id, content, createdAt, updatedAt, pinned, deletedAt, "
            "sortOrder, categoryId, highlighted) VALUES(2, '第二条', ?, ?, 0, NULL, 1, 1, 1)",
            (NOW_MS + 1, NOW_MS + 10))
        con.execute(
            "INSERT INTO browser_records(url, title, lastVisitedAt, visitCount, favorite) "
            "VALUES('https://example.com/a', 'Example A', ?, 3, 1)", (NOW_MS,))
        con.execute(
            "INSERT INTO browser_records(url, title, lastVisitedAt, visitCount, favorite) "
            "VALUES('https://example.com/history', 'History only', ?, 1, 0)", (NOW_MS,))
        con.execute(
            "INSERT INTO date_records(id, name, icon, dateIso, createdAt, updatedAt) "
            "VALUES(1, '纪念日', 'event', '2024-05-01', ?, ?)", (NOW_MS, NOW_MS))
        con.execute(
            "INSERT INTO poetry_categories(id, name, colorArgb, sortOrder, createdAt, updatedAt) "
            "VALUES(1, '唐诗', -256, 0, ?, ?)", (NOW_MS, NOW_MS))
        con.execute(
            "INSERT INTO saved_poems(id, content, source, createdAt, updatedAt, sortOrder, categoryId) "
            "VALUES(1, '床前明月光', '静夜思', ?, ?, 0, 1)", (NOW_MS, NOW_MS))
        con.execute(
            "INSERT INTO game_states(gameId, highScore, saveJson, updatedAt) "
            "VALUES('2048', 4096, '{\"board\":[2]}', ?)", (NOW_MS,))
        con.execute(
            "INSERT INTO game_statistics(gameId, metricKey, value, updatedAt) "
            "VALUES('2048', 'wins', 7, ?)", (NOW_MS,))
        # Vault: one real item (the key-marker row id is excluded from counts).
        con.execute(
            "INSERT INTO vault_items(id, cipherText, iv, createdAt, updatedAt, sortOrder) "
            "VALUES(1, ?, ?, ?, ?, 0)",
            (base64.b64encode(bytes(range(32))).decode(), base64.b64encode(bytes(12)).decode(),
             NOW_MS, NOW_MS))
        # Usage: one device with two days of app usage.
        con.execute(
            "INSERT INTO usage_devices(deviceId, deviceName, isLocal, updatedAt) VALUES(?, '手机', 0, ?)",
            (USAGE_DEVICE_ID, NOW_MS))
        for day in ("2024-01-01", "2024-01-02"):
            con.execute(
                "INSERT INTO usage_events_daily(deviceId, dayIso, packageName, appName, firstSeen, lastSeen, totalTimeMs) "
                "VALUES(?, ?, 'com.example.app', 'Example', ?, ?, 60000)",
                (USAGE_DEVICE_ID, day, NOW_MS, NOW_MS))
        # Agent chats without syncIds — export backfills them like Android.
        cursor = con.execute(
            "INSERT INTO ai_conversations(title, modelConfigId, createdAt, updatedAt, deletedAt) "
            "VALUES('对话一', 'cfg1', ?, ?, NULL)", (NOW_MS, NOW_MS))
        conv_id = cursor.lastrowid
        msg_cursor = con.execute(
            "INSERT INTO ai_messages(conversationId, role, content, reasoning, imageUri, imageMimeType, "
            "imagePermissionOwned, createdAt) VALUES(?, 'user', '你好', '', NULL, NULL, 0, ?)",
            (conv_id, NOW_MS))
        msg_id = msg_cursor.lastrowid
        con.execute(
            "INSERT INTO ai_attachments(messageId, uri, mimeType, displayName, sizeBytes, kind, extractedText, permissionOwned) "
            "VALUES(?, '', 'text/plain', 'note.txt', 12, 'document', NULL, 0)", (msg_id,))
        con.execute(
            "INSERT INTO agent_runs(runId, conversationId, conversationTitle, userRequestSummary, "
            "modelConfigId, permissionMode, enabledSourcesJson, status, modelCallCount, "
            "usageReportedCallCount, inputTokens, outputTokens, totalTokens, cachedInputTokens, "
            "cacheRateInputTokens, reasoningTokens, startedAt, completedAt) "
            "VALUES('run-backup-1', ?, '对话一', '总结', 'cfg1', 'REQUIRE_APPROVAL', "
            "'[]', 'COMPLETED', 2, 2, 30, 12, 42, 4, 30, 3, ?, ?)",
            (conv_id, NOW_MS, NOW_MS + 25),
        )
    return conv_id


def _seed_private_files(client):
    """vault-meta.json (active+pending) and the reading progress ledger."""
    from app.core.config import PRIVATE_DIR
    from app.services.reader_service import write_ledger_records

    meta = {
        "saltBase64": base64.b64encode(b"0123456789abcdef").decode(),
        "verifierCipher": base64.b64encode(bytes(48) + b"v").decode(),
        "verifierIv": base64.b64encode(bytes(12)).decode(),
        "kdfIterations": 120_000,
        "activeGenerationId": "gen-active",
        "pendingSaltBase64": base64.b64encode(b"fedcba9876543210").decode(),
        "pendingVerifierCipher": base64.b64encode(bytes(48) + b"w").decode(),
        "pendingVerifierIv": base64.b64encode(bytes(12)).decode(),
        "pendingKdfIterations": 130_000,
        "pendingGenerationId": "gen-pending",
    }
    (PRIVATE_DIR / "vault-meta.json").write_text(json.dumps(meta), encoding="utf-8")
    write_ledger_records([{
        "fingerprint": FINGERPRINT,
        "type": "TXT",
        "textPageIndex": 3,
        "textParagraphIndex": 5,
        "pdfPageIndex": 0,
        "totalPages": 120,
        "updatedAt": NOW_MS,
    }])


_seeded = False


def _ensure_seeded(client):
    """Seed exactly once even when a class runs without its siblings."""
    global _seeded
    if _seeded:
        return
    _seed_core_rows(client)
    _seed_private_files(client)
    _seeded = True


EXPECTED_COUNTS = {
    "thoughtCount": 2,
    "categoryCount": 1,
    "favoriteCount": 1,
    "dateRecordCount": 1,
    "poetryCategoryCount": 1,
    "poemCount": 1,
    "vaultItemCount": 1,
    "gameStateCount": 1,
    "gameStatisticCount": 1,
    "usageDeviceCount": 1,
    "usageDayCount": 2,
    "readerProgressCount": 1,
    "agentConversationCount": 1,
}


class TestExportDecodeRoundtrip:
    def test_export_v34_and_preview_counts_match_inserted_rows(self, client):
        from app.services.backup_import import counts_per_section, decode

        _ensure_seeded(client)

        response = client.get("/api/backup/export")
        assert response.status_code == 200, response.text
        assert response.headers["content-type"].startswith("application/json")
        assert "attachment" in response.headers.get("content-disposition", "")
        document = response.json()
        assert document["format"] == "DeskCubby"
        assert document["version"] == 34
        for section in ("settings", "thoughts", "categories", "favorites", "dateRecords",
                        "poetryCategories", "poems", "vault", "gameStates", "gameStatistics",
                        "usageDevices", "readerProgress"):
            assert section in document
        assert "agentChats" in document

        # Decoding the raw bytes yields exactly the inserted row counts.
        parsed = decode(response.content)
        counts = counts_per_section(parsed)
        for field, expected in EXPECTED_COUNTS.items():
            assert counts[field] == expected, f"{field}: {counts[field]} != {expected}"

        # The agentChats payload includes the complete Android-compatible run
        # projection; legacy Web status/kind spellings are canonicalized.
        payload = json.loads(base64.b64decode(document["agentChats"]))
        assert len(payload["conversations"]) == 1
        assert len(payload["messages"]) == 1
        assert len(payload["attachments"]) == 1
        assert len(payload["runs"]) == 1
        assert payload["messages"][0]["conversationSyncId"] == payload["conversations"][0]["syncId"]
        assert payload["attachments"][0]["kind"] == "DOCUMENT"
        assert payload["runs"][0]["status"] == "SUCCEEDED"
        assert payload["runs"][0]["conversationSyncId"] == payload["conversations"][0]["syncId"]

        # Vault metadata travels as active + pending descriptors.
        assert document["vault"]["active"]["generationId"] == "gen-active"
        assert document["vault"]["pending"]["generationId"] == "gen-pending"

    def test_exported_settings_contain_no_api_key_values(self, client):
        from app.services.settings_store import update_settings

        update_settings(_db(client), {
            "aiConfigs": [{
                "id": "cfg1", "name": "Main", "type": "TEXT",
                "endpointUrl": "https://api.example.com/v1/chat/completions",
                "model": "demo", "enabled": True, "apiKey": "sk-super-secret-123",
                "supportsToolCalling": False, "temperature": 0.7, "systemPrompt": "",
                "allowInsecureHttp": False,
            }],
        })
        response = client.get("/api/backup/export")
        assert response.status_code == 200
        text = response.text

        def walk(node):
            if isinstance(node, dict):
                for key, value in node.items():
                    assert not (key == "apiKey" and value), "apiKey leaked into backup"
                    walk(value)
            elif isinstance(node, list):
                for item in node:
                    walk(item)

        walk(response.json())
        assert "sk-super-secret-123" not in text
        # The live store keeps the key server-side; only backups strip it.
        from app.services.settings_store import load_settings

        stored = [c for c in load_settings(_db(client))["aiConfigs"] if c["id"] == "cfg1"]
        assert stored and stored[0]["apiKey"] == "sk-super-secret-123"


class TestImportCommit:
    def test_preview_then_commit_replaces_tables_transactionally(self, client):
        from app.services.backup_import import counts_per_section, decode

        _ensure_seeded(client)
        exported = client.get("/api/backup/export").json()
        preview = client.post(
            "/api/backup/import",
            files={"file": ("backup.json", json.dumps(exported).encode("utf-8"), "application/json")},
        )
        assert preview.status_code == 200, preview.text
        body = preview.json()
        token = body["token"]
        for field, expected in EXPECTED_COUNTS.items():
            assert body[field] == expected

        # Diverge locally after the preview: extra rows must disappear on commit.
        con = _db(client)
        with con:
            con.execute(
                "INSERT INTO flash_thoughts(id, content, createdAt, updatedAt, pinned, deletedAt, "
                "sortOrder, categoryId, highlighted) VALUES(999, '临时想法', ?, ?, 0, NULL, 9, 1, 0)",
                (NOW_MS + 99, NOW_MS + 99))
            con.execute(
                "INSERT INTO saved_poems(id, content, source, createdAt, updatedAt, sortOrder, categoryId) "
                "VALUES(77, '多余的诗', '', ?, ?, 9, 1)", (NOW_MS, NOW_MS))
            # History rows survive; favorites are re-projected from the backup.
            con.execute(
                "UPDATE browser_records SET favorite = 1 WHERE url = 'https://example.com/history'")

        committed = client.post("/api/backup/import/commit", json={"token": token})
        assert committed.status_code == 200, committed.text

        rows = {
            "thoughts": con.execute("SELECT id FROM flash_thoughts ORDER BY id").fetchall(),
            "poems": con.execute("SELECT id FROM saved_poems ORDER BY id").fetchall(),
            "favorites": con.execute(
                "SELECT url FROM browser_records WHERE favorite = 1 ORDER BY url").fetchall(),
            "history": con.execute("SELECT COUNT(*) FROM browser_records").fetchone()[0],
            "usage_days": con.execute("SELECT COUNT(*) FROM usage_events_daily").fetchone()[0],
            "conversations": con.execute("SELECT COUNT(*) FROM ai_conversations").fetchone()[0],
            "messages": con.execute("SELECT COUNT(*) FROM ai_messages").fetchone()[0],
            "attachments": con.execute("SELECT COUNT(*) FROM ai_attachments").fetchone()[0],
            "runs": con.execute("SELECT status, conversationId FROM agent_runs").fetchall(),
        }
        assert [r["id"] for r in rows["thoughts"]] == [1, 2], "commit must replace, not append"
        assert all(r["id"] != 999 for r in rows["thoughts"])
        assert [r["id"] for r in rows["poems"]] == [1]
        assert [r["url"] for r in rows["favorites"]] == ["https://example.com/a"]
        assert rows["history"] >= 2  # non-favorite history preserved
        assert rows["usage_days"] == 2
        assert (rows["conversations"], rows["messages"], rows["attachments"]) == (1, 1, 1)
        assert len(rows["runs"]) == 1
        assert rows["runs"][0]["status"] == "SUCCEEDED"
        assert rows["runs"][0]["conversationId"] is not None

        # Reader progress merged LWW into the ledger file.
        from app.core.config import PRIVATE_DIR
        from app.services.reader_service import read_ledger_records

        records = read_ledger_records()
        assert any(r["fingerprint"] == FINGERPRINT for r in records)

        # A used token cannot be replayed.
        replay = client.post("/api/backup/import/commit", json={"token": token})
        assert replay.status_code == 404

    def test_failed_commit_rolls_back_completely(self, client):
        import app.routers.backup as backup_router
        from app.services.backup_import import decode, map_to_rows

        sentinel = "回滚哨兵"
        con = _db(client)
        with con:
            con.execute(
                "INSERT INTO flash_thoughts(id, content, createdAt, updatedAt, pinned, deletedAt, "
                "sortOrder, categoryId, highlighted) VALUES(500, ?, ?, ?, 0, NULL, 0, NULL, 0)",
                (sentinel, NOW_MS, NOW_MS))

        exported = client.get("/api/backup/export").json()
        raw = json.dumps(exported).encode("utf-8")
        parsed = decode(raw)
        rows = map_to_rows(parsed)
        rows["flash_thoughts"].append(dict(rows["flash_thoughts"][0]))  # duplicate PK mid-transaction

        with pytest.raises(Exception):
            backup_router._apply_import(con, parsed, rows, parsed["version"])

        remaining = con.execute(
            "SELECT content FROM flash_thoughts WHERE id = 500").fetchone()
        assert remaining is not None and remaining["content"] == sentinel
        count = con.execute("SELECT COUNT(*) FROM flash_thoughts").fetchone()[0]
        assert count >= 1  # nothing was destroyed by the failed replace

        # cleanup for later assertions in other classes
        with con:
            con.execute("DELETE FROM flash_thoughts WHERE id = 500")

    def test_malformed_bytes_rejected(self, client):
        cases = [
            b"this is not json at all",
            b"",
            json.dumps({"format": "DeskCubby", "version": 34}).encode(),  # missing sections
            json.dumps({"format": "Other", "version": 34, "settings": {}}).encode(),
        ]
        for payload in cases:
            response = client.post(
                "/api/backup/import",
                files={"file": ("bad.json", payload, "application/json")},
            )
            assert response.status_code == 400, f"{payload[:40]!r} -> {response.status_code}"
            assert "error" in response.json()

    def test_oversized_backup_rejected_with_patched_cap(self, client, monkeypatch):
        import app.routers.backup as backup_router

        monkeypatch.setattr(backup_router.backup_import, "MAX_JSON_BYTES", 16)
        payload = b"x" * 128  # tiny body but far above the patched 16-byte cap
        response = client.post(
            "/api/backup/import",
            files={"file": ("big.json", payload, "application/json")},
        )
        assert response.status_code == 400
        assert "64 MiB" in response.json()["error"]["message"]

    def test_include_secrets_is_refused(self, client):
        response = client.get("/api/backup/export", params={"includeSecrets": True})
        assert response.status_code == 400


class TestWidgetsValidation:
    BASE_CONFIG = {
        "id": "w1", "name": "卡片一", "widthCells": 2, "heightCells": 1,
        "backgroundColorArgb": -15461322, "textColorArgb": -1,
        "backgroundImageUri": None, "showName": True, "backgroundOpacityPercent": 100,
        "showIcon": True, "textAlignment": "START", "textScalePercent": 100,
        "cornerStyle": "ROUNDED", "surfaceScalePercent": 100, "appIconScalePercent": 100,
        "contentType": "HOME_MODULE", "homeModuleId": "today", "appPackageName": None,
        "appLabel": None, "usageRangeDays": 7,
    }

    def _payload(self, client, configs):
        return client.put("/api/widgets/configs", json={"configs": configs})

    def test_put_then_get_roundtrip(self, client):
        response = self._payload(client, [self.BASE_CONFIG])
        assert response.status_code == 200, response.text
        stored = client.get("/api/widgets/configs").json()
        match = [c for c in stored if c["id"] == "w1"]
        assert match and match[0]["homeModuleId"] == "today"

    def test_rejects_out_of_bounds_values(self, client):
        invalid_cases = [
            {"widthCells": 7},      # task-mandated rejection: cells above 6
            {"heightCells": 0},
            {"backgroundOpacityPercent": 101},
            {"textScalePercent": 74},
            {"surfaceScalePercent": 101},
            {"appIconScalePercent": 49},
            {"usageRangeDays": 14},
            {"contentType": "MAGIC"},
            {"textAlignment": "JUSTIFY"},
            {"cornerStyle": "WAVY"},
        ]
        for patch in invalid_cases:
            config = {**self.BASE_CONFIG, **patch}
            response = self._payload(client, [config])
            assert response.status_code == 400, f"{patch} -> {response.status_code}"

    def test_legacy_home_module_ids_and_unknown_fallback(self, client):
        legacy = {**self.BASE_CONFIG, "id": "w-cloud", "homeModuleId": "cloud_sync_now"}
        unknown = {**self.BASE_CONFIG, "id": "w-unknown", "homeModuleId": "not_a_module"}
        response = self._payload(client, [legacy, unknown])
        assert response.status_code == 200, response.text
        stored = {c["id"]: c for c in client.get("/api/widgets/configs").json()}
        assert stored["w-cloud"]["homeModuleId"] == "cloud_sync"
        assert stored["w-cloud"]["contentType"] == "APP_MODULE"
        assert stored["w-unknown"]["homeModuleId"] == "today"
        assert stored["w-unknown"]["contentType"] == "HOME_MODULE"


class TestCloudSyncRedactionAndAutoBackup:
    def test_status_redacts_secrets_and_delete_purges_them(self, client):
        created = client.post("/api/cloudsync/configs", json={
            "name": "家庭 NAS", "serviceType": "WEBDAV",
            "endpointUrl": "https://dav.example.com/dav/", "remotePath": "DeskCubby",
            "webDavUsername": "alice", "webDavPassword": "sup3r-secret",
            "selectedContents": ["DIARIES", "THOUGHTS"], "direction": "TWO_WAY",
        })
        assert created.status_code == 200, created.text
        config = created.json()["config"]
        config_id = config["id"]
        assert config["webDavPassword"] == "" and "sup3r-secret" not in created.text
        assert config["hasCredentials"] is True

        status = client.get("/api/cloudsync/status")
        assert status.status_code == 200
        listed = status.json()
        entry = next(c for c in listed["configs"] if c["id"] == config_id)
        assert entry["webDavPassword"] == "" and entry["hasCredentials"] is True
        assert "sup3r-secret" not in status.text
        assert listed["running"] is False
        assert listed["undoAvailable"] in (True, False)
        assert "lastResult" in listed

        # Secrets live only in the private container, never inside settings.
        settings_text = json.dumps(client.get("/api/settings").json())
        assert "sup3r-secret" not in settings_text

        deleted = client.delete(f"/api/cloudsync/configs/{config_id}")
        assert deleted.status_code == 200
        after = client.get("/api/cloudsync/status").json()
        assert all(c["id"] != config_id for c in after["configs"])
        from app.core.config import DATA_DIR

        secrets_file = DATA_DIR / "private" / "cloud-secrets.json"
        if secrets_file.exists():
            stored_secrets = json.loads(secrets_file.read_text(encoding="utf-8"))
            assert config_id not in stored_secrets, "DELETE must purge the stored secrets"

    def test_config_validation_rejects_bad_kind_and_http(self, client):
        http_default = client.post("/api/cloudsync/configs", json={
            "name": "insecure", "serviceType": "WEBDAV",
            "endpointUrl": "http://dav.example.com/dav/",
            "selectedContents": ["DIARIES"],
        })
        assert http_default.status_code == 400  # HTTP requires allowInsecureHttp
        bad_kind = client.post("/api/cloudsync/configs", json={
            "name": "weird", "serviceType": "FTP", "endpointUrl": "https://x.example.com/",
            "selectedContents": ["DIARIES"],
        })
        assert bad_kind.status_code == 400
        no_contents = client.post("/api/cloudsync/configs", json={
            "name": "empty", "serviceType": "WEBDAV", "endpointUrl": "https://x.example.com/",
            "selectedContents": [],
        })
        assert no_contents.status_code == 400

    def test_s3_scheme_completion_and_explicit_credential_clear(self, client):
        created = client.post("/api/cloudsync/configs", json={
            "name": "S3 local", "serviceType": "S3_COMPATIBLE",
            "endpointUrl": "s3.example.test/api", "remotePath": "",
            "s3Bucket": "desk-bucket", "s3Region": "us-east-1",
            "s3AccessKey": "access", "s3SecretKey": "secret",
            "selectedContents": ["DIARIES"], "direction": "TWO_WAY",
        })
        assert created.status_code == 200, created.text
        config = created.json()["config"]
        config_id = config["id"]
        assert config["endpointUrl"] == "https://s3.example.test/api"
        assert config["remotePath"] == ""
        assert config["hasCredentials"] is True

        cleared = client.put(f"/api/cloudsync/configs/{config_id}", json={
            **config,
            "enabled": False,
            "clearCredentials": True,
        })
        assert cleared.status_code == 200, cleared.text
        assert cleared.json()["config"]["hasCredentials"] is False
        status = client.get("/api/cloudsync/status").json()
        listed = next(item for item in status["configs"] if item["id"] == config_id)
        assert listed["hasCredentials"] is False
        assert client.delete(f"/api/cloudsync/configs/{config_id}").status_code == 200

        missing = client.post("/api/cloudsync/configs", json={
            "name": "missing", "serviceType": "S3_COMPATIBLE",
            "endpointUrl": "s3.example.test", "s3Bucket": "desk-bucket",
            "selectedContents": ["DIARIES"],
        })
        assert missing.status_code == 400

    def test_delete_config_restores_secrets_when_settings_write_fails(self, client, monkeypatch):
        from app.routers import cloudsync

        created = client.post("/api/cloudsync/configs", json={
            "name": "rollback", "serviceType": "WEBDAV",
            "endpointUrl": "https://dav.example.test/root",
            "webDavUsername": "alice", "webDavPassword": "rollback-secret",
            "selectedContents": ["DIARIES"],
        })
        assert created.status_code == 200, created.text
        config_id = created.json()["config"]["id"]
        con = _db(client)
        try:
            assert cloudsync.engine.read_secrets(config_id)["webDavPassword"] == "rollback-secret"
            with monkeypatch.context() as patch:
                patch.setattr(
                    cloudsync,
                    "update_settings",
                    lambda *_args, **_kwargs: (_ for _ in ()).throw(RuntimeError("injected")),
                )
                with pytest.raises(RuntimeError, match="injected"):
                    cloudsync.delete_config(config_id, con=con)
            assert any(c.get("id") == config_id for c in cloudsync._stored_configs(con))
            assert cloudsync.engine.read_secrets(config_id)["webDavPassword"] == "rollback-secret"
        finally:
            assert client.delete(f"/api/cloudsync/configs/{config_id}").status_code == 200

    def test_auto_backup_run_writes_file_under_data_backups(self, client):
        from app.core.config import BACKUPS_DIR

        saved = client.put("/api/backup/auto", json={"enabled": True, "keepCount": 2})
        assert saved.status_code == 200, saved.text
        assert saved.json()["enabled"] is True

        run = client.post("/api/backup/auto/run")
        assert run.status_code == 200, run.text
        result = run.json()
        assert result["formatVersion"] == 34
        target = BACKUPS_DIR / result["file"]
        assert target.is_file()
        document = json.loads(target.read_text(encoding="utf-8"))
        assert document["version"] == 34

        second = client.post("/api/backup/auto/run").json()
        kept = sorted(p.name for p in (BACKUPS_DIR / "auto").glob("deskcubby-backup-*.json"))
        assert len(kept) <= 2
        assert second["file"] != result["file"] or kept

        fetched = client.get("/api/backup/auto").json()
        assert fetched["enabled"] is True and fetched["keepCount"] == 2


class TestCloudSyncEngineUnits:
    def test_manifest_codec_roundtrip_and_blob_naming(self):
        from app.services.cloudsync_engine import (
            decode_manifest,
            encode_manifest,
            object_storage_name,
            sha256_bytes,
        )

        entries = {
            "diaries/2024-01-01.md": {
                "sha256": sha256_bytes(b"a"), "size": 1, "lastModified": 1,
                "storageName": ".deskcubby-object-example", "blobVersion": '"etag-v1"',
            },
        }
        decoded = decode_manifest(encode_manifest(entries))
        assert decoded == entries
        assert object_storage_name("diaries/x.md", sha256_bytes(b"x")) == object_storage_name(
            "diaries/x.md", sha256_bytes(b"x"))
        assert object_storage_name("a.md", "0" * 64) != object_storage_name("b.md", "0" * 64)

    def test_undo_snapshot_keeps_latest_only(self, client):
        from app.services.cloudsync_engine import (
            load_undo_snapshot,
            save_undo_snapshot,
        )

        con = _db(client)
        save_undo_snapshot(con, {"configId": "c", "finishedAtMs": 1, "entries": [
            {"key": "diaries/a.md", "path": "workspace/diary/a.md", "action": "create"}]})
        first = load_undo_snapshot(con)
        assert first and first["entries"][0]["key"] == "diaries/a.md"
        save_undo_snapshot(con, None)
        assert load_undo_snapshot(con) is None

    def test_conflict_copy_and_undo_restore(self, client):
        from app.core.config import DIARY_DIR
        from app.services import cloudsync_engine as eng

        target = DIARY_DIR / "2024-01-01.md"
        original = "# 2024-01-01\n\noriginal words\n"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(original, encoding="utf-8")

        con = _db(client)

        class _App:
            state = type("S", (), {"db": con})()

        undo = eng._UndoRecorder(con)
        overwritten = b"# overwritten by remote\n"
        outcome = eng.apply_remote(
            "diaries/2024-01-01.md", overwritten,
            {"sha256": eng.sha256_bytes(overwritten)},
            target, undo, conflict_copy=True,
        )
        assert outcome == "conflict-copy"
        created = DIARY_DIR / "undo-created.md"
        assert eng.apply_remote(
            "diaries/undo-created.md", b"remote bytes\n",
            {"sha256": eng.sha256_bytes(b"remote bytes\n")},
            created, undo, conflict_copy=False,
        ) == "applied"

        # The canonical local edit remains in place; the remote bytes go to a
        # deterministic, visible sibling for manual comparison.
        assert target.read_text(encoding="utf-8") == original
        copies = list(DIARY_DIR.glob("2024-01-01.remote-conflict-*.md"))
        assert len(copies) == 1 and copies[0].read_bytes() == overwritten

        undo.commit("cfg", 1_234)
        # Undo deletes the two files this round created. The canonical diary
        # never needed restoration because conflict handling did not replace it.
        restored = eng.undo_last_sync(_App())
        assert restored == 2
        assert target.read_text(encoding="utf-8") == original
        assert not created.exists(), "undo removes files created this round"
        assert not list(DIARY_DIR.glob("*remote-conflict-*"))
        assert eng.load_undo_snapshot(con) is None  # one-shot: latest snapshot only


class TestAndroid0235ParityBoundaries:
    def test_pending_attachments_are_consumed_all_or_not_at_all(self, monkeypatch, tmp_path):
        from app.routers import ai

        uploads = tmp_path / "uploads"
        index_path = uploads / ".attachments-index.json"
        monkeypatch.setattr(ai, "UPLOADS_DIR", uploads)
        monkeypatch.setattr(ai, "_INDEX_PATH", index_path)
        entries = {
            "a": {"id": "a", "fileName": "a.txt", "kind": "DOCUMENT"},
            "b": {"id": "b", "fileName": "b.png", "kind": "IMAGE"},
        }
        ai._save_index(entries)

        assert ai._take_attachments(["a", "missing"]) == []
        assert ai._load_index() == entries, "a partial miss must not consume valid uploads"
        assert ai._take_attachments(["a", "a"]) == []
        assert ai._load_index() == entries, "duplicate ids must not consume the staged upload"

        taken = ai._take_attachments(["b", "a"])
        assert [item["id"] for item in taken] == ["b", "a"]
        assert ai._load_index() == {}

    def test_agent_history_prefers_newest_images_and_preserves_attachment_order(
        self, monkeypatch, tmp_path,
    ):
        import sqlite3

        from app.services import agent_runtime

        uploads = tmp_path / "uploads"
        uploads.mkdir()
        (uploads / "old.png").write_bytes(b"O")
        (uploads / "new-1.png").write_bytes(b"1")
        (uploads / "new-2.png").write_bytes(b"2")
        monkeypatch.setattr(agent_runtime, "UPLOADS_DIR", uploads)
        monkeypatch.setattr(agent_runtime, "MAX_IMAGE_BYTES", 2)

        con = sqlite3.connect(":memory:")
        con.row_factory = sqlite3.Row
        con.executescript(
            "CREATE TABLE ai_messages(id INTEGER, conversationId INTEGER, role TEXT, content TEXT, createdAt INTEGER);"
            "CREATE TABLE ai_attachments(id INTEGER, messageId INTEGER, uri TEXT, mimeType TEXT, "
            "displayName TEXT, kind TEXT, extractedText TEXT);"
        )
        con.executemany(
            "INSERT INTO ai_messages VALUES(?,?,?,?,?)",
            [(1, 7, "user", "old", 1), (2, 7, "assistant", "answer", 2), (3, 7, "user", "new", 3)],
        )
        con.executemany(
            "INSERT INTO ai_attachments VALUES(?,?,?,?,?,?,?)",
            [
                (1, 1, "uploads://old.png", "image/png", "old.png", "IMAGE", None),
                (2, 3, "uploads://new-1.png", "image/png", "one.png", "IMAGE", None),
                (3, 3, "uploads://new-2.png", "image/png", "two.png", "IMAGE", None),
                (4, 3, "", "text/plain", "notes.txt", "document", "untrusted words"),
            ],
        )
        history = agent_runtime._build_agent_history(con, 7, 3)
        assert [item["role"] for item in history] == ["user", "assistant", "user"]
        assert "images" not in history[0], "newest images own the bounded byte budget"
        assert history[2]["images"] == [
            "data:image/png;base64,MQ==",
            "data:image/png;base64,Mg==",
        ]
        assert '<untrusted_attachment name="notes.txt"' in history[2]["content"]
        assert "untrusted words" in history[2]["content"]
        con.close()

    def test_reader_preferences_codec_normalizes_argb_and_remote_payload(self, monkeypatch, tmp_path):
        from app.services import reader_service

        path = tmp_path / "preferences.json"
        monkeypatch.setattr(reader_service, "PREFERENCES_PATH", path)
        default_wire = reader_service.encode_reader_preferences()
        assert b'"fontSizeSp":19' in default_wire
        assert b'"lineHeightMultiplier":1.600000023841858' in default_wire
        assert b'"paragraphSpacingDp":10' in default_wire
        value = {
            **reader_service.READER_PREFERENCES_DEFAULTS,
            "background": "CUSTOM",
            "customBackgroundArgb": 0x00112233,
            "fontSizeSp": 99,
            "lineHeightMultiplier": 0.1,
            "paragraphSpacingDp": 18.5,
            "showProgressPercentage": True,
            "chapterDetectionMode": "CUSTOM",
        }
        stored = reader_service.write_reader_preferences(value)
        assert stored["customBackgroundArgb"] & 0xFFFFFFFF == 0xFF112233
        assert stored["fontSizeSp"] == 38.0
        assert stored["lineHeightMultiplier"] == 1.0
        assert stored["paragraphSpacingDp"] == 18.5

        encoded = reader_service.encode_reader_preferences()
        root = json.loads(encoded)
        assert set(root) == {
            "format", "version", *reader_service.READER_PREFERENCES_DEFAULTS,
        }
        path.unlink()
        applied = reader_service.apply_reader_preferences_payload(encoded)
        assert applied == stored

        root["deviceOnlyRegex"] = "^Chapter"
        with pytest.raises(Exception) as error:
            reader_service.apply_reader_preferences_payload(
                json.dumps(root, separators=(",", ":")).encode()
            )
        assert getattr(error.value, "status", None) == 502

    def test_reader_text_normalizes_all_android_line_endings(self):
        from app.services.reader_service import normalize_reader_text_line_breaks

        assert normalize_reader_text_line_breaks("序章\r第一章\r\n第二章\n") == (
            "序章\n第一章\n第二章\n"
        )

    def test_s3_collection_url_has_trailing_slash_for_remote_path(self):
        from app.services.s3_client import build_collection_url

        base = {
            "endpointUrl": "https://s3.example.test/api/",
            "s3Bucket": "desk-bucket",
            "s3PathStyle": True,
        }
        assert build_collection_url({**base, "remotePath": "Desk Cubby/nested"}) == (
            "https://s3.example.test/api/desk-bucket/Desk%20Cubby/nested/"
        )
        # Android treats an explicitly empty remotePath as the bucket root;
        # only a missing field receives the model's DeskCubby default.
        assert build_collection_url({**base, "remotePath": ""}).endswith("/desk-bucket/")
        assert build_collection_url(base).endswith("/desk-bucket/DeskCubby/")

    def test_cloud_config_rejects_non_android_paths_and_empty_userinfo(self):
        from app.routers.cloudsync import ConfigBody, _validate_config

        base = {
            "name": "sync", "endpointUrl": "https://dav.example.test/root",
            "selectedContents": ["DIARIES"],
        }
        assert _validate_config(ConfigBody(**base, remotePath=""))["remotePath"] == ""
        assert _validate_config(ConfigBody(**base, remotePath=" /nested//path/ "))["remotePath"] == (
            "nested/path"
        )
        for remote_path in ("folder\\child", "folder/../child", "folder/\x00child"):
            with pytest.raises(Exception) as error:
                _validate_config(ConfigBody(**base, remotePath=remote_path))
            assert getattr(error.value, "status", None) == 400
        for endpoint in (
            "https://:secret@dav.example.test/root",
            "https://@dav.example.test/root",
            "https://dav.example.test/root?",
            "https://dav.example.test/root#",
        ):
            with pytest.raises(Exception) as error:
                _validate_config(ConfigBody(**{**base, "endpointUrl": endpoint}))
            assert getattr(error.value, "status", None) == 400

    def test_cloud_scope_fingerprint_tracks_android_s3_scope_fields(self):
        import hashlib

        from app.services.cloudsync_engine import _scope_fingerprint

        base = {
            "serviceType": "S3_COMPATIBLE",
            "endpointUrl": "https://s3.example.test/api",
            "remotePath": "DeskCubby",
            "s3Bucket": "desk-bucket",
            "s3Region": "us-east-1",
            "s3PathStyle": True,
            "s3AccessKey": "access",
        }
        expected = hashlib.sha256(
            "S3_COMPATIBLE\nhttps://s3.example.test/api\nDeskCubby\n"
            "desk-bucket\nus-east-1\ntrue\naccess".encode()
        ).hexdigest()
        assert _scope_fingerprint(base) == expected
        assert _scope_fingerprint({**base, "s3Region": "eu-west-1"}) != expected
        assert _scope_fingerprint({**base, "s3PathStyle": False}) != expected

    def test_record_manifest_and_state_roundtrip_signed_hash_revision(self, monkeypatch, tmp_path):
        from app.services import record_sync

        # SHA-256("a") starts with ca..., which Android interprets as a negative
        # signed Long via Long.parseUnsignedLong. Both manifest and local ancestry
        # codecs must retain it rather than treating it as an invalid timestamp.
        revision = record_sync._content_revision(b"a")
        assert revision < 0
        entry = {
            "id": "record-negative", "revision": revision, "updatedAt": revision,
            "deleted": False, "sha256": record_sync._sha(b"a"),
        }
        encoded = record_sync.encode_record_manifest("READER_PREFERENCES", {entry["id"]: entry})
        assert record_sync.decode_record_manifest(encoded, "READER_PREFERENCES") == {
            entry["id"]: entry,
        }

        monkeypatch.setattr(record_sync, "PRIVATE_DIR", tmp_path)
        state_entry = {**entry, "localKey": "reader-preferences"}
        record_sync.save_state(
            "cfg-negative", "READER_PREFERENCES", "scope", '"manifest-v1"',
            {entry["id"]: state_entry},
        )
        assert record_sync.load_state("cfg-negative", "READER_PREFERENCES", "scope") == {
            entry["id"]: state_entry,
        }
        assert record_sync._next_tombstone_revision(2**63 - 1, NOW_MS) == NOW_MS
        assert record_sync._payload_limit("USAGE_STATISTICS") == 10 * 1024 * 1024 + 64 * 1024
        assert record_sync._payload_limit("VAULT") == 8 * 1024 * 1024

    def test_reader_preferences_record_sync_converges_across_two_devices(
        self, client, monkeypatch, tmp_path,
    ):
        from app.services import reader_service, record_sync

        remote: dict[str, tuple[bytes, str]] = {}
        writes: list[str] = []

        def remote_read(key: str, maximum: int):
            item = remote.get(key)
            if item is None:
                return None
            payload, version = item
            assert len(payload) <= maximum
            return payload, {"blobVersion": version}

        def remote_write(key: str, payload: bytes):
            version = f'"memory-{len(writes) + 1}"'
            remote[key] = (bytes(payload), version)
            writes.append(key)
            return {"blobVersion": version}

        def select_device(name: str) -> None:
            root = tmp_path / name
            monkeypatch.setattr(record_sync, "PRIVATE_DIR", root)
            monkeypatch.setattr(
                reader_service,
                "PREFERENCES_PATH",
                root / "reading" / "preferences.json",
            )

        config = {"id": "two-device-reader", "direction": "TWO_WAY"}
        scope = "a" * 64
        select_device("device-a")
        source = reader_service.write_reader_preferences({
            **reader_service.READER_PREFERENCES_DEFAULTS,
            "background": "CUSTOM",
            "customBackgroundArgb": 0x00123456,
            "fontSizeSp": 23,
            "lineHeightMultiplier": 1.7,
            "paragraphSpacingDp": 12.5,
            "showProgressPercentage": True,
            "chapterDetectionMode": "CUSTOM",
        })
        first = record_sync.sync_records(
            con=_db(client), config=config, contents={"READER_PREFERENCES"},
            mode="force_upload", scope_fingerprint=scope,
            remote_read=remote_read, remote_write=remote_write,
        )
        assert (first["uploaded"], first["downloaded"]) == (1, 0)
        record_sync.commit_states(config["id"], scope, first["pendingStates"])

        select_device("device-b")
        reader_service.write_reader_preferences({
            **reader_service.READER_PREFERENCES_DEFAULTS,
            "background": "NIGHT",
        })
        second = record_sync.sync_records(
            con=_db(client), config=config, contents={"READER_PREFERENCES"},
            mode="force_download", scope_fingerprint=scope,
            remote_read=remote_read, remote_write=remote_write,
        )
        assert (second["uploaded"], second["downloaded"]) == (0, 1)
        record_sync.commit_states(config["id"], scope, second["pendingStates"])
        assert reader_service.read_reader_preferences() == source

        writes_after_download = list(writes)
        unchanged = record_sync.sync_records(
            con=_db(client), config=config, contents={"READER_PREFERENCES"},
            mode="now", scope_fingerprint=scope,
            remote_read=remote_read, remote_write=remote_write,
        )
        assert (
            unchanged["uploaded"], unchanged["downloaded"], unchanged["conflicts"]
        ) == (0, 0, 0)
        assert writes == writes_after_download

    def test_global_settings_uses_android_agent_source_order(self, client):
        from app.services import record_sync
        from app.services.android_json import android_float32
        from app.services.settings_store import load_settings, update_settings

        con = _db(client)
        current = load_settings(con)
        original = {
            "agentEnabledSources": current["agentEnabledSources"],
            "aiEndpointUrl": current["aiEndpointUrl"],
            "aiTemperature": current["aiTemperature"],
        }
        try:
            update_settings(con, {
                "agentEnabledSources": [
                    "statistics", "app_guide", "thoughts", "diary", "unknown-source",
                ],
                "aiEndpointUrl": "https://api.example.test/v1/chat",
                "aiTemperature": 0.7,
            })
            payload = record_sync.RecordAdapter(
                con, "GLOBAL_SETTINGS",
            )._global_settings_payload()
            decoded = json.loads(payload)
            assert decoded["agentEnabledSources"] == [
                "diary", "thoughts", "statistics", "app_guide",
            ]
            assert decoded["aiTemperature"] == android_float32(0.7)
            assert b'https:\\/\\/api.example.test\\/v1\\/chat' in payload
        finally:
            update_settings(con, original)

    def test_global_settings_rejects_invalid_enum_and_scalar_types(self, client):
        from app.core.errors import ApiError
        from app.services import record_sync
        from app.services.settings_store import load_settings

        con = _db(client)
        adapter = record_sync.RecordAdapter(con, "GLOBAL_SETTINGS")
        root = json.loads(adapter._global_settings_payload())
        before = load_settings(con)

        invalid_enum = {**root, "visualStyle": "NOT_A_THEME"}
        with pytest.raises(ApiError):
            adapter._apply_global_settings(record_sync._json_bytes(invalid_enum))

        invalid_boolean = {**root, "tutorialModeEnabled": "true"}
        with pytest.raises(ApiError):
            adapter._apply_global_settings(record_sync._json_bytes(invalid_boolean))

        missing_nested_fields = {**root, "customTheme": {"baseStyle": "MATERIAL"}}
        with pytest.raises(ApiError):
            adapter._apply_global_settings(record_sync._json_bytes(missing_nested_fields))

        after = load_settings(con)
        assert after["visualStyle"] == before["visualStyle"]
        assert after["tutorialModeEnabled"] == before["tutorialModeEnabled"]

    def test_global_settings_sync_preserves_local_ai_api_key(self, client):
        from app.services import record_sync
        from app.services.settings_store import load_settings, update_settings

        con = _db(client)
        original = load_settings(con)["aiConfigs"]
        config = {
            "id": "sync-key-test", "name": "Local", "type": "TEXT",
            "endpointUrl": "https://api.example.test/v1", "model": "model",
            "enabled": True, "allowInsecureHttp": False, "temperature": 0.7,
            "systemPrompt": "", "apiKey": "local-secret-key",
            "supportsToolCalling": True,
        }
        adapter = record_sync.RecordAdapter(con, "GLOBAL_SETTINGS")
        try:
            update_settings(con, {"aiConfigs": [config]})
            root = json.loads(adapter._global_settings_payload())
            assert "local-secret-key" not in json.dumps(root)
            root["aiConfigs"][0]["name"] = "Remote metadata"
            adapter._apply_global_settings(record_sync._json_bytes(root))
            stored = load_settings(con)["aiConfigs"][0]
            assert stored["name"] == "Remote metadata"
            assert stored["apiKey"] == "local-secret-key"
        finally:
            update_settings(con, {"aiConfigs": original})

    def test_android_record_json_and_go_cloud_records(self, client):
        from app.services import record_sync

        assert record_sync._json_bytes({"url": "https://example.test/a"}) == (
            b'{"url":"https:\\/\\/example.test\\/a"}'
        )
        con = _db(client)
        with con:
            con.execute("DELETE FROM game_states WHERE gameId = 'go'")
            con.execute("DELETE FROM game_statistics WHERE gameId = 'go'")
            con.execute(
                "INSERT INTO game_states(gameId,highScore,saveJson,updatedAt) VALUES('go',3,'{}',?)",
                (NOW_MS,),
            )
            con.execute(
                "INSERT INTO game_statistics(gameId,metricKey,value,updatedAt) "
                "VALUES('go','goMovesPlayed',12,?)",
                (NOW_MS,),
            )
        try:
            state_adapter = record_sync.RecordAdapter(con, "GAME_STATES")
            stat_adapter = record_sync.RecordAdapter(con, "GAME_STATISTICS")
            assert "go" in {ref.local_key for ref in state_adapter.list_local()}
            assert "go\x00goMovesPlayed" in {ref.local_key for ref in stat_adapter.list_local()}
            assert json.loads(state_adapter.read_local("go").payload)["gameId"] == "go"
        finally:
            with con:
                con.execute("DELETE FROM game_states WHERE gameId = 'go'")
                con.execute("DELETE FROM game_statistics WHERE gameId = 'go'")

    def test_category_apply_preserves_android_whitespace_and_append_order(self, client):
        import sqlite3

        from app.services import record_sync

        con = _db(client)
        first_id = 920_001
        second_id = 920_002
        with con:
            con.execute("DELETE FROM thought_categories WHERE id IN (?, ?)", (first_id, second_id))
            con.execute(
                "INSERT INTO thought_categories(id,name,colorArgb,sortOrder,createdAt,updatedAt) "
                "VALUES(?, 'existing-sync-category', -1, 500, 1, 1)",
                (first_id,),
            )
        adapter = record_sync.RecordAdapter(con, "THOUGHT_CATEGORIES")
        expected_sort_order = int(con.execute(
            "SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM thought_categories"
        ).fetchone()[0])
        payload = record_sync._json_bytes({
            "name": "  spaced category  ", "colorArgb": -2,
            "sortOrder": -100, "createdAt": 2, "updatedAt": 2,
        })
        try:
            key, _ = adapter.apply_remote(record_sync.SyncRecord("record", 2, 2, payload))
            row = con.execute(
                "SELECT name,sortOrder FROM thought_categories WHERE id = ?", (key,),
            ).fetchone()
            assert row is not None
            assert row["name"] == "  spaced category  "
            assert row["sortOrder"] == expected_sort_order

            second_id = int(con.execute(
                "SELECT COALESCE(MAX(id), 0) + 1000 FROM thought_categories"
            ).fetchone()[0])
            with con:
                con.execute(
                    "INSERT INTO thought_categories(id,name,colorArgb,sortOrder,createdAt,updatedAt) "
                    "VALUES(?, 'duplicate-sync-category', -1, 600, 1, 1)",
                    (second_id,),
                )
            duplicate_payload = record_sync._json_bytes({
                "name": "duplicate-sync-category", "colorArgb": -3,
                "sortOrder": 0, "createdAt": 3, "updatedAt": 3,
            })
            with pytest.raises(sqlite3.IntegrityError):
                adapter.apply_remote(
                    record_sync.SyncRecord("record", 3, 3, duplicate_payload),
                    canonical_local_key=str(first_id),
                )
            unchanged = con.execute(
                "SELECT name FROM thought_categories WHERE id = ?", (first_id,),
            ).fetchone()
            assert unchanged is not None and unchanged["name"] == "existing-sync-category"
        finally:
            with con:
                con.execute("DELETE FROM thought_categories WHERE id IN (?, ?)", (first_id, second_id))
                con.execute("DELETE FROM thought_categories WHERE name = '  spaced category  '")

    def test_meal_filter_default_and_legacy_zero_migration_match_android(self):
        from app.services.settings_store import default_settings, normalize_settings

        defaults = default_settings()
        assert defaults["mealPhotoFilter"]["contrast"] == 1.0
        assert defaults["mealPhotoFilter"]["saturation"] == 1.0
        legacy = default_settings()
        legacy["mealPhotoFilter"].update({"contrast": 0.0, "saturation": 0.0})
        normalized = normalize_settings(legacy)
        assert normalized["mealPhotoFilter"]["contrast"] == 1.0
        assert normalized["mealPhotoFilter"]["saturation"] == 1.0

    def test_legacy_usage_device_id_migration_preserves_history(self):
        import sqlite3
        import uuid

        from app.core.db import _migrate_legacy_usage_device_ids

        con = sqlite3.connect(":memory:")
        con.row_factory = sqlite3.Row
        con.executescript(
            "CREATE TABLE usage_devices(deviceId TEXT PRIMARY KEY,deviceName TEXT NOT NULL,"
            "isLocal INTEGER NOT NULL,updatedAt INTEGER NOT NULL);"
            "CREATE TABLE usage_events_daily(deviceId TEXT NOT NULL,dayIso TEXT NOT NULL,"
            "packageName TEXT NOT NULL,appName TEXT NOT NULL,firstSeen INTEGER NOT NULL,"
            "lastSeen INTEGER NOT NULL,totalTimeMs INTEGER NOT NULL,"
            "PRIMARY KEY(deviceId,dayIso,packageName));"
        )
        con.execute("INSERT INTO usage_devices VALUES('legacy-phone','旧手机',0,10)")
        con.execute(
            "INSERT INTO usage_events_daily VALUES('legacy-phone','2024-01-01',"
            "'com.example','Example',1,10,9000)"
        )
        _migrate_legacy_usage_device_ids(con)
        device = con.execute("SELECT * FROM usage_devices").fetchone()
        assert device is not None
        assert str(uuid.UUID(device["deviceId"])) == device["deviceId"]
        event = con.execute("SELECT * FROM usage_events_daily").fetchone()
        assert event is not None
        assert event["deviceId"] == device["deviceId"]
        assert event["totalTimeMs"] == 9000
        con.close()

    def test_legacy_usage_id_collision_preserves_distinct_days_and_metadata(self):
        import sqlite3

        from app.core.db import _canonical_usage_device_id, _migrate_legacy_usage_device_ids

        con = sqlite3.connect(":memory:")
        con.row_factory = sqlite3.Row
        con.executescript(
            "CREATE TABLE usage_devices(deviceId TEXT PRIMARY KEY,deviceName TEXT NOT NULL,"
            "isLocal INTEGER NOT NULL,updatedAt INTEGER NOT NULL,platform TEXT NOT NULL,"
            "trackingStartedOn TEXT,backfillCompletedThrough TEXT);"
            "CREATE TABLE usage_days(deviceId TEXT NOT NULL,dayIso TEXT NOT NULL,zoneId TEXT NOT NULL,"
            "state TEXT NOT NULL,collectedAt INTEGER NOT NULL,PRIMARY KEY(deviceId,dayIso));"
            "CREATE TABLE usage_events_daily(deviceId TEXT NOT NULL,dayIso TEXT NOT NULL,"
            "packageName TEXT NOT NULL,appName TEXT NOT NULL,firstSeen INTEGER NOT NULL,"
            "lastSeen INTEGER NOT NULL,totalTimeMs INTEGER NOT NULL,"
            "PRIMARY KEY(deviceId,dayIso,packageName));"
        )
        old_id = "legacy-phone"
        canonical_id = _canonical_usage_device_id(old_id)
        con.execute(
            "INSERT INTO usage_devices VALUES(?,?,?,?,?,?,?)",
            (canonical_id, "existing", 0, 10, "android", "2024-01-02", "2024-01-02"),
        )
        con.execute(
            "INSERT INTO usage_devices VALUES(?,?,?,?,?,?,?)",
            (old_id, "legacy newer", 1, 20, "web", "2024-01-01", "2024-01-03"),
        )
        for device_id, day_iso, collected, millis in (
            (canonical_id, "2024-01-02", 10, 1000),
            (old_id, "2024-01-01", 20, 2000),
        ):
            con.execute(
                "INSERT INTO usage_days VALUES(?,?,'UTC','FINAL',?)",
                (device_id, day_iso, collected),
            )
            con.execute(
                "INSERT INTO usage_events_daily VALUES(?,?,'com.example','Example',?,?,?)",
                (device_id, day_iso, collected, collected, millis),
            )

        _migrate_legacy_usage_device_ids(con)

        device = con.execute("SELECT * FROM usage_devices").fetchone()
        assert device is not None
        assert (device["deviceId"], device["deviceName"], device["platform"]) == (
            canonical_id, "legacy newer", "web",
        )
        assert (device["trackingStartedOn"], device["backfillCompletedThrough"]) == (
            "2024-01-01", "2024-01-03",
        )
        assert dict(con.execute(
            "SELECT dayIso,totalTimeMs FROM usage_events_daily ORDER BY dayIso"
        ).fetchall()) == {"2024-01-01": 2000, "2024-01-02": 1000}
        assert con.execute("SELECT COUNT(*) FROM usage_days").fetchone()[0] == 2
        con.close()

    def test_backup_usage_device_ids_follow_android_uuid_codec(self):
        from app.services.backup_import import BackupDecodeError, _decode_usage_devices

        record = {
            "schemaVersion": 1,
            "deviceId": "  00000000-0000-4000-8000-00000000ABCD  ",
            "deviceName": "Phone",
            "platform": "android",
            "updatedAtEpochMillis": 0,
            "history": {
                "schemaVersion": 4,
                "trackingStartedOn": None,
                "backfillCompletedThrough": None,
                "days": [],
            },
        }
        decoded = _decode_usage_devices([record])
        assert decoded[0]["deviceId"] == "00000000-0000-4000-8000-00000000abcd"

        invalid = dict(record, deviceId="old-web-device-id")
        with pytest.raises(BackupDecodeError):
            _decode_usage_devices([invalid])

    def test_backup_usage_merge_is_per_day_and_final_wins(self, client):
        import datetime as dt

        from app.routers.backup import _merge_usage_devices

        con = _db(client)
        device_id = "00000000-0000-4000-8000-00000000f00d"
        first_day = (dt.date.today() - dt.timedelta(days=10)).isoformat()
        second_day = (dt.date.today() - dt.timedelta(days=9)).isoformat()

        def record(*, updated_at, name, days):
            return {
                "deviceId": device_id,
                "deviceName": name,
                "updatedAtEpochMillis": updated_at,
                "history": {"days": days},
            }

        def day(date, state, collected, millis):
            return {
                "date": date,
                "state": state,
                "collectedAtEpochMillis": collected,
                "apps": [{"packageName": "com.example", "foregroundMillis": millis}],
            }

        with con:
            con.execute("DELETE FROM usage_events_daily WHERE deviceId = ?", (device_id,))
            con.execute("DELETE FROM usage_devices WHERE deviceId = ?", (device_id,))
            con.execute(
                "INSERT INTO usage_devices(deviceId,deviceName,isLocal,updatedAt) VALUES(?,?,0,?)",
                (device_id, "newer metadata", 200),
            )
            con.execute(
                "INSERT INTO usage_events_daily(deviceId,dayIso,packageName,appName,firstSeen,"
                "lastSeen,totalTimeMs) VALUES(?,?,?,?,?,?,?)",
                (device_id, first_day, "com.example", "Example", 100, 100, 1000),
            )
        try:
            # Older device metadata must not suppress a previously unseen date.
            _merge_usage_devices(con, [record(
                updated_at=150,
                name="older metadata",
                days=[day(second_day, "FINAL", 150, 2000)],
            )])
            device = con.execute(
                "SELECT deviceName,updatedAt FROM usage_devices WHERE deviceId = ?", (device_id,)
            ).fetchone()
            assert (device["deviceName"], device["updatedAt"]) == ("newer metadata", 200)
            assert con.execute(
                "SELECT totalTimeMs FROM usage_events_daily WHERE deviceId=? AND dayIso=?",
                (device_id, second_day),
            ).fetchone()[0] == 2000

            # An OPEN snapshot cannot replace an existing FINAL day, even if newer.
            _merge_usage_devices(con, [record(
                updated_at=300,
                name="latest metadata",
                days=[day(first_day, "OPEN", 300, 3000)],
            )])
            assert con.execute(
                "SELECT totalTimeMs FROM usage_events_daily WHERE deviceId=? AND dayIso=?",
                (device_id, first_day),
            ).fetchone()[0] == 1000

            # A newer FINAL snapshot replaces that date only, preserving other dates.
            _merge_usage_devices(con, [record(
                updated_at=400,
                name="latest metadata",
                days=[day(first_day, "FINAL", 400, 4000)],
            )])
            totals = dict(con.execute(
                "SELECT dayIso,totalTimeMs FROM usage_events_daily WHERE deviceId=? ORDER BY dayIso",
                (device_id,),
            ).fetchall())
            assert totals == {first_day: 4000, second_day: 2000}
        finally:
            with con:
                con.execute("DELETE FROM usage_events_daily WHERE deviceId = ?", (device_id,))
                con.execute("DELETE FROM usage_devices WHERE deviceId = ?", (device_id,))

    def test_usage_metadata_empty_day_and_empty_device_round_trip(self, client):
        from app.services.backup_codec import build_usage_devices
        from app.services.usage_service import merge_android_usage_devices

        con = _db(client)
        device_id = "00000000-0000-4000-8000-00000000beef"
        empty_id = "00000000-0000-4000-8000-00000000feed"
        record = {
            "schemaVersion": 1,
            "deviceId": device_id,
            "deviceName": "Pixel",
            "platform": "android",
            "updatedAtEpochMillis": 500,
            "history": {
                "schemaVersion": 4,
                "trackingStartedOn": "2024-01-01",
                "backfillCompletedThrough": "2024-01-03",
                "days": [{
                    "date": "2024-01-02",
                    "zoneId": "Asia/Shanghai",
                    "state": "FINAL",
                    "collectedAtEpochMillis": 400,
                    "apps": [],
                }],
            },
        }
        with con:
            for target in (device_id, empty_id):
                con.execute("DELETE FROM usage_events_daily WHERE deviceId=?", (target,))
                con.execute("DELETE FROM usage_devices WHERE deviceId=?", (target,))
            con.execute(
                "INSERT INTO usage_devices(deviceId,deviceName,isLocal,updatedAt,platform,"
                "trackingStartedOn,backfillCompletedThrough) VALUES(?,?,0,?,?,NULL,NULL)",
                (empty_id, "Empty", 7, "web"),
            )
        try:
            merge_android_usage_devices(con, [record])
            exported = {item["deviceId"]: item for item in build_usage_devices(con)}
            assert empty_id in exported
            assert exported[empty_id]["history"]["days"] == []
            actual = exported[device_id]
            assert actual["platform"] == "android"
            assert actual["updatedAtEpochMillis"] == 500
            assert actual["history"] == record["history"]
            stored_day = con.execute(
                "SELECT zoneId,state,collectedAt FROM usage_days WHERE deviceId=? AND dayIso=?",
                (device_id, "2024-01-02"),
            ).fetchone()
            assert tuple(stored_day) == ("Asia/Shanghai", "FINAL", 400)
        finally:
            with con:
                for target in (device_id, empty_id):
                    con.execute("DELETE FROM usage_events_daily WHERE deviceId=?", (target,))
                    con.execute("DELETE FROM usage_devices WHERE deviceId=?", (target,))

    def test_record_conflict_copy_rolls_back_canonical_write(self, client):
        import sqlite3

        from app.services import record_sync

        con = _db(client)
        row_id = 910_001
        with con:
            con.execute("DELETE FROM flash_thoughts WHERE id = ?", (row_id,))
            con.execute(
                "INSERT INTO flash_thoughts(id,content,createdAt,updatedAt,pinned,deletedAt,"
                "sortOrder,categoryId,highlighted) VALUES(?,?,1,1,0,NULL,0,NULL,0)",
                (row_id, "local-before-conflict"),
            )
            con.execute(
                "CREATE TEMP TRIGGER fail_record_conflict_copy BEFORE INSERT ON flash_thoughts "
                "BEGIN SELECT RAISE(ABORT, 'injected conflict-copy failure'); END",
            )

        def thought_payload(content: str, updated_at: int) -> bytes:
            return record_sync._json_bytes({
                "content": content, "createdAt": 1, "updatedAt": updated_at,
                "pinned": False, "sortOrder": 0, "categoryName": None,
                "highlighted": False,
            })

        adapter = record_sync.RecordAdapter(con, "THOUGHTS")
        try:
            with pytest.raises(sqlite3.IntegrityError):
                adapter.apply_remote(
                    record_sync.SyncRecord("record", 2, 2, thought_payload("remote", 2)),
                    canonical_local_key=str(row_id),
                    preserve_local=record_sync.SyncRecord(
                        "record", 1, 1, thought_payload("local-before-conflict", 1),
                    ),
                )
            remaining = con.execute(
                "SELECT content,updatedAt FROM flash_thoughts WHERE id = ?", (row_id,),
            ).fetchone()
            assert remaining is not None
            assert (remaining["content"], remaining["updatedAt"]) == (
                "local-before-conflict", 1,
            )
        finally:
            with con:
                con.execute("DROP TRIGGER IF EXISTS fail_record_conflict_copy")
                con.execute("DELETE FROM flash_thoughts WHERE id = ?", (row_id,))

    @staticmethod
    def _response(status: int, body: bytes = b"", **headers):
        return type("Response", (), {
            "status_code": status,
            "content": body,
            "headers": {key.replace("_", "-"): value for key, value in headers.items()},
        })()

    def test_s3_conditional_get_and_put_forward_versions(self):
        from app.services.s3_client import S3Client

        client = S3Client({
            "endpointUrl": "https://s3.example.test",
            "remotePath": "DeskCubby",
            "s3Bucket": "desk-bucket",
            "s3PathStyle": True,
            "s3AccessKey": "access",
            "s3SecretKey": "secret",
            "s3Region": "us-east-1",
        })

        class FakeHttp:
            def __init__(self, responses):
                self.responses = list(responses)
                self.calls = []

            def request(self, method, url, *, headers=None, content=None):
                self.calls.append((method, url, headers or {}, content))
                return self.responses.pop(0)

        fake = FakeHttp([
            self._response(200, b"manifest", etag='"v1"'),
            self._response(201, b"", etag='"v2"'),
        ])
        client.http = fake
        assert client.get_blob(".manifest", expected_version='"v1"') == (b"manifest", '"v1"')
        assert fake.calls[0][2]["IF-MATCH"] == '"v1"'
        assert client.put_blob(".manifest", b"new", must_not_exist=True) == '"v2"'
        assert fake.calls[1][2]["IF-NONE-MATCH"] == "*"

    def test_webdav_conditional_get_and_put_forward_strong_etags(self):
        from app.services.webdav_client import WebDavClient

        client = WebDavClient({
            "endpointUrl": "https://dav.example.test/root",
            "remotePath": "DeskCubby",
        })

        class FakeHttp:
            def __init__(self, responses):
                self.responses = list(responses)
                self.calls = []

            def request(self, method, url, *, headers=None, content=None):
                self.calls.append((method, url, headers or {}, content))
                return self.responses.pop(0)

        fake = FakeHttp([
            self._response(200, b"manifest", etag='"dav-v1"'),
            self._response(204, b"", etag='"dav-v2"'),
        ])
        client.http = fake
        assert client.get_blob(".manifest", expected_version='"dav-v1"') == (
            b"manifest", '"dav-v1"',
        )
        assert fake.calls[0][2]["If-Match"] == '"dav-v1"'
        assert client.put_blob(".manifest", b"new", must_not_exist=True) == '"dav-v2"'
        assert fake.calls[1][2]["If-None-Match"] == "*"

    def test_agent_sse_contract_names_android_events(self):
        from app.routers.agent import _sse
        from app.services.agent_runtime import EMIT_TYPES, final_message_sync_id

        assert "started" in EMIT_TYPES and "tool_event" in EMIT_TYPES
        assert _sse("started", {"type": "started", "runId": "r1"}).startswith("event: started\n")
        assert _sse("tool_event", {"type": "tool_event"}).startswith("event: tool_event\n")
        assert final_message_sync_id("run-1") == "agent-final:run-1"

    def test_agent_run_rejects_invalid_or_reused_client_run_id(self, client):
        invalid = client.post("/api/agent/run", json={"runId": "not-a-uuid", "content": "hello"})
        assert invalid.status_code == 400
        assert invalid.json()["error"]["code"] == "invalid_run_id"

        con = _db(client)
        run_id = "00000000-0000-4000-8000-00000000a901"
        with con:
            con.execute(
                "INSERT INTO agent_runs(runId,conversationId,conversationTitle,userRequestSummary,"
                "modelConfigId,permissionMode,enabledSourcesJson,status,startedAt,completedAt) "
                "VALUES(?,NULL,'existing','existing','cfg','REQUIRE_APPROVAL','[]','FAILED',?,?)",
                (run_id, NOW_MS, NOW_MS),
            )
        try:
            reused = client.post("/api/agent/run", json={"runId": run_id, "content": "hello"})
            assert reused.status_code == 409
            assert reused.json()["error"]["code"] == "run_exists"
        finally:
            with con:
                con.execute("DELETE FROM agent_runs WHERE runId=?", (run_id,))

    def test_agent_prompt_includes_android_core_metadata_and_both_custom_prompts(self, client):
        from app.services.agent_runtime import _build_system_prompt
        from app.services.settings_store import load_settings

        con = _db(client)
        settings = {**load_settings(con), "agentPrompt": "global-agent-style"}
        prompt = _build_system_prompt(
            con,
            {"systemPrompt": "model-specific-style"},
            settings,
            ["thoughts", "date_records"],
        )
        assert "Hard rules:" in prompt
        assert "id=thoughts" in prompt and "id=date_records" in prompt
        assert "id=diary;" not in prompt
        assert "global-agent-style" in prompt
        assert "model-specific-style" in prompt

    def test_legacy_category_only_cloud_contents_migrate_to_visible_parents(self):
        from app.services.settings_store import _normalize_cloud_sync_configs

        configs = _normalize_cloud_sync_configs([{
            "id": "legacy",
            "selectedContents": [
                "THOUGHT_CATEGORIES", "POETRY_CATEGORIES", "DATE_RECORDS", "UNKNOWN",
            ],
        }])
        assert configs[0]["selectedContents"] == ["THOUGHTS", "DATE_RECORDS", "POEMS"]

    def test_browser_settings_show_ai_key_but_redact_cloud_credentials(self):
        from app.services.settings_store import _redact

        projected = _redact({
            "aiConfigs": [{"id": "ai", "apiKey": "plain-ai-key"}],
            "cloudSyncConfigs": [{
                "id": "cloud", "webDavPassword": "dav-secret", "s3SecretKey": "s3-secret",
            }],
        })
        assert projected["aiConfigs"][0]["apiKey"] == "plain-ai-key"
        assert projected["cloudSyncConfigs"][0]["webDavPassword"] == ""
        assert projected["cloudSyncConfigs"][0]["s3SecretKey"] == ""

    def test_ai_key_can_be_explicitly_cleared_like_android_plaintext_field(self):
        import sqlite3

        from app.services.settings_store import load_settings, update_settings

        con = sqlite3.connect(":memory:")
        con.row_factory = sqlite3.Row
        con.execute("CREATE TABLE app_settings_kv(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        base = {
            "id": "clear-key", "name": "Model", "type": "TEXT", "endpointUrl": "https://example.test/v1",
            "model": "model", "temperature": 0.7, "allowInsecureHttp": False, "systemPrompt": "",
            "apiKey": "plain-key", "supportsToolCalling": True, "enabled": True,
        }
        update_settings(con, {"aiConfigs": [base]})
        update_settings(con, {"aiConfigs": [{**base, "apiKey": ""}]})
        assert load_settings(con)["aiConfigs"][0]["apiKey"] == ""
        con.close()
