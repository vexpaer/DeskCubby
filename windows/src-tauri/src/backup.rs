use std::collections::{HashMap, HashSet};

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use sha2::{Digest, Sha256};
use thiserror::Error;

use crate::{
    db::{DataError, Database, now_millis},
    models::{
        BackupPreview, CoreSnapshot, DailyEventTemplate, DateRecord, HomeGreeting, ImportReceipt,
        ManagedSettings, MealPhotoFilter, SavedPoem, Thought, ThoughtCategory,
    },
};

pub const FORMAT_VERSION: i32 = 18;
pub const MAX_JSON_BYTES: usize = 10 * 1024 * 1024;

const MAX_RECOVERY_POINT_BYTES: usize = 64 * 1024 * 1024;
const MAX_THOUGHTS: usize = 50_000;
const MAX_FAVORITES: usize = 20_000;
const MAX_DATE_RECORDS: usize = 50_000;
const MAX_CATEGORIES: usize = 10_000;
const MAX_POEMS: usize = 50_000;
const MAX_THOUGHT_CHARS: usize = 1_000_000;
const MAX_URL_CHARS: usize = 8_192;
const MAX_TITLE_CHARS: usize = 4_096;
const MAX_SETTING_STRING_CHARS: usize = 1_000_000;
const MAX_DATE_NAME_CHARS: usize = 256;
const MAX_DATE_ICON_CHARS: usize = 64;
const MAX_CATEGORY_NAME_CHARS: usize = 40;
const MAX_POEM_CONTENT_CHARS: usize = 100_000;
const MAX_POEM_SOURCE_CHARS: usize = 4_096;
const MAX_CLOUD_SYNC_CONFIGS: usize = 20;

/// Credential-free cloud-sync metadata shared with Android v18 backups.
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
    pub web_dav_username: String,
    pub s3_bucket: String,
    pub s3_region: String,
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
    pub exported_at: i64,
    pub settings: ManagedSettings,
    #[allow(dead_code)]
    pub cloud_sync_configs: Vec<CloudSyncConfig>,
    pub thoughts: Vec<Thought>,
    pub categories: Vec<ThoughtCategory>,
    pub favorite_count: usize,
    pub date_records: Vec<DateRecord>,
    pub poems: Vec<SavedPoem>,
    pub source_sha256: String,
    root: Value,
}

/// A validated v18 import whose compatibility-shadow bytes have had
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
            "poems",
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
            format_version: FORMAT_VERSION,
            exported_at: self.exported_at,
            thought_count: self.thoughts.len(),
            category_count: self.categories.len(),
            favorite_count: self.favorite_count,
            date_record_count: self.date_records.len(),
            poem_count: self.poems.len(),
            preserved_top_level_keys,
        }
    }
}

pub fn parse_v18(json_text: &str) -> Result<ValidatedBackup, BackupError> {
    require_size(json_text.as_bytes())?;
    let root: Value = serde_json::from_str(json_text)?;
    let root_object = root
        .as_object()
        .ok_or_else(|| invalid("Backup root must be a JSON object"))?;
    if required_string(root_object, "format")? != "DeskCubby" {
        return Err(invalid("Unsupported backup format"));
    }
    if required_i32(root_object, "version")? != FORMAT_VERSION {
        return Err(invalid("Windows requires an Android v18 backup"));
    }
    let exported_at = required_i64(root_object, "exportedAt")?;
    require_nonnegative(exported_at, "exportedAt")?;

    let settings_object = required_object(root_object, "settings")?;
    let cloud_sync_configs = validate_full_v18_settings(settings_object)?;
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
    let poems = decode_poems(required_array(root_object, "poems")?)?;
    let source_sha256 = sha256_hex(json_text.as_bytes());

    Ok(ValidatedBackup {
        exported_at,
        settings,
        cloud_sync_configs,
        thoughts,
        categories,
        favorite_count,
        date_records,
        poems,
        source_sha256,
        root,
    })
}

