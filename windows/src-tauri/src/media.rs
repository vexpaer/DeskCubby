use crate::diary::{load_diary, validate_directory as validate_diary_directory};
use crate::models::MealPhotoFilter;
use chrono::{DateTime, Local, NaiveDate};
use image::codecs::jpeg::JpegEncoder;
use image::imageops::FilterType;
use image::{DynamicImage, GenericImage, ImageBuffer, ImageFormat, Rgb, RgbImage};
use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::fs::{self, File, OpenOptions};
use std::io::{self, Cursor, Read, Write};
use std::path::{Component, Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use thiserror::Error;
use uuid::Uuid;

const MEDIA_META_FILE_NAME: &str = "dc-media.json";
const LEGACY_MEDIA_META_FILE_NAME: &str = "deskcubby-media.json";
const MAX_MEDIA_META_BYTES: u64 = 2 * 1024 * 1024;
const MAX_SOURCE_IMAGE_BYTES: u64 = 64 * 1024 * 1024;
const MAX_SOURCE_IMAGE_PIXELS: u64 = 100_000_000;
const MAX_COMPRESSED_EDGE: u32 = 2_560;
const MAX_EXPORT_PIXELS: u64 = 60_000_000;
const MAX_EXPORT_HEIGHT: u32 = 32_000;
const EXPORT_MARGIN: u32 = 24;
const EXPORT_GAP: u32 = 10;
const DAY_HEADER_HEIGHT: u32 = 44;
const CAPTION_HEIGHT: u32 = 30;

static MEDIA_MUTEX: Mutex<()> = Mutex::new(());
static MARKDOWN_IMAGE_RE: OnceLock<Regex> = OnceLock::new();
static ENERGY_SUFFIX_RE: OnceLock<Regex> = OnceLock::new();
static DATE_RE: OnceLock<Regex> = OnceLock::new();

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MealCategory {
    Breakfast,
    Lunch,
    AfternoonTea,
    Dinner,
    Fruit,
    LateSnack,
}

impl MealCategory {
    pub fn key(self) -> &'static str {
        match self {
            Self::Breakfast => "breakfast",
            Self::Lunch => "lunch",
            Self::AfternoonTea => "afternoon_tea",
            Self::Dinner => "dinner",
            Self::Fruit => "fruit",
            Self::LateSnack => "late_snack",
        }
    }

    pub fn sort_order(self) -> u8 {
        match self {
            Self::Breakfast => 0,
            Self::Lunch => 1,
            Self::AfternoonTea => 2,
            Self::Dinner => 3,
            Self::Fruit => 4,
            Self::LateSnack => 5,
        }
    }

    fn chinese_label(self) -> &'static str {
        match self {
            Self::Breakfast => "早餐",
            Self::Lunch => "午餐",
            Self::AfternoonTea => "下午茶",
            Self::Dinner => "晚餐",
            Self::Fruit => "水果",
            Self::LateSnack => "夜宵",
        }
    }

    fn english_label(self) -> &'static str {
        match self {
            Self::Breakfast => "breakfast",
            Self::Lunch => "lunch",
            Self::AfternoonTea => "afternoon tea",
            Self::Dinner => "dinner",
            Self::Fruit => "fruit",
            Self::LateSnack => "late snack",
        }
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MealPhotosPerRow {
    Two,
    Three,
    #[default]
    Smart,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaMetaEntry {
    #[serde(default)]
    pub energy_kj: Option<i64>,
    #[serde(default)]
    pub latitude: Option<f64>,
    #[serde(default)]
    pub longitude: Option<f64>,
    #[serde(default)]
    pub place: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportImageOptions {
    #[serde(default)]
    pub category: Option<MealCategory>,
    pub date_iso: String,
    #[serde(default = "default_image_name_pattern")]
    pub image_name_pattern: String,
    #[serde(default = "default_true")]
    pub compress: bool,
    #[serde(default = "default_quality")]
    pub quality: u8,
    #[serde(default = "default_max_edge")]
    pub max_edge: u32,
    #[serde(default)]
    pub capture_gps: bool,
}

impl Default for ImportImageOptions {
    fn default() -> Self {
        Self {
            category: None,
            date_iso: Local::now().date_naive().format("%Y-%m-%d").to_string(),
            image_name_pattern: default_image_name_pattern(),
            compress: true,
            quality: default_quality(),
            max_edge: default_max_edge(),
            capture_gps: false,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportedMedia {
    pub file_name: String,
    pub markdown: String,
    pub width: u32,
    pub height: u32,
    pub compressed: bool,
    #[serde(default)]
    pub metadata: Option<MediaMetaEntry>,
    #[serde(default)]
    pub warnings: Vec<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MealScanOptions {
    #[serde(default)]
    pub categories: Vec<MealCategory>,
    #[serde(default)]
    pub start_date: Option<String>,
    #[serde(default)]
    pub end_date: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MealPhoto {
    pub file_name: String,
    pub caption: String,
    pub category: MealCategory,
    pub diary_file_name: String,
    pub markdown: String,
    pub exists: bool,
    #[serde(default)]
    pub energy_kj: Option<i64>,
    #[serde(default)]
    pub location_name: Option<String>,
    #[serde(skip)]
    appearance_order: usize,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MealCalendarDay {
    pub date_iso: String,
    pub photos: Vec<MealPhoto>,
    #[serde(default)]
    pub total_energy_kj: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MealExportOptions {
    #[serde(default = "default_export_width")]
    pub width: u32,
    #[serde(default)]
    pub photos_per_row: MealPhotosPerRow,
    #[serde(default = "default_true")]
    pub show_captions: bool,
    #[serde(default)]
    pub filter: MealPhotoFilter,
    #[serde(default = "default_export_background")]
    pub background_rgb: [u8; 3],
}

impl Default for MealExportOptions {
    fn default() -> Self {
        Self {
            width: default_export_width(),
            photos_per_row: MealPhotosPerRow::Smart,
            show_captions: true,
            filter: MealPhotoFilter::default(),
            background_rgb: default_export_background(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MealExportResult {
    pub width: u32,
    pub height: u32,
    pub day_count: usize,
    pub photo_count: usize,
    pub size: u64,
    pub sha256: String,
}

#[derive(Debug, Clone, Error, Serialize, Deserialize)]
#[error("{code}: {message}")]
#[serde(rename_all = "camelCase")]
pub struct MediaError {
    pub code: String,
    pub message: String,
}

impl MediaError {
    fn new(code: &str, message: impl Into<String>) -> Self {
        Self {
            code: code.to_owned(),
            message: message.into(),
        }
    }

    fn io(operation: &str, error: io::Error) -> Self {
        Self::new("MEDIA_IO_ERROR", format!("{operation}: {error}"))
    }
}

pub fn validate_media_directory(root: &Path) -> Result<PathBuf, MediaError> {
    let metadata = fs::symlink_metadata(root)
        .map_err(|error| MediaError::io("read media directory", error))?;
    if !metadata.is_dir() || is_reparse_point(&metadata) {
        return Err(MediaError::new(
            "MEDIA_DIRECTORY_INVALID",
            "The selected media directory must be a regular directory, not a symlink or junction.",
        ));
    }
    fs::canonicalize(root).map_err(|error| MediaError::io("canonicalize media directory", error))
}

pub fn import_image(
    media_root: &Path,
    source_path: &Path,
    options: &ImportImageOptions,
) -> Result<ImportedMedia, MediaError> {
    let _guard = MEDIA_MUTEX
        .lock()
        .map_err(|_| MediaError::new("MEDIA_LOCK_POISONED", "Media writer lock is unavailable."))?;
    let media_root = validate_media_directory(media_root)?;
    let source = validate_source_image(source_path)?;
    let date = NaiveDate::parse_from_str(&options.date_iso, "%Y-%m-%d")
        .map_err(|_| MediaError::new("MEDIA_DATE_INVALID", "Image date must use YYYY-MM-DD."))?;
    let source_metadata =
        fs::metadata(&source).map_err(|error| MediaError::io("read source image", error))?;
    if source_metadata.len() > MAX_SOURCE_IMAGE_BYTES {
        return Err(MediaError::new(
            "MEDIA_SOURCE_TOO_LARGE",
            format!("The selected image exceeds {MAX_SOURCE_IMAGE_BYTES} bytes."),
        ));
    }

    let format = image::ImageReader::open(&source)
        .map_err(|error| MediaError::io("open source image", error))?
        .with_guessed_format()
        .map_err(|error| MediaError::new("MEDIA_IMAGE_INVALID", error.to_string()))?
        .format()
        .ok_or_else(|| MediaError::new("MEDIA_IMAGE_INVALID", "Unknown image format."))?;
    if !matches!(
        format,
        ImageFormat::Jpeg | ImageFormat::Png | ImageFormat::WebP
    ) {
        return Err(MediaError::new(
            "MEDIA_IMAGE_UNSUPPORTED",
            "Only JPEG, PNG, and WebP images are supported.",
        ));
    }
    let (source_width, source_height) = image::image_dimensions(&source)
        .map_err(|error| MediaError::new("MEDIA_IMAGE_INVALID", error.to_string()))?;
    ensure_pixel_limit(source_width, source_height, MAX_SOURCE_IMAGE_PIXELS)?;

    let exif = read_jpeg_exif(&source).unwrap_or_default();
    let should_compress = options.compress && options.category.is_some();
    let (encoded, extension, width, height, compressed) = if should_compress {
        let decoded = image::open(&source)
            .map_err(|error| MediaError::new("MEDIA_IMAGE_INVALID", error.to_string()))?;
        let oriented = apply_orientation(decoded, exif.orientation);
        let edge = options.max_edge.clamp(256, MAX_COMPRESSED_EDGE);
        let resized = if oriented.width() > edge || oriented.height() > edge {
            oriented.resize(edge, edge, FilterType::Lanczos3)
        } else {
            oriented
        };
        let width = resized.width();
        let height = resized.height();
        let rgb = DynamicImage::ImageRgb8(resized.to_rgb8());
        let mut encoded = Vec::new();
        JpegEncoder::new_with_quality(&mut encoded, options.quality.clamp(30, 95))
            .encode_image(&rgb)
            .map_err(|error| MediaError::new("MEDIA_ENCODE_FAILED", error.to_string()))?;
        (encoded, "jpg".to_owned(), width, height, true)
    } else {
        let bytes = read_bounded(&source, MAX_SOURCE_IMAGE_BYTES)?;
        (
            bytes,
            extension_for_format(format).to_owned(),
            source_width,
            source_height,
            false,
        )
    };

    let category_key = options.category.map(MealCategory::key).unwrap_or("image");
    let file_name = next_media_file_name(
        &media_root,
        &options.image_name_pattern,
        &date.format("%Y-%m-%d").to_string(),
        category_key,
        &extension,
    )?;
    let destination = new_media_leaf(&media_root, &file_name)?;
    atomic_write_new(&destination, &encoded)?;

    let metadata = if options.capture_gps {
        exif.gps.map(|(latitude, longitude)| MediaMetaEntry {
            energy_kj: None,
            latitude: Some(latitude),
            longitude: Some(longitude),
            place: None,
        })
    } else {
        None
    };
    let mut warnings = Vec::new();
    if let Some(entry) = metadata.as_ref() {
        if let Err(error) = set_media_metadata_unlocked(&media_root, &file_name, entry) {
            warnings.push(format!("MEDIA_METADATA_WRITE_FAILED: {}", error.message));
        }
    }

    let caption = options
        .category
        .map(MealCategory::chinese_label)
        .unwrap_or("图片");
    Ok(ImportedMedia {
        markdown: format!("![{caption}](<{}>)", file_name.replace('>', "%3E")),
        file_name,
        width,
        height,
        compressed,
        metadata,
        warnings,
    })
}

/// Removes a media file that was just imported but could not be committed to a
/// diary. The caller supplies only the returned leaf name; sidecar files are
/// explicitly excluded so rollback can never erase shared metadata.
pub fn remove_imported_media(media_root: &Path, file_name: &str) -> Result<(), MediaError> {
    let _guard = MEDIA_MUTEX
        .lock()
        .map_err(|_| MediaError::new("MEDIA_LOCK_POISONED", "Media writer lock is unavailable."))?;
    let root = validate_media_directory(media_root)?;
    if file_name.eq_ignore_ascii_case(MEDIA_META_FILE_NAME)
        || file_name.eq_ignore_ascii_case(LEGACY_MEDIA_META_FILE_NAME)
    {
        return Err(MediaError::new(
            "MEDIA_NAME_INVALID",
            "Media metadata files cannot be removed by import rollback.",
        ));
    }
    let target = existing_media_leaf(&root, file_name)?;
    fs::remove_file(&target).map_err(|error| MediaError::io("roll back imported media", error))?;
    if target.exists() {
        return Err(MediaError::new(
            "MEDIA_ROLLBACK_VERIFY_FAILED",
            "The imported media still exists after rollback.",
        ));
    }
    Ok(())
}

pub fn read_media_metadata(
    media_root: &Path,
) -> Result<BTreeMap<String, MediaMetaEntry>, MediaError> {
    let _guard = MEDIA_MUTEX
        .lock()
        .map_err(|_| MediaError::new("MEDIA_LOCK_POISONED", "Media reader lock is unavailable."))?;
    let root = validate_media_directory(media_root)?;
    read_media_metadata_unlocked(&root)
}

#[allow(dead_code)]
pub fn set_media_metadata(
    media_root: &Path,
    file_name: &str,
    entry: &MediaMetaEntry,
) -> Result<(), MediaError> {
    let _guard = MEDIA_MUTEX
        .lock()
        .map_err(|_| MediaError::new("MEDIA_LOCK_POISONED", "Media writer lock is unavailable."))?;
    let root = validate_media_directory(media_root)?;
    set_media_metadata_unlocked(&root, file_name, entry)
}

pub fn scan_meal_calendar(
    diary_root: &Path,
    media_root: &Path,
    options: &MealScanOptions,
) -> Result<Vec<MealCalendarDay>, MediaError> {
    let diary_root = validate_diary_directory(diary_root)
        .map_err(|error| MediaError::new(error.code.as_str(), error.message))?;
    let media_root = validate_media_directory(media_root)?;
    let metadata = read_media_metadata(&media_root)?;
    let media_names = media_name_map(&media_root)?;
    let category_filter = options.categories.iter().copied().collect::<HashSet<_>>();
    let start_date = parse_optional_date(options.start_date.as_deref())?;
    let end_date = parse_optional_date(options.end_date.as_deref())?;
    if matches!((start_date, end_date), (Some(start), Some(end)) if start > end) {
        return Err(MediaError::new(
            "MEAL_DATE_RANGE_INVALID",
            "Meal calendar start date must not be after end date.",
        ));
    }

    let image_re = MARKDOWN_IMAGE_RE.get_or_init(|| {
        Regex::new(
            r#"!\[([^\]\r\n]*)\]\(\s*(?:<([^>\r\n]+)>|([^\s)\r\n]+))(?:\s+(?:"[^"\r\n]*"|'[^'\r\n]*'|\([^\)\r\n]*\)))?\s*\)"#,
        )
        .expect("constant Markdown image regex")
    });
    let mut by_date: BTreeMap<String, Vec<MealPhoto>> = BTreeMap::new();
    let entries =
        fs::read_dir(&diary_root).map_err(|error| MediaError::io("enumerate diaries", error))?;
    for entry in entries {
        let entry = entry.map_err(|error| MediaError::io("read diary entry", error))?;
        let diary_name = entry.file_name().to_string_lossy().into_owned();
        if !diary_name.to_ascii_lowercase().ends_with(".md") {
            continue;
        }
        let editor = match load_diary(&diary_root, &diary_name) {
            Ok(value) => value,
            Err(_) => continue,
        };
        let date = date_from_name_or_modified(&diary_name, editor.version.modified_at);
        if start_date.is_some_and(|start| date < start) || end_date.is_some_and(|end| date > end) {
            continue;
        }
        let date_iso = date.format("%Y-%m-%d").to_string();
        for (appearance_order, captures) in image_re.captures_iter(&editor.content).enumerate() {
            let caption = captures
                .get(1)
                .map(|value| value.as_str())
                .unwrap_or("")
                .trim();
            let target = captures
                .get(2)
                .or_else(|| captures.get(3))
                .map(|value| value.as_str())
                .unwrap_or("");
            let file_name = target_file_name(target).unwrap_or_default();
            let category =
                category_from_caption(caption).or_else(|| category_from_file_name(&file_name));
            let Some(category) = category else {
                continue;
            };
            if !category_filter.is_empty() && !category_filter.contains(&category) {
                continue;
            }
            let normalized_name = file_name.to_lowercase();
            let actual_name = media_names.get(&normalized_name).cloned();
            let entry = metadata.get(&normalized_name);
            let energy_kj = entry
                .and_then(|value| value.energy_kj)
                .or_else(|| energy_from_caption(caption));
            let location_name = entry.and_then(media_meta_location);
            by_date
                .entry(date_iso.clone())
                .or_default()
                .push(MealPhoto {
                    file_name: actual_name.clone().unwrap_or(file_name),
                    caption: caption.to_owned(),
                    category,
                    diary_file_name: diary_name.clone(),
                    markdown: captures
                        .get(0)
                        .map(|value| value.as_str())
                        .unwrap_or("")
                        .to_owned(),
                    exists: actual_name.is_some(),
                    energy_kj,
                    location_name,
                    appearance_order,
                });
        }
    }

    let mut days = by_date
        .into_iter()
        .map(|(date_iso, mut photos)| {
            photos.sort_by_key(|photo| (photo.category.sort_order(), photo.appearance_order));
            let energies = photos
                .iter()
                .filter_map(|photo| photo.energy_kj)
                .collect::<Vec<_>>();
            MealCalendarDay {
                date_iso,
                photos,
                total_energy_kj: (!energies.is_empty()).then(|| energies.iter().sum()),
            }
        })
        .collect::<Vec<_>>();
    days.sort_by(|left, right| right.date_iso.cmp(&left.date_iso));
    Ok(days)
}

pub fn meal_photo_row_sizes(count: usize, mode: MealPhotosPerRow) -> Vec<usize> {
    if count == 0 {
        return Vec::new();
    }
    match mode {
        MealPhotosPerRow::Two => {
            let mut rows = vec![2; count / 2];
            if count % 2 == 1 {
                rows.push(1);
            }
            rows
        }
        MealPhotosPerRow::Three => {
            let mut rows = vec![3; count / 3];
            if count % 3 != 0 {
                rows.push(count % 3);
            }
            rows
        }
        MealPhotosPerRow::Smart => match count {
            1 => vec![1],
            value if value % 3 == 0 => vec![3; value / 3],
            value if value % 3 == 1 => {
                let mut rows = vec![3; (value - 4) / 3];
                rows.extend([2, 2]);
                rows
            }
            value => {
                let mut rows = vec![3; value / 3];
                rows.push(2);
                rows
            }
        },
    }
}

pub fn export_meal_calendar_png(
    media_root: &Path,
    destination: &Path,
    days: &[MealCalendarDay],
    options: &MealExportOptions,
) -> Result<MealExportResult, MediaError> {
    if days.is_empty() {
        return Err(MediaError::new(
            "MEAL_EXPORT_EMPTY",
            "There are no meal photos in the selected range.",
        ));
    }
    let media_root = validate_media_directory(media_root)?;
    let width = options.width.clamp(480, 2_048);
    let content_width = width
        .checked_sub(EXPORT_MARGIN * 2)
        .ok_or_else(|| MediaError::new("MEAL_EXPORT_SIZE_INVALID", "Export width is too small."))?;
    let caption_height = if options.show_captions {
        CAPTION_HEIGHT
    } else {
        0
    };
    let mut height = EXPORT_MARGIN;
    let mut layouts = Vec::new();
    for day in days {
        let rows = meal_photo_row_sizes(day.photos.len(), options.photos_per_row);
        height = height
            .checked_add(DAY_HEADER_HEIGHT)
            .ok_or_else(export_size_error)?;
        let mut row_layouts = Vec::new();
        for row_size in rows {
            let cell_width = (content_width - EXPORT_GAP * (row_size.saturating_sub(1) as u32))
                / row_size as u32;
            let image_height = cell_width.saturating_mul(3) / 4;
            let row_height = image_height
                .checked_add(caption_height)
                .ok_or_else(export_size_error)?;
            height = height
                .checked_add(row_height)
                .and_then(|value| value.checked_add(EXPORT_GAP))
                .ok_or_else(export_size_error)?;
            row_layouts.push((row_size, cell_width, image_height, row_height));
        }
        layouts.push(row_layouts);
    }
    height = height
        .checked_add(EXPORT_MARGIN.saturating_sub(EXPORT_GAP))
        .ok_or_else(export_size_error)?;
    ensure_export_size(width, height)?;

    let mut canvas = ImageBuffer::from_pixel(width, height, Rgb(options.background_rgb));
    let mut y = EXPORT_MARGIN;
    let mut photo_count = 0_usize;
    for (day, row_layouts) in days.iter().zip(layouts.iter()) {
        fill_rect(
            &mut canvas,
            EXPORT_MARGIN,
            y,
            content_width,
            DAY_HEADER_HEIGHT.saturating_sub(6),
            Rgb([57, 87, 67]),
        );
        draw_text_5x7(
            &mut canvas,
            EXPORT_MARGIN + 12,
            y + 10,
            &day.date_iso,
            3,
            Rgb([245, 248, 244]),
        );
        y += DAY_HEADER_HEIGHT;
        let mut photo_index = 0_usize;
        for (row_size, cell_width, image_height, row_height) in row_layouts {
            for column in 0..*row_size {
                let x = EXPORT_MARGIN + column as u32 * (*cell_width + EXPORT_GAP);
                let photo = &day.photos[photo_index];
                draw_photo_cell(
                    &mut canvas,
                    &media_root,
                    photo,
                    PhotoCellRect {
                        x,
                        y,
                        width: *cell_width,
                        image_height: *image_height,
                    },
                    options,
                );
                photo_index += 1;
                photo_count += 1;
            }
            y += *row_height + EXPORT_GAP;
        }
    }

    let mut encoded = Cursor::new(Vec::new());
    DynamicImage::ImageRgb8(canvas)
        .write_to(&mut encoded, ImageFormat::Png)
        .map_err(|error| MediaError::new("MEAL_EXPORT_ENCODE_FAILED", error.to_string()))?;
    let encoded = encoded.into_inner();
    validate_export_destination(destination)?;
    atomic_write_replace(destination, &encoded)?;
    let actual = read_bounded(destination, encoded.len() as u64 + 1)?;
    if actual != encoded {
        return Err(MediaError::new(
            "MEAL_EXPORT_VERIFY_FAILED",
            "The exported PNG did not match after writing.",
        ));
    }
    Ok(MealExportResult {
        width,
        height,
        day_count: days.len(),
        photo_count,
        size: actual.len() as u64,
        sha256: hex::encode(Sha256::digest(&actual)),
    })
}

#[derive(Clone, Copy)]
struct PhotoCellRect {
    x: u32,
    y: u32,
    width: u32,
    image_height: u32,
}

fn draw_photo_cell(
    canvas: &mut RgbImage,
    media_root: &Path,
    photo: &MealPhoto,
    rect: PhotoCellRect,
    options: &MealExportOptions,
) {
    let PhotoCellRect {
        x,
        y,
        width,
        image_height,
    } = rect;
    fill_rect(canvas, x, y, width, image_height, Rgb([213, 218, 212]));
    let image = if photo.exists {
        existing_media_leaf(media_root, &photo.file_name)
            .ok()
            .and_then(|path| image::image_dimensions(&path).ok().map(|size| (path, size)))
            .filter(|(_, (w, h))| (*w as u64).saturating_mul(*h as u64) <= MAX_SOURCE_IMAGE_PIXELS)
            .and_then(|(path, _)| image::open(path).ok())
    } else {
        None
    };
    if let Some(image) = image {
        let mut rgb = image.thumbnail(width, image_height).to_rgb8();
        if options.filter.enabled {
            apply_filter(&mut rgb, &options.filter);
        }
        let offset_x = x + (width.saturating_sub(rgb.width())) / 2;
        let offset_y = y + (image_height.saturating_sub(rgb.height())) / 2;
        let _ = canvas.copy_from(&rgb, offset_x, offset_y);
    } else {
        draw_missing_placeholder(canvas, x, y, width, image_height);
    }
    if options.show_captions {
        fill_rect(
            canvas,
            x,
            y + image_height,
            width,
            CAPTION_HEIGHT,
            Rgb([31, 39, 34]),
        );
        let mut label = photo.category.key().replace('_', " ").to_ascii_uppercase();
        if let Some(energy) = photo.energy_kj {
            label.push_str(&format!("  {energy} KJ"));
        }
        if !photo.caption.is_empty() && photo.caption.is_ascii() {
            label.push_str("  ");
            label.push_str(&photo.caption.to_ascii_uppercase());
        }
        draw_text_5x7(
            canvas,
            x + 7,
            y + image_height + 8,
            &label,
            2,
            Rgb([242, 244, 241]),
        );
    }
}

fn draw_missing_placeholder(canvas: &mut RgbImage, x: u32, y: u32, width: u32, height: u32) {
    let color = Rgb([154, 161, 155]);
    let diagonal = width.min(height);
    for offset in 0..diagonal {
        if x + offset < canvas.width() && y + offset < canvas.height() {
            canvas.put_pixel(x + offset, y + offset, color);
        }
        let inverse_x = x + width.saturating_sub(offset + 1);
        if inverse_x < canvas.width() && y + offset < canvas.height() {
            canvas.put_pixel(inverse_x, y + offset, color);
        }
    }
    let text_x = x + width.saturating_sub(7 * 5 * 2) / 2;
    let text_y = y + height.saturating_sub(14) / 2;
    draw_text_5x7(canvas, text_x, text_y, "MISSING", 2, Rgb([92, 98, 93]));
}

fn apply_filter(image: &mut RgbImage, settings: &MealPhotoFilter) {
    let brightness = settings.brightness.clamp(-1.0, 1.0) * 255.0;
    let contrast = settings.contrast.clamp(0.25, 2.5);
    let saturation = settings.saturation.clamp(0.0, 2.5);
    let warmth = settings.warmth.clamp(-1.0, 1.0) * 28.0;
    let tint = settings.tint.clamp(-1.0, 1.0) * 22.0;
    for pixel in image.pixels_mut() {
        let mut red = pixel[0] as f64;
        let mut green = pixel[1] as f64;
        let mut blue = pixel[2] as f64;
        red = (red - 127.5) * contrast + 127.5 + brightness + warmth + tint * 0.25;
        green = (green - 127.5) * contrast + 127.5 + brightness - tint;
        blue = (blue - 127.5) * contrast + 127.5 + brightness - warmth + tint * 0.25;
        let luma = red * 0.2126 + green * 0.7152 + blue * 0.0722;
        red = luma + (red - luma) * saturation;
        green = luma + (green - luma) * saturation;
        blue = luma + (blue - luma) * saturation;
        *pixel = Rgb([
            red.clamp(0.0, 255.0) as u8,
            green.clamp(0.0, 255.0) as u8,
            blue.clamp(0.0, 255.0) as u8,
        ]);
    }
}

fn read_media_metadata_unlocked(
    root: &Path,
) -> Result<BTreeMap<String, MediaMetaEntry>, MediaError> {
    let raw = read_media_meta_value(root)?;
    let Some(root_object) = raw.as_object() else {
        return Err(MediaError::new(
            "MEDIA_METADATA_INVALID",
            "Media metadata root must be a JSON object.",
        ));
    };
    let Some(entries) = root_object.get("entries").and_then(Value::as_object) else {
        return Ok(BTreeMap::new());
    };
    let mut result = BTreeMap::new();
    for (key, value) in entries {
        let Some(item) = value.as_object() else {
            continue;
        };
        let energy_kj = item.get("energyKj").and_then(Value::as_i64);
        let latitude = item.get("lat").and_then(Value::as_f64);
        let longitude = item.get("lng").and_then(Value::as_f64);
        let place = item
            .get("place")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .map(str::to_owned);
        result.insert(
            key.to_lowercase(),
            MediaMetaEntry {
                energy_kj,
                latitude,
                longitude,
                place,
            },
        );
    }
    Ok(result)
}

fn set_media_metadata_unlocked(
    root: &Path,
    file_name: &str,
    entry: &MediaMetaEntry,
) -> Result<(), MediaError> {
    validate_leaf_name(file_name)?;
    validate_metadata_entry(entry)?;
    let normalized_key = file_name.to_lowercase();
    let mut root_value = read_media_meta_value(root)?;
    if !root_value.is_object() {
        root_value = Value::Object(Map::new());
    }
    let root_object = root_value
        .as_object_mut()
        .expect("object established immediately above");
    root_object.insert("version".to_owned(), Value::from(1));
    if !root_object
        .get("entries")
        .is_some_and(serde_json::Value::is_object)
    {
        root_object.insert("entries".to_owned(), Value::Object(Map::new()));
    }
    let entries = root_object
        .get_mut("entries")
        .and_then(Value::as_object_mut)
        .expect("entries object established immediately above");
    let item = entries
        .entry(normalized_key)
        .or_insert_with(|| Value::Object(Map::new()));
    if !item.is_object() {
        *item = Value::Object(Map::new());
    }
    let item = item
        .as_object_mut()
        .expect("entry object established immediately above");
    set_or_remove(item, "energyKj", entry.energy_kj.map(Value::from));
    set_or_remove(item, "lat", entry.latitude.map(Value::from));
    set_or_remove(item, "lng", entry.longitude.map(Value::from));
    set_or_remove(
        item,
        "place",
        entry
            .place
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .map(Value::from),
    );

    let encoded = serde_json::to_vec_pretty(&root_value)
        .map_err(|error| MediaError::new("MEDIA_METADATA_INVALID", error.to_string()))?;
    if encoded.len() as u64 > MAX_MEDIA_META_BYTES {
        return Err(MediaError::new(
            "MEDIA_METADATA_TOO_LARGE",
            "Media metadata exceeds its size limit.",
        ));
    }
    let target = root.join(MEDIA_META_FILE_NAME);
    atomic_write_replace(&target, &encoded)?;
    let reread = read_bounded(&target, MAX_MEDIA_META_BYTES)?;
    let decoded: Value = serde_json::from_slice(&reread)
        .map_err(|error| MediaError::new("MEDIA_METADATA_VERIFY_FAILED", error.to_string()))?;
    if decoded != root_value {
        return Err(MediaError::new(
            "MEDIA_METADATA_VERIFY_FAILED",
            "Media metadata changed during write-back verification.",
        ));
    }
    Ok(())
}

fn read_media_meta_value(root: &Path) -> Result<Value, MediaError> {
    let current = root.join(MEDIA_META_FILE_NAME);
    recover_file_if_needed(&current)?;
    let legacy = root.join(LEGACY_MEDIA_META_FILE_NAME);
    let path = if current.exists() {
        Some(current)
    } else if legacy.exists() {
        Some(legacy)
    } else {
        None
    };
    let Some(path) = path else {
        return Ok(serde_json::json!({"version": 1, "entries": {}}));
    };
    let metadata = fs::symlink_metadata(&path)
        .map_err(|error| MediaError::io("read media metadata file", error))?;
    if !metadata.is_file() || is_reparse_point(&metadata) {
        return Err(MediaError::new(
            "MEDIA_METADATA_PATH_INVALID",
            "Media metadata must be a regular file in the selected media directory.",
        ));
    }
    let canonical =
        fs::canonicalize(&path).map_err(|error| MediaError::io("canonicalize metadata", error))?;
    if canonical.parent() != Some(root) {
        return Err(MediaError::new(
            "MEDIA_PATH_OUTSIDE_ROOT",
            "Media metadata resolved outside the selected directory.",
        ));
    }
    let bytes = read_bounded(&canonical, MAX_MEDIA_META_BYTES)?;
    serde_json::from_slice(&bytes)
        .map_err(|error| MediaError::new("MEDIA_METADATA_INVALID", error.to_string()))
}

fn media_name_map(root: &Path) -> Result<HashMap<String, String>, MediaError> {
    let mut result = HashMap::new();
    for entry in
        fs::read_dir(root).map_err(|error| MediaError::io("enumerate media directory", error))?
    {
        let entry = entry.map_err(|error| MediaError::io("read media entry", error))?;
        let metadata = entry
            .metadata()
            .map_err(|error| MediaError::io("read media metadata", error))?;
        if !metadata.is_file() || is_reparse_point(&metadata) {
            continue;
        }
        let name = entry.file_name().to_string_lossy().into_owned();
        if name.eq_ignore_ascii_case(MEDIA_META_FILE_NAME)
            || name.eq_ignore_ascii_case(LEGACY_MEDIA_META_FILE_NAME)
        {
            continue;
        }
        result.insert(name.to_lowercase(), name);
    }
    Ok(result)
}

fn media_meta_location(entry: &MediaMetaEntry) -> Option<String> {
    entry
        .place
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
        .or_else(|| {
            entry
                .latitude
                .zip(entry.longitude)
                .map(|(lat, lng)| format!("{lat:.5}, {lng:.5}"))
        })
}

fn category_from_caption(caption: &str) -> Option<MealCategory> {
    let normalized = remove_energy_suffix(caption)
        .trim()
        .to_lowercase()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    all_categories().into_iter().find(|category| {
        normalized == category.chinese_label()
            || normalized == category.english_label()
            || (category == &MealCategory::LateSnack && normalized == "late-night snack")
    })
}

fn category_from_file_name(file_name: &str) -> Option<MealCategory> {
    let normalized = file_name.to_lowercase().replace(['-', '_'], " ");
    all_categories().into_iter().find(|category| {
        normalized.contains(category.chinese_label())
            || normalized.contains(category.english_label())
            || (*category == MealCategory::LateSnack && normalized.contains("late night snack"))
    })
}

fn all_categories() -> [MealCategory; 6] {
    [
        MealCategory::Breakfast,
        MealCategory::Lunch,
        MealCategory::AfternoonTea,
        MealCategory::Dinner,
        MealCategory::Fruit,
        MealCategory::LateSnack,
    ]
}

fn energy_from_caption(caption: &str) -> Option<i64> {
    ENERGY_SUFFIX_RE
        .get_or_init(|| Regex::new(r"(?i)[\-–—]\s*(\d+)\s*kJ\s*$").expect("energy regex"))
        .captures(caption)
        .and_then(|captures| captures.get(1))
        .and_then(|value| value.as_str().parse().ok())
}

fn remove_energy_suffix(caption: &str) -> String {
    ENERGY_SUFFIX_RE
        .get_or_init(|| Regex::new(r"(?i)[\-–—]\s*(\d+)\s*kJ\s*$").expect("energy regex"))
        .replace(caption, "")
        .trim_end_matches(['-', '–', '—', ' '])
        .to_owned()
}

fn target_file_name(target: &str) -> Option<String> {
    let cleaned = target.trim().trim_matches(['<', '>']).replace('\\', "/");
    if cleaned.is_empty() {
        return None;
    }
    let last = cleaned.rsplit('/').next()?;
    let decoded = percent_decode(last)?;
    validate_leaf_name(&decoded).ok()?;
    Some(decoded)
}

fn percent_decode(value: &str) -> Option<String> {
    let bytes = value.as_bytes();
    let mut output = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'%' {
            if index + 2 >= bytes.len() {
                return None;
            }
            let high = hex_digit(bytes[index + 1])?;
            let low = hex_digit(bytes[index + 2])?;
            output.push((high << 4) | low);
            index += 3;
        } else {
            output.push(bytes[index]);
            index += 1;
        }
    }
    String::from_utf8(output).ok()
}

fn hex_digit(value: u8) -> Option<u8> {
    match value {
        b'0'..=b'9' => Some(value - b'0'),
        b'a'..=b'f' => Some(value - b'a' + 10),
        b'A'..=b'F' => Some(value - b'A' + 10),
        _ => None,
    }
}

fn next_media_file_name(
    root: &Path,
    pattern: &str,
    date: &str,
    category: &str,
    extension: &str,
) -> Result<String, MediaError> {
    let existing = media_name_map(root)?;
    for sequence in 1_u32..=99_999 {
        let mut stem = pattern
            .replace("{date}", date)
            .replace("{category}", category)
            .replace("{seq}", &sequence.to_string());
        if !pattern.contains("{seq}") && sequence > 1 {
            stem.push_str(&format!("_{sequence}"));
        }
        let candidate = format!("{}.{}", sanitize_file_stem(&stem), extension);
        if !existing.contains_key(&candidate.to_lowercase()) {
            return Ok(candidate);
        }
    }
    Err(MediaError::new(
        "MEDIA_NAME_EXHAUSTED",
        "Could not allocate an unused media file name.",
    ))
}

fn validate_source_image(source: &Path) -> Result<PathBuf, MediaError> {
    let metadata =
        fs::symlink_metadata(source).map_err(|error| MediaError::io("read source image", error))?;
    if !metadata.is_file() || is_reparse_point(&metadata) {
        return Err(MediaError::new(
            "MEDIA_SOURCE_INVALID",
            "The selected image must be a regular file, not a symlink or junction.",
        ));
    }
    fs::canonicalize(source).map_err(|error| MediaError::io("canonicalize source image", error))
}

fn existing_media_leaf(root: &Path, file_name: &str) -> Result<PathBuf, MediaError> {
    validate_leaf_name(file_name)?;
    let path = root.join(file_name);
    let metadata =
        fs::symlink_metadata(&path).map_err(|error| MediaError::io("locate media file", error))?;
    if !metadata.is_file() || is_reparse_point(&metadata) {
        return Err(MediaError::new(
            "MEDIA_PATH_OUTSIDE_ROOT",
            "Media must be a regular file in the selected directory.",
        ));
    }
    let canonical =
        fs::canonicalize(path).map_err(|error| MediaError::io("canonicalize media", error))?;
    if canonical.parent() != Some(root) {
        return Err(MediaError::new(
            "MEDIA_PATH_OUTSIDE_ROOT",
            "Media resolved outside the selected directory.",
        ));
    }
    Ok(canonical)
}

fn new_media_leaf(root: &Path, file_name: &str) -> Result<PathBuf, MediaError> {
    validate_leaf_name(file_name)?;
    let path = root.join(file_name);
    if path.parent() != Some(root) {
        return Err(MediaError::new(
            "MEDIA_PATH_OUTSIDE_ROOT",
            "Media destination resolved outside the selected directory.",
        ));
    }
    Ok(path)
}

fn validate_leaf_name(file_name: &str) -> Result<(), MediaError> {
    let mut components = Path::new(file_name).components();
    if !matches!(
        (components.next(), components.next()),
        (Some(Component::Normal(_)), None)
    ) || file_name.contains(['/', '\\'])
        || file_name == "."
        || file_name == ".."
    {
        return Err(MediaError::new(
            "MEDIA_PATH_TRAVERSAL",
            "Only one file name without directory components is allowed.",
        ));
    }
    Ok(())
}

fn sanitize_file_stem(value: &str) -> String {
    let mut result = value
        .trim()
        .chars()
        .map(|character| {
            if character.is_control() || r#"<>:"/\|?*"#.contains(character) {
                '_'
            } else {
                character
            }
        })
        .collect::<String>();
    result = result.trim_end_matches([' ', '.']).to_owned();
    if result.is_empty() {
        result = "image".to_owned();
    }
    if is_reserved_windows_stem(&result) {
        result.insert(0, '_');
    }
    while result.len() > 180 {
        result.pop();
    }
    result
}

fn is_reserved_windows_stem(value: &str) -> bool {
    let upper = value
        .split('.')
        .next()
        .unwrap_or(value)
        .trim()
        .to_ascii_uppercase();
    matches!(upper.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || (upper.len() == 4
            && (upper.starts_with("COM") || upper.starts_with("LPT"))
            && matches!(upper.as_bytes()[3], b'1'..=b'9'))
}

fn extension_for_format(format: ImageFormat) -> &'static str {
    match format {
        ImageFormat::Jpeg => "jpg",
        ImageFormat::Png => "png",
        ImageFormat::WebP => "webp",
        _ => "img",
    }
}

fn parse_optional_date(value: Option<&str>) -> Result<Option<NaiveDate>, MediaError> {
    value
        .filter(|value| !value.trim().is_empty())
        .map(|value| {
            NaiveDate::parse_from_str(value, "%Y-%m-%d").map_err(|_| {
                MediaError::new("MEAL_DATE_INVALID", "Meal dates must use YYYY-MM-DD.")
            })
        })
        .transpose()
}

fn date_from_name_or_modified(file_name: &str, modified_at: i64) -> NaiveDate {
    let regex = DATE_RE.get_or_init(|| Regex::new(r"\d{4}-\d{2}-\d{2}").expect("date regex"));
    if let Some(value) = regex.find(file_name)
        && let Ok(date) = NaiveDate::parse_from_str(value.as_str(), "%Y-%m-%d")
    {
        return date;
    }
    DateTime::<chrono::Utc>::from_timestamp_millis(modified_at)
        .map(|value| value.with_timezone(&Local).date_naive())
        .unwrap_or_else(|| Local::now().date_naive())
}

fn validate_metadata_entry(entry: &MediaMetaEntry) -> Result<(), MediaError> {
    if entry
        .energy_kj
        .is_some_and(|value| !(0..=10_000_000).contains(&value))
    {
        return Err(MediaError::new(
            "MEDIA_METADATA_ENERGY_INVALID",
            "Energy must be between 0 and 10,000,000 kJ.",
        ));
    }
    if entry
        .latitude
        .is_some_and(|value| !value.is_finite() || !(-90.0..=90.0).contains(&value))
        || entry
            .longitude
            .is_some_and(|value| !value.is_finite() || !(-180.0..=180.0).contains(&value))
    {
        return Err(MediaError::new(
            "MEDIA_METADATA_COORDINATES_INVALID",
            "Latitude or longitude is outside its valid range.",
        ));
    }
    if entry
        .place
        .as_deref()
        .is_some_and(|value| value.chars().count() > 500)
    {
        return Err(MediaError::new(
            "MEDIA_METADATA_PLACE_TOO_LONG",
            "Place name exceeds 500 characters.",
        ));
    }
    Ok(())
}

fn set_or_remove(object: &mut Map<String, Value>, key: &str, value: Option<Value>) {
    if let Some(value) = value {
        object.insert(key.to_owned(), value);
    } else {
        object.remove(key);
    }
}

fn ensure_pixel_limit(width: u32, height: u32, limit: u64) -> Result<(), MediaError> {
    let pixels = (width as u64).saturating_mul(height as u64);
    if width == 0 || height == 0 || pixels > limit {
        return Err(MediaError::new(
            "MEDIA_IMAGE_PIXEL_LIMIT",
            format!("Image dimensions exceed the {limit}-pixel safety limit."),
        ));
    }
    Ok(())
}

fn ensure_export_size(width: u32, height: u32) -> Result<(), MediaError> {
    if height > MAX_EXPORT_HEIGHT
        || (width as u64).saturating_mul(height as u64) > MAX_EXPORT_PIXELS
    {
        return Err(export_size_error());
    }
    Ok(())
}

fn export_size_error() -> MediaError {
    MediaError::new(
        "MEAL_EXPORT_TOO_LARGE",
        "The requested export exceeds the safe height or pixel limit; shorten the date range.",
    )
}

fn validate_export_destination(path: &Path) -> Result<(), MediaError> {
    let extension_ok = path
        .extension()
        .and_then(|value| value.to_str())
        .is_some_and(|value| value.eq_ignore_ascii_case("png"));
    if !extension_ok {
        return Err(MediaError::new(
            "MEAL_EXPORT_EXTENSION_INVALID",
            "Meal calendar exports must use a .png file name.",
        ));
    }
    let parent = path.parent().ok_or_else(|| {
        MediaError::new(
            "MEAL_EXPORT_DESTINATION_INVALID",
            "Export destination has no parent directory.",
        )
    })?;
    let parent_metadata = fs::symlink_metadata(parent)
        .map_err(|error| MediaError::io("read export folder", error))?;
    if !parent_metadata.is_dir() || is_reparse_point(&parent_metadata) {
        return Err(MediaError::new(
            "MEAL_EXPORT_DESTINATION_INVALID",
            "Export folder must be a regular directory.",
        ));
    }
    if path.exists() {
        let metadata = fs::symlink_metadata(path)
            .map_err(|error| MediaError::io("read export destination", error))?;
        if !metadata.is_file() || is_reparse_point(&metadata) {
            return Err(MediaError::new(
                "MEAL_EXPORT_DESTINATION_INVALID",
                "Export destination must be a regular file.",
            ));
        }
    }
    Ok(())
}

fn read_bounded(path: &Path, limit: u64) -> Result<Vec<u8>, MediaError> {
    let metadata =
        fs::metadata(path).map_err(|error| MediaError::io("read file metadata", error))?;
    if metadata.len() > limit {
        return Err(MediaError::new(
            "MEDIA_FILE_TOO_LARGE",
            format!("File exceeds the {limit}-byte limit."),
        ));
    }
    let file = File::open(path).map_err(|error| MediaError::io("open file", error))?;
    let mut output = Vec::with_capacity(metadata.len() as usize);
    file.take(limit + 1)
        .read_to_end(&mut output)
        .map_err(|error| MediaError::io("read file", error))?;
    if output.len() as u64 > limit {
        return Err(MediaError::new(
            "MEDIA_FILE_TOO_LARGE",
            format!("File exceeds the {limit}-byte limit."),
        ));
    }
    Ok(output)
}

fn atomic_write_new(path: &Path, bytes: &[u8]) -> Result<(), MediaError> {
    if path.exists() {
        return Err(MediaError::new(
            "MEDIA_NAME_EXISTS",
            "The media destination already exists.",
        ));
    }
    let temp = sibling_temp_path(path, "new");
    write_temp(&temp, bytes)?;
    if path.exists() {
        let _ = fs::remove_file(&temp);
        return Err(MediaError::new(
            "MEDIA_NAME_EXISTS",
            "The media destination was created concurrently.",
        ));
    }
    fs::rename(&temp, path).map_err(|error| {
        let _ = fs::remove_file(&temp);
        MediaError::io("commit media file", error)
    })?;
    verify_bytes(path, bytes)
}

fn atomic_write_replace(path: &Path, bytes: &[u8]) -> Result<(), MediaError> {
    recover_file_if_needed(path)?;
    let temp = sibling_temp_path(path, "pending");
    let recovery = sibling_recovery_path(path)?;
    write_temp(&temp, bytes)?;
    if path.exists() {
        if recovery.exists() {
            fs::remove_file(&recovery)
                .map_err(|error| MediaError::io("remove stale recovery file", error))?;
        }
        fs::rename(path, &recovery).map_err(|error| {
            let _ = fs::remove_file(&temp);
            MediaError::io("stage original file", error)
        })?;
    }
    if let Err(error) = fs::rename(&temp, path) {
        if recovery.exists() {
            let _ = fs::rename(&recovery, path);
        }
        let _ = fs::remove_file(&temp);
        return Err(MediaError::io("commit file", error));
    }
    if let Err(error) = verify_bytes(path, bytes) {
        let _ = fs::remove_file(path);
        if recovery.exists() {
            let _ = fs::rename(&recovery, path);
        }
        return Err(error);
    }
    if recovery.exists() {
        // The new file has passed read-back verification. Cleanup is best effort
        // so a temporary antivirus lock cannot turn a committed write into an
        // apparent failure.
        let _ = fs::remove_file(recovery);
    }
    Ok(())
}

fn recover_file_if_needed(path: &Path) -> Result<(), MediaError> {
    let recovery = sibling_recovery_path(path)?;
    if recovery.exists() {
        if path.exists() {
            let _ = fs::remove_file(recovery);
        } else {
            fs::rename(recovery, path)
                .map_err(|error| MediaError::io("restore interrupted file write", error))?;
        }
    }
    Ok(())
}

fn write_temp(path: &Path, bytes: &[u8]) -> Result<(), MediaError> {
    let mut output = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .map_err(|error| MediaError::io("create temporary file", error))?;
    if let Err(error) = output.write_all(bytes).and_then(|()| output.sync_all()) {
        let _ = fs::remove_file(path);
        return Err(MediaError::io("write temporary file", error));
    }
    drop(output);
    if let Err(error) = verify_bytes(path, bytes) {
        let _ = fs::remove_file(path);
        return Err(error);
    }
    Ok(())
}

fn verify_bytes(path: &Path, bytes: &[u8]) -> Result<(), MediaError> {
    let actual = read_bounded(path, bytes.len() as u64 + 1)?;
    if actual.len() != bytes.len()
        || Sha256::digest(&actual).as_slice() != Sha256::digest(bytes).as_slice()
    {
        return Err(MediaError::new(
            "MEDIA_WRITE_VERIFY_FAILED",
            "File did not match after writing.",
        ));
    }
    Ok(())
}

fn sibling_temp_path(path: &Path, label: &str) -> PathBuf {
    let parent = path.parent().unwrap_or_else(|| Path::new("."));
    let name = path
        .file_name()
        .and_then(|value| value.to_str())
        .unwrap_or("media");
    parent.join(format!(".{name}.dc-{label}-{}", Uuid::new_v4()))
}

fn sibling_recovery_path(path: &Path) -> Result<PathBuf, MediaError> {
    let parent = path.parent().ok_or_else(|| {
        MediaError::new("MEDIA_PATH_INVALID", "Destination has no parent directory.")
    })?;
    let name = path
        .file_name()
        .and_then(|value| value.to_str())
        .ok_or_else(|| {
            MediaError::new("MEDIA_PATH_INVALID", "Destination name is not valid UTF-8.")
        })?;
    Ok(parent.join(format!(".{name}.dc-recovery")))
}

fn fill_rect(canvas: &mut RgbImage, x: u32, y: u32, width: u32, height: u32, color: Rgb<u8>) {
    let max_x = x.saturating_add(width).min(canvas.width());
    let max_y = y.saturating_add(height).min(canvas.height());
    for target_y in y..max_y {
        for target_x in x..max_x {
            canvas.put_pixel(target_x, target_y, color);
        }
    }
}

fn draw_text_5x7(canvas: &mut RgbImage, x: u32, y: u32, text: &str, scale: u32, color: Rgb<u8>) {
    let mut cursor_x = x;
    for character in text.chars() {
        let rows = glyph_5x7(character.to_ascii_uppercase());
        for (row, bits) in rows.iter().enumerate() {
            for column in 0..5_u32 {
                if bits & (1 << (4 - column)) != 0 {
                    fill_rect(
                        canvas,
                        cursor_x + column * scale,
                        y + row as u32 * scale,
                        scale,
                        scale,
                        color,
                    );
                }
            }
        }
        cursor_x = cursor_x.saturating_add(6 * scale);
        if cursor_x >= canvas.width() {
            break;
        }
    }
}

#[rustfmt::skip]
fn glyph_5x7(character: char) -> [u8; 7] {
    match character {
        'A' => [0b01110,0b10001,0b10001,0b11111,0b10001,0b10001,0b10001],
        'B' => [0b11110,0b10001,0b10001,0b11110,0b10001,0b10001,0b11110],
        'C' => [0b01111,0b10000,0b10000,0b10000,0b10000,0b10000,0b01111],
        'D' => [0b11110,0b10001,0b10001,0b10001,0b10001,0b10001,0b11110],
        'E' => [0b11111,0b10000,0b10000,0b11110,0b10000,0b10000,0b11111],
        'F' => [0b11111,0b10000,0b10000,0b11110,0b10000,0b10000,0b10000],
        'G' => [0b01111,0b10000,0b10000,0b10111,0b10001,0b10001,0b01110],
        'H' => [0b10001,0b10001,0b10001,0b11111,0b10001,0b10001,0b10001],
        'I' => [0b11111,0b00100,0b00100,0b00100,0b00100,0b00100,0b11111],
        'J' => [0b00111,0b00010,0b00010,0b00010,0b10010,0b10010,0b01100],
        'K' => [0b10001,0b10010,0b10100,0b11000,0b10100,0b10010,0b10001],
        'L' => [0b10000,0b10000,0b10000,0b10000,0b10000,0b10000,0b11111],
        'M' => [0b10001,0b11011,0b10101,0b10101,0b10001,0b10001,0b10001],
        'N' => [0b10001,0b11001,0b10101,0b10011,0b10001,0b10001,0b10001],
        'O' => [0b01110,0b10001,0b10001,0b10001,0b10001,0b10001,0b01110],
        'P' => [0b11110,0b10001,0b10001,0b11110,0b10000,0b10000,0b10000],
        'Q' => [0b01110,0b10001,0b10001,0b10001,0b10101,0b10010,0b01101],
        'R' => [0b11110,0b10001,0b10001,0b11110,0b10100,0b10010,0b10001],
        'S' => [0b01111,0b10000,0b10000,0b01110,0b00001,0b00001,0b11110],
        'T' => [0b11111,0b00100,0b00100,0b00100,0b00100,0b00100,0b00100],
        'U' => [0b10001,0b10001,0b10001,0b10001,0b10001,0b10001,0b01110],
        'V' => [0b10001,0b10001,0b10001,0b10001,0b10001,0b01010,0b00100],
        'W' => [0b10001,0b10001,0b10001,0b10101,0b10101,0b10101,0b01010],
        'X' => [0b10001,0b10001,0b01010,0b00100,0b01010,0b10001,0b10001],
        'Y' => [0b10001,0b10001,0b01010,0b00100,0b00100,0b00100,0b00100],
        'Z' => [0b11111,0b00001,0b00010,0b00100,0b01000,0b10000,0b11111],
        '0' => [0b01110,0b10001,0b10011,0b10101,0b11001,0b10001,0b01110],
        '1' => [0b00100,0b01100,0b00100,0b00100,0b00100,0b00100,0b01110],
        '2' => [0b01110,0b10001,0b00001,0b00010,0b00100,0b01000,0b11111],
        '3' => [0b11110,0b00001,0b00001,0b01110,0b00001,0b00001,0b11110],
        '4' => [0b00010,0b00110,0b01010,0b10010,0b11111,0b00010,0b00010],
        '5' => [0b11111,0b10000,0b10000,0b11110,0b00001,0b00001,0b11110],
        '6' => [0b01110,0b10000,0b10000,0b11110,0b10001,0b10001,0b01110],
        '7' => [0b11111,0b00001,0b00010,0b00100,0b01000,0b01000,0b01000],
        '8' => [0b01110,0b10001,0b10001,0b01110,0b10001,0b10001,0b01110],
        '9' => [0b01110,0b10001,0b10001,0b01111,0b00001,0b00001,0b01110],
        '-' => [0,0,0,0b11111,0,0,0],
        '.' => [0,0,0,0,0,0b00110,0b00110],
        ':' => [0,0b00110,0b00110,0,0b00110,0b00110,0],
        '/' => [0b00001,0b00010,0b00010,0b00100,0b01000,0b01000,0b10000],
        '_' => [0,0,0,0,0,0,0b11111],
        ' ' => [0; 7],
        _ => [0b11111,0b10001,0b00010,0b00100,0,0b00100,0],
    }
}

#[derive(Debug, Clone, Copy)]
struct ExifSummary {
    orientation: u16,
    gps: Option<(f64, f64)>,
}

impl Default for ExifSummary {
    fn default() -> Self {
        Self {
            orientation: 1,
            gps: None,
        }
    }
}

#[derive(Clone, Copy)]
enum Endian {
    Little,
    Big,
}

fn read_jpeg_exif(path: &Path) -> Option<ExifSummary> {
    let mut bytes = Vec::new();
    File::open(path)
        .ok()?
        .take(512 * 1024)
        .read_to_end(&mut bytes)
        .ok()?;
    if bytes.get(0..2)? != [0xFF, 0xD8] {
        return None;
    }
    let mut cursor = 2_usize;
    while cursor + 4 <= bytes.len() {
        if bytes[cursor] != 0xFF {
            cursor += 1;
            continue;
        }
        let marker = bytes[cursor + 1];
        if matches!(marker, 0xD9 | 0xDA) {
            break;
        }
        if matches!(marker, 0x01 | 0xD0..=0xD7) {
            cursor += 2;
            continue;
        }
        let length = u16::from_be_bytes([bytes[cursor + 2], bytes[cursor + 3]]) as usize;
        if length < 2 {
            return None;
        }
        let segment_start = cursor + 4;
        let segment_end = cursor.checked_add(2 + length)?;
        if segment_end > bytes.len() {
            return None;
        }
        if marker == 0xE1 && bytes.get(segment_start..segment_start + 6)? == b"Exif\0\0" {
            return parse_tiff_exif(&bytes[segment_start + 6..segment_end]);
        }
        cursor = segment_end;
    }
    None
}

fn parse_tiff_exif(tiff: &[u8]) -> Option<ExifSummary> {
    let endian = match tiff.get(0..2)? {
        b"II" => Endian::Little,
        b"MM" => Endian::Big,
        _ => return None,
    };
    if read_u16(tiff, 2, endian)? != 42 {
        return None;
    }
    let ifd0 = read_u32(tiff, 4, endian)? as usize;
    let mut orientation = 1_u16;
    let mut gps_ifd = None;
    for_each_ifd_entry(
        tiff,
        ifd0,
        endian,
        |tag, field_type, count, value_offset| {
            if tag == 0x0112 && field_type == 3 && count >= 1 {
                orientation = read_u16(tiff, value_offset, endian).unwrap_or(1);
            } else if tag == 0x8825 && field_type == 4 && count == 1 {
                gps_ifd = read_u32(tiff, value_offset, endian).map(|value| value as usize);
            }
        },
    )?;
    let gps = gps_ifd.and_then(|offset| parse_gps_ifd(tiff, offset, endian));
    Some(ExifSummary { orientation, gps })
}

fn parse_gps_ifd(tiff: &[u8], offset: usize, endian: Endian) -> Option<(f64, f64)> {
    let mut latitude_ref = None;
    let mut longitude_ref = None;
    let mut latitude = None;
    let mut longitude = None;
    for_each_ifd_entry(
        tiff,
        offset,
        endian,
        |tag, field_type, count, value_offset| match (tag, field_type) {
            (1, 2) if count >= 1 => latitude_ref = tiff.get(value_offset).copied(),
            (2, 5) if count == 3 => latitude = read_degrees(tiff, value_offset, endian),
            (3, 2) if count >= 1 => longitude_ref = tiff.get(value_offset).copied(),
            (4, 5) if count == 3 => longitude = read_degrees(tiff, value_offset, endian),
            _ => {}
        },
    )?;
    let mut latitude = latitude?;
    let mut longitude = longitude?;
    if latitude_ref == Some(b'S') {
        latitude = -latitude;
    }
    if longitude_ref == Some(b'W') {
        longitude = -longitude;
    }
    if (-90.0..=90.0).contains(&latitude) && (-180.0..=180.0).contains(&longitude) {
        Some((latitude, longitude))
    } else {
        None
    }
}

fn for_each_ifd_entry(
    tiff: &[u8],
    offset: usize,
    endian: Endian,
    mut visitor: impl FnMut(u16, u16, u32, usize),
) -> Option<()> {
    let count = read_u16(tiff, offset, endian)? as usize;
    for index in 0..count.min(256) {
        let entry = offset.checked_add(2 + index * 12)?;
        let tag = read_u16(tiff, entry, endian)?;
        let field_type = read_u16(tiff, entry + 2, endian)?;
        let field_count = read_u32(tiff, entry + 4, endian)?;
        let unit_size = match field_type {
            1 | 2 | 7 => 1_u32,
            3 => 2,
            4 | 9 => 4,
            5 | 10 => 8,
            _ => continue,
        };
        let byte_count = field_count.checked_mul(unit_size)?;
        let value_offset = if byte_count <= 4 {
            entry + 8
        } else {
            read_u32(tiff, entry + 8, endian)? as usize
        };
        if value_offset.checked_add(byte_count as usize)? <= tiff.len() {
            visitor(tag, field_type, field_count, value_offset);
        }
    }
    Some(())
}

fn read_degrees(bytes: &[u8], offset: usize, endian: Endian) -> Option<f64> {
    let mut values = [0.0_f64; 3];
    for (index, value) in values.iter_mut().enumerate() {
        let position = offset.checked_add(index * 8)?;
        let numerator = read_u32(bytes, position, endian)?;
        let denominator = read_u32(bytes, position + 4, endian)?;
        if denominator == 0 {
            return None;
        }
        *value = numerator as f64 / denominator as f64;
    }
    Some(values[0] + values[1] / 60.0 + values[2] / 3_600.0)
}

fn read_u16(bytes: &[u8], offset: usize, endian: Endian) -> Option<u16> {
    let value = [*bytes.get(offset)?, *bytes.get(offset + 1)?];
    Some(match endian {
        Endian::Little => u16::from_le_bytes(value),
        Endian::Big => u16::from_be_bytes(value),
    })
}

fn read_u32(bytes: &[u8], offset: usize, endian: Endian) -> Option<u32> {
    let value = [
        *bytes.get(offset)?,
        *bytes.get(offset + 1)?,
        *bytes.get(offset + 2)?,
        *bytes.get(offset + 3)?,
    ];
    Some(match endian {
        Endian::Little => u32::from_le_bytes(value),
        Endian::Big => u32::from_be_bytes(value),
    })
}

fn apply_orientation(image: DynamicImage, orientation: u16) -> DynamicImage {
    match orientation {
        2 => image.fliph(),
        3 => image.rotate180(),
        4 => image.flipv(),
        5 => image.rotate90().fliph(),
        6 => image.rotate90(),
        7 => image.rotate270().fliph(),
        8 => image.rotate270(),
        _ => image,
    }
}

fn default_true() -> bool {
    true
}

fn default_quality() -> u8 {
    80
}

fn default_max_edge() -> u32 {
    MAX_COMPRESSED_EDGE
}

fn default_image_name_pattern() -> String {
    "{date}_{category}_{seq}".to_owned()
}

fn default_export_width() -> u32 {
    1_080
}

fn default_export_background() -> [u8; 3] {
    [241, 244, 240]
}

#[cfg(windows)]
fn is_reparse_point(metadata: &fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;
    const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x0400;
    metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0
}

#[cfg(not(windows))]
fn is_reparse_point(metadata: &fs::Metadata) -> bool {
    metadata.file_type().is_symlink()
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn smart_rows_never_leave_a_dangling_final_cell() {
        assert_eq!(meal_photo_row_sizes(1, MealPhotosPerRow::Smart), vec![1]);
        assert_eq!(meal_photo_row_sizes(4, MealPhotosPerRow::Smart), vec![2, 2]);
        assert_eq!(meal_photo_row_sizes(5, MealPhotosPerRow::Smart), vec![3, 2]);
        assert_eq!(
            meal_photo_row_sizes(7, MealPhotosPerRow::Smart),
            vec![3, 2, 2]
        );
    }

    #[test]
    fn metadata_update_preserves_unknown_fields_and_legacy_file() {
        let root = tempdir().expect("temporary directory");
        fs::write(
            root.path().join(LEGACY_MEDIA_META_FILE_NAME),
            r#"{"version":1,"future":{"keep":true},"entries":{"meal.jpg":{"futureEntry":7,"energyKj":800}}}"#,
        )
        .expect("legacy sidecar");
        set_media_metadata(
            root.path(),
            "MEAL.JPG",
            &MediaMetaEntry {
                energy_kj: Some(900),
                latitude: Some(31.2),
                longitude: Some(121.5),
                place: Some("Shanghai".to_owned()),
            },
        )
        .expect("update");
        assert!(root.path().join(LEGACY_MEDIA_META_FILE_NAME).exists());
        let current: Value = serde_json::from_slice(
            &fs::read(root.path().join(MEDIA_META_FILE_NAME)).expect("current sidecar"),
        )
        .expect("valid JSON");
        assert_eq!(current["future"]["keep"], Value::Bool(true));
        assert_eq!(
            current["entries"]["meal.jpg"]["futureEntry"],
            Value::from(7)
        );
        assert_eq!(current["entries"]["meal.jpg"]["energyKj"], Value::from(900));
    }

    #[test]
    fn meal_scan_uses_sidecar_and_legacy_caption_energy() {
        let diaries = tempdir().expect("diary directory");
        let media = tempdir().expect("media directory");
        fs::write(
            media.path().join("2026-07-29_lunch_1.jpg"),
            b"not decoded during scan",
        )
        .expect("media");
        fs::write(
            diaries.path().join("2026-07-29.md"),
            "![午餐-800kJ](<2026-07-29_lunch_1.jpg>)",
        )
        .expect("diary");
        let days = scan_meal_calendar(diaries.path(), media.path(), &MealScanOptions::default())
            .expect("scan");
        assert_eq!(days.len(), 1);
        assert_eq!(days[0].photos[0].category, MealCategory::Lunch);
        assert_eq!(days[0].photos[0].energy_kj, Some(800));
        assert!(days[0].photos[0].exists);
    }

    #[test]
    fn export_rejects_pixel_overflow_before_allocating() {
        let root = tempdir().expect("media directory");
        let day = MealCalendarDay {
            date_iso: "2026-07-29".to_owned(),
            photos: (0..500)
                .map(|index| MealPhoto {
                    file_name: format!("missing-{index}.jpg"),
                    caption: String::new(),
                    category: MealCategory::Lunch,
                    diary_file_name: "2026-07-29.md".to_owned(),
                    markdown: String::new(),
                    exists: false,
                    energy_kj: None,
                    location_name: None,
                    appearance_order: index,
                })
                .collect(),
            total_energy_kj: None,
        };
        let error = export_meal_calendar_png(
            root.path(),
            &root.path().join("out.png"),
            &[day],
            &MealExportOptions {
                width: 2_048,
                photos_per_row: MealPhotosPerRow::Two,
                ..MealExportOptions::default()
            },
        )
        .expect_err("must reject oversized export");
        assert_eq!(error.code, "MEAL_EXPORT_TOO_LARGE");
    }

    #[test]
    fn parses_minimal_little_endian_orientation() {
        let tiff = [
            b'I', b'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0,
            0,
        ];
        let parsed = parse_tiff_exif(&tiff).expect("parse");
        assert_eq!(parsed.orientation, 6);
    }

    #[test]
    fn target_name_drops_paths_but_cannot_traverse() {
        assert_eq!(
            target_file_name("folder/photo%20one.jpg").as_deref(),
            Some("photo one.jpg")
        );
        assert_eq!(target_file_name("../"), None);
    }

    #[test]
    fn concurrent_metadata_updates_do_not_drop_entries() {
        let root = tempdir().expect("media directory");
        let root_path = root.path().to_owned();
        let workers = (0..8)
            .map(|index| {
                let root_path = root_path.clone();
                std::thread::spawn(move || {
                    set_media_metadata(
                        &root_path,
                        &format!("meal-{index}.jpg"),
                        &MediaMetaEntry {
                            energy_kj: Some(100 + index),
                            ..MediaMetaEntry::default()
                        },
                    )
                    .expect("metadata update");
                })
            })
            .collect::<Vec<_>>();
        for worker in workers {
            worker.join().expect("worker");
        }
        let metadata = read_media_metadata(root.path()).expect("metadata");
        assert_eq!(metadata.len(), 8);
        for index in 0..8 {
            assert_eq!(
                metadata[&format!("meal-{index}.jpg")].energy_kj,
                Some(100 + index)
            );
        }
    }

    #[test]
    fn metadata_rejects_path_traversal() {
        let root = tempdir().expect("media directory");
        let error = set_media_metadata(root.path(), "..\\outside.jpg", &MediaMetaEntry::default())
            .expect_err("must reject traversal");
        assert_eq!(error.code, "MEDIA_PATH_TRAVERSAL");
    }

    #[test]
    fn imported_media_rollback_is_leaf_only_and_preserves_sidecar() {
        let root = tempdir().expect("media directory");
        fs::write(root.path().join("just-imported.jpg"), b"image").expect("media");
        fs::write(
            root.path().join(MEDIA_META_FILE_NAME),
            br#"{"version":1,"entries":{}}"#,
        )
        .expect("sidecar");
        remove_imported_media(root.path(), "just-imported.jpg").expect("rollback");
        assert!(!root.path().join("just-imported.jpg").exists());
        let error = remove_imported_media(root.path(), MEDIA_META_FILE_NAME)
            .expect_err("must preserve sidecar");
        assert_eq!(error.code, "MEDIA_NAME_INVALID");
        assert!(root.path().join(MEDIA_META_FILE_NAME).exists());
    }
}
