use std::{ffi::c_void, fmt, ptr};

use reqwest::Url;
use zeroize::Zeroizing;

use super::{
    encoding::sha256_hex,
    types::{
        CloudCredentials, CloudSyncConfig, CloudSyncError, CloudSyncErrorCode, CloudSyncServiceType,
    },
};

const CREDENTIAL_MAGIC: &[u8; 8] = b"DCCRED1\0";
const MAX_SECRET_CHARS: usize = 2_048;
const MAX_PLAINTEXT_BYTES: usize = 48 * 1024;
const MAX_CIPHERTEXT_BYTES: usize = 64 * 1024;
const DPAPI_ENTROPY: &[u8] = b"DeskCubby.Windows.CloudCredentials.v1";

#[derive(Clone, PartialEq, Eq)]
pub struct EncryptedCloudCredentials {
    pub ciphertext: Vec<u8>,
    pub binding_sha256: String,
}

impl fmt::Debug for EncryptedCloudCredentials {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("EncryptedCloudCredentials")
            .field(
                "ciphertext",
                &format_args!("<redacted:{}>", self.ciphertext.len()),
            )
            .field("binding_sha256", &self.binding_sha256)
            .finish()
    }
}

pub fn encrypt_credentials(
    config: &CloudSyncConfig,
    credentials: &CloudCredentials,
) -> Result<EncryptedCloudCredentials, CloudSyncError> {
    if credentials.is_empty() {
        return Err(CloudSyncError::invalid_input());
    }
    let binding_sha256 = secret_binding_sha256(config)?;
    let mut plaintext = serialize_credentials(credentials)?;
    let encrypted = dpapi_protect(&plaintext);
    plaintext.fill(0);
    Ok(EncryptedCloudCredentials {
        ciphertext: encrypted?,
        binding_sha256,
    })
}

pub fn decrypt_credentials(
    config: &CloudSyncConfig,
    encrypted: &EncryptedCloudCredentials,
) -> Result<CloudCredentials, CloudSyncError> {
    let expected = secret_binding_sha256(config)?;
    if !constant_time_equal(expected.as_bytes(), encrypted.binding_sha256.as_bytes()) {
        return Err(CloudSyncError::new(
            CloudSyncErrorCode::InvalidConfiguration,
            "Saved credentials belong to a different cloud configuration.",
            true,
        ));
    }
    let mut plaintext = dpapi_unprotect(&encrypted.ciphertext)?;
    let decoded = deserialize_credentials(&plaintext);
    plaintext.fill(0);
    decoded
}

/// Matches Android's credential binding fields. `remotePath` is intentionally
/// excluded: moving the same account to another application prefix does not
/// expose a credential to another host/account, while synchronization ancestry
/// separately includes the remote path.
pub fn secret_binding_sha256(config: &CloudSyncConfig) -> Result<String, CloudSyncError> {
    let endpoint = Url::parse(config.endpoint_url.trim())
        .map_err(|_| CloudSyncError::invalid_configuration())?;
    if endpoint.host_str().is_none()
        || !endpoint.username().is_empty()
        || endpoint.password().is_some()
        || endpoint.query().is_some()
        || endpoint.fragment().is_some()
    {
        return Err(CloudSyncError::invalid_configuration());
    }
    let mut normalized_endpoint = format!(
        "{}://{}",
        endpoint.scheme().to_ascii_lowercase(),
        endpoint.host_str().unwrap_or_default().to_ascii_lowercase()
    );
    if let Some(port) = endpoint.port() {
        normalized_endpoint.push(':');
        normalized_endpoint.push_str(&port.to_string());
    }
    normalized_endpoint.push_str(if endpoint.path().is_empty() {
        "/"
    } else {
        endpoint.path()
    });
    let binding = match config.service_type {
        CloudSyncServiceType::Webdav => format!(
            "WEBDAV\n{normalized_endpoint}\n{}",
            config.web_dav_username.trim()
        ),
        CloudSyncServiceType::S3Compatible => format!(
            "S3_COMPATIBLE\n{normalized_endpoint}\n{}\n{}",
            config.s3_bucket.trim(),
            config.s3_region.trim()
        ),
    };
    Ok(sha256_hex(binding.as_bytes()))
}

fn serialize_credentials(credentials: &CloudCredentials) -> Result<Vec<u8>, CloudSyncError> {
    let values = [
        &credentials.web_dav_password,
        &credentials.s3_access_key,
        &credentials.s3_secret_key,
        &credentials.s3_session_token,
    ];
    if values
        .iter()
        .any(|value| value.encode_utf16().count() > MAX_SECRET_CHARS)
    {
        return Err(CloudSyncError::invalid_input());
    }
    let capacity =
        CREDENTIAL_MAGIC.len() + values.iter().map(|value| 4 + value.len()).sum::<usize>();
    if capacity > MAX_PLAINTEXT_BYTES {
        return Err(CloudSyncError::limit_exceeded());
    }
    let mut bytes = Vec::with_capacity(capacity);
    bytes.extend_from_slice(CREDENTIAL_MAGIC);
    for value in values {
        let length = u32::try_from(value.len()).map_err(|_| CloudSyncError::limit_exceeded())?;
        bytes.extend_from_slice(&length.to_le_bytes());
        bytes.extend_from_slice(value.as_bytes());
    }
    Ok(bytes)
}

