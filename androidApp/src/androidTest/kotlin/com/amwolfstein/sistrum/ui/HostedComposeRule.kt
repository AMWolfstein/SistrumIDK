package com.amwolfstein.sistrum.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule

private class HostedComposeRule(
    private val delegate: ComposeContentTestRule = createComposeRule(),
) : ComposeContentTestRule by delegate {
    override fun setContent(composable: @Composable () -> Unit) {
        delegate.setContent { TestPopupHost { composable() } }
    }
}

/**
 * `createComposeRule` with the app-level anchored menu host installed, so isolated screen tests
 * render the same context menus as the app instead of silently dropping them.
 */
fun createHostedComposeRule(): ComposeContentTestRule = HostedComposeRule()
