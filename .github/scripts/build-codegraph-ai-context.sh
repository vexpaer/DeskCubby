#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-ai-code-context/generated}"
MAX_SYMBOLS="${CODEGRAPH_MAX_SYMBOLS:-60}"
MAX_CONTEXT_BYTES="${CODEGRAPH_MAX_CONTEXT_BYTES:-120000}"
MAX_OUTPUT_BYTES="${CODEGRAPH_MAX_OUTPUT_BYTES:-1500000}"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

# The SQLite graph exists only for this process on the temporary runner.
export CODEGRAPH_TELEMETRY=0
export CODEGRAPH_NO_DAEMON=1
export DO_NOT_TRACK=1
export NO_COLOR=1

if ! codegraph init . >/tmp/codegraph-init.log 2>&1; then
  cat /tmp/codegraph-init.log >&2
  exit 1
fi

codegraph_version="$(codegraph --version 2>/dev/null || codegraph version 2>/dev/null || echo unknown)"
source_commit="$(git rev-parse HEAD)"

{
  echo "# DeskCubby CodeGraph Snapshot"
  echo
  echo "Source commit: \`${source_commit}\`"
  echo
  echo "CodeGraph version: \`${codegraph_version}\`"
  echo
  echo "This is a bounded, text-only navigation snapshot for GitHub-based agents."
  echo "Verify any finding against the current source and PR diff before editing."
  echo
  echo "## Index status"
  echo
  echo '```text'
  codegraph status . || true
  echo '```'
} > "$OUT_DIR/overview.md"

codegraph files . --json > "$OUT_DIR/files.json"

for term in sync structured diary agent navigation settings database repository; do
  target="$OUT_DIR/symbols-${term}.json"
  if ! codegraph query "$term" --limit "$MAX_SYMBOLS" --json > "$target"; then
    printf '{"query":"%s","results":[],"error":"query failed"}\n' "$term" > "$target"
  fi
done

# CodeGraph 1.5 provides natural-language source/call-path context via "explore".
# Capture before truncating so pipefail does not treat head's early exit as failure.
generate_context() {
  local filename="$1"
  local task="$2"
  local temp_output="/tmp/codegraph-explore-output"
  local temp_error="/tmp/codegraph-explore-error"

  if codegraph explore "$task" > "$temp_output" 2> "$temp_error"; then
    {
      echo "# CodeGraph exploration"
      echo
      echo "Query: $task"
      echo
      head -c "$MAX_CONTEXT_BYTES" "$temp_output"
      echo
    } > "$OUT_DIR/$filename"
  else
    {
      echo "# Context unavailable"
      echo
      echo "CodeGraph explore failed for: $task"
      echo
      echo "Use files.json and the symbols-*.json exports instead."
    } > "$OUT_DIR/$filename"
  fi
}

generate_context "architecture.md" "DeskCubby high-level architecture, major modules, entry points, and cross-module dependencies"
generate_context "android.md" "DeskCubby Android UI, navigation, data layer, repositories, database, and background work"
generate_context "windows.md" "DeskCubby Windows and Tauri frontend/backend boundary, persistence, commands, and dependencies"
generate_context "sync-data.md" "DeskCubby sync, export, diary, structured records, notes, poems, categories, and their main call paths"
generate_context "agent-ai.md" "DeskCubby Agent and AI context collection, permissions, background execution, data access, and UI integration"

actual_bytes="$(du -sb "$OUT_DIR" | cut -f1)"
if [ "$actual_bytes" -gt "$MAX_OUTPUT_BYTES" ]; then
  echo "Generated AI context is too large: ${actual_bytes} bytes (limit ${MAX_OUTPUT_BYTES})" >&2
  exit 1
fi

echo "Generated $OUT_DIR (${actual_bytes} bytes)"
