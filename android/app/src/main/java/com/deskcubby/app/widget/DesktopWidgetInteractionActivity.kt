package com.deskcubby.app.widget

import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.deskcubby.app.MainActivity
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.MealCategory
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.CalorieEstimationRepository
import com.deskcubby.app.data.repository.DateRecordRepository
import com.deskcubby.app.data.repository.DiaryFileRepository
import com.deskcubby.app.data.repository.ThoughtRepository
import com.deskcubby.app.data.sync.CloudSyncManualScheduler
import com.deskcubby.app.data.sync.CloudSyncRunMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import java.io.File
import java.time.LocalDate
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Small, non-exported UI boundary for interactions RemoteViews cannot host itself.
 *
 * Launcher widgets cannot contain an editable EditText and cannot retain a photo URI beyond an
 * Activity result. This proxy therefore owns the keyboard/photo picker and keeps itself visible
 * until the selected content is durably written through the normal repositories.
 */
@AndroidEntryPoint
class DesktopWidgetInteractionActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var thoughtRepository: ThoughtRepository
    @Inject lateinit var diaryFileRepository: DiaryFileRepository
    @Inject lateinit var calorieEstimationRepository: CalorieEstimationRepository
    @Inject lateinit var dateRecordRepository: DateRecordRepository

    private var settings: AppSettings = AppSettings()
    private var settingsLoaded: Boolean = false
    private var progressDialog: AlertDialog? = null
    private var input: EditText? = null
    private var pendingCameraPath: String? = null
    private var externalSourceLaunched: Boolean = false

    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        externalSourceLaunched = false
        if (uri == null) {
            finish()
        } else {
            lifecycleScope.launch {
                ensureSettingsLoaded()
                persistMealPhoto(uri)
            }
        }
    }

    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        externalSourceLaunched = false
        val file = pendingCameraPath?.let(::File)
        pendingCameraPath = null
        if (captured && file?.isFile == true) {
            val uri = runCatching {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            }.getOrNull()
            if (uri == null) {
                file.delete()
                showMealFailure()
            } else {
                lifecycleScope.launch {
                    ensureSettingsLoaded()
                    persistMealPhoto(uri, sourceFile = file)
                }
            }
        } else {
            file?.delete()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCameraPath = savedInstanceState?.getString(STATE_CAMERA_PATH)
        externalSourceLaunched = savedInstanceState?.getBoolean(STATE_EXTERNAL_LAUNCHED) == true
        lifecycleScope.launch {
            ensureSettingsLoaded()
            when (intent.getStringExtra(EXTRA_ACTION)) {
                ACTION_QUICK_INPUT -> showQuickInput(savedInstanceState?.getString(STATE_INPUT).orEmpty())
                ACTION_MEAL_PHOTO -> if (
                    DesktopWidgetInteractionPolicy.shouldPromptForMealSource(
                        pendingCameraPath,
                        externalSourceLaunched,
                    )
                ) {
                    launchMealPhotoPicker()
                }
                ACTION_FORCE_CLOUD -> showForceCloudConfirmation()
                ACTION_DATE_RECORD_ADD -> showDateRecordInput()
                ACTION_DAILY_RECORD -> showDailyRecordInput()
                ACTION_VIEW_THOUGHT -> showThought(intent.getLongExtra(EXTRA_THOUGHT_ID, -1L))
                ACTION_OPEN_DIARY -> openTrustedDiary()
                else -> finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_INPUT, input?.text?.toString().orEmpty())
        outState.putString(STATE_CAMERA_PATH, pendingCameraPath)
        outState.putBoolean(STATE_EXTERNAL_LAUNCHED, externalSourceLaunched)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        progressDialog?.dismiss()
        progressDialog = null
        super.onDestroy()
    }

    private suspend fun ensureSettingsLoaded() {
        if (settingsLoaded) return
        settings = try {
            settingsRepository.settings.first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AppSettings()
        }
        settingsLoaded = true
    }

    private suspend fun showThought(thoughtId: Long) {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        val thought = thoughtRepository.active.first().firstOrNull { it.id == thoughtId }
        if (thought == null) {
            Toast.makeText(
                this,
                if (english) "This thought is no longer available" else "这条小巧思已不存在",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (english) "Thought" else "小巧思")
            .setMessage(thought.content)
            .setNegativeButton(if (english) "Close" else "关闭") { _, _ -> finish() }
            .setPositiveButton(if (english) "View all" else "查看全部") { _, _ ->
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(DesktopWidgetRenderer.EXTRA_START_ROUTE, NavItemId.THOUGHT.route)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun openTrustedDiary() {
        val diaryUri = intent.getStringExtra(EXTRA_DIARY_URI)
        if (diaryUri.isNullOrBlank()) {
            finish()
            return
        }
        val token = DesktopWidgetNavigationTokenStore.issueDiaryToken(diaryUri)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(DesktopWidgetRenderer.EXTRA_START_ROUTE, NavItemId.DIARY.route)
                .putExtra(DesktopWidgetRenderer.EXTRA_DIARY_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

    private fun showQuickInput(initialValue: String) {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        val editor = EditText(this).apply {
            hint = if (english) "Capture a thought" else "记录一条小巧思"
            setText(initialValue)
            setSelection(text.length)
            maxLines = 6
            filters = arrayOf(InputFilter.LengthFilter(MAX_THOUGHT_CHARS))
        }
        input = editor
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (english) "Quick input" else "快速输入")
            .setView(editor)
            .setNegativeButton(if (english) "Cancel" else "取消") { _, _ -> finish() }
            .setPositiveButton(if (english) "Send" else "发送", null)
            .setOnCancelListener { finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val content = editor.text?.toString()?.trim().orEmpty()
                if (content.isEmpty()) {
                    editor.error = if (english) "Enter some text" else "请输入内容"
                } else {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    persistThought(content, dialog)
                }
            }
            editor.requestFocus()
            editor.post {
                getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.setOnDismissListener {
            input = null
            if (!isFinishing && !isChangingConfigurations) finish()
        }
        dialog.show()
    }

    private fun showDateRecordInput() {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = (24 * density).toInt()
            setPadding(horizontal, 0, horizontal, 0)
        }
        val nameInput = EditText(this).apply {
            hint = if (english) "Name" else "名称"
            filters = arrayOf(InputFilter.LengthFilter(256))
        }
        val iconInput = EditText(this).apply {
            hint = if (english) "Icon" else "图标"
            setText("🎯")
            filters = arrayOf(InputFilter.LengthFilter(64))
        }
        val dateInput = EditText(this).apply {
            hint = "yyyy-MM-dd"
            setText(LocalDate.now().toString())
            filters = arrayOf(InputFilter.LengthFilter(10))
        }
        container.addView(nameInput)
        container.addView(iconInput)
        container.addView(dateInput)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (english) "Add date record" else "添加日期记录")
            .setView(container)
            .setNegativeButton(if (english) "Cancel" else "取消") { _, _ -> finish() }
            .setPositiveButton(if (english) "Add" else "添加", null)
            .setOnCancelListener { finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text?.toString().orEmpty()
                val icon = iconInput.text?.toString().orEmpty()
                val date = dateInput.text?.toString().orEmpty()
                if (name.isBlank()) {
                    nameInput.error = if (english) "Enter a name" else "请输入名称"
                    return@setOnClickListener
                }
                if (runCatching { LocalDate.parse(date) }.isFailure) {
                    dateInput.error = if (english) "Use a valid yyyy-MM-dd date" else "请输入有效的 yyyy-MM-dd 日期"
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            dateRecordRepository.create(name, icon.ifBlank { "🎯" }, date)
                        }
                        requestWidgetRefresh()
                        Toast.makeText(
                            this@DesktopWidgetInteractionActivity,
                            if (english) "Date record added" else "日期记录已添加",
                            Toast.LENGTH_SHORT,
                        ).show()
                        dialog.dismiss()
                        finish()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        nameInput.error = if (english) "Could not add the record" else "添加失败"
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showDailyRecordInput() {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        val templateId = intent.getStringExtra(EXTRA_DAILY_TEMPLATE_ID)
        val template = settings.dailyEventTemplates.firstOrNull { it.id == templateId }
        if (template == null) {
            Toast.makeText(
                this,
                if (english) "This daily record no longer exists" else "这条日常记录模板已不存在",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
            return
        }
        if (settings.diaryTreeUri == null) {
            Toast.makeText(
                this,
                if (english) "Choose a diary folder in DeskCubby first" else "请先在 DeskCubby 中选择日记目录",
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }
        val editor = EditText(this).apply {
            setText(template.text)
            setSelection(text.length)
            maxLines = 8
            filters = arrayOf(InputFilter.LengthFilter(MAX_DAILY_RECORD_CHARS))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (english) "Daily record" else "日常记录")
            .setView(editor)
            .setNegativeButton(if (english) "Cancel" else "取消") { _, _ -> finish() }
            .setPositiveButton(if (english) "Add to today" else "加入今日日记", null)
            .setOnCancelListener { finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entry = editor.text?.toString()?.trim().orEmpty()
                if (entry.isEmpty()) {
                    editor.error = if (english) "Enter some text" else "请输入内容"
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            diaryFileRepository.appendTextToToday(entry, settings)
                            try {
                                diaryFileRepository.scan(settings)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // The Markdown write already succeeded.
                            }
                        }
                        requestWidgetRefresh()
                        Toast.makeText(
                            this@DesktopWidgetInteractionActivity,
                            if (english) "Added to today's diary" else "已加入今日日记",
                            Toast.LENGTH_SHORT,
                        ).show()
                        dialog.dismiss()
                        finish()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        editor.error = if (english) "Could not add the daily record" else "日常记录添加失败"
                    }
                }
            }
        }
        dialog.show()
    }

    private fun persistThought(content: String, dialog: AlertDialog) {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { thoughtRepository.create(content) }
                requestWidgetRefresh()
                Toast.makeText(
                    this@DesktopWidgetInteractionActivity,
                    if (english) "Thought saved" else "小巧思已保存",
                    Toast.LENGTH_SHORT,
                ).show()
                dialog.dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                input?.error = if (english) "Could not save" else "保存失败"
            }
        }
    }

    private fun launchMealPhotoPicker() {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        val category = selectedMealCategory()
        if (category == null) {
            finish()
            return
        }
        if (settings.diaryTreeUri == null || settings.mediaTreeUri == null) {
            Toast.makeText(
                this,
                if (english) "Choose diary and media folders in DeskCubby first"
                else "请先在 DeskCubby 中选择日记和媒体目录",
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(
                if (english) "Add ${category.englishLabel} photo"
                else "添加${category.chineseLabel}图片",
            )
            .setItems(
                if (english) arrayOf("Take photo", "Choose from photos")
                else arrayOf("拍摄照片", "从相册选择"),
            ) { _, which ->
                if (which == 0) launchCamera() else launchPhotoPicker()
            }
            .setNegativeButton(if (english) "Cancel" else "取消") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun launchPhotoPicker() {
        runCatching {
            externalSourceLaunched = true
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.onFailure {
            externalSourceLaunched = false
            showMealFailure()
        }
    }

    private fun launchCamera() {
        var file: File? = null
        runCatching {
            val directory = File(cacheDir, "meal-camera").apply {
                check(exists() || mkdirs()) { "Could not create camera cache" }
            }
            file = File.createTempFile("meal-widget-", ".jpg", directory)
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                requireNotNull(file),
            )
            pendingCameraPath = file?.absolutePath
            externalSourceLaunched = true
            camera.launch(uri)
        }.onFailure {
            file?.delete()
            pendingCameraPath = null
            externalSourceLaunched = false
            showMealFailure()
        }
    }

    private fun persistMealPhoto(uri: Uri, sourceFile: File? = null) {
        val category = selectedMealCategory() ?: run {
            finish()
            return
        }
        val english = settings.appLanguage == AppLanguage.ENGLISH
        progressDialog = AlertDialog.Builder(this)
            .setMessage(if (english) "Adding photo…" else "正在添加照片…")
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val media = diaryFileRepository.appendImageToToday(
                        uri,
                        if (english) category.englishLabel else category.chineseLabel,
                        settings,
                    )
                    if (settings.calorieEstimationEnabled) {
                        try {
                            val estimate = calorieEstimationRepository.estimate(
                                media.documentUri,
                                settings,
                            )
                            diaryFileRepository.setMealPhotoEstimate(
                                media.fileName,
                                estimate,
                                settings,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // The photo and Markdown are already durable; optional AI estimation
                            // must not turn a successful capture into a duplicate-retry prompt.
                        }
                    }
                    // The image and Markdown are already durable. Index refresh is best effort.
                    try {
                        diaryFileRepository.scan(settings)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The next normal diary scan will rebuild the index.
                    }
                }
                requestWidgetRefresh()
                Toast.makeText(
                    this@DesktopWidgetInteractionActivity,
                    if (english) "${category.englishLabel} photo added"
                    else "${category.chineseLabel}图片已加入今日日记",
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                progressDialog?.dismiss()
                progressDialog = null
                Toast.makeText(
                    this@DesktopWidgetInteractionActivity,
                    if (english) "Could not add the photo" else "图片添加失败",
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            } finally {
                sourceFile?.delete()
            }
        }
    }

    private fun showMealFailure() {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        Toast.makeText(
            this,
            if (english) "Could not open the photo source" else "无法打开照片来源",
            Toast.LENGTH_SHORT,
        ).show()
        finish()
    }

    private fun showForceCloudConfirmation() {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        val mode = intent.getStringExtra(EXTRA_CLOUD_MODE)
            ?.let { name -> CloudSyncRunMode.entries.firstOrNull { it.name == name } }
            ?.takeIf { it != CloudSyncRunMode.NORMAL }
            ?: run {
                finish()
                return
            }
        val download = mode == CloudSyncRunMode.FORCE_DOWNLOAD
        val enabledSourceCount = settings.cloudSyncConfigs.count { it.enabled }
        val availability = DesktopWidgetInteractionPolicy.forceCloudAvailability(
            syncEnabled = settings.cloudSyncEnabled,
            enabledSourceCount = enabledSourceCount,
            download = download,
        )
        if (availability != ForceCloudAvailability.READY) {
            val message = when (availability) {
                ForceCloudAvailability.SYNC_DISABLED -> if (english) {
                    "Cloud sync is turned off. Open settings to review it before continuing."
                } else {
                    "云端同步尚未开启。请先进入设置检查并明确开启。"
                }
                ForceCloudAvailability.NO_ENABLED_SOURCE -> if (english) {
                    "No cloud source is enabled. Open settings to configure one first."
                } else {
                    "没有已启用的云端来源。请先进入设置完成配置。"
                }
                ForceCloudAvailability.DOWNLOAD_REQUIRES_ONE_SOURCE -> if (english) {
                    "Force download requires exactly one enabled cloud source."
                } else {
                    "强制下载需要恰好一个已启用的云端来源。"
                }
                ForceCloudAvailability.READY -> return
            }
            AlertDialog.Builder(this)
                .setTitle(if (english) "Cloud sync unavailable" else "云端同步不可用")
                .setMessage(message)
                .setNegativeButton(if (english) "Cancel" else "取消") { _, _ -> finish() }
                .setPositiveButton(if (english) "Open settings" else "打开设置") { _, _ ->
                    openSettings()
                }
                .setOnCancelListener { finish() }
                .show()
            return
        }

        val title = if (download) {
            if (english) "Force download?" else "确认强制下载？"
        } else {
            if (english) "Force upload?" else "确认强制上传？"
        }
        val risk = if (download) {
            if (english) {
                "Force download uses the single currently enabled cloud source. New remote items are downloaded. Different items at the same path use remote data only while the local file still matches its scanned snapshot. Local-only items are not deleted, and concurrent local edits are preserved with a conflict copy."
            } else {
                "强制下载仅使用当前唯一的已启用云端来源。云端新增项目会下载；同路径内容不同时，将仅在本机文件仍匹配扫描快照时采用云端版本。本机独有项目不会删除，并发本机修改会保留并产生冲突副本。"
            }
        } else if (english) {
            "New local items are uploaded. Different items at the same path replace remote data only if its scanned version still matches. Remote-only items are not deleted, and later remote edits stop the overwrite."
        } else {
            "本机新增项目会上传；同路径内容不同时，将以扫描到的远端版本为条件覆盖远端。远端独有项目不会删除，扫描后发生的远端修改会阻止覆盖。"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(risk)
            .setNegativeButton(if (english) "Cancel" else "取消") { _, _ -> finish() }
            .setPositiveButton(
                if (download) {
                    if (english) "Force download" else "强制下载"
                } else {
                    if (english) "Force upload" else "强制上传"
                },
            ) { _, _ -> enqueueForcedCloudSync(mode) }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun enqueueForcedCloudSync(mode: CloudSyncRunMode) {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        val queued = CloudSyncManualScheduler.enqueue(this, mode)
        Toast.makeText(
            this,
            if (queued) {
                when (mode) {
                    CloudSyncRunMode.FORCE_UPLOAD ->
                        if (english) "Forced upload queued" else "强制上传已加入队列"
                    CloudSyncRunMode.FORCE_DOWNLOAD ->
                        if (english) "Forced download queued" else "强制下载已加入队列"
                    CloudSyncRunMode.NORMAL ->
                        if (english) "Sync queued" else "同步已加入队列"
                }
            } else {
                if (english) "Could not queue the sync; try again" else "无法加入同步队列，请稍后重试"
            },
            Toast.LENGTH_LONG,
        ).show()
        if (queued) requestWidgetRefresh()
        finish()
    }

    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(DesktopWidgetRenderer.EXTRA_START_ROUTE, NavItemId.SETTINGS.route)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

    private fun selectedMealCategory(): MealCategory? = intent
        .getStringExtra(EXTRA_MEAL_CATEGORY)
        ?.let { key -> MealCategory.entries.firstOrNull { it.key == key } }

    private fun requestWidgetRefresh() {
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        DeskCubbyWidgetProvider.requestUpdate(
            this,
            widgetId.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }?.let { intArrayOf(it) },
        )
    }

    companion object {
        private const val EXTRA_ACTION = "com.deskcubby.app.extra.WIDGET_ACTION"
        private const val EXTRA_MEAL_CATEGORY = "com.deskcubby.app.extra.WIDGET_MEAL_CATEGORY"
        private const val EXTRA_CLOUD_MODE = "com.deskcubby.app.extra.WIDGET_CLOUD_MODE"
        private const val EXTRA_DAILY_TEMPLATE_ID = "com.deskcubby.app.extra.WIDGET_DAILY_TEMPLATE_ID"
        private const val EXTRA_THOUGHT_ID = "com.deskcubby.app.extra.WIDGET_THOUGHT_ID"
        private const val EXTRA_DIARY_URI = "com.deskcubby.app.extra.WIDGET_DIARY_URI"
        private const val STATE_INPUT = "widget_input"
        private const val STATE_CAMERA_PATH = "widget_camera_path"
        private const val STATE_EXTERNAL_LAUNCHED = "widget_external_launched"
        private const val ACTION_QUICK_INPUT = "quick_input"
        private const val ACTION_MEAL_PHOTO = "meal_photo"
        private const val ACTION_FORCE_CLOUD = "force_cloud"
        private const val ACTION_DATE_RECORD_ADD = "date_record_add"
        private const val ACTION_DAILY_RECORD = "daily_record"
        private const val ACTION_VIEW_THOUGHT = "view_thought"
        private const val ACTION_OPEN_DIARY = "open_diary"
        private const val MAX_THOUGHT_CHARS = 4_000
        private const val MAX_DAILY_RECORD_CHARS = 8_000

        fun quickInputPendingIntent(context: Context, appWidgetId: Int): PendingIntent =
            pendingIntent(context, appWidgetId, ACTION_QUICK_INPUT, null)

        fun mealPhotoPendingIntent(
            context: Context,
            appWidgetId: Int,
            category: MealCategory,
        ): PendingIntent = pendingIntent(context, appWidgetId, ACTION_MEAL_PHOTO, category.key)

        fun forceCloudPendingIntent(
            context: Context,
            appWidgetId: Int,
            mode: CloudSyncRunMode,
        ): PendingIntent {
            require(mode != CloudSyncRunMode.NORMAL)
            val identity = "$appWidgetId/$ACTION_FORCE_CLOUD/${mode.name}"
            val intent = Intent(context, DesktopWidgetInteractionActivity::class.java)
                .setData(Uri.parse("deskcubby://widget-interaction/$identity"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(EXTRA_ACTION, ACTION_FORCE_CLOUD)
                .putExtra(EXTRA_CLOUD_MODE, mode.name)
            return PendingIntent.getActivity(
                context,
                identity.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun dateRecordAddPendingIntent(context: Context, appWidgetId: Int): PendingIntent =
            pendingIntent(context, appWidgetId, ACTION_DATE_RECORD_ADD, null)

        fun dailyRecordPendingIntent(
            context: Context,
            appWidgetId: Int,
            templateId: String,
        ): PendingIntent {
            val identity = widgetPendingIdentity(appWidgetId.toString(), ACTION_DAILY_RECORD, templateId)
            val intent = Intent(context, DesktopWidgetInteractionActivity::class.java)
                .setData(Uri.parse("deskcubby://widget-interaction/$identity"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(EXTRA_ACTION, ACTION_DAILY_RECORD)
                .putExtra(EXTRA_DAILY_TEMPLATE_ID, templateId)
            return PendingIntent.getActivity(
                context,
                identity.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun thoughtPendingIntent(
            context: Context,
            appWidgetId: Int,
            thoughtId: Long,
        ): PendingIntent {
            val identity = "$appWidgetId/$ACTION_VIEW_THOUGHT/$thoughtId"
            val intent = Intent(context, DesktopWidgetInteractionActivity::class.java)
                .setData(Uri.parse("deskcubby://widget-interaction/$identity"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(EXTRA_ACTION, ACTION_VIEW_THOUGHT)
                .putExtra(EXTRA_THOUGHT_ID, thoughtId)
            return PendingIntent.getActivity(
                context,
                identity.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun diaryPendingIntent(
            context: Context,
            appWidgetId: Int,
            diaryUri: String,
        ): PendingIntent {
            val identity = widgetPendingIdentity(appWidgetId.toString(), ACTION_OPEN_DIARY, diaryUri)
            val intent = Intent(context, DesktopWidgetInteractionActivity::class.java)
                .setData(Uri.parse("deskcubby://widget-interaction/$identity"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(EXTRA_ACTION, ACTION_OPEN_DIARY)
                .putExtra(EXTRA_DIARY_URI, diaryUri)
            return PendingIntent.getActivity(
                context,
                identity.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun pendingIntent(
            context: Context,
            appWidgetId: Int,
            action: String,
            categoryKey: String?,
        ): PendingIntent {
            val identity = listOf(appWidgetId.toString(), action, categoryKey.orEmpty()).joinToString("/")
            val intent = Intent(context, DesktopWidgetInteractionActivity::class.java)
                .setData(Uri.parse("deskcubby://widget-interaction/$identity"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(EXTRA_ACTION, action)
                .putExtra(EXTRA_MEAL_CATEGORY, categoryKey)
            return PendingIntent.getActivity(
                context,
                identity.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}

internal fun widgetPendingIdentity(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    return digest
}
