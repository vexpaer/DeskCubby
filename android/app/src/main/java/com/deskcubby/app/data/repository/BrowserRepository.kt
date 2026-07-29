package com.deskcubby.app.data.repository

import com.deskcubby.app.data.local.BrowserRecordDao
import com.deskcubby.app.data.local.BrowserRecordEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrowserRepository @Inject constructor(
    private val dao: BrowserRecordDao,
) {
    val history = dao.observeHistory()
    val favorites = dao.observeFavorites()

    suspend fun recordVisit(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        val existing = dao.get(url)
        dao.upsert(
            BrowserRecordEntity(
                url = url,
                title = title.ifBlank { url },
                lastVisitedAt = System.currentTimeMillis(),
                visitCount = (existing?.visitCount ?: 0) + 1,
                favorite = existing?.favorite ?: false,
            ),
        )
    }

    suspend fun setFavorite(url: String, title: String, favorite: Boolean) {
        val existing = dao.get(url)
        dao.upsert(
            BrowserRecordEntity(
                url = url,
                title = title.ifBlank { existing?.title ?: url },
                lastVisitedAt = existing?.lastVisitedAt ?: System.currentTimeMillis(),
                visitCount = existing?.visitCount ?: 1,
                favorite = favorite,
            ),
        )
    }

    suspend fun clearHistory() = dao.clearNonFavorites()
}
