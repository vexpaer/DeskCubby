use chrono::{DateTime, Local};
use reqwest::Client;
use reqwest::header::{ACCEPT, HeaderMap, HeaderValue, USER_AGENT};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use thiserror::Error;
use tokio::sync::Mutex;
use uuid::Uuid;

const TOKEN_URL: &str = "https://v2.jinrishici.com/token";
const SENTENCE_URL: &str = "https://v2.jinrishici.com/sentence";
const MAX_RESPONSE_BYTES: usize = 256 * 1024;
const MAX_CACHE_BYTES: u64 = 512 * 1024;
const MAX_SENTENCE_CHARS: usize = 4_000;
const MAX_FULL_CONTENT_CHARS: usize = 32_000;
const MAX_SOURCE_CHARS: usize = 2_000;
const CACHE_VERSION: u32 = 1;

static REFRESH_MUTEX: Mutex<()> = Mutex::const_new(());

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DailyPoem {
    pub content: String,
    pub source: String,
    pub updated_at: i64,
    #[serde(default)]
    pub full_content: String,
    #[serde(default)]
    pub dynasty: String,
    #[serde(default)]
    pub title: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DailyPoemSource {
    Live,
    Cache,
    Fallback,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DailyPoemResult {
    pub poem: DailyPoem,
    pub source: DailyPoemSource,
    #[serde(default)]
    pub warning_code: Option<String>,
}

#[allow(dead_code)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PoemEditContentStatus {
    StoredContent,
    ExpandedFromDailyCache,
    LegacyCacheWithoutFullContent,
    CachedFullContentTooLong,
}

#[allow(dead_code)]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PoemEditContentResolution {
    pub content: String,
    pub status: PoemEditContentStatus,
}

#[derive(Debug, Clone, Error, Serialize, Deserialize)]
#[error("{code}: {message}")]
#[serde(rename_all = "camelCase")]
pub struct PoetryError {
    pub code: String,
    pub message: String,
}

impl PoetryError {
    fn new(code: &str, message: impl Into<String>) -> Self {
        Self {
            code: code.to_owned(),
            message: message.into(),
        }
    }

