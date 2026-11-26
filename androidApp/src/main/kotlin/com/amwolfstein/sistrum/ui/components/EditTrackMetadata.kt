package com.amwolfstein.sistrum.ui.components

import android.content.ContentUris
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.amwolfstein.sistrum.R
import com.amwolfstein.sistrum.metadata.Mp3TagUpdate
import com.amwolfstein.sistrum.metadata.editTrack
import com.amwolfstein.sistrum.metadata.prepareTrackEdit
import com.amwolfstein.sistrum.metadata.EditTarget
import com.amwolfstein.sistrum.sync.LibraryTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun EditTrackMetadata(track: LibraryTrack) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember(track.id) { mutableStateOf(false) }
    var title by remember(track.id) { mutableStateOf(track.title) }
    var artist by remember(track.id) { mutableStateOf(track.artists) }
    var album by remember(track.id) { mutableStateOf(track.album) }
    var picture by remember(track.id) { mutableStateOf<android.net.Uri?>(null) }
    var pending by remember(track.id) { mutableStateOf<Pair<EditTarget, Mp3TagUpdate>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> picture = uri }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val edit = pending
        pending = null
        if (result.resultCode == android.app.Activity.RESULT_OK && edit != null) {
            scope.launch {
                val saved = runCatching { editTrack(context, edit.first, edit.second) }.isSuccess
                Toast.makeText(context, if (saved) R.string.track_edit_refresh_pending else R.string.track_edit_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
    if (track.audioPath?.endsWith(".mp3", true) != true) return
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.track_edit_tags)) }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.track_edit_tags)) },
        text = {
            Column {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.track_edit_title)) })
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text(stringResource(R.string.track_info_artist)) })
                OutlinedTextField(value = album, onValueChange = { album = it }, label = { Text(stringResource(R.string.track_edit_album)) })
                TextButton(onClick = { picker.launch(arrayOf("image/jpeg", "image/png")) }) {
                    Text(stringResource(if (picture == null) R.string.track_edit_artwork else R.string.track_edit_artwork_selected))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    if (title == track.title && artist == track.artists && album == track.album && picture == null) {
                        open = false
                        return@launch
                    }
                    val target = runCatching { prepareTrackEdit(context, track.id) }.getOrNull()
                    val input = picture
                    val image = if (input == null) null else withContext(Dispatchers.IO) {
                        runCatching {
                            checkNotNull(context.contentResolver.openInputStream(input)).use { stream ->
                                val out = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    require(out.size() + count <= 8 * 1024 * 1024)
                                    out.write(buffer, 0, count)
                                }
                                val bytes = out.toByteArray()
                                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                                require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192)
                                val sample = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                                val decoded = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sample))
                                decoded.recycle()
                                bytes
                            }
                        }.getOrNull()
                    }
                    val mime = input?.let(context.contentResolver::getType)
                    if (target == null || (input != null && (image == null || mime !in setOf("image/jpeg", "image/png")))) {
                        Toast.makeText(context, R.string.track_edit_failed, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, target.mediaId)
                    val request = runCatching { MediaStore.createWriteRequest(context.contentResolver, listOf(uri)) }.getOrNull()
                    if (request == null) {
                        Toast.makeText(context, R.string.track_edit_failed, Toast.LENGTH_LONG).show()
                    } else {
                        pending = target to Mp3TagUpdate(
                            title = title.takeUnless { it == track.title },
                            artist = artist.takeUnless { it == track.artists },
                            album = album.takeUnless { it == track.album },
                            artwork = image, artworkMime = mime,
                        )
                        open = false
                        permission.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    }
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