/// Validate and canonicalize an imported Android v18 backup before it becomes
/// the encrypted compatibility shadow.
///
/// This is the single import-side privacy boundary: known device-local vault,
/// usage, step and cloud credential fields are removed, while AI API keys,
/// Android SAF URIs and unknown compatibility fields remain untouched. The
/// sanitized compact JSON is parsed again so the returned validated model and
/// source hash describe the exact bytes that callers persist.
pub(crate) fn prepare_v18_import_for_shadow(
    json_text: &str,
) -> Result<PreparedV18Import, BackupError> {
    let mut root = parse_v18(json_text)?.root;
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
        &backup.poems,
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

/// Merge Windows-managed data into a decrypted v18 compatibility shadow.
///
/// Passing `None` creates a complete Android-readable v18 document with safe
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
/// a decrypted v18 compatibility shadow.
///
/// `cloud_sync_configs = None` preserves imported credential-free cloud
/// metadata and its unknown fields. Passing `Some` makes Windows the owner of
/// `cloudSyncConfigs`, preserves unknown sibling fields for matching IDs, and
/// disables Android cloud/usage collection toggles. Both paths unconditionally
/// remove device-local credentials, vault payloads and usage/step statistics
/// from the JSON boundary.
pub fn export_v18_merged_with_cloud_configs(
    database: &Database,
    decrypted_shadow: Option<&[u8]>,
    exported_at: i64,
    cloud_sync_configs: Option<&[CloudSyncConfig]>,
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
        "poems",
        serde_json::to_value(database.list_poems()?)?,
    );

    let encoded = serde_json::to_string_pretty(&root)?;
    require_size(encoded.as_bytes())?;
    // Validate our own output before it reaches disk. This catches locally
    // corrupted rows without risking an unreadable backup.
    parse_v18(&encoded)?;
    Ok(encoded)
}

pub fn recovery_point_bytes(database: &Database) -> Result<Vec<u8>, BackupError> {
    let recovery = RecoveryPoint {
        format: "DeskCubby Windows recovery".to_owned(),
        version: 1,
        snapshot: database.snapshot_core()?,
    };
    let bytes = serde_json::to_vec_pretty(&recovery)?;
    if bytes.len() > MAX_RECOVERY_POINT_BYTES {
        return Err(BackupError::RecoveryPointTooLarge);
    }
    Ok(bytes)
}

pub fn restore_recovery_point(database: &Database, bytes: &[u8]) -> Result<(), BackupError> {
    if bytes.len() > MAX_RECOVERY_POINT_BYTES {
        return Err(BackupError::RecoveryPointTooLarge);
    }
    let recovery: RecoveryPoint = serde_json::from_slice(bytes)?;
    if recovery.format != "DeskCubby Windows recovery" || recovery.version != 1 {
        return Err(invalid("Unsupported recovery point"));
    }
    database.restore_core_snapshot(&recovery.snapshot)?;
    Ok(())
}

#[derive(Debug, Serialize, Deserialize)]
struct RecoveryPoint {
    format: String,
    version: i32,
    snapshot: CoreSnapshot,
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

