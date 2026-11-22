package com.aurviz.agent.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.theme.Theme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class VoiceState {
    Listening,
    Hearing,
    Thinking,
    Speaking,
    Muted,
}

@Composable
fun VoiceOrb(state: VoiceState, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "Voice")
    val phase by
        transition.animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(2400, easing = LinearEasing)),
            label = "Phase",
        )
    val quick by
        transition.animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(520), RepeatMode.Reverse),
            label = "Quick",
        )
    val swell by
        animateFloatAsState(
            when (state) {
                VoiceState.Listening -> 0.06f
                VoiceState.Hearing -> 0.16f
                VoiceState.Thinking -> 0.04f
                VoiceState.Speaking -> 0.12f
                VoiceState.Muted -> 0f
            },
            spring(stiffness = 120f),
            label = "Swell",
        )
    val core by
        animateColorAsState(
            when (state) {
                VoiceState.Muted -> Theme.colors.surfaceVariant
                VoiceState.Speaking -> Theme.colors.primary
                else -> Theme.colors.primary.copy(alpha = 0.85f)
            },
            tween(300),
            label = "Core",
        )
    Canvas(
        modifier
            .sizeIn(maxWidth = 220.dp, maxHeight = 220.dp)
            .aspectRatio(1f)
            .clearAndSetSemantics {}
    ) {
        val breath = sin(phase * 2 * PI).toFloat()
        val pulse =
            if (state == VoiceState.Hearing || state == VoiceState.Speaking) quick
            else (breath + 1f) / 2f
        val radius = size.minDimension / 2 * (0.62f + swell * pulse)
        val turn = phase * 2 * PI
        val brightestPoint =
            if (state == VoiceState.Thinking)
                Offset(
                    center.x + radius * 0.35f * cos(turn).toFloat(),
                    center.y + radius * 0.35f * sin(turn).toFloat(),
                )
            else center
        drawCircle(
            brush =
                Brush.radialGradient(
                    listOf(core.copy(alpha = 0.28f), Color.Transparent),
                    center = center,
                    radius = radius * 1.6f,
                ),
            radius = radius * 1.6f,
        )
        drawCircle(
            brush =
                Brush.radialGradient(
                    listOf(core, core.copy(alpha = 0.55f)),
                    center = brightestPoint,
                    radius = radius,
                ),
            radius = radius,
        )
    }
}
