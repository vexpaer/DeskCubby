//! Safe Rust boundary around Tauri's desktop updater.
//!
//! The checked-in Tauri configuration contains an updater object with an empty
//! public key and no endpoints. Both fields are needed for plugin
//! deserialization, but they do not create a trust anchor. A production release
//! injects real values through a temporary `--config` overlay. This keeps
//! ordinary local builds offline at the updater boundary.

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::{Engine as _, engine::general_purpose::STANDARD};
use minisign_verify::{PublicKey, Signature};
use serde::Serialize;
use tauri::{AppHandle, Emitter, Runtime};
use tauri_plugin_updater::{Updater, UpdaterExt};
use updater_http::redirect::Policy;

const MAX_RELEASE_NOTES_BYTES: usize = 64 * 1024;
const MAX_PUBLIC_KEY_BASE64_BYTES: usize = 16 * 1024;
const MAX_SIGNATURE_BASE64_BYTES: usize = 64 * 1024;
const MAX_INSTALLER_BYTES: usize = 512 * 1024 * 1024;
const MAX_REDIRECTS: usize = 5;
const MANIFEST_TIMEOUT: Duration = Duration::from_secs(30);
const DOWNLOAD_TIMEOUT: Duration = Duration::from_secs(15 * 60);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);
pub const AUTOMATIC_CHECK_INITIAL_DELAY: Duration = Duration::from_secs(60);
pub const AUTOMATIC_CHECK_MIN_INTERVAL_MILLIS: i64 = 24 * 60 * 60 * 1_000;
pub const AUTOMATIC_CHECK_MAX_POLL_DELAY: Duration = Duration::from_secs(60 * 60);
pub const UPDATE_AVAILABLE_EVENT: &str = "update-available";
static UPDATE_OPERATION: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct UpdateMetadata {
    pub version: String,
    pub current_version: String,
    pub notes: Option<String>,
    pub published_at: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AutomaticUpdateState {
    pub enabled: bool,
    pub last_attempted_at: Option<i64>,
}

/// Small persistence boundary used by the startup scheduler. The database
/// implementation owns its schema and error type; the updater deliberately
/// does not log or serialize persistence errors.
pub trait AutomaticUpdateStore: Send + Sync + 'static {
    type Error: Send;

    fn automatic_update_state(&self) -> Result<AutomaticUpdateState, Self::Error>;
    fn claim_automatic_update_attempt(&self, attempted_at: i64) -> Result<bool, Self::Error>;
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct UpdateAvailableEventV1 {
    pub schema_version: u32,
    pub current_version: String,
    pub version: String,
    pub notes: Option<String>,
    pub published_at: Option<String>,
}

impl From<UpdateMetadata> for UpdateAvailableEventV1 {
    fn from(metadata: UpdateMetadata) -> Self {
        Self {
            schema_version: 1,
            current_version: metadata.current_version,
            version: metadata.version,
            notes: metadata.notes,
            published_at: metadata.published_at,
        }
    }
}

/// Errors deliberately contain no endpoint, response body, path, or signature
/// material. IPC commands should serialize `code`, `message`, and `retryable`
/// rather than an underlying updater error.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UpdateError {
    NotConfigured,
    Busy,
    NoUpdate,
    VersionChanged,
    CheckFailed,
    InstallFailed,
}

impl UpdateError {
    pub const fn code(self) -> &'static str {
        match self {
            Self::NotConfigured => "update_not_configured",
            Self::Busy => "update_busy",
            Self::NoUpdate => "update_not_available",
            Self::VersionChanged => "update_version_changed",
            Self::CheckFailed => "update_check_failed",
            Self::InstallFailed => "update_install_failed",
        }
    }

    pub const fn message(self) -> &'static str {
        match self {
            Self::NotConfigured => "Automatic updates are not configured for this build.",
            Self::Busy => "Another update operation is already running.",
            Self::NoUpdate => "The requested update is no longer available.",
            Self::VersionChanged => {
                "A different update is now available. Check again before installing."
            }
            Self::CheckFailed => "DeskCubby could not check for updates.",
            Self::InstallFailed => "DeskCubby could not download or install the update.",
        }
    }

    pub const fn retryable(self) -> bool {
        !matches!(self, Self::NotConfigured)
    }
}

