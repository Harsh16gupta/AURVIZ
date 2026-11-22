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
        val emails = registry.find("getImportantEmails")!!
        assertEquals(RiskLevel.LOW, emails.riskLevel)
        assertFalse(emails.requiresConfirmation)
        assertTrue(emails.resultType.isNotBlank())
        assertTrue(emails.parameters.getJSONObject("properties").has("max_results"))

        // ADR-0003: create_schedule is a sensitive Tool, gated per call.
        val schedule = registry.find("createSchedule")!!
        assertEquals(RiskLevel.MEDIUM, schedule.riskLevel)
        assertTrue(schedule.requiresConfirmation)
        assertTrue(schedule.resultType.isNotBlank())
        val props = schedule.parameters.getJSONObject("properties")
        assertTrue(props.has("task") && props.has("when"))
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
        val result = registry.execute("createSchedule", mapOf("task" to "check emails", "when" to "at 9am"))
        assertTrue(result is ToolResult.Success)
        assertEquals(1, store.list().size)
    }

    @Test
    fun unparseableWhenNamesTheViolation() {
        val result = registry().execute("createSchedule", mapOf("task" to "check emails", "when" to "someday maybe"))
        assertTrue(result is ToolResult.Failure)
        result as ToolResult.Failure
        assertTrue(result.error.contains("when"))
        assertTrue(result.retryable)
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
        assertEquals(
            setOf(
                "search_emails",
                "read_email",
                "getImportantEmails",
                "createSchedule",
                "save_memory",
                "search_memory",
                "open_url",
                "get_page_text",
                "click",
                "scroll",
                "extract",
            ),
            names,
        )
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
}
