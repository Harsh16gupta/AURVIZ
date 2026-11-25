package com.aira.agent

import org.json.JSONArray

// Unified pinned-fallback chat seam (wrappers-only unification).
// Foreground (AgentViewModel) and scheduled (ScheduleWork) runs inject
// different registry views via `tools`, but share pinning + disclosure:
// the first provider that answers handles every later turn, so private
// Memory/mail/tool results never replay to a second host mid-run.
// Tried unions every provider that saw the prompt, even on failure.
interface TriedChatLlm : ChatLlmProvider {
    val tried: List<String>
    val lastProvider: String?
}

class PinnedFallbackChatLlm(
    private val candidates: List<NamedProvider>,
    private val tools: JSONArray = JSONArray(),
) : TriedChatLlm {
    private val seen = mutableListOf<String>()
    override val tried: List<String> get() = seen.toList()
    private var lastName: String? = null
    override val lastProvider: String? get() = lastName
    private var pinned: String? = null

    override fun chat(messages: List<ChatMessage>): LlmResult {
        val effective =
            pinned?.let { name -> candidates.filter { it.name == name } } ?: candidates
        try {
            val fb = planWithFallbackMessages(messages, effective.ifEmpty { candidates }, tools)
            for (name in fb.tried) if (name !in seen) seen += name
            if (pinned == null) pinned = fb.providerName
            lastName = fb.providerName
            return fb.result
        } catch (e: FallbackExhausted) {
            for (name in e.tried) if (name !in seen) seen += name
            throw e.cause as? Exception ?: RuntimeException(e)
        }
    }
}
