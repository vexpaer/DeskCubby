# DeskCubby AI Code Context

This directory is the GitHub-readable handoff between CodeGraph and web-based coding agents.

CodeGraph builds its SQLite knowledge graph temporarily inside GitHub Actions. The database and all other files under `.codegraph/` are discarded with the runner and ignored by git. Only bounded Markdown and JSON exports are retained.

## Reading order for web agents

1. Read `generated/overview.md` and compare its source commit with the branch being inspected.
2. Read the relevant area snapshot: `architecture.md`, `android.md`, `windows.md`, `sync-data.md`, or `agent-ai.md`.
3. Use `files.json` and `symbols-*.json` to locate likely source files and symbols.
4. Verify conclusions against the current source and PR diff before changing code.

The snapshot is a navigation layer, not an authority. It can lag one commit behind `main` while the refresh workflow is running, and a PR may differ from the committed main-branch snapshot.

## Generated outputs

- `overview.md`: source commit, pinned CodeGraph version, and graph status.
- `files.json`: machine-readable repository structure.
- `symbols-*.json`: bounded symbol searches for frequently changed domains.
- Area Markdown files: bounded `codegraph explore` results containing relevant source, relationships, and impact context.

Do not edit `generated/` by hand.

## Workflow behavior

### Pull requests

The workflow builds a temporary graph for the PR merge commit, uploads the Markdown/JSON snapshot as a 14-day artifact, and posts one updatable PR comment containing the changed-file list and CodeGraph's affected-test/dependency result. The artifact is intentionally not committed to the PR branch.

### Pushes to main

The workflow rebuilds the snapshot, downloads it into a separate least-privilege job, and commits only `ai-code-context/generated/` to `main`. Generated-only commits are excluded by the workflow path filter, preventing refresh loops.

## Safety and size limits

- CodeGraph is pinned to `@colbymchenry/codegraph@1.5.0`.
- Telemetry and the background daemon are disabled in CI.
- `CODEGRAPH_MAX_SYMBOLS` defaults to 60 results per symbol query.
- `CODEGRAPH_MAX_CONTEXT_BYTES` defaults to 120,000 bytes per area Markdown file.
- `CODEGRAPH_MAX_OUTPUT_BYTES` caps the entire snapshot at 1,500,000 bytes.
- Indexing and size-cap failures fail the workflow instead of committing partial or unexpectedly large output.
- An individual `explore` failure produces a small fallback Markdown file while the JSON navigation exports remain available.
