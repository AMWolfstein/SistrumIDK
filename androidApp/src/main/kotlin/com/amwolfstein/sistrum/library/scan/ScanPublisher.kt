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
import com.amwolfstein.sistrum.library.tags.EntityResolver
import com.amwolfstein.sistrum.library.tags.ResolvedTrack
import com.amwolfstein.sistrum.library.tags.TagMetadata
import com.amwolfstein.sistrum.library.tags.TagAliases
import com.amwolfstein.sistrum.library.tags.TagNormalizer
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persistence boundary used by [ScanPublisher]; keeps publication testable without Room. */
interface LibraryStore {
    suspend fun source(id: String): LibrarySourceEntity?
    suspend fun itemByDocumentKey(documentKey: String): LibraryItemEntity?
    suspend fun itemsByContentKey(contentKey: String): List<LibraryItemEntity>
    suspend fun itemsForSource(sourceId: String): List<LibraryItemEntity>
    suspend fun itemSources(itemId: String): List<ItemSourceEntity>
    suspend fun itemSourcesForSource(sourceId: String): List<ItemSourceEntity>
    suspend fun itemById(itemId: String): LibraryItemEntity?
    suspend fun deleteItemSource(itemId: String, sourceId: String)
    suspend fun deleteItemSourcesExcept(itemId: String, keepSourceId: String)
    suspend fun deleteOrphanItemsForSource(sourceId: String)
    suspend fun scanEntries(runId: String): List<ScanEntryEntity>
    suspend fun upsertItem(item: LibraryItemEntity)
    suspend fun upsertArtists(values: List<ArtistEntity>)
    suspend fun upsertAlbums(values: List<AlbumEntity>)
    suspend fun upsertGenres(values: List<GenreEntity>)
    suspend fun upsertComposers(values: List<ComposerEntity>)
    suspend fun upsertTrackArtists(values: List<TrackArtistEntity>)
    suspend fun upsertTrackAlbumArtists(values: List<TrackAlbumArtistEntity>)
    suspend fun upsertTrackGenres(values: List<TrackGenreEntity>)
    suspend fun upsertTrackComposers(values: List<TrackComposerEntity>)
    suspend fun upsertAlbumArtists(values: List<AlbumArtistEntity>)
    suspend fun upsertItemSources(values: List<ItemSourceEntity>)
    suspend fun deleteTrackArtists(trackId: String)
    suspend fun deleteTrackAlbumArtists(trackId: String)
    suspend fun deleteTrackGenres(trackId: String)
    suspend fun deleteTrackComposers(trackId: String)
}

fun interface TrackMetadataProvider {
    suspend fun read(entry: ScanEntryEntity): TagMetadata?
}

data class PublishResult(
    val added: Int,
    val updated: Int,
    val moved: Int,
    val missing: Int,
    val metadataFailures: Int,
)

/**
 * Reconciles one completed scan run into the library.
 *
 * - idempotent: document keys update the same row, so a repeated run cannot duplicate items
 * - moves/renames: a unique content-key match reuses the old item id, preserving favorites,
 *   ratings, play counts and every playlist/lyrics/analysis row keyed by that id
 * - removals happen only when the caller proved complete enumeration
 * - overlapping roots link to one item through item_sources and never hide a file that another
 *   available source still provides
 */
