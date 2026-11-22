package com.aurviz.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.aurviz.agent.ui.theme.ProvideContentColor
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Theme

// Adapted: the Vela kit builds this on Compose Unstyled's UnstyledDialog.
// This port keeps the same public API and visuals on stock Dialog.
@Composable
fun AlertDialog(
    visible: Boolean,
    title: String,
    message: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    Dialog(onDismissRequest = onDismissRequest) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier
                    .padding(Space * 6)
                    .widthIn(max = 400.dp)
                    .clip(Theme.shapes.large)
                    .background(Theme.colors.surface),
            ) {
                ProvideContentColor(Theme.colors.onSurface) {
                    Column(Modifier.padding(Space * 6)) {
                        Text(title, style = Theme.typography.title)
                        Text(
                            message,
                            color = Theme.colors.onBackgroundMuted,
                            modifier = Modifier.padding(top = Space * 2),
                        )
                        Column(
                            Modifier.fillMaxWidth().padding(top = Space * 6),
                            verticalArrangement = Arrangement.spacedBy(Space * 2),
                            content = actions,
                        )
                    }
                }
            }
        }
    }
}
