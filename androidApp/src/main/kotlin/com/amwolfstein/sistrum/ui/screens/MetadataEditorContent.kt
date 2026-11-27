package com.amwolfstein.sistrum.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.amwolfstein.sistrum.R
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.tags.TagEdit
import java.io.ByteArrayOutputStream

/**
 * Single-track tag editor. Writes are crash-safe through [com.amwolfstein.sistrum.library.tags.TagWriter];
 * batch editing reuses this content with a list of items.
 */
@Composable
internal fun MetadataEditorContent(
    item: LibraryItemEntity,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = remember(item.id) { TagEditViewModel(context, item.id) }
    val state by viewModel.state.collectAsState()
    var title by remember(item.id) { mutableStateOf(item.title) }
    var artist by remember(item.id) { mutableStateOf(artistNames(item.rawArtistNames)) }
    var album by remember(item.id) { mutableStateOf(item.albumTitle) }
    var albumArtist by remember(item.id) { mutableStateOf(artistNames(item.rawAlbumArtistNames)) }
    var genre by remember(item.id) { mutableStateOf(names(item.rawGenreNames)) }
    var composer by remember(item.id) { mutableStateOf(names(item.rawComposerNames)) }
    var comment by remember(item.id) { mutableStateOf<String?>(null) }
    var year by remember(item.id) { mutableStateOf(item.year?.toString().orEmpty()) }
    var track by remember(item.id) { mutableStateOf(item.trackNumber?.toString().orEmpty()) }
    var disc by remember(item.id) { mutableStateOf(item.discNumber?.toString().orEmpty()) }
    var artwork by remember(item.id) { mutableStateOf<ByteArray?>(null) }
    val pickArtwork = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            artwork = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()?.let(::toJpeg)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text(stringResource(R.string.metadata_editor_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.metadata_editor_title_field)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(artist, { artist = it }, label = { Text(stringResource(R.string.metadata_editor_artist)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(album, { album = it }, label = { Text(stringResource(R.string.metadata_editor_album)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(albumArtist, { albumArtist = it }, label = { Text(stringResource(R.string.metadata_editor_album_artist)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(genre, { genre = it }, label = { Text(stringResource(R.string.metadata_editor_genre)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(composer, { composer = it }, label = { Text(stringResource(R.string.metadata_editor_composer)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(comment ?: "", { comment = it }, label = { Text(stringResource(R.string.metadata_editor_comment)) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.metadata_editor_year)) }, modifier = Modifier.weight(1f))
                    OutlinedTextField(track, { track = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.metadata_editor_track)) }, modifier = Modifier.weight(1f))
                    OutlinedTextField(disc, { disc = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.metadata_editor_disc)) }, modifier = Modifier.weight(1f))
                }
                TextButton(onClick = { pickArtwork.launch("image/*") }) {
                    Text(stringResource(R.string.metadata_editor_replace_artwork))
                }
                if (artwork != null) {
                    Text(stringResource(R.string.metadata_editor_replace_artwork), style = MaterialTheme.typography.bodySmall)
                }
                state.error?.let {
                    Text(stringResource(R.string.metadata_editor_failed, it), color = MaterialTheme.colorScheme.error)
                }
                if (state.saved) {
                    Text(stringResource(R.string.metadata_editor_saved), color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.saving,
                onClick = {
                    viewModel.save(
                        TagEdit(
                            title = title,
                            artist = artist,
                            album = album,
                            albumArtist = albumArtist,
                            genre = genre,
                            composer = composer,
                            comment = comment,
                            year = year.toIntOrNull(),
                            track = track.toIntOrNull(),
                            disc = disc.toIntOrNull(),
                            artwork = artwork,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.metadata_editor_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.saving) {
                Text(stringResource(R.string.metadata_editor_cancel))
            }
        },
    )

    androidx.compose.runtime.LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }
}

private fun toJpeg(source: ByteArray, quality: Int = 90): ByteArray? {
    val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size) ?: return null
    return ByteArrayOutputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        bitmap.recycle()
        output.toByteArray()
    }
}

private fun names(json: String): String =
    runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(json)
            .let { it as? kotlinx.serialization.json.JsonArray }
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.joinToString("; ")
    }.getOrNull().orEmpty()

private fun artistNames(json: String): String = names(json)
