use std::{
    fmt,
    io::Cursor,
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};

use chrono::{DateTime, Utc};
use md5::{Digest as _, Md5};
use quick_xml::{
    Reader,
    events::{BytesRef, Event},
};
use reqwest::{
    Client, Method, Response, Url,
    header::{
        AUTHORIZATION, CONTENT_LENGTH, CONTENT_TYPE, ETAG, HeaderMap, HeaderName, HeaderValue,
        IF_MATCH, IF_NONE_MATCH, LAST_MODIFIED,
    },
    redirect::Policy,
};

use super::{
    encoding::{sha256_hex, standard_base64},
    manifest::ManifestRemoteStore,
    sigv4::SigV4Signer,
    types::{
        BlobMetadata, BlobRead, BlobWriteCondition, BoxFuture, CloudCredentials, CloudRemoteStore,
        CloudRemoteStoreFactory, CloudSyncError, CloudSyncErrorCode, CloudSyncLimits,
        CloudSyncServiceType, ConditionalBlobTransport,
    },
    validation::{
        ValidatedCloudSyncConfig, append_storage_name, collection_url, valid_hash,
        valid_storage_name,
    },
};

const MAX_ERROR_RESPONSE_BYTES: u64 = 64 * 1024;
const MAX_DAV_PROPERTIES_BYTES: u64 = 64 * 1024;
const MAX_ETAG_CHARS: usize = 4_096;
const MAX_S3_ETAG_CANDIDATES: usize = 8;
const MAX_DAV_TEXT_CHARS: usize = 8_192;
const DAV_PROPFIND_BODY: &[u8] = br#"<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:"><D:prop><D:getetag/></D:prop></D:propfind>"#;

pub struct ReqwestRemoteStoreFactory;

impl CloudRemoteStoreFactory for ReqwestRemoteStoreFactory {
    fn create(
        &self,
        config: &ValidatedCloudSyncConfig,
        credentials: &CloudCredentials,
        limits: CloudSyncLimits,
    ) -> Result<Arc<dyn CloudRemoteStore>, CloudSyncError> {
        let transport = Arc::new(ReqwestBlobTransport::new(config, credentials, limits)?);
        Ok(Arc::new(ManifestRemoteStore::new(transport, limits)))
    }
}

enum Authentication {
    WebDavBasic(Option<String>),
    S3(SigV4Signer),
}

impl fmt::Debug for Authentication {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::WebDavBasic(value) => formatter
                .debug_tuple("WebDavBasic")
                .field(&value.as_ref().map(|_| "<redacted>"))
                .finish(),
            Self::S3(signer) => formatter.debug_tuple("S3").field(signer).finish(),
        }
    }
}

pub struct ReqwestBlobTransport {
    client: Client,
    collection: Url,
    authentication: Authentication,
    transfer_budget: NetworkTransferBudget,
}

impl ReqwestBlobTransport {
    pub fn new(
        config: &ValidatedCloudSyncConfig,
        credentials: &CloudCredentials,
        limits: CloudSyncLimits,
    ) -> Result<Self, CloudSyncError> {
        let limits = limits.validate()?;
        let client = Client::builder()
            .redirect(Policy::none())
            .connect_timeout(Duration::from_millis(limits.connect_timeout_millis))
            .read_timeout(Duration::from_millis(limits.read_timeout_millis))
            .user_agent(&config.source.user_agent)
            .build()
            .map_err(|_| CloudSyncError::network())?;
        let authentication = match config.source.service_type {
            CloudSyncServiceType::Webdav => {
                let value = if config.source.web_dav_username.is_empty()
                    && credentials.web_dav_password.is_empty()
                {
                    None
                } else {
                    Some(format!(
                        "Basic {}",
                        standard_base64(
                            format!(
                                "{}:{}",
                                config.source.web_dav_username, credentials.web_dav_password
                            )
                            .as_bytes()
                        )
                    ))
                };
                Authentication::WebDavBasic(value)
            }
            CloudSyncServiceType::S3Compatible => {
                Authentication::S3(SigV4Signer::new(credentials, &config.source.s3_region)?)
            }
        };
        Ok(Self {
            client,
            collection: collection_url(config)?,
            authentication,
            transfer_budget: NetworkTransferBudget::new(limits.max_transferred_bytes),
        })
    }

    async fn request(
        &self,
        method: Method,
        storage_name: &str,
        body: Option<&[u8]>,
        content_sha256: Option<&str>,
        condition: Option<&BlobWriteCondition>,
        max_response_bytes: u64,
    ) -> Result<(u16, ResponseMetadata, Vec<u8>), CloudSyncError> {
        if !valid_storage_name(storage_name)
            || content_sha256.is_some_and(|value| !valid_hash(value))
        {
            return Err(CloudSyncError::invalid_input());
        }
        let url = append_storage_name(&self.collection, storage_name)?;
        let is_head = method == Method::HEAD;
        let mut plain_headers = Vec::<(String, String)>::new();
        if method.as_str() == "PROPFIND" {
            plain_headers.push((
                CONTENT_TYPE.as_str().to_owned(),
                "application/xml".to_owned(),
            ));
            plain_headers.push(("Depth".to_owned(), "0".to_owned()));
        } else if body.is_some() {
            plain_headers.push((
                CONTENT_TYPE.as_str().to_owned(),
                "application/octet-stream".to_owned(),
            ));
        }
        if let Some(condition) = condition {
            match condition {
                BlobWriteCondition::MustNotExist => {
                    plain_headers.push((IF_NONE_MATCH.as_str().to_owned(), "*".to_owned()))
                }
                BlobWriteCondition::MustMatch(etag) => plain_headers.push((
                    IF_MATCH.as_str().to_owned(),
                    require_safe_etag(etag)?.to_owned(),
                )),
            }
        }
        let mut headers = HeaderMap::new();
        match &self.authentication {
            Authentication::WebDavBasic(authorization) => {
                if let Some(value) = authorization {
                    insert_header(&mut headers, AUTHORIZATION.as_str(), value)?;
                }
                if let Some(hash) = content_sha256 {
                    plain_headers.push(("X-DeskCubby-Sha256".to_owned(), hash.to_owned()));
                }
                for (name, value) in &plain_headers {
                    insert_header(&mut headers, name, value)?;
                }
            }
            Authentication::S3(signer) => {
                if let Some(hash) = content_sha256 {
                    plain_headers.push(("x-amz-meta-deskcubby-sha256".to_owned(), hash.to_owned()));
                }
                let signed = signer.sign(
                    method.as_str(),
                    &url,
                    &plain_headers,
                    body.unwrap_or_default(),
                    content_sha256,
                    Utc::now(),
                )?;
                for (name, value) in signed.headers {
                    // Reqwest synthesizes Host from the exact URL. It was
                    // included in SigV4 but must not be manually duplicated.
                    if name != "host" {
                        insert_header(&mut headers, &name, &value)?;
                    }
                }
            }
        }
        let mut request = self.client.request(method, url).headers(headers);
        if let Some(bytes) = body {
            self.transfer_budget.reserve(bytes.len() as u64)?;
            request = request.body(bytes.to_vec());
        }
        let response = request
            .send()
            .await
            .map_err(|_| CloudSyncError::network())?;
        let status = response.status().as_u16();
        let metadata = response_metadata(&response, self.is_webdav())?;
        let body =
            read_response_bounded(response, max_response_bytes, &self.transfer_budget).await?;
        if !is_head
            && let Some(declared) = metadata_declared_size(&metadata, &body)
            && declared != body.len() as u64
        {
            return Err(CloudSyncError::conflict());
        }
        Ok((status, metadata, body))
    }

