package com.amwolfstein.sistrum.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amwolfstein.sistrum.library.db.AlbumEntity
import com.amwolfstein.sistrum.library.db.ArtistEntity
import com.amwolfstein.sistrum.library.db.GenreEntity
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import com.amwolfstein.sistrum.library.db.TrackArtistEntity
import com.amwolfstein.sistrum.library.db.TrackGenreEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalLibraryCatalogTest {
    private lateinit var database: LibraryDatabase
    private lateinit var catalog: LocalLibraryCatalog

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        catalog = LocalLibraryCatalog(context, database, legacy = null)
        val dao = database.libraryDao()
        dao.upsertSource(LibrarySourceEntity("s", "content://s", "Music", createdAt = 1L))
        dao.upsertArtists(listOf(ArtistEntity("artist_1", "Portishead", "Portishead", "portishead")))
        dao.upsertAlbums(listOf(AlbumEntity("album_1", "Dummy", "Dummy", "dummy", 1994, primaryAlbumArtistId = "artist_1")))
        dao.upsertGenres(listOf(GenreEntity("genre_1", "Trip Hop", "trip hop")))
        dao.upsertItems(
            listOf(
                LibraryItemEntity(
                    id = "track_1", documentKey = "doc_1", fileUri = "content://m/1.mp3", sourceId = "s",
                    albumId = "album_1", title = "Roads", sortTitle = "Roads", albumTitle = "Dummy",
                    albumSortTitle = "Dummy", durationSeconds = 300, rating = 3, isFavorite = true,
                ),
                LibraryItemEntity(
                    id = "track_2", documentKey = "doc_2", fileUri = "content://m/2.mp3", sourceId = "s",
                    albumId = "album_1", title = "Glory Box", sortTitle = "Glory Box", albumTitle = "Dummy",
                    albumSortTitle = "Dummy", durationSeconds = 305,
                ),
            ),
        )
        dao.upsertTrackArtists(listOf(TrackArtistEntity("track_1", "artist_1", 0), TrackArtistEntity("track_2", "artist_1", 0)))
        dao.upsertTrackGenres(listOf(TrackGenreEntity("track_1", "genre_1"), TrackGenreEntity("track_2", "genre_1")))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun tracksExposeArtistsAlbumAndRating() = runBlocking {
        val tracks = catalog.tracks.first()
        assertEquals(setOf("track_1", "track_2"), tracks.map { it.id }.toSet())
        val roads = tracks.first { it.id == "track_1" }
        assertEquals("Portishead", roads.artists)
        assertEquals("Dummy", roads.album)
        assertEquals(3, roads.rating)
        assertTrue(roads.metadataJson.contains("Portishead"))
        assertEquals("content://m/1.mp3", roads.audioPath)
    }

    @Test
    fun albumsArtistsGenresComeFromLocalRows() = runBlocking {
        val album = catalog.albums.first().single()
        assertEquals("album_1", album.id)
        assertEquals("Portishead", album.artist)
        assertEquals(1994, album.year)
        val artist = catalog.artists.first().single()
        assertEquals("artist_1", artist.id)
        assertEquals("Portishead", artist.name)
        assertEquals("genre_1", catalog.genres.first().single().id)
        assertTrue(catalog.composers.first().isEmpty())
    }

    @Test
    fun ratingAndFavoriteWritesAreLocalAndBounded() = runBlocking {
        catalog.setRating("track_1", 5)
        assertEquals(5, database.libraryDao().itemById("track_1")?.rating)
        catalog.setRating("track_1", null)
        assertNull(database.libraryDao().itemById("track_1")?.rating)
        catalog.setFavorite("track_1", false)
        assertEquals(false, database.libraryDao().itemById("track_1")?.isFavorite)

        val rejected = runCatching { catalog.setRating("track_1", 6) }.isFailure
        assertTrue(rejected)
    }

    @Test
    fun searchCandidatesDoNotRestrictLocalSearch() = runBlocking {
        assertTrue(catalog.searchCandidates("road").first().isEmpty())
        assertNull(catalog.analysis("track_1"))
        assertTrue(catalog.playlists.first().isEmpty())
        assertTrue(catalog.lastListenedAt.first().isEmpty())
    }
}
