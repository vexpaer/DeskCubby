package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.ReaderBackground
import com.deskcubby.app.data.repository.ReaderBookType
import com.deskcubby.app.data.repository.ReaderChapterDetectionMode
import com.deskcubby.app.data.repository.ReaderPreferences
import com.deskcubby.app.data.repository.ReaderRepository
import com.deskcubby.app.data.repository.VaultEncryptedBackup
import com.deskcubby.app.data.repository.VaultEncryptedKeyBackup
import com.deskcubby.app.data.repository.VaultRepository
import com.deskcubby.app.data.statistics.UsageDeviceJsonCodec
import com.deskcubby.app.data.statistics.UsageDeviceRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class RepositoryRecordSyncAdapters @Inject constructor(
    usageDeviceRepository: UsageDeviceRepository,
    readerRepository: ReaderRepository,
    agentChatSyncRepository: AgentChatSyncRepository,
    vaultRepository: VaultRepository,
    settingsRepository: SettingsRepository,
) {
    private val usageStatistics = UsageRecordSyncAdapter(usageDeviceRepository)
    private val readerProgress = ReaderProgressRecordSyncAdapter(readerRepository)
    private val agentChats = AgentChatRecordSyncAdapter(agentChatSyncRepository)
    private val vault = VaultRecordSyncAdapter(vaultRepository)
    private val rssSubscriptions = RssSubscriptionRecordSyncAdapter(settingsRepository)
    private val globalSettings = GlobalSettingsRecordSyncAdapter(settingsRepository)
    private val readerPreferences = ReaderPreferencesRecordSyncAdapter(readerRepository)

    fun all(): Map<CloudSyncContent, RecordSyncAdapter> = linkedMapOf(
        CloudSyncContent.USAGE_STATISTICS to usageStatistics,
        CloudSyncContent.READING_PROGRESS to readerProgress,
        CloudSyncContent.AGENT_CHATS to agentChats,
        CloudSyncContent.VAULT to vault,
        CloudSyncContent.RSS_SUBSCRIPTIONS to rssSubscriptions,
        CloudSyncContent.GLOBAL_SETTINGS to globalSettings,
        CloudSyncContent.READER_PREFERENCES to readerPreferences,
    )
}

private class UsageRecordSyncAdapter(
    private val repository: UsageDeviceRepository,
) : RecordSyncAdapter {
    override val contentType = CloudSyncContent.USAGE_STATISTICS
    override val conflictPolicy = RecordConflictPolicy.LWW

    override suspend fun listLocalRecords(): List<LocalRecordRef> = repository.snapshotAll().map {
        LocalRecordRef(it.deviceId, it.updatedAtEpochMillis.coerceAtLeast(0L), it.updatedAtEpochMillis.coerceAtLeast(0L))
    }

    override suspend fun readLocalRecord(localKey: String): SyncRecord {
        val record = repository.snapshotAll().firstOrNull { it.deviceId == localKey }
            ?: throw CloudSyncConflictException("本地使用统计在同步读取期间被删除。")
        val bytes = UsageDeviceJsonCodec.encode(record).toByteArray(Charsets.UTF_8)
        return SyncRecord(
            id = "local",
            revision = record.updatedAtEpochMillis.coerceAtLeast(0L),
            updatedAt = record.updatedAtEpochMillis.coerceAtLeast(0L),
            payload = bytes,
        )
    }

    override suspend fun applyRemoteRecord(
        record: SyncRecord,
        preserveLocalConflict: SyncRecord?,
        preserveLocalKey: String?,
    ): RecordApplyResult? {
        val decoded = UsageDeviceJsonCodec.decode(record.payload.toString(Charsets.UTF_8))
        val merged = repository.mergeIncoming(decoded)
        return RecordApplyResult(merged.deviceId)
    }

    override suspend fun deleteLocalRecord(localKey: String) {
        // Usage histories are per-device merge-only data. Tombstones suppress the record in the
        // sync manifest; this device never creates one because its own identity is always listed.
    }
}

