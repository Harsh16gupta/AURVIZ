package com.aurviz.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aurviz.agent.OEM_AUTOSTART_GUIDANCE
import com.aurviz.agent.ui.AgentUiState
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ButtonVariant
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
import com.aurviz.agent.ui.theme.Waveform

// Preferences, Gmail sign-in, and latency telemetry. The loopback OAuth
// keeps its pasted-link fallback here in a collapsible section so a
// browser that never returns to the app cannot strand the user.
@Composable
fun SettingsScreen(
    state: AgentUiState,
    onConnectGmail: () -> Unit,
    onRevokeGmail: () -> Unit,
    onPasteLink: (String) -> Unit,
    onDismissPasteFallback: () -> Unit,
    onSaveLlmKeys: (String, String, String, String) -> Unit,
    onClearLlmKeys: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onRefreshExactAlarm: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRefreshBattery: () -> Unit,
    onVoiceSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // P2: key drafts and the pasted OAuth link are memory-only (remember,
    // never rememberSaveable) so they are never serialized into
    // saved-instance state. Fields start empty, clear on save, and clear
    // again when the screen leaves composition.
    var pasted by remember { mutableStateOf("") }
    var haptics by rememberSaveable { mutableStateOf(true) }
    // Key fields always start empty: saved keys are never read back into UI.
    var geminiKey by remember { mutableStateOf("") }
    var groqKey by remember { mutableStateOf("") }
    var deepseekKey by remember { mutableStateOf("") }
    var metaKey by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose {
            pasted = ""
            geminiKey = ""
            groqKey = ""
            deepseekKey = ""
            metaKey = ""
        }
    }

    LaunchedEffect(Unit) {
        onRefreshExactAlarm()
        onRefreshBattery()
    }

    Page(title = "Settings", onBack = onBack, modifier = modifier) {
        Section(
            "Gmail",
            footer = if (state.gmailConnected) {
                "Signed in as ${state.gmailAccount ?: "connected"}."
            } else {
                "Connect once — the login survives restarts."
            },
        ) {
            if (state.gmailConnected) {
                OptionRow(
                    "Gmail: ${state.gmailAccount ?: "connected"}",
                    onClick = {},
                    description = "Tap Revoke below to disconnect the mailbox.",
                    icon = Icons.EnvelopeSimple,
                )
                OptionRow(
                    "Revoke Gmail access",
                    onClick = onRevokeGmail,
                    destructive = true,
                )
            } else {
                OptionRow(
                    "Connect Gmail",
                    onClick = onConnectGmail,
                    description = "Google sign-in opens in your browser.",
                    icon = Icons.EnvelopeSimple,
                    navigates = true,
                )
            }
            OptionRow(
                if (state.pasteFallbackVisible) "Hide pasted-link fallback" else "Paste callback link instead",
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
        }
        Section(
            "AI keys",
            footer = "Keys stay encrypted on this phone and are never baked into the app. " +
                "Release builds ship with empty keys — enter them here to run the agent.",
        ) {
            OptionRow(
                "Gemini",
                onClick = {},
                description = "Default provider.",
                value = if (state.geminiKeySet) "Set" else "Not set",
            )
            TextField(
                value = geminiKey,
                onValueChange = { geminiKey = it },
                label = "Paste Gemini API key",
            )
            OptionRow(
                "Groq",
                onClick = {},
                description = "Fallback provider.",
                value = if (state.groqKeySet) "Set" else "Not set",
            )
            TextField(
                value = groqKey,
                onValueChange = { groqKey = it },
                label = "Paste Groq API key",
            )
            OptionRow(
                "DeepSeek",
                onClick = {},
                description = "DeepSeek flash provider.",
                value = if (state.deepseekKeySet) "Set" else "Not set",
            )
            TextField(
                value = deepseekKey,
                onValueChange = { deepseekKey = it },
                label = "Paste DeepSeek API key",
            )
            OptionRow(
                "Meta",
                onClick = {},
                description = "Meta Muse Spark provider.",
                value = if (state.metaKeySet) "Set" else "Not set",
            )
            TextField(
                value = metaKey,
                onValueChange = { metaKey = it },
                label = "Paste Meta API key",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
                Button(
                    onClick = {
                        onSaveLlmKeys(geminiKey, groqKey, deepseekKey, metaKey)
                        geminiKey = ""
                        groqKey = ""
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
                        groqKey = ""
                        deepseekKey = ""
                        metaKey = ""
                    },
                    variant = ButtonVariant.Secondary,
                ) {
                    Text("Clear keys")
                }
            }
        }
        Section("Performance") {
            OptionRow(
                "Last Plan generation",
                onClick = {},
                icon = Icons.Sparkle,
                value = state.perf.planMs?.let { "$it ms" } ?: "—",
            )
            OptionRow(
                "Last Tool execution",
                onClick = {},
                icon = Icons.Sparkle,
                value = state.perf.toolMs?.let { "$it ms" } ?: "—",
            )
            OptionRow(
                "Last Memory recall",
                onClick = {},
                icon = Icons.Sparkle,
                value = state.perf.recallMs?.let { "$it ms" } ?: "—",
            )
        }
        Section(
            "Schedules",
            footer = "Revoked permission falls back to inexact timing — " +
                "Schedules still run, just not exactly on time. " +
                "Android 12+ needs exact-alarm permission for on-time firing.",
        ) {
            if (state.exactAlarmNeeded) {
                OptionRow(
                    "Allow exact alarms",
                    onClick = onOpenExactAlarmSettings,
                    description = "Schedules still run without it — just not exactly on time.",
                    icon = Icons.Bell,
                    navigates = true,
                )
            } else {
                OptionRow(
                    "Exact alarms allowed",
                    onClick = {},
                    description = "Schedules fire at their saved time.",
                    icon = Icons.Bell,
                )
            }
        }
        Section(
            "Battery & background",
            footer = "Doze and vendor battery managers can delay Schedules. " +
                "Exempting Aurviz keeps fire times reliable.",
        ) {
            if (state.batteryOptimizationNeeded) {
                OptionRow(
                    "Allow background running",
                    onClick = onOpenBatterySettings,
                    description = "Battery optimization is restricting Aurviz — exempt it so Schedules fire on time.",
                    icon = Icons.Bell,
                    navigates = true,
                )
            } else {
                OptionRow(
                    "Background running allowed",
                    onClick = {},
                    description = "Battery optimization is not restricting Aurviz.",
                    icon = Icons.Bell,
                )
            }
            Text(
                OEM_AUTOSTART_GUIDANCE,
                color = Theme.colors.onBackgroundMuted,
            )
        }
        Section("Voice") {
            OptionRow(
                "Voice Command",
                onClick = onVoiceSettings,
                description = "Waveform, live transcription, hands-free options.",
                icon = Icons.Waveform,
                navigates = true,
            )
            OptionToggle(
                title = "Haptics",
                checked = haptics,
                onCheckedChange = { haptics = it },
                description = "Gentle buzz on send and confirm.",
            )
        }
    }
}