    fn io(operation: &str, error: io::Error) -> Self {
        Self::new("POETRY_CACHE_IO_ERROR", format!("{operation}: {error}"))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PoetryCache {
    version: u32,
    #[serde(default)]
    token: String,
    poem: DailyPoem,
}

/// Returns today's poem, using the private cache and then the built-in poem when
/// the HTTPS API cannot be reached.
///
/// The returned warning is a stable code only. Network response bodies and the
/// Jinrishici token never cross the IPC boundary.
pub async fn get_daily_poem(
    cache_path: &Path,
    force: bool,
) -> Result<DailyPoemResult, PoetryError> {
    let _guard = REFRESH_MUTEX.lock().await;
    let cached = read_cache(cache_path).ok().flatten();
    if !force
        && cached
            .as_ref()
            .is_some_and(|cache| is_today(cache.poem.updated_at))
    {
        return Ok(DailyPoemResult {
            poem: cached.expect("checked above").poem,
            source: DailyPoemSource::Cache,
            warning_code: None,
        });
    }

    let live = refresh_live(cached.as_ref().map(|cache| cache.token.as_str())).await;
    match live {
        Ok((mut poem, token)) => {
            poem.updated_at = now_millis();
            let cache = PoetryCache {
                version: CACHE_VERSION,
                token,
                poem: poem.clone(),
            };
            let warning_code = write_cache(cache_path, &cache)
                .err()
                .map(|_| "POETRY_CACHE_WRITE_FAILED".to_owned());
            Ok(DailyPoemResult {
                poem,
                source: DailyPoemSource::Live,
                warning_code,
            })
        }
        Err(error) => {
            let warning_code = error.code;
            Ok(cached
                .map(|cache| DailyPoemResult {
                    poem: cache.poem,
                    source: DailyPoemSource::Cache,
                    warning_code: Some(warning_code.clone()),
                })
                .unwrap_or_else(|| DailyPoemResult {
                    poem: fallback_poem(),
                    source: DailyPoemSource::Fallback,
                    warning_code: Some(warning_code),
                }))
        }
    }
}

pub fn load_cached_or_fallback(cache_path: &Path) -> DailyPoemResult {
    read_cache(cache_path)
        .ok()
        .flatten()
        .map(|cache| DailyPoemResult {
            poem: cache.poem,
            source: DailyPoemSource::Cache,
            warning_code: None,
        })
        .unwrap_or_else(|| DailyPoemResult {
            poem: fallback_poem(),
            source: DailyPoemSource::Fallback,
            warning_code: None,
        })
}

#[allow(dead_code)]
pub fn resolve_saved_content_for_edit(
    stored_content: &str,
    stored_source: &str,
    cached: &DailyPoem,
) -> PoemEditContentResolution {
    let stored_match = normalized_poem_text(stored_content);
    let cached_sentence = normalized_poem_text(&cached.content);
    let same_source =
        normalized_poem_source(stored_source) == normalized_poem_source(&cached.source);
    if !same_source || stored_match.is_empty() || stored_match != cached_sentence {
        return PoemEditContentResolution {
            content: stored_content.to_owned(),
            status: PoemEditContentStatus::StoredContent,
        };
    }
    let full_content = cached.full_content.trim();
    if full_content.is_empty() {
        return PoemEditContentResolution {
            content: stored_content.to_owned(),
            status: PoemEditContentStatus::LegacyCacheWithoutFullContent,
        };
    }
    if full_content.chars().count() > MAX_SENTENCE_CHARS {
        return PoemEditContentResolution {
            content: stored_content.to_owned(),
            status: PoemEditContentStatus::CachedFullContentTooLong,
        };
    }
    let normalized_full = normalized_poem_text(full_content);
    if !normalized_full.contains(&stored_match) || normalized_full == stored_match {
        return PoemEditContentResolution {
            content: stored_content.to_owned(),
            status: PoemEditContentStatus::StoredContent,
        };
    }
    PoemEditContentResolution {
        content: full_content.to_owned(),
        status: PoemEditContentStatus::ExpandedFromDailyCache,
    }
}

pub fn fallback_poem() -> DailyPoem {
    DailyPoem {
        content: "山中何事？松花酿酒，春水煎茶。".to_owned(),
        source: "— 张可久《人月圆·山中书事》".to_owned(),
        updated_at: 0,
        full_content: concat!(
            "兴亡千古繁华梦，诗眼倦天涯。\n",
            "孔林乔木，吴宫蔓草，楚庙寒鸦。\n",
            "数间茅舍，藏书万卷，投老村家。\n",
            "山中何事？松花酿酒，春水煎茶。"
        )
        .to_owned(),
        dynasty: "元".to_owned(),
        title: "人月圆·山中书事".to_owned(),
    }
}

async fn refresh_live(existing_token: Option<&str>) -> Result<(DailyPoem, String), PoetryError> {
    let client = build_client()?;
    let mut token = match existing_token.filter(|value| !value.is_empty()) {
        Some(value) => value.to_owned(),
        None => fetch_token(&client).await?,
    };
    match fetch_sentence(&client, &token).await {
        Ok(result) => Ok(result),
        Err(first_error) if existing_token.is_some() => {
            token = fetch_token(&client).await.map_err(|_| first_error)?;
            fetch_sentence(&client, &token).await
        }
        Err(error) => Err(error),
    }
}

fn build_client() -> Result<Client, PoetryError> {
    let mut headers = HeaderMap::new();
    headers.insert(ACCEPT, HeaderValue::from_static("application/json"));
    headers.insert(
        USER_AGENT,
        HeaderValue::from_static("DeskCubby-Windows/0.1"),
    );
    Client::builder()
        .default_headers(headers)
        .connect_timeout(Duration::from_secs(6))
        .timeout(Duration::from_secs(10))
        .redirect(reqwest::redirect::Policy::none())
        .https_only(true)
        .build()
        .map_err(|_| PoetryError::new("POETRY_CLIENT_FAILED", "Unable to initialize HTTPS client."))
}

async fn fetch_token(client: &Client) -> Result<String, PoetryError> {
    let root = bounded_json_get(client, TOKEN_URL, None).await?;
    require_success(&root)?;
    let token = root
        .get("data")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty() && value.len() <= 1_024)
        .ok_or_else(|| {
            PoetryError::new(
                "POETRY_RESPONSE_INVALID",
                "Poetry token response is malformed.",
            )
        })?;
    Ok(token.to_owned())
}

async fn fetch_sentence(client: &Client, token: &str) -> Result<(DailyPoem, String), PoetryError> {
    let root = bounded_json_get(client, SENTENCE_URL, Some(token)).await?;
    require_success(&root)?;
    let poem = parse_sentence(&root)?;
    let returned_token = root
        .get("token")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty() && value.len() <= 1_024)
        .unwrap_or(token)
        .to_owned();
    Ok((poem, returned_token))
}

