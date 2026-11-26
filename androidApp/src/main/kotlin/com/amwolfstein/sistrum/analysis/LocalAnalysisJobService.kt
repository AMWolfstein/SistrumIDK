package com.amwolfstein.sistrum.analysis

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.amwolfstein.sistrum.sync.LocalLibraryRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val LocalAnalysisJobId = 8715

internal fun scheduleLocalAnalysis(context: Context, enabled: Boolean) {
    val scheduler = context.getSystemService(JobScheduler::class.java)
    if (!enabled) {
        scheduler.cancel(LocalAnalysisJobId)
        return
    }
    val job = JobInfo.Builder(LocalAnalysisJobId, ComponentName(context, LocalAnalysisJobService::class.java))
        .setPeriodic(15 * 60_000L)
        .setPersisted(true)
        .build()
    check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "Unable to schedule library analysis" }
}

class LocalAnalysisJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null
    private var active = false
    private var generation = 0L

    override fun onStartJob(params: JobParameters): Boolean {
        LocalLibraryRuntime.initialize(applicationContext)
        val controller = LocalLibraryRuntime.analysisController
        val currentGeneration = ++generation
        controller.setScheduled(true)
        active = true
        running = scope.launch {
            try {
                controller.awaitIdle()
                jobFinished(params, false)
            } finally {
                if (generation == currentGeneration) {
                    controller.setScheduled(false)
                    active = false
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        generation++
        if (active) LocalLibraryRuntime.analysisController.setScheduled(false)
        active = false
        running?.cancel()
        running = null
        return false
    }

    override fun onDestroy() {
        generation++
        if (active) LocalLibraryRuntime.analysisController.setScheduled(false)
        active = false
        scope.cancel()
        super.onDestroy()
    }
}
