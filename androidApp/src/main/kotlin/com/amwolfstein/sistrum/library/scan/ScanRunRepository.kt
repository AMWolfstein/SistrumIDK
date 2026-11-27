package com.amwolfstein.sistrum.library.scan

import com.amwolfstein.sistrum.library.db.LibraryDao
import com.amwolfstein.sistrum.library.db.LibraryExclusionEntity
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import com.amwolfstein.sistrum.library.db.ScanEntryEntity
import com.amwolfstein.sistrum.library.db.ScanErrorEntity
import com.amwolfstein.sistrum.library.db.ScanRunEntity
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Narrow persistence boundary, so the run/checkpoint rules can be tested without Room. */
interface ScanStore {
    suspend fun upsertRun(run: ScanRunEntity)
    suspend fun run(runId: String): ScanRunEntity?
    suspend fun latestRun(sourceId: String): ScanRunEntity?
    suspend fun latestCompletedRun(sourceId: String): ScanRunEntity?
    suspend fun unfinishedRuns(): List<ScanRunEntity>
    suspend fun entries(runId: String): List<ScanEntryEntity>
    suspend fun deleteEntries(runId: String)
    suspend fun upsertEntries(values: List<ScanEntryEntity>)
    suspend fun insertError(value: ScanErrorEntity)
    suspend fun exclusions(sourceId: String): List<LibraryExclusionEntity>
    suspend fun upsertExclusions(values: List<LibraryExclusionEntity>)
}

class RoomScanStore(private val dao: LibraryDao) : ScanStore {
    override suspend fun upsertRun(run: ScanRunEntity) = dao.upsertScanRun(run)
    override suspend fun run(runId: String): ScanRunEntity? = dao.scanRun(runId)
    override suspend fun latestRun(sourceId: String): ScanRunEntity? = dao.latestScanRun(sourceId)
    override suspend fun latestCompletedRun(sourceId: String): ScanRunEntity? = dao.latestCompletedScanRun(sourceId)
    override suspend fun unfinishedRuns(): List<ScanRunEntity> = dao.unfinishedScanRuns()
    override suspend fun entries(runId: String): List<ScanEntryEntity> = dao.scanEntries(runId)
    override suspend fun deleteEntries(runId: String) = dao.deleteScanEntries(runId)
    override suspend fun upsertEntries(values: List<ScanEntryEntity>) = dao.upsertScanEntries(values)
    override suspend fun insertError(value: ScanErrorEntity) = dao.insertScanError(value)
    override suspend fun exclusions(sourceId: String): List<LibraryExclusionEntity> = dao.exclusions(sourceId)
    override suspend fun upsertExclusions(values: List<LibraryExclusionEntity>) = dao.upsertExclusions(values)
}

/**
 * Persists scan runs, per-document checkpoints and progress. A run keeps its id across process
 * death, so resuming loads the recorded entries and only skips documents already seen in this run.
 */
