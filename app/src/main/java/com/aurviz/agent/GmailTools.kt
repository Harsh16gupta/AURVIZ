package com.aurviz.agent

import org.json.JSONArray
import org.json.JSONObject

// Ticket 72 (part of #71): Gmail Tool adapter. Owns the three Gmail Tool
// declarations — mailbox search, single-message read, and gated send with
// its send-policy check — and explicitly registers them into the small
// validate-and-gate kernel (ToolRegistry). No behavior change: reads stay
// ungated LOW risk, send stays HIGH risk with per-call Confirmation, a
// missing connection still returns a non-retryable Failure, and the kernel
// still refuses gated Tools on the ungated path.
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
                    "body" to truncateToolText(detail.body),
                    "source" to detail.source,
                )
            )
        },
    )

// Ticket #106 (T1): multi-recipient split. The LLM passes "to" as one
// comma-separated string; DefaultSendPolicy counts List entries, so the
// draft must split before the policy check or multi-recipient sends
// never reach the transport.
fun parseRecipientList(rawTo: String): List<String> =
    rawTo.split(",").map { it.trim() }.filter { it.isNotEmpty() }

fun sendEmailTool(gmailApi: GmailApi?, policy: SendPolicy = AllowAllSendPolicy): ToolDefinition {
    fun draftFrom(args: Map<String, Any?>): EmailDraft =
        EmailDraft(
            to = parseRecipientList((args["to"] as? String).orEmpty()),
            subject = (args["subject"] as? String).orEmpty(),
            body = (args["body"] as? String).orEmpty(),
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

/** The Gmail adapter: all Gmail Tool declarations in registration order
 *  (search, read, gated send). The default registry builder registers
 *  every entry into the kernel; scheduled-run tests register the same
 *  entries into a standalone kernel for the read-only view. */
fun gmailTools(gmailApi: GmailApi?, policy: SendPolicy = AllowAllSendPolicy): List<ToolDefinition> =
    listOf(
        searchEmailsTool(gmailApi),
        readEmailTool(gmailApi),
        sendEmailTool(gmailApi, policy),
    )
