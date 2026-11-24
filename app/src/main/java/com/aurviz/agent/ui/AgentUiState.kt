package com.aurviz.agent.ui

import com.aurviz.agent.EmailDraft
import com.aurviz.agent.EmailSummary
import com.aurviz.agent.ChatMessage
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

// V2 flagship shell (Part A): one row per Session thread for the drawer.
data class SessionSummary(
    val sessionId: String,
    val title: String,
    val lastTimestampMs: Long,
    val interactionCount: Int,
)

fun buildSessionSummaries(rows: List<InteractionRow>): List<SessionSummary> =
    rows.groupBy { it.sessionId }
        .filterKeys { it.isNotBlank() }
        .map { (sessionId, group) ->
            val earliest = group.minByOrNull { it.timestampMs }
            val latest = group.maxOf { it.timestampMs }
            val title = earliest?.command?.trim()?.take(48)?.takeIf { it.isNotEmpty() } ?: "New Session"
            SessionSummary(
                sessionId = sessionId,
                title = title,
                lastTimestampMs = latest,
                interactionCount = group.size,
            )
        }
        .sortedByDescending { it.lastTimestampMs }

// Ticket #106 (T1): cold-start active Session hydration. After process
// death activeSessionId resets to a fresh id while Room still holds the
// last Session's rows; restoring the most recent Session keeps the
// Session transcript continuous for follow-up commands.
data class HydratedSessionState(
    val sessionId: String,
    val sessionTurns: Int,
    val interactions: List<InteractionRecord>,
    val transcript: List<ChatMessage>,
    val sessions: List<SessionSummary>,
)

fun hydrateLatestSession(
    recentRowsDesc: List<InteractionRow>,
    fallbackSessionId: String,
): HydratedSessionState {
    val summaries = buildSessionSummaries(recentRowsDesc)
    val latestSessionId = summaries.firstOrNull()?.sessionId ?: fallbackSessionId
    val sessionRowsChronological =
        recentRowsDesc
            .filter { it.sessionId == latestSessionId }
            .sortedBy { it.timestampMs }
    val records = sessionRowsChronological.map { it.toRecord() }
    val transcript = mutableListOf<ChatMessage>()
    for (row in sessionRowsChronological) {
        transcript += ChatMessage(role = "user", content = row.command)
        transcript += ChatMessage(role = "assistant", content = row.responseText)
    }
    return HydratedSessionState(
        sessionId = latestSessionId,
        sessionTurns = sessionRowsChronological.size,
        interactions = records,
        transcript = transcript,
        sessions = summaries,
    )
}

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

