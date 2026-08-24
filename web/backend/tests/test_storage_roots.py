"""Local desktop storage-root selection and server-mode isolation."""
from __future__ import annotations

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core import config, security
from app.core.errors import ApiError
from app.main import app
from app.services import storage_roots


@pytest.fixture()
def isolated_roots(tmp_path, monkeypatch):
    old_diary = config.storage_root_override("diary")
    old_media = config.storage_root_override("media")
    monkeypatch.setattr(config, "STORAGE_ROOTS_FILE", tmp_path / "private" / "storage-roots.json")
    monkeypatch.setattr(config, "LOCAL_DESKTOP_MODE", True)
    config.apply_storage_root_override("diary", None)
    config.apply_storage_root_override("media", None)
    try:
        yield tmp_path
    finally:
        config.apply_storage_root_override("diary", old_diary)
        config.apply_storage_root_override("media", old_media)


def test_runtime_diary_root_switches_without_restart_and_persists(isolated_roots):
    selected = isolated_roots / "my diary"
    selected.mkdir()

    saved = storage_roots.set_storage_root("diary", str(selected))

    assert saved["path"] == str(selected.resolve())
    assert config.DIARY_DIR.resolve() == selected.resolve()
    assert (config.DIARY_DIR / "today.md").parent == selected.resolve()
    assert (selected / "today.md").relative_to(config.DIARY_DIR).as_posix() == "today.md"
    assert config.DIARY_TRASH_DIR.resolve() == (selected / ".trash").resolve()
    assert config.STRUCTURED_DIR.resolve() == (selected / ".deskcubby").resolve()
    persisted = json.loads(config.STORAGE_ROOTS_FILE.read_text(encoding="utf-8"))
    assert persisted == {"version": 1, "roots": {"diary": str(selected.resolve())}}


def test_storage_root_validation_rejects_relative_file_and_filesystem_root(isolated_roots):
    regular_file = isolated_roots / "not-a-folder"
    regular_file.write_text("x", encoding="utf-8")
    for value, code in [
        ("relative/path", "invalid_storage_path"),
        (str(regular_file), "storage_not_folder"),
        (str(Path("/").resolve()), "storage_root_too_broad"),
    ]:
        with pytest.raises(ApiError) as raised:
            storage_roots.validate_directory(value)
        assert raised.value.code == code


def test_managed_subfolders_cannot_be_symlinked_outside_selected_root(isolated_roots):
    selected = isolated_roots / "diary-with-link"
    outside = isolated_roots / "outside"
    selected.mkdir()
    outside.mkdir()
    (selected / ".deskcubby").symlink_to(outside, target_is_directory=True)
    with pytest.raises(ApiError) as raised:
        storage_roots.set_storage_root("diary", str(selected))
    assert raised.value.code == "storage_unsafe_symlink"


def test_loopback_api_can_change_media_but_forwarded_requests_cannot(isolated_roots):
    selected = isolated_roots / "media"
    selected.mkdir()
    with TestClient(app, base_url="http://127.0.0.1:8787", client=("127.0.0.1", 50123)) as client:
        security.disable_password(client.app.state.db)
        info = client.get("/api/storage/roots")
        assert info.status_code == 200
        assert info.json()["canConfigure"] is True

        changed = client.put("/api/storage/root", json={"kind": "media", "path": str(selected)})
        assert changed.status_code == 200, changed.text
        assert changed.json()["path"] == str(selected.resolve())
        assert config.MEDIA_DIR.resolve() == selected.resolve()

        blocked = client.put(
            "/api/storage/root",
            headers={"X-Forwarded-For": "203.0.113.7"},
            json={"kind": "media", "path": str(selected)},
        )
        assert blocked.status_code == 403
        assert blocked.json()["error"]["code"] == "local_storage_only"

        rebound = client.put(
            "/api/storage/root",
            headers={"Host": "attacker.example:8787"},
            json={"kind": "media", "path": str(selected)},
        )
        assert rebound.status_code == 403


def test_diary_api_writes_to_new_root_immediately_and_rescans(isolated_roots):
    selected = isolated_roots / "live-diary"
    selected.mkdir()
    with TestClient(app, base_url="http://127.0.0.1:8787", client=("127.0.0.1", 50125)) as client:
        security.disable_password(client.app.state.db)
        changed = client.put("/api/storage/root", json={"kind": "diary", "path": str(selected)})
        assert changed.status_code == 200, changed.text
        created = client.post(
            "/api/diary/documents",
            json={"name": "storage-root-live.md", "template": False},
        )
        assert created.status_code == 200, created.text
        assert (selected / created.json()["name"]).is_file()
        listed = client.get("/api/diary/documents")
        assert any(row["name"] == created.json()["name"] for row in listed.json())
        restored = client.put("/api/storage/root", json={"kind": "diary", "path": None})
        assert restored.status_code == 200, restored.text
        assert restored.json()["isDefault"] is True


def test_health_marker_stays_public_with_access_password(isolated_roots):
    with TestClient(app, base_url="http://127.0.0.1:8787", client=("127.0.0.1", 50124)) as client:
        security.set_password(client.app.state.db, "test-password")
        try:
            response = client.get("/api/healthz")
            assert response.status_code == 200
            assert response.json() == {"ok": True, "app": "deskcubby"}
        finally:
            security.disable_password(client.app.state.db)
