package com.deskcubby.plugin.api.core.api

/** Owner-scoped, app-private storage. Implementations must never share keys between plugins. */
interface StorageAPI {
    suspend fun get(key: String): String?

    suspend fun put(key: String, value: String)

    suspend fun remove(key: String): Boolean

    suspend fun keys(): Set<String>

    suspend fun clear()
}
