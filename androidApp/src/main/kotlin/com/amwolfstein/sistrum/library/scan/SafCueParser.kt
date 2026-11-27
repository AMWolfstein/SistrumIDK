package com.amwolfstein.sistrum.library.scan

/** A CUE sheet track, with timing resolved against the previous INDEX 01 of the same file. */
data class CueTrack(
    val number: Int,
    val title: String?,
    val performer: String?,
    val fileIndex: Int,
    val startMs: Long,
    val endMs: Long?,
    val isAudio: Boolean,
)

data class CueFile(val name: String, val type: String?)

data class CueSheet(val files: List<CueFile>, val tracks: List<CueTrack>)

/**
 * Parses a text CUE sheet. Supports multiple FILE blocks, nested TRACK blocks, TITLE/PERFORMER
 * inheritance, REM lines and the mm:ss:ff frame clock (75 frames per second). INDEX 00 pregaps are
 * ignored for track start; INDEX 01 is the audible start. The end of a track is the next track's
 * start in the same file, or null for the last one (play to EOF).
 */
object SafCueParser {
    fun parse(text: String): CueSheet {
        val files = mutableListOf<CueFile>()
        val rawTracks = mutableListOf<RawTrack>()

        var sheetTitle: String? = null
        var sheetPerformer: String? = null
        var fileIndex = -1
        var fileType: String? = null
        var track: RawTrack? = null
        var inTrack = false

        fun flushTrack() {
            track?.let { rawTracks += it }
            track = null
        }

        text.removePrefix("\uFEFF").lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val keyword = line.substringBefore(' ', "").uppercase()
            val rest = line.substringAfter(' ', "").trim()
            when (keyword) {
                "FILE" -> {
                    flushTrack()
                    inTrack = false
                    val name = readQuoted(rest)
                    fileType = rest.substringAfterLast(' ').trim().ifBlank { null }
                    if (name.isNotBlank()) {
                        files += CueFile(name, fileType)
                        fileIndex = files.lastIndex
                    }
                }
                "TRACK" -> {
                    flushTrack()
                    val number = rest.substringBefore(' ').trim().toIntOrNull()
                    if (number != null) {
                        val mode = rest.substringAfter(' ', "").trim().uppercase()
                        track = RawTrack(
                            number = number,
                            fileIndex = fileIndex.coerceAtLeast(0),
                            isAudio = mode.isEmpty() || mode == "AUDIO",
                            title = null,
                            performer = null,
                            startMs = null,
                        )
                        inTrack = true
                    }
                }
                "TITLE" -> {
                    val value = readQuoted(rest).ifBlank { null }
                    if (inTrack) track = track?.copy(title = value) else sheetTitle = value
                }
                "PERFORMER" -> {
                    val value = readQuoted(rest).ifBlank { null }
                    if (inTrack) track = track?.copy(performer = value) else sheetPerformer = value
                }
                "INDEX" -> {
                    val indexNumber = rest.substringBefore(' ').trim().toIntOrNull()
                    val time = rest.substringAfter(' ', "").trim()
                    if (indexNumber == 1) {
                        track = track?.copy(startMs = parseTime(time))
                    }
                }
                else -> Unit
            }
        }
        flushTrack()

        val ordered = rawTracks.filter { it.startMs != null }.sortedWith(
            compareBy<RawTrack>({ it.fileIndex }, { it.startMs }),
        )
        val withEnds = ordered.mapIndexed { index, current ->
            val next = ordered.getOrNull(index + 1)
            val end = next?.takeIf { it.fileIndex == current.fileIndex }?.startMs
            CueTrack(
                number = current.number,
                title = current.title ?: sheetTitle,
                performer = current.performer ?: sheetPerformer,
                fileIndex = current.fileIndex,
                startMs = current.startMs ?: 0L,
                endMs = end,
                isAudio = current.isAudio,
            )
        }
        return CueSheet(files = files, tracks = withEnds)
    }

    private fun parseTime(value: String): Long? {
        val parts = value.split(':')
        if (parts.size < 2) return null
        val minutes = parts[0].toLongOrNull() ?: return null
        val seconds = parts[1].toLongOrNull() ?: return null
        val frames = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        return minutes * 60_000L + seconds * 1_000L + frames * 1_000L / 75L
    }

    private fun readQuoted(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith("\"")) {
            val end = trimmed.indexOf('"', startIndex = 1)
            if (end > 0) trimmed.substring(1, end) else trimmed.trim('"')
        } else {
            trimmed
        }
    }

    private data class RawTrack(
        val number: Int,
        val fileIndex: Int,
        val isAudio: Boolean,
        val title: String?,
        val performer: String?,
        val startMs: Long?,
    )
}
