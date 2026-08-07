//! Obsidian-compatible Markdown notes stored in a user-selected directory.
//!
//! The webview never supplies an absolute root. The only accepted root is the
//! one chosen with the native folder picker and persisted in the application
//! data directory. Every descendant is resolved component-by-component below
//! that root and reparse points (symlinks, junctions and mount points) are
//! rejected before any read or mutation.

use crate::AppState;
use crate::diary;
use crate::security::{
    CommandResult, SecurityError, SecurityErrorDto, open_regular_file_no_reparse,
    reject_reparse_point, resolve_existing_file_beneath, resolve_path_beneath,
    validate_relative_file_name, validate_relative_path,
};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64_STANDARD};
use rusqlite::{Connection, OptionalExtension, Transaction, params};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{HashSet, VecDeque};
use std::ffi::OsStr;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::{Component, Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Runtime, State};
use tauri_plugin_dialog::DialogExt;
use uuid::Uuid;

const NOTES_DTO_VERSION: u32 = 1;
const MAX_NOTE_BYTES: u64 = 4 * 1024 * 1024;
const MAX_MEDIA_IMPORT_BYTES: u64 = 64 * 1024 * 1024;
const MAX_MEDIA_PREVIEW_BYTES: u64 = 16 * 1024 * 1024;
const MAX_MEDIA_PREVIEW_TOTAL_BYTES: u64 = 32 * 1024 * 1024;
const MAX_FOLDER_ENTRIES: usize = 5_000;
const MAX_PREVIEW_TARGETS: usize = 2_000;
const MAX_MEDIA_SEARCH_ENTRIES: usize = 20_000;
const MAX_MEDIA_SEARCH_DEPTH: usize = 16;
const MAX_DELETE_ENTRIES: usize = 20_000;
const MAX_DELETE_DEPTH: usize = 32;
const IMAGE_EXTENSIONS: &[&str] = &["jpg", "jpeg", "png", "webp", "gif"];

static NOTES_WRITE_MUTEX: Mutex<()> = Mutex::new(());

/// Schema fragment for the explicit database 4 -> 5 migration.
///
/// The caller owns the transaction and advances `user_version` only after all
/// v5 fragments succeed. Deliberately do not use `IF NOT EXISTS`: an unexpected
/// pre-existing object must fail the encompassing migration and roll it back.
pub(crate) fn migrate(transaction: &Transaction<'_>) -> rusqlite::Result<()> {
    transaction.execute_batch(
        "CREATE TABLE windows_notes_settings (
            singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
            root_path TEXT NULL CHECK (root_path IS NULL OR (length(root_path) BETWEEN 1 AND 32768)),
            updated_at INTEGER NOT NULL
        );
        INSERT INTO windows_notes_settings (singleton_id, root_path, updated_at)
        VALUES (1, NULL, 0);",
    )
}

/// Windows-private root path. It is never merged into Android `notesTreeUri`
/// and is not part of the Android-compatible JSON shadow.
pub(crate) fn get_root_path(connection: &Connection) -> rusqlite::Result<Option<String>> {
    connection
        .query_row(
            "SELECT root_path FROM windows_notes_settings WHERE singleton_id = 1",
            [],
            |row| row.get(0),
        )
        .optional()
        .map(Option::flatten)
}

