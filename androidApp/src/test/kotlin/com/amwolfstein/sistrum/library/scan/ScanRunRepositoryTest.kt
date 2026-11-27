package com.amwolfstein.sistrum.library.scan

import com.amwolfstein.sistrum.library.db.LibraryExclusionEntity
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import com.amwolfstein.sistrum.library.db.ScanEntryEntity
import com.amwolfstein.sistrum.library.db.ScanErrorEntity
import com.amwolfstein.sistrum.library.db.ScanRunEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRunRepositoryTest {

    private val source = LibrarySourceEntity(
        id = "source_1",
        treeUri = "content://authority/tree/primary%3AMusic",
        displayName = "Music",
    )

    @Test
    fun resumeRestoresCheckpointedDocumentsAfterProcessDeath() = runTest {
        val store = FakeStore()
        val first = ScanRunRepository(store) { 1_000L }
        val run = first.start(source)
        val checkpoint = first.checkpoint(run)
        checkpoint.record(entry("authority:doc1", "a.mp3"))
        checkpoint.record(entry("authority:doc2", "b.mp3"))
        first.finish(run, result(run.id, ScanState.PAUSED, emptyList()), 0, 2)

        val second = ScanRunRepository(store) { 2_000L }
        val resumed = second.startOrResume(source)
        assertEquals(run.id, resumed.id)
        assertTrue(second.checkpoint(resumed).alreadySeen("authority:doc1"))
        assertTrue(second.checkpoint(resumed).alreadySeen("authority:doc2"))
        assertEquals(2, second.runProgress.value.scannedFiles)
    }

    @Test
    fun newRepositoryForFreshSourceDoesNotSkipAnything() = runTest {
        val store = FakeStore()
        val repository = ScanRunRepository(store)
        val run = repository.start(source)
        assertFalse(repository.checkpoint(run).alreadySeen("authority:doc1"))
    }

    @Test
    fun finishPersistsTerminalStateAndCounts() = runTest {
        val store = FakeStore()
        val repository = ScanRunRepository(store) { 5_000L }
        val run = repository.start(source)
        val checkpoint = repository.checkpoint(run)
        checkpoint.record(entry("authority:a", "a.mp3"))
        checkpoint.record(entry("authority:b", "b.mp3"))
        val errors = listOf(ScanErrorRecord("authority:x", "list", "denied", 4_000L))
        repository.finish(run, result(run.id, ScanState.COMPLETED_WITH_ERRORS, errors), 1, 2)

        val stored = store.runs.getValue(run.id)
        assertEquals(ScanState.COMPLETED_WITH_ERRORS.name, stored.state)
        assertEquals(2, stored.scannedFiles)
        assertEquals(1, stored.errorCount)
        assertEquals(5_000L, stored.finishedAt)
        assertFalse(stored.cancelled)
        assertEquals(2, store.entries.size)
        assertEquals(1, store.errors.size)
        assertEquals("list", store.errors.getValue(run.id).single().operation)
    }

    @Test
    fun cancelStateIsTerminalAndFlagged() = runTest {
        val store = FakeStore()
        val repository = ScanRunRepository(store) { 9_000L }
        val run = repository.start(source)
        repository.setState(run, ScanState.CANCELLED)
        val stored = store.runs.getValue(run.id)
        assertEquals(ScanState.CANCELLED.name, stored.state)
        assertTrue(stored.cancelled)
        assertEquals(9_000L, stored.finishedAt)
    }

    @Test
    fun priorVersionsComeOnlyFromACompletedRun() = runTest {
        val store = FakeStore()
        val repository = ScanRunRepository(store)
        val run = repository.start(source)
        repository.checkpoint(run).record(entry("authority:a", "a.mp3", modified = 7, size = 9))
        repository.finish(run, result(run.id, ScanState.COMPLETED, emptyList()), 0, 1)

        val versions = repository.priorVersions(source.id)
        assertEquals(ScanFileVersion(7, 9), versions["authority:a"])

        val second = repository.start(source)
        repository.finish(second, result(second.id, ScanState.COMPLETED_WITH_ERRORS, emptyList()), 1, 1)
        assertEquals(versions, repository.priorVersions(source.id))
    }

    @Test
    fun exclusionsAreNormalizedAndPersisted() = runTest {
        val store = FakeStore()
        val repository = ScanRunRepository(store)
        repository.addExclusion(source.id, "/Private/", folder = true)
        repository.addExclusion(source.id, "song.mp3", folder = false)
        val exclusions = repository.exclusions(source.id)
        assertEquals(listOf("Private", "song.mp3"), exclusions.map { it.relativePath })
        assertTrue(exclusions.first().isFolder)
    }

    @Test
    fun checkpointDoesNotDuplicateWithinARun() = runTest {
        val store = FakeStore()
        val repository = ScanRunRepository(store)
        val run = repository.start(source)
        val checkpoint = repository.checkpoint(run)
        checkpoint.record(entry("authority:a", "a.mp3"))
        checkpoint.record(entry("authority:a", "a.mp3"))
        repository.finish(run, result(run.id, ScanState.COMPLETED, emptyList()), 0, 1)
        assertEquals(1, store.entries.size)
    }

    @Test
    fun cueFieldsSurviveCheckpointing() = runTest {
        val store = FakeStore()
        val repository = ScanRunRepository(store)
        val run = repository.start(source)
        repository.checkpoint(run).record(
            entry("cue#1", "album.cue").copy(
                cueKey = "authority:album.cue",
                cueTrackNumber = 2,
                cueStartMs = 1_000,
                cueEndMs = 2_000,
                cueTitle = "Second",
                cuePerformer = "Artist",
            ),
        )
        repository.finish(run, result(run.id, ScanState.COMPLETED, emptyList()), 0, 1)
        val stored = store.entries.single()
        assertEquals("authority:album.cue", stored.cueKey)
        assertEquals(2, stored.cueTrackNumber)
        assertEquals(1_000L, stored.cueStartMs)
        assertEquals("Second", stored.cueTitle)
    }

    private fun entry(
        key: String,
        name: String,
        modified: Long = 1,
        size: Long? = 2,
    ) = ScanEntry(
        documentKey = key,
        fileUri = "content://authority/document/$key",
        relativePath = name,
        name = name,
        modTime = modified,
        size = size,
    )

    private fun result(runId: String, state: ScanState, errors: List<ScanErrorRecord>) = ScanResult(
        runId = runId,
        state = state,
        entries = emptyList(),
        covers = emptyList(),
        errors = errors,
        scannedFiles = 0,
        complete = errors.isEmpty(),
    )

    private class FakeStore : ScanStore {
        val runs = linkedMapOf<String, ScanRunEntity>()
        val entries = mutableListOf<ScanEntryEntity>()
        val errors = linkedMapOf<String, MutableList<ScanErrorEntity>>()
        private val exclusions = mutableListOf<LibraryExclusionEntity>()

        override suspend fun upsertRun(run: ScanRunEntity) {
            runs[run.id] = run
        }

        override suspend fun run(runId: String): ScanRunEntity? = runs[runId]

        override suspend fun latestRun(sourceId: String): ScanRunEntity? =
            runs.values.filter { it.sourceId == sourceId }.maxByOrNull { it.startedAt }

        override suspend fun latestCompletedRun(sourceId: String): ScanRunEntity? =
            runs.values
                .filter { it.sourceId == sourceId && it.state == ScanState.COMPLETED.name }
                .maxByOrNull { it.startedAt }

        override suspend fun unfinishedRuns(): List<ScanRunEntity> =
            runs.values.filter { it.state == ScanState.RUNNING.name || it.state == ScanState.PAUSED.name }

        override suspend fun entries(runId: String): List<ScanEntryEntity> =
            entries.filter { it.runId == runId }

        override suspend fun deleteEntries(runId: String) {
            entries.removeAll { it.runId == runId }
        }

        override suspend fun upsertEntries(values: List<ScanEntryEntity>) {
            values.forEach { value ->
                entries.removeAll { it.runId == value.runId && it.documentKey == value.documentKey }
                entries += value
            }
        }

        override suspend fun insertError(value: ScanErrorEntity) {
            errors.getOrPut(value.runId) { mutableListOf() } += value
        }

        override suspend fun exclusions(sourceId: String): List<LibraryExclusionEntity> =
            exclusions.filter { it.sourceId == sourceId }

        override suspend fun upsertExclusions(values: List<LibraryExclusionEntity>) {
            values.forEach { value ->
                exclusions.removeAll { it.id == value.id }
                exclusions += value
            }
        }
    }
}
