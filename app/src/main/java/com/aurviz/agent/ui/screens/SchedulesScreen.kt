package com.aurviz.agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurviz.agent.ToolCall
import com.aurviz.agent.approvalCardFor
import com.aurviz.agent.formatUnschedulePreview
import com.aurviz.agent.knownAddressesFromInbox
import com.aurviz.agent.nextFireTime
import com.aurviz.agent.toolLabel
import com.aurviz.agent.ui.AgentUiState
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ButtonVariant
import com.aurviz.agent.ui.components.ConfirmationCard
import com.aurviz.agent.ui.components.EmptyState
import com.aurviz.agent.ui.components.FeedCard
import com.aurviz.agent.ui.components.HapticKind
import com.aurviz.agent.ui.components.Icon
import com.aurviz.agent.ui.components.IconButton
import com.aurviz.agent.ui.components.LocalHaptics
import com.aurviz.agent.ui.components.Page
import com.aurviz.agent.ui.components.Section
import com.aurviz.agent.ui.components.Switch
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.components.TextField
import com.aurviz.agent.ui.firesIn
import com.aurviz.agent.ui.formatRunDuration
import com.aurviz.agent.ui.formatScheduleTime
import com.aurviz.agent.ui.theme.Bell
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Sparkle
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
    onApproveUnschedule: () -> Unit,
    onRejectUnschedule: () -> Unit,
    onUnschedule: (String) -> Unit,
    onApproveHeldWrite: (String) -> Unit,
    onRejectHeldWrite: (String) -> Unit,
    onRefreshApprovals: () -> Unit = {},
    onToggleScheduleEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onRunScheduleNow: (String) -> Unit = {},
    onRefreshRuns: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    // Inline delete row target (Delete Schedule? Delete/Keep) — explicit
    // row, never a morphing pill.
    var confirmDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    // Live countdown: re-resolve "Fires in …" every 30s.
    var nowTick by remember { mutableStateOf(ZonedDateTime.now()) }
    val haptics = LocalHaptics.current

    // A Worker can save a new held row + notify while this screen is cached:
    // reload on every entry so it never says "nothing waiting" stale.
    LaunchedEffect(Unit) {
        onRefreshApprovals()
        onRefreshRuns()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowTick = ZonedDateTime.now()
        }
    }

    Page(title = "Schedules", modifier = modifier, onBack = onBack) {
        Section("New Schedule") {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = "When and what — e.g. every morning at 9AM check emails",
                )
                MorningBriefingChip(
                    onPick = {
                        draft = MorningBriefingText
                        onPreviewSchedule(MorningBriefingText)
                    },
                )
                Button(
                    onClick = { onPreviewSchedule(draft) },
                    modifier = Modifier.height(44.dp),
                ) {
                    Text("Preview Schedule")
                }
            }
        }
        if (state.pendingUnschedule != null) {
            ConfirmationCard(
                toolLabel = "cancel_schedule",
                title = "Stop Schedule",
                detail = formatUnschedulePreview(state.pendingUnschedule),
                approveLabel = "Stop schedule",
                denyLabel = "Keep",
                onApprove = onApproveUnschedule,
                onDeny = onRejectUnschedule,
            )
        }
        if (state.pendingSchedule != null) {
            ConfirmationCard(
                toolLabel = "create_schedule",
                title = "Review Schedule",
                detail = state.schedulePreview,
                approveLabel = "Create schedule",
                denyLabel = "Discard",
                onApprove = {
                    onApproveSchedule()
                    // Draft clears only on save success, never on preview.
                    draft = ""
                },
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
                    val next = nextFireTime(schedule, ZoneId.systemDefault(), nowTick)
                    val nextText = next?.format(DateTimeFormatter.ofPattern("EEE MMM d HH:mm")) ?: "—"
                    FeedCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space * 2),
                        ) {
                            Text(
                                schedule.instruction,
                                style = Theme.typography.body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                                color = Theme.colors.onBackground,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = enabled,
                                onCheckedChange = {
                                    haptics?.play(HapticKind.Selection)
                                    onToggleScheduleEnabled(schedule.id, it)
                                },
                                contentDescription = if (enabled) "Disable Schedule" else "Enable Schedule",
                            )
                        }
                        Text(
                            "${formatScheduleTime(schedule)} · Next $nextText",
                            style = Theme.typography.caption,
                            color = Theme.colors.onBackgroundMuted,
                        )
                        Text(
                            firesIn(next, nowTick),
                            style = Theme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = Theme.colors.accentText,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space * 2),
                        ) {
                            // Outline Run now: bordered pill, never a filled CTA.
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(50))
                                    .border(1.dp, Theme.colors.border, RoundedCornerShape(50))
                                    .clickable {
                                        haptics?.play(HapticKind.Light)
                                        onRunScheduleNow(schedule.id)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Run now",
                                    style = Theme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                                    color = Theme.colors.onBackground,
                                )
                            }
                            IconButton(
                                onClick = { confirmDeleteId = schedule.id },
                                contentDescription = "Delete Schedule ${schedule.id}",
                            ) {
                                Icon(
                                    Icons.Trash,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = Theme.colors.destructive,
                                )
                            }
                        }
                        if (confirmDeleteId == schedule.id) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space * 2),
                            ) {
                                Text(
                                    "Delete Schedule?",
                                    style = Theme.typography.body.copy(fontWeight = FontWeight.SemiBold),
                                    color = Theme.colors.onBackground,
                                    modifier = Modifier.weight(1f),
                                )
                                Button(
                                    onClick = {
                                        haptics?.play(HapticKind.Error)
                                        confirmDeleteId = null
                                        onUnschedule(schedule.id)
                                    },
                                    variant = ButtonVariant.Destructive,
                                ) {
                                    Text("Delete")
                                }
                                Button(
                                    onClick = { confirmDeleteId = null },
                                    variant = ButtonVariant.Secondary,
                                ) {
                                    Text("Keep")
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                )
            } else {
                state.scheduleRuns.take(10).forEach { run ->
                    val ok = run.status == "SUCCESS"
                    FeedCard(modifier = Modifier.heightIn(min = 64.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space * 3),
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (ok) Theme.colors.success else Theme.colors.destructive),
                            )
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(Space),
                            ) {
                                Text(
                                    if (ok) "Task Execution completed" else "Task Execution failed",
                                    style = Theme.typography.body.copy(fontWeight = FontWeight.Medium),
                                    color = Theme.colors.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!run.summary.isNullOrBlank()) {
                                    Text(
                                        run.summary,
                                        style = Theme.typography.caption,
                                        color = Theme.colors.onBackgroundMuted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Text(
                                formatRunDuration(run.durationMs),
                                style = Theme.typography.caption,
                                color = Theme.colors.onBackgroundMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val MorningBriefingText = "every morning at 9AM summarize unread emails"

// 2-tap preset: first tap fills the draft and opens the unchanged
// ConfirmationCard gate; second tap creates the Schedule.
@Composable
private fun MorningBriefingChip(onPick: () -> Unit) {
    val haptics = LocalHaptics.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .border(1.dp, Theme.colors.border, RoundedCornerShape(50))
            .clickable(
                onClick = {
                    haptics?.play(HapticKind.Light)
                    onPick()
                },
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.foundation.LocalIndication.current,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space * 2),
        ) {
            Icon(
                Icons.Sparkle,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Theme.colors.accentText,
            )
            Text(
                "Morning Briefing · Daily at 09:00",
                style = Theme.typography.label.copy(fontWeight = FontWeight.Medium),
                color = Theme.colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
