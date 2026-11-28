package com.amwolfstein.sistrum.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import com.amwolfstein.sistrum.library.playlist.M3u8Codec
import com.amwolfstein.sistrum.library.playlist.M3u8Entry
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.amwolfstein.sistrum.sync.LibraryCatalog
import com.amwolfstein.sistrum.sync.LibraryPlaylist
import com.amwolfstein.sistrum.sync.LibrarySyncProtocol
import com.amwolfstein.sistrum.sync.LibraryTrack
import com.amwolfstein.sistrum.sync.metadataObject
import com.amwolfstein.sistrum.sync.PlaylistMutation
import com.amwolfstein.sistrum.sync.PlaylistMutationOperation
import com.amwolfstein.sistrum.sync.PlaylistMutationPayload
import com.amwolfstein.sistrum.sync.stagePlaylistArtwork
import java.util.UUID
import java.io.File
import kotlinx.serialization.json.longOrNull

internal data class PlaylistListItem(
    val id: String,
    val name: String,
    val trackIds: List<String> = emptyList(),
    val artworkPaths: List<String> = emptyList(),
    val customArtworkPath: String? = null,
    val syncFailed: Boolean = false,
) { val isFavorite: Boolean get() = id == FavoritesPlaylistId }

internal data class LibraryPlaylistsUiState(
    val playlists: List<PlaylistListItem> = emptyList(),
    val isLoaded: Boolean = true,
)

