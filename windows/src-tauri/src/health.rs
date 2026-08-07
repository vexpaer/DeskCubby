//! Read-only Android Health Connect daily aggregates for Windows.
//!
//! Android 0.9.3 deliberately excludes health history from its v27 backup and
//! from cloud sync.  Consequently this boundary only accepts a statistics JSON
//! file explicitly selected by the user (schema v1-v3, with v3 carrying steps,
//! distance and active calories).  Windows never calls an activity, health or
//! step collection API. Missing aggregates remain `null`; they are never
//! replaced with a fabricated zero.

use crate::security::{
    SecurityError, dpapi_protect_scoped, dpapi_unprotect_scoped, open_regular_file_no_reparse,
    reject_reparse_point, resolve_path_beneath,
};
use crate::usage::{UsageDayState, UsageRangeDto, parse_android_date, validate_android_zone_id};
use chrono::Duration;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::collections::HashSet;
use std::fs;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::UNIX_EPOCH;
use uuid::Uuid;

pub(crate) const HEALTH_DTO_VERSION: u32 = 1;
pub(crate) const MAX_HEALTH_JSON_BYTES: usize = 10 * 1024 * 1024;
const MAX_HEALTH_DAYS: usize = 36_600;
const MAX_STEPS_PER_DAY: i64 = 1_000_000;
const MAX_DISTANCE_METERS_PER_DAY: f64 = 1_000_000.0;
const MAX_ACTIVE_CALORIES_KILOCALORIES_PER_DAY: f64 = 100_000.0;
const HEALTH_SCHEMA_VERSION: i64 = 3;

const STATE_FILE_NAME: &str = "health-statistics-state.dchs";
const STATE_CONTAINER_MAGIC: &[u8; 8] = b"DCHLTV1\0";
const SNAPSHOT_PURPOSE: &str = "DeskCubby.Windows.Health.Snapshot.v1";
const SOURCE_PURPOSE: &str = "DeskCubby.Windows.Health.Source.v1";
const SOURCE_RECORD_TYPE: &str = "healthSourceV1";
const MAX_SOURCE_METADATA_BYTES: usize = 256 * 1024;
const MAX_SOURCE_PATH_UTF16_UNITS: usize = 32_767;
const MAX_PROTECTED_SNAPSHOT_BYTES: usize = MAX_HEALTH_JSON_BYTES + 2 * 1024 * 1024;
const MAX_PROTECTED_SOURCE_BYTES: usize = MAX_SOURCE_METADATA_BYTES + 1024 * 1024;
const MAX_STATE_CONTAINER_BYTES: usize =
    MAX_PROTECTED_SNAPSHOT_BYTES + MAX_PROTECTED_SOURCE_BYTES + 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub(crate) enum HealthServiceError {
    #[error("health JSON is invalid")]
    InvalidJson,
    #[error("health JSON exceeds its size limit")]
    TooLarge,
    #[error("the selected source is not an allowed regular file")]
    PathNotAllowed,
    #[error("the selected source no longer exists")]
    NotFound,
    #[error("the selected source changed while it was being read")]
    SourceChanged,
    #[error("the private health cache is unavailable")]
    Storage,
    #[error("the private health cache could not be decrypted")]
    Crypto,
    #[error("the private health cache is corrupt")]
    CacheCorrupt,
    #[error("no health snapshot has been imported")]
    NotConfigured,
    #[error("the current health source is not linked")]
    NotLinked,
}

impl HealthServiceError {
    pub(crate) const fn code(self) -> &'static str {
        match self {
            Self::InvalidJson => "health_statistics_invalid",
            Self::TooLarge => "health_statistics_too_large",
            Self::PathNotAllowed => "path_not_allowed",
            Self::NotFound => "health_statistics_source_missing",
            Self::SourceChanged => "health_statistics_source_changed",
            Self::Storage => "storage_unavailable",
            Self::Crypto | Self::CacheCorrupt => "health_statistics_cache_unavailable",
            Self::NotConfigured => "health_statistics_not_configured",
            Self::NotLinked => "health_statistics_not_linked",
        }
    }
}

