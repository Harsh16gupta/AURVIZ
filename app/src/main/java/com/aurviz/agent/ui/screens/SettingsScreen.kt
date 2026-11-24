package com.aurviz.agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aurviz.agent.BuildConfig
import com.aurviz.agent.OEM_AUTOSTART_GUIDANCE
import com.aurviz.agent.ProviderSettings
import com.aurviz.agent.firstConfiguredProvider
import com.aurviz.agent.providerDisplayName
import com.aurviz.agent.toolLabel
import com.aurviz.agent.ui.AgentUiState
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ButtonVariant
import com.aurviz.agent.ui.components.CardDivider
import com.aurviz.agent.ui.components.ChoiceTile
import com.aurviz.agent.ui.components.HapticKind
import com.aurviz.agent.ui.components.LocalHaptics
import com.aurviz.agent.ui.components.ModalBottomSheet
import com.aurviz.agent.ui.components.OptionRow
import com.aurviz.agent.ui.components.OptionToggle
import com.aurviz.agent.ui.components.Page
import com.aurviz.agent.ui.components.Section
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.components.TextField
import com.aurviz.agent.ui.theme.Bell
import com.aurviz.agent.ui.theme.Brain
import com.aurviz.agent.ui.theme.DeviceMobile
import com.aurviz.agent.ui.theme.EnvelopeSimple
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Theme
import com.aurviz.agent.ui.theme.ThemeMode
import com.aurviz.agent.ui.theme.Waveform

