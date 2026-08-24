"""Smoke tests for the diary / media / meals / notes / structured backend.

Exercises the full API surface over a throwaway data dir (/tmp/dc-b1) with a real
filesystem workspace: diary create/list/save + 409 conflict, image upload ->
meal calendar -> photo-meta round-trip, notes folder/file/rename, structured
day-boundary config + record write + reindex, trash flow, thumbnails and PNG export.
"""
from __future__ import annotations

import io
import os
import shutil
import uuid

# The data dir must be configured before any app module import reads it.
os.environ["DESKCUBBY_DATA_DIR"] = "/tmp/dc-b1"
if os.path.isdir("/tmp/dc-b1"):
    shutil.rmtree("/tmp/dc-b1")

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from PIL import Image  # noqa: E402

from app.main import app  # noqa: E402
from app.services.diary_files import (  # noqa: E402
    extract_date,
    meal_category_from_caption,
    meal_category_from_file_name,
    word_count,
)
from app.services.structured_records import (  # noqa: E402
    format_duration,
    format_number,
    normalize_value,
    parse_occurrences,
    replace_value,
)


@pytest.fixture(scope="module")
def client():
    with TestClient(app) as test_client:
        yield test_client


def _png_bytes(width: int = 64, height: int = 48) -> bytes:
    img = Image.new("RGB", (width, height))
    for x in range(width):
        for y in range(height):
            img.putpixel((x, y), (x * 4 % 256, y * 5 % 256, 128))
    buffer = io.BytesIO()
    img.save(buffer, format="PNG")
    return buffer.getvalue()


class TestTextSemantics:
    def test_word_count_matches_diary_text_utils(self):
        assert word_count("hello world 你好") == 4  # 2 latin words + 2 han chars
        assert word_count("做了 20 个俯卧撑") == 7  # 1 latin token + 6 han chars
        assert word_count("it's a well-known fact") == 4
        assert word_count("") == 0

    def test_extract_date_pattern_then_regex_then_mtime(self):
        from datetime import date, datetime

        assert extract_date("2024-03-05.md", 0, "yyyy-MM-dd") == date(2024, 3, 5)
        assert extract_date("diary on 2023-07-11 note.md", 0, None) == date(2023, 7, 11)
        # mtime fallback resolves in the system-local zone (Android: ZoneId.systemDefault()).
        assert extract_date("random.md", 1_700_000_000_000, None) == datetime.fromtimestamp(
            1_700_000_000
        ).date()

    def test_meal_category_detection(self):
        assert meal_category_from_caption("午餐")["key"] == "lunch"
        assert meal_category_from_caption("早餐-800kJ")["key"] == "breakfast"
        assert meal_category_from_caption("late-night snack")["key"] == "late_snack"
        assert meal_category_from_file_name("<2024-01-01_dinner_01.jpg>")["key"] == "dinner"
        assert meal_category_from_file_name("<photo.png>") is None

    def test_structured_normalizer(self):
        value, error = normalize_value("number", "20 次")
        assert error is None and value.number == 20 and value.display == "20"
        value, _ = normalize_value("time", "12:36")
        assert value.minutes == 756 and value.display == "12:36"
        value, _ = normalize_value("duration", "1h30m")
        assert value.seconds == 5400 and value.display == "1:30"
        _, error = normalize_value("word", "bad <!-- injection")
        assert error
        assert format_number(2.0) == "2" and format_number(2.345) == "2.35"
        assert format_duration(3660) == "1:01"

    def test_markdown_protocol_parse_and_replace(self):
        content = "做了 <!--dc:f_pushups-->20<!--dc:/f_pushups--> 个俯卧撑。\n```md\n<!--dc:f_x-->1<!--dc:/f_x-->\n```\n"
        occurrences = parse_occurrences(content)
        assert [o["fieldId"] for o in occurrences] == ["f_pushups"]  # fenced block skipped
        replaced = replace_value(content, occurrences[0], "25")
        assert "<!--dc:f_pushups-->25<!--dc:/f_pushups-->" in replaced


