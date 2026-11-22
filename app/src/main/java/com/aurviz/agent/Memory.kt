package com.aurviz.agent

import org.json.JSONArray
import org.json.JSONObject

// Session 1: memory = what the app remembers across restarts.
// Pure decision logic, JVM-testable. The on-phone database (Room)
// and the cloud (Supermemory) plug in through MemoryStore later.

// One remembered thing + where it came from ("you told me" vs "auto-saved").
data class Memory(val id: String, val text: String, val source: String)

// The plug shape. Tests use InMemoryStore; Room implements this on the phone.
// The store owns ids, so callers never invent them.
interface MemoryStore {
    fun save(text: String, source: String): Memory
    fun list(): List<Memory>
    fun delete(id: String)
}

class InMemoryStore : MemoryStore {
    private val items = mutableListOf<Memory>()
    private var nextId = 0
    override fun save(text: String, source: String): Memory {
        val memory = Memory("mem_${++nextId}", text, source)
        items += memory
        return memory
    }
    override fun list(): List<Memory> = items.toList()
    override fun delete(id: String) {
        items.removeAll { it.id == id }
    }
}

// Store a fact exactly as given. An identical fact (same words, same
// source) is already there, so return it instead of saving a twin.
fun rememberFact(store: MemoryStore, text: String, source: String): Memory {
    store.list().find { it.text == text && it.source == source }?.let { return it }
    return store.save(text, source)
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
// first; ties break toward the most recently saved. Recency is the
// position in list() order, which is insertion order for both
// InMemoryStore and RoomMemoryStore's pid-ASC query — no timestamp
// columns needed here (those belong to ticket #31).
fun recallFor(store: MemoryStore, query: String): List<Memory> {
    val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
    if (words.isEmpty()) return emptyList()
    return store.list()
        .mapIndexed { index, memory ->
            val hit = memory.text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
            Triple(memory, hit.intersect(words).size, index)
        }
        .filter { (_, score, _) -> score > 0 }
        .sortedWith(compareByDescending<Triple<Memory, Int, Int>> { it.second }.thenByDescending { it.third })
        .map { (memory, _, _) -> memory }
}

// Ticket 05/24: LLM-visible memory tool schemas. Room-only via
// MemoryStore (ADR-0001): the registry injects RoomMemoryStore on the
// phone and InMemoryStore in tests. Keyword plus recency ranking only;
// FTS and embeddings belong to tickets #31/#32.
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
