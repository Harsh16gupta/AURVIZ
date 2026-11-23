package com.aurviz.agent

import org.json.JSONArray

// T4: picker + auto-fallback. Pure decision logic, JVM-testable.
// The screen (picker UI) and real HTTP stay in thin wiring below.

// All keys/models come from settings (local.properties via BuildConfig).
// Blank key = that provider is skipped, never called.
data class ProviderSettings(
    val geminiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash",
    val groqKey: String = "",
    val groqModel: String = "llama-3.1-8b-instant",
    val deepseekKey: String = "",
    val deepseekModel: String = "deepseek-flash",
    val metaKey: String = "",
    val metaModel: String = "muse-spark-1.3",
)

// One provider's address + key + model. Name in, this out.
data class ProviderConfig(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

// One try in the fallback order. Blank apiKey = skip without calling.
data class NamedProvider(
    val name: String,
    val apiKey: String,
    val provider: LlmProvider,
)

// What the fallback reports: the answer + which provider ran + who was tried.
data class FallbackResult(
    val result: LlmResult,
    val providerName: String,
    val tried: List<String>,
)

// Ticket 10/24: exponential backoff between providers (ADR-0004, gaps
// §3H). The wait is never shorter than the retry-after the failed
// provider asked for; otherwise it doubles per consecutive rate limit
// (1s, 2s, 4s…) capped at 30s. attempt counts prior fallback retries.
const val PROVIDER_BACKOFF_BASE_MS = 1_000L
const val PROVIDER_BACKOFF_MAX_MS = 30_000L

fun backoffDelayMs(retryAfterSec: Int?, attempt: Int): Long {
    val exponential = PROVIDER_BACKOFF_BASE_MS * (1L shl attempt.coerceIn(0, 10))
    val retryAfterMs = (retryAfterSec ?: 0).coerceAtLeast(0) * 1_000L
    return maxOf(exponential, retryAfterMs).coerceAtMost(PROVIDER_BACKOFF_MAX_MS)
}

// Try each provider in order. Skips blank keys, moves on when the
// failure says fallback=true (429/error), stops when it says false (bad key).
// Between tries the loop sleeps the backoff above (injectable for tests);
// no sleep happens on a 401 stop or when nothing is left to try, and the
// replay is never silent — see retryDisclosure.
fun planWithFallback(
    command: String,
    candidates: List<NamedProvider>,
    sleeper: (Long) -> Unit = { Thread.sleep(it) },
): FallbackResult {
    val tried = mutableListOf<String>()
    var lastError: Exception? = null
    var retries = 0
    for ((index, candidate) in candidates.withIndex()) {
        if (candidate.apiKey.isBlank()) continue
        try {
            val result = candidate.provider.plan(command)
            tried += candidate.name
            return FallbackResult(result, candidate.name, tried.toList())
        } catch (e: LlmException) {
            if (!e.failure.fallback) throw e
            tried += candidate.name
            lastError = e
            val hasNext = candidates.drop(index + 1).any { it.apiKey.isNotBlank() }
            if (hasNext) sleeper(backoffDelayMs(e.failure.retryAfter, retries++))
        }
    }
    if (tried.isEmpty()) throw IllegalStateException("No provider with API key")
    throw lastError ?: IllegalStateException("No provider with API key")
}

// Structured fallback (ADR-0005): same try-order, disclosure, and backoff
// as planWithFallback, but the loop's messages ride intact. Providers
// implementing StructuredLlmProvider get the real roles; legacy plan-only
// doubles get the flattened string so scripted tests keep working.
// Ticket 75 (part of #71): no default Tool list — every caller injects
// the registry view (foreground) or the scheduled read-only view.
class FallbackExhausted(val tried: List<String>, cause: Exception) : Exception(cause.message, cause)

fun planWithFallbackMessages(
    messages: List<ChatMessage>,
    candidates: List<NamedProvider>,
    tools: JSONArray = JSONArray(),
    sleeper: (Long) -> Unit = { Thread.sleep(it) },
): FallbackResult {
    val tried = mutableListOf<String>()
    var lastError: Exception? = null
    var retries = 0
    for ((index, candidate) in candidates.withIndex()) {
        if (candidate.apiKey.isBlank()) continue
        try {
            val structured = candidate.provider as? StructuredLlmProvider
            val result =
                structured?.chatMessages(messages, tools)
                    ?: candidate.provider.plan(flattenChatMessages(messages))
            tried += candidate.name
            return FallbackResult(result, candidate.name, tried.toList())
        } catch (e: LlmException) {
            tried += candidate.name
            if (!e.failure.fallback) throw FallbackExhausted(tried.toList(), e)
            lastError = e
            val hasNext = candidates.drop(index + 1).any { it.apiKey.isNotBlank() }
            if (hasNext) sleeper(backoffDelayMs(e.failure.retryAfter, retries++))
        }
    }
    if (tried.isEmpty()) throw IllegalStateException("No provider with API key")
    throw FallbackExhausted(tried.toList(), lastError ?: IllegalStateException("No provider with API key"))
}

// Visible "retried on Y" disclosure (ADR-0004): null when the first provider
// answered, non-null naming every provider that saw the prompt otherwise.
// The caller copies `tried` into AgentUiState.providersTried so a
// cross-provider retry carrying private context is never silent.
fun retryDisclosure(result: FallbackResult): String? =
    if (result.tried.size > 1) {
        "Retried on ${result.providerName} (tried ${result.tried.joinToString(", ")})"
    } else {
        null
    }

// Ticket 36: user-entered keys from TokenVault override the BuildConfig keys
// per provider; a blank vault entry falls back to BuildConfig. On a release
// build (empty BuildConfig keys) with nothing entered, the result stays
// blank and planWithFallback refuses to call — never a baked-in key.
fun effectiveSettings(base: ProviderSettings, user: LlmKeys): ProviderSettings =
    base.copy(
        geminiKey = user.geminiKey.ifBlank { base.geminiKey },
        groqKey = user.groqKey.ifBlank { base.groqKey },
        deepseekKey = user.deepseekKey.ifBlank { base.deepseekKey },
        metaKey = user.metaKey.ifBlank { base.metaKey },
    )

// --- Thin wiring: settings come from BuildConfig (local.properties). ---

fun settingsFromBuildConfig(): ProviderSettings =
    ProviderSettings(
        geminiKey = BuildConfig.GEMINI_API_KEY,
        geminiModel = BuildConfig.GEMINI_MODEL,
        groqKey = BuildConfig.GROQ_API_KEY,
        groqModel = BuildConfig.GROQ_MODEL,
        deepseekKey = BuildConfig.DEEPSEEK_API_KEY,
        deepseekModel = BuildConfig.DEEPSEEK_MODEL,
        metaKey = BuildConfig.META_API_KEY,
        metaModel = BuildConfig.META_MODEL,
    )

fun providerFromConfig(config: ProviderConfig): LlmProvider =
    OpenAiChatProvider(config.baseUrl, config.apiKey, config.model)

// Real providers in try-order: picked one first, then the rest.
// No-key entries stay in the list — planWithFallback skips them.
// User keys from Settings (TokenVault) win over BuildConfig per provider.
fun defaultCandidates(preferred: String, userKeys: LlmKeys = LlmKeys()): List<NamedProvider> {
    val settings = effectiveSettings(settingsFromBuildConfig(), userKeys)
    val preferredName =
        preferred.trim().lowercase().takeIf { it in setOf("gemini", "groq", "deepseek", "meta") } ?: "gemini"
    val order = listOf(preferredName, "deepseek", "meta", "gemini", "groq").distinct()
    return order.map { name ->
        val config = resolveProvider(name, settings)
        NamedProvider(config.name, config.apiKey, providerFromConfig(config))
    }
}

// Pick by name. Unknown names fall back to Gemini so the app never crashes.
fun resolveProvider(name: String, settings: ProviderSettings): ProviderConfig =
    when (name.trim().lowercase()) {
        "groq" ->
            ProviderConfig(
                name = "groq",
                baseUrl = Providers.GROQ_BASE_URL,
                apiKey = settings.groqKey,
                model = settings.groqModel,
            )
        "deepseek" ->
            ProviderConfig(
                name = "deepseek",
                baseUrl = Providers.DEEPSEEK_BASE_URL,
                apiKey = settings.deepseekKey,
                model = settings.deepseekModel,
            )
        "meta" ->
            ProviderConfig(
                name = "meta",
                baseUrl = Providers.META_BASE_URL,
                apiKey = settings.metaKey,
                model = settings.metaModel,
            )
        "gemini" ->
            ProviderConfig(
                name = "gemini",
                baseUrl = Providers.GEMINI_BASE_URL,
                apiKey = settings.geminiKey,
                model = settings.geminiModel,
            )
        else ->
            ProviderConfig(
                name = "gemini",
                baseUrl = Providers.GEMINI_BASE_URL,
                apiKey = settings.geminiKey,
                model = settings.geminiModel,
            )
    }
