package com.aurviz.agent

// Ticket 74 (part of #71): Memory Tool adapter. Owns the two Memory Tool
// declarations — on-device save and ranked search — and explicitly
// registers them into the small validate-and-gate kernel (ToolRegistry).
// No behavior change: both stay LOW risk ungated, a missing store still
// returns a non-retryable Failure, and the kernel still refuses gated
// Tools on the ungated path (neither Memory Tool is gated).
fun saveMemoryTool(memoryStore: MemoryStore?): ToolDefinition {
    val saveMemoryFn = saveMemoryToolSchema().getJSONObject("function")
    return ToolDefinition(
        name = saveMemoryFn.getString("name"),
        description = saveMemoryFn.getString("description"),
        parameters = saveMemoryFn.getJSONObject("parameters"),
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
            val saved = rememberFact(store, text, source)
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
    val searchMemoryFn = searchMemoryToolSchema().getJSONObject("function")
    return ToolDefinition(
        name = searchMemoryFn.getString("name"),
        description = searchMemoryFn.getString("description"),
        parameters = searchMemoryFn.getJSONObject("parameters"),
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
fun memoryTools(memoryStore: MemoryStore?): List<ToolDefinition> =
    listOf(
        saveMemoryTool(memoryStore),
        searchMemoryTool(memoryStore),
    )
