package com.amwolfstein.sistrum.sync

import com.amwolfstein.sistrum.library.db.LocalAlbumRow
import com.amwolfstein.sistrum.library.db.LocalArtistRow
import com.amwolfstein.sistrum.library.db.LocalComposerRow
import com.amwolfstein.sistrum.library.db.LocalGenreRow
import com.amwolfstein.sistrum.library.db.LocalTrackRow
import com.amwolfstein.sistrum.player.TrackAnalysis
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.amwolfstein.sistrum.library.tags.TagNormalizer

/**
 * The read surface the library screens need. `AndroidLibrarySyncStore` is the temporary legacy
 * implementation; [LocalLibraryCatalog] is the Room-backed one that replaces it.
 */
internal interface LibraryCatalog {
    val tracks: Flow<List<LibraryTrack>>
    val artists: Flow<List<LibraryArtist>>
    val albums: Flow<List<LibraryAlbum>>
    val genres: Flow<List<LibraryGenre>>
    val composers: Flow<List<LibraryComposer>>
    val playlists: Flow<List<LibraryPlaylist>>
    val artworkPaths: Flow<Map<String, String>>
    val lastListenedAt: Flow<Map<String, Long>>

    fun searchCandidates(query: String): Flow<List<LibrarySearchCandidate>>
    suspend fun analysis(trackId: String): TrackAnalysis?

    suspend fun queuePlaylistMutation(mutation: PlaylistMutation)
    suspend fun createLocalPlaylist(
        mutation: PlaylistMutation,
        artwork: StagedPlaylistArtwork? = null,
        artworkMutationId: String? = null,
        initialTrackIds: List<String> = emptyList(),
    )
    suspend fun discardFailedLocalPlaylist(playlistId: String): Boolean
    suspend fun stagePlaylistArtwork(value: StagedPlaylistArtwork)

    /** Local ratings are a Sistrum extension; the legacy catalog has no rating column. */
    suspend fun setRating(trackId: String, rating: Int?)
    suspend fun setFavorite(trackId: String, favorite: Boolean)
}

/** Names joined by `char(1)` in the catalog queries; a name can never contain that control byte. */
internal fun splitCatalogNames(value: String?): List<String> =
    value?.split('\u0001')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

/**
 * Builds the desktop-compatible metadata JSON that list/detail screens and the player already
 * read, from local Room values.
 */
internal fun localTrackMetadataJson(row: LocalTrackRow): String {
    val item = row.item
    val artists = splitCatalogNames(row.artistNames)
    val albumArtists = splitCatalogNames(row.albumArtistNames)
    val genres = splitCatalogNames(row.genreNames)
    val composers = splitCatalogNames(row.composerNames)
    return buildJsonObject {
        put("title", item.title)
        put("sort_title", item.sortTitle)
        put("album", item.albumTitle)
        put("is_favorite", item.isFavorite)
        item.rating?.let { put("rating", it) }
        put("duration", item.durationSeconds)
        put("track_number", item.trackNumber ?: 0)
        put("disc_number", item.discNumber ?: 0)
        item.fileSize?.let { put("file_size", it) }
        item.bitrate?.let { put("bitrate", it) }
        item.sampleRate?.let { put("sample_rate", it) }
        item.bitDepth?.let { put("bit_depth", it) }
        item.codec?.let { put("codec", it) }
        item.format?.let { put("format", it) }
        item.year?.let { put("year", it) }
        item.releaseDate?.let { put("release_date", it) }
        item.label?.let { put("label", it) }
        item.isrc?.let { put("isrc", it) }
        item.copyright?.let { put("copyright", it) }
        put(
            "artists",
            buildJsonArray {
                artists.forEach { name ->
                    add(buildJsonObject { put("id", TagNormalizer.key("artist", name)); put("name", name) })
                }
            },
        )
        put(
            "album_artists",
            buildJsonArray {
                albumArtists.forEach { name ->
                    add(buildJsonObject { put("id", TagNormalizer.key("artist", name)); put("name", name) })
                }
            },
        )
        put("genres", buildJsonArray { genres.forEach { add(JsonPrimitive(it)) } })
        put("composers", buildJsonArray { composers.forEach { add(JsonPrimitive(it)) } })
    }.toString()
}

internal fun LocalTrackRow.toLibraryTrack(artworkPath: String?): LibraryTrack = LibraryTrack(
    id = item.id,
    title = trackDisplayTitle(item.title),
    sortTitle = item.sortTitle,
    artists = trackDisplayArtists(splitCatalogNames(artistNames).joinToString(", ")),
    sortArtists = splitCatalogNames(artistNames).joinToString(", "),
    album = item.albumTitle,
    albumId = item.albumId.orEmpty(),
    artworkKey = item.artworkKey,
    playCount = item.playCount,
    createdAt = item.addedAt.toString(),
    updatedAt = item.updatedAt.toString(),
    discNumber = item.discNumber ?: 0,
    trackNumber = item.trackNumber ?: 0,
    metadataJson = localTrackMetadataJson(this),
    artworkPath = artworkPath,
    // SAF documents are opened through ContentResolver by the playback service.
    audioPath = item.fileUri,
    rating = item.rating,
)

internal fun LocalAlbumRow.toLibraryAlbum(artworkPath: String?, fallbackArtist: String): LibraryAlbum =
    LibraryAlbum(
        id = album.id,
        title = album.title,
        artist = artistName ?: fallbackArtist,
        copyright = album.copyright.orEmpty(),
        artworkPath = artworkPath,
        year = album.year ?: 0,
        sortTitle = album.sortTitle,
        sortArtist = artistName.orEmpty(),
    )

internal fun LocalArtistRow.toLibraryArtist(artworkPath: String?): LibraryArtist = LibraryArtist(
    id = artist.id,
    name = artist.name,
    artworkPath = artworkPath,
    sortName = artist.sortName,
)

internal fun LocalGenreRow.toLibraryGenre(): LibraryGenre = LibraryGenre(
    id = genre.id,
    name = genre.name,
    sortName = genre.name,
)

internal fun LocalComposerRow.toLibraryComposer(artworkPath: String?): LibraryComposer = LibraryComposer(
    id = composer.id,
    name = composer.name,
    artworkPath = artworkPath,
    sortName = composer.sortName,
)
