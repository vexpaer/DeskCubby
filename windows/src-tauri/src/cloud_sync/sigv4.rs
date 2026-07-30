use std::{collections::BTreeMap, fmt};

use super::{
    encoding::{hmac_sha256, sha256_hex},
    types::{CloudCredentials, CloudSyncError},
};
use chrono::{DateTime, Utc};
use reqwest::Url;

const ALGORITHM: &str = "AWS4-HMAC-SHA256";
const TERMINATOR: &str = "aws4_request";

pub struct SigV4Signer {
    access_key_id: String,
    secret_access_key: Vec<u8>,
    region: String,
    session_token: Option<String>,
}

impl SigV4Signer {
    pub fn new(credentials: &CloudCredentials, region: &str) -> Result<Self, CloudSyncError> {
        if credentials.s3_access_key.is_empty()
            || credentials.s3_secret_key.is_empty()
            || credentials
                .s3_access_key
                .chars()
                .any(|value| value == '/' || value == ',')
        {
            return Err(CloudSyncError::invalid_configuration());
        }
        let region = region.trim().to_ascii_lowercase();
        if region.is_empty() || region.contains('/') {
            return Err(CloudSyncError::invalid_configuration());
        }
        Ok(Self {
            access_key_id: credentials.s3_access_key.clone(),
            secret_access_key: credentials.s3_secret_key.as_bytes().to_vec(),
            region,
            session_token: (!credentials.s3_session_token.is_empty())
                .then(|| credentials.s3_session_token.clone()),
        })
    }

    #[allow(clippy::too_many_arguments)]
    pub fn sign(
        &self,
        method: &str,
        url: &Url,
        headers: &[(String, String)],
        payload: &[u8],
        precomputed_payload_sha256: Option<&str>,
        timestamp: DateTime<Utc>,
    ) -> Result<SignedHeaders, CloudSyncError> {
        let method = method.trim().to_ascii_uppercase();
        if method.is_empty() || !method.bytes().all(is_header_name_byte) {
            return Err(CloudSyncError::invalid_input());
        }
        let payload_sha256 = match precomputed_payload_sha256 {
            Some(value) if valid_payload_hash(value) => value.to_ascii_lowercase(),
            Some(_) => return Err(CloudSyncError::invalid_input()),
            None => sha256_hex(payload),
        };
        let amz_date = timestamp.format("%Y%m%dT%H%M%SZ").to_string();
        let date = &amz_date[..8];
        let mut normalized = BTreeMap::<String, Vec<String>>::new();
        for (name, value) in headers {
            let name = name.trim().to_ascii_lowercase();
            if name.is_empty() || !name.bytes().all(is_header_name_byte) {
                return Err(CloudSyncError::invalid_input());
            }
            if name != "authorization"
                && name != "host"
                && name != "x-amz-date"
                && name != "x-amz-content-sha256"
                && name != "x-amz-security-token"
            {
                normalized
                    .entry(name)
                    .or_default()
                    .push(normalize_header_value(value));
            }
        }
        normalized.insert("host".to_owned(), vec![canonical_host(url)?]);
        normalized.insert("x-amz-date".to_owned(), vec![amz_date.clone()]);
        normalized.insert(
            "x-amz-content-sha256".to_owned(),
            vec![payload_sha256.clone()],
        );
        if let Some(token) = self.session_token.as_ref() {
            normalized.insert("x-amz-security-token".to_owned(), vec![token.clone()]);
        }
        let flattened = normalized
            .into_iter()
            .map(|(name, values)| (name, values.join(",")))
            .collect::<BTreeMap<_, _>>();
        let signed_names = flattened.keys().cloned().collect::<Vec<_>>().join(";");
        let canonical_headers = flattened
            .iter()
            .map(|(name, value)| format!("{name}:{value}\n"))
            .collect::<String>();
        let canonical_request = format!(
            "{method}\n{}\n{}\n{canonical_headers}\n{signed_names}\n{payload_sha256}",
            canonical_uri(url),
            canonical_query(url)
        );
        let canonical_request_hash = sha256_hex(canonical_request.as_bytes());
        let scope = format!("{date}/{}/s3/{TERMINATOR}", self.region);
        let string_to_sign = format!("{ALGORITHM}\n{amz_date}\n{scope}\n{canonical_request_hash}");
        let mut date_key_input = Vec::with_capacity(4 + self.secret_access_key.len());
        date_key_input.extend_from_slice(b"AWS4");
        date_key_input.extend_from_slice(&self.secret_access_key);
        let mut date_key = hmac_sha256(&date_key_input, date.as_bytes());
        date_key_input.fill(0);
        let mut region_key = hmac_sha256(&date_key, self.region.as_bytes());
        date_key.fill(0);
        let mut service_key = hmac_sha256(&region_key, b"s3");
        region_key.fill(0);
        let mut signing_key = hmac_sha256(&service_key, TERMINATOR.as_bytes());
        service_key.fill(0);
        let signature = hex::encode(hmac_sha256(&signing_key, string_to_sign.as_bytes()));
        signing_key.fill(0);
        let authorization = format!(
            "{ALGORITHM} Credential={}/{scope},SignedHeaders={signed_names},Signature={signature}",
            self.access_key_id
        );

        let mut result = flattened;
        result.insert("authorization".to_owned(), authorization);
        Ok(SignedHeaders {
            headers: result,
            payload_sha256,
            signed_header_names: signed_names,
            canonical_request_hash,
            timestamp: amz_date,
        })
    }
}

