package com.aira.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aira.agent.ui.theme.Icons
import com.aira.agent.ui.theme.ProvideContentColor
import com.aira.agent.ui.theme.Space
import com.aira.agent.ui.theme.Theme
import com.aira.agent.ui.theme.X

// Adapted: the Vela kit builds this on Compose Unstyled's UnstyledDrawer.
// This port keeps the same public API and visuals on stock Dialog.
@Composable
fun ModalBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Theme.colors.scrim),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(Theme.colors.surface)
                    .padding(bottom = Space * 6),
            ) {
                ProvideContentColor(Theme.colors.onSurface) {
                    Box(
                        Modifier.align(Alignment.CenterHorizontally)
                            .padding(top = Space * 2)
                            .size(width = 36.dp, height = 5.dp)
                            .clip(Theme.shapes.fullyRounded)
                            .background(Theme.colors.onBackground.copy(alpha = 0.14f)),
                    )
                    Box(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = Space * 3, vertical = Space * 2),
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            contentDescription = "Close",
                            variant = IconButtonVariant.Tonal,
                            size = IconButtonSize.Small,
                            modifier = Modifier.align(Alignment.CenterStart),
                        ) {
                            Icon(
                                Icons.X,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Text(
                            title,
                            style = Theme.typography.title,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    Column(
                        Modifier.fillMaxWidth()
                            .heightIn(max = 560.dp)
                            .verticalScroll(rememberScrollState())
                            // Editable Confirmation fields must clear the keyboard.
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(
                                start = Space * 4,
                                end = Space * 4,
                                top = Space,
                                bottom = Space * 6,
                            ),
                        verticalArrangement = Arrangement.spacedBy(Space * 3),
                        content = content,
                    )
                }
            }
        }
    }
}
