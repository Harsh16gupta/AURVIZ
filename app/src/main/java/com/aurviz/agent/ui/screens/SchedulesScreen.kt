package com.aurviz.agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurviz.agent.ToolCall
import com.aurviz.agent.approvalCardFor
import com.aurviz.agent.knownAddressesFromInbox
import com.aurviz.agent.nextFireTime
import com.aurviz.agent.toolLabel
import com.aurviz.agent.ui.AgentUiState
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ConfirmationCard
import com.aurviz.agent.ui.components.EmptyState
import com.aurviz.agent.ui.components.HapticKind
import com.aurviz.agent.ui.components.Icon
import com.aurviz.agent.ui.components.IconButton
import com.aurviz.agent.ui.components.LocalHaptics
import com.aurviz.agent.ui.components.OptionToggle
import com.aurviz.agent.ui.components.Page
import com.aurviz.agent.ui.components.Section
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.components.TextField
import com.aurviz.agent.ui.formatScheduleCron
import com.aurviz.agent.ui.formatScheduleTime
import com.aurviz.agent.ui.theme.Bell
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Theme
import com.aurviz.agent.ui.theme.Trash
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

// Every Schedule is preview-gated: nothing is saved until the user taps
// Confirm on the preview, the same gate as an Email send.
@Composable
fun SchedulesScreen(
    state: AgentUiState,
    onPreviewSchedule: (String) -> Unit,
    onApproveSchedule: () -> Unit,
    onRejectSchedule: () -> Unit,
    onUnschedule: (String) -> Unit,
    onApproveHeldWrite: (String) -> Unit,
    onRejectHeldWrite: (String) -> Unit,
    onRefreshApprovals: () -> Unit = {},
    onToggleScheduleEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onRunScheduleNow: (String) -> Unit = {},
    onRefreshRuns: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val haptics = LocalHaptics.current

    // A Worker can save a new held row + notify while this screen is cached:
    // reload on every entry so it never says "nothing waiting" stale.
    LaunchedEffect(Unit) {
        onRefreshApprovals()
        onRefreshRuns()
    }

    Page(title = "Schedules", modifier = modifier) {
        Section(
            "New Schedule",
            footer = "Example: every morning at 9AM check emails",
        ) {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                label = "When and what — e.g. every morning at 9AM check emails",
            )
            Button(
                onClick = {
                    onPreviewSchedule(draft)
                    draft = ""
                },
            ) {
                Text("Preview Schedule")
            }
        }
        if (state.pendingSchedule != null) {
            ConfirmationCard(
                toolLabel = "create_schedule",
                title = "Save Schedule",
                detail = state.schedulePreview,
                approveLabel = "Save Schedule",
                denyLabel = "Discard",
                onApprove = onApproveSchedule,
                onDeny = onRejectSchedule,
            )
        }
        Section("Schedules") {
            if (state.schedules.isEmpty()) {
                EmptyState(
                    Icons.Bell,
                    title = "No Schedules yet",
                    message = "Describe one above — the Agent previews the time before saving.",
                )
            } else {
                state.schedules.forEach { schedule ->
                    val enabled = schedule.id !in state.disabledScheduleIds
                    val next = nextFireTime(schedule, ZoneId.systemDefault(), ZonedDateTime.now())
                    val nextText = next?.format(DateTimeFormatter.ofPattern("EEE MMM d HH:mm")) ?: "—"
                    var armDelete by remember(schedule.id) { mutableStateOf(false) }
                    LaunchedEffect(armDelete) {
                        if (armDelete) {
                            delay(3000L)
                            armDelete = false
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 84.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Theme.colors.card)
                            .border(0.5.dp, Theme.colors.edge, RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            schedule.instruction,
                            style = Theme.typography.body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                            color = Theme.colors.onBackground,
                        )
                        Text(
                            "${formatScheduleTime(schedule)} · Next $nextText",
                            style = Theme.typography.caption,
                            color = Theme.colors.onBackgroundMuted,
                        )
                        Text(
                            formatScheduleCron(schedule),
                            style = Theme.typography.code,
                            color = Theme.colors.onBackgroundMuted,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space * 2),
                        ) {
                            OptionToggle(
                                title = "Enabled",
                                checked = enabled,
                                onCheckedChange = {
                                    haptics?.play(HapticKind.Selection)
                                    onToggleScheduleEnabled(schedule.id, it)
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 44.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Theme.colors.onBackground.copy(alpha = 0.08f))
                                    .clickable {
                                        haptics?.play(HapticKind.Light)
                                        onRunScheduleNow(schedule.id)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Run Now",
                                    style = Theme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                                    color = Theme.colors.onBackground,
                                )
                            }
                            if (!armDelete) {
                                IconButton(
                                    onClick = { armDelete = true },
                                    contentDescription = "Stop Schedule ${schedule.id}",
                                ) {
                                    Icon(
                                        Icons.Trash,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = Theme.colors.destructive,
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .heightIn(min = 44.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(Theme.colors.destructive)
                                        .clickable {
                                            haptics?.play(HapticKind.Error)
                                            armDelete = false
                                            onUnschedule(schedule.id)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "Confirm?",
                                        style = Theme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                                        color = Theme.colors.onDestructive,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // Writes from unattended runs held for the user: approve runs the
        // held call exactly once, reject drops it untouched.
        Section("Approvals") {
            if (state.pendingApprovals.isEmpty()) {
                Text(
                    "Nothing waiting — blocked Schedule writes appear here.",
                    color = Theme.colors.onBackgroundMuted,
                    style = Theme.typography.caption,
                )
            } else {
                val known = knownAddressesFromInbox(state.emails)
                state.pendingApprovals.forEach { held ->
                    val card =
                        approvalCardFor(ToolCall(held.id, held.toolName, held.arguments), known)
                    val title: String
                    val detail: String
                    if (card != null) {
                        val to = card.to.joinToString { it.address }
                        title = "Send email to $to"
                        detail = "To: $to\nSubject: ${card.subject}\n${card.body}"
                    } else {
                        title = "${toolLabel(held.toolName)} (Schedule ${held.scheduleId})"
                        detail = held.arguments.entries.joinToString("\n") { (k, v) -> "$k: $v" }
                    }
                    ConfirmationCard(
                        toolLabel = held.toolName,
                        title = title,
                        detail = detail,
                        approveLabel = "Approve",
                        denyLabel = "Deny",
                        onApprove = { onApproveHeldWrite(held.id) },
                        onDeny = { onRejectHeldWrite(held.id) },
                    )
                    Text(
                        held.reason,
                        color = Theme.colors.onBackgroundMuted,
                        style = Theme.typography.caption,
                    )
                }
            }
        }
        Section("Recent Runs") {
            if (state.scheduleRuns.isEmpty()) {
                Text(
                    "No runs yet.",
                    color = Theme.colors.onBackgroundMuted,
                    style = Theme.typography.caption,
                )
            } else {
                state.scheduleRuns.take(10).forEach { run ->
                    val ok = run.status == "SUCCESS"
                    Column(verticalArrangement = Arrangement.spacedBy(Space)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space * 2),
                        ) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (ok) Theme.colors.success else Theme.colors.destructive)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    run.status,
                                    style = Theme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (ok) Theme.colors.onSuccess else Theme.colors.onDestructive,
                                )
                            }
                            Text(
                                run.scheduleId,
                                style = Theme.typography.caption,
                                color = Theme.colors.onBackgroundMuted,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${run.durationMs} ms",
                                style = Theme.typography.code,
                                color = Theme.colors.onBackgroundMuted,
                            )
                        }
                        if (!run.summary.isNullOrBlank()) {
                            Text(
                                run.summary,
                                style = Theme.typography.caption,
                                color = Theme.colors.onBackgroundMuted,
                            )
                        }
                    }
                }
            }
        }
        // Keep the command path visible: typed "schedules" lists here too.
        if (state.statusLine.isNotBlank()) {
            Text(
                state.statusLine,
                color = Theme.colors.onBackgroundMuted,
                style = Theme.typography.caption,
            )
        }
    }
}
