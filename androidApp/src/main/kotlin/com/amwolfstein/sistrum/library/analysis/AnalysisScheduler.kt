package com.amwolfstein.sistrum.library.analysis

import com.amwolfstein.sistrum.library.db.LibraryDao
import kotlinx.coroutines.sync.Mutex

data class AnalysisRunResult(
    val processed: Int,
    val completed: Int,
    val failed: Int,
    val alreadyRunning: Boolean = false,
)

/**
 * Bounded, resumable analysis job runner.
 *
 * - one run at a time per process ([Mutex.tryLock]); a second request is reported, not queued
 * - rows are marked RUNNING before the decode and COMPLETED/FAILED/CANCELLED after it
 * - failures are retried up to [maxAttempts] and then left visible in Room
 * - [recoverInterrupted] turns RUNNING rows left by process death back into PENDING
 */
class AnalysisScheduler(
    private val engine: AnalysisEngine,
    private val dao: LibraryDao,
    private val maxAttempts: Int = 3,
) {
    private val mutex = Mutex()

    suspend fun recoverInterrupted(now: Long = System.currentTimeMillis()): Int =
        dao.resetInterruptedAnalyses(now)

    suspend fun run(limit: Int = 50): AnalysisRunResult {
        if (!mutex.tryLock()) return AnalysisRunResult(0, 0, 0, alreadyRunning = true)
        try {
            var completed = 0
            var failed = 0
            var processed = 0
            for (item in dao.itemsNeedingAnalysis(limit, AnalysisVersion.ANALYZER, maxAttempts)) {
                processed += 1
                if (engine.analyzeIfNeeded(item.id).isSuccess) completed += 1 else failed += 1
            }
            if (completed > 0) MoodDerivation.recompute(dao)
            return AnalysisRunResult(processed, completed, failed)
        } finally {
            mutex.unlock()
        }
    }
}
