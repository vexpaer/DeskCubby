package com.deskcubby.app.data.sync

import androidx.room.withTransaction
import com.deskcubby.app.data.local.AppDatabase
import com.deskcubby.app.data.local.BrowserRecordEntity
import com.deskcubby.app.data.local.DateRecordEntity
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.local.GameStateEntity
import com.deskcubby.app.data.local.GameStatisticEntity
import com.deskcubby.app.data.local.PoetryCategoryEntity
import com.deskcubby.app.data.local.SavedPoemEntity
import com.deskcubby.app.data.local.ThoughtCategoryEntity
import com.deskcubby.app.data.model.CloudSyncContent
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class RoomRecordSyncAdapters @Inject constructor(
    private val database: AppDatabase,
) {
    val thoughts: RecordSyncAdapter = ThoughtRecordSyncAdapter(database)
    val thoughtCategories: RecordSyncAdapter = ThoughtCategoryRecordSyncAdapter(database)
    val dateRecords: RecordSyncAdapter = DateRecordRecordSyncAdapter(database)
    val poems: RecordSyncAdapter = PoemRecordSyncAdapter(database)
    val poetryCategories: RecordSyncAdapter = PoetryCategoryRecordSyncAdapter(database)
    val favorites: RecordSyncAdapter = FavoriteRecordSyncAdapter(database)
    val gameStates: RecordSyncAdapter = GameStateRecordSyncAdapter(database)
    val gameStatistics: RecordSyncAdapter = GameStatisticRecordSyncAdapter(database)

    fun all(): Map<CloudSyncContent, RecordSyncAdapter> = linkedMapOf(
        CloudSyncContent.THOUGHTS to thoughts,
        CloudSyncContent.THOUGHT_CATEGORIES to thoughtCategories,
        CloudSyncContent.DATE_RECORDS to dateRecords,
        CloudSyncContent.POEMS to poems,
        CloudSyncContent.POETRY_CATEGORIES to poetryCategories,
        CloudSyncContent.FAVORITES to favorites,
        CloudSyncContent.GAME_STATES to gameStates,
        CloudSyncContent.GAME_STATISTICS to gameStatistics,
    )
}

