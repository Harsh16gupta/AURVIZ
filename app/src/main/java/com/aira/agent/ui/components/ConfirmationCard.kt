package com.aira.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aira.agent.ui.theme.Check
import com.aira.agent.ui.theme.Copy
import com.aira.agent.ui.theme.Icons
import com.aira.agent.ui.theme.LockSimple
import com.aira.agent.ui.theme.Theme
import com.aira.agent.ui.theme.X
import com.aira.agent.toolLabel

enum class ConfirmationStatus { Pending, Approved, Denied }

@Composable
fun ConfirmationCard(
    toolLabel: String,
    title: String,
    detail: String?,
    status: ConfirmationStatus = ConfirmationStatus.Pending,
    warningText: String? = null,
    approveLabel: String = "Confirm",
    denyLabel: String = "Deny",
    settledSummary: String? = null,
    onApprove: () -> Unit = {},
    onDeny: () -> Unit = {},
    // Ticket #87: settled receipts. Copy duplicates the receipt detail;
    // Remember Preference stores a Preference grant (allow-always). Both
    // are hidden when null so other screens keep their current cards.
    onCopy: (() -> Unit)? = null,
    onRememberPreference: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    bodyContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val haptics = LocalHaptics.current
    var showDetails by remember { mutableStateOf(false) }

    if (status != ConfirmationStatus.Pending) {
        val settledIcon = if (status == ConfirmationStatus.Approved) Icons.Check else Icons.X
        val tint = if (status == ConfirmationStatus.Approved) Theme.colors.success else Theme.colors.onBackgroundMuted
        Column(
            modifier = modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Theme.colors.card)
                .border(0.5.dp, Theme.colors.edge, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(settledIcon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
                Text(
                    text = settledSummary ?: title,
                    style = Theme.typography.caption,
                    color = Theme.colors.onBackgroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!detail.isNullOrBlank()) {
                    Text(
                        text = if (showDetails) "Hide" else "Details",
                        style = Theme.typography.caption.copy(fontWeight = FontWeight.Medium),
                        color = Theme.colors.accentText,
                        modifier = Modifier.clickable { showDetails = !showDetails },
                    )
                }
            }
            if (showDetails && !detail.isNullOrBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Theme.colors.inset)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(detail, style = Theme.typography.code, color = Theme.colors.onBackgroundMuted)
                }
            }
            if (onCopy != null || onRememberPreference != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onCopy != null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Theme.colors.surfaceVariant)
                                .clickable {
                                    haptics?.play(HapticKind.Light)
                                    onCopy()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    Icons.Copy,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Theme.colors.onSurfaceVariant,
                                )
                                Text(
                                    text = "Copy",
                                    style = Theme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                                    color = Theme.colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (onRememberPreference != null) {
                        Box(
                            modifier = Modifier
                                .weight(1.6f)
                                .heightIn(min = 40.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Theme.colors.surfaceVariant)
                                .clickable {
                                    haptics?.play(HapticKind.Light)
                                    onRememberPreference()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Remember Preference",
                                style = Theme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                                color = Theme.colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        return
    }

    val borderColor = if (warningText != null) Theme.colors.warning.copy(alpha = 0.55f) else Theme.colors.edge

    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Theme.colors.card)
            .border(1.dp, borderColor, RoundedCornerShape(24.dp))
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.LockSimple, contentDescription = null, modifier = Modifier.size(15.dp), tint = Theme.colors.onBackgroundMuted)
            Text(
                text = "Confirmation · ${toolLabel(toolLabel)}",
                style = Theme.typography.code.copy(fontSize = 12.sp),
                color = Theme.colors.onBackgroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = title,
            style = Theme.typography.body.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            color = Theme.colors.onBackground,
        )

        if (!warningText.isNullOrBlank()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Theme.colors.warning.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = warningText,
                    style = Theme.typography.caption.copy(fontWeight = FontWeight.Medium),
                    color = Theme.colors.warning,
                )
            }
        }

        if (bodyContent != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bodyContent()
            }
        } else if (!detail.isNullOrBlank()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Theme.colors.inset)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = detail,
                    style = Theme.typography.code,
                    color = Theme.colors.onBackground,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.size(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Theme.colors.onBackground.copy(alpha = 0.08f))
                    .clickable {
                        haptics?.play(HapticKind.Error)
                        onDeny()
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = denyLabel,
                    style = Theme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                    color = Theme.colors.onBackground,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Theme.colors.primary)
                    .clickable {
                        haptics?.play(HapticKind.Success)
                        onApprove()
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = approveLabel,
                    style = Theme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                    color = Theme.colors.onPrimary,
                )
            }
        }
    }
}