    fn is_webdav(&self) -> bool {
        matches!(self.authentication, Authentication::WebDavBasic(_))
    }

    async fn resolve_s3_read_etag(
        &self,
        storage_name: &str,
        metadata: &ResponseMetadata,
        bytes: &[u8],
        expected_etag: Option<&str>,
    ) -> Result<String, CloudSyncError> {
        let expected = expected_etag.map(require_safe_etag).transpose()?;
        if let Some(expected) = expected {
            if let Some(returned) = metadata.strong_etag.as_deref() {
                if returned != expected {
                    return Err(CloudSyncError::conflict());
                }
                return Ok(expected.to_owned());
            }
            return self
                .verify_s3_conditional_version(storage_name, &[expected.to_owned()], bytes)
                .await;
        }
        if let Some(etag) = metadata.strong_etag.as_ref() {
            return Ok(etag.clone());
        }

        let mut candidates = metadata.s3_probe_candidates.clone();
        let derived = s3_single_part_etag(bytes);
        if !candidates.contains(&derived) {
            candidates.push(derived);
        }
        self.verify_s3_conditional_version(storage_name, &candidates, bytes)
            .await
    }

    async fn verify_s3_conditional_version(
        &self,
        storage_name: &str,
        candidates: &[String],
        expected_bytes: &[u8],
    ) -> Result<String, CloudSyncError> {
        if candidates.is_empty() || candidates.len() > MAX_S3_ETAG_CANDIDATES {
            return Err(unsupported_s3_etag());
        }

        // A matching confirmation alone is insufficient: a gateway that ignores If-Match would
        // also return 200. First require a deliberately impossible validator to fail on the same
        // GET method used for candidate confirmation, then bind one candidate to the exact bytes.
        let probe = BlobWriteCondition::MustMatch(build_non_matching_s3_probe(
            &self.collection,
            storage_name,
            candidates,
        ));
        let (status, _, _) = self
            .request(
                Method::GET,
                storage_name,
                None,
                None,
                Some(&probe),
                MAX_ERROR_RESPONSE_BYTES,
            )
            .await?;
        match status {
            412 => {}
            200 | 204 => return Err(ignored_s3_condition()),
            _ => return Err(status_error(status)),
        }

        let maximum = (expected_bytes.len() as u64).max(1);
        let expected_hash = sha256_hex(expected_bytes);
        for candidate in candidates {
            let condition = BlobWriteCondition::MustMatch(candidate.clone());
            let (status, _, confirmation) = self
                .request(
                    Method::GET,
                    storage_name,
                    None,
                    None,
                    Some(&condition),
                    maximum,
                )
                .await?;
            match status {
                200 => {
                    if confirmation.as_slice() != expected_bytes
                        || sha256_hex(&confirmation) != expected_hash
                    {
                        return Err(CloudSyncError::conflict());
                    }
                    return Ok(candidate.clone());
                }
                409 | 412 => {}
                404 => return Err(CloudSyncError::conflict()),
                _ => return Err(status_error(status)),
            }
        }
        Err(CloudSyncError::conflict())
    }

    async fn webdav_strong_etag(&self, storage_name: &str) -> Result<String, CloudSyncError> {
        if !self.is_webdav() {
            return Err(unsupported_etag());
        }
        let method =
            Method::from_bytes(b"PROPFIND").map_err(|_| CloudSyncError::invalid_configuration())?;
        let (status, _, bytes) = self
            .request(
                method,
                storage_name,
                Some(DAV_PROPFIND_BODY),
                None,
                None,
                MAX_DAV_PROPERTIES_BYTES,
            )
            .await?;
        match status {
            200 | 207 => {}
            404 | 409 | 412 => return Err(CloudSyncError::conflict()),
            _ => return Err(status_error(status)),
        }
        let target = append_storage_name(&self.collection, storage_name)?;
        parse_webdav_strong_etag(&bytes, &target)
    }
}

impl ConditionalBlobTransport for ReqwestBlobTransport {
    fn get<'a>(
        &'a self,
        storage_name: &'a str,
        max_bytes: u64,
        expected_etag: Option<&'a str>,
    ) -> BoxFuture<'a, Result<Option<BlobRead>, CloudSyncError>> {
        Box::pin(async move {
            let condition =
                expected_etag.map(|value| BlobWriteCondition::MustMatch(value.to_owned()));
            let (status, metadata, bytes) = self
                .request(
                    Method::GET,
                    storage_name,
                    None,
                    None,
                    condition.as_ref(),
                    max_bytes,
                )
                .await?;
            match status {
                404 => return Ok(None),
                409 | 412 => return Err(CloudSyncError::conflict()),
                200 => {}
                _ => return Err(status_error(status)),
            }
            let etag = if self.is_webdav() {
                match metadata.strong_etag.as_ref() {
                    Some(etag) => etag.clone(),
                    None => {
                        let property_etag = self.webdav_strong_etag(storage_name).await?;
                        if let Some(expected) = expected_etag
                            && property_etag != expected
                        {
                            return Err(CloudSyncError::conflict());
                        }
                        // Bind the property response back to the exact bytes. A
                        // standalone PROPFIND is metadata-only and races with a
                        // writer unless the body is re-read conditionally.
                        let condition = BlobWriteCondition::MustMatch(property_etag.clone());
                        let (confirmation_status, confirmation_metadata, confirmation_bytes) = self
                            .request(
                                Method::GET,
                                storage_name,
                                None,
                                None,
                                Some(&condition),
                                max_bytes,
                            )
                            .await?;
                        match confirmation_status {
                            200 => {}
                            404 | 409 | 412 => return Err(CloudSyncError::conflict()),
                            _ => return Err(status_error(confirmation_status)),
                        }
                        if confirmation_bytes != bytes
                            || sha256_hex(&confirmation_bytes) != sha256_hex(&bytes)
                        {
                            return Err(CloudSyncError::conflict());
                        }
                        if let Some(confirmation_etag) = confirmation_metadata.strong_etag
                            && confirmation_etag != property_etag
                        {
                            return Err(CloudSyncError::conflict());
                        }
                        property_etag
                    }
                }
            } else {
                self.resolve_s3_read_etag(storage_name, &metadata, &bytes, expected_etag)
                    .await?
            };
            if let Some(expected) = expected_etag
                && etag != expected
            {
                return Err(CloudSyncError::conflict());
            }
            Ok(Some(BlobRead {
                metadata: BlobMetadata {
                    etag,
                    size: bytes.len() as u64,
                    last_modified_millis: metadata.last_modified_millis,
                },
                bytes,
            }))
        })
    }

    fn put<'a>(
        &'a self,
        storage_name: &'a str,
        bytes: &'a [u8],
        content_sha256: &'a str,
        condition: BlobWriteCondition,
    ) -> BoxFuture<'a, Result<BlobMetadata, CloudSyncError>> {
        Box::pin(async move {
            if sha256_hex(bytes) != content_sha256 {
                return Err(CloudSyncError::conflict());
            }
            let (status, metadata, _) = self
                .request(
                    Method::PUT,
                    storage_name,
                    Some(bytes),
                    Some(content_sha256),
                    Some(&condition),
                    MAX_ERROR_RESPONSE_BYTES,
                )
                .await?;
            match status {
                200 | 201 | 204 => {}
                409 | 412 => return Err(CloudSyncError::conflict()),
                _ => return Err(status_error(status)),
            }
            if let Some(etag) = metadata.strong_etag {
                return Ok(BlobMetadata {
                    etag,
                    size: bytes.len() as u64,
                    last_modified_millis: metadata.last_modified_millis,
                });
            }
            // Some servers omit ETag on PUT. A full conditional-protocol
            // implementation must obtain the ETag together with the exact
            // committed bytes; HEAD has a race between metadata and content.
            let verified = self
                .get(storage_name, bytes.len() as u64, None)
                .await?
                .ok_or_else(CloudSyncError::conflict)?;
            if verified.bytes != bytes || sha256_hex(&verified.bytes) != content_sha256 {
                return Err(CloudSyncError::conflict());
            }
            Ok(verified.metadata)
        })
    }
}

