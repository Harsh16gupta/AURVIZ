package com.aurviz.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket 72 (part of #71): Gmail adapter pin. The adapter owns the three
// Gmail Tool declarations and registers them into the validate-and-gate
// kernel; behavior is exercised through the kernel seam (execute,
// executeGated, schema view), never by file location. Fake Gmail only.
class GmailToolsTest {

    private class FakeGmail : GmailApi {
        val sent = mutableListOf<String>()
        override fun send(rawBase64Url: String) {
            sent += rawBase64Url
        }
        override fun listInbox(): List<EmailSummary> =
            listOf(EmailSummary("m1", "boss@work.com", "Q3 plan", "draft", true))
        override fun searchInbox(query: String, max: Int): List<EmailSummary> =
            listInbox().take(max.coerceIn(1, 25))
        override fun readMessage(id: String): EmailDetail {
            val row = listInbox().firstOrNull { it.id == id }
                ?: throw NoSuchElementException("unknown message: $id")
            return EmailDetail(row.id, row.from, row.subject, row.snippet, "body", emailSource(row.id))
        }
    }

    private fun kernelWithGmail(fake: GmailApi?): ToolRegistry =
        ToolRegistry().apply {
            for (tool in gmailTools(fake)) register(tool)
        }

    @Test
    fun adapterRegistersSearchReadAndSendIntoTheKernel() {
        val kernel = kernelWithGmail(FakeGmail())
        assertEquals(setOf("search_emails", "read_email", "send_email"), kernel.names())
    }

    @Test
    fun readsStayUngatedLowRiskAndSendStaysGatedHighRisk() {
        val kernel = kernelWithGmail(FakeGmail())
        val search = kernel.find("search_emails")!!
        assertEquals(RiskLevel.LOW, search.riskLevel)
        assertFalse(search.requiresConfirmation)
        val read = kernel.find("read_email")!!
        assertEquals(RiskLevel.LOW, read.riskLevel)
        assertFalse(read.requiresConfirmation)
        val send = kernel.find("send_email")!!
        assertEquals(RiskLevel.HIGH, send.riskLevel)
        assertTrue(send.requiresConfirmation)
    }

    @Test
    fun searchAndReadBehaveThroughTheKernelSeam() {
        val kernel = kernelWithGmail(FakeGmail())
        val search = kernel.execute("search_emails", mapOf("query" to "boss"))
        assertTrue(search is ToolResult.Success)
        @Suppress("UNCHECKED_CAST")
        val rows = (search as ToolResult.Success).data as List<Map<String, Any?>>
        assertEquals("gmail:m1", rows.single()["source"])
        val read = kernel.execute("read_email", mapOf("id" to "m1"))
        assertTrue(read is ToolResult.Success)
        @Suppress("UNCHECKED_CAST")
        val row = (read as ToolResult.Success).data as Map<String, Any?>
        assertEquals("body", row["body"])
    }

    @Test
    fun sendStillRequiresPerCallConfirmation() {
        val fake = FakeGmail()
        val kernel = kernelWithGmail(fake)
        val args = mapOf("to" to "priya@x.com", "subject" to "Hi", "body" to "hello")
        // Ungated path refuses without touching the transport.
        val refused = kernel.execute("send_email", args)
        assertTrue(refused is ToolResult.Failure)
        assertTrue(fake.sent.isEmpty())
        // Reject cancels without running the sender.
        val rejected =
            kernel.executeGated(ToolCall("c1", "send_email", args)) { ApprovalDecision.Rejected }
        assertTrue(rejected.result is ToolResult.Success)
        @Suppress("UNCHECKED_CAST")
        val cancelled = (rejected.result as ToolResult.Success).data as Map<String, Any?>
        assertEquals("cancelled_by_user", cancelled["status"])
        assertFalse(rejected.approved)
        assertTrue(fake.sent.isEmpty())
        // Timeout cancels the same way, with the timeout reason.
        val timedOut =
            kernel.executeGated(ToolCall("c1b", "send_email", args)) { ApprovalDecision.TimedOut }
        assertTrue(timedOut.result is ToolResult.Success)
        @Suppress("UNCHECKED_CAST")
        val timedOutData = (timedOut.result as ToolResult.Success).data as Map<String, Any?>
        assertEquals("cancelled_by_user", timedOutData["status"])
        assertEquals("confirmation_timed_out", timedOutData["reason"])
        assertFalse(timedOut.approved)
        assertTrue(fake.sent.isEmpty())
        // Approve sends exactly once.
        val approved =
            kernel.executeGated(ToolCall("c2", "send_email", args)) { ApprovalDecision.Approved }
        assertTrue(approved.result is ToolResult.Success)
        assertTrue(approved.approved)
        assertEquals(1, fake.sent.size)
    }

    @Test
    fun missingConnectionStillFailsClosed() {
        val kernel = kernelWithGmail(null)
        val search = kernel.execute("search_emails", mapOf("query" to "boss"))
        assertTrue(search is ToolResult.Failure)
        assertFalse((search as ToolResult.Failure).retryable)
        val read = kernel.execute("read_email", mapOf("id" to "m1"))
        assertTrue(read is ToolResult.Failure)
        assertTrue((read as ToolResult.Failure).error.contains("not connected"))
        assertFalse(read.retryable)
        val gated =
            kernel.executeGated(
                ToolCall("c1", "send_email", mapOf("to" to "a@x.com", "subject" to "Hi", "body" to "hello")),
            ) { ApprovalDecision.Approved }
        assertTrue(gated.result is ToolResult.Failure)
        assertFalse((gated.result as ToolResult.Failure).retryable)
    }

    @Test
    fun schemaViewAdvertisesAllThreeTools() {
        val names =
            kernelWithGmail(FakeGmail()).toolSchemas().let { schemas ->
                (0 until schemas.length())
                    .map { schemas.getJSONObject(it).getJSONObject("function").getString("name") }
                    .toSet()
            }
        assertEquals(setOf("search_emails", "read_email", "send_email"), names)
    }
}
