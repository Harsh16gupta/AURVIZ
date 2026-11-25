package com.aira.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aira.agent.ui.theme.Icons
import com.aira.agent.ui.theme.Image
import com.aira.agent.ui.theme.Theme
import com.aira.agent.ui.theme.X

@Composable
fun AttachmentChip(
    imageUri: String,
    contentDescription: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Adapted: the Vela kit loads remote photos via compose-uri-painter.
    // AIRA has no photo attachments; render a placeholder tile instead.
    Box(modifier.padding(top = 6.dp, end = 6.dp)) {
        Box(
            Modifier.size(56.dp)
                .clip(Theme.shapes.small)
                .background(Theme.colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Image,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Theme.colors.onBackgroundMuted,
            )
        }
        IconButton(
            onClick = onRemove,
            contentDescription = "Remove $contentDescription",
            variant = IconButtonVariant.Tonal,
            size = IconButtonSize.Small,
            // Ticket #93: Small IconButton now carries a transparent 48dp hit box
            // around its 32dp visual circle; shift the offset by half the
            // difference (8dp) so the badge renders exactly where it did.
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 18.dp, y = (-18).dp).size(24.dp),
        ) {
            Icon(Icons.X, contentDescription = null, modifier = Modifier.size(12.dp))
        }
    }
}
