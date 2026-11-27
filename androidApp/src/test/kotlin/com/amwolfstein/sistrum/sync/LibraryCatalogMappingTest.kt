package com.amwolfstein.sistrum.sync

import com.amwolfstein.sistrum.library.db.AlbumEntity
import com.amwolfstein.sistrum.library.db.ArtistEntity
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.LocalAlbumRow
import com.amwolfstein.sistrum.library.db.LocalArtistRow
import com.amwolfstein.sistrum.library.db.LocalTrackRow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCatalogMappingTest {
    private fun row(item: LibraryItemEntity = item()) = LocalTrackRow(
        item = item,
        artistNames = "Portishead\u0001Beth Gibbons",
        albumArtistNames = "Portishead",
        genreNames = "Trip Hop\u0001Electronic",
        composerNames = "Geoff Barrow",
    )

    private fun item() = LibraryItemEntity(
        id = "track_1",
        documentKey = "doc_1",
        fileUri = "content://music/1.flac",
        sourceId = "s",
        albumId = "album_1",
        title = "Roads",
        sortTitle = "Roads",
        albumTitle = "Dummy",
        albumSortTitle = "Dummy",
        durationSeconds = 305,
        fileSize = 42_000_000,
        bitrate = 900,
        sampleRate = 44_100,
        bitDepth = 16,
        codec = "flac",
        format = "flac",
        year = 1994,
        releaseDate = "1994-08-22",
        isFavorite = true,
        rating = 4,
        artworkKey = "abc",
    )

    @Test
    fun namesSplitOnTheControlSeparatorOnly() {
        assertEquals(listOf("Portishead", "Beth Gibbons"), splitCatalogNames("Portishead\u0001Beth Gibbons"))
        assertEquals(listOf("A; B"), splitCatalogNames("A; B"))
        assertEquals(emptyList<String>(), splitCatalogNames(null))
        assertEquals(emptyList<String>(), splitCatalogNames("\u0001  \u0001"))
    }

    @Test
    fun metadataJsonCarriesTheKeysScreensAndPlayerRead() {
        val json = JsonObject(
            kotlinx.serialization.json.Json.parseToJsonElement(localTrackMetadataJson(row())).jsonObject,
        )
        assertEquals("Roads", json["title"]?.jsonPrimitive?.content)
        assertEquals("Dummy", json["album"]?.jsonPrimitive?.content)
        assertEquals("4", json["rating"]?.jsonPrimitive?.content)
        assertEquals("true", json["is_favorite"]?.jsonPrimitive?.content)
        assertEquals("305", json["duration"]?.jsonPrimitive?.content)
        assertEquals("1994-08-22", json["release_date"]?.jsonPrimitive?.content)
        val artists = json["artists"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Portishead", "Beth Gibbons"), artists.map { it["name"]!!.jsonPrimitive.content })
        assertEquals("artist", artists.first()["id"]!!.jsonPrimitive.content.substringBefore('_'))
        assertEquals(2, json["genres"]!!.jsonArray.size)
        assertEquals(1, json["composers"]!!.jsonArray.size)
    }

    @Test
    fun trackMappingUsesLocalIdentityAndArtwork() {
        val track = row().toLibraryTrack("/files/artwork/ab/abc.jpg")
        assertEquals("track_1", track.id)
        assertEquals("Roads", track.title)
        assertEquals("Portishead, Beth Gibbons", track.artists)
        assertEquals("Dummy", track.album)
        assertEquals("album_1", track.albumId)
        assertEquals("/files/artwork/ab/abc.jpg", track.artworkPath)
        assertEquals(4, track.rating)
        assertNull(track.audioPath)
    }

    @Test
    fun albumAndArtistMappingKeepCountsAndSortNames() {
        val album = LocalAlbumRow(
            album = AlbumEntity("album_1", "Dummy", "Dummy", "dummy", 1994, "2021 Sony", artworkKey = "k"),
            artistName = "Portishead",
            trackCount = 11,
        ).toLibraryAlbum("/a.jpg", fallbackArtist = "")
        assertEquals("Portishead", album.artist)
        assertEquals(1994, album.year)
        assertEquals("/a.jpg", album.artworkPath)

        val artist = LocalArtistRow(
            artist = ArtistEntity("artist_1", "Portishead", "Portishead", "portishead", artworkKey = "k2"),
            trackCount = 11,
        ).toLibraryArtist("/b.jpg")
        assertEquals("artist_1", artist.id)
        assertEquals("/b.jpg", artist.artworkPath)
        assertTrue(artist.sortName.isNotBlank())
    }
}
