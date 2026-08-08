use std::collections::{HashMap, HashSet};

use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64_STANDARD};
use chrono::NaiveDate;
use chrono_tz::Tz;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use sha2::{Digest, Sha256};
use thiserror::Error;

use crate::{
    db::{DataError, Database, now_millis},
    games::{GameBackupState, GameBackupStatistic},
    models::{
        AiModelConfig, BackupPreview, CoreSnapshot, DailyEventTemplate, DateRecord, HomeGreeting,
        ImportReceipt, ManagedSettings, MealPhotoFilter, PoetryCategory, RssSubscription,
        SavedPoem, Thought, ThoughtCategory,
    },
};
use uuid::Uuid;

pub const FORMAT_VERSION: i32 = 28;
pub const MAX_JSON_BYTES: usize = 64 * 1024 * 1024;

// Recovery points currently encode the encrypted compatibility shadow as a
// JSON byte array. In the worst case that representation is materially larger
// than the 64 MiB Android source document, so this separate bounded envelope
// must accommodate a valid maximum-size v28 shadow.
pub(crate) const MAX_RECOVERY_POINT_BYTES: usize = 320 * 1024 * 1024;
const MAX_RECOVERY_READER_STATE_BYTES: usize = 2 * 1024 * 1024;
const MAX_THOUGHTS: usize = 50_000;
const MAX_FAVORITES: usize = 20_000;
const MAX_DATE_RECORDS: usize = 50_000;
const MAX_CATEGORIES: usize = 10_000;
const MAX_POETRY_CATEGORIES: usize = 10_000;
const MAX_POEMS: usize = 50_000;
const MAX_THOUGHT_CHARS: usize = 1_000_000;
const MAX_URL_CHARS: usize = 8_192;
const MAX_TITLE_CHARS: usize = 4_096;
const MAX_SETTING_STRING_CHARS: usize = 1_000_000;
const MAX_DATE_NAME_CHARS: usize = 256;
const MAX_DATE_ICON_CHARS: usize = 64;
const MAX_CATEGORY_NAME_CHARS: usize = 40;
const MAX_POETRY_CATEGORY_NAME_CHARS: usize = 100;
const MAX_POEM_CONTENT_CHARS: usize = 100_000;
const MAX_POEM_SOURCE_CHARS: usize = 4_096;
const MAX_CLOUD_SYNC_CONFIGS: usize = 20;
const MAX_DESKTOP_WIDGET_CONFIGS: usize = 50;
const MAX_VAULT_ITEMS: usize = 50_000;
const MAX_VAULT_CIPHER_CHARS: usize = 2 * 1024 * 1024;
const MAX_VAULT_IV_CHARS: usize = 128;
const MAX_VAULT_SALT_CHARS: usize = 2_048;
const MAX_VAULT_GENERATION_CHARS: usize = 64;
const MAX_GAME_STATES: usize = 16;
const MAX_GAME_STATISTICS: usize = 64;
const MAX_GAME_ID_CHARS: usize = 64;
const MAX_GAME_SAVE_CHARS: usize = 16 * 1024 * 1024;
const MAX_READER_PROGRESS_RECORDS: usize = 500;
const MAX_READER_TEXT_PAGES: i32 = 50_000;
const MAX_READER_TEXT_PARAGRAPHS: i32 = 250_000;
const MAX_READER_PDF_PAGES: i32 = 20_000;
const MAX_USAGE_DEVICES: usize = 64;
const MAX_USAGE_DEVICE_JSON_BYTES: usize = 10 * 1024 * 1024 + 64 * 1024;
const MAX_STATISTICS_DAYS: usize = 36_600;
const MAX_APPS_PER_DAY: usize = 4_096;
const MAX_PACKAGE_NAME_CHARS: usize = 255;
const MAX_ZONE_ID_CHARS: usize = 128;
const MAX_FOREGROUND_MILLIS_PER_APP_DAY: i64 = 26 * 60 * 60 * 1_000;

