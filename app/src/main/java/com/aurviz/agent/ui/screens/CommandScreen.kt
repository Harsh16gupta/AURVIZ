package com.aurviz.agent.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurviz.agent.BuildConfig
import com.aurviz.agent.StepState
import com.aurviz.agent.TimelineStep
import com.aurviz.agent.approvalCardFor
import com.aurviz.agent.confirmationDetailFor
import com.aurviz.agent.confirmationTitleFor
import com.aurviz.agent.knownAddressesFromInbox
import com.aurviz.agent.planFailure
import com.aurviz.agent.providerDisplayName
import com.aurviz.agent.stepDisplayTitle
import com.aurviz.agent.visiblePlanSteps
import com.aurviz.agent.ui.AgentUiState
import com.aurviz.agent.ui.InteractionRecord
import com.aurviz.agent.ui.components.AppBar
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ButtonVariant
import com.aurviz.agent.ui.components.Composer
import com.aurviz.agent.ui.components.ConfirmationCard
import com.aurviz.agent.ui.components.EmptyState
import com.aurviz.agent.ui.components.FeedCard
import com.aurviz.agent.ui.components.HapticKind
import com.aurviz.agent.ui.components.LocalHaptics
import com.aurviz.agent.ui.components.Markdown
import com.aurviz.agent.ui.components.ModalBottomSheet
import com.aurviz.agent.ui.components.ModelPill
import com.aurviz.agent.ui.components.OptionRow
import com.aurviz.agent.ui.components.ScreenScaffold
import com.aurviz.agent.ui.components.ScrollToLatestButton
import com.aurviz.agent.ui.components.StarterRow
import com.aurviz.agent.ui.components.StatusBanner
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.components.TextField
import com.aurviz.agent.ui.components.Icon
import com.aurviz.agent.ui.components.ThinkingIndicator
import com.aurviz.agent.ui.components.UserMessage
import com.aurviz.agent.ui.theme.Bell
import com.aurviz.agent.ui.theme.Brain
import com.aurviz.agent.ui.theme.DotsThree
import com.aurviz.agent.ui.theme.NotePencil
import com.aurviz.agent.ui.theme.SlidersHorizontal
import com.aurviz.agent.ui.theme.CaretDown
import com.aurviz.agent.ui.theme.Check
import com.aurviz.agent.ui.theme.EnvelopeSimple
import com.aurviz.agent.ui.theme.Globe
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Menu
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Sparkle
import com.aurviz.agent.ui.theme.Theme
import com.aurviz.agent.ui.theme.X
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ProviderChoices: List<String?> = listOf(null, "deepseek", "gemini", "meta")

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
    onVoiceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenSchedules: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onApprovePending: (String) -> Unit = {},
    onApproveEdited: (String, Map<String, Any?>) -> Unit = { _, _ -> },
    onRejectPending: (String) -> Unit = {},
    onCancelRun: () -> Unit = {},
    onSelectProvider: (String?) -> Unit = {},
    onNewSession: () -> Unit = {},
    onSelectPastInteraction: (String) -> Unit = {},
    onAllowAlways: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showProviderSheet by rememberSaveable { mutableStateOf(false) }
    var showMenuSheet by rememberSaveable { mutableStateOf(false) }
    var showComposerActions by rememberSaveable { mutableStateOf(false) }
    val effectiveModel = state.selectedProvider ?: state.providerUsed ?: BuildConfig.LLM_DEFAULT_PROVIDER
    fun copyReceipt(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("confirmation", text))
    }

    ScreenScaffold(modifier) {
        Column(
            Modifier.fillMaxSize()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            AppBar(
                navigation = {
                    Box(
                        Modifier.size(48.dp).clickable(
                            onClick = onOpenDrawer,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = androidx.compose.foundation.LocalIndication.current,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Menu,
                                contentDescription = "Open menu",
                                modifier = Modifier.size(22.dp),
                                tint = Theme.colors.onBackground,
                            )
                            if (state.pendingApprovals.isNotEmpty()) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).size(7.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(Theme.colors.primary),
                                )
                            }
                        }
                    }
                },
                title = {
                    ModelPill(
                        model = providerDisplayName(effectiveModel),
                        onClick = { showProviderSheet = true },
                    )
                },
                actions = {
                    Box(
                        Modifier.size(44.dp).alpha(if (state.agentRunning) 0.38f else 1f).clickable(
                            enabled = !state.agentRunning,
                            onClick = onNewSession,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = androidx.compose.foundation.LocalIndication.current,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.NotePencil,
                            contentDescription = "New Session",
                            modifier = Modifier.size(20.dp),
                            tint = Theme.colors.onBackground,
                        )
                    }
                    Box(
                        Modifier.size(44.dp).clickable(
                            onClick = { showMenuSheet = true },
                            interactionSource = remember { MutableInteractionSource() },
                            indication = androidx.compose.foundation.LocalIndication.current,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.DotsThree,
                            contentDescription = "More options",
                            modifier = Modifier.size(22.dp),
                            tint = Theme.colors.onBackground,
                        )
                    }
                },
            )

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
                                StarterRow(
                                    icon = Icons.EnvelopeSimple,
                                    text = "Check my important emails",
                                    onClick = { onSend("Check my important emails") },
                                )
                                StarterRow(
                                    icon = Icons.Bell,
                                    text = "Schedule a daily 9 AM email summary",
                                    onClick = { onSend("Schedule a daily 9 AM email summary") },
                                )
                                StarterRow(
                                    icon = Icons.Globe,
                                    text = "Open news.ycombinator.com",
                                    onClick = { onSend("Open news.ycombinator.com") },
                                )
                            }
                        }
                    }

                    items(state.interactions, key = { it.id }) { record ->
                        InteractionItem(
                            record = record,
                            onOpenBrowser = onOpenBrowser,
                            onAllowAlways = onAllowAlways,
                            onCopyReceipt = ::copyReceipt,
                        )
                    }

                    if (state.agentRunning || state.sending || state.pendingApproval != null) {
                        item {
                            ActiveRunSection(
                                state = state,
                                onRetry = onRetry,
                                onApprovePending = onApprovePending,
                                onApproveEdited = onApproveEdited,
                                onRejectPending = onRejectPending,
                            )
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
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = Space * 4)
                    .padding(top = Space * 3, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Composer(
                    value = state.inputText,
                    onValueChange = onInputChange,
                    responding = state.sending || state.agentRunning,
                    canSend = state.inputText.isNotBlank() && !state.sending && !state.agentRunning,
                    onSend = { onSend(state.inputText) },
                    onStop = onCancelRun,
                    onAddClick = { showComposerActions = true },
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
                val keySet =
                    when (choice) {
                        "gemini" -> state.geminiKeySet
                        "deepseek" -> state.deepseekKeySet
                        "meta" -> state.metaKeySet
                        else -> state.geminiKeySet || state.deepseekKeySet || state.metaKeySet
                    }
                OptionRow(
                    title = providerDisplayName(choice),
                    description = if (choice == null) "System default" else if (keySet) "Set" else "Not set",
                    selected = state.selectedProvider == choice,
                    onClick = {
                        onSelectProvider(choice)
                        haptics?.play(HapticKind.Selection)
                        showProviderSheet = false
                    },
                )
            }
        }

        ModalBottomSheet(
            visible = showMenuSheet,
            onDismiss = { showMenuSheet = false },
            title = "More options",
        ) {
            MenuSheetRow(
                icon = Icons.Brain,
                title = "Memory",
                trailing = "${state.memories.size} saved",
                onClick = {
                    showMenuSheet = false
                    onOpenMemory()
                },
            )
            MenuSheetRow(
                icon = Icons.Bell,
                title = "Schedules",
                trailing = "${state.schedules.size}",
                onClick = {
                    showMenuSheet = false
                    onOpenSchedules()
                },
            )
            MenuSheetRow(
                icon = Icons.Globe,
                title = "Browser",
                trailing = null,
                onClick = {
                    showMenuSheet = false
                    onOpenBrowser()
                },
            )
            MenuSheetRow(
                icon = Icons.SlidersHorizontal,
                title = "Settings",
                trailing = null,
                onClick = {
                    showMenuSheet = false
                    onOpenSettings()
                },
            )
        }

        ModalBottomSheet(
            visible = showComposerActions,
            onDismiss = { showComposerActions = false },
            title = "Add to command",
        ) {
            ComposerActionRow(
                icon = Icons.Globe,
                title = "Open Browser",
                description = "Load or inspect a webpage",
                onClick = {
                    showComposerActions = false
                    onOpenBrowser()
                },
            )
            ComposerActionRow(
                icon = Icons.Bell,
                title = "New Schedule",
                description = "Set up a recurring Task Execution",
                onClick = {
                    showComposerActions = false
                    onOpenSchedules()
                },
            )
            ComposerActionRow(
                icon = Icons.Brain,
                title = "Manage Memory",
                description = "View or add saved Preferences",
                onClick = {
                    showComposerActions = false
                    onOpenMemory()
                },
            )
        }
    }

    LaunchedEffect(state.interactions.size, state.timeline.size, state.pendingApproval) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.animateScrollToItem(total - 1)
    }
}

