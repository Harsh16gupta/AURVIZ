package com.aurviz.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.MagnifyingGlass
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Theme
import com.aurviz.agent.ui.theme.X

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(Theme.shapes.fullyRounded)
            .background(Theme.colors.surfaceVariant)
            .padding(start = Space * 4, end = Space),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space * 2),
    ) {
        Icon(
            Icons.MagnifyingGlass,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = Theme.colors.onBackgroundMuted,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).semantics { contentDescription = placeholder },
            textStyle = Theme.typography.body.copy(color = Theme.colors.onSurfaceVariant),
            cursorBrush = SolidColor(Theme.colors.primary),
            singleLine = true,
            decorationBox = { field ->
                Box(Modifier.padding(vertical = Space * 2.5f)) {
                    if (value.isEmpty()) Text(placeholder, color = Theme.colors.onBackgroundMuted)
                    field()
                }
            },
        )
        if (value.isNotEmpty()) {
            IconButton(
                onClick = { onValueChange("") },
                contentDescription = "Clear search",
                size = IconButtonSize.Small,
            ) {
                Icon(
                    Icons.X,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Theme.colors.onBackgroundMuted,
                )
            }
        }
    }
}
