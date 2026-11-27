package com.amwolfstein.sistrum.library.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkPrecedenceTest {
    private fun candidate(id: String, date: String?, key: String? = "art_$id") =
        TrackArtworkCandidate(trackId = id, releaseDate = date, artworkKey = key)

    @Test
    fun newestReleaseWins() {
        val key = ArtworkPrecedence.selectTrackFallback(
            listOf(
                candidate("a", "2019"),
                candidate("b", "2021-05-02"),
                candidate("c", "2020-12"),
            ),
        )
        assertEquals("art_b", key)
    }

    @Test
    fun undatedEntriesRankAfterEveryDatedEntry() {
        val key = ArtworkPrecedence.selectTrackFallback(
            listOf(
                candidate("undated", null),
                candidate("old", "1999"),
            ),
        )
        assertEquals("art_old", key)
    }

    @Test
    fun tiesAreDeterministicRegardlessOfInputOrder() {
        val forward = ArtworkPrecedence.selectTrackFallback(
            listOf(candidate("track-b", "2022-01-01"), candidate("track-a", "2022-01-01")),
        )
        val reversed = ArtworkPrecedence.selectTrackFallback(
            listOf(candidate("track-a", "2022-01-01"), candidate("track-b", "2022-01-01")),
        )
        assertEquals("art_track-a", forward)
        assertEquals(forward, reversed)
    }

    @Test
    fun partialDatesCompareByAvailableParts() {
        val key = ArtworkPrecedence.selectTrackFallback(
            listOf(candidate("year", "2022"), candidate("month", "2022-03")),
        )
        assertEquals("art_month", key)
    }

    @Test
    fun entriesWithoutArtworkAreIgnored() {
        val key = ArtworkPrecedence.selectTrackFallback(
            listOf(candidate("newer", "2024", null), candidate("older", "1990", "art_older")),
        )
        assertEquals("art_older", key)
        assertNull(ArtworkPrecedence.selectTrackFallback(emptyList()))
        assertNull(ArtworkPrecedence.selectTrackFallback(listOf(candidate("x", "2024", " "))))
    }

    @Test
    fun parsesTagDateFormats() {
        assertEquals(ReleaseDateKey(2021, 5, 2), ArtworkPrecedence.releaseDateKey("2021-05-02"))
        assertEquals(ReleaseDateKey(2021, 5, 0), ArtworkPrecedence.releaseDateKey("2021-05"))
        assertEquals(ReleaseDateKey(2021, 0, 0), ArtworkPrecedence.releaseDateKey("2021"))
        assertEquals(ReleaseDateKey(2021, 5, 2), ArtworkPrecedence.releaseDateKey(" 2021/05/02 "))
        assertNull(ArtworkPrecedence.releaseDateKey(null))
        assertNull(ArtworkPrecedence.releaseDateKey("unknown"))
    }
}
