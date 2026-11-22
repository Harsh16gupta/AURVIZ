package com.aurviz.agent.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Theme

@Composable
fun Section(
    title: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space * 2)) {
        if (title != null) {
            Text(
                title,
                style = Theme.typography.caption,
                color = Theme.colors.onBackgroundMuted,
                modifier = Modifier.padding(horizontal = Space * 4).semantics { heading() },
            )
        }
        OptionGroup(content = content)
        if (footer != null) {
            Text(
                footer,
                style = Theme.typography.caption,
                color = Theme.colors.onBackgroundMuted,
                modifier = Modifier.padding(horizontal = Space * 4),
            )
        }
    }
}
