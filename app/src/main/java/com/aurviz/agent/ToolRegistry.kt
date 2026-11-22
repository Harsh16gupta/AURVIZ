package com.aurviz.agent

import org.json.JSONArray
import org.json.JSONObject

// Ticket 01/24: central ToolRegistry. Every LLM-visible Tool declares its
// JSON schema, risk level, requiresConfirmation flag, and result type.
// Arguments are validated against the schema before execution; unknown
// Tools and invalid args return structured error results the model can
// recover from ({ok:false, error, retryable}) instead of crashing.
// The Confirmation gate (ticket 03/24) will live here, so the model has
// no path around it: requiresConfirmation Tools suspend until the UI
// returns approve, edit, or reject.

/** Sensitivity of one Tool call. Reads are LOW; state-changing but
 *  reversible calls (create_schedule) are MEDIUM; irreversible
 *  sends (send_email, ticket 02/24) will be HIGH. */
enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
}

/** Structured result of one Tool execution, shaped so the model can
 *  recover: {ok, data} on success, {ok:false, error, retryable} after. */
sealed interface ToolResult {
    data class Success(val data: Any?) : ToolResult

    data class Failure(val error: String, val retryable: Boolean) : ToolResult
}

fun ToolResult.toMap(): Map<String, Any?> =
    when (this) {
        is ToolResult.Success -> mapOf("ok" to true, "data" to data)
        is ToolResult.Failure -> mapOf("ok" to false, "error" to error, "retryable" to retryable)
    }

/** One LLM-visible capability. parameters is the JSON-schema "parameters"
 *  object (properties + required); it is the single source of truth for
 *  validation, and toolSchemas() re-emits it for the LLM request. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JSONObject,
    val riskLevel: RiskLevel,
    val requiresConfirmation: Boolean,
    val resultType: String,
    val execute: (args: Map<String, Any?>) -> ToolResult,
)

private fun schemaTypeMatches(expected: String, value: Any?): Boolean =
    when (expected) {
        "integer" -> value is Number
        "number" -> value is Number
        "string" -> value is String
        "boolean" -> value is Boolean
        "array" -> value is List<*>
        "object" -> value is Map<*, *>
        else -> true
    }

private fun validateArgs(definition: ToolDefinition, args: Map<String, Any?>): ToolResult.Failure? {
    val parameters = definition.parameters
    val required = parameters.optJSONArray("required")
    if (required != null) {
        for (i in 0 until required.length()) {
            val slot = required.getString(i)
            val value = args[slot]
            if (value == null || (value is String && value.isBlank())) {
                return ToolResult.Failure(
                    "missing required argument '$slot' for tool '${definition.name}'",
                    retryable = true,
                )
            }
        }
    }
    val properties = parameters.optJSONObject("properties")
    if (properties != null) {
        for (key in properties.keys()) {
            if (!args.containsKey(key) || args[key] == null) continue
            val expected = properties.optJSONObject(key)?.optString("type").orEmpty()
            if (expected.isNotEmpty() && !schemaTypeMatches(expected, args[key])) {
                return ToolResult.Failure(
                    "invalid argument '$key' for tool '${definition.name}': expected $expected",
                    retryable = true,
                )
            }
        }
    }
    return null
}

class ToolRegistry {
    private val tools = mutableMapOf<String, ToolDefinition>()

    fun register(definition: ToolDefinition) {
        tools[definition.name] = definition
    }

    fun find(name: String): ToolDefinition? = tools[name]

    fun names(): Set<String> = tools.keys.toSet()

    /** LLM request payload, same OpenAI function shape as allToolSchemas(). */
    fun toolSchemas(): JSONArray =
        JSONArray(
            tools.values.map { definition ->
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", definition.name)
                            .put("description", definition.description)
                            .put("parameters", definition.parameters),
                    )
            },
        )

    /** Validate args against the Tool schema, then run. Never throws:
     *  unknown Tools, invalid args, and executor failures all come back
     *  as Failure results the model can react to. */
    fun execute(name: String, args: Map<String, Any?>): ToolResult {
        val definition = tools[name]
            ?: return ToolResult.Failure(
                "unknown tool: '$name'. Available tools: ${tools.keys.sorted().joinToString(", ")}",
                retryable = true,
            )
        validateArgs(definition, args)?.let { return it }
        return try {
            definition.execute(args)
        } catch (e: Exception) {
            ToolResult.Failure("tool '$name' failed: ${e.message}", retryable = false)
        }
    }
}

