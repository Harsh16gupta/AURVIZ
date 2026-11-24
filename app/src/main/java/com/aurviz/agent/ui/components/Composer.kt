package com.aurviz.agent.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.theme.ArrowUp
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Plus
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Stop
import com.aurviz.agent.ui.theme.Theme
import com.aurviz.agent.ui.theme.Waveform

// Single-row Composer pill (48–56dp): [+] | Ask Aurviz field | one
// state-driven right action. Waveform opens the Voice Command sheet when
// blank, Electric Blue ArrowUp sends when non-blank, Stop while running.
// No duplicate ModelPill here — the provider lives in the Command header.
// No dual mic + waveform: one action, three states.
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    responding: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAddClick: () -> Unit,
    onVoiceClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Ask Aurviz",
) {
    val haptics = LocalHaptics.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp, max = 56.dp)
            .clip(RoundedCornerShape(50))
            .background(Theme.colors.composer)
            .border(1.dp, Theme.colors.composerRing, RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space),
    ) {
        IconButton(
            onClick = onAddClick,
            contentDescription = "Add to command",
            variant = IconButtonVariant.Tonal,
            size = IconButtonSize.Small,
        ) {
            Icon(Icons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).semantics { contentDescription = placeholder },
            textStyle = Theme.typography.body.copy(color = Theme.colors.onSurface),
            cursorBrush = SolidColor(Theme.colors.primary),
            maxLines = 3,
            decorationBox = { field ->
                Box(Modifier.padding(horizontal = Space, vertical = Space)) {
                    if (value.isEmpty()) Text(placeholder, color = Theme.colors.onBackgroundMuted)
                    field()
                }
            },
        )
        AnimatedContent(
            targetState =
                when {
                    responding -> MainAction.Stop
                    canSend -> MainAction.Send
                    else -> MainAction.Voice
                },
            transitionSpec = {
                (fadeIn() + scaleIn(initialScale = 0.8f)) togetherWith
                    (fadeOut() + scaleOut(targetScale = 0.8f))
            },
            label = "Send, stop or voice",
        ) { action ->
            when (action) {
                MainAction.Stop ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(Theme.shapes.fullyRounded)
                            .background(Theme.colors.primary)
                            .clickable {
                                haptics?.play(HapticKind.Medium)
                                onStop()
                            }
                            .semantics { contentDescription = "Stop response" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = Theme.colors.onPrimary,
                        )
                    }
                MainAction.Send ->
                    IconButton(
                        onClick = {
                            haptics?.play(HapticKind.Light)
                            onSend()
                        },
                        contentDescription = "Send",
                        variant = IconButtonVariant.Filled,
                    ) {
                        Icon(
                            Icons.ArrowUp,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                MainAction.Voice ->
                    IconButton(
                        onClick = onVoiceClick,
                        contentDescription = "Voice Command",
                        variant = IconButtonVariant.Filled,
                    ) {
                        Icon(
                            Icons.Waveform,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
            }
        }
    }
}

private enum class MainAction {
    Send,
    Stop,
    Voice,
}
