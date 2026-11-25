package com.aira.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aira.agent.ui.theme.ProvideContentColor
import com.aira.agent.ui.theme.Theme

@Composable
fun CardDivider(modifier: Modifier = Modifier, inset: Dp = 16.dp) {
    Box(modifier.fillMaxWidth().padding(horizontal = inset)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.colors.edge))
    }
}

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
