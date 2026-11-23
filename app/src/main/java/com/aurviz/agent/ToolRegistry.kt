package com.aurviz.agent

import org.json.JSONArray
import org.json.JSONObject

// Ticket 01/24: central ToolRegistry. Every LLM-visible Tool declares its
// JSON schema, risk level, requiresConfirmation flag, and result type.
// Arguments are validated against the schema before execution; unknown
// Tools and invalid args return structured error results the model can
// recover from ({ok:false, error, retryable}) instead of crashing.
// Ticket 09/24: the Confirmation gate lives here, so the model has no
// path around it. execute() refuses gated Tools outright (gate-refusal);
// executeGated() is the single gated path: it suspends on the confirmer
// until it returns a Confirmation decision (confirmed, confirmed-with-edits,
// reject, or timeout), and
// reject/timeout return {status:"cancelled_by_user"} without running
// the executor.

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

/** Outcome of one gated run: the structured result the model sees, the
 *  args that actually ran (edited args when the user edited them), and
 *  whether the user approved (false on reject/timeout/validation refusals
 *  where the executor never ran). */
data class GatedExecution(
    val result: ToolResult,
    val effectiveArgs: Map<String, Any?>,
    val approved: Boolean,
)

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
    // Ticket 09/24: gated entry for requiresConfirmation Tools. Plain
    // execute() never sends (send_email's closure bakes approved=false);
    // the single gated path runs the Tool's approvedExecute entry
    // (send_email's policy path) after the Confirmation UI's decision
    // travels out-of-band, so the model cannot forge it as an arg. Null
    // (e.g. schedule Tools) falls back to execute().
    val approvedExecute: ((args: Map<String, Any?>) -> ToolResult)? = null,
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

/** Shared OpenAI function-tool envelope (ADR-0004): every LLM-visible
 *  schema is {"type":"function","function":{name, description, parameters}}.
 *  One builder for ToolRegistry, LlmProvider, and Memory schemas so the
 *  shape cannot drift between them. */
fun toolSchema(name: String, description: String, parameters: JSONObject): JSONObject =
    JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", parameters),
        )

class ToolRegistry {
    private val tools = mutableMapOf<String, ToolDefinition>()

    fun register(definition: ToolDefinition) {
        tools[definition.name] = definition
    }

    fun find(name: String): ToolDefinition? = tools[name]

    fun names(): Set<String> = tools.keys.toSet()

    /** Read-only gate query for the loop's NeedsApproval event. Asking
     *  whether a Tool is gated never runs anything, so emitting the
     *  Confirmation card off this is not a path around the gate. */
    fun requiresConfirmation(name: String): Boolean = find(name)?.requiresConfirmation == true

    /** LLM request payload, same OpenAI function shape as allToolSchemas(). */
    fun toolSchemas(): JSONArray =
        JSONArray(
            tools.values.map { definition ->
                toolSchema(definition.name, definition.description, definition.parameters)
            },
        )

    /** Validate args against the Tool schema, then run. Never throws:
     *  unknown Tools, invalid args, and executor failures all come back
     *  as Failure results the model can react to. Gated Tools are refused
     *  here (gate-refusal): the executor never runs without Confirmation,
     *  so even a caller that skips executeGated cannot reach it. */
    fun execute(name: String, args: Map<String, Any?>): ToolResult {
        val definition = tools[name]
            ?: return ToolResult.Failure(
                "unknown tool: '$name'. Available tools: ${tools.keys.sorted().joinToString(", ")}",
                retryable = true,
            )
        validateArgs(definition, args)?.let { return it }
        if (definition.requiresConfirmation) {
            return ToolResult.Failure(
                "tool '$name' requires Confirmation: confirm, edit, or reject it through executeGated first",
                retryable = false,
            )
        }
        return runExecutor(definition, args)
    }