fn insert_header(headers: &mut HeaderMap, name: &str, value: &str) -> Result<(), CloudSyncError> {
    let name =
        HeaderName::from_bytes(name.as_bytes()).map_err(|_| CloudSyncError::invalid_input())?;
    let value = HeaderValue::from_str(value).map_err(|_| CloudSyncError::invalid_input())?;
    headers.insert(name, value);
    Ok(())
}

#[derive(Debug)]
struct ResponseMetadata {
    strong_etag: Option<String>,
    s3_probe_candidates: Vec<String>,
    size: u64,
    last_modified_millis: i64,
}

fn response_metadata(
    response: &Response,
    strict_webdav_etag: bool,
) -> Result<ResponseMetadata, CloudSyncError> {
    let (strong_etag, s3_probe_candidates) = if strict_webdav_etag {
        (response_strong_etag(response.headers())?, Vec::new())
    } else {
        let resolution = s3_entity_tag_resolution(response.headers())?;
        (resolution.trusted, resolution.candidates)
    };
    let last_modified_millis = response
        .headers()
        .get(LAST_MODIFIED)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| DateTime::parse_from_rfc2822(value).ok())
        .map(|value| value.timestamp_millis())
        .unwrap_or_else(|| Utc::now().timestamp_millis())
        .max(0);
    let size = response
        .headers()
        .get(CONTENT_LENGTH)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<u64>().ok())
        .unwrap_or(0);
    Ok(ResponseMetadata {
        strong_etag,
        s3_probe_candidates,
        size,
        last_modified_millis,
    })
}

fn metadata_declared_size(metadata: &ResponseMetadata, body: &[u8]) -> Option<u64> {
    (metadata.size != 0 || body.is_empty()).then_some(metadata.size)
}

async fn read_response_bounded(
    mut response: Response,
    maximum: u64,
    budget: &NetworkTransferBudget,
) -> Result<Vec<u8>, CloudSyncError> {
    if response
        .content_length()
        .is_some_and(|value| value > maximum || value > budget.remaining())
    {
        return Err(CloudSyncError::limit_exceeded());
    }
    let capacity = response
        .content_length()
        .unwrap_or(8_192)
        .min(maximum)
        .min(usize::MAX as u64) as usize;
    let mut bytes = Vec::with_capacity(capacity);
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| CloudSyncError::network())?
    {
        if chunk.len() as u64 > maximum.saturating_sub(bytes.len() as u64) {
            return Err(CloudSyncError::limit_exceeded());
        }
        budget.reserve(chunk.len() as u64)?;
        bytes.extend_from_slice(&chunk);
    }
    Ok(bytes)
}

struct NetworkTransferBudget {
    maximum: u64,
    used: AtomicU64,
}

impl NetworkTransferBudget {
    const fn new(maximum: u64) -> Self {
        Self {
            maximum,
            used: AtomicU64::new(0),
        }
    }

    fn remaining(&self) -> u64 {
        self.maximum
            .saturating_sub(self.used.load(Ordering::Relaxed))
    }

    fn reserve(&self, amount: u64) -> Result<(), CloudSyncError> {
        let mut used = self.used.load(Ordering::Relaxed);
        loop {
            if amount > self.maximum.saturating_sub(used) {
                return Err(CloudSyncError::limit_exceeded());
            }
            match self.used.compare_exchange_weak(
                used,
                used + amount,
                Ordering::Relaxed,
                Ordering::Relaxed,
            ) {
                Ok(_) => return Ok(()),
                Err(actual) => used = actual,
            }
        }
    }
}

fn response_strong_etag(headers: &HeaderMap) -> Result<Option<String>, CloudSyncError> {
    let mut values = headers.get_all(ETAG).iter();
    let Some(value) = values.next() else {
        return Ok(None);
    };
    if values.next().is_some() {
        return Err(unsupported_etag());
    }
    let value = value.to_str().map_err(|_| unsupported_etag())?;
    parse_strong_entity_tag(value)
}

#[derive(Debug, Default, PartialEq, Eq)]
struct S3EntityTagResolution {
    trusted: Option<String>,
    candidates: Vec<String>,
}

/// S3-compatible gateways sometimes remove quotes, weaken, duplicate, or combine ETag fields.
/// Only one normal quoted strong field is trusted immediately. Every repaired/multi-value result
/// remains merely a probe candidate until the conditional protocol binds it to the response body.
fn s3_entity_tag_resolution(headers: &HeaderMap) -> Result<S3EntityTagResolution, CloudSyncError> {
    let raw_values = headers
        .get_all(ETAG)
        .iter()
        .map(|value| value.to_str().map_err(|_| unsupported_etag()))
        .collect::<Result<Vec<_>, _>>()?;
    if raw_values.len() > MAX_S3_ETAG_CANDIDATES {
        return Err(unsupported_etag());
    }

    let mut parsed = Vec::new();
    for raw in raw_values {
        parsed.extend(split_s3_entity_tag_header(raw)?);
        if parsed.len() > MAX_S3_ETAG_CANDIDATES {
            return Err(unsupported_etag());
        }
    }
    if parsed.iter().any(|value| value.trim().is_empty()) {
        return Err(unsupported_etag());
    }

    let parsed = parsed
        .into_iter()
        .map(parse_s3_entity_tag_candidate)
        .collect::<Result<Vec<_>, _>>()?;
    let trusted = match parsed.as_slice() {
        [(value, true)] => Some(value.clone()),
        _ => None,
    };
    let mut candidates = Vec::new();
    for (candidate, _) in parsed {
        if !candidates.contains(&candidate) {
            candidates.push(candidate);
        }
    }
    Ok(S3EntityTagResolution {
        trusted,
        candidates,
    })
}

