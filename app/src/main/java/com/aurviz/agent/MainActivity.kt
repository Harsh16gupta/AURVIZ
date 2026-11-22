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
            }
        setContentView(layout)

        refreshMicButton()
        renderMemories()
        forgetButton.setOnClickListener {
            // Confirmed: delete exactly the previewed ids, nothing else.
            pendingForget.forEach { roomStore.delete(it.id) }
            liveText.text = "Deleted ${pendingForget.size}."
            pendingForget = emptyList()
            forgetButton.visibility = android.view.View.GONE
            renderMemories()
        }
        micButton.setOnClickListener { onMicTapped() }
        retryButton.setOnClickListener {
            // Retry keeps finished steps, only the failed one runs again.
            currentRows = retryTimeline(currentRows)
            renderTimeline()
        }
        sendButton.setOnClickListener {
            val command = resolveCommandInput(voiceText, typedInput.text.toString())
            voiceText = null
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
                return@setOnClickListener
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
                return@setOnClickListener
            }
            // Normal command: recall what we know, then plan as before.
            // (Fake ignores the context line; the real provider will read it.)
            val known = recallFor(roomStore, command).take(3)
            val knownLine = if (known.isEmpty()) "" else "\nKnown: " + known.joinToString("; ") { it.text }
            liveText.text = "Command: $command$knownLine"
            // T5 demo flow (Fake = no keys needed on device):
            // first Send shows step 2 failed so Retry can be checked.
            // Real runner swaps FakeLlmProvider for planWithFallback later.
            val effective =
                if (known.isEmpty()) command else "Known: " + known.joinToString("; ") { it.text } + ". $command"
            when (val outcome = planCommand(effective, FakeLlmProvider())) {
                is PlannerOutcome.NeedsClarification -> {
                    liveText.text = "Command: $command$knownLine\nAgent: ${outcome.question}"
                    currentRows = buildTimeline(Plan(emptyList()), doneCount = 0, error = null)
                }
                is PlannerOutcome.Ready -> {
                    currentRows = buildTimeline(outcome.plan, doneCount = 0, error = "Demo: no internet")
                }
            }
            renderTimeline()
        }
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
