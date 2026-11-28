package com.amwolfstein.sistrum.ui.screens

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.amwolfstein.sistrum.R
import com.amwolfstein.sistrum.ui.components.HeroCard
import com.amwolfstein.sistrum.ui.components.LibraryVirtualList
import com.amwolfstein.sistrum.ui.components.MaterialSymbols
import com.amwolfstein.sistrum.ui.components.PlaylistRow
import com.amwolfstein.sistrum.ui.components.PlaylistContextMenu
import com.amwolfstein.sistrum.ui.components.SistrumDialog
import com.amwolfstein.sistrum.ui.components.SistrumPillButtonVariant

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
    onImportM3u8: (android.net.Uri) -> Unit = {},
    onExportM3u8: (String, android.net.Uri) -> Unit = { _, _ -> },
    onNewSmartPlaylist: () -> Unit = {},
) {
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onImportM3u8) }
    var exportingPlaylistId by remember { mutableStateOf<String?>(null) }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("audio/x-mpegurl"),
    ) { uri ->
        val id = exportingPlaylistId
        exportingPlaylistId = null
        if (uri != null && id != null) onExportM3u8(id, uri)
    }
    fun startExport(playlistId: String, name: String) {
        exportingPlaylistId = playlistId
        exportLauncher.launch((name.ifBlank { "playlist" }).replace('/', '_') + ".m3u8")
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
        dividerTestTag = "playlist-row-divider",
        leadingContent = {
            androidx.compose.material3.TextButton(onClick = {
                importLauncher.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "text/plain", "*/*"))
            }, modifier = Modifier.testTag("playlists-import-m3u8")) {
                androidx.compose.material3.Text(stringResource(R.string.playlists_import_m3u8))
            }
            androidx.compose.material3.TextButton(
                onClick = onNewSmartPlaylist,
                modifier = Modifier.testTag("playlists-new-smart"),
            ) {
                androidx.compose.material3.Text(stringResource(R.string.smart_playlist_title))
            }
        },
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
            onExport = { startExport(playlist.id, playlist.name) },
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
