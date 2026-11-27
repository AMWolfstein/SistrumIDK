package com.amwolfstein.sistrum.ui.components

import android.content.ContentUris
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.amwolfstein.sistrum.R
import com.amwolfstein.sistrum.analysis.ReplayGainTrack
import com.amwolfstein.sistrum.sync.LibraryTrack
import com.amwolfstein.sistrum.sync.LocalLibraryRuntime
import com.amwolfstein.sistrum.metadata.EditTarget
import com.amwolfstein.sistrum.metadata.Mp3TagUpdate
import com.amwolfstein.sistrum.metadata.editTrack
import com.amwolfstein.sistrum.metadata.prepareTrackEdit
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun ReplayGainTrackPreview(track: LibraryTrack) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var running by remember(track.id) { mutableStateOf<Job?>(null) }
    var result by remember(track.id) { mutableStateOf<ReplayGainTrack?>(null) }
    var analyzedHash by remember(track.id) { mutableStateOf<String?>(null) }
    var failed by remember(track.id) { mutableStateOf(false) }
    var pending by remember(track.id) { mutableStateOf<Pair<EditTarget, Mp3TagUpdate>?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { reply ->
        val edit = pending
        pending = null
        if (reply.resultCode == android.app.Activity.RESULT_OK && edit != null) {
            scope.launch {
                val saved = runCatching { editTrack(context, edit.first, edit.second) }.isSuccess
                Toast.makeText(context, if (saved) R.string.track_edit_refresh_pending else R.string.track_edit_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
    Column {
        TextButton(onClick = {
            if (running != null) {
                running?.cancel()
            } else {
                running = scope.launch {
                    result = null
                    analyzedHash = null
                    failed = false
                    try {
                        val prior = LocalLibraryRuntime.libraryStore().priorScanState().tracksByTrackId[track.id]
                        checkNotNull(prior)
                        result = LocalLibraryRuntime.analysisController.analyzeReplayGain(prior.absolutePath, prior.identityHash)
                        analyzedHash = prior.contentHash.takeIf(String::isNotBlank)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        running = null
                    }
                }
            }
        }) {
            Text(stringResource(if (running == null) R.string.replaygain_scan_track else R.string.cancel))
        }
        if (running != null) Text(stringResource(R.string.replaygain_scanning))
        if (failed) Text(stringResource(R.string.replaygain_scan_failed))
        if (track.audioPath?.let { it.endsWith(".flac", true) || it.endsWith(".mp3", true) } == true) {
            TextButton(onClick = {
                scope.launch {
                    val target = runCatching { prepareTrackEdit(context, track.id) }.getOrNull()
                    if (target == null) { failed = true; return@launch }
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, target.mediaId)
                    val request = runCatching { MediaStore.createWriteRequest(context.contentResolver, listOf(uri)) }.getOrNull()
                    if (request == null) { failed = true; return@launch }
                    pending = target to Mp3TagUpdate(additionalProperties = mapOf(
                        "REPLAYGAIN_TRACK_GAIN" to null, "REPLAYGAIN_TRACK_PEAK" to null,
                        "REPLAYGAIN_ALBUM_GAIN" to null, "REPLAYGAIN_ALBUM_PEAK" to null,
                    ))
                    consent.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
            }) { Text(stringResource(R.string.replaygain_remove_tags)) }
        }
        result?.let { measured ->
            Text(stringResource(R.string.replaygain_preview,
                String.format(Locale.ROOT, "%.2f", measured.loudnessLufs),
                String.format(Locale.ROOT, "%+.2f", measured.gainDb),
                String.format(Locale.ROOT, "%.6f", measured.samplePeak),
                String.format(Locale.ROOT, "%.2f", measured.truePeakDb)))
            if (track.audioPath?.let { it.endsWith(".flac", true) || it.endsWith(".mp3", true) } == true && analyzedHash != null) {
                TextButton(onClick = {
                    scope.launch {
                        val target = runCatching { prepareTrackEdit(context, track.id) }.getOrNull()
                        if (target == null || target.contentHash != analyzedHash) {
                            failed = true
                            return@launch
                        }
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, target.mediaId)
                        val request = runCatching { MediaStore.createWriteRequest(context.contentResolver, listOf(uri)) }.getOrNull()
                        if (request == null) {
                            failed = true
                            return@launch
                        }
                        pending = target to Mp3TagUpdate(additionalProperties = mapOf(
                            "REPLAYGAIN_TRACK_GAIN" to listOf(String.format(Locale.ROOT, "%+.2f dB", measured.gainDb)),
                            "REPLAYGAIN_TRACK_PEAK" to listOf(String.format(Locale.ROOT, "%.8f", measured.samplePeak)),
                        ))
                        consent.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    }
                }) { Text(stringResource(R.string.replaygain_write_track)) }
            }
        }
    }
}
