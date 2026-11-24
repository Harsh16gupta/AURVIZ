package com.aurviz.agent.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.aurviz.agent.knownAddressesFromInbox
import com.aurviz.agent.unseenRecipients
import com.aurviz.agent.ui.components.ScreenScaffold
import com.aurviz.agent.ui.components.SideDrawer
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.components.Icon
import com.aurviz.agent.ui.screens.BrowserScreen
import com.aurviz.agent.ui.screens.CommandScreen
import com.aurviz.agent.ui.screens.MemoryScreen
import com.aurviz.agent.ui.screens.OnboardingScreen
import com.aurviz.agent.ui.screens.SchedulesScreen
import com.aurviz.agent.ui.screens.SettingsScreen
import com.aurviz.agent.ui.sheets.EmailConfirmationSheet
import com.aurviz.agent.ui.sheets.ForgetMemorySheet
import com.aurviz.agent.ui.sheets.VoiceCommandSheet
import com.aurviz.agent.ui.theme.Bell
import com.aurviz.agent.ui.theme.CaretRight
import com.aurviz.agent.ui.theme.Globe
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.NotePencil
import com.aurviz.agent.ui.theme.SlidersHorizontal
import com.aurviz.agent.ui.theme.Theme
import com.aurviz.agent.ui.theme.Trash
import com.aurviz.agent.ui.theme.User
import com.aurviz.agent.ui.theme.VelaMark

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
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showVoice by rememberSaveable { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.authUrl) {
        val url = state.authUrl ?: return@LaunchedEffect
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        vm.consumeAuthUrl()
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
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Dests.MEMORY) {
                    MemoryScreen(
                        state = state,
                        onRemember = { vm.sendCommand("remember $it") },
                        onForget = vm::deleteMemory,
                        onUpdateMemory = vm::updateMemory,
                        onRestoreMemory = vm::restoreMemory,
                        onBack = { navController.popBackStack() },
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
                        onBack = { navController.popBackStack() },
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
                Icon(
                    Icons.VelaMark,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = Theme.colors.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text("AURVIZ", style = Theme.typography.title, color = Theme.colors.onSurface)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(40.dp).clip(Theme.shapes.fullyRounded).clickable(
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
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Row 1: Schedules 48.dp.
                Row(
                    Modifier.fillMaxWidth().height(48.dp).clip(Theme.shapes.small).clickable(
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
                // Row 2: Browser 48.dp.
                Row(
                    Modifier.fillMaxWidth().height(48.dp).clip(Theme.shapes.small).clickable(
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
                val today = summaries.filter { it.lastTimestampMs >= startOfToday }
                val previous = summaries.filter { it.lastTimestampMs < startOfToday }
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
            // Bottom Pinned Profile / Settings Footer: 1.dp top divider, 64.dp height, 16.dp padding.
            Box(Modifier.fillMaxWidth().height(1.dp).background(Theme.colors.edge))
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp).clickable(
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
                    Modifier.size(36.dp).clip(CircleShape).background(Theme.colors.surfaceVariant)
                        .border(1.dp, Theme.colors.edge, CircleShape),
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
            onDismiss = { showVoice = false },
            onMicTap = onMicTap,
            onUseTranscript = {
                val text = state.voice.transcript
                showVoice = false
                if (text.isNotBlank()) vm.sendCommand(text)
            },
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
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(Theme.shapes.small)
                .background(Theme.colors.surfaceVariant).border(1.dp, Theme.colors.edge, Theme.shapes.small)
        } else {
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(Theme.shapes.small)
                .background(androidx.compose.ui.graphics.Color.Transparent)
        }
    Row(
        rowModifier.clickable(
            onClick = onOpen,
            interactionSource = remember { MutableInteractionSource() },
            indication = androidx.compose.foundation.LocalIndication.current,
        ).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            session.title,
            style = Theme.typography.body,
            color = Theme.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.size(32.dp).clickable(
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
