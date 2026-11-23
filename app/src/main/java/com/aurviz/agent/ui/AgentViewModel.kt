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
import com.aurviz.agent.BuildConfig
import com.aurviz.agent.ChatLlmProvider
import com.aurviz.agent.ChatMessage
import com.aurviz.agent.FallbackResult
import com.aurviz.agent.GMAIL_SCOPES
import com.aurviz.agent.GmailException
import com.aurviz.agent.GmailHttpApi
import com.aurviz.agent.LoopbackReceiver
import com.aurviz.agent.LlmKeys
import com.aurviz.agent.LlmResult
import com.aurviz.agent.MIGRATION_1_2
import com.aurviz.agent.MIGRATION_2_3
import com.aurviz.agent.MIGRATION_3_4
import com.aurviz.agent.Memory
import com.aurviz.agent.NamedProvider
import com.aurviz.agent.PendingApproval
import com.aurviz.agent.PendingApprovalOutcome
import com.aurviz.agent.Plan
import com.aurviz.agent.RoomPendingApprovalStore
import com.aurviz.agent.TimelineStep
import com.aurviz.agent.RoomMemoryStore
import com.aurviz.agent.RoomScheduleStore
import com.aurviz.agent.TokenStore
import com.aurviz.agent.TokenVault
import com.aurviz.agent.ToolCall
import com.aurviz.agent.ToolResult
import com.aurviz.agent.WebBridge
import com.aurviz.agent.WebException
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
import com.aurviz.agent.newCodeVerifier
import com.aurviz.agent.openPage
import com.aurviz.agent.parseSchedule
import com.aurviz.agent.planWithFallback
import com.aurviz.agent.refreshBatteryNeeded
import com.aurviz.agent.scheduleTimingNote
import com.aurviz.agent.readImportant
import com.aurviz.agent.recallFor
import com.aurviz.agent.registerRoutineAlarm
import com.aurviz.agent.rejectPendingApproval
import com.aurviz.agent.rememberFact
import com.aurviz.agent.retryDisclosure
import com.aurviz.agent.toTimeline
import com.aurviz.agent.ui.components.VoiceState as OrbState
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Thin orchestrator over the tested domain logic (Planner, Email, Web,
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

// Deterministic messages→prompt rendering for the single-shot fallback
// seam below: the system prompt (framing + delimited memories) stays
// first, assistant turns keep their Tool names, tool turns keep their
// JSON results tagged with the matching tool_call_id (ADR-0005).
fun renderLoopPrompt(messages: List<ChatMessage>): String =
    messages.joinToString("\n") { message ->
        when (message.role) {
            "system" -> "[system] ${message.content.orEmpty()}"
            "assistant" ->
                ("[assistant] " + message.content.orEmpty() +
                    message.toolCalls.joinToString(" ", prefix = " ") { it.name }).trim()
            "tool" -> "[tool ${message.toolCallId}] ${message.content.orEmpty()}"
            else -> message.content.orEmpty()
        }
    }

// ChatLlmProvider over the single-shot planWithFallback seam: every loop
// turn replays the rendered messages through the candidates in order and
// unions every provider that saw the prompt, so the ViewModel copies
// providersTried verbatim and the retry is never silent (ADR-0004).
// The candidate list is built once per run: rebuilding it per turn would
// also rebuild each provider's HTTP client.
class FallbackChatLlm(
    private val candidates: List<NamedProvider>,
) : ChatLlmProvider {
    val tried: MutableList<String> = mutableListOf()
    var lastProvider: String? = null
        private set

    override fun chat(messages: List<ChatMessage>): LlmResult {
        val fb = planWithFallback(renderLoopPrompt(messages), candidates)
        for (name in fb.tried) if (name !in tried) tried += name
        lastProvider = fb.providerName
        return fb.result
    }
}

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private val db: AppDb =
        Room.databaseBuilder(application, AppDb::class.java, "aurviz.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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

    init {
        tokenVault.loadInto(tokens)
        refreshLlmKeyFlags()
        refreshGmailStatus()
        refreshMemories()
        refreshSchedules()
        refreshPendingApprovals()
        refreshExactAlarmFlag()
        refreshBatteryFlag()
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
        _state.update { it.copy(inputText = "") }
        val lower = command.lowercase()
        when {
            lower.startsWith("remember ") -> handleRemember(command.substring(9).trim())
            lower.startsWith("forget ") -> handleForgetPreview(command.substring(7).trim())
            lower.startsWith("email ") -> handleEmailSend(command.substring(6).trim())
            lower == "check mail" || lower == "check email" ||
                lower == "check important emails" || lower == "check emails" -> handleEmailRead()
            lower == "disconnect gmail" -> revokeGmail()
            lower.startsWith("open ") -> openWeb(command.substring(5).trim())
            lower.startsWith("schedule ") -> handleScheduleCreate(command.substring(9).trim())
            lower == "schedules" -> refreshSchedules(showLine = true)
            lower.startsWith("unschedule ") -> handleUnschedule(command.substring(11).trim())
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
        }
    }

    fun rejectForget() {
        _state.update { it.copy(pendingForget = emptyList(), statusLine = "Kept.") }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            roomStore.delete(id)
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
        _state.update { it.copy(pendingEmail = null, sending = true, statusLine = "Sending…") }
        viewModelScope.launch(Dispatchers.IO) {
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
        }
    }

    fun rejectEmail() {
        _state.update { it.copy(pendingEmail = null, statusLine = "Not sent.") }
    }

    private fun handleEmailRead() {
        if (!tokens.isConnected()) {
            _state.update { it.copy(statusLine = "Connect Gmail first (Settings).") }
            return
        }
        _state.update { it.copy(statusLine = "Checking mailbox…") }
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            try {
                val found = readImportant(gmail, max = 5)
                val ms = System.currentTimeMillis() - t0
                _state.update {
                    it.copy(
                        statusLine = if (found.isEmpty()) {
                            "Inbox zero — nothing unread."
                        } else {
                            "Important:\n" + found.joinToString("\n") { e -> "• ${e.from}: ${e.subject}" }
                        },
                        emails = found,
                        timeline = buildTimeline(
                            Plan(listOf(ToolCall("gmail", "getImportantEmails", mapOf("max_results" to 5)))),
                            doneCount = 1,
                            error = null,
                        ),
                        perf = it.perf.copy(toolMs = ms),
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(statusLine = "Read failed: ${e.message}", timeline = emptyList()) }
            }
        }
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
        tokenVault.clear()
        refreshGmailStatus()
        _state.update {
            it.copy(
                pendingEmail = null,
                pasteFallbackVisible = false,
                statusLine = "Gmail disconnected.",
            )
        }
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
        tokenVault.saveLlmKeys(LlmKeys(geminiKey.trim(), groqKey.trim()))
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
            )
        }
    }

    // --- Web (in-app page behind BrowserScreen's retained WebView) ---

    fun openWeb(raw: String) {
        var url = raw.trim()
        if (url.isBlank()) return
        if (!url.contains("://")) url = "https://$url"
        val finalUrl = url
        val bridge = webBridge
        if (bridge == null) {
            _state.update { it.copy(statusLine = "Browser initializing — try again in a second.") }
            return
        }
        _state.update { it.copy(statusLine = "Opening $finalUrl…", webLoading = true, webError = null) }
        viewModelScope.launch(Dispatchers.IO) {
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
            }
        }
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
            return
        }
        _state.update {
            it.copy(
                pendingSchedule = parsed,
                schedulePreview = formatSchedulePreview(parsed),
                statusLine = formatSchedulePreview(parsed),
            )
        }
    }

    fun approveSchedule() {
        val parsed = _state.value.pendingSchedule ?: return
        _state.update { it.copy(pendingSchedule = null, statusLine = "Saving Schedule…") }
        viewModelScope.launch(Dispatchers.IO) {
            val saved = createSchedule(scheduleStore, parsed.instruction, parsed.hour, parsed.minute, parsed.days)
            val exact = registerRoutineAlarm(getApplication<Application>(), saved)
            refreshSchedules()
            refreshExactAlarmFlag()
            _state.update {
                it.copy(
                    statusLine = if (exact) {
                        "Schedule saved (${saved.id}). ${scheduleTimingNote(true)}"
                    } else {
                        "Schedule saved (${saved.id}). ${scheduleTimingNote(false)}"
                    },
                )
            }
        }
    }

    fun rejectSchedule() {
        _state.update { it.copy(pendingSchedule = null, statusLine = "Schedule discarded.") }
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
        when (approval.toolName) {
            "create_schedule" -> {
                val newId = (result.data as? Map<*, *>)?.get("id") as? String ?: return
                listSchedules(scheduleStore).find { it.id == newId }?.let { registerRoutineAlarm(app, it) }
            }
            "cancel_schedule" -> {
                val target = approval.arguments["schedule_id"] as? String ?: return
                cancelRoutineWork(app, target)
                cancelRoutineAlarm(app, target)
            }
        }
    }

    private fun handleUnschedule(raw: String) {
        val id = raw.trim().let { if (it.startsWith("sched_")) it else "sched_$it" }
        viewModelScope.launch(Dispatchers.IO) {
            val known = listSchedules(scheduleStore).any { it.id == id }
            if (!known) {
                _state.update { it.copy(statusLine = "No Schedule $id.") }
                return@launch
            }
            deleteSchedule(scheduleStore, id)
            cancelRoutineWork(getApplication(), id)
            cancelRoutineAlarm(getApplication(), id)
            refreshSchedules()
            _state.update { it.copy(statusLine = "Schedule $id stopped.") }
        }
    }

    fun unschedule(id: String) = handleUnschedule(id.removePrefix("sched_"))

    private fun refreshSchedules(showLine: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = listSchedules(scheduleStore)
            _state.update {
                it.copy(
                    schedules = all,
                    statusLine = if (showLine) {
                        if (all.isEmpty()) {
                            "No Schedules yet."
                        } else {
                            "Schedules:\n" + all.joinToString("\n") { r ->
                                "• ${r.id}: ${r.instruction} at ${r.hour}:${"%02d".format(r.minute)}"
                            }
                        }
                    } else {
                        it.statusLine
                    },
                )
            }
        }
    }

    fun refreshExactAlarmFlag() {
        val app = getApplication<Application>()
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val needsAsk = Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()
        _state.update { it.copy(exactAlarmNeeded = needsAsk) }
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
    private var pendingDecisions: LinkedBlockingQueue<ApprovalDecision>? = null

    fun approvePendingSend() {
        pendingDecisions?.offer(ApprovalDecision.Approved)
    }

    fun rejectPendingSend() {
        pendingDecisions?.offer(ApprovalDecision.Rejected)
    }

    private fun runLoopCommand(cmd: String) {
        _state.update {
            it.copy(
                statusLine = "Command: $cmd\nAgent: thinking…",
                timeline = emptyList(),
                providerUsed = null,
                providersTried = emptyList(),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val runT0 = System.currentTimeMillis()
            val chat =
                FallbackChatLlm(
                    defaultCandidates(BuildConfig.LLM_DEFAULT_PROVIDER, tokenVault.loadLlmKeys()),
                )
            val registry = defaultToolRegistry(gmailApi = gmail, scheduleStore = scheduleStore, memoryStore = roomStore)
            val decisions = LinkedBlockingQueue<ApprovalDecision>(1)
            pendingDecisions = decisions
            val confirm: (ToolCall) -> ApprovalDecision = { call ->
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
                        pendingApprovalUnseen = unseen,
                        statusLine = "Command: $cmd\nAgent: needs approval: ${call.name}",
                    )
                }
                try {
                    decisions.take()
                } catch (_: InterruptedException) {
                    ApprovalDecision.TimedOut
                }
            }
            var progress = LoopUiProgress()
            val loop =
                AgentLoop(
                    chat,
                    registry,
                    onEvent = { event ->
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
                                pendingApprovalUnseen = if (event is AgentEvent.StepDone) emptyList() else it.pendingApprovalUnseen,
                            )
                        }
                    },
                    confirm = confirm,
                )
            try {
                // Ticket 16/29: memories ride in the loop's delimited system
                // section via recallFor — never concatenated into the command.
                val memories = recallFor(roomStore, cmd)
                when (val result = loop.run(cmd, memories)) {
                    is AgentLoopResult.Answered -> {
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
                                pendingApprovalUnseen = emptyList(),
                                perf = it.perf.copy(planMs = System.currentTimeMillis() - runT0),
                            )
                        }
                    }
                    is AgentLoopResult.Failed -> {
                        _state.update {
                            it.copy(
                                statusLine = "Command: $cmd\nFailed: ${result.error}",
                                timeline = result.toTimeline(),
                                providerUsed = chat.lastProvider,
                                providersTried = chat.tried.toList(),
                                pendingApproval = null,
                                pendingApprovalUnseen = emptyList(),
                                perf = it.perf.copy(planMs = System.currentTimeMillis() - runT0),
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        statusLine = "Command: $cmd\nFailed: ${e.message}",
                        timeline = progress.timeline(),
                        providerUsed = chat.lastProvider,
                        providersTried = chat.tried.toList(),
                        pendingApproval = null,
                        pendingApprovalUnseen = emptyList(),
                    )
                }
            } finally {
                if (pendingDecisions === decisions) pendingDecisions = null
            }
        }
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