    /** Gated entry point (ticket 09/24): the only path that runs a
     *  requiresConfirmation Tool on the model's behalf. Invalid args fail
     *  before the confirmer is consulted; edited args are revalidated
     *  before they run. Reject and timeout return Success
     *  {status:"cancelled_by_user"} (timeout adds a reason) so the model
     *  recovers and the loop continues or ends cleanly. Approved calls run
     *  the Tool's approvedExecute entry when it has one (send_email's
     *  policy path) and fall back to execute() otherwise. */
    fun executeGated(call: ToolCall, confirm: (ToolCall) -> ApprovalDecision): GatedExecution {
        val definition = tools[call.name]
            ?: return GatedExecution(
                ToolResult.Failure(
                    "unknown tool: '${call.name}'. Available tools: ${tools.keys.sorted().joinToString(", ")}",
                    retryable = true,
                ),
                call.arguments,
                approved = false,
            )
        validateArgs(definition, call.arguments)?.let { return GatedExecution(it, call.arguments, approved = false) }
        if (!definition.requiresConfirmation) {
            return GatedExecution(runExecutor(definition, call.arguments), call.arguments, approved = true)
        }
        return when (val decision = confirm(call)) {
            is ApprovalDecision.Approved ->
                GatedExecution(runApproved(definition, call.arguments), call.arguments, approved = true)
            is ApprovalDecision.ApprovedWithEdit -> {
                validateArgs(definition, decision.args)?.let { return GatedExecution(it, decision.args, approved = true) }
                GatedExecution(runApproved(definition, decision.args), decision.args, approved = true)
            }
            is ApprovalDecision.Rejected ->
                GatedExecution(
                    ToolResult.Success(mapOf("status" to "cancelled_by_user")),
                    call.arguments,
                    approved = false,
                )
            is ApprovalDecision.TimedOut ->
                GatedExecution(
                    ToolResult.Success(
                        mapOf("status" to "cancelled_by_user", "reason" to "confirmation_timed_out"),
                    ),
                    call.arguments,
                    approved = false,
                )
        }
    }

    private fun runExecutor(definition: ToolDefinition, args: Map<String, Any?>): ToolResult =
        try {
            definition.execute(args)
        } catch (e: Exception) {
            ToolResult.Failure("tool '${definition.name}' failed: ${e.message}", retryable = false)
        }

    private fun runApproved(definition: ToolDefinition, args: Map<String, Any?>): ToolResult =
        try {
            definition.approvedExecute?.invoke(args) ?: definition.execute(args)
        } catch (e: Exception) {
            ToolResult.Failure("tool '${definition.name}' failed: ${e.message}", retryable = false)
        }
}

/** Registry with the read/search Tools + send Tool + schedule/memory Tools wired to
 *  real seams. Gmail/Room plug in through GmailApi/ScheduleStore/
 *  MemoryStore; tests pass fakes. A null dependency means "not connected
 *  on this device", also a Failure. */
fun defaultToolRegistry(
    gmailApi: GmailApi? = null,
    scheduleStore: ScheduleStore? = null,
    memoryStore: MemoryStore? = null,
    sendPolicy: SendPolicy = DefaultSendPolicy(),
    webBridge: WebBridge? = null,
    backgroundWebLoader: BackgroundPageLoader? = null,
): ToolRegistry {
    val registry = ToolRegistry()

    // Ticket 72 (part of #71): Gmail adapter owns search/read/send; each
    // entry registers explicitly into the kernel, in adapter order.
    for (tool in gmailTools(gmailApi, sendPolicy)) {
        registry.register(tool)
    }

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
                            "source" to emailSource(row.id),
                        )
                    },
                )
            },
        ),
    )

    // Ticket 73 (part of #71): Schedule adapter owns create/list/cancel;
    // each entry registers explicitly into the kernel, in adapter order.
    for (tool in scheduleTools(scheduleStore)) {
        registry.register(tool)
    }

    // Ticket 74 (part of #71): Memory adapter owns save/search; each
    // entry registers explicitly into the kernel, in adapter order.
    for (tool in memoryTools(memoryStore)) {
        registry.register(tool)
    }

    // Ticket 74 (part of #71): Web adapter owns open/page-text/click/
    // scroll/extract; each entry registers explicitly into the kernel,
    // in adapter order. get_page_text keeps its background fallback for
    // scheduled runs (webBridge == null, ADR-0002).
    for (tool in webTools(webBridge, backgroundWebLoader)) {
        registry.register(tool)
    }

    return registry
}
