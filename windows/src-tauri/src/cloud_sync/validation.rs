use std::{collections::BTreeSet, fmt};

use reqwest::Url;

use super::{
    encoding::sha256_hex,
    types::{
        CloudCredentials, CloudSyncConfig, CloudSyncContent, CloudSyncError, CloudSyncServiceType,
    },
};

const MAX_CONFIG_ID_CHARS: usize = 128;
const MAX_CONFIG_NAME_CHARS: usize = 200;
const MAX_ENDPOINT_CHARS: usize = 4_096;
const MAX_REMOTE_PATH_CHARS: usize = 1_024;
const MAX_USERNAME_CHARS: usize = 512;
const MAX_CREDENTIAL_CHARS: usize = 8_192;
const MAX_KEY_CHARS: usize = 2_048;
const MAX_USER_AGENT_CHARS: usize = 512;

#[derive(Clone)]
pub struct ValidatedCloudSyncConfig {
    pub source: CloudSyncConfig,
    pub(crate) endpoint: Url,
    pub remote_path: String,
    pub scope_fingerprint: String,
}

impl fmt::Debug for ValidatedCloudSyncConfig {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("ValidatedCloudSyncConfig")
            .field("source", &self.source)
            .field("endpoint", &"<redacted>")
            .field("remote_path", &self.remote_path)
            .field("scope_fingerprint", &self.scope_fingerprint)
            .finish()
    }
}

pub fn validate_cloud_sync_config(
    config: &CloudSyncConfig,
    credentials: &CloudCredentials,
) -> Result<ValidatedCloudSyncConfig, CloudSyncError> {
    if config.id.trim().is_empty()
        || utf16_len(&config.id) > MAX_CONFIG_ID_CHARS
        || contains_control(&config.id)
        || config.name.trim().is_empty()
        || utf16_len(&config.name) > MAX_CONFIG_NAME_CHARS
        || contains_control(&config.name)
        || !config.enabled
        || config.selected_contents.is_empty()
        || config.user_agent.trim().is_empty()
        || utf16_len(&config.user_agent) > MAX_USER_AGENT_CHARS
        || contains_control(&config.user_agent)
    {
        return Err(CloudSyncError::invalid_configuration());
    }
    if config.endpoint_url.encode_utf16().count() > MAX_ENDPOINT_CHARS {
        return Err(CloudSyncError::invalid_configuration());
    }
    let endpoint = Url::parse(config.endpoint_url.trim())
        .map_err(|_| CloudSyncError::invalid_configuration())?;
    let scheme = endpoint.scheme();
    if scheme != "https" && !(scheme == "http" && config.allow_insecure_http)
        || endpoint.host_str().is_none()
        || !endpoint.username().is_empty()
        || endpoint.password().is_some()
        || endpoint.query().is_some()
        || endpoint.fragment().is_some()
    {
        return Err(CloudSyncError::invalid_configuration());
    }
    let remote_path = normalize_remote_path(&config.remote_path)?;

    if utf16_len(&config.web_dav_username) > MAX_USERNAME_CHARS
        || contains_control(&config.web_dav_username)
        || [
            &credentials.web_dav_password,
            &credentials.s3_access_key,
            &credentials.s3_secret_key,
            &credentials.s3_session_token,
        ]
        .iter()
        .any(|value| utf16_len(value) > MAX_CREDENTIAL_CHARS)
    {
        return Err(CloudSyncError::invalid_configuration());
    }
    match config.service_type {
        CloudSyncServiceType::Webdav => {}
        CloudSyncServiceType::S3Compatible => {
            if config.s3_bucket.trim().is_empty()
                || utf16_len(&config.s3_bucket) > 255
                || config
                    .s3_bucket
                    .chars()
                    .any(|value| value == '/' || value == '\\' || value.is_control())
                || !valid_region(&config.s3_region)
                || (!config.s3_path_style
                    && (!valid_virtual_host_bucket(&config.s3_bucket)
                        || endpoint.host_str().is_some_and(|host| host.contains(':'))))
                || credentials.s3_access_key.is_empty()
                || credentials.s3_secret_key.is_empty()
                || credentials.s3_access_key.contains(['/', ','])
            {
                return Err(CloudSyncError::invalid_configuration());
            }
        }
    }

    let service_name = match config.service_type {
        CloudSyncServiceType::Webdav => "WEBDAV",
        CloudSyncServiceType::S3Compatible => "S3_COMPATIBLE",
    };
    let mut scope = format!(
        "{service_name}\n{}\n{remote_path}\n",
        normalized_endpoint_for_scope(&endpoint)
    );
    match config.service_type {
        CloudSyncServiceType::Webdav => scope.push_str(&config.web_dav_username),
        CloudSyncServiceType::S3Compatible => {
            scope.push_str(config.s3_bucket.trim());
            scope.push('\n');
            scope.push_str(config.s3_region.trim());
            scope.push('\n');
            scope.push_str(if config.s3_path_style {
                "true"
            } else {
                "false"
            });
            scope.push('\n');
            // Only the digest is persisted. Including account identity prevents
            // ancestry from one S3 account being reused after credentials change.
            scope.push_str(&credentials.s3_access_key);
        }
    }
    Ok(ValidatedCloudSyncConfig {
        source: config.clone(),
        endpoint,
        remote_path,
        scope_fingerprint: sha256_hex(scope.as_bytes()),
    })
}