async fn bounded_json_get(
    client: &Client,
    url: &'static str,
    token: Option<&str>,
) -> Result<Value, PoetryError> {
    let mut request = client.get(url);
    if let Some(token) = token {
        request = request.header("X-User-Token", token);
    }
    let mut response = request.send().await.map_err(|_| {
        PoetryError::new(
            "POETRY_NETWORK_FAILED",
            "The daily-poetry service could not be reached.",
        )
    })?;
    if response.url().as_str() != url {
        return Err(PoetryError::new(
            "POETRY_REDIRECT_BLOCKED",
            "Poetry service redirects are not allowed.",
        ));
    }
    if !response.status().is_success() {
        return Err(PoetryError::new(
            "POETRY_HTTP_FAILED",
            format!(
                "Poetry service returned HTTP {}.",
                response.status().as_u16()
            ),
        ));
    }
    if response
        .content_length()
        .is_some_and(|length| length > MAX_RESPONSE_BYTES as u64)
    {
        return Err(PoetryError::new(
            "POETRY_RESPONSE_TOO_LARGE",
            "Poetry response exceeded its size limit.",
        ));
    }
    let mut body = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|_| {
        PoetryError::new(
            "POETRY_NETWORK_FAILED",
            "Poetry response ended unexpectedly.",
        )
    })? {
        if body.len().saturating_add(chunk.len()) > MAX_RESPONSE_BYTES {
            return Err(PoetryError::new(
                "POETRY_RESPONSE_TOO_LARGE",
                "Poetry response exceeded its size limit.",
            ));
        }
        body.extend_from_slice(&chunk);
    }
    serde_json::from_slice(&body).map_err(|_| {
        PoetryError::new(
            "POETRY_RESPONSE_INVALID",
            "Poetry service returned invalid JSON.",
        )
    })
}

fn require_success(root: &Value) -> Result<(), PoetryError> {
    if root.get("status").and_then(Value::as_str) == Some("success") {
        Ok(())
    } else {
        Err(PoetryError::new(
            "POETRY_SERVICE_REJECTED",
            "The daily-poetry service rejected the request.",
        ))
    }
}

fn parse_sentence(root: &Value) -> Result<DailyPoem, PoetryError> {
    let data = root
        .get("data")
        .and_then(Value::as_object)
        .ok_or_else(response_invalid)?;
    let content = bounded_string(data.get("content"), MAX_SENTENCE_CHARS, false)?;
    let origin = data.get("origin").and_then(Value::as_object);
    let title = bounded_optional_string(
        origin.and_then(|value| value.get("title")),
        MAX_SOURCE_CHARS,
    )?;
    let author = bounded_optional_string(
        origin.and_then(|value| value.get("author")),
        MAX_SOURCE_CHARS,
    )?;
    let dynasty = bounded_optional_string(
        origin.and_then(|value| value.get("dynasty")),
        MAX_SOURCE_CHARS,
    )?;
    let full_content = match origin
        .and_then(|value| value.get("content"))
        .and_then(Value::as_array)
    {
        Some(lines) => {
            if lines.len() > 500 {
                return Err(response_invalid());
            }
            let mut normalized = Vec::new();
            let mut character_count = 0_usize;
            for line in lines {
                let line = line.as_str().ok_or_else(response_invalid)?.trim();
                if line.is_empty() {
                    continue;
                }
                character_count = character_count.saturating_add(line.chars().count());
                if character_count > MAX_FULL_CONTENT_CHARS {
                    return Err(response_invalid());
                }
                normalized.push(line);
            }
            normalized.join("\n")
        }
        None => String::new(),
    };
    let source = format_source(&title, &author);
    if source.chars().count() > MAX_SOURCE_CHARS {
        return Err(response_invalid());
    }
    Ok(DailyPoem {
        content,
        source,
        updated_at: 0,
        full_content,
        dynasty,
        title,
    })
}

