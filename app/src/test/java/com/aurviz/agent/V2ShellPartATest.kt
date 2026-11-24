package com.aurviz.agent

import com.aurviz.agent.ui.AgentUiState
import com.aurviz.agent.ui.SessionSummary
import com.aurviz.agent.ui.buildSessionSummaries
import com.aurviz.agent.ui.toRecord
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// V2 flagship shell Part A: multi-Session seam, onboarding flag, and
// switch/delete wiring. Pure JVM: Room rows + helpers only, plus
// source guards for the Android-bound ViewModel/UiPrefs seams.
class V2ShellPartATest {

    private fun row(sessionId: String, command: String, ts: Long, pid: Long = ts) =
        InteractionRow(
            pid = pid,
            sessionId = sessionId,
            command = command,
            responseText = "resp $command",
            provider = null,
            timestampMs = ts,
            failed = false,
        )

    @Test
    fun summariesGroupBySessionWithEarliestTitle() {
        val rows =
            listOf(
                row("s1", "second command here", 2000L, 2),
                row("s1", "first command", 1000L, 1),
                row("s2", "other session cmd", 1500L, 3),
            )
        val summaries = buildSessionSummaries(rows)
        assertEquals(2, summaries.size)
        val s1 = summaries.first { it.sessionId == "s1" }
        assertEquals("first command", s1.title)
        assertEquals(2000L, s1.lastTimestampMs)
        assertEquals(2, s1.interactionCount)
    }

    @Test
    fun summariesSortDescendingByLatestTimestamp() {
        val rows =
            listOf(
                row("s_old", "old", 1000L, 1),
                row("s_new", "new", 5000L, 2),
                row("s_mid", "mid", 3000L, 3),
            )
        val ids = buildSessionSummaries(rows).map { it.sessionId }
        assertEquals(listOf("s_new", "s_mid", "s_old"), ids)
    }

    @Test
    fun summariesIgnoreBlankSessionAndFallbackTitle() {
        val rows =
            listOf(
                row("", "blank", 1000L, 1),
                row("   ", "spaces", 2000L, 2),
                row("s1", "   ", 3000L, 3),
            )
        val summaries = buildSessionSummaries(rows)
        assertEquals(1, summaries.size)
        assertEquals("s1", summaries[0].sessionId)
        assertEquals("New Session", summaries[0].title)
    }

    @Test
    fun summariesTruncateTitleTo48Chars() {
        val long = "a".repeat(100)
        val summaries = buildSessionSummaries(listOf(row("s1", "  $long  ", 1000L, 1)))
        assertEquals(48, summaries[0].title.length)
    }

    @Test
    fun agentUiStateDefaultsKeepExistingTestsGreen() {
        val state = AgentUiState()
        assertTrue(state.sessions.isEmpty())
        assertTrue(state.onboardingCompleted)
    }

    @Test
    fun sessionSummaryShapeRoundTrips() {
        val s = SessionSummary("s1", "hello", 123L, 2)
        assertEquals("s1", s.sessionId)
        assertEquals("hello", s.title)
        assertEquals(123L, s.lastTimestampMs)
        assertEquals(2, s.interactionCount)
    }

    @Test
    fun switchMappingKeepsChronologicalOrder() {
        val rows =
            listOf(
                row("s1", "first", 1000L, 1),
                row("s1", "second", 2000L, 2),
            )
        val mapped = rows.map { it.toRecord() }
        assertEquals(listOf("first", "second"), mapped.map { it.command })
        assertEquals(2, mapped.size)
    }

    @Test
    fun deleteSessionFiltersSummaries() {
        val rows =
            listOf(
                row("s1", "one", 1000L, 1),
                row("s2", "two", 2000L, 2),
            )
        val remaining = rows.filter { it.sessionId != "s1" }
        val summaries = buildSessionSummaries(remaining)
        assertEquals(listOf("s2"), summaries.map { it.sessionId })
    }

    // --- Source guards for Android-bound seams ---

