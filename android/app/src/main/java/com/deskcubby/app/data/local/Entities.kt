package com.deskcubby.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "flash_thoughts",
    foreignKeys = [
        ForeignKey(
            entity = ThoughtCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("categoryId")],
)
data class FlashThoughtEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val deletedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val sortOrder: Long = 0,
    @ColumnInfo(defaultValue = "NULL") val categoryId: Long? = null,
    @ColumnInfo(defaultValue = "0") val highlighted: Boolean = false,
)

@Entity(
    tableName = "thought_categories",
    indices = [Index(value = ["name"], unique = true)],
)
data class ThoughtCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val colorArgb: Int,
    val sortOrder: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "browser_records")
data class BrowserRecordEntity(
    @PrimaryKey val url: String,
    val title: String,
    val lastVisitedAt: Long,
    val visitCount: Int = 1,
    val favorite: Boolean = false,
)

@Entity(tableName = "diary_index")
data class DiaryIndexEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val title: String,
    val dateIso: String,
    val monthKey: String,
    val lastModified: Long,
    val size: Long,
    val wordCount: Int,
    val sha256: String,
    val indexedAt: Long,
)

@Entity(tableName = "date_records")
data class DateRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String,
    val dateIso: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "poetry_categories",
    indices = [Index(value = ["name"], unique = true)],
)
data class PoetryCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val colorArgb: Int,
    val sortOrder: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "saved_poems",
    foreignKeys = [
        ForeignKey(
            entity = PoetryCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("categoryId")],
)
data class SavedPoemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Canonical saved body. List previews must never replace this value with an excerpt. */
    val content: String,
    val source: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val sortOrder: Long = 0,
    @ColumnInfo(defaultValue = "NULL") val categoryId: Long? = null,
)

@Entity(
    tableName = "ai_conversations",
    indices = [Index("updatedAt"), Index(value = ["syncId"], unique = true)],
)
data class AiConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val modelConfigId: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "NULL") val syncId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val deletedAt: Long? = null,
)

@Entity(
    tableName = "ai_messages",
    foreignKeys = [
        ForeignKey(
            entity = AiConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index(value = ["syncId"], unique = true)],
)
data class AiMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val reasoning: String,
    val imageUri: String?,
    val imageMimeType: String?,
    val imagePermissionOwned: Boolean,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "NULL") val syncId: String? = null,
)

@Entity(
    tableName = "ai_attachments",
    foreignKeys = [
        ForeignKey(
            entity = AiMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("messageId"),
        Index("uri"),
        Index(value = ["syncId"], unique = true),
    ],
)
data class AiAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val uri: String,
    val mimeType: String,
    val displayName: String,
    val sizeBytes: Long,
    val kind: String,
    val extractedText: String?,
    val permissionOwned: Boolean,
    @ColumnInfo(defaultValue = "NULL") val syncId: String? = null,
)

data class AiMessageWithAttachments(
    @Embedded val message: AiMessageEntity,
    @Relation(parentColumn = "id", entityColumn = "messageId")
    val attachments: List<AiAttachmentEntity>,
)

@Entity(
    tableName = "agent_runs",
    indices = [Index("conversationId"), Index("startedAt")],
)
data class AgentRunEntity(
    @PrimaryKey val runId: String,
    val conversationId: Long?,
    val conversationTitle: String,
    val userRequestSummary: String,
    val modelConfigId: String,
    val permissionMode: String,
    val enabledSourcesJson: String,
    val status: String,
    @ColumnInfo(defaultValue = "0") val modelCallCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val usageReportedCallCount: Int = 0,
    @ColumnInfo(defaultValue = "NULL") val inputTokens: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val outputTokens: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val totalTokens: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val cachedInputTokens: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val cacheRateInputTokens: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val reasoningTokens: Long? = null,
    val startedAt: Long,
    val completedAt: Long?,
)

data class AgentUsageAggregate(
    val runCount: Long,
    val modelCallCount: Long,
    val usageReportedCallCount: Long,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
    val cachedInputTokens: Long?,
    val cacheRateInputTokens: Long?,
    val reasoningTokens: Long?,
)

@Entity(
    tableName = "agent_tool_events",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("runId"), Index(value = ["runId", "sequence"], unique = true)],
)
data class AgentToolEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val sequence: Int,
    val toolCallId: String,
    val toolName: String,
    val classification: String,
    val status: String,
    val target: String,
    val summary: String,
    val argumentsSummary: String,
    val resultSummary: String,
    val errorCode: String?,
    val startedAt: Long,
    val completedAt: Long?,
)

@Entity(
    tableName = "agent_mutations",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AgentToolEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["toolEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("runId"), Index(value = ["toolEventId"], unique = true)],
)
data class AgentMutationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val toolEventId: Long,
    val toolName: String,
    val target: String,
    val operation: String,
    val summary: String,
    val beforeContent: String,
    val afterContent: String,
    val undoPayload: String,
    val status: String,
    val createdAt: Long,
    val undoneAt: Long?,
)

/**
 * A password-protected vault entry. [cipherText] and [iv] are Base64; the versioned
 * content + optional-note payload is only recoverable with the key derived from the
 * user's vault password. Never stored or backed up in plain form.
 */
@Entity(tableName = "vault_items")
data class VaultItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cipherText: String,
    val iv: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val sortOrder: Long = 0,
)

/** High score plus an optional serialized paused-game snapshot for one mini game. */
@Entity(tableName = "game_states")
data class GameStateEntity(
    @PrimaryKey val gameId: String,
    @ColumnInfo(defaultValue = "0") val highScore: Int = 0,
    val saveJson: String? = null,
    val updatedAt: Long,
)

/**
 * One mini-game statistic. Metrics are deliberately separate from paused-game JSON so
 * old saves remain readable and clearing a save can never erase a user's accumulated history.
 */
@Entity(tableName = "game_statistics", primaryKeys = ["gameId", "metricKey"])
data class GameStatisticEntity(
    val gameId: String,
    val metricKey: String,
    @ColumnInfo(defaultValue = "0") val value: Long = 0L,
    val updatedAt: Long,
)

/**
 * Parse state of one Markdown diary file for the structured-records index. The index is a derived
 * cache that can be fully rebuilt from Markdown + `.deskcubby`; this row drives incremental
 * updates by detecting mtime/hash changes without rescanning every file.
 */
@Entity(tableName = "structured_record_files")
data class StructuredRecordFileEntity(
    @PrimaryKey val sourceFile: String,
    val modifiedAt: Long,
    val fileSize: Long,
    val sha256: String,
    val parsedAt: Long,
)

/**
 * One structured field value occurrence found in a Markdown diary. Belongs to a Journal Day, not a
 * calendar date, so boundary handling stays uniform across the app.
 */
@Entity(
    tableName = "structured_record_occurrences",
    indices = [
        Index(value = ["fieldId", "journalDay"]),
        Index(value = ["sourceFile"]),
        Index(value = ["journalDay"]),
    ],
)
data class StructuredRecordOccurrenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val journalDay: String,
    val sourceFile: String,
    val sourceFileModifiedAt: Long,
    val fieldId: String,
    val rawValue: String,
    val normalizedValue: String,
    val valueType: String,
    val orderInFile: Int,
    val parsedAt: Long,
)
