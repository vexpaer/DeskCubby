//! Local-first TXT/PDF reader for the Windows client.
//!
//! Files enter this boundary only through an explicit native file-picker action. The
//! WebView receives TXT logical pages or an opaque `reader://` URL; an absolute path never
//! crosses IPC. Library metadata, progress, preferences and engagement time live in a private
//! crash-safe JSON file that is intentionally outside Android v27, automatic backup and cloud
//! sync data.

use crate::AppState;
use crate::security::{
    CommandResult, SecurityError, SecurityErrorDto, open_regular_file_no_reparse,
    reject_reparse_point, validate_relative_file_name,
};
use encoding_rs::GB18030;
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard, OnceLock};
use tauri::http::{Method, Request, Response, StatusCode, header};
use tauri::{AppHandle, Manager, Runtime, State};
use tauri_plugin_dialog::DialogExt;
use uuid::Uuid;

pub(crate) const READER_DTO_VERSION: u32 = 1;
const READER_STATE_SCHEMA_VERSION: u32 = 1;
const READER_DIRECTORY_NAME: &str = "reader";
const READER_STATE_FILE_NAME: &str = "reader-state-v1.json";
const READER_STATE_PENDING_FILE_NAME: &str = "reader-state-v1.json.pending";
const READER_STATE_PREVIOUS_FILE_NAME: &str = "reader-state-v1.json.previous";
const MAX_READER_STATE_BYTES: usize = 2 * 1024 * 1024;
const MAX_READER_BOOKS: usize = 500;
const MAX_READER_PATH_UTF16_UNITS: usize = 32_767;
const MAX_READER_TITLE_CHARS: usize = 240;
const MAX_TEXT_BYTES: usize = 32 * 1024 * 1024;
const MAX_PDF_BYTES: usize = 128 * 1024 * 1024;
const MAX_TEXT_PARAGRAPHS: usize = 250_000;
const MAX_TEXT_PAGES: usize = 50_000;
const MAX_PDF_PAGES: usize = 20_000;
const MAX_READER_CHAPTERS: usize = 20_000;
const MAX_READER_CHAPTER_TITLE_CHARS: usize = 240;
const MIN_READER_CHAPTER_HEADING_CHARS: u16 = 20;
const MAX_READER_CUSTOM_REGEX_CHARS: usize = 1_024;
const READER_TEXT_PAGE_TARGET_CHARS: usize = 1_800;
const MIN_TOC_HEADINGS_ON_PAGE: usize = 3;
const MAX_TOC_PARAGRAPH_SPAN: usize = 2;
const MIN_PDF_ZOOM_PERCENT: u16 = 50;
const MAX_PDF_ZOOM_PERCENT: u16 = 300;
const MAX_RECORDED_READER_DELTA_MILLIS: u64 = 5 * 60 * 1_000;
const MAX_JAVASCRIPT_DATE_MILLIS: i64 = 8_640_000_000_000_000;