private class ReaderProgressRecordSyncAdapter(
    private val repository: ReaderRepository,
) : RecordSyncAdapter {
    override val contentType = CloudSyncContent.READING_PROGRESS
    override val conflictPolicy = RecordConflictPolicy.LWW

    override suspend fun listLocalRecords(): List<LocalRecordRef> = repository
        .exportProgressRecords()
        .map { LocalRecordRef(readerKey(it.fingerprint, it.type), it.updatedAt.coerceAtLeast(0L), it.updatedAt.coerceAtLeast(0L)) }

    override suspend fun readLocalRecord(localKey: String): SyncRecord {
        val (fingerprint, type) = splitReaderKey(localKey)
        val record = repository.exportProgressRecords().firstOrNull {
            it.fingerprint == fingerprint && it.type == type
        } ?: throw CloudSyncConflictException("本地阅读进度在同步读取期间被删除。")
        val payload = ReaderProgressJsonCodec.encode(listOf(record))
        return SyncRecord("local", record.updatedAt.coerceAtLeast(0L), record.updatedAt.coerceAtLeast(0L), payload = payload)
    }

    override suspend fun applyRemoteRecord(
        record: SyncRecord,
        preserveLocalConflict: SyncRecord?,
        preserveLocalKey: String?,
    ): RecordApplyResult? {
        val incoming = ReaderProgressJsonCodec.decode(record.payload)
        val rollback = repository.exportProgressRecords()
        try {
            repository.importProgressRecords(incoming)
            val (fingerprint, type) = if (incoming.isEmpty()) {
                splitReaderKey(checkNotNull(preserveLocalKey))
            } else {
                incoming.first().let { it.fingerprint to it.type }
            }
            return RecordApplyResult(readerKey(fingerprint, type))
        } catch (error: Exception) {
            repository.replaceProgressRecordsForRollback(rollback)
            throw error
        }
    }

    override suspend fun deleteLocalRecord(localKey: String) {
        val (fingerprint, type) = splitReaderKey(localKey)
        val remaining = repository.exportProgressRecords()
            .filterNot { it.fingerprint == fingerprint && it.type == type }
        repository.replaceProgressRecordsForRollback(remaining)
    }

    private fun readerKey(fingerprint: String, type: ReaderBookType): String = "$fingerprint\u0000${type.name}"

    private fun splitReaderKey(key: String): Pair<String, ReaderBookType> {
        val parts = key.split("\u0000")
        require(parts.size == 2) { "无效的阅读进度同步键。" }
        return parts[0] to ReaderBookType.valueOf(parts[1])
    }
}

private class AgentChatRecordSyncAdapter(
    private val repository: AgentChatSyncRepository,
) : RecordSyncAdapter {
    override val contentType = CloudSyncContent.AGENT_CHATS
    override val conflictPolicy = RecordConflictPolicy.LWW

    override suspend fun listLocalRecords(): List<LocalRecordRef> {
        val snapshot = repository.snapshot(AgentChatSyncRepository.MAX_JSON_BYTES.toLong())
        return listOf(
            LocalRecordRef(
                "agent-chats",
                snapshot.lastModifiedMillis.coerceAtLeast(1L),
                snapshot.lastModifiedMillis.coerceAtLeast(0L),
            ),
        )
    }

    override suspend fun readLocalRecord(localKey: String): SyncRecord {
        val snapshot = repository.snapshot(AgentChatSyncRepository.MAX_JSON_BYTES.toLong())
        return SyncRecord(
            "local",
            snapshot.lastModifiedMillis.coerceAtLeast(1L),
            snapshot.lastModifiedMillis.coerceAtLeast(0L),
            payload = snapshot.bytes,
        )
    }

    override suspend fun applyRemoteRecord(
        record: SyncRecord,
        preserveLocalConflict: SyncRecord?,
        preserveLocalKey: String?,
    ): RecordApplyResult? {
        repository.mergeIncoming(
            bytes = record.payload,
            expectedSha256 = record.payloadSha256,
            maxBytes = AgentChatSyncRepository.MAX_JSON_BYTES.toLong(),
        )
        return RecordApplyResult("agent-chats")
    }

    override suspend fun deleteLocalRecord(localKey: String) {
        // Agent conversations use in-payload tombstones; the aggregate object is never deleted.
    }
}