fn bounded_string(
    value: Option<&Value>,
    max_chars: usize,
    allow_empty: bool,
) -> Result<String, PoetryError> {
    let value = value
        .and_then(Value::as_str)
        .map(str::trim)
        .ok_or_else(response_invalid)?;
    if (!allow_empty && value.is_empty()) || value.chars().count() > max_chars {
        return Err(response_invalid());
    }
    Ok(value.to_owned())
}

fn bounded_optional_string(value: Option<&Value>, max_chars: usize) -> Result<String, PoetryError> {
    match value {
        Some(Value::String(value)) if value.chars().count() <= max_chars => {
            Ok(value.trim().to_owned())
        }
        None | Some(Value::Null) => Ok(String::new()),
        _ => Err(response_invalid()),
    }
}

fn response_invalid() -> PoetryError {
    PoetryError::new(
        "POETRY_RESPONSE_INVALID",
        "Poetry service response is malformed or exceeds field limits.",
    )
}

fn format_source(title: &str, author: &str) -> String {
    match (title.is_empty(), author.is_empty()) {
        (false, false) => format!("— {author}《{title}》"),
        (true, false) => format!("— {author}"),
        (false, true) => format!("— 《{title}》"),
        (true, true) => "— 今日诗词".to_owned(),
    }
}

#[allow(dead_code)]
fn normalized_poem_text(value: &str) -> String {
    value
        .chars()
        .filter(|character| !character.is_whitespace())
        .collect()
}

#[allow(dead_code)]
fn normalized_poem_source(value: &str) -> String {
    value
        .trim()
        .trim_start_matches(['-', '–', '—'])
        .trim()
        .to_owned()
}

fn read_cache(path: &Path) -> Result<Option<PoetryCache>, PoetryError> {
    recover_cache_if_needed(path)?;
    if !path.exists() {
        return Ok(None);
    }
    let metadata =
        fs::symlink_metadata(path).map_err(|error| PoetryError::io("read poetry cache", error))?;
    if !metadata.is_file() || is_reparse_point(&metadata) {
        return Err(PoetryError::new(
            "POETRY_CACHE_PATH_INVALID",
            "Poetry cache must be a regular private file.",
        ));
    }
    let bytes = read_bounded(path, MAX_CACHE_BYTES)?;
    let cache: PoetryCache = serde_json::from_slice(&bytes)
        .map_err(|_| PoetryError::new("POETRY_CACHE_INVALID", "Poetry cache is malformed."))?;
    if cache.version != CACHE_VERSION
        || cache.token.len() > 1_024
        || validate_cached_poem(&cache.poem).is_err()
    {
        return Err(PoetryError::new(
            "POETRY_CACHE_INVALID",
            "Poetry cache failed validation.",
        ));
    }
    Ok(Some(cache))
}

fn validate_cached_poem(poem: &DailyPoem) -> Result<(), PoetryError> {
    if poem.content.trim().is_empty()
        || poem.content.chars().count() > MAX_SENTENCE_CHARS
        || poem.full_content.chars().count() > MAX_FULL_CONTENT_CHARS
        || poem.source.chars().count() > MAX_SOURCE_CHARS
        || poem.dynasty.chars().count() > MAX_SOURCE_CHARS
        || poem.title.chars().count() > MAX_SOURCE_CHARS
    {
        return Err(PoetryError::new(
            "POETRY_CACHE_INVALID",
            "Cached poem exceeds field limits.",
        ));
    }
    Ok(())
}

