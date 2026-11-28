package com.amwolfstein.sistrum.library.analysis

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Analysis job lifecycle: retries, failure visibility, cancellation and recovery after an
 * interrupted (process-death) run. Uses an in-memory database and a missing document so no audio
 * or installed library state is touched.
 */
class AnalysisLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var database: LibraryDatabase

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.libraryDao().upsertSource(
            LibrarySourceEntity("s", "content://authority/tree/s", "Music", createdAt = 1L),
        )
        database.libraryDao().upsertItem(
            LibraryItemEntity(
                id = "missing",
                documentKey = "doc-missing",
                fileUri = Uri.fromFile(File(context.cacheDir, "does-not-exist.mp3")).toString(),
                sourceId = "s",
                title = "Missing",
                sortTitle = "Missing",
                format = "mp3",
            ),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun failureIsRecordedAndRetriedUpToTheLimit() = runBlocking {
        val engine = AnalysisEngine(context, database)
        val first = engine.analyze("missing")
        assertTrue(first.isFailure)
        val row = database.libraryDao().analysis("missing")!!
        assertEquals("FAILED", row.status)
        assertEquals(1, row.attempts)
        assertTrue("error message missing", !row.lastError.isNullOrBlank())

        assertTrue(engine.analyze("missing").isFailure)
        assertEquals(2, database.libraryDao().analysis("missing")!!.attempts)

        // Still enqueued while attempts < 3.
        assertTrue(database.libraryDao().itemsNeedingAnalysis(10, AnalysisVersion.ANALYZER, 3).any { it.id == "missing" })

        assertTrue(engine.analyze("missing").isFailure)
        assertEquals(3, database.libraryDao().analysis("missing")!!.attempts)
        // Exhausted: excluded from the queue, but the failure stays visible.
        assertFalse(database.libraryDao().itemsNeedingAnalysis(10, AnalysisVersion.ANALYZER, 3).any { it.id == "missing" })
    }

    @Test
    fun interruptedRunIsRecoveredAndCancellationIsResumable() = runBlocking {
        val dao = database.libraryDao()
        dao.insertAnalysisPlaceholder(
            com.amwolfstein.sistrum.library.db.TrackAnalysisEntity(
                trackId = "missing",
                status = "RUNNING",
                updatedAt = 1L,
            ),
        )
        dao.markAnalysisRunning("missing", 1L)
        assertEquals("RUNNING", dao.analysis("missing")!!.status)

        val recovered = dao.resetInterruptedAnalyses(2L)
        assertEquals(1, recovered)
        assertEquals("PENDING", dao.analysis("missing")!!.status)

        dao.markAnalysisRunning("missing", 3L)
        dao.markAnalysisCancelled("missing", 4L)
        assertEquals("CANCELLED", dao.analysis("missing")!!.status)
        assertTrue(dao.itemsNeedingAnalysis(10, AnalysisVersion.ANALYZER, 3).any { it.id == "missing" })
    }

    @Test
    fun schedulerRunsAQueueAndReportsCounts() = runBlocking {
        val engine = AnalysisEngine(context, database)
        val scheduler = AnalysisScheduler(engine, database.libraryDao())
        val result = scheduler.run(limit = 5)
        assertEquals(1, result.processed)
        assertEquals(0, result.completed)
        assertEquals(1, result.failed)
        assertFalse(result.alreadyRunning)
    }

    @Test
    fun staleAnalyzerVersionIsInvalidated() = runBlocking {
        val dao = database.libraryDao()
        // A row from the previous approximate analyzer (version 1) must be re-queued.
        dao.upsertAnalysis(
            com.amwolfstein.sistrum.library.db.TrackAnalysisEntity(
                trackId = "missing",
                analyzerVersion = 1,
                status = "COMPLETED",
                loudnessLufs = -14.0,
                truePeak = -1.0,
                rms = -18.0,
                crest = 1.4,
                spectralCentroid = 1.0,
                spectralRolloff = 1.0,
                spectralFlatness = 0.1,
                spectralFlux = 0.1,
                zcr = 0.1,
                tempo = 120.0,
                onsetVariance = 1.0,
                loudnessRange = 5.0,
            ),
        )
        assertTrue(dao.itemsNeedingAnalysis(10, AnalysisVersion.ANALYZER).any { it.id == "missing" })
    }
}