/** Ticket 03/24 search_emails Tool: read-only Gmail search (ADR-0003:
 *  no Confirmation gate on reads). Every row carries its mailbox source
 *  tag for the injection defences. max is optional, default 10. */
fun searchEmailsTool(gmailApi: GmailApi?): ToolDefinition =
    ToolDefinition(
        name = "search_emails",
        description = "Search the Gmail mailbox with Gmail query syntax " +
            "(e.g. from:boss subject:plan). Read-only; returns matching rows.",
        parameters =
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("query", JSONObject().put("type", "string").put("description", "Gmail search query, e.g. from:boss is:unread"))
                        .put("max", JSONObject().put("type", "integer").put("description", "Max rows to return (1-25, default 10)")),
                )
                .put("required", JSONArray().put("query")),
        // Read-only fetch; ADR-0003 gates only sensitive calls.
        riskLevel = RiskLevel.LOW,
        requiresConfirmation = false,
        resultType = "emails",
        execute = { args ->
            val api = gmailApi
                ?: return@ToolDefinition ToolResult.Failure("gmail is not connected", retryable = false)
            val query = (args["query"] as? String)?.trim().orEmpty()
            if (query.isEmpty()) {
                return@ToolDefinition ToolResult.Failure(
                    "query must not be blank for tool 'search_emails'",
                    retryable = true,
                )
            }
            val max = ((args["max"] as? Number)?.toInt() ?: 10).coerceIn(1, 25)
            val rows = searchEmails(api, query, max)
            ToolResult.Success(
                rows.map { row ->
                    mapOf(
                        "id" to row.id,
                        "from" to row.from,
                        "subject" to row.subject,
                        "snippet" to row.snippet,
                        "source" to emailSource(row.id),
                    )
                },
            )
        },
    )

/** Ticket 03/24 read_email Tool: read one message with its body.
 *  Read-only (ADR-0003: ungated); the result carries its mailbox
 *  source tag for the injection defences. */
fun readEmailTool(gmailApi: GmailApi?): ToolDefinition =
    ToolDefinition(
        name = "read_email",
        description = "Read one Gmail message with its body. Read-only; returns the message.",
        parameters =
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("id", JSONObject().put("type", "string").put("description", "Gmail message id from search_emails")),
                )
                .put("required", JSONArray().put("id")),
        // Read-only fetch; ADR-0003 gates only sensitive calls.
        riskLevel = RiskLevel.LOW,
        requiresConfirmation = false,
        resultType = "email",
        execute = { args ->
            val api = gmailApi
                ?: return@ToolDefinition ToolResult.Failure("gmail is not connected", retryable = false)
            val detail = readEmail(api, args["id"] as String)
            ToolResult.Success(
                mapOf(
                    "id" to detail.id,
                    "from" to detail.from,
                    "subject" to detail.subject,
                    "snippet" to detail.snippet,
                    "body" to detail.body,
                    "source" to detail.source,
                ),
            )
        },
    )

/** Registry with the read/search Tools + schedule/memory Tools wired to
 *  real seams. Gmail/Room plug in through GmailApi/ScheduleStore/
 *  MemoryStore; tests pass fakes. A null dependency means "not connected
 *  on this device", also a Failure. */
