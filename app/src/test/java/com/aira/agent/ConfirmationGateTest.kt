package com.aira.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Ticket 09/24: Confirmation gate (ADR-0003, gaps §3C). The gate lives in
// the ToolRegistry, so the model has no path around it: any Tool with
// requiresConfirmation=true suspends the loop until the UI returns
// approve, edited-approve, reject, or timeout. Reject/timeout return
// {status:"cancelled_by_user"} so the model recovers and the loop
// continues or ends cleanly. Seams: ToolRegistry.executeGated (gate
// decisions + bypass refusal) and AgentLoop.run with a fake approver
// (suspend/resume on all four paths). Real UI suspension is ticket 24;
// real timeouts are ticket 23.
class ConfirmationGateTest {

    private class RecordingGmail : GmailApi {
        val sentRaws = mutableListOf<String>()

        override fun send(rawBase64Url: String) {
            sentRaws += rawBase64Url
        }

        override fun listInbox(): List<EmailSummary> =
            listOf(EmailSummary("1", "boss@work.com", "Q3 plan", "draft", true))

        override fun searchInbox(query: String, max: Int): List<EmailSummary> =
            listOf(EmailSummary("1", "boss@work.com", "Q3 plan", "draft", true))

        override fun readMessage(id: String): EmailDetail =
            EmailDetail("1", "boss@work.com", "Q3 plan", "draft", "", emailSource("1"))
    }

    private fun sendArgs(body: String = "hello"): Map<String, Any?> =
        mapOf("to" to "priya@work.com", "subject" to "Hi", "body" to body)

    private fun sendCall(args: Map<String, Any?> = sendArgs()): ToolCall =
        ToolCall("call_send", "send_email", args)

    private fun registry(gmail: RecordingGmail = RecordingGmail()): ToolRegistry =
        defaultToolRegistry(gmailApi = gmail, scheduleStore = InMemoryScheduleStore())

    private fun decodeRaw(raw: String): String {
        val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
        return String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
    }

    private fun answer(text: String) = LlmResult(toolCalls = emptyList(), content = text)

    // --- Registry gate: approve path ---

    @Test
    fun approvedGatedSendRunsExecutor() {
        val gmail = RecordingGmail()
        val gated = registry(gmail).executeGated(sendCall()) { ApprovalDecision.Approved }
        assertTrue(gated.result is ToolResult.Success)
        assertEquals(sendArgs(), gated.effectiveArgs)
        assertTrue(gated.approved)
        assertEquals(1, gmail.sentRaws.size)
        assertTrue(decodeRaw(gmail.sentRaws[0]).contains("Subject: Hi"))
    }

    @Test
    fun editedApproveRunsExecutorWithEditedArgs() {
        val gmail = RecordingGmail()
        val edited = sendArgs().toMutableMap()
        edited["subject"] = "Edited subject"
        val gated = registry(gmail).executeGated(sendCall()) { ApprovalDecision.ApprovedWithEdit(edited) }
        assertTrue(gated.result is ToolResult.Success)
        assertEquals("Edited subject", gated.effectiveArgs["subject"])
        assertTrue(gated.approved)
        assertEquals(1, gmail.sentRaws.size)
        assertTrue(decodeRaw(gmail.sentRaws[0]).contains("Subject: Edited subject"))
    }

    @Test
    fun editedApproveWithInvalidArgsFailsWithoutRunning() {
        val gmail = RecordingGmail()
        var consulted = false
        val gated =
            registry(gmail).executeGated(sendCall()) {
                consulted = true
                ApprovalDecision.ApprovedWithEdit(mapOf("to" to "priya@work.com", "subject" to "Hi"))
            }
        assertTrue(consulted)
        assertTrue(gated.result is ToolResult.Failure)
        assertTrue((gated.result as ToolResult.Failure).error.contains("body"))
        assertTrue(gmail.sentRaws.isEmpty())
    }

    // --- Registry gate: reject + timeout paths ---

    @Test
    fun rejectedSendReturnsCancelledByUserAndNeverRuns() {
        val gmail = RecordingGmail()
        val gated = registry(gmail).executeGated(sendCall()) { ApprovalDecision.Rejected }
        assertTrue(gated.result is ToolResult.Success)
        assertEquals(
            "cancelled_by_user",
            ((gated.result as ToolResult.Success).data as Map<*, *>)["status"],
        )
        assertFalse(gated.approved)
        assertTrue(gmail.sentRaws.isEmpty())
    }

    @Test
    fun timedOutSendReturnsCancelledByUserAndNeverRuns() {
        val gmail = RecordingGmail()
        val gated = registry(gmail).executeGated(sendCall()) { ApprovalDecision.TimedOut }
        assertTrue(gated.result is ToolResult.Success)
        @Suppress("UNCHECKED_CAST")
        val data = (gated.result as ToolResult.Success).data as Map<String, Any?>
        assertEquals("cancelled_by_user", data["status"])
        assertFalse(gated.approved)
        assertTrue(gmail.sentRaws.isEmpty())
    }

    // --- Registry gate: no path around it ---

    @Test
    fun directExecuteRefusesGatedToolWithoutRunning() {
        val gmail = RecordingGmail()
        val registry = registry(gmail)
        val sendRefused = registry.execute("send_email", sendArgs())
        assertTrue(sendRefused is ToolResult.Failure)
        assertTrue((sendRefused as ToolResult.Failure).error.contains("Confirmation"))
        assertFalse(sendRefused.retryable)

        val scheduleRefused = registry.execute("create_schedule", mapOf("task" to "x", "when" to "at 9am"))
        assertTrue(scheduleRefused is ToolResult.Failure)
        assertTrue((scheduleRefused as ToolResult.Failure).error.contains("Confirmation"))
        assertTrue(gmail.sentRaws.isEmpty())
    }

