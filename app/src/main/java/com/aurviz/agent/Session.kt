package com.aurviz.agent

// Issue #51: session-scoped conversational context (ChatGPT-style threads).
//
// Memory (Room) is long-term and goes to every Session. The transcript
// below is short-term: turns inside one Session share context so
// follow-ups ("send it to myself") resolve without re-asking. A new
// Session starts with an empty transcript. Scheduled/headless runs never
// read the transcript — they stay stateless per AGENTS.md.
//
// Compaction priority (industry order, no LLM summarizer in V1):
//  1. system prompt + identity — never dropped (built per run)
//  2. first user turn of the Session — never dropped (anchors "it")
//  3. most recent turns — kept full
//  4. middle turns — first to drop (tool bodies already dropped at append)

// How many history messages ride with the next run (user+assistant
// pairs). 20 = last ~10 back-and-forths plus the anchored first turn.
const val MAX_SESSION_HISTORY_MESSAGES = 20

// One Session's short-term transcript. Stored in-memory in the ViewModel;
// never persisted to Room in V1.
fun pruneSessionHistory(
    history: List<ChatMessage>,
    maxMessages: Int = MAX_SESSION_HISTORY_MESSAGES,
): List<ChatMessage> {
    if (history.size <= maxMessages) return history.toList()
    if (history.isEmpty()) return emptyList()
    // Keep the first user turn (the anchor) plus the most recent tail.
    val first = history.first()
    val tail = history.takeLast(maxMessages - 1)
    // Avoid doubling the anchor when it is already inside the tail.
    if (tail.any { it === first || it == first }) return tail
    return listOf(first) + tail
}

// Append one completed turn in compact form: user text + plain answer.
// Raw tool JSON (mail bodies, page text) stays inside the finished run's
// messages only — it never enters the long-term transcript. This is the
// V1 compaction: old tool bodies are dropped at write time, not read time.
fun appendSessionTurn(
    transcript: MutableList<ChatMessage>,
    userText: String,
    result: AgentLoopResult,
) {
    transcript += ChatMessage(role = "user", content = userText)
    val answer =
        when (result) {
            is AgentLoopResult.Answered -> result.answer
            is AgentLoopResult.Failed -> "Failed: ${result.error}"
        }
    transcript += ChatMessage(role = "assistant", content = answer)
}

// Opening messages for the next run: system framing first, then the
// pruned Session history, then the fresh user command. The loop appends
// its own assistant/tool turns after this.
fun buildSessionMessages(
    systemPrompt: String,
    history: List<ChatMessage>,
    newUserText: String,
): List<ChatMessage> {
    val out = mutableListOf(ChatMessage(role = "system", content = systemPrompt))
    out += pruneSessionHistory(history)
    out += ChatMessage(role = "user", content = newUserText)
    return out
}