@Composable
private fun MenuSheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    trailing: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp).clickable(
            onClick = onClick,
            interactionSource = remember { MutableInteractionSource() },
            indication = androidx.compose.foundation.LocalIndication.current,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = Theme.colors.onSurface)
        Text(title, style = Theme.typography.label, color = Theme.colors.onSurface, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = Theme.typography.caption, color = Theme.colors.onBackgroundMuted)
        }
    }
}

@Composable
private fun ComposerActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp).clickable(
            onClick = onClick,
            interactionSource = remember { MutableInteractionSource() },
            indication = androidx.compose.foundation.LocalIndication.current,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = Theme.colors.onSurface)
        Column(Modifier.weight(1f)) {
            Text(title, style = Theme.typography.label, color = Theme.colors.onSurface)
            Text(description, style = Theme.typography.caption, color = Theme.colors.onBackgroundMuted)
        }
    }
}

@Composable
private fun InteractionItem(
    record: InteractionRecord,
    onOpenBrowser: () -> Unit,
    onAllowAlways: (String) -> Unit = {},
    onCopyReceipt: (String) -> Unit = {},
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

        visiblePlanSteps(record.steps).forEachIndexed { _, step ->
            StepRow(step = step)
        }

        if (record.confirmationStatus != null) {
            // Settled receipt: the remembered recipient (our own "Send
            // email to <addr>" format) can become a Preference grant.
            val rememberAddress =
                record.confirmationTitle
                    ?.removePrefix("Send email to ")
                    ?.trim()
                    ?.split(",")
                    ?.firstOrNull()
                    ?.trim()
                    ?.takeIf { it.contains("@") }
            ConfirmationCard(
                toolLabel = "Confirmation",
                title = record.confirmationTitle ?: "Sensitive Tool",
                detail = record.confirmationDetail,
                status = record.confirmationStatus,
                onCopy = { onCopyReceipt(record.confirmationDetail.orEmpty()) },
                onRememberPreference = rememberAddress?.let { address ->
                    { onAllowAlways(address) }
                },
            )
        }

        if (record.failed) {
            planFailure(record.steps)?.let { failure ->
                StatusBanner(message = failure.error ?: failure.title)
            }
        }

        if (record.responseText.isNotBlank()) {
            Markdown(record.responseText)
        }

        if (record.emails.isNotEmpty()) {
            FeedCard {
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
            FeedCard {
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
    onApproveEdited: (String, Map<String, Any?>) -> Unit,
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

        visiblePlanSteps(state.timeline).forEachIndexed { _, step ->
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
                title = confirmationTitleFor(pendingCall)
                detail = confirmationDetailFor(pendingCall)
            }
            val unseen = state.pendingApprovalUnseen
            if (pendingCall.name == "send_email") {
                var to by remember(proposalId) {
                    mutableStateOf((pendingCall.arguments["to"] as? String).orEmpty())
                }
                var subject by remember(proposalId) {
                    mutableStateOf((pendingCall.arguments["subject"] as? String).orEmpty())
                }
                var body by remember(proposalId) {
                    mutableStateOf((pendingCall.arguments["body"] as? String).orEmpty())
                }
                val toUnseen = to.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    .any { it.lowercase() in unseen.map { u -> u.lowercase() }.toSet() }
                ConfirmationCard(
                    toolLabel = pendingCall.name,
                    title = title,
                    detail = detail,
                    warningText = if (toUnseen || unseen.isNotEmpty()) {
                        "New recipient — never emailed before."
                    } else {
                        null
                    },
                    approveLabel = "Approve & Send",
                    denyLabel = "Deny",
                    onApprove = {
                        val origTo = (pendingCall.arguments["to"] as? String).orEmpty()
                        val origSubject = (pendingCall.arguments["subject"] as? String).orEmpty()
                        val origBody = (pendingCall.arguments["body"] as? String).orEmpty()
                        if (to.trim() != origTo.trim() || subject != origSubject || body != origBody) {
                            onApproveEdited(
                                proposalId,
                                mapOf("to" to to.trim(), "subject" to subject, "body" to body),
                            )
                        } else {
                            onApprovePending(proposalId)
                        }
                    },
                    onDeny = { onRejectPending(proposalId) },
                ) {
                    TextField(value = to, onValueChange = { to = it }, label = "To")
                    TextField(value = subject, onValueChange = { subject = it }, label = "Subject")
                    TextField(value = body, onValueChange = { body = it }, label = "Body", minLines = 3)
                }
            } else {
                ConfirmationCard(
                    toolLabel = pendingCall.name,
                    title = title,
                    detail = detail,
                    warningText = if (unseen.isNotEmpty()) {
                        "New recipient — never emailed before: ${unseen.joinToString()}"
                    } else {
                        null
                    },
                    approveLabel = "Approve",
                    denyLabel = "Deny",
                    onApprove = { onApprovePending(proposalId) },
                    onDeny = { onRejectPending(proposalId) },
                )
            }
        }

        // Active-run only, exactly once: one error row plus a single retry.
        if (!state.agentRunning) {
            planFailure(state.timeline)?.let { failure ->
                StatusBanner(message = failure.error ?: failure.title)
                Button(onClick = onRetry, variant = ButtonVariant.Secondary) {
                    Text("Retry failed Tool")
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: TimelineStep) {
    // Expandable Plan step: collapsed shows the clean status row (human
    // Tool label, no inline error duplication); expanding reveals the args
    // summary and the failure detail when present.
    var expanded by rememberSaveable(step.title) { mutableStateOf(false) }
    val expandable = step.argsSummary != null || step.error != null
    val headerModifier =
        if (expandable) {
            Modifier.clickable(
                onClick = { expanded = !expanded },
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.foundation.LocalIndication.current,
            )
        } else {
            Modifier
        }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = headerModifier.fillMaxWidth(),
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
            Text(
                stepDisplayTitle(step),
                style = Theme.typography.code,
                color = if (step.state == StepState.FAILED) {
                    Theme.colors.destructive
                } else {
                    Theme.colors.onBackgroundMuted
                },
                modifier = Modifier.weight(1f),
            )
            if (expandable) {
                Icon(
                    Icons.CaretDown,
                    contentDescription = if (expanded) "Collapse step" else "Expand step",
                    modifier = Modifier.size(14.dp),
                    tint = Theme.colors.onBackgroundMuted,
                )
            }
        }
        if (expanded) {
            if (step.argsSummary != null) {
                Text(
                    step.argsSummary,
                    style = Theme.typography.caption,
                    color = Theme.colors.onBackgroundMuted,
                    modifier = Modifier.padding(start = 22.dp),
                )
            }
            if (step.error != null) {
                Text(
                    step.error,
                    style = Theme.typography.caption,
                    color = Theme.colors.destructive,
                    modifier = Modifier.padding(start = 22.dp),
                )
            }
        }
    }
}
