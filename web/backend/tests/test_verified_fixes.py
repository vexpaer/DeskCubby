"""Regression tests for verified backend defects:

1. GET /api/settings/data-usage must not 500 (services.data_usage import).
2. Cloud-secret fields are redacted from GET /api/settings; desktopWidgetConfigs
   are bounded like AppModels.kt (legacy home-module ids kept as-is here).
3. dc-media.json updates always commit to the short canonical name even when the
   read fell back to a previous/pending/legacy sidecar; roundToInt-style rounding;
   org.json optDouble-style numeric-string coercion.
4. Backup exports drop settings keys Android's encodeSettings never writes.
5. Failed-login lockout (8 tries -> 15 min, 429 rate_limited) and Secure-cookie
   honoring X-Forwarded-Proto.
6. POST /api/diary/rename: sanitized atomic rename, 409 on duplicates, index update.
7. Room v16 additive indices exist after init_db.
"""
from __future__ import annotations

import json
import os
import shutil
import tempfile

# Must be configured before any app module import reads it. When the full suite
# runs, whichever module imports app first wins; these tests only rely on the
# config-relative paths, never on this specific directory.
os.environ["DESKCUBBY_DATA_DIR"] = "/tmp/dc-vf2"

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app.core import security  # noqa: E402
from app.main import app  # noqa: E402


@pytest.fixture(scope="module")
def client():
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture(scope="module", autouse=True)
def _no_access_password():
    """Keep the module order-independent: never inherit (or leave behind) an
    access password or login-lockout entries in the shared data dir."""
    from app.core.db import connect, init_db

    def _reset():
        from app.core.config import DIARY_DIR
        from app.core.db import connect, init_db
        from app.services.settings_store import (
            DEFAULT_DESKTOP_WIDGET_CONFIGS,
            update_settings,
        )

        init_db()  # no-op when another module already started the app
        con = connect()
        security.disable_password(con)
        # Drop artifacts from previous runs so reruns stay deterministic.
        con.execute("DELETE FROM diary_index WHERE uri LIKE 'vf-%'")
        con.commit()
        update_settings(con, {
            "cloudSyncConfigs": [],
            "desktopWidgetConfigs": DEFAULT_DESKTOP_WIDGET_CONFIGS,
        })
        con.close()
        for stale in DIARY_DIR.glob("vf-*"):
            stale.unlink(missing_ok=True)

    _reset()
    security._failed_logins.clear()
    yield
    _reset()
    security._failed_logins.clear()


class TestDataUsageEndpoint:
    def test_get_settings_data_usage_returns_200(self, client):
        response = client.get("/api/settings/data-usage")
        assert response.status_code == 200, response.text
        body = response.json()
        assert set(body) >= {"diaryMB", "mediaMB", "notesMB", "booksMB"}
        assert all(isinstance(v, (int, float)) and v >= 0 for v in body.values())