private class ThoughtRecordSyncAdapter(
    private val database: AppDatabase,
) : RecordSyncAdapter {
    override val contentType = CloudSyncContent.THOUGHTS
    override val conflictPolicy = RecordConflictPolicy.CONFLICT_COPY

    override suspend fun listLocalRecords(): List<LocalRecordRef> {
        val dao = database.flashThoughtDao()
        return dao.getAllForBackup()
            .filter { it.deletedAt == null }
            .map { LocalRecordRef(it.id.toString(), it.updatedAt.coerceAtLeast(0L), it.updatedAt.coerceAtLeast(0L)) }
    }

    override suspend fun readLocalRecord(localKey: String): SyncRecord {
        val dao = database.flashThoughtDao()
        val item = dao.getById(localKey.toLong())
            ?: throw CloudSyncConflictException("本地小巧思在同步读取期间被删除。")
        val categoryName = item.categoryId?.let { id ->
            database.thoughtCategoryDao().getAllForBackup().firstOrNull { it.id == id }?.name
        }
        val payload = recordPayload(
            JSONObject()
                .put("content", item.content)
                .put("createdAt", item.createdAt)
                .put("updatedAt", item.updatedAt)
                .put("pinned", item.pinned)
                .put("sortOrder", item.sortOrder)
                .putRecord("categoryName", categoryName)
                .put("highlighted", item.highlighted),
        )
        return SyncRecord(
            id = "local",
            revision = item.updatedAt.coerceAtLeast(0L),
            updatedAt = item.updatedAt.coerceAtLeast(0L),
            payload = payload,
        )
    }

    override suspend fun applyRemoteRecord(
        record: SyncRecord,
        preserveLocalConflict: SyncRecord?,
        preserveLocalKey: String?,
    ): RecordApplyResult? = database.withTransaction {
        val dao = database.flashThoughtDao()
        val categoryDao = database.thoughtCategoryDao()
        val json = recordJson(record.payload)
        val categoryName = json.optionalRecordString("categoryName")
        val categoryId = categoryName?.let { name ->
            categoryDao.getAllForBackup().firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
        }
        val canonicalId = preserveLocalKey?.toLongOrNull()?.let { dao.getById(it)?.id }
            ?: dao.insert(
                FlashThoughtEntity(
                    content = json.requiredRecordString("content"),
                    createdAt = json.requiredRecordLong("createdAt"),
                    updatedAt = json.requiredRecordLong("updatedAt"),
                    pinned = json.requiredRecordBoolean("pinned"),
                    deletedAt = null,
                    sortOrder = json.requiredRecordLong("sortOrder"),
                    categoryId = categoryId,
                    highlighted = json.requiredRecordBoolean("highlighted"),
                ),
            )
        if (canonicalId != 0L) {
            dao.upsertForUndo(
                FlashThoughtEntity(
                    id = canonicalId,
                    content = json.requiredRecordString("content"),
                    createdAt = json.requiredRecordLong("createdAt"),
                    updatedAt = json.requiredRecordLong("updatedAt"),
                    pinned = json.requiredRecordBoolean("pinned"),
                    deletedAt = null,
                    sortOrder = json.requiredRecordLong("sortOrder"),
                    categoryId = categoryId,
                    highlighted = json.requiredRecordBoolean("highlighted"),
                ),
            )
        }

        val conflictCopyKey = preserveLocalConflict?.let { local ->
            val localJson = recordJson(local.payload)
            dao.insert(
                FlashThoughtEntity(
                    content = localJson.requiredRecordString("content"),
                    createdAt = localJson.requiredRecordLong("createdAt"),
                    updatedAt = local.updatedAt,
                    pinned = localJson.requiredRecordBoolean("pinned"),
                    deletedAt = null,
                    sortOrder = localJson.requiredRecordLong("sortOrder"),
                    categoryId = localJson.optionalRecordString("categoryName")?.let { name ->
                        categoryDao.getAllForBackup()
                            .firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
                    },
                    highlighted = localJson.requiredRecordBoolean("highlighted"),
                ),
            )
        }?.toString()
        RecordApplyResult(canonicalId.toString(), conflictCopyKey)
    }

    override suspend fun deleteLocalRecord(localKey: String) {
        val dao = database.flashThoughtDao()
        val id = localKey.toLongOrNull() ?: return
        val item = dao.getById(id) ?: return
        if (item.deletedAt == null) {
            dao.softDelete(id, System.currentTimeMillis())
        } else {
            dao.permanentlyDelete(id)
        }
    }
}

private class ThoughtCategoryRecordSyncAdapter(
    private val database: AppDatabase,
) : RecordSyncAdapter {
    override val contentType = CloudSyncContent.THOUGHT_CATEGORIES
    override val conflictPolicy = RecordConflictPolicy.LWW

    override suspend fun listLocalRecords(): List<LocalRecordRef> {
        val dao = database.thoughtCategoryDao()
        return dao.getAllForBackup().map {
            LocalRecordRef(it.id.toString(), it.updatedAt.coerceAtLeast(0L), it.updatedAt.coerceAtLeast(0L))
        }
    }

    override suspend fun readLocalRecord(localKey: String): SyncRecord {
        val item = database.thoughtCategoryDao().getAllForBackup()
            .firstOrNull { it.id.toString() == localKey }
            ?: throw CloudSyncConflictException("本地小巧思分类在同步读取期间被删除。")
        val payload = recordPayload(
            JSONObject()
                .put("name", item.name)
                .put("colorArgb", item.colorArgb)
                .put("sortOrder", item.sortOrder)
                .put("createdAt", item.createdAt)
                .put("updatedAt", item.updatedAt),
        )
        return SyncRecord(
            "local",
            item.updatedAt.coerceAtLeast(0L),
            item.updatedAt.coerceAtLeast(0L),
            payload = payload,
        )
    }

    override suspend fun applyRemoteRecord(
        record: SyncRecord,
        preserveLocalConflict: SyncRecord?,
        preserveLocalKey: String?,
    ): RecordApplyResult? = database.withTransaction {
        val dao = database.thoughtCategoryDao()
        val json = recordJson(record.payload)
        val item = ThoughtCategoryEntity(
            id = preserveLocalKey?.toLongOrNull() ?: 0L,
            name = json.requiredRecordString("name"),
            colorArgb = json.requiredRecordInt("colorArgb"),
            sortOrder = json.requiredRecordLong("sortOrder"),
            createdAt = json.requiredRecordLong("createdAt"),
            updatedAt = json.requiredRecordLong("updatedAt"),
        )
        val canonicalId = if (item.id != 0L && dao.getAllForBackup().any { it.id == item.id }) {
            dao.upsertForSync(item)
            item.id
        } else {
            dao.insertIfNameAvailable(item.copy(id = 0)) ?: dao.getAllForBackup()
                .firstOrNull { it.name.equals(item.name, ignoreCase = true) }?.id
                ?: throw CloudSyncException("无法创建远端小巧思分类。")
        }
        RecordApplyResult(canonicalId.toString())
    }

    override suspend fun deleteLocalRecord(localKey: String) {
        localKey.toLongOrNull()?.let { database.thoughtCategoryDao().deleteAndUncategorize(it) }
    }
}

