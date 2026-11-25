package com.aira.agent.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.aira.agent.ui.theme.Theme

// Ticket #89: standalone enable Switch (foundation only, no Material).
// The schedule card header puts this top-right; the 48dp box keeps the
// target reachable while the 48x28 track matches the OptionToggle rhythm.
@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
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
            .size(width = 56.dp, height = 48.dp)
            .clickable(
                onClick = {
                    haptics?.play(HapticKind.Selection)
                    onCheckedChange(!checked)
                },
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.foundation.LocalIndication.current,
            )
            .semantics {
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                this.contentDescription = contentDescription
            }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier.size(width = 48.dp, height = 28.dp)
                .clip(Theme.shapes.fullyRounded)
                .background(track)
                .padding(4.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier.size(20.dp)
                    .clip(Theme.shapes.fullyRounded)
                    .background(thumb),
            )
        }
    }
}