class TestSettingsRedaction:
    SECRET_FIELDS = ("password", "accessKey", "secretKey", "sessionToken",
                     "webDavPassword", "awsSecretKey")

    def test_cloud_sync_config_secrets_never_leave_the_server(self, client):
        payload = {field: f"leak-{field}" for field in self.SECRET_FIELDS}
        put = client.put("/api/settings", json={
            "cloudSyncConfigs": [{"id": "cfg-redact", "name": "NAS", **payload}],
        })
        assert put.status_code == 200, put.text
        got = client.get("/api/settings")
        assert got.status_code == 200
        entry = next(
            c for c in got.json()["cloudSyncConfigs"] if c["id"] == "cfg-redact"
        )
        for field in self.SECRET_FIELDS:
            assert entry[field] == "", f"{field} must be redacted"
            assert f"leak-{field}" not in got.text
        # The server-side store keeps the values (only reads are redacted).
        from app.services.settings_store import load_settings

        stored = load_settings(client.app.state.db)["cloudSyncConfigs"]
        match = next(c for c in stored if c["id"] == "cfg-redact")
        assert match["password"] == "leak-password"
        assert match["webDavPassword"] == "leak-webDavPassword"

    def test_desktop_widget_configs_bounded_like_appmodels(self, client):
        put = client.put("/api/settings", json={"desktopWidgetConfigs": [
            {
                "id": "vf-w1", "name": "越界", "widthCells": 99, "heightCells": 0,
                "backgroundOpacityPercent": 250, "textScalePercent": 10,
                "surfaceScalePercent": 500, "appIconScalePercent": 7,
                "usageRangeDays": 14, "contentType": "MAGIC",
                "homeModuleId": "not_a_module",
            },
            {"id": "vf-w2", "name": "旧云同步", "homeModuleId": "cloud_sync_now"},
            {"id": "vf-w3", "name": "强制上传", "homeModuleId": "cloud_sync_force"},
        ]})
        assert put.status_code == 200, put.text
        stored = {c["id"]: c for c in client.get("/api/widgets/configs").json()}
        w1 = stored["vf-w1"]
        assert w1["widthCells"] == 6 and w1["heightCells"] == 1
        assert w1["backgroundOpacityPercent"] == 100
        assert w1["textScalePercent"] == 75
        # 500 is above the 70..100 window: clamps to the upper bound, like Android.
        assert w1["surfaceScalePercent"] == 100
        assert w1["appIconScalePercent"] == 50
        assert w1["usageRangeDays"] == 7
        assert w1["contentType"] == "HOME_MODULE"
        assert w1["homeModuleId"] == "today"
        # HomePage renders the two legacy ids directly: kept AS-IS in settings.
        assert stored["vf-w2"]["homeModuleId"] == "cloud_sync_now"
        assert stored["vf-w3"]["homeModuleId"] == "cloud_sync_force"


class TestMediaMetaShortNameCommit:
    def test_update_commits_to_canonical_name_after_legacy_fallback(self):
        from pathlib import Path

        from app.services import media_meta as mm

        media_dir = Path(tempfile.mkdtemp(prefix="dc-media-"))
        try:
            legacy = media_dir / mm.LEGACY_MEDIA_META_FILE_NAME
            original_doc = {"entries": {"photo.jpg": {"energyKj": 100}}}
            legacy.write_text(json.dumps(original_doc), encoding="utf-8")

            mm.update_media_meta_entry(
                "PHOTO.jpg", lambda e: {**e, "place": "家里"}, media_dir=media_dir
            )
            # DiaryFileRepository.kt: always commit future updates to the shorter name.
            committed = json.loads(
                (media_dir / mm.MEDIA_META_FILE_NAME).read_text(encoding="utf-8")
            )
            assert committed["version"] == mm.CURRENT_VERSION
            assert committed["entries"]["photo.jpg"]["place"] == "家里"
            assert committed["entries"]["photo.jpg"]["energyKj"] == 100
            assert legacy.read_text(encoding="utf-8") == json.dumps(original_doc)

            raw, read_path = mm.read_media_meta_raw(media_dir)
            assert read_path.name == mm.MEDIA_META_FILE_NAME
            assert json.loads(raw)["version"] == mm.CURRENT_VERSION
        finally:
            shutil.rmtree(media_dir, ignore_errors=True)

    def test_update_repairs_corrupt_canonical_file_from_previous_copy(self):
        from pathlib import Path

        from app.services import media_meta as mm

        media_dir = Path(tempfile.mkdtemp(prefix="dc-media-"))
        try:
            previous = media_dir / mm.MEDIA_META_PREVIOUS_FILE_NAME
            previous.write_text(
                json.dumps({"version": 2, "entries": {"p.jpg": {"energyKj": 5}}}),
                encoding="utf-8",
            )
            (media_dir / mm.MEDIA_META_FILE_NAME).write_bytes(b"{ corrupt ")

            original, _path = mm.read_media_meta_raw(media_dir)
            assert original == '{"version": 2, "entries": {"p.jpg": {"energyKj": 5}}}'
            mm.update_media_meta_entry(
                "p.jpg", lambda e: {**e, "place": "办公室"}, media_dir=media_dir
            )
            repaired = json.loads(
                (media_dir / mm.MEDIA_META_FILE_NAME).read_text(encoding="utf-8")
            )
            assert repaired["entries"]["p.jpg"]["place"] == "办公室"
            assert repaired["entries"]["p.jpg"]["energyKj"] == 5
        finally:
            shutil.rmtree(media_dir, ignore_errors=True)

    def test_bounded_int_rounds_half_away_from_zero(self):
        from app.services.media_meta import MAX_MEAL_ENERGY_KJ, _bounded_int

        # Python's round() is banker's rounding (round(2.5) == 2); Kotlin roundToInt
        # rounds ties away from zero.
        assert int(round(2.5)) == 2  # documents the old wrong behavior
        assert _bounded_int({"v": 2.5}, "v") == 3
        assert _bounded_int({"v": 3.5}, "v") == 4
        assert _bounded_int({"v": 0.5}, "v") == 1
        assert _bounded_int({"v": 1.4}, "v") == 1
        assert _bounded_int({"v": 1}, "v") == 1
        assert _bounded_int({"v": "12"}, "v") == 12
        assert _bounded_int({"v": None}, "v") is None
        from pytest import raises

        with raises(Exception):
            _bounded_int({"v": MAX_MEAL_ENERGY_KJ + 1}, "v")

    def test_finite_double_coerces_numeric_strings(self):
        from app.services.media_meta import _finite_double

        assert _finite_double("1.5") == 1.5
        assert _finite_double(" 2 ") == 2.0
        assert _finite_double(-0.25) == -0.25
        assert _finite_double(3) == 3.0
        for bad in ("abc", "", True, [1], {"x": 1}):
            assert _finite_double(bad) is None, bad