internal class LibraryPlaylistsViewModel(private val context: Context, syncStore: LibraryCatalog) : ViewModel() {
    class Factory(private val context: Context, private val syncStore: LibraryCatalog) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryPlaylistsViewModel(context, syncStore) as T
    }

    private val syncStore = syncStore
    private val _createdPlaylistIds = MutableSharedFlow<String>()
    val createdPlaylistIds: SharedFlow<String> = _createdPlaylistIds.asSharedFlow()

    val uiState: StateFlow<LibraryPlaylistsUiState> = combine(
        syncStore.playlists,
        syncStore.tracks,
        syncStore.artworkPaths,
    ) { playlists, tracks, artworkPaths ->
        val availableTrackIds = tracks.mapTo(mutableSetOf(), LibraryTrack::id)
        LibraryPlaylistsUiState(isLoaded = true, playlists = playlistsWithFavorites(playlists, tracks).map { playlist ->
            PlaylistListItem(
                playlist.id,
                playlist.name,
                playlist.trackIds.filter { it in availableTrackIds },
                playlistArtworkPaths(playlist, tracks, artworkPaths),
                playlistManualArtworkPath(playlist, artworkPaths),
                playlist.syncFailed,
            )
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryPlaylistsUiState(isLoaded = false))

    fun createPlaylist(rawName: String, artworkUri: Uri? = null, initialTrackIds: List<String> = emptyList()) {
        val name = rawName.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            val playlistId = UUID.randomUUID().toString()
            val stagedArtwork = artworkUri?.let { uri ->
                try {
                    withContext(Dispatchers.IO) { stagePlaylistArtwork(context.contentResolver, context.filesDir, uri) }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    Log.w("LibraryPlaylists", "Ignoring unreadable playlist artwork", error)
                    null
                }
            }
            syncStore.createLocalPlaylist(
                PlaylistMutation(
                    mutationId = UUID.randomUUID().toString(),
                    playlistId = playlistId,
                    operation = PlaylistMutationOperation.CREATE,
                    updatedAt = System.currentTimeMillis(),
                    payload = PlaylistMutationPayload(name = name),
                ),
                artwork = stagedArtwork,
                artworkMutationId = stagedArtwork?.let { UUID.randomUUID().toString() },
                initialTrackIds = initialTrackIds,
            )
            _createdPlaylistIds.emit(playlistId)
        }
    }

    /** Exports any playlist, including favorites and later smart playlists, from resolved membership. */
    fun exportM3u8(playlistId: String, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val playlists = syncStore.playlists.first()
                val tracks = syncStore.tracks.first()
                val playlist = playlists.firstOrNull { it.id == playlistId } ?: return@launch
                val byId = tracks.associateBy { it.id }
                val entries = playlist.trackIds.mapNotNull(byId::get).mapNotNull { track ->
                    val path = track.audioPath ?: return@mapNotNull null
                    M3u8Entry(
                        path = path,
                        durationSeconds = track.metadataObject().durationSeconds(),
                        title = track.title,
                        artist = track.artists,
                    )
                }
                val content = M3u8Codec.export(entries, null)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")
                        ?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                        ?: error("Unable to open the selected file")
                }
            }.onFailure { Log.w("LibraryPlaylists", "M3U8 export failed", it) }
        }
    }

    fun importM3u8(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: error("Unable to read the selected playlist")
                val entries = M3u8Codec.parse(M3u8Codec.decode(bytes))
                val tracks = syncStore.tracks.first()
                val ids = entries.mapNotNull { entry ->
                    val resolved = M3u8Codec.filePathOf(entry.path)?.let { path ->
                        M3u8Codec.resolve(path, null)
                    }
                    tracks.firstOrNull { track ->
                        track.audioPath == entry.path ||
                            resolved != null && track.audioPath?.let { File(it).name } == File(resolved).name
                    }?.id
                }.distinct()
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() } ?: "Imported playlist"
                createPlaylist(name, null, ids)
            }.onFailure { Log.w("LibraryPlaylists", "M3U8 import failed", it) }
        }
    }

    fun createSmartPlaylist(
        name: String,
        config: com.amwolfstein.sistrum.library.playlist.SmartPlaylistConfig,
        onCreated: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching {
                val repository = com.amwolfstein.sistrum.library.playlist.LocalPlaylistRepository(context)
                repository.createSmart(name, config, syncStore.tracks.first())
            }.onSuccess(onCreated)
                .onFailure { Log.w("LibraryPlaylists", "Smart playlist create failed", it) }
        }
    }

    fun changePlaylistMembership(playlistId: String, trackIds: List<String>, add: Boolean) {
        if (playlistId == FavoritesPlaylistId) return
        val operation = if (add) PlaylistMutationOperation.ADD_TRACK else PlaylistMutationOperation.REMOVE_TRACK
        viewModelScope.launch {
            trackIds.distinct().filter(String::isNotBlank).forEach { trackId ->
                syncStore.queuePlaylistMutation(
                    PlaylistMutation(
                        mutationId = UUID.randomUUID().toString(),
                        playlistId = playlistId,
                        operation = operation,
                        updatedAt = System.currentTimeMillis(),
                        payload = PlaylistMutationPayload(trackId = trackId),
                    ),
                )
            }
        }
    }

    fun updatePlaylist(playlistId: String, rawName: String, artworkUri: Uri? = null, clearArtwork: Boolean = false) {
        val name = rawName.trim()
        val canRename = playlistId != FavoritesPlaylistId
        if (canRename && name.isBlank()) return
        viewModelScope.launch {
            val updatedAt = System.currentTimeMillis()
            if (canRename) {
                syncStore.queuePlaylistMutation(
                    PlaylistMutation(UUID.randomUUID().toString(), playlistId, PlaylistMutationOperation.UPDATE, updatedAt, PlaylistMutationPayload(name = name)),
                )
            }
            artworkUri?.let { uri ->
                val staged = try {
                    withContext(Dispatchers.IO) { stagePlaylistArtwork(context.contentResolver, context.filesDir, uri) }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    Log.w("LibraryPlaylists", "Ignoring unreadable playlist artwork", error)
                    null
                }
                staged?.let {
                    syncStore.stagePlaylistArtwork(it)
                    syncStore.queuePlaylistMutation(
                        PlaylistMutation(UUID.randomUUID().toString(), playlistId, PlaylistMutationOperation.SET_ARTWORK, updatedAt + 1, PlaylistMutationPayload(artworkSha256 = it.sha256)),
                    )
                }
            }
            if (clearArtwork) {
                syncStore.queuePlaylistMutation(
                    PlaylistMutation(UUID.randomUUID().toString(), playlistId, PlaylistMutationOperation.REMOVE_ARTWORK, updatedAt + 1),
                )
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        if (playlistId == FavoritesPlaylistId) return
        viewModelScope.launch {
            if (syncStore.discardFailedLocalPlaylist(playlistId)) return@launch
            syncStore.queuePlaylistMutation(
                PlaylistMutation(UUID.randomUUID().toString(), playlistId, PlaylistMutationOperation.DELETE, System.currentTimeMillis()),
            )
        }
    }
}

internal const val FavoritesPlaylistId = "favorites"

internal fun playlistsWithFavorites(
    playlists: List<LibraryPlaylist>,
    tracks: List<LibraryTrack> = emptyList(),
): List<LibraryPlaylist> {
    val favorites = playlists.firstOrNull { it.id == FavoritesPlaylistId }
        ?: LibraryPlaylist(FavoritesPlaylistId, "", emptyList(), "{}")
    val derivedFavorites = favorites.copy(trackIds = tracks.filter(LibraryTrack::isFavorite).map(LibraryTrack::id))
    return listOf(derivedFavorites) + playlists.filterNot { it.id == FavoritesPlaylistId }
}

internal fun playlistArtworkPaths(
    playlist: LibraryPlaylist,
    tracks: List<LibraryTrack>,
    artworkPaths: Map<String, String>,
): List<String> {
    playlistArtworkKey(playlist.metadataJson)?.let { key ->
        artworkPaths[key]?.let { return listOf(it) }
    }
    val tracksById = tracks.associateBy { it.id }
    return playlist.trackIds.asSequence()
        .mapNotNull { tracksById[it] }
        .mapNotNull { track -> track.artworkKey?.let { key -> key to track.artworkPath } }
        .filter { (_, path) -> !path.isNullOrBlank() }
        .distinctBy { (key, _) -> key }
        .take(4)
        .map { (_, path) -> path!! }
        .toList()
}

internal fun playlistManualArtworkPath(
    playlist: LibraryPlaylist,
    artworkPaths: Map<String, String>,
): String? = playlistArtworkKey(playlist.metadataJson)?.let(artworkPaths::get)

private fun playlistArtworkKey(metadataJson: String): String? = runCatching {
    val root = LibrarySyncProtocol.json.parseToJsonElement(metadataJson) as? JsonObject
    val playlist = root?.get("playlist") as? JsonObject ?: root
    (playlist?.get("artwork_key") as? JsonPrimitive)?.contentOrNull
}.getOrNull()?.takeIf { it.isNotBlank() }


private fun kotlinx.serialization.json.JsonObject?.durationSeconds(): Int? =
    (this?.get("duration") as? JsonPrimitive)?.longOrNull?.toInt()
