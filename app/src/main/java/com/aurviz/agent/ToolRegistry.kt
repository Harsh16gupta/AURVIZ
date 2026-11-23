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
// executeGated() is the single gated path: it suspends on the approver
// until it returns approve, edited-approve, reject, or timeout, and
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
                "tool '$name' requires confirmation: approve, edit, or reject it through executeGated first",
                retryable = false,
            )
        }
        return runExecutor(definition, args)
    }

    /** Gated entry point (ticket 09/24): the only path that runs a
     *  requiresConfirmation Tool on the model's behalf. Invalid args fail
     *  before the approver is consulted; edited args are revalidated
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

/** Ticket 04/24 send_email Tool: irreversible send, HIGH risk, gated per
 *  call (ADR-0003). The plain execute() entry always refuses without
 *  touching the transport; only the single gated path (executeGated)
 *  builds the MIME from the exact To/Subject/Body args and sends, after
 *  the deterministic SendPolicy check. */
fun sendEmailTool(gmailApi: GmailApi?, policy: SendPolicy = AllowAllSendPolicy): ToolDefinition {
    fun draftFrom(args: Map<String, Any?>): EmailDraft =
        EmailDraft(
            to = listOf(args["to"] as String),
            subject = args["subject"] as String,
            body = args["body"] as String,
        )
    return ToolDefinition(
        name = "send_email",
        description = "Send one Gmail message to To with Subject and Body. " +
            "Sensitive: runs only after per-call confirmation.",
        parameters =
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("to", JSONObject().put("type", "string").put("description", "Recipient email address"))
                        .put("subject", JSONObject().put("type", "string").put("description", "Message subject"))
                        .put("body", JSONObject().put("type", "string").put("description", "Plain-text message body")),
                )
                .put("required", JSONArray().put("to").put("subject").put("body")),
        // Irreversible outbound send; ADR-0003 gates every call.
        riskLevel = RiskLevel.HIGH,
        requiresConfirmation = true,
        resultType = "send",
        execute = { args ->
            executeSendEmail(gmailApi, draftFrom(args), approved = false, policy = policy)
        },
        approvedExecute = { args ->
            executeSendEmail(gmailApi, draftFrom(args), approved = true, policy = policy)
        },
    )
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

    registry.register(searchEmailsTool(gmailApi))
    registry.register(readEmailTool(gmailApi))
    registry.register(sendEmailTool(gmailApi, sendPolicy))

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

    registerWebTools(registry, webBridge, backgroundWebLoader)

    return registry
}

/** Ticket 07/24: five foreground web Tools over the WebView bridge. Reads
 *  and in-page interactions are LOW risk and need no Confirmation; the user
 *  watches the foreground tab. A null bridge means the Browser tab has not
 *  attached yet — a retryable Failure, never a hang.
 *
 *  Ticket 08/24: get_page_text additionally accepts a headless
 *  BackgroundPageLoader (OkHttp + Jsoup). When the bridge is attached it
 *  still wins (foreground behavior unchanged); when it is null — the
 *  scheduled-run case, ADR-0002 — the loader serves the read. The other
 *  four Tools stay foreground-only: they need the live tab. */
