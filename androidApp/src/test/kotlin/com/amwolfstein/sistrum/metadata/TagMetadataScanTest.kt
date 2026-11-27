package com.amwolfstein.sistrum.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class TagMetadataScanTest {
    @Test
    fun extractsEmbeddedFieldsWithoutArtwork() {
        val tags = embeddedTagsFromProperties(mapOf(
            "DATE" to listOf("2024-06-01"), "BPM" to listOf("120"),
            "LABEL" to listOf("Label"), "ISRC" to listOf("US1234567890"),
            "ITUNESADVISORY" to listOf("1"),
        ))
        assertEquals("2024-06-01", tags.releaseDate)
        assertEquals(2024, tags.year)
        assertEquals(120, tags.bpm)
        assertEquals("Label", tags.label)
        assertEquals("US1234567890", tags.isrc)
        assertEquals(true, tags.explicit)
    }
}
