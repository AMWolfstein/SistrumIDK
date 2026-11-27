package com.amwolfstein.sistrum.library

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.amwolfstein.sistrum.library.db.AlbumArtistEntity
import com.amwolfstein.sistrum.library.db.AlbumEntity
import com.amwolfstein.sistrum.library.db.ArtistEntity
import com.amwolfstein.sistrum.library.db.ComposerEntity
import com.amwolfstein.sistrum.library.db.GenreEntity
import com.amwolfstein.sistrum.library.db.ItemSourceEntity
import com.amwolfstein.sistrum.library.db.LibraryDao
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import com.amwolfstein.sistrum.library.db.ScanEntryEntity
import com.amwolfstein.sistrum.library.db.TrackAlbumArtistEntity
import com.amwolfstein.sistrum.library.db.TrackArtistEntity
import com.amwolfstein.sistrum.library.db.TrackComposerEntity
import com.amwolfstein.sistrum.library.db.TrackGenreEntity
import com.amwolfstein.sistrum.library.scan.LibraryStore
import com.amwolfstein.sistrum.library.scan.PublishResult
import com.amwolfstein.sistrum.library.scan.ScanPublisher
import com.amwolfstein.sistrum.library.scan.detachSource
import com.amwolfstein.sistrum.library.tags.MetadataExtractor

class RoomLibraryStore(private val dao: LibraryDao) : LibraryStore {
    override suspend fun source(id: String): LibrarySourceEntity? = dao.source(id)
    override suspend fun itemByDocumentKey(documentKey: String): LibraryItemEntity? =
        dao.itemByDocumentKey(documentKey)
    override suspend fun itemsByContentKey(contentKey: String): List<LibraryItemEntity> =
        dao.itemsByContentKey(contentKey)
    override suspend fun itemsForSource(sourceId: String): List<LibraryItemEntity> =
        dao.itemsForSource(sourceId)
    override suspend fun itemSources(itemId: String): List<ItemSourceEntity> = dao.itemSources(itemId)
    override suspend fun itemSourcesForSource(sourceId: String): List<ItemSourceEntity> =
        dao.itemSourcesForSource(sourceId)
    override suspend fun itemById(itemId: String): LibraryItemEntity? = dao.itemById(itemId)
    override suspend fun deleteItemSource(itemId: String, sourceId: String) =
        dao.deleteItemSource(itemId, sourceId)
    override suspend fun deleteItemSourcesExcept(itemId: String, keepSourceId: String) =
        dao.deleteItemSourcesExcept(itemId, keepSourceId)
    override suspend fun deleteOrphanItemsForSource(sourceId: String) =
        dao.deleteOrphanItemsForSource(sourceId)
    override suspend fun scanEntries(runId: String): List<ScanEntryEntity> = dao.scanEntries(runId)
    override suspend fun upsertItem(item: LibraryItemEntity) = dao.upsertItem(item)
    override suspend fun upsertArtists(values: List<ArtistEntity>) = dao.upsertArtists(values)
    override suspend fun upsertAlbums(values: List<AlbumEntity>) = dao.upsertAlbums(values)
    override suspend fun upsertGenres(values: List<GenreEntity>) = dao.upsertGenres(values)
    override suspend fun upsertComposers(values: List<ComposerEntity>) = dao.upsertComposers(values)
    override suspend fun upsertTrackArtists(values: List<TrackArtistEntity>) =
        dao.upsertTrackArtists(values)
    override suspend fun upsertTrackAlbumArtists(values: List<TrackAlbumArtistEntity>) =
        dao.upsertTrackAlbumArtists(values)
    override suspend fun upsertTrackGenres(values: List<TrackGenreEntity>) = dao.upsertTrackGenres(values)
    override suspend fun upsertTrackComposers(values: List<TrackComposerEntity>) =
        dao.upsertTrackComposers(values)
    override suspend fun upsertAlbumArtists(values: List<AlbumArtistEntity>) = dao.upsertAlbumArtists(values)
    override suspend fun upsertItemSources(values: List<ItemSourceEntity>) = dao.upsertItemSources(values)
    override suspend fun deleteTrackArtists(trackId: String) = dao.deleteTrackArtists(trackId)
    override suspend fun deleteTrackAlbumArtists(trackId: String) = dao.deleteTrackAlbumArtists(trackId)
    override suspend fun deleteTrackGenres(trackId: String) = dao.deleteTrackGenres(trackId)
    override suspend fun deleteTrackComposers(trackId: String) = dao.deleteTrackComposers(trackId)
}

/** Publishes scans transactionally; the whole run is atomic, so a failure keeps the previous index. */
class LibraryRepository(
    private val context: Context,
    private val database: LibraryDatabase = LibraryDatabase.get(context),
) {
    private val dao = database.libraryDao()
    private val extractor = MetadataExtractor(context)

    suspend fun publish(source: LibrarySourceEntity, runId: String, complete: Boolean): PublishResult =
        database.withTransaction {
            ScanPublisher(
                store = RoomLibraryStore(dao),
                metadataProvider = { entry ->
                    extractor.read(Uri.parse(entry.fileUri)).getOrNull()?.metadata
                },
            ).publish(source, runId, complete)
        }

    suspend fun publishEntry(
        source: LibrarySourceEntity,
        entry: ScanEntryEntity,
    ): PublishResult = database.withTransaction {
        ScanPublisher(
            store = RoomLibraryStore(dao),
            metadataProvider = { item -> extractor.read(Uri.parse(item.fileUri)).getOrNull()?.metadata },
        ).publishEntry(source, entry)
    }

    /**
     * Removes a root without deleting items another root still provides. Items that no longer have
     * any membership are deleted; the rest are re-pointed to a surviving source before the FK cascade.
     */
    suspend fun removeSource(source: LibrarySourceEntity) = database.withTransaction {
        detachSource(RoomLibraryStore(dao), source.id)
        dao.deleteSource(source.id)
    }
}
