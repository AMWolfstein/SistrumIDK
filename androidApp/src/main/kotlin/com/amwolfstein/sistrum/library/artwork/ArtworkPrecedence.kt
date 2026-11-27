package com.amwolfstein.sistrum.library.artwork

/** One track that can supply artist artwork, with the tag release date and cached artwork key. */
data class TrackArtworkCandidate(
    val trackId: String,
    val releaseDate: String?,
    val artworkKey: String?,
)

data class ReleaseDateKey(val year: Int, val month: Int, val day: Int) : Comparable<ReleaseDateKey> {
    override fun compareTo(other: ReleaseDateKey): Int =
        compareValuesBy(this, other, ReleaseDateKey::year, ReleaseDateKey::month, ReleaseDateKey::day)
}

/**
 * Owner-selected artist artwork precedence:
 * imported Deezer image (durable) > latest released track artwork by tag release date
 * (undated last, deterministic tie) > placeholder.
 *
 * Deezer durability is enforced by the repository: once an artist is marked as a Deezer import,
 * this fallback never replaces it.
 */
object ArtworkPrecedence {
    /**
     * Parses `YYYY`, `YYYY-MM` and `YYYY-MM-DD` tag dates. Missing parts count as zero, so a year
     * outranks the same year without a date. Anything unparseable is treated as undated.
     */
    fun releaseDateKey(value: String?): ReleaseDateKey? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split('-', '/', '.')
        val year = parts.getOrNull(0)?.toIntOrNull() ?: return null
        if (year <= 0) return null
        val month = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 12) ?: 0
        val day = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 31) ?: 0
        return ReleaseDateKey(year, month, day)
    }

    /** Returns the artwork key of the winning track, or null when no candidate has usable art. */
    fun selectTrackFallback(candidates: List<TrackArtworkCandidate>): String? =
        candidates
            .filter { !it.artworkKey.isNullOrBlank() }
            .sortedWith(
                // Dated entries first, newest date first, then the stable track-id tie-break.
                compareBy<TrackArtworkCandidate> { releaseDateKey(it.releaseDate) == null }
                    .thenComparator { left, right ->
                        val leftKey = releaseDateKey(left.releaseDate)
                        val rightKey = releaseDateKey(right.releaseDate)
                        when {
                            leftKey == null && rightKey == null -> 0
                            leftKey == null -> 1
                            rightKey == null -> -1
                            else -> rightKey.compareTo(leftKey)
                        }
                    }
                    .thenBy { it.trackId },
            )
            .firstOrNull()
            ?.artworkKey
}
