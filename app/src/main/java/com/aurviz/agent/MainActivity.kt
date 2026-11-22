package com.aurviz.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

// T2 screen: mic button + text field. The decisions (which language,
// what an error means, voice-vs-typed) live in VoiceInput.kt and are
// unit-tested; this file only touches Android classes and is tested
// on a real device from Android Studio.
class MainActivity : Activity() {

    private lateinit var typedInput: EditText
    private lateinit var liveText: TextView
    private lateinit var micButton: Button
    private lateinit var timelineView: TextView
    private lateinit var retryButton: Button
    private lateinit var memoryView: TextView
    private lateinit var forgetButton: Button
    private lateinit var roomStore: MemoryStore
    private var pendingForget: List<Memory> = emptyList()
    private lateinit var connectButton: Button
    private lateinit var sendConfirmButton: Button
    private val tokens = TokenStore()
    private lateinit var gmail: GmailHttpApi
    private var pendingDraft: Pair<EmailDraft, String>? = null
    private var signingIn = false
    private lateinit var pasteInput: EditText
    private lateinit var useLinkButton: Button
    private lateinit var webView: android.webkit.WebView
    private lateinit var webBridge: WebBridge
    private var pendingLinkVerifier: String? = null
    private var lastExchangedCode: String? = null
    private var signInDone = false
    private var lastCommand: String? = null
    private var currentRows: List<TimelineStep> = emptyList()
    private var recognizer: SpeechRecognizer? = null
    private var voiceText: String? = null
    private var retriedOnline = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        typedInput = EditText(this).apply { hint = "Type a command (always works)" }
        liveText = TextView(this).apply { textSize = 16f }
        micButton = Button(this).apply { text = "🎤 Speak" }
        val sendButton = Button(this).apply { text = "Send" }
        timelineView = TextView(this).apply { textSize = 14f }
        retryButton = Button(this).apply {
            text = "Retry failed step"
            visibility = android.view.View.GONE
        }
        // Session 1 wiring: the on-phone memory (Room survives restarts).
        // Small lists only, so main-thread queries are fine for this demo;
        // Spec 07 moves database work off the main thread.
        roomStore =
            RoomMemoryStore(
                androidx.room.Room.databaseBuilder(this, AppDb::class.java, "aurviz.db")
                    .allowMainThreadQueries()
                    .build()
                    .memories(),
            )
        memoryView = TextView(this).apply { textSize = 14f }
        forgetButton = Button(this).apply {
            visibility = android.view.View.GONE
        }
        gmail = GmailHttpApi(tokens, BuildConfig.GOOGLE_OAUTH_CLIENT_ID, BuildConfig.GOOGLE_OAUTH_CLIENT_SECRET)
        connectButton = Button(this).apply { text = "Connect Gmail" }
        sendConfirmButton = Button(this).apply {
            visibility = android.view.View.GONE
        }
        pasteInput = EditText(this).apply {
            hint = "Paste the callback link here (fallback)"
            visibility = android.view.View.GONE
        }
        useLinkButton = Button(this).apply {
            text = "Use pasted link"
            visibility = android.view.View.GONE
        }
        // Session 2 Web: the in-app page. Small height keeps the chat visible.
        webView = android.webkit.WebView(this).apply {
            settings.javaScriptEnabled = true
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    600,
                )
        }
        webBridge = WebViewBridge(webView)

        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                addView(micButton)
                addView(liveText)
                addView(typedInput)
                addView(sendButton)
                addView(timelineView)
                addView(retryButton)
                addView(memoryView)
                addView(forgetButton)
                addView(connectButton)
                addView(sendConfirmButton)
                addView(pasteInput)
                addView(useLinkButton)
                addView(webView)
            }
        setContentView(layout)

        refreshMicButton()
        renderMemories()
        renderGmailStatus()
        connectButton.setOnClickListener {
            // Desktop-ID + loopback sign-in (Google's rule for native apps):
            // we listen on 127.0.0.1 and Google sends the code back there.
            // One attempt at a time: each tap makes a NEW one-time address,
            // so finishing in an old browser tab can never work.
            if (signingIn) return@setOnClickListener
            signingIn = true
            liveText.text = "Opening Google sign-in…"
            Thread {
                val receiver =
                    try {
                        LoopbackReceiver()
                    } catch (e: Exception) {
                        signingIn = false
                        runOnUiThread { liveText.text = "Sign-in failed: ${e.message}" }
                        return@Thread
                    }
                val verifier = newCodeVerifier()
                pendingLinkVerifier = verifier
                gmail.redirectUri = receiver.redirectUri
                val url =
                    buildAuthUrl(
                        BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
                        receiver.redirectUri,
                        codeChallenge(verifier),
                        GMAIL_SCOPES,
                    )
                runOnUiThread {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    liveText.text = "Waiting for Google approval… (finish in the newest browser tab)"
                    pasteInput.visibility = android.view.View.VISIBLE
                    useLinkButton.visibility = android.view.View.VISIBLE
                }
                val code = receiver.waitForCode()
                signingIn = false
                if (signInDone) return@Thread
                if (code == null) {
                    // True timeout (10 min). The paste box stays: this attempt's
                    // verifier is still valid, so a pasted link still works.
                    runOnUiThread { liveText.text = "Time ran out — tap Connect once for a fresh link, or paste this attempt's link below." }
                    return@Thread
                }
                finishSignIn(code, verifier)
            }.start()
        }
        forgetButton.setOnClickListener {
            // Confirmed: delete exactly the previewed ids, nothing else.
            pendingForget.forEach { roomStore.delete(it.id) }
            liveText.text = "Deleted ${pendingForget.size}."
            pendingForget = emptyList()
            forgetButton.visibility = android.view.View.GONE
            renderMemories()
        }
        sendConfirmButton.setOnClickListener {
            // The only path that ever sends mail: preview was shown first.
            sendPendingDraft()
        }
        useLinkButton.setOnClickListener {
            // Manual fallback: the error page still shows the full callback
            // link (?code=...) in the address bar — paste it, we take it.
            val pasted = pasteInput.text.toString()
            val code = Regex("[?&]code=([^&\\s]+)").find(pasted)?.groupValues?.get(1)
            val verifier = pendingLinkVerifier
            if (code == null || verifier == null) {
                liveText.text = "No code in that link — copy the full address-bar link."
                return@setOnClickListener
            }
            // A link only fits the attempt that made it: same address, same secret.
            val pastedPort = Regex("127\\.0\\.0\\.1:(\\d+)").find(pasted)?.groupValues?.get(1)
            val currentPort = Regex("127\\.0\\.0\\.1:(\\d+)").find(gmail.redirectUri)?.groupValues?.get(1)
            if (pastedPort == null || pastedPort != currentPort) {
                liveText.text = "That link is from an older attempt — copy the newest tab's link, or tap Connect once."
                return@setOnClickListener
            }
            pendingLinkVerifier = null
            signInDone = true
            pasteInput.visibility = android.view.View.GONE
            useLinkButton.visibility = android.view.View.GONE
            finishSignIn(code, verifier)
        }
        micButton.setOnClickListener { onMicTapped() }
        retryButton.setOnClickListener {
            // Re-run the same command through the same chain again.
            lastCommand?.let { sendCommand(it) }
        }
        sendButton.setOnClickListener {
            val command = resolveCommandInput(voiceText, typedInput.text.toString())
            voiceText = null
            sendCommand(command)
        }
    }

    // One entry point for every command: rules first, real planner last.
    // Retry re-runs through here, so every command type retries the same way.
    private fun sendCommand(command: String) {
        lastCommand = command
        val lower = command.lowercase()
        // "remember my boss is Priya" -> store it, show the list.
        if (lower.startsWith("remember ")) {
            val fact = command.substring(9).trim()
            if (fact.isBlank()) {
                liveText.text = "What should I remember?"
            } else {
                rememberFact(roomStore, fact, "you told me")
                liveText.text = "Saved: $fact"
                currentRows = emptyList()
                renderTimeline()
                renderMemories()
            }
            return
        }
        // "forget boss" -> preview first, delete only on confirm.
        if (lower.startsWith("forget ")) {
            val preview = forgetPreview(roomStore, command.substring(7).trim())
            pendingForget = preview
            if (preview.isEmpty()) {
                liveText.text = "Nothing matches that."
                forgetButton.visibility = android.view.View.GONE
            } else {
                liveText.text =
                    "Delete these?\n" + preview.joinToString("\n") { "• ${it.text} (${it.source})" }
                forgetButton.text = "Delete ${preview.size}"
                forgetButton.visibility = android.view.View.VISIBLE
            }
            return
        }
        // "email priya@x.com running late" -> preview first, send only on Confirm.
        // "email bob@x.com | Trip | we leave at 9" -> custom subject (| splits).
        if (lower.startsWith("email ")) {
            handleEmailSend(command.substring(6).trim())
            return
        }
        // Real mailbox read — needs the Connect Gmail button first.
        if (lower == "check mail" || lower == "check email" || lower == "check important emails" ||
            lower == "check emails"
        ) {
            handleEmailRead()
            return
        }
        // Forget the mailbox entirely.
        if (lower == "disconnect gmail") {
            tokens.revoke()
            pendingDraft = null
            sendConfirmButton.visibility = android.view.View.GONE
            liveText.text = "Gmail disconnected."
            renderGmailStatus()
            return
        }
        // "open example.com" -> load it in the in-app page, show what it says.
        if (lower.startsWith("open ")) {
            handleWebOpen(command.substring(5).trim())
            return
        }
        // Normal command: recall what we know, then plan with the REAL chain
        // (your Gemini key). Network runs on a background thread so the
        // screen never freezes; results come back on the UI thread.
        runRealPlan(command)
    }

    // "open example.com" -> the in-app page loads it, we show title + text.
    // (https only — Android blocks plain http by default.)
    private fun handleWebOpen(raw: String) {
        var url = raw.trim()
        if (!url.contains("://")) url = "https://$url"
        val finalUrl = url
        liveText.text = "Opening $finalUrl…"
        currentRows = emptyList()
        renderTimeline()
        Thread {
            try {
                val page = openPage(webBridge, finalUrl)
                val lines = page.text.split("\n").take(5)
                runOnUiThread {
                    liveText.text = "${page.title}\n" + lines.joinToString("\n") + "\n(${page.links.size} links)"
                    currentRows =
                        buildTimeline(
                            Plan(listOf(ToolCall("call_web1", "open", mapOf("url" to finalUrl)))),
                            doneCount = 1,
                            error = null,
                        )
                    renderTimeline()
                }
            } catch (e: WebException) {
                runOnUiThread {
                    liveText.text = "Open failed: ${e.failure.reason} ($finalUrl)"
                    currentRows =
                        buildTimeline(
                            Plan(listOf(ToolCall("call_web1", "open", mapOf("url" to finalUrl)))),
                            doneCount = 0,
                            error = e.failure.reason,
                        )
                    renderTimeline()
                }
            }
        }.start()
    }

    // Swap code (+ our one-time secret) for tokens, then show who connected.
    // Network hiccups (DNS, timeouts) get 3 tries; Google's own rejections
    // (HTTP errors) fail at once — resending can't fix those.
    private fun finishSignIn(code: String, verifier: String) {
        // Google codes are single-use: replaying one always fails with 400.
        if (code == lastExchangedCode) {
            liveText.text = "That code was already used — tap Connect once for a fresh one."
            return
        }
        lastExchangedCode = code
        signInDone = true
        runOnUiThread { liveText.text = "Got approval — finishing sign-in…" }
        Thread {
            var attempt = 0
            while (true) {
                try {
                    gmail.exchangeCode(code, verifier)
                    break
                } catch (e: GmailException) {
                    runOnUiThread { liveText.text = "Sign-in failed: ${e.message}" }
                    return@Thread
                } catch (e: Exception) {
                    attempt++
                    if (attempt >= 3) {
                        runOnUiThread { liveText.text = "Sign-in failed: ${e.message}" }
                        return@Thread
                    }
                    runOnUiThread { liveText.text = "Network hiccup — retrying… ($attempt/3)" }
                    try {
                        Thread.sleep(2000)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }
            // Tokens are in; the name lookup is a bonus — don't fail over it.
            try {
                tokens.gmailAccount = gmail.fetchProfileEmail()
            } catch (_: Exception) {
                tokens.gmailAccount = "connected"
            }
            runOnUiThread {
                liveText.text = "Gmail connected."
                renderGmailStatus()
            }
        }.start()
    }

    private fun renderGmailStatus() {
        connectButton.text = tokens.gmailAccount?.let { "Gmail: $it" } ?: "Connect Gmail"
    }

    // "email ADDR words..." or "email ADDR | Subject | body". Preview, then Confirm sends.
    private fun handleEmailSend(text: String) {
        if (!tokens.isConnected()) {
            liveText.text = "Connect Gmail first (button below)."
            return
        }
        val parts = text.split("|").map { it.trim() }
        val first = parts[0].split(Regex("\\s+"), limit = 2)
        val address = first.getOrNull(0).orEmpty()
        if (!address.contains("@")) {
            liveText.text = "Who to? Say: email ADDRESS your message"
            return
        }
        val draft =
            if (parts.size >= 3) {
                composeEmail(address, parts[1], parts[2])
            } else {
                composeEmail(address, "From AURVIZ", first.getOrNull(1).orEmpty())
            }
        if (draft.body.isBlank()) {
            liveText.text = "Empty message — say what to send."
            return
        }
        pendingDraft = draft to buildRawMessage(draft)
        liveText.text = "Send this?\nTo: ${draft.to.joinToString()}\nSubject: ${draft.subject}\n${draft.body}"
        sendConfirmButton.text = "Confirm send"
        sendConfirmButton.visibility = android.view.View.VISIBLE
    }

    private fun sendPendingDraft() {
        val (_, raw) = pendingDraft ?: return
        pendingDraft = null
        sendConfirmButton.visibility = android.view.View.GONE
        liveText.text = "Sending…"
        Thread {
            try {
                val sent = sendWithConfirm(gmail, raw, confirmed = true)
                runOnUiThread {
                    liveText.text = if (sent) "Sent ✓" else "Not sent."
                    currentRows =
                        buildTimeline(
                            Plan(listOf(ToolCall("gmail", "sendEmail", mapOf("raw" to raw.take(12))))),
                            doneCount = if (sent) 1 else 0,
                            error = if (sent) null else "not confirmed",
                        )
                    renderTimeline()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    liveText.text = "Send failed: ${e.message}"
                    currentRows = emptyList()
                    renderTimeline()
                }
            }
        }.start()
    }

    // Real read from the mailbox: important first, shown + listed.
    private fun handleEmailRead() {
        if (!tokens.isConnected()) {
            liveText.text = "Connect Gmail first (button below)."
            return
        }
        liveText.text = "Checking mailbox…"
        Thread {
            try {
                val found = readImportant(gmail, max = 5)
                runOnUiThread {
                    liveText.text =
                        if (found.isEmpty()) {
                            "Inbox zero — nothing unread."
                        } else {
                            "Important:\n" + found.joinToString("\n") { "• ${it.from}: ${it.subject}" }
                        }
                    currentRows =
                        buildTimeline(
                            Plan(listOf(ToolCall("gmail", "getImportantEmails", mapOf("max_results" to 5)))),
                            doneCount = 1,
                            error = null,
                        )
                    renderTimeline()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    liveText.text = "Read failed: ${e.message}"
                    currentRows = emptyList()
                    renderTimeline()
                }
            }
        }.start()
    }

    private fun runRealPlan(cmd: String) {
        val known = recallFor(roomStore, cmd).take(3)
        val knownLine = if (known.isEmpty()) "" else "\nKnown: " + known.joinToString("; ") { it.text }
        liveText.text = "Command: $cmd$knownLine\nAgent: thinking…"
        currentRows = emptyList()
        renderTimeline()
        retryButton.visibility = android.view.View.GONE
        val effective =
            if (known.isEmpty()) cmd else "Known: " + known.joinToString("; ") { it.text } + ". $cmd"
        Thread {
            var used = ""
            // Same planCommand as the unit tests; only the transport is real:
            // try providers in order, skip ones with no key (T4 logic).
            val provider =
                object : LlmProvider {
                    override fun plan(userText: String): LlmResult {
                        val fb = planWithFallback(userText, defaultCandidates(BuildConfig.LLM_DEFAULT_PROVIDER))
                        used = fb.providerName
                        return fb.result
                    }
                }
            try {
                val outcome = planCommand(effective, provider)
                runOnUiThread {
                    when (outcome) {
                        is PlannerOutcome.NeedsClarification -> {
                            liveText.text = "Command: $cmd$knownLine\nAgent: ${outcome.question}"
                            currentRows = buildTimeline(Plan(emptyList()), doneCount = 0, error = null)
                        }
                        is PlannerOutcome.Ready -> {
                            liveText.text = "Command: $cmd$knownLine\nRan on: $used"
                            currentRows = buildTimeline(outcome.plan, doneCount = 0, error = null)
                        }
                    }
                    renderTimeline()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    liveText.text = "Command: $cmd$knownLine\nFailed: ${e.message}"
                    currentRows = emptyList()
                    renderTimeline()
                    retryButton.visibility = android.view.View.VISIBLE
                }
            }
        }.start()
    }

    // The remembered list with sources, so auto-saves can be audited.
    private fun renderMemories() {
        val all = roomStore.list()
        memoryView.text =
            if (all.isEmpty()) {
                "Remembered: nothing yet (try: remember my boss is Priya)"
            } else {
                "Remembered:\n" + all.joinToString("\n") { "• ${it.text} (${it.source})" }
            }
    }

    // Draw the rows. Finished steps stay visible; failed one shows Retry.
    private fun renderTimeline() {
        timelineView.text = currentRows.joinToString("\n") { row ->
            val mark =
                when (row.state) {
                    StepState.DONE -> "✓"
                    StepState.RUNNING -> "▶"
                    StepState.FAILED -> "✗"
                    StepState.READY -> "○"
                }
            val err = if (row.error != null) " (${row.error})" else ""
            "$mark ${row.title}$err"
        }
        retryButton.visibility =
            if (currentRows.any { it.state == StepState.FAILED }) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    // What we ask the mic for: En+Hi hints, free-form speech,
    // live partial results. First on-device; if its language pack is
    // missing we retry once online (see onError).
    private fun voiceIntent(preferOffline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, defaultVoiceHints().first())
            if (Build.VERSION.SDK_INT >= 33) {
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES,
                    ArrayList(defaultVoiceHints()),
                )
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun refreshMicButton() {
        val availability =
            voiceAvailability(SpeechRecognizer.isRecognitionAvailable(this), hasMicPermission())
        micButton.visibility =
            if (availability.state == VoiceState.FALLBACK_TEXT) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }
    }

    private fun onMicTapped() {
        val availability =
            voiceAvailability(SpeechRecognizer.isRecognitionAvailable(this), hasMicPermission())
        when (availability.state) {
            VoiceState.READY -> {
                retriedOnline = false
                voiceText = null
                startListening()
            }
            VoiceState.NEED_PERMISSION -> {
                liveText.text = availability.rationale
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            }
            VoiceState.FALLBACK_TEXT -> {
                liveText.text = "No voice recognition on this device. Please type."
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 1001 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else if (requestCode == 1001) {
            liveText.text = "Mic denied. No problem — type your command instead."
        }
        refreshMicButton()
    }

    // Must run on the main thread; shows live transcription as you speak.
    private fun startListening(online: Boolean = false) {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        liveText.text = "Listening… speak now."
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        voiceText = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        liveText.text = voiceText ?: "Listening…"
                    }

                    override fun onResults(results: Bundle?) {
                        voiceText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        liveText.text = voiceText ?: "Didn't catch that. You can type instead."
                    }

                    override fun onError(error: Int) {
                        // Missing offline pack -> retry once online. Anything
                        // else: explain + keep typing available, never crash.
                        if (shouldRetryOnline(error) && !retriedOnline) {
                            retriedOnline = true
                            liveText.text = "On-device voice missing, trying online…"
                            startListening(online = true)
                            return
                        }
                        val mapped = mapRecognizerError(error)
                        voiceText = null
                        liveText.text = mapped.message
                    }

                    override fun onBeginningOfSpeech() = Unit

                    override fun onEndOfSpeech() = Unit

                    override fun onBufferReceived(buffer: ByteArray?) = Unit

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onRmsChanged(rmsdB: Float) = Unit
                },
            )
        }
        try {
            recognizer?.startListening(voiceIntent(preferOffline = !online))
        } catch (e: Exception) {
            // Belt and braces: recognizer busy or unavailable -> text fallback.
            voiceText = null
            liveText.text = "Mic is busy. You can type instead."
        }
    }
}
