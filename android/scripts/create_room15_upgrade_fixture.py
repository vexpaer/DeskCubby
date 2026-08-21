#!/usr/bin/env python3
"""Build a representative DeskCubby Room v15 database from the committed Room schema."""

from __future__ import annotations

import json
import sqlite3
import sys
from pathlib import Path


def sql_for(item: dict) -> str:
    return item["createSql"].replace("${TABLE_NAME}", item.get("tableName") or item.get("viewName"))


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: create_room15_upgrade_fixture.py <15.json> <output.db>", file=sys.stderr)
        return 2

    schema_path = Path(sys.argv[1])
    output_path = Path(sys.argv[2])
    schema = json.loads(schema_path.read_text(encoding="utf-8"))["database"]
    if schema.get("version") != 15:
        raise SystemExit(f"expected Room schema version 15, got {schema.get('version')}")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.unlink(missing_ok=True)
    connection = sqlite3.connect(output_path)
    try:
        connection.execute("PRAGMA foreign_keys=OFF")
        for entity in schema.get("entities", []):
            connection.execute(sql_for(entity))
            for index in entity.get("indices", []):
                connection.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for view in schema.get("views", []):
            connection.execute(sql_for(view))
        for query in schema.get("setupQueries", []):
            connection.execute(query)
        connection.execute("PRAGMA user_version=15")

        # Representative rows cover ordinary user content, chat content, the agent ledger and the
        # diary index. These exact markers are asserted after installing the current APK over 0.22.0.
        connection.execute(
            """
            INSERT INTO flash_thoughts (
                id, content, createdAt, updatedAt, pinned, deletedAt,
                sortOrder, categoryId, highlighted
            ) VALUES (91001, 'upgrade-thought-v15', 101, 102, 1, NULL, 3, NULL, 1)
            """
        )
        connection.execute(
            """
            INSERT INTO ai_conversations (
                id, title, modelConfigId, createdAt, updatedAt, syncId, deletedAt
            ) VALUES (
                91002, 'upgrade-conversation-v15', 'upgrade-model', 201, 202,
                'upgrade-conversation-sync', NULL
            )
            """
        )
        connection.execute(
            """
            INSERT INTO ai_messages (
                id, conversationId, role, content, reasoning, imageUri,
                imageMimeType, imagePermissionOwned, createdAt, syncId
            ) VALUES (
                91003, 91002, 'user', 'upgrade-message-v15', '', NULL,
                NULL, 0, 203, 'upgrade-message-sync'
            )
            """
        )
        connection.execute(
            """
            INSERT INTO agent_runs (
                runId, conversationId, conversationTitle, userRequestSummary,
                modelConfigId, permissionMode, enabledSourcesJson, status,
                modelCallCount, usageReportedCallCount, inputTokens, outputTokens,
                totalTokens, cachedInputTokens, cacheRateInputTokens, reasoningTokens,
                startedAt, completedAt
            ) VALUES (
                'upgrade-run-v15', NULL, 'upgrade run', 'preserve me',
                'upgrade-model', 'REQUIRE_APPROVAL', '[]', 'SUCCEEDED',
                2, 2, 10, 5, 15, 3, 10, 1, 301, 302
            )
            """
        )
        connection.execute(
            """
            INSERT INTO diary_index (
                uri, name, title, dateIso, monthKey, lastModified,
                size, wordCount, sha256, indexedAt
            ) VALUES (
                'content://upgrade/v15', '2026-08-20.md', 'upgrade-diary-v15',
                '2026-08-20', '2026-08', 401, 402, 403, 'upgrade-sha', 404
            )
            """
        )
        connection.commit()
    finally:
        connection.close()

    with sqlite3.connect(output_path) as verify:
        version = verify.execute("PRAGMA user_version").fetchone()[0]
        marker = verify.execute(
            "SELECT content FROM flash_thoughts WHERE id = 91001"
        ).fetchone()
        if version != 15 or marker != ("upgrade-thought-v15",):
            raise SystemExit("fixture verification failed")
    print(output_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
