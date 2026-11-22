package com.aurviz.agent.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Theme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DrawerItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    // Foundation's combinedClickable adds the long press that UnstyledButton doesn't offer.
    Box(
        modifier
            .fillMaxWidth()
            .clip(Theme.shapes.small)
            .background(if (selected) Theme.colors.surfaceVariant else Color.Transparent)
            .combinedClickable(
                role = Role.Button,
                onLongClickLabel = if (onLongClick != null) "Command options" else null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics { this.selected = selected }
            .padding(horizontal = Space * 3, vertical = Space * 2.5f),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space * 3),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                label,
                style = if (icon != null) Theme.typography.label else Theme.typography.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
