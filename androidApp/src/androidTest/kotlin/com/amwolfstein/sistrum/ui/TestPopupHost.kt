package com.amwolfstein.sistrum.ui

import androidx.compose.runtime.Composable
import com.amwolfstein.sistrum.ui.components.AnchoredPopupMenuHost

/**
 * Installs the app-level anchored menu host for isolated screen tests. Production composes it
 * inside `App()`; tests that mount one screen need the same host or every context menu is a no-op.
 */
@Composable
fun TestPopupHost(content: @Composable () -> Unit) {
    AnchoredPopupMenuHost(hazeState = null) { content() }
}
