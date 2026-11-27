package com.amwolfstein.sistrum.library.scan

import android.content.Context
import android.content.Intent
import android.os.Build
import com.amwolfstein.sistrum.library.LibraryRepository
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.ScanRunEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Single-process owner of the active scan job; the foreground service delegates to it. */
object ScanCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var control: ScanControl? = null
    @Volatile private var pendingPause = false
    @Volatile private var pendingCancel = false

    val progress = MutableStateFlow(ScanProgress("", "", ScanState.IDLE))
    val isRunning: Boolean get() = job?.isActive == true

    fun start(context: Context, sourceId: String) {
        // Clear a stale terminal value before the service subscribes, otherwise it would stop at once.
        pendingPause = false
        pendingCancel = false
        progress.value = ScanProgress("", sourceId, ScanState.RUNNING)
        startService(context, ScanService.ACTION_START, sourceId)
    }

    /** Called by the foreground service; performs the actual scan work. */
    fun begin(context: Context, sourceId: String) {
        if (isRunning) return
        val app = context.applicationContext
        val scanControl = ScanControl().also {
            control = it
            if (pendingCancel) it.cancel() else if (pendingPause) it.pause()
        }
        job = scope.launch { runScan(app, sourceId, scanControl) }
    }

    /** Restarts the interrupted run of its own source after the service is recreated. */
    fun resumePending(context: Context) {
        if (isRunning) return
        val app = context.applicationContext
        val scanControl = ScanControl().also { control = it }
        job = scope.launch {
            val dao = LibraryDatabase.get(app).libraryDao()
            val unfinished = dao.unfinishedScanRuns().firstOrNull()
            if (unfinished == null) {
                control = null
                progress.value = progress.value.copy(state = ScanState.IDLE)
                app.stopService(Intent(app, ScanService::class.java))
                return@launch
            }
            progress.value = ScanProgress(unfinished.id, unfinished.sourceId, ScanState.RUNNING)
            runScan(app, unfinished.sourceId, scanControl)
        }
    }

    fun pause() {
        val active = control
        if (active != null) {
            active.pause()
            progress.value = progress.value.copy(state = ScanState.PAUSED)
        } else if (isRunning) {
            pendingPause = true
            progress.value = progress.value.copy(state = ScanState.PAUSED)
        }
    }

    fun resume() {
        pendingPause = false
        control?.resume()
        progress.value = progress.value.copy(state = ScanState.RUNNING)
    }

    fun cancel() {
        val active = control
        when {
            active != null -> {
                // CANCELLING is non-terminal: the service stays alive until the scan actually stops
                // and the run is flushed as CANCELLED.
                active.cancel()
                progress.value = progress.value.copy(state = ScanState.CANCELLING)
            }
            isRunning -> {
                pendingCancel = true
                progress.value = progress.value.copy(state = ScanState.CANCELLING)
            }
            else -> {
                // No job owns the request (for example a stale notification action): end immediately
                // so the foreground service cannot wait forever on a CANCELLING that nobody resolves.
                pendingCancel = false
                progress.value = progress.value.copy(state = ScanState.CANCELLED)
            }
        }
    }

    private suspend fun runScan(app: Context, sourceId: String, scanControl: ScanControl) {
        val database = LibraryDatabase.get(app)
        val dao = database.libraryDao()
        val repository = ScanRunRepository(dao)
        var scanRun: ScanRunEntity? = null
        var source: com.amwolfstein.sistrum.library.db.LibrarySourceEntity? = null
        var progressJob: Job? = null
        try {
            source = dao.source(sourceId) ?: run {
                progress.value = ScanProgress("", sourceId, ScanState.FAILED)
                return
            }
            progressJob = scope.launch {
                repository.runProgress.collect { progress.value = it }
            }
            scanRun = repository.startOrResume(source)
            val request = ScanRequest(
                runId = scanRun.id,
                sourceId = source.id,
                treeUri = source.treeUri,
                rootDocumentId = android.provider.DocumentsContract.getTreeDocumentId(
                    android.net.Uri.parse(source.treeUri),
                ),
                priorVersions = repository.priorVersions(source.id),
                alreadyScanned = repository.runProgress.value.scannedFiles,
                exclusions = repository.exclusions(source.id),
            )
            val result = SafScanner(AndroidScanTreeReader(app)).scan(
                request,
                repository.checkpoint(scanRun),
                scanControl,
            )
            repository.finish(scanRun, result, result.errors.size, result.entries.size)
            var publishError: String? = null
            runCatching { LibraryRepository(app).publish(source, scanRun.id, result.complete) }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    publishError = failure.message
                }
            // Artwork is post-publication enrichment: a decode/network problem must never mark the
            // scanned source unavailable or fail the scan.
            if (publishError == null) {
                runCatching {
                    com.amwolfstein.sistrum.library.artwork.ArtworkLibraryService(app)
                        .onLibraryChanged(source.id, result.covers)
                }
            }
            val terminal = repository.runProgress.value
            progressJob?.cancelAndJoin()
            progressJob = null
            progress.value = terminal.copy(state = result.state)
            dao.upsertSource(
                source.copy(
                    available = result.state != ScanState.FAILED && publishError == null,
                    lastScannedAt = System.currentTimeMillis(),
                    lastSeenAt = System.currentTimeMillis(),
                    lastScanError = publishError ?: result.errors.firstOrNull()?.message,
                ),
            )
        } catch (cancelled: ScanCancelledException) {
            scanRun?.let { repository.setState(it, ScanState.CANCELLED) }
            progressJob?.cancelAndJoin()
            progressJob = null
            progress.value = progress.value.copy(state = ScanState.CANCELLED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            scanRun?.let { repository.setState(it, ScanState.FAILED) }
            source?.let { dao.setSourceAvailability(it.id, false, failure.message) }
            progressJob?.cancelAndJoin()
            progressJob = null
            progress.value = progress.value.copy(state = ScanState.FAILED)
        } finally {
            progressJob?.cancel()
            if (control === scanControl) control = null
        }
    }

    private fun startService(context: Context, action: String, sourceId: String?) {
        val intent = Intent(context, ScanService::class.java).setAction(action)
        if (sourceId != null) intent.putExtra(ScanService.EXTRA_SOURCE_ID, sourceId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
