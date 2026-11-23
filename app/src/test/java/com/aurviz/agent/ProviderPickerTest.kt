package com.aurviz.agent

import com.aurviz.agent.ui.AgentUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// T4 slice 1 (red): picker = name in, address + key + model out.
// No internet, no Android — just a lookup from settings.
class ProviderPickerTest {

    private fun settings() =
        ProviderSettings(
            geminiKey = "gemini-key",
            geminiModel = "gemini-2.5-flash",
            groqKey = "groq-key",
            groqModel = "llama-3.1-8b-instant",
        )

    @Test
    fun groqNameUsesGroqAddressKeyModel() {
        val config = resolveProvider("groq", settings())
        assertEquals("https://api.groq.com/openai/v1", config.baseUrl)
        assertEquals("groq-key", config.apiKey)
        assertEquals("llama-3.1-8b-instant", config.model)
    }

    @Test
    fun unknownNameFallsBackToGemini() {
        val config = resolveProvider("meta", settings())
        assertEquals("gemini", config.name)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", config.baseUrl)
    }

    @Test
    fun droppedDeepseekNameFallsBackToGemini() {
        // ADR-0004: DeepSeek is unwired — the name no longer resolves and
        // must fall back to Gemini like any other unknown name.
        val config = resolveProvider("deepseek", settings())
        assertEquals("gemini", config.name)
        assertEquals("gemini-key", config.apiKey)
    }

    private fun successOn(name: String): NamedProvider {
        val result =
            LlmResult(
                toolCalls = listOf(ToolCall("call_1", "getImportantEmails", mapOf("max_results" to 5))),
                content = null,
            )
        return NamedProvider(name, "$name-key", StubProvider(result))
    }

    private fun failOn(name: String, failure: HttpFailure): NamedProvider =
        NamedProvider(name, "$name-key", FailingProvider(failure))

    @Test
    fun rateLimitRetriesNextAndSurfacesWhichRan() {
        val first = failOn("gemini", HttpFailure("rate_limited", 0, true))
        val second = successOn("groq")
        val outcome = planWithFallback("check mail", listOf(first, second), sleeper = {})
        assertEquals("groq", outcome.providerName)
        assertEquals(listOf("gemini", "groq"), outcome.tried)
        assertEquals("getImportantEmails", outcome.result.toolCalls[0].name)
    }

    @Test
    fun skipsNoKeyWithoutCalling() {
        val noKey =
            NamedProvider(
                "stale",
                "",
                object : LlmProvider {
                    override fun plan(userText: String): LlmResult =
                        throw AssertionError("no-key provider must not be called")
                },
            )
        val outcome = planWithFallback("check mail", listOf(noKey, successOn("groq")))
        assertEquals("groq", outcome.providerName)
        assertEquals(listOf("groq"), outcome.tried)
    }

