package com.deskcubby.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageStatisticsDao {
    @Query(
        """
        SELECT
            history.ownerId AS ownerId,
            history.trackingStartedOn AS trackingStartedOn,
            history.backfillCompletedThrough AS backfillCompletedThrough,
            day.dateIso AS dayDateIso,
            day.zoneId AS dayZoneId,
            day.state AS dayState,
            day.collectedAtEpochMillis AS dayCollectedAtEpochMillis,
            app.packageName AS packageName,
            app.foregroundMillis AS foregroundMillis
        FROM usage_histories AS history
        LEFT JOIN usage_days AS day ON day.ownerId = history.ownerId
        LEFT JOIN usage_app_durations AS app
            ON app.ownerId = day.ownerId AND app.dateIso = day.dateIso
        WHERE history.ownerId = :ownerId
        ORDER BY day.dateIso ASC, app.packageName ASC
        """,
    )
    fun observeHistoryRows(ownerId: String): Flow<List<UsageHistoryRoomRow>>

    @Query(
        """
        SELECT
            history.ownerId AS ownerId,
            history.trackingStartedOn AS trackingStartedOn,
            history.backfillCompletedThrough AS backfillCompletedThrough,
            day.dateIso AS dayDateIso,
            day.zoneId AS dayZoneId,
            day.state AS dayState,
            day.collectedAtEpochMillis AS dayCollectedAtEpochMillis,
            app.packageName AS packageName,
            app.foregroundMillis AS foregroundMillis
        FROM usage_histories AS history
        LEFT JOIN usage_days AS day ON day.ownerId = history.ownerId
        LEFT JOIN usage_app_durations AS app
            ON app.ownerId = day.ownerId AND app.dateIso = day.dateIso
        WHERE history.ownerId = :ownerId
        ORDER BY day.dateIso ASC, app.packageName ASC
        """,
    )
    suspend fun getHistoryRows(ownerId: String): List<UsageHistoryRoomRow>

    @Query(
        """
        SELECT
            device.deviceId AS deviceId,
            device.deviceName AS deviceName,
            device.platform AS platform,
            device.updatedAtEpochMillis AS updatedAtEpochMillis,
            history.trackingStartedOn AS trackingStartedOn,
            history.backfillCompletedThrough AS backfillCompletedThrough,
            day.dateIso AS dayDateIso,
            day.zoneId AS dayZoneId,
            day.state AS dayState,
            day.collectedAtEpochMillis AS dayCollectedAtEpochMillis,
            app.packageName AS packageName,
            app.foregroundMillis AS foregroundMillis
        FROM usage_devices AS device
        INNER JOIN usage_histories AS history ON history.ownerId = device.deviceId
        LEFT JOIN usage_days AS day ON day.ownerId = history.ownerId
        LEFT JOIN usage_app_durations AS app
            ON app.ownerId = day.ownerId AND app.dateIso = day.dateIso
        ORDER BY device.deviceId ASC, day.dateIso ASC, app.packageName ASC
        """,
    )
    fun observeForeignHistoryRows(): Flow<List<UsageDeviceHistoryRoomRow>>

    @Query(
        """
        SELECT
            device.deviceId AS deviceId,
            device.deviceName AS deviceName,
            device.platform AS platform,
            device.updatedAtEpochMillis AS updatedAtEpochMillis,
            history.trackingStartedOn AS trackingStartedOn,
            history.backfillCompletedThrough AS backfillCompletedThrough,
            day.dateIso AS dayDateIso,
            day.zoneId AS dayZoneId,
            day.state AS dayState,
            day.collectedAtEpochMillis AS dayCollectedAtEpochMillis,
            app.packageName AS packageName,
            app.foregroundMillis AS foregroundMillis
        FROM usage_devices AS device
        INNER JOIN usage_histories AS history ON history.ownerId = device.deviceId
        LEFT JOIN usage_days AS day ON day.ownerId = history.ownerId
        LEFT JOIN usage_app_durations AS app
            ON app.ownerId = day.ownerId AND app.dateIso = day.dateIso
        WHERE device.deviceId = :deviceId
        ORDER BY day.dateIso ASC, app.packageName ASC
        """,
    )
    suspend fun getForeignHistoryRows(deviceId: String): List<UsageDeviceHistoryRoomRow>

    @Query(
        """
        SELECT
            device.deviceId AS deviceId,
            device.deviceName AS deviceName,
            device.platform AS platform,
            device.updatedAtEpochMillis AS updatedAtEpochMillis,
            history.trackingStartedOn AS trackingStartedOn,
            history.backfillCompletedThrough AS backfillCompletedThrough,
            day.dateIso AS dayDateIso,
            day.zoneId AS dayZoneId,
            day.state AS dayState,
            day.collectedAtEpochMillis AS dayCollectedAtEpochMillis,
            app.packageName AS packageName,
            app.foregroundMillis AS foregroundMillis
        FROM usage_devices AS device
        INNER JOIN usage_histories AS history ON history.ownerId = device.deviceId
        LEFT JOIN usage_days AS day ON day.ownerId = history.ownerId
        LEFT JOIN usage_app_durations AS app
            ON app.ownerId = day.ownerId AND app.dateIso = day.dateIso
        ORDER BY device.deviceId ASC, day.dateIso ASC, app.packageName ASC
        """,
    )
    suspend fun getAllForeignHistoryRows(): List<UsageDeviceHistoryRoomRow>

    @Query("SELECT COUNT(*) FROM usage_devices")
    suspend fun foreignDeviceCount(): Int

    @Upsert
    suspend fun upsertHistory(history: UsageHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDays(days: List<UsageDayEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppDurations(apps: List<UsageAppDurationEntity>)

    @Upsert
    suspend fun upsertDevice(device: UsageDeviceEntity)

    @Query("DELETE FROM usage_days WHERE ownerId = :ownerId")
    suspend fun deleteDays(ownerId: String)

    @Query("DELETE FROM usage_histories WHERE ownerId = :ownerId")
    suspend fun deleteHistory(ownerId: String)

    @Query("DELETE FROM usage_histories WHERE ownerId != :localOwnerId")
    suspend fun deleteAllForeignHistories(localOwnerId: String)
}

@Dao
interface StepStatisticsDao {
    @Query(
        """
        SELECT
            history.id AS historyId,
            history.trackingStartedOn AS trackingStartedOn,
            history.baselineDateIso AS baselineDateIso,
            history.baselineCumulativeSteps AS baselineCumulativeSteps,
            history.baselineCapturedAtEpochMillis AS baselineCapturedAtEpochMillis,
            day.dateIso AS dayDateIso,
            day.zoneId AS dayZoneId,
            day.state AS dayState,
            day.collectedAtEpochMillis AS dayCollectedAtEpochMillis,
            day.steps AS steps,
            day.distanceMeters AS distanceMeters,
            day.activeCaloriesKilocalories AS activeCaloriesKilocalories
        FROM step_history AS history
        LEFT JOIN step_days AS day ON day.historyId = history.id
        WHERE history.id = :historyId
        ORDER BY day.dateIso ASC
        """,
    )
    fun observeHistoryRows(historyId: Int): Flow<List<StepHistoryRoomRow>>

    @Query(
        """
        SELECT
            history.id AS historyId,
            history.trackingStartedOn AS trackingStartedOn,
            history.baselineDateIso AS baselineDateIso,
            history.baselineCumulativeSteps AS baselineCumulativeSteps,
            history.baselineCapturedAtEpochMillis AS baselineCapturedAtEpochMillis,
            day.dateIso AS dayDateIso,
            day.zoneId AS dayZoneId,
            day.state AS dayState,
            day.collectedAtEpochMillis AS dayCollectedAtEpochMillis,
            day.steps AS steps,
            day.distanceMeters AS distanceMeters,
            day.activeCaloriesKilocalories AS activeCaloriesKilocalories
        FROM step_history AS history
        LEFT JOIN step_days AS day ON day.historyId = history.id
        WHERE history.id = :historyId
        ORDER BY day.dateIso ASC
        """,
    )
    suspend fun getHistoryRows(historyId: Int): List<StepHistoryRoomRow>

    @Upsert
    suspend fun upsertHistory(history: StepHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDays(days: List<StepDayEntity>)

    @Query("DELETE FROM step_days WHERE historyId = :historyId")
    suspend fun deleteDays(historyId: Int)
}

@Dao
interface LegacyStatisticsMigrationDao {
    @Query(
        "SELECT EXISTS(SELECT 1 FROM legacy_statistics_migrations WHERE migrationId = :migrationId)",
    )
    suspend fun isComplete(migrationId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markComplete(marker: LegacyStatisticsMigrationEntity)
}
