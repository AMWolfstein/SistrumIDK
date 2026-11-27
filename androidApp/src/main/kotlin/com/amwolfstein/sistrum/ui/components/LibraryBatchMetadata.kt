package com.amwolfstein.sistrum.ui.components

import android.app.Activity
import android.content.ContentUris
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.amwolfstein.sistrum.R
import com.amwolfstein.sistrum.analysis.ReplayGainTrack
import com.amwolfstein.sistrum.metadata.EditTarget
import com.amwolfstein.sistrum.metadata.Mp3TagUpdate
import com.amwolfstein.sistrum.metadata.TagMetadata
import com.amwolfstein.sistrum.metadata.editTrack
import com.amwolfstein.sistrum.metadata.prepareTrackEdit
import com.amwolfstein.sistrum.metadata.safeMp3PropertyFrames
import com.amwolfstein.sistrum.sync.LibraryTrack
import com.amwolfstein.sistrum.sync.LocalLibraryRuntime
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private data class BatchEdit(val target: EditTarget, val update: Mp3TagUpdate)
private data class BatchMeasurement(val result: ReplayGainTrack, val hash: String)

@Composable
internal fun LibraryBatchMetadata() {
    val tracks by LocalLibraryRuntime.libraryStore().tracks.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    val measurements = remember { mutableStateMapOf<String, BatchMeasurement>() }
    val messages = remember { mutableStateMapOf<String, String>() }
    var property by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var skipTagged by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var pending by remember { mutableStateOf<List<BatchEdit>>(emptyList()) }
    var presented by remember { mutableStateOf(false) }
    val selectedTracks = tracks.filter { selected[it.id] == true }
    val busy = running != null || pending.isNotEmpty()

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { reply ->
        val edits = pending.take(500)
        if (reply.resultCode == Activity.RESULT_OK && edits.isNotEmpty()) {
            running = scope.launch {
                progress = 0
                total = edits.size
                try {
                    for (edit in edits) {
                        val result = try {
                            editTrack(context, edit.target, edit.update)
                            context.getString(R.string.track_edit_refresh_pending)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            context.getString(R.string.track_edit_failed)
                        }
                        messages[edit.target.trackId] = result
                        progress++
                    }
                } finally {
                    pending = if (currentCoroutineContext().isActive) pending.drop(edits.size) else emptyList()
                    presented = false
                    running = null
                }
            }
        } else {
            pending.forEach { messages[it.target.trackId] = context.getString(R.string.batch_denied) }
            pending = emptyList()
            presented = false
        }
    }
    LaunchedEffect(pending, presented, running) {
        if (pending.isNotEmpty() && !presented && running == null) {
            try {
                val uris = pending.take(500).map { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.target.mediaId) }
                val request = MediaStore.createWriteRequest(context.contentResolver, uris)
                presented = true
                consent.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } catch (error: Exception) {
                pending.forEach { messages[it.target.trackId] = context.getString(R.string.batch_permission_error) }
                pending = emptyList()
            }
        }
    }

    fun requestWrites(updates: Map<String, Mp3TagUpdate>) {
        running = scope.launch {
            progress = 0
            total = updates.size
            val edits = mutableListOf<BatchEdit>()
            try {
                for ((id, update) in updates) {
                    try {
                        edits.add(BatchEdit(prepareTrackEdit(context, id), update))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        messages[id] = context.getString(R.string.track_edit_failed)
                    }
                    progress++
                }
                if (edits.isNotEmpty()) {
                    pending = edits
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                edits.forEach { messages[it.target.trackId] = context.getString(R.string.batch_permission_error) }
            } finally {
                running = null
            }
        }
    }

    TextButton(onClick = { open = true }) { Text(stringResource(R.string.batch_edit_open)) }
    if (open) AlertDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { Text(stringResource(R.string.batch_edit_open)) },
        text = {
            Column {
                Row {
                    TextButton(enabled = !busy, onClick = { tracks.forEach { selected[it.id] = true } }) { Text(stringResource(R.string.batch_select_all)) }
                    TextButton(enabled = !busy, onClick = { selected.clear() }) { Text(stringResource(R.string.batch_clear)) }
                }
                Text(stringResource(R.string.batch_selected, selectedTracks.size))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 210.dp)) {
                    items(tracks, key = { it.id }) { track ->
                        Row {
                            Checkbox(checked = selected[track.id] == true, enabled = !busy,
                                onCheckedChange = { selected[track.id] = it })
                            Column {
                                Text(track.title)
                                messages[track.id]?.let { Text(it) }
                                measurements[track.id]?.result?.let { gain ->
                                    Text(stringResource(R.string.replaygain_preview,
                                        String.format(Locale.ROOT, "%.2f", gain.loudnessLufs),
                                        String.format(Locale.ROOT, "%+.2f", gain.gainDb),
                                        String.format(Locale.ROOT, "%.6f", gain.samplePeak),
                                        String.format(Locale.ROOT, "%.2f", gain.truePeakDb)))
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(value = property, onValueChange = { property = it }, label = { Text(stringResource(R.string.track_edit_property_name)) })
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(stringResource(R.string.track_edit_property_values)) })
                val key = property.trim().uppercase(Locale.ROOT)
                val valid = key.isNotBlank() && selectedTracks.isNotEmpty() && selectedTracks.all { track ->
                    val path = track.audioPath.orEmpty()
                    path.endsWith(".flac", true) || (path.endsWith(".mp3", true) && (key in safeMp3PropertyFrames || key in setOf("TITLE", "ARTIST", "ALBUM")))
                }
                Row {
                    TextButton(enabled = valid && !busy, onClick = {
                        requestWrites(selectedTracks.associate { track ->
                            val update = when (key) {
                                "TITLE" -> Mp3TagUpdate(title = value)
                                "ARTIST" -> Mp3TagUpdate(artist = value)
                                "ALBUM" -> Mp3TagUpdate(album = value)
                                else -> Mp3TagUpdate(additionalProperties = mapOf(key to value.split('\n')))
                            }
                            track.id to update
                        })
                    }) { Text(stringResource(R.string.track_edit_property_set)) }
                    TextButton(enabled = valid && !busy, onClick = {
                        requestWrites(selectedTracks.associate { it.id to Mp3TagUpdate(additionalProperties = mapOf(key to null)) })
                    }) { Text(stringResource(R.string.track_edit_property_remove)) }
                }
                Row {
                    Checkbox(checked = skipTagged, onCheckedChange = { skipTagged = it }, enabled = !busy)
                    Text(stringResource(R.string.batch_skip_tagged))
                }
                Row {
                    TextButton(enabled = selectedTracks.isNotEmpty() && !busy, onClick = {
                        running = scope.launch {
                            measurements.clear()
                            messages.clear()
                            progress = 0
                            total = selectedTracks.size
                            try {
                                for (track in selectedTracks) {
                                    try {
                                        val prior = LocalLibraryRuntime.libraryStore().priorTrackScanState(track.id)
                                        checkNotNull(prior)
                                        val existing = if (skipTagged) withContext(Dispatchers.IO) {
                                            TagMetadata.read(File(prior.absolutePath), false).properties.let(::hasValidTrackGain)
                                        } else null
                                        if (existing == true) {
                                            messages[track.id] = context.getString(R.string.batch_skipped)
                                        } else {
                                            val result = LocalLibraryRuntime.analysisController.analyzeReplayGain(prior.absolutePath, prior.identityHash)
                                            measurements[track.id] = BatchMeasurement(result, prior.contentHash)
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        messages[track.id] = context.getString(R.string.replaygain_scan_failed)
                                    }
                                    progress++
                                }
                            } finally {
                                running = null
                            }
                        }
                    }) { Text(stringResource(R.string.replaygain_scan_track)) }
                    TextButton(enabled = measurements.isNotEmpty() && !busy, onClick = {
                        running = scope.launch {
                            val updates = mutableMapOf<String, Mp3TagUpdate>()
                            for (track in selectedTracks) {
                                val measured = measurements[track.id] ?: continue
                                if (track.audioPath?.let { it.endsWith(".flac", true) || it.endsWith(".mp3", true) } != true) continue
                                val prior = LocalLibraryRuntime.libraryStore().priorTrackScanState(track.id)
                                if (prior?.contentHash != measured.hash || measured.hash.isBlank()) {
                                    messages[track.id] = context.getString(R.string.replaygain_scan_failed)
                                    continue
                                }
                                updates[track.id] = Mp3TagUpdate(additionalProperties = mapOf(
                                    "REPLAYGAIN_TRACK_GAIN" to listOf(String.format(Locale.ROOT, "%+.2f dB", measured.result.gainDb)),
                                    "REPLAYGAIN_TRACK_PEAK" to listOf(String.format(Locale.ROOT, "%.8f", measured.result.samplePeak)),
                                ))
                            }
                            running = null
                            requestWrites(updates)
                        }
                    }) { Text(stringResource(R.string.replaygain_write_track)) }
                }
                TextButton(enabled = selectedTracks.any { it.audioPath?.let { path -> path.endsWith(".flac", true) || path.endsWith(".mp3", true) } == true } && !busy, onClick = {
                    val changes = mapOf<String, List<String>?>("REPLAYGAIN_TRACK_GAIN" to null, "REPLAYGAIN_TRACK_PEAK" to null,
                        "REPLAYGAIN_ALBUM_GAIN" to null, "REPLAYGAIN_ALBUM_PEAK" to null)
                    requestWrites(selectedTracks.filter { it.audioPath?.let { path -> path.endsWith(".flac", true) || path.endsWith(".mp3", true) } == true }
                        .associate { it.id to Mp3TagUpdate(additionalProperties = changes) })
                }) { Text(stringResource(R.string.replaygain_remove_tags)) }
                if (total > 0) {
                    Text(stringResource(R.string.batch_progress, progress, total))
                    val savedMessage = stringResource(R.string.track_edit_refresh_pending)
                    val skippedMessage = stringResource(R.string.batch_skipped)
                    val saved = messages.values.count { it == savedMessage }
                    val skipped = messages.values.count { it == skippedMessage }
                    val failed = messages.size - saved - skipped
                    Text(stringResource(R.string.batch_summary, measurements.size, saved, skipped, failed))
                }
                if (running != null) {
                    TextButton(onClick = { running?.cancel() }) { Text(stringResource(R.string.cancel)) }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text(stringResource(R.string.close)) } },
    )
}

internal fun hasValidTrackGain(properties: Map<String, List<String>>): Boolean {
    val gain = properties["REPLAYGAIN_TRACK_GAIN"]?.firstOrNull()?.trim()?.removeSuffix("dB")?.trim()?.toDoubleOrNull()
    val peak = properties["REPLAYGAIN_TRACK_PEAK"]?.firstOrNull()?.trim()?.toDoubleOrNull()
    return gain?.isFinite() == true && peak?.isFinite() == true && peak > 0.0
}