pub fn plugin<R: Runtime>() -> tauri::plugin::TauriPlugin<R, tauri_plugin_updater::Config> {
    tauri_plugin_updater::Builder::new().build()
}

/// Returns true only when the effective, merged Tauri configuration contains a
/// non-empty public key and at least one HTTPS endpoint. The production release
/// script supplies this configuration; the source configuration has only the
/// empty values Tauri needs to deserialize the plugin configuration safely.
pub fn is_configured<R: Runtime>(app: &AppHandle<R>) -> bool {
    app.config()
        .plugins
        .0
        .get("updater")
        .is_some_and(valid_updater_config)
}

/// Starts the delayed automatic-check scheduler for this process.
///
/// The persistent attempt timestamp is atomically claimed before the network
/// boundary only while automatic checks remain enabled, closing the race with
/// a simultaneous user opt-out. Transient failures therefore cannot cause
/// repeated checks across restarts. An unconfigured build returns before
/// loading the preference store and before calling `app.updater().check()`.
/// While the app stays open, the scheduler reloads the preference at least
/// hourly and never attempts a check less than 24 hours after the persisted
/// prior attempt. It only emits metadata for an available update; it never
/// downloads, installs, or restarts the application.
pub fn spawn_automatic_update_scheduler<R, S>(app: AppHandle<R>, store: S)
where
    R: Runtime,
    S: AutomaticUpdateStore,
{
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(AUTOMATIC_CHECK_INITIAL_DELAY).await;

        if !is_configured(&app) {
            return;
        }

        loop {
            let Ok(state) = store.automatic_update_state() else {
                tokio::time::sleep(AUTOMATIC_CHECK_MAX_POLL_DELAY).await;
                continue;
            };
            let Some(now) = unix_time_millis() else {
                tokio::time::sleep(AUTOMATIC_CHECK_MAX_POLL_DELAY).await;
                continue;
            };
            let Some(wake_delay) = automatic_check_wake_delay(true, state, now) else {
                return;
            };
            if !wake_delay.is_zero() {
                tokio::time::sleep(wake_delay).await;
                continue;
            }
            match store.claim_automatic_update_attempt(now) {
                Ok(true) => {}
                Ok(false) => continue,
                Err(_) => {
                    tokio::time::sleep(AUTOMATIC_CHECK_MAX_POLL_DELAY).await;
                    continue;
                }
            }

            if let Ok(Some(metadata)) = check_for_update(&app).await {
                let _ = app.emit(
                    UPDATE_AVAILABLE_EVENT,
                    UpdateAvailableEventV1::from(metadata),
                );
            }
        }
    });
}

pub fn should_attempt_automatic_check(
    configured: bool,
    state: AutomaticUpdateState,
    now: i64,
) -> bool {
    automatic_check_wake_delay(configured, state, now).is_some_and(|delay| delay.is_zero())
}

/// Returns `None` when this build can permanently stop scheduling, zero when a
/// check is due now, or a bounded delay before reloading the persisted state.
pub fn automatic_check_wake_delay(
    configured: bool,
    state: AutomaticUpdateState,
    now: i64,
) -> Option<Duration> {
    if !configured {
        return None;
    }
    if !state.enabled || now < 0 {
        return Some(AUTOMATIC_CHECK_MAX_POLL_DELAY);
    }
    let Some(last_attempted_at) = state.last_attempted_at else {
        return Some(Duration::ZERO);
    };
    if last_attempted_at < 0 {
        return Some(AUTOMATIC_CHECK_MAX_POLL_DELAY);
    }
    let Some(elapsed) = now.checked_sub(last_attempted_at) else {
        return Some(AUTOMATIC_CHECK_MAX_POLL_DELAY);
    };
    if elapsed < 0 {
        return Some(AUTOMATIC_CHECK_MAX_POLL_DELAY);
    }
    if elapsed >= AUTOMATIC_CHECK_MIN_INTERVAL_MILLIS {
        return Some(Duration::ZERO);
    }
    let remaining_millis = AUTOMATIC_CHECK_MIN_INTERVAL_MILLIS - elapsed;
    Some(Duration::from_millis(remaining_millis as u64).min(AUTOMATIC_CHECK_MAX_POLL_DELAY))
}

