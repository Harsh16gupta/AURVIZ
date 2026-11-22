package com.aurviz.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket 01/24: ToolRegistry validation paths. Seams: execute() dispatch +
// validation + structured errors; registration metadata (schema, risk level,
// requiresConfirmation, result type); built-in getImportantEmails +
// createSchedule wiring behind injected fakes.
class ToolRegistryTest {

    private class FakeGmail : GmailApi {
        override fun send(rawBase64Url: String) = Unit
        override fun listInbox(): List<EmailSummary> =
            listOf(
                EmailSummary("1", "boss@work.com", "Q3 plan", "draft", true),
                EmailSummary("2", "news@x.com", "Sale", "buy", false),
            )
    }

    private fun registry(): ToolRegistry =
        defaultToolRegistry(gmailApi = FakeGmail(), scheduleStore = InMemoryScheduleStore())

    @Test
    fun toolsDeclareSchemaRiskConfirmationAndResultType() {
        val registry = registry()
        val emails = registry.find("getImportantEmails")!!
        assertEquals(RiskLevel.LOW, emails.riskLevel)
        assertFalse(emails.requiresConfirmation)
        assertTrue(emails.resultType.isNotBlank())
        assertTrue(emails.parameters.getJSONObject("properties").has("max_results"))

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
        val result = registry().execute("getImportantEmails", emptyMap())
        assertTrue(result is ToolResult.Failure)
        result as ToolResult.Failure
        assertTrue(result.error.contains("max_results"))
        assertTrue(result.retryable)
    }

    @Test
    fun wrongArgTypeNamesTheViolation() {
        val result = registry().execute("getImportantEmails", mapOf("max_results" to "five"))
        assertTrue(result is ToolResult.Failure)
        result as ToolResult.Failure
        assertTrue(result.error.contains("max_results"))
        assertTrue(result.error.contains("integer"))
        assertTrue(result.retryable)
    }

    @Test
    fun validEmailCallSucceeds() {
        val result = registry().execute("getImportantEmails", mapOf("max_results" to 5))
        assertTrue(result is ToolResult.Success)
    }

    @Test
    fun validScheduleCallSucceedsAndStores() {
        val store = InMemoryScheduleStore()
        val registry = defaultToolRegistry(gmailApi = FakeGmail(), scheduleStore = store)
        val result = registry.execute("create_schedule", mapOf("task" to "check emails", "when" to "at 9am"))
        assertTrue(result is ToolResult.Success)
        assertEquals(1, store.list().size)
    }

    @Test
    fun unparseableWhenNamesTheViolation() {
        val result = registry().execute("create_schedule", mapOf("task" to "check emails", "when" to "someday maybe"))
        assertTrue(result is ToolResult.Failure)
        result as ToolResult.Failure
        assertTrue(result.error.contains("when"))
        assertTrue(result.retryable)
    }

    @Test
    fun crudRoundTripThroughRegistry() {
        val store = InMemoryScheduleStore()
        val registry = defaultToolRegistry(gmailApi = FakeGmail(), scheduleStore = store)

        val created = registry.execute("create_schedule", mapOf("task" to "check emails", "when" to "at 9am"))
        assertTrue(created is ToolResult.Success)
        val id = ((created as ToolResult.Success).data as Map<*, *>)["id"] as String

        val listed = registry.execute("list_schedules", emptyMap())
        assertTrue(listed is ToolResult.Success)
        val rows = (listed as ToolResult.Success).data as List<*>
        assertEquals(1, rows.size)
        assertEquals(id, (rows[0] as Map<*, *>)["id"])

        val cancelled = registry.execute("cancel_schedule", mapOf("schedule_id" to id))
        assertTrue(cancelled is ToolResult.Success)

        val listedAgain = registry.execute("list_schedules", emptyMap())
        assertTrue(listedAgain is ToolResult.Success)
        assertTrue(((listedAgain as ToolResult.Success).data as List<*>).isEmpty())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun cancelUnknownIdIsRetryableError() {
        val result = registry().execute("cancel_schedule", mapOf("schedule_id" to "sched_999"))
        assertTrue(result is ToolResult.Failure)
        result as ToolResult.Failure
        assertTrue(result.error.contains("sched_999"))
        assertTrue(result.retryable)
    }

    @Test
    fun cancelMissingIdNamesTheViolation() {
        val result = registry().execute("cancel_schedule", emptyMap())
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("schedule_id"))
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
    fun toolSchemasExposeBothToolsForLlm() {
        val schemas = registry().toolSchemas()
        val names = (0 until schemas.length())
            .map { schemas.getJSONObject(it).getJSONObject("function").getString("name") }
            .toSet()
        assertEquals(setOf("getImportantEmails", "create_schedule", "list_schedules", "cancel_schedule"), names)
    }
}
