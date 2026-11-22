package com.aurviz.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// JUnit port of spike/test_t1_llm_provider.py (same seams, same literals).
class LlmProviderTest {

    @Test
    fun buildsValidToolsRequest() {
        val body =
            buildToolsRequestBody(
                "gemini-2.5-flash",
                "Check important emails",
                JSONArray().put(demoToolSchema()),
            )
        assertEquals("gemini-2.5-flash", body.getString("model"))
        assertEquals("auto", body.getString("tool_choice"))
        assertEquals(
            "getImportantEmails",
            body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"),
        )
        val messages = body.getJSONArray("messages")
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("Check important emails", messages.getJSONObject(0).getString("content"))
    }

    @Test
    fun parsesSingleToolCall() {
        val response =
            JSONObject(
                """{"choices": [{"message": {"role": "assistant", "content": null,
                "tool_calls": [{"id": "call_abc123", "type": "function",
                "function": {"name": "getImportantEmails", "arguments": "{\"max_results\": 5}"}}]}}]}""",
            )
        val result = parseToolCalls(response)
        assertEquals(1, result.toolCalls.size)
        assertEquals("call_abc123", result.toolCalls[0].id)
        assertEquals("getImportantEmails", result.toolCalls[0].name)
        assertEquals(5, result.toolCalls[0].arguments["max_results"])
    }

    @Test
    fun textReplyHasNoToolCalls() {
        val response =
            JSONObject(
                """{"choices": [{"message": {"role": "assistant",
                "content": "Which inbox should I check?"}}]}""",
            )
        val result = parseToolCalls(response)
        assertTrue(result.toolCalls.isEmpty())
        assertEquals("Which inbox should I check?", result.content)
    }

    @Test
    fun rateLimitedSurfacesRetryAfterForFallback() {
        val failure = mapHttpError(429, mapOf("Retry-After" to "20"), "rate limit")
        assertEquals("rate_limited", failure.kind)
        assertEquals(20, failure.retryAfter)
        assertTrue(failure.fallback)
    }

    @Test
    fun unauthorizedIsAuthErrorWithoutRetry() {
        val failure = mapHttpError(401, emptyMap(), "invalid key")
        assertEquals("auth", failure.kind)
        assertNull(failure.retryAfter)
    }

    @Test
    fun fakeReturnsCannedToolCall() {
        val result = FakeLlmProvider().plan("Check important emails")
        assertEquals(1, result.toolCalls.size)
        assertEquals("getImportantEmails", result.toolCalls[0].name)
        assertEquals(5, result.toolCalls[0].arguments["max_results"])
    }

    @Test
    fun scheduleToolSchemaNamesTaskAndWhen() {
        val fn = scheduleToolSchema().getJSONObject("function")
        assertEquals("createSchedule", fn.getString("name"))
        val required = fn.getJSONObject("parameters").getJSONArray("required")
        val names = (0 until required.length()).map { required.getString(it) }.toSet()
        assertEquals(setOf("task", "when"), names)
    }

    @Test
    fun defaultLlmHttpClientHasConnectAndReadTimeouts() {
        // ADR-0004: no bare OkHttpClient without timeouts on the LLM path.
        val client = llmHttpClient()
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(60_000, client.readTimeoutMillis)
    }
}
