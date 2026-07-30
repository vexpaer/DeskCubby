//! Read-only Android phone-usage statistics for the Windows client.
//!
//! Android deliberately keeps `usage-statistics.json` outside the v18 app
//! backup.  This module therefore accepts only a file explicitly selected by
//! the user.  A snapshot import copies a canonical v4 payload into the private
//! app directory; a linked import additionally remembers the selected file so
//! that a later refresh can read it again.  Refresh never opens a source for
//! writing and never removes, renames, or otherwise modifies it.
//!
//! The WebView receives aggregated DTOs, not the source path or raw JSON.  The
//! private state is one atomically replaced container containing two
//! purpose-bound DPAPI payloads: source metadata and the canonical v4 snapshot.

use crate::security::{
    SecurityError, dpapi_protect_scoped, dpapi_unprotect_scoped, open_regular_file_no_reparse,
    reject_reparse_point, resolve_path_beneath,
};
use chrono::{Duration, NaiveDate};
use chrono_tz::Tz;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::str::FromStr;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::UNIX_EPOCH;
use uuid::Uuid;

pub(crate) const ANDROID_USAGE_SCHEMA_VERSION: i64 = 4;
pub(crate) const MAX_USAGE_JSON_BYTES: usize = 10 * 1024 * 1024;
pub(crate) const MAX_USAGE_DAYS: usize = 36_600;
pub(crate) const MAX_APPS_PER_DAY: usize = 4_096;
pub(crate) const MAX_PACKAGE_NAME_UTF16_UNITS: usize = 255;
pub(crate) const MAX_ZONE_ID_UTF16_UNITS: usize = 128;
pub(crate) const MAX_FOREGROUND_MILLIS_PER_APP_DAY: i64 = 26 * 60 * 60 * 1_000;
pub(crate) const MAX_USAGE_APP_CHOICES: usize = 512;
pub(crate) const USAGE_DTO_VERSION: u32 = 1;

const STATE_FILE_NAME: &str = "phone-usage-state.dcus";
const STATE_CONTAINER_MAGIC: &[u8; 8] = b"DCUSGV1\0";
const SNAPSHOT_PURPOSE: &str = "DeskCubby.Windows.PhoneUsage.Snapshot.v1";
const SOURCE_PURPOSE: &str = "DeskCubby.Windows.PhoneUsage.Source.v1";
const SOURCE_RECORD_TYPE: &str = "phoneUsageSourceV1";
const MAX_SOURCE_METADATA_BYTES: usize = 256 * 1024;
const MAX_SOURCE_PATH_UTF16_UNITS: usize = 32_767;
const MAX_PROTECTED_SNAPSHOT_BYTES: usize = MAX_USAGE_JSON_BYTES + 2 * 1024 * 1024;
const MAX_PROTECTED_SOURCE_BYTES: usize = MAX_SOURCE_METADATA_BYTES + 1024 * 1024;
const MAX_STATE_CONTAINER_BYTES: usize =
    MAX_PROTECTED_SNAPSHOT_BYTES + MAX_PROTECTED_SOURCE_BYTES + 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub(crate) enum UsageServiceError {
    #[error("phone usage JSON is invalid")]
    InvalidJson,
    #[error("phone usage JSON exceeds its size limit")]
    TooLarge,
    #[error("the selected source is not an allowed regular file")]
    PathNotAllowed,
    #[error("the selected source no longer exists")]
    NotFound,
    #[error("the selected source changed while it was being read")]
    SourceChanged,
    #[error("the private phone usage cache is unavailable")]
    Storage,
    #[error("the private phone usage cache could not be decrypted")]
    Crypto,
    #[error("the private phone usage cache is corrupt")]
    CacheCorrupt,
    #[error("no phone usage snapshot has been imported")]
    NotConfigured,
    #[error("the current phone usage source is not linked")]
    NotLinked,
}

impl UsageServiceError {
    /// Stable IPC code.  The error itself never contains a source path, package
    /// name, JSON excerpt, or platform error message.
    pub(crate) const fn code(self) -> &'static str {
        match self {
            Self::InvalidJson => "usage_statistics_invalid",
            Self::TooLarge => "usage_statistics_too_large",
            Self::PathNotAllowed => "path_not_allowed",
            Self::NotFound => "usage_statistics_source_missing",
            Self::SourceChanged => "usage_statistics_source_changed",
            Self::Storage => "storage_unavailable",
            Self::Crypto | Self::CacheCorrupt => "usage_statistics_cache_unavailable",
            Self::NotConfigured => "usage_statistics_not_configured",
            Self::NotLinked => "usage_statistics_not_linked",
        }
    }
}

