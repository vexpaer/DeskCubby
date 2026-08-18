package com.deskcubby.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FlashThoughtDao {
    @Query("SELECT * FROM flash_thoughts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): FlashThoughtEntity?

    @Query("SELECT * FROM flash_thoughts ORDER BY id ASC")
    fun observeAllForBackup(): Flow<List<FlashThoughtEntity>>

    @Query("SELECT * FROM flash_thoughts ORDER BY id ASC")
    suspend fun getAllForBackup(): List<FlashThoughtEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllForBackup(items: List<FlashThoughtEntity>)

    @Query("DELETE FROM flash_thoughts")
    suspend fun clearAllForBackup()

    @Transaction
    suspend fun replaceAllForBackup(items: List<FlashThoughtEntity>) {
        clearAllForBackup()
        if (items.isNotEmpty()) insertAllForBackup(items)
    }

    @Query("SELECT * FROM flash_thoughts WHERE deletedAt IS NULL ORDER BY sortOrder ASC, pinned DESC, createdAt ASC, id ASC")
    fun observeActive(): Flow<List<FlashThoughtEntity>>

    @Query("SELECT id FROM flash_thoughts WHERE deletedAt IS NULL ORDER BY sortOrder ASC, pinned DESC, createdAt ASC, id ASC")
    suspend fun getActiveIdsInOrder(): List<Long>

    @Query(
        "SELECT id FROM flash_thoughts WHERE deletedAt IS NULL AND categoryId IS :categoryId " +
            "ORDER BY sortOrder ASC, pinned DESC, createdAt ASC, id ASC",
    )
    suspend fun getActiveIdsInCategory(categoryId: Long?): List<Long>

    @Query("SELECT * FROM flash_thoughts WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<FlashThoughtEntity>>

    @Query("SELECT * FROM flash_thoughts WHERE deletedAt IS NULL ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<FlashThoughtEntity>>

    @Insert
    suspend fun insert(item: FlashThoughtEntity): Long

    @Upsert
    suspend fun upsertForUndo(item: FlashThoughtEntity)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM flash_thoughts WHERE deletedAt IS NULL")
    suspend fun nextActiveSortOrder(): Long

    @Transaction
    suspend fun insertAtEnd(item: FlashThoughtEntity): Long =
        insert(item.copy(sortOrder = nextActiveSortOrder()))

    @Query("UPDATE flash_thoughts SET content = :content, updatedAt = :now WHERE id = :id")
    suspend fun updateContent(id: Long, content: String, now: Long)

    @Query("SELECT pinned FROM flash_thoughts WHERE id = :id LIMIT 1")
    suspend fun isPinned(id: Long): Boolean?

    @Query("SELECT COALESCE(MIN(sortOrder), 0) - 1 FROM flash_thoughts WHERE deletedAt IS NULL")
    suspend fun sortOrderBeforeFirst(): Long

    @Query("UPDATE flash_thoughts SET pinned = :pinned, sortOrder = :sortOrder, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, sortOrder: Long, now: Long)

    @Transaction
    suspend fun togglePinned(id: Long, now: Long) {
        val pinned = !(isPinned(id) ?: return)
        val sortOrder = if (pinned) sortOrderBeforeFirst() else nextActiveSortOrder()
        setPinned(id, pinned, sortOrder, now)
    }

    @Query("SELECT highlighted FROM flash_thoughts WHERE id = :id LIMIT 1")
    suspend fun isHighlighted(id: Long): Boolean?

    @Query("UPDATE flash_thoughts SET highlighted = :highlighted, updatedAt = :now WHERE id = :id")
    suspend fun setHighlighted(id: Long, highlighted: Boolean, now: Long)

    @Transaction
    suspend fun toggleHighlighted(id: Long, now: Long) {
        val highlighted = !(isHighlighted(id) ?: return)
        setHighlighted(id, highlighted, now)
    }

    @Query("UPDATE flash_thoughts SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("UPDATE flash_thoughts SET deletedAt = NULL, sortOrder = :sortOrder, updatedAt = :now WHERE id = :id")
    suspend fun restore(id: Long, now: Long, sortOrder: Long)

    @Transaction
    suspend fun restoreToActiveList(id: Long, now: Long) {
        val pinned = isPinned(id) ?: return
        val sortOrder = if (pinned) sortOrderBeforeFirst() else nextActiveSortOrder()
        restore(id, now, sortOrder)
    }

    @Query("UPDATE flash_thoughts SET sortOrder = :sortOrder WHERE id = :id AND deletedAt IS NULL")
    suspend fun updateSortOrder(id: Long, sortOrder: Long)

    @Transaction
    suspend fun replaceActiveOrder(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> updateSortOrder(id, index.toLong()) }
    }

    @Transaction
    suspend fun moveActive(id: Long, targetIndex: Int) {
        val orderedIds = getActiveIdsInOrder().toMutableList()
        val sourceIndex = orderedIds.indexOf(id)
        if (sourceIndex < 0) return
        val destination = targetIndex.coerceIn(0, orderedIds.lastIndex)
        if (sourceIndex == destination) return
        orderedIds.add(destination, orderedIds.removeAt(sourceIndex))
        replaceActiveOrder(orderedIds)
    }

    @Transaction
    suspend fun moveActiveInCategory(id: Long, targetIndex: Int, categoryId: Long?) {
        val allIds = getActiveIdsInOrder().toMutableList()
        val categoryIds = getActiveIdsInCategory(categoryId).toMutableList()
        val sourceIndex = categoryIds.indexOf(id)
        if (sourceIndex < 0 || categoryIds.isEmpty()) return
        val destination = targetIndex.coerceIn(0, categoryIds.lastIndex)
        if (sourceIndex == destination) return
        categoryIds.add(destination, categoryIds.removeAt(sourceIndex))

        val categoryIdSet = categoryIds.toHashSet()
        var replacementIndex = 0
        allIds.indices.forEach { index ->
            if (allIds[index] in categoryIdSet) {
                allIds[index] = categoryIds[replacementIndex++]
            }
        }
        replaceActiveOrder(allIds)
    }

    @Query("UPDATE flash_thoughts SET categoryId = :categoryId WHERE id = :id")
    suspend fun setCategory(id: Long, categoryId: Long?): Int

    @Query("DELETE FROM flash_thoughts WHERE id = :id AND deletedAt IS NOT NULL")
    suspend fun permanentlyDelete(id: Long)

    @Query("DELETE FROM flash_thoughts WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun purgeDeletedBefore(before: Long)
}

@Dao
interface ThoughtCategoryDao {
    @Query("SELECT * FROM thought_categories ORDER BY sortOrder ASC, createdAt ASC, id ASC")
    fun observeAll(): Flow<List<ThoughtCategoryEntity>>

    @Query("SELECT * FROM thought_categories ORDER BY id ASC")
    fun observeAllForBackup(): Flow<List<ThoughtCategoryEntity>>

    @Query("SELECT * FROM thought_categories ORDER BY id ASC")
    suspend fun getAllForBackup(): List<ThoughtCategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllForBackup(items: List<ThoughtCategoryEntity>)

    @Query("DELETE FROM thought_categories")
    suspend fun clearAllForBackup()

    @Query("SELECT id FROM thought_categories WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findIdByName(name: String): Long?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM thought_categories")
    suspend fun nextSortOrder(): Long

    @Insert
    suspend fun insert(item: ThoughtCategoryEntity): Long

    @Upsert
    suspend fun upsertForSync(item: ThoughtCategoryEntity)

    @Transaction
    suspend fun insertIfNameAvailable(item: ThoughtCategoryEntity): Long? {
        if (findIdByName(item.name) != null) return null
        return insert(item.copy(sortOrder = nextSortOrder()))
    }

    @Query(
        "UPDATE thought_categories SET name = :name, colorArgb = :colorArgb, updatedAt = :now " +
            "WHERE id = :id",
    )
    suspend fun update(id: Long, name: String, colorArgb: Int, now: Long): Int

    @Transaction
    suspend fun updateIfNameAvailable(id: Long, name: String, colorArgb: Int, now: Long): Boolean {
        val duplicateId = findIdByName(name)
        if (duplicateId != null && duplicateId != id) return false
        return update(id, name, colorArgb, now) > 0
    }

    @Query("UPDATE flash_thoughts SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun uncategorizeThoughts(categoryId: Long)

    @Query("DELETE FROM thought_categories WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Transaction
    suspend fun deleteAndUncategorize(id: Long): Boolean {
        uncategorizeThoughts(id)
        return delete(id) > 0
    }
}

@Dao
interface BrowserRecordDao {
    @Query("SELECT * FROM browser_records ORDER BY url COLLATE NOCASE ASC")
    suspend fun getAllBrowserRecordsForRollback(): List<BrowserRecordEntity>

    @Query("DELETE FROM browser_records")
    suspend fun clearAllBrowserRecordsForRollback()

    @Transaction
    suspend fun replaceAllBrowserRecordsForRollback(items: List<BrowserRecordEntity>) {
        clearAllBrowserRecordsForRollback()
        if (items.isNotEmpty()) upsertAllForBackup(items)
    }

    @Query("SELECT * FROM browser_records WHERE favorite = 1 ORDER BY url COLLATE NOCASE ASC")
    suspend fun getFavoritesForBackup(): List<BrowserRecordEntity>

    @Upsert
    suspend fun upsertAllForBackup(items: List<BrowserRecordEntity>)

    @Query("UPDATE browser_records SET favorite = 0 WHERE favorite = 1")
    suspend fun clearFavoriteFlags()

    @Transaction
    suspend fun replaceFavoritesForBackup(items: List<BrowserRecordEntity>) {
        clearFavoriteFlags()
        if (items.isNotEmpty()) {
            upsertAllForBackup(items.map { it.copy(favorite = true) })
        }
    }

    @Query("SELECT * FROM browser_records ORDER BY lastVisitedAt DESC LIMIT :limit")
    fun observeHistory(limit: Int = 200): Flow<List<BrowserRecordEntity>>

    @Query("SELECT * FROM browser_records WHERE favorite = 1 ORDER BY title COLLATE NOCASE")
    fun observeFavorites(): Flow<List<BrowserRecordEntity>>

    @Query("SELECT * FROM browser_records WHERE url = :url LIMIT 1")
    suspend fun get(url: String): BrowserRecordEntity?

    @Upsert
    suspend fun upsert(item: BrowserRecordEntity)

    @Query("UPDATE browser_records SET favorite = :favorite WHERE url = :url")
    suspend fun setFavorite(url: String, favorite: Boolean)

    @Query("DELETE FROM browser_records WHERE favorite = 0")
    suspend fun clearNonFavorites()
}

@Dao
interface DiaryIndexDao {
    @Query("SELECT * FROM diary_index ORDER BY dateIso DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<DiaryIndexEntity>>

    @Query("SELECT * FROM diary_index ORDER BY dateIso DESC, name COLLATE NOCASE")
    suspend fun getAll(): List<DiaryIndexEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DiaryIndexEntity>)

    @Query("DELETE FROM diary_index WHERE uri NOT IN (:activeUris)")
    suspend fun deleteMissing(activeUris: List<String>)

    @Query("DELETE FROM diary_index")
    suspend fun clear()

    @Transaction
    suspend fun replaceAfterSuccessfulScan(items: List<DiaryIndexEntity>) {
        if (items.isEmpty()) {
            clear()
        } else {
            insertAll(items)
            deleteMissing(items.map { it.uri })
        }
    }
}

@Dao
interface DateRecordDao {
    @Query("SELECT * FROM date_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DateRecordEntity?

    @Query("SELECT * FROM date_records ORDER BY dateIso ASC, createdAt ASC, id ASC")
    fun observeAll(): Flow<List<DateRecordEntity>>

    @Query("SELECT * FROM date_records ORDER BY id ASC")
    fun observeAllForBackup(): Flow<List<DateRecordEntity>>

    @Query("SELECT * FROM date_records ORDER BY id ASC")
    suspend fun getAllForBackup(): List<DateRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllForBackup(items: List<DateRecordEntity>)

    @Query("DELETE FROM date_records")
    suspend fun clearAllForBackup()

    @Transaction
    suspend fun replaceAllForBackup(items: List<DateRecordEntity>) {
        clearAllForBackup()
        if (items.isNotEmpty()) insertAllForBackup(items)
    }

    @Insert
    suspend fun insert(item: DateRecordEntity): Long

    @Upsert
    suspend fun upsertForUndo(item: DateRecordEntity)

    @Query(
        "UPDATE date_records SET name = :name, icon = :icon, dateIso = :dateIso, " +
            "updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        name: String,
        icon: String,
        dateIso: String,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM date_records WHERE id = :id")
    suspend fun delete(id: Long): Int
}

@Dao
interface SavedPoemDao {
    @Query("SELECT * FROM saved_poems ORDER BY sortOrder ASC, createdAt DESC, id DESC")
    fun observeAll(): Flow<List<SavedPoemEntity>>

    @Query("SELECT * FROM saved_poems WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SavedPoemEntity?

    @Query("SELECT * FROM saved_poems ORDER BY id ASC")
    fun observeAllForBackup(): Flow<List<SavedPoemEntity>>

    @Query("SELECT * FROM saved_poems ORDER BY id ASC")
    suspend fun getAllForBackup(): List<SavedPoemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllForBackup(items: List<SavedPoemEntity>)

    @Query("DELETE FROM saved_poems")
    suspend fun clearAllForBackup()

    @Transaction
    suspend fun replaceAllForBackup(items: List<SavedPoemEntity>) {
        clearAllForBackup()
        if (items.isNotEmpty()) insertAllForBackup(items)
    }

    @Insert
    suspend fun insert(item: SavedPoemEntity): Long

    @Upsert
    suspend fun upsertForUndo(item: SavedPoemEntity)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM saved_poems")
    suspend fun nextSortOrder(): Long

    @Transaction
    suspend fun insertAtEnd(item: SavedPoemEntity): Long =
        insert(item.copy(sortOrder = nextSortOrder()))

    @Query(
        "UPDATE saved_poems SET content = :content, source = :source, categoryId = :categoryId, " +
            "updatedAt = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        content: String,
        source: String,
        categoryId: Long?,
        updatedAt: Long,
    ): Int

    @Query("UPDATE saved_poems SET categoryId = :categoryId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setCategory(id: Long, categoryId: Long?, updatedAt: Long): Int

    @Query("SELECT id FROM saved_poems ORDER BY sortOrder ASC, createdAt DESC, id DESC")
    suspend fun getIdsInOrder(): List<Long>

    @Query(
        "SELECT id FROM saved_poems WHERE " +
            "(:categoryId IS NULL AND categoryId IS NULL) OR categoryId = :categoryId " +
            "ORDER BY sortOrder ASC, createdAt DESC, id DESC",
    )
    suspend fun getIdsInCategory(categoryId: Long?): List<Long>

    @Query("UPDATE saved_poems SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Long)

    @Transaction
    suspend fun replaceOrder(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> updateSortOrder(id, index.toLong()) }
    }

    @Transaction
    suspend fun move(id: Long, targetIndex: Int) {
        val orderedIds = getIdsInOrder()
        val reorderedIds = movePoemIdToIndex(orderedIds, id, targetIndex)
        if (reorderedIds != orderedIds) replaceOrder(reorderedIds)
    }

    @Transaction
    suspend fun moveInCategory(id: Long, targetIndex: Int, categoryId: Long?) {
        val allIds = getIdsInOrder()
        val categoryIds = getIdsInCategory(categoryId)
        val reorderedCategoryIds = movePoemIdToIndex(categoryIds, id, targetIndex)
        if (reorderedCategoryIds == categoryIds) return
        replaceOrder(replacePoemSubsetOrder(allIds, reorderedCategoryIds))
    }

    @Query(
        "SELECT id FROM saved_poems WHERE categoryId = :categoryId AND content = :content " +
            "AND source = :source LIMIT 1",
    )
    suspend fun findMatching(categoryId: Long, content: String, source: String): Long?

    @Query("DELETE FROM saved_poems WHERE id = :id")
    suspend fun delete(id: Long): Int

}

/** Reorders against the pre-move list so index zero is a valid destination and source. */
internal fun movePoemIdToIndex(
    orderedIds: List<Long>,
    id: Long,
    targetIndex: Int,
): List<Long> {
    val sourceIndex = orderedIds.indexOf(id)
    if (sourceIndex < 0 || orderedIds.size < 2) return orderedIds
    val destination = targetIndex.coerceIn(0, orderedIds.lastIndex)
    if (sourceIndex == destination) return orderedIds
    return orderedIds.toMutableList().apply {
        val moving = removeAt(sourceIndex)
        add(destination.coerceIn(0, size), moving)
    }
}

/** Places a filtered category order back into the same global slots without moving other poems. */
internal fun replacePoemSubsetOrder(
    allIds: List<Long>,
    orderedSubsetIds: List<Long>,
): List<Long> {
    val subset = orderedSubsetIds.toHashSet()
    if (subset.size != orderedSubsetIds.size) return allIds
    var replacementIndex = 0
    return allIds.map { id ->
        if (id in subset && replacementIndex < orderedSubsetIds.size) {
            orderedSubsetIds[replacementIndex++]
        } else {
            id
        }
    }.takeIf { replacementIndex == orderedSubsetIds.size } ?: allIds
}

@Dao
interface PoetryCategoryDao {
    @Query("SELECT * FROM poetry_categories ORDER BY sortOrder ASC, createdAt ASC, id ASC")
    fun observeAll(): Flow<List<PoetryCategoryEntity>>

    @Query("SELECT * FROM poetry_categories ORDER BY id ASC")
    fun observeAllForBackup(): Flow<List<PoetryCategoryEntity>>

    @Query("SELECT * FROM poetry_categories ORDER BY id ASC")
    suspend fun getAllForBackup(): List<PoetryCategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllForBackup(items: List<PoetryCategoryEntity>)

    @Query("DELETE FROM poetry_categories")
    suspend fun clearAllForBackup()

    @Query("SELECT id FROM poetry_categories WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findIdByName(name: String): Long?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM poetry_categories")
    suspend fun nextSortOrder(): Long

    @Insert
    suspend fun insert(item: PoetryCategoryEntity): Long

    @Upsert
    suspend fun upsertForSync(item: PoetryCategoryEntity)

    @Transaction
    suspend fun insertIfNameAvailable(item: PoetryCategoryEntity): Long? {
        if (findIdByName(item.name) != null) return null
        return insert(item.copy(sortOrder = nextSortOrder()))
    }

    @Query(
        "UPDATE poetry_categories SET name = :name, colorArgb = :colorArgb, updatedAt = :now " +
            "WHERE id = :id",
    )
    suspend fun update(id: Long, name: String, colorArgb: Int, now: Long): Int

    @Transaction
    suspend fun updateIfNameAvailable(id: Long, name: String, colorArgb: Int, now: Long): Boolean {
        val duplicateId = findIdByName(name)
        if (duplicateId != null && duplicateId != id) return false
        return update(id, name, colorArgb, now) > 0
    }

    @Query("UPDATE saved_poems SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun uncategorizePoems(categoryId: Long)

    @Query("DELETE FROM saved_poems WHERE categoryId = :categoryId")
    suspend fun deletePoems(categoryId: Long): Int

    @Query("DELETE FROM poetry_categories WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Transaction
    suspend fun deleteAndUncategorize(id: Long): Boolean {
        uncategorizePoems(id)
        return delete(id) > 0
    }

    @Transaction
    suspend fun deleteWithPoems(id: Long): Boolean {
        deletePoems(id)
        return delete(id) > 0
    }
}

@Dao
interface AiChatDao {
    @Query("SELECT * FROM ai_conversations WHERE deletedAt IS NULL ORDER BY updatedAt DESC, id DESC")
    fun observeConversations(): Flow<List<AiConversationEntity>>

    @Query("SELECT * FROM ai_conversations WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getConversation(id: Long): AiConversationEntity?

    @Query("SELECT * FROM ai_conversations ORDER BY id ASC")
    suspend fun getAllConversationsForSync(): List<AiConversationEntity>

    @Query("SELECT * FROM ai_messages ORDER BY id ASC")
    suspend fun getAllMessagesForSync(): List<AiMessageEntity>

    @Query("SELECT * FROM ai_attachments ORDER BY id ASC")
    suspend fun getAllAttachmentsForSync(): List<AiAttachmentEntity>

    @Query("SELECT * FROM ai_conversations WHERE syncId = :syncId LIMIT 1")
    suspend fun getConversationBySyncId(syncId: String): AiConversationEntity?

    @Query("SELECT * FROM ai_messages WHERE syncId = :syncId LIMIT 1")
    suspend fun getMessageBySyncId(syncId: String): AiMessageEntity?

    @Query("SELECT * FROM ai_attachments WHERE syncId = :syncId LIMIT 1")
    suspend fun getAttachmentBySyncId(syncId: String): AiAttachmentEntity?

    @Query(
        "SELECT * FROM ai_messages WHERE conversationId = :conversationId " +
            "ORDER BY createdAt ASC, id ASC",
    )
    fun observeMessages(conversationId: Long): Flow<List<AiMessageEntity>>

    @Query(
        "SELECT * FROM ai_messages WHERE conversationId = :conversationId " +
            "ORDER BY createdAt ASC, id ASC",
    )
    suspend fun getMessages(conversationId: Long): List<AiMessageEntity>

    @Transaction
    @Query(
        "SELECT * FROM ai_messages WHERE conversationId = :conversationId " +
            "ORDER BY createdAt ASC, id ASC",
    )
    fun observeMessagesWithAttachments(
        conversationId: Long,
    ): Flow<List<AiMessageWithAttachments>>

    @Transaction
    @Query(
        "SELECT * FROM ai_messages WHERE conversationId = :conversationId " +
            "ORDER BY createdAt ASC, id ASC",
    )
    suspend fun getMessagesWithAttachments(
        conversationId: Long,
    ): List<AiMessageWithAttachments>

    @Query("DELETE FROM ai_conversations")
    suspend fun clearAllConversationsForSync()

    @Insert
    suspend fun insertConversation(conversation: AiConversationEntity): Long

    @Insert
    suspend fun insertMessage(message: AiMessageEntity): Long

    @Insert
    suspend fun insertAttachments(attachments: List<AiAttachmentEntity>)

    @Query("UPDATE ai_conversations SET syncId = :syncId WHERE id = :id AND syncId IS NULL")
    suspend fun assignConversationSyncId(id: Long, syncId: String): Int

    @Query("UPDATE ai_messages SET syncId = :syncId WHERE id = :id AND syncId IS NULL")
    suspend fun assignMessageSyncId(id: Long, syncId: String): Int

    @Query("UPDATE ai_attachments SET syncId = :syncId WHERE id = :id AND syncId IS NULL")
    suspend fun assignAttachmentSyncId(id: Long, syncId: String): Int

    @Query(
        "UPDATE ai_conversations SET title = :title, modelConfigId = :modelConfigId, " +
            "createdAt = :createdAt, updatedAt = :updatedAt, deletedAt = :deletedAt " +
            "WHERE id = :id",
    )
    suspend fun updateConversationFromSync(
        id: Long,
        title: String,
        modelConfigId: String,
        createdAt: Long,
        updatedAt: Long,
        deletedAt: Long?,
    ): Int

    @Query(
        "UPDATE ai_conversations SET updatedAt = :updatedAt WHERE id = :conversationId",
    )
    suspend fun touchConversation(conversationId: Long, updatedAt: Long): Int

    @Transaction
    suspend fun insertMessageAndTouch(message: AiMessageEntity): Long {
        val id = insertMessage(message)
        touchConversation(message.conversationId, message.createdAt)
        return id
    }

    @Transaction
    suspend fun insertMessageWithAttachmentsAndTouch(
        message: AiMessageEntity,
        attachments: List<AiAttachmentEntity>,
    ): Long {
        val id = insertMessage(message)
        if (attachments.isNotEmpty()) {
            insertAttachments(attachments.map { it.copy(messageId = id) })
        }
        touchConversation(message.conversationId, message.createdAt)
        return id
    }

    /**
     * Persists one logical user turn atomically. A frozen system-context message, when present,
     * must never survive without the user message it was attached to.
     */
    @Transaction
    suspend fun insertUserTurnAndTouch(messages: List<AiMessageEntity>): List<Long> {
        require(messages.isNotEmpty()) { "A user turn must contain at least one message" }
        val conversationId = messages.first().conversationId
        require(messages.all { it.conversationId == conversationId }) {
            "Every message in a user turn must belong to the same conversation"
        }
        val ids = messages.map { insertMessage(it) }
        touchConversation(conversationId, messages.maxOf(AiMessageEntity::createdAt))
        return ids
    }

    @Query(
        "UPDATE ai_conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun renameConversation(id: Long, title: String, updatedAt: Long): Int

    @Query(
        "UPDATE ai_conversations SET modelConfigId = :modelConfigId, updatedAt = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun setModelConfig(id: Long, modelConfigId: String, updatedAt: Long): Int

    @Query(
        "SELECT COALESCE(MAX(imagePermissionOwned), 0) FROM ai_messages WHERE imageUri = :imageUri",
    )
    suspend fun isImagePermissionOwned(imageUri: String): Boolean

    @Query(
        "SELECT DISTINCT imageUri FROM ai_messages " +
            "WHERE conversationId = :conversationId AND imageUri IS NOT NULL " +
            "AND imagePermissionOwned = 1",
    )
    suspend fun getOwnedImageUris(conversationId: Long): List<String>

    @Query(
        "SELECT DISTINCT ai_attachments.uri FROM ai_attachments " +
            "INNER JOIN ai_messages ON ai_messages.id = ai_attachments.messageId " +
            "WHERE ai_messages.conversationId = :conversationId " +
            "AND ai_attachments.permissionOwned = 1",
    )
    suspend fun getOwnedAttachmentUris(conversationId: Long): List<String>

    @Query(
        "SELECT COALESCE(MAX(permissionOwned), 0) FROM ai_attachments WHERE uri = :uri",
    )
    suspend fun isAttachmentPermissionOwned(uri: String): Boolean

    @Query("SELECT COUNT(*) FROM ai_messages WHERE imageUri = :imageUri")
    suspend fun countImageReferences(imageUri: String): Int

    @Query("SELECT COUNT(*) FROM ai_attachments WHERE uri = :uri")
    suspend fun countAttachmentReferences(uri: String): Int

    @Query(
        "UPDATE ai_conversations SET deletedAt = :deletedAt, updatedAt = :deletedAt " +
            "WHERE id = :id AND deletedAt IS NULL",
    )
    suspend fun deleteConversation(id: Long, deletedAt: Long): Int

    @Query(
        "UPDATE ai_messages SET imageUri = NULL, imagePermissionOwned = 0 " +
            "WHERE conversationId = :conversationId",
    )
    suspend fun clearDeletedConversationImageUris(conversationId: Long): Int

    @Query(
        "UPDATE ai_attachments SET uri = '', permissionOwned = 0 WHERE messageId IN " +
            "(SELECT id FROM ai_messages WHERE conversationId = :conversationId)",
    )
    suspend fun clearDeletedConversationAttachmentUris(conversationId: Long): Int

    @Transaction
    suspend fun deleteConversationAndGetOwnedImageUris(id: Long): List<String>? {
        val imageUris = (getOwnedImageUris(id) + getOwnedAttachmentUris(id)).distinct()
        if (deleteConversation(id, System.currentTimeMillis()) <= 0) return null
        clearDeletedConversationImageUris(id)
        clearDeletedConversationAttachmentUris(id)
        return imageUris
    }
}

@Dao
interface AgentDao {
    @Query("SELECT * FROM agent_runs ORDER BY startedAt DESC")
    fun observeRuns(): Flow<List<AgentRunEntity>>

    @Query(
        "SELECT COUNT(*) AS runCount, COALESCE(SUM(modelCallCount), 0) AS modelCallCount, " +
            "COALESCE(SUM(usageReportedCallCount), 0) AS usageReportedCallCount, " +
            "SUM(inputTokens) AS inputTokens, SUM(outputTokens) AS outputTokens, " +
            "SUM(totalTokens) AS totalTokens, SUM(cachedInputTokens) AS cachedInputTokens, " +
            "SUM(cacheRateInputTokens) AS cacheRateInputTokens, " +
            "SUM(reasoningTokens) AS reasoningTokens FROM agent_runs",
    )
    fun observeUsageAggregate(): Flow<AgentUsageAggregate>

    @Query("SELECT * FROM agent_runs WHERE runId = :runId LIMIT 1")
    suspend fun getRun(runId: String): AgentRunEntity?

    @Query("SELECT * FROM agent_runs ORDER BY startedAt ASC, runId ASC")
    suspend fun getRunsForSync(): List<AgentRunEntity>

    @Query("SELECT * FROM agent_tool_events WHERE runId = :runId ORDER BY sequence ASC")
    fun observeToolEvents(runId: String): Flow<List<AgentToolEventEntity>>

    @Query("SELECT * FROM agent_tool_events WHERE runId = :runId ORDER BY sequence ASC")
    suspend fun getToolEvents(runId: String): List<AgentToolEventEntity>

    @Query("SELECT * FROM agent_mutations WHERE runId = :runId ORDER BY createdAt ASC, id ASC")
    fun observeMutations(runId: String): Flow<List<AgentMutationEntity>>

    @Query("SELECT * FROM agent_mutations WHERE runId = :runId ORDER BY createdAt ASC, id ASC")
    suspend fun getMutations(runId: String): List<AgentMutationEntity>

    @Query("SELECT * FROM agent_mutations WHERE id = :id LIMIT 1")
    suspend fun getMutation(id: Long): AgentMutationEntity?

    @Query("DELETE FROM agent_runs")
    suspend fun clearAllRunsForSync()

    @Insert
    suspend fun insertRun(run: AgentRunEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRunFromSync(run: AgentRunEntity): Long

    @Query(
        "UPDATE agent_runs SET conversationId = :conversationId, conversationTitle = :conversationTitle, " +
            "userRequestSummary = :userRequestSummary, status = :status, " +
            "modelCallCount = :modelCallCount, usageReportedCallCount = :usageReportedCallCount, " +
            "inputTokens = :inputTokens, outputTokens = :outputTokens, totalTokens = :totalTokens, " +
            "cachedInputTokens = :cachedInputTokens, cacheRateInputTokens = :cacheRateInputTokens, " +
            "reasoningTokens = :reasoningTokens, " +
            "completedAt = :completedAt WHERE runId = :runId AND " +
            "COALESCE(completedAt, 0) < COALESCE(:completedAt, 0)",
    )
    suspend fun updateRunFromSync(
        runId: String,
        conversationId: Long?,
        conversationTitle: String,
        userRequestSummary: String,
        status: String,
        modelCallCount: Int,
        usageReportedCallCount: Int,
        inputTokens: Long?,
        outputTokens: Long?,
        totalTokens: Long?,
        cachedInputTokens: Long?,
        cacheRateInputTokens: Long?,
        reasoningTokens: Long?,
        completedAt: Long?,
    ): Int

    @Insert
    suspend fun insertToolEvent(event: AgentToolEventEntity): Long

    @Insert
    suspend fun insertMutation(mutation: AgentMutationEntity): Long

    @Query(
        "UPDATE agent_runs SET status = :status, modelCallCount = :modelCallCount, " +
            "usageReportedCallCount = :usageReportedCallCount, inputTokens = :inputTokens, " +
            "outputTokens = :outputTokens, totalTokens = :totalTokens, " +
            "cachedInputTokens = :cachedInputTokens, cacheRateInputTokens = :cacheRateInputTokens, " +
            "reasoningTokens = :reasoningTokens, " +
            "completedAt = :completedAt WHERE runId = :runId",
    )
    suspend fun finishRun(
        runId: String,
        status: String,
        modelCallCount: Int,
        usageReportedCallCount: Int,
        inputTokens: Long?,
        outputTokens: Long?,
        totalTokens: Long?,
        cachedInputTokens: Long?,
        cacheRateInputTokens: Long?,
        reasoningTokens: Long?,
        completedAt: Long,
    ): Int

    @Query(
        "UPDATE agent_tool_events SET status = :status, target = :target, summary = :summary, " +
            "resultSummary = :resultSummary, errorCode = :errorCode, completedAt = :completedAt " +
            "WHERE id = :id",
    )
    suspend fun finishToolEvent(
        id: Long,
        status: String,
        target: String,
        summary: String,
        resultSummary: String,
        errorCode: String?,
        completedAt: Long,
    ): Int

    @Query(
        "UPDATE agent_mutations SET beforeContent = :beforeContent, " +
            "afterContent = :afterContent, undoPayload = :undoPayload, status = :status " +
            "WHERE id = :id",
    )
    suspend fun completeMutation(
        id: Long,
        beforeContent: String,
        afterContent: String,
        undoPayload: String,
        status: String,
    ): Int

    @Query(
        "UPDATE agent_mutations SET status = :status WHERE id = :id AND status = 'PENDING'",
    )
    suspend fun failPendingMutation(id: Long, status: String): Int

    @Query(
        "UPDATE agent_mutations SET status = :status, undoneAt = :undoneAt " +
            "WHERE id = :id AND status = 'APPLIED'",
    )
    suspend fun markMutationUndone(id: Long, status: String, undoneAt: Long): Int
}

@Dao
interface VaultItemDao {
    @Query("SELECT * FROM vault_items ORDER BY sortOrder ASC, updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<VaultItemEntity>>

    @Query("SELECT * FROM vault_items ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<VaultItemEntity>

    @Query("SELECT * FROM vault_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): VaultItemEntity?

    @Insert
    suspend fun insert(item: VaultItemEntity): Long

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM vault_items")
    suspend fun nextSortOrder(): Long

    @Transaction
    suspend fun insertAtEnd(item: VaultItemEntity): Long =
        insert(item.copy(sortOrder = nextSortOrder()))

    @Query(
        "UPDATE vault_items SET cipherText = :cipherText, iv = :iv, updatedAt = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun update(id: Long, cipherText: String, iv: String, updatedAt: Long): Int

    @Query("DELETE FROM vault_items WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query(
        "SELECT id FROM vault_items WHERE id != :excludedId " +
            "ORDER BY sortOrder ASC, updatedAt DESC, id DESC",
    )
    suspend fun getUserIdsInOrder(excludedId: Long): List<Long>

    @Query("UPDATE vault_items SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Long)

    @Transaction
    suspend fun replaceUserOrder(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id ->
            updateSortOrder(id, index.toLong())
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<VaultItemEntity>)

    @Query("DELETE FROM vault_items")
    suspend fun clearAll()

    /** Used when the vault password changes and every row must be re-encrypted atomically. */
    @Transaction
    suspend fun replaceAll(items: List<VaultItemEntity>) {
        clearAll()
        if (items.isNotEmpty()) insertAll(items)
    }
}

@Dao
interface GameStateDao {
    @Query("SELECT * FROM game_states ORDER BY gameId ASC")
    fun observeAllForBackup(): Flow<List<GameStateEntity>>

    @Query("SELECT * FROM game_states ORDER BY gameId ASC")
    suspend fun getAllForBackup(): List<GameStateEntity>

    @Query("SELECT * FROM game_states WHERE gameId = :gameId LIMIT 1")
    fun observe(gameId: String): Flow<GameStateEntity?>

    @Query("SELECT * FROM game_states WHERE gameId = :gameId LIMIT 1")
    suspend fun get(gameId: String): GameStateEntity?

    @Upsert
    suspend fun upsert(item: GameStateEntity)

    /**
     * Updates a paused/finished round without allowing a backup-restore transaction to interleave
     * between the high-score read and write.
     */
    @Transaction
    suspend fun upsertPreservingHighScore(
        gameId: String,
        saveJson: String?,
        score: Int,
        now: Long,
    ) {
        val existing = get(gameId)
        upsert(
            GameStateEntity(
                gameId = gameId,
                highScore = maxOf(existing?.highScore ?: 0, score),
                saveJson = saveJson,
                updatedAt = now,
            ),
        )
    }

    @Upsert
    suspend fun upsertAll(items: List<GameStateEntity>)

    @Query("DELETE FROM game_states")
    suspend fun clearAllForBackup()

    @Transaction
    suspend fun replaceAllForBackup(items: List<GameStateEntity>) {
        clearAllForBackup()
        if (items.isNotEmpty()) upsertAll(items)
    }

    @Query("UPDATE game_states SET saveJson = NULL, updatedAt = :now WHERE gameId = :gameId")
    suspend fun clearSave(gameId: String, now: Long)
}

@Dao
interface GameStatisticDao {
    @Query("SELECT * FROM game_statistics ORDER BY gameId ASC, metricKey ASC")
    fun observeAll(): Flow<List<GameStatisticEntity>>

    @Query("SELECT * FROM game_statistics ORDER BY gameId ASC, metricKey ASC")
    suspend fun getAllForBackup(): List<GameStatisticEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(item: GameStatisticEntity): Long

    @Query(
        "UPDATE game_statistics SET value = CASE " +
            "WHEN value > 9223372036854775807 - :delta THEN 9223372036854775807 " +
            "ELSE value + :delta END, updatedAt = :now " +
            "WHERE gameId = :gameId AND metricKey = :metricKey",
    )
    suspend fun incrementExisting(
        gameId: String,
        metricKey: String,
        delta: Long,
        now: Long,
    )

    @Query(
        "UPDATE game_statistics SET value = CASE WHEN value < :candidate THEN :candidate " +
            "ELSE value END, updatedAt = :now " +
            "WHERE gameId = :gameId AND metricKey = :metricKey",
    )
    suspend fun recordMaximumExisting(
        gameId: String,
        metricKey: String,
        candidate: Long,
        now: Long,
    )

    @Transaction
    suspend fun applyMetrics(
        gameId: String,
        increments: Map<String, Long>,
        maxima: Map<String, Long>,
        now: Long,
    ) {
        increments.forEach { (metricKey, delta) ->
            insertIfMissing(GameStatisticEntity(gameId, metricKey, 0L, now))
            incrementExisting(gameId, metricKey, delta, now)
        }
        maxima.forEach { (metricKey, candidate) ->
            insertIfMissing(GameStatisticEntity(gameId, metricKey, candidate, now))
            recordMaximumExisting(gameId, metricKey, candidate, now)
        }
    }

    @Upsert
    suspend fun upsertAll(items: List<GameStatisticEntity>)

    @Query("DELETE FROM game_statistics WHERE gameId = :gameId AND metricKey = :metricKey")
    suspend fun delete(gameId: String, metricKey: String): Int

    @Query("DELETE FROM game_statistics")
    suspend fun clearAllForBackup()

    @Transaction
    suspend fun replaceAllForBackup(items: List<GameStatisticEntity>) {
        clearAllForBackup()
        if (items.isNotEmpty()) upsertAll(items)
    }
}

@Dao
interface AiTaskDao {
    @Insert
    suspend fun insert(task: com.deskcubby.app.data.local.AiTaskQueueEntity): Long

    @Query("SELECT * FROM ai_task_queue WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): com.deskcubby.app.data.local.AiTaskQueueEntity?

    @Query("SELECT * FROM ai_task_queue WHERE id = :id")
    fun observeById(id: Long): Flow<com.deskcubby.app.data.local.AiTaskQueueEntity?>

    @Query("SELECT * FROM ai_task_queue ORDER BY id ASC")
    fun observeAll(): Flow<List<com.deskcubby.app.data.local.AiTaskQueueEntity>>

    @Query("SELECT * FROM ai_task_queue ORDER BY id ASC")
    suspend fun getAll(): List<com.deskcubby.app.data.local.AiTaskQueueEntity>

    @Query(
        "UPDATE ai_task_queue SET state = :running, startedAt = :startedAt " +
            "WHERE id IN (SELECT id FROM ai_task_queue WHERE state = :queued " +
            "ORDER BY id ASC LIMIT 1)",
    )
    suspend fun claimOldest(
        queued: com.deskcubby.app.data.local.AiTaskStateEntity,
        running: com.deskcubby.app.data.local.AiTaskStateEntity,
        startedAt: Long,
    ): Int

    @Query(
        "SELECT * FROM ai_task_queue WHERE id IN " +
            "(SELECT id FROM ai_task_queue WHERE state = :queued ORDER BY id ASC LIMIT 1) LIMIT 1",
    )
    suspend fun peekOldest(queued: com.deskcubby.app.data.local.AiTaskStateEntity): com.deskcubby.app.data.local.AiTaskQueueEntity?

    @Query(
        "SELECT * FROM ai_task_queue WHERE state = :queued OR state = :running " +
            "ORDER BY id ASC LIMIT 1",
    )
    suspend fun nextIncomplete(
        queued: com.deskcubby.app.data.local.AiTaskStateEntity,
        running: com.deskcubby.app.data.local.AiTaskStateEntity,
    ): com.deskcubby.app.data.local.AiTaskQueueEntity?

    @Query(
        "UPDATE ai_task_queue SET state = :succeeded, resultJson = :resultJson, " +
            "completedAt = :completedAt WHERE id = :id",
    )
    suspend fun markSucceeded(
        id: Long,
        succeeded: com.deskcubby.app.data.local.AiTaskStateEntity,
        resultJson: String,
        completedAt: Long,
    )

    @Query(
        "UPDATE ai_task_queue SET state = :failed, errorSummary = :errorSummary, " +
            "errorFailure = :errorFailure, attemptCount = :attemptCount, completedAt = :completedAt " +
            "WHERE id = :id",
    )
    suspend fun markFailed(
        id: Long,
        failed: com.deskcubby.app.data.local.AiTaskStateEntity,
        errorSummary: String,
        errorFailure: String,
        attemptCount: Int,
        completedAt: Long,
    )

    @Query("UPDATE ai_task_queue SET state = :canceled, completedAt = :completedAt WHERE id = :id")
    suspend fun markCanceled(
        id: Long,
        canceled: com.deskcubby.app.data.local.AiTaskStateEntity,
        completedAt: Long,
    )

    @Query("UPDATE ai_task_queue SET state = :waiting WHERE id = :id")
    suspend fun markWaitingApproval(
        id: Long,
        waiting: com.deskcubby.app.data.local.AiTaskStateEntity,
    )

    @Query("UPDATE ai_task_queue SET progressJson = :progressJson WHERE id = :id")
    suspend fun setProgress(id: Long, progressJson: String)

    @Query(
        "UPDATE ai_task_queue SET payloadJson = :payloadJson WHERE id = :id AND state = :queued",
    )
    suspend fun updatePayloadIfQueued(
        id: Long,
        payloadJson: String,
        queued: com.deskcubby.app.data.local.AiTaskStateEntity,
    ): Int

    @Query("UPDATE ai_task_queue SET attemptCount = :attemptCount WHERE id = :id")
    suspend fun setAttemptCount(id: Long, attemptCount: Int)

    @Query("DELETE FROM ai_task_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM ai_task_queue WHERE state = :queued")
    suspend fun countQueued(queued: com.deskcubby.app.data.local.AiTaskStateEntity): Int

    @Query("SELECT COUNT(*) FROM ai_task_queue WHERE state = :candidate")
    suspend fun countByState(candidate: com.deskcubby.app.data.local.AiTaskStateEntity): Int
}