    let mut decoded = ManagedSettings {
        visual_style: required_string(settings, "visualStyle")?.to_owned(),
        dark_mode: required_string(settings, "darkMode")?.to_owned(),
        app_language: required_string(settings, "appLanguage")?.to_owned(),
        theme_color_argb: required_i32(settings, "themeColorArgb")?,
        theme_secondary_colors_argb,
        font_scale: f64::from(required_number(settings, "fontScale")? as f32),
        compact_mode: required_bool(settings, "compactMode")?,
        file_name_pattern: required_string(settings, "fileNamePattern")?.to_owned(),
        markdown_template: required_string(settings, "markdownTemplate")?.to_owned(),
        image_name_pattern: required_string(settings, "imageNamePattern")?.to_owned(),
        image_max_width_dp: required_coerced_i32(settings, "imageMaxWidthDp", 120, 2_400)?,
        image_max_height_dp: required_coerced_i32(settings, "imageMaxHeightDp", 120, 2_400)?,
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
        meal_photo_filter,
        meal_buttons_use_icons: required_bool(settings, "mealButtonsUseIcons")?,
        meal_button_icons,
        user_name: required_string(settings, "userName")?.to_owned(),
        home_greetings,
        home_widget_borders_enabled: required_bool(settings, "homeWidgetBordersEnabled")?,
        daily_event_templates,
        home_widgets: string_list(required_array(settings, "homeWidgets")?, "homeWidgets")?,
        home_widget_titles: string_list(
            required_array(settings, "homeWidgetTitles")?,
            "homeWidgetTitles",
        )?,
    };
    decoded.normalize_android_compatible();
    Ok(decoded)
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
    for (key, value) in managed_object {
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

/// Android intentionally excludes device-local credentials, encrypted vault
/// data and usage/step samples from v18. Exact known private keys are removed;
/// unrelated future fields and AI configuration fields remain untouched.
fn scrub_excluded_private_backup_fields(root: &mut Map<String, Value>) -> Result<(), BackupError> {
    const PRIVATE_BACKUP_FIELDS: [&str; 5] = [
        "vaultItems",
        "vaultMetadata",
        "usageStatistics",
        "stepStatistics",
        "cloudSyncSecrets",
    ];
    for field in PRIVATE_BACKUP_FIELDS {
        root.remove(field);
    }

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
    scrub_cloud_credential_fields(settings_value);

    Ok(())
}

const CLOUD_CREDENTIAL_FIELDS: [&str; 6] = [
    "cloudSyncCredentials",
    "cloudSyncSecrets",
    "webDavPassword",
    "s3AccessKey",
    "s3SecretKey",
    "s3SessionToken",
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
    let id = value.as_object()?.get("id")?;
    match id {
        Value::String(value) => Some(format!("s:{value}")),
        Value::Number(_) => value_i64(id, "id").ok().map(|value| format!("n:{value}")),
        _ => None,
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
            })
        })
        .collect()
}

fn validate_full_v18_settings(
    settings: &Map<String, Value>,
) -> Result<Vec<CloudSyncConfig>, BackupError> {
    require_enum(
        settings,
        "visualStyle",
        &["MATERIAL", "LIQUID_GLASS", "ORGANIC_FUTURE"],
    )?;
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
    validate_nullable_string_value(settings, "diaryTreeUri")?;
    validate_nullable_string_value(settings, "mediaTreeUri")?;
    require_string_limit(settings, "fileNamePattern", 1_024)?;
    require_string_limit(settings, "markdownTemplate", MAX_SETTING_STRING_CHARS)?;
    require_string_limit(settings, "imageNamePattern", 1_024)?;
    required_coerced_i32(settings, "imageMaxWidthDp", 120, 2_400)?;
    required_coerced_i32(settings, "imageMaxHeightDp", 120, 2_400)?;
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
    validate_string_list(required_array(settings, "homeWidgets")?, "homeWidgets")?;
    validate_string_list(
        required_array(settings, "homeWidgetTitles")?,
        "homeWidgetTitles",
    )?;
    Ok(cloud_sync_configs)
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
            let selected_contents = required_array(item, "selectedContents")?
                .iter()
                .enumerate()
                .map(|(content_index, content)| match content.as_str() {
                    Some("DIARIES") => Ok(CloudSyncContent::Diaries),
                    Some("MEDIA") => Ok(CloudSyncContent::Media),
                    Some("JSON_BACKUP") => Ok(CloudSyncContent::JsonBackup),
                    Some(_) => Err(invalid(format!(
                        "cloudSyncConfigs[{index}].selectedContents[{content_index}] has an unsupported value"
                    ))),
                    None => Err(invalid(format!(
                        "cloudSyncConfigs[{index}].selectedContents[{content_index}] must be a string"
                    ))),
                })
                .collect::<Result<Vec<_>, BackupError>>()?;
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
                web_dav_username: required_string(item, "webDavUsername")?.to_owned(),
                s3_bucket: required_string(item, "s3Bucket")?.to_owned(),
                s3_region: required_string(item, "s3Region")?.to_owned(),
                allow_insecure_http: required_bool(item, "allowInsecureHttp")?,
                selected_contents,
                direction,
            })
        })
        .collect::<Result<Vec<_>, BackupError>>()?;
    validate_cloud_sync_configs(&configs)?;
    Ok(configs)
}

