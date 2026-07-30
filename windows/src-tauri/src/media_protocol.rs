//! Read-only custom protocol for media stored in the user-selected directory.
//!
//! The webview receives only a URL containing one validated file name. Absolute
//! paths and arbitrary filesystem access never cross the IPC boundary.

use crate::AppState;
use crate::security::{
    open_regular_file_no_reparse, resolve_existing_file_beneath, validate_relative_file_name,
};
use std::io::Read;
use std::path::Path;
use tauri::Manager;
use tauri::http::{Method, Request, Response, StatusCode, header};

const MAX_MEDIA_RESPONSE_BYTES: usize = 16 * 1024 * 1024;
const MEDIA_EXTENSIONS: &[&str] = &["jpg", "jpeg", "png", "webp"];

pub(crate) fn url_for_file_name(file_name: &str) -> Option<String> {
    let file_name = validate_relative_file_name(file_name, MEDIA_EXTENSIONS).ok()?;
    Some(format!(
        "http://media.localhost/{}",
        percent_encode_leaf(file_name.as_bytes())
    ))
}

pub(crate) fn handle<R: tauri::Runtime>(
    context: tauri::UriSchemeContext<'_, R>,
    request: Request<Vec<u8>>,
) -> Response<Vec<u8>> {
    if context.webview_label() != "main" {
        return empty_response(StatusCode::NOT_FOUND);
    }
    if request.method() != Method::GET && request.method() != Method::HEAD {
        return empty_response(StatusCode::METHOD_NOT_ALLOWED);
    }
    if request.uri().query().is_some()
        || !matches!(request.uri().host(), Some("media.localhost" | "localhost"))
    {
        return empty_response(StatusCode::NOT_FOUND);
    }

    let Some(file_name) = decode_request_leaf(request.uri().path()) else {
        return empty_response(StatusCode::NOT_FOUND);
    };
    let Ok(file_name) = validate_relative_file_name(&file_name, MEDIA_EXTENSIONS) else {
        return empty_response(StatusCode::NOT_FOUND);
    };
    let Some(state) = context.app_handle().try_state::<AppState>() else {
        return empty_response(StatusCode::SERVICE_UNAVAILABLE);
    };
    let Ok(paths) = state.database.get_local_paths() else {
        return empty_response(StatusCode::SERVICE_UNAVAILABLE);
    };
    let Some(root) = paths.media_path else {
        return empty_response(StatusCode::NOT_FOUND);
    };
    serve_file(
        Path::new(&root),
        &file_name,
        request.method() == Method::HEAD,
    )
}

fn serve_file(root: &Path, file_name: &str, head_only: bool) -> Response<Vec<u8>> {
    let Ok(path) = resolve_existing_file_beneath(root, file_name) else {
        return empty_response(StatusCode::NOT_FOUND);
    };
    let Ok(file) = open_regular_file_no_reparse(&path) else {
        return empty_response(StatusCode::NOT_FOUND);
    };
    let Ok(metadata) = file.metadata() else {
        return empty_response(StatusCode::NOT_FOUND);
    };
    if metadata.len() > MAX_MEDIA_RESPONSE_BYTES as u64 {
        return empty_response(StatusCode::PAYLOAD_TOO_LARGE);
    }

    let Some(content_type) = content_type_for(&path) else {
        return empty_response(StatusCode::NOT_FOUND);
    };
    let expected_length = metadata.len();
    let body = if head_only {
        Vec::new()
    } else {
        let mut body = Vec::with_capacity(expected_length as usize);
        let mut bounded = file.take((MAX_MEDIA_RESPONSE_BYTES + 1) as u64);
        if bounded.read_to_end(&mut body).is_err() {
            return empty_response(StatusCode::NOT_FOUND);
        }
        if body.len() > MAX_MEDIA_RESPONSE_BYTES {
            return empty_response(StatusCode::PAYLOAD_TOO_LARGE);
        }
        if body.len() as u64 != expected_length {
            return empty_response(StatusCode::CONFLICT);
        }
        body
    };

    response_builder(StatusCode::OK)
        .header(header::CONTENT_TYPE, content_type)
        .header(header::CONTENT_LENGTH, expected_length.to_string())
        .body(body)
        .unwrap_or_else(|_| empty_response(StatusCode::INTERNAL_SERVER_ERROR))
}

fn response_builder(status: StatusCode) -> tauri::http::response::Builder {
    Response::builder()
        .status(status)
        .header(header::CACHE_CONTROL, "private, max-age=300")
        .header("X-Content-Type-Options", "nosniff")
        // Development uses Vite's 127.0.0.1 origin while packaged builds use
        // tauri.localhost, so the image endpoint must explicitly allow either.
        .header("Cross-Origin-Resource-Policy", "cross-origin")
}

