package com.amwolfstein.sistrum.metadata

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Verify that opaque FLAC blocks and encoded audio survive metadata editing. */
internal fun verifyFlacPreservation(source: File, replacement: File, update: Mp3TagUpdate) {
    val original = inspectFlac(source, update)
    val written = inspectFlac(replacement, update)
    check(original.blocks == written.blocks) { "Unedited FLAC metadata changed" }
    check(original.comments == written.comments) { "Unedited FLAC comments changed" }
    check(original.audioHash.contentEquals(written.audioHash)) { "FLAC audio payload changed" }
}

private data class FlacContents(val blocks: Map<Int, List<String>>, val comments: List<String>, val audioHash: ByteArray)

private fun inspectFlac(file: File, update: Mp3TagUpdate): FlacContents = RandomAccessFile(file, "r").use { input ->
    val magic = ByteArray(4)
    input.readFully(magic)
    require(magic.contentEquals("fLaC".toByteArray()))
    val blocks = mutableMapOf<Int, MutableList<String>>()
    val comments = mutableListOf<String>()
    val changedKeys = buildSet {
        if (update.title != null) add("TITLE")
        if (update.artist != null) add("ARTIST")
        if (update.album != null) add("ALBUM")
        if (update.lyrics != null) add("LYRICS")
        addAll(update.additionalProperties.keys.map(String::uppercase))
    }
    var totalBytes = 0L
    var last = false
    while (!last) {
        val marker = input.readUnsignedByte()
        last = marker and 0x80 != 0
        val type = marker and 0x7f
        val size = (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        totalBytes += size
        require(totalBytes <= 64L * 1024 * 1024 && input.filePointer + size <= input.length())
        val body = ByteArray(size)
        input.readFully(body)
        if (type == 4) {
            val buffer = java.nio.ByteBuffer.wrap(body).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            require(buffer.remaining() >= 4)
            val vendorLength = buffer.int
            require(vendorLength >= 0 && vendorLength <= buffer.remaining())
            val vendor = ByteArray(vendorLength)
            buffer.get(vendor)
            comments.add("vendor:" + vendor.toList())
            require(buffer.remaining() >= 4)
            val count = buffer.int
            require(count in 0..(buffer.remaining() / 4))
            repeat(count) {
                val length = buffer.int
                require(length >= 0 && length <= buffer.remaining())
                val entry = ByteArray(length)
                buffer.get(entry)
                val key = entry.toString(Charsets.UTF_8).substringBefore('=').uppercase()
                if (key !in changedKeys) comments.add(entry.toList().toString())
            }
            require(!buffer.hasRemaining())
        } else if (type != 1 && !(type == 6 && (update.artwork != null || update.pictures != null))) {
            val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
            blocks.getOrPut(type) { mutableListOf() }.add(hash)
        }
    }
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        hash.update(buffer, 0, count)
    }
    FlacContents(blocks.mapValues { it.value.toList() }, comments.sorted(), hash.digest())
}
