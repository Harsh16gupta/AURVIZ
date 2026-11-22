package com.aurviz.agent.ui

import com.aurviz.agent.EmailDraft
import com.aurviz.agent.EmailSummary
import com.aurviz.agent.Memory
import com.aurviz.agent.ParsedSchedule
import com.aurviz.agent.Schedule
import com.aurviz.agent.TimelineStep
import com.aurviz.agent.WebPage
import com.aurviz.agent.ui.components.VoiceState as OrbState

// One snapshot of everything the Compose screens render.
// The ViewModel owns this; screens only read it and forward events.
data class PerfTimings(
    val planMs: Long? = null,
    val toolMs: Long? = null,
    val recallMs: Long? = null,
)

data class VoiceUiState(
    val orb: OrbState = OrbState.Listening,
    val transcript: String = "",
    val listening: Boolean = false,
    val error: String? = null,
)

data class AgentUiState(
    val inputText: String = "",
    val statusLine: String = "Type a command to begin.",
    val timeline: List<TimelineStep> = emptyList(),
    val providerUsed: String? = null,
    val memories: List<Memory> = emptyList(),
    val schedules: List<Schedule> = emptyList(),
    val emails: List<EmailSummary> = emptyList(),
    val gmailAccount: String? = null,
    val gmailConnected: Boolean = false,
    val pendingEmail: EmailDraft? = null,
    val pendingForget: List<Memory> = emptyList(),
    val pendingSchedule: ParsedSchedule? = null,
    val schedulePreview: String = "",
    val webPage: WebPage? = null,
    val webLoading: Boolean = false,
    val webError: String? = null,
    val voice: VoiceUiState = VoiceUiState(),
    val perf: PerfTimings = PerfTimings(),
    // One-shot: when non-null the UI opens this URL in a browser, then calls consumeAuthUrl().
    val authUrl: String? = null,
    val pasteFallbackVisible: Boolean = false,
    val loopbackHint: String = "",
    val exactAlarmNeeded: Boolean = false,
    val sending: Boolean = false,
)
