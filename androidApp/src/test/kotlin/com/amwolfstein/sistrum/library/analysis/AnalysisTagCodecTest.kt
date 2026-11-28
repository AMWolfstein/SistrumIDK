package com.amwolfstein.sistrum.library.analysis

import com.amwolfstein.sistrum.library.db.TrackAnalysisEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisTagCodecTest {
    private fun analysis(loudness: Double, peak: Double) = TrackAnalysisEntity(
        trackId = "t",
        loudnessLufs = loudness,
        loudnessRange = 5.0,
        truePeak = peak,
        rms = -20.0,
        crest = 1.4,
        spectralCentroid = 1000.0,
        spectralRolloff = 2000.0,
        spectralFlatness = 0.1,
        spectralFlux = 0.01,
        zcr = 0.05,
        tempo = 120.0,
        onsetVariance = 0.5,
        energy = 0.6,
        danceability = 0.5,
        brightness = 0.4,
        ffmpegVersion = 1,
        aubioVersion = 1,
        analyzerVersion = 4,
        moodVersion = 1,
        audioFingerprint = "pcm-sha256-v1:abc",
        status = "COMPLETED",
    )

    @Test
    fun gainIsReferenceMinusMeasuredLoudness() {
        val values = AnalysisTagCodec.tagsFor(AnalysisTagCodec.Format.MP3, analysis(-7.0, -0.3))
        assertEquals("-11.00 dB", values.getValue(AnalysisTagCodec.TRACK_GAIN))
        assertTrue(values.containsKey(AnalysisTagCodec.TRACK_PEAK))
    }

    @Test
    fun formatsMapToTheirStandardKeys() {
        val mp3 = AnalysisTagCodec.tagsFor(AnalysisTagCodec.Format.MP3, analysis(-7.0, -0.3))
        val vorbis = AnalysisTagCodec.tagsFor(AnalysisTagCodec.Format.VORBIS, analysis(-7.0, -0.3))
        assertEquals(mp3.keys, vorbis.keys)

        val opus = AnalysisTagCodec.tagsFor(AnalysisTagCodec.Format.OPUS, analysis(-7.0, -0.3))
        assertFalse(opus.containsKey(AnalysisTagCodec.TRACK_GAIN))
        // R128 target is -23 LUFS: (-23) - (-7) = -16 dB, in Q7.8 units.
        assertEquals("-4096", opus.getValue(AnalysisTagCodec.R128_TRACK_GAIN))

        val m4a = AnalysisTagCodec.tagsFor(AnalysisTagCodec.Format.M4A, analysis(-7.0, -0.3))
        assertTrue(m4a.containsKey("----:com.apple.iTunes:REPLAYGAIN_TRACK_GAIN"))
    }

    @Test
    fun everyAnalysisFieldAndVersionIsEmbedded() {
        val tags = AnalysisTagCodec.tagsFor(AnalysisTagCodec.Format.VORBIS, analysis(-7.0, -0.3))
        AnalysisTagCodec.ANALYSIS_KEYS.forEach { key ->
            assertTrue("missing $key", tags.containsKey(key))
        }
        assertEquals("4", tags[AnalysisTagCodec.ANALYSIS_VERSION])
        assertEquals("pcm-sha256-v1:abc", tags[AnalysisTagCodec.FINGERPRINT])
        assertEquals("-7.000000000", tags[AnalysisTagCodec.LUFS])
    }

    @Test
    fun existingKeysAreNeverOverwritten() {
        val tags = AnalysisTagCodec.tagsFor(
            format = AnalysisTagCodec.Format.VORBIS,
            analysis = analysis(-7.0, -0.3),
            albumLoudnessLufs = -12.0,
            existingKeys = setOf(AnalysisTagCodec.TRACK_GAIN),
        )
        assertFalse(tags.containsKey(AnalysisTagCodec.TRACK_GAIN))
        assertTrue(tags.containsKey(AnalysisTagCodec.TRACK_PEAK))
        // Album target -18 minus album loudness -12 = -6 dB.
        assertEquals("-6.00 dB", tags[AnalysisTagCodec.ALBUM_GAIN])
    }

    @Test
    fun peakAndGainFormattingIsStable() {
        assertEquals("0.977237", AnalysisTagCodec.linearPeak(-0.2))
        assertEquals("1.000000", AnalysisTagCodec.linearPeak(0.0))
        assertEquals("0.000000", AnalysisTagCodec.linearPeak(-200.0))
        assertEquals("-18.00 dB", AnalysisTagCodec.formatGain(-18.0))
        assertEquals("-13.00 dB", AnalysisTagCodec.formatGain(-13.0))
    }

    @Test
    fun embeddedAnalysisRoundTripsAndRejectsStaleOrForeignData() {
        val source = analysis(-7.0, -0.3)
        val tags = AnalysisTagCodec.embeddedAnalysis(source).mapValues { listOf(it.value) }
        val restored = AnalysisTagCodec.fromTags(tags, source.audioFingerprint)!!
        assertEquals(source.loudnessLufs!!, restored.loudnessLufs!!, 1e-9)
        assertEquals(source.energy!!, restored.energy!!, 1e-9)
        assertEquals(source.audioFingerprint, restored.audioFingerprint)
        assertEquals(AnalysisVersion.ANALYZER, restored.analyzerVersion)

        assertEquals(null, AnalysisTagCodec.fromTags(tags, "pcm-sha256-v1:other"))
        assertEquals(null, AnalysisTagCodec.fromTags(tags - AnalysisTagCodec.LUFS, source.audioFingerprint))
        assertEquals(
            null,
            AnalysisTagCodec.fromTags(
                tags + (AnalysisTagCodec.ANALYSIS_VERSION to listOf("3")),
                source.audioFingerprint,
            ),
        )
    }

    @Test
    fun unknownFormatsGetAnalysisTagsButNoStandardGain() {
        val tags = AnalysisTagCodec.tagsFor(AnalysisTagCodec.Format.OTHER, analysis(-7.0, -0.3))
        assertTrue(tags.containsKey(AnalysisTagCodec.LUFS))
        assertFalse(tags.containsKey(AnalysisTagCodec.TRACK_GAIN))
        assertEquals(AnalysisTagCodec.Format.OPUS, AnalysisTagCodec.formatOf("OPUS"))
        assertEquals(AnalysisTagCodec.Format.M4A, AnalysisTagCodec.formatOf("m4a"))
        assertEquals(AnalysisTagCodec.Format.VORBIS, AnalysisTagCodec.formatOf("flac"))
        assertEquals(AnalysisTagCodec.Format.OTHER, AnalysisTagCodec.formatOf("wav"))
    }
}
