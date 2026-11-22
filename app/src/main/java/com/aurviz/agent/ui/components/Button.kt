package com.aurviz.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.aurviz.agent.ui.theme.ProvideContentColor
import com.aurviz.agent.ui.theme.ProvideTextStyle
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Theme

enum class ButtonVariant {
    Primary,
    Secondary,
    Destructive,
    Ghost,
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: ButtonVariant = ButtonVariant.Primary,
    content: @Composable () -> Unit,
) {
    val (container, contentColor) =
        when (variant) {
            ButtonVariant.Secondary -> Theme.colors.surfaceVariant to Theme.colors.onSurfaceVariant
            ButtonVariant.Destructive -> Theme.colors.destructive to Theme.colors.onDestructive
            ButtonVariant.Ghost -> Color.Transparent to Theme.colors.primary
            else -> Theme.colors.primary to Theme.colors.onPrimary
        }
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(Theme.shapes.fullyRounded)
            .background(container)
            .clickable(
                onClick = onClick,
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.foundation.LocalIndication.current,
            )
            .padding(PaddingValues(horizontal = Space * 5, vertical = Space * 3)),
    ) {
        ProvideContentColor(contentColor) { ProvideTextStyle(Theme.typography.label, content) }
    }
}
