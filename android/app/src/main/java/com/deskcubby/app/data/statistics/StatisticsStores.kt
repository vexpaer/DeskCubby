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

    /** Re-reads and canonically encodes the private source under the writer mutex. */
    suspend fun canonicalSnapshot(): UsageStatisticsSnapshot {
        val (encoded, verified) = store.reloadAndEncode(::canonicalUsageStatisticsHistory)
        return UsageStatisticsSnapshot(
            bytes = encoded.toByteArray(StandardCharsets.UTF_8),
            history = verified,
        )
    }
}

data class UsageStatisticsSnapshot internal constructor(
    val bytes: ByteArray,
    val history: UsageStatisticsHistory,
)

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
            // Verify the exact bytes before committing them. Once AtomicFile.finishWrite()
            // succeeds, the refresh has durably succeeded and must not be reported as a
            // failure merely because an additional post-commit read happens to fail.
            val (encoded, verified) = encodeAndVerifyStatisticsValue(
                value = next,
                encode = encode,
                decode = decode,
            )
            writeAtomically(encoded)
            mutableValue.value = verified
            verified
        }
    }

    suspend fun reload(): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            readOrEmpty().also { mutableValue.value = it }
        }
    }

    suspend fun reloadAndEncode(
        canonicalize: (T) -> T = { it },
    ): Pair<String, T> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = readOrEmpty()
            val canonical = canonicalize(current)
            encodeAndVerifyStatisticsValue(
                value = canonical,
                encode = encode,
                decode = decode,
            ).also { (_, verified) ->
                mutableValue.value = verified
            }
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

internal fun <T> encodeAndVerifyStatisticsValue(
    value: T,
    encode: (T) -> String,
    decode: (String) -> T,
    maximumBytes: Int = MAX_STATISTICS_JSON_BYTES,
): Pair<String, T> {
    require(maximumBytes > 0)
    val encoded = encode(value)
    if (encoded.toByteArray(StandardCharsets.UTF_8).size > maximumBytes) {
        throw StatisticsJsonException("Statistics JSON exceeds $maximumBytes bytes.")
    }
    val decoded = decode(encoded)
    if (decoded != value) {
        throw StatisticsJsonException("Statistics serialization verification failed.")
    }
    return encoded to decoded
}

internal fun canonicalUsageStatisticsHistory(
    history: UsageStatisticsHistory,
): UsageStatisticsHistory = history.copy(
    days = history.days
        .sortedBy(UsageStatisticsDay::date)
        .map { day ->
            day.copy(apps = day.apps.sortedBy(UsageAppDuration::packageName))
        },
)

internal fun <T> verifyStatisticsExportReadBack(
    expectedBytes: ByteArray,
    actualBytes: ByteArray,
    expectedValue: T,
    decode: (String) -> T,
    maximumBytes: Int = MAX_STATISTICS_JSON_BYTES,
): T {
    require(maximumBytes > 0)
    if (expectedBytes.size > maximumBytes || actualBytes.size > maximumBytes) {
        throw StatisticsJsonException("Statistics JSON exceeds $maximumBytes bytes.")
    }
    if (!actualBytes.contentEquals(expectedBytes)) {
        throw StatisticsJsonException("Statistics export read-back did not match the written bytes.")
    }
    val decoded = decode(actualBytes.toString(StandardCharsets.UTF_8))
    if (decoded != expectedValue) {
        throw StatisticsJsonException("Statistics export read-back verification failed.")
    }
    return decoded
}
