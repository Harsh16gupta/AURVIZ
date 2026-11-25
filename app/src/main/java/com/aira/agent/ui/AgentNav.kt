package com.aira.agent.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aira.agent.knownAddressesFromInbox
import com.aira.agent.unseenRecipients
import com.aira.agent.ui.filterSessionSummaries
import com.aira.agent.ui.components.AiraLogoMark
import com.aira.agent.ui.components.ScreenScaffold
import com.aira.agent.ui.components.SearchField
import com.aira.agent.ui.components.SideDrawer
import com.aira.agent.ui.components.Text
import com.aira.agent.ui.components.Icon
import com.aira.agent.ui.screens.BrowserScreen
import com.aira.agent.ui.screens.CommandScreen
import com.aira.agent.ui.screens.MemoryScreen
import com.aira.agent.ui.screens.OnboardingScreen
import com.aira.agent.ui.screens.SchedulesScreen
import com.aira.agent.ui.screens.SettingsScreen
import com.aira.agent.ui.sheets.EmailConfirmationSheet
import com.aira.agent.ui.sheets.ForgetMemorySheet
import com.aira.agent.ui.sheets.VoiceCommandSheet
import com.aira.agent.ui.theme.Bell
import com.aira.agent.ui.theme.Brain
import com.aira.agent.ui.theme.CaretRight
import com.aira.agent.ui.theme.Globe
import com.aira.agent.ui.theme.Icons
import com.aira.agent.ui.theme.NotePencil
import com.aira.agent.ui.theme.SlidersHorizontal
import com.aira.agent.ui.theme.Theme
import com.aira.agent.ui.theme.Trash
import com.aira.agent.ui.theme.User

object Dests {
    const val COMMAND = "command"
    const val BROWSER = "browser"
    const val SCHEDULES = "schedules"
    const val MEMORY = "memory"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"
}

