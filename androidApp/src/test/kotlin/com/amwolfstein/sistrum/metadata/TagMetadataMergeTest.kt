package com.amwolfstein.sistrum.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TagMetadataMergeTest {
    @Test
    fun keepsUntouchedValuesAndDistinguishesDeletionFromEmpty() {
        val original = mapOf("ARTIST" to listOf("أحمد", "Artist 😀"), "CUSTOM" to listOf("first", "second"))
        val result = mergeTagProperties(original, mapOf("TITLE" to listOf(""), "CUSTOM" to null))
        assertEquals(mapOf("ARTIST" to listOf("أحمد", "Artist 😀"), "TITLE" to listOf("")), result)
        assertEquals(listOf("first", "second"), original["CUSTOM"])
    }

    @Test
    fun rejectsEmbeddedNulBeforePassingTextToNativeCode() {
        assertThrows(IllegalArgumentException::class.java) {
            mergeTagProperties(emptyMap(), mapOf("TITLE" to listOf("unsafe\u0000value")))
        }
    }
}