private class VaultRecordSyncAdapter(
    private val repository: VaultRepository,
) : RecordSyncAdapter {
    override val contentType = CloudSyncContent.VAULT
    override val conflictPolicy = RecordConflictPolicy.LWW

    override suspend fun listLocalRecords(): List<LocalRecordRef> {
        val backup = repository.createEncryptedBackup()
        val revision = backup.items.maxOfOrNull { it.updatedAt } ?: 0L
        return listOf(LocalRecordRef("vault", revision.coerceAtLeast(1L), revision.coerceAtLeast(0L)))
    }

    override suspend fun readLocalRecord(localKey: String): SyncRecord {
        val backup = repository.createEncryptedBackup()
        val revision = backup.items.maxOfOrNull { it.updatedAt } ?: 0L
        return SyncRecord(
            "local",
            revision.coerceAtLeast(1L),
            revision.coerceAtLeast(0L),
            payload = encodeVault(backup),
        )
    }

    override suspend fun applyRemoteRecord(
        record: SyncRecord,
        preserveLocalConflict: SyncRecord?,
        preserveLocalKey: String?,
    ): RecordApplyResult? {
        repository.restoreEncryptedBackup(decodeVault(record.payload))
        return RecordApplyResult("vault")
    }

    override suspend fun deleteLocalRecord(localKey: String) {
        // Vault ciphertext is a single encrypted aggregate; deletion is not exposed as a record
        // tombstone to avoid ever discarding the only encrypted copy.
    }

    private fun encodeVault(backup: VaultEncryptedBackup): ByteArray {
        val root = JSONObject()
            .put("format", "deskcubby-vault-sync")
            .put("version", 1)
            .put("active", backup.active?.toJson() ?: JSONObject.NULL)
            .put("pending", backup.pending?.toJson() ?: JSONObject.NULL)
            .put(
                "items",
                JSONArray().apply {
                    backup.items.sortedBy { it.id }.forEach { item ->
                        put(
                            JSONObject()
                                .put("id", item.id)
                                .put("cipherText", item.cipherText)
                                .put("iv", item.iv)
                                .put("createdAt", item.createdAt)
                                .put("updatedAt", item.updatedAt)
                                .put("sortOrder", item.sortOrder),
                        )
                    }
                },
            )
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    private fun decodeVault(bytes: ByteArray): VaultEncryptedBackup {
        require(bytes.isNotEmpty() && bytes.size <= 8L * 1024 * 1024) { "Vault 同步载荷大小无效。" }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.optString("format") == "deskcubby-vault-sync" && root.optInt("version") == 1)
        val items = buildList {
            val array = root.optJSONArray("items") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    com.deskcubby.app.data.local.VaultItemEntity(
                        id = item.getLong("id"),
                        cipherText = item.getString("cipherText"),
                        iv = item.getString("iv"),
                        createdAt = item.getLong("createdAt"),
                        updatedAt = item.getLong("updatedAt"),
                        sortOrder = item.getLong("sortOrder"),
                    ),
                )
            }
        }
        return VaultEncryptedBackup(
            active = if (root.isNull("active")) null else root.getJSONObject("active").toVaultKey(),
            pending = if (root.isNull("pending")) null else root.getJSONObject("pending").toVaultKey(),
            items = items,
        )
    }

    private fun VaultEncryptedKeyBackup.toJson(): JSONObject = JSONObject()
        .put("saltBase64", saltBase64)
        .put("verifierCipher", verifierCipher)
        .put("verifierIv", verifierIv)
        .put("iterations", iterations)
        .put("generationId", generationId ?: JSONObject.NULL)

    private fun JSONObject.toVaultKey(): VaultEncryptedKeyBackup = VaultEncryptedKeyBackup(
        saltBase64 = getString("saltBase64"),
        verifierCipher = getString("verifierCipher"),
        verifierIv = getString("verifierIv"),
        iterations = getInt("iterations"),
        generationId = if (isNull("generationId")) null else getString("generationId"),
    )
}

