package com.aurviz.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.theme.LocalContentColor
import com.aurviz.agent.ui.theme.ProvideContentColor
import com.aurviz.agent.ui.theme.Theme

enum class IconButtonVariant {
    Ghost,
    Filled,
    Tonal,
}

enum class IconButtonSize {
    Small,
    Medium,
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: IconButtonVariant = IconButtonVariant.Ghost,
    size: IconButtonSize = IconButtonSize.Medium,
    content: @Composable () -> Unit,
) {
    val (container, contentColor) =
        when {
            variant == IconButtonVariant.Filled && !enabled ->
                Theme.colors.surfaceVariant to Theme.colors.onBackgroundMuted
            variant == IconButtonVariant.Filled -> Theme.colors.primary to Theme.colors.onPrimary
            variant == IconButtonVariant.Tonal ->
                Theme.colors.surfaceVariant to Theme.colors.onSurfaceVariant
            else -> Color.Transparent to LocalContentColor.current
        }
    // Ticket #93: every tap target is >= 48dp in both themes. Medium renders
    // a full 48dp circle. Small keeps its 32dp visual circle but carries a
    // transparent 48dp hit box outside it, so dense inline affordances
    // (message actions, search clear, chip dismiss, sheet close) keep their
    // exact look while meeting the tap-target bar. No Material dependency.
    if (size == IconButtonSize.Small) {
        Box(
            modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .alpha(if (enabled) 1f else 0.4f)
                .clickable(
                    onClick = onClick,
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = androidx.compose.foundation.LocalIndication.current,
                )
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(Theme.shapes.fullyRounded)
                    .background(container),
                contentAlignment = Alignment.Center,
            ) {
                ProvideContentColor(contentColor, content)
            }
        }
        return
    }
    Box(
        modifier
            .alpha(if (enabled || variant == IconButtonVariant.Filled) 1f else 0.4f)
            .size(48.dp)
            .clip(Theme.shapes.fullyRounded)
            .background(container)
            .clickable(
                onClick = onClick,
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.foundation.LocalIndication.current,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        ProvideContentColor(contentColor, content)
    }
}