fun defaultToolRegistry(gmailApi: GmailApi? = null, scheduleStore: ScheduleStore? = null, memoryStore: MemoryStore? = null): ToolRegistry {
    val registry = ToolRegistry()

    registry.register(searchEmailsTool(gmailApi))
    registry.register(readEmailTool(gmailApi))

    val emailFn = demoToolSchema().getJSONObject("function")
    registry.register(
        ToolDefinition(
            name = emailFn.getString("name"),
            description = emailFn.getString("description"),
            parameters = emailFn.getJSONObject("parameters"),
            // Read-only fetch; ADR-0003 gates only sensitive calls.
            riskLevel = RiskLevel.LOW,
            requiresConfirmation = false,
            resultType = "emails",
            execute = { args ->
                val api = gmailApi
                    ?: return@ToolDefinition ToolResult.Failure("gmail is not connected", retryable = false)
                val max = (args["max_results"] as Number).toInt()
                val rows = readImportant(api, max)
                ToolResult.Success(
                    rows.map { row ->
                        mapOf(
                            "id" to row.id,
                            "from" to row.from,
                            "subject" to row.subject,
                            "snippet" to row.snippet,
                        )
                    },
                )
            },
        ),
    )

    val scheduleFn = scheduleToolSchema().getJSONObject("function")
    registry.register(
        ToolDefinition(
            name = scheduleFn.getString("name"),
            description = scheduleFn.getString("description"),
            parameters = scheduleFn.getJSONObject("parameters"),
            // State-changing (arms alarms); ADR-0003 requires per-call Confirmation.
            riskLevel = RiskLevel.MEDIUM,
            requiresConfirmation = true,
            resultType = "schedule",
            execute = { args ->
                val store = scheduleStore
                    ?: return@ToolDefinition ToolResult.Failure("schedule store is not available", retryable = false)
                val task = args["task"] as String
                val whenText = args["when"] as String
                val parsed = parseSchedule("$task $whenText")
                    ?: return@ToolDefinition ToolResult.Failure(
                        "could not understand a time in 'when': '$whenText'",
                        retryable = true,
                    )
                val saved = createSchedule(store, task, parsed.hour, parsed.minute, parsed.days)
                ToolResult.Success(
                    mapOf(
                        "id" to saved.id,
                        "instruction" to saved.instruction,
                        "hour" to saved.hour,
                        "minute" to saved.minute,
                    ),
                )
            },
        ),
    )

    val listFn = listSchedulesToolSchema().getJSONObject("function")
    registry.register(
        ToolDefinition(
            name = listFn.getString("name"),
            description = listFn.getString("description"),
            parameters = listFn.getJSONObject("parameters"),
            // Read-only; ADR-0003 gates only sensitive calls.
            riskLevel = RiskLevel.LOW,
            requiresConfirmation = false,
            resultType = "schedules",
            execute = { _ ->
                val store = scheduleStore
                    ?: return@ToolDefinition ToolResult.Failure("schedule store is not available", retryable = false)
                ToolResult.Success(
                    listSchedules(store).map { schedule ->
                        mapOf(
                            "id" to schedule.id,
                            "instruction" to schedule.instruction,
                            "hour" to schedule.hour,
                            "minute" to schedule.minute,
                            "days" to schedule.days?.toList(),
                        )
                    },
                )
            },
        ),
    )

    val saveMemoryFn = saveMemoryToolSchema().getJSONObject("function")
    registry.register(
        ToolDefinition(
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
        ),
    )

    val searchMemoryFn = searchMemoryToolSchema().getJSONObject("function")
    registry.register(
        ToolDefinition(
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
                val max = (args["max_results"] as Number?)?.toInt()?.coerceAtLeast(0)
                val hits = recallFor(store, query).let { if (max != null) it.take(max) else it }
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
        ),
    )

    val cancelFn = cancelScheduleToolSchema().getJSONObject("function")
    registry.register(
        ToolDefinition(
            name = cancelFn.getString("name"),
            description = cancelFn.getString("description"),
            parameters = cancelFn.getJSONObject("parameters"),
            // State-changing (disarms alarms); ADR-0003 requires per-call Confirmation.
            riskLevel = RiskLevel.MEDIUM,
            requiresConfirmation = true,
            resultType = "schedule",
            execute = { args ->
                val store = scheduleStore
                    ?: return@ToolDefinition ToolResult.Failure("schedule store is not available", retryable = false)
                val id = args["schedule_id"] as String
                val existing = listSchedules(store).find { it.id == id }
                    ?: return@ToolDefinition ToolResult.Failure(
                        "unknown schedule id: '$id'. Use list_schedules to see saved routines.",
                        retryable = true,
                    )
                deleteSchedule(store, existing.id)
                ToolResult.Success(mapOf("id" to existing.id, "deleted" to true))
            },
        ),
    )

    return registry
}
