package com.aurviz.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket 02/24: AgentLoop Task Execution paths, driven by a scripted
// FakeLlmProvider (multi-step, error-then-recovery, max-step abort,
// reject path). Seams: AgentLoop.run + the events listener +
// AgentLoopResult.toTimeline over the existing buildTimeline UI path.
class AgentLoopTest {

    private class FakeGmail : GmailApi {
        override fun send(rawBase64Url: String) = Unit
        override fun listInbox(): List<EmailSummary> =
            listOf(
                EmailSummary("1", "boss@work.com", "Q3 plan", "draft", true),
                EmailSummary("2", "news@x.com", "Sale", "buy", false),
            )
    }

    private fun registry(store: InMemoryScheduleStore = InMemoryScheduleStore()): ToolRegistry =
        defaultToolRegistry(gmailApi = FakeGmail(), scheduleStore = store)

    private fun payload(message: ChatMessage): JSONObject = JSONObject(message.content.orEmpty())

    private fun answer(text: String) = LlmResult(toolCalls = emptyList(), content = text)

    private fun call(id: String?, name: String, args: Map<String, Any?>) =
        LlmResult(toolCalls = listOf(ToolCall(id, name, args)), content = null)

    @Test
    fun plainAnswerTerminatesWithoutTools() {
        val llm = FakeLlmProvider(listOf(answer("Hello!")))
        val events = mutableListOf<AgentEvent>()
        val result = AgentLoop(llm, registry(), onEvent = events::add).run("hi")
        assertTrue(result is AgentLoopResult.Answered)
        assertEquals("Hello!", (result as AgentLoopResult.Answered).answer)
        assertTrue(result.steps.isEmpty())
        assertEquals(1, llm.seenMessages.size)
        assertEquals(listOf(AgentEvent.StepStarted(0)), events)
    }

    @Test
    fun multiStepLoopMatchesToolCallIds() {
        val llm =
            FakeLlmProvider(
                listOf(
                    call("call_1", "getImportantEmails", mapOf("max_results" to 5)),
                    call("call_2", "createSchedule", mapOf("task" to "check mail", "when" to "at 9am")),
                    answer("Done"),
                ),
            )
        val events = mutableListOf<AgentEvent>()
        val result = AgentLoop(llm, registry(), onEvent = events::add).run("morning routine")
        assertTrue(result is AgentLoopResult.Answered)
        assertEquals("Done", (result as AgentLoopResult.Answered).answer)
        assertEquals(2, result.steps.size)

        // role:"tool" messages match the assistant tool_call_id (ADR-0005).
        val toolMessages = result.messages.filter { it.role == "tool" }
        assertEquals(2, toolMessages.size)
        assertEquals("call_1", toolMessages[0].toolCallId)
        assertEquals("call_2", toolMessages[1].toolCallId)
        assertTrue(payload(toolMessages[0]).getBoolean("ok"))
        assertTrue(payload(toolMessages[1]).getBoolean("ok"))

        val first = result.steps[0].call
        assertEquals(AgentEvent.StepStarted(0), events[0])
        assertEquals(AgentEvent.ToolRunning(first), events[1])
        assertTrue(events[2] is AgentEvent.StepDone)
        assertEquals(AgentEvent.StepStarted(1), events[3])
    }

    @Test
    fun toolErrorIsStructuredSoModelRecovers() {
        val llm =
            FakeLlmProvider(
                listOf(
                    // Missing max_results: registry validation fails this turn.
                    call("call_1", "getImportantEmails", emptyMap()),
                    call("call_2", "getImportantEmails", mapOf("max_results" to 5)),
                    answer("Here are your emails"),
                ),
            )
        val result = AgentLoop(llm, registry()).run("check mail")
        assertTrue(result is AgentLoopResult.Answered)
        assertEquals(2, result.steps.size)

        val firstResult = result.steps[0].result
        assertTrue(firstResult is ToolResult.Failure)
        assertTrue((firstResult as ToolResult.Failure).retryable)

        // The model sees {ok:false, error, retryable} and recovers next turn.
        val toolMessages = result.messages.filter { it.role == "tool" }
        val firstPayload = payload(toolMessages[0])
        assertEquals(false, firstPayload.getBoolean("ok"))
        assertTrue(firstPayload.getString("error").contains("max_results"))
        assertTrue(result.steps[1].result is ToolResult.Success)
        assertEquals("Here are your emails", (result as AgentLoopResult.Answered).answer)
    }

