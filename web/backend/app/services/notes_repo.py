"""Notes vault boundary over workspace/notes (Obsidian-compatible folders + Markdown).

Port of NotesRepository.kt to a real FS: names are normalized for Windows/Obsidian
compatibility, sibling collisions are case-insensitive, saves detect external edits via
SHA-256 (`previousSha256` -> 409 carrying the disk copy), and media imports return a
portable relative Markdown link.
"""
from __future__ import annotations

import os
import re
import shutil
import threading
from pathlib import Path
from typing import Any

from ..core.config import NOTES_DIR
from ..core.errors import ApiError
from ..core.fs import file_sha256, safe_write_text, sanitize_rel_path, sha256_bytes

MAX_NOTE_NAME_CHARS = 240
MAX_NOTE_BYTES = 4 * 1024 * 1024
MAX_NOTE_MEDIA_BYTES = 64 * 1024 * 1024
MAX_NOTE_FOLDER_ENTRIES = 5_000
MAX_NOTE_PREVIEW_TARGETS = 2_000
MAX_NOTE_MEDIA_SEARCH_ENTRIES = 20_000
MAX_NOTE_MEDIA_SEARCH_DEPTH = 16
MAX_SEARCH_RESULTS = 200

notes_mutex = threading.RLock()

_WINDOWS_RESERVED = {"CON", "PRN", "AUX", "NUL"} | {f"COM{i}" for i in range(1, 10)} | {f"LPT{i}" for i in range(1, 10)}
_NAME_UNSAFE_RE = re.compile(r"[\x00-\x1f<>:\"/\\|?*]")


class NoteExternalConflict(Exception):
    def __init__(self, disk_document: dict[str, Any]):
        super().__init__("Note was modified by another application")
        self.document = disk_document


def normalize_note_name(raw: str, markdown_file: bool) -> str:
    value = _NAME_UNSAFE_RE.sub("_", raw.strip()).rstrip(" .")[:220]
    if markdown_file and not value.lower().endswith(".md"):
        value += ".md"
    if not value or value in (".", ".."):
        raise ApiError(400, "invalid_name", "名称不能为空")
    stem = value.rsplit(".", 1)[0].upper() if "." in value else value.upper()
    if stem in _WINDOWS_RESERVED:
        raise ApiError(400, "invalid_name", "该名称不兼容 Windows/Obsidian")
    return value


def _resolve(rel: str) -> Path:
    target = sanitize_rel_path(rel, NOTES_DIR)
    return target


def folder_path(parent: str) -> Path:
    target = _resolve(parent if parent else ".")
    if not target.is_dir():
        raise ApiError(404, "not_found", "文件夹已被移动或删除")
    return target


def _entry(path: Path) -> dict[str, Any]:
    st = path.stat()
    return {
        "name": path.name,
        "path": path.relative_to(NOTES_DIR).as_posix(),
        "isFolder": path.is_dir(),
        "size": st.st_size if path.is_file() else 0,
        "lastModified": int(st.st_mtime * 1000),
    }


def scan_tree() -> dict[str, Any]:
    """Nested folder+Markdown tree; folders first, then case-insensitive names."""
    with notes_mutex:
        def build(dir_path: Path) -> list[dict[str, Any]]:
            try:
                children = sorted(dir_path.iterdir(), key=lambda p: p.name.lower())
            except OSError:
                return []
            if len(children) > MAX_NOTE_FOLDER_ENTRIES:
                raise ApiError(413, "too_many_entries", "文件夹项目过多，已停止扫描")
            nodes = []
            for child in children:
                name = child.name[:MAX_NOTE_NAME_CHARS]
                if not name:
                    continue
                rel = child.relative_to(NOTES_DIR).as_posix()
                if child.is_dir():
                    nodes.append(
                        {"name": name, "path": rel, "isFolder": True, "size": 0,
                         "lastModified": int(child.stat().st_mtime * 1000),
                         "children": build(child)}
                    )
                elif child.is_file() and name.lower().endswith(".md"):
                    st = child.stat()
                    nodes.append(
                        {"name": name, "path": rel, "isFolder": False, "size": st.st_size,
                         "lastModified": int(st.st_mtime * 1000)}
                    )
            nodes.sort(key=lambda n: (not n["isFolder"], n["name"].lower(), n["name"]))
            return nodes

        return {
            "location": {"name": NOTES_DIR.name or "Notes", "relativePath": ""},
            "root": {"name": NOTES_DIR.name or "Notes", "path": "", "isFolder": True,
                     "size": 0, "lastModified": 0, "children": build(NOTES_DIR)},
        }


