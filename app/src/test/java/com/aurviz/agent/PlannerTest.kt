package com.aurviz.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// T3: planner turns a typed command into a previewable Plan.
// Pure decision logic at testable seams; tested with a stub
// LlmProvider (no network).
class PlannerTest {

    @Test
    fun completeCommandYieldsReadyPlan() {
        val provider =
            StubLlmProvider(
                LlmResult(
                    toolCalls =
                        listOf(
                            ToolCall(
                                "call_1",
                                "sendEmail",
                                mapOf("to" to "Priya", "body" to "I will be late"),
                            ),
                        ),
                    content = null,
                ),
            )
        val outcome = planCommand("email Priya I will be late", provider)
        assertTrue(outcome is PlannerOutcome.Ready)
        val ready = outcome as PlannerOutcome.Ready
        assertEquals(1, ready.plan.steps.size)
        assertEquals("sendEmail", ready.plan.steps[0].name)
        assertEquals("Priya", ready.plan.steps[0].arguments["to"])
        assertEquals("I will be late", ready.plan.steps[0].arguments["body"])
    }

    @Test
    fun completeSendEmailHasNoMissingSlots() {
        val call = ToolCall("call_1", "sendEmail", mapOf("to" to "Priya", "body" to "I will be late"))
        assertTrue(missingSlots(call).isEmpty())
    }

    @Test
    fun sendEmailMissingRecipient() {
        val call = ToolCall("call_1", "sendEmail", mapOf("to" to "  ", "body" to "I will be late"))
        assertEquals(listOf("to"), missingSlots(call))
    }

    @Test
    fun sendEmailMissingBody() {
        val call = ToolCall("call_1", "sendEmail", mapOf("to" to "Priya"))
        assertEquals(listOf("body"), missingSlots(call))
    }

    @Test
    fun importantEmailsMissingMaxResults() {
        val call = ToolCall("call_1", "getImportantEmails", emptyMap())
        assertEquals(listOf("max_results"), missingSlots(call))
    }

    @Test
    fun unknownToolNeedsNoSlots() {
        val call = ToolCall("call_1", "openWebsite", mapOf("url" to "example.com"))
        assertTrue(missingSlots(call).isEmpty())
    }

    @Test
    fun blankCommandAsksWhatToDoWithoutCallingLlm() {
        val provider =
            object : LlmProvider {
                override fun plan(userText: String): LlmResult =
                    throw AssertionError("LLM must not be called for blank input")
            }
        val outcome = planCommand("   ", provider)
        assertTrue(outcome is PlannerOutcome.NeedsClarification)
        assertEquals(
            "What would you like me to do?",
            (outcome as PlannerOutcome.NeedsClarification).question,
        )
    }

    @Test
    fun textReplyWithNoToolsPassesThroughQuestion() {
        val provider = StubLlmProvider(LlmResult(toolCalls = emptyList(), content = "Which inbox?"))
        val outcome = planCommand("what can you do?", provider)
        assertTrue(outcome is PlannerOutcome.NeedsClarification)
        assertEquals("Which inbox?", (outcome as PlannerOutcome.NeedsClarification).question)
    }

    @Test
    fun emptyReplyFallsBackToDefaultQuestion() {
        val provider = StubLlmProvider(LlmResult(toolCalls = emptyList(), content = null))
        val outcome = planCommand("hello", provider)
        assertTrue(outcome is PlannerOutcome.NeedsClarification)
        assertEquals(
            "Could you say that another way?",
            (outcome as PlannerOutcome.NeedsClarification).question,
        )
    }

    @Test
    fun missingRecipientAsksInsteadOfSending() {
        val provider =
            StubLlmProvider(
                LlmResult(
                    toolCalls =
                        listOf(
                            ToolCall("call_1", "sendEmail", mapOf("body" to "I will be late")),
                        ),
                    content = null,
                ),
            )
        val outcome = planCommand("email I will be late", provider)
        assertTrue(outcome is PlannerOutcome.NeedsClarification)
        assertEquals(
            "Who should I send it to?",
            (outcome as PlannerOutcome.NeedsClarification).question,
        )
    }

    @Test
    fun multiStepPlanPreservesToolOrder() {
        val provider =
            StubLlmProvider(
                LlmResult(
                    toolCalls =
                        listOf(
                            ToolCall("call_1", "getImportantEmails", mapOf("max_results" to 5)),
                            ToolCall(
                                "call_2",
                                "sendEmail",
                                mapOf("to" to "Priya", "body" to "I will be late"),
                            ),
                        ),
                    content = null,
                ),
            )
        val outcome = planCommand("check important emails then email Priya I will be late", provider)
        assertTrue(outcome is PlannerOutcome.Ready)
        val steps = (outcome as PlannerOutcome.Ready).plan.steps
        assertEquals(2, steps.size)
        assertEquals("getImportantEmails", steps[0].name)
        assertEquals("sendEmail", steps[1].name)
    }

    @Test
    fun completeCreateScheduleYieldsReadyPlan() {
        val provider =
            StubLlmProvider(
                LlmResult(
                    toolCalls =
                        listOf(
                            ToolCall(
                                "call_1",
                                "create_schedule",
                                mapOf("task" to "read emails", "when" to "at 2:38 a.m."),
                            ),
                        ),
                    content = null,
                ),
            )
        val outcome = planCommand("schedule email read at 2:38 a.m.", provider)
        assertTrue(outcome is PlannerOutcome.Ready)
        val ready = outcome as PlannerOutcome.Ready
        assertEquals("create_schedule", ready.plan.steps[0].name)
    }

    @Test
    fun createScheduleMissingWhenAsks() {
        val call = ToolCall("call_1", "create_schedule", mapOf("task" to "read emails"))
        assertEquals(listOf("when"), missingSlots(call))
    }

    @Test
    fun cancelScheduleMissingIdAsks() {
        val provider =
            StubLlmProvider(
                LlmResult(
                    toolCalls = listOf(ToolCall("call_1", "cancel_schedule", emptyMap())),
                    content = null,
                ),
            )
        val outcome = planCommand("delete my routine", provider)
        assertTrue(outcome is PlannerOutcome.NeedsClarification)
        assertEquals(
            "Which routine should I delete?",
            (outcome as PlannerOutcome.NeedsClarification).question,
        )
    }

    @Test
    fun listSchedulesNeedsNoSlots() {
        val call = ToolCall("call_1", "list_schedules", emptyMap())
        assertTrue(missingSlots(call).isEmpty())
    }

    @Test
    fun createScheduleMissingTaskAsks() {
        val provider =
            StubLlmProvider(
                LlmResult(
                    toolCalls = listOf(ToolCall("call_1", "create_schedule", mapOf("when" to "at 9AM"))),
                    content = null,
                ),
            )
        val outcome = planCommand("schedule something", provider)
        assertTrue(outcome is PlannerOutcome.NeedsClarification)
        assertEquals(
            "What should the routine do?",
            (outcome as PlannerOutcome.NeedsClarification).question,
        )
    }
}

private class StubLlmProvider(private val result: LlmResult) : LlmProvider {
    override fun plan(userText: String): LlmResult = result
}
