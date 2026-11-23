package com.aurviz.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aurviz.agent.StepState
import com.aurviz.agent.TimelineStep
import com.aurviz.agent.ToolCall
import com.aurviz.agent.ui.AgentUiState
import com.aurviz.agent.ui.retryLine
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ButtonVariant
import com.aurviz.agent.ui.components.Composer
import com.aurviz.agent.ui.components.Markdown
import com.aurviz.agent.ui.components.ScreenScaffold
import com.aurviz.agent.ui.components.Section
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.components.ThinkingIndicator
import com.aurviz.agent.ui.theme.Theme

// The Agent's home: one Interaction at a time, its Plan as Task Execution
// rows, and the Composer. Sensitive Tools never run here directly — an
// Email send or a forgotten Memory opens a Confirmation sheet first.
@Composable
fun CommandScreen(
    state: AgentUiState,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onRetry: () -> Unit,
    onMicTap: () -> Unit,
    onVoiceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    onApprovePending: () -> Unit = {},
    onRejectPending: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(modifier) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space * 4),
                verticalArrangement = Arrangement.spacedBy(Space * 2),
            ) {
                item {
                    Text(
                        "Command",
                        style = Theme.typography.display,
                        modifier = Modifier.padding(top = Space * 4, bottom = Space),
                    )
                }
                item {
                    Text(state.statusLine, color = Theme.colors.onBackgroundMuted)
                }
                state.retryLine()?.let { retry ->
                    item {
                        Text(retry, color = Theme.colors.primary)
                    }
                }
                if (state.timeline.any { it.state == StepState.RUNNING }) {
                    item {
                        ThinkingIndicator(
                            thinking = true,
                            seconds = 0,
                            thoughts = "",
                        )
                    }
                }
                items(state.timeline, key = { it.title }) { step ->
                    TimelineRow(step)
                }
                // Ticket 12/25: the Confirmation gate's card. Shows the
                // exact send the model asked for plus the unseen-recipient
                // highlight; Approve runs it once through the gated path,
                // Reject discards it with no send.
                state.pendingApproval?.let { call ->
                    item {
                        ApprovalCardRow(
                            call = call,
                            unseen = state.pendingApprovalUnseen,
                            onApprove = onApprovePending,
                            onReject = onRejectPending,
                        )
                    }
                }
                if (state.timeline.any { it.state == StepState.FAILED }) {
                    item {
                        Button(onClick = onRetry, variant = ButtonVariant.Secondary) {
                            Text("Retry failed Tool")
                        }
                    }
                }
                if (state.webPage != null) {
                    item {
                        Button(onClick = onOpenBrowser, variant = ButtonVariant.Secondary) {
                            Text("View page in Browser")
                        }
                    }
                }
                if (state.voice.transcript.isNotBlank()) {
                    item {
                        Text(
                            "Heard: ${state.voice.transcript}",
                            color = Theme.colors.primary,
                        )
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = Space * 4, vertical = Space * 3),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Composer(
                    value = state.inputText,
                    onValueChange = onInputChange,
                    model = state.providerUsed ?: "Aurviz",
                    responding = state.sending,
                    canSend = state.inputText.isNotBlank(),
                    onSend = { onSend(state.inputText) },
                    onStop = {},
                    onAddClick = {},
                    onModelClick = {},
                    onSpeakClick = onMicTap,
                    onVoiceClick = onVoiceClick,
                    placeholder = "Type a command (always works)",
                )
            }
        }
    }
}

@Composable
private fun TimelineRow(step: TimelineStep) {
    val (mark, color) = when (step.state) {
        StepState.DONE -> "✓" to Theme.colors.primary
        StepState.RUNNING -> "▶" to Theme.colors.primary
        StepState.FAILED -> "✗" to Theme.colors.destructive
        StepState.READY -> "○" to Theme.colors.onBackgroundMuted
    }
    val suffix = if (step.error != null) " (${step.error})" else ""
    // Step titles are plain words, not markdown.
    if (step.title.startsWith("Tool ") || step.title.startsWith("Error:")) {
        Text("$mark ${step.title}$suffix", color = color)
    } else {
        Markdown("$mark ${step.title}$suffix")
    }
}

// Ticket 12/25: the gate's card for one suspended Tool call. Send cards
// show the exact To/Subject/Body from the call plus the unseen-recipient
// highlight; other gated calls get the same Approve/Reject shape with
// their exact args. Rendering is exact strings — never a paraphrase.
@Composable
private fun ApprovalCardRow(
    call: ToolCall,
    unseen: List<String>,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val unseenSet = unseen.map { it.trim().lowercase() }.toSet()
    Section("Confirmation — ${call.name}") {
        if (call.name == "send_email") {
            val to = (call.arguments["to"] as? String).orEmpty()
            val toUnseen = to.split(",").map { it.trim() }.filter { it.isNotEmpty() }.any { it.lowercase() in unseenSet }
            Text("To: $to")
            Text("Subject: ${(call.arguments["subject"] as? String).orEmpty()}")
            Text("Body: ${(call.arguments["body"] as? String).orEmpty()}")
            if (toUnseen) {
                Text("New recipient — never emailed before.", color = Theme.colors.destructive)
            }
        } else {
            call.arguments.entries.forEach { (key, value) ->
                Text("$key: $value")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
            Button(onClick = onApprove, modifier = Modifier.weight(1f)) {
                Text("Approve & Send")
            }
            Button(onClick = onReject, variant = ButtonVariant.Secondary, modifier = Modifier.weight(1f)) {
                Text("Reject")
            }
        }
    }
}
