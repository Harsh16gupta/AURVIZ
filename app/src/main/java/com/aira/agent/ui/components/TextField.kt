package com.aira.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.aira.agent.ui.theme.Space
import com.aira.agent.ui.theme.Theme

@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    BasicTextField(
        value = field,
        onValueChange = {
            field = it
            if (it.text != value) onValueChange(it.text)
        },
        modifier =
            modifier
                .fillMaxWidth()
                .clip(Theme.shapes.small)
                .background(Theme.colors.inset)
                .border(
                    if (focused) 1.dp else 0.5.dp,
                    if (focused) Theme.colors.primary else Theme.colors.edge,
                    Theme.shapes.small,
                )
                .semantics { contentDescription = label },
        textStyle = Theme.typography.body.copy(color = Theme.colors.onSurface),
        cursorBrush = SolidColor(Theme.colors.primary),
        singleLine = minLines == 1,
        minLines = minLines,
        interactionSource = interactionSource,
        decorationBox = { field ->
            Box(Modifier.padding(horizontal = Space * 4, vertical = Space * 3)) {
                if (value.isEmpty()) Text(label, color = Theme.colors.onBackgroundMuted)
                field()
            }
        },
    )
}
