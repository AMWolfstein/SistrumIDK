package com.amwolfstein.sistrum.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryArtistMapperTest {
    @Test
    fun groupsNormalizedManifestArtistsAndResolvesArtworkAssets() {
        val artists = libraryArtistsFrom(
            tracks = listOf(
                row("""{"artists":[{"id":"a","name":"Artist A","sort_name":"A Artist","artwork_key":"artist-a","created_at":"2026-02-01T00:00:00Z"},{"id":"b","name":"Artist B","sort_name":"B Artist","artwork_key_local":"artist-b"}]}"""),
                row("""{"artists":[{"id":"a","name":"Artist A","created_at":"2026-01-01T00:00:00Z"}]}"""),
            ),
            artworkPaths = mapOf("artist-a" to "artwork/a.jpg"),
        )

        assertEquals(listOf("a", "b"), artists.map { it.id })
        assertEquals("artwork/a.jpg", artists.first().artworkPath)
        assertEquals("A Artist", artists.first().sortName)
        assertEquals("2026-01-01T00:00:00Z", artists.first().createdAt)
        assertNull(artists.last().artworkPath)
    }

    @Test
    fun ignoresMalformedOrIncompleteArtistEntries() {
        val artists = libraryArtistsFrom(
            tracks = listOf(row("""{"artists":[{"name":"Missing id"},"bad"]}"""), row("not json")),
            artworkPaths = emptyMap(),
        )

        assertEquals(emptyList<LibraryArtist>(), artists)
    }

    @Test
    fun fallsBackToStagedArtworkByArtistIdWhenNoManifestKeyResolves() {
        val artists = libraryArtistsFrom(
            tracks = listOf(row("""{"artists":[{"id":"a","name":"Artist A"}]}""")),
            artworkPaths = mapOf("a" to "artist-artwork/staged.jpg"),
        )

        assertEquals("artist-artwork/staged.jpg", artists.single().artworkPath)
    }

    @Test
    fun keepsUserSelectedArtworkOverManifestArtwork() {
        val artists = libraryArtistsFrom(
            tracks = listOf(row("""{"artists":[{"id":"a","name":"Artist A","artwork_key":"artist-a"}]}""")),
            artworkPaths = mapOf("a" to "artist-artwork/staged.jpg", "artist-a" to "artwork/a.jpg"),
        )

        assertEquals("artist-artwork/staged.jpg", artists.single().artworkPath)
    }

    @Test
    fun localArtistArtworkKeepsUserSelectedArtworkFirst() {
        val tracks = listOf(row("""{"artists":[{"id":"a","name":"Artist A"}]}""").copy(id = "local:1"))
        val localArt = "artwork/latest-song.jpg"
        assertEquals(localArt, libraryArtistsFrom(tracks, mapOf("artist-local:a" to localArt)).single().artworkPath)
        assertEquals(
            "artist-artwork/custom.jpg",
            libraryArtistsFrom(
                tracks,
                mapOf("artist-local:a" to localArt, "a" to "artist-artwork/custom.jpg"),
            ).single().artworkPath,
        )
    }

    @Test
    fun validatesReleaseDateAndUsesYearOrDateAddedForFallback() {
        assertEquals("2024-03-01", artistArtworkDate("2024-03", 0, "2025-02-01"))
        assertEquals("2023-01-01", artistArtworkDate("invalid", 2023, "2025-02-01"))
        assertEquals("2025-02-01", artistArtworkDate("2024-19-33", 0, "2025-02-01"))
    }

    @Test
    fun groupsAlbumsByManifestIdAndResolvesAlbumArtwork() {
        val albums = libraryAlbumsFrom(
            tracks = listOf(
                row("""{"album":{"id":"album-a","title":"Album A","sort_title":"A Album","year":2001,"copyright":"© 2001 Example Records","artwork_key":"album-art","created_at":"2026-02-01T00:00:00Z"},"album_artists":[{"name":"Artist A","sort_name":"A Artist"}]}"""),
                row("""{"album":{"id":"album-a","title":"Album A","artwork_key":"album-art"},"album_artists":[{"name":"Artist A"}]}"""),
                row("""{"album":{"id":"album-b","title":"Album B"},"album_artists":[]}"""),
            ),
            artworkPaths = mapOf("album-art" to "artwork/album-a.jpg"),
        )

        assertEquals(listOf("album-a", "album-b"), albums.map { it.id })
        assertEquals("Artist A", albums.first().artist)
        assertEquals("A Album", albums.first().sortTitle)
        assertEquals("A Artist", albums.first().sortArtist)
        assertEquals("artwork/album-a.jpg", albums.first().artworkPath)
        assertEquals("2026-02-01T00:00:00Z", albums.first().createdAt)
        assertEquals(2001, albums.first().year)
        assertEquals("© 2001 Example Records", albums.first().copyright)
        assertEquals("", albums.last().artist)
    }

    @Test
    fun ignoresTracksWithoutACompleteAlbum() {
        val albums = libraryAlbumsFrom(
            tracks = listOf(
                row("""{"album":{"title":"Missing id"}}"""),
                row("""{"album":{"id":"missing-title","title":" "}}"""),
                row("not json"),
            ),
            artworkPaths = emptyMap(),
        )

        assertEquals(emptyList<LibraryAlbum>(), albums)
    }

    @Test
    fun fallsBackToTrackArtistsWhenTheManifestOmitsAlbumArtists() {
        val albums = libraryAlbumsFrom(
            tracks = listOf(
                row("""{"album":{"id":"album-a","title":"Album A"},"artists":[{"name":"Artist A"}]}"""),
            ),
            artworkPaths = emptyMap(),
        )

        assertEquals("Artist A", albums.single().artist)
    }

    private fun row(rawJson: String) = LibraryTrackRow(
        id = "track",
        title = "Track",
        artists = "",
        album = "",
        artworkKey = null,
        playCount = 0,
        createdAt = "",
        artworkPath = null,
        audioPath = null,
        rawJson = rawJson,
    )
}
