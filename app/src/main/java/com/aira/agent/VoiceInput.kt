package com.aira.agent

// T2: voice+text command input. Pure decision logic, JVM-testable.
// Thin SpeechRecognizer / permission / UI wiring lives elsewhere
// and is verified on-device.

// Languages the mic listens for: English (India) + Hindi (India).
fun defaultVoiceHints(): List<String> = listOf("en-IN", "hi-IN")

// What we ask the recognizer for: En+Hi hints, on-device when available.
data class VoiceRequest(val languageHints: List<String>, val preferOnDevice: Boolean)

fun defaultVoiceRequest(): VoiceRequest = VoiceRequest(defaultVoiceHints(), preferOnDevice = true)

// What to show when the mic fails: a friendly message + whether to
// fall back to the text field (almost always yes — typing always works).
data class VoiceError(val message: String, val fallbackToText: Boolean)

fun mapRecognizerError(code: Int): VoiceError =
    when (code) {
        android.speech.SpeechRecognizer.ERROR_NO_MATCH ->
            VoiceError("Didn't catch that. You can type instead.", fallbackToText = true)
        android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            VoiceError("Mic is busy. You can type instead.", fallbackToText = true)
        else -> VoiceError("Voice input failed. You can type instead.", fallbackToText = true)
    }

// On-device was requested but its language pack is missing (or the
// language isn't supported offline): retry once online instead of
// giving up. All other errors fall back to typing.
fun shouldRetryOnline(code: Int): Boolean =
    code == android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
        code == android.speech.SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED

// One command from two sources: spoken text wins when present,
// otherwise the typed text. Typing always works, so never empty
// just because voice failed.
fun resolveCommandInput(voiceText: String?, typedText: String): String =
    if (!voiceText.isNullOrBlank()) voiceText else typedText

// Live RMS meter mapping (pure, JVM-testable). SpeechRecognizer
// onRmsChanged dB maps silence (-2 dB) .. loud speech (10 dB) into 0..1
// for the Voice Command waveform ring buffer. MainActivity throttles
// samples (~100ms) into AgentViewModel.onVoiceLevel which clamps via
// voiceLevelClamped(); this helper is the spec -2..10 normalizer.
fun voiceRmsFraction(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)

// Can the mic be used right now? Three answers: READY shows the mic
// button, NEED_PERMISSION explains why and asks, FALLBACK_TEXT hides
// the mic (typing only) when the device has no recognizer.
enum class VoiceState { READY, NEED_PERMISSION, FALLBACK_TEXT }

data class VoiceAvailability(val state: VoiceState, val rationale: String?)

fun voiceAvailability(recognizerPresent: Boolean, hasPermission: Boolean): VoiceAvailability =
    when {
        !recognizerPresent -> VoiceAvailability(VoiceState.FALLBACK_TEXT, rationale = null)
        !hasPermission ->
            VoiceAvailability(
                VoiceState.NEED_PERMISSION,
                "Mic access lets you speak commands. You can always type instead.",
            )
        else -> VoiceAvailability(VoiceState.READY, rationale = null)
    }
