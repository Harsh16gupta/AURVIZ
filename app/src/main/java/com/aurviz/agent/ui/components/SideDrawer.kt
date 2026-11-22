package com.aurviz.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurviz.agent.ui.theme.ProvideContentColor
import com.aurviz.agent.ui.theme.Theme

private val MaxDrawerWidth = 360.dp

// Adapted: the Vela kit builds this on Compose Unstyled's UnstyledDrawer.
// This port keeps the same public API on stock Dialog.
@Composable
fun SideDrawer(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(
                modifier
                    .fillMaxHeight()
                    .width(MaxDrawerWidth)
                    .background(Theme.colors.surface),
            ) {
                ProvideContentColor(Theme.colors.onSurface) {
                    content()
                }
            }
        }
    }
}
