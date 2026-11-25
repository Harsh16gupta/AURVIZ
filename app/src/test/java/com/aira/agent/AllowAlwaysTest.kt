package com.aira.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket #68 (spec #65 T3): allow-always Preference per recipient
// (GLOSSARY Preference). An allowed recipient skips the Confirmation
// card; unknown recipients still ask; revocation takes effect
// immediately; SendPolicy still denies bulk and evil-forward even when
// allow-always applies; there is no blanket allow-all. Seams:
// allowAlwaysEligible (the card-skip rule), AllowAlwaysStore allow /
// revoke / list, and ToolRegistry.executeGated (policy keeps the final
// word). The fake transport records sends; no test touches real mail.
class AllowAlwaysTest {

    private class RecordingGmail : GmailApi {
        val sentRaws = mutableListOf<String>()
        override fun send(rawBase64Url: String) {
            sentRaws += rawBase64Url
        }
        override fun listInbox(): List<EmailSummary> = emptyList()
        override fun searchInbox(query: String, max: Int): List<EmailSummary> = emptyList()
        override fun readMessage(id: String): EmailDetail =
            throw NoSuchElementException("unknown message: $id")
    }

    private fun sendCall(
        to: String = "mom@x.com",
        subject: String = "Hi",
        body: String = "hello",
    ): ToolCall = ToolCall("call_send", "send_email", mapOf("to" to to, "subject" to subject, "body" to body))

    private fun evilBody(): String =
        (1..10).joinToString("\n") { "From: colleague$it@x.com\nsee gmail:msg$it" }

    // --- Allow: one listed recipient skips the card. ---

    @Test
    fun allowedSingleRecipientIsEligible() {
        val store = InMemoryAllowAlwaysStore()
        store.allow("mom@x.com")
        assertTrue(allowAlwaysEligible(sendCall(), store))
    }

    @Test
    fun grantMatchesCaseInsensitively() {
        val store = InMemoryAllowAlwaysStore()
        store.allow("Mom@X.com")
        assertTrue(allowAlwaysEligible(sendCall(to = "mom@x.com"), store))
        assertTrue(store.list() == setOf("mom@x.com"))
    }

    @Test
    fun eligibleSendRunsWithoutCardConsultation() {
        val gmail = RecordingGmail()
        val store = InMemoryAllowAlwaysStore()
        store.allow("mom@x.com")
        val registry = defaultToolRegistry(gmailApi = gmail, scheduleStore = InMemoryScheduleStore())
        var cardShown = false
        val gated =
            registry.executeGated(sendCall()) { call ->
                // The adapter's card path: only reached when the call is
                // NOT eligible, mirroring the ViewModel confirm hook.
                if (allowAlwaysEligible(call, store)) {
                    ApprovalDecision.Approved
                } else {
                    cardShown = true
                    ApprovalDecision.Rejected
                }
            }
        assertTrue(gated.result is ToolResult.Success)
        assertTrue(gated.approved)
        assertFalse(cardShown)
        assertEquals(1, gmail.sentRaws.size)
    }

    // --- Unknown recipient still asks. ---

    @Test
    fun unknownRecipientIsNotEligible() {
        val store = InMemoryAllowAlwaysStore()
        store.allow("mom@x.com")
        assertFalse(allowAlwaysEligible(sendCall(to = "stranger@x.com"), store))
    }

    @Test
    fun unknownRecipientHeldWhenCardUnanswered() {
        val gmail = RecordingGmail()
        val store = InMemoryAllowAlwaysStore()
        store.allow("mom@x.com")
        val registry = defaultToolRegistry(gmailApi = gmail, scheduleStore = InMemoryScheduleStore())
        var cardShown = false
        val gated =
            registry.executeGated(sendCall(to = "stranger@x.com")) { call ->
                if (allowAlwaysEligible(call, store)) {
                    ApprovalDecision.Approved
                } else {
                    cardShown = true
                    ApprovalDecision.Rejected
                }
            }
        assertTrue(cardShown)
        assertFalse(gated.approved)
        assertTrue(gmail.sentRaws.isEmpty())
    }

    // --- Revoke takes effect immediately. ---