class TestBackupSanitizeSettings:
    SERVER_LOCAL_KEYS = (
        "backupTreeUri",
        "lastThoughtPageKey",
        "navigationIntroAcknowledged",
        "orientationPreference",
        "structuredAutoRecordSleepWake",
        "tutorialAcknowledgedPages",
    )

    def test_export_drops_keys_android_encodesettings_never_writes(self, client):
        from app.services.settings_store import load_settings

        live = load_settings(client.app.state.db)
        for key in self.SERVER_LOCAL_KEYS:
            assert key in live, f"{key} should exist server-side"

        export = client.get("/api/backup/export")
        assert export.status_code == 200, export.text
        settings_section = export.json()["settings"]
        text = json.dumps(settings_section)
        for key in self.SERVER_LOCAL_KEYS:
            assert key not in settings_section, key
            assert key not in text
        # Portable settings still travel.
        assert settings_section["visualStyle"]
        assert "cloudSyncConfigs" in settings_section
        assert settings_section["cloudSyncEnabled"] is False


class TestLoginRateLimiting:
    PASSWORD = "correct-horse-battery"

    def _ensure_password_enabled(self, client):
        if not client.get("/api/auth/status").json()["enabled"]:
            resp = client.post("/api/auth/set-password", json={"password": self.PASSWORD})
            assert resp.status_code == 200, resp.text

    def test_eight_failures_lock_host_with_429(self, client):
        self._ensure_password_enabled(client)
        security._failed_logins.clear()
        try:
            for i in range(security.FAILED_LOGIN_LIMIT - 1):
                resp = client.post("/api/auth/login", json={"password": "wrong"})
                assert resp.status_code == 401, (i, resp.status_code)
            eighth = client.post("/api/auth/login", json={"password": "wrong"})
            assert eighth.status_code == 429
            assert eighth.json()["error"]["code"] == "rate_limited"
            locked = client.post("/api/auth/login", json={"password": self.PASSWORD})
            assert locked.status_code == 429  # even the correct password is refused
        finally:
            security._failed_logins.clear()

    def test_successful_login_clears_failure_counter(self, client, monkeypatch):
        self._ensure_password_enabled(client)
        security._failed_logins.clear()
        monkeypatch.setattr(security, "_host_key", lambda request: "dc-clear-host")
        for _ in range(security.FAILED_LOGIN_LIMIT - 1):
            security.register_login_failure("dc-clear-host")
        ok = client.post("/api/auth/login", json={"password": self.PASSWORD})
        assert ok.status_code == 200, ok.text
        assert "dc-clear-host" not in security._failed_logins
        # ...and the cleared counter means failures start counting from zero again.
        resp = client.post("/api/auth/login", json={"password": "nope"})
        assert resp.status_code == 401

    def test_cookie_secure_flag_honors_forwarded_proto(self, client):
        self._ensure_password_enabled(client)
        security._failed_logins.clear()
        forwarded = client.post(
            "/api/auth/login",
            json={"password": self.PASSWORD},
            headers={"X-Forwarded-Proto": "https"},
        )
        assert forwarded.status_code == 200
        assert "secure" in forwarded.headers["set-cookie"].lower()
        direct = client.post("/api/auth/login", json={"password": self.PASSWORD})
        assert direct.status_code == 200
        assert "secure" not in direct.headers["set-cookie"].lower()