/// Credential-free cloud-sync metadata shared with Android v28 backups.
///
/// Passwords, S3 access keys, secret keys and session tokens deliberately do
/// not exist on this DTO. They are device-local secrets and must be persisted
/// separately from the compatibility shadow and JSON backup.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CloudSyncConfig {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub service_type: CloudSyncServiceType,
    pub endpoint_url: String,
    pub remote_path: String,
    pub user_agent: String,
    pub web_dav_username: String,
    pub s3_bucket: String,
    pub s3_region: String,
    pub s3_path_style: bool,
    pub allow_insecure_http: bool,
    pub selected_contents: Vec<CloudSyncContent>,
    pub direction: CloudSyncDirection,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum CloudSyncServiceType {
    #[serde(rename = "WEBDAV")]
    WebDav,
    #[serde(rename = "S3_COMPATIBLE")]
    S3Compatible,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum CloudSyncContent {
    #[serde(rename = "DIARIES")]
    Diaries,
    #[serde(rename = "MEDIA")]
    Media,
    #[serde(rename = "JSON_BACKUP")]
    JsonBackup,
    #[serde(rename = "USAGE_STATISTICS")]
    UsageStatistics,
    #[serde(rename = "READING_PROGRESS")]
    ReadingProgress,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub(crate) enum ReaderProgressBookType {
    #[serde(rename = "TXT")]
    Txt,
    #[serde(rename = "PDF")]
    Pdf,
}

/// URI-free Android v28 reader-progress row. Book titles, paths, content URIs,
/// cover metadata and document bytes deliberately do not exist on this DTO.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ReaderProgressRecord {
    pub(crate) fingerprint: String,
    #[serde(rename = "type")]
    pub(crate) book_type: ReaderProgressBookType,
    pub(crate) text_page_index: i32,
    pub(crate) text_paragraph_index: i32,
    pub(crate) pdf_page_index: i32,
    pub(crate) total_pages: i32,
    pub(crate) updated_at: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum CloudSyncDirection {
    #[serde(rename = "UPLOAD_ONLY")]
    UploadOnly,
    #[serde(rename = "TWO_WAY")]
    TwoWay,
}

#[derive(Debug, Error)]
pub enum BackupError {
    #[error("INVALID_BACKUP: {0}")]
    Invalid(String),
    #[error("BACKUP_JSON_ERROR")]
    Json(#[source] serde_json::Error),
    #[error("BACKUP_DATABASE_ERROR")]
    Database(#[source] DataError),
    #[error("RECOVERY_POINT_TOO_LARGE")]
    RecoveryPointTooLarge,
}

impl From<serde_json::Error> for BackupError {
    fn from(value: serde_json::Error) -> Self {
        Self::Json(value)
    }
}

impl From<DataError> for BackupError {
    fn from(value: DataError) -> Self {
        Self::Database(value)
    }
}

#[derive(Debug, Clone)]
pub struct ValidatedBackup {
    pub format_version: i32,
    pub exported_at: i64,
    pub settings: ManagedSettings,
    #[allow(dead_code)]
    pub cloud_sync_configs: Vec<CloudSyncConfig>,
    pub thoughts: Vec<Thought>,
    pub categories: Vec<ThoughtCategory>,
    pub favorite_count: usize,
    pub date_records: Vec<DateRecord>,
    pub poetry_categories: Vec<PoetryCategory>,
    pub poems: Vec<SavedPoem>,
    pub game_states: Vec<GameBackupState>,
    pub game_statistics: Vec<GameBackupStatistic>,
    pub merge_game_states: bool,
    pub merge_game_statistics: bool,
    pub usage_devices: Vec<Value>,
    pub merge_usage_devices: bool,
    pub(crate) reader_progress: Vec<ReaderProgressRecord>,
    pub source_sha256: String,
    root: Value,
}

/// A validated v28 import whose compatibility-shadow bytes have had
/// device-local private fields removed.
///
/// `backup.source_sha256` is the digest of `canonical_bytes`, not of the
/// caller-provided JSON. Consumers can therefore encrypt and persist
/// `canonical_bytes` as the compatibility shadow, then pass `backup` to
/// [`import_v18_transaction`] without creating a hash mismatch. Cloud-sync
/// metadata remains in the Android compatibility document, but
/// `import_v18_transaction` deliberately imports only Windows-managed core
/// data and never writes Windows cloud-sync configuration rows.
#[derive(Debug)]
pub(crate) struct PreparedV18Import {
    pub(crate) canonical_bytes: Vec<u8>,
    pub(crate) backup: ValidatedBackup,
    /// Validated input-only usage rows. They may be merged into the private,
    /// display-only cache after the structured database transaction commits,
    /// but are deliberately absent from `canonical_bytes` and every export.
    pub(crate) usage_devices: Vec<Value>,
    pub(crate) merge_usage_devices: bool,
    /// Only Android v28 and newer own the cross-device reader-progress
    /// container. Older backups are upgraded to a canonical v28 shadow with
    /// an empty `readerProgress` array, but importing one must not erase or
    /// otherwise touch the Windows reader ledger.
    pub(crate) merge_reader_progress: bool,
}

impl ValidatedBackup {
    pub fn preview(&self) -> BackupPreview {
        let known = [
            "format",
            "version",
            "exportedAt",
            "settings",
            "thoughts",
            "categories",
            "favorites",
            "dateRecords",
            "poetryCategories",
            "poems",
            "vault",
            "gameStates",
            "gameStatistics",
            "usageDevices",
            "readerProgress",
        ];
        let mut preserved_top_level_keys = self
            .root
            .as_object()
            .into_iter()
            .flat_map(Map::keys)
            .filter(|key| !known.contains(&key.as_str()))
            .cloned()
            .collect::<Vec<_>>();
        preserved_top_level_keys.sort();
        BackupPreview {
            format_version: self.format_version,
            exported_at: self.exported_at,
            thought_count: self.thoughts.len(),
            category_count: self.categories.len(),
            favorite_count: self.favorite_count,
            date_record_count: self.date_records.len(),
            poem_count: self.poems.len(),
            reader_progress_count: self.reader_progress.len(),
            preserved_top_level_keys,
        }
    }
}

pub fn parse_v18(json_text: &str) -> Result<ValidatedBackup, BackupError> {
    require_size(json_text.as_bytes())?;
    let mut root: Value = serde_json::from_str(json_text)?;
    let root_object = root
        .as_object()
        .ok_or_else(|| invalid("Backup root must be a JSON object"))?;
    if required_string(root_object, "format")? != "DeskCubby" {
        return Err(invalid("Unsupported backup format"));
    }
    let source_format_version = required_i32(root_object, "version")?;
    if !(1..=FORMAT_VERSION).contains(&source_format_version) {
        return Err(invalid("Windows requires an Android v1-v28 backup"));
    }
    let exported_at = required_i64(root_object, "exportedAt")?;
    require_nonnegative(exported_at, "exportedAt")?;

    if source_format_version < FORMAT_VERSION {
        root = upgrade_legacy_backup_to_v28(root, source_format_version, exported_at)?;
    }
    let root_object = root
        .as_object()
        .ok_or_else(|| invalid("Backup root must be a JSON object"))?;

    let settings_object = required_object(root_object, "settings")?;
    let cloud_sync_configs = validate_full_v28_settings(settings_object)?;
    let settings = decode_managed_settings(settings_object)?;
    settings.validate().map_err(BackupError::Invalid)?;

    let categories = decode_categories(required_array(root_object, "categories")?)?;
    let thoughts = decode_thoughts(required_array(root_object, "thoughts")?)?;
    let category_ids = categories
        .iter()
        .map(|category| category.id)
        .collect::<HashSet<_>>();
    for (index, thought) in thoughts.iter().enumerate() {
        if thought
            .category_id
            .is_some_and(|category_id| !category_ids.contains(&category_id))
        {
            return Err(invalid(format!(
                "thoughts[{index}].categoryId references a missing category"
            )));
        }
    }
    let favorite_count = validate_favorites(required_array(root_object, "favorites")?)?;
    let date_records = decode_date_records(required_array(root_object, "dateRecords")?)?;
    let poetry_category_items = required_array(root_object, "poetryCategories")?;
    let poetry_category_ids = validate_poetry_categories(poetry_category_items)?;
    let poetry_categories = decode_poetry_categories(poetry_category_items)?;
    let poem_items = required_array(root_object, "poems")?;
    let poems = decode_poems(poem_items)?;
    validate_v27_poem_fields(poem_items, &poetry_category_ids)?;
    validate_vault(required_object(root_object, "vault")?)?;
    let game_state_items = required_array(root_object, "gameStates")?;
    let game_statistic_items = required_array(root_object, "gameStatistics")?;
    let usage_device_items = required_array(root_object, "usageDevices")?;
    validate_game_states(game_state_items)?;
    validate_game_statistics(game_statistic_items)?;
    validate_usage_devices(usage_device_items)?;
    let reader_progress_items = required_array(root_object, "readerProgress")?;
    let reader_progress = validate_reader_progress(reader_progress_items)?;
    let game_states = decode_game_states(game_state_items)?;
    let game_statistics = decode_game_statistics(game_statistic_items)?;
    let source_sha256 = sha256_hex(json_text.as_bytes());

    Ok(ValidatedBackup {
        format_version: source_format_version,
        exported_at,
        settings,
        cloud_sync_configs,
        thoughts,
        categories,
        favorite_count,
        date_records,
        poetry_categories,
        poems,
        game_states,
        game_statistics,
        merge_game_states: source_format_version >= 20,
        merge_game_statistics: source_format_version >= 24,
        usage_devices: usage_device_items.to_vec(),
        merge_usage_devices: source_format_version >= 20,
        reader_progress,
        source_sha256,
        root,
    })
}

/// Android's decoder accepts every historical backup version and materializes
/// newer fields from `AppSettings` defaults before restoring. Windows keeps a
/// lossless compatibility shadow, so legacy input is upgraded once at the
/// boundary into an Android-readable v28 document while unrelated unknown
/// siblings remain untouched.
fn upgrade_legacy_backup_to_v28(
    mut root: Value,
    version: i32,
    exported_at: i64,
) -> Result<Value, BackupError> {
    let root_object = root
        .as_object_mut()
        .ok_or_else(|| invalid("Backup root must be a JSON object"))?;
    validate_legacy_required_shape(root_object, version)?;

    let original_settings = required_object(root_object, "settings")?.clone();
    let defaults = default_root(&ManagedSettings::default(), exported_at);
    let default_root_object = defaults
        .as_object()
        .ok_or_else(|| invalid("Internal v28 default root is invalid"))?;
    let default_settings = required_object(default_root_object, "settings")?;
    let mut upgraded_settings = default_settings.clone();
    for (key, value) in original_settings {
        upgraded_settings.insert(key, value);
    }

    // Fields unknown to an older Android decoder are not allowed to override
    // the defaults that decoder would have materialized.
    for (introduced, fields) in legacy_setting_introductions() {
        if version < *introduced {
            for field in *fields {
                let value = default_settings
                    .get(*field)
                    .ok_or_else(|| invalid(format!("Missing internal default: {field}")))?;
                upgraded_settings.insert((*field).to_owned(), value.clone());
            }
        }
    }
    migrate_legacy_settings(&mut upgraded_settings, default_settings, version)?;
    root_object.insert("settings".to_owned(), Value::Object(upgraded_settings));

    if version < 2 {
        root_object.insert("dateRecords".to_owned(), json!([]));
    }
    if version < 3 {
        root_object.insert("categories".to_owned(), json!([]));
    }
    if version < 4 {
        root_object.insert("poems".to_owned(), json!([]));
    }
    if version < 19 {
        root_object.insert("poetryCategories".to_owned(), json!([]));
    }
    if version < 20 {
        root_object.insert(
            "vault".to_owned(),
            json!({"active": null, "pending": null, "items": []}),
        );
        root_object.insert("gameStates".to_owned(), json!([]));
        root_object.insert("usageDevices".to_owned(), json!([]));
    }
    if version < 24 {
        root_object.insert("gameStatistics".to_owned(), json!([]));
    }
    if version < 28 {
        root_object.insert("readerProgress".to_owned(), json!([]));
    }
    migrate_legacy_thoughts(root_object, version)?;
    migrate_legacy_poems(root_object, version)?;
    root_object.insert("version".to_owned(), Value::from(FORMAT_VERSION));
    Ok(root)
}

fn validate_legacy_required_shape(
    root: &Map<String, Value>,
    version: i32,
) -> Result<(), BackupError> {
    for field in ["settings", "thoughts", "favorites"] {
        if !root.contains_key(field) {
            return Err(invalid(format!("Missing required field: {field}")));
        }
    }
    for (introduced, fields) in [
        (2, &["dateRecords"][..]),
        (3, &["categories"][..]),
        (4, &["poems"][..]),
        (19, &["poetryCategories"][..]),
        (20, &["vault", "gameStates", "usageDevices"][..]),
        (24, &["gameStatistics"][..]),
        (28, &["readerProgress"][..]),
    ] {
        if version >= introduced {
            for field in fields {
                if !root.contains_key(*field) {
                    return Err(invalid(format!("Missing required field: {field}")));
                }
            }
        }
    }

    let settings = required_object(root, "settings")?;
    for field in [
        "visualStyle",
        "darkMode",
        "appLanguage",
        "themeColorArgb",
        "diaryTreeUri",
        "mediaTreeUri",
        "fileNamePattern",
        "markdownTemplate",
        "imageNamePattern",
        "imageMaxWidthDp",
        "imageMaxHeightDp",
        "browserHomeUrl",
        "lastBrowserUrl",
        "browserTheme",
        "browserDesktopMode",
        "thoughtSplitRatio",
        "thoughtRowHeightDp",
        "navItems",
        "defaultPage",
        "bottomNavShowLabels",
        "homeWidgets",
        "homeWidgetTitles",
    ] {
        if !settings.contains_key(field) {
            return Err(invalid(format!("Missing required field: {field}")));
        }
    }
    for (introduced, fields) in legacy_setting_introductions() {
        if version >= *introduced {
            for field in *fields {
                if !settings.contains_key(*field) {
                    return Err(invalid(format!("Missing required field: {field}")));
                }
            }
        }
    }
    if version < 7 && required_string(settings, "visualStyle")? == "ORGANIC_FUTURE" {
        return Err(invalid(
            "visualStyle ORGANIC_FUTURE requires backup version 7 or newer",
        ));
    }
    if version < 28 && required_string(settings, "visualStyle")? == "CUSTOM" {
        return Err(invalid(
            "visualStyle CUSTOM requires backup version 28 or newer",
        ));
    }
    Ok(())
}

fn legacy_setting_introductions() -> &'static [(i32, &'static [&'static str])] {
    &[
        (4, &["mealButtonsUseIcons"]),
        (
            5,
            &["userName", "homeWidgetBordersEnabled", "mealButtonIcons"],
        ),
        (
            6,
            &["mealImageCompressionEnabled", "mealImageCompressionQuality"],
        ),
        (8, &["themeSecondaryColorsArgb", "fontScale"]),
        (
            9,
            &[
                "thoughtReopenMode",
                "thoughtDisplayMode",
                "mealCalendarImageMaxHeightDp",
                "mealCalendarShowCaptions",
                "dailyEventTemplates",
                "rssSubscriptions",
                "rssMaxItemsPerFeed",
                "rssShowSummaries",
                "aiEndpointUrl",
                "aiModel",
                "aiSystemPrompt",
                "aiTemperature",
                "aiAllowInsecureHttp",
            ],
        ),
        (
            10,
            &[
                "aiConfigs",
                "calorieEstimationEnabled",
                "calorieVisionPrompt",
                "calorieTextPrompt",
            ],
        ),
        (
            11,
            &[
                "aiChatConfigId",
                "calorieTextConfigId",
                "calorieImageConfigId",
            ],
        ),
        (
            13,
            &["cloudSyncEnabled", "cloudSyncConfigs", "mealPhotoFilter"],
        ),
        (
            14,
            &[
                "compactMode",
                "useChineseLauncherName",
                "saveOriginalToGallery",
                "photoLocationEnabled",
                "thoughtHighlightColorArgb",
                "thoughtEditorMaxHeightDp",
                "mealCalendarWrapEnabled",
                "mealCalendarPhotosPerRow",
            ],
        ),
        (
            15,
            &[
                "launcherIcon",
                "usageTrackingEnabled",
                "stepTrackingEnabled",
                "morePageShowDescriptions",
            ],
        ),
        (16, &["homeGreetings", "morePageOrder"]),
        (
            17,
            &[
                "poetryFontUri",
                "poetryFontSizeSp",
                "poetryLineSpacing",
                "poetryTextAlignment",
                "poetryShowSource",
                "poetryShowQuoteMark",
            ],
        ),
        (18, &["poetrySevenCharacterWrapEnabled"]),
        (21, &["vaultRowHeightDp"]),
        (22, &["desktopWidgetConfigs"]),
        (
            23,
            &[
                "musicVisualizerEnabled",
                "musicVisualizerStyle",
                "game2048AnimationSpeed",
            ],
        ),
        (
            24,
            &[
                "musicVisualizerFrequencyMode",
                "musicVisualizerMinFrequencyHz",
                "musicVisualizerMaxFrequencyHz",
            ],
        ),
        (
            25,
            &[
                "backgroundImageUri",
                "backgroundImageOpacity",
                "backgroundImageBlurDp",
                "tutorialModeEnabled",
            ],
        ),
        (26, &["notesTreeUri", "markdownHeadingSizesSp"]),
        (27, &["homeGameShortcuts"]),
        (28, &["customTheme"]),
    ]
}

fn migrate_legacy_settings(
    settings: &mut Map<String, Value>,
    defaults: &Map<String, Value>,
    version: i32,
) -> Result<(), BackupError> {
    if version < 19 {
        if let Some(configs) = settings
            .get_mut("cloudSyncConfigs")
            .and_then(Value::as_array_mut)
        {
            for config in configs {
                let object = config
                    .as_object_mut()
                    .ok_or_else(|| invalid("cloudSyncConfigs item must be an object"))?;
                object.insert("s3PathStyle".to_owned(), Value::Bool(true));
            }
        }
    }
    if version < 21 {
        if let Some(configs) = settings
            .get_mut("cloudSyncConfigs")
            .and_then(Value::as_array_mut)
        {
            for config in configs {
                let object = config
                    .as_object_mut()
                    .ok_or_else(|| invalid("cloudSyncConfigs item must be an object"))?;
                object.insert(
                    "userAgent".to_owned(),
                    Value::String("DeskCubby-Sync/1".to_owned()),
                );
            }
        }
    }
    if (10..12).contains(&version) {
        let configs = settings
            .get_mut("aiConfigs")
            .and_then(Value::as_array_mut)
            .ok_or_else(|| invalid("aiConfigs must be an array"))?;
        for config in configs {
            let object = config
                .as_object_mut()
                .ok_or_else(|| invalid("aiConfigs item must be an object"))?;
            object.insert("apiKey".to_owned(), Value::String(String::new()));
        }
    }
    if version >= 5 {
        let icons = settings
            .get_mut("mealButtonIcons")
            .and_then(Value::as_array_mut)
            .ok_or_else(|| invalid("mealButtonIcons must be an array"))?;
        if icons.len() == 5 {
            let default_tea = defaults
                .get("mealButtonIcons")
                .and_then(Value::as_array)
                .and_then(|items| items.get(2))
                .cloned()
                .ok_or_else(|| invalid("Internal meal icon default is invalid"))?;
            icons.insert(2, default_tea);
        }
    }
    migrate_legacy_home_modules(settings, version)?;
    migrate_legacy_nav(settings, version)?;
    Ok(())
}

fn migrate_legacy_home_modules(
    settings: &mut Map<String, Value>,
    version: i32,
) -> Result<(), BackupError> {
    for field in ["homeWidgets", "homeWidgetTitles"] {
        let items = settings
            .get_mut(field)
            .and_then(Value::as_array_mut)
            .ok_or_else(|| invalid(format!("{field} must be an array")))?;
        if version < 4 {
            insert_home_module_after_quick_input(items, "meal_photos");
        }
        if version < 9 {
            insert_home_module_after_quick_input(items, "daily_records");
        }
        if version < 26 {
            for id in ["notes", "game_shortcuts", "record_overview"] {
                if !items.iter().any(|item| item.as_str() == Some(id)) {
                    items.push(Value::String(id.to_owned()));
                }
            }
        }
    }
    Ok(())
}

fn insert_home_module_after_quick_input(items: &mut Vec<Value>, id: &str) {
    if items.iter().any(|item| item.as_str() == Some(id)) {
        return;
    }
    let insert_at = items
        .iter()
        .position(|item| item.as_str() == Some("quick_input"))
        .map_or(items.len(), |index| index + 1);
    items.insert(insert_at, Value::String(id.to_owned()));
}

fn migrate_legacy_nav(settings: &mut Map<String, Value>, version: i32) -> Result<(), BackupError> {
    let default_items = default_nav_items();
    let default_by_id = default_items
        .iter()
        .filter_map(|value| {
            let object = value.as_object()?;
            Some((
                required_string(object, "id").ok()?.to_owned(),
                object.clone(),
            ))
        })
        .collect::<HashMap<_, _>>();
    let nav = settings
        .get_mut("navItems")
        .and_then(Value::as_array_mut)
        .ok_or_else(|| invalid("navItems must be an array"))?;
    let mut present = HashSet::new();
    for (index, value) in nav.iter_mut().enumerate() {
        let item = value
            .as_object_mut()
            .ok_or_else(|| invalid(format!("navItems[{index}] must be an object")))?;
        let id = required_string(item, "id")?.to_owned();
        if !present.insert(id.clone()) {
            return Err(invalid(format!("Duplicate navigation item: {id}")));
        }
        let default = default_by_id
            .get(&id)
            .ok_or_else(|| invalid(format!("navItems[{index}].id is unsupported")))?;
        let visible = required_bool(item, "visible")?;
        if version < 13 {
            let default_more = required_bool(default, "showInMore")?;
            item.insert(
                "showInMore".to_owned(),
                Value::Bool(default_more && !visible),
            );
        } else if matches!(id.as_str(), "HOME" | "MORE" | "SETTINGS") {
            item.insert("showInMore".to_owned(), Value::Bool(false));
        }
        if version < 15 {
            item.insert(
                "moreDescription".to_owned(),
                default
                    .get("moreDescription")
                    .cloned()
                    .ok_or_else(|| invalid("Internal nav default is invalid"))?,
            );
        }
        if version < 24 && matches!(id.as_str(), "USAGE" | "STEPS") {
            item.insert("showInMore".to_owned(), Value::Bool(false));
        }
    }
    let settings_position = nav
        .iter()
        .position(|value| value.get("id").and_then(Value::as_str) == Some("SETTINGS"));
    let mut missing = default_items
        .into_iter()
        .filter(|value| {
            value
                .get("id")
                .and_then(Value::as_str)
                .is_some_and(|id| !present.contains(id))
        })
        .collect::<Vec<_>>();
    if let Some(index) = settings_position.filter(|index| *index == nav.len() - 1) {
        let settings_item = nav.remove(index);
        nav.append(&mut missing);
        nav.push(settings_item);
    } else {
        nav.append(&mut missing);
    }

    if version < 16 {
        let mut order = nav
            .iter()
            .filter_map(|item| item.get("id").and_then(Value::as_str))
            .filter(|id| !matches!(*id, "HOME" | "MORE" | "SETTINGS"))
            .map(|id| Value::String(id.to_owned()))
            .collect::<Vec<_>>();
        append_missing_more_page_ids(&mut order);
        settings.insert("morePageOrder".to_owned(), Value::Array(order));
    } else if let Some(order) = settings
        .get_mut("morePageOrder")
        .and_then(Value::as_array_mut)
    {
        append_missing_more_page_ids(order);
    }
    Ok(())
}

fn append_missing_more_page_ids(order: &mut Vec<Value>) {
    for id in [
        "DIARY",
        "NOTES",
        "BLOG",
        "THOUGHT",
        "DATE",
        "POETRY",
        "RSS",
        "AI_CHAT",
        "VAULT",
        "READER",
        "GAMES",
        "STATISTICS",
        "USAGE",
        "STEPS",
        "WIDGETS",
    ] {
        if !order.iter().any(|item| item.as_str() == Some(id)) {
            order.push(Value::String(id.to_owned()));
        }
    }
}

fn migrate_legacy_thoughts(root: &mut Map<String, Value>, version: i32) -> Result<(), BackupError> {
    let thoughts = root
        .get_mut("thoughts")
        .and_then(Value::as_array_mut)
        .ok_or_else(|| invalid("thoughts must be an array"))?;
    for (index, value) in thoughts.iter_mut().enumerate() {
        let item = value
            .as_object_mut()
            .ok_or_else(|| invalid(format!("thoughts[{index}] must be an object")))?;
        if version < 3 {
            item.insert("categoryId".to_owned(), Value::Null);
        }
        if version < 14 {
            item.insert("highlighted".to_owned(), Value::Bool(false));
        }
    }
    Ok(())
}

fn migrate_legacy_poems(root: &mut Map<String, Value>, version: i32) -> Result<(), BackupError> {
    let poems = root
        .get_mut("poems")
        .and_then(Value::as_array_mut)
        .ok_or_else(|| invalid("poems must be an array"))?;
    for (index, value) in poems.iter_mut().enumerate() {
        let item = value
            .as_object_mut()
            .ok_or_else(|| invalid(format!("poems[{index}] must be an object")))?;
        if version < 19 {
            item.insert("categoryId".to_owned(), Value::Null);
        }
        if version < 21 {
            item.insert("sortOrder".to_owned(), Value::from(0));
        }
    }
    Ok(())
}

/// Validate and canonicalize an imported Android v28 backup before it becomes
/// the encrypted compatibility shadow.
///
/// This is the single import-side privacy boundary: private fields and cloud
/// credentials are removed, official Vault/usage containers are emptied, and
/// game data, the AI API key, Android SAF URIs, and unknown compatibility
/// fields remain intact. Validated usage rows are returned separately for the
/// private display-only cache. The sanitized compact JSON is parsed again so
/// the returned model and source hash describe the exact persisted bytes.
pub(crate) fn prepare_v18_import_for_shadow(
    json_text: &str,
) -> Result<PreparedV18Import, BackupError> {
    let parsed = parse_v18(json_text)?;
    let usage_devices = parsed.usage_devices.clone();
    let merge_usage_devices = parsed.merge_usage_devices;
    let merge_reader_progress = parsed.format_version >= 28;
    let mut root = parsed.root;
    let root_object = root
        .as_object_mut()
        .ok_or_else(|| invalid("Backup root must be a JSON object"))?;
    scrub_excluded_private_backup_fields(root_object)?;

    let canonical_bytes = serde_json::to_vec(&root)?;
    require_size(&canonical_bytes)?;
    let canonical_text = std::str::from_utf8(&canonical_bytes)
        .map_err(|_| invalid("Canonical backup is not UTF-8 JSON"))?;
    let backup = parse_v18(canonical_text)?;

    Ok(PreparedV18Import {
        canonical_bytes,
        backup,
        usage_devices,
        merge_usage_devices,
        merge_reader_progress,
    })
}

pub fn preview_v18(json_text: &str) -> Result<BackupPreview, BackupError> {
    Ok(parse_v18(json_text)?.preview())
}

pub fn import_v18_transaction(
    database: &Database,
    backup: &ValidatedBackup,
    encrypted_shadow: Option<&[u8]>,
) -> Result<ImportReceipt, BackupError> {
    let imported_at = now_millis();
    database.replace_imported_core(
        &backup.settings,
        &backup.thoughts,
        &backup.categories,
        &backup.date_records,
        &backup.poetry_categories,
        &backup.poems,
        &backup.game_states,
        &backup.game_statistics,
        backup.merge_game_states,
        backup.merge_game_statistics,
        encrypted_shadow,
        &backup.source_sha256,
        imported_at,
    )?;
    Ok(ImportReceipt {
        imported_at,
        source_sha256: backup.source_sha256.clone(),
        thought_count: backup.thoughts.len(),
        category_count: backup.categories.len(),
        favorite_count: backup.favorite_count,
        date_record_count: backup.date_records.len(),
        poem_count: backup.poems.len(),
    })
}

/// Merge Windows-managed data into a decrypted v28 compatibility shadow.
///
/// Passing `None` creates a complete Android-readable v28 document with safe
/// defaults for postponed modules. Local Windows folder paths are never written
/// into Android `content://` URI fields.
#[allow(dead_code)]
pub fn export_v18_merged(
    database: &Database,
    decrypted_shadow: Option<&[u8]>,
    exported_at: i64,
) -> Result<String, BackupError> {
    export_v18_merged_with_cloud_configs(database, decrypted_shadow, exported_at, None)
}

/// Merge Windows-managed data and optional credential-free cloud metadata into
/// a decrypted v28 compatibility shadow.
///
/// `cloud_sync_configs = None` preserves imported credential-free cloud
/// metadata and its unknown fields. Passing `Some` makes Windows the owner of
/// `cloudSyncConfigs`, preserves unknown sibling fields for matching IDs, and
/// disables Android cloud/usage collection toggles. Both paths unconditionally
/// remove non-format Windows private fields and device-local cloud credentials,
/// and emit the required v28 Vault/usage containers only in their empty forms.
pub fn export_v18_merged_with_cloud_configs(
    database: &Database,
    decrypted_shadow: Option<&[u8]>,
    exported_at: i64,
    cloud_sync_configs: Option<&[CloudSyncConfig]>,
) -> Result<String, BackupError> {
    export_v18_merged_with_cloud_configs_and_reader_progress(
        database,
        decrypted_shadow,
        exported_at,
        cloud_sync_configs,
        None,
    )
}

pub(crate) fn export_v18_merged_with_cloud_configs_and_reader_progress(
    database: &Database,
    decrypted_shadow: Option<&[u8]>,
    exported_at: i64,
    cloud_sync_configs: Option<&[CloudSyncConfig]>,
    reader_progress: Option<&[ReaderProgressRecord]>,
) -> Result<String, BackupError> {
    require_nonnegative(exported_at, "exportedAt")?;
    if let Some(configs) = cloud_sync_configs {
        validate_cloud_sync_configs(configs)?;
    }
    let settings = database.get_managed_settings()?;
    let mut root = if let Some(shadow) = decrypted_shadow {
        let stored_shadow = database
            .get_compatibility_shadow()?
            .ok_or_else(|| invalid("Compatibility shadow metadata is missing"))?;
        verify_source_sha256(shadow, &stored_shadow.source_sha256)?;
        let shadow_text = std::str::from_utf8(shadow)
            .map_err(|_| invalid("Compatibility shadow is not UTF-8 JSON"))?;
        parse_v18(shadow_text)?.root
    } else {
        default_root(&settings, exported_at)
    };
    let root_object = root
        .as_object_mut()
        .ok_or_else(|| invalid("Compatibility shadow root is not an object"))?;
    root_object.insert("format".to_owned(), Value::String("DeskCubby".to_owned()));
    root_object.insert("version".to_owned(), Value::from(FORMAT_VERSION));
    root_object.insert("exportedAt".to_owned(), Value::from(exported_at));
    let shadow_settings = root_object
        .get_mut("settings")
        .and_then(Value::as_object_mut)
        .ok_or_else(|| invalid("Compatibility shadow settings are invalid"))?;
    overlay_managed_settings(shadow_settings, &settings)?;
    if let Some(configs) = cloud_sync_configs {
        overlay_managed_root_field(
            shadow_settings,
            "cloudSyncConfigs",
            serde_json::to_value(configs)?,
        );
        ensure_v27_cloud_config_defaults(shadow_settings)?;
        shadow_settings.insert("cloudSyncEnabled".to_owned(), Value::Bool(false));
        shadow_settings.insert("usageTrackingEnabled".to_owned(), Value::Bool(false));
        shadow_settings.insert("stepTrackingEnabled".to_owned(), Value::Bool(false));
    }
    // This is unconditional: both the legacy `None` path and the Windows-owned
    // cloud metadata path may start from an imported compatibility shadow.
    scrub_excluded_private_backup_fields(root_object)?;
    overlay_managed_root_field(
        root_object,
        "thoughts",
        serde_json::to_value(database.list_thoughts(true)?)?,
    );
    overlay_managed_root_field(
        root_object,
        "categories",
        serde_json::to_value(database.list_categories()?)?,
    );
    overlay_managed_root_field(
        root_object,
        "dateRecords",
        serde_json::to_value(database.list_date_records()?)?,
    );
    overlay_managed_root_field(
        root_object,
        "poetryCategories",
        serde_json::to_value(database.list_poetry_categories()?)?,
    );
    overlay_managed_root_field(
        root_object,
        "poems",
        serde_json::to_value(database.list_poems()?)?,
    );
    ensure_v27_poem_defaults(root_object)?;
    let (game_states, game_statistics) = database.list_game_backup_rows()?;
    overlay_managed_root_field(
        root_object,
        "gameStates",
        serde_json::to_value(game_states)?,
    );
    overlay_managed_root_field(
        root_object,
        "gameStatistics",
        serde_json::to_value(game_statistics)?,
    );
    if let Some(records) = reader_progress {
        overlay_reader_progress(root_object, records)?;
    }

    // Reassert the private-data boundary after all managed overlays. Android
    // v28 still sees the required fields, but Windows never emits Vault
    // ciphertext/metadata or phone-usage samples in application JSON.
    scrub_excluded_private_backup_fields(root_object)?;

    let encoded = serde_json::to_string_pretty(&root)?;
    require_size(encoded.as_bytes())?;
    // Validate our own output before it reaches disk. This catches locally
    // corrupted rows without risking an unreadable backup.
    parse_v18(&encoded)?;
    Ok(encoded)
}

fn overlay_reader_progress(
    root: &mut Map<String, Value>,
    records: &[ReaderProgressRecord],
) -> Result<(), BackupError> {
    let mut sorted = records.to_vec();
    sorted.sort_by(|left, right| {
        left.fingerprint
            .cmp(&right.fingerprint)
            .then_with(|| left.book_type.cmp(&right.book_type))
    });
    let managed = serde_json::to_value(&sorted)?;
    let managed_items = managed
        .as_array()
        .ok_or_else(|| invalid("Windows reader-progress serialization failed"))?;
    validate_reader_progress(managed_items)?;

    let previous_items = root
        .get_mut("readerProgress")
        .and_then(Value::as_array_mut)
        .ok_or_else(|| invalid("readerProgress must be an array"))?;
    let mut previous = std::mem::take(previous_items)
        .into_iter()
        .filter_map(|value| reader_progress_identity(&value).map(|key| (key, value)))
        .collect::<HashMap<_, _>>();
    let mut merged_items = Vec::with_capacity(managed_items.len() + previous.len());
    for managed_record in managed_items {
        let identity = reader_progress_identity(managed_record)
            .expect("validated reader progress always has an identity");
        let merged = if let Some(mut previous_record) = previous.remove(&identity) {
            if reader_progress_rank(&previous_record) > reader_progress_rank(managed_record) {
                previous_record
            } else {
                merge_managed_value(&mut previous_record, managed_record);
                previous_record
            }
        } else {
            managed_record.clone()
        };
        merged_items.push(merged);
    }
    merged_items.extend(previous.into_values());
    // Same-key ties above use the actual TXT paragraph/PDF page position;
    // Android then keeps the newest 500 distinct fingerprint/type pairs.
    merged_items.sort_by(|left, right| {
        reader_progress_updated_at(right)
            .cmp(&reader_progress_updated_at(left))
            .then_with(|| reader_progress_identity(left).cmp(&reader_progress_identity(right)))
    });
    merged_items.truncate(MAX_READER_PROGRESS_RECORDS);
    merged_items.sort_by_key(reader_progress_identity);
    *previous_items = merged_items;
    Ok(())
}

fn reader_progress_identity(value: &Value) -> Option<String> {
    let object = value.as_object()?;
    let fingerprint = object.get("fingerprint")?.as_str()?;
    let book_type = object.get("type")?.as_str()?;
    Some(format!("{fingerprint}\0{book_type}"))
}

fn reader_progress_rank(value: &Value) -> (i64, i32) {
    let Some(object) = value.as_object() else {
        return (-1, -1);
    };
    let updated_at = object
        .get("updatedAt")
        .and_then(|value| value_i64(value, "updatedAt").ok())
        .unwrap_or(-1);
    let position_field = match object.get("type").and_then(Value::as_str) {
        Some("TXT") => "textParagraphIndex",
        _ => "pdfPageIndex",
    };
    let position = object
        .get(position_field)
        .and_then(|value| value_i32(value, position_field).ok())
        .unwrap_or(-1);
    (updated_at, position)
}

fn reader_progress_updated_at(value: &Value) -> i64 {
    value
        .as_object()
        .and_then(|object| object.get("updatedAt"))
        .and_then(|value| value_i64(value, "updatedAt").ok())
        .unwrap_or(-1)
}

fn ensure_v27_cloud_config_defaults(settings: &mut Map<String, Value>) -> Result<(), BackupError> {
    let configs = settings
        .get_mut("cloudSyncConfigs")
        .and_then(Value::as_array_mut)
        .ok_or_else(|| invalid("cloudSyncConfigs must be an array"))?;
    for (index, value) in configs.iter_mut().enumerate() {
        let config = value
            .as_object_mut()
            .ok_or_else(|| invalid(format!("cloudSyncConfigs[{index}] must be an object")))?;
        config
            .entry("userAgent".to_owned())
            .or_insert_with(|| Value::String("DeskCubby-Sync/1".to_owned()));
        config
            .entry("s3PathStyle".to_owned())
            .or_insert(Value::Bool(true));
    }
    Ok(())
}

fn ensure_v27_poem_defaults(root: &mut Map<String, Value>) -> Result<(), BackupError> {
    let poems = root
        .get_mut("poems")
        .and_then(Value::as_array_mut)
        .ok_or_else(|| invalid("poems must be an array"))?;
    for (index, value) in poems.iter_mut().enumerate() {
        let poem = value
            .as_object_mut()
            .ok_or_else(|| invalid(format!("poems[{index}] must be an object")))?;
        poem.entry("sortOrder".to_owned())
            .or_insert_with(|| Value::from(index as i64));
        poem.entry("categoryId".to_owned()).or_insert(Value::Null);
    }
    Ok(())
}

#[cfg(test)]
fn recovery_point_bytes(database: &Database) -> Result<Vec<u8>, BackupError> {
    recovery_point_bytes_inner(database, None)
}

pub(crate) fn recovery_point_bytes_with_reader(
    database: &Database,
    reader_state: &[u8],
) -> Result<Vec<u8>, BackupError> {
    if reader_state.is_empty() || reader_state.len() > MAX_RECOVERY_READER_STATE_BYTES {
        return Err(BackupError::RecoveryPointTooLarge);
    }
    let reader_state: Value = serde_json::from_slice(reader_state)?;
    if !reader_state.is_object() {
        return Err(invalid("Recovery reader state is invalid"));
    }
    recovery_point_bytes_inner(database, Some(reader_state))
}

fn recovery_point_bytes_inner(
    database: &Database,
    reader_state: Option<Value>,
) -> Result<Vec<u8>, BackupError> {
    let mut snapshot = database.snapshot_core()?;
    // Compatibility shadows can originate from an older Windows build that
    // predates the v27 scrub boundary. A recovery point cannot decrypt and
    // re-sanitize that DPAPI blob, so omit it entirely. Core rows remain
    // recoverable and restoring the point safely clears any newer shadow.
    snapshot.encrypted_compatibility_shadow = None;
    snapshot.compatibility_source_sha256 = None;
    let recovery = RecoveryPoint {
        format: "DeskCubby Windows recovery".to_owned(),
        version: if reader_state.is_some() { 2 } else { 1 },
        snapshot,
        reader_state,
    };
    let bytes = serde_json::to_vec_pretty(&recovery)?;
    ensure_recovery_point_size(bytes.len())?;
    Ok(bytes)
}

pub fn restore_recovery_point(database: &Database, bytes: &[u8]) -> Result<(), BackupError> {
    let recovery = decode_recovery_point(bytes)?;
    database.restore_core_snapshot(&recovery.snapshot)?;
    Ok(())
}

pub(crate) fn reader_state_from_recovery_point(
    bytes: &[u8],
) -> Result<Option<Vec<u8>>, BackupError> {
    let recovery = decode_recovery_point(bytes)?;
    recovery
        .reader_state
        .map(|value| {
            let bytes = serde_json::to_vec(&value)?;
            if bytes.is_empty() || bytes.len() > MAX_RECOVERY_READER_STATE_BYTES {
                return Err(BackupError::RecoveryPointTooLarge);
            }
            Ok(bytes)
        })
        .transpose()
}

fn decode_recovery_point(bytes: &[u8]) -> Result<RecoveryPoint, BackupError> {
    ensure_recovery_point_size(bytes.len())?;
    let recovery: RecoveryPoint = serde_json::from_slice(bytes)?;
    if recovery.format != "DeskCubby Windows recovery"
        || !matches!(recovery.version, 1 | 2)
        || (recovery.version == 1 && recovery.reader_state.is_some())
        || (recovery.version == 2 && recovery.reader_state.is_none())
    {
        return Err(invalid("Unsupported recovery point"));
    }
    Ok(recovery)
}

fn ensure_recovery_point_size(size: usize) -> Result<(), BackupError> {
    if size > MAX_RECOVERY_POINT_BYTES {
        return Err(BackupError::RecoveryPointTooLarge);
    }
    Ok(())
}

#[derive(Debug, Serialize, Deserialize)]
struct RecoveryPoint {
    format: String,
    version: i32,
    snapshot: CoreSnapshot,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    reader_state: Option<Value>,
}

fn decode_managed_settings(settings: &Map<String, Value>) -> Result<ManagedSettings, BackupError> {
    let theme_secondary_colors_argb = required_array(settings, "themeSecondaryColorsArgb")?
        .iter()
        .enumerate()
        .map(|(index, value)| value_i32(value, &format!("themeSecondaryColorsArgb[{index}]")))
        .collect::<Result<Vec<_>, _>>()?;
    let meal_photo_filter_object = required_object(settings, "mealPhotoFilter")?;
    let meal_photo_filter = MealPhotoFilter {
        enabled: required_bool(meal_photo_filter_object, "enabled")?,
        brightness: required_android_float_range(
            meal_photo_filter_object,
            "brightness",
            -1.0,
            1.0,
        )?,
        contrast: required_android_float_range(meal_photo_filter_object, "contrast", 0.0, 2.0)?,
        saturation: required_android_float_range(meal_photo_filter_object, "saturation", 0.0, 2.0)?,
        warmth: required_android_float_range(meal_photo_filter_object, "warmth", -1.0, 1.0)?,
        tint: required_android_float_range(meal_photo_filter_object, "tint", -1.0, 1.0)?,
    };
    let mut meal_button_icons = string_list(
        required_array(settings, "mealButtonIcons")?,
        "mealButtonIcons",
    )?;
    if meal_button_icons.len() == 5 {
        meal_button_icons.insert(2, "🍹".to_owned());
    }
    let home_greetings = required_array(settings, "homeGreetings")?
        .iter()
        .enumerate()
        .map(|(index, value)| {
            let item = value
                .as_object()
                .ok_or_else(|| invalid(format!("homeGreetings[{index}] must be an object")))?;
            Ok(HomeGreeting {
                chinese: required_string(item, "chinese")?.to_owned(),
                english: required_string(item, "english")?.to_owned(),
            })
        })
        .collect::<Result<Vec<_>, BackupError>>()?;
    let daily_event_templates = required_array(settings, "dailyEventTemplates")?
        .iter()
        .enumerate()
        .map(|(index, value)| {
            let item = value.as_object().ok_or_else(|| {
                invalid(format!("dailyEventTemplates[{index}] must be an object"))
            })?;
            Ok(DailyEventTemplate {
                id: required_string(item, "id")?.to_owned(),
                text: required_string(item, "text")?.to_owned(),
                first_unit: required_string(item, "firstUnit")?.to_owned(),
                second_unit: required_string(item, "secondUnit")?.to_owned(),
            })
        })
        .collect::<Result<Vec<_>, BackupError>>()?;
    let rss_subscriptions = required_array(settings, "rssSubscriptions")?
        .iter()
        .enumerate()
        .map(|(index, value)| {
            let item = value
                .as_object()
                .ok_or_else(|| invalid(format!("rssSubscriptions[{index}] must be an object")))?;
            Ok(RssSubscription {
                id: required_string(item, "id")?.to_owned(),
                title: required_string(item, "title")?.to_owned(),
                url: required_string(item, "url")?.to_owned(),
                enabled: required_bool(item, "enabled")?,
            })
        })
        .collect::<Result<Vec<_>, BackupError>>()?;
    let ai_configs = required_array(settings, "aiConfigs")?
        .iter()
        .enumerate()
        .map(|(index, value)| {
            let item = value
                .as_object()
                .ok_or_else(|| invalid(format!("aiConfigs[{index}] must be an object")))?;
            Ok(AiModelConfig {
                id: required_string(item, "id")?.to_owned(),
                name: required_string(item, "name")?.to_owned(),
                model_type: required_string(item, "type")?.to_owned(),
                endpoint_url: required_string(item, "endpointUrl")?.to_owned(),
                model: required_string(item, "model")?.to_owned(),
                enabled: required_bool(item, "enabled")?,
                allow_insecure_http: required_bool(item, "allowInsecureHttp")?,
                temperature: f64::from(required_number(item, "temperature")? as f32)
                    .clamp(0.0, 2.0),
                system_prompt: item
                    .get("systemPrompt")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_owned(),
                api_key: required_string(item, "apiKey")?.to_owned(),
            })
        })
        .collect::<Result<Vec<_>, BackupError>>()?;
    let markdown_heading_sizes_sp = required_array(settings, "markdownHeadingSizesSp")?
        .iter()
        .enumerate()
        .map(|(index, value)| {
            let number = value.as_number().ok_or_else(|| {
                invalid(format!("markdownHeadingSizesSp[{index}] must be a number"))
            })?;
            let value = number
                .as_f64()
                .filter(|value| value.is_finite())
                .ok_or_else(|| {
                    invalid(format!("markdownHeadingSizesSp[{index}] must be finite"))
                })?;
            Ok(f64::from(value as f32))
        })
        .collect::<Result<Vec<_>, BackupError>>()?;

    let mut decoded = ManagedSettings {
        // Windows currently renders the same three base styles as Android. A
        // v28 CUSTOM selection remains owned by the compatibility shadow, while
        // its bounded baseStyle is used for the local Windows appearance.
        visual_style: windows_visual_style(settings)?,
        dark_mode: required_string(settings, "darkMode")?.to_owned(),
        app_language: required_string(settings, "appLanguage")?.to_owned(),
        theme_color_argb: required_i32(settings, "themeColorArgb")?,
        theme_secondary_colors_argb,
        font_scale: f64::from(required_number(settings, "fontScale")? as f32),
        compact_mode: required_bool(settings, "compactMode")?,
        background_image_path: None,
        background_image_opacity: 0.45,
        background_image_blur_px: 0.0,
        tutorial_mode_enabled: required_bool(settings, "tutorialModeEnabled")?,
        file_name_pattern: required_string(settings, "fileNamePattern")?.to_owned(),
        markdown_template: required_string(settings, "markdownTemplate")?.to_owned(),
        image_name_pattern: required_string(settings, "imageNamePattern")?.to_owned(),
        image_max_width_dp: required_coerced_i32(settings, "imageMaxWidthDp", 120, 2_400)?,
        image_max_height_dp: required_coerced_i32(settings, "imageMaxHeightDp", 120, 2_400)?,
        markdown_heading_sizes_sp,
        meal_image_compression_enabled: required_bool(settings, "mealImageCompressionEnabled")?,
        meal_image_compression_quality: required_coerced_i32(
            settings,
            "mealImageCompressionQuality",
            30,
            95,
        )?,
        photo_location_enabled: required_bool(settings, "photoLocationEnabled")?,
        thought_display_mode: required_string(settings, "thoughtDisplayMode")?.to_owned(),
        thought_highlight_color_argb: required_i32(settings, "thoughtHighlightColorArgb")?,
        thought_editor_max_height_dp: required_coerced_i32(
            settings,
            "thoughtEditorMaxHeightDp",
            96,
            400,
        )?,
        vault_row_height_dp: required_coerced_i32(settings, "vaultRowHeightDp", 48, 120)?,
        poetry_font_size_sp: f64::from(required_number(settings, "poetryFontSizeSp")? as f32),
        poetry_line_spacing: f64::from(required_number(settings, "poetryLineSpacing")? as f32),
        poetry_text_alignment: required_string(settings, "poetryTextAlignment")?.to_owned(),
        poetry_show_source: required_bool(settings, "poetryShowSource")?,
        poetry_show_quote_mark: required_bool(settings, "poetryShowQuoteMark")?,
        poetry_seven_character_wrap_enabled: required_bool(
            settings,
            "poetrySevenCharacterWrapEnabled",
        )?,
        meal_calendar_image_max_height_dp: required_coerced_i32(
            settings,
            "mealCalendarImageMaxHeightDp",
            80,
            320,
        )?,
        meal_calendar_show_captions: required_bool(settings, "mealCalendarShowCaptions")?,
        meal_calendar_wrap_enabled: required_bool(settings, "mealCalendarWrapEnabled")?,
        meal_calendar_photos_per_row: required_string(settings, "mealCalendarPhotosPerRow")?
            .to_owned(),
        // This layout is Windows-local and therefore is not part of the
        // Android backup document being decoded here.
        meal_calendar_day_columns: 1,
        meal_photo_filter,
        meal_buttons_use_icons: required_bool(settings, "mealButtonsUseIcons")?,
        meal_button_icons,
        user_name: required_string(settings, "userName")?.to_owned(),
        home_greetings,
        home_widget_borders_enabled: required_bool(settings, "homeWidgetBordersEnabled")?,
        daily_event_templates,
        rss_subscriptions,
        rss_max_items_per_feed: required_coerced_i32(settings, "rssMaxItemsPerFeed", 10, 200)?,
        rss_show_summaries: required_bool(settings, "rssShowSummaries")?,
        ai_configs,
        ai_chat_config_id: validate_nullable_string(settings, "aiChatConfigId", 80)?
            .map(str::to_owned),
        calorie_estimation_enabled: required_bool(settings, "calorieEstimationEnabled")?,
        calorie_text_config_id: validate_nullable_string(settings, "calorieTextConfigId", 80)?
            .map(str::to_owned),
        calorie_image_config_id: validate_nullable_string(settings, "calorieImageConfigId", 80)?
            .map(str::to_owned),
        calorie_vision_prompt: required_string(settings, "calorieVisionPrompt")?.to_owned(),
        calorie_text_prompt: required_string(settings, "calorieTextPrompt")?.to_owned(),
        home_widgets: string_list(required_array(settings, "homeWidgets")?, "homeWidgets")?,
        home_game_shortcuts: string_list(
            required_array(settings, "homeGameShortcuts")?,
            "homeGameShortcuts",
        )?,
        home_widget_titles: string_list(
            required_array(settings, "homeWidgetTitles")?,
            "homeWidgetTitles",
        )?,
    };
    decoded.normalize_android_compatible();
    Ok(decoded)
}

fn windows_visual_style(settings: &Map<String, Value>) -> Result<String, BackupError> {
    let visual_style = required_string(settings, "visualStyle")?;
    if visual_style != "CUSTOM" {
        return Ok(visual_style.to_owned());
    }
    Ok(required_string(required_object(settings, "customTheme")?, "baseStyle")?.to_owned())
}

fn overlay_managed_settings(
    target: &mut Map<String, Value>,
    settings: &ManagedSettings,
) -> Result<(), BackupError> {
    settings.validate().map_err(BackupError::Invalid)?;
    let managed_value = serde_json::to_value(settings)?;
    let managed_object = managed_value
        .as_object()
        .ok_or_else(|| invalid("Windows settings serialization failed"))?;
    let preserve_custom_visual_style = target
        .get("visualStyle")
        .and_then(Value::as_str)
        .is_some_and(|value| value == "CUSTOM")
        && target
            .get("customTheme")
            .and_then(Value::as_object)
            .and_then(|theme| theme.get("baseStyle"))
            .and_then(Value::as_str)
            .is_some_and(|base| base == settings.visual_style.as_str());
    for (key, value) in managed_object {
        if matches!(
            key.as_str(),
            "backgroundImagePath"
                | "backgroundImageOpacity"
                | "backgroundImageBlurPx"
                | "mealCalendarDayColumns"
        ) {
            // These are Windows-local display settings. They must never become
            // Android SAF settings or unknown compatibility-shadow fields.
            continue;
        }
        if key == "visualStyle" && preserve_custom_visual_style {
            // Merely opening a Custom-themed Android backup on Windows must
            // not replace it with the local fallback base style. Choosing a
            // different Windows style still changes the value on export.
            continue;
        }
        match target.get_mut(key) {
            Some(existing) => merge_managed_value(existing, value),
            None => {
                target.insert(key.clone(), value.clone());
            }
        }
    }
    // Android-only SAF values are intentionally never derived from Windows paths.
    Ok(())
}

fn overlay_managed_root_field(target: &mut Map<String, Value>, key: &str, managed: Value) {
    match target.get_mut(key) {
        Some(existing) => merge_managed_value(existing, &managed),
        None => {
            target.insert(key.to_owned(), managed);
        }
    }
}

/// Exact known private keys are removed while unrelated future fields remain
/// untouched. Required Android v28 Vault and usage containers are retained in
/// their canonical empty forms so the document stays schema-compatible.
fn scrub_excluded_private_backup_fields(root: &mut Map<String, Value>) -> Result<(), BackupError> {
    const PRIVATE_BACKUP_FIELDS: [&str; 12] = [
        "vaultItems",
        "vaultMetadata",
        "usageStatistics",
        "stepStatistics",
        "healthHistory",
        "healthStatistics",
        "healthDevices",
        "healthSnapshot",
        "healthSourceMetadata",
        "healthSourcePath",
        "linkedHealthSourcePath",
        "cloudSyncSecrets",
    ];
    for field in PRIVATE_BACKUP_FIELDS {
        root.remove(field);
    }

    let vault = root
        .get_mut("vault")
        .and_then(Value::as_object_mut)
        .ok_or_else(|| invalid("Backup vault must be an object"))?;
    vault.insert("active".to_owned(), Value::Null);
    vault.insert("pending".to_owned(), Value::Null);
    vault.insert("items".to_owned(), Value::Array(Vec::new()));
    root.insert("usageDevices".to_owned(), Value::Array(Vec::new()));
    scrub_reader_progress_metadata(root)?;

    let settings_value = root
        .get_mut("settings")
        .ok_or_else(|| invalid("Backup settings must be an object"))?;
    {
        let settings = settings_value
            .as_object_mut()
            .ok_or_else(|| invalid("Backup settings must be an object"))?;
        for field in PRIVATE_BACKUP_FIELDS {
            settings.remove(field);
        }
    }
    // Repeat recursively across the full compatibility document so an
    // attacker cannot relocate a known Windows/credential field under an
    // otherwise unknown object. AI `apiKey` is intentionally not on this
    // deny-list because Android v12+ defines it as ordinary backup data.
    for value in root.values_mut() {
        scrub_cloud_credential_fields(value);
    }

    Ok(())
}

const CLOUD_CREDENTIAL_FIELDS: [&str; 30] = [
    "cloudSyncCredentials",
    "cloudSyncSecrets",
    "webDavPassword",
    "s3AccessKey",
    "s3SecretKey",
    "s3SessionToken",
    "dpapiCiphertext",
    "dpapiPayload",
    "encryptedCredentials",
    "credentialCiphertext",
    "vaultSessionKey",
    "vaultDerivedKey",
    "vaultItems",
    "vaultMetadata",
    "usageStatistics",
    "stepStatistics",
    "usageDevices",
    "healthHistory",
    "healthStatistics",
    "healthDevices",
    "healthSnapshot",
    "healthSourceMetadata",
    "healthSourcePath",
    "linkedHealthSourcePath",
    "usageSourcePath",
    "phoneUsageSourcePath",
    "linkedUsageSourcePath",
    "backgroundImagePath",
    "backgroundImageBlurPx",
    "mealCalendarDayColumns",
];

fn scrub_cloud_credential_fields(value: &mut Value) {
    match value {
        Value::Object(object) => scrub_cloud_credential_fields_in_object(object),
        Value::Array(items) => {
            for item in items {
                scrub_cloud_credential_fields(item);
            }
        }
        _ => {}
    }
}

fn scrub_cloud_credential_fields_in_object(object: &mut Map<String, Value>) {
    for field in CLOUD_CREDENTIAL_FIELDS {
        object.remove(field);
    }
    for value in object.values_mut() {
        scrub_cloud_credential_fields(value);
    }
}

/// Overlay fields owned by Windows while retaining unknown sibling fields from
/// the Android compatibility shadow. Object arrays with stable `id` values are
/// matched by ID; other object arrays are matched by index.
fn merge_managed_value(target: &mut Value, managed: &Value) {
    match (target, managed) {
        (Value::Object(target_object), Value::Object(managed_object)) => {
            for (key, value) in managed_object {
                match target_object.get_mut(key) {
                    Some(existing) => merge_managed_value(existing, value),
                    None => {
                        target_object.insert(key.clone(), value.clone());
                    }
                }
            }
        }
        (Value::Array(target_items), Value::Array(managed_items)) => {
            if managed_items.is_empty() {
                target_items.clear();
                return;
            }
            if managed_items
                .iter()
                .all(|item| merge_identity(item).is_some())
            {
                let mut previous = std::mem::take(target_items)
                    .into_iter()
                    .filter_map(|item| merge_identity(&item).map(|identity| (identity, item)))
                    .collect::<HashMap<_, _>>();
                *target_items = managed_items
                    .iter()
                    .map(|managed_item| {
                        let identity = merge_identity(managed_item)
                            .expect("identity was checked for every managed item");
                        let mut merged = previous
                            .remove(&identity)
                            .unwrap_or_else(|| Value::Object(Map::new()));
                        merge_managed_value(&mut merged, managed_item);
                        merged
                    })
                    .collect();
            } else if managed_items.iter().all(Value::is_object) {
                let previous = std::mem::take(target_items);
                *target_items = managed_items
                    .iter()
                    .enumerate()
                    .map(|(index, managed_item)| {
                        let mut merged = previous
                            .get(index)
                            .filter(|item| item.is_object())
                            .cloned()
                            .unwrap_or_else(|| Value::Object(Map::new()));
                        merge_managed_value(&mut merged, managed_item);
                        merged
                    })
                    .collect();
            } else {
                *target_items = managed_items.clone();
            }
        }
        (target, managed) => *target = managed.clone(),
    }
}

fn merge_identity(value: &Value) -> Option<String> {
    let object = value.as_object()?;
    if let Some(id) = object.get("id") {
        return match id {
            Value::String(value) => Some(format!("s:{value}")),
            Value::Number(_) => value_i64(id, "id").ok().map(|value| format!("n:{value}")),
            _ => None,
        };
    }
    let game_id = object.get("gameId")?.as_str()?;
    if let Some(metric_key) = object.get("metricKey").and_then(Value::as_str) {
        Some(format!("game-stat:{game_id}\0{metric_key}"))
    } else {
        Some(format!("game-state:{game_id}"))
    }
}

fn decode_thoughts(items: &[Value]) -> Result<Vec<Thought>, BackupError> {
    if items.len() > MAX_THOUGHTS {
        return Err(invalid("Backup contains too many thoughts"));
    }
    let mut ids = HashSet::with_capacity(items.len());
    items
        .iter()
        .enumerate()
        .map(|(index, value)| {
            let item = object_at(value, "thoughts", index)?;
            let id = required_i64(item, "id")?;
            require_positive_unique(id, &mut ids, "thought", index)?;
            let content = required_string(item, "content")?.to_owned();
            require_utf16_len(
                &content,
                MAX_THOUGHT_CHARS,
                &format!("thoughts[{index}].content"),
            )?;
            let created_at = required_i64(item, "createdAt")?;
            let updated_at = required_i64(item, "updatedAt")?;
            let deleted_at = required_nullable_i64(item, "deletedAt")?;
            validate_timestamps(created_at, updated_at, deleted_at, "thoughts", index)?;
            let category_id = required_nullable_i64(item, "categoryId")?;
            if category_id.is_some_and(|value| value <= 0) {
                return Err(invalid(format!(
                    "thoughts[{index}].categoryId must be positive"
                )));
            }
            Ok(Thought {
                id,
                content,
                created_at,
                updated_at,
                pinned: required_bool(item, "pinned")?,
                deleted_at,
                sort_order: required_i64(item, "sortOrder")?,
                category_id,
                highlighted: required_bool(item, "highlighted")?,
            })
        })
        .collect()
}

fn decode_categories(items: &[Value]) -> Result<Vec<ThoughtCategory>, BackupError> {
    if items.len() > MAX_CATEGORIES {
        return Err(invalid("Backup contains too many categories"));
    }
    let mut ids = HashSet::with_capacity(items.len());
    let mut names = HashSet::with_capacity(items.len());
    items
        .iter()
        .enumerate()
        .map(|(index, value)| {
            let item = object_at(value, "categories", index)?;
            let id = required_i64(item, "id")?;
            require_positive_unique(id, &mut ids, "category", index)?;
            let name = required_string(item, "name")?.to_owned();
            require_utf16_len(
                &name,
                MAX_CATEGORY_NAME_CHARS,
                &format!("categories[{index}].name"),
            )?;
            if name.trim().is_empty() || !names.insert(name.to_lowercase()) {
                return Err(invalid(format!(
                    "categories[{index}].name is blank or duplicated"
                )));
            }
            let created_at = required_i64(item, "createdAt")?;
            let updated_at = required_i64(item, "updatedAt")?;
            validate_timestamps(created_at, updated_at, None, "categories", index)?;
            Ok(ThoughtCategory {
                id,
                name,
                color_argb: required_i32(item, "colorArgb")?,
                sort_order: required_i64(item, "sortOrder")?,
                created_at,
                updated_at,
            })
        })
        .collect()
}

fn validate_favorites(items: &[Value]) -> Result<usize, BackupError> {
    if items.len() > MAX_FAVORITES {
        return Err(invalid("Backup contains too many favorites"));
    }
    let mut urls = HashSet::with_capacity(items.len());
    for (index, value) in items.iter().enumerate() {
        let item = object_at(value, "favorites", index)?;
        let url = required_string(item, "url")?;
        require_utf16_len(url, MAX_URL_CHARS, &format!("favorites[{index}].url"))?;
        if !is_http_url(url) || !urls.insert(url) {
            return Err(invalid(format!(
                "favorites[{index}].url is invalid or duplicated"
            )));
        }
        let title = required_string(item, "title")?;
        require_utf16_len(title, MAX_TITLE_CHARS, &format!("favorites[{index}].title"))?;
        let last_visited_at = required_i64(item, "lastVisitedAt")?;
        require_nonnegative(
            last_visited_at,
            &format!("favorites[{index}].lastVisitedAt"),
        )?;
        let visit_count = required_i64(item, "visitCount")?;
        if !(1..=i32::MAX as i64).contains(&visit_count) {
            return Err(invalid(format!(
                "favorites[{index}].visitCount is out of range"
            )));
        }
        if !required_bool(item, "favorite")? {
            return Err(invalid(format!("favorites[{index}].favorite must be true")));
        }
    }
    Ok(items.len())
}

fn decode_date_records(items: &[Value]) -> Result<Vec<DateRecord>, BackupError> {
    if items.len() > MAX_DATE_RECORDS {
        return Err(invalid("Backup contains too many date records"));
    }
    let mut ids = HashSet::with_capacity(items.len());
    items
        .iter()
        .enumerate()
        .map(|(index, value)| {
            let item = object_at(value, "dateRecords", index)?;
            let id = required_i64(item, "id")?;
            require_positive_unique(id, &mut ids, "date record", index)?;
            let name = required_string(item, "name")?.to_owned();
            require_utf16_len(
                &name,
                MAX_DATE_NAME_CHARS,
                &format!("dateRecords[{index}].name"),
            )?;
            if name.trim().is_empty() {
                return Err(invalid(format!("dateRecords[{index}].name is blank")));
            }
            let icon = required_string(item, "icon")?.to_owned();
            require_utf16_len(
                &icon,
                MAX_DATE_ICON_CHARS,
                &format!("dateRecords[{index}].icon"),
            )?;
            if icon.trim().is_empty() {
                return Err(invalid(format!("dateRecords[{index}].icon is blank")));
            }
            let date_iso = required_string(item, "dateIso")?.to_owned();
            require_date_iso(&date_iso, &format!("dateRecords[{index}].dateIso"))?;
            let created_at = required_i64(item, "createdAt")?;
            let updated_at = required_i64(item, "updatedAt")?;
            validate_timestamps(created_at, updated_at, None, "dateRecords", index)?;
            Ok(DateRecord {
                id,
                name,
                icon,
                date_iso,
                created_at,
                updated_at,
            })
        })
        .collect()
}

fn decode_poems(items: &[Value]) -> Result<Vec<SavedPoem>, BackupError> {
    if items.len() > MAX_POEMS {
        return Err(invalid("Backup contains too many poems"));
    }
    let mut ids = HashSet::with_capacity(items.len());
    items
        .iter()
        .enumerate()
        .map(|(index, value)| {
            let item = object_at(value, "poems", index)?;
            let id = required_i64(item, "id")?;
            require_positive_unique(id, &mut ids, "poem", index)?;
            let content = required_string(item, "content")?.to_owned();
            require_utf16_len(
                &content,
                MAX_POEM_CONTENT_CHARS,
                &format!("poems[{index}].content"),
            )?;
            if content.trim().is_empty() {
                return Err(invalid(format!("poems[{index}].content is blank")));
            }
            let source = required_string(item, "source")?.to_owned();
            require_utf16_len(
                &source,
                MAX_POEM_SOURCE_CHARS,
                &format!("poems[{index}].source"),
            )?;
            let created_at = required_i64(item, "createdAt")?;
            let updated_at = required_i64(item, "updatedAt")?;
            validate_timestamps(created_at, updated_at, None, "poems", index)?;
            Ok(SavedPoem {
                id,
                content,
                source,
                created_at,
                updated_at,
                sort_order: required_i64(item, "sortOrder")?,
                category_id: required_nullable_i64(item, "categoryId")?,
            })
        })
        .collect()
}

fn decode_poetry_categories(items: &[Value]) -> Result<Vec<PoetryCategory>, BackupError> {
    items
        .iter()
        .enumerate()
        .map(|(index, value)| {
            let item = object_at(value, "poetryCategories", index)?;
            Ok(PoetryCategory {
                id: required_i64(item, "id")?,
                name: required_string(item, "name")?.to_owned(),
                color_argb: required_i32(item, "colorArgb")?,
                sort_order: required_i64(item, "sortOrder")?,
                created_at: required_i64(item, "createdAt")?,
                updated_at: required_i64(item, "updatedAt")?,
            })
        })
        .collect()
}

fn validate_poetry_categories(items: &[Value]) -> Result<HashSet<i64>, BackupError> {
    if items.len() > MAX_POETRY_CATEGORIES {
        return Err(invalid("Backup contains too many poetry categories"));
    }
    let mut ids = HashSet::with_capacity(items.len());
    let mut names = HashSet::with_capacity(items.len());
    for (index, value) in items.iter().enumerate() {
        let item = object_at(value, "poetryCategories", index)?;
        let id = required_i64(item, "id")?;
        require_positive_unique(id, &mut ids, "poetry category", index)?;
        let name = require_string_limit(item, "name", MAX_POETRY_CATEGORY_NAME_CHARS)?;
        if name.trim().is_empty() || !names.insert(name.to_lowercase()) {
            return Err(invalid(format!(
                "poetryCategories[{index}].name is blank or duplicated"
            )));
        }
        required_i32(item, "colorArgb")?;
        required_i64(item, "sortOrder")?;
        let created_at = required_i64(item, "createdAt")?;
        let updated_at = required_i64(item, "updatedAt")?;
        validate_timestamps(created_at, updated_at, None, "poetryCategories", index)?;
    }
    Ok(ids)
}

fn validate_v27_poem_fields(
    items: &[Value],
    poetry_category_ids: &HashSet<i64>,
) -> Result<(), BackupError> {
    for (index, value) in items.iter().enumerate() {
        let item = object_at(value, "poems", index)?;
        required_i64(item, "sortOrder")?;
        let category_id = required_nullable_i64(item, "categoryId")?;
        if category_id.is_some_and(|id| !poetry_category_ids.contains(&id)) {
            return Err(invalid(format!(
                "poems[{index}].categoryId references a missing poetry category"
            )));
        }
    }
    Ok(())
}

fn validate_vault(vault: &Map<String, Value>) -> Result<(), BackupError> {
    let active = validate_optional_vault_key(vault, "active", false)?;
    let pending = validate_optional_vault_key(vault, "pending", true)?;
    let items = required_array(vault, "items")?;
    if items.len() > MAX_VAULT_ITEMS {
        return Err(invalid("Backup contains too many Vault rows"));
    }
    let mut ids = HashSet::with_capacity(items.len());
    for (index, value) in items.iter().enumerate() {
        let item = object_at(value, "vault.items", index)?;
        let id = required_i64(item, "id")?;
        if (id <= 0 && id != i64::MIN) || !ids.insert(id) {
            return Err(invalid(format!(
                "vault.items[{index}].id is invalid or duplicated"
            )));
        }
        let cipher = require_string_limit(item, "cipherText", MAX_VAULT_CIPHER_CHARS)?;
        let iv = require_string_limit(item, "iv", MAX_VAULT_IV_CHARS)?;
        validate_aes_gcm_base64(cipher, iv, &format!("vault.items[{index}]"))?;
        for field in ["createdAt", "updatedAt", "sortOrder"] {
            require_nonnegative(
                required_i64(item, field)?,
                &format!("vault.items[{index}].{field}"),
            )?;
        }
    }
    if !active && (pending || !items.is_empty()) {
        return Err(invalid(
            "Vault rows or pending metadata exist without active metadata",
        ));
    }
    Ok(())
}

fn validate_optional_vault_key(
    vault: &Map<String, Value>,
    field: &str,
    generation_required: bool,
) -> Result<bool, BackupError> {
    let value = vault
        .get(field)
        .ok_or_else(|| invalid(format!("Missing field: {field}")))?;
    if value.is_null() {
        return Ok(false);
    }
    let key = value
        .as_object()
        .ok_or_else(|| invalid(format!("vault.{field} must be an object or null")))?;
    let salt = require_string_limit(key, "saltBase64", MAX_VAULT_SALT_CHARS)?;
    let decoded_salt = BASE64_STANDARD
        .decode(salt)
        .map_err(|_| invalid(format!("vault.{field}.saltBase64 is invalid")))?;
    if !(1..=1_024).contains(&decoded_salt.len()) {
        return Err(invalid(format!(
            "vault.{field}.saltBase64 has an invalid size"
        )));
    }
    let iterations = required_i32(key, "iterations")?;
    if !(1..=10_000_000).contains(&iterations) {
        return Err(invalid(format!("vault.{field}.iterations is invalid")));
    }
    let generation = validate_nullable_string(key, "generationId", MAX_VAULT_GENERATION_CHARS)?;
    if generation_required && generation.is_none() {
        return Err(invalid("Pending Vault generation is missing"));
    }
    if generation.is_some_and(|value| {
        value.is_empty()
            || !value
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
    }) {
        return Err(invalid(format!("vault.{field}.generationId is invalid")));
    }
    let cipher = require_string_limit(key, "verifierCipher", MAX_VAULT_CIPHER_CHARS)?;
    let iv = require_string_limit(key, "verifierIv", MAX_VAULT_IV_CHARS)?;
    validate_aes_gcm_base64(cipher, iv, &format!("vault.{field}.verifier"))?;
    Ok(true)
}

fn validate_aes_gcm_base64(cipher: &str, iv: &str, field: &str) -> Result<(), BackupError> {
    if cipher.trim().is_empty() {
        return Err(invalid(format!("{field} ciphertext is blank")));
    }
    let cipher_bytes = BASE64_STANDARD
        .decode(cipher)
        .map_err(|_| invalid(format!("{field} ciphertext is invalid Base64")))?;
    let iv_bytes = BASE64_STANDARD
        .decode(iv)
        .map_err(|_| invalid(format!("{field} IV is invalid Base64")))?;
    if cipher_bytes.len() < 16 || iv_bytes.len() != 12 {
        return Err(invalid(format!("{field} has an invalid AES-GCM payload")));
    }
    Ok(())
}

fn validate_game_states(items: &[Value]) -> Result<(), BackupError> {
    const GAME_IDS: [&str; 7] = [
        "2048",
        "2048_5",
        "2048_6",
        "snake",
        "tetris",
        "minesweeper",
        "spider",
    ];
    if items.len() > MAX_GAME_STATES {
        return Err(invalid("Backup contains too many game states"));
    }
    let mut ids = HashSet::with_capacity(items.len());
    for (index, value) in items.iter().enumerate() {
        let item = object_at(value, "gameStates", index)?;
        let game_id = require_string_limit(item, "gameId", MAX_GAME_ID_CHARS)?;
        if !GAME_IDS.contains(&game_id) || !ids.insert(game_id) {
            return Err(invalid(format!(
                "gameStates[{index}].gameId is unsupported or duplicated"
            )));
        }
        require_nonnegative(
            i64::from(required_i32(item, "highScore")?),
            &format!("gameStates[{index}].highScore"),
        )?;
        require_nonnegative(
            required_i64(item, "updatedAt")?,
            &format!("gameStates[{index}].updatedAt"),
        )?;
        if let Some(save) = validate_nullable_string(item, "saveJson", MAX_GAME_SAVE_CHARS)? {
            if save.trim().is_empty() {
                return Err(invalid(format!("gameStates[{index}].saveJson is blank")));
            }
            let parsed: Value = serde_json::from_str(save)
                .map_err(|_| invalid(format!("gameStates[{index}].saveJson is invalid JSON")))?;
            if !parsed.is_object() {
                return Err(invalid(format!(
                    "gameStates[{index}].saveJson must be an object"
                )));
            }
        }
    }
    Ok(())
}

fn decode_game_states(items: &[Value]) -> Result<Vec<GameBackupState>, BackupError> {
    items
        .iter()
        .enumerate()
        .map(|(index, value)| {
            let item = object_at(value, "gameStates", index)?;
            Ok(GameBackupState {
                game_id: required_string(item, "gameId")?.to_owned(),
                high_score: i64::from(required_i32(item, "highScore")?),
                save_json: validate_nullable_string(item, "saveJson", MAX_GAME_SAVE_CHARS)?
                    .map(str::to_owned),
                updated_at: required_i64(item, "updatedAt")?,
            })
        })
        .collect()
}

fn validate_game_statistics(items: &[Value]) -> Result<(), BackupError> {
    if items.len() > MAX_GAME_STATISTICS {
        return Err(invalid("Backup contains too many game statistics"));
    }
    let mut keys = HashSet::with_capacity(items.len());
    for (index, value) in items.iter().enumerate() {
        let item = object_at(value, "gameStatistics", index)?;
        let game_id = require_string_limit(item, "gameId", MAX_GAME_ID_CHARS)?;
        let metric = require_string_limit(item, "metricKey", MAX_GAME_ID_CHARS)?;
        if !supports_game_statistic(game_id, metric) || !keys.insert(format!("{game_id}\0{metric}"))
        {
            return Err(invalid(format!(
                "gameStatistics[{index}] has an unsupported or duplicated key"
            )));
        }
        require_nonnegative(
            required_i64(item, "value")?,
            &format!("gameStatistics[{index}].value"),
        )?;
        require_nonnegative(
            required_i64(item, "updatedAt")?,
            &format!("gameStatistics[{index}].updatedAt"),
        )?;
    }
    Ok(())
}

fn decode_game_statistics(items: &[Value]) -> Result<Vec<GameBackupStatistic>, BackupError> {
    items
        .iter()
        .enumerate()
        .map(|(index, value)| {
            let item = object_at(value, "gameStatistics", index)?;
            Ok(GameBackupStatistic {
                game_id: required_string(item, "gameId")?.to_owned(),
                metric_key: required_string(item, "metricKey")?.to_owned(),
                value: required_i64(item, "value")?,
                updated_at: required_i64(item, "updatedAt")?,
            })
        })
        .collect()
}

fn supports_game_statistic(game_id: &str, metric: &str) -> bool {
    let outcome = matches!(metric, "wins" | "losses");
    match game_id {
        "2048" | "2048_5" | "2048_6" => {
            outcome
                || matches!(
                    metric,
                    "moveAttempts" | "effectiveMoves" | "merges" | "highestTile"
                )
        }
        "snake" => matches!(metric, "losses" | "foodEaten" | "maxLength"),
        "tetris" => matches!(
            metric,
            "losses" | "piecesLocked" | "linesCleared" | "tetrises"
        ),
        "minesweeper" => {
            outcome || matches!(metric, "minesCellsRevealed" | "minesSwept" | "flagsPlaced")
        }
        "spider" => outcome || matches!(metric, "spiderCardMoves" | "spiderDeals" | "spiderUndos"),
        _ => false,
    }
}

fn validate_reader_progress(items: &[Value]) -> Result<Vec<ReaderProgressRecord>, BackupError> {
    if items.len() > MAX_READER_PROGRESS_RECORDS {
        return Err(invalid("Backup contains too many reader progress records"));
    }
    let mut keys = HashSet::with_capacity(items.len());
    let mut records = Vec::with_capacity(items.len());
    for (index, value) in items.iter().enumerate() {
        let item = object_at(value, "readerProgress", index)?;
        let fingerprint = required_string(item, "fingerprint")?;
        if fingerprint.len() != 64
            || !fingerprint
                .bytes()
                .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
        {
            return Err(invalid(format!(
                "readerProgress[{index}].fingerprint is invalid"
            )));
        }
        let book_type = match required_string(item, "type")? {
            "TXT" => ReaderProgressBookType::Txt,
            "PDF" => ReaderProgressBookType::Pdf,
            _ => {
                return Err(invalid(format!(
                    "readerProgress[{index}].type has an unsupported value"
                )));
            }
        };
        let text_page_index = required_i32(item, "textPageIndex")?;
        if !(-1..MAX_READER_TEXT_PAGES).contains(&text_page_index) {
            return Err(invalid(format!(
                "readerProgress[{index}].textPageIndex is out of range"
            )));
        }
        let text_paragraph_index = required_i32(item, "textParagraphIndex")?;
        if !(0..MAX_READER_TEXT_PARAGRAPHS).contains(&text_paragraph_index) {
            return Err(invalid(format!(
                "readerProgress[{index}].textParagraphIndex is out of range"
            )));
        }
        let pdf_page_index = required_i32(item, "pdfPageIndex")?;
        if !(0..MAX_READER_PDF_PAGES).contains(&pdf_page_index) {
            return Err(invalid(format!(
                "readerProgress[{index}].pdfPageIndex is out of range"
            )));
        }
        let total_pages = required_i32(item, "totalPages")?;
        let total_page_limit = match book_type {
            ReaderProgressBookType::Txt => MAX_READER_TEXT_PAGES,
            ReaderProgressBookType::Pdf => MAX_READER_PDF_PAGES,
        };
        if !(0..=total_page_limit).contains(&total_pages) {
            return Err(invalid(format!(
                "readerProgress[{index}].totalPages is out of range"
            )));
        }
        let updated_at = required_i64(item, "updatedAt")?;
        require_nonnegative(updated_at, &format!("readerProgress[{index}].updatedAt"))?;
        let key = format!("{fingerprint}\0{}", required_string(item, "type")?);
        if !keys.insert(key) {
            return Err(invalid("Backup contains duplicate reader progress keys"));
        }
        records.push(ReaderProgressRecord {
            fingerprint: fingerprint.to_owned(),
            book_type,
            text_page_index,
            text_paragraph_index,
            pdf_page_index,
            total_pages,
            updated_at,
        });
    }
    Ok(records)
}

fn validate_usage_devices(items: &[Value]) -> Result<(), BackupError> {
    if items.len() > MAX_USAGE_DEVICES {
        return Err(invalid("Backup contains too many usage devices"));
    }
    let mut ids = HashSet::with_capacity(items.len());
    for (index, value) in items.iter().enumerate() {
        let encoded_size = serde_json::to_vec(value)?.len();
        if encoded_size > MAX_USAGE_DEVICE_JSON_BYTES {
            return Err(invalid(format!(
                "usageDevices[{index}] exceeds its per-device size limit"
            )));
        }
        let device = object_at(value, "usageDevices", index)?;
        require_exact_keys(
            device,
            &[
                "schemaVersion",
                "deviceId",
                "deviceName",
                "platform",
                "updatedAtEpochMillis",
                "history",
            ],
            &format!("usageDevices[{index}]"),
        )?;
        if required_i32(device, "schemaVersion")? != 1 {
            return Err(invalid(format!(
                "usageDevices[{index}] has an unsupported schema"
            )));
        }
        let device_id = required_string(device, "deviceId")?;
        let normalized_id = Uuid::parse_str(device_id)
            .map_err(|_| invalid(format!("usageDevices[{index}].deviceId is invalid")))?
            .hyphenated()
            .to_string();
        if normalized_id != device_id || !ids.insert(device_id) {
            return Err(invalid(format!(
                "usageDevices[{index}].deviceId is noncanonical or duplicated"
            )));
        }
        let name = required_string(device, "deviceName")?;
        if name.trim() != name
            || name.is_empty()
            || name.chars().count() > 80
            || name.chars().any(char::is_control)
        {
            return Err(invalid(format!(
                "usageDevices[{index}].deviceName is invalid"
            )));
        }
        let platform = required_string(device, "platform")?;
        if platform.is_empty()
            || platform.len() > 32
            || !platform.bytes().enumerate().all(|(offset, byte)| {
                if offset == 0 {
                    byte.is_ascii_lowercase()
                } else {
                    byte.is_ascii_lowercase()
                        || byte.is_ascii_digit()
                        || matches!(byte, b'_' | b'-')
                }
            })
        {
            return Err(invalid(format!(
                "usageDevices[{index}].platform is invalid"
            )));
        }
        let updated_at = required_i64(device, "updatedAtEpochMillis")?;
        require_nonnegative(
            updated_at,
            &format!("usageDevices[{index}].updatedAtEpochMillis"),
        )?;
        let newest = validate_usage_history(
            required_object(device, "history")?,
            &format!("usageDevices[{index}].history"),
        )?;
        if updated_at < newest {
            return Err(invalid(format!(
                "usageDevices[{index}] timestamp predates its history"
            )));
        }
    }
    Ok(())
}

fn validate_usage_history(history: &Map<String, Value>, field: &str) -> Result<i64, BackupError> {
    let schema_version = required_i32(history, "schemaVersion")?;
    match schema_version {
        1 => require_exact_keys(
            history,
            &["schemaVersion", "trackingStartedOn", "days"],
            field,
        )?,
        2..=4 => require_exact_keys(
            history,
            &[
                "schemaVersion",
                "trackingStartedOn",
                "backfillCompletedThrough",
                "days",
            ],
            field,
        )?,
        _ => return Err(invalid(format!("{field}.schemaVersion is unsupported"))),
    }
    let tracking = validate_nullable_iso_date(history, "trackingStartedOn", field)?;
    if schema_version == 4 {
        validate_nullable_iso_date(history, "backfillCompletedThrough", field)?;
    }
    let days = required_array(history, "days")?;
    if days.len() > MAX_STATISTICS_DAYS {
        return Err(invalid(format!("{field}.days contains too many entries")));
    }
    let mut dates = HashSet::with_capacity(days.len());
    let mut newest = 0_i64;
    for (index, value) in days.iter().enumerate() {
        let day = object_at(value, &format!("{field}.days"), index)?;
        require_exact_keys(
            day,
            &["date", "zoneId", "state", "collectedAtEpochMillis", "apps"],
            &format!("{field}.days[{index}]"),
        )?;
        let date_text = required_string(day, "date")?;
        let date = parse_iso_date(date_text, &format!("{field}.days[{index}].date"))?;
        if !dates.insert(date) || tracking.is_some_and(|start| date < start) {
            return Err(invalid(format!(
                "{field}.days[{index}].date is duplicated or predates tracking"
            )));
        }
        let zone = required_string(day, "zoneId")?;
        if zone.is_empty() || utf16_len(zone) > MAX_ZONE_ID_CHARS || !is_java_zone_id(zone) {
            return Err(invalid(format!("{field}.days[{index}].zoneId is invalid")));
        }
        require_enum(day, "state", &["OPEN", "FINAL"])?;
        let collected = required_i64(day, "collectedAtEpochMillis")?;
        require_nonnegative(
            collected,
            &format!("{field}.days[{index}].collectedAtEpochMillis"),
        )?;
        newest = newest.max(collected);
        let apps = required_array(day, "apps")?;
        if apps.len() > MAX_APPS_PER_DAY {
            return Err(invalid(format!(
                "{field}.days[{index}].apps contains too many entries"
            )));
        }
        let mut packages = HashSet::with_capacity(apps.len());
        for (app_index, app_value) in apps.iter().enumerate() {
            let app = object_at(app_value, &format!("{field}.days[{index}].apps"), app_index)?;
            require_exact_keys(
                app,
                &["packageName", "foregroundMillis"],
                &format!("{field}.days[{index}].apps[{app_index}]"),
            )?;
            let package = required_string(app, "packageName")?;
            if package.trim().is_empty()
                || utf16_len(package) > MAX_PACKAGE_NAME_CHARS
                || package
                    .chars()
                    .any(|ch| ch.is_control() || ch.is_whitespace())
                || !packages.insert(package)
            {
                return Err(invalid(format!(
                    "{field}.days[{index}].apps[{app_index}].packageName is invalid or duplicated"
                )));
            }
            let millis = required_i64(app, "foregroundMillis")?;
            if !(0..=MAX_FOREGROUND_MILLIS_PER_APP_DAY).contains(&millis) {
                return Err(invalid(format!(
                    "{field}.days[{index}].apps[{app_index}].foregroundMillis is out of range"
                )));
            }
        }
    }
    if tracking.is_none() && !days.is_empty() {
        return Err(invalid(format!(
            "{field}.trackingStartedOn is required when days exist"
        )));
    }
    Ok(newest)
}

fn is_java_zone_id(value: &str) -> bool {
    if value.parse::<Tz>().is_ok() || value == "Z" {
        return true;
    }
    let offset = ["UTC", "GMT", "UT"]
        .into_iter()
        .find_map(|prefix| value.strip_prefix(prefix))
        .unwrap_or(value);
    if offset.is_empty() {
        return matches!(value, "UTC" | "GMT" | "UT");
    }
    is_java_zone_offset(offset)
}

fn is_java_zone_offset(value: &str) -> bool {
    let Some(sign) = value.as_bytes().first().copied() else {
        return false;
    };
    if !matches!(sign, b'+' | b'-') {
        return false;
    }
    let digits = &value[1..];
    let components = if digits.contains(':') {
        digits.split(':').collect::<Vec<_>>()
    } else {
        match digits.len() {
            1 | 2 => vec![digits],
            4 => vec![&digits[..2], &digits[2..]],
            6 => vec![&digits[..2], &digits[2..4], &digits[4..]],
            _ => return false,
        }
    };
    if components.is_empty()
        || components.len() > 3
        || components
            .iter()
            .any(|part| part.is_empty() || !part.bytes().all(|byte| byte.is_ascii_digit()))
    {
        return false;
    }
    let Ok(hours) = components[0].parse::<u8>() else {
        return false;
    };
    let minutes = components
        .get(1)
        .and_then(|part| part.parse::<u8>().ok())
        .unwrap_or(0);
    let seconds = components
        .get(2)
        .and_then(|part| part.parse::<u8>().ok())
        .unwrap_or(0);
    hours <= 18 && minutes <= 59 && seconds <= 59 && (hours != 18 || minutes + seconds == 0)
}

fn validate_nullable_iso_date(
    object: &Map<String, Value>,
    key: &str,
    field: &str,
) -> Result<Option<NaiveDate>, BackupError> {
    let value = object
        .get(key)
        .ok_or_else(|| invalid(format!("{field}.{key} is missing")))?;
    if value.is_null() {
        Ok(None)
    } else {
        let text = value
            .as_str()
            .ok_or_else(|| invalid(format!("{field}.{key} must be a string or null")))?;
        parse_iso_date(text, &format!("{field}.{key}")).map(Some)
    }
}

fn parse_iso_date(value: &str, field: &str) -> Result<NaiveDate, BackupError> {
    if value.len() != 10 {
        return Err(invalid(format!("{field} must use yyyy-MM-dd")));
    }
    let date = NaiveDate::parse_from_str(value, "%Y-%m-%d")
        .map_err(|_| invalid(format!("{field} must be a valid yyyy-MM-dd date")))?;
    if date.format("%Y-%m-%d").to_string() != value {
        return Err(invalid(format!("{field} must use canonical yyyy-MM-dd")));
    }
    Ok(date)
}

fn require_exact_keys(
    object: &Map<String, Value>,
    expected: &[&str],
    field: &str,
) -> Result<(), BackupError> {
    if object.len() != expected.len() || expected.iter().any(|key| !object.contains_key(*key)) {
        return Err(invalid(format!(
            "{field} contains missing or unknown fields"
        )));
    }
    Ok(())
}

fn validate_full_v28_settings(
    settings: &Map<String, Value>,
) -> Result<Vec<CloudSyncConfig>, BackupError> {
    require_enum(
        settings,
        "visualStyle",
        &["MATERIAL", "LIQUID_GLASS", "ORGANIC_FUTURE", "CUSTOM"],
    )?;
    validate_custom_theme(required_object(settings, "customTheme")?)?;
    require_enum(settings, "darkMode", &["SYSTEM", "LIGHT", "DARK"])?;
    require_enum(settings, "appLanguage", &["CHINESE", "ENGLISH"])?;
    required_i32(settings, "themeColorArgb")?;
    let secondary_colors = required_array(settings, "themeSecondaryColorsArgb")?;
    if !(2..=5).contains(&secondary_colors.len()) {
        return Err(invalid(
            "themeSecondaryColorsArgb must contain 2 to 5 items",
        ));
    }
    for (index, color) in secondary_colors.iter().enumerate() {
        value_i32(color, &format!("themeSecondaryColorsArgb[{index}]"))?;
    }
    require_number_range(
        settings,
        "fontScale",
        f64::from(0.8_f32),
        f64::from(1.3_f32),
    )?;
    for field in [
        "compactMode",
        "tutorialModeEnabled",
        "useChineseLauncherName",
        "cloudSyncEnabled",
        "mealImageCompressionEnabled",
        "saveOriginalToGallery",
        "photoLocationEnabled",
        "browserDesktopMode",
        "poetryShowSource",
        "poetryShowQuoteMark",
        "poetrySevenCharacterWrapEnabled",
        "mealCalendarShowCaptions",
        "mealCalendarWrapEnabled",
        "mealButtonsUseIcons",
        "homeWidgetBordersEnabled",
        "rssShowSummaries",
        "aiAllowInsecureHttp",
        "calorieEstimationEnabled",
        "usageTrackingEnabled",
        "stepTrackingEnabled",
        "bottomNavShowLabels",
        "morePageShowDescriptions",
        "musicVisualizerEnabled",
    ] {
        required_bool(settings, field)?;
    }
    require_enum(
        settings,
        "launcherIcon",
        &["CURRENT", "MAGIC_BOOK", "DESK_CUBBY"],
    )?;
    let cloud_sync_configs =
        decode_cloud_sync_configs(required_array(settings, "cloudSyncConfigs")?)?;
    if let Some(uri) = validate_nullable_string(settings, "backgroundImageUri", MAX_URL_CHARS)? {
        if !uri.starts_with("content://") {
            return Err(invalid("backgroundImageUri must be a content URI"));
        }
    }
    required_android_float_range(settings, "backgroundImageOpacity", 0.0, 1.0)?;
    required_android_float_range(settings, "backgroundImageBlurDp", 0.0, 40.0)?;
    validate_nullable_string_value(settings, "diaryTreeUri")?;
    validate_nullable_string_value(settings, "mediaTreeUri")?;
    validate_nullable_string_value(settings, "notesTreeUri")?;
    require_string_limit(settings, "fileNamePattern", 1_024)?;
    require_string_limit(settings, "markdownTemplate", MAX_SETTING_STRING_CHARS)?;
    require_string_limit(settings, "imageNamePattern", 1_024)?;
    required_coerced_i32(settings, "imageMaxWidthDp", 120, 2_400)?;
    required_coerced_i32(settings, "imageMaxHeightDp", 120, 2_400)?;
    validate_markdown_heading_sizes(required_array(settings, "markdownHeadingSizesSp")?)?;
    required_coerced_i32(settings, "mealImageCompressionQuality", 30, 95)?;
    let browser_home_url = require_string_limit(settings, "browserHomeUrl", MAX_URL_CHARS)?;
    if !is_browser_url(browser_home_url) {
        return Err(invalid("browserHomeUrl is invalid"));
    }
    if let Some(last_browser_url) =
        validate_nullable_string(settings, "lastBrowserUrl", MAX_URL_CHARS)?
    {
        if !is_browser_url(last_browser_url) {
            return Err(invalid("lastBrowserUrl is invalid"));
        }
    }
    require_enum(settings, "browserTheme", &["SYSTEM", "LIGHT", "DARK"])?;
    require_number(settings, "thoughtSplitRatio")?;
    required_coerced_i32(settings, "thoughtRowHeightDp", 48, 120)?;
    require_enum(settings, "thoughtReopenMode", &["LAST_VISITED", "ALL"])?;
    require_enum(settings, "thoughtDisplayMode", &["SINGLE_LINE", "FULL"])?;
    required_i32(settings, "thoughtHighlightColorArgb")?;
    required_coerced_i32(settings, "thoughtEditorMaxHeightDp", 96, 400)?;
    required_coerced_i32(settings, "vaultRowHeightDp", 48, 120)?;
    validate_nullable_string(settings, "poetryFontUri", MAX_URL_CHARS)?;
    require_number_range(settings, "poetryFontSizeSp", 14.0, 36.0)?;
    require_number_range(settings, "poetryLineSpacing", 1.0, 2.0)?;
    require_enum(settings, "poetryTextAlignment", &["START", "CENTER"])?;
    required_coerced_i32(settings, "mealCalendarImageMaxHeightDp", 80, 320)?;
    require_enum(
        settings,
        "mealCalendarPhotosPerRow",
        &["TWO", "THREE", "SMART"],
    )?;
    validate_meal_photo_filter(required_object(settings, "mealPhotoFilter")?)?;
    let user_name = required_string(settings, "userName")?;
    if user_name.chars().count() > 32 {
        return Err(invalid("userName is too long"));
    }
    validate_home_greetings(required_array(settings, "homeGreetings")?)?;
    validate_meal_icons(required_array(settings, "mealButtonIcons")?)?;
    validate_daily_templates(required_array(settings, "dailyEventTemplates")?)?;
    validate_rss_subscriptions(required_array(settings, "rssSubscriptions")?)?;
    required_coerced_i32(settings, "rssMaxItemsPerFeed", 10, 200)?;
    require_string_limit(settings, "aiEndpointUrl", MAX_URL_CHARS)?;
    require_string_limit(settings, "aiModel", 512)?;
    require_string_limit(settings, "aiSystemPrompt", 20_000)?;
    require_number_range(settings, "aiTemperature", 0.0, 2.0)?;
    validate_ai_configs(required_array(settings, "aiConfigs")?)?;
    validate_nullable_string(settings, "aiChatConfigId", 80)?;
    validate_nullable_string(settings, "calorieTextConfigId", 80)?;
    validate_nullable_string(settings, "calorieImageConfigId", 80)?;
    require_string_limit(settings, "calorieVisionPrompt", 20_000)?;
    require_string_limit(settings, "calorieTextPrompt", 20_000)?;
    validate_nav_items(required_array(settings, "navItems")?)?;
    validate_more_page_order(required_array(settings, "morePageOrder")?)?;
    require_enum(settings, "defaultPage", &nav_ids())?;
    require_enum(
        settings,
        "musicVisualizerStyle",
        &["BARS", "WAVEFORM", "CURVE"],
    )?;
    require_enum(
        settings,
        "musicVisualizerFrequencyMode",
        &["ADAPTIVE", "MANUAL"],
    )?;
    let minimum_frequency = required_i32(settings, "musicVisualizerMinFrequencyHz")?;
    if !(20..=19_999).contains(&minimum_frequency) {
        return Err(invalid("musicVisualizerMinFrequencyHz is out of range"));
    }
    let maximum_frequency = required_i32(settings, "musicVisualizerMaxFrequencyHz")?;
    if maximum_frequency <= minimum_frequency || maximum_frequency > 20_000 {
        return Err(invalid("musicVisualizerMaxFrequencyHz is out of range"));
    }
    require_enum(
        settings,
        "game2048AnimationSpeed",
        &["SLOW", "NORMAL", "FAST"],
    )?;
    validate_string_list(required_array(settings, "homeWidgets")?, "homeWidgets")?;
    validate_home_game_shortcuts(required_array(settings, "homeGameShortcuts")?)?;
    validate_string_list(
        required_array(settings, "homeWidgetTitles")?,
        "homeWidgetTitles",
    )?;
    validate_desktop_widget_configs(required_array(settings, "desktopWidgetConfigs")?)?;
    Ok(cloud_sync_configs)
}

fn validate_custom_theme(theme: &Map<String, Value>) -> Result<(), BackupError> {
    require_enum(
        theme,
        "baseStyle",
        &["MATERIAL", "LIQUID_GLASS", "ORGANIC_FUTURE"],
    )?;
    validate_custom_theme_palette(required_object(theme, "lightPalette")?, "lightPalette")?;
    validate_custom_theme_palette(required_object(theme, "darkPalette")?, "darkPalette")?;
    for (field, minimum, maximum) in [
        ("cornerRadiusDp", 0.0_f64, 40.0_f64),
        ("borderWidthDp", 0.0_f64, 4.0_f64),
        ("elevationDp", 0.0_f64, 16.0_f64),
        ("panelOpacity", f64::from(0.65_f32), 1.0_f64),
        ("spacingScale", f64::from(0.75_f32), f64::from(1.35_f32)),
        ("animationScale", 0.0_f64, 2.0_f64),
    ] {
        // Android's requiredCustomThemeFloat validates the JSON Double against the
        // Float bounds converted to Double before casting. Keep that exact order:
        // values just outside a boundary must not be rounded back into range.
        require_number_range(theme, field, minimum, maximum)?;
    }
    Ok(())
}

fn scrub_reader_progress_metadata(root: &mut Map<String, Value>) -> Result<(), BackupError> {
    const FORBIDDEN_READER_METADATA: [&str; 8] = [
        "uri",
        "bookUri",
        "coverUri",
        "title",
        "content",
        "path",
        "filePath",
        "displayName",
    ];
    let records = root
        .get_mut("readerProgress")
        .and_then(Value::as_array_mut)
        .ok_or_else(|| invalid("Backup readerProgress must be an array"))?;
    for (index, value) in records.iter_mut().enumerate() {
        let record = value
            .as_object_mut()
            .ok_or_else(|| invalid(format!("readerProgress[{index}] must be an object")))?;
        scrub_reader_metadata_object(record, &FORBIDDEN_READER_METADATA);
    }
    Ok(())
}

fn scrub_reader_metadata_object(object: &mut Map<String, Value>, forbidden: &[&str]) {
    for field in forbidden {
        object.remove(*field);
    }
    for value in object.values_mut() {
        scrub_reader_metadata_value(value, forbidden);
    }
}

fn scrub_reader_metadata_value(value: &mut Value, forbidden: &[&str]) {
    match value {
        Value::Object(object) => scrub_reader_metadata_object(object, forbidden),
        Value::Array(items) => {
            for item in items {
                scrub_reader_metadata_value(item, forbidden);
            }
        }
        _ => {}
    }
}

fn validate_custom_theme_palette(
    palette: &Map<String, Value>,
    field: &str,
) -> Result<(), BackupError> {
    for role in [
        "backgroundArgb",
        "onBackgroundArgb",
        "surfaceArgb",
        "onSurfaceArgb",
        "surfaceContainerArgb",
        "surfaceVariantArgb",
        "onSurfaceVariantArgb",
        "outlineArgb",
    ] {
        required_i32(palette, role).map_err(|_| {
            invalid(format!(
                "customTheme.{field}.{role} must be a 32-bit integer"
            ))
        })?;
    }
    Ok(())
}

fn validate_markdown_heading_sizes(items: &[Value]) -> Result<(), BackupError> {
    if items.len() != 6 {
        return Err(invalid("markdownHeadingSizesSp must contain six items"));
    }
    for (index, item) in items.iter().enumerate() {
        let value = item
            .as_number()
            .and_then(serde_json::Number::as_f64)
            .filter(|value| value.is_finite())
            .ok_or_else(|| invalid(format!("markdownHeadingSizesSp[{index}] must be finite")))?;
        let android_value = f64::from(value as f32);
        if !(12.0..=48.0).contains(&android_value) {
            return Err(invalid(format!(
                "markdownHeadingSizesSp[{index}] is out of range"
            )));
        }
    }
    Ok(())
}

fn validate_home_game_shortcuts(items: &[Value]) -> Result<(), BackupError> {
    const GAME_IDS: [&str; 7] = [
        "2048",
        "2048_5",
        "2048_6",
        "snake",
        "tetris",
        "minesweeper",
        "spider",
    ];
    for (index, item) in items.iter().enumerate() {
        let id = item
            .as_str()
            .ok_or_else(|| invalid(format!("homeGameShortcuts[{index}] must be a string")))?;
        if !GAME_IDS.contains(&id) {
            return Err(invalid(format!(
                "homeGameShortcuts[{index}] is unsupported"
            )));
        }
    }
    Ok(())
}

fn validate_desktop_widget_configs(items: &[Value]) -> Result<(), BackupError> {
    const HOME_MODULE_IDS: [&str; 16] = [
        "calendar",
        "weather",
        "poem",
        "today",
        "date_records",
        "streak",
        "month_diaries",
        "total_words",
        "recent_diary",
        "recent_thought",
        "quick_input",
        "daily_records",
        "meal_photos",
        "random_diary",
        "year_progress",
        "website",
    ];
    if items.len() > MAX_DESKTOP_WIDGET_CONFIGS {
        return Err(invalid("Too many desktop widget configurations"));
    }
    let mut ids = HashSet::with_capacity(items.len());
    for (index, value) in items.iter().enumerate() {
        let item = object_at(value, "desktopWidgetConfigs", index)?;
        let id = require_string_limit(item, "id", 80)?;
        if id.is_empty()
            || !id
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
            || !ids.insert(id)
        {
            return Err(invalid(format!(
                "desktopWidgetConfigs[{index}].id is invalid or duplicated"
            )));
        }
        let name = required_string(item, "name")?;
        if name.trim().is_empty() || name.chars().count() > 80 {
            return Err(invalid(format!(
                "desktopWidgetConfigs[{index}].name is invalid"
            )));
        }
        for field in ["widthCells", "heightCells"] {
            let cells = required_i32(item, field)?;
            if !(1..=6).contains(&cells) {
                return Err(invalid(format!(
                    "desktopWidgetConfigs[{index}].{field} is invalid"
                )));
            }
        }
        required_i32(item, "backgroundColorArgb")?;
        required_i32(item, "textColorArgb")?;
        if let Some(uri) = validate_nullable_string(item, "backgroundImageUri", MAX_URL_CHARS)? {
            if !uri.starts_with("content://") {
                return Err(invalid(format!(
                    "desktopWidgetConfigs[{index}].backgroundImageUri is invalid"
                )));
            }
        }
        let content_type = required_string(item, "contentType")?;
        require_enum(item, "contentType", &["HOME_MODULE", "APP_SHORTCUT"])?;
        let home_module = required_string(item, "homeModuleId")?;
        if !HOME_MODULE_IDS.contains(&home_module) {
            return Err(invalid(format!(
                "desktopWidgetConfigs[{index}].homeModuleId is invalid"
            )));
        }
        let package = validate_nullable_string(item, "appPackageName", 255)?;
        let label = validate_nullable_string_value(item, "appLabel")?;
        if label.is_some_and(|value| value.chars().count() > 100) {
            return Err(invalid(format!(
                "desktopWidgetConfigs[{index}].appLabel is too long"
            )));
        }
        if package.is_some_and(|value| !is_android_package_name(value))
            || (content_type == "APP_SHORTCUT" && package.is_none())
        {
            return Err(invalid(format!(
                "desktopWidgetConfigs[{index}].appPackageName is invalid"
            )));
        }
    }
    Ok(())
}

fn is_android_package_name(value: &str) -> bool {
    let mut segments = value.split('.');
    let mut count = 0usize;
    for segment in &mut segments {
        count += 1;
        if segment.is_empty()
            || !segment
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
        {
            return false;
        }
    }
    count >= 2
}

fn decode_cloud_sync_configs(items: &[Value]) -> Result<Vec<CloudSyncConfig>, BackupError> {
    if items.len() > MAX_CLOUD_SYNC_CONFIGS {
        return Err(invalid("Too many cloud sync configurations"));
    }
    let configs = items
        .iter()
        .enumerate()
        .map(|(index, value)| {
            let item = object_at(value, "cloudSyncConfigs", index)?;
            let service_type = match required_string(item, "serviceType")? {
                "WEBDAV" => CloudSyncServiceType::WebDav,
                "S3_COMPATIBLE" => CloudSyncServiceType::S3Compatible,
                _ => {
                    return Err(invalid(format!(
                        "cloudSyncConfigs[{index}].serviceType has an unsupported value"
                    )));
                }
            };
            let contents = required_array(item, "selectedContents")?;
            if contents.is_empty() {
                return Err(invalid(format!(
                    "cloudSyncConfigs[{index}].selectedContents is empty"
                )));
            }
            let mut seen_contents = HashSet::with_capacity(contents.len());
            let mut selected_contents = Vec::with_capacity(contents.len());
            for (content_index, content) in contents.iter().enumerate() {
                let raw = content.as_str().ok_or_else(|| {
                    invalid(format!(
                        "cloudSyncConfigs[{index}].selectedContents[{content_index}] must be a string"
                    ))
                })?;
                if !seen_contents.insert(raw) {
                    return Err(invalid(format!(
                        "cloudSyncConfigs[{index}].selectedContents contains a duplicate"
                    )));
                }
                match raw {
                    "DIARIES" => selected_contents.push(CloudSyncContent::Diaries),
                    "MEDIA" => selected_contents.push(CloudSyncContent::Media),
                    "JSON_BACKUP" => selected_contents.push(CloudSyncContent::JsonBackup),
                    "USAGE_STATISTICS" => {
                        selected_contents.push(CloudSyncContent::UsageStatistics)
                    }
                    "READING_PROGRESS" => {
                        selected_contents.push(CloudSyncContent::ReadingProgress)
                    }
                    _ => {
                        return Err(invalid(format!(
                            "cloudSyncConfigs[{index}].selectedContents[{content_index}] has an unsupported value"
                        )));
                    }
                }
            }
            let direction = match required_string(item, "direction")? {
                "UPLOAD_ONLY" => CloudSyncDirection::UploadOnly,
                "TWO_WAY" => CloudSyncDirection::TwoWay,
                _ => {
                    return Err(invalid(format!(
                        "cloudSyncConfigs[{index}].direction has an unsupported value"
                    )));
                }
            };
            Ok(CloudSyncConfig {
                id: required_string(item, "id")?.to_owned(),
                name: required_string(item, "name")?.to_owned(),
                enabled: required_bool(item, "enabled")?,
                service_type,
                endpoint_url: required_string(item, "endpointUrl")?.to_owned(),
                remote_path: required_string(item, "remotePath")?.to_owned(),
                user_agent: required_string(item, "userAgent")?.to_owned(),
                web_dav_username: required_string(item, "webDavUsername")?.to_owned(),
                s3_bucket: required_string(item, "s3Bucket")?.to_owned(),
                s3_region: required_string(item, "s3Region")?.to_owned(),
                s3_path_style: required_bool(item, "s3PathStyle")?,
                allow_insecure_http: required_bool(item, "allowInsecureHttp")?,
                selected_contents,
                direction,
            })
        })
        .collect::<Result<Vec<_>, BackupError>>()?;
    validate_cloud_sync_configs_inner(&configs)?;
    Ok(configs)
}

/// Validate credential-free cloud metadata before it is persisted or exported.
///
/// The limits and enum set intentionally mirror Android v28.
pub fn validate_cloud_sync_configs(configs: &[CloudSyncConfig]) -> Result<(), BackupError> {
    validate_cloud_sync_configs_inner(configs)
}

fn validate_cloud_sync_configs_inner(configs: &[CloudSyncConfig]) -> Result<(), BackupError> {
    if configs.len() > MAX_CLOUD_SYNC_CONFIGS {
        return Err(invalid("Too many cloud sync configurations"));
    }
    let mut ids = HashSet::with_capacity(configs.len());
    for (index, config) in configs.iter().enumerate() {
        require_utf16_len(&config.id, 128, &format!("cloudSyncConfigs[{index}].id"))?;
        if config.id.trim().is_empty() || !ids.insert(config.id.as_str()) {
            return Err(invalid(format!(
                "cloudSyncConfigs[{index}].id is invalid or duplicated"
            )));
        }
        require_utf16_len(
            &config.name,
            200,
            &format!("cloudSyncConfigs[{index}].name"),
        )?;
        if config.name.trim().is_empty() {
            return Err(invalid(format!("cloudSyncConfigs[{index}].name is blank")));
        }
        require_utf16_len(
            &config.endpoint_url,
            MAX_URL_CHARS,
            &format!("cloudSyncConfigs[{index}].endpointUrl"),
        )?;
        if !is_sync_endpoint(&config.endpoint_url, config.allow_insecure_http) {
            return Err(invalid(format!(
                "cloudSyncConfigs[{index}].endpointUrl is invalid"
            )));
        }
        require_utf16_len(
            &config.remote_path,
            1_024,
            &format!("cloudSyncConfigs[{index}].remotePath"),
        )?;
        if config.remote_path.contains('\\')
            || config
                .remote_path
                .split('/')
                .any(|segment| segment == "." || segment == "..")
        {
            return Err(invalid(format!(
                "cloudSyncConfigs[{index}].remotePath is invalid"
            )));
        }
        require_utf16_len(
            &config.user_agent,
            512,
            &format!("cloudSyncConfigs[{index}].userAgent"),
        )?;
        if config.user_agent.trim().is_empty() || config.user_agent.chars().any(char::is_control) {
            return Err(invalid(format!(
                "cloudSyncConfigs[{index}].userAgent is invalid"
            )));
        }
        require_utf16_len(
            &config.web_dav_username,
            512,
            &format!("cloudSyncConfigs[{index}].webDavUsername"),
        )?;
        require_utf16_len(
            &config.s3_bucket,
            255,
            &format!("cloudSyncConfigs[{index}].s3Bucket"),
        )?;
        require_utf16_len(
            &config.s3_region,
            128,
            &format!("cloudSyncConfigs[{index}].s3Region"),
        )?;
        if config.service_type == CloudSyncServiceType::S3Compatible
            && (config.s3_bucket.trim().is_empty()
                || config.s3_bucket.contains('/')
                || config.s3_bucket.contains('\\')
                || config.s3_bucket.chars().any(char::is_control)
                || !is_valid_s3_region(&config.s3_region))
        {
            return Err(invalid(format!(
                "cloudSyncConfigs[{index}] S3 metadata is invalid"
            )));
        }
        if config.selected_contents.is_empty() {
            return Err(invalid(format!(
                "cloudSyncConfigs[{index}].selectedContents is empty"
            )));
        }
        let mut contents = HashSet::with_capacity(config.selected_contents.len());
        if config
            .selected_contents
            .iter()
            .any(|content| !contents.insert(*content))
        {
            return Err(invalid(format!(
                "cloudSyncConfigs[{index}].selectedContents contains a duplicate"
            )));
        }
    }
    Ok(())
}

fn validate_meal_photo_filter(filter: &Map<String, Value>) -> Result<(), BackupError> {
    required_bool(filter, "enabled")?;
    required_android_float_range(filter, "brightness", -1.0, 1.0)?;
    required_android_float_range(filter, "contrast", 0.0, 2.0)?;
    required_android_float_range(filter, "saturation", 0.0, 2.0)?;
    required_android_float_range(filter, "warmth", -1.0, 1.0)?;
    required_android_float_range(filter, "tint", -1.0, 1.0)?;
    Ok(())
}

fn validate_home_greetings(items: &[Value]) -> Result<(), BackupError> {
    if items.len() > 100 {
        return Err(invalid("Too many home greetings"));
    }
    for (index, value) in items.iter().enumerate() {
        let item = object_at(value, "homeGreetings", index)?;
        let chinese = required_string(item, "chinese")?;
        let english = required_string(item, "english")?;
        if chinese.trim().is_empty() && english.trim().is_empty() {
            return Err(invalid(format!("homeGreetings[{index}] is blank")));
        }
        if chinese.chars().count() > 40 || english.chars().count() > 40 {
            return Err(invalid(format!("homeGreetings[{index}] is too long")));
        }
    }
    Ok(())
}

fn validate_meal_icons(items: &[Value]) -> Result<(), BackupError> {
    if items.len() != 5 && items.len() != 6 {
        return Err(invalid("mealButtonIcons must contain 5 or 6 items"));
    }
    for (index, value) in items.iter().enumerate() {
        let icon = value
            .as_str()
            .ok_or_else(|| invalid(format!("mealButtonIcons[{index}] must be a string")))?;
        if icon.trim().is_empty() || icon.chars().count() > 16 {
            return Err(invalid(format!("mealButtonIcons[{index}] is invalid")));
        }
    }
    Ok(())
}

fn validate_daily_templates(items: &[Value]) -> Result<(), BackupError> {
    if items.len() > 100 {
        return Err(invalid("Too many daily event templates"));
    }
    let mut ids = HashSet::new();
    for (index, value) in items.iter().enumerate() {
        let item = object_at(value, "dailyEventTemplates", index)?;
        let id = require_string_limit(item, "id", 80)?;
        if id.trim().is_empty() || !ids.insert(id) {
            return Err(invalid(format!(
                "dailyEventTemplates[{index}].id is invalid or duplicated"
            )));
        }
        let text = require_string_limit(item, "text", 100)?;
        if text.trim().is_empty() {
            return Err(invalid(format!(
                "dailyEventTemplates[{index}].text is blank"
            )));
        }
        require_string_limit(item, "firstUnit", 12)?;
        require_string_limit(item, "secondUnit", 12)?;
    }
    Ok(())
}

fn validate_rss_subscriptions(items: &[Value]) -> Result<(), BackupError> {
    if items.len() > 100 {
        return Err(invalid("Too many RSS subscriptions"));
    }
    let mut ids = HashSet::new();
    for (index, value) in items.iter().enumerate() {
        let item = object_at(value, "rssSubscriptions", index)?;
        let id = require_string_limit(item, "id", 80)?;
        if id.trim().is_empty() || !ids.insert(id) {
            return Err(invalid(format!(
                "rssSubscriptions[{index}].id is invalid or duplicated"
            )));
        }
        require_string_limit(item, "title", 120)?;
        let url = require_string_limit(item, "url", MAX_URL_CHARS)?;
        if !is_https_url_with_host(url) {
            return Err(invalid(format!(
                "rssSubscriptions[{index}].url must use HTTPS"
            )));
        }
        required_bool(item, "enabled")?;
    }
    Ok(())
}

fn validate_ai_configs(items: &[Value]) -> Result<(), BackupError> {
    if items.len() > 20 {
        return Err(invalid("Too many AI configurations"));
    }
    for (index, value) in items.iter().enumerate() {
        let item = object_at(value, "aiConfigs", index)?;
        require_string_limit(item, "id", 80)?;
        require_string_limit(item, "name", 80)?;
        require_enum(item, "type", &["TEXT", "IMAGE"])?;
        require_string_limit(item, "endpointUrl", MAX_URL_CHARS)?;
        require_string_limit(item, "model", 512)?;
        required_bool(item, "enabled")?;
        required_bool(item, "allowInsecureHttp")?;
        // Android converts the finite value to Float and clamps it during
        // decode. The Windows v1 client does not manage this field, so the
        // compatibility shadow keeps the original token.
        require_number(item, "temperature")?;
        validate_optional_string(item, "systemPrompt", 20_000)?;
        // The API key is deliberately validated but never copied into ManagedSettings.
        require_string_limit(item, "apiKey", 8_192)?;
    }
    Ok(())
}

fn validate_nav_items(items: &[Value]) -> Result<(), BackupError> {
    let ids = nav_ids();
    if items.len() > ids.len() {
        return Err(invalid("navItems contains too many items"));
    }
    let mut seen = HashSet::new();
    for (index, value) in items.iter().enumerate() {
        let item = object_at(value, "navItems", index)?;
        let id = required_string(item, "id")?;
        if !ids.contains(&id) || !seen.insert(id) {
            return Err(invalid(format!(
                "navItems[{index}].id is invalid or duplicated"
            )));
        }
        require_string_limit(item, "label", 128)?;
        require_string_limit(item, "iconKey", 128)?;
        required_bool(item, "visible")?;
        required_bool(item, "showInMore")?;
        let description = required_string(item, "moreDescription")?;
        if description.chars().count() > 160 {
            return Err(invalid(format!(
                "navItems[{index}].moreDescription is too long"
            )));
        }
    }
    Ok(())
}

fn validate_more_page_order(items: &[Value]) -> Result<(), BackupError> {
    if items.len() > nav_ids().len() {
        return Err(invalid("morePageOrder contains too many items"));
    }
    let allowed = [
        "DIARY",
        "NOTES",
        "BLOG",
        "THOUGHT",
        "DATE",
        "POETRY",
        "RSS",
        "AI_CHAT",
        "VAULT",
        "READER",
        "GAMES",
        "STATISTICS",
        "USAGE",
        "STEPS",
        "WIDGETS",
    ];
    require_unique_enums(items, &allowed, "morePageOrder")
}

fn default_root(settings: &ManagedSettings, exported_at: i64) -> Value {
    let mut settings_value = json!({
        "visualStyle": "MATERIAL",
        "customTheme": {
            "baseStyle": "MATERIAL",
            "lightPalette": {
                "backgroundArgb": 0xFFF7FBF5u32 as i32,
                "onBackgroundArgb": 0xFF171D19u32 as i32,
                "surfaceArgb": 0xFFF7FBF5u32 as i32,
                "onSurfaceArgb": 0xFF171D19u32 as i32,
                "surfaceContainerArgb": 0xFFE9EFE9u32 as i32,
                "surfaceVariantArgb": 0xFFDDE5DDu32 as i32,
                "onSurfaceVariantArgb": 0xFF414943u32 as i32,
                "outlineArgb": 0xFF717971u32 as i32
            },
            "darkPalette": {
                "backgroundArgb": 0xFF101511u32 as i32,
                "onBackgroundArgb": 0xFFE0E4DFu32 as i32,
                "surfaceArgb": 0xFF101511u32 as i32,
                "onSurfaceArgb": 0xFFE0E4DFu32 as i32,
                "surfaceContainerArgb": 0xFF1B211Cu32 as i32,
                "surfaceVariantArgb": 0xFF414943u32 as i32,
                "onSurfaceVariantArgb": 0xFFC1C9C1u32 as i32,
                "outlineArgb": 0xFF8B938Au32 as i32
            },
            "cornerRadiusDp": 18.0,
            "borderWidthDp": 1.0,
            "elevationDp": 2.0,
            "panelOpacity": 0.94,
            "spacingScale": 1.0,
            "animationScale": 1.0
        },
        "darkMode": "SYSTEM",
        "appLanguage": "CHINESE",
        "themeColorArgb": 0xFF42664Du32 as i32,
        "themeSecondaryColorsArgb": [
            0xFFC96F4Au32 as i32,
            0xFFD4A72Cu32 as i32,
            0xFF527F91u32 as i32
        ],
        "fontScale": 1.0,
        "compactMode": false,
        "backgroundImageUri": null,
        "backgroundImageOpacity": 0.45,
        "backgroundImageBlurDp": 0.0,
        "tutorialModeEnabled": true,
        "useChineseLauncherName": false,
        "launcherIcon": "CURRENT",
        "cloudSyncEnabled": false,
        "cloudSyncConfigs": [],
        "diaryTreeUri": null,
        "mediaTreeUri": null,
        "notesTreeUri": null,
        "fileNamePattern": "yyyy-MM-dd",
        "markdownTemplate": "# {title}\n\n",
        "imageNamePattern": "{date}_{category}_{seq}",
        "imageMaxWidthDp": 720,
        "imageMaxHeightDp": 640,
        "markdownHeadingSizesSp": [32.0, 28.0, 24.0, 21.0, 19.0, 17.0],
        "mealImageCompressionEnabled": true,
        "mealImageCompressionQuality": 80,
        "saveOriginalToGallery": false,
        "photoLocationEnabled": false,
        "browserHomeUrl": "https://www.google.com",
        "lastBrowserUrl": null,
        "browserTheme": "SYSTEM",
        "browserDesktopMode": false,
        "thoughtSplitRatio": 0.58,
        "thoughtRowHeightDp": 56,
        "thoughtReopenMode": "ALL",
        "thoughtDisplayMode": "SINGLE_LINE",
        "thoughtHighlightColorArgb": 0xFFF6E3A1u32 as i32,
        "thoughtEditorMaxHeightDp": 168,
        "vaultRowHeightDp": 56,
        "poetryFontUri": null,
        "poetryFontSizeSp": 18.0,
        "poetryLineSpacing": 1.45,
        "poetryTextAlignment": "START",
        "poetryShowSource": true,
        "poetryShowQuoteMark": true,
        "poetrySevenCharacterWrapEnabled": false,
        "mealCalendarImageMaxHeightDp": 124,
        "mealCalendarShowCaptions": true,
        "mealCalendarWrapEnabled": false,
        "mealCalendarPhotosPerRow": "SMART",
        "mealPhotoFilter": {
            "enabled": false,
            "brightness": 0.0,
            "contrast": 1.0,
            "saturation": 1.0,
            "warmth": 0.0,
            "tint": 0.0
        },
        "mealButtonsUseIcons": false,
        "userName": "",
        "homeGreetings": [],
        "homeWidgetBordersEnabled": true,
        "mealButtonIcons": ["🥪", "🍱", "🍹", "🍜", "🍊", "🍤"],
        "dailyEventTemplates": [],
        "rssSubscriptions": [],
        "rssMaxItemsPerFeed": 50,
        "rssShowSummaries": true,
        "aiEndpointUrl": "https://api.openai.com/v1/chat/completions",
        "aiModel": "",
        "aiSystemPrompt": "你是一个有帮助的助手。",
        "aiTemperature": 0.7,
        "aiAllowInsecureHttp": false,
        "aiConfigs": [],
        "aiChatConfigId": null,
        "calorieEstimationEnabled": false,
        "calorieTextConfigId": null,
        "calorieImageConfigId": null,
        "calorieVisionPrompt": "识别图片中的所有食物和饮料。只返回 JSON，不要 Markdown：{\"foods\":[{\"name\":\"食物名称\",\"amount\":\"估计份量\",\"unit\":\"单位\",\"confidence\":0.0}],\"notes\":\"必要说明\"}。无法确定时给出合理估计并降低 confidence。",
        "calorieTextPrompt": "根据随后提供的食物识别 JSON，估算整张图片中食物的总能量。只返回 JSON，不要 Markdown：{\"energyKj\":整数}。energyKj 使用千焦(kJ)，综合份量并避免重复计算。",
        "usageTrackingEnabled": false,
        "stepTrackingEnabled": false,
        "navItems": default_nav_items(),
        "morePageOrder": [
            "DIARY", "NOTES", "BLOG", "THOUGHT", "DATE", "POETRY", "RSS", "AI_CHAT",
            "VAULT", "READER", "GAMES", "STATISTICS", "USAGE", "STEPS", "WIDGETS"
        ],
        "defaultPage": "HOME",
        "bottomNavShowLabels": true,
        "musicVisualizerEnabled": false,
        "musicVisualizerStyle": "BARS",
        "musicVisualizerFrequencyMode": "ADAPTIVE",
        "musicVisualizerMinFrequencyHz": 60,
        "musicVisualizerMaxFrequencyHz": 16000,
        "game2048AnimationSpeed": "NORMAL",
        "morePageShowDescriptions": true,
        "homeWidgets": [],
        "homeGameShortcuts": ["2048", "snake", "minesweeper"],
        "homeWidgetTitles": [],
        "desktopWidgetConfigs": [{
            "id": "default-today",
            "name": "今天 / Today",
            "widthCells": 2,
            "heightCells": 2,
            "backgroundColorArgb": 0xFF263238u32 as i32,
            "textColorArgb": -1,
            "backgroundImageUri": null,
            "contentType": "HOME_MODULE",
            "homeModuleId": "today",
            "appPackageName": null,
            "appLabel": null
        }]
    });
    if let Some(object) = settings_value.as_object_mut() {
        // This cannot fail for validated, serializable settings.
        let _ = overlay_managed_settings(object, settings);
    }
    json!({
        "format": "DeskCubby",
        "version": FORMAT_VERSION,
        "exportedAt": exported_at,
        "settings": settings_value,
        "thoughts": [],
        "categories": [],
        "favorites": [],
        "dateRecords": [],
        "poetryCategories": [],
        "poems": [],
        "vault": {"active": null, "pending": null, "items": []},
        "gameStates": [],
        "gameStatistics": [],
        "usageDevices": [],
        "readerProgress": []
    })
}

fn default_nav_items() -> Vec<Value> {
    let defaults = [
        ("HOME", "首页", "home", true, false, "今日概览与快捷记录"),
        ("DIARY", "日记", "book", true, false, "浏览、编辑日记与吃历"),
        (
            "NOTES",
            "笔记",
            "notes",
            false,
            true,
            "按文件夹管理 Obsidian 兼容 Markdown 笔记",
        ),
        (
            "BLOG",
            "浏览器",
            "language",
            false,
            true,
            "在应用内浏览网页",
        ),
        (
            "THOUGHT",
            "小巧思",
            "bolt",
            true,
            false,
            "记录与整理瞬间想法",
        ),
        (
            "DATE",
            "日期记录",
            "event",
            false,
            true,
            "追踪纪念日与目标日期",
        ),
        ("POETRY", "诗词本", "poetry", false, true, "收藏喜欢的诗词"),
        (
            "RSS",
            "RSS 订阅",
            "rss",
            false,
            true,
            "阅读订阅源的最新文章",
        ),
        (
            "AI_CHAT",
            "AI 聊天",
            "ai",
            false,
            true,
            "选择本机记录作为上下文并与模型分析",
        ),
        ("VAULT", "收藏夹", "lock", false, true, "密码保护的私密收藏"),
        (
            "READER",
            "阅读",
            "reader",
            false,
            true,
            "导入并阅读 TXT/PDF 小说",
        ),
        (
            "GAMES",
            "小游戏",
            "game",
            false,
            true,
            "2048、贪吃蛇、俄罗斯方块、扫雷与蜘蛛纸牌",
        ),
        (
            "STATISTICS",
            "统计",
            "statistics",
            false,
            true,
            "汇总日记、使用时间、健康、阅读与小游戏数据",
        ),
        (
            "USAGE",
            "手机使用时间",
            "usage",
            false,
            true,
            "按天查看各应用的使用时长",
        ),
        (
            "STEPS",
            "健康",
            "steps",
            false,
            false,
            "读取并可视化每日步数、距离和活动热量",
        ),
        (
            "WIDGETS",
            "小卡片",
            "widgets",
            false,
            true,
            "设计并添加可缩放的桌面小卡片",
        ),
        ("MORE", "导航", "apps", true, false, "打开收纳的页面"),
        (
            "SETTINGS",
            "设置",
            "settings",
            true,
            false,
            "调整应用与页面设置",
        ),
    ];
    defaults
        .into_iter()
        .map(
            |(id, label, icon, visible, show_in_more, more_description)| {
                json!({
                    "id": id,
                    "label": label,
                    "iconKey": icon,
                    "visible": visible,
                    "showInMore": show_in_more,
                        "moreDescription": more_description
                })
            },
        )
        .collect()
}

fn nav_ids() -> [&'static str; 18] {
    [
        "HOME",
        "DIARY",
        "NOTES",
        "BLOG",
        "THOUGHT",
        "DATE",
        "POETRY",
        "RSS",
        "AI_CHAT",
        "VAULT",
        "READER",
        "GAMES",
        "STATISTICS",
        "USAGE",
        "STEPS",
        "WIDGETS",
        "MORE",
        "SETTINGS",
    ]
}

fn required_object<'a>(
    object: &'a Map<String, Value>,
    name: &str,
) -> Result<&'a Map<String, Value>, BackupError> {
    object
        .get(name)
        .and_then(Value::as_object)
        .ok_or_else(|| invalid(format!("{name} must be an object")))
}

fn required_array<'a>(
    object: &'a Map<String, Value>,
    name: &str,
) -> Result<&'a [Value], BackupError> {
    object
        .get(name)
        .and_then(Value::as_array)
        .map(Vec::as_slice)
        .ok_or_else(|| invalid(format!("{name} must be an array")))
}