def create_folder(parent: str, requested_name: str) -> dict[str, Any]:
    with notes_mutex:
        directory = folder_path(parent)
        name = normalize_note_name(requested_name, markdown_file=False)
        _require_no_sibling(directory, name)
        created = directory / name
        created.mkdir(parents=True, exist_ok=False)
        return _entry(created)


def create_note(con_unused: Any, parent: str, requested_name: str) -> dict[str, Any]:
    with notes_mutex:
        directory = folder_path(parent)
        name = normalize_note_name(requested_name, markdown_file=True)
        _require_no_sibling(directory, name)
        title = name[:-3] if name.lower().endswith(".md") else name
        initial = f"# {title}\n\n"
        created = directory / name
        safe_write_text(created, initial)
        return load_note(created.relative_to(NOTES_DIR).as_posix())


def _require_no_sibling(directory: Path, name: str, ignored: Path | None = None) -> None:
    for child in directory.iterdir():
        if ignored is not None and child == ignored:
            continue
        if child.name.lower() == name.lower():
            raise ApiError(409, "duplicate_name", "当前文件夹已存在同名项目")


def load_note(rel_path: str) -> dict[str, Any]:
    path = _resolve(rel_path)
    if not path.is_file():
        raise ApiError(404, "not_found", "笔记已被移动或删除")
    if not path.name.lower().endswith(".md"):
        raise ApiError(400, "invalid_name", "所选文件不是 Markdown 笔记")
    size = path.stat().st_size
    if size > MAX_NOTE_BYTES:
        raise ApiError(413, "file_too_large", "笔记超过 4 MiB 上限")
    try:
        content = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        raise ApiError(400, "invalid_encoding", "笔记不是有效的 UTF-8 文本")
    raw = content.encode("utf-8")
    parent_rel = path.parent.relative_to(NOTES_DIR).as_posix()
    return {
        "uri": rel_path,
        "path": rel_path,
        "folderRelativePath": "" if parent_rel == "." else parent_rel,
        "name": path.name,
        "content": content,
        "version": {
            "sha256": sha256_bytes(raw),
            "size": len(raw),
            "lastModified": int(path.stat().st_mtime * 1000),
        },
    }


def save_note(rel_path: str, content: str, previous_sha256: str | None, force: bool = False) -> dict[str, Any]:
    if len(content.encode("utf-8")) > MAX_NOTE_BYTES:
        raise ApiError(413, "file_too_large", "笔记超过 4 MiB 上限")
    with notes_mutex:
        disk = load_note(rel_path)
        if not force and previous_sha256 is not None and disk["version"]["sha256"] != previous_sha256:
            raise NoteExternalConflict(disk)
        safe_write_text(_resolve(rel_path), content)
        return load_note(rel_path)


def rename_node(rel_path: str, requested_name: str) -> dict[str, Any]:
    with notes_mutex:
        source = _resolve(rel_path)
        if not source.exists():
            raise ApiError(404, "not_found", "找不到要重命名的项目")
        target_name = normalize_note_name(requested_name, markdown_file=source.is_file())
        if target_name == source.name:
            return _entry(source)
        _require_no_sibling(source.parent, target_name, ignored=source)
        dest = source.parent / target_name
        os.rename(source, dest)
        return _entry(dest)


def delete_node(rel_path: str) -> None:
    with notes_mutex:
        target = _resolve(rel_path)
        if not target.exists():
            raise ApiError(404, "not_found", "项目不存在")
        if target == NOTES_DIR.resolve():
            raise ApiError(400, "invalid_path", "Cannot delete the vault root")
        if target.is_dir():
            shutil.rmtree(target)
        else:
            target.unlink()


