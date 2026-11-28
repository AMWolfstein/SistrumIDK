package com.amwolfstein.sistrum.library.artwork

import android.content.Context
import android.net.Uri
import com.amwolfstein.sistrum.library.db.ArtworkAssetEntity
import com.amwolfstein.sistrum.library.db.LibraryDao
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.scan.FolderCover
import com.amwolfstein.sistrum.library.tags.MetadataExtractor
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Owner-visible cache numbers for the Online Artwork page. */
data class ArtworkCacheSummary(val assetCount: Int, val bytes: Long)

/**
 * Owns artwork population and artist precedence.
 *
 * Embedded art is imported only for items that have no artwork yet, so a scan never re-reads art
 * for the whole library. Folder covers apply to the folder they were found in. Artist artwork is
 * Deezer (if enabled and imported) or the latest released track's artwork, never the reverse.
 */
internal class ArtworkLibraryService(
    private val context: Context,
    database: LibraryDatabase = LibraryDatabase.get(context),
    private val deezer: DeezerArtworkClient = DeezerArtworkClient(),
    private val preferences: ArtworkPreferences = ArtworkPreferences(context),
    private val settingsProvider: suspend () -> ArtworkSettings = { preferences.settings.first() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao: LibraryDao = database.libraryDao()
    private val cache = ArtworkCache(context, dao, now)
    private val extractor = MetadataExtractor(context)

    /** Reads embedded artwork for one track and propagates it to an album that has none. */
    suspend fun refreshItemArtwork(itemId: String): String? = withContext(Dispatchers.IO) {
        val item = dao.itemById(itemId) ?: return@withContext null
        val bytes = extractor.read(Uri.parse(item.fileUri), includeArtwork = true)
            .getOrNull()
            ?.artwork
            ?: return@withContext null
        val asset = cache.store(bytes, kind = "EMBEDDED", origin = "TAG") ?: return@withContext null
        applyToItem(item, asset)
        asset.key
    }

    suspend fun populateMissingArtwork(limit: Int = 32): Int = withContext(Dispatchers.IO) {
        var imported = 0
        dao.itemsMissingArtwork(limit).forEach { item ->
            if (refreshItemArtwork(item.id) != null) imported += 1
        }
        imported
    }

    /**
     * Reads embedded artwork for the whole library in bounded pages. Successful items leave the
     * "missing" set, so repeated pages advance; a per-run budget protects long scans.
     */
    suspend fun populateAllMissingArtwork(budget: Int = 5_000, page: Int = 64): Int = withContext(Dispatchers.IO) {
        var imported = 0
        var scanned = 0
        var cursor = ""
        while (scanned < budget) {
            val batch = dao.itemsMissingArtworkAfter(cursor, minOf(page, budget - scanned))
            if (batch.isEmpty()) break
            batch.forEach { item ->
                scanned += 1
                if (refreshItemArtwork(item.id) != null) imported += 1
            }
            cursor = batch.last().id
        }
        imported
    }

    /**
     * Applies cover images found by the scanner to items in that folder that still have no
     * artwork. Reading a cover is bounded and a broken image never fails the scan.
     */
    suspend fun applyFolderCovers(sourceId: String, covers: List<FolderCover>): Int =
        withContext(Dispatchers.IO) {
            if (covers.isEmpty()) return@withContext 0
            val assets = mutableMapOf<String, ArtworkAssetEntity>()
            covers.forEach { cover ->
                val bytes = readBounded(Uri.parse(cover.fileUri)) ?: return@forEach
                cache.store(bytes, kind = "FOLDER", origin = "SAF")?.let { assets[cover.folderPath] = it }
            }
            if (assets.isEmpty()) return@withContext 0
            var applied = 0
            val byFolder = assets.keys
            var cursor = ""
            while (true) {
                val page = dao.itemsMissingArtworkForSourceAfter(sourceId, cursor, PAGE_SIZE)
                if (page.isEmpty()) break
                page.forEach { item ->
                    val asset = assets[parentPath(item.displayPath.orEmpty())] ?: return@forEach
                    applyToItem(item, asset)
                    applied += 1
                }
                cursor = page.last().id
            }
            applied
        }

    /**
     * Resolves one artist: durable Deezer import, then latest released track artwork, then
     * placeholder (no key). A network failure or disabled toggle keeps whatever is displayed.
     */
    suspend fun resolveArtist(artistId: String, allowOnline: Boolean = true): String? =
        withContext(Dispatchers.IO) {
            val artist = dao.artist(artistId) ?: return@withContext null
            val durableDeezer = artist.artworkSource == SOURCE_DEEZER &&
                artist.artworkKey != null && dao.artwork(artist.artworkKey) != null
            if (durableDeezer) return@withContext artist.artworkKey
            val online = allowOnline && settingsProvider().deezerEnabled
            if (online) {
                val imported = importDeezer(artist.name)
                if (imported != null) {
                    dao.updateArtistArtwork(artistId, imported, SOURCE_DEEZER, now())
                    return@withContext imported
                }
            }
            val fallback = ArtworkPrecedence.selectTrackFallback(
                dao.artworkCandidatesForArtist(artistId).map {
                    TrackArtworkCandidate(it.trackId, it.releaseDate, it.artworkKey)
                },
            )
            if (!durableDeezer) {
                // No usable track art means the placeholder; a stale Deezer row whose asset
                // disappeared is downgraded instead of pointing at a missing file.
                dao.updateArtistArtwork(artistId, fallback, fallback?.let { SOURCE_TRACK }, now())
            }
            fallback
        }

    suspend fun refreshArtistFallbacks(limit: Int = 200): Int = withContext(Dispatchers.IO) {
        var updated = 0
        // Oldest-recomputed artists come first, so repeated scans advance past the alphabet.
        dao.artistsMissingDeezerArtwork(limit).forEach { artist ->
            val key = ArtworkPrecedence.selectTrackFallback(
                dao.artworkCandidatesForArtist(artist.id).map {
                    TrackArtworkCandidate(it.trackId, it.releaseDate, it.artworkKey)
                },
            )
            if (key != artist.artworkKey) updated += 1
            dao.updateArtistArtwork(artist.id, key, key?.let { SOURCE_TRACK }, now())
        }
        updated
    }

    /** Looks up Deezer for artists that do not have a durable import yet; bounded by [limit]. */
    suspend fun resolveArtistsOnline(limit: Int = 50): Int = withContext(Dispatchers.IO) {
        var imported = 0
        dao.artistsMissingDeezerArtwork(limit).forEach { artist ->
            val before = dao.artist(artist.id)?.artworkSource
            resolveArtist(artist.id, allowOnline = true)
            if (before != SOURCE_DEEZER && dao.artist(artist.id)?.artworkSource == SOURCE_DEEZER) {
                imported += 1
            }
        }
        imported
    }

    suspend fun refreshArtistsForItem(itemId: String, allowOnline: Boolean = false) {
        dao.artistsForItem(itemId).forEach { resolveArtist(it, allowOnline) }
    }

    /** Called after a scan publication: bounded embedded/folder population plus cleanup. */
    suspend fun onLibraryChanged(sourceId: String, covers: List<FolderCover>): ArtworkCacheSummary {
        populateAllMissingArtwork()
        applyFolderCovers(sourceId, covers)
        refreshArtistFallbacks(limit = 100)
        if (settingsProvider().deezerEnabled) resolveArtistsOnline(limit = 5)
        cache.cleanup()
        return summary()
    }

    suspend fun summary(): ArtworkCacheSummary =
        ArtworkCacheSummary(cache.assetCount(), cache.totalBytes())

    suspend fun clearUnreferenced(): ArtworkCacheSummary {
        cache.clearUnreferenced()
        return summary()
    }

    suspend fun cleanup(): Int = cache.cleanup()

    private suspend fun importDeezer(artistName: String): String? {
        val artist = deezer.searchArtist(artistName) ?: return null
        val bytes = deezer.download(artist.pictureUrl) ?: return null
        return cache.store(bytes, kind = "ARTIST", origin = "DEEZER")?.key
    }

    private suspend fun applyToItem(item: LibraryItemEntity, asset: ArtworkAssetEntity) {
        dao.updateItemArtwork(item.id, asset.key)
        val albumId = item.albumId ?: return
        val album = dao.album(albumId) ?: return
        if (album.artworkKey == null) dao.updateAlbumArtwork(albumId, asset.key, "TRACK")
    }

    private fun readBounded(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_IMAGE_BYTES) return@use null
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }.getOrNull()

    private fun parentPath(displayPath: String): String =
        displayPath.substringBeforeLast('/', "")

    private companion object {
        const val SOURCE_DEEZER = "DEEZER"
        const val SOURCE_TRACK = "TRACK"
        const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
        const val PAGE_SIZE = 500
    }
}
