package com.amwolfstein.sistrum.library.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Large-library guard: 10,000 tracks must stay indexable and searchable within a usable
 * latency budget. Records the measured numbers in the assertion messages so the evidence is
 * visible in the test report.
 */
class LargeLibrarySearchTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var database: LibraryDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun tenThousandTracksIndexAndSearchWithinBudget() = runBlocking {
        val dao = database.libraryDao()
        dao.upsertSource(LibrarySourceEntity("s", "content://authority/tree/s", "Music", createdAt = 1L))
        val items = (1..10_000).map { index ->
            LibraryItemEntity(
                id = "track-$index",
                documentKey = "doc-$index",
                fileUri = Uri.parse("content://authority/document/$index").toString(),
                sourceId = "s",
                displayPath = "Artist ${index % 300}/Album ${index % 900}/$index.flac",
                title = "Track $index",
                sortTitle = "Track $index",
                albumTitle = "Album ${index % 900}",
                albumId = "album-${index % 900}",
                durationSeconds = 180,
                format = "flac",
                available = true,
            )
        }
        val insertStart = System.nanoTime()
        val albums = (0 until 900).map { index ->
            AlbumEntity(
                id = "album-$index",
                title = "Album $index",
                sortTitle = "Album $index",
                normalizationKey = "album $index",
            )
        }
        dao.upsertAlbums(albums)
        items.chunked(500).forEach { dao.upsertItems(it) }
        val indexer = SearchIndexer(database)
        items.chunked(500).forEach { chunk -> chunk.forEach { indexer.indexItem(it) } }
        val insertMs = (System.nanoTime() - insertStart) / 1_000_000

        val searchStart = System.nanoTime()
        val hits = indexer.search("Track 4321")
        val searchMs = (System.nanoTime() - searchStart) / 1_000_000

        val pageStart = System.nanoTime()
        val page = dao.itemsNeedingAnalysis(50, 4)
        val pageMs = (System.nanoTime() - pageStart) / 1_000_000

        assertEquals("index build (${insertMs} ms)", 10_000, dao.itemCount())
        assertTrue("search found the track: $hits", hits.any { it.entityId == "track-4321" })
        assertTrue("FTS search took ${searchMs} ms", searchMs < 500)
        assertTrue("analysis queue page took ${pageMs} ms", pageMs < 500)
        assertTrue("queue page bounded: ${page.size}", page.size <= 50)
        // Evidence line for the report.
        println("LARGE_LIBRARY index=${insertMs}ms search=${searchMs}ms queuePage=${pageMs}ms")
    }
}
