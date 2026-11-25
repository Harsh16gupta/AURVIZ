package com.aira.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.aira.agent.ui.theme.CaretDown
import com.aira.agent.ui.theme.CaretRight
import com.aira.agent.ui.theme.Icons
import com.aira.agent.ui.theme.Space
import com.aira.agent.ui.theme.Theme
import com.aira.agent.ui.theme.VelaMark

@Composable
fun ThinkingIndicator(
    thinking: Boolean,
    seconds: Int,
    thoughts: String,
    modifier: Modifier = Modifier,
) {
    if (thinking) {
        val transition = rememberInfiniteTransition(label = "Thinking")
        val rotation by
            transition.animateFloat(
                0f,
                360f,
                infiniteRepeatable(tween(2400, easing = LinearEasing)),
                label = "Star",
            )
        val pulse by
            transition.animateFloat(
                0f,
                1f,
                infiniteRepeatable(tween(800), RepeatMode.Reverse),
                label = "Label",
            )
        Row(
            modifier.padding(Space),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space * 2),
        ) {
            Icon(
                Icons.VelaMark,
                contentDescription = null,
                modifier = Modifier.size(18.dp).rotate(rotation),
                tint = Theme.colors.primary,
            )
            Text(
                "Thinking",
                color = lerp(Theme.colors.onBackgroundMuted, Theme.colors.onBackground, pulse),
            )
        }
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Box(
            Modifier.clip(Theme.shapes.small)
                .clickable(
                    onClick = { expanded = !expanded },
                    interactionSource = remember { MutableInteractionSource() },
                    indication = androidx.compose.foundation.LocalIndication.current,
                )
                .padding(PaddingValues(vertical = Space, horizontal = Space)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space),
            ) {
                Text(
                    "Thought for $seconds ${if (seconds == 1) "second" else "seconds"}",
                    color = Theme.colors.onBackgroundMuted,
                )
                Icon(
                    if (expanded) Icons.CaretDown else Icons.CaretRight,
                    contentDescription = if (expanded) "Hide thoughts" else "Show thoughts",
                    modifier = Modifier.size(14.dp),
                    tint = Theme.colors.onBackgroundMuted,
                )
            }
        }
        AnimatedVisibility(
            expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Row(Modifier.padding(start = Space, top = Space * 2).height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(Theme.colors.border))
                Text(
                    thoughts,
                    color = Theme.colors.onBackgroundMuted,
                    style =
                        Theme.typography.caption.copy(
                            lineHeight = Theme.typography.body.lineHeight * 0.75f
                        ),
                    modifier = Modifier.padding(start = Space * 3),
                )
            }
        }
    }
}