class TestDiaryFlow:
    def test_create_list_save_conflict(self, client):
        today_iso = __import__("datetime").date.today().isoformat()
        created = client.post("/api/diary/documents", json={"dateIso": today_iso})
        assert created.status_code == 200, created.text
        doc = created.json()
        assert doc["name"].startswith(today_iso) and doc["name"].endswith(".md")
        assert "# " in doc["content"]  # markdownTemplate "# {title}\n\n" applied
        sha = doc["sha256"]

        listed = client.get("/api/diary/documents").json()
        assert any(item["uri"] == doc["name"] for item in listed)

        conflict = client.put(
            "/api/diary/document",
            json={"name": doc["name"], "content": "wrong base", "previousSha256": "deadbeef"},
        )
        assert conflict.status_code == 409
        payload = conflict.json()
        assert payload["currentSha256"] == sha
        assert payload["content"] == doc["content"]
        assert "lastModified" in payload

        saved = client.put(
            "/api/diary/document",
            json={"name": doc["name"], "content": doc["content"] + "\n更新内容\n",
                  "previousSha256": sha},
        )
        assert saved.status_code == 200
        assert saved.json()["sha256"] != sha

    def test_document_get_and_stats(self, client):
        listed = client.get("/api/diary/documents").json()
        name = listed[0]["uri"]
        fetched = client.get("/api/diary/document", params={"name": name})
        assert fetched.status_code == 200
        stats = client.get("/api/diary/stats").json()
        assert stats["totalDocuments"] >= 1 and set(stats) >= {
            "totalDocuments", "totalWords", "streakDays", "monthDocuments",
        }
        recent = client.get("/api/diary/recent", params={"limit": 5}).json()
        assert isinstance(recent, list) and len(recent) <= 5
        random_doc = client.get("/api/diary/random")
        assert random_doc.status_code == 200

    def test_trash_restore_permanent(self, client):
        created = client.post(
            "/api/diary/documents",
            json={"name": f"{__import__('datetime').date.today().isoformat()} 抛弃.md"},
        )
        assert created.status_code == 200
        name = created.json()["name"]
        deleted = client.delete("/api/diary/document", params={"name": name})
        assert deleted.status_code == 200
        trash = client.get("/api/diary/trash").json()
        item = next(t for t in trash if t["originalName"] == name)
        assert item["deletedAt"] > 0 and "__" in item["uri"]
        restored = client.post("/api/diary/trash/restore", json={"name": item["uri"]})
        assert restored.status_code == 200
        assert client.get("/api/diary/document", params={"name": name}).status_code == 200
        # delete again then purge permanently
        client.delete("/api/diary/document", params={"name": name})
        trash = client.get("/api/diary/trash").json()
        item = next(t for t in trash if t["originalName"] == name)
        purged = client.delete("/api/diary/trash/item", params={"name": item["uri"]})
        assert purged.status_code == 200
        assert all(t["originalName"] != name for t in client.get("/api/diary/trash").json())


