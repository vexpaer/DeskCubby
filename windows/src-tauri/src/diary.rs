use chrono::{DateTime, Datelike, Local, NaiveDate};
use regex::Regex;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::ffi::OsStr;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::{Component, Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};
use thiserror::Error;
use uuid::Uuid;

pub const MAX_DIARY_BYTES: u64 = 8 * 1024 * 1024;
const TRASH_DIRECTORY: &str = ".DeskCubby Trash";
const LEGACY_TRASH_SUFFIX: &str = ".deskcubby-trash";

static DIARY_WRITE_MUTEX: Mutex<()> = Mutex::new(());
static COMMON_DATE_RES: OnceLock<Vec<Regex>> = OnceLock::new();

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileVersion {
    pub sha256: String,
    pub size: u64,
    pub modified_at: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiaryDocument {
    pub file_name: String,
    pub title: String,
    pub date_iso: String,
    pub month_key: String,
    pub word_count: usize,
    pub version: FileVersion,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiaryEditorDocument {
    pub file_name: String,
    pub content: String,
    pub version: FileVersion,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiaryTrashItem {
    pub stored_name: String,
    pub original_name: String,
    pub deleted_at: i64,
    pub size: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiaryScan {
    pub documents: Vec<DiaryDocument>,
    pub skipped_files: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(
    tag = "status",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub enum SaveDiaryOutcome {
    Saved { document: DiaryEditorDocument },
    Conflict { disk_document: DiaryEditorDocument },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RestoreDiaryResult {
    pub document: DiaryEditorDocument,
    pub trash_copy_removed: bool,
}

#[derive(Debug, Clone, Error, Serialize, Deserialize)]
#[error("{code}: {message}")]
#[serde(rename_all = "camelCase")]
pub struct DiaryError {
    pub code: String,
    pub message: String,
}

impl DiaryError {
    fn new(code: &str, message: impl Into<String>) -> Self {
        Self {
            code: code.to_owned(),
            message: message.into(),
        }
    }

    fn io(operation: &str, error: io::Error) -> Self {
        Self::new("DIARY_IO_ERROR", format!("{operation}: {error}"))
    }
}

/// Canonicalizes a user-selected directory and rejects reparse points.
///
/// All later operations resolve a single leaf beneath this canonical root. This
/// deliberately excludes symlinks and Windows junctions so a Markdown name can
/// never escape the selected directory.
pub fn validate_directory(root: &Path) -> Result<PathBuf, DiaryError> {
    let metadata = fs::symlink_metadata(root)
        .map_err(|error| DiaryError::io("read diary directory", error))?;
    if !metadata.is_dir() {
        return Err(DiaryError::new(
            "DIARY_DIRECTORY_INVALID",
            "The selected diary path is not a directory.",
        ));
    }
    if is_reparse_point(&metadata) {
        return Err(DiaryError::new(
            "DIARY_DIRECTORY_REPARSE_POINT",
            "Symlink and junction diary directories are not supported.",
        ));
    }
    fs::canonicalize(root).map_err(|error| DiaryError::io("canonicalize diary directory", error))
}

pub fn scan_diaries(root: &Path) -> Result<DiaryScan, DiaryError> {
    scan_diaries_internal(root, None)
}

pub fn scan_diaries_with_pattern(
    root: &Path,
    file_name_pattern: &str,
) -> Result<DiaryScan, DiaryError> {
    scan_diaries_internal(root, Some(file_name_pattern))
}

fn scan_diaries_internal(
    root: &Path,
    file_name_pattern: Option<&str>,
) -> Result<DiaryScan, DiaryError> {
    let root = validate_directory(root)?;
    recover_pending_writes(&root)?;
    let compiled_pattern = file_name_pattern.and_then(CompiledDatePattern::new);
    let mut documents = Vec::new();
    let mut skipped_files = Vec::new();

    let entries =
        fs::read_dir(&root).map_err(|error| DiaryError::io("enumerate diary directory", error))?;
    for entry in entries {
        let entry = match entry {
            Ok(value) => value,
            Err(_) => {
                skipped_files.push("<unreadable-entry>".to_owned());
                continue;
            }
        };
        let file_name = entry.file_name().to_string_lossy().into_owned();
        if !file_name.to_ascii_lowercase().ends_with(".md") {
            continue;
        }
        match load_diary(&root, &file_name) {
            Ok(editor) => {
                let date = diary_date(
                    &file_name,
                    editor.version.modified_at,
                    compiled_pattern.as_ref(),
                );
                documents.push(DiaryDocument {
                    file_name: file_name.clone(),
                    title: markdown_stem(&file_name),
                    date_iso: date.format("%Y-%m-%d").to_string(),
                    month_key: date.format("%Y.%m").to_string(),
                    word_count: word_count(&editor.content),
                    version: editor.version,
                });
            }
            Err(_) => skipped_files.push(file_name),
        }
    }

    documents.sort_by(|left, right| {
        right.date_iso.cmp(&left.date_iso).then_with(|| {
            right
                .file_name
                .to_lowercase()
                .cmp(&left.file_name.to_lowercase())
        })
    });
    Ok(DiaryScan {
        documents,
        skipped_files,
    })
}

pub fn load_diary(root: &Path, file_name: &str) -> Result<DiaryEditorDocument, DiaryError> {
    let root = validate_directory(root)?;
    validate_leaf_name(file_name)?;
    recover_target_if_needed(&root.join(file_name))?;
    let path = resolve_existing_leaf(&root, file_name, true)?;
    let (content, version) = read_utf8_versioned(&path, MAX_DIARY_BYTES)?;
    Ok(DiaryEditorDocument {
        file_name: file_name.to_owned(),
        content,
        version,
    })
}

pub fn create_diary(
    root: &Path,
    title: &str,
    date: NaiveDate,
    markdown_template: &str,
) -> Result<DiaryEditorDocument, DiaryError> {
    create_diary_with_pattern(root, title, date, markdown_template, "yyyy-MM-dd")
}

pub fn create_diary_with_pattern(
    root: &Path,
    title: &str,
    date: NaiveDate,
    markdown_template: &str,
    file_name_pattern: &str,
) -> Result<DiaryEditorDocument, DiaryError> {
    let _guard = DIARY_WRITE_MUTEX
        .lock()
        .map_err(|_| DiaryError::new("DIARY_LOCK_POISONED", "Diary writer lock is unavailable."))?;
    let root = validate_directory(root)?;
    let safe_title = sanitize_file_stem(if title.trim().is_empty() {
        "新日记"
    } else {
        title
    });
    let date_prefix = safe_date_prefix(date, file_name_pattern);
    let base = sanitize_file_stem(&format!("{date_prefix} {safe_title}"));
    let file_name = available_markdown_name(&root, &base)?;
    let content = markdown_template
        .replace(
            "{title}",
            if title.trim().is_empty() {
                "新日记"
            } else {
                title.trim()
            },
        )
        .replace("{date}", &date.format("%Y-%m-%d").to_string());
    ensure_content_limit(content.as_bytes())?;
    let target = resolve_new_leaf(&root, &file_name)?;
    atomic_write_new(&target, content.as_bytes())?;
    load_diary(&root, &file_name)
}

pub fn save_diary(
    root: &Path,
    file_name: &str,
    content: &str,
    expected: &FileVersion,
    force: bool,
) -> Result<SaveDiaryOutcome, DiaryError> {
    ensure_content_limit(content.as_bytes())?;
    let _guard = DIARY_WRITE_MUTEX
        .lock()
        .map_err(|_| DiaryError::new("DIARY_LOCK_POISONED", "Diary writer lock is unavailable."))?;
    let root = validate_directory(root)?;
    let disk = match load_diary(&root, file_name) {
        Ok(document) => document,
        Err(error) if force && error.code == "DIARY_FILE_NOT_FOUND" => {
            let target = resolve_new_leaf(&root, file_name)?;
            atomic_write_new(&target, content.as_bytes())?;
            let document = load_diary(&root, file_name)?;
            return Ok(SaveDiaryOutcome::Saved { document });
        }
        Err(error) => return Err(error),
    };
    if !force && disk.version.sha256 != expected.sha256 {
        return Ok(SaveDiaryOutcome::Conflict {
            disk_document: disk,
        });
    }
    let target = resolve_existing_leaf(&root, file_name, true)?;
    atomic_write_replace(&target, content.as_bytes())?;
    let document = load_diary(&root, file_name)?;
    Ok(SaveDiaryOutcome::Saved { document })
}

pub fn rename_diary(
    root: &Path,
    current_name: &str,
    requested_name: &str,
) -> Result<DiaryEditorDocument, DiaryError> {
    let _guard = DIARY_WRITE_MUTEX
        .lock()
        .map_err(|_| DiaryError::new("DIARY_LOCK_POISONED", "Diary writer lock is unavailable."))?;
    let root = validate_directory(root)?;
    let source = resolve_existing_leaf(&root, current_name, true)?;
    let target_name = normalize_markdown_file_name(requested_name);
    if current_name == target_name {
        return load_diary(&root, current_name);
    }
    if find_case_insensitive(&root, &target_name)?.is_some() {
        return Err(DiaryError::new(
            "DIARY_NAME_EXISTS",
            format!("A diary named {target_name} already exists."),
        ));
    }
    let target = resolve_new_leaf(&root, &target_name)?;
    fs::rename(&source, &target).map_err(|error| DiaryError::io("rename diary", error))?;
    load_diary(&root, &target_name)
}

pub fn rename_diary_with_pattern(
    root: &Path,
    current_name: &str,
    title: &str,
    date: NaiveDate,
    file_name_pattern: &str,
) -> Result<DiaryEditorDocument, DiaryError> {
    let safe_title = sanitize_file_stem(if title.trim().is_empty() {
        "新日记"
    } else {
        title
    });
    let date_prefix = safe_date_prefix(date, file_name_pattern);
    let requested_name = sanitize_file_stem(&format!("{date_prefix} {safe_title}"));
    rename_diary(root, current_name, &requested_name)
}

pub fn move_to_trash(root: &Path, file_name: &str) -> Result<DiaryTrashItem, DiaryError> {
    let _guard = DIARY_WRITE_MUTEX
        .lock()
        .map_err(|_| DiaryError::new("DIARY_LOCK_POISONED", "Diary writer lock is unavailable."))?;
    let root = validate_directory(root)?;
    let source = resolve_existing_leaf(&root, file_name, true)?;
    let bytes = read_bounded(&source, MAX_DIARY_BYTES)?;
    let trash = ensure_trash_directory(&root)?;
    let deleted_at = now_millis();
    let mut stored_name = format!("{deleted_at}__{file_name}");
    let mut suffix = 2_u32;
    while find_case_insensitive(&trash, &stored_name)?.is_some() {
        stored_name = format!("{deleted_at}-{suffix}__{file_name}");
        suffix += 1;
    }
    let trash_path = resolve_new_leaf(&trash, &stored_name)?;
    atomic_write_new(&trash_path, &bytes)?;
    if let Err(error) = fs::remove_file(&source) {
        let _ = fs::remove_file(&trash_path);
        return Err(DiaryError::io(
            "remove diary after verified trash copy",
            error,
        ));
    }
    Ok(DiaryTrashItem {
        stored_name,
        original_name: file_name.to_owned(),
        deleted_at,
        size: bytes.len() as u64,
    })
}

pub fn scan_trash(root: &Path) -> Result<Vec<DiaryTrashItem>, DiaryError> {
    let root = validate_directory(root)?;
    let mut items = Vec::new();
    let trash = root.join(TRASH_DIRECTORY);
    if trash.exists() {
        let trash = validate_child_directory(&root, &trash)?;
        for entry in
            fs::read_dir(&trash).map_err(|error| DiaryError::io("enumerate diary trash", error))?
        {
            let entry = entry.map_err(|error| DiaryError::io("read diary trash entry", error))?;
            let metadata = entry
                .metadata()
                .map_err(|error| DiaryError::io("read diary trash metadata", error))?;
            if !metadata.is_file() || is_reparse_point(&metadata) {
                continue;
            }
            let stored_name = entry.file_name().to_string_lossy().into_owned();
            let (deleted_at, original_name) = split_trash_name(&stored_name, &metadata);
            items.push(DiaryTrashItem {
                stored_name,
                original_name,
                deleted_at,
                size: metadata.len(),
            });
        }
    }

    for entry in fs::read_dir(&root)
        .map_err(|error| DiaryError::io("enumerate legacy diary trash", error))?
    {
        let entry =
            entry.map_err(|error| DiaryError::io("read legacy diary trash entry", error))?;
        let stored_name = entry.file_name().to_string_lossy().into_owned();
        if !stored_name
            .to_ascii_lowercase()
            .ends_with(LEGACY_TRASH_SUFFIX)
        {
            continue;
        }
        let metadata = entry
            .metadata()
            .map_err(|error| DiaryError::io("read legacy trash metadata", error))?;
        if metadata.is_file() && !is_reparse_point(&metadata) {
            items.push(DiaryTrashItem {
                original_name: stored_name[..stored_name.len() - LEGACY_TRASH_SUFFIX.len()]
                    .to_owned(),
                stored_name,
                deleted_at: modified_millis(&metadata),
                size: metadata.len(),
            });
        }
    }
    items.sort_by_key(|item| std::cmp::Reverse(item.deleted_at));
    Ok(items)
}

pub fn restore_from_trash(
    root: &Path,
    stored_name: &str,
) -> Result<RestoreDiaryResult, DiaryError> {
    let _guard = DIARY_WRITE_MUTEX
        .lock()
        .map_err(|_| DiaryError::new("DIARY_LOCK_POISONED", "Diary writer lock is unavailable."))?;
    let root = validate_directory(root)?;
    let legacy = stored_name
        .to_ascii_lowercase()
        .ends_with(LEGACY_TRASH_SUFFIX);
    let source = if legacy {
        resolve_existing_leaf(&root, stored_name, false)?
    } else {
        let trash = validate_child_directory(&root, &root.join(TRASH_DIRECTORY))?;
        resolve_existing_leaf(&trash, stored_name, false)?
    };
    let original_name = if legacy {
        stored_name[..stored_name.len() - LEGACY_TRASH_SUFFIX.len()].to_owned()
    } else {
        stored_name
            .split_once("__")
            .map(|(_, name)| name)
            .filter(|name| !name.is_empty())
            .ok_or_else(|| {
                DiaryError::new("DIARY_TRASH_NAME_INVALID", "Invalid diary trash item.")
            })?
            .to_owned()
    };
    let candidate = available_restore_name(&root, &normalize_markdown_file_name(&original_name))?;
    let bytes = read_bounded(&source, MAX_DIARY_BYTES)?;
    let target = resolve_new_leaf(&root, &candidate)?;
    atomic_write_new(&target, &bytes)?;
    let removed = fs::remove_file(&source).is_ok();
    let document = load_diary(&root, &candidate)?;
    Ok(RestoreDiaryResult {
        document,
        trash_copy_removed: removed,
    })
}

pub fn permanently_delete(root: &Path, stored_name: &str) -> Result<bool, DiaryError> {
    let _guard = DIARY_WRITE_MUTEX
        .lock()
        .map_err(|_| DiaryError::new("DIARY_LOCK_POISONED", "Diary writer lock is unavailable."))?;
    let root = validate_directory(root)?;
    let legacy = stored_name
        .to_ascii_lowercase()
        .ends_with(LEGACY_TRASH_SUFFIX);
    let source = if legacy {
        resolve_existing_leaf(&root, stored_name, false)?
    } else {
        if stored_name
            .split_once("__")
            .and_then(|(prefix, _)| prefix.split('-').next())
            .and_then(|value| value.parse::<i64>().ok())
            .is_none()
        {
            return Err(DiaryError::new(
                "DIARY_TRASH_NAME_INVALID",
                "Only files recognized as DeskCubby trash can be permanently deleted.",
            ));
        }
        let trash = validate_child_directory(&root, &root.join(TRASH_DIRECTORY))?;
        resolve_existing_leaf(&trash, stored_name, false)?
    };
    fs::remove_file(source).map_err(|error| DiaryError::io("permanently delete diary", error))?;
    Ok(true)
}

pub fn append_text(
    root: &Path,
    file_name: &str,
    text: &str,
    expected: &FileVersion,
) -> Result<SaveDiaryOutcome, DiaryError> {
    let line = text.trim().replace(['\r', '\n'], " ");
    if line.is_empty() {
        return Err(DiaryError::new(
            "DAILY_RECORD_EMPTY",
            "Daily record text cannot be empty.",
        ));
    }
    let current = load_diary(root, file_name)?;
    if current.version.sha256 != expected.sha256 {
        return Ok(SaveDiaryOutcome::Conflict {
            disk_document: current,
        });
    }
    let separator = if current.content.is_empty()
        || current.content.ends_with('\n')
        || current.content.ends_with('\r')
    {
        ""
    } else if current.content.contains("\r\n") {
        "\r\n"
    } else {
        "\n"
    };
    let updated = format!("{}{separator}{line}", current.content);
    save_diary(root, file_name, &updated, expected, false)
}

fn read_utf8_versioned(path: &Path, limit: u64) -> Result<(String, FileVersion), DiaryError> {
    let bytes = read_bounded(path, limit)?;
    let content = String::from_utf8(bytes.clone()).map_err(|_| {
        DiaryError::new(
            "DIARY_INVALID_UTF8",
            "The diary is not valid UTF-8 and was not opened.",
        )
    })?;
    let metadata =
        fs::metadata(path).map_err(|error| DiaryError::io("read diary metadata", error))?;
    let version = FileVersion {
        sha256: sha256(&bytes),
        size: bytes.len() as u64,
        modified_at: modified_millis(&metadata),
    };
    Ok((content, version))
}

fn read_bounded(path: &Path, limit: u64) -> Result<Vec<u8>, DiaryError> {
    let metadata =
        fs::metadata(path).map_err(|error| DiaryError::io("read file metadata", error))?;
    if metadata.len() > limit {
        return Err(DiaryError::new(
            "DIARY_CONTENT_TOO_LARGE",
            format!("The file exceeds the {limit}-byte limit."),
        ));
    }
    let file = File::open(path).map_err(|error| DiaryError::io("open file", error))?;
    let mut bytes = Vec::with_capacity(metadata.len() as usize);
    file.take(limit + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| DiaryError::io("read file", error))?;
    if bytes.len() as u64 > limit {
        return Err(DiaryError::new(
            "DIARY_CONTENT_TOO_LARGE",
            format!("The file exceeds the {limit}-byte limit."),
        ));
    }
    Ok(bytes)
}

fn ensure_content_limit(bytes: &[u8]) -> Result<(), DiaryError> {
    if bytes.len() as u64 > MAX_DIARY_BYTES {
        return Err(DiaryError::new(
            "DIARY_CONTENT_TOO_LARGE",
            format!("Diary text exceeds the {MAX_DIARY_BYTES}-byte limit."),
        ));
    }
    Ok(())
}

fn atomic_write_new(target: &Path, bytes: &[u8]) -> Result<(), DiaryError> {
    if target.exists() {
        return Err(DiaryError::new(
            "DIARY_NAME_EXISTS",
            "The destination already exists.",
        ));
    }
    let temp = sibling_temp_path(target, "new");
    write_and_verify_temp(&temp, bytes)?;
    if target.exists() {
        let _ = fs::remove_file(&temp);
        return Err(DiaryError::new(
            "DIARY_NAME_EXISTS",
            "The destination was created by another application.",
        ));
    }
    fs::rename(&temp, target).map_err(|error| {
        let _ = fs::remove_file(&temp);
        DiaryError::io("commit new file", error)
    })?;
    verify_file_bytes(target, bytes)
}

fn atomic_write_replace(target: &Path, bytes: &[u8]) -> Result<(), DiaryError> {
    recover_target_if_needed(target)?;
    let temp = sibling_temp_path(target, "pending");
    let recovery = recovery_path(target)?;
    write_and_verify_temp(&temp, bytes)?;

    if recovery.exists() {
        fs::remove_file(&recovery)
            .map_err(|error| DiaryError::io("remove stale recovery file", error))?;
    }
    fs::rename(target, &recovery).map_err(|error| {
        let _ = fs::remove_file(&temp);
        DiaryError::io("stage original file for safe replacement", error)
    })?;
    if let Err(error) = fs::rename(&temp, target) {
        let rollback = fs::rename(&recovery, target);
        let _ = fs::remove_file(&temp);
        return Err(match rollback {
            Ok(()) => DiaryError::io("commit replacement", error),
            Err(rollback_error) => DiaryError::new(
                "DIARY_RECOVERY_REQUIRED",
                format!(
                    "Replacement failed; the verified recovery copy was retained: {error}; rollback: {rollback_error}"
                ),
            ),
        });
    }
    if let Err(error) = verify_file_bytes(target, bytes) {
        let _ = fs::remove_file(target);
        let _ = fs::rename(&recovery, target);
        return Err(error);
    }
    // The replacement is already verified at this point. A transient antivirus
    // lock on the recovery copy must not make the UI report a failed save.
    let _ = fs::remove_file(&recovery);
    Ok(())
}

fn write_and_verify_temp(path: &Path, bytes: &[u8]) -> Result<(), DiaryError> {
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .map_err(|error| DiaryError::io("create temporary file", error))?;
    if let Err(error) = file.write_all(bytes).and_then(|()| file.sync_all()) {
        let _ = fs::remove_file(path);
        return Err(DiaryError::io("write temporary file", error));
    }
    drop(file);
    if let Err(error) = verify_file_bytes(path, bytes) {
        let _ = fs::remove_file(path);
        return Err(error);
    }
    Ok(())
}

fn verify_file_bytes(path: &Path, expected: &[u8]) -> Result<(), DiaryError> {
    let actual = read_bounded(path, expected.len() as u64 + 1)?;
    if actual.len() != expected.len() || sha256(&actual) != sha256(expected) {
        return Err(DiaryError::new(
            "DIARY_WRITE_VERIFY_FAILED",
            "The file did not match after writing.",
        ));
    }
    Ok(())
}

fn recover_pending_writes(root: &Path) -> Result<(), DiaryError> {
    for entry in fs::read_dir(root).map_err(|error| DiaryError::io("scan recovery files", error))? {
        let entry = entry.map_err(|error| DiaryError::io("read recovery entry", error))?;
        let name = entry.file_name().to_string_lossy().into_owned();
        let Some(target_name) = name
            .strip_prefix('.')
            .and_then(|value| value.strip_suffix(".dc-recovery"))
        else {
            continue;
        };
        validate_leaf_name(target_name)?;
        let recovery = entry.path();
        let target = root.join(target_name);
        if target.exists() {
            let _ = fs::remove_file(recovery);
        } else {
            fs::rename(recovery, target)
                .map_err(|error| DiaryError::io("restore interrupted diary write", error))?;
        }
    }
    Ok(())
}

fn recover_target_if_needed(target: &Path) -> Result<(), DiaryError> {
    let recovery = recovery_path(target)?;
    if recovery.exists() {
        if target.exists() {
            let _ = fs::remove_file(recovery);
        } else {
            fs::rename(recovery, target)
                .map_err(|error| DiaryError::io("restore interrupted diary write", error))?;
        }
    }
    Ok(())
}

fn recovery_path(target: &Path) -> Result<PathBuf, DiaryError> {
    let parent = target
        .parent()
        .ok_or_else(|| DiaryError::new("DIARY_PATH_INVALID", "Diary has no parent directory."))?;
    let name = target
        .file_name()
        .and_then(OsStr::to_str)
        .ok_or_else(|| DiaryError::new("DIARY_PATH_INVALID", "Diary name is not valid UTF-8."))?;
    Ok(parent.join(format!(".{name}.dc-recovery")))
}

fn sibling_temp_path(target: &Path, label: &str) -> PathBuf {
    let parent = target.parent().unwrap_or_else(|| Path::new("."));
    let name = target
        .file_name()
        .and_then(OsStr::to_str)
        .unwrap_or("diary");
    parent.join(format!(".{name}.dc-{label}-{}", Uuid::new_v4()))
}

fn resolve_existing_leaf(
    canonical_root: &Path,
    file_name: &str,
    markdown_only: bool,
) -> Result<PathBuf, DiaryError> {
    validate_leaf_name(file_name)?;
    if markdown_only && !file_name.to_ascii_lowercase().ends_with(".md") {
        return Err(DiaryError::new(
            "DIARY_FILE_TYPE_INVALID",
            "Only Markdown diary files are supported.",
        ));
    }
    let candidate = canonical_root.join(file_name);
    let metadata = fs::symlink_metadata(&candidate).map_err(|error| {
        if error.kind() == io::ErrorKind::NotFound {
            DiaryError::new("DIARY_FILE_NOT_FOUND", "The diary no longer exists.")
        } else {
            DiaryError::io("locate diary", error)
        }
    })?;
    if !metadata.is_file() || is_reparse_point(&metadata) {
        return Err(DiaryError::new(
            "DIARY_PATH_OUTSIDE_ROOT",
            "The diary is not a regular file inside the selected directory.",
        ));
    }
    let canonical =
        fs::canonicalize(candidate).map_err(|error| DiaryError::io("canonicalize diary", error))?;
    if canonical.parent() != Some(canonical_root) {
        return Err(DiaryError::new(
            "DIARY_PATH_OUTSIDE_ROOT",
            "The diary resolved outside the selected directory.",
        ));
    }
    Ok(canonical)
}

fn resolve_new_leaf(canonical_root: &Path, file_name: &str) -> Result<PathBuf, DiaryError> {
    validate_leaf_name(file_name)?;
    let candidate = canonical_root.join(file_name);
    if candidate.parent() != Some(canonical_root) {
        return Err(DiaryError::new(
            "DIARY_PATH_OUTSIDE_ROOT",
            "The destination is outside the selected directory.",
        ));
    }
    Ok(candidate)
}

fn validate_leaf_name(file_name: &str) -> Result<(), DiaryError> {
    let path = Path::new(file_name);
    let mut components = path.components();
    match (components.next(), components.next()) {
        (Some(Component::Normal(name)), None) if !name.is_empty() => {}
        _ => {
            return Err(DiaryError::new(
                "DIARY_PATH_TRAVERSAL",
                "Only one file name without directory components is allowed.",
            ));
        }
    }
    if file_name.contains(['/', '\\']) || file_name == "." || file_name == ".." {
        return Err(DiaryError::new(
            "DIARY_PATH_TRAVERSAL",
            "Directory separators and traversal segments are not allowed.",
        ));
    }
    Ok(())
}

fn validate_child_directory(root: &Path, child: &Path) -> Result<PathBuf, DiaryError> {
    let metadata = fs::symlink_metadata(child)
        .map_err(|error| DiaryError::io("read child directory metadata", error))?;
    if !metadata.is_dir() || is_reparse_point(&metadata) {
        return Err(DiaryError::new(
            "DIARY_DIRECTORY_REPARSE_POINT",
            "The DeskCubby child directory is not a safe regular directory.",
        ));
    }
    let canonical =
        fs::canonicalize(child).map_err(|error| DiaryError::io("canonicalize child", error))?;
    if canonical.parent() != Some(root) {
        return Err(DiaryError::new(
            "DIARY_PATH_OUTSIDE_ROOT",
            "The child directory resolved outside the selected directory.",
        ));
    }
    Ok(canonical)
}

fn ensure_trash_directory(root: &Path) -> Result<PathBuf, DiaryError> {
    let trash = root.join(TRASH_DIRECTORY);
    if !trash.exists() {
        fs::create_dir(&trash).map_err(|error| DiaryError::io("create diary trash", error))?;
    }
    validate_child_directory(root, &trash)
}

fn available_markdown_name(root: &Path, base: &str) -> Result<String, DiaryError> {
    let mut candidate = format!("{base}.md");
    let mut sequence = 2_u32;
    while find_case_insensitive(root, &candidate)?.is_some() {
        candidate = format!("{base} ({sequence}).md");
        sequence += 1;
    }
    Ok(candidate)
}

fn available_restore_name(root: &Path, original: &str) -> Result<String, DiaryError> {
    if find_case_insensitive(root, original)?.is_none() {
        return Ok(original.to_owned());
    }
    let stem = markdown_stem(original);
    let extension = if original.to_ascii_lowercase().ends_with(".md") {
        ".md"
    } else {
        ""
    };
    for sequence in 2_u32.. {
        let candidate = format!("{stem} (恢复 {sequence}){extension}");
        if find_case_insensitive(root, &candidate)?.is_none() {
            return Ok(candidate);
        }
    }
    unreachable!()
}

fn find_case_insensitive(root: &Path, file_name: &str) -> Result<Option<PathBuf>, DiaryError> {
    let needle = file_name.to_lowercase();
    for entry in fs::read_dir(root).map_err(|error| DiaryError::io("enumerate names", error))? {
        let entry = entry.map_err(|error| DiaryError::io("read directory entry", error))?;
        if entry.file_name().to_string_lossy().to_lowercase() == needle {
            return Ok(Some(entry.path()));
        }
    }
    Ok(None)
}

fn split_trash_name(stored_name: &str, metadata: &fs::Metadata) -> (i64, String) {
    stored_name
        .split_once("__")
        .map(|(prefix, original)| {
            (
                prefix
                    .split('-')
                    .next()
                    .and_then(|value| value.parse::<i64>().ok())
                    .unwrap_or_else(|| modified_millis(metadata)),
                original.to_owned(),
            )
        })
        .unwrap_or_else(|| (modified_millis(metadata), stored_name.to_owned()))
}

fn diary_date(
    file_name: &str,
    modified_at: i64,
    compiled_pattern: Option<&CompiledDatePattern>,
) -> NaiveDate {
    let stem = markdown_stem(file_name);
    if let Some(date) = compiled_pattern.and_then(|pattern| pattern.extract(&stem)) {
        return date;
    }
    if let Some(date) = extract_common_date(&stem) {
        return date;
    }
    DateTime::<chrono::Utc>::from_timestamp_millis(modified_at)
        .map(|value| value.with_timezone(&Local).date_naive())
        .unwrap_or_else(|| Local::now().date_naive())
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum DatePatternToken {
    Year(usize),
    Month(usize),
    Day(usize),
    Weekday,
    Literal(String),
}

struct CompiledDatePattern {
    regex: Regex,
}

impl CompiledDatePattern {
    fn new(pattern: &str) -> Option<Self> {
        let tokens = parse_java_date_pattern(pattern)?;
        let mut source = String::from(r"(?:^|[^0-9])");
        for token in tokens {
            match token {
                DatePatternToken::Year(width) => {
                    source.push_str(&format!(r"(?P<year>[0-9]{{{width}}})"));
                }
                DatePatternToken::Month(width) => {
                    if width == 1 {
                        source.push_str(r"(?P<month>[0-9]{1,2})");
                    } else {
                        source.push_str(r"(?P<month>[0-9]{2})");
                    }
                }
                DatePatternToken::Day(width) => {
                    if width == 1 {
                        source.push_str(r"(?P<day>[0-9]{1,2})");
                    } else {
                        source.push_str(r"(?P<day>[0-9]{2})");
                    }
                }
                DatePatternToken::Weekday => source.push_str(r"(?:\p{L}+)?"),
                DatePatternToken::Literal(value) => source.push_str(&regex::escape(&value)),
            }
        }
        source.push_str(r"(?:$|[^0-9])");
        Regex::new(&source).ok().map(|regex| Self { regex })
    }

    fn extract(&self, file_name_stem: &str) -> Option<NaiveDate> {
        let captures = self.regex.captures(file_name_stem)?;
        let year = captures.name("year")?.as_str().parse().ok()?;
        let month = captures.name("month")?.as_str().parse().ok()?;
        let day = captures.name("day")?.as_str().parse().ok()?;
        NaiveDate::from_ymd_opt(year, month, day)
    }
}

fn parse_java_date_pattern(pattern: &str) -> Option<Vec<DatePatternToken>> {
    if pattern.trim().is_empty() || pattern.chars().count() > 128 {
        return None;
    }
    let characters = pattern.chars().collect::<Vec<_>>();
    let mut tokens = Vec::new();
    let mut literal = String::new();
    let mut quoted = false;
    let mut index = 0_usize;
    let mut year_count = 0_u8;
    let mut month_count = 0_u8;
    let mut day_count = 0_u8;

    let flush_literal = |tokens: &mut Vec<DatePatternToken>, literal: &mut String| {
        if !literal.is_empty() {
            tokens.push(DatePatternToken::Literal(std::mem::take(literal)));
        }
    };

    while index < characters.len() {
        let character = characters[index];
        if character == '\'' {
            if characters.get(index + 1) == Some(&'\'') {
                literal.push('\'');
                index += 2;
                continue;
            }
            quoted = !quoted;
            index += 1;
            continue;
        }
        if !quoted && matches!(character, 'y' | 'M' | 'd' | 'E') {
            flush_literal(&mut tokens, &mut literal);
            let mut width = 1_usize;
            while characters.get(index + width) == Some(&character) {
                width += 1;
            }
            match character {
                'y' if width >= 4 => {
                    year_count += 1;
                    tokens.push(DatePatternToken::Year(width));
                }
                'M' if width <= 2 => {
                    month_count += 1;
                    tokens.push(DatePatternToken::Month(width));
                }
                'd' if width <= 2 => {
                    day_count += 1;
                    tokens.push(DatePatternToken::Day(width));
                }
                'E' => tokens.push(DatePatternToken::Weekday),
                _ => return None,
            }
            index += width;
            continue;
        }
        if !quoted && character.is_ascii_alphabetic() {
            return None;
        }
        literal.push(sanitize_pattern_character(character));
        index += 1;
    }
    if quoted {
        return None;
    }
    flush_literal(&mut tokens, &mut literal);
    (year_count == 1 && month_count == 1 && day_count == 1).then_some(tokens)
}

fn format_java_date_pattern(date: NaiveDate, pattern: &str) -> Option<String> {
    let tokens = parse_java_date_pattern(pattern)?;
    let mut output = String::new();
    for token in tokens {
        match token {
            DatePatternToken::Year(width) => {
                output.push_str(&format!("{:0width$}", date.year(), width = width.max(4)));
            }
            DatePatternToken::Month(width) => {
                if width == 1 {
                    output.push_str(&date.month().to_string());
                } else {
                    output.push_str(&format!("{:02}", date.month()));
                }
            }
            DatePatternToken::Day(width) => {
                if width == 1 {
                    output.push_str(&date.day().to_string());
                } else {
                    output.push_str(&format!("{:02}", date.day()));
                }
            }
            DatePatternToken::Weekday => output.push_str(&date.format("%A").to_string()),
            DatePatternToken::Literal(value) => output.push_str(&value),
        }
    }
    Some(output)
}

fn safe_date_prefix(date: NaiveDate, pattern: &str) -> String {
    let mut value = format_java_date_pattern(date, pattern)
        .unwrap_or_else(|| date.format("%Y-%m-%d").to_string());
    if value.to_ascii_lowercase().ends_with(".md") {
        value.truncate(value.len() - 3);
    }
    sanitize_file_stem(&value)
}

fn sanitize_pattern_character(character: char) -> char {
    if character.is_control() || r#"<>:"/\|?*"#.contains(character) {
        '_'
    } else {
        character
    }
}

fn extract_common_date(file_name_stem: &str) -> Option<NaiveDate> {
    let patterns = COMMON_DATE_RES.get_or_init(|| {
        [
            r"(?:^|[^0-9])([0-9]{4})[-._]([0-9]{1,2})[-._]([0-9]{1,2})(?:$|[^0-9])",
            r"(?:^|[^0-9])([0-9]{4})年([0-9]{1,2})月([0-9]{1,2})日(?:$|[^0-9])",
            r"(?:^|[^0-9])([0-9]{4})([0-9]{2})([0-9]{2})(?:$|[^0-9])",
        ]
        .into_iter()
        .map(|source| Regex::new(source).expect("constant common date regex"))
        .collect()
    });
    patterns.iter().find_map(|pattern| {
        let captures = pattern.captures(file_name_stem)?;
        let year = captures.get(1)?.as_str().parse().ok()?;
        let month = captures.get(2)?.as_str().parse().ok()?;
        let day = captures.get(3)?.as_str().parse().ok()?;
        NaiveDate::from_ymd_opt(year, month, day)
    })
}

fn markdown_stem(file_name: &str) -> String {
    if file_name.to_ascii_lowercase().ends_with(".md") {
        file_name[..file_name.len() - 3].to_owned()
    } else {
        file_name.to_owned()
    }
}

fn normalize_markdown_file_name(value: &str) -> String {
    let stem = if value.to_ascii_lowercase().ends_with(".md") {
        &value[..value.len() - 3]
    } else {
        value
    };
    format!("{}.md", sanitize_file_stem(stem))
}

fn sanitize_file_stem(value: &str) -> String {
    let mut normalized = value
        .trim()
        .chars()
        .map(|character| {
            if character.is_control() || r#"<>:"/\|?*"#.contains(character) {
                '_'
            } else {
                character
            }
        })
        .collect::<String>();
    normalized = normalized.trim_end_matches([' ', '.']).to_owned();
    if normalized.is_empty() {
        normalized = "新日记".to_owned();
    }
    if is_reserved_windows_stem(&normalized) {
        normalized.insert(0, '_');
    }
    while normalized.len() > 180 {
        normalized.pop();
    }
    normalized
}

fn is_reserved_windows_stem(value: &str) -> bool {
    let upper = value
        .split('.')
        .next()
        .unwrap_or(value)
        .trim()
        .to_ascii_uppercase();
    matches!(upper.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || (upper.len() == 4
            && (upper.starts_with("COM") || upper.starts_with("LPT"))
            && upper.as_bytes()[3].is_ascii_digit()
            && upper.as_bytes()[3] != b'0')
}

fn word_count(value: &str) -> usize {
    let mut count = 0_usize;
    let mut in_latin_word = false;
    for character in value.chars() {
        let cjk = matches!(
            character as u32,
            0x3400..=0x4DBF | 0x4E00..=0x9FFF | 0xF900..=0xFAFF
        );
        if cjk {
            count += 1;
            in_latin_word = false;
        } else if character.is_alphanumeric() {
            if !in_latin_word {
                count += 1;
                in_latin_word = true;
            }
        } else {
            in_latin_word = false;
        }
    }
    count
}

fn sha256(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

fn modified_millis(metadata: &fs::Metadata) -> i64 {
    metadata
        .modified()
        .ok()
        .and_then(|value| value.duration_since(UNIX_EPOCH).ok())
        .map(|value| value.as_millis().min(i64::MAX as u128) as i64)
        .unwrap_or(0)
}

fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| value.as_millis().min(i64::MAX as u128) as i64)
        .unwrap_or(0)
}

#[cfg(windows)]
fn is_reparse_point(metadata: &fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;
    const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x0400;
    metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0
}

#[cfg(not(windows))]
fn is_reparse_point(metadata: &fs::Metadata) -> bool {
    metadata.file_type().is_symlink()
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn rejects_path_traversal() {
        let root = tempdir().expect("temporary directory");
        let error = load_diary(root.path(), "..\\outside.md").expect_err("must reject traversal");
        assert_eq!(error.code, "DIARY_PATH_TRAVERSAL");
    }

    #[test]
    fn create_scan_and_detect_external_conflict() {
        let root = tempdir().expect("temporary directory");
        let date = NaiveDate::from_ymd_opt(2026, 7, 29).expect("valid date");
        let created =
            create_diary(root.path(), "第一天", date, "# {title}\n\n{date}").expect("create");
        let scan = scan_diaries(root.path()).expect("scan");
        assert_eq!(scan.documents.len(), 1);
        assert_eq!(scan.documents[0].date_iso, "2026-07-29");
        assert!(scan.documents[0].word_count >= 4);

        fs::write(root.path().join(&created.file_name), "external").expect("external edit");
        let result = save_diary(
            root.path(),
            &created.file_name,
            "mine",
            &created.version,
            false,
        )
        .expect("conflict outcome");
        assert!(matches!(result, SaveDiaryOutcome::Conflict { .. }));
        assert_eq!(
            fs::read_to_string(root.path().join(created.file_name)).expect("content"),
            "external"
        );
    }

    #[test]
    fn explicit_overwrite_recreates_an_externally_deleted_diary() {
        let root = tempdir().expect("temporary directory");
        let date = NaiveDate::from_ymd_opt(2026, 7, 29).expect("valid date");
        let created = create_diary(root.path(), "deleted", date, "# original").expect("create");
        fs::remove_file(root.path().join(&created.file_name)).expect("external deletion");

        let saved = save_diary(
            root.path(),
            &created.file_name,
            "# restored draft",
            &created.version,
            true,
        )
        .expect("explicit overwrite");
        let SaveDiaryOutcome::Saved { document } = saved else {
            panic!("explicit overwrite must recreate the missing diary");
        };
        assert_eq!(document.content, "# restored draft");
        assert_eq!(
            fs::read_to_string(root.path().join(&created.file_name)).expect("recreated content"),
            "# restored draft"
        );
    }

    #[test]
    fn save_round_trip_and_trash_collision_restore() {
        let root = tempdir().expect("temporary directory");
        let date = NaiveDate::from_ymd_opt(2026, 7, 29).expect("valid date");
        let created = create_diary(root.path(), "test", date, "# {title}").expect("create");
        let saved = save_diary(
            root.path(),
            &created.file_name,
            "# changed",
            &created.version,
            false,
        )
        .expect("save");
        let saved = match saved {
            SaveDiaryOutcome::Saved { document } => document,
            SaveDiaryOutcome::Conflict { .. } => panic!("unexpected conflict"),
        };
        assert_eq!(saved.content, "# changed");

        let trash = move_to_trash(root.path(), &saved.file_name).expect("trash");
        fs::write(root.path().join(&saved.file_name), "collision").expect("collision");
        let restored =
            restore_from_trash(root.path(), &trash.stored_name).expect("restore with collision");
        assert_ne!(restored.document.file_name, saved.file_name);
        assert_eq!(restored.document.content, "# changed");
    }

    #[test]
    fn sanitizes_windows_reserved_names() {
        assert_eq!(normalize_markdown_file_name("CON"), "_CON.md");
        assert_eq!(normalize_markdown_file_name("../x"), ".._x.md");
    }

    #[test]
    fn recovers_an_interrupted_safe_replacement() {
        let root = tempdir().expect("temporary directory");
        fs::write(root.path().join(".2026-07-29.md.dc-recovery"), "original")
            .expect("recovery file");
        let restored = load_diary(root.path(), "2026-07-29.md").expect("automatic recovery");
        assert_eq!(restored.content, "original");
        assert!(!root.path().join(".2026-07-29.md.dc-recovery").exists());
    }

    #[test]
    fn java_date_patterns_format_safe_windows_names() {
        let root = tempdir().expect("temporary directory");
        let date = NaiveDate::from_ymd_opt(2026, 7, 29).expect("valid date");
        let created = create_diary_with_pattern(
            root.path(),
            "记录",
            date,
            "# {title}\n{date}",
            "yyyy/MM/dd '日记' EEEE",
        )
        .expect("create using custom pattern");
        assert_eq!(created.file_name, "2026_07_29 日记 Wednesday 记录.md");
        assert_eq!(created.content, "# 记录\n2026-07-29");
        let renamed = rename_diary_with_pattern(
            root.path(),
            &created.file_name,
            "新版",
            date,
            "yyyy/MM/dd '日记' EEEE",
        )
        .expect("rename using custom pattern");
        assert_eq!(renamed.file_name, "2026_07_29 日记 Wednesday 新版.md");
        assert_eq!(renamed.content, created.content);
    }

    #[test]
    fn scans_compact_chinese_and_quoted_literal_patterns() {
        let cases = [
            ("yyyyMMdd", "20260729 记录", "2026-07-29"),
            ("yyyy年MM月dd日 EEEE", "2026年07月29日 星期三", "2026-07-29"),
            ("yyyy.MM.dd '日志'", "2026.07.29 日志", "2026-07-29"),
            ("yyyy_MM_dd", "2026_07_29", "2026-07-29"),
            ("yyyyMMdd''记录", "20260729'记录", "2026-07-29"),
        ];
        for (pattern, file_name, expected) in cases {
            let compiled = CompiledDatePattern::new(pattern).expect("supported pattern");
            assert_eq!(
                compiled
                    .extract(file_name)
                    .expect("date from pattern")
                    .format("%Y-%m-%d")
                    .to_string(),
                expected
            );
        }
    }

    #[test]
    fn scan_with_pattern_uses_pattern_before_modified_time() {
        let root = tempdir().expect("temporary directory");
        fs::write(root.path().join("2026年07月29日 星期三.md"), "# diary").expect("diary");
        let scan = scan_diaries_with_pattern(root.path(), "yyyy年MM月dd日 EEEE").expect("scan");
        assert_eq!(scan.documents.len(), 1);
        assert_eq!(scan.documents[0].date_iso, "2026-07-29");
    }
}
