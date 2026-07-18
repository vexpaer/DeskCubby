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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val Context.poetryDataStore by preferencesDataStore(name = "daily_poetry_cache")

data class DailyPoem(
    val content: String,
    val source: String,
    val updatedAt: Long = 0L,
)

@Singleton
class PoetryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val token = stringPreferencesKey("jinrishici_token")
        val content = stringPreferencesKey("content")
        val source = stringPreferencesKey("source")
        val updatedAt = longPreferencesKey("updated_at")
    }

    val poem: Flow<DailyPoem> = context.poetryDataStore.data.map { prefs ->
        DailyPoem(
            content = prefs[Keys.content] ?: FALLBACK.content,
            source = prefs[Keys.source] ?: FALLBACK.source,
            updatedAt = prefs[Keys.updatedAt] ?: 0L,
        )
    }

    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
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
            prefs[Keys.updatedAt] = System.currentTimeMillis()
        }
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
        val FALLBACK = DailyPoem("山中何事？松花酿酒，春水煎茶。", "— 张可久《人月圆·山中书事》")

        internal fun parseSentence(raw: String): DailyPoem {
            val root = JSONObject(raw)
            require(root.optString("status") == "success") { root.optString("errMessage", "Poetry request failed") }
            val data = root.getJSONObject("data")
            val origin = data.optJSONObject("origin")
            val title = origin?.optString("title").orEmpty()
            val author = origin?.optString("author").orEmpty()
            val source = formatSource(title, author)
            return DailyPoem(content = data.getString("content"), source = source)
        }

        internal fun formatSource(title: String, author: String): String = when {
                author.isNotBlank() && title.isNotBlank() -> "— $author《$title》"
                author.isNotBlank() -> "— $author"
                title.isNotBlank() -> "— 《$title》"
                else -> "— 今日诗词"
            }

        private fun dateOf(epochMillis: Long): LocalDate =
            Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    }
}
