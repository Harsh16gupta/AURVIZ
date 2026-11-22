package com.aurviz.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.theme.CaretRight
import com.aurviz.agent.ui.theme.Check
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.LocalContentColor
import com.aurviz.agent.ui.theme.ProvideContentColor
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Theme

@Composable
fun OptionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    badge: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    value: String? = null,
    navigates: Boolean = false,
) {
    Box(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(
                onClick = onClick,
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.foundation.LocalIndication.current,
            )
            .padding(PaddingValues(horizontal = Space * 4, vertical = Space * 3))
            .semantics { this.selected = selected },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (destructive)
            ProvideContentColor(Theme.colors.destructive) {
                OptionLayout(title, description, icon, badge) {}
            }
        else
            OptionLayout(title, description, icon, badge) {
                if (value != null)
                    Text(
                        value,
                        style = Theme.typography.caption,
                        color = Theme.colors.onBackgroundMuted,
                        maxLines = 1,
                    )
                if (selected)
                    Icon(
                        Icons.Check,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Theme.colors.primary,
                    )
                if (navigates)
                    Icon(
                        Icons.CaretRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Theme.colors.onBackgroundMuted,
                    )
            }
    }
}

@Composable
internal fun OptionLayout(
    title: String,
    description: String?,
    icon: ImageVector?,
    badge: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space * 3),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space * 2),
            ) {
                Text(title, style = Theme.typography.label)
                if (badge != null) {
                    Text(
                        badge,
                        style = Theme.typography.caption,
                        color = Theme.colors.onPrimary,
                        modifier =
                            Modifier.clip(Theme.shapes.fullyRounded)
                                .background(Theme.colors.primary)
                                .padding(horizontal = Space * 2, vertical = 1.dp),
                    )
                }
            }
            if (description != null)
                Text(
                    description,
                    style = Theme.typography.caption,
                    color = Theme.colors.onBackgroundMuted,
                )
        }
        trailing()
    }
}
