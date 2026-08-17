package com.deskcubby.app.data.sync

/**
 * Explicit persistence scope for every sync-relevant setting key.
 *
 * GLOBAL values are the only settings that may enter record sync (as the scoped
 * [GlobalSettingsSyncCodec] projection). DEVICE values stay on one device. SECRET values never
 * enter sync or manual backup JSON.
 */
enum class SettingSyncScope {
    GLOBAL,
    DEVICE,
    SECRET,
}

object SettingSyncScopes {
    private val globalKeys = setOf(
        "userName",
        "homeGreetings",
        "visualStyle",
        "customTheme",
        "darkMode",
        "appLanguage",
        "themeColorArgb",
        "themeSecondaryColorsArgb",
        "tutorialModeEnabled",
        "fileNamePattern",
        "markdownTemplate",
        "imageNamePattern",
        "imageMaxWidthDp",
        "imageMaxHeightDp",
        "markdownHeadingSizesSp",
        "mealImageCompressionEnabled",
        "mealImageCompressionQuality",
        "saveOriginalToGallery",
        "photoLocationEnabled",
        "browserHomeUrl",
        "browserTheme",
        "browserDesktopMode",
        "thoughtDisplayMode",
        "thoughtHighlightColorArgb",
        "thoughtEditorMaxHeightDp",
        "poetryShowSource",
        "poetryShowQuoteMark",
        "poetrySevenCharacterWrapEnabled",
        "mealCalendarImageMaxHeightDp",
        "mealCalendarShowCaptions",
        "mealCalendarWrapEnabled",
        "mealCalendarPhotosPerRow",
        "mealPhotoFilter",
        "mealButtonsUseIcons",
        "mealButtonIcons",
        "dailyEventTemplates",
        "rssMaxItemsPerFeed",
        "rssShowSummaries",
        "aiEndpointUrl",
        "aiModel",
        "aiSystemPrompt",
        "aiTemperature",
        "aiAllowInsecureHttp",
        "aiConfigs",
        "aiChatConfigId",
        "agentEnabledSources",
        "agentPermissionMode",
        "agentPrompt",
        "calorieEstimationEnabled",
        "calorieTextConfigId",
        "calorieImageConfigId",
        "calorieVisionPrompt",
        "calorieTextPrompt",
        "game2048AnimationSpeed",
        "reader.background",
        "reader.fontSizeSp",
        "reader.lineHeightMultiplier",
        "reader.paragraphSpacingDp",
        "reader.showProgressPercentage",
        "reader.chapterDetectionMode",
    )

    private val deviceKeys = setOf(
        "orientationPreference",
        "compactMode",
        "backgroundImageUri",
        "backgroundImageOpacity",
        "backgroundImageBlurDp",
        "tutorialAcknowledgedPages",
        "useChineseLauncherName",
        "launcherIcon",
        "backupTreeUri",
        "cloudSyncEnabled",
        "cloudSyncConfigs",
        "diaryTreeUri",
        "mediaTreeUri",
        "notesTreeUri",
        "defaultDiaryFoldersGrantUri",
        "lastBrowserUrl",
        "thoughtSplitRatio",
        "thoughtRowHeightDp",
        "lastThoughtPageKey",
        "vaultRowHeightDp",
        "poetryFontUri",
        "poetryFontSizeSp",
        "poetryLineSpacing",
        "poetryTextAlignment",
        "aiPageFontSizeSp",
        "aiReplyBoxWidthDp",
        "usageTrackingEnabled",
        "stepTrackingEnabled",
        "navigationIntroAcknowledged",
        "navItems",
        "morePageOrder",
        "morePageColumns",
        "morePageShowDescriptions",
        "defaultPage",
        "bottomNavShowLabels",
        "musicVisualizerEnabled",
        "musicVisualizerStyle",
        "musicVisualizerFrequencyMode",
        "musicVisualizerMinFrequencyHz",
        "musicVisualizerMaxFrequencyHz",
        "homeWidgetBordersEnabled",
        "homeWidgets",
        "homeGameShortcuts",
        "homeWidgetTitles",
        "desktopWidgetConfigs",
        "reader.pdfZoomPercent",
        "reader.orientation",
        "reader.libraryLayout",
    )

    private val secretKeys = setOf(
        "ai.apiKey",
        "webDavPassword",
        "s3AccessKey",
        "s3SecretKey",
        "s3SessionToken",
    )

    fun scope(key: String): SettingSyncScope = when (key) {
        in globalKeys -> SettingSyncScope.GLOBAL
        in deviceKeys -> SettingSyncScope.DEVICE
        in secretKeys -> SettingSyncScope.SECRET
        else -> SettingSyncScope.DEVICE
    }

    fun globalKeys(): Set<String> = globalKeys

    fun deviceKeys(): Set<String> = deviceKeys

    fun secretKeys(): Set<String> = secretKeys
}
