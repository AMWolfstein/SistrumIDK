package com.amwolfstein.sistrum.library.metadata

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amwolfstein.sistrum.library.db.AlbumEntity
import com.amwolfstein.sistrum.library.db.ArtistEntity
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import com.amwolfstein.sistrum.library.tags.TagNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RelationshipRebuilderTest {
    private lateinit var database: LibraryDatabase

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LibraryDatabase::class.java,
        ).allowMainThreadQueries().build()
        val dao = database.libraryDao()
        dao.upsertSource(LibrarySourceEntity("s", "content://s", "Music", createdAt = 1L))
        dao.upsertAlbums(listOf(AlbumEntity("album", "Album", "Album", "album", artworkKey = "cover_key")))
        val artistA = TagNormalizer.key("artist", "A")
        dao.upsertArtists(
            listOf(
                ArtistEntity(
                    id = artistA,
                    name = "A",
                    sortName = "A",
                    normalizationKey = "a",
                    artworkKey = "deezer_key",
                    artworkSource = "DEEZER",
                    artworkUpdatedAt = 5L,
                ),
            ),
        )
        dao.upsertItem(
            LibraryItemEntity(
                id = "track_1",
                documentKey = "doc_1",
                fileUri = "content://missing/audio.mp3",
                sourceId = "s",
                displayPath = "Album/01.mp3",
                albumId = "album",
                title = "Track",
                sortTitle = "Track",
                albumTitle = "Album",
                albumSortTitle = "Album",
                rawArtistNames = """["A; B","C"]""",
                rawAlbumArtistNames = """["A; B"]""",
                rawGenreNames = """["Rock; Pop"]""",
                rawComposerNames = """["One, Two"]""",
                isFavorite = true,
                rating = 4,
                artworkKey = "track_art",
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun rebuildSplitsRawValuesWithoutReadingAudioOrLosingUserState() = runBlocking {
        val dao = database.libraryDao()
        // No audio file exists at the item URI: success proves the rebuild only reads Room.
        val result = RelationshipRebuilder(database).rebuild(SeparatorConfig())

        assertEquals(1, result.items)
        assertEquals(3, result.artists)
        assertEquals(2, result.genres)
        assertEquals(2, result.composers)

        val artistIds = dao.artistsForItem("track_1")
        assertEquals(3, artistIds.size)
        assertTrue(artistIds.contains(TagNormalizer.key("artist", "A")))
        assertTrue(artistIds.contains(TagNormalizer.key("artist", "B")))
        assertTrue(artistIds.contains(TagNormalizer.key("artist", "C")))

        val item = dao.itemById("track_1")!!
        assertEquals("""["A; B","C"]""", item.rawArtistNames)
        assertEquals("""["Rock; Pop"]""", item.rawGenreNames)
        assertTrue(item.isFavorite)
        assertEquals(4, item.rating)
        assertEquals("track_art", item.artworkKey)

        // Existing artist artwork survives: a rebuild must not reset Deezer imports.
        val artist = dao.artist(TagNormalizer.key("artist", "A"))!!
        assertEquals("deezer_key", artist.artworkKey)
        assertEquals("DEEZER", artist.artworkSource)
        assertEquals(5L, artist.artworkUpdatedAt)
        assertNotNull(dao.album("album"))
        assertEquals("cover_key", dao.album("album")?.artworkKey)
    }

    @Test
    fun rebuildIsRepeatableAndEmptyDelimitersKeepWholeValues() = runBlocking {
        val dao = database.libraryDao()
        val config = SeparatorConfig(
            artist = emptyList(),
            albumArtist = emptyList(),
            genre = emptyList(),
            composer = emptyList(),
        )
        RelationshipRebuilder(database).rebuild(config)
        val artistIds = dao.artistsForItem("track_1")
        // Multi-value frames stay separate even without user delimiters.
        assertEquals(2, artistIds.size)
        assertTrue(artistIds.contains(TagNormalizer.key("artist", "A; B")))
        assertTrue(artistIds.contains(TagNormalizer.key("artist", "C")))

        RelationshipRebuilder(database).rebuild(config)
        assertEquals(artistIds.toSet(), dao.artistsForItem("track_1").toSet())
    }

    @Test
    fun orphanedEntitiesAreRemoved() = runBlocking {
        val dao = database.libraryDao()
        dao.upsertArtists(
            listOf(ArtistEntity("art_orphan", "Orphan", "Orphan", "orphan")),
        )
        RelationshipRebuilder(database).rebuild(SeparatorConfig())
        assertEquals(null, dao.artist("art_orphan"))
        assertNotNull(dao.artist(TagNormalizer.key("artist", "B")))
    }
}
