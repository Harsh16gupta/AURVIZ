package com.aurviz.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurviz.agent.BuildConfig
import com.aurviz.agent.OEM_AUTOSTART_GUIDANCE
import com.aurviz.agent.toolLabel
import com.aurviz.agent.ui.AgentUiState
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ButtonVariant
import com.aurviz.agent.ui.components.ChoiceTile
import com.aurviz.agent.ui.components.HapticKind
import com.aurviz.agent.ui.components.LocalHaptics
import com.aurviz.agent.ui.components.OptionGroup
import com.aurviz.agent.ui.components.OptionRow
import com.aurviz.agent.ui.components.OptionToggle
import com.aurviz.agent.ui.components.Page
import com.aurviz.agent.ui.components.Section
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.components.TextField
import com.aurviz.agent.ui.theme.Bell
import com.aurviz.agent.ui.theme.EnvelopeSimple
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Sparkle
import com.aurviz.agent.ui.theme.Theme
import com.aurviz.agent.ui.theme.ThemeMode
import com.aurviz.agent.ui.theme.Waveform

// Preferences, Gmail sign-in, and latency telemetry. The loopback OAuth
// keeps its pasted link fallback here in a collapsible section so a
// browser that never returns to the app cannot strand the user.
@Composable
fun SettingsScreen(
    state: AgentUiState,
    onConnectGmail: () -> Unit,
    onRevokeGmail: () -> Unit,
    onPasteLink: (String) -> Unit,
    onDismissPasteFallback: () -> Unit,
    onSaveLlmKeys: (String, String, String) -> Unit,
    onClearLlmKeys: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onRefreshExactAlarm: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRefreshBattery: () -> Unit,
    onVoiceSettings: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit = {},
    onSetHapticsEnabled: (Boolean) -> Unit = {},
    onAllowAlwaysAdd: (String) -> Unit = {},
    onAllowAlwaysRevoke: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // P2: key drafts and the pasted OAuth link are memory-only (remember,
    // never rememberSaveable) so they are never serialized into
    // saved-instance state. Fields start empty, clear on save, and clear
    // again when the screen leaves composition.
    var pasted by remember { mutableStateOf("") }
    // Ticket #68: allow-always grant draft. An address is not a secret,
    // so it may live in composition state (unlike the key fields above).
    var newAllow by remember { mutableStateOf("") }
    // Key fields always start empty: saved keys are never read back into UI.
    var geminiKey by remember { mutableStateOf("") }
    var deepseekKey by remember { mutableStateOf("") }
    var metaKey by remember { mutableStateOf("") }
    val haptics = LocalHaptics.current

    DisposableEffect(Unit) {
        onDispose {
            pasted = ""
            geminiKey = ""
            deepseekKey = ""
            metaKey = ""
        }
    }

    LaunchedEffect(Unit) {
        onRefreshExactAlarm()
        onRefreshBattery()
    }

    Page(title = "Settings", modifier = modifier) {
        Section("Appearance & Feedback") {
            Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
                listOf(
                    ThemeMode.SYSTEM to "System",
                    ThemeMode.DARK to "Dark",
                    ThemeMode.LIGHT to "Light",
                ).forEach { (mode, label) ->
                    ChoiceTile(
                        selected = state.themeMode == mode,
                        onClick = {
                            haptics?.play(HapticKind.Selection)
                            onSetThemeMode(mode)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(label, style = Theme.typography.label)
                    }
                }
            }
            OptionToggle(
                title = "Haptics",
                checked = state.hapticsEnabled,
                onCheckedChange = onSetHapticsEnabled,
                description = "Gentle buzz on send and confirm.",
            )
        }
        Section(
            "Connections",
            footer = if (state.gmailConnected) {
                "Signed in as ${state.gmailAccount ?: "connected"} · Scopes: gmail.readonly · gmail.send."
            } else {
                "Connect once — the login survives restarts."
            },
        ) {
            if (state.gmailConnected) {
                OptionRow(
                    title = "Gmail: ${state.gmailAccount ?: "connected"}",
                    description = "Tap Revoke below to disconnect the mailbox.",
                    icon = Icons.EnvelopeSimple,
                )
                OptionRow(
                    title = "Revoke Gmail access",
                    onClick = onRevokeGmail,
                    destructive = true,
                )
            } else {
                OptionRow(
                    title = "Connect Gmail",
                    onClick = onConnectGmail,
                    description = "Google sign-in opens in your browser.",
                    icon = Icons.EnvelopeSimple,
                    navigates = true,
                )
            }
            OptionRow(
                title = if (state.pasteFallbackVisible) "Hide pasted-link fallback" else "Paste callback link instead",
                onClick = {
                    if (state.pasteFallbackVisible) onDismissPasteFallback() else onConnectGmail()
                },
                description = "Use when the browser never returns to Aurviz.",
                navigates = true,
            )
            if (state.pasteFallbackVisible) {
                Text(
                    state.loopbackHint.ifBlank { "Finish in the newest browser tab, then paste the full address-bar link here." },
                    color = Theme.colors.onBackgroundMuted,
                )
                TextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = "Paste the callback link (?code=…)",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
                    Button(
                        onClick = {
                            onPasteLink(pasted)
                            pasted = ""
                        },
                    ) {
                        Text("Use pasted link")
                    }
                    Button(
                        onClick = onDismissPasteFallback,
                        variant = ButtonVariant.Secondary,
                    ) {
                        Text("Dismiss")
                    }
                }
            }
            OptionRow(
                title = "Gemini",
                description = "Default provider.",
                badge = if (state.geminiKeySet) "Set" else null,
                value = if (state.geminiKeySet) null else "Not set",
            )
            TextField(
                value = geminiKey,
                onValueChange = { geminiKey = it },
                label = "Paste Gemini API key",
            )
            OptionRow(
                title = "DeepSeek",
                description = "DeepSeek flash provider.",
                badge = if (state.deepseekKeySet) "Set" else null,
                value = if (state.deepseekKeySet) null else "Not set",
            )
            TextField(
                value = deepseekKey,
                onValueChange = { deepseekKey = it },
                label = "Paste DeepSeek API key",
            )
            OptionRow(
                title = "Meta",
                description = "Meta Muse Spark provider.",
                badge = if (state.metaKeySet) "Set" else null,
                value = if (state.metaKeySet) null else "Not set",
            )
            TextField(
                value = metaKey,
                onValueChange = { metaKey = it },
                label = "Paste Meta API key",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
                Button(
                    onClick = {
                        onSaveLlmKeys(geminiKey, deepseekKey, metaKey)
                        geminiKey = ""
                        deepseekKey = ""
                        metaKey = ""
                    },
                ) {
                    Text("Save keys")
                }
                Button(
                    onClick = {
                        onClearLlmKeys()
                        geminiKey = ""
                        deepseekKey = ""
                        metaKey = ""
                    },
                    variant = ButtonVariant.Secondary,
                ) {
                    Text("Clear keys")
                }
            }
        }
        Section("Confirmation Rules") {
            // send_email is always gated by ToolRegistry.executeGated: the
            // gate suspends until the user approves, and SendPolicy
            // re-checks before the transport. There is no bypass, so this
            // is a read-only Enforced row — never a toggle.
            OptionRow(
                title = toolLabel("send_email"),
                description = "Always requires explicit Confirmation",
                value = "Enforced",
            )
            OptionRow(
                title = "Read Emails",
                description = "Mailbox reads run without a Confirmation.",
                value = "Auto-run",
            )
            OptionRow(
                title = "Forget Memory",
                description = "Deletions preview before anything is removed.",
                value = "Preview & Confirm",
            )
            OptionRow(
                title = "Scheduled Writes",
                description = "Unattended writes wait in Schedules.",
                value = "Hold for Confirmation",
            )
            // Ticket #68: revocable per-recipient allow-always grants
            // (GLOSSARY Preference). One listed address skips the send
            // card; revocation applies to the next send. SendPolicy still
            // blocks bulk and forwarded mail for listed addresses.
            OptionRow(
                title = "Always-allowed recipients",
                description = "Sends to one listed address skip the card. Policy still applies.",
                value = if (state.allowAlways.isEmpty()) "None" else "${state.allowAlways.size} set",
            )
            state.allowAlways.sorted().forEach { address ->
                OptionRow(
                    title = address,
                    description = "Tap to revoke — takes effect immediately.",
                    onClick = { onAllowAlwaysRevoke(address) },
                    destructive = true,
                )
            }
            TextField(
                value = newAllow,
                onValueChange = { newAllow = it },
                label = "Always allow sends to…",
            )
            Button(
                onClick = {
                    onAllowAlwaysAdd(newAllow)
                    newAllow = ""
                },
            ) {
                Text("Always allow")
            }
        }
        Section(
            "Permissions & Background",
            footer = "Revoked permission falls back to inexact timing — " +
                "Schedules still run, just not exactly on time. " +
                "Android 12+ needs exact-alarm permission for on-time firing. " +
                "Doze and vendor battery managers can delay Schedules. " +
                OEM_AUTOSTART_GUIDANCE,
        ) {
            if (state.exactAlarmNeeded) {
                OptionRow(
                    title = "Exact alarms",
                    onClick = onOpenExactAlarmSettings,
                    description = "Schedules still run without it — just not exactly on time.",
                    icon = Icons.Bell,
                    value = "Action needed",
                    valueColor = Theme.colors.warning,
                    navigates = true,
                )
            } else {
                OptionRow(
                    title = "Exact alarms",
                    description = "Schedules fire at their saved time.",
                    icon = Icons.Bell,
                    value = "Granted",
                    valueColor = Theme.colors.success,
                )
            }
            if (state.batteryOptimizationNeeded) {
                OptionRow(
                    title = "Background running",
                    onClick = onOpenBatterySettings,
                    description = "Battery optimization is restricting Aurviz — exempt it so Schedules fire on time.",
                    icon = Icons.Bell,
                    value = "Action needed",
                    valueColor = Theme.colors.warning,
                    navigates = true,
                )
            } else {
                OptionRow(
                    title = "Background running",
                    description = "Battery optimization is not restricting Aurviz.",
                    icon = Icons.Bell,
                    value = "Granted",
                    valueColor = Theme.colors.success,
                )
            }
            OptionRow(
                title = "Voice Command",
                onClick = onVoiceSettings,
                description = "Waveform, live transcription, hands-free options.",
                icon = Icons.Waveform,
                navigates = true,
            )
        }
        Section("Performance Telemetry") {
            OptionGroup {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    MetricColumn("Plan", state.perf.planMs)
                    MetricColumn("Tool", state.perf.toolMs)
                    MetricColumn("Recall", state.perf.recallMs)
                }
            }
            OptionRow(
                title = "Provider",
                description = "${state.interactions.size} Interactions recorded",
                value = state.selectedProvider ?: state.providerUsed ?: BuildConfig.LLM_DEFAULT_PROVIDER,
            )
        }
    }
}

@Composable
private fun MetricColumn(label: String, ms: Long?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = Theme.typography.caption,
            color = Theme.colors.onBackgroundMuted,
        )
        Text(
            ms?.let { "$it ms" } ?: "—",
            style = Theme.typography.code,
            color = Theme.colors.onBackground,
        )
    }
}
