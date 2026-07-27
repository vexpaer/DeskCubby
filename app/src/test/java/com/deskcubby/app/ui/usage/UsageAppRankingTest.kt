package com.deskcubby.app.ui.usage

import com.deskcubby.app.data.statistics.StatisticsDayState
import com.deskcubby.app.data.statistics.UsageAppDuration
import com.deskcubby.app.data.statistics.UsageStatisticsDay
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UsageAppRankingTest {
    @Test
    fun choicesAreRankedByDurationInSelectedRange() {
        val older = day(
            "2026-06-01",
            UsageAppDuration("app.old", 10_000),
            UsageAppDuration("app.recent", 1),
        )
        val recent = day(
            "2026-07-27",
            UsageAppDuration("app.old", 10),
            UsageAppDuration("app.recent", 500),
        )

        val result = rankUsagePackages(
            allDays = listOf(older, recent),
            rangedDays = listOf(recent),
            selectedPackage = null,
        )

        assertEquals(
            listOf(
                UsagePackageTotal("app.recent", 500),
                UsagePackageTotal("app.old", 10),
            ),
            result,
        )
    }

    @Test
    fun selectedPackageSurvivesSafetyLimitEvenWithNoRangeUsage() {
        val older = day(
            "2026-07-20",
            UsageAppDuration("app.selected", 10),
        )
        val ranged = day(
            "2026-07-27",
            UsageAppDuration("app.one", 30),
            UsageAppDuration("app.two", 20),
        )

        val result = rankUsagePackages(
            allDays = listOf(older, ranged),
            rangedDays = listOf(ranged),
            selectedPackage = "app.selected",
            maximumChoices = 2,
        )

        assertEquals(2, result.size)
        assertEquals(true, result.any { it.packageName == "app.selected" })
        assertEquals(true, result.any { it.packageName == "app.one" })
    }

    @Test
    fun appsWithoutPositiveUsageInRangeAreExcluded() {
        val older = day(
            "2026-07-20",
            UsageAppDuration("app.old", 100),
        )
        val ranged = day(
            "2026-07-27",
            UsageAppDuration("app.zero", 0),
            UsageAppDuration("app.recent", 20),
        )

        val result = rankUsagePackages(
            allDays = listOf(older, ranged),
            rangedDays = listOf(ranged),
            selectedPackage = null,
        )

        assertEquals(listOf(UsagePackageTotal("app.recent", 20)), result)
    }

    @Test
    fun packageFallbackIsReadableAndDoesNotExposeComPrefix() {
        val label = fallbackUsageAppLabel("com.example.my_music")

        assertEquals("My Music", label)
        assertFalse(label.startsWith("com", ignoreCase = true))
    }

    private fun day(
        date: String,
        vararg apps: UsageAppDuration,
    ) = UsageStatisticsDay(
        date = LocalDate.parse(date),
        zoneId = "UTC",
        state = StatisticsDayState.FINAL,
        collectedAtEpochMillis = 1,
        apps = apps.toList(),
    )
}
