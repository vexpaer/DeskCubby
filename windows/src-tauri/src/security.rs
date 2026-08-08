//! Security boundary shared by the Windows IPC and persistence layers.
//!
//! Keep the public error payload intentionally small: OS errors can contain a
//! user's directory names and module errors can contain diary text.  Detailed
//! diagnostics therefore stay inside Rust and are never serialized to the web
//! view.

use serde::{Deserialize, Serialize};
use std::ffi::c_void;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

const MAX_SHADOW_BYTES: usize = 10 * 1024 * 1024;
const MAX_DPAPI_PURPOSE_BYTES: usize = 96;
const MAX_DPAPI_OVERHEAD_BYTES: usize = 1024 * 1024;
const CRYPTPROTECT_UI_FORBIDDEN: u32 = 0x1;
const DPAPI_ENTROPY: &[u8] = b"DeskCubby.Windows.BackupShadow.v1";

/// The only error shape exposed over Tauri IPC.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SecurityErrorDto {
    pub code: String,
    pub message: String,
    pub recoverable: bool,
}

impl SecurityErrorDto {
    pub fn new(code: impl Into<String>, message: impl Into<String>, recoverable: bool) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
            recoverable,
        }
    }

    pub fn invalid_input() -> Self {
        Self::new("invalid_input", "The supplied value is not valid.", true)
    }

    pub fn path_not_allowed() -> Self {
        Self::new(
            "path_not_allowed",
            "The requested file is outside the selected directory.",
            true,
        )
    }

    pub fn not_found() -> Self {
        Self::new("not_found", "The requested item no longer exists.", true)
    }

    pub fn conflict() -> Self {
        Self::new(
            "conflict",
            "The file was changed by another application.",
            true,
        )
    }

    pub fn operation_failed() -> Self {
        Self::new(
            "operation_failed",
            "The operation could not be completed.",
            true,
        )
    }

    pub fn storage_unavailable() -> Self {
        Self::new(
            "storage_unavailable",
            "Local storage is temporarily unavailable.",
            true,
        )
    }

    pub fn backup_invalid() -> Self {
        Self::new(
            "backup_invalid",
            "The selected file is not a valid DeskCubby v1-v28 backup.",
            true,
        )
    }

    pub fn network_unavailable() -> Self {
        Self::new(
            "network_unavailable",
            "Daily poetry could not be refreshed.",
            true,
        )
    }
}

pub type CommandResult<T> = Result<T, SecurityErrorDto>;

/// Convert an I/O failure without copying its message (which commonly embeds a
/// private absolute path) into the IPC error.
pub fn map_io_error(error: &io::Error) -> SecurityErrorDto {
    match error.kind() {
        io::ErrorKind::NotFound => SecurityErrorDto::not_found(),
        io::ErrorKind::InvalidInput | io::ErrorKind::InvalidData => {
            SecurityErrorDto::invalid_input()
        }
        _ => SecurityErrorDto::storage_unavailable(),
    }
}

#[derive(Debug)]
pub enum SecurityError {
    InvalidInput,
    PathNotAllowed,
    NotFound,
    Storage,
    Crypto,
    #[cfg(not(windows))]
    Unsupported,
}

impl From<io::Error> for SecurityError {
    fn from(error: io::Error) -> Self {
        match error.kind() {
            io::ErrorKind::NotFound => Self::NotFound,
            io::ErrorKind::InvalidInput | io::ErrorKind::InvalidData => Self::InvalidInput,
            _ => Self::Storage,
        }
    }
}

impl From<SecurityError> for SecurityErrorDto {
    fn from(error: SecurityError) -> Self {
        match error {
            SecurityError::InvalidInput => Self::invalid_input(),
            SecurityError::PathNotAllowed => Self::path_not_allowed(),
            SecurityError::NotFound => Self::not_found(),
            SecurityError::Storage | SecurityError::Crypto => Self::storage_unavailable(),
            #[cfg(not(windows))]
            SecurityError::Unsupported => Self::storage_unavailable(),
        }
    }
}

