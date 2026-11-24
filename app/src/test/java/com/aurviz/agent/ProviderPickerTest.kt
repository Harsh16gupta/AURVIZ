package com.aurviz.agent

import com.aurviz.agent.ui.AgentUiState
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// T4 slice 1 (red): picker = name in, address + key + model out.
// No internet, no Android — just a lookup from settings.
class ProviderPickerTest {

    private fun userTurn(text: String) = listOf(ChatMessage(role = "user", content = text))

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
        val config = resolveProvider("not-a-provider", settings())
        assertEquals("gemini", config.name)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", config.baseUrl)
    }

    @Test
    fun deepseekNameUsesDeepSeekAddressKeyModel() {
        val deepseekSettings = settings().copy(
            deepseekKey = "deepseek-key",
            deepseekModel = "deepseek-flash",
        )
        val config = resolveProvider("deepseek", deepseekSettings)
        assertEquals("deepseek", config.name)
        assertEquals("https://api.deepseek.com", config.baseUrl)
        assertEquals("deepseek-key", config.apiKey)
        assertEquals("deepseek-flash", config.model)
    }

    @Test
    fun metaNameUsesMetaAddressKeyModel() {
        val metaSettings = settings().copy(
            metaKey = "meta-key",
            metaModel = "muse-spark-1.3",
        )
        val config = resolveProvider("meta", metaSettings)
        assertEquals("meta", config.name)
        assertEquals("https://api.meta.ai/v1", config.baseUrl)
        assertEquals("meta-key", config.apiKey)
        assertEquals("muse-spark-1.3", config.model)
    }

    @Test
    fun defaultCandidatesOrdersShippedPairGeminiFirst() {
        // ADR-0004 amended: DeepSeek + Meta are now wired alongside Gemini/Groq.
        val candidates = defaultCandidates("gemini", LlmKeys(geminiKey = "g", groqKey = "q", deepseekKey = "d", metaKey = "m"))
        assertEquals(listOf("gemini", "deepseek", "meta", "groq"), candidates.map { it.name })
    }

    @Test
    fun defaultCandidatesPutsPreferredShippedProviderFirst() {
        val candidates = defaultCandidates("groq", LlmKeys(geminiKey = "g", groqKey = "q", deepseekKey = "d", metaKey = "m"))
        assertEquals(listOf("groq", "deepseek", "meta", "gemini"), candidates.map { it.name })
    }

    @Test
    fun defaultCandidatesIncludesDeepSeekAndMeta() {
        // ADR-0004 amended: DeepSeek and direct Meta are now wired providers.
        for (preferred in listOf("gemini", "groq", "deepseek", "meta")) {
            val names = defaultCandidates(preferred, LlmKeys(geminiKey = "g", groqKey = "q", deepseekKey = "d", metaKey = "m")).map { it.name }
            assertTrue("preferred=$preferred -> $names", "deepseek" in names)
            assertTrue("preferred=$preferred -> $names", "meta" in names)
            assertEquals(preferred, names.first())
        }
        val blankNames = defaultCandidates("  ", LlmKeys(geminiKey = "g", groqKey = "q", deepseekKey = "d", metaKey = "m")).map { it.name }
        assertEquals("gemini", blankNames.first())
    }

    private fun successOn(name: String): NamedProvider {
        val result =
            LlmResult(
                toolCalls = listOf(ToolCall("call_1", "search_emails", mapOf("query" to "is:important is:unread", "max" to 5))),
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
        val outcome = planWithFallbackMessages(userTurn("check mail"), listOf(first, second), sleeper = {})
        assertEquals("groq", outcome.providerName)
        assertEquals(listOf("gemini", "groq"), outcome.tried)
        assertEquals("search_emails", outcome.result.toolCalls[0].name)
    }

    @Test
    fun skipsNoKeyWithoutCalling() {
        val noKey =
            NamedProvider(
                "stale",
                "",
                object : StructuredLlmProvider {
                    override fun chatMessages(messages: List<ChatMessage>, tools: JSONArray): LlmResult =
                        throw AssertionError("no-key provider must not be called")
                },
            )
        val outcome = planWithFallbackMessages(userTurn("check mail"), listOf(noKey, successOn("groq")))
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
            planWithFallbackMessages(userTurn("check mail"), listOf(badKey, next), sleeper = { slept += it })
        } catch (e: FallbackExhausted) {
            stopped = (e.cause as? LlmException)?.failure?.kind == "auth"
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
                object : StructuredLlmProvider {
                    override fun chatMessages(messages: List<ChatMessage>, tools: JSONArray): LlmResult =
                        throw AssertionError("must not be called")
                },
            )
        planWithFallbackMessages(userTurn("check mail"), listOf(none))
    }

    @Test
    fun retryDisclosureNamesRetriedProvider() {
        val first = failOn("gemini", HttpFailure("rate_limited", 0, true))
        val second = successOn("groq")
        val outcome = planWithFallbackMessages(userTurn("check mail"), listOf(first, second), sleeper = {})
        assertEquals("Retried on groq (tried gemini, groq)", retryDisclosure(outcome))
    }

    @Test
    fun noDisclosureWhenFirstProviderSucceeds() {
        val outcome = planWithFallbackMessages(userTurn("check mail"), listOf(successOn("gemini")))
        assertNull(retryDisclosure(outcome))
    }

    @Test
    fun crossProviderRetryDisclosesEveryProviderThatSawThePrompt() {
        // ADR-0004: the same private-context prompt is replayed to the second
        // provider, so the retry must never be silent — every provider that
        // saw the prompt has to be disclosed in UI state.
        val seenMessages = mutableMapOf<String, List<ChatMessage>>()
        val flaky =
            NamedProvider(
                "gemini",
                "gemini-key",
                object : StructuredLlmProvider {
                    override fun chatMessages(messages: List<ChatMessage>, tools: JSONArray): LlmResult {
                        seenMessages["gemini"] = messages.toList()
                        throw LlmException(HttpFailure("rate_limited", 0, true), "HTTP 429")
                    }
                },
            )
        val backup =
            NamedProvider(
                "groq",
                "groq-key",
                object : StructuredLlmProvider {
                    override fun chatMessages(messages: List<ChatMessage>, tools: JSONArray): LlmResult {
                        seenMessages["groq"] = messages.toList()
                        return LlmResult(emptyList(), "done")
                    }
                },
            )
        val prompt = "Known: take nightly meds. Summarize inbox"
        val outcome = planWithFallbackMessages(userTurn(prompt), listOf(flaky, backup), sleeper = {})
        val geminiUser = seenMessages["gemini"]!!.single { it.role == "user" }.content
        val groqUser = seenMessages["groq"]!!.single { it.role == "user" }.content
        assertEquals(prompt, geminiUser)
        assertEquals(prompt, groqUser)
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
        val outcome = planWithFallbackMessages(userTurn("check mail"), listOf(first, second), sleeper = { slept += it })
        assertEquals("groq", outcome.providerName)
        assertEquals(listOf(5_000L), slept)
    }

    @Test
    fun consecutiveRateLimitsBackOffExponentially() {
        val first = failOn("gemini", HttpFailure("rate_limited", 0, true))
        val second = failOn("groq", HttpFailure("rate_limited", null, true))
        val third = successOn("gemini")
        val slept = mutableListOf<Long>()
        val outcome = planWithFallbackMessages(userTurn("check mail"), listOf(first, second, third), sleeper = { slept += it })
        assertEquals("gemini", outcome.providerName)
        assertEquals(listOf(1_000L, 2_000L), slept)
    }

    @Test
    fun noSleepWhenNothingLeftToTry() {
        val only = failOn("gemini", HttpFailure("rate_limited", 5, true))
        val slept = mutableListOf<Long>()
        try {
            planWithFallbackMessages(userTurn("check mail"), listOf(only), sleeper = { slept += it })
            throw AssertionError("expected the rate limit to propagate")
        } catch (e: FallbackExhausted) {
            assertEquals(listOf("gemini"), e.tried)
            assertEquals("rate_limited", (e.cause as LlmException).failure.kind)
        }
        assertTrue(slept.isEmpty())
    }
}

private class StubProvider(private val result: LlmResult) : StructuredLlmProvider {
    var calls = 0
    override fun chatMessages(messages: List<ChatMessage>, tools: JSONArray): LlmResult {
        calls++
        return result
    }
}

private class FailingProvider(private val failure: HttpFailure) : StructuredLlmProvider {
    var calls = 0
    override fun chatMessages(messages: List<ChatMessage>, tools: JSONArray): LlmResult {
        calls++
        throw LlmException(failure, "HTTP ${failure.kind}")
    }
}
