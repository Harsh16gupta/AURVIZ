package com.aurviz.agent.ui.sheets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.VOICE_LEVEL_MAX_DB
import com.aurviz.agent.ui.VOICE_LEVEL_MIN_DB
import com.aurviz.agent.ui.VoiceUiState
import com.aurviz.agent.ui.canConfirmVoice
import com.aurviz.agent.ui.components.Icon
import com.aurviz.agent.ui.components.ModalBottomSheet
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Check
import com.aurviz.agent.ui.theme.Microphone
import com.aurviz.agent.ui.theme.Stop
import com.aurviz.agent.ui.theme.Theme

// Ticket #91: Todoist Ramble hero. A status caption over the quoted
// transcript (muted interim vs crisp final, maxLines 4, centered) over a
// 48dp live Canvas waveform (28 rounded bars from a 32-sample ring buffer
// driven by levelDb), with a 60dp primary mic/stop circle + a 52dp
// secondary confirm circle. Confirm stays disabled on a blank transcript.
// The sheet header's single top-left X is the only dismiss affordance.
// GLOSSARY: Voice Command.
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
        val statusCaption =
            when {
                state.listening -> "Listening..."
                state.transcript.isNotBlank() -> "Paused"
                else -> "Ready to listen"
            }
        val heard = state.transcript
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                statusCaption,
                style = Theme.typography.caption.copy(textAlign = TextAlign.Center),
                color = Theme.colors.onBackgroundMuted,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            if (heard.isBlank()) {
                Text(
                    "Tap the mic and speak — typing always works too.",
                    style = Theme.typography.title.copy(textAlign = TextAlign.Center),
                    color = Theme.colors.onBackgroundMuted,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            } else {
                Text(
                    "“$heard”",
                    style = Theme.typography.title.copy(textAlign = TextAlign.Center),
                    color = if (state.isPartial) Theme.colors.onBackgroundMuted else Theme.colors.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Waveform(
                samples = samples.toList(),
                active = state.listening,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
            if (state.error != null) {
                Text(
                    state.error,
                    style = Theme.typography.caption.copy(textAlign = TextAlign.Center),
                    color = Theme.colors.destructive,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(60.dp)
                        .clip(Theme.shapes.fullyRounded)
                        .background(Theme.colors.primary)
                        .clickable(
                            onClick = onMicTap,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = androidx.compose.foundation.LocalIndication.current,
                        )
                        .semantics {
                            contentDescription = if (state.listening) "Stop" else "Speak"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.listening) Icons.Stop else Icons.Microphone,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = Theme.colors.onPrimary,
                    )
                }
                val canConfirm = state.canConfirmVoice
                Box(
                    Modifier
                        .size(52.dp)
                        .alpha(if (canConfirm) 1f else 0.35f)
                        .clip(Theme.shapes.fullyRounded)
                        .background(Theme.colors.surfaceVariant)
                        .border(1.dp, Theme.colors.edge, Theme.shapes.fullyRounded)
                        .clickable(
                            onClick = onUseTranscript,
                            enabled = canConfirm,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = androidx.compose.foundation.LocalIndication.current,
                        )
                        .semantics { contentDescription = "Use as command" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Check,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (canConfirm) Theme.colors.onSurface else Theme.colors.onBackgroundMuted,
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
