package com.amwolfstein.sistrum.library.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisTagCodecTest {
    @Test
    fun formatsMapToTheirStandardKeys() {
        val mp3 = AnalysisTagCodec.tagsFor(AnalysisTagCodec.Format.MP3, -7.0, -0.3)
        assertTrue(mp3.containsKey(AnalysisTagCodec.TRACK_GAIN))
        assertTrue(mp3.containsKey(AnalysisTagCodec.TRACK_PEAK))
        assertEquals("-7.00 dB", mp3.getValue(AnalysisTagCodec.TRACK_GAIN))

        val vorbis = AnalysisTagCodec.tagsFor(AnalysisTagCodec.Format.VORBIS, -7.0, -0.3)
        assertEquals(mp3.keys, vorbis.keys)

        val opus = AnalysisTagCodec.tagsFor(AnalysisTagCodec.Format.OPUS, -7.0, -0.3)
        assertEquals(setOf(AnalysisTagCodec.R128_TRACK_GAIN), opus.keys)
        assertEquals("-1792", opus.getValue(AnalysisTagCodec.R128_TRACK_GAIN))

        val m4a = AnalysisTagCodec.tagsFor(AnalysisTagCodec.Format.M4A, -7.0, -0.3)
        assertTrue(m4a.containsKey("----:com.apple.iTunes:REPLAYGAIN_TRACK_GAIN"))
    }

    @Test
    fun existingKeysAreNeverOverwritten() {
        val tags = AnalysisTagCodec.tagsFor(
            AnalysisTagCodec.Format.VORBIS,
            -7.0,
            -0.3,
            albumGainDb = -6.0,
            existingKeys = setOf(AnalysisTagCodec.TRACK_GAIN),
        )
        assertFalse(tags.containsKey(AnalysisTagCodec.TRACK_GAIN))
        assertTrue(tags.containsKey(AnalysisTagCodec.TRACK_PEAK))
        assertTrue(tags.containsKey(AnalysisTagCodec.ALBUM_GAIN))
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
    fun unknownFormatsProduceNoTagsAndExtensionMappingHandlesCase() {
        assertTrue(AnalysisTagCodec.tagsFor(AnalysisTagCodec.Format.OTHER, -7.0, -0.3).isEmpty())
        assertEquals(AnalysisTagCodec.Format.OPUS, AnalysisTagCodec.formatOf("OPUS"))
        assertEquals(AnalysisTagCodec.Format.M4A, AnalysisTagCodec.formatOf("m4a"))
        assertEquals(AnalysisTagCodec.Format.VORBIS, AnalysisTagCodec.formatOf("flac"))
        assertEquals(AnalysisTagCodec.Format.OTHER, AnalysisTagCodec.formatOf("wav"))
    }
}