fn deserialize_credentials(bytes: &[u8]) -> Result<CloudCredentials, CloudSyncError> {
    if bytes.len() > MAX_PLAINTEXT_BYTES || !bytes.starts_with(CREDENTIAL_MAGIC) {
        return Err(CloudSyncError::storage());
    }
    let mut cursor = CREDENTIAL_MAGIC.len();
    let mut values = Vec::with_capacity(4);
    for _ in 0..4 {
        let length_bytes = bytes
            .get(cursor..cursor + 4)
            .ok_or_else(CloudSyncError::storage)?;
        cursor += 4;
        let length = u32::from_le_bytes(
            length_bytes
                .try_into()
                .map_err(|_| CloudSyncError::storage())?,
        ) as usize;
        let value = bytes
            .get(cursor..cursor.saturating_add(length))
            .ok_or_else(CloudSyncError::storage)?;
        cursor += length;
        let value = std::str::from_utf8(value)
            .map_err(|_| CloudSyncError::storage())?
            .to_owned();
        if value.encode_utf16().count() > MAX_SECRET_CHARS {
            return Err(CloudSyncError::storage());
        }
        values.push(Zeroizing::new(value));
    }
    if cursor != bytes.len() {
        return Err(CloudSyncError::storage());
    }
    let mut values = values.into_iter();
    let mut next_value = || {
        values
            .next()
            .map(|mut value| std::mem::take(&mut *value))
            .unwrap_or_default()
    };
    Ok(CloudCredentials {
        web_dav_password: next_value(),
        s3_access_key: next_value(),
        s3_secret_key: next_value(),
        s3_session_token: next_value(),
    })
}

fn constant_time_equal(left: &[u8], right: &[u8]) -> bool {
    let mut difference = left.len() ^ right.len();
    for index in 0..left.len().max(right.len()) {
        difference |= usize::from(
            left.get(index).copied().unwrap_or(0) ^ right.get(index).copied().unwrap_or(0),
        );
    }
    difference == 0
}

