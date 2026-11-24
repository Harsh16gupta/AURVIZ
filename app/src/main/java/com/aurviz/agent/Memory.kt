package com.aurviz.agent

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

    // Offline FTS candidate lookup. The default is the same prefix-term
    // model over the listed rows (keeps InMemoryStore working with no
    // database); RoomMemoryStore overrides it with a SQLite FTS MATCH over
    // the memoriesFts index instead of scanning the table.
    fun searchFts(ftsQuery: String): List<Memory> {
        val words = ftsQuery.split(" ").map { it.removeSuffix("*") }.filter { it.isNotBlank() }.toSet()
        return list()
            .mapIndexed { index, memory ->
                val haystack = memory.text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
                val matched = words.count { term -> haystack.any { it.startsWith(term) } }
                Triple(memory, matched, index)
            }
            .filter { (_, matched, _) -> matched == words.size && words.isNotEmpty() }
            .map { (memory, _, _) -> memory }
    }
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
// Ticket #92: dedup is case- and whitespace-insensitive — "  My Boss Is
// Priya " matches "my boss is priya" — so repeats never twin.
fun rememberFact(
    store: MemoryStore,
    text: String,
    source: String,
    category: String = "general",
    importance: Int = 0,
    createdAt: Long = System.currentTimeMillis(),
): Memory {
    val key = text.trim().lowercase()
    store.list().find { it.text.trim().lowercase() == key && it.source == source }?.let { return it }
    return store.save(text, source, category = category, importance = importance, createdAt = createdAt)
}

// Show what "forget boss" WOULD delete, without deleting anything.
// The screen shows this list first; only confirmed ids get deleted.
fun forgetPreview(store: MemoryStore, query: String): List<Memory> =
    recallFor(store, query)

// Ticket #89: lossless Undo. Re-saves the FULL row (text, source,
// category, importance, createdAt, lastUsed, useCount) so a restored
// Memory keeps its ranking state instead of resetting to defaults.
fun restoreMemoryRow(store: MemoryStore, memory: Memory): Memory =
    store.save(
        memory.text,
        memory.source,
        category = memory.category,
        importance = memory.importance,
        createdAt = memory.createdAt,
        lastUsed = memory.lastUsed,
        useCount = memory.useCount,
    )

// Ticket #89: title-case facet labels ("you told me" -> "You Told Me").
fun titleCaseFacet(raw: String): String =
    raw.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        .joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }

// Ticket #89: bold search-match ranges (case-insensitive, non-overlapping).
// Pure index math so the screen can render an AnnotatedString from it.
fun matchRanges(text: String, query: String): List<IntRange> {
    val q = query.trim()
    if (q.isEmpty() || text.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var from = 0
    while (true) {
        val idx = text.indexOf(q, from, ignoreCase = true)
        if (idx < 0) break
        ranges += idx until idx + q.length
        from = idx + q.length
    }
    return ranges
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
    rankMemories(store, query, online = online)

// Shared boost ordering: most matched terms first, then higher
// importance, then more recent activity, then later insertion.
private fun boostComparator(): Comparator<Triple<Memory, Int, Int>> =
    compareByDescending<Triple<Memory, Int, Int>> { it.second }
        .thenByDescending { it.first.importance }
        .thenByDescending { maxOf(it.first.lastUsed, it.first.createdAt) }
        .thenByDescending { it.third }

private fun memoryTokens(text: String): Set<String> =
    text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()

private fun rankMemories(store: MemoryStore, query: String, online: Boolean): List<Memory> {
    val words = memoryTokens(query)
    if (words.isEmpty()) return emptyList()
    if (!online) return ftsRecall(store, words)
    val memories = store.list()
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

private fun ftsRecall(store: MemoryStore, words: Set<String>): List<Memory> {
    if (words.isEmpty()) return emptyList()
    // Real FTS MATCH candidates from the store's index (Room) or the same
    // prefix-term model over listed rows (in-memory default) — then the
    // shared boost ranking below.
    val candidates =
        try {
            store.searchFts(words.joinToString(" ") { "$it*" })
        } catch (_: Exception) {
            emptyList()
        }
    val candidateIds = candidates.map { it.id }.toSet()
    val scored =
        store.list().mapIndexed { index, memory ->
            val haystack = memoryTokens(memory.text)
            val matched = words.count { term -> haystack.any { it.startsWith(term) } }
            Triple(memory, matched, index)
        }
    return scored
        .filter { (memory, matched, _) ->
            matched == words.size && (candidateIds.isEmpty() || memory.id in candidateIds)
        }
        .sortedWith(boostComparator())
        .map { (memory, _, _) -> memory }
}

// Ticket 16/29: memories travel in a delimited system section, never
// concatenated into the user message (ADR-0005). The loop renders this
// section into the system prompt; the model sees each fact with its id
// and source so personalization stays distinguishable from instructions.
const val MEMORY_SECTION_START = "[memories]"
const val MEMORY_SECTION_END = "[/memories]"

fun renderMemorySection(memories: List<Memory>): String {
    val body =
        if (memories.isEmpty()) {
            "(none)"
        } else {
            memories.joinToString("\n") { "• ${it.id} [${it.source}]: ${it.text}" }
        }
    return "$MEMORY_SECTION_START\n$body\n$MEMORY_SECTION_END"
}

// Ticket 05/24: memory retrieval. Room-only via MemoryStore (ADR-0001):
// the registry injects RoomMemoryStore on the phone and InMemoryStore
// in tests. Keyword plus recency/importance boosts with an offline FTS
// fallback (ticket #31); embeddings belong to ticket #32.
// Ticket 75 (part of #71): the LLM-visible memory schemas live in the
// Memory adapter (MemoryTools.kt) — no schema builders here.