fn required_string<'a>(object: &'a Map<String, Value>, name: &str) -> Result<&'a str, BackupError> {
    object
        .get(name)
        .and_then(Value::as_str)
        .ok_or_else(|| invalid(format!("{name} must be a string")))
}

fn required_bool(object: &Map<String, Value>, name: &str) -> Result<bool, BackupError> {
    object
        .get(name)
        .and_then(Value::as_bool)
        .ok_or_else(|| invalid(format!("{name} must be a boolean")))
}

fn required_number(object: &Map<String, Value>, name: &str) -> Result<f64, BackupError> {
    let value = object
        .get(name)
        .and_then(Value::as_f64)
        .ok_or_else(|| invalid(format!("{name} must be a number")))?;
    if value.is_finite() {
        Ok(value)
    } else {
        Err(invalid(format!("{name} must be finite")))
    }
}

fn required_i64(object: &Map<String, Value>, name: &str) -> Result<i64, BackupError> {
    value_i64(
        object
            .get(name)
            .ok_or_else(|| invalid(format!("Missing required field: {name}")))?,
        name,
    )
}

fn required_nullable_i64(
    object: &Map<String, Value>,
    name: &str,
) -> Result<Option<i64>, BackupError> {
    let value = object
        .get(name)
        .ok_or_else(|| invalid(format!("Missing required field: {name}")))?;
    if value.is_null() {
        Ok(None)
    } else {
        Ok(Some(value_i64(value, name)?))
    }
}

