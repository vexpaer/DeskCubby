use sha2::{Digest, Sha256};

use super::types::CloudSyncError;

pub(crate) fn sha256_hex(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    let mut result = String::with_capacity(64);
    for byte in digest {
        use std::fmt::Write as _;
        let _ = write!(result, "{byte:02x}");
    }
    result
}

pub(crate) fn hmac_sha256(key: &[u8], value: &[u8]) -> [u8; 32] {
    const BLOCK: usize = 64;
    let mut normalized = [0_u8; BLOCK];
    if key.len() > BLOCK {
        normalized[..32].copy_from_slice(&Sha256::digest(key));
    } else {
        normalized[..key.len()].copy_from_slice(key);
    }
    let mut inner_pad = [0x36_u8; BLOCK];
    let mut outer_pad = [0x5c_u8; BLOCK];
    for index in 0..BLOCK {
        inner_pad[index] ^= normalized[index];
        outer_pad[index] ^= normalized[index];
    }
    let mut inner = Sha256::new();
    inner.update(inner_pad);
    inner.update(value);
    let inner_hash = inner.finalize();
    let mut outer = Sha256::new();
    outer.update(outer_pad);
    outer.update(inner_hash);
    let digest = outer.finalize();
    normalized.fill(0);
    inner_pad.fill(0);
    outer_pad.fill(0);
    digest.into()
}

pub(crate) fn standard_base64(bytes: &[u8]) -> String {
    encode_base64(bytes, false, true)
}

pub(crate) fn url_base64_no_pad(bytes: &[u8]) -> String {
    encode_base64(bytes, true, false)
}

pub(crate) fn decode_url_base64_no_pad(value: &str) -> Result<Vec<u8>, CloudSyncError> {
    decode_base64(value, true)
}

fn encode_base64(bytes: &[u8], url_safe: bool, padded: bool) -> String {
    let alphabet = if url_safe {
        b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    } else {
        b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    };
    let mut output = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let first = chunk[0];
        let second = chunk.get(1).copied().unwrap_or(0);
        let third = chunk.get(2).copied().unwrap_or(0);
        output.push(alphabet[(first >> 2) as usize] as char);
        output.push(alphabet[(((first & 0x03) << 4) | (second >> 4)) as usize] as char);
        if chunk.len() >= 2 {
            output.push(alphabet[(((second & 0x0f) << 2) | (third >> 6)) as usize] as char);
        } else if padded {
            output.push('=');
        }
        if chunk.len() == 3 {
            output.push(alphabet[(third & 0x3f) as usize] as char);
        } else if padded {
            output.push('=');
        }
    }
    output
}

fn decode_base64(value: &str, url_safe: bool) -> Result<Vec<u8>, CloudSyncError> {
    if value
        .bytes()
        .any(|byte| byte == b'=' || byte.is_ascii_whitespace())
    {
        return Err(CloudSyncError::invalid_input());
    }
    if value.len() % 4 == 1 {
        return Err(CloudSyncError::invalid_input());
    }
    let mut output = Vec::with_capacity(value.len() / 4 * 3 + 2);
    let mut accumulator = 0_u32;
    let mut bits = 0_u8;
    for byte in value.bytes() {
        let sextet = match byte {
            b'A'..=b'Z' => byte - b'A',
            b'a'..=b'z' => byte - b'a' + 26,
            b'0'..=b'9' => byte - b'0' + 52,
            b'-' if url_safe => 62,
            b'_' if url_safe => 63,
            b'+' if !url_safe => 62,
            b'/' if !url_safe => 63,
            _ => return Err(CloudSyncError::invalid_input()),
        };
        accumulator = (accumulator << 6) | u32::from(sextet);
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            output.push((accumulator >> bits) as u8);
            accumulator &= (1_u32 << bits).saturating_sub(1);
        }
    }
    if accumulator != 0 {
        return Err(CloudSyncError::invalid_input());
    }
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base64_vectors_match_rfc_4648() {
        let vectors = [
            (b"".as_slice(), "", ""),
            (b"f".as_slice(), "Zg==", "Zg"),
            (b"fo".as_slice(), "Zm8=", "Zm8"),
            (b"foo".as_slice(), "Zm9v", "Zm9v"),
            (b"\xfb\xff".as_slice(), "+/8=", "-_8"),
        ];
        for (bytes, standard, url) in vectors {
            assert_eq!(standard_base64(bytes), standard);
            assert_eq!(url_base64_no_pad(bytes), url);
            assert_eq!(decode_url_base64_no_pad(url).unwrap(), bytes);
        }
    }

    #[test]
    fn hmac_matches_rfc_4231_case_one() {
        let key = [0x0b; 20];
        assert_eq!(
            hex::encode(hmac_sha256(&key, b"Hi There")),
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
        );
    }
}