/// Validate credential-free cloud metadata before it is persisted or exported.
///
/// The limits and enum set intentionally mirror Android v18.
pub fn validate_cloud_sync_configs(configs: &[CloudSyncConfig]) -> Result<(), BackupError> {
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
        "DIARY", "BLOG", "THOUGHT", "DATE", "POETRY", "RSS", "AI_CHAT", "VAULT", "GAMES", "USAGE",
        "STEPS",
    ];
    require_unique_enums(items, &allowed, "morePageOrder")
}

fn default_root(settings: &ManagedSettings, exported_at: i64) -> Value {
    let mut settings_value = json!({
        "visualStyle": "MATERIAL",
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
        "useChineseLauncherName": false,
        "launcherIcon": "CURRENT",
        "cloudSyncEnabled": false,
        "cloudSyncConfigs": [],
        "diaryTreeUri": null,
        "mediaTreeUri": null,
        "fileNamePattern": "yyyy-MM-dd",
        "markdownTemplate": "# {title}\n\n",
        "imageNamePattern": "{date}_{category}_{seq}",
        "imageMaxWidthDp": 720,
        "imageMaxHeightDp": 640,
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
            "DIARY", "BLOG", "THOUGHT", "DATE", "POETRY", "RSS", "AI_CHAT",
            "VAULT", "GAMES", "USAGE", "STEPS"
        ],
        "defaultPage": "HOME",
        "bottomNavShowLabels": true,
        "morePageShowDescriptions": true,
        "homeWidgets": [],
        "homeWidgetTitles": []
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
        "poems": []
    })
}

