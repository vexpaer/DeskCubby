package com.deskcubby.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
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

    suspend fun refresh(force: Boolean = false) = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val existing = context.poetryDataStore.data.first()
            val cachedAt = existing[Keys.updatedAt] ?: 0L
            if (!force && cachedAt > 0 && dateOf(cachedAt) == LocalDate.now()) return@withContext

            var token = existing[Keys.token] ?: fetchToken()
            val response = runCatching { request(SENTENCE_URL, token) }.getOrElse { firstError ->
                if (existing[Keys.token] == null) throw firstError
                token = fetchToken()
                request(SENTENCE_URL, token)
            }
            val parsed = parseSentence(response)
            val returnedToken = JSONObject(response).optString("token").takeIf(String::isNotBlank) ?: token
            context.poetryDataStore.edit { prefs ->
                prefs[Keys.token] = returnedToken
                prefs[Keys.content] = parsed.content
                prefs[Keys.source] = parsed.source
                prefs[Keys.fullContent] = parsed.fullContent
                prefs[Keys.dynasty] = parsed.dynasty
                prefs[Keys.title] = parsed.title
                prefs[Keys.updatedAt] = System.currentTimeMillis()
            }
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

    private fun fetchToken(): String {
        val response = JSONObject(request(TOKEN_URL, null))
        require(response.optString("status") == "success") { response.optString("errMessage", "Token request failed") }
        return response.getString("data")
    }

    private fun request(url: String, token: String?): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DeskCubby Android")
            token?.let { setRequestProperty("X-User-Token", it) }
        }
        return try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            require(connection.responseCode in 200..299) { "Poetry API ${connection.responseCode}: $body" }
            body
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TOKEN_URL = "https://v2.jinrishici.com/token"
        private const val SENTENCE_URL = "https://v2.jinrishici.com/sentence"
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
    }
}
