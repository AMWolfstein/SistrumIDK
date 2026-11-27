package com.amwolfstein.sistrum.library.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkKeysTest {
    @Test
    fun contentKeyMatchesSha256OfBytes() {
        // FIPS 180-2 test vector for "abc".
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ArtworkKeys.contentKey("abc".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun detectsFormatsFromMagicBytesNotNames() {
        assertEquals("jpg", ArtworkKeys.extensionFor(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals(
            "png",
            ArtworkKeys.extensionFor(
                byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()),
            ),
        )
        assertEquals(
            "webp",
            ArtworkKeys.extensionFor("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.US_ASCII)),
        )
        assertEquals(
            "gif",
            ArtworkKeys.extensionFor("GIF89a".toByteArray(Charsets.US_ASCII)),
        )
        assertNull(ArtworkKeys.extensionFor(ByteArray(0)))
        assertNull(ArtworkKeys.extensionFor("<html>not an image".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun variantsUseContentKeyAndShardedDirectories() {
        val key = "abcdef0123456789"
        assertEquals("ab/abcdef0123456789.png", ArtworkKeys.relativePath(key, ArtworkKeys.VARIANT_ORIGINAL, "png"))
        assertEquals("ab/abcdef0123456789_md.jpg", ArtworkKeys.relativePath(key, ArtworkKeys.VARIANT_MEDIUM, "png"))
        assertEquals("ab/abcdef0123456789_sm.jpg", ArtworkKeys.relativePath(key, ArtworkKeys.VARIANT_SMALL, "png"))
    }

    @Test
    fun scalingNeverUpscalesAndKeepsAspectRatio() {
        assertEquals(500 to 250, ArtworkKeys.scaledSize(2000, 1000, 500))
        assertEquals(250 to 500, ArtworkKeys.scaledSize(1000, 2000, 500))
        assertEquals(40 to 30, ArtworkKeys.scaledSize(40, 30, 500))
        assertEquals(1 to 1, ArtworkKeys.scaledSize(0, 0, 500))
    }
}
