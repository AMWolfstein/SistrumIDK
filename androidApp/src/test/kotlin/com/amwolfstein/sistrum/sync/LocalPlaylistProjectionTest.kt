package com.amwolfstein.sistrum.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.amwolfstein.sistrum.sync.PlaylistMutation
import com.amwolfstein.sistrum.sync.PlaylistMutationOperation
import com.amwolfstein.sistrum.sync.PlaylistMutationPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPlaylistProjectionTest {
    @Test fun artworkReferencedByLocalEditsIsRetained() {
        val hash = "a".repeat(64)
        val pending = listOf(mutation("1", PlaylistMutationOperation.SET_ARTWORK, PlaylistMutationPayload(artworkSha256 = hash)))
        assertEquals(setOf(hash), playlistArtworkInUse(emptyList(), emptyList(), pending))
        assertEquals(emptySet<String>(), playlistArtworkInUse(emptyList(), emptyList(), emptyList()))
    }

    @Test fun pendingRemoveTrackIsProjectedImmediately() {
        val playlist = LibraryPlaylist("p", "Original", listOf("a", "b", "c"), "{}")

        val projected = applyPendingPlaylistMutations(
            listOf(playlist),
            listOf(mutation("1", PlaylistMutationOperation.REMOVE_TRACK, PlaylistMutationPayload(trackId = "b"))),
        )

        assertEquals(listOf("a", "c"), projected.single().trackIds)
    }

    @Test fun pendingEditArtworkAndDeleteAreProjectedImmediately() {
        val playlist = LibraryPlaylist("p", "Original", listOf("a"), "{}")
        val edited = applyPendingPlaylistMutations(
            listOf(playlist),
            listOf(
                mutation("1", PlaylistMutationOperation.UPDATE, PlaylistMutationPayload(name = "Edited")),
                mutation("2", PlaylistMutationOperation.SET_ARTWORK, PlaylistMutationPayload(artworkSha256 = "a".repeat(64))),
            ),
        ).single()

        assertEquals("Edited", edited.name)
        assertEquals("a".repeat(64), ((LibraryJson.json.parseToJsonElement(edited.metadataJson) as JsonObject)["playlist"] as JsonObject)["artwork_key"]?.let { (it as JsonPrimitive).content })
        assertEquals(emptyList<LibraryPlaylist>(), applyPendingPlaylistMutations(listOf(edited), listOf(mutation("3", PlaylistMutationOperation.DELETE, PlaylistMutationPayload()))))
    }

    private fun mutation(id: String, operation: PlaylistMutationOperation, payload: PlaylistMutationPayload) = PlaylistMutation(id, "p", operation, id.toLong(), payload)
}
