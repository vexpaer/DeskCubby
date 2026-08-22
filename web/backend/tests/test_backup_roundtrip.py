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
            "INSERT INTO usage_devices(deviceId, deviceName, isLocal, updatedAt) VALUES('d1', '手机', 0, ?)",
            (NOW_MS,))
        for day in ("2024-01-01", "2024-01-02"):
            con.execute(
                "INSERT INTO usage_events_daily(deviceId, dayIso, packageName, appName, firstSeen, lastSeen, totalTimeMs) "
                "VALUES('d1', ?, 'com.example.app', 'Example', ?, ?, 60000)", (day, NOW_MS, NOW_MS))
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

        # The agentChats payload decodes to one conversation/message/attachment.
        payload = json.loads(base64.b64decode(document["agentChats"]))
        assert len(payload["conversations"]) == 1
        assert len(payload["messages"]) == 1
        assert len(payload["attachments"]) == 1
        assert payload["messages"][0]["conversationSyncId"] == payload["conversations"][0]["syncId"]

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
        }
        assert [r["id"] for r in rows["thoughts"]] == [1, 2], "commit must replace, not append"
        assert all(r["id"] != 999 for r in rows["thoughts"])
        assert [r["id"] for r in rows["poems"]] == [1]
        assert [r["url"] for r in rows["favorites"]] == ["https://example.com/a"]
        assert rows["history"] >= 2  # non-favorite history preserved
        assert rows["usage_days"] == 2
        assert (rows["conversations"], rows["messages"], rows["attachments"]) == (1, 1, 1)

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
            "diary/2024-01-01.md": {"sha256": sha256_bytes(b"a"), "size": 1, "lastModified": 1},
        }
        decoded = decode_manifest(encode_manifest(entries))
        assert decoded == entries
        assert object_storage_name("diary/x.md", sha256_bytes(b"x")) == object_storage_name(
            "diary/x.md", sha256_bytes(b"x"))
        assert object_storage_name("a.md", "0" * 64) != object_storage_name("b.md", "0" * 64)

    def test_undo_snapshot_keeps_latest_only(self, client):
        from app.services.cloudsync_engine import (
            load_undo_snapshot,
            save_undo_snapshot,
        )

        con = _db(client)
        save_undo_snapshot(con, {"configId": "c", "finishedAtMs": 1, "entries": [
            {"key": "diary/a.md", "path": "workspace/diary/a.md", "action": "create"}]})
        first = load_undo_snapshot(con)
        assert first and first["entries"][0]["key"] == "diary/a.md"
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
            "diary/2024-01-01.md", overwritten,
            {"sha256": eng.sha256_bytes(overwritten)},
            target, undo, conflict_copy=True,
        )
        assert outcome == "conflict-copy"
        created = DIARY_DIR / "undo-created.md"
        assert eng.apply_remote(
            "diary/undo-created.md", b"remote bytes\n",
            {"sha256": eng.sha256_bytes(b"remote bytes\n")},
            created, undo, conflict_copy=False,
        ) == "applied"

        # The local edit survives in the deterministic conflict copy; the remote
        # version is applied without silent overwrite.
        assert target.read_text(encoding="utf-8") == overwritten.decode()
        copies = list(DIARY_DIR.glob("2024-01-01.md.conflict-*"))
        assert len(copies) == 1 and copies[0].read_bytes() == original.encode()

        undo.commit("cfg", 1_234)
        # Undo restores the overwritten diary, deletes the created file and
        # removes this round's conflict copy — three entries, zero residue.
        restored = eng.undo_last_sync(_App())
        assert restored == 3
        assert target.read_text(encoding="utf-8") == original, "undo restores originals"
        assert not created.exists(), "undo removes files created this round"
        assert not list(DIARY_DIR.glob("*conflict-*"))
        assert eng.load_undo_snapshot(con) is None  # one-shot: latest snapshot only
