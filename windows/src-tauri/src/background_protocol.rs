//! Read-only protocol for the global background selected in Windows settings.
//!
//! Requests never contain a filesystem path. The handler resolves the current
//! validated setting from SQLite for every request and re-checks the file
//! before reading it.

use crate::AppState;
use crate::security::{open_regular_file_no_reparse, reject_reparse_point};
use std::fs;
use std::io::Read;
use std::path::Path;
use tauri::Manager;
use tauri::http::{Method, Request, Response, StatusCode, header};

const MAX_BACKGROUND_RESPONSE_BYTES: usize = 64 * 1024 * 1024;

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
    if !valid_request_target(&request) {
        return empty_response(StatusCode::NOT_FOUND);
    }

    let Some(state) = context.app_handle().try_state::<AppState>() else {
        return empty_response(StatusCode::SERVICE_UNAVAILABLE);
    };
    let Ok(settings) = state.database.get_managed_settings() else {
        return empty_response(StatusCode::SERVICE_UNAVAILABLE);
    };
    let Some(path) = settings.background_image_path else {
        return empty_response(StatusCode::NOT_FOUND);
    };

    serve_file(Path::new(&path), request.method() == Method::HEAD)
}

fn valid_request_target(request: &Request<Vec<u8>>) -> bool {
    matches!(
        request.uri().host(),
        Some("background.localhost" | "localhost")
    ) && request.uri().path() == "/current"
        && request.uri().query().is_some_and(valid_revision_query)
}

fn valid_revision_query(query: &str) -> bool {
    let Some(revision) = query.strip_prefix("rev=") else {
        return false;
    };
    !revision.is_empty()
        && revision.len() <= 20
        && revision.bytes().all(|byte| byte.is_ascii_digit())
}

fn serve_file(path: &Path, head_only: bool) -> Response<Vec<u8>> {
    if !path.is_absolute() {
        return empty_response(StatusCode::NOT_FOUND);
    }
    let Some(content_type) = content_type_for(path) else {
        return empty_response(StatusCode::NOT_FOUND);
    };
    if reject_reparse_chain(path).is_err() {
        return empty_response(StatusCode::NOT_FOUND);
    }
    let Ok(mut file) = open_regular_file_no_reparse(path) else {
        return empty_response(StatusCode::NOT_FOUND);
    };
    let Ok(before) = file.metadata() else {
        return empty_response(StatusCode::NOT_FOUND);
    };
    let expected_length = before.len();
    if expected_length == 0 {
        return empty_response(StatusCode::NOT_FOUND);
    }
    if expected_length > MAX_BACKGROUND_RESPONSE_BYTES as u64 {
        return empty_response(StatusCode::PAYLOAD_TOO_LARGE);
    }

    let body = if head_only {
        Vec::new()
    } else {
        let mut body = Vec::with_capacity(expected_length as usize);
        let read_result = {
            let mut bounded = (&mut file).take((MAX_BACKGROUND_RESPONSE_BYTES + 1) as u64);
            bounded.read_to_end(&mut body)
        };
        if read_result.is_err() {
            return empty_response(StatusCode::NOT_FOUND);
        }
        if body.len() > MAX_BACKGROUND_RESPONSE_BYTES {
            return empty_response(StatusCode::PAYLOAD_TOO_LARGE);
        }
        body
    };

    let Ok(after) = file.metadata() else {
        return empty_response(StatusCode::CONFLICT);
    };
    if after.len() != expected_length || (!head_only && body.len() as u64 != expected_length) {
        return empty_response(StatusCode::CONFLICT);
    }

    response_builder(StatusCode::OK)
        .header(header::CONTENT_TYPE, content_type)
        .header(header::CONTENT_LENGTH, expected_length.to_string())
        .body(body)
        .unwrap_or_else(|_| empty_response(StatusCode::INTERNAL_SERVER_ERROR))
}

