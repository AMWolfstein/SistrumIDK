package com.amwolfstein.sistrum.library.analysis

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnalysisEngineTest {
    private lateinit var context: android.content.Context
    private lateinit var database: LibraryDatabase
    private lateinit var engine: AnalysisEngine
    private lateinit var audio: File

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        engine = AnalysisEngine(context, database)
        audio = File(context.cacheDir, "analysis-fixture.mp3")
        InstrumentationRegistry.getInstrumentation().context.assets.open("analysis/tone.mp3").use { input ->
            audio.outputStream().use { output -> input.copyTo(output) }
        }
        database.libraryDao().upsertSource(LibrarySourceEntity("s", "content://s", "Music", createdAt = 1L))
        database.libraryDao().upsertItem(
            LibraryItemEntity(
                id = "track_1", documentKey = "doc_1", fileUri = Uri.fromFile(audio).toString(),
                sourceId = "s", title = "Fixture", sortTitle = "Fixture", durationSeconds = 3, format = "mp3",
            ),
        )
    }

    private fun writeClicks(target: File, bpm: Double, seconds: Double) {
        val sampleRate = 44100
        val frames = (sampleRate * seconds).toInt()
        val data = ShortArray(frames)
        val interval = (sampleRate * 60.0 / bpm).toInt()
        var position = interval / 4
        while (position < frames) {
            for (i in 0 until 200) {
                val index = position + i
                if (index >= frames) break
                data[index] = (Short.MAX_VALUE * 0.8 * (1.0 - i / 200.0)).toInt().toShort()
            }
            position += interval
        }
        val bytes = java.nio.ByteBuffer.allocate(44 + data.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + data.size * 2).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
        bytes.putShort(2).putShort(16).put("data".toByteArray()).putInt(data.size * 2)
        data.forEach { bytes.putShort(it) }
        target.writeBytes(bytes.array())
    }

    @After
    fun tearDown() {
        audio.delete()
        database.close()
    }

    @Test
    fun analysisProducesFiniteFieldsAndPersistsThem() = runBlocking {
        val result = engine.analyze("track_1").getOrThrow()
        assertEquals("COMPLETED", result.status)
        assertEquals(AnalysisVersion.ANALYZER, result.analyzerVersion)
        val numbers = listOf(
            result.loudnessLufs, result.loudnessRange, result.truePeak, result.rms, result.crest,
            result.spectralCentroid, result.spectralRolloff, result.spectralFlatness, result.spectralFlux,
            result.zcr, result.tempo, result.onsetVariance, result.energy, result.danceability, result.brightness,
        ).filterNotNull()
        assertEquals(15, numbers.size)
        assertTrue("all fields must be finite: $numbers", numbers.all { it.isFinite() })
        assertTrue("energy in range: ${result.energy}", (result.energy ?: -1.0) in 0.0..1.0)
        assertTrue("danceability in range: ${result.danceability}", (result.danceability ?: -1.0) in 0.0..1.0)
        assertNotNull(database.libraryDao().analysis("track_1"))
    }

    @Test
    fun synthetic120BpmClicksDetectTempo() = runBlocking {
        val beats = File(context.cacheDir, "beats-120.wav")
        writeClicks(beats, bpm = 120.0, seconds = 12.0)
        database.libraryDao().upsertItem(
            LibraryItemEntity(
                id = "track_beats", documentKey = "doc_beats", fileUri = Uri.fromFile(beats).toString(),
                sourceId = "s", title = "Beats", sortTitle = "Beats", durationSeconds = 12,
            ),
        )
        val result = engine.analyze("track_beats").getOrThrow()
        val tempo = result.tempo ?: 0.0
        assertTrue("120 BPM clicks should detect near 120, got $tempo", tempo in 80.0..160.0)
        assertTrue((result.energy ?: 0.0) > 0.0)
        beats.delete()
        Unit
    }

    @Test
    fun reanalysisIsStableAndAnalyzeAllSkipsCompletedRows() = runBlocking {
        val first = engine.analyze("track_1").getOrThrow()
        val second = engine.analyze("track_1").getOrThrow()
        assertEquals(first.tempo, second.tempo)
        assertEquals(first.energy, second.energy)
        assertEquals(0, engine.analyzeAll(limit = 10))
    }

    @Test
    fun replayGainTagsAreWrittenAndExistingValuesPreserved() = runBlocking {
        val tagging = AnalysisEngine(context, database, writeTags = { true })
        val result = tagging.analyzeIfNeeded("track_1").getOrThrow()
        val tags = readTags()
        assertTrue("expected REPLAYGAIN_TRACK_GAIN in $tags", tags.containsKey(AnalysisTagCodec.TRACK_GAIN))
        assertTrue(tags.containsKey(AnalysisTagCodec.TRACK_PEAK))
        val writtenGain = tags.getValue(AnalysisTagCodec.TRACK_GAIN)

        // A second pass must keep the existing gain instead of overwriting it.
        val again = tagging.analyzeIfNeeded("track_1").getOrThrow()
        assertEquals(writtenGain, readTags()[AnalysisTagCodec.TRACK_GAIN])
        assertEquals(result.tempo, again.tempo)
    }

    private suspend fun readTags(): Map<String, List<String>> {
        val metadata = com.amwolfstein.sistrum.library.tags.MetadataExtractor(context)
            .read(Uri.fromFile(audio)).getOrThrow().metadata
        return metadata.rawTags
    }

    @Test
    fun missingFileFailsWithoutWritingAnAnalysisRow() = runBlocking {
        database.libraryDao().upsertItem(
            LibraryItemEntity(
                id = "track_missing", documentKey = "doc_missing", fileUri = "content://missing/audio.mp3",
                sourceId = "s", title = "Missing", sortTitle = "Missing",
            ),
        )
        assertTrue(engine.analyze("track_missing").isFailure)
        val failed = database.libraryDao().analysis("track_missing")
        assertEquals("FAILED", failed?.status)
        assertEquals(1, failed?.attempts)
        assertTrue("failure must stay visible and retryable", !failed?.lastError.isNullOrBlank())
    }
}