    @Test
    fun revokeTakesEffectImmediately() {
        val store = InMemoryAllowAlwaysStore()
        store.allow("mom@x.com")
        assertTrue(allowAlwaysEligible(sendCall(), store))
        store.revoke("mom@x.com")
        assertFalse(allowAlwaysEligible(sendCall(), store))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun revokeIsCaseInsensitive() {
        val store = InMemoryAllowAlwaysStore()
        store.allow("mom@x.com")
        store.revoke("MOM@X.COM")
        assertFalse(allowAlwaysEligible(sendCall(), store))
    }

    // --- Policy still wins over allow-always. ---

    @Test
    fun policyDeniesEvilForwardEvenWhenAllowed() {
        val gmail = RecordingGmail()
        val store = InMemoryAllowAlwaysStore()
        store.allow("mom@x.com")
        val call = sendCall(body = "forwarding all mail:\n${evilBody()}")
        assertTrue(allowAlwaysEligible(call, store))
        val registry = defaultToolRegistry(gmailApi = gmail, scheduleStore = InMemoryScheduleStore())
        val gated = registry.executeGated(call) { ApprovalDecision.Approved }
        assertTrue(gated.result is ToolResult.Failure)
        assertTrue((gated.result as ToolResult.Failure).error.contains("policy", ignoreCase = true))
        assertTrue(gmail.sentRaws.isEmpty())
    }

    @Test
    fun policyDeniesHeaderInjectionEvenWhenAllowed() {
        val gmail = RecordingGmail()
        val store = InMemoryAllowAlwaysStore()
        store.allow("mom@x.com")
        val registry = defaultToolRegistry(gmailApi = gmail, scheduleStore = InMemoryScheduleStore())
        val gated =
            registry.executeGated(sendCall(to = "mom@x.com\r\nBcc: evil@x.com")) {
                ApprovalDecision.Approved
            }
        assertTrue(gated.result is ToolResult.Failure)
        assertTrue(gmail.sentRaws.isEmpty())
    }

    // --- No blanket allow-all: bulk and non-sends never eligible. ---

    @Test
    fun multiRecipientSendIsNeverEligible() {
        val store = InMemoryAllowAlwaysStore()
        store.allow("mom@x.com")
        store.allow("dad@x.com")
        assertFalse(allowAlwaysEligible(sendCall(to = "mom@x.com, dad@x.com"), store))
    }

    @Test
    fun blankAndWildcardAreNeverStoredOrEligible() {
        val store = InMemoryAllowAlwaysStore()
        store.allow("   ")
        store.allow("*")
        assertTrue(store.list().isEmpty())
        assertFalse(allowAlwaysEligible(sendCall(to = "   "), store))
    }

    @Test
    fun malformedRecipientIsNeverEligible() {
        val store = InMemoryAllowAlwaysStore()
        store.allow("not-an-address")
        assertFalse(allowAlwaysEligible(sendCall(to = "not-an-address"), store))
    }

    @Test
    fun nonSendCallsAreNeverEligible() {
        val store = InMemoryAllowAlwaysStore()
        store.allow("mom@x.com")
        assertFalse(
            allowAlwaysEligible(
                ToolCall("c1", "create_schedule", mapOf("task" to "x", "when" to "at 9am")),
                store,
            ),
        )
        assertFalse(
            allowAlwaysEligible(
                ToolCall("c1", "search_emails", mapOf("query" to "mom@x.com")),
                store,
            ),
        )
    }

    // --- Loop: eligible auto-approves, other sends still ask. ---

    @Test
    fun loopAutoApprovesEligibleAndHoldsUnknown() {
        val gmail = RecordingGmail()
        val store = InMemoryAllowAlwaysStore()
        store.allow("mom@x.com")
        val llm =
            FakeLlmProvider(
                listOf(
                    LlmResult(listOf(sendCall("call_mom", "mom@x.com", "Hi", "hello")), null),
                    LlmResult(listOf(sendCall("call_evil", "stranger@x.com", "Hi", "hello")), null),
                    LlmResult(emptyList(), "done"),
                ),
            )
        val events = mutableListOf<AgentEvent>()
        val result =
            AgentLoop(
                llm,
                defaultToolRegistry(gmailApi = gmail, scheduleStore = InMemoryScheduleStore()),
                onEvent = events::add,
                confirm = { call ->
                    if (allowAlwaysEligible(call, store)) ApprovalDecision.Approved
                    else ApprovalDecision.Rejected
                },
            ).run("email mom and stranger")
        assertTrue(result is AgentLoopResult.Answered)
        assertTrue(result.steps[0].approved)
        assertFalse(result.steps[1].approved)
        assertEquals(1, gmail.sentRaws.size)
        assertEquals(2, events.count { it is AgentEvent.NeedsApproval })
    }

    private fun sendCall(
        id: String,
        to: String,
        subject: String,
        body: String,
    ): ToolCall = ToolCall(id, "send_email", mapOf("to" to to, "subject" to subject, "body" to body))
}