fn reject_reparse_chain(path: &Path) -> Result<(), ()> {
    let mut current = Some(path);
    while let Some(candidate) = current {
        let metadata = fs::symlink_metadata(candidate).map_err(|_| ())?;
        if candidate == path && !metadata.is_file() {
            return Err(());
        }
        reject_reparse_point(candidate).map_err(|_| ())?;
        current = candidate.parent();
    }
    Ok(())
}

fn response_builder(status: StatusCode) -> tauri::http::response::Builder {
    Response::builder()
        .status(status)
        .header(header::CACHE_CONTROL, "no-store")
        .header("X-Content-Type-Options", "nosniff")
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
        "bmp" => Some("image/bmp"),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::tempdir;

    fn request(uri: &str) -> Request<Vec<u8>> {
        Request::builder()
            .method(Method::GET)
            .uri(uri)
            .body(Vec::new())
            .expect("request")
    }

    #[test]
    fn accepts_only_the_path_free_revision_url() {
        assert!(valid_request_target(&request(
            "http://background.localhost/current?rev=42"
        )));
        assert!(!valid_request_target(&request(
            "http://background.localhost/C:/private/photo.png?rev=42"
        )));
        assert!(!valid_request_target(&request(
            "http://background.localhost/current?path=C%3A%5Cprivate.png"
        )));
        assert!(!valid_request_target(&request(
            "http://background.localhost/current?rev=42&extra=1"
        )));
        assert!(!valid_request_target(&request(
            "http://other.localhost/current?rev=42"
        )));
    }

    #[test]
    fn serves_supported_images_with_no_store_headers() {
        let root = tempdir().expect("background directory");
        for (name, content_type) in [
            ("background.png", "image/png"),
            ("background.jpg", "image/jpeg"),
            ("background.webp", "image/webp"),
            ("background.bmp", "image/bmp"),
        ] {
            let path = root.path().join(name);
            fs::write(&path, b"fixture").expect("write image fixture");
            let response = serve_file(&path, false);
            assert_eq!(response.status(), StatusCode::OK);
            assert_eq!(response.body(), b"fixture");
            assert_eq!(
                response.headers().get(header::CONTENT_TYPE).unwrap(),
                content_type
            );
            assert_eq!(
                response.headers().get(header::CACHE_CONTROL).unwrap(),
                "no-store"
            );
            assert_eq!(
                response.headers().get("X-Content-Type-Options").unwrap(),
                "nosniff"
            );
        }
    }

    #[test]
    fn head_is_bodyless_and_unknown_or_oversized_files_are_rejected() {
        let root = tempdir().expect("background directory");
        let image = root.path().join("background.jpeg");
        fs::write(&image, b"fixture").expect("write image fixture");
        let head = serve_file(&image, true);
        assert_eq!(head.status(), StatusCode::OK);
        assert!(head.body().is_empty());
        assert_eq!(head.headers().get(header::CONTENT_LENGTH).unwrap(), "7");

        let text = root.path().join("background.txt");
        fs::write(&text, b"fixture").expect("write text fixture");
        assert_eq!(serve_file(&text, false).status(), StatusCode::NOT_FOUND);

        let large = root.path().join("large.png");
        let file = fs::File::create(&large).expect("create sparse fixture");
        file.set_len((MAX_BACKGROUND_RESPONSE_BYTES + 1) as u64)
            .expect("extend sparse fixture");
        assert_eq!(
            serve_file(&large, false).status(),
            StatusCode::PAYLOAD_TOO_LARGE
        );
    }

    #[cfg(unix)]
    #[test]
    fn rejects_a_symbolic_link_background() {
        use std::os::unix::fs::symlink;

        let root = tempdir().expect("background directory");
        let target = root.path().join("target.png");
        let link = root.path().join("link.png");
        fs::write(&target, b"fixture").expect("write target");
        symlink(&target, &link).expect("create link");
        assert_eq!(serve_file(&link, false).status(), StatusCode::NOT_FOUND);
    }
}
