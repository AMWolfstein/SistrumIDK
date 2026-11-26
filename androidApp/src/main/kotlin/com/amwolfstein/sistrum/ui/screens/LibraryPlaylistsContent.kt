package com.amwolfstein.sistrum.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.amwolfstein.sistrum.playlist.SmartRule
import com.amwolfstein.sistrum.playlist.importM3u
import com.amwolfstein.sistrum.playlist.exportM3u
import com.amwolfstein.sistrum.sync.LocalLibraryRuntime
import com.amwolfstein.sistrum.sync.PlaylistMutation
import com.amwolfstein.sistrum.sync.PlaylistMutationOperation
import com.amwolfstein.sistrum.sync.PlaylistMutationPayload
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.amwolfstein.sistrum.R
import com.amwolfstein.sistrum.ui.components.HeroCard
import com.amwolfstein.sistrum.ui.components.LibraryVirtualList
import com.amwolfstein.sistrum.ui.components.MaterialSymbols
import com.amwolfstein.sistrum.ui.components.PlaylistRow
import com.amwolfstein.sistrum.ui.components.PlaylistContextMenu
import com.amwolfstein.sistrum.ui.components.SistrumDialog
import com.amwolfstein.sistrum.ui.components.SistrumPillButtonVariant

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LibraryPlaylistsContent(
    uiState: LibraryPlaylistsUiState,
    modifier: Modifier = Modifier,
    listState: LazyListState,
    contentPadding: PaddingValues = PaddingValues(),
    onPlaylistClick: (String) -> Unit = {},
    onPlaylistPlayNext: (List<String>) -> Unit = {},
    onPlaylistAddToQueue: (List<String>) -> Unit = {},
    onPlaylistUpdate: (String, String, android.net.Uri?, Boolean) -> Unit = { _, _, _, _ -> },
    onPlaylistDelete: (String) -> Unit = {},
) {
    var showSmartEditor by remember { mutableStateOf(false) }
    var unresolvedEntries by remember { mutableStateOf<List<String>>(emptyList()) }
    var smartName by remember { mutableStateOf("") }
    var smartValue by remember { mutableStateOf("") }
    var smartField by remember { mutableStateOf("artist") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportIds by remember { mutableStateOf<List<String>>(emptyList()) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        if (uri != null) scope.launch {
            val result = runCatching {
                val tracks = LocalLibraryRuntime.libraryStore().tracks.first()
                val exported = exportM3u(exportIds, tracks, playlistDirectory(uri))
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { stream ->
                        stream.write(exported.content.toByteArray(Charsets.UTF_8))
                    }
                }
                exported
            }
            result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            if (result.isFailure) {
                Toast.makeText(context, R.string.playlist_transfer_failed, Toast.LENGTH_LONG).show()
            } else {
                unresolvedEntries = result.getOrThrow().unresolved
                if (unresolvedEntries.isNotEmpty()) {
                    Toast.makeText(context, context.getString(R.string.playlist_export_unresolved, unresolvedEntries.size), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = runCatching {
                val content = withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 4 * 1024 * 1024)
                            output.write(buffer, 0, count)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }
                }
                val store = LocalLibraryRuntime.libraryStore()
                val imported = importM3u(content, store.tracks.first(), playlistDirectory(uri))
                val id = UUID.randomUUID().toString()
                store.createLocalPlaylist(
                    PlaylistMutation(UUID.randomUUID().toString(), id, PlaylistMutationOperation.CREATE,
                        System.currentTimeMillis(), PlaylistMutationPayload(name = context.getString(R.string.playlist_imported))),
                    initialTrackIds = imported.trackIds,
                )
                imported
            }
            result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            unresolvedEntries = result.getOrNull()?.unresolved.orEmpty()
            val message = result.getOrNull()?.let { context.getString(R.string.playlist_import_result, it.trackIds.size, it.unresolved.size, it.duplicates) }
                ?: context.getString(R.string.playlist_transfer_failed)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    val listPadding = remember(contentPadding) { PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()) }
    var contextPlaylistId by remember { mutableStateOf<String?>(null) }
    var editingPlaylist by remember { mutableStateOf<PlaylistListItem?>(null) }
    var deletingPlaylist by remember { mutableStateOf<PlaylistListItem?>(null) }
    LibraryVirtualList(
        items = uiState.playlists,
        isLoaded = uiState.isLoaded,
        key = { it.id },
        contentType = "playlist_row",
        listState = listState,
        contentPadding = listPadding,
        modifier = modifier,
        leadingContent = {
            Column {
                TextButton(onClick = { importLauncher.launch(arrayOf("audio/*", "text/*", "application/octet-stream")) }) {
                    Text(stringResource(R.string.playlist_import))
                }
                TextButton(onClick = { showSmartEditor = true }) { Text(stringResource(R.string.playlist_smart_create)) }
            }
        },
        dividerTestTag = "playlist-row-divider",
        emptyContent = {
            Column(Modifier.fillMaxSize().padding(contentPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HeroCard(
                    symbol = MaterialSymbols.QueueMusic,
                    title = stringResource(R.string.playlists_empty_title),
                    description = stringResource(R.string.playlists_empty_description),
                )
            }
        },
    ) { playlist ->
        PlaylistContextMenu(
            playlistId = playlist.id,
            trackIds = playlist.trackIds,
            expanded = contextPlaylistId == playlist.id,
            onDismiss = { if (contextPlaylistId == playlist.id) contextPlaylistId = null },
            onPlayNext = onPlaylistPlayNext,
            onAddToQueue = onPlaylistAddToQueue,
            onEdit = { editingPlaylist = playlist },
            onDelete = { deletingPlaylist = playlist },
            onExport = {
                exportIds = playlist.trackIds
                exportLauncher.launch((playlist.name.ifBlank { "playlist" }) + ".m3u8")
            },
        ) {
            PlaylistRow(
                playlist.id,
                if (playlist.isFavorite) stringResource(R.string.library_favorites) else playlist.name,
                playlist.artworkPaths,
                syncFailed = playlist.syncFailed,
                onClick = { onPlaylistClick(playlist.id) },
                onLongClick = { contextPlaylistId = playlist.id },
            )
        }
    }
    if (unresolvedEntries.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { unresolvedEntries = emptyList() },
            title = { Text(stringResource(R.string.playlist_unresolved)) },
            text = { androidx.compose.foundation.lazy.LazyColumn { items(unresolvedEntries.size) { index -> Text(unresolvedEntries[index]) } } },
            confirmButton = { TextButton(onClick = { unresolvedEntries = emptyList() }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showSmartEditor) {
        AlertDialog(
            onDismissRequest = { showSmartEditor = false },
            title = { Text(stringResource(R.string.playlist_smart_create)) },
            text = {
                Column {
                    OutlinedTextField(value = smartName, onValueChange = { smartName = it }, label = { Text(stringResource(R.string.playlist_name)) })
                    androidx.compose.foundation.layout.FlowRow {
                        listOf("artist", "album", "title", "favorites").forEach { field ->
                            TextButton(onClick = { smartField = field }) {
                                Text(stringResource(when (field) {
                                    "artist" -> R.string.playlist_smart_artist
                                    "album" -> R.string.playlist_smart_album
                                    "title" -> R.string.playlist_smart_title
                                    else -> R.string.library_favorites
                                }), color = if (smartField == field) com.amwolfstein.sistrum.ui.theme.LocalSistrumColors.current.primary else com.amwolfstein.sistrum.ui.theme.LocalSistrumColors.current.textMain)
                            }
                        }
                    }
                    if (smartField != "favorites") {
                        OutlinedTextField(value = smartValue, onValueChange = { smartValue = it }, label = { Text(stringResource(R.string.playlist_smart_contains)) })
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = smartName.isNotBlank() && (smartField == "favorites" || smartValue.isNotBlank()), onClick = {
                    val name = smartName.trim()
                    val rule = SmartRule(smartField, smartValue.trim())
                    showSmartEditor = false
                    scope.launch {
                        val store = LocalLibraryRuntime.libraryStore()
                        val id = UUID.randomUUID().toString()
                        runPlaylistWrite(context) {
                            withContext(Dispatchers.IO) { LocalLibraryRuntime.smartPlaylistRules.set(id, rule) }
                            store.createLocalPlaylist(PlaylistMutation(UUID.randomUUID().toString(), id, PlaylistMutationOperation.CREATE,
                                System.currentTimeMillis(), PlaylistMutationPayload(name = name)))
                        }
                    }
                }) { Text(stringResource(R.string.create)) }
            },
            dismissButton = { TextButton(onClick = { showSmartEditor = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    editingPlaylist?.let { playlist ->
        EditPlaylistBottomSheet(
            initialName = playlist.name,
            artworkPath = playlist.customArtworkPath,
            showNameInput = !playlist.isFavorite,
            onDismiss = { editingPlaylist = null },
            onSave = { name, artwork, clearArtwork -> onPlaylistUpdate(playlist.id, name, artwork, clearArtwork); editingPlaylist = null },
        )
    }
    deletingPlaylist?.let { playlist ->
        SistrumDialog(
            title = stringResource(R.string.playlist_delete_confirm_title),
            description = stringResource(R.string.playlist_delete_confirm_description),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { deletingPlaylist = null },
            confirmLabel = stringResource(R.string.playlist_delete),
            onConfirm = { onPlaylistDelete(playlist.id); deletingPlaylist = null },
            confirmVariant = SistrumPillButtonVariant.Destructive,
        )
    }
}

/** Resolve a local external-storage document's parent, when the provider exposes it. */
private fun playlistDirectory(uri: android.net.Uri): String? {
    if (uri.authority != "com.android.externalstorage.documents") return null
    val id = runCatching { android.provider.DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
    val volume = id.substringBefore(':')
    val relative = id.substringAfter(':', "")
    val parent = relative.substringBeforeLast('/', "")
    val root = if (volume == "primary") "/storage/emulated/0" else {
        if (!volume.matches(Regex("[A-Fa-f0-9-]+"))) return null
        "/storage/$volume"
    }
    if (parent.split('/').any { it == ".." }) return null
    return java.io.File(root, parent).path
}