impl From<SecurityError> for UsageServiceError {
    fn from(value: SecurityError) -> Self {
        match value {
            SecurityError::InvalidInput => Self::InvalidJson,
            SecurityError::PathNotAllowed => Self::PathNotAllowed,
            SecurityError::NotFound => Self::NotFound,
            SecurityError::Storage => Self::Storage,
            SecurityError::Crypto => Self::Crypto,
            #[cfg(not(windows))]
            SecurityError::Unsupported => Self::Crypto,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum UsageDayState {
    Open,
    Final,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct UsageAppDuration {
    pub(crate) package_name: String,
    pub(crate) foreground_millis: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct UsageDay {
    pub(crate) date: String,
    pub(crate) zone_id: String,
    pub(crate) state: UsageDayState,
    pub(crate) collected_at_epoch_millis: i64,
    pub(crate) apps: Vec<UsageAppDuration>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct UsageHistory {
    pub(crate) tracking_started_on: Option<String>,
    pub(crate) backfill_completed_through: Option<String>,
    pub(crate) days: Vec<UsageDay>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CanonicalUsageHistory<'a> {
    schema_version: i64,
    tracking_started_on: Option<&'a str>,
    backfill_completed_through: Option<&'a str>,
    days: &'a [UsageDay],
}

/// Parse an Android `usage-statistics.json` v4 payload with the same structural
/// limits as Android.  v1-v3 are intentionally rejected: Android v2/v3 rows
/// require an event-stream rebuild that Windows cannot perform.
pub(crate) fn parse_android_usage_v4(bytes: &[u8]) -> Result<UsageHistory, UsageServiceError> {
    if bytes.len() > MAX_USAGE_JSON_BYTES {
        return Err(UsageServiceError::TooLarge);
    }
    let value = parse_json_without_duplicate_keys(bytes)?;
    let root = required_object(&value)?;
    require_exact_keys(
        root,
        &[
            "schemaVersion",
            "trackingStartedOn",
            "backfillCompletedThrough",
            "days",
        ],
    )?;
    if required_i64(root, "schemaVersion", 4, 4)? != ANDROID_USAGE_SCHEMA_VERSION {
        return Err(UsageServiceError::InvalidJson);
    }

    let tracking_started_on = required_nullable_date(root, "trackingStartedOn")?;
    let backfill_completed_through = required_nullable_date(root, "backfillCompletedThrough")?;
    let day_values = required_array(root, "days", MAX_USAGE_DAYS)?;
    let mut days = Vec::with_capacity(day_values.len());
    let mut dates = HashSet::with_capacity(day_values.len());

    for value in day_values {
        let day = required_object(value)?;
        require_exact_keys(
            day,
            &["date", "zoneId", "state", "collectedAtEpochMillis", "apps"],
        )?;
        let date = required_date(day, "date")?;
        if !dates.insert(date.clone()) {
            return Err(UsageServiceError::InvalidJson);
        }
        if let Some(started) = tracking_started_on.as_deref()
            && parse_date(&date)? < parse_date(started)?
        {
            return Err(UsageServiceError::InvalidJson);
        }
        let zone_id = required_string(day, "zoneId", MAX_ZONE_ID_UTF16_UNITS)?;
        validate_android_zone_id(&zone_id)?;
        let state = match required_string(day, "state", 16)?.as_str() {
            "OPEN" => UsageDayState::Open,
            "FINAL" => UsageDayState::Final,
            _ => return Err(UsageServiceError::InvalidJson),
        };
        let collected_at_epoch_millis = required_i64(day, "collectedAtEpochMillis", 0, i64::MAX)?;
        let app_values = required_array(day, "apps", MAX_APPS_PER_DAY)?;
        let mut apps = Vec::with_capacity(app_values.len());
        let mut package_names = HashSet::with_capacity(app_values.len());
        for value in app_values {
            let app = required_object(value)?;
            require_exact_keys(app, &["packageName", "foregroundMillis"])?;
            let package_name = required_string(app, "packageName", MAX_PACKAGE_NAME_UTF16_UNITS)?;
            validate_package_name(&package_name)?;
            if !package_names.insert(package_name.clone()) {
                return Err(UsageServiceError::InvalidJson);
            }
            let foreground_millis = required_i64(
                app,
                "foregroundMillis",
                0,
                MAX_FOREGROUND_MILLIS_PER_APP_DAY,
            )?;
            apps.push(UsageAppDuration {
                package_name,
                foreground_millis,
            });
        }
        apps.sort_by(|left, right| left.package_name.cmp(&right.package_name));
        days.push(UsageDay {
            date,
            zone_id,
            state,
            collected_at_epoch_millis,
            apps,
        });
    }

    if !days.is_empty() && tracking_started_on.is_none() {
        return Err(UsageServiceError::InvalidJson);
    }
    days.sort_by(|left, right| left.date.cmp(&right.date));
    let history = UsageHistory {
        tracking_started_on,
        backfill_completed_through,
        days,
    };
    // Encoding is also validation of all aggregate-size and deterministic
    // canonicalization assumptions used by the private cache.
    canonical_android_usage_v4(&history)?;
    Ok(history)
}

/// `serde_json::Value` normally keeps the last value for a repeated object
/// member.  Deserialize once through an exact typed v4 schema first: Serde's
/// struct visitor rejects duplicate, missing, and unknown members before the
/// existing bounded semantic validation consumes a `Value`.
///
/// The typed pass is also important because this crate enables
/// `serde_json/arbitrary_precision`.  Parsing an attacker-controlled object
/// whose first key is serde_json's private number marker directly into `Value`
/// can otherwise make the object look like a number.
fn parse_json_without_duplicate_keys(bytes: &[u8]) -> Result<Value, UsageServiceError> {
    #[allow(dead_code)]
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct StrictUsageFile {
        schema_version: i64,
        tracking_started_on: RequiredNullableString,
        backfill_completed_through: RequiredNullableString,
        days: Vec<StrictUsageDay>,
    }

    #[allow(dead_code)]
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct StrictUsageDay {
        date: String,
        zone_id: String,
        state: String,
        collected_at_epoch_millis: i64,
        apps: Vec<StrictUsageApp>,
    }

    #[allow(dead_code)]
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct StrictUsageApp {
        package_name: String,
        foreground_millis: i64,
    }

    #[allow(dead_code)]
    struct RequiredNullableString(Option<String>);

    impl<'de> Deserialize<'de> for RequiredNullableString {
        fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
        where
            D: serde::Deserializer<'de>,
        {
            Option::<String>::deserialize(deserializer).map(Self)
        }
    }

    serde_json::from_slice::<StrictUsageFile>(bytes).map_err(|_| UsageServiceError::InvalidJson)?;
    serde_json::from_slice(bytes).map_err(|_| UsageServiceError::InvalidJson)
}

pub(crate) fn canonical_android_usage_v4(
    history: &UsageHistory,
) -> Result<Vec<u8>, UsageServiceError> {
    validate_history(history)?;
    let mut normalized = history.clone();
    normalized
        .days
        .sort_by(|left, right| left.date.cmp(&right.date));
    for day in &mut normalized.days {
        day.apps
            .sort_by(|left, right| left.package_name.cmp(&right.package_name));
    }
    let bytes = serde_json::to_vec(&CanonicalUsageHistory {
        schema_version: ANDROID_USAGE_SCHEMA_VERSION,
        tracking_started_on: normalized.tracking_started_on.as_deref(),
        backfill_completed_through: normalized.backfill_completed_through.as_deref(),
        days: &normalized.days,
    })
    .map_err(|_| UsageServiceError::InvalidJson)?;
    if bytes.len() > MAX_USAGE_JSON_BYTES {
        return Err(UsageServiceError::TooLarge);
    }
    Ok(bytes)
}

fn validate_history(history: &UsageHistory) -> Result<(), UsageServiceError> {
    if history.days.len() > MAX_USAGE_DAYS {
        return Err(UsageServiceError::InvalidJson);
    }
    if let Some(value) = history.tracking_started_on.as_deref() {
        parse_date(value)?;
    }
    if let Some(value) = history.backfill_completed_through.as_deref() {
        // Android validates this as a date but intentionally does not constrain
        // it relative to trackingStartedOn or the day rows.
        parse_date(value)?;
    }
    if !history.days.is_empty() && history.tracking_started_on.is_none() {
        return Err(UsageServiceError::InvalidJson);
    }
    let tracking_started = history
        .tracking_started_on
        .as_deref()
        .map(parse_date)
        .transpose()?;
    let mut dates = HashSet::with_capacity(history.days.len());
    for day in &history.days {
        let date = parse_date(&day.date)?;
        if !dates.insert(day.date.as_str()) || tracking_started.is_some_and(|start| date < start) {
            return Err(UsageServiceError::InvalidJson);
        }
        validate_android_zone_id(&day.zone_id)?;
        if day.collected_at_epoch_millis < 0 || day.apps.len() > MAX_APPS_PER_DAY {
            return Err(UsageServiceError::InvalidJson);
        }
        let mut packages = HashSet::with_capacity(day.apps.len());
        for app in &day.apps {
            validate_package_name(&app.package_name)?;
            if !packages.insert(app.package_name.as_str())
                || !(0..=MAX_FOREGROUND_MILLIS_PER_APP_DAY).contains(&app.foreground_millis)
            {
                return Err(UsageServiceError::InvalidJson);
            }
        }
    }
    Ok(())
}

fn required_object(value: &Value) -> Result<&Map<String, Value>, UsageServiceError> {
    value.as_object().ok_or(UsageServiceError::InvalidJson)
}

fn require_exact_keys(
    object: &Map<String, Value>,
    expected: &[&str],
) -> Result<(), UsageServiceError> {
    if object.len() != expected.len() || expected.iter().any(|key| !object.contains_key(*key)) {
        return Err(UsageServiceError::InvalidJson);
    }
    Ok(())
}

fn required_array<'a>(
    object: &'a Map<String, Value>,
    key: &str,
    maximum: usize,
) -> Result<&'a Vec<Value>, UsageServiceError> {
    let values = object
        .get(key)
        .and_then(Value::as_array)
        .ok_or(UsageServiceError::InvalidJson)?;
    if values.len() > maximum {
        return Err(UsageServiceError::InvalidJson);
    }
    Ok(values)
}

fn required_string(
    object: &Map<String, Value>,
    key: &str,
    maximum_utf16_units: usize,
) -> Result<String, UsageServiceError> {
    let value = object
        .get(key)
        .and_then(Value::as_str)
        .ok_or(UsageServiceError::InvalidJson)?;
    if value.encode_utf16().count() > maximum_utf16_units {
        return Err(UsageServiceError::InvalidJson);
    }
    Ok(value.to_owned())
}

fn required_i64(
    object: &Map<String, Value>,
    key: &str,
    minimum: i64,
    maximum: i64,
) -> Result<i64, UsageServiceError> {
    let value = object
        .get(key)
        .and_then(Value::as_i64)
        .ok_or(UsageServiceError::InvalidJson)?;
    if !(minimum..=maximum).contains(&value) {
        return Err(UsageServiceError::InvalidJson);
    }
    Ok(value)
}

fn required_date(object: &Map<String, Value>, key: &str) -> Result<String, UsageServiceError> {
    let value = required_string(object, key, 10)?;
    parse_date(&value)?;
    Ok(value)
}

fn required_nullable_date(
    object: &Map<String, Value>,
    key: &str,
) -> Result<Option<String>, UsageServiceError> {
    match object.get(key) {
        Some(Value::Null) => Ok(None),
        Some(Value::String(value)) if value.encode_utf16().count() <= 10 => {
            parse_date(value)?;
            Ok(Some(value.clone()))
        }
        _ => Err(UsageServiceError::InvalidJson),
    }
}

fn parse_date(value: &str) -> Result<NaiveDate, UsageServiceError> {
    if value.len() != 10 {
        return Err(UsageServiceError::InvalidJson);
    }
    let date =
        NaiveDate::parse_from_str(value, "%Y-%m-%d").map_err(|_| UsageServiceError::InvalidJson)?;
    if date.format("%Y-%m-%d").to_string() != value {
        return Err(UsageServiceError::InvalidJson);
    }
    Ok(date)
}

fn validate_package_name(value: &str) -> Result<(), UsageServiceError> {
    if value.is_empty()
        || value.encode_utf16().count() > MAX_PACKAGE_NAME_UTF16_UNITS
        || value.chars().any(|character| {
            character.is_whitespace() || character.is_control() || is_iso_control(character)
        })
    {
        return Err(UsageServiceError::InvalidJson);
    }
    Ok(())
}

fn is_iso_control(character: char) -> bool {
    matches!(character as u32, 0x0000..=0x001f | 0x007f..=0x009f)
}

fn validate_android_zone_id(value: &str) -> Result<(), UsageServiceError> {
    if value.is_empty()
        || value.encode_utf16().count() > MAX_ZONE_ID_UTF16_UNITS
        || value
            .chars()
            .any(|character| character.is_whitespace() || character.is_control())
    {
        return Err(UsageServiceError::InvalidJson);
    }
    if Tz::from_str(value).is_ok() || valid_java_fixed_zone_id(value) {
        Ok(())
    } else {
        Err(UsageServiceError::InvalidJson)
    }
}

fn valid_java_fixed_zone_id(value: &str) -> bool {
    if matches!(value, "Z" | "UTC" | "GMT" | "UT") {
        return true;
    }
    let offset = ["UTC", "GMT", "UT"]
        .iter()
        .find_map(|prefix| value.strip_prefix(prefix))
        .filter(|suffix| !suffix.is_empty())
        .unwrap_or(value);
    let Some(sign) = offset.as_bytes().first().copied() else {
        return false;
    };
    if sign != b'+' && sign != b'-' {
        return false;
    }
    parse_java_offset_components(&offset[1..]).is_some_and(|(hours, minutes, seconds)| {
        minutes < 60
            && seconds < 60
            && (hours < 18 || (hours == 18 && minutes == 0 && seconds == 0))
    })
}

fn parse_java_offset_components(digits: &str) -> Option<(u32, u32, u32)> {
    if digits.contains(':') {
        let parts = digits.split(':').collect::<Vec<_>>();
        if !(1..=3).contains(&parts.len())
            || !(1..=2).contains(&parts[0].len())
            || parts.iter().any(|part| part.is_empty())
            || parts.iter().skip(1).any(|part| part.len() != 2)
            || parts
                .iter()
                .any(|part| !part.bytes().all(|byte| byte.is_ascii_digit()))
        {
            None
        } else {
            Some((
                parts[0].parse().ok()?,
                parts.get(1).and_then(|part| part.parse().ok()).unwrap_or(0),
                parts.get(2).and_then(|part| part.parse().ok()).unwrap_or(0),
            ))
        }
    } else if digits.bytes().all(|byte| byte.is_ascii_digit()) {
        match digits.len() {
            1 | 2 => Some((digits.parse().ok()?, 0, 0)),
            4 => Some((digits[..2].parse().ok()?, digits[2..].parse().ok()?, 0)),
            6 => Some((
                digits[..2].parse().ok()?,
                digits[2..4].parse().ok()?,
                digits[4..].parse().ok()?,
            )),
            _ => None,
        }
    } else {
        None
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum UsageSourceModeDto {
    Snapshot,
    LinkedFile,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum UsageSourceStateDto {
    Ready,
    Stale,
    Missing,
    Invalid,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct UsageSourceStatusDto {
    pub(crate) dto_version: u32,
    pub(crate) mode: UsageSourceModeDto,
    pub(crate) state: UsageSourceStateDto,
    pub(crate) display_name: String,
    pub(crate) can_refresh: bool,
    pub(crate) last_successful_read_at_ms: String,
    pub(crate) last_attempt_at_ms: String,
    pub(crate) source_modified_at_ms: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub(crate) enum UsageRangeDto {
    #[serde(rename = "LAST_7_DAYS")]
    Last7Days,
    #[serde(rename = "LAST_30_DAYS")]
    #[default]
    Last30Days,
    #[serde(rename = "LAST_90_DAYS")]
    Last90Days,
    #[serde(rename = "ALL")]
    All,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct UsageQueryDto {
    #[serde(deserialize_with = "deserialize_usage_dto_version")]
    pub(crate) dto_version: u32,
    #[serde(default)]
    pub(crate) range: UsageRangeDto,
    #[serde(default)]
    pub(crate) package_name: Option<String>,
}

fn deserialize_usage_dto_version<'de, D>(deserializer: D) -> Result<u32, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let version = u32::deserialize(deserializer)?;
    if version == USAGE_DTO_VERSION {
        Ok(version)
    } else {
        Err(<D::Error as serde::de::Error>::custom(
            "unsupported phone usage DTO version",
        ))
    }
}

impl Default for UsageQueryDto {
    fn default() -> Self {
        Self {
            dto_version: USAGE_DTO_VERSION,
            range: UsageRangeDto::default(),
            package_name: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct UsageAppChoiceDto {
    pub(crate) package_name: String,
    pub(crate) label: String,
    pub(crate) range_millis: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct UsagePointDto {
    pub(crate) date: String,
    pub(crate) zone_id: String,
    pub(crate) state: UsageDayState,
    pub(crate) collected_at_epoch_millis: String,
    pub(crate) value_millis: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct UsageOverviewDto {
    pub(crate) range_started_on: Option<String>,
    pub(crate) recorded_days: u32,
    pub(crate) total_millis: String,
    pub(crate) average_millis: String,
    pub(crate) highest_day_millis: String,
    pub(crate) last_seven_average_millis: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct UsageSnapshotDto {
    pub(crate) dto_version: u32,
    pub(crate) source: UsageSourceStatusDto,
    pub(crate) tracking_started_on: Option<String>,
    pub(crate) backfill_completed_through: Option<String>,
    pub(crate) anchor_date: Option<String>,
    pub(crate) selected_package_name: Option<String>,
    pub(crate) overview: UsageOverviewDto,
    pub(crate) app_choices: Vec<UsageAppChoiceDto>,
    pub(crate) points: Vec<UsagePointDto>,
}

pub(crate) fn aggregate_usage_snapshot(
    history: &UsageHistory,
    source: UsageSourceStatusDto,
    query: &UsageQueryDto,
) -> Result<UsageSnapshotDto, UsageServiceError> {
    if query.dto_version != USAGE_DTO_VERSION {
        return Err(UsageServiceError::InvalidJson);
    }
    if let Some(package_name) = query.package_name.as_deref() {
        validate_package_name(package_name)?;
        if !history
            .days
            .iter()
            .any(|day| day.apps.iter().any(|app| app.package_name == package_name))
        {
            return Err(UsageServiceError::InvalidJson);
        }
    }

    let anchor = history
        .days
        .last()
        .map(|day| parse_date(&day.date))
        .transpose()?;
    let range_start = match (anchor, query.range) {
        (Some(anchor), UsageRangeDto::Last7Days) => Some(
            anchor
                .checked_sub_signed(Duration::days(6))
                .ok_or(UsageServiceError::InvalidJson)?,
        ),
        (Some(anchor), UsageRangeDto::Last30Days) => Some(
            anchor
                .checked_sub_signed(Duration::days(29))
                .ok_or(UsageServiceError::InvalidJson)?,
        ),
        (Some(anchor), UsageRangeDto::Last90Days) => Some(
            anchor
                .checked_sub_signed(Duration::days(89))
                .ok_or(UsageServiceError::InvalidJson)?,
        ),
        (_, UsageRangeDto::All) | (None, _) => None,
    };
    let ranged = history
        .days
        .iter()
        .filter(|day| {
            range_start.is_none_or(|start| {
                parse_date(&day.date)
                    .map(|date| date >= start)
                    .unwrap_or(false)
            })
        })
        .collect::<Vec<_>>();
    let selected = query.package_name.as_deref();
    let points = ranged
        .iter()
        .map(|day| {
            let value = day_value(day, selected)?;
            Ok(UsagePointDto {
                date: day.date.clone(),
                zone_id: day.zone_id.clone(),
                state: day.state,
                collected_at_epoch_millis: day.collected_at_epoch_millis.to_string(),
                value_millis: value.to_string(),
            })
        })
        .collect::<Result<Vec<_>, UsageServiceError>>()?;

    let values = points
        .iter()
        .map(|point| {
            point
                .value_millis
                .parse::<i64>()
                .map_err(|_| UsageServiceError::InvalidJson)
        })
        .collect::<Result<Vec<_>, _>>()?;
    let total = checked_sum(values.iter().copied())?;
    let highest = values.iter().copied().max().unwrap_or(0);
    let average = if values.is_empty() {
        0
    } else {
        total / i64::try_from(values.len()).map_err(|_| UsageServiceError::InvalidJson)?
    };

    let last_seven_start = match anchor {
        Some(date) => Some(
            date.checked_sub_signed(Duration::days(6))
                .ok_or(UsageServiceError::InvalidJson)?,
        ),
        None => None,
    };
    let last_seven_values = history
        .days
        .iter()
        .filter(|day| {
            last_seven_start.is_none_or(|start| {
                parse_date(&day.date)
                    .map(|date| date >= start)
                    .unwrap_or(false)
            })
        })
        .map(|day| day_value(day, selected))
        .collect::<Result<Vec<_>, _>>()?;
    let last_seven_total = checked_sum(last_seven_values.iter().copied())?;
    let last_seven_average = if last_seven_values.is_empty() {
        0
    } else {
        last_seven_total
            / i64::try_from(last_seven_values.len()).map_err(|_| UsageServiceError::InvalidJson)?
    };

    let app_choices = rank_app_choices(history, &ranged, selected)?;
    Ok(UsageSnapshotDto {
        dto_version: USAGE_DTO_VERSION,
        source,
        tracking_started_on: history.tracking_started_on.clone(),
        backfill_completed_through: history.backfill_completed_through.clone(),
        anchor_date: anchor.map(|date| date.format("%Y-%m-%d").to_string()),
        selected_package_name: query.package_name.clone(),
        overview: UsageOverviewDto {
            range_started_on: ranged.first().map(|day| day.date.clone()),
            recorded_days: u32::try_from(ranged.len())
                .map_err(|_| UsageServiceError::InvalidJson)?,
            total_millis: total.to_string(),
            average_millis: average.to_string(),
            highest_day_millis: highest.to_string(),
            last_seven_average_millis: last_seven_average.to_string(),
        },
        app_choices,
        points,
    })
}

fn day_value(day: &UsageDay, package_name: Option<&str>) -> Result<i64, UsageServiceError> {
    if let Some(package_name) = package_name {
        Ok(day
            .apps
            .iter()
            .find(|app| app.package_name == package_name)
            .map(|app| app.foreground_millis)
            .unwrap_or(0))
    } else {
        checked_sum(day.apps.iter().map(|app| app.foreground_millis))
    }
}

fn checked_sum(mut values: impl Iterator<Item = i64>) -> Result<i64, UsageServiceError> {
    values.try_fold(0_i64, |total, value| {
        total
            .checked_add(value)
            .ok_or(UsageServiceError::InvalidJson)
    })
}

fn rank_app_choices(
    history: &UsageHistory,
    ranged: &[&UsageDay],
    selected: Option<&str>,
) -> Result<Vec<UsageAppChoiceDto>, UsageServiceError> {
    let mut totals = HashMap::<&str, i64>::new();
    for day in ranged {
        for app in &day.apps {
            let current = totals.entry(app.package_name.as_str()).or_default();
            *current = current
                .checked_add(app.foreground_millis)
                .ok_or(UsageServiceError::InvalidJson)?;
        }
    }
    let mut ranked = totals
        .into_iter()
        .filter(|(_, duration)| *duration > 0)
        .map(|(package_name, duration)| (package_name.to_owned(), duration))
        .collect::<Vec<_>>();
    let selected_is_recorded = selected.is_some_and(|package_name| {
        history
            .days
            .iter()
            .any(|day| day.apps.iter().any(|app| app.package_name == package_name))
    });
    if let Some(package_name) = selected
        && selected_is_recorded
        && !ranked
            .iter()
            .any(|(candidate, _)| candidate == package_name)
    {
        ranked.push((package_name.to_owned(), 0));
    }
    ranked.sort_by(|left, right| right.1.cmp(&left.1).then_with(|| left.0.cmp(&right.0)));
    if ranked.len() > MAX_USAGE_APP_CHOICES {
        let selected_value = selected.and_then(|package_name| {
            ranked
                .iter()
                .find(|(candidate, _)| candidate == package_name)
                .cloned()
        });
        ranked.truncate(MAX_USAGE_APP_CHOICES);
        if let Some(selected_value) = selected_value
            && !ranked.iter().any(|value| value.0 == selected_value.0)
        {
            if let Some(last) = ranked.last_mut() {
                *last = selected_value;
            }
            ranked.sort_by(|left, right| right.1.cmp(&left.1).then_with(|| left.0.cmp(&right.0)));
        }
    }
    Ok(ranked
        .into_iter()
        .map(|(package_name, duration)| UsageAppChoiceDto {
            label: fallback_usage_app_label(&package_name),
            package_name,
            range_millis: duration.to_string(),
        })
        .collect())
}

fn fallback_usage_app_label(package_name: &str) -> String {
    let parts = package_name
        .split('.')
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>();
    let start = if parts.len() >= 2 && parts.last().is_some_and(|part| part.chars().count() <= 2) {
        parts.len() - 2
    } else {
        parts.len().saturating_sub(1)
    };
    let label = parts[start..]
        .iter()
        .flat_map(|part| part.split(['_', '-']))
        .filter(|part| !part.is_empty())
        .map(|part| {
            let mut characters = part.chars();
            match characters.next() {
                Some(first) => first.to_uppercase().collect::<String>() + characters.as_str(),
                None => String::new(),
            }
        })
        .collect::<Vec<_>>()
        .join(" ");
    if label.is_empty() {
        "App".to_owned()
    } else {
        label
    }
}

trait RawProtector: Send + Sync {
    fn protect(
        &self,
        plaintext: &[u8],
        purpose: &[u8],
        maximum_plaintext: usize,
    ) -> Result<Vec<u8>, UsageServiceError>;
    fn unprotect(
        &self,
        ciphertext: &[u8],
        purpose: &[u8],
        maximum_plaintext: usize,
    ) -> Result<Vec<u8>, UsageServiceError>;
}

struct DpapiRawProtector;

impl RawProtector for DpapiRawProtector {
    fn protect(
        &self,
        plaintext: &[u8],
        purpose: &[u8],
        maximum_plaintext: usize,
    ) -> Result<Vec<u8>, UsageServiceError> {
        dpapi_protect_scoped(plaintext, purpose, maximum_plaintext)
            .map_err(|_| UsageServiceError::Crypto)
    }

    fn unprotect(
        &self,
        ciphertext: &[u8],
        purpose: &[u8],
        maximum_plaintext: usize,
    ) -> Result<Vec<u8>, UsageServiceError> {
        dpapi_unprotect_scoped(ciphertext, purpose, maximum_plaintext)
            .map_err(|_| UsageServiceError::Crypto)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StoredSourceMetadata {
    record_type: String,
    mode: UsageSourceModeDto,
    state: UsageSourceStateDto,
    display_name: String,
    linked_path: Option<String>,
    last_successful_read_at_ms: i64,
    last_attempt_at_ms: i64,
    source_modified_at_ms: Option<i64>,
    source_size: u64,
    source_sha256: String,
    snapshot_sha256: String,
}

struct StoredState {
    metadata: StoredSourceMetadata,
    history: UsageHistory,
    canonical: Vec<u8>,
}

struct StableSource {
    canonical_path: PathBuf,
    display_name: String,
    modified_at_ms: Option<i64>,
    size: u64,
    source_sha256: String,
    canonical: Vec<u8>,
}

pub struct UsageStatisticsService {
    private_dir: PathBuf,
    operation: Mutex<()>,
    protector: Arc<dyn RawProtector>,
}

impl UsageStatisticsService {
    pub(crate) fn new(private_dir: PathBuf) -> Result<Self, UsageServiceError> {
        Self::with_protector(private_dir, Arc::new(DpapiRawProtector))
    }

    fn with_protector(
        private_dir: PathBuf,
        protector: Arc<dyn RawProtector>,
    ) -> Result<Self, UsageServiceError> {
        ensure_private_directory(&private_dir)?;
        Ok(Self {
            private_dir,
            operation: Mutex::new(()),
            protector,
        })
    }

    /// Import a one-time snapshot.  A failure leaves any prior valid state
    /// untouched.
    pub(crate) fn import_snapshot(
        &self,
        selected_path: &Path,
        now_ms: i64,
    ) -> Result<UsageSourceStatusDto, UsageServiceError> {
        let _guard = self.lock()?;
        let source = read_stable_source(selected_path)?;
        let metadata = metadata_for_source(
            &source,
            UsageSourceModeDto::Snapshot,
            UsageSourceStateDto::Ready,
            None,
            now_ms,
        );
        self.write_state(&metadata, &source.canonical)?;
        Ok(source_status(&metadata))
    }

    /// Link a selected JSON file for future read-only refreshes.  The absolute
    /// source path exists only inside the purpose-bound DPAPI metadata payload.
    pub(crate) fn link_file(
        &self,
        selected_path: &Path,
        now_ms: i64,
    ) -> Result<UsageSourceStatusDto, UsageServiceError> {
        let _guard = self.lock()?;
        let source = read_stable_source(selected_path)?;
        let linked_path = source
            .canonical_path
            .to_str()
            .filter(|value| value.encode_utf16().count() <= MAX_SOURCE_PATH_UTF16_UNITS)
            .ok_or(UsageServiceError::PathNotAllowed)?
            .to_owned();
        let metadata = metadata_for_source(
            &source,
            UsageSourceModeDto::LinkedFile,
            UsageSourceStateDto::Ready,
            Some(linked_path),
            now_ms,
        );
        self.write_state(&metadata, &source.canonical)?;
        Ok(source_status(&metadata))
    }

    /// Refresh a linked source without ever mutating it.  A missing, malformed,
    /// or concurrently changing source only changes the safe source status;
    /// the last valid canonical snapshot remains available.
    pub(crate) fn refresh_linked(
        &self,
        now_ms: i64,
    ) -> Result<UsageSourceStatusDto, UsageServiceError> {
        let _guard = self.lock()?;
        let mut current = self.read_state()?.ok_or(UsageServiceError::NotConfigured)?;
        if current.metadata.mode != UsageSourceModeDto::LinkedFile {
            return Err(UsageServiceError::NotLinked);
        }
        let linked_path = current
            .metadata
            .linked_path
            .as_deref()
            .ok_or(UsageServiceError::CacheCorrupt)?;
        if linked_path.encode_utf16().count() > MAX_SOURCE_PATH_UTF16_UNITS {
            return Err(UsageServiceError::CacheCorrupt);
        }
        let attempt_ms = now_ms
            .max(0)
            .max(current.metadata.last_successful_read_at_ms)
            .max(current.metadata.last_attempt_at_ms);
        match read_stable_source(Path::new(linked_path)) {
            Ok(source) => {
                let metadata = metadata_for_source(
                    &source,
                    UsageSourceModeDto::LinkedFile,
                    UsageSourceStateDto::Ready,
                    Some(linked_path.to_owned()),
                    attempt_ms,
                );
                self.write_state(&metadata, &source.canonical)?;
                Ok(source_status(&metadata))
            }
            Err(error) => {
                current.metadata.state = match error {
                    UsageServiceError::NotFound => UsageSourceStateDto::Missing,
                    UsageServiceError::InvalidJson | UsageServiceError::TooLarge => {
                        UsageSourceStateDto::Invalid
                    }
                    _ => UsageSourceStateDto::Stale,
                };
                current.metadata.last_attempt_at_ms = attempt_ms;
                self.write_state(&current.metadata, &current.canonical)?;
                Ok(source_status(&current.metadata))
            }
        }
    }

    pub(crate) fn snapshot(
        &self,
        query: &UsageQueryDto,
    ) -> Result<Option<UsageSnapshotDto>, UsageServiceError> {
        let _guard = self.lock()?;
        let Some(stored) = self.read_state()? else {
            return Ok(None);
        };
        Ok(Some(aggregate_usage_snapshot(
            &stored.history,
            source_status(&stored.metadata),
            query,
        )?))
    }

    fn lock(&self) -> Result<MutexGuard<'_, ()>, UsageServiceError> {
        self.operation
            .lock()
            .map_err(|_| UsageServiceError::Storage)
    }

    fn state_path(&self) -> Result<PathBuf, UsageServiceError> {
        ensure_private_directory(&self.private_dir)?;
        resolve_path_beneath(&self.private_dir, STATE_FILE_NAME).map_err(UsageServiceError::from)
    }

    fn write_state(
        &self,
        metadata: &StoredSourceMetadata,
        canonical: &[u8],
    ) -> Result<(), UsageServiceError> {
        if canonical.len() > MAX_USAGE_JSON_BYTES {
            return Err(UsageServiceError::TooLarge);
        }
        let metadata_bytes =
            serde_json::to_vec(metadata).map_err(|_| UsageServiceError::CacheCorrupt)?;
        if metadata_bytes.len() > MAX_SOURCE_METADATA_BYTES {
            return Err(UsageServiceError::CacheCorrupt);
        }
        let protected_metadata =
            protect_for_purpose(self.protector.as_ref(), SOURCE_PURPOSE, &metadata_bytes)?;
        let protected_snapshot =
            protect_for_purpose(self.protector.as_ref(), SNAPSHOT_PURPOSE, canonical)?;
        let container = encode_state_container(&protected_metadata, &protected_snapshot)?;
        write_private_atomic(&self.private_dir, STATE_FILE_NAME, &container)
    }

    fn read_state(&self) -> Result<Option<StoredState>, UsageServiceError> {
        let path = self.state_path()?;
        match fs::symlink_metadata(&path) {
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(map_source_io_error(error)),
        }
        let container = read_private_bounded(&path, MAX_STATE_CONTAINER_BYTES)?;
        let (protected_metadata, protected_snapshot) = decode_state_container(&container)?;
        let mut metadata_bytes = unprotect_for_purpose(
            self.protector.as_ref(),
            SOURCE_PURPOSE,
            protected_metadata,
            MAX_SOURCE_METADATA_BYTES,
        )?;
        let mut canonical = unprotect_for_purpose(
            self.protector.as_ref(),
            SNAPSHOT_PURPOSE,
            protected_snapshot,
            MAX_USAGE_JSON_BYTES,
        )?;
        let metadata: StoredSourceMetadata =
            serde_json::from_slice(&metadata_bytes).map_err(|_| UsageServiceError::CacheCorrupt)?;
        metadata_bytes.fill(0);
        validate_stored_metadata(&metadata)?;
        let history = match parse_android_usage_v4(&canonical) {
            Ok(history) => history,
            Err(error) => {
                canonical.fill(0);
                return Err(match error {
                    UsageServiceError::TooLarge => UsageServiceError::CacheCorrupt,
                    _ => UsageServiceError::CacheCorrupt,
                });
            }
        };
        if sha256_hex(&canonical) != metadata.snapshot_sha256 {
            canonical.fill(0);
            return Err(UsageServiceError::CacheCorrupt);
        }
        Ok(Some(StoredState {
            metadata,
            history,
            canonical,
        }))
    }
}

fn metadata_for_source(
    source: &StableSource,
    mode: UsageSourceModeDto,
    state: UsageSourceStateDto,
    linked_path: Option<String>,
    now_ms: i64,
) -> StoredSourceMetadata {
    StoredSourceMetadata {
        record_type: SOURCE_RECORD_TYPE.to_owned(),
        mode,
        state,
        display_name: source.display_name.clone(),
        linked_path,
        last_successful_read_at_ms: now_ms.max(0),
        last_attempt_at_ms: now_ms.max(0),
        source_modified_at_ms: source.modified_at_ms,
        source_size: source.size,
        source_sha256: source.source_sha256.clone(),
        snapshot_sha256: sha256_hex(&source.canonical),
    }
}

fn validate_stored_metadata(metadata: &StoredSourceMetadata) -> Result<(), UsageServiceError> {
    if metadata.record_type != SOURCE_RECORD_TYPE
        || !valid_source_display_name(&metadata.display_name)
        || metadata.last_successful_read_at_ms < 0
        || metadata.last_attempt_at_ms < 0
        || metadata.last_attempt_at_ms < metadata.last_successful_read_at_ms
        || metadata
            .source_modified_at_ms
            .is_some_and(|value| value < 0)
        || metadata.source_size == 0
        || metadata.source_size > MAX_USAGE_JSON_BYTES as u64
        || !is_sha256_hex(&metadata.source_sha256)
        || !is_sha256_hex(&metadata.snapshot_sha256)
    {
        return Err(UsageServiceError::CacheCorrupt);
    }
    match metadata.mode {
        UsageSourceModeDto::Snapshot
            if metadata.linked_path.is_some()
                || metadata.state != UsageSourceStateDto::Ready
                || metadata.last_attempt_at_ms != metadata.last_successful_read_at_ms =>
        {
            Err(UsageServiceError::CacheCorrupt)
        }
        UsageSourceModeDto::LinkedFile => {
            let path = metadata
                .linked_path
                .as_deref()
                .ok_or(UsageServiceError::CacheCorrupt)?;
            if !Path::new(path).is_absolute()
                || path.encode_utf16().count() > MAX_SOURCE_PATH_UTF16_UNITS
                || path.chars().any(char::is_control)
                || (metadata.state == UsageSourceStateDto::Ready
                    && metadata.last_attempt_at_ms != metadata.last_successful_read_at_ms)
            {
                Err(UsageServiceError::CacheCorrupt)
            } else {
                Ok(())
            }
        }
        _ => Ok(()),
    }
}

fn valid_source_display_name(value: &str) -> bool {
    !value.is_empty()
        && value.encode_utf16().count() <= 240
        && !matches!(value, "." | "..")
        && !value
            .chars()
            .any(|character| character.is_control() || matches!(character, '/' | '\\'))
}

fn source_status(metadata: &StoredSourceMetadata) -> UsageSourceStatusDto {
    UsageSourceStatusDto {
        dto_version: USAGE_DTO_VERSION,
        mode: metadata.mode,
        state: metadata.state,
        display_name: metadata.display_name.clone(),
        can_refresh: metadata.mode == UsageSourceModeDto::LinkedFile,
        last_successful_read_at_ms: metadata.last_successful_read_at_ms.to_string(),
        last_attempt_at_ms: metadata.last_attempt_at_ms.to_string(),
        source_modified_at_ms: metadata
            .source_modified_at_ms
            .map(|value| value.to_string()),
    }
}

fn read_stable_source(path: &Path) -> Result<StableSource, UsageServiceError> {
    let path_text = path.to_str().ok_or(UsageServiceError::PathNotAllowed)?;
    if !path.is_absolute()
        || path_text.encode_utf16().count() > MAX_SOURCE_PATH_UTF16_UNITS
        || path_text.chars().any(char::is_control)
    {
        return Err(UsageServiceError::PathNotAllowed);
    }
    let extension_is_json = path
        .extension()
        .and_then(|value| value.to_str())
        .is_some_and(|value| value.eq_ignore_ascii_case("json"));
    if !extension_is_json {
        return Err(UsageServiceError::PathNotAllowed);
    }
    reject_reparse_chain(path)?;
    let canonical_path = fs::canonicalize(path).map_err(map_source_io_error)?;
    reject_reparse_chain(&canonical_path)?;
    let mut file =
        open_regular_file_no_reparse(&canonical_path).map_err(UsageServiceError::from)?;
    reject_reparse_chain(&canonical_path)?;
    let before = file.metadata().map_err(map_source_io_error)?;
    if !before.is_file() {
        return Err(UsageServiceError::PathNotAllowed);
    }
    if before.len() > MAX_USAGE_JSON_BYTES as u64 {
        return Err(UsageServiceError::TooLarge);
    }
    let first = read_open_file_bounded(&mut file, MAX_USAGE_JSON_BYTES)?;
    file.seek(SeekFrom::Start(0)).map_err(map_source_io_error)?;
    let second = read_open_file_bounded(&mut file, MAX_USAGE_JSON_BYTES)?;
    let after = file.metadata().map_err(map_source_io_error)?;
    if first != second
        || before.len() != after.len()
        || before.modified().ok() != after.modified().ok()
    {
        return Err(UsageServiceError::SourceChanged);
    }
    reject_reparse_chain(&canonical_path)?;
    if fs::canonicalize(path).map_err(map_source_io_error)? != canonical_path {
        return Err(UsageServiceError::SourceChanged);
    }
    let mut current_file =
        open_regular_file_no_reparse(&canonical_path).map_err(UsageServiceError::from)?;
    let current = read_open_file_bounded(&mut current_file, MAX_USAGE_JSON_BYTES)?;
    let current_metadata = current_file.metadata().map_err(map_source_io_error)?;
    if current != first
        || current_metadata.len() != after.len()
        || current_metadata.modified().ok() != after.modified().ok()
    {
        return Err(UsageServiceError::SourceChanged);
    }
    reject_reparse_chain(&canonical_path)?;
    if fs::canonicalize(path).map_err(map_source_io_error)? != canonical_path {
        return Err(UsageServiceError::SourceChanged);
    }
    let history = parse_android_usage_v4(&first)?;
    let canonical = canonical_android_usage_v4(&history)?;
    let display_name = canonical_path
        .file_name()
        .and_then(|value| value.to_str())
        .filter(|value| valid_source_display_name(value))
        .ok_or(UsageServiceError::PathNotAllowed)?
        .to_owned();
    Ok(StableSource {
        canonical_path,
        display_name,
        modified_at_ms: modified_millis(&after),
        size: after.len(),
        source_sha256: sha256_hex(&first),
        canonical,
    })
}

fn read_open_file_bounded(
    file: &mut fs::File,
    maximum: usize,
) -> Result<Vec<u8>, UsageServiceError> {
    let mut bytes = Vec::new();
    (&mut *file)
        .take(maximum.saturating_add(1) as u64)
        .read_to_end(&mut bytes)
        .map_err(map_source_io_error)?;
    if bytes.len() > maximum {
        bytes.fill(0);
        return Err(UsageServiceError::TooLarge);
    }
    Ok(bytes)
}

fn reject_reparse_chain(path: &Path) -> Result<(), UsageServiceError> {
    for ancestor in path.ancestors() {
        match fs::symlink_metadata(ancestor) {
            Ok(_) => reject_reparse_point(ancestor).map_err(UsageServiceError::from)?,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Err(UsageServiceError::NotFound);
            }
            Err(error) => return Err(map_source_io_error(error)),
        }
    }
    Ok(())
}

fn reject_existing_reparse_ancestors(path: &Path) -> Result<(), UsageServiceError> {
    for ancestor in path.ancestors() {
        match fs::symlink_metadata(ancestor) {
            Ok(_) => reject_reparse_point(ancestor).map_err(UsageServiceError::from)?,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(map_source_io_error(error)),
        }
    }
    Ok(())
}

fn map_source_io_error(error: std::io::Error) -> UsageServiceError {
    match error.kind() {
        std::io::ErrorKind::NotFound => UsageServiceError::NotFound,
        std::io::ErrorKind::InvalidInput | std::io::ErrorKind::InvalidData => {
            UsageServiceError::PathNotAllowed
        }
        _ => UsageServiceError::Storage,
    }
}

fn modified_millis(metadata: &fs::Metadata) -> Option<i64> {
    metadata
        .modified()
        .ok()?
        .duration_since(UNIX_EPOCH)
        .ok()
        .map(|duration| i64::try_from(duration.as_millis()).unwrap_or(i64::MAX))
}

fn ensure_private_directory(path: &Path) -> Result<(), UsageServiceError> {
    match fs::symlink_metadata(path) {
        Ok(_) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            reject_existing_reparse_ancestors(path)?;
            fs::create_dir_all(path).map_err(map_source_io_error)?;
        }
        Err(error) => return Err(map_source_io_error(error)),
    }
    reject_reparse_chain(path)?;
    let metadata = fs::symlink_metadata(path).map_err(map_source_io_error)?;
    if !metadata.is_dir() {
        return Err(UsageServiceError::PathNotAllowed);
    }
    Ok(())
}

fn read_private_bounded(path: &Path, maximum: usize) -> Result<Vec<u8>, UsageServiceError> {
    reject_reparse_chain(path)?;
    let file = open_regular_file_no_reparse(path).map_err(UsageServiceError::from)?;
    reject_reparse_chain(path)?;
    let metadata = file.metadata().map_err(map_source_io_error)?;
    if metadata.len() > maximum as u64 {
        return Err(UsageServiceError::CacheCorrupt);
    }
    let mut bytes = Vec::with_capacity((metadata.len() as usize).min(maximum));
    file.take(maximum.saturating_add(1) as u64)
        .read_to_end(&mut bytes)
        .map_err(map_source_io_error)?;
    if bytes.len() > maximum {
        bytes.fill(0);
        return Err(UsageServiceError::CacheCorrupt);
    }
    Ok(bytes)
}

fn write_private_atomic(
    private_dir: &Path,
    leaf: &str,
    bytes: &[u8],
) -> Result<(), UsageServiceError> {
    ensure_private_directory(private_dir)?;
    let target = resolve_path_beneath(private_dir, leaf).map_err(UsageServiceError::from)?;
    let pending_leaf = format!(".{leaf}.{}.pending", Uuid::new_v4().simple());
    let previous_leaf = format!(".{leaf}.{}.previous", Uuid::new_v4().simple());
    let pending =
        resolve_path_beneath(private_dir, &pending_leaf).map_err(UsageServiceError::from)?;
    let previous =
        resolve_path_beneath(private_dir, &previous_leaf).map_err(UsageServiceError::from)?;
    reject_reparse_point(&target).map_err(UsageServiceError::from)?;
    let mut pending_file = fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&pending)
        .map_err(map_source_io_error)?;
    if let Err(error) = pending_file
        .write_all(bytes)
        .and_then(|_| pending_file.sync_all())
    {
        drop(pending_file);
        let _ = fs::remove_file(&pending);
        return Err(map_source_io_error(error));
    }
    drop(pending_file);
    let verification = read_private_bounded(&pending, bytes.len().max(1))?;
    if sha256_hex(&verification) != sha256_hex(bytes) {
        let _ = fs::remove_file(&pending);
        return Err(UsageServiceError::Storage);
    }

    let had_previous = if target.exists() {
        reject_reparse_point(&target).map_err(UsageServiceError::from)?;
        fs::rename(&target, &previous).map_err(map_source_io_error)?;
        true
    } else {
        false
    };
    if let Err(error) = fs::rename(&pending, &target) {
        if had_previous {
            let _ = fs::rename(&previous, &target);
        }
        return Err(map_source_io_error(error));
    }
    let committed = read_private_bounded(&target, bytes.len().max(1))?;
    if sha256_hex(&committed) != sha256_hex(bytes) {
        let _ = fs::remove_file(&target);
        if had_previous {
            let _ = fs::rename(&previous, &target);
        }
        return Err(UsageServiceError::Storage);
    }
    if had_previous {
        let _ = fs::remove_file(previous);
    }
    Ok(())
}

fn encode_state_container(metadata: &[u8], snapshot: &[u8]) -> Result<Vec<u8>, UsageServiceError> {
    if metadata.len() > MAX_PROTECTED_SOURCE_BYTES || snapshot.len() > MAX_PROTECTED_SNAPSHOT_BYTES
    {
        return Err(UsageServiceError::CacheCorrupt);
    }
    let metadata_len =
        u32::try_from(metadata.len()).map_err(|_| UsageServiceError::CacheCorrupt)?;
    let snapshot_len =
        u32::try_from(snapshot.len()).map_err(|_| UsageServiceError::CacheCorrupt)?;
    let mut output = Vec::with_capacity(16 + metadata.len() + snapshot.len());
    output.extend_from_slice(STATE_CONTAINER_MAGIC);
    output.extend_from_slice(&metadata_len.to_le_bytes());
    output.extend_from_slice(&snapshot_len.to_le_bytes());
    output.extend_from_slice(metadata);
    output.extend_from_slice(snapshot);
    if output.len() > MAX_STATE_CONTAINER_BYTES {
        return Err(UsageServiceError::CacheCorrupt);
    }
    Ok(output)
}

fn decode_state_container(bytes: &[u8]) -> Result<(&[u8], &[u8]), UsageServiceError> {
    if bytes.len() < 16 || &bytes[..8] != STATE_CONTAINER_MAGIC {
        return Err(UsageServiceError::CacheCorrupt);
    }
    let metadata_len = read_u32(&bytes[8..12])? as usize;
    let snapshot_len = read_u32(&bytes[12..16])? as usize;
    if metadata_len > MAX_PROTECTED_SOURCE_BYTES
        || snapshot_len > MAX_PROTECTED_SNAPSHOT_BYTES
        || 16_usize
            .checked_add(metadata_len)
            .and_then(|length| length.checked_add(snapshot_len))
            != Some(bytes.len())
    {
        return Err(UsageServiceError::CacheCorrupt);
    }
    let metadata_end = 16 + metadata_len;
    Ok((&bytes[16..metadata_end], &bytes[metadata_end..]))
}

/// Protect feature data with a purpose-specific DPAPI entropy value.  The
/// security module authenticates the purpose and accepts a full 10 MiB
/// plaintext, so a maximum-sized Android payload does not need truncation.
fn protect_for_purpose(
    protector: &dyn RawProtector,
    purpose: &str,
    plaintext: &[u8],
) -> Result<Vec<u8>, UsageServiceError> {
    validate_purpose(purpose)?;
    let maximum = match purpose {
        SNAPSHOT_PURPOSE => MAX_USAGE_JSON_BYTES,
        SOURCE_PURPOSE => MAX_SOURCE_METADATA_BYTES,
        _ => return Err(UsageServiceError::Crypto),
    };
    protector.protect(plaintext, purpose.as_bytes(), maximum)
}

fn unprotect_for_purpose(
    protector: &dyn RawProtector,
    purpose: &str,
    protected: &[u8],
    maximum_plaintext: usize,
) -> Result<Vec<u8>, UsageServiceError> {
    validate_purpose(purpose)?;
    protector.unprotect(protected, purpose.as_bytes(), maximum_plaintext)
}

fn validate_purpose(purpose: &str) -> Result<(), UsageServiceError> {
    if purpose.is_empty()
        || purpose.len() > 96
        || !purpose
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-' | b'_'))
    {
        return Err(UsageServiceError::Crypto);
    }
    Ok(())
}

fn read_u32(bytes: &[u8]) -> Result<u32, UsageServiceError> {
    let array: [u8; 4] = bytes
        .try_into()
        .map_err(|_| UsageServiceError::CacheCorrupt)?;
    Ok(u32::from_le_bytes(array))
}

fn sha256_hex(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

fn is_sha256_hex(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    struct TestRawProtector;

    impl RawProtector for TestRawProtector {
        fn protect(
            &self,
            plaintext: &[u8],
            purpose: &[u8],
            maximum_plaintext: usize,
        ) -> Result<Vec<u8>, UsageServiceError> {
            if plaintext.len() > maximum_plaintext {
                return Err(UsageServiceError::TooLarge);
            }
            let purpose_length =
                u8::try_from(purpose.len()).map_err(|_| UsageServiceError::Crypto)?;
            let mut protected = Vec::with_capacity(1 + purpose.len() + plaintext.len());
            protected.push(purpose_length);
            protected.extend_from_slice(purpose);
            protected.extend(plaintext.iter().map(|byte| byte ^ 0xa5));
            Ok(protected)
        }

        fn unprotect(
            &self,
            ciphertext: &[u8],
            purpose: &[u8],
            maximum_plaintext: usize,
        ) -> Result<Vec<u8>, UsageServiceError> {
            let purpose_length = ciphertext
                .first()
                .copied()
                .ok_or(UsageServiceError::Crypto)? as usize;
            if ciphertext.get(1..1 + purpose_length) != Some(purpose) {
                return Err(UsageServiceError::Crypto);
            }
            let plaintext = ciphertext[1 + purpose_length..]
                .iter()
                .map(|byte| byte ^ 0xa5)
                .collect::<Vec<_>>();
            if plaintext.len() > maximum_plaintext {
                return Err(UsageServiceError::TooLarge);
            }
            Ok(plaintext)
        }
    }

    fn valid_json() -> Vec<u8> {
        br#"{
          "schemaVersion":4,
          "trackingStartedOn":"2026-07-27",
          "backfillCompletedThrough":"2026-07-28",
          "days":[
            {
              "date":"2026-07-27",
              "zoneId":"Asia/Shanghai",
              "state":"FINAL",
              "collectedAtEpochMillis":42,
              "apps":[
                {"packageName":"com.tencent.mm","foregroundMillis":8400000},
                {"packageName":"com.example.music","foregroundMillis":60000}
              ]
            },
            {
              "date":"2026-07-29",
              "zoneId":"GMT+08:00",
              "state":"OPEN",
              "collectedAtEpochMillis":9223372036854775807,
              "apps":[{"packageName":"com.example.music","foregroundMillis":120000}]
            }
          ]
        }"#
        .to_vec()
    }

    fn service(path: &Path) -> UsageStatisticsService {
        UsageStatisticsService::with_protector(path.to_owned(), Arc::new(TestRawProtector))
            .expect("create service")
    }

    #[test]
    fn v4_round_trip_is_canonical_and_lossless() {
        let history = parse_android_usage_v4(&valid_json()).expect("parse");
        let canonical = canonical_android_usage_v4(&history).expect("encode");
        let decoded = parse_android_usage_v4(&canonical).expect("decode canonical");
        assert_eq!(decoded, history);
        let text = String::from_utf8(canonical).expect("utf8");
        assert!(text.starts_with(r#"{"schemaVersion":4,"trackingStartedOn":"2026-07-27""#));
        assert_eq!(decoded.days[0].apps[0].package_name, "com.example.music");
    }

    #[test]
    fn parser_rejects_wrong_version_unknown_and_missing_fields() {
        let value: Value = serde_json::from_slice(&valid_json()).expect("json");
        for mutated in [
            {
                let mut value = value.clone();
                value["schemaVersion"] = Value::from(3);
                value
            },
            {
                let mut value = value.clone();
                value
                    .as_object_mut()
                    .expect("root")
                    .insert("unknown".to_owned(), Value::Bool(true));
                value
            },
            {
                let mut value = value.clone();
                value
                    .as_object_mut()
                    .expect("root")
                    .remove("backfillCompletedThrough");
                value
            },
        ] {
            assert_eq!(
                parse_android_usage_v4(&serde_json::to_vec(&mutated).expect("encode")),
                Err(UsageServiceError::InvalidJson)
            );
        }
    }

    #[test]
    fn parser_rejects_duplicate_object_members_at_every_schema_level() {
        let text = String::from_utf8(valid_json()).expect("utf8");
        let duplicated_root = text.replacen(
            "\"schemaVersion\":4,",
            "\"schemaVersion\":4,\"schemaVersion\":4,",
            1,
        );
        let duplicated_day = text.replacen(
            "\"date\":\"2026-07-27\",",
            "\"date\":\"2026-07-27\",\"date\":\"2026-07-27\",",
            1,
        );
        let duplicated_app = text.replacen(
            "\"packageName\":\"com.tencent.mm\",",
            "\"packageName\":\"com.tencent.mm\",\"packageName\":\"com.tencent.mm\",",
            1,
        );
        for duplicated in [duplicated_root, duplicated_day, duplicated_app] {
            assert_eq!(
                parse_android_usage_v4(duplicated.as_bytes()),
                Err(UsageServiceError::InvalidJson)
            );
        }
    }

    #[test]
    fn parser_rejects_private_number_marker_object_spoofing() {
        let spoofed = String::from_utf8(valid_json()).expect("utf8").replacen(
            "\"foregroundMillis\":8400000",
            "\"foregroundMillis\":{\"$serde_json::private::Number\":\"8400000\"}",
            1,
        );
        assert_eq!(
            parse_android_usage_v4(spoofed.as_bytes()),
            Err(UsageServiceError::InvalidJson)
        );
    }

    #[test]
    fn parser_rejects_fractional_negative_overflow_and_duration_over_26_hours() {
        let base: Value = serde_json::from_slice(&valid_json()).expect("json");
        let mut mutations = Vec::new();
        for value in [
            serde_json::json!(1.5),
            serde_json::json!(-1),
            serde_json::json!(93_600_001_i64),
        ] {
            let mut mutated = base.clone();
            mutated["days"][0]["apps"][0]["foregroundMillis"] = value;
            mutations.push(mutated);
        }
        let mut overflowing = String::from_utf8(valid_json()).expect("utf8");
        overflowing = overflowing.replace(
            "\"collectedAtEpochMillis\":42",
            "\"collectedAtEpochMillis\":9223372036854775808",
        );
        assert_eq!(
            parse_android_usage_v4(overflowing.as_bytes()),
            Err(UsageServiceError::InvalidJson)
        );
        for mutation in mutations {
            assert_eq!(
                parse_android_usage_v4(&serde_json::to_vec(&mutation).expect("encode")),
                Err(UsageServiceError::InvalidJson)
            );
        }
    }

    #[test]
    fn parser_enforces_dates_packages_zones_and_utf16_limits() {
        let base: Value = serde_json::from_slice(&valid_json()).expect("json");
        let mut duplicate_date = base.clone();
        duplicate_date["days"][1]["date"] = Value::String("2026-07-27".to_owned());
        let mut duplicate_package = base.clone();
        duplicate_package["days"][0]["apps"][1]["packageName"] =
            Value::String("com.tencent.mm".to_owned());
        let mut before_start = base.clone();
        before_start["days"][0]["date"] = Value::String("2026-07-26".to_owned());
        let mut invalid_zone = base.clone();
        invalid_zone["days"][0]["zoneId"] = Value::String("No/Such_Zone".to_owned());
        let mut long_package = base.clone();
        long_package["days"][0]["apps"][0]["packageName"] =
            Value::String("x".repeat(MAX_PACKAGE_NAME_UTF16_UNITS + 1));
        for mutation in [
            duplicate_date,
            duplicate_package,
            before_start,
            invalid_zone,
            long_package,
        ] {
            assert_eq!(
                parse_android_usage_v4(&serde_json::to_vec(&mutation).expect("encode")),
                Err(UsageServiceError::InvalidJson)
            );
        }

        let mut exact_utf16 = base.clone();
        exact_utf16["days"][0]["apps"][0]["packageName"] =
            Value::String(format!("{}😀", "x".repeat(253)));
        assert!(
            parse_android_usage_v4(&serde_json::to_vec(&exact_utf16).expect("encode exact UTF-16"))
                .is_ok()
        );
        exact_utf16["days"][0]["apps"][0]["packageName"] =
            Value::String(format!("{}😀", "x".repeat(254)));
        assert_eq!(
            parse_android_usage_v4(&serde_json::to_vec(&exact_utf16).expect("encode long UTF-16")),
            Err(UsageServiceError::InvalidJson)
        );
    }

    #[test]
    fn parser_enforces_count_and_byte_limits() {
        let too_large = vec![b' '; MAX_USAGE_JSON_BYTES + 1];
        assert_eq!(
            parse_android_usage_v4(&too_large),
            Err(UsageServiceError::TooLarge)
        );

        let mut history = parse_android_usage_v4(&valid_json()).expect("parse");
        let app = history.days[0].apps[0].clone();
        history.days[0].apps = (0..=MAX_APPS_PER_DAY)
            .map(|index| UsageAppDuration {
                package_name: format!("example.app{index}"),
                foreground_millis: app.foreground_millis,
            })
            .collect();
        assert_eq!(
            canonical_android_usage_v4(&history),
            Err(UsageServiceError::InvalidJson)
        );

        history.days = (0..=MAX_USAGE_DAYS)
            .map(|index| UsageDay {
                date: format!("invalid-{index}"),
                zone_id: "UTC".to_owned(),
                state: UsageDayState::Final,
                collected_at_epoch_millis: 0,
                apps: Vec::new(),
            })
            .collect();
        assert_eq!(
            canonical_android_usage_v4(&history),
            Err(UsageServiceError::InvalidJson)
        );

        let minimal_day = serde_json::json!({
            "date": "2026-07-27",
            "zoneId": "UTC",
            "state": "FINAL",
            "collectedAtEpochMillis": 1,
            "apps": []
        });
        let too_many_days = serde_json::json!({
            "schemaVersion": 4,
            "trackingStartedOn": "2026-07-27",
            "backfillCompletedThrough": null,
            "days": vec![minimal_day; MAX_USAGE_DAYS + 1]
        });
        assert_eq!(
            parse_android_usage_v4(
                &serde_json::to_vec(&too_many_days).expect("encode too many days")
            ),
            Err(UsageServiceError::InvalidJson)
        );

        let app = serde_json::json!({
            "packageName": "com.example.app",
            "foregroundMillis": 1
        });
        let too_many_apps = serde_json::json!({
            "schemaVersion": 4,
            "trackingStartedOn": "2026-07-27",
            "backfillCompletedThrough": null,
            "days": [{
                "date": "2026-07-27",
                "zoneId": "UTC",
                "state": "FINAL",
                "collectedAtEpochMillis": 1,
                "apps": vec![app; MAX_APPS_PER_DAY + 1]
            }]
        });
        assert_eq!(
            parse_android_usage_v4(
                &serde_json::to_vec(&too_many_apps).expect("encode too many apps")
            ),
            Err(UsageServiceError::InvalidJson)
        );
    }

    #[test]
    fn usage_query_requires_and_validates_dto_version_one() {
        assert!(
            serde_json::from_value::<UsageQueryDto>(serde_json::json!({
                "range": "LAST_30_DAYS",
                "packageName": null
            }))
            .is_err()
        );
        assert!(
            serde_json::from_value::<UsageQueryDto>(serde_json::json!({
                "dtoVersion": 1,
                "unexpected": true
            }))
            .is_err()
        );
        assert!(
            serde_json::from_value::<UsageQueryDto>(serde_json::json!({
                "dtoVersion": 2
            }))
            .is_err()
        );
        let defaulted = serde_json::from_value::<UsageQueryDto>(serde_json::json!({
            "dtoVersion": 1
        }))
        .expect("versioned query");
        assert_eq!(defaulted, UsageQueryDto::default());

        let history = parse_android_usage_v4(&valid_json()).expect("parse");
        let wrong_version = UsageQueryDto {
            dto_version: 2,
            ..UsageQueryDto::default()
        };
        assert_eq!(
            aggregate_usage_snapshot(
                &history,
                UsageSourceStatusDto {
                    dto_version: USAGE_DTO_VERSION,
                    mode: UsageSourceModeDto::Snapshot,
                    state: UsageSourceStateDto::Ready,
                    display_name: "usage.json".to_owned(),
                    can_refresh: false,
                    last_successful_read_at_ms: "0".to_owned(),
                    last_attempt_at_ms: "0".to_owned(),
                    source_modified_at_ms: None,
                },
                &wrong_version,
            ),
            Err(UsageServiceError::InvalidJson)
        );
    }

    #[test]
    fn aggregate_anchors_ranges_to_latest_phone_date_and_uses_decimal_strings() {
        let history = parse_android_usage_v4(&valid_json()).expect("parse");
        let snapshot = aggregate_usage_snapshot(
            &history,
            UsageSourceStatusDto {
                dto_version: USAGE_DTO_VERSION,
                mode: UsageSourceModeDto::Snapshot,
                state: UsageSourceStateDto::Ready,
                display_name: "usage.json".to_owned(),
                can_refresh: false,
                last_successful_read_at_ms: i64::MAX.to_string(),
                last_attempt_at_ms: i64::MAX.to_string(),
                source_modified_at_ms: None,
            },
            &UsageQueryDto {
                dto_version: USAGE_DTO_VERSION,
                range: UsageRangeDto::Last7Days,
                package_name: None,
            },
        )
        .expect("aggregate");
        assert_eq!(snapshot.anchor_date.as_deref(), Some("2026-07-29"));
        assert_eq!(snapshot.overview.total_millis, "8580000");
        assert_eq!(snapshot.overview.highest_day_millis, "8460000");
        assert_eq!(snapshot.overview.average_millis, "4290000");
        assert_eq!(
            snapshot.points[1].collected_at_epoch_millis,
            i64::MAX.to_string()
        );
    }

    #[test]
    fn scoped_protection_rejects_cross_type_decryption_and_accepts_large_payloads() {
        let protector = TestRawProtector;
        let payload = vec![0x5a; 1024 * 1024 + 17];
        let protected =
            protect_for_purpose(&protector, SNAPSHOT_PURPOSE, &payload).expect("protect");
        assert_eq!(
            unprotect_for_purpose(&protector, SNAPSHOT_PURPOSE, &protected, payload.len())
                .expect("unprotect"),
            payload
        );
        assert_eq!(
            unprotect_for_purpose(&protector, SOURCE_PURPOSE, &protected, payload.len()),
            Err(UsageServiceError::Crypto)
        );
    }

    #[test]
    fn snapshot_import_does_not_modify_source_and_hides_absolute_path() {
        let directory = tempdir().expect("temp");
        let private = directory.path().join("private");
        let source = directory.path().join("phone-usage.json");
        fs::write(&source, valid_json()).expect("write source");
        let before = fs::read(&source).expect("read source");
        let service = service(&private);

        let status = service.import_snapshot(&source, 123).expect("import");
        assert_eq!(status.mode, UsageSourceModeDto::Snapshot);
        assert!(!status.can_refresh);
        assert_eq!(status.display_name, "phone-usage.json");
        assert_eq!(fs::read(&source).expect("read source"), before);

        let serialized = serde_json::to_string(&status).expect("serialize status");
        assert!(!serialized.contains(directory.path().to_string_lossy().as_ref()));
        let snapshot = service
            .snapshot(&UsageQueryDto::default())
            .expect("snapshot")
            .expect("configured");
        assert_eq!(snapshot.points.len(), 2);
    }

    #[test]
    fn linked_source_is_read_only_and_private_cache_hides_path_and_payload() {
        let directory = tempdir().expect("temp");
        let private = directory.path().join("private");
        let source = directory.path().join("linked-read-only.json");
        fs::write(&source, valid_json()).expect("write source");
        let before = fs::read(&source).expect("read source");
        let before_modified = fs::metadata(&source)
            .expect("source metadata")
            .modified()
            .ok();
        let original_permissions = fs::metadata(&source)
            .expect("source metadata")
            .permissions();
        let mut read_only = original_permissions.clone();
        read_only.set_readonly(true);
        fs::set_permissions(&source, read_only).expect("make source read-only");

        let service = service(&private);
        let status = service.link_file(&source, 100).expect("link read-only");
        assert_eq!(status.mode, UsageSourceModeDto::LinkedFile);
        assert!(status.can_refresh);
        service.refresh_linked(200).expect("refresh read-only");
        assert_eq!(fs::read(&source).expect("read source"), before);
        assert_eq!(
            fs::metadata(&source)
                .expect("source metadata")
                .modified()
                .ok(),
            before_modified
        );

        let container = fs::read(private.join(STATE_FILE_NAME)).expect("read private cache");
        let container_text = String::from_utf8_lossy(&container);
        assert!(!container_text.contains(source.to_string_lossy().as_ref()));
        assert!(!container_text.contains("com.tencent.mm"));
        assert!(!container_text.contains("\"schemaVersion\":4"));
        let (protected_metadata, protected_snapshot) =
            decode_state_container(&container).expect("decode container");
        assert_eq!(
            unprotect_for_purpose(
                &TestRawProtector,
                SNAPSHOT_PURPOSE,
                protected_metadata,
                MAX_USAGE_JSON_BYTES,
            ),
            Err(UsageServiceError::Crypto)
        );
        assert_eq!(
            unprotect_for_purpose(
                &TestRawProtector,
                SOURCE_PURPOSE,
                protected_snapshot,
                MAX_SOURCE_METADATA_BYTES,
            ),
            Err(UsageServiceError::Crypto)
        );

        fs::set_permissions(&source, original_permissions).expect("restore source permissions");
    }

    #[test]
    fn linked_invalid_refresh_keeps_last_valid_snapshot() {
        let directory = tempdir().expect("temp");
        let private = directory.path().join("private");
        let source = directory.path().join("linked.json");
        fs::write(&source, valid_json()).expect("write source");
        let service = service(&private);
        service.link_file(&source, 100).expect("link");
        let before = service
            .snapshot(&UsageQueryDto::default())
            .expect("snapshot")
            .expect("configured");

        fs::write(&source, b"{invalid").expect("replace source");
        let status = service.refresh_linked(200).expect("safe refresh");
        assert_eq!(status.state, UsageSourceStateDto::Invalid);
        assert_eq!(status.last_successful_read_at_ms, "100");
        assert_eq!(status.last_attempt_at_ms, "200");
        let after = service
            .snapshot(&UsageQueryDto::default())
            .expect("snapshot")
            .expect("configured");
        assert_eq!(after.points, before.points);
        assert_eq!(after.source.state, UsageSourceStateDto::Invalid);
    }

    #[test]
    fn linked_missing_refresh_keeps_last_valid_snapshot() {
        let directory = tempdir().expect("temp");
        let private = directory.path().join("private");
        let source = directory.path().join("linked.json");
        fs::write(&source, valid_json()).expect("write source");
        let service = service(&private);
        service.link_file(&source, 100).expect("link");
        fs::remove_file(&source).expect("remove selected test source");

        let status = service.refresh_linked(200).expect("safe refresh");
        assert_eq!(status.state, UsageSourceStateDto::Missing);
        assert_eq!(
            service
                .snapshot(&UsageQueryDto::default())
                .expect("snapshot")
                .expect("configured")
                .points
                .len(),
            2
        );
    }

    #[test]
    fn refresh_clock_rollback_does_not_move_status_timestamps_backwards() {
        let directory = tempdir().expect("temp");
        let private = directory.path().join("private");
        let source = directory.path().join("linked.json");
        fs::write(&source, valid_json()).expect("write source");
        let service = service(&private);
        service.link_file(&source, 200).expect("link");
        fs::write(&source, b"{invalid").expect("replace source");

        let status = service.refresh_linked(100).expect("safe refresh");
        assert_eq!(status.state, UsageSourceStateDto::Invalid);
        assert_eq!(status.last_successful_read_at_ms, "200");
        assert_eq!(status.last_attempt_at_ms, "200");
        assert_eq!(
            service
                .snapshot(&UsageQueryDto::default())
                .expect("snapshot")
                .expect("configured")
                .points
                .len(),
            2
        );
    }

    #[test]
    fn source_errors_and_status_never_expose_an_absolute_path() {
        let directory = tempdir().expect("temp");
        let private = directory.path().join("private");
        let missing = directory
            .path()
            .join("private-name-that-must-not-leak.json");
        let error = service(&private)
            .link_file(&missing, 100)
            .expect_err("missing source");
        let rendered = error.to_string();
        assert_eq!(error.code(), "usage_statistics_source_missing");
        assert!(!rendered.contains(directory.path().to_string_lossy().as_ref()));
        assert!(!rendered.contains("private-name-that-must-not-leak"));
    }

    #[cfg(unix)]
    #[test]
    fn linked_source_symlink_is_rejected() {
        use std::os::unix::fs::symlink;
        let directory = tempdir().expect("temp");
        let private = directory.path().join("private");
        let target = directory.path().join("target.json");
        let link = directory.path().join("link.json");
        fs::write(&target, valid_json()).expect("write target");
        symlink(&target, &link).expect("create symlink");
        assert_eq!(
            service(&private).link_file(&link, 100),
            Err(UsageServiceError::PathNotAllowed)
        );
    }

    #[cfg(unix)]
    #[test]
    fn linked_refresh_rejects_replaced_symlink_and_keeps_snapshot() {
        use std::os::unix::fs::symlink;
        let directory = tempdir().expect("temp");
        let private = directory.path().join("private");
        let source = directory.path().join("linked.json");
        let replacement = directory.path().join("replacement.json");
        fs::write(&source, valid_json()).expect("write source");
        fs::write(&replacement, valid_json()).expect("write replacement");
        let service = service(&private);
        service.link_file(&source, 100).expect("link");
        let before = service
            .snapshot(&UsageQueryDto::default())
            .expect("snapshot")
            .expect("configured");
        fs::remove_file(&source).expect("remove source");
        symlink(&replacement, &source).expect("replace with symlink");

        let status = service.refresh_linked(200).expect("safe refresh");
        assert_eq!(status.state, UsageSourceStateDto::Stale);
        let after = service
            .snapshot(&UsageQueryDto::default())
            .expect("snapshot")
            .expect("configured");
        assert_eq!(after.points, before.points);
    }

    #[cfg(unix)]
    #[test]
    fn dangling_private_cache_symlink_is_rejected_instead_of_looking_unconfigured() {
        use std::os::unix::fs::symlink;
        let directory = tempdir().expect("temp");
        let private = directory.path().join("private");
        let source = directory.path().join("source.json");
        fs::write(&source, valid_json()).expect("write source");
        let service = service(&private);
        service.import_snapshot(&source, 100).expect("import");
        let state_path = private.join(STATE_FILE_NAME);
        fs::remove_file(&state_path).expect("remove state");
        symlink(directory.path().join("missing"), &state_path).expect("create dangling symlink");
        assert_eq!(
            service.snapshot(&UsageQueryDto::default()),
            Err(UsageServiceError::PathNotAllowed)
        );
    }

    #[cfg(unix)]
    #[test]
    fn private_directory_symlink_ancestor_is_rejected_before_any_cache_write() {
        use std::os::unix::fs::symlink;
        let directory = tempdir().expect("temp");
        let outside = directory.path().join("outside");
        let linked_parent = directory.path().join("linked-parent");
        fs::create_dir(&outside).expect("create outside");
        symlink(&outside, &linked_parent).expect("create directory symlink");
        let private = linked_parent.join("private");
        assert!(matches!(
            UsageStatisticsService::with_protector(private, Arc::new(TestRawProtector),),
            Err(UsageServiceError::PathNotAllowed)
        ));
        assert!(!outside.join("private").exists());
    }

    #[cfg(windows)]
    #[test]
    fn linked_source_reparse_point_is_rejected_when_symlink_creation_is_available() {
        use std::os::windows::fs::symlink_file;
        let directory = tempdir().expect("temp");
        let private = directory.path().join("private");
        let target = directory.path().join("target.json");
        let link = directory.path().join("link.json");
        fs::write(&target, valid_json()).expect("write target");
        if symlink_file(&target, &link).is_err() {
            // Windows installations without Developer Mode or symlink
            // privilege cannot construct this fixture. Production rejection is
            // based on FILE_ATTRIBUTE_REPARSE_POINT and is shared with
            // junctions and mount points.
            return;
        }
        assert_eq!(
            service(&private).link_file(&link, 100),
            Err(UsageServiceError::PathNotAllowed)
        );
    }

    #[cfg(windows)]
    #[test]
    fn linked_refresh_rejects_replaced_reparse_point_and_keeps_snapshot() {
        use std::os::windows::fs::symlink_file;
        let directory = tempdir().expect("temp");
        let private = directory.path().join("private");
        let source = directory.path().join("linked.json");
        let replacement = directory.path().join("replacement.json");
        fs::write(&source, valid_json()).expect("write source");
        fs::write(&replacement, valid_json()).expect("write replacement");
        let service = service(&private);
        service.link_file(&source, 100).expect("link");
        let before = service
            .snapshot(&UsageQueryDto::default())
            .expect("snapshot")
            .expect("configured");
        fs::remove_file(&source).expect("remove source");
        if symlink_file(&replacement, &source).is_err() {
            return;
        }

        let status = service.refresh_linked(200).expect("safe refresh");
        assert_eq!(status.state, UsageSourceStateDto::Stale);
        let after = service
            .snapshot(&UsageQueryDto::default())
            .expect("snapshot")
            .expect("configured");
        assert_eq!(after.points, before.points);
    }

    #[cfg(windows)]
    #[test]
    fn dangling_private_cache_reparse_point_is_rejected() {
        use std::os::windows::fs::symlink_file;
        let directory = tempdir().expect("temp");
        let private = directory.path().join("private");
        let source = directory.path().join("source.json");
        fs::write(&source, valid_json()).expect("write source");
        let service = service(&private);
        service.import_snapshot(&source, 100).expect("import");
        let state_path = private.join(STATE_FILE_NAME);
        fs::remove_file(&state_path).expect("remove state");
        if symlink_file(directory.path().join("missing"), &state_path).is_err() {
            return;
        }
        assert_eq!(
            service.snapshot(&UsageQueryDto::default()),
            Err(UsageServiceError::PathNotAllowed)
        );
    }

    #[cfg(windows)]
    #[test]
    fn private_directory_reparse_ancestor_is_rejected_before_cache_write() {
        use std::os::windows::fs::symlink_dir;
        let directory = tempdir().expect("temp");
        let outside = directory.path().join("outside");
        let linked_parent = directory.path().join("linked-parent");
        fs::create_dir(&outside).expect("create outside");
        if symlink_dir(&outside, &linked_parent).is_err() {
            return;
        }
        let private = linked_parent.join("private");
        assert!(matches!(
            UsageStatisticsService::with_protector(private, Arc::new(TestRawProtector),),
            Err(UsageServiceError::PathNotAllowed)
        ));
        assert!(!outside.join("private").exists());
    }
}
