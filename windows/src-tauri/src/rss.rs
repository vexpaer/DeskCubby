//! RSS 2.0 / Atom support for the Windows client.
//!
//! Feed bytes and parsed articles are deliberately process-memory only. The
//! only persistent values are the Android-compatible subscription and display
//! settings stored in `ManagedSettings` by the command adapter at the bottom
//! of this module.

use crate::AppState;
use crate::commands::SETTINGS_UPDATE_MUTEX;
use crate::db;
use crate::models::RssSubscription;
use crate::security::{CommandResult, SecurityErrorDto};
use chrono::DateTime;
use quick_xml::Reader;
use quick_xml::events::{BytesRef, BytesStart, Event};
use reqwest::header::{ACCEPT, ACCEPT_ENCODING, CONTENT_LENGTH, LOCATION, USER_AGENT};
use reqwest::{Client, StatusCode, Url};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::io::Cursor;
use std::net::{IpAddr, SocketAddr, ToSocketAddrs};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;
use tauri::State;
use tokio::sync::Semaphore;
use tokio::task::JoinSet;
use uuid::Uuid;

const RSS_DTO_VERSION: u32 = 1;
const MAX_SUBSCRIPTIONS: usize = 100;
const MAX_FEED_URL_CHARS: usize = 8_192;
const MAX_ARTICLE_URL_CHARS: usize = 8_192;
const MAX_TITLE_CHARS: usize = 4_096;
const MAX_SUBSCRIPTION_TITLE_CHARS: usize = 120;
const MAX_SUMMARY_CHARS: usize = 8_192;
const MAX_FEED_BYTES: usize = 5 * 1024 * 1024;
const MAX_ITEMS_PER_FEED: usize = 200;
const MIN_ITEMS_PER_FEED: usize = 10;
const MAX_TOTAL_CACHED_ARTICLES: usize = 2_000;
const MAX_XML_DEPTH: usize = 64;
const MAX_REDIRECTS: usize = 5;
const MAX_PARALLEL_FEEDS: usize = 4;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(12);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(20);
const TOTAL_FEED_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RssArticleDto {
    pub id: String,
    pub feed_id: String,
    pub feed_title: String,
    pub title: String,
    pub url_available: bool,
    pub summary: String,
    pub published_at_ms: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RssFeedErrorDto {
    pub feed_id: String,
    pub code: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RssPageDto {
    pub dto_version: u32,
    pub subscriptions: Vec<RssSubscription>,
    pub max_items_per_feed: usize,
    pub show_summaries: bool,
    pub articles: Vec<RssArticleDto>,
    pub errors: Vec<RssFeedErrorDto>,
    pub last_updated_at_ms: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SaveRssSubscriptionRequest {
    pub dto_version: u32,
    #[serde(default)]
    pub id: Option<String>,
    #[serde(default)]
    pub title: String,
    pub url: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RssSubscriptionStateRequest {
    pub dto_version: u32,
    pub id: String,
    pub enabled: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RssSubscriptionIdRequest {
    pub dto_version: u32,
    pub id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RssPreferencesRequest {
    pub dto_version: u32,
    pub max_items_per_feed: usize,
    pub show_summaries: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct OpenRssArticleRequest {
    pub dto_version: u32,
    pub article_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct RssArticle {
    id: String,
    feed_id: String,
    feed_title: String,
    title: String,
    url: Option<String>,
    summary: String,
    published_at_ms: Option<i64>,
}

impl RssArticle {
    fn dto(&self) -> RssArticleDto {
        RssArticleDto {
            id: self.id.clone(),
            feed_id: self.feed_id.clone(),
            feed_title: self.feed_title.clone(),
            title: self.title.clone(),
            url_available: self.url.is_some(),
            summary: self.summary.clone(),
            published_at_ms: self.published_at_ms.map(|value| value.to_string()),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RssError {
    InvalidUrl,
    HttpsRequired,
    PrivateHost,
    DuplicateFeed,
    LimitExceeded,
    DnsFailed,
    TimedOut,
    RedirectNotAllowed,
    HttpFailed,
    TooLarge,
    DoctypeForbidden,
    UnsupportedFormat,
    ParseFailed,
    NetworkFailed,
    NotFound,
    OpenFailed,
    Storage,
}

impl RssError {
    fn code(self) -> &'static str {
        match self {
            Self::InvalidUrl => "rss_invalid_url",
            Self::HttpsRequired => "rss_https_required",
            Self::PrivateHost => "rss_private_host",
            Self::DuplicateFeed => "rss_duplicate_feed",
            Self::LimitExceeded => "rss_limit_exceeded",
            Self::DnsFailed => "rss_dns_failed",
            Self::TimedOut => "rss_timed_out",
            Self::RedirectNotAllowed => "rss_redirect_not_allowed",
            Self::HttpFailed => "rss_http_failed",
            Self::TooLarge => "rss_too_large",
            Self::DoctypeForbidden => "rss_doctype_forbidden",
            Self::UnsupportedFormat => "rss_unsupported_format",
            Self::ParseFailed => "rss_parse_failed",
            Self::NetworkFailed => "rss_network_failed",
            Self::NotFound => "rss_not_found",
            Self::OpenFailed => "rss_open_failed",
            Self::Storage => "rss_storage_unavailable",
        }
    }

    fn command(self) -> SecurityErrorDto {
        let message = match self {
            Self::InvalidUrl => "The RSS address is not valid.",
            Self::HttpsRequired => "RSS feeds require HTTPS.",
            Self::PrivateHost => "RSS feeds must use a public HTTPS host.",
            Self::DuplicateFeed => "This RSS feed has already been added.",
            Self::LimitExceeded => "The RSS safety limit was exceeded.",
            Self::NotFound => "The RSS item no longer exists.",
            Self::OpenFailed => "The RSS article could not be opened.",
            Self::Storage => "RSS settings are temporarily unavailable.",
            _ => "The RSS operation could not be completed.",
        };
        SecurityErrorDto::new(self.code(), message, true)
    }
}

#[derive(Default)]
struct CacheState {
    articles_by_feed: HashMap<String, Vec<RssArticle>>,
    feed_urls: HashMap<String, String>,
    errors: HashMap<String, RssError>,
    last_updated_at_ms: Option<i64>,
}

pub struct RssService {
    cache: Mutex<CacheState>,
    mutation: Mutex<()>,
    refresh: tokio::sync::Mutex<()>,
}

impl Default for RssService {
    fn default() -> Self {
        Self::new()
    }
}

impl RssService {
    pub fn new() -> Self {
        Self {
            cache: Mutex::new(CacheState::default()),
            mutation: Mutex::new(()),
            refresh: tokio::sync::Mutex::new(()),
        }
    }

    fn page(
        &self,
        subscriptions: &[RssSubscription],
        max_items_per_feed: usize,
        show_summaries: bool,
    ) -> Result<RssPageDto, RssError> {
        let mut cache = self.cache.lock().map_err(|_| RssError::Storage)?;
        prune_cache(&mut cache, subscriptions);
        Ok(page_from_cache(
            &cache,
            subscriptions,
            max_items_per_feed,
            show_summaries,
        ))
    }

    fn article_url(&self, article_id: &str) -> Result<String, RssError> {
        if article_id.is_empty()
            || article_id.len() > 160
            || article_id.chars().any(char::is_control)
        {
            return Err(RssError::InvalidUrl);
        }
        let cache = self.cache.lock().map_err(|_| RssError::Storage)?;
        cache
            .articles_by_feed
            .values()
            .flat_map(|articles| articles.iter())
            .find(|article| article.id == article_id)
            .and_then(|article| article.url.clone())
            .ok_or(RssError::NotFound)
    }

    fn subscriptions_changed(&self, subscriptions: &[RssSubscription]) -> Result<(), RssError> {
        let mut cache = self.cache.lock().map_err(|_| RssError::Storage)?;
        prune_cache(&mut cache, subscriptions);
        Ok(())
    }

    async fn refresh(
        &self,
        requested: Vec<RssSubscription>,
        max_items_per_feed: usize,
        current_settings: impl FnOnce() -> Result<Vec<RssSubscription>, RssError>,
    ) -> Result<(), RssError> {
        let _refresh = self.refresh.lock().await;
        let enabled = requested
            .into_iter()
            .filter(|feed| feed.enabled)
            .take(MAX_SUBSCRIPTIONS)
            .collect::<Vec<_>>();
        let requested_urls = enabled
            .iter()
            .map(|feed| (feed.id.clone(), feed.url.clone()))
            .collect::<HashMap<_, _>>();
        let gate = Arc::new(Semaphore::new(MAX_PARALLEL_FEEDS));
        let mut tasks = JoinSet::new();
        for subscription in enabled {
            let permit_gate = Arc::clone(&gate);
            let per_feed_limit = max_items_per_feed.clamp(MIN_ITEMS_PER_FEED, MAX_ITEMS_PER_FEED);
            tasks.spawn(async move {
                let _permit = permit_gate
                    .acquire_owned()
                    .await
                    .map_err(|_| RssError::NetworkFailed)?;
                let result = tokio::time::timeout(
                    TOTAL_FEED_TIMEOUT,
                    fetch_subscription(&subscription, per_feed_limit),
                )
                .await
                .map_err(|_| RssError::TimedOut)
                .and_then(|value| value);
                Ok::<_, RssError>((subscription, result))
            });
        }

        let mut outcomes = Vec::new();
        while let Some(joined) = tasks.join_next().await {
            if let Ok(Ok(outcome)) = joined {
                outcomes.push(outcome);
            }
        }

        let current = current_settings()?;
        let current_enabled = current
            .iter()
            .filter(|feed| feed.enabled)
            .map(|feed| (feed.id.clone(), feed.url.clone()))
            .collect::<HashMap<_, _>>();
        let mut cache = self.cache.lock().map_err(|_| RssError::Storage)?;
        prune_cache(&mut cache, &current);
        for (subscription, outcome) in outcomes {
            if requested_urls.get(&subscription.id) != Some(&subscription.url)
                || current_enabled.get(&subscription.id) != Some(&subscription.url)
            {
                continue;
            }
            match outcome {
                Ok(articles) => {
                    cache
                        .feed_urls
                        .insert(subscription.id.clone(), subscription.url);
                    cache.errors.remove(&subscription.id);
                    cache.articles_by_feed.insert(subscription.id, articles);
                }
                Err(error) => {
                    cache.errors.insert(subscription.id, error);
                }
            }
            trim_cache_to_global_limit(&mut cache);
        }
        cache.last_updated_at_ms = Some(chrono::Utc::now().timestamp_millis());
        Ok(())
    }
}

fn page_from_cache(
    cache: &CacheState,
    subscriptions: &[RssSubscription],
    max_items_per_feed: usize,
    show_summaries: bool,
) -> RssPageDto {
    let active_ids = subscriptions
        .iter()
        .filter(|feed| feed.enabled)
        .map(|feed| feed.id.as_str())
        .collect::<HashSet<_>>();
    let mut articles = cache
        .articles_by_feed
        .iter()
        .filter(|(feed_id, _)| active_ids.contains(feed_id.as_str()))
        .flat_map(|(_, articles)| articles.iter())
        .cloned()
        .collect::<Vec<_>>();
    articles.sort_by(|left, right| {
        right
            .published_at_ms
            .unwrap_or(i64::MIN)
            .cmp(&left.published_at_ms.unwrap_or(i64::MIN))
            .then_with(|| left.feed_title.cmp(&right.feed_title))
            .then_with(|| left.title.cmp(&right.title))
    });
    articles.dedup_by(|left, right| left.id == right.id);
    articles.truncate(MAX_TOTAL_CACHED_ARTICLES);
    let mut errors = cache
        .errors
        .iter()
        .filter(|(feed_id, _)| active_ids.contains(feed_id.as_str()))
        .map(|(feed_id, error)| RssFeedErrorDto {
            feed_id: feed_id.clone(),
            code: error.code().to_owned(),
        })
        .collect::<Vec<_>>();
    errors.sort_by(|left, right| left.feed_id.cmp(&right.feed_id));
    RssPageDto {
        dto_version: RSS_DTO_VERSION,
        subscriptions: subscriptions.to_vec(),
        max_items_per_feed: max_items_per_feed.clamp(MIN_ITEMS_PER_FEED, MAX_ITEMS_PER_FEED),
        show_summaries,
        articles: articles.iter().map(RssArticle::dto).collect(),
        errors,
        last_updated_at_ms: cache.last_updated_at_ms.map(|value| value.to_string()),
    }
}

fn prune_cache(cache: &mut CacheState, subscriptions: &[RssSubscription]) {
    let active = subscriptions
        .iter()
        .filter(|feed| feed.enabled)
        .map(|feed| (feed.id.as_str(), feed.url.as_str()))
        .collect::<HashMap<_, _>>();
    cache.articles_by_feed.retain(|feed_id, _| {
        active.get(feed_id.as_str()).is_some_and(|url| {
            cache
                .feed_urls
                .get(feed_id)
                .is_some_and(|cached| cached == url)
        })
    });
    cache.feed_urls.retain(|feed_id, url| {
        active
            .get(feed_id.as_str())
            .is_some_and(|active_url| *active_url == url)
    });
    cache
        .errors
        .retain(|feed_id, _| active.contains_key(feed_id.as_str()));
}

fn trim_cache_to_global_limit(cache: &mut CacheState) {
    let mut positions = cache
        .articles_by_feed
        .iter()
        .flat_map(|(feed_id, articles)| {
            articles.iter().map(move |article| {
                (
                    article.published_at_ms.unwrap_or(i64::MIN),
                    feed_id.clone(),
                    article.id.clone(),
                )
            })
        })
        .collect::<Vec<_>>();
    if positions.len() <= MAX_TOTAL_CACHED_ARTICLES {
        return;
    }
    positions.sort_by(|left, right| right.0.cmp(&left.0).then_with(|| left.2.cmp(&right.2)));
    let keep = positions
        .into_iter()
        .take(MAX_TOTAL_CACHED_ARTICLES)
        .map(|(_, _, id)| id)
        .collect::<HashSet<_>>();
    cache
        .articles_by_feed
        .values_mut()
        .for_each(|articles| articles.retain(|article| keep.contains(&article.id)));
}

async fn fetch_subscription(
    subscription: &RssSubscription,
    max_items: usize,
) -> Result<Vec<RssArticle>, RssError> {
    let initial = normalize_feed_url(&subscription.url)?;
    let host = initial.host_str().ok_or(RssError::InvalidUrl)?.to_owned();
    let addresses = resolve_public_addresses(initial.clone()).await?;
    let client = Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .no_proxy()
        .connect_timeout(CONNECT_TIMEOUT)
        .timeout(REQUEST_TIMEOUT)
        .resolve_to_addrs(&host, &addresses)
        .build()
        .map_err(|_| RssError::NetworkFailed)?;
    let (bytes, final_url) = download_feed(&client, initial).await?;
    parse_feed(&bytes, subscription, &final_url, max_items)
}

async fn resolve_public_addresses(url: Url) -> Result<Vec<SocketAddr>, RssError> {
    let host = url.host_str().ok_or(RssError::InvalidUrl)?.to_owned();
    let port = url.port_or_known_default().ok_or(RssError::InvalidUrl)?;
    let addresses = tokio::task::spawn_blocking(move || {
        (host.as_str(), port)
            .to_socket_addrs()
            .map(|items| items.collect::<Vec<_>>())
    })
    .await
    .map_err(|_| RssError::DnsFailed)?
    .map_err(|_| RssError::DnsFailed)?;
    if addresses.is_empty() {
        return Err(RssError::DnsFailed);
    }
    if addresses.iter().any(|address| !is_public_ip(address.ip())) {
        return Err(RssError::PrivateHost);
    }
    Ok(addresses)
}

async fn download_feed(client: &Client, initial: Url) -> Result<(Vec<u8>, Url), RssError> {
    let original_host = initial
        .host_str()
        .ok_or(RssError::InvalidUrl)?
        .to_ascii_lowercase();
    let original_port = initial.port_or_known_default();
    let mut current = initial;
    for redirects in 0..=MAX_REDIRECTS {
        let mut response = client
            .get(current.clone())
            .header(
                ACCEPT,
                "application/atom+xml, application/rss+xml, application/xml, text/xml;q=0.9",
            )
            .header(ACCEPT_ENCODING, "identity")
            .header(USER_AGENT, "DeskCubby RSS/1.0")
            .send()
            .await
            .map_err(map_reqwest_error)?;
        if is_redirect(response.status()) {
            if redirects >= MAX_REDIRECTS {
                return Err(RssError::RedirectNotAllowed);
            }
            let location = response
                .headers()
                .get(LOCATION)
                .and_then(|value| value.to_str().ok())
                .filter(|value| !value.is_empty())
                .ok_or(RssError::RedirectNotAllowed)?;
            let next = current
                .join(location)
                .map_err(|_| RssError::RedirectNotAllowed)?;
            validate_redirect(&next, &original_host, original_port)?;
            current = next;
            continue;
        }
        if !response.status().is_success() {
            return Err(RssError::HttpFailed);
        }
        if response
            .headers()
            .get(CONTENT_LENGTH)
            .and_then(|value| value.to_str().ok())
            .and_then(|value| value.parse::<usize>().ok())
            .is_some_and(|length| length > MAX_FEED_BYTES)
        {
            return Err(RssError::TooLarge);
        }
        let mut bytes = Vec::with_capacity(64 * 1024);
        while let Some(chunk) = response.chunk().await.map_err(map_reqwest_error)? {
            if bytes.len().saturating_add(chunk.len()) > MAX_FEED_BYTES {
                return Err(RssError::TooLarge);
            }
            bytes.extend_from_slice(&chunk);
        }
        return Ok((bytes, current));
    }
    Err(RssError::RedirectNotAllowed)
}

fn map_reqwest_error(error: reqwest::Error) -> RssError {
    if error.is_timeout() {
        RssError::TimedOut
    } else {
        RssError::NetworkFailed
    }
}

fn is_redirect(status: StatusCode) -> bool {
    matches!(status.as_u16(), 301 | 302 | 303 | 307 | 308)
}

fn validate_redirect(
    target: &Url,
    original_host: &str,
    original_port: Option<u16>,
) -> Result<(), RssError> {
    validate_https_url(target, MAX_FEED_URL_CHARS)?;
    if target.host_str().map(str::to_ascii_lowercase).as_deref() != Some(original_host)
        || target.port_or_known_default() != original_port
    {
        return Err(RssError::RedirectNotAllowed);
    }
    Ok(())
}

fn normalize_feed_url(raw: &str) -> Result<Url, RssError> {
    let trimmed = raw.trim();
    if trimmed.is_empty()
        || trimmed.chars().count() > MAX_FEED_URL_CHARS
        || trimmed.chars().any(char::is_control)
    {
        return Err(RssError::InvalidUrl);
    }
    let candidate = if Url::parse(trimmed)
        .ok()
        .and_then(|url| (!url.scheme().is_empty()).then_some(()))
        .is_some()
    {
        trimmed.to_owned()
    } else {
        format!("https://{trimmed}")
    };
    let mut url = Url::parse(&candidate).map_err(|_| RssError::InvalidUrl)?;
    if url.scheme() != "https" {
        return Err(RssError::HttpsRequired);
    }
    validate_https_url(&url, MAX_FEED_URL_CHARS)?;
    url.set_fragment(None);
    Ok(url)
}

fn validate_https_url(url: &Url, max_chars: usize) -> Result<(), RssError> {
    if url.as_str().chars().count() > max_chars
        || url.scheme() != "https"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err(RssError::InvalidUrl);
    }
    if let Some(host) = url.host_str() {
        let ip_literal = host
            .strip_prefix('[')
            .and_then(|value| value.strip_suffix(']'))
            .unwrap_or(host);
        if let Ok(ip) = ip_literal.parse::<IpAddr>()
            && !is_public_ip(ip)
        {
            return Err(RssError::PrivateHost);
        }
    }
    Ok(())
}

fn is_public_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => {
            let [a, b, c, _] = ip.octets();
            !(a == 0
                || a == 10
                || a == 127
                || (a == 100 && (64..=127).contains(&b))
                || (a == 169 && b == 254)
                || (a == 172 && (16..=31).contains(&b))
                || (a == 192 && b == 0 && c == 0)
                || (a == 192 && b == 0 && c == 2)
                || (a == 192 && b == 88 && c == 99)
                || (a == 192 && b == 168)
                || (a == 198 && (b == 18 || b == 19))
                || (a == 198 && b == 51 && c == 100)
                || (a == 203 && b == 0 && c == 113)
                || a >= 224)
        }
        IpAddr::V6(ip) => {
            let octets = ip.octets();
            (octets[0] & 0xe0) == 0x20
                && !(octets[0] == 0x20
                    && octets[1] == 0x01
                    && octets[2] == 0x0d
                    && octets[3] == 0xb8)
        }
    }
}

#[derive(Default)]
struct ArticleDraft {
    source_id: String,
    title: String,
    link: String,
    summary: String,
    content: String,
    published: String,
    updated: String,
    base_url: Option<Url>,
    text_char_counts: [usize; 7],
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum FeedKind {
    Rss,
    Atom,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum TextField {
    Id,
    Title,
    Link,
    Summary,
    Content,
    Published,
    Updated,
}

fn parse_feed(
    bytes: &[u8],
    subscription: &RssSubscription,
    source_url: &Url,
    max_items: usize,
) -> Result<Vec<RssArticle>, RssError> {
    if contains_doctype(bytes) {
        return Err(RssError::DoctypeForbidden);
    }
    let mut reader = Reader::from_reader(Cursor::new(bytes));
    reader.config_mut().trim_text(false);
    reader.config_mut().check_end_names = true;
    reader.config_mut().expand_empty_elements = true;
    let mut buffer = Vec::new();
    let mut names = Vec::<String>::new();
    let mut bases = vec![source_url.clone()];
    let mut kind = None;
    let mut feed_title = subscription.title.trim().to_owned();
    let mut feed_title_chars = feed_title.chars().count();
    let mut current = None::<ArticleDraft>;
    let mut current_item_depth = 0usize;
    let limit = max_items.clamp(1, MAX_ITEMS_PER_FEED);
    let mut articles = Vec::new();

    loop {
        match reader.read_event_into(&mut buffer) {
            Ok(Event::Start(start)) => {
                if names.len() >= MAX_XML_DEPTH {
                    return Err(RssError::ParseFailed);
                }
                let name = local_name(start.name().as_ref());
                if names.is_empty() {
                    kind = match name.as_str() {
                        "rss" => Some(FeedKind::Rss),
                        "feed" => Some(FeedKind::Atom),
                        _ => return Err(RssError::UnsupportedFormat),
                    };
                }
                let parent_base = bases.last().cloned().ok_or(RssError::ParseFailed)?;
                let next_base = attribute(&reader, &start, b"xml:base")
                    .and_then(|raw| parent_base.join(raw.trim()).ok())
                    .filter(|url| url.scheme() == "https")
                    .unwrap_or(parent_base);
                names.push(name.clone());
                bases.push(next_base.clone());

                if current.is_none()
                    && ((kind == Some(FeedKind::Rss) && name == "item")
                        || (kind == Some(FeedKind::Atom) && name == "entry"))
                {
                    current = Some(ArticleDraft {
                        base_url: Some(next_base),
                        ..ArticleDraft::default()
                    });
                    current_item_depth = names.len();
                } else if kind == Some(FeedKind::Atom)
                    && current.is_some()
                    && names.len() == current_item_depth + 1
                    && name == "link"
                {
                    apply_atom_link(&reader, &start, bases.last(), current.as_mut());
                }
            }
            Ok(Event::Text(text)) => {
                let decoded = text.xml10_content().map_err(|_| RssError::ParseFailed)?;
                let unescaped =
                    quick_xml::escape::unescape(&decoded).map_err(|_| RssError::ParseFailed)?;
                append_text(
                    &names,
                    kind,
                    current_item_depth,
                    current.as_mut(),
                    &mut feed_title,
                    &mut feed_title_chars,
                    &unescaped,
                );
            }
            Ok(Event::CData(text)) => {
                let decoded = text.decode().map_err(|_| RssError::ParseFailed)?;
                append_text(
                    &names,
                    kind,
                    current_item_depth,
                    current.as_mut(),
                    &mut feed_title,
                    &mut feed_title_chars,
                    &decoded,
                );
            }
            Ok(Event::GeneralRef(reference)) => {
                let decoded = decode_xml_general_ref(&reference)?;
                append_text(
                    &names,
                    kind,
                    current_item_depth,
                    current.as_mut(),
                    &mut feed_title,
                    &mut feed_title_chars,
                    &decoded,
                );
            }
            Ok(Event::End(end)) => {
                let name = local_name(end.name().as_ref());
                if current.is_some()
                    && names.len() == current_item_depth
                    && ((kind == Some(FeedKind::Rss) && name == "item")
                        || (kind == Some(FeedKind::Atom) && name == "entry"))
                {
                    let draft = current.take().ok_or(RssError::ParseFailed)?;
                    articles.push(draft_to_article(
                        draft,
                        subscription,
                        &feed_title,
                        source_url,
                    ));
                    if articles.len() >= limit {
                        break;
                    }
                }
                if names.pop().as_deref() != Some(name.as_str()) || bases.pop().is_none() {
                    return Err(RssError::ParseFailed);
                }
            }
            Ok(Event::DocType(_)) => return Err(RssError::DoctypeForbidden),
            Ok(Event::Eof) => break,
            Ok(_) => {}
            Err(_) => return Err(RssError::ParseFailed),
        }
        buffer.clear();
    }
    if kind.is_none() {
        return Err(RssError::UnsupportedFormat);
    }
    Ok(articles)
}

fn attribute<'a>(
    reader: &Reader<Cursor<&[u8]>>,
    start: &'a BytesStart<'a>,
    key: &[u8],
) -> Option<String> {
    start
        .attributes()
        .with_checks(false)
        .filter_map(Result::ok)
        .find(|attribute| attribute.key.as_ref().eq_ignore_ascii_case(key))
        .and_then(|attribute| {
            attribute
                .decode_and_unescape_value(reader.decoder())
                .ok()
                .map(|value| value.into_owned())
        })
}

fn apply_atom_link(
    reader: &Reader<Cursor<&[u8]>>,
    start: &BytesStart<'_>,
    base: Option<&Url>,
    current: Option<&mut ArticleDraft>,
) {
    let Some(current) = current else {
        return;
    };
    let href = attribute(reader, start, b"href").unwrap_or_default();
    let rel = attribute(reader, start, b"rel")
        .unwrap_or_default()
        .to_ascii_lowercase();
    let Some(resolved) = base.and_then(|base| base.join(href.trim()).ok()) else {
        return;
    };
    if safe_article_url(&resolved).is_none() {
        return;
    }
    if current.link.is_empty() || rel.is_empty() || rel == "alternate" {
        current.link = resolved.into();
        current.text_char_counts[text_field_index(TextField::Link)] = current.link.chars().count();
    }
}

fn append_text(
    names: &[String],
    kind: Option<FeedKind>,
    item_depth: usize,
    current: Option<&mut ArticleDraft>,
    feed_title: &mut String,
    feed_title_chars: &mut usize,
    text: &str,
) {
    if let Some(current) = current {
        if let Some(field) = item_text_field(names, item_depth) {
            let (target, char_count) = field_parts(current, field);
            append_bounded(target, char_count, text, field_limit(field));
        }
        return;
    }
    let is_feed_title = match kind {
        Some(FeedKind::Rss) => names.len() == 3 && names[1] == "channel" && names[2] == "title",
        Some(FeedKind::Atom) => names.len() == 2 && names[1] == "title",
        None => false,
    };
    if is_feed_title && feed_title.is_empty() {
        append_bounded(
            feed_title,
            feed_title_chars,
            text,
            MAX_SUBSCRIPTION_TITLE_CHARS,
        );
    }
}

fn item_text_field(names: &[String], item_depth: usize) -> Option<TextField> {
    let name = names.get(item_depth)?;
    match name.as_str() {
        "id" | "guid" => Some(TextField::Id),
        "title" => Some(TextField::Title),
        "link" => Some(TextField::Link),
        "description" | "summary" => Some(TextField::Summary),
        "content" | "encoded" => Some(TextField::Content),
        "pubdate" | "published" | "date" => Some(TextField::Published),
        "updated" => Some(TextField::Updated),
        _ => None,
    }
}

fn text_field_index(field: TextField) -> usize {
    match field {
        TextField::Id => 0,
        TextField::Title => 1,
        TextField::Link => 2,
        TextField::Summary => 3,
        TextField::Content => 4,
        TextField::Published => 5,
        TextField::Updated => 6,
    }
}

fn field_parts(draft: &mut ArticleDraft, field: TextField) -> (&mut String, &mut usize) {
    match field {
        TextField::Id => (&mut draft.source_id, &mut draft.text_char_counts[0]),
        TextField::Title => (&mut draft.title, &mut draft.text_char_counts[1]),
        TextField::Link => (&mut draft.link, &mut draft.text_char_counts[2]),
        TextField::Summary => (&mut draft.summary, &mut draft.text_char_counts[3]),
        TextField::Content => (&mut draft.content, &mut draft.text_char_counts[4]),
        TextField::Published => (&mut draft.published, &mut draft.text_char_counts[5]),
        TextField::Updated => (&mut draft.updated, &mut draft.text_char_counts[6]),
    }
}

fn field_limit(field: TextField) -> usize {
    match field {
        TextField::Title => MAX_TITLE_CHARS,
        TextField::Summary | TextField::Content => MAX_SUMMARY_CHARS,
        TextField::Link => MAX_ARTICLE_URL_CHARS,
        _ => 4_096,
    }
}

fn append_bounded(target: &mut String, char_count: &mut usize, value: &str, max_chars: usize) {
    let remaining = max_chars.saturating_sub(*char_count);
    if remaining == 0 {
        return;
    }
    for character in value.chars().take(remaining) {
        target.push(character);
        *char_count += 1;
    }
}

fn draft_to_article(
    draft: ArticleDraft,
    subscription: &RssSubscription,
    feed_title: &str,
    source_url: &Url,
) -> RssArticle {
    let base = draft.base_url.as_ref().unwrap_or(source_url);
    let link = if draft.link.trim().is_empty() && looks_like_url(&draft.source_id) {
        draft.source_id.trim()
    } else {
        draft.link.trim()
    };
    let url = base.join(link).ok().and_then(|url| safe_article_url(&url));
    let title = plain_text(&draft.title, MAX_TITLE_CHARS);
    let summary_source = if draft.summary.trim().is_empty() {
        &draft.content
    } else {
        &draft.summary
    };
    let summary = plain_text(summary_source, MAX_SUMMARY_CHARS);
    let published_raw = if draft.published.trim().is_empty() {
        draft.updated.trim()
    } else {
        draft.published.trim()
    };
    let stable_source = if !draft.source_id.trim().is_empty() {
        draft.source_id.trim().to_owned()
    } else if let Some(url) = &url {
        url.clone()
    } else {
        format!("{title}|{published_raw}")
    };
    let display_title = plain_text(feed_title, MAX_SUBSCRIPTION_TITLE_CHARS);
    RssArticle {
        id: format!("{}:{}", subscription.id, sha256_hex(&stable_source)),
        feed_id: subscription.id.clone(),
        feed_title: if display_title.is_empty() {
            source_url.host_str().unwrap_or("RSS").to_owned()
        } else {
            display_title
        },
        title,
        url,
        summary,
        published_at_ms: parse_published_at(published_raw),
    }
}

fn safe_article_url(url: &Url) -> Option<String> {
    if validate_https_url(url, MAX_ARTICLE_URL_CHARS).is_err() {
        return None;
    }
    let mut normalized = url.clone();
    normalized.set_username("").ok()?;
    normalized.set_password(None).ok()?;
    Some(normalized.into())
}

fn looks_like_url(raw: &str) -> bool {
    Url::parse(raw.trim())
        .ok()
        .is_some_and(|url| url.scheme() == "https")
}

fn contains_doctype(bytes: &[u8]) -> bool {
    let ascii = bytes
        .iter()
        .filter_map(|byte| (*byte != 0 && byte.is_ascii()).then_some(byte.to_ascii_lowercase()))
        .collect::<Vec<_>>();
    ascii
        .windows(b"<!doctype".len())
        .any(|window| window == b"<!doctype")
}

fn local_name(name: &[u8]) -> String {
    let local = name.rsplit(|byte| *byte == b':').next().unwrap_or(name);
    String::from_utf8_lossy(local).to_ascii_lowercase()
}

/// Feed XML may contain built-in or numeric character references. Accept only
/// those references; unresolved custom names are rejected instead of being
/// interpreted as an external entity.
fn decode_xml_general_ref(reference: &BytesRef<'_>) -> Result<String, RssError> {
    let encoded = reference
        .xml10_content()
        .map_err(|_| RssError::ParseFailed)?;
    if encoded.is_empty() || encoded.len() > 32 || encoded.chars().any(char::is_control) {
        return Err(RssError::ParseFailed);
    }
    quick_xml::escape::unescape(&format!("&{encoded};"))
        .map(|value| value.into_owned())
        .map_err(|_| RssError::ParseFailed)
}

fn plain_text(value: &str, max_chars: usize) -> String {
    let without_markup = rss_markup_regex().replace_all(value, " ");
    let decoded = quick_xml::escape::unescape(without_markup.as_ref())
        .map(|value| value.into_owned())
        .unwrap_or_else(|_| without_markup.into_owned());
    let normalized = decoded.split_whitespace().collect::<Vec<_>>().join(" ");
    normalized.chars().take(max_chars).collect()
}

fn rss_markup_regex() -> &'static regex::Regex {
    static REGEX: OnceLock<regex::Regex> = OnceLock::new();
    REGEX
        .get_or_init(|| regex::Regex::new(r"(?is)<[^>]{0,4096}>").expect("static RSS markup regex"))
}

fn parse_published_at(value: &str) -> Option<i64> {
    DateTime::parse_from_rfc3339(value)
        .or_else(|_| DateTime::parse_from_rfc2822(value))
        .ok()
        .map(|date| date.timestamp_millis())
}

fn sha256_hex(value: &str) -> String {
    hex::encode(Sha256::digest(value.as_bytes()))
}

fn normalize_subscriptions(items: &mut [RssSubscription]) -> Result<(), RssError> {
    if items.len() > MAX_SUBSCRIPTIONS {
        return Err(RssError::LimitExceeded);
    }
    let mut ids = HashSet::new();
    let mut urls = HashSet::new();
    for item in items.iter_mut() {
        item.id = truncate_utf16(item.id.trim(), 80);
        item.title = truncate_utf16(
            &item.title.split_whitespace().collect::<Vec<_>>().join(" "),
            MAX_SUBSCRIPTION_TITLE_CHARS,
        );
        item.url = normalize_feed_url(&item.url)?.into();
        if item.id.is_empty() || !ids.insert(item.id.clone()) {
            return Err(RssError::InvalidUrl);
        }
        if !urls.insert(item.url.to_ascii_lowercase()) {
            return Err(RssError::DuplicateFeed);
        }
    }
    Ok(())
}

fn ensure_dto(version: u32) -> Result<(), RssError> {
    if version == RSS_DTO_VERSION {
        Ok(())
    } else {
        Err(RssError::InvalidUrl)
    }
}

fn truncate_utf16(value: &str, maximum_units: usize) -> String {
    let mut used = 0usize;
    value
        .chars()
        .take_while(|character| {
            let next = character.len_utf16();
            if used + next > maximum_units {
                false
            } else {
                used += next;
                true
            }
        })
        .collect()
}

fn mutate_subscriptions(
    state: &AppState,
    mutate: impl FnOnce(&mut Vec<RssSubscription>) -> Result<(), RssError>,
) -> Result<RssPageDto, RssError> {
    let _settings_guard = SETTINGS_UPDATE_MUTEX
        .lock()
        .map_err(|_| RssError::Storage)?;
    let _guard = state.rss.mutation.lock().map_err(|_| RssError::Storage)?;
    let mut settings = state
        .database
        .get_managed_settings()
        .map_err(|_| RssError::Storage)?;
    mutate(&mut settings.rss_subscriptions)?;
    normalize_subscriptions(&mut settings.rss_subscriptions)?;
    state
        .database
        .put_managed_settings(&settings, db::now_millis())
        .map_err(|_| RssError::Storage)?;
    state
        .rss
        .subscriptions_changed(&settings.rss_subscriptions)?;
    state.rss.page(
        &settings.rss_subscriptions,
        settings.rss_max_items_per_feed as usize,
        settings.rss_show_summaries,
    )
}

#[tauri::command]
pub fn get_rss_page(state: State<'_, AppState>) -> CommandResult<RssPageDto> {
    let settings = state
        .database
        .get_managed_settings()
        .map_err(|_| RssError::Storage.command())?;
    state
        .rss
        .page(
            &settings.rss_subscriptions,
            settings.rss_max_items_per_feed as usize,
            settings.rss_show_summaries,
        )
        .map_err(RssError::command)
}

#[tauri::command]
pub fn save_rss_subscription(
    request: SaveRssSubscriptionRequest,
    state: State<'_, AppState>,
) -> CommandResult<RssPageDto> {
    ensure_dto(request.dto_version).map_err(RssError::command)?;
    let normalized_url = normalize_feed_url(&request.url).map_err(RssError::command)?;
    let mut title = truncate_utf16(
        &request
            .title
            .split_whitespace()
            .collect::<Vec<_>>()
            .join(" "),
        MAX_SUBSCRIPTION_TITLE_CHARS,
    );
    if title.is_empty() {
        title = normalized_url.host_str().unwrap_or("RSS").to_owned();
    }
    let id = request
        .id
        .map(|value| value.trim().to_owned())
        .unwrap_or_else(|| Uuid::new_v4().to_string());
    if id.is_empty() || id.encode_utf16().count() > 80 || id.chars().any(char::is_control) {
        return Err(RssError::InvalidUrl.command());
    }
    mutate_subscriptions(&state, move |subscriptions| {
        if subscriptions
            .iter()
            .any(|feed| feed.id != id && feed.url.eq_ignore_ascii_case(normalized_url.as_str()))
        {
            return Err(RssError::DuplicateFeed);
        }
        if let Some(existing) = subscriptions.iter_mut().find(|feed| feed.id == id) {
            existing.title = title;
            existing.url = normalized_url.into();
        } else {
            if subscriptions.len() >= MAX_SUBSCRIPTIONS {
                return Err(RssError::LimitExceeded);
            }
            subscriptions.push(RssSubscription {
                id,
                title,
                url: normalized_url.into(),
                enabled: true,
            });
        }
        Ok(())
    })
    .map_err(RssError::command)
}

#[tauri::command]
pub fn delete_rss_subscription(
    request: RssSubscriptionIdRequest,
    state: State<'_, AppState>,
) -> CommandResult<RssPageDto> {
    ensure_dto(request.dto_version).map_err(RssError::command)?;
    mutate_subscriptions(&state, move |subscriptions| {
        let before = subscriptions.len();
        subscriptions.retain(|feed| feed.id != request.id);
        if subscriptions.len() == before {
            return Err(RssError::NotFound);
        }
        Ok(())
    })
    .map_err(RssError::command)
}

#[tauri::command]
pub fn set_rss_subscription_enabled(
    request: RssSubscriptionStateRequest,
    state: State<'_, AppState>,
) -> CommandResult<RssPageDto> {
    ensure_dto(request.dto_version).map_err(RssError::command)?;
    mutate_subscriptions(&state, move |subscriptions| {
        let feed = subscriptions
            .iter_mut()
            .find(|feed| feed.id == request.id)
            .ok_or(RssError::NotFound)?;
        feed.enabled = request.enabled;
        Ok(())
    })
    .map_err(RssError::command)
}

#[tauri::command]
pub fn set_rss_preferences(
    request: RssPreferencesRequest,
    state: State<'_, AppState>,
) -> CommandResult<RssPageDto> {
    ensure_dto(request.dto_version).map_err(RssError::command)?;
    if !(MIN_ITEMS_PER_FEED..=MAX_ITEMS_PER_FEED).contains(&request.max_items_per_feed) {
        return Err(RssError::LimitExceeded.command());
    }
    let _settings_guard = SETTINGS_UPDATE_MUTEX
        .lock()
        .map_err(|_| RssError::Storage.command())?;
    let _guard = state
        .rss
        .mutation
        .lock()
        .map_err(|_| RssError::Storage.command())?;
    let mut settings = state
        .database
        .get_managed_settings()
        .map_err(|_| RssError::Storage.command())?;
    settings.rss_max_items_per_feed = request.max_items_per_feed as i32;
    settings.rss_show_summaries = request.show_summaries;
    state
        .database
        .put_managed_settings(&settings, db::now_millis())
        .map_err(|_| RssError::Storage.command())?;
    state
        .rss
        .page(
            &settings.rss_subscriptions,
            request.max_items_per_feed,
            request.show_summaries,
        )
        .map_err(RssError::command)
}

#[tauri::command]
pub async fn refresh_rss(state: State<'_, AppState>) -> CommandResult<RssPageDto> {
    let settings = state
        .database
        .get_managed_settings()
        .map_err(|_| RssError::Storage.command())?;
    let requested = settings.rss_subscriptions.clone();
    let limit = settings.rss_max_items_per_feed as usize;
    let database = state.database.clone();
    state
        .rss
        .refresh(requested, limit, move || {
            database
                .get_managed_settings()
                .map(|settings| settings.rss_subscriptions)
                .map_err(|_| RssError::Storage)
        })
        .await
        .map_err(RssError::command)?;
    get_rss_page(state)
}

#[tauri::command]
pub fn open_rss_article(
    request: OpenRssArticleRequest,
    state: State<'_, AppState>,
) -> CommandResult<()> {
    ensure_dto(request.dto_version).map_err(RssError::command)?;
    let url = state
        .rss
        .article_url(&request.article_id)
        .map_err(RssError::command)?;
    open_external_https_url(&url).map_err(RssError::command)
}

#[cfg(windows)]
fn open_external_https_url(url: &str) -> Result<(), RssError> {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    let parsed = Url::parse(url).map_err(|_| RssError::InvalidUrl)?;
    validate_https_url(&parsed, MAX_ARTICLE_URL_CHARS)?;
    std::process::Command::new("rundll32.exe")
        .args(["url.dll,FileProtocolHandler", parsed.as_str()])
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map(|_| ())
        .map_err(|_| RssError::OpenFailed)
}

#[cfg(not(windows))]
fn open_external_https_url(_url: &str) -> Result<(), RssError> {
    Err(RssError::OpenFailed)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn subscription() -> RssSubscription {
        RssSubscription {
            id: "feed-1".to_owned(),
            title: String::new(),
            url: "https://example.com/feed.xml".to_owned(),
            enabled: true,
        }
    }

    #[test]
    fn feed_url_defaults_to_https_and_rejects_credentials_or_private_hosts() {
        assert_eq!(
            normalize_feed_url("example.com/feed.xml")
                .expect("normalize")
                .as_str(),
            "https://example.com/feed.xml"
        );
        assert_eq!(
            normalize_feed_url("http://example.com/feed").unwrap_err(),
            RssError::HttpsRequired
        );
        assert!(normalize_feed_url("https://user:secret@example.com/feed").is_err());
        assert_eq!(
            normalize_feed_url("https://127.0.0.1/feed").unwrap_err(),
            RssError::PrivateHost
        );
        assert_eq!(
            normalize_feed_url("https://[::1]/feed").unwrap_err(),
            RssError::PrivateHost
        );
    }

    #[test]
    fn redirects_must_remain_https_on_the_exact_origin() {
        let original = normalize_feed_url("https://example.com/feed").expect("url");
        assert!(
            validate_redirect(
                &original.join("/next").expect("relative"),
                "example.com",
                Some(443)
            )
            .is_ok()
        );
        assert_eq!(
            validate_redirect(
                &Url::parse("https://cdn.example.com/feed").expect("url"),
                "example.com",
                Some(443)
            ),
            Err(RssError::RedirectNotAllowed)
        );
        assert!(
            validate_redirect(
                &Url::parse("http://example.com/feed").expect("url"),
                "example.com",
                Some(443)
            )
            .is_err()
        );
    }

    #[test]
    fn parses_rss_two_and_normalizes_html_text() {
        let xml = br#"<?xml version="1.0"?>
          <rss version="2.0"><channel><title>Example News</title>
            <item><guid>one</guid><title><![CDATA[ <b>Hello</b> world ]]></title>
              <link>/article/one</link><description>&lt;p&gt;A &amp;amp; B&lt;/p&gt;</description>
              <pubDate>Thu, 06 Aug 2026 09:30:00 +0800</pubDate></item>
          </channel></rss>"#;
        let articles = parse_feed(
            xml,
            &subscription(),
            &Url::parse("https://example.com/feed.xml").expect("url"),
            50,
        )
        .expect("parse RSS");
        assert_eq!(articles.len(), 1);
        assert_eq!(articles[0].feed_title, "Example News");
        assert_eq!(articles[0].title, "Hello world");
        assert_eq!(
            articles[0].url.as_deref(),
            Some("https://example.com/article/one")
        );
        assert_eq!(articles[0].summary, "A & B");
        assert!(articles[0].published_at_ms.is_some());
    }

    #[test]
    fn parses_atom_alternate_links_and_xml_base() {
        let xml = br#"<?xml version="1.0"?>
          <feed xmlns="http://www.w3.org/2005/Atom" xml:base="https://example.com/blog/">
            <title>Atom feed</title><entry xml:base="posts/"><id>tag:example,1</id>
              <title>Entry one</title><link rel="alternate" href="1" />
              <summary><![CDATA[<p>Summary</p>]]></summary>
              <updated>2026-08-06T01:30:00Z</updated></entry>
          </feed>"#;
        let articles = parse_feed(
            xml,
            &subscription(),
            &Url::parse("https://example.com/feed.xml").expect("url"),
            50,
        )
        .expect("parse Atom");
        assert_eq!(articles.len(), 1);
        assert_eq!(
            articles[0].url.as_deref(),
            Some("https://example.com/blog/posts/1")
        );
        assert_eq!(articles[0].summary, "Summary");
    }

    #[test]
    fn rejects_doctype_in_common_wide_encodings_before_parsing() {
        assert_eq!(
            parse_feed(
                b"<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///private'>]><rss/>",
                &subscription(),
                &Url::parse("https://example.com/feed").expect("url"),
                10,
            ),
            Err(RssError::DoctypeForbidden)
        );
        let utf16ish = b"<\0!\0D\0O\0C\0T\0Y\0P\0E\0 \0r\0s\0s\0>\0";
        assert!(contains_doctype(utf16ish));
    }

    #[test]
    fn unsafe_article_links_stay_visible_but_cannot_be_opened() {
        let xml = br#"<rss><channel><item><title>Unsafe</title>
          <link>http://example.com/article</link></item></channel></rss>"#;
        let articles = parse_feed(
            xml,
            &subscription(),
            &Url::parse("https://example.com/feed").expect("url"),
            10,
        )
        .expect("parse");
        assert_eq!(articles.len(), 1);
        assert_eq!(articles[0].url, None);
    }

    #[test]
    fn article_dto_never_exposes_the_cached_url() {
        let article = RssArticle {
            id: "feed-1:article".to_owned(),
            feed_id: "feed-1".to_owned(),
            feed_title: "Example".to_owned(),
            title: "Article".to_owned(),
            url: Some("https://example.com/not-exposed?opaque=query-value".to_owned()),
            summary: String::new(),
            published_at_ms: None,
        };
        let encoded = serde_json::to_value(article.dto()).expect("serialize DTO");
        assert_eq!(encoded.get("urlAvailable"), Some(&serde_json::json!(true)));
        assert!(encoded.get("url").is_none());
        assert!(!encoded.to_string().contains("not-exposed"));
    }

    #[test]
    fn parser_applies_item_and_text_limits() {
        let repeated = (0..250)
            .map(|index| {
                format!(
                    "<item><guid>{index}</guid><title>{}</title></item>",
                    "x".repeat(5_000)
                )
            })
            .collect::<String>();
        let xml = format!("<rss><channel>{repeated}</channel></rss>");
        let articles = parse_feed(
            xml.as_bytes(),
            &subscription(),
            &Url::parse("https://example.com/feed").expect("url"),
            200,
        )
        .expect("parse");
        assert_eq!(articles.len(), 200);
        assert_eq!(articles[0].title.chars().count(), MAX_TITLE_CHARS);
    }
}
