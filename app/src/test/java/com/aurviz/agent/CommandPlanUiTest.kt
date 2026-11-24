package com.aurviz.agent

import com.aurviz.agent.ui.InteractionRecord
import com.aurviz.agent.ui.rememberAddressFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket #87: pure Command-screen presentation helpers. External behavior
// only — rendered labels, single-error selection, expand/collapse inputs.
class CommandPlanUiTest {

    private fun plan() =
        Plan(
            steps = listOf(
                ToolCall("call_1", "search_emails", mapOf("query" to "is:unread", "max" to 5)),
                ToolCall("call_2", "send_email", mapOf("to" to "a@b.com", "body" to "hi")),
            ),
        )

    // --- providerDisplayName: human names, never raw ids/null. ---

    @Test
    fun providersMapToHumanNames() {
        assertEquals("Gemini", providerDisplayName("gemini"))
        assertEquals("DeepSeek", providerDisplayName("deepseek"))
        assertEquals("Meta", providerDisplayName("meta"))
    }

    @Test
    fun nullProviderIsAutoNeverNull() {
        assertEquals("Auto", providerDisplayName(null))
    }

    @Test
    fun unknownProviderFallsBackToCleanLabel() {
        val label = providerDisplayName("some_new_provider")
        assertFalse("raw id leaked: $label", label.contains("_"))
        assertEquals(label, label.trim())
    }

    // --- sessionTurnsLine: glossary copy, no thread/chat. ---

    @Test
    fun sessionLineUsesGlossaryCopy() {
        assertEquals("Session · 1 turn", sessionTurnsLine(1))
        assertEquals("Session · 3 turns", sessionTurnsLine(3))
        listOf(sessionTurnsLine(1), sessionTurnsLine(5)).forEach { line ->
            assertFalse("banned copy: $line", line.contains("thread", ignoreCase = true))
            assertFalse("banned copy: $line", line.contains("chat", ignoreCase = true))
        }
    }

    // --- buildTimeline populates toolName + argsSummary (titles pinned). ---

    @Test
    fun toolRowsCarryNameAndArgsSummary() {
        val rows = buildTimeline(plan(), doneCount = 0, error = null)
        assertEquals("Tool 1 of 2: search_emails", rows[2].title)
        assertEquals("search_emails", rows[2].toolName)
        assertEquals("query: is:unread, max: 5", rows[2].argsSummary)
        assertNull(rows[0].toolName)
        assertNull(rows[0].argsSummary)
    }

    @Test
    fun emptyArgsMeanNullSummary() {
        val rows = buildTimeline(Plan(listOf(ToolCall("c", "scroll", emptyMap()))), doneCount = 0, error = null)
        assertNull(rows[2].argsSummary)
        assertEquals("scroll", rows[2].toolName)
    }

    // --- stepDisplayTitle: clean status rows, no raw ids. ---

    @Test
    fun toolRowRendersHumanLabel() {
        val rows = buildTimeline(plan(), doneCount = 0, error = null)
        assertEquals("Tool 1 of 2: Search emails", stepDisplayTitle(rows[2]))
        assertEquals("Tool 2 of 2: Send email", stepDisplayTitle(rows[3]))
    }

    @Test
    fun nonToolRowsPassThrough() {
        val rows = buildTimeline(plan(), doneCount = 0, error = null)
        assertEquals("Heard command", stepDisplayTitle(rows[0]))
        assertEquals("Working…", stepDisplayTitle(rows.last()))
    }

    // --- planFailure + visiblePlanSteps: one error row max. ---

    @Test
    fun failedRunYieldsSingleToolFailure() {
        val rows = buildTimeline(plan(), doneCount = 1, error = "No internet")
        val failure = planFailure(rows)
        assertEquals(StepState.FAILED, failure?.state)
        assertTrue(failure?.title?.startsWith("Tool ") == true)
        assertEquals("No internet", failure?.error)
    }

    @Test
    fun noFailureYieldsNull() {
        assertNull(planFailure(buildTimeline(plan(), doneCount = 2, error = null)))
    }

    @Test
    fun errorTailRowIsFilteredFromStepList() {
        val rows = buildTimeline(plan(), doneCount = 1, error = "No internet")
        assertTrue(rows.any { it.title.startsWith("Error:") })
        val visible = visiblePlanSteps(rows)
        assertFalse(visible.any { it.title.startsWith("Error:") })
        assertEquals(rows.size - 1, visible.size)
    }

    // --- confirmationTitleFor: human labels (VM delegates here). ---

    @Test
    fun confirmationTitlesNeverLeakRawIds() {
        assertEquals("Send email to a@b.com", confirmationTitleFor(ToolCall("c", "send_email", mapOf("to" to "a@b.com"))))
        assertEquals("Run Search emails", confirmationTitleFor(ToolCall("c", "search_emails", mapOf("query" to "x"))))
        assertEquals("Run Cancel schedule", confirmationTitleFor(ToolCall("c", "cancel_schedule", emptyMap())))
    }

    // --- rememberAddressFor: settled-receipt grant derivation (#70 Gap B). ---

    private fun sendStep(argsSummary: String?) =
        TimelineStep(
            title = "Tool 1 of 1: send_email",
            state = StepState.DONE,
            toolName = "send_email",
            argsSummary = argsSummary,
        )

    @Test
    fun legacyTitlePrefixStillGrants() {
        val record = InteractionRecord(id = "r1", command = "email", confirmationTitle = "Send email to friend@work.com")
        assertEquals("friend@work.com", rememberAddressFor(record))
    }

    @Test
    fun receiptTitleFallsBackToStepArgs() {
        val record =
            InteractionRecord(
                id = "r2",
                command = "email",
                confirmationTitle = "Confirmed — ran send_email",
                steps = listOf(sendStep("to: friend@work.com, subject: Hi, body: hello")),
            )
        assertEquals("friend@work.com", rememberAddressFor(record))
    }

    @Test
    fun stepsReflectEditedAddress() {
        val record =
            InteractionRecord(
                id = "r3",
                command = "email",
                confirmationTitle = "Confirmed with edits — ran send_email",
                steps = listOf(sendStep("to: edited@work.com, subject: Hi, body: hello")),
            )
        assertEquals("edited@work.com", rememberAddressFor(record))
    }

    @Test
    fun multipleAddressesGrantFirst() {
        val record =
            InteractionRecord(
                id = "r4",
                command = "email",
                steps = listOf(sendStep("to: a@b.c, d@e.f, subject: s, body: b")),
            )
        assertEquals("a@b.c", rememberAddressFor(record))
    }

    @Test
    fun noSendStepYieldsNull() {
        val record =
            InteractionRecord(
                id = "r5",
                command = "search",
                confirmationTitle = "Confirmed — ran search_emails",
                steps = listOf(TimelineStep(title = "Tool 1 of 1: search_emails", state = StepState.DONE, toolName = "search_emails")),
            )
        assertNull(rememberAddressFor(record))
    }

    @Test
    fun nonAddressToYieldsNull() {
        val record =
            InteractionRecord(
                id = "r6",
                command = "email",
                steps = listOf(sendStep("to: not-an-address, subject: s, body: b")),
            )
        assertNull(rememberAddressFor(record))
    }
}
