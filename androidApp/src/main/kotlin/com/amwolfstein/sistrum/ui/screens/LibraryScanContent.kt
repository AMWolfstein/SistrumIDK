package com.amwolfstein.sistrum.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.amwolfstein.sistrum.R
import com.amwolfstein.sistrum.sync.LocalLibraryRuntime
import com.amwolfstein.sistrum.sync.LocalLibraryScanResult
import com.amwolfstein.sistrum.sync.MediaStoreLibraryScanner
import com.amwolfstein.sistrum.sync.legacyTracksToPreserve
import com.amwolfstein.sistrum.sync.ScanFilterPreferences
import com.amwolfstein.sistrum.sync.ScanSchedule
import com.amwolfstein.sistrum.ui.components.Card
import com.amwolfstein.sistrum.ui.components.Selection
import com.amwolfstein.sistrum.ui.components.SelectionOption
import com.amwolfstein.sistrum.sync.TagSeparatorPreferences
import com.amwolfstein.sistrum.ui.components.ActionList
import com.amwolfstein.sistrum.ui.components.ActionListContainerStyle
import com.amwolfstein.sistrum.ui.components.ActionListItem
import com.amwolfstein.sistrum.ui.components.SistrumPillButton
import com.amwolfstein.sistrum.ui.components.SistrumPillButtonVariant
import com.amwolfstein.sistrum.ui.theme.LocalSistrumColors

internal data class LibraryScanUiState(
    val isScanning: Boolean = false,
    val tracks: Int = 0,
    val albums: Int = 0,
    val artists: Int = 0,
    val completed: Boolean = false,
    val permissionDenied: Boolean = false,
    val skipped: Int = 0,
    val failed: Int = 0,
    val error: Boolean = false,
    val preserved: Int = 0,
)

