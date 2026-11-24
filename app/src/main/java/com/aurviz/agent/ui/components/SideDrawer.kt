package com.aurviz.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurviz.agent.ui.theme.ProvideContentColor
import com.aurviz.agent.ui.theme.Theme

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
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Theme.colors.scrim).clickable(
                onClick = onDismiss,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.82f)
                    .widthIn(max = 310.dp)
                    .background(Theme.colors.surface)
                    .clickable(
                        enabled = false,
                        onClick = {},
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ),
            ) {
                // Bottom inset is gesture-aware: on gesture-nav devices
                // navigationBars.bottom reads ~0, so union safeDrawing +
                // navigationBars + systemGestures (home-handle zone) plus a
                // 16.dp floor keeps the profile footer fully above the edge.
                Column(
                    Modifier.fillMaxSize()
                        .statusBarsPadding()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing
                                .union(WindowInsets.navigationBars)
                                .union(WindowInsets.systemGestures)
                                .only(WindowInsetsSides.Bottom),
                        )
                        .padding(bottom = 16.dp),
                ) {
                    ProvideContentColor(Theme.colors.onSurface) {
                        content()
                    }
                }
                Box(
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(1.dp)
                        .background(Theme.colors.edge),
                )
            }
        }
    }
}
