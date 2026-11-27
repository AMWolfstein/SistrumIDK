package com.amwolfstein.sistrum.library.metadata

import java.util.Locale

/** The four fields Airmedy Desktop lets a user split into separate entities. */
enum class SeparatorCategory(val id: String) {
    ARTIST("artist"),
    ALBUM_ARTIST("album_artist"),
    GENRE("genre"),
    COMPOSER("composer"),
}

data class SeparatorConfig(
    val artist: List<String> = Separators.DEFAULT_DELIMITERS,
    val albumArtist: List<String> = Separators.DEFAULT_DELIMITERS,
    val genre: List<String> = Separators.DEFAULT_DELIMITERS,
    val composer: List<String> = Separators.DEFAULT_DELIMITERS,
) {
    fun delimitersFor(category: SeparatorCategory): List<String> = when (category) {
        SeparatorCategory.ARTIST -> artist
        SeparatorCategory.ALBUM_ARTIST -> albumArtist
        SeparatorCategory.GENRE -> genre
        SeparatorCategory.COMPOSER -> composer
    }

    fun with(category: SeparatorCategory, delimiters: List<String>): SeparatorConfig = when (category) {
        SeparatorCategory.ARTIST -> copy(artist = delimiters)
        SeparatorCategory.ALBUM_ARTIST -> copy(albumArtist = delimiters)
        SeparatorCategory.GENRE -> copy(genre = delimiters)
        SeparatorCategory.COMPOSER -> copy(composer = delimiters)
    }
}

/**
 * Port of the upstream delimiter rules:
 * - defaults are `;`, `\` and `,`
 * - each delimiter is a literal separator, never a regular expression
 * - an empty list means "do not split" (the whole tag value is one entity)
 * - entries are trimmed, must be non-empty, at most five code points, and unique
 * - split results are trimmed, empties dropped, and deduplicated case-insensitively
 *
 * Genuine multi-value tags arrive as separate raw values, so they always stay separate even when
 * the user removes every delimiter; a value is never split twice.
 */
object Separators {
    val DEFAULT_DELIMITERS: List<String> = listOf(";", "\\", ",")

    /** Maximum delimiter length in code points (upstream `maxDelimiterLen`). */
    const val MAX_LENGTH = 5

    sealed class ValidationError {
        data object Empty : ValidationError()
        data class TooLong(val value: String) : ValidationError()
        data class Duplicate(val value: String) : ValidationError()
    }

    fun validate(delimiters: List<String>): ValidationError? {
        val seen = mutableSetOf<String>()
        delimiters.forEach { raw ->
            val value = raw.trim()
            if (value.isEmpty()) return ValidationError.Empty
            if (value.codePointCount(0, value.length) > MAX_LENGTH) return ValidationError.TooLong(value)
            if (!seen.add(value)) return ValidationError.Duplicate(value)
        }
        return null
    }

    /** Splits one raw tag value. Empty or unusable delimiters leave it whole. */
    fun split(value: String, delimiters: List<String>): List<String> {
        if (value.isBlank()) return emptyList()
        var parts = listOf(value)
        delimiters.forEach { raw ->
            val delimiter = raw.trim()
            if (delimiter.isEmpty()) return@forEach
            parts = parts.flatMap { it.split(delimiter) }
        }
        return dedupe(parts)
    }

    /**
     * Splits every raw value, preserving multi-value boundaries: a value that has no delimiter is
     * still its own entity, so two ARTIST frames never merge.
     */
    fun splitAll(values: List<String>, delimiters: List<String>): List<String> =
        dedupe(values.flatMap { split(it, delimiters) })

    fun splitFor(config: SeparatorConfig, category: SeparatorCategory, values: List<String>): List<String> =
        if (config.delimitersFor(category).isEmpty()) {
            dedupe(values)
        } else {
            splitAll(values, config.delimitersFor(category))
        }

    private fun dedupe(values: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        values.forEach { raw ->
            val value = raw.trim()
            if (value.isEmpty()) return@forEach
            if (seen.add(value.lowercase(Locale.ROOT))) result += value
        }
        return result
    }
}
