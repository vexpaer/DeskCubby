use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Thought {
    pub id: i64,
    pub content: String,
    pub created_at: i64,
    pub updated_at: i64,
    pub pinned: bool,
    pub deleted_at: Option<i64>,
    pub sort_order: i64,
    pub category_id: Option<i64>,
    pub highlighted: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ThoughtDraft {
    #[serde(default)]
    pub id: Option<i64>,
    pub content: String,
    #[serde(default)]
    pub pinned: bool,
    #[serde(default)]
    pub category_id: Option<i64>,
    #[serde(default)]
    pub highlighted: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ThoughtCategory {
    pub id: i64,
    pub name: String,
    pub color_argb: i32,
    pub sort_order: i64,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ThoughtCategoryDraft {
    #[serde(default)]
    pub id: Option<i64>,
    pub name: String,
    pub color_argb: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DateRecord {
    pub id: i64,
    pub name: String,
    pub icon: String,
    pub date_iso: String,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DateRecordDraft {
    #[serde(default)]
    pub id: Option<i64>,
    pub name: String,
    pub icon: String,
    pub date_iso: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavedPoem {
    pub id: i64,
    pub content: String,
    pub source: String,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavedPoemDraft {
    #[serde(default)]
    pub id: Option<i64>,
    pub content: String,
    #[serde(default)]
    pub source: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HomeGreeting {
    pub chinese: String,
    pub english: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DailyEventTemplate {
    pub id: String,
    pub text: String,
    #[serde(default)]
    pub first_unit: String,
    #[serde(default)]
    pub second_unit: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MealPhotoFilter {
    pub enabled: bool,
    pub brightness: f64,
    pub contrast: f64,
    pub saturation: f64,
    pub warmth: f64,
    pub tint: f64,
}

impl Default for MealPhotoFilter {
    fn default() -> Self {
        Self {
            enabled: false,
            brightness: 0.0,
            contrast: 1.0,
            saturation: 1.0,
            warmth: 0.0,
            tint: 0.0,
        }
    }
}

/// The settings that the Windows client is allowed to display and modify.
///
/// Android-only, postponed, sensitive, and unknown settings stay exclusively in
/// the DPAPI-protected compatibility shadow. Export overlays these fields on that
/// shadow, so an Android -> Windows -> Android round trip does not erase data.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManagedSettings {
    pub visual_style: String,
    pub dark_mode: String,
    pub app_language: String,
    pub theme_color_argb: i32,
    pub theme_secondary_colors_argb: Vec<i32>,
    pub font_scale: f64,
    pub compact_mode: bool,
    pub file_name_pattern: String,
    pub markdown_template: String,
    pub image_name_pattern: String,
    pub image_max_width_dp: i32,
    pub image_max_height_dp: i32,
    pub meal_image_compression_enabled: bool,
    pub meal_image_compression_quality: i32,
    pub photo_location_enabled: bool,
    pub thought_display_mode: String,
    pub thought_highlight_color_argb: i32,
    pub thought_editor_max_height_dp: i32,
    pub poetry_font_size_sp: f64,
    pub poetry_line_spacing: f64,
    pub poetry_text_alignment: String,
    pub poetry_show_source: bool,
    pub poetry_show_quote_mark: bool,
    pub poetry_seven_character_wrap_enabled: bool,
    pub meal_calendar_image_max_height_dp: i32,
    pub meal_calendar_show_captions: bool,
    pub meal_calendar_wrap_enabled: bool,
    pub meal_calendar_photos_per_row: String,
    pub meal_photo_filter: MealPhotoFilter,
    pub meal_buttons_use_icons: bool,
    pub meal_button_icons: Vec<String>,
    pub user_name: String,
    pub home_greetings: Vec<HomeGreeting>,
    pub home_widget_borders_enabled: bool,
    pub daily_event_templates: Vec<DailyEventTemplate>,
    pub home_widgets: Vec<String>,
    pub home_widget_titles: Vec<String>,
}

impl Default for ManagedSettings {
    fn default() -> Self {
        Self {
            visual_style: "MATERIAL".to_owned(),
            dark_mode: "SYSTEM".to_owned(),
            app_language: "CHINESE".to_owned(),
            theme_color_argb: 0xFF42_664Du32 as i32,
            theme_secondary_colors_argb: vec![
                0xFFC9_6F4Au32 as i32,
                0xFFD4_A72Cu32 as i32,
                0xFF52_7F91u32 as i32,
            ],
            font_scale: 1.0,
            compact_mode: false,
            file_name_pattern: "yyyy-MM-dd".to_owned(),
            markdown_template: "# {title}\n\n".to_owned(),
            image_name_pattern: "{date}_{category}_{seq}".to_owned(),
            image_max_width_dp: 720,
            image_max_height_dp: 640,
            meal_image_compression_enabled: true,
            meal_image_compression_quality: 80,
            photo_location_enabled: false,
            thought_display_mode: "SINGLE_LINE".to_owned(),
            thought_highlight_color_argb: 0xFFF6_E3A1u32 as i32,
            thought_editor_max_height_dp: 168,
            poetry_font_size_sp: 18.0,
            poetry_line_spacing: 1.45,
            poetry_text_alignment: "START".to_owned(),
            poetry_show_source: true,
            poetry_show_quote_mark: true,
            poetry_seven_character_wrap_enabled: false,
            meal_calendar_image_max_height_dp: 124,
            meal_calendar_show_captions: true,
            meal_calendar_wrap_enabled: false,
            meal_calendar_photos_per_row: "SMART".to_owned(),
            meal_photo_filter: MealPhotoFilter::default(),
            meal_buttons_use_icons: false,
            meal_button_icons: vec![
                "🥪".to_owned(),
                "🍱".to_owned(),
                "🍹".to_owned(),
                "🍜".to_owned(),
                "🍊".to_owned(),
                "🍤".to_owned(),
            ],
            user_name: String::new(),
            home_greetings: vec![
                HomeGreeting {
                    chinese: "今天从这里开始".to_owned(),
                    english: "Start here today".to_owned(),
                },
                HomeGreeting {
                    chinese: "看看今天的安排".to_owned(),
                    english: "Check today's plan".to_owned(),
                },
                HomeGreeting {
                    chinese: "有想法就记下来".to_owned(),
                    english: "Write down what's on your mind".to_owned(),
                },
                HomeGreeting {
                    chinese: "先完成一件小事".to_owned(),
                    english: "Start with one small task".to_owned(),
                },
                HomeGreeting {
                    chinese: "今天想写点什么？".to_owned(),
                    english: "What would you like to write today?".to_owned(),
                },
                HomeGreeting {
                    chinese: "看看最近的记录".to_owned(),
                    english: "Review your recent notes".to_owned(),
                },
                HomeGreeting {
                    chinese: "先处理重要的事".to_owned(),
                    english: "Start with what matters".to_owned(),
                },
                HomeGreeting {
                    chinese: "打开日历看看".to_owned(),
                    english: "Take a look at the calendar".to_owned(),
                },
                HomeGreeting {
                    chinese: "记录一下当前状态".to_owned(),
                    english: "Record where things stand".to_owned(),
                },
                HomeGreeting {
                    chinese: "今天的进度怎么样？".to_owned(),
                    english: "How is today going?".to_owned(),
                },
                HomeGreeting {
                    chinese: "先快速记一条".to_owned(),
                    english: "Add a quick note".to_owned(),
                },
                HomeGreeting {
                    chinese: "看看时间都去哪了".to_owned(),
                    english: "See where the time went".to_owned(),
                },
                HomeGreeting {
                    chinese: "今天走了多少步？".to_owned(),
                    english: "How many steps today?".to_owned(),
                },
                HomeGreeting {
                    chinese: "查看新的订阅".to_owned(),
                    english: "Check the latest feeds".to_owned(),
                },
                HomeGreeting {
                    chinese: "整理一下当前思路".to_owned(),
                    english: "Organize your current thoughts".to_owned(),
                },
                HomeGreeting {
                    chinese: "从最简单的事开始".to_owned(),
                    english: "Begin with the simplest thing".to_owned(),
                },
                HomeGreeting {
                    chinese: "该记录今天了".to_owned(),
                    english: "Time to record today".to_owned(),
                },
                HomeGreeting {
                    chinese: "翻翻过去写的内容".to_owned(),
                    english: "Browse something you wrote before".to_owned(),
                },
                HomeGreeting {
                    chinese: "现在要做什么？".to_owned(),
                    english: "What comes next?".to_owned(),
                },
                HomeGreeting {
                    chinese: "先看一眼今日数据".to_owned(),
                    english: "Check today's numbers".to_owned(),
                },
                HomeGreeting {
                    chinese: "把刚才的想法留下".to_owned(),
                    english: "Keep that thought before it slips away".to_owned(),
                },
                HomeGreeting {
                    chinese: "今天也按计划推进".to_owned(),
                    english: "Keep today's plan moving".to_owned(),
                },
                HomeGreeting {
                    chinese: "检查一下重要日期".to_owned(),
                    english: "Check the important dates".to_owned(),
                },
                HomeGreeting {
                    chinese: "{name}，欢迎回来".to_owned(),
                    english: "Welcome back, {name}".to_owned(),
                },
            ],
            home_widget_borders_enabled: true,
            daily_event_templates: Vec::new(),
            home_widgets: vec![
                "today".to_owned(),
                "poem".to_owned(),
                "quick_input".to_owned(),
                "meal_photos".to_owned(),
                "year_progress".to_owned(),
            ],
            home_widget_titles: vec![
                "calendar".to_owned(),
                "weather".to_owned(),
                "poem".to_owned(),
                "today".to_owned(),
                "streak".to_owned(),
                "month_diaries".to_owned(),
                "total_words".to_owned(),
                "recent_diary".to_owned(),
                "recent_thought".to_owned(),
                "date_records".to_owned(),
                "quick_input".to_owned(),
                "daily_records".to_owned(),
                "meal_photos".to_owned(),
                "random_diary".to_owned(),
                "year_progress".to_owned(),
                "website".to_owned(),
            ],
        }
    }
}

impl ManagedSettings {
    /// Apply the same canonicalization used when Android restores decoded
    /// settings into DataStore.
    pub fn normalize_android_compatible(&mut self) {
        self.font_scale = android_float(self.font_scale);
        self.theme_color_argb = opaque_argb(self.theme_color_argb);
        self.theme_secondary_colors_argb =
            normalize_theme_secondary_colors(&self.theme_secondary_colors_argb);
        self.image_max_width_dp = self.image_max_width_dp.clamp(120, 2_400);
        self.image_max_height_dp = self.image_max_height_dp.clamp(120, 2_400);
        self.meal_image_compression_quality = self.meal_image_compression_quality.clamp(30, 95);
        self.thought_highlight_color_argb = opaque_argb(self.thought_highlight_color_argb);
        self.thought_editor_max_height_dp = self.thought_editor_max_height_dp.clamp(96, 400);
        self.poetry_font_size_sp = android_float(self.poetry_font_size_sp);
        self.poetry_line_spacing = android_float(self.poetry_line_spacing);
        self.meal_calendar_image_max_height_dp =
            self.meal_calendar_image_max_height_dp.clamp(80, 320);
        self.meal_photo_filter.brightness = android_float(self.meal_photo_filter.brightness);
        self.meal_photo_filter.contrast = android_float(self.meal_photo_filter.contrast);
        self.meal_photo_filter.saturation = android_float(self.meal_photo_filter.saturation);
        self.meal_photo_filter.warmth = android_float(self.meal_photo_filter.warmth);
        self.meal_photo_filter.tint = android_float(self.meal_photo_filter.tint);
    }

    pub fn validate(&self) -> Result<(), String> {
        require_enum(
            "visualStyle",
            &self.visual_style,
            &["MATERIAL", "LIQUID_GLASS", "ORGANIC_FUTURE"],
        )?;
        require_enum("darkMode", &self.dark_mode, &["SYSTEM", "LIGHT", "DARK"])?;
        require_enum("appLanguage", &self.app_language, &["CHINESE", "ENGLISH"])?;
        if !(2..=5).contains(&self.theme_secondary_colors_argb.len()) {
            return Err("themeSecondaryColorsArgb must contain 2 to 5 items".to_owned());
        }
        require_finite_range(
            "fontScale",
            self.font_scale,
            f64::from(0.8_f32),
            f64::from(1.3_f32),
        )?;
        require_len("fileNamePattern", &self.file_name_pattern, 1_024)?;
        require_len("markdownTemplate", &self.markdown_template, 1_000_000)?;
        require_len("imageNamePattern", &self.image_name_pattern, 1_024)?;
        require_integer_range("imageMaxWidthDp", self.image_max_width_dp, 120, 2_400)?;
        require_integer_range("imageMaxHeightDp", self.image_max_height_dp, 120, 2_400)?;
        require_integer_range(
            "mealImageCompressionQuality",
            self.meal_image_compression_quality,
            30,
            95,
        )?;
        require_enum(
            "thoughtDisplayMode",
            &self.thought_display_mode,
            &["SINGLE_LINE", "FULL"],
        )?;
        require_integer_range(
            "thoughtEditorMaxHeightDp",
            self.thought_editor_max_height_dp,
            96,
            400,
        )?;
        require_finite_range("poetryFontSizeSp", self.poetry_font_size_sp, 14.0, 36.0)?;
        require_finite_range("poetryLineSpacing", self.poetry_line_spacing, 1.0, 2.0)?;
        require_enum(
            "poetryTextAlignment",
            &self.poetry_text_alignment,
            &["START", "CENTER"],
        )?;
        require_integer_range(
            "mealCalendarImageMaxHeightDp",
            self.meal_calendar_image_max_height_dp,
            80,
            320,
        )?;
        require_enum(
            "mealCalendarPhotosPerRow",
            &self.meal_calendar_photos_per_row,
            &["TWO", "THREE", "SMART"],
        )?;
        require_finite_range(
            "mealPhotoFilter.brightness",
            self.meal_photo_filter.brightness,
            -1.0,
            1.0,
        )?;
        require_finite_range(
            "mealPhotoFilter.contrast",
            self.meal_photo_filter.contrast,
            0.0,
            2.0,
        )?;
        require_finite_range(
            "mealPhotoFilter.saturation",
            self.meal_photo_filter.saturation,
            0.0,
            2.0,
        )?;
        require_finite_range(
            "mealPhotoFilter.warmth",
            self.meal_photo_filter.warmth,
            -1.0,
            1.0,
        )?;
        require_finite_range(
            "mealPhotoFilter.tint",
            self.meal_photo_filter.tint,
            -1.0,
            1.0,
        )?;
        if self.meal_button_icons.len() != 6 {
            return Err("mealButtonIcons must contain exactly 6 items".to_owned());
        }
        for (index, icon) in self.meal_button_icons.iter().enumerate() {
            if icon.trim().is_empty() || icon.chars().count() > 16 {
                return Err(format!("mealButtonIcons[{index}] is invalid"));
            }
        }
        if self.user_name.chars().count() > 32 {
            return Err("userName is too long".to_owned());
        }
        if self.home_greetings.len() > 100 {
            return Err("Too many home greetings".to_owned());
        }
        for (index, greeting) in self.home_greetings.iter().enumerate() {
            if greeting.chinese.trim().is_empty() && greeting.english.trim().is_empty() {
                return Err(format!("homeGreetings[{index}] is blank"));
            }
            if greeting.chinese.chars().count() > 40 || greeting.english.chars().count() > 40 {
                return Err(format!("homeGreetings[{index}] is too long"));
            }
        }
        if self.daily_event_templates.len() > 100 {
            return Err("Too many daily event templates".to_owned());
        }
        let mut template_ids = std::collections::HashSet::new();
        for (index, item) in self.daily_event_templates.iter().enumerate() {
            if item.id.trim().is_empty()
                || utf16_len(&item.id) > 80
                || !template_ids.insert(item.id.as_str())
            {
                return Err(format!(
                    "dailyEventTemplates[{index}].id is invalid or duplicated"
                ));
            }
            if item.text.trim().is_empty() || utf16_len(&item.text) > 100 {
                return Err(format!("dailyEventTemplates[{index}].text is invalid"));
            }
            if utf16_len(&item.first_unit) > 12 || utf16_len(&item.second_unit) > 12 {
                return Err(format!("dailyEventTemplates[{index}] unit is too long"));
            }
        }
        validate_short_string_list("homeWidgets", &self.home_widgets)?;
        validate_short_string_list("homeWidgetTitles", &self.home_widget_titles)?;
        Ok(())
    }
}

const DEFAULT_THEME_SECONDARY_COLORS_ARGB: [i32; 3] = [
    0xFFC9_6F4Au32 as i32,
    0xFFD4_A72Cu32 as i32,
    0xFF52_7F91u32 as i32,
];

fn opaque_argb(value: i32) -> i32 {
    ((value as u32) | 0xFF00_0000) as i32
}

fn android_float(value: f64) -> f64 {
    f64::from(value as f32)
}

fn normalize_theme_secondary_colors(items: &[i32]) -> Vec<i32> {
    let mut normalized = Vec::with_capacity(5);
    for color in items.iter().copied().map(opaque_argb) {
        if normalized.len() == 5 {
            break;
        }
        if !normalized.contains(&color) {
            normalized.push(color);
        }
    }

    let mut fallback = Vec::with_capacity(5);
    for color in DEFAULT_THEME_SECONDARY_COLORS_ARGB
        .iter()
        .copied()
        .map(opaque_argb)
    {
        if !fallback.contains(&color) {
            fallback.push(color);
        }
    }
    for color in DEFAULT_THEME_SECONDARY_COLORS_ARGB
        .iter()
        .copied()
        .map(opaque_argb)
    {
        if fallback.len() >= 2 {
            break;
        }
        if !fallback.contains(&color) {
            fallback.push(color);
        }
    }

    if normalized.is_empty() {
        return fallback;
    }
    if normalized.len() >= 2 {
        return normalized;
    }
    for color in fallback {
        if normalized.len() >= 2 {
            break;
        }
        if !normalized.contains(&color) {
            normalized.push(color);
        }
    }
    normalized
}

fn require_enum(field: &str, value: &str, allowed: &[&str]) -> Result<(), String> {
    if allowed.contains(&value) {
        Ok(())
    } else {
        Err(format!("{field} has an unsupported value"))
    }
}

fn require_finite_range(field: &str, value: f64, minimum: f64, maximum: f64) -> Result<(), String> {
    if value.is_finite() && (minimum..=maximum).contains(&value) {
        Ok(())
    } else {
        Err(format!("{field} is out of range"))
    }
}

fn require_integer_range(
    field: &str,
    value: i32,
    minimum: i32,
    maximum: i32,
) -> Result<(), String> {
    if (minimum..=maximum).contains(&value) {
        Ok(())
    } else {
        Err(format!("{field} is out of range"))
    }
}

fn require_len(field: &str, value: &str, maximum: usize) -> Result<(), String> {
    if utf16_len(value) <= maximum {
        Ok(())
    } else {
        Err(format!("{field} is too long"))
    }
}

fn validate_short_string_list(field: &str, items: &[String]) -> Result<(), String> {
    if items.len() > 1_000 {
        return Err(format!("{field} contains too many items"));
    }
    let mut unique = std::collections::HashSet::new();
    for (index, item) in items.iter().enumerate() {
        if utf16_len(item) > 256 || !unique.insert(item) {
            return Err(format!("{field}[{index}] is invalid or duplicated"));
        }
    }
    Ok(())
}

fn utf16_len(value: &str) -> usize {
    value.encode_utf16().count()
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalPaths {
    pub diary_path: Option<String>,
    pub media_path: Option<String>,
    pub backup_path: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CompatibilityShadow {
    pub ciphertext: Vec<u8>,
    pub source_sha256: String,
    pub imported_at: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupPreview {
    pub format_version: i32,
    pub exported_at: i64,
    pub thought_count: usize,
    pub category_count: usize,
    pub favorite_count: usize,
    pub date_record_count: usize,
    pub poem_count: usize,
    pub preserved_top_level_keys: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportReceipt {
    pub imported_at: i64,
    pub source_sha256: String,
    pub thought_count: usize,
    pub category_count: usize,
    pub favorite_count: usize,
    pub date_record_count: usize,
    pub poem_count: usize,
}

/// Internal, versioned recovery data written before a destructive core import.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CoreSnapshot {
    pub schema_version: i32,
    pub created_at: i64,
    pub settings: ManagedSettings,
    pub thoughts: Vec<Thought>,
    pub categories: Vec<ThoughtCategory>,
    pub date_records: Vec<DateRecord>,
    pub poems: Vec<SavedPoem>,
    pub local_paths: LocalPaths,
    #[serde(default)]
    pub encrypted_compatibility_shadow: Option<Vec<u8>>,
    #[serde(default)]
    pub compatibility_source_sha256: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn managed_settings_match_android_utf16_and_code_point_limits() {
        let mut settings = ManagedSettings {
            file_name_pattern: "名".repeat(1_024),
            daily_event_templates: vec![DailyEventTemplate {
                id: "项".repeat(80),
                text: "文".repeat(100),
                first_unit: "次".repeat(12),
                second_unit: "组".repeat(12),
            }],
            meal_button_icons: vec!["😀".repeat(16); 6],
            ..ManagedSettings::default()
        };
        settings.validate().expect("Android-valid lengths");

        settings.meal_button_icons[0] = "😀".repeat(17);
        assert!(settings.validate().is_err());
    }

    #[test]
    fn managed_settings_normalize_like_android_restore() {
        let mut settings = ManagedSettings {
            theme_color_argb: 0x0012_3456,
            theme_secondary_colors_argb: vec![0x0012_3456, 0xFF12_3456u32 as i32],
            image_max_width_dp: -1,
            image_max_height_dp: 9_999,
            meal_image_compression_quality: 0,
            thought_highlight_color_argb: 0x0001_0203,
            thought_editor_max_height_dp: 999,
            meal_calendar_image_max_height_dp: -5,
            ..ManagedSettings::default()
        };

        settings.normalize_android_compatible();

        assert_eq!(settings.theme_color_argb, 0xFF12_3456u32 as i32);
        assert_eq!(
            settings.theme_secondary_colors_argb,
            vec![0xFF12_3456u32 as i32, 0xFFC9_6F4Au32 as i32]
        );
        assert_eq!(settings.image_max_width_dp, 120);
        assert_eq!(settings.image_max_height_dp, 2_400);
        assert_eq!(settings.meal_image_compression_quality, 30);
        assert_eq!(settings.thought_highlight_color_argb, 0xFF01_0203u32 as i32);
        assert_eq!(settings.thought_editor_max_height_dp, 400);
        assert_eq!(settings.meal_calendar_image_max_height_dp, 80);
        settings.validate().expect("normalized settings");
    }

    #[test]
    fn default_home_greetings_match_android_catalog() {
        let greetings = ManagedSettings::default().home_greetings;
        assert_eq!(greetings.len(), 24);
        assert_eq!(greetings[1].chinese, "看看今天的安排");
        assert_eq!(greetings[22].english, "Check the important dates");
        assert_eq!(greetings[23].english, "Welcome back, {name}");
    }
}
