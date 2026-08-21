package com.deskcubby.app.widget

import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.deskcubby.app.MainActivity
import com.deskcubby.app.data.local.AiTaskTypeEntity
import com.deskcubby.app.data.media.GalleryOriginalStager
import com.deskcubby.app.data.media.GalleryOriginalStaging
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.MealCategory
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.DateRecordRepository
import com.deskcubby.app.data.repository.DiaryFileRepository
import com.deskcubby.app.data.repository.ThoughtRepository
import com.deskcubby.app.data.structuredrecords.JournalDayEngine
import com.deskcubby.app.data.structuredrecords.StructuredFieldType
import com.deskcubby.app.data.structuredrecords.StructuredRecordSegment
import com.deskcubby.app.data.structuredrecords.StructuredRecordsRepository
import com.deskcubby.app.data.structuredrecords.StructuredWorkspaceRepository
import com.deskcubby.app.data.sync.CloudSyncManualScheduler
import com.deskcubby.app.data.sync.CloudSyncRunMode
import com.deskcubby.app.data.taskqueue.AiTaskQueue
import com.deskcubby.app.data.taskqueue.CalorieSingleTaskPayload
import com.deskcubby.app.ui.theme.translate
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class DesktopWidgetInteractionActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var thoughtRepository: ThoughtRepository
    @Inject lateinit var diaryFileRepository: DiaryFileRepository
    @Inject lateinit var aiTaskQueue: AiTaskQueue
    @Inject lateinit var dateRecordRepository: DateRecordRepository
    @Inject lateinit var structuredWorkspaceRepository: StructuredWorkspaceRepository
    @Inject lateinit var structuredRecordsRepository: StructuredRecordsRepository
    @Inject lateinit var thoughtDraftStore: DesktopWidgetThoughtDraftStore

    private var settings: AppSettings = AppSettings()
    private var settingsLoaded = false
    private var progressDialog: AlertDialog? = null
    private var input: EditText? = null
    private var pendingCameraPath: String? = null
    private var externalSourceLaunched = false
    private var switchingToCategoryPicker = false

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
                ACTION_QUICK_INPUT -> showQuickInput(
                    savedInstanceState?.getString(STATE_INPUT)
                        ?: thoughtDraftStore.get(currentAppWidgetId()),
                    intent.getLongExtra(EXTRA_QUICK_INPUT_CATEGORY, -1L),
                )
                ACTION_QUICK_INPUT_CATEGORIES -> showQuickInputCategoryPicker()
                ACTION_MEAL_PHOTO -> if (
                    DesktopWidgetInteractionPolicy.shouldPromptForMealSource(
                        pendingCameraPath,
                        externalSourceLaunched,
                    )
                ) launchMealPhotoPicker()
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
                translate("这条小巧思已不存在", "This thought is no longer available", language(english)),
                Toast.LENGTH_SHORT,
            ).show()
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(translate("小巧思", "Thought", language(english)))
            .setMessage(thought.content)
            .setNegativeButton(translate("关闭", "Close", language(english))) { _, _ -> finish() }
            .setPositiveButton(translate("查看全部", "View all", language(english))) { _, _ ->
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

    private fun showQuickInputCategoryPicker(initialValue: String = "") {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        lifecycleScope.launch {
            val categories = runCatching { thoughtRepository.categories.first() }.getOrDefault(emptyList())
            val labels = buildList {
                add(translate("无分类", "No category", language(english)))
                categories.forEach { add(it.name) }
            }
            AlertDialog.Builder(this@DesktopWidgetInteractionActivity)
                .setTitle(translate("发送到分类", "Send to category", language(english)))
                .setItems(labels.toTypedArray()) { _, which ->
                    val categoryId = if (which == 0) -1L else categories[which - 1].id
                    val label = if (which == 0) null else categories[which - 1].name
                    showQuickInput(initialValue, categoryId, label)
                }
                .setNegativeButton(translate("取消", "Cancel", language(english))) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    private fun showQuickInput(
        initialValue: String,
        categoryId: Long = -1L,
        categoryLabel: String? = null,
    ) {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        val editor = EditText(this).apply {
            hint = translate("记录一条小巧思", "Capture a thought", language(english))
            setText(initialValue)
            setSelection(text.length)
            maxLines = 6
            filters = arrayOf(InputFilter.LengthFilter(MAX_THOUGHT_CHARS))
        }
        input = editor
        val dialogTitle = if (categoryLabel != null) {
            translate("快速输入 → $categoryLabel", "Quick input -> $categoryLabel", language(english))
        } else {
            translate("快速输入", "Quick input", language(english))
        }
        val positiveLabel = if (categoryId >= 0L) {
            translate("添加到分类", "Add to category", language(english))
        } else {
            translate("保存到卡片", "Save to widget", language(english))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setView(editor)
            .setNegativeButton(translate("取消", "Cancel", language(english))) { _, _ -> finish() }
            .setPositiveButton(positiveLabel, null)
            .setOnCancelListener { finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val content = editor.text?.toString()?.trim().orEmpty()
                if (content.isEmpty()) {
                    editor.error = translate("请输入内容", "Enter some text", language(english))
                } else {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    if (categoryId >= 0L) persistThought(content, dialog, categoryId)
                    else saveThoughtDraft(content, dialog)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnLongClickListener {
                switchingToCategoryPicker = true
                val value = editor.text?.toString().orEmpty()
                dialog.dismiss()
                showQuickInputCategoryPicker(value)
                true
            }
            editor.requestFocus()
            editor.post {
                getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.setOnDismissListener {
            input = null
            if (!isFinishing && !isChangingConfigurations && !switchingToCategoryPicker) finish()
            switchingToCategoryPicker = false
        }
        dialog.show()
    }

    private fun saveThoughtDraft(content: String, dialog: AlertDialog) {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        thoughtDraftStore.set(currentAppWidgetId(), content)
        requestWidgetRefresh()
        Toast.makeText(
            this,
            translate("已放入桌面输入框", "Draft placed in the widget", language(english)),
            Toast.LENGTH_SHORT,
        ).show()
        dialog.dismiss()
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
            hint = translate("名称", "Name", language(english))
            filters = arrayOf(InputFilter.LengthFilter(256))
        }
        val iconInput = EditText(this).apply {
            hint = translate("图标", "Icon", language(english))
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
            .setTitle(translate("添加日期记录", "Add date record", language(english)))
            .setView(container)
            .setNegativeButton(translate("取消", "Cancel", language(english))) { _, _ -> finish() }
            .setPositiveButton(translate("添加", "Add", language(english)), null)
            .setOnCancelListener { finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text?.toString().orEmpty()
                val icon = iconInput.text?.toString().orEmpty()
                val date = dateInput.text?.toString().orEmpty()
                if (name.isBlank()) {
                    nameInput.error = translate("请输入名称", "Enter a name", language(english))
                    return@setOnClickListener
                }
                if (runCatching { LocalDate.parse(date) }.isFailure) {
                    dateInput.error = translate("请输入有效的 yyyy-MM-dd 日期", "Use a valid yyyy-MM-dd date", language(english))
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
                            translate("日期记录已添加", "Date record added", language(english)),
                            Toast.LENGTH_SHORT,
                        ).show()
                        dialog.dismiss()
                        finish()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        nameInput.error = translate("添加失败", "Could not add the record", language(english))
                    }
                }
            }
        }
        dialog.show()
    }

    private suspend fun showDailyRecordInput() {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        val templateId = intent.getStringExtra(EXTRA_DAILY_TEMPLATE_ID)
        if (settings.diaryTreeUri == null) {
            Toast.makeText(
                this,
                translate("请先在 DeskCubby 中选择日记目录", "Choose a diary folder in DeskCubby first", language(english)),
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }
        val template = try {
            structuredWorkspaceRepository.resolveTemplateForWidget(settings, templateId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Toast.makeText(
                this,
                translate("无法读取结构化记录", "Could not load structured records", language(english)),
                Toast.LENGTH_SHORT,
            ).show()
            finish()
            return
        }
        if (template == null) {
            Toast.makeText(
                this,
                translate("这条结构化记录模板已不存在", "This structured record no longer exists", language(english)),
                Toast.LENGTH_SHORT,
            ).show()
            finish()
            return
        }
        val fieldsById = try {
            structuredWorkspaceRepository.loadFields(settings).associateBy { it.id }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Toast.makeText(
                this,
                translate("无法读取结构化字段", "Could not load structured fields", language(english)),
                Toast.LENGTH_SHORT,
            ).show()
            finish()
            return
        }
        val fieldSegments = template.segments.filterIsInstance<StructuredRecordSegment.Field>()
        val bindings = fieldSegments.mapNotNull { segment ->
            fieldsById[segment.fieldId]?.let { field ->
                field to EditText(this).apply {
                    val optionHint = if (field.type == StructuredFieldType.TYPE && field.options.isNotEmpty()) {
                        " · " + field.options.joinToString(" / ")
                    } else ""
                    hint = field.name + field.unit?.let { " ($it)" }.orEmpty() + optionHint
                    filters = arrayOf(InputFilter.LengthFilter(500))
                    maxLines = 2
                    if (field.type == StructuredFieldType.NUMBER) {
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                    }
                    if (field.type == StructuredFieldType.TIME) {
                        setText(JournalDayEngine.formatTime(LocalTime.now()))
                        setSelection(text.length)
                    }
                }
            }
        }
        if (bindings.size != fieldSegments.size) {
            Toast.makeText(
                this,
                translate("模板引用了不存在的字段", "The template references a missing field", language(english)),
                Toast.LENGTH_SHORT,
            ).show()
            finish()
            return
        }

        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = (24 * density).toInt()
            setPadding(horizontal, 0, horizontal, 0)
        }
        val preview = template.segments.joinToString(separator = "") { segment ->
            when (segment) {
                is StructuredRecordSegment.Text -> segment.value
                is StructuredRecordSegment.Field -> fieldsById[segment.fieldId]?.let { "【${it.name}】" }.orEmpty()
            }
        }
        container.addView(TextView(this).apply {
            text = preview
            setPadding(0, 0, 0, (8 * density).toInt())
        })
        bindings.forEach { (_, editor) -> container.addView(editor) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                container,
                android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(template.name)
            .setView(scroll)
            .setNegativeButton(translate("取消", "Cancel", language(english))) { _, _ -> finish() }
            .setPositiveButton(translate("加入今日日记", "Add to today", language(english)), null)
            .setOnCancelListener { finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values = bindings.map { it.second.text?.toString()?.trim().orEmpty() }
                val blankIndex = values.indexOfFirst(String::isBlank)
                if (blankIndex >= 0) {
                    bindings[blankIndex].second.error = translate("请填写此字段", "Fill this field", language(english))
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                lifecycleScope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            structuredRecordsRepository.insertRecordFromTemplate(settings, template, values)
                        }
                        if (!result.success) {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            val message = if (english) {
                                result.messageEnglish ?: "Could not add the structured record"
                            } else {
                                result.message ?: "结构化记录添加失败"
                            }
                            bindings.firstOrNull()?.second?.error = message
                            Toast.makeText(this@DesktopWidgetInteractionActivity, message, Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        requestWidgetRefresh()
                        val warning = if (english) result.messageEnglish else result.message
                        Toast.makeText(
                            this@DesktopWidgetInteractionActivity,
                            warning ?: translate("已加入今日日记", "Added to today's diary", language(english)),
                            if (warning == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                        ).show()
                        dialog.dismiss()
                        finish()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        val message = translate("结构化记录添加失败", "Could not add the structured record", language(english))
                        bindings.firstOrNull()?.second?.error = message
                        Toast.makeText(this@DesktopWidgetInteractionActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun persistThought(content: String, dialog: AlertDialog, categoryId: Long = -1L) {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    thoughtRepository.create(content, categoryId.takeIf { it >= 0 })
                }
                thoughtDraftStore.clear(currentAppWidgetId())
                requestWidgetRefresh()
                Toast.makeText(
                    this@DesktopWidgetInteractionActivity,
                    translate("小巧思已保存", "Thought saved", language(english)),
                    Toast.LENGTH_SHORT,
                ).show()
                dialog.dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                input?.error = translate("保存失败", "Could not save", language(english))
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
                translate("请先在 DeskCubby 中选择日记和媒体目录", "Choose diary and media folders in DeskCubby first", language(english)),
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }
        if (!runCatching { launchCamera() }.getOrDefault(false)) launchPhotoPicker()
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

    private fun launchCamera(): Boolean {
        var file: File? = null
        return runCatching {
            val directory = File(cacheDir, "meal-camera").apply {
                check(exists() || mkdirs()) { "Could not create camera cache" }
            }
            file = File.createTempFile("meal-widget-", ".jpg", directory)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", requireNotNull(file))
            pendingCameraPath = file?.absolutePath
            externalSourceLaunched = true
            camera.launch(uri)
            true
        }.onFailure {
            file?.delete()
            pendingCameraPath = null
            externalSourceLaunched = false
            showMealFailure()
        }.getOrDefault(false)
    }

    private fun persistMealPhoto(uri: Uri, sourceFile: File? = null) {
        val category = selectedMealCategory() ?: run {
            finish()
            return
        }
        val english = settings.appLanguage == AppLanguage.ENGLISH
        progressDialog = AlertDialog.Builder(this)
            .setMessage(translate("正在添加照片…", "Adding photo…", language(english)))
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            var galleryStaging: GalleryOriginalStaging? = null
            var durableWriteSucceeded = false
            var followUpWarning: String? = null
            try {
                if (settings.saveOriginalToGallery) {
                    galleryStaging = withContext(Dispatchers.IO) {
                        GalleryOriginalStager.stage(
                            this@DesktopWidgetInteractionActivity,
                            uri,
                            "${category.key}-original",
                        )
                    }
                }

                val media = withContext(Dispatchers.IO) {
                    diaryFileRepository.appendImageToToday(
                        uri,
                        if (english) category.englishLabel else category.chineseLabel,
                        settings.copy(saveOriginalToGallery = false),
                    )
                }
                durableWriteSucceeded = true

                // The queue row itself must exist before finish(); only inference remains async.
                if (settings.calorieEstimationEnabled) {
                    try {
                        withContext(Dispatchers.IO) {
                            aiTaskQueue.enqueueTask(
                                type = AiTaskTypeEntity.CALORIE_SINGLE,
                                payload = CalorieSingleTaskPayload(
                                    uri = media.documentUri,
                                    fileName = media.fileName,
                                    settings = settings,
                                ),
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        followUpWarning = translate(
                            "照片已保存，但热量估算入队失败：${error.message.orEmpty()}",
                            "Photo saved, but calorie estimation could not be queued: ${error.message.orEmpty()}",
                            language(english),
                        )
                    }
                }

                galleryStaging?.let { staged ->
                    try {
                        GalleryOriginalStager.enqueue(this@DesktopWidgetInteractionActivity, staged)
                        galleryStaging = null
                    } catch (error: Exception) {
                        followUpWarning = translate(
                            "照片已保存，但原图相册任务入队失败：${error.message.orEmpty()}",
                            "Photo saved, but the gallery copy could not be queued: ${error.message.orEmpty()}",
                            language(english),
                        )
                    }
                }

                progressDialog?.dismiss()
                progressDialog = null
                requestWidgetRefresh()
                Toast.makeText(
                    this@DesktopWidgetInteractionActivity,
                    followUpWarning ?: translate(
                        "${category.chineseLabel}图片已加入今日日记",
                        "${category.englishLabel} photo added",
                        language(english),
                    ),
                    if (followUpWarning == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                ).show()
                finish()

                // Only best-effort work remains here. It references durable app-owned media, never
                // the temporary camera file that is deleted in finally.
                kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
                ).launch {
                    try {
                        diaryFileRepository.enrichImportedMedia(
                            sourceUri = null,
                            media = media,
                            settings = settings.copy(saveOriginalToGallery = false),
                        )
                        runCatching { diaryFileRepository.scan(settings) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Durable user data and durable queues already exist.
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                progressDialog?.dismiss()
                progressDialog = null
                Toast.makeText(
                    this@DesktopWidgetInteractionActivity,
                    if (durableWriteSucceeded) {
                        translate("照片已保存，但后台任务准备失败", "Photo saved, but a background task could not be prepared", language(english))
                    } else {
                        translate("图片添加失败", "Could not add the photo", language(english))
                    },
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            } finally {
                // Staging is app-owned. If it could not be enqueued, keep it for bounded orphan
                // cleanup rather than deleting the only durable copy before the source disappears.
                if (!durableWriteSucceeded) GalleryOriginalStager.discard(galleryStaging)
                sourceFile?.delete()
            }
        }
    }

    private fun showMealFailure() {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        Toast.makeText(
            this,
            translate("无法打开照片来源", "Could not open the photo source", language(english)),
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
                ForceCloudAvailability.SYNC_DISABLED -> translate(
                    "云端同步尚未开启。请先进入设置检查并明确开启。",
                    "Cloud sync is turned off. Open settings to review it before continuing.",
                    language(english),
                )
                ForceCloudAvailability.NO_ENABLED_SOURCE -> translate(
                    "没有已启用的云端来源。请先进入设置完成配置。",
                    "No cloud source is enabled. Open settings to configure one first.",
                    language(english),
                )
                ForceCloudAvailability.DOWNLOAD_REQUIRES_ONE_SOURCE -> translate(
                    "强制下载需要恰好一个已启用的云端来源。",
                    "Force download requires exactly one enabled cloud source.",
                    language(english),
                )
                ForceCloudAvailability.READY -> return
            }
            AlertDialog.Builder(this)
                .setTitle(translate("云端同步不可用", "Cloud sync unavailable", language(english)))
                .setMessage(message)
                .setNegativeButton(translate("取消", "Cancel", language(english))) { _, _ -> finish() }
                .setPositiveButton(translate("打开设置", "Open settings", language(english))) { _, _ -> openSettings() }
                .setOnCancelListener { finish() }
                .show()
            return
        }

        val title = if (download) {
            translate("确认强制下载？", "Force download?", language(english))
        } else {
            translate("确认强制上传？", "Force upload?", language(english))
        }
        val risk = if (download) {
            translate(
                "强制下载仅使用当前唯一的已启用云端来源。云端新增项目会下载；同路径内容不同时，将仅在本机文件仍匹配扫描快照时采用云端版本。本机独有项目不会删除，并发本机修改会保留并产生冲突副本。",
                "Force download uses the single currently enabled cloud source. New remote items are downloaded. Different items at the same path use remote data only while the local file still matches its scanned snapshot. Local-only items are not deleted, and concurrent local edits are preserved with a conflict copy.",
                language(english),
            )
        } else {
            translate(
                "本机新增项目会上传；同路径内容不同时，将以扫描到的远端版本为条件覆盖远端。远端独有项目不会删除，扫描后发生的远端修改会阻止覆盖。",
                "New local items are uploaded. Different items at the same path replace remote data only if its scanned version still matches. Remote-only items are not deleted, and later remote edits stop the overwrite.",
                language(english),
            )
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(risk)
            .setNegativeButton(translate("取消", "Cancel", language(english))) { _, _ -> finish() }
            .setPositiveButton(
                if (download) translate("强制下载", "Force download", language(english))
                else translate("强制上传", "Force upload", language(english)),
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
                    CloudSyncRunMode.FORCE_UPLOAD -> translate("强制上传已加入队列", "Forced upload queued", language(english))
                    CloudSyncRunMode.FORCE_DOWNLOAD -> translate("强制下载已加入队列", "Forced download queued", language(english))
                    CloudSyncRunMode.NORMAL -> translate("同步已加入队列", "Sync queued", language(english))
                }
            } else {
                translate("无法加入同步队列，请稍后重试", "Could not queue the sync; try again", language(english))
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
        val widgetId = currentAppWidgetId()
        DeskCubbyWidgetProvider.requestUpdate(
            this,
            widgetId.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }?.let { intArrayOf(it) },
        )
    }

    private fun currentAppWidgetId(): Int = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID,
    )

    private fun language(english: Boolean): AppLanguage =
        if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE

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
        private const val ACTION_QUICK_INPUT_CATEGORIES = "quick_input_categories"
        private const val EXTRA_QUICK_INPUT_CATEGORY = "com.deskcubby.app.extra.WIDGET_QUICK_INPUT_CATEGORY"
        private const val ACTION_MEAL_PHOTO = "meal_photo"
        private const val ACTION_FORCE_CLOUD = "force_cloud"
        private const val ACTION_DATE_RECORD_ADD = "date_record_add"
        private const val ACTION_DAILY_RECORD = "daily_record"
        private const val ACTION_VIEW_THOUGHT = "view_thought"
        private const val ACTION_OPEN_DIARY = "open_diary"
        private const val MAX_THOUGHT_CHARS = 4_000

        fun quickInputPendingIntent(context: Context, appWidgetId: Int): PendingIntent =
            pendingIntent(context, appWidgetId, ACTION_QUICK_INPUT, null)

        fun quickInputCategoriesPendingIntent(context: Context, appWidgetId: Int): PendingIntent =
            pendingIntent(context, appWidgetId, ACTION_QUICK_INPUT_CATEGORIES, null)

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
