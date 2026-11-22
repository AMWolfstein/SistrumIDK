package me.misa198.airmedy.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.misa198.airmedy.sync.PlaylistMutation
import me.misa198.airmedy.sync.PlaylistMutationOperation
import me.misa198.airmedy.sync.PlaylistMutationPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPlaylistProjectionTest {
    @Test fun artworkReferencedByLocalEditsIsRetained() {
        val hash = "a".repeat(64)
        assertEquals(emptyList<String>(), unusedPlaylistArtworkHashes(listOf(hash), listOf(mutation("1", PlaylistMutationOperation.SET_ARTWORK, PlaylistMutationPayload(artworkSha256 = hash))), emptyList()))
        assertEquals(listOf(hash), unusedPlaylistArtworkHashes(listOf(hash), emptyList(), emptyList()))
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
