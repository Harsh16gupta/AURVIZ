package com.aira.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket 03/24 (ticket 16): search_emails + read_email Tools.
// Reads are LOW risk and ungated (ADR-0003); every result carries its
// mailbox source tag so the injection defences (ticket 16) can treat
// mail as data, never instructions. Seams: ToolRegistry.execute
// round-trip on fakes, domain search/read over GmailApi, pure full-
// message parsing on literal Gmail JSON.
class EmailSearchReadTest {

    private class FakeSearchGmail(
        private val inbox: List<EmailSummary> = emptyList(),
        private val bodies: Map<String, String> = emptyMap(),
    ) : GmailApi {
        override fun send(rawBase64Url: String) = Unit

        override fun listInbox(): List<EmailSummary> = inbox.toList()

        override fun searchInbox(query: String, max: Int): List<EmailSummary> =
            inbox.filter {
                it.from.contains(query, ignoreCase = true) ||
                    it.subject.contains(query, ignoreCase = true) ||
                    it.snippet.contains(query, ignoreCase = true)
            }.take(max.coerceIn(1, 25))

        override fun readMessage(id: String): EmailDetail {
            val row = inbox.firstOrNull { it.id == id }
                ?: throw NoSuchElementException("unknown message: $id")
            return EmailDetail(
                id = row.id,
                from = row.from,
                subject = row.subject,
                snippet = row.snippet,
                body = bodies[id].orEmpty(),
                source = emailSource(row.id),
            )
        }
    }

    private val inbox =
        listOf(
            EmailSummary("m1", "boss@work.com", "Q3 plan review", "please check the plan", true),
            EmailSummary("m2", "news@x.com", "Weekend sale", "buy now", false),
            EmailSummary("m3", "boss@work.com", "Re: Q3 plan", "looks good", false),
        )

    private fun registry(): ToolRegistry =
        defaultToolRegistry(
            gmailApi = FakeSearchGmail(inbox, mapOf("m1" to "Hello world")),
            scheduleStore = InMemoryScheduleStore(),
        )

    // --- Schema registration: LOW risk, ungated reads (ADR-0003). ---

    @Test
    fun searchEmailsSchemaIsLowRiskUngatedRead() {
        val tool = registry().find("search_emails")!!
        assertEquals(RiskLevel.LOW, tool.riskLevel)
        assertFalse(tool.requiresConfirmation)
        assertTrue(tool.resultType.isNotBlank())
        val props = tool.parameters.getJSONObject("properties")
        assertTrue(props.has("query") && props.has("max"))
        val required = tool.parameters.getJSONArray("required")
        assertTrue((0 until required.length()).any { required.getString(it) == "query" })
    }

    @Test
    fun readEmailSchemaIsLowRiskUngatedRead() {
        val tool = registry().find("read_email")!!
        assertEquals(RiskLevel.LOW, tool.riskLevel)
        assertFalse(tool.requiresConfirmation)
        assertTrue(tool.resultType.isNotBlank())
        assertTrue(tool.parameters.getJSONObject("properties").has("id"))
        val required = tool.parameters.getJSONArray("required")
        assertTrue((0 until required.length()).any { required.getString(it) == "id" })
    }

    // --- Executor round-trips on fakes, results source-tagged. ---

    @Test
    fun searchExecutorReturnsSourceTaggedRows() {
        val result = registry().execute("search_emails", mapOf("query" to "boss", "max" to 10))
        assertTrue(result is ToolResult.Success)
        @Suppress("UNCHECKED_CAST")
        val rows = (result as ToolResult.Success).data as List<Map<String, Any?>>
        assertEquals(2, rows.size)
        assertTrue(rows.all { (it["source"] as String).startsWith("gmail:") })
        assertEquals("gmail:m1", rows[0]["source"])
    }

    @Test
    fun readExecutorReturnsBodyAndSourceTag() {
        val result = registry().execute("read_email", mapOf("id" to "m1"))
        assertTrue(result is ToolResult.Success)
        @Suppress("UNCHECKED_CAST")
        val row = (result as ToolResult.Success).data as Map<String, Any?>
        assertEquals("Hello world", row["body"])
        assertEquals("gmail:m1", row["source"])
        assertEquals("boss@work.com", row["from"])
    }

    @Test
    fun readUnknownIdBecomesErrorResultNotCrash() {
        val result = registry().execute("read_email", mapOf("id" to "nope"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("read_email"))
    }

    @Test
    fun blankQueryIsRejected() {
        val result = registry().execute("search_emails", mapOf("query" to "  ", "max" to 5))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("query"))
    }

    @Test
    fun missingIdNamesTheViolation() {
        val result = registry().execute("read_email", emptyMap())
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("id"))
    }

    @Test
    fun searchWithoutGmailFailsClosed() {
        val offline = defaultToolRegistry(gmailApi = null, scheduleStore = InMemoryScheduleStore())
        val result = offline.execute("search_emails", mapOf("query" to "boss", "max" to 5))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("not connected"))
    }

    @Test
    fun toolSchemasExposeSearchAndReadForLlm() {
        val schemas = registry().toolSchemas()
        val names = (0 until schemas.length())
            .map { schemas.getJSONObject(it).getJSONObject("function").getString("name") }
            .toSet()
        assertTrue(names.containsAll(setOf("search_emails", "read_email")))
    }

    // --- Domain search/read over the GmailApi seam. ---

    @Test
    fun searchDelegatesQueryAndClampsMax() {
        val many = (1..30).associate { "m$it" to "row $it" }
        val fake =
            FakeSearchGmail(
                many.keys.map { id -> EmailSummary(id, "a@x.com", "Subject $id", "snippet", false) },
                many,
            )
        val found = searchEmails(fake, "subject", 100)
        assertEquals(25, found.size)
    }

    @Test
    fun readReturnsDetailWithSourceTag() {
        val detail = readEmail(FakeSearchGmail(inbox, mapOf("m2" to "sale body")), "m2")
        assertEquals("m2", detail.id)
        assertEquals("sale body", detail.body)
        assertEquals("gmail:m2", detail.source)
    }

    // --- Pure full-message parsing on literal Gmail JSON. ---

    @Test
    fun decodeGmailBodyHandlesUnpaddedBase64Url() {
        // "Hello world?+/" would carry + / = in std base64; url-safe has none.
        val raw = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("price: 5€ / qty=3".toByteArray(Charsets.UTF_8))
        assertFalse(raw.contains("+") || raw.contains("/") || raw.contains("="))
        assertEquals("price: 5€ / qty=3", decodeGmailBody(raw))
    }

    @Test
    fun parseMessageFullExtractsMultipartPlainBody() {
        val hello = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("Hello world".toByteArray(Charsets.UTF_8))
        val json =
            """
            {"id":"m9","snippet":"hello","labelIds":["INBOX"],
             "payload":{"mimeType":"multipart/alternative",
              "headers":[{"name":"From","value":"boss@x.com"},{"name":"Subject","value":"Hi"}],
              "parts":[
               {"mimeType":"text/plain","body":{"data":"$hello"}},
               {"mimeType":"text/html","body":{"data":"$hello"}}
              ]}}
            """.trimIndent()
        val detail = parseMessageFull(json)
        assertEquals("m9", detail.id)
        assertEquals("boss@x.com", detail.from)
        assertEquals("Hi", detail.subject)
        assertEquals("Hello world", detail.body)
        assertEquals("gmail:m9", detail.source)
    }
}