fn write_cache(path: &Path, cache: &PoetryCache) -> Result<(), PoetryError> {
    validate_cached_poem(&cache.poem)?;
    let parent = path.parent().ok_or_else(|| {
        PoetryError::new("POETRY_CACHE_PATH_INVALID", "Poetry cache has no parent.")
    })?;
    if !parent.exists() {
        fs::create_dir_all(parent)
            .map_err(|error| PoetryError::io("create poetry cache directory", error))?;
    }
    let parent_metadata = fs::symlink_metadata(parent)
        .map_err(|error| PoetryError::io("read poetry cache directory", error))?;
    if !parent_metadata.is_dir() || is_reparse_point(&parent_metadata) {
        return Err(PoetryError::new(
            "POETRY_CACHE_PATH_INVALID",
            "Poetry cache directory is not a regular private directory.",
        ));
    }
    let encoded = serde_json::to_vec_pretty(cache)
        .map_err(|_| PoetryError::new("POETRY_CACHE_INVALID", "Cannot encode poetry cache."))?;
    if encoded.len() as u64 > MAX_CACHE_BYTES {
        return Err(PoetryError::new(
            "POETRY_CACHE_TOO_LARGE",
            "Poetry cache exceeds its size limit.",
        ));
    }
    atomic_write_replace(path, &encoded)?;
    let decoded: PoetryCache = serde_json::from_slice(&read_bounded(path, MAX_CACHE_BYTES)?)
        .map_err(|_| {
            PoetryError::new(
                "POETRY_CACHE_VERIFY_FAILED",
                "Poetry cache could not be read after writing.",
            )
        })?;
    if decoded.version != cache.version
        || decoded.token != cache.token
        || decoded.poem != cache.poem
    {
        return Err(PoetryError::new(
            "POETRY_CACHE_VERIFY_FAILED",
            "Poetry cache changed during write-back verification.",
        ));
    }
    Ok(())
}

fn read_bounded(path: &Path, limit: u64) -> Result<Vec<u8>, PoetryError> {
    let metadata =
        fs::metadata(path).map_err(|error| PoetryError::io("read cache metadata", error))?;
    if metadata.len() > limit {
        return Err(PoetryError::new(
            "POETRY_CACHE_TOO_LARGE",
            "Poetry cache exceeds its size limit.",
        ));
    }
    let file = File::open(path).map_err(|error| PoetryError::io("open poetry cache", error))?;
    let mut bytes = Vec::with_capacity(metadata.len() as usize);
    file.take(limit + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| PoetryError::io("read poetry cache", error))?;
    if bytes.len() as u64 > limit {
        return Err(PoetryError::new(
            "POETRY_CACHE_TOO_LARGE",
            "Poetry cache exceeds its size limit.",
        ));
    }
    Ok(bytes)
}

fn atomic_write_replace(path: &Path, bytes: &[u8]) -> Result<(), PoetryError> {
    recover_cache_if_needed(path)?;
    let temp = sibling_path(path, "pending")?;
    let recovery = sibling_path(path, "recovery")?;
    let mut output = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&temp)
        .map_err(|error| PoetryError::io("create poetry cache temporary file", error))?;
    if let Err(error) = output.write_all(bytes).and_then(|()| output.sync_all()) {
        let _ = fs::remove_file(&temp);
        return Err(PoetryError::io("write poetry cache temporary file", error));
    }
    drop(output);
    let temp_hash = Sha256::digest(read_bounded(&temp, MAX_CACHE_BYTES)?);
    if temp_hash.as_slice() != Sha256::digest(bytes).as_slice() {
        let _ = fs::remove_file(&temp);
        return Err(PoetryError::new(
            "POETRY_CACHE_VERIFY_FAILED",
            "Poetry cache temporary file did not match.",
        ));
    }
    if path.exists() {
        if recovery.exists() {
            fs::remove_file(&recovery)
                .map_err(|error| PoetryError::io("remove stale poetry recovery file", error))?;
        }
        fs::rename(path, &recovery)
            .map_err(|error| PoetryError::io("stage old poetry cache", error))?;
    }
    if let Err(error) = fs::rename(&temp, path) {
        if recovery.exists() {
            let _ = fs::rename(&recovery, path);
        }
        let _ = fs::remove_file(&temp);
        return Err(PoetryError::io("commit poetry cache", error));
    }
    if recovery.exists() {
        let _ = fs::remove_file(recovery);
    }
    Ok(())
}

