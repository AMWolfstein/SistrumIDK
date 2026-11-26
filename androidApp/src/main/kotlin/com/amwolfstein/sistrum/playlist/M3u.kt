package com.amwolfstein.sistrum.playlist

import com.amwolfstein.sistrum.sync.LibraryTrack
import java.io.File
import java.net.URI

internal data class ImportedPlaylist(val trackIds: List<String>, val unresolved: List<String>, val duplicates: Int)

/** Resolve exact paths first, then unique path suffixes for portable relative entries. */
internal fun importM3u(content: String, tracks: List<LibraryTrack>, directory: String? = null): ImportedPlaylist {
    val byPath = tracks.mapNotNull { track ->
        track.audioPath?.let { normalizePath(it) }?.let { it to track.id }
    }
    val resolved = mutableListOf<String>()
    val missing = mutableListOf<String>()
    content.removePrefix("\uFEFF").lineSequence().map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .forEach { entry ->
            val path = normalizePath(entry)
            val absolute = if (directory != null && !path.startsWith("/")) normalizePath(File(directory, path).path) else path
            val exact = byPath.filter { it.first == absolute }
            val matches = if (exact.isNotEmpty() || path.startsWith("/")) exact
                else byPath.filter { it.first.endsWith("/$path") || it.first == path }
            val id = matches.singleOrNull()?.second
            if (id == null) missing.add(entry) else resolved.add(id)
        }
    return ImportedPlaylist(resolved.distinct(), missing, resolved.size - resolved.distinct().size)
}

internal data class ExportedPlaylist(val content: String, val unresolved: List<String>)

internal fun exportM3u(trackIds: List<String>, tracks: List<LibraryTrack>, directory: String? = null): ExportedPlaylist {
    val byId = tracks.associateBy { it.id }
    val missing = mutableListOf<String>()
    val output = buildString {
        append("#EXTM3U\n")
        trackIds.forEach { id ->
            val track = byId[id]
            val path = track?.audioPath
            if (path.isNullOrBlank() || path.contains('\n') || path.contains('\r')) {
                missing.add(track?.title ?: id)
            } else {
                val normalized = File(path).normalize()
                val relative = directory?.let { base ->
                    runCatching {
                        val folder = File(base).normalize()
                        if (folder.isAbsolute && normalized.isAbsolute) normalized.relativeTo(folder).path else null
                    }.getOrNull()
                }
                val locator = relative ?: path
                val safeLocator = when {
                    locator != locator.trimEnd() -> normalized.toURI().toASCIIString()
                    locator.isNotEmpty() && !locator.startsWith("/") && (locator.startsWith("#") || locator.first().isWhitespace()) -> "./$locator"
                    else -> locator
                }
                append(safeLocator).append('\n')
            }
        }
    }
    return ExportedPlaylist(output, missing)
}

private fun normalizePath(value: String): String {
    val clean = value.replace('\\', '/')
    val path = if (clean.startsWith("file:", ignoreCase = true)) {
        runCatching { URI(clean).path }.getOrNull() ?: clean
    } else clean
    return File(path).normalize().path
}
