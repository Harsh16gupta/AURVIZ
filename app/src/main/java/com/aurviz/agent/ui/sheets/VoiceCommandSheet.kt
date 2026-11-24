package com.aurviz.agent.ui.sheets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.VOICE_LEVEL_MAX_DB
import com.aurviz.agent.ui.VOICE_LEVEL_MIN_DB
import com.aurviz.agent.ui.VoiceUiState
import com.aurviz.agent.ui.canConfirmVoice
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ButtonVariant
import com.aurviz.agent.ui.components.Icon
import com.aurviz.agent.ui.components.ModalBottomSheet
import com.aurviz.agent.ui.components.Section
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Check
import com.aurviz.agent.ui.theme.Microphone
import com.aurviz.agent.ui.theme.ProvideContentColor
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Stop
import com.aurviz.agent.ui.theme.Theme
import com.aurviz.agent.ui.theme.X

// Ticket #91: Todoist Ramble hero. A quoted transcript (muted interim vs
// crisp final, maxLines 4) over a 48dp live Canvas waveform (28 rounded
// bars from a 32-sample ring buffer driven by levelDb), with 56dp Pause +
// Confirm circles + Cancel. Confirm stays disabled on a blank transcript;
// errors render once with a Retry. GLOSSARY: Voice Command.
private const val WAVE_BARS = 28
private const val WAVE_SAMPLES = 32

@Composable
fun VoiceCommandSheet(
    visible: Boolean,
    state: VoiceUiState,
    onDismiss: () -> Unit,
    onMicTap: () -> Unit,
    onUseTranscript: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        visible = visible,
        onDismiss = onDismiss,
        title = "Voice Command",
        modifier = modifier,
    ) {
        // Ring buffer: each throttled RMS sample normalizes to 0..1 and
        // the oldest sample drops past 32. Left-padded with silence.
        val samples = remember { mutableStateListOf<Float>() }
        LaunchedEffect(state.levelDb) {
            val amp =
                ((state.levelDb - VOICE_LEVEL_MIN_DB) / (VOICE_LEVEL_MAX_DB - VOICE_LEVEL_MIN_DB))
                    .coerceIn(0f, 1f)
            samples.add(amp)
            while (samples.size > WAVE_SAMPLES) samples.removeAt(0)
        }
        Section("Listen") {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space * 3),
            ) {
                // Quoted transcript: interim reads muted, final reads crisp.
                val heard = state.transcript
                if (heard.isBlank()) {
                    Text(
                        "Tap the mic and speak — typing always works too.",
                        color = Theme.colors.onBackgroundMuted,
                    )
                } else {
                    Text(
                        "“$heard”",
                        color = if (state.isPartial) Theme.colors.onBackgroundMuted else Theme.colors.onSurface,
                        style = Theme.typography.title,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Waveform(
                    samples = samples.toList(),
                    active = state.listening,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
                if (state.error != null) {
                    Text(state.error, color = Theme.colors.destructive)
                    Button(
                        onClick = onMicTap,
                        variant = ButtonVariant.Secondary,
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space * 4)) {
                SheetCircle(
                    onClick = onMicTap,
                    description = if (state.listening) "Pause" else "Speak",
                    filled = false,
                    enabled = true,
                ) {
                    Icon(
                        if (state.listening) Icons.Stop else Icons.Microphone,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
                SheetCircle(
                    onClick = onUseTranscript,
                    description = "Use as command",
                    filled = true,
                    enabled = state.canConfirmVoice,
                ) {
                    Icon(
                        Icons.Check,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
                SheetCircle(
                    onClick = onDismiss,
                    description = "Cancel",
                    filled = false,
                    enabled = true,
                ) {
                    Icon(
                        Icons.X,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Waveform(samples: List<Float>, active: Boolean, modifier: Modifier = Modifier) {
    val barColor = Theme.colors.primary
    val idleColor = Theme.colors.surfaceVariant
    Canvas(modifier.semantics { contentDescription = "Voice level" }) {
        val n = WAVE_BARS
        val gapPx = 4.dp.toPx()
        val barW = (size.width - gapPx * (n - 1)) / n
        val minH = 3.dp.toPx()
        val padded = List(n - samples.takeLast(n).size) { 0f } + samples.takeLast(n)
        padded.forEachIndexed { i, amp ->
            val h = minH + amp * (size.height - minH)
            val left = i * (barW + gapPx)
            drawRoundRect(
                color = if (active && amp > 0f) barColor else idleColor,
                topLeft = Offset(left, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

@Composable
private fun SheetCircle(
    onClick: () -> Unit,
    description: String,
    filled: Boolean,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val (container, contentColor) =
        when {
            filled && !enabled -> Theme.colors.surfaceVariant to Theme.colors.onBackgroundMuted
            filled -> Theme.colors.primary to Theme.colors.onPrimary
            else -> Theme.colors.surfaceVariant to Theme.colors.onSurfaceVariant
        }
    Box(
        Modifier
            .size(56.dp)
            .alpha(if (enabled) 1f else 0.6f)
            .clip(Theme.shapes.fullyRounded)
            .background(container)
            .clickable(
                onClick = onClick,
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.foundation.LocalIndication.current,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        ProvideContentColor(contentColor, content)
    }
}
