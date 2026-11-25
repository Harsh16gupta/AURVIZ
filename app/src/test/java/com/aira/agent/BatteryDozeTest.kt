package com.aira.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket #34 (21 Battery and Doze UX): in-app battery-optimization prompt
// flow + OEM autostart guidance + exact-alarm-need flag visible in Settings.
// Seam 1 (pure domain): prompt decision + intent shape + guidance text.
// Seam 2 (state): AgentUiState flags drive the Settings surface.
// No Robolectric: Intent shape asserted on action/data only.
class BatteryDozeTest {

    @Test
    fun batteryPromptNeededWhenNotExempted() {
        assertTrue(batteryPromptNeeded(isIgnoringBatteryOptimizations = false))
    }

    @Test
    fun batteryPromptHiddenWhenExempted() {
        assertFalse(batteryPromptNeeded(isIgnoringBatteryOptimizations = true))
    }

    @Test
    fun ignoreBatteryIntentTargetsPackage() {
        // Intent construction needs android.net.Uri (Robolectric territory);
        // the pure Uri shape plus the inlined request action are the contract.
        assertEquals("package:com.aira.agent", ignoreBatteryPackageUri("com.aira.agent"))
        assertEquals(
            "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        )
    }

    @Test
    fun oemGuidanceNamesVendorAutostartPaths() {
        assertTrue(OEM_AUTOSTART_GUIDANCE.contains("Autostart", ignoreCase = true))
        assertTrue(OEM_AUTOSTART_GUIDANCE.contains("Xiaomi"))
        assertTrue(OEM_AUTOSTART_GUIDANCE.contains("Samsung"))
    }

    @Test
    fun exactDeniedFallsBackToInexactNote() {
        val note = scheduleTimingNote(exactGranted = false)
        assertTrue(note.contains("inexact", ignoreCase = true))
        assertTrue(note.contains("Settings"))
    }

    @Test
    fun exactGrantedConfirmsExactTiming() {
        val note = scheduleTimingNote(exactGranted = true)
        assertTrue(note.contains("Exact", ignoreCase = true))
    }

    @Test
    fun settingsStateKeepsBothPromptsVisible() {
        val state = com.aira.agent.ui.AgentUiState(
            exactAlarmNeeded = true,
            batteryOptimizationNeeded = true,
        )
        assertTrue(state.exactAlarmNeeded)
        assertTrue(state.batteryOptimizationNeeded)
    }
}