pub fn normalize_remote_path(raw: &str) -> Result<String, CloudSyncError> {
    if utf16_len(raw) > MAX_REMOTE_PATH_CHARS || contains_control(raw) || raw.contains('\\') {
        return Err(CloudSyncError::invalid_configuration());
    }
    let segments = raw
        .trim()
        .trim_matches('/')
        .split('/')
        .filter(|segment| !segment.is_empty())
        .collect::<Vec<_>>();
    if segments
        .iter()
        .any(|segment| *segment == "." || *segment == "..")
    {
        return Err(CloudSyncError::invalid_configuration());
    }
    Ok(segments.join("/"))
}

pub fn require_valid_sync_key(key: &str) -> Result<&str, CloudSyncError> {
    if key.trim().is_empty()
        || utf16_len(key) > MAX_KEY_CHARS
        || key.starts_with('/')
        || key.ends_with('/')
        || key.contains('\\')
        || contains_control(key)
        || key
            .split('/')
            .any(|segment| segment.is_empty() || segment == "." || segment == "..")
    {
        return Err(CloudSyncError::invalid_input());
    }
    Ok(key)
}

pub(crate) fn selected_prefixes(selected: &BTreeSet<CloudSyncContent>) -> BTreeSet<String> {
    selected
        .iter()
        .map(|content| format!("{}/", content.remote_directory()))
        .collect()
}

pub(crate) fn collection_url(config: &ValidatedCloudSyncConfig) -> Result<Url, CloudSyncError> {
    let mut segments = Vec::new();
    if config.source.service_type == CloudSyncServiceType::S3Compatible
        && config.source.s3_path_style
    {
        segments.push(config.source.s3_bucket.trim());
    }
    segments.extend(
        config
            .remote_path
            .split('/')
            .filter(|value| !value.is_empty()),
    );

    let mut endpoint = config.endpoint.clone();
    if config.source.service_type == CloudSyncServiceType::S3Compatible
        && !config.source.s3_path_style
    {
        let bucket = config.source.s3_bucket.trim();
        let endpoint_host = endpoint
            .host_str()
            .ok_or_else(CloudSyncError::invalid_configuration)?;
        if !endpoint_host
            .to_ascii_lowercase()
            .starts_with(&format!("{}.", bucket.to_ascii_lowercase()))
        {
            endpoint
                .set_host(Some(&format!("{bucket}.{endpoint_host}")))
                .map_err(|_| CloudSyncError::invalid_configuration())?;
        }
    }

    let mut collection = endpoint_origin_and_path(&endpoint);
    if !collection.ends_with('/') {
        collection.push('/');
    }
    for segment in segments {
        collection.push_str(&percent_encode_path_segment(segment));
        collection.push('/');
    }
    Url::parse(&collection).map_err(|_| CloudSyncError::invalid_configuration())
}

pub(crate) fn append_storage_name(
    collection: &Url,
    storage_name: &str,
) -> Result<Url, CloudSyncError> {
    if !valid_storage_name(storage_name) {
        return Err(CloudSyncError::invalid_input());
    }
    Url::parse(&format!("{}{storage_name}", collection.as_str()))
        .map_err(|_| CloudSyncError::invalid_input())
}

pub(crate) fn valid_storage_name(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 200
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"._-".contains(&byte))
}

pub(crate) fn valid_hash(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
}

fn endpoint_origin_and_path(endpoint: &Url) -> String {
    let mut result = format!(
        "{}://{}",
        endpoint.scheme(),
        endpoint.host_str().unwrap_or_default()
    );
    if let Some(port) = endpoint.port() {
        result.push(':');
        result.push_str(&port.to_string());
    }
    let path = endpoint.path().trim_end_matches('/');
    if !path.is_empty() && path != "/" {
        result.push_str(path);
    }
    result
}

fn normalized_endpoint_for_scope(endpoint: &Url) -> String {
    endpoint_origin_and_path(endpoint)
}

