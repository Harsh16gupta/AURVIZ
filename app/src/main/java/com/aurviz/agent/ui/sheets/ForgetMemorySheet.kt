package com.aurviz.agent.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurviz.agent.Memory
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ButtonVariant
import com.aurviz.agent.ui.components.Icon
import com.aurviz.agent.ui.components.ModalBottomSheet
import com.aurviz.agent.ui.components.Section
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Theme
import com.aurviz.agent.ui.theme.Trash

// Ticket #91: the Confirmation gate for forgetting, styled as a Signal
// destructive sheet. Preview rows clamp to maxLines 3 with an N-more
// indicator for the overflow; the actions stack vertically as 48dp
// destructive Delete N + outline Keep. Only the previewed ids delete.
// GLOSSARY: Confirmation, Memory.
private const val PREVIEW_ROWS = 3

@Composable
fun ForgetMemorySheet(
    preview: List<Memory>,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        visible = preview.isNotEmpty(),
        onDismiss = onReject,
        title = "Forget Memory",
        modifier = modifier,
    ) {
        Section("Delete these?") {
            preview.take(PREVIEW_ROWS).forEach { memory ->
                Text(
                    memory.text,
                    color = Theme.colors.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val overflow = preview.size - PREVIEW_ROWS
            if (overflow > 0) {
                Text(
                    "+$overflow more",
                    style = Theme.typography.caption,
                    color = Theme.colors.onBackgroundMuted,
                )
            }
            Text(
                "This only deletes the ${preview.size} listed ${if (preview.size == 1) "Memory" else "Memories"} — nothing else.",
                color = Theme.colors.onBackgroundMuted,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Space * 2),
        ) {
            Button(
                onClick = onApprove,
                variant = ButtonVariant.Destructive,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space * 2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Trash,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Delete ${preview.size}")
                }
            }
            Button(
                onClick = onReject,
                variant = ButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text("Keep")
            }
        }
    }
}
