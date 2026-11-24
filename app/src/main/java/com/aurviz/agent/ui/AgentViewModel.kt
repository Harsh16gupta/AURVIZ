package com.aurviz.agent.ui

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.aurviz.agent.AppDb
import com.aurviz.agent.AgentEvent
import com.aurviz.agent.AgentLoop
import com.aurviz.agent.AgentLoopResult
import com.aurviz.agent.ApprovalDecision
import com.aurviz.agent.ApprovalGate
import com.aurviz.agent.InMemoryAllowAlwaysStore
import com.aurviz.agent.allowAlwaysEligible
import com.aurviz.agent.BuildConfig
import com.aurviz.agent.ChatMessage
import com.aurviz.agent.FallbackResult
import com.aurviz.agent.GMAIL_SCOPES
import com.aurviz.agent.GmailException
import com.aurviz.agent.GmailHttpApi
import com.aurviz.agent.LoopbackReceiver
import com.aurviz.agent.LlmKeys
import com.aurviz.agent.MIGRATION_1_2
import com.aurviz.agent.MIGRATION_2_3
import com.aurviz.agent.MIGRATION_3_4
import com.aurviz.agent.MIGRATION_4_5
import com.aurviz.agent.EmailSummary
import com.aurviz.agent.Memory
import com.aurviz.agent.NamedProvider
import com.aurviz.agent.PendingApproval
import com.aurviz.agent.PendingApprovalOutcome
import com.aurviz.agent.Plan
import com.aurviz.agent.RoomPendingApprovalStore
import com.aurviz.agent.RoomScheduleRunStore
import com.aurviz.agent.Schedule
import com.aurviz.agent.TimelineStep
import com.aurviz.agent.RoomMemoryStore
import com.aurviz.agent.RoomScheduleStore
import com.aurviz.agent.TokenStore
import com.aurviz.agent.TokenVault
import com.aurviz.agent.ToolCall
import com.aurviz.agent.ToolResult
import com.aurviz.agent.WebBridge
import com.aurviz.agent.WebPage
import com.aurviz.agent.WebException
import com.aurviz.agent.BrowserRoute
import com.aurviz.agent.appendSessionTurn
import com.aurviz.agent.routeBrowserUrl
import com.aurviz.agent.approvalCardFor
import com.aurviz.agent.approvePendingApproval
import com.aurviz.agent.buildAuthUrl
import com.aurviz.agent.buildTimeline
import com.aurviz.agent.cancelRoutineAlarm
import com.aurviz.agent.cancelRoutineWork
import com.aurviz.agent.codeChallenge
import com.aurviz.agent.composeEmail
import com.aurviz.agent.createSchedule
import com.aurviz.agent.defaultCandidates
import com.aurviz.agent.defaultToolRegistry
import com.aurviz.agent.deleteSchedule
import com.aurviz.agent.formatSchedulePreview
import com.aurviz.agent.forgetPreview
import com.aurviz.agent.knownAddressesFromInbox
import com.aurviz.agent.listSchedules
import com.aurviz.agent.onApprovedWrite
import com.aurviz.agent.ScheduleSideEffect
import com.aurviz.agent.newCodeVerifier
import com.aurviz.agent.openPage
import com.aurviz.agent.parseSchedule
import com.aurviz.agent.PinnedFallbackChatLlm
import com.aurviz.agent.flattenChatMessages
import com.aurviz.agent.refreshBatteryNeeded
import com.aurviz.agent.scheduleTimingNote
import com.aurviz.agent.recallFor
import com.aurviz.agent.registerRoutineAlarm
import com.aurviz.agent.rejectPendingApproval
import com.aurviz.agent.rememberFact
import com.aurviz.agent.retryDisclosure
import com.aurviz.agent.toTimeline
import com.aurviz.agent.ui.components.VoiceState as OrbState
import com.aurviz.agent.ui.components.ConfirmationStatus
import com.aurviz.agent.ui.components.UiPrefs
import com.aurviz.agent.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray

// Thin orchestrator over the tested domain logic (Email, Web,
// WebViewBridge, MemoryStore, VoiceInput, LoopbackAuth). All decisions stay
// in those files; this class only moves results into AgentUiState.

// Ticket 11/24: pure loop-wiring helpers (no Android) for the AgentLoop
// path below. JVM-tested in ViewModelLoopWiringTest; the ViewModel only
// folds AgentEvents through them and copies the outputs into state.

// Live timeline for one Task Execution (GLOSSARY): the Tool calls the
// loop has made so far, how many finished, and the terminal error.
fun loopTimeline(calls: List<ToolCall>, doneCount: Int, error: String?): List<TimelineStep> =
    buildTimeline(Plan(calls), doneCount, error)

// Accumulator the ViewModel keeps per run: each AgentEvent folds into it
// so the existing timeline rows render StepStarted/ToolRunning/
// NeedsApproval/StepDone/Failed live (ADR-0005).
data class LoopUiProgress(
    val calls: List<ToolCall> = emptyList(),
    val doneCount: Int = 0,
    val error: String? = null,
    val pendingApproval: ToolCall? = null,
) {
    fun timeline(): List<TimelineStep> = loopTimeline(calls, doneCount, error)
}

fun LoopUiProgress.withEvent(event: AgentEvent): LoopUiProgress =
    when (event) {
        is AgentEvent.StepStarted -> this
        is AgentEvent.ToolRunning -> copy(calls = calls + event.call)
        is AgentEvent.NeedsApproval -> copy(pendingApproval = event.call)
        is AgentEvent.StepDone -> copy(doneCount = (doneCount + 1).coerceAtMost(calls.size))
        is AgentEvent.Failed -> copy(error = event.error)
    }

// Bound the Confirmation wait by the Task Execution's remaining budget
// (ADR-0005 gaps §3H): the card never outlives the run, so expiry lands
// as TimedOut instead of blocking an already-dead budget. Zero remaining
// returns immediately (TimedOut) instead of opening a hopeless wait.
fun approvalWaitMs(
    elapsedMs: Long,
    totalBudgetMs: Long,
    maxWaitMs: Long = ApprovalGate.APPROVAL_TIMEOUT_MS,
): Long = minOf(maxWaitMs, totalBudgetMs - elapsedMs).coerceAtLeast(0L)

// Single-shot fallback rendering delegates to the shared helper in the
// agent package (ADR-0005): one renderer, not two.
fun renderLoopPrompt(messages: List<ChatMessage>): String = flattenChatMessages(messages)

