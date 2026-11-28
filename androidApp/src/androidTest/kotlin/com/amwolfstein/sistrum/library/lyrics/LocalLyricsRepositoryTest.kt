package com.amwolfstein.sistrum.library.lyrics

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalLyricsRepositoryTest {
    private lateinit var context: android.content.Context
    private lateinit var database: LibraryDatabase
    private lateinit var repository: LocalLyricsRepository
    private lateinit var audio: File

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LocalLyricsRepository(context, database)
        audio = File(context.cacheDir, "lyrics-fixture.mp3")
        InstrumentationRegistry.getInstrumentation().context.assets.open("taglib/fixture.mp3").use { input ->
            audio.outputStream().use { output -> input.copyTo(output) }
        }
        val dao = database.libraryDao()
        dao.upsertSource(LibrarySourceEntity("s", "content://tree/s", "Music", createdAt = 1L))
        dao.upsertItem(
            LibraryItemEntity(
                id = "track_1", documentKey = "doc_1", fileUri = Uri.fromFile(audio).toString(),
                sourceId = "s", displayPath = "Album/track.mp3", title = "Track", sortTitle = "Track",
                durationSeconds = 3,
            ),
        )
    }

    @After
    fun tearDown() {
        audio.delete()
        database.close()
    }

    @Test
    fun embeddedLyricsAreUsedWhenNoLocalFileExists() = runBlocking {
        val resolved = repository.resolve("track_1", LyricsLocalSettings(preferLocalLyrics = true))
        assertEquals("line one", resolved?.content)
        assertEquals("embedded", resolved?.source)
        assertTrue(resolved?.synced == false)
    }

    @Test
    fun preferLocalOffSurfacesCachedOnlineLyricsFirst() = runBlocking {
        repository.saveOnline("track_1", "[00:01.00]online words", "lrclib-synced", "fp")
        val resolved = repository.resolve("track_1", LyricsLocalSettings(preferLocalLyrics = false))
        assertEquals("online words", resolved?.content?.substringAfter(']'))
        assertEquals("lrclib-synced", resolved?.source)
        assertTrue(resolved?.synced == true)
    }

    @Test
    fun preferLocalOnKeepsEmbeddedOverCachedOnline() = runBlocking {
        repository.saveOnline("track_1", "online words", "lrclib-plain", "fp")
        val resolved = repository.resolve("track_1", LyricsLocalSettings(preferLocalLyrics = true))
        assertEquals("line one", resolved?.content)
        assertEquals("embedded", resolved?.source)
    }

    @Test
    fun unknownTrackResolvesToNothing() = runBlocking {
        assertNull(repository.resolve("missing", LyricsLocalSettings()))
        assertNull(repository.observe("track_1").first())
    }
}
