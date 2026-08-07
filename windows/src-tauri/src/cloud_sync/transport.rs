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
        let metadata = response_metadata(&response)?;
        let body =
            read_response_bounded(response, max_response_bytes, &self.transfer_budget).await?;
        if let Some(declared) = metadata_declared_size(&metadata, &body)
            && declared != body.len() as u64
        {
            return Err(CloudSyncError::conflict());
        }
        Ok((status, metadata, body))
    }

    fn is_webdav(&self) -> bool {
        matches!(self.authentication, Authentication::WebDavBasic(_))
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
            let etag = match metadata.strong_etag {
                Some(etag) => etag,
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
    size: u64,
    last_modified_millis: i64,
}

fn response_metadata(response: &Response) -> Result<ResponseMetadata, CloudSyncError> {
    let strong_etag = response_strong_etag(response.headers())?;
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
}