/// Validate a single Windows filename. Directory separators, NTFS stream
/// syntax, control characters, device names, and ambiguous trailing characters
/// are rejected.
pub fn validate_relative_file_name(
    raw: &str,
    allowed_extensions: &[&str],
) -> Result<String, SecurityError> {
    if raw.is_empty()
        || raw != raw.trim()
        || raw.encode_utf16().count() > 240
        || raw.ends_with(['.', ' '])
        || raw.chars().any(|character| {
            character.is_control()
                || matches!(
                    character,
                    '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*'
                )
        })
    {
        return Err(SecurityError::InvalidInput);
    }

    if raw == "." || raw == ".." || is_reserved_windows_name(raw) {
        return Err(SecurityError::InvalidInput);
    }

    if !allowed_extensions.is_empty() {
        let extension = Path::new(raw)
            .extension()
            .and_then(|value| value.to_str())
            .ok_or(SecurityError::InvalidInput)?;
        if !allowed_extensions
            .iter()
            .any(|allowed| extension.eq_ignore_ascii_case(allowed.trim_start_matches('.')))
        {
            return Err(SecurityError::InvalidInput);
        }
    }

    Ok(raw.to_owned())
}

/// Validate a relative path without consulting the filesystem.
///
/// Both Windows separator forms are accepted, but empty, dot and parent
/// components are rejected. Each component is subsequently checked as a safe
/// Windows filename.
pub fn validate_relative_path(raw: &str) -> Result<PathBuf, SecurityError> {
    if raw.is_empty() || raw.len() > 2048 || raw.starts_with(['/', '\\']) {
        return Err(SecurityError::InvalidInput);
    }

    let components = raw.split(['/', '\\']).collect::<Vec<_>>();
    if components.is_empty() || components.len() > 32 {
        return Err(SecurityError::InvalidInput);
    }

    let mut validated = PathBuf::new();
    for component in components {
        let component = validate_relative_file_name(component, &[])?;
        validated.push(component);
    }
    Ok(validated)
}

