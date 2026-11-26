package com.amwolfstein.sistrum.playlist

import com.amwolfstein.sistrum.sync.LibraryPlaylist
import com.amwolfstein.sistrum.sync.LibraryTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartPlaylistsTest {
    @Test fun rulesEvaluateCurrentTracksInLibraryOrder() {
        val playlist = LibraryPlaylist("p", "Name", emptyList(), "{}")
        val tracks = listOf(LibraryTrack("1", "One", "Mohamed"), LibraryTrack("2", "Two", "Other"))
        assertEquals(listOf("1"), applySmartRules(listOf(playlist), tracks, mapOf("p" to SmartRule("artist", "MOHAMED"))).single().trackIds)
        assertEquals(emptyList<String>(), applySmartRules(listOf(playlist), tracks.drop(1), mapOf("p" to SmartRule("artist", "MOHAMED"))).single().trackIds)
    }
}
