package com.deskcubby.app.data.statistics

import com.deskcubby.app.data.local.AgentDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AgentTokenStatistics(
    val runCount: Long = 0,
    val modelCallCount: Long = 0,
    val reportedCallCount: Long = 0,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val cacheRateInputTokens: Long? = null,
    val reasoningTokens: Long? = null,
) {
    val unreportedCallCount: Long
        get() = (modelCallCount - reportedCallCount).coerceAtLeast(0)

    val cacheRate: Double?
        get() = cacheRateInputTokens?.takeIf { it > 0 }?.let { input ->
            cachedInputTokens?.toDouble()?.div(input)
        }
}

@Singleton
class AgentTokenStatisticsRepository @Inject constructor(
    dao: AgentDao,
) {
    val statistics: Flow<AgentTokenStatistics> = dao.observeUsageAggregate().map { value ->
        AgentTokenStatistics(
            runCount = value.runCount.coerceAtLeast(0),
            modelCallCount = value.modelCallCount.coerceAtLeast(0),
            reportedCallCount = value.usageReportedCallCount.coerceAtLeast(0),
            inputTokens = value.inputTokens?.coerceAtLeast(0),
            outputTokens = value.outputTokens?.coerceAtLeast(0),
            totalTokens = value.totalTokens?.coerceAtLeast(0),
            cachedInputTokens = value.cachedInputTokens?.coerceAtLeast(0),
            cacheRateInputTokens = value.cacheRateInputTokens?.coerceAtLeast(0),
            reasoningTokens = value.reasoningTokens?.coerceAtLeast(0),
        )
    }
}
