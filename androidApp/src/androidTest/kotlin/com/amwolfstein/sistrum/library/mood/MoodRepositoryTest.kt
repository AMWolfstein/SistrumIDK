package com.amwolfstein.sistrum.library.mood

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amwolfstein.sistrum.library.db.AlbumEntity
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import com.amwolfstein.sistrum.library.db.TrackAnalysisEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MoodRepositoryTest {
    private lateinit var database: LibraryDatabase
    private lateinit var repository: MoodRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MoodRepository(database)
        val dao = database.libraryDao()
        dao.upsertSource(LibrarySourceEntity("s", "content://s", "Music", createdAt = 1L))
        dao.upsertAlbums(listOf(AlbumEntity("album", "Album", "Album", "album")))
        (1..3).forEach { index ->
            dao.upsertItem(
                LibraryItemEntity(
                    id = "t$index", documentKey = "d$index", fileUri = "content://m/$index.mp3",
                    sourceId = "s", albumId = "album", title = "T$index", sortTitle = "T$index",
                ),
            )
        }
        dao.upsertAnalysis(
            TrackAnalysisEntity(
                trackId = "t1", energy = 0.6, danceability = 0.5, brightness = 0.4, tempo = 120.0,
                loudnessLufs = -10.0, truePeak = -0.2, status = "COMPLETED",
            ),
        )
        dao.upsertAnalysis(
            TrackAnalysisEntity(
                trackId = "t2", energy = 0.3, danceability = 0.2, brightness = 0.9, tempo = 90.0,
                loudnessLufs = -12.0, truePeak = -0.5, status = "COMPLETED",
            ),
        )
        // t3 is pending/therefore not a candidate.
        dao.upsertAnalysis(TrackAnalysisEntity(trackId = "t3", status = "PENDING"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun onlyCompletedMoodAnalysesBecomeCandidates() = runBlocking {
        val tracks = repository.tracks()
        assertEquals(listOf("t1", "t2"), tracks.map { it.id })
        assertEquals(0.6, tracks.first().energy)
        assertTrue(repository.ready())
    }

    @Test
    fun gateNeedsTwoCompleteCandidates() = runBlocking {
        database.libraryDao().upsertAnalysis(
            TrackAnalysisEntity(trackId = "t2", status = "PENDING"),
        )
        assertEquals(1, repository.tracks().size)
        assertFalse(repository.ready())
    }
}
