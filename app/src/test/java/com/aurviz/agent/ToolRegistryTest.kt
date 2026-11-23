package com.aurviz.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket 01/24: ToolRegistry validation paths. Seams: execute() dispatch +
// validation + structured errors; registration metadata (schema, risk level,
// requiresConfirmation, result type); built-in search_emails +
// create_schedule wiring behind injected fakes.
class ToolRegistryTest {

    private class FakeGmail : GmailApi {
        override fun send(rawBase64Url: String) = Unit
        override fun listInbox(): List<EmailSummary> =
            listOf(
                EmailSummary("1", "boss@work.com", "Q3 plan", "draft", true),
                EmailSummary("2", "news@x.com", "Sale", "buy", false),
            )
        override fun searchInbox(query: String, max: Int): List<EmailSummary> =
            listInbox().filter {
                it.from.contains(query, ignoreCase = true) ||
                    it.subject.contains(query, ignoreCase = true) ||
                    it.snippet.contains(query, ignoreCase = true)
            }.take(max.coerceIn(1, 25))
        override fun readMessage(id: String): EmailDetail {
            val row = listInbox().firstOrNull { it.id == id }
                ?: throw NoSuchElementException("unknown message: $id")
            return EmailDetail(row.id, row.from, row.subject, row.snippet, "", emailSource(row.id))
        }
    }

    private fun registry(): ToolRegistry =
        defaultToolRegistry(
            gmailApi = FakeGmail(),
            scheduleStore = InMemoryScheduleStore(),
            memoryStore = InMemoryStore(),
        )

    @Test
    fun toolsDeclareSchemaRiskConfirmationAndResultType() {
        val registry = registry()
        val emails = registry.find("search_emails")!!
        assertEquals(RiskLevel.LOW, emails.riskLevel)
        assertFalse(emails.requiresConfirmation)
        assertTrue(emails.resultType.isNotBlank())
        assertTrue(emails.parameters.getJSONObject("properties").has("query"))

        // ADR-0003: create_schedule and cancel_schedule are sensitive Tools,
        // gated per call; list_schedules is a read and ungated.
        val create = registry.find("create_schedule")!!
        assertEquals(RiskLevel.MEDIUM, create.riskLevel)
        assertTrue(create.requiresConfirmation)
        assertTrue(create.resultType.isNotBlank())
        val props = create.parameters.getJSONObject("properties")
        assertTrue(props.has("task") && props.has("when"))

        val list = registry.find("list_schedules")!!
        assertEquals(RiskLevel.LOW, list.riskLevel)
        assertFalse(list.requiresConfirmation)
        assertTrue(list.resultType.isNotBlank())

        val cancel = registry.find("cancel_schedule")!!
        assertEquals(RiskLevel.MEDIUM, cancel.riskLevel)
        assertTrue(cancel.requiresConfirmation)
        assertTrue(cancel.resultType.isNotBlank())
        assertTrue(cancel.parameters.getJSONObject("properties").has("schedule_id"))
    }

    @Test
    fun unknownToolReturnsErrorResult() {
        val result = registry().execute("deleteEverything", emptyMap())
        assertTrue(result is ToolResult.Failure)
        result as ToolResult.Failure
        assertTrue(result.error.contains("unknown tool"))
        assertTrue(result.error.contains("deleteEverything"))
        assertTrue(result.retryable)
    }

    @Test
    fun missingRequiredArgNamesTheViolation() {
        val result = registry().execute("search_emails", emptyMap())
        assertTrue(result is ToolResult.Failure)
        result as ToolResult.Failure
        assertTrue(result.error.contains("query"))
        assertTrue(result.retryable)
    }

    @Test
    fun wrongArgTypeNamesTheViolation() {
        val result = registry().execute("search_emails", mapOf("query" to "boss", "max" to "five"))
        assertTrue(result is ToolResult.Failure)
        result as ToolResult.Failure
        assertTrue(result.error.contains("max"))
        assertTrue(result.error.contains("integer"))
        assertTrue(result.retryable)
    }

    @Test
    fun validEmailCallSucceeds() {
        val result = registry().execute("search_emails", mapOf("query" to "boss", "max" to 5))
        assertTrue(result is ToolResult.Success)
    }

    @Test
    fun validScheduleCallSucceedsAndStores() {
        val store = InMemoryScheduleStore()
        val registry = defaultToolRegistry(gmailApi = FakeGmail(), scheduleStore = store)
        val gated =
            registry.executeGated(
                ToolCall("call_1", "create_schedule", mapOf("task" to "check emails", "when" to "at 9am")),
                confirm = { ApprovalDecision.Approved },
            )
        assertTrue(gated.result is ToolResult.Success)
        assertEquals(1, store.list().size)
    }

    @Test
    fun unparseableWhenNamesTheViolation() {
        val gated =
            registry().executeGated(
                ToolCall("call_1", "create_schedule", mapOf("task" to "check emails", "when" to "someday maybe")),
                confirm = { ApprovalDecision.Approved },
            )
        assertTrue(gated.result is ToolResult.Failure)
        gated.result as ToolResult.Failure
        assertTrue((gated.result as ToolResult.Failure).error.contains("when"))
        assertTrue((gated.result as ToolResult.Failure).retryable)
    }