fn required_i32(object: &Map<String, Value>, name: &str) -> Result<i32, BackupError> {
    value_i32(
        object
            .get(name)
            .ok_or_else(|| invalid(format!("Missing required field: {name}")))?,
        name,
    )
}

fn value_i64(value: &Value, field: &str) -> Result<i64, BackupError> {
    let raw = value
        .as_number()
        .ok_or_else(|| invalid(format!("{field} must be an integer")))?
        .to_string();
    exact_decimal_i64(&raw).ok_or_else(|| invalid(format!("{field} must be a 64-bit integer")))
}

fn value_i32(value: &Value, field: &str) -> Result<i32, BackupError> {
    let number = value_i64(value, field)?;
    i32::try_from(number).map_err(|_| invalid(format!("{field} must be a 32-bit integer")))
}

/// Equivalent to Android's `BigDecimal(number.toString()).longValueExact()`
/// without converting through a lossy floating-point representation.
fn exact_decimal_i64(raw: &str) -> Option<i64> {
    let (negative, unsigned) = raw
        .strip_prefix('-')
        .map_or((false, raw), |rest| (true, rest));
    let (mantissa, exponent_text) = unsigned
        .split_once(['e', 'E'])
        .map_or((unsigned, None), |(mantissa, exponent)| {
            (mantissa, Some(exponent))
        });
    let (integer, fraction) = mantissa
        .split_once('.')
        .map_or((mantissa, ""), |(integer, fraction)| (integer, fraction));

    let mut digits = String::with_capacity(integer.len() + fraction.len());
    digits.push_str(integer);
    digits.push_str(fraction);
    let significant = digits.trim_start_matches('0');
    if significant.is_empty() {
        return Some(0);
    }

    // A token is capped by the 64 MiB document limit. Exponents beyond this
    // bound cannot affect a nonzero value into the i64 range, so saturation
    // here only classifies a result as overflow/non-integral.
    let exponent = bounded_decimal_exponent(exponent_text.unwrap_or("0"))?;
    let shift = exponent - i64::try_from(fraction.len()).ok()?;
    let integer_digits = if shift >= 0 {
        let appended = usize::try_from(shift).ok()?;
        if significant.len().checked_add(appended)? > 19 {
            return None;
        }
        let mut expanded = String::with_capacity(significant.len() + appended);
        expanded.push_str(significant);
        expanded.extend(std::iter::repeat_n('0', appended));
        expanded
    } else {
        let removed = usize::try_from(shift.unsigned_abs()).ok()?;
        if removed > significant.len() {
            return None;
        }
        let split = significant.len() - removed;
        if significant.as_bytes()[split..]
            .iter()
            .any(|digit| *digit != b'0')
        {
            return None;
        }
        significant[..split].to_owned()
    };
    if integer_digits.is_empty() {
        return Some(0);
    }

    let magnitude = integer_digits.parse::<u64>().ok()?;
    if negative {
        if magnitude == (i64::MAX as u64) + 1 {
            Some(i64::MIN)
        } else {
            i64::try_from(magnitude).ok().map(|value| -value)
        }
    } else {
        i64::try_from(magnitude).ok()
    }
}