// Ticket #70 Gap B: settled cards render receipt titles (ConfirmationReceipt),
// so the Remember Preference grant can no longer parse the recipient out of
// the title prefix alone. Legacy records still carry "Send email to <addr>";
// current records carry the receipt title plus the executed send step, whose
// argsSummary holds the effective (possibly edited) "to:" value. JVM-tested
// in CommandPlanUiTest. Null when no send address is recoverable.
fun rememberAddressFor(record: InteractionRecord): String? {
    val legacyPrefix = "Send email to "
    val legacy = record.confirmationTitle?.takeIf { it.startsWith(legacyPrefix) }
        ?.removePrefix(legacyPrefix)?.split(",")?.firstOrNull()?.trim()?.takeIf { it.contains("@") }
    if (legacy != null) return legacy
    val summary = record.steps.firstOrNull { it.toolName == "send_email" }?.argsSummary ?: return null
    return summary.split(",").map { it.trim() }.firstOrNull { it.startsWith("to:") }
        ?.substringAfter("to:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.trim()?.takeIf { it.contains("@") }
}

// Ticket #108 (T3): 1-tap Agent diagnostics + Share Sheet draft +
// launcher shortcuts. Pure JVM-testable models and helpers; the
// ViewModel only folds them into state, screens only render them.

// Launcher shortcut actions handled by MainActivity →
// AgentViewModel.handleIncomingIntent → AgentNav.
const val SHORTCUT_ACTION_VOICE = "com.aurviz.agent.action.VOICE_COMMAND"
const val SHORTCUT_ACTION_NEW_SESSION = "com.aurviz.agent.action.NEW_SESSION"
const val SHORTCUT_ACTION_SCHEDULES = "com.aurviz.agent.action.SCHEDULES"

// Formats shared text/URL from the Android Share Sheet
// (Intent.ACTION_SEND) into a ready-to-review Composer draft.
// Never auto-sends — the user still taps Send on CommandScreen.
fun formatSharedCommandDraft(sharedText: String?, sharedSubject: String? = null): String? {
    val body = sharedText?.trim().orEmpty()
    if (body.isEmpty()) return null
    val subjectPrefix = sharedSubject?.trim()?.takeIf { it.isNotEmpty() && !body.startsWith(it) }
    return if (com.aurviz.agent.isAllowedWebUrl(body) && !body.any { it.isWhitespace() }) {
        "Open $body and summarize the key points"
    } else if (subjectPrefix != null) {
        "Summarize this ($subjectPrefix): $body"
    } else {
        "Summarize this: $body"
    }
}

// One readiness row for the Settings System Diagnostics card.
data class DiagnosticItem(
    val label: String,
    val status: String,
    val healthy: Boolean,
    val detail: String,
)

data class AgentDiagnosticsReport(
    val running: Boolean = false,
    val timestampMs: Long = 0L,
    val items: List<DiagnosticItem> = emptyList(),
) {
    val passedCount: Int get() = items.count { it.healthy }
}

fun buildAgentDiagnostics(
    effectiveProvider: String,
    geminiKeySet: Boolean,
    deepseekKeySet: Boolean,
    metaKeySet: Boolean,
    gmailConnected: Boolean,
    gmailAccount: String?,
    gmailReachable: Boolean?,
    exactAlarmNeeded: Boolean,
    batteryOptimizationNeeded: Boolean,
    memoriesCount: Int,
    schedulesCount: Int,
    sessionsCount: Int,
    nowMs: Long = System.currentTimeMillis(),
): AgentDiagnosticsReport {
    val anyKey = geminiKeySet || deepseekKeySet || metaKeySet
    val activeKeySet = when (effectiveProvider.lowercase()) {
        "deepseek" -> deepseekKeySet
        "meta" -> metaKeySet
        else -> geminiKeySet
    }
    val providerDisplay = com.aurviz.agent.providerDisplayName(effectiveProvider)
    val gmailHealthy = gmailConnected && (gmailReachable != false)
    val gmailStatus = when {
        !gmailConnected -> "Not connected"
        gmailReachable == true -> "Connected · Verified"
        gmailReachable == false -> "Token expired · Reconnect"
        else -> "Connected"
    }
    val items = listOf(
        DiagnosticItem(
            label = "AI Provider ($providerDisplay)",
            status = if (activeKeySet) "Ready" else if (anyKey) "Fallback ready" else "No key set",
            healthy = anyKey,
            detail = if (anyKey) "Active model: $providerDisplay" else "Add a Gemini, DeepSeek, or Meta key in Connections.",
        ),
        DiagnosticItem(
            label = "Gmail OAuth",
            status = gmailStatus,
            healthy = gmailHealthy,
            detail = if (gmailConnected) "Signed in as ${gmailAccount ?: "connected"}" else "Optional — connect in Connections to search, read, or send email.",
        ),
        DiagnosticItem(
            label = "On-time Schedules",
            status = if (!exactAlarmNeeded) "Granted" else "Inexact fallback",
            healthy = !exactAlarmNeeded,
            detail = com.aurviz.agent.scheduleTimingNote(!exactAlarmNeeded),
        ),
        DiagnosticItem(
            label = "Background running",
            status = if (!batteryOptimizationNeeded) "Exempted" else "Restricted",
            healthy = !batteryOptimizationNeeded,
            detail = if (!batteryOptimizationNeeded) "Doze will not delay Schedules." else "Allow background running so Doze does not delay Schedules.",
        ),
        DiagnosticItem(
            label = "On-device Storage (Room v6)",
            status = "Healthy",
            healthy = true,
            detail = "$memoriesCount Memories · $schedulesCount Schedules · $sessionsCount Sessions",
        ),
    )
    return AgentDiagnosticsReport(running = false, timestampMs = nowMs, items = items)
}

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
    // Ticket #69 (part of #65 T4): the typed/voice `unschedule` fast path
    // publishes the resolved Schedule here for an explicit Stop/Discard
    // Confirmation card — no silent disarm. The screen's inline row keeps
    // its own Delete/Keep confirm and never touches this slot.
    val pendingUnschedule: Schedule? = null,
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
    // Disabled Schedule ids (UiPrefs-backed) + recent Task Execution runs.
    val disabledScheduleIds: Set<String> = emptySet(),
    // Ticket #68: revocable per-recipient allow-always grants
    // (GLOSSARY Preference, UiPrefs-backed). Sends to one listed address
    // skip the Confirmation card; SendPolicy still applies.
    val allowAlways: Set<String> = emptySet(),
    val scheduleRuns: List<ScheduleRunSummary> = emptyList(),
    val sessions: List<SessionSummary> = emptyList(),
    val onboardingCompleted: Boolean = true,
    // Ticket #108 (T3): 1-tap diagnostics report + Share Sheet / shortcut
    // routing. pendingNavRoute holds a Dests route until AgentNav consumes
    // it; pendingOpenVoiceSheet opens the Voice Command sheet on arrival.
    val diagnostics: AgentDiagnosticsReport = AgentDiagnosticsReport(),
    val pendingNavRoute: String? = null,
    val pendingOpenVoiceSheet: Boolean = false,
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
