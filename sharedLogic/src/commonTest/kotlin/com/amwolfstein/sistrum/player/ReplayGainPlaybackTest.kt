package com.amwolfstein.sistrum.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayGainPlaybackTest {
    @Test fun disabledAndMissingTagsHaveNoGain() {
        val tags = mapOf("REPLAYGAIN_TRACK_GAIN" to listOf("-5.00 dB"))
        assertEquals(0f, replayGainPlaybackDb(tags, ReplayGainMode.Off, 0f, true))
        assertEquals(0f, replayGainPlaybackDb(emptyMap(), ReplayGainMode.Track, 6f, true))
    }
    @Test fun trackAlbumFallbackAndPeakLimit() {
        val tags = mapOf("REPLAYGAIN_TRACK_GAIN" to listOf("+5.00 dB"), "REPLAYGAIN_TRACK_PEAK" to listOf("1.0"))
        assertTrue(kotlin.math.abs(replayGainPlaybackDb(tags, ReplayGainMode.Track, 0f, true)) < 0.001f)
        assertEquals(7f, replayGainPlaybackDb(tags, ReplayGainMode.Album, 2f, false))
        assertEquals(-3f, replayGainPlaybackDb(tags + ("REPLAYGAIN_ALBUM_GAIN" to listOf("-3 dB")), ReplayGainMode.Album, 0f, false))
    }
}
