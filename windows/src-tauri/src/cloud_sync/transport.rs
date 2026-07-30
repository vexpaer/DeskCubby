use std::{fmt, sync::Arc, time::Duration};

use chrono::{DateTime, Utc};
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
const MAX_ETAG_CHARS: usize = 4_096;

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
            .user_agent("DeskCubby-Sync/1")
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
    ) -> Result<(u16, Option<BlobMetadata>, Vec<u8>), CloudSyncError> {
        if !valid_storage_name(storage_name)
            || content_sha256.is_some_and(|value| !valid_hash(value))
        {
            return Err(CloudSyncError::invalid_input());
        }
        let url = append_storage_name(&self.collection, storage_name)?;
        let mut plain_headers = Vec::<(String, String)>::new();
        if body.is_some() {
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
            request = request.body(bytes.to_vec());
        }
        let response = request
            .send()
            .await
            .map_err(|_| CloudSyncError::network())?;
        let status = response.status().as_u16();
        let metadata = response_metadata(&response)?;
        let body = read_response_bounded(response, max_response_bytes).await?;
        if let Some(metadata) = metadata.as_ref()
            && let Some(declared) = metadata_declared_size(metadata, &body)
            && declared != body.len() as u64
        {
            return Err(CloudSyncError::conflict());
        }
        Ok((status, metadata, body))
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
            let mut metadata = metadata.ok_or_else(CloudSyncError::conflict)?;
            if let Some(expected) = expected_etag
                && metadata.etag != expected
            {
                return Err(CloudSyncError::conflict());
            }
            metadata.size = bytes.len() as u64;
            Ok(Some(BlobRead { metadata, bytes }))
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
            if let Some(metadata) = metadata
                && !metadata.etag.is_empty()
            {
                return Ok(BlobMetadata {
                    size: bytes.len() as u64,
                    ..metadata
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

fn response_metadata(response: &Response) -> Result<Option<BlobMetadata>, CloudSyncError> {
    let etag = response
        .headers()
        .get(ETAG)
        .map(|value| {
            value
                .to_str()
                .map_err(|_| CloudSyncError::conflict())
                .and_then(|value| require_safe_etag(value).map(str::to_owned))
        })
        .transpose()?;
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
    Ok(etag.map(|etag| BlobMetadata {
        etag,
        size,
        last_modified_millis,
    }))
}

fn metadata_declared_size(metadata: &BlobMetadata, body: &[u8]) -> Option<u64> {
    (metadata.size != 0 || body.is_empty()).then_some(metadata.size)
}

async fn read_response_bounded(
    mut response: Response,
    maximum: u64,
) -> Result<Vec<u8>, CloudSyncError> {
    if response
        .content_length()
        .is_some_and(|value| value > maximum)
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
        bytes.extend_from_slice(&chunk);
    }
    Ok(bytes)
}

fn require_safe_etag(value: &str) -> Result<&str, CloudSyncError> {
    if value.is_empty()
        || value.len() > MAX_ETAG_CHARS
        || value.contains(['\r', '\n'])
        || value
            .get(..2)
            .is_some_and(|prefix| prefix.eq_ignore_ascii_case("W/"))
    {
        return Err(CloudSyncError::new(
            CloudSyncErrorCode::UnsupportedRemote,
            "The cloud service did not provide a usable strong ETag.",
            true,
        ));
    }
    Ok(value)
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
