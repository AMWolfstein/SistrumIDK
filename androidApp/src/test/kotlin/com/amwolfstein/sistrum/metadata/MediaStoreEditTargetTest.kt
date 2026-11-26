package com.amwolfstein.sistrum.metadata

import org.junit.Assert.assertThrows
import org.junit.Test

class MediaStoreEditTargetTest {
    @Test fun rejectsReusedMediaIdAndMissingRow() {
        val expected = "/storage/emulated/0/Music/a.mp3"
        requireMediaPath(expected, expected)
        assertThrows(IllegalArgumentException::class.java) {
            requireMediaPath(expected, "/storage/emulated/0/Music/b.mp3")
        }
        assertThrows(IllegalArgumentException::class.java) { requireMediaPath(expected, null) }
    }
}
