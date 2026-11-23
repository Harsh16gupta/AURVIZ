package com.aurviz.agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurviz.agent.BuildConfig
import com.aurviz.agent.StepState
import com.aurviz.agent.TimelineStep
import com.aurviz.agent.approvalCardFor
import com.aurviz.agent.knownAddressesFromInbox
import com.aurviz.agent.ui.AgentUiState
import com.aurviz.agent.ui.InteractionRecord
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ButtonVariant
import com.aurviz.agent.ui.components.Composer
import com.aurviz.agent.ui.components.ConfirmationCard
import com.aurviz.agent.ui.components.EmptyState
import com.aurviz.agent.ui.components.HapticKind
import com.aurviz.agent.ui.components.LocalHaptics
import com.aurviz.agent.ui.components.Markdown
import com.aurviz.agent.ui.components.ModalBottomSheet
import com.aurviz.agent.ui.components.ModelPill
import com.aurviz.agent.ui.components.OptionRow
import com.aurviz.agent.ui.components.ScreenScaffold
import com.aurviz.agent.ui.components.ScrollToLatestButton
import com.aurviz.agent.ui.components.SuggestionCard
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.components.Icon
import com.aurviz.agent.ui.components.ThinkingIndicator
import com.aurviz.agent.ui.components.UserMessage
import com.aurviz.agent.ui.theme.Check
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Sparkle
import com.aurviz.agent.ui.theme.Theme
import com.aurviz.agent.ui.theme.X
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ProviderChoices: List<String?> = listOf(null, "deepseek", "gemini", "groq", "meta")

// The Agent's home: a scrollable Interaction feed, its Plans as Task
// Execution rows, and the Composer. Sensitive Tools never run here
// directly — an email send or a forgotten Memory opens a Confirmation
// first; the gate suspends until the user approves, edits, or rejects.
@Composable
fun CommandScreen(
    state: AgentUiState,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onRetry: () -> Unit,
    onMicTap: () -> Unit,
    onVoiceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    onApprovePending: (String) -> Unit = {},
    onApproveEdited: (String, Map<String, Any?>) -> Unit = { _, _ -> },
    onRejectPending: (String) -> Unit = {},
    onCancelRun: () -> Unit = {},
    onSelectProvider: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showProviderSheet by rememberSaveable { mutableStateOf(false) }
    val effectiveModel = state.selectedProvider ?: state.providerUsed ?: BuildConfig.LLM_DEFAULT_PROVIDER

    ScreenScaffold(modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.statusBarsPadding()
                    .height(56.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("AURVIZ", style = Theme.typography.title)
                Spacer(Modifier.weight(1f))
                ModelPill(model = effectiveModel, onClick = { showProviderSheet = true })
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                        .padding(horizontal = Space * 4)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { focusManager.clearFocus() },
                    verticalArrangement = Arrangement.spacedBy(Space * 2),
                ) {
                    if (state.interactions.isEmpty() && !state.agentRunning && !state.sending) {
                        item {
                            EmptyState(
                                Icons.Sparkle,
                                "Aurviz Agent",
                                "Give a Voice Command or type a task to execute.",
                            )
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(Space * 2)) {
                                SuggestionCard(
                                    title = "Check my important emails",
                                    description = "Read important mail",
                                    onClick = { onSend("Check my important emails") },
                                )
                                SuggestionCard(
                                    title = "Schedule a daily 9 AM email summary",
                                    description = "Daily 9 AM summary",
                                    onClick = { onSend("Schedule a daily 9 AM email summary") },
                                )
                                SuggestionCard(
                                    title = "Open news.ycombinator.com",
                                    description = "Open in Browser",
                                    onClick = { onSend("Open news.ycombinator.com") },
                                )
                            }
                        }
                    }

                    items(state.interactions, key = { it.id }) { record ->
                        InteractionItem(record = record, onOpenBrowser = onOpenBrowser)
                    }

                    if (state.agentRunning || state.sending || state.pendingApproval != null) {
                        item {
                            ActiveRunSection(
                                state = state,
                                onRetry = onRetry,
                                onApprovePending = onApprovePending,
                                onRejectPending = onRejectPending,
                            )
                        }
                    } else if (state.timeline.any { it.state == StepState.FAILED }) {
                        item {
                            Button(onClick = onRetry, variant = ButtonVariant.Secondary) {
                                Text("Retry failed Tool")
                            }
                        }
                    }
                }

                ScrollToLatestButton(
                    visible = listState.canScrollForward,
                    onClick = {
                        scope.launch {
                            val total = listState.layoutInfo.totalItemsCount
                            if (total > 0) listState.animateScrollToItem(total - 1)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(end = Space * 4, bottom = Space * 2),
                )
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
                    model = effectiveModel,
                    responding = state.sending || state.agentRunning,
                    canSend = state.inputText.isNotBlank() && !state.sending && !state.agentRunning,
                    onSend = { onSend(state.inputText) },
                    onStop = onCancelRun,
                    onAddClick = { onOpenBrowser() },
                    onModelClick = { showProviderSheet = true },
                    onSpeakClick = onMicTap,
                    onVoiceClick = onVoiceClick,
                    placeholder = "Ask Aurviz",
                )
            }
        }

        ModalBottomSheet(
            visible = showProviderSheet,
            onDismiss = { showProviderSheet = false },
            title = "AI Provider",
        ) {
            ProviderChoices.forEach { choice ->
                OptionRow(
                    title = choice ?: "Auto (Default)",
                    description = if (choice == null) "System default" else null,
                    selected = state.selectedProvider == choice,
                    onClick = {
                        onSelectProvider(choice)
                        haptics?.play(HapticKind.Selection)
                        showProviderSheet = false
                    },
                )
            }
        }
    }

    LaunchedEffect(state.interactions.size, state.timeline.size, state.pendingApproval) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.animateScrollToItem(total - 1)
    }
}

