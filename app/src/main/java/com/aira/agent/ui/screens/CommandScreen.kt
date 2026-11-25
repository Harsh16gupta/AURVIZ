package com.aira.agent.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aira.agent.StepState
import com.aira.agent.TimelineStep
import com.aira.agent.approvalCardFor
import com.aira.agent.confirmationDetailFor
import com.aira.agent.confirmationTitleFor
import com.aira.agent.knownAddressesFromInbox
import com.aira.agent.planFailure
import com.aira.agent.stepDisplayTitle
import com.aira.agent.visiblePlanSteps
import com.aira.agent.ui.AgentUiState
import com.aira.agent.ui.InteractionRecord
import com.aira.agent.ui.formatSessionTranscriptMarkdown
import com.aira.agent.ui.components.AiraLogoMark
import com.aira.agent.ui.components.AppBar
import com.aira.agent.ui.rememberAddressFor
import com.aira.agent.ui.components.Button
import com.aira.agent.ui.components.ButtonVariant
import com.aira.agent.ui.components.Composer
import com.aira.agent.ui.components.ConfirmationCard
import com.aira.agent.ui.components.FeedCard
import com.aira.agent.ui.components.Markdown
import com.aira.agent.ui.components.ModalBottomSheet
import com.aira.agent.ui.components.ScreenScaffold
import com.aira.agent.ui.components.ScrollToLatestButton
import com.aira.agent.ui.components.StatusBanner
import com.aira.agent.ui.components.Text
import com.aira.agent.ui.components.TextField
import com.aira.agent.ui.components.Icon
import com.aira.agent.ui.components.ThinkingIndicator
import com.aira.agent.ui.components.UserMessage
import com.aira.agent.ui.theme.Bell
import com.aira.agent.ui.theme.Brain
import com.aira.agent.ui.theme.DotsThree
import com.aira.agent.ui.theme.EnvelopeSimple
import com.aira.agent.ui.theme.NotePencil
import com.aira.agent.ui.theme.SlidersHorizontal
import com.aira.agent.ui.theme.CaretDown
import com.aira.agent.ui.theme.Check
import com.aira.agent.ui.theme.Copy
import com.aira.agent.ui.theme.Export
import com.aira.agent.ui.theme.Globe
import com.aira.agent.ui.theme.Icons
import com.aira.agent.ui.theme.Menu
import com.aira.agent.ui.theme.Space
import com.aira.agent.ui.theme.Theme
import com.aira.agent.ui.theme.X
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


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
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showMenuSheet by rememberSaveable { mutableStateOf(false) }
    var showComposerActions by rememberSaveable { mutableStateOf(false) }
    fun copyReceipt(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("confirmation", text))
    }
    // Ticket #109 (T4): 1-tap Markdown Session transcript export/share.
    // Share opens the Android chooser; copy writes Markdown to clipboard.
    fun copySessionTranscript() {
        val md = formatSessionTranscriptMarkdown(state.sessionId, state.interactions)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Aira Session", md))
    }

    fun shareSessionTranscript() {
        val md = formatSessionTranscriptMarkdown(state.sessionId, state.interactions)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Aira Session")
            putExtra(Intent.EXTRA_TEXT, md)
        }
        try {
            context.startActivity(Intent.createChooser(sendIntent, "Share Session"))
        } catch (_: Exception) {
        }
    }

    ScreenScaffold(modifier) {
        Column(
            Modifier.fillMaxSize()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            AppBar(
                navigation = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
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
                    }
                },
                title = {
                },
                actions = {
                    Box(
                        Modifier.size(48.dp).alpha(if (state.agentRunning) 0.38f else 1f).clickable(
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
                        Modifier.size(48.dp).clickable(
                            onClick = { showMenuSheet = true },
                            interactionSource = remember { MutableInteractionSource() },
                            indication = androidx.compose.foundation.LocalIndication.current,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.DotsThree,
                            contentDescription = "Workspace",
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
                            HeroBlock()
                        }
                        item {
                            StarterCommands(onSend = onSend)
                        }
                    }

                    items(state.interactions, key = { it.id }) { record ->
                        InteractionItem(
                            record = record,
                            onOpenBrowser = onOpenBrowser,
                            onAllowAlways = onAllowAlways,
                            onCopyReceipt = ::copyReceipt,
                            onRetry = onRetry,
                            onOpenSettings = onOpenSettings,
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
                                onOpenSettings = onOpenSettings,
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
                    .background(Theme.colors.background)
                    .padding(horizontal = Space * 4)
                    .padding(top = 8.dp, bottom = 8.dp),
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
                    placeholder = "Ask AIRA",
                )
            }
        }

        ModalBottomSheet(
            visible = showMenuSheet,
            onDismiss = { showMenuSheet = false },
            title = "Workspace",
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
            // Ticket #109 (T4): Markdown Session transcript export/share.
            if (state.interactions.isNotEmpty()) {
                MenuSheetRow(
                    icon = Icons.Export,
                    title = "Share Session",
                    trailing = "${state.interactions.size} turns",
                    onClick = {
                        showMenuSheet = false
                        shareSessionTranscript()
                    },
                )
                MenuSheetRow(
                    icon = Icons.Copy,
                    title = "Copy Session",
                    trailing = "Markdown",
                    onClick = {
                        showMenuSheet = false
                        copySessionTranscript()
                    },
                )
            }
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
private fun HeroBlock() {
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AiraLogoMark(size = 32.dp)
        Text(
            "Meet AIRA.",
            style = Theme.typography.display.copy(textAlign = TextAlign.Center),
            color = Theme.colors.onBackground,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Plan, remember, and confirm before acting.",
            style = Theme.typography.body.copy(
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Normal,
            ),
            color = Theme.colors.onBackgroundMuted,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StarterCommands(onSend: (String) -> Unit) {
    // One-tap demo starters in the ChatGPT empty-state style: compact
    // wrap-content pills, no subtitles, no chevrons. Each label sends a
    // full multi-Tool command so one tap demonstrates the whole Agent:
    // Memory recall, Gmail triage, Web research + gated send, Schedule
    // automation with Confirmation. No placeholder pages.
    data class Starter(
        val icon: androidx.compose.ui.graphics.vector.ImageVector,
        val label: String,
        val command: String,
    )
    val starters = listOf(
        Starter(
            Icons.EnvelopeSimple,
            "Summarize my unread emails",
            "Search my unread emails, summarize the top 3 by sender and subject, and remember who emails me most.",
        ),
        Starter(
            Icons.Globe,
            "Brief me on AI, email me the summary",
            "Open https://en.wikipedia.org/wiki/Artificial_intelligence, summarize it in 5 bullets, then send that summary to myself with subject AI briefing.",
        ),
        Starter(
            Icons.Bell,
            "Set my weekday 8:30 AM briefing",
            "Remind me every weekday at 8:30 AM to summarize unread emails.",
        ),
        Starter(
            Icons.Brain,
            "What do you remember about me?",
            "List everything you remember about me, including my boss and preferences.",
        ),
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        starters.forEach { starter ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Theme.colors.surfaceVariant)
                    .border(1.dp, Theme.colors.edge, RoundedCornerShape(50))
                    .clickable(
                        onClick = { onSend(starter.command) },
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.foundation.LocalIndication.current,
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    starter.icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Theme.colors.primary,
                )
                Text(
                    starter.label,
                    style = Theme.typography.label,
                    color = Theme.colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// The Plan card already shows the terminal failure once with Retry
// Tool. A settled "Failed: <same error>" response restates it verbatim,
// so the feed hides the response in that case instead of doubling it.
private fun responseDuplicatesFailure(responseText: String, failure: TimelineStep): Boolean {
    val body = responseText.removePrefix("Failed:").removePrefix("Error:").trim()
    if (body.isBlank()) return true
    val err = failure.error.orEmpty()
    val title = failure.title.orEmpty()
    return (err.isNotBlank() && (body == err || body.contains(err))) ||
        (title.isNotBlank() && (body == title || body.contains(title)))
}

@Composable
private fun PlanTraceCard(
    steps: List<TimelineStep>,
    failure: TimelineStep?,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Theme.colors.surface)
            .border(1.dp, Theme.colors.edge, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        steps.forEach { step -> StepRow(step = step) }
        if (failure != null) {
            val message = failure.error ?: failure.title
            Text(
                message,
                style = Theme.typography.caption,
                color = Theme.colors.destructive,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Theme.colors.surfaceVariant)
                        .clickable(
                            onClick = onRetry,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = androidx.compose.foundation.LocalIndication.current,
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Retry Tool",
                        style = Theme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                        color = Theme.colors.onSurfaceVariant,
                    )
                }
                val authError = message.contains("401") || message.contains("403")
                if (authError) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Theme.colors.surfaceVariant)
                            .clickable(
                                onClick = onOpenSettings,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = androidx.compose.foundation.LocalIndication.current,
                            )
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Open Settings",
                            style = Theme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                            color = Theme.colors.accentText,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractionItem(
    record: InteractionRecord,
    onOpenBrowser: () -> Unit,
    onAllowAlways: (String) -> Unit = {},
    onCopyReceipt: (String) -> Unit = {},
    onRetry: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
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

        val planSteps = visiblePlanSteps(record.steps)
        val settledFailure = if (record.failed) planFailure(record.steps) else null
        if (planSteps.isNotEmpty()) {
            PlanTraceCard(
                steps = planSteps,
                failure = settledFailure,
                onRetry = onRetry,
                onOpenSettings = onOpenSettings,
            )
        } else if (settledFailure != null) {
            StatusBanner(message = settledFailure.error ?: settledFailure.title)
        }

        if (record.confirmationStatus != null) {
            // Settled receipt: the executed send's recipient can become a
            // Preference grant. Derived via rememberAddressFor (legacy
            // title prefix, else the executed step's effective "to:").
            val rememberAddress = rememberAddressFor(record)
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

        if (record.responseText.isNotBlank()) {
            // Deduplicate: the Plan card already renders the terminal
            // failure once with Retry Tool. A settled "Failed: <same error>"
            // response would repeat it verbatim below the card, so hide the
            // response when it only restates the card's failure.
            val duplicatesFailure = record.failed && settledFailure != null &&
                responseDuplicatesFailure(record.responseText, settledFailure)
            if (!duplicatesFailure) {
                Markdown(record.responseText)
            }
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
    onOpenSettings: () -> Unit = {},
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

        val activePlanSteps = visiblePlanSteps(state.timeline)
        val activeFailure = if (!state.agentRunning) planFailure(state.timeline) else null
        if (activePlanSteps.isNotEmpty()) {
            PlanTraceCard(
                steps = activePlanSteps,
                failure = activeFailure,
                onRetry = onRetry,
                onOpenSettings = onOpenSettings,
            )
        } else if (activeFailure != null) {
            StatusBanner(message = activeFailure.error ?: activeFailure.title)
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
                    approveLabel = "Confirm & Send",
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
                    approveLabel = "Confirm",
                    denyLabel = "Deny",
                    onApprove = { onApprovePending(proposalId) },
                    onDeny = { onRejectPending(proposalId) },
                )
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
            modifier = headerModifier.fillMaxWidth().heightIn(min = 36.dp).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (step.state) {
                StepState.DONE ->
                    Box(
                        Modifier.size(18.dp).clip(RoundedCornerShape(50))
                            .background(Theme.colors.success.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Check,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = Theme.colors.success,
                        )
                    }
                StepState.FAILED ->
                    Box(
                        Modifier.size(18.dp).clip(RoundedCornerShape(50))
                            .background(Theme.colors.destructive.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.X,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = Theme.colors.destructive,
                        )
                    }
                StepState.READY, StepState.RUNNING ->
                    Box(
                        Modifier.size(18.dp).clip(RoundedCornerShape(50))
                            .background(Theme.colors.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(6.dp).clip(RoundedCornerShape(50))
                                .background(Theme.colors.primary),
                        )
                    }
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
                Box(
                    Modifier.padding(start = 26.dp, top = 2.dp, bottom = 4.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Theme.colors.inset)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(step.argsSummary, style = Theme.typography.code, color = Theme.colors.onBackgroundMuted)
                }
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