// Preferences, Gmail sign-in, and latency telemetry. The loopback OAuth
// keeps its pasted link fallback here in a collapsible section so a
// browser that never returns to the app cannot strand the user.
//
// Ticket #90: the three provider key forms collapse to 52dp disclosure
// rows opening one reusable masked key-editor sheet; Gmail identity sits
// inside the connection block; telemetry renders as labeled progress
// bars with Last + Avg; the OEM prose folds into one accordion row.
@Composable
fun SettingsScreen(
    state: AgentUiState,
    onConnectGmail: () -> Unit,
    onRevokeGmail: () -> Unit,
    onPasteLink: (String) -> Unit,
    onDismissPasteFallback: () -> Unit,
    onSaveSingleLlmKey: (String, String) -> Unit,
    onClearSingleLlmKey: (String) -> Unit,
    onSetSelectedProvider: (String?) -> Unit = {},
    onOpenExactAlarmSettings: () -> Unit,
    onRefreshExactAlarm: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRefreshBattery: () -> Unit,
    onVoiceSettings: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit = {},
    onSetHapticsEnabled: (Boolean) -> Unit = {},
    onAllowAlwaysAdd: (String) -> Unit = {},
    onAllowAlwaysRevoke: (String) -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // P2: drafts and the pasted OAuth link are memory-only (remember,
    // never rememberSaveable) so they are never serialized into
    // saved-instance state. Fields start empty, clear on save, and clear
    // again when the screen leaves composition. Saved keys are never read
    // back into UI — the sheet only ever shows Set / Not set.
    var pasted by remember { mutableStateOf("") }
    // Ticket #68: allow-always grant draft. An address is not a secret,
    // so it may live in composition state (unlike the key fields above).
    var newAllow by remember { mutableStateOf("") }
    // Ticket #90: which provider the single key-editor sheet is editing
    // (null = closed), plus its memory-only paste draft.
    var keyEditor by remember { mutableStateOf<String?>(null) }
    var keyDraft by remember { mutableStateOf("") }
    var guideExpanded by remember { mutableStateOf(false) }
    val haptics = LocalHaptics.current

    DisposableEffect(Unit) {
        onDispose {
            pasted = ""
            newAllow = ""
            keyDraft = ""
            keyEditor = null
        }
    }

    LaunchedEffect(Unit) {
        onRefreshExactAlarm()
        onRefreshBattery()
    }

    // Ticket #90: the visible Default mirrors the auto-select seam — the
    // preferred provider while its key is Set, else the first Set provider.
    // Flags only (never key values) decide the badge.
    val preferredName =
        (state.selectedProvider ?: BuildConfig.LLM_DEFAULT_PROVIDER).trim().lowercase()
            .takeIf { it in setOf("gemini", "deepseek", "meta") } ?: "gemini"
    val defaultName =
        firstConfiguredProvider(
            preferredName,
            ProviderSettings(
                geminiKey = if (state.geminiKeySet) "set" else "",
                deepseekKey = if (state.deepseekKeySet) "set" else "",
                metaKey = if (state.metaKeySet) "set" else "",
            ),
        )

    fun isSet(name: String): Boolean =
        when (name) {
            "deepseek" -> state.deepseekKeySet
            "meta" -> state.metaKeySet
            else -> state.geminiKeySet
        }

    fun providerLabel(name: String): String =
        when (name) {
            "deepseek" -> "DeepSeek"
            "meta" -> "Meta"
            else -> "Gemini"
        }

    Page(title = "Settings", modifier = modifier, onBack = onBack) {
        Section("Appearance & Feedback") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(label, style = Theme.typography.label)
                        }
                    }
                }
            }
            CardDivider()
            OptionToggle(
                title = "Haptics",
                checked = state.hapticsEnabled,
                onCheckedChange = onSetHapticsEnabled,
                description = "Gentle buzz on send and confirm.",
            )
        }
        Section("Personalization") {
            OptionRow(
                title = "Memory",
                description = "${state.memories.size} saved facts & Preferences",
                icon = Icons.Brain,
                onClick = onOpenMemory,
                navigates = true,
            )
        }
        Section(
            "Connections",
            footer = if (state.gmailConnected) {
                null
            } else {
                "Connect once — the login survives restarts."
            },
        ) {
            if (state.gmailConnected) {
                OptionRow(
                    title = "Gmail",
                    description = "Signed in as ${state.gmailAccount ?: "connected"} · " +
                        "Scopes: gmail.readonly · gmail.send.",
                    icon = Icons.EnvelopeSimple,
                )
                CardDivider()
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
            CardDivider()
            OptionRow(
                title = if (state.pasteFallbackVisible) "Hide pasted-link fallback" else "Paste callback link instead",
                onClick = {
                    if (state.pasteFallbackVisible) onDismissPasteFallback() else onConnectGmail()
                },
                description = "Use when the browser never returns to Aurviz.",
                navigates = true,
            )
            if (state.pasteFallbackVisible) {
                CardDivider()
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        state.loopbackHint.ifBlank { "Finish in the newest browser tab, then paste the full address-bar link here." },
                        style = Theme.typography.caption,
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
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) {
                            Text("Use pasted link")
                        }
                        Button(
                            onClick = onDismissPasteFallback,
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
            }
            CardDivider()
            listOf("gemini", "deepseek", "meta").forEachIndexed { index, name ->
                if (index > 0) CardDivider()
                val set = isSet(name)
                OptionRow(
                    title = providerLabel(name),
                    onClick = {
                        keyDraft = ""
                        keyEditor = name
                    },
                    value = when {
                        set && name == defaultName -> "Set · Default"
                        set -> "Set"
                        name == defaultName -> "Not set · Default"
                        else -> "Not set"
                    },
                    navigates = true,
                )
            }
        }
        Section("Performance Telemetry") {
            val perf = state.perf
            val maxScaleMs = maxOf(
                perf.planMs ?: 0L,
                perf.toolMs ?: 0L,
                perf.recallMs ?: 0L,
                perf.planAvgMs ?: 0L,
                perf.toolAvgMs ?: 0L,
                perf.recallAvgMs ?: 0L,
                1000L,
            ).toFloat()
            TelemetryBar("Plan", perf.planMs, perf.planAvgMs, maxScaleMs)
            CardDivider()
            TelemetryBar("Tool", perf.toolMs, perf.toolAvgMs, maxScaleMs)
            CardDivider()
            TelemetryBar("Recall", perf.recallMs, perf.recallAvgMs, maxScaleMs)
            CardDivider()
            OptionRow(
                title = "Provider",
                description = "${state.interactions.size} Interactions recorded · " +
                    "${(perf.successRate * 100).toInt()}% success over ${perf.runs} runs.",
                value = providerDisplayName(
                    state.selectedProvider ?: state.providerUsed ?: BuildConfig.LLM_DEFAULT_PROVIDER,
                ),
            )
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
            CardDivider()
            OptionRow(
                title = "Read emails",
                description = "Mailbox reads run without a Confirmation.",
                value = "Auto-run",
            )
            CardDivider()
            OptionRow(
                title = "Forget Memory",
                description = "Deletions preview before anything is removed.",
                value = "Preview & Confirm",
            )
            CardDivider()
            OptionRow(
                title = "Scheduled writes",
                description = "Unattended writes wait in Schedules.",
                value = "Hold for Confirmation",
            )
            CardDivider()
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
                CardDivider()
                OptionRow(
                    title = address,
                    description = "Tap to revoke — takes effect immediately.",
                    onClick = { onAllowAlwaysRevoke(address) },
                    destructive = true,
                )
            }
            CardDivider()
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("Always allow")
                }
            }
        }
        Section(
            "Permissions & Background",
            footer = "Without the system permission, Schedules still run via inexact timing — " +
                "just not exactly on time.",
        ) {
            if (state.exactAlarmNeeded) {
                OptionRow(
                    title = "On-time Schedules",
                    onClick = onOpenExactAlarmSettings,
                    description = "Schedules still run without it — just not exactly on time.",
                    icon = Icons.Bell,
                    value = "Action needed",
                    valueColor = Theme.colors.warning,
                    navigates = true,
                )
            } else {
                OptionRow(
                    title = "On-time Schedules",
                    description = "Schedules fire at their saved time.",
                    icon = Icons.Bell,
                    value = "Granted",
                    valueColor = Theme.colors.success,
                )
            }
            CardDivider()
            if (state.batteryOptimizationNeeded) {
                OptionRow(
                    title = "Background running",
                    onClick = onOpenBatterySettings,
                    description = "Battery optimization is restricting Aurviz — exempt it so Schedules fire on time.",
                    icon = Icons.DeviceMobile,
                    value = "Action needed",
                    valueColor = Theme.colors.warning,
                    navigates = true,
                )
            } else {
                OptionRow(
                    title = "Background running",
                    description = "Battery optimization is not restricting Aurviz.",
                    icon = Icons.DeviceMobile,
                    value = "Granted",
                    valueColor = Theme.colors.success,
                )
            }
            CardDivider()
            OptionRow(
                title = "Voice Command",
                onClick = onVoiceSettings,
                description = "Live waveform and Voice Command preferences.",
                icon = Icons.Waveform,
                navigates = true,
            )
            CardDivider()
            OptionRow(
                title = "Device battery & autostart guide",
                onClick = { guideExpanded = !guideExpanded },
                description = "Vendor steps for Xiaomi, Oppo, Vivo, Huawei, Samsung.",
                navigates = true,
            )
            if (guideExpanded) {
                CardDivider()
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        OEM_AUTOSTART_GUIDANCE,
                        style = Theme.typography.caption,
                        color = Theme.colors.onBackgroundMuted,
                    )
                }
            }
        }
    }

    // Ticket #90: one reusable masked key-editor sheet for all three
    // providers. Saved keys are never shown — only Set / Not set.
    val editing = keyEditor
    ModalBottomSheet(
        visible = editing != null,
        onDismiss = {
            keyEditor = null
            keyDraft = ""
        },
        title = if (editing != null) "${providerLabel(editing)} key" else "Provider key",
    ) {
        if (editing != null) {
            val set = isSet(editing)
            Text(
                if (set) {
                    "Key saved — paste a new key to replace it. Saved keys are never shown."
                } else {
                    "No key saved yet — paste a key to connect ${providerLabel(editing)}."
                },
                color = Theme.colors.onBackgroundMuted,
            )
            TextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                label = "Paste ${providerLabel(editing)} API key",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space * 2)) {
                Button(
                    onClick = {
                        onSaveSingleLlmKey(editing, keyDraft)
                        keyDraft = ""
                        keyEditor = null
                    },
                    enabled = keyDraft.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save")
                }
                Button(
                    onClick = {
                        onClearSingleLlmKey(editing)
                        keyDraft = ""
                        keyEditor = null
                    },
                    variant = ButtonVariant.Secondary,
                    enabled = set,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clear")
                }
            }
            OptionRow(
                title = "Set Default",
                onClick = {
                    onSetSelectedProvider(editing)
                    keyDraft = ""
                    keyEditor = null
                },
                description = "First choice when its key is Set.",
                value = if (editing == defaultName) "Default" else null,
                navigates = true,
            )
        }
    }
}

