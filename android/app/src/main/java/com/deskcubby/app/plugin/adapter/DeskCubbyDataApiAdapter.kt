package com.deskcubby.app.plugin.adapter

import com.deskcubby.app.data.local.DateRecordEntity
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.local.SavedPoemEntity
import com.deskcubby.app.data.model.DailyEventTemplate
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.DateRecordRepository
import com.deskcubby.app.data.repository.PoetryBookRepository
import com.deskcubby.app.data.repository.ThoughtRepository
import com.deskcubby.app.data.statistics.GameStatisticsRepository
import com.deskcubby.app.data.statistics.StepStatisticsRepository
import com.deskcubby.app.data.statistics.UsageStatisticsRepository
import com.deskcubby.plugin.api.core.PluginApiException
import com.deskcubby.plugin.api.core.api.DeskCubbyDataAPI
import com.deskcubby.plugin.api.core.api.DeskCubbyDataEntry
import com.deskcubby.plugin.api.core.api.DeskCubbyDataMutationRequest
import com.deskcubby.plugin.api.core.api.DeskCubbyDataPage
import com.deskcubby.plugin.api.core.api.DeskCubbyDataQuery
import com.deskcubby.plugin.api.core.api.DeskCubbyDataSource
import com.deskcubby.plugin.api.core.api.DeskCubbyMutationOperation
import com.deskcubby.plugin.api.core.api.DeskCubbyMutationPlan
import com.deskcubby.plugin.api.core.api.DeskCubbyMutationResult
import com.deskcubby.plugin.api.core.api.FileMutationOperation
import com.deskcubby.plugin.api.core.api.FileMutationRequest
import com.deskcubby.plugin.api.core.api.FileQuery
import com.deskcubby.plugin.api.core.api.FileSearchQuery
import com.deskcubby.plugin.api.core.api.VaultDocument
import com.deskcubby.plugin.api.core.api.VaultEntry
import com.deskcubby.plugin.api.core.api.VaultEntryKind
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONObject

