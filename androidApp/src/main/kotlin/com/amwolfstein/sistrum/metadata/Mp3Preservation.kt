package com.amwolfstein.sistrum.metadata

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

internal val safeMp3PropertyFrames = mapOf(
    "REPLAYGAIN_TRACK_GAIN" to "TXXX:REPLAYGAIN_TRACK_GAIN",
    "REPLAYGAIN_TRACK_PEAK" to "TXXX:REPLAYGAIN_TRACK_PEAK",
    "REPLAYGAIN_ALBUM_GAIN" to "TXXX:REPLAYGAIN_ALBUM_GAIN",
    "REPLAYGAIN_ALBUM_PEAK" to "TXXX:REPLAYGAIN_ALBUM_PEAK",
    "TITLE" to "TIT2", "ARTIST" to "TPE1", "ALBUM" to "TALB", "LYRICS" to "USLT",
    "ALBUMARTIST" to "TPE2", "GENRE" to "TCON", "TRACKNUMBER" to "TRCK",
    "DISCNUMBER" to "TPOS", "COMPOSER" to "TCOM", "LYRICIST" to "TEXT",
    "CONDUCTOR" to "TPE3", "PERFORMER" to "TPE4", "COPYRIGHT" to "TCOP",
    "PUBLISHER" to "TPUB", "ISRC" to "TSRC", "BPM" to "TBPM",
    "TITLESORT" to "TSOT", "ARTISTSORT" to "TSOP", "ALBUMSORT" to "TSOA",
)

/** Reject replacement unless untouched ID3 frames and the complete audio tail survive. */
internal fun verifyMp3Preservation(source: File, replacement: File, update: Mp3TagUpdate) {
    val splitUserFrames = update.additionalProperties.keys.any { it.startsWith("REPLAYGAIN_") }
    val original = inspectMp3(source, splitUserFrames)
    val written = inspectMp3(replacement, splitUserFrames)
    require((update.lyrics == null && "LYRICS" !in update.additionalProperties) || original.frames["USLT"].orEmpty().size <= 1) {
        "Multiple embedded lyric frames cannot be safely replaced"
    }
    val changedFrames = buildSet {
        if (update.title != null) add("TIT2")
        if (update.artist != null) add("TPE1")
        if (update.album != null) add("TALB")
        if (update.lyrics != null) add("USLT")
        if (update.artwork != null || update.pictures != null) add("APIC")
        update.additionalProperties.keys.forEach { key ->
            add(requireNotNull(safeMp3PropertyFrames[key]) { "This MP3 property cannot be safely edited" })
        }
    }
    check(original.audioHash.contentEquals(written.audioHash)) { "Audio payload changed" }
    check(original.frames.filterKeys { it !in changedFrames } == written.frames.filterKeys { it !in changedFrames }) {
        "Unedited ID3 frames changed"
    }
}

private data class Mp3Contents(val frames: Map<String, List<String>>, val audioHash: ByteArray)

private fun inspectMp3(file: File, splitUserFrames: Boolean): Mp3Contents = RandomAccessFile(file, "r").use { input ->
    val header = ByteArray(10)
    input.readFully(header)
    require(header.copyOfRange(0, 3).contentEquals("ID3".toByteArray()))
    val version = header[3].toInt() and 0xff
    require(version in 3..4 && header[4].toInt() == 0 && header[5].toInt() == 0)
    require((6..9).all { header[it].toInt() and 0x80 == 0 })
    val size = (6..9).fold(0) { value, index -> value shl 7 or (header[index].toInt() and 0x7f) }
    require(size <= 24 * 1024 * 1024 && input.length() >= size.toLong() + 10)
    val body = ByteArray(size)
    input.readFully(body)
    val frames = mutableMapOf<String, MutableList<String>>()
    var position = 0
    while (position + 10 <= body.size && body[position] != 0.toByte()) {
        val id = body.copyOfRange(position, position + 4).toString(Charsets.ISO_8859_1)
        require(id.all { it in 'A'..'Z' || it in '0'..'9' })
        val length = if (version == 4) {
            require((position + 4..position + 7).all { body[it].toInt() and 0x80 == 0 })
            (position + 4..position + 7).fold(0) { n, i -> n shl 7 or (body[i].toInt() and 0x7f) }
        } else {
            (position + 4..position + 7).fold(0) { n, i -> n shl 8 or (body[i].toInt() and 0xff) }
        }
        require(length >= 0 && length <= body.size - position - 10)
        val frameKey = if (id == "TXXX" && splitUserFrames) {
            require(body[position + 8] == 0.toByte() && body[position + 9] == 0.toByte())
            "TXXX:" + userTextDescription(body.copyOfRange(position + 10, position + 10 + length))
        } else id
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(body.copyOfRange(position, position + 10 + length))
            .joinToString("") { "%02x".format(it) }
        frames.getOrPut(frameKey) { mutableListOf() }.add(digest)
        position += length + 10
    }
    require(body.copyOfRange(position, body.size).all { it == 0.toByte() })
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    Mp3Contents(frames.mapValues { it.value.sorted() }, digest.digest())
}

/** Decode the TXXX description without interpreting its value or opaque frames. */
private fun userTextDescription(payload: ByteArray): String {
    require(payload.isNotEmpty())
    val encoding = payload[0].toInt() and 0xff
    val width = if (encoding == 1 || encoding == 2) 2 else 1
    require(encoding in 0..3)
    var end = 1
    while (end + width <= payload.size) {
        if (payload[end] == 0.toByte() && (width == 1 || payload[end + 1] == 0.toByte())) break
        end += width
    }
    require(end + width <= payload.size)
    val text = payload.copyOfRange(1, end)
    val charset = when (encoding) {
        0 -> Charsets.ISO_8859_1
        1 -> {
            require(text.size >= 2 && ((text[0] == 0xff.toByte() && text[1] == 0xfe.toByte()) ||
                (text[0] == 0xfe.toByte() && text[1] == 0xff.toByte())))
            Charsets.UTF_16
        }
        2 -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }
    return charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(text)).toString().uppercase(java.util.Locale.ROOT)
}
