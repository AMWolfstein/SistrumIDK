package com.amwolfstein.sistrum.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/** Sets a local 0–5 rating (null clears it) for a track id. */
val LocalTrackRatingActions = staticCompositionLocalOf<(String, Int?) -> Unit> { { _, _ -> } }