fun registerWebTools(
    registry: ToolRegistry,
    webBridge: WebBridge?,
    backgroundWebLoader: BackgroundPageLoader? = null,
) {
    fun webFailure(tool: String, e: WebException): ToolResult.Failure {
        val where = if (e.failure.url.isNotBlank()) ": ${e.failure.url}" else ""
        // "blocked" never clears on retry (same URL, same verdict), so say
        // what is allowed; every bridge failure (timeout, load-failed,
        // no-match, no-page) can clear and stays retryable.
        if (e.failure.reason == "blocked") {
            return ToolResult.Failure("$tool blocked: only http(s) URLs are allowed$where", retryable = false)
        }
        return ToolResult.Failure("$tool failed (${e.failure.reason})$where", retryable = true)
    }

    fun noBridge(): ToolResult.Failure =
        ToolResult.Failure(
            "browser is not ready yet — open the Browser tab and try again",
            retryable = true,
        )

    registry.register(
        ToolDefinition(
            name = "open_url",
            description = "Open a page in the foreground browser tab. Returns its title, readable text, and links.",
            parameters = webParams("url", "The http(s) page URL to open, e.g. https://example.com"),
            riskLevel = RiskLevel.LOW,
            requiresConfirmation = false,
            resultType = "page",
            execute = { args ->
                val bridge = webBridge ?: return@ToolDefinition noBridge()
                val url = args["url"] as String
                try {
                    val page = openPage(bridge, url)
                    ToolResult.Success(
                        mapOf(
                            "url" to page.url,
                            "title" to page.title,
                            "text" to page.text,
                            "links" to page.links,
                            "source" to pageSource(page.url),
                        ),
                    )
                } catch (e: WebException) {
                    webFailure("open_url", e)
                }
            },
        ),
    )

    registry.register(
        ToolDefinition(
            name = "get_page_text",
            description = "Return the readable text of an http(s) page, without links.",
            parameters = webParams("url", "The http(s) page URL to read, e.g. https://example.com"),
            riskLevel = RiskLevel.LOW,
            requiresConfirmation = false,
            resultType = "page_text",
            execute = { args ->
                val url = args["url"] as String
                try {
                    // Foreground tab wins when attached; headless loader
                    // serves scheduled runs (webBridge == null, ADR-0002).
                    val page =
                        if (webBridge != null) {
                            openPage(webBridge, url)
                        } else {
                            val loader = backgroundWebLoader ?: return@ToolDefinition noBridge()
                            openBackgroundPage(loader, url)
                        }
                    ToolResult.Success(
                        mapOf(
                            "url" to page.url,
                            "title" to page.title,
                            "text" to page.text,
                            "source" to pageSource(page.url),
                        ),
                    )
                } catch (e: WebException) {
                    webFailure("get_page_text", e)
                }
            },
        ),
    )

    registry.register(
        ToolDefinition(
            name = "click",
            description = "Click a CSS selector in the current foreground page. Returns the page after the click.",
            parameters = webParams("selector", "CSS selector to click, e.g. a.buy"),
            riskLevel = RiskLevel.LOW,
            requiresConfirmation = false,
            resultType = "page",
            execute = { args ->
                val bridge = webBridge ?: return@ToolDefinition noBridge()
                val selector = args["selector"] as String
                try {
                    val page = parsePage("", bridge.click(selector))
                    ToolResult.Success(
                        mapOf(
                            "title" to page.title,
                            "text" to page.text,
                            "links" to page.links,
                            "source" to pageSource(bridge.currentUrl()),
                        ),
                    )
                } catch (e: WebException) {
                    webFailure("click", e)
                }
            },
        ),
    )

    registry.register(
        ToolDefinition(
            name = "scroll",
            description = "Scroll the current foreground page vertically. Returns the page after scrolling.",
            parameters =
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "px",
                            JSONObject().put("type", "integer")
                                .put("description", "Pixels to scroll; negative scrolls up."),
                        ),
                    )
                    .put("required", JSONArray().put("px")),
            riskLevel = RiskLevel.LOW,
            requiresConfirmation = false,
            resultType = "page",
            execute = { args ->
                val bridge = webBridge ?: return@ToolDefinition noBridge()
                val px = (args["px"] as Number).toInt()
                try {
                    val page = parsePage("", bridge.scrollBy(px))
                    ToolResult.Success(
                        mapOf(
                            "title" to page.title,
                            "text" to page.text,
                            "links" to page.links,
                            "source" to pageSource(bridge.currentUrl()),
                        ),
                    )
                } catch (e: WebException) {
                    webFailure("scroll", e)
                }
            },
        ),
    )

    registry.register(
        ToolDefinition(
            name = "extract",
            description = "Return the chunks of the current foreground page most relevant to the query.",
            parameters = webParams("query", "What to look for on the current page, e.g. bread price"),
            riskLevel = RiskLevel.LOW,
            requiresConfirmation = false,
            resultType = "extract",
            execute = { args ->
                val bridge = webBridge ?: return@ToolDefinition noBridge()
                val query = args["query"] as String
                try {
                    val text = bridge.pageText()
                    val chunks = relevantChunks(WebPage(url = "", title = "", text = text, links = emptyList()), query)
                    ToolResult.Success(
                        mapOf(
                            "query" to query,
                            "chunks" to chunks,
                            "source" to pageSource(bridge.currentUrl()),
                        ),
                    )
                } catch (e: WebException) {
                    webFailure("extract", e)
                }
            },
        ),
    )
}

private fun webParams(name: String, description: String): JSONObject =
    JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put(name, JSONObject().put("type", "string").put("description", description)),
        )
        .put("required", JSONArray().put(name))
