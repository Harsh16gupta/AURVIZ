package com.aurviz.agent.ui

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.aurviz.agent.AppDb
import com.aurviz.agent.BuildConfig
import com.aurviz.agent.EmailDraft
import com.aurviz.agent.GMAIL_SCOPES
import com.aurviz.agent.GmailException
import com.aurviz.agent.GmailHttpApi
import com.aurviz.agent.LoopbackReceiver
import com.aurviz.agent.LlmProvider
import com.aurviz.agent.MIGRATION_1_2
import com.aurviz.agent.Memory
import com.aurviz.agent.Plan
import com.aurviz.agent.PlannerOutcome
import com.aurviz.agent.RoomMemoryStore
import com.aurviz.agent.RoomScheduleStore
import com.aurviz.agent.TokenStore
import com.aurviz.agent.TokenVault
import com.aurviz.agent.ToolCall
import com.aurviz.agent.WebBridge
import com.aurviz.agent.WebException
import com.aurviz.agent.buildAuthUrl
import com.aurviz.agent.buildRawMessage
import com.aurviz.agent.buildTimeline
import com.aurviz.agent.cancelRoutineAlarm
import com.aurviz.agent.cancelRoutineWork
import com.aurviz.agent.codeChallenge
import com.aurviz.agent.composeEmail
import com.aurviz.agent.createSchedule
import com.aurviz.agent.defaultCandidates
import com.aurviz.agent.deleteSchedule
import com.aurviz.agent.formatSchedulePreview
import com.aurviz.agent.forgetPreview
import com.aurviz.agent.listSchedules
import com.aurviz.agent.newCodeVerifier
import com.aurviz.agent.openPage
import com.aurviz.agent.parseSchedule
import com.aurviz.agent.planCommand
import com.aurviz.agent.planWithFallback
import com.aurviz.agent.readImportant
import com.aurviz.agent.recallFor
import com.aurviz.agent.registerRoutineAlarm
import com.aurviz.agent.rememberFact
import com.aurviz.agent.sendWithConfirm
import com.aurviz.agent.ui.components.VoiceState as OrbState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Thin orchestrator over the tested domain logic (Planner, Email, Web,
// WebViewBridge, MemoryStore, VoiceInput, LoopbackAuth). All decisions stay
// in those files; this class only moves results into AgentUiState.
class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private val db: AppDb =
        Room.databaseBuilder(application, AppDb::class.java, "aurviz.db")
            .addMigrations(MIGRATION_1_2)
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
        refreshGmailStatus()
        refreshMemories()
        refreshSchedules()
        refreshExactAlarmFlag()
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
            else -> runRealPlan(command)
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
    fun approveEmail(to: String, subject: String, body: String) {
        val draft = EmailDraft(to = listOf(to.trim()), subject = subject, body = body)
        if (!draft.to.first().contains("@") || draft.body.isBlank()) {
            _state.update { it.copy(statusLine = "Need a valid address and a message.") }
            return
        }
        _state.update { it.copy(pendingEmail = null, sending = true, statusLine = "Sending…") }
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            try {
                val sent = sendWithConfirm(gmail, buildRawMessage(draft), confirmed = true)
                val ms = System.currentTimeMillis() - t0
                _state.update {
                    it.copy(
                        sending = false,
                        statusLine = if (sent) "Sent ✓" else "Not sent.",
                        timeline = buildTimeline(
                            Plan(listOf(ToolCall("gmail", "sendEmail", mapOf("raw" to 12)))),
                            doneCount = if (sent) 1 else 0,
                            error = if (sent) null else "not confirmed",
                        ),
                        perf = it.perf.copy(toolMs = ms),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(sending = false, statusLine = "Send failed: ${e.message}", timeline = emptyList())
                }
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
                        "Schedule saved (${saved.id}). Exact alarm set."
                    } else {
                        "Schedule saved (${saved.id}), but exact alarms are off — allow them in Settings."
                    },
                )
            }
        }
    }

    fun rejectSchedule() {
        _state.update { it.copy(pendingSchedule = null, statusLine = "Schedule discarded.") }
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

    // --- Real Plan (LLM with fallback; recall timed separately) ---

    private fun runRealPlan(cmd: String) {
        _state.update {
            it.copy(
                statusLine = "Command: $cmd\nAgent: thinking…",
                timeline = emptyList(),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val recallT0 = System.currentTimeMillis()
            val known = recallFor(roomStore, cmd).take(3)
            val recallMs = System.currentTimeMillis() - recallT0
            _state.update { it.copy(perf = it.perf.copy(recallMs = recallMs)) }
            val knownLine = if (known.isEmpty()) "" else "\nKnown: " + known.joinToString("; ") { it.text }
            _state.update { it.copy(statusLine = "Command: $cmd$knownLine\nAgent: thinking…") }
            val effective =
                if (known.isEmpty()) cmd else "Known: " + known.joinToString("; ") { it.text } + ". $cmd"
            var used = ""
            val provider = object : LlmProvider {
                override fun plan(userText: String): com.aurviz.agent.LlmResult {
                    val fb = planWithFallback(userText, defaultCandidates(BuildConfig.LLM_DEFAULT_PROVIDER))
                    used = fb.providerName
                    return fb.result
                }
            }
            try {
                val planT0 = System.currentTimeMillis()
                val outcome = planCommand(effective, provider)
                val planMs = System.currentTimeMillis() - planT0
                when (outcome) {
                    is PlannerOutcome.NeedsClarification -> {
                        _state.update {
                            it.copy(
                                statusLine = "Command: $cmd$knownLine\nAgent: ${outcome.question}",
                                timeline = buildTimeline(Plan(emptyList()), doneCount = 0, error = null),
                                perf = it.perf.copy(planMs = planMs),
                            )
                        }
                    }
                    is PlannerOutcome.Ready -> {
                        val first = outcome.plan.steps.firstOrNull()
                        if (first?.name == "createSchedule") {
                            val when_ = first.arguments["when"] as? String ?: ""
                            val task = first.arguments["task"] as? String ?: ""
                            handleScheduleCreate("$when_ $task".trim())
                            _state.update { it.copy(perf = it.perf.copy(planMs = planMs)) }
                        } else {
                            _state.update {
                                it.copy(
                                    statusLine = "Command: $cmd$knownLine\nRan on: $used",
                                    timeline = buildTimeline(outcome.plan, doneCount = 0, error = null),
                                    providerUsed = used,
                                    perf = it.perf.copy(planMs = planMs),
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        statusLine = "Command: $cmd$knownLine\nFailed: ${e.message}",
                        timeline = emptyList(),
                    )
                }
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