fn empty_response(status: StatusCode) -> Response<Vec<u8>> {
    response_builder(status)
        .header(header::CONTENT_LENGTH, "0")
        .body(Vec::new())
        .unwrap_or_else(|_| Response::new(Vec::new()))
}

fn content_type_for(path: &Path) -> Option<&'static str> {
    match path
        .extension()
        .and_then(|value| value.to_str())
        .unwrap_or_default()
        .to_ascii_lowercase()
        .as_str()
    {
        "jpg" | "jpeg" => Some("image/jpeg"),
        "png" => Some("image/png"),
        "webp" => Some("image/webp"),
        _ => None,
    }
}

fn percent_encode_leaf(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut encoded = String::with_capacity(bytes.len());
    for &byte in bytes {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.' | b'~') {
            encoded.push(char::from(byte));
        } else {
            encoded.push('%');
            encoded.push(char::from(HEX[(byte >> 4) as usize]));
            encoded.push(char::from(HEX[(byte & 0x0f) as usize]));
        }
    }
    encoded
}

fn decode_request_leaf(path: &str) -> Option<String> {
    let raw = path.strip_prefix('/')?;
    if raw.is_empty() || raw.len() > 4 * 1024 || raw.contains('/') {
        return None;
    }
    let bytes = raw.as_bytes();
    let mut decoded = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'%' {
            let high = decode_hex(*bytes.get(index + 1)?)?;
            let low = decode_hex(*bytes.get(index + 2)?)?;
            decoded.push((high << 4) | low);
            index += 3;
        } else {
            if !bytes[index].is_ascii() {
                return None;
            }
            decoded.push(bytes[index]);
            index += 1;
        }
    }
    String::from_utf8(decoded).ok()
}

fn decode_hex(value: u8) -> Option<u8> {
    match value {
        b'0'..=b'9' => Some(value - b'0'),
        b'a'..=b'f' => Some(value - b'a' + 10),
        b'A'..=b'F' => Some(value - b'A' + 10),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::tempdir;

    #[test]
    fn media_urls_round_trip_unicode_without_exposing_a_path() {
        let url = url_for_file_name("午餐 01.jpg").expect("valid media name");
        assert_eq!(url, "http://media.localhost/%E5%8D%88%E9%A4%90%2001.jpg");
        let path = url.strip_prefix("http://media.localhost").unwrap();
        assert_eq!(decode_request_leaf(path).as_deref(), Some("午餐 01.jpg"));
    }

    #[test]
    fn request_decoder_rejects_nested_and_malformed_paths() {
        assert!(decode_request_leaf("/../secret.jpg").is_none());
        assert!(decode_request_leaf("/nested%2Fsecret.jpg").is_some());
        assert!(
            validate_relative_file_name(
                &decode_request_leaf("/nested%2Fsecret.jpg").unwrap(),
                MEDIA_EXTENSIONS
            )
            .is_err()
        );
        assert!(decode_request_leaf("/bad%2.jpg").is_none());
        assert!(decode_request_leaf("//server/share.jpg").is_none());
    }

    #[test]
    fn serves_only_bounded_images_with_safe_headers() {
        let root = tempdir().expect("media directory");
        fs::write(root.path().join("photo.png"), b"\x89PNG\r\n\x1a\n")
            .expect("write image fixture");

        let response = serve_file(root.path(), "photo.png", false);
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.body(), b"\x89PNG\r\n\x1a\n");
        assert_eq!(
            response.headers().get(header::CONTENT_TYPE).unwrap(),
            "image/png"
        );
        assert_eq!(
            response.headers().get("X-Content-Type-Options").unwrap(),
            "nosniff"
        );

        let head = serve_file(root.path(), "photo.png", true);
        assert_eq!(head.status(), StatusCode::OK);
        assert!(head.body().is_empty());
        assert_eq!(head.headers().get(header::CONTENT_LENGTH).unwrap(), "8");
    }

    #[test]
    fn rejects_oversized_media_before_reading_it() {
        let root = tempdir().expect("media directory");
        let file = fs::File::create(root.path().join("large.jpg")).expect("create sparse image");
        file.set_len((MAX_MEDIA_RESPONSE_BYTES + 1) as u64)
            .expect("extend sparse image");
        assert_eq!(
            serve_file(root.path(), "large.jpg", false).status(),
            StatusCode::PAYLOAD_TOO_LARGE
        );
    }
}
