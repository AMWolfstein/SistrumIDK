package com.amwolfstein.sistrum.sync

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalResplitTest {
    @Test
    fun rebuildsReferencesFromRawTagsWithoutChangingTrackIdentity() {
        val track = track(
            """{"raw_artist":"A; B","raw_album_artist":"A","raw_composer":"C; D","raw_genres":["Pop; Rock"],"artists":[],"album":{"id":"album"}}""",
        )
        val result = resplitLocalTrack(
            track,
            TagSeparatorSettings(delimiters = listOf(";"), genreDelimiters = listOf(";")),
        )!!
        val root = LibraryJson.json.parseToJsonElement(result.rawJson).jsonObject
        assertEquals("local:42", result.trackId)
        assertEquals(listOf("A", "B"), root.getValue("artists").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        assertEquals(listOf("C", "D"), root.getValue("composers").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        assertEquals(listOf("Pop", "Rock"), root.getValue("genres").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
    }

    @Test
    fun oldDocumentsWithoutRawFieldsRequestBackfill() {
        assertNull(resplitLocalTrack(track("""{"artists":[]}"""), TagSeparatorSettings()))
    }

    private fun track(rawJson: String) = SyncTrackEntity(
        planId = "plan",
        trackId = "local:42",
        title = "Title",
        artists = "A",
        album = "Album",
        artworkKey = null,
        rawJson = rawJson,
    )
}