fn bounded_decimal_exponent(raw: &str) -> Option<i64> {
    const EXPONENT_BOUND: i64 = MAX_JSON_BYTES as i64 + 64;

    let (negative, digits) = raw
        .strip_prefix('-')
        .map_or((false, raw), |rest| (true, rest));
    let digits = digits.strip_prefix('+').unwrap_or(digits);
    if digits.is_empty() {
        return None;
    }
    let mut magnitude = 0_i64;
    for digit in digits.bytes() {
        if !digit.is_ascii_digit() {
            return None;
        }
        magnitude = magnitude
            .saturating_mul(10)
            .saturating_add(i64::from(digit - b'0'))
            .min(EXPONENT_BOUND);
    }
    Some(if negative { -magnitude } else { magnitude })
}

fn require_enum(
    object: &Map<String, Value>,
    name: &str,
    allowed: &[&str],
) -> Result<(), BackupError> {
    let value = required_string(object, name)?;
    if allowed.contains(&value) {
        Ok(())
    } else {
        Err(invalid(format!("{name} has an unsupported value")))
    }
}

fn require_unique_enums(
    values: &[Value],
    allowed: &[&str],
    field: &str,
) -> Result<(), BackupError> {
    let mut seen = HashSet::new();
    for (index, value) in values.iter().enumerate() {
        let string = value
            .as_str()
            .ok_or_else(|| invalid(format!("{field}[{index}] must be a string")))?;
        if !allowed.contains(&string) || !seen.insert(string) {
            return Err(invalid(format!(
                "{field}[{index}] is invalid or duplicated"
            )));
        }
    }
    Ok(())
}

