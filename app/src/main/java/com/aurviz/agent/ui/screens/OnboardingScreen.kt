package com.aurviz.agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.AgentUiState
import com.aurviz.agent.ui.components.Button
import com.aurviz.agent.ui.components.ButtonVariant
import com.aurviz.agent.ui.components.Icon
import com.aurviz.agent.ui.components.ScreenScaffold
import com.aurviz.agent.ui.components.Text
import com.aurviz.agent.ui.theme.Bell
import com.aurviz.agent.ui.theme.Brain
import com.aurviz.agent.ui.theme.CaretLeft
import com.aurviz.agent.ui.theme.DeviceMobile
import com.aurviz.agent.ui.theme.GoogleLogo
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.LockSimple
import com.aurviz.agent.ui.theme.Sparkle
import com.aurviz.agent.ui.theme.Theme
import com.aurviz.agent.ui.theme.VelaMark

// First-run 3-step onboarding: welcome → connect agent → system
// permissions. Foundation only; no Material dependency.
@Composable
fun OnboardingScreen(
    state: AgentUiState,
    onConnectGmail: () -> Unit,
    onPasteLink: (String) -> Unit,
    onDismissPasteFallback: () -> Unit,
    onSaveSingleLlmKey: (String, String) -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableIntStateOf(0) }

    ScreenScaffold(modifier = modifier) {
        Column(
            Modifier.fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp),
        ) {
            // Top bar: 56dp — back, progress segments, Skip.
            Row(
                Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    Box(
                        Modifier.size(48.dp)
                            .clip(Theme.shapes.fullyRounded)
                            .clickable(
                                onClick = { step -= 1 },
                                interactionSource = remember { MutableInteractionSource() },
                                indication = androidx.compose.foundation.LocalIndication.current,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.CaretLeft,
                            contentDescription = "Back",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (i in 0..2) {
                        Box(
                            Modifier.width(36.dp).height(4.dp)
                                .clip(Theme.shapes.fullyRounded)
                                .background(
                                    if (i <= step) Theme.colors.primary
                                    else Theme.colors.surfaceVariant,
                                ),
                        )
                    }
                }
                Box(
                    Modifier.heightIn(min = 48.dp)
                        .clip(Theme.shapes.fullyRounded)
                        .clickable(
                            onClick = onComplete,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = androidx.compose.foundation.LocalIndication.current,
                        )
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Skip",
                        style = Theme.typography.label,
                        color = Theme.colors.onBackgroundMuted,
                    )
                }
            }

            // Middle scrollable content.
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
            ) {
                when (step) {
                    0 -> OnboardingWelcome()
                    1 -> OnboardingConnect(
                        state = state,
                        onConnectGmail = onConnectGmail,
                        onPasteLink = onPasteLink,
                        onDismissPasteFallback = onDismissPasteFallback,
                        onSaveSingleLlmKey = onSaveSingleLlmKey,
                    )
                    else -> OnboardingPermissions(
                        state = state,
                        onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                        onOpenBatterySettings = onOpenBatterySettings,
                    )
                }
            }

            // Bottom pinned primary CTA: 48dp.
            val ctaLabel =
                when (step) {
                    0 -> "Get started"
                    1 ->
                        if (state.geminiKeySet || state.deepseekKeySet ||
                            state.metaKeySet || state.gmailConnected
                        ) {
                            "Continue"
                        } else {
                            "Skip for now"
                        }
                    else -> "Start using AIRA"
                }
            Button(
                onClick = {
                    if (step < 2) step += 1 else onComplete()
                },
                modifier = Modifier.fillMaxWidth()
                    .padding(top = 12.dp, bottom = 16.dp)
                    .height(48.dp),
            ) {
                Text(ctaLabel)
            }
        }
    }
}

