package com.aurviz.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.theme.ArrowDown
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Theme

@Composable
fun ScrollToLatestButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible,
        modifier,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
    ) {
        IconButton(
            onClick = onClick,
            contentDescription = "Scroll to latest",
            variant = IconButtonVariant.Tonal,
            modifier = Modifier.border(1.dp, Theme.colors.border, Theme.shapes.fullyRounded),
        ) {
            Icon(Icons.ArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
