package com.amwolfstein.sistrum.library.tags

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import com.amwolfstein.sistrum.library.db.TagWriteJournalEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagWriterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: LibraryDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            database.libraryDao().upsertSource(
                LibrarySourceEntity(id = "s1", treeUri = "content://authority/tree/a", displayName = "Test"),
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun writesTagsAndKeepsAudioProperties() = runBlocking {
        val file = fixture("fixture.flac")
        val item = item(file)
        val before = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use {
            TagMetadataJson.parse(TagLibBridge.nativeReadMetadata(it.fd))
        }

        val result = TagWriter(context, database.libraryDao()).write(
            item,
            TagEdit(
                title = "Edited Title",
                artist = "Edited Artist",
                album = "Edited Album",
                albumArtist = "Edited Album Artist",
                genre = "Ambient",
                composer = "Edited Composer",
                year = 2025,
                track = 3,
                disc = 1,
            ),
        )

        assertTrue("write failed: $result", result is TagWriteResult.Success)
        val metadata = (result as TagWriteResult.Success).metadata
        assertEquals("Edited Title", metadata.title)
        assertEquals("Edited Artist", metadata.artist)
        assertEquals("Ambient", metadata.genre)
        assertEquals(2025, metadata.year)
        assertEquals(3, metadata.track)
        assertEquals(before.durationMs, metadata.durationMs)
        assertEquals(before.sampleRate, metadata.sampleRate)
        assertTrue(database.libraryDao().incompleteTagWrites().isEmpty())
    }

    @Test
    fun clearsFieldsWithAnEmptyValueAndKeepsAudio() = runBlocking {
        val file = fixture("fixture.opus")
        val item = item(file)
        val writer = TagWriter(context, database.libraryDao())
        val first = writer.write(item, TagEdit(title = "Keep", genre = "Rock"))
        assertTrue(first is TagWriteResult.Success)

        val cleared = writer.write(item, TagEdit(genre = ""))
        assertTrue("clear failed: $cleared", cleared is TagWriteResult.Success)
        assertEquals(null, (cleared as TagWriteResult.Success).metadata.genre)
        assertEquals("Keep", cleared.metadata.title)
    }

    @Test
    fun recoversAnInterruptedWriteFromTheJournal() = runBlocking {
        val file = fixture("fixture.wav")
        val originalBytes = file.readBytes()
        val journalDir = File(context.cacheDir, "journal-test").apply { mkdirs() }
        val backup = File(journalDir, "journal-original.wav").apply { writeBytes(originalBytes) }
        val staged = File(journalDir, "journal-staged.wav").apply { writeBytes(originalBytes) }
        file.writeBytes(ByteArray(16))

        database.libraryDao().upsertTagWriteJournal(
            TagWriteJournalEntity(
                id = "j1",
                trackId = "t1",
                operation = "TAGS",
                stagedPath = "$backup\u0000$staged",
                originalFingerprint = null,
                state = "WRITING",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        database.libraryDao().upsertItem(item(file))

        TagWriter(context, database.libraryDao()).recoverIncompleteWrites()

        assertArrayEquals(originalBytes, file.readBytes())
        assertEquals("RECOVERED", database.libraryDao().incompleteTagWrites().firstOrNull()?.state ?: "RECOVERED")
        assertFalse(backup.exists())
    }

    private fun item(file: File) = LibraryItemEntity(
        id = "t1",
        documentKey = "authority:${file.name}",
        fileUri = Uri.fromFile(file).toString(),
        sourceId = "s1",
        title = file.name,
    )

    private fun fixture(name: String): File {
        val target = File(context.cacheDir, "writer-$name")
        InstrumentationRegistry.getInstrumentation().context.assets.open("taglib/$name").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }
}
