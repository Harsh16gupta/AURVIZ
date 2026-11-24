package com.aurviz.agent.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Theme

@Composable
fun ChoiceTile(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(Space * 3),
    content: @Composable ColumnScope.() -> Unit,
) {
    val outline by
        animateColorAsState(
            if (selected) Theme.colors.primary else Theme.colors.edge,
            tween(160),
            label = "Outline",
        )
    Column(
        modifier
            .clip(Theme.shapes.small)
            .background(if (selected) Theme.colors.surfaceVariant else Theme.colors.inset)
            .border(if (selected) 1.dp else 0.5.dp, outline, Theme.shapes.small)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .heightIn(min = 44.dp)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(Space),
        content = content,
    )
}
