package com.aurviz.agent.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aurviz.agent.knownAddressesFromInbox
import com.aurviz.agent.ui.components.HapticKind
import com.aurviz.agent.ui.components.Icon
import com.aurviz.agent.unseenRecipients
import com.aurviz.agent.ui.components.IconButton
import com.aurviz.agent.ui.components.LocalHaptics
import com.aurviz.agent.ui.components.ScreenScaffold
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.screens.BrowserScreen
import com.aurviz.agent.ui.screens.CommandScreen
import com.aurviz.agent.ui.screens.MemoryScreen
import com.aurviz.agent.ui.screens.SchedulesScreen
import com.aurviz.agent.ui.screens.SettingsScreen
import com.aurviz.agent.ui.sheets.EmailConfirmationSheet
import com.aurviz.agent.ui.sheets.ForgetMemorySheet
import com.aurviz.agent.ui.sheets.VoiceCommandSheet
import com.aurviz.agent.ui.theme.Bell
import com.aurviz.agent.ui.theme.Brain
import com.aurviz.agent.ui.theme.ChatCircle
import com.aurviz.agent.ui.theme.Globe
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Theme
import com.aurviz.agent.ui.theme.User

object Dests {
    const val COMMAND = "command"
    const val BROWSER = "browser"
    const val SCHEDULES = "schedules"
    const val MEMORY = "memory"
    const val SETTINGS = "settings"
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

// Destination labels follow GLOSSARY.md: Command, Browser, Schedules,
// Memory, Settings. A Confirmation never navigates — it opens a sheet.
private val Tabs =
    listOf(
        Tab(Dests.COMMAND, "Command", Icons.ChatCircle),
        Tab(Dests.BROWSER, "Browser", Icons.Globe),
        Tab(Dests.SCHEDULES, "Schedules", Icons.Bell),
        Tab(Dests.MEMORY, "Memory", Icons.Brain),
        Tab(Dests.SETTINGS, "Settings", Icons.User),
    )

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
    val haptics = LocalHaptics.current
    var showVoice by rememberSaveable { mutableStateOf(false) }
    val goCommand: () -> Unit = {
        if (!navController.popBackStack()) navController.navigate(Dests.COMMAND)
    }

    // One-shot: the ViewModel exposes the loopback OAuth URL; the Activity
    // context opens it in a browser, then the event is consumed.
    LaunchedEffect(state.authUrl) {
        val url = state.authUrl ?: return@LaunchedEffect
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        vm.consumeAuthUrl()
    }

    ScreenScaffold(modifier) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                NavHost(navController = navController, startDestination = Dests.COMMAND) {
                    composable(Dests.COMMAND) {
                        CommandScreen(
                            state = state,
                            onInputChange = vm::onInputChange,
                            onSend = vm::sendCommand,
                            onRetry = vm::retry,
                            onMicTap = onMicTap,
                            onVoiceClick = { showVoice = true },
                            onOpenBrowser = { navController.navigate(Dests.BROWSER) },
                            onApprovePending = vm::approvePendingSend,
                            onApproveEdited = vm::approvePendingSendEdited,
                            onRejectPending = vm::rejectPendingSend,
                            onCancelRun = vm::cancelRun,
                            onSelectProvider = vm::setSelectedProvider,
                        )
                    }
                    composable(Dests.BROWSER) {
                        BrowserScreen(
                            state = state,
                            onOpenUrl = vm::openWeb,
                            onAttachBridge = vm::attachWebBridge,
                            onBack = goCommand,
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
                            onBack = goCommand,
                        )
                    }
                    composable(Dests.MEMORY) {
                        MemoryScreen(
                            state = state,
                            onRemember = { vm.sendCommand("remember $it") },
                            onForget = vm::deleteMemory,
                            onBack = goCommand,
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
                            onSaveLlmKeys = vm::saveLlmKeys,
                            onClearLlmKeys = vm::clearLlmKeys,
                            onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                            onRefreshExactAlarm = vm::refreshExactAlarmFlag,
                            onOpenBatterySettings = onOpenBatterySettings,
                            onRefreshBattery = vm::refreshBatteryFlag,
                            onVoiceSettings = { showVoice = true },
                            onBack = goCommand,
                            onSetThemeMode = vm::setThemeMode,
                            onSetHapticsEnabled = vm::setHapticsEnabled,
                            onSetConfirmSendEmail = vm::setConfirmSendEmail,
                        )
                    }
                }
            }
            BottomBar(
                currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route,
                showScheduleDot = state.pendingApprovals.isNotEmpty(),
                onSelect = { route ->
                    haptics?.play(HapticKind.Selection)
                    navController.navigate(route) {
                        popUpTo(Dests.COMMAND) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
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
private fun BottomBar(
    currentRoute: String?,
    onSelect: (String) -> Unit,
    showScheduleDot: Boolean = false,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(Theme.colors.panel),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .height(0.5.dp)
                .background(Theme.colors.edge),
        )
        Row(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Space * 2, vertical = Space * 2),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tabs.forEach { tab ->
                val selected = currentRoute == tab.route
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space * 0.5f),
                ) {
                    Box {
                        IconButton(
                            onClick = { onSelect(tab.route) },
                            contentDescription = tab.label,
                            size = com.aurviz.agent.ui.components.IconButtonSize.Medium,
                        ) {
                            Icon(
                                tab.icon,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = if (selected) Theme.colors.primary else Theme.colors.onBackgroundMuted,
                            )
                        }
                        if (tab.route == Dests.SCHEDULES && showScheduleDot) {
                            Box(
                                Modifier.align(Alignment.TopEnd)
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Theme.colors.warning),
                            )
                        }
                    }
                    Text(
                        tab.label,
                        style = Theme.typography.caption,
                        color = if (selected) Theme.colors.primary else Theme.colors.onBackgroundMuted,
                    )
                }
            }
        }
    }
}
