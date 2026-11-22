package com.aurviz.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.aurviz.agent.ui.theme.ProvideContentColor
import com.aurviz.agent.ui.theme.Theme

@Composable
fun OptionGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(Theme.shapes.medium).background(Theme.colors.surfaceVariant)
    ) {
        ProvideContentColor(Theme.colors.onSurfaceVariant) { content() }
    }
}
