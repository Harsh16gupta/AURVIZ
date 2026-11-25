package com.aira.agent

import com.aira.agent.ui.VOICE_LEVEL_MAX_DB
import com.aira.agent.ui.VOICE_LEVEL_MIN_DB
import com.aira.agent.ui.VoiceUiState
import com.aira.agent.ui.canConfirmVoice
import com.aira.agent.ui.voiceLevelClamped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket #91: voice sheet state seam. RMS samples clamp into the meter
// window without touching the interim/final transcript flag, and Confirm
// stays disabled while the transcript is blank — all state-derived.
class VoiceSheetStateTest {

    @Test
    fun voiceLevelClampsIntoMeterWindowAndKeepsInterimFlag() {
        assertEquals(VOICE_LEVEL_MIN_DB, voiceLevelClamped(-30f), 0f)
        assertEquals(VOICE_LEVEL_MAX_DB, voiceLevelClamped(20f), 0f)
        assertEquals(0f, voiceLevelClamped(0f), 0f)

        // Interim partial text keeps its muted flag through a level update.
        val interim = VoiceUiState(transcript = "send", isPartial = true)
        val leveled = interim.copy(levelDb = voiceLevelClamped(4f))
        assertEquals(4f, leveled.levelDb, 0f)
        assertTrue(leveled.isPartial)

        // A final transcript keeps its crisp flag through a level update.
        val final = interim.copy(isPartial = false, levelDb = voiceLevelClamped(-2f))
        assertFalse(final.isPartial)
        assertEquals(VOICE_LEVEL_MIN_DB, final.levelDb, 0f)
    }

    @Test
    fun blankTranscriptDisablesConfirm() {
        assertFalse(VoiceUiState(transcript = "").canConfirmVoice)
        assertFalse(VoiceUiState(transcript = "   ").canConfirmVoice)
        assertTrue(VoiceUiState(transcript = "send the email").canConfirmVoice)
    }
}
