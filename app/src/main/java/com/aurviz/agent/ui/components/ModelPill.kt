package com.aurviz.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
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
import com.aurviz.agent.ui.theme.Theme

@Composable
fun ModelPill(
    model: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    Box(
        modifier
            .heightIn(min = 36.dp)
            .clip(Theme.shapes.fullyRounded)
            .background(Theme.colors.surfaceVariant)
            .clickable(
                onClick = onClick,
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.foundation.LocalIndication.current,
            )
            .padding(PaddingValues(horizontal = 14.dp, vertical = 8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(model, style = Theme.typography.label, color = Theme.colors.onSurface)
            Icon(
                Icons.CaretDown,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = Theme.colors.onBackgroundMuted,
            )
        }
    }
}
