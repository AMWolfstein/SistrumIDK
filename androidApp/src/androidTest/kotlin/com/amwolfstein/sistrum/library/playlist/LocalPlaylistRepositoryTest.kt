package com.amwolfstein.sistrum.library.playlist

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import com.amwolfstein.sistrum.library.db.PlaylistEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalPlaylistRepositoryTest {
    private lateinit var database: LibraryDatabase
    private lateinit var repository: LocalPlaylistRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LocalPlaylistRepository(context, database)
        val dao = database.libraryDao()
        dao.upsertSource(LibrarySourceEntity("s", "content://s", "Music", createdAt = 1L))
        listOf("t1", "t2", "t3").forEach { id ->
            dao.upsertItem(
                LibraryItemEntity(
                    id = id, documentKey = "doc_$id", fileUri = "content://m/$id.mp3", sourceId = "s",
                    title = id, sortTitle = id,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createAddMoveRemoveRenameDelete() = runBlocking {
        val id = repository.createWithoutMutation("Mix", listOf("t1", "t2"))
        assertEquals(listOf("t1", "t2"), repository.observePlaylists().first().single().trackIds)

        repository.moveTrack(id, "t2", null, "t1")
        assertEquals(listOf("t2", "t1"), repository.observePlaylists().first().single().trackIds)

        repository.addTrack(id, "t3", index = 0)
        assertEquals(listOf("t3", "t2", "t1"), repository.observePlaylists().first().single().trackIds)
        repository.addTrack(id, "t1")
        assertEquals(3, repository.observePlaylists().first().single().trackIds.size)

        repository.removeTrack(id, "t2")
        assertEquals(listOf("t3", "t1"), repository.observePlaylists().first().single().trackIds)

        repository.update(id, "Renamed", "desc")
        val playlist = repository.observePlaylists().first().single()
        assertEquals("Renamed", playlist.name)
        assertTrue(playlist.metadataJson.contains("desc"))

        repository.delete(id)
        assertTrue(repository.observePlaylists().first().isEmpty())
        assertNull(database.libraryDao().playlist(id))
    }

    @Test
    fun addTrackIgnoresUnknownAndDuplicateTracks() = runBlocking {
        val id = repository.createWithoutMutation("Mix", listOf("t1", "t1", "missing"))
        assertEquals(listOf("t1"), repository.observePlaylists().first().single().trackIds)
        repository.addTrack(id, "missing")
        repository.addTrack(id, "t1")
        assertEquals(listOf("t1"), repository.observePlaylists().first().single().trackIds)
    }

    @Test
    fun favoritesSystemPlaylistIsNotEditable() = runBlocking {
        database.libraryDao().upsertPlaylist(
            PlaylistEntity("favorites", "Favorites", systemKind = "favorites"),
        )
        assertTrue(repository.observePlaylists().first().isEmpty())
        var failed = false
        try {
            repository.update("favorites", "x", null)
        } catch (expected: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
        assertTrue(runCatching { repository.delete("favorites") }.isFailure)
    }
}
