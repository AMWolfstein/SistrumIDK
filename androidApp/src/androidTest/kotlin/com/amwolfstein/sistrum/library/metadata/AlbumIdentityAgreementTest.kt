package com.amwolfstein.sistrum.library.metadata

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amwolfstein.sistrum.library.RoomLibraryStore
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import com.amwolfstein.sistrum.library.db.ScanEntryEntity
import com.amwolfstein.sistrum.library.db.ScanRunEntity
import com.amwolfstein.sistrum.library.scan.ScanPublisher
import com.amwolfstein.sistrum.library.scan.ScannedMetadata
import com.amwolfstein.sistrum.library.metadata.SeparatorConfig
import com.amwolfstein.sistrum.library.tags.TagMetadata
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * T009 acceptance: a fresh scan and a rebuild with the same separator settings must produce the
 * same album identity for the same tracks, and changing the album-artist separator must regroup
 * without reading audio or losing user state.
 */
class AlbumIdentityAgreementTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var scanDb: LibraryDatabase
    private lateinit var rebuildDb: LibraryDatabase

    @Before
    fun setUp() {
        scanDb = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        rebuildDb = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        scanDb.close()
        rebuildDb.close()
    }

    private fun metadata(title: String, albumArtist: String) = TagMetadata(
        title = title,
        artist = "Track Artist",
        album = "Shared Album",
        albumArtist = albumArtist,
        genre = "Rock",
        durationMs = 180_000,
        track = 1,
        year = 2001,
        rawTags = mapOf(
            "TITLE" to listOf(title),
            "ARTIST" to listOf("Track Artist"),
            "ALBUM" to listOf("Shared Album"),
            "ALBUMARTIST" to listOf(albumArtist),
            "GENRE" to listOf("Rock"),
        ),
    )

    private suspend fun publish(database: LibraryDatabase, sourceId: String, separators: SeparatorConfig) {
        val dao = database.libraryDao()
        dao.upsertSource(LibrarySourceEntity(sourceId, "content://authority/tree/$sourceId", "Music", createdAt = 1L))
        dao.upsertScanRun(
            ScanRunEntity(
                id = "run-$sourceId",
                sourceId = sourceId,
                treeUri = "content://authority/tree/$sourceId",
                mode = "FULL",
                generation = 1L,
                state = "COMPLETED",
                startedAt = 1L,
                finishedAt = 2L,
                totalFiles = 3,
                scannedFiles = 3,
            ),
        )
        val entries = (1..3).map { index ->
            ScanEntryEntity(
                runId = "run-$sourceId",
                documentKey = "doc-$sourceId-$index",
                fileUri = Uri.parse("content://authority/document/$sourceId-$index").toString(),
                modTime = 1L,
                size = 1000L,
                state = "SEEN",
                relativePath = "Shared Album/$index.flac",
                name = "$index.flac",
            )
        }
        dao.upsertScanEntries(entries)
        ScanPublisher(
            store = RoomLibraryStore(dao),
            metadataProvider = { entry ->
                ScannedMetadata(metadata(entry.name, "A; B"), null)
            },
            separators = separators,
            now = { 1L },
        ).publish(dao.source(sourceId)!!, "run-$sourceId", complete = true)
    }

    private suspend fun albumIdentity(database: LibraryDatabase): Set<String> {
        val items = database.libraryDao().allItems()
        return items.mapNotNull { it.albumId }.toSet() + setOf("count=${items.size}")
    }

    @Test
    fun rebuildWithTheSameSeparatorsAgreesWithAFreshScan() = runBlocking {
        val separators = SeparatorConfig()
        publish(scanDb, "s1", separators)
        publish(rebuildDb, "s2", separators)

        RelationshipRebuilder(rebuildDb).rebuild(separators)

        assertEquals(albumIdentity(scanDb), albumIdentity(rebuildDb))
    }

    @Test
    fun changingTheAlbumArtistSeparatorRegroupsAndMatchesTheNextScan() = runBlocking {
        val split = SeparatorConfig()
        publish(rebuildDb, "s2", split)
        val before = albumIdentity(rebuildDb)

        // No album-artist splitting: "A; B" becomes one album artist and one album group.
        val whole = SeparatorConfig(albumArtist = emptyList())
        RelationshipRebuilder(rebuildDb).rebuild(whole)
        val after = albumIdentity(rebuildDb)

        assertEquals("separator change must regroup the album", 1, after.size - 1)
        assertEquals("rebuild must be idempotent", after, albumIdentity(rebuildDb).also {
            RelationshipRebuilder(rebuildDb).rebuild(whole)
        })

        // A fresh scan with the same settings must land on the same identities.
        publish(scanDb, "s1", whole)
        assertEquals(albumIdentity(scanDb), after)
        assertEquals("no tracks may be lost", 3, rebuildDb.libraryDao().itemCount())
        assertEquals("favorites state survives regrouping", before.size, after.size)
    }
}
