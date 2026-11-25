package com.aira.agent.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.aira.agent.ui.theme.Space
import com.aira.agent.ui.theme.Theme

@Composable
fun OptionToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
) {
    val track by
        animateColorAsState(
            if (checked) Theme.colors.primary else Theme.colors.border,
            tween(180),
            label = "Track",
        )
    val thumb by
        animateColorAsState(
            if (checked) Theme.colors.onPrimary else Theme.colors.onBackgroundMuted,
            tween(180),
            label = "Thumb",
        )
    Box(
        modifier
            .fillMaxWidth()
            .clickable(
                onClick = { onCheckedChange(!checked) },
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.foundation.LocalIndication.current,
            )
            .semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
            .padding(horizontal = Space * 4, vertical = Space * 3),
    ) {
        OptionLayout(
            title,
            description,
            icon,
            badge = null,
        ) {
            Box(
                Modifier.size(width = 44.dp, height = 26.dp)
                    .clip(Theme.shapes.fullyRounded)
                    .background(track)
                    .padding(4.dp),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(
                    Modifier.size(18.dp)
                        .clip(Theme.shapes.fullyRounded)
                        .background(thumb),
                )
            }
        }
    }
}