class TestMediaMeals:
    def test_upload_calendar_photo_meta_roundtrip(self, client):
        today_iso = __import__("datetime").date.today().isoformat()
        diary = client.post("/api/diary/documents", json={"dateIso": today_iso}).json()

        upload = client.post(
            "/api/media/upload",
            params={"category": "午餐"},
            files={"file": ("phone-photo.png", _png_bytes(), "image/png")},
        )
        assert upload.status_code == 200, upload.text
        media = upload.json()
        assert media["fileName"].endswith(".jpg") or media["fileName"].endswith(".png")
        assert media["markdown"].startswith("![午餐](<")

        saved = client.put(
            "/api/diary/document",
            json={
                "name": diary["name"],
                "content": diary["content"] + "\n" + media["markdown"] + "\n",
                "previousSha256": diary["sha256"],
            },
        )
        assert saved.status_code == 200

        calendar = client.get(
            "/api/meals/calendar", params={"from": today_iso, "to": today_iso}
        ).json()
        assert calendar, "meal calendar should contain the uploaded photo"
        day = calendar[0]
        assert day["dateIso"] == today_iso
        photo = day["photos"][0]
        assert photo["category"] == "lunch"
        assert photo["fileName"] == media["fileName"].lower()
        assert photo["energyKj"] is None or isinstance(photo["energyKj"], int)

        put = client.put(
            "/api/meals/photo-meta",
            json={"fileName": media["fileName"], "energyKj": 800, "place": "家里",
                  "dateIso": today_iso, "note": "好吃"},
        )
        assert put.status_code == 200, put.text
        got = client.get("/api/meals/photo-meta", params={"file": media["fileName"]}).json()
        assert got["entry"]["energyKj"] == 800
        assert got["entry"]["place"] == "家里"

        calendar = client.get(
            "/api/meals/calendar", params={"from": today_iso, "to": today_iso}
        ).json()
        assert calendar[0]["photos"][0]["energyKj"] == 800  # sidecar wins over caption
        assert calendar[0]["photos"][0]["locationName"] == "家里"

        # dc-media.json v2 shape on disk: version 2, lower-cased key.
        import json as _json
        from app.core.config import MEDIA_DIR

        raw = (MEDIA_DIR / "dc-media.json").read_text(encoding="utf-8")
        parsed = _json.loads(raw)
        assert parsed["version"] == 2
        assert media["fileName"].lower() in parsed["entries"]

        categories_filter = client.get(
            "/api/meals/calendar", params={"categories": "dinner"}
        ).json()
        assert categories_filter == []

    def test_media_file_and_thumbs(self, client):
        upload = client.post(
            "/api/media/upload",
            params={"category": "水果"},
            files={"file": ("fruit.png", _png_bytes(32, 32), "image/png")},
        ).json()
        served = client.get("/api/media/file", params={"path": upload["fileName"]})
        assert served.status_code == 200
        assert served.headers["content-type"].startswith("image/")
        thumb = client.get("/api/media/file", params={"path": upload["fileName"], "size": "thumb"})
        assert thumb.status_code == 200
        thumbs = client.get("/api/media/thumbs", params={"paths": upload["fileName"]}).json()
        assert thumbs["thumbs"][upload["fileName"]].startswith("data:image/jpeg;base64,")
        traversal = client.get("/api/media/file", params={"path": "../deskcubby.db"})
        assert traversal.status_code == 400

    def test_meal_export_png(self, client):
        today_iso = __import__("datetime").date.today().isoformat()
        response = client.get(
            "/api/diary/export/meal-calendar.png",
            params={"start": today_iso, "end": today_iso},
        )
        assert response.status_code == 200, response.text
        assert response.headers["content-type"] == "image/png"
        with Image.open(io.BytesIO(response.content)) as img:
            assert img.format == "PNG" and img.width == 720 and 0 < img.height < 16384
        empty = client.get(
            "/api/diary/export/meal-calendar.png",
            params={"start": "1999-01-01", "end": "1999-01-02"},
        )
        assert empty.status_code == 400
        bad_range = client.get(
            "/api/diary/export/meal-calendar.png",
            params={"start": today_iso, "end": "1999-01-01"},
        )
        assert bad_range.status_code == 400