private class DateRecordRecordSyncAdapter(
    private val database: AppDatabase,
) : RecordSyncAdapter {
    override val contentType = CloudSyncContent.DATE_RECORDS
    override val conflictPolicy = RecordConflictPolicy.CONFLICT_COPY

    override suspend fun listLocalRecords(): List<LocalRecordRef> = database.dateRecordDao()
        .getAllForBackup()
        .map { LocalRecordRef(it.id.toString(), it.updatedAt.coerceAtLeast(0L), it.updatedAt.coerceAtLeast(0L)) }

    override suspend fun readLocalRecord(localKey: String): SyncRecord {
        val item = database.dateRecordDao().getById(localKey.toLong())
            ?: throw CloudSyncConflictException("本地日期记录在同步读取期间被删除。")
        return SyncRecord(
            "local",
            item.updatedAt.coerceAtLeast(0L),
            item.updatedAt.coerceAtLeast(0L),
            payload = dateRecordPayload(item),
        )
    }

    override suspend fun applyRemoteRecord(
        record: SyncRecord,
        preserveLocalConflict: SyncRecord?,
        preserveLocalKey: String?,
    ): RecordApplyResult? = database.withTransaction {
        val dao = database.dateRecordDao()
        val json = recordJson(record.payload)
        val canonical = dateRecordFromJson(json, preserveLocalKey?.toLongOrNull() ?: 0L)
        val canonicalId = if (preserveLocalKey?.toLongOrNull()?.let { dao.getById(it) != null } == true) {
            dao.upsertForUndo(canonical)
            canonical.id
        } else {
            dao.insert(canonical)
        }
        val copyId = preserveLocalConflict?.let { local ->
            dao.insert(dateRecordFromJson(recordJson(local.payload), 0L)).toString()
        }
        RecordApplyResult(canonicalId.toString(), copyId)
    }

    override suspend fun deleteLocalRecord(localKey: String) {
        localKey.toLongOrNull()?.let { database.dateRecordDao().delete(it) }
    }

    private fun dateRecordPayload(item: DateRecordEntity) = recordPayload(
        JSONObject()
            .put("name", item.name)
            .put("icon", item.icon)
            .put("dateIso", item.dateIso)
            .put("createdAt", item.createdAt)
            .put("updatedAt", item.updatedAt),
    )

    private fun dateRecordFromJson(json: JSONObject, id: Long) = DateRecordEntity(
        id = id,
        name = json.requiredRecordString("name"),
        icon = json.requiredRecordString("icon"),
        dateIso = json.requiredRecordString("dateIso"),
        createdAt = json.requiredRecordLong("createdAt"),
        updatedAt = json.requiredRecordLong("updatedAt"),
    )
}

