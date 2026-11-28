package com.amwolfstein.sistrum.library.analysis

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import com.amwolfstein.sistrum.library.db.TrackAnalysisEntity
import com.amwolfstein.sistrum.library.RoomLibraryStore
import com.amwolfstein.sistrum.library.db.ScanEntryEntity
import com.amwolfstein.sistrum.library.metadata.SeparatorConfig
import com.amwolfstein.sistrum.library.scan.ScanPublisher
import com.amwolfstein.sistrum.library.scan.ScannedMetadata
import com.amwolfstein.sistrum.library.tags.MetadataExtractor
import com.amwolfstein.sistrum.library.tags.TagMetadata
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Isolated clean-install recovery: analysis written to a copy's tags must be
 * restored into an empty Room database during publication, without decoding the
 * audio again, and invalid sets must be rejected. Uses its own in-memory
 * databases and a copied fixture; the installed app's library is never touched.
 */
class CleanInstallAnalysisRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val extractor = MetadataExtractor(context)
    private lateinit var source: File
    private lateinit var db1: LibraryDatabase
    private lateinit var db2: LibraryDatabase

    @Before
    fun setUp() {
        source = File(context.cacheDir, "clean-install-${System.nanoTime()}.mp3")
        // A real LAME/Xing MP3: TagLib rewrites it without touching the audible payload.
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("analysis/tone.mp3").use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            }
        db1 = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        db2 = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        source.delete()
        db1.close()
        db2.close()
    }

    private suspend fun analyzeIntoFirstInstallation(): TrackAnalysisEntity {
        val dao = db1.libraryDao()
        dao.upsertSource(LibrarySourceEntity("s1", "file://tree", "Music", createdAt = 1L))
        dao.upsertItem(
            LibraryItemEntity(
                id = "track_1", documentKey = "doc_1", fileUri = Uri.fromFile(source).toString(),
                sourceId = "s1", title = "Fixture", sortTitle = "Fixture",
                durationSeconds = 3, format = "mp3", audioFingerprint = fingerprint(),
            ),
        )
        val engine = AnalysisEngine(context, db1, writeTags = { true })
        val result = engine.analyzeIfNeeded("track_1").getOrThrow()
        // Tags were written through the journaled writer; the original audio payload is unchanged.
        val tags = extractor.read(Uri.fromFile(source), includeFingerprint = true).getOrThrow().metadata.rawTags
        AnalysisTagCodec.ANALYSIS_KEYS.forEach { key ->
            assertTrue("missing embedded $key", tags.containsKey(key))
        }
        return result
    }

    private fun fingerprint(): String? =
        android.os.ParcelFileDescriptor.open(source, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            .use { descriptor ->
                com.amwolfstein.sistrum.library.tags.TagLibBridge.nativeAudioFingerprint(descriptor.fd, 0, -1)
            }

    private suspend fun publishIntoSecondInstallation(): Pair<TrackAnalysisEntity?, TagMetadata> {
        val dao = db2.libraryDao()
        dao.upsertSource(LibrarySourceEntity("s2", "file://tree", "Music", createdAt = 1L))
        val uri = Uri.fromFile(source)
        val read = extractor.read(uri, includeFingerprint = true).getOrThrow()
        val sourceEntity = dao.source("s2")!!
        // A fresh installation has scan metadata but no analysis rows.
        dao.upsertScanRun(
            com.amwolfstein.sistrum.library.db.ScanRunEntity(
                id = "run2",
                sourceId = "s2",
                treeUri = "file://tree",
                mode = "INCREMENTAL",
                generation = 1L,
                state = "COMPLETED",
                startedAt = 1L,
                finishedAt = 2L,
                totalFiles = 1,
                scannedFiles = 1,
            ),
        )
        dao.upsertScanEntries(
            listOf(
                ScanEntryEntity(
                    runId = "run2",
                    documentKey = "doc_1",
                    fileUri = uri.toString(),
                    modTime = source.lastModified(),
                    size = source.length(),
                    state = "SEEN",
                    relativePath = "fixture.mp3",
                    name = "fixture.mp3",
                ),
            ),
        )
        ScanPublisher(
            store = RoomLibraryStore(dao),
            metadataProvider = { ScannedMetadata(read.metadata, read.fingerprint) },
            separators = SeparatorConfig(),
            now = { 1L },
        ).publish(sourceEntity, "run2", complete = true)
        return dao.analysis(dao.itemByDocumentKey("doc_1")!!.id) to read.metadata
    }

    @Test
    fun embeddedAnalysisIsRestoredWithoutReanalysis() = runBlocking {
        val original = analyzeIntoFirstInstallation()
        val (restored, metadata) = publishIntoSecondInstallation()
        assertTrue("analysis row missing after clean install", restored != null)
        assertEquals("embedded", restored!!.source)
        assertEquals(original.analyzerVersion, restored.analyzerVersion)
        assertEquals(original.loudnessLufs!!, restored.loudnessLufs!!, 1e-3)
        assertEquals(original.tempo!!, restored.tempo!!, 0.05)
        assertEquals(original.energy!!, restored.energy!!, 1e-6)
        assertEquals(original.audioFingerprint, restored.audioFingerprint)
        // A valid embedded set leaves nothing in the analysis queue.
        val needing = db2.libraryDao().itemsNeedingAnalysis(10, AnalysisVersion.ANALYZER)
        assertTrue("restored row must not be re-analyzed", needing.isEmpty())
        assertEquals(original.loudnessLufs!!, metadata.rawTags[AnalysisTagCodec.LUFS]!!.first().toDouble(), 1e-6)
    }

    @Test
    fun unchangedAudioKeepsTheSameFingerprintAfterTagWrites() = runBlocking {
        analyzeIntoFirstInstallation()
        val after = extractor.read(Uri.fromFile(source), includeFingerprint = true).getOrThrow()
        val direct = fingerprint()
        assertEquals(
            "tags must not change the decoded PCM (direct=$direct extractor=${after.fingerprint} " +
                "duration=${after.metadata.durationMs} size=${source.length()})",
            direct,
            after.fingerprint,
        )
    }

    @Test
    fun invalidOrIncompleteSetsAreRejected() = runBlocking {
        analyzeIntoFirstInstallation()
        val tags = extractor.read(Uri.fromFile(source), includeFingerprint = true).getOrThrow().metadata.rawTags
        val fingerprint = fingerprint()
        assertTrue(AnalysisTagCodec.fromTags(tags, fingerprint) != null)

        // Fingerprint mismatch: the tags belong to different audio.
        assertNull(AnalysisTagCodec.fromTags(tags, "pcm-sha256-v1:deadbeef"))

        // Incomplete set.
        val incomplete = tags - AnalysisTagCodec.LUFS
        assertNull(AnalysisTagCodec.fromTags(incomplete, fingerprint))

        // Stale analyzer version.
        val stale = tags + (AnalysisTagCodec.ANALYSIS_VERSION to listOf("3"))
        assertNull(AnalysisTagCodec.fromTags(stale, fingerprint))

        // Non-numeric value.
        val malformed = tags + (AnalysisTagCodec.TEMPO to listOf("not-a-number"))
        assertNull(AnalysisTagCodec.fromTags(malformed, fingerprint))
    }

    @Test
    fun readOnlyCopyKeepsTheRoomResultWithoutTags() = runBlocking {
        val dao = db1.libraryDao()
        dao.upsertSource(LibrarySourceEntity("s1", "file://tree", "Music", createdAt = 1L))
        dao.upsertItem(
            LibraryItemEntity(
                id = "track_1", documentKey = "doc_1", fileUri = Uri.fromFile(source).toString(),
                sourceId = "s1", title = "Fixture", sortTitle = "Fixture",
                durationSeconds = 3, format = "mp3", audioFingerprint = fingerprint(),
            ),
        )
        android.system.Os.chmod(source.absolutePath, 0b100_100_100) // 0444
        val result = AnalysisEngine(context, db1, writeTags = { true }).analyzeIfNeeded("track_1").getOrThrow()
        assertEquals("COMPLETED", result.status)
        assertTrue(result.loudnessLufs != null)
        val tags = extractor.read(Uri.fromFile(source)).getOrThrow().metadata.rawTags
        // The source is read-only: analysis stays in Room and nothing crashes.
        assertFalse(tags.containsKey(AnalysisTagCodec.ANALYSIS_VERSION))
    }

    @Test
    fun userReplayGainIsPreservedWhenConfigured() = runBlocking {
        val dao = db1.libraryDao()
        dao.upsertSource(LibrarySourceEntity("s1", "file://tree", "Music", createdAt = 1L))
        dao.upsertItem(
            LibraryItemEntity(
                id = "track_1", documentKey = "doc_1", fileUri = Uri.fromFile(source).toString(),
                sourceId = "s1", title = "Fixture", sortTitle = "Fixture",
                durationSeconds = 3, format = "mp3", audioFingerprint = fingerprint(),
            ),
        )
        // Pre-write a gain value the analyzer must not overwrite.
        val prewrite = com.amwolfstein.sistrum.library.tags.TagWriter(context, dao).write(
            dao.itemById("track_1")!!,
            com.amwolfstein.sistrum.library.tags.TagEdit(
                properties = mapOf(AnalysisTagCodec.TRACK_GAIN to "-99.00 dB"),
            ),
        )
        assertTrue("prewrite failed: $prewrite", prewrite is com.amwolfstein.sistrum.library.tags.TagWriteResult.Success)
        AnalysisEngine(context, db1, writeTags = { true }, preserveReplayGain = true)
            .analyzeIfNeeded("track_1").getOrThrow()
        val preserved = extractor.read(Uri.fromFile(source), includeFingerprint = true).getOrThrow().metadata.rawTags
        assertEquals(listOf("-99.00 dB"), preserved[AnalysisTagCodec.TRACK_GAIN])

        AnalysisEngine(context, db1, writeTags = { true }, preserveReplayGain = false)
            .analyze("track_1", deriveMood = true).getOrThrow()
        val rewrite = AnalysisEngine(context, db1, writeTags = { true }, preserveReplayGain = false)
            .writeAnalysisTags("track_1", db1.libraryDao().analysis("track_1")!!)
        assertTrue(
            "gain rewrite result: $rewrite",
            rewrite is com.amwolfstein.sistrum.library.tags.TagWriteResult.Success,
        )
        val replaced = extractor.read(Uri.fromFile(source), includeFingerprint = true).getOrThrow().metadata.rawTags
        assertFalse(
            "gain was not refreshed: file=${replaced[AnalysisTagCodec.TRACK_GAIN]} " +
                "confirmed=${(rewrite as? com.amwolfstein.sistrum.library.tags.TagWriteResult.Success)?.metadata?.first(AnalysisTagCodec.TRACK_GAIN)}",
            replaced[AnalysisTagCodec.TRACK_GAIN].orEmpty().firstOrNull() == "-99.00 dB",
        )
    }
}
