package com.deskcubby.app.data.repository

import android.content.Context
import com.deskcubby.app.takeCodePoints
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

private val Context.poetryDataStore by preferencesDataStore(name = "daily_poetry_cache")

data class DailyPoem(
    val content: String,
    val source: String,
    val updatedAt: Long = 0L,
    /** All lines of the origin poem joined with newlines; empty when unknown. */
    val fullContent: String = "",
    val dynasty: String = "",
    val title: String = "",
)

enum class PoetryRefreshResult {
    UPDATED,
    ALREADY_CURRENT_FOR_DAY,
}

private enum class PoetryProvider { JINRISHICI, HITOKOTO, GUSHI_CI }

enum class PoemEditContentStatus {
    STORED_CONTENT,
    EXPANDED_FROM_DAILY_CACHE,
    LEGACY_CACHE_WITHOUT_FULL_CONTENT,
    DAILY_CACHE_UNAVAILABLE,
    CACHED_FULL_CONTENT_TOO_LONG,
}

data class PoemEditContentResolution(
    val content: String,
    val status: PoemEditContentStatus,
)

@Singleton
class PoetryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val presetCatalog: PoetryPresetCatalog,
) {
    private val refreshMutex = Mutex()
    private object Keys {
        val token = stringPreferencesKey("jinrishici_token")
        val content = stringPreferencesKey("content")
        val source = stringPreferencesKey("source")
        val updatedAt = longPreferencesKey("updated_at")
        val fullContent = stringPreferencesKey("full_content")
        val dynasty = stringPreferencesKey("dynasty")
        val title = stringPreferencesKey("title")
        val recentFingerprints = stringPreferencesKey("recent_fingerprints")
        val dailyFingerprintDate = stringPreferencesKey("daily_fingerprint_date")
        val dailyFingerprints = stringPreferencesKey("daily_fingerprints")
    }

    val poem: Flow<DailyPoem> = context.poetryDataStore.data.map { prefs ->
        DailyPoem(
            content = prefs[Keys.content] ?: FALLBACK.content,
            source = prefs[Keys.source] ?: FALLBACK.source,
            updatedAt = prefs[Keys.updatedAt] ?: 0L,
            // Caches written before full-poem support simply have no full content.
            fullContent = if (prefs[Keys.content] != null) {
                prefs[Keys.fullContent].orEmpty()
            } else {
                FALLBACK.fullContent
            },
            dynasty = prefs[Keys.dynasty].orEmpty(),
            title = prefs[Keys.title]
                ?: titleFromFormattedSource(prefs[Keys.source] ?: FALLBACK.source),
        )
    }

    suspend fun refresh(force: Boolean = false): PoetryRefreshResult = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val existing = context.poetryDataStore.data.first()
            val cachedAt = existing[Keys.updatedAt] ?: 0L
            val today = LocalDate.now()
            if (!force && cachedAt > 0 && dateOf(cachedAt) == today) {
                return@withContext PoetryRefreshResult.ALREADY_CURRENT_FOR_DAY
            }

            var token = existing[Keys.token]
            val recent = decodeRecentFingerprints(existing[Keys.recentFingerprints])
            val current = existing[Keys.content]?.let { content ->
                DailyPoem(
                    content = content,
                    source = existing[Keys.source].orEmpty(),
                    fullContent = existing[Keys.fullContent].orEmpty(),
                    dynasty = existing[Keys.dynasty].orEmpty(),
                    title = existing[Keys.title].orEmpty(),
                )
            } ?: FALLBACK
            val dailySeen = if (existing[Keys.dailyFingerprintDate] == today.toString()) {
                decodeRecentFingerprints(existing[Keys.dailyFingerprints])
            } else if (cachedAt > 0 && dateOf(cachedAt) == today) {
                // 0.4.1 had only a rolling recent list. Treat it conservatively as today's list
                // during the one-time upgrade so a poem already shown before upgrading does not
                // reappear later that day.
                recent
            } else {
                emptyList()
            }
            val blocked = (dailySeen + poemFingerprint(current))
                .filter(String::isNotBlank)
                .toHashSet()
            var selected: DailyPoem? = null
            val providers = rotatedProviders(
                startIndex = Math.floorMod(
                    today.toEpochDay() + dailySeen.size,
                    PoetryProvider.entries.size.toLong(),
                ).toInt(),
            )
            val attemptsPerProvider = if (force) MANUAL_ATTEMPTS_PER_PROVIDER else 1
            providerLoop@ for (provider in providers) {
                repeat(attemptsPerProvider) {
                    val candidate = try {
                        when (provider) {
                            PoetryProvider.JINRISHICI -> {
                                val (poem, refreshedToken) = fetchJinrishiciPoem(token)
                                token = refreshedToken
                                poem
                            }
                            PoetryProvider.HITOKOTO -> parseHitokoto(
                                request(HITOKOTO_URL, null),
                            )
                            PoetryProvider.GUSHI_CI -> parseGushiCi(
                                request(GUSHI_CI_URL, null),
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (candidate != null && poemFingerprint(candidate) !in blocked) {
                        selected = candidate
                        break@providerLoop
                    }
                }
            }
            val parsed = selected ?: runCatching {
                chooseOfflinePoem(
                    presets = presetCatalog.allPoems(),
                    current = current,
                    blockedFingerprints = blocked,
                    seed = today.toEpochDay() + dailySeen.size,
                )
            }.getOrNull() ?: throw IllegalStateException("No poetry source is available")
            val dailyAfterRefresh = encodeRecentFingerprints(
                (dailySeen + poemFingerprint(current) + poemFingerprint(parsed))
                    .filter(String::isNotBlank)
                    .distinct()
                    .takeLast(MAX_DAILY_POEMS),
            )
            val recentAfterRefresh = encodeRecentFingerprints(
                (listOf(poemFingerprint(parsed)) + recent + poemFingerprint(current))
                    .filter(String::isNotBlank)
                    .distinct()
                    .take(MAX_RECENT_POEMS),
            )
            context.poetryDataStore.edit { prefs ->
                token?.takeIf(String::isNotBlank)?.let { prefs[Keys.token] = it }
                prefs[Keys.content] = parsed.content
                prefs[Keys.source] = parsed.source
                prefs[Keys.fullContent] = parsed.fullContent
                prefs[Keys.dynasty] = parsed.dynasty
                prefs[Keys.title] = parsed.title
                prefs[Keys.recentFingerprints] = recentAfterRefresh
                prefs[Keys.dailyFingerprintDate] = today.toString()
                prefs[Keys.dailyFingerprints] = dailyAfterRefresh
                prefs[Keys.updatedAt] = System.currentTimeMillis()
            }
            PoetryRefreshResult.UPDATED
        }
    }

    /**
     * Expands a verse saved by an older daily-poetry cache only when the current cache proves it
     * belongs to the same source and sentence. A refresh is deliberately not attempted here:
     * `/sentence` returns a random poem, so using a fresh response could silently put another
     * poem's body into this saved row.
     */
    suspend fun resolveSavedContentForEdit(
        storedContent: String,
        storedSource: String,
    ): PoemEditContentResolution {
        val cached = try {
            poem.first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PoemEditContentResolution(
                content = storedContent,
                status = PoemEditContentStatus.DAILY_CACHE_UNAVAILABLE,
            )
        }
        return resolveSavedContentForEdit(storedContent, storedSource, cached)
    }

    private suspend fun fetchToken(): String {
        val response = JSONObject(request(TOKEN_URL, null))
        require(response.optString("status") == "success") { response.optString("errMessage", "Token request failed") }
        return response.getString("data")
    }

    private suspend fun fetchJinrishiciPoem(cachedToken: String?): Pair<DailyPoem, String> {
        var activeToken = cachedToken?.takeIf(String::isNotBlank)
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                if (activeToken.isNullOrBlank()) activeToken = fetchToken()
                val response = request(JINRISHICI_SENTENCE_URL, activeToken)
                val responseToken = JSONObject(response).optString("token")
                    .takeIf(String::isNotBlank)
                    ?: requireNotNull(activeToken)
                return parseSentence(response) to responseToken
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                if (attempt == 0) activeToken = null
            }
        }
        throw lastError ?: IllegalStateException("Poetry request failed")
    }

    private suspend fun request(url: String, token: String?): String {
        val endpoint = URL(url)
        require(endpoint.protocol == "https") { "Poetry endpoint must use HTTPS" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 8_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DeskCubby Android")
            token?.let { setRequestProperty("X-User-Token", it) }
        }
        return try {
            val status = connection.responseCode
            require(status in 200..299) { "Poetry service returned HTTP $status" }
            val declaredLength = connection.contentLengthLong
            require(declaredLength <= MAX_RESPONSE_BYTES || declaredLength < 0L) {
                "Poetry response is too large"
            }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(
                    declaredLength.coerceIn(0L, MAX_RESPONSE_BYTES.toLong()).toInt(),
                )
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_RESPONSE_BYTES) { "Poetry response is too large" }
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TOKEN_URL = "https://v2.jinrishici.com/token"
        private const val JINRISHICI_SENTENCE_URL = "https://v2.jinrishici.com/sentence"
        private const val HITOKOTO_URL = "https://v1.hitokoto.cn/?c=i&encode=json&max_length=64"
        private const val GUSHI_CI_URL = "https://api.gushi.ci/all.json"
        val FALLBACK = DailyPoem(
            content = "山中何事？松花酿酒，春水煎茶。",
            source = "— 张可久《人月圆·山中书事》",
            fullContent = "兴亡千古繁华梦，诗眼倦天涯。\n孔林乔木，吴宫蔓草，楚庙寒鸦。\n数间茅舍，藏书万卷，投老村家。\n山中何事？松花酿酒，春水煎茶。",
            dynasty = "元",
            title = "人月圆·山中书事",
        )

        internal fun parseSentence(raw: String): DailyPoem {
            val root = JSONObject(raw)
            require(root.optString("status") == "success") { root.optString("errMessage", "Poetry request failed") }
            val data = root.getJSONObject("data")
            val origin = data.optJSONObject("origin")
            val title = origin?.optString("title").orEmpty()
            val author = origin?.optString("author").orEmpty()
            val source = formatSource(title, author)
            val fullContent = origin?.optJSONArray("content")?.let { lines ->
                buildList {
                    for (index in 0 until lines.length()) {
                        (lines.opt(index) as? String)?.takeIf(String::isNotBlank)?.let(::add)
                    }
                }.joinToString("\n")
            }.orEmpty()
            return DailyPoem(
                content = data.getString("content"),
                source = source,
                fullContent = fullContent,
                dynasty = origin?.optString("dynasty").orEmpty(),
                title = title,
            )
        }

        internal fun parseHitokoto(raw: String): DailyPoem {
            val root = JSONObject(raw)
            val content = root.getString("hitokoto").trim()
            require(content.isNotEmpty() && content.codePointCount(0, content.length) <= MAX_SENTENCE_CODE_POINTS)
            val title = root.optString("from").trim().takeCodePoints(MAX_TITLE_CODE_POINTS)
            val author = root.optString("from_who").trim().takeCodePoints(MAX_AUTHOR_CODE_POINTS)
            return DailyPoem(
                content = content,
                source = if (title.isBlank() && author.isBlank()) {
                    "— 一言·诗词"
                } else {
                    formatSource(title, author)
                },
                title = title,
            )
        }

        internal fun parseGushiCi(raw: String): DailyPoem {
            val root = JSONObject(raw)
            val content = root.getString("content").trim()
            require(content.isNotEmpty() && content.codePointCount(0, content.length) <= MAX_SENTENCE_CODE_POINTS)
            val title = root.optString("origin").trim().takeCodePoints(MAX_TITLE_CODE_POINTS)
            val author = root.optString("author").trim().takeCodePoints(MAX_AUTHOR_CODE_POINTS)
            return DailyPoem(
                content = content,
                source = if (title.isBlank() && author.isBlank()) {
                    "— 古诗词·一言"
                } else {
                    formatSource(title, author)
                },
                title = title,
            )
        }

        private fun rotatedProviders(startIndex: Int): List<PoetryProvider> {
            val providers = PoetryProvider.entries
            if (providers.isEmpty()) return emptyList()
            val normalized = Math.floorMod(startIndex, providers.size)
            return List(providers.size) { offset -> providers[(normalized + offset) % providers.size] }
        }

        internal fun chooseOfflinePoem(
            presets: List<PoetryPresetPoem>,
            current: DailyPoem?,
            blockedFingerprints: Set<String>,
            seed: Long,
        ): DailyPoem? {
            if (presets.isEmpty()) return null
            val candidates = presets.map(::dailyPoemFromPreset)
            val blocked = blockedFingerprints.toMutableSet().apply {
                current?.let { add(poemFingerprint(it)) }
            }
            val start = Math.floorMod(seed, candidates.size.toLong()).toInt()
            return candidates.indices
                .asSequence()
                .map { offset -> candidates[(start + offset) % candidates.size] }
                .firstOrNull { poemFingerprint(it) !in blocked }
                ?: candidates[start]
        }

        private fun dailyPoemFromPreset(preset: PoetryPresetPoem): DailyPoem {
            val normalizedBody = preset.content.trim()
            val excerpt = normalizedBody.lineSequence()
                .map(String::trim)
                .firstOrNull(String::isNotBlank)
                .orEmpty()
                .takeCodePoints(MAX_SENTENCE_CODE_POINTS)
            val source = preset.source.trim()
            return DailyPoem(
                content = excerpt.ifBlank { normalizedBody.takeCodePoints(MAX_SENTENCE_CODE_POINTS) },
                source = source.takeIf { it.startsWith('—') } ?: "— $source",
                fullContent = normalizedBody,
                title = titleFromFormattedSource(source),
            )
        }

        internal fun formatSource(title: String, author: String): String = when {
                author.isNotBlank() && title.isNotBlank() -> "— $author《$title》"
                author.isNotBlank() -> "— $author"
                title.isNotBlank() -> "— 《$title》"
                else -> "— 今日诗词"
            }

        internal fun titleFromFormattedSource(source: String): String {
            val start = source.indexOf('《')
            val end = source.indexOf('》', startIndex = (start + 1).coerceAtLeast(0))
            return if (start >= 0 && end > start + 1) {
                source.substring(start + 1, end).trim()
            } else {
                ""
            }
        }

        /**
         * The sentence endpoint is random, but it can return the same sentence repeatedly. Keep
         * the selection bounded: prefer a candidate that is not the current or a recent poem, and
         * return null after a bounded number of attempts instead of redisplaying a blocked poem.
         */
        internal fun chooseFreshPoem(
            candidates: List<DailyPoem>,
            current: DailyPoem?,
            recentFingerprints: List<String>,
        ): DailyPoem? {
            if (candidates.isEmpty()) return null
            val blocked = recentFingerprints.toMutableSet().apply {
                current?.let { add(poemFingerprint(it)) }
            }
            return candidates.firstOrNull { poemFingerprint(it) !in blocked }
        }

        internal fun poemFingerprint(poem: DailyPoem): String =
            listOf(poem.content, poem.source, poem.title)
                .joinToString("\u0001") { it.trim().replace(POEM_WHITESPACE, "") }

        private fun decodeRecentFingerprints(raw: String?): List<String> =
            raw?.let {
                runCatching {
                    val array = JSONArray(it)
                    buildList {
                        for (index in 0 until array.length()) {
                            array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                        }
                    }
                }.getOrDefault(emptyList())
            } ?: emptyList()

        private fun encodeRecentFingerprints(values: List<String>): String =
            JSONArray().apply { values.forEach(::put) }.toString()

        internal fun resolveSavedContentForEdit(
            storedContent: String,
            storedSource: String,
            cached: DailyPoem,
        ): PoemEditContentResolution {
            val storedMatchText = normalizedPoemText(storedContent)
            val cachedSentence = normalizedPoemText(cached.content)
            val sameSource = normalizedPoemSource(storedSource) == normalizedPoemSource(cached.source)
            if (!sameSource || storedMatchText.isEmpty() || storedMatchText != cachedSentence) {
                return PoemEditContentResolution(
                    content = storedContent,
                    status = PoemEditContentStatus.STORED_CONTENT,
                )
            }

            val fullContent = cached.fullContent.trim()
            if (fullContent.isEmpty()) {
                return PoemEditContentResolution(
                    content = storedContent,
                    status = PoemEditContentStatus.LEGACY_CACHE_WITHOUT_FULL_CONTENT,
                )
            }
            if (fullContent.length > MAX_EDITABLE_POEM_CONTENT_CHARS) {
                return PoemEditContentResolution(
                    content = storedContent,
                    status = PoemEditContentStatus.CACHED_FULL_CONTENT_TOO_LONG,
                )
            }
            if (!normalizedPoemText(fullContent).contains(storedMatchText)) {
                // A corrupted or unrelated cache must never replace the saved body.
                return PoemEditContentResolution(
                    content = storedContent,
                    status = PoemEditContentStatus.STORED_CONTENT,
                )
            }
            if (normalizedPoemText(fullContent) == storedMatchText) {
                return PoemEditContentResolution(
                    content = storedContent,
                    status = PoemEditContentStatus.STORED_CONTENT,
                )
            }
            return PoemEditContentResolution(
                content = fullContent,
                status = PoemEditContentStatus.EXPANDED_FROM_DAILY_CACHE,
            )
        }

        private fun normalizedPoemText(value: String): String =
            value.replace(POEM_WHITESPACE, "")

        private fun normalizedPoemSource(value: String): String =
            value.trim().trimStart('-', '–', '—').trim()

        private fun dateOf(epochMillis: Long): LocalDate =
            Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

        private val POEM_WHITESPACE = Regex("\\s+")
        private const val MAX_EDITABLE_POEM_CONTENT_CHARS = 4_000
        private const val MANUAL_ATTEMPTS_PER_PROVIDER = 2
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val MAX_SENTENCE_CODE_POINTS = 160
        private const val MAX_TITLE_CODE_POINTS = 200
        private const val MAX_AUTHOR_CODE_POINTS = 100
        private const val MAX_RECENT_POEMS = 12
        private const val MAX_DAILY_POEMS = 512
    }
}
