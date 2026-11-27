package com.amwolfstein.sistrum.library.metadata

import com.amwolfstein.sistrum.library.tags.EntityResolver
import com.amwolfstein.sistrum.library.tags.TagMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeparatorsTest {
    @Test
    fun defaultsMatchUpstream() {
        assertEquals(listOf(";", "\\", ","), Separators.DEFAULT_DELIMITERS)
        assertEquals(Separators.DEFAULT_DELIMITERS, SeparatorConfig().artist)
        assertEquals(Separators.DEFAULT_DELIMITERS, SeparatorConfig().albumArtist)
        assertEquals(Separators.DEFAULT_DELIMITERS, SeparatorConfig().genre)
        assertEquals(Separators.DEFAULT_DELIMITERS, SeparatorConfig().composer)
    }

    @Test
    fun delimitersAreLiteralNotEmptyPatterns() {
        assertEquals(listOf("AC", "DC"), Separators.split("AC/DC", listOf("/")))
        assertEquals(listOf("a", "b"), Separators.split("a|b", listOf("|")))
        // A dot is a literal separator, not a regex wildcard.
        assertEquals(listOf("AC", "DC"), Separators.split("AC.DC", listOf(".")))
        assertEquals(listOf("AC.DC"), Separators.split("AC.DC", listOf("+")))
    }

    @Test
    fun multipleDelimitersApplyInOrder() {
        assertEquals(
            listOf("A", "B", "C", "D"),
            Separators.split("A; B\\C,D", Separators.DEFAULT_DELIMITERS),
        )
    }

    @Test
    fun emptyListMeansDoNotSplit() {
        assertEquals(listOf("A; B"), Separators.split("A; B", emptyList()))
        // Multi-value tags still keep their frame boundary.
        assertEquals(listOf("A", "B; C"), Separators.splitAll(listOf("A", "B; C"), emptyList()))
    }

    @Test
    fun splitAllPreservesMultiValueBoundariesAndNeverDoubleSplits() {
        assertEquals(listOf("A", "B", "C", "D"), Separators.splitAll(listOf("A", "B; C", "D"), listOf(";")))
        // Values are already separate entities: applying the delimiters again is a no-op.
        val once = Separators.splitAll(listOf("A; B"), listOf(";"))
        assertEquals(listOf("A", "B"), once)
        assertEquals(once, Separators.splitAll(once, listOf(";")))
    }

    @Test
    fun blanksAreDroppedAndDedupeIsCaseInsensitive() {
        assertEquals(listOf("Air", "Portishead"), Separators.splitAll(listOf("Air", " air ", "Portishead"), listOf(";")))
        assertEquals(emptyList<String>(), Separators.splitAll(listOf("  ", ""), listOf(";")))
    }

    @Test
    fun validationMatchesUpstreamRules() {
        assertNull(Separators.validate(listOf(";", "\\", ",")))
        assertNull(Separators.validate(emptyList()))
        assertEquals(Separators.ValidationError.Empty, Separators.validate(listOf("  ")))
        assertEquals(
            Separators.ValidationError.TooLong("abcdef"),
            Separators.validate(listOf("abcdef")),
        )
        assertEquals(
            Separators.ValidationError.Duplicate(";"),
            Separators.validate(listOf(";", " ; ")),
        )
    }

    @Test
    fun validationCountsCodePointsNotUtf16Units() {
        // Three emoji are six UTF-16 units but three code points, so they fit the five-rune limit.
        assertNull(Separators.validate(listOf("🎵🎵🎵")))
        assertEquals(Separators.ValidationError.TooLong("🎵🎵🎵🎵🎵🎵"), Separators.validate(listOf("🎵🎵🎵🎵🎵🎵")))
    }

    @Test
    fun configUpdatesOnlyTheRequestedCategory() {
        val config = SeparatorConfig().with(SeparatorCategory.GENRE, listOf("/"))
        assertEquals(listOf("/"), config.genre)
        assertEquals(Separators.DEFAULT_DELIMITERS, config.artist)
        assertEquals(Separators.DEFAULT_DELIMITERS, config.albumArtist)
        assertEquals(Separators.DEFAULT_DELIMITERS, config.composer)
    }

    @Test
    fun entityResolverSplitsConfiguredFields() {
        val metadata = TagMetadata(
            title = "Track",
            artist = "A; B",
            album = "Album",
            albumArtist = "A; B",
            genre = "Rock; Pop",
            composer = "One, Two",
            year = 2020,
            durationMs = 1000,
            rawTags = mapOf("ARTIST" to listOf("A; B")),
        )
        val resolved = EntityResolver(SeparatorConfig()).resolve(metadata, "dir/track.mp3", "doc")
        assertEquals(listOf("A", "B"), resolved.artistNames)
        assertEquals(listOf("A", "B"), resolved.albumArtistNames)
        assertEquals(listOf("Rock", "Pop"), resolved.genreNames)
        assertEquals(listOf("One", "Two"), resolved.composerNames)
        assertEquals(2, resolved.artistIds.size)
        assertNotEquals(resolved.artistIds[0], resolved.artistIds[1])
    }

    @Test
    fun entityResolverKeepsWholeValueWhenCategoryHasNoDelimiters() {
        val metadata = TagMetadata(
            title = "Track",
            artist = "A, B",
            album = "Album",
            albumArtist = "A, B",
            genre = "Rock",
            composer = "C",
            year = 2020,
            durationMs = 1000,
            rawTags = mapOf("ARTIST" to listOf("A, B")),
        )
        val config = SeparatorConfig(
            artist = emptyList(),
            albumArtist = emptyList(),
            genre = emptyList(),
            composer = emptyList(),
        )
        val resolved = EntityResolver(config).resolve(metadata, "dir/track.mp3", "doc")
        assertEquals(listOf("A, B"), resolved.artistNames)
        assertEquals(listOf("Rock"), resolved.genreNames)
    }

    @Test
    fun entityResolverTreatsSeparateFramesAsSeparateArtistsEvenWithNoDelimiters() {
        val metadata = TagMetadata(
            title = "Track",
            artist = "A",
            album = "Album",
            albumArtist = "A",
            genre = "Rock",
            composer = "C",
            year = 2020,
            durationMs = 1000,
            rawTags = mapOf("ARTIST" to listOf("A", "B")),
        )
        val resolved = EntityResolver(SeparatorConfig(artist = emptyList())).resolve(metadata, "d/t.mp3", "doc")
        assertEquals(listOf("A", "B"), resolved.artistNames)
    }
}
