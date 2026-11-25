package com.aira.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aira.agent.ui.theme.Icons
import com.aira.agent.ui.theme.Space
import com.aira.agent.ui.theme.Theme
import com.aira.agent.ui.theme.X

@Composable
fun ToolChip(
    label: String,
    icon: ImageVector,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(Theme.shapes.fullyRounded)
            .background(Theme.colors.surfaceVariant)
            .clickable(
                onClick = onRemove,
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.foundation.LocalIndication.current,
            )
            .padding(
                PaddingValues(
                    start = Space * 2.5f,
                    end = Space * 2,
                    top = Space * 1.5f,
                    bottom = Space * 1.5f,
                )
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space * 1.5f),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Theme.colors.primary,
            )
            Text(label, style = Theme.typography.label, color = Theme.colors.onSurfaceVariant)
            Icon(
                Icons.X,
                contentDescription = "Turn off $label",
                modifier = Modifier.size(12.dp),
                tint = Theme.colors.onBackgroundMuted,
            )
        }
    }
}