@Composable
private fun InteractionItem(
    record: InteractionRecord,
    onOpenBrowser: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space * 2)) {
        UserMessage(record.command)

        if (record.recalledMemories.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                record.recalledMemories.forEach { mem ->
                    Text(
                        "Memory: ${mem.take(40)}",
                        style = Theme.typography.caption.copy(fontSize = 12.sp),
                        color = Theme.colors.onBackgroundMuted,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Theme.colors.surfaceVariant)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        record.steps.forEachIndexed { _, step ->
            StepRow(step = step)
        }

        if (record.confirmationStatus != null) {
            ConfirmationCard(
                toolLabel = "Confirmation",
                title = record.confirmationTitle ?: "Sensitive Tool",
                detail = record.confirmationDetail,
                status = record.confirmationStatus,
            )
        }

        if (record.responseText.isNotBlank()) {
            Markdown(record.responseText)
        }

        if (record.emails.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Theme.colors.card)
                    .border(0.5.dp, Theme.colors.edge, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                record.emails.forEach { email ->
                    Column {
                        Text(
                            email.from,
                            style = Theme.typography.caption,
                            color = Theme.colors.onBackgroundMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            email.subject,
                            style = Theme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                            color = Theme.colors.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            email.snippet,
                            style = Theme.typography.caption,
                            color = Theme.colors.onBackgroundMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        record.webPage?.let { page ->
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Theme.colors.card)
                    .border(0.5.dp, Theme.colors.edge, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(Space),
            ) {
                Text(
                    page.title.ifBlank { page.url },
                    style = Theme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                    color = Theme.colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    page.url,
                    style = Theme.typography.caption,
                    color = Theme.colors.onBackgroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${page.links.size} links",
                    style = Theme.typography.caption,
                    color = Theme.colors.onBackgroundMuted,
                )
                Button(onClick = onOpenBrowser, variant = ButtonVariant.Secondary) {
                    Text("View in Browser")
                }
            }
        }

        record.retryNotice?.let { notice ->
            Text(
                notice,
                style = Theme.typography.caption,
                color = Theme.colors.onBackgroundMuted,
            )
        }
    }
}

@Composable
private fun ActiveRunSection(
    state: AgentUiState,
    onRetry: () -> Unit,
    onApprovePending: (String) -> Unit,
    onRejectPending: (String) -> Unit,
) {
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.agentRunning, state.sending) {
        while (state.agentRunning || state.sending) {
            delay(1000L)
            elapsed++
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space * 2)) {
        if (state.activeCommand != null) {
            UserMessage(state.activeCommand)
        }

        if (state.agentRunning || state.sending) {
            ThinkingIndicator(
                thinking = true,
                seconds = elapsed,
                thoughts = "",
            )
        }

        state.timeline.forEachIndexed { _, step ->
            StepRow(step = step)
        }

        val pendingCall = state.pendingApproval
        val proposalId = state.pendingApprovalProposalId
        if (pendingCall != null && proposalId != null) {
            val card = approvalCardFor(pendingCall, knownAddressesFromInbox(state.emails))
            val title: String
            val detail: String
            if (card != null) {
                val to = card.to.joinToString { it.address }
                title = "Send email to $to"
                detail = "To: $to\nSubject: ${card.subject}\n${card.body}"
            } else {
                title = "Run ${pendingCall.name}"
                detail = pendingCall.arguments.entries.joinToString("\n") { (k, v) -> "$k: $v" }
            }
            val unseen = state.pendingApprovalUnseen
            ConfirmationCard(
                toolLabel = pendingCall.name,
                title = title,
                detail = detail,
                warningText = if (unseen.isNotEmpty()) {
                    "New recipient — never emailed before: ${unseen.joinToString()}"
                } else {
                    null
                },
                approveLabel = if (pendingCall.name == "send_email") "Approve & Send" else "Approve",
                denyLabel = "Deny",
                onApprove = { onApprovePending(proposalId) },
                onDeny = { onRejectPending(proposalId) },
            )
        }

        if (state.timeline.any { it.state == StepState.FAILED } && !state.agentRunning) {
            Button(onClick = onRetry, variant = ButtonVariant.Secondary) {
                Text("Retry failed Tool")
            }
        }
    }
}

@Composable
private fun StepRow(step: TimelineStep) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (step.state) {
            StepState.DONE ->
                Icon(
                    Icons.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Theme.colors.success,
                )
            StepState.FAILED ->
                Icon(
                    Icons.X,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Theme.colors.destructive,
                )
            StepState.READY, StepState.RUNNING ->
                Text("•", color = Theme.colors.onBackgroundMuted)
        }
        val suffix = if (step.error != null) " (${step.error})" else ""
        Text(
            "${step.title}$suffix",
            style = Theme.typography.code,
            color = if (step.state == StepState.FAILED) {
                Theme.colors.destructive
            } else {
                Theme.colors.onBackgroundMuted
            },
        )
    }
}