    @Test
    fun ungatedToolRunsWithoutConsultingApprover() {
        val registry = registry()
        val gated =
            registry.executeGated(ToolCall("c1", "search_emails", mapOf("query" to "boss"))) {
                fail("approver must not be consulted for ungated Tools")
                ApprovalDecision.Rejected
            }
        assertTrue(gated.result is ToolResult.Success)
        assertTrue(gated.approved)
    }

    @Test
    fun invalidArgsNeverReachApproverOrExecutor() {
        val gmail = RecordingGmail()
        var consulted = false
        val gated =
            registry(gmail).executeGated(ToolCall("c1", "send_email", mapOf("to" to "x"))) {
                consulted = true
                ApprovalDecision.Approved
            }
        assertFalse(consulted)
        assertTrue(gated.result is ToolResult.Failure)
        assertTrue((gated.result as ToolResult.Failure).error.contains("subject"))
        assertTrue(gmail.sentRaws.isEmpty())
    }

    // --- Loop: suspends/resumes on all four paths ---

    private fun loopRegistry(gmail: RecordingGmail): ToolRegistry =
        defaultToolRegistry(gmailApi = gmail, scheduleStore = InMemoryScheduleStore())

    private fun loopLlm(vararg turns: LlmResult) = FakeLlmProvider(turns.toList())

    @Test
    fun loopResumesAndSendsOnApprove() {
        val gmail = RecordingGmail()
        val llm = loopLlm(LlmResult(listOf(sendCall()), null), answer("Sent!"))
        val events = mutableListOf<AgentEvent>()
        val result =
            AgentLoop(llm, loopRegistry(gmail), onEvent = events::add, confirm = { ApprovalDecision.Approved })
                .run("email Priya")
        assertTrue(result is AgentLoopResult.Answered)
        assertEquals("Sent!", (result as AgentLoopResult.Answered).answer)
        assertEquals(1, gmail.sentRaws.size)
        assertTrue(result.steps[0].approved)
        assertTrue(events.any { it is AgentEvent.NeedsApproval })
    }

    @Test
    fun loopContinuesCleanlyAfterReject() {
        val gmail = RecordingGmail()
        val llm = loopLlm(LlmResult(listOf(sendCall()), null), answer("OK, I won't send it"))
        val events = mutableListOf<AgentEvent>()
        val result =
            AgentLoop(llm, loopRegistry(gmail), onEvent = events::add, confirm = { ApprovalDecision.Rejected })
                .run("email Priya")
        assertTrue(result is AgentLoopResult.Answered)
        assertEquals("OK, I won't send it", (result as AgentLoopResult.Answered).answer)
        assertTrue(gmail.sentRaws.isEmpty())
        assertFalse(result.steps[0].approved)
        val toolMessage = result.messages.first { it.role == "tool" }
        assertEquals(
            "cancelled_by_user",
            JSONObject(toolMessage.content.orEmpty()).getJSONObject("data").getString("status"),
        )
        assertTrue(events.any { it is AgentEvent.NeedsApproval })
    }

    @Test
    fun loopContinuesCleanlyAfterTimeout() {
        val gmail = RecordingGmail()
        val llm = loopLlm(LlmResult(listOf(sendCall()), null), answer("No answer, moving on"))
        val result =
            AgentLoop(llm, loopRegistry(gmail), confirm = { ApprovalDecision.TimedOut }).run("email Priya")
        assertTrue(result is AgentLoopResult.Answered)
        assertTrue(gmail.sentRaws.isEmpty())
        assertFalse(result.steps[0].approved)
        val toolMessage = result.messages.first { it.role == "tool" }
        assertEquals(
            "cancelled_by_user",
            JSONObject(toolMessage.content.orEmpty()).getJSONObject("data").getString("status"),
        )
    }

    @Test
    fun loopSendsEditedArgsOnEditedApprove() {
        val gmail = RecordingGmail()
        val llm = loopLlm(LlmResult(listOf(sendCall()), null), answer("Sent with edits"))
        val edited = sendArgs().toMutableMap()
        edited["body"] = "edited body"
        val result =
            AgentLoop(llm, loopRegistry(gmail), confirm = { ApprovalDecision.ApprovedWithEdit(edited) })
                .run("email Priya")
        assertTrue(result is AgentLoopResult.Answered)
        assertEquals(1, gmail.sentRaws.size)
        assertTrue(decodeRaw(gmail.sentRaws[0]).contains("edited body"))
        assertEquals("edited body", result.steps[0].call.arguments["body"])
        assertTrue(result.steps[0].approved)
    }

    @Test
    fun loopDoesNotRaiseApprovalCardForReads() {
        val gmail = RecordingGmail()
        val llm =
            loopLlm(
                LlmResult(listOf(ToolCall("c1", "search_emails", mapOf("query" to "boss"))), null),
                answer("Found it"),
            )
        val events = mutableListOf<AgentEvent>()
        var consulted = false
        val result =
            AgentLoop(llm, loopRegistry(gmail), onEvent = events::add, confirm = {
                consulted = true
                ApprovalDecision.Approved
            }).run("check mail")
        assertTrue(result is AgentLoopResult.Answered)
        assertFalse(consulted)
        assertTrue(events.none { it is AgentEvent.NeedsApproval })
    }
}
