package com.amwolfstein.sistrum.player

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.amwolfstein.sistrum.sync.AndroidLibrarySyncStore
import com.amwolfstein.sistrum.sync.LibraryTrack
import com.amwolfstein.sistrum.sync.metadataObject

/** Application-scoped composition root for Android playback. */
internal object AndroidPlaybackRuntime {
    private lateinit var appContext: Context
    private lateinit var syncStore: AndroidLibrarySyncStore
    private lateinit var resolver: PlaybackItemResolver
    private var catalog: com.amwolfstein.sistrum.sync.LibraryCatalog? = null

    fun initialize(
        context: Context,
        syncStore: AndroidLibrarySyncStore,
        libraryCatalog: com.amwolfstein.sistrum.sync.LibraryCatalog? = null,
    ) {
        if (::appContext.isInitialized) {
            if (catalog == null) catalog = libraryCatalog
            return
        }
        appContext = context.applicationContext
        this.syncStore = syncStore
        catalog = libraryCatalog
        resolver = PlaybackItemResolver { trackId ->
            val local = catalog?.tracks?.first()?.firstOrNull { it.id == trackId }
            val track = local ?: syncStore.tracks.first().firstOrNull { it.id == trackId }
            if (track == null) {
                Log.w(PlaybackLogTag, "Resolve failed: track is not in the library id=$trackId")
                null
            } else {
                val audioPath = resolvePlayablePath(track.audioPath)
                if (audioPath == null) {
                    Log.w(PlaybackLogTag, "Resolve failed: audio missing id=$trackId path=${track.audioPath}")
                    null
                } else {
                    val artwork = track.artworkPath?.let(::File)?.takeIf(File::isFile)?.absolutePath
                        ?: resolveSyncedAudioFile(appContext.filesDir, track.artworkPath)?.takeIf(File::isFile)?.absolutePath
                    Log.d(PlaybackLogTag, "Resolved audio id=$trackId path=$audioPath artwork=${artwork != null}")
                    val metadata = track.metadataObject()
                    fun firstName(key: String): String = ((metadata?.get(key) as? JsonArray)?.firstOrNull() as? JsonObject)
                        ?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
                    val analysis = if (local != null) catalog?.analysis(track.id) else syncStore.analysis(track.id)
                    PlaybackItem(
                        trackId = track.id,
                        title = track.title,
                        artist = playbackArtistNames(metadata, track.artists),
                        audioPath = audioPath,
                        artworkPath = artwork,
                        albumId = track.albumId,
                        analysis = analysis,
                        album = track.album,
                        albumArtist = firstName("album_artists"),
                        trackNumber = track.trackNumber,
                    )
                }
            }
        }
    }

    /** Validates a restored queue with one library snapshot, without constructing playback items. */
    suspend fun availableTrackIds(trackIds: Collection<String>): Set<String> {
        check(::appContext.isInitialized) { "AndroidPlaybackRuntime is not initialized" }
        if (trackIds.isEmpty()) return emptySet()
        val local = catalog?.tracks?.first().orEmpty()
        val localAvailable = local.asSequence()
            .filter { it.id in trackIds }
            .filter { resolvePlayablePath(it.audioPath) != null }
            .mapTo(LinkedHashSet()) { it.id }
        val legacy = availableSyncedTrackIds(appContext.filesDir, syncStore.tracks.first(), trackIds.toSet())
        return localAvailable + legacy
    }

    /** Returns a decoder-consumable path: the SAF URI when it opens, else an existing file. */
    private fun resolvePlayablePath(storedPath: String?): String? {
        if (storedPath.isNullOrBlank()) return null
        if (storedPath.startsWith("content://")) {
            return runCatching {
                appContext.contentResolver.openFileDescriptor(android.net.Uri.parse(storedPath), "r")?.use { storedPath }
            }.getOrNull()
        }
        if (storedPath.startsWith("file://")) {
            return android.net.Uri.parse(storedPath).path?.let(::File)?.takeIf(File::isFile)?.absolutePath
        }
        return resolveSyncedAudioFile(appContext.filesDir, storedPath)?.takeIf(File::isFile)?.absolutePath
    }

    /** Metadata snapshot for the media session: local catalog first, legacy mirror as fallback. */
    suspend fun tracksSnapshot(): List<LibraryTrack> {
        val local = catalog?.tracks?.first().orEmpty()
        val localIds = local.mapTo(mutableSetOf()) { it.id }
        return local + syncStore.tracks.first().filter { it.id !in localIds }
    }

    fun controller(): PlaybackController {
        check(::appContext.isInitialized) { "AndroidPlaybackRuntime is not initialized" }
        return PlaybackController(appContext, resolver)
    }
}

internal fun playbackArtistNames(metadata: JsonObject?, fallback: String): String =
    ((metadata?.get("artists") as? JsonArray).orEmpty())
        .mapNotNull { artist ->
            ((artist as? JsonObject)?.get("name") as? JsonPrimitive)
                ?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }
        .joinToString(", ")
        .ifBlank { fallback }

internal fun resolveSyncedAudioFile(filesDir: File, storedPath: String?): File? = storedPath?.let { path ->
    File(path).let { candidate -> if (candidate.isAbsolute) candidate else File(filesDir, path) }
}

internal fun availableSyncedTrackIds(
    filesDir: File,
    tracks: List<LibraryTrack>,
    requestedTrackIds: Set<String>,
): Set<String> = tracks.asSequence()
    .filter { it.id in requestedTrackIds }
    .filter { resolveSyncedAudioFile(filesDir, it.audioPath)?.isFile == true }
    .mapTo(LinkedHashSet()) { it.id }
