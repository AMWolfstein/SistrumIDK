package com.amwolfstein.sistrum.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.amwolfstein.sistrum.R
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import com.amwolfstein.sistrum.library.scan.ScanCoordinator
import com.amwolfstein.sistrum.library.scan.SafSourceRepository
import kotlinx.coroutines.launch

@Composable
internal fun LibrarySettingsContent(
    onSeparatorsSelected: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember { SafSourceRepository(context) }
    val sources by repository.observeSources().collectAsState(initial = emptyList())
    val scanProgress by ScanCoordinator.progress.collectAsState()
    val scope = rememberCoroutineScope()
    var addFailed by remember { mutableStateOf(false) }
    val analysisPreferences = remember { com.amwolfstein.sistrum.library.analysis.AnalysisPreferences(context) }
    val analysisSettings by analysisPreferences.settings.collectAsState(
        initial = com.amwolfstein.sistrum.library.analysis.AnalysisSettings(),
    )
    var analyzing by remember { mutableStateOf(false) }
    var analyzedCount by remember { mutableStateOf<Int?>(null) }
    var pendingRemoval by remember { mutableStateOf<LibrarySourceEntity?>(null) }
    var exclusions by remember { mutableStateOf(emptyMap<String, List<com.amwolfstein.sistrum.library.db.LibraryExclusionEntity>>()) }
    var legacyUnresolved by remember { mutableStateOf(0) }
    val scanPreferences = remember { com.amwolfstein.sistrum.library.scan.ScanPreferences(context) }
    val autoScan by scanPreferences.autoScanEnabled.collectAsState(initial = true)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            addFailed = runCatching { repository.add(uri) }.isFailure
        }
    }

    LaunchedEffect(Unit) {
        repository.refreshAvailability()
        legacyUnresolved = com.amwolfstein.sistrum.library.db.LibraryDatabase.get(context)
            .libraryDao().legacyImportState()?.unresolved ?: 0
    }
    LaunchedEffect(sources) {
        exclusions = sources.associate { source -> source.id to repository.exclusions(source.id) }
    }

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.library_folders_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sources.isEmpty()) {
            Text(stringResource(R.string.library_no_folders), style = MaterialTheme.typography.bodyMedium)
        }
        if (legacyUnresolved > 0) {
            Text(
                stringResource(R.string.library_legacy_unresolved, legacyUnresolved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onSeparatorsSelected, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.separators_title))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.analysis_settings_title))
            Switch(
                checked = analysisSettings.writeAnalysisToFiles,
                onCheckedChange = { enabled ->
                    scope.launch { analysisPreferences.setWriteAnalysisToFiles(enabled) }
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.analysis_reuse_title))
            Switch(
                checked = analysisSettings.reuseExisting,
                onCheckedChange = { enabled ->
                    scope.launch { analysisPreferences.setReuseExisting(enabled) }
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.analysis_preserve_replaygain_title))
            Switch(
                checked = analysisSettings.preserveReplayGain,
                onCheckedChange = { enabled ->
                    scope.launch { analysisPreferences.setPreserveReplayGain(enabled) }
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.library_auto_scan))
            Switch(
                checked = autoScan,
                onCheckedChange = { enabled -> scope.launch { scanPreferences.setAutoScanEnabled(enabled) } },
            )
        }
        Text(
            text = stringResource(R.string.library_auto_scan_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.analysis_write_tags_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                if (analyzing) return@Button
                analyzing = true
                analyzedCount = null
                scope.launch {
                    val engine = com.amwolfstein.sistrum.library.analysis.AnalysisEngine(
                        context,
                        writeTags = { analysisSettings.writeAnalysisToFiles },
                        reuseExisting = analysisSettings.reuseExisting,
                        preserveReplayGain = analysisSettings.preserveReplayGain,
                    )
                    analyzedCount = runCatching { engine.analyzeAll(limit = 25) }.getOrDefault(0)
                    analyzing = false
                }
            },
            enabled = !analyzing && analysisSettings.analysisEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    analyzing -> stringResource(R.string.analysis_running)
                    analyzedCount != null -> stringResource(R.string.analysis_done, analyzedCount ?: 0)
                    else -> stringResource(R.string.analysis_run)
                },
            )
        }
        sources.forEach { source ->
            SourceCard(
                source = source,
                onEnabled = { enabled -> scope.launch { repository.setEnabled(source, enabled) } },
                onRemove = { pendingRemoval = source },
                onScan = { ScanCoordinator.start(context, source.id) },
                scanning = ScanCoordinator.isRunning && scanProgress.sourceId == source.id,
                exclusions = exclusions[source.id].orEmpty(),
                onAddExclusion = { path, folder ->
                    scope.launch {
                        repository.addExclusion(source, path, folder)
                        exclusions = sources.associate { it.id to repository.exclusions(it.id) }
                    }
                },
                onRemoveExclusion = { id ->
                    scope.launch {
                        repository.removeExclusion(id)
                        exclusions = sources.associate { it.id to repository.exclusions(it.id) }
                    }
                },
            )
        }
        if (addFailed) {
            Text(
                stringResource(R.string.library_add_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(onClick = { picker.launch(null) }) {
            Text(stringResource(R.string.library_add_folder))
        }
        val active = scanProgress
        if (active.state != com.amwolfstein.sistrum.library.scan.ScanState.IDLE) {
            Text(
                text = when (active.state) {
                    com.amwolfstein.sistrum.library.scan.ScanState.RUNNING ->
                        if (active.scannedFiles > 0) {
                            stringResource(R.string.library_scan_scanned, active.scannedFiles)
                        } else {
                            stringResource(R.string.library_scan_preparing)
                        }
                    com.amwolfstein.sistrum.library.scan.ScanState.PAUSED -> stringResource(R.string.library_scan_paused)
                    com.amwolfstein.sistrum.library.scan.ScanState.CANCELLING ->
                        stringResource(R.string.library_scan_cancelling)
                    com.amwolfstein.sistrum.library.scan.ScanState.COMPLETED -> stringResource(R.string.library_scan_complete)
                    com.amwolfstein.sistrum.library.scan.ScanState.COMPLETED_WITH_ERRORS ->
                        stringResource(R.string.library_scan_complete_with_errors)
                    com.amwolfstein.sistrum.library.scan.ScanState.CANCELLED -> stringResource(R.string.library_scan_cancelled)
                    com.amwolfstein.sistrum.library.scan.ScanState.FAILED -> stringResource(R.string.library_scan_failed)
                    com.amwolfstein.sistrum.library.scan.ScanState.IDLE -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (active.state == com.amwolfstein.sistrum.library.scan.ScanState.RUNNING ||
                active.state == com.amwolfstein.sistrum.library.scan.ScanState.PAUSED
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        if (active.state == com.amwolfstein.sistrum.library.scan.ScanState.PAUSED) {
                            ScanCoordinator.resume()
                        } else {
                            ScanCoordinator.pause()
                        }
                    }) {
                        Text(
                            stringResource(
                                if (active.state == com.amwolfstein.sistrum.library.scan.ScanState.PAUSED) {
                                    R.string.library_scan_resume
                                } else {
                                    R.string.library_scan_pause
                                },
                            ),
                        )
                    }
                    TextButton(onClick = { ScanCoordinator.cancel() }) {
                        Text(stringResource(R.string.library_scan_cancel))
                    }
                }
            }
        }
    }

    pendingRemoval?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.library_remove_title)) },
            text = { Text(stringResource(R.string.library_remove_message, source.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval = null
                    scope.launch { repository.remove(source) }
                }) { Text(stringResource(R.string.library_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text(stringResource(R.string.library_cancel))
                }
            },
        )
    }
}

