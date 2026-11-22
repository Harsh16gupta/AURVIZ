package com.aurviz.agent

import org.json.JSONArray
import org.json.JSONObject

// Session 1: memory = what the app remembers across restarts.
// Pure decision logic, JVM-testable. The on-phone database (Room)
// and the cloud (Supermemory) plug in through MemoryStore later.

// One remembered thing + where it came from ("you told me" vs "auto-saved").
// Ticket #31: category groups facts ("work"), importance (0-9) marks what
// must win ranking ties, createdAt/lastUsed drive the recency boost, and
// useCount counts recalls. Defaults keep older call sites compiling.
data class Memory(
    val id: String,
    val text: String,
    val source: String,
    val category: String = "general",
    val importance: Int = 0,
    val createdAt: Long = 0L,
    val lastUsed: Long = 0L,
    val useCount: Int = 0,
)

// The plug shape. Tests use InMemoryStore; Room implements this on the phone.
// The store owns ids, so callers never invent them.
interface MemoryStore {
    fun save(
        text: String,
        source: String,
        category: String = "general",
        importance: Int = 0,
        createdAt: Long = System.currentTimeMillis(),
        lastUsed: Long = 0L,
        useCount: Int = 0,
    ): Memory
    fun list(): List<Memory>
    fun delete(id: String)

    // Ticket #31: record a recall so recency (lastUsed) and useCount learn.
    fun markUsed(id: String, nowMs: Long = System.currentTimeMillis())
}

class InMemoryStore : MemoryStore {
    private val items = mutableListOf<Memory>()
    private var nextId = 0
    override fun save(
        text: String,
        source: String,
        category: String,
        importance: Int,
        createdAt: Long,
        lastUsed: Long,
        useCount: Int,
    ): Memory {
        val memory = Memory("mem_${++nextId}", text, source, category, importance, createdAt, lastUsed, useCount)
        items += memory
        return memory
    }
    override fun list(): List<Memory> = items.toList()
    override fun delete(id: String) {
        items.removeAll { it.id == id }
    }
    override fun markUsed(id: String, nowMs: Long) {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = items[index]
        items[index] = current.copy(lastUsed = nowMs, useCount = current.useCount + 1)
    }
}

// Store a fact exactly as given. An identical fact (same words, same
// source) is already there, so return it instead of saving a twin.
fun rememberFact(
    store: MemoryStore,
    text: String,
    source: String,
    category: String = "general",
    importance: Int = 0,
    createdAt: Long = System.currentTimeMillis(),
): Memory {
    store.list().find { it.text == text && it.source == source }?.let { return it }
    return store.save(text, source, category = category, importance = importance, createdAt = createdAt)
}

// Show what "forget boss" WOULD delete, without deleting anything.
// The screen shows this list first; only confirmed ids get deleted.
fun forgetPreview(store: MemoryStore, query: String): List<Memory> =
    recallFor(store, query)

// Memories written while offline wait here.
// replay() uploads them in order and skips ones already stored.
class Outbox {
    private val pending = mutableListOf<Pair<String, String>>()

    fun queue(text: String, source: String) {
        pending += text to source
    }

    fun replay(store: MemoryStore): Int {
        val already = store.list().map { it.text to it.source }.toSet()
        var uploaded = 0
        for ((text, source) in pending) {
            if ((text to source) !in already) {
                store.save(text, source)
                uploaded++
            }
        }
        pending.clear()
        return uploaded
    }
}
// Find memories sharing words with the question: most shared words
// first (ticket #18 keyword ranking, unchanged); ties break toward higher
// importance, then toward more recent use (lastUsed, else createdAt),
// then toward the most recently saved. Recency/importance are boosts
// inside an overlap tier — overlap still outranks them, so #18 behavior
// is extended, never regressed. Room-only (ADR-0001): no network, no
// embeddings (those are ticket #32); set online=false to force the
// offline FTS fallback path explicitly.
fun recallFor(store: MemoryStore, query: String, online: Boolean = true): List<Memory> =
    rankMemories(store.list(), query, online = online)

// Shared boost ordering: most matched terms first, then higher
// importance, then more recent activity, then later insertion.
private fun boostComparator(): Comparator<Triple<Memory, Int, Int>> =
    compareByDescending<Triple<Memory, Int, Int>> { it.second }
        .thenByDescending { it.first.importance }
        .thenByDescending { maxOf(it.first.lastUsed, it.first.createdAt) }
        .thenByDescending { it.third }

private fun memoryTokens(text: String): Set<String> =
    text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()

private fun rankMemories(memories: List<Memory>, query: String, online: Boolean): List<Memory> {
    val words = memoryTokens(query)
    if (words.isEmpty()) return emptyList()
    if (!online) return ftsRecall(memories, words)
    return memories
        .mapIndexed { index, memory ->
            Triple(memory, memoryTokens(memory.text).intersect(words).size, index)
        }
        .filter { (_, score, _) -> score > 0 }
        .sortedWith(boostComparator())
        .map { (memory, _, _) -> memory }
}

// Offline FTS fallback: same term model as a SQLite FTS MATCH 't1* t2*'
// query (tokenized AND of prefix terms), ranked with the same boosts.
// Pure on-device logic, so recall works with no connectivity.
fun toFtsQuery(query: String): String =
    memoryTokens(query).joinToString(" ") { "$it*" }

private fun ftsRecall(memories: List<Memory>, words: Set<String>): List<Memory> =
    memories
        .mapIndexed { index, memory ->
            val haystack = memoryTokens(memory.text)
            val matched = words.count { term -> haystack.any { it.startsWith(term) } }
            Triple(memory, matched, index)
        }
        .filter { (_, matched, _) -> matched == words.size }
        .sortedWith(boostComparator())
        .map { (memory, _, _) -> memory }

// Ticket 05/24: LLM-visible memory tool schemas. Room-only via
// MemoryStore (ADR-0001): the registry injects RoomMemoryStore on the
// phone and InMemoryStore in tests. Keyword plus recency/importance
// boosts with an offline FTS fallback (ticket #31); embeddings belong
// to ticket #32.
fun saveMemoryToolSchema(): JSONObject =
    JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", "save_memory")
                .put("description", "Save a fact about the user for future commands, e.g. their boss's name.")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("text", JSONObject().put("type", "string").put("description", "The fact to remember, e.g. my boss is Priya"))
                                .put("source", JSONObject().put("type", "string").put("description", "Where the fact came from, e.g. you told me")),
                        )
                        .put("required", JSONArray().put("text")),
                ),
        )

fun searchMemoryToolSchema(): JSONObject =
    JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", "search_memory")
                .put("description", "Search remembered facts sharing words with the query, best match first.")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("query", JSONObject().put("type", "string").put("description", "What to look for, e.g. who is my boss?"))
                                .put("max_results", JSONObject().put("type", "integer")),
                        )
                        .put("required", JSONArray().put("query")),
                ),
        )