def search_notes(query: str) -> list[dict[str, Any]]:
    needle = query.strip().lower()
    if not needle:
        return []
    results: list[dict[str, Any]] = []
    scanned = 0
    with notes_mutex:
        for dir_path, _dirs, files in os.walk(NOTES_DIR):
            for file_name in files:
                if not file_name.lower().endswith(".md"):
                    continue
                if scanned >= MAX_NOTE_PREVIEW_TARGETS or len(results) >= MAX_SEARCH_RESULTS:
                    return results
                scanned += 1
                path = Path(dir_path) / file_name
                rel = path.relative_to(NOTES_DIR).as_posix()
                name_hit = needle in file_name.lower()
                line_matches: list[dict[str, Any]] = []
                if path.stat().st_size <= MAX_NOTE_BYTES:
                    try:
                        content = path.read_text(encoding="utf-8")
                    except (OSError, UnicodeDecodeError):
                        content = ""
                    for line_no, line in enumerate(content.splitlines(), start=1):
                        if needle in line.lower():
                            line_matches.append({"line": line_no, "text": line.strip()[:300]})
                            if len(line_matches) >= 5:
                                break
                if name_hit or line_matches:
                    results.append({"path": rel, "name": file_name, "nameMatch": name_hit,
                                    "matches": line_matches})
    return results


def resolve_wiki_link(name: str) -> dict[str, Any] | None:
    """`![[name]]` resolution: bounded BFS for a file whose name matches, any depth."""
    wanted = name.strip()
    if not wanted:
        return None
    candidates = {wanted.lower()}
    if not wanted.lower().endswith(".md"):
        candidates.add((wanted + ".md").lower())
    visited = 0
    with notes_mutex:
        queue: list[tuple[Path, int]] = [(NOTES_DIR, 0)]
        while queue and visited < MAX_NOTE_MEDIA_SEARCH_ENTRIES:
            directory, depth = queue.pop(0)
            try:
                children = sorted(directory.iterdir(), key=lambda p: p.name.lower())
            except OSError:
                continue
            for child in children:
                visited += 1
                if visited > MAX_NOTE_MEDIA_SEARCH_ENTRIES:
                    break
                if child.is_file() and child.name.lower() in candidates:
                    return {
                        "path": child.relative_to(NOTES_DIR).as_posix(),
                        "name": child.name,
                        "content": _read_limited(child),
                    }
                if child.is_dir() and depth < MAX_NOTE_MEDIA_SEARCH_DEPTH:
                    queue.append((child, depth + 1))
    return None


def _read_limited(path: Path) -> str | None:
    try:
        if path.stat().st_size > MAX_NOTE_BYTES:
            return None
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return None


_EXT_BY_MIME = {
    "image/png": "png",
    "image/webp": "webp",
    "image/gif": "gif",
    "image/heic": "heic",
    "image/heif": "heic",
}


def import_media(data: bytes, source_name: str, mime: str, target_folder: str) -> dict[str, Any]:
    """Copies one image into a user-chosen vault folder; returns a portable relative link."""
    if not data:
        raise ApiError(400, "empty_file", "所选图片为空")
    if len(data) > MAX_NOTE_MEDIA_BYTES:
        raise ApiError(413, "file_too_large", "媒体超过 64 MiB 上限")
    lowered_mime = (mime or "").lower()
    if not lowered_mime.startswith("image/"):
        raise ApiError(400, "unsupported_type", "所选文件不是受支持的图片")
    extension = source_name.rsplit(".", 1)[-1].lower() if "." in source_name else ""
    if not re.fullmatch(r"[a-z0-9]{1,8}", extension or ""):
        extension = _EXT_BY_MIME.get(lowered_mime, "jpg")
    stem = source_name.rsplit(".", 1)[0] if "." in source_name else source_name
    requested = normalize_note_name(f"{stem or 'image'}.{extension}", markdown_file=False)
    with notes_mutex:
        directory = folder_path(target_folder)
        existing = {child.name.lower() for child in directory.iterdir()}
        actual = requested
        sequence = 2
        dot = requested.rfind(".")
        while actual.lower() in existing:
            actual = f"{requested[:dot]} ({sequence}){requested[dot:]}"
            sequence += 1
        dest = directory / actual
        dest.write_bytes(data)
        if file_sha256(dest) != sha256_bytes(data):
            dest.unlink(missing_ok=True)
            raise ApiError(500, "write_verify_failed", "媒体写入后的回读校验失败")
        folder_rel = dest.parent.relative_to(NOTES_DIR).as_posix()
        markdown_target = dest.name if folder_rel == "." else f"{folder_rel}/{dest.name}"
        return {
            "fileName": dest.name,
            "markdownTarget": markdown_target.replace("\\", "/"),
            "markdown": f"![]({markdown_target})",
        }
