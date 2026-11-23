package com.aurviz.agent

import org.junit.Assert.assertEquals
import org.junit.Test

// T5 slice 1 (red): timeline = Plan in, rows out.
// No Android, no internet — just the list the chat screen will draw.
class ChatTimelineTest {

    private fun twoStepPlan() =
        Plan(
            steps = listOf(
                ToolCall("call_1", "search_emails", mapOf("query" to "is:important is:unread", "max" to 5)),
                ToolCall("call_2", "sendEmail", mapOf("to" to "a@b.com", "body" to "hi")),
            ),
        )

    @Test
    fun twoStepPlanShowsNumberedSteps() {
        val rows = buildTimeline(twoStepPlan(), doneCount = 0, error = null)
        assertEquals("Heard command", rows[0].title)
        assertEquals("Planned 2 steps", rows[1].title)
        assertEquals("Tool 1 of 2: search_emails", rows[2].title)
        assertEquals("Tool 2 of 2: sendEmail", rows[3].title)
        assertEquals(StepState.RUNNING, rows[2].state)
        assertEquals(StepState.READY, rows[3].state)
    }

    @Test
    fun doneStepsStayDone() {
        val rows = buildTimeline(twoStepPlan(), doneCount = 1, error = null)
        assertEquals(StepState.DONE, rows[2].state)
        assertEquals(StepState.RUNNING, rows[3].state)
    }

    @Test
    fun failedStepShowsError() {
        val rows = buildTimeline(twoStepPlan(), doneCount = 1, error = "No internet")
        assertEquals(StepState.DONE, rows[2].state)
        assertEquals(StepState.FAILED, rows[3].state)
        assertEquals("No internet", rows[3].error)
        assertEquals(StepState.FAILED, rows.last().state)
    }

    @Test
    fun retryKeepsDoneSteps() {
        val failed = buildTimeline(twoStepPlan(), doneCount = 1, error = "No internet")
        val retried = retryTimeline(failed)
        assertEquals(StepState.DONE, retried[2].state)
        assertEquals("Tool 2 of 2: sendEmail", retried[3].title)
        assertEquals(StepState.RUNNING, retried[3].state)
        assertEquals(null, retried[3].error)
        assertEquals(false, retried.any { it.state == StepState.FAILED })
    }

    @Test
    fun retryWithoutFailureLeavesUnchanged() {
        val working = buildTimeline(twoStepPlan(), doneCount = 1, error = null)
        assertEquals(working, retryTimeline(working))
    }

    @Test
    fun emptyPlanAsksAgain() {
        val rows = buildTimeline(Plan(steps = emptyList()), doneCount = 0, error = null)
        assertEquals(true, rows.any { it.title.contains("ask again", ignoreCase = true) })
        assertEquals(rows, retryTimeline(rows))
    }
}
