package com.aira.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aira.agent.ui.theme.CaretRight
import com.aira.agent.ui.theme.Icons
import com.aira.agent.ui.theme.Space
import com.aira.agent.ui.theme.Theme

// Full-width starter row (48–52dp): leading icon + label (maxLines=2) +
// trailing chevron. One tap starts a Task Execution. Stock foundation only.
@Composable
fun StarterRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp, max = 52.dp)
            .clip(Theme.shapes.grouped)
            .background(Theme.colors.card)
            .border(0.5.dp, Theme.colors.edge, Theme.shapes.grouped)
            .clickable(
                onClick = {
                    haptics?.play(HapticKind.Light)
                    onClick()
                },
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.foundation.LocalIndication.current,
            )
            .padding(horizontal = Space * 4 - 2.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space * 3),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = Theme.colors.primary,
        )
        Text(
            text,
            style = Theme.typography.label,
            color = Theme.colors.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.CaretRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = Theme.colors.onBackgroundMuted,
        )
    }
}