fn recover_cache_if_needed(path: &Path) -> Result<(), PoetryError> {
    let recovery = sibling_path(path, "recovery")?;
    if recovery.exists() {
        if path.exists() {
            let _ = fs::remove_file(recovery);
        } else {
            fs::rename(recovery, path).map_err(|error| {
                PoetryError::io("restore interrupted poetry cache write", error)
            })?;
        }
    }
    Ok(())
}

fn sibling_path(path: &Path, kind: &str) -> Result<PathBuf, PoetryError> {
    let parent = path.parent().ok_or_else(|| {
        PoetryError::new("POETRY_CACHE_PATH_INVALID", "Poetry cache has no parent.")
    })?;
    let name = path
        .file_name()
        .and_then(|value| value.to_str())
        .ok_or_else(|| {
            PoetryError::new(
                "POETRY_CACHE_PATH_INVALID",
                "Poetry cache name is not valid UTF-8.",
            )
        })?;
    Ok(if kind == "recovery" {
        parent.join(format!(".{name}.dc-recovery"))
    } else {
        parent.join(format!(".{name}.dc-{kind}-{}", Uuid::new_v4()))
    })
}

fn is_today(epoch_millis: i64) -> bool {
    DateTime::<chrono::Utc>::from_timestamp_millis(epoch_millis)
        .map(|value| value.with_timezone(&Local).date_naive() == Local::now().date_naive())
        .unwrap_or(false)
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
    fn parses_full_poem_and_formats_source() {
        let value = serde_json::json!({
            "status": "success",
            "data": {
                "content": "松花酿酒，春水煎茶。",
                "origin": {
                    "title": "人月圆·山中书事",
                    "author": "张可久",
                    "dynasty": "元",
                    "content": ["数间茅舍，藏书万卷。", "松花酿酒，春水煎茶。"]
                }
            }
        });
        let poem = parse_sentence(&value).expect("parse poem");
        assert_eq!(poem.source, "— 张可久《人月圆·山中书事》");
        assert_eq!(poem.full_content.lines().count(), 2);
    }

    #[test]
    fn cache_round_trip_and_built_in_fallback() {
        let root = tempdir().expect("temporary directory");
        let path = root.path().join("poetry.json");
        assert_eq!(
            load_cached_or_fallback(&path).source,
            DailyPoemSource::Fallback
        );
        let mut poem = fallback_poem();
        poem.updated_at = now_millis();
        write_cache(
            &path,
            &PoetryCache {
                version: CACHE_VERSION,
                token: "private-token".to_owned(),
                poem: poem.clone(),
            },
        )
        .expect("write cache");
        let loaded = load_cached_or_fallback(&path);
        assert_eq!(loaded.source, DailyPoemSource::Cache);
        assert_eq!(loaded.poem, poem);
    }

    #[test]
    fn only_expands_when_source_and_sentence_match() {
        let cached = DailyPoem {
            content: "松花酿酒，春水煎茶。".to_owned(),
            source: "— 张可久《人月圆·山中书事》".to_owned(),
            full_content: "数间茅舍。\n松花酿酒，春水煎茶。".to_owned(),
            ..fallback_poem()
        };
        let expanded = resolve_saved_content_for_edit(
            "松花酿酒，春水煎茶。",
            "张可久《人月圆·山中书事》",
            &cached,
        );
        assert_eq!(
            expanded.status,
            PoemEditContentStatus::ExpandedFromDailyCache
        );
        let unrelated = resolve_saved_content_for_edit("别的诗句", &cached.source, &cached);
        assert_eq!(unrelated.status, PoemEditContentStatus::StoredContent);
    }

    #[test]
    fn rejects_oversized_response_fields() {
        let value = serde_json::json!({
            "status": "success",
            "data": {"content": "x".repeat(MAX_SENTENCE_CHARS + 1)}
        });
        let error = parse_sentence(&value).expect_err("must reject oversized content");
        assert_eq!(error.code, "POETRY_RESPONSE_INVALID");
    }
}