fn require_number(object: &Map<String, Value>, name: &str) -> Result<f64, BackupError> {
    required_number(object, name)
}

fn require_number_range(
    object: &Map<String, Value>,
    name: &str,
    minimum: f64,
    maximum: f64,
) -> Result<f64, BackupError> {
    let value = required_number(object, name)?;
    if (minimum..=maximum).contains(&value) {
        Ok(value)
    } else {
        Err(invalid(format!("{name} is out of range")))
    }
}

fn required_android_float_range(
    object: &Map<String, Value>,
    name: &str,
    minimum: f32,
    maximum: f32,
) -> Result<f64, BackupError> {
    let value = required_number(object, name)? as f32;
    if value.is_finite() && (minimum..=maximum).contains(&value) {
        Ok(f64::from(value))
    } else {
        Err(invalid(format!("{name} is out of range")))
    }
}

fn required_coerced_i32(
    object: &Map<String, Value>,
    name: &str,
    minimum: i32,
    maximum: i32,
) -> Result<i32, BackupError> {
    let number = required_number(object, name)?;
    if number % 1.0 != 0.0 {
        return Err(invalid(format!("{name} must be an integer")));
    }
    Ok(number.clamp(f64::from(minimum), f64::from(maximum)) as i32)
}

fn require_string_limit<'a>(
    object: &'a Map<String, Value>,
    name: &str,
    maximum: usize,
) -> Result<&'a str, BackupError> {
    let value = required_string(object, name)?;
    require_utf16_len(value, maximum, name)?;
    Ok(value)
}

fn validate_nullable_string<'a>(
    object: &'a Map<String, Value>,
    name: &str,
    maximum: usize,
) -> Result<Option<&'a str>, BackupError> {
    let value = object
        .get(name)
        .ok_or_else(|| invalid(format!("Missing required field: {name}")))?;
    if value.is_null() {
        return Ok(None);
    }
    let string = value
        .as_str()
        .ok_or_else(|| invalid(format!("{name} must be a string or null")))?;
    require_utf16_len(string, maximum, name)?;
    Ok(Some(string))
}

fn validate_nullable_string_value<'a>(
    object: &'a Map<String, Value>,
    name: &str,
) -> Result<Option<&'a str>, BackupError> {
    let value = object
        .get(name)
        .ok_or_else(|| invalid(format!("Missing required field: {name}")))?;
    if value.is_null() {
        Ok(None)
    } else {
        value
            .as_str()
            .map(Some)
            .ok_or_else(|| invalid(format!("{name} must be a string or null")))
    }
}

fn validate_optional_string<'a>(
    object: &'a Map<String, Value>,
    name: &str,
    maximum: usize,
) -> Result<&'a str, BackupError> {
    let Some(value) = object.get(name) else {
        return Ok("");
    };
    let string = value
        .as_str()
        .ok_or_else(|| invalid(format!("{name} must be a string")))?;
    require_utf16_len(string, maximum, name)?;
    Ok(string)
}

fn string_list(items: &[Value], name: &str) -> Result<Vec<String>, BackupError> {
    items
        .iter()
        .enumerate()
        .map(|(index, value)| {
            value
                .as_str()
                .map(str::to_owned)
                .ok_or_else(|| invalid(format!("{name}[{index}] must be a string")))
        })
        .collect()
}

fn validate_string_list(items: &[Value], field: &str) -> Result<(), BackupError> {
    if items.len() > 1_000 {
        return Err(invalid(format!("{field} contains too many items")));
    }
    let mut seen = HashSet::new();
    for (index, value) in items.iter().enumerate() {
        let string = value
            .as_str()
            .ok_or_else(|| invalid(format!("{field}[{index}] must be a string")))?;
        if utf16_len(string) > 256 || !seen.insert(string) {
            return Err(invalid(format!(
                "{field}[{index}] is too long or duplicated"
            )));
        }
    }
    Ok(())
}

fn object_at<'a>(
    value: &'a Value,
    array_name: &str,
    index: usize,
) -> Result<&'a Map<String, Value>, BackupError> {
    value
        .as_object()
        .ok_or_else(|| invalid(format!("{array_name}[{index}] must be an object")))
}

fn require_positive_unique(
    id: i64,
    ids: &mut HashSet<i64>,
    entity: &str,
    index: usize,
) -> Result<(), BackupError> {
    if id <= 0 || !ids.insert(id) {
        Err(invalid(format!(
            "{entity} at index {index} has an invalid or duplicate id"
        )))
    } else {
        Ok(())
    }
}

fn validate_timestamps(
    created_at: i64,
    updated_at: i64,
    deleted_at: Option<i64>,
    array_name: &str,
    index: usize,
) -> Result<(), BackupError> {
    if created_at < 0
        || updated_at < created_at
        || deleted_at.is_some_and(|deleted_at| deleted_at < created_at)
    {
        Err(invalid(format!(
            "{array_name}[{index}] has invalid timestamps"
        )))
    } else {
        Ok(())
    }
}

fn require_nonnegative(value: i64, field: &str) -> Result<(), BackupError> {
    if value < 0 {
        Err(invalid(format!("{field} must not be negative")))
    } else {
        Ok(())
    }
}

fn require_date_iso(value: &str, field: &str) -> Result<(), BackupError> {
    if value.len() == 10 && chrono::NaiveDate::parse_from_str(value, "%Y-%m-%d").is_ok() {
        Ok(())
    } else {
        Err(invalid(format!("{field} must be a valid yyyy-MM-dd date")))
    }
}

fn require_utf16_len(value: &str, maximum: usize, field: &str) -> Result<(), BackupError> {
    if utf16_len(value) <= maximum {
        Ok(())
    } else {
        Err(invalid(format!("{field} is too long")))
    }
}

fn utf16_len(value: &str) -> usize {
    value.encode_utf16().count()
}

fn is_http_url(value: &str) -> bool {
    value
        .get(..7)
        .is_some_and(|prefix| prefix.eq_ignore_ascii_case("http://"))
        || value
            .get(..8)
            .is_some_and(|prefix| prefix.eq_ignore_ascii_case("https://"))
}

fn is_https_url_with_host(value: &str) -> bool {
    reqwest::Url::parse(value)
        .is_ok_and(|url| url.scheme().eq_ignore_ascii_case("https") && url.host_str().is_some())
}

fn is_sync_endpoint(value: &str, allow_http: bool) -> bool {
    reqwest::Url::parse(value).is_ok_and(|url| {
        let allowed_scheme = url.scheme().eq_ignore_ascii_case("https")
            || allow_http && url.scheme().eq_ignore_ascii_case("http");
        allowed_scheme
            && url.host_str().is_some()
            && url.username().is_empty()
            && url.password().is_none()
            && url.query().is_none()
            && url.fragment().is_none()
    })
}

fn is_valid_s3_region(value: &str) -> bool {
    let mut characters = value.chars();
    characters.next().is_some_and(|first| {
        first.is_ascii_alphanumeric()
            && characters.all(|character| {
                character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-')
            })
    })
}

fn is_browser_url(value: &str) -> bool {
    value.eq_ignore_ascii_case("about:blank") || is_http_url(value)
}

fn require_size(bytes: &[u8]) -> Result<(), BackupError> {
    if bytes.len() <= MAX_JSON_BYTES {
        Ok(())
    } else {
        Err(invalid("Backup JSON exceeds the 64 MiB limit"))
    }
}

/// Verify that decrypted compatibility-shadow bytes still match the source
/// document recorded alongside their DPAPI ciphertext.
pub fn verify_source_sha256(bytes: &[u8], expected: &str) -> Result<(), BackupError> {
    if expected.len() != 64
        || !expected
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
        || sha256_hex(bytes) != expected
    {
        return Err(invalid("Compatibility shadow hash mismatch"));
    }
    Ok(())
}

