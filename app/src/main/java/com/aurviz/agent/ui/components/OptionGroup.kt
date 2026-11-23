package com.aurviz.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.theme.ProvideContentColor
import com.aurviz.agent.ui.theme.Theme

@Composable
fun OptionGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Theme.colors.card)
            .border(0.5.dp, Theme.colors.edge, RoundedCornerShape(16.dp))
    ) {
        ProvideContentColor(Theme.colors.onSurface) { content() }
    }
}