private class GlobalSettingsRecordSyncAdapter(
    private val repository: SettingsRepository,
) : RecordSyncAdapter {
    override val contentType = CloudSyncContent.GLOBAL_SETTINGS
    override val conflictPolicy = RecordConflictPolicy.LWW

    override suspend fun listLocalRecords(): List<LocalRecordRef> {
        val payload = GlobalSettingsSyncCodec.encode(repository.settings.first())
        val rev = contentRevision(payload)
        return listOf(LocalRecordRef("global-settings", rev, rev))
    }

    override suspend fun readLocalRecord(localKey: String): SyncRecord {
        val payload = GlobalSettingsSyncCodec.encode(repository.settings.first())
        val rev = contentRevision(payload)
        return SyncRecord("local", rev, rev, payload = payload)
    }

    override suspend fun applyRemoteRecord(
        record: SyncRecord,
        preserveLocalConflict: SyncRecord?,
        preserveLocalKey: String?,
    ): RecordApplyResult? {
        repository.applyGlobalSettingsSync(record.payload)
        return RecordApplyResult("global-settings")
    }

    override suspend fun deleteLocalRecord(localKey: String) {
        // The GLOBAL_SETTINGS aggregate object is never deleted.
    }
}

private class RssSubscriptionRecordSyncAdapter(
    private val repository: SettingsRepository,
) : RecordSyncAdapter {
    override val contentType = CloudSyncContent.RSS_SUBSCRIPTIONS
    override val conflictPolicy = RecordConflictPolicy.LWW

    private fun payloadFor(item: com.deskcubby.app.data.model.RssSubscription): ByteArray =
        recordPayload(
            JSONObject()
                .put("id", item.id)
                .put("title", item.title)
                .put("url", item.url)
                .put("enabled", item.enabled),
        )

    override suspend fun listLocalRecords(): List<LocalRecordRef> =
        repository.settings.first().rssSubscriptions.map { item ->
            val rev = contentRevision(payloadFor(item))
            LocalRecordRef(item.id, rev, rev)
        }

    override suspend fun readLocalRecord(localKey: String): SyncRecord {
        val item = repository.settings.first().rssSubscriptions.firstOrNull { it.id == localKey }
            ?: throw CloudSyncConflictException("本地 RSS 订阅在同步读取期间被删除。")
        val payload = payloadFor(item)
        val rev = contentRevision(payload)
        return SyncRecord("local", rev, rev, payload = payload)
    }

    override suspend fun applyRemoteRecord(
        record: SyncRecord,
        preserveLocalConflict: SyncRecord?,
        preserveLocalKey: String?,
    ): RecordApplyResult? {
        val json = recordJson(record.payload)
        val remote = com.deskcubby.app.data.model.RssSubscription(
            id = json.requiredRecordString("id"),
            title = json.requiredRecordString("title"),
            url = json.requiredRecordString("url"),
            enabled = json.requiredRecordBoolean("enabled"),
        )
        val current = repository.settings.first().rssSubscriptions
        // Dedupe by feed URL, not by the device-local record id. Each device generates a fresh UUID
        // per subscription, so matching on id would never converge and would create a duplicate feed
        // for the same URL on every other device. Keep the local row's own id when the URL matches.
        val sameUrl = current.firstOrNull { it.url.equals(remote.url, ignoreCase = true) }
        if (sameUrl != null) {
            val merged = sameUrl.copy(
                title = remote.title.ifBlank { sameUrl.title },
                enabled = remote.enabled,
            )
            repository.setRssSubscriptions(
                current.map { if (it.id == sameUrl.id) merged else it },
            )
            return RecordApplyResult(sameUrl.id)
        }
        repository.setRssSubscriptions(current + remote)
        return RecordApplyResult(remote.id)
    }

    override suspend fun deleteLocalRecord(localKey: String) {
        repository.setRssSubscriptions(
            repository.settings.first().rssSubscriptions.filterNot { it.id == localKey },
        )
    }
}

