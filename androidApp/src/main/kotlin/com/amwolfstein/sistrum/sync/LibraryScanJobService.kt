package com.amwolfstein.sistrum.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.amwolfstein.sistrum.ui.screens.launchScan
import com.amwolfstein.sistrum.ui.screens.readMediaPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

private const val LibraryScanJobId = 8714

internal class ScanJobCompletion(private val finish: () -> Unit) {
    private val settled = java.util.concurrent.atomic.AtomicBoolean(false)

    fun platformStopped() {
        settled.compareAndSet(false, true)
    }

    fun completed() {
        if (settled.compareAndSet(false, true)) finish()
    }
}

internal fun scheduleLibraryScan(context: Context, schedule: ScanSchedule) {
    val scheduler = context.getSystemService(JobScheduler::class.java)
    val interval = schedule.intervalMillis
    if (interval == null) {
        scheduler.cancel(LibraryScanJobId)
        return
    }
    val job = JobInfo.Builder(LibraryScanJobId, ComponentName(context, LibraryScanJobService::class.java))
        .setPeriodic(interval)
        .setPersisted(true)
        .build()
    check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "Unable to schedule library scan" }
}

/** JobScheduler runs periodic scans on a best-effort basis under Android's background limits. */
class LibraryScanJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null
    private var completion: ScanJobCompletion? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (checkSelfPermission(readMediaPermission()) != PackageManager.PERMISSION_GRANTED) return false
        LocalLibraryRuntime.initialize(applicationContext)
        val currentCompletion = ScanJobCompletion { jobFinished(params, false) }
        completion = currentCompletion
        running = launchScan(scope, applicationContext) { }
        running?.invokeOnCompletion { currentCompletion.completed() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        completion?.platformStopped()
        completion = null
        running?.cancel()
        running = null
        return false
    }

    override fun onDestroy() {
        completion?.platformStopped()
        completion = null
        scope.cancel()
        super.onDestroy()
    }
}
