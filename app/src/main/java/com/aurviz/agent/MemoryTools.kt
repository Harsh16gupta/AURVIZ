package com.aurviz.agent

import org.json.JSONArray
import org.json.JSONObject

// Ticket 74 (part of #71): Memory Tool adapter. Owns the two Memory Tool
// declarations — on-device save and ranked search — and explicitly
// registers them into the small validate-and-gate kernel (ToolRegistry).
// No behavior change: both stay LOW risk ungated, a missing store still
// returns a non-retryable Failure, and the kernel still refuses gated
// Tools on the ungated path (neither Memory Tool is gated).
// Ticket 75 (part of #71): the schema bodies live here in the owning
// adapter — no shared hardcoded schema list; the kernel's toolSchemas()
// view is the single advertised Tool list.
fun saveMemoryTool(memoryStore: MemoryStore?, reconciler: MemoryReconciler? = null): ToolDefinition {
    return ToolDefinition(
        name = "save_memory",
        description = "Save a fact about the user for future commands, e.g. their boss's name.",
        parameters =
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("text", JSONObject().put("type", "string").put("description", "The fact to remember, e.g. my boss is Priya"))
                        .put("source", JSONObject().put("type", "string").put("description", "Where the fact came from, e.g. you told me")),
                )
                .put("required", JSONArray().put("text")),
        // On-device write, reversible via delete; not a sensitive
        // call, so no per-call Confirmation (ADR-0003 gates sends).
        riskLevel = RiskLevel.LOW,
        requiresConfirmation = false,
        resultType = "memory",
        execute = { args ->
            val store = memoryStore
                ?: return@ToolDefinition ToolResult.Failure("memory store is not available", retryable = false)
            val text = args["text"] as String
            val source = (args["source"] as String?)?.takeIf { it.isNotBlank() } ?: "you told me"
            val saved =
                if (reconciler != null) {
                    reconciler.remember(store, text, source).memory
                } else {
                    rememberFact(store, text, source)
                }
            ToolResult.Success(
                mapOf(
                    "id" to saved.id,
                    "text" to saved.text,
                    "source" to saved.source,
                ),
            )
        },
    )
}

fun searchMemoryTool(memoryStore: MemoryStore?): ToolDefinition {
    return ToolDefinition(
        name = "search_memory",
        description = "Search remembered facts sharing words with the query, best match first.",
        parameters =
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("query", JSONObject().put("type", "string").put("description", "What to look for, e.g. who is my boss?"))
                        .put("max_results", JSONObject().put("type", "integer")),
                )
                .put("required", JSONArray().put("query")),
        // Read-only recall over on-device Room.
        riskLevel = RiskLevel.LOW,
        requiresConfirmation = false,
        resultType = "memories",
        execute = { args ->
            val store = memoryStore
                ?: return@ToolDefinition ToolResult.Failure("memory store is not available", retryable = false)
            val query = args["query"] as String
            val max = (args["max_results"] as Number?)?.toInt()?.coerceAtLeast(0) ?: 5
            val hits = recallFor(store, query).take(max)
            // Ticket #92: every recall teaches ranking — stamp useCount +
            // lastUsed on each hit so repeated recalls outrank stale rows.
            hits.forEach { store.markUsed(it.id) }
            ToolResult.Success(
                hits.map { memory ->
                    mapOf(
                        "id" to memory.id,
                        "text" to memory.text,
                        "source" to memory.source,
                    )
                },
            )
        },
    )
}

/** The Memory adapter: both Memory Tool declarations in registration
 *  order (save, search). The default registry builder registers every
 *  entry into the kernel. */
fun memoryTools(
    memoryStore: MemoryStore?,
    reconciler: MemoryReconciler? = null,
): List<ToolDefinition> =
    listOf(
        saveMemoryTool(memoryStore, reconciler),
        searchMemoryTool(memoryStore),
    )

// Ticket #107 (T2): untrusted external reads. When the current AgentLoop
// run has already executed one of these Tools, a save_memory call is
// treated as attacker-influenced: its source is forced to "auto-saved" so
// injected "you told me" claims can never pass as user-stated facts.
val UNTRUSTED_EXTERNAL_READ_TOOLS: Set<String> =
    setOf("search_emails", "read_email", "open_url", "get_page_text", "click", "scroll", "extract")

fun sanitizeMemoryCallForUntrustedRun(call: ToolCall, untrustedReadOccurred: Boolean): ToolCall {
    if (call.name != "save_memory" || !untrustedReadOccurred) return call
    return call.copy(arguments = call.arguments + ("source" to "auto-saved"))
}
