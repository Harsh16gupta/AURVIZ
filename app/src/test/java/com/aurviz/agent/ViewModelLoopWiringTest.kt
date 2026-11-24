package com.aurviz.agent

import com.aurviz.agent.ui.AgentUiState
import com.aurviz.agent.ui.LoopUiProgress
import com.aurviz.agent.ui.approvalWaitMs
import com.aurviz.agent.ui.isDirectEmailCommand
import com.aurviz.agent.ui.isDirectOpenWebCommand
import com.aurviz.agent.ui.retryLine
import com.aurviz.agent.ui.withEvent
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket 11/24: ViewModel loop wiring (ADR-0005 §3A, GLOSSARY Task
// Execution). The AgentLoop replaces runRealPlan's single plan() call;
// these tests pin the wiring seams: AgentEvents fold into the live
// timeline, provider fallback still reports providerUsed/providersTried,
// and a read command works end-to-end through the loop stack.
class ViewModelLoopWiringTest {

    private class FakeGmail : GmailApi {
        override fun send(rawBase64Url: String) = Unit
        override fun listInbox(): List<EmailSummary> =
            listOf(
                EmailSummary("1", "boss@work.com", "Q3 plan", "draft", true),
                EmailSummary("2", "news@x.com", "Sale", "buy", false),
            )
        override fun searchInbox(query: String, max: Int): List<EmailSummary> =
            listInbox().filter {
                it.from.contains(query, ignoreCase = true) ||
                    it.subject.contains(query, ignoreCase = true) ||
                    it.snippet.contains(query, ignoreCase = true)
            }.take(max.coerceIn(1, 25))
        override fun readMessage(id: String): EmailDetail {
            val row = listInbox().firstOrNull { it.id == id }
                ?: throw NoSuchElementException("unknown message: $id")
            return EmailDetail(row.id, row.from, row.subject, row.snippet, "", emailSource(row.id))
        }
    }

    private class ScriptedMessagesProvider(private val script: List<LlmResult>) : StructuredLlmProvider {
        val seenMessages = mutableListOf<List<ChatMessage>>()
        override fun chatMessages(messages: List<ChatMessage>, tools: JSONArray): LlmResult {
            seenMessages += messages.toList()
            return script[minOf(seenMessages.size - 1, script.lastIndex)]
        }
    }

    private fun searchCall() =
        LlmResult(
            toolCalls = listOf(ToolCall("call_1", "search_emails", mapOf("query" to "boss", "max" to 5))),
            content = null,
        )

    private fun answer(text: String) = LlmResult(toolCalls = emptyList(), content = text)

    @Test
    fun liveReadTimelineRendersRunningThenDone() {
        val llm = FakeLlmProvider(listOf(searchCall(), answer("Found your Q3 plan email")))
        val registry = defaultToolRegistry(gmailApi = FakeGmail())
        var progress = LoopUiProgress()
        val live = mutableListOf<List<TimelineStep>>()
        val result =
            AgentLoop(
                llm,
                registry,
                onEvent = { event ->
                    progress = progress.withEvent(event)
                    live += progress.timeline()
                },
            ).run("check mail")

        assertTrue(result is AgentLoopResult.Answered)

        // While the Tool runs, the Task Execution shows a RUNNING row.
        val running = live.first { rows -> rows.any { it.state == StepState.RUNNING } }
        assertTrue(running.any { it.title == "Tool 1 of 1: search_emails" && it.state == StepState.RUNNING })

        // At the end the executed Tool is DONE and the answer is the text.
        val terminal = progress.timeline()
        assertTrue(terminal.any { it.title == "Tool 1 of 1: search_emails" && it.state == StepState.DONE })
        assertEquals(StepState.DONE, terminal.last().state)
        assertEquals("Found your Q3 plan email", (result as AgentLoopResult.Answered).answer)
    }

    @Test
    fun failedEventRendersErrorTailRow() {
        val progress = LoopUiProgress().withEvent(AgentEvent.Failed("boom"))
        val rows = progress.timeline()
        assertEquals(StepState.FAILED, rows.last().state)
        assertTrue(rows.last().title.startsWith("Error:"))
    }

    @Test
    fun approvalEventKeepsTimelineStableAndRecordsPendingCall() {
        val call = ToolCall("call_9", "createSchedule", mapOf("task" to "x", "when" to "at 9am"))
        var progress = LoopUiProgress().withEvent(AgentEvent.ToolRunning(call))
        val before = progress.timeline()
        progress = progress.withEvent(AgentEvent.NeedsApproval(call))
        assertEquals(call, progress.pendingApproval)
        assertEquals(before, progress.timeline())
        progress = progress.withEvent(AgentEvent.StepDone(call, ToolResult.Success(mapOf("ok" to true)), approved = true, effectiveArgs = call.arguments))
        assertEquals(1, progress.doneCount)
        assertTrue(progress.timeline().any { it.title.startsWith("Tool ") && it.state == StepState.DONE })
    }

    @Test
    fun approvalWaitIsCappedByRemainingRunBudget() {
        // Fresh run: the card may wait the full bound.
        assertEquals(300_000L, approvalWaitMs(0L, 300_000L))
        // Slow first Tool: only the remainder is waitable.
        assertEquals(60_000L, approvalWaitMs(240_000L, 300_000L))
        // Budget exhausted: no hopeless wait (immediate TimedOut).
        assertEquals(0L, approvalWaitMs(300_000L, 300_000L))
        assertEquals(0L, approvalWaitMs(400_000L, 300_000L))
        // A tighter cap still wins when budget remains.
        assertEquals(10_000L, approvalWaitMs(0L, 300_000L, maxWaitMs = 10_000L))
    }

    @Test
    fun providerFallbackStillReportedAfterLoop() {
        val flaky =
            NamedProvider(
                "gemini",
                "gemini-key",
                object : StructuredLlmProvider {
                    override fun chatMessages(messages: List<ChatMessage>, tools: JSONArray): LlmResult =
                        throw LlmException(HttpFailure("rate_limited", 0, true), "HTTP 429")
                },
            )
        val backup =
            NamedProvider(
                "deepseek",
                "deepseek-key",
                ScriptedMessagesProvider(listOf(searchCall(), answer("done"))),
            )
        val chat = PinnedFallbackChatLlm(listOf(flaky, backup))
        val result = AgentLoop(chat, defaultToolRegistry(gmailApi = FakeGmail())).run("check mail")

        assertTrue(result is AgentLoopResult.Answered)
        // The private-context prompt was replayed to both providers, so
        // both are reported — never a silent retry (ADR-0004).
        assertEquals(listOf("gemini", "deepseek"), chat.tried)
        assertEquals("deepseek", chat.lastProvider)

        val state = AgentUiState(providerUsed = chat.lastProvider, providersTried = chat.tried.toList())
        assertEquals("deepseek", state.providerUsed)
        assertEquals(listOf("gemini", "deepseek"), state.providersTried)
        assertEquals("Retried on deepseek (tried gemini, deepseek)", state.retryLine())
    }

    @Test
    fun noDisclosureWhenSingleProviderAnswersLoop() {
        val chat = PinnedFallbackChatLlm(listOf(NamedProvider("gemini", "k", ScriptedMessagesProvider(listOf(answer("hi"))))))
        val result = AgentLoop(chat, defaultToolRegistry(gmailApi = FakeGmail())).run("hi")
        assertTrue(result is AgentLoopResult.Answered)
        assertEquals(listOf("gemini"), chat.tried)
        assertNull(AgentUiState(providerUsed = chat.lastProvider, providersTried = chat.tried.toList()).retryLine())
    }

    @Test
    fun totalFailureStillDisclosesTriedProviders() {
        val state = AgentUiState(providerUsed = null, providersTried = listOf("gemini", "deepseek"))
        assertEquals("No provider answered (tried gemini, deepseek)", state.retryLine())
    }

    @Test
    fun checkMailWorksEndToEndThroughLoop() {
        val chat =
            PinnedFallbackChatLlm(
                listOf(NamedProvider("gemini", "k", ScriptedMessagesProvider(listOf(searchCall(), answer("Boss wrote about Q3"))))),
            )
        val registry = defaultToolRegistry(gmailApi = FakeGmail())
        var progress = LoopUiProgress()
        val result =
            AgentLoop(chat, registry, onEvent = { progress = progress.withEvent(it) }).run("check mail")

        assertTrue(result is AgentLoopResult.Answered)
        result as AgentLoopResult.Answered
        assertEquals("Boss wrote about Q3", result.answer)

        // The read actually ran: one search_emails step with real rows.
        assertEquals(1, result.steps.size)
        val data = (result.steps[0].result as ToolResult.Success).data as List<*>
        assertEquals(1, data.size)

        // Timeline for the Task Execution is DONE throughout, and the
        // provider that ran is reported.
        val rows = result.toTimeline()
        assertTrue(rows.any { it.title == "Tool 1 of 1: search_emails" && it.state == StepState.DONE })
        assertEquals(StepState.DONE, rows.last().state)
        assertEquals(progress.timeline(), rows)
        assertEquals("gemini", chat.lastProvider)
        assertEquals(listOf("gemini"), chat.tried)
    }

    // Ticket #106 (T1): "open "/"email " fast-path guards — single tokens
    // stay on the fast path, natural language goes to the AgentLoop.

    @Test
    fun directOpenWebCommandAcceptsSingleTokenUrls() {
        assertTrue(isDirectOpenWebCommand("open https://example.com"))
        assertTrue(isDirectOpenWebCommand("open news.ycombinator.com"))
        assertTrue(isDirectOpenWebCommand("OPEN https://example.com"))
    }

    @Test
    fun directOpenWebCommandRejectsNaturalLanguage() {
        assertTrue(!isDirectOpenWebCommand("open my latest email from Priya"))
        assertTrue(!isDirectOpenWebCommand("open my latest email from Priya and summarize it"))
        assertTrue(!isDirectOpenWebCommand("open "))
        assertTrue(!isDirectOpenWebCommand("check mail"))
    }

    @Test
    fun directEmailCommandAcceptsAddressFirstToken() {
        assertTrue(isDirectEmailCommand("email priya@work.com hi"))
        assertTrue(isDirectEmailCommand("email priya@work.com | Hello | Body here"))
        assertTrue(isDirectEmailCommand("Email PRIYA@x.com hello"))
    }

    @Test
    fun directEmailCommandRejectsNaturalLanguage() {
        assertTrue(!isDirectEmailCommand("email summary of unread messages"))
        assertTrue(!isDirectEmailCommand("email "))
        assertTrue(!isDirectEmailCommand("check mail"))
    }
}