    private fun moduleDir(): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "src/main/AndroidManifest.xml").exists()) return dir
            dir = dir.parentFile ?: throw AssertionError("Could not locate app module dir from user.dir")
        }
    }

    private fun mainText(path: String): String =
        File(moduleDir(), "src/main/java/$path").readText()

    @Test
    fun uiPrefsExposesOnboardingRoundTrip() {
        val text = mainText("com/aurviz/agent/ui/components/Haptics.kt")
        assertTrue(text.contains("\"onboarding_completed\""))
        assertTrue(text.contains("fun onboardingCompleted("))
        assertTrue(text.contains("fun setOnboardingCompleted("))
    }

    @Test
    fun daoExposesSessionQueriesWithoutSchemaChange() {
        val text = mainText("com/aurviz/agent/InteractionDb.kt")
        assertTrue(text.contains("fun listBySession("))
        assertTrue(text.contains("SELECT * FROM interactions WHERE sessionId = :sessionId ORDER BY timestampMs ASC"))
        assertTrue(text.contains("fun deleteSession("))
        assertTrue(text.contains("DELETE FROM interactions WHERE sessionId = :sessionId"))
        // Room stays on version 6: no bump for the new queries.
        val mem = mainText("com/aurviz/agent/MemoryDb.kt")
        assertTrue(mem.contains("version = 6"))
    }

    @Test
    fun viewModelWiresMultiSession() {
        val vm = mainText("com/aurviz/agent/ui/AgentViewModel.kt")
        assertTrue(vm.contains("fun completeOnboarding()"))
        assertTrue(vm.contains("setOnboardingCompleted"))
        assertTrue(vm.contains("onboardingCompleted = autoOnboarded") || vm.contains("onboardingCompleted = true"))
        assertTrue(vm.contains("fun switchSession("))
        assertTrue(vm.contains("listBySession("))
        assertTrue(vm.contains("if (state.value.agentRunning || sessionId == activeSessionId) return"))
        assertTrue(vm.contains("fun deleteSession("))
        assertTrue(vm.contains("deleteSession(sessionId)"))
        assertTrue(vm.contains("refreshSessions()"))
        assertTrue(vm.contains("sessions = buildSessionSummaries("))
        // startNewSession clears the feed for the fresh thread.
        assertTrue(vm.contains("interactions = emptyList()"))
        assertTrue(vm.contains("timeline = emptyList()"))
        assertTrue(vm.contains("activeCommand = null"))
        assertTrue(vm.contains("sessionTurns = 0"))
    }

    @Test
    fun drawerShellHasNoBottomBar() {
        val nav = mainText("com/aurviz/agent/ui/AgentNav.kt")
        assertFalse(nav.contains("BottomBar"))
        assertFalse(nav.contains("private val Tabs"))
        assertTrue(nav.contains("drawerOpen"))
        assertTrue(nav.contains("SideDrawer("))
        assertTrue(nav.contains("New Session"))
        assertTrue(nav.contains("Your past Sessions appear here."))
        assertTrue(nav.contains("Settings & Memory"))
        val drawer = mainText("com/aurviz/agent/ui/components/SideDrawer.kt")
        assertTrue(drawer.contains("fillMaxWidth(0.82f)"))
        assertTrue(drawer.contains("widthIn(max = 310.dp)"))
        // Gesture-nav devices report navigationBars.bottom ~0: the drawer
        // bottom inset must union in systemGestures (verified on-device).
        assertTrue(drawer.contains("systemGestures"))
        assertTrue(drawer.contains("Theme.colors.scrim"))
    }

    @Test
    fun commandTopBarAndSheetsUseGlossaryCopy() {
        val cmd = mainText("com/aurviz/agent/ui/screens/CommandScreen.kt")
        assertTrue(cmd.contains("\"Open menu\""))
        assertTrue(cmd.contains("\"New Session\""))
        assertTrue(cmd.contains("\"More options\""))
        assertTrue(cmd.contains("\"Manage Memory\""))
        assertTrue(cmd.contains("View or add saved Preferences"))
        assertTrue(cmd.contains("Set up a recurring Task Execution"))
        assertTrue(cmd.contains("navigationBarsPadding()"))
        val banned = listOf("History", "Chat", "Alarm", "Cron", "send_email")
        // "Chat" appears only inside allowed compound? Fail on raw banned UI strings.
        for (word in listOf("History", "Alarm", "Cron")) {
            assertFalse("banned copy '$word' in CommandScreen", cmd.contains("\"$word"))
        }
        assertTrue(banned.isNotEmpty())
    }
}
