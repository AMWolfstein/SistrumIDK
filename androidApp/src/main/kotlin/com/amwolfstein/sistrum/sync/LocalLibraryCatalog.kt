package com.amwolfstein.sistrum.sync

import android.content.Context
import com.amwolfstein.sistrum.library.db.ArtworkAssetEntity
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.player.TrackAnalysis
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed catalog for every library screen. Reads are local and offline; playlist writes are
 * still delegated to the legacy store until the local playlist repository (T13) replaces them.
 */
internal class LocalLibraryCatalog(
    context: Context,
    private val database: LibraryDatabase = LibraryDatabase.get(context),
    private val legacy: AndroidLibrarySyncStore? = null,
) : LibraryCatalog {
    private val dao = database.libraryDao()
    private val artworkDirectory = File(context.filesDir, "artwork")

    private val artworkPathsFlow: Flow<Map<String, String>> = dao.observeArtworkAssets().map { assets ->
        assets.associate { asset -> asset.key to assetFile(asset).absolutePath }
    }

    override val artworkPaths: Flow<Map<String, String>> = artworkPathsFlow

    override val tracks: Flow<List<LibraryTrack>> =
        combine(dao.observeLocalTracks(), artworkPathsFlow) { rows, paths ->
            rows.map { row -> row.toLibraryTrack(row.item.artworkKey?.let(paths::get)) }
        }

    override val albums: Flow<List<LibraryAlbum>> =
        combine(dao.observeLocalAlbums(), artworkPathsFlow) { rows, paths ->
            rows.map { row ->
                row.toLibraryAlbum(
                    row.album.artworkKey?.let(paths::get),
                    fallbackArtist = "",
                )
            }
        }

    override val artists: Flow<List<LibraryArtist>> =
        combine(dao.observeLocalArtists(), artworkPathsFlow) { rows, paths ->
            rows.map { row -> row.toLibraryArtist(row.artist.artworkKey?.let(paths::get)) }
        }

    override val genres: Flow<List<LibraryGenre>> =
        dao.observeLocalGenres().map { rows -> rows.map { it.toLibraryGenre() } }

    override val composers: Flow<List<LibraryComposer>> =
        combine(dao.observeLocalComposers(), artworkPathsFlow) { rows, paths ->
            rows.map { row -> row.toLibraryComposer(null) }
        }

    /** Local playlists are imported in T02; edits still go through the legacy queue until T13. */
    override val playlists: Flow<List<LibraryPlaylist>> =
        legacy?.playlists ?: flowOf(emptyList())

    override val lastListenedAt: Flow<Map<String, Long>> =
        dao.observeLocalLastListened().map { rows -> rows.associate { it.trackId to it.lastListenedAt } }

    /**
     * The search screen narrows through these ids and then matches in memory; an empty list is
     * treated as "no narrowing" so local search still returns results.
     */
    override fun searchCandidates(query: String): Flow<List<LibrarySearchCandidate>> = flowOf(emptyList())

    override suspend fun analysis(trackId: String): TrackAnalysis? = withContext(Dispatchers.IO) {
        val row = dao.analysis(trackId) ?: return@withContext null
        val loudness = row.loudnessLufs ?: return@withContext null
        val peak = row.truePeak ?: return@withContext null
        TrackAnalysis(loudness.toFloat(), peak.toFloat())
    }

    override suspend fun setRating(trackId: String, rating: Int?) {
        require(rating == null || rating in 0..5) { "rating must be between 0 and 5" }
        dao.updateRating(trackId, rating)
    }

    override suspend fun setFavorite(trackId: String, favorite: Boolean) {
        dao.updateFavorite(trackId, favorite)
    }

    override suspend fun queuePlaylistMutation(mutation: PlaylistMutation) {
        requireNotNull(legacy) { "Local playlist writes are not implemented until T13" }
            .queuePlaylistMutation(mutation)
    }

    override suspend fun createLocalPlaylist(
        mutation: PlaylistMutation,
        artwork: StagedPlaylistArtwork?,
        artworkMutationId: String?,
        initialTrackIds: List<String>,
    ) {
        requireNotNull(legacy) { "Local playlist writes are not implemented until T13" }
            .createLocalPlaylist(mutation, artwork, artworkMutationId, initialTrackIds)
    }

    override suspend fun discardFailedLocalPlaylist(playlistId: String): Boolean =
        legacy?.discardFailedLocalPlaylist(playlistId) ?: false

    override suspend fun stagePlaylistArtwork(value: StagedPlaylistArtwork) {
        requireNotNull(legacy) { "Local playlist writes are not implemented until T13" }
            .stagePlaylistArtwork(value)
    }

    private fun assetFile(asset: ArtworkAssetEntity): File {
        val extension = asset.path.substringAfterLast('.', "jpg")
        val key = asset.key
        return File(artworkDirectory, "${key.take(2)}/$key.$extension")
    }
}