class TestDiaryRenameEndpoint:
    def test_rename_updates_file_and_index_then_conflicts_on_duplicate(self, client):
        first = client.post("/api/diary/documents", json={"name": "vf-rename-me", "template": False})
        assert first.status_code == 200, first.text
        original_name = first.json()["name"]
        content = first.json()["content"]

        renamed = client.post(
            "/api/diary/rename", json={"name": original_name, "newName": "vf-renamed diary"}
        )
        assert renamed.status_code == 200, renamed.text
        body = renamed.json()
        assert body["name"] == "vf-renamed diary.md"
        assert body["uri"] == "vf-renamed diary.md"
        assert body["content"] == content

        rows = {
            row["uri"]: row["name"]
            for row in client.app.state.db.execute(
                "SELECT uri, name FROM diary_index").fetchall()
        }
        assert rows.get("vf-renamed diary.md") == "vf-renamed diary.md"
        assert original_name not in rows

        second = client.post("/api/diary/documents", json={"name": "vf-other-doc", "template": False})
        assert second.status_code == 200

        duplicate = client.post(
            "/api/diary/rename", json={"name": "vf-other-doc.md", "newName": "vf-renamed diary"}
        )
        assert duplicate.status_code == 409
        assert duplicate.json()["error"]["code"] == "duplicate_name"

        invalid = client.post(
            "/api/diary/rename", json={"name": "vf-other-doc.md", "newName": "../escape"})
        assert invalid.status_code == 400
        slash = client.post(
            "/api/diary/rename", json={"name": "vf-other-doc.md", "newName": "a/b"})
        assert slash.status_code == 400

        missing = client.post(
            "/api/diary/rename", json={"name": "vf-missing.md", "newName": "whatever"})
        assert missing.status_code == 404


class TestRoomV16Indices:
    def test_additive_indices_exist_after_init_db(self, client):
        names = {
            row["name"]
            for row in client.app.state.db.execute(
                "SELECT name FROM sqlite_master WHERE type='index'").fetchall()
        }
        assert "idx_ai_attachments_uri" in names
        assert "idx_agent_runs_started" in names
        assert "idx_sro_journal_day" in names

    def test_indices_are_recreated_on_existing_databases(self, client):
        con = client.app.state.db
        dropped = []
        for index in ("idx_ai_attachments_uri", "idx_agent_runs_started", "idx_sro_journal_day"):
            con.execute(f"DROP INDEX IF EXISTS {index}")
            dropped.append(index)
        con.commit()
        con.close()
        # A fresh connection + init_db simulates restarting against an old DB file.
        from app.core.db import connect, init_db

        init_db()
        fresh = connect()
        try:
            names = {
                row["name"]
                for row in fresh.execute(
                    "SELECT name FROM sqlite_master WHERE type='index'").fetchall()
            }
            for index in dropped:
                assert index in names
        finally:
            fresh.close()
