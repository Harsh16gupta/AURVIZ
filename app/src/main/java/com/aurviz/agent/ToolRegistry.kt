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

/** Registry with the two existing Tools wired to real seams. Gmail/Room
 *  plug in through GmailApi/ScheduleStore; tests pass fakes. A null
 *  dependency means "not connected on this device", also a Failure. */
fun defaultToolRegistry(gmailApi: GmailApi? = null, scheduleStore: ScheduleStore? = null): ToolRegistry {
    val registry = ToolRegistry()

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

    return registry
}