class ScanPublisher(
    private val store: LibraryStore,
    private val metadataProvider: TrackMetadataProvider,
    private val resolver: EntityResolver = EntityResolver(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { encodeDefaults = true }

    suspend fun publish(source: LibrarySourceEntity, runId: String, complete: Boolean): PublishResult {
        val entries = store.scanEntries(runId)
            .filter { it.state == "SEEN" }
        return publishEntries(source, entries, complete)
    }

    /** Republish one already-known document, e.g. after a tag edit; removals are never triggered. */
    suspend fun publishEntry(source: LibrarySourceEntity, entry: ScanEntryEntity): PublishResult =
        publishEntries(source, listOf(entry.copy(state = "SEEN", changed = true)), complete = false)

    private suspend fun publishEntries(
        source: LibrarySourceEntity,
        entries: List<ScanEntryEntity>,
        complete: Boolean,
    ): PublishResult {
        val orderedEntries = entries.sortedBy { it.documentKey }
        var added = 0
        var updated = 0
        var moved = 0
        var metadataFailures = 0
        val seenItemIds = mutableSetOf<String>()
        val claimedIds = mutableSetOf<String>()

        orderedEntries.forEach { entry ->
            val existingByDocument = store.itemByDocumentKey(entry.documentKey)

            // Unchanged documents keep their metadata and relations; only membership is refreshed.
            if (existingByDocument != null && !entry.changed &&
                existingByDocument.otherMetadataJson != "{}"
            ) {
                store.upsertItemSources(
                    listOf(ItemSourceEntity(existingByDocument.id, source.id, entry.fileUri)),
                )
                seenItemIds += existingByDocument.id
                claimedIds += existingByDocument.id
                updated += 1
                return@forEach
            }

            val metadata = runCatching { metadataProvider.read(entry) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()
            if (metadata == null) metadataFailures += 1

            // A transient read failure must not rewrite a good row from filename fallbacks.
            if (metadata == null && existingByDocument != null) {
                store.upsertItemSources(
                    listOf(ItemSourceEntity(existingByDocument.id, source.id, entry.fileUri)),
                )
                seenItemIds += existingByDocument.id
                claimedIds += existingByDocument.id
                return@forEach
            }

            val tag = mergeCue(entry, metadata ?: TagMetadata())
            val resolved = resolver.resolve(tag, entry.relativePath.ifBlank { entry.name }, entry.documentKey)
            val contentKey = contentKey(entry, tag, resolved)

            val moveCandidate = if (existingByDocument == null) {
                uniqueMoveCandidate(contentKey, claimedIds, source)
            } else {
                null
            }
            val base = existingByDocument ?: moveCandidate
            if (base != null) claimedIds += base.id

            val itemId = base?.id ?: resolved.itemId
            val isMove = existingByDocument == null && moveCandidate != null
            val item = buildItem(
                entry = entry,
                source = source,
                resolved = resolved,
                metadata = tag,
                contentKey = contentKey,
                existing = base,
                id = itemId,
            )
            writeEntities(item, resolved, tag)
            store.upsertItemSources(
                listOf(
                    ItemSourceEntity(
                        itemId = item.id,
                        sourceId = source.id,
                        fileUri = entry.fileUri,
                    ),
                ),
            )
            // A content-key match can also be a second copy, not a relocation. Never prune another
            // root's membership here; only a completed scan of that root may reconcile it.
            seenItemIds += item.id
            claimedIds += item.id
            when {
                isMove -> moved += 1
                existingByDocument == null -> added += 1
                else -> updated += 1
            }
        }

        val missing = if (complete) reconcileMemberships(source, seenItemIds) else 0
        return PublishResult(added, updated, moved, missing, metadataFailures)
    }

    private suspend fun uniqueMoveCandidate(
        contentKey: String,
        claimedIds: Set<String>,
        source: LibrarySourceEntity,
    ): LibraryItemEntity? {
        if (contentKey.isBlank()) return null
        val candidates = store.itemsByContentKey(contentKey)
            .filter { it.id !in claimedIds }
        // Global uniqueness wins; when the key is duplicated, only an unambiguous same-source
        // match may be re-keyed.
        return candidates.singleOrNull()
            ?: candidates
                .filter { item ->
                    store.itemSources(item.id).any { it.sourceId == source.id }
                }
                .singleOrNull()
    }

    /**
     * Drops the memberships this completed scan did not confirm. An item stays available while
     * another root still provides it; the last remaining membership is what can mark it missing.
     */
    private suspend fun reconcileMemberships(source: LibrarySourceEntity, seenItemIds: Set<String>): Int {
        var missing = 0
        store.itemSourcesForSource(source.id)
            .filter { it.itemId !in seenItemIds }
            .forEach { link ->
                store.deleteItemSource(link.itemId, source.id)
                val remaining = store.itemSources(link.itemId)
                val item = store.itemById(link.itemId) ?: return@forEach
                if (remaining.isEmpty()) {
                    store.upsertItem(
                        item.copy(
                            available = false,
                            missingSince = now(),
                            updatedAt = now(),
                        ),
                    )
                    missing += 1
                } else {
                    val primary = remaining.minBy { it.sourceId + "\u0000" + it.fileUri }
                    store.upsertItem(
                        item.copy(
                            sourceId = primary.sourceId,
                            fileUri = primary.fileUri,
                            available = true,
                            missingSince = null,
                            updatedAt = now(),
                        ),
                    )
                }
            }
        return missing
    }

    private suspend fun writeEntities(item: LibraryItemEntity, resolved: ResolvedTrack, metadata: TagMetadata) {
        val artists = resolved.artistNames.zip(resolved.artistIds).map { (name, id) ->
            ArtistEntity(
                id = id,
                name = name,
                sortName = TagNormalizer.sortName(name),
                normalizationKey = TagNormalizer.normalize(name),
            )
        }
        val albumArtists = resolved.albumArtistNames.zip(resolved.albumArtistIds).map { (name, id) ->
            ArtistEntity(
                id = id,
                name = name,
                sortName = TagNormalizer.sortName(name),
                normalizationKey = TagNormalizer.normalize(name),
            )
        }
        store.upsertArtists((artists + albumArtists).distinctBy { it.id })

        resolved.albumId?.let { albumId ->
            store.upsertAlbums(
                listOf(
                    AlbumEntity(
                        id = albumId,
                        title = resolved.albumTitle,
                        sortTitle = resolved.albumSortTitle,
                        normalizationKey = TagNormalizer.normalize(
                            resolved.albumTitle + "\u0000" + resolved.albumArtistNames.firstOrNull().orEmpty(),
                        ),
                        year = metadata.year,
                        primaryAlbumArtistId = resolved.albumArtistId,
                    ),
                ),
            )
            store.upsertAlbumArtists(
                resolved.albumArtistIds.mapIndexed { index, id ->
                    AlbumArtistEntity(albumId = albumId, artistId = id, position = index)
                },
            )
        }

        val genres = resolved.genreNames.zip(resolved.genreIds).map { (name, id) ->
            GenreEntity(id = id, name = name, normalizationKey = TagNormalizer.normalize(name))
        }
        val composers = resolved.composerNames.zip(resolved.composerIds).map { (name, id) ->
            ComposerEntity(
                id = id,
                name = name,
                sortName = TagNormalizer.sortName(name),
                normalizationKey = TagNormalizer.normalize(name),
            )
        }
        store.upsertGenres(genres)
        store.upsertComposers(composers)
        store.upsertItem(item)

        // Tags can change; replace derived relationships rather than accreting stale entries.
        store.deleteTrackArtists(item.id)
        store.deleteTrackAlbumArtists(item.id)
        store.deleteTrackGenres(item.id)
        store.deleteTrackComposers(item.id)
        store.upsertTrackArtists(
            resolved.artistIds.mapIndexed { index, id -> TrackArtistEntity(item.id, id, index) },
        )
        store.upsertTrackAlbumArtists(
            resolved.albumArtistIds.mapIndexed { index, id -> TrackAlbumArtistEntity(item.id, id, index) },
        )
        store.upsertTrackGenres(resolved.genreIds.map { TrackGenreEntity(item.id, it) })
        store.upsertTrackComposers(resolved.composerIds.map { TrackComposerEntity(item.id, it) })
    }

    private fun buildItem(
        entry: ScanEntryEntity,
        source: LibrarySourceEntity,
        resolved: ResolvedTrack,
        metadata: TagMetadata,
        contentKey: String,
        existing: LibraryItemEntity?,
        id: String,
    ): LibraryItemEntity {
        val timestamp = now()
        return LibraryItemEntity(
            id = id,
            documentKey = entry.documentKey,
            fileUri = entry.fileUri,
            sourceId = source.id,
            displayPath = entry.relativePath.ifBlank { entry.name },
            cueKey = entry.cueKey,
            trackNumberInCue = entry.cueTrackNumber,
            cueStartMs = entry.cueStartMs,
            cueEndMs = entry.cueEndMs,
            fileModTime = entry.modTime,
            fileSize = entry.size,
            metadataVersion = (existing?.metadataVersion ?: 0) + 1,
            albumId = resolved.albumId,
            title = resolved.title,
            sortTitle = resolved.sortTitle,
            albumTitle = resolved.albumTitle,
            albumSortTitle = resolved.albumSortTitle,
            rawArtistNames = json.encodeToString(resolved.artistNames),
            rawAlbumArtistNames = json.encodeToString(resolved.albumArtistNames),
            rawGenreNames = json.encodeToString(resolved.genreNames),
            rawComposerNames = json.encodeToString(resolved.composerNames),
            copyright = TagAliases.first(metadata, TagAliases.copyrightKeys) ?: existing?.copyright,
            label = metadata.first("LABEL"),
            isrc = metadata.first("ISRC"),
            year = metadata.year ?: existing?.year,
            trackNumber = metadata.track ?: existing?.trackNumber,
            discNumber = metadata.disc ?: existing?.discNumber,
            totalTracks = existing?.totalTracks,
            totalDiscs = existing?.totalDiscs,
            durationSeconds = metadata.durationMs / 1000,
            bitrate = metadata.bitrate,
            sampleRate = metadata.sampleRate,
            bitDepth = metadata.bitsPerSample,
            codec = null,
            format = entry.name.substringAfterLast('.', "").ifBlank { null },
            releaseDate = resolved.releaseDate,
            isFavorite = existing?.isFavorite ?: false,
            rating = existing?.rating,
            playCount = existing?.playCount ?: 0,
            artworkKey = existing?.artworkKey,
            audioFingerprint = existing?.audioFingerprint,
            contentKey = contentKey,
            addedAt = existing?.addedAt ?: timestamp,
            updatedAt = timestamp,
            available = true,
            missingSince = null,
            otherMetadataJson = json.encodeToString(metadata.rawTags),
            analysisStatus = existing?.analysisStatus ?: "NONE",
        )
    }

    private fun mergeCue(entry: ScanEntryEntity, metadata: TagMetadata): TagMetadata {
        if (entry.cueKey == null) return metadata
        val rawTags = metadata.rawTags.toMutableMap()
        entry.cueTitle?.let { rawTags["TITLE"] = listOf(it) }
        entry.cuePerformer?.let { rawTags["ARTIST"] = listOf(it) }
        return metadata.copy(
            title = entry.cueTitle ?: metadata.title,
            artist = entry.cuePerformer ?: metadata.artist,
            track = entry.cueTrackNumber ?: metadata.track,
            rawTags = rawTags,
        )
    }

    private fun contentKey(entry: ScanEntryEntity, metadata: TagMetadata, resolved: ResolvedTrack): String {
        val parts = listOf(
            entry.size?.toString().orEmpty(),
            metadata.durationMs.toString(),
            resolved.title,
            resolved.artistNames.joinToString("|"),
            resolved.albumTitle,
            metadata.track?.toString().orEmpty(),
            entry.cueStartMs?.toString().orEmpty(),
            entry.cueEndMs?.toString().orEmpty(),
        )
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        return "ck_" + digest.take(16).joinToString("") { "%02x".format(it) }
    }
}


/**
 * Removes one root's memberships, re-pointing items that another root still provides.
 * Returns the number of items that lost their last membership.
 */
suspend fun detachSource(store: LibraryStore, sourceId: String): Int {
    var orphaned = 0
    store.itemSourcesForSource(sourceId).sortedWith(compareBy({ it.itemId }, { it.fileUri })).forEach { link ->
        store.deleteItemSource(link.itemId, sourceId)
        val remaining = store.itemSources(link.itemId)
        val item = store.itemById(link.itemId) ?: return@forEach
        if (remaining.isEmpty()) {
            orphaned += 1
        } else {
            val primary = remaining.minBy { it.sourceId + "\u0000" + it.fileUri }
            store.upsertItem(
                item.copy(
                    sourceId = primary.sourceId,
                    fileUri = primary.fileUri,
                    available = true,
                    missingSince = null,
                ),
            )
        }
    }
    store.deleteOrphanItemsForSource(sourceId)
    return orphaned
}
