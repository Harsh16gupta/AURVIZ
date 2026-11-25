package com.aira.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket #70 T5: settled Confirmation receipts (spec #65). Every
// sensitive-tool outcome maps to one explainable receipt so the card and
// the held list read the same canonical language (GLOSSARY Confirmation +
// PendingApproval — never "Approval"). Pure derivation, JVM-tested; the
// Compose card and the held list render receipt.title/receipt.detail.
class ConfirmationReceiptTest {

    private fun sendCall(args: Map<String, Any?> = mapOf("to" to "friend@work.com", "subject" to "Hi", "body" to "hello")) =
        ToolCall("c1", "send_email", args)

    private fun assertCanonicalLanguage(receipt: ConfirmationReceipt) {
        val text = (receipt.title + " " + receipt.detail).lowercase().replace("pendingapproval", "")
        assertFalse("receipt must use Confirmation/PendingApproval language: $receipt", text.contains("approv"))
    }

    // --- ALLOWED: ran with no card (ungated read, allow-always, consent). ---

    @Test
    fun ungatedReadIsAllowed() {
        val receipt = receiptForAllowed(ToolCall("c1", "search_emails", mapOf("query" to "boss")), "read-only fetch")
        assertEquals(ConfirmationOutcome.ALLOWED, receipt.outcome)
        assertTrue(receipt.title.contains("without a Confirmation card"))
        assertTrue(receipt.detail.contains("read-only fetch"))
        assertCanonicalLanguage(receipt)
    }

    @Test
    fun allowAlwaysAutoAllowIsAllowed() {
        val receipt = receiptForAllowed(sendCall(), "allow-always grant for friend@work.com")
        assertEquals(ConfirmationOutcome.ALLOWED, receipt.outcome)
        assertTrue(receipt.detail.contains("allow-always grant for friend@work.com"))
        assertTrue(receipt.detail.contains("Policy checks still applied"))
        assertCanonicalLanguage(receipt)
    }

    @Test
    fun consentedScheduledRunIsAllowed() {
        val receipt = receiptForAllowed(sendCall(), "bounded scheduled consent for sched_1")
        assertEquals(ConfirmationOutcome.ALLOWED, receipt.outcome)
        assertTrue(receipt.detail.contains("bounded scheduled consent for sched_1"))
        assertCanonicalLanguage(receipt)
    }

    // --- CARD_APPROVED vs EDITED_APPROVED: effective args tell them apart. ---

    @Test
    fun approvedWithUnchangedArgsIsCardApproved() {
        val call = sendCall()
        val gated = GatedExecution(ToolResult.Success(mapOf("sent" to true)), call.arguments, approved = true)
        val receipt = receiptForGated(call, gated)
        assertEquals(ConfirmationOutcome.CARD_APPROVED, receipt.outcome)
        assertTrue(receipt.title.contains("Confirmed"))
        assertTrue(receipt.title.contains("send_email"))
        assertCanonicalLanguage(receipt)
    }

    @Test
    fun approvedWithEditedArgsIsEditedApproved() {
        val call = sendCall()
        val edited = mapOf("to" to "friend@work.com", "subject" to "Hi (edited)", "body" to "hello")
        val gated = GatedExecution(ToolResult.Success(mapOf("sent" to true)), edited, approved = true)
        val receipt = receiptForGated(call, gated)
        assertEquals(ConfirmationOutcome.EDITED_APPROVED, receipt.outcome)
        assertTrue(receipt.title.contains("edits"))
        assertCanonicalLanguage(receipt)
    }

    // --- DENIED: reject, timeout, and policy failure each explain why. ---

    @Test
    fun rejectedCardIsDenied() {
        val call = sendCall()
        val gated =
            GatedExecution(
                ToolResult.Success(mapOf("status" to "cancelled_by_user")),
                call.arguments,
                approved = false,
            )
        val receipt = receiptForGated(call, gated)
        assertEquals(ConfirmationOutcome.DENIED, receipt.outcome)
        assertTrue(receipt.title.contains("nothing ran"))
        assertTrue(receipt.detail.contains("denied"))
        assertCanonicalLanguage(receipt)
    }