@Composable
internal fun LibraryScanContent(
    modifier: Modifier = Modifier,
    onScanFilterSelected: () -> Unit = {},
    onTagSeparatorsSelected: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val uiState by libraryScanState.collectAsStateWithLifecycle()
    var forceRefreshPending by remember { mutableStateOf(false) }
    val colors = LocalSistrumColors.current
    val context = LocalContext.current.applicationContext
    val scanPreferences = remember { ScanFilterPreferences(context) }
    val analysisPreferences = LocalLibraryRuntime.analysisPreferences
    val analysisSettings by analysisPreferences.settings.collectAsStateWithLifecycle(
        initialValue = com.amwolfstein.sistrum.analysis.LocalAnalysisSettings(),
    )
    val analysisFailures by LocalLibraryRuntime.libraryStore().localAnalysisFailures.collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    val analysisProgress by LocalLibraryRuntime.libraryStore().analysisProgress.collectAsStateWithLifecycle(
        initialValue = com.amwolfstein.sistrum.sync.LibraryAnalysisProgress(),
    )
    val schedule by scanPreferences.schedule.collectAsStateWithLifecycle(initialValue = ScanSchedule.Manual)

    fun updateAnalysisEnabled(enabled: Boolean) {
        scope.launch {
            try {
                analysisPreferences.setEnabled(enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("SistrumAnalysis", "Unable to update analysis settings", error)
                Toast.makeText(context, R.string.library_analysis_schedule_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchScan(scope, context, forceRefresh = forceRefreshPending) { }
            forceRefreshPending = false
        } else {
            forceRefreshPending = false
            libraryScanState.value = LibraryScanUiState(permissionDenied = true)
        }
    }
    val startScan: () -> Unit = {
        forceRefreshPending = false
        val permission = readMediaPermission()
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            launchScan(scope, context) { }
        } else {
            libraryScanState.value = LibraryScanUiState()
            permissionLauncher.launch(permission)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = stringResource(R.string.scan_description),
            color = colors.textMuted,
        )
        Spacer(modifier = Modifier.height(16.dp))
        ActionList(
            items = listOf(
                ActionListItem(R.string.scan_filter_title, onClick = onScanFilterSelected),
                ActionListItem(R.string.tag_separators_title, onClick = onTagSeparatorsSelected),
            ),
            containerStyle = ActionListContainerStyle.Card,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Card {
            Selection(
                labelRes = R.string.scan_schedule,
                options = listOf(
                    SelectionOption(ScanSchedule.Manual, R.string.scan_schedule_manual),
                    SelectionOption(ScanSchedule.OnLaunch, R.string.scan_schedule_launch),
                    SelectionOption(ScanSchedule.FifteenMinutes, R.string.scan_schedule_15),
                    SelectionOption(ScanSchedule.ThirtyMinutes, R.string.scan_schedule_30),
                    SelectionOption(ScanSchedule.Hourly, R.string.scan_schedule_hour),
                ),
                selectedValue = schedule,
                onValueSelected = { value ->
                    scope.launch {
                        try {
                            scanPreferences.setSchedule(value)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Log.w("SistrumScan", "Could not update scan schedule", error)
                            Toast.makeText(context, R.string.scan_schedule_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                },
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (!uiState.isScanning) {
            ActionList(
                items = listOf(
                    ActionListItem(R.string.scan_refresh_metadata, onClick = {
                        if (context.checkSelfPermission(readMediaPermission()) == PackageManager.PERMISSION_GRANTED) {
                            launchScan(scope, context, forceRefresh = true) { }
                        } else {
                            forceRefreshPending = true
                            permissionLauncher.launch(readMediaPermission())
                        }
                    }),
                    ActionListItem(R.string.scan_rebuild_search, onClick = {
                        scope.launch {
                            libraryScanMutex.withLock { LocalLibraryRuntime.libraryStore().rebuildLocalSearch() }
                        }
                    }),
                ),
                containerStyle = ActionListContainerStyle.Card,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                ActionList(
                    items = listOf(
                        ActionListItem(
                            R.string.library_analysis_enable,
                            trailingContent = {
                                Switch(
                                    checked = analysisSettings.enabled,
                                    onCheckedChange = ::updateAnalysisEnabled,
                                )
                            },
                            onClick = { updateAnalysisEnabled(!analysisSettings.enabled) },
                        ),
                    ),
                    containerStyle = ActionListContainerStyle.Plain,
                )
                if (analysisSettings.enabled) {
                    Selection(
                        labelRes = R.string.library_analysis_workers,
                        options = listOf(
                            SelectionOption(1, R.string.library_analysis_one_worker),
                            SelectionOption(2, R.string.library_analysis_two_workers),
                        ),
                        selectedValue = analysisSettings.workers,
                        onValueSelected = { count -> scope.launch { analysisPreferences.setWorkers(count) } },
                    )
                    Text(
                        text = stringResource(
                            R.string.library_analysis_progress,
                            analysisProgress.analyzedTracks,
                            analysisProgress.totalTracks,
                            analysisProgress.failedTracks,
                        ),
                        color = colors.textMuted,
                    )
                    analysisFailures.forEach { failure ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = failure.title)
                                if (failure.failure.isNotBlank()) {
                                    Text(text = failure.failure, color = colors.textMuted)
                                }
                            }
                            TextButton(onClick = {
                                scope.launch { LocalLibraryRuntime.libraryStore().retryLocalAnalysisFailure(failure.trackId) }
                            }) {
                                Text(text = stringResource(R.string.library_analysis_retry_one))
                            }
                        }
                    }
                    if (analysisProgress.failedTracks > 0) {
                        ScanButton(
                            label = stringResource(R.string.library_analysis_retry),
                            onClick = { scope.launch { LocalLibraryRuntime.libraryStore().retryLocalAnalysisFailures() } },
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        when {
            uiState.isScanning -> {
                Text(
                    text = if (uiState.tracks > 0) stringResource(R.string.scan_progress, uiState.tracks)
                    else stringResource(R.string.scan_running),
                    color = colors.textMuted,
                )
                Spacer(modifier = Modifier.height(16.dp))
                ScanButton(
                    label = stringResource(R.string.scan_cancel),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { activeLibraryScan?.cancel() },
                )
            }
            uiState.error -> {
                Text(text = stringResource(R.string.scan_failed), color = colors.textMuted)
                Spacer(modifier = Modifier.height(16.dp))
                ScanButton(
                    label = stringResource(R.string.scan_again),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = startScan,
                )
            }
            uiState.permissionDenied -> {
                Text(
                    text = stringResource(R.string.scan_permission_denied),
                    color = colors.textMuted,
                )
                Spacer(modifier = Modifier.height(16.dp))
                ScanButton(
                    label = stringResource(R.string.scan_start),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = startScan,
                )
            }
            uiState.completed -> {
                Text(
                    text = scanCompleteSummary(uiState.tracks, uiState.albums, uiState.artists),
                    color = colors.textMuted,
                )
                Text(
                    text = stringResource(R.string.scan_diagnostics, uiState.skipped, uiState.failed),
                    color = colors.textMuted,
                )
                if (uiState.preserved > 0) {
                    Text(
                        text = stringResource(R.string.scan_preserved_legacy, uiState.preserved),
                        color = colors.textMuted,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                ScanButton(
                    label = stringResource(R.string.scan_again),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = startScan,
                )
            }
            else -> ScanButton(
                label = stringResource(R.string.scan_start),
                modifier = Modifier.fillMaxWidth(),
                onClick = startScan,
            )
        }
    }
}

@Composable
private fun ScanButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SistrumPillButton(
        label = label,
        variant = SistrumPillButtonVariant.Primary,
        onClick = onClick,
        modifier = modifier,
    )
}

/** "729 tracks, 235 albums, 71 artists", with each count in its plural form. */
@Composable
internal fun scanCompleteSummary(tracks: Int, albums: Int, artists: Int): String = stringResource(
    R.string.scan_complete,
    pluralStringResource(R.plurals.scan_complete_tracks, tracks, tracks),
    pluralStringResource(R.plurals.scan_complete_albums, albums, albums),
    pluralStringResource(R.plurals.scan_complete_artists, artists, artists),
)

internal val libraryScanMutex = Mutex()
internal val libraryScanState = MutableStateFlow(LibraryScanUiState())
private var activeLibraryScan: Job? = null

/** All entry points share one scan, rather than queueing duplicate scans. */
internal fun launchScan(
    scope: CoroutineScope,
    context: Context,
    forceRefresh: Boolean = false,
    onResult: (LibraryScanUiState) -> Unit,
): Job =
    scope.launch {
        if (!libraryScanMutex.tryLock()) {
            onResult(libraryScanState.first { !it.isScanning })
            return@launch
        }
        val progressActive = java.util.concurrent.atomic.AtomicBoolean(true)
        activeLibraryScan = currentCoroutineContext()[Job]
        fun publish(state: LibraryScanUiState) {
            libraryScanState.value = state
            onResult(state)
        }
        publish(LibraryScanUiState(isScanning = true))
        try {
            val result = performScan(context, forceRefresh) { count ->
                scope.launch {
                    if (progressActive.get()) publish(LibraryScanUiState(isScanning = true, tracks = count))
                }
            }
            progressActive.set(false)
            publish(result ?: LibraryScanUiState())
        } finally {
            progressActive.set(false)
            activeLibraryScan = null
            libraryScanMutex.unlock()
            if (!currentCoroutineContext().isActive) publish(LibraryScanUiState())
        }
    }

internal fun readMediaPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

internal suspend fun performScan(
    context: Context,
    forceRefresh: Boolean = false,
    onProgress: (Int) -> Unit,
): LibraryScanUiState? = withContext(Dispatchers.IO) {
    val scanContext = currentCoroutineContext()
    runCatching {
        val separatorPreferences = TagSeparatorPreferences(context)
        val separators = separatorPreferences.current()
        val scanner = MediaStoreLibraryScanner(
            contentResolver = context.contentResolver,
            artworkDir = File(context.filesDir, "artwork"),
            separators = separators,
        )
        val syncStore = LocalLibraryRuntime.libraryStore()
        val filter = ScanFilterPreferences(context).currentFilter()
        val prior = syncStore.priorScanState()
        val result: LocalLibraryScanResult = scanner.scan(
            prior = prior,
            filter = filter,
            onProgress = onProgress,
            checkCancelled = { scanContext.ensureActive() },
            forceRefresh = forceRefresh,
        )
        scanContext.ensureActive()
        check(prior.tracksByTrackId.values.none { state ->
            val root = externalStorageRoot(state.absolutePath)
            root != null && !File(root).isDirectory
        }) { "A previously scanned storage volume is unavailable" }
        val preservedTrackIds = legacyTracksToPreserve(prior.tracksByTrackId, result.snapshot.tracks, filter)
        val written = syncStore.writeLocalLibrary(
            snapshot = result.snapshot,
            audioRows = result.audio,
            artworkRows = result.artwork,
            preservedTrackIds = preservedTrackIds,
        )
        if (!written) {
            // An empty result (most often an empty whitelist) would have replaced the whole
            // library; writeLocalLibrary kept it instead, so tell the user why nothing changed.
            Log.w("SistrumScan", "Scan found no tracks; kept the existing library")
            withContext(Dispatchers.Main) {
                Toast.makeText(context.applicationContext, R.string.scan_found_nothing_library_kept, Toast.LENGTH_LONG).show()
            }
            return@runCatching LibraryScanUiState()
        }
        separatorPreferences.setAppliedSignature(separators.signature)
        if (LocalLibraryRuntime.deezerArtwork.enabled.value) {
            val artists = result.snapshot.tracks.flatMap { it.artists }
                .distinctBy { it.id }
                .map { com.amwolfstein.sistrum.sync.LibraryArtist(id = it.id, name = it.name) }
            LocalLibraryRuntime.deezerArtwork.refresh(artists)
        }
        val albums = result.snapshot.tracks.map { it.album.id }.distinct().size
        val artists = result.snapshot.tracks.flatMap { it.artists }.map { it.id }.distinct().size
        LibraryScanUiState(
            tracks = result.snapshot.tracks.size,
            albums = albums,
            artists = artists,
            completed = true,
            skipped = result.skipped,
            failed = result.failed,
            preserved = preservedTrackIds.size,
        )
    }.getOrElse {
        if (it is CancellationException) throw it
        Log.w("SistrumScan", "Library scan failed", it)
        LibraryScanUiState(error = true)
    }
}
/** An absent removable storage root cannot authorize removal of its tracks. */
internal fun externalStorageRoot(path: String): String? {
    val parts = path.split('/').filter(String::isNotEmpty)
    if (parts.size < 3 || parts[0] != "storage") return null
    return if (parts[1] == "emulated") "/storage/emulated/${parts[2]}" else "/storage/${parts[1]}"
}