fn percent_encode_path_segment(value: &str) -> String {
    let mut result = String::with_capacity(value.len());
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    for byte in value.as_bytes() {
        if byte.is_ascii_alphanumeric() || b"-._~".contains(byte) {
            result.push(char::from(*byte));
        } else {
            result.push('%');
            result.push(char::from(HEX[(byte >> 4) as usize]));
            result.push(char::from(HEX[(byte & 0x0f) as usize]));
        }
    }
    result
}

fn valid_region(value: &str) -> bool {
    let bytes = value.as_bytes();
    !bytes.is_empty()
        && bytes.len() <= 128
        && bytes[0].is_ascii_alphanumeric()
        && bytes
            .iter()
            .all(|byte| byte.is_ascii_alphanumeric() || b"._-".contains(byte))
}

fn valid_virtual_host_bucket(value: &str) -> bool {
    let value = value.as_bytes();
    !value.is_empty()
        && value.len() <= 63
        && value.first().is_some_and(u8::is_ascii_alphanumeric)
        && value.last().is_some_and(u8::is_ascii_alphanumeric)
        && value
            .iter()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || b".-".contains(byte))
}

fn contains_control(value: &str) -> bool {
    value.chars().any(char::is_control)
}

fn utf16_len(value: &str) -> usize {
    value.encode_utf16().count()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cloud_sync::CloudSyncDirection;

    fn config() -> CloudSyncConfig {
        CloudSyncConfig {
            id: "test".to_owned(),
            name: "Test".to_owned(),
            endpoint_url: "https://cloud.example/dav".to_owned(),
            selected_contents: [CloudSyncContent::Diaries].into_iter().collect(),
            direction: CloudSyncDirection::TwoWay,
            ..CloudSyncConfig::default()
        }
    }

    #[test]
    fn validates_https_and_normalizes_remote_path() {
        let mut value = config();
        value.remote_path = " /DeskCubby//雪/ ".to_owned();
        let validated = validate_cloud_sync_config(&value, &CloudCredentials::default()).unwrap();
        assert_eq!(validated.remote_path, "DeskCubby/雪");
        assert_eq!(
            collection_url(&validated).unwrap().as_str(),
            "https://cloud.example/dav/DeskCubby/%E9%9B%AA/"
        );
    }

    #[test]
    fn s3_collection_supports_path_and_virtual_host_styles() {
        let value = CloudSyncConfig {
            service_type: CloudSyncServiceType::S3Compatible,
            s3_bucket: "archive".to_owned(),
            s3_region: "auto".to_owned(),
            ..config()
        };
        let credentials = CloudCredentials {
            web_dav_password: String::new(),
            s3_access_key: "access".to_owned(),
            s3_secret_key: "secret".to_owned(),
            s3_session_token: String::new(),
        };
        let validated = validate_cloud_sync_config(&value, &credentials).unwrap();
        assert_eq!(
            collection_url(&validated).unwrap().as_str(),
            "https://cloud.example/dav/archive/DeskCubby/"
        );

        let virtual_host = CloudSyncConfig {
            s3_path_style: false,
            ..value
        };
        let validated = validate_cloud_sync_config(&virtual_host, &credentials).unwrap();
        assert_eq!(
            collection_url(&validated).unwrap().as_str(),
            "https://archive.cloud.example/dav/DeskCubby/"
        );
    }

    #[test]
    fn rejects_implicit_http_userinfo_and_traversal() {
        for endpoint in [
            "http://127.0.0.1/dav",
            "https://user:pass@example.test/dav",
            "https://example.test/dav?secret=yes",
        ] {
            let mut value = config();
            value.endpoint_url = endpoint.to_owned();
            assert!(
                validate_cloud_sync_config(&value, &CloudCredentials::default()).is_err(),
                "{endpoint}"
            );
        }
        assert!(normalize_remote_path("DeskCubby/../other").is_err());
        assert!(require_valid_sync_key("media/../../secret").is_err());
    }

    #[test]
    fn scope_changes_with_s3_account_identity() {
        let value = CloudSyncConfig {
            service_type: CloudSyncServiceType::S3Compatible,
            s3_bucket: "archive".to_owned(),
            ..config()
        };
        let first = CloudCredentials {
            web_dav_password: String::new(),
            s3_access_key: "first".to_owned(),
            s3_secret_key: "secret".to_owned(),
            s3_session_token: String::new(),
        };
        let mut second = first.clone();
        second.s3_access_key = "second".to_owned();
        assert_ne!(
            validate_cloud_sync_config(&value, &first)
                .unwrap()
                .scope_fingerprint,
            validate_cloud_sync_config(&value, &second)
                .unwrap()
                .scope_fingerprint
        );
    }
}
