package com.amwolfstein.sistrum.sync

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.amwolfstein.sistrum.analysis.LocalAnalyzerVersion
import com.amwolfstein.sistrum.analysis.LocalTrackAnalysisEntity
import com.amwolfstein.sistrum.library.LocalAlbumRef
import com.amwolfstein.sistrum.library.LocalLibrarySnapshot
import com.amwolfstein.sistrum.library.LocalTrack
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalAnalysisPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: SyncDatabase
    private lateinit var files: File
    private lateinit var store: AndroidLibrarySyncStore

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, SyncDatabase::class.java).build()
        files = File(context.cacheDir, "local-analysis-test-${System.nanoTime()}").apply { mkdirs() }
        store = AndroidLibrarySyncStore(database, files)
    }

    @After
    fun cleanup() {
        database.close()
        files.deleteRecursively()
    }

    @Test
    fun rejectsStaleResultsAndSurvivesUnchangedLibraryReplacement() = runBlocking {
        scan("revision-1")
        val first = result("revision-1")
        assertTrue(store.saveLocalAnalysis(first))
        assertNotNull(store.analysis("local:1"))
        scan("revision-1")
        assertNotNull(store.analysis("local:1"))
        scan("revision-2")
        assertNull(store.analysis("local:1"))
        assertFalse(store.saveLocalAnalysis(first))
        assertTrue(store.saveLocalAnalysis(result("revision-2")))
        assertNotNull(store.analysis("local:1"))
    }

    @Test
    fun failedRowsWaitForExplicitRetry() = runBlocking {
        scan("revision-1")
        assertTrue(store.saveLocalAnalysis(result("revision-1").copy(status = "failed", rawJson = "{}")))
        assertTrue(store.pendingLocalAnalyses(-1, 1).first().isEmpty())
        store.retryLocalAnalysisFailures()
        assertEquals(listOf("local:1"), store.pendingLocalAnalyses(-1, 1).first().map { it.trackId })
    }

    @Test
    fun pendingWorkIsPagedAndOnlyValidMoodFeaturesAreEligible() = runBlocking {
        val tracks = (1..3).map { index ->
            LocalTrack(
                id = "local:$index", title = "Track $index", artists = emptyList(),
                album = LocalAlbumRef("album", "Album"),
            )
        }
        store.writeLocalLibrary(
            LocalLibrarySnapshot(0, tracks),
            tracks.associate { it.id to LocalScanAudio(it.id, "/${it.id}.mp3", "revision", 100) },
            emptyList(),
        )
        val page = store.pendingLocalAnalyses(-1, 2).first()
        assertEquals(2, page.size)
        assertEquals(1, store.pendingLocalAnalyses(page.last().syncOrder, 2).first().size)
        val mood = result("revision").copy(
            rawJson = """{"loudness_lufs":-14,"true_peak":-1,"rms":-18,"crest":4,"spectral_centroid":1000,"spectral_flux":0.1,"tempo":120,"onset_variance":12,"loudness_range":4}""",
        )
        assertTrue(store.saveLocalAnalysis(mood))
        assertEquals(listOf("local:1"), database.syncDao().observeLocalMoodEligibleIds().first())
        assertTrue(store.saveLocalAnalysis(mood.copy(trackId = "local:2", rawJson = """{"loudness_lufs":-14,"true_peak":-1}""")))
        assertEquals(listOf("local:1"), database.syncDao().observeLocalMoodEligibleIds().first())
    }

    private suspend fun scan(revision: String) {
        val track = LocalTrack(
            id = "local:1", title = "Track", artists = emptyList(),
            album = LocalAlbumRef("album", "Album"),
        )
        store.writeLocalLibrary(
            LocalLibrarySnapshot(0, listOf(track)),
            mapOf(track.id to LocalScanAudio(track.id, "/track.mp3", revision, 100)),
            emptyList(),
        )
    }

    private fun result(revision: String) = LocalTrackAnalysisEntity(
        trackId = "local:1",
        sourceRevision = revision,
        analyzerVersion = LocalAnalyzerVersion,
        status = "success",
        rawJson = """{"loudness_lufs":-14.0,"true_peak":-1.0}""",
        failure = "",
    )
}
