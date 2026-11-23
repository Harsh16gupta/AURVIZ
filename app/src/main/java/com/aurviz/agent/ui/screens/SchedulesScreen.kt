package com.aurviz.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ToolCall
import com.aurviz.agent.approvalCardFor
import com.aurviz.agent.knownAddressesFromInbox
import com.aurviz.agent.ui.AgentUiState
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ButtonVariant
import com.aurviz.agent.ui.components.EmptyState
import com.aurviz.agent.ui.components.Icon
import com.aurviz.agent.ui.components.IconButton
import com.aurviz.agent.ui.components.Page
import com.aurviz.agent.ui.components.Section
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.components.TextField
import com.aurviz.agent.ui.theme.Bell
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Theme
import com.aurviz.agent.ui.theme.Trash

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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }

    Page(title = "Schedules", onBack = onBack, modifier = modifier) {
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
            Section("Preview") {
                Text(state.schedulePreview)
                Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
                    Button(onClick = onApproveSchedule) {
                        Text("Confirm Schedule")
                    }
                    Button(onClick = onRejectSchedule, variant = ButtonVariant.Secondary) {
                        Text("Discard")
                    }
                }
            }
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
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Space * 4),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "• ${schedule.id}: ${schedule.instruction} at ${schedule.hour}:" +
                                "%02d".format(schedule.minute),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onUnschedule(schedule.id) },
                            contentDescription = "Stop Schedule ${schedule.id}",
                        ) {
                            Icon(
                                Icons.Trash,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Theme.colors.destructive,
                            )
                        }
                    }
                }
            }
        }
        // Ticket 15/28: writes a scheduled run held for the user (ADR-0002).
        // Send rows reuse the approval-card model (exact To/Subject/Body
        // plus unseen highlights); other writes show the exact held
        // arguments. Approve runs the held call exactly once.
        Section("Approvals") {
            if (state.pendingApprovals.isEmpty()) {
                Text(
                    "Nothing waiting — blocked routine writes appear here.",
                    color = Theme.colors.onBackgroundMuted,
                    style = Theme.typography.caption,
                )
            } else {
                val known = knownAddressesFromInbox(state.emails)
                state.pendingApprovals.forEach { held ->
                    val card = approvalCardFor(ToolCall(held.id, held.toolName, held.arguments), known)
                    Text("${held.toolName} (from routine ${held.scheduleId})")
                    if (card != null) {
                        Text("To: ${card.to.joinToString { it.address }}")
                        if (card.unseen.isNotEmpty()) {
                            Text(
                                "New recipient — never emailed before.",
                                color = Theme.colors.destructive,
                            )
                        }
                        Text("Subject: ${card.subject}")
                        Text(card.body)
                    } else {
                        held.arguments.forEach { (key, value) ->
                            Text("$key: $value")
                        }
                    }
                    Text(
                        held.reason,
                        color = Theme.colors.onBackgroundMuted,
                        style = Theme.typography.caption,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
                        Button(onClick = { onApproveHeldWrite(held.id) }) {
                            Text("Approve")
                        }
                        Button(onClick = { onRejectHeldWrite(held.id) }, variant = ButtonVariant.Secondary) {
                            Text("Reject")
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