    @Test
    fun authErrorStopsWithoutRetrying() {
        val badKey = failOn("groq", HttpFailure("auth", null, false))
        val next = successOn("gemini")
        val slept = mutableListOf<Long>()
        var stopped = false
        try {
            planWithFallback("check mail", listOf(badKey, next), sleeper = { slept += it })
        } catch (e: LlmException) {
            stopped = e.failure.kind == "auth"
        }
        assertEquals(true, stopped)
        assertEquals(1, (badKey.provider as FailingProvider).calls)
        assertEquals(0, (next.provider as StubProvider).calls)
        assertTrue(slept.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun allSkippedThrowsClearError() {
        val none =
            NamedProvider(
                "gemini",
                "  ",
                object : LlmProvider {
                    override fun plan(userText: String): LlmResult =
                        throw AssertionError("must not be called")
                },
            )
        planWithFallback("check mail", listOf(none))
    }

    @Test
    fun retryDisclosureNamesRetriedProvider() {
        val first = failOn("gemini", HttpFailure("rate_limited", 0, true))
        val second = successOn("groq")
        val outcome = planWithFallback("check mail", listOf(first, second), sleeper = {})
        assertEquals("Retried on groq (tried gemini, groq)", retryDisclosure(outcome))
    }

    @Test
    fun noDisclosureWhenFirstProviderSucceeds() {
        val outcome = planWithFallback("check mail", listOf(successOn("gemini")))
        assertNull(retryDisclosure(outcome))
    }

    @Test
    fun crossProviderRetryDisclosesEveryProviderThatSawThePrompt() {
        // ADR-0004: the same private-context prompt is replayed to the second
        // provider, so the retry must never be silent — every provider that
        // saw the prompt has to be disclosed in UI state.
        val seenPrompts = mutableMapOf<String, String>()
        val flaky =
            NamedProvider(
                "gemini",
                "gemini-key",
                object : LlmProvider {
                    override fun plan(userText: String): LlmResult {
                        seenPrompts["gemini"] = userText
                        throw LlmException(HttpFailure("rate_limited", 0, true), "HTTP 429")
                    }
                },
            )
        val backup =
            NamedProvider(
                "groq",
                "groq-key",
                object : LlmProvider {
                    override fun plan(userText: String): LlmResult {
                        seenPrompts["groq"] = userText
                        return LlmResult(emptyList(), "done")
                    }
                },
            )
        val prompt = "Known: take nightly meds. Summarize inbox"
        val outcome = planWithFallback(prompt, listOf(flaky, backup), sleeper = {})
        assertEquals(prompt, seenPrompts["gemini"])
        assertEquals(prompt, seenPrompts["groq"])
        assertEquals(listOf("gemini", "groq"), outcome.tried)
        val state = AgentUiState(providerUsed = outcome.providerName, providersTried = outcome.tried)
        val disclosure = retryDisclosure(outcome)
        assertEquals("Retried on groq (tried gemini, groq)", disclosure)
        assertEquals(outcome.tried, state.providersTried)
        assertEquals("groq", state.providerUsed)
    }

    @Test
    fun backoffDelayHonoursRetryAfter() {
        // ADR-0004: exponential backoff honouring retry-after — the wait is
        // never shorter than what the provider asked for.
        assertEquals(20_000L, backoffDelayMs(retryAfterSec = 20, attempt = 0))
        assertEquals(20_000L, backoffDelayMs(retryAfterSec = 20, attempt = 3))
    }

    @Test
    fun backoffDelayGrowsExponentiallyWithoutRetryAfter() {
        assertEquals(1_000L, backoffDelayMs(retryAfterSec = null, attempt = 0))
        assertEquals(2_000L, backoffDelayMs(retryAfterSec = 0, attempt = 1))
        assertEquals(4_000L, backoffDelayMs(retryAfterSec = null, attempt = 2))
    }

    @Test
    fun backoffDelayIsCapped() {
        assertEquals(30_000L, backoffDelayMs(retryAfterSec = 300, attempt = 0))
        assertEquals(30_000L, backoffDelayMs(retryAfterSec = null, attempt = 10))
    }

    @Test
    fun fallbackSleepsHonouringRetryAfterBeforeNextProvider() {
        val first = failOn("gemini", HttpFailure("rate_limited", 5, true))
        val second = successOn("groq")
        val slept = mutableListOf<Long>()
        val outcome = planWithFallback("check mail", listOf(first, second), sleeper = { slept += it })
        assertEquals("groq", outcome.providerName)
        assertEquals(listOf(5_000L), slept)
    }

    @Test
    fun consecutiveRateLimitsBackOffExponentially() {
        val first = failOn("gemini", HttpFailure("rate_limited", 0, true))
        val second = failOn("groq", HttpFailure("rate_limited", null, true))
        val third = successOn("gemini")
        val slept = mutableListOf<Long>()
        val outcome = planWithFallback("check mail", listOf(first, second, third), sleeper = { slept += it })
        assertEquals("gemini", outcome.providerName)
        assertEquals(listOf(1_000L, 2_000L), slept)
    }

    @Test
    fun noSleepWhenNothingLeftToTry() {
        val only = failOn("gemini", HttpFailure("rate_limited", 5, true))
        val slept = mutableListOf<Long>()
        try {
            planWithFallback("check mail", listOf(only), sleeper = { slept += it })
            throw AssertionError("expected the rate limit to propagate")
        } catch (e: LlmException) {
            assertEquals("rate_limited", e.failure.kind)
        }
        assertTrue(slept.isEmpty())
    }
}

private class StubProvider(private val result: LlmResult) : LlmProvider {
    var calls = 0
    override fun plan(userText: String): LlmResult {
        calls++
        return result
    }
}

private class FailingProvider(private val failure: HttpFailure) : LlmProvider {
    var calls = 0
    override fun plan(userText: String): LlmResult {
        calls++
        throw LlmException(failure, "HTTP ${failure.kind}")
    }
}
