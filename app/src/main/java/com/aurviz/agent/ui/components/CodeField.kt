package com.aurviz.agent.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Theme

@Composable
fun CodeField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    length: Int = 6,
    error: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter(Char::isDigit).take(length)) },
        modifier =
            modifier.widthIn(max = 420.dp).fillMaxWidth().semantics {
                contentDescription = "Verification code"
            },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true,
        interactionSource = interactionSource,
        cursorBrush = SolidColor(Color.Transparent),
        textStyle = Theme.typography.title.copy(color = Color.Transparent),
        decorationBox = { invisibleField ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.height(1.dp)) { invisibleField() }
                DigitBoxes(
                    value,
                    length,
                    nextDigitHighlighted = focused,
                    error = error,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        },
    )
}

@Composable
private fun DigitBoxes(
    value: String,
    length: Int,
    nextDigitHighlighted: Boolean,
    error: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
        repeat(length) { index ->
            val isNextDigit = nextDigitHighlighted && index == value.length.coerceAtMost(length - 1)
            val outline by
                animateColorAsState(
                    when {
                        error -> Theme.colors.destructive
                        isNextDigit -> Theme.colors.primary
                        else -> Theme.colors.border
                    },
                    tween(140),
                    label = "Digit outline",
                )
            Box(
                Modifier.weight(1f)
                    .height(56.dp)
                    .clip(Theme.shapes.small)
                    .background(Theme.colors.surface)
                    .border(if (isNextDigit) 2.dp else 1.dp, outline, Theme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Text(value.getOrNull(index)?.toString() ?: "", style = Theme.typography.title)
            }
        }
    }
}
