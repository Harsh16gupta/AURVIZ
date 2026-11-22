package com.aurviz.agent

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
// Find memories sharing words with the question, best match first.
fun recallFor(store: MemoryStore, query: String): List<Memory> {
    val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
    if (words.isEmpty()) return emptyList()
    return store.list()
        .map { memory ->
            val hit = memory.text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
            memory to hit.intersect(words).size
        }
        .filter { (_, score) -> score > 0 }
        .sortedByDescending { (_, score) -> score }
        .map { (memory, _) -> memory }
}