private class PoetryCategoryRecordSyncAdapter(
    private val database: AppDatabase,
) : RecordSyncAdapter {
    override val contentType = CloudSyncContent.POETRY_CATEGORIES
    override val conflictPolicy = RecordConflictPolicy.LWW

    override suspend fun listLocalRecords(): List<LocalRecordRef> = database.poetryCategoryDao()
        .getAllForBackup()
        .map { LocalRecordRef(it.id.toString(), it.updatedAt.coerceAtLeast(0L), it.updatedAt.coerceAtLeast(0L)) }

    override suspend fun readLocalRecord(localKey: String): SyncRecord {
        val item = database.poetryCategoryDao().getAllForBackup()
            .firstOrNull { it.id.toString() == localKey }
            ?: throw CloudSyncConflictException("本地诗词分类在同步读取期间被删除。")
        return SyncRecord(
            "local",
            item.updatedAt.coerceAtLeast(0L),
            item.updatedAt.coerceAtLeast(0L),
            payload = recordPayload(
                JSONObject()
                    .put("name", item.name)
                    .put("colorArgb", item.colorArgb)
                    .put("sortOrder", item.sortOrder)
                    .put("createdAt", item.createdAt)
                    .put("updatedAt", item.updatedAt),
            ),
        )
    }

    override suspend fun applyRemoteRecord(
        record: SyncRecord,
        preserveLocalConflict: SyncRecord?,
        preserveLocalKey: String?,
    ): RecordApplyResult? = database.withTransaction {
        val dao = database.poetryCategoryDao()
        val json = recordJson(record.payload)
        val item = PoetryCategoryEntity(
            id = preserveLocalKey?.toLongOrNull() ?: 0L,
            name = json.requiredRecordString("name"),
            colorArgb = json.requiredRecordInt("colorArgb"),
            sortOrder = json.requiredRecordLong("sortOrder"),
            createdAt = json.requiredRecordLong("createdAt"),
            updatedAt = json.requiredRecordLong("updatedAt"),
        )
        val canonicalId = if (item.id != 0L && dao.getAllForBackup().any { it.id == item.id }) {
            dao.upsertForSync(item)
            item.id
        } else {
            dao.insertIfNameAvailable(item.copy(id = 0)) ?: dao.getAllForBackup()
                .firstOrNull { it.name.equals(item.name, ignoreCase = true) }?.id
                ?: throw CloudSyncException("无法创建远端诗词分类。")
        }
        RecordApplyResult(canonicalId.toString())
    }

    override suspend fun deleteLocalRecord(localKey: String) {
        localKey.toLongOrNull()?.let { database.poetryCategoryDao().deleteAndUncategorize(it) }
    }
}

private class PoemRecordSyncAdapter(
    private val database: AppDatabase,
) : RecordSyncAdapter {
    override val contentType = CloudSyncContent.POEMS
    override val conflictPolicy = RecordConflictPolicy.CONFLICT_COPY

    override suspend fun listLocalRecords(): List<LocalRecordRef> = database.savedPoemDao()
        .getAllForBackup()
        .map { LocalRecordRef(it.id.toString(), it.updatedAt.coerceAtLeast(0L), it.updatedAt.coerceAtLeast(0L)) }

    override suspend fun readLocalRecord(localKey: String): SyncRecord {
        val item = database.savedPoemDao().getById(localKey.toLong())
            ?: throw CloudSyncConflictException("本地诗词在同步读取期间被删除。")
        return SyncRecord(
            "local",
            item.updatedAt.coerceAtLeast(0L),
            item.updatedAt.coerceAtLeast(0L),
            payload = poemPayload(item),
        )
    }

    override suspend fun applyRemoteRecord(
        record: SyncRecord,
        preserveLocalConflict: SyncRecord?,
        preserveLocalKey: String?,
    ): RecordApplyResult? = database.withTransaction {
        val dao = database.savedPoemDao()
        val json = recordJson(record.payload)
        val canonical = poemFromJson(json, preserveLocalKey?.toLongOrNull() ?: 0L)
        val canonicalId = if (preserveLocalKey?.toLongOrNull()?.let { dao.getById(it) != null } == true) {
            dao.upsertForUndo(canonical)
            canonical.id
        } else {
            dao.insert(canonical)
        }
        val copyId = preserveLocalConflict?.let { local ->
            dao.insert(poemFromJson(recordJson(local.payload), 0L)).toString()
        }
        RecordApplyResult(canonicalId.toString(), copyId)
    }

    override suspend fun deleteLocalRecord(localKey: String) {
        localKey.toLongOrNull()?.let { database.savedPoemDao().delete(it) }
    }

    private suspend fun poemPayload(item: SavedPoemEntity): ByteArray {
        val categoryName = item.categoryId?.let { id ->
            database.poetryCategoryDao().getAllForBackup().firstOrNull { it.id == id }?.name
        }
        return recordPayload(
            JSONObject()
                .put("content", item.content)
                .put("source", item.source)
                .put("createdAt", item.createdAt)
                .put("updatedAt", item.updatedAt)
                .put("sortOrder", item.sortOrder)
                .putRecord("categoryName", categoryName),
        )
    }

    private suspend fun poemFromJson(json: JSONObject, id: Long): SavedPoemEntity =
        SavedPoemEntity(
            id = id,
            content = json.requiredRecordString("content"),
            source = json.requiredRecordString("source"),
            createdAt = json.requiredRecordLong("createdAt"),
            updatedAt = json.requiredRecordLong("updatedAt"),
            sortOrder = json.requiredRecordLong("sortOrder"),
            categoryId = json.optionalRecordString("categoryName")?.let { name ->
                database.poetryCategoryDao().getAllForBackup()
                    .firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
            },
        )
}

