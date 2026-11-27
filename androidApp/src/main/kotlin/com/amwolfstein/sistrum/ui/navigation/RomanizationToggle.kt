package com.amwolfstein.sistrum.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.amwolfstein.sistrum.R
import com.amwolfstein.sistrum.lyrics.RomanizationUiState
import com.amwolfstein.sistrum.ui.components.SistrumIconButton
import com.amwolfstein.sistrum.ui.components.SistrumIconButtonVariant
import com.amwolfstein.sistrum.ui.components.MaterialSymbols
import com.amwolfstein.sistrum.ui.components.sliderFilledTrackColor
import com.amwolfstein.sistrum.ui.theme.LocalSistrumColors

@Composable
internal fun RomanizationToggle(state: RomanizationUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalSistrumColors.current
    val background by animateColorAsState(
        if (state.enabled) sliderFilledTrackColor(colors, false) else fullScreenSecondaryControlBackground(colors),
        tween(250), label = "romanization-background",
    )
    val tint by animateColorAsState(
        if (state.enabled) colors.playerBackdrop.copy(alpha = 0.72f) else colors.onPrimary,
        tween(250), label = "romanization-icon",
    )
    val description = stringResource(when {
        state.loading -> R.string.player_romanization_loading
        state.error -> R.string.player_romanization_error
        state.mandarinDefault -> R.string.player_romanization_mandarin
        else -> R.string.player_romanization_hint
    })
    Box(modifier, contentAlignment = Alignment.Center) {
        SistrumIconButton(
            symbol = MaterialSymbols.Translate,
            label = stringResource(if (state.enabled) R.string.player_romanization_hide else R.string.player_romanization_show),
            onClick = onClick,
            variant = SistrumIconButtonVariant.Glass,
            tint = tint, glassColor = background, showGlassBorder = false,
            circleSize = 48.dp, iconSize = 20.dp,
            modifier = Modifier.testTag("romanization_toggle").semantics {
                selected = state.enabled
                stateDescription = description
            },
        )
        if (state.loading) CircularProgressIndicator(Modifier.size(36.dp), color = tint, strokeWidth = 2.dp)
    }
}