@Singleton
class DeskCubbyDataApiAdapter @Inject constructor(
    private val diaryApi: DiaryApiAdapter,
    private val vaultApi: VaultApiAdapter,
    private val fileApi: FileApiAdapter,
    private val thoughtRepository: ThoughtRepository,
    private val dateRepository: DateRecordRepository,
    private val poetryRepository: PoetryBookRepository,
    private val usageRepository: UsageStatisticsRepository,
    private val stepRepository: StepStatisticsRepository,
    private val gameStatisticsRepository: GameStatisticsRepository,
    private val settingsRepository: SettingsRepository,
) : DeskCubbyDataAPI {
    override suspend fun sources(sourceIds: Set<String>?): List<DeskCubbyDataSource> {
        val requested = sourceIds ?: ALL_SOURCES
        return buildList {
            if (DIARY in requested) {
                val diaries = tolerateFailure(emptyList()) { diaryApi.list() }
                add(
                    source(
                        DIARY,
                        "日记",
                        "Diary",
                        "按日期保存的 Markdown 日记；授权后可检索、读取和修改。",
                        "Date-based Markdown diaries; searchable, readable, and editable when authorized.",
                        diaries.size,
                        dates = diaries.mapNotNull { it.dateIso.toDateOrNull() },
                        mutable = true,
                    ),
                )
            }
            if (THOUGHTS in requested) {
                val thoughts = thoughtRepository.active.first()
                val thoughtCategories = thoughtRepository.categories.first()
                add(
                    source(
                        THOUGHTS,
                        "小巧思",
                        "Thoughts",
                        "小巧思正文、分类和时间元数据。",
                        "Thought text, categories, and timestamps.",
                        thoughts.size,
                        categories = thoughtCategories.map { it.name },
                        mutable = true,
                    ),
                )
            }
            if (DATE_RECORDS in requested) {
                val dates = dateRepository.records.first()
                add(
                    source(
                        DATE_RECORDS,
                        "日期",
                        "Dates",
                        "重要日期记录及名称、图标。",
                        "Important date records with names and icons.",
                        dates.size,
                        dates = dates.mapNotNull { it.dateIso.toDateOrNull() },
                        mutable = true,
                    ),
                )
            }
            if (DAILY_EVENTS in requested) {
                val settings = settingsRepository.settings.first()
                add(
                    source(
                        DAILY_EVENTS,
                        "日常事件",
                        "Daily events",
                        "可复用的日常记录模板；实际填写内容仍写入日记。",
                        "Reusable daily-record templates; completed records remain in diaries.",
                        settings.dailyEventTemplates.size,
                        mutable = true,
                    ),
                )
            }
            if (NOTES in requested) {
                val noteCount = tolerateFailure(0) { fileApi.list(FileQuery(NOTES)).entries.size }
                add(
                    source(
                        NOTES,
                        "笔记",
                        "Notes",
                        "用户授权笔记库中的 Markdown 文件与文件夹。",
                        "Markdown files and folders in the user-authorized notes vault.",
                        noteCount,
                        mutable = true,
                    ),
                )
            }
            if (POEMS in requested) {
                val poems = poetryRepository.poems.first()
                val poemCategories = poetryRepository.categories.first()
                add(
                    source(
                        POEMS,
                        "诗词本",
                        "Poetry book",
                        "已收藏诗词、来源和分类。",
                        "Saved poems, attribution, and categories.",
                        poems.size,
                        categories = poemCategories.map { it.name },
                        mutable = true,
                    ),
                )
            }
            if (USAGE in requested) {
                val usage = usageRepository.history.value
                add(
                    source(
                        USAGE,
                        "手机使用时间",
                        "Phone usage",
                        "按日期和应用聚合的本机使用时间，只读。",
                        "Read-only daily and per-app phone usage aggregates.",
                        usage.days.size,
                        dates = usage.days.map { it.date },
                    ),
                )
            }
            if (STATISTICS in requested) {
                add(
                    source(
                        STATISTICS,
                        "统计数据",
                        "Statistics",
                        "日记、健康、使用时间和小游戏的轻量统计汇总，只读。",
                        "Read-only summaries for diaries, health, usage, and games.",
                        statisticsEntries().size,
                    ),
                )
            }
        }
    }

    override suspend fun list(query: DeskCubbyDataQuery): DeskCubbyDataPage {
        val offset = query.offset.coerceAtLeast(0)
        val limit = query.limit.coerceIn(1, MAX_PAGE_SIZE)
        val startDate = query.startDateIso
        val endDate = query.endDateIso
        val category = query.category
        val text = query.text
        val entries = entriesFor(query)
            .asSequence()
            .filter { entry -> startDate == null || entry.dateIso.orEmpty() >= startDate }
            .filter { entry -> endDate == null || entry.dateIso.orEmpty() <= endDate }
            .filter { entry -> category == null || entry.category.equals(category, true) }
            .filter { entry ->
                text.isNullOrBlank() || listOf(entry.title, entry.subtitle, entry.content)
                    .any { it.contains(text, ignoreCase = true) }
            }
            .toList()
        return DeskCubbyDataPage(
            entries = entries.drop(offset).take(limit),
            offset = offset,
            limit = limit,
            hasMore = entries.size > offset + limit,
        )
    }

    override suspend fun read(sourceId: String, entryId: String): DeskCubbyDataEntry = when (sourceId) {
        DIARY -> fileApi.read(DIARY, entryId).let { document ->
            DeskCubbyDataEntry(
                DIARY,
                document.entry.fileId,
                document.entry.name.substringBeforeLast('.'),
                content = document.content,
                dateIso = diaryApi.list().firstOrNull { it.documentId == entryId }?.dateIso,
                updatedAtMillis = document.entry.lastModifiedMillis,
                version = document.entry.version,
            )
        }
        NOTES -> fileApi.read(NOTES, entryId).let { document ->
            DeskCubbyDataEntry(
                NOTES,
                document.entry.fileId,
                document.entry.name,
                subtitle = document.entry.parentRelativePath,
                content = document.content,
                updatedAtMillis = document.entry.lastModifiedMillis,
                version = document.entry.version,
                metadata = mapOf(
                    "parentId" to document.entry.parentId.orEmpty(),
                    "parentRelativePath" to document.entry.parentRelativePath,
                ),
            )
        }
        THOUGHTS -> thoughtRepository.get(entryId.requireLongId())
            ?.takeIf { it.deletedAt == null }
            ?.toEntry(thoughtRepository.categories.first().associate { it.id to it.name }, true)
            ?: notFound(sourceId, entryId)
        DATE_RECORDS -> dateRepository.get(entryId.requireLongId())?.toEntry()
            ?: notFound(sourceId, entryId)
        DAILY_EVENTS -> {
            val templates = settingsRepository.settings.first().dailyEventTemplates
            val index = templates.indexOfFirst { it.id == entryId }
            if (index < 0) notFound(sourceId, entryId)
            templates[index].toEntry(index)
        }
        POEMS -> poetryRepository.get(entryId.requireLongId())
            ?.toEntry(poetryRepository.categories.first().associate { it.id to it.name }, true)
            ?: notFound(sourceId, entryId)
        USAGE -> usageEntry(entryId) ?: notFound(sourceId, entryId)
        STATISTICS -> statisticsEntries().firstOrNull { it.entryId == entryId }
            ?: notFound(sourceId, entryId)
        else -> throw invalidSource(sourceId)
    }

    override suspend fun prepareMutation(
        request: DeskCubbyDataMutationRequest,
    ): DeskCubbyMutationPlan {
        require(request.sourceId in MUTABLE_SOURCES) { "This source is read-only" }
        return if (request.sourceId in FILE_SOURCES &&
            request.operation != DeskCubbyMutationOperation.DELETE
        ) {
            prepareFileMutation(request)
        } else {
            prepareDataMutation(request)
        }
    }

    override suspend fun commitMutation(planToken: String): DeskCubbyMutationResult {
        val token = decodeToken(planToken, PLAN_SCHEMA)
        return if (token.optString("delegate") == "file") {
            val result = fileApi.commitMutation(token.getString("delegateToken"))
            DeskCubbyMutationResult(
                operation = DeskCubbyMutationOperation.valueOf(token.getString("operation")),
                target = result.target,
                summary = result.summary,
                before = result.before?.let { file -> fileApi.readEntry(file) },
                after = result.after?.let { file -> fileApi.readEntry(file) },
                undoToken = JSONObject()
                    .put("schema", UNDO_SCHEMA)
                    .put("delegate", "file")
                    .put("delegateToken", result.undoToken)
                    .put("operation", token.getString("operation"))
                    .toString(),
            )
        } else {
            commitDataMutation(token)
        }
    }

    override suspend fun undoMutation(undoToken: String): DeskCubbyMutationResult {
        val token = decodeToken(undoToken, UNDO_SCHEMA)
        if (token.optString("delegate") == "file") {
            val result = fileApi.undoMutation(token.getString("delegateToken"))
            return DeskCubbyMutationResult(
                DeskCubbyMutationOperation.valueOf(token.getString("operation")),
                result.target,
                result.summary,
                before = result.before?.let { fileApi.readEntry(it) },
                after = result.after?.let { fileApi.readEntry(it) },
            )
        }
        return undoDataMutation(token)
    }

    private suspend fun entriesFor(query: DeskCubbyDataQuery): List<DeskCubbyDataEntry> = when (query.sourceId) {
        DIARY -> diaryApi.list()
            .let { items -> if (query.text.isNullOrBlank()) items else items.take(MAX_LIST_CANDIDATES) }
            .map { item ->
                DeskCubbyDataEntry(
                    DIARY,
                    item.documentId,
                    item.title,
                    subtitle = item.name,
                    content = if (query.text.isNullOrBlank()) {
                        ""
                    } else {
                        tolerateFailure("") { diaryApi.load(item.documentId).markdown }
                    },
                    dateIso = item.dateIso,
                    updatedAtMillis = item.lastModifiedMillis,
                    metadata = mapOf("wordCount" to item.wordCount.toString()),
                )
            }
        NOTES -> {
            val text = query.text
            val page = if (text.isNullOrBlank()) {
                fileApi.list(FileQuery(NOTES, query.category, 0, MAX_LIST_CANDIDATES))
            } else {
                fileApi.search(FileSearchQuery(NOTES, text, 0, MAX_LIST_CANDIDATES))
            }
            page.entries.filterNot { it.isDirectory }.map { entry ->
                DeskCubbyDataEntry(
                    NOTES,
                    entry.fileId,
                    entry.name,
                    subtitle = entry.parentRelativePath,
                    updatedAtMillis = entry.lastModifiedMillis,
                    metadata = mapOf(
                        "parentId" to entry.parentId.orEmpty(),
                        "parentRelativePath" to entry.parentRelativePath,
                    ),
                )
            }
        }
        THOUGHTS -> {
            val categories = thoughtRepository.categories.first().associate { it.id to it.name }
            thoughtRepository.active.first()
                .let { items -> if (query.text.isNullOrBlank()) items else items.take(MAX_LIST_CANDIDATES) }
                .map { it.toEntry(categories, includeContent = !query.text.isNullOrBlank()) }
        }
        DATE_RECORDS -> dateRepository.records.first().map(DateRecordEntity::toEntry)
        DAILY_EVENTS -> settingsRepository.settings.first().dailyEventTemplates.mapIndexed { index, item ->
            item.toEntry(index)
        }
        POEMS -> {
            val categories = poetryRepository.categories.first().associate { it.id to it.name }
            poetryRepository.poems.first()
                .let { items -> if (query.text.isNullOrBlank()) items else items.take(MAX_LIST_CANDIDATES) }
                .map { it.toEntry(categories, includeContent = !query.text.isNullOrBlank()) }
        }
        USAGE -> usageRepository.history.value.days.sortedByDescending { it.date }.map { day ->
            DeskCubbyDataEntry(
                USAGE,
                day.date.toString(),
                day.date.toString(),
                subtitle = "${day.totalForegroundMillis} ms",
                dateIso = day.date.toString(),
                updatedAtMillis = day.collectedAtEpochMillis,
                metadata = mapOf(
                    "totalForegroundMillis" to day.totalForegroundMillis.toString(),
                    "appCount" to day.apps.size.toString(),
                    "state" to day.state.name,
                ),
            )
        }
        STATISTICS -> statisticsEntries()
        else -> throw invalidSource(query.sourceId)
    }

    private suspend fun prepareFileMutation(
        request: DeskCubbyDataMutationRequest,
    ): DeskCubbyMutationPlan {
        val operation = when (request.operation) {
            DeskCubbyMutationOperation.CREATE -> FileMutationOperation.CREATE
            DeskCubbyMutationOperation.UPDATE -> FileMutationOperation.UPDATE
            DeskCubbyMutationOperation.DELETE -> error("Delete is prepared separately")
        }
        val filePlan = fileApi.prepareMutation(
            FileMutationRequest(
                operation = operation,
                rootId = request.sourceId,
                fileId = request.entryId,
                folderId = request.category,
                name = request.title,
                content = request.content.orEmpty(),
            ),
        )
        val token = JSONObject()
            .put("schema", PLAN_SCHEMA)
            .put("delegate", "file")
            .put("delegateToken", filePlan.planToken)
            .put("operation", request.operation.name)
            .toString()
        return DeskCubbyMutationPlan(
            token,
            request.operation,
            filePlan.target,
            filePlan.summary,
            before = filePlan.before?.let { fileApi.readEntry(it) },
            after = filePlan.after?.let { fileApi.readEntry(it) },
        )
    }

    private suspend fun prepareDataMutation(
        request: DeskCubbyDataMutationRequest,
    ): DeskCubbyMutationPlan {
        val before = request.entryId?.let { read(request.sourceId, it) }
        when (request.operation) {
            DeskCubbyMutationOperation.CREATE -> require(request.entryId == null) {
                "entryId is not allowed for create"
            }
            DeskCubbyMutationOperation.UPDATE,
            DeskCubbyMutationOperation.DELETE,
            -> requireNotNull(before) { "Entry no longer exists" }
        }
        val after = when (request.operation) {
            DeskCubbyMutationOperation.CREATE -> DeskCubbyDataEntry(
                request.sourceId,
                "",
                request.title.orEmpty(),
                content = request.content.orEmpty(),
                dateIso = request.dateIso,
                category = request.category,
            )
            DeskCubbyMutationOperation.UPDATE -> before!!.copy(
                title = request.title ?: before.title,
                content = request.content ?: before.content,
                dateIso = request.dateIso ?: before.dateIso,
                category = request.category ?: before.category,
            )
            DeskCubbyMutationOperation.DELETE -> null
        }
        val token = JSONObject()
            .put("schema", PLAN_SCHEMA)
            .put("operation", request.operation.name)
            .put("sourceId", request.sourceId)
            .put("entryId", request.entryId ?: JSONObject.NULL)
            .put("title", request.title ?: JSONObject.NULL)
            .put("content", request.content ?: JSONObject.NULL)
            .put("dateIso", request.dateIso ?: JSONObject.NULL)
            .put("category", request.category ?: JSONObject.NULL)
            .put("expectedVersion", before?.versionToken().orEmpty())
            .put("before", before?.toJson() ?: JSONObject.NULL)
            .toString()
        return DeskCubbyMutationPlan(
            token,
            request.operation,
            "${request.sourceId}/${request.entryId ?: request.title.orEmpty()}",
            "${request.operation.name.lowercase()} ${request.sourceId} entry",
            before,
            after,
        )
    }

    private suspend fun commitDataMutation(token: JSONObject): DeskCubbyMutationResult {
        val operation = DeskCubbyMutationOperation.valueOf(token.getString("operation"))
        val sourceId = token.getString("sourceId")
        val before = token.optJSONObject("before")?.toEntry()
        if (before != null) {
            val current = read(sourceId, before.entryId)
            require(current.versionToken() == token.getString("expectedVersion")) {
                "Entry changed after the Agent prepared this operation"
            }
        }
        val after = when (operation) {
            DeskCubbyMutationOperation.CREATE -> createStructured(token)
            DeskCubbyMutationOperation.UPDATE -> updateStructured(token, requireNotNull(before))
            DeskCubbyMutationOperation.DELETE -> {
                deleteStructured(sourceId, requireNotNull(before), token)
                null
            }
        }
        val undo = JSONObject()
            .put("schema", UNDO_SCHEMA)
            .put("operation", operation.name)
            .put("sourceId", sourceId)
            .put("before", before?.toJson() ?: JSONObject.NULL)
            .put("after", after?.toJson() ?: JSONObject.NULL)
            .put("trashId", token.optString("trashId"))
            .toString()
        return DeskCubbyMutationResult(
            operation,
            "$sourceId/${after?.entryId ?: before?.entryId.orEmpty()}",
            "${operation.name.lowercase()} completed",
            before,
            after,
            undo,
        )
    }

    private suspend fun createStructured(token: JSONObject): DeskCubbyDataEntry {
        val sourceId = token.getString("sourceId")
        val title = token.nullableString("title")
        val content = token.nullableString("content").orEmpty()
        val date = token.nullableString("dateIso")
        val category = token.nullableString("category")
        val id = when (sourceId) {
            THOUGHTS -> thoughtRepository.create(content, thoughtCategoryId(category))
            DATE_RECORDS -> dateRepository.create(
                title.orEmpty(),
                "📅",
                date ?: LocalDate.now().toString(),
            )
            DAILY_EVENTS -> {
                val generated = UUID.randomUUID().toString()
                settingsRepository.addDailyEventTemplate(DailyEventTemplate(generated, content))
                return read(DAILY_EVENTS, generated)
            }
            POEMS -> poetryRepository.create(content, title.orEmpty(), poemCategoryId(category))
            else -> throw invalidSource(sourceId)
        }
        return read(sourceId, id.toString())
    }

    private suspend fun updateStructured(
        token: JSONObject,
        before: DeskCubbyDataEntry,
    ): DeskCubbyDataEntry {
        val title = token.nullableString("title") ?: before.title
        val content = token.nullableString("content") ?: before.content
        val date = token.nullableString("dateIso") ?: before.dateIso
        val category = token.nullableString("category") ?: before.category
        when (before.sourceId) {
            THOUGHTS -> {
                thoughtRepository.update(before.entryId.requireLongId(), content)
                thoughtRepository.setCategory(before.entryId.requireLongId(), thoughtCategoryId(category))
            }
            DATE_RECORDS -> dateRepository.update(
                before.entryId.requireLongId(),
                title,
                before.metadata["icon"].orEmpty().ifBlank { "📅" },
                requireNotNull(date),
            )
            DAILY_EVENTS -> settingsRepository.updateDailyEventTemplate(
                DailyEventTemplate(
                    before.entryId,
                    content,
                    before.metadata["firstUnit"].orEmpty(),
                    before.metadata["secondUnit"].orEmpty(),
                ),
            )
            POEMS -> poetryRepository.update(
                before.entryId.requireLongId(),
                content,
                title,
                poemCategoryId(category),
            )
            else -> throw invalidSource(before.sourceId)
        }
        return read(before.sourceId, before.entryId)
    }

    private suspend fun deleteStructured(
        sourceId: String,
        before: DeskCubbyDataEntry,
        token: JSONObject,
    ) {
        when (sourceId) {
            DIARY -> token.put("trashId", diaryApi.moveToTrashDocument(before.entryId).documentId)
            NOTES -> vaultApi.delete(before.toVaultEntry())
            THOUGHTS -> thoughtRepository.delete(before.entryId.requireLongId())
            DATE_RECORDS -> dateRepository.delete(before.entryId.requireLongId())
            DAILY_EVENTS -> settingsRepository.removeDailyEventTemplate(before.entryId)
            POEMS -> poetryRepository.delete(before.entryId.requireLongId())
            else -> throw invalidSource(sourceId)
        }
    }

    private suspend fun undoDataMutation(token: JSONObject): DeskCubbyMutationResult {
        val operation = DeskCubbyMutationOperation.valueOf(token.getString("operation"))
        val sourceId = token.getString("sourceId")
        val before = token.optJSONObject("before")?.toEntry()
        val after = token.optJSONObject("after")?.toEntry()
        when (operation) {
            DeskCubbyMutationOperation.CREATE -> {
                val current = read(sourceId, requireNotNull(after).entryId)
                require(current.versionToken() == after.versionToken()) {
                    "Entry changed after Agent creation; Undo stopped"
                }
                deleteStructured(sourceId, current, JSONObject())
            }
            DeskCubbyMutationOperation.UPDATE -> {
                val current = read(sourceId, requireNotNull(after).entryId)
                require(current.versionToken() == after.versionToken()) {
                    "Entry changed after Agent edit; Undo stopped"
                }
                restoreExact(requireNotNull(before))
            }
            DeskCubbyMutationOperation.DELETE -> {
                when (sourceId) {
                    DIARY -> diaryApi.restoreFromTrash(token.getString("trashId"))
                    NOTES -> restoreDeletedNote(requireNotNull(before))
                    else -> restoreExact(requireNotNull(before))
                }
            }
        }
        val restored = when (operation) {
            DeskCubbyMutationOperation.CREATE -> null
            DeskCubbyMutationOperation.UPDATE,
            DeskCubbyMutationOperation.DELETE,
            -> tolerateFailure(null) { read(sourceId, requireNotNull(before).entryId) }
        }
        return DeskCubbyMutationResult(
            operation,
            "$sourceId/${before?.entryId ?: after?.entryId.orEmpty()}",
            "Undo completed",
            before = after,
            after = restored,
        )
    }

    private suspend fun restoreExact(entry: DeskCubbyDataEntry) {
        when (entry.sourceId) {
            THOUGHTS -> thoughtRepository.restoreExact(entry.toThoughtEntity())
            DATE_RECORDS -> dateRepository.restoreExact(entry.toDateEntity())
            DAILY_EVENTS -> {
                val current = settingsRepository.settings.first().dailyEventTemplates
                val restored = DailyEventTemplate(
                    entry.entryId,
                    entry.content,
                    entry.metadata["firstUnit"].orEmpty(),
                    entry.metadata["secondUnit"].orEmpty(),
                )
                val withoutEntry = current.filterNot { it.id == entry.entryId }.toMutableList()
                val originalIndex = entry.metadata["sortOrder"]?.toIntOrNull()
                    ?.coerceIn(0, withoutEntry.size) ?: withoutEntry.size
                withoutEntry.add(originalIndex, restored)
                settingsRepository.setDailyEventTemplates(withoutEntry)
            }
            POEMS -> poetryRepository.restoreExact(entry.toPoemEntity())
            else -> throw invalidSource(entry.sourceId)
        }
    }

    private suspend fun restoreDeletedNote(entry: DeskCubbyDataEntry) {
        val parentId = entry.metadata["parentId"].orEmpty()
        val parentPath = entry.metadata["parentRelativePath"].orEmpty()
        val parent = parentId.takeIf(String::isNotBlank)?.let {
            com.deskcubby.plugin.api.core.api.VaultFolder(it, parentPath.substringAfterLast('/'), parentPath)
        }
        val created = vaultApi.createMarkdown(parent, entry.title)
        vaultApi.save(created, entry.content)
    }

    private suspend fun usageEntry(entryId: String): DeskCubbyDataEntry? {
        val day = usageRepository.history.value.days.firstOrNull { it.date.toString() == entryId }
            ?: return null
        val content = buildString {
            append("date=").append(day.date).append('\n')
            append("totalForegroundMillis=").append(day.totalForegroundMillis).append('\n')
            day.apps.sortedByDescending { it.foregroundMillis }.take(100).forEach { app ->
                append(app.packageName).append('=').append(app.foregroundMillis).append(" ms\n")
            }
        }
        return DeskCubbyDataEntry(
            USAGE,
            entryId,
            entryId,
            content = content,
            dateIso = entryId,
            updatedAtMillis = day.collectedAtEpochMillis,
            metadata = mapOf("state" to day.state.name),
        )
    }

    private suspend fun statisticsEntries(): List<DeskCubbyDataEntry> {
        val diaries = tolerateFailure(emptyList()) { diaryApi.list() }
        val usage = usageRepository.history.value
        val steps = stepRepository.history.value
        val games = gameStatisticsRepository.statistics.value
        return listOf(
            DeskCubbyDataEntry(
                STATISTICS,
                "diary_overview",
                "Diary overview",
                content = "entries=${diaries.size}\nwords=${diaries.sumOf { it.wordCount }}",
            ),
            DeskCubbyDataEntry(
                STATISTICS,
                "usage_overview",
                "Usage overview",
                content = "days=${usage.days.size}\ntotalForegroundMillis=${usage.days.sumOf { it.totalForegroundMillis }}",
            ),
            DeskCubbyDataEntry(
                STATISTICS,
                "health_overview",
                "Health overview",
                content = "days=${steps.days.size}\nsteps=${steps.days.mapNotNull { it.steps }.sum()}",
            ),
            DeskCubbyDataEntry(
                STATISTICS,
                "game_overview",
                "Game overview",
                content = games.byGameId.entries.joinToString("\n") { (game, values) ->
                    "$game=${values.asMap().entries.joinToString(",") { "${it.key}:${it.value}" }}"
                },
            ),
        )
    }

    private suspend fun thoughtCategoryId(name: String?): Long? = name?.let { value ->
        thoughtRepository.categories.first().firstOrNull { it.name.equals(value, true) }?.id
            ?: throw IllegalArgumentException("Unknown thought category: $value")
    }

    private suspend fun poemCategoryId(name: String?): Long? = name?.let { value ->
        poetryRepository.categories.first().firstOrNull { it.name.equals(value, true) }?.id
            ?: throw IllegalArgumentException("Unknown poetry category: $value")
    }

    private fun source(
        id: String,
        zh: String,
        en: String,
        descriptionZh: String,
        descriptionEn: String,
        count: Int,
        categories: List<String> = emptyList(),
        dates: List<LocalDate> = emptyList(),
        mutable: Boolean = false,
    ) = DeskCubbyDataSource(
        id,
        zh,
        en,
        descriptionZh,
        descriptionEn,
        count,
        categories.take(MAX_SOURCE_CATEGORIES),
        dates.minOrNull()?.toString(),
        dates.maxOrNull()?.toString(),
        mutable,
    )

    private fun decodeToken(raw: String, schema: String): JSONObject {
        require(raw.length <= MAX_TOKEN_CHARS) { "Mutation token is too large" }
        return JSONObject(raw).also { require(it.getString("schema") == schema) }
    }

    private fun invalidSource(sourceId: String) = PluginApiException(
        "INVALID_DATA_SOURCE",
        "Unknown DeskCubby data source: $sourceId",
    )

    private fun notFound(sourceId: String, entryId: String): Nothing = throw PluginApiException(
        "ENTRY_NOT_FOUND",
        "The requested $sourceId entry '$entryId' was not found.",
    )

    private companion object {
        const val DIARY = "diary"
        const val THOUGHTS = "thoughts"
        const val DATE_RECORDS = "date_records"
        const val DAILY_EVENTS = "daily_events"
        const val NOTES = "notes"
        const val POEMS = "poems"
        const val USAGE = "usage"
        const val STATISTICS = "statistics"
        const val PLAN_SCHEMA = "deskcubby.agent-data-plan.v1"
        const val UNDO_SCHEMA = "deskcubby.agent-data-undo.v1"
        const val MAX_PAGE_SIZE = 100
        const val MAX_LIST_CANDIDATES = 1_000
        const val MAX_SOURCE_CATEGORIES = 100
        const val MAX_TOKEN_CHARS = 512 * 1024
        val FILE_SOURCES = setOf(DIARY, NOTES)
        val MUTABLE_SOURCES = setOf(DIARY, THOUGHTS, DATE_RECORDS, DAILY_EVENTS, NOTES, POEMS)
        val ALL_SOURCES = setOf(
            DIARY,
            THOUGHTS,
            DATE_RECORDS,
            DAILY_EVENTS,
            NOTES,
            POEMS,
            USAGE,
            STATISTICS,
        )
    }
}

