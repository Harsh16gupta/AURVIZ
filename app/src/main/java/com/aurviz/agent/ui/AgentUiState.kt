package com.aurviz.agent.ui

import com.aurviz.agent.EmailDraft
import com.aurviz.agent.EmailSummary
import com.aurviz.agent.FallbackResult
import com.aurviz.agent.LlmResult
import com.aurviz.agent.Memory
import com.aurviz.agent.ParsedSchedule
import com.aurviz.agent.PendingApproval
import com.aurviz.agent.Schedule
import com.aurviz.agent.TimelineStep
import com.aurviz.agent.ToolCall
import com.aurviz.agent.WebPage
import com.aurviz.agent.retryDisclosure
import com.aurviz.agent.ui.components.ConfirmationStatus
import com.aurviz.agent.ui.components.VoiceState as OrbState
import com.aurviz.agent.ui.theme.ThemeMode

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

// One past command in the scrollable Interaction feed (GLOSSARY
// Interaction): the command, recalled Memories, the Plan steps, the
// response, and any settled Confirmation receipt.
data class InteractionRecord(
    val id: String,
    val command: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val recalledMemories: List<String> = emptyList(),
    val steps: List<TimelineStep> = emptyList(),
    val responseText: String = "",
    val emails: List<EmailSummary> = emptyList(),
    val webPage: WebPage? = null,
    val providerUsed: String? = null,
    val retryNotice: String? = null,
    val confirmationTitle: String? = null,
    val confirmationDetail: String? = null,
    val confirmationStatus: ConfirmationStatus? = null,
    val failed: Boolean = false,
)

data class AgentUiState(
    val inputText: String = "",
    val statusLine: String = "Type a command to begin.",
    val timeline: List<TimelineStep> = emptyList(),
    val providerUsed: String? = null,
    // Every provider that saw the prompt, in try-order. Size > 1 means the
    // private-context prompt was replayed — retryDisclosure() renders the
    // visible "retried on Y" line from this (ADR-0004, ticket 22).
    val providersTried: List<String> = emptyList(),
    val memories: List<Memory> = emptyList(),
    val schedules: List<Schedule> = emptyList(),
    val emails: List<EmailSummary> = emptyList(),
    val gmailAccount: String? = null,
    val gmailConnected: Boolean = false,
    val pendingEmail: EmailDraft? = null,
    val pendingForget: List<Memory> = emptyList(),
    val pendingSchedule: ParsedSchedule? = null,
    val schedulePreview: String = "",
    // Ticket 12/25: the loop's live Confirmation card (GLOSSARY
    // Confirmation). NeedsApproval raises pendingApproval with the
    // unseen flags computed at raise time; StepDone clears it.
    // Approve/Reject resolve the suspended gate — nothing runs before.
    // P0: the card's capability is the opaque per-proposal nonce in
    // pendingApprovalProposalId (never the Tool call id — fallback ids
    // repeat every run). Buttons echo the nonce; a stale/guessed id fails.
    val pendingApproval: ToolCall? = null,
    val pendingApprovalProposalId: String? = null,
    // Ticket 15/28: writes a scheduled run held for the user (GLOSSARY
    // Confirmation for unattended runs, ADR-0002). Each row renders its
    // approval card in-app; approving runs the held call exactly once,
    // rejecting drops it untouched.
    val pendingApprovals: List<PendingApproval> = emptyList(),
    val pendingApprovalUnseen: List<String> = emptyList(),
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
    val batteryOptimizationNeeded: Boolean = false,
    // Ticket 36: whether a user-entered key exists in TokenVault per provider.
    // The key values themselves never enter UI state — only these flags.
    val geminiKeySet: Boolean = false,
    val groqKeySet: Boolean = false,
    val deepseekKeySet: Boolean = false,
    val metaKeySet: Boolean = false,
    val sending: Boolean = false,
    // P0 single-flight: one Task Execution at a time. While true the
    // Composer sends nothing so a second command cannot replace the open
    // Confirmation card's gate.
    val agentRunning: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val hapticsEnabled: Boolean = true,
    // Scrollable Interaction feed + in-flight command state.
    val interactions: List<InteractionRecord> = emptyList(),
    val activeCommand: String? = null,
    val activeRecalledMemories: List<String> = emptyList(),
    // Session-scoped thread (Issue #51): which ChatGPT-style thread the
    // model sees. New Session = empty transcript. Scheduled runs ignore it.
    val sessionId: String = "s_default",
    val sessionTurns: Int = 0,
    // Interactive AI provider override (null = Auto/Default).
    val selectedProvider: String? = null,
    val confirmSendEmail: Boolean = true,
    // Disabled Schedule ids (UiPrefs-backed) + recent run history.
    val disabledScheduleIds: Set<String> = emptySet(),
    val scheduleRuns: List<ScheduleRunSummary> = emptyList(),
)

// One recorded scheduled run for the Recent Runs list (GLOSSARY Schedule):
// mapped from RoomScheduleRunStore rows without changing domain entities.
data class ScheduleRunSummary(
    val id: String,
    val scheduleId: String,
    val status: String,
    val durationMs: Long,
    val summary: String?,
    val timestampMs: Long,
)

// Ticket 11/24: visible retry line for CommandScreen, rendered from the
// copied providersTried via retryDisclosure() (ticket 35). Null when the
// first provider answered or nothing ran yet — no silent replay.
fun AgentUiState.retryLine(): String? {
    if (providersTried.size <= 1) return null
    val used = providerUsed ?: return "No provider answered (tried ${providersTried.joinToString(", ")})"
    return retryDisclosure(FallbackResult(LlmResult(emptyList(), null), used, providersTried))
}