impl From<SecurityError> for HealthServiceError {
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

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct HealthDay {
    pub(crate) date: String,
    pub(crate) zone_id: String,
    pub(crate) state: UsageDayState,
    pub(crate) collected_at_epoch_millis: i64,
    pub(crate) steps: Option<i64>,
    pub(crate) distance_meters: Option<f64>,
    pub(crate) active_calories_kilocalories: Option<f64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct HealthHistory {
    pub(crate) tracking_started_on: Option<String>,
    pub(crate) days: Vec<HealthDay>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CanonicalHealthHistory<'a> {
    schema_version: i64,
    tracking_started_on: Option<&'a str>,
    device_sensor_baseline: Option<Value>,
    days: &'a [HealthDay],
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StrictHealthV1 {
    schema_version: i64,
    tracking_started_on: RequiredNullableString,
    days: Vec<StrictHealthDayV1>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StrictHealthV2 {
    schema_version: i64,
    tracking_started_on: RequiredNullableString,
    device_sensor_baseline: RequiredNullableBaseline,
    days: Vec<StrictHealthDayV1>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StrictHealthV3 {
    schema_version: i64,
    tracking_started_on: RequiredNullableString,
    device_sensor_baseline: RequiredNullableBaseline,
    days: Vec<StrictHealthDayV3>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StrictHealthDayV1 {
    date: String,
    zone_id: String,
    state: UsageDayState,
    collected_at_epoch_millis: i64,
    steps: RequiredNullableI64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StrictHealthDayV3 {
    date: String,
    zone_id: String,
    state: UsageDayState,
    collected_at_epoch_millis: i64,
    steps: RequiredNullableI64,
    distance_meters: RequiredNullableF64,
    active_calories_kilocalories: RequiredNullableF64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StrictDeviceSensorBaseline {
    date: String,
    cumulative_steps: i64,
    captured_at_epoch_millis: i64,
}

#[derive(Deserialize)]
struct RequiredNullableString(Option<String>);
#[derive(Deserialize)]
struct RequiredNullableI64(Option<i64>);
#[derive(Deserialize)]
struct RequiredNullableF64(Option<f64>);
#[derive(Deserialize)]
struct RequiredNullableBaseline(Option<StrictDeviceSensorBaseline>);

pub(crate) fn parse_android_health_json(bytes: &[u8]) -> Result<HealthHistory, HealthServiceError> {
    if bytes.len() > MAX_HEALTH_JSON_BYTES {
        return Err(HealthServiceError::TooLarge);
    }
    let probe: Value =
        serde_json::from_slice(bytes).map_err(|_| HealthServiceError::InvalidJson)?;
    let version = probe
        .as_object()
        .and_then(|root| root.get("schemaVersion"))
        .and_then(Value::as_i64)
        .ok_or(HealthServiceError::InvalidJson)?;
    let history = match version {
        1 => {
            let decoded: StrictHealthV1 =
                serde_json::from_slice(bytes).map_err(|_| HealthServiceError::InvalidJson)?;
            if decoded.schema_version != 1 {
                return Err(HealthServiceError::InvalidJson);
            }
            HealthHistory {
                tracking_started_on: decoded.tracking_started_on.0,
                days: decoded.days.into_iter().map(health_day_from_v1).collect(),
            }
        }
        2 => {
            let decoded: StrictHealthV2 =
                serde_json::from_slice(bytes).map_err(|_| HealthServiceError::InvalidJson)?;
            if decoded.schema_version != 2 {
                return Err(HealthServiceError::InvalidJson);
            }
            validate_baseline(
                decoded.device_sensor_baseline.0.as_ref(),
                decoded.tracking_started_on.0.as_deref(),
            )?;
            HealthHistory {
                tracking_started_on: decoded.tracking_started_on.0,
                days: decoded.days.into_iter().map(health_day_from_v1).collect(),
            }
        }
        HEALTH_SCHEMA_VERSION => {
            let decoded: StrictHealthV3 =
                serde_json::from_slice(bytes).map_err(|_| HealthServiceError::InvalidJson)?;
            if decoded.schema_version != HEALTH_SCHEMA_VERSION {
                return Err(HealthServiceError::InvalidJson);
            }
            validate_baseline(
                decoded.device_sensor_baseline.0.as_ref(),
                decoded.tracking_started_on.0.as_deref(),
            )?;
            HealthHistory {
                tracking_started_on: decoded.tracking_started_on.0,
                days: decoded
                    .days
                    .into_iter()
                    .map(|day| HealthDay {
                        date: day.date,
                        zone_id: day.zone_id,
                        state: day.state,
                        collected_at_epoch_millis: day.collected_at_epoch_millis,
                        steps: day.steps.0,
                        distance_meters: day.distance_meters.0,
                        active_calories_kilocalories: day.active_calories_kilocalories.0,
                    })
                    .collect(),
            }
        }
        _ => return Err(HealthServiceError::InvalidJson),
    };
    validate_health_history(&history)?;
    Ok(history)
}

fn health_day_from_v1(day: StrictHealthDayV1) -> HealthDay {
    HealthDay {
        date: day.date,
        zone_id: day.zone_id,
        state: day.state,
        collected_at_epoch_millis: day.collected_at_epoch_millis,
        steps: day.steps.0,
        distance_meters: None,
        active_calories_kilocalories: None,
    }
}

fn validate_baseline(
    baseline: Option<&StrictDeviceSensorBaseline>,
    tracking_started_on: Option<&str>,
) -> Result<(), HealthServiceError> {
    if let Some(baseline) = baseline {
        let date =
            parse_android_date(&baseline.date).map_err(|_| HealthServiceError::InvalidJson)?;
        if baseline.cumulative_steps < 0 || baseline.captured_at_epoch_millis < 0 {
            return Err(HealthServiceError::InvalidJson);
        }
        if let Some(started) = tracking_started_on {
            let started =
                parse_android_date(started).map_err(|_| HealthServiceError::InvalidJson)?;
            if date < started {
                return Err(HealthServiceError::InvalidJson);
            }
        }
    }
    Ok(())
}

fn validate_health_history(history: &HealthHistory) -> Result<(), HealthServiceError> {
    if history.days.len() > MAX_HEALTH_DAYS {
        return Err(HealthServiceError::InvalidJson);
    }
    let tracking_started = history
        .tracking_started_on
        .as_deref()
        .map(|value| parse_android_date(value).map_err(|_| HealthServiceError::InvalidJson))
        .transpose()?;
    if tracking_started.is_none() && !history.days.is_empty() {
        return Err(HealthServiceError::InvalidJson);
    }
    let mut dates = HashSet::with_capacity(history.days.len());
    for day in &history.days {
        let date = parse_android_date(&day.date).map_err(|_| HealthServiceError::InvalidJson)?;
        if !dates.insert(day.date.as_str())
            || tracking_started.is_some_and(|started| date < started)
            || day.collected_at_epoch_millis < 0
        {
            return Err(HealthServiceError::InvalidJson);
        }
        validate_android_zone_id(&day.zone_id).map_err(|_| HealthServiceError::InvalidJson)?;
        if day
            .steps
            .is_some_and(|value| !(0..=MAX_STEPS_PER_DAY).contains(&value))
            || day.distance_meters.is_some_and(|value| {
                !value.is_finite() || !(0.0..=MAX_DISTANCE_METERS_PER_DAY).contains(&value)
            })
            || day.active_calories_kilocalories.is_some_and(|value| {
                !value.is_finite()
                    || !(0.0..=MAX_ACTIVE_CALORIES_KILOCALORIES_PER_DAY).contains(&value)
            })
        {
            return Err(HealthServiceError::InvalidJson);
        }
    }
    Ok(())
}

fn canonical_health_history(history: &HealthHistory) -> Result<Vec<u8>, HealthServiceError> {
    validate_health_history(history)?;
    let mut normalized = history.clone();
    normalized
        .days
        .sort_by(|left, right| left.date.cmp(&right.date));
    let bytes = serde_json::to_vec(&CanonicalHealthHistory {
        schema_version: HEALTH_SCHEMA_VERSION,
        tracking_started_on: normalized.tracking_started_on.as_deref(),
        device_sensor_baseline: None,
        days: &normalized.days,
    })
    .map_err(|_| HealthServiceError::InvalidJson)?;
    if bytes.len() > MAX_HEALTH_JSON_BYTES {
        return Err(HealthServiceError::TooLarge);
    }
    Ok(bytes)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum HealthSourceModeDto {
    Snapshot,
    LinkedFile,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum HealthSourceStateDto {
    Ready,
    Stale,
    Missing,
    Invalid,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct HealthSourceStatusDto {
    pub(crate) dto_version: u32,
    pub(crate) mode: HealthSourceModeDto,
    pub(crate) state: HealthSourceStateDto,
    pub(crate) display_name: String,
    pub(crate) can_refresh: bool,
    pub(crate) last_successful_read_at_ms: String,
    pub(crate) last_attempt_at_ms: String,
    pub(crate) source_modified_at_ms: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum HealthMetricDto {
    #[default]
    Steps,
    Distance,
    ActiveCalories,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct HealthQueryDto {
    #[serde(deserialize_with = "deserialize_health_dto_version")]
    pub(crate) dto_version: u32,
    #[serde(default)]
    pub(crate) range: UsageRangeDto,
    #[serde(default)]
    pub(crate) metric: HealthMetricDto,
}

fn deserialize_health_dto_version<'de, D>(deserializer: D) -> Result<u32, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let version = u32::deserialize(deserializer)?;
    if version == HEALTH_DTO_VERSION {
        Ok(version)
    } else {
        Err(<D::Error as serde::de::Error>::custom(
            "unsupported health DTO version",
        ))
    }
}

impl Default for HealthQueryDto {
    fn default() -> Self {
        Self {
            dto_version: HEALTH_DTO_VERSION,
            range: UsageRangeDto::default(),
            metric: HealthMetricDto::default(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct HealthPointDto {
    pub(crate) date: String,
    pub(crate) zone_id: String,
    pub(crate) state: UsageDayState,
    pub(crate) collected_at_epoch_millis: String,
    pub(crate) value: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct HealthOverviewDto {
    pub(crate) range_started_on: Option<String>,
    pub(crate) recorded_days: u32,
    pub(crate) days_with_data: u32,
    pub(crate) total: Option<String>,
    pub(crate) average_per_data_day: Option<String>,
    pub(crate) highest_day: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct HealthSnapshotDto {
    pub(crate) dto_version: u32,
    pub(crate) source: HealthSourceStatusDto,
    pub(crate) tracking_started_on: Option<String>,
    pub(crate) anchor_date: Option<String>,
    pub(crate) metric: HealthMetricDto,
    pub(crate) overview: HealthOverviewDto,
    pub(crate) points: Vec<HealthPointDto>,
}

pub(crate) fn aggregate_health_snapshot(
    history: &HealthHistory,
    source: HealthSourceStatusDto,
    query: &HealthQueryDto,
) -> Result<HealthSnapshotDto, HealthServiceError> {
    if query.dto_version != HEALTH_DTO_VERSION {
        return Err(HealthServiceError::InvalidJson);
    }
    validate_health_history(history)?;
    let anchor = history
        .days
        .last()
        .map(|day| parse_android_date(&day.date).map_err(|_| HealthServiceError::InvalidJson))
        .transpose()?;
    let range_start = match (anchor, query.range) {
        (Some(anchor), UsageRangeDto::Last7Days) => Some(
            anchor
                .checked_sub_signed(Duration::days(6))
                .ok_or(HealthServiceError::InvalidJson)?,
        ),
        (Some(anchor), UsageRangeDto::Last30Days) => Some(
            anchor
                .checked_sub_signed(Duration::days(29))
                .ok_or(HealthServiceError::InvalidJson)?,
        ),
        (Some(anchor), UsageRangeDto::Last90Days) => Some(
            anchor
                .checked_sub_signed(Duration::days(89))
                .ok_or(HealthServiceError::InvalidJson)?,
        ),
        (_, UsageRangeDto::All) | (None, _) => None,
    };
    let ranged = history
        .days
        .iter()
        .filter(|day| {
            range_start.is_none_or(|start| {
                parse_android_date(&day.date)
                    .map(|date| date >= start)
                    .unwrap_or(false)
            })
        })
        .collect::<Vec<_>>();
    let points = ranged
        .iter()
        .map(|day| HealthPointDto {
            date: day.date.clone(),
            zone_id: day.zone_id.clone(),
            state: day.state,
            collected_at_epoch_millis: day.collected_at_epoch_millis.to_string(),
            value: health_value(day, query.metric).map(format_health_number),
        })
        .collect::<Vec<_>>();
    let values = ranged
        .iter()
        .filter_map(|day| health_value(day, query.metric))
        .collect::<Vec<_>>();
    let total = (!values.is_empty()).then(|| values.iter().sum::<f64>());
    let average = total.map(|value| value / values.len() as f64);
    let highest = values.iter().copied().reduce(f64::max);
    Ok(HealthSnapshotDto {
        dto_version: HEALTH_DTO_VERSION,
        source,
        tracking_started_on: history.tracking_started_on.clone(),
        anchor_date: anchor.map(|date| date.format("%Y-%m-%d").to_string()),
        metric: query.metric,
        overview: HealthOverviewDto {
            range_started_on: ranged.first().map(|day| day.date.clone()),
            recorded_days: u32::try_from(ranged.len())
                .map_err(|_| HealthServiceError::InvalidJson)?,
            days_with_data: u32::try_from(values.len())
                .map_err(|_| HealthServiceError::InvalidJson)?,
            total: total.map(format_health_number),
            average_per_data_day: average.map(format_health_number),
            highest_day: highest.map(format_health_number),
        },
        points,
    })
}

fn health_value(day: &HealthDay, metric: HealthMetricDto) -> Option<f64> {
    match metric {
        HealthMetricDto::Steps => day.steps.map(|value| value as f64),
        HealthMetricDto::Distance => day.distance_meters,
        HealthMetricDto::ActiveCalories => day.active_calories_kilocalories,
    }
}

fn format_health_number(value: f64) -> String {
    if value == 0.0 {
        "0".to_owned()
    } else {
        value.to_string()
    }
}

trait RawProtector: Send + Sync {
    fn protect(
        &self,
        plaintext: &[u8],
        purpose: &[u8],
        maximum_plaintext: usize,
    ) -> Result<Vec<u8>, HealthServiceError>;
    fn unprotect(
        &self,
        ciphertext: &[u8],
        purpose: &[u8],
        maximum_plaintext: usize,
    ) -> Result<Vec<u8>, HealthServiceError>;
}

struct DpapiRawProtector;

impl RawProtector for DpapiRawProtector {
    fn protect(
        &self,
        plaintext: &[u8],
        purpose: &[u8],
        maximum_plaintext: usize,
    ) -> Result<Vec<u8>, HealthServiceError> {
        dpapi_protect_scoped(plaintext, purpose, maximum_plaintext)
            .map_err(|_| HealthServiceError::Crypto)
    }

    fn unprotect(
        &self,
        ciphertext: &[u8],
        purpose: &[u8],
        maximum_plaintext: usize,
    ) -> Result<Vec<u8>, HealthServiceError> {
        dpapi_unprotect_scoped(ciphertext, purpose, maximum_plaintext)
            .map_err(|_| HealthServiceError::Crypto)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StoredSourceMetadata {
    record_type: String,
    mode: HealthSourceModeDto,
    state: HealthSourceStateDto,
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
    history: HealthHistory,
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

pub struct HealthStatisticsService {
    private_dir: PathBuf,
    operation: Mutex<()>,
    protector: Arc<dyn RawProtector>,
}

impl HealthStatisticsService {
    pub(crate) fn new(private_dir: PathBuf) -> Result<Self, HealthServiceError> {
        Self::with_protector(private_dir, Arc::new(DpapiRawProtector))
    }

    fn with_protector(
        private_dir: PathBuf,
        protector: Arc<dyn RawProtector>,
    ) -> Result<Self, HealthServiceError> {
        ensure_private_directory(&private_dir)?;
        Ok(Self {
            private_dir,
            operation: Mutex::new(()),
            protector,
        })
    }

    pub(crate) fn import_snapshot(
        &self,
        selected_path: &Path,
        now_ms: i64,
    ) -> Result<HealthSourceStatusDto, HealthServiceError> {
        let _guard = self.lock()?;
        let source = read_stable_source(selected_path)?;
        let metadata = metadata_for_source(
            &source,
            HealthSourceModeDto::Snapshot,
            HealthSourceStateDto::Ready,
            None,
            now_ms,
        );
        self.write_state(&metadata, &source.canonical)?;
        Ok(source_status(&metadata))
    }

    pub(crate) fn link_file(
        &self,
        selected_path: &Path,
        now_ms: i64,
    ) -> Result<HealthSourceStatusDto, HealthServiceError> {
        let _guard = self.lock()?;
        let source = read_stable_source(selected_path)?;
        let linked_path = source
            .canonical_path
            .to_str()
            .filter(|value| value.encode_utf16().count() <= MAX_SOURCE_PATH_UTF16_UNITS)
            .ok_or(HealthServiceError::PathNotAllowed)?
            .to_owned();
        let metadata = metadata_for_source(
            &source,
            HealthSourceModeDto::LinkedFile,
            HealthSourceStateDto::Ready,
            Some(linked_path),
            now_ms,
        );
        self.write_state(&metadata, &source.canonical)?;
        Ok(source_status(&metadata))
    }

    pub(crate) fn refresh_linked(
        &self,
        now_ms: i64,
    ) -> Result<HealthSourceStatusDto, HealthServiceError> {
        let _guard = self.lock()?;
        let mut current = self
            .read_state()?
            .ok_or(HealthServiceError::NotConfigured)?;
        if current.metadata.mode != HealthSourceModeDto::LinkedFile {
            return Err(HealthServiceError::NotLinked);
        }
        let linked_path = current
            .metadata
            .linked_path
            .as_deref()
            .ok_or(HealthServiceError::CacheCorrupt)?;
        let attempt_ms = now_ms
            .max(0)
            .max(current.metadata.last_successful_read_at_ms)
            .max(current.metadata.last_attempt_at_ms);
        match read_stable_source(Path::new(linked_path)) {
            Ok(source) => {
                let metadata = metadata_for_source(
                    &source,
                    HealthSourceModeDto::LinkedFile,
                    HealthSourceStateDto::Ready,
                    Some(linked_path.to_owned()),
                    attempt_ms,
                );
                self.write_state(&metadata, &source.canonical)?;
                Ok(source_status(&metadata))
            }
            Err(error) => {
                current.metadata.state = match error {
                    HealthServiceError::NotFound => HealthSourceStateDto::Missing,
                    HealthServiceError::InvalidJson | HealthServiceError::TooLarge => {
                        HealthSourceStateDto::Invalid
                    }
                    _ => HealthSourceStateDto::Stale,
                };
                current.metadata.last_attempt_at_ms = attempt_ms;
                self.write_state(&current.metadata, &current.canonical)?;
                Ok(source_status(&current.metadata))
            }
        }
    }

    pub(crate) fn snapshot(
        &self,
        query: &HealthQueryDto,
    ) -> Result<Option<HealthSnapshotDto>, HealthServiceError> {
        let _guard = self.lock()?;
        let Some(stored) = self.read_state()? else {
            return Ok(None);
        };
        Ok(Some(aggregate_health_snapshot(
            &stored.history,
            source_status(&stored.metadata),
            query,
        )?))
    }

    fn lock(&self) -> Result<MutexGuard<'_, ()>, HealthServiceError> {
        self.operation
            .lock()
            .map_err(|_| HealthServiceError::Storage)
    }

    fn state_path(&self) -> Result<PathBuf, HealthServiceError> {
        ensure_private_directory(&self.private_dir)?;
        resolve_path_beneath(&self.private_dir, STATE_FILE_NAME).map_err(HealthServiceError::from)
    }

    fn write_state(
        &self,
        metadata: &StoredSourceMetadata,
        canonical: &[u8],
    ) -> Result<(), HealthServiceError> {
        if canonical.len() > MAX_HEALTH_JSON_BYTES {
            return Err(HealthServiceError::TooLarge);
        }
        let metadata_bytes =
            serde_json::to_vec(metadata).map_err(|_| HealthServiceError::CacheCorrupt)?;
        if metadata_bytes.len() > MAX_SOURCE_METADATA_BYTES {
            return Err(HealthServiceError::CacheCorrupt);
        }
        let protected_metadata =
            protect_for_purpose(self.protector.as_ref(), SOURCE_PURPOSE, &metadata_bytes)?;
        let protected_snapshot =
            protect_for_purpose(self.protector.as_ref(), SNAPSHOT_PURPOSE, canonical)?;
        let container = encode_state_container(&protected_metadata, &protected_snapshot)?;
        write_private_atomic(&self.private_dir, STATE_FILE_NAME, &container)
    }

    fn read_state(&self) -> Result<Option<StoredState>, HealthServiceError> {
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
            MAX_HEALTH_JSON_BYTES,
        )?;
        let metadata: StoredSourceMetadata = serde_json::from_slice(&metadata_bytes)
            .map_err(|_| HealthServiceError::CacheCorrupt)?;
        metadata_bytes.fill(0);
        validate_stored_metadata(&metadata)?;
        let history = parse_android_health_json(&canonical).map_err(|_| {
            canonical.fill(0);
            HealthServiceError::CacheCorrupt
        })?;
        if sha256_hex(&canonical) != metadata.snapshot_sha256 {
            canonical.fill(0);
            return Err(HealthServiceError::CacheCorrupt);
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
    mode: HealthSourceModeDto,
    state: HealthSourceStateDto,
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

fn validate_stored_metadata(metadata: &StoredSourceMetadata) -> Result<(), HealthServiceError> {
    if metadata.record_type != SOURCE_RECORD_TYPE
        || !valid_source_display_name(&metadata.display_name)
        || metadata.last_successful_read_at_ms < 0
        || metadata.last_attempt_at_ms < metadata.last_successful_read_at_ms
        || metadata
            .source_modified_at_ms
            .is_some_and(|value| value < 0)
        || metadata.source_size == 0
        || metadata.source_size > MAX_HEALTH_JSON_BYTES as u64
        || !is_sha256_hex(&metadata.source_sha256)
        || !is_sha256_hex(&metadata.snapshot_sha256)
    {
        return Err(HealthServiceError::CacheCorrupt);
    }
    match metadata.mode {
        HealthSourceModeDto::Snapshot
            if metadata.linked_path.is_some()
                || metadata.state != HealthSourceStateDto::Ready
                || metadata.last_attempt_at_ms != metadata.last_successful_read_at_ms =>
        {
            Err(HealthServiceError::CacheCorrupt)
        }
        HealthSourceModeDto::LinkedFile => {
            let path = metadata
                .linked_path
                .as_deref()
                .ok_or(HealthServiceError::CacheCorrupt)?;
            if !Path::new(path).is_absolute()
                || path.encode_utf16().count() > MAX_SOURCE_PATH_UTF16_UNITS
                || path.chars().any(char::is_control)
                || (metadata.state == HealthSourceStateDto::Ready
                    && metadata.last_attempt_at_ms != metadata.last_successful_read_at_ms)
            {
                Err(HealthServiceError::CacheCorrupt)
            } else {
                Ok(())
            }
        }
        _ => Ok(()),
    }
}

fn source_status(metadata: &StoredSourceMetadata) -> HealthSourceStatusDto {
    HealthSourceStatusDto {
        dto_version: HEALTH_DTO_VERSION,
        mode: metadata.mode,
        state: metadata.state,
        display_name: metadata.display_name.clone(),
        can_refresh: metadata.mode == HealthSourceModeDto::LinkedFile,
        last_successful_read_at_ms: metadata.last_successful_read_at_ms.to_string(),
        last_attempt_at_ms: metadata.last_attempt_at_ms.to_string(),
        source_modified_at_ms: metadata
            .source_modified_at_ms
            .map(|value| value.to_string()),
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

fn read_stable_source(path: &Path) -> Result<StableSource, HealthServiceError> {
    let path_text = path.to_str().ok_or(HealthServiceError::PathNotAllowed)?;
    if !path.is_absolute()
        || path_text.encode_utf16().count() > MAX_SOURCE_PATH_UTF16_UNITS
        || path_text.chars().any(char::is_control)
        || !path
            .extension()
            .and_then(|value| value.to_str())
            .is_some_and(|value| value.eq_ignore_ascii_case("json"))
    {
        return Err(HealthServiceError::PathNotAllowed);
    }
    reject_reparse_chain(path)?;
    let canonical_path = fs::canonicalize(path).map_err(map_source_io_error)?;
    reject_reparse_chain(&canonical_path)?;
    let mut file =
        open_regular_file_no_reparse(&canonical_path).map_err(HealthServiceError::from)?;
    reject_reparse_chain(&canonical_path)?;
    let before = file.metadata().map_err(map_source_io_error)?;
    if !before.is_file() {
        return Err(HealthServiceError::PathNotAllowed);
    }
    if before.len() > MAX_HEALTH_JSON_BYTES as u64 {
        return Err(HealthServiceError::TooLarge);
    }
    let first = read_open_file_bounded(&mut file, MAX_HEALTH_JSON_BYTES)?;
    file.seek(SeekFrom::Start(0)).map_err(map_source_io_error)?;
    let second = read_open_file_bounded(&mut file, MAX_HEALTH_JSON_BYTES)?;
    let after = file.metadata().map_err(map_source_io_error)?;
    if first != second
        || before.len() != after.len()
        || before.modified().ok() != after.modified().ok()
    {
        return Err(HealthServiceError::SourceChanged);
    }
    reject_reparse_chain(&canonical_path)?;
    if fs::canonicalize(path).map_err(map_source_io_error)? != canonical_path {
        return Err(HealthServiceError::SourceChanged);
    }
    let mut current_file =
        open_regular_file_no_reparse(&canonical_path).map_err(HealthServiceError::from)?;
    let current = read_open_file_bounded(&mut current_file, MAX_HEALTH_JSON_BYTES)?;
    let current_metadata = current_file.metadata().map_err(map_source_io_error)?;
    if current != first
        || current_metadata.len() != after.len()
        || current_metadata.modified().ok() != after.modified().ok()
    {
        return Err(HealthServiceError::SourceChanged);
    }
    reject_reparse_chain(&canonical_path)?;
    if fs::canonicalize(path).map_err(map_source_io_error)? != canonical_path {
        return Err(HealthServiceError::SourceChanged);
    }
    let history = parse_android_health_json(&first)?;
    let canonical = canonical_health_history(&history)?;
    let display_name = canonical_path
        .file_name()
        .and_then(|value| value.to_str())
        .filter(|value| valid_source_display_name(value))
        .ok_or(HealthServiceError::PathNotAllowed)?
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
) -> Result<Vec<u8>, HealthServiceError> {
    let mut bytes = Vec::new();
    (&mut *file)
        .take(maximum.saturating_add(1) as u64)
        .read_to_end(&mut bytes)
        .map_err(map_source_io_error)?;
    if bytes.len() > maximum {
        bytes.fill(0);
        return Err(HealthServiceError::TooLarge);
    }
    Ok(bytes)
}

fn reject_reparse_chain(path: &Path) -> Result<(), HealthServiceError> {
    for ancestor in path.ancestors() {
        match fs::symlink_metadata(ancestor) {
            Ok(_) => reject_reparse_point(ancestor).map_err(HealthServiceError::from)?,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Err(HealthServiceError::NotFound);
            }
            Err(error) => return Err(map_source_io_error(error)),
        }
    }
    Ok(())
}

fn reject_existing_reparse_ancestors(path: &Path) -> Result<(), HealthServiceError> {
    for ancestor in path.ancestors() {
        match fs::symlink_metadata(ancestor) {
            Ok(_) => reject_reparse_point(ancestor).map_err(HealthServiceError::from)?,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(map_source_io_error(error)),
        }
    }
    Ok(())
}

fn map_source_io_error(error: std::io::Error) -> HealthServiceError {
    match error.kind() {
        std::io::ErrorKind::NotFound => HealthServiceError::NotFound,
        std::io::ErrorKind::InvalidInput | std::io::ErrorKind::InvalidData => {
            HealthServiceError::PathNotAllowed
        }
        _ => HealthServiceError::Storage,
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

fn ensure_private_directory(path: &Path) -> Result<(), HealthServiceError> {
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
        return Err(HealthServiceError::PathNotAllowed);
    }
    Ok(())
}

fn read_private_bounded(path: &Path, maximum: usize) -> Result<Vec<u8>, HealthServiceError> {
    reject_reparse_chain(path)?;
    let file = open_regular_file_no_reparse(path).map_err(HealthServiceError::from)?;
    reject_reparse_chain(path)?;
    let metadata = file.metadata().map_err(map_source_io_error)?;
    if metadata.len() > maximum as u64 {
        return Err(HealthServiceError::CacheCorrupt);
    }
    let mut bytes = Vec::with_capacity((metadata.len() as usize).min(maximum));
    file.take(maximum.saturating_add(1) as u64)
        .read_to_end(&mut bytes)
        .map_err(map_source_io_error)?;
    if bytes.len() > maximum {
        bytes.fill(0);
        return Err(HealthServiceError::CacheCorrupt);
    }
    Ok(bytes)
}

fn write_private_atomic(
    private_dir: &Path,
    leaf: &str,
    bytes: &[u8],
) -> Result<(), HealthServiceError> {
    ensure_private_directory(private_dir)?;
    let target = resolve_path_beneath(private_dir, leaf).map_err(HealthServiceError::from)?;
    let pending_leaf = format!(".{leaf}.{}.pending", Uuid::new_v4().simple());
    let previous_leaf = format!(".{leaf}.{}.previous", Uuid::new_v4().simple());
    let pending =
        resolve_path_beneath(private_dir, &pending_leaf).map_err(HealthServiceError::from)?;
    let previous =
        resolve_path_beneath(private_dir, &previous_leaf).map_err(HealthServiceError::from)?;
    reject_reparse_point(&target).map_err(HealthServiceError::from)?;
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
        return Err(HealthServiceError::Storage);
    }
    let had_previous = if target.exists() {
        reject_reparse_point(&target).map_err(HealthServiceError::from)?;
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
        return Err(HealthServiceError::Storage);
    }
    if had_previous {
        let _ = fs::remove_file(previous);
    }
    Ok(())
}

fn encode_state_container(metadata: &[u8], snapshot: &[u8]) -> Result<Vec<u8>, HealthServiceError> {
    if metadata.len() > MAX_PROTECTED_SOURCE_BYTES || snapshot.len() > MAX_PROTECTED_SNAPSHOT_BYTES
    {
        return Err(HealthServiceError::CacheCorrupt);
    }
    let metadata_len =
        u32::try_from(metadata.len()).map_err(|_| HealthServiceError::CacheCorrupt)?;
    let snapshot_len =
        u32::try_from(snapshot.len()).map_err(|_| HealthServiceError::CacheCorrupt)?;
    let mut output = Vec::with_capacity(16 + metadata.len() + snapshot.len());
    output.extend_from_slice(STATE_CONTAINER_MAGIC);
    output.extend_from_slice(&metadata_len.to_le_bytes());
    output.extend_from_slice(&snapshot_len.to_le_bytes());
    output.extend_from_slice(metadata);
    output.extend_from_slice(snapshot);
    if output.len() > MAX_STATE_CONTAINER_BYTES {
        return Err(HealthServiceError::CacheCorrupt);
    }
    Ok(output)
}

fn decode_state_container(bytes: &[u8]) -> Result<(&[u8], &[u8]), HealthServiceError> {
    if bytes.len() < 16 || &bytes[..8] != STATE_CONTAINER_MAGIC {
        return Err(HealthServiceError::CacheCorrupt);
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
        return Err(HealthServiceError::CacheCorrupt);
    }
    let metadata_end = 16 + metadata_len;
    Ok((&bytes[16..metadata_end], &bytes[metadata_end..]))
}

fn protect_for_purpose(
    protector: &dyn RawProtector,
    purpose: &str,
    plaintext: &[u8],
) -> Result<Vec<u8>, HealthServiceError> {
    let maximum = match purpose {
        SNAPSHOT_PURPOSE => MAX_HEALTH_JSON_BYTES,
        SOURCE_PURPOSE => MAX_SOURCE_METADATA_BYTES,
        _ => return Err(HealthServiceError::Crypto),
    };
    protector.protect(plaintext, purpose.as_bytes(), maximum)
}

fn unprotect_for_purpose(
    protector: &dyn RawProtector,
    purpose: &str,
    protected: &[u8],
    maximum_plaintext: usize,
) -> Result<Vec<u8>, HealthServiceError> {
    if !matches!(purpose, SNAPSHOT_PURPOSE | SOURCE_PURPOSE) {
        return Err(HealthServiceError::Crypto);
    }
    protector.unprotect(protected, purpose.as_bytes(), maximum_plaintext)
}

fn read_u32(bytes: &[u8]) -> Result<u32, HealthServiceError> {
    let array: [u8; 4] = bytes
        .try_into()
        .map_err(|_| HealthServiceError::CacheCorrupt)?;
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
        ) -> Result<Vec<u8>, HealthServiceError> {
            if plaintext.len() > maximum_plaintext {
                return Err(HealthServiceError::TooLarge);
            }
            let mut output = purpose.to_vec();
            output.push(0);
            output.extend(plaintext.iter().map(|byte| byte ^ 0xa5));
            Ok(output)
        }

        fn unprotect(
            &self,
            ciphertext: &[u8],
            purpose: &[u8],
            maximum_plaintext: usize,
        ) -> Result<Vec<u8>, HealthServiceError> {
            let prefix = ciphertext
                .get(..purpose.len() + 1)
                .ok_or(HealthServiceError::Crypto)?;
            if prefix != [purpose, &[0]].concat() {
                return Err(HealthServiceError::Crypto);
            }
            let output = ciphertext[purpose.len() + 1..]
                .iter()
                .map(|byte| byte ^ 0xa5)
                .collect::<Vec<_>>();
            if output.len() > maximum_plaintext {
                return Err(HealthServiceError::TooLarge);
            }
            Ok(output)
        }
    }

    fn valid_health_json() -> Vec<u8> {
        br#"{
          "schemaVersion":3,
          "trackingStartedOn":"2026-07-27",
          "deviceSensorBaseline":null,
          "days":[
            {
              "date":"2026-07-27",
              "zoneId":"Asia/Shanghai",
              "state":"FINAL",
              "collectedAtEpochMillis":42,
              "steps":1234,
              "distanceMeters":987.5,
              "activeCaloriesKilocalories":321.25
            },
            {
              "date":"2026-07-28",
              "zoneId":"Asia/Shanghai",
              "state":"OPEN",
              "collectedAtEpochMillis":43,
              "steps":null,
              "distanceMeters":0,
              "activeCaloriesKilocalories":null
            }
          ]
        }"#
        .to_vec()
    }

    fn source_status() -> HealthSourceStatusDto {
        HealthSourceStatusDto {
            dto_version: HEALTH_DTO_VERSION,
            mode: HealthSourceModeDto::Snapshot,
            state: HealthSourceStateDto::Ready,
            display_name: "step-statistics.json".to_owned(),
            can_refresh: false,
            last_successful_read_at_ms: "1".to_owned(),
            last_attempt_at_ms: "1".to_owned(),
            source_modified_at_ms: None,
        }
    }

    fn service(path: &Path) -> HealthStatisticsService {
        HealthStatisticsService::with_protector(path.to_owned(), Arc::new(TestRawProtector))
            .expect("create health service")
    }

    #[test]
    fn v3_preserves_missing_aggregates_distinct_from_explicit_zero() {
        let history = parse_android_health_json(&valid_health_json()).expect("parse health");
        assert_eq!(history.days[1].steps, None);
        assert_eq!(history.days[1].distance_meters, Some(0.0));
        assert_eq!(history.days[1].active_calories_kilocalories, None);

        let steps = aggregate_health_snapshot(
            &history,
            source_status(),
            &HealthQueryDto {
                range: UsageRangeDto::All,
                metric: HealthMetricDto::Steps,
                ..HealthQueryDto::default()
            },
        )
        .expect("aggregate steps");
        assert_eq!(steps.overview.days_with_data, 1);
        assert_eq!(steps.overview.total.as_deref(), Some("1234"));
        assert_eq!(steps.points[1].value, None);

        let distance = aggregate_health_snapshot(
            &history,
            source_status(),
            &HealthQueryDto {
                range: UsageRangeDto::All,
                metric: HealthMetricDto::Distance,
                ..HealthQueryDto::default()
            },
        )
        .expect("aggregate distance");
        assert_eq!(distance.overview.days_with_data, 2);
        assert_eq!(distance.points[1].value.as_deref(), Some("0"));
    }

    #[test]
    fn legacy_step_schemas_import_without_inventing_new_metrics() {
        let v1 = br#"{
          "schemaVersion":1,
          "trackingStartedOn":"2026-07-27",
          "days":[{
            "date":"2026-07-27","zoneId":"UTC","state":"FINAL",
            "collectedAtEpochMillis":1,"steps":10
          }]
        }"#;
        let history = parse_android_health_json(v1).expect("parse v1");
        assert_eq!(history.days[0].steps, Some(10));
        assert_eq!(history.days[0].distance_meters, None);
        assert_eq!(history.days[0].active_calories_kilocalories, None);
    }

    #[test]
    fn parser_rejects_duplicates_ranges_and_application_backup_without_health() {
        let duplicate = String::from_utf8(valid_health_json())
            .expect("utf8")
            .replacen("\"steps\":1234", "\"steps\":1234,\"steps\":1234", 1);
        assert_eq!(
            parse_android_health_json(duplicate.as_bytes()),
            Err(HealthServiceError::InvalidJson)
        );

        let mut invalid: Value = serde_json::from_slice(&valid_health_json()).expect("json");
        invalid["days"][0]["steps"] = Value::from(MAX_STEPS_PER_DAY + 1);
        assert_eq!(
            parse_android_health_json(&serde_json::to_vec(&invalid).expect("encode invalid")),
            Err(HealthServiceError::InvalidJson)
        );

        // Android v27 intentionally excludes Health Connect history. Never
        // manufacture a zero-valued history from its settings toggles.
        let backup = br#"{"version":27,"settings":{"stepTrackingEnabled":true}}"#;
        assert_eq!(
            parse_android_health_json(backup),
            Err(HealthServiceError::InvalidJson)
        );
    }

    #[test]
    fn linked_refresh_failure_keeps_last_valid_snapshot_and_never_writes_source() {
        let directory = tempdir().expect("temp");
        let private = directory.path().join("private");
        let source = directory.path().join("health.json");
        fs::write(&source, valid_health_json()).expect("write source");
        let before = fs::read(&source).expect("read source");
        let service = service(&private);
        service.link_file(&source, 100).expect("link");
        let valid = service
            .snapshot(&HealthQueryDto::default())
            .expect("snapshot")
            .expect("configured");
        assert_eq!(fs::read(&source).expect("read source"), before);

        fs::write(&source, b"{invalid").expect("replace source");
        let status = service.refresh_linked(200).expect("safe failed refresh");
        assert_eq!(status.state, HealthSourceStateDto::Invalid);
        let retained = service
            .snapshot(&HealthQueryDto::default())
            .expect("snapshot")
            .expect("configured");
        assert_eq!(retained.points, valid.points);
        assert_eq!(retained.source.state, HealthSourceStateDto::Invalid);
    }

    #[test]
    fn ipc_status_and_private_container_hide_source_path_and_raw_health() {
        let directory = tempdir().expect("temp");
        let private = directory.path().join("private");
        let source = directory.path().join("private-health-name.json");
        fs::write(&source, valid_health_json()).expect("write source");
        let service = service(&private);
        let status = service.import_snapshot(&source, 100).expect("import");
        let serialized = serde_json::to_string(&status).expect("serialize");
        assert!(!serialized.contains(directory.path().to_string_lossy().as_ref()));

        let container = fs::read(private.join(STATE_FILE_NAME)).expect("read cache");
        let text = String::from_utf8_lossy(&container);
        assert!(!text.contains(directory.path().to_string_lossy().as_ref()));
        assert!(!text.contains("activeCaloriesKilocalories"));
        assert!(!text.contains("1234"));
    }
}
