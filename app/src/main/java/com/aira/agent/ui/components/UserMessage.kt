package com.aira.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aira.agent.ui.theme.Image
import com.aira.agent.ui.theme.Icons
import com.aira.agent.ui.theme.Space
import com.aira.agent.ui.theme.Theme

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
                style = Theme.typography.body.copy(fontSize = 16.sp, lineHeight = 24.sp),
                color = Theme.colors.bubbleUserInk,
                modifier =
                    Modifier.widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Theme.colors.bubbleUser)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
