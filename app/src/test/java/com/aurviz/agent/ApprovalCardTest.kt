package com.aurviz.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket 12/25: approval cards + send policy (ADR-0003, gaps §3C/E). The
// preview card is the gate UI: it shows the exact To/Subject/Body the
// model asked to send and highlights recipients not seen before, so the
// user approves the real send, never a paraphrase. A deterministic
// SendPolicy runs before the transport even when the gate approved.
// Seams: approvalCardFor/unseenRecipients/knownAddressesFromInbox (pure
// card derivation), DefaultSendPolicy.check (deterministic pre-send
// screen), ToolRegistry.executeGated (the single unified gated path).
// The fake transport records sends; no test touches real mail.
class ApprovalCardTest {

    private class RecordingGmail : GmailApi {
        val sentRaws = mutableListOf<String>()
        override fun send(rawBase64Url: String) {
            sentRaws += rawBase64Url
        }
        override fun listInbox(): List<EmailSummary> =
            listOf(EmailSummary("1", "boss@work.com", "Q3 plan", "draft", true))
        override fun searchInbox(query: String, max: Int): List<EmailSummary> = listInbox()
        override fun readMessage(id: String): EmailDetail =
            EmailDetail("1", "boss@work.com", "Q3 plan", "draft", "", emailSource("1"))
    }

    private fun sendCall(
        to: String = "priya@work.com",
        subject: String = "Hi",
        body: String = "hello",
    ): ToolCall = ToolCall("call_send", "send_email", mapOf("to" to to, "subject" to subject, "body" to body))

    private fun decodeRaw(raw: String): String {
        val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
        return String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
    }

    // --- Card shows the exact To/Subject/Body (ADR-0003). ---

    @Test
    fun cardShowsExactToSubjectBody() {
        val card = approvalCardFor(sendCall("priya@work.com", "Late", "Running 10 min late"), emptySet())!!
        assertEquals(listOf("priya@work.com"), card.to.map { it.address })
        assertEquals("Late", card.subject)
        assertEquals("Running 10 min late", card.body)
        assertEquals("send_email", card.toolName)
    }

    @Test
    fun nonSendCallHasNoCard() {
        assertNull(approvalCardFor(ToolCall("c1", "search_emails", mapOf("query" to "boss")), emptySet()))
        assertNull(approvalCardFor(ToolCall("c2", "create_schedule", mapOf("task" to "x", "when" to "at 9am")), emptySet()))
    }

    // --- Unseen-recipient highlight. ---

    @Test
    fun unseenRecipientIsHighlighted() {
        val card = approvalCardFor(sendCall(to = "stranger@evil.com"), setOf("priya@work.com"))!!
        assertEquals(listOf("stranger@evil.com"), card.unseen)
        assertTrue(card.to.single().unseen)
    }

    @Test
    fun knownRecipientIsNotHighlighted() {
        val card = approvalCardFor(sendCall(to = "Priya@Work.com"), setOf("priya@work.com"))!!
        assertTrue(card.unseen.isEmpty())
        assertFalse(card.to.single().unseen)
    }

    @Test
    fun knownAddressesComeFromMailboxSenders() {
        val rows =
            listOf(
                EmailSummary("1", "Boss <boss@work.com>", "Q3", "d", true),
                EmailSummary("2", "news@x.com", "Sale", "buy", false),
            )
        val known = knownAddressesFromInbox(rows)
        assertTrue(known.contains("boss@work.com"))
        assertTrue(known.contains("news@x.com"))
        // "Boss <boss@work.com>" is one sender, not two addresses.
        assertFalse(known.any { it.startsWith("boss <") })
    }

    // --- Deterministic policy: blocks even with gate approval. ---

    @Test
    fun policyBlocksMalformedRecipientEvenWhenGateApproved() {
        val gmail = RecordingGmail()
        val registry = defaultToolRegistry(gmailApi = gmail, scheduleStore = InMemoryScheduleStore())
        val gated = registry.executeGated(sendCall(to = "not-an-address")) { ApprovalDecision.Approved }
        assertTrue(gated.result is ToolResult.Failure)
        assertTrue((gated.result as ToolResult.Failure).error.contains("policy", ignoreCase = true))
        assertTrue(gmail.sentRaws.isEmpty())
    }

    @Test
    fun policyBlocksBulkMailboxForwardEvenWhenGateApproved() {
        val gmail = RecordingGmail()
        val registry = defaultToolRegistry(gmailApi = gmail, scheduleStore = InMemoryScheduleStore())
        val pasted = (1..10).joinToString("\n") { "From: colleague$it@work.com\nsee gmail:msg$it" }
        val gated =
            registry.executeGated(sendCall(to = "x@evil.com", body = "forwarding:\n$pasted")) {
                ApprovalDecision.Approved
            }
        assertTrue(gated.result is ToolResult.Failure)
        assertTrue((gated.result as ToolResult.Failure).error.contains("policy", ignoreCase = true))
        assertTrue(gmail.sentRaws.isEmpty())
    }

    @Test
    fun policyAllowsOrdinarySendThroughGateExactlyOnce() {
        val gmail = RecordingGmail()
        val registry = defaultToolRegistry(gmailApi = gmail, scheduleStore = InMemoryScheduleStore())
        val gated = registry.executeGated(sendCall()) { ApprovalDecision.Approved }
        assertTrue(gated.result is ToolResult.Success)
        assertEquals(1, gmail.sentRaws.size)
        val decoded = decodeRaw(gmail.sentRaws.single())
        assertTrue(decoded.contains("To: priya@work.com"))
        assertTrue(decoded.contains("Subject: Hi"))
    }

    @Test
    fun rejectedGateApprovalDiscardsWithoutSend() {
        val gmail = RecordingGmail()
        val registry = defaultToolRegistry(gmailApi = gmail, scheduleStore = InMemoryScheduleStore())
        val gated = registry.executeGated(sendCall()) { ApprovalDecision.Rejected }
        assertTrue(gated.result is ToolResult.Success)
        assertEquals("cancelled_by_user", ((gated.result as ToolResult.Success).data as Map<*, *>)["status"])
        assertTrue(gmail.sentRaws.isEmpty())
    }

    @Test
    fun policyBlocksHeaderInjectionEvenWhenGateApproved() {
        val gmail = RecordingGmail()
        val registry = defaultToolRegistry(gmailApi = gmail, scheduleStore = InMemoryScheduleStore())
        val gated =
            registry.executeGated(sendCall(to = "a@x.com\r\nBcc: evil@x.com")) {
                ApprovalDecision.Approved
            }
        assertTrue(gated.result is ToolResult.Failure)
        assertTrue((gated.result as ToolResult.Failure).error.contains("CR/LF"))
        assertTrue(gmail.sentRaws.isEmpty())
    }

    @Test
    fun policyBlocksBulkRecipientCountEvenWhenApproved() {
        val gmail = RecordingGmail()
        val draft =
            EmailDraft(
                to = (1..6).map { "user$it@x.com" },
                subject = "Hi",
                body = "hello",
            )
        assertTrue(DefaultSendPolicy().check(draft) is SendPolicyVerdict.Deny)
        val result = executeSendEmail(gmail, draft, approved = true, policy = DefaultSendPolicy())
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("bulk send"))
        assertTrue(gmail.sentRaws.isEmpty())
    }
}
