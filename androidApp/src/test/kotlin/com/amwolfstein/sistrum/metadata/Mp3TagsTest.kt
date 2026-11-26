package com.amwolfstein.sistrum.metadata

import com.amwolfstein.sistrum.sync.EmbeddedTagReader
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.Assume.assumeTrue

class Mp3TagsTest {
    @Test fun writesTimedAndPlainLyricsWithoutChangingAudio() {
        listOf("[00:01.00]سلام\n[00:02.50]world", "Plain lyrics").forEach { lyrics ->
            val source = File.createTempFile("song-", ".mp3")
            val target = File.createTempFile("replacement-", ".mp3")
            try {
                val audio = byteArrayOf(0xff.toByte(), 0xfb.toByte(), 1, 2, 3, 4)
                source.writeBytes(byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 0) + audio)
                buildMp3Replacement(source, target, Mp3TagUpdate(lyrics = lyrics))
                assertEquals(lyrics, EmbeddedTagReader.embeddedLyricsText(target.path))
                assertArrayEquals(audio, target.readBytes().takeLast(audio.size).toByteArray())
            } finally {
                source.delete()
                target.delete()
            }
        }
    }

    @Test fun writesArtworkAndPreservesUnchangedFrames() {
        val source = File.createTempFile("song-", ".mp3")
        val target = File.createTempFile("replacement-", ".mp3")
        try {
            val originalFrame = byteArrayOf(84, 88, 88, 88, 0, 0, 0, 3, 0, 0, 0, 65, 66)
            source.writeBytes(byteArrayOf(73, 68, 51, 3, 0, 0, 0, 0, 0, originalFrame.size.toByte()) + originalFrame + byteArrayOf(-1, -5, 1))
            val artwork = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 1)
            buildMp3Replacement(source, target, Mp3TagUpdate(title = "عنوان", artwork = artwork, artworkMime = "image/png"))
            assertArrayEquals(artwork, EmbeddedTagReader.embeddedArtworkBytes(target.path))
            val result = target.readBytes()
            org.junit.Assert.assertTrue(result.toList().windowed(originalFrame.size).any { it == originalFrame.toList() })
            assertArrayEquals(byteArrayOf(-1, -5, 1), result.takeLast(3).toByteArray())
        } finally {
            source.delete()
            target.delete()
        }
    }

    @Test fun replacingFrontCoverRetainsBackCoverAndOtherLanguageLyrics() {
        fun frame(id: String, bytes: ByteArray): ByteArray = id.toByteArray() +
            byteArrayOf(0, 0, 0, bytes.size.toByte(), 0, 0) + bytes
        val back = frame("APIC", byteArrayOf(0) + "image/png".toByteArray() + byteArrayOf(0, 4, 0, 1, 2))
        val french = frame("USLT", byteArrayOf(3) + "fra".toByteArray() + byteArrayOf(0) + "Bonjour".toByteArray())
        val original = back + french
        val source = File.createTempFile("song-", ".mp3")
        val target = File.createTempFile("replacement-", ".mp3")
        try {
            source.writeBytes(byteArrayOf(73, 68, 51, 3, 0, 0, 0, 0, 0, original.size.toByte()) + original + byteArrayOf(-1, -5))
            val cover = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 3)
            buildMp3Replacement(source, target, Mp3TagUpdate(lyrics = "[00:01.00]Hello", artwork = cover, artworkMime = "image/png"))
            assertEquals("[00:01.00]Hello", EmbeddedTagReader.embeddedLyricsText(target.path))
            assertArrayEquals(cover, EmbeddedTagReader.embeddedArtworkBytes(target.path))
            val bytes = target.readBytes().toList()
            org.junit.Assert.assertTrue(bytes.windowed(back.size).any { it == back.toList() })
            org.junit.Assert.assertTrue(bytes.windowed(french.size).any { it == french.toList() })
        } finally {
            source.delete()
            target.delete()
        }
    }

    @Test fun utf16LyricsReadBackInV23() {
        val source = File.createTempFile("song-", ".mp3")
        val target = File.createTempFile("replacement-", ".mp3")
        try {
            source.writeBytes(byteArrayOf(73, 68, 51, 3, 0, 0, 0, 0, 0, 0, -1, -5))
            val lyric = "[00:02.50]كلمات"
            buildMp3Replacement(source, target, Mp3TagUpdate(lyrics = lyric))
            assertEquals(lyric, EmbeddedTagReader.embeddedLyricsText(target.path))
        } finally {
            source.delete()
            target.delete()
        }
    }

    @Test fun realMp3TagsAreReadableByFfprobeWhenAvailable() {
        val source = File("src/androidTest/assets/analysis/mono.mp3")
        assumeTrue(source.isFile)
        val available = runCatching { ProcessBuilder("ffprobe", "-version").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue(available)
        val target = File.createTempFile("metadata-", ".mp3")
        try {
            buildMp3Replacement(source, target, Mp3TagUpdate(title = "عنوان", artist = "Artist", album = "Album"))
            val process = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format_tags=title,artist,album",
                "-of", "default=noprint_wrappers=1", target.path).redirectErrorStream(true).start()
            val result = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor())
            org.junit.Assert.assertTrue(result.contains("TAG:title=عنوان"))
            org.junit.Assert.assertTrue(result.contains("TAG:artist=Artist"))
            org.junit.Assert.assertTrue(result.contains("TAG:album=Album"))
        } finally {
            target.delete()
        }
    }

    @Test fun rejectsUnsupportedTagsWithoutAlteringSource() {
        val source = File.createTempFile("song-", ".mp3")
        val target = File.createTempFile("replacement-", ".mp3")
        try {
            val original = byteArrayOf(73, 68, 51, 2, 0, 0, 0, 0, 0, 0)
            source.writeBytes(original)
            assertThrows(IllegalArgumentException::class.java) {
                buildMp3Replacement(source, target, Mp3TagUpdate(lyrics = "test"))
            }
            assertArrayEquals(original, source.readBytes())
        } finally {
            source.delete()
            target.delete()
        }
    }
}