// Foreground shim over the unified pinned-fallback seam: the loop's
// messages ride intact with the run's registry view injected.
// Kept as a one-line subclass so existing imports keep working; the
// shared logic lives in PinnedFallbackChatLlm. Deleted in follow-up.
class FallbackChatLlm(
    candidates: List<NamedProvider>,
    tools: JSONArray = JSONArray(),
) : PinnedFallbackChatLlm(candidates, tools)

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private val db: AppDb =
        Room.databaseBuilder(application, AppDb::class.java, "aurviz.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    private val roomStore = RoomMemoryStore(db.memories())
    private val scheduleStore = RoomScheduleStore(db.schedules())
    private val tokens = TokenStore()
    private val tokenVault = TokenVault(application)
    private val gmail = GmailHttpApi(
        tokens,
        BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
        BuildConfig.GOOGLE_OAUTH_CLIENT_SECRET,
    )

    // Set by BrowserScreen once its retained WebView exists (see WebViewHolder).
    private var webBridge: WebBridge? = null

    private var lastCommand: String? = null
    private var signingIn = false
    private var pendingLinkVerifier: String? = null
    private var lastExchangedCode: String? = null
    private var signInDone = false

    // Ticket #68: the live allow-always grant set. The confirm hook reads
    // this same instance, so allow/revoke take effect on the next decision
    // with no restart. UiPrefs is persistence only. Declared before init:
    // the init block hydrates it from prefs.
    private val allowAlwaysStore = InMemoryAllowAlwaysStore()

    init {
        tokenVault.loadInto(tokens)
        val appCtx = application.applicationContext
        UiPrefs.allowAlways(appCtx).forEach(allowAlwaysStore::allow)
        _state.update {
            it.copy(
                themeMode = UiPrefs.themeMode(appCtx),
                hapticsEnabled = UiPrefs.hapticsEnabled(appCtx),
                selectedProvider = UiPrefs.selectedProvider(appCtx),
                confirmSendEmail = UiPrefs.confirmSendEmail(appCtx),
                disabledScheduleIds = UiPrefs.disabledScheduleIds(appCtx),
                allowAlways = allowAlwaysStore.list(),
            )
        }
        refreshScheduleRuns()
        refreshLlmKeyFlags()
        refreshGmailStatus()
        refreshMemories()
        refreshSchedules()
        refreshPendingApprovals()
        refreshExactAlarmFlag()
        refreshBatteryFlag()
    }

    fun setThemeMode(mode: ThemeMode) {
        UiPrefs.setThemeMode(getApplication<Application>().applicationContext, mode)
        _state.update { it.copy(themeMode = mode) }
    }

    fun setHapticsEnabled(on: Boolean) {
        UiPrefs.setHapticsEnabled(getApplication<Application>().applicationContext, on)
        _state.update { it.copy(hapticsEnabled = on) }
    }

    fun setSelectedProvider(name: String?) {
        UiPrefs.setSelectedProvider(getApplication<Application>().applicationContext, name)
        _state.update { it.copy(selectedProvider = name) }
    }

    fun allowAlwaysAllow(address: String) {
        allowAlwaysStore.allow(address)
        val updated = allowAlwaysStore.list()
        UiPrefs.setAllowAlways(getApplication<Application>().applicationContext, updated)
        _state.update { it.copy(allowAlways = updated) }
    }

    fun allowAlwaysRevoke(address: String) {
        allowAlwaysStore.revoke(address)
        val updated = allowAlwaysStore.list()
        UiPrefs.setAllowAlways(getApplication<Application>().applicationContext, updated)
        _state.update { it.copy(allowAlways = updated) }
    }

    private fun effectiveProvider(): String =
        state.value.selectedProvider ?: BuildConfig.LLM_DEFAULT_PROVIDER

    // The currently running loop/command coroutine — cancelRun() stops it.
    private var activeJob: Job? = null
    private var interactionSeq = 0L

    // Issue #51: in-memory Session transcript (ChatGPT-style thread).
    // Turns inside one Session share context; a new Session starts empty.
    // Scheduled runs never read this — they stay stateless.
    private var activeSessionId: String = "s_${System.currentTimeMillis()}"
    private val sessionTranscript = mutableListOf<ChatMessage>()

    // Start a fresh Session thread: drop short-term context, keep Room
    // Memory (long-term) untouched. The next command runs cold.
    fun startNewSession() {
        if (state.value.agentRunning) return
        sessionTranscript.clear()
        activeSessionId = "s_${System.currentTimeMillis()}"
        _state.update { it.copy(sessionId = activeSessionId, sessionTurns = 0) }
    }

    // Confirmation receipt fields stamped while a Task Execution is still
    // in flight (approve/reject lands before the run completes). Folded
    // into the InteractionRecord when the run is recorded.
    private var pendingConfirmTitle: String? = null
    private var pendingConfirmDetail: String? = null
    private var pendingConfirmStatus: ConfirmationStatus? = null

    private fun nextInteractionId(): String = "in_${System.currentTimeMillis()}_${interactionSeq++}"

    // Appends a completed InteractionRecord for the active command and
    // clears the in-flight state. Keeps the latest 50. Must be called
    // after the terminal _state update so statusLine/timeline are final.
    private fun recordCompleted(
        command: String? = state.value.activeCommand,
        recalled: List<String> = state.value.activeRecalledMemories,
        responseText: String = state.value.statusLine,
        steps: List<TimelineStep> = state.value.timeline,
        emails: List<EmailSummary> = state.value.emails,
        webPage: WebPage? = state.value.webPage,
        failed: Boolean = false,
        confirmTitle: String? = pendingConfirmTitle,
        confirmDetail: String? = pendingConfirmDetail,
        confirmStatus: ConfirmationStatus? = pendingConfirmStatus,
    ) {
        val cmd = command ?: return
        val entry = InteractionRecord(
            id = nextInteractionId(),
            command = cmd,
            recalledMemories = recalled,
            steps = steps,
            responseText = responseText,
            emails = emails,
            webPage = webPage,
            providerUsed = state.value.providerUsed,
            retryNotice = state.value.retryLine(),
            confirmationTitle = confirmTitle,
            confirmationDetail = confirmDetail,
            confirmationStatus = confirmStatus,
            failed = failed,
        )
        pendingConfirmTitle = null
        pendingConfirmDetail = null
        pendingConfirmStatus = null
        _state.update { s ->
            s.copy(
                interactions = (s.interactions + entry).takeLast(50),
                activeCommand = null,
                activeRecalledMemories = emptyList(),
            )
        }
    }

    // Attaches a Confirmation receipt to the in-flight run: stashes it
    // for the final record and patches the latest matching record if one
    // is already in the feed, so the card stays visible in its settled
    // 18dp state instead of disappearing.
    private fun stampConfirmation(title: String?, detail: String?, status: ConfirmationStatus) {
        pendingConfirmTitle = title
        pendingConfirmDetail = detail
        pendingConfirmStatus = status
        val cmd = state.value.activeCommand
        _state.update { s ->
            val idx = if (cmd != null) s.interactions.indexOfLast { it.command == cmd } else -1
            if (idx < 0) return@update s
            val updated = s.interactions.toMutableList()
            val rec = updated[idx]
            updated[idx] = rec.copy(
                confirmationTitle = title,
                confirmationDetail = detail,
                confirmationStatus = status,
            )
            s.copy(interactions = updated)
        }
    }

    private fun confirmationTextFor(call: ToolCall, args: Map<String, Any?> = call.arguments): Pair<String, String> {
        return if (call.name == "send_email") {
            val to = (args["to"] as? String).orEmpty()
            val subject = (args["subject"] as? String).orEmpty()
            val body = (args["body"] as? String).orEmpty()
            "Send email to $to" to "To: $to\nSubject: $subject\n$body"
        } else {
            "Run ${call.name}" to args.entries.joinToString("\n") { (k, v) -> "$k: $v" }
        }
    }

    fun cancelRun() {
        state.value.pendingApprovalProposalId?.let { proposalId ->
            if (state.value.pendingApproval?.id != null) {
                approvalGate.resolve(proposalId, ApprovalDecision.Rejected)
            }
        }
        activeJob?.cancel()
        activeJob = null
        currentRunId?.let { approvalGate.finishRun(it) }
        currentRunId = null
        val cmd = state.value.activeCommand
        _state.update {
            it.copy(
                agentRunning = false,
                sending = false,
                pendingApproval = null,
                pendingApprovalProposalId = null,
                pendingApprovalUnseen = emptyList(),
                activeCommand = null,
                activeRecalledMemories = emptyList(),
                statusLine = "Stopped.",
            )
        }
        if (cmd != null) {
            val entry = InteractionRecord(
                id = nextInteractionId(),
                command = cmd,
                recalledMemories = emptyList(),
                steps = state.value.timeline,
                responseText = "Task Execution stopped by user.",
                providerUsed = state.value.providerUsed,
                failed = false,
            )
            _state.update { s -> s.copy(interactions = (s.interactions + entry).takeLast(50)) }
        }
        pendingConfirmTitle = null
        pendingConfirmDetail = null
        pendingConfirmStatus = null
    }

    fun attachWebBridge(bridge: WebBridge) {
        webBridge = bridge
    }

    fun onInputChange(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun consumeAuthUrl() {
        _state.update { it.copy(authUrl = null) }
    }

    // One entry point for every command, mirroring the old MainActivity chain.
    fun sendCommand(raw: String) {
        val command = raw.trim()
        if (command.isBlank()) return
        lastCommand = command
        _state.update { it.copy(inputText = "", activeCommand = command, activeRecalledMemories = emptyList()) }
        val lower = command.lowercase()
        when {
            lower.startsWith("remember ") -> handleRemember(command.substring(9).trim())
            lower.startsWith("forget ") -> handleForgetPreview(command.substring(7).trim())
            lower.startsWith("email ") -> handleEmailSend(command.substring(6).trim())
            lower == "disconnect gmail" -> revokeGmail()
            lower.startsWith("open ") -> openWeb(command.substring(5).trim())
            lower.startsWith("schedule ") -> handleScheduleCreate(command.substring(9).trim())
            lower == "schedules" -> refreshSchedules(showLine = true)
            lower.startsWith("unschedule ") -> handleUnschedule(command.substring(11).trim())
            // Reads (including "check mail") run the end-to-end loop like
            // every other command: the read Tools are ungated, so the loop
            // fetches and summarizes without a Confirmation card.
            else -> runLoopCommand(command)
        }
    }

    fun retry() {
        lastCommand?.let { sendCommand(it) }
    }

    // --- Memory (remember / forget preview / delete) ---

    private fun handleRemember(fact: String) {
        if (fact.isBlank()) {
            _state.update { it.copy(statusLine = "What should I remember?") }
            return
        }
        _state.update { it.copy(statusLine = "Saving…") }
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            rememberFact(roomStore, fact, "you told me")
            val recallMs = System.currentTimeMillis() - t0
            refreshMemories()
            _state.update {
                it.copy(
                    statusLine = "Saved: $fact",
                    perf = it.perf.copy(recallMs = recallMs),
                )
            }
            recordCompleted(responseText = "Saved: $fact")
        }
    }

    private fun handleForgetPreview(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val preview = forgetPreview(roomStore, query)
            val ms = System.currentTimeMillis() - t0
            _state.update {
                it.copy(
                    pendingForget = preview,
                    statusLine = if (preview.isEmpty()) {
                        "Nothing matches that."
                    } else {
                        "Delete these?\n" + preview.joinToString("\n") { m -> "• ${m.text} (${m.source})" }
                    },
                    perf = it.perf.copy(recallMs = ms),
                )
            }
            recordCompleted()
        }
    }

    fun approveForget() {
        val victims = _state.value.pendingForget
        if (victims.isEmpty()) return
        _state.update { it.copy(pendingForget = emptyList(), statusLine = "Deleting ${victims.size}…") }
        viewModelScope.launch(Dispatchers.IO) {
            victims.forEach { roomStore.delete(it.id) }
            refreshMemories()
            _state.update { it.copy(statusLine = "Deleted ${victims.size}.") }
            recordCompleted(responseText = "Deleted ${victims.size}.")
        }
    }

    fun rejectForget() {
        _state.update { it.copy(pendingForget = emptyList(), statusLine = "Kept.") }
        recordCompleted(responseText = "Kept.")
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            roomStore.delete(id)
            refreshMemories()
        }
    }

    // Inline edit: replace the Memory's text, keeping its source.
    fun updateMemory(memory: Memory, newText: String) {
        val text = newText.trim()
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            roomStore.delete(memory.id)
            roomStore.save(text, memory.source)
            refreshMemories()
        }
    }

    // Undo delete: save the text back under its original source.
    fun restoreMemory(memory: Memory) {
        viewModelScope.launch(Dispatchers.IO) {
            roomStore.save(memory.text, memory.source)
            refreshMemories()
        }
    }

    private fun refreshMemories() {
        viewModelScope.launch(Dispatchers.IO) {
            val all = roomStore.list()
            _state.update { it.copy(memories = all) }
        }
    }

    // --- Email (preview-gated send + important read + OAuth) ---

    private fun handleEmailSend(text: String) {
        if (!tokens.isConnected()) {
            _state.update { it.copy(statusLine = "Connect Gmail first (Settings).") }
            return
        }
        val parts = text.split("|").map { it.trim() }
        val first = parts[0].split(Regex("\\s+"), limit = 2)
        val address = first.getOrNull(0).orEmpty()
        if (!address.contains("@")) {
            _state.update { it.copy(statusLine = "Who to? Say: email ADDRESS your message") }
            return
        }
        val draft = if (parts.size >= 3) {
            composeEmail(address, parts[1], parts[2])
        } else {
            composeEmail(address, "From AURVIZ", first.getOrNull(1).orEmpty())
        }
        if (draft.body.isBlank()) {
            _state.update { it.copy(statusLine = "Empty message — say what to send.") }
            return
        }
        _state.update {
            it.copy(
                pendingEmail = draft,
                statusLine = "Send this?\nTo: ${draft.to.joinToString()}\nSubject: ${draft.subject}\n${draft.body}",
            )
        }
    }

    // Rebuilds the raw MIME from the EDITED fields, then sends once.
    // Ticket 12/25: the sheet is the Confirmation, so this resolves the
    // single gated path as Approved — the deterministic SendPolicy still
    // runs before the transport, and a Deny surfaces instead of a send.
    fun approveEmail(to: String, subject: String, body: String) {
        val call = ToolCall("ui_approve", "send_email", mapOf("to" to to.trim(), "subject" to subject, "body" to body))
        _state.update { it.copy(pendingEmail = null, sending = true, statusLine = "Sending…", activeCommand = it.activeCommand ?: "email $to") }
        var emailJob: Job? = null
        emailJob = viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val registry = defaultToolRegistry(gmailApi = gmail, scheduleStore = scheduleStore, memoryStore = roomStore)
            val gated = registry.executeGated(call) { ApprovalDecision.Approved }
            val ms = System.currentTimeMillis() - t0
            val sent = gated.result is com.aurviz.agent.ToolResult.Success
            val error = (gated.result as? com.aurviz.agent.ToolResult.Failure)?.error
            _state.update {
                it.copy(
                    sending = false,
                    statusLine = if (sent) "Sent ✓" else (error ?: "Not sent."),
                    timeline = buildTimeline(
                        Plan(listOf(call.copy(arguments = gated.effectiveArgs))),
                        doneCount = if (sent) 1 else 0,
                        error = error,
                    ),
                    perf = it.perf.copy(toolMs = ms),
                )
            }
            recordCompleted(
                responseText = if (sent) "Sent ✓" else (error ?: "Not sent."),
                failed = !sent,
                confirmTitle = "Send email to ${to.trim()}",
                confirmDetail = "To: ${to.trim()}\nSubject: $subject\n$body",
                confirmStatus = ConfirmationStatus.Approved,
            )
            if (activeJob === emailJob) activeJob = null
        }
        activeJob = emailJob
    }

    fun rejectEmail() {
        val draft = state.value.pendingEmail
        _state.update { it.copy(pendingEmail = null, statusLine = "Not sent.") }
        val detail = draft?.let { "To: ${it.to.joinToString()}\nSubject: ${it.subject}\n${it.body}" }
        recordCompleted(
            responseText = "Not sent.",
            confirmTitle = draft?.let { "Send email to ${it.to.joinToString()}" },
            confirmDetail = detail,
            confirmStatus = ConfirmationStatus.Denied,
        )
    }

    fun startSignIn() {
        if (signingIn) return
        signingIn = true
        signInDone = false
        _state.update { it.copy(statusLine = "Opening Google sign-in…") }
        viewModelScope.launch(Dispatchers.IO) {
            val receiver = try {
                LoopbackReceiver()
            } catch (e: Exception) {
                signingIn = false
                _state.update { it.copy(statusLine = "Sign-in failed: ${e.message}") }
                return@launch
            }
            val verifier = newCodeVerifier()
            pendingLinkVerifier = verifier
            gmail.redirectUri = receiver.redirectUri
            val url = buildAuthUrl(
                BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
                receiver.redirectUri,
                codeChallenge(verifier),
                GMAIL_SCOPES,
            )
            _state.update {
                it.copy(
                    authUrl = url,
                    pasteFallbackVisible = true,
                    loopbackHint = "Waiting for Google approval… (finish in the newest browser tab)",
                    statusLine = "Waiting for Google approval… (finish in the newest browser tab)",
                )
            }
            val code = receiver.waitForCode()
            signingIn = false
            if (signInDone) return@launch
            if (code == null) {
                _state.update {
                    it.copy(statusLine = "Time ran out — paste this attempt's link below, or Connect again.")
                }
                return@launch
            }
            finishSignIn(code, verifier)
        }
    }

    fun onPastedLink(pasted: String) {
        val code = Regex("[?&]code=([^&\\s]+)").find(pasted)?.groupValues?.get(1)
        val verifier = pendingLinkVerifier
        if (code == null || verifier == null) {
            _state.update { it.copy(statusLine = "No code in that link — copy the full address-bar link.") }
            return
        }
        val pastedPort = Regex("127\\.0\\.0\\.1:(\\d+)").find(pasted)?.groupValues?.get(1)
        val currentPort = Regex("127\\.0\\.0\\.1:(\\d+)").find(gmail.redirectUri)?.groupValues?.get(1)
        if (pastedPort == null || pastedPort != currentPort) {
            _state.update { it.copy(statusLine = "That link is from an older attempt — copy the newest tab's link.") }
            return
        }
        pendingLinkVerifier = null
        signInDone = true
        _state.update { it.copy(pasteFallbackVisible = false) }
        finishSignIn(code, verifier)
    }

    fun dismissPasteFallback() {
        _state.update { it.copy(pasteFallbackVisible = false) }
    }

    private fun finishSignIn(code: String, verifier: String) {
        if (code == lastExchangedCode) {
            _state.update { it.copy(statusLine = "That code was already used — Connect again for a fresh one.") }
            return
        }
        lastExchangedCode = code
        signInDone = true
        _state.update { it.copy(statusLine = "Got approval — finishing sign-in…") }
        viewModelScope.launch(Dispatchers.IO) {
            var attempt = 0
            while (true) {
                try {
                    gmail.exchangeCode(code, verifier)
                    break
                } catch (e: GmailException) {
                    _state.update { it.copy(statusLine = "Sign-in failed: ${e.message}") }
                    return@launch
                } catch (e: Exception) {
                    attempt++
                    if (attempt >= 3) {
                        _state.update { it.copy(statusLine = "Sign-in failed: ${e.message}") }
                        return@launch
                    }
                    _state.update { it.copy(statusLine = "Network hiccup — retrying… ($attempt/3)") }
                    try {
                        Thread.sleep(2000)
                    } catch (_: InterruptedException) {
                        return@launch
                    }
                }
            }
            try {
                tokens.gmailAccount = gmail.fetchProfileEmail()
            } catch (_: Exception) {
                tokens.gmailAccount = "connected"
            }
            tokenVault.saveFrom(tokens)
            refreshGmailStatus()
            _state.update {
                it.copy(
                    statusLine = "Gmail connected.",
                    pasteFallbackVisible = false,
                )
            }
        }
    }

    fun revokeGmail() {
        tokens.revoke()
        tokenVault.clearGmail()
        refreshGmailStatus()
        _state.update {
            it.copy(
                pendingEmail = null,
                pasteFallbackVisible = false,
                statusLine = "Gmail disconnected.",
            )
        }
        recordCompleted(responseText = "Gmail disconnected.")
    }

    private fun refreshGmailStatus() {
        _state.update {
            it.copy(
                gmailAccount = tokens.gmailAccount,
                gmailConnected = tokens.isConnected(),
            )
        }
    }

    // --- AI keys (ticket 36): user-entered, vault-persisted, never in UI state ---

    fun saveLlmKeys(geminiKey: String, groqKey: String) {
        val existing = tokenVault.loadLlmKeys()
        saveLlmKeys(geminiKey, groqKey, existing.deepseekKey, existing.metaKey)
    }

    fun saveLlmKeys(geminiKey: String, groqKey: String, deepseekKey: String, metaKey: String) {
        tokenVault.saveLlmKeys(
            LlmKeys(
                geminiKey = geminiKey.trim(),
                groqKey = groqKey.trim(),
                deepseekKey = deepseekKey.trim(),
                metaKey = metaKey.trim(),
            ),
        )
        refreshLlmKeyFlags()
    }

    fun clearLlmKeys() {
        tokenVault.clearLlmKeys()
        refreshLlmKeyFlags()
    }

    private fun refreshLlmKeyFlags() {
        val keys = tokenVault.loadLlmKeys()
        _state.update {
            it.copy(
                geminiKeySet = keys.geminiKey.isNotBlank(),
                groqKeySet = keys.groqKey.isNotBlank(),
                deepseekKeySet = keys.deepseekKey.isNotBlank(),
                metaKeySet = keys.metaKey.isNotBlank(),
            )
        }
    }

    // --- Web (in-app page behind BrowserScreen's retained WebView) ---

    fun openWeb(raw: String) {
        val typed = raw.trim()
        if (typed.isBlank()) return
        // Ticket 41/NG-5: the Browser tab routes through the shared allowlist
        // (routeBrowserUrl/isAllowedWebUrl) — the same decision the Tool layer
        // uses — and refuses non-web addresses with an explanation instead
        // of loading them. No https:// upgrade: bare hosts refuse like the
        // Tool layer. Web addresses pass through unchanged.
        when (val route = routeBrowserUrl(typed)) {
            is BrowserRoute.Refuse -> {
                _state.update {
                    it.copy(
                        statusLine = "Not opening: ${route.reason}",
                        webLoading = false,
                        webError = route.reason,
                        timeline = buildTimeline(
                            Plan(listOf(ToolCall("call_web1", "open", mapOf("url" to typed)))),
                            doneCount = 0,
                            error = route.reason,
                        ),
                    )
                }
                recordCompleted(responseText = "Not opening: ${route.reason}", failed = true)
                return
            }
            is BrowserRoute.Load -> {
                openWebUrl(route.url)
            }
        }
    }

    private fun openWebUrl(finalUrl: String) {
        val bridge = webBridge
        if (bridge == null) {
            _state.update { it.copy(statusLine = "Browser initializing — try again in a second.") }
            return
        }
        _state.update { it.copy(statusLine = "Opening $finalUrl…", webLoading = true, webError = null) }
        var webJob: Job? = null
        webJob = viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            try {
                val page = openPage(bridge, finalUrl)
                val ms = System.currentTimeMillis() - t0
                _state.update {
                    it.copy(
                        statusLine = page.title.ifBlank { finalUrl },
                        webPage = page,
                        webLoading = false,
                        timeline = buildTimeline(
                            Plan(listOf(ToolCall("call_web1", "open", mapOf("url" to finalUrl)))),
                            doneCount = 1,
                            error = null,
                        ),
                        perf = it.perf.copy(toolMs = ms),
                    )
                }
                recordCompleted(responseText = page.title.ifBlank { finalUrl })
            } catch (e: WebException) {
                _state.update {
                    it.copy(
                        statusLine = "Open failed: ${e.failure.reason} ($finalUrl)",
                        webLoading = false,
                        webError = "${e.failure.reason} ($finalUrl)",
                        timeline = buildTimeline(
                            Plan(listOf(ToolCall("call_web1", "open", mapOf("url" to finalUrl)))),
                            doneCount = 0,
                            error = e.failure.reason,
                        ),
                    )
                }
                recordCompleted(responseText = "Open failed: ${e.failure.reason} ($finalUrl)", failed = true)
            }
            if (activeJob === webJob) activeJob = null
        }
        activeJob = webJob
    }

    // --- Schedules (preview-gated save, same gate as email send) ---

    private fun handleScheduleCreate(raw: String) {
        val parsed = parseSchedule(raw)
        if (parsed == null) {
            _state.update {
                it.copy(
                    statusLine = "I couldn't find a time in that — try 'schedule every morning at 9AM check emails'.",
                    pendingSchedule = null,
                )
            }
            recordCompleted(failed = true)
            return
        }
        _state.update {
            it.copy(
                pendingSchedule = parsed,
                schedulePreview = formatSchedulePreview(parsed),
                statusLine = formatSchedulePreview(parsed),
            )
        }
        recordCompleted(responseText = formatSchedulePreview(parsed))
    }

    fun approveSchedule() {
        val parsed = _state.value.pendingSchedule ?: return
        _state.update { it.copy(pendingSchedule = null, statusLine = "Saving Schedule…") }
        viewModelScope.launch(Dispatchers.IO) {
            val saved = createSchedule(scheduleStore, parsed.instruction, parsed.hour, parsed.minute, parsed.days)
            val exact = registerRoutineAlarm(getApplication<Application>(), saved)
            refreshSchedules()
            refreshExactAlarmFlag()
            val line = if (exact) {
                "Schedule saved (${saved.id}). ${scheduleTimingNote(true)}"
            } else {
                "Schedule saved (${saved.id}). ${scheduleTimingNote(false)}"
            }
            _state.update { it.copy(statusLine = line) }
            recordCompleted(responseText = line)
        }
    }

    fun rejectSchedule() {
        _state.update { it.copy(pendingSchedule = null, statusLine = "Schedule discarded.") }
        recordCompleted(responseText = "Schedule discarded.")
    }

    // Pure UI formatting (Schedule.kt stays untouched).
    // Moved to top level below so screens can call them without a ViewModel.

    fun toggleScheduleEnabled(id: String, enabled: Boolean) {
        val app = getApplication<Application>()
        val updated = if (enabled) {
            state.value.disabledScheduleIds - id
        } else {
            state.value.disabledScheduleIds + id
        }
        UiPrefs.setDisabledScheduleIds(app.applicationContext, updated)
        _state.update { it.copy(disabledScheduleIds = updated) }
        viewModelScope.launch(Dispatchers.IO) {
            if (enabled) {
                listSchedules(scheduleStore).find { it.id == id }?.let { registerRoutineAlarm(app, it) }
            } else {
                cancelRoutineAlarm(app, id)
                cancelRoutineWork(app, id)
            }
        }
    }

    fun runScheduleNow(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val schedule = listSchedules(scheduleStore).find { it.id == id } ?: return@launch
            sendCommand(schedule.instruction)
        }
    }

    fun refreshScheduleRuns() {
        viewModelScope.launch(Dispatchers.IO) {
            val runs = RoomScheduleRunStore(db.runs()).list()
                .sortedByDescending { it.fireTime }
                .take(20)
                .map { run ->
                    ScheduleRunSummary(
                        id = "${run.scheduleId}-${run.fireTime}",
                        scheduleId = run.scheduleId,
                        status = run.status.name,
                        durationMs = run.durationMs,
                        summary = run.result ?: run.error,
                        timestampMs = run.fireTime,
                    )
                }
            _state.update { it.copy(scheduleRuns = runs) }
        }
    }

    // --- Pending approvals (held scheduled-run writes, ticket 15/28) ---
    // The Worker records blocked writes as PendingApproval rows plus a
    // notification (ADR-0002); this screen is where the user approves or
    // rejects them. Approve claims the row and runs the exact held
    // arguments through the single gated path, so a double-tap executes
    // exactly once (AlreadyHandled on the second tap). Approved schedule
    // writes also arm/disarm their alarms like the foreground preview path.

    fun refreshPendingApprovals() {
        viewModelScope.launch(Dispatchers.IO) {
            val all = RoomPendingApprovalStore(db.approvals()).list()
            _state.update { it.copy(pendingApprovals = all) }
        }
    }

    fun approveHeldWrite(id: String) {
        _state.update { it.copy(statusLine = "Running approved write…") }
        viewModelScope.launch(Dispatchers.IO) {
            val registry = defaultToolRegistry(gmailApi = gmail, scheduleStore = scheduleStore, memoryStore = roomStore)
            when (val outcome = approvePendingApproval(RoomPendingApprovalStore(db.approvals()), id, registry)) {
                is PendingApprovalOutcome.Executed -> {
                    if (outcome.result is ToolResult.Success) {
                        syncAlarmsForApproved(outcome.approval, outcome.result)
                        _state.update { it.copy(statusLine = "Approved — ran ${outcome.approval.toolName} ✓") }
                    } else {
                        val error = (outcome.result as ToolResult.Failure).error
                        _state.update { it.copy(statusLine = "Approved but failed: $error") }
                    }
                }
                is PendingApprovalOutcome.Rejected ->
                    _state.update { it.copy(statusLine = "Rejected — nothing ran.") }
                is PendingApprovalOutcome.AlreadyHandled ->
                    _state.update { it.copy(statusLine = "Already handled.") }
            }
            refreshPendingApprovals()
            refreshSchedules()
        }
    }

    fun rejectHeldWrite(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            when (rejectPendingApproval(RoomPendingApprovalStore(db.approvals()), id)) {
                is PendingApprovalOutcome.Rejected ->
                    _state.update { it.copy(statusLine = "Rejected — nothing ran.") }
                else -> _state.update { it.copy(statusLine = "Already handled.") }
            }
            refreshPendingApprovals()
        }
    }

    private fun syncAlarmsForApproved(approval: PendingApproval, result: ToolResult.Success) {
        val app = getApplication<Application>()
        when (val effect = onApprovedWrite(approval.toolName, approval.arguments, true, result)) {
            is ScheduleSideEffect.Arm -> {
                listSchedules(scheduleStore).find { it.id == effect.scheduleId }?.let { registerRoutineAlarm(app, it) }
            }
            is ScheduleSideEffect.Disarm -> {
                cancelRoutineWork(app, effect.scheduleId)
                cancelRoutineAlarm(app, effect.scheduleId)
            }
            ScheduleSideEffect.None -> Unit
        }
    }

    // Foreground loop side effects (ADR-0002): a create/cancel the Task
    // Execution ran arms/disarms exactly like the manual and held-approval
    // paths, so an approved natural-language Schedule fires without a
    // later global re-arm. Runs on the loop's IO thread; failures are
    // contained so they never break the Task Execution. Only an approved
    // Success acts: reject/timeout report Success(cancelled_by_user) with
    // approved=false, and the schedule id comes from the args that
    // actually ran (edited-approve), never the model's original call.
    private fun syncLoopScheduleSideEffect(event: AgentEvent.StepDone) {
        val call = event.call.copy(arguments = event.effectiveArgs)
        when (val effect = onApprovedWrite(call.name, call.arguments, event.approved, event.result)) {
            is ScheduleSideEffect.Arm -> {
                try {
                    val app = getApplication<Application>()
                    listSchedules(scheduleStore).find { it.id == effect.scheduleId }
                        ?.let { registerRoutineAlarm(app, it) }
                    refreshSchedules()
                    refreshExactAlarmFlag()
                } catch (_: Exception) {
                }
            }
            is ScheduleSideEffect.Disarm -> {
                try {
                    val app = getApplication<Application>()
                    cancelRoutineWork(app, effect.scheduleId)
                    cancelRoutineAlarm(app, effect.scheduleId)
                    refreshSchedules()
                } catch (_: Exception) {
                }
            }
            ScheduleSideEffect.None -> Unit
        }
    }

    private fun handleUnschedule(raw: String) {
        val id = raw.trim().let { if (it.startsWith("sched_")) it else "sched_$it" }
        viewModelScope.launch(Dispatchers.IO) {
            val known = listSchedules(scheduleStore).any { it.id == id }
            if (!known) {
                _state.update { it.copy(statusLine = "No Schedule $id.") }
                recordCompleted(responseText = "No Schedule $id.", failed = true)
                return@launch
            }
            deleteSchedule(scheduleStore, id)
            cancelRoutineWork(getApplication(), id)
            cancelRoutineAlarm(getApplication(), id)
            refreshSchedules()
            _state.update { it.copy(statusLine = "Schedule $id stopped.") }
            recordCompleted(responseText = "Schedule $id stopped.")
        }
    }

    fun unschedule(id: String) = handleUnschedule(id.removePrefix("sched_"))

    private fun refreshSchedules(showLine: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = listSchedules(scheduleStore)
            val line = if (showLine) {
                if (all.isEmpty()) {
                    "No Schedules yet."
                } else {
                    "Schedules:\n" + all.joinToString("\n") { r ->
                        "• ${r.id}: ${r.instruction} at ${r.hour}:${"%02d".format(r.minute)}"
                    }
                }
            } else {
                null
            }
            _state.update {
                it.copy(
                    schedules = all,
                    statusLine = line ?: it.statusLine,
                )
            }
            if (line != null) recordCompleted(responseText = line)
        }
    }

    fun refreshExactAlarmFlag() {
        val app = getApplication<Application>()
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val needsAsk = Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()
        _state.update { it.copy(exactAlarmNeeded = needsAsk) }
    }

    // Revocation/grant reconciliation (ADR-0002): Android cancels existing
    // exact alarms when the user revokes the permission. Re-register every
    // persisted Schedule so the alarm mode matches the current grant —
    // exact when allowed, inexact otherwise — then refresh the flags.
    // Called on every resume (covers start + back-from-Settings grant).
    fun reconcileAlarmsAfterResume() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                for (schedule in listSchedules(scheduleStore)) {
                    try {
                        registerRoutineAlarm(app, schedule)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
            refreshExactAlarmFlag()
            refreshBatteryFlag()
            // A Worker may have saved held approvals while the app was away.
            refreshSchedules()
            refreshPendingApprovals()
        }
    }

    fun refreshBatteryFlag() {
        val app = getApplication<Application>()
        val needsAsk = try {
            refreshBatteryNeeded(app)
        } catch (_: Exception) {
            false
        }
        _state.update { it.copy(batteryOptimizationNeeded = needsAsk) }
    }

    // --- Loop (AgentLoop for reads; ADR-0005 §3A) ---
    // Replaces runRealPlan's single plan() call plus createSchedule-only
    // execution: every command the keyword routes above don't catch runs
    // the loop, whose AgentEvents fold into the timeline live. The
    // schedule preview flows (handleScheduleCreate/approve/reject) stay.
    //
    // Ticket 12/25: the loop's confirm hook suspends on the approval card.
    // NeedsApproval publishes the exact call + unseen flags into state and
    // blocks the loop thread until approvePendingSend/rejectPendingSend
    // resolves it — the gate never auto-approves, and the deterministic
    // SendPolicy still screens an approved send before the transport.
    //
    // P0 binding: one ApprovalGate per ViewModel. Each gated call opens a
    // fresh one-shot ticket bound to an opaque per-proposal nonce; the card
    // buttons echo the nonce back (never the Tool call id) and stale
    // nonces are ignored. One Task Execution runs at a time so a second
    // command cannot replace the open card's queue.
    private val approvalGate = ApprovalGate()

    @Volatile
    private var currentRunId: Long? = null

    fun approvePendingSend(proposalId: String) {
        state.value.pendingApproval?.let { call ->
            val (title, detail) = confirmationTextFor(call)
            stampConfirmation(title, detail, ConfirmationStatus.Approved)
        }
        approvalGate.resolve(proposalId, ApprovalDecision.Approved)
    }

    fun approvePendingSendEdited(proposalId: String, args: Map<String, Any?>) {
        state.value.pendingApproval?.let { call ->
            val (title, detail) = confirmationTextFor(call, args)
            stampConfirmation(title, detail, ConfirmationStatus.Approved)
        }
        approvalGate.resolve(proposalId, ApprovalDecision.ApprovedWithEdit(args))
    }

    fun rejectPendingSend(proposalId: String) {
        state.value.pendingApproval?.let { call ->
            val (title, detail) = confirmationTextFor(call)
            stampConfirmation(title, detail, ConfirmationStatus.Denied)
        }
        approvalGate.resolve(proposalId, ApprovalDecision.Rejected)
    }

    private fun runLoopCommand(cmd: String) {
        val runId = approvalGate.startRun()
        if (runId == null) {
            _state.update {
                it.copy(statusLine = "Already running — resolve the current Confirmation first.")
            }
            return
        }
        currentRunId = runId
        _state.update {
            it.copy(
                statusLine = "Command: $cmd\nAgent: thinking…",
                timeline = emptyList(),
                providerUsed = null,
                providersTried = emptyList(),
                pendingApproval = null,
                pendingApprovalProposalId = null,
                pendingApprovalUnseen = emptyList(),
                agentRunning = true,
            )
        }
        var runJob: Job? = null
        runJob = viewModelScope.launch(Dispatchers.IO) {
            val runT0 = System.currentTimeMillis()
            val registry = defaultToolRegistry(gmailApi = gmail, scheduleStore = scheduleStore, memoryStore = roomStore)
            val chat =
                FallbackChatLlm(
                    defaultCandidates(effectiveProvider(), tokenVault.loadLlmKeys()),
                    // Ticket 39 (minimal): the model sees this run's
                    // registry view — every Tool it can call, gated or not.
                    registry.toolSchemas(),
                )
            val confirm: (ToolCall) -> ApprovalDecision = { call ->
                // Ticket #68: allow-always skips the card for one listed
                // recipient. This only skips the ask — the gated path still
                // runs SendPolicy, so policy denial wins over the grant.
                if (allowAlwaysEligible(call, allowAlwaysStore)) {
                    ApprovalDecision.Approved
                } else {
                    val callId = call.id.orEmpty()
                val ticket = approvalGate.openGate(runId, callId)
                if (ticket == null || callId.isEmpty()) {
                    ApprovalDecision.Rejected
                } else {
                    val known =
                        try {
                            knownAddressesFromInbox(gmail.listInbox())
                        } catch (_: Exception) {
                            knownAddressesFromInbox(_state.value.emails)
                        }
                    val unseen = approvalCardFor(call, known)?.unseen.orEmpty()
                    _state.update {
                        it.copy(
                            pendingApproval = call,
                            pendingApprovalProposalId = ticket.proposalId,
                            pendingApprovalUnseen = unseen,
                            statusLine = "Command: $cmd\nAgent: needs approval: ${call.name}",
                        )
                    }
                    // Bounded wait: expiry becomes TimedOut (cancelled_by_user),
                    // never an auto-approve. The wait is capped by the run's
                    // remaining total budget, so a slow first Tool cannot park
                    // the card past the deadline. Interrupt also maps to
                    // TimedOut so cancellation never releases the Tool.
                    try {
                        val waitMs = approvalWaitMs(System.currentTimeMillis() - runT0, AgentLoop.DEFAULT_TOTAL_TIMEOUT_MS)
                        ticket.queue.poll(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                            ?: ApprovalDecision.TimedOut
                    } catch (_: InterruptedException) {
                        ApprovalDecision.TimedOut
                    }
                    }
                }
            }
            var progress = LoopUiProgress()
            val loop =
                AgentLoop(
                    chat,
                    registry,
                    onEvent = { event ->
                        if (event is AgentEvent.StepDone) {
                            syncLoopScheduleSideEffect(event)
                        }
                        progress = progress.withEvent(event)
                        val snapshot = progress.timeline()
                        val phase =
                            when (event) {
                                is AgentEvent.StepStarted -> "thinking…"
                                is AgentEvent.ToolRunning -> "running ${event.call.name}…"
                                is AgentEvent.NeedsApproval -> "needs approval: ${event.call.name}"
                                is AgentEvent.StepDone -> "ran ${event.call.name}"
                                is AgentEvent.Failed -> "failed: ${event.error}"
                            }
                        _state.update {
                            it.copy(
                                statusLine = "Command: $cmd\nAgent: $phase",
                                timeline = snapshot,
                                // The decision landed: drop the card.
                                pendingApproval = if (event is AgentEvent.StepDone) null else it.pendingApproval,
                                pendingApprovalProposalId = if (event is AgentEvent.StepDone) null else it.pendingApprovalProposalId,
                                pendingApprovalUnseen = if (event is AgentEvent.StepDone) emptyList() else it.pendingApprovalUnseen,
                            )
                        }
                    },
                    confirm = confirm,
                )
            try {
                // Ticket 16/29: memories ride in the loop's delimited system
                // section via recallFor — never concatenated into the command.
                // Top 5 only, matching the benchmarked prompt budget.
                // Issue #51: thread history + signed-in identity ride too;
                // scheduled runs never enter runLoopCommand so they stay cold.
                val memories = recallFor(roomStore, cmd).take(5)
                _state.update { it.copy(activeRecalledMemories = memories.map { m -> m.text }) }
                val history = synchronized(sessionTranscript) { sessionTranscript.toList() }
                val accountEmail = tokens.gmailAccount ?: state.value.gmailAccount
                when (val result = loop.run(cmd, memories, accountEmail, history)) {
                    is AgentLoopResult.Answered -> {
                        synchronized(sessionTranscript) { appendSessionTurn(sessionTranscript, cmd, result) }
                        val tried = chat.tried.toList()
                        val used = chat.lastProvider
                        val disclosure =
                            AgentUiState(providerUsed = used, providersTried = tried).retryLine()
                        _state.update {
                            it.copy(
                                statusLine = "Command: $cmd\n${result.answer}" +
                                    (disclosure?.let { d -> "\n$d" } ?: ""),
                                timeline = result.toTimeline(),
                                providerUsed = used,
                                providersTried = tried,
                                pendingApproval = null,
                                pendingApprovalProposalId = null,
                                pendingApprovalUnseen = emptyList(),
                                perf = it.perf.copy(planMs = System.currentTimeMillis() - runT0),
                                sessionId = activeSessionId,
                                sessionTurns = synchronized(sessionTranscript) { sessionTranscript.size / 2 },
                            )
                        }
                        recordCompleted(responseText = result.answer)
                    }
                    is AgentLoopResult.Failed -> {
                        synchronized(sessionTranscript) { appendSessionTurn(sessionTranscript, cmd, result) }
                        _state.update {
                            it.copy(
                                statusLine = "Command: $cmd\nFailed: ${result.error}",
                                timeline = result.toTimeline(),
                                providerUsed = chat.lastProvider,
                                providersTried = chat.tried.toList(),
                                pendingApproval = null,
                                pendingApprovalProposalId = null,
                                pendingApprovalUnseen = emptyList(),
                                perf = it.perf.copy(planMs = System.currentTimeMillis() - runT0),
                                sessionId = activeSessionId,
                                sessionTurns = synchronized(sessionTranscript) { sessionTranscript.size / 2 },
                            )
                        }
                        recordCompleted(responseText = "Failed: ${result.error}", failed = true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        statusLine = "Command: $cmd\nFailed: ${e.message}",
                        timeline = progress.timeline(),
                        providerUsed = chat.lastProvider,
                        providersTried = chat.tried.toList(),
                        pendingApproval = null,
                        pendingApprovalProposalId = null,
                        pendingApprovalUnseen = emptyList(),
                    )
                }
                recordCompleted(responseText = "Failed: ${e.message}", failed = true)
            } finally {
                approvalGate.finishRun(runId)
                if (currentRunId == runId) currentRunId = null
                if (activeJob === runJob) activeJob = null
                _state.update {
                    it.copy(
                        agentRunning = false,
                        pendingApproval = null,
                        pendingApprovalProposalId = null,
                        pendingApprovalUnseen = emptyList(),
                    )
                }
            }
        }
        activeJob = runJob
    }

    // --- Voice (transcript only; onRmsChanged never reaches here) ---

    fun setVoiceListening(listening: Boolean) {
        _state.update {
            it.copy(
                voice = it.voice.copy(
                    listening = listening,
                    orb = if (listening) OrbState.Listening else OrbState.Muted,
                ),
            )
        }
    }

    fun onVoicePartial(text: String) {
        _state.update {
            it.copy(voice = it.voice.copy(transcript = text, orb = OrbState.Hearing))
        }
    }

    fun onVoiceFinal(text: String) {
        _state.update {
            it.copy(voice = it.voice.copy(transcript = text, listening = false, orb = OrbState.Thinking))
        }
    }

    fun onVoiceError(message: String) {
        _state.update {
            it.copy(voice = it.voice.copy(error = message, listening = false, orb = OrbState.Muted))
        }
    }

    fun setOrb(state: OrbState) {
        _state.update { it.copy(voice = it.voice.copy(orb = state)) }
    }

    override fun onCleared() {
        try {
            db.close()
        } catch (_: Exception) {
        }
        super.onCleared()
    }
}

// Pure UI formatting for the grouped Schedule card (Schedule.kt stays
// untouched). Top level so screens can call them without a ViewModel.

// Cron line, e.g. "9 9 * * *" or "0 9 * * 1,3,5".
fun formatScheduleCron(schedule: Schedule): String {
    val daysExpr = schedule.days?.sorted()?.joinToString(",") ?: "*"
    return "${schedule.minute} ${schedule.hour} * * $daysExpr"
}

// Human line, e.g. "Daily at 09:00" or "Mon, Wed, Fri at 09:00".
fun formatScheduleTime(schedule: Schedule): String {
    val time = "%02d:%02d".format(schedule.hour, schedule.minute)
    val days = schedule.days
    if (days.isNullOrEmpty()) return "Daily at $time"
    val names = mapOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun")
    return days.sorted().map { names[it] ?: "$it" }.joinToString(", ") + " at $time"
}
