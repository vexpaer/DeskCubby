use crate::AppState;
use crate::backup::{self, BackupError};
use crate::db::{self, DataError};
use crate::diary::{self, SaveDiaryOutcome};
use crate::media;
use crate::models::{
    DailyEventTemplate, DateRecord, DateRecordDraft, HomeGreeting, LocalPaths, ManagedSettings,
    MealPhotoFilter, PoetryCategory, PoetryCategoryDraft, SavedPoem, SavedPoemDraft, Thought,
    ThoughtCategory, ThoughtCategoryDraft, ThoughtDraft,
};
use crate::poetry;
use crate::security::{
    CommandResult, SecurityError, SecurityErrorDto, dpapi_protect, dpapi_unprotect,
    open_regular_file_no_reparse, reject_reparse_point, resolve_existing_file_beneath,
    resolve_path_beneath, validate_relative_file_name,
};
use crate::updater;
use chrono::{DateTime, Local, NaiveDate, Utc};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use tauri::{AppHandle, Emitter, Runtime, State};
use tauri_plugin_dialog::DialogExt;
use uuid::Uuid;
use zeroize::Zeroizing;

pub(crate) static SETTINGS_UPDATE_MUTEX: Mutex<()> = Mutex::new(());
static BACKUP_MUTATION_MUTEX: Mutex<()> = Mutex::new(());
const MAX_TEXT_EXPORT_BYTES: usize = 8 * 1024 * 1024;
const MAX_BACKGROUND_IMAGE_BYTES: u64 = 64 * 1024 * 1024;

pub fn handler<R: Runtime>() -> impl Fn(tauri::ipc::Invoke<R>) -> bool + Send + Sync + 'static {
    tauri::generate_handler![
        get_ipc_protocol,
        get_home_snapshot,
        list_diaries,
        open_diary,
        create_diary,
        save_diary,
        rename_diary,
        trash_diary,
        restore_diary,
        delete_diary_permanently,
        rescan_diaries,
        resolve_media_asset,
        select_and_import_diary_image,
        list_meal_photos,
        select_and_import_meal_photos,
        export_meal_calendar_png,
        list_daily_templates,
        get_daily_record_context,
        create_daily_template,
        update_daily_template,
        delete_daily_template,
        reorder_daily_templates,
        append_daily_record,
        list_thoughts,
        create_thought,
        update_thought,
        delete_thought,
        restore_thought,
        reorder_thoughts,
        list_thought_categories,
        create_thought_category,
        update_thought_category,
        delete_thought_category,
        export_thought_category,
        list_date_records,
        create_date_record,
        update_date_record,
        delete_date_record,
        get_daily_poem,
        list_poems,
        create_poem,
        update_poem,
        delete_poem,
        list_poetry_categories,
        create_poetry_category,
        update_poetry_category,
        delete_poetry_category,
        move_poetry_category,
        set_poem_category,
        move_poem,
        list_poetry_presets,
        import_poetry_preset,
        crate::notes::get_notes_root,
        crate::notes::select_notes_root,
        crate::notes::forget_notes_root,
        crate::notes::list_note_folder,
        crate::notes::create_note_folder,
        crate::notes::create_note,
        crate::notes::open_note,
        crate::notes::rename_note_entry,
        crate::notes::delete_note_entry,
        crate::notes::save_note,
        crate::notes::select_and_import_note_media,
        crate::notes::resolve_note_media,
        crate::rss::get_rss_page,
        crate::rss::save_rss_subscription,
        crate::rss::delete_rss_subscription,
        crate::rss::set_rss_subscription_enabled,
        crate::rss::set_rss_preferences,
        crate::rss::refresh_rss,
        crate::rss::open_rss_article,
        crate::reader::get_reader_library,
        crate::reader::choose_reader_book,
        crate::reader::open_reader_book,
        crate::reader::save_reader_progress,
        crate::reader::save_reader_preferences,
        crate::reader::remove_reader_book,
        crate::reader::record_reader_time,
        crate::ai::get_ai_settings,
        crate::ai::save_ai_settings,
        crate::ai::list_ai_conversations,
        crate::ai::list_ai_messages,
        crate::ai::rename_ai_conversation,
        crate::ai::delete_ai_conversation,
        crate::ai::set_ai_conversation_model,
        crate::ai::list_ai_context_candidates,
        crate::ai::pick_ai_image,
        crate::ai::cancel_ai_image,
        crate::ai::send_ai_message,
        crate::ai::estimate_meal_day,
        crate::games::get_games_snapshot,
        crate::games::apply_game_action,
        crate::games::add_game_play_time,
        get_windows_settings,
        get_default_windows_settings,
        update_windows_settings,
        select_directory,
        select_background_image,
        choose_and_preview_backup,
        import_backup,
        export_backup,
        run_automatic_backup,
        list_restore_points,
        restore_restore_point,
        crate::cloud_sync::commands::list_cloud_sync_configs,
        crate::cloud_sync::commands::save_cloud_sync_config,
        crate::cloud_sync::commands::delete_cloud_sync_config,
        crate::cloud_sync::commands::copy_cloud_sync_config,
        crate::cloud_sync::commands::set_cloud_sync_enabled,
        crate::cloud_sync::commands::run_cloud_sync,
        crate::cloud_sync::commands::cancel_cloud_sync,
        crate::cloud_sync::commands::list_pending_cloud_json,
        crate::cloud_sync::commands::preview_pending_cloud_json,
        crate::cloud_sync::commands::restore_pending_cloud_json,
        get_vault_status,
        setup_vault,
        unlock_vault,
        lock_vault,
        list_vault_items,
        create_vault_item,
        update_vault_item,
        delete_vault_item,
        reorder_vault_items,
        change_vault_password,
        copy_vault_item,
        open_vault_item_url,
        open_external_link,
        get_usage_page,
        choose_usage_statistics_source,
        refresh_usage_statistics,
        get_health_page,
        choose_health_statistics_source,
        refresh_health_statistics,
        get_update_state,
        set_automatic_update_checks,
        check_for_updates,
        install_update,
        open_official_link,
    ]
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct IpcProtocolDto {
    schema_version: u32,
    minimum_supported_version: u32,
    app_version: &'static str,
}

#[tauri::command]
fn get_ipc_protocol() -> IpcProtocolDto {
    IpcProtocolDto {
        schema_version: 2,
        minimum_supported_version: 1,
        app_version: env!("CARGO_PKG_VERSION"),
    }
}

fn map_data_error(error: DataError) -> SecurityErrorDto {
    match error {
        DataError::Validation(_) => SecurityErrorDto::invalid_input(),
        DataError::NotFound => SecurityErrorDto::not_found(),
        DataError::UnsupportedVersion => SecurityErrorDto::new(
            "database_version_unsupported",
            "This database was created by a newer DeskCubby version.",
            false,
        ),
        DataError::Sqlite(_) | DataError::Io(_) | DataError::Json(_) => {
            SecurityErrorDto::storage_unavailable()
        }
    }
}

fn map_diary_error(error: diary::DiaryError) -> SecurityErrorDto {
    match error.code.as_str() {
        "DIARY_NOT_FOUND" | "DIARY_FILE_NOT_FOUND" => {
            SecurityErrorDto::new("entry_not_found", "The diary no longer exists.", true)
        }
        "DIARY_INVALID_DATE" => {
            SecurityErrorDto::new("invalid_date", "The date is not valid.", true)
        }
        "DIARY_NAME_INVALID"
        | "DIARY_CONTENT_TOO_LARGE"
        | "DAILY_RECORD_EMPTY"
        | "DIARY_TRASH_NAME_INVALID" => SecurityErrorDto::invalid_input(),
        "DIARY_DIRECTORY_INVALID" | "DIARY_DIRECTORY_REPARSE_POINT" => SecurityErrorDto::new(
            "directory_not_configured",
            "Choose a valid local directory in Settings.",
            true,
        ),
        _ => SecurityErrorDto::new(
            "io_failed",
            "The file operation could not be completed.",
            true,
        ),
    }
}

fn map_media_error(error: media::MediaError) -> SecurityErrorDto {
    match error.code.as_str() {
        "MEDIA_DATE_INVALID" | "MEAL_DATE_RANGE_INVALID" => {
            SecurityErrorDto::new("invalid_date", "The date range is not valid.", true)
        }
        "MEDIA_FILE_NOT_FOUND" => {
            SecurityErrorDto::new("media_not_found", "The media file no longer exists.", true)
        }
        "MEAL_EXPORT_TOO_LARGE" | "MEAL_EXPORT_SIZE_INVALID" => SecurityErrorDto::new(
            "export_too_large",
            "Choose a shorter date range for this export.",
            true,
        ),
        "MEDIA_DIRECTORY_INVALID" => SecurityErrorDto::new(
            "directory_not_configured",
            "Choose a valid media directory in Settings.",
            true,
        ),
        "MEDIA_SOURCE_TOO_LARGE"
        | "MEDIA_IMAGE_INVALID"
        | "MEDIA_IMAGE_UNSUPPORTED"
        | "MEDIA_NAME_INVALID"
        | "MEAL_EXPORT_EMPTY" => SecurityErrorDto::invalid_input(),
        _ => SecurityErrorDto::new(
            "io_failed",
            "The media operation could not be completed.",
            true,
        ),
    }
}

fn map_backup_error(error: BackupError) -> SecurityErrorDto {
    match error {
        BackupError::Invalid(_) | BackupError::Json(_) => SecurityErrorDto::backup_invalid(),
        BackupError::RecoveryPointTooLarge => SecurityErrorDto::new(
            "backup_too_large",
            "The recovery point exceeds the safety limit.",
            true,
        ),
        BackupError::Database(error) => map_data_error(error),
    }
}

fn emit_diary_changed<R: Runtime>(app: &AppHandle<R>) {
    let _ = app.emit("diary-index-changed", ());
}

fn diary_root(state: &AppState) -> CommandResult<PathBuf> {
    let paths = state.database.get_local_paths().map_err(map_data_error)?;
    let root = paths.diary_path.ok_or_else(|| {
        SecurityErrorDto::new(
            "directory_not_configured",
            "Choose a diary directory in Settings.",
            true,
        )
    })?;
    diary::validate_directory(Path::new(&root)).map_err(map_diary_error)
}

fn media_root(state: &AppState) -> CommandResult<PathBuf> {
    let paths = state.database.get_local_paths().map_err(map_data_error)?;
    let root = paths.media_path.ok_or_else(|| {
        SecurityErrorDto::new(
            "directory_not_configured",
            "Choose a media directory in Settings.",
            true,
        )
    })?;
    diary::validate_directory(Path::new(&root)).map_err(map_diary_error)
}