impl Drop for SigV4Signer {
    fn drop(&mut self) {
        self.secret_access_key.fill(0);
    }
}

impl fmt::Debug for SigV4Signer {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("SigV4Signer")
            .field("access_key_id", &"<redacted>")
            .field("secret_access_key", &"<redacted>")
            .field("region", &self.region)
            .field("has_session_token", &self.session_token.is_some())
            .finish()
    }
}

pub struct SignedHeaders {
    pub headers: BTreeMap<String, String>,
    pub payload_sha256: String,
    pub signed_header_names: String,
    pub canonical_request_hash: String,
    pub timestamp: String,
}

impl fmt::Debug for SignedHeaders {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("SignedHeaders")
            .field("header_names", &self.headers.keys().collect::<Vec<_>>())
            .field("payload_sha256", &self.payload_sha256)
            .field("signed_header_names", &self.signed_header_names)
            .field("canonical_request_hash", &self.canonical_request_hash)
            .field("timestamp", &self.timestamp)
            .finish()
    }
}

fn canonical_host(url: &Url) -> Result<String, CloudSyncError> {
    let host = url
        .host_str()
        .ok_or_else(CloudSyncError::invalid_configuration)?
        .to_ascii_lowercase();
    let default_port = match url.scheme() {
        "http" => Some(80),
        "https" => Some(443),
        _ => None,
    };
    Ok(match url.port() {
        Some(port) if Some(port) != default_port => format!("{host}:{port}"),
        _ => host,
    })
}

fn canonical_uri(url: &Url) -> String {
    let path = url.path();
    if path.is_empty() {
        return "/".to_owned();
    }
    path.split('/')
        .map(|segment| aws_encode(&percent_decode(segment)))
        .collect::<Vec<_>>()
        .join("/")
}

fn canonical_query(url: &Url) -> String {
    let Some(query) = url.query() else {
        return String::new();
    };
    let mut items = query
        .split('&')
        .map(|parameter| {
            let (name, value) = parameter.split_once('=').unwrap_or((parameter, ""));
            (
                aws_encode(&percent_decode(name)),
                aws_encode(&percent_decode(value)),
            )
        })
        .collect::<Vec<_>>();
    items.sort();
    items
        .into_iter()
        .map(|(name, value)| format!("{name}={value}"))
        .collect::<Vec<_>>()
        .join("&")
}

