package com.aurviz.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.theme.Theme

@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(32.dp)
            .clip(Theme.shapes.fullyRounded)
            .background(Theme.colors.primary)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1).uppercase(),
            style = Theme.typography.label,
            color = Theme.colors.onPrimary,
        )
    }
}
