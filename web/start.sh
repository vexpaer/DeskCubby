#!/usr/bin/env bash
set -Eeuo pipefail

WEB_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
BACKEND_DIR="$WEB_DIR/backend"
FRONTEND_DIR="$WEB_DIR/frontend"
VENV_DIR="$BACKEND_DIR/.venv"

SYSTEM_PYTHON="${DESKCUBBY_PYTHON:-python3}"
NPM_BIN="${DESKCUBBY_NPM:-npm}"
WEB_HOST="${DESKCUBBY_WEB_HOST:-0.0.0.0}"
WEB_PORT="${DESKCUBBY_WEB_PORT:-8787}"
DATA_DIR="${DESKCUBBY_DATA_DIR:-$WEB_DIR/data}"

if ! command -v "$SYSTEM_PYTHON" >/dev/null 2>&1; then
  printf '未找到 Python：%s\n' "$SYSTEM_PYTHON" >&2
  exit 1
fi

if ! command -v "$NPM_BIN" >/dev/null 2>&1; then
  printf '未找到 npm：%s\n' "$NPM_BIN" >&2
  exit 1
fi

dependencies_available() {
  "$1" -c 'import cryptography, fastapi, httpx, multipart, PIL, uvicorn' >/dev/null 2>&1
}

VENV_PYTHON="$VENV_DIR/bin/python"
if [[ -x "$VENV_PYTHON" ]] && dependencies_available "$VENV_PYTHON"; then
  RUN_PYTHON="$VENV_PYTHON"
elif dependencies_available "$SYSTEM_PYTHON"; then
  RUN_PYTHON="$(command -v "$SYSTEM_PYTHON")"
else
  if [[ ! -x "$VENV_PYTHON" ]]; then
    printf '正在创建 Python 虚拟环境……\n'
    "$SYSTEM_PYTHON" -m venv "$VENV_DIR"
  fi
  printf '正在安装后端依赖……\n'
  "$VENV_PYTHON" -m pip install --disable-pip-version-check -r "$BACKEND_DIR/requirements.txt"
  RUN_PYTHON="$VENV_PYTHON"
fi

NODE_LOCK_COPY="$FRONTEND_DIR/node_modules/.package-lock.json"
if [[ ! -d "$FRONTEND_DIR/node_modules" || ! -f "$NODE_LOCK_COPY" \
  || "$FRONTEND_DIR/package-lock.json" -nt "$NODE_LOCK_COPY" ]]; then
  printf '正在安装前端依赖……\n'
  "$NPM_BIN" --prefix "$FRONTEND_DIR" ci --no-audit --no-fund
fi

printf '正在构建前端……\n'
"$NPM_BIN" --prefix "$FRONTEND_DIR" run build

mkdir -p "$DATA_DIR"
export DESKCUBBY_DATA_DIR="$DATA_DIR"
export DESKCUBBY_FRONTEND_DIST="$FRONTEND_DIR/dist"

printf '\nDeskCubby Web：http://127.0.0.1:%s\n' "$WEB_PORT"
printf '按 Ctrl+C 停止。\n\n'

cd "$BACKEND_DIR"
exec "$RUN_PYTHON" -m uvicorn app.main:app --host "$WEB_HOST" --port "$WEB_PORT"
