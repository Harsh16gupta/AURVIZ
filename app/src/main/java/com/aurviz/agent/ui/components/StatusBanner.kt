package com.aurviz.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Theme

// Shared status-banner primitive: one 56dp error row with an optional
// single action (Retry). Replaces duplicated ad-hoc error rows so failures
// never render twice on a screen.
@Composable
fun StatusBanner(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Theme.colors.destructive.copy(alpha = 0.12f))
            .border(
                0.5.dp,
                Theme.colors.destructive.copy(alpha = 0.4f),
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = Space * 4, vertical = Space * 3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space * 2),
    ) {
        Text(
            text = message,
            style = Theme.typography.caption.copy(fontWeight = FontWeight.Medium),
            color = Theme.colors.destructive,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null) {
            val haptics = LocalHaptics.current
            Text(
                text = actionLabel,
                style = Theme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                color = Theme.colors.accentText,
                modifier = Modifier.clickable {
                    haptics?.play(HapticKind.Light)
                    onAction()
                },
            )
        }
    }
}