@Composable
fun AgentNav(
    vm: AgentViewModel,
    onMicTap: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onVoiceDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showVoice by rememberSaveable { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var sessionQuery by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.authUrl) {
        val url = state.authUrl ?: return@LaunchedEffect
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        vm.consumeAuthUrl()
    }

    // Ticket #108 (T3): Share Sheet / launcher shortcut routing. The
    // ViewModel stages a pending route (+ optional Voice Command sheet);
    // the NavHost consumes it once so config changes never re-trigger it.
    LaunchedEffect(state.pendingNavRoute, state.pendingOpenVoiceSheet) {
        val route = state.pendingNavRoute ?: return@LaunchedEffect
        if (state.pendingOpenVoiceSheet) showVoice = true
        vm.consumePendingNavigation()
        navController.navigate(route)
    }

    ScreenScaffold(modifier) {
        Box(Modifier.fillMaxSize()) {
            val startDest = remember { if (state.onboardingCompleted) Dests.COMMAND else Dests.ONBOARDING }
            NavHost(navController = navController, startDestination = startDest) {
                composable(Dests.ONBOARDING) {
                    OnboardingScreen(
                        state = state,
                        onConnectGmail = vm::startSignIn,
                        onPasteLink = vm::onPastedLink,
                        onDismissPasteFallback = vm::dismissPasteFallback,
                        onSaveSingleLlmKey = vm::saveSingleLlmKey,
                        onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                        onOpenBatterySettings = onOpenBatterySettings,
                        onComplete = {
                            vm.completeOnboarding()
                            navController.navigate(Dests.COMMAND) {
                                popUpTo(Dests.ONBOARDING) { inclusive = true }
                            }
                        },
                    )
                }
                composable(Dests.COMMAND) {
                    CommandScreen(
                        state = state,
                        onInputChange = vm::onInputChange,
                        onSend = vm::sendCommand,
                        onRetry = vm::retry,
                        onVoiceClick = { showVoice = true },
                        onOpenBrowser = { navController.navigate(Dests.BROWSER) },
                        onOpenSchedules = { navController.navigate(Dests.SCHEDULES) },
                        onOpenMemory = { navController.navigate(Dests.MEMORY) },
                        onOpenSettings = { navController.navigate(Dests.SETTINGS) },
                        onOpenDrawer = { drawerOpen = true },
                        onApprovePending = vm::approvePendingSend,
                        onApproveEdited = vm::approvePendingSendEdited,
                        onRejectPending = vm::rejectPendingSend,
                        onCancelRun = vm::cancelRun,
                        onSelectProvider = vm::setSelectedProvider,
                        onNewSession = vm::startNewSession,
                        onSelectPastInteraction = vm::onInputChange,
                        onAllowAlways = vm::allowAlwaysAllow,
                    )
                }
                composable(Dests.BROWSER) {
                    BrowserScreen(
                        state = state,
                        onOpenUrl = vm::openWeb,
                        onAttachBridge = vm::attachWebBridge,
                        onBack = { navController.popBackStack() },
                        onSaveMemory = { vm.sendCommand("remember $it") },
                    )
                }
                composable(Dests.SCHEDULES) {
                    SchedulesScreen(
                        state = state,
                        onPreviewSchedule = { vm.sendCommand("schedule $it") },
                        onApproveSchedule = vm::approveSchedule,
                        onRejectSchedule = vm::rejectSchedule,
                        onApproveUnschedule = vm::approveUnschedule,
                        onRejectUnschedule = vm::rejectUnschedule,
                        onUnschedule = vm::unschedule,
                        onApproveHeldWrite = vm::approveHeldWrite,
                        onRejectHeldWrite = vm::rejectHeldWrite,
                        onRefreshApprovals = vm::refreshPendingApprovals,
                        onToggleScheduleEnabled = vm::toggleScheduleEnabled,
                        onRunScheduleNow = {
                            vm.runScheduleNow(it)
                            navController.navigate(Dests.COMMAND)
                        },
                        onRefreshRuns = vm::refreshScheduleRuns,
                    )
                }
                composable(Dests.MEMORY) {
                    MemoryScreen(
                        state = state,
                        onRemember = { vm.sendCommand("remember $it") },
                        onForget = vm::deleteMemory,
                        onUpdateMemory = vm::updateMemory,
                        onRestoreMemory = vm::restoreMemory,
                    )
                }
                composable(Dests.SETTINGS) {
                    SettingsScreen(
                        state = state,
                        onConnectGmail = vm::startSignIn,
                        onRevokeGmail = vm::revokeGmail,
                        onPasteLink = vm::onPastedLink,
                        onDismissPasteFallback = vm::dismissPasteFallback,
                        onSaveSingleLlmKey = vm::saveSingleLlmKey,
                        onClearSingleLlmKey = vm::clearSingleLlmKey,
                        onSetSelectedProvider = vm::setSelectedProvider,
                        onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                        onRefreshExactAlarm = vm::refreshExactAlarmFlag,
                        onOpenBatterySettings = onOpenBatterySettings,
                        onRefreshBattery = vm::refreshBatteryFlag,
                        onVoiceSettings = { showVoice = true },
                        onSetThemeMode = vm::setThemeMode,
                        onSetHapticsEnabled = vm::setHapticsEnabled,
                        onAllowAlwaysAdd = vm::allowAlwaysAllow,
                        onAllowAlwaysRevoke = vm::allowAlwaysRevoke,
                        onOpenMemory = { navController.navigate(Dests.MEMORY) },
                        onRunDiagnostics = vm::runDiagnostics,
                        onClearInteractions = vm::clearInteractions,
                    )
                }
            }
        }

        SideDrawer(visible = drawerOpen, onDismiss = { drawerOpen = false }) {
            // Top Row: 56.dp height, 16.dp horizontal padding.
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AiraLogoMark(
                    size = 20.dp,
                    archColor = Theme.colors.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text("AIRA", style = Theme.typography.title, color = Theme.colors.onSurface)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(48.dp).clip(Theme.shapes.fullyRounded).clickable(
                        onClick = {
                            vm.startNewSession()
                            drawerOpen = false
                        },
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.foundation.LocalIndication.current,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.NotePencil,
                        contentDescription = "New Session",
                        modifier = Modifier.size(20.dp),
                        tint = Theme.colors.onSurface,
                    )
                }
            }
            // Pinned Shortcuts: 12.dp horizontal padding, 4.dp vertical gap.
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Row 1: Schedules 44.dp.
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clip(Theme.shapes.small).clickable(
                        onClick = {
                            drawerOpen = false
                            navController.navigate(Dests.SCHEDULES)
                        },
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.foundation.LocalIndication.current,
                    ).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Bell,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Theme.colors.onSurface,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Schedules",
                        style = Theme.typography.label,
                        color = Theme.colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.pendingApprovals.isNotEmpty()) {
                        Box(
                            Modifier.clip(Theme.shapes.fullyRounded).background(Theme.colors.primary)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${state.pendingApprovals.size}",
                                style = Theme.typography.caption,
                                color = Theme.colors.onPrimary,
                            )
                        }
                    } else {
                        Text(
                            "${state.schedules.size}",
                            style = Theme.typography.caption,
                            color = Theme.colors.onBackgroundMuted,
                        )
                    }
                }
                // Row 2: Browser 44.dp.
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clip(Theme.shapes.small).clickable(
                        onClick = {
                            drawerOpen = false
                            navController.navigate(Dests.BROWSER)
                        },
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.foundation.LocalIndication.current,
                    ).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Globe,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Theme.colors.onSurface,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Browser",
                        style = Theme.typography.label,
                        color = Theme.colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.CaretRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Theme.colors.inkTertiary,
                    )
                }
                // Row 3: Memory 44.dp.
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clip(Theme.shapes.small).clickable(
                        onClick = {
                            drawerOpen = false
                            navController.navigate(Dests.MEMORY)
                        },
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.foundation.LocalIndication.current,
                    ).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Brain,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Theme.colors.onSurface,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Memories",
                        style = Theme.typography.label,
                        color = Theme.colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${state.memories.size}",
                        style = Theme.typography.caption,
                        color = Theme.colors.onBackgroundMuted,
                    )
                }
            }
            // Hairline Divider: 1.dp height, edge, 12.dp vertical padding.
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.colors.edge))
            }
            // Middle Scrollable Sessions List: weight 1f, 12.dp horizontal padding.
            val summaries = state.sessions
            if (summaries.isEmpty()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        "Sessions",
                        style = Theme.typography.caption,
                        color = Theme.colors.onBackgroundMuted,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Your past Sessions appear here.",
                            style = Theme.typography.caption,
                            color = Theme.colors.onBackgroundMuted,
                        )
                    }
                }
            } else {
                val nowMs = System.currentTimeMillis()
                val startOfToday =
                    java.util.Calendar.getInstance().let { cal ->
                        cal.timeInMillis = nowMs
                        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                        cal.set(java.util.Calendar.MINUTE, 0)
                        cal.set(java.util.Calendar.SECOND, 0)
                        cal.set(java.util.Calendar.MILLISECOND, 0)
                        cal.timeInMillis
                    }
                // Ticket #109 (T4): compact Session search above the list.
                SearchField(
                    value = sessionQuery,
                    onValueChange = { sessionQuery = it },
                    placeholder = "Search Sessions",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
                val filteredSummaries = filterSessionSummaries(summaries, sessionQuery)
                if (filteredSummaries.isEmpty()) {
                    Box(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No matching Sessions",
                            style = Theme.typography.caption,
                            color = Theme.colors.onBackgroundMuted,
                        )
                    }
                } else {
                    val today = filteredSummaries.filter { it.lastTimestampMs >= startOfToday }
                    val previous = filteredSummaries.filter { it.lastTimestampMs < startOfToday }
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    ) {
                    item {
                        Text(
                            "Sessions",
                            style = Theme.typography.caption,
                            color = Theme.colors.onBackgroundMuted,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    if (today.isNotEmpty()) {
                        item {
                            Text(
                                "Today",
                                style = Theme.typography.caption,
                                color = Theme.colors.inkTertiary,
                                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 4.dp),
                            )
                        }
                        items(today, key = { it.sessionId }) { session ->
                            SessionRow(
                                session = session,
                                active = session.sessionId == state.sessionId,
                                onOpen = {
                                    vm.switchSession(session.sessionId)
                                    drawerOpen = false
                                },
                                onDelete = { vm.deleteSession(session.sessionId) },
                            )
                        }
                    }
                    if (previous.isNotEmpty()) {
                        item {
                            Text(
                                "Previous",
                                style = Theme.typography.caption,
                                color = Theme.colors.inkTertiary,
                                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 4.dp),
                            )
                        }
                        items(previous, key = { it.sessionId }) { session ->
                            SessionRow(
                                session = session,
                                active = session.sessionId == state.sessionId,
                                onOpen = {
                                    vm.switchSession(session.sessionId)
                                    drawerOpen = false
                                },
                                onDelete = { vm.deleteSession(session.sessionId) },
                            )
                        }
                    }
                }
                }
            }
            // Bottom Pinned Profile / Settings Footer: 1.dp top divider, 64.dp height, 16.dp padding.
            Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.colors.edge))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp).clickable(
                    onClick = {
                        drawerOpen = false
                        navController.navigate(Dests.SETTINGS)
                    },
                    interactionSource = remember { MutableInteractionSource() },
                    indication = androidx.compose.foundation.LocalIndication.current,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Theme.colors.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.User,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Theme.colors.onSurface,
                    )
                }
                Column(
                    Modifier.weight(1f).padding(start = 12.dp),
                ) {
                    Text(
                        state.gmailAccount ?: "Not connected",
                        style = Theme.typography.label,
                        color = Theme.colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Settings & Memory",
                        style = Theme.typography.caption,
                        color = Theme.colors.onBackgroundMuted,
                    )
                }
                Icon(
                    Icons.SlidersHorizontal,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Theme.colors.onBackgroundMuted,
                )
            }
        }

        VoiceCommandSheet(
            visible = showVoice,
            state = state.voice,
            onDismiss = {
                // Ticket #107 (T2): dismissing the sheet cancels recognition
                // so the microphone never stays hot.
                onVoiceDismiss()
                showVoice = false
            },
            onMicTap = onMicTap,
            onUseTranscript = {
                val text = state.voice.transcript
                onVoiceDismiss()
                showVoice = false
                if (text.isNotBlank()) vm.sendCommand(text)
            },
            onTranscriptChange = vm::editVoiceTranscript,
        )
        EmailConfirmationSheet(
            draft = state.pendingEmail,
            from = state.gmailAccount,
            unseen = unseenRecipients(
                state.pendingEmail?.to.orEmpty(),
                knownAddressesFromInbox(state.emails),
            ),
            onApprove = vm::approveEmail,
            onReject = vm::rejectEmail,
        )
        ForgetMemorySheet(
            preview = state.pendingForget,
            onApprove = vm::approveForget,
            onReject = vm::rejectForget,
        )
    }
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    active: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val rowModifier =
        if (active) {
            Modifier.fillMaxWidth().height(44.dp).clip(Theme.shapes.small)
                .background(Theme.colors.surfaceVariant)
        } else {
            Modifier.fillMaxWidth().height(44.dp).clip(Theme.shapes.small)
                .background(androidx.compose.ui.graphics.Color.Transparent)
        }
    Row(
        rowModifier.clickable(
            onClick = onOpen,
            interactionSource = remember { MutableInteractionSource() },
            indication = androidx.compose.foundation.LocalIndication.current,
        ).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            session.title,
            style = if (active) Theme.typography.label else Theme.typography.body,
            color = if (active) Theme.colors.onSurface else Theme.colors.onBackgroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.size(44.dp).clickable(
                onClick = onDelete,
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.foundation.LocalIndication.current,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Trash,
                contentDescription = "Delete Session",
                modifier = Modifier.size(16.dp),
                tint = Theme.colors.inkTertiary,
            )
        }
    }
}