pub(crate) fn set_root_path(
    connection: &Connection,
    root_path: Option<&str>,
    updated_at: i64,
) -> rusqlite::Result<()> {
    connection.execute(
        "UPDATE windows_notes_settings
         SET root_path = ?1, updated_at = ?2
         WHERE singleton_id = 1",
        params![root_path, updated_at],
    )?;
    Ok(())
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct NotesRootStateV1 {
    schema_version: u32,
    configured: bool,
    display_name: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct NoteFileVersionV1 {
    sha256: String,
    size: u64,
    modified_at: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum NoteEntryKindV1 {
    Folder,
    Note,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct NoteEntryV1 {
    schema_version: u32,
    relative_path: String,
    name: String,
    kind: NoteEntryKindV1,
    size: u64,
    modified_at: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct NoteFolderSnapshotV1 {
    schema_version: u32,
    relative_path: String,
    display_name: String,
    entries: Vec<NoteEntryV1>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct NoteDocumentV1 {
    schema_version: u32,
    relative_path: String,
    folder_relative_path: String,
    name: String,
    content: String,
    version: NoteFileVersionV1,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct NoteFolderRequestV1 {
    schema_version: u32,
    #[serde(default)]
    folder_path: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct CreateNoteEntryRequestV1 {
    schema_version: u32,
    #[serde(default)]
    parent_path: String,
    name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct NotePathRequestV1 {
    schema_version: u32,
    relative_path: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct RenameNoteEntryRequestV1 {
    schema_version: u32,
    relative_path: String,
    kind: NoteEntryKindV1,
    name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct DeleteNoteEntryRequestV1 {
    schema_version: u32,
    relative_path: String,
    kind: NoteEntryKindV1,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct IncomingNoteFileVersionV1 {
    sha256: String,
    size: u64,
    modified_at: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum NoteSaveResolutionV1 {
    Normal,
    Overwrite,
    Copy,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct SaveNoteRequestV1 {
    schema_version: u32,
    relative_path: String,
    content: String,
    expected_version: IncomingNoteFileVersionV1,
    resolution: NoteSaveResolutionV1,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(
    tag = "status",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub(crate) enum SaveNoteResultV1 {
    Saved {
        schema_version: u32,
        document: NoteDocumentV1,
    },
    Conflict {
        schema_version: u32,
        reason: NoteConflictReasonV1,
        disk_document: Option<NoteDocumentV1>,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum NoteConflictReasonV1 {
    Changed,
    Deleted,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ImportedNoteMediaV1 {
    schema_version: u32,
    file_name: String,
    relative_path: String,
    markdown_target: String,
    markdown: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ResolveNoteMediaRequestV1 {
    schema_version: u32,
    note_relative_path: String,
    targets: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ResolvedNoteMediaV1 {
    target: String,
    data_url: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum NoteError {
    InvalidInput,
    NotConfigured,
    NotFound,
    NameExists,
    PathNotAllowed,
    TooLarge,
    InvalidUtf8,
    Storage,
}

impl From<SecurityError> for NoteError {
    fn from(value: SecurityError) -> Self {
        match value {
            SecurityError::InvalidInput => Self::InvalidInput,
            SecurityError::PathNotAllowed => Self::PathNotAllowed,
            SecurityError::NotFound => Self::NotFound,
            SecurityError::Storage | SecurityError::Crypto => Self::Storage,
            #[cfg(not(windows))]
            SecurityError::Unsupported => Self::Storage,
        }
    }
}

impl From<io::Error> for NoteError {
    fn from(value: io::Error) -> Self {
        match value.kind() {
            io::ErrorKind::NotFound => Self::NotFound,
            io::ErrorKind::InvalidInput | io::ErrorKind::InvalidData => Self::InvalidInput,
            io::ErrorKind::AlreadyExists => Self::NameExists,
            _ => Self::Storage,
        }
    }
}

impl From<NoteError> for SecurityErrorDto {
    fn from(value: NoteError) -> Self {
        match value {
            NoteError::InvalidInput | NoteError::InvalidUtf8 => Self::invalid_input(),
            NoteError::NotConfigured => Self::new(
                "notes_directory_not_configured",
                "Choose a notes directory first.",
                true,
            ),
            NoteError::NotFound => Self::not_found(),
            NoteError::NameExists => Self::new(
                "name_exists",
                "An item with that name already exists.",
                true,
            ),
            NoteError::PathNotAllowed => Self::path_not_allowed(),
            NoteError::TooLarge => Self::new(
                "content_too_large",
                "The selected content exceeds the safety limit.",
                true,
            ),
            NoteError::Storage => Self::storage_unavailable(),
        }
    }
}

fn require_schema(version: u32) -> Result<(), NoteError> {
    if version == NOTES_DTO_VERSION {
        Ok(())
    } else {
        Err(NoteError::InvalidInput)
    }
}

#[tauri::command]
pub(crate) fn get_notes_root(state: State<'_, AppState>) -> CommandResult<NotesRootStateV1> {
    match configured_root(&state) {
        Ok(root) => Ok(root_state(Some(&root))),
        Err(NoteError::NotConfigured | NoteError::NotFound | NoteError::PathNotAllowed) => {
            Ok(root_state(None))
        }
        Err(error) => Err(error.into()),
    }
}

#[tauri::command]
pub(crate) fn select_notes_root<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<Option<NotesRootStateV1>> {
    let mut picker = app.dialog().file();
    if let Ok(current) = configured_root(&state) {
        picker = picker.set_directory(current);
    }
    let Some(selected) = picker.blocking_pick_folder() else {
        return Ok(None);
    };
    let selected = selected
        .into_path()
        .map_err(|_| SecurityErrorDto::path_not_allowed())?;
    let root = validate_notes_root(&selected).map_err(SecurityErrorDto::from)?;
    let root_string = root.to_string_lossy().into_owned();
    state
        .database
        .set_notes_root_path(Some(&root_string))
        .map_err(|_| SecurityErrorDto::storage_unavailable())?;
    Ok(Some(root_state(Some(&root))))
}

#[tauri::command]
pub(crate) fn forget_notes_root(state: State<'_, AppState>) -> CommandResult<()> {
    state
        .database
        .set_notes_root_path(None)
        .map_err(|_| SecurityErrorDto::storage_unavailable())?;
    Ok(())
}

#[tauri::command]
pub(crate) fn list_note_folder(
    request: NoteFolderRequestV1,
    state: State<'_, AppState>,
) -> CommandResult<NoteFolderSnapshotV1> {
    require_schema(request.schema_version).map_err(SecurityErrorDto::from)?;
    let root = configured_root(&state).map_err(SecurityErrorDto::from)?;
    scan_folder(&root, &request.folder_path).map_err(SecurityErrorDto::from)
}

#[tauri::command]
pub(crate) fn create_note_folder(
    request: CreateNoteEntryRequestV1,
    state: State<'_, AppState>,
) -> CommandResult<NoteEntryV1> {
    require_schema(request.schema_version).map_err(SecurityErrorDto::from)?;
    let root = configured_root(&state).map_err(SecurityErrorDto::from)?;
    let _guard = lock_writer()?;
    create_folder(&root, &request.parent_path, &request.name).map_err(SecurityErrorDto::from)
}

#[tauri::command]
pub(crate) fn create_note(
    request: CreateNoteEntryRequestV1,
    state: State<'_, AppState>,
) -> CommandResult<NoteDocumentV1> {
    require_schema(request.schema_version).map_err(SecurityErrorDto::from)?;
    let root = configured_root(&state).map_err(SecurityErrorDto::from)?;
    let _guard = lock_writer()?;
    create_note_file(&root, &request.parent_path, &request.name).map_err(SecurityErrorDto::from)
}

#[tauri::command]
pub(crate) fn open_note(
    request: NotePathRequestV1,
    state: State<'_, AppState>,
) -> CommandResult<NoteDocumentV1> {
    require_schema(request.schema_version).map_err(SecurityErrorDto::from)?;
    let root = configured_root(&state).map_err(SecurityErrorDto::from)?;
    load_note(&root, &request.relative_path).map_err(SecurityErrorDto::from)
}

#[tauri::command]
pub(crate) fn rename_note_entry(
    request: RenameNoteEntryRequestV1,
    state: State<'_, AppState>,
) -> CommandResult<NoteEntryV1> {
    require_schema(request.schema_version).map_err(SecurityErrorDto::from)?;
    let root = configured_root(&state).map_err(SecurityErrorDto::from)?;
    let _guard = lock_writer()?;
    rename_entry(&root, &request.relative_path, request.kind, &request.name)
        .map_err(SecurityErrorDto::from)
}

#[tauri::command]
pub(crate) fn delete_note_entry(
    request: DeleteNoteEntryRequestV1,
    state: State<'_, AppState>,
) -> CommandResult<()> {
    require_schema(request.schema_version).map_err(SecurityErrorDto::from)?;
    let root = configured_root(&state).map_err(SecurityErrorDto::from)?;
    let _guard = lock_writer()?;
    delete_entry(&root, &request.relative_path, request.kind).map_err(SecurityErrorDto::from)
}

#[tauri::command]
pub(crate) fn save_note(
    request: SaveNoteRequestV1,
    state: State<'_, AppState>,
) -> CommandResult<SaveNoteResultV1> {
    require_schema(request.schema_version).map_err(SecurityErrorDto::from)?;
    validate_incoming_version(&request.expected_version).map_err(SecurityErrorDto::from)?;
    if request.content.len() as u64 > MAX_NOTE_BYTES {
        return Err(SecurityErrorDto::from(NoteError::TooLarge));
    }
    let root = configured_root(&state).map_err(SecurityErrorDto::from)?;
    let _guard = lock_writer()?;
    save_note_file(&root, request).map_err(SecurityErrorDto::from)
}

#[tauri::command]
pub(crate) fn select_and_import_note_media<R: Runtime>(
    request: NotePathRequestV1,
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<Option<ImportedNoteMediaV1>> {
    require_schema(request.schema_version).map_err(SecurityErrorDto::from)?;
    let root = configured_root(&state).map_err(SecurityErrorDto::from)?;
    let note = load_note(&root, &request.relative_path).map_err(SecurityErrorDto::from)?;

    let Some(source) = app
        .dialog()
        .file()
        .add_filter("Images", IMAGE_EXTENSIONS)
        .blocking_pick_file()
    else {
        return Ok(None);
    };
    let source = source
        .into_path()
        .map_err(|_| SecurityErrorDto::path_not_allowed())?;

    // A second native picker is deliberate: every import requires an explicit
    // destination decision inside the current vault.
    let Some(destination) = app
        .dialog()
        .file()
        .set_directory(&root)
        .blocking_pick_folder()
    else {
        return Ok(None);
    };
    let destination = destination
        .into_path()
        .map_err(|_| SecurityErrorDto::path_not_allowed())?;
    let destination =
        validate_selected_destination(&root, &destination).map_err(SecurityErrorDto::from)?;

    let _guard = lock_writer()?;
    import_media(&root, &source, &destination, &note)
        .map_err(SecurityErrorDto::from)
        .map(Some)
}

#[tauri::command]
pub(crate) fn resolve_note_media(
    request: ResolveNoteMediaRequestV1,
    state: State<'_, AppState>,
) -> CommandResult<Vec<ResolvedNoteMediaV1>> {
    require_schema(request.schema_version).map_err(SecurityErrorDto::from)?;
    if request.targets.len() > MAX_PREVIEW_TARGETS {
        return Err(SecurityErrorDto::invalid_input());
    }
    let root = configured_root(&state).map_err(SecurityErrorDto::from)?;
    let note_path = validated_note_relative_path(&request.note_relative_path)
        .map_err(SecurityErrorDto::from)?;
    // Opening the note proves that the caller's scope is a current Markdown
    // file inside the selected root before any media lookup is attempted.
    load_note(&root, &request.note_relative_path).map_err(SecurityErrorDto::from)?;
    resolve_media_batch(&root, &note_path, &request.targets).map_err(SecurityErrorDto::from)
}

fn lock_writer() -> CommandResult<std::sync::MutexGuard<'static, ()>> {
    NOTES_WRITE_MUTEX
        .lock()
        .map_err(|_| SecurityErrorDto::storage_unavailable())
}

fn root_state(root: Option<&Path>) -> NotesRootStateV1 {
    NotesRootStateV1 {
        schema_version: NOTES_DTO_VERSION,
        configured: root.is_some(),
        display_name: root.map(root_display_name),
    }
}

fn root_display_name(root: &Path) -> String {
    root.file_name()
        .and_then(OsStr::to_str)
        .filter(|value| !value.trim().is_empty())
        .unwrap_or("Notes")
        .chars()
        .take(240)
        .collect()
}

fn configured_root(state: &AppState) -> Result<PathBuf, NoteError> {
    let root = state
        .database
        .get_notes_root_path()
        .map_err(|_| NoteError::Storage)?
        .ok_or(NoteError::NotConfigured)?;
    if root.is_empty() || root.encode_utf16().count() > 32_768 {
        return Err(NoteError::Storage);
    }
    validate_notes_root(Path::new(&root))
}

fn validate_notes_root(root: &Path) -> Result<PathBuf, NoteError> {
    diary::validate_directory(root).map_err(|error| match error.code.as_str() {
        "DIARY_DIRECTORY_INVALID" => NoteError::InvalidInput,
        "DIARY_DIRECTORY_REPARSE_POINT" => NoteError::PathNotAllowed,
        _ => NoteError::Storage,
    })
}

fn scan_folder(root: &Path, relative: &str) -> Result<NoteFolderSnapshotV1, NoteError> {
    let folder = resolve_folder(root, relative)?;
    recover_folder(&folder)?;
    let mut entries = Vec::new();
    for entry in fs::read_dir(&folder)? {
        if entries.len() >= MAX_FOLDER_ENTRIES {
            return Err(NoteError::TooLarge);
        }
        let entry = entry?;
        let name = entry
            .file_name()
            .to_str()
            .map(str::to_owned)
            .ok_or(NoteError::InvalidInput)?;
        let metadata = fs::symlink_metadata(entry.path())?;
        if is_reparse(&metadata) {
            continue;
        }
        let kind = if metadata.is_dir() {
            NoteEntryKindV1::Folder
        } else if metadata.is_file()
            && Path::new(&name)
                .extension()
                .and_then(OsStr::to_str)
                .is_some_and(|value| value.eq_ignore_ascii_case("md"))
        {
            NoteEntryKindV1::Note
        } else {
            continue;
        };
        let child_relative = join_relative(relative, &name)?;
        entries.push(NoteEntryV1 {
            schema_version: NOTES_DTO_VERSION,
            relative_path: child_relative,
            name,
            kind,
            size: if kind == NoteEntryKindV1::Note {
                metadata.len()
            } else {
                0
            },
            modified_at: modified_millis(&metadata),
        });
    }
    entries.sort_by(|left, right| {
        entry_kind_rank(left.kind)
            .cmp(&entry_kind_rank(right.kind))
            .then_with(|| left.name.to_lowercase().cmp(&right.name.to_lowercase()))
            .then_with(|| left.name.cmp(&right.name))
    });
    Ok(NoteFolderSnapshotV1 {
        schema_version: NOTES_DTO_VERSION,
        relative_path: normalize_folder_relative(relative)?,
        display_name: if relative.is_empty() {
            root_display_name(root)
        } else {
            Path::new(relative)
                .file_name()
                .and_then(OsStr::to_str)
                .unwrap_or("Notes")
                .to_owned()
        },
        entries,
    })
}

fn entry_kind_rank(kind: NoteEntryKindV1) -> u8 {
    match kind {
        NoteEntryKindV1::Folder => 0,
        NoteEntryKindV1::Note => 1,
    }
}

fn create_folder(root: &Path, parent: &str, raw_name: &str) -> Result<NoteEntryV1, NoteError> {
    let parent_path = resolve_folder(root, parent)?;
    let name = normalize_entry_name(raw_name, false)?;
    require_no_sibling(&parent_path, &name, None)?;
    let relative = join_relative(parent, &name)?;
    let target = resolve_path_beneath(root, &relative)?;
    fs::create_dir(&target)?;
    let metadata = fs::symlink_metadata(&target)?;
    if !metadata.is_dir() || is_reparse(&metadata) {
        let _ = fs::remove_dir(&target);
        return Err(NoteError::Storage);
    }
    Ok(NoteEntryV1 {
        schema_version: NOTES_DTO_VERSION,
        relative_path: relative,
        name,
        kind: NoteEntryKindV1::Folder,
        size: 0,
        modified_at: modified_millis(&metadata),
    })
}

fn create_note_file(
    root: &Path,
    parent: &str,
    raw_name: &str,
) -> Result<NoteDocumentV1, NoteError> {
    let parent_path = resolve_folder(root, parent)?;
    let name = normalize_entry_name(raw_name, true)?;
    require_no_sibling(&parent_path, &name, None)?;
    let relative = join_relative(parent, &name)?;
    let target = resolve_path_beneath(root, &relative)?;
    let title = strip_markdown_extension(&name);
    let content = format!("# {title}\n\n");
    atomic_write_new(&target, content.as_bytes(), MAX_NOTE_BYTES)?;
    load_note(root, &relative)
}

fn load_note(root: &Path, relative: &str) -> Result<NoteDocumentV1, NoteError> {
    let relative_path = validated_note_relative_path(relative)?;
    let normalized = path_to_slashes(&relative_path)?;
    let target = root.join(&relative_path);
    recover_target_if_needed(&target, MAX_NOTE_BYTES)?;
    let target = resolve_existing_file_beneath(root, &normalized)?;
    let bytes = read_bounded_regular(&target, MAX_NOTE_BYTES)?;
    let content = String::from_utf8(bytes.clone()).map_err(|_| NoteError::InvalidUtf8)?;
    let metadata = fs::metadata(&target)?;
    let folder_relative_path = relative_path
        .parent()
        .filter(|value| !value.as_os_str().is_empty())
        .map(path_to_slashes)
        .transpose()?
        .unwrap_or_default();
    let name = relative_path
        .file_name()
        .and_then(OsStr::to_str)
        .ok_or(NoteError::InvalidInput)?
        .to_owned();
    Ok(NoteDocumentV1 {
        schema_version: NOTES_DTO_VERSION,
        relative_path: normalized,
        folder_relative_path,
        name,
        content,
        version: NoteFileVersionV1 {
            sha256: sha256(&bytes),
            size: bytes.len() as u64,
            modified_at: modified_millis(&metadata),
        },
    })
}

fn rename_entry(
    root: &Path,
    relative: &str,
    kind: NoteEntryKindV1,
    raw_name: &str,
) -> Result<NoteEntryV1, NoteError> {
    let validated = validate_relative_path(relative)?;
    let normalized = path_to_slashes(&validated)?;
    let source = match kind {
        NoteEntryKindV1::Folder => resolve_folder(root, &normalized)?,
        NoteEntryKindV1::Note => {
            validated_note_relative_path(&normalized)?;
            resolve_existing_file_beneath(root, &normalized)?
        }
    };
    if source == root {
        return Err(NoteError::PathNotAllowed);
    }
    let parent = source.parent().ok_or(NoteError::PathNotAllowed)?;
    let name = normalize_entry_name(raw_name, kind == NoteEntryKindV1::Note)?;
    let old_name = source
        .file_name()
        .and_then(OsStr::to_str)
        .ok_or(NoteError::InvalidInput)?;
    if old_name == name {
        return entry_from_path(root, &source, kind);
    }
    require_no_sibling(parent, &name, Some(old_name))?;
    let target = parent.join(&name);
    reject_reparse_point(&target)?;
    if target.exists() && !target.eq(&source) {
        return Err(NoteError::NameExists);
    }
    fs::rename(&source, &target)?;
    entry_from_path(root, &target, kind)
}

fn delete_entry(root: &Path, relative: &str, kind: NoteEntryKindV1) -> Result<(), NoteError> {
    let validated = validate_relative_path(relative)?;
    let normalized = path_to_slashes(&validated)?;
    let target = match kind {
        NoteEntryKindV1::Folder => resolve_folder(root, &normalized)?,
        NoteEntryKindV1::Note => {
            validated_note_relative_path(&normalized)?;
            resolve_existing_file_beneath(root, &normalized)?
        }
    };
    if target == root {
        return Err(NoteError::PathNotAllowed);
    }
    match kind {
        NoteEntryKindV1::Note => fs::remove_file(target)?,
        NoteEntryKindV1::Folder => {
            preflight_delete_tree(&target)?;
            fs::remove_dir_all(target)?;
        }
    }
    Ok(())
}

fn save_note_file(root: &Path, request: SaveNoteRequestV1) -> Result<SaveNoteResultV1, NoteError> {
    let relative = path_to_slashes(&validated_note_relative_path(&request.relative_path)?)?;
    if request.resolution == NoteSaveResolutionV1::Copy {
        let copy_relative = available_conflict_name(root, &relative)?;
        let target = resolve_path_beneath(root, &copy_relative)?;
        atomic_write_new(&target, request.content.as_bytes(), MAX_NOTE_BYTES)?;
        return Ok(SaveNoteResultV1::Saved {
            schema_version: NOTES_DTO_VERSION,
            document: load_note(root, &copy_relative)?,
        });
    }

    let disk = match load_note(root, &relative) {
        Ok(document) => Some(document),
        Err(NoteError::NotFound) => None,
        Err(error) => return Err(error),
    };
    if request.resolution == NoteSaveResolutionV1::Normal {
        let Some(disk) = disk else {
            return Ok(SaveNoteResultV1::Conflict {
                schema_version: NOTES_DTO_VERSION,
                reason: NoteConflictReasonV1::Deleted,
                disk_document: None,
            });
        };
        if disk.version.sha256 != request.expected_version.sha256
            || disk.version.size != request.expected_version.size
        {
            return Ok(SaveNoteResultV1::Conflict {
                schema_version: NOTES_DTO_VERSION,
                reason: NoteConflictReasonV1::Changed,
                disk_document: Some(disk),
            });
        }
    }

    let target = resolve_path_beneath(root, &relative)?;
    if target.exists() {
        atomic_write_replace(&target, request.content.as_bytes(), MAX_NOTE_BYTES)?;
    } else {
        let parent_relative = validated_note_relative_path(&relative)?
            .parent()
            .filter(|value| !value.as_os_str().is_empty())
            .map(path_to_slashes)
            .transpose()?
            .unwrap_or_default();
        resolve_folder(root, &parent_relative)?;
        atomic_write_new(&target, request.content.as_bytes(), MAX_NOTE_BYTES)?;
    }
    Ok(SaveNoteResultV1::Saved {
        schema_version: NOTES_DTO_VERSION,
        document: load_note(root, &relative)?,
    })
}

fn import_media(
    root: &Path,
    source: &Path,
    destination: &Path,
    note: &NoteDocumentV1,
) -> Result<ImportedNoteMediaV1, NoteError> {
    reject_reparse_point(source)?;
    let source_file = open_regular_file_no_reparse(source)?;
    let source_name = source
        .file_name()
        .and_then(OsStr::to_str)
        .ok_or(NoteError::InvalidInput)?;
    let requested = normalize_media_name(source_name)?;
    let bytes = read_bounded_handle(source_file, MAX_MEDIA_IMPORT_BYTES)?;
    validate_image_signature(&bytes, &requested)?;
    let file_name = unique_sibling_name(destination, &requested)?;
    let target = destination.join(&file_name);
    reject_reparse_point(&target)?;
    atomic_write_new(&target, &bytes, MAX_MEDIA_IMPORT_BYTES)?;
    let verified = read_bounded_regular(&target, MAX_MEDIA_IMPORT_BYTES)?;
    if verified.len() != bytes.len() || sha256(&verified) != sha256(&bytes) {
        let _ = fs::remove_file(&target);
        return Err(NoteError::Storage);
    }
    let relative_path = target
        .strip_prefix(root)
        .map_err(|_| NoteError::PathNotAllowed)
        .and_then(path_to_slashes)?;
    let note_path = validated_note_relative_path(&note.relative_path)?;
    let note_parent = note_path.parent().unwrap_or_else(|| Path::new(""));
    let markdown_target = relative_path_between(note_parent, Path::new(&relative_path))?;
    let caption = Path::new(&file_name)
        .file_stem()
        .and_then(OsStr::to_str)
        .unwrap_or("image")
        .replace(']', "_");
    let markdown = format!("![{caption}](<{markdown_target}>)");
    Ok(ImportedNoteMediaV1 {
        schema_version: NOTES_DTO_VERSION,
        file_name,
        relative_path,
        markdown_target,
        markdown,
    })
}

fn resolve_media_batch(
    root: &Path,
    note_path: &Path,
    targets: &[String],
) -> Result<Vec<ResolvedNoteMediaV1>, NoteError> {
    let note_parent = note_path.parent().unwrap_or_else(|| Path::new(""));
    let mut seen = HashSet::new();
    let mut output = Vec::new();
    let mut total = 0_u64;
    for original in targets {
        if !seen.insert(original.as_str()) {
            continue;
        }
        let Some(cleaned) = normalize_markdown_target(original) else {
            continue;
        };
        let direct_relative = combine_target(note_parent, &cleaned);
        let direct = direct_relative
            .as_deref()
            .and_then(|relative| resolve_case_insensitive_file(root, relative).ok());
        let fallback = if direct.is_none() && !cleaned.contains('/') && !cleaned.contains('\\') {
            find_media_by_name(root, &cleaned)?
        } else {
            None
        };
        let Some(path) = direct.or(fallback) else {
            continue;
        };
        let Some(mime) = image_mime(&path) else {
            continue;
        };
        let file = open_regular_file_no_reparse(&path)?;
        let metadata = file.metadata()?;
        if metadata.len() > MAX_MEDIA_PREVIEW_BYTES
            || total.saturating_add(metadata.len()) > MAX_MEDIA_PREVIEW_TOTAL_BYTES
        {
            continue;
        }
        let bytes = read_bounded_handle(file, MAX_MEDIA_PREVIEW_BYTES)?;
        let name = path
            .file_name()
            .and_then(OsStr::to_str)
            .ok_or(NoteError::InvalidInput)?;
        if validate_image_signature(&bytes, name).is_err() {
            continue;
        }
        total += bytes.len() as u64;
        output.push(ResolvedNoteMediaV1 {
            target: original.clone(),
            data_url: format!("data:{mime};base64,{}", BASE64_STANDARD.encode(bytes)),
        });
    }
    Ok(output)
}

fn resolve_folder(root: &Path, relative: &str) -> Result<PathBuf, NoteError> {
    if relative.is_empty() {
        return validate_notes_root(root);
    }
    let normalized = normalize_folder_relative(relative)?;
    let path = resolve_path_beneath(root, &normalized)?;
    let metadata = fs::symlink_metadata(&path)?;
    if !metadata.is_dir() || is_reparse(&metadata) {
        return Err(NoteError::PathNotAllowed);
    }
    Ok(path)
}

fn normalize_folder_relative(relative: &str) -> Result<String, NoteError> {
    if relative.is_empty() {
        Ok(String::new())
    } else {
        path_to_slashes(&validate_relative_path(relative)?)
    }
}

fn validated_note_relative_path(relative: &str) -> Result<PathBuf, NoteError> {
    let path = validate_relative_path(relative)?;
    let name = path
        .file_name()
        .and_then(OsStr::to_str)
        .ok_or(NoteError::InvalidInput)?;
    validate_relative_file_name(name, &["md"])?;
    Ok(path)
}

fn normalize_entry_name(raw: &str, markdown: bool) -> Result<String, NoteError> {
    let mut value = raw
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
    value = value.trim_end_matches([' ', '.']).to_owned();
    value = take_utf16(&value, 220);
    if markdown && !value.to_ascii_lowercase().ends_with(".md") {
        value.push_str(".md");
    }
    validate_relative_file_name(&value, if markdown { &["md"] } else { &[] }).map_err(Into::into)
}

fn normalize_media_name(raw: &str) -> Result<String, NoteError> {
    let extension = Path::new(raw)
        .extension()
        .and_then(OsStr::to_str)
        .map(str::to_ascii_lowercase)
        .filter(|value| IMAGE_EXTENSIONS.contains(&value.as_str()))
        .ok_or(NoteError::InvalidInput)?;
    let stem = Path::new(raw)
        .file_stem()
        .and_then(OsStr::to_str)
        .filter(|value| !value.trim().is_empty())
        .unwrap_or("image");
    normalize_entry_name(&format!("{stem}.{extension}"), false)
}

fn take_utf16(value: &str, maximum: usize) -> String {
    let mut used = 0_usize;
    value
        .chars()
        .take_while(|character| {
            let next = used + character.len_utf16();
            if next > maximum {
                false
            } else {
                used = next;
                true
            }
        })
        .collect()
}

fn require_no_sibling(
    parent: &Path,
    name: &str,
    ignored_name: Option<&str>,
) -> Result<(), NoteError> {
    for entry in fs::read_dir(parent)? {
        let entry = entry?;
        let sibling = entry.file_name();
        let sibling = sibling.to_str().ok_or(NoteError::InvalidInput)?;
        if ignored_name.is_some_and(|ignored| sibling.eq_ignore_ascii_case(ignored)) {
            continue;
        }
        if sibling.eq_ignore_ascii_case(name) {
            return Err(NoteError::NameExists);
        }
    }
    Ok(())
}

fn unique_sibling_name(parent: &Path, requested: &str) -> Result<String, NoteError> {
    if !sibling_exists(parent, requested)? {
        return Ok(requested.to_owned());
    }
    let path = Path::new(requested);
    let stem = path.file_stem().and_then(OsStr::to_str).unwrap_or("image");
    let extension = path.extension().and_then(OsStr::to_str).unwrap_or_default();
    for sequence in 2_u32..10_000 {
        let candidate = format!("{stem} ({sequence}).{extension}");
        if !sibling_exists(parent, &candidate)? {
            return Ok(candidate);
        }
    }
    Err(NoteError::TooLarge)
}

fn sibling_exists(parent: &Path, name: &str) -> Result<bool, NoteError> {
    for entry in fs::read_dir(parent)? {
        if entry?
            .file_name()
            .to_str()
            .is_some_and(|value| value.eq_ignore_ascii_case(name))
        {
            return Ok(true);
        }
    }
    Ok(false)
}

fn entry_from_path(
    root: &Path,
    path: &Path,
    kind: NoteEntryKindV1,
) -> Result<NoteEntryV1, NoteError> {
    // `resolve_path_beneath` returns a canonical path. On Windows that has a
    // `\\?\` prefix, so strip the same canonical root when creating an IPC
    // relative path instead of comparing it with the caller's display path.
    let root = fs::canonicalize(root)?;
    let path = fs::canonicalize(path)?;
    let metadata = fs::symlink_metadata(&path)?;
    let correct_kind = match kind {
        NoteEntryKindV1::Folder => metadata.is_dir(),
        NoteEntryKindV1::Note => metadata.is_file(),
    };
    if !correct_kind || is_reparse(&metadata) {
        return Err(NoteError::PathNotAllowed);
    }
    let name = path
        .file_name()
        .and_then(OsStr::to_str)
        .ok_or(NoteError::InvalidInput)?
        .to_owned();
    let relative_path = path
        .strip_prefix(&root)
        .map_err(|_| NoteError::PathNotAllowed)
        .and_then(path_to_slashes)?;
    Ok(NoteEntryV1 {
        schema_version: NOTES_DTO_VERSION,
        relative_path,
        name,
        kind,
        size: if kind == NoteEntryKindV1::Note {
            metadata.len()
        } else {
            0
        },
        modified_at: modified_millis(&metadata),
    })
}

fn validate_incoming_version(version: &IncomingNoteFileVersionV1) -> Result<(), NoteError> {
    if version.sha256.len() != 64
        || !version.sha256.bytes().all(|byte| byte.is_ascii_hexdigit())
        || version.size > MAX_NOTE_BYTES
        || version.modified_at < 0
    {
        return Err(NoteError::InvalidInput);
    }
    Ok(())
}

fn available_conflict_name(root: &Path, original: &str) -> Result<String, NoteError> {
    let path = validated_note_relative_path(original)?;
    let parent = path.parent().unwrap_or_else(|| Path::new(""));
    let name = path
        .file_name()
        .and_then(OsStr::to_str)
        .ok_or(NoteError::InvalidInput)?;
    let stem = strip_markdown_extension(name);
    let parent_path = resolve_folder(
        root,
        &if parent.as_os_str().is_empty() {
            String::new()
        } else {
            path_to_slashes(parent)?
        },
    )?;
    for sequence in 1_u32..10_000 {
        let suffix = if sequence == 1 {
            "DeskCubby conflict".to_owned()
        } else {
            format!("DeskCubby conflict {sequence}")
        };
        let candidate = normalize_entry_name(&format!("{stem} ({suffix}).md"), true)?;
        if !sibling_exists(&parent_path, &candidate)? {
            return if parent.as_os_str().is_empty() {
                Ok(candidate)
            } else {
                join_relative(&path_to_slashes(parent)?, &candidate)
            };
        }
    }
    Err(NoteError::TooLarge)
}

fn preflight_delete_tree(root: &Path) -> Result<(), NoteError> {
    let mut queue = VecDeque::from([(root.to_path_buf(), 0_usize)]);
    let mut visited = 0_usize;
    while let Some((directory, depth)) = queue.pop_front() {
        if depth > MAX_DELETE_DEPTH {
            return Err(NoteError::TooLarge);
        }
        let metadata = fs::symlink_metadata(&directory)?;
        if !metadata.is_dir() || is_reparse(&metadata) {
            return Err(NoteError::PathNotAllowed);
        }
        for entry in fs::read_dir(&directory)? {
            visited += 1;
            if visited > MAX_DELETE_ENTRIES {
                return Err(NoteError::TooLarge);
            }
            let path = entry?.path();
            let metadata = fs::symlink_metadata(&path)?;
            if is_reparse(&metadata) {
                return Err(NoteError::PathNotAllowed);
            }
            if metadata.is_dir() {
                queue.push_back((path, depth + 1));
            } else if !metadata.is_file() {
                return Err(NoteError::PathNotAllowed);
            }
        }
    }
    Ok(())
}

fn validate_selected_destination(root: &Path, selected: &Path) -> Result<PathBuf, NoteError> {
    let metadata = fs::symlink_metadata(selected)?;
    if !metadata.is_dir() || is_reparse(&metadata) {
        return Err(NoteError::PathNotAllowed);
    }
    let canonical = fs::canonicalize(selected)?;
    if canonical == root {
        return Ok(canonical);
    }
    let relative = selected
        .strip_prefix(root)
        .map_err(|_| NoteError::PathNotAllowed)
        .and_then(path_to_slashes)?;
    let checked = resolve_path_beneath(root, &relative)?;
    if checked != canonical {
        return Err(NoteError::PathNotAllowed);
    }
    Ok(checked)
}

fn normalize_markdown_target(raw: &str) -> Option<String> {
    let decoded = percent_decode_utf8(raw.trim().trim_matches(['<', '>']))?;
    let value = decoded
        .split('#')
        .next()
        .unwrap_or_default()
        .replace('\\', "/")
        .trim()
        .to_owned();
    if value.is_empty()
        || value.starts_with('/')
        || value.starts_with("//")
        || value.contains('?')
        || has_uri_scheme(&value)
    {
        None
    } else {
        Some(value)
    }
}

fn has_uri_scheme(value: &str) -> bool {
    let Some(colon) = value.find(':') else {
        return false;
    };
    let scheme = &value[..colon];
    !scheme.is_empty()
        && scheme.bytes().enumerate().all(|(index, byte)| {
            byte.is_ascii_alphabetic()
                || (index > 0 && matches!(byte, b'0'..=b'9' | b'+' | b'-' | b'.'))
        })
}

fn percent_decode_utf8(value: &str) -> Option<String> {
    let bytes = value.as_bytes();
    let mut output = Vec::with_capacity(bytes.len());
    let mut index = 0_usize;
    while index < bytes.len() {
        if bytes[index] == b'%' {
            let high = decode_hex(*bytes.get(index + 1)?)?;
            let low = decode_hex(*bytes.get(index + 2)?)?;
            output.push((high << 4) | low);
            index += 3;
        } else {
            output.push(bytes[index]);
            index += 1;
        }
    }
    String::from_utf8(output).ok()
}

fn decode_hex(value: u8) -> Option<u8> {
    match value {
        b'0'..=b'9' => Some(value - b'0'),
        b'a'..=b'f' => Some(value - b'a' + 10),
        b'A'..=b'F' => Some(value - b'A' + 10),
        _ => None,
    }
}

fn combine_target(note_parent: &Path, target: &str) -> Option<String> {
    let mut segments = note_parent
        .components()
        .filter_map(|component| match component {
            Component::Normal(value) => value.to_str().map(str::to_owned),
            _ => None,
        })
        .collect::<Vec<_>>();
    for segment in target.split('/') {
        match segment {
            "" | "." => {}
            ".." => {
                segments.pop()?;
            }
            value => {
                validate_relative_file_name(value, &[]).ok()?;
                segments.push(value.to_owned());
            }
        }
    }
    if segments.is_empty() {
        None
    } else {
        Some(segments.join("/"))
    }
}

fn resolve_case_insensitive_file(root: &Path, relative: &str) -> Result<PathBuf, NoteError> {
    let validated = validate_relative_path(relative)?;
    let mut current = root.to_path_buf();
    for component in validated.components() {
        let Component::Normal(wanted) = component else {
            return Err(NoteError::InvalidInput);
        };
        let wanted = wanted.to_str().ok_or(NoteError::InvalidInput)?;
        let mut found = None;
        for entry in fs::read_dir(&current)? {
            let entry = entry?;
            if entry
                .file_name()
                .to_str()
                .is_some_and(|value| value.eq_ignore_ascii_case(wanted))
            {
                found = Some(entry.path());
                break;
            }
        }
        current = found.ok_or(NoteError::NotFound)?;
        let metadata = fs::symlink_metadata(&current)?;
        if is_reparse(&metadata) {
            return Err(NoteError::PathNotAllowed);
        }
    }
    let canonical = fs::canonicalize(&current)?;
    if !canonical.starts_with(root) {
        return Err(NoteError::PathNotAllowed);
    }
    let metadata = fs::symlink_metadata(&canonical)?;
    if !metadata.is_file() || is_reparse(&metadata) {
        return Err(NoteError::PathNotAllowed);
    }
    Ok(canonical)
}

fn find_media_by_name(root: &Path, name: &str) -> Result<Option<PathBuf>, NoteError> {
    validate_relative_file_name(name, &[])?;
    let mut queue = VecDeque::from([(root.to_path_buf(), 0_usize)]);
    let mut visited = 0_usize;
    while let Some((directory, depth)) = queue.pop_front() {
        for entry in fs::read_dir(directory)? {
            visited += 1;
            if visited > MAX_MEDIA_SEARCH_ENTRIES {
                return Ok(None);
            }
            let entry = entry?;
            let path = entry.path();
            let metadata = fs::symlink_metadata(&path)?;
            if is_reparse(&metadata) {
                continue;
            }
            if metadata.is_file()
                && entry
                    .file_name()
                    .to_str()
                    .is_some_and(|value| value.eq_ignore_ascii_case(name))
            {
                return Ok(Some(fs::canonicalize(path)?));
            }
            if metadata.is_dir() && depth < MAX_MEDIA_SEARCH_DEPTH {
                queue.push_back((path, depth + 1));
            }
        }
    }
    Ok(None)
}

fn relative_path_between(from_folder: &Path, target: &Path) -> Result<String, NoteError> {
    let from = normal_components(from_folder)?;
    let to = normal_components(target)?;
    let mut common = 0_usize;
    while common < from.len() && common < to.len() && from[common].eq_ignore_ascii_case(&to[common])
    {
        common += 1;
    }
    let mut result = Vec::new();
    result.extend(std::iter::repeat_n("..".to_owned(), from.len() - common));
    result.extend(to.into_iter().skip(common));
    if result.is_empty() {
        return Err(NoteError::InvalidInput);
    }
    Ok(result.join("/"))
}

fn normal_components(path: &Path) -> Result<Vec<String>, NoteError> {
    path.components()
        .map(|component| match component {
            Component::Normal(value) => value
                .to_str()
                .map(str::to_owned)
                .ok_or(NoteError::InvalidInput),
            _ => Err(NoteError::InvalidInput),
        })
        .collect()
}

fn validate_image_signature(bytes: &[u8], name: &str) -> Result<(), NoteError> {
    let extension = Path::new(name)
        .extension()
        .and_then(OsStr::to_str)
        .unwrap_or_default()
        .to_ascii_lowercase();
    let valid = match extension.as_str() {
        "jpg" | "jpeg" => bytes.starts_with(&[0xff, 0xd8, 0xff]),
        "png" => bytes.starts_with(b"\x89PNG\r\n\x1a\n"),
        "webp" => bytes.len() >= 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP",
        "gif" => bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a"),
        _ => false,
    };
    if valid {
        Ok(())
    } else {
        Err(NoteError::InvalidInput)
    }
}

fn image_mime(path: &Path) -> Option<&'static str> {
    match path
        .extension()
        .and_then(OsStr::to_str)
        .unwrap_or_default()
        .to_ascii_lowercase()
        .as_str()
    {
        "jpg" | "jpeg" => Some("image/jpeg"),
        "png" => Some("image/png"),
        "webp" => Some("image/webp"),
        "gif" => Some("image/gif"),
        _ => None,
    }
}

fn read_bounded_regular(path: &Path, maximum: u64) -> Result<Vec<u8>, NoteError> {
    let file = open_regular_file_no_reparse(path)?;
    read_bounded_handle(file, maximum)
}

fn read_bounded_handle(file: File, maximum: u64) -> Result<Vec<u8>, NoteError> {
    let metadata = file.metadata()?;
    if !metadata.is_file() || metadata.len() > maximum {
        return Err(NoteError::TooLarge);
    }
    let mut bytes = Vec::with_capacity(metadata.len() as usize);
    file.take(maximum + 1).read_to_end(&mut bytes)?;
    if bytes.len() as u64 > maximum {
        return Err(NoteError::TooLarge);
    }
    if bytes.len() as u64 != metadata.len() {
        return Err(NoteError::Storage);
    }
    Ok(bytes)
}

fn atomic_write_new(target: &Path, bytes: &[u8], maximum: u64) -> Result<(), NoteError> {
    if bytes.len() as u64 > maximum {
        return Err(NoteError::TooLarge);
    }
    reject_reparse_point(target)?;
    if target.exists() {
        return Err(NoteError::NameExists);
    }
    let temp = sibling_temp_path(target, "new")?;
    write_and_verify_temp(&temp, bytes, maximum)?;
    if target.exists() {
        let _ = fs::remove_file(&temp);
        return Err(NoteError::NameExists);
    }
    if let Err(error) = fs::rename(&temp, target) {
        let _ = fs::remove_file(&temp);
        return Err(error.into());
    }
    verify_file_bytes(target, bytes, maximum)
}

fn atomic_write_replace(target: &Path, bytes: &[u8], maximum: u64) -> Result<(), NoteError> {
    if bytes.len() as u64 > maximum {
        return Err(NoteError::TooLarge);
    }
    recover_target_if_needed(target, maximum)?;
    reject_reparse_point(target)?;
    let temp = sibling_temp_path(target, "pending")?;
    let recovery = recovery_path(target)?;
    write_and_verify_temp(&temp, bytes, maximum)?;
    if recovery.exists() {
        reject_reparse_point(&recovery)?;
        fs::remove_file(&recovery)?;
    }
    if let Err(error) = fs::rename(target, &recovery) {
        let _ = fs::remove_file(&temp);
        return Err(error.into());
    }
    if let Err(error) = fs::rename(&temp, target) {
        let rollback = fs::rename(&recovery, target);
        let _ = fs::remove_file(&temp);
        return match rollback {
            Ok(()) => Err(error.into()),
            Err(_) => Err(NoteError::Storage),
        };
    }
    if let Err(error) = verify_file_bytes(target, bytes, maximum) {
        let _ = fs::remove_file(target);
        let _ = fs::rename(&recovery, target);
        return Err(error);
    }
    let _ = fs::remove_file(recovery);
    Ok(())
}

fn write_and_verify_temp(path: &Path, bytes: &[u8], maximum: u64) -> Result<(), NoteError> {
    let mut file = OpenOptions::new().write(true).create_new(true).open(path)?;
    if let Err(error) = file.write_all(bytes).and_then(|()| file.sync_all()) {
        let _ = fs::remove_file(path);
        return Err(error.into());
    }
    drop(file);
    if let Err(error) = verify_file_bytes(path, bytes, maximum) {
        let _ = fs::remove_file(path);
        return Err(error);
    }
    Ok(())
}

fn verify_file_bytes(path: &Path, expected: &[u8], maximum: u64) -> Result<(), NoteError> {
    let actual = read_bounded_regular(path, maximum)?;
    if actual.len() == expected.len() && sha256(&actual) == sha256(expected) {
        Ok(())
    } else {
        Err(NoteError::Storage)
    }
}

fn recover_folder(folder: &Path) -> Result<(), NoteError> {
    for entry in fs::read_dir(folder)? {
        let entry = entry?;
        let name = entry.file_name();
        let Some(name) = name.to_str() else {
            continue;
        };
        let Some(target_name) = name
            .strip_prefix('.')
            .and_then(|value| value.strip_suffix(".dc-recovery"))
        else {
            continue;
        };
        if validate_relative_file_name(target_name, &["md"]).is_err() {
            continue;
        }
        let recovery = entry.path();
        reject_reparse_point(&recovery)?;
        let target = folder.join(target_name);
        if target.exists() {
            fs::remove_file(recovery)?;
        } else {
            fs::rename(recovery, target)?;
        }
    }
    Ok(())
}

fn recover_target_if_needed(target: &Path, maximum: u64) -> Result<(), NoteError> {
    let recovery = recovery_path(target)?;
    if recovery.exists() {
        reject_reparse_point(&recovery)?;
        let _ = read_bounded_regular(&recovery, maximum)?;
        if target.exists() {
            fs::remove_file(recovery)?;
        } else {
            fs::rename(recovery, target)?;
        }
    }
    Ok(())
}

fn recovery_path(target: &Path) -> Result<PathBuf, NoteError> {
    let parent = target.parent().ok_or(NoteError::PathNotAllowed)?;
    let name = target
        .file_name()
        .and_then(OsStr::to_str)
        .ok_or(NoteError::InvalidInput)?;
    Ok(parent.join(format!(".{name}.dc-recovery")))
}

fn sibling_temp_path(target: &Path, label: &str) -> Result<PathBuf, NoteError> {
    let parent = target.parent().ok_or(NoteError::PathNotAllowed)?;
    let name = target
        .file_name()
        .and_then(OsStr::to_str)
        .ok_or(NoteError::InvalidInput)?;
    Ok(parent.join(format!(".{name}.dc-{label}-{}", Uuid::new_v4())))
}

fn join_relative(parent: &str, name: &str) -> Result<String, NoteError> {
    validate_relative_file_name(name, &[])?;
    if parent.is_empty() {
        Ok(name.to_owned())
    } else {
        let parent = normalize_folder_relative(parent)?;
        Ok(format!("{parent}/{name}"))
    }
}

fn path_to_slashes(path: &Path) -> Result<String, NoteError> {
    normal_components(path).map(|components| components.join("/"))
}

fn strip_markdown_extension(name: &str) -> String {
    if name.to_ascii_lowercase().ends_with(".md") {
        name[..name.len() - 3].to_owned()
    } else {
        name.to_owned()
    }
}

fn sha256(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

fn modified_millis(metadata: &fs::Metadata) -> i64 {
    metadata
        .modified()
        .unwrap_or(SystemTime::UNIX_EPOCH)
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .try_into()
        .unwrap_or(i64::MAX)
}

#[cfg(windows)]
fn is_reparse(metadata: &fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;
    const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x400;
    metadata.file_type().is_symlink()
        || metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0
}

#[cfg(not(windows))]
fn is_reparse(metadata: &fs::Metadata) -> bool {
    metadata.file_type().is_symlink()
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn version(document: &NoteDocumentV1) -> IncomingNoteFileVersionV1 {
        IncomingNoteFileVersionV1 {
            sha256: document.version.sha256.clone(),
            size: document.version.size,
            modified_at: document.version.modified_at,
        }
    }

    #[test]
    fn private_root_migration_obeys_the_callers_transaction() {
        let mut connection = Connection::open_in_memory().unwrap();
        {
            let transaction = connection.transaction().unwrap();
            migrate(&transaction).unwrap();
            set_root_path(&transaction, Some(r"C:\Users\Example\Notes"), 42).unwrap();
            assert_eq!(
                get_root_path(&transaction).unwrap().as_deref(),
                Some(r"C:\Users\Example\Notes")
            );
            transaction.rollback().unwrap();
        }
        let table_count: i64 = connection
            .query_row(
                "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'windows_notes_settings'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(table_count, 0);

        let transaction = connection.transaction().unwrap();
        migrate(&transaction).unwrap();
        transaction.commit().unwrap();
        assert_eq!(get_root_path(&connection).unwrap(), None);
        let duplicate = connection.transaction().unwrap();
        assert!(migrate(&duplicate).is_err());
    }

    #[test]
    fn names_match_android_and_reject_windows_devices() {
        assert_eq!(
            normalize_entry_name(" My note ", true).unwrap(),
            "My note.md"
        );
        assert_eq!(
            normalize_entry_name("folder/with:bad*chars.", false).unwrap(),
            "folder_with_bad_chars"
        );
        for invalid in ["CON", "nul.md", "LPT9", ".."] {
            assert!(normalize_entry_name(invalid, invalid.ends_with(".md")).is_err());
        }
    }

    #[test]
    fn folders_sort_first_and_non_markdown_files_stay_hidden() {
        let root = tempdir().unwrap();
        fs::create_dir(root.path().join("z-folder")).unwrap();
        fs::write(root.path().join("B.md"), "# B").unwrap();
        fs::write(root.path().join("a.md"), "# A").unwrap();
        fs::write(root.path().join("image.png"), b"png").unwrap();

        let snapshot = scan_folder(root.path(), "").unwrap();
        let names = snapshot
            .entries
            .iter()
            .map(|entry| entry.name.as_str())
            .collect::<Vec<_>>();
        assert_eq!(names, ["z-folder", "a.md", "B.md"]);
    }

    #[test]
    fn folders_and_markdown_notes_support_create_rename_and_delete() {
        let root = tempdir().unwrap();
        let folder = create_folder(root.path(), "", "Projects").unwrap();
        assert_eq!(folder.relative_path, "Projects");
        let note = create_note_file(root.path(), &folder.relative_path, "Plan").unwrap();
        assert_eq!(note.relative_path, "Projects/Plan.md");

        let renamed_note = rename_entry(
            root.path(),
            &note.relative_path,
            NoteEntryKindV1::Note,
            "Roadmap",
        )
        .unwrap();
        assert_eq!(renamed_note.relative_path, "Projects/Roadmap.md");
        let renamed_folder = rename_entry(
            root.path(),
            &folder.relative_path,
            NoteEntryKindV1::Folder,
            "Work",
        )
        .unwrap();
        assert_eq!(renamed_folder.relative_path, "Work");
        assert!(root.path().join("Work/Roadmap.md").is_file());

        delete_entry(root.path(), "Work/Roadmap.md", NoteEntryKindV1::Note).unwrap();
        delete_entry(root.path(), "Work", NoteEntryKindV1::Folder).unwrap();
        assert!(!root.path().join("Work").exists());
    }

    #[test]
    fn save_detects_external_change_until_overwrite_is_explicit() {
        let root = tempdir().unwrap();
        let original = create_note_file(root.path(), "", "sample").unwrap();
        fs::write(root.path().join("sample.md"), "# changed elsewhere\n").unwrap();

        let conflict = save_note_file(
            root.path(),
            SaveNoteRequestV1 {
                schema_version: NOTES_DTO_VERSION,
                relative_path: original.relative_path.clone(),
                content: "# DeskCubby draft\n".to_owned(),
                expected_version: version(&original),
                resolution: NoteSaveResolutionV1::Normal,
            },
        )
        .unwrap();
        assert!(matches!(
            conflict,
            SaveNoteResultV1::Conflict {
                reason: NoteConflictReasonV1::Changed,
                ..
            }
        ));
        assert_eq!(
            fs::read_to_string(root.path().join("sample.md")).unwrap(),
            "# changed elsewhere\n"
        );

        let saved = save_note_file(
            root.path(),
            SaveNoteRequestV1 {
                schema_version: NOTES_DTO_VERSION,
                relative_path: original.relative_path.clone(),
                content: "# DeskCubby draft\n".to_owned(),
                expected_version: version(&original),
                resolution: NoteSaveResolutionV1::Overwrite,
            },
        )
        .unwrap();
        assert!(matches!(saved, SaveNoteResultV1::Saved { .. }));
        assert_eq!(
            fs::read_to_string(root.path().join("sample.md")).unwrap(),
            "# DeskCubby draft\n"
        );
    }

    #[test]
    fn deleted_note_can_be_copied_without_recreating_original() {
        let root = tempdir().unwrap();
        let original = create_note_file(root.path(), "", "sample").unwrap();
        fs::remove_file(root.path().join("sample.md")).unwrap();
        let conflict = save_note_file(
            root.path(),
            SaveNoteRequestV1 {
                schema_version: NOTES_DTO_VERSION,
                relative_path: original.relative_path.clone(),
                content: "draft".to_owned(),
                expected_version: version(&original),
                resolution: NoteSaveResolutionV1::Normal,
            },
        )
        .unwrap();
        assert!(matches!(
            conflict,
            SaveNoteResultV1::Conflict {
                reason: NoteConflictReasonV1::Deleted,
                disk_document: None,
                ..
            }
        ));
        let saved = save_note_file(
            root.path(),
            SaveNoteRequestV1 {
                schema_version: NOTES_DTO_VERSION,
                relative_path: original.relative_path.clone(),
                content: "draft".to_owned(),
                expected_version: version(&original),
                resolution: NoteSaveResolutionV1::Copy,
            },
        )
        .unwrap();
        let SaveNoteResultV1::Saved { document, .. } = saved else {
            panic!("copy should save");
        };
        assert!(document.name.contains("DeskCubby conflict"));
        assert!(!root.path().join("sample.md").exists());
    }

    #[test]
    fn media_targets_never_escape_the_vault() {
        assert_eq!(
            combine_target(Path::new("projects/one"), "../images/p.png").as_deref(),
            Some("projects/images/p.png")
        );
        assert!(combine_target(Path::new(""), "../outside.png").is_none());
        assert!(normalize_markdown_target("https://example.test/image.png").is_none());
        assert!(normalize_markdown_target("C:/private.png").is_none());
        assert_eq!(
            normalize_markdown_target("<images/a%20b.png#crop>").as_deref(),
            Some("images/a b.png")
        );
    }

    #[test]
    fn imported_media_uses_a_portable_relative_link_and_sha_verified_copy() {
        let root = tempdir().unwrap();
        fs::create_dir_all(root.path().join("notes/deep")).unwrap();
        fs::create_dir(root.path().join("assets")).unwrap();
        let note = create_note_file(root.path(), "notes/deep", "entry").unwrap();
        let source_dir = tempdir().unwrap();
        let source = source_dir.path().join("photo.png");
        fs::write(&source, b"\x89PNG\r\n\x1a\nfixture").unwrap();

        let imported =
            import_media(root.path(), &source, &root.path().join("assets"), &note).unwrap();
        assert_eq!(imported.markdown_target, "../../assets/photo.png");
        assert_eq!(imported.markdown, "![photo](<../../assets/photo.png>)");
        assert_eq!(
            fs::read(root.path().join("assets/photo.png")).unwrap(),
            b"\x89PNG\r\n\x1a\nfixture"
        );
    }

    #[cfg(unix)]
    #[test]
    fn recursive_delete_rejects_symlinks_before_removing_anything() {
        use std::os::unix::fs::symlink;
        let root = tempdir().unwrap();
        let outside = tempdir().unwrap();
        fs::create_dir(root.path().join("folder")).unwrap();
        fs::write(outside.path().join("keep.md"), "keep").unwrap();
        symlink(outside.path(), root.path().join("folder/link")).unwrap();
        assert!(delete_entry(root.path(), "folder", NoteEntryKindV1::Folder).is_err());
        assert!(root.path().join("folder").exists());
        assert!(outside.path().join("keep.md").exists());
    }
}
