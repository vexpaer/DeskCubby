//! Release-only verifier for Tauri updater artifacts.
//!
//! Tauri stores both its Minisign public key and generated `.sig` content as
//! base64-encoded Minisign text. This example intentionally mirrors the
//! updater plugin's decoding boundary, then streams the installer through the
//! independent `minisign-verify` implementation before release publication.

use std::{env, ffi::OsString, fs::File, io::Read, path::PathBuf, process::ExitCode};

use base64::{Engine as _, engine::general_purpose::STANDARD};
use minisign_verify::{PublicKey, Signature};

const MAX_PUBLIC_KEY_BASE64_BYTES: usize = 16 * 1024;
const MAX_SIGNATURE_BASE64_BYTES: u64 = 64 * 1024;
const READ_BUFFER_BYTES: usize = 64 * 1024;

fn main() -> ExitCode {
    match verify_from_environment() {
        Ok(()) => {
            println!("Tauri updater signature verification passed.");
            ExitCode::SUCCESS
        }
        Err(()) => {
            eprintln!("Tauri updater signature verification failed.");
            ExitCode::FAILURE
        }
    }
}

fn verify_from_environment() -> Result<(), ()> {
    let (artifact_path, signature_path) = required_paths(env::args_os())?;
    let encoded_public_key = env::var("DESKCUBBY_UPDATER_PUBLIC_KEY").map_err(|_| ())?;
    if encoded_public_key.is_empty() || encoded_public_key.len() > MAX_PUBLIC_KEY_BASE64_BYTES {
        return Err(());
    }

    let public_key_text = decode_utf8_base64(encoded_public_key.trim())?;
    let public_key = PublicKey::decode(&public_key_text).map_err(|_| ())?;

    let signature_metadata = signature_path.metadata().map_err(|_| ())?;
    if signature_metadata.len() == 0 || signature_metadata.len() > MAX_SIGNATURE_BASE64_BYTES {
        return Err(());
    }
    let encoded_signature = std::fs::read_to_string(signature_path).map_err(|_| ())?;
    let signature_text = decode_utf8_base64(encoded_signature.trim())?;
    let signature = Signature::decode(&signature_text).map_err(|_| ())?;

    let mut verifier = public_key.verify_stream(&signature).map_err(|_| ())?;
    let mut artifact = File::open(artifact_path).map_err(|_| ())?;
    let mut buffer = vec![0_u8; READ_BUFFER_BYTES];
    loop {
        let read = artifact.read(&mut buffer).map_err(|_| ())?;
        if read == 0 {
            break;
        }
        verifier.update(&buffer[..read]);
    }
    verifier.finalize().map_err(|_| ())
}

fn required_paths(mut arguments: impl Iterator<Item = OsString>) -> Result<(PathBuf, PathBuf), ()> {
    let _program = arguments.next().ok_or(())?;
    let artifact = arguments.next().map(PathBuf::from).ok_or(())?;
    let signature = arguments.next().map(PathBuf::from).ok_or(())?;
    if arguments.next().is_some() {
        return Err(());
    }
    Ok((artifact, signature))
}

fn decode_utf8_base64(value: &str) -> Result<String, ()> {
    let decoded = STANDARD.decode(value).map_err(|_| ())?;
    String::from_utf8(decoded).map_err(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn requires_exactly_two_paths() {
        let valid = [
            OsString::from("tool"),
            OsString::from("a"),
            OsString::from("b"),
        ];
        assert!(required_paths(valid.into_iter()).is_ok());

        let extra = [
            OsString::from("tool"),
            OsString::from("a"),
            OsString::from("b"),
            OsString::from("c"),
        ];
        assert!(required_paths(extra.into_iter()).is_err());
    }

    #[test]
    fn decodes_utf8_base64_only() {
        assert_eq!(decode_utf8_base64("RGVzY0N1YmJ5").unwrap(), "DeskCubby");
        assert!(decode_utf8_base64("%%%").is_err());
        assert!(decode_utf8_base64("/w==").is_err());
    }
}
