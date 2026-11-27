package com.amwolfstein.sistrum.metadata

import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp3PreservationTest {
    private fun frame(id: String, value: String): ByteArray {
        val bytes = value.toByteArray()
        return id.toByteArray() + byteArrayOf(0, 0, 0, bytes.size.toByte(), 0, 0) + bytes
    }

    private fun file(title: String, custom: String, payload: ByteArray): ByteArray {
        val body = frame("TIT2", title) + frame("TXXX", custom)
        return byteArrayOf(73, 68, 51, 3, 0, 0, 0, 0, 0, body.size.toByte()) + body + payload
    }

    @Test
    fun replayGainMayChangeWithoutDiscardingOtherUserFrames() {
        fun userText(description: String, value: String): ByteArray =
            frame("TXXX", "\u0000$description\u0000$value")
        fun tagged(gain: String, comment: String): ByteArray {
            val body = userText("REPLAYGAIN_TRACK_GAIN", gain) + userText("KEEP_THIS", comment)
            require(body.size < 128)
            return byteArrayOf(73, 68, 51, 3, 0, 0, 0, 0, 0, body.size.toByte()) + body + byteArrayOf(4, 5)
        }
        val source = File.createTempFile("gain-original", ".mp3")
        val replacement = File.createTempFile("gain-replacement", ".mp3")
        try {
            source.writeBytes(tagged("-3 dB", "keep"))
            replacement.writeBytes(tagged("-2 dB", "keep"))
            val edit = Mp3TagUpdate(additionalProperties = mapOf("REPLAYGAIN_TRACK_GAIN" to listOf("-2 dB")))
            verifyMp3Preservation(source, replacement, edit)
            replacement.writeBytes(tagged("-2 dB", "changed"))
            assertThrows(IllegalStateException::class.java) { verifyMp3Preservation(source, replacement, edit) }
        } finally {
            assertTrue(source.delete())
            assertTrue(replacement.delete())
        }
    }

    @Test
    fun rejectsUnknownFrameOrAudioDamage() {
        val source = File.createTempFile("original", ".mp3")
        val replacement = File.createTempFile("replacement", ".mp3")
        try {
            source.writeBytes(file("old", "keep", byteArrayOf(1, 2, 3)))
            replacement.writeBytes(file("new", "keep", byteArrayOf(1, 2, 3)))
            verifyMp3Preservation(source, replacement, Mp3TagUpdate(title = "new"))
            replacement.writeBytes(file("new", "changed", byteArrayOf(1, 2, 3)))
            assertThrows(IllegalStateException::class.java) {
                verifyMp3Preservation(source, replacement, Mp3TagUpdate(title = "new"))
            }
            replacement.writeBytes(file("new", "keep", byteArrayOf(1, 2, 4)))
            assertThrows(IllegalStateException::class.java) {
                verifyMp3Preservation(source, replacement, Mp3TagUpdate(title = "new"))
            }
        } finally {
            source.delete()
            replacement.delete()
        }
    }
}
