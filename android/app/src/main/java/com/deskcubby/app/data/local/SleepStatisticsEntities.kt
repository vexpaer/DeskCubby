package com.deskcubby.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sleep_devices")
data class SleepDeviceEntity(
    @androidx.room.PrimaryKey val deviceId: String,
    val deviceName: String,
    val platform: String,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "sleep_nights",
    primaryKeys = ["deviceId", "wakeDateIso"],
    foreignKeys = [
        ForeignKey(
            entity = SleepDeviceEntity::class,
            parentColumns = ["deviceId"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("deviceId")],
)
data class SleepNightEntity(
    val deviceId: String,
    val wakeDateIso: String,
    val zoneId: String,
    val bedtimeEpochMillis: Long,
    val wakeEpochMillis: Long,
    val durationMinutes: Int,
    val source: String,
    val updatedAtEpochMillis: Long,
)

@Dao
interface SleepStatisticsDao {
    @Query("SELECT * FROM sleep_devices ORDER BY deviceName COLLATE NOCASE, deviceId")
    fun observeDevices(): Flow<List<SleepDeviceEntity>>

    @Query("SELECT * FROM sleep_nights ORDER BY wakeDateIso ASC")
    fun observeNights(): Flow<List<SleepNightEntity>>

    @Query("SELECT * FROM sleep_devices ORDER BY deviceName COLLATE NOCASE, deviceId")
    suspend fun getDevices(): List<SleepDeviceEntity>

    @Query("SELECT * FROM sleep_devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getDevice(deviceId: String): SleepDeviceEntity?

    @Query("SELECT * FROM sleep_nights ORDER BY wakeDateIso ASC")
    suspend fun getNights(): List<SleepNightEntity>

    @Query("SELECT * FROM sleep_nights WHERE deviceId = :deviceId AND wakeDateIso = :wakeDateIso LIMIT 1")
    suspend fun getNight(deviceId: String, wakeDateIso: String): SleepNightEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(entity: SleepDeviceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNight(entity: SleepNightEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNights(entities: List<SleepNightEntity>)
}
