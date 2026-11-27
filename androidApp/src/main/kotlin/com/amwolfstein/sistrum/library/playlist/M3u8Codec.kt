package com.amwolfstein.sistrum.library.playlist

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset

data class M3u8Entry(
    val path: String,
    val durationSeconds: Int? = null,
    val title: String? = null,
    val artist: String? = null,
)

/**
 * M3U8 import/export with the upstream defects fixed:
 * - a UTF-8 BOM and an `#EXTENC:` header are read instead of being treated as a path
 * - relative paths are resolved against the playlist's folder and exported as relative paths
 * - every playlist kind (manual, favorites, smart) can be exported from its resolved membership
 * - a UTF-8 header is always written
 */
object M3u8Codec {
    private const val UTF8_BOM = '\uFEFF'

    fun export(entries: List<M3u8Entry>, playlistFile: File? = null): String = buildString {
        append("#EXTM3U\n")
        append("#EXTENC:UTF-8\n")
        entries.forEach { entry ->
            append("#EXTINF:")
            append(entry.durationSeconds?.coerceAtLeast(0) ?: -1)
            if (!entry.artist.isNullOrBlank() || !entry.title.isNullOrBlank()) {
                append(',')
                append(listOfNotNull(entry.artist?.takeIf { it.isNotBlank() }, entry.title?.takeIf { it.isNotBlank() })
                    .joinToString(" - "))
            } else {
                append(',')
            }
            append('\n')
            append(exportPath(entry.path, playlistFile))
            append('\n')
        }
    }

    fun decode(bytes: ByteArray): String {
        val text = String(bytes, Charsets.UTF_8).removePrefix(UTF8_BOM.toString())
        val declared = text.lineSequence()
            .take(3)
            .firstOrNull { it.startsWith("#EXTENC:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
        if (declared.isNullOrBlank() || declared.equals("UTF-8", ignoreCase = true) ||
            declared.equals("UTF8", ignoreCase = true)
        ) {
            return text
        }
        return runCatching {
            String(bytes, Charset.forName(declared)).removePrefix(UTF8_BOM.toString())
        }.getOrDefault(text)
    }

    fun parse(text: String): List<M3u8Entry> {
        val lines = text.removePrefix(UTF8_BOM.toString()).lineSequence().map { it.trim() }.toList()
        val entries = mutableListOf<M3u8Entry>()
        var pending: M3u8Entry? = null
        lines.forEach { line ->
            when {
                line.isEmpty() || line.startsWith("#EXTM3U") || line.startsWith("#EXTENC:", ignoreCase = true) -> Unit
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val payload = line.substringAfter(':')
                    val duration = payload.substringBefore(',').trim().toIntOrNull()
                    val label = payload.substringAfter(',', "").trim()
                    val (artist, title) = splitLabel(label)
                    pending = M3u8Entry(path = "", durationSeconds = duration, title = title, artist = artist)
                }
                line.startsWith("#") -> Unit
                else -> {
                    entries += (pending ?: M3u8Entry(path = "")).copy(path = line)
                    pending = null
                }
            }
        }
        return entries.filter { it.path.isNotBlank() }
    }

    /** Resolves an exported entry against the playlist folder; URIs and absolute paths pass through. */
    fun resolve(entryPath: String, playlistFile: File?): String {
        if (entryPath.startsWith("content://") || entryPath.startsWith("http://") || entryPath.startsWith("https://")) {
            return entryPath
        }
        val file = File(entryPath)
        if (file.isAbsolute) return file.absolutePath
        val parent = playlistFile?.parentFile ?: return file.absolutePath
        return File(parent, entryPath).canonicalPath
    }

    /** Writes relative paths when the entry lives under the playlist folder; otherwise absolute. */
    fun exportPath(path: String, playlistFile: File?): String {
        if (path.startsWith("content://") || path.startsWith("http://") || path.startsWith("https://")) return path
        val parent = playlistFile?.parentFile?.canonicalFile ?: return path
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return path
        return runCatching {
            val relative = parent.toPath().relativize(file.toPath()).toString()
            if (relative.startsWith("..") || file.toPath().root != parent.toPath().root) {
                file.absolutePath
            } else {
                relative.replace(File.separatorChar, '/')
            }
        }.getOrDefault(path)
    }

    /** File-system path for a `file://` entry, used by importers that copy files. */
    fun filePathOf(entryPath: String): String? {
        if (!entryPath.startsWith("file://")) return entryPath.takeUnless { it.contains("://") }
        return runCatching { URI(entryPath).path?.let { URLDecoder.decode(it, "UTF-8") } }.getOrNull()
    }

    private fun splitLabel(label: String): Pair<String?, String?> {
        val index = label.indexOf(" - ")
        return if (index > 0) {
            label.substring(0, index).trim().takeIf { it.isNotEmpty() } to
                label.substring(index + 3).trim().takeIf { it.isNotEmpty() }
        } else {
            null to label.takeIf { it.isNotEmpty() }
        }
    }
}