private fun FlashThoughtEntity.toEntry(
    categories: Map<Long, String>,
    includeContent: Boolean,
) = DeskCubbyDataEntry(
    sourceId = "thoughts",
    entryId = id.toString(),
    title = content.replace(Regex("\\s+"), " ").take(80),
    content = if (includeContent) content else "",
    category = categoryId?.let(categories::get),
    createdAtMillis = createdAt,
    updatedAtMillis = updatedAt,
    metadata = mapOf(
        "revision" to updatedAt.toString(),
        "pinned" to pinned.toString(),
        "highlighted" to highlighted.toString(),
        "sortOrder" to sortOrder.toString(),
        "categoryId" to categoryId?.toString().orEmpty(),
        "deletedAt" to deletedAt?.toString().orEmpty(),
    ),
)

private fun DateRecordEntity.toEntry() = DeskCubbyDataEntry(
    sourceId = "date_records",
    entryId = id.toString(),
    title = name,
    subtitle = icon,
    dateIso = dateIso,
    createdAtMillis = createdAt,
    updatedAtMillis = updatedAt,
    metadata = mapOf("revision" to updatedAt.toString(), "icon" to icon),
)

private fun DailyEventTemplate.toEntry(sortOrder: Int) = DeskCubbyDataEntry(
    sourceId = "daily_events",
    entryId = id,
    title = text.replace(Regex("\\s+"), " ").take(80),
    content = text,
    metadata = mapOf(
        "revision" to listOf(text, firstUnit, secondUnit, sortOrder).hashCode().toString(),
        "firstUnit" to firstUnit,
        "secondUnit" to secondUnit,
        "sortOrder" to sortOrder.toString(),
    ),
)