private class ReaderPreferencesRecordSyncAdapter(
    private val repository: ReaderRepository,
) : RecordSyncAdapter {
    override val contentType = CloudSyncContent.READER_PREFERENCES
    override val conflictPolicy = RecordConflictPolicy.LWW

    override suspend fun listLocalRecords(): List<LocalRecordRef> {
        val rev = contentRevision(encode(repository.state.value.preferences))
        return listOf(LocalRecordRef("reader-preferences", rev, rev))
    }

    override suspend fun readLocalRecord(localKey: String): SyncRecord {
        val payload = encode(repository.state.value.preferences)
        val rev = contentRevision(payload)
        return SyncRecord("local", rev, rev, payload = payload)
    }

    override suspend fun applyRemoteRecord(
        record: SyncRecord,
        preserveLocalConflict: SyncRecord?,
        preserveLocalKey: String?,
    ): RecordApplyResult? {
        repository.updatePreferences(decode(record.payload))
        return RecordApplyResult("reader-preferences")
    }

    override suspend fun deleteLocalRecord(localKey: String) {
        // Reader preferences are an aggregate object and are never deleted.
    }

    private fun encode(value: ReaderPreferences): ByteArray = JSONObject()
        .put("format", "deskcubby-reader-preferences")
        .put("version", 1)
        .put("background", value.background.name)
        .put("customBackgroundArgb", value.customBackgroundArgb)
        .put("fontSizeSp", value.fontSizeSp.toDouble())
        .put("lineHeightMultiplier", value.lineHeightMultiplier.toDouble())
        .put("paragraphSpacingDp", value.paragraphSpacingDp.toDouble())
        .put("showProgressPercentage", value.showProgressPercentage)
        .put("chapterDetectionMode", value.chapterDetectionMode.name)
        .toString().toByteArray(Charsets.UTF_8)

    private fun decode(bytes: ByteArray): ReaderPreferences {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.optString("format") == "deskcubby-reader-preferences" && root.optInt("version") == 1)
        val current = repository.state.value.preferences
        return current.copy(
            background = ReaderBackground.valueOf(root.getString("background")),
            customBackgroundArgb = root.getInt("customBackgroundArgb"),
            fontSizeSp = root.getDouble("fontSizeSp").toFloat(),
            lineHeightMultiplier = root.getDouble("lineHeightMultiplier").toFloat(),
            paragraphSpacingDp = root.getDouble("paragraphSpacingDp").toFloat(),
            showProgressPercentage = root.getBoolean("showProgressPercentage"),
            chapterDetectionMode = ReaderChapterDetectionMode.valueOf(root.getString("chapterDetectionMode")),
            // Device-local fields are preserved from the current device.
        )
    }
}

/**
 * A deterministic 64-bit revision derived from the canonical payload. Aggregate configuration
 * (settings, reader preferences, RSS) has no natural monotonic counter, so a stable content
 * fingerprint is used as the sync revision: identical content yields the same revision (no
 * ping-pong), and any content change yields a different revision (change detection works). The
 * underlying payload hash is also the record's integrity check.
 */
private fun contentRevision(payload: ByteArray): Long =
    java.lang.Long.parseUnsignedLong(sha256(payload).take(16), 16)
