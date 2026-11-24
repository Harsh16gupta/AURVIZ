package com.aurviz.agent.ui.components

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
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.theme.CaretDown
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Theme

@Composable
fun ModelPill(
    model: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Ticket #87: Set badge for the Command header pill ("Set"/"Not set").
    // Null renders the human name alone (used for Auto).
    badge: String? = null,
) {
    Box(
        modifier
            .clip(Theme.shapes.fullyRounded)
            .clickable(
                onClick = onClick,
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.foundation.LocalIndication.current,
            )
            .padding(PaddingValues(horizontal = Space * 3, vertical = Space * 1.5f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space),
        ) {
            Text(model, style = Theme.typography.label, color = Theme.colors.onSurface)
            if (badge != null) {
                Text(badge, style = Theme.typography.caption, color = Theme.colors.onBackgroundMuted)
            }
            Icon(
                Icons.CaretDown,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = Theme.colors.onBackgroundMuted,
            )
        }
    }
}
