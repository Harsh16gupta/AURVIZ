package com.aurviz.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket #86: Tool names shown as human labels — no raw snake_case Tool ID
// may leak into UI labels. Seam: toolLabel().
class ToolLabelMapperTest {

    private object EmptyGmail : GmailApi {
        override fun send(rawBase64Url: String) = Unit
        override fun listInbox(): List<EmailSummary> = emptyList()
        override fun searchInbox(query: String, max: Int): List<EmailSummary> = emptyList()
        override fun readMessage(id: String): EmailDetail =
            EmailDetail(id, "", "", "", "", "")
    }

    private fun registry(): ToolRegistry =
        defaultToolRegistry(
            gmailApi = EmptyGmail,
            scheduleStore = InMemoryScheduleStore(),
            memoryStore = InMemoryStore(),
        )

    @Test
    fun `spec examples map to human labels`() {
        assertEquals("Send email", toolLabel("send_email"))
        assertEquals("Create schedule", toolLabel("create_schedule"))
        assertEquals("Cancel schedule", toolLabel("cancel_schedule"))
    }

    @Test
    fun `every registered tool maps to a clean label`() {
        val names = registry().names()
        assertTrue("registry must expose tools for the label audit", names.isNotEmpty())
        names.forEach { name ->
            val label = toolLabel(name)
            assertTrue("label must be human-readable: $name", label.isNotBlank())
            assertFalse("raw tool ID leaked into label: $name", label.contains("_"))
            assertEquals("label must be trimmed: $name", label, label.trim())
        }
    }

    @Test
    fun `unknown tool ids fall back to title-cased words`() {
        assertEquals("Unknown tool id", toolLabel("unknown_tool_id"))
        assertEquals("Select item", toolLabel("click"))
    }
}
