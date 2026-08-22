# DeskCubby AI Code Context

This directory is the stable, GitHub-visible handoff between CodeGraph and web-based coding agents.

## For AI agents

Before making a non-trivial change, read the relevant files under `ai-code-context/generated/`:

- `overview.md` — graph/index metadata and freshness.
- `files.json` — machine-readable project structure.
- `architecture.md` — high-level architecture and important relationships.
- `android.md` — Android architecture and dependencies.
- `windows.md` — Windows/Tauri architecture and dependencies.
- `sync-data.md` — persistence, sync, diary, structured-record, note/poem/category flows.
- `agent-ai.md` — Agent/AI context, permissions and background execution.
- `symbols-*.json` — bounded symbol-search results for frequently changed areas.

Treat these files as navigation aids, not as a replacement for reading the current source and PR diff. If generated context conflicts with source code, the source code wins.

For pull requests, the `CodeGraph AI Context` workflow also posts/updates a PR comment containing changed files and CodeGraph's dependency/test impact result.

## Storage policy

The local `.codegraph/` directory contains CodeGraph's SQLite index and other transient data. It is intentionally gitignored and never committed. GitHub Actions creates the index only inside the temporary runner, exports bounded Markdown/JSON context, then discards the index.

Generated context is capped at roughly 1.5 MB total so this helper cannot quietly turn into a large generated-data store.

## Refresh behavior

- Pull request: build a temporary graph, upload the text snapshot as an artifact, and update the PR impact comment.
- Push to `main`: rebuild the text snapshot and commit only changes under `ai-code-context/generated/`.
- Changes made by that refresh commit do not trigger another refresh, preventing workflow loops.

The workflow currently pins CodeGraph `1.5.0` for reproducible output. Upgrade the pin deliberately after checking the upstream changelog/CLI behavior.