fn split_s3_entity_tag_header(raw: &str) -> Result<Vec<&str>, CloudSyncError> {
    if raw.len() > MAX_ETAG_CHARS * MAX_S3_ETAG_CANDIDATES || raw.chars().any(char::is_control) {
        return Err(unsupported_etag());
    }
    let mut result = Vec::new();
    let mut start = 0;
    let mut quoted = false;
    for (index, character) in raw.char_indices() {
        match character {
            '"' => quoted = !quoted,
            ',' if !quoted => {
                result.push(raw[start..index].trim());
                start = index + 1;
            }
            _ => {}
        }
    }
    if quoted {
        return Err(unsupported_etag());
    }
    result.push(raw[start..].trim());
    Ok(result)
}

fn parse_s3_entity_tag_candidate(raw: &str) -> Result<(String, bool), CloudSyncError> {
    let value = raw.trim();
    if value.is_empty() || value.len() > MAX_ETAG_CHARS || value.chars().any(char::is_control) {
        return Err(unsupported_etag());
    }
    if value
        .get(..2)
        .is_some_and(|prefix| prefix.eq_ignore_ascii_case("W/"))
    {
        let repaired = value.get(2..).ok_or_else(unsupported_etag)?;
        let repaired = parse_strong_entity_tag(repaired)?.ok_or_else(unsupported_etag)?;
        return Ok((repaired, false));
    }
    if value.starts_with('"') {
        let strong = parse_strong_entity_tag(value)?.ok_or_else(unsupported_etag)?;
        return Ok((strong, true));
    }
    if !value
        .bytes()
        .all(|byte| (0x21..=0x7e).contains(&byte) && !matches!(byte, b'"' | b','))
    {
        return Err(unsupported_etag());
    }
    let repaired = format!("\"{value}\"");
    let repaired = parse_strong_entity_tag(&repaired)?.ok_or_else(unsupported_etag)?;
    Ok((repaired, false))
}

fn s3_single_part_etag(bytes: &[u8]) -> String {
    format!("\"{:x}\"", Md5::digest(bytes))
}

fn build_non_matching_s3_probe(
    collection: &Url,
    storage_name: &str,
    candidates: &[String],
) -> String {
    let mut seed = format!("{}\n{storage_name}\n", collection.as_str());
    for candidate in candidates {
        seed.push_str(candidate);
        seed.push('\n');
    }
    let digest = sha256_hex(seed.as_bytes());
    let mut probe = format!("\"deskcubby-condition-probe-{}\"", &digest[..32]);
    if candidates.contains(&probe) {
        probe = format!("\"deskcubby-condition-probe-{}-2\"", &digest[..32]);
    }
    probe
}

fn parse_strong_entity_tag(value: &str) -> Result<Option<String>, CloudSyncError> {
    let value = value.trim();
    if value.is_empty() || value.len() > MAX_ETAG_CHARS || value.contains(['\r', '\n']) {
        return Err(unsupported_etag());
    }
    let (weak, tag) = if value
        .get(..2)
        .is_some_and(|prefix| prefix.eq_ignore_ascii_case("W/"))
    {
        (true, value.get(2..).ok_or_else(unsupported_etag)?)
    } else {
        (false, value)
    };
    let inner = tag
        .strip_prefix('"')
        .and_then(|tag| tag.strip_suffix('"'))
        .ok_or_else(unsupported_etag)?;
    if inner.is_empty()
        || !inner
            .bytes()
            .all(|byte| (0x21..=0x7e).contains(&byte) && byte != b'"')
    {
        return Err(unsupported_etag());
    }
    Ok((!weak).then(|| tag.to_owned()))
}

fn require_safe_etag(value: &str) -> Result<&str, CloudSyncError> {
    match parse_strong_entity_tag(value)? {
        Some(_) => Ok(value),
        None => Err(unsupported_etag()),
    }
}

fn unsupported_etag() -> CloudSyncError {
    CloudSyncError::new(
        CloudSyncErrorCode::UnsupportedRemote,
        "The cloud service did not provide a usable strong ETag.",
        true,
    )
}

fn unsupported_s3_etag() -> CloudSyncError {
    CloudSyncError::new(
        CloudSyncErrorCode::UnsupportedRemote,
        "The S3 service supplied no verifiable conditional object version.",
        true,
    )
}

fn ignored_s3_condition() -> CloudSyncError {
    CloudSyncError::new(
        CloudSyncErrorCode::UnsupportedRemote,
        "The S3 service ignored a conditional request; synchronization was stopped.",
        true,
    )
}