@Composable
private fun OnboardingWelcome() {
    Spacer(Modifier.height(24.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(56.dp)
                .clip(Theme.shapes.medium)
                .background(Theme.colors.surface)
                .border(0.5.dp, Theme.colors.edge, Theme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.VelaMark,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = Theme.colors.primary,
            )
        }
    }
    Spacer(Modifier.height(20.dp))
    Text(
        "Welcome to AIRA",
        style = Theme.typography.display.copy(textAlign = TextAlign.Center),
        color = Theme.colors.onBackground,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Your personal AI Agent that plans, remembers, and asks before acting.",
        style = Theme.typography.body.copy(textAlign = TextAlign.Center),
        color = Theme.colors.onBackgroundMuted,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(28.dp))
    Column(
        Modifier.fillMaxWidth()
            .clip(Theme.shapes.medium)
            .background(Theme.colors.surface)
            .border(0.5.dp, Theme.colors.edge, Theme.shapes.medium)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OnboardingFeature(
            icon = Icons.Sparkle,
            title = "Plans & runs Tools",
            subtitle = "Reads email, extracts pages, and runs Schedules.",
        )
        OnboardingFeature(
            icon = Icons.Brain,
            title = "Persistent Memory",
            subtitle = "Recalls your facts and Preferences across Sessions.",
        )
        OnboardingFeature(
            icon = Icons.LockSimple,
            title = "Explicit Confirmation",
            subtitle = "Reviews every email and Schedule before acting.",
        )
    }
}

@Composable
private fun OnboardingFeature(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = Theme.colors.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Theme.typography.label, color = Theme.colors.onBackground)
            Text(
                subtitle,
                style = Theme.typography.caption,
                color = Theme.colors.onBackgroundMuted,
            )
        }
    }
}

@Composable
private fun OnboardingConnect(
    state: AgentUiState,
    onConnectGmail: () -> Unit,
    onPasteLink: (String) -> Unit,
    onDismissPasteFallback: () -> Unit,
    onSaveSingleLlmKey: (String, String) -> Unit,
) {
    var pasted by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("gemini") }
    var keyDraft by remember { mutableStateOf("") }

    Spacer(Modifier.height(16.dp))
    Text(
        "Connect your Agent",
        style = Theme.typography.title,
        color = Theme.colors.onBackground,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Connect Gmail and add one free model key. You can change these anytime in Settings.",
        style = Theme.typography.body,
        color = Theme.colors.onBackgroundMuted,
    )
    Spacer(Modifier.height(20.dp))

    // Gmail card.
    Column(
        Modifier.fillMaxWidth()
            .clip(Theme.shapes.medium)
            .background(Theme.colors.surface)
            .border(0.5.dp, Theme.colors.edge, Theme.shapes.medium)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.GoogleLogo, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Gmail", style = Theme.typography.label, color = Theme.colors.onBackground)
                Text(
                    state.gmailAccount ?: "Read & send with Confirmation",
                    style = Theme.typography.caption,
                    color = Theme.colors.onBackgroundMuted,
                )
            }
            Text(
                if (state.gmailConnected) "Connected" else "Optional",
                style = Theme.typography.caption,
                color = if (state.gmailConnected) Theme.colors.success
                else Theme.colors.onBackgroundMuted,
            )
        }
        if (!state.gmailConnected) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onConnectGmail,
                variant = ButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text("Connect Gmail")
            }
        }
        if (state.pasteFallbackVisible) {
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = pasted,
                onValueChange = { pasted = it },
                modifier = Modifier.fillMaxWidth()
                    .height(44.dp)
                    .clip(Theme.shapes.field)
                    .background(Theme.colors.inset)
                    .border(1.dp, Theme.colors.edge, Theme.shapes.field)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = Theme.typography.body.copy(color = Theme.colors.onBackground),
                cursorBrush = SolidColor(Theme.colors.primary),
                singleLine = true,
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (pasted.isEmpty()) {
                            Text(
                                "Paste the callback link",
                                style = Theme.typography.caption,
                                color = Theme.colors.onBackgroundMuted,
                            )
                        }
                        field()
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onPasteLink(pasted)
                        pasted = ""
                    },
                    enabled = pasted.isNotBlank(),
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

    Spacer(Modifier.height(14.dp))

    // Model key card.
    Column(
        Modifier.fillMaxWidth()
            .clip(Theme.shapes.medium)
            .background(Theme.colors.surface)
            .border(0.5.dp, Theme.colors.edge, Theme.shapes.medium)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Model API Key",
                style = Theme.typography.label,
                color = Theme.colors.onBackground,
                modifier = Modifier.weight(1f),
            )
            val set =
                when (provider) {
                    "deepseek" -> state.deepseekKeySet
                    "meta" -> state.metaKeySet
                    else -> state.geminiKeySet
                }
            Text(
                if (set) "Set" else "Not set",
                style = Theme.typography.caption,
                color = if (set) Theme.colors.success else Theme.colors.onBackgroundMuted,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, id) in listOf("Gemini" to "gemini", "DeepSeek" to "deepseek", "Meta" to "meta")) {
                val active = provider == id
                Box(
                    Modifier.weight(1f)
                        .clip(Theme.shapes.fullyRounded)
                        .background(
                            if (active) Theme.colors.surfaceVariant
                            else androidx.compose.ui.graphics.Color.Transparent,
                        )
                        .border(
                            if (active) 1.5.dp else 1.dp,
                            if (active) Theme.colors.primary else Theme.colors.edge,
                            Theme.shapes.fullyRounded,
                        )
                        .clickable(
                            onClick = { provider = id },
                            interactionSource = remember { MutableInteractionSource() },
                            indication = androidx.compose.foundation.LocalIndication.current,
                        )
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = Theme.typography.label, color = Theme.colors.onBackground)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        BasicTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            modifier = Modifier.fillMaxWidth()
                .height(44.dp)
                .clip(Theme.shapes.field)
                .background(Theme.colors.inset)
                .border(1.dp, Theme.colors.edge, Theme.shapes.field)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            textStyle = Theme.typography.body.copy(color = Theme.colors.onBackground),
            cursorBrush = SolidColor(Theme.colors.primary),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (keyDraft.isEmpty()) {
                        Text(
                            "Paste API key",
                            style = Theme.typography.caption,
                            color = Theme.colors.onBackgroundMuted,
                        )
                    }
                    field()
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                onSaveSingleLlmKey(provider, keyDraft.trim())
                keyDraft = ""
            },
            enabled = keyDraft.isNotBlank(),
            variant = ButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text("Save key")
        }
    }
}

