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
        val tools =
            JSONArray().put(
                toolSchema(
                    "search_emails",
                    "Search the Gmail mailbox.",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject().put("query", JSONObject().put("type", "string")))
                        .put("required", JSONArray().put("query")),
                ),
            )
        val body =
            buildToolsRequestBody(
                "deepseek-flash",
                "Check important emails",
                tools,
            )
        assertEquals("deepseek-flash", body.getString("model"))
        assertEquals("auto", body.getString("tool_choice"))
        assertEquals(
            "search_emails",
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
                "function": {"name": "search_emails", "arguments": "{\"query\": \"is:important is:unread\", \"max\": 5}"}}]}}]}""",
            )
        val result = parseToolCalls(response)
        assertEquals(1, result.toolCalls.size)
        assertEquals("call_abc123", result.toolCalls[0].id)
        assertEquals("search_emails", result.toolCalls[0].name)
        assertEquals("is:important is:unread", result.toolCalls[0].arguments["query"])
        assertEquals(5, result.toolCalls[0].arguments["max"])
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
        val result = FakeLlmProvider().chat(listOf(ChatMessage(role = "user", content = "Check important emails")))
        assertEquals(1, result.toolCalls.size)
        assertEquals("search_emails", result.toolCalls[0].name)
        assertEquals("is:important is:unread", result.toolCalls[0].arguments["query"])
        assertEquals(5, result.toolCalls[0].arguments["max"])
    }

    // Ticket 75 (part of #71): the schema bodies live in their owning
    // adapters and the kernel's toolSchemas() view is the single
    // advertised list — no hardcoded builders. These pin the registry
    // view's schedule entries with independent literals.
    @Test
    fun registryViewNamesCreateTaskAndWhen() {
        val parameters = defaultToolRegistry().find("create_schedule")!!.parameters
        val required = parameters.getJSONArray("required")
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

    @Test
    fun registryViewListSchedulesTakesNoRequiredArgs() {
        val parameters = defaultToolRegistry().find("list_schedules")!!.parameters
        val required = parameters.optJSONArray("required")
        assertTrue(required == null || required.length() == 0)
    }

    @Test
    fun registryViewCancelScheduleRequiresScheduleId() {
        val parameters = defaultToolRegistry().find("cancel_schedule")!!.parameters
        val required = parameters.getJSONArray("required")
        val names = (0 until required.length()).map { required.getString(it) }.toSet()
        assertEquals(setOf("schedule_id"), names)
    }

    @Test
    fun registryViewExposesAllAdapterTools() {
        val schemas = defaultToolRegistry().toolSchemas()
        val names = (0 until schemas.length())
            .map { schemas.getJSONObject(it).getJSONObject("function").getString("name") }
            .toSet()
        assertTrue(names.contains("search_emails"))
        assertTrue(names.contains("create_schedule"))
        assertTrue(names.contains("list_schedules"))
        assertTrue(names.contains("cancel_schedule"))
        assertTrue(names.contains("search_emails"))
        assertTrue(names.contains("save_memory"))
        assertTrue(names.contains("get_page_text"))
    }

    @Test
    fun structuredFallbackDefaultsToNoHardcodedTools() {
        // Ticket 75: no caller may rely on a hardcoded default Tool list —
        // every model-request site injects the registry view explicitly.
        val seen = mutableListOf<JSONArray>()
        val capturing =
            object : StructuredLlmProvider {
                override fun chatMessages(messages: List<ChatMessage>, tools: JSONArray): LlmResult {
                    seen += tools
                    return LlmResult(emptyList(), "ok")
                }
            }
        planWithFallbackMessages(
            listOf(ChatMessage(role = "user", content = "hi")),
            listOf(NamedProvider("deepseek", "k", capturing)),
            sleeper = {},
        )
        assertEquals(1, seen.size)
        assertEquals(0, seen[0].length())
    }
}
