package com.deskcubby.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
    indices = [Index("updatedAt")],
)
data class AiConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val modelConfigId: String,
    val createdAt: Long,
    val updatedAt: Long,
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
    indices = [Index("conversationId")],
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
 * One lifetime mini-game statistic. Metrics are deliberately separate from paused-game JSON so
 * old saves remain readable and clearing a save can never erase a user's accumulated history.
 */
@Entity(tableName = "game_statistics", primaryKeys = ["gameId", "metricKey"])
data class GameStatisticEntity(
    val gameId: String,
    val metricKey: String,
    @ColumnInfo(defaultValue = "0") val value: Long = 0L,
    val updatedAt: Long,
)