pub async fn check_for_update<R: Runtime>(
    app: &AppHandle<R>,
) -> Result<Option<UpdateMetadata>, UpdateError> {
    ensure_configured(app)?;
    let _operation = UPDATE_OPERATION.try_lock().map_err(|_| UpdateError::Busy)?;
    let updater = secure_updater(app)?;
    let update = updater
        .check()
        .await
        .map_err(|_| UpdateError::CheckFailed)?;
    update
        .map(|release| {
            ensure_safe_https_url(&release.download_url)?;
            Ok(UpdateMetadata {
                version: release.version,
                current_version: release.current_version,
                notes: release
                    .body
                    .as_deref()
                    .map(|notes| truncate_utf8(notes, MAX_RELEASE_NOTES_BYTES)),
                published_at: release.date.map(|date| date.to_string()),
            })
        })
        .transpose()
}

/// Re-checks the update feed immediately before installation and refuses to
/// install if the available version differs from the version the user approved.
///
/// The caller controls progress delivery and should call `AppHandle::restart`
/// only after this function succeeds.
pub async fn download_and_install<R, C, D>(
    app: &AppHandle<R>,
    expected_version: &str,
    on_chunk: C,
    on_download_finish: D,
) -> Result<(), UpdateError>
where
    R: Runtime,
    C: FnMut(usize, Option<u64>),
    D: FnOnce(),
{
    ensure_configured(app)?;
    if expected_version.trim().is_empty() || expected_version.len() > 128 {
        return Err(UpdateError::VersionChanged);
    }

    let _operation = UPDATE_OPERATION.try_lock().map_err(|_| UpdateError::Busy)?;
    let updater = secure_updater(app)?;
    let update = updater
        .check()
        .await
        .map_err(|_| UpdateError::CheckFailed)?
        .ok_or(UpdateError::NoUpdate)?;
    if update.version != expected_version {
        return Err(UpdateError::VersionChanged);
    }
    ensure_safe_https_url(&update.download_url)?;
    let public_key = updater_public_key(app)?;
    let bytes = download_installer_bounded(&update, on_chunk).await?;
    verify_installer_signature(&bytes, &update.signature, &public_key)?;
    on_download_finish();
    update
        .install(bytes)
        .map_err(|_| UpdateError::InstallFailed)
}

fn secure_updater<R: Runtime>(app: &AppHandle<R>) -> Result<Updater, UpdateError> {
    app.updater_builder()
        .timeout(MANIFEST_TIMEOUT)
        .configure_client(|client| {
            client
                .connect_timeout(CONNECT_TIMEOUT)
                .redirect(secure_redirect_policy())
        })
        .build()
        .map_err(|_| UpdateError::CheckFailed)
}

fn secure_redirect_policy() -> Policy {
    Policy::custom(|attempt| {
        if attempt.previous().len() >= MAX_REDIRECTS || !is_safe_https_url(attempt.url()) {
            attempt.stop()
        } else {
            attempt.follow()
        }
    })
}

fn is_safe_https_url(url: &updater_http::Url) -> bool {
    url.scheme() == "https"
        && url.host_str().is_some()
        && url.username().is_empty()
        && url.password().is_none()
        && url.fragment().is_none()
}

