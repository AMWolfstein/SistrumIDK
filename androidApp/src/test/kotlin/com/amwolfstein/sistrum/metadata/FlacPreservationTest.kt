package com.amwolfstein.sistrum.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Test

class FlacPreservationTest {
    private fun flac(title: String, unknown: String, audio: ByteArray): ByteArray {
        val comments = ByteArrayOutputStream()
        fun length(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        val vendor = "vendor".toByteArray()
        comments.write(length(vendor.size))
        comments.write(vendor)
        comments.write(length(2))
        listOf("TITLE=$title", "CUSTOM=$unknown").forEach {
            val bytes = it.toByteArray()
            comments.write(length(bytes.size))
            comments.write(bytes)
        }
        val body = comments.toByteArray()
        val streamInfo = ByteArray(34)
        return "fLaC".toByteArray() + byteArrayOf(0, 0, 0, 34) + streamInfo +
            byteArrayOf(0x84.toByte(), 0, 0, body.size.toByte()) + body + audio
    }

    @Test
    fun permitsOnlyRequestedCommentAndRejectsOpaqueOrAudioChanges() {
        val source = File.createTempFile("source", ".flac")
        val replacement = File.createTempFile("replacement", ".flac")
        try {
            source.writeBytes(flac("Old", "keep", byteArrayOf(1, 2, 3)))
            replacement.writeBytes(flac("New", "keep", byteArrayOf(1, 2, 3)))
            verifyFlacPreservation(source, replacement, Mp3TagUpdate(title = "New"))
            replacement.writeBytes(flac("New", "changed", byteArrayOf(1, 2, 3)))
            assertThrows(IllegalStateException::class.java) {
                verifyFlacPreservation(source, replacement, Mp3TagUpdate(title = "New"))
            }
            replacement.writeBytes(flac("New", "keep", byteArrayOf(1, 2, 4)))
            assertThrows(IllegalStateException::class.java) {
                verifyFlacPreservation(source, replacement, Mp3TagUpdate(title = "New"))
            }
        } finally {
            source.delete()
            replacement.delete()
        }
    }
}