    @Test
    fun maxStepsAbortEmitsFailedWithPartialTimeline() {
        val script = (0 until 10).map { call("call_$it", "getImportantEmails", mapOf("max_results" to 1)) }
        val llm = FakeLlmProvider(script)
        val events = mutableListOf<AgentEvent>()
        val result = AgentLoop(llm, registry(), onEvent = events::add).run("never ends")
        assertTrue(result is AgentLoopResult.Failed)
        result as AgentLoopResult.Failed
        assertEquals(AgentLoop.MAX_STEPS, result.steps.size)
        assertTrue(result.error.contains("8"))

        // The loop stops asking: exactly MAX_STEPS LLM turns, then Failed.
        assertEquals(AgentLoop.MAX_STEPS, llm.seenMessages.size)
        assertEquals(AgentLoop.MAX_STEPS, events.filterIsInstance<AgentEvent.StepStarted>().size)
        assertTrue(events.last() is AgentEvent.Failed)

        // Partial timeline: executed Tools stay DONE, tail row is the Error.
        val rows = result.toTimeline()
        assertTrue(rows.any { it.title.startsWith("Tool 8 of 8: getImportantEmails") })
        assertEquals(StepState.FAILED, rows.last().state)
        assertTrue(rows.last().title.startsWith("Error:"))
    }

    @Test
    fun rejectedConfirmationIsNotExecuted() {
        val store = InMemoryScheduleStore()
        val llm =
            FakeLlmProvider(
                listOf(
                    call("call_1", "createSchedule", mapOf("task" to "check mail", "when" to "at 9am")),
                    answer("OK, I won't schedule it"),
                ),
            )
        val events = mutableListOf<AgentEvent>()
        val loop =
            AgentLoop(
                llm,
                registry(store),
                onEvent = events::add,
                confirm = { ApprovalDecision.Rejected },
            )
        val result = loop.run("schedule mail check")
        assertTrue(result is AgentLoopResult.Answered)
        assertEquals(1, result.steps.size)
        assertEquals(false, result.steps[0].approved)

        // Reject returns {status:"cancelled_by_user"} to the model; nothing runs.
        assertEquals(0, store.list().size)
        val toolMessages = result.messages.filter { it.role == "tool" }
        assertEquals(1, toolMessages.size)
        assertEquals("cancelled_by_user", payload(toolMessages[0]).getJSONObject("data").getString("status"))
        assertTrue(events.any { it is AgentEvent.NeedsApproval })
        assertEquals("OK, I won't schedule it", (result as AgentLoopResult.Answered).answer)
    }

    @Test
    fun nullToolCallIdIsGeneratedAndMatched() {
        val llm =
            FakeLlmProvider(
                listOf(
                    call(null, "getImportantEmails", mapOf("max_results" to 5)),
                    answer("Done"),
                ),
            )
        val result = AgentLoop(llm, registry()).run("check mail")
        assertTrue(result is AgentLoopResult.Answered)
        result as AgentLoopResult.Answered
        val generated = result.steps[0].call.id
        assertTrue(!generated.isNullOrBlank())
        val toolMessages = result.messages.filter { it.role == "tool" }
        assertEquals(1, toolMessages.size)
        assertEquals(generated, toolMessages[0].toolCallId)
    }

    @Test
    fun loopTimelineShowsExecutedTools() {
        val llm =
            FakeLlmProvider(
                listOf(
                    call("call_1", "getImportantEmails", mapOf("max_results" to 5)),
                    call("call_2", "createSchedule", mapOf("task" to "check mail", "when" to "at 9am")),
                    answer("Done"),
                ),
            )
        val result = AgentLoop(llm, registry()).run("morning routine")
        assertTrue(result is AgentLoopResult.Answered)
        val rows = (result as AgentLoopResult.Answered).toTimeline()
        assertEquals("Heard command", rows[0].title)
        assertEquals("Planned 2 steps", rows[1].title)
        assertEquals("Tool 1 of 2: getImportantEmails", rows[2].title)
        assertEquals(StepState.DONE, rows[2].state)
        assertEquals("Tool 2 of 2: createSchedule", rows[3].title)
        assertEquals(StepState.DONE, rows[3].state)
        assertEquals(StepState.DONE, rows.last().state)
    }
}