fn sha256_hex(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

fn invalid(message: impl Into<String>) -> BackupError {
    BackupError::Invalid(message.into())
}

#[cfg(test)]
mod tests {
    use serde_json::Value;

    use super::*;
    use crate::models::{ThoughtCategoryDraft, ThoughtDraft};

    fn valid_json() -> String {
        serde_json::to_string_pretty(&default_root(
            &ManagedSettings::default(),
            1_700_000_000_000,
        ))
        .expect("encode fixture")
    }

    fn empty_fixture_for_version(version: i32) -> String {
        let mut root: Value = serde_json::from_str(&valid_json()).expect("current fixture");
        root["version"] = Value::from(version);
        let settings = root["settings"].as_object_mut().expect("settings");
        for (introduced, fields) in legacy_setting_introductions() {
            if version < *introduced {
                for field in *fields {
                    settings.remove(*field);
                }
            }
        }
        if version < 13 {
            for item in settings["navItems"].as_array_mut().expect("navItems") {
                item.as_object_mut().expect("nav item").remove("showInMore");
            }
        }
        if version < 15 {
            for item in settings["navItems"].as_array_mut().expect("navItems") {
                item.as_object_mut()
                    .expect("nav item")
                    .remove("moreDescription");
            }
        }
        let root = root.as_object_mut().expect("root");
        for (introduced, fields) in [
            (2, &["dateRecords"][..]),
            (3, &["categories"][..]),
            (4, &["poems"][..]),
            (19, &["poetryCategories"][..]),
            (20, &["vault", "gameStates", "usageDevices"][..]),
            (24, &["gameStatistics"][..]),
            (28, &["readerProgress"][..]),
        ] {
            if version < introduced {
                for field in fields {
                    root.remove(*field);
                }
            }
        }
        serde_json::to_string(&root).expect("legacy fixture")
    }

    fn webdav_config(id: &str) -> CloudSyncConfig {
        CloudSyncConfig {
            id: id.to_owned(),
            name: "WebDAV".to_owned(),
            enabled: true,
            service_type: CloudSyncServiceType::WebDav,
            endpoint_url: "https://dav.example.com/remote.php/dav/files/alice".to_owned(),
            remote_path: "DeskCubby".to_owned(),
            user_agent: "DeskCubby-Sync/1".to_owned(),
            web_dav_username: "alice".to_owned(),
            s3_bucket: String::new(),
            s3_region: "us-east-1".to_owned(),
            s3_path_style: true,
            allow_insecure_http: false,
            selected_contents: vec![
                CloudSyncContent::Diaries,
                CloudSyncContent::Media,
                CloudSyncContent::JsonBackup,
            ],
            direction: CloudSyncDirection::TwoWay,
        }
    }

    fn v28_cloud_config_value(config: CloudSyncConfig) -> Value {
        let mut value = serde_json::to_value(config).expect("serialize cloud config");
        let object = value.as_object_mut().expect("cloud config object");
        object.insert(
            "userAgent".to_owned(),
            Value::String("DeskCubby-Sync/1".to_owned()),
        );
        object.insert("s3PathStyle".to_owned(), Value::Bool(true));
        value
    }

    fn private_usage_device() -> Value {
        json!({
            "schemaVersion": 1,
            "deviceId": "11111111-1111-4111-8111-111111111111",
            "deviceName": "Private phone",
            "platform": "android",
            "updatedAtEpochMillis": 42,
            "history": {
                "schemaVersion": 4,
                "trackingStartedOn": "2026-07-27",
                "backfillCompletedThrough": "2026-07-27",
                "days": [{
                    "date": "2026-07-27",
                    "zoneId": "Asia/Shanghai",
                    "state": "FINAL",
                    "collectedAtEpochMillis": 42,
                    "apps": [{
                        "packageName": "com.example.private",
                        "foregroundMillis": 12_345
                    }]
                }]
            }
        })
    }

    fn private_shadow_root() -> Value {
        let mut root: Value = serde_json::from_str(&valid_json()).expect("fixture");
        root["settings"]["cloudSyncConfigs"] = json!([{
            "id": "dav",
            "name": "Android WebDAV",
            "enabled": true,
            "serviceType": "WEBDAV",
            "endpointUrl": "https://dav.example.com",
            "remotePath": "DeskCubby",
            "userAgent": "DeskCubby-Sync/1",
            "webDavUsername": "alice",
            "s3Bucket": "",
            "s3Region": "us-east-1",
            "s3PathStyle": true,
            "allowInsecureHttp": false,
            "selectedContents": ["JSON_BACKUP"],
            "direction": "UPLOAD_ONLY",
            "futureCloudField": {"keep": true},
            "webDavPassword": "must-not-leak",
            "s3AccessKey": "must-not-leak",
            "s3SecretKey": "must-not-leak",
            "s3SessionToken": "must-not-leak"
        }]);
        root["settings"]["aiConfigs"] = json!([{
            "id": "private",
            "name": "private",
            "type": "TEXT",
            "endpointUrl": "https://example.com/v1",
            "model": "model",
            "enabled": true,
            "allowInsecureHttp": false,
            "temperature": 0.7,
            "systemPrompt": "",
            "apiKey": "ai-key-must-round-trip"
        }]);
        root["settings"]["diaryTreeUri"] =
            Value::String("content://provider/tree/diary".to_owned());
        root["settings"]["mediaTreeUri"] =
            Value::String("content://provider/tree/media".to_owned());
        root["settings"]["poetryFontUri"] =
            Value::String("content://provider/document/font".to_owned());
        root["settings"]["cloudSyncCredentials"] = json!({"dav": "private"});
        root["settings"]["cloudSyncSecrets"] = json!({"dav": "private"});
        root["settings"]["webDavPassword"] = Value::String("private".to_owned());
        root["settings"]["vaultItems"] = json!([{"ciphertext": "private"}]);
        root["settings"]["vaultMetadata"] = json!({"salt": "private", "verifier": "private"});
        root["settings"]["usageStatistics"] = json!({"private": true});
        root["settings"]["stepStatistics"] = json!([{"private": true}]);
        root["settings"]["healthHistory"] = json!([{"steps": 9_999}]);
        root["settings"]["healthStatistics"] = json!({"private": true});
        root["settings"]["healthSourcePath"] = Value::String("C:\\private-health.json".to_owned());
        root["settings"]["mealCalendarDayColumns"] = json!(2);
        root["settings"]["futureSetting"] = json!({"keep": true});
        root["vaultItems"] = json!([{"ciphertext": "private"}]);
        root["vaultMetadata"] = json!({"salt": "private", "verifier": "private"});
        root["usageStatistics"] = json!({"private": true});
        root["stepStatistics"] = json!([{"private": true}]);
        root["healthHistory"] = json!([{"steps": 9_999}]);
        root["healthStatistics"] = json!({"private": true});
        root["healthDevices"] = json!([{"private": true}]);
        root["healthSnapshot"] = json!({"days": [{"steps": 9_999}]});
        root["healthSourceMetadata"] = json!({"linkedPath": "C:\\private-health.json"});
        root["healthSourcePath"] = Value::String("C:\\private-health.json".to_owned());
        root["linkedHealthSourcePath"] = Value::String("C:\\private-health.json".to_owned());
        root["cloudSyncSecrets"] = json!({"private": true});
        root["vault"] = json!({
            "active": {
                "saltBase64": "AAAAAAAAAAAAAAAAAAAAAA==",
                "iterations": 120000,
                "generationId": "active-1",
                "verifierCipher": "AAAAAAAAAAAAAAAAAAAAAA==",
                "verifierIv": "AAAAAAAAAAAAAAAA"
            },
            "pending": null,
            "items": [{
                "id": 1,
                "cipherText": "AAAAAAAAAAAAAAAAAAAAAA==",
                "iv": "AAAAAAAAAAAAAAAA",
                "createdAt": 1,
                "updatedAt": 2,
                "sortOrder": 0
            }],
            "future": "keep"
        });
        root["usageDevices"] = json!([private_usage_device()]);
        root["readerProgress"] = json!([{
            "fingerprint": "a".repeat(64),
            "type": "TXT",
            "textPageIndex": 2,
            "textParagraphIndex": 75,
            "pdfPageIndex": 0,
            "totalPages": 8,
            "updatedAt": 42,
            "title": "must-not-leak",
            "futureReaderField": {"keep": true, "bookUri": "content://must-not-leak"}
        }]);
        root["futureNested"] = json!({
            "vaultItems": [{"ciphertext": "relocated-private"}],
            "vaultMetadata": {"salt": "relocated-private"},
            "usageDevices": [private_usage_device()],
            "usageStatistics": {"private": true},
            "healthHistory": [{"steps": 9_999}],
            "healthStatistics": {"private": true},
            "healthDevices": [{"private": true}],
            "healthSnapshot": {"days": [{"steps": 9_999}]},
            "healthSourceMetadata": {"linkedPath": "C:\\private-health.json"},
            "healthSourcePath": "C:\\private-health.json",
            "linkedHealthSourcePath": "C:\\private-health.json",
            "keep": true
        });
        // Similar but non-designated unknown keys prove the scrub is exact.
        root["usage"] = json!({"future": "keep"});
        root["health"] = json!({"future": "keep"});
        root["futureRoot"] = json!({"keep": true});
        root
    }

    fn assert_private_fields_scrubbed(output: &Value) {
        for field in [
            "vaultItems",
            "vaultMetadata",
            "usageStatistics",
            "stepStatistics",
            "healthHistory",
            "healthStatistics",
            "healthDevices",
            "healthSnapshot",
            "healthSourceMetadata",
            "healthSourcePath",
            "linkedHealthSourcePath",
            "cloudSyncSecrets",
        ] {
            assert!(output.get(field).is_none(), "{field} leaked");
            assert!(
                output["settings"].get(field).is_none(),
                "settings.{field} leaked"
            );
        }
        for field in [
            "cloudSyncCredentials",
            "cloudSyncSecrets",
            "webDavPassword",
            "s3AccessKey",
            "s3SecretKey",
            "s3SessionToken",
        ] {
            assert!(
                output["settings"].get(field).is_none(),
                "settings.{field} leaked"
            );
            assert!(
                output["settings"]["cloudSyncConfigs"][0]
                    .get(field)
                    .is_none(),
                "cloudSyncConfigs[0].{field} leaked"
            );
        }
        assert_eq!(
            output["settings"]["aiConfigs"][0]["apiKey"],
            "ai-key-must-round-trip"
        );
        assert_eq!(
            output["settings"]["diaryTreeUri"],
            "content://provider/tree/diary"
        );
        assert_eq!(
            output["settings"]["mediaTreeUri"],
            "content://provider/tree/media"
        );
        assert_eq!(
            output["settings"]["poetryFontUri"],
            "content://provider/document/font"
        );
        assert!(output["settings"].get("mealCalendarDayColumns").is_none());
        assert_eq!(output["settings"]["futureSetting"], json!({"keep": true}));
        assert_eq!(output["futureRoot"], json!({"keep": true}));
        assert_eq!(output["vault"]["future"], "keep");
        assert_eq!(output["vault"]["active"], Value::Null);
        assert_eq!(output["vault"]["pending"], Value::Null);
        assert_eq!(output["vault"]["items"], json!([]));
        assert_eq!(output["usageDevices"], json!([]));
        assert_eq!(
            output["readerProgress"][0]["futureReaderField"],
            json!({"keep": true})
        );
        assert!(output["readerProgress"][0].get("title").is_none());
        assert_eq!(output["futureNested"], json!({"keep": true}));
        assert_eq!(output["usage"], json!({"future": "keep"}));
        assert_eq!(output["health"], json!({"future": "keep"}));
    }

    #[test]
    fn parses_and_previews_v28() {
        let backup = parse_v18(&valid_json()).expect("parse");
        assert_eq!(backup.preview().format_version, 28);
        assert_eq!(backup.preview().thought_count, 0);
        assert_eq!(backup.preview().reader_progress_count, 0);
    }

    #[test]
    fn accepts_and_canonicalizes_every_android_backup_version() {
        for version in 1..=FORMAT_VERSION {
            let source = empty_fixture_for_version(version);
            let parsed = parse_v18(&source)
                .unwrap_or_else(|error| panic!("Android v{version} failed: {error}"));
            assert_eq!(parsed.preview().format_version, version);
            assert_eq!(parsed.root["version"], FORMAT_VERSION);
            assert!(parsed.root["settings"]["homeGameShortcuts"].is_array());
            assert!(parsed.root["vault"].is_object());
            assert!(parsed.root["usageDevices"].is_array());
            assert!(parsed.root["settings"]["customTheme"].is_object());
            assert!(parsed.root["readerProgress"].is_array());
        }
    }

    #[test]
    fn v28_custom_theme_and_reader_progress_round_trip_without_android_uris() {
        let mut root: Value = serde_json::from_str(&valid_json()).expect("fixture");
        root["settings"]["visualStyle"] = Value::String("CUSTOM".to_owned());
        root["settings"]["customTheme"]["baseStyle"] = Value::String("LIQUID_GLASS".to_owned());
        root["settings"]["customTheme"]["cornerRadiusDp"] = json!(27.0);
        root["settings"]["customTheme"]["panelOpacity"] = json!(0.8);
        root["settings"]["customTheme"]["futureThemeToken"] = json!({"keep": true});
        root["readerProgress"] = json!([
            {
                "fingerprint": "b".repeat(64),
                "type": "PDF",
                "textPageIndex": 0,
                "textParagraphIndex": 0,
                "pdfPageIndex": 7,
                "totalPages": 80,
                "updatedAt": 22,
                "futureProgress": {"keep": "pdf"}
            },
            {
                "fingerprint": "a".repeat(64),
                "type": "TXT",
                "textPageIndex": 3,
                "textParagraphIndex": 92,
                "pdfPageIndex": 0,
                "totalPages": 14,
                "updatedAt": 23,
                "futureProgress": {"keep": "txt"}
            }
        ]);
        let source = serde_json::to_string_pretty(&root).expect("source");
        let parsed = parse_v18(&source).expect("v28 parse");
        assert_eq!(parsed.settings.visual_style, "LIQUID_GLASS");
        assert_eq!(parsed.reader_progress.len(), 2);
        assert_eq!(parsed.preview().reader_progress_count, 2);
        assert!(
            prepare_v18_import_for_shadow(&source)
                .expect("prepare v28 import")
                .merge_reader_progress
        );

        let directory = tempfile::tempdir().expect("temp dir");
        let database = Database::open(directory.path().join("deskcubby.db")).expect("database");
        import_v18_transaction(&database, &parsed, Some(b"encrypted-shadow")).expect("import");
        let output = export_v18_merged(&database, Some(source.as_bytes()), 29).expect("export");
        let output: Value = serde_json::from_str(&output).expect("output");
        assert_eq!(output["version"], 28);
        assert_eq!(output["settings"]["visualStyle"], "CUSTOM");
        assert_eq!(
            output["settings"]["customTheme"]["futureThemeToken"],
            json!({"keep": true})
        );
        assert_eq!(output["readerProgress"], root["readerProgress"]);
    }

    #[test]
    fn v28_rejects_incomplete_or_out_of_range_custom_theme() {
        let mut root: Value = serde_json::from_str(&valid_json()).expect("fixture");
        root["settings"]["customTheme"]["panelOpacity"] = json!(0.1);
        assert!(parse_v18(&root.to_string()).is_err());

        root = serde_json::from_str(&valid_json()).expect("fixture");
        root["settings"]["customTheme"]["panelOpacity"] = json!(1.000_000_01);
        assert!(parse_v18(&root.to_string()).is_err());

        root = serde_json::from_str(&valid_json()).expect("fixture");
        root["settings"]["customTheme"]["spacingScale"] = json!(1.350_000_03);
        assert!(parse_v18(&root.to_string()).is_err());

        root = serde_json::from_str(&valid_json()).expect("fixture");
        root["settings"]["customTheme"]["lightPalette"]
            .as_object_mut()
            .expect("palette")
            .remove("onSurfaceArgb");
        assert!(parse_v18(&root.to_string()).is_err());

        root = serde_json::from_str(&valid_json()).expect("fixture");
        root["settings"]["customTheme"]["baseStyle"] = Value::String("CSS".to_owned());
        assert!(parse_v18(&root.to_string()).is_err());
    }

    #[test]
    fn v28_reader_progress_rejects_malformed_duplicate_and_excess_records() {
        let valid = json!({
            "fingerprint": "a".repeat(64),
            "type": "TXT",
            "textPageIndex": -1,
            "textParagraphIndex": 0,
            "pdfPageIndex": 0,
            "totalPages": 0,
            "updatedAt": 0
        });
        let mut root: Value = serde_json::from_str(&valid_json()).expect("fixture");
        root["readerProgress"] = json!([valid.clone(), valid.clone()]);
        assert!(parse_v18(&root.to_string()).is_err());

        for (field, invalid_value) in [
            ("fingerprint", Value::String("A".repeat(64))),
            ("type", Value::String("EPUB".to_owned())),
            ("textPageIndex", Value::from(50_000)),
            ("textParagraphIndex", Value::from(250_000)),
            ("pdfPageIndex", Value::from(-1)),
            ("updatedAt", Value::from(-1)),
        ] {
            let mut record = valid.clone();
            record[field] = invalid_value;
            root["readerProgress"] = json!([record]);
            assert!(parse_v18(&root.to_string()).is_err(), "accepted {field}");
        }

        root["readerProgress"] = Value::Array(
            (0..=MAX_READER_PROGRESS_RECORDS)
                .map(|index| {
                    let mut record = valid.clone();
                    record["fingerprint"] = Value::String(format!("{index:064x}"));
                    record
                })
                .collect(),
        );
        assert!(parse_v18(&root.to_string()).is_err());
    }

    #[test]
    fn v28_2048_statistics_accept_move_attempts_and_keep_legacy_losses() {
        let mut root: Value = serde_json::from_str(&valid_json()).expect("fixture");
        root["gameStatistics"] = json!([
            {
                "gameId": "2048",
                "metricKey": "moveAttempts",
                "value": 23,
                "updatedAt": 101
            },
            {
                "gameId": "2048",
                "metricKey": "losses",
                "value": 4,
                "updatedAt": 90,
                "futureStatistic": {"keep": true}
            }
        ]);
        let source = serde_json::to_string_pretty(&root).expect("source");
        let parsed = parse_v18(&source).expect("v28 game statistics");
        assert_eq!(parsed.game_statistics.len(), 2);
        assert!(
            parsed
                .game_statistics
                .iter()
                .any(|item| item.metric_key == "moveAttempts" && item.value == 23)
        );
        assert!(
            parsed
                .game_statistics
                .iter()
                .any(|item| item.metric_key == "losses" && item.value == 4)
        );

        let directory = tempfile::tempdir().expect("temp dir");
        let database = Database::open(directory.path().join("deskcubby.db")).expect("database");
        import_v18_transaction(&database, &parsed, Some(b"encrypted-shadow")).expect("import");
        let output = export_v18_merged(&database, Some(source.as_bytes()), 102).expect("export");
        let output: Value = serde_json::from_str(&output).expect("output");
        assert_eq!(
            output["gameStatistics"]
                .as_array()
                .expect("statistics")
                .len(),
            2
        );
        assert_eq!(
            output["gameStatistics"]
                .as_array()
                .expect("statistics")
                .iter()
                .find(|item| item["metricKey"] == "losses")
                .expect("legacy loss")["futureStatistic"],
            json!({"keep": true})
        );
    }

    #[test]
    fn reader_overlay_uses_lww_keeps_unmatched_and_preserves_future_siblings() {
        let directory = tempfile::tempdir().expect("temp dir");
        let database = Database::open(directory.path().join("deskcubby.db")).expect("database");
        let mut root: Value = serde_json::from_str(&valid_json()).expect("fixture");
        root["readerProgress"] = json!([
            {
                "fingerprint": "a".repeat(64), "type": "TXT",
                "textPageIndex": 1, "textParagraphIndex": 20, "pdfPageIndex": 0,
                "totalPages": 10, "updatedAt": 10,
                "futureProgress": {"keep": true}
            },
            {
                "fingerprint": "b".repeat(64), "type": "PDF",
                "textPageIndex": 0, "textParagraphIndex": 0, "pdfPageIndex": 4,
                "totalPages": 30, "updatedAt": 20
            }
        ]);
        let source = serde_json::to_string_pretty(&root).expect("source");
        let parsed = parse_v18(&source).expect("parse");
        import_v18_transaction(&database, &parsed, Some(b"encrypted-shadow")).expect("import");
        let local = [
            ReaderProgressRecord {
                fingerprint: "a".repeat(64),
                book_type: ReaderProgressBookType::Txt,
                text_page_index: 3,
                text_paragraph_index: 90,
                pdf_page_index: 0,
                total_pages: 10,
                updated_at: 30,
            },
            ReaderProgressRecord {
                fingerprint: "c".repeat(64),
                book_type: ReaderProgressBookType::Pdf,
                text_page_index: 0,
                text_paragraph_index: 0,
                pdf_page_index: 8,
                total_pages: 40,
                updated_at: 25,
            },
        ];
        let exported = export_v18_merged_with_cloud_configs_and_reader_progress(
            &database,
            Some(source.as_bytes()),
            31,
            None,
            Some(&local),
        )
        .expect("reader overlay");
        let output: Value = serde_json::from_str(&exported).expect("output");
        let records = output["readerProgress"].as_array().expect("records");
        assert_eq!(records.len(), 3);
        assert_eq!(records[0]["fingerprint"], "a".repeat(64));
        assert_eq!(records[0]["textParagraphIndex"], 90);
        assert_eq!(records[0]["futureProgress"], json!({"keep": true}));
        assert_eq!(records[1]["fingerprint"], "b".repeat(64));
        assert_eq!(records[2]["fingerprint"], "c".repeat(64));
    }

    #[test]
    fn android_v27_golden_upgrades_to_v28_and_survives_windows_edit_and_export() {
        let source = include_str!("../test-data/android-v27-golden.json");
        let parsed = parse_v18(source).expect("parse Android golden");
        assert_eq!(parsed.thoughts.len(), 1);
        assert_eq!(parsed.favorite_count, 1);
        assert_eq!(parsed.cloud_sync_configs.len(), 1);
        assert_eq!(parsed.cloud_sync_configs[0].id, "android-s3");
        assert_eq!(
            parsed.cloud_sync_configs[0].service_type,
            CloudSyncServiceType::S3Compatible
        );
        assert_eq!(
            parsed.cloud_sync_configs[0].selected_contents,
            vec![
                CloudSyncContent::Diaries,
                CloudSyncContent::Media,
                CloudSyncContent::JsonBackup
            ]
        );

        let directory = tempfile::tempdir().expect("temp dir");
        let database = Database::open(directory.path().join("deskcubby.db")).expect("database");
        import_v18_transaction(&database, &parsed, Some(b"encrypted-shadow")).expect("import");
        database
            .save_thought(ThoughtDraft {
                id: Some(7),
                content: "Windows 修改后的正文".to_owned(),
                pinned: true,
                category_id: Some(5),
                highlighted: true,
            })
            .expect("edit imported thought");

        let exported =
            export_v18_merged(&database, Some(source.as_bytes()), 41).expect("merged export");
        let output: Value = serde_json::from_str(&exported).expect("output");
        let golden: Value = serde_json::from_str(source).expect("golden");

        assert_eq!(
            output["settings"]["diaryTreeUri"],
            golden["settings"]["diaryTreeUri"]
        );
        assert_eq!(
            output["settings"]["mediaTreeUri"],
            golden["settings"]["mediaTreeUri"]
        );
        assert_eq!(
            output["settings"]["rssSubscriptions"],
            golden["settings"]["rssSubscriptions"]
        );
        assert_eq!(
            output["settings"]["cloudSyncConfigs"],
            golden["settings"]["cloudSyncConfigs"]
        );
        assert_eq!(
            output["settings"]["aiConfigs"][0]["apiKey"],
            golden["settings"]["aiConfigs"][0]["apiKey"]
        );
        assert_eq!(
            output["settings"]["aiConfigs"][0]["futureAiField"],
            golden["settings"]["aiConfigs"][0]["futureAiField"]
        );
        assert_eq!(
            output["settings"]["futureSetting"],
            golden["settings"]["futureSetting"]
        );
        assert_eq!(output["favorites"], golden["favorites"]);
        assert_eq!(output["futureRoot"], golden["futureRoot"]);
        assert_eq!(output["thoughts"][0]["content"], "Windows 修改后的正文");
        assert_eq!(
            output["thoughts"][0]["futureThoughtField"],
            golden["thoughts"][0]["futureThoughtField"]
        );
        assert_eq!(output["version"], 28);
        assert!(output["settings"]["customTheme"].is_object());
        assert_eq!(output["readerProgress"], json!([]));
        parse_v18(&exported).expect("Android-readable v28 output");
    }

    #[test]
    fn cloud_overlay_merges_golden_unknown_fields_by_id_and_disables_android_sync() {
        let source = include_str!("../test-data/android-v27-golden.json");
        let parsed = parse_v18(source).expect("parse Android golden");
        let directory = tempfile::tempdir().expect("temp dir");
        let database = Database::open(directory.path().join("deskcubby.db")).expect("database");
        import_v18_transaction(&database, &parsed, Some(b"encrypted-shadow")).expect("import");

        let mut updated = parsed.cloud_sync_configs[0].clone();
        updated.name = "Windows S3".to_owned();
        updated.direction = CloudSyncDirection::TwoWay;
        updated.selected_contents = vec![CloudSyncContent::JsonBackup];
        let exported = export_v18_merged_with_cloud_configs(
            &database,
            Some(source.as_bytes()),
            42,
            Some(&[updated]),
        )
        .expect("cloud overlay");
        let output: Value = serde_json::from_str(&exported).expect("output");

        assert_eq!(output["settings"]["cloudSyncEnabled"], Value::Bool(false));
        assert_eq!(
            output["settings"]["usageTrackingEnabled"],
            Value::Bool(false)
        );
        assert_eq!(
            output["settings"]["stepTrackingEnabled"],
            Value::Bool(false)
        );
        assert_eq!(
            output["settings"]["cloudSyncConfigs"][0]["name"],
            "Windows S3"
        );
        assert_eq!(
            output["settings"]["cloudSyncConfigs"][0]["direction"],
            "TWO_WAY"
        );
        assert_eq!(
            output["settings"]["cloudSyncConfigs"][0]["futureCloudField"],
            json!({"preserve": true})
        );
        assert_eq!(
            output["settings"]["aiConfigs"][0]["apiKey"],
            "sk-text-plain"
        );
        assert_eq!(
            output["futureRoot"]["origin"],
            "Android BackupJsonCodecTest"
        );
        parse_v18(&exported).expect("Android-readable overlay");
    }

    #[test]
    fn cloud_overlay_never_exports_credentials_vault_or_usage_samples() {
        let root = private_shadow_root();
        let source = serde_json::to_string_pretty(&root).expect("source");
        let parsed = parse_v18(&source).expect("parse");
        let directory = tempfile::tempdir().expect("temp dir");
        let database = Database::open(directory.path().join("deskcubby.db")).expect("database");
        import_v18_transaction(&database, &parsed, Some(b"encrypted-shadow")).expect("import");

        let exported = export_v18_merged_with_cloud_configs(
            &database,
            Some(source.as_bytes()),
            43,
            Some(&[webdav_config("dav")]),
        )
        .expect("cloud overlay");
        let output: Value = serde_json::from_str(&exported).expect("output");
        let cloud = output["settings"]["cloudSyncConfigs"][0]
            .as_object()
            .expect("cloud object");

        assert_private_fields_scrubbed(&output);
        assert_eq!(cloud["futureCloudField"], json!({"keep": true}));

        let serialized = serde_json::to_value(webdav_config("dto")).expect("serialize DTO");
        let serialized = serialized.as_object().expect("DTO object");
        assert!(!serialized.contains_key("webDavPassword"));
        assert!(!serialized.contains_key("s3AccessKey"));
        assert!(!serialized.contains_key("s3SecretKey"));
        assert!(!serialized.contains_key("s3SessionToken"));
    }

    #[test]
    fn omitted_cloud_overlay_still_scrubs_private_shadow_fields() {
        let root = private_shadow_root();
        let source = serde_json::to_string_pretty(&root).expect("source");
        let parsed = parse_v18(&source).expect("parse");
        let directory = tempfile::tempdir().expect("temp dir");
        let database = Database::open(directory.path().join("deskcubby.db")).expect("database");
        import_v18_transaction(&database, &parsed, Some(b"encrypted-shadow")).expect("import");

        let exported =
            export_v18_merged_with_cloud_configs(&database, Some(source.as_bytes()), 44, None)
                .expect("legacy cloud export path");
        let output: Value = serde_json::from_str(&exported).expect("output");

        assert_private_fields_scrubbed(&output);
        assert_eq!(
            output["settings"]["cloudSyncConfigs"][0]["futureCloudField"],
            json!({"keep": true})
        );
    }

    #[test]
    fn prepared_import_shadow_is_scrubbed_reparsed_and_hash_bound() {
        let root = private_shadow_root();
        let source = serde_json::to_string_pretty(&root).expect("source");
        let original_sha256 = sha256_hex(source.as_bytes());

        let prepared = prepare_v18_import_for_shadow(&source).expect("prepare import");
        let canonical_text =
            std::str::from_utf8(&prepared.canonical_bytes).expect("canonical UTF-8");
        let canonical_root: Value =
            serde_json::from_slice(&prepared.canonical_bytes).expect("canonical JSON");

        assert_private_fields_scrubbed(&canonical_root);
        assert_eq!(prepared.usage_devices, vec![private_usage_device()]);
        assert!(prepared.merge_usage_devices);
        assert!(prepared.backup.usage_devices.is_empty());
        assert_eq!(
            canonical_root["settings"]["cloudSyncConfigs"][0]["futureCloudField"],
            json!({"keep": true})
        );
        assert_eq!(
            prepared.backup.source_sha256,
            sha256_hex(&prepared.canonical_bytes)
        );
        assert_ne!(prepared.backup.source_sha256, original_sha256);
        assert_eq!(
            parse_v18(canonical_text)
                .expect("canonical backup reparses")
                .source_sha256,
            prepared.backup.source_sha256
        );

        let directory = tempfile::tempdir().expect("temp dir");
        let database = Database::open(directory.path().join("deskcubby.db")).expect("database");
        import_v18_transaction(&database, &prepared.backup, Some(b"encrypted-shadow"))
            .expect("import prepared backup");
        assert!(
            database
                .list_cloud_sync_configs()
                .expect("list cloud configs")
                .is_empty(),
            "Android metadata must not create Windows cloud configuration rows"
        );
    }

    #[test]
    fn omitting_cloud_overlay_preserves_original_export_behavior() {
        let source = include_str!("../test-data/android-v18-golden.json");
        let parsed = parse_v18(source).expect("parse");
        let directory = tempfile::tempdir().expect("temp dir");
        let database = Database::open(directory.path().join("deskcubby.db")).expect("database");
        import_v18_transaction(&database, &parsed, Some(b"encrypted-shadow")).expect("import");

        let original =
            export_v18_merged(&database, Some(source.as_bytes()), 44).expect("original export");
        let optional =
            export_v18_merged_with_cloud_configs(&database, Some(source.as_bytes()), 44, None)
                .expect("optional export");
        assert_eq!(optional, original);
    }

    #[test]
    fn legacy_v18_is_upgraded_to_v28_without_losing_unknown_fields_or_ai_keys() {
        let source = include_str!("../test-data/android-v18-golden.json");
        let parsed = parse_v18(source).expect("parse Android v18 golden");
        assert_eq!(parsed.preview().format_version, 18);
        assert_eq!(parsed.root["version"], 28);
        assert_eq!(
            parsed.root["settings"]["cloudSyncConfigs"][0]["userAgent"],
            "DeskCubby-Sync/1"
        );
        assert_eq!(
            parsed.root["settings"]["cloudSyncConfigs"][0]["s3PathStyle"],
            true
        );
        assert_eq!(
            parsed.root["settings"]["aiConfigs"][0]["apiKey"],
            "sk-text-plain"
        );
        assert_eq!(
            parsed.root["settings"]["futureSetting"],
            json!({"keep": "android"})
        );
        assert_eq!(parsed.root["poems"][0]["sortOrder"], 0);
        assert_eq!(parsed.root["poems"][0]["categoryId"], Value::Null);
        assert_eq!(parsed.root["vault"]["items"], json!([]));

        let prepared = prepare_v18_import_for_shadow(source).expect("prepare legacy import");
        assert!(!prepared.merge_reader_progress);
        assert_eq!(prepared.backup.preview().format_version, 28);
        let canonical: Value =
            serde_json::from_slice(&prepared.canonical_bytes).expect("canonical v28");
        assert_eq!(canonical["version"], 28);
        parse_v18(std::str::from_utf8(&prepared.canonical_bytes).expect("UTF-8"))
            .expect("canonical output is Android-readable");
    }

    #[test]
    fn cloud_metadata_enforces_enum_uniqueness_and_count_limits() {
        let mut root: Value = serde_json::from_str(&valid_json()).expect("fixture");
        let raw = v28_cloud_config_value(webdav_config("dav"));
        root["settings"]["cloudSyncConfigs"] = Value::Array(vec![raw.clone()]);

        root["settings"]["cloudSyncConfigs"][0]["selectedContents"] = json!(["READING_PROGRESS"]);
        let parsed = parse_v18(&root.to_string()).expect("reading progress cloud content");
        assert_eq!(
            parsed.cloud_sync_configs[0].selected_contents,
            vec![CloudSyncContent::ReadingProgress]
        );

        root["settings"]["cloudSyncConfigs"][0]["serviceType"] = Value::String("FTP".to_owned());
        assert!(parse_v18(&root.to_string()).is_err());
        root["settings"]["cloudSyncConfigs"][0] = raw.clone();
        root["settings"]["cloudSyncConfigs"][0]["direction"] =
            Value::String("DOWNLOAD_ONLY".to_owned());
        assert!(parse_v18(&root.to_string()).is_err());
        root["settings"]["cloudSyncConfigs"][0] = raw.clone();
        root["settings"]["cloudSyncConfigs"][0]["selectedContents"] = json!(["DIARIES", "DIARIES"]);
        assert!(parse_v18(&root.to_string()).is_err());
        root["settings"]["cloudSyncConfigs"] =
            Value::Array((0..=MAX_CLOUD_SYNC_CONFIGS).map(|_| raw.clone()).collect());
        assert!(parse_v18(&root.to_string()).is_err());

        let duplicate = webdav_config("duplicate");
        assert!(validate_cloud_sync_configs(&[duplicate.clone(), duplicate]).is_err());
        let too_many = (0..=MAX_CLOUD_SYNC_CONFIGS)
            .map(|index| webdav_config(&format!("config-{index}")))
            .collect::<Vec<_>>();
        assert!(validate_cloud_sync_configs(&too_many).is_err());
    }

    #[test]
    fn rejects_out_of_range_versions_and_duplicate_ids() {
        let mut root: Value = serde_json::from_str(&valid_json()).expect("fixture");
        root["version"] = Value::from(0);
        assert!(parse_v18(&root.to_string()).is_err());

        root["version"] = Value::from(29);
        assert!(parse_v18(&root.to_string()).is_err());

        root["version"] = Value::from(FORMAT_VERSION);
        let thought = json!({
            "id": 1,
            "content": "same id",
            "createdAt": 1,
            "updatedAt": 1,
            "pinned": false,
            "deletedAt": null,
            "sortOrder": 0,
            "categoryId": null,
            "highlighted": false
        });
        root["thoughts"] = Value::Array(vec![thought.clone(), thought]);
        assert!(parse_v18(&root.to_string()).is_err());
    }

    #[test]
    fn preserves_unknown_fields_and_ai_key_during_merge() {
        let directory = tempfile::tempdir().expect("temp dir");
        let database = Database::open(directory.path().join("deskcubby.db")).expect("database");
        let mut root: Value = serde_json::from_str(&valid_json()).expect("fixture");
        root["futureRoot"] = json!({"keep": true});
        root["settings"]["futureSetting"] = Value::String("keep me".to_owned());
        root["settings"]["aiConfigs"] = json!([{
            "id": "private",
            "name": "private",
            "type": "TEXT",
            "endpointUrl": "https://example.com/v1",
            "model": "model",
            "enabled": true,
            "allowInsecureHttp": false,
            "temperature": 0.7,
            "systemPrompt": "",
            "apiKey": "secret-that-must-round-trip"
        }]);
        root["favorites"] = json!([{
            "url": "https://example.com",
            "title": "Example",
            "lastVisitedAt": 1,
            "visitCount": 1,
            "favorite": true
        }]);
        let source = serde_json::to_string_pretty(&root).expect("source");
        let parsed = parse_v18(&source).expect("parse");
        import_v18_transaction(&database, &parsed, Some(b"encrypted-shadow")).expect("import");
        database
            .save_category(ThoughtCategoryDraft {
                id: None,
                name: "Windows".to_owned(),
                color_argb: -1,
            })
            .expect("category");
        database
            .save_thought(ThoughtDraft {
                id: None,
                content: "edited on Windows".to_owned(),
                pinned: false,
                category_id: None,
                highlighted: false,
            })
            .expect("thought");
        let exported =
            export_v18_merged(&database, Some(source.as_bytes()), now_millis()).expect("export");
        let exported_root: Value = serde_json::from_str(&exported).expect("export JSON");
        assert_eq!(exported_root["futureRoot"]["keep"], Value::Bool(true));
        assert_eq!(
            exported_root["settings"]["futureSetting"],
            Value::String("keep me".to_owned())
        );
        assert_eq!(
            exported_root["settings"]["aiConfigs"][0]["apiKey"],
            Value::String("secret-that-must-round-trip".to_owned())
        );
        assert_eq!(exported_root["favorites"].as_array().unwrap().len(), 1);
        assert_eq!(exported_root["thoughts"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn preserves_nested_unknown_fields_for_managed_objects_and_entity_ids() {
        let directory = tempfile::tempdir().expect("temp dir");
        let database = Database::open(directory.path().join("deskcubby.db")).expect("database");
        let mut root: Value = serde_json::from_str(&valid_json()).expect("fixture");
        root["settings"]["mealPhotoFilter"]["futureFilter"] = json!({"keep": true});
        root["settings"]["dailyEventTemplates"] = json!([{
            "id": "daily-water",
            "text": "喝水",
            "firstUnit": "",
            "secondUnit": "",
            "futureTemplate": {"keep": true}
        }]);
        root["categories"] = json!([{
            "id": 7,
            "name": "灵感",
            "colorArgb": -1,
            "sortOrder": 0,
            "createdAt": 1,
            "updatedAt": 1,
            "futureCategory": {"keep": true}
        }]);
        root["thoughts"] = json!([{
            "id": 11,
            "content": "before",
            "createdAt": 1,
            "updatedAt": 1,
            "pinned": false,
            "deletedAt": null,
            "sortOrder": 0,
            "categoryId": 7,
            "highlighted": false,
            "futureThought": {"keep": true}
        }]);
        root["dateRecords"] = json!([{
            "id": 13,
            "name": "纪念日",
            "icon": "event",
            "dateIso": "2024-02-29",
            "createdAt": 1,
            "updatedAt": 1,
            "futureDate": {"keep": true}
        }]);
        root["poems"] = json!([{
            "id": 17,
            "content": "海上生明月",
            "source": "张九龄",
            "createdAt": 1,
            "updatedAt": 1,
            "sortOrder": 0,
            "categoryId": null,
            "futurePoem": {"keep": true}
        }]);
        let source = serde_json::to_string_pretty(&root).expect("source");
        let parsed = parse_v18(&source).expect("parse");
        import_v18_transaction(&database, &parsed, Some(b"encrypted-shadow")).expect("import");
        database
            .save_thought(ThoughtDraft {
                id: Some(11),
                content: "edited on Windows".to_owned(),
                pinned: true,
                category_id: Some(7),
                highlighted: true,
            })
            .expect("edit thought");

        let exported =
            export_v18_merged(&database, Some(source.as_bytes()), now_millis()).expect("export");
        let output: Value = serde_json::from_str(&exported).expect("output");
        assert_eq!(
            output["settings"]["mealPhotoFilter"]["futureFilter"]["keep"],
            Value::Bool(true)
        );
        assert_eq!(
            output["settings"]["dailyEventTemplates"][0]["futureTemplate"]["keep"],
            Value::Bool(true)
        );
        assert_eq!(
            output["categories"][0]["futureCategory"]["keep"],
            Value::Bool(true)
        );
        assert_eq!(
            output["thoughts"][0]["futureThought"]["keep"],
            Value::Bool(true)
        );
        assert_eq!(
            output["dateRecords"][0]["futureDate"]["keep"],
            Value::Bool(true)
        );
        assert_eq!(output["poems"][0]["futurePoem"]["keep"], Value::Bool(true));
        assert_eq!(
            output["thoughts"][0]["content"],
            Value::String("edited on Windows".to_owned())
        );
    }

    #[test]
    fn accepts_android_utf16_string_limits() {
        let mut root: Value = serde_json::from_str(&valid_json()).expect("fixture");
        root["settings"]["fileNamePattern"] = Value::String("名".repeat(1_024));
        root["settings"]["dailyEventTemplates"] = json!([{
            "id": "项".repeat(80),
            "text": "文".repeat(100),
            "firstUnit": "次".repeat(12),
            "secondUnit": "组".repeat(12)
        }]);
        root["categories"] = json!([{
            "id": 1,
            "name": "类".repeat(40),
            "colorArgb": -1,
            "sortOrder": 0,
            "createdAt": 1,
            "updatedAt": 1
        }]);
        parse_v18(&root.to_string()).expect("Android-valid UTF-16 lengths");
    }

    #[test]
    fn integer_fields_match_android_big_decimal_long_value_exact() {
        let mut root: Value = serde_json::from_str(&valid_json()).expect("fixture");
        root["exportedAt"] = serde_json::from_str::<Value>("9223372036854775808").expect("number");
        assert!(parse_v18(&root.to_string()).is_err());

        root["exportedAt"] = serde_json::from_str::<Value>("1.2").expect("number");
        assert!(parse_v18(&root.to_string()).is_err());

        for exact in ["1.0", "1e0", "10e-1", "1.20e1", "9223372036854775807.0"] {
            root["exportedAt"] = serde_json::from_str::<Value>(exact).expect("number");
            parse_v18(&root.to_string()).expect("mathematical integer");
        }
        root["exportedAt"] =
            serde_json::from_str::<Value>("-9223372036854775808.0").expect("number");
        assert!(matches!(
            parse_v18(&root.to_string()),
            Err(BackupError::Invalid(message)) if message.contains("negative")
        ));

        root["exportedAt"] = Value::from(i64::MAX);
        parse_v18(&root.to_string()).expect("i64 max integer literal");
    }

    #[test]
    fn validates_s3_metadata_and_accepts_android_ai_config_shapes() {
        let mut root: Value = serde_json::from_str(&valid_json()).expect("fixture");
        let valid_s3 = json!({
            "id": "sync",
            "name": "S3",
            "enabled": false,
            "serviceType": "S3_COMPATIBLE",
            "endpointUrl": "https://s3.example.com",
            "remotePath": "DeskCubby",
            "userAgent": "DeskCubby-Sync/1",
            "webDavUsername": "",
            "s3Bucket": "bucket",
            "s3Region": "us-east-1",
            "s3PathStyle": true,
            "allowInsecureHttp": false,
            "selectedContents": ["JSON_BACKUP"],
            "direction": "UPLOAD_ONLY"
        });
        root["settings"]["cloudSyncConfigs"] = json!([valid_s3.clone()]);
        parse_v18(&root.to_string()).expect("valid S3 metadata");

        root["settings"]["cloudSyncConfigs"][0]["s3Region"] = Value::String(".invalid".to_owned());
        assert!(parse_v18(&root.to_string()).is_err());
        root["settings"]["cloudSyncConfigs"][0] = valid_s3;
        root["settings"]["cloudSyncConfigs"][0]["s3Bucket"] =
            Value::String("bad\u{0001}bucket".to_owned());
        assert!(parse_v18(&root.to_string()).is_err());

        root["settings"]["cloudSyncConfigs"] = json!([]);
        let ai = json!({
            "id": "same",
            "name": "Model",
            "type": "TEXT",
            "endpointUrl": "https://example.com/v1",
            "model": "model",
            "enabled": true,
            "allowInsecureHttp": false,
            "temperature": 0.7,
            "systemPrompt": "",
            "apiKey": "key"
        });
        root["settings"]["aiConfigs"] = json!([ai.clone(), ai]);
        parse_v18(&root.to_string()).expect("Android permits duplicate AI IDs");
        root["settings"]["aiConfigs"][0]["id"] = Value::String(String::new());
        root["settings"]["aiConfigs"][0]["name"] = Value::String(String::new());
        root["settings"]["aiConfigs"][0]["temperature"] =
            serde_json::from_str("-100.0").expect("number");
        root["settings"]["aiConfigs"][0]
            .as_object_mut()
            .expect("AI object")
            .remove("systemPrompt");
        parse_v18(&root.to_string())
            .expect("Android permits blank AI identity, clamps temperature, and defaults prompt");

        root["settings"]["aiConfigs"][1]["id"] = Value::String("other".to_owned());
        root["settings"]["aiConfigs"][1]["name"] = Value::String(" ".to_owned());
        parse_v18(&root.to_string()).expect("Android permits blank AI names");
    }

    #[test]
    fn coerces_android_integer_settings_and_normalizes_managed_colors() {
        let mut root: Value = serde_json::from_str(&valid_json()).expect("fixture");
        root["settings"]["themeColorArgb"] = Value::from(0x0012_3456);
        root["settings"]["themeSecondaryColorsArgb"] = json!([0x00123456, 0xFF123456u32 as i32]);
        root["settings"]["thoughtHighlightColorArgb"] = Value::from(0x0001_0203);
        root["settings"]["imageMaxWidthDp"] = serde_json::from_str("9999.0").expect("number");
        root["settings"]["imageMaxHeightDp"] = serde_json::from_str("-1e0").expect("number");
        root["settings"]["mealImageCompressionQuality"] =
            serde_json::from_str("1000e-1").expect("number");
        root["settings"]["thoughtEditorMaxHeightDp"] = Value::from(8);
        root["settings"]["mealCalendarImageMaxHeightDp"] = Value::from(900);
        root["settings"]["thoughtRowHeightDp"] = Value::from(-500);
        root["settings"]["rssMaxItemsPerFeed"] = Value::from(5_000);
        root["settings"]["thoughtSplitRatio"] = Value::from(-12.0);

        let parsed = parse_v18(&root.to_string()).expect("Android-coercible settings");
        assert_eq!(parsed.settings.theme_color_argb, 0xFF12_3456u32 as i32);
        assert_eq!(
            parsed.settings.theme_secondary_colors_argb,
            vec![0xFF12_3456u32 as i32, 0xFFC9_6F4Au32 as i32]
        );
        assert_eq!(
            parsed.settings.thought_highlight_color_argb,
            0xFF01_0203u32 as i32
        );
        assert_eq!(parsed.settings.image_max_width_dp, 2_400);
        assert_eq!(parsed.settings.image_max_height_dp, 120);
        assert_eq!(parsed.settings.meal_image_compression_quality, 95);
        assert_eq!(parsed.settings.thought_editor_max_height_dp, 96);
        assert_eq!(parsed.settings.meal_calendar_image_max_height_dp, 320);

        root["settings"]["imageMaxWidthDp"] = serde_json::from_str("120.5").expect("number");
        assert!(parse_v18(&root.to_string()).is_err());
    }

    #[test]
    fn preserves_arbitrary_precision_unknown_numbers() {
        let directory = tempfile::tempdir().expect("temp dir");
        let database = Database::open(directory.path().join("deskcubby.db")).expect("database");
        let mut root: Value = serde_json::from_str(&valid_json()).expect("fixture");
        let future: Value = serde_json::from_str(
            r#"{
                "huge": 12345678901234567890123456789012345678901234567890,
                "exponent": 1e9999,
                "nested": {"precise": -9.8765432109876543210987654321e-4321}
            }"#,
        )
        .expect("arbitrary precision fixture");
        root["futureNumbers"] = future.clone();
        let source = serde_json::to_string_pretty(&root).expect("source");
        let parsed = parse_v18(&source).expect("parse");
        import_v18_transaction(&database, &parsed, Some(b"encrypted-shadow")).expect("import");

        let output = export_v18_merged(&database, Some(source.as_bytes()), 2)
            .expect("export arbitrary precision shadow");
        let output: Value = serde_json::from_str(&output).expect("output");
        assert_eq!(
            output["futureNumbers"]["huge"].to_string(),
            future["huge"].to_string()
        );
        assert_eq!(
            output["futureNumbers"]["exponent"].to_string(),
            future["exponent"].to_string()
        );
        assert_eq!(
            output["futureNumbers"]["nested"]["precise"].to_string(),
            future["nested"]["precise"].to_string()
        );
    }

    #[test]
    fn android_float_conversion_boundaries_are_reproduced() {
        let mut root: Value = serde_json::from_str(&valid_json()).expect("fixture");
        root["settings"]["fontScale"] =
            Value::Number(serde_json::Number::from_f64(0.8).expect("number"));
        assert!(parse_v18(&root.to_string()).is_err());

        root["settings"]["fontScale"] =
            Value::Number(serde_json::Number::from_f64(f64::from(0.8_f32)).expect("number"));
        root["settings"]["mealPhotoFilter"]["brightness"] =
            Value::Number(serde_json::Number::from_f64(1.000_000_01).expect("number"));
        let parsed = parse_v18(&root.to_string()).expect("values round into Android Float bounds");
        assert_eq!(parsed.settings.font_scale, f64::from(0.8_f32));
        assert_eq!(parsed.settings.meal_photo_filter.brightness, 1.0);

        root["settings"]["mealPhotoFilter"]["brightness"] =
            Value::Number(serde_json::Number::from_f64(1.000_000_2).expect("number"));
        assert!(parse_v18(&root.to_string()).is_err());
    }

    #[test]
    fn fresh_export_defaults_match_current_android_constants() {
        let managed = ManagedSettings::default();
        let root = default_root(&managed, 1);
        assert_eq!(
            root["settings"]["calorieVisionPrompt"],
            Value::String(managed.calorie_vision_prompt.clone())
        );
        assert_eq!(
            root["settings"]["calorieTextPrompt"],
            Value::String(managed.calorie_text_prompt.clone())
        );
        assert_eq!(
            root["settings"]["homeGreetings"]
                .as_array()
                .expect("greetings")
                .len(),
            24
        );
        let nav = root["settings"]["navItems"].as_array().expect("nav");
        assert_eq!(nav.len(), 18);
        assert_eq!(nav[0]["moreDescription"], "今日概览与快捷记录");
        assert_eq!(
            nav[11]["moreDescription"],
            "2048、贪吃蛇、俄罗斯方块、扫雷与蜘蛛纸牌"
        );
        assert_eq!(nav[17]["moreDescription"], "调整应用与页面设置");
        assert!(root["settings"].get("mealCalendarDayColumns").is_none());
    }

    #[test]
    fn verifies_compatibility_shadow_source_hash() {
        let bytes = b"raw Android v18";
        let digest = sha256_hex(bytes);
        verify_source_sha256(bytes, &digest).expect("matching hash");
        assert!(verify_source_sha256(b"changed", &digest).is_err());
        assert!(verify_source_sha256(bytes, &digest.to_ascii_uppercase()).is_err());
    }

    #[test]
    fn recovery_point_restores_core_transactionally() {
        let directory = tempfile::tempdir().expect("temp dir");
        let database = Database::open(directory.path().join("deskcubby.db")).expect("database");
        let thought = database
            .save_thought(ThoughtDraft {
                id: None,
                content: "before".to_owned(),
                pinned: false,
                category_id: None,
                highlighted: false,
            })
            .expect("thought");
        let recovery = recovery_point_bytes(&database).expect("recovery");
        database
            .permanently_delete_thought(thought.id)
            .expect("delete");
        restore_recovery_point(&database, &recovery).expect("restore");
        assert_eq!(
            database.list_thoughts(false).expect("thoughts")[0].content,
            "before"
        );
    }

    #[test]
    fn recovery_point_v2_round_trips_reader_state_and_v1_stays_compatible() {
        let directory = tempfile::tempdir().expect("temp dir");
        let database = Database::open(directory.path().join("deskcubby.db")).expect("database");
        let reader_state = br#"{"schemaVersion":2,"preferences":{"background":"paper"},"books":[],"progressLedger":[]}"#;

        let v2 = recovery_point_bytes_with_reader(&database, reader_state)
            .expect("reader-aware recovery point");
        let restored_reader = reader_state_from_recovery_point(&v2)
            .expect("decode reader-aware recovery point")
            .expect("reader state");
        assert_eq!(
            serde_json::from_slice::<Value>(&restored_reader).expect("restored reader JSON"),
            serde_json::from_slice::<Value>(reader_state).expect("source reader JSON")
        );
        let decoded_v2: RecoveryPoint = serde_json::from_slice(&v2).expect("decode v2");
        assert_eq!(decoded_v2.version, 2);
        assert!(decoded_v2.reader_state.is_some());

        let v1 = recovery_point_bytes(&database).expect("legacy recovery point");
        assert_eq!(
            reader_state_from_recovery_point(&v1).expect("decode legacy point"),
            None
        );
        let decoded_v1: RecoveryPoint = serde_json::from_slice(&v1).expect("decode v1");
        assert_eq!(decoded_v1.version, 1);
        assert!(decoded_v1.reader_state.is_none());
    }

    #[test]
    fn recovery_point_rejects_cross_version_reader_state_shape() {
        let directory = tempfile::tempdir().expect("temp dir");
        let database = Database::open(directory.path().join("deskcubby.db")).expect("database");
        let v1 = recovery_point_bytes(&database).expect("legacy recovery point");
        let mut malformed: Value = serde_json::from_slice(&v1).expect("decode");
        malformed["version"] = json!(2);
        assert!(
            reader_state_from_recovery_point(
                &serde_json::to_vec(&malformed).expect("encode malformed")
            )
            .is_err()
        );

        malformed["reader_state"] = json!({"schemaVersion": 2});
        malformed["version"] = json!(1);
        assert!(
            reader_state_from_recovery_point(
                &serde_json::to_vec(&malformed).expect("encode malformed")
            )
            .is_err()
        );
    }

    #[test]
    fn recovery_point_omits_compatibility_shadow_private_payload() {
        let directory = tempfile::tempdir().expect("temp dir");
        let database = Database::open(directory.path().join("deskcubby.db")).expect("database");
        let marker = b"encrypted-vault-and-usage-marker";
        database
            .put_compatibility_shadow(marker, &"a".repeat(64), 1)
            .expect("seed compatibility shadow");

        let recovery = recovery_point_bytes(&database).expect("recovery");
        assert!(
            !recovery
                .windows(marker.len())
                .any(|window| window == marker)
        );
        let decoded: RecoveryPoint = serde_json::from_slice(&recovery).expect("decode recovery");
        assert!(decoded.snapshot.encrypted_compatibility_shadow.is_none());
        assert!(decoded.snapshot.compatibility_source_sha256.is_none());

        database
            .put_compatibility_shadow(b"newer-shadow", &"b".repeat(64), 2)
            .expect("replace compatibility shadow");
        restore_recovery_point(&database, &recovery).expect("restore");
        assert!(
            database
                .get_compatibility_shadow()
                .expect("read shadow")
                .is_none()
        );
    }

    #[test]
    fn recovery_point_size_limit_accepts_boundary_and_rejects_next_byte() {
        ensure_recovery_point_size(MAX_RECOVERY_POINT_BYTES).expect("exact recovery-point limit");
        assert!(matches!(
            ensure_recovery_point_size(MAX_RECOVERY_POINT_BYTES + 1),
            Err(BackupError::RecoveryPointTooLarge)
        ));
    }

    #[test]
    fn enforces_sixty_four_mib_limit_before_parsing() {
        let huge = " ".repeat(MAX_JSON_BYTES + 1);
        assert!(matches!(
            parse_v18(&huge),
            Err(BackupError::Invalid(message)) if message.contains("64 MiB")
        ));
    }
}
