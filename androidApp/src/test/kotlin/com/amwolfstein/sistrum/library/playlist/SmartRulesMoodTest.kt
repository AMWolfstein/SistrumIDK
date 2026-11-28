package com.amwolfstein.sistrum.library.playlist

import com.amwolfstein.sistrum.sync.LibraryTrack
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mood, tempo, duration and favorite rules must read the values the local analyzer
 * persists (Room -> metadata JSON), with duration in seconds as upstream defines it.
 */
class SmartRulesMoodTest {
    private fun track(
        id: String,
        tempo: Double? = null,
        energy: Double? = null,
        danceability: Double? = null,
        brightness: Double? = null,
        durationSeconds: Long = 180,
        favorite: Boolean = false,
    ) = LibraryTrack(
        id = id,
        title = id,
        artists = "Artist",
        metadataJson = buildJsonObject {
            put("duration", durationSeconds)
            put("is_favorite", favorite)
            tempo?.let { put("tempo", it) }
            energy?.let { put("energy", it) }
            danceability?.let { put("danceability", it) }
            brightness?.let { put("brightness", it) }
        }.toString(),
    )

    private fun rule(field: String, op: String, value: kotlinx.serialization.json.JsonElement) =
        SmartPlaylistConfig(root = SmartRuleGroup(rules = listOf(SmartRule(field, op, value))))

    @Test
    fun moodAndTempoFieldsMatchTheLocalAnalysis() {
        val energetic = track("a", tempo = 128.0, energy = 0.8, danceability = 0.7, brightness = 0.6)
        val calm = track("b", tempo = 70.0, energy = 0.2, danceability = 0.3, brightness = 0.2)

        assertEquals(listOf(energetic), SmartRules.evaluate(rule("energy", "gt", JsonPrimitive(0.5)), listOf(energetic, calm)))
        assertEquals(listOf(energetic), SmartRules.evaluate(rule("danceability", "gte", JsonPrimitive(0.6)), listOf(energetic, calm)))
        assertEquals(listOf(calm), SmartRules.evaluate(rule("brightness", "lt", JsonPrimitive(0.3)), listOf(energetic, calm)))
        assertEquals(listOf(energetic), SmartRules.evaluate(rule("bpm", "between", JsonPrimitive(120).let { kotlinx.serialization.json.JsonArray(listOf(it, JsonPrimitive(140))) }), listOf(energetic, calm)))
    }

    @Test
    fun durationRulesUseSecondsNotMilliseconds() {
        val threeMinutes = track("long", durationSeconds = 180)
        // 180 seconds matches "longer than 120 s"; the old millisecond reading would reject it.
        assertEquals(listOf(threeMinutes), SmartRules.evaluate(rule("duration", "gt", JsonPrimitive(120)), listOf(threeMinutes)))
        assertTrue(SmartRules.evaluate(rule("duration", "lt", JsonPrimitive(5)), listOf(threeMinutes)).isEmpty())
    }

    @Test
    fun favoriteRulesReadTheLocalFavoriteFlag() {
        val favorite = track("f", favorite = true)
        val other = track("o", favorite = false)
        assertEquals(listOf(favorite), SmartRules.evaluate(rule("is_favorite", "is", JsonPrimitive(true)), listOf(favorite, other)))
    }

    @Test
    fun tracksWithoutAnalysisDoNotMatchMoodRules() {
        val unanalyzed = track("u")
        assertTrue(SmartRules.evaluate(rule("energy", "gt", JsonPrimitive(0.0)), listOf(unanalyzed)).isEmpty())
    }
}
