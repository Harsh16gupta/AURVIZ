package com.aurviz.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.aurviz.agent.ui.AgentNav
import com.aurviz.agent.ui.AgentViewModel
import com.aurviz.agent.ui.WebViewHolder
import com.aurviz.agent.ui.theme.VelaTheme

// Compose harness. All decisions (which language, what an error means,
// voice-vs-typed) live in VoiceInput.kt and are unit-tested; this file only
// touches Android classes (SpeechRecognizer, permissions, WebView pre-warm)
// and is verified on a real device from Android Studio.
class MainActivity : ComponentActivity() {

    private lateinit var vm: AgentViewModel
    private var recognizer: SpeechRecognizer? = null
    private var retriedOnline = false

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startListening()
            } else {
                vm.onVoiceError("Mic denied. No problem — type your command instead.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[AgentViewModel::class.java]
        // Dark scrim + light icons: Vela's background is #0E0F12.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // Pre-warm the retained WebView so `open` commands from Command work
        // even before the Browser tab is first visited.
        try {
            val wv = WebViewHolder.obtain(this)
            vm.attachWebBridge(WebViewHolder.bridge(wv))
        } catch (_: Exception) {
            // BrowserScreen re-attaches when it composes; open retries then.
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        }
        setContent {
            VelaTheme {
                AgentNav(
                    vm = vm,
                    onMicTap = ::onMicTapped,
                    onOpenExactAlarmSettings = ::openExactAlarmSettings,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from Settings: re-check the exact-alarm toggle without a restart.
        if (::vm.isInitialized) vm.refreshExactAlarmFlag()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    private fun openExactAlarmSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
    }

    // What we ask the mic for: En+Hi hints, free-form speech, live partial
    // results. First on-device; if its language pack is missing we retry
    // once online (see onError).
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
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun onMicTapped() {
        val availability =
            voiceAvailability(SpeechRecognizer.isRecognitionAvailable(this), hasMicPermission())
        when (availability.state) {
            VoiceState.READY -> {
                retriedOnline = false
                vm.setVoiceListening(true)
                startListening()
            }
            VoiceState.NEED_PERMISSION -> {
                vm.onVoiceError(
                    availability.rationale ?: "Mic access lets you speak commands. You can always type instead.",
                )
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            VoiceState.FALLBACK_TEXT -> {
                vm.onVoiceError("No voice recognition on this device. Please type.")
            }
        }
    }

    // Must run on the main thread; shows live transcription as you speak.
    // onRmsChanged intentionally does nothing: the orb is driven by
    // VoiceUiState, never blocked on audio levels.
    private fun startListening(online: Boolean = false) {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        vm.setVoiceListening(true)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val text = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        vm.onVoicePartial(text ?: "Listening…")
                    }

                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (text.isNullOrBlank()) {
                            vm.onVoiceError("Didn't catch that. You can type instead.")
                        } else {
                            vm.onVoiceFinal(text)
                        }
                    }

                    override fun onError(error: Int) {
                        // Missing offline pack -> retry once online. Anything
                        // else: explain + keep typing available, never crash.
                        if (shouldRetryOnline(error) && !retriedOnline) {
                            retriedOnline = true
                            vm.onVoicePartial("On-device voice missing, trying online…")
                            startListening(online = true)
                            return
                        }
                        val mapped = mapRecognizerError(error)
                        vm.onVoiceError(mapped.message)
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
        } catch (_: Exception) {
            // Belt and braces: recognizer busy or unavailable -> text fallback.
            vm.onVoiceError("Mic is busy. You can type instead.")
        }
    }
}
