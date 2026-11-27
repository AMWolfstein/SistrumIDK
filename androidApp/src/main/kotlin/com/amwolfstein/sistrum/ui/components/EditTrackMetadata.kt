package com.amwolfstein.sistrum.ui.components

import android.content.ContentUris
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amwolfstein.sistrum.R
import com.amwolfstein.sistrum.metadata.Mp3TagUpdate
import com.amwolfstein.sistrum.metadata.editTrack
import com.amwolfstein.sistrum.metadata.prepareTrackEdit
import com.amwolfstein.sistrum.metadata.EditTarget
import com.amwolfstein.sistrum.metadata.TagMetadata
import com.amwolfstein.sistrum.metadata.safeMp3PropertyFrames
import com.amwolfstein.sistrum.sync.LibraryTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

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
    var existingPictures by remember(track.id) { mutableStateOf<List<com.kyant.taglib.Picture>>(emptyList()) }
    var removedPictures by remember(track.id) { mutableStateOf<Set<Int>>(emptySet()) }
    var pictureType by remember(track.id) { mutableStateOf("Front Cover") }
    var pictureDescription by remember(track.id) { mutableStateOf("") }
    var existing by remember(track.id) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var lyrics by remember(track.id) { mutableStateOf("") }
    var propertyKey by remember(track.id) { mutableStateOf("") }
    var propertyValue by remember(track.id) { mutableStateOf("") }
    val changes = remember(track.id) { mutableStateMapOf<String, List<String>?>() }
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
    if (track.audioPath?.let { it.endsWith(".mp3", true) || it.endsWith(".flac", true) } != true) return
    TextButton(onClick = {
        scope.launch {
            val metadata = withContext(Dispatchers.IO) {
                runCatching { TagMetadata.read(File(requireNotNull(track.audioPath)), readPictures = true) }.getOrNull()
            }
            if (metadata == null) {
                Toast.makeText(context, R.string.track_edit_failed, Toast.LENGTH_LONG).show()
            } else {
                existing = metadata.properties
                lyrics = metadata.properties["LYRICS"].orEmpty().joinToString("\n")
                existingPictures = metadata.pictures
                removedPictures = emptySet()
                changes.clear()
                propertyKey = ""
                propertyValue = ""
                open = true
            }
        }
    }) { Text(stringResource(R.string.track_edit_tags)) }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.track_edit_tags)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.track_edit_title)) })
                OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text(stringResource(R.string.track_info_artist)) })
                OutlinedTextField(value = album, onValueChange = { album = it }, label = { Text(stringResource(R.string.track_edit_album)) })
                TextButton(onClick = { picker.launch(arrayOf("image/jpeg", "image/png")) }) {
                    Text(stringResource(if (picture == null) R.string.track_edit_artwork else R.string.track_edit_artwork_selected))
                }
                existingPictures.forEachIndexed { index, image ->
                    Row {
                        val thumbnail by produceState<android.graphics.Bitmap?>(initialValue = null, image) {
                            value = withContext(Dispatchers.IO) {
                                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                android.graphics.BitmapFactory.decodeByteArray(image.data, 0, image.data.size, bounds)
                                if (image.data.size > 8 * 1024 * 1024 || bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192) null
                                else {
                                    var sample = 1
                                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
                                    android.graphics.BitmapFactory.decodeByteArray(image.data, 0, image.data.size,
                                        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                                }
                            }
                        }
                        thumbnail?.let { Image(bitmap = it.asImageBitmap(), contentDescription = image.description, modifier = Modifier.size(56.dp)) }
                        Text(image.pictureType + " · " + image.description)
                        TextButton(onClick = { removedPictures = if (index in removedPictures) removedPictures - index else removedPictures + index }) {
                            Text(stringResource(if (index in removedPictures) R.string.batch_clear else R.string.track_edit_property_remove))
                        }
                    }
                }
                Row {
                    listOf("Front Cover", "Back Cover", "Other").forEach { type ->
                        TextButton(onClick = { pictureType = type }) { Text(if (type == pictureType) "✓ $type" else type) }
                    }
                }
                OutlinedTextField(value = pictureDescription, onValueChange = { pictureDescription = it }, label = { Text(stringResource(R.string.track_edit_picture_description)) })
                OutlinedTextField(value = lyrics, onValueChange = { lyrics = it }, label = { Text(stringResource(R.string.track_edit_lyrics)) })
                Row {
                    TextButton(onClick = { changes["LYRICS"] = listOf(lyrics) }) { Text(stringResource(R.string.track_edit_property_set)) }
                    TextButton(onClick = { changes["LYRICS"] = null }) { Text(stringResource(R.string.track_edit_property_remove)) }
                }
                Text(stringResource(R.string.track_edit_advanced))
                Text(stringResource(R.string.track_edit_multivalue_hint))
                val flac = track.audioPath?.endsWith(".flac", true) == true
                val standard = if (flac) listOf(
                    "ALBUMARTIST", "GENRE", "DATE", "YEAR", "TRACKNUMBER", "TRACKTOTAL",
                    "DISCNUMBER", "DISCTOTAL", "COMPOSER", "LYRICIST", "CONDUCTOR", "PERFORMER",
                    "COMMENT", "COPYRIGHT", "PUBLISHER", "LABEL", "ISRC", "BPM", "COMPILATION",
                    "TITLESORT", "ARTISTSORT", "ALBUMSORT", "ALBUMARTISTSORT", "COMPOSERSORT",
                ) else safeMp3PropertyFrames.keys.toList()
                (standard + existing.keys + changes.keys).distinct().sorted().filterNot {
                    it in setOf("TITLE", "ARTIST", "ALBUM", "LYRICS")
                }.forEach { key ->
                    TextButton(onClick = {
                        propertyKey = key
                        propertyValue = (changes[key] ?: existing[key]).orEmpty().joinToString("\n")
                    }) { Text(key) }
                }
                OutlinedTextField(value = propertyKey, onValueChange = { propertyKey = it }, label = { Text(stringResource(R.string.track_edit_property_name)) })
                OutlinedTextField(value = propertyValue, onValueChange = { propertyValue = it }, label = { Text(stringResource(R.string.track_edit_property_values)) })
                val key = propertyKey.trim().uppercase(Locale.ROOT)
                val editable = key.isNotEmpty() && key !in setOf("TITLE", "ARTIST", "ALBUM", "LYRICS") &&
                    (flac || key in safeMp3PropertyFrames)
                if (key.isNotEmpty() && !editable) Text(stringResource(R.string.track_edit_property_unsupported))
                Row {
                    TextButton(enabled = editable, onClick = { changes[key] = propertyValue.split('\n') }) {
                        Text(stringResource(R.string.track_edit_property_set))
                    }
                    TextButton(enabled = editable, onClick = { changes[key] = null }) {
                        Text(stringResource(R.string.track_edit_property_remove))
                    }
                }
                if (changes.isNotEmpty()) Text(stringResource(R.string.tag_property_changes_pending, changes.size))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    if (title == track.title && artist == track.artists && album == track.album && picture == null && removedPictures.isEmpty() && changes.isEmpty()) {
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
                            pictures = if (image != null || removedPictures.isNotEmpty()) {
                                existingPictures.filterIndexed { index, _ -> index !in removedPictures } +
                                    listOfNotNull(image?.let { com.kyant.taglib.Picture(it, pictureDescription, pictureType, requireNotNull(mime)) })
                            } else null,
                            additionalProperties = changes.toMap(),
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
