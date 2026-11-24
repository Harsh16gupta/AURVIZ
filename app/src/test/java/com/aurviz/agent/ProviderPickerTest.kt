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
            deepseekKey = "deepseek-key",
            deepseekModel = "deepseek-flash",
            metaKey = "meta-key",
            metaModel = "muse-spark-1.3",
        )

    @Test
    fun unknownNameFallsBackToDeepSeek() {
        val config = resolveProvider("not-a-provider", settings())
        assertEquals("deepseek", config.name)
        assertEquals("https://api.deepseek.com", config.baseUrl)
    }

    @Test
    fun legacyGeminiNameMapsToDeepSeek() {
        val config = resolveProvider("gemini", settings())
        assertEquals("deepseek", config.name)
        assertEquals("https://api.deepseek.com", config.baseUrl)
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
    fun defaultCandidatesOrdersShippedPairDeepSeekFirst() {
        // ADR-0004 amended: DeepSeek default with Meta fallback.
        val candidates = defaultCandidates("deepseek", LlmKeys(deepseekKey = "d", metaKey = "m"))
        assertEquals(listOf("deepseek", "meta"), candidates.map { it.name })
    }

    @Test
    fun defaultCandidatesPutsPreferredShippedProviderFirst() {
        val candidates = defaultCandidates("meta", LlmKeys(deepseekKey = "d", metaKey = "m"))
        assertEquals(listOf("meta", "deepseek"), candidates.map { it.name })
    }

    @Test
    fun legacyGeminiPreferredMapsToDeepSeekFirst() {
        val names = defaultCandidates("gemini", LlmKeys(deepseekKey = "d", metaKey = "m")).map { it.name }
        assertEquals(listOf("deepseek", "meta"), names)
    }

    @Test
    fun defaultCandidatesIncludesDeepSeekAndMeta() {
        // ADR-0004 amended: DeepSeek and direct Meta are the wired providers.
        for (preferred in listOf("deepseek", "meta")) {
            val names = defaultCandidates(preferred, LlmKeys(deepseekKey = "d", metaKey = "m")).map { it.name }
            assertTrue("preferred=$preferred -> $names", "deepseek" in names)
            assertTrue("preferred=$preferred -> $names", "meta" in names)
            assertEquals(preferred, names.first())
        }
        val blankNames = defaultCandidates("  ", LlmKeys(deepseekKey = "d", metaKey = "m")).map { it.name }
        assertEquals("deepseek", blankNames.first())
    }

    @Test
    fun firstConfiguredPrefersSelectedWhenItsKeyIsSet() {
        val withMeta = settings().copy(metaKey = "meta-key")
        assertEquals("meta", firstConfiguredProvider("meta", withMeta))
        assertEquals("deepseek", firstConfiguredProvider("deepseek", withMeta))
    }

    @Test
    fun firstConfiguredFallsBackWhenPreferredKeyIsBlank() {
        // Keyless preferred provider must never win when another is Set.
        val deepseekOnly = settings().copy(deepseekKey = "deepseek-key", metaKey = "")
        assertEquals("deepseek", firstConfiguredProvider("deepseek", deepseekOnly))
        assertEquals("deepseek", firstConfiguredProvider("meta", deepseekOnly))
    }

    @Test
    fun firstConfiguredReturnsPreferredWhenNothingIsSet() {
        val none = ProviderSettings()
        assertEquals("deepseek", firstConfiguredProvider("deepseek", none))
        assertEquals("meta", firstConfiguredProvider("meta", none))
        assertEquals("deepseek", firstConfiguredProvider("unknown", none))
        assertEquals("deepseek", firstConfiguredProvider("gemini", none))
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
        val first = failOn("deepseek", HttpFailure("rate_limited", 0, true))
        val second = successOn("meta")
        val outcome = planWithFallbackMessages(userTurn("check mail"), listOf(first, second), sleeper = {})
        assertEquals("meta", outcome.providerName)
        assertEquals(listOf("deepseek", "meta"), outcome.tried)
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
        val outcome = planWithFallbackMessages(userTurn("check mail"), listOf(noKey, successOn("deepseek")))
        assertEquals("deepseek", outcome.providerName)
        assertEquals(listOf("deepseek"), outcome.tried)
    }

    @Test
    fun authErrorStopsWithoutRetrying() {
        val badKey = failOn("deepseek", HttpFailure("auth", null, false))
        val next = successOn("meta")
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
                "deepseek",
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
        val first = failOn("deepseek", HttpFailure("rate_limited", 0, true))
        val second = successOn("meta")
        val outcome = planWithFallbackMessages(userTurn("check mail"), listOf(first, second), sleeper = {})
        assertEquals("Retried on meta (tried deepseek, meta)", retryDisclosure(outcome))
    }

    @Test
    fun noDisclosureWhenFirstProviderSucceeds() {
        val outcome = planWithFallbackMessages(userTurn("check mail"), listOf(successOn("deepseek")))
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
                "deepseek",
                "deepseek-key",
                object : StructuredLlmProvider {
                    override fun chatMessages(messages: List<ChatMessage>, tools: JSONArray): LlmResult {
                        seenMessages["deepseek"] = messages.toList()
                        throw LlmException(HttpFailure("rate_limited", 0, true), "HTTP 429")
                    }
                },
            )
        val backup =
            NamedProvider(
                "meta",
                "meta-key",
                object : StructuredLlmProvider {
                    override fun chatMessages(messages: List<ChatMessage>, tools: JSONArray): LlmResult {
                        seenMessages["meta"] = messages.toList()
                        return LlmResult(emptyList(), "done")
                    }
                },
            )
        val prompt = "Known: take nightly meds. Summarize inbox"
        val outcome = planWithFallbackMessages(userTurn(prompt), listOf(flaky, backup), sleeper = {})
        val deepseekUser = seenMessages["deepseek"]!!.single { it.role == "user" }.content
        val metaUser = seenMessages["meta"]!!.single { it.role == "user" }.content
        assertEquals(prompt, deepseekUser)
        assertEquals(prompt, metaUser)
        assertEquals(listOf("deepseek", "meta"), outcome.tried)
        val state = AgentUiState(providerUsed = outcome.providerName, providersTried = outcome.tried)
        val disclosure = retryDisclosure(outcome)
        assertEquals("Retried on meta (tried deepseek, meta)", disclosure)
        assertEquals(outcome.tried, state.providersTried)
        assertEquals("meta", state.providerUsed)
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
        val first = failOn("deepseek", HttpFailure("rate_limited", 5, true))
        val second = successOn("meta")
        val slept = mutableListOf<Long>()
        val outcome = planWithFallbackMessages(userTurn("check mail"), listOf(first, second), sleeper = { slept += it })
        assertEquals("meta", outcome.providerName)
        assertEquals(listOf(5_000L), slept)
    }

    @Test
    fun consecutiveRateLimitsBackOffExponentially() {
        val first = failOn("deepseek", HttpFailure("rate_limited", 0, true))
        val second = failOn("meta", HttpFailure("rate_limited", null, true))
        val third = successOn("deepseek")
        val slept = mutableListOf<Long>()
        val outcome = planWithFallbackMessages(userTurn("check mail"), listOf(first, second, third), sleeper = { slept += it })
        assertEquals("deepseek", outcome.providerName)
        assertEquals(listOf(1_000L, 2_000L), slept)
    }

    @Test
    fun noSleepWhenNothingLeftToTry() {
        val only = failOn("deepseek", HttpFailure("rate_limited", 5, true))
        val slept = mutableListOf<Long>()
        try {
            planWithFallbackMessages(userTurn("check mail"), listOf(only), sleeper = { slept += it })
            throw AssertionError("expected the rate limit to propagate")
        } catch (e: FallbackExhausted) {
            assertEquals(listOf("deepseek"), e.tried)
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
