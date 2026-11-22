package com.aurviz.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket 04/24 (ticket 17): send_email Tool. Sends are HIGH risk and gated
// per call (ADR-0003): the gate lives in the registry, so the model has no
// path around it — plain execute() always refuses without touching the
// transport, and only executeWithApproval(approved=true) sends. Approval
// travels out-of-band (never an LLM arg, or the model could forge it). A
// no-op SendPolicy seam runs after the gate for the deterministic
// pre-send check (ticket 12/25). Seams: ToolRegistry.execute /
// executeWithApproval round-trips on fakes, domain executeSendEmail over
// GmailApi, pure MIME build on literal expected bytes. The fake transport
// records sends; no test ever touches real mail.
class EmailSendTest {

    private class FakeSendGmail : GmailApi {
        val sent = mutableListOf<String>()
        override fun send(rawBase64Url: String) {
            sent += rawBase64Url
        }
        override fun listInbox(): List<EmailSummary> = emptyList()
        override fun searchInbox(query: String, max: Int): List<EmailSummary> = emptyList()
        override fun readMessage(id: String): EmailDetail =
            throw NoSuchElementException("unknown message: $id")
    }

    private class DenyAllPolicy(val reason: String = "evil-forward hold") : SendPolicy {
        override fun check(draft: EmailDraft): SendPolicyVerdict =
            SendPolicyVerdict.Deny(reason)
    }

    private fun registry(fake: FakeSendGmail, policy: SendPolicy = AllowAllSendPolicy): ToolRegistry =
        defaultToolRegistry(gmailApi = fake, scheduleStore = InMemoryScheduleStore(), sendPolicy = policy)

    private fun sendArgs() = mapOf("to" to "priya@x.com", "subject" to "Late", "body" to "Running 10 min late")

    // --- Schema registration: HIGH risk, gated send (ADR-0003). ---

    @Test
    fun sendEmailSchemaIsHighRiskGatedSend() {
        val tool = registry(FakeSendGmail()).find("send_email")!!
        assertEquals(RiskLevel.HIGH, tool.riskLevel)
        assertTrue(tool.requiresConfirmation)
        assertTrue(tool.resultType.isNotBlank())
        val props = tool.parameters.getJSONObject("properties")
        assertTrue(props.has("to") && props.has("subject") && props.has("body"))
        val required = tool.parameters.getJSONArray("required")
        val names = (0 until required.length()).map { required.getString(it) }.toSet()
        assertEquals(setOf("to", "subject", "body"), names)
    }

    @Test
    fun toolSchemasExposeSendEmailForLlm() {
        val schemas = registry(FakeSendGmail()).toolSchemas()
        val names = (0 until schemas.length())
            .map { schemas.getJSONObject(it).getJSONObject("function").getString("name") }
            .toSet()
        assertTrue(names.contains("send_email"))
    }

    // --- Gate refusal: plain execute() never touches the transport. ---

    @Test
    fun plainExecuteRefusesWithoutGateApproval() {
        val fake = FakeSendGmail()
        val result = registry(fake).execute("send_email", sendArgs())
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("send_email"))
        assertTrue(result.error.contains("confirmation", ignoreCase = true))
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun gatedExecuteRefusedLeavesTransportUntouched() {
        val fake = FakeSendGmail()
        val result = registry(fake).executeWithApproval("send_email", sendArgs(), approved = false)
        assertTrue(result is ToolResult.Failure)
        assertTrue(fake.sent.isEmpty())
    }

    // --- Approved send: exact MIME over the fake transport. ---

    @Test
    fun approvedSendBuildsExactMimeAndSendsOnce() {
        val fake = FakeSendGmail()
        val result = registry(fake).executeWithApproval("send_email", sendArgs(), approved = true)
        assertTrue(result is ToolResult.Success)
        assertEquals(1, fake.sent.size)
        val decoded = String(java.util.Base64.getUrlDecoder().decode(fake.sent[0]), Charsets.UTF_8)
        assertEquals(
            "To: priya@x.com\r\n" +
                "Subject: Late\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "\r\n" +
                "Running 10 min late",
            decoded,
        )
    }

    @Test
    fun approvedSendWithoutGmailFailsClosed() {
        val offline = defaultToolRegistry(gmailApi = null, scheduleStore = InMemoryScheduleStore())
        val result = offline.executeWithApproval("send_email", sendArgs(), approved = true)
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("not connected"))
    }

    @Test
    fun blankSubjectNamesTheViolationAndNeverSends() {
        val fake = FakeSendGmail()
        val result = registry(fake).execute("send_email", mapOf("to" to "a@x.com", "subject" to "  ", "body" to "hi"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("subject"))
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun missingBodyNamesTheViolation() {
        val fake = FakeSendGmail()
        val result = registry(fake).execute("send_email", mapOf("to" to "a@x.com", "subject" to "Hi"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("body"))
        assertTrue(fake.sent.isEmpty())
    }

    // --- Policy-hook seam: deterministic pre-send check runs after the gate. ---

    @Test
    fun denyingPolicyBlocksApprovedSendWithoutTransportTouch() {
        val fake = FakeSendGmail()
        val gated = registry(fake, policy = DenyAllPolicy())
        val result = gated.executeWithApproval("send_email", sendArgs(), approved = true)
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("evil-forward hold"))
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun defaultPolicyAllowsSoGateAloneDecides() {
        assertTrue(AllowAllSendPolicy.check(EmailDraft(listOf("a@x.com"), "Hi", "hello")) is SendPolicyVerdict.Allow)
    }

    @Test
    fun unknownToolOnGatedPathReturnsErrorResult() {
        val result = registry(FakeSendGmail()).executeWithApproval("deleteEverything", emptyMap(), approved = true)
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("unknown tool"))
    }
}