/// Resolve a user-controlled relative path beneath a selected root.
///
/// Existing descendants must not be symlinks, junctions, mount points, or
/// other reparse points. Missing final descendants are permitted so callers
/// can safely create a new file after this check.
pub fn resolve_path_beneath(root: &Path, relative: &str) -> Result<PathBuf, SecurityError> {
    let original_root = root;
    let initial_root_metadata = fs::symlink_metadata(original_root)?;
    if !initial_root_metadata.is_dir() || is_reparse_point(&initial_root_metadata) {
        return Err(SecurityError::PathNotAllowed);
    }
    let root = fs::canonicalize(original_root)?;
    let canonical_root_metadata = fs::symlink_metadata(&root)?;
    if !canonical_root_metadata.is_dir() || is_reparse_point(&canonical_root_metadata) {
        return Err(SecurityError::PathNotAllowed);
    }
    let final_original_metadata = fs::symlink_metadata(original_root)?;
    if !final_original_metadata.is_dir() || is_reparse_point(&final_original_metadata) {
        return Err(SecurityError::PathNotAllowed);
    }
    if fs::canonicalize(original_root)? != root {
        return Err(SecurityError::PathNotAllowed);
    }

    let relative = validate_relative_path(relative)?;
    let mut candidate = root.clone();
    for component in relative.components() {
        candidate.push(component.as_os_str());
        match fs::symlink_metadata(&candidate) {
            Ok(metadata) => {
                if is_reparse_point(&metadata) {
                    return Err(SecurityError::PathNotAllowed);
                }
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
    }

    if candidate.exists() {
        let canonical_candidate = fs::canonicalize(&candidate)?;
        if !canonical_candidate.starts_with(&root) {
            return Err(SecurityError::PathNotAllowed);
        }
        Ok(canonical_candidate)
    } else {
        let existing_parent = nearest_existing_parent(&candidate)?;
        let canonical_parent = fs::canonicalize(existing_parent)?;
        if !canonical_parent.starts_with(&root) {
            return Err(SecurityError::PathNotAllowed);
        }
        Ok(candidate)
    }
}

pub fn resolve_existing_file_beneath(
    root: &Path,
    relative: &str,
) -> Result<PathBuf, SecurityError> {
    let path = resolve_path_beneath(root, relative)?;
    let metadata = fs::symlink_metadata(&path)?;
    if !metadata.is_file() || is_reparse_point(&metadata) {
        return Err(SecurityError::PathNotAllowed);
    }
    Ok(path)
}

/// Reject an existing symlink, junction, mount point, or other Windows reparse
/// point without following it. A missing leaf is allowed for safe creation.
pub fn reject_reparse_point(path: &Path) -> Result<(), SecurityError> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if is_reparse_point(&metadata) => Err(SecurityError::PathNotAllowed),
        Ok(_) => Ok(()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
}

/// Open a regular file while instructing Windows not to traverse a reparse
/// point at the final component. The returned handle remains bound to the
/// checked object, closing the usual check-then-open race.
pub fn open_regular_file_no_reparse(path: &Path) -> Result<fs::File, SecurityError> {
    let mut options = fs::OpenOptions::new();
    options.read(true);
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        const FILE_FLAG_OPEN_REPARSE_POINT: u32 = 0x0020_0000;
        options.custom_flags(FILE_FLAG_OPEN_REPARSE_POINT);
    }
    let file = options.open(path)?;
    let metadata = file.metadata()?;
    if !metadata.is_file() || is_reparse_point(&metadata) {
        return Err(SecurityError::PathNotAllowed);
    }
    Ok(file)
}

fn nearest_existing_parent(path: &Path) -> Result<&Path, SecurityError> {
    let mut current = path;
    loop {
        if current.exists() {
            return Ok(current);
        }
        current = current.parent().ok_or(SecurityError::PathNotAllowed)?;
    }
}

fn is_reserved_windows_name(raw: &str) -> bool {
    let stem = raw.split('.').next().unwrap_or(raw);
    let stem = stem.trim_end_matches(['.', ' ']).to_ascii_uppercase();
    matches!(
        stem.as_str(),
        "CON" | "PRN" | "AUX" | "NUL" | "CONIN$" | "CONOUT$"
    ) || stem.strip_prefix("COM").is_some_and(|suffix| {
        matches!(
            suffix,
            "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" | "¹" | "²" | "³"
        )
    }) || stem.strip_prefix("LPT").is_some_and(|suffix| {
        matches!(
            suffix,
            "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" | "¹" | "²" | "³"
        )
    })
}

#[cfg(windows)]
fn is_reparse_point(metadata: &fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;
    const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x400;
    metadata.file_type().is_symlink()
        || metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0
}

#[cfg(not(windows))]
fn is_reparse_point(metadata: &fs::Metadata) -> bool {
    metadata.file_type().is_symlink()
}

/// Encrypt the raw Android compatibility shadow for the current Windows user.
///
/// DPAPI deliberately uses user scope (no `LOCAL_MACHINE` flag), so copying the
/// private database to another account does not expose AI or cloud fields.
#[cfg(windows)]
pub fn dpapi_protect(plaintext: &[u8]) -> Result<Vec<u8>, SecurityError> {
    dpapi_protect_scoped(plaintext, DPAPI_ENTROPY, MAX_SHADOW_BYTES)
}

#[cfg(not(windows))]
pub fn dpapi_protect(_plaintext: &[u8]) -> Result<Vec<u8>, SecurityError> {
    Err(SecurityError::Unsupported)
}

/// Decrypt a compatibility shadow encrypted for the current Windows user.
#[cfg(windows)]
pub fn dpapi_unprotect(ciphertext: &[u8]) -> Result<Vec<u8>, SecurityError> {
    dpapi_unprotect_scoped(ciphertext, DPAPI_ENTROPY, MAX_SHADOW_BYTES)
}

#[cfg(not(windows))]
pub fn dpapi_unprotect(_ciphertext: &[u8]) -> Result<Vec<u8>, SecurityError> {
    Err(SecurityError::Unsupported)
}

/// Encrypt private feature data with a feature-specific DPAPI entropy value.
///
/// Callers must use a stable, non-secret purpose such as
/// `b"DeskCubby.Windows.CloudCredentials.v1"`. Distinct purposes prevent a
/// ciphertext copied from one feature from being accepted by another. The
/// purpose is deliberately bounded and is never persisted beside the
/// ciphertext.
#[cfg(windows)]
pub fn dpapi_protect_scoped(
    plaintext: &[u8],
    purpose: &[u8],
    max_plaintext_bytes: usize,
) -> Result<Vec<u8>, SecurityError> {
    validate_dpapi_scope(plaintext.len(), purpose, max_plaintext_bytes)?;

    let input = DataBlob::from_slice(plaintext)?;
    let entropy = DataBlob::from_slice(purpose)?;
    let mut output = DataBlob::empty();
    let description: Vec<u16> = "DeskCubby private data\0".encode_utf16().collect();

    // SAFETY: all input blobs borrow live slices for the duration of the call;
    // output is initialized by CryptProtectData and owned by LocalAlloc.
    let succeeded = unsafe {
        crypt_protect_data(
            &input,
            description.as_ptr(),
            &entropy,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
    };
    if succeeded == 0 {
        return Err(SecurityError::Crypto);
    }

    copy_and_free_blob(
        output,
        max_plaintext_bytes.saturating_add(MAX_DPAPI_OVERHEAD_BYTES),
    )
}

#[cfg(not(windows))]
pub fn dpapi_protect_scoped(
    _plaintext: &[u8],
    _purpose: &[u8],
    _max_plaintext_bytes: usize,
) -> Result<Vec<u8>, SecurityError> {
    Err(SecurityError::Unsupported)
}

/// Decrypt private feature data using the exact purpose supplied at encryption
/// time. A purpose mismatch is reported only as a generic crypto failure.
#[cfg(windows)]
pub fn dpapi_unprotect_scoped(
    ciphertext: &[u8],
    purpose: &[u8],
    max_plaintext_bytes: usize,
) -> Result<Vec<u8>, SecurityError> {
    validate_dpapi_scope(0, purpose, max_plaintext_bytes)?;
    if ciphertext.is_empty()
        || ciphertext.len() > max_plaintext_bytes.saturating_add(MAX_DPAPI_OVERHEAD_BYTES)
    {
        return Err(SecurityError::InvalidInput);
    }

    let input = DataBlob::from_slice(ciphertext)?;
    let entropy = DataBlob::from_slice(purpose)?;
    let mut output = DataBlob::empty();

    // SAFETY: the borrowed blobs remain live and the output blob is initialized
    // by CryptUnprotectData. We intentionally do not request the description.
    let succeeded = unsafe {
        crypt_unprotect_data(
            &input,
            std::ptr::null_mut(),
            &entropy,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
    };
    if succeeded == 0 {
        return Err(SecurityError::Crypto);
    }

    copy_and_free_blob(output, max_plaintext_bytes)
}

#[cfg(not(windows))]
pub fn dpapi_unprotect_scoped(
    _ciphertext: &[u8],
    _purpose: &[u8],
    _max_plaintext_bytes: usize,
) -> Result<Vec<u8>, SecurityError> {
    Err(SecurityError::Unsupported)
}

fn validate_dpapi_scope(
    plaintext_bytes: usize,
    purpose: &[u8],
    max_plaintext_bytes: usize,
) -> Result<(), SecurityError> {
    if max_plaintext_bytes == 0
        || max_plaintext_bytes > MAX_SHADOW_BYTES
        || plaintext_bytes > max_plaintext_bytes
        || purpose.is_empty()
        || purpose.len() > MAX_DPAPI_PURPOSE_BYTES
        || !purpose.is_ascii()
    {
        return Err(SecurityError::InvalidInput);
    }
    Ok(())
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
            data: std::ptr::null_mut(),
        }
    }

    fn from_slice(value: &[u8]) -> Result<Self, SecurityError> {
        let size = u32::try_from(value.len()).map_err(|_| SecurityError::InvalidInput)?;
        Ok(Self {
            size,
            data: if value.is_empty() {
                std::ptr::null_mut()
            } else {
                value.as_ptr().cast_mut()
            },
        })
    }
}

#[cfg(windows)]
fn copy_and_free_blob(blob: DataBlob, limit: usize) -> Result<Vec<u8>, SecurityError> {
    struct LocalBlob(DataBlob);

    impl Drop for LocalBlob {
        fn drop(&mut self) {
            if !self.0.data.is_null() {
                // Erase DPAPI output before returning its LocalAlloc block.
                // SAFETY: DPAPI supplied a writable allocation of `size` bytes.
                unsafe {
                    std::ptr::write_bytes(self.0.data, 0, self.0.size as usize);
                    let _ = local_free(self.0.data.cast());
                }
            }
        }
    }

    let blob = LocalBlob(blob);
    let size = blob.0.size as usize;
    if size > limit || (size > 0 && blob.0.data.is_null()) {
        return Err(SecurityError::Crypto);
    }

    if size == 0 {
        Ok(Vec::new())
    } else {
        // SAFETY: the guarded DPAPI output remains allocated until after the copy.
        let bytes = unsafe { std::slice::from_raw_parts(blob.0.data, size) };
        Ok(bytes.to_vec())
    }
}

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
    use tempfile::tempdir;

    #[test]
    fn filename_validation_rejects_device_names_and_streams() {
        for invalid in [
            "CON",
            "con.md",
            "LPT9.txt",
            "note.md ",
            "note.",
            "note:secret.md",
            "../note.md",
            "folder/note.md",
        ] {
            assert!(
                validate_relative_file_name(invalid, &["md"]).is_err(),
                "{invalid} should be rejected"
            );
        }
        assert_eq!(
            validate_relative_file_name("2026-07-29 日记.md", &["md"]).unwrap(),
            "2026-07-29 日记.md"
        );
    }

    #[test]
    fn relative_path_rejects_traversal_and_empty_segments() {
        for invalid in [
            "../secret.md",
            "month/../../secret.md",
            "/absolute.md",
            r"C:\absolute.md",
            "month//entry.md",
        ] {
            assert!(
                validate_relative_path(invalid).is_err(),
                "{invalid} should be rejected"
            );
        }
        assert!(validate_relative_path("2026/07/entry.md").is_ok());
    }

    #[test]
    fn path_resolution_stays_beneath_root() {
        let directory = tempdir().unwrap();
        fs::create_dir_all(directory.path().join("2026")).unwrap();
        let result = resolve_path_beneath(directory.path(), "2026/entry.md").unwrap();
        assert!(result.starts_with(fs::canonicalize(directory.path()).unwrap()));
        assert!(resolve_path_beneath(directory.path(), "../entry.md").is_err());
    }

    #[test]
    fn dpapi_scope_rejects_empty_non_ascii_or_oversized_purposes() {
        assert!(validate_dpapi_scope(0, b"", 16).is_err());
        assert!(validate_dpapi_scope(0, &[0xff], 16).is_err());
        assert!(validate_dpapi_scope(0, &[b'a'; 97], 16).is_err());
        assert!(validate_dpapi_scope(17, b"DeskCubby.Test.v1", 16).is_err());
        assert!(validate_dpapi_scope(16, b"DeskCubby.Test.v1", 16).is_ok());
    }

    #[cfg(windows)]
    #[test]
    fn root_directory_symlink_is_rejected() {
        use std::os::windows::fs::symlink_dir;

        let directory = tempdir().unwrap();
        let target = directory.path().join("target");
        let link = directory.path().join("link");
        fs::create_dir(&target).unwrap();
        // Creating symlinks can require Developer Mode on Windows. If the test
        // account lacks that capability, the production check is still covered
        // by the metadata/path tests above.
        if symlink_dir(&target, &link).is_ok() {
            assert!(resolve_path_beneath(&link, "entry.md").is_err());
        }
    }

    #[cfg(unix)]
    #[test]
    fn root_directory_symlink_is_rejected() {
        use std::os::unix::fs::symlink;

        let directory = tempdir().unwrap();
        let target = directory.path().join("target");
        let link = directory.path().join("link");
        fs::create_dir(&target).unwrap();
        symlink(&target, &link).unwrap();
        assert!(resolve_path_beneath(&link, "entry.md").is_err());
    }

    #[cfg(windows)]
    #[test]
    fn dpapi_round_trip_is_user_bound_and_binary_safe() {
        let plaintext = b"{\"apiKey\":\"not logged\",\"unicode\":\"\\xF0\\x9F\\x8C\\xB1\"}";
        let encrypted = dpapi_protect(plaintext).unwrap();
        assert_ne!(encrypted, plaintext);
        assert_eq!(dpapi_unprotect(&encrypted).unwrap(), plaintext);
    }

    #[cfg(windows)]
    #[test]
    fn dpapi_rejects_tampered_payload() {
        let mut encrypted = dpapi_protect(b"private").unwrap();
        let middle = encrypted.len() / 2;
        encrypted[middle] ^= 0x5a;
        assert!(dpapi_unprotect(&encrypted).is_err());
    }

    #[cfg(windows)]
    #[test]
    fn dpapi_scopes_are_not_interchangeable() {
        let encrypted = dpapi_protect_scoped(b"private", b"DeskCubby.Test.One.v1", 64).unwrap();
        assert_eq!(
            dpapi_unprotect_scoped(&encrypted, b"DeskCubby.Test.One.v1", 64).unwrap(),
            b"private"
        );
        assert!(dpapi_unprotect_scoped(&encrypted, b"DeskCubby.Test.Two.v1", 64).is_err());
    }
}
