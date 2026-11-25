package com.aira.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aira.agent.ui.theme.ProvideContentColor
import com.aira.agent.ui.theme.Theme

// Adapted: the Vela kit wraps content in Compose Unstyled's DialogHost and
// sets system-bar contrast via com.composables.systembars. This port keeps
// the same public API on stock foundation (edge-to-edge is handled by the
// Activity in 2B).
@Composable
fun ScreenScaffold(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // Top inset lives here so every destination (Page AppBar included)
    // clears the status bar; bottom/IME insets are handled per screen.
    Box(
        modifier
            .fillMaxSize()
            .background(Theme.colors.background)
            .statusBarsPadding()
            .consumeWindowInsets(WindowInsets.statusBars),
    ) {
        ProvideContentColor(Theme.colors.onBackground) { content() }
    }
}
