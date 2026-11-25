package com.aira.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.aira.agent.ui.theme.CheckCircle
import com.aira.agent.ui.theme.Icons
import com.aira.agent.ui.theme.Space
import com.aira.agent.ui.theme.Theme

@Composable
fun Toast(
    visible: Boolean,
    message: String,
    modifier: Modifier = Modifier,
    // Ticket #89: the Memory delete Undo toast passes Icons.Trash so the
    // affordance reads as restore; success toasts keep the check.
    icon: ImageVector = Icons.CheckCircle,
) {
    var lastMessage by remember { mutableStateOf(message) }
    if (visible && message.isNotEmpty()) lastMessage = message
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Row(
            Modifier.clip(Theme.shapes.fullyRounded)
                .background(Theme.colors.surfaceVariant)
                .border(1.dp, Theme.colors.border, Theme.shapes.fullyRounded)
                .padding(horizontal = Space * 4, vertical = Space * 2.5f)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space * 2),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Theme.colors.primary,
            )
            Text(lastMessage, style = Theme.typography.label, color = Theme.colors.onSurfaceVariant)
        }
    }
}
