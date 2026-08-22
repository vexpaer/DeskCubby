"""Filesystem helpers: path containment, crash-safe writes, trash moves.

Mirrors Android DiaryFileRepository guarantees where applicable to a real FS:
temp write -> read-back SHA-256 verify -> commit; no symlink escapes; names rejected
when they contain '..', are absolute, or are reserved.
"""
from __future__ import annotations

import hashlib
import os
import re
import shutil
import tempfile
import time
from pathlib import Path

from .errors import ApiError

_UNSAFE = re.compile(r"[\\/:*?\"<>|\x00-\x1f]")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sanitize_rel_path(rel: str, base: Path) -> Path:
    """Resolve `rel` under `base`; refuse traversal outside it."""
    if not rel or "\x00" in rel:
        raise ApiError(400, "invalid_path", "Invalid path")
    p = Path(rel)
    if p.is_absolute() or any(part in ("..", "") for part in p.parts):
        raise ApiError(400, "invalid_path", "Invalid path")
    target = (base / p).resolve()
    base_resolved = base.resolve()
    if target != base_resolved and base_resolved not in target.parents:
        raise ApiError(400, "invalid_path", "Path escapes root")
    return target


def sanitize_file_name(name: str) -> str:
    name = name.strip()
    if not name or name in (".", "..") or _UNSAFE.search(name):
        raise ApiError(400, "invalid_name", "Invalid file name")
    if len(name.encode("utf-8")) > 200:
        raise ApiError(400, "invalid_name", "File name too long")
    return name


def safe_write(path: Path, data: bytes) -> str:
    """Crash-safe write with read-back verification. Returns final sha256."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(dir=path.parent, prefix=".tmp-", suffix=path.name)
    tmp = Path(tmp_name)
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        if file_sha256(tmp) != hashlib.sha256(data).hexdigest():
            raise ApiError(500, "write_verify_failed", "Write verification failed")
        os.replace(tmp, path)
    except ApiError:
        tmp.unlink(missing_ok=True)
        raise
    except Exception:
        tmp.unlink(missing_ok=True)
        raise ApiError(500, "write_failed", "Failed to write file")
    return sha256_bytes(data)


def safe_write_text(path: Path, text: str) -> str:
    return safe_write(path, text.encode("utf-8"))


def move_to_trash(src: Path, trash_dir: Path) -> Path:
    """Move into trash dir with timestamp prefix to avoid collisions."""
    trash_dir.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    dest = trash_dir / f"{stamp}-{src.name}"
    seq = 1
    while dest.exists():
        dest = trash_dir / f"{stamp}-{seq}-{src.name}"
        seq += 1
    shutil.move(str(src), str(dest))
    return dest


def read_text_limited(path: Path, limit: int) -> str:
    size = path.stat().st_size
    if size > limit:
        raise ApiError(413, "file_too_large", "File too large")
    return path.read_text(encoding="utf-8")


def dir_size(path: Path) -> int:
    total = 0
    for p in path.rglob("*"):
        try:
            if p.is_file() and ".trash" not in p.parts:
                total += p.stat().st_size
        except OSError:
            continue
    return total
