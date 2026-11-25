package com.aira.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aira.agent.ui.theme.CaretRight
import com.aira.agent.ui.theme.Check
import com.aira.agent.ui.theme.Icons
import com.aira.agent.ui.theme.LocalContentColor
import com.aira.agent.ui.theme.ProvideContentColor
import com.aira.agent.ui.theme.Theme

@Composable
fun OptionRow(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    description: String? = null,
    icon: ImageVector? = null,
    badge: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    value: String? = null,
    valueColor: Color = Theme.colors.onBackgroundMuted,
    navigates: Boolean = false,
) {
    val clickableMod =
        if (onClick != null && enabled) {
            Modifier.clickable(
                onClick = onClick,
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.foundation.LocalIndication.current,
            )
        } else {
            Modifier
        }
    Box(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .then(clickableMod)
            .heightIn(min = 52.dp)
            .padding(PaddingValues(horizontal = 16.dp, vertical = 10.dp))
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
                        color = valueColor,
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
fun OptionLayout(
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
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                                .padding(horizontal = 10.dp, vertical = 1.dp),
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
