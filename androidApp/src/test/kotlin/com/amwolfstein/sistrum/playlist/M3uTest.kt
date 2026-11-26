package com.amwolfstein.sistrum.playlist

import com.amwolfstein.sistrum.sync.LibraryTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class M3uTest {
    private val tracks = listOf(
        LibraryTrack("1", "One", "Artist", audioPath = "/music/Arabic/أغنية.mp3"),
        LibraryTrack("2", "Two", "Artist", audioPath = "/music/album/song.mp3"),
        LibraryTrack("3", "Three", "Artist", audioPath = "/music/other/song.mp3"),
    )

    @Test fun utf8RoundTripMaintainsOrder() {
        val result = importM3u(exportM3u(listOf("2", "1"), tracks).content, tracks)
        assertEquals(listOf("2", "1"), result.trackIds)
        assertEquals(emptyList<String>(), result.unresolved)
    }

    @Test fun exportReportsMissingPathsAndUsesResolvableRelativePaths() {
        val exported = exportM3u(listOf("2", "missing", "1"), tracks, "/music/lists")
        assertEquals("#EXTM3U\n../album/song.mp3\n../Arabic/أغنية.mp3\n", exported.content)
        assertEquals(listOf("missing"), exported.unresolved)
        val imported = importM3u(exported.content, tracks, "/music/lists")
        assertEquals(listOf("2", "1"), imported.trackIds)
    }

    @Test fun sameDirectoryHashAndWhitespaceNamesRoundTrip() {
        val special = listOf(
            LibraryTrack("hash", "Hash", "", audioPath = "/Music/#1.mp3"),
            LibraryTrack("leading", "Leading", "", audioPath = "/Music/ leading.mp3"),
            LibraryTrack("trailing", "Trailing", "", audioPath = "/Music/trailing.mp3 "),
        )
        val exported = exportM3u(special.map { it.id }, special, "/Music")
        org.junit.Assert.assertTrue(exported.content.contains("./#1.mp3"))
        org.junit.Assert.assertTrue(exported.content.contains("./ leading.mp3"))
        org.junit.Assert.assertTrue(exported.content.contains("trailing.mp3%20"))
        assertEquals(special.map { it.id }, importM3u(exported.content, special, "/Music").trackIds)
    }

    @Test fun duplicateEntriesAreReportedForTheExistingPlaylistModel() {
        val result = importM3u("/music/album/song.mp3\n/music/album/song.mp3\n", tracks)
        assertEquals(listOf("2"), result.trackIds)
        assertEquals(1, result.duplicates)
    }

    @Test fun relativePathsResolveAgainstPlaylistDirectory() {
        val result = importM3u("../Arabic/أغنية.mp3", tracks, "/music/album")
        assertEquals(listOf("1"), result.trackIds)
    }

    @Test fun relativePathsResolveOnlyWhenUnique() {
        val result = importM3u("#EXTM3U\nArabic/أغنية.mp3\nsong.mp3\nmissing.mp3\n", tracks)
        assertEquals(listOf("1"), result.trackIds)
        assertEquals(listOf("song.mp3", "missing.mp3"), result.unresolved)
    }
}