    @Test
    fun timedOutCardIsDeniedWithTimeoutReason() {
        val call = sendCall()
        val gated =
            GatedExecution(
                ToolResult.Success(mapOf("status" to "cancelled_by_user", "reason" to "confirmation_timed_out")),
                call.arguments,
                approved = false,
            )
        val receipt = receiptForGated(call, gated)
        assertEquals(ConfirmationOutcome.DENIED, receipt.outcome)
        assertTrue(receipt.detail.contains("timed out"))
        assertCanonicalLanguage(receipt)
    }

    @Test
    fun policyFailureIsDeniedWithPolicyReason() {
        val call = sendCall(mapOf("to" to "x@evil.com", "subject" to "F", "body" to "bulk"))
        val gated =
            GatedExecution(
                ToolResult.Failure("tool 'send_email' blocked by policy: bulk send", retryable = false),
                call.arguments,
                approved = false,
            )
        val receipt = receiptForGated(call, gated)
        assertEquals(ConfirmationOutcome.DENIED, receipt.outcome)
        assertTrue(receipt.detail.contains("policy"))
        assertCanonicalLanguage(receipt)
    }

    // --- HELD: a scheduled attempt with no one present. ---

    @Test
    fun scheduledAttemptIsHeld() {
        val held =
            PendingApproval(
                scheduleId = "sched_1",
                toolName = "send_email",
                arguments = sendCall().arguments,
                reason = "scheduled run: sensitive tool held for an in-app Confirmation decision, never executed",
            )
        val receipt = receiptForHeld(held)
        assertEquals(ConfirmationOutcome.HELD, receipt.outcome)
        assertTrue(receipt.title.contains("Held"))
        assertTrue(receipt.detail.contains("send_email"))
        assertTrue(receipt.detail.lowercase().contains("nothing ran yet"))
        assertCanonicalLanguage(receipt)
    }

    @Test
    fun heldScheduleWriteNamesTheTool() {
        val held =
            PendingApproval(
                scheduleId = "sched_9",
                toolName = "create_schedule",
                arguments = mapOf("task" to "spam", "when" to "at 9am"),
                reason = "scheduled run: sensitive tool held for an in-app Confirmation decision, never executed",
            )
        val receipt = receiptForHeld(held)
        assertEquals(ConfirmationOutcome.HELD, receipt.outcome)
        assertTrue(receipt.detail.contains("create_schedule"))
        assertCanonicalLanguage(receipt)
    }

    // --- Every outcome carries an explainable title + detail. ---

    @Test
    fun everyOutcomeExplainsItself() {
        val receipts =
            listOf(
                receiptForAllowed(sendCall(), "read-only fetch"),
                receiptForGated(sendCall(), GatedExecution(ToolResult.Success(mapOf("sent" to true)), sendCall().arguments, approved = true)),
                receiptForGated(
                    sendCall(),
                    GatedExecution(ToolResult.Success(mapOf("sent" to true)), mapOf("to" to "a@b.c", "subject" to "s", "body" to "b"), approved = true),
                ),
                receiptForGated(
                    sendCall(),
                    GatedExecution(ToolResult.Success(mapOf("status" to "cancelled_by_user")), sendCall().arguments, approved = false),
                ),
                receiptForHeld(PendingApproval("sched_1", "send_email", sendCall().arguments, "held")),
            )
        assertEquals(
            listOf(
                ConfirmationOutcome.ALLOWED,
                ConfirmationOutcome.CARD_APPROVED,
                ConfirmationOutcome.EDITED_APPROVED,
                ConfirmationOutcome.DENIED,
                ConfirmationOutcome.HELD,
            ),
            receipts.map { it.outcome },
        )
        for (receipt in receipts) {
            assertTrue("title must read like an explanation: $receipt", receipt.title.isNotBlank())
            assertTrue("detail must read like an explanation: $receipt", receipt.detail.isNotBlank())
            assertCanonicalLanguage(receipt)
        }
    }
}
