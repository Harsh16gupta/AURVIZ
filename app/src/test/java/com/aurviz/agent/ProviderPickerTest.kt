package com.aurviz.agent

import org.junit.Assert.assertEquals
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
            deepseekKey = "deepseek-key",
            deepseekBaseUrl = "https://api.deepseek.com",
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
        val outcome = planWithFallback("check mail", listOf(first, second))
        assertEquals("groq", outcome.providerName)
        assertEquals(listOf("gemini", "groq"), outcome.tried)
        assertEquals("getImportantEmails", outcome.result.toolCalls[0].name)
    }

    @Test
    fun skipsNoKeyWithoutCalling() {
        val noKey =
            NamedProvider(
                "deepseek",
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
        var stopped = false
        try {
            planWithFallback("check mail", listOf(badKey, next))
        } catch (e: LlmException) {
            stopped = e.failure.kind == "auth"
        }
        assertEquals(true, stopped)
        assertEquals(1, (badKey.provider as FailingProvider).calls)
        assertEquals(0, (next.provider as StubProvider).calls)
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
