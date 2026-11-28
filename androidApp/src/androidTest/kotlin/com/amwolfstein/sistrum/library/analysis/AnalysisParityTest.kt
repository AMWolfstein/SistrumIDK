package com.amwolfstein.sistrum.library.analysis

import androidx.test.platform.app.InstrumentationRegistry
import com.amwolfstein.sistrum.player.AnalysisBridge
import java.io.File
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Numerical parity against the upstream desktop producer.
 *
 * The expected values in `assets/analysis/goldens.json` were produced by
 * Airmedy `d859940c` (FFmpeg 9.0.2 + aubio 0.4.9 + FFTW 3.3.11) running its own
 * `ffmpeg_analyzer.h`; see `tools/analysis-goldens/README.md`. Tolerances absorb
 * the double→float JNI boundary only.
 */
class AnalysisParityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private val cacheDir = instrumentation.targetContext.cacheDir

    private data class Field(val name: String, val tolerance: Double, val relative: Boolean = false)

    private val fields = listOf(
        Field("loudness_lufs", 0.01),
        Field("loudness_range", 0.01),
        Field("true_peak", 0.01),
        Field("rms", 0.01),
        Field("crest", 0.001),
        Field("spectral_centroid", 0.5),
        Field("spectral_rolloff", 0.5),
        Field("spectral_flatness", 1e-5),
        Field("spectral_flux", 1e-5),
        Field("zcr", 1e-5),
        Field("tempo", 0.05),
        Field("onset_variance", 1e-3, relative = true),
    )

    private fun goldens(): JSONObject =
        JSONObject(context.assets.open("analysis/goldens.json").bufferedReader().readText())

    private fun analyze(file: String, startMs: Long = 0, endMs: Long = -1): FloatArray {
        val copy = File(cacheDir, "parity-$file")
        context.assets.open("analysis/$file").use { input ->
            copy.outputStream().use { output -> input.copyTo(output) }
        }
        return try {
            android.os.ParcelFileDescriptor.open(copy, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                .use { descriptor -> AnalysisBridge.nativeAnalyzeFd(descriptor.fd, startMs, endMs) }
        } finally {
            copy.delete()
        }
    }

    private fun compare(expected: JSONObject, actual: FloatArray, label: String) {
        assertEquals("$label: analyzer version", 4, AnalysisVersion.ANALYZER)
        assertEquals("$label: field count", fields.size, actual.size)
        fields.forEachIndexed { index, field ->
            if (!expected.has(field.name) || expected.isNull(field.name)) {
                // null means the producer measured digital silence (-inf); the app must not invent a number.
                assertTrue(
                    "$label ${field.name}: expected non-finite, got ${actual[index]}",
                    actual[index].isNaN() || actual[index].isInfinite() || actual[index] <= -69f,
                )
                return@forEachIndexed
            }
            val want = expected.getDouble(field.name)
            val got = actual[index].toDouble()
            val delta = if (field.relative) abs(want) * field.tolerance else field.tolerance
            assertEquals("$label ${field.name} (expected $want, got $got)", want, got, delta)
        }
    }

    @Test
    fun everyFormatMatchesTheProducer() {
        val producer = goldens()
        assertTrue("producer revision", producer.getJSONObject("producer").getString("revision").startsWith("d859940c"))
        val cases = producer.getJSONArray("cases")
        assertTrue("at least 15 producer cases", cases.length() >= 15)
        for (index in 0 until cases.length()) {
            val expected = cases.getJSONObject(index)
            val file = expected.getString("file")
            compare(expected, analyze(file), file)
        }
    }

    @Test
    fun fixtureChecksumsMatchTheRecordedProducerCorpus() {
        val checksums = goldens().getJSONObject("checksums")
        for (file in checksums.keys()) {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            context.assets.open("analysis/$file").use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            assertEquals("fixture checksum $file", checksums.getString(file), actual)
        }
    }

    @Test
    fun albumLoudnessMatchesTheProducerMean() {
        val golden = goldens()
        val cases = golden.getJSONArray("cases")
        val byName = (0 until cases.length()).associate { index ->
            val case = cases.getJSONObject(index)
            case.getString("file") to case
        }
        val album = golden.getJSONObject("albumCase")
        val tracks = album.getJSONArray("tracks")
        val analyses = (0 until tracks.length()).map { index ->
            val case = byName.getValue(tracks.getString(index))
            com.amwolfstein.sistrum.player.TrackAnalysis(
                loudnessLufs = case.getDouble("loudness_lufs").toFloat(),
                truePeak = case.getDouble("true_peak").toFloat(),
            )
        }
        val aggregate = com.amwolfstein.sistrum.library.analysis.AlbumAggregate.aggregate(analyses)!!
        val expected = album.getJSONObject("expected")
        assertEquals(expected.getDouble("loudnessLufs"), aggregate.loudnessLufs.toDouble(), 1e-5)
        assertEquals(expected.getDouble("truePeak"), aggregate.truePeak.toDouble(), 1e-5)
        assertEquals(expected.getInt("trackCount"), aggregate.trackCount)
        // Producer album gain is target -18 minus the album mean.
        assertEquals(
            expected.getDouble("replayGainAlbumGain"),
            -18.0 - aggregate.loudnessLufs.toDouble(),
            1e-5,
        )
    }

    @Test
    fun cueSegmentMatchesTheProducer() {
        val segment = goldens().getJSONObject("segmentCase")
        compare(
            segment.getJSONObject("expected"),
            analyze(segment.getString("file"), segment.getLong("startMs"), segment.getLong("endMs")),
            "segment:" + segment.getString("file"),
        )
    }
}