// Ticket #90: one ChatGPT-Usage-style labeled progress bar per stage —
// Last + Avg ms with proportional fill against the slowest stage. The Last
// (else Avg) sample fills a 4dp track as its share of maxScaleMs.
// Foundation Box only; no new icons.
@Composable
private fun TelemetryBar(
    label: String,
    lastMs: Long?,
    avgMs: Long?,
    maxScaleMs: Float = 4000f,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = Theme.typography.label)
            Text(
                "Last ${lastMs?.let { "$it ms" } ?: "—"} · Avg ${avgMs?.let { "$it ms" } ?: "—"}",
                style = Theme.typography.caption,
                color = Theme.colors.onBackgroundMuted,
            )
        }
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(4.dp)
                    .clip(Theme.shapes.fullyRounded)
                    .background(Theme.colors.surfaceVariant),
        ) {
            val sample = lastMs ?: avgMs
            val fraction = if (sample == null) 0f else (sample.toFloat() / maxScaleMs).coerceIn(0.06f, 1f)
            if (fraction > 0f) {
                Box(
                    modifier =
                        Modifier.fillMaxWidth(fraction)
                            .height(4.dp)
                            .clip(Theme.shapes.fullyRounded)
                            .background(Theme.colors.primary),
                )
            }
        }
    }
}