private fun SavedPoemEntity.toEntry(
    categories: Map<Long, String>,
    includeContent: Boolean,
) = DeskCubbyDataEntry(
    sourceId = "poems",
    entryId = id.toString(),
    title = source.ifBlank { content.replace(Regex("\\s+"), " ").take(80) },
    subtitle = source,
    content = if (includeContent) content else "",
    category = categoryId?.let(categories::get),
    createdAtMillis = createdAt,
    updatedAtMillis = updatedAt,
    metadata = mapOf(
        "revision" to updatedAt.toString(),
        "source" to source,
        "sortOrder" to sortOrder.toString(),
        "categoryId" to categoryId?.toString().orEmpty(),
    ),
)

private fun String.requireLongId(): Long = toLongOrNull()?.takeIf { it > 0 }
    ?: throw IllegalArgumentException("Entry id must be a positive integer")

private fun String.toDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

private suspend inline fun <T> tolerateFailure(default: T, block: () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    default
}

private fun DeskCubbyDataEntry.versionToken(): String =
    version?.sha256 ?: metadata["revision"] ?: updatedAtMillis?.toString().orEmpty()

private fun DeskCubbyDataEntry.toJson(): JSONObject = JSONObject()
    .put("sourceId", sourceId)
    .put("entryId", entryId)
    .put("title", title)
    .put("subtitle", subtitle)
    .put("content", content)
    .put("dateIso", dateIso ?: JSONObject.NULL)
    .put("category", category ?: JSONObject.NULL)
    .put("createdAtMillis", createdAtMillis ?: JSONObject.NULL)
    .put("updatedAtMillis", updatedAtMillis ?: JSONObject.NULL)
    .put("versionSha256", version?.sha256 ?: JSONObject.NULL)
    .put("versionSize", version?.size ?: JSONObject.NULL)
    .put("versionModified", version?.lastModifiedMillis ?: JSONObject.NULL)
    .put("metadata", JSONObject(metadata))

