package com.aurviz.agent

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// T2: voice+text command input. Pure decision logic at testable seams;
// the SpeechRecognizer / permission wiring stays thin and is device-tested.
class VoiceInputTest {

    @Test
    fun voiceHintsCoverEnglishAndHindi() {
        assertEquals(listOf("en-IN", "hi-IN"), defaultVoiceHints())
    }

    @Test
    fun voiceRequestPrefersOnDeviceRecognition() {
        assertEquals(true, defaultVoiceRequest().preferOnDevice)
    }

    @Test
    fun noMatchFallsBackToText() {
        val error = mapRecognizerError(SpeechRecognizer.ERROR_NO_MATCH)
        assertTrue(error.fallbackToText)
        assertEquals("Didn't catch that. You can type instead.", error.message)
    }

    @Test
    fun busyRecognizerFallsBackWithoutCrash() {
        val error = mapRecognizerError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        assertTrue(error.fallbackToText)
        assertEquals("Mic is busy. You can type instead.", error.message)
    }

    @Test
    fun spokenTextWinsOverTypedText() {
        assertEquals("kal meeting hai", resolveCommandInput("kal meeting hai", "check email"))
    }

    @Test
    fun blankVoiceFallsBackToTypedText() {
        assertEquals("check email", resolveCommandInput("   ", "check email"))
    }

    @Test
    fun deniedPermissionAsksWithRationale() {
        val availability = voiceAvailability(recognizerPresent = true, hasPermission = false)
        assertEquals(VoiceState.NEED_PERMISSION, availability.state)
        assertTrue(availability.rationale!!.contains("type"))
    }

    @Test
    fun missingRecognizerFallsBackToText() {
        val availability = voiceAvailability(recognizerPresent = false, hasPermission = false)
        assertEquals(VoiceState.FALLBACK_TEXT, availability.state)
    }

    @Test
    fun grantedPermissionIsReady() {
        val availability = voiceAvailability(recognizerPresent = true, hasPermission = true)
        assertEquals(VoiceState.READY, availability.state)
    }

    @Test
    fun missingLanguagePackRetriesOnline() {
        assertTrue(shouldRetryOnline(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertTrue(shouldRetryOnline(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED))
    }

    @Test
    fun ordinaryErrorsDoNotRetryOnline() {
        assertEquals(false, shouldRetryOnline(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(false, shouldRetryOnline(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
    }
}