static READER_STATE_MUTEX: Mutex<()> = Mutex::new(());

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum ReaderBookType {
    Txt,
    Pdf,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum ReaderBackground {
    White,
    Paper,
    Sepia,
    Green,
    Night,
    Custom,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum ReaderChapterDetectionMode {
    Smart,
    Custom,
    SmartAndCustom,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ReaderPreferences {
    pub(crate) background: ReaderBackground,
    pub(crate) custom_background_argb: i32,
    pub(crate) font_size_px: f64,
    pub(crate) line_height_multiplier: f64,
    pub(crate) paragraph_spacing_px: f64,
    pub(crate) pdf_zoom_percent: u16,
    pub(crate) chapter_detection_mode: ReaderChapterDetectionMode,
    pub(crate) custom_chapter_regex: String,
    pub(crate) chapter_heading_max_chars: u16,
}

impl Default for ReaderPreferences {
    fn default() -> Self {
        Self {
            background: ReaderBackground::Paper,
            custom_background_argb: 0xFFF4F0E6_u32 as i32,
            font_size_px: 19.0,
            line_height_multiplier: 1.6,
            paragraph_spacing_px: 10.0,
            pdf_zoom_percent: 100,
            chapter_detection_mode: ReaderChapterDetectionMode::SmartAndCustom,
            custom_chapter_regex: String::new(),
            chapter_heading_max_chars: 160,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StoredReaderBook {
    id: String,
    path: String,
    title: String,
    book_type: ReaderBookType,
    added_at: i64,
    last_opened_at: i64,
    text_paragraph_index: usize,
    text_page_index: usize,
    pdf_page_index: usize,
    reading_millis: u64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StoredReaderState {
    schema_version: u32,
    preferences: ReaderPreferences,
    books: Vec<StoredReaderBook>,
}

impl Default for StoredReaderState {
    fn default() -> Self {
        Self {
            schema_version: READER_STATE_SCHEMA_VERSION,
            preferences: ReaderPreferences::default(),
            books: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ReaderBookDto {
    dto_version: u32,
    id: String,
    title: String,
    book_type: ReaderBookType,
    added_at: i64,
    last_opened_at: i64,
    text_paragraph_index: usize,
    text_page_index: usize,
    pdf_page_index: usize,
    reading_millis: String,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ReaderLibraryDto {
    dto_version: u32,
    books: Vec<ReaderBookDto>,
    preferences: ReaderPreferences,
    total_reading_millis: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ReaderTextPageDto {
    text: String,
    first_paragraph_index: usize,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ReaderChapterDto {
    title: String,
    page_index: usize,
    paragraph_index: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ReaderTextLayout {
    pages: Vec<ReaderTextPageDto>,
    chapters: Vec<ReaderChapterDto>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
enum ReaderDocumentContentDto {
    Txt {
        pages: Vec<ReaderTextPageDto>,
        chapters: Vec<ReaderChapterDto>,
    },
    Pdf {
        asset_url: String,
    },
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ReaderDocumentDto {
    dto_version: u32,
    book: ReaderBookDto,
    preferences: ReaderPreferences,
    #[serde(flatten)]
    content: ReaderDocumentContentDto,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ReaderBookRequest {
    dto_version: u32,
    book_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ReaderProgressRequest {
    dto_version: u32,
    book_id: String,
    page_index: usize,
    #[serde(default)]
    paragraph_index: Option<usize>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ReaderPreferencesRequest {
    dto_version: u32,
    preferences: ReaderPreferences,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ReaderTimeRequest {
    dto_version: u32,
    book_id: String,
    delta_millis: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ReaderError {
    InvalidInput,
    UnsupportedType,
    TooLarge,
    TooManyBooks,
    NotFound,
    PathNotAllowed,
    SourceChanged,
    StateCorrupt,
    StateUnsupported,
    Storage,
}

impl ReaderError {
    fn dto(self) -> SecurityErrorDto {
        match self {
            Self::InvalidInput => SecurityErrorDto::invalid_input(),
            Self::UnsupportedType => SecurityErrorDto::new(
                "reader_file_type_unsupported",
                "Choose a TXT or PDF document.",
                true,
            ),
            Self::TooLarge => SecurityErrorDto::new(
                "reader_file_too_large",
                "The selected reader document exceeds its safety limit.",
                true,
            ),
            Self::TooManyBooks => SecurityErrorDto::new(
                "reader_library_limit_exceeded",
                "The local reader library reached its safety limit.",
                true,
            ),
            Self::NotFound => SecurityErrorDto::new(
                "reader_file_missing",
                "The reader document is no longer available.",
                true,
            ),
            Self::PathNotAllowed => SecurityErrorDto::path_not_allowed(),
            Self::SourceChanged => SecurityErrorDto::new(
                "reader_file_changed",
                "The reader document changed while it was being read.",
                true,
            ),
            Self::StateCorrupt => SecurityErrorDto::new(
                "reader_state_corrupt",
                "The private reader state is damaged and was preserved for recovery.",
                false,
            ),
            Self::StateUnsupported => SecurityErrorDto::new(
                "reader_state_unsupported",
                "The private reader state was created by a newer DeskCubby version.",
                false,
            ),
            Self::Storage => SecurityErrorDto::storage_unavailable(),
        }
    }
}

impl From<SecurityError> for ReaderError {
    fn from(error: SecurityError) -> Self {
        match error {
            SecurityError::InvalidInput => Self::InvalidInput,
            SecurityError::PathNotAllowed => Self::PathNotAllowed,
            SecurityError::NotFound => Self::NotFound,
            SecurityError::Storage | SecurityError::Crypto => Self::Storage,
            #[cfg(not(windows))]
            SecurityError::Unsupported => Self::Storage,
        }
    }
}

fn reader_lock() -> Result<MutexGuard<'static, ()>, ReaderError> {
    READER_STATE_MUTEX.lock().map_err(|_| ReaderError::Storage)
}

fn require_dto_version(version: u32) -> Result<(), ReaderError> {
    if version == READER_DTO_VERSION {
        Ok(())
    } else {
        Err(ReaderError::InvalidInput)
    }
}

#[tauri::command]
pub(crate) fn get_reader_library(state: State<'_, AppState>) -> CommandResult<ReaderLibraryDto> {
    let _guard = reader_lock().map_err(ReaderError::dto)?;
    let stored = load_reader_state(&state.private_dir).map_err(ReaderError::dto)?;
    Ok(library_dto(&stored))
}

#[tauri::command]
pub(crate) fn choose_reader_book<R: Runtime>(
    app: AppHandle<R>,
    state: State<'_, AppState>,
) -> CommandResult<Option<ReaderDocumentDto>> {
    let Some(selected) = app
        .dialog()
        .file()
        .add_filter("Reader documents", &["txt", "pdf"])
        .blocking_pick_file()
    else {
        return Ok(None);
    };
    let selected = selected
        .into_path()
        .map_err(|_| ReaderError::PathNotAllowed.dto())?;
    let _guard = reader_lock().map_err(ReaderError::dto)?;
    let mut stored = load_reader_state(&state.private_dir).map_err(ReaderError::dto)?;
    let (canonical, book_type, title) =
        validate_selected_reader_path(&selected).map_err(ReaderError::dto)?;
    let canonical_text = canonical
        .to_str()
        .filter(|value| value.encode_utf16().count() <= MAX_READER_PATH_UTF16_UNITS)
        .ok_or_else(|| ReaderError::PathNotAllowed.dto())?
        .to_owned();
    let now = chrono::Utc::now().timestamp_millis().max(0);
    let existing_index = stored.books.iter().position(|book| {
        if cfg!(windows) {
            book.path.eq_ignore_ascii_case(&canonical_text)
        } else {
            book.path == canonical_text
        }
    });
    if existing_index.is_none() && stored.books.len() >= MAX_READER_BOOKS {
        return Err(ReaderError::TooManyBooks.dto());
    }
    let mut book = existing_index
        .map(|index| stored.books[index].clone())
        .unwrap_or_else(|| StoredReaderBook {
            id: Uuid::new_v4().to_string(),
            path: canonical_text,
            title,
            book_type,
            added_at: now,
            last_opened_at: now,
            text_paragraph_index: 0,
            text_page_index: 0,
            pdf_page_index: 0,
            reading_millis: 0,
        });
    if book.book_type != book_type {
        return Err(ReaderError::UnsupportedType.dto());
    }
    let content = read_document_content(&book, &stored.preferences).map_err(ReaderError::dto)?;
    book.last_opened_at = now;
    if let Some(index) = existing_index {
        stored.books[index] = book.clone();
    } else {
        stored.books.push(book.clone());
    }
    sort_reader_books(&mut stored.books);
    write_reader_state(&state.private_dir, &stored).map_err(ReaderError::dto)?;
    Ok(Some(document_dto(book, stored.preferences, content)))
}

#[tauri::command]
pub(crate) fn open_reader_book(
    request: ReaderBookRequest,
    state: State<'_, AppState>,
) -> CommandResult<ReaderDocumentDto> {
    require_dto_version(request.dto_version).map_err(ReaderError::dto)?;
    validate_book_id(&request.book_id).map_err(ReaderError::dto)?;
    let _guard = reader_lock().map_err(ReaderError::dto)?;
    let mut stored = load_reader_state(&state.private_dir).map_err(ReaderError::dto)?;
    let index = stored
        .books
        .iter()
        .position(|book| book.id == request.book_id)
        .ok_or_else(|| ReaderError::NotFound.dto())?;
    let content = read_document_content(&stored.books[index], &stored.preferences)
        .map_err(ReaderError::dto)?;
    stored.books[index].last_opened_at = chrono::Utc::now().timestamp_millis().max(0);
    let book = stored.books[index].clone();
    sort_reader_books(&mut stored.books);
    write_reader_state(&state.private_dir, &stored).map_err(ReaderError::dto)?;
    Ok(document_dto(book, stored.preferences, content))
}

#[tauri::command]
pub(crate) fn save_reader_progress(
    request: ReaderProgressRequest,
    state: State<'_, AppState>,
) -> CommandResult<ReaderBookDto> {
    require_dto_version(request.dto_version).map_err(ReaderError::dto)?;
    validate_book_id(&request.book_id).map_err(ReaderError::dto)?;
    let _guard = reader_lock().map_err(ReaderError::dto)?;
    let mut stored = load_reader_state(&state.private_dir).map_err(ReaderError::dto)?;
    let book = stored
        .books
        .iter_mut()
        .find(|book| book.id == request.book_id)
        .ok_or_else(|| ReaderError::NotFound.dto())?;
    match book.book_type {
        ReaderBookType::Txt => {
            if request.page_index >= MAX_TEXT_PAGES {
                return Err(ReaderError::InvalidInput.dto());
            }
            let paragraph = request.paragraph_index.unwrap_or(0);
            if paragraph >= MAX_TEXT_PARAGRAPHS {
                return Err(ReaderError::InvalidInput.dto());
            }
            book.text_page_index = request.page_index;
            book.text_paragraph_index = paragraph;
        }
        ReaderBookType::Pdf => {
            if request.page_index >= MAX_PDF_PAGES || request.paragraph_index.is_some() {
                return Err(ReaderError::InvalidInput.dto());
            }
            book.pdf_page_index = request.page_index;
        }
    }
    let updated = book.clone();
    write_reader_state(&state.private_dir, &stored).map_err(ReaderError::dto)?;
    Ok(book_dto(&updated))
}

#[tauri::command]
pub(crate) fn save_reader_preferences(
    request: ReaderPreferencesRequest,
    state: State<'_, AppState>,
) -> CommandResult<ReaderLibraryDto> {
    require_dto_version(request.dto_version).map_err(ReaderError::dto)?;
    let preferences =
        normalize_reader_preferences(request.preferences).map_err(ReaderError::dto)?;
    let _guard = reader_lock().map_err(ReaderError::dto)?;
    let mut stored = load_reader_state(&state.private_dir).map_err(ReaderError::dto)?;
    stored.preferences = preferences;
    write_reader_state(&state.private_dir, &stored).map_err(ReaderError::dto)?;
    Ok(library_dto(&stored))
}

#[tauri::command]
pub(crate) fn remove_reader_book(
    request: ReaderBookRequest,
    state: State<'_, AppState>,
) -> CommandResult<ReaderLibraryDto> {
    require_dto_version(request.dto_version).map_err(ReaderError::dto)?;
    validate_book_id(&request.book_id).map_err(ReaderError::dto)?;
    let _guard = reader_lock().map_err(ReaderError::dto)?;
    let mut stored = load_reader_state(&state.private_dir).map_err(ReaderError::dto)?;
    let original_len = stored.books.len();
    stored.books.retain(|book| book.id != request.book_id);
    if stored.books.len() == original_len {
        return Err(ReaderError::NotFound.dto());
    }
    write_reader_state(&state.private_dir, &stored).map_err(ReaderError::dto)?;
    Ok(library_dto(&stored))
}

#[tauri::command]
pub(crate) fn record_reader_time(
    request: ReaderTimeRequest,
    state: State<'_, AppState>,
) -> CommandResult<ReaderBookDto> {
    require_dto_version(request.dto_version).map_err(ReaderError::dto)?;
    validate_book_id(&request.book_id).map_err(ReaderError::dto)?;
    if request.delta_millis == 0 || request.delta_millis > MAX_RECORDED_READER_DELTA_MILLIS {
        return Err(ReaderError::InvalidInput.dto());
    }
    let _guard = reader_lock().map_err(ReaderError::dto)?;
    let mut stored = load_reader_state(&state.private_dir).map_err(ReaderError::dto)?;
    let book = stored
        .books
        .iter_mut()
        .find(|book| book.id == request.book_id)
        .ok_or_else(|| ReaderError::NotFound.dto())?;
    book.reading_millis = book.reading_millis.saturating_add(request.delta_millis);
    let updated = book.clone();
    write_reader_state(&state.private_dir, &stored).map_err(ReaderError::dto)?;
    Ok(book_dto(&updated))
}

fn library_dto(state: &StoredReaderState) -> ReaderLibraryDto {
    ReaderLibraryDto {
        dto_version: READER_DTO_VERSION,
        books: state.books.iter().map(book_dto).collect(),
        preferences: state.preferences.clone(),
        total_reading_millis: state
            .books
            .iter()
            .fold(0_u64, |total, book| {
                total.saturating_add(book.reading_millis)
            })
            .to_string(),
    }
}

fn book_dto(book: &StoredReaderBook) -> ReaderBookDto {
    ReaderBookDto {
        dto_version: READER_DTO_VERSION,
        id: book.id.clone(),
        title: book.title.clone(),
        book_type: book.book_type,
        added_at: book.added_at,
        last_opened_at: book.last_opened_at,
        text_paragraph_index: book.text_paragraph_index,
        text_page_index: book.text_page_index,
        pdf_page_index: book.pdf_page_index,
        reading_millis: book.reading_millis.to_string(),
    }
}

fn document_dto(
    book: StoredReaderBook,
    preferences: ReaderPreferences,
    content: ReaderDocumentContentDto,
) -> ReaderDocumentDto {
    ReaderDocumentDto {
        dto_version: READER_DTO_VERSION,
        book: book_dto(&book),
        preferences,
        content,
    }
}

fn sort_reader_books(books: &mut [StoredReaderBook]) {
    books.sort_by(|left, right| {
        right
            .last_opened_at
            .cmp(&left.last_opened_at)
            .then_with(|| left.title.cmp(&right.title))
            .then_with(|| left.id.cmp(&right.id))
    });
}

fn validate_book_id(book_id: &str) -> Result<(), ReaderError> {
    let parsed = Uuid::parse_str(book_id).map_err(|_| ReaderError::InvalidInput)?;
    if parsed.to_string() == book_id.to_ascii_lowercase() {
        Ok(())
    } else {
        Err(ReaderError::InvalidInput)
    }
}

fn normalize_reader_preferences(
    mut value: ReaderPreferences,
) -> Result<ReaderPreferences, ReaderError> {
    if !value.font_size_px.is_finite()
        || !value.line_height_multiplier.is_finite()
        || !value.paragraph_spacing_px.is_finite()
    {
        return Err(ReaderError::InvalidInput);
    }
    value.font_size_px = value.font_size_px.clamp(12.0, 38.0);
    value.line_height_multiplier = value.line_height_multiplier.clamp(1.0, 2.4);
    value.paragraph_spacing_px = value.paragraph_spacing_px.clamp(0.0, 36.0);
    value.pdf_zoom_percent = value
        .pdf_zoom_percent
        .clamp(MIN_PDF_ZOOM_PERCENT, MAX_PDF_ZOOM_PERCENT);
    value.custom_background_argb = (value.custom_background_argb as u32 | 0xFF00_0000) as i32;
    value.custom_chapter_regex = truncate_chars(
        value.custom_chapter_regex.trim(),
        MAX_READER_CUSTOM_REGEX_CHARS,
    );
    value.chapter_heading_max_chars = value.chapter_heading_max_chars.clamp(
        MIN_READER_CHAPTER_HEADING_CHARS,
        MAX_READER_CHAPTER_TITLE_CHARS as u16,
    );
    if !value.custom_chapter_regex.is_empty() {
        Regex::new(&value.custom_chapter_regex).map_err(|_| ReaderError::InvalidInput)?;
    }
    Ok(value)
}

fn state_paths(private_dir: &Path) -> (PathBuf, PathBuf, PathBuf, PathBuf) {
    let directory = private_dir.join(READER_DIRECTORY_NAME);
    (
        directory.join(READER_STATE_FILE_NAME),
        directory.join(READER_STATE_PENDING_FILE_NAME),
        directory.join(READER_STATE_PREVIOUS_FILE_NAME),
        directory,
    )
}

fn read_state_candidate(path: &Path) -> Result<Option<StoredReaderState>, ReaderError> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => {
            if !metadata.is_file() || metadata.file_type().is_symlink() {
                return Err(ReaderError::StateCorrupt);
            }
            if metadata.len() == 0 || metadata.len() > MAX_READER_STATE_BYTES as u64 {
                return Err(ReaderError::StateCorrupt);
            }
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(_) => return Err(ReaderError::Storage),
    }
    let mut file = open_regular_file_no_reparse(path).map_err(ReaderError::from)?;
    let mut bytes = Vec::new();
    <File as Read>::by_ref(&mut file)
        .take((MAX_READER_STATE_BYTES + 1) as u64)
        .read_to_end(&mut bytes)
        .map_err(|_| ReaderError::Storage)?;
    if bytes.is_empty() || bytes.len() > MAX_READER_STATE_BYTES {
        return Err(ReaderError::StateCorrupt);
    }
    let state: StoredReaderState =
        serde_json::from_slice(&bytes).map_err(|_| ReaderError::StateCorrupt)?;
    validate_stored_state(state).map(Some)
}

fn validate_stored_state(mut state: StoredReaderState) -> Result<StoredReaderState, ReaderError> {
    if state.schema_version > READER_STATE_SCHEMA_VERSION {
        return Err(ReaderError::StateUnsupported);
    }
    if state.schema_version != READER_STATE_SCHEMA_VERSION || state.books.len() > MAX_READER_BOOKS {
        return Err(ReaderError::StateCorrupt);
    }
    state.preferences =
        normalize_reader_preferences(state.preferences).map_err(|_| ReaderError::StateCorrupt)?;
    let mut ids = HashSet::with_capacity(state.books.len());
    let mut paths = HashSet::with_capacity(state.books.len());
    for book in &state.books {
        validate_book_id(&book.id).map_err(|_| ReaderError::StateCorrupt)?;
        if !ids.insert(book.id.clone())
            || book.path.encode_utf16().count() > MAX_READER_PATH_UTF16_UNITS
            || !Path::new(&book.path).is_absolute()
            || book.title.chars().count() > MAX_READER_TITLE_CHARS
            || book.title.trim().is_empty()
            || !(0..=MAX_JAVASCRIPT_DATE_MILLIS).contains(&book.added_at)
            || !(0..=MAX_JAVASCRIPT_DATE_MILLIS).contains(&book.last_opened_at)
            || book.text_paragraph_index >= MAX_TEXT_PARAGRAPHS
            || book.text_page_index >= MAX_TEXT_PAGES
            || book.pdf_page_index >= MAX_PDF_PAGES
        {
            return Err(ReaderError::StateCorrupt);
        }
        let path_key = if cfg!(windows) {
            book.path.to_ascii_lowercase()
        } else {
            book.path.clone()
        };
        if !paths.insert(path_key) {
            return Err(ReaderError::StateCorrupt);
        }
        let extension = Path::new(&book.path)
            .extension()
            .and_then(|value| value.to_str())
            .unwrap_or_default();
        let valid_extension = match book.book_type {
            ReaderBookType::Txt => extension.eq_ignore_ascii_case("txt"),
            ReaderBookType::Pdf => extension.eq_ignore_ascii_case("pdf"),
        };
        if !valid_extension {
            return Err(ReaderError::StateCorrupt);
        }
    }
    sort_reader_books(&mut state.books);
    Ok(state)
}

fn load_reader_state(private_dir: &Path) -> Result<StoredReaderState, ReaderError> {
    let (target, pending, previous, _) = state_paths(private_dir);
    match read_state_candidate(&target) {
        Ok(Some(state)) => {
            let _ = fs::remove_file(pending);
            let _ = fs::remove_file(previous);
            Ok(state)
        }
        Err(error) => Err(error),
        Ok(None) => match read_state_candidate(&pending) {
            Ok(Some(state)) => {
                fs::rename(&pending, &target).map_err(|_| ReaderError::Storage)?;
                if read_state_candidate(&target)? != Some(state.clone()) {
                    return Err(ReaderError::StateCorrupt);
                }
                let _ = fs::remove_file(previous);
                Ok(state)
            }
            Err(error) => Err(error),
            Ok(None) => match read_state_candidate(&previous) {
                Ok(Some(state)) => {
                    fs::rename(&previous, &target).map_err(|_| ReaderError::Storage)?;
                    if read_state_candidate(&target)? != Some(state.clone()) {
                        return Err(ReaderError::StateCorrupt);
                    }
                    Ok(state)
                }
                Err(error) => Err(error),
                Ok(None) => Ok(StoredReaderState::default()),
            },
        },
    }
}

fn write_reader_state(private_dir: &Path, state: &StoredReaderState) -> Result<(), ReaderError> {
    let validated = validate_stored_state(state.clone())?;
    let bytes = serde_json::to_vec(&validated).map_err(|_| ReaderError::Storage)?;
    if bytes.is_empty() || bytes.len() > MAX_READER_STATE_BYTES {
        return Err(ReaderError::TooLarge);
    }
    let (target, pending, previous, directory) = state_paths(private_dir);
    fs::create_dir_all(&directory).map_err(|_| ReaderError::Storage)?;
    reject_reparse_point(&directory).map_err(ReaderError::from)?;
    let _ = fs::remove_file(&pending);
    let mut output = fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&pending)
        .map_err(|_| ReaderError::Storage)?;
    if output
        .write_all(&bytes)
        .and_then(|_| output.sync_all())
        .is_err()
    {
        drop(output);
        let _ = fs::remove_file(&pending);
        return Err(ReaderError::Storage);
    }
    drop(output);
    if read_state_candidate(&pending)? != Some(validated.clone()) {
        return Err(ReaderError::Storage);
    }
    let had_target = target.exists();
    if had_target {
        let _ = fs::remove_file(&previous);
        fs::rename(&target, &previous).map_err(|_| ReaderError::Storage)?;
    }
    if fs::rename(&pending, &target).is_err() {
        if had_target {
            let _ = fs::rename(&previous, &target);
        }
        return Err(ReaderError::Storage);
    }
    if read_state_candidate(&target)? != Some(validated) {
        let _ = fs::remove_file(&target);
        if had_target {
            let _ = fs::rename(&previous, &target);
        }
        return Err(ReaderError::Storage);
    }
    if had_target {
        let _ = fs::remove_file(previous);
    }
    Ok(())
}

fn validate_selected_reader_path(
    selected: &Path,
) -> Result<(PathBuf, ReaderBookType, String), ReaderError> {
    if !selected.is_absolute() {
        return Err(ReaderError::PathNotAllowed);
    }
    reject_reparse_point(selected).map_err(ReaderError::from)?;
    let canonical = fs::canonicalize(selected).map_err(|error| match error.kind() {
        std::io::ErrorKind::NotFound => ReaderError::NotFound,
        _ => ReaderError::Storage,
    })?;
    let leaf = canonical
        .file_name()
        .and_then(|value| value.to_str())
        .ok_or(ReaderError::PathNotAllowed)?;
    let extension = canonical
        .extension()
        .and_then(|value| value.to_str())
        .unwrap_or_default();
    let book_type = if extension.eq_ignore_ascii_case("txt") {
        validate_relative_file_name(leaf, &["txt"]).map_err(ReaderError::from)?;
        ReaderBookType::Txt
    } else if extension.eq_ignore_ascii_case("pdf") {
        validate_relative_file_name(leaf, &["pdf"]).map_err(ReaderError::from)?;
        ReaderBookType::Pdf
    } else {
        return Err(ReaderError::UnsupportedType);
    };
    let mut file = open_regular_file_no_reparse(&canonical).map_err(ReaderError::from)?;
    validate_reader_file(&mut file, book_type)?;
    let title = canonical
        .file_stem()
        .and_then(|value| value.to_str())
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(|value| truncate_chars(value, MAX_READER_TITLE_CHARS))
        .unwrap_or_else(|| "Untitled".to_owned());
    Ok((canonical, book_type, title))
}

fn open_stored_reader_file(book: &StoredReaderBook) -> Result<File, ReaderError> {
    let path = PathBuf::from(&book.path);
    if !path.is_absolute() {
        return Err(ReaderError::PathNotAllowed);
    }
    reject_reparse_point(&path).map_err(ReaderError::from)?;
    let canonical = fs::canonicalize(&path).map_err(|error| match error.kind() {
        std::io::ErrorKind::NotFound => ReaderError::NotFound,
        _ => ReaderError::Storage,
    })?;
    let same_path = if cfg!(windows) {
        canonical
            .to_string_lossy()
            .eq_ignore_ascii_case(path.to_string_lossy().as_ref())
    } else {
        canonical == path
    };
    if !same_path {
        return Err(ReaderError::PathNotAllowed);
    }
    let leaf = canonical
        .file_name()
        .and_then(|value| value.to_str())
        .ok_or(ReaderError::PathNotAllowed)?;
    match book.book_type {
        ReaderBookType::Txt => {
            validate_relative_file_name(leaf, &["txt"]).map_err(ReaderError::from)?;
        }
        ReaderBookType::Pdf => {
            validate_relative_file_name(leaf, &["pdf"]).map_err(ReaderError::from)?;
        }
    }
    open_regular_file_no_reparse(&canonical).map_err(ReaderError::from)
}

fn validate_reader_file(file: &mut File, book_type: ReaderBookType) -> Result<(), ReaderError> {
    let metadata = file.metadata().map_err(|_| ReaderError::Storage)?;
    let maximum = match book_type {
        ReaderBookType::Txt => MAX_TEXT_BYTES,
        ReaderBookType::Pdf => MAX_PDF_BYTES,
    };
    if metadata.len() > maximum as u64 || (book_type == ReaderBookType::Pdf && metadata.len() < 5) {
        return Err(ReaderError::TooLarge);
    }
    if book_type == ReaderBookType::Pdf {
        let mut prefix = [0_u8; 1_024];
        let count = file.read(&mut prefix).map_err(|_| ReaderError::Storage)?;
        file.seek(SeekFrom::Start(0))
            .map_err(|_| ReaderError::Storage)?;
        if !prefix[..count].windows(5).any(|value| value == b"%PDF-") {
            return Err(ReaderError::InvalidInput);
        }
    }
    Ok(())
}

fn read_document_content(
    book: &StoredReaderBook,
    preferences: &ReaderPreferences,
) -> Result<ReaderDocumentContentDto, ReaderError> {
    let mut file = open_stored_reader_file(book)?;
    validate_reader_file(&mut file, book.book_type)?;
    match book.book_type {
        ReaderBookType::Txt => {
            let bytes = read_bounded(&mut file, MAX_TEXT_BYTES)?;
            let decoded = decode_reader_text(&bytes);
            let paragraphs = normalized_reader_paragraphs(&decoded)?;
            let layout = paginate_reader_text(&paragraphs, preferences)?;
            Ok(ReaderDocumentContentDto::Txt {
                pages: layout.pages,
                chapters: layout.chapters,
            })
        }
        ReaderBookType::Pdf => Ok(ReaderDocumentContentDto::Pdf {
            asset_url: reader_url_for_book(&book.id).ok_or(ReaderError::InvalidInput)?,
        }),
    }
}

fn read_bounded(file: &mut File, maximum: usize) -> Result<Vec<u8>, ReaderError> {
    let before = file.metadata().map_err(|_| ReaderError::Storage)?.len();
    if before > maximum as u64 {
        return Err(ReaderError::TooLarge);
    }
    file.seek(SeekFrom::Start(0))
        .map_err(|_| ReaderError::Storage)?;
    let mut bytes = Vec::with_capacity(before as usize);
    <File as Read>::by_ref(file)
        .take((maximum + 1) as u64)
        .read_to_end(&mut bytes)
        .map_err(|_| ReaderError::Storage)?;
    if bytes.len() > maximum {
        return Err(ReaderError::TooLarge);
    }
    let after = file.metadata().map_err(|_| ReaderError::Storage)?.len();
    if before != after || bytes.len() as u64 != before {
        return Err(ReaderError::SourceChanged);
    }
    Ok(bytes)
}

fn decode_reader_text(bytes: &[u8]) -> String {
    if let Some(rest) = bytes.strip_prefix(&[0xEF, 0xBB, 0xBF]) {
        return String::from_utf8_lossy(rest).into_owned();
    }
    if let Some(rest) = bytes.strip_prefix(&[0xFF, 0xFE]) {
        return decode_utf16_bytes(rest, true);
    }
    if let Some(rest) = bytes.strip_prefix(&[0xFE, 0xFF]) {
        return decode_utf16_bytes(rest, false);
    }
    if let Ok(value) = std::str::from_utf8(bytes) {
        return value.to_owned();
    }
    let (decoded, _, _) = GB18030.decode(bytes);
    decoded.into_owned()
}

fn decode_utf16_bytes(bytes: &[u8], little_endian: bool) -> String {
    let units = bytes.chunks_exact(2).map(|pair| {
        if little_endian {
            u16::from_le_bytes([pair[0], pair[1]])
        } else {
            u16::from_be_bytes([pair[0], pair[1]])
        }
    });
    char::decode_utf16(units)
        .map(|value| value.unwrap_or(char::REPLACEMENT_CHARACTER))
        .collect()
}

fn normalized_reader_paragraphs(decoded: &str) -> Result<Vec<String>, ReaderError> {
    let normalized = decoded.replace("\r\n", "\n").replace('\r', "\n");
    let mut paragraphs = Vec::new();
    for line in normalized.lines() {
        let paragraph = line.trim_end();
        if paragraph.trim().is_empty() {
            continue;
        }
        if paragraphs.len() >= MAX_TEXT_PARAGRAPHS {
            return Err(ReaderError::TooLarge);
        }
        paragraphs.push(paragraph.to_owned());
    }
    if paragraphs.is_empty() {
        paragraphs.push(String::new());
    }
    Ok(paragraphs)
}

fn paginate_reader_text(
    paragraphs: &[String],
    preferences: &ReaderPreferences,
) -> Result<ReaderTextLayout, ReaderError> {
    let preferences = normalize_reader_preferences(preferences.clone())?;
    let custom_regex = if preferences.custom_chapter_regex.is_empty() {
        None
    } else {
        Some(Regex::new(&preferences.custom_chapter_regex).map_err(|_| ReaderError::InvalidInput)?)
    };
    let mut pages = Vec::new();
    let mut chapters = Vec::new();
    let mut buffer = String::with_capacity(READER_TEXT_PAGE_TARGET_CHARS + 128);
    let mut buffer_chars = 0_usize;
    let mut first_paragraph_index = 0_usize;

    let flush = |pages: &mut Vec<ReaderTextPageDto>,
                 buffer: &mut String,
                 buffer_chars: &mut usize,
                 first_index: usize|
     -> Result<(), ReaderError> {
        if buffer.is_empty() {
            return Ok(());
        }
        if pages.len() >= MAX_TEXT_PAGES {
            return Err(ReaderError::TooLarge);
        }
        pages.push(ReaderTextPageDto {
            text: std::mem::take(buffer),
            first_paragraph_index: first_index,
        });
        *buffer_chars = 0;
        Ok(())
    };

    for (paragraph_index, raw_paragraph) in paragraphs.iter().enumerate() {
        let paragraph = raw_paragraph.trim_end();
        let chapter_title = normalize_reader_chapter_candidate(paragraph);
        if is_reader_chapter_heading(&chapter_title, &preferences, custom_regex.as_ref()) {
            flush(
                &mut pages,
                &mut buffer,
                &mut buffer_chars,
                first_paragraph_index,
            )?;
            if chapters.len() < MAX_READER_CHAPTERS {
                chapters.push(ReaderChapterDto {
                    title: truncate_chars(&chapter_title, MAX_READER_CHAPTER_TITLE_CHARS),
                    page_index: pages.len(),
                    paragraph_index,
                });
            }
        }

        let mut remaining = paragraph;
        loop {
            if buffer.is_empty() {
                first_paragraph_index = paragraph_index;
            }
            let separator_chars = usize::from(!buffer.is_empty()) * 2;
            let available =
                READER_TEXT_PAGE_TARGET_CHARS.saturating_sub(buffer_chars + separator_chars);
            let remaining_chars = remaining.chars().count();
            if available == 0 || (remaining_chars > available && !buffer.is_empty()) {
                flush(
                    &mut pages,
                    &mut buffer,
                    &mut buffer_chars,
                    first_paragraph_index,
                )?;
                continue;
            }
            if !buffer.is_empty() {
                buffer.push_str("\n\n");
                buffer_chars += 2;
            }
            if remaining_chars <= READER_TEXT_PAGE_TARGET_CHARS {
                buffer.push_str(remaining);
                buffer_chars += remaining_chars;
                remaining = "";
            } else {
                let split_byte = byte_index_at_char(remaining, READER_TEXT_PAGE_TARGET_CHARS);
                let (chunk, rest) = remaining.split_at(split_byte);
                buffer.push_str(chunk);
                buffer_chars += chunk.chars().count();
                remaining = rest.trim_start();
                flush(
                    &mut pages,
                    &mut buffer,
                    &mut buffer_chars,
                    first_paragraph_index,
                )?;
            }
            if remaining.is_empty() {
                break;
            }
        }
    }
    flush(
        &mut pages,
        &mut buffer,
        &mut buffer_chars,
        first_paragraph_index,
    )?;
    if pages.is_empty() {
        pages.push(ReaderTextPageDto {
            text: String::new(),
            first_paragraph_index: 0,
        });
    }
    Ok(ReaderTextLayout {
        pages,
        chapters: collapse_reader_chapter_duplicates(chapters),
    })
}

fn byte_index_at_char(value: &str, requested: usize) -> usize {
    value
        .char_indices()
        .nth(requested)
        .map(|(index, _)| index)
        .unwrap_or(value.len())
}

fn normalize_reader_chapter_candidate(raw: &str) -> String {
    let mut result = String::with_capacity(raw.len());
    let mut pending_space = false;
    for value in raw.chars().filter(|value| {
        !matches!(
            value,
            '\u{FEFF}' | '\u{200B}' | '\u{200C}' | '\u{200D}' | '\u{2060}'
        )
    }) {
        if matches!(value, '\t' | '\u{00A0}' | '\u{3000}' | ' ') {
            pending_space = !result.is_empty();
        } else {
            if pending_space {
                result.push(' ');
                pending_space = false;
            }
            result.push(value);
        }
    }
    result.trim().to_owned()
}

fn smart_reader_chapter_regexes() -> &'static Vec<Regex> {
    static REGEXES: OnceLock<Vec<Regex>> = OnceLock::new();
    REGEXES.get_or_init(|| {
        [
            r"^(?:正文\s*)?[【\[〈《（(]?[☆★◎◇◆•·\s]*第\s*(?:[0-9０-９零〇○一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟]\s*)+[章节卷回部篇集幕]\s*[】\]〉》）)]?(?:\s+|[:：、.．_\-—]?)[^\n]*$",
            r"(?i)^(?:chapter|part|book|section|episode)\s*(?:[0-9０-９]+|[ivxlcdm]+|[a-z]+(?:[ -][a-z]+){0,5})(?:\s*[:：.\-—]?\s*.*)?$",
            r"^(?:[0-9０-９]{1,5}(?:[.．、]|\s+-\s+)|[一二三四五六七八九十百千万]+、)\s*\S.*$",
            r"(?i)^(?:[【\[])?(?:序章|序言|前言|楔子|引子|终章|尾声|后记|番外(?:篇)?|上卷|中卷|下卷|prologue|epilogue|preface|introduction|afterword)(?:[】\]])?(?:\s*[:：.、\-]?\s*.*)?$",
            r"^(?:卷|部|篇|集)\s*(?:[0-9０-９零〇○一二三四五六七八九十百千万两]\s*)+(?:[:：.、\-]?\s*.*)?$",
            r"^#{1,6}\s+\S.*$",
            r"(?i)^[【\[](?:第(?:[0-9０-９零〇○一二三四五六七八九十百千万两]\s*)+[章节卷回部篇集幕]|chapter\s+[^】\]]+)[】\]](?:\s*.*)?$",
        ]
        .into_iter()
        .map(|pattern| Regex::new(pattern).expect("static reader chapter regex"))
        .collect()
    })
}

fn reader_toc_entry_regex() -> &'static Regex {
    static REGEX: OnceLock<Regex> = OnceLock::new();
    REGEX.get_or_init(|| {
        Regex::new(r"(?i)(?:\.{3,}|…{2,}|·{3,}|_{3,})\s*(?:[0-9０-９]+|[ivxlcdm]+)\s*$")
            .expect("static reader toc regex")
    })
}

fn is_reader_chapter_heading(
    value: &str,
    preferences: &ReaderPreferences,
    custom_regex: Option<&Regex>,
) -> bool {
    if value.is_empty()
        || value.chars().count() > preferences.chapter_heading_max_chars as usize
        || reader_toc_entry_regex().is_match(value)
    {
        return false;
    }
    let smart_enabled = preferences.chapter_detection_mode != ReaderChapterDetectionMode::Custom;
    let custom_enabled = preferences.chapter_detection_mode != ReaderChapterDetectionMode::Smart;
    let smart_match = smart_enabled
        && smart_reader_chapter_regexes()
            .iter()
            .any(|regex| regex.is_match(value));
    let custom_match = custom_enabled
        && custom_regex
            .and_then(|regex| regex.find(value))
            .is_some_and(|found| found.start() == 0 && found.end() == value.len());
    smart_match || custom_match
}

fn collapse_reader_chapter_duplicates(chapters: Vec<ReaderChapterDto>) -> Vec<ReaderChapterDto> {
    if chapters.len() < 2 {
        return chapters;
    }
    let mut page_counts: HashMap<usize, usize> = HashMap::new();
    for chapter in &chapters {
        *page_counts.entry(chapter.page_index).or_default() += 1;
    }
    let dense_pages: HashSet<usize> = page_counts
        .into_iter()
        .filter_map(|(page, count)| (count >= MIN_TOC_HEADINGS_ON_PAGE).then_some(page))
        .collect();
    let mut likely_toc = HashSet::new();
    for window in chapters.windows(MIN_TOC_HEADINGS_ON_PAGE) {
        if window[window.len() - 1]
            .paragraph_index
            .saturating_sub(window[0].paragraph_index)
            <= MAX_TOC_PARAGRAPH_SPAN
        {
            likely_toc.extend(window.iter().map(chapter_identity));
        }
    }
    let mut group_indices = HashMap::<String, usize>::new();
    let mut groups: Vec<Vec<ReaderChapterDto>> = Vec::new();
    for chapter in chapters {
        let key = reader_chapter_key(&chapter.title);
        let index = *group_indices.entry(key).or_insert_with(|| {
            groups.push(Vec::new());
            groups.len() - 1
        });
        groups[index].push(chapter);
    }
    let mut collapsed = groups
        .into_iter()
        .filter_map(|matches| {
            let first = matches.first()?.clone();
            if dense_pages.contains(&first.page_index)
                || likely_toc.contains(&chapter_identity(&first))
            {
                Some(
                    matches
                        .iter()
                        .find(|candidate| {
                            !dense_pages.contains(&candidate.page_index)
                                && !likely_toc.contains(&chapter_identity(candidate))
                        })
                        .cloned()
                        .unwrap_or(first),
                )
            } else {
                Some(first)
            }
        })
        .collect::<Vec<_>>();
    collapsed.sort_by_key(|chapter| (chapter.page_index, chapter.paragraph_index));
    collapsed
}

fn chapter_identity(chapter: &ReaderChapterDto) -> (usize, usize) {
    (chapter.page_index, chapter.paragraph_index)
}

fn reader_chapter_key(title: &str) -> String {
    static TRAILING_PAGE: OnceLock<Regex> = OnceLock::new();
    static DECORATION: OnceLock<Regex> = OnceLock::new();
    static CHINESE: OnceLock<Regex> = OnceLock::new();
    let trailing = TRAILING_PAGE.get_or_init(|| {
        Regex::new(r"(?i)(?:\s+|[.．…·_]{2,})(?:[0-9０-９]+|[ivxlcdm]+)\s*$")
            .expect("static trailing reader page regex")
    });
    let decoration = DECORATION.get_or_init(|| {
        Regex::new(r"[\s:：、.．_\-—【】\[\]〈〉《》（）()☆★◎◇◆•·]+")
            .expect("static reader decoration regex")
    });
    let chinese = CHINESE.get_or_init(|| {
        Regex::new(
            r"^(正文)?\s*第\s*([0-9０-９零〇○一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟]+)\s*([章节卷回部篇集幕])(.*)$",
        )
        .expect("static Chinese reader key regex")
    });
    let without_page = trailing.replace(&title.to_lowercase(), "").into_owned();
    let normalized_number = if let Some(captures) = chinese.captures(&without_page) {
        format!(
            "{} 第{}{}{}",
            captures.get(1).map_or("", |value| value.as_str()),
            reader_chapter_number_key(captures.get(2).map_or("", |value| value.as_str())),
            captures.get(3).map_or("", |value| value.as_str()),
            captures.get(4).map_or("", |value| value.as_str()),
        )
    } else {
        without_page
    };
    decoration
        .replace_all(&normalized_number, "")
        .trim()
        .to_owned()
}

fn reader_chapter_number_key(raw: &str) -> String {
    let ascii_digits = raw
        .chars()
        .map(|value| match value {
            '０'..='９' => {
                char::from_u32('0' as u32 + value as u32 - '０' as u32).unwrap_or(value)
            }
            _ => value,
        })
        .collect::<String>();
    if let Ok(value) = ascii_digits.parse::<u64>() {
        return value.to_string();
    }
    let digit = |value| match value {
        '零' | '〇' | '○' => Some(0_u64),
        '一' | '壹' => Some(1),
        '二' | '贰' | '两' => Some(2),
        '三' | '叁' => Some(3),
        '四' | '肆' => Some(4),
        '五' | '伍' => Some(5),
        '六' | '陆' => Some(6),
        '七' | '柒' => Some(7),
        '八' | '捌' => Some(8),
        '九' | '玖' => Some(9),
        _ => None,
    };
    let unit = |value| match value {
        '十' | '拾' => Some(10_u64),
        '百' | '佰' => Some(100),
        '千' | '仟' => Some(1_000),
        _ => None,
    };
    let mut total = 0_u64;
    let mut section = 0_u64;
    let mut number = 0_u64;
    for value in raw.chars() {
        if let Some(next) = digit(value) {
            number = next;
        } else if let Some(next) = unit(value) {
            section = section.saturating_add(number.max(1).saturating_mul(next));
            number = 0;
        } else if value == '万' {
            total = total.saturating_add((section + number).max(1).saturating_mul(10_000));
            section = 0;
            number = 0;
        } else {
            return raw.to_owned();
        }
    }
    total
        .saturating_add(section)
        .saturating_add(number)
        .to_string()
}

fn truncate_chars(value: &str, maximum: usize) -> String {
    value.chars().take(maximum).collect()
}

pub(crate) fn reader_url_for_book(book_id: &str) -> Option<String> {
    validate_book_id(book_id).ok()?;
    Some(format!("http://reader.localhost/{book_id}.pdf"))
}

fn decode_protocol_book_id(path: &str) -> Option<String> {
    let leaf = path.strip_prefix('/')?.strip_suffix(".pdf")?;
    if leaf.contains('/') || leaf.contains('%') {
        return None;
    }
    validate_book_id(leaf).ok()?;
    Some(leaf.to_owned())
}

/// Read-only protocol endpoint used by the WebView2 built-in PDF viewer. The URL carries only a
/// UUID. Each request resolves that UUID through private reader state and revalidates the exact
/// explicitly selected regular file before serving bounded bytes.
pub(crate) fn handle_protocol<R: Runtime>(
    context: tauri::UriSchemeContext<'_, R>,
    request: Request<Vec<u8>>,
) -> Response<Vec<u8>> {
    if context.webview_label() != "main"
        || (request.method() != Method::GET && request.method() != Method::HEAD)
        || request.uri().query().is_some()
        || !matches!(request.uri().host(), Some("reader.localhost" | "localhost"))
    {
        return reader_empty_response(StatusCode::NOT_FOUND);
    }
    let Some(book_id) = decode_protocol_book_id(request.uri().path()) else {
        return reader_empty_response(StatusCode::NOT_FOUND);
    };
    let Some(app_state) = context.app_handle().try_state::<AppState>() else {
        return reader_empty_response(StatusCode::SERVICE_UNAVAILABLE);
    };
    let book = {
        let Ok(_guard) = reader_lock() else {
            return reader_empty_response(StatusCode::SERVICE_UNAVAILABLE);
        };
        let Ok(state) = load_reader_state(&app_state.private_dir) else {
            return reader_empty_response(StatusCode::SERVICE_UNAVAILABLE);
        };
        let Some(book) = state
            .books
            .into_iter()
            .find(|book| book.id == book_id && book.book_type == ReaderBookType::Pdf)
        else {
            return reader_empty_response(StatusCode::NOT_FOUND);
        };
        book
    };
    serve_reader_pdf(&book, &request)
}

fn serve_reader_pdf(book: &StoredReaderBook, request: &Request<Vec<u8>>) -> Response<Vec<u8>> {
    let Ok(mut file) = open_stored_reader_file(book) else {
        return reader_empty_response(StatusCode::NOT_FOUND);
    };
    if validate_reader_file(&mut file, ReaderBookType::Pdf).is_err() {
        return reader_empty_response(StatusCode::UNSUPPORTED_MEDIA_TYPE);
    }
    let Ok(metadata) = file.metadata() else {
        return reader_empty_response(StatusCode::NOT_FOUND);
    };
    let length = metadata.len();
    if length == 0 || length > MAX_PDF_BYTES as u64 {
        return reader_empty_response(StatusCode::PAYLOAD_TOO_LARGE);
    }
    let range = match request.headers().get(header::RANGE) {
        Some(value) => match value
            .to_str()
            .ok()
            .and_then(|value| parse_byte_range(value, length))
        {
            Some(range) => Some(range),
            None => return reader_range_error(length),
        },
        None => None,
    };
    let (start, end, status) = range
        .map(|(start, end)| (start, end, StatusCode::PARTIAL_CONTENT))
        .unwrap_or((0, length - 1, StatusCode::OK));
    let response_length = end - start + 1;
    if response_length > MAX_PDF_BYTES as u64 {
        return reader_empty_response(StatusCode::PAYLOAD_TOO_LARGE);
    }
    let body = if request.method() == Method::HEAD {
        Vec::new()
    } else {
        if file.seek(SeekFrom::Start(start)).is_err() {
            return reader_empty_response(StatusCode::NOT_FOUND);
        }
        let mut body = Vec::with_capacity(response_length as usize);
        if file.take(response_length).read_to_end(&mut body).is_err()
            || body.len() as u64 != response_length
        {
            return reader_empty_response(StatusCode::CONFLICT);
        }
        body
    };
    let mut builder = reader_response_builder(status)
        .header(header::CONTENT_TYPE, "application/pdf")
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::CONTENT_LENGTH, response_length.to_string())
        .header(header::CONTENT_DISPOSITION, "inline");
    if status == StatusCode::PARTIAL_CONTENT {
        builder = builder.header(
            header::CONTENT_RANGE,
            format!("bytes {start}-{end}/{length}"),
        );
    }
    builder
        .body(body)
        .unwrap_or_else(|_| reader_empty_response(StatusCode::INTERNAL_SERVER_ERROR))
}

fn parse_byte_range(value: &str, length: u64) -> Option<(u64, u64)> {
    let raw = value.strip_prefix("bytes=")?;
    if raw.contains(',') {
        return None;
    }
    let (start_raw, end_raw) = raw.split_once('-')?;
    if start_raw.is_empty() {
        let suffix = end_raw.parse::<u64>().ok()?;
        if suffix == 0 {
            return None;
        }
        let start = length.saturating_sub(suffix.min(length));
        return Some((start, length - 1));
    }
    let start = start_raw.parse::<u64>().ok()?;
    if start >= length {
        return None;
    }
    let end = if end_raw.is_empty() {
        length - 1
    } else {
        end_raw.parse::<u64>().ok()?.min(length - 1)
    };
    (start <= end).then_some((start, end))
}

fn reader_response_builder(status: StatusCode) -> tauri::http::response::Builder {
    Response::builder()
        .status(status)
        .header(header::CACHE_CONTROL, "private, no-cache")
        .header("X-Content-Type-Options", "nosniff")
        .header("Cross-Origin-Resource-Policy", "cross-origin")
}

fn reader_empty_response(status: StatusCode) -> Response<Vec<u8>> {
    reader_response_builder(status)
        .header(header::CONTENT_LENGTH, "0")
        .body(Vec::new())
        .unwrap_or_else(|_| Response::new(Vec::new()))
}

fn reader_range_error(length: u64) -> Response<Vec<u8>> {
    reader_response_builder(StatusCode::RANGE_NOT_SATISFIABLE)
        .header(header::CONTENT_RANGE, format!("bytes */{length}"))
        .header(header::CONTENT_LENGTH, "0")
        .body(Vec::new())
        .unwrap_or_else(|_| Response::new(Vec::new()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn preferences() -> ReaderPreferences {
        ReaderPreferences::default()
    }

    #[test]
    fn decodes_utf_boms_and_gb18030() {
        assert_eq!(decode_reader_text(b"\xEF\xBB\xBFhello"), "hello");
        assert_eq!(
            decode_reader_text(&[0xFF, 0xFE, 0x2D, 0x4E, 0x87, 0x65]),
            "中文"
        );
        let (encoded, _, _) = GB18030.encode("中文小说");
        assert_eq!(decode_reader_text(&encoded), "中文小说");
    }

    #[test]
    fn detects_extended_headings_and_rejects_toc_dot_leaders() {
        let expected = [
            "第一章 初见",
            "第 二 十 章　归来",
            "【第三卷】远方",
            "Chapter XVIII: Return",
            "BOOK IV",
            "Scene 12",
            "序章",
            "Epilogue - Home",
            "卷三 风起",
            "12. A numbered title",
            "## Markdown heading",
        ];
        let mut custom = preferences();
        custom.custom_chapter_regex = r"Scene\s+\d+".to_owned();
        for heading in expected {
            let value = normalize_reader_chapter_candidate(heading);
            let regex = Regex::new(&custom.custom_chapter_regex).unwrap();
            assert!(
                is_reader_chapter_heading(&value, &custom, Some(&regex)),
                "missing heading: {heading}"
            );
        }
        assert!(!is_reader_chapter_heading(
            "第四章 归来……52",
            &custom,
            Some(&Regex::new(&custom.custom_chapter_regex).unwrap())
        ));
        assert!(!is_reader_chapter_heading(
            "This is a normal paragraph and must not enter the table of contents.",
            &custom,
            None
        ));
    }

    #[test]
    fn pagination_starts_chapters_on_stable_pages_and_prefers_body_duplicates() {
        let paragraphs = vec![
            "第一章 初见".to_owned(),
            "第二章 重逢".to_owned(),
            "第三章 远行".to_owned(),
            "一些目录后的说明".to_owned(),
            "第一章 初见".to_owned(),
            "正文内容".repeat(1_000),
            "第二章 重逢".to_owned(),
            "另一段正文".to_owned(),
            "第三章 远行".to_owned(),
            "结尾".to_owned(),
        ];
        let layout = paginate_reader_text(&paragraphs, &preferences()).unwrap();
        assert!(layout.pages.len() >= 4);
        assert_eq!(
            layout
                .chapters
                .iter()
                .map(|chapter| chapter.title.as_str())
                .collect::<Vec<_>>(),
            vec!["第一章 初见", "第二章 重逢", "第三章 远行"]
        );
        assert!(
            layout
                .chapters
                .iter()
                .all(|chapter| chapter.paragraph_index >= 4)
        );
    }

    #[test]
    fn private_state_round_trips_and_preserves_committed_file_on_corruption() {
        let root = tempdir().unwrap();
        let book_path = root.path().join("book.txt");
        fs::write(&book_path, "第一章\n正文").unwrap();
        let state = StoredReaderState {
            books: vec![StoredReaderBook {
                id: Uuid::new_v4().to_string(),
                path: fs::canonicalize(book_path)
                    .unwrap()
                    .to_string_lossy()
                    .into_owned(),
                title: "book".to_owned(),
                book_type: ReaderBookType::Txt,
                added_at: 1,
                last_opened_at: 2,
                text_paragraph_index: 0,
                text_page_index: 0,
                pdf_page_index: 0,
                reading_millis: 30_000,
            }],
            ..StoredReaderState::default()
        };
        write_reader_state(root.path(), &state).unwrap();
        assert_eq!(load_reader_state(root.path()).unwrap(), state);
        let (target, _, _, _) = state_paths(root.path());
        fs::write(&target, b"{damaged").unwrap();
        assert_eq!(
            load_reader_state(root.path()),
            Err(ReaderError::StateCorrupt)
        );
        assert_eq!(fs::read(&target).unwrap(), b"{damaged");
    }

    #[test]
    fn reader_urls_are_opaque_and_ranges_are_strict() {
        let id = Uuid::new_v4().to_string();
        let url = reader_url_for_book(&id).unwrap();
        assert_eq!(url, format!("http://reader.localhost/{id}.pdf"));
        assert_eq!(decode_protocol_book_id(&format!("/{id}.pdf")), Some(id));
        assert!(decode_protocol_book_id("/../private.pdf").is_none());
        assert_eq!(parse_byte_range("bytes=0-99", 1_000), Some((0, 99)));
        assert_eq!(parse_byte_range("bytes=-50", 1_000), Some((950, 999)));
        assert_eq!(parse_byte_range("bytes=950-", 1_000), Some((950, 999)));
        assert_eq!(parse_byte_range("bytes=1000-", 1_000), None);
        assert_eq!(parse_byte_range("bytes=0-1,4-5", 1_000), None);
    }

    #[test]
    fn preferences_are_bounded_and_custom_regex_is_validated() {
        let mut value = preferences();
        value.font_size_px = 500.0;
        value.line_height_multiplier = -2.0;
        value.paragraph_spacing_px = 100.0;
        value.pdf_zoom_percent = 900;
        value.custom_background_argb = 0x0012_3456;
        let normalized = normalize_reader_preferences(value).unwrap();
        assert_eq!(normalized.font_size_px, 38.0);
        assert_eq!(normalized.line_height_multiplier, 1.0);
        assert_eq!(normalized.paragraph_spacing_px, 36.0);
        assert_eq!(normalized.pdf_zoom_percent, 300);
        assert_eq!(normalized.custom_background_argb as u32, 0xFF12_3456);

        let mut invalid = preferences();
        invalid.custom_chapter_regex = "[unfinished".to_owned();
        assert_eq!(
            normalize_reader_preferences(invalid),
            Err(ReaderError::InvalidInput)
        );
    }
}