class ScanRunRepository(
    private val store: ScanStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(dao: LibraryDao) : this(RoomScanStore(dao))

    private val writeLock = Mutex()
    private val seen = mutableMapOf<String, ScanEntryEntity>()
    private var pendingEntries = mutableListOf<ScanEntry>()
    private var pendingErrors = mutableListOf<ScanErrorRecord>()
    private var loadedRunId: String? = null
    private val recordedErrors = mutableSetOf<String>()
    private var lastProgress = ScanProgress("", "", ScanState.IDLE)

    val runProgress = MutableStateFlow(ScanProgress("", "", ScanState.IDLE))

    suspend fun start(
        source: LibrarySourceEntity,
        mode: String = "FULL",
        generation: Long = now(),
    ): ScanRunEntity {
        val run = ScanRunEntity(
            id = "scan_" + UUID.randomUUID().toString().replace("-", "").take(24),
            sourceId = source.id,
            treeUri = source.treeUri,
            mode = mode,
            generation = generation,
            state = ScanState.RUNNING.name,
            startedAt = now(),
        )
        store.upsertRun(run)
        store.deleteEntries(run.id)
        loadedRunId = run.id
        seen.clear()
        pendingEntries = mutableListOf()
        pendingErrors = mutableListOf()
        lastProgress = ScanProgress(run.id, source.id, ScanState.RUNNING)
        runProgress.value = lastProgress
        return run
    }

    /** Reuses an unfinished run for the source, restoring every checkpointed document. */
    suspend fun startOrResume(source: LibrarySourceEntity, mode: String = "FULL"): ScanRunEntity {
        val unfinished = store.unfinishedRuns().firstOrNull { it.sourceId == source.id }
        if (unfinished != null) {
            val resumed = unfinished.copy(state = ScanState.RUNNING.name, cancelled = false)
            store.upsertRun(resumed)
            loadedRunId = null
            pendingEntries = mutableListOf()
            pendingErrors = mutableListOf()
            ensureLoaded(resumed)
            return resumed
        }
        return start(source, mode)
    }

    suspend fun latestRun(sourceId: String): ScanRunEntity? = store.latestRun(sourceId)

    /** Versions from the last completed run, used to mark changed documents without re-reading audio. */
    suspend fun priorVersions(sourceId: String): Map<String, ScanFileVersion> {
        val run = store.latestCompletedRun(sourceId) ?: return emptyMap()
        return store.entries(run.id).associate { it.documentKey to ScanFileVersion(it.modTime, it.size) }
    }

    suspend fun exclusions(sourceId: String): List<ScanExclusion> =
        store.exclusions(sourceId).map { ScanExclusion(it.relativePath, it.kind) }

    suspend fun addExclusion(sourceId: String, relativePath: String, folder: Boolean) {
        val normalized = relativePath.trim('/')
        if (normalized.isEmpty()) return
        store.upsertExclusions(
            listOf(
                LibraryExclusionEntity(
                    id = "${sourceId}_${normalized.hashCode()}",
                    sourceId = sourceId,
                    relativePath = normalized,
                    kind = if (folder) ScanExclusion.KIND_FOLDER else ScanExclusion.KIND_FILE,
                    createdAt = now(),
                ),
            ),
        )
    }

    fun checkpoint(run: ScanRunEntity): ScanCheckpoint = object : ScanCheckpoint {
        override suspend fun alreadySeen(documentKey: String): Boolean = writeLock.withLock {
            ensureLoaded(run)
            documentKey in seen
        }

        override suspend fun record(entry: ScanEntry) {
            writeLock.withLock {
                ensureLoaded(run)
                if (entry.documentKey in seen) return@withLock
                pendingEntries += entry
                if (pendingEntries.size >= FLUSH_SIZE) flushLocked(run)
            }
        }

        override suspend fun recordError(error: ScanErrorRecord) {
            writeLock.withLock {
                if (!recordedErrors.add(errorKey(error))) return@withLock
                pendingErrors += error
                if (pendingErrors.size >= FLUSH_SIZE) flushLocked(run)
            }
        }

        override suspend fun reportProgress(scannedFiles: Int, totalFiles: Int, currentPath: String?) {
            writeLock.withLock {
                lastProgress = lastProgress.copy(
                    scannedFiles = scannedFiles,
                    totalFiles = totalFiles,
                    currentPath = currentPath,
                    errorCount = pendingErrors.size,
                )
                runProgress.value = lastProgress
                if (scannedFiles % PROGRESS_WRITE_EVERY == 0) {
                    store.upsertRun(
                        run.copy(
                            scannedFiles = scannedFiles,
                            totalFiles = totalFiles,
                            errorCount = pendingErrors.size,
                        ),
                    )
                }
            }
        }
    }

    suspend fun finish(run: ScanRunEntity, result: ScanResult, errorCount: Int, totalFiles: Int) {
        writeLock.withLock {
            ensureLoaded(run)
            result.errors.forEach { error ->
                if (recordedErrors.add(errorKey(error))) pendingErrors += error
            }
            flushLocked(run)
            val state = result.state
            val effectiveTotal = maxOf(totalFiles, seen.size)
            daoUpsert(
                run.copy(
                    state = state.name,
                    finishedAt = if (state == ScanState.RUNNING || state == ScanState.PAUSED) null else now(),
                    totalFiles = effectiveTotal,
                    scannedFiles = seen.size,
                    errorCount = errorCount,
                    cancelled = state == ScanState.CANCELLED,
                ),
            )
            lastProgress = lastProgress.copy(
                state = state,
                scannedFiles = seen.size,
                totalFiles = effectiveTotal,
                errorCount = errorCount,
            )
            runProgress.value = lastProgress
        }
    }

    suspend fun setState(run: ScanRunEntity, state: ScanState) {
        writeLock.withLock {
            flushLocked(run)
            val terminal = state != ScanState.RUNNING && state != ScanState.PAUSED
            daoUpsert(
                run.copy(
                    state = state.name,
                    finishedAt = if (terminal) now() else null,
                    cancelled = state == ScanState.CANCELLED,
                ),
            )
            lastProgress = lastProgress.copy(state = state)
            runProgress.value = lastProgress
        }
    }

    private suspend fun daoUpsert(run: ScanRunEntity) = store.upsertRun(run)

    private fun errorKey(error: ScanErrorRecord): String =
        "${error.documentKey}|${error.operation}|${error.message}|${error.occurredAt}"

    private suspend fun ensureLoaded(run: ScanRunEntity) {
        if (loadedRunId == run.id) return
        seen.clear()
        store.entries(run.id).forEach { seen[it.documentKey] = it }
        loadedRunId = run.id
        lastProgress = ScanProgress(
            runId = run.id,
            sourceId = run.sourceId,
            state = ScanState.RUNNING,
            scannedFiles = seen.size,
            totalFiles = maxOf(run.totalFiles, seen.size),
            errorCount = run.errorCount,
        )
        runProgress.value = lastProgress
    }

    private suspend fun flushLocked(run: ScanRunEntity) {
        if (pendingEntries.isNotEmpty()) {
            val rows = pendingEntries.map {
                val row = ScanEntryEntity(
                    runId = run.id,
                    documentKey = it.documentKey,
                    fileUri = it.fileUri,
                    modTime = it.modTime,
                    size = it.size,
                    state = "SEEN",
                    relativePath = it.relativePath,
                    name = it.name,
                    cueKey = it.cueKey,
                    cueTrackNumber = it.cueTrackNumber,
                    cueStartMs = it.cueStartMs,
                    cueEndMs = it.cueEndMs,
                    cueTitle = it.cueTitle,
                    cuePerformer = it.cuePerformer,
                    changed = it.changed,
                )
                seen[it.documentKey] = row
                row
            }
            store.upsertEntries(rows)
            pendingEntries = mutableListOf()
        }
        if (pendingErrors.isNotEmpty()) {
            pendingErrors.forEach {
                store.insertError(
                    ScanErrorEntity(
                        runId = run.id,
                        documentKey = it.documentKey,
                        operation = it.operation,
                        message = it.message,
                        occurredAt = it.occurredAt,
                    ),
                )
            }
            pendingErrors = mutableListOf()
        }
    }

    private companion object {
        const val FLUSH_SIZE = 100
        const val PROGRESS_WRITE_EVERY = 50
    }
}
