package com.aurviz.agent

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
    val deepseekBaseUrl: String = "https://api.deepseek.com",
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

// --- Thin wiring: settings come from BuildConfig (local.properties). ---

fun settingsFromBuildConfig(): ProviderSettings =
    ProviderSettings(
        geminiKey = BuildConfig.GEMINI_API_KEY,
        geminiModel = BuildConfig.GEMINI_MODEL,
        groqKey = BuildConfig.GROQ_API_KEY,
        groqModel = BuildConfig.GROQ_MODEL,
        deepseekKey = BuildConfig.DEEPSEEK_API_KEY,
        deepseekBaseUrl = BuildConfig.DEEPSEEK_BASE_URL,
    )

fun providerFromConfig(config: ProviderConfig): LlmProvider =
    OpenAiChatProvider(config.baseUrl, config.apiKey, config.model)

// Real providers in try-order: picked one first, then the rest.
// No-key entries stay in the list — planWithFallback skips them.
fun defaultCandidates(preferred: String): List<NamedProvider> {
    val settings = settingsFromBuildConfig()
    val order = listOf(preferred.trim().lowercase(), "gemini", "groq", "deepseek").distinct()
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
                baseUrl = settings.deepseekBaseUrl,
                apiKey = settings.deepseekKey,
                model = "deepseek-chat",
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
