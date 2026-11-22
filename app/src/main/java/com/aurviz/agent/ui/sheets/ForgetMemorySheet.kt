package com.aurviz.agent.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aurviz.agent.Memory
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ButtonVariant
import com.aurviz.agent.ui.components.ModalBottomSheet
import com.aurviz.agent.ui.components.Section
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.theme.Theme

// The Confirmation gate for forgetting: lists exactly what matches —
// only the previewed ids are deleted on Delete.
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
            preview.forEach { memory ->
                Text("• ${memory.text} (${memory.source})")
            }
            Text(
                "This only deletes the ${preview.size} listed memor${if (preview.size == 1) "y" else "ies"} — nothing else.",
                color = Theme.colors.onBackgroundMuted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
            Button(
                onClick = onApprove,
                variant = ButtonVariant.Destructive,
                modifier = Modifier.weight(1f),
            ) {
                Text("Delete ${preview.size}")
            }
            Button(
                onClick = onReject,
                variant = ButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            ) {
                Text("Keep")
            }
        }
    }
}
