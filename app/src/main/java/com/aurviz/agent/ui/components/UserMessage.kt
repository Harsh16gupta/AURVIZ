package com.aurviz.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.theme.Image
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Theme

@Composable
fun UserMessage(
    text: String,
    modifier: Modifier = Modifier,
    imageUris: List<String> = emptyList(),
) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(Space * 2),
    ) {
        if (imageUris.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
                imageUris.forEach { _ ->
                    // Adapted: placeholder tile (no remote image loader).
                    Box(
                        Modifier.size(96.dp)
                            .clip(Theme.shapes.medium)
                            .background(Theme.colors.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Image,
                            contentDescription = "Attached photo",
                            modifier = Modifier.size(32.dp),
                            tint = Theme.colors.onBackgroundMuted,
                        )
                    }
                }
            }
        }
        if (text.isNotEmpty()) {
            Text(
                text,
                color = Theme.colors.onSurface,
                modifier =
                    Modifier.widthIn(max = 300.dp)
                        .clip(Theme.shapes.medium)
                        .background(Theme.colors.surface)
                        .padding(horizontal = Space * 4, vertical = Space * 2.5f),
            )
        }
    }
}