private fun JSONObject.toEntry(): DeskCubbyDataEntry {
    val sha = nullableString("versionSha256")
    return DeskCubbyDataEntry(
        sourceId = getString("sourceId"),
        entryId = getString("entryId"),
        title = getString("title"),
        subtitle = getString("subtitle"),
        content = getString("content"),
        dateIso = nullableString("dateIso"),
        category = nullableString("category"),
        createdAtMillis = if (isNull("createdAtMillis")) null else getLong("createdAtMillis"),
        updatedAtMillis = if (isNull("updatedAtMillis")) null else getLong("updatedAtMillis"),
        version = sha?.let {
            com.deskcubby.plugin.api.core.api.ContentVersion(
                it,
                getLong("versionSize"),
                getLong("versionModified"),
            )
        },
        metadata = optJSONObject("metadata")?.keys()?.asSequence()?.associateWith { key ->
            optJSONObject("metadata")!!.optString(key)
        }.orEmpty(),
    )
}

private fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name)

private fun DeskCubbyDataEntry.toThoughtEntity() = FlashThoughtEntity(
    id = entryId.requireLongId(),
    content = content,
    createdAt = requireNotNull(createdAtMillis),
    updatedAt = requireNotNull(updatedAtMillis),
    pinned = metadata["pinned"].toBoolean(),
    deletedAt = metadata["deletedAt"]?.toLongOrNull(),
    sortOrder = metadata["sortOrder"]?.toLongOrNull() ?: 0,
    categoryId = metadata["categoryId"]?.toLongOrNull(),
    highlighted = metadata["highlighted"].toBoolean(),
)

