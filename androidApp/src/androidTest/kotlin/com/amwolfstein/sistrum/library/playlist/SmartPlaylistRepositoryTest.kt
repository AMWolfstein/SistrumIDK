package com.amwolfstein.sistrum.library.playlist

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amwolfstein.sistrum.library.db.ArtistEntity
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import com.amwolfstein.sistrum.library.db.TrackArtistEntity
import com.amwolfstein.sistrum.sync.LocalLibraryCatalog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmartPlaylistRepositoryTest {
    private lateinit var database: LibraryDatabase
    private lateinit var repository: LocalPlaylistRepository
    private lateinit var catalog: LocalLibraryCatalog

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LocalPlaylistRepository(context, database)
        catalog = LocalLibraryCatalog(context, database, legacy = null)
        val dao = database.libraryDao()
        dao.upsertSource(LibrarySourceEntity("s", "content://s", "Music", createdAt = 1L))
        dao.upsertArtists(
            listOf(
                ArtistEntity("artist_a", "A", "A", "a"),
                ArtistEntity("artist_b", "B", "B", "b"),
            ),
        )
        dao.upsertItem(
            LibraryItemEntity(
                id = "t1", documentKey = "d1", fileUri = "content://m/1.mp3", sourceId = "s",
                title = "One", sortTitle = "One", rawArtistNames = """["A"]""", rating = 5,
            ),
        )
        dao.upsertItem(
            LibraryItemEntity(
                id = "t2", documentKey = "d2", fileUri = "content://m/2.mp3", sourceId = "s",
                title = "Two", sortTitle = "Two", rawArtistNames = """["B"]""",
            ),
        )
        dao.upsertTrackArtists(
            listOf(TrackArtistEntity("t1", "artist_a", 0), TrackArtistEntity("t2", "artist_b", 0)),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun liveSmartPlaylistTracksLibraryChanges() = runBlocking {
        val config = SmartPlaylistConfig(
            root = SmartRuleGroup(rules = listOf(SmartRule("artist", "is", JsonPrimitive("A")))),
        )
        val id = repository.createSmart("A only", config, catalog.tracks.first())
        assertEquals(listOf("t1"), repository.observePlaylists(catalog.tracks).first().single().trackIds)

        repository.updateSmart(
            id,
            "A only",
            config.copy(root = SmartRuleGroup(rules = listOf(SmartRule("rating", "gte", JsonPrimitive(4))))),
            catalog.tracks.first(),
        )
        assertEquals(listOf("t1"), repository.observePlaylists(catalog.tracks).first().single().trackIds)

        repository.updateSmart(
            id,
            "A only",
            config.copy(root = SmartRuleGroup(rules = listOf(SmartRule("artist", "is", JsonPrimitive("B"))))),
            catalog.tracks.first(),
        )
        assertEquals(listOf("t2"), repository.observePlaylists(catalog.tracks).first().single().trackIds)
    }

    @Test
    fun frozenSmartPlaylistKeepsItsSnapshot() = runBlocking {
        val config = SmartPlaylistConfig(
            root = SmartRuleGroup(rules = listOf(SmartRule("rating", "gte", JsonPrimitive(1)))),
            liveUpdating = false,
        )
        val id = repository.createSmart("Frozen", config, catalog.tracks.first())
        assertEquals(listOf("t1"), repository.observePlaylists(catalog.tracks).first().single().trackIds)

        // A new matching track does not join a frozen playlist.
        database.libraryDao().upsertItem(
            LibraryItemEntity(
                id = "t3", documentKey = "d3", fileUri = "content://m/3.mp3", sourceId = "s",
                title = "Three", sortTitle = "Three", rating = 3,
            ),
        )
        assertEquals(listOf("t1"), repository.observePlaylists(catalog.tracks).first().single().trackIds)
        assertEquals(id, repository.observePlaylists(catalog.tracks).first().single().id)
    }

    @Test
    fun invalidRulesAreRejected() = runBlocking {
        val invalid = SmartPlaylistConfig(root = SmartRuleGroup(rules = listOf(SmartRule("album", "is", JsonPrimitive("x")))))
        assertTrue(runCatching { repository.createSmart("Bad", invalid, catalog.tracks.first()) }.isFailure)
        assertTrue(repository.observePlaylists(catalog.tracks).first().isEmpty())
    }
}
