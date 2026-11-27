package com.amwolfstein.sistrum.library.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafSourceRepositoryTest {
    @Test
    fun sourceIdIsStableAndDistinguishesUris() {
        val a = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        val b = "content://com.android.externalstorage.documents/tree/1234-5678%3AMusic"
        assertEquals(SafSourceRepository.sourceId(a), SafSourceRepository.sourceId(a))
        assertNotEquals(SafSourceRepository.sourceId(a), SafSourceRepository.sourceId(b))
        assertTrue(SafSourceRepository.sourceId(a).matches(Regex("source_[0-9a-f]{16}")))
    }

    @Test
    fun volumeClassificationKeepsInternalVolumesNonRemovable() {
        assertEquals("primary", SafSourceRepository.volumeIdOf("primary:Music"))
        assertEquals("1234-5678", SafSourceRepository.volumeIdOf("1234-5678:Music"))
        assertEquals(null, SafSourceRepository.volumeIdOf("Music"))
        listOf("primary", "home", "raw", "downloads").forEach {
            org.junit.Assert.assertFalse(it, SafSourceRepository.isRemovableVolume(it))
        }
        org.junit.Assert.assertTrue(SafSourceRepository.isRemovableVolume("1234-5678"))
        org.junit.Assert.assertFalse(SafSourceRepository.isRemovableVolume(null))
    }
}