@Composable
private fun OnboardingPermissions(
    state: AgentUiState,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    Text(
        "Voice & Schedules",
        style = Theme.typography.title,
        color = Theme.colors.onBackground,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Optional system permissions so Schedules run on time in the background.",
        style = Theme.typography.body,
        color = Theme.colors.onBackgroundMuted,
    )
    Spacer(Modifier.height(20.dp))
    Column(
        Modifier.fillMaxWidth()
            .clip(Theme.shapes.medium)
            .background(Theme.colors.surface)
            .border(0.5.dp, Theme.colors.edge, Theme.shapes.medium),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Bell, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "On-time Schedules",
                    style = Theme.typography.label,
                    color = Theme.colors.onBackground,
                )
                Text(
                    "Fire Schedules at their exact saved time.",
                    style = Theme.typography.caption,
                    color = Theme.colors.onBackgroundMuted,
                )
            }
            if (!state.exactAlarmNeeded) {
                Text(
                    "Granted",
                    style = Theme.typography.caption,
                    color = Theme.colors.success,
                )
            } else {
                Box(
                    Modifier.clip(Theme.shapes.fullyRounded)
                        .background(Theme.colors.primary)
                        .clickable(
                            onClick = onOpenExactAlarmSettings,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = androidx.compose.foundation.LocalIndication.current,
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Allow",
                        style = Theme.typography.label,
                        color = Theme.colors.onPrimary,
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .padding(horizontal = 16.dp)
                .background(Theme.colors.edge),
        )
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.DeviceMobile, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Background running",
                    style = Theme.typography.label,
                    color = Theme.colors.onBackground,
                )
                Text(
                    "Prevents battery saver from delaying Task Execution.",
                    style = Theme.typography.caption,
                    color = Theme.colors.onBackgroundMuted,
                )
            }
            if (!state.batteryOptimizationNeeded) {
                Text(
                    "Granted",
                    style = Theme.typography.caption,
                    color = Theme.colors.success,
                )
            } else {
                Box(
                    Modifier.clip(Theme.shapes.fullyRounded)
                        .background(Theme.colors.primary)
                        .clickable(
                            onClick = onOpenBatterySettings,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = androidx.compose.foundation.LocalIndication.current,
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Allow",
                        style = Theme.typography.label,
                        color = Theme.colors.onPrimary,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "Microphone access is requested the first time you tap Voice Command.",
        style = Theme.typography.caption,
        color = Theme.colors.onBackgroundMuted,
    )
}
