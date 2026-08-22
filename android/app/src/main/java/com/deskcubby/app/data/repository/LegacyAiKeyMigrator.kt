package com.deskcubby.app.data.repository

import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.preferences.SettingsRepository
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One small application-level migration for AI credentials created by older DeskCubby versions.
 * Background AI must not depend on SettingsViewModel being constructed before its Worker starts.
 */
@Singleton
class LegacyAiKeyMigrator @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val legacyStore: LegacyAiKeyMigrationStore,
) {
    private val migrationMutex = Mutex()

    suspend fun migrateIfNeeded(): AppSettings = migrationMutex.withLock {
        val current = settingsRepository.settings.first()
        try {
            var allStoredKeysReadable = true
            val migrated = withContext(Dispatchers.IO) {
                current.aiConfigs.map { config ->
                    if (config.apiKey.isNotEmpty()) return@map config
                    if (!legacyStore.containsApiKey(config.id)) return@map config
                    val endpoint = runCatching { URL(config.endpointUrl) }.getOrNull() ?: run {
                        allStoredKeysReadable = false
                        return@map config
                    }
                    val key = legacyStore.readApiKey(config.id, endpoint)?.take(MAX_API_KEY_CHARS).orEmpty()
                    if (key.isEmpty()) {
                        allStoredKeysReadable = false
                        config
                    } else {
                        config.copy(apiKey = key)
                    }
                }
            }
            if (migrated != current.aiConfigs) settingsRepository.setAiConfigs(migrated)
            if (allStoredKeysReadable) {
                withContext(Dispatchers.IO) { legacyStore.discardLegacyStore() }
            }
            current.copy(aiConfigs = migrated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A transient DataStore/KeyStore failure must not delete the legacy source. Re-read in
            // case the DataStore write succeeded before cleanup failed, then retry next time.
            try {
                settingsRepository.settings.first()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                current
            }
        }
    }

    private companion object {
        const val MAX_API_KEY_CHARS = 8_192
    }
}