class TestNotes:
    def test_folder_note_rename_save_search(self, client):
        folder = client.post("/api/notes/folder", json={"parent": "", "name": "Journal"})
        assert folder.status_code == 200, folder.text
        duplicate = client.post("/api/notes/folder", json={"parent": "", "name": "journal"})
        assert duplicate.status_code == 409

        note = client.post(
            "/api/notes/file-create", json={"parent": "Journal", "name": "first"}
        )
        assert note.status_code == 200, note.text
        note_doc = note.json()
        assert note_doc["path"] == "Journal/first.md"
        assert note_doc["content"] == "# first\n\n"

        tree = client.get("/api/notes/tree").json()
        journal_node = next(c for c in tree["root"]["children"] if c["name"] == "Journal")
        assert any(c["path"] == "Journal/first.md" for c in journal_node["children"])

        saved = client.put(
            "/api/notes/file",
            json={"path": "Journal/first.md", "content": "# first\n\n今天开始写笔记\n",
                  "previousSha256": note_doc["version"]["sha256"]},
        )
        assert saved.status_code == 200
        conflict = client.put(
            "/api/notes/file",
            json={"path": "Journal/first.md", "content": "x", "previousSha256": "nope"},
        )
        assert conflict.status_code == 409
        assert "currentSha256" in conflict.json()

        renamed = client.post(
            "/api/notes/rename", json={"path": "Journal/first.md", "newName": "renamed"}
        )
        assert renamed.status_code == 200
        entry = renamed.json()["entry"]
        assert entry["path"] == "Journal/renamed.md"

        results = client.get("/api/notes/search", params={"q": "写笔记"}).json()
        assert any(r["path"] == "Journal/renamed.md" for r in results)

        wiki = client.get("/api/notes/resolve", params={"name": "renamed"}).json()
        assert wiki["path"] == "Journal/renamed.md"

        media_up = client.post(
            "/api/notes/media-upload",
            params={"targetFolder": "Journal"},
            files={"file": ("shot.png", _png_bytes(24, 24), "image/png")},
        )
        assert media_up.status_code == 200, media_up.text
        assert media_up.json()["markdown"] == "![](Journal/" + media_up.json()["fileName"] + ")"

        deleted = client.delete("/api/notes/node", params={"path": "Journal"})
        assert deleted.status_code == 200
        assert client.get("/api/notes/file", params={"path": "Journal/renamed.md"}).status_code == 404


