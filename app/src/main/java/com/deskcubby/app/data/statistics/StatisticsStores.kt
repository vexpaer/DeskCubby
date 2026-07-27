package com.deskcubby.app.data.statistics

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class UsageStatisticsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = AtomicStatisticsStore(
        file = prepareStatisticsFile(
            filesDir = context.filesDir,
            fileName = USAGE_STATISTICS_FILE_NAME,
            validator = { bytes ->
                UsageStatisticsJsonCodec.decode(bytes.toString(StandardCharsets.UTF_8))
            },
        ),
        emptyValue = UsageStatisticsHistory(),
        encode = UsageStatisticsJsonCodec::encode,
        decode = UsageStatisticsJsonCodec::decode,
    )

    val history: StateFlow<UsageStatisticsHistory> = store.value

    suspend fun update(
        transform: (UsageStatisticsHistory) -> UsageStatisticsHistory,
    ): UsageStatisticsHistory = store.update(transform)

    suspend fun reload(): UsageStatisticsHistory = store.reload()
}

@Singleton
class StepStatisticsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = AtomicStatisticsStore(
        file = prepareStatisticsFile(
            filesDir = context.filesDir,
            fileName = STEP_STATISTICS_FILE_NAME,
            validator = { bytes ->
                StepStatisticsJsonCodec.decode(bytes.toString(StandardCharsets.UTF_8))
            },
        ),
        emptyValue = StepStatisticsHistory(),
        encode = StepStatisticsJsonCodec::encode,
        decode = StepStatisticsJsonCodec::decode,
    )

    val history: StateFlow<StepStatisticsHistory> = store.value

    suspend fun update(
        transform: (StepStatisticsHistory) -> StepStatisticsHistory,
    ): StepStatisticsHistory = store.update(transform)

    suspend fun reload(): StepStatisticsHistory = store.reload()
}

private class AtomicStatisticsStore<T>(
    file: File,
    private val emptyValue: T,
    private val encode: (T) -> String,
    private val decode: (String) -> T,
) {
    private val atomicFile = AtomicFile(file)
    private val mutex = Mutex()
    // A malformed file is never treated as valid or overwritten. Keep the
    // process alive with an empty presentation value; the next explicit
    // update/reload re-reads the file, fails, and surfaces through the feature
    // collection state while the original bytes remain recoverable.
    private val mutableValue = MutableStateFlow(
        runCatching(::readOrEmpty).getOrDefault(emptyValue),
    )

    val value: StateFlow<T> = mutableValue.asStateFlow()

    suspend fun update(transform: (T) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            // Re-read under the same process mutex so an external restore or a
            // process restart can never be silently overwritten by stale state.
            val current = readOrEmpty()
            val next = transform(current)
            val encoded = encode(next)
            if (encoded.toByteArray(StandardCharsets.UTF_8).size > MAX_STATISTICS_JSON_BYTES) {
                throw StatisticsJsonException(
                    "Statistics JSON exceeds $MAX_STATISTICS_JSON_BYTES bytes.",
                )
            }
            writeAtomically(encoded)
            val verified = readRequired()
            if (verified != next) {
                throw StatisticsJsonException("Statistics file verification failed.")
            }
            mutableValue.value = verified
            verified
        }
    }

    suspend fun reload(): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            readOrEmpty().also { mutableValue.value = it }
        }
    }

    private fun readOrEmpty(): T = try {
        readRequired()
    } catch (_: FileNotFoundException) {
        emptyValue
    }

    private fun readRequired(): T {
        val bytes = atomicFile.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1_024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_STATISTICS_JSON_BYTES) {
                    throw StatisticsJsonException(
                        "Statistics JSON exceeds $MAX_STATISTICS_JSON_BYTES bytes.",
                    )
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        return decode(bytes.toString(StandardCharsets.UTF_8))
    }

    private fun writeAtomically(json: String) {
        var output = atomicFile.startWrite()
        try {
            output.write(json.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            atomicFile.failWrite(output)
            throw error
        }
    }
}

const val USAGE_STATISTICS_FILE_NAME = "usage-statistics.json"
const val STEP_STATISTICS_FILE_NAME = "step-statistics.json"
