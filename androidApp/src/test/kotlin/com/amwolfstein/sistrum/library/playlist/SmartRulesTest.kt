package com.amwolfstein.sistrum.library.playlist

import com.amwolfstein.sistrum.sync.LibraryTrack
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRulesTest {
    private fun track(
        id: String,
        artists: String = "Portishead",
        genres: List<String> = listOf("Trip Hop"),
        year: Int = 1994,
        rating: Int? = null,
        favorite: Boolean = false,
        playCount: Int = 0,
        durationMs: Long = 300_000,
        bpm: Double? = null,
        addedAt: Long = 1_700_000_000_000,
    ) = LibraryTrack(
        id = id,
        title = id,
        sortTitle = id,
        artists = artists,
        album = "Dummy",
        playCount = playCount,
        createdAt = addedAt.toString(),
        rating = rating,
        metadataJson = buildString {
            append("{\"title\":\"").append(id).append("\",")
            append("\"year\":").append(year).append(',')
            append("\"duration\":").append(durationMs).append(',')
            append("\"is_favorite\":").append(favorite).append(',')
            append("\"genres\":").append(JsonArray(genres.map(::JsonPrimitive))).append(',')
            append("\"artists\":[{\"name\":\"").append(artists).append("\"}]")
            bpm?.let { append(",\"tempo\":").append(it) }
            append('}')
        },
    )

    private fun rule(config: SmartPlaylistConfig, track: LibraryTrack) =
        SmartRules.matches(config.root, track, Instant.ofEpochMilli(1_700_000_000_000))

    @Test
    fun allAndAnyCombineRulesAndNestedGroups() {
        val config = SmartPlaylistConfig(
            root = SmartRuleGroup(
                match = "all",
                rules = listOf(SmartRule("genre", "is", JsonPrimitive("Trip Hop"))),
                groups = listOf(
                    SmartRuleGroup(
                        match = "any",
                        rules = listOf(
                            SmartRule("year", "gte", JsonPrimitive(2000)),
                            SmartRule("rating", "gte", JsonPrimitive(4)),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(rule(config, track("a", rating = 5)))
        assertTrue(rule(config, track("b", year = 2001)))
        assertFalse(rule(config, track("c", year = 1990)))
        assertNull(SmartRules.validate(config))
    }

    @Test
    fun stringAndRelationOperators() {
        val artistIs = SmartPlaylistConfig(root = SmartRuleGroup(rules = listOf(SmartRule("artist", "is", JsonPrimitive("portishead")))))
        assertTrue(rule(artistIs, track("a")))

        val artistNot = SmartPlaylistConfig(root = SmartRuleGroup(rules = listOf(SmartRule("artist", "is_not", JsonPrimitive("Air")))))
        assertTrue(rule(artistNot, track("a")))

        val genreContains = SmartPlaylistConfig(root = SmartRuleGroup(rules = listOf(SmartRule("genre", "contains", JsonPrimitive("hop")))))
        assertTrue(rule(genreContains, track("a")))

        val genreIs = SmartPlaylistConfig(root = SmartRuleGroup(rules = listOf(SmartRule("genre", "is", JsonPrimitive("Rock")))))
        assertFalse(rule(genreIs, track("a")))
    }

    @Test
    fun numericOperatorsAndBetween() {
        val bpm = SmartPlaylistConfig(root = SmartRuleGroup(rules = listOf(
            SmartRule("bpm", "between", JsonArray(listOf(JsonPrimitive(80), JsonPrimitive(120)))),
        )))
        assertTrue(rule(bpm, track("a", bpm = 100.0)))
        assertFalse(rule(bpm, track("b", bpm = 140.0)))

        val year = SmartPlaylistConfig(root = SmartRuleGroup(rules = listOf(SmartRule("year", "lt", JsonPrimitive(2000)))))
        assertTrue(rule(year, track("a", year = 1994)))
        assertFalse(rule(year, track("b", year = 2020)))

        val missing = SmartPlaylistConfig(root = SmartRuleGroup(rules = listOf(SmartRule("bpm", "gt", JsonPrimitive(1)))))
        assertFalse(rule(missing, track("no-bpm")))
    }

    @Test
    fun favoriteAndAddedAtRules() {
        val favorite = SmartPlaylistConfig(root = SmartRuleGroup(rules = listOf(SmartRule("is_favorite", "is", JsonPrimitive(true)))))
        assertTrue(rule(favorite, track("a", favorite = true)))
        assertFalse(rule(favorite, track("b")))

        val recent = SmartPlaylistConfig(root = SmartRuleGroup(rules = listOf(SmartRule("added_at", "in_last_days", JsonPrimitive(30)))))
        assertTrue(rule(recent, track("a", addedAt = 1_700_000_000_000 - 5L * 86_400_000)))
        assertFalse(rule(recent, track("b", addedAt = 1_700_000_000_000 - 90L * 86_400_000)))
    }

    @Test
    fun invalidFieldsOperatorsAndCapsAreRejected() {
        assertNotNull(SmartRules.validate(SmartPlaylistConfig()))
        assertNotNull(
            SmartRules.validate(
                SmartPlaylistConfig(root = SmartRuleGroup(rules = listOf(SmartRule("album", "is", JsonPrimitive("x"))))),
            ),
        )
        assertNotNull(
            SmartRules.validate(
                SmartPlaylistConfig(root = SmartRuleGroup(rules = listOf(SmartRule("year", "is", JsonPrimitive("x"))))),
            ),
        )
        assertNotNull(
            SmartRules.validate(
                SmartPlaylistConfig(root = SmartRuleGroup(rules = listOf(SmartRule("year", "between", JsonPrimitive(1))))),
            ),
        )
        val seventeen = (1..17).map { SmartRule("year", "gt", JsonPrimitive(it)) }
        assertNotNull(SmartRules.validate(SmartPlaylistConfig(root = SmartRuleGroup(rules = seventeen))))
        assertNull(SmartRules.validate(SmartPlaylistConfig(root = SmartRuleGroup(rules = seventeen.take(16)))))
    }

    @Test
    fun limitCapsAndOrdersTheMatch() {
        val tracks = listOf(
            track("a", playCount = 5),
            track("b", playCount = 1),
            track("c", playCount = 9),
        )
        val config = SmartPlaylistConfig(
            root = SmartRuleGroup(rules = listOf(SmartRule("artist", "is", JsonPrimitive("Portishead")))),
            limit = SmartPlaylistLimit(enabled = true, count = 2, by = "most_played"),
        )
        assertEquals(listOf("c", "a"), SmartRules.evaluate(config, tracks).map { it.id })
        assertTrue(SmartRules.validate(config.copy(limit = SmartPlaylistLimit(true, 0, "title"))) != null)
        assertTrue(SmartRules.validate(config.copy(limit = SmartPlaylistLimit(true, 2, "sideways"))) != null)
    }

    @Test
    fun encodingRoundTrips() {
        val config = SmartPlaylistConfig(
            root = SmartRuleGroup(
                match = "any",
                rules = listOf(SmartRule("rating", "gte", JsonPrimitive(3))),
                groups = listOf(SmartRuleGroup(rules = listOf(SmartRule("genre", "is", JsonPrimitive("Jazz"))))),
            ),
            limit = SmartPlaylistLimit(true, 10, "random"),
            liveUpdating = false,
        )
        val decoded = SmartPlaylistJson.decode(SmartPlaylistJson.encode(config))
        assertEquals(config, decoded)
    }
}