@Composable
private fun SourceCard(
    source: LibrarySourceEntity,
    onEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onScan: () -> Unit,
    scanning: Boolean,
    exclusions: List<com.amwolfstein.sistrum.library.db.LibraryExclusionEntity>,
    onAddExclusion: (String, Boolean) -> Unit,
    onRemoveExclusion: (String) -> Unit,
) {
    var exclusionText by remember { mutableStateOf("") }
    var exclusionIsFolder by remember { mutableStateOf(true) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(source.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !source.available -> stringResource(R.string.library_unavailable)
                            source.isRemovable -> stringResource(R.string.library_removable)
                            else -> stringResource(R.string.library_available)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (source.available) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    )
                }
                val enableLabel = stringResource(R.string.library_enable_folder, source.displayName)
                Switch(
                    checked = source.enabled,
                    onCheckedChange = onEnabled,
                    modifier = Modifier.semantics { contentDescription = enableLabel },
                )
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onScan, enabled = source.enabled && source.available && !scanning) {
                    Text(stringResource(R.string.library_scan_now))
                }
                val removeLabel = stringResource(R.string.library_remove_folder_named, source.displayName)
                TextButton(
                    onClick = onRemove,
                    modifier = Modifier.semantics { contentDescription = removeLabel },
                ) { Text(stringResource(R.string.library_remove_folder)) }
            }
            HorizontalDivider()
            Text(stringResource(R.string.library_exclusions_title), style = MaterialTheme.typography.labelLarge)
            exclusions.forEach { exclusion ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        exclusion.relativePath,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onRemoveExclusion(exclusion.id) }) {
                        Text(stringResource(R.string.library_cancel))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(
                    value = exclusionText,
                    onValueChange = { exclusionText = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.library_exclusion_hint)) },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        if (exclusionText.isNotBlank()) {
                            onAddExclusion(exclusionText, exclusionIsFolder)
                            exclusionText = ""
                        }
                    },
                ) { Text(stringResource(R.string.library_exclusion_add)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(
                    checked = exclusionIsFolder,
                    onCheckedChange = { exclusionIsFolder = it },
                )
                Text(stringResource(R.string.library_exclusion_folder), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