private fun DeskCubbyDataEntry.toDateEntity() = DateRecordEntity(
    id = entryId.requireLongId(),
    name = title,
    icon = metadata["icon"].orEmpty().ifBlank { "📅" },
    dateIso = requireNotNull(dateIso),
    createdAt = requireNotNull(createdAtMillis),
    updatedAt = requireNotNull(updatedAtMillis),
)

private fun DeskCubbyDataEntry.toPoemEntity() = SavedPoemEntity(
    id = entryId.requireLongId(),
    content = content,
    source = metadata["source"].orEmpty(),
    createdAt = requireNotNull(createdAtMillis),
    updatedAt = requireNotNull(updatedAtMillis),
    sortOrder = metadata["sortOrder"]?.toLongOrNull() ?: 0,
    categoryId = metadata["categoryId"]?.toLongOrNull(),
)

private fun DeskCubbyDataEntry.toVaultEntry() = VaultEntry(
    entryId = entryId,
    parentId = metadata["parentId"].orEmpty(),
    parentRelativePath = metadata["parentRelativePath"].orEmpty(),
    name = title,
    kind = VaultEntryKind.MARKDOWN,
    size = version?.size ?: content.toByteArray().size.toLong(),
    lastModifiedMillis = updatedAtMillis ?: 0,
)

private fun FileApiAdapter.readEntry(file: com.deskcubby.plugin.api.core.api.FileDocument) =
    DeskCubbyDataEntry(
        sourceId = file.entry.rootId,
        entryId = file.entry.fileId,
        title = file.entry.name,
        subtitle = file.entry.parentRelativePath,
        content = file.content,
        updatedAtMillis = file.entry.lastModifiedMillis,
        version = file.entry.version,
        metadata = mapOf(
            "parentId" to file.entry.parentId.orEmpty(),
            "parentRelativePath" to file.entry.parentRelativePath,
        ),
    )
