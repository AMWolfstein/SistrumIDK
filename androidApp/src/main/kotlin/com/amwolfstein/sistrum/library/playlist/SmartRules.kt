package com.amwolfstein.sistrum.library.playlist

import com.amwolfstein.sistrum.sync.LibraryTrack
import com.amwolfstein.sistrum.sync.metadataObject
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class SmartRule(
    val field: String,
    val op: String,
    val value: kotlinx.serialization.json.JsonElement,
)

@Serializable
data class SmartRuleGroup(
    val match: String = "all",
    val rules: List<SmartRule> = emptyList(),
    val groups: List<SmartRuleGroup> = emptyList(),
)

@Serializable
data class SmartPlaylistLimit(
    val enabled: Boolean = false,
    val count: Int = 25,
    val by: String = "random",
)

@Serializable
data class SmartPlaylistConfig(
    val root: SmartRuleGroup = SmartRuleGroup(),
    val limit: SmartPlaylistLimit = SmartPlaylistLimit(),
    @SerialName("live_updating") val liveUpdating: Boolean = true,
)

/**
 * Port of the upstream smart-playlist rule engine.
 *
 * The field/operator allowlist is the injection gate: unknown names are rejected, never
 * interpolated. Rules are evaluated in Kotlin over the local catalog, with the same `all`/`any`
 * recursion and the same 16-leaf cap as the desktop implementation.
 */
object SmartRules {
    const val MAX_RULES = 16
    private val NUMBER_OPS = setOf("gt", "lt", "gte", "lte", "between")

    val fields: Map<String, Set<String>> = linkedMapOf(
        "genre" to setOf("is", "is_not", "contains"),
        "artist" to setOf("is", "is_not", "contains"),
        "year" to NUMBER_OPS,
        "bpm" to NUMBER_OPS,
        "play_count" to NUMBER_OPS,
        "duration" to NUMBER_OPS,
        "bitrate" to NUMBER_OPS,
        "energy" to NUMBER_OPS,
        "danceability" to NUMBER_OPS,
        "brightness" to NUMBER_OPS,
        "rating" to NUMBER_OPS,
        "is_favorite" to setOf("is"),
        "added_at" to setOf("in_last_days"),
    )

    val limitOrders = setOf("title", "random", "album", "artist", "genre", "most_played")

    private val numberFields = setOf(
        "year", "bpm", "play_count", "duration", "bitrate", "energy", "danceability",
        "brightness", "rating",
    )

    private val URL_RELATION_FIELDS = setOf("genre", "artist")

    fun validate(config: SmartPlaylistConfig): String? {
        val leaves = countLeaves(config.root)
        if (leaves == 0) return "at least one rule is required"
        if (leaves > MAX_RULES) return "too many rules: $leaves (max $MAX_RULES)"
        validateGroup(config.root)?.let { return it }
        if (config.limit.enabled) {
            if (config.limit.count <= 0) return "limit count must be positive when enabled"
            if (config.limit.by !in limitOrders) return "unknown limit-by value: ${config.limit.by}"
        }
        return null
    }

    fun countLeaves(group: SmartRuleGroup): Int =
        group.rules.size + group.groups.sumOf(::countLeaves)

    fun evaluate(
        config: SmartPlaylistConfig,
        tracks: List<LibraryTrack>,
        now: Instant = Instant.now(),
        excludeIds: Set<String> = emptySet(),
    ): List<LibraryTrack> {
        if (validate(config) != null) return emptyList()
        val matched = tracks.asSequence()
            .filter { it.id !in excludeIds }
            .filter { matches(config.root, it, now) }
            .toList()
        val ordered = sort(matched, config.limit.by)
        return if (config.limit.enabled && config.limit.count > 0) {
            ordered.take(config.limit.count)
        } else {
            ordered
        }
    }

    fun matches(group: SmartRuleGroup, track: LibraryTrack, now: Instant = Instant.now()): Boolean {
        if (group.match !in setOf("all", "any", "")) return false
        val ruleResults = group.rules.map { matchesRule(it, track, now) }
        val groupResults = group.groups.map { matches(it, track, now) }
        val all = ruleResults + groupResults
        if (all.isEmpty()) return true
        return if (group.match == "any") all.any { it } else all.all { it }
    }