private class FavoriteRecordSyncAdapter(
    private val database: AppDatabase,
) : RecordSyncAdapter {
    override val contentType = CloudSyncContent.FAVORITES
    override val conflictPolicy = RecordConflictPolicy.LWW

    override suspend fun listLocalRecords(): List<LocalRecordRef> = database.browserRecordDao()
        .getFavoritesForBackup()
        .map { LocalRecordRef(it.url, it.lastVisitedAt.coerceAtLeast(0L), it.lastVisitedAt.coerceAtLeast(0L)) }

    override suspend fun readLocalRecord(localKey: String): SyncRecord {
        val item = database.browserRecordDao().get(localKey)
            ?: throw CloudSyncConflictException("本地收藏在同步读取期间被删除。")
        return SyncRecord(
            "local",
            item.lastVisitedAt.coerceAtLeast(0L),
            item.lastVisitedAt.coerceAtLeast(0L),
            payload = recordPayload(
                JSONObject()
                    .put("url", item.url)
                    .put("title", item.title)
                    .put("lastVisitedAt", item.lastVisitedAt)
                    .put("visitCount", item.visitCount),
            ),
        )
    }

    override suspend fun applyRemoteRecord(
        record: SyncRecord,
        preserveLocalConflict: SyncRecord?,
        preserveLocalKey: String?,
    ): RecordApplyResult? {
        val json = recordJson(record.payload)
        val url = json.requiredRecordString("url")
        val dao = database.browserRecordDao()
        val existing = dao.get(url)
        val canonical = BrowserRecordEntity(
            url = url,
            title = json.requiredRecordString("title"),
            lastVisitedAt = json.requiredRecordLong("lastVisitedAt"),
            visitCount = json.requiredRecordInt("visitCount"),
            favorite = true,
        )
        dao.upsert(
            if (existing != null && existing.visitCount > canonical.visitCount) {
                canonical.copy(visitCount = existing.visitCount)
            } else {
                canonical
            },
        )
        return RecordApplyResult(url)
    }

    override suspend fun deleteLocalRecord(localKey: String) {
        database.browserRecordDao().setFavorite(localKey, false)
    }
}