fn ensure_safe_https_url(url: &updater_http::Url) -> Result<(), UpdateError> {
    if is_safe_https_url(url) {
        Ok(())
    } else {
        Err(UpdateError::CheckFailed)
    }
}

fn updater_public_key<R: Runtime>(app: &AppHandle<R>) -> Result<String, UpdateError> {
    let public_key = app
        .config()
        .plugins
        .0
        .get("updater")
        .and_then(serde_json::Value::as_object)
        .and_then(|config| config.get("pubkey"))
        .and_then(serde_json::Value::as_str)
        .ok_or(UpdateError::NotConfigured)?;
    if public_key.trim().is_empty() || public_key.len() > MAX_PUBLIC_KEY_BASE64_BYTES {
        return Err(UpdateError::NotConfigured);
    }
    Ok(public_key.to_owned())
}

async fn download_installer_bounded<C>(
    update: &tauri_plugin_updater::Update,
    mut on_chunk: C,
) -> Result<Vec<u8>, UpdateError>
where
    C: FnMut(usize, Option<u64>),
{
    let response = updater_http::Client::builder()
        .user_agent("DeskCubby-Updater/0.2")
        .connect_timeout(CONNECT_TIMEOUT)
        .timeout(DOWNLOAD_TIMEOUT)
        .redirect(secure_redirect_policy())
        .build()
        .map_err(|_| UpdateError::InstallFailed)?
        .get(update.download_url.clone())
        .headers(update.headers.clone())
        .send()
        .await
        .map_err(|_| UpdateError::InstallFailed)?;
    if !response.status().is_success() || !is_safe_https_url(response.url()) {
        return Err(UpdateError::InstallFailed);
    }
    let content_length = response.content_length();
    if content_length.is_some_and(|size| size == 0 || size > MAX_INSTALLER_BYTES as u64) {
        return Err(UpdateError::InstallFailed);
    }

    let mut response = response;
    let mut bytes = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| UpdateError::InstallFailed)?
    {
        let next_size = bytes
            .len()
            .checked_add(chunk.len())
            .ok_or(UpdateError::InstallFailed)?;
        if next_size > MAX_INSTALLER_BYTES {
            return Err(UpdateError::InstallFailed);
        }
        bytes.extend_from_slice(&chunk);
        on_chunk(chunk.len(), content_length);
    }
    if bytes.is_empty() {
        return Err(UpdateError::InstallFailed);
    }
    Ok(bytes)
}

fn verify_installer_signature(
    bytes: &[u8],
    encoded_signature: &str,
    encoded_public_key: &str,
) -> Result<(), UpdateError> {
    if encoded_signature.is_empty()
        || encoded_signature.len() > MAX_SIGNATURE_BASE64_BYTES
        || encoded_public_key.is_empty()
        || encoded_public_key.len() > MAX_PUBLIC_KEY_BASE64_BYTES
    {
        return Err(UpdateError::InstallFailed);
    }
    let public_key_text = decode_updater_base64(encoded_public_key)?;
    let signature_text = decode_updater_base64(encoded_signature)?;
    let public_key = PublicKey::decode(&public_key_text).map_err(|_| UpdateError::InstallFailed)?;
    let signature = Signature::decode(&signature_text).map_err(|_| UpdateError::InstallFailed)?;
    public_key
        .verify(bytes, &signature, false)
        .map_err(|_| UpdateError::InstallFailed)
}

fn decode_updater_base64(value: &str) -> Result<String, UpdateError> {
    let bytes = STANDARD
        .decode(value.trim())
        .map_err(|_| UpdateError::InstallFailed)?;
    String::from_utf8(bytes).map_err(|_| UpdateError::InstallFailed)
}

fn ensure_configured<R: Runtime>(app: &AppHandle<R>) -> Result<(), UpdateError> {
    if is_configured(app) {
        Ok(())
    } else {
        Err(UpdateError::NotConfigured)
    }
}