class TestStructured:
    def test_config_day_boundary_fields_reindex(self, client):
        config = client.get("/api/structured/config").json()
        assert config["dayBoundary"] == "05:00"
        assert config["dayBoundaryHour"] == 5
        assert config["todayDiarySwitchTime"] == "05:00"
        assert {f["id"] for f in config["fields"]} >= {"f_number_pushups", "f_time_lunch"}

        updated = client.put("/api/structured/day-boundary", json={"hours": 5})
        assert updated.status_code == 200
        assert updated.json()["dayBoundary"] == "05:00"
        assert updated.json()["todayDiarySwitchTime"] == "05:00"
        invalid = client.put("/api/structured/day-boundary", json={"hours": 24})
        assert invalid.status_code == 400

        fields_put = client.put(
            "/api/structured/fields",
            json={"fields": [
                {"id": "f_number_pushups", "name": "俯卧撑次数", "type": "number", "unit": "次"},
                {"id": "f_word_today", "name": "今日一句话", "type": "word"},
            ]},
        )
        assert fields_put.status_code == 200, fields_put.text
        assert len(fields_put.json()["fields"]) == 2

        record = client.post(
            "/api/structured/records",
            json={"fieldId": "f_number_pushups", "rawValue": "20 次"},
        )
        assert record.status_code == 200, record.text
        body = record.json()
        assert body["success"] is True and body["journalDay"]
        assert "<!--dc:f_number_pushups-->20<!--dc:/f_number_pushups-->" in body["document"]["content"]

        invalid_value = client.post(
            "/api/structured/records",
            json={"fieldId": "f_number_pushups", "rawValue": "abc 个"},
        )
        assert invalid_value.status_code == 400

        reindexed = client.post("/api/structured/reindex")
        assert reindexed.status_code == 200
        assert reindexed.json()["parsedFiles"] >= 1
        assert reindexed.json()["totalOccurrences"] >= 1

        records = client.get(
            "/api/structured/records",
            params={"fromDay": "2000-01-01", "toDay": "2999-01-01"},
        ).json()
        target = [r for r in records if r["fieldId"] == "f_number_pushups"]
        assert target and target[0]["rawValue"] == "20" and target[0]["fieldName"] == "俯卧撑次数"

        statistics = client.get("/api/structured/statistics").json()
        pushups = next(f for f in statistics["fields"] if f["fieldId"] == "f_number_pushups")
        assert pushups["count"] >= 1 and pushups["latest"] == "20"
        assert pushups["average"] == "20"

    def test_statistics_with_derived_metric(self, client):
        import json as _json

        from app.core.config import STRUCTURED_DIR

        # timeDiff only accepts TIME operands (MetricEvaluator.asNaturalDateTime);
        # sleep 23:30 -> wake 07:00 on one day is an overnight 7.5h interval.
        fields = client.put(
            "/api/structured/fields",
            json={"fields": [
                {"id": "f_system_sleep_time", "name": "睡觉时间", "type": "time"},
                {"id": "f_system_wake_time", "name": "起床时间", "type": "time"},
            ]},
        )
        assert fields.status_code == 200
        assert client.post("/api/structured/records",
                           json={"fieldId": "f_system_sleep_time", "rawValue": "23:30"}).status_code == 200
        assert client.post("/api/structured/records",
                           json={"fieldId": "f_system_wake_time", "rawValue": "07:00"}).status_code == 200

        metric = {
            "schemaVersion": 1,
            "metrics": [{
                "id": "m_sleep_len", "name": "睡眠时长", "resultType": "duration",
                "expression": {"op": "timeDiff",
                               "end": {"op": "fieldRef", "fieldId": "f_system_wake_time",
                                       "dayOffset": 0, "selector": "last"},
                               "start": {"op": "fieldRef", "fieldId": "f_system_sleep_time",
                                         "dayOffset": 0, "selector": "last"}},
                "display": {"chart": "line", "period": "day"},
                "archived": False, "sortOrder": 0,
            }],
        }
        (STRUCTURED_DIR / "statistics.json").write_text(
            _json.dumps(metric, ensure_ascii=False), encoding="utf-8"
        )
        try:
            stats = client.get("/api/structured/statistics").json()
            assert stats["metrics"], "derived metric should be evaluated"
            sleep_metric = next(m for m in stats["metrics"] if m["id"] == "m_sleep_len")
            today_values = [p for p in sleep_metric["series"] if p["chartValue"] is not None]
            assert today_values and today_values[-1]["chartValue"] == 27000.0
            assert today_values[-1]["display"] == "7:30"
        finally:
            (STRUCTURED_DIR / "statistics.json").unlink(missing_ok=True)


class TestMediaMetaRobustness:
    def test_corrupt_sidecar_never_overwritten_as_empty(self, client):
        from app.core.config import MEDIA_DIR

        corrupt = MEDIA_DIR / "dc-media.json"
        original_bytes = corrupt.read_bytes() if corrupt.exists() else b"{}"
        try:
            corrupt.write_bytes(b"{ this is not json ")
            rejected = client.put(
                "/api/meals/photo-meta",
                json={"fileName": "whatever.jpg", "energyKj": 100},
            )
            assert rejected.status_code == 500
            assert corrupt.read_bytes() == b"{ this is not json "
        finally:
            corrupt.write_bytes(original_bytes)


class TestSettingsShape:
    def test_settings_expose_media_patterns(self, client):
        settings = client.get("/api/settings").json()
        assert settings["fileNamePattern"] == "yyyy-MM-dd"
        assert settings["markdownTemplate"] == "# {title}\n\n"
        assert settings["imageNamePattern"] == "{date}_{category}_{seq}"
        assert settings["mealImageCompressionEnabled"] is True
