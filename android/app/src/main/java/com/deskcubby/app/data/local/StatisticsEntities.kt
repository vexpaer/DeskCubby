package com.deskcubby.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A normalized usage-history header. The reserved local owner stores this device's history;
 * UUID owners store histories received from other devices.
 */
@Entity(tableName = "usage_histories")
data class UsageHistoryEntity(
    @androidx.room.PrimaryKey val ownerId: String,
    val trackingStartedOn: String?,
    val backfillCompletedThrough: String?,
)

@Entity(
    tableName = "usage_days",
    primaryKeys = ["ownerId", "dateIso"],
    foreignKeys = [
        ForeignKey(
            entity = UsageHistoryEntity::class,
            parentColumns = ["ownerId"],
            childColumns = ["ownerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("ownerId")],
)
data class UsageDayEntity(
    val ownerId: String,
    val dateIso: String,
    val zoneId: String,
    val state: String,
    val collectedAtEpochMillis: Long,
)

@Entity(
    tableName = "usage_app_durations",
    primaryKeys = ["ownerId", "dateIso", "packageName"],
    foreignKeys = [
        ForeignKey(
            entity = UsageDayEntity::class,
            parentColumns = ["ownerId", "dateIso"],
            childColumns = ["ownerId", "dateIso"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["ownerId", "dateIso"])],
)
data class UsageAppDurationEntity(
    val ownerId: String,
    val dateIso: String,
    val packageName: String,
    val foregroundMillis: Long,
)

/** Metadata for a non-local usage history received through JSON backup or cloud sync. */
@Entity(
    tableName = "usage_devices",
    foreignKeys = [
        ForeignKey(
            entity = UsageHistoryEntity::class,
            parentColumns = ["ownerId"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class UsageDeviceEntity(
    @androidx.room.PrimaryKey val deviceId: String,
    val deviceName: String,
    val platform: String,
    val updatedAtEpochMillis: Long,
)

/** Device-local Health Connect collection state and its history header. */
@Entity(tableName = "step_history")
data class StepHistoryEntity(
    @androidx.room.PrimaryKey val id: Int,
    val trackingStartedOn: String?,
    val baselineDateIso: String?,
    val baselineCumulativeSteps: Long?,
    val baselineCapturedAtEpochMillis: Long?,
)

@Entity(
    tableName = "step_days",
    primaryKeys = ["historyId", "dateIso"],
    foreignKeys = [
        ForeignKey(
            entity = StepHistoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["historyId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("historyId")],
)
data class StepDayEntity(
    val historyId: Int,
    val dateIso: String,
    val zoneId: String,
    val state: String,
    val collectedAtEpochMillis: Long,
    val steps: Long?,
    val distanceMeters: Double?,
    val activeCaloriesKilocalories: Double?,
)

/**
 * A transaction marker written only after a legacy private JSON source has been decoded and
 * committed to Room. Retaining the source file makes a failed migration recoverable, while this
 * marker prevents a successful import from ever becoming a second runtime authority.
 */
@Entity(tableName = "legacy_statistics_migrations")
data class LegacyStatisticsMigrationEntity(
    @androidx.room.PrimaryKey val migrationId: String,
    val importedAtEpochMillis: Long,
)

/** Flattened rows let Room observe a complete history without an unsafe partial-key relation. */
data class UsageHistoryRoomRow(
    val ownerId: String,
    val trackingStartedOn: String?,
    val backfillCompletedThrough: String?,
    val dayDateIso: String?,
    val dayZoneId: String?,
    val dayState: String?,
    val dayCollectedAtEpochMillis: Long?,
    val packageName: String?,
    val foregroundMillis: Long?,
)

data class UsageDeviceHistoryRoomRow(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val updatedAtEpochMillis: Long,
    val trackingStartedOn: String?,
    val backfillCompletedThrough: String?,
    val dayDateIso: String?,
    val dayZoneId: String?,
    val dayState: String?,
    val dayCollectedAtEpochMillis: Long?,
    val packageName: String?,
    val foregroundMillis: Long?,
)

data class StepHistoryRoomRow(
    val historyId: Int,
    val trackingStartedOn: String?,
    val baselineDateIso: String?,
    val baselineCumulativeSteps: Long?,
    val baselineCapturedAtEpochMillis: Long?,
    val dayDateIso: String?,
    val dayZoneId: String?,
    val dayState: String?,
    val dayCollectedAtEpochMillis: Long?,
    val steps: Long?,
    val distanceMeters: Double?,
    val activeCaloriesKilocalories: Double?,
)