    private fun matchesRule(rule: SmartRule, track: LibraryTrack, now: Instant): Boolean {
        val ops = fields[rule.field] ?: return false
        if (rule.op !in ops) return false
        val metadata = track.metadataObject()
        fun text() = rule.value.asString()
        fun number(value: kotlinx.serialization.json.JsonElement) = value.asDouble()

        if (rule.field in URL_RELATION_FIELDS) {
            val target = text() ?: return false
            val values = if (rule.field == "artist") {
                listOf(track.artists)
            } else {
                (metadata?.get("genres") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            }
            return when (rule.op) {
                "is" -> values.any { it.equals(target, ignoreCase = true) }
                "contains" -> values.any { it.contains(target, ignoreCase = true) }
                "is_not" -> values.none { it.equals(target, ignoreCase = true) }
                else -> false
            }
        }
        if (rule.field == "is_favorite") {
            return track.metadataObject()?.get("is_favorite")?.let { (it as? JsonPrimitive)?.contentOrNull == "true" }
                ?: false
        }
        if (rule.field == "added_at") {
            val days = number(rule.value) ?: return false
            val createdAt = track.createdAt.toLongOrNull() ?: return false
            return Instant.ofEpochMilli(createdAt) >= now.minus(days.toLong(), ChronoUnit.DAYS)
        }
        if (rule.field in numberFields) {
            val actual = when (rule.field) {
                "year" -> metadata?.get("year")?.asDouble()
                "bpm" -> metadata?.get("tempo")?.asDouble()
                "play_count" -> track.playCount.toDouble()
                // Duration rules are in seconds, like the upstream smart-rule column and M3U8.
                "duration" -> metadata?.get("duration")?.asDouble() ?: track.durationSeconds()
                "bitrate" -> metadata?.get("bitrate")?.asDouble()
                "energy" -> metadata?.get("energy")?.asDouble()
                "danceability" -> metadata?.get("danceability")?.asDouble()
                "brightness" -> metadata?.get("brightness")?.asDouble()
                "rating" -> track.rating?.toDouble()
                else -> null
            } ?: return false
            return when (rule.op) {
                "gt" -> actual > (number(rule.value) ?: return false)
                "lt" -> actual < (number(rule.value) ?: return false)
                "gte" -> actual >= (number(rule.value) ?: return false)
                "lte" -> actual <= (number(rule.value) ?: return false)
                "between" -> {
                    val range = rule.value as? JsonArray ?: return false
                    if (range.size != 2) return false
                    val low = range[0].asDouble() ?: return false
                    val high = range[1].asDouble() ?: return false
                    actual in low..high
                }
                else -> false
            }
        }
        return false
    }

    private fun sort(tracks: List<LibraryTrack>, by: String): List<LibraryTrack> = when (by) {
        "title", "" -> tracks.sortedBy { it.sortTitle.ifBlank { it.title } }
        "album" -> tracks.sortedWith(compareBy({ it.album }, { it.discNumber }, { it.trackNumber }))
        "artist" -> tracks.sortedBy { it.artists }
        "genre" -> tracks.sortedBy { track ->
            (track.metadataObject()?.get("genres") as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        }
        "most_played" -> tracks.sortedByDescending { it.playCount }
        "random" -> tracks.shuffled()
        else -> tracks
    }

    private fun validateGroup(group: SmartRuleGroup): String? {
        if (group.match !in setOf("all", "any", "")) return "invalid match type: ${group.match}"
        group.rules.forEach { rule ->
            val ops = fields[rule.field] ?: return "unknown smart playlist field: ${rule.field}"
            if (rule.op !in ops) return "operator ${rule.op} not allowed for field ${rule.field}"
            if (rule.field in numberFields && rule.op == "between") {
                val range = rule.value as? JsonArray
                if (range == null || range.size != 2 || range.any { it.asDouble() == null }) {
                    return "expected [min, max] value for between"
                }
            } else if (rule.field in numberFields && rule.value.asDouble() == null) {
                return "expected numeric value for ${rule.field}"
            }
        }
        group.groups.forEach { sub -> validateGroup(sub)?.let { return it } }
        return null
    }

    private fun kotlinx.serialization.json.JsonElement.asString(): String? =
        (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun kotlinx.serialization.json.JsonElement.asDouble(): Double? =
        (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private fun LibraryTrack.durationSeconds(): Double =
        (metadataObject()?.get("duration") as? JsonPrimitive)?.longOrNull?.toDouble() ?: 0.0
}

object SmartPlaylistJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(config: SmartPlaylistConfig): String = json.encodeToString(SmartPlaylistConfig.serializer(), config)

    fun decode(value: String?): SmartPlaylistConfig? =
        value?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString(SmartPlaylistConfig.serializer(), it) }.getOrNull()
        }
}