fn backup_root(state: &AppState) -> CommandResult<PathBuf> {
    let paths = state.database.get_local_paths().map_err(map_data_error)?;
    let root = paths.backup_path.ok_or_else(|| {
        SecurityErrorDto::new(
            "directory_not_configured",
            "Choose a backup directory in Settings.",
            true,
        )
    })?;
    diary::validate_directory(Path::new(&root)).map_err(map_diary_error)
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct IpcFileVersion {
    sha256: String,
    size: u64,
    modified_at: String,
}

impl From<diary::FileVersion> for IpcFileVersion {
    fn from(value: diary::FileVersion) -> Self {
        Self {
            sha256: value.sha256,
            size: value.size,
            modified_at: millis_to_rfc3339(value.modified_at),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct IncomingFileVersion {
    sha256: String,
    size: u64,
    modified_at: String,
}

impl IncomingFileVersion {
    fn into_internal(self) -> CommandResult<diary::FileVersion> {
        if self.sha256.len() != 64 || !self.sha256.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return Err(SecurityErrorDto::invalid_input());
        }
        let modified_at = if let Ok(value) = DateTime::parse_from_rfc3339(&self.modified_at) {
            value.timestamp_millis()
        } else {
            self.modified_at
                .parse::<i64>()
                .map_err(|_| SecurityErrorDto::invalid_input())?
        };
        Ok(diary::FileVersion {
            sha256: self.sha256,
            size: self.size,
            modified_at,
        })
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DiaryEntryDto {
    relative_path: String,
    file_name: String,
    title: String,
    date: String,
    month: String,
    excerpt: String,
    word_count: usize,
    modified_at: String,
    trashed: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DiaryDocumentDto {
    entry: DiaryEntryDto,
    content: String,
    version: IpcFileVersion,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CreateDiaryRequest {
    date: String,
    title: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SaveDiaryRequest {
    relative_path: String,
    content: String,
    expected_version: Option<IncomingFileVersion>,
    resolution: DiarySaveResolution,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
enum DiarySaveResolution {
    Normal,
    Overwrite,
    Copy,
}

#[derive(Debug, Serialize)]
#[serde(
    tag = "status",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
enum DiarySaveResultDto {
    Saved {
        document: DiaryDocumentDto,
    },
    Conflict {
        current_version: IpcFileVersion,
        reason: &'static str,
    },
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RenameDiaryRequest {
    relative_path: String,
    title: String,
}

fn diary_entry_from_editor(editor: &diary::DiaryEditorDocument) -> DiaryEntryDto {
    let date = date_from_file_name(&editor.file_name, editor.version.modified_at);
    DiaryEntryDto {
        relative_path: editor.file_name.clone(),
        file_name: editor.file_name.clone(),
        title: title_from_file_name(&editor.file_name),
        month: date.get(..7).unwrap_or(&date).to_owned(),
        date,
        excerpt: markdown_excerpt(&editor.content),
        word_count: count_words(&editor.content),
        modified_at: millis_to_rfc3339(editor.version.modified_at),
        trashed: false,
    }
}

fn diary_document_dto(editor: diary::DiaryEditorDocument) -> DiaryDocumentDto {
    let entry = diary_entry_from_editor(&editor);
    DiaryDocumentDto {
        entry,
        content: editor.content,
        version: editor.version.into(),
    }
}

fn diary_document_dto_for_state(
    state: &AppState,
    root: &Path,
    editor: diary::DiaryEditorDocument,
) -> CommandResult<DiaryDocumentDto> {
    let settings = state
        .database
        .get_managed_settings()
        .map_err(map_data_error)?;
    let scanned = scan_diaries_configured(root, &settings)
        .map_err(map_diary_error)?
        .documents
        .into_iter()
        .find(|document| document.file_name.eq_ignore_ascii_case(&editor.file_name));
    let Some(scanned) = scanned else {
        return Ok(diary_document_dto(editor));
    };
    let entry = DiaryEntryDto {
        relative_path: scanned.file_name.clone(),
        file_name: scanned.file_name,
        title: scanned.title,
        date: scanned.date_iso.clone(),
        month: scanned
            .date_iso
            .get(..7)
            .unwrap_or(&scanned.month_key)
            .to_owned(),
        excerpt: markdown_excerpt(&editor.content),
        word_count: scanned.word_count,
        modified_at: millis_to_rfc3339(editor.version.modified_at),
        trashed: false,
    };
    Ok(DiaryDocumentDto {
        entry,
        content: editor.content,
        version: editor.version.into(),
    })
}

fn configured_diary_date(
    state: &AppState,
    root: &Path,
    file_name: &str,
    fallback_millis: i64,
) -> CommandResult<String> {
    let settings = state
        .database
        .get_managed_settings()
        .map_err(map_data_error)?;
    Ok(scan_diaries_configured(root, &settings)
        .map_err(map_diary_error)?
        .documents
        .into_iter()
        .find(|document| document.file_name.eq_ignore_ascii_case(file_name))
        .map(|document| document.date_iso)
        .unwrap_or_else(|| date_from_file_name(file_name, fallback_millis)))
}

fn scan_diaries_configured(
    root: &Path,
    settings: &ManagedSettings,
) -> Result<diary::DiaryScan, diary::DiaryError> {
    if settings.file_name_pattern == "yyyy-MM-dd" {
        diary::scan_diaries(root)
    } else {
        diary::scan_diaries_with_pattern(root, &settings.file_name_pattern)
    }
}

fn create_diary_configured(
    root: &Path,
    title: &str,
    date: NaiveDate,
    template: &str,
    settings: &ManagedSettings,
) -> Result<diary::DiaryEditorDocument, diary::DiaryError> {
    if settings.file_name_pattern == "yyyy-MM-dd" {
        diary::create_diary(root, title, date, template)
    } else {
        diary::create_diary_with_pattern(root, title, date, template, &settings.file_name_pattern)
    }
}

fn diary_entry_from_scan(
    root: &Path,
    document: diary::DiaryDocument,
) -> CommandResult<DiaryEntryDto> {
    let editor = diary::load_diary(root, &document.file_name).map_err(map_diary_error)?;
    Ok(DiaryEntryDto {
        relative_path: document.file_name.clone(),
        file_name: document.file_name,
        title: document.title,
        date: document.date_iso.clone(),
        month: document
            .date_iso
            .get(..7)
            .unwrap_or(&document.month_key)
            .to_owned(),
        excerpt: markdown_excerpt(&editor.content),
        word_count: document.word_count,
        modified_at: millis_to_rfc3339(document.version.modified_at),
        trashed: false,
    })
}

fn list_diary_entries(
    state: &AppState,
    include_trashed: bool,
) -> CommandResult<Vec<DiaryEntryDto>> {
    let root = diary_root(state)?;
    let settings = state
        .database
        .get_managed_settings()
        .map_err(map_data_error)?;
    let scan = scan_diaries_configured(&root, &settings).map_err(map_diary_error)?;
    let mut entries = scan
        .documents
        .into_iter()
        .map(|document| diary_entry_from_scan(&root, document))
        .collect::<CommandResult<Vec<_>>>()?;
    if include_trashed {
        for item in diary::scan_trash(&root).map_err(map_diary_error)? {
            let date = millis_to_local_date(item.deleted_at);
            entries.push(DiaryEntryDto {
                relative_path: item.stored_name,
                file_name: item.original_name.clone(),
                title: title_from_file_name(&item.original_name),
                month: date.get(..7).unwrap_or(&date).to_owned(),
                date,
                excerpt: String::new(),
                word_count: 0,
                modified_at: millis_to_rfc3339(item.deleted_at),
                trashed: true,
            });
        }
    }
    Ok(entries)
}

#[tauri::command]
fn list_diaries(
    include_trashed: Option<bool>,
    state: State<'_, AppState>,
) -> CommandResult<Vec<DiaryEntryDto>> {
    list_diary_entries(&state, include_trashed.unwrap_or(false))
}

#[tauri::command]
fn open_diary(
    relative_path: String,
    state: State<'_, AppState>,
) -> CommandResult<DiaryDocumentDto> {
    let file_name =
        validate_relative_file_name(&relative_path, &["md"]).map_err(SecurityErrorDto::from)?;
    let root = diary_root(&state)?;
    let editor = diary::load_diary(&root, &file_name).map_err(map_diary_error)?;
    diary_document_dto_for_state(&state, &root, editor)
}

#[tauri::command]
fn create_diary<R: Runtime>(
    request: CreateDiaryRequest,
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<DiaryDocumentDto> {
    let date = NaiveDate::parse_from_str(&request.date, "%Y-%m-%d")
        .map_err(|_| SecurityErrorDto::new("invalid_date", "The date is not valid.", true))?;
    let settings = state
        .database
        .get_managed_settings()
        .map_err(map_data_error)?;
    let root = diary_root(&state)?;
    let editor = create_diary_configured(
        &root,
        &request.title,
        date,
        &settings.markdown_template,
        &settings,
    )
    .map_err(map_diary_error)?;
    emit_diary_changed(&app);
    diary_document_dto_for_state(&state, &root, editor)
}

#[tauri::command]
fn save_diary<R: Runtime>(
    request: SaveDiaryRequest,
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<DiarySaveResultDto> {
    let root = diary_root(&state)?;
    let file_name = validate_relative_file_name(&request.relative_path, &["md"])
        .map_err(SecurityErrorDto::from)?;
    let expected = match request.expected_version {
        Some(version) => version.into_internal()?,
        None => {
            diary::load_diary(&root, &file_name)
                .map_err(map_diary_error)?
                .version
        }
    };

    if matches!(request.resolution, DiarySaveResolution::Copy) {
        let date = NaiveDate::parse_from_str(
            &configured_diary_date(&state, &root, &file_name, expected.modified_at)?,
            "%Y-%m-%d",
        )
        .unwrap_or_else(|_| Local::now().date_naive());
        let title = format!("{} copy", title_from_file_name(&file_name));
        let settings = state
            .database
            .get_managed_settings()
            .map_err(map_data_error)?;
        let created =
            create_diary_configured(&root, &title, date, "", &settings).map_err(map_diary_error)?;
        let created = match diary::save_diary(
            &root,
            &created.file_name,
            &request.content,
            &created.version,
            true,
        ) {
            Ok(SaveDiaryOutcome::Saved { document }) => document,
            Ok(SaveDiaryOutcome::Conflict { .. }) => {
                return Err(SecurityErrorDto::conflict());
            }
            Err(error) => {
                let _ = diary::move_to_trash(&root, &created.file_name);
                return Err(map_diary_error(error));
            }
        };
        emit_diary_changed(&app);
        return Ok(DiarySaveResultDto::Saved {
            document: diary_document_dto_for_state(&state, &root, created)?,
        });
    }

    let force = matches!(request.resolution, DiarySaveResolution::Overwrite);
    let outcome = match diary::save_diary(&root, &file_name, &request.content, &expected, force) {
        Ok(outcome) => outcome,
        Err(error) if error.code == "DIARY_FILE_NOT_FOUND" => {
            return Ok(DiarySaveResultDto::Conflict {
                current_version: expected.into(),
                reason: "deleted",
            });
        }
        Err(error) => return Err(map_diary_error(error)),
    };
    match outcome {
        SaveDiaryOutcome::Saved { document } => {
            emit_diary_changed(&app);
            Ok(DiarySaveResultDto::Saved {
                document: diary_document_dto_for_state(&state, &root, document)?,
            })
        }
        SaveDiaryOutcome::Conflict { disk_document } => Ok(DiarySaveResultDto::Conflict {
            current_version: disk_document.version.into(),
            reason: "changed",
        }),
    }
}

#[tauri::command]
fn rename_diary<R: Runtime>(
    request: RenameDiaryRequest,
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<DiaryDocumentDto> {
    let root = diary_root(&state)?;
    let current = diary::load_diary(&root, &request.relative_path).map_err(map_diary_error)?;
    let date_iso = configured_diary_date(
        &state,
        &root,
        &request.relative_path,
        current.version.modified_at,
    )?;
    let date = NaiveDate::parse_from_str(&date_iso, "%Y-%m-%d")
        .map_err(|_| SecurityErrorDto::new("invalid_date", "The diary date is invalid.", true))?;
    let settings = state
        .database
        .get_managed_settings()
        .map_err(map_data_error)?;
    let editor = diary::rename_diary_with_pattern(
        &root,
        &request.relative_path,
        request.title.trim(),
        date,
        &settings.file_name_pattern,
    )
    .map_err(map_diary_error)?;
    emit_diary_changed(&app);
    diary_document_dto_for_state(&state, &root, editor)
}

#[tauri::command]
fn trash_diary<R: Runtime>(
    relative_path: String,
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<()> {
    diary::move_to_trash(&diary_root(&state)?, &relative_path).map_err(map_diary_error)?;
    emit_diary_changed(&app);
    Ok(())
}

#[tauri::command]
fn restore_diary<R: Runtime>(
    relative_path: String,
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<DiaryDocumentDto> {
    let root = diary_root(&state)?;
    let restored = diary::restore_from_trash(&root, &relative_path).map_err(map_diary_error)?;
    emit_diary_changed(&app);
    diary_document_dto_for_state(&state, &root, restored.document)
}

#[tauri::command]
fn delete_diary_permanently<R: Runtime>(
    relative_path: String,
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<()> {
    diary::permanently_delete(&diary_root(&state)?, &relative_path).map_err(map_diary_error)?;
    emit_diary_changed(&app);
    Ok(())
}

#[tauri::command]
fn rescan_diaries<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<Vec<DiaryEntryDto>> {
    let entries = list_diary_entries(&state, false)?;
    emit_diary_changed(&app);
    Ok(entries)
}

fn parse_positive_ipc_id(value: &str) -> CommandResult<i64> {
    if value.is_empty()
        || (value.len() > 1 && value.starts_with('0'))
        || !value.bytes().all(|byte| byte.is_ascii_digit())
    {
        return Err(SecurityErrorDto::invalid_input());
    }
    let id = value
        .parse::<i64>()
        .map_err(|_| SecurityErrorDto::invalid_input())?;
    if id <= 0 {
        return Err(SecurityErrorDto::invalid_input());
    }
    Ok(id)
}

fn parse_optional_positive_ipc_id(value: Option<String>) -> CommandResult<Option<i64>> {
    value.as_deref().map(parse_positive_ipc_id).transpose()
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ThoughtDto {
    id: String,
    content: String,
    created_at: String,
    updated_at: String,
    pinned: bool,
    deleted_at: Option<String>,
    sort_order: String,
    category_id: Option<String>,
    highlighted: bool,
}

impl From<Thought> for ThoughtDto {
    fn from(value: Thought) -> Self {
        Self {
            id: value.id.to_string(),
            content: value.content,
            created_at: value.created_at.to_string(),
            updated_at: value.updated_at.to_string(),
            pinned: value.pinned,
            deleted_at: value.deleted_at.map(|timestamp| timestamp.to_string()),
            sort_order: value.sort_order.to_string(),
            category_id: value.category_id.map(|id| id.to_string()),
            highlighted: value.highlighted,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ThoughtDraftDto {
    #[serde(default)]
    id: Option<String>,
    content: String,
    #[serde(default)]
    pinned: bool,
    #[serde(default)]
    category_id: Option<String>,
    #[serde(default)]
    highlighted: bool,
}

impl ThoughtDraftDto {
    fn into_internal(self) -> CommandResult<ThoughtDraft> {
        Ok(ThoughtDraft {
            id: parse_optional_positive_ipc_id(self.id)?,
            content: self.content,
            pinned: self.pinned,
            category_id: parse_optional_positive_ipc_id(self.category_id)?,
            highlighted: self.highlighted,
        })
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ThoughtCategoryDto {
    id: String,
    name: String,
    color_argb: i32,
    sort_order: String,
    created_at: String,
    updated_at: String,
}

impl From<ThoughtCategory> for ThoughtCategoryDto {
    fn from(value: ThoughtCategory) -> Self {
        Self {
            id: value.id.to_string(),
            name: value.name,
            color_argb: value.color_argb,
            sort_order: value.sort_order.to_string(),
            created_at: value.created_at.to_string(),
            updated_at: value.updated_at.to_string(),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ThoughtCategoryDraftDto {
    #[serde(default)]
    id: Option<String>,
    name: String,
    color_argb: i32,
}

impl ThoughtCategoryDraftDto {
    fn into_internal(self) -> CommandResult<ThoughtCategoryDraft> {
        Ok(ThoughtCategoryDraft {
            id: parse_optional_positive_ipc_id(self.id)?,
            name: self.name,
            color_argb: self.color_argb,
        })
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DateRecordDto {
    id: String,
    name: String,
    icon: String,
    date_iso: String,
    created_at: String,
    updated_at: String,
}

impl From<DateRecord> for DateRecordDto {
    fn from(value: DateRecord) -> Self {
        Self {
            id: value.id.to_string(),
            name: value.name,
            icon: value.icon,
            date_iso: value.date_iso,
            created_at: value.created_at.to_string(),
            updated_at: value.updated_at.to_string(),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DateRecordDraftDto {
    #[serde(default)]
    id: Option<String>,
    name: String,
    icon: String,
    date_iso: String,
}

impl DateRecordDraftDto {
    fn into_internal(self) -> CommandResult<DateRecordDraft> {
        Ok(DateRecordDraft {
            id: parse_optional_positive_ipc_id(self.id)?,
            name: self.name,
            icon: self.icon,
            date_iso: self.date_iso,
        })
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct SavedPoemDto {
    id: String,
    content: String,
    source: String,
    created_at: String,
    updated_at: String,
    sort_order: String,
    category_id: Option<String>,
}

impl From<SavedPoem> for SavedPoemDto {
    fn from(value: SavedPoem) -> Self {
        Self {
            id: value.id.to_string(),
            content: value.content,
            source: value.source,
            created_at: value.created_at.to_string(),
            updated_at: value.updated_at.to_string(),
            sort_order: value.sort_order.to_string(),
            category_id: value.category_id.map(|id| id.to_string()),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SavedPoemDraftDto {
    #[serde(default)]
    id: Option<String>,
    content: String,
    #[serde(default)]
    source: String,
    #[serde(default)]
    category_id: Option<String>,
}

impl SavedPoemDraftDto {
    fn into_internal(self) -> CommandResult<SavedPoemDraft> {
        Ok(SavedPoemDraft {
            id: parse_optional_positive_ipc_id(self.id)?,
            content: self.content,
            source: self.source,
            category_id: parse_optional_positive_ipc_id(self.category_id)?,
        })
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct PoetryCategoryDto {
    id: String,
    name: String,
    color_argb: i32,
    sort_order: String,
    created_at: String,
    updated_at: String,
}

impl From<PoetryCategory> for PoetryCategoryDto {
    fn from(value: PoetryCategory) -> Self {
        Self {
            id: value.id.to_string(),
            name: value.name,
            color_argb: value.color_argb,
            sort_order: value.sort_order.to_string(),
            created_at: value.created_at.to_string(),
            updated_at: value.updated_at.to_string(),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PoetryCategoryDraftDto {
    #[serde(default)]
    id: Option<String>,
    name: String,
    color_argb: i32,
}

impl PoetryCategoryDraftDto {
    fn into_internal(self) -> CommandResult<PoetryCategoryDraft> {
        Ok(PoetryCategoryDraft {
            id: parse_optional_positive_ipc_id(self.id)?,
            name: self.name,
            color_argb: self.color_argb,
        })
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct PoetryPresetSummaryDto {
    id: String,
    name_zh: String,
    name_en: String,
    color_argb: i32,
    item_count: usize,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct PoetryPresetImportDto {
    category_id: String,
    added_count: usize,
    existing_count: usize,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PoetryPresetAsset {
    version: u32,
    categories: Vec<PoetryPresetCategoryAsset>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PoetryPresetCategoryAsset {
    id: String,
    name_zh: String,
    name_en: String,
    color_argb: i32,
    items: Vec<PoetryPresetItemAsset>,
}

#[derive(Debug, Deserialize)]
struct PoetryPresetItemAsset {
    title: String,
    #[serde(default)]
    author: String,
    content: String,
}

#[tauri::command]
fn list_thoughts(
    include_deleted: Option<bool>,
    state: State<'_, AppState>,
) -> CommandResult<Vec<ThoughtDto>> {
    state
        .database
        .list_thoughts(include_deleted.unwrap_or(false))
        .map(|thoughts| thoughts.into_iter().map(ThoughtDto::from).collect())
        .map_err(map_data_error)
}

#[tauri::command]
fn create_thought(
    request: Option<ThoughtDraftDto>,
    draft: Option<ThoughtDraftDto>,
    state: State<'_, AppState>,
) -> CommandResult<ThoughtDto> {
    let mut draft = draft
        .or(request)
        .ok_or_else(SecurityErrorDto::invalid_input)?
        .into_internal()?;
    draft.id = None;
    state
        .database
        .save_thought(draft)
        .map(ThoughtDto::from)
        .map_err(map_data_error)
}

#[tauri::command]
fn update_thought(
    id: String,
    draft: ThoughtDraftDto,
    state: State<'_, AppState>,
) -> CommandResult<ThoughtDto> {
    let id = parse_positive_ipc_id(&id)?;
    let mut draft = draft.into_internal()?;
    draft.id = Some(id);
    state
        .database
        .save_thought(draft)
        .map(ThoughtDto::from)
        .map_err(map_data_error)
}

#[tauri::command]
fn delete_thought(
    id: String,
    permanent: Option<bool>,
    state: State<'_, AppState>,
) -> CommandResult<Option<ThoughtDto>> {
    let id = parse_positive_ipc_id(&id)?;
    if permanent.unwrap_or(false) {
        state
            .database
            .permanently_delete_thought(id)
            .map_err(map_data_error)?;
        return Ok(None);
    }
    state
        .database
        .soft_delete_thought(id, db::now_millis())
        .map_err(map_data_error)?;
    let thought = state
        .database
        .list_thoughts(true)
        .map_err(map_data_error)?
        .into_iter()
        .find(|thought| thought.id == id)
        .ok_or_else(SecurityErrorDto::not_found)?;
    Ok(Some(thought.into()))
}

#[tauri::command]
fn restore_thought(id: String, state: State<'_, AppState>) -> CommandResult<ThoughtDto> {
    let id = parse_positive_ipc_id(&id)?;
    state
        .database
        .restore_thought(id, db::now_millis())
        .map_err(map_data_error)?;
    state
        .database
        .list_thoughts(true)
        .map_err(map_data_error)?
        .into_iter()
        .find(|thought| thought.id == id)
        .map(ThoughtDto::from)
        .ok_or_else(SecurityErrorDto::not_found)
}

#[tauri::command]
fn reorder_thoughts(ids: Vec<String>, state: State<'_, AppState>) -> CommandResult<()> {
    let ids = ids
        .iter()
        .map(|id| parse_positive_ipc_id(id))
        .collect::<CommandResult<Vec<_>>>()?;
    state
        .database
        .reorder_thoughts(&ids, db::now_millis())
        .map_err(map_data_error)
}

#[tauri::command]
fn list_thought_categories(state: State<'_, AppState>) -> CommandResult<Vec<ThoughtCategoryDto>> {
    state
        .database
        .list_categories()
        .map(|categories| {
            categories
                .into_iter()
                .map(ThoughtCategoryDto::from)
                .collect()
        })
        .map_err(map_data_error)
}

#[tauri::command]
fn create_thought_category(
    draft: ThoughtCategoryDraftDto,
    state: State<'_, AppState>,
) -> CommandResult<ThoughtCategoryDto> {
    let mut draft = draft.into_internal()?;
    draft.id = None;
    state
        .database
        .save_category(draft)
        .map(ThoughtCategoryDto::from)
        .map_err(map_data_error)
}

#[tauri::command]
fn update_thought_category(
    id: String,
    draft: ThoughtCategoryDraftDto,
    state: State<'_, AppState>,
) -> CommandResult<ThoughtCategoryDto> {
    let id = parse_positive_ipc_id(&id)?;
    let mut draft = draft.into_internal()?;
    draft.id = Some(id);
    state
        .database
        .save_category(draft)
        .map(ThoughtCategoryDto::from)
        .map_err(map_data_error)
}

#[tauri::command]
fn delete_thought_category(id: String, state: State<'_, AppState>) -> CommandResult<()> {
    let id = parse_positive_ipc_id(&id)?;
    state.database.delete_category(id).map_err(map_data_error)
}

#[tauri::command]
fn export_thought_category<R: Runtime>(
    category_id: String,
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<bool> {
    let category_id = parse_positive_ipc_id(&category_id)?;
    let category = state
        .database
        .list_categories()
        .map_err(map_data_error)?
        .into_iter()
        .find(|category| category.id == category_id)
        .ok_or_else(SecurityErrorDto::not_found)?;
    let text = state
        .database
        .list_thoughts(false)
        .map_err(map_data_error)?
        .into_iter()
        .filter(|thought| thought.category_id == Some(category_id))
        .map(|thought| thought.content)
        .collect::<Vec<_>>()
        .join("\r\n\r\n---\r\n\r\n");
    if text.len() > MAX_TEXT_EXPORT_BYTES {
        return Err(SecurityErrorDto::invalid_input());
    }
    let default_name = format!("thoughts-{}.txt", category.id);
    if let Some(path) = app
        .dialog()
        .file()
        .add_filter("Text", &["txt"])
        .set_file_name(default_name)
        .blocking_save_file()
    {
        let path = path
            .into_path()
            .map_err(|_| SecurityErrorDto::path_not_allowed())?;
        write_verified(&path, text.as_bytes())?;
        return Ok(true);
    }
    Ok(false)
}

#[tauri::command]
fn list_date_records(state: State<'_, AppState>) -> CommandResult<Vec<DateRecordDto>> {
    state
        .database
        .list_date_records()
        .map(|records| records.into_iter().map(DateRecordDto::from).collect())
        .map_err(map_data_error)
}

#[tauri::command]
fn create_date_record(
    draft: DateRecordDraftDto,
    state: State<'_, AppState>,
) -> CommandResult<DateRecordDto> {
    let mut draft = draft.into_internal()?;
    draft.id = None;
    state
        .database
        .save_date_record(draft)
        .map(DateRecordDto::from)
        .map_err(map_data_error)
}

#[tauri::command]
fn update_date_record(
    id: String,
    draft: DateRecordDraftDto,
    state: State<'_, AppState>,
) -> CommandResult<DateRecordDto> {
    let id = parse_positive_ipc_id(&id)?;
    let mut draft = draft.into_internal()?;
    draft.id = Some(id);
    state
        .database
        .save_date_record(draft)
        .map(DateRecordDto::from)
        .map_err(map_data_error)
}

#[tauri::command]
fn delete_date_record(id: String, state: State<'_, AppState>) -> CommandResult<()> {
    let id = parse_positive_ipc_id(&id)?;
    state
        .database
        .delete_date_record(id)
        .map_err(map_data_error)
}

#[tauri::command]
fn list_poems(state: State<'_, AppState>) -> CommandResult<Vec<SavedPoemDto>> {
    state
        .database
        .list_poems()
        .map(|poems| poems.into_iter().map(SavedPoemDto::from).collect())
        .map_err(map_data_error)
}

#[tauri::command]
fn create_poem(
    draft: SavedPoemDraftDto,
    state: State<'_, AppState>,
) -> CommandResult<SavedPoemDto> {
    let mut draft = draft.into_internal()?;
    draft.id = None;
    state
        .database
        .save_poem(draft)
        .map(SavedPoemDto::from)
        .map_err(map_data_error)
}

#[tauri::command]
fn update_poem(
    id: String,
    draft: SavedPoemDraftDto,
    state: State<'_, AppState>,
) -> CommandResult<SavedPoemDto> {
    let id = parse_positive_ipc_id(&id)?;
    let mut draft = draft.into_internal()?;
    draft.id = Some(id);
    state
        .database
        .save_poem(draft)
        .map(SavedPoemDto::from)
        .map_err(map_data_error)
}

#[tauri::command]
fn delete_poem(id: String, state: State<'_, AppState>) -> CommandResult<()> {
    let id = parse_positive_ipc_id(&id)?;
    state.database.delete_poem(id).map_err(map_data_error)
}

#[tauri::command]
fn list_poetry_categories(state: State<'_, AppState>) -> CommandResult<Vec<PoetryCategoryDto>> {
    state
        .database
        .list_poetry_categories()
        .map(|categories| {
            categories
                .into_iter()
                .map(PoetryCategoryDto::from)
                .collect()
        })
        .map_err(map_data_error)
}

#[tauri::command]
fn create_poetry_category(
    draft: PoetryCategoryDraftDto,
    state: State<'_, AppState>,
) -> CommandResult<PoetryCategoryDto> {
    let mut draft = draft.into_internal()?;
    draft.id = None;
    state
        .database
        .save_poetry_category(draft)
        .map(PoetryCategoryDto::from)
        .map_err(map_data_error)
}

#[tauri::command]
fn update_poetry_category(
    id: String,
    draft: PoetryCategoryDraftDto,
    state: State<'_, AppState>,
) -> CommandResult<PoetryCategoryDto> {
    let id = parse_positive_ipc_id(&id)?;
    let mut draft = draft.into_internal()?;
    draft.id = Some(id);
    state
        .database
        .save_poetry_category(draft)
        .map(PoetryCategoryDto::from)
        .map_err(map_data_error)
}

#[tauri::command]
fn delete_poetry_category(
    id: String,
    delete_poems: Option<bool>,
    state: State<'_, AppState>,
) -> CommandResult<()> {
    let id = parse_positive_ipc_id(&id)?;
    state
        .database
        .delete_poetry_category(id, delete_poems.unwrap_or(false))
        .map_err(map_data_error)
}

#[tauri::command]
fn move_poetry_category(
    id: String,
    target_index: usize,
    state: State<'_, AppState>,
) -> CommandResult<()> {
    let id = parse_positive_ipc_id(&id)?;
    state
        .database
        .move_poetry_category(id, target_index)
        .map_err(map_data_error)
}

#[tauri::command]
fn set_poem_category(
    id: String,
    category_id: Option<String>,
    state: State<'_, AppState>,
) -> CommandResult<()> {
    let id = parse_positive_ipc_id(&id)?;
    let category_id = parse_optional_positive_ipc_id(category_id)?;
    state
        .database
        .set_poem_category(id, category_id)
        .map_err(map_data_error)
}

#[tauri::command]
fn move_poem(
    id: String,
    target_index: usize,
    scope: String,
    state: State<'_, AppState>,
) -> CommandResult<()> {
    let id = parse_positive_ipc_id(&id)?;
    let category_scope = match scope.as_str() {
        "all" => None,
        "uncategorized" => Some(None),
        value => Some(Some(parse_positive_ipc_id(value)?)),
    };
    state
        .database
        .move_poem(id, target_index, category_scope)
        .map_err(map_data_error)
}

#[tauri::command]
fn list_poetry_presets() -> CommandResult<Vec<PoetryPresetSummaryDto>> {
    let catalog = decode_poetry_preset_asset()?;
    Ok(catalog
        .categories
        .into_iter()
        .map(|category| PoetryPresetSummaryDto {
            id: category.id,
            name_zh: category.name_zh.trim().to_owned(),
            name_en: category.name_en.trim().to_owned(),
            color_argb: category.color_argb | (0xff00_0000_u32 as i32),
            item_count: category.items.len(),
        })
        .collect())
}

#[tauri::command]
fn import_poetry_preset(
    preset_id: String,
    state: State<'_, AppState>,
) -> CommandResult<PoetryPresetImportDto> {
    let catalog = decode_poetry_preset_asset()?;
    let preset = catalog
        .categories
        .into_iter()
        .find(|category| category.id == preset_id)
        .ok_or_else(SecurityErrorDto::not_found)?;
    let poems = preset
        .items
        .into_iter()
        .map(|item| {
            let title = item.title.trim();
            let author = item.author.trim();
            let source = if author.is_empty() {
                format!("《{title}》")
            } else {
                format!("{author}《{title}》")
            };
            (item.content.trim().to_owned(), source)
        })
        .collect::<Vec<_>>();
    state
        .database
        .import_poetry_preset(
            preset.name_zh.trim(),
            preset.color_argb | (0xff00_0000_u32 as i32),
            &poems,
        )
        .map(
            |(category_id, added_count, existing_count)| PoetryPresetImportDto {
                category_id: category_id.to_string(),
                added_count,
                existing_count,
            },
        )
        .map_err(map_data_error)
}

fn decode_poetry_preset_asset() -> CommandResult<PoetryPresetAsset> {
    const ASSET: &str = include_str!("../../../android/app/src/main/assets/poetry_presets.json");
    if ASSET.len() > 1024 * 1024 {
        return Err(SecurityErrorDto::operation_failed());
    }
    let catalog: PoetryPresetAsset =
        serde_json::from_str(ASSET).map_err(|_| SecurityErrorDto::operation_failed())?;
    if catalog.version != 1 || catalog.categories.is_empty() || catalog.categories.len() > 32 {
        return Err(SecurityErrorDto::operation_failed());
    }
    let mut ids = std::collections::HashSet::new();
    let mut total_items = 0_usize;
    for category in &catalog.categories {
        let valid_id = !category.id.is_empty()
            && category.id.len() <= 64
            && category
                .id
                .bytes()
                .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-');
        let valid_names = !category.name_zh.trim().is_empty()
            && category.name_zh.chars().count() <= 100
            && !category.name_en.trim().is_empty()
            && category.name_en.chars().count() <= 100;
        if !valid_id
            || !ids.insert(category.id.as_str())
            || !valid_names
            || category.items.is_empty()
            || category.items.len() > 128
        {
            return Err(SecurityErrorDto::operation_failed());
        }
        total_items += category.items.len();
        if total_items > 512 {
            return Err(SecurityErrorDto::operation_failed());
        }
        for item in &category.items {
            let title = item.title.trim();
            let author = item.author.trim();
            let content = item.content.trim();
            let source_chars = title.chars().count() + author.chars().count() + 2;
            if title.is_empty()
                || title.chars().count() > 200
                || author.chars().count() > 100
                || content.is_empty()
                || content.chars().count() > 4_000
                || source_chars > 512
            {
                return Err(SecurityErrorDto::operation_failed());
            }
        }
    }
    Ok(catalog)
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DailyPoemDto {
    content: String,
    title: Option<String>,
    source: Option<String>,
    author: Option<String>,
    dynasty: Option<String>,
    from_cache: bool,
    used_fallback: bool,
}

#[tauri::command]
async fn get_daily_poem(
    force_refresh: Option<bool>,
    state: State<'_, AppState>,
) -> CommandResult<DailyPoemDto> {
    let cache_path = state.private_dir.join("poetry").join("daily.json");
    let result = poetry::get_daily_poem(&cache_path, force_refresh.unwrap_or(false))
        .await
        .map_err(|_| SecurityErrorDto::network_unavailable())?;
    Ok(daily_poem_dto(result))
}

fn daily_poem_dto(result: poetry::DailyPoemResult) -> DailyPoemDto {
    let from_cache = matches!(result.source, poetry::DailyPoemSource::Cache);
    let used_fallback = matches!(result.source, poetry::DailyPoemSource::Fallback);
    let author = poem_author(&result.poem.source);
    DailyPoemDto {
        content: result.poem.content,
        title: nonempty(result.poem.title),
        source: nonempty(result.poem.source),
        author,
        dynasty: nonempty(result.poem.dynasty),
        from_cache,
        used_fallback,
    }
}

fn nonempty(value: String) -> Option<String> {
    (!value.trim().is_empty()).then_some(value)
}

fn poem_author(source: &str) -> Option<String> {
    let value = source
        .trim()
        .trim_start_matches(['—', '-', ' '])
        .split('《')
        .next()
        .unwrap_or_default()
        .trim();
    (!value.is_empty()).then(|| value.to_owned())
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct HomePoemDto {
    title: String,
    dynasty: String,
    author: String,
    content: Vec<String>,
    source: &'static str,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ThoughtSummaryDto {
    id: String,
    content: String,
    category_name: Option<String>,
    color: Option<String>,
    pinned: bool,
    highlighted: bool,
    updated_at: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct HomeSnapshotDto {
    today: String,
    greeting: String,
    daily_poem: Option<HomePoemDto>,
    recent_diaries: Vec<DiaryEntryDto>,
    recent_thoughts: Vec<ThoughtSummaryDto>,
    meal_photos: Vec<MealPhotoDto>,
    daily_templates: Vec<DailyTemplateDto>,
    current_diary_relative_path: Option<String>,
    monthly_diary_count: usize,
    monthly_thought_count: usize,
    total_word_count: usize,
    year_progress: f64,
    random_diary: Option<DiaryEntryDto>,
}

#[tauri::command]
fn get_home_snapshot(state: State<'_, AppState>) -> CommandResult<HomeSnapshotDto> {
    use chrono::Datelike;

    let now = Local::now();
    let today = now.format("%Y-%m-%d").to_string();
    let month = now.format("%Y-%m").to_string();
    let settings = state
        .database
        .get_managed_settings()
        .map_err(map_data_error)?;
    let mut diaries = list_diary_entries(&state, false).unwrap_or_default();
    diaries.sort_by(|left, right| {
        right
            .date
            .cmp(&left.date)
            .then_with(|| right.modified_at.cmp(&left.modified_at))
    });
    let current_diary_relative_path = diaries
        .iter()
        .find(|entry| entry.date == today)
        .map(|entry| entry.relative_path.clone());
    let monthly_diary_count = diaries
        .iter()
        .filter(|entry| entry.date.starts_with(&month))
        .count();
    let total_word_count = diaries.iter().map(|entry| entry.word_count).sum();
    let recent_diaries = diaries.iter().take(5).cloned().collect::<Vec<_>>();
    let random_diary = if diaries.is_empty() {
        None
    } else {
        Some(diaries[now.ordinal0() as usize % diaries.len()].clone())
    };

    let categories = state.database.list_categories().map_err(map_data_error)?;
    let thoughts = state
        .database
        .list_thoughts(false)
        .map_err(map_data_error)?;
    let monthly_thought_count = thoughts
        .iter()
        .filter(|thought| millis_to_local_month(thought.updated_at) == month)
        .count();
    let recent_thoughts = thoughts
        .iter()
        .take(5)
        .map(|thought| {
            let category = thought
                .category_id
                .and_then(|id| categories.iter().find(|category| category.id == id));
            ThoughtSummaryDto {
                id: thought.id.to_string(),
                content: thought.content.clone(),
                category_name: category.map(|category| category.name.clone()),
                color: category.map(|category| argb_to_css_hex(category.color_argb)),
                pinned: thought.pinned,
                highlighted: thought.highlighted,
                updated_at: millis_to_rfc3339(thought.updated_at),
            }
        })
        .collect();

    let cached_poem =
        poetry::load_cached_or_fallback(&state.private_dir.join("poetry").join("daily.json"));
    let poem_source = match cached_poem.source {
        poetry::DailyPoemSource::Live => "network",
        poetry::DailyPoemSource::Cache => "cache",
        poetry::DailyPoemSource::Fallback => "builtin",
    };
    let poem = cached_poem.poem;
    let poem_content = if poem.full_content.trim().is_empty() {
        vec![poem.content.clone()]
    } else {
        poem.full_content
            .lines()
            .map(str::trim)
            .filter(|line| !line.is_empty())
            .map(str::to_owned)
            .collect()
    };
    let daily_poem = Some(HomePoemDto {
        title: poem.title,
        dynasty: poem.dynasty,
        author: poem_author(&poem.source).unwrap_or_default(),
        content: poem_content,
        source: poem_source,
    });

    let meal_photos = (|| -> CommandResult<Vec<MealPhotoDto>> {
        let diary_root = diary_root(&state)?;
        let media_root = media_root(&state)?;
        let query = MealQueryDto {
            start_date: Some(today.clone()),
            end_date: Some(today.clone()),
            categories: Vec::new(),
        };
        let metadata = media::read_media_metadata(&media_root).map_err(map_media_error)?;
        let days = media::scan_meal_calendar(&diary_root, &media_root, &query.to_scan_options())
            .map_err(map_media_error)?;
        flatten_meal_days(&media_root, &metadata, days)
    })()
    .unwrap_or_default();

    let greeting_template = if settings.home_greetings.is_empty() {
        if settings.app_language == "ENGLISH" {
            "Start here today".to_owned()
        } else {
            "今天从这里开始".to_owned()
        }
    } else {
        let greeting =
            &settings.home_greetings[now.ordinal0() as usize % settings.home_greetings.len()];
        if settings.app_language == "ENGLISH" && !greeting.english.trim().is_empty() {
            greeting.english.clone()
        } else {
            greeting.chinese.clone()
        }
    };
    let greeting = greeting_template.replace(
        "{name}",
        if settings.user_name.trim().is_empty() {
            if settings.app_language == "ENGLISH" {
                "friend"
            } else {
                "你"
            }
        } else {
            settings.user_name.trim()
        },
    );
    let days_in_year = NaiveDate::from_ymd_opt(now.year(), 12, 31)
        .map(|date| date.ordinal())
        .unwrap_or(365);

    Ok(HomeSnapshotDto {
        today,
        greeting,
        daily_poem,
        recent_diaries,
        recent_thoughts,
        meal_photos,
        daily_templates: template_dtos(&settings),
        current_diary_relative_path,
        monthly_diary_count,
        monthly_thought_count,
        total_word_count,
        year_progress: f64::from(now.ordinal()) / f64::from(days_in_year),
        random_diary,
    })
}

fn millis_to_local_month(value: i64) -> String {
    DateTime::<Utc>::from_timestamp_millis(value)
        .map(|date| date.with_timezone(&Local).format("%Y-%m").to_string())
        .unwrap_or_default()
}

fn argb_to_css_hex(value: i32) -> String {
    format!("#{:06x}", (value as u32) & 0x00ff_ffff)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WindowsSettingsDto {
    visual_style: String,
    dark_mode: String,
    app_language: String,
    theme_color_argb: i32,
    theme_secondary_colors_argb: Vec<i32>,
    font_scale: f64,
    compact_mode: bool,
    background_image_path: Option<String>,
    background_image_opacity: f64,
    background_image_blur_px: f64,
    tutorial_mode_enabled: bool,
    diary_directory: Option<String>,
    media_directory: Option<String>,
    backup_directory: Option<String>,
    file_name_pattern: String,
    markdown_template: String,
    image_name_pattern: String,
    image_max_width_px: i32,
    image_max_height_px: i32,
    markdown_heading_sizes_sp: Vec<f64>,
    meal_image_compression_enabled: bool,
    meal_image_compression_quality: i32,
    photo_location_enabled: bool,
    thought_display_mode: String,
    thought_highlight_color_argb: i32,
    thought_editor_max_height_px: i32,
    vault_row_height_dp: i32,
    poetry_font_size_px: f64,
    poetry_line_spacing: f64,
    poetry_text_alignment: String,
    poetry_show_source: bool,
    poetry_show_quote_mark: bool,
    poetry_seven_character_wrap_enabled: bool,
    meal_calendar_image_max_height_px: i32,
    meal_calendar_show_captions: bool,
    meal_calendar_wrap_enabled: bool,
    meal_calendar_photos_per_row: String,
    meal_buttons_use_icons: bool,
    meal_button_icons: Vec<String>,
    user_name: String,
    home_greetings: Vec<HomeGreeting>,
    home_widget_borders_enabled: bool,
    home_widgets: Vec<String>,
    home_game_shortcuts: Vec<String>,
    home_widget_titles: Vec<String>,
}

impl WindowsSettingsDto {
    fn from_parts(settings: ManagedSettings, paths: LocalPaths) -> Self {
        Self {
            visual_style: settings.visual_style,
            dark_mode: settings.dark_mode,
            app_language: settings.app_language,
            theme_color_argb: settings.theme_color_argb,
            theme_secondary_colors_argb: settings.theme_secondary_colors_argb,
            font_scale: settings.font_scale,
            compact_mode: settings.compact_mode,
            background_image_path: settings.background_image_path,
            background_image_opacity: settings.background_image_opacity,
            background_image_blur_px: settings.background_image_blur_px,
            tutorial_mode_enabled: settings.tutorial_mode_enabled,
            diary_directory: paths.diary_path,
            media_directory: paths.media_path,
            backup_directory: paths.backup_path,
            file_name_pattern: settings.file_name_pattern,
            markdown_template: settings.markdown_template,
            image_name_pattern: settings.image_name_pattern,
            image_max_width_px: settings.image_max_width_dp,
            image_max_height_px: settings.image_max_height_dp,
            markdown_heading_sizes_sp: settings.markdown_heading_sizes_sp,
            meal_image_compression_enabled: settings.meal_image_compression_enabled,
            meal_image_compression_quality: settings.meal_image_compression_quality,
            photo_location_enabled: settings.photo_location_enabled,
            thought_display_mode: settings.thought_display_mode,
            thought_highlight_color_argb: settings.thought_highlight_color_argb,
            thought_editor_max_height_px: settings.thought_editor_max_height_dp,
            vault_row_height_dp: settings.vault_row_height_dp,
            poetry_font_size_px: settings.poetry_font_size_sp,
            poetry_line_spacing: settings.poetry_line_spacing,
            poetry_text_alignment: settings.poetry_text_alignment,
            poetry_show_source: settings.poetry_show_source,
            poetry_show_quote_mark: settings.poetry_show_quote_mark,
            poetry_seven_character_wrap_enabled: settings.poetry_seven_character_wrap_enabled,
            meal_calendar_image_max_height_px: settings.meal_calendar_image_max_height_dp,
            meal_calendar_show_captions: settings.meal_calendar_show_captions,
            meal_calendar_wrap_enabled: settings.meal_calendar_wrap_enabled,
            meal_calendar_photos_per_row: settings.meal_calendar_photos_per_row,
            meal_buttons_use_icons: settings.meal_buttons_use_icons,
            meal_button_icons: settings.meal_button_icons,
            user_name: settings.user_name,
            home_greetings: settings.home_greetings,
            home_widget_borders_enabled: settings.home_widget_borders_enabled,
            home_widgets: settings.home_widgets,
            home_game_shortcuts: settings.home_game_shortcuts,
            home_widget_titles: settings.home_widget_titles,
        }
    }

    fn apply_to(self, settings: &mut ManagedSettings) -> LocalPaths {
        settings.visual_style = self.visual_style;
        settings.dark_mode = self.dark_mode;
        settings.app_language = self.app_language;
        settings.theme_color_argb = self.theme_color_argb;
        settings.theme_secondary_colors_argb = self.theme_secondary_colors_argb;
        settings.font_scale = self.font_scale;
        settings.compact_mode = self.compact_mode;
        settings.background_image_path = self.background_image_path;
        settings.background_image_opacity = self.background_image_opacity;
        settings.background_image_blur_px = self.background_image_blur_px;
        settings.tutorial_mode_enabled = self.tutorial_mode_enabled;
        settings.file_name_pattern = self.file_name_pattern;
        settings.markdown_template = self.markdown_template;
        settings.image_name_pattern = self.image_name_pattern;
        settings.image_max_width_dp = self.image_max_width_px;
        settings.image_max_height_dp = self.image_max_height_px;
        settings.markdown_heading_sizes_sp = self.markdown_heading_sizes_sp;
        settings.meal_image_compression_enabled = self.meal_image_compression_enabled;
        settings.meal_image_compression_quality = self.meal_image_compression_quality;
        settings.photo_location_enabled = self.photo_location_enabled;
        settings.thought_display_mode = self.thought_display_mode;
        settings.thought_highlight_color_argb = self.thought_highlight_color_argb;
        settings.thought_editor_max_height_dp = self.thought_editor_max_height_px;
        settings.vault_row_height_dp = self.vault_row_height_dp;
        settings.poetry_font_size_sp = self.poetry_font_size_px;
        settings.poetry_line_spacing = self.poetry_line_spacing;
        settings.poetry_text_alignment = self.poetry_text_alignment;
        settings.poetry_show_source = self.poetry_show_source;
        settings.poetry_show_quote_mark = self.poetry_show_quote_mark;
        settings.poetry_seven_character_wrap_enabled = self.poetry_seven_character_wrap_enabled;
        settings.meal_calendar_image_max_height_dp = self.meal_calendar_image_max_height_px;
        settings.meal_calendar_show_captions = self.meal_calendar_show_captions;
        settings.meal_calendar_wrap_enabled = self.meal_calendar_wrap_enabled;
        settings.meal_calendar_photos_per_row = self.meal_calendar_photos_per_row;
        settings.meal_buttons_use_icons = self.meal_buttons_use_icons;
        settings.meal_button_icons = self.meal_button_icons;
        settings.user_name = self.user_name;
        settings.home_greetings = self.home_greetings;
        settings.home_widget_borders_enabled = self.home_widget_borders_enabled;
        settings.home_widgets = self.home_widgets;
        settings.home_game_shortcuts = self.home_game_shortcuts;
        settings.home_widget_titles = self.home_widget_titles;
        LocalPaths {
            diary_path: self.diary_directory,
            media_path: self.media_directory,
            backup_path: self.backup_directory,
        }
    }
}

#[tauri::command]
fn get_windows_settings(state: State<'_, AppState>) -> CommandResult<WindowsSettingsDto> {
    let settings = state
        .database
        .get_managed_settings()
        .map_err(map_data_error)?;
    let paths = state.database.get_local_paths().map_err(map_data_error)?;
    Ok(WindowsSettingsDto::from_parts(settings, paths))
}

#[tauri::command]
fn get_default_windows_settings() -> WindowsSettingsDto {
    WindowsSettingsDto::from_parts(ManagedSettings::default(), LocalPaths::default())
}

#[tauri::command]
fn update_windows_settings<R: Runtime>(
    settings: WindowsSettingsDto,
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<WindowsSettingsDto> {
    let _cloud_idle = state.cloud_sync.acquire_idle()?;
    let _guard = SETTINGS_UPDATE_MUTEX
        .lock()
        .map_err(|_| SecurityErrorDto::storage_unavailable())?;
    let mut managed = state
        .database
        .get_managed_settings()
        .map_err(map_data_error)?;
    let requested_background = settings.background_image_path.clone();
    let validated_background = if requested_background == managed.background_image_path {
        requested_background
    } else {
        requested_background
            .as_deref()
            .map(|path| validate_background_image(Path::new(path)))
            .transpose()?
            .map(|path| path.to_string_lossy().into_owned())
    };
    let paths = settings.apply_to(&mut managed);
    managed.background_image_path = validated_background;
    managed
        .validate()
        .map_err(|_| SecurityErrorDto::invalid_input())?;
    let paths = validate_local_paths(paths)?;
    state
        .database
        .put_windows_configuration(&managed, &paths, db::now_millis())
        .map_err(map_data_error)?;
    state
        .diary_watcher
        .set_directory(app, paths.diary_path.as_deref());
    Ok(WindowsSettingsDto::from_parts(managed, paths))
}

fn validate_local_paths(paths: LocalPaths) -> CommandResult<LocalPaths> {
    Ok(LocalPaths {
        diary_path: validate_optional_directory(paths.diary_path)?,
        media_path: validate_optional_directory(paths.media_path)?,
        backup_path: validate_optional_directory(paths.backup_path)?,
    })
}

fn validate_optional_directory(value: Option<String>) -> CommandResult<Option<String>> {
    let Some(value) = value else {
        return Ok(None);
    };
    let canonical = diary::validate_directory(Path::new(&value)).map_err(map_diary_error)?;
    Ok(Some(canonical.to_string_lossy().into_owned()))
}

#[tauri::command]
fn select_directory<R: Runtime>(
    kind: String,
    current_path: Option<String>,
    app: AppHandle<R>,
) -> CommandResult<Option<String>> {
    if !matches!(kind.as_str(), "diary" | "media" | "backup") {
        return Err(SecurityErrorDto::invalid_input());
    }
    let mut picker = app.dialog().file();
    if let Some(current) = current_path {
        if let Ok(current) = diary::validate_directory(Path::new(&current)) {
            picker = picker.set_directory(current);
        }
    }
    let Some(selected) = picker.blocking_pick_folder() else {
        return Ok(None);
    };
    let selected = selected
        .into_path()
        .map_err(|_| SecurityErrorDto::path_not_allowed())?;
    let selected = diary::validate_directory(&selected).map_err(map_diary_error)?;
    Ok(Some(selected.to_string_lossy().into_owned()))
}

#[tauri::command]
fn select_background_image<R: Runtime>(
    current_path: Option<String>,
    app: AppHandle<R>,
) -> CommandResult<Option<String>> {
    let mut picker = app
        .dialog()
        .file()
        .add_filter("Image", &["png", "jpg", "jpeg", "webp", "bmp"]);
    if let Some(current) = current_path {
        if let Ok(current) = validate_background_image(Path::new(&current))
            && let Some(parent) = current.parent()
        {
            picker = picker.set_directory(parent);
        }
    }
    let Some(selected) = picker.blocking_pick_file() else {
        return Ok(None);
    };
    let selected = selected
        .into_path()
        .map_err(|_| SecurityErrorDto::path_not_allowed())?;
    let selected = validate_background_image(&selected)?;
    Ok(Some(selected.to_string_lossy().into_owned()))
}

fn validate_background_image(path: &Path) -> CommandResult<PathBuf> {
    if !path.is_absolute()
        || !path
            .extension()
            .and_then(|value| value.to_str())
            .is_some_and(|extension| {
                matches!(
                    extension.to_ascii_lowercase().as_str(),
                    "png" | "jpg" | "jpeg" | "webp" | "bmp"
                )
            })
    {
        return Err(SecurityErrorDto::path_not_allowed());
    }
    reject_reparse_point(path).map_err(SecurityErrorDto::from)?;
    let canonical = fs::canonicalize(path).map_err(|_| SecurityErrorDto::path_not_allowed())?;
    reject_reparse_point(&canonical).map_err(SecurityErrorDto::from)?;
    let file = open_regular_file_no_reparse(&canonical).map_err(SecurityErrorDto::from)?;
    let metadata = file
        .metadata()
        .map_err(|_| SecurityErrorDto::storage_unavailable())?;
    if metadata.len() == 0 || metadata.len() > MAX_BACKGROUND_IMAGE_BYTES {
        return Err(SecurityErrorDto::invalid_input());
    }
    Ok(canonical)
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DailyTemplateDto {
    id: String,
    text: String,
    sort_order: usize,
}

fn template_dtos(settings: &ManagedSettings) -> Vec<DailyTemplateDto> {
    settings
        .daily_event_templates
        .iter()
        .enumerate()
        .map(|(index, template)| DailyTemplateDto {
            id: template.id.clone(),
            text: template.text.clone(),
            sort_order: index,
        })
        .collect()
}

#[tauri::command]
fn list_daily_templates(state: State<'_, AppState>) -> CommandResult<Vec<DailyTemplateDto>> {
    let settings = state
        .database
        .get_managed_settings()
        .map_err(map_data_error)?;
    Ok(template_dtos(&settings))
}

fn mutate_daily_templates(
    state: &AppState,
    mutation: impl FnOnce(&mut Vec<DailyEventTemplate>) -> CommandResult<()>,
) -> CommandResult<Vec<DailyTemplateDto>> {
    let _guard = SETTINGS_UPDATE_MUTEX
        .lock()
        .map_err(|_| SecurityErrorDto::storage_unavailable())?;
    let mut settings = state
        .database
        .get_managed_settings()
        .map_err(map_data_error)?;
    mutation(&mut settings.daily_event_templates)?;
    settings
        .validate()
        .map_err(|_| SecurityErrorDto::invalid_input())?;
    state
        .database
        .put_managed_settings(&settings, db::now_millis())
        .map_err(map_data_error)?;
    Ok(template_dtos(&settings))
}

#[tauri::command]
fn create_daily_template(
    text: String,
    state: State<'_, AppState>,
) -> CommandResult<DailyTemplateDto> {
    let text = validate_daily_text(text)?;
    let id = Uuid::new_v4().to_string();
    let templates = mutate_daily_templates(&state, |templates| {
        templates.push(DailyEventTemplate {
            id: id.clone(),
            text,
            first_unit: String::new(),
            second_unit: String::new(),
        });
        Ok(())
    })?;
    templates
        .into_iter()
        .find(|template| template.id == id)
        .ok_or_else(SecurityErrorDto::operation_failed)
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct UpdateDailyTemplateRequest {
    id: String,
    text: String,
}

#[tauri::command]
fn update_daily_template(
    request: UpdateDailyTemplateRequest,
    state: State<'_, AppState>,
) -> CommandResult<DailyTemplateDto> {
    let text = validate_daily_text(request.text)?;
    let id = request.id;
    let templates = mutate_daily_templates(&state, |templates| {
        let template = templates
            .iter_mut()
            .find(|template| template.id == id)
            .ok_or_else(SecurityErrorDto::not_found)?;
        template.text = text;
        Ok(())
    })?;
    templates
        .into_iter()
        .find(|template| template.id == id)
        .ok_or_else(SecurityErrorDto::operation_failed)
}

#[tauri::command]
fn delete_daily_template(id: String, state: State<'_, AppState>) -> CommandResult<()> {
    mutate_daily_templates(&state, |templates| {
        let before = templates.len();
        templates.retain(|template| template.id != id);
        if templates.len() == before {
            return Err(SecurityErrorDto::not_found());
        }
        Ok(())
    })?;
    Ok(())
}

#[tauri::command]
fn reorder_daily_templates(
    ids: Vec<String>,
    state: State<'_, AppState>,
) -> CommandResult<Vec<DailyTemplateDto>> {
    mutate_daily_templates(&state, |templates| {
        if ids.len() != templates.len() {
            return Err(SecurityErrorDto::invalid_input());
        }
        let mut reordered = Vec::with_capacity(templates.len());
        for id in ids {
            let index = templates
                .iter()
                .position(|template| template.id == id)
                .ok_or_else(SecurityErrorDto::invalid_input)?;
            reordered.push(templates.remove(index));
        }
        *templates = reordered;
        Ok(())
    })
}

fn validate_daily_text(text: String) -> CommandResult<String> {
    let text = text.trim().replace(['\r', '\n'], " ");
    if text.is_empty() || text.chars().count() > 100 {
        return Err(SecurityErrorDto::invalid_input());
    }
    Ok(text)
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DailyRecordContextDto {
    current_diary_relative_path: Option<String>,
    today: String,
}

#[tauri::command]
fn get_daily_record_context(state: State<'_, AppState>) -> CommandResult<DailyRecordContextDto> {
    let today = Local::now().format("%Y-%m-%d").to_string();
    let current_diary_relative_path = list_diary_entries(&state, false)
        .unwrap_or_default()
        .into_iter()
        .find(|entry| entry.date == today)
        .map(|entry| entry.relative_path);
    Ok(DailyRecordContextDto {
        current_diary_relative_path,
        today,
    })
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AppendDailyRecordRequest {
    text: String,
    target: String,
    current_diary_relative_path: Option<String>,
}

#[tauri::command]
fn append_daily_record<R: Runtime>(
    request: AppendDailyRecordRequest,
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<DiaryEntryDto> {
    let text = validate_daily_text(request.text)?;
    let root = diary_root(&state)?;
    let settings = state
        .database
        .get_managed_settings()
        .map_err(map_data_error)?;
    let today = Local::now().date_naive();
    let file_name = match request.target.as_str() {
        "current" => request
            .current_diary_relative_path
            .ok_or_else(SecurityErrorDto::invalid_input)?,
        "today" => {
            let scan = scan_diaries_configured(&root, &settings).map_err(map_diary_error)?;
            if let Some(document) = scan
                .documents
                .into_iter()
                .find(|document| document.date_iso == today.format("%Y-%m-%d").to_string())
            {
                document.file_name
            } else {
                create_diary_configured(
                    &root,
                    &today.format("%Y-%m-%d").to_string(),
                    today,
                    &settings.markdown_template,
                    &settings,
                )
                .map_err(map_diary_error)?
                .file_name
            }
        }
        _ => return Err(SecurityErrorDto::invalid_input()),
    };
    let current = diary::load_diary(&root, &file_name).map_err(map_diary_error)?;
    let saved =
        diary::append_text(&root, &file_name, &text, &current.version).map_err(map_diary_error)?;
    let editor = match saved {
        SaveDiaryOutcome::Saved { document } => document,
        SaveDiaryOutcome::Conflict { .. } => return Err(SecurityErrorDto::conflict()),
    };
    emit_diary_changed(&app);
    Ok(diary_entry_from_editor(&editor))
}

fn millis_to_rfc3339(value: i64) -> String {
    DateTime::<Utc>::from_timestamp_millis(value)
        .unwrap_or(DateTime::<Utc>::UNIX_EPOCH)
        .to_rfc3339()
}

fn millis_to_local_date(value: i64) -> String {
    DateTime::<Utc>::from_timestamp_millis(value)
        .map(|date| date.with_timezone(&Local).format("%Y-%m-%d").to_string())
        .unwrap_or_else(|| "1970-01-01".to_owned())
}

fn date_from_file_name(file_name: &str, fallback_millis: i64) -> String {
    let prefix = file_name.get(..10).unwrap_or_default();
    if NaiveDate::parse_from_str(prefix, "%Y-%m-%d").is_ok() {
        prefix.to_owned()
    } else {
        millis_to_local_date(fallback_millis)
    }
}

fn title_from_file_name(file_name: &str) -> String {
    let stem = file_name
        .strip_suffix(".md")
        .or_else(|| file_name.strip_suffix(".MD"))
        .unwrap_or(file_name);
    let without_date = if stem.len() > 11
        && NaiveDate::parse_from_str(&stem[..10], "%Y-%m-%d").is_ok()
        && stem.as_bytes().get(10).is_some_and(u8::is_ascii_whitespace)
    {
        &stem[11..]
    } else {
        stem
    };
    if without_date.trim().is_empty() {
        stem.to_owned()
    } else {
        without_date.trim().to_owned()
    }
}

fn markdown_excerpt(content: &str) -> String {
    let mut excerpt = String::new();
    for line in content.lines() {
        let line = line
            .trim()
            .trim_start_matches('#')
            .trim_start_matches(['-', '*', '>', ' ']);
        if line.is_empty() || line.starts_with("![") {
            continue;
        }
        if !excerpt.is_empty() {
            excerpt.push(' ');
        }
        excerpt.push_str(line);
        if excerpt.chars().count() >= 160 {
            break;
        }
    }
    excerpt.chars().take(160).collect()
}

fn count_words(content: &str) -> usize {
    let (cjk, non_cjk) = content
        .chars()
        .fold((0, String::new()), |mut state, character| {
            if ('\u{3400}'..='\u{9fff}').contains(&character) {
                state.0 += 1;
                state.1.push(' ');
            } else {
                state.1.push(character);
            }
            state
        });
    cjk + non_cjk.split_whitespace().count()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
enum MealCategoryDto {
    Breakfast,
    Lunch,
    AfternoonTea,
    Dinner,
    Fruit,
    LateNight,
}

impl From<MealCategoryDto> for media::MealCategory {
    fn from(value: MealCategoryDto) -> Self {
        match value {
            MealCategoryDto::Breakfast => Self::Breakfast,
            MealCategoryDto::Lunch => Self::Lunch,
            MealCategoryDto::AfternoonTea => Self::AfternoonTea,
            MealCategoryDto::Dinner => Self::Dinner,
            MealCategoryDto::Fruit => Self::Fruit,
            MealCategoryDto::LateNight => Self::LateSnack,
        }
    }
}

impl From<media::MealCategory> for MealCategoryDto {
    fn from(value: media::MealCategory) -> Self {
        match value {
            media::MealCategory::Breakfast => Self::Breakfast,
            media::MealCategory::Lunch => Self::Lunch,
            media::MealCategory::AfternoonTea => Self::AfternoonTea,
            media::MealCategory::Dinner => Self::Dinner,
            media::MealCategory::Fruit => Self::Fruit,
            media::MealCategory::LateSnack => Self::LateNight,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct MealQueryDto {
    start_date: Option<String>,
    end_date: Option<String>,
    #[serde(default)]
    categories: Vec<MealCategoryDto>,
}

impl MealQueryDto {
    fn to_scan_options(&self) -> media::MealScanOptions {
        media::MealScanOptions {
            categories: self.categories.iter().copied().map(Into::into).collect(),
            start_date: self.start_date.clone(),
            end_date: self.end_date.clone(),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct MealPhotoDto {
    id: String,
    file_name: String,
    diary_relative_path: String,
    date: String,
    category: MealCategoryDto,
    caption: String,
    energy_kj: Option<i64>,
    location: Option<String>,
    latitude: Option<f64>,
    longitude: Option<f64>,
    asset_url: Option<String>,
    missing: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ImportedMediaDto {
    file_name: String,
    markdown: String,
    photo: Option<MealPhotoDto>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ImportDiaryImageRequest {
    diary_relative_path: String,
    category: Option<MealCategoryDto>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ImportMealPhotosRequest {
    date: String,
    category: MealCategoryDto,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
enum MealColumnsDto {
    Count(u8),
    Named(String),
}

impl MealColumnsDto {
    fn into_internal(self) -> CommandResult<media::MealPhotosPerRow> {
        match self {
            Self::Count(2) => Ok(media::MealPhotosPerRow::Two),
            Self::Count(3) => Ok(media::MealPhotosPerRow::Three),
            Self::Named(value) if value.eq_ignore_ascii_case("smart") => {
                Ok(media::MealPhotosPerRow::Smart)
            }
            _ => Err(SecurityErrorDto::invalid_input()),
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct MealExportRequestDto {
    start_date: Option<String>,
    end_date: Option<String>,
    #[serde(default)]
    categories: Vec<MealCategoryDto>,
    columns: MealColumnsDto,
    show_captions: bool,
    brightness: f64,
    contrast: f64,
    saturation: f64,
    warmth: f64,
    tint: f64,
}

#[tauri::command]
fn resolve_media_asset(
    diary_relative_path: String,
    source: String,
    state: State<'_, AppState>,
) -> CommandResult<Option<String>> {
    validate_relative_file_name(&diary_relative_path, &["md"]).map_err(SecurityErrorDto::from)?;
    let Some(file_name) = normalized_markdown_media_name(&source) else {
        return Ok(None);
    };
    media_asset_url(&media_root(&state)?, &file_name)
}

#[tauri::command]
fn select_and_import_diary_image<R: Runtime>(
    request: ImportDiaryImageRequest,
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<Option<ImportedMediaDto>> {
    let diary_name = validate_relative_file_name(&request.diary_relative_path, &["md"])
        .map_err(SecurityErrorDto::from)?;
    let root = diary_root(&state)?;
    let diary_document = diary::load_diary(&root, &diary_name).map_err(map_diary_error)?;
    let Some(selected) = app
        .dialog()
        .file()
        .add_filter("Images", &["jpg", "jpeg", "png", "webp"])
        .blocking_pick_file()
    else {
        return Ok(None);
    };
    let source = selected
        .into_path()
        .map_err(|_| SecurityErrorDto::path_not_allowed())?;
    let settings = state
        .database
        .get_managed_settings()
        .map_err(map_data_error)?;
    let options = media_import_options(
        &settings,
        configured_diary_date(
            &state,
            &root,
            &diary_name,
            diary_document.version.modified_at,
        )?,
        request.category,
    );
    let imported =
        media::import_image(&media_root(&state)?, &source, &options).map_err(map_media_error)?;
    Ok(Some(ImportedMediaDto {
        file_name: imported.file_name,
        markdown: imported.markdown,
        photo: None,
    }))
}

#[tauri::command]
fn list_meal_photos(
    query: MealQueryDto,
    state: State<'_, AppState>,
) -> CommandResult<Vec<MealPhotoDto>> {
    let diary_root = diary_root(&state)?;
    let media_root = media_root(&state)?;
    let metadata = media::read_media_metadata(&media_root).map_err(map_media_error)?;
    let days = media::scan_meal_calendar(&diary_root, &media_root, &query.to_scan_options())
        .map_err(map_media_error)?;
    flatten_meal_days(&media_root, &metadata, days)
}

#[tauri::command]
fn select_and_import_meal_photos<R: Runtime>(
    request: ImportMealPhotosRequest,
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<Vec<ImportedMediaDto>> {
    let date = NaiveDate::parse_from_str(&request.date, "%Y-%m-%d")
        .map_err(|_| SecurityErrorDto::new("invalid_date", "The date is not valid.", true))?;
    let Some(selected) = app
        .dialog()
        .file()
        .add_filter("Images", &["jpg", "jpeg", "png", "webp"])
        .blocking_pick_files()
    else {
        return Ok(Vec::new());
    };
    let sources = selected
        .into_iter()
        .map(|path| {
            path.into_path()
                .map_err(|_| SecurityErrorDto::path_not_allowed())
        })
        .collect::<CommandResult<Vec<_>>>()?;
    let settings = state
        .database
        .get_managed_settings()
        .map_err(map_data_error)?;
    let diary_root = diary_root(&state)?;
    let media_root = media_root(&state)?;
    let date_iso = date.format("%Y-%m-%d").to_string();
    let diary_name = find_or_create_diary_for_date(&diary_root, date, &settings)?;
    let mut imported_items = Vec::with_capacity(sources.len());
    for source in sources {
        let options = media_import_options(&settings, date_iso.clone(), Some(request.category));
        let imported =
            media::import_image(&media_root, &source, &options).map_err(map_media_error)?;
        let append_result = (|| -> CommandResult<()> {
            let current = diary::load_diary(&diary_root, &diary_name).map_err(map_diary_error)?;
            match diary::append_text(
                &diary_root,
                &diary_name,
                &imported.markdown,
                &current.version,
            )
            .map_err(map_diary_error)?
            {
                SaveDiaryOutcome::Saved { .. } => Ok(()),
                SaveDiaryOutcome::Conflict { .. } => Err(SecurityErrorDto::conflict()),
            }
        })();
        if let Err(error) = append_result {
            let _ = media::remove_imported_media(&media_root, &imported.file_name);
            return Err(error);
        }
        imported_items.push(imported);
    }

    emit_diary_changed(&app);
    let query = MealQueryDto {
        start_date: Some(date_iso.clone()),
        end_date: Some(date_iso),
        categories: vec![request.category],
    };
    let metadata = media::read_media_metadata(&media_root).map_err(map_media_error)?;
    let days = media::scan_meal_calendar(&diary_root, &media_root, &query.to_scan_options())
        .map_err(map_media_error)?;
    let photos = flatten_meal_days(&media_root, &metadata, days)?;
    Ok(imported_items
        .into_iter()
        .map(|imported| ImportedMediaDto {
            photo: photos
                .iter()
                .find(|photo| photo.file_name.eq_ignore_ascii_case(&imported.file_name))
                .cloned(),
            file_name: imported.file_name,
            markdown: imported.markdown,
        })
        .collect())
}

#[tauri::command]
fn export_meal_calendar_png<R: Runtime>(
    request: MealExportRequestDto,
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<Option<String>> {
    let scan_options = media::MealScanOptions {
        categories: request.categories.iter().copied().map(Into::into).collect(),
        start_date: request.start_date,
        end_date: request.end_date,
    };
    let diary_root = diary_root(&state)?;
    let media_root = media_root(&state)?;
    let days = media::scan_meal_calendar(&diary_root, &media_root, &scan_options)
        .map_err(map_media_error)?;
    let Some(selected) = app
        .dialog()
        .file()
        .add_filter("PNG image", &["png"])
        .set_file_name(format!("dc-meals-{}.png", Local::now().format("%Y-%m-%d")))
        .blocking_save_file()
    else {
        return Ok(None);
    };
    let destination = selected
        .into_path()
        .map_err(|_| SecurityErrorDto::path_not_allowed())?;
    let filter = meal_filter_from_percentages(
        request.brightness,
        request.contrast,
        request.saturation,
        request.warmth,
        request.tint,
    )?;
    let options = media::MealExportOptions {
        width: 1_200,
        photos_per_row: request.columns.into_internal()?,
        show_captions: request.show_captions,
        filter,
        background_rgb: [247, 246, 241],
    };
    media::export_meal_calendar_png(&media_root, &destination, &days, &options)
        .map_err(map_media_error)?;
    Ok(Some(
        destination
            .file_name()
            .and_then(|value| value.to_str())
            .unwrap_or("dc-meals.png")
            .to_owned(),
    ))
}

fn meal_filter_from_percentages(
    brightness: f64,
    contrast: f64,
    saturation: f64,
    warmth: f64,
    tint: f64,
) -> CommandResult<MealPhotoFilter> {
    let values = [brightness, contrast, saturation, warmth, tint];
    if values.iter().any(|value| !value.is_finite())
        || !(50.0..=150.0).contains(&brightness)
        || !(50.0..=150.0).contains(&contrast)
        || !(0.0..=200.0).contains(&saturation)
        || !(-100.0..=100.0).contains(&warmth)
        || !(-100.0..=100.0).contains(&tint)
    {
        return Err(SecurityErrorDto::invalid_input());
    }
    Ok(MealPhotoFilter {
        enabled: true,
        brightness: (brightness - 100.0) / 100.0,
        contrast: contrast / 100.0,
        saturation: saturation / 100.0,
        warmth: warmth / 100.0,
        tint: tint / 100.0,
    })
}

fn media_import_options(
    settings: &ManagedSettings,
    date_iso: String,
    category: Option<MealCategoryDto>,
) -> media::ImportImageOptions {
    media::ImportImageOptions {
        category: category.map(Into::into),
        date_iso,
        image_name_pattern: settings.image_name_pattern.clone(),
        compress: settings.meal_image_compression_enabled,
        quality: u8::try_from(settings.meal_image_compression_quality)
            .unwrap_or(80)
            .clamp(30, 95),
        max_edge: 2_560,
        capture_gps: settings.photo_location_enabled,
    }
}

fn find_or_create_diary_for_date(
    root: &Path,
    date: NaiveDate,
    settings: &ManagedSettings,
) -> CommandResult<String> {
    let date_iso = date.format("%Y-%m-%d").to_string();
    if let Some(document) = scan_diaries_configured(root, settings)
        .map_err(map_diary_error)?
        .documents
        .into_iter()
        .find(|document| document.date_iso == date_iso)
    {
        return Ok(document.file_name);
    }
    Ok(
        create_diary_configured(root, &date_iso, date, &settings.markdown_template, settings)
            .map_err(map_diary_error)?
            .file_name,
    )
}

fn flatten_meal_days(
    media_root: &Path,
    metadata: &std::collections::BTreeMap<String, media::MediaMetaEntry>,
    days: Vec<media::MealCalendarDay>,
) -> CommandResult<Vec<MealPhotoDto>> {
    let mut result = Vec::new();
    for day in days {
        for photo in day.photos {
            let normalized = photo.file_name.to_lowercase();
            let meta = metadata.get(&normalized);
            let id = sha256_bytes(
                format!(
                    "{}\0{}\0{}",
                    day.date_iso, photo.diary_file_name, normalized
                )
                .as_bytes(),
            );
            let asset_url = if photo.exists {
                media_asset_url(media_root, &photo.file_name)?
            } else {
                None
            };
            result.push(MealPhotoDto {
                id,
                file_name: photo.file_name,
                diary_relative_path: photo.diary_file_name,
                date: day.date_iso.clone(),
                category: photo.category.into(),
                caption: photo.caption,
                energy_kj: photo.energy_kj,
                location: photo
                    .location_name
                    .or_else(|| meta.and_then(|value| value.place.clone())),
                latitude: meta.and_then(|value| value.latitude),
                longitude: meta.and_then(|value| value.longitude),
                asset_url,
                missing: !photo.exists,
            });
        }
    }
    Ok(result)
}

fn normalized_markdown_media_name(source: &str) -> Option<String> {
    let source = source
        .trim()
        .trim_start_matches('<')
        .trim_end_matches('>')
        .strip_prefix("./")
        .unwrap_or_else(|| source.trim().trim_start_matches('<').trim_end_matches('>'));
    if source.contains('%') || source.contains('?') || source.contains('#') {
        return None;
    }
    validate_relative_file_name(source, &["jpg", "jpeg", "png", "webp"]).ok()
}

fn media_asset_url(root: &Path, file_name: &str) -> CommandResult<Option<String>> {
    let path = match resolve_existing_file_beneath(root, file_name) {
        Ok(path) => path,
        Err(SecurityError::NotFound) => return Ok(None),
        Err(error) => return Err(SecurityErrorDto::from(error)),
    };
    let file = open_regular_file_no_reparse(&path).map_err(SecurityErrorDto::from)?;
    let metadata = file
        .metadata()
        .map_err(|error| crate::security::map_io_error(&error))?;
    if metadata.len() > 16 * 1024 * 1024 {
        return Ok(None);
    }
    Ok(crate::media_protocol::url_for_file_name(file_name))
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct BackupPreviewDto {
    format_version: i32,
    exported_at: String,
    thought_count: usize,
    category_count: usize,
    favorite_count: usize,
    date_record_count: usize,
    poem_count: usize,
    preserved_top_level_keys: Vec<String>,
}

impl From<crate::models::BackupPreview> for BackupPreviewDto {
    fn from(preview: crate::models::BackupPreview) -> Self {
        Self {
            format_version: preview.format_version,
            exported_at: millis_to_rfc3339(preview.exported_at),
            thought_count: preview.thought_count,
            category_count: preview.category_count,
            favorite_count: preview.favorite_count,
            date_record_count: preview.date_record_count,
            poem_count: preview.poem_count,
            preserved_top_level_keys: preview.preserved_top_level_keys,
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct BackupSelectionDto {
    token: String,
    display_name: String,
    preview: BackupPreviewDto,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ImportResultDto {
    imported_at: String,
    restore_point_id: String,
    restore_point_label: String,
    thought_count: usize,
    category_count: usize,
    date_record_count: usize,
    poem_count: usize,
    usage_devices_merged: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct BackupWriteResultDto {
    display_name: String,
    file_name: String,
    created_at: String,
    size: u64,
    sha256: String,
    previous_rotated: bool,
    skipped: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RestorePointDto {
    id: String,
    label: String,
    created_at: String,
    size: u64,
}

fn backup_mutation_guard() -> CommandResult<std::sync::MutexGuard<'static, ()>> {
    BACKUP_MUTATION_MUTEX
        .lock()
        .map_err(|_| SecurityErrorDto::storage_unavailable())
}

fn ensure_private_subdirectory(state: &AppState, name: &str) -> CommandResult<PathBuf> {
    let name = validate_relative_file_name(name, &[]).map_err(SecurityErrorDto::from)?;
    reject_reparse_point(&state.private_dir).map_err(SecurityErrorDto::from)?;
    let directory =
        resolve_path_beneath(&state.private_dir, &name).map_err(SecurityErrorDto::from)?;
    if !directory.exists() {
        match fs::create_dir(&directory) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(error) => return Err(crate::security::map_io_error(&error)),
        }
    }
    let directory =
        resolve_path_beneath(&state.private_dir, &name).map_err(SecurityErrorDto::from)?;
    let metadata =
        fs::symlink_metadata(&directory).map_err(|error| crate::security::map_io_error(&error))?;
    if !metadata.is_dir() {
        return Err(SecurityErrorDto::path_not_allowed());
    }
    reject_reparse_point(&directory).map_err(SecurityErrorDto::from)?;
    Ok(directory)
}

fn secure_leaf(root: &Path, name: &str, extensions: &[&str]) -> CommandResult<PathBuf> {
    let name = validate_relative_file_name(name, extensions).map_err(SecurityErrorDto::from)?;
    resolve_path_beneath(root, &name).map_err(SecurityErrorDto::from)
}

#[tauri::command]
fn choose_and_preview_backup<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<Option<BackupSelectionDto>> {
    let Some(selected) = app
        .dialog()
        .file()
        .add_filter("DeskCubby JSON", &["json"])
        .blocking_pick_file()
    else {
        return Ok(None);
    };
    let selected = selected
        .into_path()
        .map_err(|_| SecurityErrorDto::path_not_allowed())?;
    let display_name = selected
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("dc-backup.json")
        .to_owned();
    let bytes = Zeroizing::new(read_bounded_file(&selected, backup::MAX_JSON_BYTES)?);
    let text = std::str::from_utf8(&bytes).map_err(|_| SecurityErrorDto::backup_invalid())?;
    let preview = backup::preview_v18(text).map_err(map_backup_error)?.into();

    let _guard = backup_mutation_guard()?;
    let staging = ensure_private_subdirectory(&state, "import-staging")?;
    let token = Uuid::new_v4().to_string();
    let staged_path = secure_leaf(&staging, &format!("{token}.dci"), &["dci"])?;
    let encrypted_staging = dpapi_protect(&bytes).map_err(SecurityErrorDto::from)?;
    write_verified(&staged_path, &encrypted_staging)?;

    Ok(Some(BackupSelectionDto {
        token,
        display_name,
        preview,
    }))
}

#[tauri::command]
fn import_backup(token: String, state: State<'_, AppState>) -> CommandResult<ImportResultDto> {
    let token = Uuid::parse_str(&token).map_err(|_| SecurityErrorDto::invalid_input())?;
    let _cloud_idle = state.cloud_sync.acquire_idle()?;
    let _guard = backup_mutation_guard()?;
    let staging = ensure_private_subdirectory(&state, "import-staging")?;
    let staged_path = secure_leaf(&staging, &format!("{token}.dci"), &["dci"])?;
    let encrypted = read_bounded_file(&staged_path, backup::MAX_JSON_BYTES + 1024 * 1024)?;
    let bytes = Zeroizing::new(dpapi_unprotect(&encrypted).map_err(SecurityErrorDto::from)?);
    let text = std::str::from_utf8(&bytes).map_err(|_| SecurityErrorDto::backup_invalid())?;
    let prepared = backup::prepare_v18_import_for_shadow(text).map_err(map_backup_error)?;

    let restore_point = create_restore_point_unlocked(&state)?;
    let encrypted_shadow =
        dpapi_protect(&prepared.canonical_bytes).map_err(SecurityErrorDto::from)?;
    let receipt =
        backup::import_v18_transaction(&state.database, &prepared.backup, Some(&encrypted_shadow))
            .map_err(map_backup_error)?;
    // The core database transaction above is the durable import boundary. The
    // usage cache is a separate DPAPI-private, display-only projection: keep
    // its last valid snapshot on a cache failure rather than reporting the
    // already committed core import as failed.
    let usage_devices_merged = !prepared.merge_usage_devices
        || state
            .usage_statistics
            .merge_backup_device_values(&prepared.usage_devices, receipt.imported_at)
            .is_ok();
    drop(prepared);
    // A staged selection is ephemeral and contains a copy of the user-selected
    // file. It is removed only after the database transaction commits.
    let _ = fs::remove_file(staged_path);
    Ok(ImportResultDto {
        imported_at: millis_to_rfc3339(receipt.imported_at),
        restore_point_id: restore_point.id.clone(),
        restore_point_label: restore_point.label.clone(),
        thought_count: receipt.thought_count,
        category_count: receipt.category_count,
        date_record_count: receipt.date_record_count,
        poem_count: receipt.poem_count,
        usage_devices_merged,
    })
}

#[tauri::command]
fn export_backup<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<Option<BackupWriteResultDto>> {
    let now = Local::now();
    let file_name = format!("dc-backup-{}.json", now.format("%Y-%m-%d"));
    let Some(selected) = app
        .dialog()
        .file()
        .add_filter("DeskCubby JSON", &["json"])
        .set_file_name(&file_name)
        .blocking_save_file()
    else {
        return Ok(None);
    };
    let selected = selected
        .into_path()
        .map_err(|_| SecurityErrorDto::path_not_allowed())?;
    let _guard = backup_mutation_guard()?;
    let bytes = build_backup_bytes(&state)?;
    write_verified(&selected, &bytes)?;
    Ok(Some(backup_write_result(
        selected
            .file_name()
            .and_then(|value| value.to_str())
            .unwrap_or(&file_name),
        &bytes,
        false,
        false,
    )))
}

#[tauri::command]
fn run_automatic_backup(state: State<'_, AppState>) -> CommandResult<BackupWriteResultDto> {
    run_automatic_backup_for_state(&state)
}

pub(crate) fn run_automatic_backup_for_state(
    state: &AppState,
) -> CommandResult<BackupWriteResultDto> {
    let _guard = backup_mutation_guard()?;
    run_automatic_backup_unlocked(state)
}

fn run_automatic_backup_unlocked(state: &AppState) -> CommandResult<BackupWriteResultDto> {
    let root = backup_root(state)?;
    let bytes = build_backup_bytes(state)?;
    let pending = secure_leaf(&root, "dc.pending.json", &["json"])?;
    let current = secure_leaf(&root, "dc.json", &["json"])?;
    let previous = secure_leaf(&root, "dc.previous.json", &["json"])?;

    let current_bytes = if current.exists() {
        read_valid_backup_for_rotation(&current)?
    } else {
        None
    };
    if let Some(existing) = current_bytes.as_deref()
        && backup_payload_equivalent(existing, &bytes)
    {
        return Ok(backup_write_result("dc.json", existing, false, true));
    }

    write_verified(&pending, &bytes)?;
    let mut previous_rotated = false;
    if current.exists() {
        if let Some(current_bytes) = current_bytes {
            write_verified(&previous, &current_bytes)?;
            previous_rotated = true;
        } else {
            let corrupt_name = format!(
                "dc.corrupt-{}-{}.json",
                db::now_millis(),
                Uuid::new_v4().simple()
            );
            let corrupt = secure_leaf(&root, &corrupt_name, &["json"])?;
            fs::rename(&current, &corrupt)
                .map_err(|error| crate::security::map_io_error(&error))?;
        }
    }
    write_verified(&current, &bytes)?;
    let verification = read_bounded_file(&current, backup::MAX_JSON_BYTES)?;
    if sha256_bytes(&verification) != sha256_bytes(&bytes) {
        return Err(SecurityErrorDto::storage_unavailable());
    }
    let _ = fs::remove_file(pending);
    Ok(backup_write_result(
        "dc.json",
        &bytes,
        previous_rotated,
        false,
    ))
}

fn backup_payload_equivalent(left: &[u8], right: &[u8]) -> bool {
    fn without_export_time(bytes: &[u8]) -> Option<serde_json::Value> {
        let mut value = serde_json::from_slice::<serde_json::Value>(bytes).ok()?;
        value.as_object_mut()?.remove("exportedAt");
        Some(value)
    }
    matches!(
        (without_export_time(left), without_export_time(right)),
        (Some(left), Some(right)) if left == right
    )
}

fn read_valid_backup_for_rotation(path: &Path) -> CommandResult<Option<Vec<u8>>> {
    reject_reparse_point(path).map_err(SecurityErrorDto::from)?;
    let file = open_regular_file_no_reparse(path).map_err(SecurityErrorDto::from)?;
    let metadata = file
        .metadata()
        .map_err(|error| crate::security::map_io_error(&error))?;
    if metadata.len() > backup::MAX_JSON_BYTES as u64 {
        return Ok(None);
    }
    let mut bytes = Vec::with_capacity(metadata.len() as usize);
    file.take((backup::MAX_JSON_BYTES + 1) as u64)
        .read_to_end(&mut bytes)
        .map_err(|error| crate::security::map_io_error(&error))?;
    if bytes.len() > backup::MAX_JSON_BYTES {
        return Ok(None);
    }
    let Ok(text) = std::str::from_utf8(&bytes) else {
        return Ok(None);
    };
    if backup::preview_v18(text).is_err() {
        return Ok(None);
    }
    Ok(Some(bytes))
}

#[tauri::command]
fn list_restore_points(state: State<'_, AppState>) -> CommandResult<Vec<RestorePointDto>> {
    let directory = ensure_private_subdirectory(&state, "restore-points")?;
    let mut points = Vec::new();
    for entry in fs::read_dir(&directory).map_err(|error| crate::security::map_io_error(&error))? {
        let entry = entry.map_err(|error| crate::security::map_io_error(&error))?;
        let metadata = fs::symlink_metadata(entry.path())
            .map_err(|error| crate::security::map_io_error(&error))?;
        if !metadata.is_file() {
            continue;
        }
        let id = entry.file_name().to_string_lossy().into_owned();
        if validate_relative_file_name(&id, &["dcr"]).is_err() {
            continue;
        }
        let path = match resolve_existing_file_beneath(&directory, &id) {
            Ok(path) => path,
            Err(_) => continue,
        };
        let metadata =
            fs::symlink_metadata(path).map_err(|error| crate::security::map_io_error(&error))?;
        let created_at = metadata
            .modified()
            .ok()
            .and_then(|value| value.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|value| i64::try_from(value.as_millis()).unwrap_or(i64::MAX))
            .unwrap_or(0);
        points.push(RestorePointDto {
            label: format!("Before change · {}", millis_to_local_display(created_at)),
            id,
            created_at: millis_to_rfc3339(created_at),
            size: metadata.len(),
        });
    }
    points.sort_by(|left, right| right.created_at.cmp(&left.created_at));
    points.truncate(50);
    Ok(points)
}

#[tauri::command]
fn restore_restore_point(id: String, state: State<'_, AppState>) -> CommandResult<()> {
    let id = validate_relative_file_name(&id, &["dcr"]).map_err(SecurityErrorDto::from)?;
    let _cloud_idle = state.cloud_sync.acquire_idle()?;
    let _guard = backup_mutation_guard()?;
    let directory = ensure_private_subdirectory(&state, "restore-points")?;
    let path = resolve_existing_file_beneath(&directory, &id).map_err(SecurityErrorDto::from)?;
    let bytes = read_bounded_file(&path, 64 * 1024 * 1024)?;
    // Restoring is itself destructive, so preserve the current core first.
    create_restore_point_unlocked(&state)?;
    backup::restore_recovery_point(&state.database, &bytes).map_err(map_backup_error)
}

fn create_restore_point_unlocked(state: &AppState) -> CommandResult<RestorePointDto> {
    let directory = ensure_private_subdirectory(state, "restore-points")?;
    let created_at = db::now_millis();
    let id = format!("restore-{created_at}-{}.dcr", Uuid::new_v4().simple());
    let bytes = backup::recovery_point_bytes(&state.database).map_err(map_backup_error)?;
    let path = secure_leaf(&directory, &id, &["dcr"])?;
    write_verified(&path, &bytes)?;
    Ok(RestorePointDto {
        id,
        label: format!("Before import · {}", millis_to_local_display(created_at)),
        created_at: millis_to_rfc3339(created_at),
        size: bytes.len() as u64,
    })
}

fn build_backup_bytes(state: &AppState) -> CommandResult<Vec<u8>> {
    build_backup_bytes_from_database(&state.database)
}

fn build_backup_bytes_from_database(database: &db::Database) -> CommandResult<Vec<u8>> {
    let shadow = database
        .get_compatibility_shadow()
        .map_err(map_data_error)?;
    let decrypted = match shadow {
        Some(shadow) => {
            let plaintext = Zeroizing::new(
                dpapi_unprotect(&shadow.ciphertext).map_err(SecurityErrorDto::from)?,
            );
            if backup::verify_source_sha256(&plaintext, &shadow.source_sha256).is_err() {
                return Err(SecurityErrorDto::new(
                    "compatibility_shadow_corrupt",
                    "The encrypted Android compatibility data failed verification.",
                    false,
                ));
            }
            Some(plaintext)
        }
        None => None,
    };
    let cloud_settings = database.get_cloud_sync_settings().map_err(map_data_error)?;
    let cloud_configs = if cloud_settings.configs_managed {
        Some(
            crate::cloud_sync::commands::backup_configs_from_database(database)
                .map_err(|_| SecurityErrorDto::operation_failed())?,
        )
    } else {
        None
    };
    let exported = backup::export_v18_merged_with_cloud_configs(
        database,
        decrypted.as_ref().map(|plaintext| plaintext.as_slice()),
        db::now_millis(),
        cloud_configs.as_deref(),
    );
    exported.map(String::into_bytes).map_err(map_backup_error)
}

/// Cloud JSON snapshots share the same serialization lock as manual/automatic
/// backups so an import cannot interleave with a multi-read export.
pub(crate) fn build_cloud_backup_bytes(database: &db::Database) -> CommandResult<Vec<u8>> {
    let _guard = backup_mutation_guard()?;
    build_backup_bytes_from_database(database)
}

/// Restore a fully downloaded cloud JSON only after the cloud command has
/// verified its one-time confirmation token. The existing core is preserved
/// first, and the DPAPI compatibility shadow stores only canonical scrubbed
/// bytes.
pub(crate) fn restore_cloud_backup_bytes(state: &AppState, bytes: &[u8]) -> CommandResult<()> {
    let _guard = backup_mutation_guard()?;
    let text = std::str::from_utf8(bytes).map_err(|_| SecurityErrorDto::backup_invalid())?;
    let prepared = backup::prepare_v18_import_for_shadow(text).map_err(map_backup_error)?;
    create_restore_point_unlocked(state)?;
    let encrypted_shadow =
        dpapi_protect(&prepared.canonical_bytes).map_err(SecurityErrorDto::from)?;
    let receipt =
        backup::import_v18_transaction(&state.database, &prepared.backup, Some(&encrypted_shadow))
            .map_err(map_backup_error)?;
    if prepared.merge_usage_devices {
        // The structured cloud restore has committed. Usage is deliberately a
        // separate, read-only private projection, so a cache write failure
        // preserves its previous valid snapshot and must not misreport the
        // successful core restore as a failure.
        let _ = state
            .usage_statistics
            .merge_backup_device_values(&prepared.usage_devices, receipt.imported_at);
    }
    Ok(())
}

fn read_bounded_file(path: &Path, limit: usize) -> CommandResult<Vec<u8>> {
    reject_reparse_point(path).map_err(SecurityErrorDto::from)?;
    let file = open_regular_file_no_reparse(path).map_err(SecurityErrorDto::from)?;
    let metadata = file
        .metadata()
        .map_err(|error| crate::security::map_io_error(&error))?;
    if metadata.len() > limit as u64 {
        return Err(SecurityErrorDto::invalid_input());
    }
    let mut bytes = Vec::with_capacity((metadata.len() as usize).min(limit));
    file.take(limit.saturating_add(1) as u64)
        .read_to_end(&mut bytes)
        .map_err(|error| crate::security::map_io_error(&error))?;
    if bytes.len() > limit {
        return Err(SecurityErrorDto::invalid_input());
    }
    Ok(bytes)
}

fn write_new_verified(path: &Path, bytes: &[u8]) -> CommandResult<()> {
    reject_reparse_point(path).map_err(SecurityErrorDto::from)?;
    let mut file = fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(path)
        .map_err(|error| crate::security::map_io_error(&error))?;
    if let Err(error) = file.write_all(bytes).and_then(|_| file.sync_all()) {
        drop(file);
        let _ = fs::remove_file(path);
        return Err(crate::security::map_io_error(&error));
    }
    drop(file);
    let written = match read_bounded_file(path, bytes.len().max(1)) {
        Ok(written) => written,
        Err(error) => {
            let _ = fs::remove_file(path);
            return Err(error);
        }
    };
    if sha256_bytes(&written) != sha256_bytes(bytes) {
        let _ = fs::remove_file(path);
        return Err(SecurityErrorDto::storage_unavailable());
    }
    Ok(())
}

fn write_verified(path: &Path, bytes: &[u8]) -> CommandResult<()> {
    let parent = path
        .parent()
        .ok_or_else(SecurityErrorDto::path_not_allowed)?;
    reject_reparse_point(parent).map_err(SecurityErrorDto::from)?;
    let parent_metadata =
        fs::symlink_metadata(parent).map_err(|error| crate::security::map_io_error(&error))?;
    if !parent_metadata.is_dir() {
        return Err(SecurityErrorDto::path_not_allowed());
    }
    let leaf = path
        .file_name()
        .and_then(|value| value.to_str())
        .ok_or_else(SecurityErrorDto::invalid_input)?;
    let path = secure_leaf(parent, leaf, &[])?;
    let pending_name = format!(".{leaf}.{}.pending", Uuid::new_v4().simple());
    let pending = secure_leaf(parent, &pending_name, &[])?;
    write_new_verified(&pending, bytes)?;

    let previous = if path.exists() {
        let previous_name = format!(".{leaf}.{}.previous", Uuid::new_v4().simple());
        let backup = secure_leaf(parent, &previous_name, &[])?;
        fs::rename(&path, &backup).map_err(|error| crate::security::map_io_error(&error))?;
        Some(backup)
    } else {
        None
    };
    if let Err(error) = fs::rename(&pending, &path) {
        if let Some(previous) = previous.as_ref() {
            let _ = fs::rename(previous, &path);
        }
        return Err(crate::security::map_io_error(&error));
    }
    let verification = read_bounded_file(&path, bytes.len().max(1))?;
    if sha256_bytes(&verification) != sha256_bytes(bytes) {
        if let Some(previous) = previous.as_ref() {
            let _ = fs::remove_file(&path);
            let _ = fs::rename(previous, &path);
        } else {
            let _ = fs::remove_file(&path);
        }
        return Err(SecurityErrorDto::storage_unavailable());
    }
    if let Some(previous) = previous {
        let _ = fs::remove_file(previous);
    }
    Ok(())
}

fn backup_write_result(
    file_name: &str,
    bytes: &[u8],
    previous_rotated: bool,
    skipped: bool,
) -> BackupWriteResultDto {
    BackupWriteResultDto {
        display_name: file_name.to_owned(),
        file_name: file_name.to_owned(),
        created_at: millis_to_rfc3339(db::now_millis()),
        size: bytes.len() as u64,
        sha256: sha256_bytes(bytes),
        previous_rotated,
        skipped,
    }
}

fn sha256_bytes(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

fn millis_to_local_display(value: i64) -> String {
    DateTime::<Utc>::from_timestamp_millis(value)
        .map(|date| {
            date.with_timezone(&Local)
                .format("%Y-%m-%d %H:%M")
                .to_string()
        })
        .unwrap_or_else(|| "unknown time".to_owned())
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct VaultStatusDto {
    schema_version: u32,
    lock_state: &'static str,
    corrupted_item_count: usize,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct VaultItemDto {
    id: String,
    content: String,
    note: Option<String>,
    sort_order: String,
    created_at: String,
    updated_at: String,
    primary_action: &'static str,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VaultPasswordRequest {
    schema_version: u32,
    password: crate::vault::VaultPassword,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VaultItemDraftRequest {
    schema_version: u32,
    content: String,
    note: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VaultItemUpdateRequest {
    schema_version: u32,
    id: String,
    content: String,
    note: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VaultItemIdRequest {
    schema_version: u32,
    id: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VaultReorderRequest {
    schema_version: u32,
    ids: Vec<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VaultChangePasswordRequest {
    schema_version: u32,
    current_password: crate::vault::VaultPassword,
    new_password: crate::vault::VaultPassword,
}

fn map_vault_error(error: crate::vault::VaultError) -> SecurityErrorDto {
    let recoverable = !matches!(
        error,
        crate::vault::VaultError::MetadataCorrupt | crate::vault::VaultError::CorruptedItems
    );
    SecurityErrorDto::new(
        error.code(),
        "The vault operation could not be completed.",
        recoverable,
    )
}

fn vault_status(state: &AppState) -> CommandResult<VaultStatusDto> {
    let lock_state = state.vault.lock_state().map_err(map_vault_error)?;
    let corrupted_item_count = if matches!(lock_state, crate::vault::VaultLockState::Unlocked) {
        state
            .vault
            .content_state()
            .map_err(map_vault_error)?
            .corrupted_item_count
    } else {
        0
    };
    Ok(VaultStatusDto {
        schema_version: 1,
        lock_state: match lock_state {
            crate::vault::VaultLockState::NotSet => "NOT_SET",
            crate::vault::VaultLockState::Locked => "LOCKED",
            crate::vault::VaultLockState::Unlocked => "UNLOCKED",
        },
        corrupted_item_count,
    })
}

fn vault_item_dto(item: crate::vault::VaultItem) -> VaultItemDto {
    let primary_action = if crate::vault::safe_vault_http_url_or_null(&item.content).is_some() {
        "OPEN_URL"
    } else {
        "COPY"
    };
    VaultItemDto {
        id: item.id.to_string(),
        content: item.content,
        note: item.note,
        sort_order: item.sort_order.to_string(),
        created_at: item.created_at.to_string(),
        updated_at: item.updated_at.to_string(),
        primary_action,
    }
}

fn list_vault_item_dtos(state: &AppState) -> CommandResult<Vec<VaultItemDto>> {
    Ok(state
        .vault
        .content_state()
        .map_err(map_vault_error)?
        .items
        .into_iter()
        .map(vault_item_dto)
        .collect())
}

#[tauri::command]
fn get_vault_status(state: State<'_, AppState>) -> CommandResult<VaultStatusDto> {
    vault_status(&state)
}

#[tauri::command]
fn setup_vault(
    request: VaultPasswordRequest,
    state: State<'_, AppState>,
) -> CommandResult<VaultStatusDto> {
    if request.schema_version != 1 {
        return Err(SecurityErrorDto::invalid_input());
    }
    state
        .vault
        .setup_password(&request.password)
        .map_err(map_vault_error)?;
    match vault_status(&state) {
        Ok(status) => Ok(status),
        Err(error) => {
            state.vault.lock();
            Err(error)
        }
    }
}

#[tauri::command]
fn unlock_vault(
    request: VaultPasswordRequest,
    state: State<'_, AppState>,
) -> CommandResult<VaultStatusDto> {
    if request.schema_version != 1 {
        return Err(SecurityErrorDto::invalid_input());
    }
    state
        .vault
        .unlock(&request.password)
        .map_err(map_vault_error)?;
    match vault_status(&state) {
        Ok(status) => Ok(status),
        Err(error) => {
            state.vault.lock();
            Err(error)
        }
    }
}

#[tauri::command]
fn lock_vault(state: State<'_, AppState>) -> CommandResult<VaultStatusDto> {
    state.vault.lock();
    Ok(VaultStatusDto {
        schema_version: 1,
        lock_state: "LOCKED",
        corrupted_item_count: 0,
    })
}

#[tauri::command]
fn list_vault_items(state: State<'_, AppState>) -> CommandResult<Vec<VaultItemDto>> {
    list_vault_item_dtos(&state)
}

#[tauri::command]
fn create_vault_item(
    request: VaultItemDraftRequest,
    state: State<'_, AppState>,
) -> CommandResult<VaultItemDto> {
    if request.schema_version != 1 {
        return Err(SecurityErrorDto::invalid_input());
    }
    let item = state
        .vault
        .add_item(&request.content, request.note.as_deref(), db::now_millis())
        .map_err(map_vault_error)?;
    Ok(vault_item_dto(item))
}

#[tauri::command]
fn update_vault_item(
    request: VaultItemUpdateRequest,
    state: State<'_, AppState>,
) -> CommandResult<VaultItemDto> {
    if request.schema_version != 1 {
        return Err(SecurityErrorDto::invalid_input());
    }
    let id = parse_positive_ipc_id(&request.id)?;
    state
        .vault
        .update_item(
            id,
            &request.content,
            request.note.as_deref(),
            db::now_millis(),
        )
        .map_err(map_vault_error)?;
    list_vault_item_dtos(&state)?
        .into_iter()
        .find(|item| item.id == request.id)
        .ok_or_else(SecurityErrorDto::not_found)
}

#[tauri::command]
fn delete_vault_item(request: VaultItemIdRequest, state: State<'_, AppState>) -> CommandResult<()> {
    if request.schema_version != 1 {
        return Err(SecurityErrorDto::invalid_input());
    }
    state
        .vault
        .delete_item(parse_positive_ipc_id(&request.id)?)
        .map_err(map_vault_error)
}

#[tauri::command]
fn reorder_vault_items(
    request: VaultReorderRequest,
    state: State<'_, AppState>,
) -> CommandResult<Vec<VaultItemDto>> {
    if request.schema_version != 1 || request.ids.len() > 100_000 {
        return Err(SecurityErrorDto::invalid_input());
    }
    let ids = request
        .ids
        .iter()
        .map(|id| parse_positive_ipc_id(id))
        .collect::<CommandResult<Vec<_>>>()?;
    state.vault.reorder_items(&ids).map_err(map_vault_error)?;
    list_vault_item_dtos(&state)
}

#[tauri::command]
fn change_vault_password(
    request: VaultChangePasswordRequest,
    state: State<'_, AppState>,
) -> CommandResult<()> {
    if request.schema_version != 1 {
        return Err(SecurityErrorDto::invalid_input());
    }
    state
        .vault
        .change_password(&request.current_password, &request.new_password)
        .map_err(map_vault_error)
}

fn vault_item_content(state: &AppState, id: i64) -> CommandResult<String> {
    state
        .vault
        .content_state()
        .map_err(map_vault_error)?
        .items
        .into_iter()
        .find(|item| item.id == id)
        .map(|item| item.content)
        .ok_or_else(SecurityErrorDto::not_found)
}

#[tauri::command]
fn copy_vault_item(request: VaultItemIdRequest, state: State<'_, AppState>) -> CommandResult<()> {
    if request.schema_version != 1 {
        return Err(SecurityErrorDto::invalid_input());
    }
    let content = vault_item_content(&state, parse_positive_ipc_id(&request.id)?)?;
    set_clipboard_text(&content)
}

#[tauri::command]
fn open_vault_item_url(
    request: VaultItemIdRequest,
    state: State<'_, AppState>,
) -> CommandResult<()> {
    if request.schema_version != 1 {
        return Err(SecurityErrorDto::invalid_input());
    }
    let content = vault_item_content(&state, parse_positive_ipc_id(&request.id)?)?;
    let url = crate::vault::safe_vault_http_url_or_null(&content).ok_or_else(|| {
        SecurityErrorDto::new(
            "vault_url_not_safe",
            "The vault entry is not a safe URL.",
            true,
        )
    })?;
    open_external_http_url(&url).map_err(|_| {
        SecurityErrorDto::new(
            "vault_open_failed",
            "The vault URL could not be opened.",
            true,
        )
    })
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ChooseUsageSourceRequest {
    dto_version: u32,
    mode: crate::usage::UsageSourceModeDto,
}

fn map_usage_error(error: crate::usage::UsageServiceError) -> SecurityErrorDto {
    SecurityErrorDto::new(error.code(), "Phone usage data could not be read.", true)
}

#[tauri::command]
fn get_usage_page(
    query: crate::usage::UsageQueryDto,
    state: State<'_, AppState>,
) -> CommandResult<Option<crate::usage::UsageSnapshotDto>> {
    state
        .usage_statistics
        .snapshot(&query)
        .map_err(map_usage_error)
}

#[tauri::command]
fn choose_usage_statistics_source<R: Runtime>(
    app: AppHandle<R>,
    request: ChooseUsageSourceRequest,
    state: State<'_, AppState>,
) -> CommandResult<Option<crate::usage::UsageSnapshotDto>> {
    if request.dto_version != crate::usage::USAGE_DTO_VERSION
        || request.mode == crate::usage::UsageSourceModeDto::CloudSync
    {
        return Err(SecurityErrorDto::invalid_input());
    }
    let Some(selected) = app
        .dialog()
        .file()
        .add_filter("Android usage statistics", &["json"])
        .blocking_pick_file()
    else {
        return Ok(None);
    };
    let selected = selected
        .into_path()
        .map_err(|_| SecurityErrorDto::path_not_allowed())?;
    match request.mode {
        crate::usage::UsageSourceModeDto::Snapshot => state
            .usage_statistics
            .import_snapshot(&selected, db::now_millis()),
        crate::usage::UsageSourceModeDto::LinkedFile => state
            .usage_statistics
            .link_file(&selected, db::now_millis()),
        crate::usage::UsageSourceModeDto::CloudSync => {
            return Err(SecurityErrorDto::invalid_input());
        }
    }
    .map_err(map_usage_error)?;
    state
        .usage_statistics
        .snapshot(&crate::usage::UsageQueryDto::default())
        .map_err(map_usage_error)
}

#[tauri::command]
fn refresh_usage_statistics(
    query: crate::usage::UsageQueryDto,
    state: State<'_, AppState>,
) -> CommandResult<Option<crate::usage::UsageSnapshotDto>> {
    state
        .usage_statistics
        .refresh_linked(db::now_millis())
        .map_err(map_usage_error)?;
    state
        .usage_statistics
        .snapshot(&query)
        .map_err(map_usage_error)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ChooseHealthSourceRequest {
    dto_version: u32,
    mode: crate::health::HealthSourceModeDto,
}

fn map_health_error(error: crate::health::HealthServiceError) -> SecurityErrorDto {
    SecurityErrorDto::new(error.code(), "Health data could not be read.", true)
}

#[tauri::command]
fn get_health_page(
    query: crate::health::HealthQueryDto,
    state: State<'_, AppState>,
) -> CommandResult<Option<crate::health::HealthSnapshotDto>> {
    state
        .health_statistics
        .snapshot(&query)
        .map_err(map_health_error)
}

#[tauri::command]
fn choose_health_statistics_source<R: Runtime>(
    app: AppHandle<R>,
    request: ChooseHealthSourceRequest,
    state: State<'_, AppState>,
) -> CommandResult<Option<crate::health::HealthSnapshotDto>> {
    if request.dto_version != crate::health::HEALTH_DTO_VERSION {
        return Err(SecurityErrorDto::invalid_input());
    }
    let Some(selected) = app
        .dialog()
        .file()
        .add_filter("Android health statistics", &["json"])
        .blocking_pick_file()
    else {
        return Ok(None);
    };
    let selected = selected
        .into_path()
        .map_err(|_| SecurityErrorDto::path_not_allowed())?;
    match request.mode {
        crate::health::HealthSourceModeDto::Snapshot => state
            .health_statistics
            .import_snapshot(&selected, db::now_millis()),
        crate::health::HealthSourceModeDto::LinkedFile => state
            .health_statistics
            .link_file(&selected, db::now_millis()),
    }
    .map_err(map_health_error)?;
    state
        .health_statistics
        .snapshot(&crate::health::HealthQueryDto::default())
        .map_err(map_health_error)
}

#[tauri::command]
fn refresh_health_statistics(
    query: crate::health::HealthQueryDto,
    state: State<'_, AppState>,
) -> CommandResult<Option<crate::health::HealthSnapshotDto>> {
    state
        .health_statistics
        .refresh_linked(db::now_millis())
        .map_err(map_health_error)?;
    state
        .health_statistics
        .snapshot(&query)
        .map_err(map_health_error)
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct UpdateStateDto {
    schema_version: u32,
    configured: bool,
    current_version: &'static str,
    automatic_checks_enabled: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct UpdateCheckResultDto {
    schema_version: u32,
    kind: &'static str,
    current_version: String,
    version: Option<String>,
    notes: Option<String>,
    published_at: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct UpdateDownloadProgressDto {
    schema_version: u32,
    downloaded_bytes: String,
    total_bytes: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AutomaticUpdateChecksRequest {
    schema_version: u32,
    enabled: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct InstallUpdateRequest {
    schema_version: u32,
    expected_version: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum OfficialLinkTarget {
    Repository,
    Tutorial,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct OpenOfficialLinkRequest {
    schema_version: u32,
    target: OfficialLinkTarget,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct OpenExternalLinkRequest {
    schema_version: u32,
    url: String,
}

fn map_update_error(error: updater::UpdateError) -> SecurityErrorDto {
    SecurityErrorDto::new(error.code(), error.message(), error.retryable())
}

#[tauri::command]
fn get_update_state<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<UpdateStateDto> {
    let settings = state
        .database
        .get_update_settings()
        .map_err(map_data_error)?;
    Ok(UpdateStateDto {
        schema_version: 1,
        configured: updater::is_configured(&app),
        current_version: env!("CARGO_PKG_VERSION"),
        automatic_checks_enabled: settings.automatic_checks_enabled,
    })
}

#[tauri::command]
fn set_automatic_update_checks(
    request: AutomaticUpdateChecksRequest,
    state: State<'_, AppState>,
) -> CommandResult<()> {
    if request.schema_version != 1 {
        return Err(SecurityErrorDto::invalid_input());
    }
    state
        .database
        .set_automatic_update_checks(request.enabled, db::now_millis())
        .map_err(map_data_error)
}

#[tauri::command]
async fn check_for_updates<R: Runtime>(app: AppHandle<R>) -> CommandResult<UpdateCheckResultDto> {
    let current_version = env!("CARGO_PKG_VERSION").to_owned();
    let update = updater::check_for_update(&app)
        .await
        .map_err(map_update_error)?;
    Ok(match update {
        Some(update) => UpdateCheckResultDto {
            schema_version: 1,
            kind: "AVAILABLE",
            current_version: update.current_version,
            version: Some(update.version),
            notes: update.notes,
            published_at: update.published_at,
        },
        None => UpdateCheckResultDto {
            schema_version: 1,
            kind: "UP_TO_DATE",
            current_version,
            version: None,
            notes: None,
            published_at: None,
        },
    })
}

#[tauri::command]
async fn install_update<R: Runtime>(
    app: AppHandle<R>,
    request: InstallUpdateRequest,
) -> CommandResult<()> {
    if request.schema_version != 1 {
        return Err(SecurityErrorDto::invalid_input());
    }
    let progress_app = app.clone();
    let mut downloaded = 0_u64;
    updater::download_and_install(
        &app,
        &request.expected_version,
        move |chunk_bytes, total_bytes| {
            downloaded = downloaded.saturating_add(chunk_bytes as u64);
            let _ = progress_app.emit(
                "update-download-progress",
                UpdateDownloadProgressDto {
                    schema_version: 1,
                    downloaded_bytes: downloaded.to_string(),
                    total_bytes: total_bytes.map(|value| value.to_string()),
                },
            );
        },
        || {},
    )
    .await
    .map_err(map_update_error)?;
    app.restart()
}

#[tauri::command]
fn open_official_link(request: OpenOfficialLinkRequest) -> CommandResult<()> {
    if request.schema_version != 1 {
        return Err(SecurityErrorDto::invalid_input());
    }
    let url = match request.target {
        OfficialLinkTarget::Repository => "https://github.com/vexpaer/DeskCubby",
        OfficialLinkTarget::Tutorial => {
            "https://github.com/vexpaer/DeskCubby/blob/main/TUTORIAL.md"
        }
    };
    open_fixed_https_url(url)
}

#[tauri::command]
fn open_external_link(request: OpenExternalLinkRequest) -> CommandResult<()> {
    if request.schema_version != 1 {
        return Err(SecurityErrorDto::invalid_input());
    }
    open_external_http_url(&request.url)
}

#[cfg(windows)]
fn open_fixed_https_url(url: &'static str) -> CommandResult<()> {
    if !url.starts_with("https://") {
        return Err(SecurityErrorDto::invalid_input());
    }
    open_external_http_url(url)
}

#[cfg(windows)]
fn open_external_http_url(url: &str) -> CommandResult<()> {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    validate_external_http_url(url)?;
    std::process::Command::new("rundll32.exe")
        .args(["url.dll,FileProtocolHandler", url])
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map(|_| ())
        .map_err(|_| SecurityErrorDto::new("open_failed", "The link could not be opened.", true))
}

fn validate_external_http_url(url: &str) -> CommandResult<()> {
    const MAX_EXTERNAL_URL_UTF16_UNITS: usize = 8_192;
    if url.is_empty()
        || url.trim() != url
        || url.encode_utf16().count() > MAX_EXTERNAL_URL_UTF16_UNITS
        || url.chars().any(char::is_control)
    {
        return Err(SecurityErrorDto::invalid_input());
    }
    let parsed = reqwest::Url::parse(url).map_err(|_| SecurityErrorDto::invalid_input())?;
    if !matches!(parsed.scheme(), "http" | "https")
        || parsed.host_str().is_none()
        || !parsed.username().is_empty()
        || parsed.password().is_some()
    {
        return Err(SecurityErrorDto::invalid_input());
    }
    Ok(())
}

#[cfg(not(windows))]
fn open_fixed_https_url(_url: &'static str) -> CommandResult<()> {
    Err(SecurityErrorDto::new(
        "open_failed",
        "The link could not be opened.",
        true,
    ))
}

#[cfg(not(windows))]
fn open_external_http_url(_url: &str) -> CommandResult<()> {
    Err(SecurityErrorDto::new(
        "open_failed",
        "The link could not be opened.",
        true,
    ))
}

#[cfg(windows)]
fn set_clipboard_text(text: &str) -> CommandResult<()> {
    use std::{iter, ptr, thread, time::Duration};

    const CF_UNICODETEXT: u32 = 13;
    const GMEM_MOVEABLE: u32 = 0x0002;
    const MAX_CLIPBOARD_UTF16_UNITS: usize = 1024 * 1024;

    let wide = text.encode_utf16().chain(iter::once(0)).collect::<Vec<_>>();
    if wide.len() > MAX_CLIPBOARD_UTF16_UNITS {
        return Err(SecurityErrorDto::new(
            "vault_clipboard_failed",
            "The vault entry is too large to copy.",
            true,
        ));
    }

    let mut opened = false;
    for _ in 0..5 {
        // SAFETY: a null owner is allowed and no borrowed pointer is retained.
        if unsafe { open_clipboard(ptr::null_mut()) } != 0 {
            opened = true;
            break;
        }
        thread::sleep(Duration::from_millis(10));
    }
    if !opened {
        return Err(SecurityErrorDto::new(
            "vault_clipboard_failed",
            "The clipboard is temporarily unavailable.",
            true,
        ));
    }

    struct ClipboardGuard;
    impl Drop for ClipboardGuard {
        fn drop(&mut self) {
            // SAFETY: this guard is created only after OpenClipboard succeeds.
            unsafe {
                let _ = close_clipboard();
            }
        }
    }
    let _guard = ClipboardGuard;

    // SAFETY: the current thread owns the open clipboard.
    if unsafe { empty_clipboard() } == 0 {
        return Err(SecurityErrorDto::new(
            "vault_clipboard_failed",
            "The clipboard is temporarily unavailable.",
            true,
        ));
    }
    let bytes = wide
        .len()
        .checked_mul(std::mem::size_of::<u16>())
        .ok_or_else(SecurityErrorDto::invalid_input)?;
    // SAFETY: GlobalAlloc returns an owned movable block or null.
    let allocation = unsafe { global_alloc(GMEM_MOVEABLE, bytes) };
    if allocation.is_null() {
        return Err(SecurityErrorDto::new(
            "vault_clipboard_failed",
            "The clipboard is temporarily unavailable.",
            true,
        ));
    }
    // SAFETY: allocation is a live HGLOBAL allocated above.
    let destination = unsafe { global_lock(allocation) }.cast::<u16>();
    if destination.is_null() {
        // SAFETY: ownership has not been transferred to the clipboard.
        unsafe {
            let _ = global_free(allocation);
        }
        return Err(SecurityErrorDto::new(
            "vault_clipboard_failed",
            "The clipboard is temporarily unavailable.",
            true,
        ));
    }
    // SAFETY: the block was allocated for exactly `wide.len()` u16 units.
    unsafe {
        ptr::copy_nonoverlapping(wide.as_ptr(), destination, wide.len());
        let _ = global_unlock(allocation);
    }
    // SAFETY: on success Windows takes ownership of the allocation.
    if unsafe { set_clipboard_data(CF_UNICODETEXT, allocation) }.is_null() {
        // SAFETY: failed SetClipboardData leaves ownership with the caller.
        unsafe {
            let _ = global_free(allocation);
        }
        return Err(SecurityErrorDto::new(
            "vault_clipboard_failed",
            "The clipboard is temporarily unavailable.",
            true,
        ));
    }
    Ok(())
}

#[cfg(not(windows))]
fn set_clipboard_text(_text: &str) -> CommandResult<()> {
    Err(SecurityErrorDto::new(
        "vault_clipboard_failed",
        "The clipboard is temporarily unavailable.",
        true,
    ))
}

#[cfg(windows)]
#[link(name = "user32")]
unsafe extern "system" {
    #[link_name = "OpenClipboard"]
    fn open_clipboard(owner: *mut std::ffi::c_void) -> i32;
    #[link_name = "CloseClipboard"]
    fn close_clipboard() -> i32;
    #[link_name = "EmptyClipboard"]
    fn empty_clipboard() -> i32;
    #[link_name = "SetClipboardData"]
    fn set_clipboard_data(format: u32, memory: *mut std::ffi::c_void) -> *mut std::ffi::c_void;
}

#[cfg(windows)]
#[link(name = "kernel32")]
unsafe extern "system" {
    #[link_name = "GlobalAlloc"]
    fn global_alloc(flags: u32, bytes: usize) -> *mut std::ffi::c_void;
    #[link_name = "GlobalLock"]
    fn global_lock(memory: *mut std::ffi::c_void) -> *mut std::ffi::c_void;
    #[link_name = "GlobalUnlock"]
    fn global_unlock(memory: *mut std::ffi::c_void) -> i32;
    #[link_name = "GlobalFree"]
    fn global_free(memory: *mut std::ffi::c_void) -> *mut std::ffi::c_void;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn windows_settings_dto_round_trips_home_configuration() {
        let managed = ManagedSettings {
            user_name: "Desk user".to_owned(),
            home_greetings: vec![HomeGreeting {
                chinese: "你好，{name}".to_owned(),
                english: "Hello, {name}".to_owned(),
            }],
            home_widget_borders_enabled: false,
            home_widgets: vec![
                "notes".to_owned(),
                "game_shortcuts".to_owned(),
                "record_overview".to_owned(),
            ],
            home_widget_titles: vec!["game_shortcuts".to_owned()],
            home_game_shortcuts: vec!["spider".to_owned(), "2048_6".to_owned()],
            meal_buttons_use_icons: true,
            meal_button_icons: vec![
                "A".to_owned(),
                "B".to_owned(),
                "C".to_owned(),
                "D".to_owned(),
                "E".to_owned(),
                "F".to_owned(),
            ],
            ..ManagedSettings::default()
        };

        let dto = WindowsSettingsDto::from_parts(managed.clone(), LocalPaths::default());
        let serialized = serde_json::to_value(&dto).expect("serialize Windows settings");
        assert_eq!(serialized["homeWidgets"][0], "notes");
        assert_eq!(serialized["homeWidgetTitles"][0], "game_shortcuts");
        assert_eq!(serialized["homeWidgetBordersEnabled"], false);
        assert_eq!(serialized["homeGameShortcuts"][0], "spider");
        assert_eq!(serialized["mealButtonsUseIcons"], true);

        let mut restored = ManagedSettings::default();
        dto.apply_to(&mut restored);
        assert_eq!(restored.user_name, managed.user_name);
        assert_eq!(restored.home_greetings, managed.home_greetings);
        assert_eq!(restored.home_widgets, managed.home_widgets);
        assert_eq!(restored.home_widget_titles, managed.home_widget_titles);
        assert_eq!(restored.home_game_shortcuts, managed.home_game_shortcuts);
        assert_eq!(
            restored.home_widget_borders_enabled,
            managed.home_widget_borders_enabled
        );
        assert_eq!(restored.meal_button_icons, managed.meal_button_icons);
        assert_eq!(
            restored.meal_buttons_use_icons,
            managed.meal_buttons_use_icons
        );
    }

    #[test]
    fn background_picker_accepts_only_bounded_regular_image_files() {
        let directory = tempfile::tempdir().expect("temporary directory");
        let image = directory.path().join("background.PNG");
        fs::write(&image, b"not-decoded-at-selection-time").expect("write image candidate");
        assert_eq!(
            validate_background_image(&image).expect("valid image path"),
            fs::canonicalize(&image).expect("canonical image")
        );

        let wrong_extension = directory.path().join("background.txt");
        fs::write(&wrong_extension, b"content").expect("write wrong extension");
        assert!(validate_background_image(&wrong_extension).is_err());

        let empty = directory.path().join("empty.jpg");
        fs::write(&empty, []).expect("write empty image");
        assert!(validate_background_image(&empty).is_err());
    }

    #[test]
    fn default_meal_filter_percentages_map_to_identity() {
        let filter =
            meal_filter_from_percentages(100.0, 100.0, 100.0, 0.0, 0.0).expect("valid filter");
        assert_eq!(filter.brightness, 0.0);
        assert_eq!(filter.contrast, 1.0);
        assert_eq!(filter.saturation, 1.0);
        assert_eq!(filter.warmth, 0.0);
        assert_eq!(filter.tint, 0.0);
    }

    #[test]
    fn diary_conflict_dto_uses_frontend_camel_case_fields() {
        let value = serde_json::to_value(DiarySaveResultDto::Conflict {
            current_version: IpcFileVersion {
                sha256: "a".repeat(64),
                size: 1,
                modified_at: "2026-07-29T00:00:00Z".to_owned(),
            },
            reason: "changed",
        })
        .expect("serialize conflict");
        assert!(value.get("currentVersion").is_some());
        assert!(value.get("current_version").is_none());
    }

    #[test]
    fn meal_filter_rejects_non_finite_and_out_of_range_values() {
        assert!(meal_filter_from_percentages(f64::NAN, 100.0, 100.0, 0.0, 0.0).is_err());
        assert!(meal_filter_from_percentages(100.0, 151.0, 100.0, 0.0, 0.0).is_err());
        assert!(meal_filter_from_percentages(100.0, 100.0, 201.0, 0.0, 0.0).is_err());
    }

    #[test]
    fn external_links_accept_only_bounded_credential_free_http_urls() {
        assert!(validate_external_http_url("https://example.test/path?q=1").is_ok());
        assert!(validate_external_http_url("http://example.test").is_ok());
        assert!(validate_external_http_url("mailto:user@example.test").is_err());
        assert!(validate_external_http_url("https://user@example.test").is_err());
        assert!(validate_external_http_url("https://example.test\nnext").is_err());
        assert!(
            validate_external_http_url(&format!("https://example.test/{}", "a".repeat(8_192)))
                .is_err()
        );
    }

    #[test]
    fn ipc_entity_long_fields_are_decimal_strings() {
        let thought = serde_json::to_value(ThoughtDto::from(Thought {
            id: i64::MAX,
            content: "thought".to_owned(),
            created_at: i64::MAX - 4,
            updated_at: i64::MAX - 3,
            pinned: true,
            deleted_at: Some(i64::MAX - 2),
            sort_order: i64::MAX - 1,
            category_id: Some(i64::MAX - 5),
            highlighted: true,
        }))
        .expect("serialize thought");
        assert_eq!(thought["id"], i64::MAX.to_string());
        assert_eq!(thought["categoryId"], (i64::MAX - 5).to_string());
        assert_eq!(thought["createdAt"], (i64::MAX - 4).to_string());
        assert_eq!(thought["updatedAt"], (i64::MAX - 3).to_string());
        assert_eq!(thought["deletedAt"], (i64::MAX - 2).to_string());
        assert_eq!(thought["sortOrder"], (i64::MAX - 1).to_string());

        let category = serde_json::to_value(ThoughtCategoryDto::from(ThoughtCategory {
            id: i64::MAX,
            name: "category".to_owned(),
            color_argb: i32::MIN,
            sort_order: i64::MAX - 1,
            created_at: i64::MAX - 2,
            updated_at: i64::MAX - 1,
        }))
        .expect("serialize category");
        assert_eq!(category["id"], i64::MAX.to_string());
        assert_eq!(category["sortOrder"], (i64::MAX - 1).to_string());
        assert_eq!(category["createdAt"], (i64::MAX - 2).to_string());
        assert_eq!(category["updatedAt"], (i64::MAX - 1).to_string());

        let record = serde_json::to_value(DateRecordDto::from(DateRecord {
            id: i64::MAX,
            name: "date".to_owned(),
            icon: "📅".to_owned(),
            date_iso: "2099-12-31".to_owned(),
            created_at: i64::MAX - 1,
            updated_at: i64::MAX,
        }))
        .expect("serialize date record");
        assert_eq!(record["id"], i64::MAX.to_string());
        assert_eq!(record["createdAt"], (i64::MAX - 1).to_string());
        assert_eq!(record["updatedAt"], i64::MAX.to_string());

        let poem = serde_json::to_value(SavedPoemDto::from(SavedPoem {
            id: i64::MAX,
            content: "poem".to_owned(),
            source: "source".to_owned(),
            created_at: i64::MAX - 1,
            updated_at: i64::MAX,
            sort_order: i64::MAX - 2,
            category_id: None,
        }))
        .expect("serialize poem");
        assert_eq!(poem["id"], i64::MAX.to_string());
        assert_eq!(poem["createdAt"], (i64::MAX - 1).to_string());
        assert_eq!(poem["updatedAt"], i64::MAX.to_string());
    }

    #[test]
    fn ipc_id_parser_accepts_only_positive_decimal_i64_values() {
        assert_eq!(parse_positive_ipc_id("1").expect("positive id"), 1);
        assert_eq!(
            parse_positive_ipc_id(&i64::MAX.to_string()).expect("maximum id"),
            i64::MAX
        );
        for invalid in [
            "",
            "0",
            "01",
            "-1",
            "+1",
            " 1",
            "1 ",
            "1.0",
            "１２",
            "9223372036854775808",
        ] {
            assert_eq!(
                parse_positive_ipc_id(invalid).expect_err("invalid id").code,
                "invalid_input"
            );
        }
    }

    #[test]
    fn ipc_drafts_parse_string_ids_without_using_json_numbers() {
        let thought: ThoughtDraftDto = serde_json::from_value(serde_json::json!({
            "id": "9223372036854775807",
            "content": "thought",
            "categoryId": "9007199254740993"
        }))
        .expect("deserialize thought draft");
        let thought = thought.into_internal().expect("convert thought draft");
        assert_eq!(thought.id, Some(i64::MAX));
        assert_eq!(thought.category_id, Some(9_007_199_254_740_993));

        assert!(
            serde_json::from_value::<ThoughtDraftDto>(serde_json::json!({
                "content": "thought",
                "categoryId": 9007199254740993_i64
            }))
            .is_err()
        );
        let invalid: DateRecordDraftDto = serde_json::from_value(serde_json::json!({
            "id": "0",
            "name": "date",
            "icon": "📅",
            "dateIso": "2026-07-29"
        }))
        .expect("deserialize invalid date draft");
        assert_eq!(
            invalid.into_internal().expect_err("reject zero id").code,
            "invalid_input"
        );
    }
}