fn valid_updater_config(value: &serde_json::Value) -> bool {
    let Some(config) = value.as_object() else {
        return false;
    };
    let Some(public_key) = config.get("pubkey").and_then(serde_json::Value::as_str) else {
        return false;
    };
    if public_key.trim().is_empty() || public_key.len() > 16 * 1024 {
        return false;
    }
    for dangerous_option in [
        "dangerousInsecureTransportProtocol",
        "dangerousAcceptInvalidCerts",
        "dangerousAcceptInvalidHostnames",
    ] {
        if config
            .get(dangerous_option)
            .is_some_and(|value| value.as_bool() != Some(false))
        {
            return false;
        }
    }

    let Some(endpoints) = config
        .get("endpoints")
        .and_then(serde_json::Value::as_array)
    else {
        return false;
    };
    !endpoints.is_empty()
        && endpoints.len() <= 4
        && endpoints.iter().all(|endpoint| {
            endpoint
                .as_str()
                .and_then(|raw| updater_http::Url::parse(raw).ok())
                .is_some_and(|url| is_safe_https_url(&url))
        })
}

fn truncate_utf8(value: &str, max_bytes: usize) -> String {
    if value.len() <= max_bytes {
        return value.to_owned();
    }
    let mut boundary = max_bytes;
    while boundary > 0 && !value.is_char_boundary(boundary) {
        boundary -= 1;
    }
    value[..boundary].to_owned()
}

