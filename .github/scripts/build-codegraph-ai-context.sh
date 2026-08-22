#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-ai-code-context/generated}"
MAX_SYMBOLS="${CODEGRAPH_MAX_SYMBOLS:-60}"
MAX_CONTEXT_NODES="${CODEGRAPH_MAX_CONTEXT_NODES:-30}"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

# CodeGraph's SQLite index is deliberately ephemeral. Only the bounded,
# text-based exports below are retained in git/artifacts.
export CODEGRAPH_TELEMETRY=0
export DO_NOT_TRACK=1

codegraph init . >/tmp/codegraph-init.log 2>&1

{
  echo "# DeskCubby CodeGraph Snapshot"
  echo
  echo "Generated from commit: \`$(git rev-parse HEAD)\`"
  echo
  echo "CodeGraph version: \`$(codegraph --version 2>/dev/null || codegraph version 2>/dev/null || echo unknown)\`"
  echo
  echo "## Index status"
  echo
  echo '```text'
  codegraph status . || true
  echo '```'
  echo
  echo "This directory is generated for AI/code-review navigation. The binary .codegraph database is never committed."
} > "$OUT_DIR/overview.md"

codegraph files . --json > "$OUT_DIR/files.json"

# Bounded symbol lookups for the areas most frequently touched in DeskCubby.
for term in sync structured diary agent navigation settings database repository; do
  if ! codegraph query "$term" --limit "$MAX_SYMBOLS" --json > "$OUT_DIR/symbols-${term}.json"; then
    printf '{"query":"%s","results":[],"error":"query failed"}\n' "$term" > "$OUT_DIR/symbols-${term}.json"
  fi
done

# Natural-language context is especially useful to web-based agents. Keep each
# export bounded; if a future CodeGraph release changes the context CLI, the
# snapshot still succeeds and the JSON exports remain available.
generate_context() {
  local filename="$1"
  local task="$2"
  if ! codegraph context "$task" --format markdown --max-nodes "$MAX_CONTEXT_NODES" > "$OUT_DIR/$filename" 2>/tmp/codegraph-context.err; then
    {
      echo "# Context unavailable"
      echo
      echo "CodeGraph context generation failed for: $task"
      echo
      echo "Use files.json and the symbols-*.json exports instead."
    } > "$OUT_DIR/$filename"
  fi
}

generate_context "architecture.md" "Explain DeskCubby's high-level architecture, major modules, entry points, and the most important dependency relationships for an AI coding agent."
generate_context "android.md" "Explain the Android application architecture, UI/navigation, data layer, background work, and important cross-module dependencies in DeskCubby."
generate_context "windows.md" "Explain the Windows/Tauri application architecture, frontend/backend boundary, persistence, and important dependencies in DeskCubby."
generate_context "sync-data.md" "Explain DeskCubby's sync, persistence, diary, structured records, notes, poems, categories, and related data flows. Identify the main symbols and call paths an AI should inspect before changing them."
generate_context "agent-ai.md" "Explain DeskCubby's Agent/AI functionality, context collection, permissions, background execution, and how these features connect to app data and UI."

# Keep generated context predictably small. Fail rather than silently committing
# an unexpectedly huge export after an upstream CLI change.
MAX_BYTES="${CODEGRAPH_MAX_OUTPUT_BYTES:-1500000}"
actual_bytes=$(du -sb "$OUT_DIR" | cut -f1)
if [ "$actual_bytes" -gt "$MAX_BYTES" ]; then
  echo "Generated AI context is too large: ${actual_bytes} bytes (limit ${MAX_BYTES})" >&2
  exit 1
fi

echo "Generated $OUT_DIR (${actual_bytes} bytes)"