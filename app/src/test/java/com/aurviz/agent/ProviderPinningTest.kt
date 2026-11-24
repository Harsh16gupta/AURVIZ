package com.aurviz.agent

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// P1 cross-provider replay: pin the provider once it answers so private
// transcript is never replayed mid-run, and disclose every provider that
// saw the prompt — including on terminal failure (ADR-0004).
class ProviderPinningTest {

    private fun ok(text: String) = LlmResult(emptyList(), text)

    private class Scripted(structured: Boolean, val script: List<Any>) : StructuredLlmProvider {
        var calls = 0
        val seenMessages = mutableListOf<List<ChatMessage>>()
        override fun plan(userText: String): LlmResult = throw AssertionError("structured path expected")
        override fun chatMessages(messages: List<ChatMessage>, tools: JSONArray): LlmResult {
            seenMessages += messages.toList()
            val next = script[minOf(calls++, script.lastIndex)]
            if (next is Exception) throw next
            return next as LlmResult
        }
    }

    private fun failingThenOk(failure: HttpFailure, answer: String): Scripted =
        Scripted(true, listOf(LlmException(failure, "HTTP x"), ok(answer)))

    @Test
    fun foregroundPinsAfterFirstAnswerAndNeverReplaysPrivateTranscript() {
        val first = Scripted(true, listOf(ok("turn1"), ok("turn2")))
        val second = Scripted(true, listOf(ok("should-never-run")))
        val chat = PinnedFallbackChatLlm(listOf(NamedProvider("gemini", "k", first), NamedProvider("groq", "k", second)))
        val m1 = listOf(ChatMessage("system", "sys [memories] private [/memories]"), ChatMessage("user", "hi"))
        val m2 = m1 + ChatMessage("tool", "private mail body", toolCallId = "c1")
        chat.chat(m1)
        chat.chat(m2)
        assertEquals(listOf("gemini"), chat.tried)
        assertEquals("gemini", chat.lastProvider)
        assertEquals(2, first.calls)
        assertEquals(0, second.calls)
        // The private second turn went only to the pinned provider.
        assertTrue(first.seenMessages[1].any { it.role == "tool" })
    }

    @Test
    fun foregroundFailureStillDisclosesWhoSawIt() {
        val bad = Scripted(true, listOf(LlmException(HttpFailure("error", null, true), "HTTP 500")))
        val chat = PinnedFallbackChatLlm(listOf(NamedProvider("gemini", "k", bad)))
        try {
            chat.chat(listOf(ChatMessage("user", "hi")))
            throw AssertionError("expected failure")
        } catch (e: LlmException) {
            assertEquals(listOf("gemini"), chat.tried)
        }
    }

    @Test
    fun nonFallbackFailureRecordsTriedBeforeStop() {
        val badKey = Scripted(true, listOf(LlmException(HttpFailure("auth", null, false), "HTTP 401")))
        val chat = PinnedFallbackChatLlm(listOf(NamedProvider("groq", "k", badKey)))
        try {
            chat.chat(listOf(ChatMessage("user", "hi")))
            throw AssertionError("expected 401")
        } catch (e: LlmException) {
            assertEquals("auth", e.failure.kind)
            assertEquals(listOf("groq"), chat.tried)
        }
    }

    @Test
    fun scheduledOutcomeCarriesProvidersAndDisclosesRetry() {
        val flaky = failingThenOk(HttpFailure("rate_limited", 0, true), "done")
        val chat = PinnedFallbackChatLlm(
            listOf(
                NamedProvider("gemini", "k", flaky),
                NamedProvider("groq", "k", Scripted(true, listOf(ok("done")))),
            ),
        )
        // First turn retries gemini->groq and pins groq; loop then answers.
        val first = chat.chat(listOf(ChatMessage("system", "sys"), ChatMessage("user", "hi")))
        assertEquals("done", first.content)
        assertEquals(listOf("gemini", "groq"), chat.tried)
        val summary = scheduledSummary("done", null, 0, chat.tried.toList(), chat.lastProvider)
        assertTrue(summary.contains("retried on groq"))
        assertTrue(summary.contains("gemini, groq"))
    }

    @Test
    fun scheduledFailureStillDisclosesTried() {
        val bad = Scripted(true, listOf(LlmException(HttpFailure("error", null, true), "HTTP 500")))
        val chat = PinnedFallbackChatLlm(listOf(NamedProvider("gemini", "k", bad)))
        val schedule = Schedule("sched_1", "check mail", 9, 0, null)
        val outcome = runScheduledRoutine(
            schedule, 1L, chat, ToolRegistry(), InMemoryScheduleRunStore(), InMemoryPendingApprovalStore(),
        )
        assertTrue(outcome is ScheduledRunOutcome.Ran)
        outcome as ScheduledRunOutcome.Ran
        assertEquals(listOf("gemini"), outcome.providersTried)
    }

    @Test
    fun scheduledFailureSummaryNamesTriedProviders() {
        val summary = scheduledSummary("Routine failed: HTTP 500", "HTTP 500", 0, listOf("gemini", "groq"), null)
        assertTrue(summary.contains("gemini, groq"))
        assertTrue(summary.contains("no provider answered"))
    }
}