fn unix_time_millis() -> Option<i64> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .and_then(|duration| i64::try_from(duration.as_millis()).ok())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn configuration_requires_public_key_and_https_endpoint() {
        assert!(!valid_updater_config(&json!({
            "pubkey": "",
            "endpoints": []
        })));
        assert!(!valid_updater_config(&json!({})));
        assert!(!valid_updater_config(&serde_json::Value::Null));
        assert!(valid_updater_config(&json!({
            "pubkey": "untrusted comment: test\nRWQtest",
            "endpoints": [
                "https://github.com/vexpaer/DeskCubby/releases/latest/download/latest.json"
            ]
        })));
        assert!(!valid_updater_config(&json!({
            "pubkey": "",
            "endpoints": ["https://example.invalid/latest.json"]
        })));
        assert!(!valid_updater_config(&json!({
            "pubkey": "test",
            "endpoints": ["http://example.invalid/latest.json"]
        })));
        assert!(!valid_updater_config(&json!({
            "pubkey": "test",
            "endpoints": ["https://user:secret@example.invalid/latest.json"]
        })));
        for dangerous_option in [
            "dangerousInsecureTransportProtocol",
            "dangerousAcceptInvalidCerts",
            "dangerousAcceptInvalidHostnames",
        ] {
            let mut value = json!({
                "pubkey": "test",
                "endpoints": ["https://example.invalid/latest.json"]
            });
            value[dangerous_option] = serde_json::Value::Bool(true);
            assert!(!valid_updater_config(&value));
        }
    }

    #[test]
    fn updater_urls_require_credential_free_https() {
        assert!(is_safe_https_url(
            &updater_http::Url::parse("https://example.invalid/update.exe?token=1")
                .expect("HTTPS URL")
        ));
        for raw in [
            "http://example.invalid/update.exe",
            "https://user:secret@example.invalid/update.exe",
            "https://example.invalid/update.exe#fragment",
        ] {
            assert!(!is_safe_https_url(
                &updater_http::Url::parse(raw).expect("URL")
            ));
        }
    }

    #[test]
    fn checked_in_configuration_is_deserializable_but_offline() {
        let value = json!({
            "pubkey": "",
            "endpoints": []
        });
        assert!(
            serde_json::from_value::<tauri_plugin_updater::Config>(value.clone()).is_ok(),
            "the updater plugin must be able to initialize from the base config"
        );
        assert!(!valid_updater_config(&value));
    }

    #[test]
    fn automatic_check_requires_configuration_preference_and_elapsed_interval() {
        let first_run = AutomaticUpdateState {
            enabled: true,
            last_attempted_at: None,
        };
        assert!(should_attempt_automatic_check(true, first_run, 100));
        assert!(!should_attempt_automatic_check(false, first_run, 100));
        assert!(!should_attempt_automatic_check(
            true,
            AutomaticUpdateState {
                enabled: false,
                last_attempted_at: None,
            },
            100
        ));

        let last_attempted_at = 1_000;
        let state = AutomaticUpdateState {
            enabled: true,
            last_attempted_at: Some(last_attempted_at),
        };
        assert!(!should_attempt_automatic_check(
            true,
            state,
            last_attempted_at + AUTOMATIC_CHECK_MIN_INTERVAL_MILLIS - 1
        ));
        assert!(should_attempt_automatic_check(
            true,
            state,
            last_attempted_at + AUTOMATIC_CHECK_MIN_INTERVAL_MILLIS
        ));
        assert!(!should_attempt_automatic_check(
            true,
            state,
            last_attempted_at - 1
        ));
    }

    #[test]
    fn scheduler_wake_delay_is_due_aware_and_bounded() {
        let disabled = AutomaticUpdateState {
            enabled: false,
            last_attempted_at: None,
        };
        assert_eq!(
            automatic_check_wake_delay(true, disabled, 1_000),
            Some(AUTOMATIC_CHECK_MAX_POLL_DELAY)
        );
        assert_eq!(automatic_check_wake_delay(false, disabled, 1_000), None);

        let half_hour_millis = 30 * 60 * 1_000;
        let state = AutomaticUpdateState {
            enabled: true,
            last_attempted_at: Some(1_000),
        };
        assert_eq!(
            automatic_check_wake_delay(
                true,
                state,
                1_000 + AUTOMATIC_CHECK_MIN_INTERVAL_MILLIS - half_hour_millis
            ),
            Some(Duration::from_millis(half_hour_millis as u64))
        );
        assert_eq!(
            automatic_check_wake_delay(true, state, 1_001),
            Some(AUTOMATIC_CHECK_MAX_POLL_DELAY)
        );
        assert_eq!(
            automatic_check_wake_delay(
                true,
                AutomaticUpdateState {
                    enabled: true,
                    last_attempted_at: Some(i64::MAX),
                },
                0
            ),
            Some(AUTOMATIC_CHECK_MAX_POLL_DELAY)
        );
        assert_eq!(
            automatic_check_wake_delay(true, state, 1_000 + AUTOMATIC_CHECK_MIN_INTERVAL_MILLIS),
            Some(Duration::ZERO)
        );
    }

    #[test]
    fn available_event_matches_the_frontend_v1_contract() {
        let event = UpdateAvailableEventV1::from(UpdateMetadata {
            version: "0.3.0".to_owned(),
            current_version: "0.2.0".to_owned(),
            notes: Some("Release notes".to_owned()),
            published_at: Some("2026-07-29T00:00:00Z".to_owned()),
        });
        assert_eq!(
            serde_json::to_value(event).expect("serialize updater event"),
            json!({
                "schemaVersion": 1,
                "currentVersion": "0.2.0",
                "version": "0.3.0",
                "notes": "Release notes",
                "publishedAt": "2026-07-29T00:00:00Z"
            })
        );
    }

    #[test]
    fn release_notes_are_truncated_on_a_utf8_boundary() {
        let input = format!("{}界", "a".repeat(7));
        assert_eq!(truncate_utf8(&input, 8), "aaaaaaa");
        assert_eq!(truncate_utf8(&input, input.len()), input);
    }

    #[test]
    fn errors_have_stable_safe_metadata() {
        assert_eq!(UpdateError::NotConfigured.code(), "update_not_configured");
        assert!(!UpdateError::NotConfigured.retryable());
        assert!(UpdateError::CheckFailed.retryable());
        assert!(!UpdateError::InstallFailed.message().contains("http"));
    }
}