fn default_nav_items() -> Vec<Value> {
    let defaults = [
        ("HOME", "首页", "home", true, false, "今日概览与快捷记录"),
        ("DIARY", "日记", "book", true, false, "浏览、编辑日记与吃历"),
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
            "GAMES",
            "小游戏",
            "game",
            false,
            true,
            "4×4 / 5×5 / 6×6 版 2048、贪吃蛇与俄罗斯方块",
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
            "步数记录",
            "steps",
            false,
            true,
            "自动读取并可视化每日步数",
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

fn nav_ids() -> [&'static str; 14] {
    [
        "HOME", "DIARY", "BLOG", "THOUGHT", "DATE", "POETRY", "RSS", "AI_CHAT", "VAULT", "GAMES",
        "USAGE", "STEPS", "MORE", "SETTINGS",
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

    // A token is capped by the 10 MiB document limit. Exponents beyond this
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
        Err(invalid("Backup JSON exceeds the 10 MiB limit"))
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

    fn webdav_config(id: &str) -> CloudSyncConfig {
        CloudSyncConfig {
            id: id.to_owned(),
            name: "WebDAV".to_owned(),
            enabled: true,
            service_type: CloudSyncServiceType::WebDav,
            endpoint_url: "https://dav.example.com/remote.php/dav/files/alice".to_owned(),
            remote_path: "DeskCubby".to_owned(),
            web_dav_username: "alice".to_owned(),
            s3_bucket: String::new(),
            s3_region: "us-east-1".to_owned(),
            allow_insecure_http: false,
            selected_contents: vec![
                CloudSyncContent::Diaries,
                CloudSyncContent::Media,
                CloudSyncContent::JsonBackup,
            ],
            direction: CloudSyncDirection::TwoWay,
        }
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
            "webDavUsername": "alice",
            "s3Bucket": "",
            "s3Region": "us-east-1",
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
        root["settings"]["futureSetting"] = json!({"keep": true});
        root["vaultItems"] = json!([{"ciphertext": "private"}]);
        root["vaultMetadata"] = json!({"salt": "private", "verifier": "private"});
        root["usageStatistics"] = json!({"private": true});
        root["stepStatistics"] = json!([{"private": true}]);
        root["cloudSyncSecrets"] = json!({"private": true});
        // Similar but non-designated unknown keys prove the scrub is exact.
        root["vault"] = json!({"future": "keep"});
        root["usage"] = json!({"future": "keep"});
        root["futureRoot"] = json!({"keep": true});
        root
    }

    fn assert_private_fields_scrubbed(output: &Value) {
        for field in [
            "vaultItems",
            "vaultMetadata",
            "usageStatistics",
            "stepStatistics",
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
        assert_eq!(output["settings"]["futureSetting"], json!({"keep": true}));
        assert_eq!(output["futureRoot"], json!({"keep": true}));
        assert_eq!(output["vault"], json!({"future": "keep"}));
        assert_eq!(output["usage"], json!({"future": "keep"}));
    }

    #[test]
    fn parses_and_previews_v18() {
        let backup = parse_v18(&valid_json()).expect("parse");
        assert_eq!(backup.preview().format_version, 18);
        assert_eq!(backup.preview().thought_count, 0);
    }

    #[test]
    fn android_v18_golden_survives_windows_edit_and_export() {
        let source = include_str!("../test-data/android-v18-golden.json");
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
        parse_v18(&exported).expect("Android-readable v18 output");
    }

    #[test]
    fn cloud_overlay_merges_golden_unknown_fields_by_id_and_disables_android_sync() {
        let source = include_str!("../test-data/android-v18-golden.json");
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
    fn cloud_metadata_enforces_enum_uniqueness_and_count_limits() {
        let mut root: Value = serde_json::from_str(&valid_json()).expect("fixture");
        let raw = serde_json::to_value(webdav_config("dav")).expect("config");
        root["settings"]["cloudSyncConfigs"] = Value::Array(vec![raw.clone()]);

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
    fn rejects_old_versions_and_duplicate_ids() {
        let mut root: Value = serde_json::from_str(&valid_json()).expect("fixture");
        root["version"] = Value::from(17);
        assert!(parse_v18(&root.to_string()).is_err());

        root["version"] = Value::from(18);
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
            "webDavUsername": "",
            "s3Bucket": "bucket",
            "s3Region": "us-east-1",
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
        let root = default_root(&ManagedSettings::default(), 1);
        assert_eq!(
            root["settings"]["calorieVisionPrompt"],
            Value::String("识别图片中的所有食物和饮料。只返回 JSON，不要 Markdown：{\"foods\":[{\"name\":\"食物名称\",\"amount\":\"估计份量\",\"unit\":\"单位\",\"confidence\":0.0}],\"notes\":\"必要说明\"}。无法确定时给出合理估计并降低 confidence。".to_owned())
        );
        assert_eq!(
            root["settings"]["calorieTextPrompt"],
            Value::String("根据随后提供的食物识别 JSON，估算整张图片中食物的总能量。只返回 JSON，不要 Markdown：{\"energyKj\":整数}。energyKj 使用千焦(kJ)，综合份量并避免重复计算。".to_owned())
        );
        assert_eq!(
            root["settings"]["homeGreetings"]
                .as_array()
                .expect("greetings")
                .len(),
            24
        );
        let nav = root["settings"]["navItems"].as_array().expect("nav");
        assert_eq!(nav.len(), 14);
        assert_eq!(nav[0]["moreDescription"], "今日概览与快捷记录");
        assert_eq!(
            nav[9]["moreDescription"],
            "4×4 / 5×5 / 6×6 版 2048、贪吃蛇与俄罗斯方块"
        );
        assert_eq!(nav[13]["moreDescription"], "调整应用与页面设置");
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
    fn enforces_ten_mib_limit_before_parsing() {
        let huge = " ".repeat(MAX_JSON_BYTES + 1);
        assert!(matches!(
            parse_v18(&huge),
            Err(BackupError::Invalid(message)) if message.contains("10 MiB")
        ));
    }
}
