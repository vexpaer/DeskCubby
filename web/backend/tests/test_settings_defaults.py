import json
import sqlite3

from app.services.settings_store import (
    DEFAULT_MEAL_BUTTON_ICONS,
    MEAL_ICON_DEFAULT_MIGRATION_KEY,
    SETTINGS_KEY,
    default_settings,
    load_settings,
    update_settings,
)


def settings_connection() -> sqlite3.Connection:
    con = sqlite3.connect(":memory:")
    con.row_factory = sqlite3.Row
    con.execute("CREATE TABLE app_settings_kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    return con


def test_meal_buttons_default_to_icons_for_new_install() -> None:
    con = settings_connection()
    try:
        assert load_settings(con)["mealButtonsUseIcons"] is True
        marker = con.execute(
            "SELECT value FROM app_settings_kv WHERE key = ?", (MEAL_ICON_DEFAULT_MIGRATION_KEY,)
        ).fetchone()
        assert marker["value"] == "1"
    finally:
        con.close()


def test_untouched_legacy_text_default_migrates_once_and_remains_editable() -> None:
    con = settings_connection()
    try:
        legacy = default_settings()
        legacy["mealButtonsUseIcons"] = False
        legacy["mealButtonIcons"] = list(DEFAULT_MEAL_BUTTON_ICONS)
        con.execute(
            "INSERT INTO app_settings_kv(key, value) VALUES(?, ?)",
            (SETTINGS_KEY, json.dumps(legacy, ensure_ascii=False)),
        )
        con.commit()

        assert load_settings(con)["mealButtonsUseIcons"] is True
        update_settings(con, {"mealButtonsUseIcons": False})
        assert load_settings(con)["mealButtonsUseIcons"] is False
    finally:
        con.close()


def test_legacy_custom_text_choice_is_not_overridden() -> None:
    con = settings_connection()
    try:
        legacy = default_settings()
        legacy["mealButtonsUseIcons"] = False
        legacy["mealButtonIcons"] = ["1", "2", "3", "4", "5", "6"]
        con.execute(
            "INSERT INTO app_settings_kv(key, value) VALUES(?, ?)",
            (SETTINGS_KEY, json.dumps(legacy, ensure_ascii=False)),
        )
        con.commit()

        assert load_settings(con)["mealButtonsUseIcons"] is False
    finally:
        con.close()


def test_default_migration_does_not_commit_callers_transaction() -> None:
    con = settings_connection()
    try:
        legacy = default_settings()
        legacy["mealButtonsUseIcons"] = False
        legacy["mealButtonIcons"] = list(DEFAULT_MEAL_BUTTON_ICONS)
        con.execute(
            "INSERT INTO app_settings_kv(key, value) VALUES(?, ?)",
            (SETTINGS_KEY, json.dumps(legacy, ensure_ascii=False)),
        )

        assert con.in_transaction
        assert load_settings(con)["mealButtonsUseIcons"] is True
        assert con.in_transaction
        con.rollback()

        assert con.execute(
            "SELECT 1 FROM app_settings_kv WHERE key = ?", (SETTINGS_KEY,)
        ).fetchone() is None
        assert con.execute(
            "SELECT 1 FROM app_settings_kv WHERE key = ?", (MEAL_ICON_DEFAULT_MIGRATION_KEY,)
        ).fetchone() is None
    finally:
        con.close()
