package com.amwolfstein.sistrum.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import com.amwolfstein.sistrum.ui.components.MaterialSymbol
import com.amwolfstein.sistrum.ui.theme.LocalSistrumColors
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import com.amwolfstein.sistrum.ui.components.MaterialSymbols
import com.amwolfstein.sistrum.R
import com.amwolfstein.sistrum.sync.LibraryTrack
import com.amwolfstein.sistrum.player.PlaybackQueueSnapshot
import com.amwolfstein.sistrum.ui.components.HeroCard
import com.amwolfstein.sistrum.ui.components.LibraryVirtualList
import com.amwolfstein.sistrum.ui.components.LibraryPlaybackActions
import com.amwolfstein.sistrum.ui.components.LibraryTextFilter
import com.amwolfstein.sistrum.ui.components.TrackRow
import com.amwolfstein.sistrum.ui.components.TrackContextArtist
import com.amwolfstein.sistrum.ui.components.TrackContextMenu
import com.amwolfstein.sistrum.ui.components.TrackContextBottomSheetRequest

@Composable
internal fun LibraryTracksContent(
    uiState: LibraryTracksUiState,
    onSortOptionSelected: (TrackSortOption) -> Unit,
    onToggleSortOrder: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = remember(uiState.sortOption, uiState.sortOrder) { LazyListState() },
    contentPadding: PaddingValues = PaddingValues(),
    onTrackClick: ((LibraryTrack) -> Unit)? = null,
    onTrackMoreClick: ((LibraryTrack) -> Unit)? = null,
    playbackQueue: PlaybackQueueSnapshot = PlaybackQueueSnapshot(),
    onTrackPlayNext: (LibraryTrack) -> Unit = {},
    onTrackAddToQueue: (LibraryTrack) -> Unit = {},
    onTrackFavoriteToggle: (LibraryTrack, Boolean) -> Unit = { _, _ -> },
    onTrackAlbumClick: (LibraryTrack) -> Unit = {},
    onTrackArtistClick: (TrackContextArtist) -> Unit = {},
    hazeState: HazeState? = null,
    onPlayAll: (Boolean) -> Unit = {},
    onFilterQueryChange: (String) -> Unit = {},
    onRatingFilterChange: (Int?) -> Unit = {},
    onTrackContextBottomSheet: (TrackContextBottomSheetRequest) -> Unit = {},
) {
    var contextTrack by remember { mutableStateOf<LibraryTrack?>(null) }
    val listPadding = remember(contentPadding) {
        PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
            start = 0.dp,
            end = 0.dp,
        )
    }
    LibraryVirtualList(
        items = uiState.tracks,
        isLoaded = uiState.isLoaded,
        key = { track -> track.id.ifBlank { "${track.title}_${track.artists}" } },
        contentType = "track_row",
        listState = listState,
        contentPadding = listPadding,
        modifier = modifier,
        dividerTestTag = "track-row-divider",
        filterKey = "tracks",
        filterActive = uiState.filterQuery.isNotBlank(),
        filterContent = {
            LibraryTextFilter(
                value = uiState.filterQuery,
                onValueChange = onFilterQueryChange,
                placeholder = stringResource(R.string.filter_placeholder_search),
            )
        },
        leadingContent = {
            Column {
                LibraryPlaybackActions(
                    playLabel = stringResource(R.string.player_play),
                    shuffleLabel = stringResource(R.string.player_shuffle),
                    onPlay = { onPlayAll(false) },
                    onShuffle = { onPlayAll(true) },
                    hazeState = hazeState,
                )
                RatingFilterRow(
                    selected = uiState.ratingFilter,
                    onSelected = onRatingFilterChange,
                )
            }
        },
        alphabeticalIndexKey = when (uiState.sortOption) {
            TrackSortOption.Name -> { track -> track.sortTitle.ifBlank { track.title } }
            TrackSortOption.Artist -> { track -> track.sortArtists.ifBlank { track.artists } }
            else -> null
        },
        emptyContent = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeroCard(
                    title = stringResource(if (uiState.filterQuery.isBlank()) R.string.tracks_empty_title else R.string.tracks_no_match_title),
                    description = stringResource(if (uiState.filterQuery.isBlank()) R.string.tracks_empty_description else R.string.filter_no_match_description),
                    symbol = MaterialSymbols.MusicNote,
                )
            }
        },
    ) { track ->
        val onItemClick = remember(onTrackClick, track) {
            if (onTrackClick != null) { { onTrackClick(track) } } else null
        }
        val onItemMoreClick = remember(onTrackMoreClick, track) {
            { onTrackMoreClick?.invoke(track); contextTrack = track }
        }
        TrackContextMenu(
            track = track,
            expanded = contextTrack?.id == track.id,
            onDismiss = { if (contextTrack?.id == track.id) contextTrack = null },
            playbackQueue = playbackQueue,
            onPlayNext = onTrackPlayNext,
            onAddToQueue = onTrackAddToQueue,
            onFavoriteChange = onTrackFavoriteToggle,
            onGoToAlbum = onTrackAlbumClick,
            onGoToArtist = onTrackArtistClick,
            onBottomSheetRequested = onTrackContextBottomSheet,
        ) {
            TrackRow(
                title = track.title,
                artist = track.artists,
                artworkPath = track.artworkPath,
                onClick = onItemClick,
                onMoreClick = onItemMoreClick,
                onLongClick = { contextTrack = track },
            )
        }
    }
}


/** Minimum-rating filter: tapping the same star again clears it. */
@Composable
internal fun RatingFilterRow(selected: Int?, onSelected: (Int?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.rating_filter_label),
            style = MaterialTheme.typography.bodySmall,
            color = LocalSistrumColors.current.textMuted,
        )
        for (value in 1..5) {
            MaterialSymbol(
                symbol = MaterialSymbols.Star,
                contentDescription = null,
                size = 20.dp,
                tint = if ((selected ?: 0) >= value) LocalSistrumColors.current.primary else LocalSistrumColors.current.textMuted,
                modifier = Modifier
                    .testTag("rating-filter-star-$value")
                    .clickable { onSelected(if (selected == value) null else value) },
            )
        }
    }
}