    @Test
    fun crudRoundTripThroughRegistry() {
        val store = InMemoryScheduleStore()
        val registry = defaultToolRegistry(gmailApi = FakeGmail(), scheduleStore = store)
        val approve: (ToolCall) -> ApprovalDecision = { ApprovalDecision.Approved }

        val created =
            registry.executeGated(
                ToolCall("call_1", "create_schedule", mapOf("task" to "check emails", "when" to "at 9am")),
                confirm = approve,
            )
        assertTrue(created.result is ToolResult.Success)
        val id = ((created.result as ToolResult.Success).data as Map<*, *>)["id"] as String

        val listed = registry.execute("list_schedules", emptyMap())
        assertTrue(listed is ToolResult.Success)
        val rows = (listed as ToolResult.Success).data as List<*>
        assertEquals(1, rows.size)
        assertEquals(id, (rows[0] as Map<*, *>)["id"])

        val cancelled =
            registry.executeGated(
                ToolCall("call_2", "cancel_schedule", mapOf("schedule_id" to id)),
                confirm = approve,
            )
        assertTrue(cancelled.result is ToolResult.Success)

        val listedAgain = registry.execute("list_schedules", emptyMap())
        assertTrue(listedAgain is ToolResult.Success)
        assertTrue(((listedAgain as ToolResult.Success).data as List<*>).isEmpty())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun cancelUnknownIdIsRetryableError() {
        val gated =
            registry().executeGated(
                ToolCall("call_1", "cancel_schedule", mapOf("schedule_id" to "sched_999")),
                confirm = { ApprovalDecision.Approved },
            )
        assertTrue(gated.result is ToolResult.Failure)
        gated.result as ToolResult.Failure
        assertTrue((gated.result as ToolResult.Failure).error.contains("sched_999"))
        assertTrue((gated.result as ToolResult.Failure).retryable)
    }

    @Test
    fun cancelMissingIdNamesTheViolation() {
        var consulted = false
        val gated =
            registry().executeGated(
                ToolCall("call_1", "cancel_schedule", emptyMap()),
                confirm = { consulted = true; ApprovalDecision.Approved },
            )
        assertTrue(gated.result is ToolResult.Failure)
        assertTrue((gated.result as ToolResult.Failure).error.contains("schedule_id"))
        // Invalid args fail before the approver is consulted.
        assertFalse(consulted)
    }

    @Test
    fun throwingExecutorBecomesErrorResultNotCrash() {
        val registry = ToolRegistry()
        registry.register(
            ToolDefinition(
                name = "boom",
                description = "always throws",
                parameters = org.json.JSONObject("{\"type\":\"object\",\"properties\":{}}"),
                riskLevel = RiskLevel.LOW,
                requiresConfirmation = false,
                resultType = "none",
                execute = { throw RuntimeException("kaput") },
            ),
        )
        val result = registry.execute("boom", emptyMap())
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("boom"))
    }

    @Test
    fun toolSchemasExposeAllToolsForLlm() {
        val schemas = registry().toolSchemas()
        val names = (0 until schemas.length())
            .map { schemas.getJSONObject(it).getJSONObject("function").getString("name") }
            .toSet()
        assertEquals(setOf("search_emails", "read_email", "send_email", "create_schedule", "list_schedules", "cancel_schedule", "save_memory", "search_memory", "open_url", "get_page_text", "click", "scroll", "extract"), names)
    }

    @Test
    fun memoryToolsDeclareSchemaRiskConfirmationAndResultType() {
        val registry = registry()
        val save = registry.find("save_memory")!!
        assertEquals(RiskLevel.LOW, save.riskLevel)
        assertFalse(save.requiresConfirmation)
        assertTrue(save.resultType.isNotBlank())
        assertTrue(save.parameters.getJSONObject("properties").has("text"))

        val search = registry.find("search_memory")!!
        assertEquals(RiskLevel.LOW, search.riskLevel)
        assertFalse(search.requiresConfirmation)
        assertTrue(search.resultType.isNotBlank())
        assertTrue(search.parameters.getJSONObject("properties").has("query"))
    }

    @Test
    fun saveMemoryThenSearchMemoryReturnsTheFact() {
        val store = InMemoryStore()
        val registry =
            defaultToolRegistry(
                gmailApi = FakeGmail(),
                scheduleStore = InMemoryScheduleStore(),
                memoryStore = store,
            )
        val saved = registry.execute("save_memory", mapOf("text" to "my boss is Priya", "source" to "you told me"))
        assertTrue(saved is ToolResult.Success)

        val found = registry.execute("search_memory", mapOf("query" to "who is my boss?"))
        assertTrue(found is ToolResult.Success)
        found as ToolResult.Success
        @Suppress("UNCHECKED_CAST")
        val rows = found.data as List<Map<String, Any?>>
        assertTrue(rows.any { it["text"] == "my boss is Priya" })
    }

    @Test
    fun saveMemoryWithoutTextNamesTheViolation() {
        val result = registry().execute("save_memory", emptyMap())
        assertTrue(result is ToolResult.Failure)
        result as ToolResult.Failure
        assertTrue(result.error.contains("text"))
        assertTrue(result.retryable)
    }

    @Test
    fun searchMemoryWithoutQueryNamesTheViolation() {
        val result = registry().execute("search_memory", emptyMap())
        assertTrue(result is ToolResult.Failure)
        result as ToolResult.Failure
        assertTrue(result.error.contains("query"))
        assertTrue(result.retryable)
    }

    @Test
    fun searchMemoryDefaultsToFiveResults() {
        val store = InMemoryStore()
        val registry =
            defaultToolRegistry(
                gmailApi = FakeGmail(),
                scheduleStore = InMemoryScheduleStore(),
                memoryStore = store,
            )
        for (i in 1..8) {
            registry.execute("save_memory", mapOf("text" to "shared fact number $i boss", "source" to "you told me"))
        }
        val found = registry.execute("search_memory", mapOf("query" to "shared fact boss"))
        assertTrue(found is ToolResult.Success)
        @Suppress("UNCHECKED_CAST")
        val rows = (found as ToolResult.Success).data as List<Map<String, Any?>>
        assertEquals(5, rows.size)
    }
}
