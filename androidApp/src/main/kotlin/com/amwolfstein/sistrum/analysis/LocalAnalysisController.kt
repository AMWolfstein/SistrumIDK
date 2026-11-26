package com.amwolfstein.sistrum.analysis

import android.os.Process
import com.amwolfstein.sistrum.sync.AndroidLibrarySyncStore
import com.amwolfstein.sistrum.sync.identityHash
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Runs only for the opt-in setting. Each worker owns its descriptor, decoder
 * and meter. All results are committed only while the scanned revision is active.
 */
internal class LocalAnalysisController(
    private val store: AndroidLibrarySyncStore,
    private val preferences: LocalAnalysisPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val foreground = MutableStateFlow(false)
    private val scheduled = MutableStateFlow(false)
    private val dispatcher = BoundedAnalysisDispatcher()

    fun start() {
        scope.launch {
            combine(preferences.settings, foreground, scheduled) { settings, isForeground, isScheduled ->
                settings to (isForeground || isScheduled)
            }.collectLatest { (settings, allowed) ->
                if (!settings.enabled || !allowed) return@collectLatest
                var cursor = -1
                while (isActive) {
                    var candidates = store.pendingLocalAnalyses(cursor, settings.workers).first()
                    if (candidates.isEmpty() && cursor >= 0) {
                        cursor = -1
                        candidates = store.pendingLocalAnalyses(cursor, settings.workers).first()
                    }
                    if (candidates.isEmpty()) {
                        candidates = store.pendingLocalAnalyses(-1, settings.workers).first { it.isNotEmpty() }
                    }
                    coroutineScope {
                        candidates.map { candidate -> async { process(candidate) } }.awaitAll()
                    }
                    cursor = candidates.maxOf { it.syncOrder }
                }
            }
        }
    }

    fun setForeground(active: Boolean) {
        foreground.value = active
    }

    fun setScheduled(active: Boolean) {
        scheduled.value = active
    }

    suspend fun awaitIdle() {
        combine(preferences.settings, store.pendingLocalAnalyses(-1, 1)) { settings, candidates ->
            !settings.enabled || candidates.isEmpty()
        }.first { it }
    }

    private suspend fun process(candidate: LocalAnalysisCandidate) {
        val file = File(candidate.path)
        val initialRevision = localAnalysisFileRevision(file)
        if (initialRevision != candidate.sourceRevision) {
            saveFailure(candidate, "File changed since the last scan")
            return
        }
        try {
            val output = analyze(file)
            if (localAnalysisFileRevision(file) != initialRevision) {
                saveFailure(candidate, "File changed during analysis")
                return
            }
            store.saveLocalAnalysis(
                LocalTrackAnalysisEntity(
                    trackId = candidate.trackId,
                    sourceRevision = candidate.sourceRevision,
                    analyzerVersion = LocalAnalyzerVersion,
                    status = "success",
                    rawJson = output,
                    failure = "",
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            saveFailure(candidate, error.message.orEmpty().take(256))
        }
    }

    private suspend fun saveFailure(candidate: LocalAnalysisCandidate, message: String) {
        store.saveLocalAnalysis(
            LocalTrackAnalysisEntity(
                trackId = candidate.trackId,
                sourceRevision = candidate.sourceRevision,
                analyzerVersion = LocalAnalyzerVersion,
                status = "failed",
                rawJson = "{}",
                failure = message,
            ),
        )
    }

    private suspend fun analyze(file: File): String = dispatcher.run(
        create = ::OfflineAudioDecoder,
        cancel = OfflineAudioDecoder::cancel,
        close = OfflineAudioDecoder::close,
        execute = { decoder ->
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            decoder.open(file)
            val extractor = SignalFeatureExtractor()
            val buffer = FloatArray(8192)
            while (true) {
                val count = decoder.read(buffer)
                if (count == 0) break
                check(count > 0) { "Audio decoding failed" }
                extractor.add(buffer, count)
            }
            val measured = decoder.metrics()
            check(measured.size == 3 && measured.all(Double::isFinite)) {
                "No measurable loudness in audio"
            }
            val features = extractor.finish(measured[2])
            buildJsonObject {
                put("loudness_lufs", measured[0])
                put("true_peak", measured[1])
                put("loudness_range", measured[2])
                if (features != null) {
                    put("rms", features.rmsDb)
                    put("crest", features.crest)
                    put("spectral_centroid", features.spectralCentroid)
                    put("spectral_flux", features.spectralFlux)
                    put("tempo", features.tempo)
                    put("onset_variance", features.onsetVariance)
                }
            }.toString()
        },
    )

}

internal fun localAnalysisFileRevision(file: File): String? {
    if (!file.isFile) return null
    val modified = file.lastModified()
    if (modified <= 0) return null
    return identityHash("${file.absolutePath}|${file.length()}|${modified / 1000}")
}
