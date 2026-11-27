package com.amwolfstein.sistrum.library.playlist

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LexoRankTest {
    @Test
    fun betweenProducesStrictlyOrderedRanks() {
        val first = LexoRank.between(null, null)
        val second = LexoRank.between(first, null)
        val before = LexoRank.between(null, first)
        val middle = LexoRank.between(first, second)
        assertTrue(before < first)
        assertTrue(first < middle)
        assertTrue(middle < second)
    }

    @Test
    fun repeatedInsertionsStayOrdered() {
        var previous: String? = null
        val ranks = mutableListOf<String>()
        repeat(200) {
            val rank = LexoRank.between(previous, null)
            ranks += rank
            previous = rank
        }
        assertEquals(ranks.sorted(), ranks)
        assertEquals(ranks.distinct().size, ranks.size)
    }

    @Test
    fun insertingAtTheFrontStaysFirst() {
        val first = LexoRank.between(null, null)
        val front = LexoRank.between(null, first)
        assertTrue(front < first)
        assertTrue(LexoRank.between(null, front) < front)
    }

    @Test
    fun adjacentRanksStillAcceptAValueBetween() {
        val left = "A"
        val right = "B"
        val middle = LexoRank.between(left, right)
        assertTrue("$left < $middle < $right", left < middle && middle < right)
    }
}

class M3u8CodecTest {
    @Test
    fun exportsUtf8HeaderAndRelativePaths() {
        val dir = File(System.getProperty("java.io.tmpdir"), "m3u8-test-${System.nanoTime()}")
        dir.mkdirs()
        val playlist = File(dir, "list.m3u8")
        val track = File(dir, "sub/track one.mp3")
        track.parentFile.mkdirs()
        track.writeText("x")

        val text = M3u8Codec.export(
            listOf(M3u8Entry(track.absolutePath, 245, "Roads", "Portishead")),
            playlist,
        )
        assertTrue(text.startsWith("#EXTM3U\n#EXTENC:UTF-8\n"))
        assertTrue(text.contains("#EXTINF:245,Portishead - Roads"))
        assertTrue(text.contains("sub/track one.mp3"))
        assertEquals(track.canonicalPath, M3u8Codec.resolve("sub/track one.mp3", playlist))
        dir.deleteRecursively()
    }

    @Test
    fun parsesBomExtencAndRelativeEntries() {
        val bom = "\uFEFF#EXTM3U\n#EXTENC:UTF-8\n#EXTINF:100,Artist - Title\nmusic/a.mp3\n#comment\ncontent://tree/document/track\n"
        val entries = M3u8Codec.parse(bom)
        assertEquals(2, entries.size)
        assertEquals("music/a.mp3", entries[0].path)
        assertEquals(100, entries[0].durationSeconds)
        assertEquals("Artist", entries[0].artist)
        assertEquals("Title", entries[0].title)
        assertEquals("content://tree/document/track", entries[1].path)
    }

    @Test
    fun decodesLegacyBomAndDeclaredCharset() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "#EXTM3U\n#EXTINF:1,x\n".toByteArray()
        assertTrue(M3u8Codec.decode(bytes).startsWith("#EXTM3U"))
        val latin1 = "#EXTM3U\n#EXTENC:ISO-8859-1\n#EXTINF:1,Café\n".toByteArray(Charsets.ISO_8859_1)
        assertTrue(M3u8Codec.decode(latin1).contains("Café"))
    }

    @Test
    fun absoluteAndUriEntriesPassThrough() {
        assertEquals("/music/a.mp3", M3u8Codec.resolve("/music/a.mp3", null))
        assertEquals("content://x/y", M3u8Codec.resolve("content://x/y", null))
        assertEquals("https://example.com/a.mp3", M3u8Codec.exportPath("https://example.com/a.mp3", null))
    }
}
