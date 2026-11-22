package com.aurviz.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// T1: one OpenAI-shape HTTP client for all providers (ADR-0004).
// Only base URL + key + model change per provider; the tools schema,
// tool_calls parsing, and 429/auth mapping are shared.

data class ToolCall(val id: String?, val name: String, val arguments: Map<String, Any?>)

data class LlmResult(val toolCalls: List<ToolCall>, val content: String?)

data class HttpFailure(val kind: String, val retryAfter: Int?, val fallback: Boolean)

class LlmException(val failure: HttpFailure, message: String) : Exception(message)

interface LlmProvider {
    fun plan(userText: String): LlmResult
}

/** Demo Tool: read-only, safe for the spike. Real Tools land in T3+. */
fun demoToolSchema(): JSONObject =
    JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", "getImportantEmails")
                .put("description", "Fetch unread important emails.")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("max_results", JSONObject().put("type", "integer")),
                        )
                        .put("required", JSONArray().put("max_results")),
                ),
        )

/** Scheduling Tool: the LLM proposes WHAT + WHEN in words; the app parses
 *  the time with its tested parser and shows a preview for Confirm.
 *  The LLM never sets alarms itself — same Confirm gate as email send. */
fun scheduleToolSchema(): JSONObject =
    JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", "createSchedule")
                .put("description", "Save a recurring routine that runs later. Split the request into WHAT to do (task) and WHEN it runs (when, in the user's own words).")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("task", JSONObject().put("type", "string").put("description", "What the routine does, e.g. read emails"))
                                .put("when", JSONObject().put("type", "string").put("description", "When it runs, in the user's own words, e.g. at 2:38 a.m.")),
                        )
                        .put("required", JSONArray().put("task").put("when")),
                ),
        )

fun allToolSchemas(): JSONArray = JSONArray().put(demoToolSchema()).put(scheduleToolSchema())

fun buildToolsRequestBody(model: String, userText: String, tools: JSONArray): JSONObject =
    JSONObject()
        .put("model", model)
        .put(
            "messages",
            JSONArray().put(JSONObject().put("role", "user").put("content", userText)),
        )
        .put("tools", tools)
        .put("tool_choice", "auto")

fun parseToolCalls(response: JSONObject): LlmResult {
    val message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
    val calls = mutableListOf<ToolCall>()
    val rawCalls = message.optJSONArray("tool_calls")
    if (rawCalls != null) {
        for (i in 0 until rawCalls.length()) {
            val raw = rawCalls.getJSONObject(i)
            val fn = raw.getJSONObject("function")
            val args = mutableMapOf<String, Any?>()
            val argsObj = JSONObject(fn.optString("arguments", "{}"))
            for (key in argsObj.keys()) {
                args[key] = if (argsObj.isNull(key)) null else argsObj.get(key)
            }
            calls += ToolCall(if (raw.isNull("id")) null else raw.getString("id"), fn.getString("name"), args)
        }
    }
    val content = if (message.isNull("content")) null else message.getString("content")
    return LlmResult(calls, content)
}

/** Maps HTTP failures to planner signals. 429 carries retry-after (Groq sends
 *  retry-after + x-ratelimit-*); 401 means bad key (no retry); other errors
 *  fall back to the next provider. Header lookup is case-insensitive. */
fun mapHttpError(status: Int, headers: Map<String, String>, body: String): HttpFailure {
    val lowered = headers.mapKeys { it.key.lowercase() }
    if (status == 429) {
        val retryAfter = lowered["retry-after"]?.trim()?.toIntOrNull() ?: 0
        return HttpFailure("rate_limited", retryAfter, true)
    }
    if (status == 401) {
        return HttpFailure("auth", null, false)
    }
    return HttpFailure("error", null, true)
}

/** OpenAI-shape chat/completions client. Covers Gemini compat, Groq, DeepSeek. */
class OpenAiChatProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val client: OkHttpClient = OkHttpClient(),
) : LlmProvider {
    override fun plan(userText: String): LlmResult {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val json =
            buildToolsRequestBody(model, userText, allToolSchemas()).toString()
        val request =
            Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val singleHeaders = mutableMapOf<String, String>()
                for (name in response.headers.names()) {
                    singleHeaders.putIfAbsent(name, response.headers[name].orEmpty())
                }
                throw LlmException(mapHttpError(response.code, singleHeaders, text), "HTTP ${response.code}")
            }
            return parseToolCalls(JSONObject(text))
        }
    }
}

/** Provider endpoints. Keys/models come from BuildConfig (local.properties). */
object Providers {
    const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
    const val GROQ_BASE_URL = "https://api.groq.com/openai/v1"

    fun default(): LlmProvider =
        when (BuildConfig.LLM_DEFAULT_PROVIDER) {
            "groq" -> OpenAiChatProvider(GROQ_BASE_URL, BuildConfig.GROQ_API_KEY, BuildConfig.GROQ_MODEL)
            "deepseek" ->
                OpenAiChatProvider(
                    BuildConfig.DEEPSEEK_BASE_URL,
                    BuildConfig.DEEPSEEK_API_KEY,
                    "deepseek-chat",
                )
            else -> OpenAiChatProvider(GEMINI_BASE_URL, BuildConfig.GEMINI_API_KEY, BuildConfig.GEMINI_MODEL)
        }
}

/** Canned provider for planner tests: no network, fixed tool call. */
class FakeLlmProvider : LlmProvider {
    override fun plan(userText: String): LlmResult =
        LlmResult(
            toolCalls = listOf(ToolCall("call_fake001", "getImportantEmails", mapOf("max_results" to 5))),
            content = null,
        )
}