#[cfg(windows)]
fn dpapi_protect(plaintext: &[u8]) -> Result<Vec<u8>, CloudSyncError> {
    if plaintext.is_empty() || plaintext.len() > MAX_PLAINTEXT_BYTES {
        return Err(CloudSyncError::invalid_input());
    }
    let input = DataBlob::borrowing(plaintext)?;
    let entropy = DataBlob::borrowing(DPAPI_ENTROPY)?;
    let mut output = DataBlob::empty();
    let description = "DeskCubby cloud credentials\0"
        .encode_utf16()
        .collect::<Vec<_>>();
    // SAFETY: input and entropy borrow live slices for this call. Windows
    // initializes output with LocalAlloc-owned memory on success.
    let succeeded = unsafe {
        crypt_protect_data(
            &input,
            description.as_ptr(),
            &entropy,
            ptr::null_mut(),
            ptr::null_mut(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
    };
    if succeeded == 0 {
        return Err(CloudSyncError::storage());
    }
    copy_and_free(output, MAX_CIPHERTEXT_BYTES)
}

#[cfg(not(windows))]
fn dpapi_protect(_plaintext: &[u8]) -> Result<Vec<u8>, CloudSyncError> {
    Err(CloudSyncError::new(
        CloudSyncErrorCode::StorageUnavailable,
        "DPAPI credential protection is available only on Windows.",
        false,
    ))
}

#[cfg(windows)]
fn dpapi_unprotect(ciphertext: &[u8]) -> Result<Vec<u8>, CloudSyncError> {
    if ciphertext.is_empty() || ciphertext.len() > MAX_CIPHERTEXT_BYTES {
        return Err(CloudSyncError::storage());
    }
    let input = DataBlob::borrowing(ciphertext)?;
    let entropy = DataBlob::borrowing(DPAPI_ENTROPY)?;
    let mut output = DataBlob::empty();
    // SAFETY: borrowed buffers remain live, and Windows initializes output.
    let succeeded = unsafe {
        crypt_unprotect_data(
            &input,
            ptr::null_mut(),
            &entropy,
            ptr::null_mut(),
            ptr::null_mut(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
    };
    if succeeded == 0 {
        return Err(CloudSyncError::storage());
    }
    copy_and_free(output, MAX_PLAINTEXT_BYTES)
}

#[cfg(not(windows))]
fn dpapi_unprotect(_ciphertext: &[u8]) -> Result<Vec<u8>, CloudSyncError> {
    Err(CloudSyncError::new(
        CloudSyncErrorCode::StorageUnavailable,
        "DPAPI credential protection is available only on Windows.",
        false,
    ))
}

#[cfg(windows)]
#[repr(C)]
struct DataBlob {
    size: u32,
    data: *mut u8,
}

#[cfg(windows)]
impl DataBlob {
    const fn empty() -> Self {
        Self {
            size: 0,
            data: ptr::null_mut(),
        }
    }

    fn borrowing(bytes: &[u8]) -> Result<Self, CloudSyncError> {
        Ok(Self {
            size: u32::try_from(bytes.len()).map_err(|_| CloudSyncError::limit_exceeded())?,
            data: bytes.as_ptr().cast_mut(),
        })
    }
}

#[cfg(windows)]
fn copy_and_free(blob: DataBlob, maximum: usize) -> Result<Vec<u8>, CloudSyncError> {
    if blob.data.is_null() || blob.size == 0 || blob.size as usize > maximum {
        if !blob.data.is_null() {
            zero_and_free(blob);
        }
        return Err(CloudSyncError::storage());
    }
    // SAFETY: DPAPI returned `size` initialized bytes.
    let copied = unsafe { std::slice::from_raw_parts(blob.data, blob.size as usize).to_vec() };
    zero_and_free(blob);
    Ok(copied)
}

#[cfg(windows)]
fn zero_and_free(blob: DataBlob) {
    // SAFETY: DPAPI returned a writable LocalAlloc buffer of `size` bytes.
    // Volatile writes prevent the plaintext wipe from being optimized away.
    unsafe {
        for offset in 0..blob.size as usize {
            ptr::write_volatile(blob.data.add(offset), 0);
        }
        local_free(blob.data.cast());
    }
}

#[cfg(windows)]
const CRYPTPROTECT_UI_FORBIDDEN: u32 = 0x1;

#[cfg(windows)]
#[link(name = "crypt32")]
unsafe extern "system" {
    #[link_name = "CryptProtectData"]
    fn crypt_protect_data(
        data_in: *const DataBlob,
        data_description: *const u16,
        optional_entropy: *const DataBlob,
        reserved: *mut c_void,
        prompt: *mut c_void,
        flags: u32,
        data_out: *mut DataBlob,
    ) -> i32;

    #[link_name = "CryptUnprotectData"]
    fn crypt_unprotect_data(
        data_in: *const DataBlob,
        data_description: *mut *mut u16,
        optional_entropy: *const DataBlob,
        reserved: *mut c_void,
        prompt: *mut c_void,
        flags: u32,
        data_out: *mut DataBlob,
    ) -> i32;
}

#[cfg(windows)]
#[link(name = "kernel32")]
unsafe extern "system" {
    #[link_name = "LocalFree"]
    fn local_free(memory: *mut c_void) -> *mut c_void;
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config() -> CloudSyncConfig {
        CloudSyncConfig {
            id: "cloud".to_owned(),
            name: "Cloud".to_owned(),
            endpoint_url: "https://EXAMPLE.test/dav".to_owned(),
            web_dav_username: " alice ".to_owned(),
            ..CloudSyncConfig::default()
        }
    }

    #[test]
    fn binary_codec_round_trips_unicode_and_delimiters() {
        let credentials = CloudCredentials {
            web_dav_password: "密\n码\0:".to_owned(),
            s3_access_key: "access".to_owned(),
            s3_secret_key: "secret".to_owned(),
            s3_session_token: "token".to_owned(),
        };
        let mut bytes = serialize_credentials(&credentials).unwrap();
        assert_eq!(deserialize_credentials(&bytes).unwrap(), credentials);
        bytes.fill(0);
    }

    #[test]
    fn binding_changes_with_endpoint_or_account_but_not_remote_path() {
        let first = config();
        let mut moved = first.clone();
        moved.remote_path = "Other".to_owned();
        assert_eq!(
            secret_binding_sha256(&first).unwrap(),
            secret_binding_sha256(&moved).unwrap()
        );
        moved.web_dav_username = "bob".to_owned();
        assert_ne!(
            secret_binding_sha256(&first).unwrap(),
            secret_binding_sha256(&moved).unwrap()
        );
    }

    #[test]
    fn encrypted_debug_never_includes_ciphertext() {
        let encrypted = EncryptedCloudCredentials {
            ciphertext: b"very-secret-ciphertext".to_vec(),
            binding_sha256: "a".repeat(64),
        };
        assert!(!format!("{encrypted:?}").contains("very-secret"));
    }

    #[cfg(windows)]
    #[test]
    fn dpapi_round_trip_is_user_bound_and_tamper_evident() {
        let credentials = CloudCredentials {
            web_dav_password: "private".to_owned(),
            s3_access_key: String::new(),
            s3_secret_key: String::new(),
            s3_session_token: String::new(),
        };
        let mut encrypted = encrypt_credentials(&config(), &credentials).unwrap();
        assert_eq!(
            decrypt_credentials(&config(), &encrypted).unwrap(),
            credentials
        );
        let middle = encrypted.ciphertext.len() / 2;
        encrypted.ciphertext[middle] ^= 0x5a;
        assert!(decrypt_credentials(&config(), &encrypted).is_err());
    }
}
