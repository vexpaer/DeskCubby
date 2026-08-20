package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.model.CloudSyncDirection
import java.util.Locale

class RecordSyncEngine(
    private val adapters: Map<CloudSyncContent, RecordSyncAdapter>,
    private val recordStateStore: RecordSyncStateStore,
) {
    /** Content types in [contents] that have no registered adapter; used for a fail-fast guard. */
    fun missingAdapters(contents: Set<CloudSyncContent>): Set<CloudSyncContent> =
        contents.filterNot { it in adapters }.toSet()

    internal suspend fun syncContents(
        config: CloudSyncConfig,
        validated: ValidatedCloudSyncConfig,
        contents: Set<CloudSyncContent>,
        limits: CloudSyncLimits,
        remoteStore: CloudSyncRemoteStore,
        mode: CloudSyncRunMode,
        onProgress: (CloudSyncProgress) -> Unit,
    ): List<CloudSyncItemReport> {
        if (contents.isEmpty()) return emptyList()
        val missing = contents.filterNot { it in adapters }.toSet()
        if (missing.isNotEmpty()) {
            throw CloudSyncConfigurationException(missingRecordAdaptersMessage(missing))
        }
        val ordered = contents.sortedBy { it.name }
        val reports = ArrayList<CloudSyncItemReport>()
        val transport = RecordSyncRemoteStore(remoteStore, limits)
        ordered.forEachIndexed { index, content ->
            val adapter = adapters.getValue(content)
            reports += syncContent(config, validated, adapter, transport, limits, mode)
            onProgress(
                CloudSyncProgress(
                    completedObjects = index + 1,
                    totalObjects = ordered.size,
                    transferredBytes = 0L,
                    currentKey = "records/${content.remoteDirectory.substringAfter('/')}",
                ),
            )
        }
        return reports
    }

    private suspend fun syncContent(
        config: CloudSyncConfig,
        validated: ValidatedCloudSyncConfig,
        adapter: RecordSyncAdapter,
        transport: RecordSyncRemoteStore,
        limits: CloudSyncLimits,
        mode: CloudSyncRunMode,
    ): List<CloudSyncItemReport> {
        val content = adapter.contentType
        val stored = recordStateStore.load(config.id, content)
        val validStored = stored?.takeIf { it.scopeFingerprint == validated.scopeFingerprint }
        val stateEntries = validStored?.entries.orEmpty().toMutableMap()
        val oldManifestVersion = validStored?.manifestVersion

        val remoteManifest = transport.loadManifest(content)
        val localRefs = adapter.listLocalRecords()
        val idByLocalKey = linkedMapOf<String, String>()
        val localRefById = linkedMapOf<String, LocalRecordRef>()
        localRefs.forEach { ref ->
            val existingId = stateEntries.entries.firstOrNull {
                it.value.localKey == ref.localKey && !it.value.deleted
            }?.key
            // Stable identity is derived from the record's logical key, never from its payload.
            // Two distinct records with identical content therefore keep distinct identities (no
            // collision), while a logical object that lives on two devices under the same key
            // (e.g. aggregate settings) converges. Any cross-device "same legacy content" dedupe,
            // if still desired, must be a separate heuristic and must not drive record identity.
            val id = existingId ?: stableRecordId(ref.localKey)
            require(idByLocalKey.put(ref.localKey, id) == null) { "记录同步本地标识重复。" }
            localRefById[id] = ref
        }
        val remoteEntries = remoteManifest?.entriesById.orEmpty().toMutableMap()
        val allIds = (localRefById.keys + remoteEntries.keys + stateEntries.keys).sorted()
        val nextEntries = linkedMapOf<String, RemoteRecordManifestEntry>()
        val updatedStateEntries = linkedMapOf<String, RecordSyncEntry>()
        val reports = ArrayList<CloudSyncItemReport>(allIds.size)

        suspend fun localRecordFor(id: String): SyncRecord? {
            val ref = localRefById[id] ?: return null
            return adapter.readLocalRecord(ref.localKey).copy(id = id)
        }

        suspend fun upload(id: String, record: SyncRecord) {
            transport.writePayload(
                content,
                id,
                record.payload,
                record.payloadSha256,
                remoteEntries[id],
            )
            nextEntries[id] = record.toManifestEntry()
            updatedStateEntries[id] = record.toStateEntry(localRefById[id]?.localKey)
        }

        suspend fun applyRemote(remoteEntry: RemoteRecordManifestEntry) {
            val payload = transport.readPayload(content, remoteEntry.id, remoteEntry.payloadSha256)
            val remoteRecord = SyncRecord(
                id = remoteEntry.id,
                revision = remoteEntry.revision,
                updatedAt = remoteEntry.updatedAt,
                deleted = false,
                payload = payload,
            )
            val applied = adapter.applyRemoteRecord(remoteRecord)
                ?: throw CloudSyncException("远端记录无法写入本地。")
            nextEntries[remoteEntry.id] = remoteEntry
            updatedStateEntries[remoteEntry.id] = remoteEntry.toStateEntry(applied.canonicalLocalKey)
        }

        fun report(key: String, outcome: CloudSyncItemOutcome) {
            reports += CloudSyncItemReport(key, outcome)
        }

        allIds.forEach { id ->
            val ref = localRefById[id]
            val remoteEntry = remoteEntries[id]
            val old = stateEntries[id]

            if (ref == null && remoteEntry == null) {
                if (old != null && !old.deleted) {
                    val tombstone = tombstoneEntry(old)
                    nextEntries[id] = tombstone
                    updatedStateEntries[id] = tombstone.toStateEntry(null)
                } else if (old?.deleted == true) {
                    nextEntries[id] = old.toManifestEntry()
                    updatedStateEntries[id] = old
                }
                return@forEach
            }

            if (remoteEntry?.deleted == true) {
                val localKey = localKeyForId(idByLocalKey, id) ?: old?.localKey
                if (localKey != null) adapter.deleteLocalRecord(localKey)
                nextEntries[id] = remoteEntry
                updatedStateEntries[id] = remoteEntry.toStateEntry(null)
                if (ref != null) report(recordKey(content, id), CloudSyncItemOutcome.DOWNLOADED)
                return@forEach
            }

            if (ref == null) {
                val remote = checkNotNull(remoteEntry)
                if (old != null && !old.deleted) {
                    if (mode != CloudSyncRunMode.FORCE_DOWNLOAD) {
                        val tombstone = tombstoneEntry(old, remote.toStateEntry(null))
                        nextEntries[id] = tombstone
                        updatedStateEntries[id] = tombstone.toStateEntry(null)
                        report(recordKey(content, id), CloudSyncItemOutcome.UPLOADED)
                    } else {
                        applyRemote(remote)
                        report(recordKey(content, id), CloudSyncItemOutcome.DOWNLOADED)
                    }
                } else if (
                    mode == CloudSyncRunMode.FORCE_UPLOAD ||
                    (mode == CloudSyncRunMode.NORMAL &&
                        config.direction == CloudSyncDirection.UPLOAD_ONLY)
                ) {
                    report(recordKey(content, id), CloudSyncItemOutcome.REMOTE_CHANGE_SKIPPED)
                } else {
                    applyRemote(remote)
                    report(recordKey(content, id), CloudSyncItemOutcome.DOWNLOADED)
                }
                return@forEach
            }

            if (remoteEntry == null || mode == CloudSyncRunMode.FORCE_UPLOAD) {
                if (mode == CloudSyncRunMode.FORCE_DOWNLOAD) {
                    report(recordKey(content, id), CloudSyncItemOutcome.REMOTE_CHANGE_SKIPPED)
                    return@forEach
                }
                val local = checkNotNull(localRecordFor(id))
                upload(id, local)
                report(recordKey(content, id), CloudSyncItemOutcome.UPLOADED)
                return@forEach
            }

            if (mode == CloudSyncRunMode.FORCE_DOWNLOAD) {
                applyRemote(remoteEntry)
                report(recordKey(content, id), CloudSyncItemOutcome.DOWNLOADED)
                return@forEach
            }

            // Trust the persisted payload hash when the local revision is unchanged; this keeps
            // a normal sync O(changed records) instead of rereading the entire database.
            val localChanged = old == null || old.deleted ||
                old.revision != checkNotNull(localRefById[id]).revision
            val remoteChanged = old == null || remoteEntry.payloadSha256 != old.payloadSha256 ||
                remoteEntry.revision != old.revision
            val local = if (localChanged) checkNotNull(localRecordFor(id)) else null

            when {
                !localChanged && !remoteChanged -> {
                    nextEntries[id] = remoteEntry
                    updatedStateEntries[id] = remoteEntry.toStateEntry(localKeyForId(idByLocalKey, id))
                    report(recordKey(content, id), CloudSyncItemOutcome.UNCHANGED)
                }

                !localChanged && remoteChanged -> {
                    if (config.direction == CloudSyncDirection.UPLOAD_ONLY) {
                        report(recordKey(content, id), CloudSyncItemOutcome.REMOTE_CHANGE_SKIPPED)
                    } else {
                        applyRemote(remoteEntry)
                        report(recordKey(content, id), CloudSyncItemOutcome.DOWNLOADED)
                    }
                }

                localChanged && !remoteChanged -> {
                    upload(id, checkNotNull(local))
                    report(recordKey(content, id), CloudSyncItemOutcome.UPLOADED)
                }

                localChanged && checkNotNull(local).payloadSha256 == remoteEntry.payloadSha256 -> {
                    val merged = remoteEntry.copy(
                        revision = maxOf(checkNotNull(local).revision, remoteEntry.revision),
                        updatedAt = maxOf(checkNotNull(local).updatedAt, remoteEntry.updatedAt),
                    )
                    nextEntries[id] = merged
                    updatedStateEntries[id] = merged.toStateEntry(localKeyForId(idByLocalKey, id))
                    report(recordKey(content, id), CloudSyncItemOutcome.UNCHANGED)
                }

                else -> {
                    val remotePayload = transport.readPayload(content, id, remoteEntry.payloadSha256)
                    val remote = SyncRecord(
                        id = id,
                        revision = remoteEntry.revision,
                        updatedAt = remoteEntry.updatedAt,
                        deleted = false,
                        payload = remotePayload,
                    )
                    val currentLocal = checkNotNull(local)
                    when (adapter.conflictPolicy) {
                        RecordConflictPolicy.LWW -> {
                            val remoteWins = remote.revision > currentLocal.revision ||
                                (remote.revision == currentLocal.revision &&
                                    remote.payloadSha256 > currentLocal.payloadSha256)
                            if (remoteWins) {
                                if (config.direction != CloudSyncDirection.UPLOAD_ONLY) {
                                    applyRemote(remoteEntry)
                                    report(recordKey(content, id), CloudSyncItemOutcome.DOWNLOADED)
                                } else {
                                    report(recordKey(content, id), CloudSyncItemOutcome.REMOTE_CHANGE_SKIPPED)
                                }
                            } else {
                                upload(id, local)
                                report(recordKey(content, id), CloudSyncItemOutcome.UPLOADED)
                            }
                        }

                        RecordConflictPolicy.CONFLICT_COPY -> {
                            val conflictId = deterministicConflictId(id, local.payloadSha256, remote.payloadSha256)
                            val conflictRecord = local.copy(
                                id = conflictId,
                                revision = nextTombstoneRevision(
                                    maxOf(local.revision, remote.revision),
                                    System.currentTimeMillis(),
                                ),
                                updatedAt = System.currentTimeMillis(),
                            )
                            val applied = adapter.applyRemoteRecord(
                                remote,
                                preserveLocalConflict = local,
                                preserveLocalKey = localKeyForId(idByLocalKey, id),
                            )
                                ?: throw CloudSyncException("无法保存远端冲突副本。")
                            transport.writePayload(
                                content,
                                conflictId,
                                conflictRecord.payload,
                                conflictRecord.payloadSha256,
                            )
                            nextEntries[id] = remoteEntry
                            nextEntries[conflictId] = conflictRecord.toManifestEntry()
                            updatedStateEntries[id] = remoteEntry.toStateEntry(applied.canonicalLocalKey)
                            updatedStateEntries[conflictId] = conflictRecord.toStateEntry(
                                applied.conflictCopyLocalKey,
                            )
                            report(recordKey(content, id), CloudSyncItemOutcome.CONFLICT_COPY_SAVED)
                        }
                    }
                }
            }
        }

        val manifestEntries = nextEntries.values.toList()
        if (manifestEntries.isNotEmpty() && manifestEntries != remoteManifest?.entries) {
            val saved = transport.saveManifest(
                content,
                manifestEntries,
                expectedRemoteVersion = remoteManifest?.version,
            )
            recordStateStore.save(
                config.id,
                RecordSyncContentState(
                    contentType = content,
                    scopeFingerprint = validated.scopeFingerprint,
                    manifestVersion = saved.version,
                    entries = updatedStateEntries,
                ),
            )
        } else if (stateEntries != updatedStateEntries) {
            recordStateStore.save(
                config.id,
                RecordSyncContentState(
                    contentType = content,
                    scopeFingerprint = validated.scopeFingerprint,
                    manifestVersion = oldManifestVersion,
                    entries = updatedStateEntries,
                ),
            )
        }
        return reports
    }

    private fun localKeyForId(
        idByLocalKey: Map<String, String>,
        id: String,
    ): String? = idByLocalKey.entries.firstOrNull { it.value == id }?.key

    private fun RemoteRecordManifestEntry.toStateEntry(localKey: String?): RecordSyncEntry =
        RecordSyncEntry(
            id = id,
            revision = revision,
            updatedAt = updatedAt,
            deleted = deleted,
            payloadSha256 = payloadSha256,
            localKey = localKey,
        )

    private fun RecordSyncEntry.toManifestEntry(): RemoteRecordManifestEntry =
        RemoteRecordManifestEntry(
            id = id,
            revision = revision,
            updatedAt = updatedAt,
            deleted = deleted,
            payloadSha256 = payloadSha256,
        )

    private fun SyncRecord.toManifestEntry(): RemoteRecordManifestEntry =
        RemoteRecordManifestEntry(id, revision, updatedAt, deleted, payloadSha256)

    private fun SyncRecord.toStateEntry(localKey: String?): RecordSyncEntry =
        RecordSyncEntry(id, revision, updatedAt, deleted, payloadSha256, localKey)

    private fun tombstoneEntry(vararg existing: RecordSyncEntry): RemoteRecordManifestEntry {
        val first = existing.first()
        val maxRevision = existing.maxOfOrNull(RecordSyncEntry::revision) ?: 0L
        return RemoteRecordManifestEntry(
            id = first.id,
            revision = nextTombstoneRevision(maxRevision, System.currentTimeMillis()),
            updatedAt = System.currentTimeMillis(),
            deleted = true,
            payloadSha256 = EMPTY_SHA256,
        )
    }

    /** Stable, payload-independent identity derived from the record's logical local key. */
    private fun stableRecordId(localKey: String): String {
        val digest = sha256("record-key $localKey".toByteArray(Charsets.UTF_8)).take(32)
        return "record-$digest"
    }

    private fun recordKey(content: CloudSyncContent, id: String): String =
        "records/${content.remoteDirectory.substringAfter('/')}/$id"

    private companion object {
        val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        fun nextTombstoneRevision(maxKnown: Long, now: Long): Long = maxOf(now, maxKnown + 1)

        fun deterministicConflictId(originalId: String, localSha256: String, remoteSha256: String): String {
            val raw = "$originalId $localSha256 $remoteSha256"
            val digest = sha256(raw.toByteArray(Charsets.UTF_8)).lowercase(Locale.ROOT)
            return "$originalId-conflict-$digest"
        }
    }
}
