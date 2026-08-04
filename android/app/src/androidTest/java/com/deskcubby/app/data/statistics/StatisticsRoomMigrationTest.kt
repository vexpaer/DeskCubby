package com.deskcubby.app.data.statistics

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deskcubby.app.data.local.AppDatabase
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatisticsRoomMigrationTest {
    @Test
    fun legacyUsageAndStepFilesImportOnceAndRoomRemainsAuthoritative() = runBlocking {
        withFixture("local") { context, database ->
            val usage = usageHistory("2026-08-01", "example.one", 12_000)
            val steps = stepHistory("2026-08-01", 8_000)
            File(context.filesDir, USAGE_STATISTICS_FILE_NAME)
                .writeText(UsageStatisticsJsonCodec.encode(usage))
            File(context.filesDir, STEP_STATISTICS_FILE_NAME)
                .writeText(StepStatisticsJsonCodec.encode(steps))

            val usageStore = usageStore(context, database)
            val stepStore = stepStore(context, database)

            assertEquals(usage, usageStore.current())
            assertEquals(steps, stepStore.current())
            assertTrue(
                database.legacyStatisticsMigrationDao()
                    .isComplete(LEGACY_USAGE_FILE_MIGRATION_ID),
            )
            assertTrue(
                database.legacyStatisticsMigrationDao()
                    .isComplete(LEGACY_STEP_FILE_MIGRATION_ID),
            )

            val retainedUsageFile = File(
                context.filesDir,
                "$STATISTICS_DIRECTORY_NAME/$USAGE_STATISTICS_FILE_NAME",
            )
            val changedLegacy = usageHistory("2026-08-02", "example.changed", 99_000)
            retainedUsageFile.writeText(UsageStatisticsJsonCodec.encode(changedLegacy))

            val restartedStore = usageStore(context, database)
            assertEquals(usage, restartedStore.current())
            assertTrue(retainedUsageFile.isFile)
            restartedStore.cancelForTest()
            stepStore.cancelForTest()
            usageStore.cancelForTest()
        }
    }

    @Test
    fun malformedLegacyFileIsRetainedWithoutBlockingNewRoomWrites() = runBlocking {
        withFixture("malformed") { context, database ->
            val malformed = "{not valid usage statistics"
            File(context.filesDir, USAGE_STATISTICS_FILE_NAME).writeText(malformed)
            val store = usageStore(context, database)

            assertEquals(UsageStatisticsHistory(), store.current())
            assertFalse(
                database.legacyStatisticsMigrationDao()
                    .isComplete(LEGACY_USAGE_FILE_MIGRATION_ID),
            )
            val retained = File(
                context.filesDir,
                "$STATISTICS_DIRECTORY_NAME/$USAGE_STATISTICS_FILE_NAME",
            )
            assertEquals(malformed, retained.readText())

            val collected = usageHistory("2026-08-03", "example.new", 5_000)
            assertEquals(collected, store.update { collected })
            assertEquals(collected, store.current())
            assertEquals(malformed, retained.readText())
            store.cancelForTest()
        }
    }

    @Test
    fun malformedForeignCacheDoesNotHideValidDeviceMigrations() = runBlocking {
        withFixture("foreign") { context, database ->
            val cache = File(context.filesDir, "usage-device-histories").apply {
                assertTrue(mkdirs())
            }
            val goodRecords = (1..63).map { index ->
                usageDeviceRecord(
                    id = "10000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
                    name = "Device $index",
                    date = "2026-08-01",
                )
            }
            goodRecords.forEach { record ->
                File(cache, "${record.deviceId}.json")
                    .writeText(UsageDeviceJsonCodec.encode(record))
            }
            // This malformed file sorts first. The 63rd valid foreign record is therefore the
            // 64th candidate and must still be migrated.
            val badId = "00000000-0000-0000-0000-000000000001"
            val badFile = File(cache, "$badId.json").apply { writeText("{broken") }
            val usageStore = usageStore(context, database)
            val repository = UsageDeviceRepository(
                context = context,
                usageStore = usageStore,
                database = database,
                dao = database.usageStatisticsDao(),
                migrationDao = database.legacyStatisticsMigrationDao(),
            )

            val snapshots = repository.snapshotAll()

            assertTrue(goodRecords.all { good -> snapshots.any { it.deviceId == good.deviceId } })
            assertEquals(MAX_USAGE_DEVICES, snapshots.size)
            assertTrue(badFile.isFile)
            assertFalse(
                database.legacyStatisticsMigrationDao()
                    .isComplete(LEGACY_USAGE_DEVICE_CACHE_MIGRATION_ID),
            )
            goodRecords.forEach { record ->
                assertTrue(
                    database.legacyStatisticsMigrationDao().isComplete(
                        "$LEGACY_USAGE_DEVICE_CACHE_FILE_MIGRATION_PREFIX${record.deviceId}.json",
                    ),
                )
            }
            repository.cancelForTest()
            usageStore.cancelForTest()
        }
    }

    private fun usageStore(context: Context, database: AppDatabase) = UsageStatisticsStore(
        context = context,
        database = database,
        dao = database.usageStatisticsDao(),
        migrationDao = database.legacyStatisticsMigrationDao(),
    )

    private fun stepStore(context: Context, database: AppDatabase) = StepStatisticsStore(
        context = context,
        database = database,
        dao = database.stepStatisticsDao(),
        migrationDao = database.legacyStatisticsMigrationDao(),
    )

    private suspend fun withFixture(
        suffix: String,
        block: suspend (Context, AppDatabase) -> Unit,
    ) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "statistics-room-$suffix-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        val context = FilesDirContext(base, root)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            block(context, database)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    private fun usageHistory(
        date: String,
        packageName: String,
        foregroundMillis: Long,
    ): UsageStatisticsHistory {
        val parsed = LocalDate.parse(date)
        return UsageStatisticsHistory(
            trackingStartedOn = parsed,
            days = listOf(
                UsageStatisticsDay(
                    date = parsed,
                    zoneId = "Asia/Shanghai",
                    state = StatisticsDayState.FINAL,
                    collectedAtEpochMillis = 100,
                    apps = listOf(UsageAppDuration(packageName, foregroundMillis)),
                ),
            ),
            backfillCompletedThrough = parsed,
        )
    }

    private fun stepHistory(date: String, steps: Long): StepStatisticsHistory {
        val parsed = LocalDate.parse(date)
        return StepStatisticsHistory(
            trackingStartedOn = parsed,
            days = listOf(
                StepStatisticsDay(
                    date = parsed,
                    zoneId = "Asia/Shanghai",
                    state = StatisticsDayState.FINAL,
                    collectedAtEpochMillis = 100,
                    steps = steps,
                    distanceMeters = 5_000.0,
                    activeCaloriesKilocalories = 300.0,
                ),
            ),
        )
    }

    private fun usageDeviceRecord(
        id: String,
        name: String,
        date: String,
    ): UsageDeviceRecord = UsageDeviceRecord(
        deviceId = id,
        deviceName = name,
        platform = USAGE_DEVICE_PLATFORM_ANDROID,
        updatedAtEpochMillis = 100,
        history = usageHistory(date, "example.$name".replace(' ', '_').lowercase(), 10_000),
    )

    private class FilesDirContext(
        base: Context,
        private val privateFilesDir: File,
    ) : ContextWrapper(base) {
        override fun getFilesDir(): File = privateFilesDir
        override fun getApplicationContext(): Context = this
    }
}
