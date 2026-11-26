package com.amwolfstein.sistrum.sync

import com.amwolfstein.sistrum.library.LocalAlbumRef
import com.amwolfstein.sistrum.library.LocalTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMoveIdentityTest {
    @Test
    fun retainsIdentityForAUniqueContentMatch() {
        val prior = mapOf("local:1" to state("same"))
        assertEquals(mapOf("local:8" to "local:1"), movedTrackIds(listOf(track("local:8", "same")), prior))
    }

    @Test
    fun doesNotMatchAmbiguousDuplicateContent() {
        val old = mapOf("local:1" to state("same"), "local:2" to state("same"))
        assertEquals(emptyMap<String, String>(), movedTrackIds(listOf(track("local:8", "same")), old))
        val duplicateNew = listOf(track("local:8", "same"), track("local:9", "same"))
        assertEquals(emptyMap<String, String>(), movedTrackIds(duplicateNew, mapOf("local:1" to state("same"))))
    }

    @Test
    fun doesNotReplaceIdentityOfAnExistingTrackOrMatchMissingHashes() {
        val prior = mapOf("local:1" to state("same"))
        assertEquals(emptyMap<String, String>(), movedTrackIds(listOf(track("local:1", "same")), prior))
        assertEquals(emptyMap<String, String>(), movedTrackIds(listOf(track("local:8", "")), prior))
    }

    @Test
    fun legacyMoveCandidateIsKeptAcrossRepeatedScansButOrdinaryChangesProceed() {
        val old = state("").copy(
            absolutePath = "/storage/emulated/0/Music/old.mp3",
            title = "Old",
            durationMillis = 1000,
            size = 100,
        )
        val plausible = track("local:8", "digest").copy(title = "Old", durationMillis = 1000, fileSize = 100)
        val different = track("local:9", "digest2").copy(title = "New", durationMillis = 2000, fileSize = 200)
        val prior = mapOf("local:1" to old)
        assertEquals(setOf("local:1"), legacyTracksToPreserve(prior, listOf(plausible), MediaScanFilter()))
        val secondPrior = prior + ("local:8" to state("digest"))
        assertEquals(setOf("local:1"), legacyTracksToPreserve(secondPrior, listOf(plausible), MediaScanFilter()))
        assertEquals(emptySet<String>(), legacyTracksToPreserve(prior, listOf(different), MediaScanFilter()))
        val excluded = MediaScanFilter(mode = MediaScanMode.Whitelist, whitelistedFolders = setOf("/storage/emulated/0/Other"))
        assertEquals(emptySet<String>(), legacyTracksToPreserve(prior, listOf(plausible), excluded))
    }

    @Test
    fun incompleteMediaStoreRowsAbortBeforeReplacement() {
        assertThrows(IllegalArgumentException::class.java) { requireCompleteMediaRow(1, null, "Title") }
        assertThrows(IllegalArgumentException::class.java) { requireCompleteMediaRow(null, "/path", "Title") }
        assertThrows(IllegalArgumentException::class.java) { requireCompleteMediaRow(1, "/path", " ") }
        assertEquals(Triple(1L, "/path", "Title"), requireCompleteMediaRow(1, "/path", "Title"))
        assertTrue(isNewIncompleteMediaRow(2, null, "Title", setOf(1)))
        assertFalse(isNewIncompleteMediaRow(1, null, "Title", setOf(1)))
        assertFalse(isNewIncompleteMediaRow(null, null, "Title", setOf(1)))
    }

    @Test
    fun jobCompletionRunsOnceOnUserCancelAndNeverAfterPlatformStop() {
        var finished = 0
        val userCancel = ScanJobCompletion { finished++ }
        userCancel.completed()
        userCancel.completed()
        assertEquals(1, finished)

        val stopped = ScanJobCompletion { finished++ }
        stopped.platformStopped()
        stopped.completed()
        assertEquals(1, finished)
    }

    private fun state(hash: String) = PriorTrackScanState(
        identityHash = "", schemaVersion = 0, year = 0, releaseDate = "", bpm = 0,
        label = "", isrc = "", copyright = "", contentHash = hash,
    )

    private fun track(id: String, hash: String) = LocalTrack(
        id = id,
        title = id,
        artists = emptyList(),
        album = LocalAlbumRef(id = "album", title = "album"),
        contentHash = hash,
    )
}
