#!/usr/bin/env bash
set -Eeuo pipefail

WEB_SOURCE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
INSTALL_PREFIX="${DESKCUBBY_INSTALL_PREFIX:-${HOME:?}/.local}"
SYSTEM_PYTHON="${DESKCUBBY_PYTHON:-python3}"
NPM_COMMAND="${DESKCUBBY_NPM:-npm}"
APP_VERSION="0.23.5"

usage() {
  printf '%s\n' \
    '用法：./install.sh [--prefix 路径]' \
    '' \
    '默认安装到 ~/.local：' \
    '  命令      ~/.local/bin/deskcubby' \
    '  程序      ~/.local/share/deskcubby/' \
    '  用户数据  ~/.local/share/deskcubby/data/'
}

while (($# > 0)); do
  case "$1" in
    --prefix)
      (($# >= 2)) || { printf '缺少 --prefix 的路径。\n' >&2; exit 2; }
      INSTALL_PREFIX="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf '未知参数：%s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ "$INSTALL_PREFIX" != /* ]]; then
  printf '安装前缀必须是绝对路径：%s\n' "$INSTALL_PREFIX" >&2
  exit 2
fi
if [[ "$INSTALL_PREFIX" == "/" ]]; then
  printf '安装前缀不能是文件系统根目录。\n' >&2
  exit 2
fi
if ! command -v "$SYSTEM_PYTHON" >/dev/null 2>&1; then
  printf '未找到 Python 3。请先安装 Python 3.11 或更高版本。\n' >&2
  exit 1
fi
if ! command -v "$NPM_COMMAND" >/dev/null 2>&1; then
  printf '未找到 npm。请先安装 Node.js 18 或更高版本。\n' >&2
  exit 1
fi
if ! "$SYSTEM_PYTHON" -c 'import sys;raise SystemExit(0 if sys.version_info >= (3,11) else 1)'; then
  printf 'Python 版本过低，需要 Python 3.11 或更高版本。\n' >&2
  exit 1
fi

FRONTEND_SOURCE="$WEB_SOURCE_DIR/frontend"
BACKEND_SOURCE="$WEB_SOURCE_DIR/backend"
BACKEND_RUNTIME_REQUIREMENTS="$BACKEND_SOURCE/requirements-runtime.txt"
INSTALL_ROOT="$INSTALL_PREFIX/share/deskcubby"
BIN_DIR="$INSTALL_PREFIX/bin"
RELEASES_DIR="$INSTALL_ROOT/releases"
VENV_DIR="$INSTALL_ROOT/venv"

NODE_LOCK_COPY="$FRONTEND_SOURCE/node_modules/.package-lock.json"
if [[ ! -x "$FRONTEND_SOURCE/node_modules/.bin/tsc" \
  || ! -x "$FRONTEND_SOURCE/node_modules/.bin/vite" \
  || ! -f "$NODE_LOCK_COPY" \
  || "$FRONTEND_SOURCE/package-lock.json" -nt "$NODE_LOCK_COPY" ]]; then
  printf '正在安装前端依赖……\n'
  "$NPM_COMMAND" --prefix "$FRONTEND_SOURCE" ci --no-audit --no-fund
else
  printf '前端依赖已与锁文件一致，直接复用。\n'
fi
printf '正在构建 DeskCubby Web……\n'
"$NPM_COMMAND" --prefix "$FRONTEND_SOURCE" run build

mkdir -p "$BIN_DIR" "$RELEASES_DIR" "$INSTALL_ROOT/data"
if [[ ! -x "$VENV_DIR/bin/python" ]]; then
  printf '正在创建隔离的 Python 环境……\n'
  if ! "$SYSTEM_PYTHON" -m venv "$VENV_DIR"; then
    printf '无法创建 Python 虚拟环境；Debian/Ubuntu 可先安装 python3-venv。\n' >&2
    exit 1
  fi
fi
printf '正在安装后端依赖……\n'
if ! "$VENV_DIR/bin/python" -m pip install \
  --disable-pip-version-check --retries 2 --timeout 20 \
  -r "$BACKEND_RUNTIME_REQUIREMENTS"; then
  if [[ -n "${DESKCUBBY_PIP_FALLBACK_INDEX_URL:-}" ]]; then
    PIP_FALLBACK_INDEXES=("$DESKCUBBY_PIP_FALLBACK_INDEX_URL")
  else
    PIP_FALLBACK_INDEXES=(
      "https://pypi.org/simple"
      "https://mirrors.aliyun.com/pypi/simple"
    )
  fi
  for PIP_FALLBACK_INDEX in "${PIP_FALLBACK_INDEXES[@]}"; do
    if [[ "$PIP_FALLBACK_INDEX" != https://* ]]; then
      printf '备用 pip 地址必须使用 HTTPS：%s\n' "$PIP_FALLBACK_INDEX" >&2
      exit 2
    fi
  done
  PIP_RESUME_OPTIONS=()
  PIP_INSTALL_HELP="$("$VENV_DIR/bin/python" -m pip install --help 2>/dev/null || true)"
  if [[ "$PIP_INSTALL_HELP" == *"--resume-retries"* ]]; then
    PIP_RESUME_OPTIONS=(--resume-retries 8)
  fi
  printf '\n当前 pip 镜像不可用，正在忽略用户 pip 配置并尝试备用 HTTPS 源。\n' >&2
  PIP_FALLBACK_INSTALLED=0
  for PIP_FALLBACK_INDEX in "${PIP_FALLBACK_INDEXES[@]}"; do
    for PIP_FALLBACK_ATTEMPT in 1 2; do
      printf '备用源 %s（尝试 %s/2）……\n' "$PIP_FALLBACK_INDEX" "$PIP_FALLBACK_ATTEMPT" >&2
      if "$VENV_DIR/bin/python" -m pip --isolated install \
        --disable-pip-version-check --prefer-binary \
        --cache-dir "$INSTALL_ROOT/pip-cache" \
        --retries 4 --timeout 30 "${PIP_RESUME_OPTIONS[@]}" \
        --index-url "$PIP_FALLBACK_INDEX" \
        -r "$BACKEND_RUNTIME_REQUIREMENTS"; then
        PIP_FALLBACK_INSTALLED=1
        break 2
      fi
      if ((PIP_FALLBACK_ATTEMPT < 2)); then
        sleep "$PIP_FALLBACK_ATTEMPT"
      fi
    done
  done
  if ((PIP_FALLBACK_INSTALLED == 0)); then
    printf '\n后端依赖安装失败。请检查网络或 TLS 证书后重试；现有 DeskCubby 安装未被替换。\n' >&2
    printf '也可设置 DESKCUBBY_PIP_FALLBACK_INDEX_URL 为可用的 HTTPS PyPI 镜像。\n' >&2
    exit 1
  fi
fi

STAGING_DIR="$(mktemp -d "$RELEASES_DIR/.staging.XXXXXX")"
cleanup_staging() {
  if [[ -n "${STAGING_DIR:-}" && -d "$STAGING_DIR" ]]; then
    rm -rf -- "$STAGING_DIR"
  fi
}
trap cleanup_staging EXIT

mkdir -p "$STAGING_DIR/backend" "$STAGING_DIR/frontend"
cp -a "$BACKEND_SOURCE/app" "$STAGING_DIR/backend/app"
cp "$BACKEND_SOURCE/requirements.txt" "$STAGING_DIR/backend/requirements.txt"
cp "$BACKEND_RUNTIME_REQUIREMENTS" "$STAGING_DIR/backend/requirements-runtime.txt"
cp -a "$FRONTEND_SOURCE/dist" "$STAGING_DIR/frontend/dist"

RELEASE_NAME="$APP_VERSION-$(date -u +%Y%m%dT%H%M%SZ)-$$"
RELEASE_DIR="$RELEASES_DIR/$RELEASE_NAME"
mv "$STAGING_DIR" "$RELEASE_DIR"
STAGING_DIR=""

CURRENT_LINK="$INSTALL_ROOT/.current.$$"
ln -s "$RELEASE_DIR" "$CURRENT_LINK"
mv -Tf "$CURRENT_LINK" "$INSTALL_ROOT/current"
install -m 0755 "$WEB_SOURCE_DIR/scripts/deskcubby" "$BIN_DIR/deskcubby"

trap - EXIT
printf '\nDeskCubby 安装完成。\n'
printf '运行：%s\n' "$BIN_DIR/deskcubby"
if [[ ":$PATH:" != *":$BIN_DIR:"* ]]; then
  printf '\n当前 PATH 尚未包含 %s。把下面一行加入你的 shell 配置后重新打开终端：\n' "$BIN_DIR"
  printf 'export PATH="%s:$PATH"\n' "$BIN_DIR"
else
  printf '现在可直接在终端运行：deskcubby\n'
fi
printf '默认地址：http://127.0.0.1:8787\n'
printf '用户数据：%s\n' "$INSTALL_ROOT/data"
