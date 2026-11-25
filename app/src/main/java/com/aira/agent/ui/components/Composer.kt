package com.aira.agent.ui.components

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aira.agent.ui.theme.ArrowUp
import com.aira.agent.ui.theme.Icons
import com.aira.agent.ui.theme.Microphone
import com.aira.agent.ui.theme.Plus
import com.aira.agent.ui.theme.Space
import com.aira.agent.ui.theme.Stop
import com.aira.agent.ui.theme.Theme
import com.aira.agent.ui.theme.Waveform

// Single-row capsule Composer pill: [+] | Ask AIRA field (expands
// vertically only when multi-line wraps) | mic icon | state-driven main
// action (Stop while running, Electric Blue ArrowUp send when non-blank,
// #1E78FF Voice Command pill when blank). The provider selector lives in
// Settings only — never in the Composer.
// GLOSSARY: Voice Command, Task Execution.
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
    placeholder: String = "Ask AIRA",
    model: String = "Auto",
    onModelClick: () -> Unit = {},
) {
    val haptics = LocalHaptics.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Theme.colors.composer)
            .border(1.dp, Theme.colors.composerRing, RoundedCornerShape(26.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
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
            maxLines = 4,
            decorationBox = { field ->
                Box(Modifier.padding(horizontal = Space, vertical = Space)) {
                    if (value.isEmpty()) Text(placeholder, color = Theme.colors.onBackgroundMuted)
                    field()
                }
            },
        )
        IconButton(
            onClick = onVoiceClick,
            contentDescription = "Mic",
            variant = IconButtonVariant.Ghost,
            size = IconButtonSize.Small,
        ) {
            Icon(Icons.Microphone, contentDescription = null, modifier = Modifier.size(18.dp))
        }
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
                            // Ticket #93: 48dp tap target, matching Send/Voice.
                            .size(48.dp)
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
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(Theme.shapes.fullyRounded)
                            .background(Color(0xFF1E78FF))
                            .clickable {
                                haptics?.play(HapticKind.Light)
                                onVoiceClick()
                            }
                            .semantics { contentDescription = "Voice Command" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Waveform,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = Color(0xFFFFFFFF),
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