private class GameStateRecordSyncAdapter(
    private val database: AppDatabase,
) : RecordSyncAdapter {
    override val contentType = CloudSyncContent.GAME_STATES
    override val conflictPolicy = RecordConflictPolicy.LWW

    override suspend fun listLocalRecords(): List<LocalRecordRef> = database.gameStateDao()
        .getAllForBackup()
        .map { LocalRecordRef(it.gameId, it.updatedAt.coerceAtLeast(0L), it.updatedAt.coerceAtLeast(0L)) }

    override suspend fun readLocalRecord(localKey: String): SyncRecord {
        val item = database.gameStateDao().get(localKey)
            ?: throw CloudSyncConflictException("本地游戏存档在同步读取期间被删除。")
        return SyncRecord(
            "local",
            item.updatedAt.coerceAtLeast(0L),
            item.updatedAt.coerceAtLeast(0L),
            payload = recordPayload(
                JSONObject()
                    .put("gameId", item.gameId)
                    .put("highScore", item.highScore)
                    .putRecord("saveJson", item.saveJson)
                    .put("updatedAt", item.updatedAt),
            ),
        )
    }

    override suspend fun applyRemoteRecord(
        record: SyncRecord,
        preserveLocalConflict: SyncRecord?,
        preserveLocalKey: String?,
    ): RecordApplyResult? {
        val json = recordJson(record.payload)
        val gameId = json.requiredRecordString("gameId")
        val existing = database.gameStateDao().get(gameId)
        val remote = GameStateEntity(
            gameId = gameId,
            highScore = json.requiredRecordInt("highScore"),
            saveJson = json.optionalRecordString("saveJson"),
            updatedAt = json.requiredRecordLong("updatedAt"),
        )
        database.gameStateDao().upsert(
            if (existing != null && existing.updatedAt > remote.updatedAt) existing else remote,
        )
        return RecordApplyResult(gameId)
    }

    override suspend fun deleteLocalRecord(localKey: String) {
        database.gameStateDao().clearSave(localKey, System.currentTimeMillis())
    }
}

private class GameStatisticRecordSyncAdapter(
    private val database: AppDatabase,
) : RecordSyncAdapter {
    override val contentType = CloudSyncContent.GAME_STATISTICS
    override val conflictPolicy = RecordConflictPolicy.LWW

    override suspend fun listLocalRecords(): List<LocalRecordRef> = database.gameStatisticDao()
        .getAllForBackup()
        .map { LocalRecordRef(gameStatisticKey(it.gameId, it.metricKey), it.updatedAt.coerceAtLeast(0L), it.updatedAt.coerceAtLeast(0L)) }

    override suspend fun readLocalRecord(localKey: String): SyncRecord {
        val (gameId, metricKey) = splitGameStatisticKey(localKey)
        val item = database.gameStatisticDao().getAllForBackup()
            .firstOrNull { it.gameId == gameId && it.metricKey == metricKey }
            ?: throw CloudSyncConflictException("本地游戏统计在同步读取期间被删除。")
        return SyncRecord(
            "local",
            item.updatedAt.coerceAtLeast(0L),
            item.updatedAt.coerceAtLeast(0L),
            payload = recordPayload(
                JSONObject()
                    .put("gameId", item.gameId)
                    .put("metricKey", item.metricKey)
                    .put("value", item.value)
                    .put("updatedAt", item.updatedAt),
            ),
        )
    }

    override suspend fun applyRemoteRecord(
        record: SyncRecord,
        preserveLocalConflict: SyncRecord?,
        preserveLocalKey: String?,
    ): RecordApplyResult? {
        val json = recordJson(record.payload)
        val item = GameStatisticEntity(
            gameId = json.requiredRecordString("gameId"),
            metricKey = json.requiredRecordString("metricKey"),
            value = json.requiredRecordLong("value"),
            updatedAt = json.requiredRecordLong("updatedAt"),
        )
        database.gameStatisticDao().upsertAll(listOf(item))
        return RecordApplyResult(gameStatisticKey(item.gameId, item.metricKey))
    }

    override suspend fun deleteLocalRecord(localKey: String) {
        val (gameId, metricKey) = splitGameStatisticKey(localKey)
        database.gameStatisticDao().delete(gameId, metricKey)
    }

    private fun gameStatisticKey(gameId: String, metricKey: String): String = "$gameId $metricKey"

    private fun splitGameStatisticKey(localKey: String): Pair<String, String> {
        val parts = localKey.split(" ")
        require(parts.size == 2) { "无效的游戏统计同步键。" }
        return parts[0] to parts[1]
    }
}
