package com.amwolfstein.sistrum.library.analysis

import com.amwolfstein.sistrum.library.db.LibraryDao
import com.amwolfstein.sistrum.library.db.TrackAnalysisEntity
import com.amwolfstein.sistrum.library.mood.MoodFormula

/**
 * Re-derives energy/danceability/brightness for the whole analyzed corpus, the
 * way Airmedy `d859940c` recomputes percentiles and backfills mood after an
 * analysis batch. Raw DSP fields are never touched here.
 */
object MoodDerivation {
    private fun rawOf(value: TrackAnalysisEntity): MoodFormula.Raw? = MoodFormula.Raw(
        rms = value.rms ?: return null,
        spectralCentroid = value.spectralCentroid ?: return null,
        spectralFlux = value.spectralFlux ?: return null,
        tempo = value.tempo ?: return null,
        crest = value.crest ?: return null,
        onsetVariance = value.onsetVariance ?: return null,
        loudnessRange = value.loudnessRange ?: return null,
    )

    /** Returns how many rows were re-derived. */
    suspend fun recompute(dao: LibraryDao): Int {
        val corpus = dao.completedAnalyses().mapNotNull { entity -> rawOf(entity)?.let { entity to it } }
        if (corpus.isEmpty()) return 0
        val percentiles = MoodFormula.percentiles(corpus.map { it.second })
        var updated = 0
        for ((entity, raw) in corpus) {
            val derived = MoodFormula.derive(raw, percentiles)
            if (entity.moodVersion == AnalysisVersion.MOOD &&
                entity.energy == derived.energy &&
                entity.danceability == derived.danceability &&
                entity.brightness == derived.brightness
            ) {
                continue
            }
            dao.upsertAnalysis(
                entity.copy(
                    energy = derived.energy,
                    danceability = derived.danceability,
                    brightness = derived.brightness,
                    moodVersion = AnalysisVersion.MOOD,
                ),
            )
            updated += 1
        }
        return updated
    }
}
