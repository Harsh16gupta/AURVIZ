package com.aurviz.agent.ui

import com.aurviz.agent.EmailDraft
import com.aurviz.agent.EmailSummary
import com.aurviz.agent.FallbackResult
import com.aurviz.agent.InteractionRow
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
    // Ticket #90: defaulted rolling averages over `runs` completed runs
    // plus the share of successful runs. Last-run fields above stay the
    // freshest sample; the averages below roll forward via recordRun().
    val planAvgMs: Long? = null,
    val toolAvgMs: Long? = null,
    val recallAvgMs: Long? = null,
    val runs: Int = 0,
    val successRate: Double = 1.0,
)

// Ticket #90: pure rolling update for PerfTimings. Supplied stages refresh
// both their last-run field and their rolling average; unmeasured stages
// keep their previous values. `runs` counts every recorded run and
// `successRate` tracks the share of successful ones.
fun PerfTimings.recordRun(
    planMs: Long? = null,
    toolMs: Long? = null,
    recallMs: Long? = null,
    success: Boolean = true,
): PerfTimings {
    val nextRuns = runs + 1
    fun roll(oldAvg: Long?, sample: Long?): Long? {
        if (sample == null) return oldAvg
        if (oldAvg == null || runs == 0) return sample
        return (oldAvg * runs + sample) / nextRuns
    }
    val nextSuccess = (successRate * runs + if (success) 1.0 else 0.0) / nextRuns
    return copy(
        planMs = planMs ?: this.planMs,
        toolMs = toolMs ?: this.toolMs,
        recallMs = recallMs ?: this.recallMs,
        planAvgMs = roll(planAvgMs, planMs),
        toolAvgMs = roll(toolAvgMs, toolMs),
        recallAvgMs = roll(recallAvgMs, recallMs),
        runs = nextRuns,
        successRate = nextSuccess,
    )
}

data class VoiceUiState(
    val orb: OrbState = OrbState.Listening,
    val transcript: String = "",
    val listening: Boolean = false,
    val error: String? = null,
    // Ticket #91: live mic level in dB for the waveform ring buffer
    // (MainActivity.onRmsChanged throttled ~100ms → onVoiceLevel) and
    // whether the visible transcript is interim (muted) or final (crisp).
    val levelDb: Float = VOICE_LEVEL_MIN_DB,
    val isPartial: Boolean = true,
)

// Ticket #91: RMS meter window for the VoiceCommandSheet waveform.
// Silence floor (-2 dB) renders flat; loud speech (~12 dB) peaks.
const val VOICE_LEVEL_MIN_DB = -2f
const val VOICE_LEVEL_MAX_DB = 12f

// Ticket #91: pure clamp for RMS samples — JVM-tested, no Android.
fun voiceLevelClamped(db: Float): Float = db.coerceIn(VOICE_LEVEL_MIN_DB, VOICE_LEVEL_MAX_DB)

// Ticket #91: Confirm stays disabled on a blank transcript (state-derived).
val VoiceUiState.canConfirmVoice: Boolean
    get() = transcript.isNotBlank()

// Ticket #92: Sessions drawer copy (GLOSSARY: Session, Interaction, Past
// Interactions). Never "History" — banned word — in any of these.
const val PAST_INTERACTIONS_TITLE = "Past Interactions"
const val GROUP_TODAY = "Today"
const val GROUP_PREVIOUS_7_DAYS = "Previous 7 Days"
const val NEW_SESSION_LABEL = "New session"
const val OPEN_SESSIONS_DESCRIPTION = "Open Sessions"
const val CLOSE_SESSIONS_DESCRIPTION = "Close Sessions"
const val NO_PAST_SESSIONS_LINE = "No past Sessions yet."

// Ticket #92: Room round-trip for the feed. The Session id rides in the
// row only — InteractionRecord stays the feed shape screens render.
fun InteractionRecord.toRow(sessionId: String): InteractionRow =
    InteractionRow(
        sessionId = sessionId,
        command = command,
        responseText = responseText,
        provider = providerUsed,
        timestampMs = timestampMs,
        failed = failed,
    )

fun InteractionRow.toRecord(): InteractionRecord =
    InteractionRecord(
        // Room ids ("42") never collide with in-memory ids ("in_..."),
        // so hydrated rows keep their row id namespaced.
        id = "room_interaction_$pid",
        command = command,
        timestampMs = timestampMs,
        responseText = responseText,
        providerUsed = provider,
        failed = failed,
    )

// Ticket #92: drawer groups, newest first. Today = same calendar day as
// now; Previous 7 Days = the 7 days before today; older rows stay in
// Room but out of the drawer.
data class PastInteractionGroups(
    val today: List<InteractionRecord>,
    val previous7Days: List<InteractionRecord>,
)

fun groupPastInteractions(records: List<InteractionRecord>, nowMs: Long = System.currentTimeMillis()): PastInteractionGroups {
    val startOfToday =
        java.util.Calendar.getInstance().let { cal ->
            cal.timeInMillis = nowMs
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }
    val weekAgoMs = nowMs - 7L * 24 * 60 * 60 * 1000
    val sorted = records.sortedByDescending { it.timestampMs }
    return PastInteractionGroups(
        today = sorted.filter { it.timestampMs >= startOfToday },
        previous7Days = sorted.filter { it.timestampMs >= weekAgoMs && it.timestampMs < startOfToday },
    )
}

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
    // Confirmation card in-app; approving runs the held call exactly once,
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
    // Ticket #68: revocable per-recipient allow-always grants
    // (GLOSSARY Preference, UiPrefs-backed). Sends to one listed address
    // skip the Confirmation card; SendPolicy still applies.
    val allowAlways: Set<String> = emptySet(),
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