#[derive(Default)]
struct DavPropStat {
    status: Option<u16>,
    etags: Vec<String>,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum DavTextField {
    Href,
    Status,
    Etag,
}

struct DavTextCapture {
    field: DavTextField,
    depth: usize,
    text: String,
}

/// Resolve only XML's built-in and numeric references. WebDAV responses must
/// not be able to introduce a custom entity through a server-controlled DTD.
fn decode_xml_general_ref(reference: &BytesRef<'_>) -> Result<String, CloudSyncError> {
    let encoded = reference.xml10_content().map_err(|_| unsupported_etag())?;
    if encoded.is_empty() || encoded.len() > 32 || encoded.chars().any(char::is_control) {
        return Err(unsupported_etag());
    }
    quick_xml::escape::unescape(&format!("&{encoded};"))
        .map(|value| value.into_owned())
        .map_err(|_| unsupported_etag())
}

fn parse_webdav_strong_etag(bytes: &[u8], target: &Url) -> Result<String, CloudSyncError> {
    if bytes.is_empty()
        || bytes.len() as u64 > MAX_DAV_PROPERTIES_BYTES
        || contains_forbidden_xml_markup(bytes)
    {
        return Err(unsupported_etag());
    }
    let mut reader = Reader::from_reader(Cursor::new(bytes));
    reader.config_mut().trim_text(false);
    reader.config_mut().check_end_names = true;
    reader.config_mut().expand_empty_elements = true;
    let mut buffer = Vec::new();
    let mut names = Vec::<String>::new();
    let mut response_count = 0usize;
    let mut response_depth = None;
    let mut hrefs = Vec::<String>::new();
    let mut propstat = None::<(usize, DavPropStat)>;
    let mut propstats = Vec::<DavPropStat>::new();
    let mut capture = None::<DavTextCapture>;
    let mut saw_multistatus = false;

    loop {
        match reader.read_event_into(&mut buffer) {
            Ok(Event::Start(start)) => {
                if names.len() >= 64 {
                    return Err(unsupported_etag());
                }
                let name = xml_local_name(start.name().as_ref());
                names.push(name.clone());
                let depth = names.len();
                if depth == 1 {
                    saw_multistatus = name.eq_ignore_ascii_case("multistatus");
                }
                if name.eq_ignore_ascii_case("response") {
                    response_count += 1;
                    if response_count != 1 || response_depth.is_some() {
                        return Err(unsupported_etag());
                    }
                    response_depth = Some(depth);
                } else if response_depth.is_some() && name.eq_ignore_ascii_case("propstat") {
                    if propstat.is_some() {
                        return Err(unsupported_etag());
                    }
                    propstat = Some((depth, DavPropStat::default()));
                }

                let field = if response_depth.is_some() && name.eq_ignore_ascii_case("href") {
                    Some(DavTextField::Href)
                } else if propstat.is_some() && name.eq_ignore_ascii_case("status") {
                    Some(DavTextField::Status)
                } else if propstat.is_some() && name.eq_ignore_ascii_case("getetag") {
                    Some(DavTextField::Etag)
                } else {
                    None
                };
                if let Some(field) = field {
                    if capture.is_some() {
                        return Err(unsupported_etag());
                    }
                    capture = Some(DavTextCapture {
                        field,
                        depth,
                        text: String::new(),
                    });
                }
            }
            Ok(Event::Text(text)) => {
                if let Some(capture) = capture.as_mut() {
                    let decoded = text.xml10_content().map_err(|_| unsupported_etag())?;
                    let decoded =
                        quick_xml::escape::unescape(&decoded).map_err(|_| unsupported_etag())?;
                    if decoded.chars().count()
                        > MAX_DAV_TEXT_CHARS.saturating_sub(capture.text.chars().count())
                    {
                        return Err(unsupported_etag());
                    }
                    capture.text.push_str(&decoded);
                }
            }
            Ok(Event::CData(text)) => {
                if let Some(capture) = capture.as_mut() {
                    let decoded = text.decode().map_err(|_| unsupported_etag())?;
                    if decoded.chars().count()
                        > MAX_DAV_TEXT_CHARS.saturating_sub(capture.text.chars().count())
                    {
                        return Err(unsupported_etag());
                    }
                    capture.text.push_str(&decoded);
                }
            }
            Ok(Event::GeneralRef(reference)) => {
                let decoded = decode_xml_general_ref(&reference)?;
                let capture = capture.as_mut().ok_or_else(unsupported_etag)?;
                if decoded.chars().count()
                    > MAX_DAV_TEXT_CHARS.saturating_sub(capture.text.chars().count())
                {
                    return Err(unsupported_etag());
                }
                capture.text.push_str(&decoded);
            }
            Ok(Event::End(end)) => {
                let name = xml_local_name(end.name().as_ref());
                let depth = names.len();
                if capture.as_ref().is_some_and(|value| value.depth == depth) {
                    let completed = capture.take().ok_or_else(unsupported_etag)?;
                    let value = completed.text.trim().to_owned();
                    match completed.field {
                        DavTextField::Href => hrefs.push(value),
                        DavTextField::Status => {
                            let status = parse_dav_status(&value).ok_or_else(unsupported_etag)?;
                            let (_, current) = propstat.as_mut().ok_or_else(unsupported_etag)?;
                            if current.status.replace(status).is_some() {
                                return Err(unsupported_etag());
                            }
                        }
                        DavTextField::Etag => {
                            let (_, current) = propstat.as_mut().ok_or_else(unsupported_etag)?;
                            current.etags.push(value);
                        }
                    }
                }
                if propstat
                    .as_ref()
                    .is_some_and(|(propstat_depth, _)| *propstat_depth == depth)
                {
                    let (_, completed) = propstat.take().ok_or_else(unsupported_etag)?;
                    propstats.push(completed);
                }
                if response_depth == Some(depth) {
                    response_depth = None;
                }
                if names.pop().as_deref() != Some(name.as_str()) {
                    return Err(unsupported_etag());
                }
            }
            Ok(Event::DocType(_)) => {
                return Err(unsupported_etag());
            }
            Ok(Event::Eof) => break,
            Ok(_) => {}
            Err(_) => return Err(unsupported_etag()),
        }
        buffer.clear();
    }
    if !saw_multistatus
        || response_count != 1
        || response_depth.is_some()
        || propstat.is_some()
        || capture.is_some()
        || hrefs.len() != 1
        || !dav_href_matches(target, &hrefs[0])
    {
        return Err(unsupported_etag());
    }
    let mut etags = Vec::new();
    for propstat in propstats {
        if propstat.status == Some(200) {
            for value in propstat.etags {
                let etag = parse_strong_entity_tag(&value)?.ok_or_else(unsupported_etag)?;
                etags.push(etag);
            }
        }
    }
    if etags.len() != 1 {
        return Err(unsupported_etag());
    }
    etags.pop().ok_or_else(unsupported_etag)
}

fn xml_local_name(name: &[u8]) -> String {
    let local = name.rsplit(|byte| *byte == b':').next().unwrap_or(name);
    String::from_utf8_lossy(local).into_owned()
}

fn parse_dav_status(value: &str) -> Option<u16> {
    let mut fields = value.split_ascii_whitespace();
    let protocol = fields.next()?;
    let status = fields.next()?.parse::<u16>().ok()?;
    (protocol.starts_with("HTTP/") && (100..=599).contains(&status)).then_some(status)
}

fn dav_href_matches(target: &Url, href: &str) -> bool {
    if href.is_empty() || href.len() > MAX_DAV_TEXT_CHARS {
        return false;
    }
    let candidate = Url::parse(href).or_else(|_| target.join(href));
    let Ok(candidate) = candidate else {
        return false;
    };
    candidate.username().is_empty()
        && candidate.password().is_none()
        && candidate.query().is_none()
        && candidate.fragment().is_none()
        && candidate.scheme().eq_ignore_ascii_case(target.scheme())
        && candidate
            .host_str()
            .zip(target.host_str())
            .is_some_and(|(left, right)| left.eq_ignore_ascii_case(right))
        && candidate.port_or_known_default() == target.port_or_known_default()
        && candidate.path() == target.path()
}

fn contains_forbidden_xml_markup(bytes: &[u8]) -> bool {
    bytes
        .windows(9)
        .any(|window| window.eq_ignore_ascii_case(b"<!DOCTYPE"))
        || bytes
            .windows(8)
            .any(|window| window.eq_ignore_ascii_case(b"<!ENTITY"))
}

fn status_error(status: u16) -> CloudSyncError {
    match status {
        301 | 302 | 303 | 307 | 308 => CloudSyncError::new(
            CloudSyncErrorCode::UnsupportedRemote,
            "The cloud endpoint redirected; configure the final endpoint URL.",
            true,
        ),
        401 => CloudSyncError::new(
            CloudSyncErrorCode::AuthenticationFailed,
            "Cloud authentication failed.",
            true,
        ),
        403 => CloudSyncError::new(
            CloudSyncErrorCode::PermissionDenied,
            "The cloud service denied access.",
            true,
        ),
        404 => CloudSyncError::new(
            CloudSyncErrorCode::RemoteDirectoryMissing,
            "The configured remote directory does not exist.",
            true,
        ),
        405 | 501 => CloudSyncError::new(
            CloudSyncErrorCode::UnsupportedRemote,
            "The cloud service does not support safe conditional GET and PUT.",
            false,
        ),
        411 | 413 => CloudSyncError::limit_exceeded(),
        429 | 500..=599 => CloudSyncError::network(),
        _ => CloudSyncError::new(
            CloudSyncErrorCode::UnsupportedRemote,
            "The cloud service returned an unsupported response.",
            true,
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        collections::{BTreeMap, BTreeSet},
        io::{Read, Write},
        net::{TcpListener, TcpStream},
        sync::{
            Arc, Mutex,
            atomic::{AtomicBool, Ordering},
        },
        thread,
    };

    use crate::cloud_sync::{
        types::{
            CloudCredentials, CloudSyncConfig, CloudSyncContent, CloudSyncDirection,
            CloudSyncServiceType, DEFAULT_CLOUD_SYNC_USER_AGENT,
        },
        validation::validate_cloud_sync_config,
    };

    #[test]
    fn weak_empty_and_injected_etags_are_rejected() {
        for value in ["", "W/\"weak\"", "w/\"weak\"", "\"ok\"\r\nInjected: yes"] {
            assert!(require_safe_etag(value).is_err(), "{value:?}");
        }
        assert_eq!(require_safe_etag("\"strong\"").unwrap(), "\"strong\"");
    }

    #[test]
    fn weak_etag_is_well_formed_but_never_a_validator() {
        assert_eq!(parse_strong_entity_tag("W/\"weak\"").unwrap(), None);
        for value in ["strong", "\"\"", "\"bad quote\"tail", "\"bad\"quote\""] {
            assert!(parse_strong_entity_tag(value).is_err(), "{value:?}");
        }
    }

    #[test]
    fn s3_etag_variants_are_candidates_without_weakening_webdav() {
        let headers = header_map(&[("ETag", "\"quoted-multipart-4\"")]);
        assert_eq!(
            s3_entity_tag_resolution(&headers).unwrap(),
            S3EntityTagResolution {
                trusted: Some("\"quoted-multipart-4\"".to_owned()),
                candidates: vec!["\"quoted-multipart-4\"".to_owned()],
            }
        );

        for (values, candidates) in [
            (vec![], vec![]),
            (vec![("ETag", "unquoted-4")], vec!["\"unquoted-4\""]),
            (vec![("ETag", "W/\"weak\"")], vec!["\"weak\""]),
            (
                vec![("ETag", "\"same\", \"same\""), ("ETag", "\"same\"")],
                vec!["\"same\""],
            ),
            (
                vec![("ETag", "\"proxy\", \"origin\"")],
                vec!["\"proxy\"", "\"origin\""],
            ),
        ] {
            let resolution = s3_entity_tag_resolution(&header_map(&values)).unwrap();
            assert_eq!(resolution.trusted, None);
            assert_eq!(
                resolution.candidates,
                candidates
                    .into_iter()
                    .map(str::to_owned)
                    .collect::<Vec<_>>()
            );
        }

        // The strict parser used by WebDAV is deliberately unchanged.
        assert_eq!(
            response_strong_etag(&header_map(&[("ETag", "W/\"weak\"")])).unwrap(),
            None
        );
        for values in [
            vec![("ETag", "unquoted-4")],
            vec![("ETag", "\"one\""), ("ETag", "\"two\"")],
        ] {
            assert!(response_strong_etag(&header_map(&values)).is_err());
        }
    }

    #[tokio::test]
    async fn s3_repairs_missing_weak_unquoted_duplicate_and_multiple_etags_with_proof() {
        let body = b"remote manifest".to_vec();
        let cases = vec![
            (Vec::new(), s3_single_part_etag(&body)),
            (
                vec![("ETag".to_owned(), "W/\"weak-version\"".to_owned())],
                "\"weak-version\"".to_owned(),
            ),
            (
                vec![("ETag".to_owned(), "multipart-hash-3".to_owned())],
                "\"multipart-hash-3\"".to_owned(),
            ),
            (
                vec![
                    ("ETag".to_owned(), "\"same\", \"same\"".to_owned()),
                    ("ETag".to_owned(), "\"same\"".to_owned()),
                ],
                "\"same\"".to_owned(),
            ),
            (
                vec![("ETag".to_owned(), "\"proxy\", \"origin\"".to_owned())],
                "\"origin\"".to_owned(),
            ),
        ];

        for (initial_headers, accepted) in cases {
            let response_body = body.clone();
            let accepted_for_server = accepted.clone();
            let server = TestHttpServer::new(move |request| {
                if let Some(candidate) = request.headers.get("if-match") {
                    return if candidate == &accepted_for_server {
                        TestResponse::ok(response_body.clone())
                    } else {
                        TestResponse::status(412)
                    };
                }
                TestResponse {
                    status: 200,
                    headers: initial_headers.clone(),
                    body: response_body.clone(),
                }
            });
            let transport = test_s3_transport(&server.endpoint());
            let read = transport
                .get("object.dc", 1_024, None)
                .await
                .unwrap()
                .unwrap();
            assert_eq!(read.metadata.etag, accepted);
            assert_eq!(read.bytes, body);

            let requests = server.requests();
            assert_eq!(requests[0].method, "GET");
            assert_eq!(requests[1].method, "GET");
            assert!(
                requests[1]
                    .headers
                    .get("if-match")
                    .is_some_and(|value| value.starts_with("\"deskcubby-condition-probe-"))
            );
            assert!(requests[2..].iter().any(|request| {
                request.method == "GET"
                    && request.headers.get("if-match") == Some(&read.metadata.etag)
            }));
        }
    }

    #[tokio::test]
    async fn s3_missing_etag_does_not_treat_a_fixed_409_as_condition_proof() {
        let body = b"remote manifest".to_vec();
        let response_body = body.clone();
        let server = TestHttpServer::new(move |request| {
            if request.headers.contains_key("if-match") {
                TestResponse::status(409)
            } else {
                TestResponse::ok(response_body.clone())
            }
        });
        let transport = test_s3_transport(&server.endpoint());

        let error = transport.get("object.dc", 1_024, None).await.unwrap_err();
        assert_eq!(error.code, CloudSyncErrorCode::UnsupportedRemote);
        let requests = server.requests();
        assert_eq!(requests.len(), 2);
        assert!(requests.iter().all(|request| request.method == "GET"));
        assert!(
            requests[1]
                .headers
                .get("if-match")
                .is_some_and(|value| value.starts_with("\"deskcubby-condition-probe-"))
        );
    }

    #[tokio::test]
    async fn s3_missing_etag_rejects_a_stale_conditional_write() {
        let state = Arc::new(Mutex::new(TestS3Object::default()));
        let state_for_server = Arc::clone(&state);
        let server = TestHttpServer::new(move |request| {
            state_for_server.lock().unwrap().respond(request, false)
        });
        let first_phone = test_s3_transport(&server.endpoint());
        let second_phone = test_s3_transport(&server.endpoint());

        let first = b"first manifest";
        let created = first_phone
            .put(
                "object.dc",
                first,
                &sha256_hex(first),
                BlobWriteCondition::MustNotExist,
            )
            .await
            .unwrap();
        let stale = second_phone
            .get("object.dc", 1_024, None)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(stale.metadata.etag, created.etag);

        let winner = b"winner manifest";
        first_phone
            .put(
                "object.dc",
                winner,
                &sha256_hex(winner),
                BlobWriteCondition::MustMatch(created.etag),
            )
            .await
            .unwrap();
        let loser = b"stale loser";
        let error = second_phone
            .put(
                "object.dc",
                loser,
                &sha256_hex(loser),
                BlobWriteCondition::MustMatch(stale.metadata.etag),
            )
            .await
            .unwrap_err();
        assert_eq!(error.code, CloudSyncErrorCode::Conflict);
        assert_eq!(
            state.lock().unwrap().bytes.as_deref(),
            Some(winner.as_slice())
        );
    }

    #[tokio::test]
    async fn s3_missing_etag_never_accepts_a_service_that_ignores_if_match() {
        let state = Arc::new(Mutex::new(TestS3Object {
            bytes: Some(b"remote manifest".to_vec()),
        }));
        let state_for_server = Arc::clone(&state);
        let server = TestHttpServer::new(move |request| {
            state_for_server.lock().unwrap().respond(request, true)
        });
        let transport = test_s3_transport(&server.endpoint());

        let error = transport.get("object.dc", 1_024, None).await.unwrap_err();
        assert_eq!(error.code, CloudSyncErrorCode::UnsupportedRemote);
        assert_eq!(error.message, ignored_s3_condition().message);
        assert_eq!(
            server
                .requests()
                .iter()
                .map(|request| request.method.as_str())
                .collect::<Vec<_>>(),
            vec!["GET", "GET"]
        );
    }

    #[test]
    fn dav_depth_zero_response_requires_exact_target_and_one_strong_etag() {
        let target = Url::parse("https://cloud.example.test/root/object.dc").unwrap();
        let xml = br#"<?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/root/object.dc</D:href>
                <D:propstat><D:prop><D:getetag>&quot;strong-1&quot;</D:getetag></D:prop>
                <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
            </D:multistatus>"#;
        assert_eq!(
            parse_webdav_strong_etag(xml, &target).unwrap(),
            "\"strong-1\""
        );
    }

    #[test]
    fn dav_metadata_fails_closed_for_weak_wrong_or_multiple_responses() {
        let target = Url::parse("https://cloud.example.test/root/object.dc").unwrap();
        for xml in [
            br#"<D:multistatus xmlns:D="DAV:"><D:response><D:href>/root/object.dc</D:href><D:propstat><D:prop><D:getetag>W/&quot;weak&quot;</D:getetag></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>"#.as_slice(),
            br#"<D:multistatus xmlns:D="DAV:"><D:response><D:href>/root/other.dc</D:href><D:propstat><D:prop><D:getetag>&quot;strong&quot;</D:getetag></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>"#.as_slice(),
            br#"<D:multistatus xmlns:D="DAV:"><D:response><D:href>/root/object.dc</D:href><D:propstat><D:prop><D:getetag>&quot;one&quot;</D:getetag></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response><D:response><D:href>/root/object.dc</D:href><D:propstat><D:prop><D:getetag>&quot;two&quot;</D:getetag></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>"#.as_slice(),
            br#"<!DOCTYPE x [<!ENTITY y "boom">]><D:multistatus xmlns:D="DAV:"/>"#.as_slice(),
        ] {
            assert!(parse_webdav_strong_etag(xml, &target).is_err());
        }
    }

    #[test]
    fn network_budget_is_atomic_and_fail_closed() {
        let budget = NetworkTransferBudget::new(10);
        budget.reserve(4).unwrap();
        assert_eq!(budget.remaining(), 6);
        assert!(budget.reserve(7).is_err());
        assert_eq!(budget.remaining(), 6);
        budget.reserve(6).unwrap();
        assert_eq!(budget.remaining(), 0);
    }

    #[test]
    fn errors_never_contain_status_bodies_or_endpoints() {
        let error = status_error(503);
        assert_eq!(error.code, CloudSyncErrorCode::NetworkUnavailable);
        assert!(!error.to_string().contains("https://"));
    }

    #[test]
    fn authentication_debug_redacts_basic_value() {
        let auth = Authentication::WebDavBasic(Some("Basic dXNlcjpzZWNyZXQ=".to_owned()));
        let rendered = format!("{auth:?}");
        assert!(!rendered.contains("dXNlcjpzZWNyZXQ"));
    }

    fn header_map(values: &[(&str, &str)]) -> HeaderMap {
        let mut headers = HeaderMap::new();
        for (name, value) in values {
            headers.append(
                HeaderName::from_bytes(name.as_bytes()).unwrap(),
                HeaderValue::from_str(value).unwrap(),
            );
        }
        headers
    }

    fn test_s3_transport(endpoint: &str) -> ReqwestBlobTransport {
        let config = CloudSyncConfig {
            id: "test-s3".to_owned(),
            name: "Test S3".to_owned(),
            enabled: true,
            service_type: CloudSyncServiceType::S3Compatible,
            endpoint_url: endpoint.to_owned(),
            remote_path: String::new(),
            user_agent: DEFAULT_CLOUD_SYNC_USER_AGENT.to_owned(),
            web_dav_username: String::new(),
            s3_bucket: "deskcubby".to_owned(),
            s3_region: "us-east-1".to_owned(),
            s3_path_style: true,
            allow_insecure_http: true,
            selected_contents: BTreeSet::from([CloudSyncContent::JsonBackup]),
            direction: CloudSyncDirection::TwoWay,
        };
        let mut credentials = CloudCredentials::default();
        credentials.s3_access_key = "access-key".to_owned();
        credentials.s3_secret_key = "secret-key".to_owned();
        let validated = validate_cloud_sync_config(&config, &credentials).unwrap();
        ReqwestBlobTransport::new(&validated, &credentials, CloudSyncLimits::default()).unwrap()
    }

    #[derive(Clone, Debug)]
    struct TestRequest {
        method: String,
        headers: BTreeMap<String, String>,
        body: Vec<u8>,
    }

    struct TestResponse {
        status: u16,
        headers: Vec<(String, String)>,
        body: Vec<u8>,
    }

    impl TestResponse {
        fn ok(body: Vec<u8>) -> Self {
            Self {
                status: 200,
                headers: Vec::new(),
                body,
            }
        }

        fn status(status: u16) -> Self {
            Self {
                status,
                headers: Vec::new(),
                body: Vec::new(),
            }
        }
    }

    #[derive(Default)]
    struct TestS3Object {
        bytes: Option<Vec<u8>>,
    }

    impl TestS3Object {
        fn respond(&mut self, request: &TestRequest, ignore_conditions: bool) -> TestResponse {
            let etag = self.bytes.as_deref().map(s3_single_part_etag);
            let matches = ignore_conditions
                || request
                    .headers
                    .get("if-match")
                    .is_none_or(|expected| etag.as_ref() == Some(expected));
            let absent = ignore_conditions
                || request
                    .headers
                    .get("if-none-match")
                    .is_none_or(|expected| expected != "*" || self.bytes.is_none());
            if !matches || !absent {
                return TestResponse::status(412);
            }
            match request.method.as_str() {
                "HEAD" => {
                    if self.bytes.is_some() {
                        TestResponse::status(200)
                    } else {
                        TestResponse::status(404)
                    }
                }
                "GET" => self
                    .bytes
                    .clone()
                    .map(TestResponse::ok)
                    .unwrap_or_else(|| TestResponse::status(404)),
                "PUT" => {
                    self.bytes = Some(request.body.clone());
                    TestResponse::status(200)
                }
                _ => TestResponse::status(405),
            }
        }
    }

    struct TestHttpServer {
        address: std::net::SocketAddr,
        stop: Arc<AtomicBool>,
        requests: Arc<Mutex<Vec<TestRequest>>>,
        thread: Option<thread::JoinHandle<()>>,
    }

    impl TestHttpServer {
        fn new<F>(handler: F) -> Self
        where
            F: Fn(&TestRequest) -> TestResponse + Send + Sync + 'static,
        {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            listener.set_nonblocking(true).unwrap();
            let address = listener.local_addr().unwrap();
            let stop = Arc::new(AtomicBool::new(false));
            let requests = Arc::new(Mutex::new(Vec::new()));
            let thread_stop = Arc::clone(&stop);
            let thread_requests = Arc::clone(&requests);
            let handler = Arc::new(handler);
            let thread = thread::spawn(move || {
                while !thread_stop.load(Ordering::Relaxed) {
                    match listener.accept() {
                        Ok((mut stream, _)) => {
                            let _ = stream.set_nonblocking(false);
                            let request = match read_test_request(&mut stream) {
                                Ok(request) => request,
                                Err(_) => continue,
                            };
                            thread_requests.lock().unwrap().push(request.clone());
                            let response = handler(&request);
                            write_test_response(&mut stream, response);
                        }
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            thread::sleep(Duration::from_millis(2));
                        }
                        // Windows can surface transient accept errors while clients rapidly close
                        // the deliberately short-lived test connections. Keep the listener alive;
                        // `stop` is the sole shutdown signal.
                        Err(_) => thread::sleep(Duration::from_millis(2)),
                    }
                }
            });
            Self {
                address,
                stop,
                requests,
                thread: Some(thread),
            }
        }

        fn endpoint(&self) -> String {
            format!("http://{}", self.address)
        }

        fn requests(&self) -> Vec<TestRequest> {
            self.requests.lock().unwrap().clone()
        }
    }

    impl Drop for TestHttpServer {
        fn drop(&mut self) {
            self.stop.store(true, Ordering::Relaxed);
            let _ = TcpStream::connect(self.address);
            if let Some(thread) = self.thread.take() {
                let _ = thread.join();
            }
        }
    }

    fn read_test_request(stream: &mut TcpStream) -> Result<TestRequest, String> {
        stream
            .set_read_timeout(Some(Duration::from_secs(5)))
            .map_err(|error| format!("timeout: {error}"))?;
        let mut bytes = Vec::new();
        let header_end = loop {
            let mut chunk = [0_u8; 4_096];
            let count = stream
                .read(&mut chunk)
                .map_err(|error| format!("read: {error}"))?;
            if count == 0 {
                return Err("eof".to_owned());
            }
            bytes.extend_from_slice(&chunk[..count]);
            if bytes.len() > 2 * 1024 * 1024 {
                return Err("too large".to_owned());
            }
            if let Some(index) = bytes.windows(4).position(|window| window == b"\r\n\r\n") {
                break index + 4;
            }
        };
        let head = std::str::from_utf8(&bytes[..header_end]).map_err(|_| "utf8".to_owned())?;
        let mut lines = head.split("\r\n");
        let method = lines
            .next()
            .and_then(|line| line.split_ascii_whitespace().next())
            .ok_or_else(|| "request line".to_owned())?
            .to_owned();
        let mut headers = BTreeMap::new();
        for line in lines.filter(|line| !line.is_empty()) {
            let (name, value) = line
                .split_once(':')
                .ok_or_else(|| format!("header: {line:?}"))?;
            headers.insert(name.trim().to_ascii_lowercase(), value.trim().to_owned());
        }
        let content_length = headers
            .get("content-length")
            .and_then(|value| value.parse::<usize>().ok())
            .unwrap_or(0);
        while bytes.len() - header_end < content_length {
            let mut chunk = [0_u8; 4_096];
            let count = stream
                .read(&mut chunk)
                .map_err(|error| format!("body read: {error}"))?;
            if count == 0 {
                return Err("body eof".to_owned());
            }
            bytes.extend_from_slice(&chunk[..count]);
        }
        Ok(TestRequest {
            method,
            headers,
            body: bytes[header_end..header_end + content_length].to_vec(),
        })
    }

    fn write_test_response(stream: &mut TcpStream, response: TestResponse) {
        let reason = match response.status {
            200 => "OK",
            201 => "Created",
            204 => "No Content",
            404 => "Not Found",
            405 => "Method Not Allowed",
            409 => "Conflict",
            412 => "Precondition Failed",
            _ => "Error",
        };
        let mut head = format!(
            "HTTP/1.1 {} {}\r\nContent-Length: {}\r\nConnection: close\r\n",
            response.status,
            reason,
            response.body.len()
        );
        for (name, value) in response.headers {
            head.push_str(&format!("{name}: {value}\r\n"));
        }
        head.push_str("\r\n");
        if stream.write_all(head.as_bytes()).is_err() {
            return;
        }
        if stream.write_all(&response.body).is_err() {
            return;
        }
        let _ = stream.flush();
    }
}
