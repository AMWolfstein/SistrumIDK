package com.amwolfstein.sistrum.library.scan

import com.amwolfstein.sistrum.library.db.AlbumArtistEntity
import com.amwolfstein.sistrum.library.db.AlbumEntity
import com.amwolfstein.sistrum.library.db.ArtistEntity
import com.amwolfstein.sistrum.library.db.ComposerEntity
import com.amwolfstein.sistrum.library.db.GenreEntity
import com.amwolfstein.sistrum.library.db.ItemSourceEntity
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import com.amwolfstein.sistrum.library.db.ScanEntryEntity
import com.amwolfstein.sistrum.library.db.TrackAlbumArtistEntity
import com.amwolfstein.sistrum.library.db.TrackArtistEntity
import com.amwolfstein.sistrum.library.db.TrackComposerEntity
import com.amwolfstein.sistrum.library.db.TrackGenreEntity
import com.amwolfstein.sistrum.library.tags.TagMetadata
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPublisherTest {

    private val sourceA = LibrarySourceEntity(
        id = "sourceA",
        treeUri = "content://authority/tree/a",
        displayName = "Internal",
        available = true,
    )
    private val sourceB = LibrarySourceEntity(
        id = "sourceB",
        treeUri = "content://authority/tree/b",
        displayName = "SD",
        available = true,
    )

    @Test
    fun initialPublishCreatesItemAndDerivedEntities() = runTest {
        val store = FakeStore()
        store.entries += entry("authority:a.mp3", "Artist/Album/01.mp3", size = 100)
        val result = publish(store, sourceA, complete = true, metadata = metadata())

        assertEquals(1, result.added)
        val item = store.items.values.single()
        assertEquals("Song", item.title)
        assertEquals("Album", item.albumTitle)
        assertEquals(1, item.metadataVersion)
        assertEquals("mp3", item.format)
        assertTrue(item.available)
        assertNotNull(item.albumId)
        assertEquals(1, store.artists.size)
        assertEquals(1, store.albums.size)
        assertEquals(1, store.genres.size)
        assertEquals(1, store.trackArtists.size)
        assertEquals(1, store.itemSources.size)
    }

    @Test
    fun repeatedPublishIsIdempotentAndPreservesUserState() = runTest {
        val store = FakeStore()
        store.entries += entry("authority:a.mp3", "a.mp3", size = 100)
        publish(store, sourceA, complete = true, metadata = metadata())
        store.items.replaceAll { _, item -> item.copy(isFavorite = true, rating = 5, playCount = 12) }

        val result = publish(store, sourceA, complete = true, metadata = metadata())

        assertEquals(1, store.items.size)
        assertEquals(1, result.updated)
        val item = store.items.values.single()
        assertTrue(item.isFavorite)
        assertEquals(5, item.rating)
        assertEquals(12, item.playCount)
        assertEquals(2, item.metadataVersion)
    }

    @Test
    fun renameReusesItemIdAndPreservesPlaylistReferences() = runTest {
        val store = FakeStore()
        store.entries += entry("authority:a/old.mp3", "old.mp3", size = 100)
        publish(store, sourceA, complete = true, metadata = metadata())
        val originalId = store.items.keys.single()
        store.items[originalId] = store.items.getValue(originalId).copy(isFavorite = true, rating = 4)

        store.entries.clear()
        store.entries += entry("authority:a/new.mp3", "new.mp3", size = 100)
        val result = publish(store, sourceA, complete = true, metadata = metadata())

        assertEquals(1, result.moved)
        assertTrue(store.items.containsKey(originalId))
        val item = store.items.getValue(originalId)
        assertEquals("authority:a/new.mp3", item.documentKey)
        assertTrue(item.isFavorite)
        assertEquals(4, item.rating)
    }

    @Test
    fun nonUniqueContentMatchNeverGuessesAnIdentity() = runTest {
        val store = FakeStore()
        store.entries += entry("authority:a.mp3", "a.mp3", size = 100)
        store.entries += entry("authority:b.mp3", "b.mp3", size = 100)
        publish(store, sourceA, complete = true, metadata = metadata())
        assertEquals(2, store.items.size)

        store.entries.clear()
        store.entries += entry("authority:c.mp3", "c.mp3", size = 100)
        val result = publish(store, sourceA, complete = true, metadata = metadata())

        assertEquals(0, result.moved)
        assertEquals(1, result.added)
        assertEquals(3, store.items.size)
    }

    @Test
    fun partialEnumerationNeverMarksFilesMissing() = runTest {
        val store = FakeStore()
        store.entries += entry("authority:a.mp3", "a.mp3", size = 100)
        publish(store, sourceA, complete = true, metadata = metadata())

        store.entries.clear()
        val result = publish(store, sourceA, complete = false, metadata = metadata())

        assertEquals(0, result.missing)
        assertTrue(store.items.values.single().available)
        assertNull(store.items.values.single().missingSince)
    }

    @Test
    fun completeEnumerationMarksUnseenFilesMissingWithoutDeleting() = runTest {
        val store = FakeStore()
        store.entries += entry("authority:a.mp3", "a.mp3", size = 100)
        publish(store, sourceA, complete = true, metadata = metadata())

        store.entries.clear()
        val result = publish(store, sourceA, complete = true, metadata = metadata())

        assertEquals(1, result.missing)
        val item = store.items.values.single()
        assertFalse(item.available)
        assertNotNull(item.missingSince)
    }

    @Test
    fun overlappingRootsShareOneItemAndDoNotHideEachOther() = runTest {
        val store = FakeStore()
        store.entries += entry("authority:shared.mp3", "shared.mp3", size = 100)
        publish(store, sourceB, complete = true, metadata = metadata())
        assertEquals(1, store.items.size)

        val item = store.items.values.single()
        store.items[item.id] = item.copy(sourceId = sourceA.id, available = true)
        store.itemSources += ItemSourceEntity(item.id, sourceA.id, "content://a/shared")

        store.entries.clear()
        store.entries += entry("authority:shared.mp3", "shared.mp3", size = 100)
        val result = publish(store, sourceB, complete = true, metadata = metadata())

        assertEquals(0, result.missing)
        assertEquals(1, store.items.size)
        assertTrue(store.items.values.single().available)
        assertEquals(2, store.itemSources.count { it.itemId == item.id })
    }

    @Test
    fun crossSourceMoveRepointsPrimaryAndKeepsMembershipsForLaterReconciliation() = runTest {
        val store = FakeStore()
        store.entries += entry("authority:internal/a.mp3", "a.mp3", size = 100)
        publish(store, sourceA, complete = true, metadata = metadata())
        val originalId = store.items.keys.single()

        store.entries.clear()
        store.entries += entry("authority:sd/a.mp3", "a.mp3", size = 100)
        val result = publish(store, sourceB, complete = true, metadata = metadata())

        assertEquals(1, result.moved)
        val item = store.items.getValue(originalId)
        assertEquals(sourceB.id, item.sourceId)
        assertEquals(
            listOf(sourceA.id, sourceB.id),
            store.itemSources.filter { it.itemId == originalId }.map { it.sourceId }.sorted(),
        )
    }

    @Test
    fun removingOneOfTwoDuplicateRootsKeepsTheItemAndUserState() = runTest {
        val store = FakeStore()
        store.entries += entry("authority:a/dup.mp3", "dup.mp3", size = 100)
        publish(store, sourceA, complete = true, metadata = metadata())
        store.entries.clear()
        store.entries += entry("authority:b/dup.mp3", "dup.mp3", size = 100)
        publish(store, sourceB, complete = true, metadata = metadata())
        val itemId = store.items.keys.single()
        store.items[itemId] = store.items.getValue(itemId).copy(isFavorite = true, rating = 5)
        assertEquals(2, store.itemSources.count { it.itemId == itemId })

        val orphaned = detachSource(store, sourceB.id)

        assertEquals(0, orphaned)
        assertEquals(sourceA.id, store.items.getValue(itemId).sourceId)
        assertTrue(store.items.getValue(itemId).isFavorite)
        assertEquals(5, store.items.getValue(itemId).rating)
        assertEquals(listOf(sourceA.id), store.itemSources.filter { it.itemId == itemId }.map { it.sourceId })
    }

    @Test
    fun removingTheLastRootOrphansTheItem() = runTest {
        val store = FakeStore()
        store.entries += entry("authority:a/only.mp3", "only.mp3", size = 100)
        publish(store, sourceA, complete = true, metadata = metadata())
        val itemId = store.items.keys.single()

        assertEquals(1, detachSource(store, sourceA.id))
        assertTrue(store.itemSources.none { it.itemId == itemId })
    }

    @Test
    fun moveAfterTheOldSourceWasMarkedMissingStillReusesTheItem() = runTest {
        val store = FakeStore()
        store.entries += entry("authority:internal/a.mp3", "a.mp3", size = 100)
        publish(store, sourceA, complete = true, metadata = metadata())
        val originalId = store.items.keys.single()

        store.entries.clear()
        publish(store, sourceA, complete = true, metadata = metadata())
        assertFalse(store.items.getValue(originalId).available)

        store.entries += entry("authority:sd/a.mp3", "a.mp3", size = 100)
        val result = publish(store, sourceB, complete = true, metadata = metadata())

        assertEquals(1, result.moved)
        val item = store.items.getValue(originalId)
        assertTrue(item.available)
        assertNull(item.missingSince)
        assertEquals(sourceB.id, item.sourceId)
    }

    @Test
    fun duplicatedContentKeyFallsBackToAUniqueSameSourceMatch() = runTest {
        val store = FakeStore()
        store.entries += entry("authority:a/a.mp3", "a.mp3", size = 100)
        publish(store, sourceA, complete = true, metadata = metadata())
        val first = store.items.getValue(store.items.keys.single())

        // Simulate a second identical copy owned by another source.
        val copy = first.copy(id = "manual_copy", documentKey = "authority:b/a.mp3", sourceId = sourceB.id)
        store.items[copy.id] = copy
        store.itemSources += ItemSourceEntity(copy.id, sourceB.id, "content://b/a.mp3")

        store.entries.clear()
        store.entries += entry("authority:a/renamed.mp3", "renamed.mp3", size = 100)
        val result = publish(store, sourceA, complete = true, metadata = metadata())

        assertEquals(1, result.moved)
        assertEquals(2, store.items.size)
        val moved = store.items.values.single { it.documentKey == "authority:a/renamed.mp3" }
        assertEquals(sourceA.id, moved.sourceId)
    }

    @Test
    fun deletionIsOnlyReportedAfterEverySourceDropsItsMembership() = runTest {
        val store = FakeStore()
        store.entries += entry("authority:shared.mp3", "shared.mp3", size = 100)
        publish(store, sourceA, complete = true, metadata = metadata())
        publish(store, sourceB, complete = true, metadata = metadata())
        val itemId = store.items.keys.single()

        store.entries.clear()
        assertEquals(0, publish(store, sourceA, complete = true, metadata = metadata()).missing)
        assertTrue(store.items.getValue(itemId).available)
        assertEquals(listOf(sourceB.id), store.itemSources.filter { it.itemId == itemId }.map { it.sourceId })

        assertEquals(1, publish(store, sourceB, complete = true, metadata = metadata()).missing)
        assertFalse(store.items.getValue(itemId).available)
    }

    @Test
    fun cueEntryUsesCueTitleAndTiming() = runTest {
        val store = FakeStore()
        store.entries += entry("authority:cue#1", "album.cue", size = 100).copy(
            cueKey = "authority:album.cue",
            cueTrackNumber = 2,
            cueStartMs = 1_000,
            cueEndMs = 61_000,
            cueTitle = "Second Track",
            cuePerformer = "Cue Artist",
        )
        publish(store, sourceA, complete = true, metadata = metadata())
        val item = store.items.values.single()
        assertEquals("Second Track", item.title)
        assertTrue(item.rawArtistNames.contains("Cue Artist"))
        assertEquals(1_000L, item.cueStartMs)
        assertEquals(61_000L, item.cueEndMs)
        assertEquals(2, item.trackNumberInCue)
    }

    @Test
    fun embeddedArtworkIsCachedAndAppliedDuringPublication() = runTest {
        val store = FakeStore()
        store.entries += entry("authority:a.mp3", "Music/a.mp3", size = 100)
        var stored: ByteArray? = null
        ScanPublisher(
            store = store,
            metadataProvider = { ScannedMetadata(metadata(), fingerprint = null, artwork = byteArrayOf(1, 2, 3)) },
            now = { 5_000L },
            artworkStore = { bytes -> stored = bytes; "art_key" },
        ).publish(sourceA, "run1", complete = true)

        val item = store.items.values.single()
        assertTrue("embedded bytes were not cached", stored?.contentEquals(byteArrayOf(1, 2, 3)) == true)
        assertEquals("art_key", item.artworkKey)
        item.albumId?.let { assertEquals("art_key", store.albumArtwork[it]) }
    }

    @Test
    fun embeddedAnalysisIsRestoredOnACleanInstall() = runTest {
        val store = FakeStore()
        store.entries += entry("authority:a.mp3", "Music/a.mp3", size = 100)
        val analysis = com.amwolfstein.sistrum.library.db.TrackAnalysisEntity(
            trackId = "",
            loudnessLufs = -14.0,
            loudnessRange = 6.0,
            truePeak = -0.5,
            rms = -18.0,
            crest = 1.4,
            spectralCentroid = 900.0,
            spectralRolloff = 1800.0,
            spectralFlatness = 0.2,
            spectralFlux = 0.03,
            zcr = 0.04,
            tempo = 122.0,
            onsetVariance = 8.0,
            energy = 0.7,
            danceability = 0.6,
            brightness = 0.5,
            ffmpegVersion = com.amwolfstein.sistrum.library.analysis.AnalysisVersion.FFMPEG,
            aubioVersion = com.amwolfstein.sistrum.library.analysis.AnalysisVersion.AUBIO,
            analyzerVersion = com.amwolfstein.sistrum.library.analysis.AnalysisVersion.ANALYZER,
            moodVersion = com.amwolfstein.sistrum.library.analysis.AnalysisVersion.MOOD,
            audioFingerprint = "pcm-sha256-v1:abc",
            status = "COMPLETED",
        )
        val tags = com.amwolfstein.sistrum.library.analysis.AnalysisTagCodec
            .embeddedAnalysis(analysis)
            .mapValues { listOf(it.value) }

        publish(store, sourceA, complete = true, metadata = metadata().copy(rawTags = tags))

        val restored = store.analyses.values.single()
        assertEquals(store.items.values.single().id, restored.trackId)
        assertEquals(-14.0, restored.loudnessLufs!!, 1e-9)
        assertEquals(122.0, restored.tempo!!, 1e-9)
        assertEquals("embedded", restored.source)
    }

    @Test
    fun missingMetadataStillPublishesFilenameFallback() = runTest {
        val store = FakeStore()
        store.entries += entry("authority:a.mp3", "Music/07 - Unknown.mp3", size = 100)
        val result = publish(store, sourceA, complete = true, metadata = null)
        assertEquals(1, result.metadataFailures)
        assertEquals("07 - Unknown", store.items.values.single().title)
        assertTrue(store.items.values.single().available)
    }

    private suspend fun publish(
        store: FakeStore,
        source: LibrarySourceEntity,
        complete: Boolean,
        metadata: TagMetadata?,
    ): PublishResult {
        val publisher = ScanPublisher(
            store = store,
            metadataProvider = { metadata?.let { ScannedMetadata(it) } },
            now = { 5_000L },
        )
        return publisher.publish(source, "run1", complete)
    }

    private fun metadata() = TagMetadata(
        title = "Song",
        artist = "Artist",
        album = "Album",
        genre = "Rock",
        composer = "Composer",
        durationMs = 60_000,
        track = 1,
        year = 1999,
        rawTags = mapOf("TITLE" to listOf("Song"), "ARTIST" to listOf("Artist")),
    )

    private fun entry(documentKey: String, relativePath: String, size: Long) = ScanEntryEntity(
        runId = "run1",
        documentKey = documentKey,
        fileUri = "content://authority/document/${documentKey.substringAfterLast(':')}",
        modTime = 1,
        size = size,
        state = "SEEN",
        relativePath = relativePath,
        name = relativePath.substringAfterLast('/'),
        changed = true,
    )

    private class FakeStore : LibraryStore {
        val albumsByKey = mutableMapOf<String, com.amwolfstein.sistrum.library.db.AlbumEntity>()
        override suspend fun albumByNormalizationKey(
            normalizationKey: String,
        ): com.amwolfstein.sistrum.library.db.AlbumEntity? = albumsByKey[normalizationKey]
        val analyses = mutableMapOf<String, com.amwolfstein.sistrum.library.db.TrackAnalysisEntity>()
        val albumArtwork = mutableMapOf<String, String>()
        override suspend fun setAlbumArtworkIfMissing(albumId: String, key: String, source: String) {
            albumArtwork.putIfAbsent(albumId, key)
        }
        override suspend fun upsertAnalysis(value: com.amwolfstein.sistrum.library.db.TrackAnalysisEntity) {
            analyses[value.trackId] = value
        }
        val sources = mutableMapOf("sourceA" to sourceEntity("sourceA"), "sourceB" to sourceEntity("sourceB"))
        val items = linkedMapOf<String, LibraryItemEntity>()
        val itemSources = mutableListOf<ItemSourceEntity>()
        val entries = mutableListOf<ScanEntryEntity>()
        val artists = linkedMapOf<String, ArtistEntity>()
        val albums = linkedMapOf<String, AlbumEntity>()
        val genres = linkedMapOf<String, GenreEntity>()
        val composers = linkedMapOf<String, ComposerEntity>()
        val trackArtists = mutableListOf<TrackArtistEntity>()
        val trackAlbumArtists = mutableListOf<TrackAlbumArtistEntity>()
        val trackGenres = mutableListOf<TrackGenreEntity>()
        val trackComposers = mutableListOf<TrackComposerEntity>()
        val albumArtists = mutableListOf<AlbumArtistEntity>()

        override suspend fun source(id: String): LibrarySourceEntity? = sources[id]
        override suspend fun itemByDocumentKey(documentKey: String): LibraryItemEntity? =
            items.values.firstOrNull { it.documentKey == documentKey }
        override suspend fun itemsByContentKey(contentKey: String): List<LibraryItemEntity> =
            items.values.filter { it.contentKey == contentKey }
        override suspend fun itemsForSource(sourceId: String): List<LibraryItemEntity> =
            items.values.filter { item -> itemSources.any { it.itemId == item.id && it.sourceId == sourceId } }
        override suspend fun itemSources(itemId: String): List<ItemSourceEntity> =
            itemSources.filter { it.itemId == itemId }
        override suspend fun itemSourcesForSource(sourceId: String): List<ItemSourceEntity> =
            itemSources.filter { it.sourceId == sourceId }
        override suspend fun itemById(itemId: String): LibraryItemEntity? = items[itemId]
        override suspend fun deleteItemSource(itemId: String, sourceId: String) {
            itemSources.removeAll { it.itemId == itemId && it.sourceId == sourceId }
        }
        override suspend fun deleteItemSourcesExcept(itemId: String, keepSourceId: String) {
            itemSources.removeAll { it.itemId == itemId && it.sourceId != keepSourceId }
        }
        override suspend fun deleteOrphanItemsForSource(sourceId: String) {
            // Room deletes rows with no membership; the fake keeps them for assertion.
        }
        override suspend fun scanEntries(runId: String): List<ScanEntryEntity> =
            entries.filter { it.runId == runId }
        override suspend fun upsertItem(item: LibraryItemEntity) { items[item.id] = item }
        override suspend fun upsertArtists(values: List<ArtistEntity>) {
            values.forEach { artists[it.id] = it }
        }
        override suspend fun upsertAlbums(values: List<AlbumEntity>) {
            values.forEach {
                albums[it.id] = it
                albumsByKey[it.normalizationKey] = it
            }
        }
        override suspend fun upsertGenres(values: List<GenreEntity>) {
            values.forEach { genres[it.id] = it }
        }
        override suspend fun upsertComposers(values: List<ComposerEntity>) {
            values.forEach { composers[it.id] = it }
        }
        override suspend fun upsertTrackArtists(values: List<TrackArtistEntity>) {
            trackArtists += values
        }
        override suspend fun upsertTrackAlbumArtists(values: List<TrackAlbumArtistEntity>) {
            trackAlbumArtists += values
        }
        override suspend fun upsertTrackGenres(values: List<TrackGenreEntity>) {
            trackGenres += values
        }
        override suspend fun upsertTrackComposers(values: List<TrackComposerEntity>) {
            trackComposers += values
        }
        override suspend fun upsertAlbumArtists(values: List<AlbumArtistEntity>) {
            albumArtists += values
        }
        override suspend fun upsertItemSources(values: List<ItemSourceEntity>) {
            values.forEach { value ->
                itemSources.removeAll { it.itemId == value.itemId && it.sourceId == value.sourceId }
                itemSources += value
            }
        }
        override suspend fun deleteTrackArtists(trackId: String) {
            trackArtists.removeAll { it.trackId == trackId }
        }
        override suspend fun deleteTrackAlbumArtists(trackId: String) {
            trackAlbumArtists.removeAll { it.trackId == trackId }
        }
        override suspend fun deleteTrackGenres(trackId: String) {
            trackGenres.removeAll { it.trackId == trackId }
        }
        override suspend fun deleteTrackComposers(trackId: String) {
            trackComposers.removeAll { it.trackId == trackId }
        }

        private companion object {
            fun sourceEntity(id: String) = LibrarySourceEntity(
                id = id,
                treeUri = "content://authority/tree/$id",
                displayName = id,
                available = true,
            )
        }
    }
}
