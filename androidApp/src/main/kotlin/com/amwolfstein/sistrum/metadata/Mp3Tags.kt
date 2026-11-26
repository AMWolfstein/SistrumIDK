package com.amwolfstein.sistrum.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** An update leaves unspecified frames byte-for-byte intact. */
internal data class Mp3TagUpdate(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val lyrics: String? = null,
    val artwork: ByteArray? = null,
    val artworkMime: String? = null,
)

/** Builds a complete replacement alongside the source. Caller owns the temporary file. */
internal fun buildMp3Replacement(source: File, target: File, update: Mp3TagUpdate) {
    require(source.extension.equals("mp3", ignoreCase = true))
    FileInputStream(source).use { input ->
        val header = ByteArray(10)
        java.io.DataInputStream(input).readFully(header)
        require(header.copyOfRange(0, 3).contentEquals("ID3".toByteArray()))
        val major = header[3].toInt() and 0xff
        require(major in 3..4 && header[4].toInt() == 0 && header[5].toInt() == 0) {
            "Unsupported ID3 version or flags"
        }
        require((6..9).all { header[it].toInt() and 0x80 == 0 })
        val size = (6..9).fold(0) { value, index -> value shl 7 or (header[index].toInt() and 0x7f) }
        require(size in 0..24 * 1024 * 1024)
        val body = ByteArray(size)
        java.io.DataInputStream(input).readFully(body)
        val replaced = buildSet {
            if (update.title != null) add("TIT2")
            if (update.artist != null) add("TPE1")
            if (update.album != null) add("TALB")
            if (update.lyrics != null) add("USLT")
            if (update.artwork != null) add("APIC")
        }
        val frames = ByteArrayOutputStream()
        val preserved = ByteArrayOutputStream()
        var position = 0
        while (position + 10 <= body.size && body[position] != 0.toByte()) {
            val id = body.copyOfRange(position, position + 4).toString(Charsets.ISO_8859_1)
            require(id.all { it in 'A'..'Z' || it in '0'..'9' })
            val length = if (major == 4) {
                require((position + 4..position + 7).all { body[it].toInt() and 0x80 == 0 })
                (position + 4..position + 7).fold(0) { value, index -> value shl 7 or (body[index].toInt() and 0x7f) }
            } else {
                (position + 4..position + 7).fold(0) { value, index -> value shl 8 or (body[index].toInt() and 0xff) }
            }
            require(length >= 0 && length <= body.size - position - 10)
            val payload = body.copyOfRange(position + 10, position + 10 + length)
            val remove = if (id !in replaced) false else when (id) {
                "APIC" -> {
                    require(body[position + 8] == 0.toByte() && body[position + 9] == 0.toByte())
                    require(payload.size >= 4 && payload[0].toInt() in 0..3)
                    val mimeEnd = (1 until payload.size).firstOrNull { payload[it] == 0.toByte() } ?: -1
                    require(mimeEnd >= 1 && mimeEnd + 1 < payload.size)
                    payload[mimeEnd + 1].toInt() == 3
                }
                "USLT" -> {
                    require(body[position + 8] == 0.toByte() && body[position + 9] == 0.toByte())
                    require(payload.size >= 5 && payload[0].toInt() in 0..3)
                    val emptyDescriptor = if (payload[0].toInt() in 1..2) {
                        payload.size >= 6 && payload[4] == 0.toByte() && payload[5] == 0.toByte()
                    } else payload[4] == 0.toByte()
                    payload.copyOfRange(1, 4).toString(Charsets.ISO_8859_1).equals("eng", true) && emptyDescriptor
                }
                else -> true
            }
            if (!remove) preserved.write(body, position, length + 10)
            position += length + 10
        }
        require(body.copyOfRange(position, body.size).all { it == 0.toByte() })
        fun add(id: String, payload: ByteArray) {
            frames.write(id.toByteArray(Charsets.ISO_8859_1))
            val length = payload.size
            if (major == 4) frames.write(byteArrayOf((length shr 21 and 127).toByte(), (length shr 14 and 127).toByte(), (length shr 7 and 127).toByte(), (length and 127).toByte()))
            else frames.write(byteArrayOf((length shr 24).toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte()))
            frames.write(byteArrayOf(0, 0))
            frames.write(payload)
        }
        fun encoded(value: String): ByteArray = if (major == 4)
            byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)
        else byteArrayOf(1) + value.toByteArray(Charsets.UTF_16)
        update.title?.let { add("TIT2", encoded(it)) }
        update.artist?.let { add("TPE1", encoded(it)) }
        update.album?.let { add("TALB", encoded(it)) }
        update.lyrics?.let { lyrics ->
            val payload = if (major == 4) byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + lyrics.toByteArray(Charsets.UTF_8)
                else byteArrayOf(1) + "eng".toByteArray() + byteArrayOf(0, 0) + lyrics.toByteArray(Charsets.UTF_16)
            add("USLT", payload)
        }
        update.artwork?.let { image ->
            val mime = requireNotNull(update.artworkMime)
            require(mime == "image/jpeg" || mime == "image/png")
            require(image.size in 1..8 * 1024 * 1024)
            add("APIC", byteArrayOf(0) + mime.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + image)
        }
        frames.write(preserved.toByteArray())
        val output = frames.toByteArray()
        require(output.size < 1 shl 28)
        val tagSize = output.size
        FileOutputStream(target).use { stream ->
            stream.write(byteArrayOf(73, 68, 51, major.toByte(), 0, 0,
                (tagSize shr 21 and 127).toByte(), (tagSize shr 14 and 127).toByte(),
                (tagSize shr 7 and 127).toByte(), (tagSize and 127).toByte()))
            stream.write(output)
            input.copyTo(stream)
            stream.fd.sync()
        }
    }
}