fn percent_decode(value: &str) -> Vec<u8> {
    let bytes = value.as_bytes();
    let mut output = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'%' && index + 2 < bytes.len() {
            let high = from_hex(bytes[index + 1]);
            let low = from_hex(bytes[index + 2]);
            if let (Some(high), Some(low)) = (high, low) {
                output.push((high << 4) | low);
                index += 3;
                continue;
            }
        }
        output.push(bytes[index]);
        index += 1;
    }
    output
}

fn aws_encode(value: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut output = String::with_capacity(value.len());
    for byte in value {
        if byte.is_ascii_alphanumeric() || b"-._~".contains(byte) {
            output.push(char::from(*byte));
        } else {
            output.push('%');
            output.push(char::from(HEX[(byte >> 4) as usize]));
            output.push(char::from(HEX[(byte & 0x0f) as usize]));
        }
    }
    output
}

fn from_hex(value: u8) -> Option<u8> {
    match value {
        b'0'..=b'9' => Some(value - b'0'),
        b'a'..=b'f' => Some(value - b'a' + 10),
        b'A'..=b'F' => Some(value - b'A' + 10),
        _ => None,
    }
}

fn normalize_header_value(value: &str) -> String {
    value.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn is_header_name_byte(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&byte)
}

fn valid_payload_hash(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}

#[cfg(test)]
mod tests {
    use chrono::TimeZone;

    use super::*;

    fn official_signer() -> SigV4Signer {
        SigV4Signer::new(
            &CloudCredentials {
                web_dav_password: String::new(),
                s3_access_key: "AKIAIOSFODNN7EXAMPLE".to_owned(),
                s3_secret_key: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY".to_owned(),
                s3_session_token: String::new(),
            },
            "us-east-1",
        )
        .unwrap()
    }

    #[test]
    fn matches_official_aws_s3_get_object_vector() {
        let signed = official_signer()
            .sign(
                "GET",
                &Url::parse("https://examplebucket.s3.amazonaws.com/test.txt").unwrap(),
                &[("Range".to_owned(), "bytes=0-9".to_owned())],
                &[],
                None,
                Utc.with_ymd_and_hms(2013, 5, 24, 0, 0, 0).unwrap(),
            )
            .unwrap();
        assert_eq!(
            signed.canonical_request_hash,
            "7344ae5b7ee6c3e7e6b0fe0640412a37625d1fbfff95c48bbb2dc43964946972"
        );
        assert!(signed.headers.get("authorization").unwrap().ends_with(
            "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"
        ));
    }

    #[test]
    fn canonicalizes_duplicate_query_and_encoded_slash() {
        let url =
            Url::parse("https://example.test/snow-%E9%9B%AA/a%2Fb?dup=2&dup=1&plus=a+b&slash=%2f")
                .unwrap();
        assert_eq!(canonical_uri(&url), "/snow-%E9%9B%AA/a%2Fb");
        assert_eq!(canonical_query(&url), "dup=1&dup=2&plus=a%2Bb&slash=%2F");
    }

    #[test]
    fn signed_debug_does_not_expose_authorization_or_session_token() {
        let signer = SigV4Signer::new(
            &CloudCredentials {
                web_dav_password: String::new(),
                s3_access_key: "access-secret-identity".to_owned(),
                s3_secret_key: "secret-material".to_owned(),
                s3_session_token: "session-material".to_owned(),
            },
            "auto",
        )
        .unwrap();
        let signed = signer
            .sign(
                "GET",
                &Url::parse("https://example.test/object").unwrap(),
                &[],
                &[],
                None,
                Utc.with_ymd_and_hms(2026, 7, 29, 0, 0, 0).unwrap(),
            )
            .unwrap();
        let rendered = format!("{signed:?}");
        assert!(!rendered.contains("session-material"));
        assert!(!rendered.contains("access-secret-identity"));
        assert!(!rendered.contains("Authorization"));
    }
}
